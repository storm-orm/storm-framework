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

import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.core.spi.TransactionTemplate.TransactionHandle;
import st.orm.spi.QueryObserver;

/**
 * A pending transaction scope that binds to the first ORM template that executes inside it.
 *
 * <p>A scope is opened by the transaction API before the transactional block runs, carrying only the requested
 * transaction options. No transaction subsystem is selected at that point. The scope is <em>materialized</em> by the
 * first template that acquires a connection inside the block: that template's
 * {@link TransactionTemplateProvider} opens the actual transaction with the scope's options. Subsequent executions
 * by templates configured with the <em>same provider instance</em> join the materialized transaction; executions by
 * templates configured with a different provider fail fast, since a single commit cannot span two transaction
 * subsystems.</p>
 *
 * <p>Scopes nest: a scope opened while another scope is active joins or suspends the outer transaction according to
 * its propagation, evaluated by the owning provider at materialization time. A scope that is never materialized
 * completes as a no-op, propagating a buffered rollback-only mark to its parent when its propagation joins the outer
 * transaction.</p>
 *
 * <p>The current scope is tracked per thread via {@link #holder()}; coroutine or executor integrations propagate it
 * across threads by installing the scope in the holder for the duration of a task.</p>
 *
 * @see TransactionTemplateProvider
 * @see TransactionTemplate
 * @since 1.13
 */
public final class TransactionScope {

    private static final ThreadLocal<TransactionScope> CURRENT = new ThreadLocal<>();

    private static final java.util.logging.Logger OBSERVER_LOGGER =
            java.util.logging.Logger.getLogger("st.orm.transaction");

    /**
     * Returns the thread local that holds the current transaction scope.
     *
     * <p>Intended for integrations that propagate the scope across threads, such as coroutine context elements.
     * Application code should not modify the holder directly.</p>
     *
     * @return the thread local holding the current transaction scope.
     */
    public static ThreadLocal<TransactionScope> holder() {
        return CURRENT;
    }

    /**
     * Returns the transaction scope that is active on the current thread, if any.
     *
     * @return the current transaction scope, or {@code null} when no scope is active.
     */
    public static @Nullable TransactionScope current() {
        return CURRENT.get();
    }

    /**
     * Opens a new transaction scope on the current thread.
     *
     * <p>The new scope's parent is the scope currently active on this thread. The scope is installed as the current
     * scope; callers must invoke {@link #close()} on the same thread when the transactional block completes.</p>
     *
     * @param options the requested transaction options.
     * @param suspendMode whether the transaction is created to be used in suspend mode.
     * @return the newly opened scope.
     */
    public static TransactionScope open(TransactionOptions options, boolean suspendMode) {
        var scope = new TransactionScope(options, suspendMode, CURRENT.get());
        CURRENT.set(scope);
        return scope;
    }

    /**
     * Creates a new transaction scope without installing it on the current thread.
     *
     * <p>Intended for integrations that manage scope propagation themselves, such as coroutine context elements.
     * {@link #close()} must not be called on scopes created through this method.</p>
     *
     * @param options the requested transaction options.
     * @param suspendMode whether the transaction is created to be used in suspend mode.
     * @param parent the parent scope, or {@code null} when this is an outermost scope.
     * @return the new scope.
     */
    public static TransactionScope create(TransactionOptions options, boolean suspendMode,
                                          @Nullable TransactionScope parent) {
        return new TransactionScope(options, suspendMode, parent);
    }

    /**
     * Returns the transaction context for the given provider, materializing the current scope if needed.
     *
     * <p>This is the connection-acquisition entry point used by ORM templates. When no scope is active, the
     * provider's own current context is returned, which covers externally managed transactions.</p>
     *
     * @param provider the transaction template provider of the executing template.
     * @return the transaction context to execute under, or {@code null} when no transaction is active.
     * @throws PersistenceException if the active scope is owned by a different provider.
     */
    public static @Nullable TransactionContext resolveContext(TransactionTemplateProvider provider,
                                                               @Nullable QueryObserver queryObserver) {
        var scope = current();
        if (scope != null) {
            return scope.getOrMaterializeContext(provider, queryObserver);
        }
        return provider.getTransactionTemplate().currentContext().orElse(null);
    }

    /**
     * Resolves the transaction context without a query observer; physical transactions opened through this
     * path are not observed.
     */
    public static @Nullable TransactionContext resolveContext(TransactionTemplateProvider provider) {
        return resolveContext(provider, null);
    }

