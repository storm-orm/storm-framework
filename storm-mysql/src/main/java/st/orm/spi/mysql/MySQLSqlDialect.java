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

import static java.util.stream.Collectors.toSet;
import static st.orm.Operator.BETWEEN;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.IN;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LESS_THAN_OR_EQUAL;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.Operator.NOT_IN;

import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import st.orm.Operator;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplateException;

public class MySQLSqlDialect extends DefaultSqlDialect implements SqlDialect {

    public MySQLSqlDialect() {
    }

    public MySQLSqlDialect(@Nonnull StormConfig config) {
        super(config);
    }

    /**
     * Returns the name of the SQL dialect.
     *
     * @return the name of the SQL dialect.
     * @since 1.2
     */
    @Override
    public String name() {
        return "MySQL";
    }

    /**
     * Indicates whether the SQL dialect supports delete aliases.
     *
     * <p>Delete aliases allow delete statements to use table aliases in joins,  making it easier to filter rows based
     * on related data.</p>
     *
     * @return {@code true} if delete aliases are supported, {@code false} otherwise.
     */
    @Override
    public boolean supportsDeleteAlias() {
        return true;
    }

    /**
     * Indicates whether the SQL dialect supports multi-value tuples in the IN clause.
     *
     * @return {@code true} if multi-value tuples are supported, {@code false} otherwise.
     * @since 1.2
     */
    @Override
    public boolean supportsMultiValueTuples() {
        // Note that tuple IN is only supported as of MySQL 8.0.19. We will account for this in the future.
        return true;
    }

    private static final Pattern MYSQL_IDENTIFIER = Pattern.compile("^[_A-Za-z][_A-Za-z0-9]*$");

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.2
     */
    @Override
    public Pattern getValidIdentifierPattern() {
        return MYSQL_IDENTIFIER;
    }

    private static final Set<String> MYSQL_KEYWORDS = Stream.concat(ANSI_KEYWORDS.stream(), Stream.of(
            "ACCESSIBLE", "ANALYZE", "CHANGE", "CHECKSUM", "DATABASE", "DAY_HOUR", "DAY_MINUTE", "DAY_SECOND",
            "DELAYED", "DESCRIBE", "DISTINCTROW", "DIV", "DO", "ENCLOSED", "ESCAPED", "EXPLAIN", "FORCE",
            "FULLTEXT", "GENERATED", "HIGH_PRIORITY", "HOUR_MICROSECOND", "HOUR_SECOND", "IGNORE", "INDEX",
            "INFILE", "INT1", "INT2", "INT3", "INT4", "INT8", "KEY", "KEYS", "LINES", "LOAD", "LOW_PRIORITY",
            "MEDIUMINT", "MIDDLEINT", "MODIFIES", "OPTIMIZE", "OPTION", "OPTIONALLY", "OUTFILE", "PRIVILEGES",
            "PURGE", "REQUIRE", "RESIGNAL", "SCHEMAS", "SHOW", "SQL_BIG_RESULT", "SQL_CALC_FOUND_ROWS",
            "SQL_SMALL_RESULT", "STRAIGHT_JOIN", "TERMINATED", "TINYINT", "UNSIGNED", "UTC_DATE", "UTC_TIME",
            "UTC_TIMESTAMP", "VIRTUAL", "VISIBLE", "INVISIBLE", "XOR", "ZEROFILL"
    )).collect(toSet());

    /**
     * Indicates whether the given name is a keyword in this SQL dialect.
     *
     * @param name the name to check.
     * @return {@code true} if the name is a keyword, {@code false} otherwise.
     * @since 1.2
     */
    @Override
    public boolean isKeyword(@Nonnull String name) {
        return MYSQL_KEYWORDS.contains(name.toUpperCase());
    }

    /**
     * Escapes the given database identifier (e.g., table or column name) according to this SQL dialect.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(@Nonnull String name) {
        return "`%s`".formatted(name.replace("`", "``"));
    }

    /**
     * Regex for double-quoted identifiers (including escaped quotes inside). In ANSI SQL, an embedded double quote is
     * escaped by doubling it (""). Look for sequences of "" or any non-quote character, all enclosed between double
     * quotes.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\"(?:\"\"|[^\"])*\""   // Either: double‐quoted identifier (with "" as escape)
            + "|" +
            "`(?:``|[^`])*`"    // Or: backtick‐quoted identifier (with `` as escape)
    );

    /**
     * Returns the pattern for identifiers.
     *
     * @return the pattern for identifiers.
     * @since 1.2
     */
    @Override
    public Pattern getIdentifierPattern() {
        return IDENTIFIER_PATTERN;
    }

    /**
     * Regex for single-quoted string literals, handling both double single quotes and backslash escapes.
     */
    private static final Pattern QUOTE_LITERAL_PATTERN = Pattern.compile("'(?:''|\\\\.|[^'\\\\])*'");

    /**
     * Returns the pattern for string literals.
     *
     * @return the pattern for string literals.
     * @since 1.2
     */
    @Override
    public Pattern getQuoteLiteralPattern() {
        return QUOTE_LITERAL_PATTERN;
    }

