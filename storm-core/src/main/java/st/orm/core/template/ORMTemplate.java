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
package st.orm.core.template;

import static java.util.Objects.requireNonNull;

import java.sql.Connection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.repository.RepositoryLookup;
import st.orm.core.repository.impl.CallbackTemplate;
import st.orm.core.spi.ConnectionProvider;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.Providers;
import st.orm.core.spi.TransactionTemplateProvider;
import st.orm.core.template.impl.PreparedStatementTemplateImpl;
import st.orm.mapping.TemplateDecorator;
import st.orm.spi.ExceptionMapper;
import st.orm.spi.QueryObserver;
import st.orm.spi.SqlCommenter;

/**
 * <p>The {@code ORMTemplate} is the primary interface that extends the {@code QueryTemplate} and
 * {@code RepositoryLooking} interfaces, providing access to both the SQL Template engine and ORM logic.
 *
 * @see Templates
 * @see EntityRepository
 * @see ProjectionRepository
 */
public interface ORMTemplate extends QueryTemplate, RepositoryLookup {

    /**
     * Returns the template of the operation that fired the entity callback running on this thread.
     *
     * <p>An {@link EntityCallback} receives the entity and nothing else, so a callback that performs database work
     * of its own reaches its template here rather than capturing one. The template returned is the one the write is
     * running on, so the work goes to the same database, over the same connection, inside the same transaction,
     * whichever template fired the callback. A callback that captures a template instead makes that choice once, at
     * construction, and a callback registered on several templates has no correct choice to make.</p>
     *
     * <p>Callbacks never fire recursively, so the work performed through this template fires none of its own.</p>
     *
     * @return the template the operation runs on; never {@code null}.
     * @throws PersistenceException if no entity callback is executing on this thread.
     * @since 1.14
     */
    static ORMTemplate current() {
        return CallbackTemplate.current();
    }

    /**
     * Returns the configuration associated with this template.
     *
     * @return the Storm configuration; never {@code null}.
     * @since 1.9
     */
    StormConfig config();

    /**
     * Returns the entity callbacks associated with this template.
     *
     * @return an unmodifiable list of entity callbacks; never {@code null}.
     * @since 1.9
     */
    default List<EntityCallback<?>> entityCallbacks() {
        return List.of();
    }

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
    default ORMTemplate withEntityCallback(EntityCallback<?> callback) {
        return withEntityCallbacks(List.of(callback));
    }

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
     * @throws PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    default List<String> validateSchema() {
        throw new PersistenceException("Schema validation is not supported by this template.");
    }

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
     * @throws PersistenceException if the template does not support schema validation.
     * @since 1.11
     */
    default List<String> validateSchema(Predicate<Class<? extends Data>> filter) {
        throw new PersistenceException("Schema validation is not supported by this template.");
    }

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
     * @throws PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    default List<String> validateSchema(Iterable<Class<? extends Data>> types) {
        throw new PersistenceException("Schema validation is not supported by this template.");
    }

    /**
     * Validates all discovered types and throws if any errors are found.
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @throws PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    default void validateSchemaOrThrow() {
        throw new PersistenceException("Schema validation is not supported by this template.");
    }

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
     * @throws PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.11
     */
    default void validateSchemaOrThrow(Predicate<Class<? extends Data>> filter) {
        throw new PersistenceException("Schema validation is not supported by this template.");
    }

    /**
     * Validates the specified types and throws if any errors are found.
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @param types the entity and projection types to validate.
     * @throws PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    default void validateSchemaOrThrow(Iterable<Class<? extends Data>> types) {
        throw new PersistenceException("Schema validation is not supported by this template.");
    }

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
        return new PreparedStatementTemplateImpl(dataSource).toORM();
    }

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
    static ORMTemplate of(DataSource dataSource, UnaryOperator<TemplateDecorator> decorator) {
        var template = new PreparedStatementTemplateImpl(dataSource);
        var decorated = decorator.apply(template);
        if (!(decorated instanceof PreparedStatementTemplateImpl)) {
            throw new PersistenceException("Decorator must return the same template type.");
        }
        return ((PreparedStatementTemplateImpl) decorated).toORM();
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
        return new PreparedStatementTemplateImpl(connection).toORM();
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
    static ORMTemplate of(Connection connection,
                          UnaryOperator<TemplateDecorator> decorator) {
        var template = new PreparedStatementTemplateImpl(connection);
        var decorated = decorator.apply(template);
        if (!(decorated instanceof PreparedStatementTemplateImpl)) {
            throw new PersistenceException("Decorator must return the same template type.");
        }
        return ((PreparedStatementTemplateImpl) decorated).toORM();
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
        return new PreparedStatementTemplateImpl(dataSource, config).toORM();
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
        var template = new PreparedStatementTemplateImpl(dataSource, config);
        var decorated = decorator.apply(template);
        if (!(decorated instanceof PreparedStatementTemplateImpl)) {
            throw new PersistenceException("Decorator must return the same template type.");
        }
        return ((PreparedStatementTemplateImpl) decorated).toORM();
    }

    /**
     * Returns an {@link ORMTemplate} for use with JDBC, configured with the provided {@link StormConfig}.
     *
     * <p>The provided configuration is applied to the template instance, not as a process-wide default.</p>
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @param config the Storm configuration to apply; must not be {@code null}.
     * @return an {@link ORMTemplate} configured for use with JDBC.
     */
    static ORMTemplate of(Connection connection, StormConfig config) {
        return new PreparedStatementTemplateImpl(connection, config).toORM();
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
        var template = new PreparedStatementTemplateImpl(connection, config);
        var decorated = decorator.apply(template);
        if (!(decorated instanceof PreparedStatementTemplateImpl)) {
            throw new PersistenceException("Decorator must return the same template type.");
        }
        return ((PreparedStatementTemplateImpl) decorated).toORM();
    }

