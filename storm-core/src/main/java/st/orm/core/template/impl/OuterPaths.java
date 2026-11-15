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
package st.orm.core.template.impl;

import st.orm.Metamodel;
import st.orm.PersistenceException;
import st.orm.SqlFunction;

/**
 * Paths named in the query that encloses a subquery, made by {@code Metamodel.outer}. Such a path is
 * the other side of a comparison of two columns, or a column of a template; every other element of a query takes the
 * query's own paths and refuses it where the element is built.
 *
 * @since 1.16
 */
final class OuterPaths {

    private OuterPaths() {
    }

    /**
     * Returns the path as the enclosing query holds it: without the marking that names it there, and with the
     * function calls applied to it in place.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Metamodel<?, ?> strip(Metamodel<?, ?> path) {
        return switch (path) {
            case Metamodel.Outer<?, ?>(var column) -> strip(column);
            case SqlFunction.Call<?, ?> call -> new SqlFunction.Call(call.function(), strip(call.argument()));
            default -> path;
        };
    }

    /**
     * Refuses a path named in the enclosing query where an element takes a path of the query itself.
     *
     * @param path the path the element was given.
     * @param use what the element does with its paths, completing "A query ... its own columns."
     * @throws PersistenceException if the path names a column of an enclosing query.
     */
    static void refuse(Metamodel<?, ?> path, String use) {
        if (Metamodel.isOuter(path)) {
            throw new PersistenceException("%1$s names a column of the enclosing query, which a subquery compares a column of its own with: where(<path>, <operator>, %1$s). A query %2$s its own columns."
                    .formatted(path, use));
        }
    }
}
