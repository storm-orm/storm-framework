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

import static st.orm.Match.anywhere;
import static st.orm.Match.exact;
import static st.orm.Match.prefix;
import static st.orm.Match.wordStart;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
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

    /**
     * Stops the database when the class is done: each test class starts its own, and one left running holds its
     * memory until the module's tests end.
     */
    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
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
    protected List<String> searchProbeDdl() {
        return List.of(
                "CREATE TABLE search_probe (id INT PRIMARY KEY, name VARCHAR(100) NOT NULL, name_lower VARCHAR(100) GENERATED ALWAYS AS (LOWER(name)) STORED)",
                "CREATE EXTENSION IF NOT EXISTS pg_trgm");
    }

    @Override
    protected List<SearchIndexProbe> searchIndexProbes() {
        return List.of(
                // A trigram index over the lowered name serves a match anywhere and at a word start.
                new SearchIndexProbe(anywhere(PROBE_NAME), "CREATE INDEX probe_trgm ON search_probe USING gin (LOWER(name) gin_trgm_ops)", true),
                new SearchIndexProbe(wordStart(PROBE_NAME), "CREATE INDEX probe_trgm ON search_probe USING gin (LOWER(name) gin_trgm_ops)", true),
                // A B-tree serves a prefix with the pattern operator class, and an equality with the default one.
                new SearchIndexProbe(prefix(PROBE_NAME_LOWER), "CREATE INDEX probe_pattern ON search_probe (name_lower text_pattern_ops)", true),
                new SearchIndexProbe(exact(PROBE_NAME_LOWER), "CREATE INDEX probe_plain ON search_probe (name_lower)", true),
                // An index on the bare name serves no match of the lowered name.
                new SearchIndexProbe(wordStart(PROBE_NAME), "CREATE INDEX probe_name ON search_probe (name)", false),
                new SearchIndexProbe(anywhere(PROBE_NAME), null, false));
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("Index Scan using probe_") || plan.contains("Bitmap Index Scan on probe_")
                || plan.contains("Index Only Scan using probe_");
    }

}
