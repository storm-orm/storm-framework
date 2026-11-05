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
import java.util.Set;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.Match;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.TemplateString;
import st.orm.core.template.impl.MatchForm;
import st.orm.core.template.impl.PredicateBuilderFactory;
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
    protected void analyze(Statement statement, String table) throws SQLException {
    }

    /**
     * A field of the search probe table with an index that may serve it, for {@link #eachMatchFormIsServedByTheIndexTheDocsNameAndAdvisedOtherwise()}.
     *
     * @param field the field with its match form.
     * @param ddl the statement creating the index, or {@code null} for no index.
     * @param index the name of the index the statement creates, or {@code null} for none.
     * @param served whether the index serves the field in its form: the plan must then use it and the advice stay
     *               silent; otherwise the advice names the index that exists and cannot serve the match, or says no
     *               index does.
     */
    public record SearchIndexProbe(Match<SearchProbe> field, @Nullable String ddl, @Nullable String index,
                                      boolean served) {
    }

    /**
     * Returns the statements that create the search probe table, {@code search_probe(id, title, title_lower)} with
     * {@code title_lower} the stored lowered title, which a prefix or exact match compares on its own. Empty where
     * the probe does not apply.
     */
    protected List<String> searchProbeDdl() {
        return List.of();
    }

    /**
     * Returns the index probes of the dialect: for each match form, where the database has an index that serves it,
     * that index, and an index that exists and cannot serve the form, or none.
     */
    protected List<SearchIndexProbe> searchIndexProbes() {
        return List.of();
    }

    /**
     * Prepares the session the probe reads its plans in, so the plans show what an index can serve rather than what
     * the planner prefers for a small table: a database whose planner weighs a sequential scan against an index
     * turns the sequential scan off here.
     */
    protected void preferIndexes(Statement statement) throws SQLException {
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
            analyze(statement, "advice_pet");
        }
        orm = PreparedStatementTemplate.ORM(dataSource);
    }

    @AfterEach
    final void drop() throws SQLException {
        SlowStatementLog.threshold(null);
        for (String table : List.of("advice_pet", "advice_note", "search_probe")) {
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
            var parts = event.getFormattedMessage().split("\\R");
            for (int i = 1; i < parts.length; i++) {
                var part = parts[i];
                if (!LoggedAdvice.isAdviceLine(part) && !part.startsWith("\tshape: ")
                        && !part.startsWith("\tparameters: ")) {
                    sql.append(part.strip()).append(' ');
                }
            }
            lines.add(new SlowLine(sql.toString().strip(), LoggedAdvice.in(event.getFormattedMessage())));
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
            analyze(ddl, "advice_pet");
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
                + "without a temporary table or sort\ncovering: with body appended it "), advice);
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

    /** A table of titles with a stored lowered copy, the column a prefix or exact match compares on its own. */
    @DbTable("search_probe")
    public record SearchProbe(@PK(generation = NONE) Integer id, String title, String titleLower)
            implements Entity<Integer> {}

    protected static final Metamodel<SearchProbe, String> PROBE_TITLE = Metamodel.of(SearchProbe.class, "title");
    protected static final Metamodel<SearchProbe, String> PROBE_TITLE_LOWER = Metamodel.of(SearchProbe.class, "titleLower");

    /**
     * Returns whether the plan reads the probe table through an index named {@code probe_...}, as a range or a seek
     * rather than a scan of the index: a database that prints the index name for a full scan of it says so in its own
     * words.
     */
    protected boolean servedByIndex(String plan) {
        return plan.toLowerCase(Locale.ROOT).contains("probe_");
    }

    /** Creates the search probe table with its rows, and the given index. */
    private void createSearchProbe(@Nullable String indexDdl) throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            try {
                statement.execute("DROP TABLE search_probe");
            } catch (SQLException ignore) {
                // Not there yet.
            }
            for (String sql : searchProbeDdl()) {
                statement.execute(sql);
            }
        }
        String[] words = {"alpha", "bravo", "charlie", "delta", "echo", "foxtrot", "golf", "hotel", "india",
                "juliet", "kilo", "lima", "mike", "november", "oscar", "papa", "quebec", "romeo", "sierra", "tango",
                "victor", "whiskey", "xray", "yankee", "zulu"};
        try (var connection = dataSource.getConnection();
             var insert = connection.prepareStatement("INSERT INTO search_probe (id, title) VALUES (?, ?)")) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            for (int id = 1; id <= ROWS; id++) {
                String title = switch (id) {
                    case 1 -> "Upside Down";
                    case 2 -> "Pick Me Up";
                    case 3 -> "Grown Ups";
                    case 4 -> "Spider-Man";
                    case 5 -> "O'Connor";
                    default -> words[id % words.length] + " " + words[id * 7 % words.length] + " " + id;
                };
                insert.setInt(1, id);
                insert.setString(2, title);
                insert.addBatch();
                if (id % 1_000 == 0) {
                    insert.executeBatch();
                }
            }
            connection.commit();
            connection.setAutoCommit(autoCommit);
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            if (indexDdl != null) {
                statement.execute(indexDdl);
            }
            analyze(statement, "search_probe");
        }
    }

    private List<String> titles(String input, Match<SearchProbe> field) {
        return orm.entity(SearchProbe.class).select()
                .where(it -> PredicateBuilderFactory.<SearchProbe, SearchProbe, Integer>search(input, field))
                .getResultList().stream().map(SearchProbe::title).sorted().toList();
    }

    /**
     * Each match form finds the same rows on every database: a word anywhere, at the start of a word, which follows
     * the value's start, a space or a punctuation character such as a hyphen or an apostrophe, as a prefix of the
     * stored lowered value, or as the whole of it.
     */
    @Test
    public void eachMatchFormFindsTheSameRowsOnEveryDatabase() throws SQLException {
        Assumptions.assumeFalse(searchProbeDdl().isEmpty(), "No probe for this dialect.");
        createSearchProbe(null);
        assertEquals(List.of("Grown Ups", "Pick Me Up", "Upside Down"), titles("up", Match.anywhere(PROBE_TITLE)));
        assertEquals(List.of("Grown Ups", "Upside Down"), titles("ps", Match.anywhere(PROBE_TITLE)));
        assertEquals(List.of("Grown Ups", "Pick Me Up", "Upside Down"), titles("up", Match.wordStart(PROBE_TITLE)));
        assertEquals(List.of(), titles("ps", Match.wordStart(PROBE_TITLE)));
        assertEquals(List.of("Spider-Man"), titles("man", Match.wordStart(PROBE_TITLE)));
        assertEquals(List.of("O'Connor"), titles("connor", Match.wordStart(PROBE_TITLE)));
        assertEquals(List.of("Upside Down"), titles("Up", Match.prefix(PROBE_TITLE_LOWER)));
        assertEquals(List.of("Grown Ups"), titles("\"grown ups", Match.exact(PROBE_TITLE_LOWER)));
        assertEquals(List.of(), titles("grown", Match.exact(PROBE_TITLE_LOWER)));
        // suggest takes the same forms; a one-character word takes no part, whatever the index serves.
        assertEquals(List.of("Spider-Man"), orm.entity(SearchProbe.class).select()
                .where(it -> PredicateBuilderFactory.<SearchProbe, SearchProbe, Integer>suggest("Ma", Set.of(), PROBE_TITLE))
                .getResultList().stream().map(SearchProbe::title).toList());
        assertEquals(List.of(), orm.entity(SearchProbe.class).select()
                .where(it -> PredicateBuilderFactory.<SearchProbe, SearchProbe, Integer>suggest("U", Set.of(), PROBE_TITLE))
                .getResultList());
    }

    /**
     * The index the docs name for a match form serves the statement the form renders, and the advice is silent: the
     * database's own plan, read with the word inlined and the planner's preference for a sequential scan of a small
     * table turned off where it has one, uses the index. An index that exists and cannot serve the form is named by
     * the advice as such, and without one the advice says no index serves the field.
     */
    @Test
    public void eachMatchFormIsServedByTheIndexTheDocsNameAndAdvisedOtherwise() throws SQLException {
        var probes = searchIndexProbes();
        Assumptions.assumeFalse(probes.isEmpty(), "No probe for this dialect.");
        for (var probe : probes) {
            createSearchProbe(probe.ddl());
            orm.loadSchema();
            String form = MatchForm.of(probe.field()).factory();
            String input = probe.field() instanceof Match.Exact<?> ? "\"grown ups\"" : "up";
            var results = new ArrayList<SearchProbe>();
            var lines = slowLines(() -> results.addAll(orm.entity(SearchProbe.class).select()
                    .where(it -> PredicateBuilderFactory.<SearchProbe, SearchProbe, Integer>search(input, probe.field()))
                    .getResultList()));
            assertFalse(results.isEmpty(), probe.toString());
            var advice = lines.getFirst().advice().stream()
                    .filter(text -> text.contains("#unindexed-search"))
                    .toList();
            if (probe.served()) {
                assertEquals(List.of(), advice, probe.toString());
                try (var connection = dataSource.getConnection()) {
                    try (var statement = connection.createStatement()) {
                        preferIndexes(statement);
                    }
                    var plan = plan(connection, lines.getFirst().sql());
                    assertTrue(servedByIndex(plan), probe + " is not served by its index:\n"
                            + lines.getFirst().sql() + "\n" + plan);
                }
            } else {
                assertEquals(1, advice.size(), probe + ": " + advice);
                String expected = probe.index() == null
                        ? "no index on search_probe serves " + form + "("
                        : "index " + probe.index() + " on search_probe ";
                assertTrue(advice.getFirst().toLowerCase(Locale.ROOT).startsWith(expected.toLowerCase(Locale.ROOT)),
                        probe + ": " + advice.getFirst());
            }
        }
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE search_probe");
        }
    }
}
