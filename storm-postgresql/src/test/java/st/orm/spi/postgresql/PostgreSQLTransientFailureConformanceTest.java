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

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.PostgreSQLContainer;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against PostgreSQL. The suite commits, so it runs without the
 * per-test rollback.
 */
@StormTest(rollback = false)
public class PostgreSQLTransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

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
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("SET LOCAL lock_timeout = '1s'", "UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
    }

    /**
     * PostgreSQL tracks the rows a serializable transaction read and detects the read/write dependency cycle when the
     * first of the two transactions commits: the commit of the second fails with a serialization failure.
     */
    @Override
    protected WriteSkewReport writeSkewReport() {
        return WriteSkewReport.COMMIT;
    }
}
