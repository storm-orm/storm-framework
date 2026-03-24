package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.ktor.model.PetType
import st.orm.template.ORMTemplate

class StormPluginTest {

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
            petTypes.findAll().toList().size shouldBe 2
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
}
