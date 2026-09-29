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


/**
 * Decorator used for integrations with applications and frameworks.
 *
 * @since 1.6
 */
public interface TemplateDecorator {

    /**
     * Returns a new prepared statement template with the specified table name resolver.
     *
     * @param tableNameResolver the table name resolver.
     * @return a new prepared statement template.
     */
    TemplateDecorator withTableNameResolver(TableNameResolver tableNameResolver);

    /**
     * Returns a new prepared statement template with the specified schema resolver.
     *
     * @param schemaResolver the schema resolver.
     * @return a new prepared statement template.
     * @since 1.15
     */
    TemplateDecorator withSchemaResolver(SchemaResolver schemaResolver);

    /**
     * Returns a new prepared statement template with the specified column name resolver.
     *
     * @param columnNameResolver the column name resolver.
     * @return a new prepared statement template.
     */
    TemplateDecorator withColumnNameResolver(ColumnNameResolver columnNameResolver);

    /**
     * Returns a new prepared statement template with the specified foreign key resolver.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return a new prepared statement template.
     */
    TemplateDecorator withForeignKeyResolver(ForeignKeyResolver foreignKeyResolver);
}
