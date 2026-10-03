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
package st.orm.spring.boot.autoconfigure

import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Logger
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.AutoConfigurations
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration
import org.springframework.boot.context.properties.source.InvalidConfigurationPropertyValueException
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.system.CapturedOutput
import org.springframework.boot.test.system.OutputCaptureExtension
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import st.orm.Entity
import st.orm.EntityCallback
import st.orm.core.spi.ConnectionProvider
import st.orm.core.spi.TransactionTemplateProvider
import st.orm.spring.SpringConnectionProvider
import st.orm.spring.SpringTransactionTemplateProvider
import st.orm.spring.boot.StormExceptionTranslationAutoConfiguration
import st.orm.spring.boot.StormObservationAutoConfiguration
import st.orm.spring.boot.StormProperties
import st.orm.spring.boot.StormTransactionAutoConfiguration
import st.orm.spring.boot.StormValidationAutoConfiguration
import st.orm.spring.kotlin.RepositoryBeanFactoryPostProcessor
import st.orm.template.ORMTemplate
import javax.sql.DataSource

@ExtendWith(OutputCaptureExtension::class)
internal class StormAutoConfigurationTest {

    /**
     * Logs the schema validation outcome at info while [block] runs. The build logs warnings and errors only, and the
     * schema validation tests read the success line from the output.
     */
    private fun logValidationOutcome(block: () -> Unit) {
        val logger = LoggerFactory.getLogger(StormValidationAutoConfiguration::class.java) as Logger
        val level = logger.level
        logger.level = Level.INFO
        try {
            block()
        } finally {
            logger.level = level
        }
    }

    private val contextRunner = ApplicationContextRunner()
        .withConfiguration(
            AutoConfigurations.of(
                DataSourceAutoConfiguration::class.java,
                DataSourceTransactionManagerAutoConfiguration::class.java,
                StormAutoConfiguration::class.java,
                StormRepositoryAutoConfiguration::class.java,
                StormTransactionAutoConfiguration::class.java,
                StormValidationAutoConfiguration::class.java,
                StormExceptionTranslationAutoConfiguration::class.java,
                StormObservationAutoConfiguration::class.java,
            ),
        )

