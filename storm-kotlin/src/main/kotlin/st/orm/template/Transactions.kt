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
package st.orm.template

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.asContextElement
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import st.orm.TransactionIsolation
import st.orm.TransactionOptions
import st.orm.TransactionPropagation
import st.orm.TransactionPropagation.MANDATORY
import st.orm.TransactionPropagation.NESTED
import st.orm.TransactionPropagation.NEVER
import st.orm.TransactionPropagation.NOT_SUPPORTED
import st.orm.TransactionPropagation.REQUIRED
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.TransactionPropagation.SUPPORTS
import st.orm.core.spi.TransactionRunner
import st.orm.core.spi.TransactionScope
import st.orm.template.impl.TransactionCallbacks
import java.util.concurrent.atomic.AtomicReference
import java.util.function.Consumer
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Executes the given [block] within a database transaction.
 *
 * The transaction binds to the first ORM template that executes inside the block: opening the block only records the
 * requested options, and the template's transaction provider opens the actual transaction on first use. A block that
 * never touches a template completes as a no-op.
 *
 * ## Propagation behavior matrix
 *
 * | Propagation       | Inner commit                                     | Inner rollback                                                   | Outer commit                                         | Outer rollback                                                      |
 * |-------------------|--------------------------------------------------|------------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------------|
 * | `REQUIRED`        | Joins outer tx—no actual commit until outer ends | Marks whole tx rollback-only; everything rolls back at end       | Commits entire tx (all work)                         | Rolls back entire tx (all work)                                     |
 * | `REQUIRES_NEW`    | Commits only the new (inner) tx                  | Rolls back only the inner tx; outer stays active                 | Commits the outer tx (inner work stays committed)    | Rolls back the outer tx; inner-committed work remains               |
 * | `NESTED`          | Releases the JDBC savepoint—inner changes are now visible to the outer transaction  | Rolls back to savepoint—undoes just inner work, outer stays open | Commits entire tx (savepoints dropped, all work kept) | Rolls back entire tx (including inner work, regardless of savepoint) |
 *
 * @param propagation    Controls how this call participates in an existing transaction:
 *                       - `REQUIRED` (default): join or start if none
 *                       - `REQUIRES_NEW`: suspend outer, start fresh
 *                       - `NESTED`: create JDBC savepoint
 * @param isolation      The isolation level for the transaction. If `null`, uses the provider default.
 * @param timeoutSeconds The transaction timeout in seconds. If `null`, uses provider default.
 * @param readOnly       Whether the transaction is read-only. Defaults to `false`.
 * @param block          The transactional logic to execute.
 * @return The result of executing [block].
 * @throws st.orm.PersistenceException if transaction execution fails.
 * @since 1.5
 */
public fun <T> transactionBlocking(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: Transaction.() -> T,
): T {
    val options = localTransactionOptions.get() ?: globalTransactionOptions.get()
    val scopeOptions = TransactionOptions(
        propagation ?: options.propagation,
        isolation ?: options.isolation,
        timeoutSeconds ?: options.timeoutSeconds,
        readOnly ?: options.readOnly,
    )
    // The blocking orchestration lives once, in core's TransactionRunner; wrap the language-neutral handle in
    // the Kotlin Transaction so the receiver keeps its suspend-friendly callback overloads.
    return TransactionRunner.execute<T, RuntimeException>(scopeOptions) { transaction ->
        block(transaction.asKotlinTransaction())
    }
}

