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

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.core.template.SqlTemplate.BindVariables;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.impl.Elements.Insert;
import st.orm.core.template.impl.Elements.Set;
import st.orm.core.template.impl.Elements.Table;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Represents the generated SQL statement with parameters.
 */
public interface Sql {

    /**
     * Classifies the kind of SQL statement.
     */
    SqlOperation operation();

    /**
     * Returns a new instance of the SQL statement with the given operation.
     *
     * @param operation the SQL operation that classifies the kind of SQL statement.
     * @return a new instance of the SQL statement with the given operation.
     */
    Sql operation(SqlOperation operation);

    /**
     * The generated SQL with all parameters replaced by '?' or named ':name' placeholders.
     */
    String statement();

    /**
     * Returns a new instance of the SQL statement with the given statement.
     *
     * @param statement the new SQL statement.
     * @return a new instance of the SQL statement with the given statement.
     */
    Sql statement(String statement);

    /**
     * The parameters that were used to generate the SQL.
     */
    List<Parameter> parameters();

    /**
     * Returns a new instance of the SQL statement with the given parameters.
     *
     * @param parameters the new parameters.
     * @return a new instance of the SQL statement with the given parameters.
     */
    Sql parameters(List<Parameter> parameters);

    /**
     * A bind variables object that can be used to add bind variables to a batch, if available.
     */
    Optional<BindVariables> bindVariables();

    /**
     * Returns a new instance of the SQL statement with the given bind variables.
     *
     * @param bindVariables the new bind variables
     * @return a new instance of the SQL statement with the given bind variables
     */
    Sql bindVariables(@Nullable BindVariables bindVariables);

    /**
     * The primary key that have been auto generated as part of in insert statement.
     *
     * <p><strong>Note:</strong> The generated keys are only set when the SQL is generated using an {@link Insert}
     * element, either directly or indirectly using {@link Table}.</p>
     */
    List<String> generatedKeys();

    /**
     * Returns a new instance of the SQL statement with the given generated keys.
     *
     * @param generatedKeys the new generated keys
     * @return a new instance of the SQL statement with the given generated keys
     * @since 1.2
     */
    Sql generatedKeys(List<String> generatedKeys);

    /**
     * Returns {@code true} if the statement is version aware, {@code false} otherwise.
     *
     * <p><strong>Note:</strong> The version-aware flag is only set when the SQL is generated using a {@link Set}
     * element.</p>
     */
    boolean versionAware();

    /**
     * Returns a new instance of the SQL statement with the version-aware flag set to the given value.
     *
     * @param versionAware the new value of the version-aware flag.
     * @return a new instance of the SQL statement with the version-aware flag set to the given value
     * @since 1.2
     */
    Sql versionAware(boolean versionAware);

    /**
     * Returns the type affected by this INSERT, UPDATE, or DELETE operation.
     *
     * <p>This information is used to invalidate caches after raw INSERT/UPDATE/DELETE queries are executed.
     * For SELECT and INSERT operations, this returns empty.</p>
     *
     * @return the type affected by this operation, or empty for SELECT.
     * @since 1.8
     */
    Optional<Class<? extends Data>> affectedType();

    /**
     * Returns a new instance of the SQL statement with the given affected type.
     *
     * @param affectedType the type affected by this INSERT, UPDATE, or DELETE operation.
     * @return a new instance of the SQL statement with the given affected type.
     * @since 1.8
     */
    Sql affectedType(@Nullable Class<? extends Data> affectedType);

    /**
     * Returns the primary entity or projection type of the statement, if known: the selected type for SELECT
     * statements, or the affected type for INSERT, UPDATE, and DELETE statements.
     *
     * <p>This is purely informational metadata, exposed to query observers as the statement's data type. Unlike
     * {@link #affectedType()}, it carries no cache invalidation semantics.</p>
     *
     * @return the primary entity or projection type of the statement, or empty if unknown.
     * @since 1.13
     */
    default Optional<Class<? extends Data>> dataType() {
        return affectedType();
    }

    /**
     * Returns the references this statement resolves as part of its select list, as field paths relative to the
     * selected type.
     *
     * <p>A reference is selected as its foreign key column by default and is resolved on demand through
     * {@link st.orm.Ref#fetch()}. A path listed here is selected as the referenced table's columns instead, so the
     * statement's select list is wider than the selected type's declared shape and the row mapper has to consume that
     * wider shape. The paths are prefix-closed and sorted.</p>
     *
     * @return the resolved reference paths, empty when every reference is selected as its foreign key column.
     * @since 1.13
     */
    List<String> fetchPaths();

    /**
     * Returns where the values this statement reads alongside its mapped result are found: one entry per field, in
     * the order the fields were requested, listing the columns the field's value spans.
     *
     * <p>A field the select list already carries is not selected again. A positive number is the one-based
     * position of such a column; a negative number {@code -n} is the {@code n}-th column appended after the select
     * list, so the leading columns of a row map to the result type exactly as they would without the cursor. An
     * inline record spans several columns; every other field spans one. A scroll window reads its sort and key
     * values this way.</p>
     *
     * @return the cursor column positions, empty for a statement that reads nothing alongside its result.
     * @since 1.14
     */
    List<List<Integer>> cursorColumns();

    /**
     * Returns what caused this statement to execute.
     *
     * <p>A statement resolving a reference is shaped exactly like a primary key lookup the application could have
     * written itself; the origin is what tells the two apart.</p>
     *
     * @return the statement origin; {@link StatementOrigin#DIRECT} unless the statement resolves a reference.
     * @since 1.13
     */
    StatementOrigin origin();

    /**
     * Returns a new instance of the SQL statement with the given origin.
     *
     * <p>The statement itself is unchanged: a statement resolving a reference is the same statement whichever way
     * it was reached, so only what caused it differs.</p>
     *
     * @param origin what caused the statement to execute.
     * @return a new instance of the SQL statement with the given origin.
     * @since 1.13
     */
    Sql origin(StatementOrigin origin);

    /**
     * Returns the identity of the statement's shape: the template it was generated from, before values were
     * bound.
     *
     * <p>Statements generated from one template share a shape whatever their parameters look like, including a
     * collection parameter that expands to a different number of placeholders per execution. The shape is
     * therefore what groups executions of the same statement, where the text alone would split them. Derived from
     * the template's fragments at generation, so no statement text is ever parsed.</p>
     *
     * @return the shape identity; {@code 0} when unknown.
     * @since 1.13
     */
    long shapeId();

    /**
     * Returns a new instance of the SQL statement with the given shape identity.
     *
     * <p>Set where the statement is generated, since the shape is a property of the template rather than of the
     * text the statement ends up carrying.</p>
     *
     * @param shapeId the shape identity.
     * @return a new instance of the SQL statement with the given shape identity.
     * @since 1.13
     */
    Sql shapeId(long shapeId);

    /**
     * Returns a warning message if the statement is deemed potentially unsafe, an empty optional otherwise.
     *
     * @since 1.2
     */
    Optional<String> unsafeWarning();

    /**
     * Returns a new instance of the SQL statement with the given unsafe warning message.
     *
     * @param unsafeWarning the new unsafe warning message
     * @return a new instance of the SQL statement with the given unsafe warning message
     * @since 1.2
     */
    Sql unsafeWarning(@Nullable String unsafeWarning);
}
