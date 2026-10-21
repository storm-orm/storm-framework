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

import static java.lang.StringTemplate.RAW;

import st.orm.BindVars;
import st.orm.Data;
import st.orm.Ref;

/**
 * Provides methods for constructing SQL queries and query builders using String Templates or the fluent API.
 *
 * <p>{@code QueryTemplate} is the core interface for interacting with the Storm SQL template engine. It offers two
 * complementary approaches to database access:</p>
 * <ul>
 *   <li><strong>String Templates</strong> -- Write SQL using Java String Templates with type-safe interpolation of
 *       entity classes, records, and parameters via the {@link #query(StringTemplate)} method.</li>
 *   <li><strong>Query Builder</strong> -- Construct queries programmatically using the fluent
 *       {@link #selectFrom(Class)}, {@link #deleteFrom(Class)} and {@link #updateFrom(Class)} methods, which return a
 *       {@link QueryBuilder}.</li>
 * </ul>
 *
 * <p>This interface also provides utility methods for creating {@link st.orm.BindVars} for batch operations and
 * retrieving {@link Model} metadata for entity types.</p>
 *
 * <h2>Example: Query with String Template</h2>
 * <pre>{@code
 * Query query = orm.query(RAW."""
 *         SELECT \{User.class}
 *         FROM \{User.class}
 *         WHERE \{User_.name} = \{"Alice"}""");
 * List<User> users = query.getResultList(User.class);
 * }</pre>
 *
 * <h2>Example: Query with QueryBuilder</h2>
 * <pre>{@code
 * List<User> users = orm.selectFrom(User.class)
 *         .where(User_.name, EQUALS, "Alice")
 *         .getResultList();
 * }</pre>
 *
 * @see ORMTemplate
 * @see QueryBuilder
 * @see Query
 */
public interface QueryTemplate extends SubqueryTemplate {

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     * @see PreparedQuery#addBatch(Data)
     */
    BindVars createBindVars();

    /**
     * Creates a ref instance for the specified record {@code type} and {@code pk}. This method can be used to generate
     * ref instances for entities, projections and regular records.
     *
     * @param type record type.
     * @param id primary key.
     * @return ref instance.
     * @param <T> table type.
     * @param <ID> primary key type.
     * @since 1.3
     */
    <T extends Data, ID> Ref<T> ref(Class<T> type, ID id);

    /**
     * Creates a ref instance for the specified record {@code type} and {@code id}. This method can be used to generate
     * ref instances for entities, projections and regular records. The object returned by this method already contains
     * the fetched record.
     *
     * @param id primary key.
     * @return ref instance.
     * @param <T> table type.
     * @param <ID> primary key type.
     * @since 1.3
     */
    <T extends Data, ID> Ref<T> ref(T record, ID id);

    /**
     * Get the model for the specified record {@code type}. The model provides information about the type's database
     * name, primary keys and columns.
     *
     * @param type record type.
     * @return the model.
     * @param <T> table type.
     * @param <ID> primary key type.
     */
    default <T extends Data, ID> Model<T, ID> model(Class<T> type) {
        return model(type, false);
    }

    /**
     * Get the model for the specified record {@code type}. The model provides information about the type's database
     * name, primary keys and columns.
     *
     * @param type record type.
     * @param requirePrimaryKey whether to require a primary key.
     * @return the model.
     * @param <T> table type.
     * @param <ID> primary key type.
     * @since 1.3
     */
    <T extends Data, ID> Model<T, ID> model(Class<T> type, boolean requirePrimaryKey);

    /**
     * Creates a query builder for the specified table.
     *
     * @param fromType the table to select from.
     * @return the query builder.
     * @param <T> the table type to select from.
     */
    default <T extends Data> QueryBuilder<T, T, ?> selectFrom(Class<T> fromType) {
        return selectFrom(fromType, fromType);
    }

    /**
     * Creates a query builder for the specified table and select type.
     *
     * <p>The select list is rendered from the FROM table, so {@code selectType} may be any record shape over those
     * columns, such as a wrapper record nesting the from-entity; it need not be a {@code Data} type itself.</p>
     *
     * @param fromType the table to select from.
     * @param selectType the result type of the query.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    default <T extends Data, R> QueryBuilder<T, R, ?> selectFrom(Class<T> fromType,
                                                                 Class<R> selectType) {
        return selectFrom(fromType, selectType, RAW."\{fromType}");
    }

    /**
     * Creates a query builder for the specified table and select type using the given {@code template}.
     *
     * @param fromType the table to select from.
     * @param selectType the result type of the query.
     * @param template the select clause template.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    <T extends Data, R> QueryBuilder<T, R, ?> selectFrom(Class<T> fromType,
                                                         Class<R> selectType,
                                                         StringTemplate template);

    /**
     * Creates a query builder for the specified table to delete from.
     *
     * @param fromType the table to delete from.
     * @return the query builder.
     * @param <T> the table type to delete from.
     */
    <T extends Data> QueryBuilder<T, ?, ?> deleteFrom(Class<T> fromType);

    /**
     * Creates a query builder for the specified table to update.
     *
     * <p>The builder sets the columns named through {@code set} on the rows its conditions select. It requires a
     * WHERE clause unless {@link QueryBuilder#unsafe()} is called, and it increments the version column of a versioned
     * table.</p>
     *
     * @param fromType the table to update.
     * @return the query builder.
     * @param <T> the table type to update.
     * @since 1.16
     */
    <T extends Data> QueryBuilder<T, ?, ?> updateFrom(Class<T> fromType);

    /**
     * Creates a query for the specified {@code query} string.
     *
     * @param query the query.
     * @return the query.
     */
    Query query(String query);

    /**
     * Creates a query for the specified query {@code template}.
     *
     * @param template the query template.
     * @return the query.
     */
    Query query(StringTemplate template);
}
