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
package st.orm.core.template;

import static java.util.Objects.requireNonNull;
import static st.orm.Operator.IN;
import static st.orm.ResolveScope.CASCADE;
import static st.orm.SelectMode.NESTED;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.sql.Connection;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.function.Function;
import javax.sql.DataSource;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.ResolveScope;
import st.orm.SelectMode;
import st.orm.TemporalType;
import st.orm.core.repository.EntityRepository;
import st.orm.core.repository.ProjectionRepository;
import st.orm.core.template.impl.Elements;
import st.orm.core.template.impl.Elements.Alias;
import st.orm.core.template.impl.Elements.BindVar;
import st.orm.core.template.impl.Elements.Delete;
import st.orm.core.template.impl.Elements.From;
import st.orm.core.template.impl.Elements.Insert;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.Param;
import st.orm.core.template.impl.Elements.Select;
import st.orm.core.template.impl.Elements.Set;
import st.orm.core.template.impl.Elements.Subquery;
import st.orm.core.template.impl.Elements.Table;
import st.orm.core.template.impl.Elements.TableSource;
import st.orm.core.template.impl.Elements.TemplateSource;
import st.orm.core.template.impl.Elements.Unsafe;
import st.orm.core.template.impl.Elements.Update;
import st.orm.core.template.impl.Elements.Values;
import st.orm.core.template.impl.Elements.Where;
import st.orm.core.template.impl.Subqueryable;

/**
 * The {@code Templates} interface provides a collection of static methods for constructing SQL query elements
 * and creating ORM repository templates. It serves as a central point for building SQL queries or getting access to
 * repositories that can interact with databases in a type-safe and manner, supporting both JPA and JDBC.
 *
 * <p>This interface includes methods for generating SQL clauses such as {@code SELECT}, {@code FROM}, {@code WHERE},
 * {@code INSERT}, {@code UPDATE}, and {@code DELETE}, as well as utility methods for working with parameters,
 * tables, aliases, and more.
 *
 * <p>Additionally, the {@code Templates} interface provides methods to create {@link ORMTemplate}
 * instances for use with different data sources like JDBC's {@link DataSource} or {@link Connection}, or
 * JPA's {@code EntityManager} (see {@link JpaTemplate}).
 *
 * <h2>Using Templates</h2>
 *
 * <p>Define the records to use them to construct the query using templates:</p>
 *
 * <pre>{@code
 * record City(int id, String name, long population) {};
 *
 * record User(int id, String name, int age, LocalDate birthDate,
 *             String street, String postalCode, int cityId) {};
 * }</pre>
 *
 * <h3>Create</h3>
 *
 * <p>Insert a user into the database. The template engine also supports insertion of multiple entries by passing an
 * array (or list) of objects or primary keys. Alternatively, insertion can also be executed in batch mode using
 * {@link BindVars}.</p>
 * <pre>{@code
 * User user = ...;
 * ORM(dataSource).query(RAW."""
 *         INSERT INTO \{User.class}
 *         VALUES \{user}""");
 * }</pre>
 *
 * <h3>Read</h3>
 *
 * <p>Select all users from the database that are linked to City with primary key 1. The value is passed to the
 * underling JDBC or JPA system as variable. The results can also be retrieved as a stream of objects by using
 * {@link Query#getResultStream(Class)}.</p>
 * <pre>{@code
 * List<User> users = ORM(dataSource).query(RAW."""
 *         SELECT \{User.class}
 *         FROM \{User.class}
 *         WHERE \{User.class}.city_id = \{1}""")
 *     .getResultList(User.class);
 * }</pre>
 *
 * <h3>Update</h3>
 *
 * <p>Update a user in the database. The template engine also supports updates for multiple entries by passing an
 * array or list of objects. Alternatively, updates can be executed in batch mode by using {@link BindVars}.</p>
 * <pre>{@code
 * User user = ...;
 * ORM(dataSource).query(RAW."""
 *         UPDATE \{User.class}
 *         SET \{user}
 *         WHERE \{user}""");
 * }</pre>
 *
 * <h3>Delete</h3>
 *
 * <p>Delete user in the database. The template engine also supports updates for multiple entries by passing an
 * array (or list) of objects or primary keys. Alternatively, deletion can be executed in batch mode by using
 * {@link BindVars}.</p>
 * <pre>{@code
 * User user = ...;
 * ORM(dataSource).query(RAW."""
 *         DELETE FROM \{User.class}
 *         WHERE \{user}""");
 * }</pre>
 **
 * <h2>Howto start</h2>
 *
 * <p>The {@code Templates} interface provides static methods to create {@link ORMTemplate} instances based on your data source:
 *
 * <h3>Using EntityManager (JPA)</h3>
 * <pre>{@code
 * EntityManager entityManager = ...;
 * ORMTemplate orm = Templates.ORM(entityManager);
 * }</pre>
 *
 * <h3>Using DataSource (JDBC)</h3>
 * <pre>{@code
 * DataSource dataSource = ...;
 * ORMTemplate orm = Templates.ORM(dataSource);
 * }</pre>
 *
 * <h3>Using Connection (JDBC)</h3>
 *
 * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.
 *
 * <pre>{@code
 * Connection connection = ...;
 * ORMTemplate orm = Templates.ORM(connection);
 * }</pre>
 *
 * @see EntityRepository
 * @see ProjectionRepository
 */
public interface Templates {

    /**
     * Generates a SELECT element for the specified table class.
     *
     * <p>This method creates a {@code SELECT} clause for the provided table record, including all of its
     * columns as well as columns from any foreign key relationships defined within the record. It is
     * designed to be used within SQL string templates to dynamically construct queries based on the
     * table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{select(MyTable.class)}
     * FROM \{from(MyTable.class)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine
     * automatically detects that a SELECT element is required based on its placement in the query:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @return an {@link Element} representing the SELECT clause for the specified table.
     */
    static Element select(Class<? extends Data> table) {
        return select(table, NESTED);
    }

    /**
     * Generates a SELECT element for the specified table class.
     *
     * <p>This method creates a {@code SELECT} clause for the provided table record, including all of its
     * columns as well as columns from any foreign key relationships defined within the record. It is
     * designed to be used within SQL string templates to dynamically construct queries based on the
     * table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{select(MyTable.class, NESTED)}
     * FROM \{from(MyTable.class)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine
     * automatically detects that a SELECT element is required based on its placement in the query:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @param mode NESTED to include the full object hierarchy, FLAT to include only the main table's fields, PK to
     * include only the primary key fields.
     * @return an {@link Element} representing the SELECT clause for the specified table.
     */
    static Element select(@Nonnull Class<? extends Data> table, @Nonnull SelectMode mode) {
        return new Select(table, mode);
    }

