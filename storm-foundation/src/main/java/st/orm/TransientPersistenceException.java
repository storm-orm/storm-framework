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
 * Thrown when a failure stems from concurrent activity, such as a deadlock, a serialization failure or an optimistic
 * lock conflict. Running the whole transaction again may succeed.
 *
 * <p>The database failures of this type are the ones on which the database gave up the transaction's work because
 * another transaction conflicted with it; the dialect classifies them from the error the database reports. A failure
 * after which the outcome of the transaction is unknown, such as a lost connection or a failed commit, is not of this
 * type, and neither is a lock wait timeout, which some databases answer by rolling back only the statement.</p>
 *
 * <p>Rerun the transaction, never the statement alone: the transaction the failure occurred in cannot commit the work
 * it did. A rerun repeats every side effect of the transactional block, so an effect outside the database belongs in
 * a commit callback.</p>
 *
 * @since 1.16
 */
public class TransientPersistenceException extends PersistenceException {

    /**
     * Constructs a new {@code TransientPersistenceException} exception with {@code null} as its detail message.
     */
    public TransientPersistenceException() {
        super();
    }

    /**
     * Constructs a new {@code TransientPersistenceException} exception with the specified detail message.
     * @param message the detail message.
     */
    public TransientPersistenceException(String message) {
        super(message);
    }

    /**
     * Constructs a new {@code TransientPersistenceException} exception with the specified detail message and cause.
     * @param message the detail message.
     * @param cause the cause.
     */
    public TransientPersistenceException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Constructs a new {@code TransientPersistenceException} exception with the specified cause.
     * @param cause the cause.
     */
    public TransientPersistenceException(Throwable cause) {
        super(cause);
    }
}
