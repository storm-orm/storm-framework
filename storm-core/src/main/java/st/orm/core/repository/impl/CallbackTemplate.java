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
package st.orm.core.repository.impl;

import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;
import st.orm.core.template.ORMTemplate;

/**
 * Holds the template running the entity callback on the current thread.
 *
 * <p>A callback receives the entity and nothing else, so one that performs database work of its own has to reach a
 * template. Which template that is cannot be left to the callback: it has to be the one the operation runs on, since
 * that is what carries the connection and with it the transaction, and a callback registered on several templates
 * cannot name one of them at construction. The template is therefore bound here for the duration of the dispatch and
 * read back through {@link ORMTemplate#current()}.</p>
 *
 * <p>The binding is removed when the dispatch returns, so a pooled thread retains nothing between operations.
 * Callbacks never fire recursively, so a binding never covers another.</p>
 *
 * @since 1.14
 */
public final class CallbackTemplate {

    private static final ThreadLocal<ORMTemplate> CURRENT = new ThreadLocal<>();

    private CallbackTemplate() {
    }

    /**
     * Binds the given template for the callbacks about to be dispatched on this thread.
     *
     * @param ormTemplate the template the operation runs on.
     */
    static void bind(ORMTemplate ormTemplate) {
        CURRENT.set(ormTemplate);
    }

    /** Removes the binding once the dispatch has returned. */
    static void unbind() {
        CURRENT.remove();
    }

    /**
     * Returns the template running the entity callback on the current thread.
     *
     * @return the template the operation runs on; never {@code null}.
     * @throws PersistenceException if no entity callback is executing on this thread.
     */
    public static ORMTemplate current() {
        @Nullable ORMTemplate ormTemplate = CURRENT.get();
        if (ormTemplate == null) {
            throw new PersistenceException("""
                    No template is running here. ORMTemplate.current() returns the template of the operation that \
                    fired an entity callback, so it is available while a callback executes and nowhere else. \
                    Elsewhere, use the template the application is configured with.""");
        }
        return ormTemplate;
    }
}
