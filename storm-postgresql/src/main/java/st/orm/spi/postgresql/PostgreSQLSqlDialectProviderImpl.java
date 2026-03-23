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
package st.orm.spi.postgresql;

import jakarta.annotation.Nonnull;
import java.util.function.Predicate;
import st.orm.StormConfig;
import st.orm.core.spi.Provider;
import st.orm.core.spi.SqlDialectProvider;
import st.orm.core.template.SqlDialect;

/**
 * Implementation of {@link SqlDialectProvider} for PostgreSQL.
 */
public class PostgreSQLSqlDialectProviderImpl implements SqlDialectProvider {

    @Override
    public boolean supports(@Nonnull String databaseProductName) {
        return "PostgreSQL".equalsIgnoreCase(databaseProductName);
    }

    @Override
    public Predicate<Provider> getProviderFilter() {
        return PostgreSQLProviderFilter.INSTANCE;
    }

    @Override
    public SqlDialect getSqlDialect(@Nonnull StormConfig config) {
        return new PostgreSQLSqlDialect(config);
    }
}