    /**
     * Returns the transaction context for the given provider without materializing any scope.
     *
     * <p>Intended for diagnostic and cache lookups that must observe, but never start, a transaction. Returns the
     * context of the nearest materialized scope when it is owned by the given provider, or the provider's own
     * current context when no scope is active.</p>
     *
     * @param provider the transaction template provider of the executing template.
     * @return the active transaction context, or {@code null} when none is active for this provider.
     */
    public static @Nullable TransactionContext peekContext(TransactionTemplateProvider provider) {
        var scope = CURRENT.get();
        if (scope != null) {
            for (var candidate = scope; candidate != null; candidate = candidate.parent) {
                var candidateHandle = candidate.handle;
                if (candidateHandle != null) {
                    return candidate.owner == provider ? candidateHandle.context() : null;
                }
            }
            return null;
        }
        return provider.getTransactionTemplate().currentContext().orElse(null);
    }

    private final TransactionOptions options;
    private final boolean suspendMode;
    private final @Nullable TransactionScope parent;
    private final @Nullable Long deadlineNanos;

    private volatile @Nullable TransactionTemplateProvider owner;
    private volatile @Nullable TransactionHandle handle;
    private QueryObserver.@Nullable TransactionObservation observation;
    private boolean rollbackOnly;       // Buffered until materialization; guarded by this.
    private boolean rollbackInherited;  // Set when the mark came from a joined inner scope; guarded by this.

    private TransactionScope(TransactionOptions options, boolean suspendMode, @Nullable TransactionScope parent) {
        this.options = options;
        this.suspendMode = suspendMode;
        this.parent = parent;
        this.deadlineNanos = options.timeoutSeconds() != null
                ? System.nanoTime() + options.timeoutSeconds() * 1_000_000_000L
                : null;
    }

    /**
     * Returns the requested transaction options.
     *
     * @return the transaction options.
     */
    public TransactionOptions options() {
        return options;
    }

    /**
     * Returns the parent scope.
     *
     * @return the parent scope, or {@code null} when this is an outermost scope.
     */
    public @Nullable TransactionScope parent() {
        return parent;
    }

    /**
     * Returns whether this scope has been materialized by a provider.
     *
     * @return {@code true} if a transaction has been opened for this scope.
     */
    public boolean isMaterialized() {
        return handle != null;
    }

    /**
     * Returns the provider that materialized this scope.
     *
     * @return the owning provider, or {@code null} when the scope has not been materialized.
     */
    public @Nullable TransactionTemplateProvider owner() {
        return owner;
    }

    /**
     * Returns whether this scope joined a physical transaction that an external transaction manager had already
     * opened, which makes that manager, rather than this scope, the owner of its completion.
     *
     * <p>Must be read before the scope completes, since a provider releases the underlying transaction state as
     * part of completing.</p>
     *
     * @return {@code true} if this scope materialized into an externally owned physical transaction.
     * @since 1.13
     */
    public boolean joinedExistingTransaction() {
        var currentHandle = handle;
        return currentHandle != null && currentHandle.joinedExistingTransaction();
    }

    /**
     * Hands a completion callback to the physical transaction this scope joined, so the external manager that
     * owns it fires the callback on the real outcome.
     *
     * <p>Only valid for scopes that {@link #joinedExistingTransaction() joined an existing transaction}; the
     * owning provider registers the callback with its transaction manager.</p>
     *
     * @param callback receives {@code true} when the external transaction commits and {@code false} when it
     * rolls back.
     * @since 1.13
     */
    public void deferCompletion(Consumer<Boolean> callback) {
        var currentHandle = handle;
        if (currentHandle == null) {
            throw new IllegalStateException("Scope has not been materialized.");
        }
        currentHandle.deferCompletion(callback);
    }

    /**
     * Returns the transaction context of this scope, without materializing it.
     *
     * <p>Intended for diagnostics, such as describing the transaction's characteristics in error messages.</p>
     *
     * @return the materialized transaction context, or {@code null} when the scope has not been materialized.
     */
    public @Nullable TransactionContext materializedContext() {
        var currentHandle = handle;
        return currentHandle != null ? currentHandle.context() : null;
    }

    /**
     * Returns the transaction context of this scope, materializing it through the given provider if needed.
     *
     * @param provider the transaction template provider of the executing template.
     * @return the transaction context of this scope.
     * @throws PersistenceException if this scope, or an outer scope it must join, is owned by a different provider.
     */
    public synchronized TransactionContext getOrMaterializeContext(TransactionTemplateProvider provider) {
        return getOrMaterializeContext(provider, null);
    }

