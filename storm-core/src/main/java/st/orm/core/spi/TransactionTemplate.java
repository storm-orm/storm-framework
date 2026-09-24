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

import java.util.Optional;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;
import st.orm.TransactionIsolation;
import st.orm.TransactionPropagation;

/**
 * The transaction template allows a transaction to be opened for the template's configuration and completed once the
 * transactional work has finished.
 *
 * <p>Transactions are opened and completed in two separate steps rather than around a callback. This enables lazy
 * binding: a {@link TransactionScope} is materialized by the first template that executes inside it, at which point
 * the transactional block is already running.</p>
 *
 * @since 1.5
 */
public interface TransactionTemplate {

    /**
     * Set the propagation, such as REQUIRED, REQUIRES_NEW, NESTED.
     *
     * @param propagation the propagation.
     * @return this transaction template instance.
     */
    TransactionTemplate propagation(TransactionPropagation propagation);

    /**
     * Set the isolation level, such as REPEATABLE_READ, READ_COMMITTED, READ_UNCOMMITTED and SERIALIZABLE.
     * When not set, the provider's default applies.
     *
     * @param isolation the isolation level.
     * @return this transaction template instance.
     */
    TransactionTemplate isolation(TransactionIsolation isolation);

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
     * <p>{@code 0} means no time is left: a scope whose deadline passed before its first statement opens its
     * transaction with the zero seconds it has left, and that statement must be refused. A provider that bridges to
     * another transaction system keeps this deadline itself, since such a system may read a timeout of zero as no
     * timeout or as its own default.</p>
     *
     * @param timeoutSeconds transaction timeout in seconds; {@code 0} means no time is left.
     * @return this transaction template instance.
     */
    TransactionTemplate timeout(int timeoutSeconds);

    /**
     * Opens a transaction based on the current configuration of this transaction template.
     *
     * <p>When {@code existing} is {@code null}, a new transaction context is created. When an existing context is
     * given, the opened transaction joins, nests in, or suspends the existing transaction according to the configured
     * propagation; the returned handle exposes the same context instance.</p>
     *
     * <p>The physical transaction may start lazily, when the first connection is bound to the context.</p>
     *
     * @param existing the transaction context to join, or {@code null} to create a new context.
     * @param suspendMode whether the transaction is created to be used in suspend mode.
     * @return a handle used to observe and complete the opened transaction.
     * @throws PersistenceException if the transaction subsystem raised an issue, such as an invalid configuration.
     * @since 1.13
     */
    TransactionHandle open(@Nullable TransactionContext existing, boolean suspendMode) throws PersistenceException;

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
     * Handle for a transaction opened via {@link #open}.
     *
     * @since 1.13
     */
    interface TransactionHandle {

        /**
         * Returns the transaction context of the opened transaction.
         *
         * @return the transaction context; never {@code null}.
         */
        TransactionContext context();

        /**
         * Returns the status of the opened transaction.
         *
         * @return the transaction status; never {@code null}.
         */
        TransactionStatus status();

        /**
         * Returns whether this handle joined a physical transaction that an external transaction manager had
         * already opened, rather than opening one of its own.
         *
         * <p>The distinction decides who completes the transaction, and therefore where completion callbacks
         * belong: a handle that opened the transaction fires them when its block ends, while a handle that
         * joined one defers them to the manager that will commit it, via {@link #deferCompletion(Consumer)}.
         * Providers that always open their own transaction, such as the JDBC one, keep the default.</p>
         *
         * @return {@code true} if the physical transaction was already open and this handle joined it.
         * @since 1.13
         */
        default boolean joinedExistingTransaction() {
            return false;
        }

        /**
         * Registers a completion callback with the physical transaction this handle joined.
         *
         * <p>Called only for handles that {@link #joinedExistingTransaction() joined an existing transaction}:
         * the external manager owns the commit, so the callback must fire on that manager's outcome rather
         * than at the end of the block. The provider registers it with its transaction manager, under Spring
         * as a transaction synchronization. Providers that never join keep the default.</p>
         *
         * @param callback receives {@code true} when the transaction commits and {@code false} when it rolls
         * back.
         * @since 1.13
         */
        default void deferCompletion(Consumer<Boolean> callback) {
            throw new UnsupportedOperationException("This transaction handle does not defer completion.");
        }

        /**
         * Completes the opened transaction.
         *
         * <p>The transaction rolls back when {@code rollback} is {@code true} or the transaction has been marked
         * rollback-only, and commits otherwise. Must be invoked exactly once.</p>
         *
         * @param rollback whether the transactional work failed and the transaction must be rolled back.
         * @throws PersistenceException if the transaction subsystem raised an issue while completing, or to signal an
         * unexpected rollback or timeout.
         */
        void complete(boolean rollback) throws PersistenceException;
    }
}
