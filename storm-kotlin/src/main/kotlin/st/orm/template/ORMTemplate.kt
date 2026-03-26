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

import st.orm.Data
import st.orm.EntityCallback
import st.orm.StormConfig
import st.orm.mapping.TemplateDecorator
import st.orm.repository.RepositoryLookup
import st.orm.template.impl.ORMTemplateImpl
import java.sql.Connection
import javax.sql.DataSource
import kotlin.reflect.KClass

/**
 * The primary entry point for Storm's ORM functionality in Kotlin, combining SQL template query construction with
 * repository access.
 *
 * `ORMTemplate` extends both [QueryTemplate] (for constructing and executing SQL queries) and
 * [RepositoryLookup] (for obtaining type-safe [st.orm.repository.EntityRepository] and
 * [st.orm.repository.ProjectionRepository] instances). It is the central interface from which all database
 * operations originate.
 *
 * Instances can be created using the companion object factory methods [of], or via the Kotlin extension
 * properties [DataSource.orm] and [Connection.orm]. For JPA-based usage, see `JpaTemplate`.
 *
 * ## Example
 * ```kotlin
 * val orm = dataSource.orm
 *
 * // Repository-based access
 * val users = orm.entity(User::class)
 * val user = users.findById(42)
 *
 * // Template-based query
 * val result = orm.query("SELECT ${User::class} FROM ${User::class} WHERE ${User_.name} = ${"Alice"}")
 *     .getResultList(User::class)
 * ```
 *
 * @see st.orm.repository.EntityRepository
 * @see st.orm.repository.ProjectionRepository
 */
