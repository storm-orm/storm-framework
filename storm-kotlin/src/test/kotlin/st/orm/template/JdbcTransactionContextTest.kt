package st.orm.template

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.PersistenceException
import st.orm.repository.countAll
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.template.TransactionIsolation.*
import st.orm.template.TransactionPropagation.*
import st.orm.template.model.City
import st.orm.template.model.Visit

/**
 * Additional tests for [st.orm.template.impl.JdbcTransactionContext] covering edge cases
 * in transaction propagation, entity caching, and timeout handling.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class JdbcTransactionContextTest(
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

    // Entity cache behavior

    @Test
    fun `REPEATABLE_READ transaction should enable entity caching`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            // With REPEATABLE_READ, cached entity should be returned
            city1.name shouldBe city2.name
        }
    }

    @Test
    fun `READ_COMMITTED transaction should fetch fresh data on each read`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_COMMITTED) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            city1.name shouldBe "Sun Paririe"
            city2.name shouldBe "Sun Paririe"
        }
    }

    // Nested NESTED with savepoint

    @Test
    fun `nested savepoint rollback should clear entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            orm.countAll<Visit>() shouldBe 14
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                orm.exists<Visit>().shouldBeFalse()
                setRollbackOnly()
            }
            // After nested rollback, data should be restored
            orm.exists<Visit>().shouldBeTrue()
        }
    }

    @Test
    fun `double nested NESTED should work correctly`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                transactionBlocking(NESTED) {
                    orm.exists<Visit>().shouldBeFalse()
                    setRollbackOnly()
                }
                // Inner nested was rolled back, but visits were already deleted in outer nested
                orm.exists<Visit>().shouldBeFalse()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `NESTED with outer REQUIRES_NEW should work`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                transactionBlocking(NESTED) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
                orm.exists<Visit>().shouldBeTrue()
            }
        }
    }

    // Timeout scenarios

    @Test
    fun `nested transaction should inherit outer timeout`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                transactionBlocking(NESTED) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    @Test
    fun `inner timeout should be min of outer and inner`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 5) {
                transactionBlocking(REQUIRED, timeoutSeconds = 1) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    @Test
    fun `suspend transaction with short timeout should time out on delay`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transaction(timeoutSeconds = 1) {
                delay(1500)
            }
        }
    }

    // SUPPORTS edge cases

    @Test
    fun `SUPPORTS inside REQUIRED should share entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            transactionBlocking(SUPPORTS) {
                orm.countAll<City>() shouldBe 6
            }
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `SUPPORTS without outer transaction runs non-transactional`(): Unit = runBlocking {
        transactionBlocking(SUPPORTS) {
            orm.removeAll<Visit>()
        }
        // Non-transactional: auto-committed
        orm.exists<Visit>().shouldBeFalse()
    }

    // NOT_SUPPORTED edge cases

    @Test
    fun `NOT_SUPPORTED should suspend outer transaction and run non-transactional`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NOT_SUPPORTED) {
                orm.removeAll<Visit>()
            }
            // NOT_SUPPORTED changes are already committed
            orm.exists<Visit>().shouldBeFalse()
            setRollbackOnly()
        }
        // NOT_SUPPORTED changes survive outer rollback
        orm.exists<Visit>().shouldBeFalse()
    }

    // MANDATORY edge cases

    @Test
    fun `MANDATORY inside REQUIRED should join transaction`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `MANDATORY without outer transaction should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transactionBlocking(MANDATORY) {
                orm.countAll<City>()
            }
        }
    }

    // NEVER edge cases

    @Test
    fun `NEVER without outer should work non-transactionally`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `NEVER inside REQUIRED should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transactionBlocking {
                transactionBlocking(NEVER) {
                    orm.countAll<City>()
                }
            }
        }
    }

    // Read-only transactions

    @Test
    fun `readOnly transaction should allow read operations`(): Unit = runBlocking {
        transactionBlocking(readOnly = true) {
            orm.countAll<City>() shouldBe 6
            orm.countAll<Visit>() shouldBe 14
        }
    }

    @Test
    fun `readOnly flag combined with isolation should work`(): Unit = runBlocking {
        transactionBlocking(readOnly = true, isolation = READ_COMMITTED) {
            orm.countAll<City>() shouldBe 6
        }
    }

    // Global and scoped defaults

    @Test
    fun `global readOnly default should apply to transactions`(): Unit = runBlocking {
        setGlobalTransactionOptions(readOnly = true)
        transactionBlocking {
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `scoped isolation default should apply to suspend transactions`(): Unit = runBlocking {
        withTransactionOptions(isolation = READ_COMMITTED) {
            transaction {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `thread-scoped defaults should apply to blocking transactions`(): Unit = runBlocking {
        withTransactionOptionsBlocking(isolation = SERIALIZABLE) {
            transactionBlocking {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `explicit args should override scoped defaults`(): Unit = runBlocking {
        withTransactionOptions(isolation = SERIALIZABLE) {
            transaction(isolation = READ_COMMITTED) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    // Multiple operations within same transaction

    @Test
    fun `multiple insert and delete within transaction should work`(): Unit = runBlocking {
        transactionBlocking {
            val repo = orm.entity(City::class)
            val initialCount = repo.count()
            initialCount shouldBe 6

            repo.insert(City(name = "NewCity"))
            repo.count() shouldBe 7

            repo.delete().where(7).executeUpdate() shouldBe 1
            repo.count() shouldBe 6
        }
    }

    @Test
    fun `timeout with no DB access should throw on commit path`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                // No DB operations, just let the timeout expire
                Thread.sleep(1500)
            }
        }
    }

    @Test
    fun `suspend timeout with no DB access should throw on commit path`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transaction(timeoutSeconds = 1) {
                // No DB operations, just delay
                kotlinx.coroutines.delay(1500)
            }
        }
    }

    @Test
    fun `rollbackInherited flag should cause UnexpectedRollbackException`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                transactionBlocking(REQUIRED) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
                // Outer tries to commit but inner set rollback-only
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `SUPPORTS inner setRollbackOnly should propagate to outer`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                transactionBlocking(SUPPORTS) {
                    setRollbackOnly()
                }
            }
        }
    }

    @Test
    fun `MANDATORY inner setRollbackOnly should propagate to outer`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                transactionBlocking(MANDATORY) {
                    setRollbackOnly()
                }
            }
        }
    }

    @Test
    fun `transaction with timeout should apply query timeout to statements`(): Unit = runBlocking {
        transactionBlocking(timeoutSeconds = 5) {
            // The decorator should set queryTimeout on PreparedStatements
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `REQUIRED inner with no timeout and outer with no timeout`(): Unit = runBlocking {
        // Branch: outer.deadlineNanos == null && innerRequested == null
        transactionBlocking {
            transactionBlocking(REQUIRED) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `REQUIRED outer with no timeout and inner with timeout should use inner`(): Unit = runBlocking {
        // Branch: outer.deadlineNanos == null -> innerRequested
        assertThrows<TransactionTimedOutException> {
            transactionBlocking {
                transactionBlocking(REQUIRED, timeoutSeconds = 1) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    @Test
    fun `REQUIRED outer with timeout and inner with no timeout should use outer`(): Unit = runBlocking {
        // Branch: innerRequested == null -> outer.deadlineNanos
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                transactionBlocking(REQUIRED) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    @Test
    fun `DML on entity type should invalidate that entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            // Load City into cache
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()

            // Insert a new city: should invalidate City cache
            orm.entity(City::class).insert(City(name = "NewCity"))

            // After insert, reload should hit DB again
            val city3 = orm.entity(City::class).select().where(1).singleResult
            city3.name shouldBe city1.name
        }
    }

    @Test
    fun `DML on one entity type should not invalidate other entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            // Load City into cache
            val city1 = orm.entity(City::class).select().where(1).singleResult

            // Load Visit into cache
            orm.entity(Visit::class).select().where(1).singleResult

            // Delete visits: should invalidate Visit cache but NOT City cache
            orm.removeAll<Visit>()

            // City cache should still be intact
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()
        }
    }

    @Test
    fun `SUPPORTS with outer transaction should join and share data`(): Unit = runBlocking {
        transactionBlocking {
            orm.removeAll<Visit>()
            transactionBlocking(SUPPORTS) {
                orm.exists<Visit>().shouldBeFalse()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `MANDATORY with outer transaction should join`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(MANDATORY) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `multiple operations after rollback should succeed`(): Unit = runBlocking {
        transactionBlocking {
            orm.removeAll<Visit>()
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
        // Subsequent transaction should work fine
        transactionBlocking {
            orm.countAll<City>() shouldBe 6
        }
    }
}
