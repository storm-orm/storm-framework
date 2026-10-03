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

import java.sql.Connection;
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
            jdbcContext.connectionGuard().acquire(connection);
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
            // Every connection handed out under the context registered an access, including one whose frame has
            // ended since.
            jdbcContext.connectionGuard().release();
            if (jdbcContext.currentConnection() == connection) {
                // If this connection is the current transaction connection, do not close it. It will be closed
                // when the outermost transaction ends.
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
}
