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

import java.util.Map;

/**
 * A schema resolver that renames declared schemas and leaves every other schema as declared. Being a record, two
 * mappings of the same schemas are equal.
 *
 * @param schemas the declared schemas, mapped to the schemas to address instead.
 * @since 1.15
 */
record SchemaMapping(Map<String, String> schemas) implements SchemaResolver {

    @Override
    public String resolveSchema(Class<?> type, String schema) {
        return schemas.getOrDefault(schema, schema);
    }
}
