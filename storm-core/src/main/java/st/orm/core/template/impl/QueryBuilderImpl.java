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
import static st.orm.JoinType.cross;
import static st.orm.JoinType.inner;
import static st.orm.JoinType.left;
import static st.orm.JoinType.right;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.IN;
import static st.orm.Operator.LESS_THAN;
import static st.orm.core.template.TemplateString.combine;
import static st.orm.core.template.TemplateString.wrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.JoinType;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.Operator;
import st.orm.Order;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.SqlTemplateException;
import st.orm.Window;
import st.orm.core.template.JoinBuilder;
import st.orm.core.template.Model;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.SubqueryTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.TypedJoinBuilder;
import st.orm.core.template.WhereBuilder;
import st.orm.core.template.impl.Elements.Expression;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.TableSource;
import st.orm.core.template.impl.Elements.TableTarget;
import st.orm.core.template.impl.Elements.TemplateExpression;
import st.orm.core.template.impl.Elements.TemplateSource;
import st.orm.core.template.impl.Elements.TemplateTarget;
import st.orm.core.template.impl.Elements.Where;

/**
 * Abstract query builder implementation.
 *
 * @param <T> the type of the table being queried.
 * @param <R> the type of the result.
 * @param <ID> the type of the primary key.
 */
abstract class QueryBuilderImpl<T extends Data, R, ID> extends QueryBuilder<T, R, ID> implements Subqueryable {

    protected final QueryTemplate queryTemplate;
    protected final Class<T> fromType;
    protected final List<Join> join;
    protected final List<Where> where;
    protected final List<TemplateString> groupBy;
    protected final List<TemplateString> having;
    protected final List<TemplateString> orderBy;
    protected final Supplier<Model<T, ID>> modelSupplier;

    protected QueryBuilderImpl(QueryTemplate queryTemplate,
                               Class<T> fromType,
                               List<Join> join,
                               List<Where> where,
                               List<TemplateString> groupBy,
                               List<TemplateString> having,
                               List<TemplateString> orderBy,
                               Supplier<Model<T, ID>> modelSupplier) {
        this.queryTemplate = queryTemplate;
        this.fromType = fromType;
        this.join = List.copyOf(join);
        this.where = List.copyOf(where);
        this.groupBy = List.copyOf(groupBy);
        this.having = List.copyOf(having);
        this.orderBy = List.copyOf(orderBy);
        this.modelSupplier = requireNonNull(modelSupplier, "modelSupplier");
    }

    /**
     * Returns the data type used in the FROM clause of the query. This is the entity or projection type {@code T}
     * that the query is built against.
     *
     * @return the FROM clause data type.
     */
    /**
     * Executes the query with the given cursor columns appended to its select list, reading them from each row
     * alongside the mapped result.
     *
     * @param columns the cursor columns, in the order their values are wanted.
     * @return the rows with their cursor values.
     */
    abstract List<KeyedQuery.Row<R>> getKeyedResultList(List<Metamodel<T, ?>> columns);

    @Override
    @SuppressWarnings("unchecked")
    public final Window<R> scroll(Scrollable<T> scrollable) {
        requireNonNull(scrollable, "scrollable");
        if (hasOrderBy()) {
            throw new PersistenceException("scroll manages ORDER BY internally; remove explicit orderBy calls.");
        }
        validateKey(scrollable.key());
        for (var order : scrollable.sort()) {
            validateSortField(order.field());
        }
        var orders = scrollable.orders();
        var position = scrollable.position();
        // A window before a row is read in the reversed ordering and turned around, so it comes back in the
        // request's sort order like every other window.
        boolean reverse = position != null && !position.after();
        QueryBuilder<T, R, ID> query = this;
        if (position != null) {
            var values = PositionImpl.of(position).values();
            if (values.size() != orders.size()) {
                throw new IllegalArgumentException(
                        "A position carries one value per sort field and one for the key: expected %d values, got %d."
                                .formatted(orders.size(), values.size()));
            }
            query = query.where(wb -> keysetPredicate(wb, orders, values, reverse));
        }
        for (var order : orders) {
            var field = (Metamodel<T, Object>) order.field();
            query = order.descending() ^ reverse ? query.orderByDescending(field) : query.orderBy(field);
        }
        int size = scrollable.size();
        var limited = (QueryBuilderImpl<T, R, ID>) query.limit(size + 1);
        List<KeyedQuery.Row<R>> rows = scrollable.key().isInline()
                ? limited.keyedRowsFromRecords(orders)
                : limited.getKeyedResultList(orders.stream().<Metamodel<T, ?>>map(order -> (Metamodel<T, ?>) order.field()).toList());
        boolean more = rows.size() > size;
        if (more) {
            rows = rows.subList(0, size);
        }
        if (reverse) {
            rows = rows.reversed();
        }
        List<R> content = rows.stream().map(KeyedQuery.Row::value).toList();
        Scrollable<T> next = null;
        Scrollable<T> previous = null;
        if (!rows.isEmpty()) {
            next = scrollable.after(rows.getLast().cursor());
            previous = scrollable.before(rows.getFirst().cursor());
        }
        // The anchor row of a position lies on the side the request continued from, so that side has rows; the
        // other side is decided by the extra row fetched.
        boolean hasNext = reverse || more;
        boolean hasPrevious = reverse ? more : position != null;
        return new Window<>(content, hasNext, hasPrevious, next, previous);
    }

