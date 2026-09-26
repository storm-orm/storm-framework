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
package st.orm.template;

import java.sql.Connection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import st.orm.Data;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.mapping.TemplateDecorator;
import st.orm.repository.EntityRepository;
import st.orm.repository.ProjectionRepository;
import st.orm.repository.RepositoryLookup;
import st.orm.template.impl.BuilderImpl;
import st.orm.template.impl.Engine;
import st.orm.template.impl.ORMTemplateImpl;

/**
 * The primary entry point for Storm's ORM functionality, combining SQL template query construction with
 * repository access.
 *
 * <p>{@code ORMTemplate} extends both {@link QueryTemplate} (for constructing and executing SQL queries) and
 * {@link RepositoryLookup} (for obtaining type-safe {@link EntityRepository} and {@link ProjectionRepository}
 * instances). It is the central interface from which all database operations originate.</p>
 *
 * <p>Instances are created using the static factory methods {@link #of(javax.sql.DataSource)} or
 * {@link #of(java.sql.Connection)}, or via the convenience methods in the {@link Templates} class.
 * For JPA-based usage, see {@code JpaTemplate}.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * ORMTemplate orm = ORMTemplate.of(dataSource);
 *
 * // Repository-based access
 * EntityRepository<User, Integer> users = orm.entity(User.class);
 * Optional<User> user = users.findById(42);
 *
 * // Template-based query
 * List<User> result = orm.query(RAW."""
 *         SELECT \{User.class}
 *         FROM \{User.class}
 *         WHERE \{User_.name} = \{"Alice"}""")
 *     .getResultList(User.class);
 * }</pre>
 *
 * @see Templates
 * @see EntityRepository
 * @see ProjectionRepository
 */
public interface ORMTemplate extends QueryTemplate, RepositoryLookup {

    /**
     * Returns a new {@code ORMTemplate} with the specified entity callback added.
     *
     * <p>The returned template shares the same underlying connection and configuration, but applies the given
     * callback to entity lifecycle operations (insert, update, delete) performed through its repositories. The
     * callback is only invoked for entities matching its type parameter. Multiple callbacks can be registered by
     * chaining calls to this method.</p>
     *
     * @param callback the entity callback to add; must not be {@code null}.
     * @return a new {@code ORMTemplate} with the callback added.
     * @since 1.9
     */
    ORMTemplate withEntityCallback(EntityCallback<?> callback);

    /**
     * Returns a new {@code ORMTemplate} with the specified entity callbacks added.
     *
     * <p>The returned template shares the same underlying connection and configuration, but applies the given
     * callbacks to entity lifecycle operations (insert, update, delete) performed through its repositories. Each
     * callback is only invoked for entities matching its type parameter.</p>
     *
     * @param callbacks the entity callbacks to add; must not be {@code null}.
     * @return a new {@code ORMTemplate} with the callbacks added.
     * @since 1.9
     */
    ORMTemplate withEntityCallbacks(List<EntityCallback<?>> callbacks);

    /**
     * Validates all discovered entity and projection types against the database schema.
     *
     * <p>Logs each validation error and returns the list of error messages. On success, logs a
     * confirmation message and returns an empty list.</p>
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @return the list of validation error messages (empty on success).
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    List<String> validateSchema();

    /**
     * Validates discovered types matching the filter against the database schema.
     *
     * <p>Discovers all entity and projection types via the classpath index, applies the given filter, and validates
     * the matching types. This is useful when a single application connects to multiple datasources and only a
     * subset of types is reachable from each connection.</p>
     *
     * <p>Logs each validation error and returns the list of error messages. On success, logs a
     * confirmation message and returns an empty list.</p>
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @param filter predicate to select which discovered types to validate.
     * @return the list of validation error messages (empty on success).
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.11
     */
    List<String> validateSchema(Predicate<Class<? extends Data>> filter);

    /**
     * Validates the specified types against the database schema.
     *
     * <p>Logs each validation error and returns the list of error messages. On success, logs a
     * confirmation message and returns an empty list.</p>
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @param types the entity and projection types to validate.
     * @return the list of validation error messages (empty on success).
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    List<String> validateSchema(Iterable<Class<? extends Data>> types);

    /**
     * Validates all discovered types and throws if any errors are found.
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    void validateSchemaOrThrow();

    /**
     * Validates discovered types matching the filter and throws if any errors are found.
     *
     * <p>Discovers all entity and projection types via the classpath index, applies the given filter, and validates
     * the matching types. This is useful when a single application connects to multiple datasources and only a
     * subset of types is reachable from each connection.</p>
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @param filter predicate to select which discovered types to validate.
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.11
     */
    void validateSchemaOrThrow(Predicate<Class<? extends Data>> filter);

