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

import java.sql.SQLException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLRecoverableException;
import java.sql.SQLTransientConnectionException;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.PersistenceException;
import st.orm.SqlTemplateException;
import st.orm.TransientPersistenceException;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.TransactionScope;
import st.orm.core.spi.TransactionTemplateProvider;
import st.orm.core.template.QueryTemplate;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlDialect;
import st.orm.spi.ExceptionContext;
import st.orm.spi.ExceptionMapper;
import st.orm.spi.SqlOperation;

/**
 * Helper class for augmenting exceptions with SQL statements and mapping them via the template's exception mapper.
 *
 * @since 1.3
 */
public final class ExceptionHelper {

    private ExceptionHelper() {}

    /**
     * Returns a transformer that enriches failures with SQL diagnostics and maps them to the exception thrown to the
     * caller via the given exception mapper.
     *
     * <p>The SQL statement and transaction description are attached to the original failure as a suppressed
     * exception, so every mapper receives full diagnostics; the mapper only decides the thrown type. The dialect
     * classifies the failure before the mapper sees it ({@link ExceptionContext#transientFailure()}), and a failure
     * classified transient is recorded on the active transaction scope, so a block that reruns on a transient
     * failure recognises it whatever the mapper throws. If
     * the mapper itself fails or returns {@code null}, the failure is wrapped in a {@link PersistenceException} with
     * the mapper failure suppressed; diagnostic mapping must never mask the original error.</p>
     *
     * @param sql the SQL statement to attach, or {@code null} when no statement is associated.
     * @param dialect the dialect the statement was generated with, which classifies the failure.
     * @param exceptionMapper the exception mapper configured on the template.
     * @param transactionTemplateProvider the transaction template provider of the template, used to describe the
     *                                    active transaction.
     * @return the exception transformer.
     */
    public static Function<Throwable, RuntimeException> getExceptionTransformer(
            @Nullable Sql sql,
            SqlDialect dialect,
            ExceptionMapper exceptionMapper,
            TransactionTemplateProvider transactionTemplateProvider) {
        return getExceptionTransformer(
                sql != null ? sql.operation() : SqlOperation.UNDEFINED,
                sql != null ? sql.affectedType().orElse(null) : null,
                sql,
                dialect,
                exceptionMapper,
                transactionTemplateProvider);
    }

    /**
     * Returns a transformer that maps the failures Storm's own logic detects after a statement ran, such as an
     * optimistic lock conflict or a single result that is missing or not unique, to the exception thrown to the
     * caller via the template's exception mapper. The statement itself is not known at the point of detection, so
     * the mapper receives the operation and the data type as context, without statement text.
     *
     * @param template the template whose exception mapper and transaction template provider apply.
     * @param operation the operation the failure belongs to.
     * @param dataType the data type the failure belongs to, or {@code null} when it has none.
     * @return the exception transformer.
     * @since 1.16
     */
    public static Function<Throwable, RuntimeException> getExceptionTransformer(
            QueryTemplate template,
            SqlOperation operation,
            @Nullable Class<? extends Data> dataType) {
        return getExceptionTransformer(operation, dataType, null, template.dialect(), template.exceptionMapper(),
                template.transactionTemplateProvider());
    }

    /**
     * Returns a transformer that maps a failure of a transaction's commit to the exception thrown to the caller via
     * the given exception mapper, as a statement's failure is mapped: the dialect classifies the failure, under the
     * same guard against a lost connection, and a failure classified transient is recorded on the active transaction
     * scope. A database that detects a serialization conflict only when the transaction commits reports it here.
     *
     * <p>The mapper receives no statement and no transaction description: the transaction has completed by the time
     * its commit fails.</p>
     *
     * @param dialect the dialect of the template that opened the transaction, which classifies the failure.
     * @param exceptionMapper the exception mapper configured on that template.
     * @return the exception transformer.
     * @since 1.16
     */
    public static Function<Throwable, RuntimeException> getCommitExceptionTransformer(
            SqlDialect dialect,
            ExceptionMapper exceptionMapper) {
        return getExceptionTransformer(SqlOperation.UNDEFINED, null, null, dialect, exceptionMapper, null);
    }

