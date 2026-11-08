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
package st.orm.spi.mysql;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.core.template.IntegrityFailureClassifier;

/**
 * Reads the errors MySQL reports for a constraint, by error code: {@code 1062} duplicate entry, {@code 1452} a
 * foreign key whose parent row is absent, {@code 1451} a row a foreign key still references, {@code 1048} a column
 * that cannot be null and {@code 1364} a field without a default that the insert leaves out, {@code 3819} a check
 * constraint that is violated, {@code 1406} data too long for a column and {@code 1264} a value out of range for a
 * column.
 *
 * <p>The names come from the message, read from the identifiers MySQL quotes rather than from the words around
 * them, which a server configured for another language translates: the last quoted name of a duplicate is its key,
 * {@code PRIMARY} for the primary key; the first quoted name of a null, a value too long, a value out of range or a
 * check is the column or the constraint; a foreign key failure names its constraint and referencing table in the
 * backquoted constraint definition.</p>
 *
 * @since 1.16
 */
public class MySQLIntegrityFailureClassifier implements IntegrityFailureClassifier {

    /** A name in single quotes, as MySQL quotes a key or a column: {@code 'owner.owner_email_uk'}. */
    private static final Pattern QUOTED = Pattern.compile("'([^']*)'");

    /** The constraint and referencing table of {@code a foreign key constraint fails (`db`.`pet`, CONSTRAINT `pet_owner_fk` ...)}. */
    private static final Pattern FOREIGN_KEY_CONSTRAINT = Pattern.compile("\\(`[^`]*`\\.`([^`]+)`, CONSTRAINT `([^`]+)`");

    @Override
    public Optional<IntegrityFailure> classify(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return switch (exception.getErrorCode()) {
            case 1062 -> {
                var failure = IntegrityFailure.of(Kind.UNIQUE);
                var quoted = quoted(message);
                if (!quoted.isEmpty()) {
                    // The key, after the entry: 'owner.owner_email_uk' names the table as well, 'PRIMARY' the primary key.
                    var key = quoted.getLast();
                    key = key.substring(key.lastIndexOf('.') + 1);
                    failure = "PRIMARY".equals(key) ? failure.ofPrimaryKey() : failure.withConstraint(key);
                }
                yield Optional.of(failure);
            }
            case 1452, 1451 -> {
                var failure = IntegrityFailure.of(exception.getErrorCode() == 1452 ? Kind.FOREIGN_KEY : Kind.REFERENCED);
                var matcher = FOREIGN_KEY_CONSTRAINT.matcher(message);
                if (matcher.find()) {
                    failure = failure.withTable(matcher.group(1)).withConstraint(matcher.group(2));
                }
                yield Optional.of(failure);
            }
            case 1048, 1364 -> Optional.of(IntegrityFailure.of(Kind.NOT_NULL).withColumn(firstQuoted(message)));
            case 3819 -> Optional.of(IntegrityFailure.of(Kind.CHECK).withConstraint(firstQuoted(message)));
            case 1406 -> Optional.of(IntegrityFailure.of(Kind.LENGTH).withColumn(firstQuoted(message)));
            case 1264 -> Optional.of(IntegrityFailure.of(Kind.NUMERIC_RANGE).withColumn(firstQuoted(message)));
            default -> Optional.empty();
        };
    }

    /** Returns the names a message quotes, in order. */
    private static List<String> quoted(String message) {
        List<String> names = new ArrayList<>();
        var matcher = QUOTED.matcher(message);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    /** Returns the first name a message quotes, or {@code null} when it quotes none. */
    private static @Nullable String firstQuoted(String message) {
        var matcher = QUOTED.matcher(message);
        return matcher.find() ? matcher.group(1) : null;
    }
}
