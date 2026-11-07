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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.boot.jdbc.DataSourceBuilder
import org.springframework.dao.TransientDataAccessException
import org.springframework.jdbc.datasource.DataSourceTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import st.orm.IllegalTransactionStateException
import st.orm.TransactionPropagation.REQUIRED
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.spring.kotlin.springOrmTemplate
import st.orm.template.ORMTemplate
import st.orm.template.transaction
import st.orm.template.transactionBlocking
import java.sql.SQLException
import java.util.concurrent.atomic.AtomicInteger
import javax.sql.DataSource

/**
 * The database function behind `CONFLICT()`: fails with a serialization failure while conflicts remain.
 */
public object SpringRetryConflicts {
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
 * Verifies that a Kotlin block with retries reruns on a transient failure when the template runs through Spring's
 * transaction manager and exception translation, where the failure surfaces as a [TransientDataAccessException], and
 * that a block joining a Spring-managed transaction refuses retries. Spring-managed transactions are blocking, so the
 * suspending variant appears only where it is refused before it opens.
 */
internal class SpringTransactionRetriesTest {

    private lateinit var dataSource: DataSource
    private lateinit var transactionManager: DataSourceTransactionManager
    private lateinit var orm: ORMTemplate

    @BeforeEach
    fun setUp() {
        SpringRetryConflicts.remaining.set(0)
        dataSource = DataSourceBuilder.create()
            .url("jdbc:h2:mem:kotlinspringretries;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
            .username("sa")
            .password("")
            .driverClassName("org.h2.Driver")
            .build()
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)")
                statement.execute("INSERT INTO counter (id, amount) VALUES (1, 0)")
                statement.execute("CREATE ALIAS CONFLICT FOR \"${SpringRetryConflicts::class.java.name}.conflict\"")
            }
        }
        transactionManager = DataSourceTransactionManager(dataSource)
        orm = springOrmTemplate(dataSource) { listOf(transactionManager) }
    }

    @AfterEach
    fun tearDown() {
        dataSource.connection.use { connection -> connection.createStatement().use { it.execute("DROP ALL OBJECTS") } }
    }

    private fun incrementAndConflict() {
        orm.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate()
        orm.query("SELECT CONFLICT()").resultList
    }

    private fun amount(): Int = (orm.query("SELECT amount FROM counter WHERE id = 1").singleResult[0] as Number).toInt()

    @Test
    fun `a transient failure Spring translates reruns a blocking block`() {
        SpringRetryConflicts.remaining.set(1)
        var attempts = 0
        var commits = 0
        transactionBlocking(retries = 2) {
            attempts++
            onCommit { commits++ }
            incrementAndConflict()
        }
        attempts shouldBe 2
        commits shouldBe 1
        amount() shouldBe 1
    }

    @Test
    fun `exhausted retries throw what Spring translated with the earlier failures suppressed`() {
        SpringRetryConflicts.remaining.set(10)
        var attempts = 0
        val thrown = assertThrows<TransientDataAccessException> {
            transactionBlocking(retries = 1) {
                attempts++
                incrementAndConflict()
            }
        }
        attempts shouldBe 2
        thrown.suppressed.filterIsInstance<TransientDataAccessException>().size shouldBe 1
        amount() shouldBe 0
    }

    @Test
    fun `a block joining a Spring transaction refuses retries`() {
        TransactionTemplate(transactionManager).executeWithoutResult {
            assertThrows<IllegalTransactionStateException> {
                transactionBlocking(propagation = REQUIRED, retries = 1) { }
            }
            assertThrows<IllegalTransactionStateException> {
                runBlocking { transaction(retries = 1) { } }
            }
        }
    }

    @Test
    fun `a block starting its own transaction inside a Spring transaction reruns`() {
        SpringRetryConflicts.remaining.set(1)
        var attempts = 0
        TransactionTemplate(transactionManager).executeWithoutResult {
            transactionBlocking(propagation = REQUIRES_NEW, retries = 1) {
                attempts++
                incrementAndConflict()
            }
        }
        attempts shouldBe 2
        amount() shouldBe 1
    }
}
