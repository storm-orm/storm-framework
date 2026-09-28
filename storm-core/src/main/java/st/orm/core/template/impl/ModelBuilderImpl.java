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

import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.SqlTemplateException;
import st.orm.core.template.Model;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;

/**
 * Builder for creating a model.
 *
 * @since 1.2
 */
record ModelBuilderImpl(
        TableNameResolver tableNameResolver,
        SchemaResolver schemaResolver,
        ColumnNameResolver columnNameResolver,
        ForeignKeyResolver foreignKeyResolver
) implements ModelBuilder {
    public ModelBuilderImpl() {
        this(TableNameResolver.DEFAULT, SchemaResolver.DEFAULT, ColumnNameResolver.DEFAULT, ForeignKeyResolver.DEFAULT);
    }
    public ModelBuilderImpl {
        tableNameResolver = tableNameResolver == null ? TableNameResolver.DEFAULT : tableNameResolver;
        schemaResolver = schemaResolver == null ? SchemaResolver.DEFAULT : schemaResolver;
        columnNameResolver = columnNameResolver == null ? ColumnNameResolver.DEFAULT : columnNameResolver;
        foreignKeyResolver = foreignKeyResolver == null ? ForeignKeyResolver.DEFAULT : foreignKeyResolver;
    }

    /**
     * Sets the table name resolver for the model.
     *
     * @param tableNameResolver the table name resolver.
     * @return this model builder.
     */
    @Override
    public ModelBuilder tableNameResolver(@Nullable TableNameResolver tableNameResolver) {
        return new ModelBuilderImpl(tableNameResolver, schemaResolver, columnNameResolver, foreignKeyResolver);
    }

    /**
     * Sets the schema resolver for the model.
     *
     * @param schemaResolver the schema resolver.
     * @return this model builder.
     */
    @Override
    public ModelBuilder schemaResolver(@Nullable SchemaResolver schemaResolver) {
        return new ModelBuilderImpl(tableNameResolver, schemaResolver, columnNameResolver, foreignKeyResolver);
    }

    /**
     * Sets the column name resolver for the model.
     *
     * @param columnNameResolver the column name resolver.
     * @return this model builder.
     */
    @Override
    public ModelBuilder columnNameResolver(@Nullable ColumnNameResolver columnNameResolver) {
        return new ModelBuilderImpl(tableNameResolver, schemaResolver, columnNameResolver, foreignKeyResolver);
    }

    /**
     * Sets the foreign key resolver for the model.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return this model builder.
     */
    @Override
    public ModelBuilder foreignKeyResolver(@Nullable ForeignKeyResolver foreignKeyResolver) {
        return new ModelBuilderImpl(tableNameResolver, schemaResolver, columnNameResolver, foreignKeyResolver);
    }

    /**
     * Builds the model.
     *
     * @param type the record type.
     * @return the model.
     * @param <ID> the primary key type.
     * @throws SqlTemplateException if an error occurs while building the model.
     */
    @Override
    public <T extends Data, ID> Model<T, ID> build(Class<T> type, boolean requirePrimaryKey)
            throws SqlTemplateException {
        return ModelFactory.getModel(this, type, requirePrimaryKey);
    }

    /**
     * Builds the model, expanding the references the plan resolves.
     *
     * @param type the record type.
     * @param requirePrimaryKey {@code true} if the primary key is required, {@code false} otherwise.
     * @param fetchPlan the references the query resolves as part of the statement.
     * @return the model.
     * @param <ID> the primary key type.
     * @throws SqlTemplateException if an error occurs while building the model.
     * @since 1.13
     */
    @Override
    public <T extends Data, ID> Model<T, ID> build(Class<T> type, boolean requirePrimaryKey,
                                                   FetchPlan fetchPlan) throws SqlTemplateException {
        return ModelFactory.getModel(this, type, requirePrimaryKey, fetchPlan);
    }
}
