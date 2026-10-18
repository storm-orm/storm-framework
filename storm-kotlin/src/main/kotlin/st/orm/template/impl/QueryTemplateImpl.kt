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
package st.orm.template.impl

import st.orm.BindVars
import st.orm.Data
import st.orm.Ref
import st.orm.template.*
import kotlin.reflect.KClass

internal open class QueryTemplateImpl(private val core: st.orm.core.template.QueryTemplate) : QueryTemplate {

    override fun createBindVars(): BindVars = core.createBindVars()

    override fun <T : Data, ID : Any> ref(type: KClass<T>, id: ID): Ref<T> = core.ref<T, ID>(type.java, id)

    override fun <T : Data, ID : Any> ref(record: T, id: ID): Ref<T> = core.ref<T, ID>(record, id)

    override fun <T : Data> model(type: KClass<T>): Model<T, *> = ModelImpl(core.model(type.java))

    override fun <T : Data> model(type: KClass<T>, requirePrimaryKey: Boolean): Model<T, *> = ModelImpl(core.model(type.java, requirePrimaryKey))

    override fun <T : Data, R : Any> selectFrom(
        fromType: KClass<T>,
        selectType: KClass<R>,
        template: TemplateString,
    ): QueryBuilder<T, R, *> = QueryBuilderImpl(core.selectFrom(fromType.java, selectType.java, template.unwrap))

    override fun <T : Data> deleteFrom(fromType: KClass<T>): QueryBuilder<T, *, *> = QueryBuilderImpl(core.deleteFrom<T>(fromType.java))

    override fun <T : Data> updateFrom(fromType: KClass<T>): QueryBuilder<T, *, *> = QueryBuilderImpl(core.updateFrom<T>(fromType.java))

    override fun query(query: String): Query = QueryImpl(core.query(query))

    override fun query(template: TemplateString): Query = QueryImpl(core.query(template.unwrap))

    override fun <T : Data> subquery(fromType: KClass<T>): QueryBuilder<T, *, *> = QueryBuilderImpl(core.subquery(fromType.java))

    override fun <T : Data, R : Data> subquery(
        fromType: KClass<T>,
        selectType: KClass<R>,
    ): QueryBuilder<T, *, *> = QueryBuilderImpl(core.subquery(fromType.java, selectType.java))

    override fun <T : Data> subquery(fromType: KClass<T>, template: TemplateString): QueryBuilder<T, *, *> = QueryBuilderImpl(core.subquery<T>(fromType.java, template.unwrap))
}
