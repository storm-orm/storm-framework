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

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import st.orm.core.template.impl.SlowStatementLog;

/**
 * Captures the advice statements carry, as the slow statement log reports it: every execution counts as slow while
 * the action runs, and no line is held back, so each statement's line ends with every piece of advice it carries,
 * whatever the categories or acceptance.
 */
final class AdviceCapture {

    private static final String PREFIX = "\tadvice: ";

    private AdviceCapture() {
    }

    /**
     * Runs the action and returns the advice on the slow lines of the statements it executed, in order. Advice on a
     * missing index is left out: it depends on the schema loaded for the data source, which tests share, and has
     * tests of its own.
     */
    static List<String> advisories(Supplier<?> action) {
        var context = (LoggerContext) LoggerFactory.getILoggerFactory();
        var logger = context.getLogger("st.orm.sql.slow");
        var appender = new ListAppender<ILoggingEvent>();
        appender.setContext(context);
        appender.start();
        Level previousLevel = logger.getLevel();
        Duration previousThreshold = SlowStatementLog.threshold();
        int previousLimit = SlowStatementLog.limit();
        logger.setLevel(Level.WARN);
        logger.addAppender(appender);
        // Captured events stay out of the build log.
        logger.setAdditive(false);
        SlowStatementLog.threshold(Duration.ofNanos(1));
        SlowStatementLog.limit(0);
        try {
            action.get();
        } finally {
            SlowStatementLog.limit(previousLimit);
            SlowStatementLog.threshold(previousThreshold);
            logger.detachAppender(appender);
            logger.setAdditive(true);
            logger.setLevel(previousLevel);
            appender.stop();
        }
        return appender.list.stream()
                .flatMap(event -> event.getFormattedMessage().lines())
                .filter(line -> line.startsWith(PREFIX) && !line.contains("#missing-index"))
                .map(line -> line.substring(PREFIX.length()))
                .toList();
    }
}
