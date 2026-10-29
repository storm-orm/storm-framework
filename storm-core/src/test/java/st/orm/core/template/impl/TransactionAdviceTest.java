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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import clinic.Customer;
import clinic.CustomerRepository;
import clinic.ReminderService;
import java.sql.SQLException;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.AcceptAdvice;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.TransactionOptions;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlLog;
import st.orm.core.template.TemplateString;

/**
 * A transaction records what it repeats, the same single-row read or write again and again, while the advice log
 * reports that advice, and the advice log reports it as one block when the transaction completes, with the call
 * site that began the transaction and, per repeat, the time its executions spent and the call site of the statement.
 * A joined block counts toward the transaction it joined; a transaction of its own is counted on its own. The block
 * is reported once per call site and piece of advice at INFO, and the performance log's summary leaves such a
 * repeat to the transaction.
 */
class TransactionAdviceTest {

    private static final String LOGGER = "st.orm.sql.advice";
    private static final TransactionOptions REQUIRED_OPTIONS = new TransactionOptions(REQUIRED, null, null, null);
    private static final TransactionOptions NEW_OPTIONS = new TransactionOptions(REQUIRES_NEW, null, null, null);

    private ORMTemplate orm;

    @BeforeEach
    void prepare() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:transaction_advice_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            for (int i = 1; i <= 20; i++) {
                statement.execute("INSERT INTO customer (id, name) VALUES (%d, 'c%d')".formatted(i, i));
            }
        }
        // Storm's own providers, whatever the module's test classpath registers for its other tests.
        orm = ORMTemplate.builder(h2)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        AdviceLog.reset();
        AdviceLog.categories(List.of("correctness", "performance", "design"));
        // An in-memory database answers in microseconds, so the floor would hide every repeat these tests make.
        AdviceLog.repeatFloor(null);
        SlowStatementLogTestSupport.reset();
        // The slow log plays no part: the transaction is recorded for the advice log alone.
        SlowStatementLog.transactionThreshold(null);
    }

    @AfterEach
    void forget() {
        SlowStatementLog.transactionThreshold(SlowStatementLog.DEFAULT_TRANSACTION_THRESHOLD);
        AdviceLog.reset();
    }

    private void readOneByOne(int count) {
        for (int i = 1; i <= count; i++) {
            orm.entity(Customer.class).getById(i);
        }
    }

    private void writeOneByOne(int count) {
        for (int i = 1; i <= count; i++) {
            orm.entity(Customer.class).insert(new Customer(100 + i, "new" + i));
        }
    }

    private static void transaction(TransactionOptions options, Runnable block) {
        TransactionRunner.<Void, RuntimeException>execute(options, transaction -> {
            block.run();
            return null;
        });
    }

    /** Collects the advice log's events at the level while the action runs. */
    private static List<ILoggingEvent> capture(Level level, Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(LOGGER);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(level);
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
        return List.copyOf(appender.list);
    }

    private static List<String> lines(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void aTransactionReportsTheReadItRepeatedWhenItCompletes() {
        var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10)));
        assertEquals(1, events.size(), lines(events).toString());
        var event = events.getFirst();
        // The block is the transaction's, so it goes out under the advice log itself.
        assertEquals(LOGGER, event.getLoggerName());
        assertEquals(Level.INFO, event.getLevel());
        var line = event.getFormattedMessage();
        // The call site of the transaction heads the block; this test lives under st.orm, which the walker treats
        // as framework, so the frame it finds is the test runner's.
        assertTrue(Pattern.compile("^SQL advice \\(transaction\\): [A-Za-z0-9_$.]+:\\d+" + System.lineSeparator())
                .matcher(line).find(), line);
        assertTrue(Pattern.compile(System.lineSeparator() + "\tadvice: getById of Customer ran 10 times, "
                + "[0-9.]+ ms, [0-9.]+ ms each, one row each, at [A-Za-z0-9_$.]+:\\d+; findAllById over the keys reads "
                + "the rows in one round trip").matcher(line).find(), line);
        assertTrue(line.endsWith("See https://orm.st/docs/advice#repeated-select"), line);
        assertFalse(line.contains("transactions"), line);
    }

    @Test
    void nothingIsReportedUntilTheTransactionCompletes() {
        var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(10);
            assertEquals(List.of(), capture(Level.INFO, () -> { }));
        }));
        assertEquals(1, events.size(), lines(events).toString());
    }

    @Test
    void aRepeatedWriteIsAdvisedABatchWithoutAClaimOnWhatCommits() {
        var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> writeOneByOne(10)));
        assertEquals(1, events.size(), lines(events).toString());
        var line = events.getFirst().getFormattedMessage();
        assertTrue(line.contains("\tadvice: insert of Customer ran 10 times, "), line);
        assertTrue(line.contains("one row each, at "), line);
        // Within one transaction a batch changes nothing about what commits, so the advice does not say it does.
        assertTrue(line.endsWith("send the rows as one batch. See https://orm.st/docs/advice#repeated-write"), line);
    }

    @Test
    void everythingATransactionRepeatedIsOneBlockHeaviestFirst() {
        var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(10);
            writeOneByOne(10);
        }));
        assertEquals(1, events.size(), lines(events).toString());
        var line = events.getFirst().getFormattedMessage();
        assertEquals(2, line.split(System.lineSeparator() + "\tadvice: ").length - 1, line);
        assertTrue(line.contains("#repeated-select"), line);
        assertTrue(line.contains("#repeated-write"), line);
    }

    @Test
    void aPieceOfAdviceWhoseLoggerIsOffLeavesTheBlock() {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(LOGGER + ".repeated-write");
        logger.setLevel(Level.OFF);
        try {
            var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> {
                readOneByOne(10);
                writeOneByOne(10);
            }));
            assertEquals(1, events.size(), lines(events).toString());
            var line = events.getFirst().getFormattedMessage();
            assertTrue(line.contains("#repeated-select"), line);
            assertFalse(line.contains("#repeated-write"), line);
        } finally {
            logger.setLevel(null);
        }
    }

    @Test
    void atInfoEachTransactionCallSiteReportsItsOwnRepeat() {
        var service = new ReminderService(() -> readOneByOne(10));
        var events = capture(Level.INFO, () -> {
            service.remindOwners();
            service.remindVets();
            // The same call site again: reported there already.
            service.remindOwners();
        });
        assertEquals(2, events.size(), lines(events).toString());
        var first = events.get(0).getFormattedMessage();
        var second = events.get(1).getFormattedMessage();
        assertTrue(first.startsWith("SQL advice (transaction): ReminderService.java:"), first);
        assertTrue(second.startsWith("SQL advice (transaction): ReminderService.java:"), second);
        assertFalse(first.lines().findFirst().equals(second.lines().findFirst()), lines(events).toString());
    }

    @Test
    void aRepeatThroughARepositoryMethodNamesTheCallerThroughIt() {
        var customers = orm.repository(CustomerRepository.class);
        var service = new ReminderService(() -> {
            for (int i = 1; i <= 10; i++) {
                customers.lookUp(i);
            }
        });
        var events = capture(Level.INFO, service::remindOwners);
        assertEquals(1, events.size(), lines(events).toString());
        var line = events.getFirst().getFormattedMessage();
        // The line inside the repository says which query ran; the loop in the service is the line to change.
        assertTrue(Pattern.compile("one row each, at ReminderService\\.java:\\d+ via CustomerRepository\\.lookUp; ")
                .matcher(line).find(), line);
    }

    @Test
    void aShapeRepeatedFromTwoCallersIsTwoPiecesOfAdviceEachAtItsOwnCaller() {
        var customers = orm.repository(CustomerRepository.class);
        var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> {
            ReminderService.lookUpOwners(customers);
            ReminderService.lookUpVets(customers);
        }));
        assertEquals(1, events.size(), lines(events).toString());
        var line = events.getFirst().getFormattedMessage();
        var sites = Pattern.compile("ran (\\d+) times, [0-9.]+ ms, [0-9.]+ ms each, one row each, at (ReminderService\\.java:\\d+) via")
                .matcher(line);
        var counts = new HashMap<String, String>();
        while (sites.find()) {
            counts.put(sites.group(2), sites.group(1));
        }
        // Two loops, two pieces of advice, each with its own count at its own line.
        assertEquals(2, counts.size(), line);
        assertEquals(Set.of("10", "12"), new HashSet<>(counts.values()), line);
    }

    @Test
    void aRepeatBelowTheFloorDrawsNoAdvice() {
        AdviceLog.repeatFloor(Duration.ofMinutes(1));
        try {
            assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10))));
        } finally {
            AdviceLog.repeatFloor(null);
        }
        assertEquals(1, capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10))).size());
    }

    @Test
    void theFloorIsFiftyMillisecondsUnlessSet() {
        AdviceLog.reset();
        assertEquals(AdviceLog.DEFAULT_REPEAT_FLOOR, AdviceLog.repeatFloor());
        AdviceLog.repeatFloor(Duration.ZERO);
        assertNull(AdviceLog.repeatFloor());
        AdviceLog.repeatFloor(Duration.ofMillis(-1));
        assertNull(AdviceLog.repeatFloor());
    }

    private static void otherWork() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    @Test
    void aTransactionThatHeldItsConnectionIdleIsAdvisedInTheBlockWithOrWithoutARepeat() {
        SlowStatementLog.transactionThreshold(Duration.ofMillis(50));
        // Alone: a transaction whose only advice is the idle time still gets its block.
        var alone = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(1);
            otherWork();
        }));
        assertEquals(1, alone.size(), lines(alone).toString());
        var line = alone.getFirst().getFormattedMessage();
        assertTrue(line.startsWith("SQL advice (transaction)"), line);
        assertTrue(line.contains("\tadvice: the transaction held its connection "), line);
        assertTrue(line.contains("#idle-transaction"), line);
        assertFalse(line.contains("#repeated-select"), line);
        // Beside a repeat: one block, the repeat first and the idle advice after it.
        var beside = capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(10);
            otherWork();
        }));
        assertEquals(1, beside.size(), lines(beside).toString());
        line = beside.getFirst().getFormattedMessage();
        assertTrue(line.indexOf("#repeated-select") < line.indexOf("#idle-transaction"), line);
    }

    @Test
    void theIdleAdviceStaysOutOfTheBlockWhilePerformanceAdviceIsNotReported() {
        SlowStatementLog.transactionThreshold(Duration.ofMillis(50));
        AdviceLog.reset();
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(1);
            otherWork();
        })));
    }

    @Test
    void fewerExecutionsThanTheThresholdDrawNoAdvice() {
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(9))));
    }

    @Test
    void aJoinedBlockCountsTowardTheTransactionItJoined() {
        var events = capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(5);
            transaction(REQUIRED_OPTIONS, () -> readOneByOne(5));
        }));
        assertEquals(1, events.size(), lines(events).toString());
        assertTrue(events.getFirst().getFormattedMessage().contains("ran 10 times, "), lines(events).toString());
    }

    @Test
    void aTransactionOfItsOwnInsideAnotherIsCountedOnItsOwn() {
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(5);
            transaction(NEW_OPTIONS, () -> readOneByOne(5));
        })));
        var events = capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> {
            readOneByOne(10);
            transaction(NEW_OPTIONS, () -> readOneByOne(10));
        }));
        // The inner transaction completes first; at TRACE each transaction reports its own repeat.
        assertEquals(2, events.size(), lines(events).toString());
    }

    @Test
    void atInfoARepeatIsReportedOncePerCallSiteAndAtDebugOncePerShapeThere() {
        var once = capture(Level.INFO, () -> {
            transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
            transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
        });
        assertEquals(1, once.size(), lines(once).toString());
        AdviceLog.reset();
        AdviceLog.categories(List.of("performance"));
        AdviceLog.repeatFloor(null);
        var perShape = capture(Level.DEBUG, () -> {
            transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
            transaction(REQUIRED_OPTIONS, () -> writeOneByOne(10));
            transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
        });
        assertEquals(2, perShape.size(), lines(perShape).toString());
        assertTrue(perShape.stream().allMatch(event -> event.getLevel() == Level.DEBUG), lines(perShape).toString());
    }

    @Test
    @AcceptAdvice("repeated-select")
    void acceptedOnTheMethodRunningTheTransactionItIsNotReported() {
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10))));
    }

    @Test
    void withoutThePerformanceCategoryNothingIsRecorded() {
        AdviceLog.reset();
        assertFalse(AdviceLog.recordsRepeats());
        assertNull(TransactionRecord.take(false));
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10))));
        // The slow log's watch records the transaction, but not its repeats.
        SlowStatementLog.transactionThreshold(Duration.ofSeconds(1));
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10))));
    }

    private SqlLog.Summary record(Runnable work) {
        try (var scope = SqlLog.open("call", 1_000)) {
            work.run();
            scope.close();
            return scope.summary();
        }
    }

    @Test
    void theSummaryLeavesTheExecutionsATransactionAdvisedOnToTheTransaction() {
        // The advice log reports the advice, so the transaction records its repeats and advises on them.
        var events = capture(Level.TRACE, () -> {
            var summary = record(() -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10)));
            assertEquals(10, summary.statementCount());
            assertTrue(summary.statements().stream().allMatch(statement -> statement.transactionId() != 0),
                    summary.statements().toString());
            assertEquals(List.of(), SqlLogRenderer.advisories(summary));
            // Two transactions that each advised on the shape leave nothing for the summary either.
            summary = record(() -> {
                transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
                transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
            });
            assertEquals(List.of(), SqlLogRenderer.advisories(summary));
            // What no single transaction saw remains: a loop that opens a transaction per read.
            summary = record(() -> {
                transaction(REQUIRED_OPTIONS, () -> readOneByOne(10));
                for (int i = 1; i <= 10; i++) {
                    int id = i;
                    transaction(REQUIRED_OPTIONS, () -> orm.entity(Customer.class).getById(id));
                }
            });
            var advisories = SqlLogRenderer.advisories(summary);
            assertEquals(1, advisories.size(), advisories.toString());
            assertTrue(advisories.getFirst().startsWith("getById of Customer ran 10 times, "),
                    advisories.getFirst());
            assertTrue(advisories.getFirst().contains("one row each, in 10 transactions of their own; findAllById "
                    + "over the keys reads the rows in one round trip"), advisories.getFirst());
        });
        // The four transactions that repeated the read each reported it; the ten single reads repeated nothing.
        assertEquals(4, events.size(), lines(events).toString());
    }

    @Test
    void theSummaryKeepsTheExecutionsOfAnotherCallerOfAShapeATransactionAdvisedOn() {
        var customers = orm.repository(CustomerRepository.class);
        var events = capture(Level.TRACE, () -> {
            SqlLog.Summary summary;
            try (var scope = SqlLog.open("call", 1_000, true)) {
                // The transaction advises on the shape at this loop's caller.
                transaction(REQUIRED_OPTIONS, () -> ReminderService.lookUpOwners(customers));
                // The same shape from another caller, a transaction per read, is the summary's.
                for (int i = 1; i <= 10; i++) {
                    int id = i;
                    transaction(REQUIRED_OPTIONS, () -> ReminderService.lookUpOne(customers, id));
                }
                scope.close();
                summary = scope.summary();
            }
            var advisories = SqlLogRenderer.advisories(summary);
            assertEquals(1, advisories.size(), advisories.toString());
            assertTrue(advisories.getFirst().contains("ran 10 times, "), advisories.getFirst());
            assertTrue(advisories.getFirst().contains("in 10 transactions of their own, at ReminderService.java:"),
                    advisories.getFirst());
        });
        assertEquals(1, events.size(), lines(events).toString());
    }

    private SqlLog.Summary record(int limit, Runnable work) {
        try (var scope = SqlLog.open("task", limit)) {
            work.run();
            scope.close();
            return scope.summary();
        }
    }

    @Test
    void aRepeatThatStartsPastTheTablesLimitIsAdvisedOn() {
        AdviceLog.reset();
        AdviceLog.repeatFloor(null);
        var summary = record(5, () -> {
            orm.entity(Customer.class).findAll();
            orm.entity(Customer.class).findAll();
            orm.entity(Customer.class).findAll();
            orm.entity(Customer.class).findAll();
            orm.entity(Customer.class).findAll();
            // Past the table's limit of five, a transaction per lookup.
            for (int i = 1; i <= 12; i++) {
                int id = i;
                transaction(REQUIRED_OPTIONS, () -> orm.entity(Customer.class).getById(id));
            }
        });
        assertEquals(17, summary.statementCount());
        assertEquals(5, summary.statements().size());
        var advisories = SqlLogRenderer.advisories(summary);
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("getById of Customer ran 12 times, "), advisories.getFirst());
    }

    @Test
    void aRepeatThatRunsPastTheTablesLimitIsAdvisedOnWithEveryExecution() {
        AdviceLog.reset();
        AdviceLog.repeatFloor(null);
        var summary = record(5, () -> readOneByOne(20));
        assertEquals(5, summary.statements().size());
        var advisories = SqlLogRenderer.advisories(summary);
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("getById of Customer ran 20 times, "), advisories.getFirst());
    }

    @Test
    void theExecutionsATransactionAdvisedOnPastTheTablesLimitAreLeftToIt() {
        var events = capture(Level.TRACE, () -> {
            var summary = record(5, () -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(20)));
            assertEquals(5, summary.statements().size());
            assertEquals(List.of(), SqlLogRenderer.advisories(summary));
        });
        assertEquals(1, events.size(), lines(events).toString());
    }

    @Test
    void theStatementsPastTheTablesLimitAreTimedAndCountedPerOperationAndType() {
        var summary = record(3, () -> {
            for (int i = 0; i < 3; i++) {
                orm.entity(Customer.class).findAll();
            }
            // Past the limit: reads of a type, which can repeat, and statements of none, which cannot.
            for (int i = 0; i < 4; i++) {
                orm.entity(Customer.class).findAll();
                orm.query(TemplateString.of("SELECT COUNT(*) FROM customer"))
                        .getSingleResult(Long.class);
            }
        });
        assertEquals(11, summary.statementCount());
        assertEquals(3, summary.statements().size());
        var unrecorded = summary.unrecorded();
        assertTrue(unrecorded != null && unrecorded.nanos() > 0, String.valueOf(unrecorded));
        assertEquals(2, unrecorded.groups().size(), unrecorded.toString());
        assertTrue(unrecorded.groups().stream().allMatch(group -> group.executions() == 4), unrecorded.toString());
        assertTrue(unrecorded.groups().stream().anyMatch(group -> "Customer".equals(group.type())),
                unrecorded.toString());
        assertTrue(unrecorded.groups().stream().anyMatch(group -> group.type() == null), unrecorded.toString());
        var rendered = SqlLogRenderer.render(summary);
        assertFalse(rendered.contains("+ ms in database"), rendered);
        assertTrue(Pattern.compile("\\(8 statements not recorded, \\d+ ms: 4 SELECT").matcher(rendered).find(),
                rendered);
        // The database time covers every execution: the recorded ones and the ones past the limit.
        long recordedNanos = summary.statements().stream().mapToLong(SqlLog.Statement::durationNanos).sum();
        assertEquals(recordedNanos + unrecorded.nanos(), summary.databaseNanos());
    }

    private static final Metamodel<Customer, Integer> CUSTOMER_ID = Metamodel.of(Customer.class, "id");

    /** Returns the one advice line of the one block the action's transaction reported. */
    private String adviceOf(Runnable work) {
        var events = capture(Level.INFO, () -> transaction(REQUIRED_OPTIONS, work));
        assertEquals(1, events.size(), lines(events).toString());
        var line = events.getFirst().getFormattedMessage();
        int advice = line.indexOf("\tadvice: ");
        assertTrue(advice >= 0, line);
        return line.substring(advice + "\tadvice: ".length());
    }

    @Test
    void aWriteByConditionThatTouchedNoRowIsAdvisedAsOneStatementOverAllOfThem() {
        var line = adviceOf(() -> {
            for (int i = 1; i <= 10; i++) {
                // No customer has an id past 100, so every delete affects no row.
                orm.entity(Customer.class).delete().where(CUSTOMER_ID, EQUALS, 100 + i)
                        .executeUpdate();
            }
        });
        assertTrue(line.startsWith("the same DELETE of Customer ran 10 times, "), line);
        assertTrue(Pattern.compile(", [0-9.]+ ms each, no rows, at ").matcher(line).find(), line);
        assertTrue(line.contains("; one statement whose condition covers all of them, such as a where with IN over "
                + "what the loop varies, writes them in one round trip. See"), line);
        assertFalse(line.contains("overloads that take a list"), line);
    }

    @Test
    void aWriteTheRepositoryMadeForAnEntityIsAdvisedTheListOverloads() {
        var line = adviceOf(() -> {
            for (int i = 1; i <= 10; i++) {
                orm.entity(Customer.class).update(new Customer(i, "renamed" + i));
            }
        });
        assertTrue(line.startsWith("update of Customer ran 10 times, "), line);
        assertTrue(line.contains(", one row each, at "), line);
        assertTrue(line.contains("; the overloads that take a list send the rows as one batch. See"), line);
    }

    @Test
    void aRepeatedUpsertIsNamedOneAndAdvisedAReadFirst() {
        var line = adviceOf(() -> {
            for (int i = 1; i <= 10; i++) {
                // Every customer exists, so every upsert writes back a row that is there. The dialect recognizes the
                // statement as the upsert it is, a MERGE that both updates and inserts.
                orm.query(TemplateString.raw("""
                        MERGE INTO \0 USING (VALUES (\0, \0)) AS src(id, name) ON \0.id = src.id
                        WHEN MATCHED THEN UPDATE SET name = src.name
                        WHEN NOT MATCHED THEN INSERT (id, name) VALUES (src.id, src.name)""", Customer.class, i, "c" + i,
                        Customer.class)).executeUpdate();
            }
        });
        assertTrue(line.startsWith("the same UPSERT of Customer ran 10 times, "), line);
        assertTrue(line.contains("; where the rows mostly exist and rarely change, reading them first and writing only "
                + "the new or changed ones avoids the writes. Otherwise, the overloads that take a list send the rows "
                + "as one batch. See"), line);
    }

    @Test
    void aRepeatedCountIsAdvisedTheKeysThatExist() {
        var line = adviceOf(() -> {
            for (int i = 1; i <= 10; i++) {
                orm.entity(Customer.class).selectCount().where(CUSTOMER_ID, EQUALS, i)
                        .getSingleResult();
            }
        });
        assertTrue(line.startsWith("the same SELECT of Customer ran 10 times, "), line);
        assertTrue(line.contains("; one query for all of them, with a where with IN over what the loop varies, "
                + "selects the keys that exist in one round trip, with a GROUP BY on it where the counts themselves "
                + "are used. See"), line);
        assertFalse(line.contains("findAllById"), line);
    }

    @Test
    void aRepeatedExistenceCheckIsAdvisedTheListFormOfTheCall() {
        var line = adviceOf(() -> {
            for (int i = 1; i <= 10; i++) {
                orm.entity(Customer.class).existsById(i);
            }
        });
        // existsById counts through the query builder, yet the advice names the call the application made.
        assertTrue(line.startsWith("existsById of Customer ran 10 times, "), line);
        assertTrue(line.contains("; findAllById over the keys reads the ones that exist in one round trip. See"), line);
        assertFalse(line.contains("GROUP BY"), line);
    }

    @Test
    void aRepeatedLookupByRefIsAdvisedTheListFormOfTheCall() {
        var line = adviceOf(() -> {
            for (int i = 1; i <= 10; i++) {
                orm.entity(Customer.class).findByRef(st.orm.Ref.of(Customer.class, i));
            }
        });
        assertTrue(line.startsWith("findByRef of Customer ran 10 times, "), line);
        assertTrue(line.contains("; findAllByRef over the keys reads the rows in one round trip. See"), line);
    }

    @Test
    void aJoinedBlockThatRunsTheFirstStatementReportsUnderTheSiteThatBeganTheTransaction() {
        var service = new ReminderService(() -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10)));
        var events = capture(Level.INFO, service::remindOwners);
        assertEquals(1, events.size(), lines(events).toString());
        var line = events.getFirst().getFormattedMessage();
        assertTrue(line.startsWith("SQL advice (transaction): ReminderService.java:"), line);
    }

    @Test
    void aStatementWithoutATypeIsNotCountedAsARepeat() {
        assertEquals(List.of(), capture(Level.TRACE, () -> transaction(REQUIRED_OPTIONS, () -> {
            for (int i = 0; i < 12; i++) {
                orm.query(TemplateString.of("SELECT COUNT(*) FROM customer")).getSingleResult(Long.class);
            }
        })));
    }

    @Test
    void aRecordTakenOutsideAnyTransactionBlockNamesNoSite() {
        capture(Level.INFO, () -> {
            var record = TransactionRecord.take(false);
            assertNotNull(record);
            assertNull(record.beganAt());
        });
    }

    @Test
    void theSummaryKeepsTheExecutionsOfAShapeATransactionDidNotAdviseOn() {
        capture(Level.TRACE, () -> {
            var summary = record(() -> {
                // The first transaction advises on its reads, not on the one count it also runs.
                transaction(REQUIRED_OPTIONS, () -> {
                    readOneByOne(10);
                    orm.entity(Customer.class).selectCount().getSingleResult();
                });
                for (int i = 0; i < 9; i++) {
                    transaction(REQUIRED_OPTIONS, () -> orm.entity(Customer.class).selectCount().getSingleResult());
                }
            });
            var advisories = SqlLogRenderer.advisories(summary);
            assertEquals(1, advisories.size(), advisories.toString());
            assertTrue(advisories.getFirst().startsWith("the same SELECT of Customer ran 10 times, "),
                    advisories.getFirst());
            // The first transaction held the reads beside its count, so the counts did not run in transactions of
            // their own, and the advice does not say they did.
            assertTrue(advisories.getFirst().contains("one row each, in 10 transactions; one query"),
                    advisories.getFirst());
            assertFalse(advisories.getFirst().contains("of their own"), advisories.getFirst());
        });
    }

    @Test
    void theRepeatsPastTheTablesLimitNameTheirCallSiteWhereTheScopeRecordsThem() {
        AdviceLog.reset();
        AdviceLog.repeatFloor(null);
        SqlLog.Summary summary;
        try (var scope = SqlLog.open("task", 3, true)) {
            for (int i = 1; i <= 15; i++) {
                int id = 1 + i % 10;
                transaction(REQUIRED_OPTIONS, () -> orm.entity(Customer.class).getById(id));
            }
            scope.close();
            summary = scope.summary();
        }
        var advisories = SqlLogRenderer.advisories(summary);
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().contains("ran 15 times, "), advisories.getFirst());
        assertTrue(advisories.getFirst().contains(", at "), advisories.getFirst());
    }

    /** A customer read through a view whose column fails as it is computed. */
    @DbTable("failing_customer")
    public record FailingCustomer(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    @Test
    void anAdviceLineOfAFailedExecutionNamesTheFailure() {
        var context = new QueryImpl.QueryContextImpl(st.orm.spi.SqlOperation.SELECT, false, Customer.class,
                st.orm.spi.QueryContext.ExecutionKind.QUERY, "SELECT c.id FROM customer c LIMIT 1",
                st.orm.spi.StatementOrigin.DIRECT, 99, false, null, true, null, false);
        var events = capture(Level.TRACE, () -> {
            var handle = AdviceLog.onExecute(context, "SELECT c.id FROM customer c LIMIT 1",
                    List.of(Advice.UNORDERED_LIMIT.format()));
            assertNotNull(handle);
            handle.error(new java.sql.SQLTimeoutException("cancelled"));
            handle.close(0, true);
        });
        assertEquals(1, events.size(), lines(events).toString());
        assertTrue(events.getFirst().getFormattedMessage().contains(" in database, failed (SQLTimeoutException)"),
                lines(events).toString());
    }

    @Test
    void anAdviceLineStatesAFailedExecutionAndAStreamClosedBeforeItsEnd() {
        orm.query(TemplateString.of("CREATE VIEW failing_customer AS SELECT id, CAST(1 / (id - id) AS VARCHAR) AS name "
                + "FROM customer")).executeUpdate();
        var events = capture(Level.TRACE, () -> {
            // A read of the view prepares without error and fails as it executes: its column divides by zero.
            assertThrows(PersistenceException.class,
                    () -> orm.entity(FailingCustomer.class).select().limit(2).getResultList());
            try (var stream = orm.entity(Customer.class).select().limit(5).getResultStream()) {
                assertTrue(stream.findFirst().isPresent());
            }
        });
        var all = lines(events);
        assertTrue(all.stream().anyMatch(line -> line.startsWith("SQL advice (SELECT FailingCustomer): ")
                && line.contains(" in database, failed (")), all.toString());
        assertTrue(all.stream().anyMatch(line -> line.startsWith("SQL advice (SELECT Customer): ")
                && line.contains("* rows")), all.toString());
    }

    @Test
    void theSummaryCountsEveryExecutionWhileTransactionsAreNotRecorded() {
        AdviceLog.reset();
        // The advice is decided when the scope closes, under the floor in effect then.
        AdviceLog.repeatFloor(null);
        var summary = record(() -> transaction(REQUIRED_OPTIONS, () -> readOneByOne(10)));
        var advisories = SqlLogRenderer.advisories(summary);
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("getById of Customer ran 10 times, "), advisories.getFirst());
    }
}
