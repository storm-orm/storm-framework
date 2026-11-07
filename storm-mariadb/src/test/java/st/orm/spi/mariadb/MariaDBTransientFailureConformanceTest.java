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

import static st.orm.TransactionIsolation.REPEATABLE_READ;
import static st.orm.TransactionPropagation.REQUIRED;

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MariaDBContainer;
import st.orm.TransactionOptions;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against MariaDB, which serializes with locks, so the serialization
 * case ends in a deadlock. The suite commits, so it runs without the per-test rollback.
 */
@StormTest(rollback = false)
public class MariaDBTransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

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

    @Override
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("SET SESSION innodb_lock_wait_timeout = 1",
                "UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
    }

    /**
     * Under repeatable read with {@code innodb_snapshot_isolation}, the default, a transaction that writes a row
     * another transaction changed after its snapshot fails with error {@code 1020}, and MariaDB rolls it back.
     */
    @Test
    public void aWriteConflictWithTheSnapshotIsTransient() throws Exception {
        assertOneSerializationFailure(dataSource, new TransactionOptions(REQUIRED, REPEATABLE_READ, 60, false));
    }

    /**
     * MariaDB serializes with locks: a serializable read takes a shared lock, so each write waits for the lock the
     * other transaction's read holds, and the database ends one of them at that statement as a deadlock.
     */
    @Override
    protected WriteSkewReport writeSkewReport() {
        return WriteSkewReport.STATEMENT;
    }
}
