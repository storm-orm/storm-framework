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
package st.orm.spi.h2;

import java.sql.SQLException;
import java.sql.Statement;
import st.orm.tck.AbstractMissingIndexConformanceTest;
import st.orm.test.StormTest;

/** The index a missing-index advice names is the one H2 uses. */
@StormTest(rollback = false)
public class H2MissingIndexConformanceTest extends AbstractMissingIndexConformanceTest {

    @Override
    protected void analyze(Statement statement, String table) throws SQLException {
        statement.execute("ANALYZE");
    }

    @Override
    protected java.util.List<String> suggestProbeDdl() {
        return java.util.List.of(
                "CREATE TABLE suggest_probe (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL, title_lower VARCHAR(100) GENERATED ALWAYS AS (LOWER(title)))",
                "CREATE INDEX probe_stored ON suggest_probe (title_lower)");
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("PROBE_STORED:");
    }
}
