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
 * The advice log: the advice a statement carries, reported under {@code st.orm.sql.advice} at {@code DEBUG} the first
 * time a statement of its shape executes, fast or slow.
 *
 * <p>Advice is decided when the statement is built, so the log adds nothing to an execution while it is off: the
 * execution path tests whether the statement carries advice, and only then whether the logger is enabled. Each piece
 * of advice is reported once per shape, so the log reads as a list of what to change rather than a stream. The record
 * of what was reported holds at most {@link #MAX_REPORTED} entries and starts over when full, which may repeat a line
 * but never changes what a line says.</p>
 *
 * @since 1.15
 */
final class AdviceLog {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.sql.advice");

    /** The most advice the log remembers having reported before it starts over. */
    static final int MAX_REPORTED = 4_096;

    private record Reported(long shapeId, String advisory) {}

    private static final Set<Reported> REPORTED = ConcurrentHashMap.newKeySet();

    private AdviceLog() {
    }

    /**
     * Returns whether the advice log is on, which the execution path asks only for a statement that carries advice.
     *
     * @return {@code true} while {@code st.orm.sql.advice} is enabled at {@code DEBUG}.
     */
    static boolean enabled() {
        return LOGGER.isDebugEnabled();
    }

    /**
     * Reports the advice of an executing statement that the log has not reported for the statement's shape before.
     *
     * @param context the execution.
     * @param statement the statement text, or {@code null} when unknown.
     * @param advisories the advice the statement carries.
     */
    static void onExecute(QueryContext context, @Nullable String statement, List<String> advisories) {
        List<String> fresh = null;
        for (var advisory : advisories) {
            if (REPORTED.size() >= MAX_REPORTED) {
                REPORTED.clear();
            }
            if (REPORTED.add(new Reported(context.shapeId(), advisory))) {
                if (fresh == null) {
                    fresh = new ArrayList<>(advisories.size());
                }
                fresh.add(advisory);
            }
        }
        if (fresh != null) {
            LOGGER.debug("{}", render(context, CallSiteCapture.callSite(), statement, fresh));
        }
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
     * Forgets what the log reported, so the next execution of each shape reports its advice again.
     */
    static void reset() {
        REPORTED.clear();
    }
}
