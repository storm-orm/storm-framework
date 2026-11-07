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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;
import static st.orm.TransactionPropagation.SUPPORTS;
import static st.orm.template.Transactions.transaction;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import st.orm.IllegalTransactionStateException;
import st.orm.template.ORMTemplate;

/**
 * Verifies that a Storm block with retries reruns on a transient failure when the template runs through Spring's
 * transaction manager and exception translation, where the failure surfaces as a {@link TransientDataAccessException}
 * rather than a Storm type, and that a block joining a Spring-managed transaction refuses retries.
 *
 * <p>The transient failure comes from a database function that fails with a serialization failure while
 * {@link #CONFLICTS} is positive, or from a commit that fails with the next error of {@link #COMMIT_FAILURES}, as a
 * database that detects a serialization conflict at commit reports it to Spring's transaction manager.</p>
 */
@Timeout(30)
public class SpringTransactionRetriesTest {

    private static final AtomicInteger CONFLICTS = new AtomicInteger();

    private static final Deque<SQLException> COMMIT_FAILURES = new ArrayDeque<>();

    private DataSource dataSource;
    private DataSourceTransactionManager transactionManager;
    private ORMTemplate orm;

    /**
     * The database function behind {@code CONFLICT()}: fails with a serialization failure while conflicts remain.
     */
    public static int conflict() throws SQLException {
        if (CONFLICTS.getAndDecrement() > 0) {
            throw new SQLException("Simulated serialization failure.", "40001", 40001);
        }
        return 0;
    }

    /**
     * Hands out proxies over its connections whose {@code commit()} rolls back and throws the next error of
     * {@link #COMMIT_FAILURES}, or commits once there is none.
     */
    private static final class FailingCommitDataSource extends DelegatingDataSource {

        FailingCommitDataSource(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("commit")) {
                            var failure = COMMIT_FAILURES.poll();
                            if (failure != null) {
                                connection.rollback();
                                throw failure;
                            }
                        }
                        try {
                            return method.invoke(connection, arguments);
                        } catch (InvocationTargetException e) {
                            throw e.getTargetException();
                        }
                    });
        }
    }

    @BeforeEach
    void setUp() throws SQLException {
        CONFLICTS.set(0);
        COMMIT_FAILURES.clear();
        dataSource = new FailingCommitDataSource(DataSourceBuilder.create()
                .url("jdbc:h2:mem:springretries;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build());
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)");
            statement.execute("INSERT INTO counter (id, amount) VALUES (1, 0)");
            statement.execute("CREATE ALIAS CONFLICT FOR \"" + SpringTransactionRetriesTest.class.getName()
                    + ".conflict\"");
        }
        transactionManager = new DataSourceTransactionManager(dataSource);
        orm = SpringOrmTemplate.of(dataSource, () -> List.of(transactionManager));
    }

    @AfterEach
    void tearDown() throws SQLException {
        COMMIT_FAILURES.clear();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("DROP ALL OBJECTS");
        }
    }

    private void incrementAndConflict() {
        orm.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate();
        orm.query("SELECT CONFLICT()").getResultList();
    }

    private void increment() {
        orm.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate();
    }

    private static SQLException serializationFailure() {
        return new SQLException("could not serialize access due to read/write dependencies", "40001");
    }

    private int amount() {
        return ((Number) orm.query("SELECT amount FROM counter WHERE id = 1").getSingleResult()[0]).intValue();
    }

    @Test
    void aTransientFailureSpringTranslatesReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        var commits = new AtomicInteger();
        transaction(2, transaction -> {
            attempts.incrementAndGet();
            transaction.onCommit(commits::incrementAndGet);
            incrementAndConflict();
            return null;
        });
        assertEquals(2, attempts.get());
        assertEquals(1, commits.get());
        assertEquals(1, amount());
    }

    @Test
    void exhaustedRetriesThrowWhatSpringTranslatedWithTheEarlierFailuresSuppressed() {
        CONFLICTS.set(10);
        var attempts = new AtomicInteger();
        var thrown = assertThrows(TransientDataAccessException.class, () -> transaction(1, transaction -> {
            attempts.incrementAndGet();
            incrementAndConflict();
            return null;
        }));
        assertEquals(2, attempts.get());
        assertEquals(1, Arrays.stream(thrown.getSuppressed())
                .filter(TransientDataAccessException.class::isInstance)
                .count());
        assertEquals(0, amount());
    }

    @Test
    void aBlockJoiningASpringTransactionRefusesRetries() {
        var springTransaction = new TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> {
            assertThrows(IllegalTransactionStateException.class, () -> transaction(REQUIRED, 1, transaction -> null));
            assertThrows(IllegalTransactionStateException.class, () -> transaction(SUPPORTS, 1, transaction -> null));
        });
    }

    @Test
    void aBlockStartingItsOwnTransactionInsideASpringTransactionReruns() {
        CONFLICTS.set(1);
        var attempts = new AtomicInteger();
        var springTransaction = new TransactionTemplate(transactionManager);
        springTransaction.executeWithoutResult(status -> transaction(REQUIRES_NEW, 1, transaction -> {
            attempts.incrementAndGet();
            incrementAndConflict();
            return null;
        }));
        assertEquals(2, attempts.get());
        assertEquals(1, amount());
    }

    @Test
    void aSerializationFailureSpringsTransactionManagerReportsAtCommitReruns() {
        COMMIT_FAILURES.add(serializationFailure());
        var attempts = new AtomicInteger();
        var commits = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        transaction(2, transaction -> {
            attempts.incrementAndGet();
            transaction.onCommit(commits::incrementAndGet);
            transaction.onRollback(rollbacks::incrementAndGet);
            increment();
            return null;
        });
        assertEquals(2, attempts.get());
        assertEquals(1, commits.get(), "onCommit fires once, for the attempt that committed");
        assertEquals(1, rollbacks.get(), "the attempt whose commit failed rolls back");
        assertEquals(1, amount());
    }

    @Test
    void aSerializationFailureAtCommitTranslatesToATransientDataAccessException() {
        COMMIT_FAILURES.add(serializationFailure());
        var thrown = assertThrows(TransientDataAccessException.class, () -> transaction(transaction -> {
            increment();
            return null;
        }));
        var cause = assertInstanceOf(SQLException.class, thrown.getCause(), "the database's error is the cause");
        assertEquals("40001", cause.getSQLState());
        assertEquals(0, amount());
    }

    @Test
    void aCommitFailureOnALostConnectionIsNotRerun() {
        COMMIT_FAILURES.add(new SQLException("connection reset", "08006"));
        var attempts = new AtomicInteger();
        var thrown = assertThrows(DataAccessException.class, () -> transaction(2, transaction -> {
            attempts.incrementAndGet();
            increment();
            return null;
        }));
        assertFalse(thrown instanceof TransientDataAccessException, () -> "not transient: " + thrown);
        assertEquals(1, attempts.get(), "a commit whose outcome is unknown does not rerun");
    }

    @Test
    void aSerializationFailureAtCommitRerunsUnderAJdbcTransactionManagerThatTranslatesIt() {
        PlatformTransactionManager translating = new JdbcTransactionManager(dataSource);
        var template = SpringOrmTemplate.of(dataSource, () -> List.of(translating));
        COMMIT_FAILURES.add(serializationFailure());
        var attempts = new AtomicInteger();
        transaction(1, transaction -> {
            attempts.incrementAndGet();
            template.query("UPDATE counter SET amount = amount + 1 WHERE id = 1").executeUpdate();
            return null;
        });
        assertEquals(2, attempts.get());
        assertEquals(1, amount());
    }
}
