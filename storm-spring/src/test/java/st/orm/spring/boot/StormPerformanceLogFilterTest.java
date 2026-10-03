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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import st.orm.core.template.ORMTemplate;

/**
 * Verifies the request filter that reports what each request cost the database: the scope is named after the
 * request, opens only while something consumes the summary, reports every request at INFO without thresholds and
 * only the requests exceeding a threshold at WARN with one, and lets the request's own failure through untouched.
 */
class StormPerformanceLogFilterTest {

    private ORMTemplate orm;

    @BeforeEach
    void setUp() {
        DataSource dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:sql-log-filter;DB_CLOSE_DELAY=-1")
                .build();
        orm = ORMTemplate.of(dataSource);
    }

    /** A test body that runs the filter, which declares the servlet chain's checked exceptions. */
    interface ScopeTest {
        void run(List<ILoggingEvent> events) throws Exception;
    }

    private static void withScopeLogger(Level level, ScopeTest test) throws Exception {
        var logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger("st.orm.sql.perf");
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        var previous = logger.getLevel();
        logger.setLevel(level);
        try {
            test.run(appender.list);
        } finally {
            logger.setLevel(previous);
            logger.detachAppender(appender);
            logger.setAdditive(true);
        }
    }

    /** A request handler standing in for the rest of the filter chain, issuing the given number of statements. */
    private FilterChain handler(int statements, AtomicInteger invocations) {
        return (request, response) -> {
            invocations.incrementAndGet();
            for (int i = 0; i < statements; i++) {
                orm.query("SELECT 1").getSingleResult();
            }
        };
    }

    private static void run(StormPerformanceLogFilter filter, FilterChain chain) throws ServletException, IOException {
        filter.doFilter(new MockHttpServletRequest("GET", "/owners/42"), new MockHttpServletResponse(), chain);
    }

    @Test
    void aRequestThatTouchesTheDatabaseIsReportedUnderItsMethodAndPath() throws Exception {
        withScopeLogger(Level.INFO, events -> {
            var invocations = new AtomicInteger();
            run(new StormPerformanceLogFilter(10), handler(2, invocations));
            assertEquals(1, invocations.get());
            assertEquals(1, events.size());
            assertEquals(Level.INFO, events.getFirst().getLevel());
            String message = events.getFirst().getFormattedMessage();
            assertTrue(message.startsWith("SQL (GET /owners/42): 2 statements"), message);
        });
    }

    @Test
    void aRequestThatTouchesNoDatabaseSaysNothing() throws Exception {
        withScopeLogger(Level.INFO, events -> {
            run(new StormPerformanceLogFilter(10), handler(0, new AtomicInteger()));
            assertTrue(events.isEmpty());
        });
    }

    @Test
    void aThresholdReportsOnlyTheRequestsExceedingItAtWarn() throws Exception {
        withScopeLogger(Level.WARN, events -> {
            var filter = new StormPerformanceLogFilter(10, 3, null);
            run(filter, handler(2, new AtomicInteger()));
            assertTrue(events.isEmpty(), "a request below the threshold stays quiet");
            run(filter, handler(3, new AtomicInteger()));
            assertEquals(1, events.size());
            assertEquals(Level.WARN, events.getFirst().getLevel());
            assertTrue(events.getFirst().getFormattedMessage().startsWith("SQL (GET /owners/42): 3 statements"),
                    events.getFirst().getFormattedMessage());
        });
    }

    @Test
    void aDurationThresholdOfZeroReportsEveryRequestThatTouchesTheDatabase() throws Exception {
        withScopeLogger(Level.WARN, events -> {
            // Any request lasts longer than zero, so the duration threshold alone selects it; the call-site
            // variant of the constructor is exercised alongside.
            run(new StormPerformanceLogFilter(10, true, null, Duration.ZERO), handler(1, new AtomicInteger()));
            assertEquals(1, events.size());
            assertEquals(Level.WARN, events.getFirst().getLevel());
        });
    }

    @Test
    void theRequestRunsWithoutAScopeWhenNothingConsumesTheSummary() throws Exception {
        // INFO is off, so an unthresholded filter has no consumer; the request itself is unaffected.
        withScopeLogger(Level.WARN, events -> {
            var invocations = new AtomicInteger();
            run(new StormPerformanceLogFilter(10), handler(2, invocations));
            assertEquals(1, invocations.get());
            assertTrue(events.isEmpty());
        });
        // WARN is off, so a thresholded filter has no consumer either.
        withScopeLogger(Level.ERROR, events -> {
            var invocations = new AtomicInteger();
            run(new StormPerformanceLogFilter(10, 1, null), handler(2, invocations));
            assertEquals(1, invocations.get());
            assertTrue(events.isEmpty());
        });
    }

    @Test
    void aFailingRequestPropagatesItsExceptionUnchanged() throws Exception {
        withScopeLogger(Level.INFO, events -> {
            var failure = new IllegalStateException("handler failed");
            var thrown = assertThrows(IllegalStateException.class, () -> run(new StormPerformanceLogFilter(10),
                    (request, response) -> {
                        orm.query("SELECT 1").getSingleResult();
                        throw failure;
                    }));
            assertSame(failure, thrown);
            var servletFailure = new ServletException("handler failed");
            var thrownServletFailure = assertThrows(ServletException.class, () -> run(new StormPerformanceLogFilter(10),
                    (request, response) -> {
                        throw servletFailure;
                    }));
            assertSame(servletFailure, thrownServletFailure);
        });
    }
}
