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

import st.orm.*
import st.orm.core.template.impl.Elements
import st.orm.core.template.impl.Elements.ObjectExpression
import st.orm.core.template.impl.Elements.TemplateSource
import st.orm.core.template.impl.Subqueryable
import st.orm.template.Templates.table
import java.sql.Connection
import java.sql.Time
import java.sql.Timestamp
import java.util.*
import javax.sql.DataSource
import kotlin.reflect.KClass

/**
 * The `Templates` interface provides a collection of static methods for constructing SQL query elements
 * and creating ORM repository templates. It serves as a central point for building SQL queries or getting access to
 * repositories that can interact with databases in a type-safe and manner, supporting both JPA and JDBC.
 *
 * This interface includes methods for generating SQL clauses such as `SELECT`, `FROM`, `WHERE`,
 * `INSERT`, `UPDATE`, and `DELETE`, as well as utility methods for working with parameters,
 * tables, aliases, and more.
 *
 * Additionally, the `Templates` interface provides methods to create [ORMTemplate]
 * instances for use with different data sources like JDBC's [DataSource] or [Connection], or
 * JPA's `EntityManager` (see `JpaTemplate`).
 *
 * ## Using Templates
 *
 * Define the records to use them to construct the query using templates:
 * ```
 * data class City(
 *     id: Int = 0
 *     name: String,
 *     population: Long
 * )
 * ```
 *
 * ```
 * data class User(
 *     id: Int = 0,
 *     name: String,
 *     age: Int,
 *     birthDate: LocalDate,
 *     street: String,
 *     postalCode: String,
 *     cityId: Int
 * )
 * ```
 *
 * ### Create
 *
 * Insert a user into the database. The template engine also supports insertion of multiple entries by passing an
 * array (or list) of objects or primary keys. Alternatively, insertion can also be executed in batch mode using
 * [st.orm.BindVars].
 * ```
 * val user = ...
 * ORM(dataSource).query { """
 *     INSERT INTO ${t(User::class)}
 *     VALUES ${t(user)}
 * """ }
 * ```
 *
 * ### Read
 *
 * Select all users from the database that are linked to City with primary key 1. The value is passed to the
 * underling JDBC or JPA system as variable. The results can also be retrieved as a stream of objects by using
 * [Query.getResultStream].
 * ```
 * val users: List<User> = ORM(dataSource).query { """
 *     SELECT ${t(User::class)}
 *     FROM ${t(User::class)}
 *     WHERE ${t(User::class)}.city_id = {1}"""
 * }
 * .getResultList(User::class);
 * ```
 *
 * ### Update
 *
 * Update a user in the database. The template engine also supports updates for multiple entries by passing an
 * array or list of objects. Alternatively, updates can be executed in batch mode by using [st.orm.BindVars].
 * ```
 * val user = ...
 * ORM(dataSource).query { """
 *     UPDATE ${t(User::class)}
 *     SET ${t(user)}
 *     WHERE ${t(user)}
 * """ }
 * ```
 *
 * ### Delete
 *
 * Delete user in the database. The template engine also supports updates for multiple entries by passing an
 * array (or list) of objects or primary keys. Alternatively, deletion can be executed in batch mode by using
 * [st.orm.BindVars].
 * ```
 * val user = ...
 * ORM(dataSource).query { """
 *     DELETE FROM ${t(User::class)}
 *     WHERE ${t(user)}
 * """ }
 * ```
 *
 * ## Howto start
 *
 * The `Templates` interface provides static methods to create [ORMTemplate] instances based on your data source:
 *
 * ### Using EntityManager (JPA)
 * ```
 * val entityManager = ...
 * val orm = Templates.ORM(entityManager)
 * ```
 *
 * ### Using DataSource (JDBC)
 * ```
 * val dataSource = ...
 * val orm = Templates.ORM(dataSource)
 * ```
 *
 * ### Using Connection (JDBC)
 *
 * **Note:** The caller is responsible for closing the connection after usage.
 *
 * ```
 * val connection = ...
 * val orm = Templates.ORM(connection)
 * ```
 *
 * @see st.orm.repository.EntityRepository
 * @see st.orm.repository.ProjectionRepository
 */
object Templates {

    /**
     * Generates a SELECT element for the specified table class.
     *
     * This method creates a `SELECT` clause for the provided table record, including all of its
     * columns as well as columns from any foreign key relationships defined within the record. It is
     * designed to be used within SQL string templates to dynamically construct queries based on the
     * table's structure.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(select(MyTable::class, NESTED))}
     * FROM ${t(from(MyTable::class))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine
     * automatically detects that a SELECT element is required based on its placement in the query:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @return an [Element] representing the SELECT clause for the specified table.
     */
    fun select(table: KClass<out Data>): Element = Elements.Select(table.java, SelectMode.NESTED)

    /**
     * Generates a SELECT element for the specified [table] class with a specific selection mode.
     *
     * This method creates a `SELECT` clause for the provided table record, including all of its
     * columns as well as columns from any foreign key relationships defined within the record. It is
     * designed to be used within SQL string templates to dynamically construct queries based on the
     * table's structure.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(select(MyTable::class))}
     * FROM ${t(from(MyTable::class))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine
     * automatically detects that a SELECT element is required based on its placement in the query:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @param mode NESTED to include the full object hierarchy, FLAT to include only the main table's fields, PK to
     * include only the primary key fields.
     * @return an [Element] representing the SELECT clause for the specified table.
     */
    fun select(table: KClass<out Data>, mode: SelectMode = SelectMode.NESTED): Element = Elements.Select(table.java, mode)

    /**
     * Generates a FROM element for the specified table class without an alias and optional auto-joining of foreign
     * keys.
     *
     * This method creates a `FROM` clause for the provided table record. If [autoJoin] is set
     * to `true`, it will automatically include JOIN clauses for all foreign keys defined in the record.
     * This facilitates constructing complex queries that involve related tables without manually specifying each join.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(select(MyTable::class))}
     * FROM ${t(from(MyTable::class, true))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. In this case, [autoJoin]
     * defaults to `false`. The SQL template engine automatically detects that a FROM element is required
     * based on its placement in the query:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @param autoJoin if `true`, automatically join all foreign keys listed in the record.
     * @return an [Element] representing the FROM clause for the specified table.
     */
    fun from(table: KClass<out Data>, autoJoin: Boolean): Element = Elements.From(table.java, autoJoin)

