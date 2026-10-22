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
package st.orm;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, untyped configuration for the Storm ORM framework.
 *
 * <p>A {@code StormConfig} holds an immutable set of {@code String} key-value properties. Property keys use the same
 * names as the corresponding JVM system properties (e.g. {@code storm.update.default_mode}). When a requested key is
 * not present in the property map, the lookup falls back to {@link System#getProperty(String)}, so existing JVM flag
 * users are unaffected.</p>
 *
 * <h2>Usage</h2>
 *
 * <p>Programmatic configuration:</p>
 * <pre>{@code
 * StormConfig config = StormConfig.of(Map.of(
 *     StormConfig.UPDATE_DEFAULT_MODE, "FIELD",
 *     StormConfig.UPDATE_MAX_SHAPES, "10"
 * ));
 * ORMTemplate orm = ORMTemplate.of(dataSource, config);
 * }</pre>
 *
 * <p>When no configuration is provided, {@code ORMTemplate.of(dataSource)} uses {@link #defaults()}, which reads
 * exclusively from system properties.</p>
 *
 * @since 1.9
 */
public final class StormConfig {

    /**
     * Marks a property key whose value affects the SQL generated for a template. Caches of generated SQL must
     * segment by the values of the marked keys; {@link #sqlShapingKeys()} exposes them, derived from these
     * declarations, so a key declared as SQL-shaping participates in cache segmentation without further registration.
     */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.FIELD)
    private @interface SqlShaping {}

    /** Default update mode for entities without {@code @DynamicUpdate}. Values: ENTITY, FIELD, OFF. */
    public static final String UPDATE_DEFAULT_MODE = "storm.update.default_mode";
    /** Default dirty check strategy. Values: INSTANCE, VALUE. */
    public static final String UPDATE_DIRTY_CHECK = "storm.update.dirty_check";
    /** Maximum UPDATE shapes before fallback to full-row update. */
    public static final String UPDATE_MAX_SHAPES = "storm.update.max_shapes";
    /** Cache retention mode. Values: default, light. */
    public static final String ENTITY_CACHE_RETENTION = "storm.entity_cache.retention";
    /**
     * How an {@code IN} list renders. Values: {@code auto}, one array parameter where the dialect binds one, so the
     * statement does not change with the length of the list, and one placeholder per value elsewhere;
     * {@code padded}, one placeholder per value on every dialect, padded to the next power of two by repeating the
     * last value, for a database whose plan cache fills with the lengths; {@code exact}, one placeholder per value
     * everywhere. Defaults to {@code auto}.
     *
     * @since 1.16
     */
    @SqlShaping
    public static final String IN_LIST = "storm.in_list";
    /** Maximum number of compiled templates to cache. */
    public static final String TEMPLATE_CACHE_SIZE = "storm.template_cache.size";
    /** Whether to use ANSI escaping for identifiers. */
    @SqlShaping
    public static final String ANSI_ESCAPING = "storm.ansi_escaping";
    /**
     * Shortest search term, in characters, that the database's substring index serves. {@code search} matches a
     * shorter term only against the rows its longer terms found, and {@code suggest} ignores it. Defaults to the
     * dialect's value: 3 on PostgreSQL, whose trigram index needs three characters, and 0 elsewhere, which treats
     * every term alike.
     */
    @SqlShaping
    public static final String SEARCH_MIN_TERM_LENGTH = "storm.search.min_term_length";
    /** Record validation mode. Values: fail, warn, none. */
    public static final String VALIDATION_RECORD_MODE = "storm.validation.record_mode";
    /** Schema validation mode. Values: none, load, warn, fail; load loads the schema without validating it. */
    public static final String VALIDATION_SCHEMA_MODE = "storm.validation.schema_mode";
    /** Whether to treat schema validation warnings as errors. */
    public static final String VALIDATION_STRICT = "storm.validation.strict";
    /** Interpolation safety mode. Values: warn, fail, none. */
    public static final String VALIDATION_INTERPOLATION_MODE = "storm.validation.interpolation_mode";
    /** Display width performance log summary rows aim for, such as 120 for narrow viewers or 240 for wide ones; at least 80. */
    public static final String SQL_LOG_LINE_WIDTH = "storm.sql_log.performance.line_width";
    /** Comma-separated package prefixes or source file names skipped in SQL log call-site attribution. */
    public static final String SQL_LOG_CALL_SITE_SKIP = "storm.sql_log.call_site_skip";
    /**
     * Database time above which a single statement execution is reported under the {@code st.orm.sql.slow}
     * logger, such as {@code 200ms} or {@code 2s}; a bare number is milliseconds. Unset means no slow log.
     */
    public static final String SQL_LOG_SLOW_THRESHOLD = "storm.sql_log.slow.threshold";
    /**
     * Slow statement lines reported per shape per minute before the rest are suppressed and counted; zero for no
     * limit. Defaults to 5.
     */
    public static final String SQL_LOG_SLOW_LIMIT = "storm.sql_log.slow.limit";
    /**
     * Time a transaction may hold its connection beyond what its statements spent on it, such as {@code 2s}; a
     * transaction idle for longer is reported under the {@code st.orm.sql.slow} logger, with the call site that
     * began it. Defaults to {@code 1s}; {@code 0} stops watching transactions.
     *
     * @since 1.16
     */
    public static final String SQL_LOG_SLOW_TRANSACTION = "storm.sql_log.slow.transaction";
    /**
     * Comma-separated categories of advice the {@code st.orm.sql.advice} log reports: {@code correctness},
     * {@code performance} and {@code design}. Defaults to {@code correctness}; the Spring Boot and Ktor
     * integrations add design and performance in development, as does the test extension.
     *
     * @since 1.16
     */
    public static final String ADVICE_CATEGORIES = "storm.advice.categories";

    /**
     * The most tables the design budget lets an entity's read join, which design advice checks entities against.
     * Defaults to {@code 10}; at least {@code 1}.
     *
     * @since 1.16
     */
    public static final String ADVICE_ENTITY_BUDGET_JOINS = "storm.advice.entity_budget.joins";

    /**
     * The most columns the design budget lets an entity's read select beyond its own, which design advice checks
     * entities against. Defaults to {@code 96}; at least {@code 1}.
     *
     * @since 1.16
     */
    public static final String ADVICE_ENTITY_BUDGET_COLUMNS = "storm.advice.entity_budget.columns";

    private static final Set<String> SQL_SHAPING_KEYS = sqlShapingKeysFromDeclarations();

    private static final StormConfig DEFAULTS = new StormConfig(Map.of());

    private final Map<String, String> properties;

    private StormConfig(Map<String, String> properties) {
        this.properties = Map.copyOf(properties);
    }

    /**
     * Returns the value of the property with the given key.
     *
     * <p>If the key is present in the property map, its value is returned. Otherwise, the value of the corresponding
     * JVM system property is returned. If neither is set, {@code null} is returned.</p>
     *
     * @param key the property key.
     * @return the property value, or {@code null} if not set.
     */
    @Nullable
    public String getProperty(String key) {
        String value = properties.get(key);
        return value != null ? value : System.getProperty(key);
    }

    /**
     * Returns the value of the property with the given key, falling back to the specified default.
     *
     * @param key the property key.
     * @param defaultValue the default value to return if the property is not set.
     * @return the property value, or {@code defaultValue} if not set.
     */
    public String getProperty(String key, String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Creates a new {@code StormConfig} with the given properties.
     *
     * @param properties the configuration properties; must not be {@code null}.
     * @return a new immutable configuration.
     */
    public static StormConfig of(Map<String, String> properties) {
        return new StormConfig(properties);
    }

    /**
     * Returns a configuration that reads exclusively from JVM system properties.
     *
     * <p>This is the default configuration used when no explicit {@code StormConfig} is provided.</p>
     *
     * @return the default configuration; never {@code null}.
     */
    public static StormConfig defaults() {
        return DEFAULTS;
    }

    /**
     * Returns the keys of the properties that affect the shape of generated SQL.
     *
     * <p>Two configurations that differ in any of these keys can generate different SQL for the same template, so
     * caches of generated SQL must include the values of these keys in their cache keys. The set is derived from the
     * key declarations in this class.</p>
     *
     * @return the SQL-shaping property keys; never {@code null}.
     * @since 1.14
     */
    public static Set<String> sqlShapingKeys() {
        return SQL_SHAPING_KEYS;
    }

    private static Set<String> sqlShapingKeysFromDeclarations() {
        var keys = new HashSet<String>();
        for (Field field : StormConfig.class.getDeclaredFields()) {
            if (field.isAnnotationPresent(SqlShaping.class)) {
                try {
                    keys.add((String) field.get(null));
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException("Failed to read SQL-shaping key %s.".formatted(field.getName()), e);
                }
            }
        }
        return Set.copyOf(keys);
    }
}
