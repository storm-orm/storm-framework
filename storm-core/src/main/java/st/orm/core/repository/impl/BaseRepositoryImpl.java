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
package st.orm.core.repository.impl;

import static java.lang.Integer.MAX_VALUE;
import static java.util.Objects.requireNonNull;
import static java.util.Spliterator.ORDERED;
import static java.util.Spliterators.spliteratorUnknownSize;
import static st.orm.Operator.EQUALS;
import static st.orm.core.spi.Providers.selectFrom;
import static st.orm.core.spi.Providers.selectRefFrom;
import static st.orm.core.template.TemplateString.wrap;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.NoResultException;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.repository.Repository;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.Query;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.TemplateString;
import st.orm.core.template.Templates;
import st.orm.core.template.impl.SqlInterceptorManager;

/**
 * Base implementation for all repositories.
 */
abstract class BaseRepositoryImpl<E extends Data, ID> implements Repository {

    protected final ORMTemplate ormTemplate;
    protected final Model<E, ID> model;
    private final boolean compoundPrimaryKey;

    /** Lazily created constant plans for the parameter-less statement shapes; racy initialization is benign. */
    private volatile QueryPlan findAllPlan;
    private volatile QueryPlan findAllRefPlan;
    private volatile QueryPlan countPlan;

    /** Lazily created by-id select plan, bound per call via the primary key; racy initialization is benign. */
    private volatile QueryPlan findByIdPlan;

    /** Lazily created by-key select plans for unique-key lookups; the population is the finite set of unique keys. */
    private final ConcurrentMap<Metamodel<E, ?>, QueryPlan> findByKeyPlans = new ConcurrentHashMap<>();

    /**
     * Cleared on the first failed plan creation. Plans require template processing support ahead of execution;
     * templates without it, such as JPA, keep the per-call path, and genuine template errors surface through that
     * same path.
     */
    private volatile boolean plansSupported = true;

    public BaseRepositoryImpl(ORMTemplate ormTemplate, Model<E, ID> model) {
        this.ormTemplate = requireNonNull(ormTemplate);
        this.model = requireNonNull(model);
        this.compoundPrimaryKey = model.getPrimaryKeyMetamodel()
                .filter(Metamodel::isInline)
                .isPresent();
    }

    /**
     * Returns whether repository statements are served from cached plans. Plans are processed against the
     * repository's own template; while a scoped template customizer is active, the customized template must
     * generate the statement, so the cached plans are bypassed.
     */
    protected final boolean usePlans() {
        return plansSupported && !SqlInterceptorManager.hasLocalCustomizers();
    }

    protected final @Nullable QueryPlan createPlanQuietly(Supplier<QueryPlan> factory) {
        try {
            return factory.get();
        } catch (PersistenceException e) {
            plansSupported = false;
            return null;
        }
    }

    /**
     * Returns the default chunk size for batch operations.
     *
     * <p>This method is aware of any registered {@code SqlInterceptor} instances and returns the default chunk size
     * that is optimized for the SQL dialect that is used by the underlying SQL template. The dialect is determined
     * based on the SQL template's configuration and the interceptors that have been applied to it.</p>
     *
     * @return the default chunk size for batch operations.
     * @since 1.5
     */
    public int getDefaultChunkSize() {
        if (ormTemplate.dialect().supportsMultiValueTuples()) {
            return 1000;
        } else {
            return compoundPrimaryKey
                    ? 100   // Compound PKs can become quite large, so we default to a smaller chunk size.
                    : 1000;
        }
    }

    /**
     * Converts an iterable collection to a stream.
     *
     * @param iterable the iterable collection to convert.
     * @return a stream representing the elements of the iterable collection.
     * @param <X> the type of elements in the iterable collection.
     */
    protected static <X> Stream<X> toStream(Iterable<X> iterable) {
        return StreamSupport.stream(spliteratorUnknownSize(iterable.iterator(), ORDERED), false);
    }

    /**
     * Provides access to the underlying ORM template.
     *
     * @return the ORM template.
     */
    @Override
    public ORMTemplate orm() {
        return ormTemplate;
    }

    /**
     * Returns the model associated with this repository.
     *
     * @return the model.
     */
    public Model<E, ID> model() {
        return model;
    }

    /**
     * Creates a new ref entity instance with the specified primary key.
     *
     * <p>This method creates a lightweight reference that encapsulates only the primary key of an entity,
     * without loading the full entity data into memory. The complete record can be fetched on demand by invoking
     * {@link Ref#fetch()}, which will trigger a separate database call.</p>
     *
     * @param id the primary key of the entity.
     * @return a ref entity instance containing only the primary key.
     * @since 1.3
     */
    public Ref<E> ref(ID id) {
        return ormTemplate.ref(model.type(), id);
    }

