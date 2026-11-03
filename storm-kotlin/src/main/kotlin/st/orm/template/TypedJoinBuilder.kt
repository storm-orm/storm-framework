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
import kotlin.reflect.KClass

/**
 * A builder for specifying the ON condition of a JOIN clause using a type-safe entity relation or a custom expression.
 *
 * `TypedJoinBuilder` extends [JoinBuilder] and is returned by the join methods on [QueryBuilder] that accept an
 * entity class. In addition to the template-based [JoinBuilder.on] method, it provides an [on] method that accepts
 * a [KClass] to resolve the join condition automatically based on the foreign key relationships defined in the
 * entity graph.
 *
 * ## Example: Automatic join resolution
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .innerJoin(Order::class).on(User::class)
 *     .resultList
 * ```
 *
 * The same join can be expressed with reified type arguments:
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .innerJoin<Order>().on<User>()
 *     .resultList
 * ```
 *
 * @param T the type of the table being queried.
 * @param R the type of the result.
 * @param ID the type of the primary key.
 * @see JoinBuilder
 * @see QueryBuilder
 */
public abstract class TypedJoinBuilder<T : Data, R : Any, ID : Any> : JoinBuilder<T, R, ID>() {
    /**
     * Specifies the relation to join on.
     *
     * @param relation the relation to join on.
     * @return the query builder.
     */
    public abstract fun on(relation: KClass<out Data>): QueryBuilder<Data, R, ID>

    /**
     * Specifies the relation to join on.
     *
     * @param O the relation to join on.
     * @return the query builder.
     * @since 1.12
     */
    public inline fun <reified O : Data> on(): QueryBuilder<Data, R, ID> = on(O::class)

    /**
     * Specifies the relation to join on, with a condition the join adds to the foreign key it resolves. On a left
     * join, the condition decides which related rows join, so a row without a match is kept; on an anti-join, it
     * decides which related rows exclude a row.
     *
     * ```kotlin
     * val petsWithoutRabiesShot = pets
     *     .select()
     *     .antiJoin<Visit>().on<Pet> { Visit_.description eq "rabies shot" }
     *     .resultList
     * ```
     *
     * @param relation the relation to join on.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @since 1.16
     */
    public abstract fun on(
        relation: KClass<out Data>,
        condition: WhereBuilder<Data, R, ID>.() -> PredicateBuilder<out Data, *, *>,
    ): QueryBuilder<Data, R, ID>

    /**
     * Specifies the relation to join on, with a condition the join adds to the foreign key it resolves.
     *
     * @param O the relation to join on.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @since 1.16
     */
    @JvmName("onCondition")
    public inline fun <reified O : Data> on(
        noinline condition: WhereBuilder<Data, R, ID>.() -> PredicateBuilder<out Data, *, *>,
    ): QueryBuilder<Data, R, ID> = on(O::class, condition)

    /**
     * Specifies the foreign key to join on, where the table holds more than one key to the table joined on. A key
     * of the joined table joins the table it references; a key of another table joins that table, and must reference
     * the joined table.
     *
     * ```kotlin
     * orm.entity(Owner::class)
     *     .select()
     *     .semiJoin<Transfer>().on(Transfer_.to)
     *     .resultList
     * ```
     *
     * @param key a foreign key of the joined table, or of the table to join on.
     * @return the query builder.
     * @since 1.16
     */
    public abstract fun on(key: Metamodel<out Data, *>): QueryBuilder<Data, R, ID>

    /**
     * Specifies the foreign key to join on, with a condition the join adds to the key it joins by.
     *
     * @param key a foreign key of the joined table, or of the table to join on.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @since 1.16
     */
    public abstract fun on(
        key: Metamodel<out Data, *>,
        condition: WhereBuilder<Data, R, ID>.() -> PredicateBuilder<out Data, *, *>,
    ): QueryBuilder<Data, R, ID>

    /**
     * Specifies the foreign keys to join on, where neither table references the other and both hold a key to one
     * table: the join matches the two keys with each other.
     *
     * ```kotlin
     * orm.entity(Pet::class)
     *     .select()
     *     .semiJoin<Visit>().on<Pet>()
     *     .semiJoin<Transfer>().on(Transfer_.pet, Visit_.pet)
     *     .resultList
     * ```
     *
     * @param key a foreign key of the joined table.
     * @param anchorKey a foreign key of the table to join on, referencing the table [key] references.
     * @return the query builder.
     * @since 1.16
     */
    public abstract fun on(key: Metamodel<out Data, *>, anchorKey: Metamodel<out Data, *>): QueryBuilder<Data, R, ID>

    /**
     * Specifies the foreign keys to join on, with a condition the join adds to the keys it joins by.
     *
     * @param key a foreign key of the joined table.
     * @param anchorKey a foreign key of the table to join on, referencing the table [key] references.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @since 1.16
     */
    public abstract fun on(
        key: Metamodel<out Data, *>,
        anchorKey: Metamodel<out Data, *>,
        condition: WhereBuilder<Data, R, ID>.() -> PredicateBuilder<out Data, *, *>,
    ): QueryBuilder<Data, R, ID>
}
