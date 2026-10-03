package st.orm.template;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.template.model.City;

/**
 * Verifies the Java {@link SqlLog} facade: a scope opened around a call reports its summary under the
 * {@code st.orm.sql.perf} logger when it closes, and records nothing when that logger is disabled, so a scope left
 * in production code costs nothing until the logger is raised.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@SpringBootTest
@Sql("/data.sql")
public class SqlLogTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private ORMTemplate orm;

    private ListAppender<ILoggingEvent> capture(Level level, Runnable action) {
        var logger = (Logger) LoggerFactory.getLogger("st.orm.sql.perf");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        var previous = logger.getLevel();
        logger.setLevel(level);
        try {
            action.run();
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
        return appender;
    }

    @Test
    public void testAScopeReportsItsSummaryWhenClosed() {
        var appender = capture(Level.INFO, () -> {
            try (var scope = SqlLog.open("cities")) {
                orm.entity(City.class).getById(1);
                orm.entity(City.class).getById(2);
            }
        });
        assertEquals(1, appender.list.size(), appender.list.toString());
        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.startsWith("SQL (cities):"), message);
        assertTrue(message.contains("2 statement"), message);
    }

    @Test
    public void testAScopeWithALimitStillCountsEveryStatement() {
        var appender = capture(Level.INFO, () -> {
            try (var scope = SqlLog.open("limited", 1)) {
                orm.entity(City.class).getById(1);
                orm.entity(City.class).getById(2);
                orm.entity(City.class).getById(3);
            }
        });
        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.startsWith("SQL (limited):"), message);
        assertTrue(message.contains("3 statement"), message);
    }

    @Test
    public void testAScopeRecordingCallSitesAttributesTheStatementsAtTrace() {
        // Call sites and statement texts only show in the detailed rendering, which the logger prints at TRACE.
        var appender = capture(Level.TRACE, () -> {
            try (var scope = SqlLog.open("sited", 100, true)) {
                orm.entity(City.class).getById(1);
            }
        });
        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.startsWith("SQL (sited):"), message);
        assertTrue(message.contains("SELECT c.id, c.name"), message);
        // The frame is whatever called into Storm, here JUnit's reflective invoker rather than this method, so the
        // assertion is that a frame was attributed at all: file and line, which only the stack walk can supply.
        assertTrue(message.matches("(?s).*\\w+\\.java:\\d+.*"), message);
    }

    @Test
    public void testAScopeWithoutCallSitesRecordsTheStatementsWithoutAFrame() {
        var appender = capture(Level.TRACE, () -> {
            try (var scope = SqlLog.open("unsited", 100)) {
                orm.entity(City.class).getById(1);
            }
        });
        String message = appender.list.getFirst().getFormattedMessage();
        assertTrue(message.contains("SELECT c.id, c.name"), message);
        assertFalse(message.matches("(?s).*\\w+\\.java:\\d+.*"), message);
    }

    @Test
    public void testAScopeRecordsNothingWhileTheLoggerIsDisabled() {
        var appender = capture(Level.OFF, () -> {
            try (var scope = SqlLog.open("silent")) {
                orm.entity(City.class).getById(1);
            }
            try (var scope = SqlLog.open("silent", 1)) {
                orm.entity(City.class).getById(1);
            }
            try (var scope = SqlLog.open("silent", 1, true)) {
                orm.entity(City.class).getById(1);
            }
        });
        assertTrue(appender.list.isEmpty(), appender.list.toString());
    }
}
