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
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractReservedWordConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(rollback = false)
public class OracleReservedWordConformanceTest extends AbstractReservedWordConformanceTest {
    private static OracleContainer container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new OracleContainer("gvenzl/oracle-free:23");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Stops the database when the class is done: each Oracle test class starts its own, and one left running slows
     * every start after it toward the startup timeout.
     */
    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /**
     * The keywords {@code V$RESERVED_WORDS} marks as reserved or semi-reserved, the ones Oracle refuses as an
     * identifier. The view is readable by {@code SYSTEM}, whose password the container sets to the application
     * user's.
     */
    @Override
    protected Optional<Set<String>> reservedWords() throws SQLException {
        try (Connection connection = DriverManager.getConnection(
                container.getJdbcUrl(), "system", container.getPassword())) {
            return Optional.of(queryWords(connection,
                    "SELECT keyword FROM v$reserved_words WHERE reserved = 'Y' OR res_semi = 'Y'"));
        }
    }

    /**
     * Oracle folds an unquoted name to upper case, so a word quoted needlessly no longer matches a column created
     * without quotes.
     */
    @Override
    protected Optional<Set<String>> quotedWords() {
        return Optional.of(OracleSqlDialect.RESERVED_WORDS);
    }
}
