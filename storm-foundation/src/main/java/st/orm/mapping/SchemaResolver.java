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
package st.orm.mapping;

import static java.util.Objects.requireNonNull;

import java.util.Map;

/**
 * Resolves the schema a table lives in, per template.
 *
 * <p>The schema an entity declares with {@code @DbTable(schema = ...)} is fixed at compile time, while the schema a
 * table actually lives in can depend on the database a template connects to: two templates may reach the same tables
 * under different schema names. A template's schema resolver maps the declared schema, or the absence of one, to the
 * schema its statements address.</p>
 *
 * @since 1.15
 */
@FunctionalInterface
public interface SchemaResolver {

    /**
     * The default schema resolver, which addresses the schema the entity declares.
     */
    SchemaResolver DEFAULT = (type, schema) -> schema;

    /**
     * Returns a schema resolver that maps declared schemas to other names, and leaves every schema it does not name
     * as declared. Resolvers created from equal mappings are equal, so templates configured alike share the models
     * Storm caches for them.
     *
     * @param schemas the declared schemas, mapped to the schemas to address instead.
     * @return the schema resolver.
     */
    static SchemaResolver mapping(Map<String, String> schemas) {
        return new SchemaMapping(Map.copyOf(requireNonNull(schemas, "schemas")));
    }

    /**
     * Resolves the schema of the table the given type maps to.
     *
     * @param type the entity or projection type.
     * @param schema the schema the type declares, or an empty string if it declares none.
     * @return the schema to address, or an empty string to address the table without a schema.
     */
    String resolveSchema(Class<?> type, String schema);
}
