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
import st.orm.SqlFunction;
import st.orm.SqlTemplateException;

/**
 * Paths named in the query that encloses a subquery, made by {@code Metamodel.outer}. Such a path is
 * the other side of a comparison of two columns, or a column of a template, and both strip it before they resolve its
 * columns in the enclosing query. Column resolution in a model refuses a path that still carries the marking, so every
 * other use of it fails where its columns are resolved, rather than resolving to a column of the subquery.
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
     * Refuses a path named in the enclosing query where its columns are resolved as columns of the query itself.
     *
     * @param path the path to resolve.
     * @throws SqlTemplateException if the path names a column of an enclosing query.
     */
    static void refuse(Metamodel<?, ?> path) throws SqlTemplateException {
        if (Metamodel.isOuter(path)) {
            throw new SqlTemplateException(message(path));
        }
    }

    /**
     * Returns the message that refuses a path named in the enclosing query where a path of the query itself is
     * expected.
     */
    static String message(Metamodel<?, ?> path) {
        return "%1$s is a column of the enclosing query; only a comparison of two columns takes it: where(<path>, <operator>, %1$s)."
                .formatted(path);
    }
}
