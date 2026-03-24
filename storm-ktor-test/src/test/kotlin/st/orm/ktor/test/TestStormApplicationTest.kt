package st.orm.ktor.test

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import org.junit.jupiter.api.Test
import st.orm.ktor.Storm
import st.orm.ktor.orm
import st.orm.ktor.test.model.Pet

class TestStormApplicationTest {

    @Test
    fun `testStormApplication provides DataSource and ORMTemplate`() = testStormApplication(
        scripts = listOf("/schema.sql"),
    ) { scope ->
        scope.stormDataSource shouldNotBe null
        scope.stormOrm shouldNotBe null
        val pets = scope.stormOrm.entity(Pet::class)
        pets.findAll().size shouldBe 3
    }

    @Test
    fun `testStormApplication integrates with Ktor routes`() = testStormApplication(
        scripts = listOf("/schema.sql"),
    ) { scope ->
        application {
            install(Storm) {
                dataSource = scope.stormDataSource
            }
            routing {
                get("/pets/count") {
                    val count = call.application.orm.entity(Pet::class).findAll().size
                    call.respondText(count.toString())
                }
            }
        }
        val response = client.get("/pets/count")
        response.status shouldBe HttpStatusCode.OK
    }

    @Test
    fun `testStormApplication works without scripts`() = testStormApplication { scope ->
        scope.stormDataSource shouldNotBe null
        scope.stormOrm shouldNotBe null
    }

    @Test
    fun `testStormApplication SqlCapture is available`() = testStormApplication(
        scripts = listOf("/schema.sql"),
    ) { scope ->
        scope.stormSqlCapture shouldNotBe null
    }
}
