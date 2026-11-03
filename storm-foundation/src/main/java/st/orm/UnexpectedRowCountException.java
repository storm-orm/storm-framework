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

/**
 * Thrown when a write affects a different number of rows than it expects: an insert, update or remove of an entity
 * that affects no row, or a batch whose affected row count differs from its size.
 *
 * <p>Storm detects this after the statement ran, so it passes through the template's exception mapper like a
 * failure the database reported. An update of a versioned entity that affects no row is an
 * {@link OptimisticLockException} instead.</p>
 *
 * @since 1.16
 */
public class UnexpectedRowCountException extends PersistenceException {

    private final int expectedRowCount;
    private final int actualRowCount;

    /**
     * Constructs a new exception with the specified detail message and row counts.
     *
     * @param message the detail message.
     * @param expectedRowCount the number of rows the write expected to affect.
     * @param actualRowCount the number of rows the write affected.
     */
    public UnexpectedRowCountException(String message, int expectedRowCount, int actualRowCount) {
        super(message);
        this.expectedRowCount = expectedRowCount;
        this.actualRowCount = actualRowCount;
    }

    /**
     * Returns the number of rows the write expected to affect.
     *
     * @return the expected row count.
     */
    public int getExpectedRowCount() {
        return expectedRowCount;
    }

    /**
     * Returns the number of rows the write affected, as the driver reported it. For a batch, this is the sum of the
     * counts the driver reported per statement.
     *
     * @return the actual row count.
     */
    public int getActualRowCount() {
        return actualRowCount;
    }
}
