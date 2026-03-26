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

import st.orm.Data
import st.orm.Metamodel
import st.orm.Operator
import st.orm.Ref
import st.orm.template.TemplateString.Companion.raw

/**
 * A builder for constructing the WHERE clause of a query, providing type-safe predicate construction.
 *
 * The `WhereBuilder` is passed to the lambda argument of [QueryBuilder.where] and offers methods for matching by
 * primary key, record, ref, metamodel path, or custom template string expressions. Each method returns a
 * [PredicateBuilder] that can be further composed using `and()` and `or()` combinators.
 *
 * Methods named `where` are type-safe and restrict metamodel paths to the root table's entity graph.
 * Methods named `whereAny` accept metamodel paths from any table, including manually added joins.
 *
 * ## Example
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .where { predicate ->
 *         predicate
 *             .where(User_.active, EQUALS, true)
 *             .and(predicate.where(User_.address.city.name, EQUALS, "Sunnyvale"))
 *     }
 *     .getResultList()
 * ```
 *
 * @param T the type of the table being queried.
 * @param R the type of the result.
 * @param ID the type of the primary key.
 * @see QueryBuilder.where
 * @see PredicateBuilder
 */
@SqlDsl
interface WhereBuilder<T : Data, R, ID> : SubqueryTemplate {

    /**
     * A predicate that always evaluates to true.
     */
    fun TRUE(): PredicateBuilder<T, R, ID> = where(raw("TRUE"))

    /**
     * A predicate that always evaluates to false.
     */
    fun FALSE(): PredicateBuilder<T, R, ID> = where(raw("FALSE"))

    /**
     * Adds an `EXISTS` condition to the WHERE clause using the specified subquery.
     *
     *
     * This method appends an `EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the presence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the updated [PredicateBuilder] with the EXISTS condition applied.
     */
    fun exists(subquery: QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID>

    /**
     * Adds an `EXISTS` condition to the WHERE clause using the specified subquery.
     *
     *
     * This method appends an `EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the presence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the updated [PredicateBuilder] with the EXISTS condition applied.
     */
    fun exists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID> = exists(builder(this))

    /**
     * Adds an `NOT EXISTS` condition to the WHERE clause using the specified subquery.
     *
     *
     * This method appends an `NOT EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the absence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the updated [PredicateBuilder] with the NOT EXISTS condition applied.
     */
    fun notExists(subquery: QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID>

    /**
     * Adds an `NOT EXISTS` condition to the WHERE clause using the specified subquery.
     *
     *
     * This method appends an `NOT EXISTS` clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the absence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the updated [PredicateBuilder] with the NOT EXISTS condition applied.
     */
    fun notExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID> = notExists(builder(this))

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table.
     *
     * @param id the id to match.
     * @return the predicate builder.
     */
    fun whereId(id: ID): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun whereRef(ref: Ref<T>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table, expressed by a ref.
     * The ref can represent any of the related tables in the table graph or manually added joins.
     *
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun whereAnyRef(ref: Ref<out Data>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the predicate builder.
     */
    fun where(record: T): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified record. The record can represent any of the
     * related tables in the table graph or manually added joins.
     *
     * @param record the record to match.
     * @return the predicate builder.
     * @since 1.2
     */
    fun whereAny(record: Data): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table.
     *
     * @param it the ids to match.
     * @return the predicate builder.
     * @since 1.2
     */
    fun whereId(it: Iterable<ID>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun whereRef(it: Iterable<Ref<T>>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     * The ref can represent any of the related tables in the table graph or manually added joins.
     *
     * @param it the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun whereAnyRef(it: Iterable<Ref<out Data>>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the predicate builder.
     */
    fun where(it: Iterable<T>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified records. The record can represent any of the
     * related tables in the table graph or manually added joins.
     *
     * @param it the records to match.
     * @return the query builder.
     */
    fun whereAny(it: Iterable<Data>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified record. The record can represent any of
     * the related tables in the table graph.
     *
     * @param path   the path to the object in the table graph.
     * @param record the records to match.
     * @return the predicate builder.
     */
    fun <V : Data> where(path: Metamodel<T, V>, record: V): PredicateBuilder<T, R, ID> = where(path, Operator.EQUALS, record)

    /**
     * Adds a condition to the WHERE clause that matches the specified record. The record can represent any of
     * the related tables in the table graph or manually added joins.
     *
     * @param record the records to match.
     * @return the predicate builder.
     */
    fun <V : Data> whereAny(path: Metamodel<*, V>, record: V): PredicateBuilder<T, R, ID> = whereAny(path, Operator.EQUALS, record)

    /**
     * Adds a condition to the WHERE clause that matches the specified ref. The record can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param ref  the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun <V : Data> where(
        path: Metamodel<T, V>,
        ref: Ref<V>,
    ): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified ref. The record can represent any of
     * the related tables in the table graph or manually added joins.
     *
     * @param path the path to the object in the table graph.
     * @param ref  the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun <V : Data> whereAny(
        path: Metamodel<*, V>,
        ref: Ref<V>,
    ): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified refs. The refs can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the ref in the table graph.
     * @param it   the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun <V : Data> whereRef(
        path: Metamodel<T, V>,
        it: Iterable<Ref<V>>,
    ): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified refs. The refs can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the ref in the table graph.
     * @param it   the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    fun <V : Data> whereAnyRef(
        path: Metamodel<*, V>,
        it: Iterable<Ref<V>>,
    ): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified records. The records can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it   the records to match.
     * @return the predicate builder.
     */
    fun <V : Data> where(
        path: Metamodel<T, V>,
        it: Iterable<V>,
    ): PredicateBuilder<T, R, ID> = where(path, Operator.IN, it)

    /**
     * Adds a condition to the WHERE clause that matches the specified records. The records can represent any of
     * the related tables in the table graph or manually added joins.
     *
     * @param path the path to the object in the table graph.
     * @param it   the records to match.
     * @return the predicate builder.
     */
    fun <V : Data> whereAny(
        path: Metamodel<*, V>,
        it: Iterable<V>,
    ): PredicateBuilder<T, R, ID> = whereAny(path, Operator.IN, it)

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param it       the objects to match, which can be primary keys, records representing the table, or fields in the
     * table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    fun <V> where(
        path: Metamodel<T, V>,
        operator: Operator,
        it: Iterable<V>,
    ): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph or manually added joins.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param it       the objects to match, which can be primary keys, records representing the table, or fields in the
     * table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    fun <V> whereAny(
        path: Metamodel<*, V>,
        operator: Operator,
        it: Iterable<V>,
    ): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o        the object(s) to match, which can be primary keys, records representing the table, or fields in the
     * table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    fun <V> where(
        path: Metamodel<T, V>,
        operator: Operator,
        vararg o: V,
    ): PredicateBuilder<T, R, ID> = whereAny(path, operator, *o)

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph or manually added joins.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o        the object(s) to match, which can be primary keys, records representing the table, or fields in the
     * table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    fun <V> whereAny(
        path: Metamodel<*, V>,
        operator: Operator,
        vararg o: V,
    ): PredicateBuilder<T, R, ID>

    /**
     * Appends a custom expression to the WHERE clause.
     *
     * @param builder the expression to add.
     * @return the predicate builder.
     */
    fun where(builder: TemplateBuilder): PredicateBuilder<T, R, ID> = where(builder.build())

    /**
     * Appends a custom expression to the WHERE clause.
     *
     * @param template the expression to add.
     * @return the predicate builder.
     */
    fun where(template: TemplateString): PredicateBuilder<T, R, ID>
}
