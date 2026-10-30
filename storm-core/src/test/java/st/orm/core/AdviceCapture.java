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
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;
import org.slf4j.LoggerFactory;
import st.orm.core.template.impl.SlowStatementLog;

/**
 * Captures the advice statements carry, as the slow statement log reports it: every execution counts as slow while
 * the action runs, and no line is held back, so each statement's line ends with every piece of advice it carries,
 * whatever the categories or acceptance.
 */
public final class AdviceCapture {

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
        return advisoriesIn(appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList()).stream()
                .filter(advisory -> !advisory.contains("#missing-index"))
                .toList();
    }

    /**
     * Returns the advice the log entries carry, in order: each the text after {@code advice:}, with the lines that
     * continue it, which a log entry indents one level deeper, joined by {@code \n} as the advice holds them.
     *
     * @param messages the formatted log entries.
     * @return the advice.
     */
    public static List<String> advisoriesIn(List<String> messages) {
        var advisories = new ArrayList<String>();
        for (var message : messages) {
            StringBuilder current = null;
            for (var line : message.lines().toList()) {
                if (line.startsWith(PREFIX)) {
                    if (current != null) {
                        advisories.add(current.toString());
                    }
                    current = new StringBuilder(line.substring(PREFIX.length()));
                } else if (current != null && line.startsWith("\t\t")) {
                    current.append('\n').append(line.substring(2));
                } else if (current != null) {
                    advisories.add(current.toString());
                    current = null;
                }
            }
            if (current != null) {
                advisories.add(current.toString());
            }
        }
        return advisories;
    }
}
