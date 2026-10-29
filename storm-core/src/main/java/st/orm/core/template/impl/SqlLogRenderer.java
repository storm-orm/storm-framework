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

import static java.util.Comparator.comparingLong;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static st.orm.core.spi.StormConfigHelper.getInt;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.StormConfig;
import st.orm.core.template.SqlLog;
import st.orm.core.template.SqlLog.StatementLine;
import st.orm.core.template.SqlLog.Summary;
import st.orm.spi.QueryContext;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Renders a {@link Summary} as a headline plus an aligned line per distinct statement.
 *
 * <p>How a summary renders — its line width — is a property of the log viewer rather than of any scope,
 * configured like a deployment property: the {@code storm.sql_log.performance.line_width} system property on a plain JVM, or
 * the corresponding keys of the Spring and Ktor integrations, which apply their configuration through the setter
 * here.</p>
 *
 * @since 1.14
 */
public final class SqlLogRenderer {

    private SqlLogRenderer() {
    }

    /**
     * Width a summary row aims for, a property of the log viewer rather than of any scope. The statement text
     * receives what the row's other columns leave, down to a floor that keeps it identifiable. Set once at
     * startup; read at rendering only.
     */
    private static volatile int lineWidth = 200;

    /** The groups of unrecorded statements the closing line names, heaviest first. */
    private static final int UNRECORDED_GROUPS = 3;

    /** The least statement text a row keeps, whatever its other columns consume. */
    private static final int MIN_STATEMENT_WIDTH = 40;

    /**
     * Sets the width summary rows aim for, such as {@code 120} for narrow viewers or {@code 240} for wide
     * ones. The statement text elides to what the row's other columns leave. Intended to be called once at
     * startup.
     *
     * @param width the display width; at least 80.
     */
    public static void lineWidth(int width) {
        lineWidth = Math.max(80, width);
    }

    /**
     * Renders the summary as a headline plus a line per distinct statement.
     *
     * @param summary the summary to render.
     * @return the rendered summary.
     */
    public static String render(Summary summary) {
        var rendered = new StringBuilder(render(summary.name(), summary.recorded(),
                summary.count(StatementOrigin.FETCH), summary.cacheHits(),
                NANOSECONDS.toMillis(summary.databaseNanos()), NANOSECONDS.toMillis(summary.databaseElapsedNanos()),
                summary.peakConcurrency(), NANOSECONDS.toMillis(summary.durationNanos()), summary.byStatement(),
                summary.recorded() - summary.statements().size(), summary.unrecorded()));
        for (var advisory : advisories(summary)) {
            rendered.append(System.lineSeparator()).append("\tadvice: ").append(advisory);
        }
        return rendered.toString();
    }

    /** Returns whether a statement of the operation writes rows; an upsert does whatever keyword it starts with. */
    private static boolean writes(SqlOperation operation, boolean upsert) {
        return upsert || switch (operation) {
            case INSERT, UPDATE, DELETE -> true;
            default -> false;
        };
    }

    /**
     * Returns the advice on the unit of work: related writes that each committed on their own first, over the
     * recorded statements, a write outside any transaction to a type that references a type an earlier write
     * outside any transaction wrote; then what the unit of work repeated across its transactions, heaviest first by
     * the time the repeat took on the clock: references of one type fetched one statement at a time, and the same
     * single-row read or unbatched single-row write run again and again, each from {@link Advice#REPEAT_THRESHOLD}
     * executions. The repeats of a scope are counted from every execution, past the table's recording limit too; a
     * summary built from its statements alone derives them from those. The executions of a transaction that advised
     * on a repeat are that transaction's. A statement without a type is not advised on, since the advice names it.
     */
    static List<String> advisories(Summary summary) {
        Set<Class<?>> writtenOutside = new LinkedHashSet<>();
        Set<String> relatedWrites = new LinkedHashSet<>();
        var derived = summary.repeatAdvice() == null ? new Repeats(true) : null;
        for (var statement : summary.statements()) {
            var dataType = statement.dataType();
            if (dataType == null) {
                continue;
            }
            if (!statement.transactional() && writes(statement.operation(), statement.upsert())) {
                for (var target : ForeignKeyTargets.of(dataType)) {
                    // Accepted on the type written second, or on the method or class running the unit of work.
                    if (writtenOutside.contains(target)
                            && !AdviceAcceptance.accepts(Advice.WRITES_WITHOUT_TRANSACTION, 0, dataType)) {
                        relatedWrites.add(target.getSimpleName() + "\u0000" + dataType.getSimpleName());
                    }
                }
                writtenOutside.add(dataType);
            }
            if (derived != null) {
                derived.record(new Replayed(statement), statement.startNanos(), statement.executedNanos(),
                        statement.rows(), statement.exactRows(), statement.transactional(), statement.transactionId(),
                        statement.callSite());
            }
        }
        var advisories = new ArrayList<String>();
        for (var related : relatedWrites) {
            int separator = related.indexOf('\u0000');
            advisories.add(Advice.WRITES_WITHOUT_TRANSACTION.format(related.substring(0, separator),
                    related.substring(separator + 1)));
        }
        if (derived != null) {
            for (var advisory : derived.advisories(false)) {
                advisories.add(advisory.text());
            }
        } else {
            advisories.addAll(summary.repeatAdvice());
        }
        return advisories;
    }

