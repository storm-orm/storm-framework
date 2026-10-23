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
import st.orm.GenerationStrategy
import st.orm.Metamodel
import st.orm.template.Column
import kotlin.reflect.KClass

internal class ColumnImpl(
    internal val core: st.orm.core.template.Column,
) : Column {
    override val index: Int get() = core.index()
    override val name: String get() = core.name()
    override val type: KClass<*> get() = core.type().kotlin
    override val primaryKey: Boolean get() = core.primaryKey()
    override val generation: GenerationStrategy get() = core.generation()
    override val sequence: String? get() = core.sequence()?.ifEmpty { null }
    override val foreignKey: Boolean get() = core.foreignKey()
    override val nullable: Boolean get() = core.nullable()
    override val insertable: Boolean get() = core.insertable()
    override val updatable: Boolean get() = core.updatable()
    override val version: Boolean get() = core.version()
    override val ref: Boolean get() = core.ref()
    override val function: String? get() = core.function().ifEmpty { null }
    override val metamodel: Metamodel<Data, *> get() = core.metamodel()
}
