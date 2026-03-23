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

import static java.util.stream.Collectors.joining;
import static st.orm.Operator.BETWEEN;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.IS_NOT_NULL;
import static st.orm.Operator.IS_NULL;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LESS_THAN_OR_EQUAL;

import jakarta.annotation.Nonnull;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import st.orm.Operator;

/**
 * Represents a specific SQL dialect with methods to determine feature support and handle identifier escaping.
 *
 * @since 1.1
 */
public interface SqlDialect {

    /**
     * Returns the name of the SQL dialect.
     *
     * @return the name of the SQL dialect.
     * @since 1.2
     */
    String name();

    /**
     * Indicates whether the SQL dialect supports delete aliases.
     *
     * <p>Delete aliases allow delete statements to use table aliases in joins,  making it easier to filter rows based
     * on related data.</p>
     *
     * @return {@code true} if delete aliases are supported, {@code false} otherwise.
     */
    boolean supportsDeleteAlias();

    /**
     * Indicates whether the SQL dialect supports multi-value tuples in the IN clause.
     *
     * @return {@code true} if multi-value tuples are supported, {@code false} otherwise.
     * @since 1.2
     */
    boolean supportsMultiValueTuples();

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.2
     */
    Pattern getValidIdentifierPattern();

    /**
     * Indicates whether the given name is a keyword in this SQL dialect.
     *
     * @param name the name to check.
     * @return {@code true} if the name is a keyword, {@code false} otherwise.
     * @since 1.2
     */
    boolean isKeyword(@Nonnull String name);

    /**
     * Escapes the given database identifier (e.g., table or column name) according to this SQL dialect.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    String escape(@Nonnull String name);

    /**
     * Returns a safe identifier for the given name, possibly escaping it if it is a keyword or contains invalid
     * characters.
     *
     * @param name the name to check.
     * @return a safe identifier for the given name.
     * @since 1.2
     */
    default String getSafeIdentifier(String name) {
        if (isKeyword(name) || !getValidIdentifierPattern().matcher(name).matches()) {
            return escape(name);
        }
        return name;
    }

    /**
     * Returns the pattern for single line comments.
     *
     * @return the pattern for single line comments.
     * @since 1.2
     */
    Pattern getSingleLineCommentPattern();

    /**
     * Returns the pattern for multi line comments.
     *
     * @return the pattern for multi line comments.
     * @since 1.2
     */
    Pattern getMultiLineCommentPattern();

    /**
     * Returns the pattern for identifiers.
     *
     * @return the pattern for identifiers.
     * @since 1.2
     */
    Pattern getIdentifierPattern();

    /**
     * Returns the pattern for string literals.
     *
     * @return the pattern for string literals.
     * @since 1.2
     */
    Pattern getQuoteLiteralPattern();

    /**
     * Builds a multi-value IN clause.
     *
     * <p>The provided values are processed in a deterministic order. First, the list is iterated row by row. For each
     * row, the values of the map are then processed in the map’s iteration order. This order is used both for SQL
     * rendering and for parameter binding.</p>
     *
     * @param values the multi-row values to use in the IN clause. Each map represents a single row.
     * @param parameterFunction the function responsible for binding the parameters to the SQL template and returning
     * the string representation of each parameter, either a '?' placeholder or a literal value.
     * @return the string that represents the multi-value IN clause.
     * @throws SqlTemplateException if the values are incompatible.
     * @since 1.2
     */
    String multiValueIn(@Nonnull List<SequencedMap<String, Object>> values,
                        @Nonnull Function<Object, String> parameterFunction)
            throws SqlTemplateException;

