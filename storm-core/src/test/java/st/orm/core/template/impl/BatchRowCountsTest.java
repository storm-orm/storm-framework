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

import static java.sql.Statement.EXECUTE_FAILED;
import static java.sql.Statement.SUCCESS_NO_INFO;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.IntUnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.OptimisticLockException;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.UnexpectedRowCountException;
import st.orm.Version;
import st.orm.core.model.polymorphic.JoinedAnimal;
import st.orm.core.model.polymorphic.JoinedCat;
import st.orm.core.model.polymorphic.JoinedDog;
import st.orm.core.template.ORMTemplate;

/**
 * A driver may report {@link Statement#SUCCESS_NO_INFO} for a statement of a batch that succeeded without
 * a known row count. The batch checks accept it as a success and fail on {@link Statement#EXECUTE_FAILED}
 * and every count other than the expected ones. The repository checks run against a data source whose prepared
 * statements report the counts the test chooses.
 */
class BatchRowCountsTest {

    @DbTable("batch_tag")
    public record BatchTag(@PK Integer id, String name) implements Entity<Integer> {}

    @DbTable("versioned_tag")
    public record VersionedTag(@PK Integer id, String name, @Version int version) implements Entity<Integer> {}

    private volatile IntUnaryOperator reportedCount = IntUnaryOperator.identity();
    private final List<String> prepared = new CopyOnWriteArrayList<>();
    private ORMTemplate orm;

