package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.core.model.City;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.SlowStatementLog;
import st.orm.core.template.impl.SlowStatementLogTestSupport;

/**
 * Verifies that an execution whose database time exceeds the threshold is reported on its own, wherever it runs:
 * with the statement, its call site, and what there is to analyze it by; nothing below the threshold, values at
 * TRACE only.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SlowStatementLogIntegrationTest {

    private static final String LOGGER = "st.orm.sql.slow";

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    public void everyExecutionIsSlow() {
        SlowStatementLogTestSupport.reset();
        SlowStatementLog.threshold(Duration.ofNanos(1));
    }

    @AfterEach
    public void noThreshold() {
        SlowStatementLog.threshold(null);
        SlowStatementLogTestSupport.reset();
    }

    /** Collects the events the given logger emits at the given level while the action runs. */
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

    private static List<ILoggingEvent> capture(Level level, Supplier<?> action) {
        return capture(level, (Runnable) action::get);
    }

    @Test
    public void testASlowExecutionIsReportedWithItsStatementAndCallSite() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture(Level.WARN, () -> orm.entity(City.class).getById(1));
        assertEquals(1, events.size(), events.toString());
        var event = events.getFirst();
        assertEquals(Level.WARN, event.getLevel());
        String message = event.getFormattedMessage();
        assertTrue(message.startsWith("SQL slow (SELECT City): "), message);
        assertTrue(message.contains(" ms in database, 1 rows"), message);
        // The call site is walked for the slow execution, on the executing thread; this test lives under st.orm,
        // which the walker treats as framework, so the frame it finds is the test runner's.
        assertTrue(Pattern.compile(", [A-Za-z0-9_$.]+:\\d+" + System.lineSeparator()).matcher(message).find(), message);
        assertTrue(message.contains("\tSELECT c.id, c.name"), message);
        assertTrue(message.contains("WHERE c.id = ?"), message);
        assertTrue(message.contains("\tshape "), message);
        assertTrue(message.contains("parameters 1"), message);
    }

    @Test
    public void testNothingIsReportedBelowTheThreshold() {
        var orm = ORMTemplate.of(dataSource);
        SlowStatementLog.threshold(Duration.ofHours(1));
        var events = capture(Level.WARN, () -> orm.entity(City.class).getById(1));
        assertTrue(events.isEmpty(), events.toString());
    }

    @Test
    public void testWithoutAThresholdTheLogIsInactive() {
        SlowStatementLog.threshold(null);
        assertFalse(SlowStatementLog.active());
        SlowStatementLog.threshold(Duration.ZERO);
        assertFalse(SlowStatementLog.active());
        SlowStatementLog.threshold(Duration.ofMillis(200));
        assertTrue(SlowStatementLog.active());
    }

    @Test
    public void testTraceRendersTheValuesIntoTheStatement() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture(Level.TRACE, () -> orm.entity(City.class).getById(2));
        String message = events.getFirst().getFormattedMessage();
        // The values are the detail; the line stays a warning.
        assertEquals(Level.WARN, events.getFirst().getLevel());
        assertFalse(message.contains("?"), message);
        assertTrue(message.contains("= 2"), message);
    }

    @Test
    public void testWarnKeepsTheValuesOut() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture(Level.WARN, () -> orm.entity(City.class).getById(2));
        String message = events.getFirst().getFormattedMessage();
        assertTrue(message.contains("= ?"), message);
        assertFalse(message.contains("= 2"), message);
    }

    @Test
    public void testAStreamedReadReportsDatabaseTimeApartFromConsumption() {
        var orm = ORMTemplate.of(dataSource);
        // The claim is that the two figures are kept apart, not that the database is fast; a first execution on a
        // cold JVM costs enough to blur that, so the read is warmed before the one that is measured.
        try (var warmUp = orm.entity(City.class).select().getResultStream()) {
            warmUp.count();
        }
        var events = capture(Level.WARN, () -> {
            try (var stream = orm.entity(City.class).select().getResultStream()) {
                Thread.sleep(50);
                return stream.count();
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        });
        String message = events.getFirst().getFormattedMessage();
        var matcher = Pattern.compile("(\\d+(?:\\.\\d+)?) ms in database, \\d+ rows read over (\\d+) ms")
                .matcher(message);
        assertTrue(matcher.find(), message);
        assertTrue(Double.parseDouble(matcher.group(1)) < 50, message);
        assertTrue(Long.parseLong(matcher.group(2)) >= 50, message);
    }

    @Test
    public void testAShapeReportsTheConfiguredLinesPerMinute() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture(Level.WARN, () -> {
            for (int i = 0; i < 8; i++) {
                orm.entity(City.class).getById(1);
            }
        });
        assertEquals(SlowStatementLog.DEFAULT_LIMIT, events.size(), events.toString());
        SlowStatementLogTestSupport.reset();
        SlowStatementLog.limit(2);
        try {
            var limited = capture(Level.WARN, () -> {
                for (int i = 0; i < 8; i++) {
                    orm.entity(City.class).getById(1);
                }
            });
            assertEquals(2, limited.size(), limited.toString());
        } finally {
            SlowStatementLog.limit(SlowStatementLog.DEFAULT_LIMIT);
        }
    }

    @Test
    public void testAnUntrackedExecutionIsStillRateLimited() {
        var orm = ORMTemplate.of(dataSource);
        // With no room to track a shape, every execution takes the path a statement without a shape of its own
        // takes: no stats, and so no baseline. The limit must still hold, or the log floods with exactly the
        // statements it knows the least about.
        int tracked = SlowStatementLogTestSupport.maxShapes();
        SlowStatementLogTestSupport.maxShapes(0);
        try {
            var events = capture(Level.WARN, () -> {
                for (int i = 0; i < 8; i++) {
                    orm.entity(City.class).getById(1);
                }
            });
            assertEquals(SlowStatementLog.DEFAULT_LIMIT, events.size(), events.toString());
            String message = events.getFirst().getFormattedMessage();
            assertFalse(message.contains("typically"), message);
        } finally {
            SlowStatementLogTestSupport.maxShapes(tracked);
        }
    }

    @Test
    public void testAFailedExecutionIsReportedWithItsFailure() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture(Level.WARN, () -> {
            try {
                orm.query("SELECT * FROM no_such_table").getResultList();
            } catch (RuntimeException expected) {
                // The failure itself is the caller's to handle; its cost is still the log's to report.
            }
        });
        assertEquals(1, events.size(), events.toString());
        String message = events.getFirst().getFormattedMessage();
        // The time to the failure is database time; the failure is named by class, whose message may quote values.
        assertTrue(message.matches("(?s)SQL slow \\(SELECT\\): [\\d.]+ ms in database, failed \\(\\w+\\), .*"), message);
        assertFalse(message.contains("rows"), message);
    }
}
