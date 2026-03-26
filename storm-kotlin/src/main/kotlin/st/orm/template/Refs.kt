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
import st.orm.Entity
import st.orm.Ref

/**
 * Creates a [Ref] for the entity.
 *
 * Usage:
 * ```kotlin
 * val myEntityRef = myEntity.ref()
 * ```
 *
 * Requires `import st.orm.template.ref`.
 */
fun <E : Entity<*>> E.ref(): Ref<E> = Ref.of(this)

/**
 * Creates a [Ref] from a type and primary key value, without needing an entity instance.
 *
 * Usage:
 * ```kotlin
 * val myEntityRef = refById<MyEntity>(id)
 * ```
 *
 * Requires `import st.orm.template.refById`.
 */
inline fun <reified T : Data> refById(id: Any): Ref<T> = Ref.of(T::class.java, id)
