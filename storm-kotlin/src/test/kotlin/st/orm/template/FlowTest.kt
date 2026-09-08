package st.orm.template

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.PersistenceException
import st.orm.repository.select
import st.orm.template.model.Visit

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class FlowTest(
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
    fun `statement issued while a flow is collected within a transaction is refused`(): Unit = runBlocking {
        // Inside a transaction the flow and the delete share the transaction's connection, and the flow holds it
        // consume-only until its last row is emitted. A batch smaller than the result makes the delete execute
        // while rows remain, so its first statement is refused, on every database.
        transaction {
            val repository = orm.entity(Visit::class)
            val exception = shouldThrow<PersistenceException> {
                repository.remove(repository.select().resultFlow, 5)
            }
            exception.message shouldContain "result stream is still open"
            exception.message shouldContain "windows(size)"
            // The refused flow is closed, so the connection is free again.
            repository.count() shouldBe 14
        }
    }

    @Test
    fun `write fed by a flow it reads to the end first is allowed`(): Unit = runBlocking {
        // With every row emitted before the first batch executes, the flow has completed and closed its statement.
        transaction {
            val repository = orm.entity(Visit::class)
            repository.remove(repository.select().resultFlow)
            repository.count() shouldBe 0
        }
    }

    @Test
    fun `query issued from a flow collector within a transaction is refused`(): Unit = runBlocking {
        transaction {
            val repository = orm.entity(Visit::class)
            val exception = shouldThrow<PersistenceException> {
                repository.select().resultFlow.collect { repository.count() }
            }
            exception.message shouldContain "result stream is still open"
        }
    }

    // Windows: one closed statement per window, so the connection is free between windows.

    @Test
    fun `windows should emit closed windows in key order`(): Unit = runBlocking {
        val windows = orm.entity(Visit::class).windows(4).toList()
        windows.map { it.content().size } shouldBe listOf(4, 4, 4, 2)
        windows.map { it.hasNext() } shouldBe listOf(true, true, true, false)
        windows.flatMap { it.content() }.map { it.id } shouldBe (1..14).toList()
    }

    @Test
    fun `windows within suspend transaction allow a write per window`(): Unit = runBlocking {
        transaction {
            val repository = orm.entity(Visit::class)
            repository.windows(5).collect { window ->
                repository.count() shouldBe 14 - (window.content().first().id - 1)
                repository.remove(window.content())
            }
            repository.count() shouldBe 0
        }
    }

    @Test
    fun `windows resume from a navigation token`(): Unit = runBlocking {
        val first = orm.entity(Visit::class).windows(4).first()
        val rest = orm.entity(Visit::class).windows(first.next<Visit>()!!).toList()
        rest.flatMap { it.content() }.map { it.id } shouldBe (5..14).toList()
    }
}
