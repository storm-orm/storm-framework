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
package st.orm.repository.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.fold
import kotlinx.coroutines.flow.map
import st.orm.*
import st.orm.repository.ProjectionRepository
import st.orm.template.*
import st.orm.template.impl.ModelImpl
import st.orm.template.impl.ORMTemplateImpl
import st.orm.template.impl.QueryBuilderImpl
import kotlin.reflect.KClass

/**
 */
class ProjectionRepositoryImpl<P, ID : Any>(
    private val core: st.orm.core.repository.ProjectionRepository<P, ID>,
) : ProjectionRepository<P, ID> where P : Data, P : Projection<ID> {

    override val orm: ORMTemplate
        get() = ORMTemplateImpl(core.orm())

    override val model: Model<P, ID>
        get() = ModelImpl<P, ID>(core.model())

    override fun ref(id: ID): Ref<P> = core.ref(id)

    override fun ref(projection: P, id: ID): Ref<P> = core.ref(projection, id)

    override fun select(): QueryBuilder<P, P, ID> = QueryBuilderImpl(core.select())

    override fun selectCount(): QueryBuilder<P, Long, ID> = QueryBuilderImpl(core.selectCount())

    override fun <R : Any> select(selectType: KClass<R>): QueryBuilder<P, R, ID> = QueryBuilderImpl(core.select(selectType.java))

    override fun selectRef(): QueryBuilder<P, Ref<P>, ID> = QueryBuilderImpl<P, Ref<P>, ID>(core.selectRef())

    override fun <R : Any> select(
        selectType: KClass<R>,
        template: TemplateString,
    ): QueryBuilder<P, R, ID> = QueryBuilderImpl<P, R, ID>(
        core.select(selectType.java, template.unwrap),
    )

    override fun <R : Data> selectRef(refType: KClass<R>): QueryBuilder<P, Ref<R>, ID> = QueryBuilderImpl(core.selectRef(refType.java))

    override fun count(): Long = core.count()

    override fun exists(): Boolean = core.exists()

    override fun existsById(id: ID): Boolean = core.existsById(id)

    override fun existsByRef(ref: Ref<P>): Boolean = core.existsByRef(ref)

    override fun findById(id: ID): P? = core.findById(id).orElse(null)

    override fun findByRef(ref: Ref<P>): P? = core.findByRef(ref).orElse(null)

    override fun getById(id: ID): P = core.getById(id)

    override fun getByRef(ref: Ref<P>): P = core.getByRef(ref)

    override fun <V : Any> findBy(key: Metamodel.Key<P, V>, value: V): P? = core.findBy(key, value).orElse(null)

    override fun <V : Any> getBy(key: Metamodel.Key<P, V>, value: V): P = core.getBy(key, value)

    override fun <V : Data> findByRef(key: Metamodel.Key<P, V>, value: Ref<V>): P? = core.findByRef(key, value).orElse(null)

    override fun <V : Data> getByRef(key: Metamodel.Key<P, V>, value: Ref<V>): P = core.getByRef(key, value)

    override fun findAll(): List<P> = core.findAll()

    override fun findAllById(ids: Iterable<ID>): List<P> = core.findAllById(ids)

    override fun findAllByRef(refs: Iterable<Ref<P>>): List<P> = core.findAllByRef(refs)

    override suspend fun countById(ids: Flow<ID>): Long = ids.chunked(core.defaultChunkSize)
        .map { chunk -> core.countById(chunk.stream()) }
        .fold(0L) { acc, v -> acc + v }

    override suspend fun countById(ids: Flow<ID>, chunkSize: Int): Long = ids.chunked(chunkSize)
        .map { chunk -> core.countById(chunk.stream()) }
        .fold(0L) { acc, v -> acc + v }

    override suspend fun countByRef(refs: Flow<Ref<P>>): Long = refs.chunked(core.defaultChunkSize)
        .map { chunk -> core.countByRef(chunk.stream()) }
        .fold(0L) { acc, v -> acc + v }

    override suspend fun countByRef(refs: Flow<Ref<P>>, chunkSize: Int): Long = refs.chunked(chunkSize)
        .map { chunk -> core.countByRef(chunk.stream()) }
        .fold(0L) { acc, v -> acc + v }

    override fun page(pageNumber: Int, pageSize: Int): Page<P> = core.page(pageNumber, pageSize)

    override fun page(pageable: Pageable): Page<P> = core.page(pageable)

    override fun pageRef(pageNumber: Int, pageSize: Int): Page<Ref<P>> = core.pageRef(pageNumber, pageSize)

    override fun pageRef(pageable: Pageable): Page<Ref<P>> = core.pageRef(pageable)

    override fun scroll(scrollable: Scrollable<P>): Window<P> = select().scroll(scrollable)
}
