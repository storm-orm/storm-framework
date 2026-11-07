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
import java.util.function.IntUnaryOperator;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.DbTable;
import st.orm.Entity;
import st.orm.PK;
import st.orm.PersistenceException;
import st.orm.UnexpectedRowCountException;
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

    private volatile IntUnaryOperator reportedCount = IntUnaryOperator.identity();
    private ORMTemplate orm;

    @BeforeEach
    void setUp() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:batch_counts_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE batch_tag (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            statement.execute("INSERT INTO batch_tag (id, name) VALUES (1, 'first'), (2, 'second'), (3, 'third')");
            statement.execute("CREATE TABLE joined_animal (id INTEGER AUTO_INCREMENT PRIMARY KEY, dtype VARCHAR(50) NOT NULL, name VARCHAR(255))");
            statement.execute("CREATE TABLE joined_cat (id INTEGER PRIMARY KEY, indoor BOOLEAN NOT NULL, FOREIGN KEY (id) REFERENCES joined_animal (id))");
            statement.execute("CREATE TABLE joined_dog (id INTEGER PRIMARY KEY, weight INTEGER NOT NULL, FOREIGN KEY (id) REFERENCES joined_animal (id))");
            statement.execute("INSERT INTO joined_animal (dtype, name) VALUES ('JoinedCat', 'Whiskers'), ('JoinedCat', 'Luna')");
            statement.execute("INSERT INTO joined_cat (id, indoor) VALUES (1, true), (2, false)");
        }
        orm = ORMTemplate.of(reporting(h2));
    }

    /** Wraps the data source so the counts of every batch its prepared statements execute pass through the test's choice. */
    private DataSource reporting(DataSource target) {
        return proxy(DataSource.class, target, (method, result) -> result instanceof Connection connection
                ? proxy(Connection.class, connection, (connectionMethod, connectionResult) ->
                        connectionResult instanceof PreparedStatement statement
                                ? proxy(PreparedStatement.class, statement, (statementMethod, statementResult) ->
                                        statementMethod.getName().equals("executeBatch")
                                                ? Arrays.stream((int[]) statementResult).map(reportedCount).toArray()
                                                : statementResult)
                                : connectionResult)
                : result);
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(Method method, Object result);
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
                    return observer.observe(method, result);
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
        assertEquals(0, animals.count());
    }

    @Test
    void joinedBatchUpdateWithCountWithoutInfoMovesChangedType() {
        var animals = orm.entity(JoinedAnimal.class);
        reportedCount = count -> SUCCESS_NO_INFO;
        animals.update(List.of(new JoinedCat(1, "Whiskers", false), new JoinedDog(2, "Luna", 12)));
        reportedCount = IntUnaryOperator.identity();
        assertEquals(new JoinedCat(1, "Whiskers", false), animals.getById(1));
        assertEquals(new JoinedDog(2, "Luna", 12), animals.getById(2));
    }
}
