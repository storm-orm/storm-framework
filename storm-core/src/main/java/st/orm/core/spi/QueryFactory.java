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

import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import st.orm.BindVars;
import st.orm.PersistenceException;
import st.orm.core.template.Query;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.SqlTemplate;
import st.orm.core.template.TemplateString;
import st.orm.spi.ExceptionMapper;

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
    Query create(TemplateString template);

    /**
     * Compiles the specified query {@code template} into a reusable plan.
     *
     * <p>The template's variable parts must be expressed as bind variables; templates without any parameters compile
     * to constant plans, and templates with fixed parameter values are rejected. The default implementation does not
     * support plans and throws; factories that process templates ahead of execution override this method.</p>
     *
     * @param template the template to compile.
     * @return a reusable plan for the template.
     * @throws PersistenceException if this factory does not support plans, the template is invalid, or it carries
     *                              fixed parameter values.
     * @since 1.13
     */
    default QueryPlan plan(TemplateString template) {
        throw new PersistenceException("Query plans are not supported by %s.".formatted(getClass().getSimpleName()));
    }

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

    /**
     * Returns the transaction template provider used by this factory.
     *
     * <p>The default implementation resolves the fallback provider via {@code ServiceLoader} discovery;
     * implementations that carry instance-scoped integration strategies return their configured provider.</p>
     *
     * @return the transaction template provider.
     * @since 1.13
     */
    default TransactionTemplateProvider transactionTemplateProvider() {
        return Providers.getTransactionTemplateProvider();
    }

    /**
     * Returns the exception mapper used by this factory.
     *
     * <p>The default implementation returns the default mapper; implementations that carry instance-scoped
     * integration strategies return their configured mapper.</p>
     *
     * @return the exception mapper.
     * @since 1.16
     */
    default ExceptionMapper exceptionMapper() {
        return ExceptionMapper.defaultMapper();
    }
}
