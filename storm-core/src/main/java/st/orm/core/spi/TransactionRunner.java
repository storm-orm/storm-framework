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

import java.sql.SQLTimeoutException;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import st.orm.Transaction;
import st.orm.TransactionCallbackException;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.TransactionTimedOutException;
import st.orm.UnexpectedRollbackException;

/**
 * Executes transactional blocks on the pending-scope engine: the single source of the blocking transaction
 * orchestration, driven by the Java {@code Transactions} API and Kotlin's {@code transactionBlocking}.
 *
 * <p>Opening a block only records the requested options as a {@link TransactionScope}; the transaction binds to
 * the first ORM template that executes inside the block. A block that never touches a template completes as a
 * no-op, with deadline and inherited-rollback checks still enforced deterministically.</p>
 *
 * <p>The Kotlin suspend flow keeps its own coroutine-shaped orchestration, reusing
 * {@link #afterSuccessfulCompletion(TransactionScope)} and
 * {@link #completeAfterFailure(TransactionScope, Throwable)} and installing its callback holder in
 * {@link #callbacksHolder()} so blocking blocks nested inside suspend blocks defer their callbacks to the
 * owning physical transaction.</p>
 *
 * @since 1.13
 */
public final class TransactionRunner {

    /**
     * A transactional block. Checked exceptions propagate to the caller unchanged and trigger rollback.
     *
     * @param <R> the result type.
     * @param <E> the checked exception type thrown by the block, if any.
     */
    @FunctionalInterface
    public interface Block<R extends @Nullable Object, E extends Exception> {
        R execute(Transaction transaction) throws E;
    }

    /**
     * Holds the callbacks sink of the current physical transaction for the blocking flow. Exposed so coroutine
     * integrations can propagate it across dispatcher hops as a context element.
     */
    private static final ThreadLocal<TransactionCallbacks> CURRENT_CALLBACKS = new ThreadLocal<>();

    private TransactionRunner() {
    }

    /**
     * Returns the thread local holding the callbacks sink of the current physical transaction.
     */
    public static ThreadLocal<TransactionCallbacks> callbacksHolder() {
        return CURRENT_CALLBACKS;
    }

    /**
     * Executes the given block within a transaction scope built from the given options.
     *
     * @param options the fully resolved transaction options.
     * @param block the transactional logic to execute.
     * @return the result of the block.
     * @param <R> the result type.
     * @param <E> the checked exception type thrown by the block, if any.
     */
    public static <R extends @Nullable Object, E extends Exception> R execute(TransactionOptions options,
                                                     Block<R, E> block) throws E {
        var scope = TransactionScope.open(options, false);
        var parentCallbacks = CURRENT_CALLBACKS.get();
        if (isJoining(options.propagation()) && scope.parent() != null && parentCallbacks != null) {
            // Joining an outer transaction block: delegate callbacks to the outer holder; do not fire here.
            R result;
            try {
                result = block.execute(scopeTransaction(scope, parentCallbacks));
            } catch (Throwable e) {
                try {
                    throw sneakyThrow(completeAfterFailure(scope, e));
                } finally {
                    scope.close();
                }
            }
            try {
                scope.complete(false);
                afterSuccessfulCompletion(scope);
            } finally {
                scope.close();
            }
            return result;
        }
        // Owner of the callback lifecycle: outermost block, REQUIRES_NEW / NOT_SUPPORTED, or no parent
        // callbacks.
        var callbacks = new BlockingCallbacks();
        var previousCallbacks = parentCallbacks;
        CURRENT_CALLBACKS.set(callbacks);
        R value;
        boolean rollbackOnly;
        // Read while the scope still holds its transaction state, which completing releases.
        boolean joinedExternal;
        boolean mayDefer;
        boolean mayMark;
        try {
            value = block.execute(scopeTransaction(scope, callbacks));
            rollbackOnly = scope.isRollbackOnly();
            joinedExternal = scope.joinedExistingTransaction();
            mayDefer = mayDeferToExternal(scope);
            mayMark = mayMarkExternalRollbackOnly(scope);
        } catch (Throwable e) {
            joinedExternal = scope.joinedExistingTransaction();
            mayDefer = mayDeferToExternal(scope);
            mayMark = mayMarkExternalRollbackOnly(scope);
            // Restore and uninstall the scope before firing so callbacks see a clean state.
            restoreCallbacks(previousCallbacks);
            Throwable wrapped;
            try {
                wrapped = completeAfterFailure(scope, e);
            } catch (Throwable completionException) {
                wrapped = completionException;
            } finally {
                scope.close();
            }
            try {
                complete(scope, callbacks, joinedExternal, mayDefer, mayMark, false);
            } catch (Throwable callbackException) {
                wrapped.addSuppressed(callbackException);
            }
            throw sneakyThrow(wrapped);
        }
        // Restore and uninstall the scope before firing so callbacks see a clean state.
        restoreCallbacks(previousCallbacks);
        try {
            try {
                scope.complete(false);
                afterSuccessfulCompletion(scope);
            } finally {
                scope.close();
            }
        } catch (Throwable completionException) {
            try {
                complete(scope, callbacks, joinedExternal, mayDefer, mayMark, false);
            } catch (Throwable callbackException) {
                completionException.addSuppressed(callbackException);
            }
            throw sneakyThrow(completionException);
        }
        complete(scope, callbacks, joinedExternal, mayDefer, rollbackOnly && mayMark, !rollbackOnly);
        return value;
    }