    /**
     * Generates a FROM element for the specified table class without an alias and optional auto-joining of foreign
     * keys.
     *
     * <p>This method creates a {@code FROM} clause for the provided table record. If {@code autoJoin} is set
     * to {@code true}, it will automatically include JOIN clauses for all foreign keys defined in the record.
     * This facilitates constructing complex queries that involve related tables without manually specifying each join.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{select(MyTable.class)}
     * FROM \{from(MyTable.class, true)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. In this case, {@code autoJoin}
     * defaults to {@code false}. The SQL template engine automatically detects that a FROM element is required
     * based on its placement in the query:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @param autoJoin if {@code true}, automatically join all foreign keys listed in the record.
     * @return an {@link Element} representing the FROM clause for the specified table.
     */
    static Element from(@Nonnull Class<? extends Data> table, boolean autoJoin) {
        return new From(table, autoJoin);
    }

    /**
     * Generates a FROM element for the specified table class, with an alias and optional auto-joining of foreign keys.
     *
     * <p>This method creates a {@code FROM} clause for the provided table record, applying the specified alias.
     * If {@code autoJoin} is set to {@code true}, it will automatically include JOIN clauses for all foreign keys
     * defined in the record. This facilitates constructing complex queries that involve related tables without
     * manually specifying each join.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{select(Table.class)}
     * FROM \{from(MyTable.class, "t", true)}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @param autoJoin if {@code true}, automatically join all foreign keys listed in the record.
     * @return an {@link Element} representing the FROM clause for the specified table.
     */
    static Element from(@Nonnull Class<? extends Data> table, @Nonnull String alias, boolean autoJoin) {
        return new From(new TableSource(table), requireNonNull(alias, "alias"), autoJoin);
    }

    /**
     * Generates a FROM element using a provided SQL string template with an alias.
     *
     * <p>This method allows you to specify a custom {@link TemplateString} to be used as the source in the {@code FROM}
     * clause, applying the provided alias. This is useful when you need to include subqueries or complex table
     * expressions in your SQL queries.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{select(Table.class)}
     * FROM \{from(RAW."SELECT column_a, column_b FROM table", "t")}
     * }</pre>
     *
     * </p> in this context, the alias is mandatory and auto-joining of foreign keys is not applicable.</p>
     *
     * @param template the {@link TemplateString} representing the custom SQL to be used in the FROM clause.
     * @param alias the alias to assign to the frame clause in the query. The alias must not require escaping.
     * @return an {@link Element} representing the FROM clause with the specified template and alias.
     */
    static Element from(@Nonnull TemplateString template, @Nonnull String alias) {
        return new From(new TemplateSource(template), requireNonNull(alias, "alias"), false);
    }

    /**
     * Generates an INSERT element for the specified table class.
     *
     * <p>This method creates an {@code INSERT} clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct INSERT queries based on the table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * INSERT INTO \{insert(MyTable.class)}
     * VALUES \{values(entity)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an INSERT element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{entity}
     * }</pre>
     *
     * <p>Here, {@code entity} is an instance of the {@code Table} class containing the values to be inserted.
     *
     * @param table the {@link Class} object representing the table record.
     * @return an {@link Element} representing the INSERT clause for the specified table.
     */
    static Element insert(@Nonnull Class<? extends Data> table) {
        return new Insert(table);
    }
    /**
     * Generates an INSERT element for the specified table class.
     *
     * <p>This method creates an {@code INSERT} clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct INSERT queries based on the table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * INSERT INTO \{insert(MyTable.class)}
     * VALUES \{values(entity)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an INSERT element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{entity}
     * }</pre>
     *
     * <p>Here, {@code entity} is an instance of the {@code Table} class containing the values to be inserted.
     *
     * @param table the {@link Class} object representing the table record.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @return an {@link Element} representing the INSERT clause for the specified table.
     */
    static Element insert(@Nonnull Class<? extends Data> table, boolean ignoreAutoGenerate) {
        return new Insert(table, ignoreAutoGenerate);
    }

    /**
     * Generates a VALUES clause for the specified record instance(s).
     *
     * <p>This method creates a {@code VALUES} clause using the provided {@link Data} instance(s).
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{values(entity1, entity2)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{new Data[] {entity1, entity2}}
     * }</pre>
     *
     * <p>Here, {@code entity1}, {@code entity2}, etc., are instances of the {@code Data} class containing
     * the values to be inserted.
     *
     * @param r one or more {@link Data} instances containing the values to be inserted.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @return an {@link Element} representing the VALUES clause with the specified records.
     */
    static Element values(@Nonnull Data r, boolean ignoreAutoGenerate) {
        return new Values(List.of(r), null, ignoreAutoGenerate);
    }

    /**
     * Generates a VALUES clause for the specified record instance(s).
     *
     * <p>This method creates a {@code VALUES} clause using the provided {@link Data} instance(s).
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{values(entity1, entity2)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{new Data[] {entity1, entity2}}
     * }</pre>
     *
     * <p>Here, {@code entity1}, {@code entity2}, etc., are instances of the {@code Data} class containing
     * the values to be inserted.
     *
     * @param r one or more {@link Data} instances containing the values to be inserted.
     * @return an {@link Element} representing the VALUES clause with the specified records.
     */
    static Element values(@Nonnull Data... r) {
        return new Values(Arrays.asList(r), null);
    }

    /**
     * Generates a VALUES clause for the specified iterable of record instances.
     *
     * <p>This method creates a {@code VALUES} clause using the provided {@link Iterable} of {@link Data} instances.
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{values(records)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{records}
     * }</pre>
     *
     * <p>Here, {@code records} is an {@link Iterable} of {@code Data} instances containing
     * the values to be inserted.
     *
     * @param records an {@link Iterable} of {@link Data} instances containing the values to be inserted.
     * @return an {@link Element} representing the VALUES clause with the specified records.
     */
    static Element values(@Nonnull Iterable<? extends Data> records) {
        return new Values(records, null);
    }

