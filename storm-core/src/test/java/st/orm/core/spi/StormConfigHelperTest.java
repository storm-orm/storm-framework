package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import st.orm.StormConfig;

/**
 * Tests for {@link StormConfigHelper}.
 */
public class StormConfigHelperTest {

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
        assertEquals(10, StormConfigHelper.getInt(config, "key", 10));
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
        assertEquals(Color.RED, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED));
    }

    @Test
    public void getEnumTrimsWhitespace() {
        var config = StormConfig.of(Map.of("key", "  green  "));
        assertEquals(Color.GREEN, StormConfigHelper.getEnum(config, "key", Color.class, Color.RED));
    }
}
