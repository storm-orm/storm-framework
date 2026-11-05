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
 * <p>Usage is recorded while the statement compiles, from the parts of it Storm builds: a metamodel predicate, the
 * correlation of a semi-join or anti-join, the ordering of a limited result, and the table of a delete. A raw template
 * fragment is opaque and requires nothing. A compiled statement is cached per shape, so the usage is recorded once
 * per shape and every execution of it carries the same instance.</p>
 *
 * <p>The check against an {@link IndexCatalog} runs only for a slow execution, and its outcome is kept on the
 * instance for the catalog it was decided against: a shape that is slow again is answered without a second look, and
 * a new catalog, published by a later schema read, is decided anew.</p>
 *
 * @since 1.16
 */
final class IndexUsage {

    /**
     * How a statement uses a column.
     */
    enum Kind {
        /** Compared for equality, as {@code =} and {@code IN} compare. */
        EQUALITY,
        /** Compared by range, as {@code <}, {@code >} and {@code BETWEEN} compare. */
        RANGE,
        /** Sorted by, for a result the statement limits. */
        ORDER,
        /** Grouped by, which an index read in its order serves without sorting the rows into groups. */
        GROUP,
        /** The key a to-many join reads the joined table by, compared for equality with each joined row's key. */
        JOIN,
        /** Read by the select list, which an index that also holds it serves without reading the table. */
        SELECT,
        /**
         * Read by a select list that names every column it reads, so that an index holding these columns serves it
         * without reading the table for certain; recorded with no column for a select that reads none, such as a
         * count.
         */
        WHOLE_SELECT
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
     * The usage of one table.
     *
     * @param schema the table's schema as its model names it, {@code ""} for the default schema.
     * @param table the table name.
     * @param equality the columns compared for equality, in the order the statement names them.
     * @param range the columns compared by range, in order.
     * @param order the columns sorted by, in order.
     * @param group the columns grouped by, in order.
     * @param join the key columns a to-many join reads the table by, in order.
     * @param select the columns the select list reads, where it names them through the metamodel.
     */
    private record TableUsage(String schema, String table, List<String> equality, List<String> range,
                                List<String> order, List<String> group, List<String> join, List<String> select) {
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
     * A field of a {@code search} or {@code suggest} with the form it matches in.
     *
     * @param schema the table's schema as its model names it, {@code ""} for the default schema.
     * @param table the table name.
     * @param column the column name.
     * @param form the match form.
     * @param field the column as the match names it, bare or wrapped in its function calls.
     * @param expression the expression the match compares, rendered around the bare column name.
     */
    private record SearchField(String schema, String table, String column, MatchForm form, String field,
                               String expression) {

        /** Returns whether the match compares the bare column, with no function around it. */
        boolean bare() {
            return expression.equalsIgnoreCase(column);
        }

        /** Returns the match as the API names it, such as {@code wordStart(title)}. */
        String match() {
            return form.factory() + "(" + field + ")";
        }
    }

    /**
     * The decision for one catalog.
     */
    private record Decision(IndexCatalog catalog, List<String> advice) {
    }

    private final List<TableUsage> tables;
    private final List<SearchField> searches;
    private final @Nullable DeleteTarget deleted;
    /** Whether the select names every column it reads, so an index that holds them is known to cover it. */
    private final boolean selectKnown;
    private volatile @Nullable Decision decision;

    private IndexUsage(List<TableUsage> tables, List<SearchField> searches, @Nullable DeleteTarget deleted,
                         boolean selectKnown) {
        this.tables = tables;
        this.searches = searches;
        this.deleted = deleted;
        this.selectKnown = selectKnown;
    }

    /**
     * Collects the usage of a statement while it compiles. Not thread-safe; a statement compiles on one thread.
     */
    static final class Builder {
        private final Map<String, TableUsage> tables = new LinkedHashMap<>();
        private final Map<String, SearchField> searches = new LinkedHashMap<>();
        private @Nullable DeleteTarget deleted;
        private boolean selectKnown;

        /**
         * Records that a search of the statement matches the given column of the given table in a match form.
         *
         * @param schema the table's schema as its model names it, {@code ""} for the default schema.
         * @param table the table name.
         * @param column the column name.
         * @param form the match form.
         * @param field the column as the match names it, bare or wrapped in its function calls.
         * @param expression the expression the match compares, rendered around the bare column name.
         */
        void addSearch(String schema, String table, String column, MatchForm form, String field, String expression) {
            requireNonNull(schema, "schema");
            requireNonNull(table, "table");
            requireNonNull(column, "column");
            searches.putIfAbsent(
                    schema.toLowerCase(Locale.ROOT) + '\0' + table.toLowerCase(Locale.ROOT) + '\0'
                            + expression.toLowerCase(Locale.ROOT) + '\0' + form,
                    new SearchField(schema, table, column, form, field, expression));
        }

        /**
         * Records that the statement uses the given column of the given table.
         *
         * @param schema the table's schema as its model names it, {@code ""} for the default schema.
         * @param table the table name.
         * @param column the column name; empty for a {@link Kind#WHOLE_SELECT} that reads no column of the table.
         * @param kind how the statement uses the column.
         */
        void add(String schema, String table, String column, Kind kind) {
            requireNonNull(schema, "schema");
            requireNonNull(table, "table");
            requireNonNull(column, "column");
            if (kind == Kind.WHOLE_SELECT) {
                selectKnown = true;
                if (column.isEmpty()) {
                    return;
                }
            }
            var usage = tables.computeIfAbsent(
                    schema.toLowerCase(Locale.ROOT) + '\0' + table.toLowerCase(Locale.ROOT),
                    ignore -> new TableUsage(schema, table, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(),
                            new ArrayList<>(), new ArrayList<>(), new ArrayList<>()));
            var columns = switch (kind) {
                case EQUALITY -> usage.equality();
                case RANGE -> usage.range();
                case ORDER -> usage.order();
                case GROUP -> usage.group();
                case JOIN -> usage.join();
                case SELECT, WHOLE_SELECT -> usage.select();
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
         * Returns the usage, or {@code null} when the statement uses no column.
         */
        @Nullable IndexUsage build() {
            if (tables.isEmpty() && searches.isEmpty() && deleted == null) {
                return null;
            }
            return new IndexUsage(tables.values().stream()
                    .map(usage -> new TableUsage(usage.schema(), usage.table(), List.copyOf(usage.equality()),
                            List.copyOf(usage.range()), List.copyOf(usage.order()), List.copyOf(usage.group()),
                            List.copyOf(usage.join()), List.copyOf(usage.select())))
                    .toList(), List.copyOf(searches.values()), deleted, selectKnown);
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
        for (var usage : tables) {
            var indexes = catalog.find(usage.schema(), usage.table());
            if (indexes == null) {
                continue;
            }
            var piece = advice(usage, indexes, catalog.maxKeyBytes());
            if (piece != null) {
                advice.add(piece);
            }
        }
        for (var search : searches) {
            var indexes = catalog.find(search.schema(), search.table());
            if (indexes == null) {
                continue;
            }
            var piece = searchAdvice(search, indexes, catalog.dialect().substringIndex());
            if (piece != null) {
                advice.add(piece);
            }
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
     * Returns the advice on one table, or {@code null} where its indexes serve the statement. Two things can need an
     * index: finding the matching rows, and reading them in the order the statement needs, grouped or sorted for a
     * limited result. Finding them is served once an index starts with a column the statement filters, joins, sorts
     * or groups by; which one the database uses, and how selective it is, no index list tells. Reading them in order
     * is served by an index that starts with the equality columns and continues with the group columns, or with the
     * sort columns where no range competes; a table whose filter is indexed but whose order is not is advised the
     * index that continues it.
     *
     * @param maxKeyBytes the most bytes an index key may hold on the database, {@code 0} where it checks no
     *                    declared size.
     */
    private @Nullable String advice(TableUsage usage, IndexCatalog.TableIndexes indexes, int maxKeyBytes) {
        var keys = new ArrayList<String>(usage.equality());
        addAbsent(keys, usage.join());
        var range = usage.range();
        boolean groups = !usage.group().isEmpty();
        boolean filters = !keys.isEmpty() || !range.isEmpty();
        // An equality on every column of a unique index selects at most one row, which needs no index to sort or
        // group, and has one to find it.
        if (indexes.indexes().stream().anyMatch(index -> index.unique()
                && index.columns().stream().allMatch(column -> containsIgnoreCase(keys, column)))) {
            return null;
        }
        var group = without(usage.group(), keys);
        var order = without(usage.order(), keys);
        // The columns to read in order after the keys: the group columns, or the sort columns of a limited result
        // without a range, which an index then serves together with the keys. A group that holds the primary key
        // puts every row in a group of its own, so reading in order saves nothing.
        boolean ownGroups = groups && indexes.indexes().stream()
                .filter(index -> index.name() == null)
                .anyMatch(primaryKey -> primaryKey.columns().stream()
                        .allMatch(column -> containsIgnoreCase(usage.group(), column)));
        List<String> inOrder = ownGroups ? List.of() : groups ? group : range.isEmpty() ? order : List.of();
        // The columns that compete with a range: the group columns, or a sort on another column than the range.
        List<String> competing = ownGroups ? List.of() : groups ? group
                : !range.isEmpty() && !order.isEmpty() && !range.getFirst().equalsIgnoreCase(order.getFirst())
                        ? order : List.of();
        var findable = new ArrayList<String>(keys);
        addAbsent(findable, range);
        addAbsent(findable, usage.order());
        addAbsent(findable, usage.group());
        boolean found = findable.stream().anyMatch(indexes::leadsWith);
        var sizing = new Sizing(indexes, maxKeyBytes);
        if (!filters) {
            if (inOrder.isEmpty() || readsInOrder(indexes, keys, inOrder, groups)) {
                return null;
            }
            return fullAdvice(usage, sizing, keys, group, order, competing);
        }
        if (!found) {
            return fullAdvice(usage, sizing, keys, group, order, competing);
        }
        if (inOrder.isEmpty() || readsInOrder(indexes, keys, inOrder, groups)) {
            return null;
        }
        return continuingAdvice(usage, sizing, keys, inOrder);
    }

    /**
     * Returns the advice on one search field, or {@code null} where an index of its table serves the field in its
     * match form, or may. A prefix or exact match on a bare column is served by an index that leads with the column.
     * A match anywhere or at a word start compares the lowered column, which only an index over that expression
     * serves: an index of the table that starts with an expression may be it, so the advice stays silent; without
     * one, no index serves the field. A field wrapped in a function compares an expression as well. The fix names
     * what serves the form: the database's index for substring matching on the expression, where it has one, a
     * word-suffix table matched as a prefix elsewhere, or an ordinary index on a bare column.
     *
     * @param substringIndex the kind of index the database has for substring matching, or {@code null}.
     */
    private static @Nullable String searchAdvice(SearchField search, IndexCatalog.TableIndexes indexes,
                                                 @Nullable String substringIndex) {
        if (search.bare() ? indexes.leadsWith(search.column()) : indexes.expressionIndexed()) {
            return null;
        }
        String table = qualified(search.schema(), search.table());
        String fix;
        if (search.form().lowered()) {
            fix = substringIndex != null
                    ? "%s on %s serves it".formatted(substringIndex, search.expression())
                    : ("no index of the database serves the %s form; a word-suffix table, matched as a prefix, takes "
                            + "an ordinary index").formatted(search.form().factory());
        } else if (search.bare()) {
            fix = "an index on %s(%s) serves it".formatted(table, search.column());
        } else {
            fix = ("an index on %s serves it where the database indexes an expression, and a stored column holding "
                    + "it, declared as a query column, takes an ordinary index").formatted(search.expression());
        }
        return Advice.UNINDEXED_SEARCH.format("no index on %s serves %s".formatted(table, search.match()), table, fix);
    }

    /**
     * The table's indexes and the key limit the index named by the advice is sized against.
     */
    private record Sizing(IndexCatalog.TableIndexes indexes, int maxKeyBytes) {

        /**
         * Returns the bytes a key on the columns takes at most, or {@code 0} where the database checks no declared
         * size, so that no key passes its limit.
         */
        int keyBytes(List<String> columns) {
            return maxKeyBytes == 0 ? 0 : indexes.keyBytes(columns);
        }

        /** Returns whether a key on the columns passes the limit. */
        boolean passes(List<String> columns) {
            return keyBytes(columns) > maxKeyBytes;
        }

        /** Says that the key of the index passes the limit, or nothing where it fits. */
        String limit(List<String> columns) {
            return passes(columns)
                    ? (", though its key of about %d bytes passes the %d bytes the database allows a key, so a column "
                            + "must be shortened or left out").formatted(keyBytes(columns), maxKeyBytes)
                    : "";
        }
    }

    /** Returns the advice for a table none of whose indexes starts with a column the statement asks for. */
    private String fullAdvice(TableUsage usage,
                              Sizing sizing,
                              List<String> keys,
                              List<String> group,
                              List<String> order,
                              List<String> inOrder) {
        String table = qualified(usage.schema(), usage.table());
        var range = usage.range();
        boolean groups = !usage.group().isEmpty();
        boolean sorts = !order.isEmpty() && !groups;
        boolean filters = !keys.isEmpty() || !range.isEmpty();
        var asked = new ArrayList<String>(keys);
        addAbsent(asked, range);
        if (!groups) {
            addAbsent(asked, usage.order());
        }
        if (asked.isEmpty()) {
            addAbsent(asked, usage.group());
        }
        // The statement is in the log entry above the advice, so the advice names what the database does, not which
        // columns the statement uses them for.
        String cost;
        if (!filters && groups) {
            cost = "so the database sorts every row into groups";
        } else if (!filters) {
            cost = "so the database sorts every row before it returns the first ones";
        } else if (groups) {
            cost = "so the database reads the table in full and sorts the matching rows into groups";
        } else if (usage.equality().isEmpty() && range.isEmpty() && !sorts) {
            cost = "so the database reads the table in full to find the joined rows";
        } else {
            cost = "so the database reads the table in full to find the matching rows";
        }
        String fewValued = null;
        for (var column : keys) {
            var type = sizing.indexes().fewValued(column);
            if (type != null) {
                fewValued = "; %s is %s, with few distinct values, so an index on it alone is often not chosen"
                        .formatted(column, type);
                break;
            }
        }
        String options;
        if (inOrder.isEmpty()) {
            var single = suggested(keys, range, order);
            options = "index: %s(%s) %s%s%s".formatted(table, String.join(", ", single),
                    !filters ? "reads the rows in order and stops at the limit"
                            : "finds them directly where the condition selects few of them", sizing.limit(single),
                    fewValued == null ? "" : fewValued);
        } else {
            var ordered = inOrderIndex(usage, keys, inOrder);
            String readInOrder = groups
                    ? groupedRead(usage, sizing, ordered, filters)
                    : ("reads the rows in %s order, checks the range on each and stops at the limit, which is best "
                            + "where the range selects many of them").formatted(String.join(", ", inOrder))
                            + sizing.limit(ordered);
            String inOrderOption = "%s(%s) %s".formatted(table, String.join(", ", ordered), readInOrder);
            if (!filters) {
                options = "index: " + inOrderOption;
            } else {
                var find = new ArrayList<String>(keys);
                if (!range.isEmpty()) {
                    addAbsent(find, List.of(range.getFirst()));
                }
                String findOption = "%s(%s) %s%s".formatted(table, String.join(", ", find), groups
                        ? "finds them directly where the condition selects few of them"
                        : "finds the matching rows directly, then sorts them, which is best where the range selects "
                                + "few of them", sizing.limit(find)) + (fewValued == null ? "" : fewValued);
                options = fewValued != null || groups
                        ? "index: " + inOrderOption + "\nor: " + findOption
                        : "index: " + findOption + "\nor: " + inOrderOption;
            }
        }
        return Advice.MISSING_INDEX.format(table, "starts with " + String.join(" or ", asked), cost, options);
    }

    /**
     * Returns the advice for a table whose filter is indexed but whose rows the statement reads in an order no index
     * holds after the filter's columns: the index that continues the equality columns with the group or sort
     * columns, named as an extension of the index that holds exactly the equality columns, where one does.
     */
    private String continuingAdvice(TableUsage usage,
                                    Sizing sizing,
                                    List<String> keys,
                                    List<String> inOrder) {
        String table = qualified(usage.schema(), usage.table());
        boolean groups = !usage.group().isEmpty();
        String which = keys.isEmpty()
                ? "starts with " + String.join(", ", inOrder)
                : "continues %s with %s".formatted(String.join(", ", keys), String.join(", ", inOrder));
        String cost = groups
                ? "so the database sorts the matching rows into groups"
                : "so the database sorts every matching row before it returns the first ones";
        var ordered = inOrderIndex(usage, keys, inOrder);
        String extending = null;
        if (!keys.isEmpty()) {
            for (var index : sizing.indexes().indexes()) {
                if (index.columns().size() == keys.size()
                        && index.columns().stream().allMatch(column -> containsIgnoreCase(keys, column))) {
                    extending = index.name() == null ? "the primary key" : index.name();
                    break;
                }
            }
        }
        String read = groups
                ? groupedRead(usage, sizing, ordered, true)
                : "reads the matching rows in order and stops at the limit" + sizing.limit(ordered);
        String option = "index: %s(%s)%s %s".formatted(table, String.join(", ", ordered),
                extending == null ? "" : ", extending " + extending + ",", read);
        return Advice.MISSING_INDEX.format(table, which, cost, option);
    }

    /**
     * Returns the columns of the index that reads in order: the keys, the group or sort columns, and for a grouped
     * read a range column after them. The columns the select reads are not among them: they widen the key for the
     * last part of the gain, so the advice names them apart, for the reader to weigh.
     */
    private static List<String> inOrderIndex(TableUsage usage, List<String> keys, List<String> inOrder) {
        var ordered = new ArrayList<String>(keys);
        addAbsent(ordered, inOrder);
        if (!usage.group().isEmpty() && !usage.range().isEmpty()) {
            addAbsent(ordered, List.of(usage.range().getFirst()));
        }
        return ordered;
    }

    /**
     * Describes what the index that reads in group order does: finds the rows and reads them grouped, in the order
     * of an ORDER BY on the group columns too, and what reading the index alone takes: nothing more where it holds
     * every column the select reads, or the select's columns appended, named apart so the reader can weigh the key
     * they add. Where the key passes the database's limit, that is said instead.
     */
    private String groupedRead(TableUsage usage, Sizing sizing, List<String> index, boolean filters) {
        var grouped = new ArrayList<String>(index);
        addAbsent(grouped, usage.group());
        boolean ordersByGroup = !usage.order().isEmpty()
                && usage.order().stream().allMatch(column -> containsIgnoreCase(grouped, column));
        String read = (filters ? "finds the matching rows and reads them already grouped" : "reads the rows already grouped")
                + (ordersByGroup ? " and in the order the ORDER BY names" : "")
                + ", without a temporary table or sort";
        if (sizing.passes(index)) {
            return read + sizing.limit(index);
        }
        return read + covering(usage, sizing, index);
    }

    /**
     * Says what reading the index alone takes, for an index on the given columns: where the select names every
     * column it reads, whether the index holds them or which to append; where part of the select is opaque, the same
     * hedged on what the select reads; and where the select names nothing, that the index must also hold its columns.
     */
    private String covering(TableUsage usage, Sizing sizing, List<String> index) {
        var appended = without(usage.select(), index);
        if (usage.select().isEmpty() && !selectKnown) {
            return "\ncovering: it reads the index alone when it also holds the columns the select reads";
        }
        if (appended.isEmpty()) {
            return "\ncovering: it reads the index alone" + (selectKnown
                    ? ", since it holds every column the select reads"
                    : " where the select reads no other columns");
        }
        var widened = new ArrayList<String>(index);
        widened.addAll(appended);
        if (sizing.passes(widened)) {
            return ("\ncovering: with %s appended it would read the index alone, but a key of about %d bytes passes "
                    + "the %d bytes the database allows a key").formatted(String.join(", ", appended),
                    sizing.keyBytes(widened), sizing.maxKeyBytes());
        }
        return "\ncovering: with %s appended it reads the index alone%s".formatted(String.join(", ", appended),
                selectKnown
                        ? ", since it then holds every column the select reads"
                        : " where the select reads no other columns");
    }

    /**
     * Returns whether an index starts with the keys, in any order, and continues with the columns to read in order:
     * the group columns in any order, or the sort columns in theirs.
     */
    private static boolean readsInOrder(IndexCatalog.TableIndexes indexes, List<String> keys, List<String> inOrder,
                                        boolean groups) {
        for (var index : indexes.indexes()) {
            var columns = index.columns();
            if (columns.size() < keys.size() + inOrder.size()) {
                continue;
            }
            var leading = columns.subList(0, keys.size());
            if (!keys.stream().allMatch(key -> containsIgnoreCase(leading, key))) {
                continue;
            }
            var next = columns.subList(keys.size(), keys.size() + inOrder.size());
            boolean matches = groups
                    ? inOrder.stream().allMatch(column -> containsIgnoreCase(next, column))
                    : sameColumns(next, inOrder);
            if (matches) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the columns of the index that finds the matching rows: the keys, then one range column, or failing
     * that, the columns a limited result is sorted by. An index serves equality on any of its leading columns and a
     * range or an order on the ones after them.
     */
    private static List<String> suggested(List<String> keys, List<String> range, List<String> order) {
        var columns = new ArrayList<String>(keys);
        if (!range.isEmpty()) {
            addAbsent(columns, List.of(range.getFirst()));
        } else {
            addAbsent(columns, order);
        }
        return columns;
    }

    private static List<String> without(List<String> columns, List<String> removed) {
        return columns.stream().filter(column -> !containsIgnoreCase(removed, column)).toList();
    }

    private static boolean containsIgnoreCase(List<String> columns, String column) {
        return columns.stream().anyMatch(column::equalsIgnoreCase);
    }

    private static boolean sameColumns(List<String> left, List<String> right) {
        if (left.size() != right.size()) {
            return false;
        }
        for (int i = 0; i < left.size(); i++) {
            if (!left.get(i).equalsIgnoreCase(right.get(i))) {
                return false;
            }
        }
        return true;
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
