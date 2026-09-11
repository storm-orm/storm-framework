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
package st.orm.spring;

import java.util.Optional;
import st.orm.StormConfig;
import st.orm.core.spi.DefaultSqlDialect;
import st.orm.core.spi.Orderable;
import st.orm.core.spi.SqlDialectProvider;
import st.orm.core.template.SqlDialect;

/**
 * The test dialect of this module: the default dialect with a read-only transaction statement H2 accepts, a user
 * variable assignment, so a test can observe that the statement was sent and where.
 */
@Orderable.BeforeAny
public class ReadOnlyStatementSqlDialectProviderImpl implements SqlDialectProvider {

    public static final String READ_ONLY_TRANSACTION_STATEMENT = "SET @STORM_READ_ONLY = 1";

    @Override
    public SqlDialect getSqlDialect(StormConfig config) {
        return new DefaultSqlDialect(config) {
            @Override
            public String name() {
                return "ReadOnlyStatementTest";
            }

            @Override
            public Optional<String> readOnlyTransactionStatement() {
                return Optional.of(READ_ONLY_TRANSACTION_STATEMENT);
            }
        };
    }
}
