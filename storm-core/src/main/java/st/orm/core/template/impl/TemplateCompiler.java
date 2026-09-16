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

import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Element;
import st.orm.Metamodel;
import st.orm.ResolveScope;
import st.orm.SqlTemplateException;
import st.orm.core.template.Model;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.TemplateString;

/**
 * Compile-time API used by element processors to convert templates and elements into SQL fragments.
 *
 * <p>A {@code TemplateCompiler} is used only during compilation. It provides access to dialect helpers, models, alias
 * resolution, and facilities to map runtime values to SQL placeholders. It also records compile-time expectations such
 * as positional parameter counts and bind vars arity.</p>
 *
 * <p>Implementations may be stateful and are typically not intended for direct use by application code. Compile-time
 * state must be finalized before binding begins.</p>
 *
 * @since 1.8
 */
interface TemplateCompiler {

    /**
     * Returns the SQL template.
     *
     * @return the SQL template.
     */
    SqlTemplate template();

    /**
     * Returns the dialect used for SQL rendering.
     *
     * @return the SQL dialect.
     */
    SqlDialect dialect();

    /**
     * Returns dialect-specific helpers used for template rendering.
     *
     * @return the dialect template helper.
     */
    SqlDialectTemplate dialectTemplate();

    /**
     * Resolves the model for the runtime type of the given record.
     *
     * <p>This is a convenience overload for cases where only an instance is available. The model is resolved based on
     * {@code record.getClass()}.</p>
     *
     * @param record the record instance whose runtime type is used to resolve a model.
     * @param <T>    the record type.
     * @param <ID>   the identifier type.
     * @return the resolved model for the record's runtime type.
     * @throws SqlTemplateException if the model cannot be resolved.
     */
    default <T extends Data, ID> Model<T, ID> getModel(T record) throws SqlTemplateException {
        //noinspection unchecked
        return getModel((Class<T>) record.getClass());
    }

    /**
     * Resolves a model for the given data type.
     *
     * @param type the data type.
     * @param <T>  the data type.
     * @param <ID> the identifier type.
     * @return the model for the given type.
     * @throws UncheckedSqlTemplateException if model resolution fails.
     */
    <T extends Data, ID> Model<T, ID> getModel(Class<T> type);

    /**
     * Returns the query model, throwing if none is available.
     *
     * @return the query model.
     * @throws UncheckedSqlTemplateException if no primary table was specified.
     */
    QueryModel getQueryModel();

    /**
     * Returns the query model if one is available.
     *
     * @return the query model, if present.
     */
    Optional<QueryModel> findQueryModel();

    /**
     * Compiles a nested template string and returns its SQL fragment.
     *
     * <p>This method performs preprocessing and preparation to ensure bind hints are recorded on the shared hint tape
     * and that nested compilation correlates correctly with the outer query when requested.</p>
     *
     * @param template  the nested template to compile.
     * @param correlate whether the nested template should correlate with the outer query.
     * @return the compiled SQL fragment.
     * @throws UncheckedSqlTemplateException if compilation fails.
     */
    String compile(TemplateString template, boolean correlate);

    /**
     * Compiles an element into its SQL representation.
     *
     * @param element the element to compile.
     * @return the compiled SQL fragment.
     * @throws UncheckedSqlTemplateException if compilation fails.
     */
    String compile(Element element);

    /**
     * Checks whether a given table alias is referenced in the current compilation context.
     *
     * @param table the table type.
     * @param alias the table alias.
     * @return {@code true} if the alias is referenced.
     */
    boolean isReferenced(Class<? extends Data> table, String alias);

    /**
     * Resolves the SQL alias for the given metamodel and resolve scope.
     *
     * @param metamodel the metamodel to resolve an alias for.
     * @param scope     resolve scope controlling join visibility.
     * @return the resolved alias.
     * @throws UncheckedSqlTemplateException if alias resolution fails.
     */
    String getAlias(Metamodel<?, ?> metamodel, ResolveScope scope);

