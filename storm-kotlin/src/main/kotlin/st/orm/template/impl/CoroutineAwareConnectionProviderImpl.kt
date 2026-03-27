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

import st.orm.PersistenceException
import st.orm.core.spi.ConnectionProvider
import st.orm.core.spi.TransactionContext
import java.lang.System.identityHashCode
import java.lang.ref.ReferenceQueue
import java.lang.ref.WeakReference
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import javax.sql.DataSource

/**
 * @since 1.5
 */
class CoroutineAwareConnectionProviderImpl : ConnectionProvider {

    override fun getConnection(dataSource: DataSource, context: TransactionContext?): Connection {
        if (context != null) {
            require(context is JdbcTransactionContext) { "Transaction context must be of type JdbcTransactionContext." }
            validateState()
            val connection = context.getConnection(dataSource)
            ConcurrencyDetector.beforeAccess(connection, context)
            return connection
        }
        // If no programmatic transaction is active, obtain a new connection from the data source.
        return getRegularConnection(dataSource)
    }

    override fun releaseConnection(connection: Connection, dataSource: DataSource, context: TransactionContext?) {
        if (context != null) {
            require(context is JdbcTransactionContext) { "Transaction context must be of type JdbcTransactionContext." }
            if (context.currentConnection() == connection) {
                // If this connection is the current transaction connection, do not close it. It will be closed when the
                // outermost transaction ends.
                ConcurrencyDetector.afterAccess(connection, context)
                return
            }
        }
        releaseRegularConnection(connection, dataSource)
    }

    private fun validateState() {
        try {
            if (IS_ACTUAL_TRANSACTION_ACTIVE != null) {
                try {
                    if (IS_ACTUAL_TRANSACTION_ACTIVE.invoke(null) as Boolean) {
                        throw PersistenceException(
                            "Programmatic transactions and Spring managed transactions cannot be mixed when spring-managed transactions are disabled for storm. " +
                                "Use `@EnableTransactionIntegration` to enable spring-managed transactions for storm.",
                        )
                    }
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            }
        } catch (e: PersistenceException) {
            throw e
        } catch (t: Throwable) {
            throw PersistenceException("Failed to validate connection.", t)
        }
    }

    private fun getRegularConnection(dataSource: DataSource): Connection {
        try {
            return if (GET_CONNECTION_METHOD != null) {
                try {
                    GET_CONNECTION_METHOD.invoke(null, dataSource) as Connection
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } else {
                dataSource.connection
            }
        } catch (t: Throwable) {
            throw PersistenceException("Failed to get connection from DataSource.", t)
        }
    }

    private fun releaseRegularConnection(connection: Connection, dataSource: DataSource) {
        try {
            if (RELEASE_CONNECTION_METHOD != null) {
                try {
                    RELEASE_CONNECTION_METHOD.invoke(null, connection, dataSource)
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } else {
                connection.close()
            }
        } catch (t: Throwable) {
            throw PersistenceException("Failed to release connection.", t)
        }
    }

    /**
     * Detects concurrent access to transaction-scoped connections.
     *
     * Ownership is tracked by transaction context identity rather than thread identity, because coroutines may resume
     * on a different virtual thread after suspension (especially with OpenTelemetry or other javaagent instrumentation
     * that wraps dispatched tasks).
     *
     * The same context can access the same connection multiple times (re-entrancy).
     */
    object ConcurrencyDetector {
        private class ConnectionIdentity(connection: Connection, queue: ReferenceQueue<Connection>) : WeakReference<Connection>(connection, queue) {
            private val id = identityHashCode(connection)
            override fun hashCode() = id
            override fun equals(other: Any?) = other is ConnectionIdentity && this.get() === other.get() && this.get() != null
        }

        private data class Owner(var context: TransactionContext? = null, var depth: Int = 0)
        private val queue = ReferenceQueue<Connection>()
        private val owners = ConcurrentHashMap<ConnectionIdentity, Owner>()

        private fun reap() {
            while (true) {
                val ref = queue.poll() as? ConnectionIdentity ?: break
                owners.remove(ref)
            }
        }

        fun beforeAccess(connection: Connection, context: TransactionContext) {
            reap()
            val key = ConnectionIdentity(connection, queue)
            val owner = owners.computeIfAbsent(key) { Owner() }
            synchronized(owner) {
                when (owner.context) {
                    null -> {
                        owner.context = context
                        owner.depth = 1
                    }
                    context -> owner.depth++
                    else -> throw PersistenceException("Concurrent access on $connection.")
                }
            }
        }

        fun afterAccess(connection: Connection, context: TransactionContext) {
            reap()
            val key = ConnectionIdentity(connection, queue)
            val owner = owners[key] ?: return
            var clear = false
            synchronized(owner) {
                if (owner.context !== context) return
                if (--owner.depth == 0) {
                    owner.context = null
                    clear = true
                }
            }
            if (clear) owners.remove(key, owner)
        }
    }

    companion object {
        private val GET_CONNECTION_METHOD: Method?
        private val RELEASE_CONNECTION_METHOD: Method?
        private val IS_ACTUAL_TRANSACTION_ACTIVE: Method?

        init {
            var getConnection: Method?
            var releaseConnection: Method?
            var isActualTransactionActive: Method?
            try {
                val utilsClass = Class.forName("org.springframework.jdbc.datasource.DataSourceUtils")
                val transactionSynchonizationManagerClass =
                    Class.forName("org.springframework.transaction.support.TransactionSynchronizationManager")
                getConnection = utilsClass.getMethod("getConnection", DataSource::class.java)
                releaseConnection =
                    utilsClass.getMethod("releaseConnection", Connection::class.java, DataSource::class.java)
                isActualTransactionActive = transactionSynchonizationManagerClass.getMethod("isActualTransactionActive")
            } catch (_: ClassNotFoundException) {
                getConnection = null
                releaseConnection = null
                isActualTransactionActive = null
            } catch (_: NoSuchMethodException) {
                getConnection = null
                releaseConnection = null
                isActualTransactionActive = null
            }
            GET_CONNECTION_METHOD = getConnection
            RELEASE_CONNECTION_METHOD = releaseConnection
            IS_ACTUAL_TRANSACTION_ACTIVE = isActualTransactionActive
        }
    }
}
