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

import java.util.Optional;
import javax.sql.DataSource;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractTransactionConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the read-only transaction conformance suite against Oracle. The suite commits, so it runs without the
 * per-test rollback and removes the rows it inserts.
 */
@StormTest(scripts = "/data.sql", rollback = false)
public class OracleTransactionConformanceTest extends AbstractTransactionConformanceTest {

    private static OracleContainer container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new OracleContainer("gvenzl/oracle-free:23");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * The Oracle driver keeps the flag to itself, so the dialect opens the transaction read-only on the server
     * itself; Oracle then reports a write as ORA-01456, under the SQL state it assigns to errors of the execute
     * phase rather than the standard's read-only state.
     */
    @Override
    protected String readOnlyViolationSqlState() {
        return "72000";
    }

    /**
     * {@code MERGE} carries no operation Storm recognises, so it reaches the server as the write the server
     * refuses.
     */
    @Override
    protected Optional<String> unrecognisedWriteStatement() {
        return Optional.of("""
                MERGE INTO vet USING (SELECT 1 AS one FROM dual) source ON (1 = 0)
                WHEN NOT MATCHED THEN INSERT (first_name, last_name) VALUES ('Read', '""" + INSERTED_LAST_NAME + "')");
    }
}
