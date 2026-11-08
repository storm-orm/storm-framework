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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.REQUIRES_NEW;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.PersistenceException;
import st.orm.TransactionIsolation;
import st.orm.TransactionOptions;
import st.orm.TransactionPropagation;
import st.orm.TransientPersistenceException;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.TemplateString;
import st.orm.spi.ExceptionContext;
import st.orm.spi.ExceptionMapper;

/**
 * A failure of the commit is classified by the dialect as a statement's failure is, under the same guard against a
 * lost connection, and passes the exception mapper of the template that opened the transaction: a transient one
 * throws {@link TransientPersistenceException} and reruns a block that states retries.
 *
 * <p>The commit fails on a connection whose {@code commit()} rolls the transaction back and throws the next error
 * of {@link FailingCommitDataSource#failures}, as a database that detects a serialization conflict at commit
 * does.</p>
 */
@Timeout(30)
class CommitFailureClassificationTest {

    /**
     * Hands out proxies over its connections whose {@code commit()} rolls back and throws the next queued error, or
     * commits once the queue is empty.
     */
    static final class FailingCommitDataSource extends DelegatingDataSource {
        final Deque<SQLException> failures = new ArrayDeque<>();

        FailingCommitDataSource(DataSource target) {
            super(target);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[] {Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("commit")) {
                            var failure = failures.poll();
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

    /**
     * A failure type of a mapper's own, outside Storm's hierarchy.
     */
    static final class MappedFailure extends RuntimeException {
        MappedFailure(Throwable cause) {
            super(cause);
        }
    }

    private final JdbcTransactionTemplateProviderImpl transactionTemplateProvider =
            new JdbcTransactionTemplateProviderImpl();
    private FailingCommitDataSource dataSource;
    private ORMTemplate orm;
    private final List<ExceptionContext> contexts = new ArrayList<>();

    @BeforeEach
    void createDatabase() {
        dataSource = new FailingCommitDataSource(new DriverManagerDataSource(
                "jdbc:h2:mem:commit_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1"));
        orm = template(ExceptionMapper.defaultMapper());
        execute("CREATE TABLE counter (id INTEGER PRIMARY KEY, amount INTEGER NOT NULL)");
        execute("INSERT INTO counter (id, amount) VALUES (1, 0)");
    }

    @AfterEach
    void dropDatabase() {
        dataSource.failures.clear();
        execute("SHUTDOWN");
    }

    private ORMTemplate template(ExceptionMapper exceptionMapper) {
        ExceptionMapper recording = (cause, context) -> {
            contexts.add(context);
            return exceptionMapper.map(cause, context);
        };
        return ORMTemplate.builder(dataSource)
                .connectionProvider(new JdbcConnectionProviderImpl())
                .transactionTemplateProvider(transactionTemplateProvider)
                .exceptionMapper(recording)
                .build();
    }

    private void execute(String statement) {
        orm.query(TemplateString.of(statement)).executeUpdate();
    }

    private void increment(ORMTemplate template) {
        template.query(TemplateString.of("UPDATE counter SET amount = amount + 1 WHERE id = 1")).executeUpdate();
    }

    private int amount() {
        var row = orm.query(TemplateString.of("SELECT amount FROM counter WHERE id = 1")).getSingleResult();
        return ((Number) row[0]).intValue();
    }

    private static TransactionOptions options(TransactionPropagation propagation) {
        return new TransactionOptions(propagation, TransactionIsolation.SERIALIZABLE, null, false);
    }

    private static SQLException serializationFailure() {
        return new SQLException("could not serialize access due to read/write dependencies", "40001");
    }

    @Test
    void aSerializationFailureTheCommitReportsIsTransient() {
        var failure = serializationFailure();
        dataSource.failures.add(failure);
        var thrown = assertThrows(TransientPersistenceException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), transaction -> {
                    increment(orm);
                    return null;
                }));
        assertSame(failure, thrown.getCause().getCause(), "the database's error is in the chain");
        var context = contexts.getLast();
        assertTrue(context.statement().isEmpty(), "a commit has no statement");
        assertTrue(context.transactionDescription().isEmpty(), "the transaction has completed");
        assertEquals(0, amount(), "the transaction did not commit");
    }

