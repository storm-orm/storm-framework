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

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.sql.SQLTransactionRollbackException;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.IncorrectUpdateSemanticsDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import st.orm.Data;
import st.orm.IllegalTransactionStateException;
import st.orm.IntegrityViolation;
import st.orm.IntegrityViolationException;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.ReadOnlyTransactionException;
import st.orm.TransactionCallbackException;
import st.orm.TransactionTimedOutException;
import st.orm.TransientPersistenceException;
import st.orm.UnexpectedRollbackException;
import st.orm.UnexpectedRowCountException;
import st.orm.spi.ExceptionContext;
import st.orm.spi.SqlOperation;
import st.orm.spring.model.Owner;
import st.orm.spring.model.Pet;
import st.orm.spring.model.PetType;
import st.orm.template.ORMTemplate;

/**
 * Verifies that {@link SpringExceptionMapper} translates SQL failures to Spring's
 * {@link DataAccessException} hierarchy while leaving Storm's own exceptions untouched.
 */
class SpringExceptionMapperTest {

    private DataSource dataSource;

    @BeforeEach
    void setUp() {
        dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:exceptionmappertest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        new ResourceDatabasePopulator(new ClassPathResource("data.sql")).execute(dataSource);
    }

    @Test
    void duplicateKeyTranslatesToDataAccessException() {
        ORMTemplate orm = ORMTemplate.builder(dataSource)
                .exceptionMapper(new SpringExceptionMapper(dataSource))
                .build();
        // Pet type 1 exists in the test data; inserting it again violates the primary key.
        assertThrows(DuplicateKeyException.class, () ->
                orm.entity(PetType.class).insert(new PetType(1, "duplicate")));
    }

    @Test
    void duplicateKeyTranslatesThroughSpringComposedTemplate() {
        // The canonical Spring composition applies the mapper automatically.
        ORMTemplate orm = SpringOrmTemplate.of(dataSource, () -> List.of(new DataSourceTransactionManager(dataSource)));
        assertThrows(DuplicateKeyException.class, () ->
                orm.entity(PetType.class).insert(new PetType(1, "duplicate")));
    }

    @Test
    void subclassTranslationWorksWithoutDataSource() {
        ORMTemplate orm = ORMTemplate.builder(dataSource)
                .exceptionMapper(new SpringExceptionMapper())
                .build();
        assertThrows(DataAccessException.class, () ->
                orm.entity(PetType.class).insert(new PetType(1, "duplicate")));
    }

    @Test
    void withoutTheMapperStormThrowsPersistenceException() {
        ORMTemplate orm = ORMTemplate.builder(dataSource).build();
        assertThrows(PersistenceException.class, () ->
                orm.entity(PetType.class).insert(new PetType(1, "duplicate")));
    }

