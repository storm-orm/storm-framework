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
package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import javax.sql.DataSource

/**
 * Creates a [HikariDataSource] from the application's HOCON configuration.
 *
 * Expected configuration in `application.conf`:
 * ```
 * storm {
 *     datasource {
 *         jdbcUrl = "jdbc:h2:mem:test"
 *         driverClassName = "org.h2.Driver"
 *         username = "sa"
 *         password = ""
 *         maximumPoolSize = 10
 *     }
 * }
 * ```
 */
internal fun createDataSourceFromConfig(application: Application): DataSource {
    val config = application.environment.config
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = config.property("storm.datasource.jdbcUrl").getString()
        config.propertyOrNull("storm.datasource.driverClassName")?.getString()?.let {
            driverClassName = it
        }
        config.propertyOrNull("storm.datasource.username")?.getString()?.let {
            username = it
        }
        config.propertyOrNull("storm.datasource.password")?.getString()?.let {
            password = it
        }
        config.propertyOrNull("storm.datasource.maximumPoolSize")?.getString()?.toIntOrNull()?.let {
            maximumPoolSize = it
        }
        config.propertyOrNull("storm.datasource.connectionTimeout")?.getString()?.toLongOrNull()?.let {
            connectionTimeout = it
        }
        config.propertyOrNull("storm.datasource.idleTimeout")?.getString()?.toLongOrNull()?.let {
            idleTimeout = it
        }
        config.propertyOrNull("storm.datasource.maxLifetime")?.getString()?.toLongOrNull()?.let {
            maxLifetime = it
        }
        config.propertyOrNull("storm.datasource.minimumIdle")?.getString()?.toIntOrNull()?.let {
            minimumIdle = it
        }
    }
    return HikariDataSource(hikariConfig)
}

/**
 * Closes the [DataSource] if it is a [HikariDataSource] (managed by the plugin).
 */
internal fun closeDataSourceIfManaged(dataSource: DataSource) {
    if (dataSource is HikariDataSource) {
        dataSource.close()
    }
}
