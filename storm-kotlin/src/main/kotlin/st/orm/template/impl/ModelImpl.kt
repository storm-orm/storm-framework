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

import st.orm.Data
import st.orm.template.Column
import st.orm.template.Model
import kotlin.reflect.KClass

internal class ModelImpl<E : Data, ID : Any>(
    internal val core: st.orm.core.template.Model<E, ID>,
    override val columns: List<Column> = core.columns().map(::ColumnImpl),
    override val declaredColumns: List<Column> = core.declaredColumns().map(::ColumnImpl),
    override val queryColumns: List<Column> = core.queryColumns().map(::ColumnImpl),
) : Model<E, ID> {

    override val schema: String get() = core.schema()
    override val name: String get() = core.name()
    override val type: KClass<E> get() = core.type().kotlin
    override val primaryKeyType: KClass<ID> get() = core.primaryKeyType().kotlin
    override fun isDefaultPrimaryKey(pk: ID?): Boolean = core.isDefaultPrimaryKey(pk)
    override fun forEachValue(columns: List<Column>, record: E, consumer: (Column, Any?) -> Unit) {
        core.forEachValue(columns.map { c -> (c as ColumnImpl).core }, record, { c, v -> consumer(ColumnImpl(c), v) })
    }
}