    /**
     * Generates a VALUES clause for the specified iterable of record instances.
     *
     * <p>This method creates a {@code VALUES} clause using the provided {@link Iterable} of {@link Data} instances.
     * It is intended to be used within SQL string templates to dynamically construct INSERT statements with
     * the given values.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{values(records)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{records}
     * }</pre>
     *
     * <p>Here, {@code records} is an {@link Iterable} of {@code Data} instances containing
     * the values to be inserted.
     *
     * @param records an {@link Iterable} of {@link Data} instances containing the values to be inserted.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @return an {@link Element} representing the VALUES clause with the specified records.
     */
    static Element values(@Nonnull Iterable<? extends Data> records, boolean ignoreAutoGenerate) {
        return new Values(records, null, ignoreAutoGenerate);
    }

    /**
     * Generates a VALUES clause using the specified {@link BindVars} for batch insertion.
     *
     * <p>This method creates a {@code VALUES} clause that utilizes a {@link BindVars} instance, allowing for batch
     * insertion of records using bind variables. This is particularly useful when performing batch operations where
     * the same query is executed multiple times with different variable values.
     *
     * <p>Example usage in a batch insertion scenario:
     * <pre>{@code
     * var bindVars = orm.createBindVars();
     * try (var query = orm.query(RAW."""
     *         INSERT INTO \{MyTable.class}
     *         VALUES \{values(bindVars)}""").prepare()) {
     *     records.forEach(query::addBatch);
     *     query.executeBatch();
     * }
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{bindVars}
     * }</pre>
     *
     * <p>In this example, {@code bindVars} is a {@link BindVars} instance created by the ORM. The {@code records} are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the {@link BindVars} instance used for batch insertion.
     * @return an {@link Element} representing the VALUES clause utilizing the specified bind variables.
     */
    static Element values(@Nonnull BindVars bindVars) {
        return new Values(null, requireNonNull(bindVars, "bindVars"));
    }

    /**
     * Generates a VALUES clause using the specified {@link BindVars} for batch insertion.
     *
     * <p>This method creates a {@code VALUES} clause that utilizes a {@link BindVars} instance, allowing for batch
     * insertion of records using bind variables. This is particularly useful when performing batch operations where
     * the same query is executed multiple times with different variable values.
     *
     * <p>Example usage in a batch insertion scenario:
     * <pre>{@code
     * var bindVars = orm.createBindVars();
     * try (var query = orm.query(RAW."""
     *         INSERT INTO \{MyTable.class}
     *         VALUES \{values(bindVars)}""").prepare()) {
     *     records.forEach(query::addBatch);
     *     query.executeBatch();
     * }
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically
     * detects that a VALUES element is required based on its placement in the query:
     * <pre>{@code
     * INSERT INTO \{MyTable.class}
     * VALUES \{bindVars}
     * }</pre>
     *
     * <p>In this example, {@code bindVars} is a {@link BindVars} instance created by the ORM. The {@code records} are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the {@link BindVars} instance used for batch insertion.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @return an {@link Element} representing the VALUES clause utilizing the specified bind variables.
     */
    static Element values(@Nonnull BindVars bindVars, boolean ignoreAutoGenerate) {
        return new Values(null, requireNonNull(bindVars, "bindVars"), ignoreAutoGenerate);
    }

    /**
     * Generates an UPDATE element for the specified table class.
     *
     * <p>This method creates an {@code UPDATE} clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct UPDATE queries based on the table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * UPDATE \{update(MyTable.class)}
     * SET \{set(record)}
     * WHERE \{where(record)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an UPDATE element is required based on its placement in the query:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{record}
     * WHERE \{record}
     * }</pre>
     *
     * <p>Here, {@code record} is an instance of the {@code Data} class containing the values to be updated.
     *
     * @param table the {@link Class} object representing the table record.
     * @return an {@link Element} representing the UPDATE clause for the specified table.
     */
    static Element update(@Nonnull Class<? extends Data> table) {
        return new Update(table);
    }

    /**
     * Generates an UPDATE element for the specified table class with an alias.
     *
     * <p>This method creates an {@code UPDATE} clause for the provided table record, applying the specified alias.
     * It is designed to be used within SQL string templates to dynamically construct UPDATE queries based on the
     * table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * UPDATE \{update(MyTable.class, "t")}
     * SET \{set(record)}
     * WHERE \{where(record)}
     * }</pre>
     *
     * <p>Here, {@code record} is an instance of the {@code Data} class containing the values to be updated.
     *
     * @param table the {@link Class} object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @return an {@link Element} representing the UPDATE clause for the specified table with alias.
     */
    static Element update(@Nonnull Class<? extends Data> table, @Nonnull String alias) {
        return new Update(table, alias);
    }

    /**
     * Generates a SET clause for the specified record.
     *
     * <p>This method creates a {@code SET} clause using the provided {@link Data} instance. It is intended to be used
     * within SQL string templates to dynamically construct UPDATE statements with the given values.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{set(record)}
     * WHERE \{where(record)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects
     * that a SET element is required based on its placement in the query:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{record}
     * WHERE \{record}
     * }</pre>
     *
     * <p>Here, {@code record} is an instance of the {@code Data} class containing the values to be set.
     *
     * @param record the {@link Data} instance containing the values to be set.
     * @return an {@link Element} representing the SET clause with the specified record.
     */
    static Element set(@Nonnull Data record) {
        return new Set(requireNonNull(record, "record"), null, List.of());
    }

    /**
     * Generates a SET clause for the specified record.
     *
     * <p>This method creates a {@code SET} clause using the provided {@link Data} instance. It is intended to be used
     * within SQL string templates to dynamically construct UPDATE statements with the given values.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{set(record)}
     * WHERE \{where(record)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects
     * that a SET element is required based on its placement in the query:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{record}
     * WHERE \{record}
     * }</pre>
     *
     * <p>Here, {@code record} is an instance of the {@code Data} class containing the values to be set.
     *
     * @param record the {@link Data} instance containing the values to be set.
     * @param fields the fields to update.
     * @return an {@link Element} representing the SET clause with the specified record.
     * @since 1.7
     */
    static Element set(@Nonnull Data record, @Nonnull Collection<Metamodel<?, ?>> fields) {
        return new Set(requireNonNull(record, "record"), null, fields);
    }

