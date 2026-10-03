package st.orm.template

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldStartWith
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.PersistenceException
import st.orm.TransactionPropagation
import st.orm.core.spi.ConnectionGuard
import st.orm.core.spi.JdbcConnectionProviderImpl
import st.orm.repository.countAll
import st.orm.template.model.City
import st.orm.template.model.Visit
import javax.sql.DataSource

/**
 * Tests for [JdbcConnectionProviderImpl] covering connection acquisition,
 * release, and the ConnectionGuard.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class ConnectionProviderTest(
    @Autowired val orm: ORMTemplate,
    @Autowired val dataSource: DataSource,
) {

    @Test
    fun `getConnection without transaction should return new connection`() {
        val provider = JdbcConnectionProviderImpl()
        val connection = provider.getConnection(dataSource, null)
        connection shouldNotBe null
        connection.isClosed.shouldBeFalse()
        provider.releaseConnection(connection, dataSource, null)
        connection.isClosed.shouldBeTrue()
    }

    @Test
    fun `releaseConnection without transaction should close connection`() {
        val provider = JdbcConnectionProviderImpl()
        val connection = provider.getConnection(dataSource, null)
        connection.isClosed.shouldBeFalse()
        provider.releaseConnection(connection, dataSource, null)
        connection.isClosed.shouldBeTrue()
    }

    @Test
    fun `getConnection within transaction should reuse transaction connection`(): Unit = runBlocking {
        transactionBlocking {
            // Within a transaction, operations should use the transaction's connection
            val count = orm.countAll<City>()
            count shouldBe 6
        }
    }

    @Test
    fun `releaseConnection within transaction should not close connection`(): Unit = runBlocking {
        transactionBlocking {
            // Multiple operations within same transaction should reuse connection
            orm.countAll<City>() shouldBe 6
            orm.countAll<Visit>() shouldBe 14
        }
    }

    /** Runs [block] on a new thread and returns what it threw, if anything. */
    private fun onOtherThread(block: () -> Unit): Throwable? {
        var caught: Throwable? = null
        val thread = Thread(block)
        thread.setUncaughtExceptionHandler { _, throwable -> caught = throwable }
        thread.start()
        thread.join()
        return caught
    }

    @Test
    fun `ConnectionGuard allows an acquire and release`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.acquire(connection)
            guard.release()
        }
    }

    @Test
    fun `ConnectionGuard allows nested access by the same caller`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.acquire(connection)
            guard.acquire(connection)
            guard.release()
            guard.release()
            // Fully released: another caller can take the connection.
            onOtherThread {
                guard.acquire(connection)
                guard.release()
            } shouldBe null
        }
    }

    @Test
    fun `ConnectionGuard allows another thread after release`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.acquire(connection)
            guard.release()
            onOtherThread {
                guard.acquire(connection)
                guard.release()
            } shouldBe null
        }
    }

    @Test
    fun `ConnectionGuard refuses another thread while the connection is held`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.acquire(connection)
            val failure = onOtherThread { guard.acquire(connection) }
            (failure is PersistenceException).shouldBeTrue()
            failure!!.message!! shouldStartWith "Concurrent access"
            guard.release()
        }
    }

    @Test
    fun `ConnectionGuard keeps nested access counted after a refused caller`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.acquire(connection)
            guard.acquire(connection)
            onOtherThread { guard.acquire(connection) } shouldNotBe null
            guard.release()
            // Still held once by this thread.
            onOtherThread { guard.acquire(connection) } shouldNotBe null
            guard.release()
            onOtherThread {
                guard.acquire(connection)
                guard.release()
            } shouldBe null
        }
    }

    @Test
    fun `ConnectionGuard treats an installed caller identity as the same caller on any thread`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            val caller = Any()
            val holder = ConnectionGuard.callerHolder()
            holder.set(caller)
            try {
                guard.acquire(connection)
                onOtherThread {
                    holder.set(caller)
                    try {
                        guard.acquire(connection)
                        guard.release()
                    } finally {
                        holder.remove()
                    }
                } shouldBe null
                guard.release()
            } finally {
                holder.remove()
            }
        }
    }

    @Test
    fun `ConnectionGuard releases an access from another thread`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.acquire(connection)
            onOtherThread { guard.release() } shouldBe null
            onOtherThread {
                guard.acquire(connection)
                guard.release()
            } shouldBe null
        }
    }

    @Test
    fun `ConnectionGuard release without an access is a no-op`() {
        dataSource.connection.use { connection ->
            val guard = ConnectionGuard()
            guard.release()
            guard.acquire(connection)
            guard.release()
        }
    }

    @Test
    fun `queries outside transaction should each get fresh connection`() {
        // Each query outside a transaction gets its own connection
        val count1 = orm.entity(City::class).select().resultCount
        val count2 = orm.entity(City::class).select().resultCount
        count1 shouldBe 6L
        count2 shouldBe 6L
    }

    @Test
    fun `queries inside transaction should share connection`(): Unit = runBlocking {
        transactionBlocking {
            val count1 = orm.entity(City::class).select().resultCount
            val count2 = orm.entity(Visit::class).select().resultCount
            count1 shouldBe 6L
            count2 shouldBe 14L
        }
    }

    @Test
    fun `nested transactions should manage connections correctly`(): Unit = runBlocking {
        transactionBlocking {
            orm.countAll<City>() shouldBe 6
            transactionBlocking(TransactionPropagation.REQUIRES_NEW) {
                orm.countAll<City>() shouldBe 6
            }
            orm.countAll<City>() shouldBe 6
        }
    }
}
