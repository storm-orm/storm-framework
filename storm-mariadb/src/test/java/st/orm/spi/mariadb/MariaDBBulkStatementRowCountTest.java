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
package st.orm.spi.mariadb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static st.orm.Polymorphic.Strategy.JOINED;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import javax.sql.DataSource;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MariaDBContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import st.orm.DbTable;
import st.orm.Discriminator;
import st.orm.Entity;
import st.orm.OptimisticLockException;
import st.orm.PK;
import st.orm.Polymorphic;
import st.orm.UnexpectedRowCountException;
import st.orm.Version;
import st.orm.core.template.ORMTemplate;
import st.orm.tck.ContainerDataSource;

/**
 * The driver reports the update count of every statement in a batch, with its bulk statements enabled
 * ({@code useBulkStmts=true}) as without them, so the batch checks see a conflict or a missing row as a count of
 * zero. The writes run against a data source that records the counts of every batch, on a connection of each kind.
 */
@Testcontainers
public class MariaDBBulkStatementRowCountTest {

    @SuppressWarnings("resource")
    @Container
    public static MariaDBContainer<?> mariadbContainer = new MariaDBContainer<>("mariadb:11.8")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")
            .waitingFor(Wait.forListeningPort());

    @DbTable("bulk_item")
    public record BulkItem(@PK Integer id, String name, @Version int version) implements Entity<Integer> {}

    @DbTable("plain_item")
    public record PlainItem(@PK Integer id, String name) implements Entity<Integer> {}

    @Discriminator
    @Polymorphic(JOINED)
    @DbTable("bulk_animal")
    public sealed interface BulkAnimal extends Entity<Integer> permits BulkCat, BulkDog {}

    @DbTable("bulk_cat")
    public record BulkCat(@PK Integer id, String name, boolean indoor) implements BulkAnimal {}

    @DbTable("bulk_dog")
    public record BulkDog(@PK Integer id, String name, int weight) implements BulkAnimal {}

    private final List<String> counts = new CopyOnWriteArrayList<>();

    /** Wraps the data source so the counts of every batch it executes are recorded with the statement. */
    private DataSource recording(DataSource target) {
        return proxy(DataSource.class, target, (method, arguments, result) -> result instanceof Connection connection
                ? proxy(Connection.class, connection, (connectionMethod, connectionArguments, connectionResult) -> {
                    if (!(connectionResult instanceof PreparedStatement statement)) {
                        return connectionResult;
                    }
                    String sql = String.valueOf(connectionArguments[0]).strip().replaceAll("\\s+", " ");
                    return proxy(PreparedStatement.class, statement, (statementMethod, statementArguments, statementResult) -> {
                        if (statementMethod.getName().equals("executeBatch")) {
                            counts.add(sql.substring(0, sql.indexOf(' ', sql.indexOf(' ') + 1)) + " "
                                    + Arrays.toString((int[]) statementResult));
                        }
                        return statementResult;
                    });
                })
                : result);
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(Method method, Object[] arguments, Object result);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Observer observer) {
        return (T) Proxy.newProxyInstance(MariaDBBulkStatementRowCountTest.class.getClassLoader(), new Class<?>[] {type},
                (proxy, method, arguments) -> {
                    Object result;
                    try {
                        result = method.invoke(target, arguments);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                    return observer.observe(method, arguments, result);
                });
    }

    private ORMTemplate orm(boolean bulkStatements) throws SQLException {
        String url = mariadbContainer.getJdbcUrl();
        if (bulkStatements) {
            url += (url.contains("?") ? "&" : "?") + "useBulkStmts=true";
        }
        var dataSource = ContainerDataSource.of(url, mariadbContainer.getUsername(), mariadbContainer.getPassword());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE IF EXISTS bulk_cat, bulk_dog, bulk_animal, bulk_item, plain_item");
            statement.execute("CREATE TABLE bulk_item (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50), version INT NOT NULL)");
            statement.execute("CREATE TABLE plain_item (id INT AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50))");
            statement.execute("CREATE TABLE bulk_animal (id INT AUTO_INCREMENT PRIMARY KEY, dtype VARCHAR(50) NOT NULL, name VARCHAR(50))");
            statement.execute("CREATE TABLE bulk_cat (id INT PRIMARY KEY, indoor BOOLEAN NOT NULL, FOREIGN KEY (id) REFERENCES bulk_animal (id))");
            statement.execute("CREATE TABLE bulk_dog (id INT PRIMARY KEY, weight INT NOT NULL, FOREIGN KEY (id) REFERENCES bulk_animal (id))");
            statement.execute("INSERT INTO bulk_item (name, version) VALUES ('a', 0), ('b', 0), ('c', 0)");
            statement.execute("INSERT INTO plain_item (name) VALUES ('a'), ('b'), ('c')");
            statement.execute("INSERT INTO bulk_animal (dtype, name) VALUES ('BulkCat', 'a'), ('BulkCat', 'b')");
            statement.execute("INSERT INTO bulk_cat (id, indoor) VALUES (1, true), (2, false)");
        }
        return ORMTemplate.of(recording(dataSource));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void versionedUpdateReportsCountsAndDetectsConflict(boolean bulkStatements) throws SQLException {
        var items = orm(bulkStatements).entity(BulkItem.class);
        items.update(List.of(new BulkItem(1, "x", 0), new BulkItem(2, "y", 0), new BulkItem(3, "z", 0)));
        assertThrows(OptimisticLockException.class,
                () -> items.update(List.of(new BulkItem(1, "x2", 1), new BulkItem(2, "stale", 0))));
        assertEquals(List.of("UPDATE bulk_item [1, 1, 1]", "UPDATE bulk_item [1, 0]"), counts);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void unversionedUpdateReportsCountsAndDetectsMissingRow(boolean bulkStatements) throws SQLException {
        var items = orm(bulkStatements).entity(PlainItem.class);
        items.update(List.of(new PlainItem(1, "x"), new PlainItem(2, "y"), new PlainItem(3, "z")));
        assertThrows(UnexpectedRowCountException.class,
                () -> items.update(List.of(new PlainItem(1, "x"), new PlainItem(99, "missing"))));
        assertEquals(List.of("UPDATE plain_item [1, 1, 1]", "UPDATE plain_item [1, 0]"), counts);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void deleteReportsCounts(boolean bulkStatements) throws SQLException {
        var items = orm(bulkStatements).entity(PlainItem.class);
        items.remove(List.of(new PlainItem(1, "a"), new PlainItem(2, "b")));
        assertEquals(List.of("DELETE FROM [1, 1]"), counts);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void joinedExtensionUpdateReportsCounts(boolean bulkStatements) throws SQLException {
        var animals = orm(bulkStatements).entity(BulkAnimal.class);
        animals.update(List.of(new BulkCat(1, "a2", false), new BulkDog(2, "b2", 12)));
        assertEquals(List.of("UPDATE bulk_animal [1, 1]", "UPDATE bulk_cat [1]", "UPDATE bulk_dog [0]",
                "INSERT INTO [1]"), counts);
        assertEquals(new BulkDog(2, "b2", 12), animals.getById(2));
    }
}
