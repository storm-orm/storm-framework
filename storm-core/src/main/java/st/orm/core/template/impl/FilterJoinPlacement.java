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

import static java.util.stream.Collectors.joining;
import static st.orm.ResolveScope.INNER;
import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.impl.RecordReflection.findRecordFields;
import static st.orm.core.template.impl.RecordReflection.getFkFields;
import static st.orm.core.template.impl.RecordReflection.getRefDataType;
import static st.orm.core.template.impl.RecordReflection.isTableJoinCandidate;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Elements.Alias;
import st.orm.core.template.impl.Elements.Column;
import st.orm.core.template.impl.Elements.Columns;
import st.orm.core.template.impl.Elements.Correlation;
import st.orm.core.template.impl.Elements.Expression;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.RelationKey;
import st.orm.core.template.impl.Elements.Subquery;
import st.orm.core.template.impl.Elements.TableSource;
import st.orm.core.template.impl.Elements.TableTarget;
import st.orm.core.template.impl.Elements.TemplateExpression;
import st.orm.core.template.impl.Elements.Where;
import st.orm.core.template.impl.PredicateNode.Group;
import st.orm.core.template.impl.PredicateNode.Scope;
import st.orm.core.template.impl.PredicateNode.Term;
import st.orm.mapping.RecordField;

/**
 * Places the conditions of a query's semi-joins and anti-joins in subqueries of its WHERE clause.
 *
 * <p>A condition on a semi-joined table holds when it holds for a related row, and the conditions joined by AND hold
 * for the same related row. A part of the condition that names only the query's own tables holds without one, so a
 * row without related rows still matches through its own columns. Each {@link Scope} is answered on its own, which is
 * how every word of a search may match a different related row.</p>
 *
 * <p>A condition on a semi-joined table renders as {@code EXISTS} over the related rows. Where the condition is an OR
 * of terms on the query's table and terms on the semi-joined table, the common shape of a search, it renders as an
 * {@code IN} over a {@code UNION} of a branch per side instead: each branch reads its own table through its own
 * indexes, where an OR across two tables reads every related row. An anti-join renders as {@code NOT EXISTS} over the
 * rows matching its join condition.</p>
 *
 * @since 1.15
 */
final class FilterJoinPlacement {

    /**
     * How a filtered table relates to the table it is joined on.
     *
     * @param foreignKey the foreign key field that relates them.
     * @param tableHoldsKey {@code true} if the filtered table holds the foreign key, {@code false} if the table it is
     *                      joined on does.
     * @param composite whether the key spans several columns.
     */
    private record Relation(RecordField foreignKey, boolean tableHoldsKey, boolean composite) {}

    /**
     * A semi-join or anti-join.
     *
     * @param table the filtered table.
     * @param anchor the table it is joined on.
     * @param relation how the two relate.
     * @param condition the condition stated on the join, or {@code null}.
     * @param type the join type.
     */
    private record FilterJoin(Class<? extends Data> table,
                              Class<? extends Data> anchor,
                              Relation relation,
                              @Nullable PredicateNode condition,
                              FilterJoinType type) {}

    private final QueryTemplate queryTemplate;
    private final boolean branchOnAnchor;
    private final Map<Class<? extends Data>, FilterJoin> semiJoins = new LinkedHashMap<>();
    private final Map<Class<? extends Data>, FilterJoin> antiJoins = new LinkedHashMap<>();

    /**
     * @param queryTemplate the template the query builds its subqueries with.
     * @param joins the query's semi-joins and anti-joins.
     * @param branchOnAnchor whether a subquery may read the table a semi-join is joined on, which a DELETE refuses on
     *                       some databases for the table it deletes from.
     */
    private FilterJoinPlacement(QueryTemplate queryTemplate, List<Join> joins, boolean branchOnAnchor) {
        this.queryTemplate = queryTemplate;
        this.branchOnAnchor = branchOnAnchor;
        for (var join : joins) {
            var filterJoin = filterJoin(queryTemplate, join);
            (filterJoin.type() == FilterJoinType.SEMI ? semiJoins : antiJoins).put(filterJoin.table(), filterJoin);
        }
    }

    /**
     * Returns whether the join is a semi-join or anti-join.
     */
    static boolean isFilterJoin(Join join) {
        return join.type() instanceof FilterJoinType;
    }

