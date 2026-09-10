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
package st.orm.repository.impl;

import static java.util.Objects.requireNonNull;
import static st.orm.template.impl.StringTemplates.convert;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.Ref;
import st.orm.Slice;
import st.orm.repository.EntityRepository;
import st.orm.template.Model;
import st.orm.template.ORMTemplate;
import st.orm.template.QueryBuilder;
import st.orm.template.impl.ModelImpl;
import st.orm.template.impl.ORMTemplateImpl;
import st.orm.template.impl.QueryBuilderImpl;

/**
 */
public final class EntityRepositoryImpl<E extends Entity<ID>, ID> implements EntityRepository<E, ID> {
    private final st.orm.core.repository.EntityRepository<E, ID> core;

    public EntityRepositoryImpl(st.orm.core.repository.EntityRepository<E, ID> core) {
        this.core = requireNonNull(core);
    }

    @Override
    public Model<E, ID> model() {
        return new ModelImpl<>((st.orm.core.template.impl.ModelImpl<E, ID>) core.model());
    }

    @Override
    public Ref<E> ref(ID id) {
        return core.ref(id);
    }

    @Override
    public Ref<E> ref(E entity) {
        return core.ref(entity);
    }

    @Override
    public Ref<E> unload(E entity) {
        return core.unload(entity);
    }

    @Override
    public QueryBuilder<E, E, ID> select() {
        return new QueryBuilderImpl<>(core.select());
    }

    @Override
    public QueryBuilder<E, Long, ID> selectCount() {
        return new QueryBuilderImpl<>(core.selectCount());
    }

    @Override
    public <R> QueryBuilder<E, R, ID> select(Class<R> selectType) {
        return new QueryBuilderImpl<>(core.select(selectType));
    }

    @Override
    public QueryBuilder<E, Ref<E>, ID> selectRef() {
        return new QueryBuilderImpl<>(core.selectRef());
    }

    @Override
    public <R> QueryBuilder<E, R, ID> select(Class<R> selectType, StringTemplate template) {
        return new QueryBuilderImpl<>(core.select(selectType, convert(template)));
    }

    @Override
    public <R extends Data> QueryBuilder<E, Ref<R>, ID> selectRef(Class<R> refType) {
        return new QueryBuilderImpl<>(core.selectRef(refType));
    }

    @Override
    public QueryBuilder<E, ?, ID> delete() {
        return new QueryBuilderImpl<>(core.delete());
    }

    @Override
    public long count() {
        return core.count();
    }

    @Override
    public boolean exists() {
        return core.exists();
    }

    @Override
    public boolean existsById(ID id) {
        return core.existsById(id);
    }

    @Override
    public boolean existsByRef(Ref<E> ref) {
        return core.existsByRef(ref);
    }

    @Override
    public void insert(E entity) {
        core.insert(entity);
    }

    @Override
    public void insert(E entity, boolean ignoreAutoGenerate) {
        core.insert(entity, ignoreAutoGenerate);
    }

    @Override
    public ID insertAndFetchId(E entity) {
        return core.insertAndFetchId(entity);
    }

    @Override
    public E insertAndFetch(E entity) {
        return core.insertAndFetch(entity);
    }

    @Override
    public void update(E entity) {
        core.update(entity);
    }

    @Override
    public E updateAndFetch(E entity) {
        return core.updateAndFetch(entity);
    }

    @Override
    public void upsert(E entity) {
        core.upsert(entity);
    }

    @Override
    public ID upsertAndFetchId(E entity) {
        return core.upsertAndFetchId(entity);
    }

    @Override
    public E upsertAndFetch(E entity) {
        return core.upsertAndFetch(entity);
    }

    @Override
    public void remove(E entity) {
        core.remove(entity);
    }

    @Override
    public void removeById(ID id) {
        core.removeById(id);
    }

    @Override
    public void removeByRef(Ref<E> ref) {
        core.removeByRef(ref);
    }

    @Override
    public void removeAll() {
        core.removeAll();
    }

    @Override
    public Optional<E> findById(ID id) {
        return core.findById(id);
    }

    @Override
    public Optional<E> findByRef(Ref<E> ref) {
        return core.findByRef(ref);
    }

    @Override
    public E getById(ID id) {
        return core.getById(id);
    }

    @Override
    public E getByRef(Ref<E> ref) {
        return core.getByRef(ref);
    }

    @Override
    public <V> Optional<E> findBy(Metamodel.Key<E, V> key, V value) {
        return core.findBy(key, value);
    }

    @Override
    public <V> E getBy(Metamodel.Key<E, V> key, V value) {
        return core.getBy(key, value);
    }

    @Override
    public <V extends Data> Optional<E> findByRef(Metamodel.Key<E, V> key, Ref<V> value) {
        return core.findByRef(key, value);
    }

