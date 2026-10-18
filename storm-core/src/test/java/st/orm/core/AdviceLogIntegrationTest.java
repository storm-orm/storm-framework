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
package st.orm.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.JdbcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit.jupiter.SpringExtension;
import st.orm.Pageable;
import st.orm.core.model.DesignModel.Chain0;
import st.orm.core.model.DesignModel.ChainRoot;
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.AdviceLog;
import st.orm.core.template.impl.AdviceLogTestSupport;

/**
 * The advice log reports the categories of advice the application asks for, correctness by default, each piece of
 * advice under its own logger. The level of {@code st.orm.sql.advice} decides how often: once per piece of advice at
 * {@code INFO}, once per shape at {@code DEBUG}, every time at {@code TRACE}.
 */
@ExtendWith(SpringExtension.class)
@ContextConfiguration(classes = IntegrationConfig.class)
@JdbcTest
public class AdviceLogIntegrationTest {

    private static final String LOGGER = "st.orm.sql.advice";

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    public void reset() {
        AdviceLogTestSupport.reset();
    }

    private static List<ILoggingEvent> capture(Level level, Runnable action) {
        return capture(level, Map.of(), action);
    }

    /** Collects the events the advice loggers emit, with the given levels set, while the action runs. */
    private static List<ILoggingEvent> capture(Level level, Map<String, Level> levels, Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(LOGGER);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(level);
        levels.forEach((name, value) -> context.getLogger(name).setLevel(value));
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        try {
            action.run();
        } finally {
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previous);
            levels.keySet().forEach(name -> context.getLogger(name).setLevel(null));
            appender.stop();
        }
        return List.copyOf(appender.list);
    }

    private static List<String> lines(List<ILoggingEvent> events) {
        return events.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private void unorderedPage() {
        ORMTemplate.of(dataSource).entity(Owner.class).select().limit(5).getResultList();
    }

    /** Pages of a statement without an order, each of its own shape, since the offset is part of the statement. */
    private void unorderedPages() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class);
        owners.select().offset(2).limit(2).getResultList();
        owners.select().offset(4).limit(2).getResultList();
    }

    /** The count behind a page, a deep offset and a distinct over a repeating join. */
    private void costlyStatements() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class);
        owners.select().page(Pageable.ofSize(2).sortBy(Owner_.id));
        owners.select().orderBy(Owner_.id).offset(1_000).limit(10).getResultList();
        owners.select().distinct().innerJoin(Pet.class).on(Owner.class).getResultList();
    }

    @Test
    public void correctnessIsReportedOnceAtInfo() {
        var events = capture(Level.INFO, () -> {
            unorderedPage();
            unorderedPages();
        });
        assertEquals(1, events.size(), events.toString());
        var event = events.getFirst();
        assertEquals(Level.INFO, event.getLevel());
        assertEquals(LOGGER + ".unordered-limit", event.getLoggerName());
        var line = event.getFormattedMessage();
        assertTrue(line.startsWith("SQL advice (SELECT Owner)"), line);
        assertTrue(line.contains("\tshape: "), line);
        assertTrue(line.contains("\tadvice: the statement takes part of the result without an ORDER BY"), line);
        assertTrue(line.contains("See https://orm.st/docs/advice#unordered-limit"), line);
    }

    @Test
    public void performanceIsLeftToTheSlowStatementLogByDefault() {
        assertEquals(List.of(), capture(Level.INFO, this::costlyStatements));
    }

    @Test
    public void performanceIsReportedOnceAtInfoWhenAskedFor() {
        AdviceLog.categories(List.of("correctness", "performance"));
        var events = capture(Level.INFO, () -> {
            costlyStatements();
            costlyStatements();
        });
        // A page reporting its total is a choice, advised only when slow.
        assertEquals(List.of(LOGGER + ".deep-offset", LOGGER + ".distinct-over-join"),
                events.stream().map(ILoggingEvent::getLoggerName).toList());
        assertTrue(events.stream().allMatch(event -> event.getLevel() == Level.INFO), events.toString());
    }

    @Test
    public void anUnknownCategoryIsRefused() {
        var e = assertThrows(IllegalArgumentException.class, () -> AdviceLog.categories(List.of("speed")));
        assertTrue(e.getMessage().contains("[correctness, performance, design]"), e.getMessage());
    }

    @Test
    public void debugReportsAdviceOncePerShape() {
        AdviceLog.categories(List.of("correctness", "performance"));
        var events = capture(Level.DEBUG, () -> {
            unorderedPage();
            unorderedPage();
            unorderedPages();
            costlyStatements();
        });
        var lines = lines(events);
        // Three shapes without an order, a deep offset and a distinct; a page reporting its total is a choice.
        assertEquals(5, lines.size(), lines.toString());
        assertTrue(events.stream().allMatch(event -> event.getLevel() == Level.DEBUG), lines.toString());
        assertTrue(lines.stream().noneMatch(line -> line.contains("#page-count")), lines.toString());
    }

    @Test
    public void performanceIsLeftOutAtEveryLevelUntilAskedFor() {
        assertEquals(List.of(), capture(Level.TRACE, this::costlyStatements));
    }

    @Test
    public void adviceTheApplicationAcceptsIsTurnedOff() {
        assertEquals(List.of(), capture(Level.DEBUG, Map.of(LOGGER + ".unordered-limit", Level.OFF),
                this::unorderedPage));
    }

    @Test
    public void adviceIsReportedWhileOnlyItsOwnLoggerIsOn() {
        var events = capture(Level.OFF, Map.of(LOGGER + ".unordered-limit", Level.INFO), () -> {
            unorderedPage();
            unorderedPages();
        });
        assertEquals(1, events.size(), events.toString());
    }

    @Test
    public void traceReportsAllAdviceEveryTime() {
        var events = capture(Level.TRACE, () -> {
            unorderedPage();
            unorderedPage();
        });
        assertEquals(2, events.size(), events.toString());
        assertEquals(Level.TRACE, events.getFirst().getLevel());
    }

    @Test
    public void nothingIsReportedWhileTheLoggerIsOff() {
        assertEquals(List.of(), capture(Level.WARN, this::unorderedPage));
        // Nothing was recorded as reported while off, so turning the log on reports the advice.
        assertEquals(1, capture(Level.INFO, this::unorderedPage).size());
    }

    @Test
    public void aStatementWithoutAdviceIsNotReported() {
        assertEquals(List.of(), capture(Level.TRACE, () -> ORMTemplate.of(dataSource).entity(Owner.class).select()
                .orderBy(Owner_.id).limit(5).getResultList()));
    }

    @Test
    public void designAdviceIsLeftToDevelopment() {
        assertEquals(List.of(), capture(Level.TRACE, () -> AdviceLogTestSupport.checkDesign(List.of(Chain0.class))));
        AdviceLog.development();
        assertEquals(List.of("correctness", "performance", "design"), AdviceLog.categories());
        assertEquals(1, capture(Level.INFO, () -> AdviceLogTestSupport.checkDesign(List.of(Chain0.class))).size());
    }

    @Test
    public void developmentLeavesTheCategoriesTheApplicationNamed() {
        AdviceLog.categories(List.of("correctness"));
        AdviceLog.development();
        assertEquals(List.of("correctness"), AdviceLog.categories());
    }

    @Test
    public void designAdviceIsReportedOncePerEntityAtStartup() {
        AdviceLog.development();
        var events = capture(Level.INFO, () -> {
            AdviceLogTestSupport.checkDesign(List.of(Chain0.class, Owner.class));
            AdviceLogTestSupport.checkDesign(List.of(Chain0.class));
        });
        assertEquals(1, events.size(), events.toString());
        var event = events.getFirst();
        assertEquals(Level.INFO, event.getLevel());
        assertEquals(LOGGER + ".entity-budget", event.getLoggerName());
        var line = event.getFormattedMessage();
        assertTrue(line.startsWith("SQL advice (design Chain0)"), line);
        assertTrue(line.contains("\tgraph: Chain0(Chain1(Chain2("), line);
        assertTrue(line.contains("11 joins, 22 columns beyond its own"), line);
        assertTrue(line.contains("a Ref for Chain9.next removes 2 joins and 3 columns"), line);
        assertTrue(line.endsWith("See https://orm.st/docs/advice#entity-budget"), line);
    }

    @Test
    public void designAdviceIsReportedWhenAnEntityIsFirstUsed() {
        AdviceLog.development();
        var events = capture(Level.INFO, () -> ORMTemplate.of(dataSource).model(ChainRoot.class));
        assertEquals(1, events.size(), events.toString());
        assertTrue(events.getFirst().getFormattedMessage().startsWith("SQL advice (design ChainRoot)"));
    }

    @Test
    public void designAdviceIsLeftOutWhenTheCategoryIsNot() {
        AdviceLog.categories(List.of("correctness"));
        assertEquals(List.of(), capture(Level.TRACE, () -> AdviceLogTestSupport.checkDesign(List.of(Chain0.class))));
    }
}