    /**
     * Validates the specified types and throws if any errors are found.
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @param types the entity and projection types to validate.
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    void validateSchemaOrThrow(Iterable<Class<? extends Data>> types);

    /**
     * Returns an {@link ORMTemplate} for use with JDBC.
     *
     * <p>This method creates an ORM repository template using the provided {@link DataSource}.
     * It allows you to perform database operations using JDBC in a type-safe manner.
     *
     * <p>Example usage:
     * <pre>{@code
     * DataSource dataSource = ...;
     * ORMTemplate orm = ORMTemplate.of(dataSource);
     * List<MyTable> otherTables = orm.query(RAW."""
     *         SELECT \{MyTable.class}
     *         FROM \{MyTable.class}
     *         WHERE \{MyTable_.name} = \{"ABC"}""")
     *     .getResultList(MyTable.class);
     * }</pre>
     *
     * @param dataSource the {@link DataSource} to use for database operations; must not be {@code null}.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(DataSource dataSource) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource));
    }

    /**
     * Returns the template of the operation that fired the entity callback running on this thread.
     *
     * <p>An {@link EntityCallback} receives the entity and nothing else, so a callback that performs database work
     * of its own reaches its template here rather than capturing one:</p>
     *
     * <pre>{@code
     * public class ArticleHistoryCallback implements EntityCallback<Article> {
     *
     *     public void afterUpdate(List<Article> articles) {
     *         ORMTemplate.current().writeSet().insert(articles.stream().map(ArticleHistory::of).toList());
     *     }
     * }
     * }</pre>
     *
     * <p>The template returned is the one the write is running on, so the work goes to the same database, over the
     * same connection, inside the same transaction, whichever template fired the callback. A callback that captures
     * a template instead makes that choice once, at construction, and a callback registered on several templates has
     * no correct choice to make.</p>
     *
     * <p>Callbacks never fire recursively, so the work performed through this template fires none of its own.</p>
     *
     * @return the template the operation runs on; never {@code null}.
     * @throws PersistenceException if no entity callback is executing on this thread.
     * @since 1.14
     */
    static ORMTemplate current() {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.current());
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC.
     *
     * <p>This method creates an ORM repository template using the provided {@link Connection}.
     * It allows you to perform database operations using JDBC in a type-safe manner.</p>
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * <p>Example usage:
     * <pre>{@code
     * try (Connection connection = ...) {
     *     ORMTemplate orm = ORMTemplate.of(connection);
     *     List<MyTable> otherTables = orm.query(RAW."""
     *             SELECT \{MyTable.class}
     *             FROM \{MyTable.class}
     *             WHERE \{MyTable_.name} = \{"ABC"}""")
     *         .getResultList(MyTable.class)
     * }
     * }</pre>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(Connection connection) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection));
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, with a custom template decorator.
     *
     * <p>This method creates an ORM repository template using the provided {@link DataSource} and applies
     * the specified decorator to customize template processing behavior.
     *
     * @param dataSource the {@link DataSource} to use for database operations; must not be {@code null}.
     * @param decorator a function that transforms the {@link TemplateDecorator} to customize template processing.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(DataSource dataSource, UnaryOperator<TemplateDecorator> decorator) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, decorator));
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, with a custom template decorator.
     *
     * <p>This method creates an ORM repository template using the provided {@link Connection} and applies
     * the specified decorator to customize template processing behavior.</p>
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @param decorator a function that transforms the {@link TemplateDecorator} to customize template processing.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(Connection connection, UnaryOperator<TemplateDecorator> decorator) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, decorator));
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, configured with the provided {@link StormConfig}.
     *
     * <p>The provided configuration is applied to the template instance, not as a process-wide default.</p>
     *
     * @param dataSource the {@link DataSource} to use for database operations; must not be {@code null}.
     * @param config the Storm configuration to apply; must not be {@code null}.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(DataSource dataSource, StormConfig config) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, config));
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, configured with the provided {@link StormConfig} and a custom
     * template decorator.
     *
     * @param dataSource the {@link DataSource} to use for database operations; must not be {@code null}.
     * @param config the Storm configuration to apply; must not be {@code null}.
     * @param decorator a function that transforms the {@link TemplateDecorator} to customize template processing.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(DataSource dataSource, StormConfig config,
                          UnaryOperator<TemplateDecorator> decorator) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource, config, decorator));
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, configured with the provided {@link StormConfig}.
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @param config the Storm configuration to apply; must not be {@code null}.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(Connection connection, StormConfig config) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config));
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, configured with the provided {@link StormConfig} and a custom
     * template decorator.
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @param config the Storm configuration to apply; must not be {@code null}.
     * @param decorator a function that transforms the {@link TemplateDecorator} to customize template processing.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(Connection connection, StormConfig config,
                          UnaryOperator<TemplateDecorator> decorator) {
        Engine.require();
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config, decorator));
    }

    /**
     * Returns a builder for constructing an {@link ORMTemplate} with instance-scoped integration strategies.
     *
     * <p>The builder is the injection point for platform services: integrations hand their connection provider,
     * transaction template provider, exception mapper and query observer to the template they construct, instead of
     * relying on JVM-global discovery.</p>
     *
     * @param dataSource the {@link DataSource} to use for database operations; must not be {@code null}.
     * @return a builder for constructing the ORM template.
     * @since 1.13
     */
    static Builder builder(DataSource dataSource) {
        Engine.require();
        return new BuilderImpl(st.orm.core.template.ORMTemplate.builder(dataSource));
    }

    /**
     * Returns a builder for constructing an {@link ORMTemplate} backed by a single connection, with instance-scoped
     * integration strategies.
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @return a builder for constructing the ORM template.
     * @since 1.13
     */
    static Builder builder(Connection connection) {
        Engine.require();
        return new BuilderImpl(st.orm.core.template.ORMTemplate.builder(connection));
    }

    /**
     * Builder for constructing an {@link ORMTemplate} with instance-scoped integration strategies.
     *
     * @since 1.13
     */
    interface Builder {

        /**
         * Sets the Storm configuration to apply to the template instance.
         *
         * @param config the Storm configuration; must not be {@code null}.
         * @return this builder.
         */
        Builder config(StormConfig config);

        /**
         * Sets a function that transforms the {@link TemplateDecorator} to customize template processing.
         *
         * @param decorator the decorator function; must not be {@code null}.
         * @return this builder.
         */
        Builder decorator(UnaryOperator<TemplateDecorator> decorator);

        /**
         * Declares that the data source hands out connections with auto-commit disabled.
         *
         * <p>The declared mode is verified in both directions: a declared template that receives an auto-commit
         * connection fails fast naming the misdeclaration, exactly like an undeclared template that receives a
         * manual-commit one. With the declaration in place, the transactional path performs no auto-commit flips
         * and releases connections in their arrived state; non-transactional connections get auto-commit enabled
         * while Storm uses them and restored before release, so each statement still commits.</p>
         *
         * <p>Cannot be combined with a custom connection provider and only valid for data source backed templates;
         * {@link #build()} fails fast otherwise.</p>
         *
         * @return this builder.
         * @since 1.14
         */
        Builder manualCommitConnections();

        /**
         * Sets the exception mapper that maps failures raised during query execution to the runtime exception thrown
         * to the caller.
         *
         * @param exceptionMapper the exception mapper; must not be {@code null}.
         * @return this builder.
         */
        Builder exceptionMapper(st.orm.spi.ExceptionMapper exceptionMapper);

        /**
         * Sets the query observer that is notified of query executions performed by the template.
         *
         * @param queryObserver the query observer; must not be {@code null}.
         * @return this builder.
         */
        Builder queryObserver(st.orm.spi.QueryObserver queryObserver);

        /**
         * Sets the SQL commenter that appends per-execution comment content to statements, such as the
         * current trace context. Note that per-execution content defeats prepared statement caching.
         *
         * @param sqlCommenter the SQL commenter; must not be {@code null}.
         * @return this builder.
         * @since 1.13
         */
        Builder sqlCommenter(st.orm.spi.SqlCommenter sqlCommenter);

        /**
         * Builds the ORM template.
         *
         * @return the ORM template.
         */
        ORMTemplate build();
    }
}
