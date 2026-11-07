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

import java.util.List;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.core.template.SqlDialect;
import st.orm.tck.AbstractFetchingBatchSizeConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(rollback = false)
public class MSSQLServerFetchingBatchSizeConformanceTest extends AbstractFetchingBatchSizeConformanceTest {

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

    @Override
    protected List<String> schemaDdl() {
        return List.of(
                "CREATE TABLE batch_item (id INT IDENTITY(1,1) PRIMARY KEY, name VARCHAR(50) NOT NULL)",
                "CREATE SEQUENCE batch_item_seq START WITH 1 INCREMENT BY 1",
                "CREATE TABLE batch_sequence_item (id INT PRIMARY KEY DEFAULT NEXT VALUE FOR batch_item_seq, name VARCHAR(50) NOT NULL)");
    }

    @Override
    protected List<String> dropDdl() {
        return List.of(
                "DROP TABLE batch_item",
                "DROP TABLE batch_sequence_item",
                "DROP SEQUENCE batch_item_seq");
    }

    @Override
    protected boolean supportsBatchUpsertAndFetchWithSequences() {
        return false;
    }

    @Override
    protected boolean supportsBatchUpsertAndFetchWithDefaultSequences() {
        return true;
    }

    /** SQL Server takes at most 1000 rows in the {@code VALUES} list of an insert. */
    @Override
    protected int statementRowLimit(SqlDialect dialect, int boundColumns) {
        return Math.min(1000, super.statementRowLimit(dialect, boundColumns));
    }
}
