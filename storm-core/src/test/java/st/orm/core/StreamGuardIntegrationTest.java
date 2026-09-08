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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.PersistenceException;
import st.orm.core.model.Pet;
import st.orm.core.model.Vet;
import st.orm.core.spi.JdbcConnectionProviderImpl;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.template.ORMTemplate;

/**
 * A result stream holds its connection consume-only until it closes. A connection-backed template shares one
 * connection between all of its statements, the way a transaction does, so it stands in for the transaction here.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class StreamGuardIntegrationTest {

    @Autowired
    private DataSource dataSource;

    /**
     * A connection of its own, outside the suite's Spring-managed transaction, so writes made on it are rolled back
     * here rather than committed into the shared database.
     */
    private Connection sharedConnection() throws SQLException {
        var connection = dataSource.getConnection();
        connection.setAutoCommit(false);
        return new RollbackOnClose(connection).proxy();
    }

    private record RollbackOnClose(Connection connection) {
        Connection proxy() {
            return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (ignore, method, args) -> {
                        if (method.getName().equals("close")) {
                            try {
                                connection.rollback();
                            } finally {
                                connection.close();
                            }
                            return null;
                        }
                        try {
                            return method.invoke(connection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }

    @Test
    public void statementWhileStreamIsOpenOnSharedConnectionIsRefused() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            try (var vets = orm.selectFrom(Vet.class).getResultStream()) {
                var exception = assertThrows(PersistenceException.class,
                        () -> vets.forEach(vet -> orm.selectFrom(Vet.class).getResultCount()));
                assertTrue(exception.getMessage().contains("result stream is still open"), exception.getMessage());
                assertTrue(exception.getMessage().contains("Open stream: SELECT Vet"), exception.getMessage());
                assertTrue(exception.getMessage().contains("windows(size)"), exception.getMessage());
            }
            // The stream is closed, so the connection is free again.
            assertEquals(6, orm.selectFrom(Vet.class).getResultCount());
        }
    }

    @Test
    public void writeWhileStreamIsOpenOnSharedConnectionIsRefused() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            var vets = orm.entity(Vet.class);
            try (var stream = vets.select().getResultStream()) {
                var exception = assertThrows(PersistenceException.class, () -> stream.forEach(vet ->
                        vets.update(vet.toBuilder().lastName(vet.lastName() + " DVM").build())));
                assertTrue(exception.getMessage().contains("Statement: UPDATE Vet"), exception.getMessage());
            }
        }
    }

    @Test
    public void writeFedByStreamOnSharedConnectionIsRefused() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            var vets = orm.entity(Vet.class);
            // The batched write executes its first batch while the stream still has rows to read.
            var exception = assertThrows(PersistenceException.class,
                    () -> vets.update(vets.select().getResultStream()
                            .map(vet -> vet.toBuilder().lastName(vet.lastName() + " DVM").build()), 2));
            assertTrue(exception.getMessage().contains("result stream is still open"), exception.getMessage());
        }
    }

    @Test
    public void refFetchWhileStreamIsOpenNamesTheFetchPlan() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            try (var pets = orm.selectFrom(Pet.class).getResultStream()) {
                var exception = assertThrows(PersistenceException.class,
                        () -> pets.forEach(pet -> pet.type().fetch()));
                assertTrue(exception.getMessage().contains("fetch plan"), exception.getMessage());
            }
        }
    }

    @Test
    public void statementAfterStreamIsReadToItsEndIsAllowed() throws SQLException {
        // A result read to its end blocks nothing on any driver, so the connection is free before the stream closes.
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            var vets = orm.entity(Vet.class);
            try (var stream = vets.select().getResultStream()) {
                assertEquals(6, stream.count());
                assertEquals(6, vets.count());
            }
            // The same holds for a batched write fed by a stream it reads to the end before its first batch.
            vets.update(vets.select().getResultStream()
                    .map(vet -> vet.toBuilder().lastName(vet.lastName() + " DVM").build()));
            assertTrue(vets.select().getResultList().stream().allMatch(vet -> vet.lastName().endsWith(" DVM")));
        }
    }

    @Test
    public void statementWhileRowsRemainUnreadIsRefused() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            var vets = orm.entity(Vet.class);
            try (var stream = vets.select().getResultStream()) {
                assertEquals(2, stream.limit(2).toList().size());
                assertThrows(PersistenceException.class, vets::count);
            }
            assertEquals(6, vets.count());
        }
    }

    @Test
    public void statementThatCannotNameItsConnectionIsNotGuarded() throws SQLException {
        // The guard identifies the connection through the statement's own getConnection(). A driver or wrapper
        // that cannot answer it leaves the connection unguarded rather than failing the statement.
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(withAnonymousStatements(connection));
            var counted = new AtomicInteger();
            try (var vets = orm.selectFrom(Vet.class).getResultStream()) {
                vets.forEach(vet -> counted.addAndGet((int) orm.selectFrom(Vet.class).getResultCount()));
            }
            assertEquals(36, counted.get());
        }
    }

    /** Wraps the connection so every statement it prepares throws from {@code getConnection()}. */
    private static Connection withAnonymousStatements(Connection connection) {
        return (Connection) java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                new Class<?>[] {Connection.class}, (ignore, method, args) -> {
                    Object result;
                    try {
                        result = method.invoke(connection, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    if (result instanceof java.sql.PreparedStatement statement) {
                        return java.lang.reflect.Proxy.newProxyInstance(Connection.class.getClassLoader(),
                                new Class<?>[] {java.sql.PreparedStatement.class}, (ignored, statementMethod, statementArgs) -> {
                                    if (statementMethod.getName().equals("getConnection")) {
                                        throw new SQLException("This statement does not name its connection.");
                                    }
                                    try {
                                        return statementMethod.invoke(statement, statementArgs);
                                    } catch (java.lang.reflect.InvocationTargetException e) {
                                        throw e.getTargetException();
                                    }
                                });
                    }
                    return result;
                });
    }

    @Test
    public void secondStreamOnSharedConnectionIsRefused() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            try (var vets = orm.selectFrom(Vet.class).getResultStream()) {
                assertThrows(PersistenceException.class, () -> orm.selectFrom(Pet.class).getResultStream());
                assertEquals(6, vets.count());
            }
        }
    }

    @Test
    public void statementWhileStreamIsOpenOnItsOwnConnectionIsAllowed() {
        // Outside a transaction each statement obtains its own connection, so the stream and the statement never
        // meet. The test suite binds its statements to one Spring-managed connection, so the plain JDBC provider
        // stands in for the unbound case.
        var orm = ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(new JdbcTransactionTemplateProviderImpl())
                .build();
        var counted = new AtomicInteger();
        try (var vets = orm.selectFrom(Vet.class).getResultStream()) {
            vets.forEach(vet -> counted.addAndGet((int) orm.selectFrom(Vet.class).getResultCount()));
        }
        assertEquals(36, counted.get());
    }

    @Test
    public void eagerReadsLeaveNoStreamOpen() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            var counted = new AtomicInteger();
            orm.selectFrom(Vet.class).getResultList()
                    .forEach(vet -> counted.addAndGet((int) orm.selectFrom(Vet.class).getResultCount()));
            assertEquals(36, counted.get());
        }
    }

    @Test
    public void windowsLeaveTheSharedConnectionFreeBetweenWindows() throws SQLException {
        try (var connection = sharedConnection()) {
            var orm = ORMTemplate.of(connection);
            var vets = orm.entity(Vet.class);
            var windows = new AtomicInteger();
            vets.windows(2).forEach(window -> {
                // A query and a batched write per window, on the connection the windows are read from.
                assertEquals(6, vets.count());
                vets.update(window.content().stream()
                        .map(vet -> vet.toBuilder().lastName(vet.lastName() + " DVM").build())
                        .toList());
                windows.incrementAndGet();
            });
            assertEquals(3, windows.get());
            assertTrue(vets.select().getResultList().stream().allMatch(vet -> vet.lastName().endsWith(" DVM")));
        }
    }
}
