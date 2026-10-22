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
 * any, and only a statement with advice asks its loggers whether they are enabled. The record of what was reported
 * per shape holds at most {@link #MAX_REPORTED} entries and starts over when full, which may repeat a line but never
 * changes what a line says.</p>
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
                + System.lineSeparator() + "\tadvice: " + advisory);
    }

    /**
     * Reports the advice of an executing statement that the advice log reports and, at its frequency, has not
     * reported yet: once per piece of advice at {@code INFO}, once per shape at {@code DEBUG}, every time at
     * {@code TRACE}.
     *
     * @param context the execution.
     * @param statement the statement text, or {@code null} when unknown.
     * @param advisories the advice the statement carries.
     */
    static void onExecute(QueryContext context, @Nullable String statement, List<String> advisories) {
        Level level = LOGGER.isTraceEnabled() ? Level.TRACE : LOGGER.isDebugEnabled() ? Level.DEBUG : Level.INFO;
        String callSite = null;
        for (var advisory : advisories) {
            var advice = Advice.of(advisory);
            // Whether the application accepts the advice is asked only of advice the log would report now.
            if (advice == null || !reports(advice) || reported(context.shapeId(), advice, level)
                    || AdviceAcceptance.accepts(advice, context.shapeId(), context.dataType().orElse(null))
                    || !firstReport(context.shapeId(), advice, level)) {
                continue;
            }
            if (callSite == null) {
                callSite = Objects.requireNonNullElse(CallSiteCapture.callSite(), "");
            }
            var logger = LOGGERS[advice.ordinal()];
            var line = render(context, callSite.isEmpty() ? null : callSite, statement, List.of(advisory));
            // The line goes out at the frequency's level, or at INFO where the advice's own logger is set to INFO.
            if (level == Level.TRACE && logger.isTraceEnabled()) {
                logger.trace("{}", line);
            } else if (level != Level.INFO && logger.isDebugEnabled()) {
                logger.debug("{}", line);
            } else {
                logger.info("{}", line);
            }
        }
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
            rendered.append(System.lineSeparator()).append("\tshape: ").append("%016x".formatted(context.shapeId()));
        }
        for (var advisory : advisories) {
            rendered.append(System.lineSeparator()).append("\tadvice: ").append(advisory);
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
        configured = false;
        REPORTED_DESIGN.clear();
        REPORTED_ONCE.clear();
        REPORTED.clear();
        InListShapes.reset();
        AdviceAcceptance.reset();
    }
}
