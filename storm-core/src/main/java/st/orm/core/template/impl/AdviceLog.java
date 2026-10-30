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

import static st.orm.core.spi.StormConfigHelper.getDuration;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import st.orm.StormConfig;
import st.orm.core.template.impl.Advice.Category;
import st.orm.core.template.impl.StatementListener.Handle;
import st.orm.spi.QueryContext;

/**
 * The advice log: the advice a statement carries, reported fast or slow under {@code st.orm.sql.advice}, each piece
 * of advice under a logger of its own named after its anchor, such as {@code st.orm.sql.advice.unordered-limit}.
 *
 * <p>The log reports the categories of advice the application asks for, in {@code storm.advice.categories}:
 * correctness by default, and design and performance as well while developing, as {@link #development()} says. Design advice on an entity is reported
 * once per entity, when it is checked at startup or first used. Advice the application accepts for an entity, a
 * repository or the code that issues a statement, with {@link st.orm.AcceptAdvice}, is not reported there; a piece of
 * advice the application accepts everywhere is turned off by setting its logger to {@code OFF}. Advice that holds only once the statement is slow, such as the count behind a
 * page, is left to the slow statement log.</p>
 *
 * <p>Design advice checks entities against the design budget, 10 joins and 96 columns beyond an entity's own unless
 * the application sets its own in {@code storm.advice.entity_budget.joins} and {@code .columns}, or through
 * {@link #entityBudget(Integer, Integer)}.</p>
 *
 * <p>How often a piece of advice is reported follows the level of {@code st.orm.sql.advice}: once per piece of advice
 * at {@code INFO}, once per statement shape at {@code DEBUG}, and at every execution at {@code TRACE}.</p>
 *
 * <p>Advice is decided when the statement is built, so a statement without advice pays one test of whether it carries
 * any, and only a statement with advice asks its loggers whether they are enabled. A statement whose advice is
 * reported is reported when it closes, with what the execution cost. The record of what was reported per shape
 * holds at most {@link #MAX_REPORTED} entries and starts over when full, which may repeat a line but never changes
 * what a line says.</p>
 *
 * <p>What a transaction repeats, the same single-row read or write again and again or references fetched one at a
 * time, is recorded by the transaction while the log reports that advice, and reported as one block when the
 * transaction completes, under the call site that began it. The block is reported at the frequency of the level,
 * counted per call site: once per piece of advice there at {@code INFO}, once per repeated shape there at
 * {@code DEBUG}, every time at {@code TRACE}, so what one transaction repeats is reported whatever another
 * reported before.</p>
 *
 * @since 1.16
 */
public final class AdviceLog {

    private static final String NAME = "st.orm.sql.advice";

    /** Decides how often advice is reported. */
    private static final Logger LOGGER = LoggerFactory.getLogger(NAME);

    /** A logger per piece of advice, by ordinal. */
    private static final Logger[] LOGGERS = Arrays.stream(Advice.values())
            .map(advice -> LoggerFactory.getLogger(logger(advice)))
            .toArray(Logger[]::new);

    /** The most advice the log remembers having reported for a shape before it starts over. */
    static final int MAX_REPORTED = 4_096;

    private record Reported(long shapeId, Advice advice) {}

    /** The advice reported once at {@code INFO}. */
    private static final Set<Advice> REPORTED_ONCE = ConcurrentHashMap.newKeySet();

    /** The advice reported per shape at {@code DEBUG}. */
    private static final Set<Reported> REPORTED = ConcurrentHashMap.newKeySet();

    /**
     * A transaction's advice reported for a call site: per piece of advice at {@code INFO}, per repeat at
     * {@code DEBUG}.
     */
    private record ReportedTransaction(String callSite, Repeats.@Nullable ShapeAtSite repeat, Advice advice) {}

    /** The advice reported per transaction call site. */
    private static final Set<ReportedTransaction> REPORTED_TRANSACTIONS = ConcurrentHashMap.newKeySet();

    /** The categories reported unless the application names others. */
    static final String DEFAULT_CATEGORIES = "correctness";

    /** The categories reported while developing, unless the application names others. */
    static final String DEVELOPMENT_CATEGORIES = "correctness,design,performance";

    /** The design advice reported, per entity and advice. */
    private static final Set<String> REPORTED_DESIGN = ConcurrentHashMap.newKeySet();

    /** Whether the application named the categories, which development then leaves as they are. */
    private static volatile boolean configured =
            StormConfig.defaults().getProperty(StormConfig.ADVICE_CATEGORIES) != null;

