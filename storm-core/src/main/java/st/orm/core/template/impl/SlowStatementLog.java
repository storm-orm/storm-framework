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

import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.stream.Collectors.joining;
import static st.orm.core.spi.StormConfigHelper.getDuration;
import static st.orm.core.spi.StormConfigHelper.getInt;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.StormConfig;
import st.orm.core.spi.TransactionScope;
import st.orm.core.spi.TransactionTemplateProvider;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.impl.StatementListener.Handle;
import st.orm.spi.QueryContext;
import st.orm.spi.QueryContext.ExecutionKind;
import st.orm.spi.SqlCommenter;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Reports individual statement executions whose database time exceeds a threshold, under the
 * {@code st.orm.sql.slow} logger.
 *
 * <p>The statement log says what ran and a scope summary says what a call cost; neither names the one execution
 * that was slow. This log does, and it does so for every execution, inside a scope or not, on whatever thread it
 * runs: the decision is made where the statement returns from the database, on the thread that executed it.
 * That is what lets the call site be walked only for the executions that turned out slow, at no cost to the
 * rest, and what keeps the log independent of any request or coroutine boundary.</p>
 *
 * <p>Whether a statement is slow depends on its parameters as much as on its shape, so the line says which of the
 * two to look at. Each shape keeps a running baseline of what it typically costs and how many parameters it
 * typically binds; a slow line then reads either as an outlier of a normally fast shape (look at the values, or a
 * plan that changed) or as the usual cost of a uniformly slow one (look at the design). The parameter count is
 * always safe to print. Values themselves render only while the logger is at {@code TRACE}, the rule every Storm
 * SQL logger follows; the line stays at {@code WARN}.</p>
 *
 * <p>Enabled by a threshold: the {@code storm.sql_log.slow.threshold} system property on a plain JVM, or the
 * corresponding keys of the Spring and Ktor integrations, which apply their configuration through
 * {@link #threshold(Duration)}. Without a threshold no statement is measured; a statement outside a transaction
 * reads one volatile field and moves on. Lines report at {@code WARN}, so raising {@code st.orm.sql} to
 * {@code DEBUG} for per-statement logging duplicates nothing here.</p>
 *
 * <p>Transactions are watched by default: a transaction that holds its connection idle, beyond what its statements
 * spent on it, for longer than {@link #DEFAULT_TRANSACTION_THRESHOLD} is reported when it completes, with the
 * call site that began it. What is slow for a statement depends on the database and the workload, which is why the
 * statement threshold has no default; a connection held for a second while the application does other work is
 * wrong on any database, so the transaction threshold has one. {@code storm.sql_log.slow.transaction} retunes it,
 * and zero switches it off.</p>
 *
 * <p>The threshold is a live setting rather than a startup one: {@link #threshold(Duration)} and
 * {@link #limit(int)} take effect on the next execution, so an application that exposes them can switch the log
 * on while a database is degraded and off again afterwards, which is when the log is worth the most. A shape's
 * baseline is learned from the executions observed since the log was switched on, so the first lines after a
 * switch carry none.</p>
 *
 * <p>Under a degraded database every statement is slow. Lines are rate-limited per shape
 * ({@code storm.sql_log.slow.limit}, lines per shape per minute), and a line that follows suppressed
 * ones says how many it stands for, so the log names the shapes that suffer without drowning in them. An
 * execution whose shape is not tracked, because the statement has no shape of its own or because the tracked
 * shapes are exhausted, counts against one budget shared by all of them: such a line carries no baseline, but the
 * limit still holds, since flooding the log is the one thing the limit exists to prevent.</p>
 *
 * @since 1.14
 */
public final class SlowStatementLog {

    private SlowStatementLog() {
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.sql.slow");

    /**
     * Database time above which an execution is reported, in nanoseconds; zero switches the log off. Read once
     * per execution on the path every statement takes, which is the entire cost of the log while nothing is slow.
     */
    private static volatile long thresholdNanos;

    /**
     * Sets the database time above which an execution is reported; {@code null} or a non-positive duration
     * switches the log off.
     *
     * <p>Safe to call at any time, not only at startup: the write is picked up by the next execution to return
     * from the database. Switching the log on while an application runs is what lets a degraded database be
     * diagnosed without a restart, at the cost of the first lines carrying no baseline, since a shape is only
     * measured while the log is on.</p>
     *
     * @param threshold the threshold, or {@code null} for none.
     */
    public static void threshold(@Nullable Duration threshold) {
        thresholdNanos = threshold == null || threshold.isNegative() || threshold.isZero() ? 0 : threshold.toNanos();
    }

    /**
     * Returns the database time above which an execution is reported, or {@code null} while the log is off.
     *
     * @return the threshold in effect, or {@code null} for none.
     */
    public static @Nullable Duration threshold() {
        long nanos = thresholdNanos;
        return nanos == 0 ? null : Duration.ofNanos(nanos);
    }

    /**
     * The time a transaction may hold its connection beyond what its statements spent on it, applied when neither
     * the system property nor an integration sets one: one second.
     */
    public static final Duration DEFAULT_TRANSACTION_THRESHOLD = Duration.ofSeconds(1);

    /**
     * Time a transaction may hold its connection beyond what its statements spent on it, in nanoseconds; zero
     * leaves transactions unwatched. Read once per transaction where it takes its connection, and once per execution
     * where the statement threshold is read already.
     */
    private static volatile long transactionThresholdNanos = DEFAULT_TRANSACTION_THRESHOLD.toNanos();

    /**
     * Sets the time a transaction may hold its connection beyond what its statements spent on it; {@code null} or a
     * non-positive duration leaves transactions unwatched.
     *
     * <p>Safe to call at any time, as {@link #threshold(Duration)} is: a transaction that takes its connection after
     * the write is watched.</p>
     *
     * @param threshold the threshold, or {@code null} to stop watching transactions.
     */
    public static void transactionThreshold(@Nullable Duration threshold) {
        transactionThresholdNanos = threshold == null || threshold.isNegative() || threshold.isZero()
                ? 0
                : threshold.toNanos();
    }

    /**
     * Returns the time a transaction may hold its connection beyond what its statements spent on it, or {@code null}
     * while transactions are unwatched.
     *
     * @return the threshold in effect, or {@code null} for none.
     */
    public static @Nullable Duration transactionThreshold() {
        long nanos = transactionThresholdNanos;
        return nanos == 0 ? null : Duration.ofNanos(nanos);
    }

    /**
     * Returns whether transactions are measured against a threshold.
     *
     * @return {@code true} while a transaction threshold is configured.
     */
    static boolean watchesTransactions() {
        return transactionThresholdNanos != 0;
    }

    /**
     * Returns whether executions are measured against a threshold, which is what decides whether a slow line can
     * report what an execution carries.
     *
     * @return {@code true} while a statement threshold is configured.
     */
    static boolean timesStatements() {
        return thresholdNanos != 0;
    }

    /**
     * Returns whether executions or transactions are measured against a threshold.
     *
     * @return {@code true} while a threshold is configured.
     */
    public static boolean active() {
        return thresholdNanos != 0 || transactionThresholdNanos != 0;
    }

    /**
     * Returns whether an execution about to run is to be observed: every execution while a statement threshold is
     * set, and, while transactions are watched, an execution on a thread that is in a transaction, whose time the
     * transaction is credited with. An execution outside any transaction while no statement threshold is set is
     * what lets the execution path skip every other cost of the log, which is the common case with the defaults.
     *
     * @param inTransaction whether the thread is in a transaction block or a transaction of the provider, as
     *                      {@link TransactionScope#inTransaction} answers it; a block that opens its transaction at
     *                      its first statement counts, since that statement is what opens it.
     * @return {@code true} when the execution is to be observed.
     */
    static boolean observes(boolean inTransaction) {
        return thresholdNanos != 0 || (transactionThresholdNanos != 0 && inTransaction);
    }

    /** The default rate limit, applied when neither the system property nor an integration sets one. */
    public static final int DEFAULT_LIMIT = 5;

    /** Lines reported per shape per minute before the rest of the minute's lines are suppressed; zero for no limit. */
    private static volatile int linesPerMinute = DEFAULT_LIMIT;

    /**
     * Sets how many lines a shape may report per minute; the lines beyond it are suppressed and counted, and the
     * first line the shape reports afterwards says how many. Zero lifts the limit.
     *
     * <p>Safe to call at any time, as {@link #threshold(Duration)} is: the write is picked up by the next
     * execution that reports.</p>
     *
     * @param limit lines per shape per minute, or zero for no limit.
     */
    public static void limit(int limit) {
        linesPerMinute = Math.max(0, limit);
    }

    /**
     * Returns how many lines a shape may report per minute, or zero when the limit is lifted.
     *
     * @return the limit in effect.
     */
    public static int limit() {
        return linesPerMinute;
    }

    /** Executions of a shape before its baseline is trusted enough to appear on a line. */
    private static final int MIN_SAMPLES = 8;

    /** The window a shape's samples and reporting budget are counted in. */
    private static final long WINDOW_NANOS = Duration.ofMinutes(1).toNanos();

    /**
     * Shapes tracked before new ones go untracked; templates bound the count, this bounds a runaway. Not final so
     * a test can exhaust the map without generating four thousand shapes.
     */
    static int maxShapes = 4096;

    /**
     * What a shape typically costs, and the reporting budget it has left, counted per window.
     *
     * <p>The hot path only adds: the logarithm of the database time, the parameter count and one to the sample
     * count of the current window, on adders that stripe under contention and lose nothing. When a window closes,
     * the one thread that moves the window start folds the closed window's means into the baseline. The baseline
     * is therefore a geometric mean, which an outlier barely moves, over the shape's recent windows: thousands of
     * samples for a hot shape, its last few executions for a rare one.</p>
     */
    static final class ShapeStats {
        private final DoubleAdder logNanos = new DoubleAdder();
        private final LongAdder parameters = new LongAdder();
        private final LongAdder samples = new LongAdder();
        private final AtomicLong windowStart = new AtomicLong();
        /**
         * The closed windows folded together. One immutable snapshot, written by the thread that closed the last
         * window and read whenever a line is written, so a reader never sees a mean from one window with the
         * sample count of another.
         */
        private volatile @Nullable Closed closed;
        private final AtomicInteger reported = new AtomicInteger();
        private final AtomicInteger suppressed = new AtomicInteger();

        /**
         * What the shape typically costs and binds, and over how many samples that is known.
         *
         * @param samples the executions the figures rest on.
         * @param typicalNanos the geometric mean of the database time.
         * @param typicalParameters the mean parameter count.
         */
        record Baseline(long samples, long typicalNanos, int typicalParameters) {
        }

        /**
         * The closed windows folded together: sample-weighted means, with the weight of the past halved at every
         * close, so a large window dominates a small one and old windows fade.
         *
         * @param logNanos the mean logarithm of the database time.
         * @param parameters the mean parameter count.
         * @param weight the decayed sample count behind the means.
         * @param samples the executions folded in, undecayed; what the baseline is trusted by.
         */
        private record Closed(double logNanos, double parameters, double weight, long samples) {

            /** Folds a closed window of {@code count} samples in. */
            Closed fold(double meanLogNanos, double meanParameters, long count) {
                double past = weight / 2;
                double total = past + count;
                return new Closed((logNanos * past + meanLogNanos * count) / total,
                        (parameters * past + meanParameters * count) / total, total, samples + count);
            }
        }

        private static final Closed NOTHING = new Closed(0, 0, 0, 0);

        /** Folds an execution into the current window, closing the window first when its minute is up. */
        void record(long now, long nanos, int parameterCount) {
            roll(now);
            logNanos.add(Math.log(Math.max(1, nanos)));
            parameters.add(parameterCount);
            samples.increment();
        }

        /**
         * Closes the current window when its minute is up. The thread that moves the window start is the one that
         * folds the window in; the sums are read and reset one adder at a time, so a sample arriving during the
         * close may straddle two windows, which shifts a mean by one sample in thousands.
         */
        private void roll(long now) {
            long start = windowStart.get();
            if (start == 0) {
                windowStart.compareAndSet(0, now);
                return;
            }
            if (now - start <= WINDOW_NANOS || !windowStart.compareAndSet(start, now)) {
                return;
            }
            reported.set(0);
            long count = samples.sumThenReset();
            double logSum = logNanos.sumThenReset();
            long parameterSum = parameters.sumThenReset();
            if (count == 0) {
                return;
            }
            var previous = closed;
            closed = (previous == null ? NOTHING : previous)
                    .fold(logSum / count, (double) parameterSum / count, count);
        }

        /**
         * Returns the baseline an execution is judged against: the closed windows once enough executions back
         * them, otherwise the current window without the execution itself, which is what a shape has to show for
         * itself in its first minute. Read when a line is written, not per execution.
         *
         * @param nanos the database time of the execution being judged.
         * @param parameterCount its parameter count.
         * @return the baseline, or {@code null} while fewer than {@value #MIN_SAMPLES} other executions back it.
         */
        @Nullable Baseline baseline(long nanos, int parameterCount) {
            var known = closed;
            if (known != null && known.samples() >= MIN_SAMPLES) {
                return new Baseline(known.samples(), (long) Math.exp(known.logNanos()),
                        (int) Math.round(known.parameters()));
            }
            long count = samples.sum() - 1;
            if (count < MIN_SAMPLES) {
                return null;
            }
            double meanLog = (logNanos.sum() - Math.log(Math.max(1, nanos))) / count;
            double meanParameters = (double) (parameters.sum() - parameterCount) / count;
            return new Baseline(count, (long) Math.exp(meanLog), (int) Math.round(meanParameters));
        }

        /**
         * Claims a line in the current window under the given per-minute limit, returning the number of lines
         * suppressed since the last one that was reported, or {@code -1} when this one is suppressed too. A
         * limit of zero suppresses nothing.
         */
        int claim(int limit) {
            if (limit == 0) {
                return 0;
            }
            if (reported.incrementAndGet() > limit) {
                suppressed.incrementAndGet();
                return -1;
            }
            return suppressed.getAndSet(0);
        }
    }

    private static final ConcurrentHashMap<Long, ShapeStats> SHAPES = new ConcurrentHashMap<>();

    /**
     * The reporting budget every execution without stats of its own counts against: a statement whose shape could
     * not be derived, and every shape beyond {@link #maxShapes}. Its means are never read, only its budget, so
     * what the executions sharing it cost has no bearing on anything; what it carries is the guarantee that they
     * cannot report without limit.
     */
    private static volatile ShapeStats untracked = new ShapeStats();

    /** The reporting budget of the transaction lines, which have no shape; its means are never read. */
    private static volatile ShapeStats transactions = new ShapeStats();

    /** Forgets every shape's baseline and reporting budget; for tests, which share the JVM and its shapes. */
    static void reset() {
        SHAPES.clear();
        untracked = new ShapeStats();
        transactions = new ShapeStats();
    }

    /** Returns the stats of the shape, or {@code null} for an unknown shape or once the shape count is exhausted. */
    private static @Nullable ShapeStats statsOf(long shapeId) {
        if (shapeId == 0) {
            return null;
        }
        var stats = SHAPES.get(shapeId);
        if (stats != null) {
            return stats;
        }
        if (SHAPES.size() >= maxShapes) {
            return null;
        }
        return SHAPES.computeIfAbsent(shapeId, ignore -> new ShapeStats());
    }

    /**
     * Observes an execution, deciding at its return whether it is slow.
     *
     * @param context describes the execution.
     * @param parameters the values bound to the statement, read only if the execution is slow and values are
     *                   to render.
     * @param sqlCommenter the commenter whose content the statement carried, or {@code null}; asked again on the
     *                     executing thread, in the span the statement was prepared in, only for a slow execution.
     * @param advisories advice about the statement, reported on a slow execution.
     * @param indexDemands the columns the statement asks an index for, checked against the indexes of the database
     *                     only for a slow execution; {@code null} when it asks for none.
     * @param dataSource the data source the statement runs on, whose published index catalog the demands are checked
     *                   against; {@code null} for a template without one.
     * @param transactionTemplateProvider the provider whose transaction the statement runs in, if any, whose held
     *                                    connection the statement is credited to.
     * @return the handle to mark and close with the execution.
     */
    static Handle onExecute(QueryContext context,
                            List<Parameter> parameters,
                            @Nullable SqlCommenter sqlCommenter,
                            List<String> advisories,
                            @Nullable IndexDemands indexDemands,
                            @Nullable DataSource dataSource,
                            TransactionTemplateProvider transactionTemplateProvider) {
        return new SlowHandle(context, parameters, sqlCommenter, advisories, indexDemands, dataSource,
                transactionTemplateProvider);
    }

    /** One observed execution; carries state from the return of the statement to the close of the execution. */
    private static final class SlowHandle implements Handle {
        private final QueryContext context;
        private final List<Parameter> parameters;
        private final @Nullable SqlCommenter sqlCommenter;
        private final List<String> advisories;
        private final @Nullable IndexDemands indexDemands;
        private final @Nullable DataSource dataSource;
        private final TransactionTemplateProvider transactionTemplateProvider;
        private final long start = System.nanoTime();
        private @Nullable HeldConnection held;
        private long executed;
        private long databaseNanos;
        private boolean slow;
        private int suppressed;
        private ShapeStats.@Nullable Baseline baseline;
        private @Nullable String callSite;
        private @Nullable String comment;
        private @Nullable Throwable failure;

        SlowHandle(QueryContext context,
                   List<Parameter> parameters,
                   @Nullable SqlCommenter sqlCommenter,
                   List<String> advisories,
                   @Nullable IndexDemands indexDemands,
                   @Nullable DataSource dataSource,
                   TransactionTemplateProvider transactionTemplateProvider) {
            this.context = context;
            this.parameters = parameters;
            this.sqlCommenter = sqlCommenter;
            this.advisories = advisories;
            this.indexDemands = indexDemands;
            this.dataSource = dataSource;
            this.transactionTemplateProvider = transactionTemplateProvider;
        }

        @Override
        public void executed() {
            executed = System.nanoTime();
            databaseNanos = executed - start;
            if (transactionThresholdNanos != 0) {
                // The transaction the statement ran in, looked up without starting one: the statement has run, so
                // a block that was to open a transaction has opened it by now. Credited when the statement closes.
                var transactionContext = TransactionScope.peekContext(transactionTemplateProvider);
                held = transactionContext == null ? null : transactionContext.heldConnection();
            }
            long threshold = thresholdNanos;
            if (threshold == 0) {
                return;
            }
            var stats = statsOf(context.shapeId());
            // An execution the map has no room for, or that has no shape to be keyed by, still counts against a
            // budget; only its baseline is lost. Recording is what moves the window a budget is counted in, so
            // the shared budget is recorded into as a shape's own is.
            var budget = stats == null ? untracked : stats;
            // A batch binds its rows through bind variables, not the parameters the context carries, so the count
            // would describe the template rather than the execution.
            int parameterCount = context.kind() == ExecutionKind.BATCH ? 0 : parameters.size();
            budget.record(executed, databaseNanos, parameterCount);
            if (databaseNanos < threshold || !LOGGER.isWarnEnabled()) {
                return;
            }
            suppressed = budget.claim(linesPerMinute);
            if (suppressed < 0) {
                return;
            }
            baseline = stats == null ? null : stats.baseline(databaseNanos, parameterCount);
            slow = true;
            // Only a slow execution pays for the stack walk, and it pays here, where the caller is still on it.
            callSite = CallSiteCapture.callSite();
            comment = sqlCommenter == null ? null : sqlCommenter.comment().orElse(null);
        }

        @Override
        public void error(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void close(long rows, boolean exact) {
            if (held != null) {
                // A SELECT reads; anything else, a call to a procedure included, may have written and taken locks.
                held.statementClosed(System.nanoTime() - start, context.operation() != SqlOperation.SELECT);
            }
            if (!slow) {
                return;
            }
            long consumeNanos = System.nanoTime() - executed;
            String statement = context.statement().orElse("");
            // The logger's TRACE is the switch for values, as it is for the statement log; the line itself stays a
            // warning, since a slow execution is one whatever detail it is reported with.
            if (LOGGER.isTraceEnabled()) {
                statement = SqlLiterals.inline(statement, parameters);
            }
            LOGGER.warn(render(context, databaseNanos, consumeNanos, rows, exact, failure, callSite, statement,
                    baseline, parameters.size(), comment, suppressed, advisories()));
        }

        /**
         * Returns the advice the statement carries, with the missing-index advice decided now that the execution is
         * slow. The decision is kept on the statement's shape, so a shape that is slow again is not decided twice.
         */
        private List<String> advisories() {
            if (indexDemands == null) {
                return advisories;
            }
            var missing = indexDemands.advise(IndexCatalog.published(dataSource));
            if (missing.isEmpty()) {
                return advisories;
            }
            var all = new ArrayList<String>(advisories.size() + missing.size());
            all.addAll(advisories);
            all.addAll(missing);
            return all;
        }
    }

    /**
     * Renders a slow line: the headline names the operation and type the way the statement log does and states the
     * database time, the rows and the call site; the statement follows as sent, indented; a closing line carries
     * what there is to analyze it by.
     *
     * <p>A failed execution is reported too: a statement that timed out or waited on a lock until it was cancelled
     * spent its time in the database, and the line carries what a caller's exception does not, the call site and
     * the shape's baseline. It names the failure by class alone, since a driver's message may quote values.</p>
     */
    static String render(QueryContext context,
                         long databaseNanos,
                         long consumeNanos,
                         long rows,
                         boolean exact,
                         @Nullable Throwable failure,
                         @Nullable String callSite,
                         String statement,
                         ShapeStats.@Nullable Baseline baseline,
                         int parameterCount,
                         @Nullable String comment,
                         int suppressed) {
        return render(context, databaseNanos, consumeNanos, rows, exact, failure, callSite, statement, baseline,
                parameterCount, comment, suppressed, List.of());
    }

    /**
     * Renders a slow line as {@link #render(QueryContext, long, long, long, boolean, Throwable, String, String,
     * ShapeStats.Baseline, int, String, int)} does, followed by a line per piece of advice the statement carries, such
     * as a search that reads every row.
     */
    static String render(QueryContext context,
                         long databaseNanos,
                         long consumeNanos,
                         long rows,
                         boolean exact,
                         @Nullable Throwable failure,
                         @Nullable String callSite,
                         String statement,
                         ShapeStats.@Nullable Baseline baseline,
                         int parameterCount,
                         @Nullable String comment,
                         int suppressed,
                         List<String> advisories) {
        var rendered = new StringBuilder("SQL slow (").append(describe(context)).append("): ")
                .append(millis(databaseNanos)).append(" in database");
        if (failure != null) {
            rendered.append(", failed (").append(failure.getClass().getSimpleName()).append(')');
        } else {
            rendered.append(", ").append(exact ? rows : rows + "*").append(" rows");
            // A read whose rows took a while to consume says so, so its database time is not read as the whole.
            if (context.kind() == ExecutionKind.QUERY && consumeNanos >= 1_000_000L) {
                rendered.append(" read over ").append(millis(consumeNanos));
            }
        }
        if (callSite != null) {
            rendered.append(", ").append(callSite);
        }
        rendered.append(System.lineSeparator()).append(indent(statement));
        var facts = new StringBuilder();
        long shapeId = context.shapeId();
        if (shapeId != 0) {
            facts.append("shape: ").append("%016x".formatted(shapeId));
            if (baseline != null && baseline.typicalNanos() > 0) {
                facts.append(" (typically ").append(millis(baseline.typicalNanos()));
                long ratio = databaseNanos / baseline.typicalNanos();
                if (ratio >= 2) {
                    facts.append(", ").append(ratio).append("x");
                }
                facts.append(')');
            }
        }
        if (context.kind() != ExecutionKind.BATCH) {
            append(facts, "parameters: " + parameterCount);
            if (baseline != null) {
                int typical = baseline.typicalParameters();
                if (parameterCount >= 2 * Math.max(1, typical) || 2 * parameterCount <= typical) {
                    facts.append(" (typically ").append(typical).append(')');
                }
            }
            // A shape whose IN list has rendered in more than one length says in how many, as of this line.
            int lengths = InListShapes.lengths(shapeId);
            if (lengths > 1) {
                append(facts, "lengths: " + lengths);
            }
        }
        if (comment != null) {
            append(facts, "comment: " + comment);
        }
        if (suppressed > 0) {
            append(facts, "+" + suppressed + " suppressed");
        }
        if (!facts.isEmpty()) {
            rendered.append(System.lineSeparator()).append('\t').append(facts);
        }
        for (var advisory : advisories) {
            rendered.append(System.lineSeparator()).append("\tadvice: ").append(advisory);
        }
        return rendered.toString();
    }

    /**
     * Decides, as a transaction returns its connection, whether it held the connection idle for longer than the
     * threshold: the time it held the connection less the time its statements spent on it. A transaction that did
     * is reported with the call site that began it, unless the method running it accepts the advice; the stack is
     * walked for both only for a transaction that was idle too long.
     *
     * @param held the connection the transaction held.
     * @param now the moment the connection is returned.
     */
    static void transactionCompleted(HeldConnection held, long now) {
        long threshold = transactionThresholdNanos;
        if (threshold == 0) {
            return;
        }
        long heldNanos = now - held.takenNanos();
        long idleNanos = heldNanos - held.busyNanos();
        if (idleNanos < threshold || !LOGGER.isWarnEnabled()) {
            return;
        }
        // The method that runs the transaction is still on the stack while the transaction completes.
        if (AdviceAcceptance.accepts(Advice.IDLE_TRANSACTION, 0, null)) {
            return;
        }
        var budget = transactions;
        budget.record(now, idleNanos, 0);
        int suppressed = budget.claim(linesPerMinute);
        if (suppressed < 0) {
            return;
        }
        LOGGER.warn(renderTransaction(heldNanos, held.busyNanos(), idleNanos, held.statements(), held.wrote(),
                held.fromFirstStatement(), CallSiteCapture.callSite(), suppressed));
    }

    /**
     * Renders a slow transaction line: the headline states how long the transaction held its connection, how much
     * of it its statements spent, how many there were and whether they wrote, and the call site; the advice follows
     * on a line of its own, as it does under a slow statement.
     */
    static String renderTransaction(long heldNanos,
                                    long busyNanos,
                                    long idleNanos,
                                    int statements,
                                    boolean wrote,
                                    boolean fromFirstStatement,
                                    @Nullable String callSite,
                                    int suppressed) {
        var rendered = new StringBuilder("SQL slow (transaction): held its connection ").append(millis(heldNanos));
        if (fromFirstStatement) {
            rendered.append(" from its first statement");
        }
        rendered.append(", ").append(millis(busyNanos)).append(" in its ")
                .append(statements == 1 ? "statement" : statements + " statements")
                .append(", ").append(wrote ? "wrote" : "read only");
        if (callSite != null) {
            rendered.append(", ").append(callSite);
        }
        if (suppressed > 0) {
            rendered.append(System.lineSeparator()).append("\t+").append(suppressed).append(" suppressed");
        }
        rendered.append(System.lineSeparator()).append("\tadvice: ").append(Advice.IDLE_TRANSACTION.format(
                millis(heldNanos), millis(busyNanos), millis(idleNanos),
                wrote ? ", and the row locks of its writes," : ""));
        return rendered.toString();
    }

    private static void append(StringBuilder facts, String fact) {
        if (!facts.isEmpty()) {
            facts.append("  ");
        }
        facts.append(fact);
    }

    /** Describes the execution the way the statement log does: what it does, to what, and what caused it. */
    static String describe(QueryContext context) {
        String description = context.dataType()
                .map(type -> "%s %s".formatted(context.operation().name(), type.getSimpleName()))
                .orElseGet(() -> context.operation().name());
        if (context.origin() == StatementOrigin.FETCH) {
            description += ", fetch";
        }
        if (context.kind() == ExecutionKind.BATCH) {
            description += ", batch";
        }
        return description;
    }

    /**
     * Renders nanoseconds as milliseconds, with decimals below ten milliseconds so a typical sub-millisecond cost
     * keeps a value.
     */
    private static String millis(long nanos) {
        long micros = NANOSECONDS.toMicros(nanos);
        if (micros < 1_000) {
            return "%.2f ms".formatted(micros / 1000.0);
        }
        if (micros < 10_000) {
            return "%.1f ms".formatted(micros / 1000.0);
        }
        return "%d ms".formatted(micros / 1000);
    }

    static String indent(String statement) {
        return statement.lines()
                .map(line -> "\t" + line)
                .collect(joining(System.lineSeparator()));
    }

    /**
     * Applies the threshold from configuration ({@link StormConfig#defaults()}, which reads system properties).
     * The Spring and Ktor integrations apply their own configuration through the setter.
     */
    // Placed after every field it touches, since static initialization runs in textual order.
    static {
        var config = StormConfig.defaults();
        threshold(getDuration(config, StormConfig.SQL_LOG_SLOW_THRESHOLD, null));
        limit(getInt(config, StormConfig.SQL_LOG_SLOW_LIMIT, DEFAULT_LIMIT));
        transactionThreshold(getDuration(config, StormConfig.SQL_LOG_SLOW_TRANSACTION, DEFAULT_TRANSACTION_THRESHOLD));
    }
}