    /**
     * Renders the summary with the full statement texts appended, one per row in row order, so a row whose
     * elided text is not enough can be matched to the statement it stands for.
     *
     * @param summary the summary to render.
     * @return the rendered summary, followed by the full statements.
     */
    public static String renderDetailed(Summary summary) {
        var rendered = new StringBuilder(render(summary));
        var lines = summary.byStatement();
        if (!lines.isEmpty()) {
            rendered.append(String.format("%n\tstatements:"));
        }
        for (var line : lines) {
            rendered.append(String.format("%n\t  ")).append(flatten(line.statement()));
        }
        return rendered.toString();
    }

    /**
     * Renders a summary as a headline plus a line per distinct statement, heaviest first.
     *
     * <p>The headline separates the time the database spent from the time the call took, so a call that is slow for
     * other reasons says so. Under a fan-out the summed database time exceeds the elapsed time, and their ratio is
     * the concurrency the work achieved. Database time is measured to the statement's return, so a streamed read
     * held open while the application consumes it does not read as database time.</p>
     *
     * <p>Statements that ran past the recording limit are counted but not retained here, so they contribute no
     * duration and no row. The database times are then lower bounds and are marked {@code +}, and a closing line reports how
     * many statements went unrecorded. The statement count and the call's own duration stay exact.</p>
     *
     * @param name what the scope covered.
     * @param statementCount the statements the call executed.
     * @param fetchCount how many of those resolved a reference.
     * @param cacheHits the reads the transaction's entity cache served without a statement.
     * @param databaseMillis the summed duration of the recorded statements.
     * @param databaseElapsedMillis the time during which at least one recorded statement was in flight.
     * @param peakConcurrency the greatest number of recorded statements in flight at once.
     * @param totalMillis how long the call took.
     * @param byStatement one line per distinct statement, in any order.
     * @param notRecorded how many statements ran past the recording limit.
     * @return the rendered summary.
     */
    public static String render(String name,
                                int statementCount,
                                int fetchCount,
                                int cacheHits,
                                long databaseMillis,
                                long databaseElapsedMillis,
                                int peakConcurrency,
                                long totalMillis,
                                List<StatementLine> byStatement,
                                int notRecorded) {
        return render(name, statementCount, fetchCount, cacheHits, databaseMillis, databaseElapsedMillis,
                peakConcurrency, totalMillis, byStatement, notRecorded, null);
    }

