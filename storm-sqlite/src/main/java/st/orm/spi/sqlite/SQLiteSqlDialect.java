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

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Pattern;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;

public class SQLiteSqlDialect extends DefaultSqlDialect {

    public SQLiteSqlDialect() {
    }

    public SQLiteSqlDialect(StormConfig config) {
        super(config);
    }

    /**
     * Returns the name of the SQL dialect.
     *
     * @return the name of the SQL dialect.
     * @since 1.11
     */
    @Override
    public String name() {
        return "SQLite";
    }

    private static final Pattern SQLITE_IDENTIFIER = Pattern.compile("^[A-Za-z_][A-Za-z0-9_]*$");

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.11
     */
    @Override
    public Pattern getValidIdentifierPattern() {
        return SQLITE_IDENTIFIER;
    }

    /**
     * The keywords SQLite documents. SQLite accepts many of them as an unquoted name, as a fallback its documentation
     * warns a later release may withdraw, and quoting a name leaves its meaning unchanged in SQLite.
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "ABORT", "ACTION", "ADD", "AFTER", "ALL", "ALTER", "ALWAYS", "ANALYZE", "AND", "AS", "ASC", "ATTACH",
            "AUTOINCREMENT", "BEFORE", "BEGIN", "BETWEEN", "BY", "CASCADE", "CASE", "CAST", "CHECK", "COLLATE",
            "COLUMN", "COMMIT", "CONFLICT", "CONSTRAINT", "CREATE", "CROSS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "DATABASE", "DEFAULT", "DEFERRABLE", "DEFERRED", "DELETE", "DESC", "DETACH",
            "DISTINCT", "DO", "DROP", "EACH", "ELSE", "END", "ESCAPE", "EXCEPT", "EXCLUDE", "EXCLUSIVE", "EXISTS",
            "EXPLAIN", "FAIL", "FILTER", "FIRST", "FOLLOWING", "FOR", "FOREIGN", "FROM", "FULL", "GENERATED", "GLOB",
            "GROUP", "GROUPS", "HAVING", "IF", "IGNORE", "IMMEDIATE", "IN", "INDEX", "INDEXED", "INITIALLY", "INNER",
            "INSERT", "INSTEAD", "INTERSECT", "INTO", "IS", "ISNULL", "JOIN", "KEY", "LAST", "LEFT", "LIKE", "LIMIT",
            "MATCH", "MATERIALIZED", "NATURAL", "NO", "NOT", "NOTHING", "NOTNULL", "NULL", "NULLS", "OF", "OFFSET",
            "ON", "OR", "ORDER", "OTHERS", "OUTER", "OVER", "PARTITION", "PLAN", "PRAGMA", "PRECEDING", "PRIMARY",
            "QUERY", "RAISE", "RANGE", "RECURSIVE", "REFERENCES", "REGEXP", "REINDEX", "RELEASE", "RENAME", "REPLACE",
            "RESTRICT", "RETURNING", "RIGHT", "ROLLBACK", "ROW", "ROWS", "SAVEPOINT", "SELECT", "SET", "TABLE", "TEMP",
            "TEMPORARY", "THEN", "TIES", "TO", "TRANSACTION", "TRIGGER", "UNBOUNDED", "UNION", "UNIQUE", "UPDATE",
            "USING", "VACUUM", "VALUES", "VIEW", "VIRTUAL", "WHEN", "WHERE", "WINDOW", "WITH", "WITHOUT"
    );

    /**
     * Indicates whether the given name is a keyword in this SQL dialect.
     *
     * @param name the name to check.
     * @return {@code true} if the name is a keyword, {@code false} otherwise.
     * @since 1.11
     */
    @Override
    public boolean isKeyword(String name) {
        return RESERVED_WORDS.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Escapes the given database identifier using double quotes (ANSI SQL standard).
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(String name) {
        return "\"%s\"".formatted(name.replace("\"", "\"\""));
    }

    /**
     * Regex for single-quoted string literals, handling doubled single quotes as escapes.
     */
    private static final Pattern QUOTE_LITERAL_PATTERN = Pattern.compile(
            "'(?:''|[^'])*'"
    );

    /**
     * Returns the pattern for string literals.
     *
     * @return the pattern for string literals.
     */
    @Override
    public Pattern getQuoteLiteralPattern() {
        return QUOTE_LITERAL_PATTERN;
    }

    /**
     * Returns a SQLite limit clause.
     *
     * @param limit the maximum number of records to return.
     * @return the limit clause.
     */
    @Override
    public String limit(int limit) {
        return "LIMIT %d".formatted(limit);
    }

    /**
     * Returns a SQLite offset clause.
     *
     * <p>SQLite requires a LIMIT clause before OFFSET. A large limit value is used to return all rows.</p>
     *
     * @param offset the offset.
     * @return the offset clause.
     */
    @Override
    public String offset(int offset) {
        return "LIMIT -1 OFFSET %d".formatted(offset);
    }

    /**
     * Returns a SQLite limit clause with offset.
     *
     * @param offset the offset.
     * @param limit the maximum number of records to return.
     * @return the limit clause with offset.
     */
    @Override
    public String limit(int offset, int limit) {
        return "LIMIT %d OFFSET %d".formatted(limit, offset);
    }

    /**
     * Returns the lock hint for a shared reading lock.
     *
     * <p>SQLite uses file-level locking and does not support row-level lock hints. An empty string is returned.</p>
     *
     * @return an empty string.
     */
    @Override
    public String forShareLockHint() {
        return "";
    }

    /**
     * Returns the lock hint for a write lock.
     *
     * <p>SQLite uses file-level locking and does not support row-level lock hints. An empty string is returned.</p>
     *
     * @return an empty string.
     */
    @Override
    public String forUpdateLockHint() {
        return "";
    }

    /**
     * Returns the strategy for discovering sequences in the database schema.
     *
     * <p>SQLite does not support sequences. Auto-incrementing columns use {@code AUTOINCREMENT}.</p>
     *
     * @return {@link SequenceDiscoveryStrategy#NONE}.
     * @since 1.11
     */
    @Override
    public SequenceDiscoveryStrategy sequenceDiscoveryStrategy() {
        return SequenceDiscoveryStrategy.NONE;
    }

    /**
     * Returns the strategy for discovering constraints in the database schema.
     *
     * <p>SQLite does not support {@code INFORMATION_SCHEMA}. Constraints are discovered using
     * JDBC metadata queries.</p>
     *
     * @return {@link ConstraintDiscoveryStrategy#JDBC_METADATA}.
     * @since 1.11
     */
    @Override
    public ConstraintDiscoveryStrategy constraintDiscoveryStrategy() {
        return ConstraintDiscoveryStrategy.JDBC_METADATA;
    }

    /**
     * Returns the SQL expression for the current timestamp.
     *
     * <p>SQLite stores timestamps as text. The standard {@code CURRENT_TIMESTAMP} produces
     * {@code YYYY-MM-DD HH:MM:SS} (no fractional seconds), but the SQLite JDBC driver binds
     * {@code Timestamp} parameters with fractional seconds. This mismatch causes optimistic lock
     * comparisons to fail. Using {@code strftime} with {@code %f} includes fractional seconds,
     * ensuring round-trip consistency between reads and writes.</p>
     *
     * @return the SQL expression for the current timestamp.
     * @since 1.11
     */
    @Override
    public String currentTimestamp() {
        return "strftime('%Y-%m-%d %H:%M:%f', 'now')";
    }

    private static final String SQLITE_TIMESTAMP_FORMAT = "yyyy-MM-dd HH:mm:ss.SSS";

    /**
     * Binds a {@link Timestamp} parameter as a formatted text string.
     *
     * <p>The default SQLite JDBC driver stores timestamps as epoch milliseconds when using
     * {@link PreparedStatement#setTimestamp}, which makes text-based comparisons with
     * {@code strftime} or {@code CURRENT_TIMESTAMP} fail. This override formats the timestamp
     * as a text string matching the output of {@link #currentTimestamp()}, ensuring that
     * optimistic lock comparisons ({@code WHERE version = ?}) work correctly.</p>
     *
     * @since 1.11
     */
    @Override
    public void setParameter(PreparedStatement preparedStatement, int index,
                             Timestamp timestamp, Calendar calendar) throws SQLException {
        SimpleDateFormat format = new SimpleDateFormat(SQLITE_TIMESTAMP_FORMAT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        preparedStatement.setString(index, format.format(timestamp));
    }

    /**
     * Returns the SQL statement for getting the next value of the given sequence.
     *
     * <p>SQLite does not support sequences.</p>
     *
     * @param sequenceName the name of the sequence.
     * @return never returns normally.
     * @throws PersistenceException always, as SQLite does not support sequences.
     * @since 1.11
     */
    @Override
    public String sequenceNextVal(String sequenceName) {
        throw new PersistenceException("SQLite does not support sequences.");
    }

    /**
     * SQLite supports {@code INSERT ... RETURNING} (since 3.35), so batch {@code insertAndFetchIds} reads the keys
     * from a multi-row {@code VALUES ... RETURNING} result set.
     *
     * @return {@code true}.
     * @since 1.13
     */
    @Override
    public boolean supportsInsertReturning() {
        return true;
    }

    /**
     * SQLite historically caps a statement at {@code SQLITE_MAX_VARIABLE_NUMBER} bind parameters, which defaults to
     * {@code 999} on builds older than 3.32. Multi-row inserts are chunked to stay within that conservative limit.
     *
     * @return {@code 999}.
     * @since 1.13
     */
    @Override
    public int maxBindParameters() {
        return 999;
    }
}
