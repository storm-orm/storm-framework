package st.orm.template

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.PersistenceException
import st.orm.core.spi.TransactionContext
import st.orm.repository.countAll
import st.orm.template.impl.CoroutineAwareConnectionProviderImpl
import st.orm.template.model.City
import st.orm.template.model.Visit
import javax.sql.DataSource

/**
 * Tests for [CoroutineAwareConnectionProviderImpl] covering connection acquisition,
 * release, and the ConcurrencyDetector.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class ConnectionProviderTest(
    @Autowired val orm: ORMTemplate,
    @Autowired val dataSource: DataSource,
) {

    @Test
    fun `getConnection without transaction should return new connection`() {
        val provider = CoroutineAwareConnectionProviderImpl()
        val connection = provider.getConnection(dataSource, null)
        connection shouldNotBe null
        connection.isClosed.shouldBeFalse()
        provider.releaseConnection(connection, dataSource, null)
        connection.isClosed.shouldBeTrue()
    }

    @Test
    fun `releaseConnection without transaction should close connection`() {
        val provider = CoroutineAwareConnectionProviderImpl()
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

    private fun stubContext(): TransactionContext = object : TransactionContext {
        override fun entityCache(entityType: Class<out st.orm.Entity<*>>, retention: st.orm.core.spi.CacheRetention) = throw UnsupportedOperationException()
        override fun getEntityCache(entityType: Class<out st.orm.Entity<*>>) = throw UnsupportedOperationException()
        override fun findEntityCache(entityType: Class<out st.orm.Entity<*>>) = null
        override fun clearAllEntityCaches() {}
        override fun <T : Any?> getDecorator(resourceType: Class<T>): TransactionContext.Decorator<T> = TransactionContext.Decorator { it }
    }

    @Test
    fun `ConcurrencyDetector beforeAccess and afterAccess with same context should succeed`() {
        val connection = dataSource.connection
        val context = stubContext()
        try {
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context)
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `ConcurrencyDetector should allow nested access with same context`() {
        val connection = dataSource.connection
        val context = stubContext()
        try {
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context)
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context)
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context)
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `ConcurrencyDetector should allow same context from different thread`() {
        val connection = dataSource.connection
        val context = stubContext()
        try {
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context)
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context)
            // Same context, different thread — simulates virtual thread migration.
            val thread = Thread {
                CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context)
                CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context)
            }
            thread.start()
            thread.join()
        } finally {
            connection.close()
        }
    }

    @Test
    fun `ConcurrencyDetector should detect concurrent access from different contexts`() {
        val connection = dataSource.connection
        val context1 = stubContext()
        val context2 = stubContext()
        try {
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context1)
            var caughtException: Throwable? = null
            val thread = Thread {
                CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.beforeAccess(connection, context2)
            }
            thread.setUncaughtExceptionHandler { _, throwable -> caughtException = throwable }
            thread.start()
            thread.join()
            assertThrows<PersistenceException> {
                caughtException?.let { throw it }
            }
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context1)
        } finally {
            connection.close()
        }
    }

    @Test
    fun `ConcurrencyDetector afterAccess on unknown connection should be no-op`() {
        val connection = dataSource.connection
        val context = stubContext()
        try {
            // afterAccess on a connection never registered should not throw
            CoroutineAwareConnectionProviderImpl.ConcurrencyDetector.afterAccess(connection, context)
        } finally {
            connection.close()
        }
    }

    // Integration: connection provider behavior through ORM operations

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
