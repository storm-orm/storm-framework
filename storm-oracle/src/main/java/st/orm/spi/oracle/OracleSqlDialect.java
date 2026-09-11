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
package st.orm.spi.oracle;

import static java.util.stream.Collectors.toSet;
import static st.orm.Operator.BETWEEN;
import static st.orm.Operator.GREATER_THAN;
import static st.orm.Operator.GREATER_THAN_OR_EQUAL;
import static st.orm.Operator.LESS_THAN;
import static st.orm.Operator.LESS_THAN_OR_EQUAL;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import st.orm.Operator;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.template.Column;

public class OracleSqlDialect extends DefaultSqlDialect {

    public OracleSqlDialect() {
    }

    public OracleSqlDialect(StormConfig config) {
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

    /**
     * Returns the selected columns rather than the key alone.
     *
     * <p>Oracle does not resolve functional dependency from a grouped key, so every non-aggregated column of the
     * select list has to appear in the GROUP BY. The rows are the ones the key identifies either way; only the
     * text differs.</p>
     */
    @Override
    public List<Column> groupBy(List<Column> key, List<Column> selected) {
        return selected;
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
    public boolean isKeyword(String name) {
        return ORACLE_RESERVED.contains(name.toUpperCase());
    }

    @Override
    public String escape(String name) {
        return "\"%s\"".formatted(name.replace("\"", "\"\""));
    }

    /**
     * Returns whether a multi-column comparison renders as a row value tuple, which for Oracle is the case for an
     * ordering comparison and for a multi-row list.
     *
     * <p>Unlike the other tuple-rendering dialects, Oracle's plans have not been measured here, so this answer is
     * reasoned rather than observed and the table other dialects carry is deliberately absent.</p>
     *
     * <p>A single row of equality renders as the {@code AND} expansion. Across every dialect that has been
     * measured the expansion ties the tuple or beats it for that shape, and the shape is the identifying
     * comparison behind every keyed update, delete and lookup, so it takes the form known to be safe rather than
     * one resting on an assumption about how this planner rewrites a row value comparison.</p>
     *
     * <p>The ordering comparison and the multi-row list keep the tuple, which is the behaviour that predates this
     * distinction; Oracle documents row value comparison for both. Should the plans be measured and disagree,
     * this is the one method to revisit.</p>
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
     * Returns a fetch size of 1000 to control Oracle's row prefetch behavior.
     *
     * <p>The Oracle JDBC driver uses a prefetch mechanism to reduce database round-trips. Setting the fetch
     * size to 1000 instructs the driver to prefetch rows in batches of 1000, providing a good balance between
     * memory consumption and network efficiency for both streaming and eager result consumption.</p>
     *
     * @return {@code 1000}.
     * @since 1.10
     */
    /**
     * The Oracle driver keeps the connection's read-only flag to itself, so the transaction is opened read-only on
     * the server here, which also gives it transaction-level read consistency.
     */
    @Override
    public Optional<String> readOnlyTransactionStatement() {
        return Optional.of("SET TRANSACTION READ ONLY");
    }

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