    /**
     * Generates a FROM element for the specified table class, with an alias and optional auto-joining of foreign keys.
     *
     * This method creates a `FROM` clause for the provided table record, applying the specified alias.
     * If [autoJoin] is set to `true`, it will automatically include JOIN clauses for all foreign keys
     * defined in the record. This facilitates constructing complex queries that involve related tables without
     * manually specifying each join.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(select(Table::class))}
     * FROM ${t(from(MyTable::class, "t", true))}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @param autoJoin if `true`, automatically join all foreign keys listed in the record.
     * @return an [Element] representing the FROM clause for the specified table.
     */
    fun from(table: KClass<out Data>, alias: String, autoJoin: Boolean): Element = Elements.From(Elements.TableSource(table.java), alias, autoJoin)

    /**
     * Generates a FROM element using a provided SQL string template with an alias.
     *
     * This method allows you to specify a custom [TemplateBuilder] to be used as the source in the `FROM`
     * clause, applying the provided [alias]. This is useful when you need to include subqueries or complex table
     * expressions in your SQL queries.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(select(Table::class))}
     * FROM ${t(from({ "SELECT column_a, column_b FROM table" }, "t"))}
     * ```
     *
     * In this context, the alias is mandatory and auto-joining of foreign keys is not applicable.
     *
     * @param builder the [TemplateBuilder] representing the custom SQL to be used in the FROM clause.
     * @param alias the alias to assign to the frame clause in the query. The alias must not require escaping.
     * @return an [Element] representing the FROM clause with the specified template and alias.
     */
    fun from(builder: TemplateBuilder, alias: String): Element = from(builder.build(), alias)

    /**
     * Generates a FROM element using a provided SQL template string with an alias.
     *
     * This method allows you to specify a custom [TemplateString] to be used as the source in the `FROM`
     * clause, applying the provided alias. This is useful when you need to include subqueries or complex table
     * expressions in your SQL queries.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(select(Table::class))}
     * FROM ${t(from(template("SELECT column_a, column_b FROM table"), "t"))}
     * ```
     *
     * In this context, the alias is mandatory and auto-joining of foreign keys is not applicable.
     *
     * @param template the [TemplateString] representing the custom SQL to be used in the FROM clause.
     * @param alias the alias to assign to the frame clause in the query. The alias must not require escaping.
     * @return an [Element] representing the FROM clause with the specified template and alias.
     */
    fun from(template: TemplateString, alias: String): Element = Elements.From(TemplateSource(template.unwrap), alias, false)

    /**
     * Generates an INSERT element for the specified table class.
     *
     * This method creates an `INSERT` clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct INSERT queries based on the table's structure.
     *
     * Example usage in a string template:
     * ```
     * INSERT INTO ${t(insert(MyTable::class))}
     * VALUES ${t(values(entity))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an INSERT element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(entity)}
     * ```
     *
     * Here, `entity` is an instance of the `Table` class containing the values to be inserted.
     *
     * @param table the [KClass] object representing the table record.
     * @return an [Element] representing the INSERT clause for the specified table.
     */
    fun insert(table: KClass<out Data>): Element = Elements.Insert(table.java)

    /**
     * Generates an INSERT element for the specified table class with control over auto-generation.
     *
     * This method creates an `INSERT` clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct INSERT queries based on the table's structure.
     *
     * Example usage in a string template:
     * ```
     * INSERT INTO ${t(insert(MyTable::class, true))}
     * VALUES ${t(values(entity))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an INSERT element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(entity)}
     * ```
     *
     * Here, `entity` is an instance of the `Table` class containing the values to be inserted.
     *
     * @param table the [KClass] object representing the table record.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @return an [Element] representing the INSERT clause for the specified table.
     */
    fun insert(table: KClass<out Data>, ignoreAutoGenerate: Boolean): Element = Elements.Insert(table.java, ignoreAutoGenerate)

    /**
     * Generates a VALUES clause for a single record instance with control over auto-generation.
     *
     * This method creates a `VALUES` clause using the provided [Data] instance.
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * Example usage in a string template:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(values(entity, true))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(entity)}
     * ```
     *
     * Here, `entity` is an instance of the `Data` class containing the values to be inserted.
     *
     * @param r the [Data] instance containing the values to be inserted.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @return an [Element] representing the VALUES clause with the specified record.
     */
    fun values(r: Data, ignoreAutoGenerate: Boolean): Element = Elements.Values(listOf(r), null, ignoreAutoGenerate)

    /**
     * Generates a VALUES clause for the specified record instance(s).
     *
     * This method creates a `VALUES` clause using the provided [Data] instance(s).
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * Example usage in a string template:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(values(entity1, entity2))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(arrayOf(entity1, entity2))}
     * ```
     *
     * Here, `entity1`, `entity2`, etc., are instances of the `Data` class containing
     * the values to be inserted.
     *
     * @param r one or more [Data] instances containing the values to be inserted.
     * @return an [Element] representing the VALUES clause with the specified records.
     */
    fun values(vararg r: Data): Element = Elements.Values(listOf(*r), null)

    /**
     * Generates a VALUES clause for the specified iterable of record instances.
     *
     * This method creates a `VALUES` clause using the provided [Iterable] of [Data] instances.
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * Example usage in a string template:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(values(records))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(records)}
     * ```
     *
     * Here, `records` is an [Iterable] of `Data` instances containing
     * the values to be inserted.
     *
     * @param records an [Iterable] of [Data] instances containing the values to be inserted.
     * @return an [Element] representing the VALUES clause with the specified records.
     */
    fun values(records: Iterable<Data>): Element = Elements.Values(records, null)

