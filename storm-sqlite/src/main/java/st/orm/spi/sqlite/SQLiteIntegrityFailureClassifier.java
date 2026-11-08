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
package st.orm.spi.sqlite;

import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.IntegrityFailure;
import st.orm.core.template.IntegrityFailure.Kind;
import st.orm.core.template.IntegrityFailureClassifier;

/**
 * Reads the constraint violations SQLite reports under result code {@code SQLITE_CONSTRAINT}, by the extended result
 * code: {@code UNIQUE} and {@code PRIMARYKEY}, {@code FOREIGNKEY}, {@code NOTNULL} and {@code CHECK}. The driver
 * exposes the extended code on its own exception type, read here by reflection, as the module does not depend on
 * the driver; where it is not the driver's, the name of the code at the start of the message decides. The message
 * names the columns of a unique, primary key or not null violation, each with its table, and the constraint of a
 * check violation; a foreign key violation names nothing, and whether the parent row is absent or the row is still
 * referenced follows from the statement. SQLite stores any value whatever a column's declared size, so it reports
 * no value too long or out of range.
 *
 * @since 1.16
 */
public final class SQLiteIntegrityFailureClassifier implements IntegrityFailureClassifier {

    /**
     * The primary result code SQLite reports for a constraint violation; the extended code says which constraint.
     */
    private static final int SQLITE_CONSTRAINT = 19;

    private static final int SQLITE_CONSTRAINT_CHECK = 275;
    private static final int SQLITE_CONSTRAINT_FOREIGNKEY = 787;
    private static final int SQLITE_CONSTRAINT_NOTNULL = 1299;
    private static final int SQLITE_CONSTRAINT_PRIMARYKEY = 1555;
    private static final int SQLITE_CONSTRAINT_UNIQUE = 2067;

    /** The columns of {@code UNIQUE constraint failed: owner.email, owner.name} and {@code NOT NULL constraint failed: owner.email}. */
    private static final Pattern CONSTRAINT_COLUMNS = Pattern.compile("constraint failed: ([^)]+?)\\)?$");

    /** The constraint of {@code CHECK constraint failed: owner_age_ck}. */
    private static final Pattern CHECK_CONSTRAINT = Pattern.compile("CHECK constraint failed: ([^)]+?)\\)?$");

    @Override
    public Optional<IntegrityFailure> classify(SQLException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (exception.getErrorCode() != SQLITE_CONSTRAINT) {
            return Optional.empty();
        }
        return switch (extendedCode(exception, message)) {
            case SQLITE_CONSTRAINT_UNIQUE -> Optional.of(IntegrityFailure.of(Kind.UNIQUE).withColumns(columns(message)));
            case SQLITE_CONSTRAINT_PRIMARYKEY -> Optional.of(IntegrityFailure.of(Kind.UNIQUE).ofPrimaryKey());
            case SQLITE_CONSTRAINT_FOREIGNKEY -> Optional.of(IntegrityFailure.of(Kind.FOREIGN_KEY));
            case SQLITE_CONSTRAINT_NOTNULL -> Optional.of(IntegrityFailure.of(Kind.NOT_NULL).withColumns(columns(message)));
            case SQLITE_CONSTRAINT_CHECK -> {
                var matcher = CHECK_CONSTRAINT.matcher(message);
                yield Optional.of(IntegrityFailure.of(Kind.CHECK).withConstraint(matcher.find() ? matcher.group(1).trim() : null));
            }
            default -> Optional.empty();
        };
    }

    /**
     * Returns the extended result code of the error: the one the driver's exception carries, or the one whose name
     * opens the message, as the driver writes it, or {@code 0} for neither.
     */
    private static int extendedCode(SQLException exception, String message) {
        Integer fromDriver = resultCode(exception);
        if (fromDriver != null) {
            return fromDriver;
        }
        if (message.startsWith("[SQLITE_CONSTRAINT_UNIQUE]")) {
            return SQLITE_CONSTRAINT_UNIQUE;
        }
        if (message.startsWith("[SQLITE_CONSTRAINT_PRIMARYKEY]")) {
            return SQLITE_CONSTRAINT_PRIMARYKEY;
        }
        if (message.startsWith("[SQLITE_CONSTRAINT_FOREIGNKEY]")) {
            return SQLITE_CONSTRAINT_FOREIGNKEY;
        }
        if (message.startsWith("[SQLITE_CONSTRAINT_NOTNULL]")) {
            return SQLITE_CONSTRAINT_NOTNULL;
        }
        if (message.startsWith("[SQLITE_CONSTRAINT_CHECK]")) {
            return SQLITE_CONSTRAINT_CHECK;
        }
        return 0;
    }

    /**
     * Reads the extended result code off the driver's exception, {@code getResultCode().code}, or {@code null} when
     * the exception is not the driver's.
     */
    private static @Nullable Integer resultCode(SQLException exception) {
        try {
            var resultCode = exception.getClass().getMethod("getResultCode").invoke(exception);
            return resultCode == null ? null : (Integer) resultCode.getClass().getField("code").get(resultCode);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static List<String> columns(String message) {
        var matcher = CONSTRAINT_COLUMNS.matcher(message);
        if (!matcher.find()) {
            return List.of();
        }
        return Stream.of(matcher.group(1).split(","))
                .map(String::trim)
                .map(column -> column.substring(column.lastIndexOf('.') + 1))
                .filter(column -> !column.isEmpty())
                .toList();
    }
}