    /**
     * Builds a multi-column expression for the given operator.
     *
     * <p>This method generalizes {@link #multiValueIn} to support all comparison operators when multiple columns are
     * involved (e.g., inline records or compound keys). The default implementation produces universally supported SQL.
     * Dialects that support tuple comparison syntax can override this method to produce more
     * compact SQL like {@code (a, b) > (?, ?)}.</p>
     *
     * <p>For comparison operators ({@code >}, {@code >=}, {@code <}, {@code <=}), the default implementation uses
     * lexicographic expansion. For example, {@code (a, b) > (v1, v2)} expands to:</p>
     * <pre>{@code (a > v1) OR (a = v1 AND b > v2)}</pre>
     *
     * <p>The provided values are processed in a deterministic order. The {@code parameterFunction} is called for each
     * value in the order they appear in the generated SQL, which may differ from the input order when values are
     * repeated (e.g., in lexicographic expansion). This same method must be used for both compilation and binding to
     * ensure the parameter order is consistent.</p>
     *
     * @param operator the comparison operator to apply.
     * @param values the multi-row values. Each map represents a single row of column-name-to-value mappings.
     * @param parameterFunction the function responsible for binding the parameters to the SQL template and returning
     *                          the string representation of each parameter, either a '?' placeholder or a literal value.
     * @return the SQL fragment representing the multi-column expression.
     * @throws SqlTemplateException if the operator is not supported for multi-column expressions.
     * @since 1.9
     */
    default String multiColumnExpression(@Nonnull Operator operator,
                                         @Nonnull List<SequencedMap<String, Object>> values,
                                         @Nonnull Function<Object, String> parameterFunction)
            throws SqlTemplateException {
        if (operator == EQUALS || operator == Operator.IN) {
            return multiValueIn(values, parameterFunction);
        }
        if (operator == Operator.NOT_EQUALS || operator == Operator.NOT_IN) {
            // NOT has higher precedence than AND in SQL, so we always need parentheses to ensure NOT
            // applies to the entire expression: NOT (a = ? AND b = ?).
            return "NOT (%s)".formatted(multiValueIn(values, parameterFunction));
        }
        if (operator == IS_NULL) {
            if (values.size() != 1) {
                throw new SqlTemplateException("IS_NULL operator requires exactly one value set.");
            }
            return values.getFirst().keySet().stream()
                    .map(IS_NULL::format)
                    .collect(joining(" AND "));
        }
        if (operator == IS_NOT_NULL) {
            if (values.size() != 1) {
                throw new SqlTemplateException("IS_NOT_NULL operator requires exactly one value set.");
            }
            return values.getFirst().keySet().stream()
                    .map(IS_NOT_NULL::format)
                    .collect(joining(" AND "));
        }
        // Lexicographic comparison operators.
        if (operator == GREATER_THAN || operator == GREATER_THAN_OR_EQUAL
                || operator == LESS_THAN || operator == LESS_THAN_OR_EQUAL) {
            if (values.size() != 1) {
                throw new SqlTemplateException("Comparison operator requires exactly one value set for multi-column expression.");
            }
            return lexicographicComparison(operator, values.getFirst(), parameterFunction);
        }
        if (operator == BETWEEN) {
            if (values.size() != 2) {
                throw new SqlTemplateException("BETWEEN operator requires exactly two value sets for multi-column expression.");
            }
            String lower = lexicographicComparison(GREATER_THAN_OR_EQUAL, values.getFirst(), parameterFunction);
            String upper = lexicographicComparison(LESS_THAN_OR_EQUAL, values.getLast(), parameterFunction);
            return "(%s AND %s)".formatted(lower, upper);
        }
        throw new SqlTemplateException("Operator %s is not supported for multi-column expressions.".formatted(operator));
    }

