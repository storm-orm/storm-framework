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
package st.orm.spring.boot.autoconfigure

import st.orm.template.ORMTemplate
import javax.sql.DataSource

/**
 * Composes fully integrated [ORMTemplate]s for the applications that define their own template beans, which
 * backs the auto-configured single template off. The factory keeps the property-driven integration for every
 * template the application composes, one per data source, however many that is.
 *
 * Each created template carries the same Spring integration the auto-configured single template does, driven
 * by the same `storm.*` properties: the Spring-aware connection and transaction template providers, SQL
 * failure translation to Spring's `DataAccessException` hierarchy, query and transaction observations, and
 * trace-context SQL comments. The observations resolve their semantic conventions against the given data
 * source and, when a `database` name is given, carry it as the low-cardinality `storm.database` key value, so
 * the templates stay separable in dashboards.
 *
 * ```kotlin
 * @Bean
 * fun orderTemplate(factory: OrmTemplateFactory) = factory.create(orderDataSource, "orders")
 * ```
 *
 * The `customize` block of [create] runs after the integration is composed, so application-specific composition, such as
 * a table name resolver, applies on top without the application touching the integration SPI. A template that
 * needs a different integration altogether defines it in the block, overriding what the factory set.
 *
 * @since 1.14
 */
public interface OrmTemplateFactory {

    /**
     * Creates a fully integrated [ORMTemplate] for the given data source.
     *
     * @param dataSource the data source backing the template.
     * @param database the name reported as the `storm.database` observation key value, or `null` to report none.
     * @param customize additional composition applied after the integration, such as a table name resolver.
     * @return the composed template.
     */
    public fun create(
        dataSource: DataSource,
        database: String? = null,
        customize: ORMTemplate.Builder.() -> Unit = {},
    ): ORMTemplate
}
