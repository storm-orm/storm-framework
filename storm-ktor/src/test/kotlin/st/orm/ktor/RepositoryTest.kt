package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.ktor.model.PetRepository

class RepositoryTest {

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:storm-repo-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
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
    fun `register and retrieve repository`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    stormRepositories {
                        register(PetRepository::class)
                    }
                    val petRepository = repository<PetRepository>()
                    petRepository shouldNotBe null
                    petRepository.findAll().size shouldBe 3
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `register by package does not crash when no index exists`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    stormRepositories {
                        register("st.orm.ktor.model")
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `unregistered repository throws`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    stormRepositories { }
                    try {
                        repository<PetRepository>()
                        throw AssertionError("Expected IllegalStateException")
                    } catch (expected: IllegalStateException) {
                        expected.message!! shouldBe
                            "Repository PetRepository is not registered. " +
                            "Call register(PetRepository::class) or register(\"<package>\") in stormRepositories { }."
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `forEach iterates registered repositories`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    val registry = stormRepositories {
                        register(PetRepository::class)
                    }
                    val types = mutableListOf<String>()
                    registry.forEach { type, _ -> types.add(type.simpleName!!) }
                    types shouldBe listOf("PetRepository")
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `repository throws when no registry configured`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    try {
                        repository<PetRepository>()
                        throw AssertionError("Expected IllegalStateException")
                    } catch (expected: IllegalStateException) {
                        expected.message shouldBe "No Storm repository registry configured. Call stormRepositories { } in your application module."
                    }
                }
            }
        } finally {
            dataSource.close()
        }
    }
}
