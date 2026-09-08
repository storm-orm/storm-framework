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
package st.orm.spi.mariadb;

import st.orm.StormConfig;
import st.orm.spi.mysql.MySQLSqlDialect;

/**
 * The MariaDB dialect.
 *
 * <h2>Row value comparison</h2>
 *
 * <p>MariaDB inherits {@link MySQLSqlDialect#rendersTupleComparison} unchanged: a row value tuple is rendered for
 * a multi-row list and nowhere else. MariaDB is the dialect that makes that choice necessary rather than merely
 * tidy, because its optimizer treats a row value comparison differently depending on the statement it sits in.
 * Measured on 10.5.22 and confirmed on 11.4.12, against a 118k-row table keyed on {@code (study_id, user_id)}:</p>
 *
 * <table border="1">
 *   <caption>MariaDB plan for each comparison shape</caption>
 *   <tr><th>Shape</th><th>As a tuple</th><th>As the expansion</th><th>Rendered as</th></tr>
 *   <tr><td>Single row of equality, SELECT</td><td>{@code const}, primary key, 1 row</td>
 *       <td>{@code const}, primary key, 1 row</td><td>Expansion</td></tr>
 *   <tr><td>Single row of equality, UPDATE</td>
 *       <td>{@code index}, <em>no candidate key</em>, all rows — <strong>~7500x slower</strong></td>
 *       <td>{@code range}, 1 row</td><td>Expansion</td></tr>
 *   <tr><td>Single row of equality, DELETE</td><td>{@code ALL}, all rows</td>
 *       <td>{@code range}, 1 row</td><td>Expansion</td></tr>
 *   <tr><td>Multi-row list (500 keys)</td>
 *       <td>Materialized semi-join on {@code distinct_key} — <strong>~150x faster</strong></td>
 *       <td>500-branch OR</td><td><strong>Tuple</strong></td></tr>
 *   <tr><td>Ordering comparison</td><td>{@code ALL}, all rows</td>
 *       <td>{@code range}, primary key</td><td>Expansion</td></tr>
 * </table>
 *
 * <p>The SELECT row is what makes this easy to miss: the read path reduces the row value comparison to its
 * conjunction and takes the index, so the tuple looks harmless until it reaches a write. In an UPDATE or a DELETE
 * the reduction does not happen, no index is even considered, and every identified row costs a scan of the whole
 * table. A batch of keyed updates is then quadratic in the table, and because a scanning statement locks what it
 * examines, two such batches against one table deadlock rather than merely running slowly.</p>
 *
 * <p>The list row is the mirror image and the reason the tuple is kept for it.</p>
 */
public class MariaDBSqlDialect extends MySQLSqlDialect {

    public MariaDBSqlDialect() {
    }

    public MariaDBSqlDialect(StormConfig config) {
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
        return "MariaDB";
    }

    /**
     * Returns a fetch size of 1000 to control result batching.
     *
     * <p>Unlike MySQL Connector/J, the MariaDB Connector/J driver does not support {@code Integer.MIN_VALUE}
     * as a fetch size for row-by-row streaming. Instead, a positive fetch size is used to instruct the driver
     * to fetch rows in batches, reducing memory consumption for large result sets while maintaining good
     * throughput.</p>
     *
     * @return {@code 1000}.
     * @since 1.10
     */
    @Override
    public int defaultFetchSize() {
        return 1000;
    }

    /**
     * Returns {@code false} because the positive fetch size is safe to apply universally.
     *
     * <p>A positive fetch size costs eager consumption nothing: an eager read drains the result before it
     * returns, so it never meets another statement. A result that stays open does: when another statement
     * executes on the connection while a batched result is still being read, MariaDB Connector/J reads the rest
     * of that result into memory first, where MySQL Connector/J rejects the statement. Storm refuses the nested
     * statement itself, on every dialect, so the difference never reaches the application.</p>
     *
     * @return {@code false}.
     * @since 1.10
     */
    @Override
    public boolean streamOnlyFetchSize() {
        return false;
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
     * Returns the strategy for discovering sequences in the database schema.
     *
     * <p>MariaDB supports sequences (since 10.3) but exposes them as {@code INFORMATION_SCHEMA.TABLES} rows with
     * {@code TABLE_TYPE = 'SEQUENCE'} rather than through the {@code INFORMATION_SCHEMA.SEQUENCES} view, which only
     * exists since MariaDB 11.5.</p>
     *
     * @return {@link SequenceDiscoveryStrategy#INFORMATION_SCHEMA_TABLES}.
     * @since 1.14
     */
    @Override
    public SequenceDiscoveryStrategy sequenceDiscoveryStrategy() {
        return SequenceDiscoveryStrategy.INFORMATION_SCHEMA_TABLES;
    }

    /**
     * MariaDB supports {@code INSERT ... RETURNING} (since 10.5), so batch {@code insertAndFetchIds} reads the keys
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
     * Disables the multi-row generated-keys path inherited from {@link MySQLSqlDialect}. Unlike MySQL Connector/J,
     * the MariaDB Connector/J driver returns only a single key from {@code getGeneratedKeys} for a multi-row
     * {@code INSERT}; the {@link #supportsInsertReturning() RETURNING} path is used instead.
     *
     * @return {@code false}.
     * @since 1.13
     */
    @Override
    public boolean supportsMultiRowGeneratedKeys() {
        return false;
    }
}
