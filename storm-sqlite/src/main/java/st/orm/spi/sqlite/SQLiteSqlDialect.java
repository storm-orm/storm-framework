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

import static java.util.stream.Collectors.toSet;

import jakarta.annotation.Nonnull;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import st.orm.PersistenceException;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.SqlDialect;

public class SQLiteSqlDialect extends DefaultSqlDialect implements SqlDialect {

    public SQLiteSqlDialect() {
    }

    public SQLiteSqlDialect(@Nonnull StormConfig config) {
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

    /**
     * SQLite does not support aliasing the target table in DELETE statements.
     */
    @Override
    public boolean supportsDeleteAlias() {
        return false;
    }

    /**
     * SQLite does not support multi-value tuples in the IN clause.
     */
    @Override
    public boolean supportsMultiValueTuples() {
        return false;
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

    private static final Set<String> SQLITE_KEYWORDS = Stream.concat(ANSI_KEYWORDS.stream(), Stream.of(
            "ABORT", "AUTOINCREMENT", "CONFLICT", "DATABASE", "DETACH", "EXPLAIN", "FAIL",
            "GLOB", "IF", "IGNORE", "INDEX", "INDEXED", "INSTEAD", "ISNULL", "KEY", "LIMIT",
            "NOTNULL", "OFFSET", "PLAN", "PRAGMA", "QUERY", "RAISE", "REGEXP", "REINDEX",
            "RENAME", "REPLACE", "VACUUM", "VIRTUAL"
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
        return SQLITE_KEYWORDS.contains(name.toUpperCase());
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
}
