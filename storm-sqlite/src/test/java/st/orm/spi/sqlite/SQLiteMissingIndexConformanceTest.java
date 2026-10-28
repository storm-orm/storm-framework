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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import st.orm.tck.AbstractMissingIndexConformanceTest;
import st.orm.test.StormTest;

/** The index a missing-index advice names is the one SQLite uses. */
@StormTest(url = "jdbc:sqlite:target/conformance.db", rollback = false)
public class SQLiteMissingIndexConformanceTest extends AbstractMissingIndexConformanceTest {

    @Override
    protected void analyze(Statement statement, String table) throws SQLException {
        statement.execute("ANALYZE");
    }

    @Override
    protected String plan(Connection connection, String sql) throws SQLException {
        var plan = new StringBuilder();
        try (var statement = connection.createStatement();
             var resultSet = statement.executeQuery("EXPLAIN QUERY PLAN " + sql)) {
            int columns = resultSet.getMetaData().getColumnCount();
            while (resultSet.next()) {
                plan.append(resultSet.getString(columns)).append('\n');
            }
        }
        return plan.toString();
    }

    @Override
    protected java.util.List<String> suggestProbeDdl() {
        return java.util.List.of(
                "CREATE TABLE suggest_probe (id INTEGER PRIMARY KEY, title TEXT NOT NULL, title_lower TEXT GENERATED ALWAYS AS (lower(title)) STORED)",
                "CREATE INDEX probe_stored ON suggest_probe (title_lower COLLATE NOCASE)");
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("SEARCH") && plan.contains("probe_stored");
    }
}