    @Test
    void aCommitFailureOnALostConnectionIsNotTransient() {
        var lost = new SQLException("connection reset", "08006");
        lost.setNextException(serializationFailure());
        dataSource.failures.add(lost);
        var attempts = new AtomicInteger();
        var thrown = assertThrows(PersistenceException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), 2, transaction -> {
                    attempts.incrementAndGet();
                    increment(orm);
                    return null;
                }));
        assertFalse(thrown instanceof TransientPersistenceException, "a lost connection is never transient");
        assertSame(lost, thrown.getCause());
        assertEquals(1, attempts.get(), "a commit whose outcome is unknown does not rerun");
    }

    @Test
    void aCommitFailureOnARecoverableConnectionIsNotTransient() {
        dataSource.failures.add(new SQLRecoverableException("connection closed", "40001"));
        var thrown = assertThrows(PersistenceException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), transaction -> {
                    increment(orm);
                    return null;
                }));
        assertFalse(thrown instanceof TransientPersistenceException);
    }

    @Test
    void aCommitFailureTheDialectDoesNotRecogniseIsNotTransient() {
        dataSource.failures.add(new SQLException("deferred constraint violated", "23505"));
        var thrown = assertThrows(PersistenceException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), 2, transaction -> {
                    increment(orm);
                    return null;
                }));
        assertFalse(thrown instanceof TransientPersistenceException);
    }

    @Test
    void aRerunAfterATransientCommitFailureGetsFreshCallbacksAndCommitsOnce() {
        dataSource.failures.add(serializationFailure());
        var attempts = new AtomicInteger();
        var commits = new AtomicInteger();
        var rollbacks = new AtomicInteger();
        var callbacksPerAttempt = new ArrayList<Integer>();
        var result = TransactionRunner.<String, RuntimeException>execute(options(REQUIRED), 2, transaction -> {
            int attempt = attempts.incrementAndGet();
            transaction.onCommit(() -> {
                commits.incrementAndGet();
                callbacksPerAttempt.add(attempt);
            });
            transaction.onRollback(() -> {
                rollbacks.incrementAndGet();
                callbacksPerAttempt.add(-attempt);
            });
            increment(orm);
            return "attempt " + attempt;
        });
        assertEquals("attempt 2", result);
        assertEquals(2, attempts.get());
        assertEquals(1, commits.get(), "onCommit fires once, for the attempt that committed");
        assertEquals(1, rollbacks.get(), "the attempt whose commit failed rolls back");
        assertEquals(List.of(-1, 2), callbacksPerAttempt, "each attempt settles its own callbacks");
        assertEquals(1, amount(), "the rerun's write alone");
    }

    @Test
    void exhaustedRetriesAfterCommitFailuresThrowWithTheEarlierFailuresSuppressed() {
        dataSource.failures.add(serializationFailure());
        dataSource.failures.add(serializationFailure());
        var thrown = assertThrows(TransientPersistenceException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), 1, transaction -> {
                    increment(orm);
                    return null;
                }));
        assertEquals(1, List.of(thrown.getSuppressed()).stream()
                .filter(TransientPersistenceException.class::isInstance)
                .count());
        assertEquals(0, amount());
    }

    @Test
    void aCustomMapperDecidesTheTypeOfATransientCommitFailureAndTheBlockStillReruns() {
        var mapped = template((cause, context) -> cause instanceof TransientPersistenceException
                ? new MappedFailure(cause)
                : ExceptionMapper.defaultMapper().map(cause, context));
        dataSource.failures.add(serializationFailure());
        var thrown = assertThrows(MappedFailure.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), transaction -> {
                    increment(mapped);
                    return null;
                }));
        assertInstanceOf(PersistenceException.class, thrown.getCause());
        dataSource.failures.add(serializationFailure());
        var attempts = new AtomicInteger();
        TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), 1, transaction -> {
            attempts.incrementAndGet();
            increment(mapped);
            return null;
        });
        assertEquals(2, attempts.get(), "the rerun decides on the classification, not on the mapped type");
        assertEquals(1, amount());
    }

    @Test
    void theTemplateThatOpensTheTransactionMapsItsCommit() {
        var mapped = template((cause, context) -> new MappedFailure(cause));
        dataSource.failures.add(new SQLException("deferred constraint violated", "23505"));
        assertThrows(MappedFailure.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), transaction -> {
                    increment(mapped);
                    increment(orm);
                    return null;
                }));
        dataSource.failures.add(new SQLException("deferred constraint violated", "23505"));
        var thrown = assertThrows(PersistenceException.class,
                () -> TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), transaction -> {
                    increment(orm);
                    increment(mapped);
                    return null;
                }));
        assertFalse(thrown instanceof TransientPersistenceException);
    }

    @Test
    void aBlockThatStartsItsOwnTransactionInsideAnotherRerunsOnItsCommitFailure() {
        var attempts = new AtomicInteger();
        TransactionRunner.<Object, RuntimeException>execute(options(REQUIRED), outer -> {
            increment(orm);
            dataSource.failures.add(serializationFailure());
            return TransactionRunner.<Object, RuntimeException>execute(options(REQUIRES_NEW), 1, inner -> {
                attempts.incrementAndGet();
                orm.query(TemplateString.of("INSERT INTO counter (id, amount) VALUES (2, 0)")).executeUpdate();
                return null;
            });
        });
        assertEquals(2, attempts.get());
        assertEquals(1, amount());
    }

    /**
     * A provider whose transactions complete with the given failure, to show which failures of completing a scope
     * pass the commit transformer.
     */
    private static TransactionTemplateProvider completingWith(Throwable failure) {
        return () -> new TransactionTemplate() {
            @Override
            public TransactionTemplate propagation(TransactionPropagation propagation) {
                return this;
            }

            @Override
            public TransactionTemplate isolation(TransactionIsolation isolation) {
                return this;
            }

            @Override
            public TransactionTemplate readOnly(boolean readOnly) {
                return this;
            }

            @Override
            public TransactionTemplate timeout(int timeoutSeconds) {
                return this;
            }

            @Override
            public TransactionHandle open(@Nullable TransactionContext existing, boolean suspendMode) {
                var context = new JdbcTransactionContext();
                return new TransactionHandle() {
                    @Override
                    public TransactionContext context() {
                        return context;
                    }

                    @Override
                    public TransactionStatus status() {
                        return new TransactionStatus() {
                            @Override
                            public void setRollbackOnly() {
                            }

                            @Override
                            public boolean isRollbackOnly() {
                                return false;
                            }
                        };
                    }

                    @Override
                    public void complete(boolean rollback) {
                        if (failure instanceof Error error) {
                            throw error;
                        }
                        throw (RuntimeException) failure;
                    }
                };
            }

            @Override
            public Optional<TransactionContext> currentContext() {
                return Optional.empty();
            }

            @Override
            public ThreadLocal<TransactionContext> contextHolder() {
                return new ThreadLocal<>();
            }
        };
    }

    private static TransactionScope materialized(Throwable completionFailure, List<Throwable> transformed) {
        var scope = TransactionScope.create(options(REQUIRED), false, null);
        scope.getOrMaterializeContext(completingWith(completionFailure), null, failure -> {
            transformed.add(failure);
            return new MappedFailure(failure);
        });
        return scope;
    }

    @Test
    void aFailingCommitPassesTheTransformer() {
        var failure = new PersistenceException("Commit failed.");
        List<Throwable> transformed = new ArrayList<>();
        var scope = materialized(failure, transformed);
        var thrown = assertThrows(MappedFailure.class, () -> scope.complete(false));
        assertSame(failure, thrown.getCause());
        assertEquals(List.of(failure), transformed);
    }

    @Test
    void aFailingRollbackIsThrownUnmapped() {
        var failure = new PersistenceException("Rollback failed.");
        List<Throwable> transformed = new ArrayList<>();
        var scope = materialized(failure, transformed);
        assertSame(failure, assertThrows(PersistenceException.class, () -> scope.complete(true)));
        assertTrue(transformed.isEmpty());
    }

    @Test
    void anErrorDuringTheCommitIsThrownUnmapped() {
        var failure = new AssertionError("commit broke");
        List<Throwable> transformed = new ArrayList<>();
        var scope = materialized(failure, transformed);
        assertSame(failure, assertThrows(AssertionError.class, () -> scope.complete(false)));
        assertTrue(transformed.isEmpty());
    }
}