    /**
     * Builds a lexicographic comparison for multiple columns.
     *
     * <p>For columns {@code (a, b, c)} with values {@code (v1, v2, v3)} and operator {@code >}, this produces:</p>
     * <pre>{@code (a > v1) OR (a = v1 AND b > v2) OR (a = v1 AND b = v2 AND c > v3)}</pre>
     *
     * <p>For {@code >=}, the last column uses {@code >=} while all others use {@code >}:</p>
     * <pre>{@code (a > v1) OR (a = v1 AND b > v2) OR (a = v1 AND b = v2 AND c >= v3)}</pre>
     *
     * @param operator the comparison operator ({@code >}, {@code >=}, {@code <}, or {@code <=}).
     * @param valueMap the column-name-to-value mappings in column order.
     * @param parameterFunction the function for binding parameters.
     * @return the lexicographic comparison SQL fragment.
     */
    private static String lexicographicComparison(@Nonnull Operator operator,
                                                  @Nonnull SequencedMap<String, Object> valueMap,
                                                  @Nonnull Function<Object, String> parameterFunction) {
        // Determine the strict and non-strict variants of the operator.
        Operator strictOperator;
        boolean includeEqual;
        if (operator == GREATER_THAN) {
            strictOperator = GREATER_THAN;
            includeEqual = false;
        } else if (operator == GREATER_THAN_OR_EQUAL) {
            strictOperator = GREATER_THAN;
            includeEqual = true;
        } else if (operator == LESS_THAN) {
            strictOperator = LESS_THAN;
            includeEqual = false;
        } else {
            // LESS_THAN_OR_EQUAL
            strictOperator = LESS_THAN;
            includeEqual = true;
        }
        List<Map.Entry<String, Object>> entries = new ArrayList<>(valueMap.entrySet());
        int columnCount = entries.size();
        List<String> disjuncts = new ArrayList<>();
        for (int i = 0; i < columnCount; i++) {
            List<String> conjuncts = new ArrayList<>();
            // Add equality conditions for all preceding columns.
            for (int j = 0; j < i; j++) {
                var entry = entries.get(j);
                conjuncts.add(EQUALS.format(entry.getKey(), parameterFunction.apply(entry.getValue())));
            }
            // Add the comparison condition for the current column.
            var entry = entries.get(i);
            boolean isLastColumn = (i == columnCount - 1);
            Operator columnOperator = (isLastColumn && includeEqual) ? operator : strictOperator;
            conjuncts.add(columnOperator.format(entry.getKey(), parameterFunction.apply(entry.getValue())));
            disjuncts.add(conjuncts.size() == 1 ? conjuncts.getFirst() : "(%s)".formatted(String.join(" AND ", conjuncts)));
        }
        return disjuncts.size() == 1 ? disjuncts.getFirst() : "(%s)".formatted(String.join(" OR ", disjuncts));
    }

    /**
     * Returns {@code true} if the limit should be applied after the SELECT clause, {@code false} to apply the limit at
     * the end of the query.
     *
     * @return {@code true} if the limit should be applied after the SELECT clause, {@code false} to apply the limit at
     * the end of the query.
     * @since 1.2
     */
    boolean applyLimitAfterSelect();

    /**
     * Returns a string template for the given limit.
     *
     * @param limit the maximum number of records to return.
     * @return a string template for the given limit.
     * @since 1.2
     */
    String limit(int limit);

    /**
     * Returns a string template for the given offset.
     *
     * @param offset the offset.
     * @return a string template for the given offset.
     * @since 1.2
     */
    String offset(int offset);

    /**
     * Returns a string template for the given limit and offset.
     *
     * @param offset the offset.
     * @param limit the maximum number of records to return.
     * @return a string template for the given limit and offset.
     * @since 1.2
     */
    String limit(int offset, int limit);

    /**
     * Returns {@code true} if the lock hint should be applied after the FROM clause, {@code false} to apply the lock
     * hint at the end of the query.
     *
     * @return {@code true} if the lock hint should be applied after the FROM clause, {@code false} to apply the lock
     * hint at the end of the query.
     * @since 1.2
     */
    boolean applyLockHintAfterFrom();

    /**
     * Returns the lock hint for a shared reading lock.
     *
     * @return the lock hint for a shared reading lock.
     * @since 1.2
     */
    String forShareLockHint();

    /**
     * Returns the lock hint for a write lock.
     *
     * @return the lock hint for a write lock.
     * @since 1.2
     */
    String forUpdateLockHint();

