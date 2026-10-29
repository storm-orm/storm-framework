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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
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
    protected java.util.List<String> suggestProbeDdl() {
        return java.util.List.of(
                "CREATE TABLE suggest_probe (id NUMBER(10) PRIMARY KEY, title VARCHAR2(100) NOT NULL, title_lower VARCHAR2(100) GENERATED ALWAYS AS (LOWER(title)) VIRTUAL)",
                "CREATE INDEX probe_stored ON suggest_probe (title_lower)");
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("INDEX RANGE SCAN") && plan.contains("PROBE_STORED");
    }
}
