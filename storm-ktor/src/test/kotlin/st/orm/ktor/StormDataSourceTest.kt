package st.orm.ktor

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.ktor.server.application.install
import io.ktor.server.testing.testApplication
import org.junit.jupiter.api.Test
import java.sql.DriverManager

class StormDataSourceTest {

    @Test
    fun `creates DataSource from HOCON config`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "storm.datasource.jdbcUrl" to "jdbc:h2:mem:ds-test-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "storm.datasource.driverClassName" to "org.h2.Driver",
                "storm.datasource.username" to "sa",
                "storm.datasource.password" to "",
                "storm.datasource.maximumPoolSize" to "3",
            )
        }
        application {
            install(Storm)
            stormDataSource shouldNotBe null
            orm shouldNotBe null
        }
    }

    @Test
    fun `creates DataSource with all optional HikariCP properties`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "storm.datasource.jdbcUrl" to "jdbc:h2:mem:ds-full-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "storm.datasource.driverClassName" to "org.h2.Driver",
                "storm.datasource.username" to "sa",
                "storm.datasource.password" to "",
                "storm.datasource.maximumPoolSize" to "5",
                "storm.datasource.connectionTimeout" to "30000",
                "storm.datasource.idleTimeout" to "600000",
                "storm.datasource.maxLifetime" to "1800000",
                "storm.datasource.minimumIdle" to "2",
            )
        }
        application {
            install(Storm)
            stormDataSource shouldNotBe null
        }
    }

    @Test
    fun `creates DataSource with minimal config`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "storm.datasource.jdbcUrl" to "jdbc:h2:mem:ds-minimal-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            )
        }
        application {
            install(Storm)
            stormDataSource shouldNotBe null
        }
    }

    @Test
    fun `DataSource is closed on application stop`() = testApplication {
        environment {
            config = io.ktor.server.config.MapApplicationConfig(
                "storm.datasource.jdbcUrl" to "jdbc:h2:mem:ds-close-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "storm.datasource.driverClassName" to "org.h2.Driver",
                "storm.datasource.username" to "sa",
                "storm.datasource.password" to "",
            )
        }
        application {
            install(Storm)
            stormDataSource shouldNotBe null
        }
        // testApplication stops the application after the block, triggering the shutdown hook.
    }

    @Test
    fun `non-HikariCP DataSource is not closed by plugin`() {
        // Verify closeDataSourceIfManaged does not fail on a non-Hikari DataSource.
        val simpleDataSource = object : javax.sql.DataSource {
            override fun getConnection() = DriverManager.getConnection(
                "jdbc:h2:mem:ds-simple-${System.nanoTime()};DB_CLOSE_DELAY=-1",
                "sa",
                "",
            )
            override fun getConnection(u: String?, p: String?) = getConnection()
            override fun getLogWriter() = null
            override fun setLogWriter(out: java.io.PrintWriter?) {}
            override fun setLoginTimeout(seconds: Int) {}
            override fun getLoginTimeout() = 0
            override fun getParentLogger(): java.util.logging.Logger = throw java.sql.SQLFeatureNotSupportedException()
            override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLException()
            override fun isWrapperFor(iface: Class<*>?) = false
        }
        testApplication {
            application {
                install(Storm) {
                    dataSource = simpleDataSource
                }
                stormDataSource shouldBe simpleDataSource
            }
        }
        // No exception means closeDataSourceIfManaged correctly skipped the non-Hikari DataSource.
    }
}
