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

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.jdbc.datasource.DriverManagerDataSource
import st.orm.DbTable
import st.orm.Entity
import st.orm.PK
import st.orm.repository.EntityRepository
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.sql.Connection
import java.sql.PreparedStatement
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * A flow write with a batch size executes one batch per that many entities, also for a batch size above the
 * default of 1000. The writes run against a data source that counts the batches its prepared statements execute.
 */
class FlowBatchSizeTest {

    @DbTable("flow_item")
    data class FlowItem(
        @PK val id: Int = 0,
        val name: String,
    ) : Entity<Int>

    private val batches = AtomicInteger()
    private lateinit var items: EntityRepository<FlowItem, Int>

    @BeforeEach
    fun setUp() {
        val h2 = DriverManagerDataSource("jdbc:h2:mem:flow_batch_${UUID.randomUUID()};DB_CLOSE_DELAY=-1")
        h2.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE flow_item (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL)")
            }
        }
        items = ORMTemplate.of(counting(h2)).entity(FlowItem::class)
    }

    /** Wraps the data source so every batch its prepared statements execute is counted. */
    private fun counting(target: DataSource): DataSource = proxy(DataSource::class.java, target) { _, result ->
        if (result is Connection) {
            proxy(Connection::class.java, result) { _, connectionResult ->
                if (connectionResult is PreparedStatement) {
                    proxy(PreparedStatement::class.java, connectionResult) { method, statementResult ->
                        if (method.name == "executeBatch") {
                            batches.incrementAndGet()
                        }
                        statementResult
                    }
                } else {
                    connectionResult
                }
            }
        } else {
            result
        }
    }

    private fun <T : Any> proxy(type: Class<T>, target: T, observer: (Method, Any?) -> Any?): T = type.cast(
        Proxy.newProxyInstance(FlowBatchSizeTest::class.java.classLoader, arrayOf(type)) { _, method, arguments ->
            val result = try {
                method.invoke(target, *(arguments ?: emptyArray()))
            } catch (e: InvocationTargetException) {
                throw e.targetException
            }
            observer(method, result)
        },
    )

    private fun newItems(count: Int): List<FlowItem> = (1..count).map { FlowItem(name = "item$it") }

    /** Inserts the rows and returns them as read, with the ids they were given. */
    private fun existingItems(count: Int): List<FlowItem> {
        items.insert(newItems(count))
        return items.findAll()
    }

    /** Runs the write and returns the number of batches it executed. */
    private fun batchesOf(write: suspend () -> Unit): Int {
        batches.set(0)
        runBlocking { write() }
        return batches.get()
    }

    @Test
    fun `insert flow executes one batch per batch size above the default`() {
        batchesOf { items.insert(newItems(2500).asFlow(), 2500) } shouldBe 1
        items.count() shouldBe 2500
    }

    @Test
    fun `insert flow executes one batch per batch size below the default`() {
        batchesOf { items.insert(newItems(2500).asFlow(), 700) } shouldBe 4
        items.count() shouldBe 2500
    }

    @Test
    fun `insert flow without auto generation executes one batch per batch size above the default`() {
        val explicit = (1..2500).map { FlowItem(id = it, name = "item$it") }
        batchesOf { items.insert(explicit.asFlow(), 2500, ignoreAutoGenerate = true) } shouldBe 1
        items.count() shouldBe 2500
    }

    @Test
    fun `update flow executes one batch per batch size above the default`() {
        val existing = existingItems(2500)
        batchesOf { items.update(existing.map { it.copy(name = "updated") }.asFlow(), 2500) } shouldBe 1
        items.findAll().all { it.name == "updated" } shouldBe true
    }

    @Test
    fun `update flow executes one batch per batch size below the default`() {
        val existing = existingItems(2500)
        batchesOf { items.update(existing.map { it.copy(name = "updated") }.asFlow(), 700) } shouldBe 4
    }

    @Test
    fun `upsert flow executes one batch per batch size above the default`() {
        val existing = existingItems(2500)
        batchesOf { items.upsert(existing.map { it.copy(name = "upserted") }.asFlow(), 2500) } shouldBe 1
        items.findAll().all { it.name == "upserted" } shouldBe true
    }

    @Test
    fun `remove flow executes one batch per batch size above the default`() {
        val existing = existingItems(2500)
        batchesOf { items.remove(existing.asFlow(), 2500) } shouldBe 1
        items.count() shouldBe 0
    }
}
