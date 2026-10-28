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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.EQUALS;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.SlowStatementLog;

/**
 * The index a missing-index advice names is the one the database uses: the test runs a statement against a table
 * without its index, takes the index the advice names, creates it, and reads the database's own plan for the same
 * statement to find the index in it. The plan is read here, in the test, to prove the advice right on each database;
 * the advice itself is decided from the statement and the schema alone.
 *
 * <p>The table holds enough rows for an index to pay off, and a condition that selects few of them, so a planner that
 * weighs the index against reading the table chooses it.</p>
 */
public abstract class AbstractMissingIndexConformanceTest {

    @DbTable("advice_pet")
    public record AdvicePet(@PK(generation = NONE) Integer id, int typeId, String name) implements Entity<Integer> {}

    /** A table with wide text columns, whose index key the advice sizes against the database's limit. */
    @DbTable("advice_note")
    public record AdviceNote(@PK(generation = NONE) Integer id, int petId, String title, String body)
            implements Entity<Integer> {}

    private static final Metamodel<AdvicePet, Integer> TYPE_ID = Metamodel.of(AdvicePet.class, "typeId");
    private static final Metamodel<AdvicePet, String> NAME = Metamodel.of(AdvicePet.class, "name");
    private static final Metamodel<AdviceNote, Integer> PET_ID = Metamodel.of(AdviceNote.class, "petId");
    private static final Metamodel<AdviceNote, String> TITLE = Metamodel.of(AdviceNote.class, "title");
    private static final Metamodel<AdviceNote, String> BODY = Metamodel.of(AdviceNote.class, "body");

    /**
     * The rows of the table, and the distinct types among them: each type selects five rows, spread over the table,
     * the few rows the advice says an index finds directly. A condition that selects rows from every block of a small
     * table is cheaper to read in full, which some planners rightly choose.
     */
    private static final int ROWS = 20_000;
    private static final int TYPES = 4_000;

    private static final Pattern ADVISED_INDEX = Pattern.compile("advice_pet\\(([a-z_, ]+)\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern KEY_LIMIT = Pattern.compile("passes the (\\d+) bytes the database allows a key");

    private DataSource dataSource;
    private ORMTemplate orm;

    /** Refreshes the planner's statistics of the table, once its rows or indexes change. */
    protected void analyze(Statement statement) throws SQLException {
    }

    /** Returns the database's plan for the statement, as text. */
    protected String plan(Connection connection, String sql) throws SQLException {
        var plan = new StringBuilder();
        try (var statement = connection.createStatement(); var resultSet = statement.executeQuery("EXPLAIN " + sql)) {
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                for (int column = 1; column <= columns; column++) {
                    plan.append(resultSet.getString(column)).append(' ');
                }
                plan.append('\n');
            }
        }
        return plan.toString();
    }