    /**
     * Generates a VALUES clause for the specified iterable of record instances with control over auto-generation.
     *
     * This method creates a `VALUES` clause using the provided [Iterable] of [Data] instances.
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * Example usage in a string template:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(values(records, true))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(records)}
     * ```
     *
     * Here, `records` is an [Iterable] of `Data` instances containing
     * the values to be inserted.
     *
     * @param records an [Iterable] of [Data] instances containing the values to be inserted.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @return an [Element] representing the VALUES clause with the specified records.
     */
    fun values(records: Iterable<Data>, ignoreAutoGenerate: Boolean): Element = Elements.Values(records, null, ignoreAutoGenerate)

    /**
     * Generates a VALUES clause using the specified [BindVars] for batch insertion.
     *
     * This method creates a `VALUES` clause that utilizes a [BindVars] instance, allowing for batch
     * insertion of records using bind variables. This is particularly useful when performing batch operations where
     * the same query is executed multiple times with different variable values.
     *
     * Example usage in a batch insertion scenario:
     * ```
     * val bindVars = orm.createBindVars()
     * orm.query { """
     *     INSERT INTO ${t(MyTable::class)}
     *     VALUES ${t(values(bindVars))}
     * """ }.prepare().use { query ->
     *     records.forEach(query::addBatch)
     *     query.executeBatch()
     * }
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(bindVars)}
     * ```
     *
     * In this example, `bindVars` is a [BindVars] instance created by the ORM. The `records` are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the [BindVars] instance used for batch insertion.
     * @return an [Element] representing the VALUES clause utilizing the specified bind variables.
     */
    fun values(bindVars: BindVars): Element = Elements.Values(null, bindVars)

    /**
     * Generates a VALUES clause using the specified [BindVars] for batch insertion with control over auto-generation.
     *
     * This method creates a `VALUES` clause that utilizes a [BindVars] instance, allowing for batch
     * insertion of records using bind variables. This is particularly useful when performing batch operations where
     * the same query is executed multiple times with different variable values.
     *
     * Example usage in a batch insertion scenario:
     * ```
     * val bindVars = orm.createBindVars()
     * orm.query { """
     *     INSERT INTO ${t(MyTable::class)}
     *     VALUES ${t(values(bindVars, true))}
     * """ }.prepare().use { query ->
     *     records.forEach(query::addBatch)
     *     query.executeBatch()
     * }
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * ```
     * INSERT INTO ${t(MyTable::class)}
     * VALUES ${t(bindVars)}
     * ```
     *
     * In this example, `bindVars` is a [BindVars] instance created by the ORM. The `records` are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the [BindVars] instance used for batch insertion.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @return an [Element] representing the VALUES clause utilizing the specified bind variables.
     */
    fun values(bindVars: BindVars, ignoreAutoGenerate: Boolean): Element = Elements.Values(null, bindVars, ignoreAutoGenerate)

    /**
     * Generates an UPDATE element for the specified table class.
     *
     * This method creates an `UPDATE` clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct UPDATE queries based on the table's structure.
     *
     * Example usage in a string template:
     * ```
     * UPDATE ${t(update(MyTable::class))}
     * SET ${t(set(record))}
     * WHERE ${t(where(record))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an UPDATE element is required based on its placement in the query:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(record)}
     * WHERE ${t(record)}
     * ```
     *
     * Here, `record` is an instance of the `Data` class containing the values to be updated.
     *
     * @param table the [KClass] object representing the table record.
     * @return an [Element] representing the UPDATE clause for the specified table.
     */
    fun update(table: KClass<out Data>): Element = Elements.Update(table.java)

    /**
     * Generates an UPDATE element for the specified table class with an alias.
     *
     * This method creates an `UPDATE` clause for the provided table record, applying the specified alias.
     * It is designed to be used within SQL string templates to dynamically construct UPDATE queries based on the
     * table's structure.
     *
     * Example usage in a string template:
     * ```
     * UPDATE ${t(update(MyTable::class, "t"))}
     * SET ${t(set(record))}
     * WHERE ${t(where(record))}
     * ```
     *
     * Here, `record` is an instance of the `Data` class containing the values to be updated.
     *
     * @param table the [KClass] object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @return an [Element] representing the UPDATE clause for the specified table with alias.
     */
    fun update(table: KClass<out Data>, alias: String): Element = Elements.Update(table.java, alias)

    /**
     * Generates a SET clause for the specified record.
     *
     * This method creates a `SET` clause using the provided [Data] instance. It is intended to be used
     * within SQL string templates to dynamically construct UPDATE statements with the given values.
     *
     * Example usage in a string template:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(set(record))}
     * WHERE ${t(where(record))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects
     * that a SET element is required based on its placement in the query:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(record)}
     * WHERE ${t(record)}
     * ```
     *
     * Here, `record` is an instance of the `Data` class containing the values to be set.
     *
     * @param record the [Data] instance containing the values to be set.
     * @return an [Element] representing the SET clause with the specified record.
     */
    fun set(record: Data): Element = Elements.Set(record, null, listOf())

    /**
     * Generates a SET clause for the specified record.
     *
     * This method creates a `SET` clause using the provided [Data] instance. It is intended to be used
     * within SQL string templates to dynamically construct UPDATE statements with the given values.
     *
     * Example usage in a string template:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(set(record))}
     * WHERE ${t(where(record))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects
     * that a SET element is required based on its placement in the query:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(record)}
     * WHERE ${t(record)}
     * ```
     *
     * Here, `record` is an instance of the `Data` class containing the values to be set.
     *
     * @param record the [Data] instance containing the values to be set.
     * @param fields the fields to update.
     * @return an [Element] representing the SET clause with the specified record.
     * @since 1.7
     */
    fun set(record: Data, fields: Collection<Metamodel<*, *>>): Element = Elements.Set(record, null, fields)

