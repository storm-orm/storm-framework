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
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.core.template.impl.BindVarsHandle;
import st.orm.core.template.impl.SqlTemplateImpl;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;
import st.orm.mapping.TemplateDecorator;

/**
 * A template processor for generating SQL queries. While this interface can be used directly, developers are encouraged
 * to leverage the higher-level abstractions provided by the framework, as they are likely more useful and convenient
 * for most use cases.
 */
public interface SqlTemplate extends TemplateDecorator {

    /**
     * Represents a parameter that can be used in an SQL query.
     *
     * <p>This abstraction encapsulates values that will be passed directly to the underlying database system,
     * regardless of the data access framework being used.</p>
     */
    sealed interface Parameter {

        /**
         * Retrieves the database value of this parameter.
         *
         * <p>The returned value will be passed directly to the underlying database system (e.g., via a prepared
         * statement JPA query, or any other database interaction mechanism) without additional transformation.</p>
         *
         * @return the raw database value of this parameter.
         */
        Object dbValue();
    }

    /**
     * Represents a named parameter that can be used in an SQL query. The same parameter name can be used multiple times
     * within a single query.
     *
     * <p>Note: At the SQL template level, the framework enforces that multiple occurrences of the same parameter name
     * must have the same value. This enforcement ensures consistency and prevents logical errors in the query.</p>
     *
     * @param name the name of the parameter.
     * @param dbValue the database value of the parameter.
     */
    record NamedParameter(String name, @Nullable Object dbValue) implements Parameter {}

    /**
     * Represents a positional parameter that can be used in an SQL query.
     *
     * <p>Note: At the SQL template level, the framework enforces that each position in the query must be unique. The
     * same position cannot be used more than once, ensuring logical correctness and preventing conflicts in parameter
     * binding.</p>
     *
     * @param position the position of the parameter in the SQL query (starting from 1).
     * @param dbValue the database value of the parameter.
     */
    record PositionalParameter(int position, @Nullable Object dbValue) implements Parameter {}

    /**
     * Manages the binding of variables in a SQL query.
     *
     * <p>Provides a handle for configuring parameter bindings and allows registering a listener for batch processing
     * events.</p>
     */
    interface BindVariables {

        /**
         * Retrieves a handle to the bound variables. Can be used for configuring or inspecting the
         * parameters associated with the current query.
         *
         * @return a handle to the bound variables.
         */
        BindVarsHandle getHandle();

        /**
         * Registers a listener that will be notified when positional parameters are processed in batches.
         *
         * @param listener the listener to be notified of batch events.
         */
        void setBatchListener(BatchListener listener);
    }

    /**
     * A listener for record events within a SQL query context.
     */
    @FunctionalInterface
    interface RecordListener {

        /**
         * Invoked when a new record is processed.
         *
         * @param record the record that is being processed.
         * @since 1.2
         */
        void onRecord(Record record);
    }

    /**
     * A listener for batch events within a SQL query context.
     *
     * <p>When a batch of positional parameters is about to be processed, the {@link #onBatch(List)} method is called
     * with the current list of parameters.</p>
     */
    @FunctionalInterface
    interface BatchListener {

        /**
         * Invoked when a new batch of positional parameters is processed.
         *
         * <p>Implementations can inspect, transform, or validate the parameters before they are executed.</p>
         *
         * @param parameters the list of positional parameters in this batch.
         */
        void onBatch(List<PositionalParameter> parameters);
    }

    /**
     * The default SQL template that is optimized for prepared statements.
     */
    SqlTemplate PS = new SqlTemplateImpl(
            true,
            true,
            true);

    /**
     * The default SQL template that is optimized for JPA.
     */
    SqlTemplate JPA = new SqlTemplateImpl(
            false,
            false, // False, as JPA does not support collection expansion for positional parameters.
            true);  // Note that JPA does not support nested records.

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     */
    BindVars createBindVars();

    /**
     * Returns {@code true} if the template only support positional parameters, {@code false} otherwise.
     *
     * @return {@code true} if the template only support positional parameters, {@code false} otherwise.
     */
    boolean positionalOnly();

    /**
     * Returns {@code true} if collection parameters must be expanded as multiple (positional) parameters,
     * {@code false} otherwise.
     *
     * @return {@code true} if the template expands collection parameters, {@code false} otherwise.
     */
    boolean expandCollection();