    /**
     * Post-completion checks that mirror the semantics of eagerly entered transaction frames.
     *
     * <p>A scope that never touched a template still fails deterministically when its deadline has passed,
     * marking a joined outer scope rollback-only. A scope whose rollback-only mark was inherited from a joined
     * inner scope raises an {@link UnexpectedRollbackException} when its block attempts to commit; materialized
     * frames raise this from the provider's commit path, this check covers marks that were propagated at the
     * scope level.</p>
     */
    public static void afterSuccessfulCompletion(TransactionScope scope) {
        if (!scope.isMaterialized() && scope.isDeadlineExpired()) {
            var propagation = scope.options().propagation();
            boolean joining = propagation == null
                    || propagation == TransactionPropagation.REQUIRED
                    || propagation == TransactionPropagation.SUPPORTS
                    || propagation == TransactionPropagation.MANDATORY;
            if (joining && scope.parent() != null) {
                scope.parent().setRollbackOnly();
            }
            throw new TransactionTimedOutException("Did not complete within timeout.");
        }
        if (scope.isRollbackInherited()) {
            throw new UnexpectedRollbackException("Transaction was marked rollback-only by a joined scope.");
        }
    }

    /**
     * Completes the scope after a failed block and returns the exception to throw: the original failure, or a
     * {@link TransactionTimedOutException} when the failure was caused by a statement timeout. Completion
     * failures are suppressed onto the original failure.
     */
    public static Throwable completeAfterFailure(TransactionScope scope, Throwable e) {
        String description;
        try {
            var context = scope.materializedContext();
            description = context == null ? null : context.describe().orElse(null);
        } catch (Throwable ignore) {
            description = null;
        }
        try {
            scope.complete(true); // Suppresses rollback exceptions internally to surface the original error.
        } catch (Throwable completionException) {
            e.addSuppressed(completionException);
        }
        if (e.getCause() instanceof SQLTimeoutException) {
            var base = e.getMessage() == null ? "Did not complete within timeout." : e.getMessage();
            return new TransactionTimedOutException(description != null ? base + " [" + description + "]" : base, e);
        }
        return e;
    }

    /**
     * Settles the block's callbacks for the given outcome.
     *
     * <p>A block inside a physical transaction an external manager owns has not reached an outcome yet: that
     * manager still decides one and commits. A block that joined the transaction hands its callbacks to its
     * transaction handle, which registers them with the manager to fire on the real outcome, keeping their
     * registration order. A block that never bound to a template settles the same way through the detected
     * external transaction, which also receives the block's rollback-only demand, mirroring how a joined scope
     * marks its parent. Everything else fires here, since the block's own transaction has completed.</p>
     */
    private static void complete(TransactionScope scope,
                                 BlockingCallbacks callbacks,
                                 boolean joinedExternal,
                                 boolean mayDeferToExternal,
                                 boolean markExternalRollbackOnly,
                                 boolean committed) {
        if (joinedExternal) {
            if (!callbacks.isEmpty()) {
                scope.deferCompletion(callbacks::fire);
            }
            return;
        }
        if (mayDeferToExternal && (markExternalRollbackOnly || !callbacks.isEmpty())) {
            var external = externalTransaction();
            if (external != null) {
                if (markExternalRollbackOnly) {
                    external.setRollbackOnly();
                }
                if (!callbacks.isEmpty()) {
                    external.onCompletion(callbacks::fire);
                }
                return;
            }
        }
        if (callbacks.isEmpty()) {
            return;
        }
        if (committed) {
            callbacks.fireCommit();
        } else {
            callbacks.fireRollback();
        }
    }

    /**
     * Returns whether the block may sit inside an externally managed transaction it never bound to: its scope
     * never materialized while its propagation joins whatever transaction surrounds it. Settlement then asks
     * the {@link ExternalTransactionProvider external transaction providers} whether one is active.
     *
     * <p>Must be called before the scope completes, since completing releases the transaction state this
     * reads.</p>
     *
     * @param scope the scope of the completed block.
     * @return {@code true} if the block's callbacks may belong to an external transaction.
     * @since 1.13
     */
    public static boolean mayDeferToExternal(TransactionScope scope) {
        return !scope.isMaterialized() && isJoining(scope.options().propagation());
    }

