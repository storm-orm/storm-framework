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

import static st.orm.TransactionPropagation.REQUIRED;
import static st.orm.TransactionPropagation.SUPPORTS;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.IllegalTransactionStateException;
import st.orm.TransactionOptions;
import st.orm.TransactionTimedOutException;
import st.orm.TransientPersistenceException;
import st.orm.core.spi.TransactionRunner;

/**
 * The rules of a transactional block that reruns on a transient failure, shared by the blocking flow of
 * {@link TransactionRunner} and the language flows that keep their own orchestration, such as Kotlin's suspending
 * one.
 *
 * <p>A block reruns only when it owns its physical transaction, so that a rerun starts that transaction over: a
 * block that joins an outer transaction, or runs without one, is refused when it states retries. Each attempt is a
 * transaction of its own. A failed attempt reruns only when its failure is transient: the exception that left the
 * attempt, or one in its cause chain, was classified transient by the framework while the attempt ran, whatever
 * type the exception mapper translated it to, or is a {@link TransientPersistenceException}. A timed-out or
 * cancelled attempt never reruns. Between attempts the block pauses for a short, jittered time that grows with
 * each rerun.</p>
 *
 * @since 1.16
 */
public final class TransactionRetries {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.transaction");

    /**
     * The base pause before the first rerun, in milliseconds; it doubles with each further rerun, up to
     * {@link #MAXIMUM_PAUSE_MILLIS}.
     */
    private static final long FIRST_PAUSE_MILLIS = 10;

    private static final long MAXIMUM_PAUSE_MILLIS = 1_000;

    private TransactionRetries() {
    }

    /**
     * Refuses retries the block cannot honour, before the block opens.
     *
     * <p>A block owns its physical transaction when it starts one: {@code REQUIRES_NEW}, or {@code REQUIRED} or
     * {@code NESTED} while no transaction is active on the calling thread, neither a transaction block that runs in
     * a transaction nor a transaction an external manager owns. Every other block joins a transaction it does not
     * own, or runs without one, and a rerun of it would not start a transaction over.</p>
     *
     * @param options the fully resolved options of the block.
     * @param retries the number of reruns after the first attempt.
     * @throws IllegalArgumentException if {@code retries} is negative.
     * @throws IllegalTransactionStateException if {@code retries} is positive and the block does not own its
     *                                          physical transaction.
     */
    public static void check(TransactionOptions options, int retries) {
        if (retries < 0) {
            throw new IllegalArgumentException("Retries must not be negative: %d.".formatted(retries));
        }
        if (retries == 0) {
            return;
        }
        var propagation = options.propagation() != null ? options.propagation() : REQUIRED;
        String refusal = switch (propagation) {
            case REQUIRES_NEW -> null;
            case REQUIRED, NESTED -> inTransaction(TransactionScope.current())
                    ? "joins the active transaction"
                    : null;
            case MANDATORY -> "joins the transaction that must be active";
            case SUPPORTS -> inTransaction(TransactionScope.current())
                    ? "joins the active transaction"
                    : "runs without a transaction";
            case NOT_SUPPORTED, NEVER -> "runs without a transaction";
        };
        if (refusal != null) {
            throw new IllegalTransactionStateException(("A block with propagation %s %s, so it cannot rerun: a "
                    + "rerun starts the transaction over, which only the block that starts the transaction can do. "
                    + "State retries on that block, or use REQUIRES_NEW.").formatted(propagation, refusal));
        }
    }

    /**
     * Returns whether a transaction is active around a block that opens inside the given scope: the nearest
     * enclosing scope whose propagation decides it, or, when there is none, a transaction an external manager owns.
     */
    private static boolean inTransaction(@Nullable TransactionScope scope) {
        for (var candidate = scope; candidate != null; candidate = candidate.parent()) {
            if (candidate.options().propagation() != SUPPORTS) {
                return candidate.runsInTransaction();
            }
        }
        return TransactionRunner.externalTransaction() != null;
    }

    /**
     * Returns whether an attempt that failed with the given exception reruns: the exception, or one in its cause
     * chain, was classified transient while the attempt's scope was current, or is a
     * {@link TransientPersistenceException}. A timed-out or cancelled attempt never reruns.
     *
     * @param scope the scope of the failed attempt.
     * @param failure the exception that left the attempt.
     * @return {@code true} if the attempt failed transiently.
     */
    public static boolean isTransient(TransactionScope scope, Throwable failure) {
        if (scope.isCancelled() || failure instanceof TransactionTimedOutException) {
            return false;
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable cause = failure; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (cause instanceof TransientPersistenceException || scope.isTransientFailure(cause)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the pause before the given rerun, in milliseconds: a random time between the rerun's base pause and
     * twice that, where the base starts at {@value #FIRST_PAUSE_MILLIS} milliseconds and doubles per rerun, up to
     * {@value #MAXIMUM_PAUSE_MILLIS}.
     *
     * @param rerun the rerun about to start, {@code 1} for the first.
     * @return the pause in milliseconds.
     */
    public static long pauseMillis(int rerun) {
        long base = Math.min(MAXIMUM_PAUSE_MILLIS, FIRST_PAUSE_MILLIS << Math.min(Math.max(rerun, 1) - 1, 16));
        return base + ThreadLocalRandom.current().nextLong(base + 1);
    }

    /**
     * Logs the rerun about to start, naming its attempt and the failure of the attempt before it.
     *
     * @param rerun the rerun about to start, {@code 1} for the first.
     * @param retries the number of reruns the block states.
     * @param failure the failure of the attempt before it.
     */
    public static void logRerun(int rerun, int retries, Throwable failure) {
        LOGGER.info("Rerunning transaction, attempt {} of {}, after a transient failure: {}",
                rerun + 1, retries + 1, failure.toString());
    }

    /**
     * Returns the failure of the last attempt, with the failures of the attempts before it added as suppressed.
     *
     * @param failure the failure of the last attempt.
     * @param earlier the failures of the attempts before it, in order.
     * @return {@code failure}.
     */
    public static Throwable withEarlierFailures(Throwable failure, List<Throwable> earlier) {
        for (var previous : earlier) {
            if (previous != failure) {
                failure.addSuppressed(previous);
            }
        }
        return failure;
    }
}
