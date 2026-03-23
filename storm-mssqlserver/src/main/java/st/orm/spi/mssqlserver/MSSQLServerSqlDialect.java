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
package st.orm.spi.mssqlserver;

import static java.util.stream.Collectors.toSet;

import jakarta.annotation.Nonnull;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.SqlDialect;

public class MSSQLServerSqlDialect extends DefaultSqlDialect implements SqlDialect {

    public MSSQLServerSqlDialect() {
    }

    public MSSQLServerSqlDialect(@Nonnull StormConfig config) {
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
        return "MS SQL Server";
    }

    /**
     * Indicates whether the SQL dialect supports delete aliases.
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
        // SQL Server does not support multi-value tuple IN clauses.
        return false;
    }

    private static final Pattern MSSQL_IDENTIFIER = Pattern.compile("^[_A-Za-z#][_A-Za-z0-9]*$");

    /**
     * Returns the pattern for valid identifiers.
     *
     * @return the pattern for valid identifiers.
     * @since 1.2
     */
    @Override
    public Pattern getValidIdentifierPattern() {
        return MSSQL_IDENTIFIER;
    }

    private static final Set<String> MSSQL_RESERVED = Stream.concat(ANSI_KEYWORDS.stream(), Stream.of(
            "BACKUP", "BREAK", "BROWSE", "BULK", "CHECKPOINT", "CLUSTERED", "COMPUTE", "CONTAINS",
            "CONTAINSTABLE", "DENY", "DUMP", "ERRLVL", "EXTERNAL", "FREETEXT", "FREETEXTTABLE", "HOLDLOCK",
            "IDENTITY_INSERT", "IDENTITYCOL", "INDEX", "KILL", "LINENO", "MERGE", "NOCHECK", "NONCLUSTERED",
            "OFFSETS", "PERCENT", "PLAN", "PIVOT", "PRINT", "PROC", "RAISERROR", "READTEXT", "REPLICATION", "ROWCOUNT",
            "ROWGUIDCOL", "RULE", "SAVE", "SEQUENCE", "STATISTICS", "TEXTSIZE", "TOP", "TRAN", "TRANSACTION",
            "TRUNCATE", "TRY_CONVERT", "TSEQUAL", "UNPIVOT", "UPDATETEXT", "WAITFOR", "WHILE", "WRITETEXT"
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
        return MSSQL_RESERVED.contains(name.toUpperCase());
    }

    /**
     * Escapes the given database identifier (e.g., table or column name) according to SQL Server.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(@Nonnull String name) {
        // Escape identifier for SQL Server by wrapping it in square brackets and doubling any closing brackets.
        return "[%s]".formatted(name.replace("]", "]]"));
    }

    /**
     * Regex for identifiers. Supports SQL Server's square bracket quoting as well as double quotes.
     */
    private static final Pattern IDENTIFIER_PATTERN = Pattern.compile(
            "\\[(?:]]|[^]])+]|\"(?:\"\"|[^\"])+\""
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
     * Returns {@code true} if the limit should be applied after the SELECT clause, {@code false} to apply the limit at
     * the end of the query.
     *
     * @return {@code true} if the limit should be applied after the SELECT clause, {@code false} to apply the limit at
     * the end of the query.
     * @since 1.2
     */
    @Override
    public boolean applyLimitAfterSelect() {
        return true;
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
        // For SQL Server, use the TOP clause. Note: TOP must appear immediately after SELECT.
        return "TOP %d".formatted(limit);
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
        // Note: An ORDER BY clause is required for OFFSET to work correctly.
        return "OFFSET %d ROWS".formatted(offset);
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
        // For SQL Server 2012 and later, use the OFFSET-FETCH clause.
        // Note: An ORDER BY clause is required for OFFSET-FETCH to work correctly.
        return "OFFSET %d ROWS FETCH NEXT %d ROWS ONLY".formatted(offset, limit);
    }

    /**
     * Returns {@code true} if the lock hint should be applied after the FROM clause.
     *
     * @return {@code true} if the lock hint should be applied after the FROM clause.
     * @since 1.2
     */
    @Override
    public boolean applyLockHintAfterFrom() {
        // In SQL Server, table hints are applied immediately after the table name in the FROM clause.
        return true;
    }

    /**
     * Returns the lock hint for a shared reading lock.
     *
     * @return the lock hint for a shared reading lock.
     * @since 1.2
     */
    @Override
    public String forShareLockHint() {
        return "WITH (HOLDLOCK)";
    }

    /**
     * Returns the lock hint for a write lock.
     *
     * @return the lock hint for a write lock.
     * @since 1.2
     */
    @Override
    public String forUpdateLockHint() {
        return "WITH (UPDLOCK)";
    }

    /**
     * Returns the strategy for discovering constraints in the database schema.
     *
     * <p>Falls back to per-table JDBC metadata calls because the required
     * {@code POSITION_IN_UNIQUE_CONSTRAINT} column is not available in
     * {@code INFORMATION_SCHEMA.KEY_COLUMN_USAGE}.</p>
     *
     * @return {@link ConstraintDiscoveryStrategy#JDBC_METADATA}.
     * @since 1.9
     */
    @Override
    public ConstraintDiscoveryStrategy constraintDiscoveryStrategy() {
        return ConstraintDiscoveryStrategy.JDBC_METADATA;
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
        return "NEXT VALUE FOR " + getSafeIdentifier(sequenceName);
    }
}