    /**
     * Verifies that a semi-join or anti-join can be added to a query that already holds the given joins, and that
     * its table relates to the table it is joined on.
     *
     * @param queryTemplate the template the query belongs to.
     * @param fromType the query's table.
     * @param joins the query's joins so far.
     * @param join the join to add.
     * @throws PersistenceException if the join cannot be added.
     */
    static void verify(QueryTemplate queryTemplate, Class<? extends Data> fromType, List<Join> joins, Join join) {
        var type = (FilterJoinType) join.type();
        var table = ((TableSource) join.source()).table();
        if (table == fromType) {
            throw new PersistenceException("%s(%s): %s is the query's own table. A %s filters the rows of the query by the rows of another table."
                    .formatted(type.method(), table.getSimpleName(), table.getSimpleName(), type.method()));
        }
        for (var existing : joins) {
            if (existing.source() instanceof TableSource(var existingTable) && existingTable == table) {
                throw new PersistenceException("%s(%s): the query already joins %s, so a condition naming it could not tell the joins apart."
                        .formatted(type.method(), table.getSimpleName(), table.getSimpleName()));
            }
        }
        filterJoin(queryTemplate, join);
    }

    /**
     * Renders the WHERE clause of a query with semi-joins or anti-joins.
     *
     * @param queryTemplate the template the query builds its subqueries with.
     * @param filterJoins the query's semi-joins and anti-joins.
     * @param where the query's WHERE conditions.
     * @param clauses the query's other clauses, which may not name a filtered table.
     * @param branchOnAnchor whether a subquery may read the table a semi-join is joined on.
     * @return the WHERE condition, or {@code null} for none.
     */
    static @Nullable TemplateString where(QueryTemplate queryTemplate,
                                          List<Join> filterJoins,
                                          List<Where> where,
                                          List<TemplateString> clauses,
                                          boolean branchOnAnchor) {
        var placement = new FilterJoinPlacement(queryTemplate, filterJoins, branchOnAnchor);
        placement.verifyClauses(clauses);
        return placement.where(where);
    }

    private @Nullable TemplateString where(List<Where> where) {
        var conjuncts = new ArrayList<PredicateNode>();
        for (var condition : where) {
            conjuncts.add(node(condition));
        }
        for (var antiJoin : antiJoins.values()) {
            for (var conjunct : conjuncts) {
                if (references(conjunct).contains(antiJoin.table())) {
                    throw new PersistenceException("A condition names the anti-joined %1$s, but the query keeps the rows without a matching %1$s, so the condition belongs to the join: antiJoin(%1$s).on(%2$s, condition)."
                            .formatted(antiJoin.table().getSimpleName(), antiJoin.anchor().getSimpleName()));
                }
            }
        }
        for (var semiJoin : semiJoins.values()) {
            if (semiJoin.condition() != null) {
                conjuncts.add(semiJoin.condition());
            }
        }
        for (var semiJoin : semiJoins.values()) {
            if (conjuncts.stream().noneMatch(conjunct -> references(conjunct).contains(semiJoin.table()))) {
                throw new PersistenceException("semiJoin(%1$s) filters nothing: no condition names %1$s. Name it in a condition, or state one on the join: semiJoin(%1$s).on(%2$s, condition)."
                        .formatted(semiJoin.table().getSimpleName(), semiJoin.anchor().getSimpleName()));
            }
        }
        var parts = new ArrayList<PredicateNode>();
        if (!conjuncts.isEmpty()) {
            parts.add(place(PredicateNode.and(conjuncts)));
        }
        for (var antiJoin : antiJoins.values()) {
            parts.add(new Term(exists(antiJoin, antiJoin.condition()), true));
        }
        return parts.isEmpty() ? null : PredicateNode.and(parts).toTemplate();
    }

    /**
     * Refuses a filtered table in the clauses that order, group or select: it adds no columns to the query.
     */
    private void verifyClauses(List<TemplateString> clauses) {
        for (var clause : clauses) {
            var tables = new LinkedHashSet<Class<? extends Data>>();
            collect(clause, tables);
            for (var table : tables) {
                var filterJoin = semiJoins.containsKey(table) ? semiJoins.get(table) : antiJoins.get(table);
                if (filterJoin != null) {
                    throw new PersistenceException("%1$s is %2$s: it filters the query but adds no columns to it, so the query cannot order, group or select by its columns. Join %1$s to use its columns."
                            .formatted(table.getSimpleName(), filterJoin.type() == FilterJoinType.SEMI ? "semi-joined" : "anti-joined"));
                }
            }
        }
    }

