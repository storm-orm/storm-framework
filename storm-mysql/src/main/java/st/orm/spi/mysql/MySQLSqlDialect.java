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

import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import st.orm.Operator;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;

public class MySQLSqlDialect extends DefaultSqlDialect {

    public MySQLSqlDialect() {
    }

    public MySQLSqlDialect(StormConfig config) {
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
    public boolean isKeyword(String name) {
        return MYSQL_KEYWORDS.contains(name.toUpperCase());
    }

    /**
     * Escapes the given database identifier (e.g., table or column name) according to this SQL dialect.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(String name) {
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
     * Returns whether a multi-column comparison renders as a row value tuple, which for MySQL is the case for a
     * multi-row list only.
     *
     * <p>MySQL accepts a row value comparison for every operator, but accepting one and planning it well are
     * different things. Measured on MySQL 8.4 against a 100k-row table keyed on {@code (a, b)}:</p>
     *
     * <table border="1">
     *   <caption>MySQL 8.4 plan for each comparison shape</caption>
     *   <tr><th>Shape</th><th>As a tuple</th><th>As the expansion</th><th>Rendered as</th></tr>
     *   <tr><td>Single row of equality, SELECT</td><td>{@code const}, primary key</td>
     *       <td>{@code const}, primary key</td><td>Expansion</td></tr>
     *   <tr><td>Single row of equality, UPDATE / DELETE</td><td>{@code range}, primary key</td>
     *       <td>{@code range}, primary key</td><td>Expansion</td></tr>
     *   <tr><td>Multi-row list (500 keys)</td><td>{@code range}, primary key — <strong>~4x faster</strong></td>
     *       <td>{@code range}, primary key</td><td><strong>Tuple</strong></td></tr>
     *   <tr><td>Ordering comparison</td><td>{@code ALL}, all rows</td>
     *       <td>{@code range}, primary key</td><td>Expansion</td></tr>
     * </table>
     *
     * <p>Equality plans identically either way, so it takes the expansion, which is the form that is safe on every
     * dialect. The list is the one shape the tuple wins: same plan, but the optimizer is spared a 500-branch OR
     * tree. The ordering comparison is the shape it loses outright, so that renders lexicographically.</p>
     *
     * <p>{@link st.orm.spi.mariadb.MariaDBSqlDialect} inherits this and measures the same way, more sharply.</p>
     *
     * @param operator the comparison operator to apply.
     * @param rowCount the number of value rows in the comparison.
     * @return {@code true} to render a row value tuple, {@code false} to render the {@code AND} expansion.
     * @since 1.13
     */
    @Override
    protected boolean rendersTupleComparison(Operator operator, int rowCount) {
        return isMultiRowEquality(operator, rowCount);
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
     * same connection, and the driver rejects a statement issued in between. Storm refuses such a statement
     * itself, on every dialect, so the constraint is the same everywhere. Applying this mode to eager methods
     * (such as {@code getResultList()} or {@code getSingleResult()}) would add unnecessary per-row latency
     * without memory benefits, since those methods collect all results immediately.</p>
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

    /**
     * MySQL's driver returns every generated key for a single multi-row {@code INSERT}: it reports the first
     * auto-increment value and, because a bounded multi-row {@code VALUES} statement reserves a contiguous block of
     * auto-increment values, derives the remaining keys from it. Batch {@code insertAndFetchIds} can therefore emit one
     * multi-row {@code VALUES} statement rather than a JDBC {@code executeBatch}.
     *
     * @return {@code true}.
     * @since 1.13
     */
    @Override
    public boolean supportsMultiRowGeneratedKeys() {
        return true;
    }

    /**
     * The MySQL client/server protocol encodes the placeholder count in a 16-bit field, capping a single statement at
     * {@code 65535} bind parameters.
     *
     * @return {@code 65535}.
     * @since 1.13
     */
    @Override
    public int maxBindParameters() {
        return 65_535;
    }
}
