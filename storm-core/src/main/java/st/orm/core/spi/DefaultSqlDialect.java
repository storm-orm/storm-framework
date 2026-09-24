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
package st.orm.core.spi;


import static java.util.stream.Collectors.joining;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;
import static st.orm.Operator.NOT_EQUALS;
import static st.orm.Operator.NOT_IN;
import static st.orm.StormConfig.ANSI_ESCAPING;
import static st.orm.core.spi.StormConfigHelper.getBoolean;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.SequencedMap;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Pattern;
import st.orm.Operator;
import st.orm.PersistenceException;
import st.orm.SqlTemplateException;
import st.orm.StormConfig;
import st.orm.core.template.SqlDialect;

public class DefaultSqlDialect implements SqlDialect {

    private final boolean ansiEscaping;

    public DefaultSqlDialect() {
        this(StormConfig.defaults());
    }

    public DefaultSqlDialect(StormConfig config) {
        this.ansiEscaping = getBoolean(config, ANSI_ESCAPING, false);
    }

    /**
     * Returns the name of the SQL dialect.
     *
     * @return the name of the SQL dialect.
     * @since 1.2
     */
    @Override
    public String name() {
        return ansiEscaping ? "Default[ansi]" : "Default";
    }

    /**
     * Indicates whether the SQL dialect supports delete aliases.
     *
     * <p>Delete aliases allow delete statements to use table aliases in joins, making it easier to filter rows based
     * on related data.</p>
     *
     * @return {@code true} if delete aliases are supported, {@code false} otherwise.
     */
    @Override
    public boolean supportsDeleteAlias() {
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
        return false;
    }

