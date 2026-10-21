package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.core.template.impl.AdviceLog
import st.orm.ktor.model.PetType
import st.orm.template.ORMTemplate
import javax.sql.DataSource

internal class StormPluginTest {

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:storm-plugin-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }
        return HikariDataSource(config)
    }

    private fun initializeSchema(dataSource: HikariDataSource) {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val sql = this::class.java.getResourceAsStream("/schema.sql")!!.bufferedReader().readText()
                for (line in sql.split(";")) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        statement.execute(trimmed)
                    }
                }
            }
        }
    }

    @Test
    fun `install Storm plugin with explicit DataSource`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            val ormTemplate = ORMTemplate.of(dataSource)
            ormTemplate shouldNotBe null
            val petTypes = ormTemplate.entity(PetType::class)
            petTypes.findAll().size shouldBe 2
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `storm extension property throws when plugin not installed`() = testApplication {
        application {
            try {
                orm
                throw AssertionError("Expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                expected.message shouldBe "Storm plugin is not installed. Call install(Storm) in your application module."
            }
        }
    }

    @Test
    fun `storm extension returns configured template`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    orm shouldNotBe null
                    stormDataSource shouldBe dataSource
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `development mode reports every category of advice`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            // A test application runs in development mode.
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    developmentMode shouldBe true
                    AdviceLog.categories() shouldBe listOf("correctness", "performance", "design")
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `RoutingContext orm extension works in routes`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    routing {
                        get("/count") {
                            val count = orm.entity(PetType::class).findAll().size
                            call.respondText(count.toString())
                        }
                    }
                }
                val response = client.get("/count")
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `ApplicationCall orm extension works in routes`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    routing {
                        get("/call-orm") {
                            val count = call.orm.entity(PetType::class).findAll().size
                            call.respondText(count.toString())
                        }
                    }
                }
                val response = client.get("/call-orm")
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `schema validation warn mode does not throw`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        schemaValidation = "warn"
                    }
                    orm shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `schema load mode loads without validating`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        schemaValidation = "load"
                    }
                    orm shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `schema validation fail mode does not throw on valid schema`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        schemaValidation = "fail"
                    }
                    orm shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `migration hook runs before schema validation`() {
        // Empty database: default fail-mode schema validation would abort startup,
        // but the migration hook creates the schema first.
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        migration { initializeSchema(dataSource) }
                    }
                    orm shouldNotBe null
                    repository<st.orm.ktor.model.PetRepository>().findAll().size shouldBe 3
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `schema validation fails startup by default on missing schema`() {
        // No migration hook and no schema: the fail default must abort startup.
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    try {
                        install(Storm) {
                            this.dataSource = dataSource
                        }
                        throw AssertionError("Expected schema validation to fail startup")
                    } catch (expected: st.orm.PersistenceException) {
                        // Validation ran in fail mode by default.
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `schema validation mode is read from application config`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                environment {
                    config = MapApplicationConfig("storm.validation.schemaMode" to "fail")
                }
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    orm shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `schema validation from config fails startup on missing schema`() {
        // No schema initialized: "fail" from the application config must
        // surface as a startup failure, proving the config fallback triggers.
        val dataSource = createTestDataSource()
        try {
            testApplication {
                environment {
                    config = MapApplicationConfig("storm.validation.schemaMode" to "fail")
                }
                application {
                    try {
                        install(Storm) {
                            this.dataSource = dataSource
                        }
                        throw AssertionError("Expected schema validation to fail startup")
                    } catch (expected: st.orm.PersistenceException) {
                        // Schema validation ran in fail mode, as configured.
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `plugin option overrides schema validation mode from config`() {
        // No schema initialized: "fail" from the config would throw, but the
        // explicit plugin option takes precedence and disables validation.
        val dataSource = createTestDataSource()
        try {
            testApplication {
                environment {
                    config = MapApplicationConfig("storm.validation.schemaMode" to "fail")
                }
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        schemaValidation = "none"
                    }
                    orm shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `unknown schema validation mode fails startup`() {
        // A valid schema proves the failure is the unrecognized mode, not a validation mismatch.
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    try {
                        install(Storm) {
                            this.dataSource = dataSource
                            schemaValidation = "invalid"
                        }
                        throw AssertionError("Expected unknown schema validation mode to fail startup")
                    } catch (expected: IllegalStateException) {
                        expected.message shouldBe "Invalid schema validation mode 'invalid' for primary database " +
                            "(schemaValidation option or storm.validation.schemaMode). " +
                            "Valid values are: none, load, warn, fail."
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `blank schema validation mode falls back to the fail default`() {
        // No schema initialized: blank means unset, so the fail default must abort startup instead of
        // skipping validation.
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    try {
                        install(Storm) {
                            this.dataSource = dataSource
                            schemaValidation = "  "
                        }
                        throw AssertionError("Expected schema validation to fail startup")
                    } catch (expected: st.orm.PersistenceException) {
                        // Blank resolved to the fail default and validation ran.
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `stormDataSource throws when plugin not installed`() = testApplication {
        application {
            try {
                stormDataSource
                throw AssertionError("Expected IllegalStateException")
            } catch (expected: IllegalStateException) {
                expected.message shouldBe "Storm plugin is not installed. Call install(Storm) in your application module."
            }
        }
    }

    @Test
    fun `user-supplied data source is not closed at shutdown`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                }
                startApplication()
            }
            dataSource.isClosed shouldBe false
            // The pool must remain usable after the application stopped.
            dataSource.connection.use { }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `plugin-created data source is closed at shutdown`() {
        lateinit var created: DataSource
        testApplication {
            environment {
                config = MapApplicationConfig(
                    "storm.datasource.jdbcUrl" to "jdbc:h2:mem:storm-owned-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                    "storm.datasource.driverClassName" to "org.h2.Driver",
                    "storm.datasource.username" to "sa",
                    "storm.datasource.password" to "",
                )
            }
            application {
                install(Storm) {
                    schemaValidation = "none"
                }
                created = stormDataSource
            }
            startApplication()
        }
        (created as HikariDataSource).isClosed shouldBe true
    }
}
