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
import org.jspecify.annotations.Nullable;
import st.orm.Data;
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
 * executions never overlap, so a transaction keeps no intervals and its elapsed time is the sum.</p>
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
        final Class<? extends Data> type;
        final long shapeId;
        /** Whether a read maps its rows to a type, rather than to computed values such as a count. */
        final boolean readsRecords;
        /** Whether a write was made for given entities or keys, rather than by condition. */
        final boolean repositoryWrite;
        /** Whether an upsert that updates draws a generated key value all the same, on the database it ran on. */
        final boolean upsertTakesGeneratedKey;
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

        private Repeat(Advice advice, SqlOperation operation, Class<? extends Data> type, long shapeId,
                       boolean readsRecords, boolean repositoryWrite, boolean upsertTakesGeneratedKey,
                       boolean intervals) {
            this.intervals = intervals ? new Intervals() : null;
            this.advice = advice;
            this.operation = operation;
            this.type = type;
            this.shapeId = shapeId;
            this.readsRecords = readsRecords;
            this.repositoryWrite = repositoryWrite;
            this.upsertTakesGeneratedKey = upsertTakesGeneratedKey;
        }
    }

    /** What the advice on a repeat states, over the parts that count. */
    private record Counted(int executions, long nanos, long elapsedNanos, long spanNanos, boolean singleRows,
                           boolean noRows, int transactions) {}

    private final Map<ShapeAtSite, Repeat> repeats = new LinkedHashMap<>();
    private final boolean intervals;

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
     * Returns the advice a statement's repetition carries: on a fetch, the same single-row read, or the same
     * single-row write, an upsert included; {@code null} for a statement whose repetition carries none.
     */
    static @Nullable Advice adviceOn(SqlOperation operation, StatementOrigin origin) {
        if (origin == StatementOrigin.FETCH) {
            return Advice.REPEATED_FETCH;
        }
        return switch (operation) {
            case SELECT -> Advice.REPEATED_SELECT;
            case INSERT, UPDATE, DELETE, UPSERT -> Advice.REPEATED_WRITE;
            default -> null;
        };
    }

    /**
     * Records an execution, and returns its repeat as the execution brings it to the threshold, so the caller can
     * decide the advice while the execution is still on the stack; {@code null} for every other execution, and for
     * a statement without a type, since the advice names it.
     *
     * @param operation what the statement did.
     * @param type the type it read or wrote, or {@code null} for none.
     * @param origin what caused it.
     * @param shapeId the shape of the statement, or {@code 0} for none.
     * @param statement the statement text, the key of a statement without a shape.
     * @param startNanos when the execution was prepared.
     * @param executedNanos when it returned from the database.
     * @param rows the rows it produced or affected.
     * @param exactRows whether that count is exact.
     * @param transactional whether it ran in a transaction.
     * @param transactionId the identity of that transaction, or {@code 0} when unknown.
     * @param callSite the call site of the execution, or {@code null} when not recorded.
     * @param readsRecords whether a read maps its rows to a type, rather than to computed values.
     * @param repositoryWrite whether a write was made for given entities or keys, rather than by condition.
     * @param upsertTakesGeneratedKey whether an upsert that updates draws a generated key value all the same.
     * @return the repeat that reached the threshold with this execution, or {@code null}.
     */
    @Nullable Repeat record(SqlOperation operation,
                            @Nullable Class<? extends Data> type,
                            StatementOrigin origin,
                            long shapeId,
                            String statement,
                            long startNanos,
                            long executedNanos,
                            long rows,
                            boolean exactRows,
                            boolean transactional,
                            long transactionId,
                            @Nullable String callSite,
                            boolean readsRecords,
                            boolean repositoryWrite,
                            boolean upsertTakesGeneratedKey) {
        if (type == null) {
            return null;
        }
        var advice = adviceOn(operation, origin);
        if (advice == null) {
            return null;
        }
        Object shape = advice == Advice.REPEATED_FETCH ? new Fetched(type) : shapeId != 0 ? shapeId : statement;
        var key = new ShapeAtSite(shape, callSite);
        var repeat = repeats.get(key);
        if (repeat == null) {
            if (repeats.size() >= MAX_REPEATS) {
                return null;
            }
            repeat = new Repeat(advice, operation, type, shapeId, readsRecords, repositoryWrite,
                    upsertTakesGeneratedKey, intervals);
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
     * is the sum of the rest, as for executions that ran one after another.
     */
    private Counted count(ShapeAtSite key, Repeat repeat, boolean withinTransaction) {
        int executions = 0;
        long nanos = 0;
        boolean singleRows = true;
        boolean anyRows = false;
        int transactions = 0;
        boolean unknown = false;
        boolean leftOut = false;
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
            } else if (partId == OVERFLOW) {
                transactions += repeat.overflowTransactions;
            } else {
                transactions++;
            }
        }
        long elapsed = repeat.intervals == null || leftOut ? nanos
                : Math.min(nanos, Intervals.union(List.of(repeat.intervals)));
        return new Counted(executions, nanos, elapsed, executions == 0 ? 0 : lastEnd - firstStart, singleRows,
                !anyRows, withinTransaction ? 1 : unknown ? -1 : transactions);
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
            advisories.add(new Advisory(repeat.advice, entry.getKey(), repeat.shapeId, counted.elapsedNanos(),
                    text(repeat, counted)));
        }
        advisories.sort(comparingLong(Advisory::elapsedNanos).reversed());
        return advisories;
    }

    /**
     * Returns whether the executions were spread over far more time than they spent in the database, with work
     * outside it between them, as a loop around a remote call is: the time outside the database between the first
     * and the last is at least {@link #SPREAD_FACTOR} times what they spent inside it.
     */
    private static boolean spread(Counted counted) {
        long outside = counted.spanNanos() - counted.elapsedNanos();
        return counted.elapsedNanos() > 0 && outside >= SPREAD_FACTOR * counted.elapsedNanos();
    }

    /** How many times the time outside the database must exceed the time inside it for a repeat to be spread. */
    static final long SPREAD_FACTOR = 10;

    /**
     * Renders the advice of a repeat with its facts: the time, with the elapsed time where the executions ran
     * concurrently enough for the sum to overstate what they cost, by at least twice, and the mean per execution;
     * the rows, none where no execution touched one; the transactions where stated; where the executions were
     * spread over far more time than they took, with work outside the database between them; the call site. From
     * {@link Advice#STATEMENT_COST_NANOS} per execution the advice says the statement itself is the cost, ahead of
     * the remedy for the loop, which fits the statement: rows by key or a grouped query for a read, the overloads
     * that take a list or one statement whose condition covers all of them for a write. A write spread between
     * work outside the database is to be collected and written after the loop, never wrapped in a transaction that
     * would hold the connection across that work. An upsert repeated on rows that mostly exist is to be replaced
     * by a read and a write of what is new or changed, before any batch.
     */
    private static String text(Repeat repeat, Counted counted) {
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
        boolean spread = spread(counted);
        int transactions = counted.transactions();
        if (transactions == counted.executions()) {
            facts.append(", in ").append(transactions).append(" transactions of their own");
            commits = spread
                    ? " and commits them together, rather than opening a transaction around the loop"
                    : ", which commits them together";
        } else if (transactions > 1) {
            facts.append(", in ").append(transactions).append(" transactions");
        }
        if (spread) {
            facts.append(", spread over ").append(SlowStatementLog.millis(counted.spanNanos()))
                    .append(" with work outside the database between them");
        }
        if (repeat.callSite != null) {
            facts.append(", at ").append(repeat.callSite);
        }
        String cost = mean >= Advice.STATEMENT_COST_NANOS
                ? "at " + SlowStatementLog.millis(mean) + " each the statement itself costs more than its round "
                        + "trips, so look at it first; then "
                : "";
        String type = repeat.type.getSimpleName();
        return switch (repeat.advice) {
            case REPEATED_FETCH -> Advice.REPEATED_FETCH.format(counted.executions(), type, time + facts, cost);
            case REPEATED_SELECT -> Advice.REPEATED_SELECT.format(counted.executions(), type, time, rows, facts,
                    cost, repeat.readsRecords
                            ? "one query for all of them, such as findAllById or a where with IN, reads the rows "
                                    + "in one round trip"
                            : "one query for all of them, with a where with IN over what the loop varies and a "
                                    + "GROUP BY on it for an aggregate such as a count, computes them in one round "
                                    + "trip");
            default -> Advice.REPEATED_WRITE.format(repeat.operation.name(), type,
                    counted.executions(), time, rows, facts, cost, writeRemedy(repeat, spread), commits);
        };
    }

    /**
     * Returns the remedy for a repeated write: for an upsert, a read first and a write of what is new or changed,
     * then the batch; for an insert or a write the repository made for given entities or keys, the overloads that
     * take a list, collected and written after the loop where the executions were spread between work outside the
     * database; for a write by condition, one statement whose condition covers all of them.
     */
    private static String writeRemedy(Repeat repeat, boolean spread) {
        boolean upsert = repeat.operation == SqlOperation.UPSERT;
        if (!upsert && repeat.operation != SqlOperation.INSERT && !repeat.repositoryWrite) {
            return "one statement whose condition covers all of them, such as a where with IN over what the loop "
                    + "varies, writes them in one round trip";
        }
        String batch = spread
                ? "collect the rows and write them after the loop through the overloads that take a list, which "
                        + "sends them as one batch"
                : "the overloads that take a list send the rows as one batch";
        if (!upsert) {
            return batch;
        }
        return "where the rows mostly exist and rarely change, reading them first and writing only the new or "
                + "changed ones avoids the writes"
                + (repeat.upsertTakesGeneratedKey ? ", and, where the key is left to the database, the generated "
                        + "key value each upsert takes even where it updates" : "")
                + "; otherwise " + batch;
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
