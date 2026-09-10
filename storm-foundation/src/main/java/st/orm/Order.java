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
package st.orm;

import static java.util.Objects.requireNonNull;

/**
 * A sort order for one metamodel field.
 *
 * <p>{@link Pageable} and {@link Scrollable} both order their results by a list of these, so a request built for
 * one is expressed in the same vocabulary as the other.</p>
 *
 * @param field the metamodel field to sort by.
 * @param descending {@code true} for descending order, {@code false} for ascending.
 * @since 1.14
 */
public record Order(Metamodel<?, ?> field, boolean descending) {

    public Order {
        requireNonNull(field, "field must not be null.");
    }

    /**
     * Returns an ascending order for the given field.
     *
     * @param field the metamodel field to sort by.
     * @return an ascending order.
     */
    public static Order asc(Metamodel<?, ?> field) {
        return new Order(field, false);
    }

    /**
     * Returns a descending order for the given field.
     *
     * @param field the metamodel field to sort by.
     * @return a descending order.
     */
    public static Order desc(Metamodel<?, ?> field) {
        return new Order(field, true);
    }
}
