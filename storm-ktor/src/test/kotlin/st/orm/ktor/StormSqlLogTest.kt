package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.config.MapApplicationConfig
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import st.orm.core.template.impl.SlowStatementLog
import st.orm.ktor.model.PetRepository
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Verifies the per-call SQL log end to end: the summary reports through the `st.orm.sql.perf` logger, which the
 * test classpath's slf4j-simple binding writes to `System.err`, captured around the whole test application so
 * the assertion runs after every pipeline has unwound.
 */
internal class StormSqlLogTest {

    private fun createTestDataSource(): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:storm-sqllog-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }
        val dataSource = HikariDataSource(config)
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                val sql = this::class.java.getResourceAsStream("/schema.sql")!!.bufferedReader().readText()
                for (line in sql.split(";")) {
                    val trimmed = line.trim()
                    if (trimmed.isNotEmpty()) {
                        statement.execute(trimmed)
                    }
                }
            }
        }
        return dataSource
    }

    private fun captureStdErr(block: () -> Unit): String {
        val original = System.err
        val buffer = ByteArrayOutputStream()
        System.setErr(PrintStream(buffer, true))
        try {
            block()
        } finally {
            System.setErr(original)
        }
        return buffer.toString()
    }

    private fun requestPets(vararg configPairs: Pair<String, String>, configure: StormPluginConfig.() -> Unit = {}): String {
        val dataSource = createTestDataSource()
        try {
            return captureStdErr {
                testApplication {
                    environment {
                        config = MapApplicationConfig(*configPairs)
                    }
                    application {
                        install(Storm) {
                            this.dataSource = dataSource
                            configure()
                        }
                        routing {
                            get("/pets") {
                                call.respondText(repository<PetRepository>().findAll().size.toString())
                            }
                        }
                    }
                    client.get("/pets").status shouldBe HttpStatusCode.OK
                }
            }
        } finally {
            dataSource.close()
        }
    }

    @Test
    fun `the per-call summary is enabled from the configuration file alone`() {
        val output = requestPets("storm.sqlLog.performance.enabled" to "true")
        output shouldContain "SQL (GET /pets)"
    }

    @Test
    fun `a plugin setting overrides the configuration file`() {
        val output = requestPets("storm.sqlLog.performance.enabled" to "true") {
            sqlLogPerformance = false
        }
        output shouldNotContain "SQL (GET /pets)"
    }

    @Test
    fun `an unexceeded threshold from the configuration file suppresses the summary`() {
        val output = requestPets(
            "storm.sqlLog.performance.enabled" to "true",
            "storm.sqlLog.performance.threshold.statements" to "999",
        )
        output shouldNotContain "SQL (GET /pets)"
    }

    @Test
    fun `the slow statement log reports without the per-call summary`() {
        try {
            // A one-nanosecond threshold makes every execution slow; the summaries stay off.
            val output = requestPets("storm.sqlLog.slow.threshold" to "1ns")
            output shouldContain "SQL slow (SELECT Pet)"
            output shouldContain "WARN"
            output shouldNotContain "SQL (GET /pets)"
        } finally {
            SlowStatementLog.threshold(null)
        }
    }

    @Test
    fun `the slow statement threshold applies from the plugin configuration`() {
        try {
            val output = requestPets { sqlLogSlowThreshold = 1.nanoseconds }
            output shouldContain "SQL slow (SELECT Pet)"
        } finally {
            SlowStatementLog.threshold(null)
        }
    }

    @Test
    fun `the slow transaction threshold applies from the configuration file and the plugin configuration`() {
        try {
            requestPets("storm.sqlLog.slow.transaction" to "2s")
            SlowStatementLog.transactionThreshold() shouldBe java.time.Duration.ofSeconds(2)
            requestPets { sqlLogSlowTransaction = 3.seconds }
            SlowStatementLog.transactionThreshold() shouldBe java.time.Duration.ofSeconds(3)
        } finally {
            SlowStatementLog.transactionThreshold(null)
        }
    }

    @Test
    fun `an exceeded threshold from the configuration file reports at WARN`() {
        val output = requestPets(
            "storm.sql_log.performance.enabled" to "true",
            "storm.sql_log.performance.threshold.statements" to "1",
        )
        output shouldContain "SQL (GET /pets)"
        output shouldContain "WARN"
    }
}
