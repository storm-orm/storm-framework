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
import st.orm.StormConfig.IN_LIST
import st.orm.StormConfig.TEMPLATE_CACHE_SIZE
import st.orm.StormConfig.UPDATE_DEFAULT_MODE
import st.orm.StormConfig.UPDATE_DIRTY_CHECK
import st.orm.StormConfig.UPDATE_MAX_SHAPES
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
 *     inList = "auto"
 *     validation {
 *         recordMode = "fail"
 *         strict = false
 *     }
 * }
 * ```
 *
 * A named database reads the same keys relative to its own prefix (`storm.databases.<name>`) and inherits the
 * rest through [fallback], the primary database's effective configuration: a key set under the named prefix
 * overrides, every other key resolves to what the primary uses. The [overrides], the settings the plugin's own
 * configuration block states, win over every key read.
 */
internal fun readStormConfig(
    application: Application,
    prefix: String = "storm",
    fallback: StormConfig? = null,
    overrides: Map<String, String> = emptyMap(),
): StormConfig {
    val config = application.environment.config
    val properties = mutableMapOf<String, String>()
    val keys = listOf(
        UPDATE_DEFAULT_MODE,
        UPDATE_DIRTY_CHECK,
        UPDATE_MAX_SHAPES,
        ENTITY_CACHE_RETENTION,
        TEMPLATE_CACHE_SIZE,
        ANSI_ESCAPING,
        IN_LIST,
        VALIDATION_RECORD_MODE,
        VALIDATION_SCHEMA_MODE,
        VALIDATION_STRICT,
    )
    for (key in keys) {
        // Storm property keys are rooted at "storm."; secondary databases read the same keys relative to their
        // own prefix, e.g. "storm.databases.analytics.update.default_mode". The prefix itself (which may contain
        // a user-chosen database name) is looked up verbatim; only the relative key gets the camelCase variant.
        val relativeKey = key.removePrefix("storm")
        // Try camelCase (HOCON convention) first, then snake_case (Storm convention), then the fallback
        // configuration a named database inherits from.
        val value = config.propertyOrNull(prefix + snakeToCamel(relativeKey))?.getString()
            ?: config.propertyOrNull(prefix + relativeKey)?.getString()
            ?: fallback?.getProperty(key)
            ?: continue
        properties[key] = value
    }
    properties.putAll(overrides)
    return StormConfig.of(properties)
}

/**
 * Returns the configuration of the plugin's primary database: the [StormPluginConfig.config] given, or else the
 * configuration read from HOCON with the settings the plugin's configuration block states on top. A setting stated
 * beside a given [StormPluginConfig.config] is refused, since that configuration states it itself.
 */
internal fun pluginStormConfig(application: Application, pluginConfig: StormPluginConfig): StormConfig {
    val config = pluginConfig.config
    if (config != null) {
        require(pluginConfig.inList == null) { "inList is set beside config; state storm.in_list in the config instead." }
        return config
    }
    return readStormConfig(application, overrides = pluginConfig.inList?.let { mapOf(IN_LIST to it) }.orEmpty())
}

/**
 * Converts a dotted snake_case Storm property key to dotted camelCase for HOCON lookup.
 *
 * Example: `storm.entity_cache.retention` becomes `storm.entityCache.retention`.
 */
private fun snakeToCamel(key: String): String = key.split('.').joinToString(".") { segment ->
    segment.replace(Regex("_([a-z])")) { it.groupValues[1].uppercase() }
}
