package st.orm.template

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.AcceptAdvice
import st.orm.repository.EntityRepository
import st.orm.template.model.City

internal interface AcceptingCities : EntityRepository<City, Int> {

    @AcceptAdvice("unordered-limit")
    fun acceptedPage(): List<City> = select().limit(21).resultList

    fun page(): List<City> = select().limit(22).resultList
}

/**
 * [AcceptAdvice] is found where Kotlin compiles code differently from Java: a repository's default function, which
 * runs from the interface's DefaultImpls, a lambda, and the block of a transaction. The advice log runs at DEBUG,
 * once per shape, and every statement reads a page of its own size, so each has a shape of its own.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class AcceptAdviceTest(
    @Autowired val orm: ORMTemplate,
) {

    private fun advice(action: () -> Unit): List<String> {
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val logger = context.getLogger("st.orm.sql.advice")
        val appender = ListAppender<ILoggingEvent>().apply {
            this.context = context
            start()
        }
        logger.level = Level.DEBUG
        logger.addAppender(appender)
        logger.isAdditive = false
        try {
            action()
        } finally {
            logger.detachAppender(appender)
            logger.isAdditive = true
            logger.level = null
            appender.stop()
        }
        return appender.list.map { it.formattedMessage }
    }

    private fun unorderedPage(size: Int) {
        orm.entity(City::class).select().limit(size).resultList
    }

    @AcceptAdvice("unordered-limit")
    private fun acceptedInLambda() {
        val read: () -> Unit = { unorderedPage(23) }
        read()
    }

    @AcceptAdvice("unordered-limit")
    private fun acceptedInTransaction() {
        transactionBlocking { unorderedPage(24) }
    }

    @AcceptAdvice("unordered-limit")
    private suspend fun acceptedInSuspendTransaction() {
        transaction { unorderedPage(25) }
    }

    @Test
    fun `advice accepted on a repository default function is not reported`() {
        val cities = orm.repository(AcceptingCities::class)
        advice { cities.acceptedPage() }.shouldBeEmpty()
        advice { cities.page() } shouldHaveSize 1
    }

    @Test
    fun `advice accepted on the function that declares a lambda is not reported`() {
        advice { acceptedInLambda() }.shouldBeEmpty()
    }

    @Test
    fun `advice accepted on the function that runs a transaction is not reported`() {
        advice { acceptedInTransaction() }.shouldBeEmpty()
        advice { runBlocking { acceptedInSuspendTransaction() } }.shouldBeEmpty()
        advice { unorderedPage(26) } shouldHaveSize 1
    }
}
