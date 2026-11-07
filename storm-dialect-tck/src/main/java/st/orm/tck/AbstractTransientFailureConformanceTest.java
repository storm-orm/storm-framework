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

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static st.orm.TransactionIsolation.SERIALIZABLE;
import static st.orm.TransactionPropagation.REQUIRED;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.PersistenceException;
import st.orm.Transaction;
import st.orm.TransactionOptions;
import st.orm.TransientPersistenceException;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * Transient failure conformance: a failure that stems from concurrent activity, on which running the whole
 * transaction again may succeed, reaches the caller as a {@link TransientPersistenceException}, and a failure that
 * does not, never does.
 *
 * <p>The deadlock case runs two transactions on two threads that update the same two rows in opposite order, so
 * each waits for the row the other holds; the database ends one of them. The serialization case runs two
 * serializable transactions on two threads that both read a row and then both update it, so the second write
 * conflicts with a read the first one's update invalidated; the database ends one of them, as a serialization
 * failure or, on a database that serializes with locks, as a deadlock. In both cases one transaction commits and
 * the other fails with {@link TransientPersistenceException}, and the rows hold the winner's writes alone. A
 * database with one writer at a time says so through {@link #singleWriter()}.</p>
 *
 * <p>The cases that must not classify as transient: a constraint violation, a statement on a connection that is
 * gone, and a lock wait timeout, which some databases answer by rolling back only the statement. The lock wait
 * case takes the statements that make the database give up waiting from {@link #lockWaitTimeoutStatements()}.</p>
 *
 * <p>A database that cannot produce a case says so through {@link #deadlockUnsupportedReason()} or
 * {@link #serializationFailureUnsupportedReason()}, which skips that case with the reason as its message. The
 * suite creates its own table, {@value #TABLE}, and runs without the per-test rollback, as the cases commit.</p>
 */
public abstract class AbstractTransientFailureConformanceTest {

    /**
     * The table the suite creates and fills with two rows, {@code 1} and {@code 2}, both with amount {@code 0}.
     */
    protected static final String TABLE = "transient_row";

    private static final int TIMEOUT_SECONDS = 60;

    protected DataSource dataSource;

    /**
     * {@code @StormTest} resolves the data source as a parameter, so it is bound here rather than injected into the
     * field directly.
     */
    @BeforeEach
    final void createTable(DataSource dataSource) throws Exception {
        this.dataSource = dataSource;
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("DROP TABLE " + TABLE);
            } catch (SQLException absent) {
                // The table is left from an earlier run only when that run was interrupted.
            }
            statement.execute("CREATE TABLE " + TABLE + " (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)");
            statement.execute("INSERT INTO " + TABLE + " (id, amount) VALUES (1, 0)");
            statement.execute("INSERT INTO " + TABLE + " (id, amount) VALUES (2, 0)");
            if (!connection.getAutoCommit()) {
                connection.commit();
            }
        }
        awaitTable();
    }

    @AfterEach
    final void dropTable() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE " + TABLE);
        }
    }

    /**
     * Waits until a transaction that starts now reads the table the suite just created. Returns at once by default;
     * a database whose snapshots refuse a table younger than they are waits here.
     */
    protected void awaitTable() throws Exception {
    }

    /**
     * Why the database cannot produce a deadlock between two transactions, or empty when it can.
     */
    protected Optional<String> deadlockUnsupportedReason() {
        return Optional.empty();
    }

    /**
     * Why the database cannot produce a serialization failure between two transactions, or empty when it can.
     */
    protected Optional<String> serializationFailureUnsupportedReason() {
        return Optional.empty();
    }

    /**
     * Whether the database has one writer at a time and refuses a second writer outright while the first holds the
     * write lock, under the code it reports for a lock wait that ran out. The serialization case then has the second
     * transaction write once the first one committed, which such a database reports as the serialization failure
     * it is.
     */
    protected boolean singleWriter() {
        return false;
    }

    /**
     * The statements that, run in order in a transaction, make the database give up waiting for the lock on row
     * {@code 1} within a few seconds: a setting for the lock wait, if the database has one, and a statement that
     * locks row {@code 1}. Empty when the database has no lock wait to give up on, which skips the case.
     */
    protected List<String> lockWaitTimeoutStatements() {
        return List.of();
    }

    /**
     * A template on Storm's own JDBC connection and transaction handling, whatever the module's test classpath
     * registers.
     */
    protected static ORMTemplate template(DataSource dataSource) {
        return ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
    }

    @Test
    public void aDeadlockIsTransient() throws Exception {
        var reason = deadlockUnsupportedReason();
        assumeTrue(reason.isEmpty(), reason.orElse(""));
        var orm = template(dataSource);
        var options = new TransactionOptions(REQUIRED, null, TIMEOUT_SECONDS, false);
        var failures = runConcurrently(options,
                (transaction, barrier) -> {
                    add(orm, 1, 1);
                    barrier.await(TIMEOUT_SECONDS, SECONDS);
                    add(orm, 2, 1);
                },
                (transaction, barrier) -> {
                    add(orm, 2, 10);
                    barrier.await(TIMEOUT_SECONDS, SECONDS);
                    add(orm, 1, 10);
                });
        int winner = assertOneTransientFailure(failures);
        int expected = winner == 0 ? 1 : 10;
        assertEquals(expected, amount(orm, 1), "the winner's write to row 1 alone");
        assertEquals(expected, amount(orm, 2), "the winner's write to row 2 alone");
    }

    @Test
    public void aSerializationFailureIsTransient() throws Exception {
        var reason = serializationFailureUnsupportedReason();
        assumeTrue(reason.isEmpty(), reason.orElse(""));
        assertOneSerializationFailure(dataSource,
                new TransactionOptions(REQUIRED, SERIALIZABLE, TIMEOUT_SECONDS, false));
    }

    /**
     * Runs two transactions with the given options on two threads that each read row {@code 1} and, once both have
     * read it, update it. Asserts that one of them commits and the other fails with
     * {@link TransientPersistenceException}, and that the row holds the winner's write alone.
     *
     * <p>On a database with {@linkplain #singleWriter() one writer at a time}, the second transaction writes once
     * the first one committed.</p>
     *
     * @param dataSource the data source of the transactions, such as one whose connections are set to an isolation
     *                   level the options cannot state.
     * @param options the options of both transactions.
     */
    protected final void assertOneSerializationFailure(DataSource dataSource, TransactionOptions options)
            throws Exception {
        var orm = template(dataSource);
        boolean singleWriter = singleWriter();
        var firstCommitted = new CountDownLatch(1);
        var failures = runConcurrently(options,
                (transaction, barrier) -> {
                    transaction.onCommit(firstCommitted::countDown);
                    amount(orm, 1);
                    barrier.await(TIMEOUT_SECONDS, SECONDS);
                    add(orm, 1, 1);
                },
                (transaction, barrier) -> {
                    amount(orm, 1);
                    barrier.await(TIMEOUT_SECONDS, SECONDS);
                    if (singleWriter && !firstCommitted.await(TIMEOUT_SECONDS, SECONDS)) {
                        throw new TimeoutException("The first transaction did not commit.");
                    }
                    add(orm, 1, 10);
                });
        int winner = assertOneTransientFailure(failures);
        assertEquals(winner == 0 ? 1 : 10, amount(orm, 1), "the winner's write alone");
    }

    @Test
    public void aConstraintViolationIsNotTransient() {
        var orm = template(dataSource);
        var thrown = assertThrows(PersistenceException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, TIMEOUT_SECONDS, false),
                transaction -> execute(orm, "INSERT INTO " + TABLE + " (id, amount) VALUES (1, 0)")));
        assertNotTransient(thrown);
    }

    @Test
    public void aStatementOnALostConnectionIsNotTransient() {
        var losing = new LosingDataSource(dataSource);
        var orm = template(losing);
        var thrown = assertThrows(PersistenceException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, TIMEOUT_SECONDS, false),
                transaction -> {
                    add(orm, 1, 1);
                    losing.loseConnection();
                    return add(orm, 2, 1);
                }));
        assertNotTransient(thrown);
    }

    @Test
    public void aLockWaitTimeoutIsNotTransient() throws SQLException {
        var statements = lockWaitTimeoutStatements();
        assumeTrue(!statements.isEmpty(), "The database has no lock wait to give up on.");
        var orm = template(dataSource);
        // The holder is a plain connection rather than a Storm transaction: it is idle by design while the waiter
        // waits, which Storm would report as a transaction that holds its connection idle.
        try (var holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var statement = holder.createStatement()) {
                statement.executeUpdate("UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
            }
            try {
                // The waiter takes no timeout: a driver may apply it as the time to wait for a lock, and the wait
                // the case gives up on is the database's own.
                var thrown = assertThrows(PersistenceException.class,
                        () -> TransactionRunner.<Object, RuntimeException>execute(
                                new TransactionOptions(REQUIRED, null, null, false),
                                waiter -> {
                                    statements.forEach(statement -> execute(orm, statement));
                                    return null;
                                }));
                assertNotTransient(thrown);
            } finally {
                holder.rollback();
            }
        }
        assertEquals(0, amount(orm, 1));
    }

    private static void assertNotTransient(PersistenceException thrown) {
        assertFalse(thrown instanceof TransientPersistenceException, () -> "not transient: " + thrown);
        assertNotNull(sqlException(thrown), () -> "the database's error is the cause of " + thrown);
    }

    /**
     * Asserts that one transaction failed with a {@link TransientPersistenceException} whose cause is the database's
     * error, and that the other one committed.
     *
     * @return the index of the transaction that committed.
     */
    private static int assertOneTransientFailure(List<@Nullable Throwable> failures) {
        int failed = failures.get(0) != null ? 0 : 1;
        var failure = failures.get(failed);
        assertNotNull(failure, "one transaction fails");
        assertNull(failures.get(1 - failed), () -> "the other transaction commits: " + failures);
        if (!(failure instanceof TransientPersistenceException)) {
            throw new AssertionError("The failing transaction fails with a TransientPersistenceException.", failure);
        }
        assertNotNull(sqlException(failure), () -> "the database's error is the cause of " + failure);
        return 1 - failed;
    }

    private static @Nullable SQLException sqlException(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }

    @FunctionalInterface
    private interface ConcurrentWork {
        void run(Transaction transaction, CyclicBarrier barrier)
                throws InterruptedException, BrokenBarrierException, TimeoutException;
    }

    /**
     * Runs the two pieces of work each in a transaction of its own, on a thread of its own, and returns what each
     * failed with, {@code null} for a transaction that committed. A transaction that fails before it reaches the
     * barrier leaves the other one waiting there until it times out, which fails the case.
     */
    private static List<@Nullable Throwable> runConcurrently(TransactionOptions options, ConcurrentWork first,
                                                             ConcurrentWork second) throws InterruptedException {
        var barrier = new CyclicBarrier(2);
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            for (var work : List.of(first, second)) {
                futures.add(executor.submit(() -> TransactionRunner.<Object, Exception>execute(options, transaction -> {
                    work.run(transaction, barrier);
                    return null;
                })));
            }
            List<@Nullable Throwable> failures = new ArrayList<>();
            for (var future : futures) {
                try {
                    future.get(2L * TIMEOUT_SECONDS, SECONDS);
                    failures.add(null);
                } catch (ExecutionException e) {
                    assertFalse(e.getCause() instanceof BrokenBarrierException
                            || e.getCause() instanceof TimeoutException,
                            () -> "both transactions reach the barrier: " + e.getCause());
                    failures.add(e.getCause());
                } catch (TimeoutException e) {
                    throw new AssertionError("The transactions did not complete.", e);
                }
            }
            return failures;
        }
    }

    private static Object add(ORMTemplate orm, int id, int amount) {
        return execute(orm, "UPDATE " + TABLE + " SET amount = amount + " + amount + " WHERE id = " + id);
    }

    /**
     * Executes the statement, reading the rows of a {@code SELECT} rather than counting updates.
     */
    private static Object execute(ORMTemplate orm, String statement) {
        var query = orm.query(TemplateString.of(statement));
        if (statement.regionMatches(true, 0, "SELECT", 0, "SELECT".length())) {
            query.getResultList();
        } else {
            query.executeUpdate();
        }
        return null;
    }

    private static int amount(ORMTemplate orm, int id) {
        var row = orm.query(TemplateString.of("SELECT amount FROM " + TABLE + " WHERE id = " + id)).getSingleResult();
        return ((Number) row[0]).intValue();
    }

    /**
     * A data source whose connections can be lost: {@link #loseConnection()} closes the connection it handed out last
     * underneath its holder, which is what a connection the network or the server dropped looks
     * like to the driver.
     */
    private static final class LosingDataSource implements DataSource {

        private final DataSource dataSource;
        private @Nullable Connection last;

        LosingDataSource(DataSource dataSource) {
            this.dataSource = dataSource;
        }

        void loseConnection() {
            assertNotNull(last, "a connection was handed out");
            try {
                last.close();
            } catch (SQLException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = dataSource.getConnection();
            last = connection;
            return connection;
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return getConnection();
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
