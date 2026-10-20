package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import st.orm.StormConfig;

/**
 * Tests for {@link StormConfigHelper}.
 */
public class StormConfigHelperTest {

    /** Returns the warnings {@code st.orm.config} logs while the action runs, keeping them out of the build log. */
    private static List<String> warnings(Runnable action) {
        var logger = (Logger) LoggerFactory.getLogger("st.orm.config");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(logger.getLoggerContext());
        appender.start();
        logger.addAppender(appender);
        logger.setAdditive(false);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            appender.stop();
        }
        return appender.list.stream()
                .filter(event -> event.getLevel() == Level.WARN)
                .map(ILoggingEvent::getFormattedMessage)
                .toList();
    }

    @Test
    public void getIntReturnsConfiguredValue() {
        var config = StormConfig.of(Map.of("key", "42"));
        assertEquals(42, StormConfigHelper.getInt(config, "key", 10));
    }

    @Test
    public void getIntReturnsDefaultWhenMissing() {
        var config = StormConfig.of(Map.of());
        assertEquals(10, StormConfigHelper.getInt(config, "key", 10));
    }

    @Test
    public void getIntReturnsDefaultOnInvalidValue() {
        var config = StormConfig.of(Map.of("key", "not-a-number"));
        var warnings = warnings(() -> assertEquals(10, StormConfigHelper.getInt(config, "key", 10)));
        assertEquals(List.of("Invalid integer value 'not-a-number' for property 'key', using default 10."), warnings);
    }

    @Test
    public void getIntTrimsWhitespace() {
        var config = StormConfig.of(Map.of("key", "  7  "));
        assertEquals(7, StormConfigHelper.getInt(config, "key", 10));
    }

    @Test
    public void getBooleanReturnsTrueWhenConfigured() {
        var config = StormConfig.of(Map.of("key", "true"));
        assertTrue(StormConfigHelper.getBoolean(config, "key", false));
    }

    @Test
    public void getBooleanReturnsFalseForNonBooleanValue() {
        var config = StormConfig.of(Map.of("key", "yes"));
        assertFalse(StormConfigHelper.getBoolean(config, "key", true));
    }

    @Test
    public void getBooleanReturnsDefaultWhenMissing() {
        var config = StormConfig.of(Map.of());
        assertTrue(StormConfigHelper.getBoolean(config, "key", true));
    }

    @Test
    public void getBooleanTrimsWhitespace() {
        var config = StormConfig.of(Map.of("key", " true "));
        assertTrue(StormConfigHelper.getBoolean(config, "key", false));
    }

    enum Color { RED, GREEN, BLUE }

    @Test
    public void getEnumReturnsConfiguredValue() {
        var config = StormConfig.of(Map.of("key", "GREEN"));
        assertEquals(Color.GREEN, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED));
    }

    @Test
    public void getEnumIsCaseInsensitive() {
        var config = StormConfig.of(Map.of("key", "blue"));
        assertEquals(Color.BLUE, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED));
    }

    @Test
    public void getEnumReturnsDefaultWhenMissing() {
        var config = StormConfig.of(Map.of());
        assertEquals(Color.RED, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED));
    }

    @Test
    public void getEnumReturnsDefaultOnInvalidValue() {
        var config = StormConfig.of(Map.of("key", "YELLOW"));
        var warnings = warnings(() -> assertEquals(Color.RED, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED)));
        assertEquals(List.of("Invalid value 'YELLOW' for property 'key', using default RED."), warnings);
    }

    @Test
    public void getEnumTrimsWhitespace() {
        var config = StormConfig.of(Map.of("key", "  green  "));
        assertEquals(Color.GREEN, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED));
    }

    @Test
    public void getDurationReadsAUnitSuffixABareNumberOrIso() {
        assertEquals(Duration.ofMillis(200), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "200ms")), "key", null));
        assertEquals(Duration.ofMillis(200), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "200")), "key", null));
        assertEquals(Duration.ofSeconds(2), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "2s")), "key", null));
        assertEquals(Duration.ofMillis(1500), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "1.5s")), "key", null));
        assertEquals(Duration.ofMinutes(1), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "1m")), "key", null));
        assertEquals(Duration.ofMillis(200), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "PT0.2S")), "key", null));
        assertEquals(Duration.ofMillis(200), StormConfigHelper.getDuration(StormConfig.of(Map.of("key", " 200 ms ")), "key", null));
    }

    @Test
    public void getDurationReturnsDefaultWhenMissingOrInvalid() {
        assertNull(StormConfigHelper.getDuration(StormConfig.of(Map.of()), "key", null));
        var warnings = warnings(() -> assertEquals(Duration.ofSeconds(1),
                StormConfigHelper.getDuration(StormConfig.of(Map.of("key", "soon")), "key", Duration.ofSeconds(1))));
        assertEquals(List.of("Invalid duration value 'soon' for property 'key', using default PT1S."), warnings);
    }
}