    /**
     * Allocates or reuses an alias for the given table.
     *
     * @param table the table type.
     * @param alias preferred alias.
     * @return the actual alias used.
     * @throws UncheckedSqlTemplateException if alias allocation fails.
     */
    String useAlias(Class<? extends Data> table, String alias);

    /**
     * Returns whether the current compilation is version-aware.
     *
     * @return {@code true} if version-aware.
     */
    boolean isVersionAware();

    /**
     * Marks the compiled template as version-aware.
     */
    void setVersionAware();

    /**
     * Configures generated keys for the compiled template.
     *
     * @param generatedKeys the generated key column names.
     */
    void setGeneratedKeys(List<String> generatedKeys);

    /**
     * Maps a runtime value to a SQL parameter placeholder.
     *
     * <p>If named parameters are supported, a fresh name is generated. If positional-only mode is active, a question
     * mark placeholder is produced and compile-time positional parameter count is incremented. When collection
     * expansion is enabled, arrays and iterables may expand into multiple placeholders or literals.</p>
     *
     * @param value the value to map.
     * @return the SQL parameter placeholder or an inlined literal.
     */
    String mapParameter(@Nullable Object value);

    /**
     * Maps a runtime value to a named SQL parameter placeholder.
     *
     * @param name  the parameter name.
     * @param value the runtime value.
     * @return the named SQL parameter placeholder.
     * @throws UncheckedSqlTemplateException if the template is configured for positional-only parameters.
     */
    String mapParameter(String name, @Nullable Object value);

    /**
     * Records a {@link BindVars} segment length.
     *
     * @param bindVarsCount the number of positional placeholders to be produced by the bind vars segment.
     */
    void mapBindVars(int bindVarsCount);

    /**
     * Sets the type affected by an INSERT, UPDATE, or DELETE operation.
     *
     * <p>This information is used to invalidate caches after raw INSERT/UPDATE/DELETE queries are executed.</p>
     *
     * @param type the type affected by the operation.
     */
    void setAffectedType(Class<? extends Data> type);

    /**
     * Records the primary entity or projection type of the statement for observability purposes.
     *
     * <p>Unlike {@link #setAffectedType(Class)}, this is purely informational metadata: the first recorded type wins
     * and subsequent calls are ignored, so the outermost select takes precedence over the sources it queries.</p>
     *
     * @param type the entity or projection type the statement operates on.
     * @since 1.13
     */
    void setDataType(Class<? extends Data> type);

    /**
     * Records the references the statement resolves as part of its select list.
     *
     * <p>The paths determine the shape of the select list, so the row mapper has to read them back to consume the
     * referenced table's columns where a reference would otherwise consume its foreign key column alone. As with
     * {@link #setDataType(Class)}, the first recorded value wins, so the outermost select takes precedence.</p>
     *
     * @param fetchPaths the field paths of the resolved references, relative to the selected type.
     * @since 1.13
     */
    void setFetchPaths(List<String> fetchPaths);

    /**
     * Returns the fetch plan of the compilation in progress: the references the statement resolves as part of its
     * select list, collected from the template's fetch elements before its elements compile.
     *
     * @return the fetch plan, or the empty plan when the statement resolves no reference.
     */
    FetchPlan getFetchPlan();

    /**
     * Records the select list the query model generated, one rendered column per position, so an element that
     * follows the select clause can find a column in it. The first recorded list wins, as with
     * {@link #setFetchPaths(List)}.
     *
     * @param columns the rendered select-list columns, in select order.
     * @since 1.14
     */
    void setSelectColumns(List<String> columns);

    /**
     * Returns the select list the query model generated, empty when the caller wrote the select list.
     *
     * @return the rendered select-list columns, in select order.
     * @since 1.14
     */
    Optional<List<String>> getSelectColumns();

    /**
     * Records where the values the statement reads alongside its mapped result are found; see
     * {@link st.orm.core.template.Sql#cursorColumns()} for the shape.
     *
     * @param positions the cursor column positions, one entry per field.
     * @since 1.14
     */
    void setCursorColumns(List<List<Integer>> positions);
}
