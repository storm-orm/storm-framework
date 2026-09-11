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
package st.orm.spi.sqlite;

import st.orm.tck.AbstractTransactionConformanceTest;
import st.orm.test.StormTest;

/**
 * Runs the read-only transaction conformance suite against SQLite. The suite commits, so it runs without the
 * per-test rollback and removes the rows it inserts.
 */
@StormTest(url = "jdbc:sqlite:target/conformance.db", scripts = "/data.sql", rollback = false)
public class SQLiteTransactionConformanceTest extends AbstractTransactionConformanceTest {

    /**
     * The SQLite driver fixes the read-only mode when the connection is established and refuses to change it
     * afterwards, so no read-only transaction can open on a connection the pool hands out read-write.
     */
    @Override
    protected boolean supportsReadOnlyTransactions() {
        return false;
    }
}
