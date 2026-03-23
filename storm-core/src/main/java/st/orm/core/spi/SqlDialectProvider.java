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
package st.orm.core.spi;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.util.function.Predicate;
import st.orm.StormConfig;
import st.orm.core.template.SqlDialect;

/**
 * Provides the SQL dialect.
 *
 * @since 1.1
 */
public interface SqlDialectProvider extends Provider {

    /**
     * Returns whether this dialect provider supports the given database product name, as returned by
     * {@link java.sql.DatabaseMetaData#getDatabaseProductName()}.
     *
     * <p>When multiple dialect modules are on the classpath, this method is used to select the appropriate dialect
     * based on the actual database being connected to. The default implementation returns {@code true}, which means
     * the provider matches any database. Dialect-specific implementations should override this method to match only
     * their target database.</p>
     *
     * @param databaseProductName the database product name.
     * @return {@code true} if this provider supports the given database.
     * @since 1.11
     */
    default boolean supports(@Nonnull String databaseProductName) {
        return true;
    }

    /**
     * Returns a provider filter that restricts entity repository selection to the implementation provided by this
     * dialect, or {@code null} if no filtering is needed.
     *
     * <p>When connection-aware dialect detection selects a specific dialect provider, this filter is used to ensure
     * the matching entity repository provider is also selected.</p>
     *
     * @return the provider filter, or {@code null}.
     * @since 1.11
     */
    default @Nullable Predicate<Provider> getProviderFilter() {
        return null;
    }

    /**
     * Returns the SQL dialect configured with the given {@link StormConfig}.
     *
     * @param config the Storm configuration to apply.
     * @return the SQL dialect.
     */
    SqlDialect getSqlDialect(@Nonnull StormConfig config);
}
