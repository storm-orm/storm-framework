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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.scheduling.annotation.Scheduled;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.SlowStatementLog;

/**
 * Verifies that the SQL log covers every way work enters the application: the servlet filter covers requests,
 * and the entry-point post-processor covers the boundaries a filter cannot see, such as scheduled tasks and
 * message listeners.
 */
public class StormSqlLogAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(StormSqlLogAutoConfiguration.class));

    /** An entry point of the application's own, standing in for a listener annotation Spring does not ship. */
    @Retention(RetentionPolicy.RUNTIME)
    @Target(ElementType.METHOD)
    public @interface CustomListener {
    }

    @Configuration(proxyBeanMethods = false)
    static class JobConfiguration {

        @Bean
        DataSource dataSource() {
            return DataSourceBuilder.create()
                    .url("jdbc:h2:mem:sql-log-entry-points;DB_CLOSE_DELAY=-1")
                    .build();
        }

        @Bean
        ReportJob reportJob(DataSource dataSource) {
            return new ReportJob(ORMTemplate.of(dataSource));
        }

        @Bean
        PlainService plainService() {
            return new PlainService();
        }
    }

    static class ReportJob {
        private final ORMTemplate orm;

        ReportJob(ORMTemplate orm) {
            this.orm = orm;
        }

        @Scheduled(fixedDelay = Long.MAX_VALUE)
        public void nightly() {
            orm.query("SELECT 1").getSingleResult();
        }

        @CustomListener
        public void onMessage() {
            orm.query("SELECT 1").getSingleResult();
            orm.query("SELECT 2").getSingleResult();
        }

        public void plain() {
            orm.query("SELECT 1").getSingleResult();
        }
    }

    static class PlainService {
        public void doWork() {
        }
    }

    private void withScopeLogger(Consumer<List<ILoggingEvent>> test) {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("st.orm.sql.perf");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        var level = logger.getLevel();
        logger.setLevel(Level.INFO);
        try {
            test.accept(appender.list);
        } finally {
            logger.setLevel(level);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
    }

    @Test
    public void testAScheduledMethodReportsAScopeNamedAfterIt() {
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true")
                .run(context -> withScopeLogger(events -> {
                    var job = context.getBean(ReportJob.class);
                    assertTrue(AopUtils.isCglibProxy(job));
                    job.nightly();
                    assertEquals(1, events.size());
                    assertTrue(events.getFirst().getFormattedMessage().startsWith("SQL (ReportJob.nightly):"),
                            events.getFirst().getFormattedMessage());
                }));
    }

    @Test
    public void testAMethodWithoutAnEntryPointAnnotationIsNotWrapped() {
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true")
                .run(context -> withScopeLogger(events -> {
                    context.getBean(ReportJob.class).plain();
                    assertTrue(events.isEmpty());
                    // A bean with no entry points at all is not proxied to begin with.
                    assertFalse(AopUtils.isAopProxy(context.getBean(PlainService.class)));
                }));
    }

    @Test
    public void testConfiguredEntryPointsReplaceTheDefaults() {
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true",
                        "storm.sql-log.performance.entry-points=" + CustomListener.class.getName())
                .run(context -> withScopeLogger(events -> {
                    var job = context.getBean(ReportJob.class);
                    job.nightly();
                    assertTrue(events.isEmpty());
                    job.onMessage();
                    assertEquals(1, events.size());
                    assertTrue(events.getFirst().getFormattedMessage().startsWith("SQL (ReportJob.onMessage):"),
                            events.getFirst().getFormattedMessage());
                }));
    }

    @Test
    public void testAThresholdReportsOnlyTheInvocationsExceedingIt() {
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true",
                        "storm.sql-log.performance.entry-points=" + CustomListener.class.getName(),
                        "storm.sql-log.performance.threshold.statements=2")
                .run(context -> withScopeLogger(events -> {
                    var job = context.getBean(ReportJob.class);
                    job.onMessage();
                    assertEquals(1, events.size());
                    assertEquals(Level.WARN, events.getFirst().getLevel());
                }));
    }

    @Test
    public void testAThresholdKeepsAnInvocationBelowItQuiet() {
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true",
                        "storm.sql-log.performance.threshold.statements=2")
                .run(context -> withScopeLogger(events -> {
                    context.getBean(ReportJob.class).nightly();
                    assertTrue(events.isEmpty());
                }));
    }

    @Test
    public void testTheScopeBacksOffWithoutTheProperty() {
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .run(context -> {
                    assertFalse(context.containsBean("stormPerformanceLogEntryPointPostProcessor"));
                    assertFalse(AopUtils.isAopProxy(context.getBean(ReportJob.class)));
                });
    }

    @Test
    public void testAServletWebApplicationRegistersTheFilterFromTheProperties() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StormSqlLogAutoConfiguration.class))
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true", "storm.sql-log.performance.threshold.statements=5")
                .run(context -> withScopeLogger(events -> {
                    var filter = context.getBean("stormPerformanceLogFilter", StormPerformanceLogFilter.class);
                    // The threshold from the properties reached the filter: a request below it says nothing,
                    // although the logger would report it without a threshold.
                    try {
                        filter.doFilter(new MockHttpServletRequest("GET", "/reports"), new MockHttpServletResponse(),
                                (request, response) -> context.getBean(ReportJob.class).plain());
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                    assertTrue(events.isEmpty());
                }));
    }

    @Test
    public void testTheSlowStatementLogNeedsNoScope() {
        // The slow log sees every execution on its own, so it applies whether or not the summaries are enabled.
        try {
            contextRunner
                    .withUserConfiguration(JobConfiguration.class)
                    .withPropertyValues("storm.sql-log.slow.threshold=200ms")
                    .run(context -> {
                        assertTrue(SlowStatementLog.active());
                        assertFalse(context.containsBean("stormPerformanceLogEntryPointPostProcessor"));
                    });
        } finally {
            SlowStatementLog.threshold(null);
        }
    }

    @Test
    public void testASlowExecutionIsReportedInsideARequest() {
        // Inside a request the summary and the slow line report side by side, each under its own logger.
        SlowStatementLog.threshold(null);
        try {
            new WebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(StormSqlLogAutoConfiguration.class))
                    .withUserConfiguration(JobConfiguration.class)
                    .withPropertyValues("storm.sql-log.performance.enabled=true", "storm.sql-log.slow.threshold=1ns")
                    .run(context -> {
                        assertTrue(SlowStatementLog.active());
                        assertTrue(context.containsBean("stormPerformanceLogFilter"));
                        var slowLogger = (ch.qos.logback.classic.Logger)
                                org.slf4j.LoggerFactory.getLogger("st.orm.sql.slow");
                        var appender = new ListAppender<ILoggingEvent>();
                        appender.start();
                        slowLogger.addAppender(appender);
                        // Captured events stay out of the build log.
                        slowLogger.setAdditive(false);
                        try {
                            withScopeLogger(events -> {
                                var filter = context.getBean(StormPerformanceLogFilter.class);
                                var job = context.getBean(ReportJob.class);
                                var request = new MockHttpServletRequest("GET", "/report");
                                try {
                                    filter.doFilter(request, new MockHttpServletResponse(),
                                            (servletRequest, servletResponse) -> job.plain());
                                } catch (Exception e) {
                                    throw new RuntimeException(e);
                                }
                                assertEquals(1, events.size(), events.toString());
                                assertTrue(events.getFirst().getFormattedMessage().startsWith("SQL (GET /report)"),
                                        events.getFirst().getFormattedMessage());
                            });
                            assertEquals(1, appender.list.size(), appender.list.toString());
                            assertTrue(appender.list.getFirst().getFormattedMessage().startsWith("SQL slow (SELECT)"),
                                    appender.list.getFirst().getFormattedMessage());
                        } finally {
                            slowLogger.detachAppender(appender);
                            slowLogger.setAdditive(true);
                        }
                    });
        } finally {
            SlowStatementLog.threshold(null);
        }
    }

    @Test
    public void testWithoutTheSlowStatementPropertyNoThresholdApplies() {
        SlowStatementLog.threshold(null);
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true")
                .run(context -> assertFalse(SlowStatementLog.active()));
    }

    @Test
    public void testTheSummaryDurationThresholdBecomesTheStatementThreshold() {
        // A call that exceeds the duration holds at least one execution, so the same duration at statement grain
        // only reports inside a call that reports anyway: the summary gains the statement that caused it.
        SlowStatementLog.threshold(null);
        try {
            contextRunner
                    .withUserConfiguration(JobConfiguration.class)
                    .withPropertyValues("storm.sql-log.performance.enabled=true", "storm.sql-log.performance.threshold.duration=500ms")
                    .run(context -> assertEquals(Duration.ofMillis(500), SlowStatementLog.threshold()));
        } finally {
            SlowStatementLog.threshold(null);
        }
    }

    @Test
    public void testAnExplicitSlowStatementWinsOverTheSummaryThreshold() {
        SlowStatementLog.threshold(null);
        try {
            contextRunner
                    .withUserConfiguration(JobConfiguration.class)
                    .withPropertyValues("storm.sql-log.performance.enabled=true",
                            "storm.sql-log.performance.threshold.duration=500ms",
                            "storm.sql-log.slow.threshold=50ms")
                    .run(context -> assertEquals(Duration.ofMillis(50), SlowStatementLog.threshold()));
        } finally {
            SlowStatementLog.threshold(null);
        }
    }

    @Test
    public void testTheSummaryThresholdIsNotDerivedWithoutTheSummaries() {
        // The duration only says what a slow call is once calls are being reported; on its own it configures
        // nothing, so nothing is derived from it.
        SlowStatementLog.threshold(null);
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.threshold.duration=500ms")
                .run(context -> assertFalse(SlowStatementLog.active()));
    }

    @Test
    public void testTheLimitAppliesWithoutAThresholdOfItsOwn() {
        // The threshold may arrive from a system property or at runtime, so a configured limit has to be applied
        // whether or not this application configures the threshold too.
        SlowStatementLog.threshold(null);
        int previous = SlowStatementLog.limit();
        try {
            contextRunner
                    .withUserConfiguration(JobConfiguration.class)
                    .withPropertyValues("storm.sql-log.slow.limit=2")
                    .run(context -> {
                        assertFalse(SlowStatementLog.active());
                        assertEquals(2, SlowStatementLog.limit());
                    });
        } finally {
            SlowStatementLog.limit(previous);
        }
    }

    @Test
    public void testTheEndpointReadsAndSetsTheSlowThresholdAtRuntime() {
        // Off by default costs nothing only while it can be switched on where it is needed, which is a running
        // deployment whose database is degraded now.
        SlowStatementLog.threshold(null);
        try {
            contextRunner
                    .withUserConfiguration(JobConfiguration.class)
                    .run(context -> {
                        var endpoint = context.getBean(StormEndpoint.class);
                        assertEquals(false, slowStatementOf(endpoint).get("active"));
                        endpoint.configure("200ms", 3, null, null, null, null);
                        assertEquals(Duration.ofMillis(200), SlowStatementLog.threshold());
                        assertEquals(3, SlowStatementLog.limit());
                        assertEquals(true, slowStatementOf(endpoint).get("active"));
                        endpoint.configure("off", null, null, null, null, null);
                        assertFalse(SlowStatementLog.active());
                        assertEquals(3, SlowStatementLog.limit());
                    });
        } finally {
            SlowStatementLog.threshold(null);
            SlowStatementLog.limit(SlowStatementLog.DEFAULT_LIMIT);
        }
    }

    @Test
    public void testTheEndpointRetunesThePerformanceBoundariesAtRuntime() {
        // The performance log answers the same question about the same misbehaving deployment, so what it reports
        // on is as live as the slow threshold; only whether the boundaries exist stays a startup decision.
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(StormSqlLogAutoConfiguration.class))
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true", "storm.sql-log.performance.threshold.duration=500ms")
                .run(context -> {
                    var endpoint = context.getBean(StormEndpoint.class);
                    var filter = context.getBean(StormPerformanceLogFilter.class);
                    var postProcessor = context.getBean(StormPerformanceLogEntryPointPostProcessor.class);
                    assertEquals(Duration.ofMillis(500), filter.settings().durationThreshold());
                    assertEquals(Duration.ofMillis(500), postProcessor.settings().durationThreshold());
                    endpoint.configure(null, null, "20", "50ms", true, 500);
                    for (var boundary : List.of(filter.settings(), postProcessor.settings())) {
                        assertEquals(20, boundary.statementThreshold());
                        assertEquals(Duration.ofMillis(50), boundary.durationThreshold());
                        assertTrue(boundary.callSites());
                        assertEquals(500, boundary.limit());
                    }
                    // Dropping both thresholds returns the boundaries to reporting every unit of work at INFO.
                    endpoint.configure(null, null, "off", "off", null, null);
                    assertFalse(filter.settings().thresholded());
                    assertFalse(postProcessor.settings().thresholded());
                });
    }

    @Test
    public void testTheEndpointReportsTheSlowLogWithoutAnyBoundary() {
        // The slow statement log needs no boundary, so the endpoint is useful in an application that never
        // enabled the performance log at all.
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .run(context -> {
                    var endpoint = context.getBean(StormEndpoint.class);
                    assertTrue(endpoint.settings().containsKey("slowStatement"));
                    assertTrue(performanceOf(endpoint).isEmpty());
                });
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> slowStatementOf(StormEndpoint endpoint) {
        return (Map<String, Object>) endpoint.settings().get("slowStatement");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> performanceOf(StormEndpoint endpoint) {
        return (Map<String, Object>) endpoint.settings().get("performance");
    }

    @Test
    public void testTheFilterRequiresAServletWebApplication() {
        // The runner is not a web application, so only the entry-point post-processor registers.
        contextRunner
                .withUserConfiguration(JobConfiguration.class)
                .withPropertyValues("storm.sql-log.performance.enabled=true")
                .run(context -> {
                    assertTrue(context.containsBean("stormPerformanceLogEntryPointPostProcessor"));
                    assertFalse(context.containsBean("stormPerformanceLogFilter"));
                });
    }
}
