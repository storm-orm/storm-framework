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
package st.orm.tck;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.TransactionPropagation.NOT_SUPPORTED;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.PersistenceException;
import st.orm.TransactionOptions;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;
import st.orm.tck.model.Vet;

/**
 * Read-only transaction conformance: a read-only transaction reaches the server as one, so the server refuses a
 * write in it, and the transaction leaves its connection as it found it, so the next borrower of that connection
 * writes again.
 *
 * <p>The read-only flag travels through the driver, which is where it may be lost: a driver may leave the server
 * unaware of the flag, and the server then executes the write the application meant to rule out. The suite states
 * the behaviour Storm's transaction handling produces on the driver the dialect module tests with, and a dialect
 * whose driver does not carry the flag says so by overriding {@link #enforcesReadOnly()}, which keeps the exception
 * in one place instead of leaving a test absent.</p>
 *
 * <p>The propagation cases assert the part of the contract that is independent of enforcement: a
 * {@code REQUIRES_NEW} or {@code NOT_SUPPORTED} frame opens a connection of its own and does not inherit the
 * enclosing frame's mode, while a joined {@code REQUIRED} frame runs on the enclosing frame's connection and cannot
 * lift its mode. On a dialect that enforces the flag the server observes each of these.</p>
 *
 * <p>The suite commits, so a dialect module runs it with {@code rollback = false} and the suite removes the rows it
 * inserts. The shared seed data holds six vets; the rows the suite inserts carry the last name
 * {@value #INSERTED_LAST_NAME}, on which it removes them. The templates the suite builds name Storm's own JDBC
 * connection and transaction providers, so a provider a module registers for its other tests through
 * {@code META-INF/services} does not take the transaction out of Storm's hands.</p>
 */
public abstract class AbstractTransactionConformanceTest {

    private static final String INSERTED_LAST_NAME = "ReadOnlyConformance";

    private static final TransactionOptions READ_ONLY = new TransactionOptions(REQUIRED, null, null, true);
    private static final TransactionOptions READ_WRITE = new TransactionOptions(REQUIRED, null, null, false);
    private static final TransactionOptions READ_ONLY_NEW = new TransactionOptions(REQUIRES_NEW, null, null, true);
    private static final TransactionOptions READ_WRITE_NEW = new TransactionOptions(REQUIRES_NEW, null, null, false);
    private static final TransactionOptions READ_WRITE_NOT_SUPPORTED =
            new TransactionOptions(NOT_SUPPORTED, null, null, false);

