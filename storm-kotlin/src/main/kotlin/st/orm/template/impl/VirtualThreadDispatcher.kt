package st.orm.template.impl

import kotlinx.coroutines.CoroutineDispatcher
import java.io.Closeable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import kotlin.coroutines.CoroutineContext

/**
 * A [kotlinx.coroutines.CoroutineDispatcher] that uses virtual threads for executing coroutines.
 *
 * This dispatcher is designed to be used in environments that support virtual threads.
 *
 * @since 1.5
 */
internal class VirtualThreadDispatcher(
    threadNamePrefix: String = "virtual",
) : CoroutineDispatcher(),
    Closeable {

    private val factory: ThreadFactory = Thread.ofVirtual()
        .name(threadNamePrefix, 0)
        .factory()

    // The executor the dispatcher runs on, or null until the next dispatch starts one.
    @Volatile
    private var executor: ExecutorService? = null

    private fun executor(): ExecutorService = executor ?: synchronized(this) {
        executor ?: Executors.newThreadPerTaskExecutor(factory).also { executor = it }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        executor().execute(block)
    }

    /**
     * Shuts down the executor, letting the work it runs complete. The dispatcher stays usable: the next dispatch
     * starts a new executor, so whoever holds the dispatcher, such as a context or a default that names it, keeps
     * working after a shutdown.
     */
    override fun close() {
        synchronized(this) { executor.also { executor = null } }?.close()
    }
}
