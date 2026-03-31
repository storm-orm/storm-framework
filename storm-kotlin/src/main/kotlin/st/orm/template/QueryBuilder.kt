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
package st.orm.template

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.stream.consumeAsFlow
import st.orm.*
import st.orm.Operator.*
import st.orm.core.template.impl.Elements.ObjectExpression
import st.orm.template.TemplateString.Companion.combine
import st.orm.template.TemplateString.Companion.raw
import st.orm.template.TemplateString.Companion.wrap
import st.orm.template.impl.create
import st.orm.template.impl.createRef
import java.util.function.Supplier
import java.util.stream.Stream
import kotlin.reflect.KClass

/**
 * A fluent builder for constructing type-safe SELECT and DELETE queries using the entity graph and metamodel.
 *
 * The `QueryBuilder` provides a composable, chainable API for building SQL queries without writing raw SQL.
 * It supports joins, WHERE clauses with type-safe metamodel paths, GROUP BY, HAVING, ORDER BY, LIMIT/OFFSET,
 * row locking (FOR SHARE/FOR UPDATE), and result retrieval as flows, lists, or single results.
 *
 * Instances are obtained from an [st.orm.repository.EntityRepository] or
 * [st.orm.repository.ProjectionRepository] via their `select()`, `selectCount()`, or
 * `delete()` methods, or from a [QueryTemplate] via `selectFrom()` and `deleteFrom()`.
 *
 * ## Example: Select with type-safe WHERE clause
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .where(User_.address.city.name, EQUALS, "Sunnyvale")
 *     .orderBy(User_.email)
 *     .limit(10)
 *     .getResultList()
 * ```
 *
 * ## Example: Delete with WHERE clause
 * ```kotlin
 * val deleted = userRepository
 *     .delete()
 *     .where(User_.email, IS_NULL)
 *     .executeUpdate()
 * ```
 *
 * ## Immutability
 * `QueryBuilder` is immutable: every builder method (such as `where()`, `orderBy()`,
 * `limit()`, etc.) returns a *new* instance with the modification applied, leaving the original
 * unchanged. If you call a builder method and ignore the return value, the change is silently lost.
 *
 * ```kotlin
 * // WRONG - the where clause is lost because the return value is discarded:
 * val builder = userRepository.select()
 * builder.where(User_.active, EQUALS, true)  // returns a new builder, but it's ignored
 * builder.resultList                          // executes without the WHERE clause
 *
 * // CORRECT - chain the calls or capture the returned builder:
 * val results = userRepository.select()
 *     .where(User_.active, EQUALS, true)
 *     .resultList
 * ```
 *
 * @param T the type of the table being queried.
 * @param R the type of the result.
 * @param ID the type of the primary key.
 * @see st.orm.repository.EntityRepository
 * @see st.orm.repository.ProjectionRepository
 * @see QueryTemplate
 */
interface QueryBuilder<T : Data, R, ID> {
    /**
     * Returns a typed query builder for the specified primary key type.
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param <X> the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.2
     */
    fun <X : Any> typed(pkType: KClass<X>): QueryBuilder<T, R, X>

    /**
     * Returns a query builder that allows UPDATE and DELETE queries without a WHERE clause.
     *
     * By default, Storm rejects UPDATE and DELETE queries that lack a WHERE clause, throwing a
     * [PersistenceException]. Call this method to disable that check when you intentionally want to affect all
     * rows in the table.
     *
     * @since 1.2
     */
    fun unsafe(): QueryBuilder<T, R, ID>

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    fun distinct(): QueryBuilder<T, R, ID>

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    fun crossJoin(relation: KClass<out Data>): QueryBuilder<T, R, ID>

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    fun innerJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID>

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    fun leftJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID>

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    fun rightJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID>