/**
 * Executes the given [block] within a coroutine-friendly database transaction.
 *
 * This variant ensures the transactional logic runs on the specified coroutine [dispatcher]
 * (e.g. a dispatcher) while preserving all the usual Spring semantics for propagation,
 * isolation, timeout and read-only settings.
 *
 * The transaction binds to the first ORM template that executes inside the block: opening the block only records the
 * requested options, and the template's transaction provider opens the actual transaction on first use. A block that
 * never touches a template completes as a no-op.
 *
 * The options not given here come from the defaults in scope: the coroutine context's, set with
 * [withTransactionOptions]; otherwise the calling thread's, set with [withTransactionOptionsBlocking], which is how a
 * coroutine bridged from a thread with `runBlocking` inherits what that thread declared; otherwise the global defaults
 * of [setGlobalTransactionOptions].
 *
 * ## Propagation behavior matrix
 *
 * | Propagation       | Inner commit                                     | Inner rollback                                                   | Outer commit                                         | Outer rollback                                                      |
 * |-------------------|--------------------------------------------------|------------------------------------------------------------------|------------------------------------------------------|----------------------------------------------------------------------|
 * | `REQUIRED`        | Joins outer tx—no real commit until outer ends   | Marks whole tx rollback-only; rolls back all on outer exit      | Commits entire tx (all work)                         | Rolls back entire tx (all work)                                     |
 * | `REQUIRES_NEW`    | Commits only the new (inner) tx                  | Rolls back only the inner tx; outer stays active                 | Commits outer tx (inner stays committed)             | Rolls back outer tx; inner-committed work remains                  |
 * | `NESTED`          | Releases the JDBC savepoint—inner changes are now visible to the outer transaction  | Rolls back to savepoint—undoes just inner work, outer stays open | Commits entire tx (savepoints dropped, all work kept) | Rolls back entire tx (including inner work, regardless of savepoint) |
 *
 * @param dispatcher        The [CoroutineDispatcher] in which to run the transaction.
 *                          For example, `Dispatchers.IO`.
 * @param propagation       Controls how this call participates in an existing transaction:
 *                          - `REQUIRED` (default): join or start if none
 *                          - `REQUIRES_NEW`: suspend outer, start fresh
 *                          - `NESTED`: create JDBC savepoint
 * @param isolation         The isolation level for the transaction. If `null`, uses the provider default.
 * @param timeoutSeconds    The transaction timeout in seconds. If `null`, uses the provider's default.
 * @param readOnly          Whether the transaction is read-only. Defaults to `false`.
 * @param block             The transactional logic to execute, with `this` bound to a [Transaction].
 * @return The result of executing [block].
 * @throws st.orm.PersistenceException if transaction execution or rollback/commit fails.
 * @since 1.5
 */
public suspend fun <T> transaction(
    dispatcher: CoroutineDispatcher = TransactionDispatchers.Default,
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: suspend Transaction.() -> T,
): T {
    val currentContext = currentCoroutineContext()
    // The coroutine context's defaults win; a coroutine bridged from a thread carries none and takes the thread's,
    // as the blocking variant does, before the global ones.
    val options = currentContext[Scoped]?.options ?: localTransactionOptions.get() ?: globalTransactionOptions.get()
    val resolvedPropagation = propagation ?: options.propagation
    val scopeOptions = TransactionOptions(
        resolvedPropagation,
        isolation ?: options.isolation,
        timeoutSeconds ?: options.timeoutSeconds,
        readOnly ?: options.readOnly,
    )
    val parentScope = TransactionScope.current()
    val scope = TransactionScope.create(scopeOptions, true, parentScope)
    val parentCallbacks = currentContext[CallbacksKey]?.callbacks
    if (resolvedPropagation.isJoining && parentScope != null && parentCallbacks != null) {
        // Joining an outer transaction block: delegate callbacks to the outer holder; do not fire here.
        val elements = CallbacksKey(parentCallbacks) +
            TransactionScope.holder().asContextElement(scope) +
            TransactionRunner.callbacksHolder().asContextElement(parentCallbacks) +
            localTransactionOptions.asContextElement(options) +
            sqlLogContext()
        val result = try {
            withContext(currentContext + elements) {
                block(scopeTransaction(scope, parentCallbacks))
            }
        } catch (e: Throwable) {
            throw TransactionRunner.completeAfterFailure(scope, e)
        }
        scope.complete(false)
        TransactionRunner.afterSuccessfulCompletion(scope)
        return result
    }
    // Owner of the callback lifecycle: outermost block, REQUIRES_NEW / NOT_SUPPORTED, or no parent callbacks.
    val callbacks = TransactionCallbacks()
    val elements = CallbacksKey(callbacks) +
        TransactionScope.holder().asContextElement(scope) + // Make the scope available via the ThreadLocal.
        TransactionRunner.callbacksHolder().asContextElement(callbacks) +
        // Make the options available via the ThreadLocal in case the blocking variant is invoked from suspend context.
        localTransactionOptions.asContextElement(options) +
        sqlLogContext()
    // The dispatcher only applies to outermost transactions; nested blocks stay on the caller's dispatcher.
    val context = if (parentScope == null) currentContext + dispatcher + elements else currentContext + elements
    // Fire callbacks AFTER withContext returns, so CallbacksKey and ThreadLocals are restored.
    val outcome = try {
        withContext(context) {
            val value = block(scopeTransaction(scope, callbacks))
            TransactionOutcome(value, scope.isRollbackOnly)
        }
    } catch (e: Throwable) {
        // Read while the scope still holds its transaction state, which completing releases.
        val mayDefer = TransactionRunner.mayDeferToExternal(scope)
        val mayMark = TransactionRunner.mayMarkExternalRollbackOnly(scope)
        val wrapped = try {
            TransactionRunner.completeAfterFailure(scope, e)
        } catch (completionException: Throwable) {
            completionException
        }
        try {
            callbacks.settle(mayDefer, mayMark, committed = false)
        } catch (callbackException: Throwable) {
            wrapped.addSuppressed(callbackException)
        }
        throw wrapped
    }
    // Read while the scope still holds its transaction state, which completing releases.
    val mayDefer = TransactionRunner.mayDeferToExternal(scope)
    val mayMark = TransactionRunner.mayMarkExternalRollbackOnly(scope)
    try {
        scope.complete(false)
        TransactionRunner.afterSuccessfulCompletion(scope)
    } catch (completionException: Throwable) {
        try {
            callbacks.settle(mayDefer, mayMark, committed = false)
        } catch (callbackException: Throwable) {
            completionException.addSuppressed(callbackException)
        }
        throw completionException
    }
    callbacks.settle(mayDefer, outcome.rollbackOnly && mayMark, committed = !outcome.rollbackOnly)
    return outcome.value
}

