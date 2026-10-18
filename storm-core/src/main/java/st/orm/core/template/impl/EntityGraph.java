/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.core.template.impl;

import static java.util.Comparator.comparingInt;
import static st.orm.core.spi.Providers.getORMConverter;
import static st.orm.core.template.impl.RecordReflection.findPkField;
import static st.orm.core.template.impl.RecordReflection.getRecordType;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isRecord;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.mapping.RecordField;
import st.orm.mapping.RecordType;

/**
 * The shape of an entity's eager read: the tables it joins and the columns it selects through the entities it declares
 * inline, and the fields whose inline entity a {@code Ref} would cut from it.
 *
 * <p>An entity component joins its table and brings its whole select list, recursively. A {@code Ref} is its foreign
 * key column and stops. An inline record is columns on the same row, so an entity it carries joins from the row that
 * holds it. The design budget of {@code entity-design.md} bounds what a read adds beyond the entity's own columns:
 * 10 joins and 96 columns unless the application sets its own, as {@link Budget} says.</p>
 *
 * @param type the entity.
 * @param ownColumns the columns on the entity's own row.
 * @param joins the tables a read joins.
 * @param columns the columns a read selects beyond the entity's own.
 * @param graph the joined entities, such as {@code Visit(Pet(Owner(City)))}.
 * @param cuts the fields a {@code Ref} could cut, never an identifying foreign key or a target without foreign keys.
 * @since 1.15
 */
record EntityGraph(Class<?> type, int ownColumns, int joins, int columns, String graph, List<Cut> cuts) {

    /**
     * The design budget: the most tables a read may join and the most columns it may select beyond the entity's own.
     * A read from half the budget, in joins or in columns, is wide.
     *
     * @param joins the most tables a read may join; at least one.
     * @param columns the most columns a read may select beyond the entity's own; at least one.
     */
    record Budget(int joins, int columns) {

        /** The budget unless the application sets its own. */
        static final Budget DEFAULT = new Budget(10, 96);

        Budget {
            if (joins < 1 || columns < 1) {
                throw new IllegalArgumentException(
                        "The entity budget must allow at least one join and one column; got %d joins and %d columns."
                                .formatted(joins, columns));
            }
        }

        /** Returns the joins from which a read is wide. */
        int wideJoins() {
            return Math.max(1, joins / 2);
        }

        /** Returns the columns from which a read is wide. */
        int wideColumns() {
            return Math.max(1, columns / 2);
        }
    }

    private static volatile Budget budget = new Budget(
            property(StormConfig.ADVICE_ENTITY_BUDGET_JOINS, Budget.DEFAULT.joins()),
            property(StormConfig.ADVICE_ENTITY_BUDGET_COLUMNS, Budget.DEFAULT.columns()));