    /**
     * Adds a join of the specified type to the query.
     *
     * @param type the type of the join (e.g., INNER, LEFT, RIGHT).
     * @param relation the relation to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun join(
        type: JoinType,
        relation: KClass<out Data>,
        alias: String,
    ): TypedJoinBuilder<T, R, ID>

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    fun crossJoin(template: TemplateBuilder): QueryBuilder<T, R, ID> = crossJoin(template.build())

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    fun crossJoin(template: TemplateString): QueryBuilder<T, R, ID>

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun innerJoin(template: TemplateBuilder, alias: String): JoinBuilder<T, R, ID> = innerJoin(template.build(), alias)

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun innerJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID>

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun leftJoin(template: TemplateBuilder, alias: String): JoinBuilder<T, R, ID> = leftJoin(template.build(), alias)

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun leftJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID>

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun rightJoin(template: TemplateBuilder, alias: String): JoinBuilder<T, R, ID> = rightJoin(template.build(), alias)

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun rightJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID>

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun join(
        type: JoinType,
        template: TemplateBuilder,
        alias: String,
    ): JoinBuilder<T, R, ID> = join(type, template.build(), alias)

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun join(
        type: JoinType,
        template: TemplateString,
        alias: String,
    ): JoinBuilder<T, R, ID>

    /**
     * Adds a join of the specified type to the query using a subquery.
     *
     * @param type the join type.
     * @param subquery the subquery to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    fun join(
        type: JoinType,
        subquery: QueryBuilder<*, *, *>,
        alias: String,
    ): JoinBuilder<T, R, ID>

    /**
     * Adds a WHERE clause that matches the specified primary key of the table.
     *
     * @param id the id to match.
     * @return the query builder.
     */
    fun where(id: ID): QueryBuilder<T, R, ID> = whereBuilder { whereId(id) }

    /**
     * Adds a WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the query builder.
     * @since 1.3
     */
    fun where(ref: Ref<T>): QueryBuilder<T, R, ID> = whereBuilder { whereRef(ref) }

    /**
     * Adds a WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the query builder.
     */
    fun where(record: T): QueryBuilder<T, R, ID> = whereBuilder { where(record) }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table.
     *
     * @param it ids to match.
     * @return the query builder.
     * @since 1.2
     */
    fun whereId(it: Iterable<ID>): QueryBuilder<T, R, ID> = whereBuilder { whereId(it) }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it refs to match.
     * @return the query builder.
     * @since 1.3
     */
    fun whereRef(it: Iterable<Ref<T>>): QueryBuilder<T, R, ID> = whereBuilder { whereRef(it) }

    /**
     * Adds a WHERE clause that matches the specified record. The record can represent any of the related tables in the
     * table graph.
     *
     * @param path the path to the object in the table graph.
     * @param record the records to match.
     * @return the predicate builder.
     */
    fun <V : Data> where(path: Metamodel<T, V>, record: V): QueryBuilder<T, R, ID> = where(path, EQUALS, record)

    /**
     * Adds a WHERE clause that matches the specified ref. The ref can represent any of the related tables in the
     * table graph.
     *
     * @param path the path to the object in the table graph.
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun <V : Data> where(path: Metamodel<T, V>, ref: Ref<V>): QueryBuilder<T, R, ID> = whereBuilder { where(path, ref) }

    /**
     * Adds a WHERE clause that matches the specified records. The records can represent any of the related tables in
     * the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it the records to match.
     * @return the predicate builder.
     */
    fun <V : Data> where(path: Metamodel<T, V>, it: Iterable<V>): QueryBuilder<T, R, ID> = where(path, IN, it)

    /**
     * Adds a WHERE clause that matches the specified records. The records can represent any of the related tables in
     * the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it the records to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun <V : Data> whereRef(
        path: Metamodel<T, V>,
        it: Iterable<Ref<V>>,
    ): QueryBuilder<T, R, ID> = whereBuilder { whereRef(path, it) }

    /**
     * Adds a WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the query builder.
     */
    fun where(it: Iterable<T>): QueryBuilder<T, R, ID> = whereBuilder { where(it) }

    /**
     * Adds a WHERE clause that matches the specified objects at the specified path in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param it the objects to match, which can be primary keys, records representing the table, or fields in the table
     * graph.
     * @return the query builder.
     * @param <V> the type of the object that the metamodel represents.
     * @since 1.2
     */
    fun <V> where(
        path: Metamodel<T, V>,
        operator: Operator,
        it: Iterable<V>,
    ): QueryBuilder<T, R, ID> = whereBuilder { where(path, operator, it) }