    /**
     * Returns a new SQL template instance configured to inline parameters directly into the SQL string,
     * rather than using bind variables.
     *
     * @param inlineParameters if true, parameters will be inlined as literals into the SQL. If false, parameters are
     *                         passed via bind variables (default behavior).
     * @return a new SqlTemplate instance configured with the specified parameter handling.
     * @since 1.3
     */
    SqlTemplate withInlineParameters(boolean inlineParameters);

    /**
     * Indicates whether the SQL parameters should be inlined directly as literals into the SQL string,
     * or whether bind variables should be used.
     *
     * <P>Note that batch processing cannot be used when inlining parameters, as batch processing requires
     * bind variables to be used for each parameter in the batch. This setting is therefore ignored when
     * batch processing is used.</p>
     *
     * @return true if parameters are inlined as literals; false if using bind variables.
     * @since 1.3
     */
    boolean inlineParameters();

    /**
     * Returns a new SQL template with support for records enabled or disabled.
     *
     * @param supportRecords {@code true} if the template should support records, {@code false} otherwise.
     * @return a new SQL template.
     */
    SqlTemplate withSupportRecords(boolean supportRecords);

    /**
     * Returns {@code true} if the template supports tables represented as records, {@code false} otherwise.
     *
     * @return {@code true} if the template supports records, {@code false} otherwise.
     */
    boolean supportRecords();

    /**
     * Returns a new SQL template with the specified table name resolver.
     *
     * @param resolver the table name resolver.
     * @return a new SQL template.
     */
    @Override
    SqlTemplate withTableNameResolver(TableNameResolver resolver);

    /**
     * Returns the table name resolver used by this template.
     *
     * @return the table name resolver used by this template.
     */
    TableNameResolver tableNameResolver();

    /**
     * Returns a new SQL template with the specified schema resolver.
     *
     * @param resolver the schema resolver.
     * @return a new SQL template.
     * @since 1.14
     */
    @Override
    SqlTemplate withSchemaResolver(SchemaResolver resolver);

    /**
     * Returns the schema resolver used by this template.
     *
     * @return the schema resolver used by this template.
     * @since 1.14
     */
    SchemaResolver schemaResolver();

    /**
     * Returns a new SQL template with the specified table alias resolver.
     *
     * @param resolver the table alias resolver.
     * @return a new SQL template.
     */
    SqlTemplate withTableAliasResolver(TableAliasResolver resolver);

    /**
     * Returns the table alias resolver used by this template.
     *
     * @return the table alias resolver used by this template.
     */
    TableAliasResolver tableAliasResolver();

    /**
     * Returns a new SQL template with the specified column name resolver.
     *
     * @param resolver the column name resolver.
     * @return a new SQL template.
     */
    @Override
    SqlTemplate withColumnNameResolver(ColumnNameResolver resolver);

    /**
     * Returns the column name resolver used by this template.
     *
     * @return the column name resolver used by this template.
     */
    ColumnNameResolver columnNameResolver();

    /**
     * Returns a new SQL template with the specified foreign key resolver.
     *
     * @param resolver the foreign key resolver.
     * @return a new SQL template.
     */
    @Override
    SqlTemplate withForeignKeyResolver(ForeignKeyResolver resolver);

    /**
     * Returns the foreign key resolver used by this template.
     *
     * @return the foreign key resolver used by this template.
     */
    ForeignKeyResolver foreignKeyResolver();

    /**
     * Returns a new SQL template with the specified SQL dialect.
     *
     * @param dialect the SQL dialect to use.
     * @return a new SQL template.
     */
    SqlTemplate withDialect(SqlDialect dialect);

    /**
     * Returns the SQL dialect used by this template.
     *
     * <p>When no dialect has been set via {@link #withDialect(SqlDialect)}, the dialect is resolved from the
     * classpath on first use. That resolution fails with a descriptive exception when multiple dialect providers
     * are eligible without a defined order.</p>
     *
     * @return the SQL dialect used by this template.
     * @since 1.2
     */
    SqlDialect dialect();

    /**
     * Returns a new SQL template with the specified Storm configuration. A dialect set via
     * {@link #withDialect(SqlDialect)} is retained; a classpath-resolved dialect is re-resolved under the new
     * configuration, as dialects capture their configuration at construction.
     *
     * @param config the Storm configuration to apply.
     * @return a new SQL template.
     * @since 1.9
     */
    SqlTemplate withConfig(StormConfig config);

    /**
     * Processes the specified {@code template} and returns the resulting SQL and parameters.
     *
     * @param template the string template to process.
     * @return the resulting SQL and parameters.
     * @throws SqlTemplateException if an error occurs while processing the input.
     */
    Sql process(TemplateString template) throws SqlTemplateException;
}
