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
import kotlin.time.Duration

/**
 * The SQL log configuration the plugin runs with, covering both logs: the performance log, which reports what a
 * call cost, and the slow statement log, which names the execution that cost too much.
 *
 * Resolved per option: an explicit [StormPluginConfig] setting wins, otherwise the HOCON configuration under
 * `storm.sqlLog` (or `storm.sql_log`) applies, otherwise the option's default. The HOCON path is what makes the
 * log switchable through `application.conf` without a code change, mirroring the Spring starter's
 * `storm.sql-log.*` properties.
 *
 * [lineWidth], [slowThreshold], [slowLimit], [adviceCategories], [entityBudgetJoins] and [entityBudgetColumns] are `null` when neither the DSL nor the configuration sets them,
 * so installation leaves the JVM-wide settings (the `storm.sql_log.*` system properties, or another
 * application's configuration in the same JVM) untouched.
 */
internal class SqlLogSettings(
    val enabled: Boolean,
    val limit: Int,
    val statementThreshold: Int?,
    val durationThreshold: Duration?,
    val slowThreshold: Duration?,
    val slowLimit: Int?,
    val adviceCategories: List<String>?,
    val entityBudgetJoins: Int?,
    val entityBudgetColumns: Int?,
    val callSites: Boolean,
    val callSiteSkip: List<String>,
    val lineWidth: Int?,
)

/**
 * Resolves the effective per-call SQL log settings from the plugin configuration and the application's HOCON
 * configuration. An invalid configuration value aborts installation with an error naming the key, so a typo
 * cannot silently misconfigure the log.
 */
internal fun resolveSqlLogSettings(application: Application, pluginConfig: StormPluginConfig): SqlLogSettings = SqlLogSettings(
    enabled = pluginConfig.sqlLogPerformance ?: booleanProperty(application, "performance.enabled") ?: false,
    limit = pluginConfig.sqlLogPerformanceLimit ?: intProperty(application, "performance.limit") ?: 200,
    statementThreshold = pluginConfig.sqlLogPerformanceStatementThreshold
        ?: intProperty(application, "performance.threshold.statements"),
    durationThreshold = pluginConfig.sqlLogPerformanceDurationThreshold
        ?: durationProperty(application, "performance.threshold.duration"),
    slowThreshold = pluginConfig.sqlLogSlowThreshold ?: durationProperty(application, "slow.threshold"),
    slowLimit = pluginConfig.sqlLogSlowLimit ?: intProperty(application, "slow.limit"),
    // Advice is a feature of its own, configured under `storm.advice` rather than `storm.sqlLog`.
    adviceCategories = pluginConfig.adviceCategories ?: listProperty(application, "categories", ADVICE),
    entityBudgetJoins = pluginConfig.adviceEntityBudgetJoins ?: intProperty(application, "entityBudget.joins", ADVICE),
    entityBudgetColumns = pluginConfig.adviceEntityBudgetColumns
        ?: intProperty(application, "entityBudget.columns", ADVICE),
    callSites = pluginConfig.sqlLogPerformanceCallSites
        ?: booleanProperty(application, "performance.callSites") ?: false,
    // Shared: both logs attribute a frame the same way, so the skip list sits above the two sections.
    callSiteSkip = pluginConfig.sqlLogCallSiteSkip
        ?: listProperty(application, "callSiteSkip")
        ?: emptyList(),
    lineWidth = pluginConfig.sqlLogPerformanceLineWidth ?: intProperty(application, "performance.lineWidth"),
)

/** The section of the SQL log settings. */
private const val SQL_LOG = "sqlLog"

/** The section of the advice settings. */
private const val ADVICE = "advice"

/**
 * Reads a property of a section, trying the camelCase key under `storm.<section>` first (HOCON convention), then the
 * snake_case key (Storm convention): `storm.sqlLog.slow.threshold`, then `storm.sql_log.slow.threshold`.
 */
private fun stringProperty(application: Application, relativeKey: String, section: String): Pair<String, String>? {
    val config = application.environment.config
    val camelKey = "storm.$section.$relativeKey"
    val snakeKey = "storm.${camelToSnake(section)}." + camelToSnake(relativeKey)
    config.propertyOrNull(camelKey)?.let { return camelKey to it.getString() }
    config.propertyOrNull(snakeKey)?.let { return snakeKey to it.getString() }
    return null
}

private fun booleanProperty(application: Application, relativeKey: String, section: String = SQL_LOG): Boolean? {
    val (key, value) = stringProperty(application, relativeKey, section) ?: return null
    return value.toBooleanStrictOrNull()
        ?: throw IllegalStateException("Invalid value '$value' for $key. Valid values are: true, false.")
}

private fun intProperty(application: Application, relativeKey: String, section: String = SQL_LOG): Int? {
    val (key, value) = stringProperty(application, relativeKey, section) ?: return null
    return value.toIntOrNull()
        ?: throw IllegalStateException("Invalid value '$value' for $key. The value must be an integer.")
}

private fun durationProperty(application: Application, relativeKey: String, section: String = SQL_LOG): Duration? {
    val (key, value) = stringProperty(application, relativeKey, section) ?: return null
    return Duration.parseOrNull(value)
        ?: throw IllegalStateException(
            "Invalid value '$value' for $key. The value must be a duration such as 500ms, 2s, or PT0.5S.",
        )
}

/**
 * Reads a list-valued property of a section: a HOCON list, or a single comma-separated string.
 */
private fun listProperty(application: Application, relativeKey: String, section: String = SQL_LOG): List<String>? {
    val config = application.environment.config
    val camelKey = "storm.$section.$relativeKey"
    val snakeKey = "storm.${camelToSnake(section)}." + camelToSnake(relativeKey)
    val property = config.propertyOrNull(camelKey) ?: config.propertyOrNull(snakeKey) ?: return null
    val values = runCatching { property.getList() }.getOrElse { property.getString().split(',') }
    return values.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * Converts a dotted camelCase key segment to snake_case: `callSiteSkip` becomes `call_site_skip`.
 */
private fun camelToSnake(key: String): String = key.split('.').joinToString(".") { segment ->
    segment.replace(Regex("([A-Z])")) { "_" + it.groupValues[1].lowercase() }
}
