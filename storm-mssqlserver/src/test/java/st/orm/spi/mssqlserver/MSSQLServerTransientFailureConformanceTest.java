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

import static st.orm.TransactionPropagation.REQUIRED;

import com.microsoft.sqlserver.jdbc.SQLServerConnection;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DelegatingDataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.TransactionOptions;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against SQL Server, which serializes with locks, so the serialization
 * case ends in a deadlock. The suite commits, so it runs without the per-test rollback.
 */
@StormTest(rollback = false)
public class MSSQLServerTransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

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
     * The driver sends a statement as a batch of its own, and a {@code SET} in a batch ends with it, so the lock
     * timeout and the update it bounds go in one batch.
     */
    @Override
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("SET LOCK_TIMEOUT 1000; UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
    }

    /**
     * Under snapshot isolation, a transaction that writes a row another transaction changed after its snapshot fails
     * with error {@code 3960}, and SQL Server rolls it back.
     */
    @Test
    public void aSnapshotUpdateConflictIsTransient() throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            // Allows snapshot isolation where the database does not already; a system database is named, never
            // altered through CURRENT.
            statement.execute("""
                    IF (SELECT snapshot_isolation_state FROM sys.databases WHERE name = DB_NAME()) = 0
                    BEGIN
                        DECLARE @allow NVARCHAR(300) =
                            'ALTER DATABASE ' + QUOTENAME(DB_NAME()) + ' SET ALLOW_SNAPSHOT_ISOLATION ON';
                        EXEC (@allow);
                    END""");
        }
        assertOneSerializationFailure(new SnapshotDataSource(dataSource),
                new TransactionOptions(REQUIRED, null, 60, false));
    }

    /**
     * Hands out connections at snapshot isolation, a level {@code TransactionOptions} does not name. A
     * {@code SET TRANSACTION ISOLATION LEVEL} statement would not do: the driver sends each statement as a batch of
     * its own, and the level it sets ends with the batch.
     */
    private static final class SnapshotDataSource extends DelegatingDataSource {

        SnapshotDataSource(DataSource dataSource) {
            super(dataSource);
        }

        @Override
        public Connection getConnection() throws SQLException {
            var connection = super.getConnection();
            connection.setTransactionIsolation(SQLServerConnection.TRANSACTION_SNAPSHOT);
            return connection;
        }
    }
}
