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
package st.orm.template.impl;

import static java.util.Objects.requireNonNull;
import static st.orm.JoinType.cross;
import static st.orm.JoinType.inner;
import static st.orm.JoinType.left;
import static st.orm.JoinType.right;
import static st.orm.template.impl.StringTemplates.convert;

import jakarta.annotation.Nonnull;
import java.util.function.Function;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.JoinType;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.core.spi.ORMReflection;
import st.orm.core.spi.Providers;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.Subqueryable;
import st.orm.template.JoinBuilder;
import st.orm.template.PredicateBuilder;
import st.orm.template.Query;
import st.orm.template.QueryBuilder;
import st.orm.template.TypedJoinBuilder;
import st.orm.template.WhereBuilder;

public final class QueryBuilderImpl<T extends Data, R, ID> extends QueryBuilder<T, R, ID> implements Subqueryable {
    private final static ORMReflection REFLECTION = Providers.getORMReflection();

    private final st.orm.core.template.QueryBuilder<T, R, ID> core;

    public QueryBuilderImpl(@Nonnull st.orm.core.template.QueryBuilder<T, R, ID> core) {
        this.core = requireNonNull(core, "core");
    }

    /**
     * Returns a typed query builder for the specified primary key type.
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param <X> the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.2
     */
    @Override
    public <X> QueryBuilder<T, R, X> typed(@Nonnull Class<X> pkType) {
        //noinspection unchecked
        return new QueryBuilderImpl<>(core.typed((Class<X>) REFLECTION.getType(pkType)));
    }