    /**
     * Generates a SET clause using the specified {@link BindVars}.
     *
     * <p>This method creates a {@code SET} clause that utilizes a {@link BindVars} instance, allowing for batch
     * updates using bind variables. This is particularly useful when performing batch operations where the same
     * update query is executed multiple times with different variable values.
     *
     * <p>Example usage in a batch update scenario:
     * <pre>{@code
     * var bindVars = orm.createBindVars();
     * try (var query = orm.query(RAW."""
     *         UPDATE \{MyTable.class}
     *         SET \{set(bindVars)}
     *         WHERE \{where(bindVars)}""").prepare()) {
     *     records.forEach(query::addBatch);
     *     query.executeBatch();
     * }
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an UPDATE element is required based on its placement in the query:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{record}
     * WHERE \{record}
     * }</pre>
     *
     * <p>In this example, {@code bindVars} is a {@link BindVars} instance created by the ORM. The {@code records} are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the {@link BindVars} instance used for batch updates.
     * @return an {@link Element} representing the SET clause utilizing the specified bind variables.
     */
    static Element set(@Nonnull BindVars bindVars) {
        return new Set(null, requireNonNull(bindVars, "bindVars"), List.of());
    }

    /**
     * Generates a SET clause using the specified {@link BindVars}.
     *
     * <p>This method creates a {@code SET} clause that utilizes a {@link BindVars} instance, allowing for batch
     * updates using bind variables. This is particularly useful when performing batch operations where the same
     * update query is executed multiple times with different variable values.
     *
     * <p>Example usage in a batch update scenario:
     * <pre>{@code
     * var bindVars = orm.createBindVars();
     * try (var query = orm.query(RAW."""
     *         UPDATE \{MyTable.class}
     *         SET \{set(bindVars)}
     *         WHERE \{where(bindVars)}""").prepare()) {
     *     records.forEach(query::addBatch);
     *     query.executeBatch();
     * }
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * an UPDATE element is required based on its placement in the query:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{record}
     * WHERE \{record}
     * }</pre>
     *
     * <p>In this example, {@code bindVars} is a {@link BindVars} instance created by the ORM. The {@code records} are
     * iterated over, and each is added to the batch. The query is then executed as a batch operation.
     *
     * @param bindVars the {@link BindVars} instance used for batch updates.
     * @param fields the fields to update.
     * @return an {@link Element} representing the SET clause utilizing the specified bind variables.
     * @since 1.7
     */
    static Element set(@Nonnull BindVars bindVars, @Nonnull Collection<Metamodel<?, ?>> fields) {
        return new Set(null, requireNonNull(bindVars, "bindVars"), fields);
    }

    /**
     * Generates a WHERE clause based on the provided iterable of values or records.
     *
     * <p>This method creates a {@code WHERE} clause that matches the primary key(s) of the root table,
     * a record instance of the root table, or foreign key(s) in the hierarchy against the provided records
     * using the {@code IN} operator. It is useful when you want to select records where the primary key,
     * a specific record, or related foreign keys match any of the values in the iterable.
     *
     * <p>The objects in the iterable can be:
     * <ul>
     *   <li>Primitive values matching the primary key of the root table.</li>
     *   <li>Instances of {@link Record} matching the compound primary key of the root table.</li>
     *   <li>Instances of {@link Data} representing records of related (foreign key) tables in the hierarchy of the
     *   root table.</li>
     * </ul>
     *
     * <p>Example usage with primary key values:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(listOfIds)}
     * }</pre>
     *
     * <p>Example usage with records:
     * <pre>{@code
     * List<Table> entities = List.of(entity1, entity2);
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(entities)}
     * }</pre>
     *
     * <p>In this example, the query selects all entries in {@code Table} that are linked to any of the otherTables in the
     * list.
     *
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a WHERE element is required based on its placement in the query:
     * <pre>{@code
     * List<Table> entities = List.of(entity1, entity2);
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{entities}
     * }</pre>
     *
     * <p>As per the resolution rules:
     * <ul>
     *   <li>If {@code \{entities}}, or any other primitive or object, is placed after WHERE the SQL template engine
     *   resolves it into a WHERE element.</li>
     *   <li>If {@code \{entities}}, or any other primitive or object, is placed after keywords like VALUES, SET, the SQL
     *   template engine resolves it into the appropriate element (e.g., VALUES element, SET element).</li>
     *   <li>If {@code \{entities}} is not in such a placement, it is resolved into a param element.</li>
     * </ul>
     *
     * @param it an {@link Iterable} of values or records to match against the primary key(s) or foreign keys.
     * @return an {@link Element} representing the WHERE clause.
     */
    static Element where(@Nonnull Iterable<?> it) {
        return new Where(new ObjectExpression(IN, it), null);
    }

    /**
     * Generates a WHERE clause based on the provided value or record.
     *
     * <p>This method creates a {@code WHERE} clause that matches the primary key(s) of the root table,
     * a record instance of the root table, or foreign key(s) in the hierarchy against the provided records
     * using the {@code EQUALS} operator. It is useful when you want to select records where the primary key,
     * a specific record, or related foreign keys match the object.
     *
     * <p>The object can be:
     * <ul>
     *   <li>Primitive values matching the primary key of the root table.</li>
     *   <li>Instances of {@link Record} matching the compound primary key of the root table.</li>
     *   <li>Instances of {@link Data} representing records of related (foreign key) tables in the hierarchy of the
     *   root table.</li>
     * </ul>
     *
     * <p>Example usage with a primary key value:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(id)}
     * }</pre>
     *
     * <p>Example usage with a record:
     * <pre>{@code
     * Table entity = ...;
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(otherTable)}
     * }</pre>
     *
     * <p>In this example, the query selects all entries in {@code Table} that are linked to the specified {@code otherTable}.
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a WHERE element is required based on its placement in the query:
     * <pre>{@code
     * Table entity = ...;
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{entity}
     * }</pre>
     *
     * <p>As per the resolution rules:
     * <ul>
     *   <li>If {@code \{entity}}, or any other primitive or object, is placed after WHERE the SQL template engine
     *   resolves it into a WHERE element.</li>
     *   <li>If {@code \{entity}}, or any other primitive or object, is placed after keywords like VALUES, SET, the SQL
     *   template engine resolves it into the appropriate element (e.g., VALUES element, SET element).</li>
     *   <li>If {@code \{entity}} is not in such a placement, it is resolved into a param element.</li>
     * </ul>
     *
     * @param o the value or record to match against the primary key or foreign key.
     * @return an {@link Element} representing the WHERE clause.
     */
    static Element where(@Nonnull Object o) {
        return new Where(new ObjectExpression(o), null);
    }

