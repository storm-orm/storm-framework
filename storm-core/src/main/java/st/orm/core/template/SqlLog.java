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
package st.orm.core.template;

import static java.util.Comparator.comparingLong;
import static java.util.Objects.requireNonNull;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.Data;
import st.orm.PersistenceException;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.impl.CallSiteCapture;
import st.orm.core.template.impl.SqlInterceptorManager;
import st.orm.core.template.impl.SqlLogRenderer;
import st.orm.core.template.impl.StatementListener;
import st.orm.spi.QueryContext;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Records the statements a call executes, so a unit of work can be judged by what it cost the database.
 *
 * <p>A scope answers what a single call did as a whole: how many statements it took, which of them resolved
 * references rather than being asked for, and which statement carried the weight. That total is invisible from any
 * one statement.</p>
 *
 * <p>A scope covers whatever executes inside it, whichever repository, query builder or template issued the
 * statement, so it can wrap the entry point of a request rather than a single repository.</p>
 *
 * <p><strong>Cost when inactive is zero.</strong> A scope registers on the interceptor chain that every statement
 * already walks, so a statement executed with no scope open reads a single counter and stops. The recording cost
 * is paid only while a scope is open.</p>
 *
 * <p>A scope is bound to the thread that opened it, so it records the statements of a blocking call. Statements
 * issued after a suspension that resumed on another thread fall outside it.</p>
 *
 * <p>How summaries render — line width, call-site skips — is a property of the deployment, configured rather
 * than programmed: the {@code storm.sql_log.performance.line_width} and {@code storm.sql_log.call_site_skip} system
 * properties on a plain JVM, or the corresponding keys of the Spring and Ktor integrations.</p>
 *
 * @since 1.13
 */
public final class SqlLog {

    private SqlLog() {
    }

    /**
     * The logger every scope summary reports through. The logger is the switch: a summary is recorded only while
     * it is enabled and read only through it, so what a scope observed never becomes API surface an application
     * couples to.
     */
    private static final Logger REPORT_LOGGER = LoggerFactory.getLogger("st.orm.sql.perf");

    /**
     * Returns whether a reported summary reaches anything, so a caller can skip opening a scope whose summary
     * nothing consumes.
     *
     * @return whether the {@code st.orm.sql.perf} logger is enabled at {@code INFO}.
     */
    public static boolean reporting() {
        return REPORT_LOGGER.isInfoEnabled();
    }

    /**
     * Reports a summary under the {@code st.orm.sql.perf} logger: at {@code INFO}, with the full statement texts
     * appended while the logger is at {@code TRACE}. A summary without statements says nothing worth a line and is
     * not reported.
     *
     * <p>The texts follow at {@code TRACE} rather than {@code DEBUG} because this logger is a child of
     * {@code st.orm.sql}: raising that to {@code DEBUG} for per-statement logging would otherwise raise this one
     * with it and repeat every statement the statement logger had just written on its own line.</p>
     *
     * @param summary the summary to report.
     */
    public static void report(Summary summary) {
        if (summary.statementCount() == 0) {
            return;
        }
        REPORT_LOGGER.info("{}", REPORT_LOGGER.isTraceEnabled() ? summary.toDetailedString() : summary);
    }

    /**
     * A statement recorded by a scope.
     *
     * <p>Three instants bound an execution. It starts when the statement is prepared, it is executed when the
     * statement returns from the database (a result set opened, an update count reported, a batch acknowledged),
     * and it ends when its observation closes: at once for an update or a single-row read, at the close of the
     * stream for a streamed read. Database time is the first interval; for a stream, the second is fetch round
     * trips interleaved with the application consuming rows, which is not the database's cost to carry.</p>
     *
     * @param operation what the statement does.
     * @param dataType the entity or projection it targets, or {@code null} when it targets none.
     * @param origin what caused it to execute.
     * @param statement the statement text, with placeholders.
     * @param startNanos when the execution started.
     * @param executedNanos when the statement returned from the database.
     * @param endNanos when the execution completed.
     * @param shapeId the identity of the statement's shape, or {@code 0} when unknown.
     * @param callSite the application frame the execution came from, or {@code null} when not recorded.
     * @param rows the rows the execution produced or affected; a lower bound when not exact.
     * @param exactRows whether that count is exact.
     */
    public record Statement(SqlOperation operation,
                            Class<? extends Data> dataType,
                            StatementOrigin origin,
                            String statement,
                            long startNanos,
                            long executedNanos,
                            long endNanos,
                            long shapeId,
                            @Nullable String callSite,
                            long rows,
                            boolean exactRows) {

        /** Returns the time the execution spent in the database: from prepare to the statement's return. */
        public long durationNanos() {
            return executedNanos - startNanos;
        }

        /**
         * Returns the time between the statement's return and the execution's completion: zero for an update or
         * a single-row read, the consumption of the stream for a streamed read.
         */
        public long consumeNanos() {
            return endNanos - executedNanos;
        }
    }