    /**
     * Returns a query builder that allows UPDATE and DELETE queries without a WHERE clause.
     *
     * <p>By default, Storm rejects UPDATE and DELETE queries that lack a WHERE clause, throwing a
     * {@link PersistenceException}. Call this method to disable that check when you intentionally want to affect all
     * rows in the table.</p>
     *
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> unsafe() {
        return new QueryBuilderImpl<>(core.unsafe());
    }

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> distinct() {
        return new QueryBuilderImpl<>(core.distinct());
    }

    /**
     * Returns a processor that can be used to append the query with a string template.
     *
     * @param template the string template to append.
     * @return a processor that can be used to append the query with a string template.
     */
    @Override
    public QueryBuilder<T, R, ID> append(@Nonnull StringTemplate template) {
        return new QueryBuilderImpl<>(core.append(convert(template)));
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    protected QueryBuilder<T, R, ID> orderBy(@Nonnull StringTemplate template) {
        return new QueryBuilderImpl<>(core.orderBy(convert(template)));
    }

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    protected QueryBuilder<T, R, ID> groupBy(@Nonnull StringTemplate template) {
        return new QueryBuilderImpl<>(core.groupBy(convert(template)));
    }

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    protected QueryBuilder<T, R, ID> having(@Nonnull StringTemplate template) {
        return new QueryBuilderImpl<>(core.having(convert(template)));
    }

    /**
     * Returns {@code true} if any ORDER BY columns have been added to this query builder.
     *
     * @return {@code true} if ORDER BY columns are present, {@code false} otherwise.
     * @since 1.9
     */
    @Override
    protected boolean hasOrderBy() {
        return core.hasOrderBy();
    }

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> forShare() {
        return new QueryBuilderImpl<>(core.forShare());
    }

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> forUpdate() {
        return new QueryBuilderImpl<>(core.forUpdate());
    }

    /**
     * Locks the selected rows using a custom lock mode.
     *
     * <p><strong>Note:</strong> This method results in non-portable code, as the lock mode is specific to the underlying database.</p>
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> forLock(@Nonnull StringTemplate template) {
        return new QueryBuilderImpl<>(core.forLock(convert(template)));
    }

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    @Override
    public Query build() {
        return new QueryImpl(core.build());
    }

    /**
     * Executes the query and returns a stream of results.
     *
     * <p>The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must
     * be closed after usage to prevent resource leaks.</p>
     *
     * @return a stream of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    @Override
    public Stream<R> getResultStream() {
        return core.getResultStream();
    }

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> crossJoin(@Nonnull Class<? extends Data> relation) {
        return join(cross(), relation, "").on(convert(TemplateString.EMPTY));
    }

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> innerJoin(@Nonnull Class<? extends Data> relation) {
        return join(inner(), relation, "");
    }

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> leftJoin(@Nonnull Class<? extends Data> relation) {
        return join(left(), relation, "");
    }

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    @Override
    public TypedJoinBuilder<T, R, ID> rightJoin(@Nonnull Class<? extends Data> relation) {
        return join(right(), relation, "");
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
    public TypedJoinBuilder<T, R, ID> join(@Nonnull JoinType type, @Nonnull Class<? extends Data> relation, @Nonnull String alias) {
        st.orm.core.template.TypedJoinBuilder<T, R, ID> joinBuilder = core.join(type, relation, alias);
        return new TypedJoinBuilder<>() {
            @Override
            public QueryBuilder<T, R, ID> on(@Nonnull Class<? extends Data> relation) {
                return new QueryBuilderImpl<>(joinBuilder.on(relation));
            }

            @Override
            public QueryBuilder<T, R, ID> on(@Nonnull StringTemplate template) {
                return new QueryBuilderImpl<>(joinBuilder.on(convert(template)));
            }
        };
    }

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> crossJoin(@Nonnull StringTemplate template) {
        return join(cross(), template, "").on(convert(TemplateString.EMPTY));
    }

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> innerJoin(@Nonnull StringTemplate template, @Nonnull String alias) {
        return join(inner(), template, alias);
    }

    /**
     * Adds a left join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> leftJoin(@Nonnull StringTemplate template, @Nonnull String alias) {
        return join(left(), template, alias);
    }

    /**
     * Adds a right join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    @Override
    public JoinBuilder<T, R, ID> rightJoin(@Nonnull StringTemplate template, @Nonnull String alias) {
        return join(right(), template, alias);
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
    public JoinBuilder<T, R, ID> join(@Nonnull JoinType type, @Nonnull StringTemplate template, @Nonnull String alias) {
        st.orm.core.template.JoinBuilder<T, R, ID> joinBuilder = core.join(type, convert(template), alias);
        return onTemplate -> new QueryBuilderImpl<>(joinBuilder.on(convert(onTemplate)));
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
    public JoinBuilder<T, R, ID> join(@Nonnull JoinType type, @Nonnull QueryBuilder<?, ?, ?> subquery, @Nonnull String alias) {
        st.orm.core.template.JoinBuilder<T, R, ID> joinBuilder = core.join(type, ((QueryBuilderImpl<?, ?, ?>) subquery).core, alias);
        return onTemplate -> new QueryBuilderImpl<>(joinBuilder.on(convert(onTemplate)));
    }

    static class PredicateBuilderImpl<TX extends Data, RX, IDX> implements PredicateBuilder<TX, RX, IDX> {
        final st.orm.core.template.PredicateBuilder<TX, RX, IDX> core;

        PredicateBuilderImpl(@Nonnull st.orm.core.template.PredicateBuilder<TX, RX, IDX> core) {
            this.core = core;
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> and(@Nonnull PredicateBuilder<TX, ?, ?> predicate) {
            return new PredicateBuilderImpl<>(core.and(((PredicateBuilderImpl<TX, ?, ?>) predicate).core));
        }

        @Override
        public <TY extends Data, RY, IDY> PredicateBuilder<TY, RY, IDY> andAny(@Nonnull PredicateBuilder<TY, RY, IDY> predicate) {
            return new PredicateBuilderImpl<>(core.andAny(((PredicateBuilderImpl<TY, RY, IDY>) predicate).core));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> and(@Nonnull StringTemplate template) {
            return new PredicateBuilderImpl<>(core.and(convert(template)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> or(@Nonnull PredicateBuilder<TX, ?, ?> predicate) {
            return new PredicateBuilderImpl<>(core.or(((PredicateBuilderImpl<TX, ?, ?>) predicate).core));
        }

        @Override
        public <TY extends Data, RY, IDY> PredicateBuilder<TY, RY, IDY> orAny(@Nonnull PredicateBuilder<TY, RY, IDY> predicate) {
            return new PredicateBuilderImpl<>(core.orAny(((PredicateBuilderImpl<TY, RY, IDY>) predicate).core));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> or(@Nonnull StringTemplate template) {
            return new PredicateBuilderImpl<>(core.or(convert(template)));
        }
    }

    static class WhereBuilderImpl<TX extends Data, RX, IDX> extends WhereBuilder<TX, RX, IDX> {
        private final st.orm.core.template.WhereBuilder<TX, RX, IDX> core;

        private WhereBuilderImpl(@Nonnull st.orm.core.template.WhereBuilder<TX, RX, IDX> core) {
            this.core = core;
        }

        @Override
        public <T extends Data> QueryBuilder<T, ?, ?> subquery(@Nonnull Class<T> fromType, @Nonnull StringTemplate template) {
            return new QueryBuilderImpl<>(core.subquery(fromType, convert(template)));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> exists(@Nonnull QueryBuilder<?, ?, ?> subquery) {
            return new PredicateBuilderImpl<>(core.exists(((QueryBuilderImpl<?, ?, ?>) subquery).core));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> notExists(@Nonnull st.orm.template.QueryBuilder<?, ?, ?> subquery) {
            return new PredicateBuilderImpl<>(core.notExists(((QueryBuilderImpl<?, ?, ?>) subquery).core));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereId(@Nonnull IDX id) {
            return new PredicateBuilderImpl<>(core.whereId(id));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereRef(@Nonnull Ref<TX> ref) {
            return new PredicateBuilderImpl<>(core.whereRef(ref));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereAnyRef(@Nonnull Ref<?> ref) {
            return new PredicateBuilderImpl<>(core.whereAnyRef(ref));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> where(@Nonnull TX record) {
            return new PredicateBuilderImpl<>(core.where(record));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereAny(@Nonnull Data record) {
            return new PredicateBuilderImpl<>(core.whereAny(record));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereId(@Nonnull Iterable<? extends IDX> it) {
            return new PredicateBuilderImpl<>(core.whereId(it));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereRef(@Nonnull Iterable<? extends Ref<TX>> it) {
            return new PredicateBuilderImpl<>(core.whereRef(it));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereAnyRef(@Nonnull Iterable<? extends Ref<?>> it) {
            return new PredicateBuilderImpl<>(core.whereAnyRef(it));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> where(@Nonnull Iterable<? extends TX> it) {
            return new PredicateBuilderImpl<>(core.where(it));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> whereAny(@Nonnull Iterable<? extends Data> it) {
            return new PredicateBuilderImpl<>(core.whereAny(it));
        }

        @Override
        public <V extends Data> PredicateBuilder<TX, RX, IDX> where(@Nonnull Metamodel<TX, V> path, @Nonnull Ref<V> ref) {
            return new PredicateBuilderImpl<>(core.where(path, ref));
        }

        @Override
        public <V extends Data> PredicateBuilder<TX, RX, IDX> whereAny(@Nonnull Metamodel<?, V> path, @Nonnull Ref<V> ref) {
            return new PredicateBuilderImpl<>(core.whereAny(path, ref));
        }

        @Override
        public <V extends Data> PredicateBuilder<TX, RX, IDX> whereRef(@Nonnull Metamodel<TX, V> path, @Nonnull Iterable<? extends Ref<V>> it) {
            return new PredicateBuilderImpl<>(core.whereRef(path, it));
        }

        @Override
        public <V extends Data> PredicateBuilder<TX, RX, IDX> whereAnyRef(@Nonnull Metamodel<?, V> path, @Nonnull Iterable<? extends Ref<V>> it) {
            return new PredicateBuilderImpl<>(core.whereAnyRef(path, it));
        }

        @Override
        public <V> PredicateBuilder<TX, RX, IDX> where(@Nonnull Metamodel<TX, V> path, @Nonnull Operator operator, @Nonnull Iterable<? extends V> it) {
            return new PredicateBuilderImpl<>(core.where(path, operator, it));
        }

        @Override
        public <V> PredicateBuilder<TX, RX, IDX> whereAny(@Nonnull Metamodel<?, V> path, @Nonnull Operator operator, @Nonnull Iterable<? extends V> it) {
            return new PredicateBuilderImpl<>(core.whereAny(path, operator, it));
        }

        @Override
        public PredicateBuilder<TX, RX, IDX> where(@Nonnull StringTemplate template) throws PersistenceException {
            return new PredicateBuilderImpl<>(core.where(convert(template)));
        }

        @Override
        protected <V> PredicateBuilder<TX, RX, IDX> whereImpl(@Nonnull Metamodel<?, V> path, @Nonnull Operator operator, @Nonnull V[] o) {
            return new PredicateBuilderImpl<>(core.whereAny(path, operator, o));
        }
    }

    /**
     * Adds a WHERE clause to the query using a {@link WhereBuilder}.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> where(@Nonnull Function<WhereBuilder<T, R, ID>, PredicateBuilder<T, ?, ?>> predicate) {
        return new QueryBuilderImpl<>(core.where(whereBuilder -> {
            var builder = predicate.apply(new WhereBuilderImpl<>(whereBuilder));
            return ((PredicateBuilderImpl<T, ?, ?>) builder).core;
        }));
    }

    /**
     * Adds a WHERE clause to the query using a {@link WhereBuilder}.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    @Override
    public QueryBuilder<T, R, ID> whereAny(@Nonnull Function<WhereBuilder<T, R, ID>, PredicateBuilder<?, ?, ?>> predicate) {
        return new QueryBuilderImpl<>(core.whereAny(whereBuilder -> {
            var builder = predicate.apply(new WhereBuilderImpl<>(whereBuilder));
            return ((PredicateBuilderImpl<?, ?, ?>) builder).core;
        }));
    }

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> limit(int limit) {
        return new QueryBuilderImpl<>(core.limit(limit));
    }

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    @Override
    public QueryBuilder<T, R, ID> offset(int offset) {
        return new QueryBuilderImpl<>(core.offset(offset));
    }

    @Override
    public Window<R> scroll(int size) {
        return core.scroll(size);
    }

    @Override
    public Window<R> scroll(@Nonnull Scrollable<T> scrollable) {
        return core.scroll(scrollable);
    }

    @Override
    public TemplateString getSubquery() {
        return ((Subqueryable) core).getSubquery();
    }
}