    /**
     * Reads the cursor values from the mapped records rather than from the row. An inline record key spans several
     * columns and is compared as a whole, so its value is the record's own field, which only a result of the root
     * type carries.
     */
    @SuppressWarnings("unchecked")
    private List<KeyedQuery.Row<R>> keyedRowsFromRecords(List<Order> orders) {
        var rows = new ArrayList<KeyedQuery.Row<R>>();
        for (R value : getResultList()) {
            if (!getFromType().isInstance(value)) {
                throw new PersistenceException(
                        ("Scrolling by the inline key requires the result type to be %s, but the query selects %s; "
                        + "select the entity type, or scroll by a single-column key.")
                                .formatted(getFromType().getSimpleName(), value.getClass().getSimpleName()));
            }
            Object[] cursor = new Object[orders.size()];
            for (int i = 0; i < orders.size(); i++) {
                cursor[i] = ((Metamodel<T, Object>) orders.get(i).field()).getValue((T) value);
            }
            rows.add(new KeyedQuery.Row<>(value, cursor));
        }
        return rows;
    }

    /**
     * Builds the keyset predicate for continuing from a row: for each field in precedence, the fields before it
     * equal the row's values and the field itself lies beyond the row's value in its direction, all joined by OR.
     * Reversing flips every comparison, which reads the rows on the other side of the row.
     */
    @SuppressWarnings("unchecked")
    private static <T extends Data, R, ID> PredicateBuilder<T, ?, ?> keysetPredicate(WhereBuilder<T, R, ID> wb,
                                                                                    List<Order> orders,
                                                                                    List<Object> values,
                                                                                    boolean reverse) {
        PredicateBuilder<T, R, ID> chain = null;
        for (int i = 0; i < orders.size(); i++) {
            PredicateBuilder<T, R, ID> term = null;
            for (int j = 0; j < i; j++) {
                var field = (Metamodel<T, Object>) orders.get(j).field();
                var equal = wb.where(field, EQUALS, new Object[] {values.get(j)});
                term = term == null ? equal : term.and(equal);
            }
            var field = (Metamodel<T, Object>) orders.get(i).field();
            boolean descending = orders.get(i).descending() ^ reverse;
            var beyond = wb.where(field, descending ? LESS_THAN : GREATER_THAN, new Object[] {values.get(i)});
            term = term == null ? beyond : term.and(beyond);
            chain = chain == null ? term : chain.or(term);
        }
        return requireNonNull(chain);
    }

    /**
     * Validates that the key can address a row: it must not allow NULL, because {@code WHERE key > cursor} silently
     * excludes NULL rows.
     */
    private static <T extends Data> void validateKey(Metamodel.Key<T, ?> key) {
        if (key.isNullable()) {
            throw new PersistenceException(
                    ("Scrolling requires a non-nullable unique key, but '%s' allows NULL values. "
                    + "SQL comparisons with NULL silently exclude rows from the result set. "
                    + "Either make the field non-nullable (or a primitive type), or set "
                    + "@UK(nullsDistinct = false) if the database constraint prevents duplicate NULLs.")
                    .formatted(key.fieldPath()));
        }
    }