    /**
     * Returns whether a rollback demand of an unbound block must be pushed onto the surrounding external
     * transaction.
     *
     * <p>Mirrors {@link TransactionScope}'s parent-mark propagation: {@code REQUIRED}, {@code SUPPORTS} and
     * {@code MANDATORY} join the surrounding transaction outright, so their rollback dooms it, while
     * {@code NESTED} bounds its rollback at a savepoint. A materialized scope delivers the mark through its
     * own handle, and needs no push here.</p>
     *
     * <p>Must be called before the scope completes, since completing releases the transaction state this
     * reads.</p>
     *
     * @param scope the scope of the completed block.
     * @return {@code true} if a rollback of the block must mark the surrounding external transaction.
     * @since 1.13
     */
    public static boolean mayMarkExternalRollbackOnly(TransactionScope scope) {
        if (scope.isMaterialized()) {
            return false;
        }
        var propagation = scope.options().propagation();
        return propagation == null
                || propagation == TransactionPropagation.REQUIRED
                || propagation == TransactionPropagation.SUPPORTS
                || propagation == TransactionPropagation.MANDATORY;
    }

    /**
     * Returns the handle of the externally managed transaction active on the current thread, asking each
     * external transaction provider in resolution order and taking the first that answers, or {@code null}
     * when there is none.
     *
     * <p>Exposed for language flows that own their callback orchestration, such as the Kotlin suspend flow,
     * which settle their callbacks against the same external transaction this class does.</p>
     *
     * @return the externally managed transaction, or {@code null} when none is active.
     * @since 1.13
     */
    public static @Nullable Transaction externalTransaction() {
        for (var provider : Providers.getExternalTransactionProviders()) {
            var transaction = provider.currentTransaction().orElse(null);
            if (transaction != null) {
                return transaction;
            }
        }
        return null;
    }

    private static boolean isJoining(@Nullable TransactionPropagation propagation) {
        if (propagation == null) {
            return true; // Provider default is REQUIRED.
        }
        return switch (propagation) {
            case REQUIRED, SUPPORTS, MANDATORY, NESTED -> true;
            case REQUIRES_NEW, NOT_SUPPORTED, NEVER -> false;
        };
    }

    private static void restoreCallbacks(@Nullable TransactionCallbacks previous) {
        if (previous == null) {
            CURRENT_CALLBACKS.remove();
        } else {
            CURRENT_CALLBACKS.set(previous);
        }
    }

    private static Transaction scopeTransaction(TransactionScope scope,
                                                TransactionCallbacks callbacks) {
        return new Transaction() {
            @Override
            public boolean isRollbackOnly() {
                return scope.isRollbackOnly();
            }

            @Override
            public void setRollbackOnly() {
                scope.setRollbackOnly();
            }

            @Override
            public void onCommit(Runnable callback) {
                callbacks.addOnCommit(callback);
            }

            @Override
            public void onRollback(Runnable callback) {
                callbacks.addOnRollback(callback);
            }

            @Override
            public void onCompletion(Consumer<Boolean> callback) {
                callbacks.addOnCompletion(callback);
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static <E extends Throwable> RuntimeException sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    /**
     * Collects and executes transaction lifecycle callbacks in registration order. If a callback throws,
     * remaining callbacks still execute; the first exception is surfaced with subsequent ones added as
     * suppressed.
     */
    private static final class BlockingCallbacks implements TransactionCallbacks {
        private final List<Entry> callbacks = new ArrayList<>();

        /**
         * A registered callback and the outcomes it applies to, so that callbacks of every kind share a single
         * registration order.
         */
        private record Entry(boolean onCommit, boolean onRollback, Consumer<Boolean> action) {
            boolean applies(boolean committed) {
                return committed ? onCommit : onRollback;
            }
        }

        @Override
        public void addOnCommit(Runnable callback) {
            callbacks.add(new Entry(true, false, committed -> callback.run()));
        }

        @Override
        public void addOnRollback(Runnable callback) {
            callbacks.add(new Entry(false, true, committed -> callback.run()));
        }

        @Override
        public void addOnCompletion(Consumer<Boolean> callback) {
            callbacks.add(new Entry(true, true, callback));
        }

        boolean isEmpty() {
            return callbacks.isEmpty();
        }

        void fireCommit() {
            fire(true);
        }

        void fireRollback() {
            fire(false);
        }

        private void fire(boolean committed) {
            Throwable first = null;
            // Iterate a snapshot so that a callback registering another callback is a no-op for this run rather
            // than a concurrent modification.
            for (var entry : List.copyOf(callbacks)) {
                if (!entry.applies(committed)) {
                    continue;
                }
                try {
                    entry.action().accept(committed);
                } catch (Throwable e) {
                    if (first == null) {
                        first = e;
                    } else {
                        first.addSuppressed(e);
                    }
                }
            }
            if (first != null) {
                throw new TransactionCallbackException(committed
                        ? "Transaction committed, but a completion callback failed."
                        : "Transaction rolled back, and a completion callback failed.", first, committed);
            }
        }
    }
}
