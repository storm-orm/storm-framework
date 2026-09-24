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

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.Column;

public class MSSQLServerSqlDialect extends DefaultSqlDialect {

    public MSSQLServerSqlDialect() {
    }

    public MSSQLServerSqlDialect(StormConfig config) {
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
     * Returns the selected columns rather than the key alone.
     *
     * <p>SQL Server does not resolve functional dependency from a grouped key, so every non-aggregated column of the
     * select list has to appear in the GROUP BY. The rows are the ones the key identifies either way; only the
     * text differs.</p>
     */
    @Override
    public List<Column> groupBy(List<Column> key, List<Column> selected) {
        return selected;
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

    /**
     * The reserved keywords Microsoft documents for Transact-SQL, and {@code REGEXP_LIKE}, which SQL Server 2025
     * refuses as an unquoted identifier without documenting it as reserved.
     */
    private static final Set<String> RESERVED_WORDS = Set.of(
            "ADD", "ALL", "ALTER", "AND", "ANY", "AS", "ASC", "AUTHORIZATION", "BACKUP", "BEGIN", "BETWEEN", "BREAK",
            "BROWSE", "BULK", "BY", "CASCADE", "CASE", "CHECK", "CHECKPOINT", "CLOSE", "CLUSTERED", "COALESCE",
            "COLLATE", "COLUMN", "COMMIT", "COMPUTE", "CONSTRAINT", "CONTAINS", "CONTAINSTABLE", "CONTINUE", "CONVERT",
            "CREATE", "CROSS", "CURRENT", "CURRENT_DATE", "CURRENT_TIME", "CURRENT_TIMESTAMP", "CURRENT_USER", "CURSOR",
            "DATABASE", "DBCC", "DEALLOCATE", "DECLARE", "DEFAULT", "DELETE", "DENY", "DESC", "DISK", "DISTINCT",
            "DISTRIBUTED", "DOUBLE", "DROP", "DUMP", "ELSE", "END", "ERRLVL", "ESCAPE", "EXCEPT", "EXEC", "EXECUTE",
            "EXISTS", "EXIT", "EXTERNAL", "FETCH", "FILE", "FILLFACTOR", "FOR", "FOREIGN", "FREETEXT", "FREETEXTTABLE",
            "FROM", "FULL", "FUNCTION", "GOTO", "GRANT", "GROUP", "HAVING", "HOLDLOCK", "IDENTITY", "IDENTITYCOL",
            "IDENTITY_INSERT", "IF", "IN", "INDEX", "INNER", "INSERT", "INTERSECT", "INTO", "IS", "JOIN", "KEY", "KILL",
            "LEFT", "LIKE", "LINENO", "LOAD", "MERGE", "NATIONAL", "NOCHECK", "NONCLUSTERED", "NOT", "NULL", "NULLIF",
            "OF", "OFF", "OFFSETS", "ON", "OPEN", "OPENDATASOURCE", "OPENQUERY", "OPENROWSET", "OPENXML", "OPTION",
            "OR", "ORDER", "OUTER", "OVER", "PERCENT", "PIVOT", "PLAN", "PRECISION", "PRIMARY", "PRINT", "PROC",
            "PROCEDURE", "PUBLIC", "RAISERROR", "READ", "READTEXT", "RECONFIGURE", "REFERENCES", "REGEXP_LIKE",
            "REPLICATION", "RESTORE", "RESTRICT", "RETURN", "REVERT", "REVOKE", "RIGHT", "ROLLBACK", "ROWCOUNT",
            "ROWGUIDCOL", "RULE", "SAVE", "SCHEMA", "SECURITYAUDIT", "SELECT", "SEMANTICKEYPHRASETABLE",
            "SEMANTICSIMILARITYDETAILSTABLE", "SEMANTICSIMILARITYTABLE", "SESSION_USER", "SET", "SETUSER", "SHUTDOWN",
            "SOME", "STATISTICS", "SYSTEM_USER", "TABLE", "TABLESAMPLE", "TEXTSIZE", "THEN", "TO", "TOP", "TRAN",
            "TRANSACTION", "TRIGGER", "TRUNCATE", "TRY_CONVERT", "TSEQUAL", "UNION", "UNIQUE", "UNPIVOT", "UPDATE",
            "UPDATETEXT", "USE", "USER", "VALUES", "VARYING", "VIEW", "WAITFOR", "WHEN", "WHERE", "WHILE", "WITH",
            "WRITETEXT"
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
     * Escapes the given database identifier (e.g., table or column name) according to SQL Server.
     *
     * @param name the identifier to escape (must not be {@code null})
     * @return the escaped identifier
     */
    @Override
    public String escape(String name) {
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

    @Override
    public String orderByForOffset() {
        // OFFSET and OFFSET-FETCH are only valid after an ORDER BY. A constant ordering satisfies the grammar
        // without imposing an order the query did not ask for.
        return "ORDER BY (SELECT NULL)";
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

    /**
     * Returns {@code false}: the SQL Server driver rejects reading a batch's generated keys, failing with
     * "The statement must be executed before any results can be obtained". Keys come from an
     * {@code OUTPUT INSERTED} clause on the insert statement instead.
     *
     * @return {@code false}.
     * @since 1.13
     */
    @Override
    public boolean supportsBatchGeneratedKeys() {
        return false;
    }
}