    /**
     * Renders a summary as {@link #render(String, int, int, int, long, long, int, long, List, int)} does, with the
     * executions past the recording limit timed: the database time then covers them and is exact, and the closing
     * line says what they spent, per operation and type for the heaviest few. The elapsed time and the concurrency
     * cover the recorded executions alone, so they stay lower bounds.
     *
     * @param name what the scope covered.
     * @param statementCount the statements the call executed.
     * @param fetchCount how many of those resolved a reference.
     * @param cacheHits the reads the transaction's entity cache served without a statement.
     * @param databaseMillis the summed duration of the statements, every one where they were timed.
     * @param databaseElapsedMillis the time during which at least one recorded statement was in flight.
     * @param peakConcurrency the greatest number of recorded statements in flight at once.
     * @param totalMillis how long the call took.
     * @param byStatement one line per distinct statement, in any order.
     * @param notRecorded how many statements ran past the recording limit.
     * @param unrecorded what those statements cost, or {@code null} when they were not timed.
     * @return the rendered summary.
     */
    public static String render(String name,
                                int statementCount,
                                int fetchCount,
                                int cacheHits,
                                long databaseMillis,
                                long databaseElapsedMillis,
                                int peakConcurrency,
                                long totalMillis,
                                List<StatementLine> byStatement,
                                int notRecorded,
                                SqlLog.@Nullable Unrecorded unrecorded) {
        var rendered = new StringBuilder("SQL (%s): %s".formatted(name, statements(statementCount)));
        if (fetchCount > 0) {
            rendered.append(", ").append(fetches(fetchCount));
        }
        if (cacheHits > 0) {
            rendered.append(", %d from cache".formatted(cacheHits));
        }
        // Statements past the recording limit contribute no duration, so the database times cover the recorded
        // ones only; mark them as lower bounds rather than let a truncated summary understate its own cost.
        var bound = notRecorded > 0 ? "+" : "";
        // Timed past the limit, the database time covers every execution and is exact.
        rendered.append(", %d%s ms in database".formatted(databaseMillis, unrecorded != null ? "" : bound));
        // Concurrency is a count of simultaneous executions, reported only when the work overlapped: summed
        // database time then exceeds the elapsed time it was compressed into, so both appear.
        if (peakConcurrency > 1) {
            rendered.append(" over %d%s ms elapsed (peak %d concurrent)".formatted(
                    databaseElapsedMillis, bound, peakConcurrency));
        }
        rendered.append(", %d ms total".formatted(totalMillis));
        int width = byStatement.stream()
                .mapToInt(line -> String.valueOf(NANOSECONDS.toMillis(line.durationNanos())).length())
                .max()
                .orElse(1);
        int executionWidth = byStatement.stream()
                .mapToInt(line -> String.valueOf(line.executions()).length())
                .max()
                .orElse(1);
        int typeWidth = byStatement.stream()
                .mapToInt(line -> line.dataType().length())
                .max()
                .orElse(1);
        int rowsWidth = byStatement.stream()
                .mapToInt(line -> rowsLabel(line).length())
                .max()
                .orElse(1);
        // The identifying columns align and lead; the free-form statement text comes last, where raggedness
        // does not break the columns after it. The max, fetch and call-site columns appear only when any row has
        // one.
        int maxWidth = byStatement.stream()
                .mapToInt(line -> maxLabel(line).length())
                .max()
                .orElse(0);
        boolean anyFetch = byStatement.stream().anyMatch(StatementLine::fetch);
        int siteWidth = byStatement.stream()
                .mapToInt(line -> siteLabel(line).length())
                .max()
                .orElse(0);
        // The row aims for the configured line width: the statement text receives what the fixed columns and
        // the row's own suffixes leave, down to a floor that keeps it identifiable.
        int prefixWidth = width + 5 + (maxWidth > 0 ? maxWidth + 2 : 0) + executionWidth + 3 + rowsWidth + 6
                + typeWidth + 2 + (anyFetch ? 7 : 0) + (siteWidth > 0 ? siteWidth + 2 : 0);
        // Ordering is presentation, so the renderer owns it rather than trusting the order it was handed.
        for (var line : byStatement.stream()
                .sorted(comparingLong(StatementLine::durationNanos).reversed())
                .toList()) {
            rendered.append(String.format("%n\t%" + width + "d ms", NANOSECONDS.toMillis(line.durationNanos())));
            if (maxWidth > 0) {
                rendered.append(String.format("  %-" + maxWidth + "s", maxLabel(line)));
            }
            rendered.append(String.format(
                    "  %" + rowsWidth + "s rows  %" + executionWidth + "dx  %-" + typeWidth + "s",
                    rowsLabel(line),
                    line.executions(),
                    line.dataType()));
            if (anyFetch) {
                rendered.append(String.format("  %-5s", line.fetch() ? "fetch" : ""));
            }
            if (siteWidth > 0) {
                rendered.append(String.format("  %-" + siteWidth + "s", siteLabel(line)));
            }
            int suffixWidth = line.variants() > 1 ? 12 + String.valueOf(line.variants()).length() : 0;
            int statementBudget = Math.max(MIN_STATEMENT_WIDTH, lineWidth - prefixWidth - suffixWidth);
            rendered.append("  ").append(elide(line.statement(), statementBudget));
            if (line.variants() > 1) {
                rendered.append(" (%d variants)".formatted(line.variants()));
            }
        }
        if (notRecorded > 0) {
            rendered.append("%n\t(%s not recorded".formatted(statements(notRecorded)));
            if (unrecorded != null) {
                rendered.append(", %d ms".formatted(NANOSECONDS.toMillis(unrecorded.nanos())));
                var groups = unrecorded.groups();
                for (int i = 0; i < Math.min(UNRECORDED_GROUPS, groups.size()); i++) {
                    var group = groups.get(i);
                    rendered.append(i == 0 ? ": " : ", ").append(group.executions()).append(' ')
                            .append(group.operation().name());
                    if (group.type() != null) {
                        rendered.append(" of ").append(group.type());
                    } else if (group.otherTypes()) {
                        rendered.append(" of other types");
                    }
                    rendered.append(' ').append(NANOSECONDS.toMillis(group.nanos())).append(" ms");
                }
                if (groups.size() > UNRECORDED_GROUPS) {
                    rendered.append(", +").append(groups.size() - UNRECORDED_GROUPS).append(" more");
                }
            }
            rendered.append(')');
        }
        return rendered.toString();
    }

