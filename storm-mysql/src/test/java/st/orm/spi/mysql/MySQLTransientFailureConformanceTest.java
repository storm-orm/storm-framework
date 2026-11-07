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
package st.orm.spi.mysql;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MySQLContainer;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against MySQL, which serializes with locks, so the serialization
 * case ends in a deadlock. The suite commits, so it runs without the per-test rollback.
 */
@StormTest(rollback = false)
public class MySQLTransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

    private static MySQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:9.2");
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
     * The MySQL driver reports the lock wait timeout under SQL state {@code 40001}, the state of a serialization
     * failure, while MySQL rolls back only the statement that waited: the case guards the dialect's reading of the
     * error code.
     */
    @Override
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("SET SESSION innodb_lock_wait_timeout = 1",
                "UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
    }
}
