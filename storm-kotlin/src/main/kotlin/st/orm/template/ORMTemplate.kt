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
import st.orm.template.impl.Engine
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
public interface ORMTemplate :
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
    public fun withEntityCallback(callback: EntityCallback<*>): ORMTemplate

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
    public fun withEntityCallbacks(callbacks: List<EntityCallback<*>>): ORMTemplate

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
    public fun validateSchema(): List<String>

    /**
     * Validates discovered types matching the filter against the database schema.
     *
     * Discovers all entity and projection types via the classpath index, applies the given filter, and validates
     * the matching types. This is useful when a single application connects to multiple datasources and only a
     * subset of types is reachable from each connection.
     *
     * Logs each validation error and returns the list of error messages. On success, logs a
     * confirmation message and returns an empty list.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @param filter predicate to select which discovered types to validate.
     * @return the list of validation error messages (empty on success).
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.11
     */
    public fun validateSchema(filter: (KClass<out Data>) -> Boolean): List<String>

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
    public fun validateSchema(vararg types: KClass<out Data>): List<String>

    /**
     * Validates all discovered types and throws if any errors are found.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    public fun validateSchemaOrThrow()

    /**
     * Validates discovered types matching the filter and throws if any errors are found.
     *
     * Discovers all entity and projection types via the classpath index, applies the given filter, and validates
     * the matching types. This is useful when a single application connects to multiple datasources and only a
     * subset of types is reachable from each connection.
     *
     * This method requires a DataSource-backed template. Templates created from a raw
     * [java.sql.Connection] or `EntityManager` do not support schema validation.
     *
     * @param filter predicate to select which discovered types to validate.
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.11
     */
    public fun validateSchemaOrThrow(filter: (KClass<out Data>) -> Boolean)

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
    public fun validateSchemaOrThrow(vararg types: KClass<out Data>)

    public companion object {
        /**
         * Returns the template of the operation that fired the entity callback running on this thread.
         *
         * An [EntityCallback] receives the entity and nothing else, so a callback that performs database work of
         * its own reaches its template here rather than capturing one:
         *
         * ```
         * class ArticleHistoryCallback : EntityCallback<Article> {
         *     override fun afterUpdate(entities: List<Article>) {
         *         ORMTemplate.current().writeSet().insert(entities.map(ArticleHistory::of))
         *     }
         * }
         * ```
         *
         * The template returned is the one the write is running on, so the work goes to the same database, over the
         * same connection, inside the same transaction, whichever template fired the callback. A callback that
         * captures a template instead makes that choice once, at construction, and a callback registered on several
         * templates has no correct choice to make.
         *
         * Callbacks never fire recursively, so the work performed through this template fires none of its own.
         *
         * @return the template the operation runs on; never `null`.
         * @throws st.orm.PersistenceException if no entity callback is executing on this thread.
         * @since 1.14
         */
        public fun current(): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.current())
        }

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
        public fun of(dataSource: DataSource): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource))
        }

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
        public fun of(connection: Connection): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection))
        }

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
        public fun of(
            dataSource: DataSource,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, decorator))
        }

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
        public fun of(
            connection: Connection,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, decorator))
        }

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig].
         *
         * @param dataSource the [DataSource] to use for database operations.
         * @param config the Storm configuration to apply.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        public fun of(dataSource: DataSource, config: StormConfig): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, config))
        }

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig] and a custom
         * template decorator.
         *
         * @param dataSource the [DataSource] to use for database operations.
         * @param config the Storm configuration to apply.
         * @param decorator a function that transforms the [TemplateDecorator] to customize template processing.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        public fun of(
            dataSource: DataSource,
            config: StormConfig,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, config, decorator))
        }

        /**
         * Returns an [ORMTemplate] for use with JDBC, configured with the provided [StormConfig].
         *
         * **Note:** The caller is responsible for closing the connection after usage.
         *
         * @param connection the [Connection] to use for database operations.
         * @param config the Storm configuration to apply.
         * @return an [ORMTemplate] configured for use with JDBC.
         */
        public fun of(connection: Connection, config: StormConfig): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config))
        }

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
        public fun of(
            connection: Connection,
            config: StormConfig,
            decorator: (TemplateDecorator) -> TemplateDecorator,
        ): ORMTemplate {
            Engine.require()
            return ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config, decorator))
        }

        /**
         * Returns a builder for constructing an [ORMTemplate] with instance-scoped integration strategies.
         *
         * The builder is the injection point for platform services: integrations hand their connection provider,
         * transaction template provider, exception mapper and query observer to the template they construct, instead
         * of relying on JVM-global discovery.
         *
         * @param dataSource the [DataSource] to use for database operations.
         * @return a builder for constructing the ORM template.
         * @since 1.13
         */
        @OptIn(InternalStormApi::class)
        public fun builder(dataSource: DataSource): Builder {
            Engine.require()
            return Builder(st.orm.core.template.ORMTemplate.builder(dataSource))
        }

        /**
         * Returns a builder for constructing an [ORMTemplate] backed by a single connection, with instance-scoped
         * integration strategies.
         *
         * **Note:** The caller is responsible for closing the connection after usage.
         *
         * @param connection the [Connection] to use for database operations.
         * @return a builder for constructing the ORM template.
         * @since 1.13
         */
        @OptIn(InternalStormApi::class)
        public fun builder(connection: Connection): Builder {
            Engine.require()
            return Builder(st.orm.core.template.ORMTemplate.builder(connection))
        }
    }

    /**
     * Builder for constructing an [ORMTemplate] with instance-scoped integration strategies.
     *
     * @since 1.13
     */
    public class Builder @InternalStormApi public constructor(private val core: st.orm.core.template.ORMTemplate.Builder) {

        /**
         * Sets the Storm configuration to apply to the template instance.
         */
        public fun config(config: StormConfig): Builder = apply { core.config(config) }

        /**
         * Sets a function that transforms the [TemplateDecorator] to customize template processing.
         */
        public fun decorator(decorator: (TemplateDecorator) -> TemplateDecorator): Builder = apply { core.decorator(decorator) }

        /**
         * Declares that the data source hands out connections with auto-commit disabled.
         *
         * The declared mode is verified in both directions: a declared template that receives an auto-commit
         * connection fails fast naming the misdeclaration, exactly like an undeclared template that receives a
         * manual-commit one. With the declaration in place, the transactional path performs no auto-commit flips
         * and releases connections in their arrived state; non-transactional connections get auto-commit enabled
         * while Storm uses them and restored before release, so each statement still commits.
         *
         * Cannot be combined with a custom connection provider and only valid for data source backed templates;
         * [build] fails fast otherwise.
         *
         * @since 1.14
         */
        public fun manualCommitConnections(): Builder = apply { core.manualCommitConnections() }

        /**
         * Sets the exception mapper that maps failures raised during query execution to the runtime exception thrown
         * to the caller.
         */
        public fun exceptionMapper(exceptionMapper: st.orm.spi.ExceptionMapper): Builder = apply { core.exceptionMapper(exceptionMapper) }

        /**
         * Sets the query observer that is notified of query executions performed by the template.
         */
        public fun queryObserver(queryObserver: st.orm.spi.QueryObserver): Builder = apply { core.queryObserver(queryObserver) }

        /**
         * Sets the SQL commenter that appends per-execution comment content to statements, such as the
         * current trace context. Note that per-execution content defeats prepared statement caching.
         *
         * @since 1.13
         */
        public fun sqlCommenter(sqlCommenter: st.orm.spi.SqlCommenter): Builder = apply { core.sqlCommenter(sqlCommenter) }

        /**
         * Builds the ORM template.
         */
        public fun build(): ORMTemplate = ORMTemplateImpl(core.build())
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
public val DataSource.orm: ORMTemplate
    get() = ORMTemplate.of(this)

/**
 * Creates an [ORMTemplate] from this [Connection] with default configuration.
 *
 * Requires `import st.orm.template.orm`.
 */
public val Connection.orm: ORMTemplate
    get() = ORMTemplate.of(this)

/**
 * Creates an [ORMTemplate] from this [DataSource] with a custom [TemplateDecorator].
 *
 * The decorator wraps the template pipeline, allowing cross-cutting concerns such as logging or SQL rewriting.
 *
 * Requires `import st.orm.template.orm`.
 */
public fun DataSource.orm(decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, decorator)

/**
 * Creates an [ORMTemplate] from this [Connection] with a custom [TemplateDecorator].
 *
 * Requires `import st.orm.template.orm`.
 */
public fun Connection.orm(decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, decorator)

/**
 * Creates an [ORMTemplate] from this [DataSource] with a custom [StormConfig].
 *
 * Requires `import st.orm.template.orm`.
 */
public fun DataSource.orm(config: StormConfig): ORMTemplate = ORMTemplate.of(this, config)

/**
 * Creates an [ORMTemplate] from this [Connection] with a custom [StormConfig].
 *
 * Requires `import st.orm.template.orm`.
 */
public fun Connection.orm(config: StormConfig): ORMTemplate = ORMTemplate.of(this, config)

/**
 * Creates an [ORMTemplate] from this [DataSource] with a custom [StormConfig] and [TemplateDecorator].
 *
 * Requires `import st.orm.template.orm`.
 */
public fun DataSource.orm(config: StormConfig, decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, config, decorator)

/**
 * Creates an [ORMTemplate] from this [Connection] with a custom [StormConfig] and [TemplateDecorator].
 *
 * Requires `import st.orm.template.orm`.
 */
public fun Connection.orm(config: StormConfig, decorator: (TemplateDecorator) -> TemplateDecorator): ORMTemplate = ORMTemplate.of(this, config, decorator)
