/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.template

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import st.orm.IllegalTransactionStateException
import st.orm.OptimisticLockException
import st.orm.PersistenceException
import st.orm.TransactionPropagation
import st.orm.TransactionPropagation.NOT_SUPPORTED
import st.orm.TransactionPropagation.REQUIRED
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.TransientPersistenceException
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger

/**
 * The database function behind `CONFLICT()`: fails with a serialization failure while conflicts remain.
 */
public object RetryConflicts {
    internal val remaining = AtomicInteger()

    @JvmStatic
    public fun conflict(): Int {
        if (remaining.getAndDecrement() > 0) {
            throw SQLException("Simulated serialization failure.", "40001", 40001)
        }
        return 0
    }
}

/**
 * Tests the retry count on [transaction] and [transactionBlocking]: an attempt that fails transiently runs again in a
 * transaction of its own, any other failure leaves the block at once, and a block that does not start its own
 * transaction refuses the count.
 */
internal class TransactionRetriesTest {

    private lateinit var dataSource: DriverManagerDataSource
    private lateinit var orm: ORMTemplate

    @BeforeEach
    fun createDatabase() {
        RetryConflicts.remaining.set(0)
        dataSource = DriverManagerDataSource("jdbc:h2:mem:kotlin-retries${databases.incrementAndGet()};DB_CLOSE_DELAY=-1", "sa", "")
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
                statement.execute("INSERT INTO counter (id, amount) VALUES (1, 0)")
                statement.execute("CREATE ALIAS CONFLICT FOR \"${RetryConflicts::class.java.name}.conflict\"")
            }
        }
        orm = ORMTemplate.of(dataSource)
    }

    @AfterEach
    fun dropDatabase() {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("SHUTDOWN") } }
    }

    private fun increment() {
        orm.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate()
    }

    private fun conflictPoint(template: ORMTemplate = orm) {
        template.query("SELECT CONFLICT()").resultList
    }

    private fun amount(): Int = (orm.query("SELECT amount FROM counter WHERE id = 1").singleResult[0] as Number).toInt()

    @Test
    fun `a transient failure reruns a suspending block in a transaction of its own`(): Unit = runBlocking {
        RetryConflicts.remaining.set(1)
        var attempts = 0
        var commits = 0
        var rollbacks = 0
        val completions = mutableListOf<Boolean>()
        val result = transaction(retries = 2) {
            attempts++
            onCommit { commits++ }
            onRollback { rollbacks++ }
            onCompletion { committed -> completions += committed }
            increment()
            conflictPoint()
            "done"
        }
        result shouldBe "done"
        attempts shouldBe 2
        commits shouldBe 1
        rollbacks shouldBe 1
        completions shouldContainExactly listOf(false, true)
        amount() shouldBe 1
    }

    @Test
    fun `a transient failure reruns a blocking block in a transaction of its own`() {
        RetryConflicts.remaining.set(1)
        var attempts = 0
        var commits = 0
        var rollbacks = 0
        val result = transactionBlocking(retries = 2) {
            attempts++
            onCommit { commits++ }
            onRollback { rollbacks++ }
            increment()
            conflictPoint()
            "done"
        }
        result shouldBe "done"
        attempts shouldBe 2
        commits shouldBe 1
        rollbacks shouldBe 1
        amount() shouldBe 1
    }

    @Test
    fun `exhausted retries throw the last failure with the earlier ones suppressed`(): Unit = runBlocking {
        RetryConflicts.remaining.set(10)
        var attempts = 0
        val thrown = assertThrows<TransientPersistenceException> {
            transaction(retries = 2) {
                attempts++
                conflictPoint()
            }
        }
        attempts shouldBe 3
        thrown.suppressed.filterIsInstance<TransientPersistenceException>().size shouldBe 2
        val blocking = assertThrows<TransientPersistenceException> {
            transactionBlocking(retries = 1) { conflictPoint() }
        }
        blocking.suppressed.filterIsInstance<TransientPersistenceException>().size shouldBe 1
    }

    @Test
    fun `a failure that is not transient is not rerun`(): Unit = runBlocking {
        var attempts = 0
        assertThrows<IllegalStateException> {
            transaction(retries = 2) {
                attempts++
                increment()
                throw IllegalStateException("Not transient.")
            }
        }
        attempts shouldBe 1
        val thrown = assertThrows<PersistenceException> {
            transaction(retries = 2) {
                attempts++
                orm.query("INSERT INTO counter (id, amount) VALUES (1, 0)").executeUpdate()
            }
        }
        (thrown is TransientPersistenceException) shouldBe false
        attempts shouldBe 2
        amount() shouldBe 0
    }

    @Test
    fun `a transient persistence exception thrown by the block reruns`(): Unit = runBlocking {
        var attempts = 0
        val result = transaction(retries = 1) {
            if (++attempts == 1) throw OptimisticLockException("Simulated conflict.")
            attempts
        }
        result shouldBe 2
    }

    private class RetryableFailure(cause: Throwable) : RuntimeException(cause)

    @Test
    fun `a transient failure a mapper translates to its own type still reruns`(): Unit = runBlocking {
        val mapped = ORMTemplate.builder(dataSource)
            .exceptionMapper { failure, context -> if (failure is TransientPersistenceException) RetryableFailure(failure) else null }
            .build()
        RetryConflicts.remaining.set(1)
        var attempts = 0
        transaction(retries = 1) {
            attempts++
            conflictPoint(mapped)
        }
        attempts shouldBe 2
        RetryConflicts.remaining.set(1)
        transactionBlocking(retries = 1) {
            attempts++
            conflictPoint(mapped)
        }
        attempts shouldBe 4
    }

    @Test
    fun `a block that starts its own transaction inside another reruns`(): Unit = runBlocking {
        RetryConflicts.remaining.set(1)
        var attempts = 0
        transaction {
            increment()
            transaction(propagation = REQUIRES_NEW, retries = 1) {
                attempts++
                conflictPoint()
            }
        }
        attempts shouldBe 2
        RetryConflicts.remaining.set(1)
        transaction(propagation = NOT_SUPPORTED) {
            transaction(propagation = REQUIRED, retries = 1) {
                attempts++
                conflictPoint()
            }
        }
        attempts shouldBe 4
        amount() shouldBe 1
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation::class, names = ["REQUIRED", "NESTED", "MANDATORY", "SUPPORTS", "NOT_SUPPORTED", "NEVER"])
    fun `a block that joins or runs without a transaction inside one refuses retries`(propagation: TransactionPropagation): Unit = runBlocking {
        var attempts = 0
        transaction {
            assertThrows<IllegalTransactionStateException> {
                transaction(propagation = propagation, retries = 1) { attempts++ }
            }
            assertThrows<IllegalTransactionStateException> {
                transactionBlocking(propagation = propagation, retries = 1) { attempts++ }
            }
        }
        attempts shouldBe 0
    }

    @ParameterizedTest
    @EnumSource(value = TransactionPropagation::class, names = ["MANDATORY", "SUPPORTS", "NOT_SUPPORTED", "NEVER"])
    fun `a block that runs without a transaction refuses retries`(propagation: TransactionPropagation): Unit = runBlocking {
        var attempts = 0
        assertThrows<IllegalTransactionStateException> {
            transaction(propagation = propagation, retries = 1) { attempts++ }
        }
        assertThrows<IllegalTransactionStateException> {
            transactionBlocking(propagation = propagation, retries = 1) { attempts++ }
        }
        attempts shouldBe 0
    }

    @Test
    fun `negative retries are refused`(): Unit = runBlocking {
        assertThrows<IllegalArgumentException> { transaction(retries = -1) { } }
        assertThrows<IllegalArgumentException> { transactionBlocking(retries = -1) { } }
    }

    @Test
    fun `a cancellation during the pause ends the reruns`(): Unit = runBlocking {
        RetryConflicts.remaining.set(10)
        var attempts = 0
        lateinit var self: Deferred<Unit>
        self = async(start = CoroutineStart.LAZY) {
            transaction(retries = 5) {
                attempts++
                onRollback { self.cancel() }
                conflictPoint()
            }
        }
        assertThrows<CancellationException> { self.await() }
        attempts shouldBe 1
    }

    @Test
    fun `a cancellation during an attempt is not rerun`(): Unit = runBlocking {
        RetryConflicts.remaining.set(10)
        var attempts = 0
        lateinit var self: Deferred<Unit>
        self = async(start = CoroutineStart.LAZY) {
            transaction(retries = 5) {
                attempts++
                self.cancel()
                conflictPoint()
            }
        }
        assertThrows<CancellationException> { self.await() }
        attempts shouldBe 1
        lateinit var nested: Deferred<Unit>
        nested = async(start = CoroutineStart.LAZY) {
            transaction(propagation = NOT_SUPPORTED) {
                transaction(propagation = REQUIRES_NEW, retries = 5) {
                    attempts++
                    nested.cancel()
                    conflictPoint()
                }
            }
        }
        assertThrows<CancellationException> { nested.await() }
        attempts shouldBe 2
    }

    @Test
    fun `a cancellation the block throws is not rerun, whatever its cause`(): Unit = runBlocking {
        var attempts = 0
        assertThrows<CancellationException> {
            transaction(retries = 2) {
                attempts++
                throw CancellationException("Stopped.", OptimisticLockException("Simulated conflict."))
            }
        }
        attempts shouldBe 1
    }

    @Test
    fun `a suspending block that joins a blocking one commits with it`() {
        val events = mutableListOf<String>()
        transactionBlocking {
            runBlocking {
                transaction {
                    onCommit { events += "commit" }
                    increment()
                }
            }
        }
        events shouldContainExactly listOf("commit")
        amount() shouldBe 1
    }

    private companion object {
        val databases = AtomicInteger()
    }
}
