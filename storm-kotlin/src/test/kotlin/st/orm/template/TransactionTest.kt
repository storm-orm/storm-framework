package st.orm.template

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.*
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.PersistenceException
import st.orm.TransactionIsolation.*
import st.orm.TransactionPropagation.*
import st.orm.TransactionTimedOutException
import st.orm.UnexpectedRollbackException
import st.orm.core.template.impl.SlowStatementLog
import st.orm.repository.countAll
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.template.model.Visit
import java.time.Duration
import java.util.concurrent.CompletableFuture

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class TransactionTest(
    @Autowired val orm: ORMTemplate,
) {

    @AfterEach
    fun resetDefaults() {
        // Restore baseline defaults: REQUIRED, isolation=null, timeout=null, readOnly=null.
        setGlobalTransactionOptions()
    }

    /**
     * Single-layer scenarios (default REQUIRED propagation)
     */

    @Test
    fun `modification visible after required commit`(): Unit = runBlocking {
        // data.sql inserts 14 visits. After a committed deleteAll, none should remain.
        transactionBlocking {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `modification rolled back after required rollback`(): Unit = runBlocking {
        // Marking rollback-only after deleteAll should restore the 14 visits.
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

    /**
     * Single-layer tests with various isolation levels.
     */

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
    fun `dirty read allowed with READ_UNCOMMITTED isolation`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sawDirty = CompletableDeferred<Boolean>()
        // Transaction 1: delete then mark rollback-only after a delay
        launch {
            transaction {
                orm.removeAll<Visit>()
                started.complete(Unit)
                delay(500)
                setRollbackOnly()
            }
        }
        // Transaction 2: read while Transaction 1 is still active
        launch {
            started.await()
            transaction(isolation = READ_UNCOMMITTED) {
                sawDirty.complete(orm.exists<Visit>())
            }
        }
        sawDirty.await().shouldBeFalse()
    }

    @Test
    fun `non repeatable read allowed with READ_COMMITTED isolation`(): Unit = runBlocking {
        val first = CompletableDeferred<Long>()
        val latch1 = CompletableDeferred<Unit>()
        val latch2 = CompletableDeferred<Unit>()
        // Transaction 1: read, wait, read again.
        launch {
            transaction(isolation = READ_COMMITTED) {
                first.complete(orm.countAll<Visit>())
                latch1.complete(Unit)
                latch2.await()
                val second = orm.countAll<Visit>()
                first.await().shouldNotBe(second)
            }
        }
        // Transaction 2: wait, then delete all and commit.
        launch {
            latch1.await()
            transaction {
                orm.removeAll<Visit>()
            }
            latch2.complete(Unit)
        }
    }

    @Test
    fun `dirty read should be prevented with READ_COMMITTED isolation`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sawDirty = CompletableDeferred<Boolean>()
        // Transaction 1: delete then mark rollback-only after a delay.
        launch {
            transaction {
                orm.removeAll<Visit>()
                started.complete(Unit)
                delay(500)
            }
        }
        // Transaction 2: read while Transaction 1 is still active.
        launch {
            started.await()
            transaction(isolation = READ_COMMITTED) {
                sawDirty.complete(orm.exists<Visit>())
            }
        }
        sawDirty.await().shouldBeTrue()
    }

    @Test
    fun `uncommitted removeAll should be visible with READ_UNCOMMITTED`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sawEmpty = CompletableDeferred<Boolean>()
        // Transaction1: deleteAll but delay before commit.
        launch {
            transaction {
                orm.removeAll<Visit>()
                started.complete(Unit)
                delay(500)
            }
        }
        // Transaction2: under READ_UNCOMMITTED we should see the uncommitted delete.
        launch {
            started.await()
            transaction(isolation = READ_UNCOMMITTED) {
                sawEmpty.complete(!orm.exists<Visit>())
            }
        }
        sawEmpty.await().shouldBeTrue()
    }

    @Test
    fun `uncommitted removeAll should not be visible with READ_COMMITTED`(): Unit = runBlocking {
        val started = CompletableDeferred<Unit>()
        val sawFull = CompletableDeferred<Boolean>()
        // Transaction1: deleteAll but delay before commit.
        launch {
            transaction {
                orm.removeAll<Visit>()
                started.complete(Unit)
                delay(500)
            }
        }
        // Transaction2: under READ_COMMITTED we should NOT see the uncommitted delete.
        launch {
            started.await()
            transaction(isolation = READ_COMMITTED) {
                sawFull.complete(orm.exists<Visit>())
            }
        }
        sawFull.await().shouldBeTrue()
    }

    @Test
    fun `non-repeatable read of removeAll should occur with READ_COMMITTED`(): Unit = runBlocking {
        val deleted = CompletableDeferred<Unit>()
        val firstExists = CompletableDeferred<Boolean>()
        val secondExists = CompletableDeferred<Boolean>()
        // Transaction: after a short delay, deleteAll and commit.
        launch {
            delay(500)
            transaction {
                orm.removeAll<Visit>()
            }
            deleted.complete(Unit)
        }
        // Transaction2: under READ_COMMITTED we expect a non-repeatable read of existence.
        launch {
            transaction(isolation = READ_COMMITTED) {
                firstExists.complete(orm.exists<Visit>()) // should be true
                deleted.await()
                secondExists.complete(orm.exists<Visit>()) // should be false
            }
        }
        firstExists.await().shouldBeTrue()
        secondExists.await().shouldBeFalse()
    }

    @Test
    fun `non-repeatable read of removeAll should not occur with REPEATABLE_READ`(): Unit = runBlocking {
        val deleted = CompletableDeferred<Unit>()
        val firstExists = CompletableDeferred<Boolean>()
        val secondExists = CompletableDeferred<Boolean>()
        // Transaction1: after a short delay, deleteAll and commit.
        launch {
            delay(500)
            transaction {
                orm.removeAll<Visit>()
            }
            deleted.complete(Unit)
        }
        // Transaction2: under REPEATABLE_READ we expect repeatable existence
        launch {
            transaction(isolation = REPEATABLE_READ) {
                firstExists.complete(orm.exists<Visit>()) // should be true
                deleted.await()
                secondExists.complete(orm.exists<Visit>()) // should still be true
            }
        }
        firstExists.await().shouldBeTrue()
        secondExists.await().shouldBeTrue()
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
    open fun `suspendTransaction times out and rolls back when execution exceeds delay timeout`(): Unit = runBlocking {
        assertThrows<TransactionTimedOutException> {
            transaction(timeoutSeconds = 1) {
                orm.removeAll<Visit>()
                delay(1500)
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
     * Transaction cancellation.
     */

    @Test
    fun `cancelled suspend transaction stops its running statement and rolls back`(): Unit = runBlocking {
        val started = System.nanoTime()
        // The slow log reports the stopped statement as cancelled, in one line below the warnings, not as failed.
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("st.orm.sql.slow")
        val appender = ListAppender<ILoggingEvent>()
        appender.context = context
        appender.start()
        val previousLevel = logger.level
        val previousThreshold = SlowStatementLog.threshold()
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        logger.isAdditive = false
        SlowStatementLog.threshold(Duration.ofNanos(1))
        try {
            assertThrows<TimeoutCancellationException> {
                withTimeout(500) {
                    transaction {
                        orm.removeAll<Visit>()
                        orm.query(SLOW_STATEMENT).singleResult
                    }
                }
            }
        } finally {
            SlowStatementLog.threshold(previousThreshold)
            logger.detachAppender(appender)
            logger.isAdditive = true
            logger.level = previousLevel
            appender.stop()
        }
        // The statement was stopped long before it would have completed, and the delete rolled back with it.
        (System.nanoTime() - started).shouldBeLessThan(10_000_000_000L)
        orm.exists<Visit>().shouldBeTrue()
        val cancelled = appender.list.filter { it.formattedMessage.startsWith("SQL cancelled (") }
        cancelled.size shouldBe 1
        cancelled.single().level shouldBe Level.DEBUG
        cancelled.single().formattedMessage.shouldContain(") after ")
        appender.list.none { it.formattedMessage.contains("failed (") }.shouldBeTrue()
    }

    @Test
    fun `cancelled suspend transaction refuses its next statement and rolls back`(): Unit = runBlocking {
        val deleted = CompletableDeferred<Unit>()
        val refused = CompletableDeferred<Throwable?>()
        val job = launch {
            transaction {
                orm.removeAll<Visit>()
                deleted.complete(Unit)
                // Blocked without a suspension point, so the cancellation is not observed before the next statement.
                Thread.sleep(500)
                try {
                    orm.exists<Visit>()
                    refused.complete(null)
                } catch (e: Throwable) {
                    refused.complete(e)
                    throw e
                }
            }
        }
        deleted.await()
        job.cancelAndJoin()
        refused.await().shouldBeInstanceOf<PersistenceException>().message.shouldContain("cancelled")
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `cancelled joined block dooms the outer transaction`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transaction {
                orm.removeAll<Visit>()
                try {
                    withTimeout(500) {
                        transaction {
                            orm.query(SLOW_STATEMENT).singleResult
                        }
                    }
                } catch (ignore: TimeoutCancellationException) {
                    // The joined block was cancelled; this block goes on and tries to commit.
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `stopped statement in a joined block surfaces as a cancellation inside the cancelled coroutine`(): Unit = runBlocking {
        val failure = CompletableDeferred<Throwable>()
        val job = launch {
            transaction {
                try {
                    transaction {
                        orm.query(SLOW_STATEMENT).singleResult
                    }
                } catch (e: Throwable) {
                    failure.complete(e)
                    throw e
                }
            }
        }
        delay(200)
        job.cancelAndJoin()
        // The joined block failed at the statement the cancellation stopped, which it reports as the cancellation
        // rather than as the driver's error.
        failure.await().shouldBeInstanceOf<CancellationException>()
    }

    @Test
    fun `suspend transaction cancelled through its handle fails at its statement and rolls back`(): Unit = runBlocking {
        val handle = CompletableDeferred<Transaction>()
        val started = System.nanoTime()
        val work = async {
            runCatching {
                transaction {
                    orm.removeAll<Visit>()
                    handle.complete(this)
                    orm.query(SLOW_STATEMENT).singleResult
                }
            }
        }
        handle.await()
        delay(200)
        handle.await().cancel()
        work.await().exceptionOrNull().shouldBeInstanceOf<PersistenceException>()
        (System.nanoTime() - started).shouldBeLessThan(10_000_000_000L)
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `blocking transaction cancelled through its handle fails at its statement and rolls back`() {
        val handle = CompletableFuture<Transaction>()
        val canceller = Thread {
            Thread.sleep(200)
            handle.join().cancel()
        }
        canceller.start()
        val started = System.nanoTime()
        assertThrows<PersistenceException> {
            transactionBlocking {
                orm.removeAll<Visit>()
                handle.complete(this)
                orm.query(SLOW_STATEMENT).singleResult
            }
        }
        canceller.join()
        (System.nanoTime() - started).shouldBeLessThan(10_000_000_000L)
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `interrupted thread is refused its next statement and the transaction rolls back`() {
        try {
            val refused = assertThrows<PersistenceException> {
                transactionBlocking {
                    orm.removeAll<Visit>()
                    Thread.currentThread().interrupt()
                    orm.exists<Visit>()
                }
            }
            refused.message.shouldContain("interrupted")
            // The flag is read, not cleared.
            Thread.currentThread().isInterrupted.shouldBeTrue()
        } finally {
            Thread.interrupted()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Context switching scenarios.
     */

    @Test
    fun `context switch sees uncommitted data`(): Unit = runBlocking {
        val afterSwitch = CompletableDeferred<Boolean>()
        transaction {
            orm.removeAll<Visit>()
            withContext(Dispatchers.IO) {
                afterSwitch.complete(orm.exists<Visit>())
            }
            delay(500) // Ensure the launch happens after the deleteAll
        }
        afterSwitch.await().shouldBeFalse()
    }

    @Test
    fun `context switch with transaction sees uncommitted data`(): Unit = runBlocking {
        val afterSwitch = CompletableDeferred<Boolean>()
        transaction {
            orm.removeAll<Visit>()
            withContext(Dispatchers.IO) {
                transactionBlocking {
                    afterSwitch.complete(orm.exists<Visit>())
                }
            }
            delay(500) // Ensure the launch happens after the deleteAll
        }
        afterSwitch.await().shouldBeFalse()
    }

    @Test
    fun `context switch with suspend transaction sees uncommitted data`(): Unit = runBlocking {
        val afterSwitch = CompletableDeferred<Boolean>()
        transaction {
            orm.removeAll<Visit>()
            withContext(Dispatchers.IO) {
                transaction {
                    afterSwitch.complete(orm.exists<Visit>())
                }
            }
            delay(500) // Ensure the launch happens after the deleteAll
        }
        afterSwitch.await().shouldBeFalse()
    }

    /**
     * Fail-fast concurrency scenarios.
     */

    @Test
    fun `concurrent DB access in same transaction throws exception`(): Unit = runBlocking {
        val e = assertThrows<PersistenceException> {
            transaction {
                coroutineScope {
                    val holding = CompletableDeferred<Unit>()
                    // The first coroutine holds the connection while it collects an open result.
                    launch(Dispatchers.IO) {
                        orm.query("SELECT 1").resultFlow.collect {
                            holding.complete(Unit)
                            awaitCancellation()
                        }
                    }
                    launch(Dispatchers.IO) {
                        holding.await()
                        orm.query("SELECT 1").singleResult
                    }
                }
            }
        }
        e.message!! shouldStartWith "Concurrent access"
    }

    @Test
    fun `withContext on another dispatcher continues as the same caller`(): Unit = runBlocking {
        transaction {
            orm.query("SELECT 1").singleResult
            withContext(Dispatchers.IO) {
                orm.query("SELECT 1").singleResult
            }
            orm.query("SELECT 1").singleResult
        }
    }

    @Test
    fun `coroutines that take turns share the transaction`(): Unit = runBlocking {
        transaction {
            coroutineScope {
                launch(Dispatchers.IO) { orm.query("SELECT 1").singleResult }.join()
                launch(Dispatchers.IO) { orm.query("SELECT 1").singleResult }.join()
            }
        }
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
    fun `inner REQUIRED exception caught by outer should cause UnexpectedRollbackException`(): Unit = runBlocking {
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
    fun `inner REQUIRED exception propagated to outer should throw original exception`(): Unit = runBlocking {
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

    @Test
    fun `global timeout applies to suspendTransaction`(): Unit = runBlocking {
        setGlobalTransactionOptions(timeoutSeconds = 1)
        assertThrows<TransactionTimedOutException> {
            transaction {
                orm.removeAll<Visit>()
                delay(1500)
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * Coroutine-scoped defaults
     */

    @Test
    fun `withDefaults overrides global for suspendTransaction`(): Unit = runBlocking {
        setGlobalTransactionOptions(timeoutSeconds = 5) // Relaxed global
        assertThrows<TransactionTimedOutException> {
            withTransactionOptions(timeoutSeconds = 1) {
                transaction {
                    orm.removeAll<Visit>()
                    delay(1500)
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `withDefaults default propagation=REQUIRES_NEW makes inner commit survive outer rollback`(): Unit = runBlocking {
        // Ensure outer uses default REQUIRED; inner (no args) should pick REQUIRES_NEW from withDefaults
        withTransactionOptions(propagation = REQUIRES_NEW, isolation = READ_COMMITTED) {
            transaction {
                // inner starts a new tx (from defaults), commits delete
                transaction {
                    orm.removeAll<Visit>()
                }
                // outer marks rollback-only: should NOT undo inner commit
                setRollbackOnly()
            }
        }
        // Inner committed work should remain
        orm.exists<Visit>().shouldBeFalse()
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
     * Thread-scoped defaults reach the coroutines bridged from the thread.
     */

    @Test
    fun `withThreadDefaults reaches a suspend transaction bridged with runBlocking`() {
        setGlobalTransactionOptions(timeoutSeconds = 5) // Relaxed global
        withTransactionOptionsBlocking(timeoutSeconds = 1) {
            // A runBlocking coroutine carries no scoped options of its own, so the thread's apply.
            assertThrows<TransactionTimedOutException> {
                runBlocking {
                    transaction {
                        orm.removeAll<Visit>()
                        delay(1500)
                    }
                }
            }
        }
        // Rolled back
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `withDefaults inside a bridged coroutine builds on the thread defaults`() {
        withTransactionOptionsBlocking(timeoutSeconds = 1) {
            assertThrows<TransactionTimedOutException> {
                runBlocking {
                    // Says nothing about the timeout, so the thread's 1s stands.
                    withTransactionOptions(readOnly = true) {
                        transaction {
                            delay(1500)
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `scoped defaults in the coroutine context win over the thread defaults`() {
        withTransactionOptionsBlocking(timeoutSeconds = 1) {
            runBlocking {
                withTransactionOptions(timeoutSeconds = 5) {
                    transaction {
                        // Should not throw
                        delay(1500)
                    }
                }
            }
        }
    }

    /**
     * Explicit args override defaults.
     */

    @Test
    fun `explicit suspendTransaction args override scoped defaults`(): Unit = runBlocking {
        withTransactionOptions(timeoutSeconds = 5) {
            // Even though scoped default is 5s, explicit 1s should win and time out
            assertThrows<TransactionTimedOutException> {
                transaction(timeoutSeconds = 1) {
                    delay(1500)
                }
            }
        }
    }

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

    /**
     * SUPPORTS propagation scenarios.
     */

    @Test
    fun `SUPPORTS with outer transaction should join and commit`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(SUPPORTS) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `SUPPORTS with outer transaction should join and rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(SUPPORTS) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `SUPPORTS without outer transaction should run non-transactionally`(): Unit = runBlocking {
        transactionBlocking(SUPPORTS) {
            orm.removeAll<Visit>()
        }
        // Non-transactional: changes are auto-committed immediately
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `SUPPORTS inner rollback marks outer rollback-only`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                transactionBlocking(SUPPORTS) {
                    orm.removeAll<Visit>()
                    setRollbackOnly()
                }
            }
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    /**
     * MANDATORY propagation scenarios.
     */

    @Test
    fun `MANDATORY with outer transaction should join and rollback`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
        }
        orm.exists<Visit>().shouldBeTrue()
    }

    @Test
    fun `MANDATORY without outer transaction should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transactionBlocking(MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
    }

    /**
     * NEVER propagation scenarios.
     */

    @Test
    fun `NEVER without outer transaction should run non-transactionally`(): Unit = runBlocking {
        transactionBlocking(NEVER) {
            orm.removeAll<Visit>()
        }
        // Non-transactional: auto-committed
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `NEVER with outer transaction should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transactionBlocking {
                transactionBlocking(NEVER) {
                    orm.removeAll<Visit>()
                }
            }
        }
    }

    /**
     * NOT_SUPPORTED propagation scenarios.
     */

    @Test
    fun `NOT_SUPPORTED without outer transaction should run non-transactionally`(): Unit = runBlocking {
        transactionBlocking(NOT_SUPPORTED) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `NOT_SUPPORTED with outer transaction should suspend outer and run non-transactionally`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NOT_SUPPORTED) {
                // This runs non-transactionally, changes are auto-committed
                orm.removeAll<Visit>()
            }
            // After NOT_SUPPORTED completes, outer transaction is resumed
            // The inner changes are already committed independently
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `NOT_SUPPORTED with outer rollback should still have inner changes committed`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NOT_SUPPORTED) {
                orm.removeAll<Visit>()
            }
            setRollbackOnly()
        }
        // NOT_SUPPORTED inner was non-transactional, so changes persist even though outer rolled back
        orm.exists<Visit>().shouldBeFalse()
    }

    /**
     * Read-only transaction scenarios.
     */

    @Test
    fun `readOnly transaction should allow reads`(): Unit = runBlocking {
        transactionBlocking(readOnly = true) {
            orm.exists<Visit>().shouldBeTrue()
        }
    }

    @Test
    fun `global readOnly default should allow reads`(): Unit = runBlocking {
        setGlobalTransactionOptions(readOnly = true)
        transactionBlocking {
            orm.exists<Visit>().shouldBeTrue()
        }
    }

    @Test
    fun `scoped readOnly default should allow reads`(): Unit = runBlocking {
        withTransactionOptions(readOnly = true) {
            transaction {
                orm.exists<Visit>().shouldBeTrue()
            }
        }
    }

    @Test
    fun `threadScoped readOnly default should allow reads`(): Unit = runBlocking {
        withTransactionOptionsBlocking(readOnly = true) {
            transactionBlocking {
                orm.exists<Visit>().shouldBeTrue()
            }
        }
    }

    /**
     * SUPPORTS with suspend transaction.
     */

    @Test
    fun `suspend SUPPORTS with outer transaction should join`(): Unit = runBlocking {
        transaction {
            transaction(propagation = SUPPORTS) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend SUPPORTS without outer transaction should run non-transactionally`(): Unit = runBlocking {
        transaction(propagation = SUPPORTS) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    /**
     * MANDATORY with suspend transaction.
     */

    @Test
    fun `suspend MANDATORY with outer transaction should join`(): Unit = runBlocking {
        transaction {
            transaction(propagation = MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend MANDATORY without outer transaction should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transaction(propagation = MANDATORY) {
                orm.removeAll<Visit>()
            }
        }
    }

    /**
     * NEVER with suspend transaction.
     */

    @Test
    fun `suspend NEVER without outer should run non-transactionally`(): Unit = runBlocking {
        transaction(propagation = NEVER) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend NEVER with outer should throw`(): Unit = runBlocking {
        assertThrows<PersistenceException> {
            transaction {
                transaction(propagation = NEVER) {
                    orm.removeAll<Visit>()
                }
            }
        }
    }

    /**
     * NOT_SUPPORTED with suspend transaction.
     */

    @Test
    fun `suspend NOT_SUPPORTED without outer should run non-transactionally`(): Unit = runBlocking {
        transaction(propagation = NOT_SUPPORTED) {
            orm.removeAll<Visit>()
        }
        orm.exists<Visit>().shouldBeFalse()
    }

    @Test
    fun `suspend NOT_SUPPORTED with outer should suspend and run non-transactionally`(): Unit = runBlocking {
        transaction {
            transaction(propagation = NOT_SUPPORTED) {
                orm.removeAll<Visit>()
            }
        }
        orm.exists<Visit>().shouldBeFalse()
    }
}

/**
 * A statement that runs for far longer than any test waits, so a test that stops it early proves the cancellation.
 */
private const val SLOW_STATEMENT = """
    SELECT COUNT(*)
    FROM SYSTEM_RANGE(1, 1_000_000) AS A
    CROSS JOIN SYSTEM_RANGE(1, 1_000_000) AS B
"""
