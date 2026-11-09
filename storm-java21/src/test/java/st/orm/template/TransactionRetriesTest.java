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
package st.orm.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionPropagation.MANDATORY;
import static st.orm.TransactionPropagation.NESTED;
import static st.orm.TransactionPropagation.NEVER;
import static st.orm.TransactionPropagation.NOT_SUPPORTED;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;
import static st.orm.TransactionPropagation.SUPPORTS;
import static st.orm.template.Transactions.transaction;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.IllegalTransactionStateException;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.TransientPersistenceException;

/**
 * Tests the retry count on the Java transaction entry points: an attempt that fails transiently runs again in a
 * transaction of its own, any other failure leaves the block at once, and a block that does not start its own
 * transaction refuses the count.
 *
 * <p>The transient failure comes from a database function that fails with a serialization failure while
 * {@link #CONFLICTS} is positive, which the dialect classifies as transient.</p>
 */
public class TransactionRetriesTest {

    private static final AtomicInteger CONFLICTS = new AtomicInteger();

    private static final AtomicInteger DATABASES = new AtomicInteger();

    private DriverManagerDataSource dataSource;
    private ORMTemplate orm;

    /**
     * The database function behind {@code CONFLICT()}: fails with a serialization failure while conflicts remain.
     */
    public static int conflict() throws SQLException {
        if (CONFLICTS.getAndDecrement() > 0) {
            throw new SQLException("Simulated serialization failure.", "40001", 40001);
        }
        return 0;
    }

