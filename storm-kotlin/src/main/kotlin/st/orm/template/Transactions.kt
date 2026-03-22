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

import kotlinx.coroutines.*
import st.orm.core.spi.Providers.getTransactionTemplate
import st.orm.core.spi.TransactionContext
import st.orm.core.spi.TransactionTemplate
import st.orm.template.TransactionIsolation.*
import st.orm.template.TransactionPropagation.*
import st.orm.template.impl.TransactionCallbacks
import java.sql.Connection.*
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Executes the given [block] within a database transaction.
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
 * @param isolation      The isolation level for the transaction. Defaults to [TransactionIsolation.DEFAULT].
 * @param timeoutSeconds The transaction timeout in seconds. If `null`, uses provider default.
 * @param readOnly       Whether the transaction is read-only. Defaults to `false`.
 * @param block          The transactional logic to execute.
 * @return The result of executing [block].
 * @throws st.orm.PersistenceException if transaction execution fails.
 * @since 1.5
 */
fun <T> transactionBlocking(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: Transaction.() -> T,
): T {
    val options = localTransactionOptions.get() ?: globalTransactionOptions.get()
    val resolvedPropagation = propagation ?: options.propagation
    val transactionTemplate = getTransactionTemplate(
        propagation = resolvedPropagation,
        isolation = isolation ?: options.isolation,
        timeoutSeconds = timeoutSeconds ?: options.timeoutSeconds,
        readOnly = readOnly ?: options.readOnly,
    )
    val contextHolder = transactionTemplate.contextHolder()
    contextHolder.get()?.let { existingCtx ->
        // An outer transaction context exists. Determine whether this scope joins it or owns a new physical tx.
        val parentCallbacks = currentBlockingCallbacks.get()
        if (resolvedPropagation.isJoining && parentCallbacks != null) {
            // Joining: delegate callbacks to the outer holder; do not fire here.
            return executeBlocking(transactionTemplate, existingCtx, parentCallbacks, block)
        }
        // REQUIRES_NEW / NOT_SUPPORTED or no parent callbacks: own lifecycle.
        // contextHolder is managed by the outer scope; only currentBlockingCallbacks is ours.
        val callbacks = TransactionCallbacks()
        val previousCallbacks = currentBlockingCallbacks.get()
        currentBlockingCallbacks.set(callbacks)
        val outcome = try {
            executeBlockingAndCapture(transactionTemplate, existingCtx, callbacks, block)
        } catch (e: Throwable) {
            // Restore before firing so callbacks see a clean state.
            if (previousCallbacks == null) currentBlockingCallbacks.remove() else currentBlockingCallbacks.set(previousCallbacks)
            try {
                callbacks.fireRollbackBlocking()
            } catch (callbackException: Throwable) {
                e.addSuppressed(callbackException)
            }
            throw e
        }
        // Restore before firing so callbacks see a clean state.
        if (previousCallbacks == null) currentBlockingCallbacks.remove() else currentBlockingCallbacks.set(previousCallbacks)
        if (outcome.rollbackOnly) callbacks.fireRollbackBlocking() else callbacks.fireCommitBlocking()
        return outcome.value
    }
    // Outermost: we own the physical transaction.
    val callbacks = TransactionCallbacks()
    val previousCallbacks = currentBlockingCallbacks.get()
    currentBlockingCallbacks.set(callbacks)
    val newContext = transactionTemplate.newContext(false).also { contextHolder.set(it) }
    val outcome = try {
        executeBlockingAndCapture(transactionTemplate, newContext, callbacks, block)
    } catch (e: Throwable) {
        // Clean up transaction state before firing rollback callbacks.
        contextHolder.remove()
        if (previousCallbacks == null) currentBlockingCallbacks.remove() else currentBlockingCallbacks.set(previousCallbacks)
        try {
            callbacks.fireRollbackBlocking()
        } catch (callbackException: Throwable) {
            e.addSuppressed(callbackException)
        }
        throw e
    }
    // Clean up transaction state before firing callbacks.
    contextHolder.remove()
    if (previousCallbacks == null) currentBlockingCallbacks.remove() else currentBlockingCallbacks.set(previousCallbacks)
    if (outcome.rollbackOnly) callbacks.fireRollbackBlocking() else callbacks.fireCommitBlocking()
    return outcome.value
}

