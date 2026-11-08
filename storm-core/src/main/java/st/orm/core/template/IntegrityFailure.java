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
package st.orm.core.template;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * What a dialect reads from the error a database reports for an integrity violation: the kind of rule, and the
 * names the error carries, as the database spells them. Storm resolves the names to the entity's fields.
 *
 * <p>A dialect fills in what the error states and leaves out what it does not. A name is read from the error's
 * structured fields where the database has them, and from the fixed format of its message where the message is the
 * only place the name appears; a dialect never infers a column the error does not name.</p>
 *
 * @param kind the kind of rule the value broke.
 * @param constraint the name of the violated constraint, or {@code null} when the error names none.
 * @param table the table the error names, or {@code null} when it names none. For a violation of kind
 *              {@link Kind#REFERENCED} this is the table that holds the referencing rows, where the error names it.
 * @param columns the columns the error names, as the database spells them; empty when it names none.
 * @param primaryKey {@code true} when the violated constraint is the table's primary key, which a database may
 *                   report under a name of its own rather than the constraint's.
 * @see IntegrityFailureClassifier
 * @since 1.16
 */
public record IntegrityFailure(Kind kind,
                               @Nullable String constraint,
                               @Nullable String table,
                               List<String> columns,
                               boolean primaryKey) {

    /**
     * The kind of rule a value broke, as a classifier reads it from the error before Storm resolves the names to the
     * entity's fields and reports the violation as the {@link st.orm.IntegrityViolation} record of the kind.
     *
     * @since 1.16
     */
    public enum Kind {
        /** {@link st.orm.IntegrityViolation.Unique}. */
        UNIQUE,
        /** {@link st.orm.IntegrityViolation.ForeignKey}. */
        FOREIGN_KEY,
        /** {@link st.orm.IntegrityViolation.Referenced}. */
        REFERENCED,
        /** {@link st.orm.IntegrityViolation.NotNull}. */
        NOT_NULL,
        /** {@link st.orm.IntegrityViolation.Check}. */
        CHECK,
        /** {@link st.orm.IntegrityViolation.Length}. */
        LENGTH,
        /** {@link st.orm.IntegrityViolation.NumericRange}. */
        NUMERIC_RANGE
    }

    public IntegrityFailure {
        requireNonNull(kind, "kind");
        columns = List.copyOf(requireNonNull(columns, "columns"));
    }

    /**
     * Returns a failure of the given kind that names nothing.
     *
     * @param kind the kind of rule the value broke.
     * @return the failure.
     */
    public static IntegrityFailure of(Kind kind) {
        return new IntegrityFailure(kind, null, null, List.of(), false);
    }

    /**
     * Returns this failure with the constraint name set.
     *
     * @param constraint the name of the violated constraint, or {@code null}.
     * @return the failure.
     */
    public IntegrityFailure withConstraint(@Nullable String constraint) {
        return new IntegrityFailure(kind, constraint, table, columns, primaryKey);
    }

    /**
     * Returns this failure with the table name set.
     *
     * @param table the table the error names, or {@code null}.
     * @return the failure.
     */
    public IntegrityFailure withTable(@Nullable String table) {
        return new IntegrityFailure(kind, constraint, table, columns, primaryKey);
    }

    /**
     * Returns this failure with the column names set.
     *
     * @param columns the columns the error names.
     * @return the failure.
     */
    public IntegrityFailure withColumns(List<String> columns) {
        return new IntegrityFailure(kind, constraint, table, columns, primaryKey);
    }

    /**
     * Returns this failure with the column name set.
     *
     * @param column the column the error names, or {@code null} for none.
     * @return the failure.
     */
    public IntegrityFailure withColumn(@Nullable String column) {
        return withColumns(column == null ? List.of() : List.of(column));
    }

    /**
     * Returns this failure marked as a violation of the table's primary key.
     *
     * @return the failure.
     */
    public IntegrityFailure ofPrimaryKey() {
        return new IntegrityFailure(kind, constraint, table, columns, true);
    }
}
