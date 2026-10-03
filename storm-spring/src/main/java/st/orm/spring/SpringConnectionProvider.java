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
package st.orm.spring;

import java.sql.Connection;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.datasource.DataSourceUtils;
import st.orm.PersistenceException;
import st.orm.core.spi.ConnectionProvider;
import st.orm.core.spi.JdbcConnectionProviderImpl.ConcurrencyDetector;
import st.orm.core.spi.TransactionContext;
import st.orm.spring.impl.SpringTransactionContext;

/**
 * Connection provider that binds connections to Spring's transaction management.
 *
 * <p>Connections are acquired through {@link DataSourceUtils}, so statements executed by the template participate in
 * Spring-managed ({@code @Transactional}) transactions via thread-bound connections, and degrade gracefully to plain
 * connections when no transaction is active. A Storm transaction block starts its Spring transaction on the first
 * statement that touches a data source.</p>
 *
 * <p>Within a transaction the connection serves one caller at a time: a statement issued while another caller still
 * holds it fails fast, as on the plain JDBC path.</p>
 *
 * <p>Templates created through {@link SpringOrmTemplate#of} and by the Spring Boot starters carry this provider.
 * Composing a template by hand goes through the engine's builder, which owns the transaction-bridging strategies:
 * <pre>{@code
 * var engine = st.orm.core.template.ORMTemplate.builder(dataSource)
 *         .connectionProvider(new SpringConnectionProvider())
 *         .transactionTemplateProvider(new SpringTransactionTemplateProvider());
 * }</pre>
 *
 * @since 1.13
 */
public class SpringConnectionProvider implements ConnectionProvider {

    @Override
    public Connection getConnection(DataSource dataSource, @Nullable TransactionContext context) {
        if (context instanceof SpringTransactionContext springContext) {
            // Storm-initiated transaction: lazily start the Spring transaction for the pending frames.
            springContext.useDataSource(dataSource);
        }
        Connection connection;
        try {
            connection = DataSourceUtils.getConnection(dataSource);
        } catch (CannotGetJdbcConnectionException e) {
            throw new PersistenceException("Failed to get connection from DataSource.", e);
        }
        if (context != null) {
            try {
                ConcurrencyDetector.beforeAccess(connection, context);
            } catch (PersistenceException e) {
                DataSourceUtils.releaseConnection(connection, dataSource);
                throw e;
            }
        }
        return connection;
    }

    @Override
    public void releaseConnection(Connection connection, DataSource dataSource, @Nullable TransactionContext context) {
        if (context != null) {
            ConcurrencyDetector.afterAccess(connection, context);
        }
        DataSourceUtils.releaseConnection(connection, dataSource);
    }
}