    /**
     * What a call cost the database.
     */
    public record Summary(String name,
                          List<Statement> statements,
                          int recorded,
                          int cacheHits,
                          long durationNanos) {

        public Summary {
            requireNonNull(name, "name");
            statements = List.copyOf(statements);
        }

        /**
         * Returns the number of statements the call executed, including any beyond the recording limit.
         *
         * @return the statement count.
         */
        public int statementCount() {
            return recorded;
        }

        /**
         * Returns the number of statements the call executed with the given origin.
         *
         * <p>A {@link StatementOrigin#FETCH} count reports what resolving references cost: one statement per
         * reference the call read that no query had already brought along. A reference the transaction's entity
         * cache served issues no statement, so this counts distinct cache misses.</p>
         *
         * @param origin the origin to count.
         * @return the matching statement count, over the recorded statements.
         */
        public int count(StatementOrigin origin) {
            return (int) statements.stream().filter(s -> s.origin() == origin).count();
        }

        /**
         * Returns the reads the transaction's entity cache served without a statement: a reference resolving to
         * an entity the transaction had already read, or an identity lookup at {@code REPEATABLE_READ} and
         * above. The fetch count reports the cache misses; this is what the cache saved.
         *
         * @return the cache-served read count.
         */
        public int cacheHits() {
            return cacheHits;
        }

        /**
         * Returns whether statements were executed beyond the ones recorded.
         *
         * @return {@code true} when the recording limit truncated the statements.
         */
        public boolean truncated() {
            return recorded > statements.size();
        }

        /**
         * Returns the total time the executions spent in the database, which under concurrency exceeds the
         * elapsed time.
         *
         * @return the summed statement duration, in nanoseconds.
         */
        public long databaseNanos() {
            return statements.stream().mapToLong(Statement::durationNanos).sum();
        }

        /**
         * Returns the time during which at least one statement was in flight, which is what the summed duration was
         * compressed into.
         *
         * @return the union of the execution intervals, in nanoseconds.
         */
        public long databaseElapsedNanos() {
            var intervals = statements.stream()
                    .sorted(comparingLong(Statement::startNanos))
                    .toList();
            long elapsed = 0;
            long openedAt = 0;
            long closesAt = 0;
            for (var interval : intervals) {
                if (interval.startNanos() > closesAt) {
                    elapsed += closesAt - openedAt;
                    openedAt = interval.startNanos();
                }
                closesAt = Math.max(closesAt, interval.executedNanos());
            }
            return elapsed + (closesAt - openedAt);
        }

        /**
         * Returns the greatest number of statements that were in flight at once, which tells a fan-out that ran in
         * parallel from one that did not.
         *
         * @return the peak number of concurrent executions.
         */
        public int peakConcurrency() {
            long[] starts = statements.stream().mapToLong(Statement::startNanos).sorted().toArray();
            long[] ends = statements.stream().mapToLong(Statement::executedNanos).sorted().toArray();
            int peak = 0;
            int inFlight = 0;
            int closing = 0;
            for (long start : starts) {
                while (closing < ends.length && ends[closing] <= start) {
                    inFlight--;
                    closing++;
                }
                inFlight++;
                peak = Math.max(peak, inFlight);
            }
            return peak;
        }

        /**
         * Returns the recorded statements grouped by their text, heaviest first, which is the view that answers
         * where the time went: a statement run many times cheaply outranks one slow statement when it cost more in
         * total.
         *
         * @return one line per distinct statement, ordered by total time.
         */
        public List<StatementLine> byStatement() {
            // Statements group by shape, so a collection parameter that expands to a different number of
            // placeholders per execution stays one group; text is the fallback for statements without one.
            Map<Object, Group> groups = new LinkedHashMap<>();
            for (var statement : statements) {
                Object key = statement.shapeId() != 0 ? statement.shapeId() : statement.statement();
                var group = groups.computeIfAbsent(key, ignore -> new Group(statement.statement(),
                        statement.dataType()));
                group.executions++;
                group.durationNanos += statement.durationNanos();
                group.maxNanos = Math.max(group.maxNanos, statement.durationNanos());
                group.fetch |= statement.origin() == StatementOrigin.FETCH;
                group.texts.add(statement.statement());
                group.rows += statement.rows();
                group.exactRows &= statement.exactRows();
                if (statement.callSite() != null) {
                    group.sites.add(statement.callSite());
                }
            }
            return groups.values().stream()
                    .map(group -> new StatementLine(group.text, group.dataTypeName, group.fetch, group.executions,
                            group.texts.size(), group.durationNanos, group.maxNanos,
                            group.sites.isEmpty() ? null : group.sites.iterator().next(), group.sites.size(),
                            group.rows, group.exactRows))
                    .sorted(comparingLong(StatementLine::durationNanos).reversed())
                    .toList();
        }

        /** One statement group under construction; the first execution seen represents the group. */
        private static final class Group {
            final String text;
            final String dataTypeName;
            final java.util.Set<String> texts = new java.util.HashSet<>();
            final java.util.Set<String> sites = new java.util.LinkedHashSet<>();
            boolean fetch;
            int executions;
            long durationNanos;
            long maxNanos;
            long rows;
            boolean exactRows = true;

            Group(String text, @Nullable Class<? extends Data> dataType) {
                this.text = text;
                this.dataTypeName = dataType == null ? "-" : dataType.getSimpleName();
            }
        }

        /**
         * Renders the summary with the full statement texts appended, one per row in row order, so a row whose
         * elided text is not enough can be matched to the statement it stands for.
         *
         * @return the rendered summary, followed by the full statements.
         */
        public String toDetailedString() {
            return SqlLogRenderer.renderDetailed(this);
        }

        /**
         * Renders the summary as a headline plus a line per distinct statement.
         */
        @Override
        public String toString() {
            return SqlLogRenderer.render(this);
        }
    }

