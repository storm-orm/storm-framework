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
import st.orm.StormConfig.ANSI_ESCAPING
import st.orm.StormConfig.ENTITY_CACHE_RETENTION
import st.orm.StormConfig.TEMPLATE_CACHE_SIZE
import st.orm.StormConfig.UPDATE_DEFAULT_MODE
import st.orm.StormConfig.UPDATE_DIRTY_CHECK
import st.orm.StormConfig.UPDATE_MAX_SHAPES
import st.orm.StormConfig.VALIDATION_INTERPOLATION_MODE
import st.orm.StormConfig.VALIDATION_RECORD_MODE
import st.orm.StormConfig.VALIDATION_SCHEMA_MODE
import st.orm.StormConfig.VALIDATION_STRICT

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
    val keys = listOf(
        UPDATE_DEFAULT_MODE,
        UPDATE_DIRTY_CHECK,
        UPDATE_MAX_SHAPES,
        ENTITY_CACHE_RETENTION,
        TEMPLATE_CACHE_SIZE,
        ANSI_ESCAPING,
        VALIDATION_RECORD_MODE,
        VALIDATION_SCHEMA_MODE,
        VALIDATION_STRICT,
        VALIDATION_INTERPOLATION_MODE,
    )
    for (key in keys) {
        // Try camelCase (HOCON convention) first, then snake_case (Storm convention).
        val hoconKey = snakeToCamel(key)
        val value = config.propertyOrNull(hoconKey)?.getString()
            ?: config.propertyOrNull(key)?.getString()
            ?: continue
        properties[key] = value
    }
    return StormConfig.of(properties)
}

/**
 * Converts a dotted snake_case Storm property key to dotted camelCase for HOCON lookup.
 *
 * Example: `storm.entity_cache.retention` becomes `storm.entityCache.retention`.
 */
private fun snakeToCamel(key: String): String = key.split('.').joinToString(".") { segment ->
    segment.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }
}