    /**
     * Generates a SET clause using the specified [BindVars] for batch updates.
     *
     * This method creates a `SET` clause that utilizes a [BindVars] instance, allowing for batch
     * updates using bind variables. This is particularly useful when performing batch operations where the same
     * update query is executed multiple times with different variable values.
     *
     * Example usage in a batch update scenario:
     * ```
     * val bindVars = orm.createBindVars()
     * orm.query { """
     *     UPDATE ${t(MyTable::class)}
     *     SET ${t(set(bindVars))}
     *     WHERE ${t(where(bindVars))}
     * """ }.prepare().use { query ->
     *     records.forEach(query::addBatch)
     *     query.executeBatch()
     * }
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an UPDATE element is required based on its placement in the query:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(record)}
     * WHERE ${t(record)}
     * ```
     *
     * In this example, `bindVars` is a [BindVars] instance created by the ORM. The `records` are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the [BindVars] instance used for batch updates.
     * @return an [Element] representing the SET clause utilizing the specified bind variables.
     */
    fun set(bindVars: BindVars): Element = Elements.Set(null, bindVars, listOf())

    /**
     * Generates a SET clause using the specified [BindVars] for batch updates.
     *
     * This method creates a `SET` clause that utilizes a [BindVars] instance, allowing for batch
     * updates using bind variables. This is particularly useful when performing batch operations where the same
     * update query is executed multiple times with different variable values.
     *
     * Example usage in a batch update scenario:
     * ```
     * val bindVars = orm.createBindVars()
     * orm.query { """
     *     UPDATE ${t(MyTable::class)}
     *     SET ${t(set(bindVars))}
     *     WHERE ${t(where(bindVars))}
     * """ }.prepare().use { query ->
     *     records.forEach(query::addBatch)
     *     query.executeBatch()
     * }
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an UPDATE element is required based on its placement in the query:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(record)}
     * WHERE ${t(record)}
     * ```
     *
     * In this example, `bindVars` is a [BindVars] instance created by the ORM. The `records` are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the [BindVars] instance used for batch updates.
     * @param fields the fields to update.
     * @return an [Element] representing the SET clause utilizing the specified bind variables.
     * @since 1.7
     */
    fun set(bindVars: BindVars, fields: Collection<Metamodel<*, *>>): Element = Elements.Set(null, bindVars, fields)

    /**
     * Generates a WHERE clause based on the provided iterable of values or records.
     *
     * This method creates a `WHERE` clause that matches the primary key(s) of the root table,
     * a record instance of the root table, or foreign key(s) in the hierarchy against the provided records
     * using the `IN` operator. It is useful when you want to select records where the primary key,
     * a specific record, or related foreign keys match any of the values in the iterable.
     *
     * The objects in the iterable can be:
     * - Primitive values matching the primary key of the root table.
     * - Instances of [Record] matching the compound primary key of the root table.
     * - Instances of [Data] representing records of related (foreign key) tables in the hierarchy of the
     *   root table.
     *
     * Example usage with primary key values:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(listOfIds))}
     * ```
     *
     * Example usage with records:
     * ```
     * val entities = listOf(entity1, entity2)
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(entities))}
     * ```
     *
     * In this example, the query selects all entries in `Table` that are linked to any of the otherTables in the
     * list.
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a WHERE element is required based on its placement in the query:
     * ```
     * val entities = listOf(entity1, entity2)
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(entities)}
     * ```
     *
     * As per the resolution rules:
     * - If `${t(entities)}`, or any other primitive or object, is placed after WHERE the SQL template engine
     *   resolves it into a WHERE element.
     * - If `${t(entities)}`, or any other primitive or object, is placed after keywords like VALUES, SET, the SQL
     *   template engine resolves it into the appropriate element (e.g., VALUES element, SET element).
     * - If `${t(entities)}` is not in such a placement, it is resolved into a param element.
     *
     * @param it an [Iterable] of values or records to match against the primary key(s) or foreign keys.
     * @return an [Element] representing the WHERE clause.
     */
    fun where(it: Iterable<*>): Element = Elements.Where(ObjectExpression(Operator.IN, it), null)

    /**
     * Generates a WHERE clause based on the provided value or record.
     *
     * This method creates a `WHERE` clause that matches the primary key(s) of the root table,
     * a record instance of the root table, or foreign key(s) in the hierarchy against the provided records
     * using the `EQUALS` operator. It is useful when you want to select records where the primary key,
     * a specific record, or related foreign keys match the object.
     *
     * The object can be:
     * - Primitive values matching the primary key of the root table.
     * - Instances of [Record] matching the compound primary key of the root table.
     * - Instances of [Data] representing records of related (foreign key) tables in the hierarchy of the
     *   root table.
     *
     * Example usage with a primary key value:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(id))}
     * ```
     *
     * Example usage with a record:
     * ```
     * val entity = ...
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(otherTable))}
     * ```
     *
     * In this example, the query selects all entries in `Table` that are linked to the specified `otherTable`.
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a WHERE element is required based on its placement in the query:
     * ```
     * val entity = ...
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(entity)}
     * ```
     *
     * As per the resolution rules:
     * - If `${t(entity)}`, or any other primitive or object, is placed after WHERE the SQL template engine
     *   resolves it into a WHERE element.
     * - If `${t(entity)}`, or any other primitive or object, is placed after keywords like VALUES, SET, the SQL
     *   template engine resolves it into the appropriate element (e.g., VALUES element, SET element).
     * - If `${t(entity)}` is not in such a placement, it is resolved into a param element.
     *
     * @param o the value or record to match against the primary key or foreign key.
     * @return an [Element] representing the WHERE clause.
     */
    fun where(o: Any): Element = Elements.Where(ObjectExpression(o), null)

