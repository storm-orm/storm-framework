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

import org.jspecify.annotations.Nullable;

/**
 * Represents a comparison operator in a SQL query.
 *
 * <p><strong>Caching contract:</strong> Operators may participate in template caching where the operator instance
 * becomes part of the cache key for a compiled SQL shape. Custom operator implementations must therefore implement
 * stable {@link Object#equals(Object)} and {@link Object#hashCode()} semantics. Two operators must be considered equal
 * (and have the same hash code) if they produce the same SQL shape, so that equivalent templates map to the same cached
 * compilation result.</p>
 */
@SuppressWarnings({"SwitchStatementWithTooFewBranches", "unused"})
public interface Operator {

    /**
     * The {@code IN} operator.
     */
    Operator IN = (column, placeholders) -> switch (placeholders.length) {
        case 0 -> "1 <> 1";
        default -> "%s IN (%s)".formatted(requireColumn(column), String.join(", ", placeholders));
    };

    /**
     * The {@code NOT IN} operator.
     */
    Operator NOT_IN = (column, placeholders) -> switch (placeholders.length) {
        case 0 -> "1 = 1";
        default -> "%s NOT IN (%s)".formatted(requireColumn(column), String.join(", ", placeholders));
    };

    /**
     * The {@code =} operator.
     */
    Operator EQUALS = (column, placeholders) -> format("Equals", 1, placeholders.length, "%s = %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code <>} operator.
     */
    Operator NOT_EQUALS = (column, placeholders) -> format("Not equals", 1, placeholders.length, "%s <> %s".formatted(requireColumn(column), Operator.get(placeholders)));

    /**
     * The {@code LIKE} operator.
     */
    Operator LIKE = (column, placeholders) -> format("Like", 1, placeholders.length, "%s LIKE %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code NOT LIKE} operator.
     */
    Operator NOT_LIKE = (column, placeholders) -> format("Not like", 1, placeholders.length, "%s NOT LIKE %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * Matches a column that contains the given text anywhere in its value.
     *
     * <p>The value is literal text rather than a pattern: Storm escapes the characters the database reads as
     * wildcards, adds the wildcards itself and renders {@code column LIKE ? ESCAPE '!'}, so {@code 50%} matches exactly
     * {@code 50%} on every database. Empty text matches every non-null value. Case sensitivity follows the column's
     * collation, as it does for {@link #LIKE}. The operator applies to a single column holding text.</p>
     *
     * @since 1.15
     */
    Operator CONTAINS = (column, placeholders) -> format("Contains", 1, placeholders.length, "%s LIKE %s ESCAPE '!'".formatted(requireColumn(column), get(placeholders)));

    /**
     * Matches a column that does not contain the given text, the negation of {@link #CONTAINS}.
     *
     * @since 1.15
     */
    Operator NOT_CONTAINS = (column, placeholders) -> format("Not contains", 1, placeholders.length, "%s NOT LIKE %s ESCAPE '!'".formatted(requireColumn(column), get(placeholders)));

    /**
     * Matches a column whose value starts with the given text. The value is literal text, as for {@link #CONTAINS}.
     *
     * @since 1.15
     */
    Operator STARTS_WITH = (column, placeholders) -> format("Starts with", 1, placeholders.length, "%s LIKE %s ESCAPE '!'".formatted(requireColumn(column), get(placeholders)));

    /**
     * Matches a column whose value does not start with the given text, the negation of {@link #STARTS_WITH}.
     *
     * @since 1.15
     */
    Operator NOT_STARTS_WITH = (column, placeholders) -> format("Not starts with", 1, placeholders.length, "%s NOT LIKE %s ESCAPE '!'".formatted(requireColumn(column), get(placeholders)));

    /**
     * Matches a column whose value ends with the given text. The value is literal text, as for {@link #CONTAINS}.
     *
     * @since 1.15
     */
    Operator ENDS_WITH = (column, placeholders) -> format("Ends with", 1, placeholders.length, "%s LIKE %s ESCAPE '!'".formatted(requireColumn(column), get(placeholders)));

    /**
     * Matches a column whose value does not end with the given text, the negation of {@link #ENDS_WITH}.
     *
     * @since 1.15
     */
    Operator NOT_ENDS_WITH = (column, placeholders) -> format("Not ends with", 1, placeholders.length, "%s NOT LIKE %s ESCAPE '!'".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code >} operator.
     */
    Operator GREATER_THAN = (column, placeholders) -> format("Greater than", 1 , placeholders.length, "%s > %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code >=} operator.
     */
    Operator GREATER_THAN_OR_EQUAL = (column, placeholders) -> format("Greater than or equal", 1, placeholders.length, "%s >= %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code <} operator.
     */
    Operator LESS_THAN = (column, placeholders) -> format("Less than", 1, placeholders.length, "%s < %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code <=} operator.
     */
    Operator LESS_THAN_OR_EQUAL= (column, placeholders) -> format("Less than or equal", 1, placeholders.length, "%s <= %s".formatted(requireColumn(column), get(placeholders)));

    /**
     * The {@code BETWEEN} operator.
     */
    Operator BETWEEN = (column, placeholders) -> format("Between", 2, placeholders.length, "%s BETWEEN %s AND %s".formatted(requireColumn(column), get(placeholders), get(1, placeholders)));

    /**
     * The {@code IS TRUE} operator.
     */
    Operator IS_TRUE = (column, placeholders) -> format("Is true", 0, placeholders.length, "%s IS TRUE".formatted(requireColumn(column)));

    /**
     * The {@code IS FALSE} operator.
     */
    Operator IS_FALSE = (column, placeholders) -> format("Is false", 0, placeholders.length, "%s IS FALSE".formatted(requireColumn(column)));

    /**
     * The {@code IS NULL} operator.
     */
    Operator IS_NULL = (column, placeholders) -> format("Is null", 0, placeholders.length, "%s IS NULL".formatted(requireColumn(column)));

    /**
     * The {@code IS NOT NULL} operator.
     */
    Operator IS_NOT_NULL = (column, placeholders) -> format("Is not null", 0, placeholders.length, "%s IS NOT NULL".formatted(requireColumn(column)));

    /**
     * Formats the operator with bind variables matching the specified size.
     *
     * @param column the column to compare.
     * @param placeholders the placeholders to use in the template.
     * @return the formatted operator.
     * @throws IllegalArgumentException if the column is null but required by the operator, or if the number of
     * placeholders is not supported by the operator.
     */
    String format(@Nullable String column, String... placeholders);

    private static String format(String name, int requiredSize, int actualSize, String operator) {
        if (requiredSize != actualSize) {
            throw new IllegalArgumentException("%s operator requires %s value(s). Found %s value(s).".formatted(name, requiredSize, actualSize));
        }
        return operator;
    }

    private static String requireColumn(@Nullable String column) {
        if (column == null) {
            throw new IllegalArgumentException("Column name cannot be null.");
        }
        return column;
    }

    private static String get(String... placeholders) {
        return get(0, placeholders);
    }

    private static String get(int index, String... placeholders) {
        if (index < 0 || index >= placeholders.length) {
            throw new IllegalArgumentException("Unexpected number of placeholders. Expected at least %s but found %d.".formatted(index + 1, placeholders.length));
        }
        return placeholders[index];
    }
}
