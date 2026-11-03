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

import java.util.function.Consumer;

/**
 * Handle to the transaction a transactional block runs in: exposes the rollback-only state and registration of
 * completion callbacks.
 *
 * <p>This is the language-neutral base handle. The Kotlin API extends it with suspend-friendly callback
 * overloads.</p>
 *
 * @since 1.13
 */
public interface Transaction {

    /**
     * Whether the transaction is marked rollback-only.
     */
    boolean isRollbackOnly();

    /**
     * Marks the transaction rollback-only: the block completes normally, but the transaction rolls back.
     */
    void setRollbackOnly();

    /**
     * Cancels the transaction: the statement the block is blocked on is stopped through the driver, and no further
     * statement runs in the block. The block then fails at that statement, or at its next one, and rolls back as a
     * failing block does. A cancellation states no further interest in the block's work, not an outcome: a block
     * whose statements all completed before it arrived completes as usual.
     *
     * <p>Meant to be called from another thread or coroutine, by whoever decides the work is no longer wanted:
     * the block hands its handle out, and the caller returns at once while the driver is asked to stop the
     * statement on a thread of its own. The block may also call it on itself, which refuses its next statement.
     * A suspending block is cancelled the same way when its coroutine is cancelled.</p>
     *
     * @since 1.16
     */
    void cancel();

    /**
     * Registers a callback invoked after the physical transaction commits successfully.
     *
     * <p>If this scope is joined to an outer transaction (for example via {@link TransactionPropagation#REQUIRED}
     * or {@link TransactionPropagation#NESTED}), the callback is deferred to the outermost physical transaction's
     * commit. {@link TransactionPropagation#REQUIRES_NEW} scopes fire their own callbacks independently.</p>
     *
     * <p>The scope is uninstalled before the callback runs, so no transaction is active while it executes. A
     * database operation performed here runs in auto-commit, and a transactional block opened here starts a new
     * physical transaction rather than joining the one that just committed. That is what makes the callback the
     * place for work that must observe the committed state, or that must not be rolled back together with it,
     * such as publishing an event or invalidating a cache.</p>
     *
     * <p>Callbacks run synchronously, before the transactional block returns, so their duration is added to the
     * caller's. Work that can block for a long time, such as a write that contends with a batch job, belongs on a
     * background worker that the callback hands off to.</p>
     *
     * <p>Callbacks registered through {@link #onCommit(Runnable)}, {@link #onRollback(Runnable)} and
     * {@link #onCompletion(Consumer)} share one order: they execute in the order they were registered, skipping
     * the ones that do not apply to the outcome. If a callback throws, remaining callbacks still execute and the
     * failures are reported as a {@link TransactionCallbackException} whose cause is the first one, with the rest
     * attached to it as suppressed. That exception leaves the transactional block after the transaction has
     * already committed, so catch it to tell a failed side effect apart from a failed transaction.</p>
     *
     * @param callback the callback to invoke after commit.
     */
    void onCommit(Runnable callback);

    /**
     * Registers a callback invoked after the physical transaction rolls back.
     *
     * <p>Rollback may be triggered by an exception, {@link #setRollbackOnly()}, or a timeout. Deferral, ordering,
     * exception handling, and the absence of an active transaction while the callback runs match
     * {@link #onCommit(Runnable)}. When the rollback was caused by an exception, a callback failure is attached
     * to that exception as suppressed rather than replacing it.</p>
     *
     * @param callback the callback to invoke after rollback.
     */
    void onRollback(Runnable callback);

    /**
     * Registers a callback invoked after the physical transaction completes, whichever way it completed. The
     * callback receives {@code true} when the transaction committed and {@code false} when it rolled back.
     *
     * <p>This is the variant for work that has to happen either way, such as releasing a lock or closing a span.
     * Use {@link #onCommit(Runnable)} or {@link #onRollback(Runnable)} when only one outcome is of interest;
     * they are the simpler form and say so at the registration site.</p>
     *
     * <p>Deferral, ordering, exception handling, and the absence of an active transaction while the callback runs
     * match {@link #onCommit(Runnable)}.</p>
     *
     * @param callback the callback to invoke after completion.
     */
    void onCompletion(Consumer<Boolean> callback);
}