    /**
     * Generates a WHERE clause based on the provided path, operator, and iterable of values or records.
     *
     * This method creates a `WHERE` clause for the specified column or path using the given
     * operator and values or records. The `path` parameter specifies the column or property to apply the
     * condition on, which can include nested properties using dot notation.
     *
     * The objects in the iterable must match the type of the record component found at the specified path.
     * If the path points to a record, the objects may also match the primary key type of that record.
     *
     * Example usage with primary keys:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(MyTable_.otherTable.id, Operator.IN, listOfIds))}
     * ```
     *
     * In this example, `listOfIds` contains the primary key values of the `MyTable` records,
     * and the query selects all entries in `Table` linked to that MyTable.
     *
     * Example usage with records:
     * ```
     * val entities = listOf(...)
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(MyTable_.otherTable, Operator.IN, entities))}
     * ```
     *
     * In this example, `entities` is a list of `MyTable` records. The query matches entries in
     * `Table` linked to any of the records in the list via their foreign keys.
     *
     * @param V the type of values in the iterable.
     * @param path the path or column name to apply the condition on.
     * @param operator the [Operator] to use in the condition.
     * @param it an [Iterable] of values or records for the condition.
     * @return an [Element] representing the WHERE clause.
     */
    fun <V> where(
        path: Metamodel<*, V>,
        operator: Operator,
        it: Iterable<V>,
    ): Element = Elements.Where(ObjectExpression(path, operator, it), null)

    /**
     * Generates a WHERE clause based on the provided path, operator, and values or records.
     *
     * This method creates a `WHERE` clause for the specified column or path using the given
     * operator and values or records. The `path` parameter specifies the column or property to apply the
     * condition on, which can include nested properties using dot notation.
     *
     * The objects in the array must match the type of the record component found at the specified path.
     * If the path points to a record, the objects may also match the primary key type of that record.
     *
     * Example usage with primary keys:
     * ```
     * SELECT ${t(MyTable::class)}
     * FROM ${t(MyTable::class)}
     * WHERE ${t(where(MyTable_.otherTable.id, Operator.BETWEEN, 1, 10))}
     * ```
     *
     * In this example, the query selects all entries in `Table` where the associated `MyTable`
     * records have primary keys between `1` and `10`.
     *
     * @param V the type of values in the vararg.
     * @param path the path or column name to apply the condition on.
     * @param operator the [Operator] to use in the condition.
     * @param o the values or records for the condition.
     * @return an [Element] representing the WHERE clause.
     */
    fun <V> where(path: Metamodel<*, V>, operator: Operator, vararg o: V): Element = Elements.Where(ObjectExpression(path, operator, o), null)

    /**
     * Generates a WHERE clause using the specified [BindVars] for batch operations.
     *
     * This method is particularly useful when performing batch operations where the same query is executed
     * multiple times with different parameter values. The [BindVars] instance allows for parameterized
     * queries, enhancing performance and security by preventing SQL injection and enabling query plan reuse.
     *
     * Example usage in a batch operation:
     * ```
     * val bindVars = orm.createBindVars()
     * orm.query { """
     *     UPDATE ${t(MyTable::class)}
     *     SET ${t(bindVars)}
     *     WHERE ${t(where(bindVars))}
     * """ }.prepare().use { query ->
     *     records.forEach(query::addBatch)
     *     query.executeBatch()
     * }
     * ```
     *
     * In this example, the `bindVars` instance is used to bind variables for the WHERE clause in a batch
     * operation. Each record in `records` provides the parameter values for a single execution of the query.
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a WHERE element is required based on its placement in the query:
     * ```
     * UPDATE ${t(MyTable::class)}
     * SET ${t(bindVars)}
     * WHERE ${t(bindVars)}
     * ```
     *
     * @param bindVars the [BindVars] instance used for binding variables in the WHERE clause; must not be null.
     * @return an [Element] representing the WHERE clause utilizing the specified bind variables.
     */
    fun where(bindVars: BindVars): Element = Elements.Where(null, bindVars)

    /**
     * Generates a DELETE element for the specified table class.
     *
     * This method creates a `DELETE` clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct DELETE queries based on the table's structure.
     *
     * Example usage in a string template:
     * ```
     * DELETE ${t(delete(MyTable::class))} FROM ${t(from(MyTable::class))}
     * WHERE ${t(where(record))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a DELETE element is required based on its placement in the query:
     * ```
     * DELETE ${t(MyTable::class)} FROM ${t(MyTable::class)}
     * WHERE ${t(record)}
     * ```
     *
     * Here, `record` is an instance of the [Data] class containing the criteria for deletion.
     *
     * **Note:** In most databases, specifying the table in the DELETE clause is not
     * necessary, or even disallowed; the DELETE statement is usually constructed with only a FROM clause:
     * ```
     * DELETE FROM ${t(from(MyTable::class))}
     * WHERE ${t(where(record))}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @return an [Element] representing the DELETE clause for the specified table.
     */
    fun delete(table: KClass<out Data>): Element = Elements.Delete(table.java)

    /**
     * Generates a DELETE element for the specified table class with an alias.
     *
     * This method creates a `DELETE` clause for the provided table record, applying the specified alias.
     * It is designed to be used within SQL string templates to dynamically construct DELETE queries based on the
     * table's structure.
     *
     * Example usage in a string template:
     * ```
     * DELETE ${t(delete(MyTable::class, "t"))} FROM ${t(from(MyTable::class, "t"))}
     * WHERE ${t(where(record))}
     * ```
     *
     * Here, `record` is an instance of the [Data] class containing the criteria for deletion.
     *
     * **Note:** In most databases, specifying the table in the DELETE clause with an alias
     * is not necessary; the DELETE statement can be constructed with only a FROM clause and an alias:
     * ```
     * DELETE FROM ${t(from(MyTable::class, "t"))}
     * WHERE ${t(where(record))}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @return an [Element] representing the DELETE clause for the specified table with an alias.
     */
    fun delete(table: KClass<out Data>, alias: String): Element = Elements.Delete(table.java, alias)

