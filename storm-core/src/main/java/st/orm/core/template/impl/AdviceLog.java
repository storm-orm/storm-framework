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

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.spi.QueryContext;

/**
 * The advice log: the advice a statement carries, reported under {@code st.orm.sql.advice}, fast or slow. Each level
 * reports more than the one above it:
 *
 * <ul>
 *   <li>{@code INFO}: the advice that holds at any speed, such as rows returned in no stated order, once per kind of
 *   advice, so an application logging at {@code INFO} sees at most a line for each;</li>
 *   <li>{@code DEBUG}: the advice about cost as well, which otherwise only the slow statement log reports, once per
 *   statement shape;</li>
 *   <li>{@code TRACE}: all advice, at every execution.</li>
 * </ul>
 *
 * <p>Advice is decided when the statement is built, so a statement without advice pays one test of whether it carries
 * any, and only a statement with advice asks whether the logger is enabled. The record of what was reported per shape
 * holds at most {@link #MAX_REPORTED} entries and starts over when full, which may repeat a line but never changes
 * what a line says.</p>
 *
 * @since 1.15
 */
final class AdviceLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.sql.advice");

    /** The most advice the log remembers having reported for a shape before it starts over. */
    static final int MAX_REPORTED = 4_096;

    private record Reported(long shapeId, Advice advice) {}

    /** The kinds of advice reported at {@code INFO}. */
    private static final Set<Advice> REPORTED_KINDS = ConcurrentHashMap.newKeySet();

    /** The advice reported per shape at {@code DEBUG}. */
    private static final Set<Reported> REPORTED = ConcurrentHashMap.newKeySet();

    private AdviceLog() {
    }

    /**
     * Returns whether the advice log is on, which the execution path asks only for a statement that carries advice.
     *
     * @return {@code true} while {@code st.orm.sql.advice} is enabled at {@code INFO}.
     */
    static boolean enabled() {
        return LOGGER.isInfoEnabled();
    }

    /**
     * Reports the advice of an executing statement that the log's level asks for and has not reported yet: at
     * {@code INFO} the advice that holds at any speed, once per kind; at {@code DEBUG} all advice, once per shape; at
     * {@code TRACE} all advice, every time.
     *
     * @param context the execution.
     * @param statement the statement text, or {@code null} when unknown.
     * @param advisories the advice the statement carries.
     */
    static void onExecute(QueryContext context, @Nullable String statement, List<String> advisories) {
        boolean trace = LOGGER.isTraceEnabled();
        boolean debug = trace || LOGGER.isDebugEnabled();
        List<String> fresh = null;
        for (var advisory : advisories) {
            var advice = Advice.of(advisory);
            if (advice == null || !(debug || advice.anySpeed()) || !(trace || firstReport(context.shapeId(), advice, debug))) {
                continue;
            }
            if (fresh == null) {
                fresh = new ArrayList<>(advisories.size());
            }
            fresh.add(advisory);
        }
        if (fresh == null) {
            return;
        }
        var line = render(context, CallSiteCapture.callSite(), statement, fresh);
        if (trace) {
            LOGGER.trace("{}", line);
        } else if (debug) {
            LOGGER.debug("{}", line);
        } else {
            LOGGER.info("{}", line);
        }
    }

    /**
     * Records the advice as reported, for its shape at {@code DEBUG} or for its kind at {@code INFO}, and returns
     * whether this is the first report.
     */
    private static boolean firstReport(long shapeId, Advice advice, boolean perShape) {
        if (!perShape) {
            return REPORTED_KINDS.add(advice);
        }
        if (REPORTED.size() >= MAX_REPORTED) {
            REPORTED.clear();
        }
        return REPORTED.add(new Reported(shapeId, advice));
    }

    /**
     * Renders an advice line: what the statement does and where it was called from, the statement as sent, its shape,
     * and a line per piece of advice.
     */
    static String render(QueryContext context,
                         @Nullable String callSite,
                         @Nullable String statement,
                         List<String> advisories) {
        var rendered = new StringBuilder("SQL advice (").append(SlowStatementLog.describe(context)).append(')');
        if (callSite != null) {
            rendered.append(": ").append(callSite);
        }
        if (statement != null) {
            rendered.append(System.lineSeparator()).append(SlowStatementLog.indent(statement));
        }
        if (context.shapeId() != 0) {
            rendered.append(System.lineSeparator()).append("\tshape ").append("%016x".formatted(context.shapeId()));
        }
        for (var advisory : advisories) {
            rendered.append(System.lineSeparator()).append("\tadvice ").append(advisory);
        }
        return rendered.toString();
    }

    /**
     * Forgets what the log reported, so the next execution reports its advice again.
     */
    static void reset() {
        REPORTED_KINDS.clear();
        REPORTED.clear();
    }
}