    /**
     * Generates a WHERE clause based on the provided path, operator, and iterable of values or records.
     *
     * <p>This method creates a {@code WHERE} clause for the specified column or path using the given
     * operator and values or records. The {@code path} parameter specifies the column or property to apply the
     * condition on, which can include nested properties using dot notation.
     *
     * <p>The objects in the iterable must match the type of the record component found at the specified path.
     * If the path points to a record, the objects may also match the primary key type of that record.</pz>
     *
     * <p>Example usage with primary keys:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(MyTable_.otherTable.id, Operator.IN, listOfIds)}
     * }</pre>
     *
     * <p>In this example, {@code listOfIds} contains the primary key values of the {@code MyTable} records,
     * and the query selects all entries in {@code Table} linked to that MyTable.</p>
     *
     * <p>Example usage with records:
     * <pre>{@code
     * List<MyTable> entities = ...;
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(MyTable_.otherTable, Operator.IN, entities)}
     * }</pre>
     *
     * <p>In this example, {@code entities} is a list of {@code MyTable} records. The query matches entries in
     * {@code Table} linked to any of the records in the list via their foreign keys.</p>
     *
     * @param path the path or column name to apply the condition on.
     * @param operator the {@link Operator} to use in the condition.
     * @param it an {@link Iterable} of values or records for the condition.
     * @return an {@link Element} representing the WHERE clause.
     */
    static <V> Element where(@Nonnull Metamodel<?, V> path, @Nonnull Operator operator, @Nonnull Iterable<? extends V> it) {
        return new Where(new ObjectExpression(path, operator, it), null);
    }

    /**
     * Generates a WHERE clause based on the provided path, operator, and values or records.
     *
     * <p>This method creates a {@code WHERE} clause for the specified column or path using the given
     * operator and values or records. The {@code path} parameter specifies the column or property to apply the
     * condition on, which can include nested properties using dot notation.
     *
     * <p>The objects in the array must match the type of the record component found at the specified path.
     * If the path points to a record, the objects may also match the primary key type of that record.</pz>
     *
     * <p>Example usage with primary keys:
     * <pre>{@code
     * SELECT \{MyTable.class}
     * FROM \{MyTable.class}
     * WHERE \{where(MyTable_.otherTable.id, Operator.BETWEEN, 1, 10)}
     * }</pre>
     *
     * <p>In this example, the query selects all entries in {@code Table} where the associated {@code MyTable}
     * records have primary keys between {@code 1} and {@code 10}.
     *
     * @param path the path or column name to apply the condition on.
     * @param operator the {@link Operator} to use in the condition.
     * @param o the values or records for the condition.
     * @return an {@link Element} representing the WHERE clause.
     */
    @SafeVarargs
    static <V> Element where(@Nonnull Metamodel<?, V> path, @Nonnull Operator operator, @Nonnull V... o) {
        return new Where(new ObjectExpression(path, operator, o), null);
    }

    /**
     * Generates a WHERE clause using the specified {@link BindVars} for batch operations.
     *
     * <p>This method is particularly useful when performing batch operations where the same query is executed
     * multiple times with different parameter values. The {@link BindVars} instance allows for parameterized
     * queries, enhancing performance and security by preventing SQL injection and enabling query plan reuse.
     *
     * <p>Example usage in a batch operation:
     * <pre>{@code
     * var bindVars = orm.createBindVars();
     * try (var query = orm.query(RAW."""
     *         UPDATE \{MyTable.class}
     *         SET \{bindVars}
     *         WHERE \{where(bindVars)}""").prepare()) {
     *     records.forEach(query::addBatch);
     *     query.executeBatch();
     * }
     * }</pre>
     *
     * <p>In this example, the {@code bindVars} instance is used to bind variables for the WHERE clause in a batch
     * operation. Each record in {@code records} provides the parameter values for a single execution of the query.
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a WHERE element is required based on its placement in the query:
     * <pre>{@code
     * UPDATE \{MyTable.class}
     * SET \{bindVars}
     * WHERE \{bindVars}
     * }</pre>
     *
     * @param bindVars the {@link BindVars} instance used for binding variables in the WHERE clause; must not be
     * {@code null}.
     * @return an {@link Element} representing the WHERE clause utilizing the specified bind variables.
     */
    static Element where(@Nonnull BindVars bindVars) {
        return new Where( null, requireNonNull(bindVars, "bindVars"));
    }

    /**
     * Generates a DELETE element for the specified table class.
     *
     * <p>This method creates a {@code DELETE} clause for the provided table record. It is designed to be used
     * within SQL string templates to dynamically construct DELETE queries based on the table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * DELETE \{delete(MyTable.class)} FROM \{from(MyTable.class)}
     * WHERE \{where(record)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. The SQL template engine automatically detects that
     * a DELETE element is required based on its placement in the query:
     * <pre>{@code
     * DELETE \{MyTable.class} FROM \{MyTable.class}
     * WHERE \{record}
     * }</pre>
     *
     * <p>Here, {@code record} is an instance of the {@link Data} class containing the criteria for deletion.
     *
     * <p><strong>Note:</strong> In most databases, specifying the table in the DELETE clause is not
     * necessary, or even disallowed; the DELETE statement is usually constructed with only a FROM clause:</p>
     *
     * <pre>{@code
     * DELETE FROM \{from(MyTable.class)}
     * WHERE \{where(record)}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @return an {@link Element} representing the DELETE clause for the specified table.
     */
    static Element delete(@Nonnull Class<? extends Data> table) {
        return new Delete(table);
    }

    /**
     * Generates a DELETE element for the specified table class with an alias.
     *
     * <p>This method creates a {@code DELETE} clause for the provided table record, applying the specified alias.
     * It is designed to be used within SQL string templates to dynamically construct DELETE queries based on the
     * table's structure.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * DELETE \{delete(MyTable.class, "t")} FROM \{from(MyTable.class, "t")}
     * WHERE \{where(record)}
     * }</pre>
     *
     * <p>Here, {@code record} is an instance of the {@link Data} class containing the criteria for deletion.
     *
     * <p><strong>Note:</strong> In most databases, specifying the table in the DELETE clause with an alias
     * is not necessary; the DELETE statement can be constructed with only a FROM clause and an alias:</p>
     *
     * <pre>{@code
     * DELETE FROM \{from(MyTable.class, "t")}
     * WHERE \{where(record)}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @return an {@link Element} representing the DELETE clause for the specified table with an alias.
     */
    static Element delete(@Nonnull Class<? extends Data> table, @Nonnull String alias) {
        return new Delete(table, alias);
    }