    /**
     * Sets a UUID parameter on the given prepared statement.
     *
     * <p>The default implementation sets the UUID as a string, which is compatible with databases that store UUIDs as
     * character types. Dialects that support native UUID types should override this method to use
     * {@link PreparedStatement#setObject(int, Object)}.</p>
     *
     * @param preparedStatement the prepared statement.
     * @param index the parameter index.
     * @param uuid the UUID value.
     * @throws SQLException if a database access error occurs.
     * @since 1.9
     */
    default void setParameter(@Nonnull PreparedStatement preparedStatement, int index,
                              @Nonnull UUID uuid) throws SQLException {
        preparedStatement.setString(index, uuid.toString());
    }

    /**
     * Sets a {@link Timestamp} parameter on the given prepared statement.
     *
     * <p>The default implementation uses {@link PreparedStatement#setTimestamp(int, Timestamp, Calendar)}.
     * Dialects where the JDBC driver does not store timestamps in a text-comparable format should override this
     * method to bind the timestamp in a format that ensures round-trip consistency for optimistic lock
     * comparisons.</p>
     *
     * @param preparedStatement the prepared statement.
     * @param index the parameter index.
     * @param timestamp the timestamp value.
     * @param calendar the calendar to use for timezone conversion.
     * @throws SQLException if a database access error occurs.
     * @since 1.11
     */
    default void setParameter(@Nonnull PreparedStatement preparedStatement, int index,
                              @Nonnull Timestamp timestamp, @Nonnull Calendar calendar) throws SQLException {
        preparedStatement.setTimestamp(index, timestamp, calendar);
    }

    /**
     * Returns the SQL statement for getting the next value of the given sequence.
     *
     * @param sequenceName the name of the sequence.
     * @return the SQL statement for getting the next value of the given sequence.
     * @since 1.6
     */
    String sequenceNextVal(String sequenceName);

    /**
     * Strategy for discovering sequences in the database schema.
     *
     * @since 1.9
     */
    enum SequenceDiscoveryStrategy {
        /** Use {@code INFORMATION_SCHEMA.SEQUENCES}. */
        INFORMATION_SCHEMA,
        /** Use {@code ALL_SEQUENCES} dictionary view. */
        ALL_SEQUENCES,
        /** Sequences are not discoverable; skip sequence validation. */
        NONE
    }

    /**
     * Strategy for discovering primary keys, unique keys, and foreign keys in the database schema.
     *
     * <p>The default {@link #INFORMATION_SCHEMA} strategy executes a small number of bulk SQL queries against
     * standard {@code INFORMATION_SCHEMA} views, regardless of the number of tables in the schema. This is
     * significantly faster than the per-table {@link #JDBC_METADATA} fallback when the database is accessed over
     * a high-latency connection.</p>
     *
     * @since 1.9
     */
    enum ConstraintDiscoveryStrategy {
        /**
         * Per-table JDBC {@link java.sql.DatabaseMetaData} calls. Safe, portable fallback that works with any
         * JDBC driver but issues three metadata queries per table in the schema.
         */
        JDBC_METADATA,
        /**
         * Bulk queries using standard {@code INFORMATION_SCHEMA} views ({@code TABLE_CONSTRAINTS},
         * {@code KEY_COLUMN_USAGE}, {@code REFERENTIAL_CONSTRAINTS}). Requires the driver to expose
         * {@code POSITION_IN_UNIQUE_CONSTRAINT} in {@code KEY_COLUMN_USAGE} for foreign key discovery.
         */
        INFORMATION_SCHEMA,
        /**
         * Bulk queries using {@code INFORMATION_SCHEMA} views with {@code REFERENCED_TABLE_NAME} and
         * {@code REFERENCED_COLUMN_NAME} columns in {@code KEY_COLUMN_USAGE} for foreign key discovery.
         */
        INFORMATION_SCHEMA_REFERENCING,
        /**
         * Bulk queries using {@code ALL_CONSTRAINTS} and {@code ALL_CONS_COLUMNS} dictionary views.
         */
        ALL_CONSTRAINTS
    }

