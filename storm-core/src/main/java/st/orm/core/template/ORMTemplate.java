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

import jakarta.annotation.Nonnull;
import java.sql.Connection;
import java.util.List;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import st.orm.Data;
import st.orm.EntityCallback;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.repository.RepositoryLookup;
import st.orm.core.template.impl.PreparedStatementTemplateImpl;
import st.orm.mapping.TemplateDecorator;

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
    default ORMTemplate withEntityCallback(@Nonnull EntityCallback<?> callback) {
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
    ORMTemplate withEntityCallbacks(@Nonnull List<EntityCallback<?>> callbacks);

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
    default List<String> validateSchema(@Nonnull Predicate<Class<? extends Data>> filter) {
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
    default List<String> validateSchema(@Nonnull Iterable<Class<? extends Data>> types) {
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
    default void validateSchemaOrThrow(@Nonnull Predicate<Class<? extends Data>> filter) {
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
    default void validateSchemaOrThrow(@Nonnull Iterable<Class<? extends Data>> types) {
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
     * ORMTemplate orm = Templates.ORM(dataSource);
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
    static ORMTemplate of(@Nonnull DataSource dataSource) {
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
     * ORMTemplate orm = Templates.ORM(dataSource);
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
    static ORMTemplate of(@Nonnull DataSource dataSource, @Nonnull UnaryOperator<TemplateDecorator> decorator) {
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
     *     ORMTemplate orm = Templates.ORM(connection);
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
    static ORMTemplate of(@Nonnull Connection connection) {
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
     *     ORMTemplate orm = Templates.ORM(connection);
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
    static ORMTemplate of(@Nonnull Connection connection,
                          @Nonnull UnaryOperator<TemplateDecorator> decorator) {
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
    static ORMTemplate of(@Nonnull DataSource dataSource, @Nonnull StormConfig config) {
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
    static ORMTemplate of(@Nonnull DataSource dataSource, @Nonnull StormConfig config,
                          @Nonnull UnaryOperator<TemplateDecorator> decorator) {
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
    static ORMTemplate of(@Nonnull Connection connection, @Nonnull StormConfig config) {
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
    static ORMTemplate of(@Nonnull Connection connection, @Nonnull StormConfig config,
                          @Nonnull UnaryOperator<TemplateDecorator> decorator) {
        var template = new PreparedStatementTemplateImpl(connection, config);
        var decorated = decorator.apply(template);
        if (!(decorated instanceof PreparedStatementTemplateImpl)) {
            throw new PersistenceException("Decorator must return the same template type.");
        }
        return ((PreparedStatementTemplateImpl) decorated).toORM();
    }
}