    @Test
    void failuresWithoutSpringCounterpartTranslateToStormSystemException() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        PersistenceException storm = new PersistenceException("model constraint violated");
        var translatedStorm = assertInstanceOf(StormSystemException.class, mapper.map(storm, emptyContext()));
        assertSame(storm, translatedStorm.getCause());
        var other = new IllegalStateException("no sql");
        var translatedOther = assertInstanceOf(StormSystemException.class, mapper.map(other, emptyContext()));
        assertSame(other, translatedOther.getCause());
    }

    @Test
    void aUniqueViolationTranslatesToDuplicateKeyExceptionWithTheViolationAsCause() {
        ORMTemplate orm = ORMTemplate.builder(dataSource)
                .exceptionMapper(new SpringExceptionMapper(dataSource))
                .build();
        var thrown = assertThrows(DuplicateKeyException.class, () ->
                orm.entity(PetType.class).insert(new PetType(1, "duplicate")));
        var violation = assertInstanceOf(IntegrityViolationException.class, thrown.getCause());
        var unique = assertInstanceOf(IntegrityViolation.Unique.class, violation.violations().getFirst());
        assertEquals(PetType.class, unique.type());
    }

    @Test
    void aViolationTheDialectDoesNotRecogniseStillTranslatesThroughSpring() {
        // The test runs on the default dialect, which classifies the standard SQL states alone; this database
        // reports a missing parent row under a state of its own, so the failure reaches Spring's translator as
        // the driver's exception.
        ORMTemplate orm = ORMTemplate.builder(dataSource)
                .exceptionMapper(new SpringExceptionMapper(dataSource))
                .build();
        var thrown = assertThrows(DataIntegrityViolationException.class, () ->
                orm.entity(Pet.class).insert(new Pet(0, "Rex", LocalDate.now(), new PetType(999, "missing"), null)));
        assertFalse(thrown instanceof DuplicateKeyException);
        assertInstanceOf(SQLException.class, thrown.getCause());
    }

    @Test
    void aViolationValidateOrThrowFindsTranslatesToDataIntegrityViolationException() {
        ORMTemplate orm = ORMTemplate.builder(dataSource)
                .exceptionMapper(new SpringExceptionMapper(dataSource))
                .build();
        var thrown = assertThrows(DataIntegrityViolationException.class, () ->
                orm.entity(PetType.class).validateOrThrow(new PetType(2, null)));
        var violation = assertInstanceOf(IntegrityViolationException.class, thrown.getCause());
        assertInstanceOf(IntegrityViolation.NotNull.class, violation.violations().getFirst());
    }

    @Test
    void aNullInANonNullableFieldTranslatesToDataIntegrityViolationException() {
        ORMTemplate orm = ORMTemplate.builder(dataSource)
                .exceptionMapper(new SpringExceptionMapper(dataSource))
                .build();
        var thrown = assertThrows(DataIntegrityViolationException.class, () ->
                orm.entity(PetType.class).insert(new PetType(2, null)));
        var violation = assertInstanceOf(IntegrityViolationException.class, thrown.getCause());
        assertInstanceOf(IntegrityViolation.NotNull.class, violation.violations().getFirst());
    }

    @Test
    @Timeout(10)
    void aFailureWhoseCausesFormACycleTranslatesWithoutHanging() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        var first = new IllegalStateException("first");
        var second = new IllegalStateException("second", first);
        first.initCause(second);
        var translated = assertInstanceOf(StormSystemException.class, mapper.map(first, emptyContext()));
        assertSame(first, translated.getCause());
    }

    @Test
    void transactionApiFailuresPassThroughUnchanged() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        List<RuntimeException> failures = List.of(
                new TransactionTimedOutException("late"),
                new IllegalTransactionStateException("refused"),
                new UnexpectedRollbackException("rolled back"),
                new ReadOnlyTransactionException("read-only"),
                new TransactionCallbackException("callback", new IllegalStateException("callback failed"), true));
        for (RuntimeException failure : failures) {
            assertSame(failure, mapper.map(failure, emptyContext()));
        }
    }

    @Test
    void stormDetectedFailuresTranslateToSpringsDataAccessTypes() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        var conflict = new OptimisticLockException("stale");
        var translatedConflict = assertInstanceOf(OptimisticLockingFailureException.class,
                mapper.map(conflict, emptyContext()));
        assertSame(conflict, translatedConflict.getCause());
        var missing = new NoResultException("none");
        var translatedMissing = assertInstanceOf(EmptyResultDataAccessException.class,
                mapper.map(missing, emptyContext()));
        assertSame(missing, translatedMissing.getCause());
        assertEquals(1, translatedMissing.getExpectedSize());
        var ambiguous = new NonUniqueResultException("two");
        var translatedAmbiguous = assertInstanceOf(IncorrectResultSizeDataAccessException.class,
                mapper.map(ambiguous, emptyContext()));
        assertSame(ambiguous, translatedAmbiguous.getCause());
        assertEquals(1, translatedAmbiguous.getExpectedSize());
        var rowCount = new UnexpectedRowCountException("none affected", 1, 0);
        var translatedRowCount = assertInstanceOf(IncorrectUpdateSemanticsDataAccessException.class,
                mapper.map(rowCount, emptyContext()));
        assertSame(rowCount, translatedRowCount.getCause());
    }

    @Test
    void aWriteToAMissingRowThroughTheComposedTemplateFailsWithSpringsIncorrectUpdateSemantics() {
        ORMTemplate orm = SpringOrmTemplate.of(dataSource, () -> List.of(new DataSourceTransactionManager(dataSource)));
        var petTypes = orm.entity(PetType.class);
        var missing = new PetType(-1, "missing");
        var updateFailure = assertThrows(IncorrectUpdateSemanticsDataAccessException.class,
                () -> petTypes.update(missing));
        var updateRowCount = assertInstanceOf(UnexpectedRowCountException.class, updateFailure.getCause());
        assertEquals(1, updateRowCount.getExpectedRowCount());
        assertEquals(0, updateRowCount.getActualRowCount());
        var removeFailure = assertThrows(IncorrectUpdateSemanticsDataAccessException.class,
                () -> petTypes.remove(missing));
        assertInstanceOf(UnexpectedRowCountException.class, removeFailure.getCause());
        var batchFailure = assertThrows(IncorrectUpdateSemanticsDataAccessException.class,
                () -> petTypes.update(List.of(new PetType(1, "renamed"), missing)));
        var batchRowCount = assertInstanceOf(UnexpectedRowCountException.class, batchFailure.getCause());
        assertEquals(2, batchRowCount.getExpectedRowCount());
        assertEquals(1, batchRowCount.getActualRowCount());
    }

    @Test
    void aStaleUpdateThroughTheComposedTemplateFailsWithSpringsOptimisticLockingFailure() {
        ORMTemplate orm = SpringOrmTemplate.of(dataSource, () -> List.of(new DataSourceTransactionManager(dataSource)));
        var owners = orm.entity(Owner.class);
        Owner owner = owners.getById(1);
        Owner renamed = new Owner(owner.id(), "Renamed", owner.lastName(), owner.address(), owner.telephone(),
                owner.version());
        owners.update(renamed);
        // The same instance carries the version the first update moved past.
        var failure = assertThrows(OptimisticLockingFailureException.class, () -> owners.update(renamed));
        var conflict = assertInstanceOf(OptimisticLockException.class, failure.getCause());
        assertSame(renamed, conflict.getEntity());
        assertThrows(EmptyResultDataAccessException.class, () -> owners.getById(-1));
    }

    @Test
    void suppressedDiagnosticsSurviveTranslationOfWrappedFailures() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        // The framework attaches SQL diagnostics as a suppressed exception on the reported failure, which
        // may wrap the SQLException. Translation drops the wrapper but must keep its suppressed exceptions.
        SQLException sqlException = new SQLException("Unique index violation", "23505", 23505);
        PersistenceException wrapper = new PersistenceException(sqlException);
        RuntimeException diagnostics = new RuntimeException("SQL:\nINSERT INTO pet_type ...");
        wrapper.addSuppressed(diagnostics);
        RuntimeException translated = mapper.map(wrapper, emptyContext());
        assertInstanceOf(DataAccessException.class, translated);
        assertThat(translated.getSuppressed()).contains(diagnostics);
    }

    @Test
    void transientSqlFailuresTranslateToTransientDataAccessException() {
        // Deadlocks and lock timeouts must land in the transient branch of the hierarchy: that is what
        // retry setups key on, and the reason the translation exists.
        SpringExceptionMapper mapper = new SpringExceptionMapper();
        RuntimeException translated = mapper.map(
                new SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213),
                emptyContext());
        assertInstanceOf(TransientDataAccessException.class, translated);
    }

    @Test
    void optimisticLockConflictStaysAnOptimisticLockingFailureAsATransientFailure() {
        // The conflict is a TransientPersistenceException; it keeps Spring's optimistic locking type rather than
        // the generic concurrency type of the other transient failures.
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        var conflict = new OptimisticLockException("stale");
        var translated = assertInstanceOf(OptimisticLockingFailureException.class,
                mapper.map(conflict, context(true)));
        assertSame(conflict, translated.getCause());
    }

    @Test
    void transientStormFailureTranslatesToConcurrencyFailure() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        var failure = new TransientPersistenceException("conflict");
        var translated = assertInstanceOf(ConcurrencyFailureException.class, mapper.map(failure, context(true)));
        assertSame(failure, translated.getCause());
    }

    @Test
    void sqlFailureTheDialectClassifiesAsTransientTranslatesToATransientType() {
        // A code the translator knows nothing of, as a database reports for a snapshot update conflict, still lands
        // in the transient branch when the dialect classified it.
        SpringExceptionMapper mapper = new SpringExceptionMapper();
        var conflict = new SQLException("Snapshot isolation transaction aborted due to update conflict.", "S0002", 3960);
        assertInstanceOf(DataAccessException.class, mapper.map(conflict, emptyContext()));
        var translated = assertInstanceOf(ConcurrencyFailureException.class, mapper.map(conflict, context(true)));
        assertSame(conflict, translated.getCause());
    }

    @Test
    void sqlFailureTheTranslatorAlreadyTranslatesAsTransientKeepsItsType() {
        SpringExceptionMapper mapper = new SpringExceptionMapper();
        var deadlock = new SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213);
        var translated = assertInstanceOf(TransientDataAccessException.class, mapper.map(deadlock, context(true)));
        assertEquals(mapper.map(deadlock, emptyContext()).getClass(), translated.getClass());
    }

    private static ExceptionContext emptyContext() {
        return context(false);
    }

    private static ExceptionContext context(boolean transientFailure) {
        return new ExceptionContext() {
            @Override
            public SqlOperation operation() {
                return SqlOperation.UNDEFINED;
            }

            @Override
            public Optional<String> statement() {
                return Optional.empty();
            }

            @Override
            public Optional<Class<? extends Data>> dataType() {
                return Optional.empty();
            }

            @Override
            public Optional<String> transactionDescription() {
                return Optional.empty();
            }

            @Override
            public boolean transientFailure() {
                return transientFailure;
            }
        };
    }
}