    /**
     * Adds a WHERE clause that matches the specified objects at the specified path in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o the object(s) to match, which can be primary keys, records representing the table, or fields in the
     * table graph.
     * @return the query builder.
     * @param <V> the type of the object that the metamodel represents.
     * @since 1.2
     */
    fun <V> where(
        path: Metamodel<T, V>,
        operator: Operator,
        vararg o: V,
    ): QueryBuilder<T, R, ID> = whereBuilder { where(path, operator, *o) }

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param builder the expression.
     * @return the query builder.
     */
    fun where(builder: TemplateBuilder): QueryBuilder<T, R, ID> = where(builder.build())

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param template the expression.
     * @return the query builder.
     */
    fun where(template: TemplateString): QueryBuilder<T, R, ID> = whereBuilder { where(template) }

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    fun where(predicate: PredicateBuilder<T, *, *>): QueryBuilder<T, R, ID> = whereBuilder { predicate }

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    fun whereAny(predicate: PredicateBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereAnyBuilder { predicate }

    /**
     * Adds an `EXISTS` WHERE clause using the specified subquery.
     *
     *
     * This method appends an `EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the presence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the query builder.
     */
    fun whereExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { exists(subquery) }

    /**
     * Adds an `EXISTS` WHERE clause using the specified subquery.
     *
     * This method appends an `EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the presence of certain records in a related table or subquery.
     *
     * @param builder the subquery to check for existence.
     * @return the query builder.
     */
    fun whereExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { exists(builder(this)) }

    /**
     * Adds a `NOT EXISTS` WHERE clause using the specified subquery.
     *
     * This method appends an `NOT EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the absence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the query builder.
     */
    fun whereNotExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { notExists(subquery) }

    /**
     * Adds a `NOT EXISTS` WHERE clause using the specified subquery.
     *
     * This method appends an `NOT EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the absence of certain records in a related table or subquery.
     *
     * @param builder the subquery to check for existence.
     * @return the query builder.
     */
    fun whereNotExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { notExists(builder(this)) }

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    fun whereBuilder(predicate: WhereBuilder<T, R, ID>.() -> PredicateBuilder<T, *, *>): QueryBuilder<T, R, ID>

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    fun whereAnyBuilder(predicate: WhereBuilder<T, R, ID>.() -> PredicateBuilder<*, *, *>): QueryBuilder<T, R, ID>

    /**
     * Adds a GROUP BY clause to the query for field at the specified path in the table graph. The metamodel can refer
     * to manually added joins.
     *
     * @param path the path to group by.
     * @return the query builder.
     * @since 1.2
     */
    fun groupBy(vararg path: Metamodel<T, *>): QueryBuilder<T, R, ID> {
        // We can safely invoke groupByAny as the underlying logic is identical. The main purpose of having these
        // separate methods is to provide (more) type safety when using metamodels that are guaranteed to be present in
        // the table graph.
        return groupByAny(*path)
    }

    /**
     * Adds a GROUP BY clause to the query for field at the specified path in the table graph. The metamodel can refer
     * to manually added joins.
     *
     * @param path the path to group by.
     * @return the query builder.
     * @since 1.2
     */
    fun groupByAny(vararg path: Metamodel<*, *>): QueryBuilder<T, R, ID> {
        if (path.isEmpty()) {
            throw PersistenceException("At least one path must be provided for GROUP BY clause.")
        }
        val templates = buildList {
            path.forEachIndexed { index, metamodel ->
                add(TemplateString.wrap(metamodel))
                // only add a comma between elements, not after the last one.
                if (index < path.lastIndex) {
                    add(raw(", "))
                }
            }
        }
        return groupBy(combine(*templates.toTypedArray()))
    }

    /**
     * Adds a GROUP BY clause to the query using a string template.
     *
     * @param builder the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    fun groupBy(builder: TemplateBuilder): QueryBuilder<T, R, ID> = groupBy(builder.build())

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    fun groupBy(template: TemplateString): QueryBuilder<T, R, ID>

    /**
     * Adds a HAVING clause to the query using the specified expression.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o the object(s) to match, which can be primary keys, records representing the table, or fields in the
     * table graph.
     * @return the query builder.
     * @since 1.2
     */
    fun <V> having(
        path: Metamodel<T, V>,
        operator: Operator,
        vararg o: V,
    ): QueryBuilder<T, R, ID> = havingAny(path, operator, *o)

    /**
     * Adds a HAVING clause to the query using the specified expression. The metamodel can refer to manually added joins.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o the object(s) to match, which can be primary keys, records representing the table, or fields in the
     * table graph or manually added joins.
     * @return the query builder.
     * @since 1.2
     */
    fun <V> havingAny(
        path: Metamodel<*, V>,
        operator: Operator,
        vararg o: V,
    ): QueryBuilder<T, R, ID> = having(wrap(ObjectExpression(path, operator, o)))

    /**
     * Adds a HAVING clause to the query using the specified expression.
     *
     * @param builder the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    fun having(builder: TemplateBuilder): QueryBuilder<T, R, ID> = having(builder.build())

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    fun having(template: TemplateString): QueryBuilder<T, R, ID>

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    fun orderBy(vararg path: Metamodel<T, *>): QueryBuilder<T, R, ID> {
        // We can safely invoke orderByAny as the underlying logic is identical. The main purpose of having these
        // separate methods is to provide (more) type safety when using metamodels that are guaranteed to be present in
        // the table graph.
        return orderByAny(*path)
    }

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph. The results are
     * sorted in descending order.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    fun orderByDescending(path: Metamodel<T, *>): QueryBuilder<T, R, ID> = orderBy(combine(wrap(path), raw(" DESC")))

    /**
     * Adds an ORDER BY clause to the query for the fields at the specified paths in the table graph. The results
     * are sorted in descending order for each column.
     *
     * @param path the paths to order by.
     * @return the query builder.
     * @since 1.9
     */
    fun orderByDescending(vararg path: Metamodel<T, *>): QueryBuilder<T, R, ID> = orderByDescendingAny(*path)

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph or manually added
     * joins. The results are sorted in descending order.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.9
     */
    fun orderByDescendingAny(path: Metamodel<*, *>): QueryBuilder<T, R, ID> = orderBy(combine(wrap(path), raw(" DESC")))

    /**
     * Adds an ORDER BY clause to the query for the fields at the specified paths in the table graph or manually
     * added joins. The results are sorted in descending order for each column.
     *
     * @param path the paths to order by.
     * @return the query builder.
     * @since 1.9
     */
    fun orderByDescendingAny(vararg path: Metamodel<*, *>): QueryBuilder<T, R, ID> {
        if (path.isEmpty()) {
            throw PersistenceException("At least one path must be provided for ORDER BY clause.")
        }
        val templates = buildList {
            path.forEachIndexed { index, metamodel ->
                add(wrap(metamodel))
                add(raw(" DESC"))
                if (index < path.size - 1) {
                    add(raw(", "))
                }
            }
        }
        return orderBy(combine(*templates.toTypedArray()))
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. The results are sorted in descending order.
     * Multiple calls to this method append additional columns to the ORDER BY clause.
     *
     * @param builder the template to order by.
     * @return the query builder.
     * @since 1.9
     */
    fun orderByDescending(builder: TemplateBuilder): QueryBuilder<T, R, ID> = orderByDescending(builder.build())

    /**
     * Adds an ORDER BY clause to the query using a string template. The results are sorted in descending order.
     * Multiple calls to this method append additional columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.9
     */
    fun orderByDescending(template: TemplateString): QueryBuilder<T, R, ID> = orderBy(combine(template, raw(" DESC")))

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph or manually added
     * joins.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    fun orderByAny(vararg path: Metamodel<*, *>): QueryBuilder<T, R, ID> {
        if (path.isEmpty()) {
            throw PersistenceException("At least one path must be provided for ORDER BY clause.")
        }
        val templates = buildList {
            path.forEachIndexed { index, metamodel ->
                add(wrap(metamodel))
                // only add a comma between elements, not after the last one.
                if (index < path.lastIndex) {
                    add(raw(", "))
                }
            }
        }
        return orderBy(combine(*templates.toTypedArray()))
    }

    /**
     * Adds an ORDER BY clause to the query using a string template.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    fun orderBy(template: TemplateBuilder): QueryBuilder<T, R, ID> = orderBy(template.build())

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    fun orderBy(template: TemplateString): QueryBuilder<T, R, ID>

    /**
     * Returns `true` if any ORDER BY columns have been added to this query builder.
     *
     * @return `true` if ORDER BY columns are present, `false` otherwise.
     * @since 1.9
     */
    fun hasOrderBy(): Boolean

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    fun limit(limit: Int): QueryBuilder<T, R, ID>

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    fun offset(offset: Int): QueryBuilder<T, R, ID>

    /**
     * Append the query with a string template.
     *
     * @param builder the string template to append.
     * @return the query builder.
     */
    fun append(builder: TemplateBuilder): QueryBuilder<T, R, ID> = append(builder.build())

    /**
     * Append the query with a string template.
     *
     * @param template the string template to append.
     * @return the query builder.
     */
    fun append(template: TemplateString): QueryBuilder<T, R, ID>

    //
    // Locking.
    //

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    fun forShare(): QueryBuilder<T, R, ID>

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    fun forUpdate(): QueryBuilder<T, R, ID>

    /**
     * Locks the selected rows using a custom lock mode.
     *
     * **Note:** This method results in non-portable code, as the lock mode is specific to the
     * underlying database.
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     * @since 1.2
     */
    fun forLock(builder: TemplateBuilder): QueryBuilder<T, R, ID> = forLock(builder.build())

    /**
     * Locks the selected rows using a custom lock mode.
     *
     * **Note:** This method results in non-portable code, as the lock mode is specific to the
     * underlying database.
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     * @since 1.2
     */
    fun forLock(template: TemplateString): QueryBuilder<T, R, ID>

    //
    // Finalization.
    //

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    fun build(): Query

    /**
     * Prepares the query for execution.
     *
     * Unlike regular queries, which are constructed lazily, prepared queries are constructed eagerly.
     * Prepared queries allow the use of bind variables and enable reading generated keys after row insertion.
     *
     * **Note:** The prepared query must be closed after usage to prevent resource leaks. As the
     * prepared query is `AutoCloseable`, it is recommended to use it within a `try-with-resources` block.
     *
     * @return the prepared query.
     * @throws PersistenceException if the query preparation fails.
     */
    fun prepare(): PreparedQuery = build().prepare()

    /**
     * Executes the query and returns a [Page] of results using offset-based pagination.
     *
     * This method executes two queries: one to count the total number of matching results (without offset or
     * limit), and one to fetch the content for the requested page. The caller is responsible for adding ORDER BY
     * clauses to ensure deterministic ordering across pages.
     *
     * Page numbers are zero-based: pass `0` for the first page.
     *
     * @param pageNumber the zero-based page index (must not be negative).
     * @param pageSize the maximum number of results per page (must be positive).
     * @return a page containing the results and pagination metadata.
     * @throws IllegalArgumentException if [pageNumber] is negative or [pageSize] is not positive.
     * @since 1.10
     */
    fun page(pageNumber: Int, pageSize: Int): Page<R> = page(Pageable.of(pageNumber, pageSize))

    /**
     * Executes the query and returns a [Page] of results using offset-based pagination.
     *
     * This method executes two queries: one to count the total number of matching results (without offset or
     * limit), and one to fetch the content for the requested page. Sort orders can be specified either through the
     * pageable or through explicit `orderBy` calls on the query builder, but not both. If both are present,
     * a [PersistenceException] is thrown.
     *
     * Use [Pageable.ofSize] for the first page, then navigate with
     * [Page.nextPageable] or [Page.previousPageable].
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @throws PersistenceException if the pageable has sort orders and the query builder has explicit orderBy calls.
     * @since 1.10
     */
    fun page(pageable: Pageable): Page<R> = page(pageable, resultCount)

    /**
     * Executes the query and returns a [Page] of results using offset-based pagination with a pre-computed
     * total count.
     *
     * This method applies the sort orders from the pageable, then fetches the content for the requested page
     * using the provided total count instead of executing a separate count query. This is useful when the total
     * count is already known (for example, cached from a previous request or obtained from an external source),
     * avoiding a redundant `COUNT` query.
     *
     * Sort orders can be specified either through the pageable or through explicit `orderBy` calls on the query
     * builder, but not both. If both are present, a [PersistenceException] is thrown.
     *
     * @param pageable the pagination request specifying page number and page size.
     * @param totalCount the pre-computed total number of matching results.
     * @return a page containing the results and pagination metadata.
     * @throws PersistenceException if the pageable has sort orders and the query builder has explicit orderBy calls.
     * @since 1.10
     */
    fun page(pageable: Pageable, totalCount: Long): Page<R> {
        // Forbid combining explicit orderBy with Pageable sort orders for consistency with scroll, which also
        // manages ORDER BY internally and forbids explicit orderBy calls.
        if (hasOrderBy() && pageable.orders().isNotEmpty()) {
            throw PersistenceException("page with Pageable sort orders cannot be combined with explicit orderBy calls.")
        }
        var sorted: QueryBuilder<T, R, ID> = this
        for (order in pageable.orders()) {
            sorted = if (order.descending()) {
                sorted.orderByDescendingAny(order.field())
            } else {
                sorted.orderByAny(order.field())
            }
        }
        val content = sorted.offset(pageable.offset().toInt()).limit(pageable.pageSize()).resultList
        return Page(content, totalCount, pageable)
    }

    /**
     * Executes the query and returns a [Window] of results.
     *
     * This method fetches `size + 1` rows to determine whether more results are available, then returns at
     * most `size` results along with a `hasNext` flag. The caller is responsible for managing any WHERE
     * and ORDER BY clauses externally.
     *
     * @param size the maximum number of results to include in the window (must be positive).
     * @return a window containing the results and a flag indicating whether more results exist.
     * @throws IllegalArgumentException if [size] is not positive.
     * @since 1.11
     */
    fun scroll(size: Int): Window<R>

    /**
     * Executes a scroll request from a [Scrollable] token, typically obtained from
     * [Window.next] or [Window.previous].
     *
     * @param scrollable the scroll request containing cursor state, key, sort, size, and direction.
     * @return a window containing the results for the requested scroll position.
     * @since 1.11
     */
    fun scroll(scrollable: Scrollable<T>): Window<R>

    //
    // Execution methods.
    //

    /**
     * Executes the query and returns a stream of results.
     *
     * The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.
     *
     * **Note:** Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is `AutoCloseable`, it is recommended to use it
     * within a `try-with-resources` block.
     *
     * @return a stream of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     */
    val resultStream: Stream<R>

    /**
     * Executes the query and returns a flow of results.
     *
     * @since 1.5
     */
    val resultFlow: Flow<R>
        get() = resultStream.consumeAsFlow()

    val resultCount: Long
        /**
         * Returns the number of results of this query.
         *
         * @return the total number of results of this query as a long value.
         * @throws PersistenceException if the query operation fails due to underlying database issues, such as
         * connectivity.
         */
        get() {
            resultStream.use { stream ->
                return stream.count()
            }
        }

    val resultList: List<R>
        /**
         * Executes the query and returns a list of results.
         *
         * @return the list of results.
         * @throws PersistenceException if the query fails.
         */
        get() {
            resultStream.use { stream ->
                return stream.toList()
            }
        }

    val singleResult: R
        /**
         * Executes the query and returns a single result.
         *
         * @return the single result.
         * @throws NoResultException if there is no result.
         * @throws NonUniqueResultException if more than one result.
         * @throws PersistenceException if the query fails.
         */
        get() {
            resultStream.use { stream ->
                return stream
                    .reduce { _, _ ->
                        throw NonUniqueResultException("Expected single result, but found more than one.")
                    }
                    .orElseThrow(Supplier { NoResultException("Expected single result, but found none.") })
            }
        }

    val optionalResult: R?
        /**
         * Executes the query and returns an optional result.
         *
         * @return the optional result.
         * @throws NonUniqueResultException if more than one result.
         * @throws PersistenceException if the query fails.
         */
        get() {
            resultStream.use { stream ->
                return stream.reduce { _, _ ->
                    throw NonUniqueResultException("Expected single result, but found more than one.")
                }
                    .orElse(null)
            }
        }

    /**
     * Execute a DELETE statement.
     *
     * @return the number of rows impacted as result of the statement.
     * @throws PersistenceException if the statement fails.
     */
    fun executeUpdate(): Int = build().executeUpdate()
}

// Kotlin specific DSL

/**
 * Infix function to create a predicate to check if a field is in a list of values.
 */
infix fun <T : Data, V> Metamodel<T, V>.inList(value: Iterable<V>): PredicateBuilder<T, T, *> = create(this, IN, value)

/**
 * Infix function to create a predicate to check if a field is in a list of references.
 */
infix fun <T : Data, V : Data> Metamodel<T, V>.inRefs(value: Iterable<Ref<V>>): PredicateBuilder<T, T, *> = createRef(this, IN, value)

/**
 * Infix function to create a predicate to check if a field is not in a list of values.
 */
infix fun <T : Data, V> Metamodel<T, V>.notInList(value: Iterable<V>): PredicateBuilder<T, T, *> = create(this, NOT_IN, value)

/**
 * Infix function to create a predicate to check if a field is not in a list of references.
 */
infix fun <T : Data, V : Data> Metamodel<T, V>.notInRefs(value: Iterable<Ref<V>>): PredicateBuilder<T, T, *> = createRef(this, NOT_IN, value)

/**
 * Infix functions to create a predicate to check if a field is equal to a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.eq(value: V): PredicateBuilder<T, T, *> = create(this, EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is equal to a reference.
 */
infix fun <T : Data, V : Data> Metamodel<T, V>.eq(value: Ref<V>): PredicateBuilder<T, T, *> = createRef(this, EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is not equal to a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.neq(value: V): PredicateBuilder<T, T, *> = create(this, NOT_EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is not equal to a reference.
 */
infix fun <T : Data, V : Data> Metamodel<T, V>.neq(value: Ref<V>): PredicateBuilder<T, T, *> = createRef(this, NOT_EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is like a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.like(value: V): PredicateBuilder<T, T, *> = create(this, LIKE, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is not like a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.notLike(value: V): PredicateBuilder<T, T, *> = create(this, NOT_LIKE, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is greater than a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.greater(value: V): PredicateBuilder<T, T, *> = create(this, GREATER_THAN, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is less than a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.less(value: V): PredicateBuilder<T, T, *> = create(this, LESS_THAN, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is greater than or equal to a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.greaterEq(value: V): PredicateBuilder<T, T, *> = create(this, GREATER_THAN_OR_EQUAL, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is less than or equal to a value.
 */
infix fun <T : Data, V> Metamodel<T, V>.lessEq(value: V): PredicateBuilder<T, T, *> = create(this, LESS_THAN_OR_EQUAL, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is between two values.
 */
fun <T : Data, V> Metamodel<T, V>.between(left: V, right: V): PredicateBuilder<T, T, *> = create(this, BETWEEN, listOf(left, right))

/**
 * Infix functions to create a predicate to check if a field is true.
 */
fun <T : Data, V> Metamodel<T, V>.isTrue(): PredicateBuilder<T, T, *> = create(this, IS_TRUE, emptyList())

/**
 * Infix functions to create a predicate to check if a field is false.
 */
fun <T : Data, V> Metamodel<T, V>.isFalse(): PredicateBuilder<T, T, *> = create(this, IS_FALSE, emptyList())

/**
 * Infix functions to create a predicate to check if a field is null.
 */
fun <T : Data, V> Metamodel<T, V>.isNull(): PredicateBuilder<T, T, *> = create(this, IS_NULL, emptyList())

/**
 * Infix functions to create a predicate to check if a field is not null.
 */
fun <T : Data, V> Metamodel<T, V>.isNotNull(): PredicateBuilder<T, T, *> = create(this, IS_NOT_NULL, emptyList())

// Block-based query DSL

/**
 * A mutable scope for constructing queries using [QueryBuilder] methods in a block-based style, similar to
 * [buildList] or [buildMap].
 *
 * Each call inside the block (such as [where], [orderBy], [limit]) updates the internal builder state.
 *
 * ```kotlin
 * userRepository.select {
 *     where(User_.active eq true)
 *     orderBy(User_.name)
 * }.resultList
 * ```
 *
 * @param T the entity type being queried.
 * @param R the result type of the query.
 * @param ID the primary key type.
 */
@SqlDsl
class SqlScope<T : Data, R, ID : Any> @PublishedApi internal constructor(
    @PublishedApi internal var builder: QueryBuilder<T, R, ID>,
) {
    /** Adds a WHERE clause using a predicate built with metamodel infix operators (e.g., `User_.name eq "Alice"`). */
    fun where(predicate: PredicateBuilder<T, *, *>) {
        builder = builder.where(predicate)
    }

    /** Adds a WHERE clause matching a metamodel path to value(s) using an [Operator]. */
    fun <V> where(path: Metamodel<T, V>, operator: Operator, vararg value: V) {
        builder = builder.where(path, operator, *value)
    }

    /** Adds a WHERE clause matching a metamodel path to a data record. */
    fun <V : Data> where(path: Metamodel<T, V>, record: V) {
        builder = builder.where(path, record)
    }

    /** Adds a WHERE clause matching a metamodel path to a [Ref]. */
    fun <V : Data> where(path: Metamodel<T, V>, ref: Ref<V>) {
        builder = builder.where(path, ref)
    }

    /** Adds a WHERE clause matching a primary key. */
    fun where(id: ID) {
        builder = builder.where(id)
    }

    /** Adds a WHERE clause matching a [Ref]. */
    fun where(ref: Ref<T>) {
        builder = builder.where(ref)
    }

    /** Adds a WHERE clause matching a record. */
    fun where(record: T) {
        builder = builder.where(record)
    }

    /** Adds a WHERE clause using a SQL template expression (e.g., `where { "${t(User_.score)} > ${t(100)}" }`). */
    fun where(template: TemplateBuilder) {
        builder = builder.where(template)
    }

    /** Adds a WHERE clause for any entity type (e.g., a joined entity). */
    fun whereAny(predicate: PredicateBuilder<*, *, *>) {
        builder = builder.whereAny(predicate)
    }

    /** Adds a WHERE clause using a [WhereBuilder] for compound predicates with `and`/`or`. */
    fun whereBuilder(predicate: WhereBuilder<T, R, ID>.() -> PredicateBuilder<T, *, *>) {
        builder = builder.whereBuilder(predicate)
    }

    /** Adds a WHERE EXISTS clause with the given subquery. */
    fun whereExists(subquery: QueryBuilder<*, *, *>) {
        builder = builder.whereExists(subquery)
    }

    /** Adds a WHERE NOT EXISTS clause with the given subquery. */
    fun whereNotExists(subquery: QueryBuilder<*, *, *>) {
        builder = builder.whereNotExists(subquery)
    }

    /** Adds an INNER JOIN with automatic ON resolution between [relation] and [on]. */
    fun innerJoin(relation: KClass<out Data>, on: KClass<out Data>) {
        builder = builder.innerJoin(relation).on(on)
    }

    /** Adds a LEFT JOIN with automatic ON resolution between [relation] and [on]. */
    fun leftJoin(relation: KClass<out Data>, on: KClass<out Data>) {
        builder = builder.leftJoin(relation).on(on)
    }

    /** Adds a RIGHT JOIN with automatic ON resolution between [relation] and [on]. */
    fun rightJoin(relation: KClass<out Data>, on: KClass<out Data>) {
        builder = builder.rightJoin(relation).on(on)
    }

    /** Adds a CROSS JOIN for [relation]. */
    fun crossJoin(relation: KClass<out Data>) {
        builder = builder.crossJoin(relation)
    }

    /** Adds a GROUP BY clause for the specified metamodel path(s). */
    fun groupBy(vararg path: Metamodel<T, *>) {
        builder = builder.groupBy(*path)
    }

    /** Adds a GROUP BY clause using a SQL template expression. */
    fun groupBy(template: TemplateBuilder) {
        builder = builder.groupBy(template)
    }

    /** Adds a HAVING clause matching a metamodel path to value(s) using an [Operator]. */
    fun <V> having(path: Metamodel<T, V>, operator: Operator, vararg value: V) {
        builder = builder.having(path, operator, *value)
    }

    /** Adds a HAVING clause using a SQL template expression (e.g., `having { "COUNT(*) > ${t(5)}" }`). */
    fun having(template: TemplateBuilder) {
        builder = builder.having(template)
    }

    /** Adds an ORDER BY clause (ascending) for the specified metamodel path(s). */
    fun orderBy(vararg path: Metamodel<T, *>) {
        builder = builder.orderBy(*path)
    }

    /** Adds an ORDER BY clause (descending) for the specified metamodel path(s). */
    fun orderByDescending(vararg path: Metamodel<T, *>) {
        builder = builder.orderByDescending(*path)
    }

    /** Adds an ORDER BY clause (ascending) for any entity type (e.g., a joined entity). */
    fun orderByAny(vararg path: Metamodel<*, *>) {
        builder = builder.orderByAny(*path)
    }

    /** Adds an ORDER BY clause (descending) for any entity type (e.g., a joined entity). */
    fun orderByDescendingAny(vararg path: Metamodel<*, *>) {
        builder = builder.orderByDescendingAny(*path)
    }

    /** Adds an ORDER BY clause using a SQL template expression. */
    fun orderBy(template: TemplateBuilder) {
        builder = builder.orderBy(template)
    }

    /** Adds a LIMIT clause restricting the maximum number of results. */
    fun limit(limit: Int) {
        builder = builder.limit(limit)
    }

    /** Adds an OFFSET clause skipping the first [offset] results. */
    fun offset(offset: Int) {
        builder = builder.offset(offset)
    }

    /** Marks the query as SELECT DISTINCT. */
    fun distinct() {
        builder = builder.distinct()
    }

    /** Allows DELETE/UPDATE without a WHERE clause (Storm rejects this by default). */
    fun unsafe() {
        builder = builder.unsafe()
    }

    /** Locks the selected rows for reading (SELECT ... FOR SHARE). */
    fun forShare() {
        builder = builder.forShare()
    }

    /** Locks the selected rows for writing (SELECT ... FOR UPDATE). */
    fun forUpdate() {
        builder = builder.forUpdate()
    }

    /** Appends raw SQL to the query using a template expression. */
    fun append(template: TemplateBuilder) {
        builder = builder.append(template)
    }

    /**
     * Validates the block's return value: a non-null, non-[Unit] value is unexpected and indicates
     * a programming error where an expression result was not consumed by a scope method.
     */
    @PublishedApi
    internal fun validateResult(result: Any?) {
        if (result != null && result !is Unit) {
            throw IllegalStateException(
                "Unexpected ${result::class.simpleName} return value in query block. " +
                    "All expressions must be consumed by scope methods such as where(), orderBy(), or limit().",
            )
        }
    }
}