    /**
     * Builds a multi-column expression using row value constructor syntax.
     *
     * <p>MySQL (8.0.19+) supports tuple comparison syntax for all comparison operators, producing compact SQL like
     * {@code (a, b) > (?, ?)} instead of the lexicographic expansion used by the default implementation.</p>
     *
     * <p>Operators without clear tuple semantics ({@code IS_NULL}, {@code IS_NOT_NULL}, {@code LIKE}, etc.) fall back
     * to the {@link SqlDialect} default implementation.</p>
     *
     * @param operator the comparison operator to apply.
     * @param values the multi-row values. Each map represents a single row of column-name-to-value mappings.
     * @param parameterFunction the function responsible for binding the parameters.
     * @return the SQL fragment representing the multi-column expression.
     * @throws SqlTemplateException if the operator is not supported for multi-column expressions.
     * @since 1.9
     */
    @Override
    public String multiColumnExpression(@Nonnull Operator operator,
                                         @Nonnull List<SequencedMap<String, Object>> values,
                                         @Nonnull Function<Object, String> parameterFunction)
            throws SqlTemplateException {
        if (operator == EQUALS || operator == NOT_EQUALS
                || operator == IN || operator == NOT_IN
                || operator == GREATER_THAN || operator == GREATER_THAN_OR_EQUAL
                || operator == LESS_THAN || operator == LESS_THAN_OR_EQUAL
                || operator == BETWEEN) {
            return tupleExpression(operator, values, parameterFunction);
        }
        return super.multiColumnExpression(operator, values, parameterFunction);
    }

    /**
     * Returns a string template for the given limit.
     *
     * @param limit the maximum number of records to return.
     * @return a string template for the given limit.
     * @since 1.2
     */
    @Override
    public String limit(int limit) {
        // Taking the most basic approach that is supported by most database in test (containers).
        // For production use, ensure the right dialect is used.
        return "LIMIT %d".formatted(limit);
    }

    /**
     * Returns a string template for the given offset.
     *
     * @param offset the offset.
     * @return a string template for the given offset.
     * @since 1.2
     */
    @Override
    public String offset(int offset) {
        return "LIMIT 18446744073709551615 OFFSET %d".formatted(offset);
    }

    /**
     * Returns a string template for the given limit and offset.
     *
     * @param limit the maximum number of records to return.
     * @param offset the offset.
     * @return a string template for the given limit and offset.
     * @since 1.2
     */
    @Override
    public String limit(int offset, int limit) {
        // Taking the most basic approach that is supported by most database in test (containers).
        // For production use, ensure the right dialect is used.
        return "LIMIT %d OFFSET %d".formatted(limit, offset);
    }

    /**
     * Returns the strategy for discovering sequences in the database schema.
     *
     * <p>MySQL does not support sequences.</p>
     *
     * @return {@link SequenceDiscoveryStrategy#NONE}.
     * @since 1.9
     */
    @Override
    public SequenceDiscoveryStrategy sequenceDiscoveryStrategy() {
        return SequenceDiscoveryStrategy.NONE;
    }

    /**
     * Returns whether the database uses JDBC catalogs in place of schemas.
     *
     * <p>MySQL does not support JDBC schemas. The database name is exposed as the JDBC catalog.</p>
     *
     * @return {@code true}.
     * @since 1.9
     */
    @Override
    public boolean useCatalogAsSchema() {
        return true;
    }

    /**
     * Returns the strategy for discovering constraints in the database schema.
     *
     * <p>MySQL exposes {@code REFERENCED_TABLE_NAME} and {@code REFERENCED_COLUMN_NAME} columns in
     * {@code INFORMATION_SCHEMA.KEY_COLUMN_USAGE}, which enables efficient bulk foreign key discovery.</p>
     *
     * @return {@link ConstraintDiscoveryStrategy#INFORMATION_SCHEMA_REFERENCING}.
     * @since 1.9
     */
    @Override
    public ConstraintDiscoveryStrategy constraintDiscoveryStrategy() {
        return ConstraintDiscoveryStrategy.INFORMATION_SCHEMA_REFERENCING;
    }

    /**
     * Returns {@code Integer.MIN_VALUE} to enable the MySQL Connector/J row-by-row streaming mode.
     *
     * <p>By default, the MySQL JDBC driver buffers the entire result set in memory during
     * {@code executeQuery()}. Setting the fetch size to {@code Integer.MIN_VALUE} switches the driver to
     * streaming mode, where rows are fetched one at a time from the server. This prevents
     * {@code OutOfMemoryError} for large result sets when consumed lazily via {@code getResultStream()}.</p>
     *
     * <p>Because row-by-row streaming incurs per-row network latency, this fetch size is only applied for
     * streaming access (see {@link #streamOnlyFetchSize()}).</p>
     *
     * @return {@code Integer.MIN_VALUE}.
     * @since 1.10
     */
    @Override
    public int defaultFetchSize() {
        return Integer.MIN_VALUE;
    }

    /**
     * Returns {@code true} to restrict the streaming fetch size to stream-based methods only.
     *
     * <p>The MySQL row-by-row streaming mode ({@code fetchSize = Integer.MIN_VALUE}) imposes constraints on
     * the connection: the result set must be fully consumed or closed before another query can execute on the
     * same connection. Applying this mode to eager methods (such as {@code getResultList()} or
     * {@code getSingleResult()}) would add unnecessary per-row latency without memory benefits, since those
     * methods collect all results immediately.</p>
     *
     * @return {@code true}.
     * @since 1.10
     */
    @Override
    public boolean streamOnlyFetchSize() {
        return true;
    }

    /**
     * Returns the SQL statement for getting the next value of the given sequence.
     *
     * @param sequenceName the name of the sequence.
     * @return the SQL statement for getting the next value of the given sequence.
     * @since 1.6
     */
    @Override
    public String sequenceNextVal(String sequenceName) {
        throw new PersistenceException("MySQL does not support sequence-based generation.");
    }
}
