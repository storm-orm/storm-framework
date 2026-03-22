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

/**
 * Interface for programmatic transaction management with ORMTemplate.
 *
 * Provides methods to check if the transaction is rollback-only and to set it as such.
 * This interface extends ORMTemplate to allow access to ORM operations within a transaction context.
 *
 * @property isRollbackOnly Indicates if the transaction is marked for rollback only.
 * @property setRollbackOnly Marks the transaction as rollback-only.
 * @since 1.5
 */
interface Transaction {

    /**
     * Indicates if the transaction is marked for rollback only.
     */
    val isRollbackOnly: Boolean

    /**
     * Marks the transaction as rollback-only, preventing any further commits.
     */
    fun setRollbackOnly()

    /**
     * Registers a callback that will be invoked after the physical transaction commits successfully.
     *
     * If this scope is joined to an outer transaction (e.g. via [TransactionPropagation.REQUIRED] or
     * [TransactionPropagation.NESTED]), the callback is deferred to the outermost physical transaction's commit.
     * [TransactionPropagation.REQUIRES_NEW] scopes fire their own callbacks independently.
     *
     * Multiple callbacks are executed in registration order. If a callback throws, remaining callbacks still execute;
     * the first exception is surfaced with others added as suppressed.
     *
     * The callback accepts a `suspend` function, allowing both regular and suspend lambdas. When registered within a
     * suspend [transaction], the callback executes in the enclosing coroutine context. When registered within a
     * [transactionBlocking], the callback is bridged via `runBlocking` with an empty coroutine context.
     *
     * @param callback the callback to invoke after commit.
     * @since 1.11
     */
    fun onCommit(callback: suspend () -> Unit)

    /**
     * Registers a callback that will be invoked after the physical transaction rolls back.
     *
     * Rollback may be triggered by an exception, [setRollbackOnly], or a timeout. If this scope is joined to an
     * outer transaction (e.g. via [TransactionPropagation.REQUIRED] or [TransactionPropagation.NESTED]), the callback
     * is deferred to the outermost physical transaction's rollback. [TransactionPropagation.REQUIRES_NEW] scopes fire
     * their own callbacks independently.
     *
     * Multiple callbacks are executed in registration order. If a callback throws, remaining callbacks still execute;
     * the first exception is surfaced with others added as suppressed.
     *
     * The callback accepts a `suspend` function, allowing both regular and suspend lambdas. When registered within a
     * suspend [transaction], the callback executes in the enclosing coroutine context. When registered within a
     * [transactionBlocking], the callback is bridged via `runBlocking` with an empty coroutine context.
     *
     * @param callback the callback to invoke after rollback.
     * @since 1.11
     */
    fun onRollback(callback: suspend () -> Unit)
}
