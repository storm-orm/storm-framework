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
package st.orm.spi.mssqlserver;

import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.tck.AbstractTransactionConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the read-only transaction conformance suite against SQL Server. The suite commits, so it runs without the
 * per-test rollback and removes the rows it inserts.
 */
@StormTest(scripts = "/data.sql", rollback = false)
public class MSSQLServerTransactionConformanceTest extends AbstractTransactionConformanceTest {

    private static MSSQLServerContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-latest")
                .acceptLicense();
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
     * The SQL Server driver accepts the flag and does nothing with it, as the JDBC specification allows, so the
     * server executes a write in a read-only transaction.
     */
    @Override
    protected boolean enforcesReadOnly() {
        return false;
    }

    /**
     * A count over a recursive sequence of ten billion rows, which runs until it is stopped; a cross join of the
     * catalog with itself completes at once, as the count needs no rows of it.
     */
    @Override
    protected Optional<String> slowStatement() {
        return Optional.of("WITH sequence(n) AS (SELECT CAST(1 AS BIGINT) UNION ALL SELECT n + 1 FROM sequence WHERE n < 10000000000) SELECT COUNT_BIG(*) FROM sequence OPTION (MAXRECURSION 0)");
    }
}
