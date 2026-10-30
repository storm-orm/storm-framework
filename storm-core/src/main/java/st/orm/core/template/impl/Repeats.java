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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.spi.QueryContext;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * What a transaction, or the unit of work a performance log scope covers, repeated: the same single-row read or
 * unbatched single-row write run again and again, and references of one type fetched one statement at a time, each
 * from {@link Advice#REPEAT_THRESHOLD} executions. A transaction records its executions as they close and reports
 * when it completes; a performance log scope records every execution as it closes, past the recording limit of its
 * table too, and advises when it closes.
 *
 * <p>A repeat is keyed by shape, or by text for a statement without one, so a collection parameter that renders in
 * different lengths stays one repeat, and a fetch by the type it resolves, whatever the statement; and by the call
 * site where it is known, so a shape repeated from two loops is two repeats, each pointing at its own loop and
 * weighed by its own cost. Each repeat carries what the advice states beyond the count: the database time its executions
 * spent, and where they ran concurrently the time they covered on the clock, which is what the repeat cost the
 * caller and what orders the advice and is measured against the floor; the transactions they ran in, which a summary
 * states where they spanned several; and the call site of the statement, where it is known. A transaction's
 * executions never overlap, so a transaction keeps no intervals and its elapsed time is the sum. Every statement of
 * the scope, whether it can repeat or not, reports when it ran and in which transaction, so a repeat knows whether
 * the time between its executions held other statements and whether its transactions held anything else.</p>
 *
 * <p>Not thread-safe: a transaction's connection serves one caller at a time, and a scope's executions, which may
 * run concurrently, are recorded through {@link UnitOfWorkRepeats}, which serializes them.</p>
 *
 * @since 1.16
 */
final class Repeats {

    /**
     * One piece of advice on a repeat, with the key of the repeat, the shape it is reported for and the elapsed time
     * that orders it.
     */
    record Advisory(Advice advice, @Nullable ShapeAtSite key, long shapeId, long elapsedNanos, String text) {}

    /** The key of the fetches of one type. */
    private record Fetched(Class<?> type) {}

    /**
     * The key of a repeat: what the statement is, a shape, a text or the type fetched, and the call site it ran from,
     * {@code null} where call sites are not recorded.
     */
    record ShapeAtSite(Object shape, @Nullable String callSite) {

        /** Returns whether this key, from a summary, is the key a transaction advised on. */
        boolean matches(ShapeAtSite advised) {
            return shape.equals(advised.shape) && (callSite == null || callSite.equals(advised.callSite));
        }
    }

    /** The part of a repeat for the executions that ran outside any transaction, each committing on its own. */
    private static final long OWN = -1;

    /** The part of a repeat for the executions that ran in a transaction whose identity is unknown. */
    private static final long UNKNOWN = -2;

    /**
     * The executions of a repeat past the first {@link #MAX_PARTS} transactions, counted together; such a part is
     * never left out for a transaction that advised, and counts its transactions by their identities, which are
     * taken in order.
     */
    private static final long OVERFLOW = -3;

    /** The most transactions a repeat counts apart, so a long unit of work keeps a bounded record. */
    static final int MAX_PARTS = 1_024;

    /** The most repeats a scope counts; executions of further statements are not counted. */
    static final int MAX_REPEATS = 4_096;

    /** The most transactions whose statements are counted apart, so a long unit of work keeps a bounded record. */
    static final int MAX_COUNTED_TRANSACTIONS = 4_096;

    /** The executions of a repeat in one transaction, or in the own, unknown or overflow part: counters only. */
    private static final class Part {
        int executions;
        long nanos;
        boolean singleRows = true;
        /** Whether an execution produced or affected a row, or reported no exact count. */
        boolean anyRows;
        /** When the first execution was prepared and the last returned, which bound the time the loop spanned. */
        long firstStartNanos = Long.MAX_VALUE;
        long lastEndNanos = Long.MIN_VALUE;
    }

    /** A statement or fetch that may repeat, under construction. */
    static final class Repeat {
        final Advice advice;
        final SqlOperation operation;
        /** Whether the statement is an upsert, which its keyword does not show. */
        final boolean upsert;
        final Class<? extends Data> type;
        final long shapeId;
        /** Whether a read maps its rows to a type, rather than to computed values such as a count. */
        final boolean readsRecords;
        /** The built-in repository call the statement served, or {@code null} for none. */
        final @Nullable RepositoryCall call;
        /** Whether an upsert that updates draws a generated key value all the same, on the database it ran on. */
        final boolean upsertTakesGeneratedKey;
        /** The columns the statement asks an index for, from its first execution; {@code null} for none. */
        final @Nullable IndexDemands indexDemands;
        /** The data source the statement ran on, whose index catalog the demands are checked against. */
        final @Nullable DataSource dataSource;
        /** Every execution recorded, which decides when the threshold is reached. */
        int executions;
        /** The call site of the executions, part of the key where it is known. */
        @Nullable String callSite;
        /** Whether the application accepts the advice, decided by the transaction that records the repeat. */
        boolean accepted;
        /** The executions per transaction, in the order the transactions first ran one. */
        private final Map<Long, Part> parts = new LinkedHashMap<>();
        /** The intervals of every execution, where executions may overlap; bounded. */
        private final @Nullable Intervals intervals;
        /** The transactions the overflow part ran in, counted as their identities grow. */
        private int overflowTransactions;
        /** The greatest transaction identity seen. */
        private long maxTransactionId;

        private Repeat(Advice advice, QueryContext context, Class<? extends Data> type, boolean intervals) {
            this.intervals = intervals ? new Intervals() : null;
            this.advice = advice;
            this.operation = context.operation();
            this.upsert = context.upsert();
            this.type = type;
            this.shapeId = context.shapeId();
            this.readsRecords = context.readsRecords();
            this.call = context instanceof QueryImpl.QueryContextImpl impl ? impl.call() : null;
            this.upsertTakesGeneratedKey = upsertTakesGeneratedKey(context);
            var impl = context instanceof QueryImpl.QueryContextImpl it ? it : null;
            this.indexDemands = impl == null ? null : impl.indexDemands();
            this.dataSource = impl == null ? null : impl.dataSource();
        }

        /** Whether a write was made for given entities or keys, rather than by condition. */
        boolean repositoryWrite() {
            return call != null && call.writes();
        }

        /**
         * What the statement did, as the advice names it: the built-in repository call it served, or the same
         * statement by its operation, UPSERT for an upsert.
         */
        String name() {
            if (call != null) {
                return call.method;
            }
            return "the same " + (upsert ? "UPSERT" : operation.name());
        }
    }

    /**
     * Returns whether an upsert the context describes draws a generated key value even where it updates, which the
     * dialect of the database it ran on knows and only Storm's own context carries from it.
     */
    private static boolean upsertTakesGeneratedKey(QueryContext context) {
        return context instanceof QueryImpl.QueryContextImpl impl && impl.upsertTakesGeneratedKey();
    }

    /** What the advice on a repeat states, over the parts that count. */
    private record Counted(int executions, long nanos, long elapsedNanos, long spanNanos, long outsideNanos,
                           boolean singleRows, boolean noRows, int transactions, boolean own) {}

    private final Map<ShapeAtSite, Repeat> repeats = new LinkedHashMap<>();
    private final boolean intervals;
    /** When every statement of the scope ran, repeats or not, from which the time between a repeat's executions is known. */
    private final Intervals statements = new Intervals();
    /** The statements each transaction held, by identity, for the first {@link #MAX_COUNTED_TRANSACTIONS}. */
    private final Map<Long, Integer> statementsByTransaction = new LinkedHashMap<>();

    /**
     * Creates the repeats of a transaction, whose executions never overlap, or of a unit of work, whose executions
     * may, so that their elapsed time is measured as the time they covered.
     *
     * @param intervals whether to keep the interval of each execution.
     */
    Repeats(boolean intervals) {
        this.intervals = intervals;
    }

    /**
     * Records when a statement of the scope ran and in which transaction, whether or not it can repeat: the time
     * between a repeat's executions that held other statements is database work, not work outside it, and a
     * transaction that held other statements did not hold the repeat's execution on its own.
     *
     * @param transactionId the identity of the transaction the statement ran in, or {@code 0} for none or unknown.
     * @param startNanos when the execution was prepared.
     * @param executedNanos when it returned from the database.
     */
    void observed(long transactionId, long startNanos, long executedNanos) {
        statements.add(startNanos, executedNanos);
        if (transactionId == 0) {
            return;
        }
        var count = statementsByTransaction.get(transactionId);
        if (count == null && statementsByTransaction.size() >= MAX_COUNTED_TRANSACTIONS) {
            return;
        }
        statementsByTransaction.put(transactionId, count == null ? 1 : count + 1);
    }

    /**
     * Returns the advice a statement's repetition carries: on a fetch, the same single-row read, or the same
     * single-row write, an upsert included; {@code null} for a statement whose repetition carries none.
     */
    static @Nullable Advice adviceOn(QueryContext context) {
        if (context.origin() == StatementOrigin.FETCH) {
            return Advice.REPEATED_FETCH;
        }
        if (context.upsert()) {
            return Advice.REPEATED_WRITE;
        }
        return switch (context.operation()) {
            case SELECT -> Advice.REPEATED_SELECT;
            case INSERT, UPDATE, DELETE -> Advice.REPEATED_WRITE;
            default -> null;
        };
    }

    /**
     * Records an execution, and returns its repeat as the execution brings it to the threshold, so the caller can
     * decide the advice while the execution is still on the stack; {@code null} for every other execution, and for
     * a statement without a type, since the advice names it.
     *
     * @param context the context of the execution: what the statement did, the type it read or wrote, its shape,
     *                and its text, the key of a statement without a shape.
     * @param startNanos when the execution was prepared.
     * @param executedNanos when it returned from the database.
     * @param rows the rows it produced or affected.
     * @param exactRows whether that count is exact.
     * @param transactional whether it ran in a transaction.
     * @param transactionId the identity of that transaction, or {@code 0} when unknown.
     * @param callSite the call site of the execution, or {@code null} when not recorded.
     * @return the repeat that reached the threshold with this execution, or {@code null}.
     */
    @Nullable Repeat record(QueryContext context,
                            long startNanos,
                            long executedNanos,
                            long rows,
                            boolean exactRows,
                            boolean transactional,
                            long transactionId,
                            @Nullable String callSite) {
        var type = context.dataType().orElse(null);
        if (type == null) {
            return null;
        }
        var advice = adviceOn(context);
        if (advice == null) {
            return null;
        }
        long shapeId = context.shapeId();
        Object shape = advice == Advice.REPEATED_FETCH ? new Fetched(type)
                : shapeId != 0 ? shapeId : context.statement().orElse("");
        var key = new ShapeAtSite(shape, callSite);
        var repeat = repeats.get(key);
        if (repeat == null) {
            if (repeats.size() >= MAX_REPEATS) {
                return null;
            }
            repeat = new Repeat(advice, context, type, intervals);
            repeats.put(key, repeat);
        }
        repeat.executions++;
        repeat.callSite = callSite;
        long partId = !transactional ? OWN : transactionId == 0 ? UNKNOWN : transactionId;
        if (partId > 0 && !repeat.parts.containsKey(partId) && repeat.parts.size() >= MAX_PARTS) {
            // Past the transactions counted apart: a transaction is new to the overflow part when its identity is
            // the greatest yet, as identities are taken in order.
            if (partId > repeat.maxTransactionId) {
                repeat.overflowTransactions++;
            }
            partId = OVERFLOW;
        }
        if (transactionId > repeat.maxTransactionId) {
            repeat.maxTransactionId = transactionId;
        }
        var part = repeat.parts.computeIfAbsent(partId, ignore -> new Part());
        part.executions++;
        part.nanos += executedNanos - startNanos;
        part.firstStartNanos = Math.min(part.firstStartNanos, startNanos);
        part.lastEndNanos = Math.max(part.lastEndNanos, executedNanos);
        // A batch is one execution, so a row count of at most one per execution means no batch took the rows.
        part.singleRows &= exactRows && rows <= 1;
        part.anyRows |= !exactRows || rows > 0;
        if (repeat.intervals != null) {
            repeat.intervals.add(startNanos, executedNanos);
        }
        return repeat.executions == Advice.REPEAT_THRESHOLD ? repeat : null;
    }

    /**
     * Counts the parts of a repeat that are its scope's to advise on: every part within a transaction; across a
     * unit of work, the parts of the transactions that did not advise on the repeat themselves, which a transaction
     * decides when it completes, so the parts are kept apart until the summary asks. The time the executions covered
     * on the clock is the union of their intervals; where a part is left out, whose intervals are not kept apart, it
     * is the sum of the rest, as for executions that ran one after another. The time outside the database between
     * the first and the last execution leaves out every statement of the scope that ran in between. The executions
     * ran in transactions of their own where each transaction counted held nothing else; a transaction whose
     * statements were not counted, past the bound or of unknown identity, is not said to.
     */
    private Counted count(ShapeAtSite key, Repeat repeat, boolean withinTransaction) {
        int executions = 0;
        long nanos = 0;
        boolean singleRows = true;
        boolean anyRows = false;
        int transactions = 0;
        boolean unknown = false;
        boolean leftOut = false;
        boolean own = true;
        long firstStart = Long.MAX_VALUE;
        long lastEnd = Long.MIN_VALUE;
        for (var entry : repeat.parts.entrySet()) {
            long partId = entry.getKey();
            var part = entry.getValue();
            if (!withinTransaction && partId > 0 && TransactionRecord.advised(partId, key)) {
                leftOut = true;
                continue;
            }
            executions += part.executions;
            nanos += part.nanos;
            singleRows &= part.singleRows;
            anyRows |= part.anyRows;
            firstStart = Math.min(firstStart, part.firstStartNanos);
            lastEnd = Math.max(lastEnd, part.lastEndNanos);
            if (partId == OWN) {
                transactions += part.executions;
            } else if (partId == UNKNOWN) {
                unknown = true;
                own = false;
            } else if (partId == OVERFLOW) {
                transactions += repeat.overflowTransactions;
                own = false;
            } else {
                transactions++;
                var held = statementsByTransaction.get(partId);
                own &= held != null && held == part.executions;
            }
        }
        long elapsed = repeat.intervals == null || leftOut ? nanos
                : Math.min(nanos, Intervals.union(List.of(repeat.intervals)));
        long span = executions == 0 ? 0 : lastEnd - firstStart;
        long outside = executions == 0 ? 0 : Math.max(0, span - statements.coveredWithin(firstStart, lastEnd));
        return new Counted(executions, nanos, elapsed, span, outside, singleRows, !anyRows,
                withinTransaction ? 1 : unknown ? -1 : transactions, own);
    }

    /**
     * Returns the advice on the repeats, heaviest first by the time they took on the clock. A repeat that took less
     * than the advice log's floor carries none. Within a transaction every repeat past the threshold is advised on.
     * Across a unit of work, the executions of a transaction that advised on the repeat are that transaction's, so
     * what remains is the part no single transaction saw; the advice states the transactions it spanned, and a write
     * that ran in a transaction of its own every time says that a batch commits the rows together.
     *
     * @param withinTransaction whether the repeats are one transaction's.
     * @return the advisories, ordered by the time their executions covered.
     */
    List<Advisory> advisories(boolean withinTransaction) {
        var advisories = new ArrayList<Advisory>();
        long floor = AdviceLog.repeatFloorNanos();
        for (var entry : repeats.entrySet()) {
            var repeat = entry.getValue();
            if (repeat.accepted || repeat.executions < Advice.REPEAT_THRESHOLD) {
                continue;
            }
            var counted = count(entry.getKey(), repeat, withinTransaction);
            if (counted.executions() < Advice.REPEAT_THRESHOLD
                    || (repeat.advice != Advice.REPEATED_FETCH && !counted.singleRows())
                    || counted.elapsedNanos() < floor) {
                continue;
            }
            var text = text(repeat, counted);
            if (text == null) {
                continue;
            }
            advisories.add(new Advisory(repeat.advice, entry.getKey(), repeat.shapeId, counted.elapsedNanos(), text));
            // A statement that costs more than its round trips is the thing to look at, and no single execution of
            // it need reach the slow threshold that would name the index it lacks, so the repeat names it.
            if (costly(counted) && repeat.indexDemands != null) {
                for (var index : repeat.indexDemands.advise(IndexCatalog.published(repeat.dataSource))) {
                    var advice = Advice.of(index);
                    if (advice != null) {
                        advisories.add(new Advisory(advice, entry.getKey(), repeat.shapeId, counted.elapsedNanos(),
                                index));
                    }
                }
            }
        }
        // Stable, so the index a repeat lacks follows the repeat.
        advisories.sort(comparingLong(Advisory::elapsedNanos).reversed());
        return advisories;
    }

    /** Returns whether each execution costs more than its round trip, so the statement is the thing to look at. */
    private static boolean costly(Counted counted) {
        return counted.nanos() / Math.max(1, counted.executions()) >= Advice.STATEMENT_COST_NANOS;
    }

    /**
     * Returns whether the executions were spread over far more time than they spent in the database, with work
     * outside it between them, as a loop around a remote call is: the time between the first and the last that no
     * statement of the scope spent in the database is at least {@link #SPREAD_FACTOR} times what the executions
     * spent inside it.
     */
    private static boolean spread(Counted counted) {
        return counted.elapsedNanos() > 0 && counted.outsideNanos() >= SPREAD_FACTOR * counted.elapsedNanos();
    }

    /** How many times the time outside the database must exceed the time inside it for a repeat to be spread. */
    static final long SPREAD_FACTOR = 10;

    /**
     * Renders the advice of a repeat with its facts: the time, with the elapsed time where the executions ran
     * concurrently enough for the sum to overstate what they cost, by at least twice, and the mean per execution;
     * the rows, none where no execution touched one; the transactions where stated, of their own where each held
     * nothing else; for a write, where the executions were spread over far more time than they took, with work
     * outside the database between them; the call site. From {@link Advice#STATEMENT_COST_NANOS} per execution the
     * advice says the statement itself is the cost, ahead of the remedy for the loop, which fits the statement: the
     * list form of the built-in call it served, else rows by key or the keys that exist for a read, the overloads
     * that take a list or one statement whose condition covers all of them for a write. A write spread between
     * work outside the database is to be collected and written after the loop, never wrapped in a transaction that
     * would hold the connection across that work. An upsert repeated on rows that mostly exist is to be replaced
     * by a read and a write of what is new or changed, before any batch. A write whose transactions each held it
     * alone is told that the combined write commits the rows together; one whose transactions held more is the loop
     * over units of work, which no batch merges.
     */
    private static @Nullable String text(Repeat repeat, Counted counted) {
        long mean = counted.nanos() / Math.max(1, counted.executions());
        String time = ", " + SlowStatementLog.millis(counted.nanos());
        long elapsed = counted.elapsedNanos();
        if (elapsed > 0 && counted.nanos() >= 2 * elapsed) {
            time += " over " + SlowStatementLog.millis(elapsed) + " elapsed";
        }
        time += ", " + SlowStatementLog.millis(mean) + " each";
        String rows = counted.noRows() ? "no rows" : "one row each";
        var facts = new StringBuilder();
        String commits = "";
        boolean write = repeat.advice == Advice.REPEATED_WRITE;
        boolean spread = write && spread(counted);
        int transactions = counted.transactions();
        // A write run once in each of several transactions that held more is the loop over units of work: one
        // statement or one batch over all of them would take the writes out of the transactions they belong to, so
        // only the statement itself is left to look at, and a write that costs no more than its round trip is no
        // repeat to advise on.
        boolean unitsOfWork = write && transactions > 1 && transactions == counted.executions() && !counted.own();
        if (unitsOfWork && !costly(counted)) {
            return null;
        }
        if (transactions == counted.executions() && counted.own()) {
            facts.append(", in ").append(transactions).append(" transactions of their own");
            commits = spread
                    ? " and commits them together, rather than a transaction around the loop"
                    : ", which commits them together";
        } else if (transactions > 1) {
            facts.append(", in ").append(transactions).append(" transactions");
        }
        if (spread) {
            facts.append(", spread over ").append(SlowStatementLog.millis(counted.spanNanos()))
                    .append(" with work outside the database between them");
        }
        // One fact per line after the first: where it ran, what each execution costs where that is the thing to
        // look at, and the remedy for the loop.
        var lines = new StringBuilder();
        if (repeat.callSite != null) {
            lines.append("\nat: ").append(repeat.callSite);
        }
        if (costly(counted)) {
            lines.append("\ncost: at ").append(SlowStatementLog.millis(mean))
                    .append(unitsOfWork
                            ? " each the statement itself costs more than its round trip, and it runs once per "
                                    + "transaction, so look at the statement"
                            : " each the statement itself costs more than its round trips, so look at it first");
        }
        String type = repeat.type.getSimpleName();
        return switch (repeat.advice) {
            case REPEATED_FETCH -> Advice.REPEATED_FETCH.format(counted.executions(), type, time + facts, lines);
            case REPEATED_SELECT -> Advice.REPEATED_SELECT.format(repeat.name(), type, counted.executions(), time,
                    rows, facts, lines.append("\nfix: ").append(readRemedy(repeat)));
            default -> Advice.REPEATED_WRITE.format(repeat.name(), type, counted.executions(), time, rows, facts,
                    unitsOfWork ? lines : lines.append("\nfix: ").append(writeRemedy(repeat, spread, commits)));
        };
    }

    /**
     * Returns the remedy for a repeated read: the list form of the built-in call it served, over the keys of every
     * execution; else one query for all of them, which reads the rows by key for a read of records, and for a read
     * of computed values selects the keys that exist, grouped only where the counts themselves are used.
     */
    private static String readRemedy(Repeat repeat) {
        if (repeat.call != null && repeat.call.listForm != null) {
            return repeat.call.listForm + " over the keys reads "
                    + (repeat.call.exists() ? "the ones that exist" : "the rows") + " in one round trip";
        }
        return repeat.readsRecords
                ? "one query for all of them, such as findAllById or a where with IN, reads the rows in one round "
                        + "trip"
                : "one query for all of them, with a where with IN over what the loop varies, selects the keys that "
                        + "exist in one round trip, with a GROUP BY on it where the counts themselves are used";
    }

    /**
     * Returns the remedy for a repeated write: for an upsert, a read first and a write of what is new or changed,
     * then the batch; for an insert or a write the repository made for given entities or keys, the overloads that
     * take a list, collected and written after the loop where the executions were spread between work outside the
     * database; for a write by condition, one statement whose condition covers all of them.
     */
    private static String writeRemedy(Repeat repeat, boolean spread, String commits) {
        boolean upsert = repeat.upsert;
        if (!upsert && repeat.operation != SqlOperation.INSERT && !repeat.repositoryWrite()) {
            return "one statement whose condition covers all of them, such as a where with IN over what the loop "
                    + "varies, writes them in one round trip";
        }
        String batch = (spread
                ? "collect the rows and write them after the loop through the overloads that take a list, which "
                        + "sends them as one batch"
                : "the overloads that take a list send the rows as one batch") + commits;
        if (!upsert) {
            return batch;
        }
        return "where the rows mostly exist and rarely change, read them first and write only the new or changed "
                + "ones, which avoids the writes"
                + (repeat.upsertTakesGeneratedKey ? ", and the generated key value each upsert takes even where it "
                        + "updates" : "")
                + "\nor: " + batch;
    }

    /**
     * The intervals the executions of one part spent in the database, from which the time they covered on the clock
     * is the union. Kept as pairs in a growing array, and bounded: past {@link #CAPACITY} pairs they are merged
     * where they overlap, and where that leaves more than half, the pairs separated by the shortest gaps are joined
     * across them, so the union may count a few short gaps as covered, never less than the executions took.
     */
    static final class Intervals {

        /** The most pairs kept before they are merged. */
        static final int CAPACITY = 4_096;

        private long[] starts = new long[16];
        private long[] ends = new long[16];
        private int size;

        void add(long start, long end) {
            if (size == starts.length) {
                if (size >= CAPACITY) {
                    compact();
                } else {
                    starts = Arrays.copyOf(starts, size * 2);
                    ends = Arrays.copyOf(ends, size * 2);
                }
            }
            starts[size] = start;
            ends[size] = end;
            size++;
        }

        /** Merges overlapping pairs and, while more than half the capacity remain, joins across the shortest gaps. */
        private void compact() {
            merge();
            if (size <= CAPACITY / 2) {
                return;
            }
            long[] gaps = new long[size - 1];
            for (int i = 0; i < size - 1; i++) {
                gaps[i] = starts[i + 1] - ends[i];
            }
            long[] sortedGaps = gaps.clone();
            Arrays.sort(sortedGaps);
            long joinBelow = sortedGaps[size - 1 - CAPACITY / 2];
            int kept = 0;
            for (int i = 0; i < size; i++) {
                if (kept > 0 && i > 0 && gaps[i - 1] <= joinBelow) {
                    ends[kept - 1] = Math.max(ends[kept - 1], ends[i]);
                } else {
                    starts[kept] = starts[i];
                    ends[kept] = ends[i];
                    kept++;
                }
            }
            size = kept;
        }

        /** Sorts the pairs by start and merges the ones that overlap. */
        private void merge() {
            Integer[] order = new Integer[size];
            for (int i = 0; i < size; i++) {
                order[i] = i;
            }
            Arrays.sort(order, (a, b) -> Long.compare(starts[a], starts[b]));
            long[] mergedStarts = new long[starts.length];
            long[] mergedEnds = new long[ends.length];
            int kept = 0;
            for (int index : order) {
                if (kept > 0 && starts[index] <= mergedEnds[kept - 1]) {
                    mergedEnds[kept - 1] = Math.max(mergedEnds[kept - 1], ends[index]);
                } else {
                    mergedStarts[kept] = starts[index];
                    mergedEnds[kept] = ends[index];
                    kept++;
                }
            }
            starts = mergedStarts;
            ends = mergedEnds;
            size = kept;
        }

        /** Returns the time the intervals of the parts covered together. */
        static long union(List<Intervals> parts) {
            int total = 0;
            for (var part : parts) {
                total += part.size;
            }
            long[][] pairs = new long[total][];
            int next = 0;
            for (var part : parts) {
                for (int i = 0; i < part.size; i++) {
                    pairs[next++] = new long[] {part.starts[i], part.ends[i]};
                }
            }
            return covered(pairs);
        }

        /** Returns the time the intervals covered within the given bounds, each cut to them. */
        long coveredWithin(long from, long to) {
            long[][] pairs = new long[size][];
            int next = 0;
            for (int i = 0; i < size; i++) {
                long start = Math.max(starts[i], from);
                long end = Math.min(ends[i], to);
                if (start < end) {
                    pairs[next++] = new long[] {start, end};
                }
            }
            return covered(Arrays.copyOf(pairs, next));
        }

        /** Returns the time the pairs cover together, overlaps counted once. */
        private static long covered(long[][] pairs) {
            Arrays.sort(pairs, comparingLong(pair -> pair[0]));
            long elapsed = 0;
            long openedAt = 0;
            long closesAt = Long.MIN_VALUE;
            for (var pair : pairs) {
                if (pair[0] > closesAt) {
                    if (closesAt != Long.MIN_VALUE) {
                        elapsed += closesAt - openedAt;
                    }
                    openedAt = pair[0];
                }
                closesAt = Math.max(closesAt, pair[1]);
            }
            return closesAt == Long.MIN_VALUE ? 0 : elapsed + (closesAt - openedAt);
        }
    }
}