    /**
     * Returns the transaction context of this scope, materializing it through the given provider if needed and
     * reporting a newly opened physical transaction to the given query observer.
     *
     * @param provider the transaction template provider of the executing template.
     * @param queryObserver the query observer of the executing template, or {@code null} to observe nothing.
     * @return the transaction context of this scope.
     * @throws PersistenceException if this scope, or an outer scope it must join, is owned by a different provider.
     * @since 1.13
     */
    public synchronized TransactionContext getOrMaterializeContext(TransactionTemplateProvider provider,
                                                                   @Nullable QueryObserver queryObserver) {
        var existingHandle = handle;
        if (existingHandle != null) {
            if (owner != provider) {
                throw providerMismatch(provider, owner);
            }
            return existingHandle.context();
        }
        // Materialize enclosing scopes first, outermost-down, so every enclosing transaction block has its frame in
        // place before this scope opens. This preserves the semantics of eagerly entered transaction blocks: joining
        // propagations share the outer transaction, MANDATORY finds it, NEVER detects it, and REQUIRES_NEW suspends
        // it. The provider-identity check happens naturally in the ancestor's own materialization.
        TransactionContext outerContext = parent != null ? parent.getOrMaterializeContext(provider, queryObserver) : null;
        var template = provider.getTransactionTemplate();
        if (options.propagation() != null) {
            template = template.propagation(options.propagation());
        }
        if (options.isolation() != null) {
            template = template.isolation(options.isolation());
        }
        if (deadlineNanos != null) {
            // The timeout counts from the moment the scope was opened, not from materialization.
            template = template.timeout(remainingSeconds());
        }
        if (options.readOnly() != null) {
            template = template.readOnly(options.readOnly());
        }
        var newHandle = template.open(outerContext, suspendMode);
        if (rollbackOnly) {
            newHandle.status().setRollbackOnly();
        }
        this.owner = provider;
        this.handle = newHandle;
        if (queryObserver != null && isPhysicalTransaction(outerContext)) {
            try {
                this.observation = queryObserver.onTransaction(options);
            } catch (Throwable observerFailure) {
                OBSERVER_LOGGER.log(java.util.logging.Level.WARNING,
                        "Transaction observer failed on open; transaction unaffected.", observerFailure);
            }
        }
        return newHandle.context();
    }

    /**
     * Returns whether this scope opens a physical transaction of its own: an outermost transactional block,
     * or a block that suspends the enclosing transaction with {@code REQUIRES_NEW}. Joined blocks and
     * savepoint scopes run inside an existing physical transaction.
     */
    private boolean isPhysicalTransaction(@Nullable TransactionContext outerContext) {
        var propagation = options.propagation();
        if (propagation == TransactionPropagation.REQUIRES_NEW) {
            return true;
        }
        return outerContext == null
                && (propagation == null
                        || propagation == TransactionPropagation.REQUIRED
                        || propagation == TransactionPropagation.MANDATORY);
    }

