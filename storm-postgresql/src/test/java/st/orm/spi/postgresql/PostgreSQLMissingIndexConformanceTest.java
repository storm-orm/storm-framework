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
package st.orm.spi.postgresql;

import java.sql.SQLException;
import java.sql.Statement;
import javax.sql.DataSource;
import org.testcontainers.containers.PostgreSQLContainer;
import st.orm.tck.AbstractMissingIndexConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/** The index a missing-index advice names is the one PostgreSQL uses. */
@StormTest(rollback = false)
public class PostgreSQLMissingIndexConformanceTest extends AbstractMissingIndexConformanceTest {
    private static PostgreSQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:17");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected void analyze(Statement statement, String table) throws SQLException {
        statement.execute("ANALYZE " + table);
    }

    @Override
    protected void preferIndexes(Statement statement) throws SQLException {
        statement.execute("SET enable_seqscan = off");
    }

    @Override
    protected java.util.List<String> suggestProbeDdl() {
        return java.util.List.of(
                "CREATE TABLE suggest_probe (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL, title_lower VARCHAR(100) GENERATED ALWAYS AS (LOWER(title)) STORED)",
                "CREATE EXTENSION IF NOT EXISTS pg_trgm",
                "CREATE INDEX probe_trgm ON suggest_probe USING gin (LOWER(title_lower) gin_trgm_ops)");
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("Bitmap Index Scan on probe_trgm");
    }
}