    /**
     * One distinct statement within a scope, with what it cost in total.
     *
     * @param statement a representative of the group's text, with placeholders.
     * @param dataType the simple name of the entity or projection the statement targets, or {@code -} when it
     *                 targets none.
     * @param fetch whether it resolved a reference.
     * @param executions how many times it ran.
     * @param variants how many distinct texts the group covers; above one, a collection parameter expanded to a
     *                 different number of placeholders per execution.
     * @param durationNanos the summed database time of those executions.
     * @param maxNanos the database time of the slowest of them, which the rendering shows when it stands out from
     *                 the group's average: one slow execution among many cheap ones is a different finding from
     *                 a statement that is uniformly slow.
     * @param callSite the application frame the executions came from, or {@code null} when the scope does not
     *                 record call sites; the first seen when a group covers several.
     * @param sites how many distinct call sites the group covers.
     * @param rows the rows the group's executions produced or affected, in total.
     * @param exactRows whether that count is exact; when a driver declined to report a batch entry's count or a
     *                  stream closed before its end, the count is a lower bound and the rendering marks it
     *                  {@code *}.
     */
    public record StatementLine(String statement,
                                String dataType,
                                boolean fetch,
                                int executions,
                                int variants,
                                long durationNanos,
                                long maxNanos,
                                @Nullable String callSite,
                                int sites,
                                long rows,
                                boolean exactRows) {
    }

    /** Statements recorded per scope before recording stops, keeping a runaway call from retaining the lot. */
    private static final int DEFAULT_LIMIT = 200;

    /**
     * Runs the action, recording the statements it executes, and hands the result to the given consumer.
     *
     * @param name what the scope covers, used to label the summary.
     * @param action the action to run.
     * @param onSummary receives the summary once the action completes, normally or not.
     * @param <T> the result type.
     * @return the action's result.
     * @throws Exception whatever the action throws.
     */
    public static <T extends @Nullable Object> T recordThrowing(String name,
                                       Callable<T> action,
                                       Consumer<Summary> onSummary) throws Exception {
        return recordThrowing(name, DEFAULT_LIMIT, action, onSummary);
    }

    /**
     * Runs the action, recording up to {@code limit} of the statements it executes.
     *
     * @param name what the scope covers, used to label the summary.
     * @param limit the number of statements to record; the summary still counts the rest.
     * @param action the action to run.
     * @param onSummary receives the summary once the action completes, normally or not.
     * @param <T> the result type.
     * @return the action's result.
     * @throws Exception whatever the action throws.
     */
    public static <T extends @Nullable Object> T recordThrowing(String name,
                                       int limit,
                                       Callable<T> action,
                                       Consumer<Summary> onSummary) throws Exception {
        return recordThrowing(name, limit, false, action, onSummary);
    }

