package st.orm.template

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.repository.select
import st.orm.template.model.Visit

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class FlowTest(
    @Autowired val orm: ORMTemplate,
) {

    // Flow operations without explicit transaction

    @Test
    fun `selectAll should return all visits as flow`(): Unit = runBlocking {
        // data.sql inserts exactly 14 visits (ids 1-14).
        orm.select<Visit>().resultFlow.count() shouldBe 14
    }

    @Test
    fun `remove flow should remove all visits`(): Unit = runBlocking {
        // Deleting all entities via a flow should leave the table empty.
        val repository = orm.entity(Visit::class)
        val entities = repository.select().resultFlow
        repository.remove(entities)
        repository.count() shouldBe 0
    }

    // Flow operations within a suspend transaction

    @Test
    fun `selectAll within suspend transaction should return all visits`(): Unit = runBlocking {
        // Same as above but within a suspend transaction; data.sql inserts 14 visits.
        transaction {
            orm.select<Visit>().resultFlow.count() shouldBe 14
        }
    }

    @Test
    fun `remove flow within suspend transaction should remove all visits`(): Unit = runBlocking {
        // Deleting all entities via flow within a suspend transaction should leave the table empty.
        transaction {
            val repository = orm.entity(Visit::class)
            val entities = repository.select().resultFlow
            repository.remove(entities)
            repository.count() shouldBe 0
        }
    }
}