    @Override
    public <V extends Data> E getByRef(Metamodel.Key<E, V> key, Ref<V> value) {
        return core.getByRef(key, value);
    }

    // Page methods.

    @Override
    public Page<E> page(int pageNumber, int pageSize) {
        return core.page(pageNumber, pageSize);
    }

    @Override
    public Page<E> page(Pageable pageable) {
        return core.page(pageable);
    }

    @Override
    public Page<Ref<E>> pageRef(int pageNumber, int pageSize) {
        return core.pageRef(pageNumber, pageSize);
    }

    @Override
    public Page<Ref<E>> pageRef(Pageable pageable) {
        return core.pageRef(pageable);
    }

    @Override
    public Slice<E> slice(int pageNumber, int pageSize) {
        return core.slice(pageNumber, pageSize);
    }

    @Override
    public Slice<E> slice(Pageable pageable) {
        return core.slice(pageable);
    }

    @Override
    public Slice<Ref<E>> sliceRef(int pageNumber, int pageSize) {
        return core.sliceRef(pageNumber, pageSize);
    }

    @Override
    public Slice<Ref<E>> sliceRef(Pageable pageable) {
        return core.sliceRef(pageable);
    }

    @Override
    public List<E> findAll() {
        return core.findAll();
    }

    @Override
    public List<Ref<E>> findAllRef() {
        return selectRef().getResultList();
    }

    @Override
    public List<E> findAllById(Iterable<ID> ids) {
        return core.findAllById(ids);
    }

    @Override
    public List<E> findAllByRef(Iterable<Ref<E>> refs) {
        return core.findAllByRef(refs);
    }

    @Override
    public void insert(Iterable<E> entities) {
        core.insert(entities);
    }

    @Override
    public void insert(Iterable<E> entities, boolean ignoreAutoGenerate) {
        core.insert(entities, ignoreAutoGenerate);
    }

    @Override
    public List<ID> insertAndFetchIds(Iterable<E> entities) {
        return core.insertAndFetchIds(entities);
    }

    @Override
    public List<E> insertAndFetch(Iterable<E> entities) {
        return core.insertAndFetch(entities);
    }

    @Override
    public void update(Iterable<E> entities) {
        core.update(entities);
    }

    @Override
    public List<E> updateAndFetch(Iterable<E> entities) {
        return core.updateAndFetch(entities);
    }

    @Override
    public void upsert(Iterable<E> entities) {
        core.upsert(entities);
    }

    @Override
    public List<ID> upsertAndFetchIds(Iterable<E> entities) {
        return core.upsertAndFetchIds(entities);
    }

    @Override
    public List<E> upsertAndFetch(Iterable<E> entities) {
        return core.upsertAndFetch(entities);
    }

    @Override
    public void remove(Iterable<E> entities) {
        core.remove(entities);
    }

    @Override
    public void removeByRef(Iterable<Ref<E>> refs) {
        core.removeByRef(refs);
    }

    @Override
    public long countById(Stream<ID> ids) {
        return core.countById(ids);
    }

    @Override
    public long countById(Stream<ID> ids, int chunkSize) {
        return core.countById(ids, chunkSize);
    }

    @Override
    public long countByRef(Stream<Ref<E>> refs) {
        return core.countByRef(refs);
    }

    @Override
    public long countByRef(Stream<Ref<E>> refs, int chunkSize) {
        return core.countByRef(refs, chunkSize);
    }

    @Override
    public void insert(Stream<E> entities) {
        core.insert(entities);
    }

    @Override
    public void insert(Stream<E> entities, boolean ignoreAutoGenerate) {
        core.insert(entities, ignoreAutoGenerate);
    }

    @Override
    public void insert(Stream<E> entities, int batchSize) {
        core.insert(entities, batchSize);
    }

    @Override
    public void insert(Stream<E> entities, int batchSize, boolean ignoreAutoGenerate) {
        core.insert(entities, batchSize, ignoreAutoGenerate);
    }

    @Override
    public void update(Stream<E> entities) {
        core.update(entities);
    }

    @Override
    public void update(Stream<E> entities, int batchSize) {
        core.update(entities, batchSize);
    }

    @Override
    public void upsert(Stream<E> entities) {
        core.upsert(entities);
    }

    @Override
    public void upsert(Stream<E> entities, int batchSize) {
        core.upsert(entities, batchSize);
    }

    @Override
    public void remove(Stream<E> entities) {
        core.remove(entities);
    }

    @Override
    public void remove(Stream<E> entities, int batchSize) {
        core.remove(entities, batchSize);
    }

    @Override
    public void removeByRef(Stream<Ref<E>> refs) {
        core.removeByRef(refs);
    }

    @Override
    public void removeByRef(Stream<Ref<E>> refs, int batchSize) {
        core.removeByRef(refs, batchSize);
    }

    @Override
    public ORMTemplate orm() {
        return new ORMTemplateImpl(core.orm());
    }
}