    private static int property(String key, int defaultValue) {
        String value = StormConfig.defaults().getProperty(key);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("%s must be a whole number; got '%s'.".formatted(key, value), e);
        }
    }

    /**
     * Returns the design budget in effect.
     *
     * @return the budget.
     */
    static Budget budget() {
        return budget;
    }

    /**
     * Sets the design budget.
     *
     * @param budget the budget.
     */
    static void budget(Budget budget) {
        EntityGraph.budget = budget;
    }

    /**
     * A field whose inline entity a {@code Ref} would cut, with what the cut removes from the graph, summed over every
     * path that reaches the field.
     *
     * @param field the field, as {@code DeclaringType.field}.
     * @param depth the deepest entity level at which the field is reached, {@code 1} for a field of the entity itself.
     * @param joins the joins the cut removes.
     * @param columns the columns the cut removes; the foreign key column it leaves is counted against them.
     * @param paths the paths from the entity that reach the field.
     */
    record Cut(String field, int depth, int joins, int columns, List<String> paths) {

        /**
         * Returns the cut as advice reads it.
         *
         * @return such as {@code a Ref for Clinic.organization removes 4 joins and 52 columns}.
         */
        String describe() {
            return "a Ref for %s removes %s and %s".formatted(field, count(joins, "join"), count(columns, "column"));
        }
    }

    private static final ClassValue<EntityGraph> GRAPHS = new ClassValue<>() {
        @Override
        protected EntityGraph computeValue(Class<?> type) {
            return compute(type);
        }
    };

    /** The advice a slow read of an entity carries, for the budget it was worked out for. */
    private record WideRead(Budget budget, @Nullable String advice) {}

    private static final ClassValue<WideRead> WIDE_READS = new ClassValue<>() {
        @Override
        protected WideRead computeValue(Class<?> type) {
            var current = budget;
            var graph = of(type);
            if (!graph.wide(current) || graph.cuts().isEmpty()) {
                return new WideRead(current, null);
            }
            return new WideRead(current, Advice.WIDE_ENTITY_READ.format(type.getSimpleName(), graph.joins(),
                    graph.columns(), String.join(", and ", graph.belowWide(current).stream()
                            .map(Cut::describe).toList())));
        }
    };

    /**
     * Returns the graph of an entity, computed once per type.
     *
     * @param type the entity.
     * @return the graph.
     */
    static EntityGraph of(Class<?> type) {
        return GRAPHS.get(type);
    }

    /**
     * Returns the advice a slow read of an entity carries where its graph is wide, or {@code null} where it is not or
     * no field can be cut. Computed once per type.
     *
     * @param type the type a select reads.
     * @return the advice, or {@code null}.
     */
    static @Nullable String wideReadAdvice(Class<?> type) {
        if (!Entity.class.isAssignableFrom(type) || type.isInterface() || !isRecord(type)) {
            return null;
        }
        try {
            var read = WIDE_READS.get(type);
            if (!read.budget().equals(budget)) {
                // The budget changed since the advice was worked out, which happens at startup if at all.
                WIDE_READS.remove(type);
                read = WIDE_READS.get(type);
            }
            return read.advice();
        } catch (RuntimeException e) {
            // Advice never stands in the way of a query.
            return null;
        }
    }

    /**
     * Returns whether a read exceeds the design budget.
     *
     * @return {@code true} past the budget's joins or columns.
     */
    boolean overBudget() {
        var current = budget;
        return joins > current.joins() || columns > current.columns();
    }

    /**
     * Returns whether a read is wide: at least half the design budget, in joins or in columns.
     *
     * @return {@code true} from half the budget.
     */
    boolean wide() {
        return wide(budget);
    }

    private boolean wide(Budget budget) {
        return joins >= budget.wideJoins() || columns >= budget.wideColumns();
    }

    /**
     * Returns the cuts that bring the graph within the budget.
     *
     * @return the cuts, empty where no field can be cut.
     * @see #cutsTo(int, int)
     */
    List<Cut> withinBudget() {
        var current = budget;
        return cutsTo(current.joins(), current.columns());
    }

    /**
     * Returns the cuts that bring the graph below half the budget, where a read is no longer wide.
     *
     * @return the cuts, empty where no field can be cut.
     * @see #cutsTo(int, int)
     */
    List<Cut> belowWide() {
        return belowWide(budget);
    }

    private List<Cut> belowWide(Budget budget) {
        return cutsTo(budget.wideJoins() - 1, budget.wideColumns() - 1);
    }

    /**
     * Returns the cuts that bring the graph to at most the given joins and columns: the deepest single cut that does,
     * as a cut near the leaves narrows every entity above it at once and leaves the relationships read through most;
     * otherwise the widest cuts first, at most three.
     */
    private List<Cut> cutsTo(int maxJoins, int maxColumns) {
        var single = cuts.stream()
                .filter(cut -> joins - cut.joins() <= maxJoins && columns - cut.columns() <= maxColumns)
                .max(comparingInt(Cut::depth).thenComparingInt(Cut::columns));
        if (single.isPresent()) {
            return List.of(single.get());
        }
        List<Cut> chosen = new ArrayList<>();
        int remainingJoins = joins;
        int remainingColumns = columns;
        var widestFirst = cuts.stream()
                .sorted(comparingInt(Cut::columns).thenComparingInt(Cut::depth).reversed())
                .toList();
        for (var cut : widestFirst) {
            if (chosen.size() == 3 || (remainingJoins <= maxJoins && remainingColumns <= maxColumns)) {
                break;
            }
            if (inside(cut, chosen)) {
                continue;
            }
            chosen.add(cut);
            remainingJoins -= cut.joins();
            remainingColumns -= cut.columns();
        }
        return chosen;
    }

    /**
     * Returns the advice for a graph past the design budget.
     *
     * @return the advice.
     */
    String budgetAdvice() {
        var current = budget;
        var within = cutsTo(current.joins(), current.columns());
        String remedy = within.isEmpty()
                ? "every joined entity is part of an identifying key or a lookup table without foreign keys, so a "
                        + "projection is the way to read less"
                : String.join(", and ", within.stream().map(Cut::describe).toList());
        return Advice.ENTITY_BUDGET.format(type.getSimpleName(), joins, columns, remedy, current.joins(),
                current.columns());
    }

    private static boolean inside(Cut cut, List<Cut> chosen) {
        return cut.paths().stream().allMatch(path -> chosen.stream()
                .anyMatch(outer -> outer.paths().stream().anyMatch(prefix -> path.startsWith(prefix + "."))));
    }

    private static String count(int count, String noun) {
        return count + " " + noun + (count == 1 ? "" : "s");
    }

    /** A field reached through an inline entity, and what cutting it there removes. */
    private record Occurrence(String field, String path, int depth, int joins, int columns, boolean cuttable) {}

    /** What a record type contributes to a read. */
    private static final class Node {
        int own;
        int joins;
        int joinedColumns;
        boolean foreignKeys;
        final StringBuilder children = new StringBuilder();

        int total() {
            return own + joinedColumns;
        }
    }

    private static EntityGraph compute(Class<?> type) {
        List<Occurrence> occurrences = new ArrayList<>();
        var root = walk(type, 0, "", new HashSet<>(), occurrences);
        var cuts = new LinkedHashMap<String, Cut>();
        for (var occurrence : occurrences) {
            if (!occurrence.cuttable()) {
                continue;
            }
            cuts.merge(occurrence.field(),
                    new Cut(occurrence.field(), occurrence.depth(), occurrence.joins(), occurrence.columns(),
                            List.of(occurrence.path())),
                    (first, second) -> {
                        var paths = new ArrayList<>(first.paths());
                        paths.addAll(second.paths());
                        return new Cut(first.field(), Math.max(first.depth(), second.depth()),
                                first.joins() + second.joins(), first.columns() + second.columns(), List.copyOf(paths));
                    });
        }
        String graph = type.getSimpleName() + (root.children.isEmpty() ? "" : "(" + root.children + ")");
        return new EntityGraph(type, root.own, root.joins, root.joinedColumns, graph, List.copyOf(cuts.values()));
    }

    private static Node walk(Class<?> type, int depth, String path, Set<Class<?>> visiting,
                             List<Occurrence> occurrences) {
        var node = new Node();
        visiting.add(type);
        try {
            fields(getRecordType(type), node, depth, path, visiting, occurrences);
        } finally {
            visiting.remove(type);
        }
        return node;
    }

    private static void fields(RecordType recordType, Node node, int depth, String path, Set<Class<?>> visiting,
                               List<Occurrence> occurrences) {
        for (var field : recordType.fields()) {
            String fieldPath = path.isEmpty() ? field.name() : path + "." + field.name();
            if (field.isAnnotationPresent(FK.class)) {
                node.foreignKeys = true;
                if (Ref.class.isAssignableFrom(field.type())) {
                    node.own += keyColumns(refTarget(field));
                    continue;
                }
                Class<?> target = field.type();
                if (!isRecord(target) || visiting.contains(target)) {
                    // A sealed entity reads the union of its subtypes; a cycle stops at the key. Neither recurses.
                    node.joins += isRecord(target) ? 0 : 1;
                    node.own += keyColumns(target);
                    continue;
                }
                var child = walk(target, depth + 1, fieldPath, visiting, occurrences);
                node.joins += 1 + child.joins;
                node.joinedColumns += child.total();
                if (!node.children.isEmpty()) {
                    node.children.append(", ");
                }
                node.children.append(target.getSimpleName());
                if (!child.children.isEmpty()) {
                    node.children.append('(').append(child.children).append(')');
                }
                // An identifying key is composition, and a target without foreign keys can never grow.
                boolean cuttable = !field.isAnnotationPresent(PK.class) && child.foreignKeys;
                occurrences.add(new Occurrence(field.declaringType().getSimpleName() + "." + field.name(), fieldPath,
                        depth + 1, 1 + child.joins, child.total() - keyColumns(target), cuttable));
                continue;
            }
            var converter = getORMConverter(field);
            if (converter.isPresent()) {
                try {
                    node.own += converter.get().getParameterCount();
                } catch (SqlTemplateException e) {
                    node.own++;
                }
                continue;
            }
            if (isRecord(field.type())) {
                // An inline record is columns on the same row.
                fields(getRecordType(field.type()), node, depth, fieldPath, visiting, occurrences);
                continue;
            }
            node.own++;
        }
    }

    private static Class<?> refTarget(RecordField field) {
        try {
            return getRefDataType(field);
        } catch (SqlTemplateException e) {
            return Object.class;
        }
    }

    private static int keyColumns(Class<?> type) {
        var pk = findPkField(type);
        if (pk.isEmpty()) {
            return 1;
        }
        var pkType = pk.get().type();
        return isRecord(pkType) ? getRecordType(pkType).fields().size() : 1;
    }
}
