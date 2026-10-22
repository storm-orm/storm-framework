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

import java.sql.SQLException;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.support.SQLErrorCodeSQLExceptionTranslator;
import org.springframework.jdbc.support.SQLExceptionSubclassTranslator;
import org.springframework.jdbc.support.SQLExceptionTranslator;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.spi.ExceptionContext;
import st.orm.spi.ExceptionMapper;

/**
 * Exception mapper that translates SQL failures to Spring's {@link DataAccessException} hierarchy.
 *
 * <p>When the failure carries a {@link SQLException}, it is translated with Spring's
 * {@link SQLExceptionTranslator}: vendor error codes when the data source is known, falling back to
 * {@code SQLException} subclass and SQL state translation. This gives Storm repositories the same exception
 * semantics as Spring's other data access integrations: deadlocks and lock timeouts surface as
 * {@code TransientDataAccessException} subtypes (enabling retry setups), constraint violations as
 * {@code DataIntegrityViolationException} subtypes, and so on.</p>
 *
 * <p>The failures Storm's own logic detects translate as Spring's JPA integration translates their
 * counterparts: an {@link OptimisticLockException} to {@link OptimisticLockingFailureException}, which keeps the
 * Storm exception, and the entity it carries, as its cause; a {@link NoResultException} to
 * {@link EmptyResultDataAccessException}; a {@link NonUniqueResultException} to
 * {@link IncorrectResultSizeDataAccessException}. A retry setup keyed on
 * {@code OptimisticLockingFailureException} therefore sees Storm's optimistic lock conflicts. Any other
 * {@link PersistenceException} passes through unchanged.</p>
 *
 * <p>The Spring Boot starters apply this mapper automatically to the template they auto-configure; disable with
 * {@code storm.exception-translation.enabled=false} or by defining your own {@code ExceptionMapper} bean.</p>
 *
 * @since 1.13
 */
public class SpringExceptionMapper implements ExceptionMapper {

    private final SQLExceptionTranslator translator;

    /**
     * Creates a mapper that translates on {@code SQLException} subclass and SQL state alone.
     */
    public SpringExceptionMapper() {
        this.translator = new SQLExceptionSubclassTranslator();
    }

    /**
     * Creates a mapper that also translates vendor error codes for the database product of the given data source.
     *
     * @param dataSource the data source used to determine the database product for error-code translation.
     */
    public SpringExceptionMapper(DataSource dataSource) {
        this.translator = new SQLErrorCodeSQLExceptionTranslator(dataSource);
    }

    @Override
    public RuntimeException map(Throwable cause, ExceptionContext context) {
        SQLException sqlException = findSqlException(cause);
        if (sqlException == null) {
            return switch (cause) {
                case OptimisticLockException conflict ->
                        new OptimisticLockingFailureException(conflict.getMessage(), conflict);
                case NoResultException missing ->
                        new EmptyResultDataAccessException(missing.getMessage(), 1, missing);
                case NonUniqueResultException ambiguous ->
                        new IncorrectResultSizeDataAccessException(ambiguous.getMessage(), 1, ambiguous);
                case PersistenceException persistenceException -> persistenceException;
                default -> new PersistenceException(cause);
            };
        }
        String task = "Storm " + context.operation()
                + context.dataType().map(type -> " for " + type.getSimpleName()).orElse("");
        String sql = context.statement().orElse(null);
        DataAccessException translated = translator.translate(task, sql, sqlException);
        if (translated == null) {
            translated = new UncategorizedSQLException(task, sql, sqlException);
        }
        // The translated exception keeps the SQLException as its cause, but drops any wrapper between the
        // reported failure and the SQLException. The framework attaches SQL diagnostics as suppressed
        // exceptions to the reported failure, so carry the suppressed exceptions of the dropped wrappers over.
        for (Throwable wrapper = cause; wrapper != null && wrapper != sqlException; wrapper = wrapper.getCause()) {
            for (Throwable suppressed : wrapper.getSuppressed()) {
                translated.addSuppressed(suppressed);
            }
        }
        return translated;
    }

    private static @Nullable SQLException findSqlException(Throwable cause) {
        for (Throwable current = cause; current != null; current = current.getCause()) {
            if (current instanceof SQLException sqlException) {
                return sqlException;
            }
        }
        return null;
    }
}
