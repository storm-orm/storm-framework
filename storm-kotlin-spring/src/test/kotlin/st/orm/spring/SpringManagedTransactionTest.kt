package st.orm.spring

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.transaction.annotation.Transactional
import st.orm.PersistenceException
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.spring.model.Visit
import st.orm.template.*
import st.orm.template.TransactionIsolation.*
import st.orm.template.TransactionPropagation.*

@ContextConfiguration(classes = [IntegrationConfig::class])
@EnableTransactionIntegration
@SpringBootTest
@Sql("/data.sql")
open class SpringManagedTransactionTest(
    @Autowired val orm: ORMTemplate,
) {

    @AfterEach
    fun resetDefaults() {
        // Restore baseline defaults: REQUIRED, isolation=null, timeout=null, readOnly=false.
        setGlobalTransactionOptions(
            propagation = REQUIRED,
            isolation = null,
            timeoutSeconds = null,
            readOnly = false,
        )
    }

    /**
     * Single-layer scenarios (default REQUIRED propagation)
     */

    @Test
    fun `modification visible after required commit`(): Unit = runBlocking {
        transactionBlocking {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `modification rolled back after required rollback`(): Unit = runBlocking {
        transactionBlocking {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `modification rolled back after required rollback at start`(): Unit = runBlocking {
        transactionBlocking {
            setRollbackOnly()
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Two-layer scenarios: INNER uses REQUIRED (default), outer is default
     */

    @Test
    fun `modification visible for required inner commit and outer commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `modification rolled back for required inner commit and outer rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
            orm.exists<Visit>().shouldBeFalse()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Two-layer scenarios: INNER uses REQUIRES_NEW, outer is default
     */

    @Test
    fun `modification visible for requires_new inner commit and outer commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
            }
            orm.exists<Visit>().shouldBeFalse()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `modification visible for requires_new inner commit and outer rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
            orm.exists<Visit>().shouldBeFalse()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `modification rolled back for requires_new inner rollback and outer commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            orm.exists<Visit>().shouldBeTrue()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `modification rolled back for requires_new inner rollback and outer rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            setRollbackOnly()
            orm.exists<Visit>().shouldBeTrue()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Two-layer scenarios: INNER uses NESTED (JDBC savepoint), outer is default
     */

    @Test
    fun `modification visible for nested inner commit and outer commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `modification rolled back for nested inner commit and outer rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
            orm.exists<Visit>().shouldBeFalse()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `modification rolled back for nested inner rollback and outer commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            orm.exists<Visit>().shouldBeTrue()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `modification rolled back for nested inner rollback and outer rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            setRollbackOnly()
            orm.exists<Visit>().shouldBeTrue()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    // Single-layer tests with various isolation levels

    @Test
    fun `required commit with READ_UNCOMMITTED isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_UNCOMMITTED) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `required rollback with READ_UNCOMMITTED isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_UNCOMMITTED) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `required commit with READ_COMMITTED isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_COMMITTED) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `required rollback with READ_COMMITTED isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_COMMITTED) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `required commit with REPEATABLE_READ isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `required rollback with REPEATABLE_READ isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `required commit with SERIALIZABLE isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = SERIALIZABLE) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `required rollback with SERIALIZABLE isolation`(): Unit = runBlocking {
        transactionBlocking(isolation = SERIALIZABLE) {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Concurrent isolation tests
     */

    @Test
    fun `suspendTransaction not allowed with spring managed transactions`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transaction {
                orm.removeAll<Visit>()
            }
        }
    }

    @Transactional
    @Test
    open fun `programmatic transactions allowed with spring managed transactions`(): Unit = runBlocking {
        transactionBlocking {
            orm.removeAll<Visit>()
        }
    }

    /**
     * Transaction timeouts.
     */

    @Test
    open fun `transaction times out and rolls back when execution exceeds timeout`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                orm.removeAll<Visit>()
                Thread.sleep(1500)
            }
        }
        // Timeout should cause rollback: data still exists.
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    open fun `transaction times out and rolls back when execution exceeds query timeout`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                orm.removeAll<Visit>()
                orm.query(
                    """
                        SELECT COUNT(*)
                        FROM SYSTEM_RANGE(1, 1_000_000) AS A
                        CROSS JOIN SYSTEM_RANGE(1, 1_000_000) AS B
                    """.trimIndent(),
                ).singleResult
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Unexpected rollback scenarios.
     */

    @Test
    fun `outer commit fails with UnexpectedRollback when inner REQUIRED marks rollback-only`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                orm.removeAll<Visit>()
                // Inner REQUIRED participates in the same physical tx and marks rollback-only.
                transactionBlocking(REQUIRED) {
                    setRollbackOnly()
                }
                // Outer tries to commit -> should throw UnexpectedRollbackException.
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `nested rollback does not throw on outer commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            // Outer should still commit fine; data remains.
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `outer rollback does not throw when inner REQUIRED marked rollback-only too`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.removeAll<Visit>()
                setRollbackOnly() // Inner marks global tx rollback-only.
            }
            orm.exists<Visit>().shouldBeFalse() // Mid-scope still sees uncommitted change.
            setRollbackOnly() // Outer makes rollback explicit/expected.
            // exit -> should roll back WITHOUT throwing UnexpectedRollbackException.
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `inner transaction fails with exception should result in unexpected rollback exception if inner exception is ignored`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking(propagation = REQUIRED) {
                runCatching {
                    transactionBlocking(propagation = REQUIRED) {
                        orm.removeAll<Visit>()
                        throw IllegalStateException("Something went wrong")
                    }
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `inner transaction fails with exception should not result in unexpected rollback exception if inner exception falls through`(): Unit = runBlocking {
        assertThrows<IllegalStateException> {
            transactionBlocking(propagation = REQUIRED) {
                transactionBlocking(propagation = REQUIRED) {
                    orm.removeAll<Visit>()
                    throw IllegalStateException("Something went wrong")
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Global defaults.
     */

    @Test
    fun `global timeout applies to sync transaction`(): Unit = runBlocking {
        setGlobalTransactionOptions(timeoutSeconds = 1)
        assertThrows<TransactionTimedOutException> {
            transactionBlocking {
                orm.removeAll<Visit>()
                Thread.sleep(1500)
            }
        }
        // Timed out -> should have rolled back
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Thread-scoped defaults (synchronous path).
     */

    @Test
    fun `withThreadDefaults overrides global for sync transaction`(): Unit = runBlocking {
        setGlobalTransactionOptions(timeoutSeconds = 5) // Relaxed global
        assertThrows<TransactionTimedOutException> {
            withTransactionOptionsBlocking(timeoutSeconds = 1) {
                transactionBlocking {
                    orm.removeAll<Visit>()
                    Thread.sleep(1500)
                }
            }
        }
        // Rolled back
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `withThreadDefaults is cleared after block`() = runBlocking {
        // Inside block -> short timeout
        withTransactionOptionsBlocking(timeoutSeconds = 1) {
            assertThrows<TransactionTimedOutException> {
                transactionBlocking {
                    Thread.sleep(1500)
                }
            }
        }
        // Outside block -> no timeout default (uses global which we reset in @AfterEach)
        transactionBlocking {
            // Should not throw
            Thread.sleep(100)
        }
    }

    /**
     * Explicit args override defaults.
     */

    @Test
    fun `explicit transaction args override thread defaults`(): Unit = runBlocking {
        withTransactionOptionsBlocking(timeoutSeconds = 5) {
            // Explicit 1s should win and time out
            assertThrows<TransactionTimedOutException> {
                transactionBlocking(timeoutSeconds = 1) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    /**
     * Sanity: defaults don't force read-only etc. (smoke).
     */

    @Test
    fun `global isolation default does not block writes (smoke)`(): Unit = runBlocking {
        setGlobalTransactionOptions(isolation = READ_COMMITTED)
        transactionBlocking {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }
}
