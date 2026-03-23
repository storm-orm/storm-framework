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
 * distributed under the "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.spi.oracle;

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
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.SqlDialect;
import st.orm.core.template.SqlTemplateException;

public class OracleSqlDialect extends DefaultSqlDialect implements SqlDialect {

    public OracleSqlDialect() {
    }

    public OracleSqlDialect(@Nonnull StormConfig config) {
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
        return "Oracle";
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
        // Oracle doesn't allow table aliases in DELETE.
        return false;
    }

    /**
     * Indicates whether the SQL dialect supports multi-value tuples in the IN clause.
     *
     * @return {@code true} if multi-value tuples are supported, {@code false} otherwise.
     * @since 1.2
     */
    @Override
    public boolean supportsMultiValueTuples() {
        // Oracle supports multi-column IN (col1, col2) IN ((v1_1, v1_2), ...).
        return true;
    }

    private static final Pattern ORACLE_IDENTIFIER = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.2
     */
    @Override
    public Pattern getValidIdentifierPattern() {
        return ORACLE_IDENTIFIER;
    }

    private static final Set<String> ORACLE_RESERVED = Stream.concat(ANSI_KEYWORDS.stream(), Stream.of(
            "ACCESS", "AUDIT", "CLUSTER", "COMMENT", "COMPRESS", "EXCLUSIVE", "FILE", "IDENTIFIED",
            "INCREMENT", "INDEX", "INITIAL", "LOCK", "LONG", "MAXEXTENTS", "MLSLABEL", "MODE", "MODIFY", "NOWAIT",
            "OFFLINE", "ONLINE", "PCTFREE", "RAW", "ROWID", "ROWNUM", "SESSION", "SHARE", "SUCCESSFUL", "SYNONYM",
            "UID", "VALIDATE", "VARCHAR2", "VIEW"
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
        return ORACLE_RESERVED.contains(name.toUpperCase());
    }

    @Override
    public String escape(@Nonnull String name) {
        return "\"%s\"".formatted(name.replace("\"", "\"\""));
    }

    /**
     * Regex for double-quoted identifiers in Oracle (embedded quotes are doubled).
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
        "\"(?:\"\"|[^\"])*\""
    );

    @Override
    public Pattern getIdentifierPattern() {
        return IDENTIFIER_PATTERN;
    }

    /**
     * Regex for single-quoted string literals in Oracle (escaped by doubling the single quote).
     */
    private static final Pattern QUOTE_LITERAL_PATTERN = Pattern.compile("'(?:''|\\\\.|[^'\\\\])*'");

    @Override
    public Pattern getQuoteLiteralPattern() {
        return QUOTE_LITERAL_PATTERN;
    }

    /**
     * Builds a multi-column expression using row value constructor syntax.
     *
     * <p>Oracle supports tuple comparison syntax for all comparison operators, producing compact SQL like
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
     * Returns a fetch size of 1000 to control Oracle's row prefetch behavior.
     *
     * <p>The Oracle JDBC driver uses a prefetch mechanism to reduce database round-trips. Setting the fetch
     * size to 1000 instructs the driver to prefetch rows in batches of 1000, providing a good balance between
     * memory consumption and network efficiency for both streaming and eager result consumption.</p>
     *
     * @return {@code 1000}.
     * @since 1.10
     */
    @Override
    public int defaultFetchSize() {
        return 1000;
    }

    /**
     * For Oracle 12c+ you can use:
     *   SELECT ... FETCH FIRST n ROWS ONLY
     */
    @Override
    public String limit(int limit) {
        return "FETCH FIRST %d ROWS ONLY".formatted(limit);
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
        return "OFFSET %d ROWS".formatted(offset);
    }

    /**
     * Oracle 12c+ offset syntax is:
     *   SELECT ...
     *   OFFSET {offset} ROWS FETCH NEXT {limit} ROWS ONLY
     */
    @Override
    public String limit(int offset, int limit) {
        return "OFFSET %d ROWS FETCH NEXT %d ROWS ONLY".formatted(offset, limit);
    }

    /**
     * Oracle does not support a shared lock hint for SELECT statements. Return an empty string.
     *
     * @return an empty string.
     */
    @Override
    public String forShareLockHint() {
        // We may add configuration flags to choose between an empty String and a PersistenceException.
        return "";
    }

    /**
     * Returns the lock hint for a write lock in Oracle.
     * Oracle supports "FOR UPDATE" to lock rows for update.
     *
     * @return the lock hint for a write lock.
     */
    @Override
    public String forUpdateLockHint() {
        return "FOR UPDATE";
    }

    /**
     * Returns the strategy for discovering sequences in the database schema.
     *
     * <p>Oracle uses the {@code ALL_SEQUENCES} dictionary view instead of {@code INFORMATION_SCHEMA.SEQUENCES}.</p>
     *
     * @return {@link SequenceDiscoveryStrategy#ALL_SEQUENCES}.
     * @since 1.9
     */
    @Override
    public SequenceDiscoveryStrategy sequenceDiscoveryStrategy() {
        return SequenceDiscoveryStrategy.ALL_SEQUENCES;
    }

    /**
     * Returns the strategy for discovering constraints in the database schema.
     *
     * <p>Oracle does not support {@code INFORMATION_SCHEMA}. Constraints are discovered using the
     * {@code ALL_CONSTRAINTS} and {@code ALL_CONS_COLUMNS} dictionary views.</p>
     *
     * @return {@link ConstraintDiscoveryStrategy#ALL_CONSTRAINTS}.
     * @since 1.9
     */
    @Override
    public ConstraintDiscoveryStrategy constraintDiscoveryStrategy() {
        return ConstraintDiscoveryStrategy.ALL_CONSTRAINTS;
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
        return getSafeIdentifier(sequenceName) + ".NEXTVAL";
    }
}
