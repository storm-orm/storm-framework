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
package st.orm.core.template.impl;

import st.orm.core.spi.QueryFactory;
import st.orm.core.template.Query;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.TemplateString;

/**
 * A query factory that takes the facts a builder asserts about a statement beside its template.
 *
 * @since 1.16
 */
interface FactsQueryFactory extends QueryFactory {

    /**
     * Creates a query for the template, compiled with the facts applied.
     *
     * @param template the template.
     * @param facts what the builder asserts about the statement.
     * @return the query.
     */
    Query create(TemplateString template, StatementFacts facts);

    /**
     * Compiles the template into a plan, with the facts applied.
     *
     * @param template the template.
     * @param facts what the builder asserts about the statement.
     * @return the plan.
     */
    default QueryPlan plan(TemplateString template, StatementFacts facts) {
        return plan(template);
    }
}