    /**
     * Generates a Table element for the specified table class.
     *
     * <p>This method creates a representation of a database table, which can be used in SQL string templates
     * to refer to the table in queries. It is useful when you need to explicitly specify the table in parts of
     * your query where the SQL template engine does not automatically resolve the table based on context.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT * FROM \{table(MyTable.class)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation. If the SQL template engine cannot resolve
     * {@code \{MyTable.class}} into a specific element based on its placement in the query (e.g., after SELECT, FROM, etc.),
     * it will default to creating a Table element:
     * <pre>{@code
     * ... \{MyTable.class} ...
     * }</pre>
     *
     * <p>However, if {@code \{MyTable.class}} is followed by a dot {@code '.'}, the SQL template engine will resolve it into an
     * alias element, representing the alias of the table in the query:
     * <pre>{@code
     * SELECT \{MyTable.class}.column_name FROM \{MyTable.class}
     * }</pre>
     *
     * <p>As per the resolution rules:
     * <ul>
     *   <li>If {@code \{MyTable.class}} is placed after keywords like SELECT, FROM, INSERT INTO, UPDATE, or DELETE,
     *       the SQL template engine resolves it into the appropriate element (e.g., SELECT element, FROM element).</li>
     *   <li>If {@code \{MyTable.class}} is not in such a placement and is not followed by a dot {@code '.'},
     *       it is resolved into a table element.</li>
     *   <li>If {@code \{MyTable.class}} is followed by a dot {@code '.'}, it is resolved into an alias element.</li>
     * </ul>
     *
     * @param table the {@link Class} object representing the table record.
     * @return an {@link Element} representing the table.
     */
    static Element table(@Nonnull Class<? extends Data> table) {
        return new Table(table);
    }

    /**
     * Generates a Table element with an alias for the specified table class.
     *
     * <p>This method creates a representation of a database table with the specified alias, which can be used in
     * SQL string templates to refer to the table in queries. This is useful when you need to assign an alias to
     * a table for use in your query.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT * FROM \{table(MyTable.class, "t")}
     * }</pre>
     *
     * <p>You can refer to the table alias in your query as follows:
     * <pre>{@code
     * SELECT \{alias(MyTable.class)}.column_name FROM \{table(MyTable.class, "t")}
     * }</pre>
     *
     * @param table the {@link Class} object representing the table record.
     * @param alias the alias to use for the table in the query. The alias must not require escaping.
     * @return an {@link Element} representing the table with an alias.
     */
    static Element table(@Nonnull Class<? extends Data> table, @Nonnull String alias) {
        return new Table(table, alias);
    }

    /**
     * Generates an alias element for the specified table class.
     *
     * <p>This method returns the alias of the table as used in the query. It is useful when you need to refer to
     * the table's alias, especially in situations where the SQL template engine cannot automatically determine
     * the appropriate element based on context.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{alias(MyTable.class)}.column_name FROM \{table(MyTable.class, "t")}
     * }</pre>
     *
     * <p>According to the resolution rules, if {@code \{MyTable.class}} is followed by a dot {@code '.'}, the SQL template engine
     * automatically resolves it into an alias element:
     * <pre>{@code
     * SELECT \{MyTable.class}.column_name FROM \{MyTable.class}
     * }</pre>
     *
     * <p>As per the resolution rules:
     * <ul>
     *   <li>If {@code \{MyTable.class}} is placed after keywords like SELECT, FROM, INSERT INTO, UPDATE, or DELETE,
     *       the SQL template engine resolves it into the appropriate element (e.g., SELECT element, FROM element).</li>
     *   <li>If {@code \{MyTable.class}} is not in such a placement and is not followed by a dot {@code '.'},
     *       it is resolved into a table element.</li>
     *   <li>If {@code \{MyTable.class}} is followed by a dot {@code '.'}, it is resolved into an alias element.</li>
     * </ul>
     *
     * @param table the {@link Class} object representing the table record.
     * @return an {@link Element} representing the table's alias.
     */
    static Element alias(@Nonnull Class<? extends Data> table) {
        return new Alias(table, CASCADE);
    }

    /**
     * Generates an alias element for the specified table class.
     *
     * <p>This method returns the alias of the table as used in the query. It is useful when you need to refer to
     * the table's alias, especially in situations where the SQL template engine cannot automatically determine
     * the appropriate element based on context.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT \{alias(MyTable.class)}.column_name FROM \{table(MyTable.class, "t")}
     * }</pre>
     *
     * <p>According to the resolution rules, if {@code \{MyTable.class}} is followed by a dot {@code '.'}, the SQL template engine
     * automatically resolves it into an alias element:
     * <pre>{@code
     * SELECT \{MyTable.class}.column_name FROM \{MyTable.class}
     * }</pre>
     *
     * <p>As per the resolution rules:
     * <ul>
     *   <li>If {@code \{MyTable.class}} is placed after keywords like SELECT, FROM, INSERT INTO, UPDATE, or DELETE,
     *       the SQL template engine resolves it into the appropriate element (e.g., SELECT element, FROM element).</li>
     *   <li>If {@code \{MyTable.class}} is not in such a placement and is not followed by a dot {@code '.'},
     *       it is resolved into a table element.</li>
     *   <li>If {@code \{MyTable.class}} is followed by a dot {@code '.'}, it is resolved into an alias element.</li>
     * </ul>
     *
     * @param table the {@link Class} object representing the table record.
     * @param scope the {@link ResolveScope} to use when resolving the alias. Use CASCADE to include local and outer
     *        aliases, LOCAL to include local aliases only, and OUTER to include outer aliases only.
     * @return an {@link Element} representing the table's alias.
     */
    static Element alias(@Nonnull Class<? extends Data> table, @Nonnull ResolveScope scope) {
        return new Alias(table, scope);
    }

