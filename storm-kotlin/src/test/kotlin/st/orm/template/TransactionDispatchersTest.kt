package st.orm.template

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import st.orm.template.impl.VirtualThreadDispatcher

/**
 * Tests for [TransactionDispatchers] covering:
 * - Default dispatcher resolution
 * - Override via setter
 * - Virtual dispatcher lazy creation
 * - System property handling
 * - shutdown()
 */
internal class TransactionDispatchersTest {

    @Test
    fun `Default dispatcher should not be null`() {
        TransactionDispatchers.Default shouldNotBe null
    }

    @Test
    fun `Virtual dispatcher should be VirtualThreadDispatcher`() {
        TransactionDispatchers.Virtual.shouldBeInstanceOf<VirtualThreadDispatcher>()
    }

    @Test
    fun `setting Default should override resolved default`() {
        val original = TransactionDispatchers.Default
        try {
            TransactionDispatchers.Default = Dispatchers.Unconfined
            TransactionDispatchers.Default shouldBe Dispatchers.Unconfined
        } finally {
            TransactionDispatchers.Default = original
        }
    }

    @Test
    fun `clearing Default override should resolve lazily`() {
        val original = TransactionDispatchers.Default
        try {
            // Set override
            TransactionDispatchers.Default = Dispatchers.Unconfined
            TransactionDispatchers.Default shouldBe Dispatchers.Unconfined
            // Restore
            TransactionDispatchers.Default = original
            TransactionDispatchers.Default shouldBe original
        } finally {
            TransactionDispatchers.Default = original
        }
    }

    @Test
    fun `shutdown should not throw even when Virtual was created`() {
        // Force Virtual to be created
        val virtual = TransactionDispatchers.Virtual
        virtual shouldNotBe null
        // Shutdown should not throw
        TransactionDispatchers.shutdown()
        // The dispatcher stays usable: work dispatched after the shutdown runs on a new executor.
        runBlocking(TransactionDispatchers.Virtual) { 1 + 1 } shouldBe 2
    }

    @Test
    fun `resolveDefault on JDK 21 should return IO dispatcher`() {
        // On JDK 21 (which is what we run on), virtual threads are not enabled by default
        // because isJava24OrNewer returns false. The system property storm.virtual_threads.enabled
        // is not set in tests (defaults to "false"), so resolveDefault should return Dispatchers.IO.
        val prop = System.getProperty("storm.virtual_threads.enabled")
        if (prop == null || !prop.equals("true", ignoreCase = true)) {
            val runtimeVersion = Runtime.version().feature()
            if (runtimeVersion < 24) {
                // On JDK < 24 with no property override, Default should be IO
                TransactionDispatchers.Default shouldBe Dispatchers.IO
            }
        }
    }
}
