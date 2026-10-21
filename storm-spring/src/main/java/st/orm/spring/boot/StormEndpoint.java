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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.boot.convert.DurationStyle;
import st.orm.core.template.impl.SlowStatementLog;

/**
 * Storm's control surface on a running application, registered as the {@code storm} actuator endpoint.
 *
 * <p>It carries what Storm can be told about itself while it runs, in a section per subject; today that is the
 * SQL log, under {@code performance} and {@code slowStatement}. A reader asks one endpoint what Storm is doing
 * and tells it to do something else, the way {@code flyway} and {@code liquibase} answer for theirs.</p>
 *
 * <p>Both halves of the SQL log answer a question that arises about a deployment that is misbehaving now, which
 * is the moment a restart costs the most: the performance log says what a call cost the database, and the slow
 * statement log names the execution that made it cost that. A threshold that can only be set at startup is a
 * threshold that is wrong when it matters, so both are read per unit of work and both are settable here.</p>
 *
 * <p>What cannot be set here is {@code storm.sql-log.performance.enabled}. It decides whether the request filter
 * and the entry-point proxies exist, and neither can be installed into a context that has already refreshed. An
 * application that wants the performance log reachable in production enables it and leaves the thresholds high;
 * lowering a threshold here then costs nothing until it is lowered. The slow statement log needs no such
 * plumbing and can be switched on from off.</p>
 *
 * <p>Like every actuator endpoint it is only reachable once exposed through
 * {@code management.endpoints.web.exposure.include}. It reads and changes the diagnostic settings of a running
 * deployment, so it belongs behind the same authorization as the other write endpoints.</p>
 *
 * <p>A setting changed here holds until it is changed again or the application restarts; nothing is written back
 * to the configuration. The slow log learns what a shape typically costs while it runs, so lines reported shortly
 * after it is switched on carry no baseline. The slow threshold the performance log's duration is derived into is
 * read at startup, so retuning the performance threshold here leaves the slow threshold where it is; set both when
 * both are meant to move.</p>
 *
 * @since 1.14
 */
@Endpoint(id = "storm")
public class StormEndpoint {

    private final List<PerformanceLog.Boundary> boundaries;

    /**
     * Creates the endpoint over the boundaries the application registered, of which there are none when the
     * performance log is disabled. Constructed by the auto-configuration, since what it operates on is internal.
     *
     * @param boundaries the request filter and the entry-point post-processor, where they exist.
     */
    StormEndpoint(List<PerformanceLog.Boundary> boundaries) {
        this.boundaries = List.copyOf(boundaries);
    }

    /**
     * Returns what the slow statement log and each performance boundary report with.
     *
     * @return the settings in effect; never {@code null}.
     */
    @ReadOperation
    public Map<String, Object> settings() {
        var reported = new LinkedHashMap<String, Object>();
        reported.put("slowStatement", slowStatement());
        var performance = new LinkedHashMap<String, Object>();
        for (var boundary : boundaries) {
            performance.put(boundary.boundaryName(), describe(boundary.settings()));
        }
        reported.put("performance", performance);
        return reported;
    }

    /**
     * Sets what the slow statement log and every performance boundary report with, leaving out of the request what
     * is to stay as it is. The performance settings apply to every boundary, since the configuration gives them
     * the same settings to begin with.
     *
     * @param slowStatement database time above which a single execution is reported, such as {@code 200ms} or
     *                      {@code PT0.2S}; {@code off} switches the slow log off.
     * @param slowStatementLimit slow lines a shape may report per minute, or zero for no limit.
     * @param slowTransaction time a transaction may hold its connection beyond what its statements spent on it, such
     *                        as {@code 1s}; {@code off} stops watching transactions.
     * @param statements statements above which a unit of work is reported; {@code off} removes the threshold.
     * @param duration duration above which a unit of work is reported, such as {@code 500ms}; {@code off} removes
     *                 the threshold. With neither threshold set, every unit of work that touches the database is
     *                 reported, at INFO.
     * @param callSites whether each execution is attributed to the frame that caused it, which costs a stack walk
     *                  per execution while a scope records.
     * @param limit statements recorded per scope; the summary counts the rest regardless.
     * @return the settings the change left in effect; never {@code null}.
     * @throws IllegalArgumentException if a duration cannot be read.
     */
    @WriteOperation
    public Map<String, Object> configure(@Nullable String slowStatement,
                                         @Nullable Integer slowStatementLimit,
                                         @Nullable String slowTransaction,
                                         @Nullable String statements,
                                         @Nullable String duration,
                                         @Nullable Boolean callSites,
                                         @Nullable Integer limit) {
        if (slowStatement != null) {
            SlowStatementLog.threshold(parseDuration(slowStatement, "slowStatement"));
        }
        if (slowStatementLimit != null) {
            SlowStatementLog.limit(slowStatementLimit);
        }
        if (slowTransaction != null) {
            SlowStatementLog.transactionThreshold(parseDuration(slowTransaction, "slowTransaction"));
        }
        for (var boundary : boundaries) {
            var current = boundary.settings();
            boundary.settings(new PerformanceLog.Settings(
                    limit == null ? current.limit() : limit,
                    callSites == null ? current.callSites() : callSites,
                    statements == null ? current.statementThreshold() : parseStatements(statements),
                    duration == null ? current.durationThreshold() : parseDuration(duration, "duration")));
        }
        return settings();
    }

    /** Describes the slow statement log's settings. */
    private static Map<String, Object> slowStatement() {
        var threshold = SlowStatementLog.threshold();
        var described = new LinkedHashMap<String, Object>();
        described.put("active", threshold != null);
        described.put("threshold", threshold == null ? null : threshold.toString());
        described.put("limit", SlowStatementLog.limit());
        var transaction = SlowStatementLog.transactionThreshold();
        described.put("transaction", transaction == null ? null : transaction.toString());
        return described;
    }

    /** Describes a boundary's settings. */
    private static Map<String, Object> describe(PerformanceLog.Settings settings) {
        var described = new LinkedHashMap<String, Object>();
        described.put("statements", settings.statementThreshold());
        described.put("duration",
                settings.durationThreshold() == null ? null : settings.durationThreshold().toString());
        described.put("callSites", settings.callSites());
        described.put("limit", settings.limit());
        described.put("level", settings.thresholded() ? "WARN" : "INFO");
        return described;
    }

    /** Reads a statement threshold, with {@code off} for none. */
    private static @Nullable Integer parseStatements(String statements) {
        String text = statements.trim();
        if (isOff(text)) {
            return null;
        }
        try {
            return Integer.valueOf(text);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid statements '%s'. Use a whole number, or 'off'.".formatted(statements));
        }
    }

    /**
     * Reads a duration written as {@code off}, an ISO-8601 duration, or a duration with a unit suffix, the way
     * the {@code storm.sql-log} properties are read, so the endpoint accepts what the configuration accepts.
     */
    private static @Nullable Duration parseDuration(String value, String name) {
        String text = value.trim();
        if (isOff(text)) {
            return null;
        }
        try {
            return DurationStyle.detectAndParse(text);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "Invalid %s '%s'. Use a duration such as 200ms, 2s or PT0.2S, or 'off'."
                            .formatted(name, value));
        }
    }

    /** Returns whether the value asks for the setting to be removed rather than given a value. */
    private static boolean isOff(String text) {
        return text.isEmpty() || "off".equalsIgnoreCase(text) || "none".equalsIgnoreCase(text);
    }
}
