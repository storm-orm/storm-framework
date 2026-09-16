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

import static java.util.List.copyOf;
import static java.util.Objects.requireNonNull;
import static java.util.Optional.ofNullable;

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.SqlTemplate.BindVariables;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * A result record that contains the generated SQL and the parameters that were used to generate it.
 *
 * @param operation     classifies the kind of SQL statement.
 * @param statement     the generated SQL with all parameters replaced by '?' or named ':name' placeholders.
 * @param parameters    the parameters that were used to generate the SQL.
 * @param bindVariables a bind variables object that can be used to add bind variables to a batch.
 * @param generatedKeys the primary key that have been auto generated as part of in insert statement.
 * @param affectedType  the type affected by INSERT, UPDATE, or DELETE operations.
 * @param dataType      the primary entity or projection type of the statement, recorded for observability.
 * @param fetchPaths    the references the statement resolves as part of its select list, as field paths.
 * @param cursorColumns where the values read alongside the mapped result are found, one entry per field.
 * @param versionAware  true if the statement is version aware, false otherwise.
 * @param unsafeWarning a warning message if the statement is deemed potentially unsafe, an empty optional otherwise.
 * @param origin        what caused the statement to execute.
 * @param shapeId       the identity of the statement's shape: the template it was generated from.
 */
record SqlImpl(
        SqlOperation operation,
        String statement,
        List<Parameter> parameters,
        Optional<BindVariables> bindVariables,
        List<String> generatedKeys,
        Optional<Class<? extends Data>> affectedType,
        Optional<Class<? extends Data>> dataType,
        List<String> fetchPaths,
        List<List<Integer>> cursorColumns,
        boolean versionAware,
        Optional<String> unsafeWarning,
        StatementOrigin origin,
        long shapeId
) implements Sql {
    public SqlImpl {
        requireNonNull(operation, "operation");
        requireNonNull(statement, "statement");
        parameters = copyOf(parameters);
        generatedKeys = copyOf(generatedKeys);
        requireNonNull(affectedType, "affectedType");
        requireNonNull(dataType, "dataType");
        fetchPaths = copyOf(fetchPaths);
        cursorColumns = copyOf(cursorColumns);
        requireNonNull(unsafeWarning, "unsafeWarning");
        requireNonNull(origin, "origin");
    }

    /**
     * Returns a new instance of the SQL statement with the given shape identity.
     *
     * @param shapeId the shape identity.
     * @return a new instance of the SQL statement with the given shape identity.
     * @since 1.13
     */
    @Override
    public Sql shapeId(long shapeId) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given origin.
     *
     * @param origin what caused the statement to execute.
     * @return a new instance of the SQL statement with the given origin.
     * @since 1.13
     */
    @Override
    public Sql origin(StatementOrigin origin) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given operation.
     *
     * @param operation the SQL operation that classifies the kind of SQL statement.
     * @return a new instance of the SQL statement with the given operation.
     */
    @Override
    public Sql operation(SqlOperation operation) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given statement.
     *
     * @param statement the new SQL statement.
     * @return a new instance of the SQL statement with the given statement.
     */
    @Override
    public Sql statement(String statement) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given parameters.
     *
     * @param parameters the new parameters.
     * @return a new instance of the SQL statement with the given parameters.
     */
    @Override
    public Sql parameters(List<Parameter> parameters) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given bind variables.
     *
     * @param bindVariables the new bind variables
     * @return a new instance of the SQL statement with the given bind variables
     */
    @Override
    public Sql bindVariables(SqlTemplate.@Nullable BindVariables bindVariables) {
        return new SqlImpl(operation, statement, parameters, ofNullable(bindVariables), generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given generated keys.
     *
     * @param generatedKeys the new generated keys
     * @return a new instance of the SQL statement with the given generated keys
     * @since 1.2
     */
    @Override
    public Sql generatedKeys(List<String> generatedKeys) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given affected type.
     *
     * @param affectedType the type affected by this INSERT, UPDATE, or DELETE operation.
     * @return a new instance of the SQL statement with the given affected type.
     * @since 1.8
     */
    @Override
    public Sql affectedType(@Nullable Class<? extends Data> affectedType) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, ofNullable(affectedType), dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the version aware flag set to the given value.
     *
     * @param versionAware the new value of the version aware flag.
     * @return a new instance of the SQL statement with the version aware flag set to the given value
     * @since 1.2
     */
    @Override
    public Sql versionAware(boolean versionAware) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, unsafeWarning, origin, shapeId);
    }

    /**
     * Returns a new instance of the SQL statement with the given unsafe warning message.
     *
     * @param unsafeWarning the new unsafe warning message
     * @return a new instance of the SQL statement with the given unsafe warning message
     * @since 1.2
     */
    @Override
    public Sql unsafeWarning(@Nullable String unsafeWarning) {
        return new SqlImpl(operation, statement, parameters, bindVariables, generatedKeys, affectedType, dataType, fetchPaths, cursorColumns, versionAware, ofNullable(unsafeWarning), origin, shapeId);
    }
}