/**
 * Executes the given [block] within a coroutine-friendly database transaction.
 *
 * This variant ensures the transactional logic runs on the specified coroutine [dispatcher]
 * (e.g. a dispatcher) while preserving all the usual Spring semantics for propagation,
 * isolation, timeout and read-only settings.
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
 * @param isolation         The isolation level for the transaction. Defaults to [TransactionIsolation.DEFAULT].
 * @param timeoutSeconds    The transaction timeout in seconds. If `null`, uses the provider's default.
 * @param readOnly          Whether the transaction is read-only. Defaults to `false`.
 * @param block             The transactional logic to execute, with `this` bound to a [Transaction].
 * @return The result of executing [block].
 * @throws st.orm.PersistenceException if transaction execution or rollback/commit fails.
 * @since 1.5
 */
suspend fun <T> transaction(
    dispatcher: CoroutineDispatcher = TransactionDispatchers.Default,
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: suspend Transaction.() -> T,
): T {
    val currentContext = currentCoroutineContext()
    val options = currentContext[Scoped]?.options ?: globalTransactionOptions.get()
    val resolvedPropagation = propagation ?: options.propagation
    val transactionTemplate = getTransactionTemplate(
        propagation = resolvedPropagation,
        isolation = isolation ?: options.isolation,
        timeoutSeconds = timeoutSeconds ?: options.timeoutSeconds,
        readOnly = readOnly ?: options.readOnly,
    )
    // Already in a transaction: re-use it and ensure the ThreadLocal holder is visible on this thread.
    currentContext[TransactionKey]?.context?.let { existing ->
        val parentCallbacks = currentContext[CallbacksKey]?.callbacks
        if (resolvedPropagation.isJoining && parentCallbacks != null) {
            // Joining: delegate callbacks to the outer holder; do not fire here.
            val elements = TransactionKey(existing) +
                CallbacksKey(parentCallbacks) +
                transactionTemplate.contextHolder().asContextElement(existing) +
                currentBlockingCallbacks.asContextElement(parentCallbacks) +
                localTransactionOptions.asContextElement(options)
            return withContext(currentContext + elements) {
                executeSuspend(coroutineContext, transactionTemplate, existing, parentCallbacks, block)
            }
        }
        // REQUIRES_NEW / NOT_SUPPORTED or no parent callbacks: own lifecycle.
        val callbacks = TransactionCallbacks()
        val elements = TransactionKey(existing) +
            CallbacksKey(callbacks) +
            transactionTemplate.contextHolder().asContextElement(existing) +
            currentBlockingCallbacks.asContextElement(callbacks) +
            localTransactionOptions.asContextElement(options)
        // Fire callbacks AFTER withContext returns, so TransactionKey, CallbacksKey, and ThreadLocals are restored.
        val outcome = try {
            withContext(currentContext + elements) {
                executeSuspendAndCapture(coroutineContext, transactionTemplate, existing, callbacks, block)
            }
        } catch (e: Throwable) {
            try {
                callbacks.fireRollback()
            } catch (callbackException: Throwable) {
                e.addSuppressed(callbackException)
            }
            throw e
        }
        if (outcome.rollbackOnly) callbacks.fireRollback() else callbacks.fireCommit()
        return outcome.value
    }
    val newContext = transactionTemplate.newContext(true)
    val callbacks = TransactionCallbacks()
    val elements = TransactionKey(newContext) +
        CallbacksKey(callbacks) +
        transactionTemplate.contextHolder().asContextElement(newContext) + // Make the context available via the ThreadLocal.
        currentBlockingCallbacks.asContextElement(callbacks) +
        localTransactionOptions.asContextElement(options) // Make the options available via the ThreadLocal in case the blocking variant is invoked from suspend context.
    // Fire callbacks AFTER withContext returns, so TransactionKey, CallbacksKey, and ThreadLocals are restored.
    val outcome = try {
        withContext(currentContext + dispatcher + elements) {
            // Potentially add limitedParallelism(1) here in the future.
            // Just pass the elements, not the context, as the caller might have switched dispatchers. We just want to make
            // the transaction context available to the caller's coroutine.
            executeSuspendAndCapture(coroutineContext, transactionTemplate, newContext, callbacks, block)
        }
    } catch (e: Throwable) {
        try {
            callbacks.fireRollback()
        } catch (callbackException: Throwable) {
            e.addSuppressed(callbackException)
        }
        throw e
    }
    if (outcome.rollbackOnly) callbacks.fireRollback() else callbacks.fireCommit()
    return outcome.value
}

