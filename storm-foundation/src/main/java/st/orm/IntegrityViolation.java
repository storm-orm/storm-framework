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
package st.orm;

import static java.util.Objects.requireNonNull;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A value that breaks a rule of its table: a duplicate under a unique key, a reference to a row that does not exist,
 * a null in a column that refuses it, a value a check constraint rejects, or a value too large for its column. Each
 * kind of rule is a record of its own, carrying what its kind can report, so a caller matches on the type.
 *
 * <p>Integrity is meant in the sense of the data conforming to its schema. It spans what SQL calls an integrity
 * constraint violation, SQL state class {@code 23}, and a value too large for its column, which SQL files under data
 * exceptions, class {@code 22}: both are a value the table refuses, and a caller handles them as one. Each record
 * names the SQL state the standard assigns to its kind; a database may report the violation under a code of its
 * own, which its dialect recognises, and the driver's exception stays the cause of the
 * {@link IntegrityViolationException} for a caller that needs the code as reported.</p>
 *
 * <p>A violation is reported in terms of the entity: the {@linkplain #fields() fields} it resolves to as metamodel
 * keys, and, where the database reported them, the name of the {@linkplain #constraint() constraint} and the
 * {@linkplain #table() table}. The database reports one violation per failed write; the checks of {@code validate}
 * report every violation of an entity, each with the limit the value exceeded. A field is set only when the error or
 * the check identifies the column; a violation whose column the database does not name has no fields.</p>
 *
 * @see IntegrityViolationException
 * @since 1.16
 */
public sealed interface IntegrityViolation {

    /**
     * Returns the name of the violated constraint, as the database reported it. {@code null} for a violation the
     * database did not report, such as one of {@code validate}, for a database that names no constraint for the
     * kind, and for a kind that has no named constraint: a null, a value too long or a value out of range.
     *
     * @return the constraint name, or {@code null}.
     */
    default @Nullable String constraint() {
        return null;
    }

    /**
     * Returns the table the violated constraint belongs to. For a {@link Referenced} violation this is the table that
     * holds the referencing rows.
     *
     * @return the table name, or {@code null} when it is not known.
     */
    @Nullable String table();

    /**
     * Returns the entity type of the write that was refused. For a {@link Referenced} violation this is the type of
     * the removed row.
     *
     * @return the entity type, or {@code null} when the write names none.
     */
    @Nullable Class<? extends Data> type();

    /**
     * Returns the fields the violation resolves to. A violation of a compound key resolves to each of its fields.
     * Empty when the database does not say which column, which the fields of the entity cannot be inferred from,
     * and for a kind that names none: a row still referenced, whose referencing rows name no field of the removed
     * row, and a check constraint.
     *
     * @return the fields, as metamodel keys.
     */
    default List<Metamodel<?, ?>> fields() {
        return List.of();
    }

    /**
     * Returns whether the violation resolves to the given field.
     *
     * @param field the field to test for.
     * @return {@code true} if the field is among the violation's fields.
     */
    default boolean on(Metamodel<?, ?> field) {
        return fields().contains(field);
    }

    /**
     * A value, or a combination of values, that another row already holds under a unique key or the primary key. SQL
     * state {@code 23505}, unique violation.
     *
     * @param constraint the name of the violated key, or {@code null} when the database reported none.
     * @param table the table the key belongs to, or {@code null} when it is not known.
     * @param type the entity type of the write, or {@code null} when it is not known.
     * @param fields the fields of the key; empty when they are not identified.
     * @since 1.16
     */
    record Unique(@Nullable String constraint,
                  @Nullable String table,
                  @Nullable Class<? extends Data> type,
                  List<Metamodel<?, ?>> fields) implements IntegrityViolation {
        public Unique {
            fields = List.copyOf(requireNonNull(fields, "fields"));
        }
    }

    /**
     * A foreign key that references a row that does not exist. SQL state {@code 23503}, foreign key violation.
     *
     * @param constraint the name of the violated foreign key, or {@code null} when the database reported none.
     * @param table the table that holds the foreign key, or {@code null} when it is not known.
     * @param type the entity type of the write, or {@code null} when it is not known.
     * @param fields the fields of the foreign key; empty when they are not identified.
     * @since 1.16
     */
    record ForeignKey(@Nullable String constraint,
                      @Nullable String table,
                      @Nullable Class<? extends Data> type,
                      List<Metamodel<?, ?>> fields) implements IntegrityViolation {
        public ForeignKey {
            fields = List.copyOf(requireNonNull(fields, "fields"));
        }
    }

    /**
     * A row that other rows still reference through a foreign key, so it cannot be removed, or have its key changed.
     * SQL state {@code 23503} as well; the statement and the schema tell it apart from a reference to a missing row.
     * The referencing rows name no field of the removed row.
     *
     * @param constraint the name of the foreign key that references the row, or {@code null} when the database
     *                   reported none.
     * @param table the table that holds the referencing rows, or {@code null} when it is not known.
     * @param type the type of the removed row, or {@code null} when it is not known.
     * @since 1.16
     */
    record Referenced(@Nullable String constraint,
                      @Nullable String table,
                      @Nullable Class<? extends Data> type) implements IntegrityViolation {}

    /**
     * A null in a column that does not accept one. SQL state {@code 23502}, not null violation. No database names a
     * constraint for it.
     *
     * @param table the table the column belongs to, or {@code null} when it is not known.
     * @param type the entity type of the write, or {@code null} when it is not known.
     * @param fields the field of the column; empty when it is not identified.
     * @since 1.16
     */
    record NotNull(@Nullable String table,
                   @Nullable Class<? extends Data> type,
                   List<Metamodel<?, ?>> fields) implements IntegrityViolation {
        public NotNull {
            fields = List.copyOf(requireNonNull(fields, "fields"));
        }
    }

    /**
     * A value a check constraint rejects. SQL state {@code 23514}, check violation. A check constraint names no
     * column in its error.
     *
     * @param constraint the name of the check constraint, or {@code null} when the database reported none.
     * @param table the table the constraint belongs to, or {@code null} when it is not known.
     * @param type the entity type of the write, or {@code null} when it is not known.
     * @since 1.16
     */
    record Check(@Nullable String constraint,
                 @Nullable String table,
                 @Nullable Class<? extends Data> type) implements IntegrityViolation {}

    /**
     * A string longer than its column. SQL state {@code 22001}, string data right truncation. A column's length is
     * no named constraint.
     *
     * @param table the table the column belongs to, or {@code null} when it is not known.
     * @param type the entity type of the write, or {@code null} when it is not known.
     * @param fields the field of the column; empty when the database does not name it.
     * @param maxLength the column's length in characters, known for a violation {@code validate} found; {@code null}
     *                  for one the database reported.
     * @since 1.16
     */
    record Length(@Nullable String table,
                  @Nullable Class<? extends Data> type,
                  List<Metamodel<?, ?>> fields,
                  @Nullable Integer maxLength) implements IntegrityViolation {
        public Length {
            fields = List.copyOf(requireNonNull(fields, "fields"));
        }
    }

    /**
     * A number with more digits before the decimal point than its column holds. SQL state {@code 22003}, numeric
     * value out of range. A column's precision is no named constraint.
     *
     * @param table the table the column belongs to, or {@code null} when it is not known.
     * @param type the entity type of the write, or {@code null} when it is not known.
     * @param fields the field of the column; empty when the database does not name it.
     * @param precision the column's precision, the number of digits it holds, known for a violation {@code validate}
     *                  found; {@code null} for one the database reported.
     * @param scale the column's scale, the number of digits after the decimal point, known with the precision.
     * @since 1.16
     */
    record NumericRange(@Nullable String table,
                        @Nullable Class<? extends Data> type,
                        List<Metamodel<?, ?>> fields,
                        @Nullable Integer precision,
                        @Nullable Integer scale) implements IntegrityViolation {
        public NumericRange {
            fields = List.copyOf(requireNonNull(fields, "fields"));
        }
    }
}
