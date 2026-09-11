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
package st.orm.core.template;

import org.jspecify.annotations.Nullable;
import st.orm.Data;

/**
 * A builder for constructing the predicates of the WHERE clause of the query.
 *
 * @param <T>  the type of the table being queried.
 * @param <R>  the type of the result.
 * @param <ID> the type of the primary key.
 */
public interface PredicateBuilder<T extends Data, R extends @Nullable Object, ID extends @Nullable Object> {

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
    PredicateBuilder<T, R, ID> and(TemplateString template);

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
    PredicateBuilder<T, R, ID> or(TemplateString template);
}
