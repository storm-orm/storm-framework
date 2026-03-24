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
package st.orm.core.spi;

import jakarta.annotation.Nonnull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.StormConfig;

/**
 * Internal helper for reading typed values from {@link StormConfig} with safe parsing.
 *
 * <p>Invalid values are logged as warnings and fall back to the provided default, rather than throwing exceptions.
 * This prevents a configuration typo from crashing the application at startup.</p>
 */
public final class StormConfigHelper {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.config");

    private StormConfigHelper() {
    }

    /**
     * Returns the integer value of the property, or the default if missing or unparseable.
     */
    public static int getInt(@Nonnull StormConfig config, @Nonnull String key, int defaultValue) {
        String value = config.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            LOGGER.warn("Invalid integer value '{}' for property '{}', using default {}.", value, key, defaultValue);
            return defaultValue;
        }
    }

    /**
     * Returns the boolean value of the property, or the default if missing.
     */
    public static boolean getBoolean(@Nonnull StormConfig config, @Nonnull String key, boolean defaultValue) {
        String value = config.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        return Boolean.parseBoolean(value.trim());
    }

    /**
     * Returns the enum value of the property, or the default if missing or unrecognized.
     */
    @Nonnull
    public static <E extends Enum<E>> E getEnum(
            @Nonnull StormConfig config,
            @Nonnull String key,
            @Nonnull Class<E> enumType,
            @Nonnull E defaultValue) {
        String value = config.getProperty(key);
        if (value == null) {
            return defaultValue;
        }
        try {
            return Enum.valueOf(enumType, value.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            LOGGER.warn("Invalid value '{}' for property '{}', using default {}.", value, key, defaultValue);
            return defaultValue;
        }
    }
}