    /**
     * Returns a builder for constructing an {@link ORMTemplate} with instance-scoped integration strategies.
     *
     * <p>The builder is the injection point for platform services: integrations hand their connection provider,
     * transaction template provider, exception mapper and query observer to the template they construct, instead of
     * relying on JVM-global discovery. Strategies that are not set fall back to {@code ServiceLoader} discovery for
     * the connection and transaction template providers, and to the built-in defaults for the exception mapper and
     * query observer.</p>
     *
     * <p>Example usage:
     * <pre>{@code
     * ORMTemplate orm = ORMTemplate.builder(dataSource)
     *         .config(config)
     *         .connectionProvider(connectionProvider)
     *         .transactionTemplateProvider(transactionTemplateProvider)
     *         .build();
     * }</pre>
     *
     * @param dataSource the {@link DataSource} to use for database operations; must not be {@code null}.
     * @return a builder for constructing the ORM template.
     * @since 1.13
     */
    static Builder builder(DataSource dataSource) {
        return new Builder(requireNonNull(dataSource, "dataSource"), null);
    }

    /**
     * Returns a builder for constructing an {@link ORMTemplate} backed by a single connection, with instance-scoped
     * integration strategies.
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage. Connection backed
     * templates never acquire connections themselves, so no connection provider can be configured.</p>
     *
     * @param connection the {@link Connection} to use for database operations; must not be {@code null}.
     * @return a builder for constructing the ORM template.
     * @since 1.13
     */
    static Builder builder(Connection connection) {
        return new Builder(null, requireNonNull(connection, "connection"));
    }

    /**
     * Builder for constructing an {@link ORMTemplate} with instance-scoped integration strategies.
     *
     * @since 1.13
     */
    final class Builder {
        private final @Nullable DataSource dataSource;
        private final @Nullable Connection connection;
        private StormConfig config = StormConfig.defaults();
        private @Nullable UnaryOperator<TemplateDecorator> decorator;
        private @Nullable ConnectionProvider connectionProvider;
        private boolean manualCommitConnections;
        private @Nullable TransactionTemplateProvider transactionTemplateProvider;
        private @Nullable ExceptionMapper exceptionMapper;
        private @Nullable QueryObserver queryObserver;
        private @Nullable SqlCommenter sqlCommenter;

        private Builder(@Nullable DataSource dataSource, @Nullable Connection connection) {
            this.dataSource = dataSource;
            this.connection = connection;
        }

        /**
         * Sets the Storm configuration to apply to the template instance.
         *
         * @param config the Storm configuration; must not be {@code null}.
         * @return this builder.
         */
        public Builder config(StormConfig config) {
            this.config = requireNonNull(config, "config");
            return this;
        }

        /**
         * Sets a function that transforms the {@link TemplateDecorator} to customize template processing.
         *
         * @param decorator the decorator function; must not be {@code null}.
         * @return this builder.
         */
        public Builder decorator(UnaryOperator<TemplateDecorator> decorator) {
            this.decorator = requireNonNull(decorator, "decorator");
            return this;
        }

        /**
         * Sets the connection provider used by the template to acquire and release connections.
         *
         * <p>Only valid for data source backed templates; {@link #build()} fails fast otherwise.</p>
         *
         * @param connectionProvider the connection provider; must not be {@code null}.
         * @return this builder.
         */
        public Builder connectionProvider(ConnectionProvider connectionProvider) {
            this.connectionProvider = requireNonNull(connectionProvider, "connectionProvider");
            return this;
        }

