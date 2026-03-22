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
package st.orm.template.impl

import kotlinx.coroutines.runBlocking

/**
 * Collects and executes transaction lifecycle callbacks.
 *
 * Callbacks registered via [addOnCommit] and [addOnRollback] are stored in registration order. When
 * [fireCommit] or [fireRollback] is called, the corresponding list is executed sequentially. If any callback
 * throws, remaining callbacks still execute; the first exception is surfaced with subsequent ones added as
 * suppressed.
 *
 * @since 1.11
 */
internal class TransactionCallbacks {
    private val onCommit = mutableListOf<suspend () -> Unit>()
    private val onRollback = mutableListOf<suspend () -> Unit>()

    fun addOnCommit(callback: suspend () -> Unit) {
        onCommit += callback
    }

    fun addOnRollback(callback: suspend () -> Unit) {
        onRollback += callback
    }

    suspend fun fireCommit() {
        execute(onCommit)
    }

    suspend fun fireRollback() {
        execute(onRollback)
    }

    fun fireCommitBlocking() {
        runBlocking { execute(onCommit) }
    }

    fun fireRollbackBlocking() {
        runBlocking { execute(onRollback) }
    }

    private suspend fun execute(callbacks: List<suspend () -> Unit>) {
        var first: Throwable? = null
        for (callback in callbacks) {
            try {
                callback()
            } catch (e: Throwable) {
                if (first == null) first = e else first.addSuppressed(e)
            }
        }
        first?.let { throw it }
    }
}
