package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.ktor.model.Pet
import st.orm.ktor.model.PetRepository
import st.orm.ktor.model.PetView

internal class RepositoryTest {

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
    fun `register no-arg discovers repositories from type index`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    stormRepositories {
                        register()
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
    fun `register by package discovers repositories from type index`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    stormRepositories {
                        register("st.orm.ktor.model")
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
    fun `repository resolves without any registration`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    // No stormRepositories block: the type index auto-registers at install.
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
    fun `auto-registration registers indexed repositories eagerly`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    val types = mutableListOf<String>()
                    stormRepositories { }.forEach { type, _ -> types.add(type.simpleName!!) }
                    // Without named databases, all indexed repositories register against the primary.
                    types.sorted() shouldBe listOf("PetRepository", "VetRepository")
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `auto-registration can be narrowed by package`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        repositories("com.example.nowhere")
                    }
                    // Nothing matches the package, so the registry starts empty ...
                    val registeredTypes = mutableListOf<String>()
                    stormRepositories { }.forEach { type, _ -> registeredTypes.add(type.simpleName!!) }
                    registeredTypes shouldBe emptyList()
                    // ... but lazy creation still resolves the repository on first access.
                    val petRepository = repository<PetRepository>()
                    petRepository.findAll().size shouldBe 3
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `auto-registration can be disabled`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                        autoRegisterRepositories = false
                    }
                    val registeredTypes = mutableListOf<String>()
                    stormRepositories { }.forEach { type, _ -> registeredTypes.add(type.simpleName!!) }
                    registeredTypes shouldBe emptyList()
                    // Lazy creation still resolves the repository on first access.
                    val petRepository = repository<PetRepository>()
                    petRepository.findAll().size shouldBe 3
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
                    // Without named databases, all indexed repositories register against the primary.
                    types.sorted() shouldBe listOf("PetRepository", "VetRepository")
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `entity extension with type arguments infers the primary key type`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    val typedPets = entity<Pet, _>()
                    typedPets.findById(1) shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `projection extension with type arguments infers the primary key type`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    val typedPetViews = projection<PetView, _>()
                    typedPetViews.findById(1) shouldNotBe null
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `class lookups infer the primary key type`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    entity(Pet::class).findById(1)?.name shouldBe "Leo"
                    projection(PetView::class).findById(2)?.name shouldBe "Basil"
                    routing {
                        get("/routing-context") {
                            val pet = entity(Pet::class).findById(1)
                            val petView = projection(PetView::class).findById(2)
                            call.respondText("${pet?.name},${petView?.name}")
                        }
                        get("/call") {
                            val pet = call.entity(Pet::class).findById(1)
                            val petView = call.projection(PetView::class).findById(2)
                            call.respondText("${pet?.name},${petView?.name}")
                        }
                    }
                }
                client.get("/routing-context").bodyAsText() shouldBe "Leo,Basil"
                client.get("/call").bodyAsText() shouldBe "Leo,Basil"
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `repository throws when plugin not installed`() {
        testApplication {
            application {
                try {
                    repository<PetRepository>()
                    throw AssertionError("Expected IllegalStateException")
                } catch (expected: IllegalStateException) {
                    expected.message shouldBe "Storm plugin is not installed. Call install(Storm) in your application module."
                }
            }
        }
    }
}
