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

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;
import st.orm.TransactionPropagation;
import st.orm.core.spi.TransactionScope;
import st.orm.spi.QueryContext;
import st.orm.spi.SqlOperation;

/**
 * What a transaction did on its connection, from the moment it took it to the moment it returned it: the time its
 * statements spent on it, against which the slow statement log measures the time the connection was held while the
 * application did other work, and from its first write the time its row locks were held with it; and what its
 * statements repeated, on which the advice log advises when the transaction completes.
 *
 * <p>A transaction context takes one when its transaction starts using a connection, credits it with each statement
 * as the statement closes, and releases it when the connection goes back to the pool. Nothing is taken while the
 * slow statement log does not watch transactions and the advice log reports no advice on what a transaction repeats,
 * so a transaction then costs one volatile read. The repeats are recorded only while the advice log reports them,
 * which is development.</p>
 *
 * <p>The fields are plain: a transaction's connection serves one caller at a time, and a transaction that moves
 * between threads does so with the ordering its own machinery gives.</p>
 *
 * @since 1.16
 */
public final class TransactionRecord {

    private static final AtomicLong IDS = new AtomicLong();

    /**
     * The repeats transactions advised on, by transaction identity and repeat key, so a performance log summary
     * leaves those executions to the transaction. Holds the transactions that advised on something, which are few;
     * at most {@link AdviceLog#MAX_REPORTED} of them, after which it starts over.
     */
    private static final ConcurrentHashMap<Long, Set<Repeats.ShapeAtSite>> ADVISED = new ConcurrentHashMap<>();

    /**
     * The identity, taken from {@link #IDS} on first request: only a performance log scope and the advice log ask for
     * it, so a transaction that neither observes leaves the shared counter alone.
     */
    private long id;
    private final long takenNanos = System.nanoTime();
    private final boolean fromFirstStatement;
    private final @Nullable String beganAt;
    private final @Nullable Repeats repeats;
    private long busyNanos;
    private int statements;
    /** When the first write started, or zero while the transaction has not written. */
    private long firstWriteNanos;
    /** The time the statements spent on the connection from the first write on. */
    private long busyFromFirstWriteNanos;

    private TransactionRecord(boolean fromFirstStatement, boolean recordRepeats) {
        this.fromFirstStatement = fromFirstStatement;
        this.beganAt = recordRepeats ? beganAt(TransactionScope.current()) : null;
        this.repeats = recordRepeats ? new Repeats(false) : null;
    }

    /**
     * Returns the call site that began the transaction the scope runs in: the outermost of the scopes the current
     * one joined, since a joined block is part of the transaction it joined, which is where the repeats are
     * counted too.
     */
    private static @Nullable String beganAt(@Nullable TransactionScope scope) {
        if (scope == null) {
            return null;
        }
        while (scope.parent() != null && joins(scope.options().propagation())) {
            scope = scope.parent();
        }
        return scope.beganAt();
    }

    /** Returns whether a block with the propagation joins the transaction around it rather than opening its own. */
    private static boolean joins(TransactionPropagation propagation) {
        return switch (propagation) {
            case REQUIRED, SUPPORTS, MANDATORY, NESTED -> true;
            case REQUIRES_NEW, NOT_SUPPORTED, NEVER -> false;
        };
    }

    /**
     * Returns whether transactions are recorded: while the slow statement log watches them, or the advice log
     * reports what a transaction repeats.
     *
     * @return {@code true} if a transaction that starts now is recorded.
     */
    static boolean records() {
        return SlowStatementLog.watchesTransactions() || AdviceLog.recordsRepeats();
    }

    /**
     * Takes the record of a transaction that starts using its connection now, or returns {@code null} while
     * transactions are not recorded.
     *
     * @param fromFirstStatement whether the hold is measured from the transaction's first statement rather than from
     *                           the moment it took its connection, as for a transaction another manager opened.
     * @return the record, or {@code null}.
     */
    public static @Nullable TransactionRecord take(boolean fromFirstStatement) {
        boolean recordRepeats = AdviceLog.recordsRepeats();
        return SlowStatementLog.watchesTransactions() || recordRepeats
                ? new TransactionRecord(fromFirstStatement, recordRepeats)
                : null;
    }

    /**
     * Returns the identity of the transaction: a number no other transaction of the application has.
     *
     * @return the identity.
     */
    public long id() {
        // A transaction's connection serves one caller at a time, so the identity is taken once.
        if (id == 0) {
            id = IDS.incrementAndGet();
        }
        return id;
    }

