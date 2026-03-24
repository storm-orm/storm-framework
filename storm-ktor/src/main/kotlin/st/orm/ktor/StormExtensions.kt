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

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.routing.RoutingContext
import st.orm.repository.Repository
import st.orm.template.ORMTemplate
import javax.sql.DataSource

/**
 * Returns the Storm [ORMTemplate] configured for this application.
 *
 * Requires the [Storm] plugin to be installed.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
val Application.orm: ORMTemplate
    get() = attributes.getOrNull(OrmTemplateKey)
        ?: throw IllegalStateException(
            "Storm plugin is not installed. Call install(Storm) in your application module.",
        )

/**
 * Returns the [DataSource] configured for this application.
 *
 * Requires the [Storm] plugin to be installed.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
val Application.stormDataSource: DataSource
    get() = attributes.getOrNull(DataSourceKey)
        ?: throw IllegalStateException(
            "Storm plugin is not installed. Call install(Storm) in your application module.",
        )

/**
 * Returns the Storm [ORMTemplate] configured for this application.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
val ApplicationCall.orm: ORMTemplate
    get() = application.orm

/**
 * Returns the Storm [ORMTemplate] configured for this application.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
val RoutingContext.orm: ORMTemplate
    get() = call.application.orm

/**
 * Retrieves a registered Storm repository.
 *
 * Requires the repository to have been registered via [stormRepositories].
 *
 * @throws IllegalStateException if no repository registry is configured or the repository type is not registered.
 * @since 1.11
 */
inline fun <reified T : Repository> Application.repository(): T {
    val registry = attributes.getOrNull(RepositoryRegistryKey)
        ?: throw IllegalStateException(
            "No Storm repository registry configured. Call stormRepositories { } in your application module.",
        )
    return registry.get(T::class)
}

/**
 * Retrieves a registered Storm repository.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if no repository registry is configured or the repository type is not registered.
 * @since 1.11
 */
inline fun <reified T : Repository> ApplicationCall.repository(): T = application.repository()

/**
 * Retrieves a registered Storm repository.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if no repository registry is configured or the repository type is not registered.
 * @since 1.11
 */
inline fun <reified T : Repository> RoutingContext.repository(): T = call.application.repository()
