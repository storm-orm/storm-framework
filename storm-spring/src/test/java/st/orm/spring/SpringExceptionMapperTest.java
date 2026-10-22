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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.spi.ExceptionContext;
import st.orm.spring.model.Owner;
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
    void failuresWithoutSqlExceptionPassThroughUnchanged() {
        SpringExceptionMapper mapper = new SpringExceptionMapper(dataSource);
        PersistenceException storm = new PersistenceException("model constraint violated");
        assertSame(storm, mapper.map(storm, emptyContext()));
        assertInstanceOf(PersistenceException.class, mapper.map(new IllegalStateException("no sql"), emptyContext()));
    }

    @Test
    void stormDetectedFailuresTranslateAsSpringsJpaIntegrationTranslatesTheirCounterparts() {
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
        java.sql.SQLException sqlException = new java.sql.SQLException("Unique index violation", "23505", 23505);
        PersistenceException wrapper = new PersistenceException(sqlException);
        RuntimeException diagnostics = new RuntimeException("SQL:\nINSERT INTO pet_type ...");
        wrapper.addSuppressed(diagnostics);
        RuntimeException translated = mapper.map(wrapper, emptyContext());
        assertInstanceOf(DataAccessException.class, translated);
        org.assertj.core.api.Assertions.assertThat(translated.getSuppressed()).contains(diagnostics);
    }

    @Test
    void transientSqlFailuresTranslateToTransientDataAccessException() {
        // Deadlocks and lock timeouts must land in the transient branch of the hierarchy: that is what
        // retry setups key on, and the reason the translation exists.
        SpringExceptionMapper mapper = new SpringExceptionMapper();
        RuntimeException translated = mapper.map(
                new java.sql.SQLTransactionRollbackException("Deadlock found when trying to get lock", "40001", 1213),
                emptyContext());
        assertInstanceOf(org.springframework.dao.TransientDataAccessException.class, translated);
    }

    private static ExceptionContext emptyContext() {
        return new ExceptionContext() {
            @Override
            public st.orm.spi.SqlOperation operation() {
                return st.orm.spi.SqlOperation.UNDEFINED;
            }

            @Override
            public java.util.Optional<String> statement() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<Class<? extends st.orm.Data>> dataType() {
                return java.util.Optional.empty();
            }

            @Override
            public java.util.Optional<String> transactionDescription() {
                return java.util.Optional.empty();
            }
        };
    }
}