    /**
     * Places the conditions on semi-joined tables of a predicate, answering the predicate as one scope: its terms
     * joined by AND on one semi-joined table hold for the same related row.
     */
    private PredicateNode place(PredicateNode node) {
        var parts = new ArrayList<PredicateNode>();
        var groups = new LinkedHashMap<FilterJoin, List<PredicateNode>>();
        for (var conjunct : conjuncts(node)) {
            if (conjunct instanceof Scope(var inner) && !semiJoinsOf(inner).isEmpty()) {
                parts.add(place(inner));
                continue;
            }
            var named = semiJoinsOf(conjunct);
            if (named.isEmpty()) {
                parts.add(conjunct);
            } else if (named.size() > 1) {
                throw new PersistenceException("A condition names the semi-joined %s. Each condition on a semi-joined table is answered by a row of that table, so join conditions on different semi-joined tables with AND."
                        .formatted(named.stream().map(join -> join.table().getSimpleName()).collect(joining(" and "))));
            } else {
                groups.computeIfAbsent(named.iterator().next(), ignore -> new ArrayList<>()).add(conjunct);
            }
        }
        for (var group : groups.entrySet()) {
            parts.add(placeGroup(group.getKey(), group.getValue()));
        }
        return PredicateNode.and(parts);
    }

    /**
     * Places the conditions of one scope that name one semi-joined table.
     */
    private PredicateNode placeGroup(FilterJoin semiJoin, List<PredicateNode> conditions) {
        if (conditions.size() == 1 && conditions.getFirst() instanceof Group(var and, var operands) && !and) {
            var union = union(semiJoin, operands);
            if (union != null) {
                return new Term(union, true);
            }
        }
        var condition = PredicateNode.and(conditions);
        var exists = new Term(exists(semiJoin, condition), true);
        // The rows without a related row match through the part of the condition that names none.
        var withoutRelated = withoutTable(condition, semiJoin.table());
        return withoutRelated == null ? exists : PredicateNode.or(List.of(withoutRelated, exists));
    }

    /**
     * Returns the OR of terms on the anchor and terms on the semi-joined table as an {@code IN} over a {@code UNION},
     * or {@code null} when the operands or the relation do not take that shape.
     */
    private @Nullable TemplateString union(FilterJoin semiJoin, List<PredicateNode> operands) {
        var relation = semiJoin.relation();
        if (!branchOnAnchor || !relation.tableHoldsKey() || relation.composite()) {
            return null;
        }
        var anchorTerms = new ArrayList<PredicateNode>();
        var tableTerms = new ArrayList<PredicateNode>();
        for (var operand : operands) {
            if (onlyNames(operand, semiJoin.anchor())) {
                anchorTerms.add(operand);
            } else if (onlyNames(operand, semiJoin.table())) {
                tableTerms.add(operand);
            } else {
                return null;
            }
        }
        if (anchorTerms.isEmpty() || tableTerms.isEmpty()) {
            return null;
        }
        var foreignKey = relation.foreignKey();
        var anchorBranch = branch(semiJoin.anchor(), new RelationKey(semiJoin.anchor(), foreignKey, false, INNER), PredicateNode.or(anchorTerms));
        var tableBranch = branch(semiJoin.table(), new RelationKey(semiJoin.table(), foreignKey, true, INNER), PredicateNode.or(tableTerms));
        return TemplateString.raw("\0 IN (\0\nUNION\n\0)",
                new RelationKey(semiJoin.anchor(), foreignKey, false, INNER),
                new Subquery(anchorBranch, false),
                new Subquery(tableBranch, false));
    }

    /**
     * Returns a subquery selecting the given key from the table's rows that match the condition.
     */
    private TemplateString branch(Class<? extends Data> table, RelationKey key, PredicateNode condition) {
        var subquery = (QueryBuilderImpl<?, ?, ?>) queryTemplate.subquery(table, wrap(key));
        return subquery.withCondition(condition).getSubquery();
    }

