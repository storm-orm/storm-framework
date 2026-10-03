package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.response.respond
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.NoResultException
import st.orm.OptimisticLockException
import st.orm.PersistenceException
import st.orm.ktor.model.Pet
import st.orm.ktor.model.PetRepository
import st.orm.ktor.model.PetType
import java.sql.SQLException

/**
 * Verifies the [transactional] route DSL: routes inside the block run in one transaction per call, committed
 * when the handler completes and rolled back when it throws, with options passed through and nested blocks
 * joining the outer transaction. Also verifies the StatusPages recipes documented in the Ktor integration
 * guide, exactly as written there.
 */
internal class TransactionalRouteTest {

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:storm-transactional-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }
        val dataSource = HikariDataSource(config)
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
        return dataSource
    }

    private fun countPets(dataSource: HikariDataSource): Int = dataSource.connection.use { connection ->
        connection.createStatement().use { statement ->
            statement.executeQuery("SELECT COUNT(*) FROM pet").use { resultSet ->
                resultSet.next()
                resultSet.getInt(1)
            }
        }
    }

    @Test
    fun `transactional route commits when the handler completes`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    routing {
                        transactional {
                            post("/pets") {
                                repository<PetRepository>().insert(Pet(name = "Milo", type = PetType(id = 1, name = "Cat")))
                                call.respondText("created", status = HttpStatusCode.Created)
                            }
                        }
                    }
                }
                client.post("/pets").status shouldBe HttpStatusCode.Created
                countPets(dataSource) shouldBe 4
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `transactional route rolls back when the handler throws`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    // The failure leaves the transactional route, which rolls back, and becomes the response.
                    install(StatusPages) {
                        exception<IllegalStateException> { call, _ ->
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }
                    routing {
                        transactional {
                            post("/pets") {
                                repository<PetRepository>().insert(Pet(name = "Milo", type = PetType(id = 1, name = "Cat")))
                                throw IllegalStateException("Simulated failure after the insert.")
                            }
                        }
                    }
                }
                client.post("/pets").status shouldBe HttpStatusCode.InternalServerError
                countPets(dataSource) shouldBe 3
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `nested transactional blocks join the outer transaction`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    // The failure leaves the transactional route, which rolls back, and becomes the response.
                    install(StatusPages) {
                        exception<IllegalStateException> { call, _ ->
                            call.respond(HttpStatusCode.InternalServerError)
                        }
                    }
                    routing {
                        transactional {
                            transactional {
                                post("/pets/pair") {
                                    val pets = repository<PetRepository>()
                                    pets.insert(Pet(name = "Milo", type = PetType(id = 1, name = "Cat")))
                                    pets.insert(Pet(name = "Rex", type = PetType(id = 2, name = "Dog")))
                                    throw IllegalStateException("Simulated failure after both inserts.")
                                }
                            }
                        }
                    }
                }
                client.post("/pets/pair").status shouldBe HttpStatusCode.InternalServerError
                // One rollback covers both writes: the inner block joined the outer transaction.
                countPets(dataSource) shouldBe 3
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `read-only transactional route serves reads`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    routing {
                        transactional(readOnly = true) {
                            get("/pets/count") {
                                call.respondText(repository<PetRepository>().findAll().size.toString())
                            }
                        }
                    }
                }
                val response = client.get("/pets/count")
                response.status shouldBe HttpStatusCode.OK
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `StatusPages recipes map Storm exceptions to HTTP responses`() {
        val dataSource = createTestDataSource()
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    // The recipes exactly as documented in docs/ktor-integration.md.
                    install(StatusPages) {
                        exception<NoResultException> { call, _ ->
                            call.respond(HttpStatusCode.NotFound)
                        }
                        exception<OptimisticLockException> { call, _ ->
                            call.respond(HttpStatusCode.Conflict)
                        }
                        exception<PersistenceException> { call, cause ->
                            val constraintViolation = generateSequence<Throwable>(cause) { it.cause }
                                .filterIsInstance<SQLException>()
                                .any { it.sqlState?.startsWith("23") == true }
                            if (constraintViolation) {
                                call.respond(HttpStatusCode.Conflict)
                            } else {
                                call.respond(HttpStatusCode.InternalServerError)
                            }
                        }
                    }
                    routing {
                        get("/pets/{id}") {
                            val pet = repository<PetRepository>().getById(call.parameters["id"]!!.toInt())
                            call.respond(pet.name)
                        }
                        transactional {
                            post("/pets/invalid") {
                                // The referenced pet type does not exist: foreign key violation (SQL state 23xxx).
                                repository<PetRepository>().insert(Pet(name = "Ghost", type = PetType(id = 999, name = "Unknown")))
                                call.respondText("created", status = HttpStatusCode.Created)
                            }
                        }
                    }
                }
                // Missing row: getById throws NoResultException, mapped to 404.
                client.get("/pets/9999").status shouldBe HttpStatusCode.NotFound
                // Constraint violation: mapped to 409, and the transaction rolled back first.
                client.post("/pets/invalid").status shouldBe HttpStatusCode.Conflict
                countPets(dataSource) shouldBe 3
                // Existing row still resolves normally.
                client.get("/pets/1").status shouldBe HttpStatusCode.OK
            }
        } finally {
            dataSource.close()
        }
    }
}