    /**
     * Returns the max column content: the slowest execution of the group when it stands out from the group's
     * average, empty otherwise. A group's total ranks it, and a total is what one slow execution among many cheap
     * ones and a uniformly slow statement have in common; the max is what tells them apart, so it shows only when
     * the two readings differ: at least twice the average, and at least a millisecond.
     */
    private static String maxLabel(StatementLine line) {
        if (line.executions() < 2) {
            return "";
        }
        long maxMillis = NANOSECONDS.toMillis(line.maxNanos());
        if (maxMillis < 1 || line.maxNanos() * line.executions() < 2 * line.durationNanos()) {
            return "";
        }
        return "max %d ms".formatted(maxMillis);
    }

    /** Returns the row-count column content: the count, marked {@code *} when it is a lower bound. */
    private static String rowsLabel(StatementLine line) {
        return line.exactRows() ? String.valueOf(line.rows()) : line.rows() + "*";
    }

    /** Returns the call-site column content for the line, empty when the scope recorded none. */
    private static String siteLabel(StatementLine line) {
        if (line.callSite() == null) {
            return "";
        }
        return line.sites() > 1
                ? "%s (+%d sites)".formatted(line.callSite(), line.sites() - 1)
                : line.callSite();
    }

    /**
     * Returns the statement on one line, elided from the middle so a summary stays scannable: the head names the
     * operation and columns, the tail carries the FROM and WHERE clauses that identify what the statement does.
     */
    private static String elide(String statement, int width) {
        // A run of placeholders says nothing its length does not; collapsing it leaves the elision budget to
        // the clauses that identify the statement. Display only: the detailed rendering keeps the exact text.
        String flattened = flatten(statement).replaceAll("\\?(?:, \\?){3,}", "?, \u2026, ?");
        if (flattened.length() <= width) {
            return flattened;
        }
        int head = width / 3;
        int tail = width - head - 1;
        return flattened.substring(0, head) + "\u2026" + flattened.substring(flattened.length() - tail);
    }

    /**
     * Returns the statement on one line, joining it on the line breaks it was rendered with.
     *
     * <p>A nested subquery is rendered on lines of its own, which leaves its closing parenthesis opening a line.
     * Joining every break with a space reads that back as {@code WHERE id = ? ) ) x}, so a break between characters
     * that belong together closes up instead.</p>
     */
    private static String flatten(String statement) {
        var flattened = new StringBuilder();
        statement.lines()
                .map(String::strip)
                .filter(line -> !line.isEmpty())
                .forEach(line -> {
                    if (!flattened.isEmpty()
                            && separated(flattened.charAt(flattened.length() - 1), line.charAt(0))) {
                        flattened.append(' ');
                    }
                    flattened.append(line);
                });
        return flattened.toString();
    }

    /** Returns whether a line break between the two characters reads as a space rather than as nothing at all. */
    private static boolean separated(char before, char after) {
        return before != '(' && after != ')' && after != ',';
    }

    private static String statements(int count) {
        return "%d statement%s".formatted(count, count == 1 ? "" : "s");
    }

    private static String fetches(int count) {
        return "%d fetch%s".formatted(count, count == 1 ? "" : "es");
    }

    /**
     * Applies the display settings from configuration ({@link StormConfig#defaults()}, which reads system
     * properties). How the log renders is a property of the deployment, so it is configured like one; the Spring
     * and Ktor integrations apply their own configuration through the setters.
     */
    // Placed after every field it touches, since static initialization runs in textual order.
    static {
        lineWidth(getInt(StormConfig.defaults(), StormConfig.SQL_LOG_LINE_WIDTH, 200));
    }

    /**
     * A recorded statement replayed as the context of its execution, so a summary built from its statements alone
     * derives the repeats the scope would have counted. What the record does not keep, how the statement was
     * executed and what its result maps to, takes the context's defaults.
     */
    private record Replayed(SqlLog.Statement recorded) implements QueryContext {
        @Override
        public SqlOperation operation() {
            return recorded.operation();
        }

        @Override
        public boolean upsert() {
            return recorded.upsert();
        }

        @Override
        public Optional<Class<? extends Data>> dataType() {
            return Optional.ofNullable(recorded.dataType());
        }

        @Override
        public ExecutionKind kind() {
            return recorded.operation() == SqlOperation.SELECT ? ExecutionKind.QUERY : ExecutionKind.UPDATE;
        }

        @Override
        public StatementOrigin origin() {
            return recorded.origin();
        }

        @Override
        public long shapeId() {
            return recorded.shapeId();
        }

        @Override
        public OptionalInt batchSize() {
            return OptionalInt.empty();
        }

        @Override
        public boolean transactional() {
            return recorded.transactional();
        }

        @Override
        public long transactionId() {
            return recorded.transactionId();
        }

        @Override
        public Optional<String> statement() {
            return Optional.of(recorded.statement());
        }
    }
}
