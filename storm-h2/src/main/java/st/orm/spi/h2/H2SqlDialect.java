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
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import st.orm.Operator;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplateException;

public class H2SqlDialect extends DefaultSqlDialect implements SqlDialect {

    public H2SqlDialect() {
    }

    public H2SqlDialect(@Nonnull StormConfig config) {
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
     * H2 does not support aliasing the target table in DELETE statements.
     */
    @Override
    public boolean supportsDeleteAlias() {
        return false;
    }

    /**
     * H2 supports multi-value tuples in the IN clause.
     */
    @Override
    public boolean supportsMultiValueTuples() {
        return true;
    }

    private static final Pattern H2_IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.11
     */
    @Override
    public Pattern getValidIdentifierPattern() {
        return H2_IDENTIFIER;
    }

    private static final Set<String> H2_KEYWORDS = Stream.concat(ANSI_KEYWORDS.stream(), Stream.of(
            "AUTOINCREMENT", "CACHED", "EXPLAIN", "IF", "ILIKE", "INDEX", "KEY", "LIMIT",
            "MEMORY", "MINUS", "OFFSET", "QUALIFY", "REGEXP", "ROWNUM", "SYSDATE", "SYSTIME",
            "SYSTIMESTAMP", "TODAY", "TOP"
    )).collect(toSet());

    /**
     * Indicates whether the given name is a keyword in this SQL dialect.
     *
     * @param name the name to check.
     * @return {@code true} if the name is a keyword, {@code false} otherwise.
     * @since 1.11
     */
    @Override
    public boolean isKeyword(@Nonnull String name) {
        return H2_KEYWORDS.contains(name.toUpperCase());
    }

    /**
     * Escapes the given database identifier using double quotes (ANSI SQL standard).
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(@Nonnull String name) {
        return "\"%s\"".formatted(name.replace("\"", "\"\""));
    }

    /**
     * Regex for double-quoted identifiers (handling doubled double quotes as escapes).
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\"(?:\"\"|[^\"])*\""
    );

    /**
     * Returns the pattern for identifiers.
     *
     * @return the pattern for identifiers.
     */
    @Override
    public Pattern getIdentifierPattern() {
        return IDENTIFIER_PATTERN;
    }

    /**
     * Regex for single-quoted string literals, handling both doubled single quotes and backslash escapes.
     */
    private static final Pattern QUOTE_LITERAL_PATTERN = Pattern.compile(
            "'(?:''|\\.|[^'])*'"
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
     * Builds a multi-column expression using row value constructor syntax.
     *
     * <p>H2 supports tuple comparison syntax for all comparison operators, producing compact SQL like
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
     * @since 1.11
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
        return "OFFSET %d LIMIT %d".formatted(offset, limit);
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
     * Returns the lock hint for a write lock.
     *
     * @return the lock hint for a write lock.
     */
    @Override
    public String forUpdateLockHint() {
        return "FOR UPDATE";
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
    public void setParameter(@Nonnull PreparedStatement preparedStatement, int index,
                             @Nonnull UUID uuid) throws SQLException {
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
}
