package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.DbTable
import st.orm.Entity
import st.orm.EntityCallback
import st.orm.EntityCallbacks
import st.orm.PK

/**
 * A callback an entity declares needs no plugin configuration: the Ktor plugin's template applies it because the
 * entity names it, the same way the Spring starter and a standalone template do.
 */
@DbTable("vet")
@EntityCallbacks(UppercaseLastName::class)
internal data class DeclaredVet(
    @PK val id: Int = 0,
    val firstName: String,
    val lastName: String,
) : Entity<Int>

internal class UppercaseLastName : EntityCallback<DeclaredVet> {
    override fun beforeInsert(entity: DeclaredVet): DeclaredVet = entity.copy(lastName = entity.lastName.uppercase())
}

internal class StormEntityCallbackTest {

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:storm-callback-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
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
    fun `a callback declared on the entity applies without plugin configuration`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            testApplication {
                application {
                    install(Storm) {
                        this.dataSource = dataSource
                    }
                    val vets = orm.entity(DeclaredVet::class)
                    vets.insert(DeclaredVet(firstName = "Sharon", lastName = "Jenkins"))
                    vets.findAll().map { it.lastName } shouldBe listOf("JENKINS")
                }
            }
        } finally {
            dataSource.close()
        }
    }
}
