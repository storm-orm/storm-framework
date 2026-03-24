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
package st.orm.ktor.test

import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import st.orm.template.ORMTemplate
import st.orm.test.SqlCapture
import java.sql.Connection
import java.sql.DriverManager
import javax.sql.DataSource

/**
 * Convenience function for testing Ktor applications that use Storm.
 *
 * Creates an H2 in-memory database, executes optional SQL scripts, and provides a [StormTestScope] with
 * pre-configured [DataSource], [ORMTemplate], and [SqlCapture]. The scope also acts as an
 * [ApplicationTestBuilder] so you can configure routes, install plugins, and make test HTTP requests.
 *
 * Example usage:
 * ```kotlin
 * @Test
 * fun `GET users returns list`() = testStormApplication(
 *     scripts = listOf("/schema.sql", "/data.sql"),
 * ) {
 *     application {
 *         install(Storm) { dataSource = stormDataSource }
 *         routing { userRoutes() }
 *     }
 *
 *     client.get("/users").apply {
 *         assertEquals(HttpStatusCode.OK, status)
 *     }
 * }
 * ```
 *
 * @param scripts classpath resource paths of SQL scripts to execute before the test.
 * @param url JDBC URL for the test database. Defaults to a unique H2 in-memory database.
 * @param username database username. Defaults to `"sa"`.
 * @param password database password. Defaults to `""`.
 * @param block the test body, with both [StormTestScope] and [ApplicationTestBuilder] as receivers.
 * @since 1.11
 */
fun testStormApplication(
    scripts: List<String> = emptyList(),
    url: String = "",
    username: String = "sa",
    password: String = "",
    block: suspend ApplicationTestBuilder.(StormTestScope) -> Unit,
) {
    val resolvedUrl = url.ifEmpty {
        "jdbc:h2:mem:storm-test-${System.nanoTime()};DB_CLOSE_DELAY=-1"
    }
    val dataSource = SimpleDataSource(resolvedUrl, username, password)

    if (scripts.isNotEmpty()) {
        dataSource.connection.use { connection ->
            for (script in scripts) {
                val sql = readScript(script)
                executeScript(connection, sql)
            }
        }
    }

    val ormTemplate = ORMTemplate.of(dataSource)
    val sqlCapture = SqlCapture()
    val scope = StormTestScope(dataSource, ormTemplate, sqlCapture)

    testApplication {
        block(scope)
    }
}

/**
 * Reads a SQL script from the classpath.
 */
private fun readScript(resourcePath: String): String {
    val inputStream = StormTestScope::class.java.getResourceAsStream(resourcePath)
        ?: throw IllegalArgumentException("SQL script not found on classpath: $resourcePath")
    return inputStream.bufferedReader().use { it.readText() }
}

/**
 * Executes a SQL script by splitting on semicolons and executing each statement.
 */
private fun executeScript(connection: Connection, sql: String) {
    connection.createStatement().use { statement ->
        for (line in sql.split(";")) {
            val trimmed = line.trim()
            if (trimmed.isNotEmpty()) {
                statement.execute(trimmed)
            }
        }
    }
}

/**
 * Minimal [DataSource] implementation backed by [DriverManager].
 */
private class SimpleDataSource(
    private val url: String,
    private val user: String,
    private val pass: String,
) : DataSource {

    override fun getConnection(): Connection = DriverManager.getConnection(url, user, pass)
    override fun getConnection(username: String?, password: String?): Connection = DriverManager.getConnection(url, username, password)

    override fun getLogWriter() = null
    override fun setLogWriter(out: java.io.PrintWriter?) {}
    override fun setLoginTimeout(seconds: Int) {}
    override fun getLoginTimeout() = 0
    override fun getParentLogger(): java.util.logging.Logger = throw java.sql.SQLFeatureNotSupportedException()

    override fun <T : Any?> unwrap(iface: Class<T>?): T = throw java.sql.SQLException("Not a wrapper.")
    override fun isWrapperFor(iface: Class<*>?): Boolean = false
}