    /**
     * Generates a column element for a column specified by the given {@code field} in a type safe manner.
     *
     * <p>Example usage in a string template where {@code MyTable} is referenced twice:
     * <pre>{@code
     * // Define a record with two references to MyTable
     * record Table(int id, MyTable child, MyTable parent) {}
     *
     * // In the SQL template
     * SELECT \{column(MyTable_.child.name)} FROM \{MyTable.class}
     * }</pre>
     *
     * <p>In this example, the path "child" specifies that we are referring to the {@code child} field of the
     * {@code Table} record, which is of type {@code MyTable}. This distinguishes it from the {@code parent} field, which
     * is also of type {@code MyTable}. The "name" componentName refers to the name record component of {@code MyTable}.</p>
     *
     * @param field specifies the field for which the column is to be generated.
     * @return an {@link Element} representing the table's column with the specified path.
     * @since 1.2
     */
    static Element column(@Nonnull Metamodel<?, ?> field) {
        return new Elements.Column(field, CASCADE);
    }

    /**
     * Generates a column element for a column specified by the given {@code field} in a type safe manner.
     *
     * <p>Example usage in a string template where {@code MyTable} is referenced twice:
     * <pre>{@code
     * // Define a record with two references to MyTable
     * record Table(int id, MyTable child, MyTable parent) {}
     *
     * // In the SQL template
     * SELECT \{column(MyTable_.child.name)} FROM \{MyTable.class}
     * }</pre>
     *
     * <p>In this example, the path "child" specifies that we are referring to the {@code child} field of the
     * {@code Table} record, which is of type {@code MyTable}. This distinguishes it from the {@code parent} field, which
     * is also of type {@code MyTable}. The "name" componentName refers to the name record component of {@code MyTable}.</p>
     *
     * @param field specifies the field for which the column is to be generated.
     * @param scope the {@link ResolveScope} to use when resolving the alias. Use CASCADE to include local and outer
     *              aliases, LOCAL to include local aliases only, and OUTER to include outer aliases only.
     * @return an {@link Element} representing the table's column with the specified path.
     * @since 1.2
     */
    static Element column(@Nonnull Metamodel<?, ?> field, @Nonnull ResolveScope scope) {
        return new Elements.Column(field, scope);
    }

    /**
     * Generates a parameter element for the specified value, to be used in SQL queries.
     *
     * <p>This method creates a positional parameter for use in SQL string templates. The parameter
     * can be of any object type and may be {@code null}. It is intended to be used in places where
     * you need to bind a value to a SQL query parameter.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE status = \{param(1)}
     * }</pre>
     *
     * <p>For convenience, you can also use the shorthand notation, where the SQL template engine
     * automatically detects that a parameter is required:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE status = \{1}
     * }</pre>
     *
     * <p>As per the resolution rules:
     * <ul>
     *   <li>If {@code \{1}}, or any other primitive or object, is placed after keywords like VALUES, SET or WHERE,
     *       the SQL template engine resolves it into the appropriate element (e.g., VALUES element, SET element).</li>
     *   <li>If {@code \{1}} is not in such a placement, it is resolved into a param element.</li>
     * </ul>
     *
     * @param value the value to be used as a parameter in the SQL query; may be {@code null}.
     * @return an {@link Element} representing the parameter.
     */
    static Element param(@Nullable Object value) {
        return new Param(null, value);
    }

    /**
     * Generates a named parameter element for the specified value, to be used in SQL queries.
     *
     * <p>This method creates a named parameter for use in SQL string templates. Named parameters
     * are useful when you want to explicitly specify the parameter's name in the query, improving
     * readability and maintainability, especially in complex queries.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE status = \{param("status", 1)}
     * }</pre>
     *
     * <p>In the query, the parameter will be referred to by its name {@code status}.
     *
     * @param name the name of the parameter; must not be {@code null}.
     * @param value the value to be used as a parameter in the SQL query; may be {@code null}.
     * @return an {@link Element} representing the named parameter.
     */
    static Element param(@Nonnull String name, @Nullable Object value) {
        return new Param(requireNonNull(name, "name"), value);
    }

    /**
     * Generates a parameter element for the specified value with a converter function.
     *
     * <p>This method allows you to provide a custom converter function to transform the value
     * into a database-compatible format. This is useful when the value needs to be converted
     * before being set as a parameter in the SQL query, such as formatting dates or custom objects.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE created_at = \{param(dateValue, date -> new java.sql.Date(date.getTime()))}
     * }</pre>
     *
     * @param <P> the type of the value to be converted.
     * @param value the value to be used as a parameter in the SQL query; may be {@code null}.
     * @param converter a {@link Function} that converts the value to a database-compatible format; must not be
     * {@code null}.
     * @return an {@link Element} representing the parameter with a converter applied.
     */
    static <P> Element param(@Nullable P value, @Nonnull Function<? super P, ?> converter) {
        //noinspection unchecked
        return new Param(null, value, (Function<Object, ?>) requireNonNull(converter, "converter"));
    }

    /**
     * Generates a named parameter element for the specified value with a converter function.
     *
     * <p>This method allows you to provide a custom converter function to transform the value
     * into a database-compatible format and assign a name to the parameter. Named parameters
     * enhance query readability and are particularly useful in complex queries.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE created_at = \{param("createdAt", dateValue, date -> new java.sql.Date(date.getTime()))}
     * }</pre>
     *
     * @param <P> the type of the value to be converted.
     * @param name the name of the parameter; must not be {@code null}.
     * @param value the value to be used as a parameter in the SQL query; may be {@code null}.
     * @param converter a {@link Function} that converts the value to a database-compatible format; must not be
     * {@code null}.
     * @return an {@link Element} representing the named parameter with a converter applied.
     */
    static <P> Element param(@Nonnull String name, @Nullable P value, @Nonnull Function<? super P, ?> converter) {
        //noinspection unchecked
        return new Param(name, value, (Function<Object, ?>) requireNonNull(converter, "converter"));
    }

    /**
     * Generates a parameter element for the specified {@link Date} value with a temporal type.
     *
     * <p>This method creates a positional parameter for a {@link Date} value, converting it to the appropriate
     * SQL type based on the provided {@link TemporalType}. It is useful when you need to specify how the date
     * should be interpreted in the database (as DATE, TIME, or TIMESTAMP).
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE event_date = \{param(dateValue, TemporalType.DATE)}
     * }</pre>
     *
     * @param value the {@link Date} value to be used as a parameter; must not be {@code null}.
     * @param temporalType the {@link TemporalType} specifying how the date should be handled; must not be {@code null}.
     * @return an {@link Element} representing the date parameter with the specified temporal type.
     */
    static Element param(@Nonnull Date value, @Nonnull TemporalType temporalType) {
        return param(value, v -> switch (temporalType) {
            case DATE -> new java.sql.Date(v.getTime());
            case TIME -> new java.sql.Time(v.getTime());
            case TIMESTAMP -> new java.sql.Timestamp(v.getTime());
        });
    }

