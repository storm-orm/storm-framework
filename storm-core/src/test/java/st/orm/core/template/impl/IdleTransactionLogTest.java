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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
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
        SlowStatementLog.transactionThreshold(null);
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
        assertTrue(line.contains(" between them it held the connection while the application did other work; "
                + "move that work before the transaction or after it commits. See https://orm.st/docs/advice"
                + "#idle-transaction"), line);
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
        assertTrue(line.contains("it held the connection, and the row locks of its writes, while the application "
                + "did other work"), line);
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
    void withoutAThresholdNoConnectionIsHeld() {
        SlowStatementLog.transactionThreshold(null);
        assertFalse(SlowStatementLog.active());
        assertNull(HeldConnection.take(false));
        var lines = capture(() -> transaction(REQUIRED_OPTIONS, () -> {
            read();
            otherWork();
        }));
        assertEquals(List.of(), lines);
        SlowStatementLog.transactionThreshold(Duration.ofMillis(5));
        assertTrue(SlowStatementLog.active());
        assertEquals(Duration.ofMillis(5), SlowStatementLog.transactionThreshold());
    }
}
