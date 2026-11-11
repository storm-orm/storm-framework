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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionPropagation.MANDATORY;
import static st.orm.TransactionPropagation.NESTED;
import static st.orm.TransactionPropagation.NEVER;
import static st.orm.TransactionPropagation.NOT_SUPPORTED;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;
import static st.orm.TransactionPropagation.SUPPORTS;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.IllegalTransactionStateException;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.Transaction;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.TransactionTimedOutException;
import st.orm.TransientPersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.TransactionRetries;
import st.orm.core.testsupport.TestExternalTransactionProvider;
import st.orm.spi.ExceptionMapper;

/**
 * Tests the rerun of a transactional block on a transient failure in the blocking runner: which failures rerun,
 * which blocks may state retries, how the attempts settle, and how the framework's classification reaches the
 * runner through the transaction scope.
 *
 * <p>The transient failure comes from a database function that fails with a serialization failure while
 * {@link #CONFLICTS} is positive.</p>
 */
public class TransactionRetriesTest {

    private static final AtomicInteger CONFLICTS = new AtomicInteger();

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
    void createDatabase() {
        CONFLICTS.set(0);
        dataSource = new DriverManagerDataSource("jdbc:h2:mem:retries_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        orm = template(null);
        execute(orm, "CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)");
        execute(orm, "INSERT INTO counter (id, amount) VALUES (1, 0)");
        execute(orm, "CREATE ALIAS CONFLICT FOR \"" + TransactionRetriesTest.class.getName() + ".conflict\"");
    }

    @AfterEach
    void dropDatabase() {
        execute(orm, "SHUTDOWN");
    }

    private ORMTemplate template(@Nullable ExceptionMapper exceptionMapper) {
        var builder = ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl());
        if (exceptionMapper != null) {
            builder = builder.exceptionMapper(exceptionMapper);
        }
        return builder.build();
    }

    private static void execute(ORMTemplate template, String statement) {
        template.query(TemplateString.of(statement)).executeUpdate();
    }

    private static void conflictPoint(ORMTemplate template) {
        template.query(TemplateString.of("SELECT CONFLICT()")).getResultList();
    }

    private void increment() {
        execute(orm, "UPDATE counter SET amount = amount + 1 WHERE id = 1");
    }

    private int amount() {
        var row = orm.query(TemplateString.of("SELECT amount FROM counter WHERE id = 1")).getSingleResult();
        return ((Number) row[0]).intValue();
    }

    private static TransactionOptions options(@Nullable TransactionPropagation propagation) {
        return new TransactionOptions(propagation, null, null, false);
    }

    private static <R> R run(@Nullable TransactionPropagation propagation, int retries,
                             TransactionRunner.Block<R, RuntimeException> block) {
        return TransactionRunner.execute(options(propagation), retries, block);
    }

    private static <R> R run(@Nullable TransactionPropagation propagation,
                             TransactionRunner.Block<R, RuntimeException> block) {
        return TransactionRunner.execute(options(propagation), block);
    }

    /**
     * An external transaction that registers nothing, which is all the refusal looks for.
     */
    private static final Transaction EXTERNAL = new Transaction() {
        @Override
        public boolean isRollbackOnly() {
            return false;
        }

        @Override
        public void setRollbackOnly() {
        }

        @Override
        public void cancel() {
        }

        @Override
        public void onCommit(Runnable callback) {
        }

        @Override
        public void onRollback(Runnable callback) {
        }

        @Override
        public void onCompletion(Consumer<Boolean> callback) {
        }
    };

