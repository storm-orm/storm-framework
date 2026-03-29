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
import st.orm.spring.model.City
import st.orm.spring.model.Visit
import st.orm.template.*
import st.orm.template.TransactionIsolation.*
import st.orm.template.TransactionPropagation.*

/**
 * Tests for [st.orm.spring.impl.SpringTransactionContext] covering entity caching,
 * isRepeatableRead, getDecorator timeout behavior, nested cache sharing/isolation,
 * and timeout edge cases.
 */
@ContextConfiguration(classes = [IntegrationConfig::class])
@EnableTransactionIntegration
@SpringBootTest
@Sql("/data.sql")
open class SpringTransactionContextTest(
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

    // isRepeatableRead behavior

    @Test
    fun `REPEATABLE_READ transaction should enable entity caching`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            // With REPEATABLE_READ, cached entity should be the same identity
            (city1 === city2).shouldBeTrue()
        }
    }

    @Test
    fun `SERIALIZABLE transaction should enable entity caching`(): Unit = runBlocking {
        transactionBlocking(isolation = SERIALIZABLE) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            // SERIALIZABLE >= REPEATABLE_READ, so caching should be active
            (city1 === city2).shouldBeTrue()
        }
    }

    @Test
    fun `READ_COMMITTED transaction should not cache entities`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_COMMITTED) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            city1.name shouldBe city2.name
            // With READ_COMMITTED, new objects should be created
            (city1 === city2).shouldBeFalse()
        }
    }

    @Test
    fun `READ_UNCOMMITTED transaction should not cache entities`(): Unit = runBlocking {
        transactionBlocking(isolation = READ_UNCOMMITTED) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            city1.name shouldBe city2.name
            (city1 === city2).shouldBeFalse()
        }
    }

    @Test
    fun `default isolation transaction should not cache entities`(): Unit = runBlocking {
        // Default isolation (no explicit isolation) maps to ISOLATION_DEFAULT (-1) in Spring,
        // which should not enable caching since most DBs default to READ_COMMITTED.
        transactionBlocking {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            city1.name shouldBe city2.name
            (city1 === city2).shouldBeFalse()
        }
    }

    // Entity cache sharing and isolation across propagation modes

    @Test
    fun `REQUIRED inner with same isolation should share entity cache with outer`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(REQUIRED, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                // REQUIRED with REPEATABLE_READ shares the outer cache and uses it
                (city1 === city2).shouldBeTrue()
            }
        }
    }

    @Test
    fun `SUPPORTS inner with same isolation should share entity cache with outer`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(SUPPORTS, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                // SUPPORTS with REPEATABLE_READ joins and shares cache
                (city1 === city2).shouldBeTrue()
            }
        }
    }

    @Test
    fun `MANDATORY inner with same isolation should share entity cache with outer`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(MANDATORY, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                // MANDATORY with REPEATABLE_READ joins and shares cache
                (city1 === city2).shouldBeTrue()
            }
        }
    }

    @Test
    fun `NESTED inner with same isolation should share entity cache with outer`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(NESTED, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                // NESTED with REPEATABLE_READ shares the outer cache (same physical transaction)
                (city1 === city2).shouldBeTrue()
            }
        }
    }

    @Test
    fun `REQUIRES_NEW inner should have its own entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(REQUIRES_NEW, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                // REQUIRES_NEW uses a separate cache
                (city1 === city2).shouldBeFalse()
                city1.name shouldBe city2.name
            }
        }
    }

    @Test
    fun `NOT_SUPPORTED inner should have its own entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(NOT_SUPPORTED) {
                // NOT_SUPPORTED runs non-transactionally with its own cache
                orm.countAll<City>() shouldBe 6
            }
            // Outer should still have its cache intact
            val city3 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city3).shouldBeTrue()
        }
    }

    // NESTED rollback clears outer entity cache

    @Test
    fun `NESTED rollback should clear outer entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            // After nested rollback, entity cache should have been cleared
            val city2 = orm.entity(City::class).select().where(1).singleResult
            // city2 is a new object because the cache was cleared
            (city1 === city2).shouldBeFalse()
            city1.name shouldBe city2.name
        }
    }

    @Test
    fun `NESTED commit should preserve outer entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(NESTED) {
                // Just read, no rollback
                orm.countAll<City>() shouldBe 6
            }
            // After nested commit, entity cache should still work
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()
        }
    }

    // clearAllEntityCaches

    @Test
    fun `NESTED rollback with DB access should invalidate outer entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            // NESTED rollback with DB access forces transaction start, so rollback
            // actually clears the entity cache.
            transactionBlocking(NESTED) {
                orm.countAll<City>() shouldBe 6
                setRollbackOnly()
            }
            // After cache clear, a new object should be returned
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeFalse()
            city1.name shouldBe city2.name
        }
    }

    // Timeout edge cases for Spring transaction context

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
    fun `inner timeout should be minimum of outer and inner`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 5) {
                transactionBlocking(REQUIRED, timeoutSeconds = 1) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    @Test
    fun `REQUIRES_NEW with timeout should work independently`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 5) {
                transactionBlocking(REQUIRES_NEW, timeoutSeconds = 1) {
                    Thread.sleep(1500)
                }
            }
        }
    }

    @Test
    fun `timeout in commit path with no DB access should throw`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                Thread.sleep(1500)
                // No DB access: transaction status was never started, but deadline expired
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
    fun `readOnly combined with isolation should work`(): Unit = runBlocking {
        transactionBlocking(readOnly = true, isolation = READ_COMMITTED) {
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `readOnly combined with REPEATABLE_READ should enable caching`(): Unit = runBlocking {
        transactionBlocking(readOnly = true, isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()
        }
    }

    // Multiple operations in transaction

    @Test
    fun `multiple insert and delete within spring transaction should work`(): Unit = runBlocking {
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

    // Entity cache isolation across double nested

    @Test
    fun `double nested NESTED should share and clear cache correctly`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(NESTED, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                (city1 === city2).shouldBeTrue()
                transactionBlocking(NESTED, isolation = REPEATABLE_READ) {
                    // Must perform DB access to actually start the transaction
                    orm.countAll<City>() shouldBe 6
                    setRollbackOnly()
                }
                // Inner nested rollback cleared the shared cache
                val city3 = orm.entity(City::class).select().where(1).singleResult
                (city1 === city3).shouldBeFalse()
            }
        }
    }

    @Test
    fun `NESTED with REQUIRES_NEW outer should work correctly`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                transactionBlocking(NESTED) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
                // Nested rolled back, visits should still exist
                orm.exists<Visit>().shouldBeTrue()
            }
        }
    }

    // setRollbackOnly before any DB access

    @Test
    fun `setRollbackOnly before DB access should still rollback`(): Unit = runBlocking {
        transactionBlocking {
            setRollbackOnly()
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    // Global and scoped defaults with Spring integration

    @Test
    fun `global readOnly default should apply`(): Unit = runBlocking {
        setGlobalTransactionOptions(readOnly = true)
        transactionBlocking {
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `thread-scoped isolation default should apply`(): Unit = runBlocking {
        withTransactionOptionsBlocking(isolation = SERIALIZABLE) {
            transactionBlocking {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `explicit args should override scoped defaults`(): Unit = runBlocking {
        withTransactionOptionsBlocking(isolation = SERIALIZABLE) {
            transactionBlocking(isolation = READ_COMMITTED) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `timeout with no DB access in rollback path should throw`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                Thread.sleep(1500)
                setRollbackOnly()
            }
        }
    }

    @Test
    fun `setRollbackOnly before any DB access then access should rollback`(): Unit = runBlocking {
        transactionBlocking {
            setRollbackOnly()
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `global timeout should apply to transactions`(): Unit = runBlocking {
        setGlobalTransactionOptions(timeoutSeconds = 1)
        assertThrows<TransactionTimedOutException> {
            transactionBlocking {
                Thread.sleep(1500)
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
    fun `explicit args should override thread-scoped defaults`(): Unit = runBlocking {
        withTransactionOptionsBlocking(isolation = SERIALIZABLE) {
            transactionBlocking(isolation = READ_COMMITTED) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `NEVER propagation should work without outer transaction`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.countAll<City>() shouldBe 6
        }
    }

    @Test
    fun `MANDATORY should throw when no outer transaction exists`(): Unit = runBlocking {
        assertThrows<Exception> {
            transactionBlocking(MANDATORY) {
                orm.countAll<City>()
            }
        }
    }

    @Test
    fun `setRollbackOnly in REQUIRED inner should cause outer commit to throw`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                transactionBlocking(REQUIRED) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
                // Outer tries to commit but inner marked rollback-only
            }
        }
        // Visits should still exist since rollback occurred
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `REQUIRES_NEW with readOnly inside writable outer`(): Unit = runBlocking {
        transactionBlocking {
            orm.removeAll<Visit>()
            transactionBlocking(REQUIRES_NEW, readOnly = true) {
                orm.countAll<City>() shouldBe 6
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }
}
