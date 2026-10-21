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
import st.orm.Entity
import st.orm.Projection
import st.orm.repository.EntityRepository
import st.orm.repository.ProjectionRepository
import st.orm.repository.Repository
import st.orm.repository.entity
import st.orm.repository.projection
import st.orm.template.ORMTemplate
import javax.sql.DataSource
import kotlin.reflect.KClass

/**
 * Returns the Storm [ORMTemplate] configured for this application.
 *
 * Requires the [Storm] plugin to be installed.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
public val Application.orm: ORMTemplate
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
public val Application.stormDataSource: DataSource
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
public val ApplicationCall.orm: ORMTemplate
    get() = application.orm

/**
 * Returns the Storm [ORMTemplate] configured for this application.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
public val RoutingContext.orm: ORMTemplate
    get() = call.application.orm

/**
 * Returns the Storm [ORMTemplate] of the named database.
 *
 * Named databases are configured inside the [Storm] plugin via `database("name") { }`.
 *
 * @param name the database name as declared in the plugin configuration.
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public fun Application.orm(name: String): ORMTemplate {
    val namedTemplates = attributes.getOrNull(NamedOrmTemplatesKey)
        ?: throw IllegalStateException(
            "Storm plugin is not installed. Call install(Storm) in your application module.",
        )
    return namedTemplates[name] ?: throw IllegalStateException(
        "No database named '$name' is configured. Configured databases: " +
            (namedTemplates.keys.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: "<none>") +
            ". Declare it with database(\"$name\") { } inside install(Storm).",
    )
}

/**
 * Returns the Storm [ORMTemplate] of the named database.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public fun ApplicationCall.orm(name: String): ORMTemplate = application.orm(name)

/**
 * Returns the Storm [ORMTemplate] of the named database.
 *
 * Convenience extension for use in route handlers.
 *
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public fun RoutingContext.orm(name: String): ORMTemplate = call.application.orm(name)

/**
 * Returns the [DataSource] of the named database.
 *
 * @param name the database name as declared in the plugin configuration.
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public fun Application.stormDataSource(name: String): DataSource {
    val namedDataSources = attributes.getOrNull(NamedDataSourcesKey)
        ?: throw IllegalStateException(
            "Storm plugin is not installed. Call install(Storm) in your application module.",
        )
    return namedDataSources[name] ?: throw IllegalStateException(
        "No database named '$name' is configured. Declare it with database(\"$name\") { } inside install(Storm).",
    )
}

/**
 * Retrieves a Storm repository.
 *
 * Repositories from the compile-time type index are registered automatically when the [Storm] plugin is
 * installed (see [StormPluginConfig.autoRegisterRepositories]); other types are created lazily on first
 * access. Either way, the instance is cached for the lifetime of the application.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
public inline fun <reified T : Repository> Application.repository(): T {
    val registry = attributes.getOrNull(RepositoryRegistryKey)
        ?: throw IllegalStateException(
            "Storm plugin is not installed. Call install(Storm) in your application module.",
        )
    return registry.getOrCreate(T::class)
}

/**
 * Retrieves a Storm repository from the named database.
 *
 * Repositories under the packages declared in the database's `repositories(...)` configuration are registered at
 * startup; other types are created lazily against the named database's template and cached.
 *
 * @param name the database name as declared in the plugin configuration.
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public inline fun <reified T : Repository> Application.repository(name: String): T {
    val namedRegistries = attributes.getOrNull(NamedRepositoryRegistriesKey)
        ?: throw IllegalStateException(
            "Storm plugin is not installed. Call install(Storm) in your application module.",
        )
    val registry = namedRegistries[name] ?: throw IllegalStateException(
        "No database named '$name' is configured. Declare it with database(\"$name\") { } inside install(Storm).",
    )
    return registry.getOrCreate(T::class)
}

/**
 * Retrieves a Storm repository from the named database.
 *
 * Convenience extension for use in route handlers. See [Application.repository] for registration and caching
 * semantics.
 *
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public inline fun <reified T : Repository> ApplicationCall.repository(name: String): T = application.repository<T>(name)

/**
 * Retrieves a Storm repository from the named database.
 *
 * Convenience extension for use in route handlers. See [Application.repository] for registration and caching
 * semantics.
 *
 * @throws IllegalStateException if the Storm plugin is not installed or no database with the given name is
 * configured.
 * @since 1.13
 */
