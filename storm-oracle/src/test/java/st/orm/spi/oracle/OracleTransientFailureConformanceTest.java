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
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against Oracle. Oracle rolls back the statement that met the
 * deadlock or the serialization failure, not the transaction; the transaction rolls back when the failure leaves
 * it, which the case observes in the rows. The suite commits, so it runs without the per-test rollback.
 */
@StormTest(rollback = false)
public class OracleTransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

    /**
     * ORA-01466, the error Oracle reports for a read from a snapshot older than the table it reads.
     */
    private static final int TABLE_DEFINITION_HAS_CHANGED = 1466;

    private static final long SNAPSHOT_TIMEOUT_NANOS = SECONDS.toNanos(30);
    private static final long SNAPSHOT_RETRY_MILLIS = 100;

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
     * A serializable transaction reads from one snapshot, and refuses a table whose definition changed in the second
     * that snapshot was taken with ORA-01466. The suite creates its table moments before each case, so a
     * serializable read is repeated here until its snapshot clears the table's creation.
     */
    @Override
    protected void awaitTable() throws SQLException, InterruptedException {
        long started = System.nanoTime();
        while (true) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
                    try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + TABLE)) {
                        resultSet.next();
                    }
                }
                connection.commit();
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
     * Oracle has no lock wait setting for a session; a row lock taken with a {@code WAIT} clause gives up after it,
     * with ORA-30006.
     */
    @Override
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("SELECT id FROM " + TABLE + " WHERE id = 1 FOR UPDATE WAIT 1");
    }
}
