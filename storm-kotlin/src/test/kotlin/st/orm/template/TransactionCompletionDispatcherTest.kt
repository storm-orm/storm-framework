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

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import st.orm.template.impl.VirtualThreadDispatcher
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * A suspending transaction completes on the dispatcher that ran its block, so the connection returns to the pool
 * without a hop back to the caller's dispatcher. A caller's dispatcher that is saturated while the block is in
 * flight, as a request dispatcher is under a burst of blocking work, must not keep connections idle in transaction.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
internal class TransactionCompletionDispatcherTest {

    private lateinit var pool: HikariDataSource
    private lateinit var orm: ORMTemplate
    private val commitThreads = ConcurrentHashMap<String, Int>()
    private val virtual = VirtualThreadDispatcher(threadNamePrefix = "tx")

    @BeforeAll
    fun openPool() {
        pool = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:h2:mem:completion_dispatcher;DB_CLOSE_DELAY=-1"
                username = "sa"
                password = ""
                maximumPoolSize = 10
                connectionTimeout = 3000
            },
        )
        orm = ORMTemplate.of(commitTracing(pool))
    }

    @AfterAll
    fun closePool() {
        pool.close()
        virtual.close()
    }

    private fun commitTracing(dataSource: DataSource): DataSource = object : DataSource by dataSource {
        override fun getConnection(): Connection {
            val connection = dataSource.connection
            return Proxy.newProxyInstance(javaClass.classLoader, arrayOf(Connection::class.java)) { _, method, args ->
                if (method.name == "commit") {
                    commitThreads.merge(Thread.currentThread().name.replace(Regex("[0-9]+"), ""), 1, Int::plus)
                }
                try {
                    method.invoke(connection, *(args ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
        }
    }

    /**
     * Runs [requests] transactions from [caller] on [transactionDispatcher]. Once the first blocks are in flight,
     * [hogs] blocking tasks take every slot of the IO dispatcher for [hogMillis]. The pool serves the burst within
     * its timeout only when the transactions in flight release their connections without the IO dispatcher.
     */
    private fun burst(
        caller: CoroutineDispatcher,
        transactionDispatcher: CoroutineDispatcher,
        requests: Int = 60,
        hogs: Int = 64,
        hogMillis: Long = 4000,
    ): Int {
        commitThreads.clear()
        val completed = AtomicInteger()
        runBlocking {
            coroutineScope {
                repeat(requests) {
                    launch(caller) {
                        transaction(dispatcher = transactionDispatcher, readOnly = true) {
                            orm.query("SELECT 1").getSingleResult(Int::class)
                            Thread.sleep(300)
                        }
                        completed.incrementAndGet()
                    }
                }
                delay(100)
                repeat(hogs) {
                    launch(Dispatchers.IO) { Thread.sleep(hogMillis) }
                }
            }
        }
        return completed.get()
    }

    @Test
    fun `a transaction on virtual threads releases its connection while the caller's IO dispatcher is saturated`() {
        burst(caller = Dispatchers.IO, transactionDispatcher = virtual) shouldBe 60
        commitThreads.keys.all { it.startsWith("tx") } shouldBe true
    }

    @Test
    fun `a transaction on a bounded IO view releases its connection while the caller's IO dispatcher is saturated`() {
        burst(caller = Dispatchers.IO, transactionDispatcher = Dispatchers.IO.limitedParallelism(10)) shouldBe 60
    }

    @Test
    fun `a transaction on the caller's own dispatcher commits in place`() {
        burst(caller = Dispatchers.IO, transactionDispatcher = Dispatchers.IO, hogs = 0) shouldBe 60
    }
}