public inline fun <reified T : Repository> RoutingContext.repository(name: String): T = call.application.repository<T>(name)

/**
 * Retrieves a Storm repository.
 *
 * Convenience extension for use in route handlers. See [Application.repository] for registration and caching
 * semantics.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
public inline fun <reified T : Repository> ApplicationCall.repository(): T = application.repository()

/**
 * Retrieves a Storm repository.
 *
 * Convenience extension for use in route handlers. See [Application.repository] for registration and caching
 * semantics.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.11
 */
public inline fun <reified T : Repository> RoutingContext.repository(): T = call.application.repository()

/**
 * Returns the repository for entity type [T] with primary key type [ID].
 *
 * The primary key type can be inferred with the underscore operator: `entity<User, _>()`.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.12
 */
public inline fun <reified T : Entity<ID>, ID : Any> Application.entity(): EntityRepository<T, ID> = orm.entity<T, ID>()

/**
 * Returns the repository for entity type [T] with primary key type [ID].
 *
 * Convenience extension for use in route handlers. See [Application.entity].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.12
 */
public inline fun <reified T : Entity<ID>, ID : Any> ApplicationCall.entity(): EntityRepository<T, ID> = application.entity<T, ID>()

/**
 * Returns the repository for entity type [T] with primary key type [ID].
 *
 * Convenience extension for use in route handlers. See [Application.entity].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.12
 */
public inline fun <reified T : Entity<ID>, ID : Any> RoutingContext.entity(): EntityRepository<T, ID> = call.application.entity<T, ID>()

/**
 * Returns the repository for projection type [T] with primary key type [ID].
 *
 * The primary key type can be inferred with the underscore operator: `projection<OwnerView, _>()`.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.12
 */
public inline fun <reified T : Projection<ID>, ID : Any> Application.projection(): ProjectionRepository<T, ID> = orm.projection<T, ID>()

/**
 * Returns the repository for projection type [T] with primary key type [ID].
 *
 * Convenience extension for use in route handlers. See [Application.projection].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.12
 */
public inline fun <reified T : Projection<ID>, ID : Any> ApplicationCall.projection(): ProjectionRepository<T, ID> = application.projection<T, ID>()

/**
 * Returns the repository for projection type [T] with primary key type [ID].
 *
 * Convenience extension for use in route handlers. See [Application.projection].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.12
 */
public inline fun <reified T : Projection<ID>, ID : Any> RoutingContext.projection(): ProjectionRepository<T, ID> = call.application.projection<T, ID>()

/**
 * Returns the repository for entity type [type], with the primary key type inferred from the entity.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.16
 */
public fun <T, ID : Any> Application.entity(type: KClass<T>): EntityRepository<T, ID> where T : Entity<ID> = orm.entity(type)

/**
 * Returns the repository for entity type [type], with the primary key type inferred from the entity.
 *
 * Convenience extension for use in route handlers. See [Application.entity].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.16
 */
public fun <T, ID : Any> ApplicationCall.entity(type: KClass<T>): EntityRepository<T, ID> where T : Entity<ID> = application.entity(type)

/**
 * Returns the repository for entity type [type], with the primary key type inferred from the entity.
 *
 * Convenience extension for use in route handlers. See [Application.entity].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.16
 */
public fun <T, ID : Any> RoutingContext.entity(type: KClass<T>): EntityRepository<T, ID> where T : Entity<ID> = call.application.entity(type)

/**
 * Returns the repository for projection type [type], with the primary key type inferred from the projection.
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.16
 */
public fun <T, ID : Any> Application.projection(type: KClass<T>): ProjectionRepository<T, ID> where T : Projection<ID> = orm.projection(type)

/**
 * Returns the repository for projection type [type], with the primary key type inferred from the projection.
 *
 * Convenience extension for use in route handlers. See [Application.projection].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.16
 */
public fun <T, ID : Any> ApplicationCall.projection(type: KClass<T>): ProjectionRepository<T, ID> where T : Projection<ID> = application.projection(type)

/**
 * Returns the repository for projection type [type], with the primary key type inferred from the projection.
 *
 * Convenience extension for use in route handlers. See [Application.projection].
 *
 * @throws IllegalStateException if the Storm plugin is not installed.
 * @since 1.16
 */
public fun <T, ID : Any> RoutingContext.projection(type: KClass<T>): ProjectionRepository<T, ID> where T : Projection<ID> = call.application.projection(type)
