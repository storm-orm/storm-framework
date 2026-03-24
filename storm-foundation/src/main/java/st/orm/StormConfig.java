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

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.Map;

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

    /** Default update mode for entities without {@code @DynamicUpdate}. Values: ENTITY, FIELD, OFF. */
    public static final String UPDATE_DEFAULT_MODE = "storm.update.default_mode";
    /** Default dirty check strategy. Values: INSTANCE, VALUE. */
    public static final String UPDATE_DIRTY_CHECK = "storm.update.dirty_check";
    /** Maximum UPDATE shapes before fallback to full-row update. */
    public static final String UPDATE_MAX_SHAPES = "storm.update.max_shapes";
    /** Cache retention mode. Values: default, light. */
    public static final String ENTITY_CACHE_RETENTION = "storm.entity_cache.retention";
    /** Maximum number of compiled templates to cache. */
    public static final String TEMPLATE_CACHE_SIZE = "storm.template_cache.size";
    /** Whether to use ANSI escaping for identifiers. */
    public static final String ANSI_ESCAPING = "storm.ansi_escaping";
    /** Record validation mode. Values: fail, warn, none. */
    public static final String VALIDATION_RECORD_MODE = "storm.validation.record_mode";
    /** Schema validation mode. Values: none, warn, fail. */
    public static final String VALIDATION_SCHEMA_MODE = "storm.validation.schema_mode";
    /** Whether to treat schema validation warnings as errors. */
    public static final String VALIDATION_STRICT = "storm.validation.strict";
    /** Interpolation safety mode. Values: warn, fail, none. */
    public static final String VALIDATION_INTERPOLATION_MODE = "storm.validation.interpolation_mode";

    private static final StormConfig DEFAULTS = new StormConfig(Map.of());

    private final Map<String, String> properties;

    private StormConfig(@Nonnull Map<String, String> properties) {
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
    public String getProperty(@Nonnull String key) {
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
    @Nonnull
    public String getProperty(@Nonnull String key, @Nonnull String defaultValue) {
        String value = getProperty(key);
        return value != null ? value : defaultValue;
    }

    /**
     * Creates a new {@code StormConfig} with the given properties.
     *
     * @param properties the configuration properties; must not be {@code null}.
     * @return a new immutable configuration.
     */
    @Nonnull
    public static StormConfig of(@Nonnull Map<String, String> properties) {
        return new StormConfig(properties);
    }

    /**
     * Returns a configuration that reads exclusively from JVM system properties.
     *
     * <p>This is the default configuration used when no explicit {@code StormConfig} is provided.</p>
     *
     * @return the default configuration; never {@code null}.
     */
    @Nonnull
    public static StormConfig defaults() {
        return DEFAULTS;
    }
}
