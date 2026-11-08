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
package st.orm.spi.mssqlserver;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.core.template.SqlFailureClassifier;

/**
 * Reads the errors SQL Server reports for a constraint, by error number: {@code 2627} a violated primary key or
 * unique key constraint and {@code 2601} a duplicate under a unique index, {@code 547} a statement that conflicts
 * with a foreign key, reference or check constraint, {@code 515} a null in a column that does not allow it,
 * {@code 2628} and {@code 8152} string or binary data that would be truncated, and {@code 8115} and {@code 220} an
 * arithmetic overflow.
 *
 * <p>The names come from the message, read from the identifiers SQL Server quotes, in the order it quotes them,
 * rather than from the words around them, which a server configured for another language translates. Error
 * {@code 547} covers three kinds, told apart by the SQL term the message carries for the constraint,
 * {@code FOREIGN KEY}, {@code REFERENCE} or {@code CHECK}, which a translation keeps; a message that carries none
 * of them reports no violation. A conflict with a {@code FOREIGN KEY} constraint is an insert or update whose parent
 * row is absent; the table the message names is the parent's, so it is left out. A conflict with a {@code REFERENCE}
 * constraint is a delete or update of a row other rows still reference; the table the message names holds them.</p>
 *
 * @since 1.16
 */
public final class MSSQLServerSqlFailureClassifier implements SqlFailureClassifier {

    /** A name in single quotes: {@code 'owner_email_uk'}, {@code 'dbo.owner'}, {@code 'db.dbo.owner'}. */
    private static final Pattern SINGLE_QUOTED = Pattern.compile("'([^']*)'");

    /** A name in double quotes: {@code "pet_owner_fk"}, {@code "dbo.owner"}. */
    private static final Pattern DOUBLE_QUOTED = Pattern.compile("\"([^\"]*)\"");

    /**
     * Recognises the errors SQL Server aborts the transaction with when another transaction conflicts with it:
     * {@code 1205}, chosen as deadlock victim, which also covers a conflict under serializable isolation, as SQL
     * Server serializes with locks; {@code 3960}, snapshot isolation transaction aborted due to update conflict; and
     * {@code 41302}, the update conflict of a transaction on a memory-optimized table. A lock request that runs out
     * of {@code LOCK_TIMEOUT}, {@code 1222}, is not transient: SQL Server cancels only the statement.
     *
     * @param exception one error of the failure's chain.
     * @return {@code true} if the error reports a deadlock or an update conflict.
     * @since 1.16
     */
    @Override
    public boolean isTransient(SQLException exception) {
        int errorCode = exception.getErrorCode();
        return errorCode == 1205 || errorCode == 3960 || errorCode == 41302;
    }

    @Override
    public Optional<IntegrityFailure> integrityFailure(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        return switch (exception.getErrorCode()) {
            case 2627 -> {
                // Violation of UNIQUE KEY constraint 'uk'. Cannot insert duplicate key in object 'dbo.owner'.
                var quoted = quoted(SINGLE_QUOTED, message);
                var failure = IntegrityFailure.of(Kind.UNIQUE)
                        .withConstraint(at(quoted, 0))
                        .withTable(unqualified(at(quoted, 1)));
                yield Optional.of(message.contains("PRIMARY KEY") ? failure.ofPrimaryKey() : failure);
            }
            case 2601 -> {
                // Cannot insert duplicate key row in object 'dbo.owner' with unique index 'owner_email_ix'.
                var quoted = quoted(SINGLE_QUOTED, message);
                yield Optional.of(IntegrityFailure.of(Kind.UNIQUE)
                        .withTable(unqualified(at(quoted, 0)))
                        .withConstraint(at(quoted, 1)));
            }
            case 547 -> {
                // The INSERT statement conflicted with the FOREIGN KEY constraint "fk". The conflict occurred in
                // database "db", table "dbo.owner", column 'id'.
                var quoted = quoted(DOUBLE_QUOTED, message);
                var constraint = at(quoted, 0);
                var table = unqualified(quoted.size() > 1 ? quoted.getLast() : null);
                if (message.contains("REFERENCE")) {
                    yield Optional.of(IntegrityFailure.of(Kind.REFERENCED).withConstraint(constraint).withTable(table));
                }
                if (message.contains("FOREIGN KEY")) {
                    yield Optional.of(IntegrityFailure.of(Kind.FOREIGN_KEY).withConstraint(constraint));
                }
                if (message.contains("CHECK")) {
                    yield Optional.of(IntegrityFailure.of(Kind.CHECK).withConstraint(constraint).withTable(table));
                }
                yield Optional.empty();
            }
            case 515 -> {
                // Cannot insert the value NULL into column 'email', table 'db.dbo.owner'; column does not allow nulls.
                var quoted = quoted(SINGLE_QUOTED, message);
                yield Optional.of(IntegrityFailure.of(Kind.NOT_NULL)
                        .withColumn(at(quoted, 0))
                        .withTable(unqualified(at(quoted, 1))));
            }
            case 2628 -> {
                // String or binary data would be truncated in table 'db.dbo.owner', column 'email'. Truncated value: '...'.
                var quoted = quoted(SINGLE_QUOTED, message);
                yield Optional.of(IntegrityFailure.of(Kind.LENGTH)
                        .withTable(unqualified(at(quoted, 0)))
                        .withColumn(at(quoted, 1)));
            }
            case 8152 -> Optional.of(IntegrityFailure.of(Kind.LENGTH));
            case 8115, 220 -> Optional.of(IntegrityFailure.of(Kind.NUMERIC_RANGE));
            default -> Optional.empty();
        };
    }

    private static List<String> quoted(Pattern quote, String message) {
        List<String> names = new ArrayList<>();
        var matcher = quote.matcher(message);
        while (matcher.find()) {
            names.add(matcher.group(1));
        }
        return names;
    }

    private static @Nullable String at(List<String> names, int index) {
        return index < names.size() ? names.get(index) : null;
    }

    /** Strips the database and schema from {@code db.dbo.owner}. */
    private static @Nullable String unqualified(@Nullable String name) {
        return name == null ? null : name.substring(name.lastIndexOf('.') + 1);
    }
}
