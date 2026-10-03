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
package st.orm.spring.boot.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import st.orm.EntityCallback;
import st.orm.spring.RepositoryBeanFactoryPostProcessor;
import st.orm.spring.boot.StormExceptionTranslationAutoConfiguration;
import st.orm.spring.boot.StormObservationAutoConfiguration;
import st.orm.spring.boot.StormProperties;
import st.orm.spring.boot.StormTracingAutoConfiguration;
import st.orm.spring.boot.StormValidationAutoConfiguration;
import st.orm.template.ORMTemplate;

@ExtendWith(OutputCaptureExtension.class)
class StormAutoConfigurationTest {

    /**
     * Logs the schema validation outcome at info for the duration of the returned scope. The build logs warnings and
     * errors only, and the schema validation tests read the success line from the output.
     */
    private static AutoCloseable logValidationOutcome() {
        var logger = (Logger) LoggerFactory.getLogger(StormValidationAutoConfiguration.class);
        var level = logger.getLevel();
        logger.setLevel(Level.INFO);
        return () -> logger.setLevel(level);
    }

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    DataSourceAutoConfiguration.class,
                    StormAutoConfiguration.class,
                    StormRepositoryAutoConfiguration.class,
                    StormValidationAutoConfiguration.class,
                    StormExceptionTranslationAutoConfiguration.class,
                    StormObservationAutoConfiguration.class,
                    StormTracingAutoConfiguration.class
            ));

    @Test
    void ormTemplateBeanCreatedWhenDataSourcePresent() {
        // StormAutoConfiguration is conditional on a DataSource bean. When a DataSource is available,
        // it should auto-configure exactly one ORMTemplate bean.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                });
    }

    @Test
    void ormTemplateBeanNotCreatedWithoutDataSource() {
        // Without a DataSource in the context, StormAutoConfiguration should not create
        // an ORMTemplate bean (the @ConditionalOnBean(DataSource.class) condition should fail).
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StormAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).doesNotHaveBean(ORMTemplate.class);
                });
    }

    @Test
    void userDefinedOrmTemplateTakesPrecedence() {
        // StormAutoConfiguration uses @ConditionalOnMissingBean(ORMTemplate.class), so a user-defined
        // ORMTemplate bean should take precedence over the auto-configured one.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(CustomOrmTemplateConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    assertThat(context).getBean(ORMTemplate.class)
                            .isSameAs(context.getBean("customOrmTemplate"));
                });
    }

    @Test
    void repositoryBeanFactoryPostProcessorAutoConfigured() {
        // StormRepositoryAutoConfiguration should register an AutoConfiguredRepositoryBeanFactoryPostProcessor
        // that scans for repository interfaces and registers them as Spring beans.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(RepositoryBeanFactoryPostProcessor.class);
                    assertThat(context).getBean(RepositoryBeanFactoryPostProcessor.class)
                            .isInstanceOf(AutoConfiguredRepositoryBeanFactoryPostProcessor.class);
                });
    }

    @Test
    void entityCallbackBeanAutoDetected() {
        // When a user defines an EntityCallback bean, StormAutoConfiguration should detect it
        // and wire it into the ORMTemplate for entity lifecycle callbacks.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(EntityCallbackConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    assertThat(context).hasSingleBean(EntityCallback.class);
                });
    }

    @Test
    void noEntityCallbackByDefault() {
        // Without user-defined EntityCallback beans, none should be present in the context.
        // The auto-configuration does not register a default EntityCallback.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    assertThat(context).doesNotHaveBean(EntityCallback.class);
                });
    }

    @Test
    void stormPropertiesAppliedToOrmTemplate() {
        // Storm properties under the "storm.*" prefix should be bound to StormProperties and applied
        // to the ORMTemplate. Each property value set here should be reflected in the bound bean.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.ansi-escaping=false",
                        "storm.update.default-mode=ENTITY",
                        "storm.update.dirty-check=INSTANCE",
                        "storm.update.max-shapes=5",
                        "storm.entity-cache.retention=light",
                        "storm.template-cache.size=100",
                        "storm.validation.record-mode=warn"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    assertThat(context).hasSingleBean(StormProperties.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getAnsiEscaping()).isFalse();
                    assertThat(props.getUpdate().getDefaultMode()).isEqualTo("ENTITY");
                    assertThat(props.getUpdate().getDirtyCheck()).isEqualTo("INSTANCE");
                    assertThat(props.getUpdate().getMaxShapes()).isEqualTo(5);
                    assertThat(props.getEntityCache().getRetention()).isEqualTo("light");
                    assertThat(props.getTemplateCache().getSize()).isEqualTo(100);
                    assertThat(props.getValidation().getRecordMode()).isEqualTo("warn");
                });
    }

    @Test
    void userDefinedRepositoryBeanFactoryPostProcessorTakesPrecedence() {
        // StormRepositoryAutoConfiguration uses @ConditionalOnMissingBean, so a user-defined
        // RepositoryBeanFactoryPostProcessor should prevent the auto-configured one from being created.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(CustomRepositoryPostProcessorConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(RepositoryBeanFactoryPostProcessor.class);
                    assertThat(context).doesNotHaveBean(AutoConfiguredRepositoryBeanFactoryPostProcessor.class);
                });
    }

    @Test
    void schemaValidationDefaultsToFailMode(CapturedOutput output) throws Exception {
        // No schema-mode property: validation must run in fail mode by default.
        // With no entities on the test classpath there is nothing to reject, so
        // the success log proves the default dispatched into the fail branch.
        try (var ignored = logValidationOutcome()) {
            contextRunner
                    .withPropertyValues(
                            "spring.datasource.url=jdbc:h2:mem:schemaDefaultTest;DB_CLOSE_DELAY=-1",
                            "spring.datasource.driver-class-name=org.h2.Driver"
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(ORMTemplate.class);
                        assertThat(output).contains("Storm schema validation passed (mode=fail).");
                    });
        }
    }

    @Test
    void schemaValidationWarnModeShouldNotPreventContextStartup() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:schemaWarnTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.validation.schema-mode=warn"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                });
    }

    @Test
    void schemaValidationNoneModeShouldNotPreventContextStartup() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:schemaNoneTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.validation.schema-mode=none"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                });
    }

    @Test
    void strictValidationPropertyShouldBeBoundCorrectly() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:strictTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.validation.strict=true"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getValidation().getStrict()).isTrue();
                });
    }

    @Configuration
    static class EntityCallbackConfig {
        @Bean
        public EntityCallback<?> entityCallback() {
            return new EntityCallback<>() {};
        }
    }

    @Configuration
    static class CustomOrmTemplateConfig {
        @Bean
        public ORMTemplate customOrmTemplate(DataSource dataSource) {
            return ORMTemplate.of(dataSource);
        }
    }

    @Configuration
    static class CustomRepositoryPostProcessorConfig {
        @Bean
        public static RepositoryBeanFactoryPostProcessor repositoryBeanFactoryPostProcessor() {
            return new RepositoryBeanFactoryPostProcessor() {
                @Override
                public String[] getRepositoryBasePackages() {
                    return new String[] { "com.example" };
                }
            };
        }
    }

    @Test
    void schemaValidationFailModeShouldNotPreventStartupWithEmptyDatabase() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:schemaFailTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.validation.schema-mode=fail"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                });
    }

    @Test
    void schemaValidationUnknownModeShouldFailContextStartup() {
        // A typo in the mode must fail startup instead of silently skipping schema validation.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:schemaUnknownTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.validation.schema-mode=fial"
                )
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .isInstanceOf(InvalidConfigurationPropertyValueException.class)
                            .hasMessageContaining("storm.validation.schema-mode")
                            .hasMessageContaining("'fial'")
                            .hasMessageContaining("Valid values are: none, warn, fail.");
                });
    }

    @Test
    void schemaValidationEmptyModeFallsBackToFailDefault(CapturedOutput output) throws Exception {
        // An empty schema-mode is treated as unset and falls back to the fail default.
        try (var ignored = logValidationOutcome()) {
            contextRunner
                    .withPropertyValues(
                            "spring.datasource.url=jdbc:h2:mem:schemaEmptyTest;DB_CLOSE_DELAY=-1",
                            "spring.datasource.driver-class-name=org.h2.Driver",
                            "storm.validation.schema-mode="
                    )
                    .run(context -> {
                        assertThat(context).hasSingleBean(ORMTemplate.class);
                        assertThat(output).contains("Storm schema validation passed (mode=fail).");
                    });
        }
    }

    @Test
    void updatePropertiesWithNullsShouldFallToDefaults() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:updateDefaultsTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getUpdate().getDefaultMode()).isNull();
                    assertThat(props.getUpdate().getDirtyCheck()).isNull();
                    assertThat(props.getUpdate().getMaxShapes()).isNull();
                });
    }

    @Test
    void entityCacheRetentionNullShouldUseDefault() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:cacheRetentionTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getEntityCache().getRetention()).isNull();
                });
    }

    @Test
    void templateCacheSizeNullShouldUseDefault() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:templateCacheTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getTemplateCache().getSize()).isNull();
                });
    }

    @Test
    void ansiEscapingNullShouldUseDefault() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:ansiTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getAnsiEscaping()).isNull();
                });
    }

    @Test
    void validationRecordModeNullShouldUseDefault() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:recordModeTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getValidation().getRecordMode()).isNull();
                });
    }

    @Test
    void validationStrictNullShouldUseDefault() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:strictNullTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                    StormProperties props = context.getBean(StormProperties.class);
                    assertThat(props.getValidation().getStrict()).isNull();
                });
    }
    @Test
    void multipleDataSourcesWithoutPrimaryBackOffCleanly() {
        // With several DataSource beans and no @Primary (one connection pool per domain over the same
        // database), there is no single candidate to build a template from: the ormTemplate and schema
        // validation beans must back off instead of failing the context.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        StormAutoConfiguration.class,
                        StormRepositoryAutoConfiguration.class,
                        StormValidationAutoConfiguration.class
                ))
                .withUserConfiguration(MultiDataSourceConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).getBeans(DataSource.class).hasSize(2);
                    assertThat(context).doesNotHaveBean(ORMTemplate.class);
                });
    }

    @Test
    void multipleDataSourcesWithPrimaryBuildTemplateForPrimary() {
        // Marking one of the pools @Primary restores a single candidate: the auto-configured template
        // binds to the primary DataSource.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        StormAutoConfiguration.class,
                        StormRepositoryAutoConfiguration.class,
                        StormValidationAutoConfiguration.class
                ))
                .withUserConfiguration(MultiDataSourceWithPrimaryConfig.class)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ORMTemplate.class);
                });
    }

    @Configuration
    static class MultiDataSourceConfig {
        static DataSource h2(String name) {
            var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource();
            dataSource.setUrl("jdbc:h2:mem:" + name + ";DB_CLOSE_DELAY=-1");
            dataSource.setDriverClassName("org.h2.Driver");
            return dataSource;
        }

        @Bean
        public DataSource domainOneDataSource() {
            return h2("multids");
        }

        @Bean
        public DataSource domainTwoDataSource() {
            return h2("multids");
        }
    }

    @Configuration
    static class MultiDataSourceWithPrimaryConfig extends MultiDataSourceConfig {
        @Bean
        @org.springframework.context.annotation.Primary
        public DataSource primaryDataSource() {
            return h2("multids");
        }
    }

    @Test
    void exceptionMapperAutoConfiguredByDefault() {
        // SQL failures translate to Spring's DataAccessException hierarchy out of the box.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:exceptionTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    assertThat(context).hasSingleBean(st.orm.spi.ExceptionMapper.class);
                    assertThat(context).getBean(st.orm.spi.ExceptionMapper.class)
                            .isInstanceOf(st.orm.spring.SpringExceptionMapper.class);
                });
    }

    @Test
    void exceptionTranslationCanBeDisabledByProperty() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:exceptionDisabledTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.exception-translation.enabled=false"
                )
                .run(context -> {
                    assertThat(context).doesNotHaveBean(st.orm.spi.ExceptionMapper.class);
                });
    }

    @Test
    void userDefinedExceptionMapperTakesPrecedence() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:exceptionCustomTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(CustomExceptionMapperConfig.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(st.orm.spi.ExceptionMapper.class);
                    assertThat(context).getBean(st.orm.spi.ExceptionMapper.class)
                            .isSameAs(context.getBean("customExceptionMapper"));
                });
    }

    @Test
    void autoConfiguredTemplateThrowsSpringExceptions() {
        // The mapper bean must actually reach the auto-configured template: a duplicate key raised through
        // the template surfaces as Spring's DuplicateKeyException, not Storm's PersistenceException.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:exceptionEndToEndTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> {
                    ORMTemplate orm = context.getBean(ORMTemplate.class);
                    orm.query("CREATE TABLE dup_check (id INT PRIMARY KEY)").executeUpdate();
                    orm.query("INSERT INTO dup_check VALUES (1)").executeUpdate();
                    org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                                    orm.query("INSERT INTO dup_check VALUES (1)").executeUpdate())
                            .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
                });
    }

    @Test
    void queryObserverNotConfiguredWithoutObservationRegistry() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:observationAbsentTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .run(context -> assertThat(context).doesNotHaveBean(st.orm.spi.QueryObserver.class));
    }

    @Test
    void queryExecutionsObservedWhenRegistryPresent() {
        // With an ObservationRegistry in the context, the auto-configured template reports each query
        // as a storm.query observation with the operation as a low-cardinality key value.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:observationTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(TestObservationRegistryConfig.class)
                .run(context -> {
                    assertThat(context).getBean(st.orm.spi.QueryObserver.class)
                            .isInstanceOf(st.orm.micrometer.MicrometerQueryObserver.class);
                    ORMTemplate orm = context.getBean(ORMTemplate.class);
                    orm.query("CREATE TABLE observed (id INT PRIMARY KEY)").executeUpdate();
                    var registry = context.getBean(io.micrometer.observation.tck.TestObservationRegistry.class);
                    io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
                            .hasObservationWithNameEqualTo("storm.query");
                });
    }

    @Test
    void otelSemanticConventionsActivatedByProperty() {
        // storm.observations.semantic-conventions=otel adds the OTel database attributes, with the
        // database product detected from the DataSource.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:otelConventionTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.observations.semantic-conventions=otel"
                )
                .withUserConfiguration(TestObservationRegistryConfig.class)
                .run(context -> {
                    ORMTemplate orm = context.getBean(ORMTemplate.class);
                    orm.query("CREATE TABLE otel_check (id INT PRIMARY KEY)").executeUpdate();
                    var registry = context.getBean(io.micrometer.observation.tck.TestObservationRegistry.class);
                    io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
                            .hasObservationWithNameEqualTo("storm.query")
                            .that()
                            .hasLowCardinalityKeyValue("db.system.name", "h2database")
                            .hasLowCardinalityKeyValue("db.operation.name", "UNDEFINED");
                });
    }

    @Test
    void transactionConventionBeanOverridesTransactionObservations() {
        // An ObservationConvention bean for StormTransactionObservationContext renames and re-tags the
        // transaction observations, without touching the query observations.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:txConventionTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(TestObservationRegistryConfig.class, TransactionConventionConfig.class)
                .run(context -> {
                    var observer = context.getBean(st.orm.spi.QueryObserver.class);
                    observer.onTransaction(new st.orm.TransactionOptions(null, null, null, null))
                            .close(false);
                    var registry = context.getBean(io.micrometer.observation.tck.TestObservationRegistry.class);
                    io.micrometer.observation.tck.TestObservationRegistryAssert.assertThat(registry)
                            .hasObservationWithNameEqualTo("db.tx")
                            .that()
                            .hasLowCardinalityKeyValue("storm.tx.outcome", "committed");
                });
    }

    @Test
    void unknownSemanticConventionsValueFailsFast() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:otelBogusTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.observations.semantic-conventions=bogus"
                )
                .withUserConfiguration(TestObservationRegistryConfig.class)
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void traceSqlCommentsSupportSampledModeAndFailFastOnUnknownValues() {
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sqlCommentsSampledTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.tracing.sql-comments=sampled"
                )
                .withBean(io.micrometer.tracing.Tracer.class, () -> org.mockito.Mockito.mock(io.micrometer.tracing.Tracer.class))
                .run(context -> assertThat(context).getBean(st.orm.spi.SqlCommenter.class)
                        .isInstanceOf(st.orm.micrometer.TraceContextSqlCommenter.class));
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sqlCommentsBogusTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.tracing.sql-comments=bogus"
                )
                .withBean(io.micrometer.tracing.Tracer.class, () -> org.mockito.Mockito.mock(io.micrometer.tracing.Tracer.class))
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void traceSqlCommentsAreOptIn() {
        // Off by default even with a Tracer present: a per-execution comment defeats prepared statement
        // caching, so the correlation is enabled deliberately.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sqlCommentsDefaultTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withBean(io.micrometer.tracing.Tracer.class, () -> org.mockito.Mockito.mock(io.micrometer.tracing.Tracer.class))
                .run(context -> assertThat(context).doesNotHaveBean(st.orm.spi.SqlCommenter.class));
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:sqlCommentsEnabledTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver",
                        "storm.tracing.sql-comments=true"
                )
                .withBean(io.micrometer.tracing.Tracer.class, () -> org.mockito.Mockito.mock(io.micrometer.tracing.Tracer.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(st.orm.spi.SqlCommenter.class);
                    assertThat(context).getBean(st.orm.spi.SqlCommenter.class)
                            .isInstanceOf(st.orm.micrometer.TraceContextSqlCommenter.class);
                });
    }

    @Configuration
    static class TestObservationRegistryConfig {
        @Bean
        public io.micrometer.observation.tck.TestObservationRegistry observationRegistry() {
            return io.micrometer.observation.tck.TestObservationRegistry.create();
        }
    }

    @Configuration
    static class TransactionConventionConfig {
        @Bean
        public io.micrometer.observation.ObservationConvention<st.orm.micrometer.StormTransactionObservationContext> stormTransactionConvention() {
            return new st.orm.micrometer.StormTransactionObservationConvention() {
                @Override
                public String getName() {
                    return "db.tx";
                }
            };
        }
    }

    @Test
    void enableStormRepositoriesBacksOffAutoConfiguredScanning() {
        // @EnableStormRepositories doubles as the explicit override in Boot: its registrar-provided
        // post-processor makes the auto-configured one back off.
        contextRunner
                .withPropertyValues(
                        "spring.datasource.url=jdbc:h2:mem:enableAnnotationTest;DB_CLOSE_DELAY=-1",
                        "spring.datasource.driver-class-name=org.h2.Driver"
                )
                .withUserConfiguration(EnableStormRepositoriesConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(AutoConfiguredRepositoryBeanFactoryPostProcessor.class);
                    assertThat(context).hasBean("stormRepositoriesPostProcessor");
                });
    }

    @Configuration
    @st.orm.spring.EnableStormRepositories(basePackages = "com.example.repository")
    static class EnableStormRepositoriesConfig {
    }

    @Configuration
    static class CustomExceptionMapperConfig {
        @Bean
        public st.orm.spi.ExceptionMapper customExceptionMapper() {
            return st.orm.spi.ExceptionMapper.defaultMapper();
        }
    }

    @Test
    void templateFactoryComposesTemplatesForMultipleDataSources() {
        // With several DataSource beans the single auto-configured template backs off, while the factory
        // composes one fully integrated template per data source.
        contextRunner
                .withUserConfiguration(FactoryDataSourcesConfig.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(st.orm.template.ORMTemplate.class);
                    var factory = context.getBean(OrmTemplateFactory.class);
                    var customized = new java.util.concurrent.atomic.AtomicBoolean();
                    var orders = factory.create(
                            context.getBean("ordersDataSource", javax.sql.DataSource.class), "orders",
                            builder -> customized.set(true));
                    var billing = factory.create(context.getBean("billingDataSource", javax.sql.DataSource.class), "billing");
                    assertThat(customized).isTrue();
                    assertThat(orders).isNotNull();
                    assertThat(billing).isNotNull();
                    assertThat(orders).isNotSameAs(billing);
                });
    }

    @Configuration
    static class FactoryDataSourcesConfig {
        @Bean
        javax.sql.DataSource ordersDataSource() {
            return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                    new org.h2.Driver(), "jdbc:h2:mem:factoryOrders;DB_CLOSE_DELAY=-1");
        }

        @Bean
        javax.sql.DataSource billingDataSource() {
            return new org.springframework.jdbc.datasource.SimpleDriverDataSource(
                    new org.h2.Driver(), "jdbc:h2:mem:factoryBilling;DB_CLOSE_DELAY=-1");
        }
    }

}
