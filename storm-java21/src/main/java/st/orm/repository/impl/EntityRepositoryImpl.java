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

import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.Entity;
import st.orm.Metamodel;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
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

    public EntityRepositoryImpl(@Nonnull st.orm.core.repository.EntityRepository<E, ID> core) {
        this.core = requireNonNull(core);
    }

    @Override
    public Model<E, ID> model() {
        return new ModelImpl<>((st.orm.core.template.impl.ModelImpl<E, ID>) core.model());
    }

    @Override
    public Ref<E> ref(@Nonnull ID id) {
        return core.ref(id);
    }

    @Override
    public Ref<E> ref(@Nonnull E entity) {
        return core.ref(entity);
    }

    @Override
    public Ref<E> unload(@Nonnull E entity) {
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
    public <R> QueryBuilder<E, R, ID> select(@Nonnull Class<R> selectType) {
        return new QueryBuilderImpl<>(core.select(selectType));
    }

    @Override
    public QueryBuilder<E, Ref<E>, ID> selectRef() {
        return new QueryBuilderImpl<>(core.selectRef());
    }

    @Override
    public <R> QueryBuilder<E, R, ID> select(@Nonnull Class<R> selectType, @Nonnull StringTemplate template) {
        return new QueryBuilderImpl<>(core.select(selectType, convert(template)));
    }

    @Override
    public <R extends Data> QueryBuilder<E, Ref<R>, ID> selectRef(@Nonnull Class<R> refType) {
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
    public boolean existsById(@Nonnull ID id) {
        return core.existsById(id);
    }

    @Override
    public boolean existsByRef(@Nonnull Ref<E> ref) {
        return core.existsByRef(ref);
    }

    @Override
    public void insert(@Nonnull E entity) {
        core.insert(entity);
    }

    @Override
    public void insert(@Nonnull E entity, boolean ignoreAutoGenerate) {
        core.insert(entity, ignoreAutoGenerate);
    }

    @Override
    public ID insertAndFetchId(@Nonnull E entity) {
        return core.insertAndFetchId(entity);
    }

    @Override
    public E insertAndFetch(@Nonnull E entity) {
        return core.insertAndFetch(entity);
    }

    @Override
    public void update(@Nonnull E entity) {
        core.update(entity);
    }

    @Override
    public E updateAndFetch(@Nonnull E entity) {
        return core.updateAndFetch(entity);
    }

    @Override
    public void upsert(@Nonnull E entity) {
        core.upsert(entity);
    }

    @Override
    public ID upsertAndFetchId(@Nonnull E entity) {
        return core.upsertAndFetchId(entity);
    }

    @Override
    public E upsertAndFetch(@Nonnull E entity) {
        return core.upsertAndFetch(entity);
    }

    @Override
    public void remove(@Nonnull E entity) {
        core.remove(entity);
    }

    @Override
    public void removeById(@Nonnull ID id) {
        core.removeById(id);
    }

    @Override
    public void removeByRef(@Nonnull Ref<E> ref) {
        core.removeByRef(ref);
    }

    @Override
    public void removeAll() {
        core.removeAll();
    }

    @Override
    public Optional<E> findById(@Nonnull ID id) {
        return core.findById(id);
    }

    @Override
    public Optional<E> findByRef(@Nonnull Ref<E> ref) {
        return core.findByRef(ref);
    }

    @Override
    public E getById(@Nonnull ID id) {
        return core.getById(id);
    }

    @Override
    public E getByRef(@Nonnull Ref<E> ref) {
        return core.getByRef(ref);
    }

    @Override
    public <V> Optional<E> findBy(@Nonnull Metamodel.Key<E, V> key, @Nonnull V value) {
        return core.findBy(key, value);
    }

    @Override
    public <V> E getBy(@Nonnull Metamodel.Key<E, V> key, @Nonnull V value) {
        return core.getBy(key, value);
    }

    @Override
    public <V extends Data> Optional<E> findByRef(@Nonnull Metamodel.Key<E, V> key, @Nonnull Ref<V> value) {
        return core.findByRef(key, value);
    }

    @Override
    public <V extends Data> E getByRef(@Nonnull Metamodel.Key<E, V> key, @Nonnull Ref<V> value) {
        return core.getByRef(key, value);
    }

    // Page methods.

    @Override
    public Page<E> page(int pageNumber, int pageSize) {
        return core.page(pageNumber, pageSize);
    }

    @Override
    public Page<E> page(@Nonnull Pageable pageable) {
        return core.page(pageable);
    }

    @Override
    public Page<Ref<E>> pageRef(int pageNumber, int pageSize) {
        return core.pageRef(pageNumber, pageSize);
    }

    @Override
    public Page<Ref<E>> pageRef(@Nonnull Pageable pageable) {
        return core.pageRef(pageable);
    }

    @Override
    public Window<E> scroll(@Nonnull Scrollable<E> scrollable) {
        return select().scroll(scrollable);
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
    public List<E> findAllById(@Nonnull Iterable<ID> ids) {
        return core.findAllById(ids);
    }

    @Override
    public List<E> findAllByRef(@Nonnull Iterable<Ref<E>> refs) {
        return core.findAllByRef(refs);
    }

    @Override
    public void insert(@Nonnull Iterable<E> entities) {
        core.insert(entities);
    }

    @Override
    public void insert(@Nonnull Iterable<E> entities, boolean ignoreAutoGenerate) {
        core.insert(entities, ignoreAutoGenerate);
    }

    @Override
    public List<ID> insertAndFetchIds(@Nonnull Iterable<E> entities) {
        return core.insertAndFetchIds(entities);
    }

    @Override
    public List<E> insertAndFetch(@Nonnull Iterable<E> entities) {
        return core.insertAndFetch(entities);
    }

    @Override
    public void update(@Nonnull Iterable<E> entities) {
        core.update(entities);
    }

    @Override
    public List<E> updateAndFetch(@Nonnull Iterable<E> entities) {
        return core.updateAndFetch(entities);
    }

    @Override
    public void upsert(@Nonnull Iterable<E> entities) {
        core.upsert(entities);
    }

    @Override
    public List<ID> upsertAndFetchIds(@Nonnull Iterable<E> entities) {
        return core.upsertAndFetchIds(entities);
    }

    @Override
    public List<E> upsertAndFetch(@Nonnull Iterable<E> entities) {
        return core.upsertAndFetch(entities);
    }

    @Override
    public void remove(@Nonnull Iterable<E> entities) {
        core.remove(entities);
    }

    @Override
    public void removeByRef(@Nonnull Iterable<Ref<E>> refs) {
        core.removeByRef(refs);
    }

    @Override
    public long countById(@Nonnull Stream<ID> ids) {
        return core.countById(ids);
    }

    @Override
    public long countById(@Nonnull Stream<ID> ids, int chunkSize) {
        return core.countById(ids, chunkSize);
    }

    @Override
    public long countByRef(@Nonnull Stream<Ref<E>> refs) {
        return core.countByRef(refs);
    }

    @Override
    public long countByRef(@Nonnull Stream<Ref<E>> refs, int chunkSize) {
        return core.countByRef(refs, chunkSize);
    }

    @Override
    public void insert(@Nonnull Stream<E> entities) {
        core.insert(entities);
    }

    @Override
    public void insert(@Nonnull Stream<E> entities, boolean ignoreAutoGenerate) {
        core.insert(entities, ignoreAutoGenerate);
    }

    @Override
    public void insert(@Nonnull Stream<E> entities, int batchSize) {
        core.insert(entities, batchSize);
    }

    @Override
    public void insert(@Nonnull Stream<E> entities, int batchSize, boolean ignoreAutoGenerate) {
        core.insert(entities, batchSize, ignoreAutoGenerate);
    }

    @Override
    public void update(@Nonnull Stream<E> entities) {
        core.update(entities);
    }

    @Override
    public void update(@Nonnull Stream<E> entities, int batchSize) {
        core.update(entities, batchSize);
    }

    @Override
    public void upsert(@Nonnull Stream<E> entities) {
        core.upsert(entities);
    }

    @Override
    public void upsert(@Nonnull Stream<E> entities, int batchSize) {
        core.upsert(entities, batchSize);
    }

    @Override
    public void remove(@Nonnull Stream<E> entities) {
        core.remove(entities);
    }

    @Override
    public void remove(@Nonnull Stream<E> entities, int batchSize) {
        core.remove(entities, batchSize);
    }

    @Override
    public void removeByRef(@Nonnull Stream<Ref<E>> refs) {
        core.removeByRef(refs);
    }

    @Override
    public void removeByRef(@Nonnull Stream<Ref<E>> refs, int batchSize) {
        core.removeByRef(refs, batchSize);
    }

    @Override
    public ORMTemplate orm() {
        return new ORMTemplateImpl(core.orm());
    }
}
