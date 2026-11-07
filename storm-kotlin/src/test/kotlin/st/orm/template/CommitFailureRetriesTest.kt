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
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.springframework.jdbc.datasource.DelegatingDataSource
import org.springframework.jdbc.datasource.DriverManagerDataSource
import st.orm.PersistenceException
import st.orm.TransientPersistenceException
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.SQLException
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * A serialization failure the commit reports reruns a block that states retries, in the suspending and the blocking
 * flow, as one a statement reports does; a commit failure on a lost connection does not.
 */
@Timeout(30)
internal class CommitFailureRetriesTest {

    /**
     * Hands out proxies over its connections whose `commit()` rolls back and throws the next error of [failures], or
     * commits once there is none.
     */
    private class FailingCommitDataSource(dataSource: DataSource) : DelegatingDataSource(dataSource) {
        val failures = ArrayDeque<SQLException>()

        override fun getConnection(): Connection {
            val connection = super.getConnection()
            return Proxy.newProxyInstance(
                Connection::class.java.classLoader,
                arrayOf(Connection::class.java),
            ) { _, method, arguments ->
                if (method.name == "commit") {
                    val failure = failures.poll()
                    if (failure != null) {
                        connection.rollback()
                        throw failure
                    }
                }
                try {
                    method.invoke(connection, *(arguments ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.targetException
                }
            } as Connection
        }
    }

    private lateinit var dataSource: FailingCommitDataSource
    private lateinit var orm: ORMTemplate

    @BeforeEach
    fun createDatabase() {
        dataSource = FailingCommitDataSource(
            DriverManagerDataSource("jdbc:h2:mem:kotlin-commit-${UUID.randomUUID()};DB_CLOSE_DELAY=-1", "sa", ""),
        )
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
                statement.execute("INSERT INTO counter (id, amount) VALUES (1, 0)")
            }
        }
        orm = ORMTemplate.of(dataSource)
    }

    @AfterEach
    fun dropDatabase() {
        dataSource.failures.clear()
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("SHUTDOWN") } }
    }

    private fun increment() {
        orm.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate()
    }

    private fun amount(): Int = (orm.query("SELECT amount FROM counter WHERE id = 1").singleResult[0] as Number).toInt()

    private fun serializationFailure() = SQLException("could not serialize access due to read/write dependencies", "40001")

    @Test
    fun `a serialization failure at commit reruns a suspending block`(): Unit = runBlocking {
        dataSource.failures += serializationFailure()
        var attempts = 0
        val completions = mutableListOf<Boolean>()
        transaction(retries = 2) {
            attempts++
            onCompletion { committed -> completions += committed }
            increment()
        }
        attempts shouldBe 2
        completions shouldContainExactly listOf(false, true)
        amount() shouldBe 1
    }

    @Test
    fun `a serialization failure at commit reruns a blocking block`() {
        dataSource.failures += serializationFailure()
        val attempts = AtomicInteger()
        val commits = AtomicInteger()
        transactionBlocking(retries = 2) {
            attempts.incrementAndGet()
            onCommit { commits.incrementAndGet() }
            increment()
        }
        attempts.get() shouldBe 2
        commits.get() shouldBe 1
        amount() shouldBe 1
    }

    @Test
    fun `a serialization failure at commit leaves a block without retries as a transient failure`(): Unit = runBlocking {
        dataSource.failures += serializationFailure()
        assertThrows<TransientPersistenceException> {
            transaction { increment() }
        }
        amount() shouldBe 0
    }

    @Test
    fun `a commit failure on a lost connection does not rerun`(): Unit = runBlocking {
        dataSource.failures += SQLException("connection reset", "08006")
        var attempts = 0
        val thrown = assertThrows<PersistenceException> {
            transaction(retries = 2) {
                attempts++
                increment()
            }
        }
        thrown.shouldNotBeInstanceOf<TransientPersistenceException>()
        attempts shouldBe 1
    }
}
