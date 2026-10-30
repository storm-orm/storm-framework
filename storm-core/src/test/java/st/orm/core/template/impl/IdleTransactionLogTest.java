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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionIsolation.SERIALIZABLE;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import st.orm.AcceptAdvice;
import st.orm.TransactionOptions;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.spi.TransactionScope;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * A transaction that holds its connection idle for longer than the threshold, the time it held the connection less
 * the time its statements spent on it, is reported when it returns the connection: with the call site that began it,
 * its statements and whether it wrote. Nothing is reported for a transaction below the threshold, for one whose time
 * went into its statements, or where the method running it accepts the advice.
 */
class IdleTransactionLogTest {

    private static final TransactionOptions REQUIRED_OPTIONS = new TransactionOptions(REQUIRED, null, null, null);
    private static final TransactionOptions NEW_OPTIONS = new TransactionOptions(REQUIRES_NEW, null, null, null);

    private ORMTemplate orm;

    @BeforeEach
    void prepare() throws SQLException {
        var h2 = DataSourceBuilder.create()
                .url("jdbc:h2:mem:idleTransactionLog;DB_CLOSE_DELAY=-1")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS idle_transaction (id INTEGER PRIMARY KEY)");
            statement.execute("DELETE FROM idle_transaction");
            // A statement that spends its time in the database.
            statement.execute("CREATE ALIAS IF NOT EXISTS SLEEP FOR \"java.lang.Thread.sleep(long)\"");
        }
        // Storm's own providers, whatever the module's test classpath registers for its other tests.
        orm = ORMTemplate.builder(h2)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        SlowStatementLogTestSupport.reset();
        SlowStatementLog.transactionThreshold(Duration.ofMillis(50));
    }

    @AfterEach
    void unwatch() {
        SlowStatementLog.transactionThreshold(SlowStatementLog.DEFAULT_TRANSACTION_THRESHOLD);
        SlowStatementLog.limit(SlowStatementLog.DEFAULT_LIMIT);
        SlowStatementLogTestSupport.reset();
    }

    private void read() {
        orm.query(TemplateString.of("SELECT COUNT(*) FROM idle_transaction")).getSingleResult(Long.class);
    }

    private void write() {
        orm.query(TemplateString.of(
                "INSERT INTO idle_transaction (id) SELECT COALESCE(MAX(id), 0) + 1 FROM idle_transaction"))
                .executeUpdate();
    }

    private void sleepInDatabase() {
        orm.query(TemplateString.of("CALL SLEEP(120)")).executeUpdate();
    }

    private static void otherWork() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static void transaction(TransactionOptions options, Runnable block) {
        TransactionRunner.<Void, RuntimeException>execute(options, transaction -> {
            block.run();
            return null;
        });
    }

    /** Collects the slow log's warnings while the action runs. */
    private static List<String> capture(Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void aTransactionIdleBeyondTheThresholdIsReported() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
            read();
        }));
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(line.startsWith("SQL slow (transaction): held its connection "), line);
        assertTrue(line.contains(" ms in its 2 statements, read only, "), line);
        // A Storm transaction is measured from the moment it takes its connection, not from its first statement.
        assertFalse(line.contains("from its first statement"), line);
        // The call site is walked for the completing transaction; this test lives under st.orm, which the walker
        // treats as framework, so the frame it finds is the test runner's.
        assertTrue(Pattern.compile(", [A-Za-z0-9_$.]+:\\d+" + System.lineSeparator()).matcher(line).find(), line);
        assertTrue(line.contains(System.lineSeparator() + "\tadvice: the transaction held its connection "), line);
        // The work sat between the reads, in one gap, which no move before or after the transaction takes out.
        assertTrue(Pattern.compile(" ms of it idle between statements\\.\\R\t\tlongest idle: [0-9.]+ ms, after the "
                + "SELECT, before the SELECT at [A-Za-z0-9_$.]+:\\d+, the only gap of 1\\.0 ms or more\\R\t\tfix: read "
                + "what the work needs inside the transaction and do the work after it returns; where the reads need not "
                + "see one state of the database, reading without the transaction holds a connection only while each "
                + "statement runs\\.\\R\t\tsee: https://orm\\.st/docs/advice#idle-transaction").matcher(line).find(), line);
    }

    @Test
    void workAfterTheLastStatementIsToMoveAfterTheCommit() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
        }));
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(Pattern.compile(" ms of it idle after its last statement\\.\\R\t\tfix: move that work before the "
                + "transaction or after it commits\\.").matcher(line).find(), line);
    }

    @Test
    void gapsSpreadBetweenWritesNameTheLongestAndHowManyThereWere() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            sleep(30);
            write();
            otherWork();
            write();
            sleep(30);
            read();
        }));
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(Pattern.compile(" ms of it idle between statements, with its row locks held for [0-9.]+ ms of that\\."
                + "\\R\t\tlongest idle: [0-9.]+ ms, after the INSERT, before the INSERT at [A-Za-z0-9_$.]+:\\d+, of 3 gaps "
                + "of 1\\.0 ms or more\\R\t\tfix: where the writes need not see the reads of this transaction, do the "
                + "reads and that work first, then write in a short transaction of its own\\.").matcher(line).find(),
                line);
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aTransactionWithAnIsolationLevelRestoresItWhenItReturnsItsConnection() {
        var lines = capture(() -> transaction(new TransactionOptions(REQUIRED, SERIALIZABLE, null, null), () -> {
            read();
            otherWork();
        }));
        assertEquals(1, lines.size(), lines.toString());
    }

    @Test
    void switchedOffWhileATransactionRunsItIsNotReported() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
            SlowStatementLog.transactionThreshold(null);
        }));
        assertEquals(List.of(), lines);
    }

    @Test
    void nothingIsRenderedWhileTheLoggerIsBelowWarn() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        Level previous = logger.getLevel();
        logger.setLevel(Level.ERROR);
        try {
            var appender = new ListAppender<ILoggingEvent>();
            appender.setContext(context);
            appender.start();
            logger.addAppender(appender);
            try {
                transaction(REQUIRED_OPTIONS, () -> {
                    read();
                    otherWork();
                });
            } finally {
                logger.detachAppender(appender);
                appender.stop();
            }
            assertEquals(List.of(), appender.list);
        } finally {
            logger.setLevel(previous);
        }
    }

    @Test
    void aStatementIsTimedOnItsOwnWhileTransactionsAreUnwatched() {
        SlowStatementLog.transactionThreshold(null);
        SlowStatementLog.threshold(Duration.ofNanos(1));
        try {
            var lines = capture(() -> transaction(REQUIRED_OPTIONS, this::read));
            assertEquals(1, lines.size(), lines.toString());
            assertTrue(lines.getFirst().startsWith("SQL slow (SELECT): "), lines.getFirst());
        } finally {
            SlowStatementLog.threshold(null);
        }
    }

    @Test
    void theLineNamesEveryPartItCanCarry() {
        var line = SlowStatementLog.renderTransaction(2_300_000_000L, 120_000_000L, 2_180_000_000L,
                2_000_000_000L, 1, true, true, TransactionRecord.Gaps.NONE, null, 3);
        assertEquals("SQL slow (transaction): held its connection 2300 ms from its first statement, 120 ms in its "
                + "statement, wrote" + System.lineSeparator()
                + "	+3 suppressed" + System.lineSeparator()
                + "	advice: the transaction held its connection 2300 ms, 2180 ms of it idle, with its row locks held "
                + "for 2000 ms of that." + System.lineSeparator()
                + "		fix: move that work before the transaction or after it commits." + System.lineSeparator() + "		see: "
                + "https://orm.st/docs/advice#idle-transaction", line);
    }

    @Test
    void everyPartOfTheIdleTimeIsNamedWithItsFigure() {
        // A part under a tenth of the idle time would be left out.
        var gaps = new TransactionRecord.Gaps(3, 300_000_000L, 800_000_000L, 165_000_000L, 412, 420_000_000L,
                null, null, null, null);
        var advisory = SlowStatementLog.idleAdvisory(1_400_000_000L, 1_265_000_000L, 0, false, gaps);
        assertEquals("the transaction held its connection 1400 ms, 1265 ms of it idle.\n"
                + "idle: 300 ms before its first statement; 800 ms between statements; 165 ms after its last statement\n"
                + "fix: read what the work needs inside the transaction and do the work after it returns; where the "
                + "reads need not see one state of the database, reading without the transaction holds a connection "
                + "only while each statement runs.\nsee: https://orm.st/docs/advice#idle-transaction", advisory);
    }

    @Test
    void idleTimeIsWorthActingOnFromAQuarterOfTheHoldOrASecondOfIdleRowLocks() {
        // The production planner: 1.26 s idle of 9.0 s, 0.19 s of it with row locks.
        assertFalse(SlowStatementLog.worthActingOn(9_000_000_000L, 1_260_000_000L, 190_000_000L));
        // The cold start: 37.3 s idle of 96 s.
        assertTrue(SlowStatementLog.worthActingOn(96_000_000_000L, 37_300_000_000L, 700_000_000L));
        // A long write transaction whose idle share is small, but whose row locks were held idle for a second.
        assertTrue(SlowStatementLog.worthActingOn(20_000_000_000L, 1_200_000_000L, 1_000_000_000L));
    }

    @Test
    void idleTimeBelowTheFloorGoesToTrace() {
        SlowStatementLog.transactionThreshold(Duration.ofMillis(50));
        // 120 ms of other work against 600 ms in the database: a sixth of the hold, read only.
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
            sleepInDatabase();
            sleepInDatabase();
            sleepInDatabase();
            sleepInDatabase();
            sleepInDatabase();
        }));
        assertEquals(List.of(), lines);
    }

    @Test
    void statementsCloseTogetherAreNotAdvisedAConnectionBorrowedForEach() {
        // 300 statements a millisecond apart: a borrow per statement would cost about what the held connection did.
        var gaps = new TransactionRecord.Gaps(301, 0, 300_000_000L, 0, 0, 0, null, null, null, null);
        var advisory = SlowStatementLog.idleAdvisory(400_000_000L, 300_000_000L, 0, false, gaps);
        assertTrue(advisory.endsWith("idle between statements.\nidle gaps: all under 1.0 ms\nfix: read what the work "
                + "needs inside the transaction and do the work after it returns.\n"
                + "see: https://orm.st/docs/advice#idle-transaction"),
                advisory);
    }

    @Test
    void aTransactionThatWroteNamesTheRowLocksItHeld() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            write();
            otherWork();
        }));
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(line.contains(" ms in its statement, wrote, "), line);
        assertTrue(line.contains(" ms of it idle after its last statement, with its row locks held for "), line);
        // The work followed the write, so the locks were held for all of it.
        assertTrue(lockedIdleMillis(line) >= 100, line);
    }

    @Test
    void theRowLocksAreClaimedOnlyFromTheFirstWriteOn() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
            write();
        }));
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(line.contains(" ms in its 2 statements, wrote, "), line);
        // The work preceded the write, so the locks were held for the commit alone.
        assertTrue(lockedIdleMillis(line) < 50, line);
    }

    /** Returns the idle time the line claims the row locks for, in milliseconds. */
    private static double lockedIdleMillis(String line) {
        var matcher = Pattern.compile("with its row locks held for ([0-9.]+) ms of that").matcher(line);
        assertTrue(matcher.find(), line);
        return Double.parseDouble(matcher.group(1));
    }

    @Test
    void nothingIsReportedBelowTheThreshold() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            read();
        }));
        assertEquals(List.of(), lines);
    }

    @Test
    void timeSpentInStatementsIsNotIdle() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, this::sleepInDatabase));
        assertEquals(List.of(), lines);
    }

    @Test
    void aJoinedBlockIsPartOfTheTransactionItJoined() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            transaction(REQUIRED_OPTIONS, () -> {
                otherWork();
                read();
            });
        }));
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains(" in its 2 statements, "), lines.getFirst());
    }

    @Test
    void aTransactionOfItsOwnInsideAnotherIsMeasuredOnItsOwn() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            transaction(NEW_OPTIONS, () -> {
                read();
                otherWork();
            });
        }));
        // The inner transaction completes first; the outer one held its connection through the inner's work.
        assertEquals(2, lines.size(), lines.toString());
        assertTrue(lines.get(0).contains(" in its statement, "), lines.get(0));
        assertTrue(lines.get(1).contains(" in its statement, "), lines.get(1));
    }

    @Test
    @AcceptAdvice("idle-transaction")
    void acceptedOnTheMethodRunningTheTransactionItIsNotReported() {
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
        }));
        assertEquals(List.of(), lines);
    }

    @Test
    void linesAreRateLimited() {
        SlowStatementLog.limit(1);
        var lines = capture(() -> {
            for (int i = 0; i < 2; i++) {
                transaction(REQUIRED_OPTIONS, () -> {
                    read();
                    otherWork();
                });
            }
        });
        assertEquals(1, lines.size(), lines.toString());
    }

    @Test
    void switchedOffNoConnectionIsHeld() {
        SlowStatementLog.transactionThreshold(null);
        assertFalse(SlowStatementLog.active());
        assertNull(TransactionRecord.take(false));
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
        }));
        assertEquals(List.of(), lines);
        SlowStatementLog.transactionThreshold(Duration.ofMillis(5));
        assertTrue(SlowStatementLog.active());
        assertEquals(Duration.ofMillis(5), SlowStatementLog.transactionThreshold());
    }

    @Test
    void transactionsAreWatchedByDefaultAtOneSecond() {
        assertEquals(Duration.ofSeconds(1), SlowStatementLog.DEFAULT_TRANSACTION_THRESHOLD);
        SlowStatementLog.transactionThreshold(SlowStatementLog.DEFAULT_TRANSACTION_THRESHOLD);
        assertTrue(SlowStatementLog.active());
        assertNotNull(TransactionRecord.take(false));
        assertEquals(Duration.ofSeconds(1), SlowStatementLog.transactionThreshold());
        // Zero, the value a configuration sets to switch the watch off, stops it as null does, and so does a
        // negative duration.
        SlowStatementLog.transactionThreshold(Duration.ZERO);
        assertNull(SlowStatementLog.transactionThreshold());
        SlowStatementLog.transactionThreshold(Duration.ofMillis(-1));
        assertNull(SlowStatementLog.transactionThreshold());
    }

    @Test
    void aStatementOutsideATransactionIsNotObservedWhileOnlyTransactionsAreWatched() {
        var provider = new JdbcTransactionTemplateProviderImpl();
        assertFalse(TransactionScope.inTransaction(provider));
        assertFalse(SlowStatementLog.observes(false));
        transaction(REQUIRED_OPTIONS, () -> {
            // The block has not opened its transaction yet; its first statement is still to be credited.
            assertTrue(TransactionScope.inTransaction(provider));
            read();
            assertTrue(TransactionScope.inTransaction(provider));
        });
        assertFalse(TransactionScope.inTransaction(provider));
        assertTrue(SlowStatementLog.observes(true));
        SlowStatementLog.transactionThreshold(null);
        assertFalse(SlowStatementLog.observes(true));
        SlowStatementLog.threshold(Duration.ofNanos(1));
        try {
            assertTrue(SlowStatementLog.observes(false));
        } finally {
            SlowStatementLog.threshold(null);
        }
    }
}