    @BeforeEach
    void createDatabase() throws SQLException {
        CONFLICTS.set(0);
        dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:retries" + DATABASES.incrementAndGet() + ";DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)");
            statement.execute("INSERT INTO counter (id, amount) VALUES (1, 0)");
            statement.execute("CREATE ALIAS CONFLICT FOR \"" + TransactionRetriesTest.class.getName() + ".conflict\"");
        }
        orm = ORMTemplate.of(dataSource);
    }

    @AfterEach
    void dropDatabase() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("SHUTDOWN");
        }
    }

    private void increment(ORMTemplate template) {
        template.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate();
    }

    private void conflictPoint(ORMTemplate template) {
        template.query("SELECT CONFLICT()").getResultList();
    }

    private int amount() {
        return ((Number) orm.query("SELECT amount FROM counter WHERE id = 1").getSingleResult()[0]).intValue();
    }

    @Test
    public void aTransientFailureRerunsTheBlockInATransactionOfItsOwn() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        var commits = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        List<Boolean> completions = new ArrayList<>();
        var result = transaction(2, transaction -> {
            attempts.incrementAndGet();
            transaction.onCommit(commits::incrementAndGet);
            transaction.onRollback(rollbacks::incrementAndGet);
            transaction.onCompletion(completions::add);
            increment(orm);
            conflictPoint(orm);
            return "done";
        });
        assertEquals("done", result);
        assertEquals(2, attempts.get());
        assertEquals(1, commits.get(), "onCommit fires for the committing attempt only");
        assertEquals(1, rollbacks.get(), "onRollback fires for the failed attempt");
        assertEquals(List.of(false, true), completions);
        assertEquals(1, amount(), "the failed attempt's write rolled back");
    }

    @Test
    public void eachRerunLogsOneLineNamingTheAttemptAndTheFailure() {
        CONFLICTS.set(2);
        var logger = (Logger) LoggerFactory.getLogger("st.orm.transaction");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        var previous = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            transaction(2, transaction -> {
                conflictPoint(orm);
                return null;
            });
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
        var lines = appender.list.stream().filter(event -> event.getLevel() == Level.INFO).toList();
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).getFormattedMessage().contains("attempt 2 of 3"), lines.get(0).getFormattedMessage());
        assertTrue(lines.get(1).getFormattedMessage().contains("attempt 3 of 3"), lines.get(1).getFormattedMessage());
        assertTrue(lines.get(0).getFormattedMessage().contains("Simulated serialization failure."),
                lines.get(0).getFormattedMessage());
    }

    @Test
    public void exhaustedRetriesThrowTheLastFailureWithTheEarlierOnesSuppressed() {
        CONFLICTS.set(10);
        var attempts = new AtomicInteger();
        var thrown = assertThrows(TransientPersistenceException.class, () -> transaction(2, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(orm);
            return null;
        }));
        assertEquals(3, attempts.get());
        var earlier = Arrays.stream(thrown.getSuppressed())
                .filter(TransientPersistenceException.class::isInstance)
                .toList();
        assertEquals(2, earlier.size(), () -> "the earlier attempts' failures: " + Arrays.toString(thrown.getSuppressed()));
    }

    @Test
    public void aFailureThatIsNotTransientIsNotRerun() {
        var attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transaction(2, transaction -> {
            attempts.incrementAndGet();
            increment(orm);
            throw new IllegalStateException("Not transient.");
        }));
        assertEquals(1, attempts.get());
        assertEquals(0, amount());
    }

    @Test
    public void aDatabaseFailureThatIsNotTransientIsNotRerun() {
        var attempts = new AtomicInteger();
        var thrown = assertThrows(PersistenceException.class, () -> transaction(2, transaction -> {
            attempts.incrementAndGet();
            orm.query("INSERT INTO counter (id, amount) VALUES (1, 0)").executeUpdate();
            return null;
        }));
        assertFalse(thrown instanceof TransientPersistenceException);
        assertEquals(1, attempts.get());
    }

    @Test
    public void aTransientPersistenceExceptionThrownByTheBlockReruns() {
        var attempts = new AtomicInteger();
        var result = transaction(1, transaction -> {
            if (attempts.incrementAndGet() == 1) {
                throw new OptimisticLockException("Simulated conflict.");
            }
            return attempts.get();
        });
        assertEquals(2, result);
    }

    @Test
    public void aWrappedTransientFailureReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        transaction(1, transaction -> {
            attempts.incrementAndGet();
            try {
                conflictPoint(orm);
            } catch (PersistenceException e) {
                throw new IllegalStateException("Wrapped.", e);
            }
            return null;
        });
        assertEquals(2, attempts.get());
    }

    @Test
    public void aTransientFailureTheBlockRecoveredFromDoesNotRerunALaterFailure() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> transaction(REQUIRES_NEW, 2, transaction -> {
            attempts.incrementAndGet();
            try {
                transaction(REQUIRES_NEW, inner -> {
                    conflictPoint(orm);
                    return null;
                });
            } catch (TransientPersistenceException recovered) {
                // The inner transaction is gone; the block carries on without it.
            }
            throw new IllegalStateException("Not transient.");
        }));
        assertEquals(1, attempts.get());
    }

    /**
     * A mapper that translates a transient failure to a type of its own, outside Storm's hierarchy.
     */
    static final class RetryableFailure extends RuntimeException {
        RetryableFailure(Throwable cause) {
            super(cause);
        }
    }

    @Test
    public void aTransientFailureAMapperTranslatesToItsOwnTypeStillReruns() {
        var mapped = ORMTemplate.builder(dataSource)
                .exceptionMapper((failure, context) -> failure instanceof TransientPersistenceException ? new RetryableFailure(failure) : null)
                .build();
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        transaction(1, transaction -> {
            attempts.incrementAndGet();
            increment(mapped);
            conflictPoint(mapped);
            return null;
        });
        assertEquals(2, attempts.get());
        assertEquals(1, amount());
        CONFLICTS.set(2);
        assertThrows(RetryableFailure.class, () -> transaction(1, transaction -> {
            conflictPoint(mapped);
            return null;
        }));
    }

    @Test
    public void aBlockThatStartsItsOwnTransactionInsideAnotherReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        transaction(transaction -> {
            increment(orm);
            return transaction(REQUIRES_NEW, 1, inner -> {
                attempts.incrementAndGet();
                conflictPoint(orm);
                return null;
            });
        });
        assertEquals(2, attempts.get());
        assertEquals(1, amount());
    }

    @Test
    public void aRequiredBlockInsideABlockWithoutATransactionReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        var commits = new AtomicInteger();
        transaction(NOT_SUPPORTED, transaction -> transaction(REQUIRED, 1, inner -> {
            attempts.incrementAndGet();
            inner.onCommit(commits::incrementAndGet);
            increment(orm);
            conflictPoint(orm);
            return null;
        }));
        assertEquals(2, attempts.get());
        assertEquals(1, commits.get());
        assertEquals(1, amount());
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation.class, names = {"REQUIRED", "NESTED", "MANDATORY", "SUPPORTS",
            "NOT_SUPPORTED", "NEVER"})
    public void aBlockThatJoinsOrRunsWithoutATransactionInsideOneRefusesRetries(TransactionPropagation propagation) {
        var attempts = new AtomicInteger();
        transaction(transaction -> {
            assertThrows(IllegalTransactionStateException.class, () -> transaction(propagation, 1, inner -> {
                attempts.incrementAndGet();
                return null;
            }));
            return null;
        });
        assertEquals(0, attempts.get(), "refused before the block runs");
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation.class, names = {"MANDATORY", "SUPPORTS", "NOT_SUPPORTED", "NEVER"})
    public void aBlockThatRunsWithoutATransactionRefusesRetries(TransactionPropagation propagation) {
        var attempts = new AtomicInteger();
        assertThrows(IllegalTransactionStateException.class, () -> transaction(propagation, 1, inner -> {
            attempts.incrementAndGet();
            return null;
        }));
        assertEquals(0, attempts.get(), "refused before the block runs");
    }

    @Test
    public void aNestedBlockWithoutAnActiveTransactionReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        transaction(NESTED, 1, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(orm);
            return null;
        });
        assertEquals(2, attempts.get());
    }

    @Test
    public void aBlockInsideOneThatRunsWithoutATransactionInsideAnotherRefusesRetries() {
        transaction(transaction -> transaction(SUPPORTS, inner -> {
            assertThrows(IllegalTransactionStateException.class, () -> transaction(REQUIRED, 1, innermost -> null));
            return null;
        }));
        transaction(NOT_SUPPORTED, transaction -> transaction(SUPPORTS, inner -> {
            assertEquals("ran", transaction(REQUIRED, 1, innermost -> "ran"));
            return null;
        }));
    }

    @Test
    public void retriesAreNotInheritedFromTheOptions() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        assertThrows(TransientPersistenceException.class, () -> transaction(TransactionOptions.defaults(), transaction -> {
            attempts.incrementAndGet();
            conflictPoint(orm);
            return null;
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    public void negativeRetriesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> transaction(-1, transaction -> null));
        assertThrows(IllegalTransactionStateException.class, () -> transaction(MANDATORY, 1, transaction -> null));
        assertThrows(IllegalTransactionStateException.class, () -> transaction(NEVER, 1, transaction -> null));
    }

    @Test
    public void anInterruptDuringThePauseEndsTheReruns() {
        CONFLICTS.set(5);
        var attempts = new AtomicInteger();
        try {
            var thrown = assertThrows(TransientPersistenceException.class, () -> transaction(2, transaction -> {
                attempts.incrementAndGet();
                transaction.onRollback(() -> Thread.currentThread().interrupt());
                conflictPoint(orm);
                return null;
            }));
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted(), "the interrupt is kept");
            assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(InterruptedException.class::isInstance));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    public void callbacksOfABlockThatStartsItsOwnTransactionInsideOneWithoutFireByItsOwnOutcome() {
        var commits = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        transaction(NOT_SUPPORTED, transaction -> {
            assertThrows(IllegalStateException.class, () -> transaction(REQUIRED, inner -> {
                inner.onCommit(commits::incrementAndGet);
                inner.onRollback(rollbacks::incrementAndGet);
                increment(orm);
                throw new IllegalStateException("Inner failure.");
            }));
            assertEquals(1, rollbacks.get(), "the inner transaction rolled back when its block failed");
            return null;
        });
        assertEquals(0, commits.get());
        assertEquals(1, rollbacks.get());
        assertEquals(0, amount());
    }
}