    /**
     * Returns the strategy for discovering sequences in the database schema.
     *
     * <p>The default strategy queries {@code INFORMATION_SCHEMA.SEQUENCES}. Database dialects that use a different
     * mechanism or do not support sequences at all should override this method.</p>
     *
     * @return the sequence discovery strategy.
     * @since 1.9
     */
    default SequenceDiscoveryStrategy sequenceDiscoveryStrategy() {
        return SequenceDiscoveryStrategy.INFORMATION_SCHEMA;
    }

    /**
     * Returns the strategy for discovering primary keys, unique keys, and foreign keys in the database schema.
     *
     * <p>The default strategy uses bulk {@code INFORMATION_SCHEMA} queries, which reduces the number of database
     * round-trips from three per table to a fixed number of queries regardless of table count. Dialects that do not
     * support the required {@code INFORMATION_SCHEMA} views should override this method to return an appropriate
     * alternative strategy.</p>
     *
     * @return the constraint discovery strategy.
     * @since 1.9
     */
    default ConstraintDiscoveryStrategy constraintDiscoveryStrategy() {
        return ConstraintDiscoveryStrategy.INFORMATION_SCHEMA;
    }

    /**
     * Returns whether the database uses JDBC catalogs in place of schemas.
     *
     * <p>Some databases do not support JDBC schemas. Instead, the database name is exposed as
     * the JDBC catalog. When this method returns {@code true}, schema validation will pass the entity's schema value as
     * the JDBC catalog parameter instead of the schema pattern.</p>
     *
     * @return {@code true} if the database uses catalogs as schemas, {@code false} otherwise.
     * @since 1.9
     */
    default boolean useCatalogAsSchema() {
        return false;
    }

    /**
     * Returns the default JDBC fetch size hint for queries.
     *
     * <p>The returned value is passed to {@link PreparedStatement#setFetchSize(int)} to control how many rows the
     * JDBC driver fetches from the database at a time. A value of {@code 0} (the default) leaves the fetch size
     * unset, deferring to driver defaults.</p>
     *
     * @return the default fetch size.
     * @since 1.10
     */
    default int defaultFetchSize() {
        return 0;
    }

    /**
     * Returns whether the default fetch size should only be applied for streaming result consumption
     * (i.e., {@code getResultStream()} and flow-based access), not for eager methods like
     * {@code getResultList()} or {@code getSingleResult()}.
     *
     * @return {@code true} if fetch size should only apply to streaming access.
     * @since 1.10
     */
    default boolean streamOnlyFetchSize() {
        return false;
    }

    /**
     * Returns whether streaming result sets require an active transaction for the fetch size hint to take effect.
     *
     * <p>Some JDBC drivers silently ignore {@link PreparedStatement#setFetchSize(int)} when the connection is in
     * auto-commit mode. When this method returns {@code true} and a streaming method ({@code getResultStream()}) is
     * called without an active transaction, a warning is logged to alert the caller that the entire result set will
     * be buffered in memory instead of being fetched in batches.</p>
     *
     * @return {@code true} if an active transaction is required for cursor-based streaming.
     * @since 1.10
     */
    default boolean streamingRequiresTransaction() {
        return false;
    }

    /**
     * Returns the SQL expression for the current timestamp.
     *
     * <p>The default implementation returns {@code CURRENT_TIMESTAMP}, which is the ANSI SQL standard. Dialects where
     * the JDBC driver's timestamp format does not match the database's {@code CURRENT_TIMESTAMP} output should
     * override this method to return an expression that produces a format compatible with the JDBC driver's parameter
     * binding.</p>
     *
     * @return the SQL expression for the current timestamp.
     * @since 1.11
     */
    default String currentTimestamp() {
        return "CURRENT_TIMESTAMP";
    }

}
