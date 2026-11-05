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

import static st.orm.Match.anywhere;
import static st.orm.Match.exact;
import static st.orm.Match.prefix;
import static st.orm.Match.wordStart;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.tck.AbstractMissingIndexConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/** The index a missing-index advice names is the one SQL Server uses. */
@StormTest(rollback = false)
public class MSSQLServerMissingIndexConformanceTest extends AbstractMissingIndexConformanceTest {
    private static MSSQLServerContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-latest").acceptLicense();
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected void analyze(Statement statement, String table) throws SQLException {
        statement.execute("UPDATE STATISTICS " + table);
    }

    @Override
    protected String plan(Connection connection, String sql) throws SQLException {
        var plan = new StringBuilder();
        try (var statement = connection.createStatement()) {
            statement.execute("SET SHOWPLAN_TEXT ON");
            try {
                boolean results = statement.execute(sql);
                while (results || statement.getUpdateCount() != -1) {
                    if (results) {
                        try (var resultSet = statement.getResultSet()) {
                            while (resultSet.next()) {
                                plan.append(resultSet.getString(1)).append('\n');
                            }
                        }
                    }
                    results = statement.getMoreResults();
                }
            } finally {
                statement.execute("SET SHOWPLAN_TEXT OFF");
            }
        }
        return plan.toString();
    }

    @Override
    protected List<String> searchProbeDdl() {
        return List.of(
                "CREATE TABLE search_probe (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, name_lower AS LOWER(name) PERSISTED)");
    }

    @Override
    protected List<SearchIndexProbe> searchIndexProbes() {
        return List.of(
                // An ordinary index over the persisted lowered name serves a prefix and an exact match.
                new SearchIndexProbe(prefix(PROBE_NAME_LOWER), "CREATE INDEX probe_stored ON search_probe (name_lower)", true),
                new SearchIndexProbe(exact(PROBE_NAME_LOWER), "CREATE INDEX probe_stored ON search_probe (name_lower)", true),
                // No index serves a match that starts with a wildcard: an index on the column cannot.
                new SearchIndexProbe(anywhere(PROBE_NAME), "CREATE INDEX probe_name ON search_probe (name)", false),
                new SearchIndexProbe(wordStart(PROBE_NAME), null, false));
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("Index Seek") && plan.contains("probe_");
    }

}
