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

import jakarta.annotation.Nonnull;
import java.sql.Connection;
import java.util.List;
import java.util.function.UnaryOperator;
import javax.sql.DataSource;
import st.orm.Data;
import st.orm.EntityCallback;
import st.orm.StormConfig;
import st.orm.mapping.TemplateDecorator;
import st.orm.repository.EntityRepository;
import st.orm.repository.ProjectionRepository;
import st.orm.repository.RepositoryLookup;
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
    ORMTemplate withEntityCallback(@Nonnull EntityCallback<?> callback);

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
     * @throws st.orm.PersistenceException if the template does not support schema validation.
     * @since 1.9
     */
    List<String> validateSchema();

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
    List<String> validateSchema(@Nonnull Iterable<Class<? extends Data>> types);

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
     * Validates the specified types and throws if any errors are found.
     *
     * <p>This method requires a DataSource-backed template. Templates created from a raw
     * {@link Connection} or {@code EntityManager} do not support schema validation.</p>
     *
     * @param types the entity and projection types to validate.
     * @throws st.orm.PersistenceException if validation fails or the template does not support schema validation.
     * @since 1.9
     */
    void validateSchemaOrThrow(@Nonnull Iterable<Class<? extends Data>> types);

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
    static ORMTemplate of(@Nonnull DataSource dataSource) {
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(dataSource));
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
    static ORMTemplate of(@Nonnull Connection connection) {
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
    static ORMTemplate of(@Nonnull DataSource dataSource, @Nonnull UnaryOperator<TemplateDecorator> decorator) {
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
    static ORMTemplate of(@Nonnull Connection connection, @Nonnull UnaryOperator<TemplateDecorator> decorator) {
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
    static ORMTemplate of(@Nonnull DataSource dataSource, @Nonnull StormConfig config) {
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
    static ORMTemplate of(@Nonnull DataSource dataSource, @Nonnull StormConfig config,
                          @Nonnull UnaryOperator<TemplateDecorator> decorator) {
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
    static ORMTemplate of(@Nonnull Connection connection, @Nonnull StormConfig config) {
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
    static ORMTemplate of(@Nonnull Connection connection, @Nonnull StormConfig config,
                          @Nonnull UnaryOperator<TemplateDecorator> decorator) {
        return new ORMTemplateImpl(st.orm.core.template.ORMTemplate.of(connection, config, decorator));
    }
}
