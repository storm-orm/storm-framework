package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.Operator.IN;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import org.springframework.transaction.annotation.Transactional;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.model.City;
import st.orm.core.model.City_;
import st.orm.core.model.PetOwnerRef;
import st.orm.core.model.PetOwnerRef_;
import st.orm.core.template.JpaTemplate;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.SqlLog;
import st.orm.core.template.impl.SqlInterceptorManager;
import st.orm.spi.StatementOrigin;

/**
 * Verifies that a scope reports what a call cost the database: the statements it took whichever repository issued
 * them, how many of those resolved references, and which type carried the weight.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = JpaIntegrationConfig.class)
@DataJpaTest(showSql = false)
public class SqlLogIntegrationTest {

    @Autowired
    private DataSource dataSource;

    @PersistenceContext
    private EntityManager entityManager;

    @Test
    public void testAScopeCoversEveryStatementOfTheCall() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("call", () -> {
            // Two unrelated repositories: a scope covers the call, not one repository.
            orm.entity(City.class).getById(1);
            orm.entity(PetOwnerRef.class).select().getResultList();
            return null;
        }, summaries::add);
        var summary = summaries.getFirst();
        assertEquals("call", summary.name());
        assertEquals(2, summary.statementCount());
        assertEquals(2, summary.byStatement().size(), summary.byStatement().toString());
    }

    @Test
    public void testAScopeCountsTheReferencesTheCallResolved() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("n-plus-one", () -> {
            var pets = orm.entity(PetOwnerRef.class).select().getResultList();
            pets.stream().map(PetOwnerRef::owner).filter(Objects::nonNull).forEach(Ref::fetch);
            return null;
        }, summaries::add);
        var summary = summaries.getFirst();
        // One select, then one statement per reference it left unresolved.
        assertEquals(1, summary.count(StatementOrigin.DIRECT));
        assertTrue(summary.count(StatementOrigin.FETCH) > 0, summary.statements().toString());
        assertTrue(summary.byStatement().stream().anyMatch(line -> line.fetch()),
                summary.byStatement().toString());
    }

    @Test
    public void testAResolvingQueryLeavesTheScopeAtOneStatement() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("fetched", () -> {
            var pets = orm.entity(PetOwnerRef.class).select()
                    .fetch(PetOwnerRef_.owner)
                    .getResultList();
            pets.stream().map(PetOwnerRef::owner).filter(Objects::nonNull).forEach(Ref::fetch);
            return null;
        }, summaries::add);
        var summary = summaries.getFirst();
        assertEquals(1, summary.statementCount());
        assertEquals(0, summary.count(StatementOrigin.FETCH));
    }

    @Test
    public void testACheckedExceptionPropagatesFromRecordThrowingAndIsStillSummarized() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        var thrown = assertThrows(IOException.class,
                () -> SqlLog.recordThrowing("checked", () -> {
                    orm.entity(City.class).getById(1);
                    throw new IOException("disk full");
                }, summaries::add));
        assertEquals("disk full", thrown.getMessage());
        // The default limit applies: the one statement executed before the failure is recorded, not merely counted.
        assertEquals(1, summaries.getFirst().statementCount());
        assertEquals(1, summaries.getFirst().statements().size());
    }

    @Test
    public void testARecorderInstalledAsListenerObservesTheStatementsWithinItsLimit() {
        var orm = ORMTemplate.of(dataSource);
        // The recorder is the building block behind record and open: installed as a listener it counts every
        // statement and keeps up to its limit; a summary built from it reports both figures.
        var recorder = SqlLog.recorder(1);
        SqlInterceptorManager.listen(recorder).run(() -> {
            orm.entity(City.class).getById(1);
            orm.entity(City.class).getById(2);
        });
        var summary = SqlLog.summary("recorded", recorder, 42L);
        assertEquals("recorded", summary.name());
        assertEquals(2, summary.statementCount());
        assertEquals(1, summary.statements().size(), summary.statements().toString());
    }

    @Test
    public void testAFailedCallIsStillSummarized() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        try {
            SqlLog.record("failing", () -> {
                orm.entity(City.class).getById(1);
                throw new IllegalStateException("boom");
            }, summaries::add);
        } catch (IllegalStateException ignore) {
            // The statements leading up to a failure are the evidence, so they are reported.
        }
        assertEquals(1, summaries.getFirst().statementCount());
    }

    @Test
    public void testStatementsBeyondTheLimitAreCountedAndReported() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("limited", 1, (Supplier<Object>) () -> {
            orm.entity(City.class).getById(1);
            orm.entity(City.class).getById(2);
            orm.entity(City.class).getById(3);
            return null;
        }, summaries::add);
        var summary = summaries.getFirst();
        assertEquals(3, summary.statementCount(), "the count covers every statement");
        assertEquals(1, summary.statements().size(), "only the limit is retained");
        assertTrue(summary.truncated());
    }

    @Test
    public void testNoScopeLeaksAfterTheCall() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("scoped", (Supplier<Object>) () -> orm.entity(City.class).getById(1), summaries::add);
        int during = summaries.getFirst().statementCount();
        // A statement executed after the scope closed is not recorded into it.
        orm.entity(City.class).getById(2);
        assertEquals(during, summaries.getFirst().statementCount());
        assertFalse(summaries.getFirst().truncated());
    }

    @Test
    public void testCollectionSizesGroupAsOneStatement() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("in-lists", (Supplier<Object>) () -> {
            orm.entity(City.class).select().where(City_.id, IN, List.of(1)).getResultList();
            orm.entity(City.class).select().where(City_.id, IN, List.of(1, 2)).getResultList();
            orm.entity(City.class).select().where(City_.id, IN, List.of(1, 2, 3)).getResultList();
            return null;
        }, summaries::add);
        var summary = summaries.getFirst();
        assertEquals(3, summary.statementCount());
        // The statements differ in placeholder count but share their template, so they group as one line.
        assertEquals(1, summary.byStatement().size(), summary.byStatement().toString());
        var line = summary.byStatement().getFirst();
        assertEquals(3, line.executions());
        assertEquals(3, line.variants());
    }

    @Test
    public void testStatementsCarryTheirDuration() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("timed", (Supplier<Object>) () -> orm.entity(City.class).getById(1), summaries::add);
        var summary = summaries.getFirst();
        assertTrue(summary.statements().getFirst().durationNanos() > 0, summary.statements().toString());
        assertTrue(summary.databaseNanos() > 0);
        assertEquals(1, summary.peakConcurrency());
    }

    @Test
    public void testDatabaseTimeEndsWhenTheStatementReturns() throws Exception {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.recordThrowing("streamed", () -> {
            // The stream is held open well beyond the statement's return; that time is the application's, not the
            // database's.
            try (var stream = orm.entity(City.class).select().getResultStream()) {
                Thread.sleep(50);
                return stream.count();
            }
        }, summaries::add);
        var statement = summaries.getFirst().statements().getFirst();
        assertTrue(statement.executedNanos() >= statement.startNanos(), statement.toString());
        assertTrue(statement.endNanos() >= statement.executedNanos(), statement.toString());
        assertTrue(statement.durationNanos() < 50_000_000L, "database time excludes the hold: " + statement);
        assertTrue(statement.consumeNanos() >= 50_000_000L, "consumption carries the hold: " + statement);
        assertTrue(summaries.getFirst().databaseNanos() < 50_000_000L);
    }

    @Test
    public void testAReadStillStreamingWhenTheScopeClosesIsInTheSummary() throws Exception {
        var orm = ORMTemplate.of(dataSource);
        var scope = SqlLog.open("streaming");
        var stream = orm.entity(City.class).select().getResultStream();
        try {
            // The database has answered; the application has not finished reading. The summary carries what the
            // execution cost the database, and the rows read so far as a lower bound.
            scope.close();
            var summary = scope.summary();
            assertEquals(1, summary.statementCount());
            assertEquals(1, summary.statements().size(), "the open read is in the summary");
            assertFalse(summary.truncated(), "an open read is not a truncated recording");
            var statement = summary.statements().getFirst();
            assertTrue(statement.durationNanos() > 0, statement.toString());
            assertFalse(statement.exactRows(), statement.toString());
        } finally {
            stream.close();
        }
    }

    @Test
    public void testAFailedExecutionStillCarriesItsDatabaseTime() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        assertThrows(PersistenceException.class, () -> SqlLog.record("failing", (Supplier<Object>) () ->
                orm.query("SELECT * FROM no_such_table").getResultList(), summaries::add));
        var statement = summaries.getFirst().statements().getFirst();
        // The failure closes the execution without a separate return; database time then runs to the failure.
        assertTrue(statement.executedNanos() >= statement.startNanos(), statement.toString());
        assertTrue(statement.consumeNanos() < 1_000_000L, statement.toString());
    }

    @Test
    public void testAScopeOpensAndClosesWithTryWithResources() {
        var orm = ORMTemplate.of(dataSource);
        var scope = SqlLog.open("block");
        try (scope) {
            orm.entity(City.class).getById(1);
            orm.entity(City.class).getById(2);
        }
        var summary = scope.summary();
        assertEquals(2, summary.statementCount());
        // A statement executed after the scope closed is not recorded into it.
        orm.entity(City.class).getById(3);
        assertEquals(2, summary.statementCount());
    }

    @Test
    public void testCallSitesNameTheFrameThatCausedTheExecution() throws Exception {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.recordThrowing("sited", 200, true, () -> {
            orm.entity(City.class).getById(1);
            return null;
        }, summaries::add);
        var line = summaries.getFirst().byStatement().getFirst();
        // This test lives under st.orm, which the walker treats as framework, so the frame it finds is the
        // test runner's; an application's own frame is the first outside the framework packages.
        assertNotNull(line.callSite());
        assertTrue(line.callSite().matches(".+:\\d+"), line.callSite());
        assertEquals(1, line.sites());
    }

    @Test
    public void testWithoutCallSitesNoStackIsWalked() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("unsited", (Supplier<Object>) () -> orm.entity(City.class).getById(1), summaries::add);
        assertNull(summaries.getFirst().byStatement().getFirst().callSite());
    }

    @Test
    public void testTheDetailedRenderingCarriesTheFullStatement() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("detailed", (Supplier<Object>) () -> orm.entity(City.class).getById(1), summaries::add);
        String detailed = summaries.getFirst().toDetailedString();
        assertTrue(detailed.contains("statements:"), detailed);
        // The full text follows the summary, un-elided.
        assertTrue(detailed.contains("SELECT c.id, c.name FROM city c WHERE c.id = ?"), detailed);
    }

    @Test
    public void testStatementsCarryTheRowsTheyProduced() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("rows", (Supplier<Object>) () -> {
            orm.entity(City.class).getById(1);
            return orm.entity(City.class).select().getResultList();
        }, summaries::add);
        var summary = summaries.getFirst();
        var byRows = summary.byStatement();
        // The lookup produced one row; the unfiltered select produced as many as the list holds.
        var lookup = byRows.stream().filter(line -> line.statement().contains("WHERE")).findFirst().orElseThrow();
        assertEquals(1, lookup.rows());
        var all = byRows.stream().filter(line -> !line.statement().contains("WHERE")).findFirst().orElseThrow();
        assertTrue(all.rows() > 1, String.valueOf(all.rows()));
        // A fully consumed result is an exact count.
        assertTrue(all.exactRows());
    }

    @Test
    public void testAnEarlyClosedStreamReportsItsCountAsALowerBound() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("partial", (Supplier<Object>) () -> {
            try (var stream = orm.entity(City.class).select().getResultStream()) {
                return stream.findFirst().orElseThrow();
            }
        }, summaries::add);
        var line = summaries.getFirst().byStatement().getFirst();
        // One row was consumed before the stream closed; more may have existed, so the count is a known lower
        // bound and the rendering marks it.
        assertEquals(1, line.rows());
        assertFalse(line.exactRows());
        assertTrue(summaries.getFirst().toString().contains("1* rows"), summaries.getFirst().toString());
    }

    @Transactional(isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
    @Test
    public void testCacheServedReadsAreCounted() {
        var orm = ORMTemplate.of(dataSource);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("cached", (Supplier<Object>) () -> {
            var cities = orm.entity(City.class);
            cities.getById(1);
            // The second read is served by the transaction's entity cache: no statement, one hit.
            cities.getById(1);
            return null;
        }, summaries::add);
        var summary = summaries.getFirst();
        assertEquals(1, summary.statementCount(), summary.toString());
        assertEquals(1, summary.cacheHits());
        assertTrue(summary.toString().contains("1 from cache"), summary.toString());
    }

    @Test
    public void testAScopeCoversTheJpaTemplatePath() {
        var orm = JpaTemplate.ORM(entityManager);
        List<SqlLog.Summary> summaries = new ArrayList<>();
        SqlLog.record("jpa", (Supplier<Object>) () ->
                orm.query("SELECT id, name FROM city WHERE id = 1").getResultList(), summaries::add);
        var summary = summaries.getFirst();
        // The JPA template path brackets its executions like the JDBC path: the statement, its duration, and
        // the rows it produced all reach the scope.
        assertEquals(1, summary.statementCount(), summary.toString());
        var line = summary.byStatement().getFirst();
        assertEquals(1, line.rows());
        assertTrue(line.durationNanos() > 0);
        assertTrue(summary.toString().contains("FROM city"), summary.toString());
    }

    @Test
    public void testAReportedSummaryLogsUnderTheScopeLogger() {
        var orm = ORMTemplate.of(dataSource);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("st.orm.sql.perf");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        var level = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            List<SqlLog.Summary> summaries = new ArrayList<>();
            SqlLog.record("reported", (Supplier<Object>) () -> orm.entity(City.class).getById(1),
                    summaries::add);
            SqlLog.report(summaries.getFirst());
            assertEquals(1, appender.list.size());
            assertTrue(appender.list.getFirst().getFormattedMessage().startsWith("SQL (reported):"),
                    appender.list.getFirst().getFormattedMessage());
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
    }

    @Test
    public void testTheStatementTextsFollowAtTraceRatherThanDebug() {
        var orm = ORMTemplate.of(dataSource);
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("st.orm.sql.perf");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        var level = logger.getLevel();
        try {
            List<SqlLog.Summary> summaries = new ArrayList<>();
            SqlLog.record("levels", (Supplier<Object>) () -> orm.entity(City.class).getById(1), summaries::add);
            var summary = summaries.getFirst();
            // This logger sits under st.orm.sql, so DEBUG arrives by inheritance whenever per-statement logging
            // is raised. Repeating the texts there would print every statement twice.
            logger.setLevel(ch.qos.logback.classic.Level.DEBUG);
            SqlLog.report(summary);
            assertEquals(summary.toString(), appender.list.getFirst().getFormattedMessage());
            appender.list.clear();
            logger.setLevel(ch.qos.logback.classic.Level.TRACE);
            SqlLog.report(summary);
            assertEquals(summary.toDetailedString(), appender.list.getFirst().getFormattedMessage());
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
    }

    @Test
    public void testASummaryWithoutStatementsIsNotReported() {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("st.orm.sql.perf");
        var appender = new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        var level = logger.getLevel();
        logger.setLevel(ch.qos.logback.classic.Level.INFO);
        try {
            List<SqlLog.Summary> summaries = new ArrayList<>();
            SqlLog.record("silent", (Supplier<Object>) () -> null, summaries::add);
            SqlLog.report(summaries.getFirst());
            assertTrue(appender.list.isEmpty());
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
    }
}
