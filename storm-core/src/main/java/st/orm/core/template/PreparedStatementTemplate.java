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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.function.Predicate;
import javax.sql.DataSource;
import st.orm.BindVars;
import st.orm.core.spi.Provider;
import st.orm.core.template.impl.PreparedStatementTemplateImpl;
import st.orm.mapping.ColumnNameResolver;
import st.orm.mapping.ForeignKeyResolver;
import st.orm.mapping.SchemaResolver;
import st.orm.mapping.TableNameResolver;
import st.orm.mapping.TemplateDecorator;

/**
 * A template backed by JDBC.
 */
public interface PreparedStatementTemplate extends TemplateDecorator {

    /**
     * Creates a new prepared statement template for the given data source.
     *
     * @param dataSource the data source.
     * @return the prepared statement template.
     */
    static PreparedStatementTemplate of(DataSource dataSource) {
        return new PreparedStatementTemplateImpl(dataSource);
    }

    /**
     * Creates a new prepared statement template for the given connection.
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the connection.
     * @return the prepared statement template.
     */
    static PreparedStatementTemplate of(Connection connection) {
        return new PreparedStatementTemplateImpl(connection);
    }

    /**
     * Creates a new ORM template for the given data source.
     *
     * @param dataSource the data source.
     * @return the ORM template.
     */
    static ORMTemplate ORM(DataSource dataSource) {
        return new PreparedStatementTemplateImpl(dataSource).toORM();
    }

    /**
     * Creates a new ORM template for the given connection.
     *
     * <p><strong>Note:</strong> The caller is responsible for closing the connection after usage.</p>
     *
     * @param connection the connection.
     * @return the ORM template.
     */
    static ORMTemplate ORM(Connection connection) {
        return new PreparedStatementTemplateImpl(connection).toORM();
    }

    /**
     * Returns a new prepared statement template with the specified table name resolver.
     *
     * @param tableNameResolver the table name resolver.
     * @return a new prepared statement template.
     */
    @Override
    PreparedStatementTemplate withTableNameResolver(TableNameResolver tableNameResolver);

    /**
     * Returns a new prepared statement template with the specified schema resolver.
     *
     * @param schemaResolver the schema resolver.
     * @return a new prepared statement template.
     * @since 1.14
     */
    @Override
    PreparedStatementTemplate withSchemaResolver(SchemaResolver schemaResolver);

    /**
     * Returns a new prepared statement template with the specified column name resolver.
     *
     * @param columnNameResolver the column name resolver.
     * @return a new prepared statement template.
     */
    @Override
    PreparedStatementTemplate withColumnNameResolver(ColumnNameResolver columnNameResolver);

    /**
     * Returns a new prepared statement template with the specified foreign key resolver.
     *
     * @param foreignKeyResolver the foreign key resolver.
     * @return a new prepared statement template.
     */
    @Override
    PreparedStatementTemplate withForeignKeyResolver(ForeignKeyResolver foreignKeyResolver);

    /**
     * Returns a new prepared statement template with the specified table alias resolver.
     *
     * @param tableAliasResolver the table alias resolver.
     * @return a new prepared statement template.
     */
    PreparedStatementTemplate withTableAliasResolver(TableAliasResolver tableAliasResolver);

    /**
     * Returns a new prepared statement template with the specified provider filter.
     *
     * @param providerFilter the provider filter.
     * @return a new prepared statement template.
     */
    PreparedStatementTemplate withProviderFilter(Predicate<Provider> providerFilter);

    /**
     * Returns an ORM template that is backed by this prepared statement template.
     *
     * @return the ORM template.
     */
    ORMTemplate toORM();

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     */
    BindVars createBindVars();

    /**
     * Creates a query for the specified query {@code template}.
     *
     * @param template the query template.
     * @return the query.
     */
    PreparedStatement query(TemplateString template) throws SQLException;
}