    // ANSI SQL valid identifier regex pattern.
    private static final Pattern SQL_IDENTIFIER_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_]*$");

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.2
     */
    @Override
    public Pattern getValidIdentifierPattern() {
        return SQL_IDENTIFIER_PATTERN;
    }

    protected static final Set<String> ANSI_KEYWORDS = Set.of(
            "ABSOLUTE", "ACTION", "ADD", "ALL", "ALLOCATE", "ALTER", "AND", "ANY", "ARE",
            "ARRAY", "AS", "ASENSITIVE", "ASYMMETRIC", "AT", "ATOMIC", "AUTHORIZATION",
            "BEGIN", "BETWEEN", "BIGINT", "BINARY", "BLOB", "BOOLEAN", "BOTH", "BY",
            "CALL", "CALLED", "CASCADED", "CASE", "CAST", "CHAR", "CHARACTER", "CHECK",
            "CLOB", "CLOSE", "COLLATE", "COLUMN", "COMMIT", "CONNECT", "CONSTRAINT",
            "CORRESPONDING", "CREATE", "CROSS", "CUBE", "CURRENT", "CURRENT_CATALOG",
            "CURRENT_DATE", "CURRENT_DEFAULT_TRANSFORM_GROUP", "CURRENT_PATH",
            "CURRENT_ROLE", "CURRENT_SCHEMA", "CURRENT_TIME", "CURRENT_TIMESTAMP",
            "CURRENT_TRANSFORM_GROUP_FOR_TYPE", "CURRENT_USER", "CURSOR", "CYCLE",
            "DATE", "DAY", "DEALLOCATE", "DEC", "DECIMAL", "DECLARE", "DEFAULT",
            "DELETE", "DEREF", "DESCRIBE", "DETERMINISTIC", "DISCONNECT", "DISTINCT",
            "DO", "DOUBLE", "DROP", "DYNAMIC", "EACH", "ELEMENT", "ELSE", "END",
            "END-EXEC", "ESCAPE", "EXCEPT", "EXEC", "EXECUTE", "EXISTS", "EXTERNAL",
            "FALSE", "FETCH", "FILTER", "FLOAT", "FOR", "FOREIGN", "FREE", "FROM",
            "FULL", "FUNCTION", "GET", "GLOBAL", "GRANT", "GROUP", "GROUPING", "HAVING",
            "HOLD", "HOUR", "IDENTITY", "IN", "INDICATOR", "INNER", "INOUT", "INPUT",
            "INSENSITIVE", "INSERT", "INT", "INTEGER", "INTERSECT", "INTERVAL", "INTO",
            "IS", "ITERATE", "JOIN", "LAG", "LANGUAGE", "LARGE", "LAST", "LATERAL",
            "LEAD", "LEADING", "LEFT", "LIKE", "LOCAL", "LOCALTIME", "LOCALTIMESTAMP",
            "MATCH", "MAXVALUE", "MINUTE", "MODIFIES", "MODULE", "MONTH", "MULTISET",
            "NATIONAL", "NATURAL", "NCHAR", "NCLOB", "NEW", "NO", "NONE", "NORMALIZE",
            "NOT", "NTH_VALUE", "NTILE", "NULL", "NUMERIC", "OCTET_LENGTH", "OF",
            "OFFSET", "OLD", "ON", "ONLY", "OPEN", "OR", "ORDER", "OUT", "OUTER",
            "OVER", "OVERLAPS", "PARAMETER", "PARTITION", "PERCENT", "PERCENT_RANK",
            "PERCENTILE_CONT", "PERCENTILE_DISC", "PERIOD", "PORTION", "POSITION",
            "PRECISION", "PREPARE", "PRIMARY", "PROCEDURE", "RANGE", "READS", "REAL",
            "RECURSIVE", "REF", "REFERENCES", "REFERENCING", "REGR_AVGX", "REGR_AVGY",
            "REGR_COUNT", "REGR_INTERCEPT", "REGR_R2", "REGR_SLOPE", "REGR_SXX",
            "REGR_SXY", "REGR_SYY", "RELEASE", "RESULT", "RETURN", "RETURNS", "REVOKE",
            "RIGHT", "ROLLBACK", "ROLLUP", "ROW", "ROWS", "SAVEPOINT", "SCOPE",
            "SCROLL", "SEARCH", "SECOND", "SELECT", "SENSITIVE", "SESSION_USER", "SET",
            "SIMILAR", "SMALLINT", "SOME", "SPECIFIC", "SPECIFICTYPE", "SQL", "SQLEXCEPTION",
            "SQLSTATE", "SQLWARNING", "START", "STATIC", "SUBMULTISET", "SUBSTRING",
            "SUM", "SYMMETRIC", "SYSTEM", "SYSTEM_TIME", "SYSTEM_USER", "TABLE",
            "TABLESAMPLE", "THEN", "TIME", "TIMESTAMP", "TIMEZONE_HOUR",
            "TIMEZONE_MINUTE", "TO", "TRAILING", "TRANSLATE", "TRANSLATION", "TREAT",
            "TRIGGER", "TRIM", "TRUE", "UESCAPE", "UNDER", "UNION", "UNIQUE", "UNKNOWN",
            "UNNEST", "UPDATE", "UPPER", "USE", "USER", "USING", "VALUE", "VALUES", "VAR_POP",
            "VAR_SAMP", "VARBINARY", "VARCHAR", "VARYING", "VERSIONING", "WHEN",
            "WHENEVER", "WHERE", "WIDTH_BUCKET", "WINDOW", "WITH", "WITHIN", "WITHOUT",
            "YEAR"
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
        return ANSI_KEYWORDS.contains(name.toUpperCase(Locale.ROOT));
    }

    /**
     * Escapes the given database identifier (e.g., table or column name) according to this SQL dialect.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(String name) {
        if (ansiEscaping) {
            // Escape identifier for ANSI SQL by wrapping it in double quotes and doubling any embedded double quotes.
            return "\"" + name.replace("\"", "\"\"") + "\"";
        }
        return name;
    }

    private static final Pattern SINGLE_LINE_COMMENT_PATTERN = Pattern.compile("(--|#).*?(\\n|$)");

    /**
     * Returns the pattern for single line comments.
     *
     * @return the pattern for single line comments.
     * @since 1.2
     */
    @Override
    public Pattern getSingleLineCommentPattern() {
        return SINGLE_LINE_COMMENT_PATTERN;
    }

    private static final Pattern MULTI_LINE_COMMENT_PATTERN = Pattern.compile("(?s)/\\*.*?\\*/");

    /**
     * Returns the pattern for multi line comments.
     *
     * @return the pattern for multi line comments.
     * @since 1.2
     */
    @Override
    public Pattern getMultiLineCommentPattern() {
        return MULTI_LINE_COMMENT_PATTERN;
    }

    /**
     * Regex for double-quoted identifiers (including escaped quotes inside). In ANSI SQL, an embedded double quote is
     * escaped by doubling it (""). Look for sequences of "" or any non-quote character, all enclosed between double
     * quotes.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile("\"(?:\"\"|[^\"])*\"");

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
     * Regex for single-quoted string literals, handling double single quotes and backslash escapes.
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
     * Returns a string for the given column name.
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
    @Override
    public String multiValueIn(List<SequencedMap<String, Object>> values,
                               Function<Object, String> parameterFunction) throws SqlTemplateException {
        boolean wrapRows = values.size() > 1;
        List<String> args = new ArrayList<>();
        for (var valueMap : values) {
            String row = valueMap.entrySet().stream()
                    .map(entry -> EQUALS.format(entry.getKey(), parameterFunction.apply(entry.getValue())))  // We can safely use EQUALS here.
                    .collect(joining(" AND "));
            args.add(wrapRows ? "(%s)".formatted(row) : row);
            args.add(" OR ");
        }
        if (!args.isEmpty()) {
            args.removeLast();
        }
        return String.join("", args);
    }

    /**
     * Builds a multi-column expression, rendering it as a row value tuple where {@link #rendersTupleComparison}
     * allows and as the {@code AND} expansion otherwise.
     *
     * @param operator the comparison operator to apply.
     * @param values the multi-row values. Each map represents a single row of column-name-to-value mappings.
     * @param parameterFunction the function responsible for binding the parameters.
     * @return the SQL fragment representing the multi-column expression.
     * @throws SqlTemplateException if the operator is not supported for multi-column expressions.
     * @since 1.13
     */
    @Override
    public String multiColumnExpression(Operator operator,
                                        List<SequencedMap<String, Object>> values,
                                        Function<Object, String> parameterFunction)
            throws SqlTemplateException {
        if (rendersTupleComparison(operator, values.size())) {
            return tupleExpression(operator, values, parameterFunction);
        }
        return SqlDialect.super.multiColumnExpression(operator, values, parameterFunction);
    }

    /**
     * Returns whether a multi-column comparison renders as a row value tuple rather than as the {@code AND}
     * expansion.
     *
     * <p>Rendering a tuple is a question of what the optimizer does with one, not of what the grammar accepts. A
     * dialect answers {@code true} only where the tuple earns its place, because the two forms are not
     * interchangeable to a planner:</p>
     *
     * <ul>
     *   <li><strong>A single row of equality</strong> never renders as a tuple. The expansion
     *   {@code a = ? AND b = ?} is the shape an optimizer resolves to an index lookup, while a row value
     *   comparison is not reduced to that shape everywhere, and a planner that leaves it alone in an UPDATE or a
     *   DELETE scans the table instead. The tuple gains nothing here on any dialect, so the identifying
     *   comparison that every keyed update, delete and lookup is built from takes the form that is safe
     *   throughout.</li>
     *   <li><strong>A multi-row list</strong> is where the tuple pays: {@code (a, b) IN ((?, ?), ...)} states the
     *   set once, and a planner that resolves it against the index handles it far better than the equivalent
     *   chain of ORs.</li>
     *   <li><strong>An ordering comparison</strong> divides the dialects. Some use the index for
     *   {@code (a, b) > (?, ?)} and cannot use it for the lexicographic expansion; others do the reverse. This is
     *   the keyset pagination path, so each dialect answers for itself.</li>
     * </ul>
     *
     * <p>The default never renders a tuple, which suits dialects without row value comparison and is a safe answer
     * for any dialect whose planner has not been measured.</p>
     *
     * @param operator the comparison operator to apply.
     * @param rowCount the number of value rows in the comparison.
     * @return {@code true} to render a row value tuple, {@code false} to render the {@code AND} expansion.
     * @since 1.13
     */
    protected boolean rendersTupleComparison(Operator operator, int rowCount) {
        return false;
    }

    /**
     * Returns whether a multi-column comparison of the equality family renders as a row value tuple, which is the
     * case only for a list of more than one row.
     *
     * <p>Provided for dialects that render tuples, so the single-row rule that holds for all of them is stated
     * once.</p>
     *
     * @param operator the comparison operator to apply.
     * @param rowCount the number of value rows in the comparison.
     * @return {@code true} if the operator is of the equality family and the comparison spans multiple rows.
     * @since 1.13
     */
    protected final boolean isMultiRowEquality(Operator operator, int rowCount) {
        return (operator == EQUALS || operator == NOT_EQUALS || operator == IN || operator == NOT_IN)
                && rowCount > 1;
    }

    /**
     * Builds a tuple expression by composing column names and values into row value constructor syntax.
     *
     * <p>The {@link Operator#format} method is used to produce the final SQL, which allows all operators to work
     * naturally with tuple syntax. For example, {@code GREATER_THAN.format("(a, b)", "(?, ?)")} produces
     * {@code (a, b) > (?, ?)}.</p>
     *
     * <p>Called for a subclass that answers {@link #rendersTupleComparison} affirmatively, which is how a dialect
     * supporting row value comparison opts into the compact form for the shapes its planner handles well.</p>
     *
     * @param operator the comparison operator.
     * @param values the column-to-value mappings for each row.
     * @param parameterFunction the function for binding parameters.
     * @return the tuple expression SQL fragment.
     * @since 1.9
     */
    protected String tupleExpression(Operator operator,
                                      List<SequencedMap<String, Object>> values,
                                      Function<Object, String> parameterFunction) {
        Set<String> columns = new LinkedHashSet<>(values.getFirst().keySet());
        String columnTuple = "(%s)".formatted(String.join(", ", columns));
        String[] valueTuples = values.stream()
                .map(row -> "(%s)".formatted(
                        columns.stream()
                                .map(row::get)
                                .map(parameterFunction)
                                .collect(joining(", "))))
                .toArray(String[]::new);
        return operator.format(columnTuple, valueTuples);
    }

    /**
     * Returns {@code true} if the limit should be applied after the SELECT clause, {@code false} to apply the limit at
     * the end of the query.
     *
     * @return {@code true} if the limit should be applied after the SELECT clause, {@code false} to apply the limit at
     * the end of the query.
     * @since 1.2
     */
    @Override
    public boolean applyLimitAfterSelect() {
        return false;
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
        // The limit is inlined as a literal, not bound (see SqlDialect#limit): a literal lets the planner pick an
        // early-terminating plan for small pages, where a bound value would force a slower generic plan. Basic LIMIT
        // syntax works on most databases in the test containers; for production, ensure the right dialect is used.
        return "LIMIT " + limit;
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
        // Inlined as a literal, not bound; see SqlDialect#limit for the rationale.
        return "OFFSET " + offset;
    }

    /**
     * Returns a string template for the given limit and offset.
     *
     * @param offset the offset.
     * @param limit the maximum number of records to return.
     * @return a string template for the given limit and offset.
     * @since 1.2
     */
    @Override
    public String limit(int offset, int limit) {
        // Both values inlined as literals, not bound (see SqlDialect#limit). Basic LIMIT/OFFSET syntax works on most
        // databases in the test containers; for production, ensure the right dialect is used.
        return "LIMIT %s OFFSET %s".formatted(limit, offset);
    }

    /**
     * Returns {@code true} if the lock hint should be applied after the FROM clause, {@code false} to apply the lock
     * hint at the end of the query.
     *
     * @return {@code true} if the lock hint should be applied after the FROM clause, {@code false} to apply the lock
     * hint at the end of the query.
     * @since 1.2
     */
    @Override
    public boolean applyLockHintAfterFrom() {
        return false;
    }

    /**
     * Returns the lock hint for a shared reading lock.
     *
     * @return the lock hint for a shared reading lock.
     * @since 1.2
     */
    @Override
    public String forShareLockHint() {
        return "FOR SHARE";
    }

    /**
     * Returns the lock hint for a write lock.
     *
     * @return the lock hint for a write lock.
     * @since 1.2
     */
    @Override
    public String forUpdateLockHint() {
        return "FOR UPDATE";
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
        throw new PersistenceException("Sequences are not supported by this dialect.");
    }
}
