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

import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.BeanFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.AutoConfiguration
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnSingleCandidate
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import st.orm.EntityCallback
import st.orm.StormConfig
import st.orm.micrometer.MicrometerQueryObserver
import st.orm.micrometer.StormQueryObservationContext
import st.orm.micrometer.StormTransactionObservationContext
import st.orm.spi.ExceptionMapper
import st.orm.spi.QueryObserver
import st.orm.spi.SqlCommenter
import st.orm.spring.SpringExceptionMapper
import st.orm.spring.boot.StormProperties
import st.orm.spring.boot.StormQueryObservers
import st.orm.spring.kotlin.springOrmTemplateBuilder
import st.orm.template.ORMTemplate
import javax.sql.DataSource

/**
 * Auto-configuration for the Storm ORM framework.
 *
 * Creates an [ORMTemplate] bean from the available [DataSource] if no `ORMTemplate` bean has been defined by the user.
 * A [StormConfig] is built from the bound [StormProperties] and passed to the `ORMTemplate` factory.
 *
 * @see StormConfig
 */
@AutoConfiguration
@ConditionalOnClass(ORMTemplate::class)
@EnableConfigurationProperties(StormProperties::class)
public open class StormAutoConfiguration {

    /**
     * Creates an [ORMTemplate] bean using the provided [DataSource] and [StormProperties].
     *
     * A [StormConfig] is built from the bound properties. Fields not explicitly configured in `application.yml`
     * fall back to system properties and then to built-in defaults.
     *
     * Integration strategies are consumed from the application context when present: the Spring-aware connection and
     * transaction template providers contributed by [StormTransactionAutoConfiguration][st.orm.spring.boot.StormTransactionAutoConfiguration] (or user-defined
     * replacements), and optional [ExceptionMapper] and [QueryObserver] beans. Without such beans the template falls
     * back to `ServiceLoader` discovery, matching standalone behavior.
     *
     * This bean backs off if the user has already defined their own `ORMTemplate` bean.
     *
     * @param dataSource the data source to use for database operations.
     * @param properties the Storm configuration properties bound from `storm.*`.
     * @return a new [ORMTemplate] instance.
     */
    @Bean
    @ConditionalOnMissingBean(ORMTemplate::class)
    @ConditionalOnSingleCandidate(DataSource::class)
    public open fun ormTemplate(
        dataSource: DataSource,
        properties: StormProperties,
        entityCallbacks: List<EntityCallback<*>>,
        beanFactory: BeanFactory,
        exceptionMapper: ObjectProvider<ExceptionMapper>,
        queryObserver: ObjectProvider<QueryObserver>,
        sqlCommenter: ObjectProvider<SqlCommenter>,
    ): ORMTemplate {
        val builder = springOrmTemplateBuilder(dataSource, beanFactory).config(properties.toStormConfig())
        exceptionMapper.ifAvailable { builder.exceptionMapper(it) }
        queryObserver.ifAvailable { builder.queryObserver(it) }
        sqlCommenter.ifAvailable { builder.sqlCommenter(it) }
        return builder.build().withEntityCallbacks(entityCallbacks)
    }

    /**
     * Creates the [OrmTemplateFactory] that composes fully integrated templates for the applications that
     * define their own template beans, where the single auto-configured template does not apply.
     *
     * The factory consumes the same integration beans the auto-configured template does. SQL failure
     * translation follows `storm.exception-translation.enabled`, with the mapper created per data source when
     * no [ExceptionMapper] bean is defined, so every template translates against the database it runs on.
     * Observations are composed per data source through [StormQueryObservers] when an [ObservationRegistry]
     * is available; a [QueryObserver] bean is not consumed here, since it carries no database identity. A
     * template that needs a custom observer sets it in the factory's customize block.
     *
     * @param properties the Storm configuration properties bound from `storm.*`.
     * @param entityCallbacks the entity callbacks applied to every created template.
     * @param beanFactory the bean factory holding the Spring-aware transaction integration, when active.
     * @param exceptionMapper user-defined exception mapper overriding the per-data-source default.
     * @param sqlCommenter the SQL commenter contributed by the tracing auto-configuration.
     * @param observationSupport the observation composition, present when Micrometer observations are active.
     * @return the template factory.
     */
    @Bean
    @ConditionalOnMissingBean(OrmTemplateFactory::class)
    public open fun ormTemplateFactory(
        properties: StormProperties,
        entityCallbacks: List<EntityCallback<*>>,
        beanFactory: BeanFactory,
        exceptionMapper: ObjectProvider<ExceptionMapper>,
        sqlCommenter: ObjectProvider<SqlCommenter>,
        observationSupport: ObjectProvider<OrmTemplateObservationSupport>,
    ): OrmTemplateFactory = object : OrmTemplateFactory {
        override fun create(
            dataSource: DataSource,
            database: String?,
            customize: ORMTemplate.Builder.() -> Unit,
        ): ORMTemplate {
            val builder = springOrmTemplateBuilder(dataSource, beanFactory).config(properties.toStormConfig())
            val mapper = exceptionMapper.ifAvailable
                ?: if (properties.exceptionTranslation.enabled != false) SpringExceptionMapper(dataSource) else null
            mapper?.let { builder.exceptionMapper(it) }
            observationSupport.ifAvailable { builder.queryObserver(it.observerFor(dataSource, database)) }
            sqlCommenter.ifAvailable { builder.sqlCommenter(it) }
            builder.customize()
            return builder.build().withEntityCallbacks(entityCallbacks)
        }
    }

    /**
     * Observation composition for factory-created templates, present when Micrometer observations are on the
     * class path and an [ObservationRegistry] bean is available. Each observer resolves the semantic
     * conventions against its own data source and carries the template's database name, so the observation
     * identity follows the template rather than the application.
     */
    public class OrmTemplateObservationSupport internal constructor(
        private val observationRegistry: ObservationRegistry,
        private val properties: StormProperties,
        private val queryConvention: ObservationConvention<StormQueryObservationContext>?,
        private val transactionConvention: ObservationConvention<StormTransactionObservationContext>?,
    ) {
        /** Composes the observer for a template on [dataSource], reporting [database] as `storm.database`. */
        public fun observerFor(dataSource: DataSource, database: String?): QueryObserver = StormQueryObservers.create(
            observationRegistry,
            properties,
            dataSource,
            database,
            queryConvention,
            transactionConvention,
        )
    }

    /** Contributes the factory's observation composition when Micrometer observations are active. */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(ObservationRegistry::class, MicrometerQueryObserver::class)
    @ConditionalOnBean(ObservationRegistry::class)
    public open class ObservationSupportConfiguration {

        @Bean
        @ConditionalOnMissingBean(OrmTemplateObservationSupport::class)
        public open fun ormTemplateObservationSupport(
            observationRegistry: ObservationRegistry,
            properties: StormProperties,
            queryConvention: ObjectProvider<ObservationConvention<StormQueryObservationContext>>,
            transactionConvention: ObjectProvider<ObservationConvention<StormTransactionObservationContext>>,
        ): OrmTemplateObservationSupport = OrmTemplateObservationSupport(
            observationRegistry,
            properties,
            queryConvention.ifAvailable,
            transactionConvention.ifAvailable,
        )
    }
}
