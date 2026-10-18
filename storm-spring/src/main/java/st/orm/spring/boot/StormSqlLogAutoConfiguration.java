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
package st.orm.spring.boot;

import jakarta.servlet.Filter;
import java.time.Duration;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.ClassUtils;
import st.orm.core.template.impl.AdviceLog;
import st.orm.core.template.impl.CallSiteCapture;
import st.orm.core.template.impl.SlowStatementLog;
import st.orm.core.template.impl.SqlLogRenderer;

/**
 * Auto-configuration that reports what each unit of work cost the database, and the single statement executions
 * that cost too much.
 *
 * <p>Opt in to the performance log with {@code storm.sql-log.performance.enabled=true}. Every way work enters the
 * application is a boundary: HTTP requests are wrapped by a servlet filter, and scheduled tasks and message
 * listeners by a proxy around the annotated entry-point method, so a worker without a web layer reports the same
 * way a web application does. For a narrower boundary, such as one service method, open a scope directly with
 * {@link st.orm.template.SqlLog}.</p>
 *
 * <p>The slow statement log, {@code storm.sql-log.slow.threshold=200ms}, needs no boundary: it reports each
 * execution whose database time exceeds the threshold, wherever it runs, and so applies with or without the
 * performance log. Left unset alongside {@code storm.sql-log.performance.enabled=true} it takes
 * {@code storm.sql-log.performance.threshold.duration} for its threshold, so a performance line that reports a slow call is
 * accompanied by the execution that made it slow.</p>
 *
 * <p>Everything the log reports with is applied at startup and stays changeable while the application runs,
 * through {@link StormEndpoint} where the actuator is on the classpath. Only {@code enabled} is fixed
 * there: it decides whether the filter and the proxies exist, which a refreshed context cannot be given.</p>
 *
 * @since 1.13
 */
@AutoConfiguration
@EnableConfigurationProperties(StormProperties.class)
public class StormSqlLogAutoConfiguration {

    /** A class of Spring Boot DevTools, whose presence marks an application in development. */
    private static final String DEVTOOLS = "org.springframework.boot.devtools.restart.Restarter";

    /**
     * Binds the {@code storm.sql-log} properties ahead of the configuration-properties machinery, for the beans
     * created before it.
     */
    private static StormProperties.SqlLog bind(Environment environment) {
        return new Binder(
                ConfigurationPropertySources.get(environment),
                new PropertySourcesPlaceholdersResolver(environment),
                ApplicationConversionService.getSharedInstance())
                .bindOrCreate("storm.sql-log", StormProperties.SqlLog.class);
    }

    /**
     * Applies the slow statement threshold and the categories of advice the advice log reports. Statements execute while the application starts, migrations and
     * schema validation among them, so the threshold is applied when this bean is defined, which a bean factory
     * post-processor is before any singleton exists; the processor itself has nothing left to do.
     *
     * <p>Each setting is applied only when configured, so an application that configures neither keeps whatever
     * the {@code storm.sql_log.*} system properties put in effect.</p>
     */
    @Bean
    static BeanFactoryPostProcessor stormSlowStatementLog(Environment environment) {
        var sqlLog = bind(environment);
        var threshold = slowThreshold(sqlLog);
        if (threshold != null) {
            SlowStatementLog.threshold(threshold);
        }
        var limit = sqlLog.getSlow().getLimit();
        if (limit != null) {
            SlowStatementLog.limit(limit);
        }
        var categories = sqlLog.getAdvice().getCategories();
        if (categories != null) {
            AdviceLog.categories(categories);
        } else if (ClassUtils.isPresent(DEVTOOLS, StormSqlLogAutoConfiguration.class.getClassLoader())) {
            // DevTools is on the classpath in development and left out of a packaged application.
            AdviceLog.development();
        }
        return beanFactory -> {
        };
    }