    /**
     * Generates a Table element for the specified table class.
     *
     * This method creates a representation of a database table, which can be used in SQL string templates
     * to refer to the table in queries. It is useful when you need to explicitly specify the table in parts of
     * your query where the SQL template engine does not automatically resolve the table based on context.
     *
     * Example usage in a string template:
     * ```
     * SELECT * FROM ${t(table(MyTable::class))}
     * ```
     *
     * For convenience, you can also use the shorthand notation. If the SQL template engine cannot resolve
     * `${t(MyTable::class)}` into a specific element based on its placement in the query (e.g., after SELECT, FROM, etc.),
     * it will default to creating a Table element:
     * ```
     * ... ${t(MyTable::class)} ...
     * ```
     *
     * However, if `${t(MyTable::class)}` is followed by a dot `'.'`, the SQL template engine will resolve it into an
     * alias element, representing the alias of the table in the query:
     * ```
     * SELECT ${t(MyTable::class)}.column_name FROM ${t(MyTable::class)}
     * ```
     *
     * As per the resolution rules:
     * - If `${t(MyTable::class)}` is placed after keywords like SELECT, FROM, INSERT INTO, UPDATE, or DELETE,
     *   the SQL template engine resolves it into the appropriate element (e.g., SELECT element, FROM element).
     * - If `${t(MyTable::class)}` is not in such a placement and is not followed by a dot `'.'`,
     *   it is resolved into a table element.
     * - If `${t(MyTable::class)}` is followed by a dot `'.'`, it is resolved into an alias element.
     *
     * @param table the [KClass] object representing the table record.
     * @return an [Element] representing the table.
     */
    fun table(table: KClass<out Data>): Element = Elements.Table(table.java)

    /**
     * Generates a Table element with an alias for the specified table class.
     *
     * This method creates a representation of a database table with the specified alias, which can be used in
     * SQL string templates to refer to the table in queries. This is useful when you need to assign an alias to
     * a table for use in your query.
     *
     * Example usage in a string template:
     * ```
     * SELECT * FROM ${t(table(MyTable::class, "t"))}
     * ```
     *
     * You can refer to the table alias in your query as follows:
     * ```
     * SELECT ${t(alias(MyTable::class))}.column_name FROM ${t(table(MyTable::class, "t"))}
     * ```
     *
     * @param table the [KClass] object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @return an [Element] representing the table with an alias.
     */
    fun table(table: KClass<out Data>, alias: String): Element = Elements.Table(table.java, alias)

    /**
     * Generates an alias element for the specified table class.
     *
     * This method returns the alias of the table as used in the query. It is useful when you need to refer to
     * the table's alias, especially in situations where the SQL template engine cannot automatically determine
     * the appropriate element based on context.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(alias(MyTable::class))}.column_name FROM ${t(table(MyTable::class, "t"))}
     * ```
     *
     * According to the resolution rules, if `${t(MyTable::class)}` is followed by a dot `'.'`, the SQL template engine
     * automatically resolves it into an alias element:
     * ```
     * SELECT ${t(MyTable::class)}.column_name FROM ${t(MyTable::class)}
     * ```
     *
     * As per the resolution rules:
     * - If `${t(MyTable::class)}` is placed after keywords like SELECT, FROM, INSERT INTO, UPDATE, or DELETE,
     *   the SQL template engine resolves it into the appropriate element (e.g., SELECT element, FROM element).
     * - If `${t(MyTable::class)}` is not in such a placement and is not followed by a dot `'.'`,
     *   it is resolved into a table element.
     * - If `${t(MyTable::class)}` is followed by a dot `'.'`, it is resolved into an alias element.
     *
     * @param table the [KClass] object representing the table record.
     * @return an [Element] representing the table's alias.
     */
    fun alias(table: KClass<out Data>): Element = Elements.Alias(table.java, ResolveScope.CASCADE)

    /**
     * Generates an alias element for the specified table class with a specific resolution scope.
     *
     * This method returns the alias of the table as used in the query. It is useful when you need to refer to
     * the table's alias, especially in situations where the SQL template engine cannot automatically determine
     * the appropriate element based on context.
     *
     * Example usage in a string template:
     * ```
     * SELECT ${t(alias(MyTable::class, ResolveScope.LOCAL))}.column_name FROM ${t(table(MyTable::class, "t"))}
     * ```
     *
     * According to the resolution rules, if `${t(MyTable::class)}` is followed by a dot `'.'`, the SQL template engine
     * automatically resolves it into an alias element:
     * ```
     * SELECT ${t(MyTable::class)}.column_name FROM ${t(MyTable::class)}
     * ```
     *
     * As per the resolution rules:
     * - If `${t(MyTable::class)}` is placed after keywords like SELECT, FROM, INSERT INTO, UPDATE, or DELETE,
     *   the SQL template engine resolves it into the appropriate element (e.g., SELECT element, FROM element).
     * - If `${t(MyTable::class)}` is not in such a placement and is not followed by a dot `'.'`,
     *   it is resolved into a table element.
     * - If `${t(MyTable::class)}` is followed by a dot `'.'`, it is resolved into an alias element.
     *
     * @param table the [KClass] object representing the table record.
     * @param scope the [ResolveScope] to use when resolving the alias. Use CASCADE to include local and outer
     * aliases, LOCAL to include local aliases only, and OUTER to include outer aliases only.
     * @return an [Element] representing the table's alias.
     */
    fun alias(table: KClass<out Data>, scope: ResolveScope): Element = Elements.Alias(table.java, scope)

    /**
     * Generates a column element for a column specified by the given [field] in a type safe manner.
     *
     * Example usage in a string template where `MyTable` is referenced twice:
     * ```
     * // Define a record with two references to MyTable
     * data class Table(val id: Int, val child: MyTable, val parent: MyTable)
     *
     * // In the SQL template
     * SELECT ${t(column(MyTable_.child.name))} FROM ${t(MyTable::class)}
     * ```
     *
     * In this example, the path "child" specifies that we are referring to the `child` field of the
     * `Table` record, which is of type `MyTable`. This distinguishes it from the `parent` field, which
     * is also of type `MyTable`. The "name" componentName refers to the name record component of `MyTable`.
     *
     * @param field specifies the field for which the column is to be generated.
     * @return an [Element] representing the table's column with the specified path.
     * @since 1.2
     */
    fun column(field: Metamodel<*, *>): Element = Elements.Column(field, ResolveScope.CASCADE)

