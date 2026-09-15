package st.orm.template

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.TransactionCallbackException
import st.orm.TransactionPropagation.*
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.template.model.Visit
import java.util.function.Consumer

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class TransactionCallbackTest(
    @Autowired val orm: ORMTemplate,
) {

    @AfterEach
    fun resetDefaults() {
        setGlobalTransactionOptions()
    }

    @Test
    fun `onCommit fires after successful blocking transaction`(): Unit = runBlocking {
        var committed = false
        transactionBlocking {
            orm.removeAll<Visit>()
            onCommit { committed = true }
        }
        committed.shouldBeTrue()
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `onRollback fires after exception in blocking transaction`(): Unit = runBlocking {
        var rolledBack = false
        assertThrows<IllegalStateException> {
            transactionBlocking {
                orm.removeAll<Visit>()
                onRollback { rolledBack = true }
                throw IllegalStateException("boom")
            }
        }
        rolledBack.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `onRollback fires after setRollbackOnly in blocking transaction`(): Unit = runBlocking {
        var rolledBack = false
        transactionBlocking {
            orm.removeAll<Visit>()
            onRollback { rolledBack = true }
            setRollbackOnly()
        }
        rolledBack.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `onCommit does not fire on rollback`(): Unit = runBlocking {
        var committed = false
        transactionBlocking {
            onCommit { committed = true }
            setRollbackOnly()
        }
        committed.shouldBeFalse()
    }

    @Test
    fun `onRollback does not fire on commit`(): Unit = runBlocking {
        var rolledBack = false
        transactionBlocking {
            onRollback { rolledBack = true }
        }
        rolledBack.shouldBeFalse()
    }

    @Test
    fun `onCommit fires after successful suspend transaction`(): Unit = runBlocking {
        var committed = false
        transaction {
            orm.removeAll<Visit>()
            onCommit { committed = true }
        }
        committed.shouldBeTrue()
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `onRollback fires after exception in suspend transaction`(): Unit = runBlocking {
        var rolledBack = false
        assertThrows<IllegalStateException> {
            transaction {
                orm.removeAll<Visit>()
                onRollback { rolledBack = true }
                throw IllegalStateException("boom")
            }
        }
        rolledBack.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `onRollback fires after setRollbackOnly in suspend transaction`(): Unit = runBlocking {
        var rolledBack = false
        transaction {
            orm.removeAll<Visit>()
            onRollback { rolledBack = true }
            setRollbackOnly()
        }
        rolledBack.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `multiple callbacks execute in registration order`(): Unit = runBlocking {
        val order = mutableListOf<Int>()
        transactionBlocking {
            onCommit { order += 1 }
            onCommit { order += 2 }
            onCommit { order += 3 }
        }
        order shouldBe listOf(1, 2, 3)
    }

    @Test
    fun `callback exception does not prevent other callbacks from running`(): Unit = runBlocking {
        var secondExecuted = false
        val exception = assertThrows<TransactionCallbackException> {
            transactionBlocking {
                onCommit { throw IllegalStateException("first") }
                onCommit { secondExecuted = true }
            }
        }
        secondExecuted.shouldBeTrue()
        exception.isCommitted.shouldBeTrue()
        exception.cause?.message shouldBe "first"
    }

    @Test
    fun `joined REQUIRED onCommit deferred to outer commit`(): Unit = runBlocking {
        var innerCommitted = false
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            // Inner has returned, but callbacks should not have fired yet (deferred to outer).
            innerCommitted.shouldBeFalse()
        }
        // Now the outer committed: inner's onCommit should have fired.
        innerCommitted.shouldBeTrue()
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `joined REQUIRED onCommit not fired if outer rolls back`(): Unit = runBlocking {
        var innerCommitted = false
        var outerRolledBack = false
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            onRollback { outerRolledBack = true }
            setRollbackOnly()
        }
        innerCommitted.shouldBeFalse()
        outerRolledBack.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `REQUIRES_NEW inner onCommit fires independently of outer`(): Unit = runBlocking {
        var innerCommitted = false
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            // Inner has its own physical tx: callbacks fire immediately after inner returns.
            innerCommitted.shouldBeTrue()
            setRollbackOnly() // Outer rolls back, but inner was already committed.
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `REQUIRES_NEW inner onRollback fires independently of outer`(): Unit = runBlocking {
        var innerRolledBack = false
        var outerCommitted = false
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
                onRollback { innerRolledBack = true }
                setRollbackOnly()
            }
            onCommit { outerCommitted = true }
        }
        innerRolledBack.shouldBeTrue()
        outerCommitted.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `NESTED onCommit deferred to outer commit`(): Unit = runBlocking {
        var innerCommitted = false
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            innerCommitted.shouldBeFalse()
        }
        innerCommitted.shouldBeTrue()
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `NESTED onCommit not fired if outer rolls back`(): Unit = runBlocking {
        var innerCommitted = false
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            setRollbackOnly()
        }
        innerCommitted.shouldBeFalse()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `suspend joined REQUIRED onCommit deferred to outer commit`(): Unit = runBlocking {
        var innerCommitted = false
        transaction {
            transaction(propagation = REQUIRED) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            innerCommitted.shouldBeFalse()
        }
        innerCommitted.shouldBeTrue()
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend joined REQUIRED onCommit not fired if outer rolls back`(): Unit = runBlocking {
        var innerCommitted = false
        transaction {
            transaction(propagation = REQUIRED) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            setRollbackOnly()
        }
        innerCommitted.shouldBeFalse()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `suspend REQUIRES_NEW inner onCommit fires independently`(): Unit = runBlocking {
        var innerCommitted = false
        transaction {
            transaction(propagation = REQUIRES_NEW) {
                orm.removeAll<Visit>()
                onCommit { innerCommitted = true }
            }
            innerCommitted.shouldBeTrue()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend onCommit callback works in suspend transaction`(): Unit = runBlocking {
        var committed = false
        transaction {
            orm.removeAll<Visit>()
            onCommit {
                kotlinx.coroutines.delay(1) // Verify suspend is actually supported
                committed = true
            }
        }
        committed.shouldBeTrue()
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend onRollback callback works in suspend transaction`(): Unit = runBlocking {
        var rolledBack = false
        transaction {
            orm.removeAll<Visit>()
            onRollback {
                kotlinx.coroutines.delay(1) // Verify suspend is actually supported
                rolledBack = true
            }
            setRollbackOnly()
        }
        rolledBack.shouldBeTrue()
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `onCommit callback can perform database operations in blocking transaction`(): Unit = runBlocking {
        var visitExists = false
        transactionBlocking {
            orm.removeAll<Visit>()
            onCommit {
                // After commit, the transaction context is cleaned up. DB operations use auto-commit.
                visitExists = orm.exists<Visit>()
            }
        }
        visitExists.shouldBeFalse()
    }

    @Test
    fun `onCommit callback can perform database operations in suspend transaction`(): Unit = runBlocking {
        var visitExists = false
        transaction {
            orm.removeAll<Visit>()
            onCommit {
                // After commit, the transaction context is cleaned up. DB operations use auto-commit.
                visitExists = orm.exists<Visit>()
            }
        }
        visitExists.shouldBeFalse()
    }

    @Test
    fun `onCommit callback can start new transaction`(): Unit = runBlocking {
        var visitExists = false
        transaction {
            orm.removeAll<Visit>()
            onCommit {
                transaction {
                    visitExists = orm.exists<Visit>()
                }
            }
        }
        visitExists.shouldBeFalse()
    }

    @Test
    fun `rollback callback exception is suppressed under transaction exception`(): Unit = runBlocking {
        val exception = assertThrows<IllegalStateException> {
            transactionBlocking {
                onRollback { throw RuntimeException("callback") }
                throw IllegalStateException("tx")
            }
        }
        exception.message shouldBe "tx"
        exception.suppressed.size shouldBe 1
        val callbackFailure = exception.suppressed[0] as TransactionCallbackException
        callbackFailure.isCommitted.shouldBeFalse()
        callbackFailure.cause?.message shouldBe "callback"
    }

    @Test
    fun `onCompletion reports a commit`(): Unit = runBlocking {
        var observed: Boolean? = null
        transaction {
            onCompletion { committed -> observed = committed }
        }
        observed shouldBe true
    }

    @Test
    fun `onCompletion reports a rollback`(): Unit = runBlocking {
        var observed: Boolean? = null
        transaction {
            onCompletion { committed -> observed = committed }
            setRollbackOnly()
        }
        observed shouldBe false
    }

    @Test
    fun `onCompletion fires in registration order with the outcome-specific callbacks`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        transaction {
            onCompletion { events += "completion-1" }
            onCommit { events += "commit" }
            onRollback { events += "rollback" }
            onCompletion { events += "completion-2" }
        }
        events shouldBe listOf("completion-1", "commit", "completion-2")
    }

    @Test
    fun `commit callback failure reports the transaction as committed`(): Unit = runBlocking {
        val exception = assertThrows<TransactionCallbackException> {
            transaction {
                orm.removeAll<Visit>()
                onCommit { throw IllegalStateException("callback") }
            }
        }
        exception.isCommitted.shouldBeTrue()
        exception.cause?.message shouldBe "callback"
        // The failure is a failed side effect: the transaction itself committed.
        transaction { orm.exists<Visit>() }.shouldBeFalse()
    }

    @Test
    fun `further callback failures are suppressed onto the first`(): Unit = runBlocking {
        val exception = assertThrows<TransactionCallbackException> {
            transaction {
                onCommit { throw IllegalStateException("first") }
                onCommit { throw IllegalStateException("second") }
            }
        }
        exception.cause?.message shouldBe "first"
        exception.cause?.suppressed?.size shouldBe 1
        exception.cause?.suppressed?.get(0)?.message shouldBe "second"
    }

    @Test
    fun `a callback registered from a callback does not disturb the run`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        transaction {
            onCommit {
                events += "first"
                onCommit { events += "registered-during-run" }
            }
            onCommit { events += "second" }
        }
        events shouldBe listOf("first", "second")
    }

    @Test
    fun `a nested blocking block registers callbacks on the suspend transaction it joins`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        transaction {
            orm.exists<Visit>()
            // Code that does not see this block's receiver, such as an entity callback, participates by opening
            // a joining block of its own; its callbacks defer to the outermost physical commit.
            registerAuditHook(events)
            events shouldBe emptyList()
        }
        events shouldBe listOf("commit")
    }

    private fun registerAuditHook(events: MutableList<String>) {
        transactionBlocking {
            onCommit { events += "commit" }
        }
    }

    // The language-neutral overloads inside a suspend block, and the suspend onCompletion inside a blocking block.

    @Test
    fun `Runnable and Consumer callbacks registered in a suspend transaction fire on commit`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        transaction {
            onCommit(Runnable { events += "commit" })
            onRollback(Runnable { events += "rollback" })
            onCompletion(Consumer { committed -> events += "completion:$committed" })
        }
        events shouldBe listOf("commit", "completion:true")
    }

    @Test
    fun `Runnable and Consumer callbacks registered in a suspend transaction fire on rollback`(): Unit = runBlocking {
        val events = mutableListOf<String>()
        transaction {
            onCommit(Runnable { events += "commit" })
            onRollback(Runnable { events += "rollback" })
            onCompletion(Consumer { committed -> events += "completion:$committed" })
            setRollbackOnly()
        }
        events shouldBe listOf("rollback", "completion:false")
    }

    @Test
    fun `isRollbackOnly reflects setRollbackOnly inside a suspend transaction`(): Unit = runBlocking {
        val observed = mutableListOf<Boolean>()
        transaction {
            observed += isRollbackOnly()
            setRollbackOnly()
            observed += isRollbackOnly()
        }
        observed shouldBe listOf(false, true)
    }

    @Test
    fun `suspend onCompletion reports the outcome of a blocking transaction`(): Unit = runBlocking {
        var afterCommit: Boolean? = null
        transactionBlocking {
            onCompletion { committed -> afterCommit = committed }
        }
        afterCommit shouldBe true
        var afterRollback: Boolean? = null
        transactionBlocking {
            onCompletion { committed -> afterRollback = committed }
            setRollbackOnly()
        }
        afterRollback shouldBe false
    }
}
