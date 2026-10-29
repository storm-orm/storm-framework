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

import java.util.List;
import org.jspecify.annotations.Nullable;
import st.orm.spi.QueryContext;

/**
 * What the unit of work a performance log scope covers repeated, counted from every execution as it closes, past
 * the recording limit of the scope's table too, so the summary's advice covers the whole unit of work while its
 * table lists the first executions. Keeps no statement: per repeat, the count, time, rows and transactions of its
 * executions, and their intervals, bounded.
 *
 * <p>Thread-safe: a scope's executions may run concurrently, so they are recorded one at a time.</p>
 *
 * @since 1.16
 */
public final class UnitOfWorkRepeats {

    private final Repeats repeats = new Repeats(true);

    /**
     * Returns whether an execution can take part in a repeat, so a scope walks a call site past its recording
     * limit only for one that can.
     *
     * @param context the context of the execution.
     * @return {@code true} if the execution's repetition can carry advice.
     */
    public static boolean candidate(QueryContext context) {
        return context.dataType().isPresent() && Repeats.adviceOn(context) != null;
    }

    /**
     * Records an execution that has closed.
     *
     * @param context the context of the execution: what the statement did, the type it read or wrote, its shape.
     * @param startNanos when the execution was prepared.
     * @param executedNanos when it returned from the database.
     * @param rows the rows it produced or affected.
     * @param exactRows whether that count is exact.
     * @param transactional whether it ran in a transaction.
     * @param transactionId the identity of that transaction, or {@code 0} when unknown.
     * @param callSite the call site of the execution, or {@code null} when not recorded.
     */
    public synchronized void record(QueryContext context,
                                    long startNanos,
                                    long executedNanos,
                                    long rows,
                                    boolean exactRows,
                                    boolean transactional,
                                    long transactionId,
                                    @Nullable String callSite) {
        repeats.record(context, startNanos, executedNanos, rows, exactRows, transactional, transactionId, callSite);
    }

    /**
     * Returns the advice on what the unit of work repeated across its transactions, heaviest first, leaving the
     * executions of a transaction that advised on a repeat to that transaction.
     *
     * @return the advice, as the summary reports it.
     */
    public synchronized List<String> advice() {
        return repeats.advisories(false).stream().map(Repeats.Advisory::text).toList();
    }
}