    /**
     * Generates a column element for a column specified by the given [field] in a type safe manner with a specific
     * resolution scope.
     *
     * Example usage in a string template where `MyTable` is referenced twice:
     * ```
     * // Define a record with two references to MyTable
     * data class Table(val id: Int, val child: MyTable, val parent: MyTable)
     *
     * // In the SQL template
     * SELECT ${t(column(MyTable_.child.name, ResolveScope.LOCAL))} FROM ${t(MyTable::class)}
     * ```
     *
     * In this example, the path "child" specifies that we are referring to the `child` field of the
     * `Table` record, which is of type `MyTable`. This distinguishes it from the `parent` field, which
     * is also of type `MyTable`. The "name" componentName refers to the name record component of `MyTable`.
     *
     * @param field specifies the field for which the column is to be generated.
     * @param scope the [ResolveScope] to use when resolving the alias. Use CASCADE to include local and outer
     * aliases, LOCAL to include local aliases only, and OUTER to include outer aliases only.
     * @return an [Element] representing the table's column with the specified path.
     * @since 1.2
     */
    fun column(field: Metamodel<*, *>, scope: ResolveScope): Element = Elements.Column(field, scope)

    /**
     * Generates a parameter element for the specified value, to be used in SQL queries.
     *
     * This method creates a positional parameter for use in SQL string templates. The parameter
     * can be of any object type and may be null. It is intended to be used in places where
     * you need to bind a value to a SQL query parameter.
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE status = ${t(param(1))}
     * ```
     *
     * For convenience, you can also use the shorthand notation, where the SQL template engine
     * automatically detects that a parameter is required:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE status = ${t(1)}
     * ```
     *
     * As per the resolution rules:
     * - If `${t(1)}`, or any other primitive or object, is placed after keywords like VALUES, SET or WHERE,
     *   the SQL template engine resolves it into the appropriate element (e.g., VALUES element, SET element).
     * - If `${t(1)}` is not in such a placement, it is resolved into a param element.
     *
     * @param value the value to be used as a parameter in the SQL query; may be null.
     * @return an [Element] representing the parameter.
     */
    fun param(value: Any?): Element = Elements.Param(null, value)

    /**
     * Generates a named parameter element for the specified value, to be used in SQL queries.
     *
     * This method creates a named parameter for use in SQL string templates. Named parameters
     * are useful when you want to explicitly specify the parameter's name in the query, improving
     * readability and maintainability, especially in complex queries.
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE status = ${t(param("status", 1))}
     * ```
     *
     * In the query, the parameter will be referred to by its name `status`.
     *
     * @param name the name of the parameter; must not be null.
     * @param value the value to be used as a parameter in the SQL query; may be null.
     * @return an [Element] representing the named parameter.
     */
    fun param(name: String, value: Any?): Element = Elements.Param(name, value)

    /**
     * Generates a parameter element for the specified value with a converter function.
     *
     * This method allows you to provide a custom converter function to transform the value
     * into a database-compatible format. This is useful when the value needs to be converted
     * before being set as a parameter in the SQL query, such as formatting dates or custom objects.
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE created_at = ${t(param(dateValue) { java.sql.Date(it.time) })}
     * ```
     *
     * @param P the type of the value to be converted.
     * @param value the value to be used as a parameter in the SQL query; may be null.
     * @param converter a function that converts the value to a database-compatible format; must not be null.
     * @return an [Element] representing the parameter with a converter applied.
     */
    fun <P> param(value: P, converter: (P) -> Any?): Element = Elements.Param(null, value) {
        @Suppress("UNCHECKED_CAST")
        converter(it as P)
    }

    /**
     * Generates a named parameter element for the specified value with a converter function.
     *
     * This method allows you to provide a custom converter function to transform the value
     * into a database-compatible format and assign a name to the parameter. Named parameters
     * enhance query readability and are particularly useful in complex queries.
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE created_at = ${t(param("createdAt", dateValue) { java.sql.Date(it.time) })}
     * ```
     *
     * @param P the type of the value to be converted.
     * @param name the name of the parameter; must not be null.
     * @param value the value to be used as a parameter in the SQL query; may be null.
     * @param converter a function that converts the value to a database-compatible format; must not be null.
     * @return an [Element] representing the named parameter with a converter applied.
     */
    fun <P> param(name: String, value: P, converter: (P) -> Any?): Element = Elements.Param(name, value) {
        @Suppress("UNCHECKED_CAST")
        converter(it as P)
    }

    /**
     * Generates a parameter element for the specified [Date] value with a temporal type.
     *
     * This method creates a positional parameter for a [Date] value, converting it to the appropriate
     * SQL type based on the provided [TemporalType]. It is useful when you need to specify how the date
     * should be interpreted in the database (as DATE, TIME, or TIMESTAMP).
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE event_date = ${t(param(dateValue, TemporalType.DATE))}
     * ```
     *
     * @param value the [Date] value to be used as a parameter; must not be null.
     * @param temporalType the [TemporalType] specifying how the date should be handled; must not be null.
     * @return an [Element] representing the date parameter with the specified temporal type.
     */
    fun param(value: Date, temporalType: TemporalType): Element = param(value) {
        when (temporalType) {
            TemporalType.DATE -> java.sql.Date(it.time)
            TemporalType.TIME -> Time(it.time)
            TemporalType.TIMESTAMP -> Timestamp(it.time)
        }
    }

    /**
     * Generates a named parameter element for the specified [Date] value with a temporal type.
     *
     * This method creates a named parameter for a [Date] value, converting it to the appropriate
     * SQL type based on the provided [TemporalType]. Named parameters improve query readability
     * and are especially useful in complex queries.
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE event_date = ${t(param("eventDate", dateValue, TemporalType.DATE))}
     * ```
     *
     * @param name the name of the parameter; must not be null.
     * @param value the [Date] value to be used as a parameter; must not be null.
     * @param temporalType the [TemporalType] specifying how the date should be handled; must not be null.
     * @return an [Element] representing the named date parameter with the specified temporal type.
     */
    fun param(name: String, value: Date, temporalType: TemporalType): Element = param(name, value) {
        when (temporalType) {
            TemporalType.DATE -> java.sql.Date(it.time)
            TemporalType.TIME -> Time(it.time)
            TemporalType.TIMESTAMP -> Timestamp(it.time)
        }
    }