/**
 * Coroutine context element that carries the [TransactionCallbacks] holder for the current physical transaction.
 */
private class CallbacksKey(val callbacks: TransactionCallbacks) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallbacksKey>
}

/**
 * Returns `true` when this propagation joins an existing physical transaction rather than starting a new one.
 */
private val TransactionPropagation.isJoining: Boolean
    get() = when (this) {
        REQUIRED, SUPPORTS, MANDATORY, NESTED -> true
        REQUIRES_NEW, NOT_SUPPORTED, NEVER -> false
    }

/**
 * Captures the result and rollback-only status of a transaction execution.
 */
private class TransactionOutcome<T>(val value: T, val rollbackOnly: Boolean)

/**
 * Wraps the language-neutral transaction handle in the Kotlin [Transaction], bridging suspend callbacks via
 * `runBlocking` with an empty coroutine context (the blocking flow's documented callback semantics).
 */
private fun st.orm.Transaction.asKotlinTransaction(): Transaction {
    val base = this
    return object : Transaction, st.orm.Transaction by base {
        override fun onCommit(callback: suspend () -> Unit) {
            base.onCommit { runBlocking { callback() } }
        }

        override fun onRollback(callback: suspend () -> Unit) {
            base.onRollback { runBlocking { callback() } }
        }

        override fun onCompletion(callback: suspend (Boolean) -> Unit) {
            base.onCompletion { committed -> runBlocking { callback(committed) } }
        }
    }
}

/**
 * Returns the [Transaction] receiver for the given scope, delegating callback registration to [callbacks].
 */
private fun scopeTransaction(scope: TransactionScope, callbacks: TransactionCallbacks): Transaction = object : Transaction {
    override fun isRollbackOnly(): Boolean = scope.isRollbackOnly

    override fun setRollbackOnly() {
        scope.setRollbackOnly()
    }

    override fun onCommit(callback: suspend () -> Unit) {
        callbacks.addOnCommit(callback)
    }

    override fun onRollback(callback: suspend () -> Unit) {
        callbacks.addOnRollback(callback)
    }

    override fun onCompletion(callback: suspend (Boolean) -> Unit) {
        callbacks.addOnCompletion(callback)
    }

    override fun onCommit(callback: Runnable) {
        callbacks.addOnCommit(callback)
    }

    override fun onRollback(callback: Runnable) {
        callbacks.addOnRollback(callback)
    }

    override fun onCompletion(callback: Consumer<Boolean>) {
        callbacks.addOnCompletion(callback)
    }
}

/**
 * Global transaction options that are applied to all transactions by default.
 */
