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
package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.Operator.IN;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.PK;
import st.orm.StormConfig;
import st.orm.core.spi.NoPlanPerStatementSqlDialectProviderImpl;
import st.orm.core.spi.SqlDialectProvider;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedStatementTemplate;

/**
 * On H2, which binds no arrays, a statement whose IN list has rendered with eight different lengths as
 * placeholders is reported by the advice log, once, and carries the advice on its slow line where only the slow
 * statement log runs, with the count of lengths as a fact; a padded or exact template, and a deployment with
 * neither log, draw nothing.
 */
class InListShapesAdviceTest {

    private static final String LOGGER = "st.orm.sql.advice";

    public record Customer(@PK(generation = NONE) Integer id, String name) implements Entity<Integer> {}

    private static final Metamodel<Customer, Integer> ID = Metamodel.of(Customer.class, "id");

    private DataSource dataSource;

    @BeforeEach
    void database() throws SQLException {
        var h2 = new DriverManagerDataSource("jdbc:h2:mem:in_list_shapes_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = h2.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE customer (id INTEGER PRIMARY KEY, name VARCHAR(50) NOT NULL)");
            for (int i = 1; i <= 20; i++) {
                statement.execute("INSERT INTO customer (id, name) VALUES (%d, 'c%d')".formatted(i, i));
            }
        }
        dataSource = h2;
        AdviceLog.reset();
    }

    @AfterEach
    void forget() {
        AdviceLog.reset();
    }

    private ORMTemplate template(String mode) {
        return ORMTemplate.builder(dataSource)
                .config(StormConfig.of(Map.of(StormConfig.IN_LIST, mode)))
                .build();
    }

    /** Runs the lookup once per length from 1 to the given length. */
    private static void lookUpInEveryLengthTo(ORMTemplate orm, int lengths) {
        for (int length = 1; length <= lengths; length++) {
            var ids = IntStream.rangeClosed(1, length).boxed().toList();
            assertEquals(length, orm.entity(Customer.class).select().where(ID, IN, ids).getResultList().size());
        }
    }

    /** Collects the lines the advice loggers emit at INFO while the action runs. */
    private static List<String> capture(Runnable action) {
        return capture(LOGGER, Level.INFO, action);
    }

    /** Collects the lines the logger emits at the level while the action runs. */
    private static List<String> capture(String name, Level level, Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(name);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(level);
        logger.addAppender(appender);
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
    void aStatementRunInEightLengthsIsReportedOnce() {
        AdviceLog.categories(List.of("performance"));
        var orm = ORMTemplate.of(dataSource);
        var lines = capture(() -> {
            lookUpInEveryLengthTo(orm, 20);
            // A length that ran before runs through the same compiled statement, which carries the advice already.
            lookUpInEveryLengthTo(orm, 20);
        });
        assertEquals(1, lines.size(), lines.toString());
        var line = lines.getFirst();
        assertTrue(line.startsWith("SQL advice (SELECT Customer)"), line);
        assertTrue(line.contains("WHERE c.id IN (?, ?, ?, ?, ?, ?, ?, ?)"), line);
        assertTrue(line.contains("the IN list runs in many lengths"), line);
        assertTrue(line.contains("#in-list-shapes"), line);
    }

    @Test
    void aDatabaseThatKeepsNoPlanPerStatementTextDrawsNoAdvice() {
        AdviceLog.categories(List.of("performance"));
        // The filter applies to every kind of provider, so only the dialect providers are narrowed.
        var orm = ORMTemplate.of(dataSource, template -> ((PreparedStatementTemplate) template)
                .withProviderFilter(provider -> !(provider instanceof SqlDialectProvider)
                        || provider instanceof NoPlanPerStatementSqlDialectProviderImpl));
        assertEquals("NoPlanPerStatementTest", orm.dialect().name());
        assertEquals(List.of(), capture(() -> lookUpInEveryLengthTo(orm, 20)));
    }

    @Test
    void fewerLengthsDrawNoAdvice() {
        AdviceLog.categories(List.of("performance"));
        var orm = ORMTemplate.of(dataSource);
        assertEquals(List.of(), capture(() -> lookUpInEveryLengthTo(orm, 7)));
    }

    @Test
    void aSlowExecutionCarriesTheAdviceWherePerformanceAdviceIsNotReported() {
        AdviceLog.categories(List.of("correctness"));
        var orm = ORMTemplate.of(dataSource);
        Duration previousThreshold = SlowStatementLog.threshold();
        int previousLimit = SlowStatementLog.limit();
        SlowStatementLog.threshold(Duration.ofNanos(1));
        SlowStatementLog.limit(0);
        try {
            var advice = capture(() -> {
                var slow = capture("st.orm.sql.slow", Level.WARN, () -> lookUpInEveryLengthTo(orm, 10));
                // Ten slow lines; every line past the first says how many lengths the shape has run in, and the
                // lengths from the eighth on carry the advice.
                assertEquals(10, slow.size(), slow.toString());
                assertTrue(!slow.getFirst().contains("lengths: "), slow.getFirst());
                for (int i = 1; i < 10; i++) {
                    var lengths = Pattern.compile("  lengths: " + (i + 1) + "(\\R|$)");
                    assertTrue(lengths.matcher(slow.get(i)).find(), slow.get(i));
                }
                for (int i = 0; i < 7; i++) {
                    assertTrue(!slow.get(i).contains("#in-list-shapes"), slow.get(i));
                }
                for (int i = 7; i < 10; i++) {
                    assertTrue(slow.get(i).contains("the IN list runs in many lengths"), slow.get(i));
                }
            });
            assertEquals(List.of(), advice);
        } finally {
            SlowStatementLog.limit(previousLimit);
            SlowStatementLog.threshold(previousThreshold);
        }
    }

    @Test
    void thePaddedAndExactModesAreNotReported() {
        AdviceLog.categories(List.of("performance"));
        assertEquals(List.of(), capture(() -> lookUpInEveryLengthTo(template("padded"), 20)));
        assertEquals(List.of(), capture(() -> lookUpInEveryLengthTo(template("exact"), 20)));
    }

    @Test
    void nothingIsRecordedWhileNeitherLogWouldShowIt() {
        AdviceLog.categories(List.of("correctness"));
        // The transaction watch is on by default and names no statement, so it records no lengths either.
        assertTrue(SlowStatementLog.active());
        var orm = ORMTemplate.of(dataSource);
        assertEquals(List.of(), capture(() -> lookUpInEveryLengthTo(orm, 20)));
        // The lengths that ran while nothing would report them were not recorded, so one more draws nothing.
        AdviceLog.categories(List.of("performance"));
        assertEquals(List.of(), capture(() -> lookUpInEveryLengthTo(orm, 1)));
    }
}
