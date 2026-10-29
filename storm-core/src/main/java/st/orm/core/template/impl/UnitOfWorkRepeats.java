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
import st.orm.Data;
import st.orm.spi.QueryContext;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

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
     * @param operation what the statement does.
     * @param type the type it reads or writes, or {@code null} for none.
     * @param origin what caused it.
     * @return {@code true} if the execution's repetition can carry advice.
     */
    public static boolean candidate(SqlOperation operation, @Nullable Class<? extends Data> type,
                                    StatementOrigin origin) {
        return type != null && Repeats.adviceOn(operation, origin) != null;
    }

    /**
     * Records an execution that has closed.
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
     */
    public synchronized void record(SqlOperation operation,
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
        repeats.record(operation, type, origin, shapeId, statement, startNanos, executedNanos, rows, exactRows,
                transactional, transactionId, callSite, readsRecords, repositoryWrite, upsertTakesGeneratedKey);
    }

    /**
     * Returns whether an upsert the context describes draws a generated key value even where it updates, which the
     * dialect of the database it ran on knows and the context carries from it.
     *
     * @param context the context of the execution.
     * @return {@code true} for an upsert that draws a generated key value on update.
     */
    public static boolean upsertTakesGeneratedKey(QueryContext context) {
        return context instanceof QueryImpl.QueryContextImpl impl && impl.upsertTakesGeneratedKey();
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
