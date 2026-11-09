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

import java.util.Optional;
import st.orm.Data;

/**
 * Describes the execution context in which a failure occurred.
 *
 * <p>An instance of this interface is passed to the {@link ExceptionMapper} configured on the ORM template, allowing
 * the mapper to base its translation on the SQL operation, the statement text and the affected data type.</p>
 *
 * @see ExceptionMapper
 * @since 1.13
 */
public interface ExceptionContext {

    /**
     * Classifies the kind of SQL statement that failed.
     *
     * @return the SQL operation; {@link SqlOperation#UNDEFINED} when the operation is unknown.
     */
    SqlOperation operation();

    /**
     * Returns the SQL statement that failed, with all parameters replaced by placeholders.
     *
     * @return the SQL statement, or empty when no statement is associated with the failure.
     */
    Optional<String> statement();

    /**
     * Returns the entity or projection type primarily affected by the failed operation.
     *
     * @return the affected data type, or empty when the operation is not associated with a specific type.
     */
    Optional<Class<? extends Data>> dataType();

    /**
     * Returns a description of the transaction in whose scope the failure occurred.
     *
     * @return the transaction description, or empty when no transaction is active or the transaction subsystem does
     * not provide a description.
     */
    Optional<String> transactionDescription();
}
