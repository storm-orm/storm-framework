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

import java.util.function.UnaryOperator;
import st.orm.Metamodel;
import st.orm.SqlFunction;
import st.orm.SqlFunction.Call;

/**
 * Renders the {@link SqlFunction} calls a path may be wrapped in. Column resolution works on the column the calls are
 * applied to, and the rendered column, and the value it is compared against, are wrapped in the calls afterwards.
 *
 * @since 1.16
 */
final class FunctionCalls {

    private FunctionCalls() {
    }

    /**
     * Returns the column the path's function calls are applied to, or the path itself if it is no call.
     */
    static Metamodel<?, ?> column(Metamodel<?, ?> path) {
        return path instanceof Call<?, ?> call ? column(call.argument()) : path;
    }

    /**
     * Wraps the rendered column, or the rendered value compared against it, in the path's function calls, the
     * innermost first. A path that is no call returns the SQL as it is.
     */
    static String render(Metamodel<?, ?> path, String sql) {
        return path instanceof Call<?, ?> call
                ? call.function().name() + "(" + render(call.argument(), sql) + ")"
                : sql;
    }

    /**
     * Maps the column the path's function calls are applied to and wraps the result in the same calls.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static Metamodel<?, ?> map(Metamodel<?, ?> path, UnaryOperator<Metamodel<?, ?>> mapper) {
        if (path instanceof Call<?, ?> call) {
            return new Call(call.function(), map(call.argument(), mapper));
        }
        return mapper.apply(path);
    }
}
