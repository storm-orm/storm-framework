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
package st.orm.spi.h2;

import static st.orm.Operator.BETWEEN;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LESS_THAN_OR_EQUAL;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import st.orm.Operator;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;

public class H2SqlDialect extends DefaultSqlDialect {

    public H2SqlDialect() {
    }

    public H2SqlDialect(StormConfig config) {
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
        return "H2";
    }

    /**
     * H2 supports multi-value tuples in the IN clause.
     */
    @Override
    public boolean supportsMultiValueTuples() {
        return true;
    }

    /**
     * The words H2 refuses unquoted where Storm renders a name. H2 folds an unquoted name to upper case, so quoting
     * any other word would make the lower-case name Storm renders case-sensitive, and it would no longer match a
     * column created without quotes. Package-private for the conformance test, which checks that H2 refuses every
     * word on it.
     */
    static final Set<String> RESERVED_WORDS = Set.of(
            "ALL", "AND", "ANY", "ARRAY", "AS", "ASYMMETRIC", "AUTHORIZATION", "BETWEEN", "CASE", "CAST", "CHECK",
            "CONSTRAINT", "CROSS", "CURRENT_CATALOG", "CURRENT_DATE", "CURRENT_PATH", "CURRENT_ROLE", "CURRENT_SCHEMA",
            "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "DAY", "DEFAULT", "DISTINCT", "ELSE", "END", "EXCEPT",
            "EXISTS", "FALSE", "FETCH", "FOR", "FOREIGN", "FROM", "FULL", "GROUP", "HAVING", "HOUR", "IF", "IN",
            "INNER", "INTERSECT", "INTERVAL", "IS", "JOIN", "KEY", "LEFT", "LIKE", "LIMIT", "LOCALTIME",
            "LOCALTIMESTAMP", "MINUS", "MINUTE", "MONTH", "NATURAL", "NOT", "NULL", "OFFSET", "ON", "OR", "ORDER",
            "PRIMARY", "QUALIFY", "RIGHT", "ROW", "ROWNUM", "SECOND", "SELECT", "SESSION_USER", "SET", "SOME",
            "SYMMETRIC", "SYSTEM_USER", "TABLE", "TO", "TOP", "TRUE", "UESCAPE", "UNION", "UNIQUE", "UNKNOWN", "USER",
            "USING", "VALUE", "VALUES", "WHEN", "WHERE", "WINDOW", "WITH", "YEAR", "_ROWID_"
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
     * Returns whether a multi-column comparison renders as a row value tuple, which for H2 is the case for an
     * ordering comparison and for a multi-row list.
     *
     * <p>Measured on 2.2.220 against a 100k-row table keyed on {@code (a, b)}, reading the index H2 names in its
     * plan comment:</p>
     *
     * <table border="1">
     *   <caption>H2 2.2 plan for each comparison shape</caption>
     *   <tr><th>Shape</th><th>As a tuple</th><th>As the expansion</th><th>Rendered as</th></tr>
     *   <tr><td>Single row of equality, SELECT / UPDATE / DELETE</td>
     *       <td>Primary key, {@code A = ? AND B = ?}</td>
     *       <td>Primary key, same condition</td><td>Expansion</td></tr>
     *   <tr><td>Ordering comparison</td><td>Primary key range, {@code A &gt;= ?}</td>
     *       <td>Table scan</td><td><strong>Tuple</strong></td></tr>
     * </table>
     *
     * <p>H2 rewrites a row value equality into its conjunction and reaches the primary key either way, in reads
     * and writes alike, so equality takes the expansion: the form that is safe on every dialect, at no cost here.
     * The ordering comparison is the shape where the choice matters, and H2 only derives an index range from the
     * tuple; the lexicographic expansion of the same predicate falls through to a table scan.</p>
     *
     * <p>The multi-row list follows the ordering comparison in keeping the tuple, on the compactness argument
     * rather than a measured plan difference.</p>
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
     * Returns an H2 limit clause.
     *
     * @param limit the maximum number of records to return.
     * @return the limit clause.
     */
    @Override
    public String limit(int limit) {
        return "LIMIT %d".formatted(limit);
    }

    /**
     * Returns an H2 offset clause.
     *
     * @param offset the offset.
     * @return the offset clause.
     */
    @Override
    public String offset(int offset) {
        return "OFFSET %d".formatted(offset);
    }

    /**
     * Returns an H2 limit clause with offset.
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
     * <p>H2 does not support {@code FOR SHARE}. An empty string is returned.</p>
     *
     * @return an empty string.
     */
    @Override
    public String forShareLockHint() {
        return "";
    }

    /**
     * Sets a UUID parameter using {@link PreparedStatement#setObject(int, Object)}, which allows the H2 JDBC
     * driver to bind the value as a native UUID type.
     *
     * @param preparedStatement the prepared statement.
     * @param index the parameter index.
     * @param uuid the UUID value.
     * @throws SQLException if a database access error occurs.
     * @since 1.11
     */
    @Override
    public void setParameter(PreparedStatement preparedStatement, int index,
                             UUID uuid) throws SQLException {
        preparedStatement.setObject(index, uuid);
    }

    /**
     * Returns the SQL statement for getting the next value of the given sequence.
     *
     * @param sequenceName the name of the sequence.
     * @return the SQL statement for getting the next value of the given sequence.
     * @since 1.11
     */
    @Override
    public String sequenceNextVal(String sequenceName) {
        return "NEXT VALUE FOR %s".formatted(getSafeIdentifier(sequenceName));
    }

    /**
     * H2 returns every generated key for a single multi-row {@code INSERT} through {@code getGeneratedKeys}, so batch
     * {@code insertAndFetchIds} can emit one multi-row {@code VALUES} statement rather than a JDBC {@code executeBatch}.
     *
     * @return {@code true}.
     * @since 1.13
     */
    @Override
    public boolean supportsMultiRowGeneratedKeys() {
        return true;
    }
}
