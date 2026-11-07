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
import java.sql.SQLRecoverableException;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.TransientPersistenceException;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl;
import st.orm.core.template.SqlDialect;
import st.orm.spi.ExceptionContext;
import st.orm.spi.ExceptionMapper;

/**
 * The dialect classifies a statement's failure before the exception mapper sees it: the mapper receives the failure
 * as reported, with {@link ExceptionContext#transientFailure()} set, and the default mapper throws
 * {@link TransientPersistenceException} for it.
 */
class TransientFailureClassificationTest {

    private static final SqlDialect DIALECT = new DefaultSqlDialect();

    private final List<ExceptionContext> contexts = new ArrayList<>();

    private RuntimeException map(Throwable failure, ExceptionMapper mapper) {
        ExceptionMapper recording = (cause, context) -> {
            contexts.add(context);
            return mapper.map(cause, context);
        };
        return ExceptionHelper.getExceptionTransformer(null, DIALECT, recording,
                new JdbcTransactionTemplateProviderImpl()).apply(failure);
    }

    private RuntimeException map(Throwable failure) {
        return map(failure, ExceptionMapper.defaultMapper());
    }

    @Test
    void aSerializationFailureIsTransient() {
        var serializationFailure = new SQLException("could not serialize", "40001");
        var mapped = assertInstanceOf(TransientPersistenceException.class, map(serializationFailure));
        assertSame(serializationFailure, mapped.getCause());
        assertTrue(contexts.getFirst().transientFailure());
    }

    @Test
    void aCustomMapperReceivesTheFailureAsReportedAndCanOverride() {
        var serializationFailure = new SQLException("could not serialize", "40001");
        var overridden = new IllegalStateException("mine");
        var mapped = map(serializationFailure, (cause, context) -> {
            assertSame(serializationFailure, cause);
            assertTrue(context.transientFailure());
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
        assertFalse(contexts.getFirst().transientFailure());
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
    }

    @Test
    void aChainThatLoopsIsWalkedOnce() {
        var first = new SQLException("first", "42000");
        var second = new SQLException("second", "42000");
        first.setNextException(second);
        second.setNextException(first);
        assertFalse(map(first) instanceof TransientPersistenceException);
    }

    @Test
    void anOptimisticLockConflictIsTransientAndPassesThrough() {
        var conflict = new OptimisticLockException("stale");
        assertSame(conflict, map(conflict));
        assertTrue(contexts.getFirst().transientFailure());
        assertEquals(1, contexts.size());
    }
}
