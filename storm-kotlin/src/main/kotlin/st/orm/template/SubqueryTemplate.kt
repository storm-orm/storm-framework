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
package st.orm.template

import st.orm.Data
import st.orm.SelectMode.DECLARED
import st.orm.template.Templates.select
import kotlin.reflect.KClass

/**
 * Provides factory methods for constructing subqueries that can be correlated with or embedded in an outer query.
 *
 * Unlike regular queries created by [QueryTemplate], subqueries only select fields from the primary table; fields
 * from nested (foreign key) records are not included. Subqueries cannot be directly executed -- they must be passed
 * as a [QueryBuilder] object to the outer query (e.g., via [WhereBuilder.exists] or [Templates.subquery]).
 *
 * @since 1.1
 * @see QueryTemplate
 * @see WhereBuilder.exists
 */
@SqlDsl
interface SubqueryTemplate {
    /**
     * Create a subquery for the given table.
     *
     * @param fromType the table to create the subquery for.
     * @param <T> the table type to select from.
     * @return the subquery builder.
     */
    fun <T : Data> subquery(fromType: KClass<T>): QueryBuilder<T, *, *> = subquery(fromType, fromType)

    /**
     * Create a subquery for the given table and select type.
     *
     * @param fromType the table to create the subquery for.
     * @param selectType the type to select.
     * @return the subquery builder.
     * @param <T> the table type to select from.
     * @param <R> the result type.
     */
    fun <T : Data, R : Data> subquery(fromType: KClass<T>, selectType: KClass<R>): QueryBuilder<T, *, *> = subquery(fromType) { t(select(selectType, DECLARED)) }

    /**
     * Create a subquery for the given table and select type using the given `template`.
     *
     * @param fromType the table to create the subquery for.
     * @param template the select clause template.
     * @return the subquery builder.
     * @param <T> the table type to select from.
     */
    fun <T : Data> subquery(fromType: KClass<T>, template: TemplateBuilder): QueryBuilder<T, *, *> = subquery(fromType, template.build())

    /**
     * Create a subquery for the given table and select type using the given `template`.
     *
     * @param fromType the table to create the subquery for.
     * @param template the select clause template.
     * @return the subquery builder.
     * @param <T> the table type to select from.
     */
    fun <T : Data> subquery(fromType: KClass<T>, template: TemplateString): QueryBuilder<T, *, *>
}
