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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.TransactionPropagation.REQUIRED;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import clinic.VisitService;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.boot.jdbc.DataSourceBuilder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.interceptor.MatchAlwaysTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import st.orm.AcceptAdvice;
import st.orm.TransactionOptions;
import st.orm.core.spi.TransactionRunner;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.SlowStatementLog;
import st.orm.spring.impl.SpringTransactionContext;

/**
 * A Spring-managed transaction that holds its connection idle for longer than the threshold is reported when it
 * completes, measured from Storm's first statement in it, whether Spring's interceptor, its transaction template or
 * a Storm block under Spring runs it. The advice is accepted on the service method that runs the transaction,
 * through the proxy Spring puts around the service.
 */
class IdleTransactionSpringTest {

    /** A transactional service, proxied as {@code @Transactional} proxies it. */
    public static class OrderService {
        private final ORMTemplate orm;

        OrderService(ORMTemplate orm) {
            this.orm = orm;
        }

        public void placeOrder() {
            orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
            otherWork();
        }

        @AcceptAdvice("idle-transaction")
        public void nightlyReport() {
            orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
            otherWork();
        }
    }

    private ORMTemplate orm;
    private TransactionTemplate transactionTemplate;
    private VisitService visits;
    private OrderService orders;

    @BeforeEach
    void prepare() throws SQLException {
        DataSource dataSource = DataSourceBuilder.create()
                .url("jdbc:h2:mem:idleTransactionSpring;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
                .username("sa")
                .password("")
                .driverClassName("org.h2.Driver")
                .build();
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS idle_order (id INTEGER PRIMARY KEY)");
        }
        var transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
        orm = ORMTemplate.builder(dataSource)
                .connectionProvider(new SpringConnectionProvider())
                .transactionTemplateProvider(new SpringTransactionTemplateProvider(List.of(transactionManager)))
                .build();
        // The interceptor is what @Transactional installs; a proxy over the class is the subclass Spring generates.
        var proxyFactory = new ProxyFactory(new OrderService(orm));
        proxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new MatchAlwaysTransactionAttributeSource()));
        orders = (OrderService) proxyFactory.getProxy();
        var visitProxyFactory = new ProxyFactory(new VisitService(orm));
        visitProxyFactory.addAdvice(new TransactionInterceptor(transactionManager, new MatchAlwaysTransactionAttributeSource()));
        visits = (VisitService) visitProxyFactory.getProxy();
        // The lines share one budget across the JVM's tests; none is to be held back here.
        SlowStatementLog.limit(0);
        SlowStatementLog.transactionThreshold(Duration.ofMillis(50));
    }

    @AfterEach
    void unwatch() {
        SlowStatementLog.transactionThreshold(SlowStatementLog.DEFAULT_TRANSACTION_THRESHOLD);
        SlowStatementLog.limit(SlowStatementLog.DEFAULT_LIMIT);
    }

    private static void otherWork() {
        try {
            Thread.sleep(120);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    /** Collects the slow log's warnings while the action runs. */
    private static List<String> capture(Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previous);
            appender.stop();
        }
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    void anInterceptedTransactionIsMeasuredFromStormsFirstStatement() {
        var lines = capture(orders::placeOrder);
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(line.startsWith("SQL slow (transaction): held its connection "), line);
        assertTrue(line.contains(" from its first statement, "), line);
        assertTrue(line.contains(" ms in its statement, read only, "), line);
        assertTrue(line.contains("#idle-transaction"), line);
    }

    @Test
    void theCallSiteNamesTheClassTheGeneratedProxyStandsFor() {
        var lines = capture(visits::closeVisit);
        assertEquals(1, lines.size(), lines.toString());
        // The proxy's frame is the first application frame, and the generated class has no source file.
        assertTrue(lines.getFirst().contains(", clinic.VisitService.closeVisit" + System.lineSeparator()),
                lines.getFirst());
    }

    @Test
    void nothingIsHeldWhileTransactionsAreUnwatched() {
        SlowStatementLog.transactionThreshold(null);
        var lines = capture(() -> transactionTemplate.executeWithoutResult(status -> {
            orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
            otherWork();
        }));
        assertEquals(List.of(), lines);
    }

    @Test
    void holdingNeedsTheWatchAndAnActiveSynchronization() {
        // Outside a transaction no synchronization is active to report the completion.
        assertNull(SpringTransactionContext.holdSpringTransaction());
        SlowStatementLog.transactionThreshold(null);
        transactionTemplate.executeWithoutResult(status ->
                assertNull(SpringTransactionContext.holdSpringTransaction()));
        SlowStatementLog.transactionThreshold(Duration.ofMillis(50));
        transactionTemplate.executeWithoutResult(status ->
                assertNotNull(SpringTransactionContext.holdSpringTransaction()));
    }

    @Test
    void acceptedOnTheServiceMethodBehindTheProxyItIsNotReported() {
        assertEquals(List.of(), capture(orders::nightlyReport));
    }

    @Test
    void aTransactionTemplateIsReported() {
        var lines = capture(() -> transactionTemplate.executeWithoutResult(status -> {
            orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
            otherWork();
            // A second statement is credited to the hold the first one took.
            orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
        }));
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains(" from its first statement, "), lines.getFirst());
        assertTrue(lines.getFirst().contains(" ms in its 2 statements, "), lines.getFirst());
    }

    @Test
    void aStormBlockUnderSpringIsReported() {
        var lines = capture(() -> TransactionRunner.<Void, RuntimeException>execute(
                new TransactionOptions(REQUIRED, null, null, null), transaction -> {
                    orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class);
                    otherWork();
                    return null;
                }));
        assertEquals(1, lines.size(), lines.toString());
        assertTrue(lines.getFirst().contains(" from its first statement, "), lines.getFirst());
    }

    @Test
    void nothingIsReportedBelowTheThreshold() {
        var lines = capture(() -> transactionTemplate.executeWithoutResult(status ->
                orm.query("SELECT COUNT(*) FROM idle_order").getSingleResult(Long.class)));
        assertEquals(List.of(), lines);
    }
}
