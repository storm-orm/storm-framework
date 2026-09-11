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
}
