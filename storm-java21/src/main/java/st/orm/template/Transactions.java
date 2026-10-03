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
package st.orm.template;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.atomic.AtomicReference;
import org.jspecify.annotations.Nullable;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.core.spi.TransactionRunner;

/**
 * Programmatic transactions for Java.
 *
 * <p>The transaction binds to the first ORM template that executes inside the block: opening the block only
 * records the requested options, and the template's transaction provider opens the actual transaction on first
 * use. A block that never touches a template completes as a no-op. The block commits when it completes
 * normally and rolls back when it throws; checked exceptions propagate to the caller unchanged.</p>
 *
 * {@snippet lang = java:
 * import static st.orm.template.Transactions.transaction;
 * import static st.orm.TransactionPropagation.REQUIRES_NEW;
 *
 * var user = transaction(tx -> users.insertAndFetch(user));
 *
 * transaction(REQUIRES_NEW, tx -> {
 *     tx.onCommit(() -> log.info("audit committed"));
 *     return audit.insertAndFetch(entry);
 * });
 * }
 *
 * <p>The transaction subsystem is provider-driven: standalone templates run on Storm's JDBC transactions, and
 * templates composed with an integration's providers (such as Spring's) run through that platform's
 * transaction manager — the calling code is identical.</p>
 *
 * <p>All entry points are blocking and virtual-thread friendly: the block parks on I/O rather than pinning
 * carrier threads.</p>
 *
 * ## Propagation behavior matrix
 *
 * <table border="1">
 *   <caption>Propagation behavior</caption>
 *   <tr><th>Propagation</th><th>Inner commit</th><th>Inner rollback</th><th>Outer commit</th><th>Outer rollback</th></tr>
 *   <tr><td>{@code REQUIRED}</td><td>Joins outer tx — no actual commit until outer ends</td>
 *       <td>Marks whole tx rollback-only; everything rolls back at end</td>
 *       <td>Commits entire tx (all work)</td><td>Rolls back entire tx (all work)</td></tr>
 *   <tr><td>{@code REQUIRES_NEW}</td><td>Commits only the new (inner) tx</td>
 *       <td>Rolls back only the inner tx; outer stays active</td>
 *       <td>Commits the outer tx (inner work stays committed)</td>
 *       <td>Rolls back the outer tx; inner-committed work remains</td></tr>
 *   <tr><td>{@code NESTED}</td><td>Releases the JDBC savepoint — inner changes become visible to the outer
 *       transaction</td>
 *       <td>Rolls back to savepoint — undoes just inner work, outer stays open</td>
 *       <td>Commits entire tx (savepoints dropped, all work kept)</td>
 *       <td>Rolls back entire tx (including inner work, regardless of savepoint)</td></tr>
 * </table>
 *
 * @since 1.13
 */
public final class Transactions {

    /**
     * The baseline defaults: {@link TransactionPropagation#REQUIRED}, provider-default isolation and timeout,
     * read-write.
     */
    private static final TransactionOptions BASELINE =
            new TransactionOptions(TransactionPropagation.REQUIRED, null, null, false);

    private static final AtomicReference<TransactionOptions> GLOBAL = new AtomicReference<>(BASELINE);

    private static final ThreadLocal<TransactionOptions> LOCAL = new ThreadLocal<>();

    private Transactions() {
    }

    /**
     * Executes the given block within a database transaction with the surrounding default options.
     *
     * @param block the transactional logic to execute.
     * @return the result of the block.
     * @param <R> the result type.
     * @param <E> the checked exception type thrown by the block, if any.
     * @throws st.orm.PersistenceException if transaction execution fails.
     */
    public static <R extends @Nullable Object, E extends Exception> R transaction(TransactionBlock<R, E> block) throws E {
        return transaction(TransactionOptions.defaults(), block);
    }

    /**
     * Executes the given block within a database transaction with the given propagation.
     *
     * @param propagation how the block relates to an already active transaction.
     * @param block the transactional logic to execute.
     * @return the result of the block.
     * @param <R> the result type.
     * @param <E> the checked exception type thrown by the block, if any.
     * @throws st.orm.PersistenceException if transaction execution fails.
     */
    public static <R extends @Nullable Object, E extends Exception> R transaction(TransactionPropagation propagation,
                                                         TransactionBlock<R, E> block) throws E {
        return transaction(TransactionOptions.defaults().withPropagation(propagation), block);
    }

    /**
     * Executes the given block within a database transaction with the given options. Options left {@code null}
     * are inherited from the surrounding defaults: the thread-scoped options, then the global options, then the
     * baseline ({@link TransactionPropagation#REQUIRED}, provider-default isolation and timeout, read-write).
     *
     * @param options the transaction options.
     * @param block the transactional logic to execute.
     * @return the result of the block.
     * @param <R> the result type.
     * @param <E> the checked exception type thrown by the block, if any.
     * @throws st.orm.PersistenceException if transaction execution fails.
     */
    public static <R extends @Nullable Object, E extends Exception> R transaction(TransactionOptions options,
                                                         TransactionBlock<R, E> block) throws E {
        requireNonNull(options, "options");
        requireNonNull(block, "block");
        var resolved = merge(options, currentDefaults());
        return TransactionRunner.execute(resolved, block::execute);
    }

    /**
     * Sets the global transaction options, affecting new transactions that do not override options locally.
     * Options left {@code null} fall back to the baseline defaults.
     *
     * <p>Typical usage: call once during application startup to configure defaults that apply to all
     * transactions.</p>
     *
     * @param options the global transaction options.
     */
    public static void setGlobalTransactionOptions(TransactionOptions options) {
        requireNonNull(options, "options");
        GLOBAL.set(merge(options, BASELINE));
    }

    /**
     * Executes the given block with the given options as the thread-scoped transaction defaults. Options left
     * {@code null} inherit the current defaults; the previous defaults are restored when the block completes.
     *
     * @param options the scoped transaction defaults.
     * @param block the code to execute.
     * @return the result of the block.
     * @param <R> the result type.
     * @param <E> the checked exception type thrown by the block, if any.
     */
    public static <R extends @Nullable Object, E extends Exception> R withTransactionOptions(TransactionOptions options,
                                                                    TransactionSupplier<R, E> block) throws E {
        requireNonNull(options, "options");
        requireNonNull(block, "block");
        var previous = LOCAL.get();
        LOCAL.set(merge(options, currentDefaults()));
        try {
            return block.get();
        } finally {
            if (previous == null) {
                LOCAL.remove();
            } else {
                LOCAL.set(previous);
            }
        }
    }

    private static TransactionOptions currentDefaults() {
        var local = LOCAL.get();
        return local != null ? local : GLOBAL.get();
    }

    private static TransactionOptions merge(TransactionOptions options, TransactionOptions defaults) {
        return new TransactionOptions(
                options.propagation() != null ? options.propagation() : defaults.propagation(),
                options.isolation() != null ? options.isolation() : defaults.isolation(),
                options.timeoutSeconds() != null ? options.timeoutSeconds() : defaults.timeoutSeconds(),
                options.readOnly() != null ? options.readOnly() : defaults.readOnly());
    }
}