    @Test
    fun `ormTemplate bean created when DataSource present`() {
        // StormAutoConfiguration is conditional on a DataSource bean. When a DataSource is available,
        // it should auto-configure an ORMTemplate bean.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
            }
    }

    @Test
    fun `ormTemplate bean not created without DataSource`() {
        // Without a DataSource in the context, StormAutoConfiguration should not create
        // an ORMTemplate bean (the @ConditionalOnBean(DataSource) condition should fail).
        ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StormAutoConfiguration::class.java))
            .run { context ->
                context.containsBean("ormTemplate") shouldBe false
            }
    }

    @Test
    fun `user-defined ORMTemplate takes precedence`() {
        // StormAutoConfiguration uses @ConditionalOnMissingBean(ORMTemplate), so a user-defined
        // ORMTemplate bean should take precedence over the auto-configured one.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .withUserConfiguration(CustomOrmTemplateConfig::class.java)
            .run { context ->
                val ormTemplate = context.getBean(ORMTemplate::class.java)
                ormTemplate shouldBe context.getBean("customOrmTemplate")
            }
    }

    @Test
    fun `repository bean factory post processor auto-configured`() {
        // StormRepositoryAutoConfiguration should register an AutoConfiguredRepositoryBeanFactoryPostProcessor
        // that scans for repository interfaces and registers them as Spring beans.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .run { context ->
                context.getBean(RepositoryBeanFactoryPostProcessor::class.java)
                    .shouldBeInstanceOf<AutoConfiguredRepositoryBeanFactoryPostProcessor>()
            }
    }

    @Test
    fun `user-defined RepositoryBeanFactoryPostProcessor takes precedence`() {
        // StormRepositoryAutoConfiguration uses @ConditionalOnMissingBean, so a user-defined
        // RepositoryBeanFactoryPostProcessor should prevent the auto-configured one from being created.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .withUserConfiguration(CustomRepositoryPostProcessorConfig::class.java)
            .run { context ->
                context.containsBean("repositoryBeanFactoryPostProcessor") shouldBe true
                context.containsBeanDefinition("autoConfiguredRepositoryBeanFactoryPostProcessor") shouldBe false
            }
    }

    @Test
    fun `transaction auto-configuration provides spring-aware providers when a transaction manager is present`() {
        // StormTransactionAutoConfiguration should contribute the Spring-aware ConnectionProvider and
        // TransactionTemplateProvider beans when a PlatformTransactionManager is present (provided by
        // DataSourceTransactionManagerAutoConfiguration).
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .run { context ->
                context.getBean(ConnectionProvider::class.java).shouldBeInstanceOf<SpringConnectionProvider>()
                context.getBean(TransactionTemplateProvider::class.java)
                    .shouldBeInstanceOf<SpringTransactionTemplateProvider>()
            }
    }

    @Test
    fun `user-defined provider beans take precedence over the auto-configured ones`() {
        // The auto-configured providers back off via @ConditionalOnMissingBean when the user defines their own.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .withUserConfiguration(CustomProviderConfig::class.java)
            .run { context ->
                context.getBean(ConnectionProvider::class.java)
                    .shouldBeInstanceOf<CustomProviderConfig.CustomConnectionProvider>()
            }
    }

    @Configuration
    open class CustomProviderConfig {
        class CustomConnectionProvider(private val delegate: ConnectionProvider = SpringConnectionProvider()) : ConnectionProvider by delegate

        @Bean
        open fun customConnectionProvider(): ConnectionProvider = CustomConnectionProvider()
    }

    @Test
    fun `storm properties bound from application configuration`() {
        // Storm properties under the "storm.*" prefix should be bound to StormProperties and applied
        // to the ORMTemplate. Each property value set here should be reflected in the bound bean.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.update.default-mode=ENTITY",
                "storm.update.dirty-check=INSTANCE",
                "storm.update.max-shapes=5",
                "storm.entity-cache.retention=default",
                "storm.template-cache.size=100",
                "storm.ansi-escaping=false",
                "storm.validation.record-mode=warn",
            )
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
                val properties = context.getBean(StormProperties::class.java)
                properties.update.defaultMode shouldBe "ENTITY"
                properties.update.dirtyCheck shouldBe "INSTANCE"
                properties.update.maxShapes shouldBe 5
                properties.entityCache.retention shouldBe "default"
                properties.templateCache.size shouldBe 100
                properties.ansiEscaping shouldBe false
                properties.validation.recordMode shouldBe "warn"
            }
    }

    @Test
    fun `storm properties setters update values`() {
        // StormProperties is a POJO with nested config objects. Setters should correctly update values,
        // which is essential for Spring Boot's property binding mechanism.
        val properties = StormProperties()

        val update = StormProperties.Update()
        update.defaultMode = "FIELD"
        update.dirtyCheck = "FIELD"
        update.maxShapes = 10
        properties.update = update

        val entityCache = StormProperties.EntityCache()
        entityCache.retention = "light"
        properties.entityCache = entityCache

        val templateCache = StormProperties.TemplateCache()
        templateCache.size = 200
        properties.templateCache = templateCache

        val validation = StormProperties.Validation()
        validation.recordMode = "none"
        properties.validation = validation

        properties.ansiEscaping = true

        properties.update.defaultMode shouldBe "FIELD"
        properties.update.dirtyCheck shouldBe "FIELD"
        properties.update.maxShapes shouldBe 10
        properties.entityCache.retention shouldBe "light"
        properties.templateCache.size shouldBe 200
        properties.validation.recordMode shouldBe "none"
        properties.ansiEscaping shouldBe true
    }

    @Test
    fun `entity callback bean auto-detected`() {
        // When a user defines an EntityCallback bean, StormAutoConfiguration should detect it
        // and wire it into the ORMTemplate for entity lifecycle callbacks.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .withUserConfiguration(EntityCallbackConfig::class.java)
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
                context.containsBean("entityCallback") shouldBe true
            }
    }

    @Test
    fun `schema validation warn mode should not prevent context startup`() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:schemaWarnTest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.validation.schema-mode=warn",
            )
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
            }
    }

    @Test
    fun `schema validation none mode should not prevent context startup`() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:schemaNoneTest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.validation.schema-mode=none",
            )
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
            }
    }

    @Test
    fun `strict validation property should be bound correctly`() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:strictTest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.validation.strict=true",
            )
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
                val properties = context.getBean(StormProperties::class.java)
                properties.validation.strict shouldBe true
            }
    }

    @Test
    fun `schema validation defaults to fail mode`(output: CapturedOutput) {
        // No schema-mode property: validation must run in fail mode by default.
        // With no entities on the test classpath there is nothing to reject, so
        // the success log proves the default dispatched into the fail branch.
        logValidationOutcome {
            contextRunner
                .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:schemaDefaultTest;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                )
                .run { context ->
                    context.getBean(ORMTemplate::class.java) shouldNotBe null
                    output.out.contains("Storm schema validation passed (mode=fail).") shouldBe true
                }
        }
    }

    @Test
    fun `schema validation fail mode should start context when no entities are registered`() {
        // With no entities registered, validateOrThrow should succeed (nothing to validate).
        // This exercises the "fail" branch in runSchemaValidation.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:schemaFailTest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.validation.schema-mode=fail",
            )
            .run { context ->
                context.getBean(ORMTemplate::class.java) shouldNotBe null
            }
    }

    @Test
    fun `schema validation blank mode falls back to fail default`(output: CapturedOutput) {
        // A blank (whitespace-only) schema-mode is treated as unset and falls back
        // to the fail default.
        logValidationOutcome {
            contextRunner
                .withPropertyValues(
                    "spring.datasource.url=jdbc:h2:mem:schemaBlankTest;DB_CLOSE_DELAY=-1",
                    "spring.datasource.driver-class-name=org.h2.Driver",
                    "storm.validation.schema-mode=  ",
                )
                .run { context ->
                    context.getBean(ORMTemplate::class.java) shouldNotBe null
                    output.out.contains("Storm schema validation passed (mode=fail).") shouldBe true
                }
        }
    }

    @Test
    fun `schema validation unknown mode fails context startup`() {
        // A typo in the mode must fail startup instead of silently skipping schema validation.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:schemaUnknownTest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.validation.schema-mode=fial",
            )
            .run { context ->
                val failure = context.startupFailure.shouldBeInstanceOf<InvalidConfigurationPropertyValueException>()
                failure.message shouldContain "storm.validation.schema-mode"
                failure.message shouldContain "'fial'"
                failure.message shouldContain "Valid values are: none, warn, fail."
            }
    }

    @Test
    fun `AutoConfiguredRepositoryBeanFactoryPostProcessor resolves packages from auto-configuration`() {
        // When the processor runs inside a Spring Boot context, it should resolve
        // base packages from AutoConfigurationPackages rather than returning empty.
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:autoPackagesTest;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .run { context ->
                val processor = context.getBean(RepositoryBeanFactoryPostProcessor::class.java)
                    as AutoConfiguredRepositoryBeanFactoryPostProcessor
                // After postProcessBeanFactory, packages should be resolved (non-null).
                // The important contract: repositoryBasePackages never returns null after initialization.
                processor.repositoryBasePackages shouldNotBe null
            }
    }

    @Configuration
    open class EntityCallbackConfig {
        @Bean
        open fun entityCallback(): EntityCallback<*> = object : EntityCallback<Entity<*>> {}
    }

    @Configuration
    open class CustomOrmTemplateConfig {
        @Bean
        open fun customOrmTemplate(dataSource: DataSource): ORMTemplate = ORMTemplate.of(dataSource)
    }

    @Configuration
    open class CustomRepositoryPostProcessorConfig {
        companion object {
            @JvmStatic
            @Bean
            fun repositoryBeanFactoryPostProcessor(): RepositoryBeanFactoryPostProcessor = object : RepositoryBeanFactoryPostProcessor() {
                override fun getRepositoryBasePackages(): Array<String> = arrayOf("com.example")
            }
        }
    }

    @Test
    fun `exception mapper auto-configured by default and disabled by property`() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:exceptionTestKt;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .run { context ->
                context.getBean(st.orm.spi.ExceptionMapper::class.java)
                    .shouldBeInstanceOf<st.orm.spring.SpringExceptionMapper>()
            }
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:exceptionDisabledTestKt;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "storm.exception-translation.enabled=false",
            )
            .run { context ->
                context.getBeanNamesForType(st.orm.spi.ExceptionMapper::class.java).toList().shouldBeEmpty()
            }
    }

    @Test
    fun `template factory composes templates for the data sources of a multi-database application`() {
        // With several DataSource beans the single auto-configured template backs off, while the factory
        // composes one fully integrated template per data source.
        contextRunner
            .withUserConfiguration(MultiDataSourceConfig::class.java)
            .run { context ->
                context.getBeanNamesForType(ORMTemplate::class.java).toList().shouldBeEmpty()
                val factory = context.getBean(OrmTemplateFactory::class.java)
                var customized = false
                val orders = factory.create(context.getBean("ordersDataSource", DataSource::class.java), "orders") {
                    customized = true
                }
                val billing = factory.create(context.getBean("billingDataSource", DataSource::class.java), "billing")
                customized shouldBe true
                orders shouldNotBe null
                billing shouldNotBe null
                orders shouldNotBe billing
            }
    }

    @Configuration
    open class MultiDataSourceConfig {
        @Bean
        open fun ordersDataSource(): DataSource = org.springframework.jdbc.datasource.SimpleDriverDataSource(org.h2.Driver(), "jdbc:h2:mem:factoryOrdersKt;DB_CLOSE_DELAY=-1")

        @Bean
        open fun billingDataSource(): DataSource = org.springframework.jdbc.datasource.SimpleDriverDataSource(org.h2.Driver(), "jdbc:h2:mem:factoryBillingKt;DB_CLOSE_DELAY=-1")
    }

    @Test
    fun `query observer follows the observation registry`() {
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:observationAbsentKt;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .run { context ->
                context.getBeanNamesForType(st.orm.spi.QueryObserver::class.java).toList().shouldBeEmpty()
            }
        contextRunner
            .withPropertyValues(
                "spring.datasource.url=jdbc:h2:mem:observationKt;DB_CLOSE_DELAY=-1",
                "spring.datasource.driver-class-name=org.h2.Driver",
            )
            .withBean(io.micrometer.observation.ObservationRegistry::class.java, { io.micrometer.observation.ObservationRegistry.create() })
            .run { context ->
                context.getBean(st.orm.spi.QueryObserver::class.java)
                    .shouldBeInstanceOf<st.orm.micrometer.MicrometerQueryObserver>()
            }
    }
}
