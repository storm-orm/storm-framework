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

import static st.orm.Match.anywhere;
import static st.orm.Match.exact;
import static st.orm.Match.prefix;
import static st.orm.Match.wordStart;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
    protected List<String> searchProbeDdl() {
        return List.of(
                "CREATE TABLE search_probe (id INTEGER PRIMARY KEY, name TEXT NOT NULL, name_lower TEXT COLLATE NOCASE GENERATED ALWAYS AS (lower(name)) STORED)");
    }

    @Override
    protected List<SearchIndexProbe> searchIndexProbes() {
        return List.of(
                // An ordinary index over the stored lowered name, a column with the NOCASE collation, serves a prefix
                // and an exact match; a NOCASE index over a BINARY column serves the prefix alone.
                new SearchIndexProbe(prefix(PROBE_NAME_LOWER), "CREATE INDEX probe_stored ON search_probe (name_lower)", true),
                new SearchIndexProbe(exact(PROBE_NAME_LOWER), "CREATE INDEX probe_stored ON search_probe (name_lower)", true),
                // No index serves a match that starts with a wildcard: an index on the column cannot.
                new SearchIndexProbe(anywhere(PROBE_NAME), "CREATE INDEX probe_name ON search_probe (name)", false),
                new SearchIndexProbe(wordStart(PROBE_NAME), null, false));
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("SEARCH") && plan.contains("probe_");
    }

}
