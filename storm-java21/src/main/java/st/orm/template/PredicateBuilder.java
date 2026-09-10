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

import st.orm.Data;

/**
 * Represents a composable predicate for the WHERE clause of a query, supporting {@code AND} and {@code OR} composition.
 *
 * <p>{@code PredicateBuilder} instances are returned by the methods on {@link WhereBuilder} and can be combined
 * using {@link #and(PredicateBuilder)} and {@link #or(PredicateBuilder)} to build compound conditions. Each
 * combinator returns a new {@code PredicateBuilder} that represents the combined expression.</p>
 *
 * <p>Predicates combine within the query root. A join widens the root, after which the {@link WhereBuilder}
 * produces predicates that may reference any entity in the query; a path on an entity outside the query fails
 * when the query is built.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * List<User> users = userRepository
 *         .select()
 *         .where(predicate -> predicate
 *             .where(User_.email, LIKE, "%@example.com")
 *             .and(predicate.where(User_.email, IS_NOT_NULL))
 *             .or(predicate.where(User_.role, EQUALS, "admin")))
 *         .getResultList();
 * }</pre>
 *
 * @param <T>  the type of the table being queried.
 * @param <R>  the type of the result.
 * @param <ID> the type of the primary key.
 * @see WhereBuilder
 * @see QueryBuilder
 */
public interface PredicateBuilder<T extends Data, R, ID> {

    /**
     * Adds a predicate to the WHERE clause using an AND condition.
     *
     * <p>This method combines the specified predicate with existing predicates using an AND operation, ensuring
     * that all added conditions must be true. The predicate inherits the query root: a join widens the root,
     * admitting predicates that reference any entity in the query.</p>
     *
     * @param predicate the predicate to add.
     * @return the predicate builder.
     */
    PredicateBuilder<T, R, ID> and(PredicateBuilder<? extends T, ?, ?> predicate);

    /**
     * Adds a predicate to the WHERE clause using an AND condition.
     *
     * <p>This method combines the specified predicate with existing predicates using an AND operation, ensuring
     * that all added conditions must be true.</p>
     *
     * @param template the template string representing the predicate to add.
     * @return the predicate builder.
     */
    PredicateBuilder<T, R, ID> and(StringTemplate template);

    /**
     * Adds a predicate to the WHERE clause using an OR condition.
     *
     * <p>This method combines the specified predicate with existing predicates using an OR operation, allowing any
     * of the added conditions to be true. The predicate inherits the query root: a join widens the root,
     * admitting predicates that reference any entity in the query.</p>
     *
     * @param predicate the predicate to add.
     * @return the predicate builder.
     */
    PredicateBuilder<T, R, ID> or(PredicateBuilder<? extends T, ?, ?> predicate);

    /**
     * Adds a predicate to the WHERE clause using an OR condition.
     *
     * <p>This method combines the specified predicate with existing predicates using an OR operation, ensuring
     * that all added conditions must be true.</p>
     *
     * @param template the template string representing the predicate to add.
     * @return the predicate builder.
     */
    PredicateBuilder<T, R, ID> or(StringTemplate template);
}