    /**
     * Creates a new query builder for the entity type managed by this repository.
     *
     * @return a new query builder for the entity type.
     */
    public QueryBuilder<E, E, ID> select() {
        return select(model.type());
    }

    /**
     * Creates a new query builder for the entity type managed by this repository.
     *
     * @return a new query builder for the entity type.
     */
    public QueryBuilder<E, Long, ID> selectCount() {
        return selectFrom(ormTemplate, model.type(), Long.class, TemplateString.of("COUNT(*)"), false, () -> model);
    }

    /**
     * Creates a new query builder for the custom {@code selectType}.
     *
     * @param selectType the result type of the query.
     * @return a new query builder for the custom {@code selectType}.
     * @param <R> the result type of the query.
     */
    public <R> QueryBuilder<E, R, ID> select(Class<R> selectType) {
        return select(selectType, wrap(selectType));
    }
    /**
     * Creates a new query builder for selecting refs to entities of the type managed by this repository.
     *
     * <p>This method is typically used when you only need the primary keys of the entities initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full entity, call {@link Ref#fetch()}, which will perform an
     * additional database query on demand.</p>
     *
     * @return a new query builder for selecting refs to entities.
     * @since 1.3
     */
    public QueryBuilder<E, Ref<E>, ID> selectRef() {
        return selectRefFrom(ormTemplate, model.type(), model.type(), model.primaryKeyType(), () -> model);
    }

    /**
     * Creates a new query builder for the custom {@code selectType} and custom {@code template} for the select clause.
     *
     * @param selectType the result type of the query.
     * @param template the custom template for the select clause.
     * @return a new query builder for the custom {@code selectType}.
     * @param <R> the result type of the query.
     */
    public <R> QueryBuilder<E, R, ID> select(Class<R> selectType, TemplateString template) {
        return selectFrom(ormTemplate, model().type(), selectType, template, false, () -> model);
    }


    /**
     * Creates a new query builder for selecting refs to entities of the type managed by this repository.
     *
     * <p>This method is typically used when you only need the primary keys of the entities initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full entity, call {@link Ref#fetch()}, which will perform an
     * additional database query on demand.</p>
     *
     * @param refType the type that is selected as ref.
     * @return a new query builder for selecting refs to entities.
     * @since 1.3
     */
    public <R extends Data> QueryBuilder<E, Ref<R>, ID> selectRef(Class<R> refType) {
        var pkType = ormTemplate.model(refType, true).primaryKeyType();
        return selectRefFrom(ormTemplate, model.type(), refType, pkType, () -> model);
    }

