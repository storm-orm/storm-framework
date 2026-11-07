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

import static java.sql.Statement.SUCCESS_NO_INFO;

import java.sql.Statement;
import java.util.stream.IntStream;
import st.orm.PersistenceException;

/**
 * Checks the per-statement row counts a driver reports for a batch.
 *
 * <p>A driver may report {@link Statement#SUCCESS_NO_INFO} for a statement that succeeded without a known
 * row count. Such a count is a success. {@link Statement#EXECUTE_FAILED} and every count other than the
 * expected ones are failures, and so is an unknown count in a batch that decides from its counts: a versioned update,
 * where a count of zero marks a version conflict, and the update of a joined entity's extension rows, where it marks
 * a changed subtype.</p>
 *
 * @since 1.16
 */
public final class BatchRowCounts {

    private BatchRowCounts() {
    }

    /**
     * Returns whether the count a driver reported for one statement of a batch is a success: one of the expected
     * counts, or a success without a known count.
     *
     * @param count the count the driver reported for the statement.
     * @param expected the row counts that make the statement a success.
     * @return {@code true} if the count is a success.
     */
    public static boolean isSuccess(int count, int... expected) {
        if (count == SUCCESS_NO_INFO) {
            return true;
        }
        for (int value : expected) {
            if (count == value) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns whether the counts a driver reported for a batch are all a success: each one of the expected counts,
     * or a success without a known count.
     *
     * @param counts the counts the driver reported, one per statement of the batch.
     * @param expected the row counts that make a statement a success.
     * @return {@code true} if every count is a success.
     */
    public static boolean allSucceeded(int[] counts, int... expected) {
        return IntStream.of(counts).allMatch(count -> isSuccess(count, expected));
    }

    /**
     * Returns whether the driver reported a success without a known count for any statement of the batch.
     *
     * @param counts the counts the driver reported, one per statement of the batch.
     * @return {@code true} if any count is unknown.
     */
    public static boolean anyUnknown(int[] counts) {
        return IntStream.of(counts).anyMatch(count -> count == SUCCESS_NO_INFO);
    }

    /**
     * Returns the failure of a batch update that decides from its counts and whose counts the driver did not report.
     * A versioned update reads a count of zero as a version conflict, and the update of a joined entity's extension
     * rows reads it as a changed subtype, so without the counts neither can tell that case from a success.
     *
     * @param type the entity type of the batch.
     * @param unverified what the batch cannot verify, such as {@code "optimistic locking"}.
     * @return the failure.
     */
    public static PersistenceException unverifiedFailure(Class<?> type, String unverified) {
        return new PersistenceException(("Batch update of %s cannot verify %s because the driver did not report "
                + "update counts for the batch. Configure the driver to report the update count of every statement "
                + "in a batch, or update the entities one at a time.").formatted(type.getSimpleName(), unverified));
    }

    /**
     * Returns the number of rows the batch is known to have affected: the sum of the counts the driver reported as
     * a number of rows.
     *
     * @param counts the counts the driver reported, one per statement of the batch.
     * @return the number of rows known to be affected.
     */
    public static int knownAffected(int[] counts) {
        return IntStream.of(counts).filter(count -> count > 0).sum();
    }
}
