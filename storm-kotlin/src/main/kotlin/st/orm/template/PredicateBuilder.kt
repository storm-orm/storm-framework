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

/**
 * Represents a composable predicate for the WHERE clause of a query, supporting `AND` and `OR` composition.
 *
 * `PredicateBuilder` instances are returned by the methods on [WhereBuilder] and by the infix operators such as
 * `eq` and `like`. They combine with `and` and `or`; each combinator returns a new `PredicateBuilder` that
 * represents the combined expression.
 *
 * Inside a [WhereBuilder] scope, the scope's `and`/`or` combinators inherit the query root: a narrow scope
 * combines predicates within the root table's entity graph, and a join widens the root so the same syntax
 * combines predicates across all entities in the query. Outside a scope, the top-level `and`/`or` extensions
 * combine predicates that share the same root.
 *
 * ## Example
 * ```kotlin
 * val users = userRepository
 *     .select()
 *     .whereBuilder {
 *         (User_.email like "%@example.com")
 *             .and(User_.email.isNotNull())
 *             .or(User_.role eq "admin")
 *     }
 *     .resultList
 * ```
 *
 * @param T the type of the table being queried.
 * @param R the type of the result.
 * @param ID the type of the primary key.
 * @see WhereBuilder
 * @see QueryBuilder
 */
public interface PredicateBuilder<T : Data, R, ID> {
    /**
     * Adds a predicate to the WHERE clause using an AND condition.
     *
     * This method combines the specified predicate with existing predicates using an AND operation, ensuring
     * that all added conditions must be true.
     *
     * @param template the predicate builder to add.
     * @return the predicate builder.
     */
    public infix fun and(template: TemplateBuilder): PredicateBuilder<T, R, ID> = and(template.build())

    /**
     * Adds a predicate to the WHERE clause using an AND condition.
     *
     * This method combines the specified predicate with existing predicates using an AND operation, ensuring
     * that all added conditions must be true.
     *
     * @param template the predicate template to add.
     * @return the predicate builder.
     */
    public infix fun and(template: TemplateString): PredicateBuilder<T, R, ID>

    /**
     * Adds a predicate to the WHERE clause using an OR condition.
     *
     * This method combines the specified predicate with existing predicates using an OR operation, ensuring
     * that all added conditions must be true.
     *
     * @param template the predicate builder to add.
     * @return the predicate builder.
     */
    public infix fun or(template: TemplateBuilder): PredicateBuilder<T, R, ID> = or(template.build())

    /**
     * Adds a predicate to the WHERE clause using an OR condition.
     *
     * This method combines the specified predicate with existing predicates using an OR operation, ensuring
     * that all added conditions must be true.
     *
     * @param template the predicate template to add.
     * @return the predicate builder.
     */
    public infix fun or(template: TemplateString): PredicateBuilder<T, R, ID>
}
