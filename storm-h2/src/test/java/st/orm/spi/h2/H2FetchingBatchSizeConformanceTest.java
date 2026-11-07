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
import st.orm.tck.AbstractFetchingBatchSizeConformanceTest;
import st.orm.test.StormTest;

@StormTest(rollback = false)
public class H2FetchingBatchSizeConformanceTest extends AbstractFetchingBatchSizeConformanceTest {

    @Override
    protected List<String> schemaDdl() {
        return List.of(
                "CREATE TABLE batch_item (id INTEGER AUTO_INCREMENT PRIMARY KEY, name VARCHAR(50) NOT NULL)");
    }

    @Override
    protected List<String> dropDdl() {
        return List.of(
                "DROP TABLE batch_item");
    }

    @Override
    protected boolean supportsFetchWithSequences() {
        return false;
    }
}
