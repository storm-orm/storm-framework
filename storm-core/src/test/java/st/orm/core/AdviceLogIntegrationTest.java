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
import st.orm.core.model.Owner;
import st.orm.core.model.Owner_;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.impl.AdviceLogTestSupport;

/**
 * The advice log reports the advice a statement carries at {@code DEBUG}, fast or slow, once per shape.
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

    @Test
    public void adviceIsReportedAtDebugOncePerShape() {
        var events = capture(Level.DEBUG, () -> {
            unorderedPage();
            unorderedPage();
        });
        assertEquals(1, events.size(), events.toString());
        var event = events.getFirst();
        assertEquals(Level.DEBUG, event.getLevel());
        var line = event.getFormattedMessage();
        assertTrue(line.startsWith("SQL advice (SELECT Owner)"), line);
        assertTrue(line.contains("\tshape "), line);
        assertTrue(line.contains("\tadvice the statement takes part of the result without an ORDER BY"), line);
        assertTrue(line.contains("See https://orm.st/docs/advice#unordered-limit"), line);
    }

    @Test
    public void nothingIsReportedWhileTheLoggerIsOff() {
        assertEquals(List.of(), capture(Level.INFO, this::unorderedPage));
        // Nothing was recorded as reported while off, so turning the log on reports the advice.
        assertEquals(1, capture(Level.DEBUG, this::unorderedPage).size());
    }

    @Test
    public void aStatementWithoutAdviceIsNotReported() {
        assertEquals(List.of(), capture(Level.DEBUG, () -> ORMTemplate.of(dataSource).entity(Owner.class).select()
                .orderBy(Owner_.id).limit(5).getResultList()));
    }
}
