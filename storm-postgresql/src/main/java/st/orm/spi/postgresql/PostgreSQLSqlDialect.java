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
package st.orm.spi.postgresql;

import static st.orm.Operator.BETWEEN;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LESS_THAN_OR_EQUAL;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import st.orm.Operator;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.spi.JsonString;

public class PostgreSQLSqlDialect extends DefaultSqlDialect {

    public PostgreSQLSqlDialect() {
    }

    public PostgreSQLSqlDialect(StormConfig config) {
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
        return "PostgreSQL";
    }

    /**
     * PostgreSQL supports multi-value tuples in the IN clause.
     */
    @Override
    public boolean supportsMultiValueTuples() {
        return true;
    }

    /**
     * The words PostgreSQL refuses as an unquoted table, column or alias name: those {@code pg_get_keywords()} lists
     * as reserved, including the ones it allows as a function or type name.
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "ALL", "ANALYSE", "ANALYZE", "AND", "ANY", "ARRAY", "AS", "ASC", "ASYMMETRIC", "AUTHORIZATION", "BINARY",
            "BOTH", "CASE", "CAST", "CHECK", "COLLATE", "COLLATION", "COLUMN", "CONCURRENTLY", "CONSTRAINT", "CREATE",
            "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME",
            "CURRENT_TIMESTAMP", "CURRENT_USER", "DEFAULT", "DEFERRABLE", "DESC", "DISTINCT", "DO", "ELSE", "END",
            "EXCEPT", "FALSE", "FETCH", "FOR", "FOREIGN", "FREEZE", "FROM", "FULL", "GRANT", "GROUP", "HAVING", "ILIKE",
            "IN", "INITIALLY", "INNER", "INTERSECT", "INTO", "IS", "ISNULL", "JOIN", "LATERAL", "LEADING", "LEFT",
            "LIKE", "LIMIT", "LOCALTIME", "LOCALTIMESTAMP", "NATURAL", "NOT", "NOTNULL", "NULL", "OFFSET", "ON", "ONLY",
            "OR", "ORDER", "OUTER", "OVERLAPS", "PLACING", "PRIMARY", "REFERENCES", "RETURNING", "RIGHT", "SELECT",
            "SESSION_USER", "SIMILAR", "SOME", "SYMMETRIC", "SYSTEM_USER", "TABLE", "TABLESAMPLE", "THEN", "TO",
            "TRAILING", "TRUE", "UNION", "UNIQUE", "USER", "USING", "VARIADIC", "VERBOSE", "WHEN", "WHERE", "WINDOW",
            "WITH"
    );

    /**
     * Indicates whether the given name is a keyword in this SQL dialect.
     *
     * @param name the name to check.
     * @return {@code true} if the name is a keyword, {@code false} otherwise.
     * @since 1.2
     */
    @Override
    public boolean isKeyword(String name) {
        return RESERVED_WORDS.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Escapes the given database identifier using double quotes.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(String name) {
        return "\"%s\"".formatted(name.replace("\"", "\"\""));
    }

    /**
     * Returns whether a multi-column comparison renders as a row value tuple, which for PostgreSQL is the case for
     * an ordering comparison and for a multi-row list.
     *
     * <p>PostgreSQL is the dialect that gets the most out of a row value comparison, and the only measured one
     * where the lexicographic expansion is the worse plan. Measured on 17.9 against a 100k-row table keyed on
     * {@code (a, b)}:</p>
     *
     * <table border="1">
     *   <caption>PostgreSQL 17 plan for each comparison shape</caption>
     *   <tr><th>Shape</th><th>As a tuple</th><th>As the expansion</th><th>Rendered as</th></tr>
     *   <tr><td>Single row of equality, UPDATE / DELETE</td>
     *       <td>Index scan, {@code Index Cond: ((a = ?) AND (b = ?))}</td>
     *       <td>Index scan, same condition</td><td>Expansion</td></tr>
     *   <tr><td>Multi-row list (500 keys)</td><td>Bitmap scan over a 500-way {@code BitmapOr}</td>
     *       <td>Identical plan</td><td><strong>Tuple</strong></td></tr>
     *   <tr><td>Ordering comparison (selective bound)</td>
     *       <td>Index scan, {@code Index Cond: (ROW(a, b) &gt; ROW(?, ?))}</td>
     *       <td>Bitmap heap scan — <strong>~40x the estimated cost</strong></td>
     *       <td><strong>Tuple</strong></td></tr>
     * </table>
     *
     * <p>Equality and the list are decided in the planner's favour either way: it rewrites a row value equality
     * into its conjunction and a row value list into the same {@code BitmapOr} the expansion produces, so the
     * plans are indistinguishable. Equality therefore takes the expansion, the form that is safe on every dialect,
     * and the list keeps the tuple for the compactness.</p>
     *
     * <p>The ordering comparison is the one shape where the choice changes the plan, and it favours the tuple
     * decisively: the row value comparison drives the index directly, where the expansion has to be reassembled
     * from a bitmap. This is the keyset pagination path, so it renders as a tuple.</p>
     *
     * @param operator the comparison operator to apply.
     * @param rowCount the number of value rows in the comparison.
     * @return {@code true} to render a row value tuple, {@code false} to render the {@code AND} expansion.
     * @since 1.13
     */
    @Override
    protected boolean rendersTupleComparison(Operator operator, int rowCount) {
        return isMultiRowEquality(operator, rowCount)
                || operator == GREATER_THAN || operator == GREATER_THAN_OR_EQUAL
                || operator == LESS_THAN || operator == LESS_THAN_OR_EQUAL
                || operator == BETWEEN;
    }

    /**
     * Returns a fetch size of 1000 to enable cursor-based result batching.
     *
     * <p>By default, the PostgreSQL JDBC driver fetches the entire result set into memory. When a non-zero
     * fetch size is set and the connection is not in auto-commit mode, the driver uses a database cursor to
     * fetch rows in batches of the specified size. A value of 1000 provides a good balance between reducing
     * memory consumption and minimizing round-trip overhead.</p>
     *
     * @return {@code 1000}.
     * @since 1.10
     */
    @Override
    public int defaultFetchSize() {
        return 1000;
    }

    /**
     * Returns {@code true} so the fetch size only applies to streaming result consumption.
     *
     * <p>Cursor-based fetching requires a transaction on PostgreSQL, so applying the fetch size to eagerly
     * consumed results would wrap every auto-commit query in a transaction, adding round trips without any
     * benefit: eager methods materialize the full result either way.</p>
     *
     * @return {@code true}.
     * @since 1.13
     */
    @Override
    public boolean streamOnlyFetchSize() {
        return true;
    }

    /**
     * Returns {@code true} because the PostgreSQL JDBC driver requires the connection to be in non-auto-commit
     * mode for the fetch size hint to activate cursor-based result batching. When auto-commit is enabled, the
     * driver silently ignores the fetch size and buffers the entire result set in memory.
     *
     * @return {@code true}.
     * @since 1.10
     */
    @Override
    public boolean streamingRequiresTransaction() {
        return true;
    }

    /**
     * Returns a PostgreSQL limit clause, with the value inlined.
     *
     * <p>A prepared {@code LIMIT ?} uses a value-aware custom plan for its first executions and then degrades to a
     * generic plan for an unknown row count, so the literal is what keeps the early-terminating plan pagination
     * wants.</p>
     *
     * @param limit the maximum number of records to return.
     * @return the limit clause.
     */
    @Override
    public String limit(int limit) {
        return "LIMIT %d".formatted(limit);
    }

    /**
     * Returns a PostgreSQL offset clause.
     *
     * @param offset the offset.
     * @return the offset clause.
     */
    @Override
    public String offset(int offset) {
        return "OFFSET %d".formatted(offset);
    }

    /**
     * Returns a PostgreSQL limit clause with offset.
     *
     * @param offset the offset.
     * @param limit the maximum number of records to return.
     * @return the limit clause with offset.
     */
    @Override
    public String limit(int offset, int limit) {
        return "OFFSET %d LIMIT %d".formatted(offset, limit);
    }

    /**
     * Returns the lock hint for a shared reading lock.
     *
     * @return the lock hint for a shared reading lock.
     */
    @Override
    public String forShareLockHint() {
        // We may add configuration flags to use the old PostgreSQL need FOR SHARE instead.
        return "FOR KEY SHARE";
    }

    /**
     * Sets a UUID parameter using {@link PreparedStatement#setObject(int, Object)}, which allows the PostgreSQL JDBC
     * driver to bind the value as a native UUID type.
     *
     * @param preparedStatement the prepared statement.
     * @param index the parameter index.
     * @param uuid the UUID value.
     * @throws SQLException if a database access error occurs.
     * @since 1.9
     */
    @Override
    public void setParameter(PreparedStatement preparedStatement, int index,
                             UUID uuid) throws SQLException {
        preparedStatement.setObject(index, uuid);
    }

    /**
     * Sets a serialized JSON parameter on the given prepared statement.
     *
     * <p>PostgreSQL rejects string-typed parameters for {@code json} and {@code jsonb} columns ("column is of
     * type jsonb but expression is of type character varying"). Binding the value as an untyped parameter lets
     * the server cast it to the column's JSON type.</p>
     *
     * @param preparedStatement the prepared statement.
     * @param index the parameter index.
     * @param json the serialized JSON value.
     * @throws SQLException if a database access error occurs.
     * @since 1.12
     */
    @Override
    public void setParameter(PreparedStatement preparedStatement, int index,
                             JsonString json) throws SQLException {
        preparedStatement.setObject(index, json.value(), Types.OTHER);
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
        return "nextval('" + getSafeIdentifier(sequenceName) + "')";
    }

    /**
     * PostgreSQL supports {@code INSERT ... RETURNING}, so batch {@code insertAndFetchIds} emits a single multi-row
     * {@code VALUES ... RETURNING} statement and reads the keys from the result set rather than issuing a JDBC
     * {@code executeBatch}.
     *
     * @return {@code true}.
     * @since 1.13
     */
    @Override
    public boolean supportsInsertReturning() {
        return true;
    }

    /**
     * The PostgreSQL wire protocol encodes bind parameters in a 16-bit field, capping a single statement at
     * {@code 65535} parameters.
     *
     * @return {@code 65535}.
     * @since 1.13
     */
    @Override
    public int maxBindParameters() {
        return 65_535;
    }
}
