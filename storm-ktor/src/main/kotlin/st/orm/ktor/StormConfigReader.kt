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
package st.orm.ktor

import io.ktor.server.application.Application
import st.orm.StormConfig

/**
 * Reads Storm configuration properties from the application's HOCON configuration and converts them to a
 * [StormConfig].
 *
 * Properties are read from the `storm` section of the HOCON config. Both camelCase (HOCON convention) and
 * snake_case (Storm convention) keys are accepted. The `storm.datasource` sub-tree is handled separately by
 * [createDataSourceFromConfig].
 *
 * Expected configuration in `application.conf`:
 * ```
 * storm {
 *     update {
 *         defaultMode = "ENTITY"
 *         dirtyCheck = "INSTANCE"
 *         maxShapes = 5
 *     }
 *     entityCache {
 *         retention = "default"
 *     }
 *     templateCache {
 *         size = 256
 *     }
 *     ansiEscaping = false
 *     validation {
 *         recordMode = "fail"
 *         strict = false
 *     }
 * }
 * ```
 */
internal fun readStormConfig(application: Application): StormConfig {
    val config = application.environment.config
    val properties = mutableMapOf<String, String>()

    fun read(stormKey: String, vararg hoconKeys: String) {
        for (hoconKey in hoconKeys) {
            config.propertyOrNull(hoconKey)?.getString()?.let {
                properties[stormKey] = it
                return
            }
        }
    }

    read(StormConfig.UPDATE_DEFAULT_MODE, "storm.update.defaultMode", "storm.update.default_mode")
    read(StormConfig.UPDATE_DIRTY_CHECK, "storm.update.dirtyCheck", "storm.update.dirty_check")
    read(StormConfig.UPDATE_MAX_SHAPES, "storm.update.maxShapes", "storm.update.max_shapes")
    read(StormConfig.ENTITY_CACHE_RETENTION, "storm.entityCache.retention", "storm.entity_cache.retention")
    read(StormConfig.TEMPLATE_CACHE_SIZE, "storm.templateCache.size", "storm.template_cache.size")
    read(StormConfig.ANSI_ESCAPING, "storm.ansiEscaping", "storm.ansi_escaping")
    read(StormConfig.VALIDATION_RECORD_MODE, "storm.validation.recordMode", "storm.validation.record_mode")
    read(StormConfig.VALIDATION_SCHEMA_MODE, "storm.validation.schemaMode", "storm.validation.schema_mode")
    read(StormConfig.VALIDATION_STRICT, "storm.validation.strict")
    read(StormConfig.VALIDATION_INTERPOLATION_MODE, "storm.validation.interpolationMode", "storm.validation.interpolation_mode")

    return StormConfig.of(properties)
}
