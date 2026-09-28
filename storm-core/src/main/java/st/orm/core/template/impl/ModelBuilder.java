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

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.PersistenceException;
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
public interface ModelBuilder {

    /**
     * Creates a new instance of the model builder.
     *
     * @return a new instance of the model builder.
     */
    static ModelBuilder newInstance() {
        return new ModelBuilderImpl();
    }

    /**
     * Returns the table name resolver for the model.
     */
    TableNameResolver tableNameResolver();

    /**
     * Sets the table name resolver for the model.
     *
     * @param tableNameResolver the table name resolver.
     * @return this model builder.
     */
    ModelBuilder tableNameResolver(@Nullable TableNameResolver tableNameResolver);

    /**
     * Returns the schema resolver for the model.
     *
     * @since 1.14
     */
    SchemaResolver schemaResolver();

    /**
     * Sets the schema resolver for the model.
     *
     * @param schemaResolver the schema resolver.
     * @return this model builder.
     * @since 1.14
     */
    ModelBuilder schemaResolver(@Nullable SchemaResolver schemaResolver);

    /**
     * Returns the column name resolver for the model.
     */
    ColumnNameResolver columnNameResolver();

    /**
     * Sets the column name resolver for the model.
     *
     * @param columnNameResolver the column name resolver.
     * @return this model builder.
     */
    ModelBuilder columnNameResolver(@Nullable ColumnNameResolver columnNameResolver);

    /**
     * Returns the foreign key resolver for the model.
     */
    ForeignKeyResolver foreignKeyResolver();

    /**
     * Sets the foreign key resolver for the model.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return this model builder.
     */
    ModelBuilder foreignKeyResolver(@Nullable ForeignKeyResolver foreignKeyResolver);


    /**
     * Builds the model for the data type of the specified record.
     *
     * @param record the record to build the model for.
     * @return the model.
     * @param <ID> the primary key type.
     * @throws SqlTemplateException if an error occurs while building the model.
     */
    default <T extends Data, ID> Model<T, ID> build(T record, boolean requirePrimaryKey)
            throws SqlTemplateException {
        //noinspection unchecked
        return build((Class<T>) record.getClass(), requirePrimaryKey);
    }

    /**
     * Builds the model for the specified data type.
     *
     * @param type the record type.
     * @return the model.
     * @param <ID> the primary key type.
     * @throws SqlTemplateException if an error occurs while building the model.
     */
    <T extends Data, ID> Model<T, ID> build(Class<T> type, boolean requirePrimaryKey) throws SqlTemplateException;

    /**
     * Builds the model for the specified data type, expanding the references the plan resolves.
     *
     * <p>A reference contributes its foreign key column alone, since the referenced record is not materialized. A
     * reference the plan resolves contributes the referenced table's columns as well, laid out exactly as an entity
     * foreign key: the foreign key column carries the target's primary key, followed by the target's remaining
     * columns.</p>
     *
     * @param type the record type.
     * @param requirePrimaryKey {@code true} if the primary key is required, {@code false} otherwise.
     * @param fetchPlan the references the query resolves as part of the statement.
     * @return the model.
     * @param <ID> the primary key type.
     * @throws SqlTemplateException if an error occurs while building the model.
     * @since 1.13
     */
    <T extends Data, ID> Model<T, ID> build(Class<T> type, boolean requirePrimaryKey,
                                            FetchPlan fetchPlan) throws SqlTemplateException;

    /**
     * Returns a supplier for the model.
     *
     * @param type the record type.
     * @param requirePrimaryKey {@code true} if the primary key is required, {@code false} otherwise.
     * @return a supplier for the model.
     * @param <T> the record type.
     * @param <ID> the primary key type.
     */
    default <T extends Data, ID> Supplier<Model<T, ID>> supplier(Class<T> type, boolean requirePrimaryKey) {
        return () -> {
            try {
                return build(type, requirePrimaryKey);
            } catch (SqlTemplateException e) {
                throw new PersistenceException(e);
            }
        };
    }
}
