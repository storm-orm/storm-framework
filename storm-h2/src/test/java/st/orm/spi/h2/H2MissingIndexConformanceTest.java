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

import static st.orm.Match.anywhere;
import static st.orm.Match.exact;
import static st.orm.Match.prefix;
import static st.orm.Match.wordStart;

import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
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
    protected List<String> searchProbeDdl() {
        return List.of(
                "CREATE TABLE search_probe (id INT PRIMARY KEY, title VARCHAR(100) NOT NULL, title_lower VARCHAR(100) GENERATED ALWAYS AS (LOWER(title)))");
    }

    @Override
    protected List<SearchIndexProbe> searchIndexProbes() {
        return List.of(
                // An ordinary index over the stored lowered title serves a prefix and an exact match.
                new SearchIndexProbe(prefix(PROBE_TITLE_LOWER), "CREATE INDEX probe_stored ON search_probe (title_lower)", "probe_stored", true),
                new SearchIndexProbe(exact(PROBE_TITLE_LOWER), "CREATE INDEX probe_stored ON search_probe (title_lower)", "probe_stored", true),
                // No index of H2 serves a match that starts with a wildcard: an index on the column cannot.
                new SearchIndexProbe(anywhere(PROBE_TITLE), "CREATE INDEX probe_title ON search_probe (title)", "probe_title", false),
                new SearchIndexProbe(wordStart(PROBE_TITLE), null, null, false),
                new SearchIndexProbe(prefix(PROBE_TITLE_LOWER), null, null, false));
    }

    @Override
    protected boolean servedByIndex(String plan) {
        return plan.contains("PROBE_") && !plan.contains("tableScan");
    }

}