    protected DataSource dataSource;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly.
     */
    @BeforeEach
    final void bindDataSource(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @AfterEach
    final void removeInsertedRows() {
        removeInsertedRows(template(dataSource));
    }

    /**
     * A template on Storm's own JDBC connection and transaction handling, whatever the module's test classpath
     * registers.
     */
    private static ORMTemplate template(DataSource dataSource) {
        return ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
    }

    /**
     * Whether the driver accepts {@link Connection#setReadOnly} at all. A driver that fixes the mode at connect
     * time refuses the call, and no read-only transaction can open on it.
     */
    protected boolean supportsReadOnlyTransactions() {
        return true;
    }

    /**
     * Whether the server refuses a write in a transaction the driver marked read-only. A driver that keeps the flag
     * to itself leaves the server executing the write, and the enforcement cases do not apply.
     */
    protected boolean enforcesReadOnly() {
        return true;
    }

    /**
     * The SQL state the server reports for a write in a read-only transaction. The SQL standard assigns
     * {@code 25006}, which is what the databases that implement read-only transactions report.
     */
    protected String readOnlyViolationSqlState() {
        return "25006";
    }

    @Test
    public void readOnlyTransactionReads() {
        assumeTrue(supportsReadOnlyTransactions());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        long counted = transaction(READ_ONLY, () -> orm.entity(Vet.class).count());
        assertEquals(seeded, counted);
    }

    @Test
    public void readOnlyTransactionRefusesAWrite() {
        assumeTrue(supportsReadOnlyTransactions());
        assumeTrue(enforcesReadOnly());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        var thrown = assertThrows(PersistenceException.class,
                () -> transaction(READ_ONLY, () -> insertVet(orm)));
        assertEquals(readOnlyViolationSqlState(), sqlState(thrown));
        assertEquals(seeded, orm.entity(Vet.class).count());
    }

    @Test
    public void requiresNewInsideReadOnlyOuterWritesOnItsOwnConnection() {
        assumeTrue(supportsReadOnlyTransactions());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        transaction(READ_ONLY, () -> {
            assertEquals(seeded, orm.entity(Vet.class).count());
            transaction(READ_WRITE_NEW, () -> insertVet(orm));
            return null;
        });
        assertEquals(seeded + 1, orm.entity(Vet.class).count());
    }

    @Test
    public void outerStaysReadOnlyAfterRequiresNewCompletes() {
        assumeTrue(supportsReadOnlyTransactions());
        assumeTrue(enforcesReadOnly());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        var thrown = assertThrows(PersistenceException.class, () -> transaction(READ_ONLY, () -> {
            transaction(READ_WRITE_NEW, () -> insertVet(orm));
            return insertVet(orm);
        }));
        assertEquals(readOnlyViolationSqlState(), sqlState(thrown));
        // The inner frame committed on its own connection before the outer frame's write was refused.
        assertEquals(seeded + 1, orm.entity(Vet.class).count());
    }

    @Test
    public void readOnlyRequiresNewInsideWritableOuterIsRefusedAndTheOuterStillWrites() {
        assumeTrue(supportsReadOnlyTransactions());
        assumeTrue(enforcesReadOnly());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        transaction(READ_WRITE, () -> {
            var thrown = assertThrows(PersistenceException.class,
                    () -> transaction(READ_ONLY_NEW, () -> insertVet(orm)));
            assertEquals(readOnlyViolationSqlState(), sqlState(thrown));
            return insertVet(orm);
        });
        assertEquals(seeded + 1, orm.entity(Vet.class).count());
    }

    @Test
    public void joinedFrameCannotLiftTheOwnersReadOnly() {
        assumeTrue(supportsReadOnlyTransactions());
        assumeTrue(enforcesReadOnly());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        // A REQUIRED frame joins the enclosing transaction and runs on its connection; asking for read-write there
        // changes nothing, since the mode belongs to the frame that owns the connection.
        var thrown = assertThrows(PersistenceException.class,
                () -> transaction(READ_ONLY, () -> transaction(READ_WRITE, () -> insertVet(orm))));
        assertEquals(readOnlyViolationSqlState(), sqlState(thrown));
        assertEquals(seeded, orm.entity(Vet.class).count());
    }

    @Test
    public void notSupportedInsideReadOnlyOuterWritesOutsideTheTransaction() {
        assumeTrue(supportsReadOnlyTransactions());
        var orm = template(dataSource);
        long seeded = orm.entity(Vet.class).count();
        transaction(READ_ONLY, () -> transaction(READ_WRITE_NOT_SUPPORTED, () -> insertVet(orm)));
        assertEquals(seeded + 1, orm.entity(Vet.class).count());
    }

    @Test
    public void theConnectionLeavesAReadOnlyTransactionReadWrite() throws SQLException {
        assumeTrue(supportsReadOnlyTransactions());
        // One physical connection serves every request, as a pool of one would, so the second transaction runs on
        // the connection the first one marked read-only. The write it performs proves the first transaction handed
        // the connection back read-write; the driver's own flag is checked as well, since the server-side mode is
        // tied to it.
        try (var single = new SingleConnectionDataSource(dataSource)) {
            var orm = template(single);
            long seeded = orm.entity(Vet.class).count();
            transaction(READ_ONLY, () -> orm.entity(Vet.class).count());
            assertFalse(single.physical.isReadOnly());
            transaction(READ_WRITE, () -> insertVet(orm));
            assertEquals(seeded + 1, orm.entity(Vet.class).count());
            removeInsertedRows(orm);
        }
    }

    private static <R> R transaction(TransactionOptions options, TransactionalWork<R> work) {
        return TransactionRunner.<R, RuntimeException>execute(options, transaction -> work.run());
    }

    private static Object insertVet(ORMTemplate orm) {
        orm.entity(Vet.class).insert(Vet.builder().firstName("Read").lastName(INSERTED_LAST_NAME).build());
        return null;
    }

    private static void removeInsertedRows(ORMTemplate orm) {
        orm.query(TemplateString.of("DELETE FROM vet WHERE last_name = '" + INSERTED_LAST_NAME + "'")).executeUpdate();
    }

    /**
     * The SQL state of the first {@link SQLException} in the cause chain.
     */
    private static String sqlState(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                assertNotNull(sqlException.getSQLState(), "SQL state of " + sqlException);
                return sqlException.getSQLState();
            }
        }
        throw new AssertionError("No SQLException in the cause chain of " + thrown, thrown);
    }

    @FunctionalInterface
    private interface TransactionalWork<R> {
        R run();
    }

    /**
     * A data source over one physical connection. Every request hands out a proxy over that connection whose
     * {@code close()} returns it for the next request instead of closing it, which is what a pool of one does.
     * Closing the data source closes the physical connection.
     */
    private static final class SingleConnectionDataSource implements DataSource, AutoCloseable {

        private final Connection physical;

        SingleConnectionDataSource(DataSource dataSource) throws SQLException {
            this.physical = dataSource.getConnection();
        }

        @Override
        public Connection getConnection() {
            InvocationHandler handler = (proxy, method, arguments) -> {
                if (method.getName().equals("close") && method.getParameterCount() == 0) {
                    return null;
                }
                try {
                    return method.invoke(physical, arguments);
                } catch (InvocationTargetException e) {
                    throw e.getCause();
                }
            };
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, handler);
        }

        @Override
        public Connection getConnection(String username, String password) {
            return getConnection();
        }

        @Override
        public void close() throws SQLException {
            physical.close();
        }

        @Override
        public PrintWriter getLogWriter() {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLogWriter(PrintWriter out) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void setLoginTimeout(int seconds) {
            throw new UnsupportedOperationException();
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            throw new SQLFeatureNotSupportedException();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            if (iface.isInstance(this)) {
                return iface.cast(this);
            }
            throw new SQLException("Not a wrapper for " + iface.getName() + ".");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return iface.isInstance(this);
        }
    }
}
