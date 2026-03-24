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
import st.orm.StormConfig;

/**
 * Controls the retention behavior of the transaction-scoped entity cache.
 *
 * <p>This setting determines how long cached entity state is retained for dirty checking purposes. It can be
 * configured via the {@code storm.entity_cache.retention} property (see {@link st.orm.StormConfig}).</p>
 *
 * @since 1.9
 */
public enum CacheRetention {

    /**
     * Observed state is retained for the duration of the transaction, improving the dirty-check hit rate at the cost
     * of higher memory usage during transactions. The JVM may still reclaim entries under memory pressure.
     *
     * <p>This is the recommended mode for most applications.</p>
     */
    DEFAULT,

    /**
     * Observed state may be cleaned up as soon as the application no longer holds a reference to the entity. This
     * reduces memory overhead but may cause dirty-check cache misses, resulting in full-row updates.
     */
    LIGHT;

    /**
     * Resolves the cache retention from the given configuration.
     *
     * @param config the configuration to read from.
     * @return the configured cache retention.
     */
    @Nonnull
    public static CacheRetention fromConfig(@Nonnull StormConfig config) {
        return CacheRetention.valueOf(
                config.getProperty(StormConfig.ENTITY_CACHE_RETENTION, "DEFAULT").trim().toUpperCase());
    }
}
