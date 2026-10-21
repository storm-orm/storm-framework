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

import static java.util.Objects.requireNonNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import st.orm.Operator;

/**
 * The columns a statement asks an index for, per table: the columns it compares for equality, the columns it
 * compares by range, and the column it sorts a limited result by; and for a delete, the table it deletes from, whose
 * referencing foreign keys the database checks for every row it deletes.
 *
 * <p>Demands are recorded while the statement compiles, from the parts of it Storm builds: a metamodel predicate, the
 * correlation of a semi-join or anti-join, the ordering of a limited result, and the table of a delete. A raw template
 * fragment is opaque and demands nothing. A compiled statement is cached per shape, so the demands are recorded once
 * per shape and every execution of it carries the same instance.</p>
 *
 * <p>The check against an {@link IndexCatalog} runs only for a slow execution, and its outcome is kept on the
 * instance for the catalog it was decided against: a shape that is slow again is answered without a second look, and
 * a new catalog, published by a later schema read, is decided anew.</p>
 *
 * @since 1.16
 */
final class IndexDemands {

    /**
     * How a statement uses a column.
     */
    enum Kind {
        /** Compared for equality, as {@code =} and {@code IN} compare. */
        EQUALITY,
        /** Compared by range, as {@code <}, {@code >} and {@code BETWEEN} compare. */
        RANGE,
        /** Sorted by, for a result the statement limits. */
        ORDER
    }

    /**
     * Returns how a predicate with the given operator uses its column, or {@code null} when an ordinary index does
     * not serve it: a negation, a pattern match, or a comparison that applies a function to the column.
     *
     * @param operator the predicate's operator.
     * @return the kind of use, or {@code null}.
     */
    static @Nullable Kind kindOf(Operator operator) {
        if (operator == Operator.EQUALS || operator == Operator.IN) {
            return Kind.EQUALITY;
        }
        if (operator == Operator.GREATER_THAN || operator == Operator.GREATER_THAN_OR_EQUAL
                || operator == Operator.LESS_THAN || operator == Operator.LESS_THAN_OR_EQUAL
                || operator == Operator.BETWEEN) {
            return Kind.RANGE;
        }
        return null;
    }

    /**
     * The demands on one table.
     *
     * @param schema the table's schema as its model names it, {@code ""} for the default schema.
     * @param table the table name.
     * @param equality the columns compared for equality, in the order the statement names them.
     * @param range the columns compared by range, in order.
     * @param order the columns sorted by, in order.
     */
    private record TableDemands(String schema, String table, List<String> equality, List<String> range,
                                List<String> order) {
    }

    /**
     * A table a delete removes rows from.
     *
     * @param schema the table's schema as its model names it, {@code ""} for the default schema.
     * @param table the table name.
     */
    private record DeleteTarget(String schema, String table) {
    }

    /**
     * The decision for one catalog.
     */
    private record Decision(IndexCatalog catalog, List<String> advice) {
    }

    private final List<TableDemands> tables;
    private final @Nullable DeleteTarget deleted;
    private volatile @Nullable Decision decision;

    private IndexDemands(List<TableDemands> tables, @Nullable DeleteTarget deleted) {
        this.tables = tables;
        this.deleted = deleted;
    }

    /**
     * Collects the demands of a statement while it compiles. Not thread-safe; a statement compiles on one thread.
     */
    static final class Builder {
        private final Map<String, TableDemands> tables = new LinkedHashMap<>();
        private @Nullable DeleteTarget deleted;