    @BeforeEach
    void setUp() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:batch_counts_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE batch_tag (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO batch_tag (id, name) VALUES (1, 'first'), (2, 'second'), (3, 'third')");
            statement.execute("CREATE TABLE versioned_tag (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL, version INTEGER NOT NULL)");
            statement.execute("INSERT INTO versioned_tag (id, name, version) VALUES (1, 'first', 0), (2, 'second', 0)");
            statement.execute("CREATE TABLE joined_animal (id INTEGER AUTO_INCREMENT PRIMARY KEY, dtype VARCHAR(50) NOT NULL, name VARCHAR(255))");
            statement.execute("CREATE TABLE joined_cat (id INTEGER PRIMARY KEY, indoor BOOLEAN NOT NULL, FOREIGN KEY (id) REFERENCES joined_animal (id))");
            statement.execute("CREATE TABLE joined_dog (id INTEGER PRIMARY KEY, weight INTEGER NOT NULL, FOREIGN KEY (id) REFERENCES joined_animal (id))");
            statement.execute("INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Whiskers'), ('JoinedCat', 'Luna'), ('JoinedCat', 'Milo'), ('JoinedDog', 'Rex')");
            statement.execute("INSERT INTO joined_cat (id, indoor) VALUES (1, true), (2, false), (3, true)");
            statement.execute("INSERT INTO joined_dog (id, weight) VALUES (4, 30)");
        }
        orm = ORMTemplate.of(reporting(h2));
    }

    /**
     * Wraps the data source so the counts of every batch its prepared statements execute pass through the test's
     * choice, and the statements it prepares are recorded.
     */
    private DataSource reporting(DataSource target) {
        return proxy(DataSource.class, target, (method, arguments, result) -> result instanceof Connection connection
                ? proxy(Connection.class, connection, (connectionMethod, connectionArguments, connectionResult) -> {
                    if (!(connectionResult instanceof PreparedStatement statement)) {
                        return connectionResult;
                    }
                    prepared.add(String.valueOf(connectionArguments[0]).strip());
                    return proxy(PreparedStatement.class, statement, (statementMethod, statementArguments, statementResult) ->
                            statementMethod.getName().equals("executeBatch")
                                    ? Arrays.stream((int[]) statementResult).map(reportedCount).toArray()
                                    : statementResult);
                })
                : result);
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(Method method, Object[] arguments, Object result);
    }

    private long preparedStartingWith(String prefix) {
        return prepared.stream().filter(sql -> sql.toUpperCase().startsWith(prefix)).count();
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T target, Observer observer) {
        return (T) Proxy.newProxyInstance(BatchRowCountsTest.class.getClassLoader(), new Class<?>[] {type},
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

    @Test
    void countWithoutInfoIsSuccess() {
        assertTrue(BatchRowCounts.isSuccess(SUCCESS_NO_INFO, 1));
        assertTrue(BatchRowCounts.allSucceeded(new int[] {1, SUCCESS_NO_INFO, 1}, 1));
        assertTrue(BatchRowCounts.allSucceeded(new int[] {0, 2, SUCCESS_NO_INFO}, 0, 1, 2));
        assertTrue(BatchRowCounts.allSucceeded(new int[0], 1));
    }

    @Test
    void failedOrUnexpectedCountIsFailure() {
        assertFalse(BatchRowCounts.isSuccess(EXECUTE_FAILED, 1));
        assertFalse(BatchRowCounts.isSuccess(0, 1));
        assertFalse(BatchRowCounts.isSuccess(2, 1));
        assertFalse(BatchRowCounts.allSucceeded(new int[] {1, EXECUTE_FAILED}, 1));
        assertFalse(BatchRowCounts.allSucceeded(new int[] {SUCCESS_NO_INFO, 0}, 1));
        assertFalse(BatchRowCounts.allSucceeded(new int[] {3}, 0, 1, 2));
    }

    @Test
    void knownAffectedSumsReportedRows() {
        assertEquals(3, BatchRowCounts.knownAffected(new int[] {1, SUCCESS_NO_INFO, 2, EXECUTE_FAILED, 0}));
    }

    @Test
    void batchRemoveAcceptsCountWithoutInfo() {
        var tags = orm.entity(BatchTag.class);
        reportedCount = count -> SUCCESS_NO_INFO;
        tags.remove(List.of(new BatchTag(1, "first"), new BatchTag(2, "second")));
        reportedCount = IntUnaryOperator.identity();
        assertEquals(List.of(new BatchTag(3, "third")), tags.findAll());
    }

    @Test
    void batchUpdateAcceptsCountWithoutInfo() {
        var tags = orm.entity(BatchTag.class);
        reportedCount = count -> SUCCESS_NO_INFO;
        tags.update(List.of(new BatchTag(1, "one"), new BatchTag(2, "two")));
        reportedCount = IntUnaryOperator.identity();
        assertEquals("one", tags.getById(1).name());
        assertEquals("two", tags.getById(2).name());
    }

    @Test
    void batchRemoveFailsOnExecuteFailed() {
        var tags = orm.entity(BatchTag.class);
        reportedCount = count -> EXECUTE_FAILED;
        var failure = assertThrows(PersistenceException.class,
                () -> tags.remove(List.of(new BatchTag(1, "first"), new BatchTag(2, "second"))));
        assertInstanceOf(UnexpectedRowCountException.class, failure);
    }

    @Test
    void batchRemoveFailsOnMissingRowAlongsideCountWithoutInfo() {
        var tags = orm.entity(BatchTag.class);
        reportedCount = count -> count == 1 ? SUCCESS_NO_INFO : count;
        assertThrows(UnexpectedRowCountException.class,
                () -> tags.remove(List.of(new BatchTag(1, "first"), new BatchTag(99, "missing"))));
    }

    @Test
    void joinedBatchRemoveAcceptsCountWithoutInfo() {
        var animals = orm.entity(JoinedAnimal.class);
        reportedCount = count -> SUCCESS_NO_INFO;
        animals.remove(List.of(new JoinedCat(1, "Whiskers", true), new JoinedCat(2, "Luna", false)));
        reportedCount = IntUnaryOperator.identity();
        assertEquals(2, animals.count());
    }

    @Test
    void joinedBatchUpdateWithCountWithoutInfoMovesChangedType() {
        var animals = orm.entity(JoinedAnimal.class);
        reportedCount = count -> SUCCESS_NO_INFO;
        prepared.clear();
        animals.update(List.of(new JoinedCat(1, "Whiskers", false), new JoinedDog(2, "Luna", 12),
                new JoinedCat(3, "Milo", false), new JoinedCat(4, "Rex", true)));
        reportedCount = IntUnaryOperator.identity();
        // One statement per extension table for each subtype in the batch, however many entities it holds.
        assertEquals(4, preparedStartingWith("DELETE"), prepared.toString());
        assertEquals(new JoinedCat(1, "Whiskers", false), animals.getById(1));
        assertEquals(new JoinedDog(2, "Luna", 12), animals.getById(2));
        assertEquals(new JoinedCat(3, "Milo", false), animals.getById(3));
        assertEquals(new JoinedCat(4, "Rex", true), animals.getById(4));
    }

    @Test
    void joinedBatchUpdateWithReportedCountsMovesChangedTypeWithOneStatementPerTable() {
        var animals = orm.entity(JoinedAnimal.class);
        prepared.clear();
        animals.update(List.of(new JoinedDog(1, "Whiskers", 3), new JoinedDog(2, "Luna", 12),
                new JoinedCat(3, "Milo", false)));
        // The subtype changed for two dogs: one statement deletes their rows from the other extension table.
        assertEquals(1, preparedStartingWith("DELETE"), prepared.toString());
        assertEquals(new JoinedDog(1, "Whiskers", 3), animals.getById(1));
        assertEquals(new JoinedDog(2, "Luna", 12), animals.getById(2));
        assertEquals(new JoinedCat(3, "Milo", false), animals.getById(3));
    }

    @Test
    void versionedBatchUpdateWithReportedCountsRunsNoExtraStatement() {
        var tags = orm.entity(VersionedTag.class);
        prepared.clear();
        tags.update(List.of(new VersionedTag(1, "one", 0), new VersionedTag(2, "two", 0)));
        assertEquals(1, prepared.size(), prepared.toString());
        assertEquals(1, tags.getById(1).version());
    }

    @Test
    void versionedBatchUpdateConflictWithReportedCountsIsOptimisticLock() {
        var tags = orm.entity(VersionedTag.class);
        assertThrows(OptimisticLockException.class,
                () -> tags.update(List.of(new VersionedTag(1, "one", 0), new VersionedTag(2, "stale", 7))));
    }

    @Test
    void versionedBatchUpdateWithCountWithoutInfoCannotVerifyLocking() {
        var tags = orm.entity(VersionedTag.class);
        reportedCount = count -> SUCCESS_NO_INFO;
        var failure = assertThrows(PersistenceException.class,
                () -> tags.update(List.of(new VersionedTag(1, "one", 0), new VersionedTag(2, "stale", 7))));
        assertFalse(failure instanceof OptimisticLockException);
        assertTrue(failure.getMessage().contains("VersionedTag"), failure.getMessage());
        assertTrue(failure.getMessage().contains("cannot verify optimistic locking"), failure.getMessage());
    }
}
