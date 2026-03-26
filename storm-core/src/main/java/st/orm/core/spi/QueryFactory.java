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
import javax.sql.DataSource;
import st.orm.BindVars;
import st.orm.PersistenceException;
import st.orm.core.template.Query;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.TemplateString;

/**
 * Factory for creating queries.
 */
public interface QueryFactory {

    /**
     * Get the SQL template used by this factory.
     *
     * <p>Query factory implementations must ensure that the SQL Template returned by this method is processed by any
     * registered {@code SqlInterceptor} instances before being returned. As a result, this method is expected to
     * return a new instance of the SQL template each time it is called, ensuring that any modifications made by
     * interceptors are applied correctly.</p>
     *
     * @return the SQL template.
     * @since 1.3
     */
    SqlTemplate sqlTemplate();

    /**
     * Create a new bind variables instance that can be used to add bind variables to a batch.
     *
     * @return a new bind variables instance.
     */
    BindVars createBindVars();

    /**
     * Create a new query for the specified {@code template}.
     *
     * @param template the template to process.
     * @return a query that can be executed.
     * @throws PersistenceException if the template is invalid.
     */
    Query create(@Nonnull TemplateString template);

    /**
     * Returns the {@link DataSource} backing this factory, or {@code null} if the factory was created from a raw
     * {@link java.sql.Connection} or JPA {@code EntityManager}.
     *
     * @return the data source, or {@code null}.
     * @since 1.9
     */
    default @Nullable DataSource dataSource() {
        return null;
    }
}
