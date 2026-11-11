package st.orm.ktor

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.StormConfig
import st.orm.StormConfig.ENTITY_CACHE_RETENTION
import st.orm.StormConfig.IN_LIST
import st.orm.StormConfig.UPDATE_DEFAULT_MODE
import st.orm.StormConfig.UPDATE_DIRTY_CHECK

internal class StormConfigReaderTest {

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
    fun `reads in-list config in camelCase`() {
        val config = readConfigFromMap("storm.inList" to "padded")
        config.getProperty(IN_LIST) shouldBe "padded"
    }

    @Test
    fun `reads in-list config in snake_case`() {
        val config = readConfigFromMap("storm.in_list" to "exact")
        config.getProperty(IN_LIST) shouldBe "exact"
    }

    @Test
    fun `named database reads in-list under its own prefix`() {
        val config = readNamedConfigFromMap(
            "storm.inList" to "padded",
            "storm.databases.analytics.inList" to "exact",
        )
        config.getProperty(IN_LIST) shouldBe "exact"
    }

    @Test
    fun `missing properties are not set`() {
        val config = readConfigFromMap()
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe null
    }

    @Test
    fun `named database reads keys under its own prefix`() {
        val config = readNamedConfigFromMap(
            "storm.databases.analytics.update.defaultMode" to "FIELD",
        )
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe "FIELD"
    }

    @Test
    fun `named database inherits keys its own prefix does not set`() {
        val config = readNamedConfigFromMap(
            "storm.update.defaultMode" to "FIELD",
            "storm.entityCache.retention" to "light",
            "storm.databases.analytics.entityCache.retention" to "default",
        )
        // Unset under the named prefix: inherited from the root.
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe "FIELD"
        // Set under the named prefix: overrides the root.
        config.getProperty(ENTITY_CACHE_RETENTION) shouldBe "default"
    }

    @Test
    fun `named database inherits from an explicit fallback config`() {
        val config = readNamedConfigFromMap(
            "storm.databases.analytics.entityCache.retention" to "light",
            fallback = StormConfig.of(mapOf(UPDATE_DEFAULT_MODE to "ENTITY")),
        )
        config.getProperty(UPDATE_DEFAULT_MODE) shouldBe "ENTITY"
        config.getProperty(ENTITY_CACHE_RETENTION) shouldBe "light"
    }

    @Test
    fun `plugin reads config from HOCON environment`() = testApplication {
        environment {
            config = MapApplicationConfig(
                "storm.validation.schemaMode" to "none", // not exercising schema validation here
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

    @Test
    fun `plugin in-list is read on top of the HOCON config`() {
        val config = readConfigFromMap("storm.inList" to "padded", overrides = mapOf(IN_LIST to "exact"))
        config.getProperty(IN_LIST) shouldBe "exact"
    }

    @Test
    fun `plugin in-list reaches the plugin's config`() = testApplication {
        environment {
            config = MapApplicationConfig("storm.inList" to "padded")
        }
        application {
            pluginStormConfig(this, StormPluginConfig().apply { inList = "exact" }).getProperty(IN_LIST) shouldBe "exact"
            pluginStormConfig(this, StormPluginConfig()).getProperty(IN_LIST) shouldBe "padded"
        }
    }

    @Test
    fun `plugin in-list is refused beside a config`() = testApplication {
        application {
            val given = StormConfig.of(mapOf(IN_LIST to "padded"))
            pluginStormConfig(this, StormPluginConfig().apply { config = given }).getProperty(IN_LIST) shouldBe "padded"
            shouldThrow<IllegalArgumentException> {
                pluginStormConfig(
                    this,
                    StormPluginConfig().apply {
                        config = given
                        inList = "exact"
                    },
                )
            }
        }
    }

    /**
     * Helper that creates a StormConfig by feeding key-value pairs through the same
     * HOCON-reading logic the plugin uses.
     */
    private fun readConfigFromMap(vararg pairs: Pair<String, String>, overrides: Map<String, String> = emptyMap()): StormConfig {
        var result: StormConfig? = null
        kotlinx.coroutines.runBlocking {
            io.ktor.server.testing.testApplication {
                environment {
                    config = MapApplicationConfig(*pairs)
                }
                application {
                    result = st.orm.ktor.readStormConfig(this, overrides = overrides)
                }
            }
        }
        return result!!
    }

    /**
     * Helper that reads the configuration of the named database `analytics` the way the plugin does: the keys
     * under its own prefix, inheriting the rest from the fallback, which defaults to the root configuration.
     */
    private fun readNamedConfigFromMap(vararg pairs: Pair<String, String>, fallback: StormConfig? = null): StormConfig {
        var result: StormConfig? = null
        kotlinx.coroutines.runBlocking {
            io.ktor.server.testing.testApplication {
                environment {
                    config = MapApplicationConfig(*pairs)
                }
                application {
                    val primary = fallback ?: st.orm.ktor.readStormConfig(this)
                    result = st.orm.ktor.readStormConfig(this, "storm.databases.analytics", fallback = primary)
                }
            }
        }
        return result!!
    }
}