    /**
     * Validates that a sort field can position a row: a single column that cannot be NULL.
     */
    private static void validateSortField(Metamodel<?, ?> field) {
        if (field.isInline()) {
            throw new PersistenceException(
                    "Scrolling sorts by columns, but '%s' is an inline record.".formatted(field.fieldPath()));
        }
        if (MetamodelFactory.isNullable(field)) {
            throw new PersistenceException(
                    ("Scrolling requires non-nullable sort fields, but '%s' allows NULL values. "
                    + "SQL comparisons with NULL silently exclude rows from the result set.")
                    .formatted(field.fieldPath()));
        }
    }

    @Override
    protected Optional<Metamodel<T, ?>> getPrimaryKeyMetamodel() {
        return modelSupplier.get().getPrimaryKeyMetamodel().map(metamodel -> metamodel);
    }

    @Override
    protected Class<T> getFromType() {
        return fromType;
    }

    /**
     * Returns a typed query builder for the specified primary key type.
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param <X> the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.14
     */
    @Override
    public <X> QueryBuilder<T, R, X> typedId(Class<X> pkType) {
        requireNonNull(pkType, "pkType");
        Model<T, ID> model = modelSupplier.get();
        if (model.primaryKeyType() != pkType) {
            throw new PersistenceException("Primary key type mismatch: expected %s, got %s.".formatted(model.primaryKeyType().getName(), pkType.getName()));
        }
        //noinspection unchecked
        return (QueryBuilder<T, R, X>) this;
    }

    /**
     * Returns a query builder rooted at the specified type.
     *
     * @param rootType the type this query is rooted at.
     * @return the query builder, rooted at {@code rootType}.
     * @since 1.14
     */
    @Override
    @SuppressWarnings("unchecked")
    public <X extends Data> QueryBuilder<X, R, ID> narrow(Class<X> rootType) {
        requireNonNull(rootType, "rootType");
        if (fromType != rootType) {
            throw new PersistenceException("Root type mismatch: expected %s, got %s.".formatted(fromType.getName(), rootType.getName()));
        }
        return (QueryBuilder<X, R, ID>) this;
    }

    /**
     * Widens the query as a join does, without joining.
     *
     * @return the query builder, accepting paths from any entity in the query.
     * @since 1.14
     */
    @Override
    @SuppressWarnings("unchecked")
    public QueryBuilder<Data, R, ID> widen() {
        return (QueryBuilder<Data, R, ID>) this;
    }

    /**
     * Returns a new query builder instance with the specified parameters.
     *
     * @param queryTemplate the query template.
     * @param fromType the type of the table being queried.
     * @param join the list of joins.
     * @param where the list of where clauses.
     * @return a new query builder.
     */
    abstract QueryBuilder<T, R, ID> copyWith(QueryTemplate queryTemplate,
                                             Class<T> fromType,
                                             List<Join> join,
                                             List<Where> where,
                                             List<TemplateString> groupBy,
                                             List<TemplateString> having,
                                             List<TemplateString> orderBy);

    /**
     * Returns true to indicate that the query supports joins, false otherwise.
     *
     * @return true if the query supports joins, false otherwise.
     */
    protected abstract boolean supportsJoin();

    /**
     * Appends the registered joins and the WHERE clause, with multiple where-conditions AND-ed together, to the
     * given template.
     */
    protected final TemplateString appendJoinsAndWhere(TemplateString template) {
        if (!join.isEmpty()) {
            template = join.stream()
                    .reduce(template,
                            (acc, join) -> TemplateString.combine(acc, wrap(join)),
                            TemplateString::combine);
        }
        if (!where.isEmpty()) {
            if (where.size() == 1) {
                template = TemplateString.combine(template, TemplateString.of("\nWHERE "), wrap(where.getFirst()));
            } else {
                TemplateString whereClause = where.stream()
                        .map(w -> TemplateString.combine(TemplateString.of("("), wrap(w), TemplateString.of(")")))
                        .reduce((a, b) -> TemplateString.combine(a, TemplateString.of("\n  AND "), b))
                        .orElseThrow();
                template = TemplateString.combine(template, TemplateString.of("\nWHERE "), whereClause);
            }
        }
        return template;
    }

