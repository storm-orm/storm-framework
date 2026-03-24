package st.orm.ktor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.StormConfig
import st.orm.StormConfig.ENTITY_CACHE_RETENTION
import st.orm.StormConfig.UPDATE_DEFAULT_MODE
import st.orm.StormConfig.UPDATE_DIRTY_CHECK

class StormConfigReaderTest {

    @Test
    fun `reads camelCase storm config from HOCON`() {
        val config = readConfigFromMap(
            "storm.update.defaultMode" to "FIELD",
            "storm.update.dirtyCheck" to "VALUE",
            "storm.entityCache.retention" to "light",
        )
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe "FIELD"
        config.getProperty(UPDATE_DIRTY_CHECK) shouldBe "VALUE"
        config.getProperty(ENTITY_CACHE_RETENTION) shouldBe "light"
    }

    @Test
    fun `reads snake_case storm config from HOCON`() {
        val config = readConfigFromMap(
            "storm.update.default_mode" to "ENTITY",
            "storm.entity_cache.retention" to "default",
        )
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe "ENTITY"
        config.getProperty(ENTITY_CACHE_RETENTION) shouldBe "default"
    }

    @Test
    fun `camelCase takes precedence over snake_case`() {
        val config = readConfigFromMap(
            "storm.update.defaultMode" to "FIELD",
            "storm.update.default_mode" to "ENTITY",
        )
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe "FIELD"
    }

    @Test
    fun `missing properties are not set`() {
        val config = readConfigFromMap()
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe null
    }

    @Test
    fun `plugin reads config from HOCON environment`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "storm.datasource.jdbcUrl" to "jdbc:h2:mem:cfgenv-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "storm.datasource.driverClassName" to "org.h2.Driver",
                "storm.datasource.username" to "sa",
                "storm.datasource.password" to "",
                "storm.update.defaultMode" to "FIELD",
            )
        }
        application {
            install(Storm)
            orm shouldNotBe null
        }
    }

    /**
     * Helper that creates a StormConfig by feeding key-value pairs through the same
     * HOCON-reading logic the plugin uses.
     */
    private fun readConfigFromMap(vararg pairs: Pair<String, String>): StormConfig {
        var result: StormConfig? = null
        kotlinx.coroutines.runBlocking {
            io.ktor.server.testing.testApplication {
                environment {
                    config = MapApplicationConfig(*pairs)
                }
                application {
                    result = st.orm.ktor.readStormConfig(this)
                }
            }
        }
        return result!!
    }
}
