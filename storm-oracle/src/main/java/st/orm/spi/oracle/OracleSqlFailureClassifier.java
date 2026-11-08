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
package st.orm.spi.oracle;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.core.template.SqlFailureClassifier;

/**
 * Reads the errors Oracle reports for a constraint, by error code: {@code ORA-00001} unique constraint violated,
 * {@code ORA-02291} integrity constraint violated with the parent key not found, {@code ORA-02292} integrity
 * constraint violated with a child record found, {@code ORA-01400} and {@code ORA-01407} a null inserted or updated
 * into a column that refuses it, {@code ORA-02290} check constraint violated, {@code ORA-12899} a value too large
 * for its column and {@code ORA-01438} a value larger than the precision of its column. The names come from the
 * message, which Oracle writes in a fixed form: the constraint in parentheses, with its schema, and the table and
 * column in quotes.
 *
 * <p>A deferred constraint that fails at commit is reported as {@code ORA-02091}, transaction rolled back, with the
 * constraint's own error nested in the message; the nested error classifies the failure.</p>
 *
 * @since 1.16
 */
public final class OracleSqlFailureClassifier implements SqlFailureClassifier {

    /** The error a commit reports when a deferred constraint fails: {@code ORA-02091: transaction rolled back}. */
    private static final int TRANSACTION_ROLLED_BACK = 2091;

    /** The code of an error Oracle nests in the message of another, {@code ORA-02291: ...} after the first line. */
    private static final Pattern NESTED_ERROR = Pattern.compile("ORA-(\\d{5}):");

    /** The constraint of {@code ORA-00001: unique constraint (APP.OWNER_EMAIL_UK) violated}, with its schema. */
    private static final Pattern CONSTRAINT = Pattern.compile("\\((?:[^.()]+\\.)?([^.()]+)\\)");

    /** The table and column of {@code cannot insert NULL into ("APP"."OWNER"."EMAIL")} and {@code value too large for column "APP"."OWNER"."EMAIL"}. */
    private static final Pattern TABLE_COLUMN = Pattern.compile("\"[^\"]+\"\\.\"([^\"]+)\"\\.\"([^\"]+)\"");

    /**
     * Recognises {@code ORA-00060}, deadlock detected while waiting for resource, and {@code ORA-08177}, can't
     * serialize access for this transaction. Oracle rolls back the statement that failed, not the transaction, but
     * the transaction cannot complete its work: the conflict repeats on every retry of the statement, and Oracle's
     * remedy is to roll back and run the transaction again, which Storm's transaction does when the failure leaves
     * it. A lock wait that runs out of its {@code WAIT} clause, {@code ORA-30006}, and a {@code NOWAIT} lock that is
     * busy, {@code ORA-00054}, are not transient.
     *
     * @param exception one error of the failure's chain.
     * @return {@code true} if the error reports a deadlock or a serialization failure.
     * @since 1.16
     */
    @Override
    public boolean isTransient(SQLException exception) {
        int errorCode = exception.getErrorCode();
        return errorCode == 60 || errorCode == 8177;
    }

    @Override
    public Optional<IntegrityFailure> integrityFailure(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        int errorCode = exception.getErrorCode();
        if (errorCode == TRANSACTION_ROLLED_BACK) {
            var nested = NESTED_ERROR.matcher(message);
            // The first code is the rollback's own; the one after it is the constraint's.
            if (nested.find() && nested.find()) {
                return integrityFailure(Integer.parseInt(nested.group(1)), message.substring(nested.start()));
            }
            return Optional.empty();
        }
        return integrityFailure(errorCode, message);
    }

    private static Optional<IntegrityFailure> integrityFailure(int errorCode, String message) {
        return switch (errorCode) {
            case 1 -> Optional.of(IntegrityFailure.of(Kind.UNIQUE).withConstraint(constraint(message)));
            case 2291 -> Optional.of(IntegrityFailure.of(Kind.FOREIGN_KEY).withConstraint(constraint(message)));
            case 2292 -> Optional.of(IntegrityFailure.of(Kind.REFERENCED).withConstraint(constraint(message)));
            case 1400, 1407 -> Optional.of(withTableAndColumn(IntegrityFailure.of(Kind.NOT_NULL), message));
            case 2290 -> Optional.of(IntegrityFailure.of(Kind.CHECK).withConstraint(constraint(message)));
            case 12899 -> Optional.of(withTableAndColumn(IntegrityFailure.of(Kind.LENGTH), message));
            case 1438 -> Optional.of(IntegrityFailure.of(Kind.NUMERIC_RANGE));
            default -> Optional.empty();
        };
    }

    private static @Nullable String constraint(String message) {
        var matcher = CONSTRAINT.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }

    private static IntegrityFailure withTableAndColumn(IntegrityFailure failure, String message) {
        var matcher = TABLE_COLUMN.matcher(message);
        return matcher.find() ? failure.withTable(matcher.group(1)).withColumn(matcher.group(2)) : failure;
    }
}
