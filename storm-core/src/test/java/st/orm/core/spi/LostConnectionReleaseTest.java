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
package st.orm.core.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static st.orm.TransactionPropagation.REQUIRED;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.PersistenceException;
import st.orm.TransactionOptions;
import st.orm.TransientPersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;

/**
 * A transaction whose connection is lost still hands the connection back: a pool learns of the loss only when the
 * connection is closed, and restoring the connection's settings first fails on a connection that is gone.
 */
class LostConnectionReleaseTest {

    /**
     * Hands out a proxy over each connection that counts its {@code close()} calls, and closes the physical
     * connection on {@link #loseConnection()}, as a database or a network that dropped it would.
     */
    static final class LosingDataSource extends DelegatingDataSource {
        final AtomicInteger closes = new AtomicInteger();
        @Nullable Connection physical;

        LosingDataSource(DataSource target) {
            super(target);
        }

        void loseConnection() throws SQLException {
            assertNotNull(physical, "a connection was handed out");
            physical.close();
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            physical = connection;
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("close")) {
                            closes.incrementAndGet();
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }

    @Test
    void aLostConnectionIsClosedWhenTheTransactionRollsBack() throws SQLException {
        var dataSource = new LosingDataSource(new DriverManagerDataSource(
                "jdbc:h2:mem:lost_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
        var orm = ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        orm.query(TemplateString.of("CREATE TABLE lost_row (id INTEGER PRIMARY KEY)")).executeUpdate();
        int closedBefore = dataSource.closes.get();
        var thrown = assertThrows(PersistenceException.class, () -> TransactionRunner.<Object, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, null, false), transaction -> {
                    orm.query(TemplateString.of("INSERT INTO lost_row (id) VALUES (1)")).executeUpdate();
                    try {
                        dataSource.loseConnection();
                    } catch (SQLException e) {
                        throw new IllegalStateException(e);
                    }
                    return orm.query(TemplateString.of("INSERT INTO lost_row (id) VALUES (2)")).executeUpdate();
                }));
        assertFalse(thrown instanceof TransientPersistenceException, "a lost connection is not transient");
        assertEquals(1, dataSource.closes.get() - closedBefore, "the transaction's connection is closed once");
    }
}
