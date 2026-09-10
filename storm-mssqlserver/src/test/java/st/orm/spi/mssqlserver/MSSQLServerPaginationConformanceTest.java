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

import javax.sql.DataSource;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.tck.AbstractPaginationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the keyset scrolling conformance suite against MSSQLServer.
 */
@StormTest(scripts = "/data.sql")
public class MSSQLServerPaginationConformanceTest extends AbstractPaginationConformanceTest {
    private static MSSQLServerContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-latest")
                .acceptLicense();
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }
}
