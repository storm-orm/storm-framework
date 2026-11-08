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
package st.orm.core.template.impl;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.core.template.SqlDialect;

/**
 * The database schemas read for a data source, kept for the lifetime of the data source and shared by everything
 * that reads a column's limits or a constraint's columns: the resolution of an integrity violation to its fields,
 * the checks of {@code validate}, and schema validation, which publishes what it read so neither reads it again.
 *
 * <p>A schema is read on first use, over a connection of its own, and never on a write path: a write that succeeds
 * reads nothing. A read that fails leaves the cache as it was and is reported to the caller, which decides what to
 * do without it.</p>
 *
 * @since 1.16
 */
public final class SchemaCache {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.validation");

    private static final Map<DataSource, ConcurrentMap<String, DatabaseSchema>> SCHEMAS =
            Collections.synchronizedMap(new WeakHashMap<>());

    /** The data sources whose schema could not be read: read once, reported once, not retried. */
    private static final Set<DataSource> UNREADABLE = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));

    private SchemaCache() {}

    /**
     * Returns the schema an entity's tables live in, reading it on first use, or {@code null} when it cannot be
     * read. A read that fails is reported once per data source and not tried again: the features that need the
     * schema degrade, an integrity violation to the names the error carries and {@code validate} to the null check,
     * and a caller is told why in the log rather than paying for a read on every failure.
     *
     * @param dataSource the data source to read over.
     * @param dialect the dialect, whose strategies read the constraints and sequences.
     * @param entitySchema the schema the entity names, or an empty string for the connection's default schema.
     * @return the schema, or {@code null} when it cannot be read.
     */
    static @Nullable DatabaseSchema readOrNull(DataSource dataSource, SqlDialect dialect, String entitySchema) {
        var cached = cached(dataSource, entitySchema);
        if (cached != null) {
            return cached;
        }
        if (UNREADABLE.contains(dataSource)) {
            return null;
        }
        try {
            return read(dataSource, dialect, entitySchema);
        } catch (SQLException | RuntimeException e) {
            if (UNREADABLE.add(dataSource)) {
                LOGGER.warn("Cannot read the database schema over {}; an integrity violation resolves to no field and validate checks nulls alone.", dataSource, e);
            }
            return null;
        }
    }

    /**
     * Returns the schema an entity's tables live in, reading it on first use.
     *
     * @param dataSource the data source to read over.
     * @param dialect the dialect, whose strategies read the constraints and sequences.
     * @param entitySchema the schema the entity names, or an empty string for the connection's default schema.
     * @return the schema.
     * @throws SQLException if the schema cannot be read.
     */
    static DatabaseSchema read(DataSource dataSource, SqlDialect dialect, String entitySchema) throws SQLException {
        var cached = cached(dataSource, entitySchema);
        if (cached != null) {
            return cached;
        }
        try (Connection connection = dataSource.getConnection()) {
            var schema = resolve(connection, dialect, connection.getCatalog(), connection.getSchema(), entitySchema,
                    new ConcurrentHashMap<>());
            publish(dataSource, entitySchema, schema);
            return schema;
        }
    }

    /**
     * Returns the schema an entity's tables live in if it has been read, without reading it.
     *
     * @param dataSource the data source.
     * @param entitySchema the schema the entity names, or an empty string for the connection's default schema.
     * @return the schema, or {@code null} when it has not been read.
     */
    static @Nullable DatabaseSchema cached(DataSource dataSource, String entitySchema) {
        var schemas = SCHEMAS.get(dataSource);
        return schemas == null ? null : schemas.get(entitySchema);
    }

    /**
     * Keeps a schema that was read for the data source, so later uses read nothing.
     *
     * @param dataSource the data source the schema was read over.
     * @param entitySchema the schema the entities name, or an empty string for the connection's default schema.
     * @param schema the schema.
     */
    static void publish(DataSource dataSource, String entitySchema, DatabaseSchema schema) {
        SCHEMAS.computeIfAbsent(dataSource, ignore -> new ConcurrentHashMap<>()).put(entitySchema, schema);
    }

    /**
     * Reads the schema an entity names over the connection, or the connection's default schema when the entity
     * names none, through the cache given.
     *
     * @param connection the connection to read over.
     * @param dialect the dialect, whose strategies read the constraints and sequences.
     * @param defaultCatalog the connection's catalog.
     * @param defaultSchema the connection's schema.
     * @param entitySchema the schema the entity names, or an empty string for the default schema.
     * @param cache the schemas read so far, by the schema's key.
     * @return the schema.
     * @throws SQLException if the schema cannot be read.
     */
    static DatabaseSchema resolve(Connection connection,
                                  SqlDialect dialect,
                                  @Nullable String defaultCatalog,
                                  @Nullable String defaultSchema,
                                  String entitySchema,
                                  Map<String, DatabaseSchema> cache) throws SQLException {
        // Use the entity's schema if specified, otherwise fall back to the connection's default schema.
        String schemaKey = entitySchema.isEmpty()
                ? (defaultSchema != null ? defaultSchema : "")
                : entitySchema;
        DatabaseSchema cached = cache.get(schemaKey);
        if (cached != null) {
            return cached;
        }
        String catalog;
        String schemaPattern;
        if (!entitySchema.isEmpty() && dialect.useCatalogAsSchema()) {
            // Database uses catalogs as schemas. The entity's schema represents a
            // database name, which maps to the JDBC catalog.
            catalog = entitySchema;
            schemaPattern = null;
        } else {
            catalog = defaultCatalog;
            schemaPattern = entitySchema.isEmpty() ? defaultSchema : entitySchema;
        }
        DatabaseSchema databaseSchema = DatabaseSchema.read(connection, catalog, schemaPattern,
                DatabaseSchema.Discovery.of(dialect));
        cache.put(schemaKey, databaseSchema);
        return databaseSchema;
    }
}
