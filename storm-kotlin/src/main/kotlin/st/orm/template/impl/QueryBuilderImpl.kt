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
package st.orm.template.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.stream.consumeAsFlow
import st.orm.*
import st.orm.core.template.impl.Subqueryable
import st.orm.template.*
import java.util.stream.Stream
import kotlin.reflect.KClass

internal class QueryBuilderImpl<T : Data, R, ID>(
    private val core: st.orm.core.template.QueryBuilder<T, R, ID>,
) : QueryBuilder<T, R, ID>(),
    Subqueryable {

    /**
     * Returns a typed query builder for the specified primary key type.
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param <X> the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.14
     */
    override fun <X : Any> typedId(pkType: KClass<X>): QueryBuilder<T, R, X> = QueryBuilderImpl<T, R, X>(core.typedId<X>(pkType.java))

    override fun <X : Data> narrow(rootType: KClass<X>): QueryBuilder<X, R, ID> = QueryBuilderImpl(core.narrow(rootType.java))

    override fun widen(): QueryBuilder<Data, R, ID> = QueryBuilderImpl(core.widen())

    /**
     * Returns a query builder that does not require a WHERE clause for UPDATE and DELETE queries.
     *
     *
     * This method is used to prevent accidental updates or deletions of all records in a table when a WHERE clause
     * is not provided.
     *
     * @since 1.2
     */
    override fun unsafe(): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.unsafe())

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    override fun distinct(): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.distinct())

    override fun fetch(paths: List<Navigable<T, out Data>>): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.fetch(paths))

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    override fun orderBy(template: TemplateString): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.orderBy(template.unwrap))

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    override fun groupBy(template: TemplateString): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.groupBy(template.unwrap))

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    override fun having(template: TemplateString): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.having(template.unwrap))

    /**
     * Returns `true` if any ORDER BY columns have been added to this query builder.
     *
     * @return `true` if ORDER BY columns are present, `false` otherwise.
     * @since 1.9
     */
    override fun hasOrderBy(): Boolean = core.hasOrderBy()

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    override fun forShare(): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.forShare())

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    override fun forUpdate(): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.forUpdate())

    /**
     * Locks the selected rows using a custom lock mode.
     *
     *
     * **Note:** This method results in non-portable code, as the lock mode is specific to the underlying database.
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     * @since 1.2
     */
    override fun forLock(template: TemplateString): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.forLock(template.unwrap))

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    override fun build(): Query = QueryImpl(core.build())

    override val resultStream: Stream<R>
        /**
         * Executes the query and returns a stream of results.
         *
         *
         * The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
         * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
         * dealing with large volumes of records.
         *
         *
         * **Note:** Calling this method does trigger the execution of the underlying query, so it should
         * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must
         * be closed after usage to prevent resource leaks.
         *
         * @return a stream of results.
         * @throws PersistenceException if the query operation fails due to underlying database issues, such as
         * connectivity.
         */
        get() = core.getResultStream()

    override val resultCount: Long
        /**
         * Delegates to the core builder, which executes a dedicated count query derived from the builder's shape.
         */
        get() = core.resultCount

    override val resultList: List<R>
        /**
         * Eager terminals delegate to the core builder, which executes them without the fetch-size hint,
         * avoiding transaction wrapping for eagerly consumed results.
         */
        get() = core.resultList

    override val singleResult: R
        get() = core.singleResult

    override val optionalResult: R?
        get() = core.optionalResult.orElse(null)

    /**
     * Executes the query and returns the results grouped by the record reached via [path], in encounter order.
     *
     * @param path the metamodel path from the table type to the record to group by.
     * @return the results grouped by the record reached via [path], in encounter order.
     */
    @Suppress("UNCHECKED_CAST")
    override fun <V : Data> resultGroupedBy(path: TypedMetamodel<T, V, out V?>): Map<V, List<R>> = core.getResultGroupedBy(path as TypedMetamodel<T, V, V>)

    /**
     * Executes the query and returns the results grouped by a ref to the record reached via [path], in encounter
     * order.
     *
     * @param path the metamodel path from the table type to the record to group by.
     * @return the results grouped by a ref to the record reached via [path], in encounter order.
     */
    override fun <V : Data> resultGroupedByRef(path: Metamodel<T, V>): Map<Ref<V>, List<R>> = core.getResultGroupedByRef(path)

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    override fun crossJoin(relation: KClass<out Data>): QueryBuilder<Data, R, ID> = join(JoinType.cross(), relation, "").on { t("") }

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    override fun innerJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID> = join(JoinType.inner(), relation, "")

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    override fun leftJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID> = join(JoinType.left(), relation, "")

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    override fun rightJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID> = join(JoinType.right(), relation, "")

    /**
     * Adds a join of the specified type to the query.
     *
     * @param type the type of the join (e.g., INNER, LEFT, RIGHT).
     * @param relation the relation to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    override fun join(
        type: JoinType,
        relation: KClass<out Data>,
        alias: String,
    ): TypedJoinBuilder<T, R, ID> {
        val joinBuilder = core.join(type, relation.java, alias)
        return object : TypedJoinBuilder<T, R, ID>() {
            override fun on(relation: KClass<out Data>): QueryBuilder<Data, R, ID> = QueryBuilderImpl(joinBuilder.on(relation.java))

            override fun on(template: TemplateString): QueryBuilder<Data, R, ID> = QueryBuilderImpl<Data, R, ID>(joinBuilder.on(template.unwrap))
        }
    }

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    override fun crossJoin(template: TemplateString): QueryBuilder<Data, R, ID> = join(JoinType.cross(), template, "").on { t("") }

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    override fun innerJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID> = join(JoinType.inner(), template, alias)

    /**
     * Adds a left join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    override fun leftJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID> = join(JoinType.left(), template, alias)

    /**
     * Adds a right join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    override fun rightJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID> = join(JoinType.right(), template, alias)

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    override fun join(
        type: JoinType,
        template: TemplateString,
        alias: String,
    ): JoinBuilder<T, R, ID> {
        val joinBuilder = core.join(type, template.unwrap, alias)
        return object : JoinBuilder<T, R, ID>() {
            override fun on(template: TemplateString): QueryBuilder<Data, R, ID> = QueryBuilderImpl<Data, R, ID>(joinBuilder.on(template.unwrap))
        }
    }

    /**
     * Adds a join of the specified type to the query using a subquery.
     *
     * @param type the join type.
     * @param subquery the subquery to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    override fun join(
        type: JoinType,
        subquery: QueryBuilder<*, *, *>,
        alias: String,
    ): JoinBuilder<T, R, ID> {
        val joinBuilder = core.join(type, (subquery as QueryBuilderImpl<*, *, *>).core, alias)
        return object : JoinBuilder<T, R, ID>() {
            override fun on(template: TemplateString): QueryBuilder<Data, R, ID> = QueryBuilderImpl<Data, R, ID>(joinBuilder.on(template.unwrap))
        }
    }

    internal class PredicateBuilderImpl<TX : Data, RX, IDX>(
        val core: st.orm.core.template.PredicateBuilder<TX, RX, IDX>,
    ) : PredicateBuilder<TX, RX, IDX> {
        override fun and(template: TemplateString): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.and(template.unwrap))

        override fun or(template: TemplateString): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.or(template.unwrap))
    }

    internal class WhereBuilderImpl<TX : Data, RX, IDX>(
        val core: st.orm.core.template.WhereBuilder<TX, RX, IDX>,
    ) : WhereBuilder<TX, RX, IDX> {
        override fun <T : Data> subquery(fromType: KClass<T>, template: TemplateString): QueryBuilder<T, *, *> = QueryBuilderImpl(core.subquery(fromType.java, template.unwrap))

        override fun exists(subquery: QueryBuilder<*, *, *>): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.exists((subquery as QueryBuilderImpl<*, *, *>).core))

        override fun notExists(subquery: QueryBuilder<*, *, *>): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.notExists((subquery as QueryBuilderImpl<*, *, *>).core))

        override fun whereId(id: IDX): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.whereId(id))

        override fun whereRef(ref: Ref<out TX>): PredicateBuilder<TX, RX, IDX> {
            // The ref is only read for its id and type, so re-labelling the covariant ref to the core's invariant
            // parameter is safe.
            @Suppress("UNCHECKED_CAST")
            return PredicateBuilderImpl<TX, RX, IDX>(core.whereRef(ref as Ref<TX>))
        }

        override fun where(record: TX): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.where(record))

        override fun whereId(it: Iterable<IDX>): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.whereId(it))

        override fun whereRef(it: Iterable<Ref<out TX>>): PredicateBuilder<TX, RX, IDX> {
            // The refs are only read for their id and type, so re-labelling the covariant refs to the core's
            // invariant parameter is safe.
            @Suppress("UNCHECKED_CAST")
            return PredicateBuilderImpl<TX, RX, IDX>(core.whereRef(it as Iterable<Ref<TX>>))
        }

        override fun where(it: Iterable<TX>): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.where(it))

        override fun <V : Data> where(
            path: Navigable<out TX, V>,
            ref: Ref<V>,
        ): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.where<V>(path, ref))

        override fun <V : Data> whereRef(
            path: Navigable<out TX, V>,
            it: Iterable<Ref<V>>,
        ): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.whereRef<V>(path, it))

        override fun <V> where(
            path: Navigable<out TX, V>,
            operator: Operator,
            it: Iterable<V>,
        ): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.where<V>(path.asMetamodel(), operator, it))

        override fun where(template: TemplateString): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl<TX, RX, IDX>(core.where((template as TemplateStringHolder).templateString))

        override infix fun PredicateBuilder<out TX, *, *>.and(predicate: PredicateBuilder<out TX, *, *>): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl((this as PredicateBuilderImpl<TX, RX, IDX>).core.and((predicate as PredicateBuilderImpl<TX, *, *>).core))

        override infix fun PredicateBuilder<out TX, *, *>.or(predicate: PredicateBuilder<out TX, *, *>): PredicateBuilder<TX, RX, IDX> = PredicateBuilderImpl((this as PredicateBuilderImpl<TX, RX, IDX>).core.or((predicate as PredicateBuilderImpl<TX, *, *>).core))
    }

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    override fun whereBuilder(predicate: (WhereBuilder<T, R, ID>) -> PredicateBuilder<out T, *, *>): QueryBuilder<T, R, ID> = QueryBuilderImpl(
        core.where { whereBuilder ->
            val builder = predicate(WhereBuilderImpl(whereBuilder))
            (builder as PredicateBuilderImpl<T, *, *>).core
        },
    )

    /**
     * Adds a HAVING clause to the query for the specified predicate.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     * @since 1.13
     */
    override fun having(predicate: PredicateBuilder<out T, *, *>): QueryBuilder<T, R, ID> = QueryBuilderImpl(core.having((predicate as PredicateBuilderImpl<T, *, *>).core))

    /**
     * Adds a HAVING clause that keeps the groups for which [subquery] returns at least one row.
     *
     * @param subquery the subquery to test for existence.
     * @return the query builder.
     * @since 1.13
     */
    override fun havingExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = QueryBuilderImpl(core.havingExists((subquery as QueryBuilderImpl<*, *, *>).core))

    /**
     * Adds a HAVING clause that keeps the groups for which [subquery] returns no rows.
     *
     * @param subquery the subquery to test for absence.
     * @return the query builder.
     * @since 1.13
     */
    override fun havingNotExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = QueryBuilderImpl(core.havingNotExists((subquery as QueryBuilderImpl<*, *, *>).core))

    /**
     * Returns the factory this query builds its subqueries with.
     *
     * @return the subquery factory for this query.
     * @since 1.13
     */
    override fun subqueryTemplate(): SubqueryTemplate = object : SubqueryTemplate {
        override fun <X : Data> subquery(fromType: KClass<X>, template: TemplateString): QueryBuilder<X, *, *> = QueryBuilderImpl(core.subqueryTemplate().subquery(fromType.java, template.unwrap))
    }

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    override fun limit(limit: Int): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.limit(limit))

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    override fun offset(offset: Int): QueryBuilder<T, R, ID> = QueryBuilderImpl<T, R, ID>(core.offset(offset))

    override fun scroll(size: Int): Window<R> = core.scroll(size)

    override fun scroll(scrollable: Scrollable<T>): Window<R> = core.scroll(scrollable)

    override fun windows(size: Int): Flow<Window<R>> = core.windows(size).consumeAsFlow()

    override fun windows(scrollable: Scrollable<T>): Flow<Window<R>> = core.windows(scrollable).consumeAsFlow()

    override fun getSubquery(): st.orm.core.template.TemplateString = (core as Subqueryable).subquery
}