    @Test
    void aTransientFailureRerunsTheBlockInATransactionOfItsOwn() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        var commits = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        var result = run(REQUIRED, 2, transaction -> {
            attempts.incrementAndGet();
            transaction.onCommit(commits::incrementAndGet);
            transaction.onRollback(rollbacks::incrementAndGet);
            increment();
            conflictPoint(orm);
            return "done";
        });
        assertEquals("done", result);
        assertEquals(2, attempts.get());
        assertEquals(1, commits.get());
        assertEquals(1, rollbacks.get());
        assertEquals(1, amount());
    }

    @Test
    void anUnstatedPropagationReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        run(null, 1, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(orm);
            return null;
        });
        assertEquals(2, attempts.get());
    }

    @Test
    void exhaustedRetriesThrowTheLastFailureWithTheEarlierOnesSuppressed() {
        CONFLICTS.set(10);
        var attempts = new AtomicInteger();
        var thrown = assertThrows(TransientPersistenceException.class, () -> run(REQUIRED, 2, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(orm);
            return null;
        }));
        assertEquals(3, attempts.get());
        assertEquals(2, Arrays.stream(thrown.getSuppressed())
                .filter(TransientPersistenceException.class::isInstance)
                .count());
    }

    @Test
    void theSameFailureThrownByEveryAttemptIsNotSuppressedOnItself() {
        var failure = new OptimisticLockException("Simulated conflict.");
        var attempts = new AtomicInteger();
        var thrown = assertThrows(OptimisticLockException.class, () -> run(REQUIRED, 1, transaction -> {
            attempts.incrementAndGet();
            throw failure;
        }));
        assertSame(failure, thrown);
        assertEquals(2, attempts.get());
        assertEquals(0, thrown.getSuppressed().length);
    }

    @Test
    void aFailureThatIsNotTransientIsNotRerun() {
        var attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> run(REQUIRED, 2, transaction -> {
            attempts.incrementAndGet();
            throw new IllegalStateException("Not transient.");
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    @Timeout(10)
    void aFailureWhoseCausesFormACycleIsNotTransient() {
        var first = new IllegalStateException("First.");
        var second = new IllegalStateException("Second.", first);
        first.initCause(second);
        var attempts = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> run(REQUIRED, 2, transaction -> {
            attempts.incrementAndGet();
            throw first;
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    @Timeout(10)
    void aFailureWhoseCausesFormACycleFailsTheTransactionPastItsDeadline() {
        var first = new IllegalStateException("First.");
        var second = new IllegalStateException("Second.", first);
        first.initCause(second);
        var thrown = assertThrows(IllegalStateException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, 0, false), transaction -> {
                    throw first;
                }));
        assertSame(first, thrown);
    }

    @Test
    void aClassifiedFailureWrappedByTheBlockReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        run(REQUIRED, 1, transaction -> {
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
    void aClassifiedFailureOfAnInnerBlockIsRecordedOnTheEnclosingBlock() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        run(REQUIRED, 1, transaction -> {
            attempts.incrementAndGet();
            RuntimeException caught = null;
            try {
                run(REQUIRES_NEW, inner -> {
                    conflictPoint(orm);
                    return null;
                });
            } catch (RuntimeException e) {
                caught = e;
            }
            if (caught != null) {
                throw new IllegalStateException("Inner block failed.", caught);
            }
            return null;
        });
        assertEquals(2, attempts.get());
    }

    /**
     * A failure type of a mapper's own, outside Storm's hierarchy.
     */
    static final class MappedFailure extends RuntimeException {
        MappedFailure(Throwable cause) {
            super(cause);
        }
    }

    @Test
    void aTransientFailureAMapperTranslatesToItsOwnTypeReruns() {
        var mapped = template((failure, context) -> failure instanceof TransientPersistenceException ? new MappedFailure(failure) : null);
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        run(REQUIRED, 1, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(mapped);
            return null;
        });
        assertEquals(2, attempts.get());
        CONFLICTS.set(1);
        assertThrows(MappedFailure.class, () -> conflictPoint(mapped), "classified outside a block as well");
    }

    @Test
    void aTransientFailureAMapperDeclinesReruns() {
        var declining = template((failure, context) -> null);
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        run(REQUIRED, 1, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(declining);
            return null;
        });
        assertEquals(2, attempts.get());
    }

    @Test
    void aTransientFailureWhoseMapperFailsReruns() {
        var failing = template((failure, context) -> {
            throw new IllegalStateException("Mapper failure.");
        });
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        run(REQUIRED, 1, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(failing);
            return null;
        });
        assertEquals(2, attempts.get());
    }

    @Test
    void aCancelledAttemptIsNotRerun() {
        var attempts = new AtomicInteger();
        assertThrows(OptimisticLockException.class, () -> run(REQUIRED, 2, transaction -> {
            attempts.incrementAndGet();
            transaction.cancel();
            throw new OptimisticLockException("Simulated conflict.");
        }));
        assertEquals(1, attempts.get());
    }

    @Test
    void aTimedOutAttemptIsNotRerun() {
        var attempts = new AtomicInteger();
        assertThrows(TransactionTimedOutException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, 0, false), 2, transaction -> {
                    attempts.incrementAndGet();
                    increment();
                    return null;
                }));
        assertEquals(1, attempts.get());
    }

    @Test
    void aFailureAtCompletionIsNotRerun() {
        var attempts = new AtomicInteger();
        assertThrows(TransactionTimedOutException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, 0, false), 2, transaction -> {
                    attempts.incrementAndGet();
                    return null;
                }));
        assertEquals(1, attempts.get());
        assertThrows(TransactionTimedOutException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, 0, false), transaction -> null));
    }

    @Test
    void anInterruptDuringThePauseEndsTheReruns() {
        CONFLICTS.set(5);
        var attempts = new AtomicInteger();
        try {
            var thrown = assertThrows(TransientPersistenceException.class, () -> run(REQUIRED, 2, transaction -> {
                attempts.incrementAndGet();
                transaction.onRollback(() -> Thread.currentThread().interrupt());
                conflictPoint(orm);
                return null;
            }));
            assertEquals(1, attempts.get());
            assertTrue(Thread.currentThread().isInterrupted());
            assertTrue(Arrays.stream(thrown.getSuppressed()).anyMatch(InterruptedException.class::isInstance));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void zeroRetriesRunTheBlockOnce() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        assertThrows(TransientPersistenceException.class, () -> run(REQUIRED, 0, transaction -> {
            attempts.incrementAndGet();
            conflictPoint(orm);
            return null;
        }));
        assertEquals(1, attempts.get());
        assertEquals("ran", run(REQUIRED, 0, transaction -> "ran"));
    }

    @Test
    void negativeRetriesAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> run(REQUIRED, -1, transaction -> null));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation.class, names = {"REQUIRED", "NESTED", "MANDATORY", "SUPPORTS",
            "NOT_SUPPORTED", "NEVER"})
    void aBlockThatJoinsOrRunsWithoutATransactionInsideOneRefusesRetries(TransactionPropagation propagation) {
        run(REQUIRED, transaction -> assertThrows(IllegalTransactionStateException.class,
                () -> run(propagation, 1, inner -> null)));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation.class, names = {"MANDATORY", "SUPPORTS", "NOT_SUPPORTED", "NEVER"})
    void aBlockThatRunsWithoutATransactionRefusesRetries(TransactionPropagation propagation) {
        assertThrows(IllegalTransactionStateException.class, () -> run(propagation, 1, transaction -> null));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation.class, names = {"REQUIRED", "NESTED", "SUPPORTS"})
    void aBlockInsideAnExternalTransactionRefusesRetries(TransactionPropagation propagation) {
        TestExternalTransactionProvider.during(EXTERNAL, () -> assertThrows(IllegalTransactionStateException.class,
                () -> run(propagation, 1, transaction -> null)));
        TestExternalTransactionProvider.during(EXTERNAL, () -> assertThrows(IllegalTransactionStateException.class,
                () -> run(null, 1, transaction -> null)));
        assertEquals("ran", TestExternalTransactionProvider.during(EXTERNAL,
                () -> run(REQUIRES_NEW, 1, transaction -> "ran")));
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation.class, names = {"REQUIRED", "NESTED", "REQUIRES_NEW"})
    void aBlockThatStartsItsOwnTransactionTakesRetries(TransactionPropagation propagation) {
        assertEquals("ran", run(propagation, 1, transaction -> "ran"));
        assertEquals("ran", run(NOT_SUPPORTED, transaction -> run(propagation, 1, inner -> "ran")));
        assertEquals("ran", run(NOT_SUPPORTED, transaction -> run(SUPPORTS, inner -> run(propagation, 1,
                innermost -> "ran"))));
    }

    @Test
    void aBlockInsideOneThatSupportsATransactionTakesAfterItsParent() {
        run(REQUIRED, transaction -> run(SUPPORTS, inner -> assertThrows(IllegalTransactionStateException.class,
                () -> run(REQUIRED, 1, innermost -> null))));
        assertThrows(IllegalTransactionStateException.class,
                () -> run(MANDATORY, 1, transaction -> null));
        assertThrows(IllegalTransactionStateException.class, () -> run(NEVER, 1, transaction -> null));
    }

    @Test
    void theRerunPauseIsShortJitteredAndGrowing() {
        for (int sample = 0; sample < 100; sample++) {
            long first = TransactionRetries.pauseMillis(1);
            assertTrue(first >= 10 && first <= 20, "first pause: " + first);
            long second = TransactionRetries.pauseMillis(2);
            assertTrue(second >= 20 && second <= 40, "second pause: " + second);
            long late = TransactionRetries.pauseMillis(100);
            assertTrue(late >= 1_000 && late <= 2_000, "capped pause: " + late);
            long unstated = TransactionRetries.pauseMillis(0);
            assertTrue(unstated >= 10 && unstated <= 20, "pause before an unnumbered rerun: " + unstated);
        }
    }

    @Test
    void callbacksOfABlockThatStartsItsOwnTransactionInsideOneWithoutFireByItsOwnOutcome() {
        List<String> events = new ArrayList<>();
        run(NOT_SUPPORTED, transaction -> {
            assertThrows(IllegalStateException.class, () -> run(REQUIRED, inner -> {
                inner.onCommit(() -> events.add("commit"));
                inner.onRollback(() -> events.add("rollback"));
                increment();
                throw new IllegalStateException("Inner failure.");
            }));
            assertEquals(List.of("rollback"), events);
            return null;
        });
        assertEquals(List.of("rollback"), events);
        assertEquals(0, amount());
    }

    @Test
    void callbacksOfAJoiningBlockDeferToTheTransactionItJoins() {
        List<String> events = new ArrayList<>();
        run(REQUIRED, transaction -> run(REQUIRED, inner -> {
            inner.onCommit(() -> events.add("commit"));
            assertEquals(List.of(), events);
            return null;
        }));
        assertEquals(List.of("commit"), events);
    }

    @Test
    void aBlockInsideAScopeWithoutCallbacksSettlesItsOwn() {
        List<String> events = new ArrayList<>();
        var outer = TransactionScope.open(options(REQUIRED), false);
        try {
            run(REQUIRED, inner -> {
                inner.onCommit(() -> events.add("commit"));
                return null;
            });
        } finally {
            outer.complete(false);
            outer.close();
        }
        assertEquals(List.of("commit"), events);
    }

    @Test
    void aScopeRunsInATransactionByItsPropagation() {
        var required = TransactionScope.create(options(REQUIRED), false, null);
        var notSupported = TransactionScope.create(options(NOT_SUPPORTED), false, null);
        assertTrue(required.runsInTransaction());
        assertTrue(TransactionScope.create(options(null), false, null).runsInTransaction());
        assertFalse(notSupported.runsInTransaction());
        assertFalse(TransactionScope.create(options(NEVER), false, null).runsInTransaction());
        assertTrue(TransactionScope.create(options(NESTED), false, null).runsInTransaction());
        assertTrue(TransactionScope.create(options(SUPPORTS), false, null).runsInTransaction(),
                "an outermost SUPPORTS scope is assumed to join a transaction");
        assertTrue(TransactionScope.create(options(SUPPORTS), false, required).runsInTransaction());
        assertFalse(TransactionScope.create(options(SUPPORTS), false, notSupported).runsInTransaction());
    }

    @Test
    void aJoiningScopeSharesTheRollbackMarkOfAParentInATransactionOnly() {
        var required = TransactionScope.create(options(REQUIRED), false, null);
        required.setRollbackOnly();
        assertTrue(TransactionScope.create(options(REQUIRED), false, required).isRollbackOnly());
        assertFalse(TransactionScope.create(options(REQUIRES_NEW), false, required).isRollbackOnly());
        assertFalse(TransactionScope.create(options(REQUIRED), false, null).isRollbackOnly());
        var notSupported = TransactionScope.create(options(NOT_SUPPORTED), false, null);
        notSupported.setRollbackOnly();
        assertFalse(TransactionScope.create(options(REQUIRED), false, notSupported).isRollbackOnly());
    }

    @Test
    void aRecordedTransientFailureIsKnownToTheScopeAndItsParents() {
        var parent = TransactionScope.create(options(REQUIRED), false, null);
        var child = TransactionScope.create(options(REQUIRES_NEW), false, parent);
        var failure = new IllegalStateException("Recorded.");
        var other = new IllegalStateException("Recorded too.");
        assertFalse(child.isTransientFailure(failure));
        child.recordTransientFailure(failure);
        child.recordTransientFailure(other);
        assertTrue(child.isTransientFailure(failure));
        assertTrue(parent.isTransientFailure(other));
        assertFalse(parent.isTransientFailure(new IllegalStateException("Not recorded.")));
    }
}
