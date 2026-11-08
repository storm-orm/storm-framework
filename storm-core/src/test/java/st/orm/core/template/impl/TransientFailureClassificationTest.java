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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.BatchUpdateException;
import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.TransientPersistenceException;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlFailureClassifier;
import st.orm.spi.ExceptionContext;
import st.orm.spi.ExceptionMapper;

/**
 * The dialect's classifier reads a statement's failure before the exception mapper sees it: the mapper receives a
 * transient failure as a {@link TransientPersistenceException} with the database's error as its cause.
 */
class TransientFailureClassificationTest {

    private static final SqlDialect DIALECT = new DefaultSqlDialect();

    private final List<ExceptionContext> contexts = new ArrayList<>();

    private RuntimeException map(Throwable failure, SqlDialect dialect, ExceptionMapper mapper) {
        ExceptionMapper recording = (cause, context) -> {
            contexts.add(context);
            return mapper.map(cause, context);
        };
        return ExceptionHelper.getExceptionTransformer(null, dialect, recording,
                new JdbcTransactionTemplateProviderImpl(), IntegrityViolations.of(dialect)).apply(failure);
    }

    private RuntimeException map(Throwable failure, ExceptionMapper mapper) {
        return map(failure, DIALECT, mapper);
    }

    private RuntimeException map(Throwable failure) {
        return map(failure, ExceptionMapper.defaultMapper());
    }

    @Test
    @Timeout(10)
    void aChainOfNextExceptionsThatLoopsEnds() {
        var first = new SQLException("first", "42000");
        var second = new SQLException("second", "42000");
        first.setNextException(second);
        second.setNextException(first);
        assertFalse(map(first) instanceof TransientPersistenceException);
    }

    @Test
    void aSerializationFailureIsTransient() {
        var serializationFailure = new SQLException("could not serialize", "40001");
        var mapped = assertInstanceOf(TransientPersistenceException.class, map(serializationFailure));
        assertSame(serializationFailure, mapped.getCause());
    }

    @Test
    void aCustomMapperReceivesATransientFailureByItsTypeAndCanOverride() {
        var serializationFailure = new SQLException("could not serialize", "40001");
        var overridden = new IllegalStateException("mine");
        var mapped = map(serializationFailure, (cause, context) -> {
            assertInstanceOf(TransientPersistenceException.class, cause);
            assertSame(serializationFailure, cause.getCause());
            return overridden;
        });
        assertSame(overridden, mapped);
    }

    @Test
    void anErrorTheDialectDoesNotRecogniseIsNotTransient() {
        var violation = new SQLException("unique violation", "23505");
        var mapped = map(violation);
        assertFalse(mapped instanceof TransientPersistenceException);
        assertSame(violation, mapped.getCause());
    }

    @Test
    void theStatementCompletionUnknownStateIsNotTransient() {
        assertFalse(map(new SQLException("integrity constraint violation", "40002"))
                instanceof TransientPersistenceException);
        assertFalse(map(new SQLException("statement completion unknown", "40003"))
                instanceof TransientPersistenceException);
    }

    @Test
    void aWrappedErrorIsClassifiedThroughItsCauses() {
        var wrapper = new PersistenceException(new SQLException("could not serialize", "40001"));
        assertInstanceOf(TransientPersistenceException.class, map(wrapper));
    }

    @Test
    void aBatchFailureIsClassifiedThroughItsNextExceptions() {
        var batch = new BatchUpdateException("batch entry 1 failed", "42000", 0, new int[0]);
        batch.setNextException(new SQLException("could not serialize", "40001"));
        assertInstanceOf(TransientPersistenceException.class, map(batch));
    }

    @Test
    void aLostConnectionIsNeverTransient() {
        var lost = new SQLException("connection reset", "08006");
        lost.setNextException(new SQLException("could not serialize", "40001"));
        assertFalse(map(lost) instanceof TransientPersistenceException);
        var recoverable = new SQLRecoverableException("closed", "40001");
        assertFalse(map(recoverable) instanceof TransientPersistenceException);
        var nonTransientConnection = new SQLNonTransientConnectionException("refused", "40001");
        assertFalse(map(nonTransientConnection) instanceof TransientPersistenceException);
        var transientConnection = new SQLTransientConnectionException("timed out", "40001");
        assertFalse(map(transientConnection) instanceof TransientPersistenceException);
    }

    @Test
    void aClassificationThatFailsLeavesTheFailureNotTransientAndTravelsWithIt() {
        var classificationFailure = new IllegalStateException("classification failed");
        var failingDialect = new DefaultSqlDialect() {
            @Override
            public SqlFailureClassifier sqlFailureClassifier() {
                return new SqlFailureClassifier() {
                    @Override
                    public boolean isTransient(SQLException exception) {
                        throw classificationFailure;
                    }
                };
            }
        };
        var serializationFailure = new SQLException("could not serialize", "40001");
        var mapped = map(serializationFailure, failingDialect, ExceptionMapper.defaultMapper());
        assertFalse(mapped instanceof TransientPersistenceException);
        assertSame(serializationFailure, mapped.getCause());
        assertTrue(List.of(serializationFailure.getSuppressed()).contains(classificationFailure));
    }

    @Test
    void aCauseChainThatLoopsIsWalkedOnce() {
        var first = new PersistenceException("first");
        var second = new PersistenceException("second", first);
        first.initCause(second);
        assertFalse(map(first) instanceof TransientPersistenceException);
    }

    @Test
    void anOptimisticLockConflictIsTransientAndPassesThrough() {
        var conflict = new OptimisticLockException("stale");
        assertSame(conflict, map(conflict));
        assertEquals(1, contexts.size());
    }
}
