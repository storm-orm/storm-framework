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

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import st.orm.core.template.SqlLog.Summary
import st.orm.core.template.impl.AdviceLog
import st.orm.core.template.impl.SqlLogRenderer
import st.orm.spring.model.Visit
import st.orm.template.InternalStormApi
import st.orm.template.ORMTemplate
import st.orm.template.recordSqlLog
import st.orm.template.transaction

/**
 * A suspending transaction under the Spring provider advises on the read it repeated when it completes, and the
 * performance log summary of the scope around it leaves those executions to the transaction: every statement
 * carries the identity of the transaction that advised, whichever dispatcher thread it ran on.
 */
@OptIn(InternalStormApi::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@SpringBootTest
@Sql("/data.sql")
internal open class SuspendingTransactionSummaryTest(
    @Autowired val orm: ORMTemplate,
) {

    @BeforeEach
    fun reportRepeats() {
        AdviceLog.categories(listOf("correctness", "performance", "design"))
        AdviceLog.repeatFloor(null)
    }

    @AfterEach
    fun forget() {
        AdviceLog.categories(listOf("correctness"))
        AdviceLog.repeatFloor(AdviceLog.DEFAULT_REPEAT_FLOOR)
    }

    private fun capture(action: () -> Unit): List<String> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("st.orm.sql.advice")
        val appender = ListAppender<ILoggingEvent>().apply {
            setContext(context)
            start()
        }
        val previous = logger.level
        logger.level = Level.INFO
        logger.addAppender(appender)
        logger.isAdditive = false
        try {
            action()
        } finally {
            logger.detachAppender(appender)
            logger.isAdditive = true
            logger.level = previous
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    @Test
    fun `the summary leaves a suspending transaction's repeat to the transaction`() {
        var summary: Summary? = null
        val lines = capture {
            runBlocking {
                recordSqlLog("task", 1_000, true, {
                    transaction(Dispatchers.IO) {
                        repeat(20) { orm.entity(Visit::class).findById(1) }
                    }
                }) { summary = it }
            }
        }
        lines shouldHaveSize 1
        summary!!.statementCount() shouldBe 20
        summary!!.statements().all { it.transactionId() != 0L } shouldBe true
        SqlLogRenderer.render(summary!!).contains("advice:") shouldBe false
    }
}
