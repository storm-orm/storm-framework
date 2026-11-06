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

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MariaDBContainer;
import st.orm.tck.AbstractReservedWordConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(rollback = false)
public class MariaDBReservedWordConformanceTest extends AbstractReservedWordConformanceTest {
    private static MariaDBContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MariaDBContainer<>("mariadb:11.8");
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

    /**
     * The keywords in {@code information_schema.KEYWORDS} that MariaDB refuses where Storm renders a name. MariaDB
     * lists its keywords without marking the reserved ones, so each is tried.
     */
    @Override
    protected Optional<Set<String>> reservedWords() throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            return Optional.of(refusedAsName(connection,
                    queryWords(connection, "SELECT word FROM information_schema.keywords")));
        }
    }
}
