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

import static st.orm.Match.anywhere;
import static st.orm.Match.exact;
import static st.orm.Match.prefix;
import static st.orm.Match.wordStart;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractMissingIndexConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/** The index a missing-index advice names is the one Oracle uses. */
@StormTest(rollback = false)
public class OracleMissingIndexConformanceTest extends AbstractMissingIndexConformanceTest {
    private static OracleContainer container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new OracleContainer("gvenzl/oracle-free:23");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected void analyze(Statement statement, String table) throws SQLException {
        statement.execute("BEGIN DBMS_STATS.GATHER_TABLE_STATS(USER, '" + table.toUpperCase(java.util.Locale.ROOT) + "', cascade => TRUE); END;");
    }

    @Override
    protected String plan(Connection connection, String sql) throws SQLException {
        var plan = new StringBuilder();
        try (var statement = connection.createStatement()) {
            statement.execute("EXPLAIN PLAN FOR " + sql);
            try (var resultSet = statement.executeQuery("SELECT plan_table_output FROM TABLE(DBMS_XPLAN.DISPLAY())")) {
                while (resultSet.next()) {
                    plan.append(resultSet.getString(1)).append('\n');
                }
            }
        }
        return plan.toString();
    }

    @Override
    protected List<String> searchProbeDdl() {
        return List.of(
                "CREATE TABLE search_probe (id NUMBER(10) PRIMARY KEY, title VARCHAR2(100) NOT NULL, title_lower VARCHAR2(100) GENERATED ALWAYS AS (LOWER(title)) VIRTUAL)");
    }

    @Override
    protected List<SearchIndexProbe> searchIndexProbes() {
        return List.of(
                // An ordinary index over the virtual lowered title serves a prefix and an exact match.
                new SearchIndexProbe(prefix(PROBE_TITLE_LOWER), "CREATE INDEX probe_stored ON search_probe (title_lower)", "probe_stored", true),
                new SearchIndexProbe(exact(PROBE_TITLE_LOWER), "CREATE INDEX probe_stored ON search_probe (title_lower)", "probe_stored", true),
                // A function-based index over another expression of the title, and an index on the bare title, hold
                // another expression than the match compares. An index over LOWER(title) is not probed: Oracle keys
                // it on the virtual title_lower column, which holds the same expression.
                new SearchIndexProbe(anywhere(PROBE_TITLE), "CREATE INDEX probe_upper ON search_probe (UPPER(title))", "probe_upper", false),
                new SearchIndexProbe(anywhere(PROBE_TITLE), "CREATE INDEX probe_title ON search_probe (title)", "probe_title", false),
                new SearchIndexProbe(wordStart(PROBE_TITLE), null, null, false));
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return (plan.contains("INDEX RANGE SCAN") || plan.contains("INDEX UNIQUE SCAN")) && plan.contains("PROBE_");
    }

}
