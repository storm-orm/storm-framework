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

import org.jspecify.annotations.Nullable;

/**
 * The connection a transaction holds, from the moment it takes it to the moment it returns it, against the time its
 * statements spent on it. The difference is time the connection was held, and after a write its row locks, while the
 * application did other work; the slow statement log reports a transaction whose difference exceeds its threshold.
 *
 * <p>A transaction context takes one when its transaction starts using a connection, credits it with each statement
 * as the statement closes, and releases it when the connection goes back to the pool. Nothing is taken while the
 * slow statement log does not watch transactions, so a transaction then costs one volatile read.</p>
 *
 * <p>The fields are plain: a transaction's connection serves one caller at a time, and a transaction that moves
 * between threads does so with the ordering its own machinery gives.</p>
 *
 * @since 1.16
 */
public final class HeldConnection {

    private final long takenNanos = System.nanoTime();
    private final boolean fromFirstStatement;
    private long busyNanos;
    private int statements;
    private boolean wrote;

    private HeldConnection(boolean fromFirstStatement) {
        this.fromFirstStatement = fromFirstStatement;
    }

    /**
     * Takes the connection a transaction starts using now, or returns {@code null} while the slow statement log does
     * not watch transactions.
     *
     * @param fromFirstStatement whether the hold is measured from the transaction's first statement rather than from
     *                           the moment it took its connection, as for a transaction another manager opened.
     * @return the held connection, or {@code null}.
     */
    public static @Nullable HeldConnection take(boolean fromFirstStatement) {
        return SlowStatementLog.watchesTransactions() ? new HeldConnection(fromFirstStatement) : null;
    }

    /**
     * Credits the hold with a statement that has closed: the time from its preparation to its close, which covers
     * its execution and the reading of its rows.
     *
     * @param nanos the time the statement spent on the connection.
     * @param write whether the statement wrote.
     */
    public void statementClosed(long nanos, boolean write) {
        busyNanos += nanos;
        statements++;
        wrote |= write;
    }

    /**
     * Releases the hold, now that the connection goes back to the pool, and reports it when it held the connection
     * idle for longer than the slow statement log's threshold.
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
        return wrote;
    }

    boolean fromFirstStatement() {
        return fromFirstStatement;
    }
}
