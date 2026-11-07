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

import static java.util.concurrent.TimeUnit.MINUTES;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
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
 * <p>The write skew case runs two serializable transactions that each read the row the other one writes, and only
 * then write, so neither write conflicts with another write: the conflict is a read/write dependency cycle across
 * the two. Where the database reports it, at a statement or at the commit, is a property of the database, which
 * each database states through {@link #writeSkewReport()}; a failure reported at the commit is transient as one
 * reported at a statement is, and a block that states retries reruns on it.</p>
 *
 * <p>A database that cannot produce a case says so through {@link #deadlockUnsupportedReason()} or
 * {@link #serializationFailureUnsupportedReason()}, which skips that case with the reason as its message. The
 * suite creates its own table, {@value #TABLE}, and runs without the per-test rollback, as the cases commit.</p>
 */
@Timeout(value = 5, unit = MINUTES)
public abstract class AbstractTransientFailureConformanceTest {

    /**
     * Where a database reports the conflict of two serializable transactions that each read the row the other one
     * writes.
     */
    public enum WriteSkewReport {

        /**
         * A statement of one of the transactions fails, as a deadlock or a serialization failure.
         */
        STATEMENT,

        /**
         * The statements of both transactions run, and the commit of one of them fails with a serialization failure.
         */
        COMMIT,

        /**
         * Both transactions commit: the database's serializable isolation lets the dependency cycle through.
         */
        NONE
    }

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
     * Where the database reports the conflict of the write skew case: at a statement, at the commit, or not at all.
     */
    protected abstract WriteSkewReport writeSkewReport();

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
                (transaction, rendezvous) -> {
                    add(orm, 1, 1);
                    rendezvous.await();
                    add(orm, 2, 1);
                },
                (transaction, rendezvous) -> {
                    add(orm, 2, 10);
                    rendezvous.await();
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

    @Test
    public void aDeadlockedTransactionRerunsAndCommits() throws Exception {
        var reason = deadlockUnsupportedReason();
        assumeTrue(reason.isEmpty(), reason.orElse(""));
        var orm = template(dataSource);
        var outcomes = runConcurrently(new TransactionOptions(REQUIRED, null, TIMEOUT_SECONDS, false), 1,
                (transaction, rendezvous) -> {
                    add(orm, 1, 1);
                    rendezvous.await();
                    add(orm, 2, 1);
                },
                (transaction, rendezvous) -> {
                    add(orm, 2, 10);
                    rendezvous.await();
                    add(orm, 1, 10);
                });
        assertOneRerunAndBothCommit(outcomes);
        assertEquals(11, amount(orm, 1), "both transactions' writes to row 1");
        assertEquals(11, amount(orm, 2), "both transactions' writes to row 2");
    }

    @Test
    public void aSerializationFailureRerunsAndCommits() throws Exception {
        var reason = serializationFailureUnsupportedReason();
        assumeTrue(reason.isEmpty(), reason.orElse(""));
        assertOneSerializationFailureReruns(template(dataSource));
    }

    @Test
    public void aTransientFailureAMapperTranslatesToItsOwnTypeStillReruns() throws Exception {
        var reason = serializationFailureUnsupportedReason();
        assumeTrue(reason.isEmpty(), reason.orElse(""));
        var orm = ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .exceptionMapper((failure, context) -> context.transientFailure() ? new MappedFailure(failure) : null)
                .build();
        assertOneSerializationFailureReruns(orm);
    }

    @Test
    public void aWriteSkewIsReportedWhereTheDatabaseReportsIt() throws Exception {
        var orm = template(dataSource);
        var report = writeSkewReport();
        var outcomes = runWriteSkew(orm, 0);
        if (report == WriteSkewReport.NONE) {
            assertBothCommitAtOnce(outcomes);
            assertEquals(1, amount(orm, 1), "the first transaction's write");
            assertEquals(10, amount(orm, 2), "the second transaction's write");
            return;
        }
        var failures = outcomes.stream().map(WriteSkewOutcome::outcome).map(Outcome::failure).toList();
        int winner = assertOneTransientFailure(failures);
        var loser = outcomes.get(1 - winner);
        assertEquals(report == WriteSkewReport.COMMIT, loser.bodies() == loser.outcome().attempts(),
                () -> "the conflict is reported " + (report == WriteSkewReport.COMMIT
                        ? "at the commit, after the block's statements all ran"
                        : "at a statement") + ": " + outcomes);
        assertEquals(winner == 0 ? 1 : 0, amount(orm, 1), "the winner's write alone");
        assertEquals(winner == 1 ? 10 : 0, amount(orm, 2), "the winner's write alone");
    }

    @Test
    public void aWriteSkewRerunsAndCommits() throws Exception {
        assertWriteSkewReruns(template(dataSource));
    }

    @Test
    public void aWriteSkewAMapperTranslatesToItsOwnTypeStillReruns() throws Exception {
        assertWriteSkewReruns(ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .exceptionMapper((failure, context) -> context.transientFailure() ? new MappedFailure(failure) : null)
                .build());
    }

    /**
     * Runs the write skew case with each transaction rerunning once, and asserts that the transaction that lost ran
     * again and committed, so both rows hold their writes, wherever the database reported the conflict.
     */
    private void assertWriteSkewReruns(ORMTemplate orm) throws Exception {
        var outcomes = runWriteSkew(orm, 1);
        if (writeSkewReport() == WriteSkewReport.NONE) {
            assertBothCommitAtOnce(outcomes);
        } else {
            assertOneRerunAndBothCommit(outcomes.stream().map(WriteSkewOutcome::outcome).toList());
        }
        assertEquals(1, amount(orm, 1), "the first transaction's write");
        assertEquals(10, amount(orm, 2), "the second transaction's write");
    }

    private static void assertBothCommitAtOnce(List<WriteSkewOutcome> outcomes) {
        for (var outcome : outcomes) {
            if (outcome.outcome().failure() != null) {
                throw new AssertionError("Both transactions commit: " + outcomes, outcome.outcome().failure());
            }
            assertEquals(1, outcome.outcome().attempts(), () -> "neither transaction reruns: " + outcomes);
        }
    }

    /**
     * What a transaction of the write skew case ended with, and how many of its attempts ran all their statements.
     */
    private record WriteSkewOutcome(Outcome outcome, int bodies) {}

    /**
     * Runs two serializable transactions on two threads: the first reads row {@code 2} and writes row {@code 1}, the
     * second reads row {@code 1} and writes row {@code 2}. Both read before either writes, and both write before
     * either commits, so the database meets the cycle with both transactions still open. A transaction that cannot
     * write yet, as on a database that serializes with locks, releases the other one when it fails. On a database
     * with {@linkplain #singleWriter() one writer at a time}, the second transaction writes once the first one
     * committed.
     */
    private List<WriteSkewOutcome> runWriteSkew(ORMTemplate orm, int retries) throws InterruptedException {
        boolean singleWriter = singleWriter();
        var firstCommitted = new CountDownLatch(1);
        List<CountDownLatch> written = List.of(new CountDownLatch(1), new CountDownLatch(1));
        List<AtomicInteger> attempts = List.of(new AtomicInteger(), new AtomicInteger());
        List<AtomicInteger> bodies = List.of(new AtomicInteger(), new AtomicInteger());
        var outcomes = runConcurrently(new TransactionOptions(REQUIRED, SERIALIZABLE, TIMEOUT_SECONDS, false), retries,
                (transaction, rendezvous) -> {
                    boolean first = attempts.get(0).incrementAndGet() == 1;
                    transaction.onCommit(firstCommitted::countDown);
                    amount(orm, 2);
                    rendezvous.await();
                    try {
                        add(orm, 1, 1);
                    } finally {
                        written.get(0).countDown();
                    }
                    if (first && !singleWriter) {
                        awaitLatch(written.get(1), "The second transaction did not write.");
                    }
                    bodies.get(0).incrementAndGet();
                },
                (transaction, rendezvous) -> {
                    boolean first = attempts.get(1).incrementAndGet() == 1;
                    amount(orm, 1);
                    rendezvous.await();
                    if (first && singleWriter) {
                        awaitLatch(firstCommitted, "The first transaction did not commit.");
                    }
                    try {
                        add(orm, 2, 10);
                    } finally {
                        written.get(1).countDown();
                    }
                    if (first && !singleWriter) {
                        awaitLatch(written.get(0), "The first transaction did not write.");
                    }
                    bodies.get(1).incrementAndGet();
                });
        List<WriteSkewOutcome> result = new ArrayList<>();
        for (int index = 0; index < outcomes.size(); index++) {
            result.add(new WriteSkewOutcome(outcomes.get(index), bodies.get(index).get()));
        }
        return result;
    }

    private static void awaitLatch(CountDownLatch latch, String message)
            throws InterruptedException, TimeoutException {
        if (!latch.await(TIMEOUT_SECONDS, SECONDS)) {
            throw new TimeoutException(message);
        }
    }

    @Test
    public void aConstraintViolationIsNotRerun() {
        var orm = template(dataSource);
        var attempts = new AtomicInteger();
        var thrown = assertThrows(PersistenceException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, TIMEOUT_SECONDS, false), 2,
                transaction -> {
                    attempts.incrementAndGet();
                    return execute(orm, "INSERT INTO " + TABLE + " (id, amount) VALUES (1, 0)");
                }));
        assertNotTransient(thrown);
        assertEquals(1, attempts.get(), "a failure that is not transient leaves the block at once");
    }

    /**
     * A failure type of a mapper's own, outside Storm's hierarchy.
     */
    private static final class MappedFailure extends RuntimeException {
        MappedFailure(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Runs the serialization case with each transaction rerunning once, and asserts that the transaction that lost
     * ran again on the winner's write and committed, so the row holds both writes.
     */
    private void assertOneSerializationFailureReruns(ORMTemplate orm) throws Exception {
        boolean singleWriter = singleWriter();
        var firstCommitted = new CountDownLatch(1);
        var outcomes = runConcurrently(new TransactionOptions(REQUIRED, SERIALIZABLE, TIMEOUT_SECONDS, false), 1,
                (transaction, rendezvous) -> {
                    transaction.onCommit(firstCommitted::countDown);
                    amount(orm, 1);
                    rendezvous.await();
                    add(orm, 1, 1);
                },
                (transaction, rendezvous) -> {
                    amount(orm, 1);
                    rendezvous.await();
                    if (singleWriter && !firstCommitted.await(TIMEOUT_SECONDS, SECONDS)) {
                        throw new TimeoutException("The first transaction did not commit.");
                    }
                    add(orm, 1, 10);
                });
        assertOneRerunAndBothCommit(outcomes);
        assertEquals(11, amount(orm, 1), "both transactions' writes");
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
                (transaction, rendezvous) -> {
                    transaction.onCommit(firstCommitted::countDown);
                    amount(orm, 1);
                    rendezvous.await();
                    add(orm, 1, 1);
                },
                (transaction, rendezvous) -> {
                    amount(orm, 1);
                    rendezvous.await();
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
        void run(Transaction transaction, Rendezvous rendezvous)
                throws InterruptedException, BrokenBarrierException, TimeoutException;
    }

    /**
     * The point where the two transactions wait for each other. A rerun meets no one there, as the other transaction
     * is done by then, so it passes straight through.
     */
    @FunctionalInterface
    private interface Rendezvous {
        void await() throws InterruptedException, BrokenBarrierException, TimeoutException;
    }

    /**
     * What a transaction run by {@link #runConcurrently(TransactionOptions, int, ConcurrentWork, ConcurrentWork)}
     * ended with: the failure that left it, {@code null} when it committed, and how often it ran, committed and
     * rolled back.
     */
    private record Outcome(@Nullable Throwable failure, int attempts, int commits, int rollbacks) {}

    /**
     * Runs the two pieces of work each in a transaction of its own, on a thread of its own, and returns what each
     * failed with, {@code null} for a transaction that committed. A transaction that fails before it reaches the
     * barrier leaves the other one waiting there until it times out, which fails the case.
     */
    private static List<@Nullable Throwable> runConcurrently(TransactionOptions options, ConcurrentWork first,
                                                             ConcurrentWork second) throws InterruptedException {
        List<@Nullable Throwable> failures = new ArrayList<>();
        for (var outcome : runConcurrently(options, 0, first, second)) {
            failures.add(outcome.failure());
        }
        return failures;
    }

    /**
     * Runs the two pieces of work each in a transaction of its own that reruns up to {@code retries} times, on a
     * thread of its own, and returns what each ended with. The first attempts meet at a barrier; reruns pass it.
     */
    private static List<Outcome> runConcurrently(TransactionOptions options, int retries, ConcurrentWork first,
                                                 ConcurrentWork second) throws InterruptedException {
        var barrier = new CyclicBarrier(2);
        Rendezvous meeting = () -> barrier.await(TIMEOUT_SECONDS, SECONDS);
        Rendezvous passing = () -> { };
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<?>> futures = new ArrayList<>();
            List<AtomicInteger> attempts = List.of(new AtomicInteger(), new AtomicInteger());
            List<AtomicInteger> commits = List.of(new AtomicInteger(), new AtomicInteger());
            List<AtomicInteger> rollbacks = List.of(new AtomicInteger(), new AtomicInteger());
            var works = List.of(first, second);
            for (int index = 0; index < works.size(); index++) {
                var work = works.get(index);
                var attempt = attempts.get(index);
                var commit = commits.get(index);
                var rollback = rollbacks.get(index);
                futures.add(executor.submit(() -> TransactionRunner.<Object, Exception>execute(options, retries,
                        transaction -> {
                            boolean rerun = attempt.incrementAndGet() > 1;
                            transaction.onCommit(commit::incrementAndGet);
                            transaction.onRollback(rollback::incrementAndGet);
                            work.run(transaction, rerun ? passing : meeting);
                            return null;
                        })));
            }
            List<Outcome> outcomes = new ArrayList<>();
            for (int index = 0; index < futures.size(); index++) {
                Throwable failure = null;
                try {
                    futures.get(index).get(2L * TIMEOUT_SECONDS, SECONDS);
                } catch (ExecutionException e) {
                    assertFalse(e.getCause() instanceof BrokenBarrierException
                            || e.getCause() instanceof TimeoutException,
                            () -> "both transactions reach the barrier: " + e.getCause());
                    failure = e.getCause();
                } catch (TimeoutException e) {
                    throw new AssertionError("The transactions did not complete.", e);
                }
                outcomes.add(new Outcome(failure, attempts.get(index).get(), commits.get(index).get(),
                        rollbacks.get(index).get()));
            }
            return outcomes;
        }
    }

    /**
     * Asserts that both transactions committed, one at its first attempt and the other at its second, after a first
     * attempt that rolled back, and that each transaction fired {@code onCommit} once.
     */
    private static void assertOneRerunAndBothCommit(List<Outcome> outcomes) {
        for (var outcome : outcomes) {
            if (outcome.failure() != null) {
                throw new AssertionError("Both transactions commit: " + outcomes, outcome.failure());
            }
            assertEquals(1, outcome.commits(), () -> "onCommit fires once per transaction: " + outcomes);
            assertEquals(outcome.attempts() - 1, outcome.rollbacks(),
                    () -> "onRollback fires once per failed attempt: " + outcomes);
        }
        assertEquals(List.of(1, 2), outcomes.stream().map(Outcome::attempts).sorted().toList(),
                () -> "one transaction reruns once: " + outcomes);
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
