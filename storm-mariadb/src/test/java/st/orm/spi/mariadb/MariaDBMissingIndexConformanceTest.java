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

import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.testcontainers.containers.MariaDBContainer;
import st.orm.tck.AbstractMissingIndexConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/** The index a missing-index advice names is the one MariaDB uses. */
@StormTest(rollback = false)
public class MariaDBMissingIndexConformanceTest extends AbstractMissingIndexConformanceTest {
    private static MariaDBContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MariaDBContainer<>("mariadb:11.8");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected void analyze(Statement statement, String table) throws SQLException {
        statement.execute("ANALYZE TABLE " + table);
    }

    @Override
    protected java.util.List<String> suggestProbeDdl() {
        return java.util.List.of(
                "CREATE TABLE suggest_probe (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL, title_lower VARCHAR(100) AS (LOWER(title)) STORED)",
                "CREATE INDEX probe_stored ON suggest_probe (title_lower)");
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("range") && plan.contains("probe_stored");
    }
}