        /**
         * Records that the statement uses the given column of the given table.
         *
         * @param schema the table's schema as its model names it, {@code ""} for the default schema.
         * @param table the table name.
         * @param column the column name.
         * @param kind how the statement uses the column.
         */
        void add(String schema, String table, String column, Kind kind) {
            requireNonNull(schema, "schema");
            requireNonNull(table, "table");
            requireNonNull(column, "column");
            var demands = tables.computeIfAbsent(
                    schema.toLowerCase(Locale.ROOT) + '\0' + table.toLowerCase(Locale.ROOT),
                    ignore -> new TableDemands(schema, table, new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            var columns = switch (kind) {
                case EQUALITY -> demands.equality();
                case RANGE -> demands.range();
                case ORDER -> demands.order();
            };
            if (columns.stream().noneMatch(column::equalsIgnoreCase)) {
                columns.add(column);
            }
        }

        /**
         * Records that the statement deletes rows from the given table.
         *
         * @param schema the table's schema as its model names it, {@code ""} for the default schema.
         * @param table the table name.
         */
        void deleteFrom(String schema, String table) {
            deleted = new DeleteTarget(requireNonNull(schema, "schema"), requireNonNull(table, "table"));
        }

        /**
         * Returns the demands, or {@code null} when the statement made none.
         */
        @Nullable IndexDemands build() {
            if (tables.isEmpty() && deleted == null) {
                return null;
            }
            return new IndexDemands(tables.values().stream()
                    .map(demands -> new TableDemands(demands.schema(), demands.table(), List.copyOf(demands.equality()),
                            List.copyOf(demands.range()), List.copyOf(demands.order())))
                    .toList(), deleted);
        }
    }

    /**
     * Returns the index advice for the statement: one piece for each table none of whose indexes starts with a column
     * the statement asks for, and for a delete, one for each foreign key that references the table it deletes from
     * without an index to serve the database's check of it. A table the catalog does not know gets none, since whether
     * an index serves it cannot be decided.
     *
     * @param catalog the catalog of the database the statement ran on, or {@code null} when its schema was not read.
     * @return the advice, empty when every table is served or none can be decided.
     */
    List<String> advise(@Nullable IndexCatalog catalog) {
        if (catalog == null) {
            return List.of();
        }
        var decided = decision;
        if (decided != null && decided.catalog() == catalog) {
            return decided.advice();
        }
        var advice = new ArrayList<String>(1);
        for (var demands : tables) {
            var indexes = catalog.find(demands.schema(), demands.table());
            if (indexes == null || served(demands, indexes)) {
                continue;
            }
            var asked = new ArrayList<String>(demands.equality());
            addAbsent(asked, demands.range());
            addAbsent(asked, demands.order());
            String table = qualified(demands.schema(), demands.table());
            boolean filters = !demands.equality().isEmpty() || !demands.range().isEmpty();
            boolean sorts = !demands.order().isEmpty();
            String cost = !filters
                    ? "which the statement sorts its limited result by, so the database sorts every row before it "
                            + "returns the first ones"
                    : "which the statement %s %s by, so the database reads the table in full to find the matching rows"
                            .formatted(sorts ? "filters or sorts" : "filters", table);
            String remedy = !filters
                    ? "reads the rows in order and stops at the limit"
                    : "finds them directly where the condition selects few of them";
            advice.add(Advice.MISSING_INDEX.format(table, String.join(" or ", asked), cost,
                    "%s(%s)".formatted(table, String.join(", ", suggested(demands))), remedy));
        }
        if (deleted != null) {
            var indexes = catalog.find(deleted.schema(), deleted.table());
            if (indexes != null) {
                for (var reference : indexes.unindexedReferences()) {
                    String referencing = qualified(deleted.schema(), reference.table());
                    advice.add(Advice.UNINDEXED_FOREIGN_KEY.format(qualified(deleted.schema(), deleted.table()),
                            referencing, String.join(", ", reference.columns()),
                            String.join(" or ", reference.columns())));
                }
            }
        }
        var result = List.copyOf(advice);
        // Benign race: two threads may decide the same catalog; both reach the same advice.
        decision = new Decision(catalog, result);
        return result;
    }

    /**
     * Returns whether an index of the table starts with a column the statement asks for. Which of them the database
     * uses, and how selective it is, the catalog cannot tell; any one is a path that avoids reading the table in full.
     */
    private static boolean served(TableDemands demands, IndexCatalog.TableIndexes indexes) {
        return demands.equality().stream().anyMatch(indexes::leadsWith)
                || demands.range().stream().anyMatch(indexes::leadsWith)
                || demands.order().stream().anyMatch(indexes::leadsWith);
    }

    /**
     * Returns the columns of the index to suggest: the equality columns, then one range column, or failing that, one
     * column the statement sorts by. An index serves equality on any of its leading columns and a range or an order
     * on the one after them.
     */
    private static List<String> suggested(TableDemands demands) {
        var columns = new ArrayList<String>(demands.equality());
        var next = !demands.range().isEmpty() ? demands.range() : demands.order();
        for (var column : next) {
            if (columns.stream().noneMatch(column::equalsIgnoreCase)) {
                columns.add(column);
                break;
            }
        }
        return columns;
    }

    /**
     * Returns the table name qualified by its schema, or the bare name in the default schema.
     */
    private static String qualified(String schema, String table) {
        return schema.isEmpty() ? table : schema + "." + table;
    }

    private static void addAbsent(List<String> target, List<String> columns) {
        for (var column : columns) {
            if (target.stream().noneMatch(column::equalsIgnoreCase)) {
                target.add(column);
            }
        }
    }
}