        /**
         * Declares that the data source hands out connections with auto-commit disabled.
         *
         * <p>Storm-managed transactions require connections to arrive from the data source in auto-commit mode
         * and fail fast otherwise, since a manual-commit arrival is indistinguishable from a connection carrying
         * an unfinished transaction. Pools that are deliberately configured to hand out manual-commit
         * connections, for example to save the two auto-commit round trips per transaction, declare that mode
         * here.</p>
         *
         * <p>The declared mode is verified in both directions: a declared template that receives an auto-commit
         * connection fails fast naming the misdeclaration, exactly like an undeclared template that receives a
         * manual-commit one. With the declaration in place, the transactional path performs no auto-commit flips
         * and releases connections in their arrived state; non-transactional connections get auto-commit enabled
         * while Storm uses them and restored before release, so each statement still commits.</p>
         *
         * <p>The declaration configures Storm's built-in JDBC connection handling and cannot be combined with a
         * custom {@link #connectionProvider(ConnectionProvider) connection provider}; a provider that manages
         * connections itself carries its own arrival-state contract, such as
         * {@code new JdbcConnectionProviderImpl(true)}. Only valid for data source backed templates;
         * {@link #build()} fails fast otherwise.</p>
         *
         * @return this builder.
         * @since 1.14
         */
        public Builder manualCommitConnections() {
            this.manualCommitConnections = true;
            return this;
        }

        /**
         * Sets the transaction template provider used by the template to participate in transactions.
         *
         * <p>Templates that should share transactions must be configured with the <em>same provider instance</em>.</p>
         *
         * @param transactionTemplateProvider the transaction template provider; must not be {@code null}.
         * @return this builder.
         */
        public Builder transactionTemplateProvider(TransactionTemplateProvider transactionTemplateProvider) {
            this.transactionTemplateProvider = requireNonNull(transactionTemplateProvider, "transactionTemplateProvider");
            return this;
        }

        /**
         * Sets the exception mapper that maps failures raised during query execution to the runtime exception thrown
         * to the caller.
         *
         * @param exceptionMapper the exception mapper; must not be {@code null}.
         * @return this builder.
         */
        public Builder exceptionMapper(ExceptionMapper exceptionMapper) {
            this.exceptionMapper = requireNonNull(exceptionMapper, "exceptionMapper");
            return this;
        }

        /**
         * Sets the query observer that is notified of query executions performed by the template.
         *
         * @param queryObserver the query observer; must not be {@code null}.
         * @return this builder.
         */
        public Builder queryObserver(QueryObserver queryObserver) {
            this.queryObserver = requireNonNull(queryObserver, "queryObserver");
            return this;
        }

        /**
         * Sets the SQL commenter that appends per-execution comment content to statements, such as the
         * current trace context. Note that per-execution content defeats prepared statement caching.
         *
         * @param sqlCommenter the SQL commenter; must not be {@code null}.
         * @return this builder.
         * @since 1.13
         */
        public Builder sqlCommenter(SqlCommenter sqlCommenter) {
            this.sqlCommenter = requireNonNull(sqlCommenter, "sqlCommenter");
            return this;
        }

        /**
         * Builds the ORM template.
         *
         * @return the ORM template.
         * @throws PersistenceException if the configuration is invalid, such as a connection provider configured for
         * a connection backed template.
         */
        public ORMTemplate build() {
            PreparedStatementTemplateImpl template;
            if (dataSource != null) {
                var effectiveConnectionProvider = connectionProvider;
                if (manualCommitConnections) {
                    if (connectionProvider != null) {
                        throw new PersistenceException(
                                "A manual-commit declaration cannot be combined with a custom connection " +
                                "provider; the declaration configures Storm's built-in JDBC connection " +
                                "handling. Construct the connection provider in the declared mode instead.");
                    }
                    // The declaration only governs Storm's built-in JDBC connection handling. Silently
                    // replacing a discovered platform provider would change how connections bind to the
                    // platform's transaction subsystem, so a non-default resolution fails fast instead.
                    var discovered = Providers.getConnectionProvider();
                    if (!(discovered instanceof JdbcConnectionProviderImpl)) {
                        throw new PersistenceException(
                                "A manual-commit declaration only applies to Storm's built-in JDBC connection " +
                                "handling, but connection provider discovery resolved " +
                                discovered.getClass().getName() + ". Configure that provider for " +
                                "manual-commit connections instead.");
                    }
                    effectiveConnectionProvider = new JdbcConnectionProviderImpl(true);
                }
                template = new PreparedStatementTemplateImpl(dataSource, config, effectiveConnectionProvider,
                        transactionTemplateProvider, exceptionMapper, queryObserver, sqlCommenter);
            } else {
                if (connectionProvider != null) {
                    throw new PersistenceException(
                            "A connection provider cannot be configured for a connection backed template.");
                }
                if (manualCommitConnections) {
                    throw new PersistenceException(
                            "A manual-commit declaration cannot be configured for a connection backed template.");
                }
                assert connection != null;
                template = new PreparedStatementTemplateImpl(connection, config,
                        transactionTemplateProvider, exceptionMapper, queryObserver, sqlCommenter);
            }
            if (decorator != null) {
                var decorated = decorator.apply(template);
                if (!(decorated instanceof PreparedStatementTemplateImpl decoratedTemplate)) {
                    throw new PersistenceException("Decorator must return the same template type.");
                }
                template = decoratedTemplate;
            }
            return template.toORM();
        }
    }
}
