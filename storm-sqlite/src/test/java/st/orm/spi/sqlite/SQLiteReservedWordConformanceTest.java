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

import java.util.Optional;
import java.util.Set;
import st.orm.tck.AbstractReservedWordConformanceTest;
import st.orm.test.StormTest;

@StormTest(url = "jdbc:sqlite:target/conformance.db", rollback = false)
public class SQLiteReservedWordConformanceTest extends AbstractReservedWordConformanceTest {

    /**
     * SQLite lists its keywords through its C interface only, which JDBC does not reach; the dialect quotes the ones
     * SQLite documents.
     */
    @Override
    protected Optional<Set<String>> reservedWords() {
        return Optional.empty();
    }
}