    /**
     * Generates a named parameter element for the specified {@link Date} value with a temporal type.
     *
     * <p>This method creates a named parameter for a {@link Date} value, converting it to the appropriate
     * SQL type based on the provided {@link TemporalType}. Named parameters improve query readability
     * and are especially useful in complex queries.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE event_date = \{param("eventDate", dateValue, TemporalType.DATE)}
     * }</pre>
     *
     * @param name the name of the parameter; must not be {@code null}.
     * @param value the {@link Date} value to be used as a parameter; must not be {@code null}.
     * @param temporalType the {@link TemporalType} specifying how the date should be handled; must not be {@code null}.
     * @return an {@link Element} representing the named date parameter with the specified temporal type.
     */
    static Element param(@Nonnull String name, @Nonnull Date value, @Nonnull TemporalType temporalType) {
        return param(name, value, v -> switch (temporalType) {
            case DATE -> new java.sql.Date(v.getTime());
            case TIME -> new java.sql.Time(v.getTime());
            case TIMESTAMP -> new java.sql.Timestamp(v.getTime());
        });
    }

    /**
     * Generates a parameter element for the specified {@link Calendar} value with a temporal type.
     *
     * <p>This method creates a positional parameter for a {@link Calendar} value, converting it to the appropriate
     * SQL type based on the provided {@link TemporalType}. It is useful when working with calendar instances
     * that need to be interpreted in the database as specific temporal types.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE event_time = \{param(calendarValue, TemporalType.TIMESTAMP)}
     * }</pre>
     *
     * @param value the {@link Calendar} value to be used as a parameter; must not be {@code null}.
     * @param temporalType the {@link TemporalType} specifying how the calendar should be handled; must not be
     * {@code null}.
     * @return an {@link Element} representing the calendar parameter with the specified temporal type.
     */
    static Element param(@Nonnull Calendar value, @Nonnull TemporalType temporalType) {
        return param(value, v -> switch (temporalType) {
            case DATE -> new java.sql.Date(v.getTimeInMillis());
            case TIME -> new java.sql.Time(v.getTimeInMillis());
            case TIMESTAMP -> new java.sql.Timestamp(v.getTimeInMillis());
        });
    }

    /**
     * Generates a named parameter element for the specified {@link Calendar} value with a temporal type.
     *
     * <p>This method creates a named parameter for a {@link Calendar} value, converting it to the appropriate
     * SQL type based on the provided {@link TemporalType}. Named parameters enhance query readability
     * and are particularly useful in complex queries.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT *
     * FROM \{MyTable.class}
     * WHERE event_time = \{param("eventTime", calendarValue, TemporalType.TIMESTAMP)}
     * }</pre>
     *
     * @param name the name of the parameter; must not be {@code null}.
     * @param value the {@link Calendar} value to be used as a parameter; must not be {@code null}.
     * @param temporalType the {@link TemporalType} specifying how the calendar should be handled; must not be
     * {@code null}.
     * @return an {@link Element} representing the named calendar parameter with the specified temporal type.
     */
    static Element param(@Nonnull String name, @Nonnull Calendar value, @Nonnull TemporalType temporalType) {
        return param(name, value, v -> switch (temporalType) {
            case DATE -> new java.sql.Date(v.getTimeInMillis());
            case TIME -> new java.sql.Time(v.getTimeInMillis());
            case TIMESTAMP -> new java.sql.Timestamp(v.getTimeInMillis());
        });
    }

    /**
     * Creates a new var element that can be used to specify individual bind variables in the query.
     *
     * @param bindVars the bind variables instance used for parameter binding.
     * @param extractor the function used to extract the value from the record for the bind variable.
     * @return a new {@link Element} representing the bind variable.
     */
    static Element bindVar(@Nonnull BindVars bindVars, @Nonnull Function<Data, ?> extractor) {
        return new BindVar(bindVars, extractor);
    }

    /**
     * Creates a new subquery element using a query builder.
     *
     * @param builder   the query builder used to construct the subquery; must not be null.
     * @param correlate a flag indicating whether the subquery should correlate with the outer query.
     *                  If {@code true}, the subquery can reference elements from the outer query.
     *                  If {@code false}, the subquery is independent and does not access the outer query.
     * @return a new {@code Subquery} element based on the provided query builder and correlation flag.
     */
    static Element subquery(@Nonnull QueryBuilder<?, ?, ?> builder, boolean correlate) {
        return new Subquery(((Subqueryable) builder).getSubquery(), correlate);
    }

    /**
     * Creates a new subquery element using a string template.
     *
     * @param template  the string template representing the subquery; must not be null.
     * @param correlate a flag indicating whether the subquery should correlate with the outer query.
     *                  If {@code true}, the subquery can reference elements from the outer query.
     *                  If {@code false}, the subquery is independent and does not access the outer query.
     * @return a new {@code Subquery} element based on the provided template and correlation flag.
     */
    static Element subquery(@Nonnull TemplateString template, boolean correlate) {
        return new Subquery(template, correlate);
    }

    /**
     * Injects raw SQL into the query without any processing or sanitization.
     *
     * <p>This method allows you to insert arbitrary SQL code directly into your query. It bypasses any
     * automatic parameter binding or SQL generation provided by the SQL template engine. As a result, it can be
     * potentially unsafe and may expose your application to SQL injection attacks if not used carefully.
     *
     * <p><strong>Warning:</strong> Use this method only when you are certain that the SQL string being injected
     * is safe and originates from a trusted source. Avoid using user-supplied input with this method.
     *
     * <p>Example usage in a string template:
     * <pre>{@code
     * SELECT * FROM \{User.class} WHERE \{unsafe("city = 'Sunnyvale'")}
     * }</pre>
     *
     * <p>In this example, the SQL fragment <code>"city = 'Sunnyvale'"</code> is injected directly into the query.
     *
     * @param sql the raw SQL string to inject into the query.
     * @return an {@link Element} that represents the raw SQL code to be inserted into the query.
     */
    static Element unsafe(@Nonnull String sql) {
        return new Unsafe(sql);
    }
}