    /**
     * Resolving a reference selects the referenced table's columns into the row that holds the reference, which only a
     * statement that selects rows can do.
     */
    @Override
    public QueryBuilder<T, R, ID> fetch(List<? extends Navigable<T, ? extends Data>> paths) {
        throw new PersistenceException("Cannot resolve references for this query: only a select carries the referenced record back into the row that holds the reference.");
    }

    /**
     * Returns a new query builder instance with the specified {@code join} added to the list of joins.
     *
     * @param join the join to add.
     * @return a new query builder.
     */
    @SuppressWarnings("unchecked")
    private QueryBuilder<Data, R, ID> addJoin(Join join) {
        List<Join> copy = new ArrayList<>(this.join);
        copy.add(join);
        return (QueryBuilder<Data, R, ID>) copyWith(queryTemplate, fromType, copy, where, groupBy, having, orderBy);
    }

    /**
     * Returns the factory this query builds its subqueries with.
     *
     * @return the subquery factory for this query.
     * @since 1.13
     */
    @Override
    public SubqueryTemplate subqueryTemplate() {
        return queryTemplate;
    }

    /**
     * Returns a new query builder instance with the specified {@code where} added to the list of where clauses.
     *
     * @param where the where clause to add.
     * @return a new query builder.
     */
    private QueryBuilder<T, R, ID> addWhere(Where where) {
        List<Where> copy = new ArrayList<>(this.where);
        copy.add(where);
        return copyWith(queryTemplate, fromType, join, copy, groupBy, having, orderBy);
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    public QueryBuilder<T, R, ID> orderBy(TemplateString template) {
        List<TemplateString> copy = new ArrayList<>(orderBy);
        copy.add(template);
        return copyWith(queryTemplate, fromType, join, where, groupBy, having, copy);
    }

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    public QueryBuilder<T, R, ID> groupBy(TemplateString template) {
        List<TemplateString> copy = new ArrayList<>(groupBy);
        copy.add(template);
        return copyWith(queryTemplate, fromType, join, where, copy, having, orderBy);
    }

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    public QueryBuilder<T, R, ID> having(TemplateString template) {
        List<TemplateString> copy = new ArrayList<>(having);
        copy.add(template);
        return copyWith(queryTemplate, fromType, join, where, groupBy, copy, orderBy);
    }

    /**
     * Returns {@code true} if any ORDER BY columns have been added to this query builder.
     *
     * @return {@code true} if ORDER BY columns are present, {@code false} otherwise.
     * @since 1.9
     */
    public boolean hasOrderBy() {
        return !orderBy.isEmpty();
    }

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<Data, R, ID> crossJoin(Class<? extends Data> relation) {
        return join(cross(), relation, "").on(TemplateString.EMPTY);
    }

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> innerJoin(Class<? extends Data> relation) {
        return join(inner(), relation, "");
    }

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> leftJoin(Class<? extends Data> relation) {
        return join(left(), relation, "");
    }

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> rightJoin(Class<? extends Data> relation) {
        return join(right(), relation, "");
    }

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> join(JoinType type, TemplateString template, String alias) {
        requireNonNull(type, "type");
        requireNonNull(type, "template");
        requireNonNull(alias, "alias");
        return onTemplate -> addJoin(new Join(new TemplateSource(template), alias, new TemplateTarget(onTemplate), type, false));
    }

    /**
     * Adds a join of the specified type to the query using a subquery.
     *
     * @param type the join type.
     * @param subquery the subquery to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> join(JoinType type, QueryBuilder<?, ?, ?> subquery, String alias) {
        requireNonNull(type, "type");
        requireNonNull(type, "subquery");
        requireNonNull(alias, "alias");
        return onTemplate -> addJoin(new Join(new TemplateSource(wrap(subquery)), alias, new TemplateTarget(onTemplate), type, false));
    }

    /**
     * Adds a cross join to the query.
     *
     * @param template the template to join.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<Data, R, ID> crossJoin(TemplateString template) {
        return join(cross(), template, "").on(TemplateString.EMPTY);
    }

    /**
     * Adds an inner join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> innerJoin(TemplateString template, String alias) {
        return join(inner(), template, alias);
    }

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> leftJoin(TemplateString template, String alias) {
        return join(left(), template, alias);
    }

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> rightJoin(TemplateString template, String alias) {
        return join(right(), template, alias);
    }

    /**
     * Adds a join of the specified type to the query.
     *
     * @param type the type of the join (e.g., INNER, LEFT, RIGHT).
     * @param relation the relation to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> join(JoinType type, Class<? extends Data> relation, String alias) {
        if (!supportsJoin()) {
            throw new UnsupportedOperationException("Joins are not supported in this query.");
        }
        requireNonNull(type, "type");
        requireNonNull(relation, "relation");
        requireNonNull(alias, "alias");
        return new TypedJoinBuilder<>() {
            @Override
            public QueryBuilder<Data, R, ID> on(Class<? extends Data> onRelation) {
                return addJoin(new Join(new TableSource(relation), alias, new TableTarget(onRelation), type, false));
            }

            @Override
            public QueryBuilder<Data, R, ID> on(TemplateString onTemplate) {
                return addJoin(new Join(new TableSource(relation), alias, new TemplateTarget(onTemplate), type, false));
            }
        };
    }

    // Define the raw templates
    private static final TemplateString RAW_AND = TemplateString.of(" AND ");
    private static final TemplateString RAW_OR = TemplateString.of(" OR ");
    private static final TemplateString RAW_OPEN = TemplateString.of("(");
    private static final TemplateString RAW_CLOSE = TemplateString.of(")");

    static class PredicateBuilderImpl<TX extends Data, RX, IDX> implements PredicateBuilder<TX, RX, IDX> {
        private final List<TemplateString> templates = new ArrayList<>();
        private final boolean safe;

        PredicateBuilderImpl(TemplateString template) {
            this(template, true);
        }

        PredicateBuilderImpl(TemplateString template, boolean safe) {
            templates.add(requireNonNull(template, "template"));
            this.safe = safe;
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> and(PredicateBuilder<? extends TX, ?, ?> predicate) {
            add(RAW_AND, predicate);
            return this;
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> and(TemplateString template) {
            add(RAW_AND, combine(RAW_OPEN, template, RAW_CLOSE));   // Always wrap a template in parentheses as we don't know if it's a single expression or a complex one.
            return this;
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> or(PredicateBuilder<? extends TX, ?, ?> predicate) {
            add(RAW_OR, predicate);
            return this;
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> or(TemplateString template) {
            add(RAW_OR, combine(RAW_OPEN, template, RAW_CLOSE));
            return this;
        }

        private void add(TemplateString operator, PredicateBuilder<?, ?, ?> predicate) {
            var list = ((PredicateBuilderImpl<?, ?, ?>) predicate).templates;
            assert !list.isEmpty();
            if (list.size() > 1 || !((PredicateBuilderImpl<?, ?, ?>) predicate).safe) {
                var wrap = new ArrayList<TemplateString>();
                wrap.add(RAW_OPEN);
                wrap.addAll(list);
                wrap.add(RAW_CLOSE);
                add(operator, combine(wrap));
            } else {
                add(operator, list.getFirst());
            }
        }

        private void add(TemplateString operator, TemplateString template) {
            if (templates.size() == 1 && !safe) {
                // Wrap the first template in parentheses if it's the only one.
                templates.addFirst(RAW_OPEN);
                templates.addLast(RAW_CLOSE);
            }
            templates.add(operator);
            templates.add(template);
        }

        /**
         * Returns the predicate as a single template, for a clause that carries its conditions as templates rather
         * than as {@link Where} elements.
         */
        private TemplateString asTemplate() {
            return combine(templates);
        }
    }

