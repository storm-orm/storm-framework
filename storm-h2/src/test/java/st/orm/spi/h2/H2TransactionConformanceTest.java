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
package st.orm.spi.h2;

import st.orm.tck.AbstractTransactionConformanceTest;
import st.orm.test.StormTest;

/**
 * Runs the read-only transaction conformance suite against H2. The suite commits, so it runs without the per-test
 * rollback and removes the rows it inserts.
 */
@StormTest(scripts = "/data.sql", rollback = false)
public class H2TransactionConformanceTest extends AbstractTransactionConformanceTest {

    /**
     * The H2 driver accepts the flag and does nothing with it, as the JDBC specification allows, so the database
     * executes a write in a read-only transaction.
     */
    @Override
    protected boolean enforcesReadOnly() {
        return false;
    }
}
