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
package st.orm.spring

import io.kotest.matchers.shouldBe
import org.h2.jdbcx.JdbcDataSource
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import st.orm.TransactionPropagation.NOT_SUPPORTED
import st.orm.TransactionPropagation.REQUIRED
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.spring.kotlin.springOrmTemplate
import st.orm.template.ORMTemplate
import st.orm.template.transactionBlocking
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Proxy
import java.sql.Connection
import javax.sql.DataSource

/**
 * The calls a Spring-managed transaction makes on a connection for the read-only mode, and the connection each
 * frame makes them on. Storm hands the mode to Spring's transaction definition, and Spring's transaction manager
 * decides what reaches the connection; this test states the resulting sequence so that it holds on every driver
 * alike.
 *
 * The contract: a read-only frame marks the connection it opens and clears the mark before the connection returns
 * to the pool, while a read-write frame states nothing, since the pool's connections arrive read-write. A
 * `REQUIRES_NEW` frame suspends the enclosing transaction, which keeps that transaction's connection and its mode
 * as they are, and opens a connection of its own; a `NOT_SUPPORTED` frame does the same and runs outside a
 * transaction; a joined `REQUIRED` frame runs on the enclosing transaction's connection and cannot lift its mode.
 *
 * A frame binds its connection on its first touch, so each enclosing frame runs a query before opening the inner
 * one; that keeps the connection numbers in the order the frames appear.
 */
internal class ReadOnlyCallSequenceTest {
    private val h2 = JdbcDataSource().apply {
        setURL("jdbc:h2:mem:springReadOnlyCallSequence;DB_CLOSE_DELAY=-1")
        user = "sa"
    }

    private lateinit var recording: RecordingDataSource
    private lateinit var orm: ORMTemplate

    @BeforeEach
    fun prepare() {
        h2.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE IF NOT EXISTS call_sequence (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(255))")
                statement.execute("DELETE FROM call_sequence")
            }
        }
        recording = RecordingDataSource(h2)
        val transactionManager = DataSourceTransactionManager(recording)
        orm = springOrmTemplate(recording) { listOf(transactionManager) }
        // The template opens a connection of its own once, to detect the dialect; that one is not part of any
        // transaction's sequence.
        read()
        recording.reset()
    }

    /**
     * Hands out proxies over the target's connections, numbered in the order they are opened, and records every
     * `setReadOnly` and `close` call with the number of the connection it lands on.
     */
    private class RecordingDataSource(private val target: DataSource) : DataSource by target {
        val calls = mutableListOf<String>()
        private var opened = 0

        fun reset() {
            calls.clear()
            opened = 0
        }

        override fun getConnection(): Connection {
            val physical = target.connection
            val number = ++opened
            return Proxy.newProxyInstance(Connection::class.java.classLoader, arrayOf(Connection::class.java)) { _, method, arguments ->
                when (method.name) {
                    "setReadOnly" -> calls += "$number:setReadOnly(${arguments[0]})"
                    "close" -> if (method.parameterCount == 0) calls += "$number:close"
                }
                try {
                    method.invoke(physical, *(arguments ?: emptyArray()))
                } catch (e: InvocationTargetException) {
                    throw e.cause ?: e
                }
            } as Connection
        }
    }

    private fun read() {
        orm.query("SELECT COUNT(*) FROM call_sequence").resultList
    }

    private fun write() {
        orm.query("INSERT INTO call_sequence (name) VALUES ('row')").executeUpdate()
    }

    @Test
    fun `a read-only frame marks its connection on open and clears the mark before the connection closes`() {
        transactionBlocking(readOnly = true) {
            read()
        }
        recording.calls shouldBe listOf("1:setReadOnly(true)", "1:setReadOnly(false)", "1:close")
    }

    @Test
    fun `a read-write frame states no mode`() {
        transactionBlocking {
            write()
        }
        recording.calls shouldBe listOf("1:close")
    }

    @Test
    fun `a REQUIRES_NEW frame inside a read-only frame opens a connection of its own and leaves the suspended one marked`() {
        transactionBlocking(readOnly = true) {
            read()
            transactionBlocking(REQUIRES_NEW, readOnly = false) {
                write()
            }
            read()
        }
        recording.calls shouldBe listOf("1:setReadOnly(true)", "2:close", "1:setReadOnly(false)", "1:close")
    }

    @Test
    fun `a joined REQUIRED frame runs on the enclosing connection and does not lift its read-only mode`() {
        transactionBlocking(readOnly = true) {
            read()
            transactionBlocking(REQUIRED, readOnly = false) {
                read()
            }
        }
        recording.calls shouldBe listOf("1:setReadOnly(true)", "1:setReadOnly(false)", "1:close")
    }

    @Test
    fun `a NOT_SUPPORTED frame inside a read-only frame opens a connection of its own and states no mode`() {
        transactionBlocking(readOnly = true) {
            read()
            transactionBlocking(NOT_SUPPORTED) {
                write()
            }
        }
        recording.calls shouldBe listOf("1:setReadOnly(true)", "2:close", "1:setReadOnly(false)", "1:close")
    }

    @Test
    fun `consecutive transactions each leave their connection as they found it`() {
        transactionBlocking(readOnly = true) {
            read()
        }
        transactionBlocking {
            write()
        }
        recording.calls shouldBe listOf("1:setReadOnly(true)", "1:setReadOnly(false)", "1:close", "2:close")
    }
}
