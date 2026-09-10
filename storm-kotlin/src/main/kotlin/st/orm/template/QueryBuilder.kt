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
import st.orm.ResolveScope.CASCADE
import st.orm.core.template.impl.Elements
import st.orm.core.template.impl.Elements.Clause.GROUP_BY
import st.orm.core.template.impl.Elements.Clause.ORDER_BY_ASCENDING
import st.orm.core.template.impl.Elements.Clause.ORDER_BY_DESCENDING
import st.orm.core.template.impl.Elements.Columns
import st.orm.core.template.impl.Elements.ObjectExpression
import st.orm.template.TemplateString.Companion.combine
import st.orm.template.TemplateString.Companion.raw
import st.orm.template.TemplateString.Companion.wrap
import st.orm.template.impl.combineAnd
import st.orm.template.impl.combineOr
import st.orm.template.impl.create
import st.orm.template.impl.createRef
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
 *     .where(User_.address.city.name eq "Sunnyvale")
 *     .orderBy(User_.email)
 *     .limit(10)
 *     .resultList
 * ```
 *
 * ## Example: Join with reified type arguments
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .innerJoin<Order>().on<User>()
 *     .resultList
 * ```
 *
 * ## Example: Delete with WHERE clause
 * ```kotlin
 * val deleted = userRepository
 *     .delete()
 *     .where(User_.email.isNull())
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
 * builder.where(User_.email like "%@example.com")  // returns a new builder, but it's ignored
 * builder.resultList                   // executes without the WHERE clause
 *
 * // CORRECT - chain the calls or capture the returned builder:
 * val results = userRepository.select()
 *     .where(User_.email like "%@example.com")
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
public abstract class QueryBuilder<T : Data, R, ID> {
    /**
     * Returns a query builder whose primary key type is [pkType], so the operations that take an id can be used.
     *
     * A builder that did not come from a typed entity lookup carries no primary key type. `selectFrom(...)` names the
     * table but not its key, so the id is a star projection and [WhereBuilder.whereId] has nothing to match against.
     * Stating the key type resolves it:
     *
     * ```kotlin
     * val cities = orm.selectFrom<City>()
     *     .typedId<Int>()
     *     .whereBuilder { whereId(listOf(1, 3, 5)) }
     *     .resultList
     * ```
     *
     * The type is checked against the model, so a type that is not the table's key fails here rather than when the
     * query runs. This types the key, while [narrow] types the root; the two are independent, and neither is undone
     * by a join.
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param X the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.14
     */
    public abstract fun <X : Any> typedId(pkType: KClass<X>): QueryBuilder<T, R, X>

    /**
     * Returns a query builder whose primary key type is [X], so the operations that take an id can be used.
     *
     * See [typedId] for when a builder has no primary key type and why stating it is checked.
     *
     * @param X the type of the primary key.
     * @return the typed query builder.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.14
     */
    public inline fun <reified X : Any> typedId(): QueryBuilder<T, R, X> = typedId(X::class)

    /**
     * Returns a query builder rooted at the specified type, narrowing a builder whose root was relaxed by a join.
     *
     * A join relaxes the root so that clauses may name any entity in the query. This narrows it again, which
     * re-enables the operations defined relative to the root, such as [fetch] and [resultGroupedBy].
     *
     * @param rootType the type this query is rooted at.
     * @return the query builder, rooted at [rootType].
     * @throws PersistenceException if [rootType] is not the type this query selects from.
     * @since 1.14
     */
    public abstract fun <X : Data> narrow(rootType: KClass<X>): QueryBuilder<X, R, ID>

    /**
     * Returns a query builder rooted at [X], narrowing a builder whose root was relaxed by a join.
     *
     * @throws PersistenceException if [X] is not the type this query selects from.
     * @since 1.14
     */
    public inline fun <reified X : Data> narrow(): QueryBuilder<X, R, ID> = narrow(X::class)

    /**
     * Widens the query as a join does, without joining: from here on, every clause accepts paths from any entity in
     * the query. Use it to reference an entity of the query's graph in short form on a query that joins nothing;
     * resolution happens when the query is built, and a table the query does not contain, or contains more than once,
     * fails with an error naming the candidates.
     *
     * Widening is always safe, so unlike [narrow] there is nothing to verify.
     *
     * @return the query builder, accepting paths from any entity in the query.
     * @since 1.14
     */
    public abstract fun widen(): QueryBuilder<Data, R, ID>

    /**
     * Returns a query builder that allows UPDATE and DELETE queries without a WHERE clause.
     *
     * By default, Storm rejects UPDATE and DELETE queries that lack a WHERE clause, throwing a
     * [PersistenceException]. Call this method to disable that check when you intentionally want to affect all
     * rows in the table.
     *
     * @since 1.2
     */
    public abstract fun unsafe(): QueryBuilder<T, R, ID>

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    public abstract fun distinct(): QueryBuilder<T, R, ID>

    /**
     * Resolves the references at the specified paths as part of this query.
     *
     * A [Ref] foreign key is selected as its foreign key column and resolved on demand, which costs a query per
     * reference. A path named here is selected as the referenced table's columns instead, joined into the same
     * statement, so the reference comes back already loaded: [Ref.fetch] returns the record without querying and
     * [Ref.isLoaded] reports `true`.
     *
     * ```kotlin
     * val users = orm.entity<User>().select()
     *     .fetch(User_.city, User_.city.country)
     *     .resultList
     *
     * val city = users.first().city.fetch()   // already loaded, no query
     * ```
     *
     * The record type is unchanged: the property stays a `Ref`, so the same record can come from a query that resolves
     * the reference and from one that does not. Reference identity and equality are unaffected, and [Ref.unload]
     * returns to a reference that carries the key alone.
     *
     * The plan is prefix-closed: naming `User_.city.country` resolves `User_.city` as well, since the city record is
     * what holds the country reference. A reference is always a to-one foreign key, so resolving one widens the row
     * without multiplying it, and a cycle stays bounded by the depth the path names.
     *
     * A nullable reference is joined with an outer join, so a row whose foreign key is null yields a null reference,
     * matching a nullable entity foreign key. A path that crosses no reference is rejected: the target is already part
     * of the entity graph and there is nothing to resolve.
     *
     * @param path the paths of the references to resolve.
     * @return the query builder.
     * @throws PersistenceException if no path is provided, if a path crosses no reference, or if this query does not
     * select a record that can hold one.
     * @since 1.13
     */
    public fun fetch(vararg path: Navigable<T, out Data>): QueryBuilder<T, R, ID> = fetch(path.toList())

    /**
     * Resolves the references at the specified paths as part of this query.
     *
     * A path a generated metamodel cannot express, a cycle deeper than the two hops it constructs in particular, is
     * named with [Metamodel.of].
     *
     * @param paths the paths of the references to resolve.
     * @return the query builder.
     * @throws PersistenceException if no path is provided, if a path crosses no reference, or if this query does not
     * select a record that can hold one.
     * @since 1.13
     */
    public abstract fun fetch(paths: List<Navigable<T, out Data>>): QueryBuilder<T, R, ID>

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract fun crossJoin(relation: KClass<out Data>): QueryBuilder<Data, R, ID>

    /**
     * Adds a cross join to the query.
     *
     * @param J the relation to join.
     * @return the query builder.
     * @since 1.12
     */
    public inline fun <reified J : Data> crossJoin(): QueryBuilder<Data, R, ID> = crossJoin(J::class)

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract fun innerJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID>

    /**
     * Adds an inner join to the query.
     *
     * @param J the relation to join.
     * @return the query builder.
     * @since 1.12
     */
    public inline fun <reified J : Data> innerJoin(): TypedJoinBuilder<T, R, ID> = innerJoin(J::class)

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract fun leftJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID>

    /**
     * Adds a left join to the query.
     *
     * @param J the relation to join.
     * @return the query builder.
     * @since 1.12
     */
    public inline fun <reified J : Data> leftJoin(): TypedJoinBuilder<T, R, ID> = leftJoin(J::class)

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract fun rightJoin(relation: KClass<out Data>): TypedJoinBuilder<T, R, ID>

    /**
     * Adds a right join to the query.
     *
     * @param J the relation to join.
     * @return the query builder.
     * @since 1.12
     */
    public inline fun <reified J : Data> rightJoin(): TypedJoinBuilder<T, R, ID> = rightJoin(J::class)

    /**
     * Adds a join of the specified type to the query.
     *
     * @param type the type of the join (e.g., INNER, LEFT, RIGHT).
     * @param relation the relation to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract fun join(
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
    public fun crossJoin(template: TemplateBuilder): QueryBuilder<Data, R, ID> = crossJoin(template.build())

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    public abstract fun crossJoin(template: TemplateString): QueryBuilder<Data, R, ID>

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public fun innerJoin(template: TemplateBuilder, alias: String): JoinBuilder<T, R, ID> = innerJoin(template.build(), alias)

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract fun innerJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID>

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public fun leftJoin(template: TemplateBuilder, alias: String): JoinBuilder<T, R, ID> = leftJoin(template.build(), alias)

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract fun leftJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID>

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public fun rightJoin(template: TemplateBuilder, alias: String): JoinBuilder<T, R, ID> = rightJoin(template.build(), alias)

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract fun rightJoin(template: TemplateString, alias: String): JoinBuilder<T, R, ID>

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public fun join(
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
    public abstract fun join(
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
    public abstract fun join(
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
    public fun where(id: ID): QueryBuilder<T, R, ID> = whereBuilder { whereId(id) }

    /**
     * Adds a WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the query builder.
     * @since 1.3
     */
    public fun where(ref: Ref<out T>): QueryBuilder<T, R, ID> = whereBuilder { whereRef(ref) }

    /**
     * Adds a WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the query builder.
     */
    public fun where(record: T): QueryBuilder<T, R, ID> = whereBuilder { where(record) }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table.
     *
     * @param it ids to match.
     * @return the query builder.
     * @since 1.2
     */
    public fun whereId(it: Iterable<ID>): QueryBuilder<T, R, ID> = whereBuilder { whereId(it) }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it refs to match.
     * @return the query builder.
     * @since 1.3
     */
    public fun whereRef(it: Iterable<Ref<T>>): QueryBuilder<T, R, ID> = whereBuilder { whereRef(it) }

    /**
     * Adds a WHERE clause that matches the specified record. The record can represent any of the related tables in the
     * table graph.
     *
     * @param path the path to the object in the table graph.
     * @param record the records to match.
     * @return the predicate builder.
     */
    public fun <V : Data> where(path: Navigable<out T, V>, record: V): QueryBuilder<T, R, ID> = where(path eq record)

    /**
     * Adds a WHERE clause that matches the specified ref. The ref can represent any of the related tables in the
     * table graph.
     *
     * @param path the path to the object in the table graph.
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public fun <V : Data> where(path: Navigable<out T, V>, ref: Ref<V>): QueryBuilder<T, R, ID> = whereBuilder { where(path, ref) }

    /**
     * Adds a WHERE clause that matches the specified records. The records can represent any of the related tables in
     * the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it the records to match.
     * @return the predicate builder.
     */
    public fun <V : Data> where(path: Navigable<out T, V>, it: Iterable<V>): QueryBuilder<T, R, ID> = where(path inList it)

    /**
     * Adds a WHERE clause that matches the specified records. The records can represent any of the related tables in
     * the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it the records to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public fun <V : Data> whereRef(
        path: Navigable<out T, V>,
        it: Iterable<Ref<V>>,
    ): QueryBuilder<T, R, ID> = whereBuilder { whereRef(path, it) }

    /**
     * Adds a WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the query builder.
     */
    public fun where(it: Iterable<T>): QueryBuilder<T, R, ID> = whereBuilder { where(it) }

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
    public fun <V> where(
        path: Navigable<out T, V>,
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
    public fun <V> where(
        path: Navigable<out T, V>,
        operator: Operator,
        vararg o: V,
    ): QueryBuilder<T, R, ID> = whereBuilder { where(path, operator, *o) }

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param builder the expression.
     * @return the query builder.
     */
    public fun where(builder: TemplateBuilder): QueryBuilder<T, R, ID> = where(builder.build())

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param template the expression.
     * @return the query builder.
     */
    public fun where(template: TemplateString): QueryBuilder<T, R, ID> = whereBuilder { where(template) }

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    public fun where(predicate: PredicateBuilder<out T, *, *>): QueryBuilder<T, R, ID> = whereBuilder { predicate }

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
    public fun whereExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { exists(subquery) }

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
    public fun whereExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { exists(builder(this)) }

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
    public fun whereNotExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { notExists(subquery) }

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
    public fun whereNotExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = whereBuilder { notExists(builder(this)) }

    /**
     * Adds a WHERE clause to the query using a [WhereBuilder].
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    public abstract fun whereBuilder(predicate: WhereBuilder<T, R, ID>.() -> PredicateBuilder<out T, *, *>): QueryBuilder<T, R, ID>

    /**
     * Adds a GROUP BY clause to the query for field at the specified path in the table graph. The metamodel can refer
     * to manually added joins.
     *
     * A path resolves to the same columns a predicate on that path would use: a foreign key expands to its foreign
     * key column(s) on the referencing table, without joining the referenced table, and an inline record expands to
     * its component columns. A single-column path contributes exactly one column.
     *
     * @param path the path to group by.
     * @return the query builder.
     * @since 1.2
     */
    public fun groupBy(vararg path: Navigable<out T, *>): QueryBuilder<T, R, ID> = groupBy(columnList(path, GROUP_BY, "GROUP BY"))

    /**
     * Renders the paths as a comma-separated column list for the given clause.
     */
    private fun columnList(
        path: Array<out Navigable<out T, *>>,
        clause: Elements.Clause,
        clauseName: String,
    ): TemplateString {
        if (path.isEmpty()) {
            throw PersistenceException("At least one path must be provided for $clauseName clause.")
        }
        val templates = buildList {
            path.forEachIndexed { index, navigable ->
                add(wrap(Columns(listOf(navigable.asMetamodel()), CASCADE, clause)))
                if (index < path.lastIndex) {
                    add(raw(", "))
                }
            }
        }
        return combine(*templates.toTypedArray())
    }

    /**
     * Adds a GROUP BY clause to the query using a string template.
     *
     * @param builder the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    public fun groupBy(builder: TemplateBuilder): QueryBuilder<T, R, ID> = groupBy(builder.build())

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    public abstract fun groupBy(template: TemplateString): QueryBuilder<T, R, ID>

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
    public fun <V> having(
        path: Navigable<out T, V>,
        operator: Operator,
        vararg o: V,
    ): QueryBuilder<T, R, ID> = having(wrap(ObjectExpression(path.asMetamodel(), operator, o)))

    /**
     * Adds a HAVING clause to the query using the specified expression.
     *
     * @param builder the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    public fun having(builder: TemplateBuilder): QueryBuilder<T, R, ID> = having(builder.build())

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    public abstract fun having(template: TemplateString): QueryBuilder<T, R, ID>

    /**
     * Adds a HAVING clause to the query for the specified predicate. Multiple calls to this method are combined using
     * AND; compose the predicate with the infix `and` and `or` operators to build a single clause that mixes both.
     *
     * The predicate is taken directly rather than through a [WhereBuilder]. A HAVING clause filters groups rather than
     * rows, so the builder's identity matching would not carry over, and its methods would read `where` at a `having`
     * call site.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     * @since 1.13
     */
    public abstract fun having(predicate: PredicateBuilder<out T, *, *>): QueryBuilder<T, R, ID>

    /**
     * Adds a HAVING clause that keeps the groups for which [subquery] returns at least one row.
     *
     * @param subquery the subquery to test for existence.
     * @return the query builder.
     * @since 1.13
     */
    public abstract fun havingExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID>

    /**
     * Adds a HAVING clause that keeps the groups for which [subquery] returns no rows.
     *
     * @param subquery the subquery to test for absence.
     * @return the query builder.
     * @since 1.13
     */
    public abstract fun havingNotExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<T, R, ID>

    /**
     * Adds a HAVING clause that keeps the groups for which the subquery built by [builder] returns at least one row.
     *
     * @param builder builds the subquery to test for existence.
     * @return the query builder.
     * @since 1.13
     */
    public fun havingExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = havingExists(builder(subqueryTemplate()))

    /**
     * Adds a HAVING clause that keeps the groups for which the subquery built by [builder] returns no rows.
     *
     * @param builder builds the subquery to test for absence.
     * @return the query builder.
     * @since 1.13
     */
    public fun havingNotExists(builder: SubqueryTemplate.() -> QueryBuilder<*, *, *>): QueryBuilder<T, R, ID> = havingNotExists(builder(subqueryTemplate()))

    /**
     * Returns the factory this query builds its subqueries with.
     *
     * The factory belongs to the query rather than to a clause: a subquery correlates through how it is embedded, not
     * through where it was created, so a clause that takes a subquery can obtain one here without a [WhereBuilder].
     *
     * @return the subquery factory for this query.
     * @since 1.13
     */
    public abstract fun subqueryTemplate(): SubqueryTemplate

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    public fun orderBy(vararg path: Navigable<out T, *>): QueryBuilder<T, R, ID> = orderBy(columnList(path, ORDER_BY_ASCENDING, "ORDER BY"))

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph. The results are
     * sorted in descending order.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    public fun orderByDescending(path: Navigable<out T, *>): QueryBuilder<T, R, ID> = orderBy(wrap(Columns(listOf(path.asMetamodel()), CASCADE, ORDER_BY_DESCENDING)))

    /**
     * Adds an ORDER BY clause to the query for the fields at the specified paths in the table graph. The results
     * are sorted in descending order for each column.
     *
     * @param path the paths to order by.
     * @return the query builder.
     * @since 1.9
     */
    public fun orderByDescending(vararg path: Navigable<out T, *>): QueryBuilder<T, R, ID> = orderBy(columnList(path, ORDER_BY_DESCENDING, "ORDER BY"))

    /**
     * Adds an ORDER BY clause to the query using a string template. The results are sorted in descending order.
     * Multiple calls to this method append additional columns to the ORDER BY clause.
     *
     * @param builder the template to order by.
     * @return the query builder.
     * @since 1.9
     */
    public fun orderByDescending(builder: TemplateBuilder): QueryBuilder<T, R, ID> = orderByDescending(builder.build())

    /**
     * Adds an ORDER BY clause to the query using a string template. The results are sorted in descending order.
     * Multiple calls to this method append additional columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.9
     */
    public fun orderByDescending(template: TemplateString): QueryBuilder<T, R, ID> = orderBy(combine(template, raw(" DESC")))

    /**
     * Adds an ORDER BY clause to the query using a string template.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    public fun orderBy(template: TemplateBuilder): QueryBuilder<T, R, ID> = orderBy(template.build())

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    public abstract fun orderBy(template: TemplateString): QueryBuilder<T, R, ID>

    /**
     * Returns `true` if any ORDER BY columns have been added to this query builder.
     *
     * @return `true` if ORDER BY columns are present, `false` otherwise.
     * @since 1.9
     */
    protected abstract fun hasOrderBy(): Boolean

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    public abstract fun limit(limit: Int): QueryBuilder<T, R, ID>

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    public abstract fun offset(offset: Int): QueryBuilder<T, R, ID>

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
    public abstract fun forShare(): QueryBuilder<T, R, ID>

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    public abstract fun forUpdate(): QueryBuilder<T, R, ID>

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
    public fun forLock(builder: TemplateBuilder): QueryBuilder<T, R, ID> = forLock(builder.build())

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
    public abstract fun forLock(template: TemplateString): QueryBuilder<T, R, ID>

    //
    // Finalization.
    //

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    public abstract fun build(): Query

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
    public fun prepare(): PreparedQuery = build().prepare()

    /**
     * Executes the query and returns a [Page] of results using offset-based pagination.
     *
     * This method executes the query for the requested page and, when the total cannot be derived from the
     * fetched page, a count query (without offset or limit). A page that is not full determines the total directly,
     * so the count query only runs for a full page, or for an empty page beyond the first. The caller is responsible
     * for adding ORDER BY clauses to ensure deterministic ordering across pages.
     *
     * Page numbers are zero-based: pass `0` for the first page.
     *
     * @param pageNumber the zero-based page index (must not be negative).
     * @param pageSize the maximum number of results per page (must be positive).
     * @return a page containing the results and pagination metadata.
     * @throws IllegalArgumentException if [pageNumber] is negative or [pageSize] is not positive.
     * @since 1.10
     */
    public fun page(pageNumber: Int, pageSize: Int): Page<R> = page(Pageable.of(pageNumber, pageSize))

    /**
     * Executes the query and returns a [Page] of results using offset-based pagination.
     *
     * This method executes the query for the requested page and, when the total cannot be derived from the
     * fetched page, a count query (without offset or limit). A page that is not full determines the total directly,
     * so the count query only runs for a full page, or for an empty page beyond the first. Sort orders can be
     * specified either through the pageable or through explicit `orderBy` calls on the query builder, but not both.
     * If both are present, a [PersistenceException] is thrown.
     *
     * Use [Pageable.ofSize] for the first page, then navigate with
     * [Page.next] or [Page.previous].
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @throws PersistenceException if the pageable has sort orders and the query builder has explicit orderBy calls.
     * @since 1.10
     */
    public fun page(pageable: Pageable): Page<R> {
        val content = pageContent(pageable, pageable.pageSize())
        val totalCount = if (content.size < pageable.pageSize() && (pageable.offset() == 0L || content.isNotEmpty())) {
            // A page that is not full is the last page, so the total follows from the page itself. An empty page
            // beyond the first proves nothing: the offset may lie anywhere past the end.
            pageable.offset() + content.size
        } else {
            resultCount
        }
        return Page(content, totalCount, pageable)
    }

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
    public fun page(pageable: Pageable, totalCount: Long): Page<R> = Page(pageContent(pageable, pageable.pageSize()), totalCount, pageable)

    /**
     * Fetches the content for the requested page, applying the pageable's sort orders and offset/limit window.
     */
    /**
     * Executes the query and returns a [Slice] of results using offset-based pagination without a count.
     *
     * The slice is read the way a page is, with OFFSET and LIMIT from the request, but one row beyond the page
     * size is fetched instead of running a count query: [Slice.hasNext] says whether that row existed, and
     * [Slice.hasPrevious] follows from the page number. Use it for a "load more" that needs no total, and for a
     * query without a unique key, where [scroll] is not possible.
     *
     * Page numbers are zero-based: pass `0` for the first slice.
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of results per slice.
     * @return the slice.
     * @since 1.14
     */
    public fun slice(pageNumber: Int, pageSize: Int): Slice<R> = slice(Pageable.of(pageNumber, pageSize))

    /**
     * Executes the query and returns a [Slice] of results using offset-based pagination without a count.
     *
     * The slice is read the way a page is, with OFFSET and LIMIT from the request, but one row beyond the page
     * size is fetched instead of running a count query: [Slice.hasNext] says whether that row existed, and
     * [Slice.hasPrevious] follows from the page number. Use it for a "load more" that needs no total, and for a
     * query without a unique key, where [scroll] is not possible.
     *
     * Use [Pageable.ofSize] for the first slice, then navigate with [Pageable.next] or [Pageable.previous].
     *
     * @param pageable the request specifying page number, page size and sort orders.
     * @return the slice.
     * @throws PersistenceException if the request carries sort orders and the query an explicit ORDER BY.
     * @since 1.14
     */
    public fun slice(pageable: Pageable): Slice<R> {
        val content = pageContent(pageable, pageable.pageSize() + 1)
        val hasNext = content.size > pageable.pageSize()
        return Slice.of(if (hasNext) content.subList(0, pageable.pageSize()) else content, hasNext, pageable.pageNumber() > 0)
    }

    private fun pageContent(pageable: Pageable, limit: Int): List<R> {
        // Forbid combining explicit orderBy with Pageable sort orders for consistency with scroll, which also
        // manages ORDER BY internally and forbids explicit orderBy calls.
        if (hasOrderBy() && pageable.orders().isNotEmpty()) {
            throw PersistenceException("Pageable sort orders cannot be combined with explicit orderBy calls.")
        }
        var sorted: QueryBuilder<T, R, ID> = this
        for (order in pageable.orders()) {
            // The Pageable's sort field may be rooted anywhere in the query, so the column is named directly.
            sorted = sorted.orderBy(wrap(Columns(listOf(order.field()), CASCADE, if (order.descending()) ORDER_BY_DESCENDING else ORDER_BY_ASCENDING)))
        }
        return sorted.offset(pageable.offset().toInt()).limit(limit).resultList
    }

    /**
     * Executes a scroll request and returns a [Window]: the results in the request's sort order, the flags that
     * say whether rows exist after and before the window, and the tokens that continue from it.
     *
     * The request owns the ordering, so the query must not carry an ORDER BY of its own. The sort fields and the
     * key are read from each row alongside the result, so the tokens are there for every result type: the entity,
     * a projection, a ref or a custom select type. A window reached through [Window.previous] comes back in the
     * same sort order as every other window.
     *
     * @param scrollable the scroll request: ordering, size and position.
     * @return a window containing the results and navigation tokens.
     * @throws PersistenceException if the query carries an explicit ORDER BY, the key is compound or nullable, or
     * a sort field is nullable.
     * @since 1.11
     */
    public abstract fun scroll(scrollable: Scrollable<T>): Window<R>

    /**
     * Executes the query in windows of [size] rows ordered by the primary key, each window one closed statement.
     *
     * Where [resultFlow] holds one open statement on the connection for as long as the flow is collected, a window
     * is fetched by a statement that has returned and closed before the window is emitted. Between windows the
     * connection is free, so the loop over a window may query, fetch references and write, inside a transaction or
     * with a transaction per window. The flow carries no database resource. Each window is its own statement: it
     * runs the query's WHERE clause again from the cursor position, and under READ COMMITTED it sees rows committed
     * since the previous window.
     *
     * Windows are keyset windows over the primary key, so the query must not carry an ORDER BY of its own, and the
     * result type must be the entity type the key belongs to: `selectRef()` and custom select types are refused. A
     * compound primary key is refused too; pass [windows] a [Scrollable] with a single-column unique key instead.
     * The rows come in ascending key order; pass `Scrollable.of(key, size).descending()` for descending order.
     *
     * ```kotlin
     * users.select().where(User_.city eq city).windows(1000).collect { window ->
     *     users.update(window.content().map { it.copy(email = it.email.lowercase()) })
     * }
     * ```
     *
     * @param size the maximum number of rows per window (must be positive).
     * @return a flow of windows; each window's [Window.next] resumes the iteration after that window.
     * @throws IllegalArgumentException if [size] is not positive.
     * @throws PersistenceException if the query has no single-column primary key, carries an explicit ORDER BY, or
     * does not select the entity type.
     * @since 1.14
     */
    public abstract fun windows(size: Int): Flow<Window<R>>

    /**
     * Executes the query in windows described by the given scroll request, each window one closed statement.
     *
     * This is the form of [windows] that chooses the key, the sort fields, the directions and the starting
     * position: `Scrollable.of(key, size)` iterates from the start, a [Window.next] token or [Scrollable.from]
     * resumes after an earlier window, and `.descending()` iterates in descending key order. The same key rules as
     * [scroll] apply.
     *
     * @param scrollable the scroll request describing key, sort, size, direction and starting position.
     * @return a flow of windows; each window's [Window.next] resumes the iteration after that window.
     * @throws PersistenceException if the query carries an explicit ORDER BY, the key is nullable, or the result
     * type does not carry the key.
     * @since 1.14
     */
    public abstract fun windows(scrollable: Scrollable<T>): Flow<Window<R>>

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
     * The stream is one open statement on the connection it reads from, and that connection is consume-only until
     * the stream is read to its end or closed: inside a transaction every statement shares the transaction's
     * connection, so a query, a `Ref.fetch()` or a write issued while rows remain unread is refused with a
     * [PersistenceException], on every database. A loop that needs the connection while it iterates uses [windows], which runs one closed statement
     * per window. Outside a transaction the stream holds a connection of its own for as long as it is open.
     *
     * **Note:** Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is `AutoCloseable`, it is recommended to use it
     * within a `use` block.
     *
     * @return a stream of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     */
    public abstract val resultStream: Stream<R>

    /**
     * Executes the query and returns a flow of results.
     *
     * The flow is cold: the query executes when the flow is collected, rows are read from the database as they are
     * emitted, and the statement closes when collection completes or is cancelled, so no explicit cleanup is
     * needed.
     *
     * The flow is one open statement on the connection it reads from, and that connection is consume-only until
     * the last row is emitted or collection is cancelled: inside a transaction every statement shares the
     * transaction's connection, so a query, a `Ref.fetch()` or a write issued from the collector while rows remain
     * is refused with a [PersistenceException], on every database. A loop that needs the connection while it iterates uses [windows], which runs one closed statement
     * per window. Outside a transaction the flow holds a connection of its own for as long as it is collected.
     *
     * @return a flow of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     * @since 1.5
     */
    public val resultFlow: Flow<R>
        get() = resultStream.consumeAsFlow()

    public open val resultCount: Long
        /**
         * Returns the number of results of this query.
         *
         * Select queries execute a dedicated count query derived from this builder: the select clause is replaced by
         * `COUNT(*)`, or the query is counted as a derived table when its shape requires it (DISTINCT, GROUP BY,
         * HAVING, limit, offset or a custom select clause). Queries that lock rows fetch and
         * count the results instead, so the requested locks are acquired.
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

    public open val resultList: List<R>
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

    /**
     * Executes the query and returns the results grouped by the record reached via [path], typically the parent
     * entity of a foreign key:
     *
     * ```kotlin
     * val ownersWithPets: Map<Owner, List<Pet>> = orm.entity<Pet>()
     *     .select()
     *     .where(Pet_.owner.city eq city)
     *     .orderBy(Pet_.owner)
     *     .resultGroupedBy(Pet_.owner)
     * ```
     *
     * The SQL is not affected by the grouping; the same select is executed and the results are grouped during
     * hydration. The returned map and its lists are unmodifiable and insertion-ordered: groups appear in the order
     * their first result is encountered, and results appear in encounter order within each group. Use `orderBy()` to
     * control both. Duplicate entities within a result set are guaranteed to share the same instance as long as
     * earlier occurrences remain strongly reachable, and the grouping retains every result and group key while the
     * result set is consumed; each result's reference to its group key is therefore the map key itself.
     *
     * This method requires an entity query: the result type must be the table type `T` so that the path can be
     * resolved against the results. The path must also resolve to a non-null record for every result; paths over
     * nullable foreign keys must be narrowed with a `where()` clause first.
     *
     * The signature requires a path whose component type equals its field type, which is how the generated
     * metamodels type eagerly fetched fields. Paths over `Ref` fields are typed
     * `TypedMetamodel<T, V, Ref<V>>` and therefore do not compile; use [resultGroupedByRef] for those.
     *
     * @param path the metamodel path from the table type to the record to group by, for example `Pet_.owner`.
     * @param V the type of the record to group by.
     * @return the results grouped by the record reached via [path], in encounter order.
     * @throws PersistenceException if the query fails, if the result type is not the table type, or if the path
     * resolves to null for a result.
     * @since 1.13
     */
    public abstract fun <V : Data> resultGroupedBy(path: TypedMetamodel<T, V, out V?>): Map<V, List<R>>

    /**
     * Executes the query and returns the results grouped by a lightweight ref to the record reached via [path],
     * typically the parent entity of a foreign key.
     *
     * This is the ref-based variant of [resultGroupedBy]: the map keys are [Ref] instances, which are compared by
     * primary key, keeping map lookups constant-cost regardless of the size of the group record.
     *
     * The behavior of the keys follows how the foreign key is declared on the record:
     *
     * - **Entity field** (for example `@FK val owner: Owner`): the referenced record is fetched eagerly, as part
     *   of the query's auto-joined graph, and is materialized with each result. The keys are *loaded* refs
     *   wrapping that record: [Ref.getOrNull] returns it directly, without touching the database.
     * - **Ref field** (for example `@FK val pet: Ref<Pet>`): the referenced record is fetched lazily; the query
     *   reads only the foreign key column, without joining or fetching the referenced table. The keys are the
     *   *unloaded* refs produced by the query, carrying just the primary key. When the records are needed, fetch
     *   them afterwards in a single query with `findAllByRef(map.keys)`:
     *
     * ```kotlin
     * val visitsByPet: Map<Ref<Pet>, List<Visit>> = orm.entity<Visit>()
     *     .select()
     *     .resultGroupedByRef(Visit_.pet)
     * ```
     *
     * The SQL is not affected by the grouping; the same select is executed and the results are grouped during
     * hydration. The returned map and its lists are unmodifiable and insertion-ordered: groups appear in the order
     * their first result is encountered, and results appear in encounter order within each group. Use `orderBy()` to
     * control both.
     *
     * This method requires an entity query: the result type must be the table type `T` so that the path can be
     * resolved against the results. The path must also resolve to a non-null value for every result; paths over
     * nullable foreign keys must be narrowed with a `where()` clause first.
     *
     * @param path the metamodel path from the table type to the record to group by, for example `Visit_.pet`.
     * @param V the type of the record to group by.
     * @return the results grouped by a ref to the record reached via [path], in encounter order.
     * @throws PersistenceException if the query fails, if the result type is not the table type, if the path does
     * not reference an entity or ref, or if the path resolves to null for a result.
     * @since 1.13
     */
    public abstract fun <V : Data> resultGroupedByRef(path: Metamodel<T, V>): Map<Ref<V>, List<R>>

    public open val singleResult: R
        /**
         * Executes the query and returns a single result.
         *
         * @return the single result.
         * @throws NoResultException if there is no result.
         * @throws NonUniqueResultException if more than one result.
         * @throws PersistenceException if the single row's value is null, or the query fails.
         */
        get() {
            resultStream.use { stream ->
                val iterator = stream.iterator()
                if (!iterator.hasNext()) {
                    throw NoResultException("Expected single result, but found none.")
                }
                val result = iterator.next()
                if (iterator.hasNext()) {
                    throw NonUniqueResultException("Expected single result, but found more than one.")
                }
                if (result == null) {
                    throw PersistenceException("Expected single result, but found null. Wrap the field in COALESCE() to provide a non-null default.")
                }
                return result
            }
        }

    public open val optionalResult: R?
        /**
         * Executes the query and returns an optional result.
         *
         * @return the optional result; `null` when no row matched.
         * @throws NonUniqueResultException if more than one result.
         * @throws PersistenceException if the single row's value is null, or the query fails.
         */
        get() {
            resultStream.use { stream ->
                val iterator = stream.iterator()
                if (!iterator.hasNext()) {
                    return null
                }
                val result = iterator.next()
                if (iterator.hasNext()) {
                    throw NonUniqueResultException("Expected single result, but found more than one.")
                }
                if (result == null) {
                    throw PersistenceException("Result is null. Wrap the field in COALESCE() to provide a non-null default.")
                }
                return result
            }
        }

    /**
     * Execute a DELETE statement.
     *
     * @return the number of rows impacted as result of the statement.
     * @throws PersistenceException if the statement fails.
     */
    public fun executeUpdate(): Int = build().executeUpdate()
}

// Kotlin specific DSL

/**
 * Infix function to create a predicate to check if a field is in a list of values.
 */
public infix fun <T : Data, V> Navigable<T, V>.inList(value: Iterable<V>): PredicateBuilder<T, T, *> = create(this.asMetamodel(), IN, value)

/**
 * Infix function to create a predicate to check if a field is in a list of references.
 */
public infix fun <T : Data, V : Data> Metamodel<T, V>.inRefs(value: Iterable<Ref<V>>): PredicateBuilder<T, T, *> = createRef(this, IN, value)

/**
 * Infix function to create a predicate to check if a field is not in a list of values.
 */
public infix fun <T : Data, V> Navigable<T, V>.notInList(value: Iterable<V>): PredicateBuilder<T, T, *> = create(this.asMetamodel(), NOT_IN, value)

/**
 * Infix function to create a predicate to check if a field is not in a list of references.
 */
public infix fun <T : Data, V : Data> Metamodel<T, V>.notInRefs(value: Iterable<Ref<V>>): PredicateBuilder<T, T, *> = createRef(this, NOT_IN, value)

/**
 * Infix functions to create a predicate to check if a field is equal to a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.eq(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is equal to a reference.
 */
public infix fun <T : Data, V : Data> Metamodel<T, V>.eq(value: Ref<V>): PredicateBuilder<T, T, *> = createRef(this, EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is not equal to a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.neq(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), NOT_EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is not equal to a reference.
 */
public infix fun <T : Data, V : Data> Metamodel<T, V>.neq(value: Ref<V>): PredicateBuilder<T, T, *> = createRef(this, NOT_EQUALS, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is like a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.like(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), LIKE, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is not like a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.notLike(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), NOT_LIKE, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is greater than a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.greater(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), GREATER_THAN, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is less than a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.less(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), LESS_THAN, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is greater than or equal to a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.greaterEq(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), GREATER_THAN_OR_EQUAL, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is less than or equal to a value.
 */
public infix fun <T : Data, V> Navigable<T, V>.lessEq(value: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), LESS_THAN_OR_EQUAL, listOf(value))

/**
 * Infix functions to create a predicate to check if a field is between two values.
 */
public fun <T : Data, V> Navigable<T, V>.between(left: V, right: V): PredicateBuilder<T, T, *> = create(this.asMetamodel(), BETWEEN, listOf(left, right))

/**
 * Infix functions to create a predicate to check if a field is true.
 */
public fun <T : Data, V> Navigable<T, V>.isTrue(): PredicateBuilder<T, T, *> = create(this.asMetamodel(), IS_TRUE, emptyList())

/**
 * Infix functions to create a predicate to check if a field is false.
 */
public fun <T : Data, V> Navigable<T, V>.isFalse(): PredicateBuilder<T, T, *> = create(this.asMetamodel(), IS_FALSE, emptyList())

/**
 * Infix functions to create a predicate to check if a field is null.
 */
public fun <T : Data, V> Navigable<T, V>.isNull(): PredicateBuilder<T, T, *> = create(this.asMetamodel(), IS_NULL, emptyList())

/**
 * Infix functions to create a predicate to check if a field is not null.
 */
public fun <T : Data, V> Navigable<T, V>.isNotNull(): PredicateBuilder<T, T, *> = create(this.asMetamodel(), IS_NOT_NULL, emptyList())

/**
 * Combines two predicates using an AND condition.
 *
 * The result is rooted at the operands' least common root: predicates that share a root keep it, and predicates
 * rooted at different entities combine into a predicate that only a widened query accepts. Written directly in a
 * `where(...)` call, the builder's root drives the inference, so a narrow builder rejects a cross-root combination
 * at the call site while a widened builder accepts it and verifies the paths when the query is built.
 *
 * Inside a [WhereBuilder] scope, the scope's own `and` takes precedence and inherits the query root, so a widened
 * query combines predicates across joined entities with the same syntax.
 *
 * @param predicate the predicate to add.
 * @return the combined predicate builder, rooted at the operands' least common root.
 */
public infix fun <T : Data> PredicateBuilder<out T, *, *>.and(predicate: PredicateBuilder<out T, *, *>): PredicateBuilder<T, *, *> = combineAnd(this, predicate)

/**
 * Combines two predicates using an OR condition.
 *
 * The result is rooted at the operands' least common root: predicates that share a root keep it, and predicates
 * rooted at different entities combine into a predicate that only a widened query accepts. Written directly in a
 * `where(...)` call, the builder's root drives the inference, so a narrow builder rejects a cross-root combination
 * at the call site while a widened builder accepts it and verifies the paths when the query is built.
 *
 * Inside a [WhereBuilder] scope, the scope's own `or` takes precedence and inherits the query root, so a widened
 * query combines predicates across joined entities with the same syntax.
 *
 * @param predicate the predicate to add.
 * @return the combined predicate builder, rooted at the operands' least common root.
 */
public infix fun <T : Data> PredicateBuilder<out T, *, *>.or(predicate: PredicateBuilder<out T, *, *>): PredicateBuilder<T, *, *> = combineOr(this, predicate)

// Block-based query DSL

/**
 * A mutable scope for constructing queries using [QueryBuilder] methods in a block-based style, similar to
 * [buildList] or [buildMap].
 *
 * Each call inside the block (such as [where], [orderBy], [limit]) updates the internal builder state.
 *
 * ```kotlin
 * userRepository.select {
 *     where(User_.email like "%@example.com")
 *     orderBy(User_.name)
 * }.resultList
 * ```
 *
 * The scope holds both ends of the root-model axis at once: clauses delegate to a widened builder, so paths of any
 * entity in the query work without escalation, while the scope's own type parameters keep record, id, ref and fetch
 * matching typed to the root. Operations that change the builder's type ([QueryBuilder.narrow], [QueryBuilder.widen]
 * and [QueryBuilder.typedId]) and terminals such as [QueryBuilder.page] and [QueryBuilder.scroll] are deliberately
 * absent: they belong on the builder the block returns, as in `select { }.narrow<Pet>().resultGroupedBy(Pet_.owner)`.
 *
 * @param T the entity type being queried.
 * @param R the result type of the query.
 * @param ID the primary key type.
 */
@SqlDsl
public class SqlScope<T : Data, R, ID : Any> @PublishedApi internal constructor(
    @PublishedApi internal var builder: QueryBuilder<Data, R, ID>,
) {
    /** Adds a WHERE clause using a predicate built with metamodel infix operators (e.g., `User_.name eq "Alice"`). */
    public fun where(predicate: PredicateBuilder<*, *, *>) {
        builder = builder.where(predicate)
    }

    /** Adds a WHERE clause matching a metamodel path to value(s) using an [Operator]. */
    public fun <V> where(path: Navigable<*, V>, operator: Operator, vararg value: V) {
        builder = builder.where(path, operator, *value)
    }

    /** Adds a WHERE clause matching a metamodel path to a data record. */
    public fun <V : Data> where(path: Navigable<*, V>, record: V) {
        builder = builder.where(path, record)
    }

    /** Adds a WHERE clause matching a metamodel path to a [Ref]. */
    public fun <V : Data> where(path: Navigable<*, V>, ref: Ref<V>) {
        builder = builder.where(path, ref)
    }

    /** Adds a WHERE clause matching a metamodel path to any of the specified records. */
    public fun <V : Data> where(path: Navigable<*, V>, it: Iterable<V>) {
        builder = builder.where(path, it)
    }

    /** Adds a WHERE clause matching a metamodel path to value(s) using an [Operator]. */
    public fun <V> where(path: Navigable<*, V>, operator: Operator, it: Iterable<V>) {
        builder = builder.where(path, operator, it)
    }

    /** Adds a WHERE clause matching a metamodel path to any of the specified [Ref]s. */
    public fun <V : Data> whereRef(path: Navigable<*, V>, it: Iterable<Ref<V>>) {
        builder = builder.whereRef(path, it)
    }

    /** Adds a WHERE clause matching a primary key. */
    public fun where(id: ID) {
        builder = builder.where(id)
    }

    /** Adds a WHERE clause matching a [Ref]. */
    public fun where(ref: Ref<T>) {
        builder = builder.where(ref as Ref<out Data>)
    }

    /** Adds a WHERE clause matching a record. */
    public fun where(record: T) {
        builder = builder.where(record)
    }

    /** Adds a WHERE clause matching any of the specified records. */
    public fun where(it: Iterable<T>) {
        builder = builder.where(it)
    }

    /** Adds a WHERE clause matching any of the specified primary keys. */
    public fun whereId(it: Iterable<ID>) {
        builder = builder.whereId(it)
    }

    /** Adds a WHERE clause matching any of the specified [Ref]s. */
    public fun whereRef(it: Iterable<Ref<T>>) {
        // The scope's T is the FROM entity by construction, so this signature already checks what the widened
        // builder's Ref<Data> parameter cannot: the refs reference the table the query selects from. The cast
        // re-labels the checked refs to fit the widened signature.
        @Suppress("UNCHECKED_CAST")
        builder = builder.whereRef(it as Iterable<Ref<Data>>)
    }

    /** Adds a WHERE clause using a SQL template expression (e.g., `where { "${t(User_.score)} > ${t(100)}" }`). */
    public fun where(template: TemplateBuilder) {
        builder = builder.where(template)
    }

    /** Adds a WHERE clause using a [WhereBuilder] for compound predicates with `and`/`or`. */
    public fun whereBuilder(predicate: WhereBuilder<Data, R, ID>.() -> PredicateBuilder<*, *, *>) {
        builder = builder.whereBuilder(predicate)
    }

    /** Adds a WHERE EXISTS clause with the given subquery. */
    public fun whereExists(subquery: QueryBuilder<*, *, *>) {
        builder = builder.whereExists(subquery)
    }

    /** Adds a WHERE NOT EXISTS clause with the given subquery. */
    public fun whereNotExists(subquery: QueryBuilder<*, *, *>) {
        builder = builder.whereNotExists(subquery)
    }

    /** Adds a WHERE EXISTS clause for the subquery built by [subquery]. */
    public fun whereExists(subquery: SubqueryTemplate.() -> QueryBuilder<*, *, *>) {
        builder = builder.whereExists(subquery)
    }

    /** Adds a WHERE NOT EXISTS clause for the subquery built by [subquery]. */
    public fun whereNotExists(subquery: SubqueryTemplate.() -> QueryBuilder<*, *, *>) {
        builder = builder.whereNotExists(subquery)
    }

    /** Adds an INNER JOIN with automatic ON resolution between [relation] and [on]. */
    public fun innerJoin(relation: KClass<out Data>, on: KClass<out Data>) {
        builder = builder.innerJoin(relation).on(on)
    }

    /** Adds an INNER JOIN with automatic ON resolution between [J] and [O] (e.g., `innerJoin<Order, User>()`). */
    public inline fun <reified J : Data, reified O : Data> innerJoin() {
        builder = builder.innerJoin(J::class).on(O::class)
    }

    /** Adds a LEFT JOIN with automatic ON resolution between [relation] and [on]. */
    public fun leftJoin(relation: KClass<out Data>, on: KClass<out Data>) {
        builder = builder.leftJoin(relation).on(on)
    }

    /** Adds a LEFT JOIN with automatic ON resolution between [J] and [O] (e.g., `leftJoin<Order, User>()`). */
    public inline fun <reified J : Data, reified O : Data> leftJoin() {
        builder = builder.leftJoin(J::class).on(O::class)
    }

    /** Adds a RIGHT JOIN with automatic ON resolution between [relation] and [on]. */
    public fun rightJoin(relation: KClass<out Data>, on: KClass<out Data>) {
        builder = builder.rightJoin(relation).on(on)
    }

    /** Adds a RIGHT JOIN with automatic ON resolution between [J] and [O] (e.g., `rightJoin<Order, User>()`). */
    public inline fun <reified J : Data, reified O : Data> rightJoin() {
        builder = builder.rightJoin(J::class).on(O::class)
    }

    /** Adds a CROSS JOIN for [relation]. */
    public fun crossJoin(relation: KClass<out Data>) {
        builder = builder.crossJoin(relation)
    }

    /** Adds a CROSS JOIN for [J]. */
    public inline fun <reified J : Data> crossJoin() {
        builder = builder.crossJoin(J::class)
    }

    /** Adds an INNER JOIN for [relation] with a template ON condition. */
    public fun innerJoin(relation: KClass<out Data>, on: TemplateBuilder) {
        builder = builder.innerJoin(relation).on(on)
    }

    /** Adds an INNER JOIN for [J] with a template ON condition (e.g., `innerJoin<Order> { "..." }`). */
    public inline fun <reified J : Data> innerJoin(noinline on: TemplateBuilder) {
        builder = builder.innerJoin(J::class).on(on)
    }

    /** Adds a LEFT JOIN for [relation] with a template ON condition. */
    public fun leftJoin(relation: KClass<out Data>, on: TemplateBuilder) {
        builder = builder.leftJoin(relation).on(on)
    }

    /** Adds a LEFT JOIN for [J] with a template ON condition (e.g., `leftJoin<Order> { "..." }`). */
    public inline fun <reified J : Data> leftJoin(noinline on: TemplateBuilder) {
        builder = builder.leftJoin(J::class).on(on)
    }

    /** Adds a RIGHT JOIN for [relation] with a template ON condition. */
    public fun rightJoin(relation: KClass<out Data>, on: TemplateBuilder) {
        builder = builder.rightJoin(relation).on(on)
    }

    /** Adds a RIGHT JOIN for [J] with a template ON condition (e.g., `rightJoin<Order> { "..." }`). */
    public inline fun <reified J : Data> rightJoin(noinline on: TemplateBuilder) {
        builder = builder.rightJoin(J::class).on(on)
    }

    /** Adds a join of the specified [type] for [relation] under [alias], with automatic ON resolution against [on]. */
    public fun join(type: JoinType, relation: KClass<out Data>, alias: String, on: KClass<out Data>) {
        builder = builder.join(type, relation, alias).on(on)
    }

    /** Adds a join of the specified [type] for [relation] under [alias], with a template ON condition. */
    public fun join(type: JoinType, relation: KClass<out Data>, alias: String, on: TemplateBuilder) {
        builder = builder.join(type, relation, alias).on(on)
    }

    /** Adds an INNER JOIN for the relation given by [template] under [alias], with a template ON condition. */
    public fun innerJoin(template: TemplateBuilder, alias: String, on: TemplateBuilder) {
        builder = builder.innerJoin(template, alias).on(on)
    }

    /** Adds a LEFT JOIN for the relation given by [template] under [alias], with a template ON condition. */
    public fun leftJoin(template: TemplateBuilder, alias: String, on: TemplateBuilder) {
        builder = builder.leftJoin(template, alias).on(on)
    }

    /** Adds a RIGHT JOIN for the relation given by [template] under [alias], with a template ON condition. */
    public fun rightJoin(template: TemplateBuilder, alias: String, on: TemplateBuilder) {
        builder = builder.rightJoin(template, alias).on(on)
    }

    /** Adds a join of the specified [type] for the relation given by [template] under [alias], with a template ON condition. */
    public fun join(type: JoinType, template: TemplateBuilder, alias: String, on: TemplateBuilder) {
        builder = builder.join(type, template, alias).on(on)
    }

    /** Adds a join of the specified [type] for [subquery] under [alias], with a template ON condition. */
    public fun join(type: JoinType, subquery: QueryBuilder<*, *, *>, alias: String, on: TemplateBuilder) {
        builder = builder.join(type, subquery, alias).on(on)
    }

    /** Adds a CROSS JOIN for the relation given by [template]. */
    public fun crossJoin(template: TemplateBuilder) {
        builder = builder.crossJoin(template)
    }

    /** Adds a GROUP BY clause for the specified metamodel path(s). */
    public fun groupBy(vararg path: Navigable<*, *>) {
        builder = builder.groupBy(*path)
    }

    /** Adds a GROUP BY clause using a SQL template expression. */
    public fun groupBy(template: TemplateBuilder) {
        builder = builder.groupBy(template)
    }

    /** Adds a HAVING clause for the specified predicate. */
    public fun having(predicate: PredicateBuilder<*, *, *>) {
        builder = builder.having(predicate)
    }

    /** Adds a HAVING clause matching a metamodel path to value(s) using an [Operator]. */
    public fun <V> having(path: Navigable<*, V>, operator: Operator, vararg value: V) {
        builder = builder.having(path, operator, *value)
    }

    /** Adds a HAVING clause using a SQL template expression (e.g., `having { "COUNT(*) > ${t(5)}" }`). */
    public fun having(template: TemplateBuilder) {
        builder = builder.having(template)
    }

    /** Adds a HAVING EXISTS clause with the given subquery. */
    public fun havingExists(subquery: QueryBuilder<*, *, *>) {
        builder = builder.havingExists(subquery)
    }

    /** Adds a HAVING NOT EXISTS clause with the given subquery. */
    public fun havingNotExists(subquery: QueryBuilder<*, *, *>) {
        builder = builder.havingNotExists(subquery)
    }

    /** Adds a HAVING EXISTS clause for the subquery built by [subquery]. */
    public fun havingExists(subquery: SubqueryTemplate.() -> QueryBuilder<*, *, *>) {
        builder = builder.havingExists(subquery)
    }

    /** Adds a HAVING NOT EXISTS clause for the subquery built by [subquery]. */
    public fun havingNotExists(subquery: SubqueryTemplate.() -> QueryBuilder<*, *, *>) {
        builder = builder.havingNotExists(subquery)
    }

    /** Adds an ORDER BY clause (ascending) for the specified metamodel path(s). */
    public fun orderBy(vararg path: Navigable<*, *>) {
        builder = builder.orderBy(*path)
    }

    /** Adds an ORDER BY clause (descending) for the specified metamodel path(s). */
    public fun orderByDescending(vararg path: Navigable<*, *>) {
        builder = builder.orderByDescending(*path)
    }

    /** Adds an ORDER BY clause using a SQL template expression. */
    public fun orderBy(template: TemplateBuilder) {
        builder = builder.orderBy(template)
    }

    /** Adds an ORDER BY clause (descending) using a SQL template expression. */
    public fun orderByDescending(template: TemplateBuilder) {
        builder = builder.orderByDescending(template)
    }

    /** Adds a LIMIT clause restricting the maximum number of results. */
    public fun limit(limit: Int) {
        builder = builder.limit(limit)
    }

    /** Adds an OFFSET clause skipping the first [offset] results. */
    public fun offset(offset: Int) {
        builder = builder.offset(offset)
    }

    /** Marks the query as SELECT DISTINCT. */
    public fun distinct() {
        builder = builder.distinct()
    }

    /**
     * Resolves the references at the specified metamodel path(s) as part of this query, so [Ref.fetch] returns the
     * referenced record without querying.
     */
    public fun fetch(vararg path: Navigable<out T, out Data>) {
        // The scope's T is the FROM entity by construction, so this signature already checks what the widened
        // builder's Data-rooted fetch cannot: the paths start at the table the query selects from. The cast
        // re-labels the checked paths to fit the widened signature.
        @Suppress("UNCHECKED_CAST")
        builder = builder.fetch(path.toList() as List<Navigable<Data, out Data>>)
    }

    /** Allows DELETE/UPDATE without a WHERE clause (Storm rejects this by default). */
    public fun unsafe() {
        builder = builder.unsafe()
    }

    /** Locks the selected rows for reading (SELECT ... FOR SHARE). */
    public fun forShare() {
        builder = builder.forShare()
    }

    /** Locks the selected rows for writing (SELECT ... FOR UPDATE). */
    public fun forUpdate() {
        builder = builder.forUpdate()
    }

    /** Locks the selected rows using a dialect-specific template (e.g., `forLock { "FOR UPDATE SKIP LOCKED" }`). */
    public fun forLock(template: TemplateBuilder) {
        builder = builder.forLock(template)
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