    /**
     * Generates a parameter element for the specified [Calendar] value with a temporal type.
     *
     * This method creates a positional parameter for a [Calendar] value, converting it to the appropriate
     * SQL type based on the provided [TemporalType]. It is useful when working with calendar instances
     * that need to be interpreted in the database as specific temporal types.
     *
     * Example usage in a string template:
     * ```
     * SELECT *
     * FROM ${t(MyTable::class)}
     * WHERE event_time = ${t(param(calendarValue, TemporalType.TIMESTAMP))}
     * ```
     *
     * @param value the [Calendar] value to be used as a parameter; must not be null.
     * @param temporalType the [TemporalType] specifying how the calendar should be handled; must not be null.
     * @return an [Element] representing the calendar parameter with the specified temporal type.
     */
    fun param(value: Calendar, temporalType: TemporalType): Element = param(value) {
        when (temporalType) {
            TemporalType.DATE -> java.sql.Date(it.timeInMillis)
            TemporalType.TIME -> Time(it.timeInMillis)
            TemporalType.TIMESTAMP -> Timestamp(it.timeInMillis)
        }
    }

    /**
     * Creates a bind-variable placeholder that extracts its value from a [Data] record when executing a batch.
     *
     * Use this when you prepare a statement once and then execute it multiple times with different records via
     * `addBatch(record)`. The [extractor] is invoked for each record to provide the parameter value.
     *
     * Example usage:
     * ```
     * val bindVars = orm.createBindVars()
     * val idVar = bindVar(bindVars) { (it as User).id }
     *
     * orm.query {
     *     """
     *     UPDATE ${t(User::class)}
     *     SET ${t(bindVars)}
     *     WHERE ${t(column(User_.id))} = ${t(idVar)}
     *     """
     * }.prepare().use { q ->
     *     users.forEach(q::addBatch)
     *     q.executeBatch()
     * }
     * ```
     *
     * @param bindVars the bind variable set this variable belongs to.
     * @param extractor extracts the value to bind for this variable from the record passed to `addBatch`.
     * @return an [Element] representing a single bind variable placeholder.
     */
    fun bindVar(bindVars: BindVars, extractor: (Data) -> Any): Element = Elements.BindVar(bindVars, extractor)

    /**
     * Creates a subquery element from a query builder.
     *
     * Use this to embed a nested `SELECT (...)` in a larger query, for example in `WHERE`, `IN`, `EXISTS`,
     * or projection expressions.
     *
     * If [correlate] is `true`, the subquery may reference aliases from the outer query (correlated subquery).
     * If `false`, the subquery is fully independent.
     *
     * Example usage:
     * ```
     * SELECT ${t(User::class)}
     * FROM ${t(User::class)}
     * WHERE id IN ${t(subquery(queryBuilder, false))}
     * ```
     *
     * @param builder the query builder that produces the subquery.
     * @param correlate whether the subquery may reference outer query aliases.
     * @return an [Element] representing the subquery.
     */
    fun subquery(builder: QueryBuilder<*, *, *>, correlate: Boolean): Element = Elements.Subquery((builder as Subqueryable).subquery, correlate)

    /**
     * Creates a subquery element from a [TemplateBuilder].
     *
     * This variant is useful when the subquery itself is most naturally expressed as a SQL template.
     *
     * If [correlate] is `true`, the subquery may reference aliases from the outer query.
     * If `false`, the subquery is independent.
     *
     * Example usage:
     * ```
     * SELECT ${t(User::class)}
     * FROM ${t(User::class)}
     * WHERE id IN ${t(subquery({ "SELECT user_id FROM active_users" }, false))}
     * ```
     *
     * @param builder builds the subquery template.
     * @param correlate whether the subquery may reference outer query aliases.
     * @return an [Element] representing the subquery.
     */
    fun subquery(builder: TemplateBuilder, correlate: Boolean): Element = subquery(builder.build(), correlate)

    /**
     * Creates a subquery element from a [TemplateString].
     *
     * If [correlate] is `true`, the subquery may reference aliases from the outer query.
     * If `false`, the subquery is independent.
     *
     * Example usage:
     * ```
     * SELECT ${t(User::class)}
     * FROM ${t(User::class)}
     * WHERE id IN ${t(subquery(template("SELECT user_id FROM active_users"), false))}
     * ```
     *
     * @param template the template representing the subquery.
     * @param correlate whether the subquery may reference outer query aliases.
     * @return an [Element] representing the subquery.
     */
    fun subquery(template: TemplateString, correlate: Boolean): Element = Elements.Subquery(template.unwrap, correlate)

    /**
     * Injects raw SQL into the query without any processing or sanitization.
     *
     * This method allows you to insert arbitrary SQL code directly into your query. It bypasses any
     * automatic parameter binding or SQL generation provided by the SQL template engine. As a result, it can be
     * potentially unsafe and may expose your application to SQL injection attacks if not used carefully.
     *
     * **Warning:** Use this method only when you are certain that the SQL string being injected
     * is safe and originates from a trusted source. Avoid using user-supplied input with this method.
     *
     * Example usage in a string template:
     * ```
     * SELECT * FROM ${t(User::class)} WHERE ${t(unsafe("city = 'Sunnyvale'"))}
     * ```
     *
     * In this example, the SQL fragment `"city = 'Sunnyvale'"` is injected directly into the query.
     *
     * @param sql the raw SQL string to inject into the query.
     * @return an [Element] that represents the raw SQL code to be inserted into the query.
     */
    fun unsafe(sql: String): Element = Elements.Unsafe(sql)
}
