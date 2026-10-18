package st.orm.ktor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the per-option resolution of the SQL log settings: an explicit plugin setting wins, otherwise the
 * HOCON configuration under `storm.sqlLog` (or `storm.sql_log`) applies, otherwise the option's default.
 */
internal class SqlLogConfigTest {

    @Test
    fun `defaults apply when neither the plugin nor the configuration sets an option`() {
        val settings = resolve()
        settings.enabled shouldBe false
        settings.limit shouldBe 200
        settings.statementThreshold shouldBe null
        settings.durationThreshold shouldBe null
        settings.slowThreshold shouldBe null
        settings.slowLimit shouldBe null
        settings.adviceCategories shouldBe null
        settings.entityBudgetJoins shouldBe null
        settings.entityBudgetColumns shouldBe null
        settings.callSites shouldBe false
        settings.callSiteSkip shouldBe emptyList()
        settings.lineWidth shouldBe null
    }

    @Test
    fun `every option reads from camelCase configuration keys`() {
        val settings = resolve(
            "storm.sqlLog.performance.enabled" to "true",
            "storm.sqlLog.performance.limit" to "50",
            "storm.sqlLog.performance.threshold.statements" to "10",
            "storm.sqlLog.performance.threshold.duration" to "250ms",
            "storm.sqlLog.slow.threshold" to "200ms",
            "storm.sqlLog.slow.limit" to "3",
            "storm.advice.categories" to "correctness, performance",
            "storm.advice.entityBudget.joins" to "14",
            "storm.advice.entityBudget.columns" to "150",
            "storm.sqlLog.performance.callSites" to "true",
            "storm.sqlLog.callSiteSkip" to "com.myapp.data, com.myapp.plumbing",
            "storm.sqlLog.performance.lineWidth" to "120",
        )
        settings.enabled shouldBe true
        settings.limit shouldBe 50
        settings.statementThreshold shouldBe 10
        settings.durationThreshold shouldBe 250.milliseconds
        settings.slowThreshold shouldBe 200.milliseconds
        settings.slowLimit shouldBe 3
        settings.adviceCategories shouldBe listOf("correctness", "performance")
        settings.entityBudgetJoins shouldBe 14
        settings.entityBudgetColumns shouldBe 150
        settings.callSites shouldBe true
        settings.callSiteSkip shouldBe listOf("com.myapp.data", "com.myapp.plumbing")
        settings.lineWidth shouldBe 120
    }

    @Test
    fun `every option reads from snake_case configuration keys`() {
        val settings = resolve(
            "storm.sql_log.performance.enabled" to "true",
            "storm.sql_log.performance.limit" to "25",
            "storm.sql_log.performance.threshold.statements" to "5",
            "storm.sql_log.performance.threshold.duration" to "2s",
            "storm.sql_log.slow.threshold" to "1s",
            "storm.sql_log.slow.limit" to "0",
            "storm.advice.categories" to "performance",
            "storm.advice.entity_budget.joins" to "8",
            "storm.advice.entity_budget.columns" to "64",
            "storm.sql_log.performance.call_sites" to "true",
            "storm.sql_log.call_site_skip" to "com.myapp.data",
            "storm.sql_log.performance.line_width" to "240",
        )
        settings.enabled shouldBe true
        settings.limit shouldBe 25
        settings.statementThreshold shouldBe 5
        settings.durationThreshold shouldBe 2.seconds
        settings.slowThreshold shouldBe 1.seconds
        settings.slowLimit shouldBe 0
        settings.adviceCategories shouldBe listOf("performance")
        settings.entityBudgetJoins shouldBe 8
        settings.entityBudgetColumns shouldBe 64
        settings.callSites shouldBe true
        settings.callSiteSkip shouldBe listOf("com.myapp.data")
        settings.lineWidth shouldBe 240
    }

    @Test
    fun `callSiteSkip reads a configuration list`() {
        val applicationConfig = MapApplicationConfig()
        applicationConfig.put("storm.sqlLog.callSiteSkip", listOf("com.myapp.data", "com.myapp.web"))
        val settings = resolve(applicationConfig)
        settings.callSiteSkip shouldBe listOf("com.myapp.data", "com.myapp.web")
    }

    @Test
    fun `a plugin setting overrides the configuration file`() {
        val pluginConfig = StormPluginConfig().apply {
            sqlLogPerformance = false
            sqlLogPerformanceLimit = 5
        }
        val settings = resolve(
            "storm.sqlLog.performance.enabled" to "true",
            "storm.sqlLog.performance.limit" to "99",
            pluginConfig = pluginConfig,
        )
        settings.enabled shouldBe false
        settings.limit shouldBe 5
    }

    @Test
    fun `camelCase takes precedence over snake_case`() {
        val settings = resolve(
            "storm.sqlLog.performance.limit" to "10",
            "storm.sql_log.performance.limit" to "20",
        )
        settings.limit shouldBe 10
    }

    @Test
    fun `an invalid boolean aborts installation naming the key`() {
        val exception = shouldThrow<IllegalStateException> {
            resolve("storm.sqlLog.performance.enabled" to "yes")
        }
        exception.message!! shouldContain "storm.sqlLog.performance.enabled"
        exception.message!! shouldContain "'yes'"
    }

    @Test
    fun `an invalid integer aborts installation naming the key`() {
        val exception = shouldThrow<IllegalStateException> {
            resolve("storm.sql_log.performance.limit" to "many")
        }
        exception.message!! shouldContain "storm.sql_log.performance.limit"
    }

    @Test
    fun `an invalid duration aborts installation naming the key`() {
        val exception = shouldThrow<IllegalStateException> {
            resolve("storm.sqlLog.performance.threshold.duration" to "fast")
        }
        exception.message!! shouldContain "storm.sqlLog.performance.threshold.duration"
    }

    private fun resolve(
        vararg pairs: Pair<String, String>,
        pluginConfig: StormPluginConfig = StormPluginConfig(),
    ): SqlLogSettings = resolve(MapApplicationConfig(*pairs), pluginConfig)

    private fun resolve(
        applicationConfig: MapApplicationConfig,
        pluginConfig: StormPluginConfig = StormPluginConfig(),
    ): SqlLogSettings {
        var result: SqlLogSettings? = null
        var failure: Throwable? = null
        runBlocking {
            testApplication {
                environment {
                    config = applicationConfig
                }
                application {
                    // Resolution failures are rethrown outside the application block: testApplication wraps
                    // an exception thrown during a module into its own startup failure.
                    try {
                        result = resolveSqlLogSettings(this, pluginConfig)
                    } catch (e: Throwable) {
                        failure = e
                    }
                }
            }
        }
        failure?.let { throw it }
        return result!!
    }
}
