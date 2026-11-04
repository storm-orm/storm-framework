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

import st.orm.core.template.TemplateString;

/**
 * The query template is used to construct queries that may be linked to the outer query.
 *
 * @since 1.1
 */
public interface Subqueryable {

    /**
     * Returns the template for the query for the subquery.
     *
     * @return the template.
     */
    TemplateString getSubquery();

    /**
     * Returns the template for the query as the subquery of an existence test. The test ignores the select list,
     * which the database does not always, so a query that selects a record's columns selects a constant instead; a
     * query with a select list of its own keeps it.
     *
     * @return the template for the subquery of an existence test.
     * @since 1.16
     */
    default TemplateString getExistsSubquery() {
        return getSubquery();
    }
}
