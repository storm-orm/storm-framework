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
package com.example.scope

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import st.orm.repository.EntityRepository
import st.orm.template.model.PetOwnerRef

/**
 * A repository whose methods build a flow, one returning it as is and one mapping it before returning it, the way an application writes one: the
 * flow runs when the caller collects it, after the method has returned.
 */
internal interface PetOwnerRefs : EntityRepository<PetOwnerRef, Int> {

    fun all(): Flow<PetOwnerRef> = select().resultFlow

    fun allRenamed(): Flow<PetOwnerRef> = select().resultFlow.map { it.copy(name = it.name.uppercase()) }
}