    private static Function<Throwable, RuntimeException> getExceptionTransformer(
            SqlOperation operation,
            @Nullable Class<? extends Data> dataType,
            @Nullable Sql sql,
            SqlDialect dialect,
            ExceptionMapper exceptionMapper,
            @Nullable TransactionTemplateProvider transactionTemplateProvider) {
        return e -> {
            String transactionDescription = transactionTemplateProvider != null
                    ? currentTransactionDescription(transactionTemplateProvider)
                    : null;
            if (sql != null) {
                e.addSuppressed(new SqlTemplateException(buildSqlDetail(sql, transactionDescription)));
            }
            var context = new ExceptionContextImpl(
                    operation,
                    sql != null ? sql.statement() : null,
                    dataType,
                    transactionDescription,
                    e instanceof TransientPersistenceException || isTransientFailure(e, dialect));
            RuntimeException thrown;
            try {
                var mapped = exceptionMapper.map(e, context);
                thrown = mapped != null ? mapped : new PersistenceException(e);
            } catch (Throwable mapperFailure) {
                var fallback = e instanceof PersistenceException persistenceException
                        ? persistenceException
                        : new PersistenceException(e);
                fallback.addSuppressed(mapperFailure);
                thrown = fallback;
            }
            if (context.transientFailure()) {
                recordTransientFailure(e, thrown);
            }
            return thrown;
        };
    }

    /**
     * Records the transient failure and the exception it is thrown as on the transaction scope active on this
     * thread, so the block that reruns on a transient failure recognises it whatever type the mapper chose.
     */
    private static void recordTransientFailure(Throwable failure, RuntimeException thrown) {
        var scope = TransactionScope.current();
        if (scope != null) {
            scope.recordTransientFailure(failure);
            scope.recordTransientFailure(thrown);
        }
    }

    /**
     * Returns whether the dialect recognises an error in the failure's chain as a deadlock or a serialization
     * failure. The chain is walked through the causes and, for each {@code SQLException}, the next exceptions a
     * driver chains to it. A chain that reports a lost connection is never transient, as the outcome of the
     * transaction is unknown.
     */
    private static boolean isTransientFailure(Throwable failure, SqlDialect dialect) {
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        boolean recognised = false;
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof SQLException sqlException) {
                for (SQLException next = sqlException; next != null; next = next.getNextException()) {
                    if (next != sqlException && !visited.add(next)) {
                        break;
                    }
                    if (isConnectionFailure(next)) {
                        return false;
                    }
                    try {
                        recognised |= dialect.isTransientFailure(next);
                    } catch (RuntimeException classificationFailure) {
                        // Never let classification mask the original exception; it travels along with it.
                        failure.addSuppressed(classificationFailure);
                        return false;
                    }
                }
            }
        }
        return recognised;
    }

    private static boolean isConnectionFailure(SQLException exception) {
        if (exception instanceof SQLNonTransientConnectionException
                || exception instanceof SQLTransientConnectionException
                || exception instanceof SQLRecoverableException) {
            return true;
        }
        String sqlState = exception.getSQLState();
        return sqlState != null && sqlState.startsWith("08");
    }

    private static String buildSqlDetail(Sql sql, @Nullable String transactionDescription) {
        String detail = String.format("SQL:%n%s", sql.statement());
        if (transactionDescription != null) {
            detail = detail + String.format("%nTransaction: %s", transactionDescription);
        }
        return detail;
    }

    /**
     * Returns a description of the current transaction's characteristics (such as isolation level and timeout), or
     * {@code null} when no transaction is active or the description cannot be determined.
     */
    private static @Nullable String currentTransactionDescription(
            TransactionTemplateProvider transactionTemplateProvider) {
        try {
            return Optional.ofNullable(TransactionScope.peekContext(transactionTemplateProvider))
                    .flatMap(TransactionContext::describe)
                    .orElse(null);
        } catch (Throwable ignore) {
            // Never let diagnostic enrichment mask the original exception.
            return null;
        }
    }

    private record ExceptionContextImpl(SqlOperation operation,
                                        @Nullable String statementText,
                                        @Nullable Class<? extends Data> affectedType,
                                        @Nullable String description,
                                        boolean transientFailure) implements ExceptionContext {
        @Override
        public Optional<String> statement() {
            return Optional.ofNullable(statementText);
        }

        @Override
        public Optional<Class<? extends Data>> dataType() {
            return Optional.ofNullable(affectedType);
        }

        @Override
        public Optional<String> transactionDescription() {
            return Optional.ofNullable(description);
        }
    }
}