    private static volatile Set<Category> categories = parse(Objects.requireNonNullElse(
            StormConfig.defaults().getProperty(StormConfig.ADVICE_CATEGORIES), DEFAULT_CATEGORIES));

    /**
     * The time a repeat must have taken before it carries advice, applied when neither the system property nor an
     * integration sets one: fifty milliseconds. Most repeats in a larger application are correct and cost a few
     * milliseconds, such as lookups of reference rows or a guard that keeps a task idempotent; the floor keeps the
     * advice to the repeats worth a change, and the performance log's table still lists every shape.
     */
    public static final Duration DEFAULT_REPEAT_FLOOR = Duration.ofMillis(50);

    /**
     * The time a repeat must have taken on the clock before it carries advice, in nanoseconds; zero for no floor.
     */
    private static volatile long repeatFloorNanos;

    /**
     * Applies the repeat floor from configuration ({@link StormConfig#defaults()}, which reads system properties);
     * the Spring and Ktor integrations apply their own configuration through {@link #repeatFloor(Duration)}.
     */
    // Placed after every field it touches, the default included, since static initialization runs in textual
    // order and a Duration is no compile-time constant.
    static {
        configuredRepeatFloor();
    }

    private AdviceLog() {
    }

    /**
     * Sets the categories of advice the log reports, such as {@code correctness} and {@code performance}.
     *
     * @param categories the names of the categories, in any case.
     * @throws IllegalArgumentException if a name is not a category of advice.
     */
    public static void categories(Collection<String> categories) {
        AdviceLog.categories = parse(String.join(",", categories));
        configured = true;
    }

    /**
     * Sets the design budget entities are checked against: the most tables a read may join and the most columns it
     * may select beyond the entity's own, 10 and 96 unless set. A read from half the budget is wide, which a slow
     * read of it is advised on. Set it before the first template is created, which checks the model.
     *
     * @param joins the most tables a read may join, or {@code null} to keep the current setting.
     * @param columns the most columns a read may select beyond the entity's own, or {@code null} to keep the current
     *                setting.
     * @throws IllegalArgumentException if a value is below one.
     */
    public static void entityBudget(@Nullable Integer joins, @Nullable Integer columns) {
        var current = EntityGraph.budget();
        EntityGraph.budget(new EntityGraph.Budget(joins != null ? joins : current.joins(),
                columns != null ? columns : current.columns()));
    }

    /**
     * Returns the most tables the design budget lets a read join.
     *
     * @return the joins of the design budget.
     */
    public static int entityBudgetJoins() {
        return EntityGraph.budget().joins();
    }

    /**
     * Returns the most columns the design budget lets a read select beyond the entity's own.
     *
     * @return the columns of the design budget.
     */
    public static int entityBudgetColumns() {
        return EntityGraph.budget().columns();
    }

    /**
     * Reports every category, as suits development, unless the application named the categories. The integrations
     * call it where their framework says the application is in development, and the test extension for every test,
     * so design and performance advice reach the developer and stay out of production.
     */
    public static void development() {
        if (!configured) {
            categories = parse(DEVELOPMENT_CATEGORIES);
        }
    }

    /**
     * Returns the categories of advice the log reports.
     *
     * @return the names of the categories, in lower case.
     */
    public static List<String> categories() {
        return categories.stream().sorted().map(category -> category.name().toLowerCase(Locale.ROOT)).toList();
    }