    /**
     * Returns the number of entities in the database of the entity type supported by this repository.
     *
     * @return the total number of entities in the database as a long value.
     * @throws PersistenceException if the count operation fails due to underlying database issues, such as
     * connectivity.
     */
    public long count() {
        if (usePlans()) {
            var plan = countPlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> selectCount().plan());
                countPlan = plan;
            }
            if (plan != null) {
                return plan.query().withoutFetchSize().getSingleResult(Long.class);
            }
        }
        return selectCount().getSingleResult();
    }

    /**
     * Checks if any entity of the type managed by this repository exists in the database.
     *
     * @return true if at least one entity exists, false otherwise.
     * @throws PersistenceException if there is an underlying database issue during the count operation.
     */
    public boolean exists() {
        return count() > 0;
    }

    /**
     * Checks if an entity with the specified primary key exists in the database.
     *
     * <p>This method determines the presence of an entity by checking if the count of entities with the given primary
     * key is greater than zero. It leverages the {@code selectCount} method, which performs a count operation on the
     * database.</p>
     *
     * @param id the primary key of the entity to check for existence.
     * @return true if an entity with the specified primary key exists, false otherwise.
     * @throws PersistenceException if there is an underlying database issue during the count operation.
     */
    public boolean existsById(ID id) {
        return countById(Stream.of(id)) > 0;
    }

    /**
     * Checks if an entity with the specified primary key exists in the database.
     *
     * <p>This method determines the presence of an entity by checking if the count of entities with the given primary
     * key is greater than zero. It leverages the {@code selectCount} method, which performs a count operation on the
     * database.</p>
     *
     * @param ref the primary key of the entity to check for existence.
     * @return true if an entity with the specified primary key exists, false otherwise.
     * @throws PersistenceException if there is an underlying database issue during the count operation.
     */
    public boolean existsByRef(Ref<E> ref) {
        return countByRef(Stream.of(ref)) > 0;
    }

    // Singular findBy methods.

    /**
     * Retrieves an entity based on its primary key.
     *
     * <p>This method performs a lookup in the database, returning the corresponding entity if it exists.</p>
     *
     * @param id the primary key of the entity to retrieve.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    public Optional<E> findById(ID id) {
        var query = findByIdQuery(id);
        if (query != null) {
            return query.getOptionalResult(model.type());
        }
        return select().where(id).getOptionalResult();
    }

    /**
     * Retrieves an entity based on its primary key, expressed by a ref.
     *
     * <p>This method performs a lookup in the database, returning the corresponding entity if it exists.</p>
     *
     * @param ref the ref to match.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    public Optional<E> findByRef(Ref<E> ref) {
        //noinspection unchecked
        var query = findByIdQuery((ID) ref.id());
        if (query != null) {
            return query.getOptionalResult(model.type());
        }
        return select().where(ref).getOptionalResult();
    }

    /**
     * Retrieves an entity based on its primary key.
     *
     * <p>This method performs a lookup in the database, returning the corresponding entity if it exists.</p>
     *
     * @param id the primary key of the entity to retrieve.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws NoResultException if no entity is found matching the given primary key, indicating that there's no
     *                           corresponding data in the database.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    public E getById(ID id) {
        var query = findByIdQuery(id);
        if (query != null) {
            return query.getSingleResult(model.type());
        }
        return select().where(id).getSingleResult();
    }

    /**
     * Returns a query that selects the entity graph by primary key, served from a cached plan, or {@code null} when
     * plans are unavailable; see {@link #usePlans()} for the guards.
     */
    private @Nullable Query findByIdQuery(ID id) {
        if (usePlans()) {
            var plan = findByIdPlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> {
                    var bindVars = ormTemplate.createBindVars();
                    return ormTemplate.plan(TemplateString.raw("""
                            SELECT \0
                            FROM \0
                            WHERE \0""", model.type(), Templates.from(model.type(), true), bindVars));
                });
                findByIdPlan = plan;
            }
            if (plan != null) {
                // Eager consumption; the fetch-size hint is skipped like the builder's eager terminal does.
                return plan.bindValue(id).withoutFetchSize();
            }
        }
        return null;
    }

    /**
     * Retrieves an entity based on its primary key, expressed by a ref.
     *
     * <p>This method performs a lookup in the database, returning the corresponding entity if it exists.</p>
     *
     * @param ref the ref to match.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws NoResultException if no entity is found matching the given primary key, indicating that there's no
     *                           corresponding data in the database.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    public E getByRef(Ref<E> ref) {
        //noinspection unchecked
        var query = findByIdQuery((ID) ref.id());
        if (query != null) {
            return query.getSingleResult(model.type());
        }
        return select().where(ref).getSingleResult();
    }

    // Singular findBy / getBy methods for unique keys.

    /**
     * Retrieves an entity by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the entity matching the given key value, or empty if none exists.
     * @param <V> the type of the key field.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     */
    public <V extends @Nullable Object> Optional<E> findBy(Metamodel.Key<E, V> key, V value) {
        var query = findByKeyQuery(key, value);
        if (query != null) {
            return query.getOptionalResult(model.type());
        }
        return select().where(key, EQUALS, value).getOptionalResult();
    }

    /**
     * Retrieves an entity by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the entity matching the given key value.
     * @param <V> the type of the key field.
     * @throws NoResultException if no entity is found matching the given key value.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     */
    public <V extends @Nullable Object> E getBy(Metamodel.Key<E, V> key, V value) {
        var query = findByKeyQuery(key, value);
        if (query != null) {
            return query.getSingleResult(model.type());
        }
        return select().where(key, EQUALS, value).getSingleResult();
    }

    /**
     * Returns a query that selects the entity graph by the given unique key, served from a cached plan, or
     * {@code null} when plans are unavailable; see {@link #usePlans()} for the guards.
     */
    private @Nullable Query findByKeyQuery(Metamodel<E, ?> key, Object value) {
        if (usePlans()) {
            var plan = findByKeyPlans.get(key);
            if (plan == null) {
                var created = createPlanQuietly(() -> {
                    var bindVars = ormTemplate.createBindVars();
                    return ormTemplate.plan(TemplateString.raw("""
                            SELECT \0
                            FROM \0
                            WHERE \0""", model.type(), Templates.from(model.type(), true),
                            Templates.where(key, bindVars)));
                });
                if (created != null) {
                    var existing = findByKeyPlans.putIfAbsent(key, created);
                    plan = existing != null ? existing : created;
                }
            }
            if (plan != null) {
                // Eager consumption; the fetch-size hint is skipped like the builder's eager terminal does.
                return plan.bindValue(value).withoutFetchSize();
            }
        }
        return null;
    }

    /**
     * Retrieves an entity by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the entity matching the given ref value, or empty if none exists.
     * @param <V> the type of the referenced entity.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     */
    public <V extends Data> Optional<E> findByRef(Metamodel.Key<E, V> key, Ref<V> value) {
        return select().where(key, value).getOptionalResult();
    }

    /**
     * Retrieves an entity by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the entity matching the given ref value.
     * @param <V> the type of the referenced entity.
     * @throws NoResultException if no entity is found matching the given ref value.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     */
    public <V extends Data> E getByRef(Metamodel.Key<E, V> key, Ref<V> value) {
        return select().where(key, value).getSingleResult();
    }

    // List based methods.

    /**
     * Returns a list of all entities of the type supported by this repository. Each element in the list represents
     * an entity in the database, encapsulating all relevant data as mapped by the entity model.
     *
     * <p><strong>Note:</strong> Loading all entities into memory at once can be very memory-intensive if your
     * table is large.</p>
     *
     * @return a stream of all entities of the type supported by this repository.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public List<E> findAll() {
        if (usePlans()) {
            var plan = findAllPlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> select().plan());
                findAllPlan = plan;
            }
            if (plan != null) {
                // Eager consumption; the fetch-size hint is skipped like the builder's eager terminal does.
                return plan.query().withoutFetchSize().getResultList(model.type());
            }
        }
        return select().getResultList();
    }

    public List<Ref<E>> findAllRef() {
        if (usePlans()) {
            var plan = findAllRefPlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> selectRef().plan());
                findAllRefPlan = plan;
            }
            if (plan != null) {
                // Eager consumption; the fetch-size hint is skipped like the builder's eager terminal does.
                try (var stream = plan.query().withoutFetchSize().getRefStream(model.type(), model.primaryKeyType())) {
                    return stream.toList();
                }
            }
        }
        return selectRef().getResultList();
    }

    /**
     * Retrieves a list of entities based on their primary keys.
     *
     * <p>This method retrieves entities matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     * </p>
     *
     * <p><strong>Note:</strong> The order of entities in the returned list is not guaranteed to match the order of IDs
     * in the input collection, as the database may not preserve insertion order during retrieval.</p>
     *
     * @param ids the primary keys of the entities to retrieve, represented as an iterable collection.
     * @return a list of entities corresponding to the provided primary keys. Entities are returned without any
     *         guarantee of order alignment with the input list. If an ID does not correspond to any entity in the
     *         database, no corresponding entity will be included in the returned list.
     * @throws PersistenceException if the selection operation fails due to database issues, such as connectivity
     *         problems or invalid input parameters.
     */
    public List<E> findAllById(Iterable<ID> ids) {
        try (var stream = selectByIdMaterialized(toStream(ids))) {
            return stream.toList();
        }
    }

    /**
     * Retrieves a list of entities based on their primary keys.
     *
     * <p>This method retrieves entities matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     * </p>
     *
     * <p><strong>Note:</strong> The order of entities in the returned list is not guaranteed to match the order of IDs
     * in the input collection, as the database may not preserve insertion order during retrieval.</p>
     *
     * @param refs the primary keys of the entities to retrieve, represented as an iterable collection.
     * @return a list of entities corresponding to the provided primary keys. Entities are returned without any
     *         guarantee of order alignment with the input list. If an ID does not correspond to any entity in the
     *         database, no corresponding entity will be included in the returned list.
     * @throws PersistenceException if the selection operation fails due to database issues, such as connectivity
     *         problems or invalid input parameters.
     */
    public List<E> findAllByRef(Iterable<Ref<E>> refs) {
        try (var stream = selectByRefMaterialized(toStream(refs))) {
            return stream.toList();
        }
    }

    /**
     * Streams entities by id in batches, materializing each batch without the fetch-size hint.
     *
     * <p>Backs {@link #findAllById(Iterable)}, which buffers the full result anyway. Cursor-based fetching would only
     * add overhead here, wrapping each batch in a transaction on dialects where cursors require one. Unlike
     * {@link #selectById(Stream)} this does not stream rows within a batch, so it is kept private rather than exposed
     * as a lazy API.</p>
     *
     * @param ids a stream of entity IDs to retrieve from the repository.
     * @return a stream of entities, materialized one batch at a time.
     */
    protected Stream<E> selectByIdMaterialized(Stream<ID> ids) {
        return chunked(ids, getDefaultChunkSize(), batch -> select().whereId(batch).getResultList().stream());
    }

    /**
     * Streams entities by ref in batches, materializing each batch without the fetch-size hint.
     *
     * <p>The ref counterpart to {@link #selectByIdMaterialized(Stream)}, backing {@link #findAllByRef(Iterable)}.</p>
     *
     * @param refs a stream of refs to retrieve from the repository.
     * @return a stream of entities, materialized one batch at a time.
     */
    protected Stream<E> selectByRefMaterialized(Stream<Ref<E>> refs) {
        return chunked(refs, getDefaultChunkSize(), batch -> select().whereRef(batch).getResultList().stream());
    }

    // Stream based methods. These methods operate in multiple batches.

    /**
     * Retrieves a stream of entities based on their primary keys.
     *
     * <p>This method executes queries in batches, depending on the number of primary keys in the specified ids stream.
     * This optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities.
     * The batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param ids a stream of entity IDs to retrieve from the repository.
     * @return a stream of entities corresponding to the provided primary keys. The order of entities in the stream is
     *         not guaranteed to match the order of ids in the input stream. If an id does not correspond to any entity
     *         in the database, it will simply be skipped, and no corresponding entity will be included in the returned
     *         stream. If the same entity is requested multiple times, it may be included in the stream multiple times
     *         if it is part of a separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public Stream<E> selectById(Stream<ID> ids) {
        return selectById(ids, getDefaultChunkSize());
    }

    /**
     * Retrieves a stream of entities based on their primary keys.
     *
     * <p>This method executes queries in batches, depending on the number of primary keys in the specified ids stream.
     * This optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities.
     * The batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param refs a stream of refs to retrieve from the repository.
     * @return a stream of entities corresponding to the provided primary keys. The order of entities in the stream is
     *         not guaranteed to match the order of ids in the input stream. If an id does not correspond to any entity
     *         in the database, it will simply be skipped, and no corresponding entity will be included in the returned
     *         stream. If the same entity is requested multiple times, it may be included in the stream multiple times
     *         if it is part of a separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public Stream<E> selectByRef(Stream<Ref<E>> refs) {
        return selectByRef(refs, getDefaultChunkSize());
    }

    /**
     * Retrieves a stream of entities based on their primary keys.
     *
     * <p>This method executes queries in batches, with the batch size determined by the provided parameter. This
     * optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities. The
     * batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param ids a stream of entity IDs to retrieve from the repository.
     * @param chunkSize the number of primary keys to include in each batch. This parameter determines the size of the
     *                  batches used to execute the selection operation. A larger batch size can improve performance,
     *                  especially when dealing with large sets of primary keys.
     * @return a stream of entities corresponding to the provided primary keys. The order of entities in the stream is
     * not guaranteed to match the order of ids in the input stream. If an id does not correspond to any entity in the
     * database, it will simply be skipped, and no corresponding entity will be included in the returned stream. If the
     * same entity is requested multiple times, it may be included in the stream multiple times if it is part of a
     * separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public Stream<E> selectById(Stream<ID> ids, int chunkSize) {
        return chunked(ids, chunkSize, batch -> select().whereId(batch).getResultStream()); // Stream returned by getResultStream is closed by the batch operation.
    }

    /**
     * Retrieves a stream of entities based on their primary keys.
     *
     * <p>This method executes queries in batches, with the batch size determined by the provided parameter. This
     * optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities. The
     * batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param refs a stream of refs to retrieve from the repository.
     * @param chunkSize the number of primary keys to include in each batch. This parameter determines the size of the
     *                  batches used to execute the selection operation. A larger batch size can improve performance,
     *                  especially when dealing with large sets of primary keys.
     * @return a stream of entities corresponding to the provided primary keys. The order of entities in the stream is
     * not guaranteed to match the order of ids in the input stream. If an id does not correspond to any entity in the
     * database, it will simply be skipped, and no corresponding entity will be included in the returned stream. If the
     * same entity is requested multiple times, it may be included in the stream multiple times if it is part of a
     * separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public Stream<E> selectByRef(Stream<Ref<E>> refs, int chunkSize) {
        return chunked(refs, chunkSize, batch -> select().whereRef(batch).getResultStream()); // Stream returned by getResultStream is closed by the batch operation.
    }

    /**
     * Counts the number of entities identified by the provided stream of IDs using the default batch size.
     *
     * <p>This method calculates the total number of entities that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.</p>
     *
     * @param ids a stream of IDs for which to count matching entities.
     * @return the total count of entities matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    public long countById(Stream<ID> ids) {
        return countById(ids, getDefaultChunkSize());
    }

    /**
     * Counts the number of entities identified by the provided stream of IDs, with the counting process divided into
     * batches of the specified size.
     *
     * <p>This method performs the counting operation in batches, specified by the {@code batchSize} parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.</p>
     *
     * @param ids a stream of IDs for which to count matching entities.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @return the total count of entities matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    public long countById(Stream<ID> ids, int chunkSize) {
        return chunked(ids, chunkSize)
                .mapToLong(slice -> selectCount()
                        .whereId(slice)
                        .getSingleResult())
                .sum();
    }

    /**
     * Counts the number of entities identified by the provided stream of refs using the default batch size.
     *
     * <p>This method calculates the total number of entities that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.</p>
     *
     * @param refs a stream of refs for which to count matching entities.
     * @return the total count of entities matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    public long countByRef(Stream<Ref<E>> refs) {
        return countByRef(refs, getDefaultChunkSize());
    }

    /**
     * Counts the number of entities identified by the provided stream of refs, with the counting process divided into
     * batches of the specified size.
     *
     * <p>This method performs the counting operation in batches, specified by the {@code batchSize} parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.</p>
     *
     * @param refs a stream of IDs for which to count matching entities.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @return the total count of entities matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    public long countByRef(Stream<Ref<E>> refs, int chunkSize) {
        return chunked(refs, chunkSize)
                .mapToLong(slice -> selectCount()
                        .whereRef(slice)
                        .getSingleResult())
                .sum();
    }

    /**
     * Performs the function in multiple batches, each containing up to {@code batchSize} elements from the stream.
     *
     * @param stream the stream to batch.
     * @param batchSize the maximum number of elements to include in each batch.
     * @param function the function to apply to each batch.
     * @return a stream of results from each batch.
     * @param <X> the type of elements in the stream.
     * @param <Y> the type of elements in the result stream.
     */
    protected static <X, Y> Stream<Y> chunked(Stream<X> stream,
                                              int batchSize,
                                              Function<List<X>, Stream<Y>> function) {
        return chunked(stream, batchSize)
                .flatMap(function); // Note that the flatMap operation closes the stream passed to it.
    }

    /**
     * Generates a stream of slices, each containing a subset of elements from the original stream up to a specified
     * size. This method is designed to facilitate batch processing of large streams by dividing the stream into
     * smaller manageable slices, which can be processed independently.
     *
     * <p>If the specified size is equal to {@code Integer.MAX_VALUE}, this method will return a single slice containing
     * the original stream, effectively bypassing the slicing mechanism. This is useful for operations that can handle
     * all elements at once without the need for batching.</p>
     *
     * @param <X> the type of elements in the stream.
     * @param stream the original stream of elements to be sliced.
     * @param size the maximum number of elements to include in each slice. If {@code size} is
     * {@code Integer.MAX_VALUE}, only one slice will be returned.
     * @return a stream of slices, where each slice contains up to {@code size} elements from the original stream.
     */
    protected static <X> Stream<List<X>> chunked(Stream<X> stream, int size) {
        if (size == MAX_VALUE) {
            return Stream.of(stream.toList());
        }
        // We're lifting the resource closing logic from the input stream to the output stream.
        final Iterator<X> iterator = stream.iterator();
        var it = new Iterator<List<X>>() {
            @Override
            public boolean hasNext() {
                return iterator.hasNext();
            }

            @Override
            public List<X> next() {
                Iterator<X> sliceIterator = new Iterator<>() {
                    private int count = 0;

                    @Override
                    public boolean hasNext() {
                        return count < size && iterator.hasNext();
                    }

                    @Override
                    public X next() {
                        if (count >= size) {
                            throw new IllegalStateException("Size exceeded.");
                        }
                        count++;
                        return iterator.next();
                    }
                };
                return StreamSupport.stream(spliteratorUnknownSize(sliceIterator, 0), false).toList();
            }
        };
        return StreamSupport.stream(spliteratorUnknownSize(it, 0), false)
                .onClose(stream::close);
    }
}