    /**
     * Returns {@code EXISTS}, or for an anti-join {@code NOT EXISTS}, over the related rows matching the condition.
     */
    private TemplateString exists(FilterJoin filterJoin, @Nullable PredicateNode condition) {
        var relation = filterJoin.relation();
        PredicateNode correlated = new Term(wrap(new Correlation(filterJoin.table(), filterJoin.anchor(), relation.foreignKey(), relation.tableHoldsKey())), true);
        if (condition != null) {
            correlated = PredicateNode.and(List.of(correlated, condition));
        }
        var subquery = (QueryBuilderImpl<?, ?, ?>) queryTemplate.subquery(filterJoin.table(), TemplateString.of("1"));
        var template = subquery.withCondition(correlated).getSubquery();
        return TemplateString.raw(filterJoin.type() == FilterJoinType.SEMI ? "EXISTS (\0)" : "NOT EXISTS (\0)",
                new Subquery(template, true));
    }

    /**
     * Returns the predicate with every term naming the table taken as false, or {@code null} when the predicate is
     * then false: the condition a row without a related row is held to.
     */
    private static @Nullable PredicateNode withoutTable(PredicateNode node, Class<? extends Data> table) {
        return switch (node) {
            case Term term -> references(term).contains(table) ? null : term;
            case Scope(var inner) -> withoutTable(inner, table);
            case Group(var and, var operands) -> {
                var kept = new ArrayList<PredicateNode>(operands.size());
                for (var operand : operands) {
                    var without = withoutTable(operand, table);
                    if (without == null) {
                        if (and) {
                            yield null;
                        }
                    } else {
                        kept.add(without);
                    }
                }
                yield kept.isEmpty() ? null : and ? PredicateNode.and(kept) : PredicateNode.or(kept);
            }
        };
    }

    /**
     * Returns the operands of a predicate joined by AND, or the predicate itself.
     */
    private static List<PredicateNode> conjuncts(PredicateNode node) {
        return node instanceof Group(var and, var operands) && and ? operands : List.of(node);
    }

    /**
     * Returns the semi-joins whose table the predicate names.
     */
    private Set<FilterJoin> semiJoinsOf(PredicateNode node) {
        var named = new LinkedHashSet<FilterJoin>();
        for (var table : references(node)) {
            var semiJoin = semiJoins.get(table);
            if (semiJoin != null) {
                named.add(semiJoin);
            }
        }
        return named;
    }

    /**
     * Returns whether every term of the predicate names the table and no other, through typed references alone, so the
     * predicate can move into a subquery reading only that table.
     */
    private static boolean onlyNames(PredicateNode node, Class<? extends Data> table) {
        return switch (node) {
            case Term(var template, var ignore) -> {
                if (template.fragments().stream().anyMatch(fragment -> !fragment.isBlank())) {
                    yield false;    // Text of its own may name any alias of the enclosing query.
                }
                var tables = new LinkedHashSet<Class<? extends Data>>();
                collect(template, tables);
                yield tables.equals(Set.of(table));
            }
            case Scope(var inner) -> onlyNames(inner, table);
            case Group(var ignore, var operands) -> operands.stream().allMatch(operand -> onlyNames(operand, table));
        };
    }

    /**
     * Returns the tables the terms of a predicate name through typed references.
     */
    private static Set<Class<? extends Data>> references(PredicateNode node) {
        var tables = new LinkedHashSet<Class<? extends Data>>();
        collect(node, tables);
        return tables;
    }

    private static void collect(PredicateNode node, Set<Class<? extends Data>> tables) {
        switch (node) {
            case Term(var template, var ignore) -> collect(template, tables);
            case Scope(var inner) -> collect(inner, tables);
            case Group(var ignore, var operands) -> operands.forEach(operand -> collect(operand, tables));
        }
    }

    private static void collect(TemplateString template, Set<Class<? extends Data>> tables) {
        for (var value : template.values()) {
            collect(value, tables);
        }
    }

    @SuppressWarnings("unchecked")
    private static void collect(@Nullable Object value, Set<Class<? extends Data>> tables) {
        switch (value) {
            case null -> { }
            case TemplateString template -> collect(template, tables);
            case Where where -> collect(where.expression(), tables);
            case ObjectExpression expression -> {
                if (expression.metamodel() != null) {
                    tables.add(expression.metamodel().root());
                } else if (expression.object() instanceof Data data) {
                    tables.add(data.getClass());
                }
            }
            case TemplateExpression expression -> collect(expression.template(), tables);
            case Navigable<?, ?> path -> tables.add(path.root());
            case Column column -> tables.add(column.field().root());
            case Columns columns -> columns.fields().forEach(field -> tables.add(field.root()));
            case Alias alias -> tables.add(alias.table());
            case Class<?> type when Data.class.isAssignableFrom(type) -> tables.add((Class<? extends Data>) type);
            default -> { }  // Parameters, and subqueries, which resolve their own tables.
        }
    }