private val globalTransactionOptions = AtomicReference(TransactionDefaults())

/**
 * Coroutine context element for transaction options that are applied to all transactions started in the current
 * coroutine context.
 */
private class Scoped(val options: TransactionDefaults) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Scoped>
}

/**
 * Thread-local transaction options that are applied to all transactions started in the current thread: the blocking
 * ones, and the suspending ones whose coroutine context carries no scoped options of its own.
 */
private val localTransactionOptions: ThreadLocal<TransactionDefaults?> = ThreadLocal.withInitial { null }

/**
 * Sets the global transaction options.
 *
 * This affects *new* transactions that do not override options locally.
 *
 * Typical usage: call once during application startup to configure default options that apply to all transactions.
 *
 * @param propagation The transaction propagation behavior.
 * @param isolation The transaction isolation level.
 * @param timeoutSeconds The transaction timeout in seconds.
 * @param readOnly Whether the transaction is read-only.
 * @since 1.6
 */
public fun setGlobalTransactionOptions(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
) {
    val defaults = TransactionDefaults()
    globalTransactionOptions.set(
        defaults.copy(
            propagation = propagation ?: defaults.propagation,
            isolation = isolation ?: defaults.isolation,
            timeoutSeconds = timeoutSeconds ?: defaults.timeoutSeconds,
            readOnly = readOnly ?: defaults.readOnly,
        ),
    )
}

/**
 * Set the default transaction options for the current coroutine context.
 *
 * This function is intended to be used in combination with [transaction]. The options not given here are inherited
 * from the defaults already in scope: the enclosing coroutine context's, otherwise the calling thread's, set with
 * [withTransactionOptionsBlocking], otherwise the global ones. Opened without arguments at the start of a coroutine
 * bridged from a thread, it pins the thread's defaults into the coroutine context, so they survive a later dispatcher
 * switch.
 *
 * @param propagation The transaction propagation behavior.
 * @param isolation The transaction isolation level.
 * @param timeoutSeconds The transaction timeout in seconds.
 * @param readOnly Whether the transaction is read-only.
 * @param block The coroutine code to execute.
 * @return The result of executing [block].
 * @since 1.6
 */
public suspend fun <T> withTransactionOptions(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: suspend () -> T,
): T {
    val currentContext = currentCoroutineContext()
    val current = currentContext[Scoped]?.options ?: localTransactionOptions.get() ?: globalTransactionOptions.get()
    val scoped = TransactionDefaults().copy(
        propagation = propagation ?: current.propagation,
        isolation = isolation ?: current.isolation,
        timeoutSeconds = timeoutSeconds ?: current.timeoutSeconds,
        readOnly = readOnly ?: current.readOnly,
    )
    return withContext(
        Scoped(scoped) +
            sqlLogContext() +
            localTransactionOptions.asContextElement(scoped), // Make the defaults available via the ThreadLocal in case the blocking variant is invoked from suspend context.
    ) { block() }
}

/**
 * Set the default transaction options for the current thread.
 *
 * This function is intended to be used in combination with [transactionBlocking]. A suspending [transaction] or
 * [withTransactionOptions] entered on this thread whose coroutine context carries no scoped options of its own, as one
 * bridged with `runBlocking` does, inherits them as well: a servlet filter that declares a reading request read only
 * here reaches every transaction the request opens, blocking or suspending.
 *
 * @param propagation The transaction propagation behavior.
 * @param isolation The transaction isolation level.
 * @param timeoutSeconds The transaction timeout in seconds.
 * @param readOnly Whether the transaction is read-only.
 * @param block The code to execute.
 * @return The result of executing [block].
 * @since 1.6
 */
public fun <T> withTransactionOptionsBlocking(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: () -> T,
): T {
    val previous = localTransactionOptions.get()
    val current = previous ?: globalTransactionOptions.get()
    localTransactionOptions.set(
        TransactionDefaults(
            propagation = propagation ?: current.propagation,
            isolation = isolation ?: current.isolation,
            timeoutSeconds = timeoutSeconds ?: current.timeoutSeconds,
            readOnly = readOnly ?: current.readOnly,
        ),
    )
    return try {
        block()
    } finally {
        if (previous == null) localTransactionOptions.remove() else localTransactionOptions.set(previous)
    }
}