    /**
     * Returns the number of seconds remaining until the scope's deadline, rounded up; {@code 0} when the deadline
     * has already passed.
     */
    private int remainingSeconds() {
        assert deadlineNanos != null;
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            return 0;
        }
        return (int) Math.min(Integer.MAX_VALUE, (remainingNanos + 999_999_999L) / 1_000_000_000L);
    }

    /**
     * Returns whether this scope's deadline has passed.
     *
     * <p>Always {@code false} for scopes without a timeout. Callers use this to enforce timeouts on scopes that were
     * never materialized; materialized scopes enforce their deadline through the owning provider.</p>
     *
     * @return {@code true} if the scope has a timeout and it has expired.
     */
    public boolean isDeadlineExpired() {
        return deadlineNanos != null && System.nanoTime() >= deadlineNanos;
    }

    /**
     * Marks this scope so that the only possible outcome of the transaction is a rollback.
     *
     * <p>Before materialization the mark is buffered and applied when the transaction is opened; when this scope's
     * propagation joins an outer transaction, the mark is propagated to the parent scope immediately.</p>
     */
    public synchronized void setRollbackOnly() {
        var currentHandle = handle;
        if (currentHandle != null) {
            currentHandle.status().setRollbackOnly();
            return;
        }
        rollbackOnly = true;
        if (isJoining() && parent != null) {
            parent.markRollbackInherited();
        }
    }

    /**
     * Marks this scope rollback-only on behalf of a joined inner scope.
     *
     * <p>The inherited mark distinguishes "a joined scope doomed this transaction" from "this scope marked itself";
     * transaction APIs use it to raise an unexpected-rollback error when the outer block attempts to commit.</p>
     */
    private synchronized void markRollbackInherited() {
        rollbackOnly = true;
        rollbackInherited = true;
        var currentHandle = handle;
        if (currentHandle != null) {
            currentHandle.status().setRollbackOnly();
        }
        if (isJoining() && parent != null) {
            parent.markRollbackInherited();
        }
    }

    /**
     * Returns whether this scope was marked rollback-only by a joined inner scope.
     *
     * @return {@code true} if the rollback-only mark was inherited from a joined inner scope.
     */
    public synchronized boolean isRollbackInherited() {
        return rollbackInherited;
    }

    /**
     * Returns whether this scope has been marked rollback-only.
     *
     * @return {@code true} if the transaction can only be rolled back.
     */
    public synchronized boolean isRollbackOnly() {
        var currentHandle = handle;
        if (currentHandle != null) {
            return currentHandle.status().isRollbackOnly();
        }
        if (rollbackOnly) {
            return true;
        }
        return isJoining() && parent != null && parent.isRollbackOnly();
    }

    /**
     * Completes this scope.
     *
     * <p>When the scope has been materialized, the owning provider completes the transaction: it rolls back when
     * {@code rollback} is {@code true} or the transaction has been marked rollback-only, and commits otherwise.
     * When the scope was never materialized this is a no-op, except that a rollback outcome is propagated to the
     * parent scope when this scope's propagation joins the outer transaction.</p>
     *
     * <p>This method does not uninstall the scope from the current thread; callers opened via {@link #open(TransactionOptions, boolean)}
     * must additionally call {@link #close()}.</p>
     *
     * @param rollback whether the transactional block failed and the transaction must be rolled back.
     * @throws PersistenceException if the transaction subsystem raised an issue while completing.
     */
    public synchronized void complete(boolean rollback) {
        var currentHandle = handle;
        if (currentHandle != null) {
            var currentObservation = observation;
            observation = null;
            try {
                currentHandle.complete(rollback);
            } catch (Throwable completionFailure) {
                closeObservation(currentObservation, true, completionFailure);
                throw completionFailure;
            }
            closeObservation(currentObservation, rollback || rollbackOnly, null);
            return;
        }
        if ((rollback || rollbackOnly) && isJoining() && parent != null) {
            parent.markRollbackInherited();
        }
    }

    private static void closeObservation(QueryObserver.@Nullable TransactionObservation observation,
                                         boolean rolledBack,
                                         @Nullable Throwable error) {
        if (observation == null) {
            return;
        }
        try {
            if (error != null) {
                observation.error(error);
            }
            observation.close(rolledBack);
        } catch (Throwable observerFailure) {
            OBSERVER_LOGGER.log(java.util.logging.Level.WARNING,
                    "Transaction observer failed on completion; transaction unaffected.", observerFailure);
        }
    }

    /**
     * Uninstalls this scope from the current thread, restoring its parent as the current scope.
     *
     * <p>Must be called on the thread that opened the scope via {@link #open(TransactionOptions, boolean)}, after {@link #complete(boolean)}.</p>
     *
     * @throws IllegalStateException if this scope is not the current scope of this thread.
     */
    public void close() {
        if (CURRENT.get() != this) {
            throw new IllegalStateException("Transaction scope closed out of order or on a different thread.");
        }
        if (parent == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(parent);
        }
    }

    /**
     * Returns whether this scope's propagation joins an outer transaction rather than starting an independent one:
     * a joining propagation, inside a parent that runs in a transaction.
     */
    private boolean isJoining() {
        var propagation = options.propagation();
        boolean joiningPropagation = propagation == null
                || propagation == TransactionPropagation.REQUIRED
                || propagation == TransactionPropagation.SUPPORTS
                || propagation == TransactionPropagation.MANDATORY;
        return joiningPropagation && parent != null && parent.isTransactional();
    }

    /**
     * Returns whether this scope runs in a transaction, by its propagation; {@code SUPPORTS} takes after its
     * parent. Whether an outermost {@code SUPPORTS} scope joins an externally managed transaction is known only
     * to the provider that materializes it, so it is assumed to: a rollback-only mark that travels one block too
     * far is the safer error.
     */
    private boolean isTransactional() {
        var propagation = options.propagation();
        if (propagation == null) {
            return true;
        }
        return switch (propagation) {
            case REQUIRED, REQUIRES_NEW, NESTED, MANDATORY -> true;
            case NOT_SUPPORTED, NEVER -> false;
            case SUPPORTS -> parent == null || parent.isTransactional();
        };
    }

    private static PersistenceException providerMismatch(TransactionTemplateProvider provider,
                                                         @Nullable TransactionTemplateProvider owner) {
        return new PersistenceException(("Transaction was started by %s, but the executing template is configured " +
                "with %s. A transaction cannot span templates that use different transaction template providers; " +
                "use templates that share the same provider instance within one transaction block.")
                .formatted(describeProvider(owner), describeProvider(provider)));
    }

    private static String describeProvider(@Nullable TransactionTemplateProvider provider) {
        if (provider == null) {
            return "<none>";
        }
        return "%s@%s".formatted(provider.getClass().getName(), Integer.toHexString(System.identityHashCode(provider)));
    }
}