    /**
     * Runs the action, recording up to {@code limit} statements and, when {@code callSites} is set, the
     * application frame each execution came from, which costs a stack walk per execution.
     *
     * @param name what the scope covers, used to label the summary.
     * @param limit the number of statements to record; the summary counts the rest regardless.
     * @param callSites whether to record call sites.
     * @param action the action to run.
     * @param onSummary receives the summary once the action completes, normally or not.
     * @param <T> the result type.
     * @return the action's result.
     * @throws Exception whatever the action throws.
     */
    public static <T extends @Nullable Object> T recordThrowing(String name,
                                       int limit,
                                       boolean callSites,
                                       Callable<T> action,
                                       Consumer<Summary> onSummary) throws Exception {
        requireNonNull(name, "name");
        requireNonNull(action, "action");
        requireNonNull(onSummary, "onSummary");
        var recorder = new Recorder(limit, callSites);
        long started = System.nanoTime();
        try {
            return SqlInterceptorManager.listen(recorder).call(action);
        } finally {
            // A call that failed is worth summarizing too: the statements leading up to it are the evidence.
            long elapsed = System.nanoTime() - started;
            onSummary.accept(new Summary(name, recorder.statements(), recorder.recorded.get(),
                    recorder.cacheHits.get(), elapsed));
        }
    }

    /**
     * Runs the action, recording the statements it executes.
     *
     * @param name what the scope covers, used to label the summary.
     * @param action the action to run.
     * @param onSummary receives the summary once the action completes, normally or not.
     * @param <T> the result type.
     * @return the action's result.
     */
    public static <T extends @Nullable Object> T record(String name,
                               Supplier<T> action,
                               Consumer<Summary> onSummary) {
        return record(name, DEFAULT_LIMIT, action, onSummary);
    }

    /**
     * Runs the action, recording up to {@code limit} of the statements it executes.
     *
     * <p>For an action that throws a checked exception, use
     * {@link #recordThrowing(String, int, Callable, Consumer)}.</p>
     *
     * @param name what the scope covers, used to label the summary.
     * @param limit the number of statements to record; the summary still counts the rest.
     * @param action the action to run.
     * @param onSummary receives the summary once the action completes, normally or not.
     * @param <T> the result type.
     * @return the action's result.
     */
    public static <T extends @Nullable Object> T record(String name,
                               int limit,
                               Supplier<T> action,
                               Consumer<Summary> onSummary) {
        try {
            return recordThrowing(name, limit, action::get, onSummary);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            // A Supplier declares none, so anything checked arriving here came from the interceptor chain.
            throw new PersistenceException(e);
        }
    }

    /**
     * Opens a scope on the calling thread, closed with try-with-resources.
     *
     * <pre>{@code
     * var scope = SqlLog.open("importOwners");
     * try (scope) {
     *     ...
     * }
     * // scope.summary() reports what the block cost
     * }</pre>
     *
     * <p>The scope must be closed on the thread that opened it, which a try-with-resources block guarantees.</p>
     *
     * @param name what the scope covers, used to label the summary.
     * @return the open scope.
     */
    public static Scope open(String name) {
        return open(name, DEFAULT_LIMIT);
    }

    /**
     * Opens a scope on the calling thread, recording up to {@code limit} statements.
     *
     * @param name what the scope covers, used to label the summary.
     * @param limit the number of statements to record; the summary counts the rest regardless.
     * @return the open scope.
     */
    public static Scope open(String name, int limit) {
        return open(name, limit, false);
    }

    /**
     * Opens a scope that additionally attributes each execution to the application frame that caused it, which
     * costs a stack walk per execution while the scope records.
     *
     * @param name what the scope covers, used to label the summary.
     * @param limit the number of statements to record; the summary counts the rest regardless.
     * @param callSites whether to record call sites.
     * @return the open scope.
     */
    public static Scope open(String name, int limit, boolean callSites) {
        return new Scope(requireNonNull(name, "name"), limit, callSites);
    }


    /**
     * A scope opened with {@link #open}, reporting its summary once closed.
     */
    public static final class Scope implements AutoCloseable {
        private final String name;
        private final Recorder recorder;
        private final AutoCloseable detach;
        private final long started;
        private Summary summary;

        private Scope(String name, int limit, boolean callSites) {
            this.name = name;
            this.recorder = new Recorder(limit, callSites);
            this.detach = SqlInterceptorManager.attach(recorder);
            this.started = System.nanoTime();
        }