    static class WhereBuilderImpl<TX extends Data, RX, IDX> extends WhereBuilder<TX, RX, IDX> {
        private final QueryBuilderImpl<TX, RX, IDX> queryBuilder;

        WhereBuilderImpl(QueryBuilderImpl<TX, RX, IDX> queryBuilder) {
            this.queryBuilder = queryBuilder;
        }

        @Override
        public <F extends Data> QueryBuilder<F, ?, ?> subquery(Class<F> fromType, TemplateString template) {
            return queryBuilder.queryTemplate.subquery(fromType, template);
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> exists(QueryBuilder<?, ?, ?> subquery) {
            return new PredicateBuilderImpl<>(TemplateString.raw("EXISTS (\0)", subquery));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> notExists(QueryBuilder<?, ?, ?> subquery) {
            return new PredicateBuilderImpl<>(TemplateString.raw("NOT EXISTS (\0)", subquery));
        }

        /**
         * Returns the root primary-key metamodel to pin on id/ref predicates, or {@code null} when the model has no
         * single primary-key path. Pinning lets binding resolve the target column directly instead of re-deriving it
         * from the value's runtime type on every execution; passing {@code null} falls back to that derivation, so the
         * behavior is identical to the unpinned path for models without a primary key.
         */
        @Nullable
        private Metamodel<?, ?> primaryKeyMetamodel() {
            return queryBuilder.modelSupplier.get().getPrimaryKeyMetamodel().orElse(null);
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereId(IDX id) {
            // whereId targets the root primary key by contract, so pin its metamodel.
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(primaryKeyMetamodel(), EQUALS, id)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereRef(Ref<TX> ref) {
            // A ref to the root entity resolves to its primary key, so pin the primary-key metamodel.
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(primaryKeyMetamodel(), EQUALS, ref)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> where(TX record) {
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(EQUALS, record)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereId(Iterable<? extends IDX> it) {
            // whereId targets the root primary key by contract, so pin its metamodel.
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(primaryKeyMetamodel(), IN, it)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereRef(Iterable<? extends Ref<TX>> it) {
            // Refs to the root entity resolve to its primary key, so pin the primary-key metamodel.
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(primaryKeyMetamodel(), IN, it)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> where(Iterable<? extends TX> it) {
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(IN, it)));
        }

        @Override
        public <V extends Data> PredicateBuilder<TX, RX, IDX> where(Navigable<? extends TX, V> path, Ref<V> ref) {
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(path.asMetamodel(), EQUALS, ref)));
        }

        @Override
        public <V extends Data> PredicateBuilder<TX, RX, IDX> whereRef(Navigable<? extends TX, V> path, Iterable<? extends Ref<V>> it) {
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(path.asMetamodel(), IN, it)));
        }

        @Override
        public <V> PredicateBuilder<TX, RX, IDX> where(Navigable<? extends TX, V> path, Operator operator, Iterable<? extends V> it) {
            return new PredicateBuilderImpl<>(wrap(new ObjectExpression(path.asMetamodel(), operator, it)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> where(TemplateString template) {
            return new PredicateBuilderImpl<>(template, false);
        }

        @Override
        protected <V> PredicateBuilder<TX, RX, IDX> whereImpl(Navigable<?, V> path, Operator operator, V[] o) {
            try {
                try {
                    return PredicateBuilderFactory.createWithId(path.asMetamodel(), operator, List.of(o));
                } catch (NullPointerException e) {
                    throw new SqlTemplateException("Null value not allowed.");
                }
            } catch (SqlTemplateException e) {
                throw new PersistenceException(e);
            }
        }

        private Object unwrap(TemplateString template) {
            if (template.fragments().equals(List.of("", ""))) {
                return template.values().getFirst();
            }
            return null;
        }

        private QueryBuilder<TX, RX, IDX> build(TemplateString template) {
            Where where;
            if (unwrap(template) instanceof Expression expression) {
                where = new Where(expression, null);
            } else {
                where = new Where(new TemplateExpression(template), null);
            }
            return queryBuilder.addWhere(where);
        }
    }

    /**
     * Adds a WHERE clause to the query using a {@link WhereBuilder}.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> where(Function<WhereBuilder<T, R, ID>, PredicateBuilder<T, ?, ?>> predicate) {
        requireNonNull(predicate, "predicate");
        var whereBuilder = new WhereBuilderImpl<>(this);
        return whereBuilder.build(((PredicateBuilderImpl<T, ?, ?>) predicate.apply(whereBuilder)).asTemplate());
    }

    /**
     * Adds a HAVING clause to the query for the specified predicate.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     * @since 1.13
     */
    @Override
    public QueryBuilder<T, R, ID> having(PredicateBuilder<? extends T, ?, ?> predicate) {
        return addHaving(predicate);
    }

    /**
     * Appends the predicate to the HAVING clause, which carries its conditions as templates rather than as
     * {@code Where} elements.
     */
    private QueryBuilder<T, R, ID> addHaving(PredicateBuilder<?, ?, ?> predicate) {
        requireNonNull(predicate, "predicate");
        return having(((PredicateBuilderImpl<?, ?, ?>) predicate).asTemplate());
    }
}
