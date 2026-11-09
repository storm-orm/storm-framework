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
package st.orm.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.test.TestDatabase.POSTGRESQL;

import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a script holding dollar-quoted strings runs on a database that reads them.
 */
@StormTest(database = POSTGRESQL, scripts = "/container-dollar-quote.sql")
class StormExtensionDollarQuoteScriptTest {

    @Test
    void blockAndFunctionBodyShouldRunAsOneStatementEach(DataSource dataSource) throws Exception {
        try (var connection = dataSource.getConnection();
             var statement = connection.createStatement();
             var rows = statement.executeQuery("select item_count()")) {
            assertTrue(rows.next());
            assertEquals(2, rows.getLong(1));
        }
    }
}
