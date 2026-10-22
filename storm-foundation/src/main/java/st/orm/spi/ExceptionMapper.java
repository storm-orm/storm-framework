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
package st.orm.spi;

import st.orm.PersistenceException;

/**
 * Maps failures raised during query execution to the runtime exception thrown to the caller.
 *
 * <p>The default mapper wraps failures in {@link PersistenceException}. Integrations may translate to platform
 * exception hierarchies instead, such as Spring's {@code DataAccessException}.</p>
 *
 * <p>Exception mappers are configured per ORM template via the template builder; they are deliberately not
 * discovered through the {@code ServiceLoader} mechanism. The framework enriches the failure with SQL diagnostics
 * (as a suppressed exception on the cause) before invoking the mapper, so the mapper only decides the exception
 * type that is thrown.</p>
 *
 * <p>The failures Storm's own logic detects after a statement ran pass through the mapper as well: an optimistic
 * lock conflict, a single result that is missing or not unique, a write that affected no row. Their context names
 * the operation and, for a write, the entity type, but no statement. A mapper that translates to a platform
 * hierarchy translates them too, so a caller sees one exception family whichever side detected the failure.</p>
 *
 * @see ExceptionContext
 * @since 1.13
 */
@FunctionalInterface
public interface ExceptionMapper {

    /**
     * Maps the given failure to the runtime exception that is thrown to the caller.
     *
     * @param cause the failure; typically a {@code SQLException} or {@link PersistenceException}. The framework has
     *              already attached SQL diagnostics as a suppressed exception where available.
     * @param context the execution context of the failure; never {@code null}.
     * @return the exception to throw; never {@code null}.
     */
    RuntimeException map(Throwable cause, ExceptionContext context);

    /**
     * Returns the default exception mapper.
     *
     * <p>The default mapper passes {@link PersistenceException} instances through unchanged and wraps any other
     * failure in a new {@link PersistenceException}.</p>
     *
     * @return the default exception mapper.
     */
    static ExceptionMapper defaultMapper() {
        return (cause, context) -> cause instanceof PersistenceException persistenceException
                ? persistenceException
                : new PersistenceException(cause);
    }
}