private class TransactionKey(val context: TransactionContext) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<TransactionKey>
}

/**
 * Coroutine context element that carries the [TransactionCallbacks] holder for the current physical transaction.
 */
private class CallbacksKey(val callbacks: TransactionCallbacks) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<CallbacksKey>
}

/**
 * Thread-local that carries the [TransactionCallbacks] holder for the current physical transaction (blocking path).
 */
private val currentBlockingCallbacks: ThreadLocal<TransactionCallbacks?> = ThreadLocal.withInitial { null }

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

private fun getTransactionTemplate(
    propagation: TransactionPropagation = REQUIRED,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean = false,
) = getTransactionTemplate().apply {
    propagation(propagation.toString())
    isolation?.let {
        isolation(
            when (it) {
                READ_UNCOMMITTED -> TRANSACTION_READ_UNCOMMITTED
                READ_COMMITTED -> TRANSACTION_READ_COMMITTED
                REPEATABLE_READ -> TRANSACTION_REPEATABLE_READ
                SERIALIZABLE -> TRANSACTION_SERIALIZABLE
            },
        )
    }
    timeoutSeconds?.let { timeout(it) }
    readOnly(readOnly)
}

/**
 * Executes [block] inside the transaction and returns the result with the rollback-only status. Does not fire
 * callbacks; the caller is responsible for cleanup and firing.
 */
private fun <T> executeBlockingAndCapture(
    transactionTemplate: TransactionTemplate,
    transactionContext: TransactionContext,
    callbacks: TransactionCallbacks,
    block: Transaction.() -> T,
): TransactionOutcome<T> {
    var rollbackOnly = false
    val result = transactionTemplate.execute({ status ->
        val transaction = object : Transaction {
            override val isRollbackOnly: Boolean
                get() = status.isRollbackOnly
            override fun setRollbackOnly() {
                status.setRollbackOnly()
            }
            override fun onCommit(callback: suspend () -> Unit) {
                callbacks.addOnCommit(callback)
            }
            override fun onRollback(callback: suspend () -> Unit) {
                callbacks.addOnRollback(callback)
            }
        }
        block(transaction).also { rollbackOnly = status.isRollbackOnly }
    }, transactionContext)
    return TransactionOutcome(result, rollbackOnly)
}

/**
 * Executes [block] inside the transaction, delegating callbacks to the provided [callbacks] holder.
 * Used when joining an existing physical transaction (blocking path).
 */
private fun <T> executeBlocking(
    transactionTemplate: TransactionTemplate,
    transactionContext: TransactionContext,
    callbacks: TransactionCallbacks,
    block: Transaction.() -> T,
): T = transactionTemplate.execute({ status ->
    val transaction = object : Transaction {
        override val isRollbackOnly: Boolean
            get() = status.isRollbackOnly
        override fun setRollbackOnly() {
            status.setRollbackOnly()
        }
        override fun onCommit(callback: suspend () -> Unit) {
            callbacks.addOnCommit(callback)
        }
        override fun onRollback(callback: suspend () -> Unit) {
            callbacks.addOnRollback(callback)
        }
    }
    block(transaction)
}, transactionContext)

/**
 * Suspend variant: executes [block] inside the transaction and returns the result with the rollback-only status.
 * Does not fire callbacks; the caller is responsible for cleanup and firing.
 */