    private static Set<Category> parse(String names) {
        var parsed = EnumSet.noneOf(Category.class);
        for (var name : names.split(",")) {
            var trimmed = name.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            try {
                parsed.add(Category.valueOf(trimmed.toUpperCase(Locale.ROOT)));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Unknown category of advice '%s'; expected one of %s."
                        .formatted(trimmed, Arrays.stream(Category.values())
                                .map(category -> category.name().toLowerCase(Locale.ROOT)).toList()), e);
            }
        }
        return Set.copyOf(parsed);
    }

    /**
     * Returns the name of the logger that reports a piece of advice.
     *
     * @param advice the advice.
     * @return {@code st.orm.sql.advice.<anchor>}.
     */
    static String logger(Advice advice) {
        return NAME + "." + advice.anchor();
    }

    /**
     * Returns whether the advice log reports any of a statement's advice, which the execution path asks only for a
     * statement that carries advice.
     *
     * @param advisories the advice the statement carries.
     * @return {@code true} if the log reports a piece of the advice.
     */
    static boolean enabled(List<String> advisories) {
        for (var advisory : advisories) {
            var advice = Advice.of(advisory);
            if (advice != null && reports(advice)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the advice log reports a piece of advice: never for advice that holds only once slow, otherwise
     * while its category is reported and its logger is enabled.
     */
    static boolean reports(Advice advice) {
        return !advice.slowOnly() && categories.contains(advice.category())
                && LOGGERS[advice.ordinal()].isInfoEnabled();
    }

    /**
     * Returns whether the advice log reports what a transaction repeats, which is what makes a transaction record
     * its statements.
     *
     * @return {@code true} if a piece of advice on a repeat is reported.
     */
    public static boolean recordsRepeats() {
        return reports(Advice.REPEATED_SELECT) || reports(Advice.REPEATED_WRITE) || reports(Advice.REPEATED_FETCH);
    }

    /**
     * Sets the time a repeated statement must have taken, on the clock, before it carries advice: the same
     * single-row read or write run again and again, or references fetched one at a time, that took less is not
     * advised on. {@link #DEFAULT_REPEAT_FLOOR} unless set; zero advises on every repeat past ten executions.
     * Measured on the clock rather than as the sum of the executions, so executions that ran concurrently count
     * for the time they covered.
     *
     * @param floor the floor, or {@code null} or zero for none.
     */
    public static void repeatFloor(@Nullable Duration floor) {
        repeatFloorNanos = floor == null || floor.isNegative() || floor.isZero() ? 0 : floor.toNanos();
    }

    /**
     * Returns the time a repeated statement must have taken before it carries advice, or {@code null} for none.
     *
     * @return the floor, or {@code null}.
     */
    public static @Nullable Duration repeatFloor() {
        long nanos = repeatFloorNanos;
        return nanos == 0 ? null : Duration.ofNanos(nanos);
    }

    /** Returns the floor in nanoseconds, zero for none. */
    static long repeatFloorNanos() {
        return repeatFloorNanos;
    }

    /** Restores the floor to the configured or default setting. */
    private static void configuredRepeatFloor() {
        repeatFloor(getDuration(StormConfig.defaults(), StormConfig.ADVICE_REPEAT_FLOOR, DEFAULT_REPEAT_FLOOR));
    }

    /**
     * Reports design advice on an entity, once per entity and advice, at {@code INFO}: it describes the model rather
     * than an execution, so the level of {@code st.orm.sql.advice} does not repeat it.
     *
     * @param advice the advice.
     * @param graph the entity's graph.
     * @param advisory the advice as the line reports it.
     */
    static void onDesign(Advice advice, EntityGraph graph, String advisory) {
        if (!reports(advice) || AdviceAcceptance.accepts(advice, graph.type())
                || !REPORTED_DESIGN.add(graph.type().getName() + "#" + advice.anchor())) {
            return;
        }
        LOGGERS[advice.ordinal()].info("{}", "SQL advice (design " + graph.type().getSimpleName() + ")"
                + System.lineSeparator() + "\tgraph: " + graph.graph() + "  " + graph.joins() + " joins, "
                + graph.columns() + " columns beyond its own"
                + Advice.lines(advisory));
    }

    /**
     * Decides which advice of an executing statement the advice log reports and, at its frequency, has not reported
     * yet: once per piece of advice at {@code INFO}, once per shape at {@code DEBUG}, every time at {@code TRACE}.
     * The call site is walked now, while the caller is on the stack; the lines go out when the execution closes,
     * with what it cost.
     *
     * @param context the execution.
     * @param statement the statement text, or {@code null} when unknown.
     * @param advisories the advice the statement carries.
     * @return the handle that reports the advice when the execution closes, or {@code null} when none is reported.
     */
    static @Nullable Handle onExecute(QueryContext context, @Nullable String statement, List<String> advisories) {
        Level level = LOGGER.isTraceEnabled() ? Level.TRACE : LOGGER.isDebugEnabled() ? Level.DEBUG : Level.INFO;
        List<String> reported = null;
        for (var advisory : advisories) {
            var advice = Advice.of(advisory);
            // Whether the application accepts the advice is asked only of advice the log would report now.
            if (advice == null || !reports(advice) || reported(context.shapeId(), advice, level)
                    || AdviceAcceptance.accepts(advice, context.shapeId(), context.dataType().orElse(null))
                    || !firstReport(context.shapeId(), advice, level)) {
                continue;
            }
            if (reported == null) {
                reported = new ArrayList<>();
            }
            reported.add(advisory);
        }
        if (reported == null) {
            return null;
        }
        return new Report(context, CallSiteCapture.callSite(context), statement, reported, level);
    }

    /** Logs a line at the frequency's level, or at INFO where the advice's own logger is set to INFO. */
    private static void log(Logger logger, Level level, String line) {
        if (level == Level.TRACE && logger.isTraceEnabled()) {
            logger.trace("{}", line);
        } else if (level != Level.INFO && logger.isDebugEnabled()) {
            logger.debug("{}", line);
        } else {
            logger.info("{}", line);
        }
    }

    /** The advice of one execution, reported when the execution closes, with what it cost. */
    private static final class Report implements Handle {
        private final QueryContext context;
        private final @Nullable String callSite;
        private final @Nullable String statement;
        private final List<String> advisories;
        private final Level level;
        private final long start = System.nanoTime();
        private long executed;
        private @Nullable Throwable failure;

        Report(QueryContext context,
               @Nullable String callSite,
               @Nullable String statement,
               List<String> advisories,
               Level level) {
            this.context = context;
            this.callSite = callSite;
            this.statement = statement;
            this.advisories = advisories;
            this.level = level;
        }

        @Override
        public void executed() {
            executed = System.nanoTime();
        }

        @Override
        public void error(Throwable throwable) {
            failure = throwable;
        }

        @Override
        public void close(long rows, boolean exact) {
            for (var advisory : advisories) {
                var advice = Objects.requireNonNull(Advice.of(advisory));
                log(LOGGERS[advice.ordinal()], level, render(context, executed - start, rows, exact, failure,
                        callSite, statement, List.of(advisory)));
            }
        }
    }

    /**
     * Reports the advice on what a completing transaction repeated, as one block under the call site that began the
     * transaction, at the frequency of the level counted per call site: once per piece of advice there at
     * {@code INFO}, once per repeated shape there at {@code DEBUG}, every time at {@code TRACE}. Whether the
     * application accepts the advice was decided by the transaction, where the statement's call path was still on
     * the stack; a piece of advice whose logger is off is left out of the block.
     *
     * <p>The idle advice of a transaction that held its connection beyond the slow statement log's threshold is in
     * the block too, after the repeats or alone, once per call site like the rest: a reader working through the
     * advice log sees what the transaction has to be advised on in one place, whether or not a repeat survived the
     * floor. The slow statement log reports the same transaction on its own line, in production too.</p>
     *
     * @param advisories the advice on what the transaction repeated, heaviest first.
     * @param idleAdvisory the advice on the connection the transaction held idle, or {@code null} when it did not.
     * @param beganAt the call site that began the transaction, walked where its block was entered, or {@code null}
     *                to walk it now, which names the method that runs a blocking transaction.
     */
    static void onTransaction(List<Repeats.Advisory> advisories,
                              @Nullable String idleAdvisory,
                              @Nullable String beganAt) {
        Level level = LOGGER.isTraceEnabled() ? Level.TRACE : LOGGER.isDebugEnabled() ? Level.DEBUG : Level.INFO;
        String callSite = null;
        StringBuilder lines = null;
        // The advice this block reports already, so every repeat of the same advice in it goes out with the first.
        var reporting = EnumSet.noneOf(Advice.class);
        var all = new ArrayList<Repeats.Advisory>(advisories.size() + 1);
        all.addAll(advisories);
        if (idleAdvisory != null) {
            all.add(new Repeats.Advisory(Advice.IDLE_TRANSACTION, null, 0, 0, idleAdvisory));
        }
        for (var advisory : all) {
            var advice = advisory.advice();
            if (!reportsInTransaction(advice)) {
                continue;
            }
            if (callSite == null) {
                // A suspending transaction completes on the dispatcher that ran its block, with no caller on the
                // stack, which is why the site is walked where the block was entered; the walk here names the
                // method that runs a blocking transaction, still on the stack while it completes.
                callSite = Objects.requireNonNullElse(beganAt != null ? beganAt : CallSiteCapture.callSite(), "");
            }
            if (!reporting.contains(advice) && !firstTransactionReport(callSite, advisory.key(), advice, level)) {
                continue;
            }
            reporting.add(advice);
            if (lines == null) {
                lines = new StringBuilder("SQL advice (transaction)");
                if (!callSite.isEmpty()) {
                    lines.append(": ").append(callSite);
                }
            }
            lines.append(Advice.lines(advisory.text()));
        }
        if (lines != null) {
            log(LOGGER, level, lines.toString());
        }
    }

    /**
     * Returns whether a transaction's block reports a piece of advice: as {@link #reports(Advice)} decides, except
     * for advice that is otherwise the slow statement log's and holds here too, which is reported while its category
     * is and its logger is on: the idle advice, once the transaction held its connection too long, and the index a
     * repeated statement lacks, once the statement costs more than its round trips.
     */
    private static boolean reportsInTransaction(Advice advice) {
        return switch (advice) {
            case IDLE_TRANSACTION, MISSING_INDEX, UNINDEXED_FOREIGN_KEY ->
                    categories.contains(advice.category()) && LOGGERS[advice.ordinal()].isInfoEnabled();
            default -> reports(advice);
        };
    }

    /**
     * Records a transaction's advice as reported for its call site at the frequency of the level and returns whether
     * to report it: always at {@code TRACE}, the first time for the repeat there at {@code DEBUG}, the first time
     * for the advice there at {@code INFO}.
     */
    private static boolean firstTransactionReport(String callSite, Repeats.@Nullable ShapeAtSite repeat,
                                                  Advice advice, Level level) {
        if (level == Level.TRACE) {
            return true;
        }
        var reported = new ReportedTransaction(callSite, level == Level.DEBUG ? repeat : null, advice);
        // A full record starts over only for advice it does not hold, so what it holds stays quiet.
        if (REPORTED_TRANSACTIONS.contains(reported)) {
            return false;
        }
        if (REPORTED_TRANSACTIONS.size() >= MAX_REPORTED) {
            REPORTED_TRANSACTIONS.clear();
        }
        return REPORTED_TRANSACTIONS.add(reported);
    }

    /**
     * Returns whether the advice was reported already at the frequency of the level: never at {@code TRACE}, for its
     * shape at {@code DEBUG}, at all at {@code INFO}.
     */
    private static boolean reported(long shapeId, Advice advice, Level level) {
        return switch (level) {
            case TRACE -> false;
            case DEBUG -> REPORTED.contains(new Reported(shapeId, advice));
            default -> REPORTED_ONCE.contains(advice);
        };
    }

    /**
     * Records the advice as reported at the frequency of the level and returns whether to report it: always at
     * {@code TRACE}, the first time for its shape at {@code DEBUG}, the first time at all at {@code INFO}.
     */
    private static boolean firstReport(long shapeId, Advice advice, Level level) {
        return switch (level) {
            case TRACE -> true;
            case DEBUG -> {
                if (REPORTED.size() >= MAX_REPORTED) {
                    REPORTED.clear();
                }
                yield REPORTED.add(new Reported(shapeId, advice));
            }
            default -> REPORTED_ONCE.add(advice);
        };
    }

    /**
     * Renders an advice line: what the statement does, what the execution cost, the database time and the rows,
     * and where it was called from; the statement as sent, its shape, and a line per piece of advice. A failed
     * execution is named by the class of its failure, as a slow line names it.
     */
    static String render(QueryContext context,
                         long databaseNanos,
                         long rows,
                         boolean exact,
                         @Nullable Throwable failure,
                         @Nullable String callSite,
                         @Nullable String statement,
                         List<String> advisories) {
        var rendered = new StringBuilder("SQL advice (").append(SlowStatementLog.describe(context)).append("): ")
                .append(SlowStatementLog.millis(databaseNanos)).append(" in database");
        if (failure != null) {
            rendered.append(", failed (").append(failure.getClass().getSimpleName()).append(')');
        } else {
            rendered.append(", ").append(exact ? rows : rows + "*").append(" rows");
        }
        if (callSite != null) {
            rendered.append(", ").append(callSite);
        }
        if (statement != null) {
            rendered.append(System.lineSeparator()).append(SlowStatementLog.indent(statement));
        }
        if (context.shapeId() != 0) {
            rendered.append(System.lineSeparator()).append("\tshape: ").append("%016x".formatted(context.shapeId()));
        }
        for (var advisory : advisories) {
            rendered.append(Advice.lines(advisory));
        }
        return rendered.toString();
    }

    /**
     * Forgets what the log reported, so the next execution reports its advice again, and restores the categories
     * and the design budget to their defaults.
     */
    static void reset() {
        categories = parse(DEFAULT_CATEGORIES);
        EntityGraph.budget(EntityGraph.Budget.DEFAULT);
        configuredRepeatFloor();
        configured = false;
        REPORTED_DESIGN.clear();
        REPORTED_ONCE.clear();
        REPORTED.clear();
        REPORTED_TRANSACTIONS.clear();
        InListShapes.reset();
        AdviceAcceptance.reset();
        TransactionRecord.reset();
    }
}