    /**
     * Returns the database time above which a single execution is reported, or {@code null} to leave the log as
     * configured elsewhere.
     *
     * <p>An application that opted into the performance log and told it what a slow call is has already said what
     * slow means for its workload, and already accepted a warning when work exceeds it. A call contains at least
     * one execution, so the same duration at statement grain can only be exceeded inside a call that exceeds it
     * too: the derived default names the statement behind a warning that was going to be logged anyway, rather
     * than adding warnings of its own. Work outside a boundary has no summary to sit beside, and reports on the
     * same threshold.</p>
     */
    private static @Nullable Duration slowThreshold(StormProperties.SqlLog sqlLog) {
        var configured = sqlLog.getSlow().getThreshold();
        if (configured != null) {
            return configured;
        }
        var performance = sqlLog.getPerformance();
        return performance.isEnabled() ? performance.getThreshold().getDuration() : null;
    }

    /**
     * The endpoint that reads and retunes what the log reports at runtime, registered where the actuator is on
     * the classpath. Exposing it is the application's decision, as it is for every endpoint.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(Endpoint.class)
    static class EndpointConfiguration {

        /**
         * Provides the endpoint over the boundaries the application registered, of which there are none while the
         * performance log is disabled; the slow statement log needs no boundary and is settable either way.
         */
        @Bean
        @ConditionalOnMissingBean(StormEndpoint.class)
        StormEndpoint stormEndpoint(ObjectProvider<PerformanceLog.Boundary> boundaries) {
            return new StormEndpoint(boundaries.orderedStream().toList());
        }
    }

    /**
     * The performance log, which needs a boundary and is opted into with
     * {@code storm.sql-log.performance.enabled=true}.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "storm.sql-log.performance.enabled", havingValue = "true")
    static class PerformanceLogConfiguration {

        /**
         * Provides the post-processor that wraps non-request entry points, such as {@code @Scheduled} tasks and
         * message listeners, in a scope the performance log reports on.
         *
         * <p>Registered as a static bean and bound through the {@link Binder}, since a bean post-processor is
         * created before the configuration-properties machinery. The display settings, which apply to every
         * summary whichever boundary produced it, are applied here as the one unconditional spot of the
         * performance log's configuration.</p>
         */
        @Bean
        @ConditionalOnMissingBean(StormPerformanceLogEntryPointPostProcessor.class)
        static StormPerformanceLogEntryPointPostProcessor stormPerformanceLogEntryPointPostProcessor(
                Environment environment) {
            var sqlLog = bind(environment);
            applyDisplaySettings(sqlLog);
            var performance = sqlLog.getPerformance();
            return new StormPerformanceLogEntryPointPostProcessor(Set.copyOf(performance.getEntryPoints()),
                    performance.getLimit(),
                    performance.isCallSites(),
                    performance.getThreshold().getStatements(),
                    performance.getThreshold().getDuration());
        }

        /**
         * Wraps each HTTP request in a scope; the request is the boundary a web application already has.
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
        @ConditionalOnClass(Filter.class)
        static class WebConfiguration {

            /**
             * Provides the filter that wraps each request in a scope the performance log reports on.
             */
            @Bean
            @ConditionalOnMissingBean(StormPerformanceLogFilter.class)
            StormPerformanceLogFilter stormPerformanceLogFilter(StormProperties properties) {
                var performance = properties.getSqlLog().getPerformance();
                return new StormPerformanceLogFilter(performance.getLimit(),
                        performance.isCallSites(),
                        performance.getThreshold().getStatements(),
                        performance.getThreshold().getDuration());
            }
        }
    }

    /** Applies the settings that shape how every summary renders, whichever boundary produced it. */
    private static void applyDisplaySettings(StormProperties.SqlLog sqlLog) {
        if (!sqlLog.getCallSiteSkip().isEmpty()) {
            CallSiteCapture.ignoreCallSites(sqlLog.getCallSiteSkip().toArray(String[]::new));
        }
        var lineWidth = sqlLog.getPerformance().getLineWidth();
        if (lineWidth != null) {
            SqlLogRenderer.lineWidth(lineWidth);
        }
    }

}
