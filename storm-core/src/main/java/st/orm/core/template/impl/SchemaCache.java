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

import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;

/**
 * The database schemas loaded for a data source, kept for the lifetime of the data source and shared by what needs a
 * column's limits or a constraint's columns: the resolution of an integrity violation to its fields and the checks of
 * {@code validate}.
 *
 * <p>Schema validation and {@code loadSchema()} publish what they read, as they do for the index catalog; nothing
 * else reads the schema, so no statement, failed or not, touches the database's metadata. A later load replaces what
 * an earlier one kept for the same schema.</p>
 *
 * @since 1.16
 */
final class SchemaCache {

    private static final Map<DataSource, ConcurrentMap<String, DatabaseSchema>> SCHEMAS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private SchemaCache() {}

    /**
     * Returns the schema an entity's tables live in, if it has been loaded.
     *
     * @param dataSource the data source.
     * @param entitySchema the schema the entity names, or an empty string for the connection's default schema.
     * @return the schema, or {@code null} when it has not been loaded.
     */
    static @Nullable DatabaseSchema cached(DataSource dataSource, String entitySchema) {
        var schemas = SCHEMAS.get(dataSource);
        return schemas == null ? null : schemas.get(entitySchema);
    }

    /**
     * Keeps a schema that was read for the data source.
     *
     * @param dataSource the data source the schema was read over.
     * @param entitySchema the schema the entities name, or an empty string for the connection's default schema.
     * @param schema the schema.
     */
    static void publish(DataSource dataSource, String entitySchema, DatabaseSchema schema) {
        SCHEMAS.computeIfAbsent(dataSource, ignore -> new ConcurrentHashMap<>()).put(entitySchema, schema);
    }
}
