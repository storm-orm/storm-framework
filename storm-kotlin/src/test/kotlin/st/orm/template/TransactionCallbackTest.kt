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
import st.orm.repository.deleteAll
import st.orm.repository.exists
import st.orm.template.TransactionPropagation.*
import st.orm.template.model.Visit

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class TransactionCallbackTest(
    @Autowired val orm: ORMTemplate,
) {

    @AfterEach
    fun resetDefaults() {
        setGlobalTransactionOptions(
            propagation = REQUIRED,
            isolation = null,
            timeoutSeconds = null,
            readOnly = false,
        )
    }

    @Test
    fun `onCommit fires after successful blocking transaction`(): Unit = runBlocking {
        var committed = false
        transactionBlocking {
            orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
        val exception = assertThrows<IllegalStateException> {
            transactionBlocking {
                onCommit { throw IllegalStateException("first") }
                onCommit { secondExecuted = true }
            }
        }
        secondExecuted.shouldBeTrue()
        exception.message shouldBe "first"
    }

    @Test
    fun `joined REQUIRED onCommit deferred to outer commit`(): Unit = runBlocking {
        var innerCommitted = false
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
                orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
            orm.deleteAll<Visit>()
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
        exception.suppressed[0].message shouldBe "callback"
    }
}
