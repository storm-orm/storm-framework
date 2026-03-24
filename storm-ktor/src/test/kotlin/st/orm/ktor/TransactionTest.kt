package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import st.orm.ktor.model.Pet
import st.orm.ktor.model.PetType
import st.orm.template.ORMTemplate
import st.orm.template.transaction

class TransactionTest {

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:storm-tx-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
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
    fun `transaction commit persists data`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            val orm = ORMTemplate.of(dataSource)
            kotlinx.coroutines.runBlocking {
                transaction {
                    val pets = orm.entity(Pet::class)
                    pets.insert(Pet(name = "Milo", type = PetType(id = 1, name = "Cat")))
                }
            }
            val pets = orm.entity(Pet::class)
            pets.findAll().toList().size shouldBe 4
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `transaction rollback does not persist data`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            val orm = ORMTemplate.of(dataSource)
            try {
                kotlinx.coroutines.runBlocking {
                    transaction {
                        val pets = orm.entity(Pet::class)
                        pets.insert(Pet(name = "Milo", type = PetType(id = 1, name = "Cat")))
                        throw RuntimeException("Simulated error")
                    }
                }
            } catch (_: RuntimeException) {
                // Expected.
            }
            val pets = orm.entity(Pet::class)
            pets.findAll().toList().size shouldBe 3
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `read operations work without explicit transaction`() {
        val dataSource = createTestDataSource()
        initializeSchema(dataSource)
        try {
            val orm = ORMTemplate.of(dataSource)
            val pets = orm.entity(Pet::class)
            pets.findAll().toList().size shouldBe 3
        } finally {
            dataSource.close()
        }
    }
}
