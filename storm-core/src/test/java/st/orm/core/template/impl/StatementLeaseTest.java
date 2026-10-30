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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;
import st.orm.core.template.TemplateString;

/**
 * Every statement Storm executes on a data source's connection returns the connection when Storm closes it: the
 * statement is the driver's own, so the release is tied to Storm's close rather than to the statement's. Each
 * terminal is run against a data source that counts the connections it hands out and the ones closed, on success,
 * on failure partway through, when a stream is left before its end, and when a statement is closed twice.
 */
class StatementLeaseTest {

    @DbTable("lease_item")
    public record LeaseItem(@PK Integer id, String name) implements Entity<Integer> {}

    private final AtomicInteger handedOut = new AtomicInteger();
    private final AtomicInteger closed = new AtomicInteger();
    private DataSource dataSource;
    private ORMTemplate orm;

    @BeforeEach
    void setUp() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:lease_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE lease_item (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO lease_item (name) VALUES ('first'), ('second'), ('third')");
        }
        dataSource = counting(h2);
        orm = ORMTemplate.of(dataSource);
    }

    /** Wraps the data source so every connection it hands out, and every close of one, is counted. */
    private DataSource counting(DataSource target) {
        return (DataSource) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object result = invoke(target, method, args);
                    if (result instanceof Connection connection) {
                        handedOut.incrementAndGet();
                        return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Connection.class},
                                (connectionProxy, connectionMethod, connectionArgs) -> {
                                    if (connectionMethod.getName().equals("close")) {
                                        closed.incrementAndGet();
                                    }
                                    return invoke(connection, connectionMethod, connectionArgs);
                                });
                    }
                    return result;
                });
    }

    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getTargetException();
        }
    }

    /** Runs the work and asserts that it took at least one connection and returned every connection it took. */
    private void assertReturned(String label, Runnable work) {
        int before = handedOut.get();
        try {
            work.run();
        } catch (RuntimeException ignore) {
            // A failing statement returns its connection as a succeeding one does.
        }
        assertTrue(handedOut.get() > before, label + ": took no connection");
        assertEquals(handedOut.get(), closed.get(), label + ": connections handed out and closed");
    }

    @Test
    void readsReturnTheirConnection() {
        var items = orm.entity(LeaseItem.class);
        assertReturned("findById", () -> items.findById(1));
        assertReturned("getById", () -> items.getById(1));
        assertReturned("findAll", items::findAll);
        assertReturned("select list", () -> items.select().getResultList());
        assertReturned("select single", () -> items.select().where(1).getSingleResult());
        assertReturned("select optional", () -> items.select().where(1).getOptionalResult());
        assertReturned("count", items::count);
        assertReturned("refs", () -> items.selectRef().getResultList());
        assertReturned("template list", () -> orm.query("SELECT id, name FROM lease_item").getResultList(LeaseItem.class));
    }

    @Test
    void streamsReturnTheirConnectionWhenClosed() {
        var items = orm.entity(LeaseItem.class);
        assertReturned("stream read to its end", () -> {
            try (var stream = items.select().getResultStream()) {
                assertEquals(3, stream.count());
            }
        });
        assertReturned("stream left before its end", () -> {
            try (var stream = items.select().getResultStream()) {
                assertTrue(stream.findFirst().isPresent());
            }
        });
    }

    @Test
    void writesReturnTheirConnection() {
        var items = orm.entity(LeaseItem.class);
        assertReturned("insert", () -> items.insert(new LeaseItem(null, "fourth")));
        assertReturned("insertAndFetchId", () -> items.insertAndFetchId(new LeaseItem(null, "fifth")));
        assertReturned("batch insert", () -> items.insert(List.of(new LeaseItem(null, "a"), new LeaseItem(null, "b"))));
        assertReturned("batch insertAndFetchIds",
                () -> items.insertAndFetchIds(List.of(new LeaseItem(null, "c"), new LeaseItem(null, "d"))));
        assertReturned("update", () -> items.update(new LeaseItem(1, "renamed")));
        assertReturned("remove", () -> items.removeById(2));
        assertReturned("delete by condition", () -> items.delete().where(3).executeUpdate());
    }

    @Test
    void failingStatementsReturnTheirConnection() {
        var items = orm.entity(LeaseItem.class);
        assertReturned("no row", () -> assertThrows(PersistenceException.class, () -> items.getById(99)));
        assertReturned("several rows", () -> assertThrows(PersistenceException.class,
                () -> items.select().getSingleResult()));
        assertReturned("unknown table", () -> assertThrows(PersistenceException.class,
                () -> orm.query("SELECT id FROM no_such_table").getResultList()));
        assertReturned("constraint", () -> assertThrows(PersistenceException.class,
                () -> orm.query("INSERT INTO lease_item (name) VALUES (NULL)").executeUpdate()));
    }

    @Test
    void aPreparedQueryReturnsItsConnectionOnceWhenClosedTwice() {
        assertReturned("prepared query", () -> {
            var prepared = orm.query("SELECT id, name FROM lease_item").prepare();
            assertEquals(3, prepared.getResultList(LeaseItem.class).size());
            prepared.close();
            prepared.close();
        });
    }

    @Test
    void aStatementHandedToTheApplicationReturnsItsConnectionOnceWhenClosed() throws SQLException {
        int before = handedOut.get();
        var statement = PreparedStatementTemplate.of(dataSource).query(TemplateString.raw("SELECT 1"));
        assertTrue(statement.executeQuery().next());
        assertEquals(before + 1, handedOut.get());
        assertFalse(handedOut.get() == closed.get(), "the connection is the application's until it closes the statement");
        statement.close();
        statement.close();
        assertEquals(handedOut.get(), closed.get());
    }
}
