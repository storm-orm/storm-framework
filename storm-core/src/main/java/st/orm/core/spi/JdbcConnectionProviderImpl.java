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

import static java.lang.System.identityHashCode;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;

/**
 * The default connection provider, binding connections to the active {@link JdbcTransactionContext}.
 *
 * <p>This provider is platform-neutral: outside a programmatic transaction, connections are acquired and
 * closed directly on the data source. Integrations that bind connections to an external transaction subsystem
 * supply their own {@link ConnectionProvider} via the template builder.</p>
 *
 * <p>The provider declares the auto-commit state in which the data source hands out connections, auto-commit
 * by default, and verifies every fresh connection against the declaration in both directions.</p>
 *
 * @since 1.13
 */
public final class JdbcConnectionProviderImpl implements ConnectionProvider {

    private final boolean manualCommitConnections;

    public JdbcConnectionProviderImpl() {
        this(false);
    }

    /**
     * Creates a provider that declares the auto-commit state in which the data source hands out connections.
     *
     * <p>The declared mode is verified in both directions on every fresh connection, so a wrong declaration
     * fails fast in every combination. For a declared manual-commit pool the transactional path performs no
     * auto-commit flips and releases connections in their arrived state; non-transactional connections get
     * auto-commit enabled while Storm uses them and restored before release, so each statement commits.</p>
     *
     * @param manualCommitConnections whether the data source hands out connections with auto-commit disabled.
     * @since 1.14
     */
    public JdbcConnectionProviderImpl(boolean manualCommitConnections) {
        this.manualCommitConnections = manualCommitConnections;
    }

    @Override
    public Connection getConnection(DataSource dataSource, @Nullable TransactionContext context) {
        if (context != null) {
            if (!(context instanceof JdbcTransactionContext jdbcContext)) {
                throw new IllegalArgumentException("Transaction context must be of type JdbcTransactionContext.");
            }
            var connection = jdbcContext.getConnection(dataSource, manualCommitConnections);
            ConcurrencyDetector.beforeAccess(connection, context);
            return connection;
        }
        // If no programmatic transaction is active, obtain a new connection from the data source.
        return getRegularConnection(dataSource);
    }

    @Override
    public void releaseConnection(Connection connection, DataSource dataSource,
                                  @Nullable TransactionContext context) {
        if (context != null) {
            if (!(context instanceof JdbcTransactionContext jdbcContext)) {
                throw new IllegalArgumentException("Transaction context must be of type JdbcTransactionContext.");
            }
            if (jdbcContext.currentConnection() == connection) {
                // If this connection is the current transaction connection, do not close it. It will be closed
                // when the outermost transaction ends.
                ConcurrencyDetector.afterAccess(connection, context);
                return;
            }
        }
        releaseRegularConnection(connection);
    }

    private Connection getRegularConnection(DataSource dataSource) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (Throwable t) {
            throw new PersistenceException("Failed to get connection from DataSource.", t);
        }
        JdbcTransactionContext.verifyArrivedAutoCommitState(connection, manualCommitConnections);
        // Non-transactional statements execute in auto-commit mode. On a declared manual-commit pool the
        // connection arrives with auto-commit disabled, so auto-commit is enabled here and restored on release;
        // leaving it disabled would let the pool roll back uncommitted statements on release, silently losing
        // writes.
        if (manualCommitConnections) {
            try {
                connection.setAutoCommit(true);
            } catch (Throwable t) {
                try {
                    connection.close();
                } catch (Throwable ignore) {}
                throw new PersistenceException("Failed to get connection from DataSource.", t);
            }
        }
        return connection;
    }

    private void releaseRegularConnection(Connection connection) {
        try {
            try {
                // Return the connection to the pool in its arrived state.
                if (manualCommitConnections) {
                    connection.setAutoCommit(false);
                }
            } finally {
                connection.close();
            }
        } catch (Throwable t) {
            throw new PersistenceException("Failed to release connection.", t);
        }
    }

    /**
     * Detects concurrent access to transaction-scoped connections.
     *
     * <p>A transaction's connection serves one caller at a time. An access holds the connection from acquisition
     * until its statement is released, and is owned by the transaction context and by the caller that issued it.
     * The caller is the current thread, unless an integration installs a caller identity through
     * {@link #callerHolder()}: a coroutine keeps its identity when it resumes on another thread, and a coroutine
     * started concurrently, such as with {@code launch} or {@code async}, gets its own.</p>
     *
     * <p>The owning caller can access the connection again while it holds it (re-entrancy), and any caller can
     * release an access, so a statement handed to another thread can be closed there. Another caller that accesses
     * the connection while it is held fails fast instead of waiting behind the current statement.</p>
     */
    public static final class ConcurrencyDetector {

        private static final class ConnectionIdentity extends WeakReference<Connection> {
            private final int id;

            ConnectionIdentity(Connection connection, ReferenceQueue<Connection> queue) {
                super(connection, queue);
                this.id = identityHashCode(connection);
            }

            @Override
            public int hashCode() {
                return id;
            }

            @Override
            public boolean equals(Object other) {
                return other instanceof ConnectionIdentity otherIdentity
                        && this.get() == otherIdentity.get()
                        && this.get() != null;
            }
        }

        /**
         * The holder of a connection; only read and written inside the map's atomic compute functions.
         */
        private static final class Owner {
            final TransactionContext context;
            final Object caller;
            int depth = 1;

            Owner(TransactionContext context, Object caller) {
                this.context = context;
                this.caller = caller;
            }
        }

        private static final ThreadLocal<Object> CALLER = new ThreadLocal<>();
        private static final ReferenceQueue<Connection> QUEUE = new ReferenceQueue<>();
        private static final Map<ConnectionIdentity, Owner> OWNERS = new ConcurrentHashMap<>();

        private ConcurrencyDetector() {
        }

        /**
         * Returns the thread local that holds the identity of the current caller.
         *
         * <p>Intended for integrations whose units of work move between threads, such as coroutine context
         * elements, which install the identity of the running unit for the duration of each of its slices. When
         * the holder is empty, the current thread is the caller.</p>
         *
         * @return the thread local holding the identity of the current caller.
         * @since 1.15
         */
        public static ThreadLocal<Object> callerHolder() {
            return CALLER;
        }

        private static Object currentCaller() {
            var caller = CALLER.get();
            return caller != null ? caller : Thread.currentThread();
        }

        private static void reap() {
            while (true) {
                var ref = QUEUE.poll();
                if (!(ref instanceof ConnectionIdentity identity)) {
                    break;
                }
                OWNERS.remove(identity);
            }
        }

        public static void beforeAccess(Connection connection, TransactionContext context) {
            reap();
            var caller = currentCaller();
            OWNERS.compute(new ConnectionIdentity(connection, QUEUE), (key, owner) -> {
                if (owner == null) {
                    return new Owner(context, caller);
                }
                if (owner.context == context && owner.caller == caller) {
                    owner.depth++;
                    return owner;
                }
                throw new PersistenceException(("Concurrent access on %s: another caller in the transaction is "
                        + "still using its connection, which serves one caller at a time. Await concurrent work "
                        + "before starting the next, or give it its own transaction.").formatted(connection));
            });
        }

        public static void afterAccess(Connection connection, TransactionContext context) {
            reap();
            OWNERS.computeIfPresent(new ConnectionIdentity(connection, QUEUE), (key, owner) -> {
                if (owner.context != context) {
                    return owner;
                }
                return --owner.depth == 0 ? null : owner;
            });
        }
    }
}
