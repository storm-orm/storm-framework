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

import java.util.Optional;
import javax.sql.DataSource;
import org.testcontainers.containers.MariaDBContainer;
import st.orm.tck.AbstractTransactionConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the read-only transaction conformance suite against MariaDB. The suite commits, so it runs without the
 * per-test rollback and removes the rows it inserts.
 */
@StormTest(scripts = "/data.sql", rollback = false)
public class MariaDBTransactionConformanceTest extends AbstractTransactionConformanceTest {

    private static MariaDBContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MariaDBContainer<>("mariadb:11.8");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * {@code REPLACE} carries no operation Storm recognises, so it reaches the server as the write the server
     * refuses.
     */
    @Override
    protected Optional<String> unrecognisedWriteStatement() {
        return Optional.of("REPLACE INTO vet (first_name, last_name) VALUES ('Read', '" + INSERTED_LAST_NAME + "')");
    }
}
