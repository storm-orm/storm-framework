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
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
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
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.model.Pet;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.AdviceLogTestSupport;

/**
 * The advice log reports more at each level: at {@code INFO} the advice that holds at any speed, once per kind; at
 * {@code DEBUG} all advice, once per shape; at {@code TRACE} all advice, every time.
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

    /** Collects the events the advice logger emits at the given level while the action runs. */
    private static List<ILoggingEvent> capture(Level level, Runnable action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger(LOGGER);
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previous = logger.getLevel();
        logger.setLevel(level);
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
        return List.copyOf(appender.list);
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

    /** The count behind a page, a deep offset and a distinct over a repeating join: advice about cost. */
    private void costlyStatements() {
        var owners = ORMTemplate.of(dataSource).entity(Owner.class);
        owners.select().page(Pageable.ofSize(2).sortBy(Owner_.id));
        owners.select().orderBy(Owner_.id).offset(1_000).limit(10).getResultList();
        owners.select().distinct().innerJoin(Pet.class).on(Owner.class).getResultList();
    }

    @Test
    public void infoReportsEachKindOfAdviceOnce() {
        var events = capture(Level.INFO, () -> {
            unorderedPage();
            unorderedPages();
        });
        assertEquals(1, events.size(), events.toString());
        var event = events.getFirst();
        assertEquals(Level.INFO, event.getLevel());
        var line = event.getFormattedMessage();
        assertTrue(line.startsWith("SQL advice (SELECT Owner)"), line);
        assertTrue(line.contains("\tshape "), line);
        assertTrue(line.contains("\tadvice the statement takes part of the result without an ORDER BY"), line);
        assertTrue(line.contains("See https://orm.st/docs/advice#unordered-limit"), line);
    }

    @Test
    public void infoLeavesAdviceAboutCostToTheSlowStatementLog() {
        assertEquals(List.of(), capture(Level.INFO, this::costlyStatements));
    }

    @Test
    public void debugReportsAllAdviceOncePerShape() {
        var events = capture(Level.DEBUG, () -> {
            unorderedPage();
            unorderedPage();
            unorderedPages();
            costlyStatements();
        });
        var lines = events.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertEquals(6, lines.size(), lines.toString());
        assertTrue(events.stream().allMatch(event -> event.getLevel() == Level.DEBUG), lines.toString());
        assertTrue(lines.get(3).contains("#page-count"), lines.get(3));
        assertTrue(lines.get(4).contains("#deep-offset"), lines.get(4));
        assertTrue(lines.get(5).contains("#distinct-over-join"), lines.get(5));
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
}
