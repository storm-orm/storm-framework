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
package clinic;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.template.ORMTemplate;

/**
 * A repository is an instance of the class Storm writes for its interface on Java 24 and up, and a proxy before. This
 * test runs against the packaged jar, whose multi-release part holds the class writer, as an application loads it; the
 * build runs it on a Java 24 or later toolchain.
 */
class GeneratedRepositoryIT {

    private ORMTemplate orm;

    @BeforeEach
    void prepare() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:generated_repository_" + UUID.randomUUID()
                + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO customer (id, name) VALUES (1, 'Betty'), (2, 'George')");
        }
        orm = ORMTemplate.builder(h2)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
    }

    @Test
    void aRepositoryIsTheClassWrittenForItsInterfaceFromJava24() {
        var customers = orm.repository(CustomerRepository.class);
        if (Runtime.version().feature() >= 24) {
            assertEquals(CustomerRepository.class.getName() + "$$Storm", customers.getClass().getName());
        } else {
            assertTrue(Proxy.isProxyClass(customers.getClass()), customers.getClass().getName());
        }
        // A repository reads the same on every Java version.
        assertEquals(CustomerRepository.class.getName() + "@proxy", customers.toString());
    }

    @Test
    void theClassServesTheBuiltInCallsTheDefaultMethodsAndTheTemplate() {
        var customers = orm.repository(CustomerRepository.class);
        assertEquals("Betty", customers.getById(1).name());
        assertEquals("George", customers.lookUp(2).name());
        assertEquals(2, customers.count());
        assertSame(orm, customers.orm());
        assertSame(customers, orm.repository(CustomerRepository.class));
    }

    @Test
    void aMethodNoImplementationServesIsRefused() {
        var unserved = orm.repository(UnservedCustomerRepository.class);
        assertEquals("Betty", unserved.getById(1).name());
        var e = assertThrows(UnsupportedOperationException.class, () -> unserved.findSomehow(1));
        assertTrue(e.getMessage().contains("findSomehow"), e.getMessage());
    }
}