        /**
         * Returns what the scope observed. Available once the scope is closed.
         *
         * @return the summary.
         * @throws IllegalStateException if the scope is still open.
         */
        public Summary summary() {
            if (summary == null) {
                throw new IllegalStateException("The scope is still open; the summary is available after close().");
            }
            return summary;
        }

        @Override
        public void close() {
            if (summary != null) {
                return;
            }
            long elapsed = System.nanoTime() - started;
            try {
                detach.close();
            } catch (Exception e) {
                throw new PersistenceException(e);
            }
            summary = new Summary(name, recorder.statements(), recorder.recorded.get(),
                    recorder.cacheHits.get(), elapsed);
        }
    }

    /**
     * Creates a recorder a caller can install itself, for a facade that binds the scope to its own execution
     * model rather than to the calling thread.
     *
     * @param limit the number of statements to record.
     * @return the recorder to install as a listener.
     */
    public static Recorder recorder(int limit) {
        return new Recorder(limit, false);
    }

    /**
     * Creates a recorder that additionally attributes each execution to the application frame that caused it.
     *
     * @param limit the number of statements to record.
     * @param callSites whether to record call sites; costs a stack walk per execution while the scope records.
     * @return the recorder to install as a listener.
     */
    public static Recorder recorder(int limit, boolean callSites) {
        return new Recorder(limit, callSites);
    }

    /**
     * Builds the summary of what the given recorder observed.
     *
     * @param name what the scope covered.
     * @param recorder the recorder that observed the executions.
     * @param durationNanos how long the scope was open.
     * @return the summary.
     */
    public static Summary summary(String name, Recorder recorder, long durationNanos) {
        return new Summary(name, recorder.statements(), recorder.recorded.get(),
                recorder.cacheHits.get(), durationNanos);
    }

    /**
     * Accumulates the statements of one scope.
     */
    public static final class Recorder implements StatementListener {
        /**
         * One slot per execution, filled when the statement returns from the database and completed when the
         * execution closes. A statement is what it cost the database as soon as the database has answered, so a
         * read whose stream is still open when the scope closes is in the summary with its database time and the
         * rows read so far, rather than missing from it.
         */
        private final Queue<Slot> statements = new ConcurrentLinkedQueue<>();
        private final int limit;
        private final boolean callSites;
        private final AtomicInteger recorded = new AtomicInteger();
        private final AtomicInteger cacheHits = new AtomicInteger();

        Recorder(int limit, boolean callSites) {
            this.limit = limit;
            this.callSites = callSites;
        }

        /** Returns the statements as they stand: completed ones as closed, open ones as of the database's return. */
        List<Statement> statements() {
            var snapshot = new java.util.ArrayList<Statement>();
            for (var slot : statements) {
                var statement = slot.statement;
                if (statement != null) {
                    snapshot.add(statement);
                }
            }
            return snapshot;
        }

        /** The statement of one execution; written at the database's return, rewritten at close. */
        private static final class Slot implements Handle {
            private final Recorder recorder;
            private final long start = System.nanoTime();
            private final SqlOperation operation;
            private final @Nullable Class<? extends Data> dataType;
            private final StatementOrigin origin;
            private final String text;
            private final long shapeId;
            private final @Nullable String callSite;
            private long executed;
            private volatile @Nullable Statement statement;

            Slot(Recorder recorder, QueryContext context, @Nullable String callSite) {
                this.recorder = recorder;
                this.operation = context.operation();
                this.dataType = context.dataType().orElse(null);
                this.origin = context.origin();
                this.text = context.statement().orElse("");
                this.shapeId = context.shapeId();
                this.callSite = callSite;
            }

            @Override
            public void executed() {
                executed = System.nanoTime();
                // Rows are not known yet; the count is a lower bound until the close reports it.
                statement = new Statement(operation, dataType, origin, text, start, executed, executed, shapeId,
                        callSite, 0, false);
                recorder.statements.add(this);
            }

            @Override
            public void close(long rows, boolean exact) {
                statement = new Statement(operation, dataType, origin, text, start, executed, System.nanoTime(),
                        shapeId, callSite, rows, exact);
            }
        }

        @Override
        public Handle onExecute(QueryContext context, List<Parameter> parameters) {
            if (recorded.incrementAndGet() > limit) {
                return Handle.NOOP;
            }
            return new Slot(this, context, callSites ? CallSiteCapture.callSite() : null);
        }

        @Override
        public boolean callSites() {
            return callSites;
        }

        @Override
        public void onCacheHit(Class<? extends Data> dataType, int count) {
            cacheHits.addAndGet(count);
        }
    }
}
