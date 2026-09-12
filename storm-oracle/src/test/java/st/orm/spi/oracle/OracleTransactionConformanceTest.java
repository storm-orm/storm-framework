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

import static java.util.concurrent.TimeUnit.SECONDS;

import java.sql.SQLException;
import java.util.Optional;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
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

    /**
     * ORA-01466, the error Oracle reports for a read from a snapshot older than the table it reads.
     */
    private static final int TABLE_DEFINITION_HAS_CHANGED = 1466;

    private static final long SNAPSHOT_TIMEOUT_NANOS = SECONDS.toNanos(30);
    private static final long SNAPSHOT_RETRY_MILLIS = 100;

    private static OracleContainer container;
    private static boolean snapshotClearsTheSchema;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new OracleContainer("gvenzl/oracle-free:23");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Oracle's read-only transaction reads the whole transaction from one snapshot, and refuses a table whose
     * definition changed in the second that snapshot was taken with ORA-01466. The suite's schema is created
     * moments before its first test runs, which puts the first read-only reads inside that window, so the read is
     * repeated here until the snapshot clears the schema's creation. It settles within a second or two, and once
     * it has, no further DDL follows.
     */
    @BeforeEach
    void awaitASnapshotThatClearsTheSchema() throws SQLException, InterruptedException {
        if (snapshotClearsTheSchema) {
            return;
        }
        long started = System.nanoTime();
        while (true) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION READ ONLY");
                    try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM vet")) {
                        resultSet.next();
                    }
                }
                connection.commit();
                snapshotClearsTheSchema = true;
                return;
            } catch (SQLException e) {
                if (e.getErrorCode() != TABLE_DEFINITION_HAS_CHANGED
                        || System.nanoTime() - started > SNAPSHOT_TIMEOUT_NANOS) {
                    throw e;
                }
            }
            Thread.sleep(SNAPSHOT_RETRY_MILLIS);
        }
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