interface ORMTemplate :
    QueryTemplate,
    RepositoryLookup {

    /**
     * Returns a new [ORMTemplate] with the specified entity callback added.
     *
     * The returned template shares the same underlying connection and configuration, but applies the given
     * callback to entity lifecycle operations (insert, update, delete) performed through its repositories. The
     * callback is only invoked for entities matching its type parameter. Multiple callbacks can be registered by
     * chaining calls to this method.
     *
     * @param callback the entity callback to add.
     * @return a new [ORMTemplate] with the callback added.
     * @since 1.9
     */
    fun withEntityCallback(callback: EntityCallback<*>): ORMTemplate

    /**
     * Returns a new [ORMTemplate] with the specified entity callbacks added.
     *
     * The returned template shares the same underlying connection and configuration, but applies the given
     * callbacks to entity lifecycle operations (insert, update, delete) performed through its repositories. Each
     * callback is only invoked for entities matching its type parameter.
     *
     * @param callbacks the entity callbacks to add.
     * @return a new [ORMTemplate] with the callbacks added.
     * @since 1.9
     */
    fun withEntityCallbacks(callbacks: List<EntityCallback<*>>): ORMTemplate

    /**
     * Validates all discovered entity and projection types against the database schema.
     *
     * Logs each validation error and returns the list of error messages. On success, logs a
     * confirmation message and returns an empty list.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @return the list of validation error messages (empty on success).
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    fun validateSchema(): List<String>

    /**
     * Validates the specified types against the database schema.
     *
     * Logs each validation error and returns the list of error messages. On success, logs a
     * confirmation message and returns an empty list.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @param types the entity and projection types to validate.
     * @return the list of validation error messages (empty on success).
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    fun validateSchema(vararg types: KClass<out Data>): List<String>

    /**
     * Validates all discovered types and throws if any errors are found.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    fun validateSchemaOrThrow()

    /**
     * Validates the specified types and throws if any errors are found.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @param types the entity and projection types to validate.
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    fun validateSchemaOrThrow(vararg types: KClass<out Data>)

    companion object {
        /**
         * Returns an [ORMTemplate] for use with JDBC.
         *
         * This method creates an ORM repository template using the provided [DataSource].
         * It allows you to perform database operations using JDBC in a type-safe manner.
         *
         * Example usage:
         * ```
         * DataSource dataSource = ...;
         * ORMTemplate orm = ORMTemplate.of(dataSource);
         * List<MyTable> otherTables = orm.query(RAW."""
         * SELECT \{MyTable.class}
         * FROM \{MyTable.class}
         * WHERE \{MyTable_.name} = \{"ABC"}""")
         * .getResultList(MyTable.class);
         * ```
         *
         * @param dataSource the [DataSource] to use for database operations; must not be `null`.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(dataSource: DataSource): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource))

        /**
         * Returns an [ORMTemplate] for use with JDBC.
         *
         * This method creates an ORM repository template using the provided [Connection].
         * It allows you to perform database operations using JDBC in a type-safe manner.
         *
         * **Note:** The caller is responsible for closing the connection after usage.
         *
         * Example usage:
         * ```
         * try (Connection connection = ...) {
         * ORMTemplate orm = ORMTemplate.of(connection);
         * List<MyTable> otherTables = orm.query(RAW."""
         * SELECT \{MyTable.class}
         * FROM \{MyTable.class}
         * WHERE \{MyTable_.name} = \{"ABC"}""")
         * .getResultList(MyTable.class)
         * }
         * ```
         *
         * @param connection the [Connection] to use for database operations; must not be `null`.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(connection: Connection): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection))

        /**
         * Returns an [ORMTemplate] for use with JDBC, with a custom template decorator.
         *
         * This method creates an ORM repository template using the provided [DataSource] and applies
         * the specified decorator to customize template processing behavior.
         *
         * @param dataSource the [DataSource] to use for database operations.
         * @param decorator a function that transforms the [TemplateDecorator] to customize template processing.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(
            dataSource: DataSource,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, decorator))

        /**
         * Returns an [ORMTemplate] for use with JDBC, with a custom template decorator.
         *
         * This method creates an ORM repository template using the provided [Connection] and applies
         * the specified decorator to customize template processing behavior.
         *
         * **Note:** The caller is responsible for closing the connection after usage.
         *
         * @param connection the [Connection] to use for database operations.
         * @param decorator a function that transforms the [TemplateDecorator] to customize template processing.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(
            connection: Connection,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, decorator))

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig].
         *
         * @param dataSource the [DataSource] to use for database operations.
         * @param config the Storm configuration to apply.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(dataSource: DataSource, config: StormConfig): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, config))

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig] and a custom
         * template decorator.
         *
         * @param dataSource the [DataSource] to use for database operations.
         * @param config the Storm configuration to apply.
         * @param decorator a function that transforms the [TemplateDecorator] to customize template processing.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(
            dataSource: DataSource,
            config: StormConfig,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, config, decorator))

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig].
         *
         * **Note:** The caller is responsible for closing the connection after usage.
         *
         * @param connection the [Connection] to use for database operations.
         * @param config the Storm configuration to apply.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(connection: Connection, config: StormConfig): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config))

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig] and a custom
         * template decorator.
         *
         * **Note:** The caller is responsible for closing the connection after usage.
         *
         * @param connection the [Connection] to use for database operations.
         * @param config the Storm configuration to apply.
         * @param decorator a function that transforms the [TemplateDecorator] to customize template processing.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        fun of(
            connection: Connection,
            config: StormConfig,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate = ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config, decorator))
    }
}

/**
 * Creates an [ORMTemplate] from this [DataSource] with default configuration.
 *
 * ```kotlin
 * val orm = dataSource.orm
 * ```
 *
 * Requires `import st.orm.template.orm`.
 */
val DataSource.orm: ORMTemplate
    get() = ORMTemplate.of(this)

/**
 * Creates an [ORMTemplate] from this [Connection] with default configuration.
 *
 * Requires `import st.orm.template.orm`.
 */
val Connection.orm: ORMTemplate
    get() = ORMTemplate.of(this)

/**
 * Creates an [ORMTemplate] from this [DataSource] with a custom [TemplateDecorator].
 *
 * The decorator wraps the template pipeline, allowing cross-cutting concerns such as logging or SQL rewriting.
 *
 * Requires `import st.orm.template.orm`.
 */
fun DataSource.orm(decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, decorator)

/**
 * Creates an [ORMTemplate] from this [Connection] with a custom [TemplateDecorator].
 *
 * Requires `import st.orm.template.orm`.
 */
fun Connection.orm(decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, decorator)

/**
 * Creates an [ORMTemplate] from this [DataSource] with a custom [StormConfig].
 *
 * Requires `import st.orm.template.orm`.
 */
fun DataSource.orm(config: StormConfig): ORMTemplate = ORMTemplate.of(this, config)

/**
 * Creates an [ORMTemplate] from this [Connection] with a custom [StormConfig].
 *
 * Requires `import st.orm.template.orm`.
 */
fun Connection.orm(config: StormConfig): ORMTemplate = ORMTemplate.of(this, config)

/**
 * Creates an [ORMTemplate] from this [DataSource] with a custom [StormConfig] and [TemplateDecorator].
 *
 * Requires `import st.orm.template.orm`.
 */
fun DataSource.orm(config: StormConfig, decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, config, decorator)

/**
 * Creates an [ORMTemplate] from this [Connection] with a custom [StormConfig] and [TemplateDecorator].
 *
 * Requires `import st.orm.template.orm`.
 */
fun Connection.orm(config: StormConfig, decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, config, decorator)