    /**
     * Credits the record with a statement that has closed: the time from its preparation to its close, which covers
     * its execution and the reading of its rows, and the statement itself for the repeats, keyed by its shape and
     * the call site it ran from, walked here while the caller is on the stack. The execution that makes a statement
     * a repeat is where whether the application accepts the advice is decided, once per repeat, with both the
     * statement's call path and the method running the transaction on the stack.
     *
     * @param context the execution.
     * @param startNanos when the statement was prepared.
     * @param executedNanos when it returned from the database.
     * @param endNanos when it closed.
     * @param rows the rows it produced or affected.
     * @param exactRows whether that count is exact.
     */
    void statementClosed(QueryContext context,
                         long startNanos,
                         long executedNanos,
                         long endNanos,
                         long rows,
                         boolean exactRows) {
        busyNanos += endNanos - startNanos;
        statements++;
        // A SELECT reads; anything else, a call to a procedure included, may have written and taken locks.
        if (context.operation() != SqlOperation.SELECT && firstWriteNanos == 0) {
            firstWriteNanos = startNanos;
        }
        if (firstWriteNanos != 0) {
            busyFromFirstWriteNanos += endNanos - Math.max(startNanos, firstWriteNanos);
        }
        if (repeats == null) {
            return;
        }
        // A statement whose repetition carries no advice is not walked for.
        if (Repeats.adviceOn(context.operation(), context.origin()) == null || context.dataType().isEmpty()) {
            return;
        }
        var repeat = repeats.record(context.operation(), context.dataType().orElse(null), context.origin(),
                context.shapeId(), context.statement().orElse(""), startNanos, executedNanos, rows, exactRows,
                true, id(), CallSiteCapture.callSite(), context.readsRecords(), context.repositoryWrite(),
                UnitOfWorkRepeats.upsertTakesGeneratedKey(context));
        if (repeat != null && (!AdviceLog.reports(repeat.advice)
                || AdviceAcceptance.accepts(repeat.advice, repeat.shapeId, repeat.type))) {
            repeat.accepted = true;
        }
    }

    /**
     * Releases the record, now that the connection goes back to the pool: the advice log reports what the
     * transaction repeated, and the slow statement log reports the transaction when it held the connection idle for
     * longer than its threshold.
     */
    public void released() {
        SlowStatementLog.transactionCompleted(this, System.nanoTime());
    }

    long takenNanos() {
        return takenNanos;
    }

    long busyNanos() {
        return busyNanos;
    }

    int statements() {
        return statements;
    }

    boolean wrote() {
        return firstWriteNanos != 0;
    }

    /**
     * Returns the time the connection was held while the application did other work from the first write on, when
     * the row locks of the writes were held with it: the time from the first write to now, less what the statements
     * spent on the connection in it. Zero for a transaction that has not written.
     *
     * @param now the moment the connection is returned.
     * @return the idle time from the first write on.
     */
    long lockedIdleNanos(long now) {
        if (firstWriteNanos == 0) {
            return 0;
        }
        // A hold measured from the first statement started after that statement was prepared, so the time from the
        // first write is capped at the idle time of the whole hold.
        long idleNanos = now - takenNanos - busyNanos;
        return Math.max(0, Math.min(idleNanos, now - firstWriteNanos - busyFromFirstWriteNanos));
    }

    boolean fromFirstStatement() {
        return fromFirstStatement;
    }

    /**
     * Returns the call site that began the transaction, walked where its block was entered while the advice log
     * reports what a transaction repeats, or {@code null} when it was not walked: for a transaction another manager
     * opened, or in production.
     *
     * @return the call site, or {@code null}.
     */
    @Nullable String beganAt() {
        return beganAt;
    }

    /**
     * Returns the advice on what the transaction repeated, heaviest first, and remembers the repeats advised on,
     * so a performance log summary that covers the transaction leaves their executions to it.
     *
     * @return the advisories; empty while repeats are not recorded.
     */
    List<Repeats.Advisory> advisories() {
        if (repeats == null) {
            return List.of();
        }
        var advisories = repeats.advisories(true);
        if (!advisories.isEmpty()) {
            if (ADVISED.size() >= AdviceLog.MAX_REPORTED) {
                ADVISED.clear();
            }
            Set<Repeats.ShapeAtSite> keys = new HashSet<>();
            for (var advisory : advisories) {
                keys.add(advisory.key());
            }
            ADVISED.put(id(), keys);
        }
        return advisories;
    }

    /**
     * Returns whether the transaction advised on the repeat, which makes the repeat's executions in it the
     * transaction's rather than a unit of work's. A summary that records no call sites asks by shape alone, which
     * the transaction's advice at any call site answers.
     *
     * @param transactionId the identity of the transaction.
     * @param key the key of the repeat.
     * @return {@code true} if the transaction advised on it when it completed.
     */
    static boolean advised(long transactionId, Repeats.ShapeAtSite key) {
        var keys = ADVISED.get(transactionId);
        if (keys == null) {
            return false;
        }
        if (key.callSite() != null) {
            return keys.contains(key);
        }
        for (var advised : keys) {
            if (key.matches(advised)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets which transactions advised on what. */
    static void reset() {
        ADVISED.clear();
    }
}