    /**
     * Returns the predicate of a WHERE condition: the structure a predicate builder recorded, or the condition as one
     * term.
     */
    private static PredicateNode node(Where where) {
        if (where.expression() instanceof TemplateExpression(var ignore, var predicate) && predicate != null) {
            return predicate;
        }
        Expression expression = where.expression();
        return new Term(expression == null ? wrap(where) : wrap(expression), true);
    }

    private static FilterJoin filterJoin(QueryTemplate queryTemplate, Join join) {
        var type = (FilterJoinType) join.type();
        var table = ((TableSource) join.source()).table();
        var target = (TableTarget) join.target();
        return new FilterJoin(table, target.table(), relation(queryTemplate, type, table, target.table()), target.condition(), type);
    }

    /**
     * Resolves how the filtered table relates to the table it is joined on, the way a join between them resolves its
     * ON clause: a foreign key referencing the other type, then one referencing the other type's table.
     */
    private static Relation relation(QueryTemplate queryTemplate,
                                     FilterJoinType type,
                                     Class<? extends Data> table,
                                     Class<? extends Data> anchor) {
        try {
            var anchorKey = single(type, anchor, table, findRecordFields(getFkFields(anchor).toList(), table));
            if (anchorKey != null) {
                return relation(queryTemplate, anchorKey, false, table);
            }
            var tableKey = single(type, table, anchor, findRecordFields(getFkFields(table).toList(), anchor));
            if (tableKey != null) {
                return relation(queryTemplate, tableKey, true, anchor);
            }
            anchorKey = single(type, anchor, table, byTable(queryTemplate, getFkFields(anchor).toList(), table));
            if (anchorKey != null) {
                return relation(queryTemplate, anchorKey, false, table);
            }
            tableKey = single(type, table, anchor, byTable(queryTemplate, getFkFields(table).toList(), anchor));
            if (tableKey != null) {
                return relation(queryTemplate, tableKey, true, anchor);
            }
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
        throw new PersistenceException("%1$s(%2$s).on(%3$s): no foreign key relates %2$s and %3$s. A %1$s needs one of them to hold a @FK field referencing the other."
                .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName()));
    }

    private static Relation relation(QueryTemplate queryTemplate, RecordField foreignKey, boolean tableHoldsKey, Class<? extends Data> referenced) {
        var composite = queryTemplate.model(referenced, false).getPrimaryKeyMetamodel()
                .map(Metamodel::isInline)
                .orElse(false);
        return new Relation(foreignKey, tableHoldsKey, composite);
    }

    private static @Nullable RecordField single(FilterJoinType type,
                                                Class<? extends Data> holder,
                                                Class<? extends Data> referenced,
                                                List<RecordField> matches) {
        if (matches.size() > 1) {
            throw new PersistenceException("%s: %s references %s through more than one foreign key (fields: %s), so the relation to filter by is ambiguous. Use an explicit subquery with exists to choose one."
                    .formatted(type.method(), holder.getSimpleName(), referenced.getSimpleName(), matches.stream().map(RecordField::name).collect(joining(", "))));
        }
        return matches.isEmpty() ? null : matches.getFirst();
    }

    /**
     * Returns the foreign key fields referencing a type that maps the same table as the given type, which relates a
     * projection of a table to the foreign keys referencing the table's entity.
     */
    @SuppressWarnings("unchecked")
    private static List<RecordField> byTable(QueryTemplate queryTemplate,
                                             List<RecordField> fields,
                                             Class<? extends Data> table) throws SqlTemplateException {
        if (!isTableJoinCandidate(table)) {
            return List.of();
        }
        var model = queryTemplate.model(table, false);
        var matches = new ArrayList<RecordField>();
        for (var field : fields) {
            Class<?> target = Ref.class.isAssignableFrom(field.type()) ? getRefDataType(field) : field.type();
            if (!Data.class.isAssignableFrom(target)) {
                continue;
            }
            var targetModel = queryTemplate.model((Class<? extends Data>) target, false);
            if (targetModel.name().equals(model.name()) && targetModel.schema().equals(model.schema())) {
                matches.add(field);
            }
        }
        return matches;
    }
}
