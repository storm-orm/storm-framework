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
package st.orm.core.spi;

import static java.util.Optional.ofNullable;

import jakarta.annotation.Nonnull;
import java.util.Optional;
import st.orm.PersistenceException;

/**
 * The transaction template is a functional interface that allows callers to let logic be executed in the scope of a
 * new transaction.
 *
 * @since 1.5
 */
public interface TransactionTemplate {

    /**
     * Set the propagation, such as DEFAULT, REQUIRED, REQUIRES_NEW, NESTED.
     *
     * @param propagation name of the propagation.
     * @return this transaction template instance.
     * @throws IllegalArgumentException if the supplied value is invalid.
     */
    TransactionTemplate propagation(String propagation);

    /**
     * Set the isolation level, such as DEFAULT, REPEATABLE_READ, READ_COMMITTED, READ_UNCOMMITTED and SERIALIZABLE.
     *
     * @param isolation name of the isolation level.
     * @return this transaction template instance.
     * @throws IllegalArgumentException if the supplied value is invalid.
     */
    TransactionTemplate isolation(int isolation);

    /**
     * Set whether to optimize as read-only transaction. Default is "false".
     *
     * <p>The read-only flag applies to any transaction context, whether backed by an actual resource transaction
     * propagation {@code REQUIRED} and {@code REQUIRES_NEW} or operating non-transactionally at the resource level
     * {@code SUPPORTS}. In the latter case, the flag will only apply to managed resources within the application,
     * such as a Hibernate {@code Session}.</p>
     *
     * <p>This just serves as a hint for the actual transaction subsystem; it will <i>not necessarily</i> cause failure
     * of write access attempts. A transaction manager which cannot interpret the read-only hint will <i>not</i>
     * throw an exception when asked for a read-only transaction.
     *
     * @return this transaction template instance.
     */
    TransactionTemplate readOnly(boolean readOnly);

    /**
     * Set the transaction timeout to apply, as number of seconds.
     *
     * <p>Exclusively designed for use with propagation {@code REQUIRED} and {@code REQUIRES_NEW} since it only applies
     * to newly started transactions.</p>
     *
     * <p>Note that a transaction manager that does not support timeouts will throw an
     * {@code IllegalArgumentException}.</p>
     *
     * @param timeoutSeconds transaction timeout in seconds.
     * @return this transaction template instance.
     */
    TransactionTemplate timeout(int timeoutSeconds);

    /**
     * Creates a new transaction context based on the current configuration of this transaction template.
     *
     * @param suspendMode whether the transaction is created to be used in suspend mode.
     * @throws PersistenceException if the transaction subsystem raised an issue, such as an invalid configuration.
     */
    TransactionContext newContext(boolean suspendMode) throws PersistenceException;

    /**
     * Returns the current transaction context if any.
     *
     * @return the current transaction context if any.
     */
    default Optional<TransactionContext> currentContext() {
        return ofNullable(contextHolder().get());
    }

    /**
     * Returns the thread local that holds the current transaction context.
     *
     * @return thread local that holds the current transaction context.
     */
    ThreadLocal<TransactionContext> contextHolder();

    /**
     * Executes the specified action in the scope of a transaction. Exceptions raised by the action will be relayed
     * and will mark the transaction as rollback only.
     *
     * @param action action to preform in the scope of a transaction.
     * @return result object.
     * @param <R> result object type.
     * @throws PersistenceException if the transaction subsystem raised an issue.
     */
    <R> R execute(@Nonnull TransactionCallback<R> action, @Nonnull TransactionContext context) throws PersistenceException;
}
