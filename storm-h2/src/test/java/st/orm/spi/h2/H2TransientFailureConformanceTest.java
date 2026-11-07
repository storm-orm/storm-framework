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

import java.util.List;
import st.orm.tck.AbstractTransientFailureConformanceTest;
import st.orm.test.StormTest;

/**
 * Runs the transient failure conformance suite against H2. The suite commits, so it runs without the per-test
 * rollback.
 */
@StormTest(rollback = false)
public class H2TransientFailureConformanceTest extends AbstractTransientFailureConformanceTest {

    @Override
    protected List<String> lockWaitTimeoutStatements() {
        return List.of("SET LOCK_TIMEOUT 1000", "UPDATE " + TABLE + " SET amount = amount + 1 WHERE id = 1");
    }
}
