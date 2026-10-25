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

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
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
import st.orm.core.template.impl.PredicateNode.Search;
import st.orm.core.template.impl.PredicateNode.Term;
import st.orm.mapping.RecordField;

/**
 * Places the conditions of a query's semi-joins and anti-joins in subqueries of its WHERE clause.
 *
 * <p>A semi-join and an anti-join on the same table with the same condition on the join are complements: the one keeps
 * the rows that have a related row matching the condition, or any related row without one, and the other the rows
 * that have none.</p>
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
 * <p>A semi-join or anti-join may be joined on a semi-joined or anti-joined table, or on a table that one holds a
 * foreign key to, which filters through two to-many relations. Its subquery then sits inside the subquery of the table
 * it hangs off, correlated to that subquery's row, and the rules above hold at each level: a condition naming the
 * inner table goes into the inner subquery, and conditions joined by AND on the outer filtered table and the tables
 * inside its subquery hold for the same outer related row. A table reached through a foreign key is correlated on that
 * foreign key's columns, so the subquery does not read the table in between. A table the query reaches itself, as its
 * own table, a table it joins, or a table its own foreign keys join, stays the query's.</p>
 *
 * <p>A table filtered inside an anti-join's subquery decides which related rows exclude a row, so, like the anti-joined
 * table, it takes its condition on the join and not in the WHERE clause.</p>
 *
 * @since 1.16
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
     * A semi-join or anti-join. Joins are compared by identity, as each filters by its own table.
     *
     * @param table the filtered table.
     * @param anchor the table it is joined on.
     * @param relation how the two relate.
     * @param condition the condition stated on the join, or {@code null}.
     * @param type the join type.
     * @param parent the semi-join or anti-join whose subquery holds this one's, or {@code null} when the anchor is a
     *               table of the query.
     * @param anchorKey the foreign key field of the parent's table that references the anchor, or of the query's table
     *                  for a join on the query's own table; {@code null} when the anchor is the parent's table itself
     *                  or a table of the query.
     */
    private record FilterJoin(Class<? extends Data> table,
                              Class<? extends Data> anchor,
                              Relation relation,
                              @Nullable PredicateNode condition,
                              FilterJoinType type,
                              @Nullable FilterJoin parent,
                              @Nullable RecordField anchorKey) {

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }
    }

    /**
     * A foreign key of a filtered table through which a semi-join or anti-join reaches the table it is joined on.
     */
    private record Hop(FilterJoin filterJoin, RecordField foreignKey) {}

    private final QueryTemplate queryTemplate;
    private final Class<? extends Data> fromType;
    private final boolean branchOnAnchor;
    /**
     * Every semi-join and anti-join in the order they were added, a join before the joins its subquery holds. A table
     * may be filtered by more than one join, each a subquery of its own with its own condition on the join.
     */
    private final List<FilterJoin> filterJoins;

    /**
     * @param queryTemplate the template the query builds its subqueries with.
     * @param fromType the query's table.
     * @param joins the query's joins.
     * @param branchOnAnchor whether a subquery may read the table a semi-join is joined on, which a DELETE refuses on
     *                       some databases for the table it deletes from.
     */
    private FilterJoinPlacement(QueryTemplate queryTemplate,
                                Class<? extends Data> fromType,
                                List<Join> joins,
                                boolean branchOnAnchor) {
        this.queryTemplate = queryTemplate;
        this.fromType = fromType;
        this.branchOnAnchor = branchOnAnchor;
        this.filterJoins = resolve(queryTemplate, fromType, joins);
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
        for (var existing : joins) {
            if (!isFilterJoin(existing) && existing.source() instanceof TableSource(var existingTable) && existingTable == table) {
                throw new PersistenceException("%s(%s): the query already joins %s, so a condition naming it could not tell the joins apart."
                        .formatted(type.method(), table.getSimpleName(), table.getSimpleName()));
            }
        }
        var withJoin = new ArrayList<>(joins);
        withJoin.add(join);
        resolve(queryTemplate, fromType, withJoin);
    }

    /**
     * Renders the WHERE clause of a query with semi-joins or anti-joins.
     *
     * @param queryTemplate the template the query builds its subqueries with.
     * @param fromType the query's table.
     * @param joins the query's joins, of which the semi-joins and anti-joins are placed.
     * @param where the query's WHERE conditions.
     * @param clauses the query's other clauses, which may not name a filtered table.
     * @param branchOnAnchor whether a subquery may read the table a semi-join is joined on.
     * @return the WHERE condition, or {@code null} for none.
     */
    static @Nullable TemplateString where(QueryTemplate queryTemplate,
                                          Class<? extends Data> fromType,
                                          List<Join> joins,
                                          List<Where> where,
                                          List<TemplateString> clauses,
                                          boolean branchOnAnchor) {
        var placement = new FilterJoinPlacement(queryTemplate, fromType, joins, branchOnAnchor);
        placement.verifyClauses(clauses);
        return placement.where(where);
    }

    private @Nullable TemplateString where(List<Where> where) {
        var conjuncts = new ArrayList<PredicateNode>();
        for (var condition : where) {
            var conjunct = node(condition);
            // A condition naming a table the query filters by more than once could not tell the joins apart.
            filterJoinsNamed(null, conjunct);
            conjuncts.add(conjunct);
        }
        for (var filterJoin : filterJoins) {
            var antiJoin = antiJoinHolding(filterJoin);
            if (antiJoin == null || filterJoin.table() == fromType) {
                continue;
            }
            for (var conjunct : conjuncts) {
                if (!references(conjunct).contains(filterJoin.table())) {
                    continue;
                }
                if (antiJoin == filterJoin) {
                    throw new PersistenceException("A condition names the anti-joined %1$s, but the query keeps the rows without a matching %1$s, so the condition belongs to the join: antiJoin(%1$s).on(%2$s, condition)."
                            .formatted(antiJoin.table().getSimpleName(), antiJoin.anchor().getSimpleName()));
                }
                throw new PersistenceException("A condition names %1$s, which decides which %2$s rows the anti-join on %2$s counts, but the query keeps the rows without such a %2$s, so the condition belongs to the join: %3$s(%1$s).on(%4$s, condition)."
                        .formatted(filterJoin.table().getSimpleName(), antiJoin.table().getSimpleName(),
                                filterJoin.type().method(), filterJoin.anchor().getSimpleName()));
            }
        }
        var condition = condition(null, conjuncts, false);
        return condition == null ? null : condition.toTemplate();
    }

    /**
     * Returns the condition of one level of the query: the statement itself, or the subquery of a semi-join or
     * anti-join. The conditions naming a semi-join of the level, or a table filtered inside its subquery, go into that
     * semi-join's subquery; a semi-join no condition names keeps the rows with any related row, and an anti-join the
     * rows without one.
     *
     * @param level the semi-join or anti-join whose subquery the condition is for, or {@code null} for the statement.
     * @param conjuncts the conditions of the level, joined by AND.
     * @param residual whether the conditions are tested only on the rows the rest of the condition found.
     * @return the condition, or {@code null} for none.
     */
    private @Nullable PredicateNode condition(@Nullable FilterJoin level, List<PredicateNode> conjuncts, boolean residual) {
        var parts = new ArrayList<PredicateNode>();
        if (!conjuncts.isEmpty()) {
            parts.add(place(level, PredicateNode.and(conjuncts), residual));
        }
        for (var filterJoin : filterJoins) {
            if (filterJoin.parent() != level) {
                continue;
            }
            // Without a condition naming the table, the semi-join keeps the rows that have a related row at all: the
            // complement of the anti-join on the same table.
            if (filterJoin.type() == FilterJoinType.ANTI
                    || conjuncts.stream().noneMatch(conjunct -> namesAny(conjunct, tablesOf(filterJoin)))) {
                parts.add(new Term(exists(filterJoin, filterJoin.condition()), true));
            }
        }
        return parts.isEmpty() ? null : PredicateNode.and(parts);
    }

    /**
     * Returns the anti-join whose subquery holds the filtered table, the join itself when it is an anti-join, or
     * {@code null} when no anti-join holds it.
     */
    private static @Nullable FilterJoin antiJoinHolding(FilterJoin filterJoin) {
        for (FilterJoin join = filterJoin; join != null; join = join.parent()) {
            if (join.type() == FilterJoinType.ANTI) {
                return join;
            }
        }
        return null;
    }

    /**
     * Refuses a filtered table in the clauses that order, group or select: it adds no columns to the query.
     */
    private void verifyClauses(List<TemplateString> clauses) {
        for (var clause : clauses) {
            var tables = new LinkedHashSet<Class<? extends Data>>();
            collect(clause, tables);
            for (var table : tables) {
                var named = filterJoinsOf(filterJoins, table);
                if (!named.isEmpty() && table != fromType) {
                    throw new PersistenceException("%1$s is %2$s: it filters the query but adds no columns to it, so the query cannot order, group or select by its columns. Join %1$s to use its columns."
                            .formatted(table.getSimpleName(), named.getFirst().type() == FilterJoinType.SEMI ? "semi-joined" : "anti-joined"));
                }
            }
        }
    }

    /**
     * Places the conditions on semi-joined tables of a predicate, answering the predicate as one scope: its terms
     * joined by AND on one semi-joined table, or on the tables filtered inside its subquery, hold for the same related
     * row.
     *
     * @param level the semi-join or anti-join whose subquery the predicate is for, or {@code null} for the statement.
     * @param residual whether the predicate is tested only on the rows the rest of the condition found, rather than
     *                 read through an index of its own.
     */
    private PredicateNode place(@Nullable FilterJoin level, PredicateNode node, boolean residual) {
        var parts = new ArrayList<PredicateNode>();
        var groups = new LinkedHashMap<FilterJoin, List<PredicateNode>>();
        for (var conjunct : conjuncts(node)) {
            if (conjunct instanceof Scope(var inner, var innerResidual) && !semiJoinsOf(level, inner).isEmpty()) {
                parts.add(place(level, inner, residual || innerResidual));
                continue;
            }
            var named = semiJoinsOf(level, conjunct);
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
            parts.add(placeGroup(group.getKey(), group.getValue(), residual));
        }
        return PredicateNode.and(parts);
    }

    /**
     * Places the conditions of one scope that name one semi-joined table, or the tables filtered inside its subquery.
     *
     * <p>A residual scope renders as {@code EXISTS} over the related rows of each row, which the database tests on the
     * rows the rest of the condition found. An {@code IN} over a {@code UNION} reads the rows matching the condition
     * from every table instead, which only pays for a condition an index serves.</p>
     *
     * @param residual whether the conditions are tested only on the rows the rest of the condition found.
     */
    private PredicateNode placeGroup(FilterJoin semiJoin, List<PredicateNode> conditions, boolean residual) {
        if (residual && conditions.size() == 1 && conditions.getFirst() instanceof Group(var and, var operands) && !and) {
            var residualCondition = residual(semiJoin, operands);
            if (residualCondition != null) {
                return residualCondition;
            }
        }
        if (!residual && conditions.size() == 1 && conditions.getFirst() instanceof Group(var and, var operands) && !and) {
            var union = union(semiJoin, operands);
            if (union != null) {
                return new Term(union, true);
            }
        }
        var condition = PredicateNode.and(conditions);
        var exists = new Term(exists(semiJoin, restricted(semiJoin, condition)), true);
        // The rows without a related row match through the part of the condition that names none.
        var withoutRelated = withoutTables(condition, tablesOf(semiJoin));
        return withoutRelated == null ? exists : PredicateNode.or(List.of(withoutRelated, exists));
    }

    /**
     * Returns the OR of terms on the anchor and terms on the semi-joined table as the anchor's terms OR an
     * {@code EXISTS} over the related rows holding the table's terms, or {@code null} when the operands do not take that
     * shape. The database tests it on each row the rest of the condition found, reading the related rows of that row
     * alone; the anchor's terms stay outside the subquery, which reads the semi-joined table only.
     */
    private @Nullable PredicateNode residual(FilterJoin semiJoin, List<PredicateNode> operands) {
        var tables = tablesOf(semiJoin);
        var anchorTerms = new ArrayList<PredicateNode>(operands.size());
        var tableTerms = new ArrayList<PredicateNode>(operands.size());
        for (var operand : operands) {
            if (onlyNames(operand, tables)) {
                tableTerms.add(operand);
            } else if (!namesAny(operand, tables)) {
                anchorTerms.add(operand);
            } else {
                return null;
            }
        }
        if (tableTerms.isEmpty()) {
            return null;
        }
        var exists = new Term(exists(semiJoin, restricted(semiJoin, PredicateNode.or(tableTerms))), true);
        if (anchorTerms.isEmpty()) {
            return exists;
        }
        anchorTerms.add(exists);
        return PredicateNode.or(anchorTerms);
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
            if (onlyNames(operand, Set.of(semiJoin.anchor()))) {
                anchorTerms.add(operand);
            } else if (onlyNames(operand, Set.of(semiJoin.table()))) {
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
        var tableBranch = branch(semiJoin.table(), new RelationKey(semiJoin.table(), foreignKey, true, INNER),
                restricted(semiJoin, PredicateNode.or(tableTerms)));
        return TemplateString.raw("\0 IN (\0\nUNION\n\0)",
                new RelationKey(semiJoin.anchor(), foreignKey, false, INNER),
                new Subquery(anchorBranch, false),
                new Subquery(tableBranch, false));
    }

    /**
     * Returns the condition on a semi-joined table limited to the related rows the join's own condition admits. The
     * join's condition states which related rows count at all, so every condition answered by a related row holds it
     * too: each word of a search matches a related row the join admits.
     */
    private static PredicateNode restricted(FilterJoin semiJoin, PredicateNode condition) {
        return semiJoin.condition() == null ? condition : PredicateNode.and(List.of(condition, semiJoin.condition()));
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
     * The semi-joins and anti-joins joined on the filtered table, or on a table it holds a foreign key to, are placed
     * in the subquery, with the parts of the condition that name them. The database tests the subquery's condition on
     * the related rows of one row at a time, so the conditions in it are residual: an {@code IN} over a {@code UNION}
     * there would read every matching row of the tables for each of them.
     */
    private TemplateString exists(FilterJoin filterJoin, @Nullable PredicateNode condition) {
        var relation = filterJoin.relation();
        var parent = filterJoin.parent();
        // A join on the query's own table correlates the foreign key of the subquery's row with the query's row's.
        var correlation = filterJoin.anchorKey() == null
                ? new Correlation(filterJoin.table(), filterJoin.anchor(), relation.foreignKey(), relation.tableHoldsKey())
                : new Correlation(filterJoin.table(), parent == null ? fromType : parent.table(), relation.foreignKey(), true, filterJoin.anchorKey());
        var parts = new ArrayList<PredicateNode>(2);
        parts.add(new Term(wrap(correlation), true));
        var inner = condition(filterJoin, condition == null ? List.of() : List.of(condition), true);
        if (inner != null) {
            parts.add(inner);
        }
        var subquery = (QueryBuilderImpl<?, ?, ?>) queryTemplate.subquery(filterJoin.table(), TemplateString.of("1"));
        var template = subquery.withCondition(PredicateNode.and(parts)).getSubquery();
        return TemplateString.raw(filterJoin.type() == FilterJoinType.SEMI ? "EXISTS (\0)" : "NOT EXISTS (\0)",
                new Subquery(template, true));
    }

    /**
     * Returns the filtered table and the tables filtered inside its subquery, at any depth. The query's own table is
     * not among them: a condition naming it names the query's row, and a join on the table takes its condition on the
     * join.
     */
    private Set<Class<? extends Data>> tablesOf(FilterJoin filterJoin) {
        var tables = new LinkedHashSet<Class<? extends Data>>();
        for (var candidate : filterJoins) {
            if (candidate.table() == fromType) {
                continue;
            }
            for (FilterJoin join = candidate; join != null; join = join.parent()) {
                if (join == filterJoin) {
                    tables.add(candidate.table());
                    break;
                }
            }
        }
        return tables;
    }

    /**
     * Returns the predicate with every term naming one of the tables taken as false, or {@code null} when the
     * predicate is then false: the condition a row without a related row is held to.
     */
    private static @Nullable PredicateNode withoutTables(PredicateNode node, Set<Class<? extends Data>> tables) {
        return switch (node) {
            case Term term -> namesAny(term, tables) ? null : term;
            case Scope(var inner, var ignore) -> withoutTables(inner, tables);
            case Search search -> withoutTables(search.resolve(0), tables);
            case Group(var and, var operands) -> {
                var kept = new ArrayList<PredicateNode>(operands.size());
                for (var operand : operands) {
                    var without = withoutTables(operand, tables);
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
     * Returns the semi-joins of the level whose table, or a table filtered inside whose subquery, the predicate names.
     *
     * @param level the semi-join or anti-join whose subquery the predicate is for, or {@code null} for the statement.
     */
    private Set<FilterJoin> semiJoinsOf(@Nullable FilterJoin level, PredicateNode node) {
        var named = new LinkedHashSet<FilterJoin>();
        for (var join : filterJoinsNamed(level, node)) {
            if (join.type() == FilterJoinType.SEMI) {
                named.add(join);
            }
        }
        return named;
    }

    /**
     * Returns the semi-joins and anti-joins of the level whose table, or a table filtered inside whose subquery, the
     * predicate names.
     *
     * @param level the semi-join or anti-join whose subquery the predicate is for, or {@code null} for the statement.
     * @throws PersistenceException if a table the predicate names resolves to more than one join of the level, which
     *                              the predicate could not tell apart.
     */
    private Set<FilterJoin> filterJoinsNamed(@Nullable FilterJoin level, PredicateNode node) {
        var named = new LinkedHashSet<FilterJoin>();
        for (var table : references(node)) {
            if (level == null && table == fromType) {
                continue;   // The condition names the query's row; a join on the table takes its condition on the join.
            }
            var candidates = new ArrayList<FilterJoin>(1);
            for (var candidate : filterJoinsOf(filterJoins, table)) {
                var join = candidate;
                while (join != null && join.parent() != level) {
                    join = join.parent();
                }
                if (join != null && !candidates.contains(join)) {
                    candidates.add(join);
                }
            }
            if (candidates.size() > 1) {
                var first = candidates.getFirst();
                throw new PersistenceException("A condition names %1$s, which the query filters by more than once (%2$s), so the condition could not tell the joins apart. State it on the join it belongs to: %3$s(%1$s).on(%4$s, condition)."
                        .formatted(table.getSimpleName(), describe(candidates), first.type().method(), first.anchor().getSimpleName()));
            }
            named.addAll(candidates);
        }
        return named;
    }

    /**
     * Returns the semi-joins and anti-joins on the table, in the order they were added.
     */
    private static List<FilterJoin> filterJoinsOf(Iterable<FilterJoin> filterJoins, Class<? extends Data> table) {
        var named = new ArrayList<FilterJoin>(1);
        for (var filterJoin : filterJoins) {
            if (filterJoin.table() == table) {
                named.add(filterJoin);
            }
        }
        return named;
    }

    /**
     * Describes the joins as they were written, {@code semiJoin(Visit).on(Pet)}, for a message naming them.
     */
    private static String describe(List<FilterJoin> joins) {
        return joins.stream()
                .map(join -> "%s(%s).on(%s)".formatted(join.type().method(), join.table().getSimpleName(), join.anchor().getSimpleName()))
                .collect(joining(", "));
    }

    private static boolean namesAny(PredicateNode node, Set<Class<? extends Data>> tables) {
        for (var table : references(node)) {
            if (tables.contains(table)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether every term of the predicate names one or more of the tables and no other, through typed
     * references alone, so the predicate can move into a subquery reading only those tables.
     */
    private static boolean onlyNames(PredicateNode node, Set<Class<? extends Data>> tables) {
        return switch (node) {
            case Term term -> {
                var template = term.template();
                if (template.fragments().stream().anyMatch(fragment -> !fragment.isBlank())) {
                    yield false;    // Text of its own may name any alias of the enclosing query.
                }
                var named = new LinkedHashSet<Class<? extends Data>>();
                collect(template, named);
                yield !named.isEmpty() && tables.containsAll(named);
            }
            case Scope(var inner, var ignore) -> onlyNames(inner, tables);
            case Search search -> onlyNames(search.resolve(0), tables);
            case Group group -> group.operands().stream().allMatch(operand -> onlyNames(operand, tables));
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
            case Term term -> collect(term.template(), tables);
            case Scope(var inner, var ignore) -> collect(inner, tables);
            case Search search -> search.words().forEach(word -> collect(word, tables));
            case Group group -> group.operands().forEach(operand -> collect(operand, tables));
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
        if (where.expression() instanceof TemplateExpression expression && expression.predicate() != null) {
            return expression.predicate();
        }
        Expression expression = where.expression();
        return new Term(expression == null ? wrap(where) : wrap(expression), true);
    }

    /**
     * Resolves the semi-joins and anti-joins of a query in the order they were added, each with the join whose
     * subquery holds it.
     *
     * <p>A join is joined on a table of the query when the anchor is the query's table, a table the query joins, or a
     * table its own foreign keys join. Otherwise it is joined on a semi-joined or anti-joined table named as the
     * anchor, or on a table one of them holds a foreign key to. An anchor neither reaches stays the query's, which
     * resolves it as it resolves any table, including the tables of an enclosing query.</p>
     *
     * <p>A join on the query's own table finds the rows that reference the same row of the anchor as the query's row:
     * the visits of the pets another visit was for. Both occurrences hold the foreign key, so the subquery's row
     * correlates with the query's row through it.</p>
     */
    private static List<FilterJoin> resolve(QueryTemplate queryTemplate, Class<? extends Data> fromType, List<Join> joins) {
        var joined = new HashSet<Class<? extends Data>>();
        var resolved = new ArrayList<FilterJoin>();
        for (var join : joins) {
            if (!isFilterJoin(join)) {
                if (join.source() instanceof TableSource(var table)) {
                    joined.add(table);
                }
                continue;
            }
            resolved.add(filterJoin(queryTemplate, fromType, joined, resolved, join));
        }
        return List.copyOf(resolved);
    }

    private static FilterJoin filterJoin(QueryTemplate queryTemplate,
                                         Class<? extends Data> fromType,
                                         Set<Class<? extends Data>> joined,
                                         List<FilterJoin> resolved,
                                         Join join) {
        var type = (FilterJoinType) join.type();
        var table = ((TableSource) join.source()).table();
        var target = (TableTarget) join.target();
        var anchor = target.table();
        var condition = target.condition();
        if (condition != null) {
            condition = PredicateNode.resolve(condition, queryTemplate.dialect().minimumSearchTermLength());
        }
        if (table == fromType) {
            return selfFilterJoin(queryTemplate, type, table, anchor, condition);
        }
        var parents = filterJoinsOf(resolved, anchor);
        if (parents.size() > 1) {
            throw new PersistenceException("%1$s(%2$s).on(%3$s): the query filters by %3$s more than once (%4$s), so the subquery to place the join in is ambiguous."
                    .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName(), describe(parents)));
        }
        var parent = parents.isEmpty() ? null : parents.getFirst();
        RecordField anchorKey = null;
        if (parent == null && anchor != fromType && !joined.contains(anchor) && !joinedThroughForeignKeys(fromType, anchor)) {
            var hop = hop(queryTemplate, type, table, anchor, resolved);
            if (hop != null) {
                parent = hop.filterJoin();
                anchorKey = hop.foreignKey();
            }
        }
        var relation = relation(queryTemplate, type, table, anchor);
        if (parent != null && anchorKey != null && !relation.tableHoldsKey()) {
            throw new PersistenceException("%1$s(%2$s).on(%3$s): %3$s is reached through the foreign key %4$s.%5$s, but %3$s holds the foreign key to %2$s, so the subquery of %4$s would have to read %3$s. Filter by %3$s itself first: semiJoin(%3$s).on(%4$s), then %1$s(%2$s).on(%3$s)."
                    .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName(),
                            parent.table().getSimpleName(), anchorKey.name()));
        }
        return new FilterJoin(table, anchor, relation, condition, type, parent, anchorKey);
    }

    /**
     * Resolves a semi-join or anti-join on the query's own table: the rows referencing the same row of the anchor as
     * the query's row, through the foreign key the table holds to the anchor.
     *
     * @throws PersistenceException if the anchor is the table itself, or the table holds no foreign key to the anchor.
     */
    private static FilterJoin selfFilterJoin(QueryTemplate queryTemplate,
                                             FilterJoinType type,
                                             Class<? extends Data> table,
                                             Class<? extends Data> anchor,
                                             @Nullable PredicateNode condition) {
        if (anchor == table) {
            throw new PersistenceException("%1$s(%2$s).on(%2$s): a %1$s on the query's own table finds the %2$s rows that reference the same row of another table as the query's row. Name that table: %1$s(%2$s).on(<table>)."
                    .formatted(type.method(), table.getSimpleName()));
        }
        var relation = relation(queryTemplate, type, table, anchor);
        if (!relation.tableHoldsKey()) {
            throw new PersistenceException("%1$s(%2$s).on(%3$s): a %1$s on the query's own table finds the %2$s rows that reference the same %3$s as the query's row, which takes a foreign key of %2$s referencing %3$s; %3$s references %2$s instead."
                    .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName()));
        }
        return new FilterJoin(table, anchor, relation, condition, type, null, relation.foreignKey());
    }

    /**
     * Returns the foreign key of a semi-joined or anti-joined table that references the anchor, or {@code null} when
     * none does.
     *
     * @throws PersistenceException if more than one foreign key references it, or a filtered table reaches it only
     *                              through another table.
     */
    private static @Nullable Hop hop(QueryTemplate queryTemplate,
                                     FilterJoinType type,
                                     Class<? extends Data> table,
                                     Class<? extends Data> anchor,
                                     Iterable<FilterJoin> filterJoins) {
        var hops = new ArrayList<Hop>();
        try {
            for (var filterJoin : filterJoins) {
                var fields = getFkFields(filterJoin.table()).toList();
                var matches = findRecordFields(fields, anchor);
                if (matches.isEmpty()) {
                    matches = byTable(queryTemplate, fields, anchor);
                }
                for (var match : matches) {
                    hops.add(new Hop(filterJoin, match));
                }
            }
            if (hops.size() > 1) {
                var holders = hops.stream().map(Hop::filterJoin).toList();
                if (holders.stream().map(FilterJoin::table).distinct().count() < holders.size()) {
                    throw new PersistenceException("%1$s(%2$s).on(%3$s): %3$s is reached through the foreign key of %4$s, which the query filters by more than once (%5$s), so the subquery to place the join in is ambiguous."
                            .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName(),
                                    holders.getFirst().table().getSimpleName(), describe(holders)));
                }
                throw new PersistenceException("%s(%s).on(%s): %s is reached through more than one foreign key of the filtered tables (%s), so the table to filter by is ambiguous. Filter by %s itself first: semiJoin(%s).on(<table>), with the table holding the foreign key to use."
                        .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName(), anchor.getSimpleName(),
                                hops.stream().map(hop -> hop.filterJoin().table().getSimpleName() + "." + hop.foreignKey().name()).collect(joining(", ")),
                                anchor.getSimpleName(), anchor.getSimpleName()));
            }
            if (hops.isEmpty()) {
                for (var filterJoin : filterJoins) {
                    var via = via(filterJoin.table(), anchor);
                    if (via != null) {
                        throw new PersistenceException("%1$s(%2$s).on(%3$s): %3$s is not part of this query, and the filtered %4$s reaches it only through %5$s. Filter by each table on the way, so each is reached through a foreign key of the one before: semiJoin(%5$s).on(%4$s), and on to %3$s."
                                .formatted(type.method(), table.getSimpleName(), anchor.getSimpleName(),
                                        filterJoin.table().getSimpleName(), via.getSimpleName()));
                    }
                }
            }
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
        return hops.isEmpty() ? null : hops.getFirst();
    }

    /**
     * Returns whether the query's own foreign keys join the table: a foreign key holding the table's record, on the
     * query's table or on a table such a key joins in turn.
     */
    private static boolean joinedThroughForeignKeys(Class<? extends Data> fromType, Class<? extends Data> table) {
        var visited = new HashSet<Class<?>>();
        var pending = new ArrayDeque<Class<?>>();
        pending.add(fromType);
        while (!pending.isEmpty()) {
            var current = pending.poll();
            if (!visited.add(current)) {
                continue;
            }
            for (var field : getFkFields(current).toList()) {
                var type = field.type();
                if (Ref.class.isAssignableFrom(type) || !Data.class.isAssignableFrom(type)) {
                    continue;   // A Ref is not joined.
                }
                if (type == table) {
                    return true;
                }
                pending.add(type);
            }
        }
        return false;
    }

    /**
     * Returns the table a filtered table's foreign key references on the way to the anchor through further foreign
     * keys, or {@code null} when its foreign keys do not reach the anchor.
     */
    private static @Nullable Class<? extends Data> via(Class<? extends Data> table, Class<? extends Data> anchor)
            throws SqlTemplateException {
        var visited = new HashSet<Class<?>>();
        visited.add(table);
        var pending = new ArrayDeque<Map.Entry<Class<? extends Data>, Class<? extends Data>>>();
        for (var field : getFkFields(table).toList()) {
            var referenced = referenced(field);
            if (referenced != null) {
                pending.add(Map.entry(referenced, referenced));
            }
        }
        while (!pending.isEmpty()) {
            var entry = pending.poll();
            var current = entry.getKey();
            if (!visited.add(current)) {
                continue;
            }
            for (var field : getFkFields(current).toList()) {
                var referenced = referenced(field);
                if (referenced == anchor) {
                    return entry.getValue();
                }
                if (referenced != null) {
                    pending.add(Map.entry(referenced, entry.getValue()));
                }
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static @Nullable Class<? extends Data> referenced(RecordField field) throws SqlTemplateException {
        Class<?> type = Ref.class.isAssignableFrom(field.type()) ? getRefDataType(field) : field.type();
        return Data.class.isAssignableFrom(type) && !type.isSealed() ? (Class<? extends Data>) type : null;
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
