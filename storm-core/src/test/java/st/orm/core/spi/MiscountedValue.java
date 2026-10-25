package st.orm.core.spi;

/**
 * A field type whose test converter returns more values than it declares columns.
 */
public record MiscountedValue(String text) {}
