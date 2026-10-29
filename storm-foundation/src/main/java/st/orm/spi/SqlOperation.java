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

/**
 * Represents the SQL mode.
 *
 * <p>The SQL mode provides context for the {@code SqlTemplate} processor, enabling validation and inference by the
 * SQL template logic. Each mode indicates the type of SQL operation being performed, which allows the framework to
 * adapt behavior accordingly.</p>
 */
public enum SqlOperation {

    /**
     * Represents a SELECT operation.
     *
     * <p>This mode is used for queries that retrieve data from the database without modifying its state. The SQL
     * template processor uses this mode to validate that the query conforms to the structure of a SELECT
     * statement.</p>
     */
    SELECT,

    /**
     * Represents an INSERT operation.
     *
     * <p>This mode is used for queries that add new records to the database. The SQL template processor validates
     * that parameters align with the fields being inserted.</p>
     */
    INSERT,

    /**
     * Represents an UPDATE operation.
     *
     * <p>This mode is used for queries that modify existing records in the database. The SQL template processor
     * ensures that the query includes a WHERE clause (if required) to avoid unintentional updates across all
     * rows.</p>
     */
    UPDATE,

    /**
     * Represents a DELETE operation.
     *
     * <p>This mode is used for queries that remove records from the database. The SQL template processor validates
     * the presence of a WHERE clause (if required) to prevent unintended deletion of all rows.</p>
     */
    DELETE,

    /**
     * Represents an undefined operation.
     *
     * <p>This mode is used when the type of SQL operation cannot be determined. The SQL template processor may
     * apply relaxed validation or inference rules when this mode is specified.</p>
     */
    UNDEFINED
}
