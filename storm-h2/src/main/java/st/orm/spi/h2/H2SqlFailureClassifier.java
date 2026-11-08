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
package st.orm.spi.h2;

import java.sql.SQLException;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.core.template.SqlFailureClassifier;

/**
 * Reads the errors H2 reports for a constraint, by error code: {@code 23505} unique index or primary key violation,
 * {@code 23506} a foreign key whose parent row is absent, {@code 23503} a row a foreign key still references,
 * {@code 23502} null not allowed, {@code 23513} check constraint violation, {@code 22001} value too long and
 * {@code 22003} numeric value out of range. H2 reports a number with more digits than its column holds as a value too
 * long as well, naming the column with its numeric type, which reads as out of range.
 *
 * <p>The names come from the message, which H2 quotes in a fixed form: the index and table of a unique violation,
 * the constraint and referencing table of a referential violation, the constraint of a check violation, and the
 * column of a null or a value too long.</p>
 *
 * @since 1.16
 */
public final class H2SqlFailureClassifier implements SqlFailureClassifier {

    /** {@code Unique index or primary key violation: "PUBLIC.OWNER_EMAIL_UK ON PUBLIC.OWNER(EMAIL ...) ..."}. */
    private static final Pattern UNIQUE_VIOLATION = Pattern.compile("\"(?:PRIMARY KEY|(?:[^\".]+\\.)?([^\" ]+)) ON (?:[^\".]+\\.)?([^\"(]+)\\(");

    /** {@code Referential integrity constraint violation: "PET_OWNER_FK: PUBLIC.PET FOREIGN KEY(OWNER_ID) ..."}. */
    private static final Pattern REFERENTIAL_VIOLATION = Pattern.compile("\"([^\":]+): (?:[^\".]+\\.)?([^\" ]+) FOREIGN KEY");

    /** {@code Check constraint violation: "OWNER_AGE_CK: ..."}. */
    private static final Pattern CHECK_VIOLATION = Pattern.compile("\"([^\":]+):");

    /** {@code NULL not allowed for column "EMAIL"} and {@code Value too long for column "EMAIL CHARACTER VARYING(5)"}. */
    private static final Pattern COLUMN = Pattern.compile("for column \"([^\" ]+)");

    /** The type of {@code Value too long for column "AMOUNT DECIMAL(5, 2)"}, which H2 reports for a number as well. */
    private static final Pattern NUMERIC_COLUMN = Pattern.compile("for column \"[^\" ]+ (?:DECIMAL|NUMERIC|DECFLOAT|NUMBER|DEC)\\b");

    /**
     * Recognises error {@code 40001}, {@code DEADLOCK_1}, which H2 reports, under SQL state {@code 40001}, both for a
     * deadlock and for a write that conflicts with a serializable or repeatable read transaction's snapshot; H2
     * rolls the transaction back on it. A lock wait that runs out of {@code LOCK_TIMEOUT}, error {@code 50200}, is
     * not transient.
     *
     * @param exception one error of the failure's chain.
     * @return {@code true} if the error reports a deadlock or a serialization conflict.
     * @since 1.16
     */
    @Override
    public boolean isTransient(SQLException exception) {
        return exception.getErrorCode() == 40001;
    }

    @Override
    public Optional<IntegrityFailure> integrityFailure(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return switch (exception.getErrorCode()) {
            case 23505 -> {
                var failure = IntegrityFailure.of(Kind.UNIQUE);
                var matcher = UNIQUE_VIOLATION.matcher(message);
                if (matcher.find()) {
                    failure = failure.withTable(matcher.group(2));
                    failure = matcher.group(1) == null ? failure.ofPrimaryKey() : failure.withConstraint(matcher.group(1));
                }
                yield Optional.of(failure);
            }
            case 23506, 23503 -> {
                var failure = IntegrityFailure.of(exception.getErrorCode() == 23506 ? Kind.FOREIGN_KEY : Kind.REFERENCED);
                var matcher = REFERENTIAL_VIOLATION.matcher(message);
                if (matcher.find()) {
                    failure = failure.withConstraint(matcher.group(1)).withTable(matcher.group(2));
                }
                yield Optional.of(failure);
            }
            case 23502 -> Optional.of(IntegrityFailure.of(Kind.NOT_NULL).withColumn(column(message)));
            case 23513 -> {
                var matcher = CHECK_VIOLATION.matcher(message);
                yield Optional.of(IntegrityFailure.of(Kind.CHECK).withConstraint(matcher.find() ? matcher.group(1) : null));
            }
            case 22001 -> Optional.of(IntegrityFailure.of(NUMERIC_COLUMN.matcher(message).find() ? Kind.NUMERIC_RANGE : Kind.LENGTH)
                    .withColumn(column(message)));
            case 22003 -> Optional.of(IntegrityFailure.of(Kind.NUMERIC_RANGE).withColumn(column(message)));
            default -> Optional.empty();
        };
    }

    private static @Nullable String column(String message) {
        var matcher = COLUMN.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