    @BeforeEach
    final void prepare(DataSource dataSource) throws SQLException {
        this.dataSource = dataSource;
        drop();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE advice_pet (id INT PRIMARY KEY, type_id INT NOT NULL, name VARCHAR(50) NOT NULL)");
            statement.execute("CREATE TABLE advice_note (id INT PRIMARY KEY, pet_id INT NOT NULL, "
                    + "title VARCHAR(500) NOT NULL, body VARCHAR(500) NOT NULL)");
        }
        try (var connection = dataSource.getConnection();
             var insert = connection.prepareStatement("INSERT INTO advice_pet (id, type_id, name) VALUES (?, ?, ?)")) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            for (int id = 1; id <= ROWS; id++) {
                insert.setInt(1, id);
                insert.setInt(2, id % TYPES);
                insert.setString(3, "pet" + (id * 7919 % ROWS));
                insert.addBatch();
                if (id % 1_000 == 0) {
                    insert.executeBatch();
                }
            }
            connection.commit();
            connection.setAutoCommit(autoCommit);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            analyze(statement);
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
    }

    @AfterEach
    final void drop() throws SQLException {
        SlowStatementLog.threshold(null);
        for (String table : List.of("advice_pet", "advice_note")) {
            try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
                statement.execute("DROP TABLE " + table);
            } catch (SQLException ignore) {
                // Not there yet.
            }
        }
    }

    /** A slow line: the statement as sent, its values inlined, and the advice it carries. */
    private record SlowLine(String sql, List<String> advice) {}

    /** Runs the action with every statement slow and its values inlined, and returns its slow lines. */
    private List<SlowLine> slowLines(Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.TRACE);
        logger.addAppender(appender);
        logger.setAdditive(false);
        SlowStatementLog.threshold(Duration.ofNanos(1));
        try {
            action.run();
        } finally {
            SlowStatementLog.threshold(null);
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previous);
            appender.stop();
        }
        var lines = new ArrayList<SlowLine>();
        for (var event : appender.list) {
            var sql = new StringBuilder();
            var advice = new ArrayList<String>();
            var parts = event.getFormattedMessage().split("\\R");
            for (int i = 1; i < parts.length; i++) {
                var part = parts[i];
                if (part.startsWith("\tadvice: ")) {
                    advice.add(part.substring("\tadvice: ".length()));
                } else if (!part.startsWith("\tshape: ") && !part.startsWith("\tparameters: ")) {
                    sql.append(part.strip()).append(' ');
                }
            }
            lines.add(new SlowLine(sql.toString().strip(), advice));
        }
        return lines;
    }

    /**
     * Runs the statement, creates the first index its missing-index advice names, and asserts that the database's
     * plan for the statement uses it.
     */
    private void assertAdvisedIndexIsUsed(Runnable statement) throws SQLException {
        orm.loadSchema();
        var line = slowLines(statement).stream()
                .filter(slow -> slow.advice().stream().anyMatch(advice -> advice.contains("#missing-index")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The statement drew no missing-index advice."));
        var advice = line.advice().stream().filter(text -> text.contains("#missing-index")).findFirst().orElseThrow();
        var advised = ADVISED_INDEX.matcher(advice);
        assertTrue(advised.find(), advice);
        try (var connection = dataSource.getConnection(); var ddl = connection.createStatement()) {
            ddl.execute("CREATE INDEX advised_idx ON advice_pet (" + advised.group(1) + ")");
            analyze(ddl);
        }
        try (var connection = dataSource.getConnection()) {
            var plan = plan(connection, line.sql());
            assertTrue(plan.toLowerCase(Locale.ROOT).contains("advised_idx"),
                    "The plan for the advised index advice_pet(" + advised.group(1) + ") does not use it:\n" + plan);
        }
    }

    @Test
    public void theIndexAdvisedForAFilterIsTheOneTheDatabaseUses() throws SQLException {
        assertAdvisedIndexIsUsed(() -> orm.entity(AdvicePet.class).select().where(TYPE_ID, EQUALS, 7).getResultList());
    }

    @Test
    public void theIndexAdvisedForALimitedOrderIsTheOneTheDatabaseUses() throws SQLException {
        assertAdvisedIndexIsUsed(() -> orm.entity(AdvicePet.class).select().where(TYPE_ID, EQUALS, 7)
                .orderBy(NAME).limit(20).getResultList());
    }

    /** Returns the missing-index advice the statement draws. */
    private String missingIndexAdvice(Runnable statement) {
        orm.loadSchema();
        return slowLines(statement).stream()
                .flatMap(slow -> slow.advice().stream())
                .filter(advice -> advice.contains("#missing-index"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("The statement drew no missing-index advice."));
    }

    /**
     * Creates an index on the columns and returns whether the database took it as is: refusing it, or creating it
     * with a warning, says the key passes a limit of the database.
     */
    private boolean createsIndex(String columns) throws SQLException {
        try (var connection = dataSource.getConnection(); var ddl = connection.createStatement()) {
            try {
                ddl.execute("CREATE INDEX advised_idx ON advice_note (" + columns + ")");
            } catch (SQLException e) {
                return false;
            }
            return ddl.getWarnings() == null;
        }
    }

    /**
     * Asserts that the advice sizes a key as the database does: where it says the key passes the database's limit,
     * the limit is the dialect's, and the database refuses the index or warns on it; where it does not, the database
     * creates the index without a word.
     */
    private void assertKeyLimitIsTheDatabases(String advice, String columns) throws SQLException {
        var limit = KEY_LIMIT.matcher(advice);
        if (limit.find()) {
            assertEquals(orm.dialect().maxIndexKeyBytes(), Integer.parseInt(limit.group(1)), advice);
            assertFalse(createsIndex(columns), "The database created advice_note(" + columns + ") without a word, "
                    + "but the advice says its key passes the limit: " + advice);
        } else {
            assertTrue(createsIndex(columns), "The database refused advice_note(" + columns + ") or warned on it, "
                    + "and the advice does not say its key passes the limit: " + advice);
        }
    }

    @Test
    public void theCoveringColumnsAreAppendedOnlyWhereTheKeyStaysWithinTheDatabasesLimit() throws SQLException {
        var advice = missingIndexAdvice(() -> orm.selectFrom(AdviceNote.class, String.class,
                        TemplateString.raw("MAX(\0)", BODY))
                .where(PET_ID, EQUALS, 7).groupBy(TITLE).getResultList());
        assertTrue(advice.contains("advice_note(pet_id, title) finds the matching rows and reads them already grouped, "
                + "without a temporary table or sort; with body appended it "), advice);
        assertKeyLimitIsTheDatabases(advice, "pet_id, title, body");
    }

    @Test
    public void anIndexWhoseKeyPassesTheDatabasesLimitIsSaidSo() throws SQLException {
        var advice = missingIndexAdvice(() -> orm.entity(AdviceNote.class).selectCount()
                .where(PET_ID, EQUALS, 7).groupBy(TITLE, BODY).getResultList());
        assertTrue(advice.contains("advice_note(pet_id, title, body) finds the matching rows and reads them already "
                + "grouped, without a temporary table or sort"), advice);
        assertKeyLimitIsTheDatabases(advice, "pet_id, title, body");
    }

    @Test
    public void theIndexAdvisedForAGroupedReadIsTheOneTheDatabaseUses() throws SQLException {
        assertAdvisedIndexIsUsed(() -> orm.entity(AdvicePet.class).selectCount().where(TYPE_ID, EQUALS, 7)
                .groupBy(NAME).getResultList());
    }
}
