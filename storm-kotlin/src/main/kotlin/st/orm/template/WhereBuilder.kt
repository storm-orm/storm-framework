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
import st.orm.Navigable
import st.orm.Operator
import st.orm.Ref
import st.orm.template.TemplateString.Companion.raw

/**
 * A builder for constructing the WHERE clause of a query, providing type-safe predicate construction.
 *
 * The `WhereBuilder` is the receiver of the lambda passed to [QueryBuilder.whereBuilder] and offers methods for
 * matching by primary key, record, ref, metamodel path, or custom template string expressions. Each method returns
 * a [PredicateBuilder] that can be further composed using `and()` and `or()` combinators.
 *
 * The `where` methods are type-safe and restrict metamodel paths to the root table's entity graph. A join widens
 * the query root, and the scope's `and`/`or` combinators inherit it: a narrow scope combines predicates within
 * the root graph, a widened scope combines predicates across all entities in the query.
 *
 * ## Example
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .whereBuilder {
 *         (User_.email like "%@example.com") and (User_.address.city.name eq "Sunnyvale")
 *     }
 *     .resultList
 * ```
 *
 * @param T the type of the table being queried.
 * @param R the type of the result.
 * @param ID the type of the primary key.
 * @see QueryBuilder.whereBuilder
 * @see PredicateBuilder
 */
@SqlDsl
public interface WhereBuilder<T : Data, R, ID> : SubqueryTemplate {

    /**
     * A predicate that always evaluates to true.
     */
    public fun TRUE(): PredicateBuilder<T, R, ID> = where(raw("TRUE"))

    /**
     * A predicate that always evaluates to false.
     */
    public fun FALSE(): PredicateBuilder<T, R, ID> = where(raw("FALSE"))

    /**
     * Combines this predicate with another using an AND condition.
     *
     * The combinator inherits the query root of this `WhereBuilder`: a narrow scope combines predicates within
     * the root table's entity graph, and a join widens the root so predicates rooted at any entity in the query
     * combine with the same syntax.
     *
     * @param predicate the predicate to add.
     * @return the combined predicate builder.
     */
    public infix fun PredicateBuilder<out T, *, *>.and(predicate: PredicateBuilder<out T, *, *>): PredicateBuilder<T, R, ID>

    /**
     * Combines this predicate with another using an OR condition.
     *
     * The combinator inherits the query root of this `WhereBuilder`: a narrow scope combines predicates within
     * the root table's entity graph, and a join widens the root so predicates rooted at any entity in the query
     * combine with the same syntax.
     *
     * @param predicate the predicate to add.
     * @return the combined predicate builder.
     */
    public infix fun PredicateBuilder<out T, *, *>.or(predicate: PredicateBuilder<out T, *, *>): PredicateBuilder<T, R, ID>

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
    public fun exists(subquery: QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID>

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
    public fun exists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID> = exists(builder(this))

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
    public fun notExists(subquery: QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID>

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
    public fun notExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): PredicateBuilder<T, R, ID> = notExists(builder(this))

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table.
     *
     * @param id the id to match.
     * @return the predicate builder.
     */
    public fun whereId(id: ID): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public fun whereRef(ref: Ref<out T>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the predicate builder.
     */
    public fun where(record: T): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table.
     *
     * @param it the ids to match.
     * @return the predicate builder.
     * @since 1.2
     */
    public fun whereId(it: Iterable<ID>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public fun whereRef(it: Iterable<Ref<out T>>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the predicate builder.
     */
    public fun where(it: Iterable<T>): PredicateBuilder<T, R, ID>

    /**
     * Adds a condition to the WHERE clause that matches the specified record. The record can represent any of
     * the related tables in the table graph.
     *
     * @param path   the path to the object in the table graph.
     * @param record the records to match.
     * @return the predicate builder.
     */
    public fun <V : Data> where(path: Navigable<out T, V>, record: V): PredicateBuilder<T, R, ID> = where(path, Operator.EQUALS, record)

    /**
     * Adds a condition to the WHERE clause that matches the specified ref. The record can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param ref  the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public fun <V : Data> where(
        path: Navigable<out T, V>,
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
    public fun <V : Data> whereRef(
        path: Navigable<out T, V>,
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
    public fun <V : Data> where(
        path: Navigable<out T, V>,
        it: Iterable<V>,
    ): PredicateBuilder<T, R, ID> = where(path, Operator.IN, it)

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
    public fun <V> where(
        path: Navigable<out T, V>,
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
    public fun <V> where(
        path: Navigable<out T, V>,
        operator: Operator,
        vararg o: V,
    ): PredicateBuilder<T, R, ID> = where(path, operator, o.toList())

    /**
     * Appends a custom expression to the WHERE clause.
     *
     * @param builder the expression to add.
     * @return the predicate builder.
     */
    public fun where(builder: TemplateBuilder): PredicateBuilder<T, R, ID> = where(builder.build())

    /**
     * Appends a custom expression to the WHERE clause.
     *
     * @param template the expression to add.
     * @return the predicate builder.
     */
    public fun where(template: TemplateString): PredicateBuilder<T, R, ID>
}
