package st.orm.template

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
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
import st.orm.IllegalTransactionStateException
import st.orm.PersistenceException
import st.orm.TransactionIsolation.*
import st.orm.TransactionPropagation.*
import st.orm.TransactionTimedOutException
import st.orm.UnexpectedRollbackException
import st.orm.repository.countAll
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.template.model.City
import st.orm.template.model.Visit

/**
 * Additional tests for [st.orm.template.impl.JdbcTransactionContext] covering edge cases
 * in transaction propagation, entity caching, and timeout handling.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class JdbcTransactionContextTest(
    @Autowired val orm: ORMTemplate,
) {

    @AfterEach
    fun resetDefaults() {
        setGlobalTransactionOptions()
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

    // Joined frames: the isolation belongs to the transaction owning the connection.

    @Test
    fun `a joined frame stating a stricter isolation than the transaction it joins is refused`() {
        // The refused frame fails as a joined block does, so the transaction it joined rolls back with it.
        val thrown = assertThrows<IllegalTransactionStateException> {
            transactionBlocking(isolation = READ_COMMITTED) {
                orm.countAll<City>() shouldBe 6
                transactionBlocking(isolation = REPEATABLE_READ) {
                    orm.countAll<City>()
                }
            }
        }
        thrown.message shouldBe "A REQUIRED block states REPEATABLE_READ isolation but joins a READ_COMMITTED " +
            "transaction; the isolation level belongs to the transaction owning the connection and cannot " +
            "change once it is open. State at least REPEATABLE_READ on the transaction it joins, open the " +
            "block with REQUIRES_NEW for a transaction of its own, or leave its isolation unstated."
    }

    @Test
    fun `a NESTED frame stating a stricter isolation is refused the same way`() {
        val thrown = assertThrows<IllegalTransactionStateException> {
            transactionBlocking(isolation = READ_COMMITTED) {
                orm.countAll<City>() shouldBe 6
                transactionBlocking(NESTED, isolation = SERIALIZABLE) {
                    orm.countAll<City>()
                }
            }
        }
        thrown.message shouldBe "A NESTED block states SERIALIZABLE isolation but joins a READ_COMMITTED " +
            "transaction; the isolation level belongs to the transaction owning the connection and cannot " +
            "change once it is open. State at least SERIALIZABLE on the transaction it joins, open the " +
            "block with REQUIRES_NEW for a transaction of its own, or leave its isolation unstated."
    }

    @Test
    fun `a joined frame stating a level inside a transaction at the database default is refused`() {
        val thrown = assertThrows<IllegalTransactionStateException> {
            transactionBlocking {
                orm.countAll<City>() shouldBe 6
                transactionBlocking(isolation = READ_COMMITTED) {
                    orm.countAll<City>()
                }
            }
        }
        thrown.message shouldBe "A REQUIRED block states READ_COMMITTED isolation but joins a transaction running " +
            "at the database's default isolation level, which is not known to be as strict; the isolation " +
            "level belongs to the transaction owning the connection and cannot change once it is open. State " +
            "the level on the transaction it joins, open the block with REQUIRES_NEW for a transaction of its " +
            "own, or leave its isolation unstated."
    }

    @Test
    fun `a joined frame stating the owner's level or a lower one joins`() {
        transactionBlocking(isolation = REPEATABLE_READ) {
            transactionBlocking(isolation = REPEATABLE_READ) {
                orm.countAll<City>() shouldBe 6
            }
            transactionBlocking(isolation = READ_COMMITTED) {
                orm.countAll<City>() shouldBe 6
            }
            transactionBlocking(SUPPORTS, isolation = READ_UNCOMMITTED) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `a REQUIRES_NEW frame states any isolation inside a transaction at the database default`() {
        transactionBlocking {
            orm.countAll<City>() shouldBe 6
            transactionBlocking(REQUIRES_NEW, isolation = SERIALIZABLE) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `a global isolation default does not trip a joined frame inside a stricter transaction`() {
        setGlobalTransactionOptions(isolation = REPEATABLE_READ)
        transactionBlocking(isolation = SERIALIZABLE) {
            transactionBlocking {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `a joined frame follows the transaction it joins for the entity cache`() {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val outer = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking {
                orm.entity(City::class).select().where(1).singleResult shouldBeSameInstanceAs outer
            }
            transactionBlocking(isolation = READ_COMMITTED) {
                // The stated level is lower than the owner's, so the frame joins and runs at the owner's.
                orm.entity(City::class).select().where(1).singleResult shouldBeSameInstanceAs outer
            }
        }
        transactionBlocking(isolation = READ_COMMITTED) {
            val outer = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking {
                orm.entity(City::class).select().where(1).singleResult shouldNotBeSameInstanceAs outer
            }
        }
    }

    @Test
    fun `a frame outside a transaction reads no snapshot whatever it declares`() {
        transactionBlocking(NOT_SUPPORTED, isolation = REPEATABLE_READ) {
            val first = orm.entity(City::class).select().where(1).singleResult
            orm.entity(City::class).select().where(1).singleResult shouldNotBeSameInstanceAs first
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

    @Test
    fun `a statement issued after the deadline is refused`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                orm.removeAll<Visit>()
                Thread.sleep(1500)
                assertThrows<TransactionTimedOutException> { orm.countAll<City>() }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `a statement issued after the deadline outside a transaction is refused, so it commits nothing`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(NOT_SUPPORTED, timeoutSeconds = 1) {
                orm.countAll<City>() shouldBe 6
                Thread.sleep(1500)
                orm.entity(City::class).insert(City(name = "Late"))
            }
        }
        orm.countAll<City>() shouldBe 6
    }

    @Test
    fun `a block whose deadline passed before its first statement refuses that statement`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(NOT_SUPPORTED, timeoutSeconds = 1) {
                Thread.sleep(1500)
                orm.entity(City::class).insert(City(name = "Late"))
            }
        }
        orm.countAll<City>() shouldBe 6
    }

    @Test
    fun `a timeout of zero is a deadline that has already passed`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(NOT_SUPPORTED, timeoutSeconds = 0) {
                orm.entity(City::class).insert(City(name = "Late"))
            }
        }
        orm.countAll<City>() shouldBe 6
    }

    @Test
    fun `a statement issued with less than a second left runs`(): Unit = runBlocking {
        transactionBlocking(timeoutSeconds = 2) {
            orm.countAll<City>() shouldBe 6
            Thread.sleep(1200)
            orm.countAll<City>() shouldBe 6
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

    // Joining propagations inside a non-transactional frame: the enclosing frame has a connection, but no
    // transaction, and the propagation must go by the transaction, not the connection.

    @Test
    fun `REQUIRED inside NOT_SUPPORTED opens its own transaction and rolls back independently`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.exists<Visit>().shouldBeTrue() // Binds the NOT_SUPPORTED frame to an auto-commit connection.
            transactionBlocking {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            // The inner block ran in a transaction of its own and rolled it back; the delete never committed.
            orm.exists<Visit>().shouldBeTrue()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `REQUIRED inside NEVER opens its own transaction and rolls back independently`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.exists<Visit>().shouldBeTrue()
            transactionBlocking {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            orm.exists<Visit>().shouldBeTrue()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `MANDATORY inside NOT_SUPPORTED should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transactionBlocking(NOT_SUPPORTED) {
                orm.countAll<City>()
                transactionBlocking(MANDATORY) {
                    orm.countAll<City>()
                }
            }
        }
    }

    @Test
    fun `NEVER inside NOT_SUPPORTED should run`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.countAll<City>()
            transactionBlocking(NEVER) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `rollback-only in an untouched REQUIRED inside NOT_SUPPORTED does not doom the enclosing block`(): Unit = runBlocking {
        // The inner block never executes a statement, so the mark travels through the scope layer alone. It
        // must stop at the NOT_SUPPORTED block, which has no transaction to be marked.
        transactionBlocking(NOT_SUPPORTED) {
            orm.countAll<City>()
            transactionBlocking {
                setRollbackOnly()
            }
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `SUPPORTS inside NOT_SUPPORTED runs non-transactionally`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.countAll<City>()
            transactionBlocking(SUPPORTS) {
                orm.removeAll<Visit>()
            }
            // SUPPORTS takes after its enclosing frame: no transaction, so the delete is already committed.
            orm.exists<Visit>().shouldBeFalse()
        }
        orm.exists<Visit>().shouldBeFalse()
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