private fun <T> executeSuspendAndCapture(
    context: CoroutineContext,
    transactionTemplate: TransactionTemplate,
    transactionContext: TransactionContext,
    callbacks: TransactionCallbacks,
    block: suspend Transaction.() -> T,
): TransactionOutcome<T> {
    var rollbackOnly = false
    val result = transactionTemplate.execute({ status ->
        val transaction = object : Transaction {
            override val isRollbackOnly: Boolean
                get() = status.isRollbackOnly
            override fun setRollbackOnly() {
                status.setRollbackOnly()
            }
            override fun onCommit(callback: suspend () -> Unit) {
                callbacks.addOnCommit(callback)
            }
            override fun onRollback(callback: suspend () -> Unit) {
                callbacks.addOnRollback(callback)
            }
        }
        runBlocking(context) {
            block(transaction)
        }.also { rollbackOnly = status.isRollbackOnly }
    }, transactionContext)
    return TransactionOutcome(result, rollbackOnly)
}

/**
 * Suspend variant: executes [block] inside the transaction, delegating callbacks to the provided [callbacks] holder.
 * Used when joining an existing physical transaction.
 */
private fun <T> executeSuspend(
    context: CoroutineContext,
    transactionTemplate: TransactionTemplate,
    transactionContext: TransactionContext,
    callbacks: TransactionCallbacks,
    block: suspend Transaction.() -> T,
): T {
    @Suppress("UNCHECKED_CAST")
    return transactionTemplate.execute({ status ->
        val transaction = object : Transaction {
            override val isRollbackOnly: Boolean
                get() = status.isRollbackOnly
            override fun setRollbackOnly() {
                status.setRollbackOnly()
            }
            override fun onCommit(callback: suspend () -> Unit) {
                callbacks.addOnCommit(callback)
            }
            override fun onRollback(callback: suspend () -> Unit) {
                callbacks.addOnRollback(callback)
            }
        }
        runBlocking(context) {
            // Bridges between the blocking TransactionTemplate.execute call and the suspending block, while propagating
            // the transaction context into the coroutine.
            //
            // Note: On pre-Java 24 virtual threads, runBlocking will pin the carrier thread for the entire duration of
            // the block, eliminating the scalability benefits of virtual threads.
            block(transaction)
        }
    }, transactionContext)
}

/**
 * Global transaction options that are applied to all transactions by default.
 */
private val globalTransactionOptions = AtomicReference(TransactionOptions())

/**
 * Coroutine context element for transaction options that are applied to all transactions started in the current
 * coroutine context.
 */
private class Scoped(val options: TransactionOptions) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<Scoped>
}

/**
 * Thread-local transaction options that are applied to all transactions started in the current thread.
 */
private val localTransactionOptions: ThreadLocal<TransactionOptions?> = ThreadLocal.withInitial { null }

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
fun setGlobalTransactionOptions(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
) {
    val defaults = TransactionOptions()
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
 * This function is intended to be used in combination with [transaction].
 *
 * @param propagation The transaction propagation behavior.
 * @param isolation The transaction isolation level.
 * @param timeoutSeconds The transaction timeout in seconds.
 * @param readOnly Whether the transaction is read-only.
 * @param block The coroutine code to execute.
 * @return The result of executing [block].
 * @since 1.6
 */
suspend fun <T> withTransactionOptions(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: suspend () -> T,
): T {
    val currentContext = currentCoroutineContext()
    val current = currentContext[Scoped]?.options ?: globalTransactionOptions.get()
    val scoped = TransactionOptions().copy(
        propagation = propagation ?: current.propagation,
        isolation = isolation ?: current.isolation,
        timeoutSeconds = timeoutSeconds ?: current.timeoutSeconds,
        readOnly = readOnly ?: current.readOnly,
    )
    return withContext(
        Scoped(scoped) +
            localTransactionOptions.asContextElement(scoped), // Make the defaults available via the ThreadLocal in case the blocking variant is invoked from suspend context.
    ) { block() }
}

/**
 * Set the default transaction options for the current thread.
 *
 * This function is intended to be used in combination with [transactionBlocking].
 *
 * @param propagation The transaction propagation behavior.
 * @param isolation The transaction isolation level.
 * @param timeoutSeconds The transaction timeout in seconds.
 * @param readOnly Whether the transaction is read-only.
 * @param block The code to execute.
 * @return The result of executing [block].
 * @since 1.6
 */
fun <T> withTransactionOptionsBlocking(
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    block: () -> T,
): T {
    val previous = localTransactionOptions.get()
    val current = previous ?: globalTransactionOptions.get()
    localTransactionOptions.set(
        TransactionOptions(
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
