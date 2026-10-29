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

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.spi.SqlOperation;

/**
 * A built-in repository call a statement serves, which the advice on a repeated statement names and whose list form
 * it points at: a repeated {@code existsById} wants {@code findAllById} over the keys and a look at which came back,
 * not a grouped query. A built-in read is built like any query, so the repository tags it around the call, on the
 * thread that makes it; a write is told by the query the repository marks as managed. A statement of the query
 * builder, a template or a custom repository method serves no call, and its repeat takes the remedy shaped like the
 * query.
 *
 * @since 1.16
 */
public enum RepositoryCall {

    EXISTS_BY_ID("existsById", "findAllById"),
    EXISTS_BY_REF("existsByRef", "findAllByRef"),
    FIND_BY_ID("findById", "findAllById"),
    FIND_BY_REF("findByRef", "findAllByRef"),
    GET_BY_ID("getById", "findAllById"),
    GET_BY_REF("getByRef", "findAllByRef"),
    INSERT("insert", null),
    UPSERT("upsert", null),
    UPDATE("update", null),
    REMOVE("remove", null);

    /** The call the repository tags on its thread, around a built-in read. */
    private static final ThreadLocal<RepositoryCall> CURRENT = new ThreadLocal<>();

    /** The method as the advice names it. */
    final String method;
    /** The list form of the call, over the keys of every execution, or {@code null} for a write. */
    final @Nullable String listForm;

    RepositoryCall(String method, @Nullable String listForm) {
        this.method = method;
        this.listForm = listForm;
    }

    /** Returns whether the call writes rows. */
    boolean writes() {
        return listForm == null;
    }

    /** Returns whether the call asks whether rows exist, so its list form reads the ones that do. */
    boolean exists() {
        return this == EXISTS_BY_ID || this == EXISTS_BY_REF;
    }

    /**
     * Runs a built-in read with its statements tagged as serving the call. The outermost call wins where one runs
     * another, as {@code existsById} runs {@code countById}, since the outermost is the one the application made.
     *
     * @param call the call the repository makes.
     * @param body the call.
     * @return what the call returns.
     * @param <T> the type the call returns.
     */
    public static <T> T in(RepositoryCall call, Supplier<T> body) {
        if (CURRENT.get() != null) {
            return body.get();
        }
        CURRENT.set(call);
        try {
            return body.get();
        } finally {
            CURRENT.remove();
        }
    }

    /**
     * Returns the call a statement serves: the read tagged on the thread, where the statement is a read, or the
     * write a query the repository marks as managed makes; {@code null} where the statement serves no built-in call.
     */
    static @Nullable RepositoryCall of(SqlOperation operation, boolean upsert, boolean managed) {
        var call = CURRENT.get();
        if (call != null) {
            return operation == SqlOperation.SELECT ? call : null;
        }
        if (!managed) {
            return null;
        }
        if (upsert) {
            return UPSERT;
        }
        return switch (operation) {
            case INSERT -> INSERT;
            case UPDATE -> UPDATE;
            case DELETE -> REMOVE;
            default -> null;
        };
    }
}
