package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Ref;
import st.orm.core.model.City;
import st.orm.core.model.PetOwnerRef;
import st.orm.core.template.ORMTemplate;

/**
 * Verifies that statements are logged where they execute: a statement served by a compiled query plan is reported
 * on every execution, a fetch is labelled as such, and parameter values appear only once the logger is turned up
 * to TRACE.
 */
@SuppressWarnings("ALL")
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class SqlStatementLoggingIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /** Collects the events the given logger emits at the given level while the action runs. */
    private static List<ILoggingEvent> capture(String loggerName, Level level, Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(loggerName);
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

    private static List<ILoggingEvent> capture(String loggerName, Level level, Supplier<?> action) {
        return capture(loggerName, level, (Runnable) action::get);
    }

    @Test
    public void testEveryExecutionIsLoggedRatherThanEveryCompilation() {
        var orm = ORMTemplate.of(dataSource);
        var cities = orm.entity(City.class);
        // Warm any compiled plan, so the statement is already built before the capture starts.
        cities.getById(1);
        var events = capture("st.orm.sql", Level.DEBUG, () -> {
            cities.getById(1);
            cities.getById(2);
        });
        assertEquals(2, events.size(), events.toString());
        assertTrue(events.getFirst().getFormattedMessage().contains("SELECT City"),
                events.getFirst().getFormattedMessage());
    }

    @Test
    public void testDebugLevelKeepsParameterValuesOutOfTheStatement() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture("st.orm.sql", Level.DEBUG, () -> orm.entity(City.class).getById(1));
        String message = events.getFirst().getFormattedMessage();
        assertTrue(message.contains("?"), message);
    }

    @Test
    public void testTraceLevelRendersValuesIntoTheStatement() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture("st.orm.sql", Level.TRACE, () -> orm.entity(City.class).getById(2));
        String message = events.getFirst().getFormattedMessage();
        // The statement is runnable as logged: no placeholder left to substitute by hand.
        assertFalse(message.contains("?"), message);
        assertTrue(message.contains("= 2"), message);
    }

    @Test
    public void testAFetchIsLabelledAsSuch() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture("st.orm.sql", Level.DEBUG, () -> {
            var pets = orm.entity(PetOwnerRef.class).select().getResultList();
            pets.stream()
                    .map(PetOwnerRef::owner)
                    .filter(Objects::nonNull)
                    .findFirst()
                    .ifPresent(Ref::fetch);
        });
        assertTrue(events.stream().anyMatch(e -> e.getFormattedMessage().contains(", fetch")),
                events.toString());
        // The select that led to the fetch is not itself a fetch.
        assertFalse(events.getFirst().getFormattedMessage().contains(", fetch"),
                events.getFirst().getFormattedMessage());
    }

    @Test
    public void testATypeLoggerScopesLoggingToThatType() {
        var orm = ORMTemplate.of(dataSource);
        var events = capture("st.orm.sql.City", Level.DEBUG, () -> {
            orm.entity(City.class).getById(1);
            orm.entity(PetOwnerRef.class).select().getResultList();
        });
        assertEquals(1, events.size(), events.toString());
        assertTrue(events.getFirst().getFormattedMessage().contains("SELECT City"),
                events.getFirst().getFormattedMessage());
    }
}
