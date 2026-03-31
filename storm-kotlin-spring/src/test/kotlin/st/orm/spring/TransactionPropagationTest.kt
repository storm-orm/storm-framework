package st.orm.spring

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import st.orm.repository.countAll
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.spring.model.Visit
import st.orm.template.*
import st.orm.template.TransactionPropagation.*

/**
 * Tests for the SUPPORTS, MANDATORY, NOT_SUPPORTED, and NEVER transaction propagation modes.
 *
 * These tests cover both programmatic transactions (without Spring-managed transaction integration)
 * and Spring-managed transactions (with [EnableTransactionIntegration]), verifying that each
 * propagation mode behaves correctly in single-layer and two-layer (nested) scenarios.
 */
@ContextConfiguration(classes = [IntegrationConfig::class])
@EnableTransactionIntegration
@SpringBootTest
@Sql("/data.sql")
open class TransactionPropagationTest(
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

    // SUPPORTS propagation

    @Test
    fun `supports without outer transaction should execute non-transactionally and persist changes`(): Unit = runBlocking {
        // SUPPORTS without an existing transaction executes non-transactionally.
        // Modifications should be visible immediately since there is no transaction to roll back.
        transactionBlocking(SUPPORTS) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `supports without outer transaction should ignore rollback-only since there is no transaction`(): Unit = runBlocking {
        // Without a real transaction, setRollbackOnly is a no-op (or the modifications are auto-committed).
        // The exact behavior depends on whether the database connection is in auto-commit mode.
        // In non-transactional mode, writes are immediately committed, so rollback-only has no effect.
        transactionBlocking(SUPPORTS) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        // Modifications should have been immediately committed since there is no transaction boundary.
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `supports inside required outer transaction should join the outer transaction and commit together`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(SUPPORTS) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `supports inside required outer transaction should roll back with outer transaction`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(SUPPORTS) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `supports inner rollback-only should cause unexpected rollback on outer commit`(): Unit = runBlocking {
        // SUPPORTS joins the outer REQUIRED transaction. When inner marks rollback-only, it poisons the
        // entire physical transaction, causing an UnexpectedRollbackException when the outer tries to commit.
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking(REQUIRED) {
                transactionBlocking(SUPPORTS) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    // MANDATORY propagation

    @Test
    fun `mandatory without outer transaction should throw exception on database access`(): Unit = runBlocking {
        // MANDATORY requires an existing transaction. Without one, attempting to use the database
        // should throw an exception. The exact exception depends on when the transaction is
        // materialized (eagerly by Spring or lazily on first DB access).
        assertThrows<Exception> {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
        // Data should remain unchanged since the transaction was never started.
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `mandatory inside required outer transaction should join and commit together`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `mandatory inside required outer transaction should roll back with outer`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `mandatory inner rollback-only should cause unexpected rollback on outer commit`(): Unit = runBlocking {
        // MANDATORY joins the outer REQUIRED transaction. Marking rollback-only in the inner scope
        // affects the entire physical transaction.
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking(REQUIRED) {
                transactionBlocking(MANDATORY) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `mandatory inner rollback-only with explicit outer rollback should not throw`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    // NOT_SUPPORTED propagation

    @Test
    fun `not_supported without outer transaction should execute non-transactionally`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `not_supported inside required outer transaction should suspend outer and execute independently`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(NOT_SUPPORTED) {
                // This runs outside any transaction (outer is suspended).
                orm.removeAll<Visit>()
            }
            // The outer transaction is resumed. The inner changes were committed immediately
            // since they ran non-transactionally.
            orm.exists<Visit>().shouldBeFalse()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `not_supported changes should survive outer rollback since they run non-transactionally`(): Unit = runBlocking {
        transactionBlocking(REQUIRED) {
            transactionBlocking(NOT_SUPPORTED) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
        }
        // NOT_SUPPORTED changes were committed outside the transaction, so they survive the rollback.
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `not_supported should ignore rollback-only since there is no transaction`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        // Non-transactional execution means changes are auto-committed.
        orm.exists<Visit>().shouldBeFalse()
    }

    // NEVER propagation

    @Test
    fun `never without outer transaction should execute non-transactionally`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `never inside required outer transaction should throw exception on database access`(): Unit = runBlocking {
        // NEVER must not be called within an existing transaction. When it is, accessing the
        // database should fail.
        assertThrows<Exception> {
            transactionBlocking(REQUIRED) {
                transactionBlocking(NEVER) {
                    orm.removeAll<Visit>()
                }
            }
        }
        // The outer transaction is rolled back due to the exception.
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `never should ignore rollback-only since there is no transaction`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        // Non-transactional: changes committed immediately.
        orm.exists<Visit>().shouldBeFalse()
    }

    // Mixed propagation scenarios

    @Test
    fun `requires_new inside supports without outer transaction should create a new transaction`(): Unit = runBlocking {
        // SUPPORTS without outer tx runs non-transactionally. REQUIRES_NEW inside creates a real tx.
        transactionBlocking(SUPPORTS) {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `requires_new inside supports without outer should allow independent rollback`(): Unit = runBlocking {
        transactionBlocking(SUPPORTS) {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
        }
        // REQUIRES_NEW was rolled back, data should remain.
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `mandatory inside requires_new should join the new transaction`(): Unit = runBlocking {
        // REQUIRES_NEW creates a new transaction, then MANDATORY joins it.
        transactionBlocking(REQUIRES_NEW) {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `not_supported inside requires_new should suspend the new transaction`(): Unit = runBlocking {
        transactionBlocking(REQUIRES_NEW) {
            transactionBlocking(NOT_SUPPORTED) {
                orm.removeAll<Visit>()
            }
            // NOT_SUPPORTED committed changes non-transactionally.
            orm.exists<Visit>().shouldBeFalse()
            setRollbackOnly()
        }
        // REQUIRES_NEW rolled back, but NOT_SUPPORTED changes persist.
        orm.exists<Visit>().shouldBeFalse()
    }

    // Global default propagation

    @Test
    fun `global default propagation SUPPORTS should execute non-transactionally when no outer exists`(): Unit = runBlocking {
        setGlobalTransactionOptions(propagation = SUPPORTS)
        transactionBlocking {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `global default propagation can be overridden by explicit argument`(): Unit = runBlocking {
        setGlobalTransactionOptions(propagation = NEVER)
        // Even though global says NEVER, explicit REQUIRED should work.
        transactionBlocking(REQUIRED) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `countAll should work within supports propagation`(): Unit = runBlocking {
        transactionBlocking(SUPPORTS) {
            orm.countAll<Visit>() shouldBe 14
        }
    }

    @Test
    fun `countAll should work within never propagation without outer transaction`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.countAll<Visit>() shouldBe 14
        }
    }

    @Test
    fun `countAll should work within not_supported propagation`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.countAll<Visit>() shouldBe 14
        }
    }
}
