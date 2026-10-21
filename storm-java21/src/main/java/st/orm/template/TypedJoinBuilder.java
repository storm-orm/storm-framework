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
package st.orm.template;

import java.util.function.Function;
import st.orm.Data;

/**
 * A builder for specifying the ON condition of a JOIN clause using a type-safe entity relation or a custom expression.
 *
 * <p>{@code TypedJoinBuilder} extends {@link JoinBuilder} and is returned by the join methods on
 * {@link QueryBuilder} that accept an entity class. In addition to the template-based
 * {@link JoinBuilder#on(StringTemplate) on(StringTemplate)} method, it provides an
 * {@link #on(Class) on(Class)} method that resolves the join condition automatically based on the foreign key
 * relationships defined in the entity graph.</p>
 *
 * <h2>Example: Automatic join resolution</h2>
 * <pre>{@code
 * List<User> users = userRepository
 *         .select()
 *         .innerJoin(Order.class).on(User.class)
 *         .getResultList();
 * }</pre>
 *
 * @param <T>  the type of the table being queried.
 * @param <R>  the type of the result.
 * @param <ID> the type of the primary key.
 * @see JoinBuilder
 * @see QueryBuilder
 */
public interface TypedJoinBuilder<T extends Data, R, ID> extends JoinBuilder<T, R, ID> {

    /**
     * Specifies the relation to join on.
     *
     * @param relation the relation to join on.
     * @return the query builder.
     */
    QueryBuilder<Data, R, ID> on(Class<? extends Data> relation);

    /**
     * Specifies the relation to join on, with a condition the join adds to the foreign key it resolves. On a left
     * join, the condition decides which related rows join, so a row without a match is kept; on an anti-join, it
     * decides which related rows exclude a row.
     *
     * <pre>{@code
     * List<Pet> petsWithoutRabiesShot = pets
     *         .select()
     *         .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot"))
     *         .getResultList();
     * }</pre>
     *
     * @param relation the relation to join on.
     * @param condition the condition on the joined rows.
     * @return the query builder.
     * @since 1.16
     */
    QueryBuilder<Data, R, ID> on(Class<? extends Data> relation,
                                 Function<WhereBuilder<Data, R, ID>, PredicateBuilder<Data, ?, ?>> condition);
}
