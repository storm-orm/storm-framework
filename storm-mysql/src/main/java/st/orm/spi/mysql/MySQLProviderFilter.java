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
package st.orm.spi.mysql;


import java.util.function.Predicate;
import st.orm.core.spi.EntityRepositoryProvider;
import st.orm.core.spi.Provider;
import st.orm.core.spi.SqlDialectProvider;

/**
 * Provider filter to select the MySQL dialect and entity repository providers.
 */
public final class MySQLProviderFilter implements Predicate<Provider> {

    public static final MySQLProviderFilter INSTANCE = new MySQLProviderFilter();

    private MySQLProviderFilter() {
    }

    @Override
    public boolean test(Provider provider) {
        if (provider instanceof SqlDialectProvider) {
            return provider instanceof MySQLSqlDialectProviderImpl;
        }
        if (provider instanceof EntityRepositoryProvider) {
            return provider instanceof MySQLEntityRepositoryProviderImpl;
        }
        return true;
    }
}
