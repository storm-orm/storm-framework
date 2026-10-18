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
package st.orm.template

import st.orm.BindVars
import st.orm.Data
import st.orm.Ref
import st.orm.template.TemplateString.Companion.wrap
import kotlin.reflect.KClass

/**
 * Provides methods for constructing SQL queries and query builders using Storm's template string DSL or the fluent API.
 *
 * `QueryTemplate` is the core interface for interacting with the Storm SQL template engine in Kotlin. It offers two
 * complementary approaches to database access:
 * - **Template Strings** -- Write SQL using Storm's `TemplateString` with type-safe interpolation of
 *   entity classes, records, and parameters via the [query] method.
 * - **Query Builder** -- Construct queries programmatically using the fluent [selectFrom], [updateFrom] and [deleteFrom]
 *   methods, which return a [QueryBuilder].
 *
 * This interface also provides utility methods for creating [BindVars] for batch operations and retrieving
 * [Model] metadata for entity types.
 *
 * ## Example: Query with QueryBuilder
 * ```kotlin
 * val users = orm.selectFrom(User::class)
 *     .where(User_.name eq "Alice")
 *     .resultList
 * ```
 *
 * @see ORMTemplate
 * @see QueryBuilder
 * @see Query
 */
public interface QueryTemplate : SubqueryTemplate {
    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     * @see PreparedQuery.addBatch
     */
    public fun createBindVars(): BindVars

    /**
     * Creates a ref instance for the specified record `type` and `pk`. This method can be used to generate
     * ref instances for entities, projections and regular records.
     *
     * @param type record type.
     * @param id primary key.
     * @return ref instance.
     * @param <T> table type.
     * @param <ID> primary key type.
     * @since 1.3
     */
    public fun <T : Data, ID : Any> ref(type: KClass<T>, id: ID): Ref<T>

    /**
     * Creates a ref instance for the specified record `type` and `id`. This method can be used to generate
     * ref instances for entities, projections and regular records. The object returned by this method already contains
     * the fetched record.
     *
     * @param id primary key.
     * @return ref instance.
     * @param <T> table type.
     * @param <ID> primary key type.
     * @since 1.3
     */
    public fun <T : Data, ID : Any> ref(record: T, id: ID): Ref<T>

    /**
     * Get the model for the specified record `type`. The model provides information about the type's database
     * name, primary keys and columns.
     *
     * @param type record type.
     * @return the model.
     * @param <T> table type.
     * @param <ID> primary key type.
     */
    public fun <T : Data> model(type: KClass<T>): Model<T, *> = model(type, false)

    /**
     * Get the model for the specified record `type`. The model provides information about the type's database
     * name, primary keys and columns.
     *
     * @param type record type.
     * @param requirePrimaryKey whether to require a primary key.
     * @return the model.
     * @param <T> table type.
     * @param <ID> primary key type.
     * @since 1.3
     */
    public fun <T : Data> model(type: KClass<T>, requirePrimaryKey: Boolean): Model<T, *>

    /**
     * Creates a query builder for the specified table.
     *
     * @param fromType the table to select from.
     * @return the query builder.
     * @param <T> the table type to select from.
     */
    public fun <T : Data> selectFrom(fromType: KClass<T>): QueryBuilder<T, T, *> = selectFrom(fromType, fromType)

    /**
     * Creates a query builder for the specified table and select type.
     *
     * The select list is rendered from the FROM table, so `selectType` may be any record shape over those
     * columns, such as a wrapper record nesting the from-entity; it need not be a `Data` type itself.
     *
     * @param fromType the table to select from.
     * @param selectType the result type of the query.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    public fun <T : Data, R : Any> selectFrom(
        fromType: KClass<T>,
        selectType: KClass<R>,
    ): QueryBuilder<T, R, *> = selectFrom(fromType, selectType, wrap(fromType))

    /**
     * Creates a query builder for the specified table and select type using the given `template`.
     *
     * @param fromType the table to select from.
     * @param selectType the result type of the query.
     * @param template the select clause template.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    public fun <T : Data, R : Any> selectFrom(
        fromType: KClass<T>,
        selectType: KClass<R>,
        template: TemplateBuilder,
    ): QueryBuilder<T, R, *> = selectFrom(fromType, selectType, template.build())

    /**
     * Creates a query builder for the specified table and select type using the given `template`.
     *
     * @param fromType the table to select from.
     * @param selectType the result type of the query.
     * @param template the select clause template.
     * @return the query builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    public fun <T : Data, R : Any> selectFrom(
        fromType: KClass<T>,
        selectType: KClass<R>,
        template: TemplateString,
    ): QueryBuilder<T, R, *>

    /**
     * Creates a query builder for the specified table to delete from.
     *
     * @param fromType the table to delete from.
     * @return the query builder.
     * @param <T> the table type to delete from.
     */
    public fun <T : Data> deleteFrom(fromType: KClass<T>): QueryBuilder<T, *, *>

    /**
     * Creates a query builder for the specified table to update.
     *
     * The builder sets the columns named through `set` on the rows its conditions select. It requires a WHERE clause
     * unless [QueryBuilder.unsafe] is called, and it increments the version column of a versioned table.
     *
     * @param fromType the table to update.
     * @return the query builder.
     * @param <T> the table type to update.
     * @since 1.15
     */
    public fun <T : Data> updateFrom(fromType: KClass<T>): QueryBuilder<T, *, *>

    /**
     * Creates a query for the specified `query` string.
     *
     * @param query the query.
     * @return the query.
     */
    public fun query(query: String): Query

    /**
     * Creates a query for the specified query `template`.
     *
     * @param template the query template.
     * @return the query.
     */
    public fun query(template: TemplateBuilder): Query = query(template.build())

    /**
     * Creates a query for the specified query `template`.
     *
     * @param template the query template.
     * @return the query.
     */
    public fun query(template: TemplateString): Query
}

/**
 * Creates a query builder for table [T] producing results of type [R] using the given select clause `template`.
 *
 * ```kotlin
 * orm.selectFrom<User, UserSummary> { "${t(User_.id)}, ${t(User_.email)}" }.resultList
 * ```
 *
 * @param T the table type to select from.
 * @param R the result type of the query.
 * @param template the select clause template.
 * @return the query builder.
 * @since 1.12
 */
public inline fun <reified T : Data, reified R : Any> QueryTemplate.selectFrom(
    noinline template: TemplateBuilder,
): QueryBuilder<T, R, *> = selectFrom(T::class, R::class, template)
