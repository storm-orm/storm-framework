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

import static java.util.stream.Collectors.joining;
import static st.orm.GenerationStrategy.IDENTITY;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.core.repository.impl.StreamSupport.partitioned;
import static st.orm.core.spi.Providers.deleteFrom;
import static st.orm.core.template.TemplateString.raw;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.BindVars;
import st.orm.Data;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.GenerationStrategy;
import st.orm.Metamodel;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.OptimisticLockException;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.core.repository.EntityRepository;
import st.orm.core.spi.CacheRetention;
import st.orm.core.spi.EntityCache;
import st.orm.core.spi.EntityCacheMetrics;
import st.orm.core.spi.StormConfigHelper;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.TransactionScope;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedQuery;
import st.orm.core.template.Query;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.QueryPlan;
import st.orm.core.template.TemplateString;
import st.orm.core.template.Templates;
import st.orm.core.template.impl.Elements;
import st.orm.core.template.impl.JoinedEntityHelper;
import st.orm.core.template.impl.LazySupplier;
import st.orm.core.template.impl.SqlInterceptorManager;

/**
 * Default implementation of {@link EntityRepository}.
 *
 * @param <E> the type of entity managed by this repository.
 * @param <ID> the type of the primary key of the entity.
 */
@SuppressWarnings("DuplicatedCode")
public class EntityRepositoryImpl<E extends Entity<ID>, ID>
        extends BaseRepositoryImpl<E, ID>
        implements EntityRepository<E, ID> {

    private static final Logger LOGGER = LoggerFactory.getLogger(EntityRepositoryImpl.class);

    protected final int defaultBatchSize;
    protected final List<Column> primaryKeyColumns;
    protected final GenerationStrategy generationStrategy;
    private final DirtySupport<E, ID> dirtySupport;
    private final CacheRetention cacheRetention;
    private final CallbackSupport<E, ID> entityCallbacks;

    /**
     * Bounded cache of single-update plans keyed by dirty shape. It admits the configured maximum number of dynamic
     * shapes plus the full-update shape; shapes beyond the bound fall back to per-call template processing, keeping
     * the cached statement fan-out aligned with what maxShapes allows for batches.
     */
    private final ConcurrentMap<Set<Metamodel<?, ?>>, QueryPlan> updatePlans = new ConcurrentHashMap<>();

    /** Lazily created plans for the fixed single-entity statement shapes; racy initialization is benign. */
    private volatile QueryPlan insertPlan;
    private volatile QueryPlan insertIgnoringAutoGeneratePlan;
    private volatile QueryPlan removePlan;
    private volatile QueryPlan removeAllPlan;
    private volatile QueryPlan removeByIdPlan;

    /**
     * Version columns participate in the identifying WHERE columns of delete statements, and an id cannot supply a
     * version value, so versioned types keep the per-call path for removal by id.
     */
    private final boolean versionedEntity;

    public EntityRepositoryImpl(ORMTemplate ormTemplate, Model<E, ID> model) {
        super(ormTemplate, model);
        this.versionedEntity = model.declaredColumns().stream().anyMatch(Column::version);
        this.defaultBatchSize = 1000;
        this.primaryKeyColumns = model.declaredColumns().stream()
                .filter(Column::primaryKey)
                .toList();
        this.generationStrategy = primaryKeyColumns.stream()
                .map(Column::generation)
                .findFirst()
                .orElse(NONE);
        if (generationStrategy == SEQUENCE && primaryKeyColumns.size() != 1) {
            throw new PersistenceException("Sequence generation is only supported for single-column primary keys for %s.".formatted(model.type().getSimpleName()));
        }
        this.dirtySupport = new DirtySupport<>(model, ormTemplate.config());
        this.cacheRetention = StormConfigHelper.cacheRetention(ormTemplate.config());
        this.entityCallbacks = new CallbackSupport<>(ormTemplate, model.type());
        EntityCacheMetrics.getInstance().registerEntity(model.type().getName(), cacheRetention.name());
        LOGGER.debug("{}: cacheRetention={}", model.type().getSimpleName(), cacheRetention);
    }

    protected boolean isAutoGeneratedPrimaryKey() {
        return generationStrategy == IDENTITY || generationStrategy == SEQUENCE;
    }

    /**
     * Returns {@code true} if the given entity should be routed to {@link #update(Entity)} when
     * {@link #upsert(Entity)} is called. This is the case for auto-generated primary keys where
     * the entity has a non-default primary key value (i.e., the entity was previously inserted).
     *
     * @param entity the entity to check.
     * @return {@code true} if the upsert should be routed to update.
     * @since 1.9
     */
    protected boolean isUpsertUpdate(E entity) {
        return isAutoGeneratedPrimaryKey() && !model.isDefaultPrimaryKey(entity.id());
    }

    /**
     * Returns {@code true} if the given entity should be routed to {@link #insert(Entity)} when
     * {@link #upsert(Entity)} is called. This is the case for databases that cannot perform a
     * SQL-level upsert (MERGE) with auto-generated primary keys.
     *
     * <p>The default implementation returns {@code false}. Dialect-specific subclasses override
     * this method to return {@code true} when appropriate.</p>
     *
     * @param entity the entity to check.
     * @return {@code true} if the upsert should be routed to insert.
     * @since 1.9
     */
    protected boolean isUpsertInsert(E entity) {
        return false;
    }

    /**
     * Fires {@link EntityCallback#beforeInsert(Entity)} on all registered callbacks, returning the (potentially
     * transformed) entity to persist.
     *
     * <p>Callbacks are invoked in registration order. Each callback receives the entity returned by the previous
     * one, forming a transformation chain.</p>
     *
     * @param entity the entity about to be inserted.
     * @return the entity to persist, after all callbacks have been applied.
     * @since 1.9
     */
    protected E fireBeforeInsert(E entity) {
        return entityCallbacks.beforeInsert(entity);
    }

    /**
     * Fires {@link EntityCallback#beforeUpdate(Entity)} on all registered callbacks, returning the (potentially
     * transformed) entity to persist.
     *
     * <p>Callbacks are invoked in registration order. Each callback receives the entity returned by the previous
     * one, forming a transformation chain.</p>
     *
     * @param entity the entity about to be updated.
     * @return the entity to persist, after all callbacks have been applied.
     * @since 1.9
     */
    protected E fireBeforeUpdate(E entity) {
        return entityCallbacks.beforeUpdate(entity);
    }

    /**
     * Fires {@link EntityCallback#afterInsert(List)} on all registered callbacks with a list of one: the entity as it
     * was sent to the database.
     *
     * <p>Used by the methods that return nothing, which read no key back and so cannot report one.</p>
     *
     * @param entity the entity that was inserted.
     * @since 1.9
     */
    protected void fireAfterInsert(E entity) {
        entityCallbacks.afterInsert(entity);
    }

    /**
     * Fires {@link EntityCallback#afterInsert(List)} on all registered callbacks with a list of one: the entity as it
     * was sent to the database, carrying the primary key the database assigned.
     *
     * @param entity the entity that was inserted.
     * @param generatedPrimaryKey the primary key the database assigned, or {@code null} when no key was retrieved.
     * @since 1.13
     */
    protected void fireAfterInsert(E entity, @Nullable ID generatedPrimaryKey) {
        entityCallbacks.afterInsert(entity, generatedPrimaryKey);
    }

    /**
     * Fires {@link EntityCallback#afterInsert(List)} for a batch, pairing each entity with the primary key the
     * database assigned. The keys are reported in insertion order, which is the contract the batch insert paths
     * already rely on; a batch that read no keys back passes an empty list and reports the entities as sent.
     *
     * @param entities the entities that were inserted, in insertion order.
     * @param generatedPrimaryKeys the assigned primary keys, in the same order.
     * @since 1.13
     */
    protected void fireAfterInsert(List<E> entities, List<ID> generatedPrimaryKeys) {
        entityCallbacks.afterInsert(entities, generatedPrimaryKeys);
    }

    /**
     * Fires {@link EntityCallback#afterUpdate(List)} on all registered callbacks with a list of one.
     *
     * <p>The entity passed to this method is the entity as it was sent to the database (after
     * {@link #fireBeforeUpdate(Entity) beforeUpdate} transformation). An update carries its own primary key, so only
     * the {@code *AndFetch} methods, which read the row back, reflect database-side changes such as version
     * increments or trigger-applied modifications.</p>
     *
     * @param entity the entity that was updated.
     * @since 1.9
     */
    private void fireAfterUpdate(E entity) {
        entityCallbacks.afterUpdate(entity);
    }

    /**
     * Fires {@link EntityCallback#afterUpdate(List)} for a batch, with the entities as they were sent to the database.
     *
     * @param entities the entities that were updated, in update order.
     * @since 1.14
     */
    private void fireAfterUpdate(List<E> entities) {
        entityCallbacks.afterUpdate(entities);
    }

    /**
     * Returns {@code true} if there are entity callbacks registered.
     *
     * @return {@code true} if entity callbacks are registered.
     * @since 1.9
     */
    protected boolean hasEntityCallbacks() {
        return entityCallbacks.isActive();
    }

    /**
     * Fires {@link EntityCallback#beforeUpsert(Entity)} on all registered callbacks, returning the (potentially
     * transformed) entity to persist.
     *
     * <p>This method is only called on the SQL-level upsert path. When an upsert is routed to
     * {@link #insert(Entity)} or {@link #update(Entity)}, the corresponding {@code beforeInsert} or
     * {@code beforeUpdate} callbacks are fired instead.</p>
     *
     * <p>Callbacks are invoked in registration order. Each callback receives the entity returned by the previous
     * one, forming a transformation chain.</p>
     *
     * @param entity the entity about to be upserted.
     * @return the entity to persist, after all callbacks have been applied.
     * @since 1.9
     */
    protected E fireBeforeUpsert(E entity) {
        return entityCallbacks.beforeUpsert(entity);
    }

    /**
     * Fires {@link EntityCallback#afterUpsert(List)} on all registered callbacks with a list of one.
     *
     * <p>This method is only called on the SQL-level upsert path. When an upsert is routed to
     * {@link #insert(Entity)} or {@link #update(Entity)}, the corresponding {@code afterInsert} or
     * {@code afterUpdate} callbacks are fired instead.</p>
     *
     * <p>The entity passed to this method is the entity as it was sent to the database (after
     * {@link #fireBeforeUpsert(Entity) beforeUpsert} transformation). Used by the methods that return nothing, which
     * read no key back and so cannot report one.</p>
     *
     * @param entity the entity that was upserted.
     * @since 1.9
     */
    protected void fireAfterUpsert(E entity) {
        entityCallbacks.afterUpsert(entity);
    }

    /**
     * Fires {@link EntityCallback#afterUpsert(List)} on all registered callbacks with a list of one: the entity as it
     * was sent to the database, carrying the primary key the database assigned.
     *
     * @param entity the entity that was upserted.
     * @param generatedPrimaryKey the primary key the database assigned, or {@code null} when no key was retrieved.
     * @since 1.13
     */
    protected void fireAfterUpsert(E entity, @Nullable ID generatedPrimaryKey) {
        entityCallbacks.afterUpsert(entity, generatedPrimaryKey);
    }

    /**
     * Fires {@link EntityCallback#afterUpsert(List)} for a batch, pairing each entity with the primary key the
     * database assigned. The keys are reported in upsert order, which is the contract the batch upsert paths already
     * rely on; a batch that read no keys back passes an empty list and reports the entities as sent.
     *
     * @param entities the entities that were upserted, in upsert order.
     * @param generatedPrimaryKeys the assigned primary keys, in the same order.
     * @since 1.13
     */
    protected void fireAfterUpsert(List<E> entities, List<ID> generatedPrimaryKeys) {
        entityCallbacks.afterUpsert(entities, generatedPrimaryKeys);
    }

    /**
     * Fires {@link EntityCallback#beforeRemove(Entity)} on all registered callbacks.
     *
     * @param entity the entity about to be deleted.
     * @since 1.9
     */
    private void fireBeforeRemove(E entity) {
        entityCallbacks.beforeRemove(entity);
    }

    /**
     * Fires {@link EntityCallback#afterRemove(List)} on all registered callbacks with a list of one.
     *
     * @param entity the entity that was deleted.
     * @since 1.9
     */
    private void fireAfterRemove(E entity) {
        entityCallbacks.afterRemove(entity);
    }

    /**
     * Fires {@link EntityCallback#afterRemove(List)} for a batch.
     *
     * @param entities the entities that were deleted, in removal order.
     * @since 1.14
     */
    private void fireAfterRemove(List<E> entities) {
        entityCallbacks.afterRemove(entities);
    }

    /**
     * Returns the default batch size applied by the repository.
     *
     * @return the default batch size applied by the repository.
     * @since 1.5
     */
    @Override
    public int getDefaultBatchSize() {
        return defaultBatchSize;
    }

    protected E validateInsert(E entity) {
        if (isAutoGeneratedPrimaryKey()) {
            if (!model.isDefaultPrimaryKey(entity.id())) {
                throw new PersistenceException("Primary key must not be set when inserting %s with an auto-generated primary key. Either omit the primary key value, or use insert(entity, true) to explicitly provide it.".formatted(model.type().getSimpleName()));
            }
        } else {
            if (model.isDefaultPrimaryKey(entity.id())) {
                throw new PersistenceException("Primary key must be set when inserting %s because the primary key is not auto-generated. Provide a non-default primary key value.".formatted(model.type().getSimpleName()));
            }
        }
        return entity;
    }

    protected E validateInsert(E entity, boolean ignoreAutoGenerate) {
        if (!ignoreAutoGenerate) {
            return validateInsert(entity);
        }
        if (model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when inserting %s with ignoreAutoGenerate enabled.".formatted(model.type().getSimpleName()));
        }
        return entity;
    }

    protected E validateUpdate(E entity) {
        if (model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when updating %s. Provide a non-default primary key value to identify the row to update.".formatted(model.type().getSimpleName()));
        }
        return entity;
    }

    protected E validateDelete(E entity) {
        if (model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when deleting %s. Provide a non-default primary key value to identify the row to delete.".formatted(model.type().getSimpleName()));
        }
        return entity;
    }

    /**
     * Validates the entity for an upsert operation.
     *
     * <p>For non-auto-generated primary keys, the primary key must be set. Dialect-specific subclasses
     * may override this method to add additional validation logic.</p>
     *
     * @param entity the entity to validate.
     * @return the validated entity.
     * @since 1.9
     */
    protected E validateUpsert(E entity) {
        if (!isAutoGeneratedPrimaryKey() && model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when upserting %s because the primary key is not auto-generated. Provide a non-default primary key value.".formatted(model.type().getSimpleName()));
        }
        return entity;
    }

    /**
     * Returns the failure message for an upsert that produced an unexpected affected-row count.
     *
     * <p>The message wording is tailored to the batch size: a single-entity batch uses singular phrasing,
     * larger batches use plural phrasing.</p>
     *
     * @param batchSize the number of entities in the batch (1 for a single-entity upsert).
     * @return the failure message, including a hint to check the {@code @PK} generation strategy.
     * @since 1.11.3
     */
    protected String upsertFailureMessage(int batchSize) {
        String typeName = model.type().getSimpleName();
        if (batchSize == 1) {
            return "Upsert of %s failed: unexpected affected-row count. If the primary key is not auto-generated, verify that the @PK generation strategy is configured correctly.".formatted(typeName);
        }
        return "Batch upsert of %s failed: unexpected affected-row count for one or more entities. If the primary key is not auto-generated, verify that the @PK generation strategy is configured correctly.".formatted(typeName);
    }

    /**
     * Returns the entity model associated with this repository.
     *
     * @return the entity model.
     */
    @Override
    public Model<E, ID> model() {
        return model;
    }

    /**
     * Creates a new ref entity instance for the specified entity.
     *
     * <p>This method wraps a fully loaded entity in a lightweight reference. Although the complete entity is provided,
     * the returned ref retains only the primary key for identification. In this case, calling {@link Ref#fetch()} will
     * return the full entity (which is already loaded), ensuring a consistent API for accessing entity records on
     * demand. This approach supports lazy-loading scenarios where only the identifier is needed initially.</p>
     *
     * @param entity the entity to wrap in a ref.
     * @return a ref entity instance containing the primary key of the provided entity.
     * @since 1.3
     */
    @Override
    public Ref<E> ref(E entity) {
        return ormTemplate.ref(entity, entity.id());
    }

    /**
     * Unloads the given entity from memory by converting it into a lightweight ref containing only its primary key.
     *
     * <p>This method discards the full entity data and returns an attached ref that encapsulates just the primary key.
     * The actual record is not retained in memory, but can be retrieved on demand by calling {@link Ref#fetch()},
     * which will trigger a new database call.</p>
     *
     * <p>Unlike {@link Ref#unload()}, which returns a detached ref, this method returns an attached ref that can
     * re-fetch the entity from the database.</p>
     *
     * @param entity the entity to unload into a lightweight ref.
     * @return an attached ref containing only the primary key of the entity, allowing the full record to be fetched
     * again when needed.
     * @since 1.3
     */
    @Override
    public Ref<E> unload(E entity) {
        return ref(entity.id());
    }

    /**
     * Creates a new query builder for delete entities of the type managed by this repository.
     *
     * @return a new query builder for the entity type.
     */
    @Override
    public QueryBuilder<E, ?, ID> delete() {
        return deleteFrom(ormTemplate, model.type(), () -> model);
    }

    /**
     * Inserts an entity into the database.
     *
     * <p>This method adds a new entity to the database. It ensures that the entity is persisted according to the defined
     * database constraints and entity model. It's critical for the entity to be fully initialized as per the entity
     * model requirements.</p>
     *
     * @param entity the entity to insert. The entity must satisfy all model constraints.
     * @throws PersistenceException if the insert operation fails. This can happen due to a variety of reasons,
     *                              including database constraints violations, connectivity issues, or if the entity parameter is null.
     */
    @Override
    public void insert(E entity) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity);
        if (model.isJoinedInheritance()) {
            JoinedEntityHelper.insert(ormTemplate, model, entity);
            fireAfterInsert(entity);
            return;
        }
        var query = insertQuery(entity, false);
        if (query.executeUpdate() != 1) {
            throw new PersistenceException("Insert of %s failed. 0 rows were affected, which may indicate a trigger, constraint, or BEFORE INSERT hook prevented the row from being written.".formatted(model.type().getSimpleName()));
        }
        fireAfterInsert(entity);
    }

    /**
     * Inserts an entity into the database.
     *
     * <p>This method adds a new entity to the database. It ensures that the entity is persisted according to the defined
     * database constraints and entity model. It's critical for the entity to be fully initialized as per the entity
     * model requirements.</p>
     *
     * @param entity the entity to insert. The entity must satisfy all model constraints.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @throws PersistenceException if the insert operation fails. This can happen due to a variety of reasons,
     *                              including database constraints violations, connectivity issues, or if the entity parameter is null.
     */
    @Override
    public void insert(E entity, boolean ignoreAutoGenerate) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity, ignoreAutoGenerate);
        var query = insertQuery(entity, ignoreAutoGenerate);
        if (query.executeUpdate() != 1) {
            throw new PersistenceException("Insert of %s failed. 0 rows were affected, which may indicate a trigger, constraint, or BEFORE INSERT hook prevented the row from being written.".formatted(model.type().getSimpleName()));
        }
        fireAfterInsert(entity);
    }

    /**
     * Inserts an entity into the database and returns its primary key.
     *
     * <p>This method adds a new entity to the database and upon successful insertion, returns the primary key assigned to
     * the entity when the primary key is generated by the database (e.g., auto-incremented). Otherwise, if the primary
     * key is not generated by the database, the method returns an empty optional.</p>
     *
     * @param entity the entity to insert. The entity must satisfy all model constraints.
     * @return the generated primary key of the successfully inserted entity.
     * @throws PersistenceException if the insert operation fails for reasons such as database constraints violations,
     *                              connectivity issues, or if the entity parameter is null.
     */
    @Override
    public ID insertAndFetchId(E entity) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity);
        if (model.isJoinedInheritance()) {
            ID id = JoinedEntityHelper.insertAndFetchId(ormTemplate, model, entity);
            fireAfterInsert(entity, id);
            return id;
        }
        try (var query = ormTemplate.query(TemplateString.raw("""
                INSERT INTO \0
                VALUES \0""", model.type(), entity)).managed().prepare()) {
            if (query.executeUpdate() != 1) {
                throw new PersistenceException("Insert of %s failed. 0 rows were affected, which may indicate a trigger, constraint, or BEFORE INSERT hook prevented the row from being written.".formatted(model.type().getSimpleName()));
            }
            ID id = singleResult(isAutoGeneratedPrimaryKey()
                    ? query.getGeneratedKeys(model.primaryKeyType())
                    : Stream.of(entity.id()));
            fireAfterInsert(entity, id);
            return id;
        }
    }

    /**
     * Inserts a single entity into the database and returns the inserted entity with its current state.
     *
     * <p>This method inserts the provided entity into the database. Upon successful insertion, it returns
     * the entity as it exists in the database after the operation. This ensures that the returned entity
     * includes any modifications applied during the insertion process, such as generated primary keys,
     * default values, or other automatic changes triggered by the database.</p>
     *
     * @param entity the entity to be inserted. The entity must be non-null and contain valid data for insertion
     *               into the database.
     * @return the inserted entity, reflecting its state in the database after insertion. This includes any
     *         database-applied changes such as primary key assignments or default values.
     * @throws PersistenceException if the insertion operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public E insertAndFetch(E entity) {
        return entityCallbacks.fetchAndFire(() -> List.of(getById(insertAndFetchId(entity)))).getFirst();
    }

    /**
     * Returns the entity cache for the current transaction, if available.
     *
     * @return the entity cache for the current transaction, or empty if not available.
     * @since 1.7
     */
    protected Optional<EntityCache<E, ID>> entityCache() {
        //noinspection unchecked
        return currentTransactionContext()
                .map(ctx -> (EntityCache<E, ID>) ctx.entityCache(model().type(), cacheRetention));
    }

    /**
     * Returns the transaction context that is active for this repository's template, or empty when no transaction is
     * active. This is an observing lookup: it never starts a transaction.
     */
    private Optional<TransactionContext> currentTransactionContext() {
        return Optional.ofNullable(TransactionScope.peekContext(ormTemplate.transactionTemplateProvider()));
    }

    /**
     * Returns true if the transaction isolation level is {@code REPEATABLE_READ} or higher.
     *
     * <p>At {@code REPEATABLE_READ} and above, cached entities are returned when re-reading the same entity,
     * preserving entity identity. At lower isolation levels, fresh data is fetched.</p>
     *
     * @return true if isolation level is {@code REPEATABLE_READ} or higher, false otherwise.
     * @since 1.8
     */
    protected boolean isRepeatableRead() {
        return currentTransactionContext()
                .map(TransactionContext::isRepeatableRead)
                .orElse(false);
    }

    // Cache-first lookup methods.

    /**
     * {@inheritDoc}
     *
     * <p>This implementation first checks the entity cache (if available and isolation is REPEATABLE_READ or higher)
     * before querying the database.</p>
     */
    @Override
    public Optional<E> findById(ID id) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                Optional<E> cached = cache.get().get(id);
                if (cached.isPresent()) {
                    SqlInterceptorManager.notifyCacheHits(model().type(), 1);
                    return cached;
                }
            }
        }
        return super.findById(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation first checks the entity cache (if available and isolation is REPEATABLE_READ or higher)
     * before querying the database.</p>
     */
    @Override
    public E getById(ID id) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                Optional<E> cached = cache.get().get(id);
                if (cached.isPresent()) {
                    SqlInterceptorManager.notifyCacheHits(model().type(), 1);
                    return cached.get();
                }
            }
        }
        return super.getById(id);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation first checks the entity cache (if available and isolation is REPEATABLE_READ or higher)
     * before querying the database.</p>
     */
    @Override
    public Optional<E> findByRef(Ref<E> ref) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                //noinspection unchecked
                Optional<E> cached = cache.get().get((ID) ref.id());
                if (cached.isPresent()) {
                    SqlInterceptorManager.notifyCacheHits(model().type(), 1);
                    return cached;
                }
            }
        }
        return super.findByRef(ref);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation first checks the entity cache (if available and isolation is REPEATABLE_READ or higher)
     * before querying the database.</p>
     */
    @Override
    public E getByRef(Ref<E> ref) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                //noinspection unchecked
                Optional<E> cached = cache.get().get((ID) ref.id());
                if (cached.isPresent()) {
                    SqlInterceptorManager.notifyCacheHits(model().type(), 1);
                    return cached.get();
                }
            }
        }
        return super.getByRef(ref);
    }

    /**
     * Partitions a batch into cached entities and uncached items, fetching the uncached remainder with the given
     * function. Shared by the cache-aware batch select variants.
     */
    private <X> Stream<E> partitionByCache(EntityCache<E, ID> entityCache,
                                           List<X> batch,
                                           Function<X, ID> idOf,
                                           Function<List<X>, Stream<E>> fetchUncached) {
        var cached = new ArrayList<E>();
        var uncached = new ArrayList<X>();
        for (X item : batch) {
            Optional<E> cachedEntity = entityCache.get(idOf.apply(item));
            if (cachedEntity.isPresent()) {
                cached.add(cachedEntity.get());
            } else {
                uncached.add(item);
            }
        }
        SqlInterceptorManager.notifyCacheHits(model().type(), cached.size());
        if (uncached.isEmpty()) {
            return cached.stream();
        }
        return Stream.concat(cached.stream(), fetchUncached.apply(uncached));
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation partitions IDs into cached and uncached (when isolation is REPEATABLE_READ or higher),
     * returning cached entities immediately and only querying the database for uncached IDs.</p>
     */
    @Override
    protected Stream<E> selectByIdMaterialized(Stream<ID> ids) {
        if (!isRepeatableRead()) {
            return super.selectByIdMaterialized(ids);
        }
        var cache = entityCache();
        if (cache.isEmpty()) {
            return super.selectByIdMaterialized(ids);
        }
        EntityCache<E, ID> entityCache = cache.get();
        return chunked(ids, getDefaultChunkSize(), batch -> partitionByCache(entityCache, batch,
                Function.identity(), uncached -> select().whereId(uncached).getResultList().stream()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation partitions refs into cached and uncached (when isolation is REPEATABLE_READ or higher),
     * returning cached entities immediately and only querying the database for uncached refs.</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    protected Stream<E> selectByRefMaterialized(Stream<Ref<E>> refs) {
        if (!isRepeatableRead()) {
            return super.selectByRefMaterialized(refs);
        }
        var cache = entityCache();
        if (cache.isEmpty()) {
            return super.selectByRefMaterialized(refs);
        }
        EntityCache<E, ID> entityCache = cache.get();
        return chunked(refs, getDefaultChunkSize(), batch -> partitionByCache(entityCache, batch,
                ref -> (ID) ref.id(), uncached -> select().whereRef(uncached).getResultList().stream()));
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation partitions IDs into cached and uncached (when isolation is REPEATABLE_READ or higher),
     * returning cached entities immediately and only querying the database for uncached IDs.</p>
     */
    @Override
    public Stream<E> selectById(Stream<ID> ids, int chunkSize) {
        if (!isRepeatableRead()) {
            return super.selectById(ids, chunkSize);
        }
        var cache = entityCache();
        if (cache.isEmpty()) {
            return super.selectById(ids, chunkSize);
        }
        EntityCache<E, ID> entityCache = cache.get();
        return chunked(ids, chunkSize, batch -> {
            var cached = new ArrayList<E>();
            var uncached = new ArrayList<ID>();
            for (ID id : batch) {
                Optional<E> cachedEntity = entityCache.get(id);
                if (cachedEntity.isPresent()) {
                    cached.add(cachedEntity.get());
                } else {
                    uncached.add(id);
                }
            }
            SqlInterceptorManager.notifyCacheHits(model().type(), cached.size());
            if (uncached.isEmpty()) {
                return cached.stream();
            }
            return Stream.concat(cached.stream(),
                select().whereId(uncached).getResultStream());
        });
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation partitions refs into cached and uncached (when isolation is REPEATABLE_READ or higher),
     * returning cached entities immediately and only querying the database for uncached refs.</p>
     */
    @Override
    @SuppressWarnings("unchecked")
    public Stream<E> selectByRef(Stream<Ref<E>> refs, int chunkSize) {
        if (!isRepeatableRead()) {
            return super.selectByRef(refs, chunkSize);
        }
        var cache = entityCache();
        if (cache.isEmpty()) {
            return super.selectByRef(refs, chunkSize);
        }
        EntityCache<E, ID> entityCache = cache.get();
        return chunked(refs, chunkSize, batch -> {
            var cached = new ArrayList<E>();
            var uncached = new ArrayList<Ref<E>>();
            for (var ref : batch) {
                Optional<E> cachedEntity = entityCache.get((ID) ref.id());
                if (cachedEntity.isPresent()) {
                    cached.add(cachedEntity.get());
                } else {
                    uncached.add(ref);
                }
            }
            SqlInterceptorManager.notifyCacheHits(model().type(), cached.size());
            if (uncached.isEmpty()) {
                return cached.stream();
            }
            return Stream.concat(cached.stream(),
                select().whereRef(uncached).getResultStream());
        });
    }

    /**
     * Updates a single entity in the database.
     *
     * <p>This method updates the provided entity in the database, modifying its existing record to reflect the
     * current state of the entity. It is intended for cases where only one entity needs to be updated.</p>
     *
     * @param entity the entity to be updated. The entity must be non-null and contain valid data for updating
     *               its corresponding record in the database.
     * @throws PersistenceException if the update operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void update(E entity) {
        E e = fireBeforeUpdate(entity);
        if (model.isJoinedInheritance()) {
            validateUpdate(e);
            entityCache().ifPresent(cache -> {
                if (!model.isDefaultPrimaryKey(e.id())) {
                    cache.remove(e.id());
                }
            });
            JoinedEntityHelper.update(ormTemplate, model, e);
            fireAfterUpdate(e);
            return;
        }
        var entityCache = entityCache();
        var dirty = getDirty(e, entityCache.orElse(null));
        if (dirty.isEmpty()) {
            return;
        }
        validateUpdate(e);
        entityCache.ifPresent(cache -> {
            if (!model.isDefaultPrimaryKey(e.id())) {
                cache.remove(e.id());
            }
        });
        var query = updateQuery(e, dirty.get());
        int result = query.executeUpdate();
        if (query.isVersionAware() && result == 0) {
            throw new OptimisticLockException("Update of %s failed due to optimistic lock. The entity may have been modified or deleted by another transaction.".formatted(model.type().getSimpleName()));
        } else if (result != 1) {
            throw new PersistenceException("Update of %s failed. 0 rows were affected, possibly because the row does not exist or a constraint prevented the update.".formatted(model.type().getSimpleName()));
        }
        fireAfterUpdate(e);
    }

    /**
     * Updates a single entity in the database and returns the updated entity with its current state.
     *
     * <p>This method updates the provided entity in the database and, upon successful completion,
     * returns the entity as it exists in the database after the update operation. This ensures that the returned
     * entity reflects any modifications applied during the update process, such as updated timestamps,
     * versioning, or other automatic changes triggered by the database.</p>
     *
     * @param entity the entity to be updated. The entity must be non-null and contain valid data for updating
     *               its corresponding record in the database.
     * @return the updated entity, reflecting its state in the database after the update. This includes any
     *         database-applied changes such as modified timestamps or version numbers.
     * @throws PersistenceException if the update operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public E updateAndFetch(E entity) {
        return entityCallbacks.fetchAndFire(() -> {
            update(entity);
            return List.of(getById(entity.id()));
        }).getFirst();
    }

    private PersistenceException upsertNotAvailable() {
        return new PersistenceException("Upsert is not available for the default implementation.");
    }

    private void requireNonJoinedSealedEntity() {
        if (model.isJoinedInheritance()) {
            throw new PersistenceException("Upsert is not supported for joined sealed entity %s.".formatted(model.type().getSimpleName()));
        }
    }

    /**
     * Inserts or updates a single entity in the database.
     *
     * <p>This method performs an "upsert" operation on the provided entity. If the entity does not already exist
     * in the database, it will be inserted. If it does exist, it will be updated to reflect the current state of
     * the entity. This approach ensures that the entity is either created or brought up-to-date, depending on
     * its existence in the database.</p>
     *
     * <p>When the entity is routed to an {@link #update(Entity) update} or {@link #insert(Entity) insert}, the
     * corresponding lifecycle callbacks ({@code beforeUpdate}/{@code afterUpdate} or
     * {@code beforeInsert}/{@code afterInsert}) are fired. When the entity goes through the SQL-level upsert
     * path, the {@code beforeUpsert}/{@code afterUpsert} callbacks are fired instead.</p>
     *
     * @param entity the entity to be inserted or updated. The entity must be non-null and contain valid data
     *               for insertion or update in the database.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void upsert(E entity) {
        if (isUpsertUpdate(entity)) {
            update(entity);
            return;
        }
        if (isUpsertInsert(entity)) {
            insert(entity);
            return;
        }
        requireNonJoinedSealedEntity();
        entity = fireBeforeUpsert(entity);
        doUpsert(entity);
        fireAfterUpsert(entity);
    }

    /**
     * Performs the SQL-level upsert operation for a single entity, without lifecycle callbacks.
     *
     * <p>Dialect-specific subclasses must override this method to provide the actual upsert SQL logic
     * ({@code INSERT ... ON CONFLICT}, {@code INSERT ... ON DUPLICATE KEY} or {@code MERGE}, whichever the database
     * offers).</p>
     *
     * @param entity the entity to upsert.
     * @throws PersistenceException if the upsert operation is not available or fails.
     * @since 1.9
     */
    protected void doUpsert(E entity) {
        throw upsertNotAvailable();
    }

    /**
     * Inserts or updates a single entity in the database and returns its ID.
     *
     * <p>This method performs an "upsert" operation on the provided entity. If the entity does not already exist
     * in the database, it will be inserted; if it exists, it will be updated. Upon successful completion,
     * the method returns the ID of the entity as stored in the database. This approach ensures that the entity
     * is either created or brought up-to-date, depending on its existence in the database.</p>
     *
     * @param entity the entity to be inserted or updated. The entity must be non-null and contain valid data
     *               for insertion or update in the database.
     * @return the ID of the upserted entity, reflecting its identifier in the database.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public ID upsertAndFetchId(E entity) {
        if (isUpsertUpdate(entity)) {
            update(entity);
            return entity.id();
        }
        if (isUpsertInsert(entity)) {
            return insertAndFetchId(entity);
        }
        requireNonJoinedSealedEntity();
        entity = fireBeforeUpsert(entity);
        ID id = doUpsertAndFetchId(entity);
        fireAfterUpsert(entity, id);
        return id;
    }

    /**
     * Performs the SQL-level upsert operation for a single entity and returns its ID, without lifecycle callbacks.
     *
     * <p>Dialect-specific subclasses must override this method to provide the actual upsert SQL logic.</p>
     *
     * @param entity the entity to upsert.
     * @return the ID of the upserted entity.
     * @throws PersistenceException if the upsert operation is not available or fails.
     * @since 1.9
     */
    protected ID doUpsertAndFetchId(E entity) {
        throw upsertNotAvailable();
    }

    /**
     * Inserts or updates a single entity in the database and returns the entity with its current state.
     *
     * <p>This method performs an "upsert" operation on the provided entity. If the entity does not already exist
     * in the database, it will be inserted; if it exists, it will be updated. Upon successful completion,
     * the method returns the entity as it exists in the database after the upsert operation. This ensures that
     * the returned entity reflects any modifications applied during the upsert process, such as generated primary keys,
     * updated timestamps, or default values set by the database.</p>
     *
     * @param entity the entity to be inserted or updated. The entity must be non-null and contain valid data
     *               for insertion or update in the database.
     * @return the upserted entity, reflecting its current state in the database. This includes any
     *         database-applied changes, such as primary key assignments, default values, or timestamp updates.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public E upsertAndFetch(E entity) {
        return entityCallbacks.fetchAndFire(() -> List.of(getById(upsertAndFetchId(entity)))).getFirst();
    }

    /**
     * Deletes an entity from the database.
     *
     * <p>This method removes an existing entity from the database. The entity must exist in the database; if it does
     * not, a {@link PersistenceException} is thrown. Unlike {@link #removeById} and {@link #removeByRef}, this method
     * is strict rather than idempotent, because possessing the full entity implies the caller expects it to exist.</p>
     *
     * @param entity the entity to remove. The entity must exist in the database and should be correctly identified by
     *               its primary key.
     * @throws PersistenceException if the removal operation fails. Reasons for failure might include the entity not
     *                              being found in the database, violations of database constraints, connectivity
     *                              issues, or if the entity parameter is null.
     */
    @Override
    public void remove(E entity) {
        validateDelete(entity);
        fireBeforeRemove(entity);
        entityCache().ifPresent(cache -> {
            if (!model.isDefaultPrimaryKey(entity.id())) {
                cache.remove(entity.id());
            }
        });
        if (model.isJoinedInheritance()) {
            JoinedEntityHelper.remove(ormTemplate, model, entity);
            fireAfterRemove(entity);
            return;
        }
        // Don't use query builder to prevent WHERE IN clause.
        int result = removeQuery(entity).executeUpdate();
        if (result != 1) {
            throw new PersistenceException("Remove of %s failed. 0 rows were affected, possibly because the entity does not exist or a foreign key constraint prevents deletion.".formatted(model.type().getSimpleName()));
        }
        fireAfterRemove(entity);
    }

    /**
     * Removes an entity from the database based on its primary key.
     *
     * <p>This method ensures the entity with the given primary key is removed from the database. If the entity does
     * not exist, the operation completes successfully without error (idempotent behavior).</p>
     *
     * @param id the primary key of the entity to remove.
     * @throws PersistenceException if the removal operation fails due to violations of database constraints,
     *                              connectivity issues, or if the id parameter is null.
     */
    @Override
    public void removeById(ID id) {
        entityCache().ifPresent(cache -> cache.remove(id));
        if (model.isJoinedInheritance()) {
            JoinedEntityHelper.removeById(ormTemplate, model, id);
            return;
        }
        if (!versionedEntity && usePlans()) {
            var plan = deleteByKeyPlan();
            if (plan != null) {
                plan.bindValue(id).managed().executeUpdate();
                return;
            }
        }
        // Don't use query builder to prevent WHERE IN clause.
        ormTemplate.query(deleteByKeyStatement(id)).managed().executeUpdate();
    }

    /**
     * Removes an entity from the database by its reference.
     *
     * <p>This method ensures the entity identified by the given reference is removed from the database. If the entity
     * does not exist, the operation completes successfully without error (idempotent behavior).</p>
     *
     * @param ref the reference to the entity to remove.
     * @throws PersistenceException if the removal operation fails due to violations of database constraints,
     *                              connectivity issues, or if the ref parameter is null.
     */
    @Override
    public void removeByRef(Ref<E> ref) {
        //noinspection unchecked
        entityCache().ifPresent(cache -> cache.remove((ID) ref.id()));
        if (!versionedEntity && !model.isJoinedInheritance() && usePlans()) {
            var plan = deleteByKeyPlan();
            if (plan != null) {
                plan.bindValue(ref.id()).managed().executeUpdate();
                return;
            }
        }
        // Don't use query builder to prevent WHERE IN clause.
        ormTemplate.query(deleteByKeyStatement(ref)).managed().executeUpdate();
    }

    /**
     * Removes all entities from the database.
     *
     * <p>This method performs a bulk removal operation, removing all instances of the entities managed by this
     * repository from the database.</p>
     *
     * @throws PersistenceException if the bulk removal operation fails. Failure can occur for several reasons,
     *                              including but not limited to database access issues, transaction failures, or
     *                              underlying database constraints that prevent the removal of certain records.
     */
    @Override
    public void removeAll() {
        entityCache().ifPresent(EntityCache::clear);
        if (usePlans()) {
            var plan = removeAllPlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> ormTemplate.plan(TemplateString.raw("DELETE FROM \0", model.type())));
                removeAllPlan = plan;
            }
            if (plan != null) {
                plan.query()
                        .unsafe() // Omission of WHERE clause is intentional.
                        .managed()
                        .executeUpdate();
                return;
            }
        }
        // Don't use query builder to prevent WHERE IN clause.
        ormTemplate.query(TemplateString.raw("DELETE FROM \0", model.type()))
                .unsafe() // Omission of WHERE clause is intentional.
                .managed()
                .executeUpdate();
    }

    // List based methods.

    /**
     * Inserts a batch of joined (sealed/polymorphic) entities into both base and extension tables.
     *
     * <p>This method delegates to {@link JoinedEntityHelper#insertBatch} by default. Dialect-specific
     * implementations may override this method to handle database-specific limitations, such as a driver that
     * does not report {@code getGeneratedKeys()} after {@code executeBatch()}.</p>
     *
     * @param entities the entities to insert (already validated and transformed by callbacks).
     * @return the list of generated (or provided) primary keys, one per entity.
     * @throws PersistenceException if the insert fails.
     * @since 1.9
     */
    protected List<ID> insertJoinedBatch(List<E> entities) {
        return JoinedEntityHelper.insertBatch(ormTemplate, model, entities);
    }

    /**
     * Inserts a collection of entities into the database in batches.
     *
     * <p>This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.</p>
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     *                 be non-null and contain valid data for insertion.
     * @throws PersistenceException if the insertion operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void insert(Iterable<E> entities) {
        insert(toStream(entities), defaultBatchSize);
    }

    /**
     * Inserts a collection of entities into the database in batches.
     *
     * <p>This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.</p>
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     *                 be non-null and contain valid data for insertion.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @throws PersistenceException if the insertion operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void insert(Iterable<E> entities, boolean ignoreAutoGenerate) {
        insert(toStream(entities), defaultBatchSize, ignoreAutoGenerate);
    }

    /**
     * Inserts a collection of entities into the database in batches.
     *
     * <p>This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.</p>
     *
     * <p>Upon successful insertion, it returns the primary keys assigned to the entities when the primary keys are
     * generated by the database (e.g., auto-incremented). Otherwise, if the primary keys are not generated by the
     * database, the method returns an empty list.</p>
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     *                 be non-null and contain valid data for insertion.
     * @return the primary keys assigned to the entities when the primary keys are generated by the database,
     * @throws PersistenceException if the insertion operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public List<ID> insertAndFetchIds(Iterable<E> entities) {
        if (model.isJoinedInheritance()) {
            return chunked(toStream(entities), defaultBatchSize, batch -> {
                List<E> transformed = batch.stream().map(this::fireBeforeInsert).toList();
                transformed.forEach(this::validateInsert);
                List<ID> ids = insertJoinedBatch(transformed);
                fireAfterInsert(transformed, ids);
                return ids.stream();
            }).toList();
        }
        if (isAutoGeneratedPrimaryKey() && supportsMultiRowInsert()) {
            return chunked(toStream(entities), multiRowInsertChunkSize(),
                    batch -> insertAndFetchIdsMultiRow(batch).stream()
            ).toList();
        }
        try (var query = prepareInsertQuery()) {
            return chunked(toStream(entities), defaultBatchSize,
                    batch -> insertAndFetchIds(batch, query).stream()
            ).toList();
        }
    }

    /**
     * Chunk size for the multi-row insert path. Bounded by {@link #defaultBatchSize} and by the dialect's bind
     * parameter limit so that {@code rows × bound columns} never exceeds what a single statement can carry.
     */
    private int multiRowInsertChunkSize() {
        return multiRowInsertChunkSize(false);
    }

    private int multiRowInsertChunkSize(boolean ignoreAutoGenerate) {
        int boundColumns = (int) model.declaredColumns().stream()
                .filter(Column::insertable)
                .filter(column -> ignoreAutoGenerate || column.generation() == NONE)
                .count();
        if (boundColumns == 0) {
            return defaultBatchSize;
        }
        int maxRows = Math.max(1, ormTemplate.dialect().maxBindParameters() / boundColumns);
        return Math.min(defaultBatchSize, maxRows);
    }

    /**
     * Inserts a batch using a single multi-row {@code INSERT INTO t (...) VALUES (...),(...)} statement without
     * fetching generated keys, avoiding the per-row statements of {@code executeBatch} on dialects that support the
     * multi-row form.
     */
    private void insertMultiRow(List<E> batch, boolean ignoreAutoGenerate) {
        if (batch.isEmpty()) {
            return;
        }
        List<E> transformed = batch.stream()
                .map(this::fireBeforeInsert)
                .map(e -> validateInsert(e, ignoreAutoGenerate))
                .toList();
        var query = ormTemplate.query(raw("""
                INSERT INTO \0
                VALUES \0""",
                Templates.insert(model.type(), ignoreAutoGenerate),
                Templates.values(transformed, ignoreAutoGenerate))).managed();
        if (query.executeUpdate() != transformed.size()) {
            throw new PersistenceException("Multi-row insert of %s failed. The number of affected rows does not match the batch size."
                    .formatted(model.type().getSimpleName()));
        }
        fireAfterInsert(transformed, List.of());
    }

    /**
     * Returns whether the current dialect can fetch the generated keys of a multi-row insert in a single statement,
     * either through an {@code INSERT ... RETURNING} clause or through the driver's multi-row {@code getGeneratedKeys}.
     */
    private boolean supportsMultiRowInsert() {
        var dialect = ormTemplate.dialect();
        return dialect.supportsInsertReturning() || dialect.supportsMultiRowGeneratedKeys();
    }

    /**
     * Inserts a batch using a single multi-row {@code INSERT INTO t (...) VALUES (...),(...)} statement and returns the
     * generated primary keys in insertion order. Dialects with a {@code RETURNING} clause read the keys from the
     * result set; the rest rely on the driver returning every key from {@code getGeneratedKeys}. Either way this issues
     * one round trip instead of a per-row {@code executeBatch}.
     */
    private List<ID> insertAndFetchIdsMultiRow(List<E> batch) {
        if (batch.isEmpty()) {
            return List.of();
        }
        List<E> transformed = batch.stream()
                .map(this::fireBeforeInsert)
                .map(this::validateInsert)
                .toList();
        List<ID> ids = ormTemplate.dialect().supportsInsertReturning()
                ? insertMultiRowReturning(transformed)
                : insertMultiRowGeneratedKeys(transformed);
        if (ids.size() != transformed.size()) {
            throw new PersistenceException("Multi-row insert of %s returned %d keys for %d rows."
                    .formatted(model.type().getSimpleName(), ids.size(), transformed.size()));
        }
        fireAfterInsert(transformed, ids);
        return ids;
    }

    /**
     * Multi-row insert that reads the generated keys from an {@code INSERT ... VALUES (...),(...) RETURNING <pk>} result
     * set. The {@link Elements.Insert} element is built with {@code returningKeys} so the identity column is excluded
     * from the value tuples (the database generates it) without being registered as a JDBC generated key, leaving the
     * statement free to be executed as a query.
     */
    private List<ID> insertMultiRowReturning(List<E> transformed) {
        String returning = primaryKeyColumns.stream()
                .map(column -> ormTemplate.dialect().escape(column.name()))
                .collect(joining(", ", "RETURNING ", ""));
        var query = ormTemplate.query(raw("""
                INSERT INTO \0
                VALUES \0
                """ + returning,
                new Elements.Insert(model.type(), false, true),
                Templates.values(transformed)));
        try (var stream = query.getResultStream(model.primaryKeyType())) {
            return stream.toList();
        }
    }

    /**
     * Multi-row insert that reads the generated keys from the driver's {@code getGeneratedKeys} after a single
     * multi-row statement. Used by dialects whose driver returns every key for a multi-row insert but that have no
     * {@code RETURNING} clause.
     */
    private List<ID> insertMultiRowGeneratedKeys(List<E> transformed) {
        try (var query = ormTemplate.query(raw("""
                INSERT INTO \0
                VALUES \0""",
                Templates.insert(model.type()),
                Templates.values(transformed))).managed().prepare()) {
            if (query.executeUpdate() != transformed.size()) {
                throw new PersistenceException("Multi-row insert of %s failed. The number of affected rows does not match the batch size."
                        .formatted(model.type().getSimpleName()));
            }
            try (var stream = query.getGeneratedKeys(model.primaryKeyType())) {
                return stream.toList();
            }
        }
    }

    /**
     * Inserts a collection of entities into the database in batches.
     *
     * <p>This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.</p>
     *
     * <p>Upon successful insertion, it returns the entities that were inserted. The returned entities reflect the
     * state of the entities as they exist in the database after the insertion operation. This ensures that the
     * returned entities include any changes that might have been applied during the insertion process, such as
     * primary key, default values or triggers.</p>
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     *                 be non-null and contain valid data for insertion.
     * @return the entities that were inserted into the database.
     * @throws PersistenceException if the insertion operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public List<E> insertAndFetch(Iterable<E> entities) {
        return entityCallbacks.fetchAndFire(() -> findAllById(insertAndFetchIds(entities)));
    }

    /**
     * Updates a collection of entities in the database in batches.
     *
     * <p>This method processes the provided entities in batches to optimize updating of larger collections,
     * reducing database overhead and improving performance. Batch processing allows efficient handling of
     * bulk updates, minimizing memory and processing costs.</p>
     *
     * @param entities an iterable collection of entities to be updated. Each entity in the collection must
     *                 be non-null and contain valid, up-to-date data for modification in the database.
     * @throws PersistenceException if the update operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void update(Iterable<E> entities) {
        update(toStream(entities), defaultBatchSize);
    }

    /**
     * Updates a collection of entities in the database in batches and returns a list of the updated entities.
     *
     * <p>This method processes the provided entities in batches, optimizing performance for larger collections by
     * reducing database overhead. Upon successful update, it returns the entities as they exist in the database
     * after the update operation. This ensures that the returned entities reflect any modifications applied during
     * the update process, such as updated timestamps, versioning, or other automatic changes made by the database.</p>
     *
     * @param entities an iterable collection of entities to be updated. Each entity in the collection must be non-null
     *                 and contain valid data for modification in the database.
     * @return a list of entities reflecting their state in the database after the update. The order of entities in
     *         the returned list is not guaranteed to match the order of the input collection.
     * @throws PersistenceException if the update operation fails due to database issues, such as connectivity problems,
     *                              constraints violations, or invalid entity data.
     */
    @Override
    public List<E> updateAndFetch(Iterable<E> entities) {
        return entityCallbacks.fetchAndFire(() -> {
            update(entities);
            return findAllById(toStream(entities).map(Entity::id).toList());
        });
    }

    /**
     * Inserts or updates a collection of entities in the database in batches.
     *
     * <p>This method processes the provided entities in batches, optimizing performance for larger collections by
     * reducing database overhead. For each entity, the method performs an "upsert" operation, meaning it will insert
     * the entity if it does not already exist in the database, or update it if it does. This approach ensures that
     * the entities are either created or brought up-to-date, depending on their existence in the database.</p>
     *
     * @param entities an iterable collection of entities to be inserted or updated. Each entity in the collection must
     *                 be non-null and contain valid data for insertion or update in the database.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     *                              constraints violations, or invalid entity data.
     */
    @Override
    public void upsert(Iterable<E> entities) {
        upsert(toStream(entities), defaultBatchSize);
    }

    /**
     * Inserts or updates a collection of entities in the database in batches and returns a list of their IDs.
     *
     * <p>This method processes the provided entities in batches to optimize performance for larger collections,
     * reducing database overhead. For each entity, the method performs an "upsert" operation, inserting the entity
     * if it does not already exist in the database, or updating it if it does. Upon successful completion,
     * the method returns a list of the IDs of the upserted entities, reflecting their identifiers as stored
     * in the database.</p>
     *
     * @param entities an iterable collection of entities to be inserted or updated. Each entity in the collection
     *                 must be non-null and contain valid data for insertion or update in the database.
     * @return a list of IDs corresponding to the upserted entities. The order of IDs in the returned list
     *         is not guaranteed to match the order of the input collection.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     *                              constraints violations, or invalid entity data.
     */
    @Override
    public List<ID> upsertAndFetchIds(Iterable<E> entities) {
        requireNonJoinedSealedEntity();
        var updateQueries = new HashMap<Set<Metamodel<?, ?>>, PreparedQuery>();
        // Insert-routed entities read their keys back, so the insert partitions share one prepared insert statement
        // only where that statement can report them: a dialect with a multi-row key-returning form has a faster
        // statement to emit, and a driver that does not report a batch's keys cannot serve this path at all. Both
        // route through insertAndFetchIds instead, letting the dialect emit the form that carries the keys.
        boolean sharedInsertStatement = !(isAutoGeneratedPrimaryKey() && supportsMultiRowInsert())
                && ormTemplate.dialect().supportsBatchGeneratedKeys();
        var insertQuery = new LazySupplier<>(this::prepareInsertQuery);
        var upsertQuery = new LazySupplier<>(this::prepareUpsertQuery);
        try {
            var result = new ArrayList<ID>();
            var entityCache = entityCache();
            partitioned(toStream(entities), defaultBatchSize, entity -> {
                if (isUpsertUpdate(entity)) {
                    var dirty = getDirty(entity, entityCache.orElse(null));
                    if (dirty.isEmpty()) {
                        return UpsertNoOp.INSTANCE;
                    }
                    return new UpsertUpdateKey(dirty.get());
                }
                if (isUpsertInsert(entity)) {
                    return UpsertInsertKey.INSTANCE;
                }
                return UpsertSqlKey.INSTANCE;
            }, getMaxShapes(), new UpsertUpdateKey()).forEach(partition -> {
                switch (partition.key()) {
                    case UpsertNoOp ignore -> result.addAll(partition.chunk().stream().map(E::id).toList());
                    case UpsertInsertKey ignore -> result.addAll(sharedInsertStatement
                            ? insertAndFetchIds(partition.chunk(), insertQuery.get())
                            : insertAndFetchIds(partition.chunk()));
                    case UpsertSqlKey ignore -> {
                        List<E> batch = hasEntityCallbacks()
                                ? partition.chunk().stream().map(this::fireBeforeUpsert).toList()
                                : partition.chunk();
                        List<ID> ids = doUpsertAndFetchIdsBatch(batch, upsertQuery.get(), entityCache.orElse(null));
                        result.addAll(ids);
                        if (hasEntityCallbacks()) {
                            fireAfterUpsert(batch, ids);
                        }
                    }
                    case UpsertUpdateKey u -> {
                        List<E> batch = hasEntityCallbacks()
                                ? partition.chunk().stream().map(this::fireBeforeUpdate).toList()
                                : partition.chunk();
                        result.addAll(updateAndFetchIds(batch,
                                updateQueries.computeIfAbsent(u.fields(), this::prepareUpdateQuery),
                                entityCache.orElse(null)));
                    }
                }
            });
            return result;
        } finally {
            var streams = Stream.concat(updateQueries.values().stream(), insertQuery.value().stream());
            closeQuietly(Stream.concat(streams, upsertQuery.value().stream()));
        }
    }

    /**
     * Inserts or updates a collection of entities in the database in batches and returns a list of the upserted
     * entities.
     *
     * <p>This method processes the provided entities in batches, optimizing performance for larger collections
     * by reducing database overhead. For each entity, it performs an "upsert" operation, inserting the entity if it
     * does not already exist in the database, or updating it if it does. Upon successful completion, it returns
     * the entities as they exist in the database after the operation. This ensures that the returned entities reflect
     * any changes applied during the upsert process, such as generated primary keys, updated timestamps, or default
     * values set by the database.</p>
     *
     * @param entities an iterable collection of entities to be inserted or updated. Each entity in the collection
     *                 must be non-null and contain valid data for insertion or update in the database.
     * @return a list of upserted entities reflecting their current state in the database. The order of entities
     *         in the returned list is not guaranteed to match the order of the input collection.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     *                              constraints violations, or invalid entity data.
     */
    @Override
    public List<E> upsertAndFetch(Iterable<E> entities) {
        return entityCallbacks.fetchAndFire(() -> findAllById(upsertAndFetchIds(entities)));
    }

    /**
     * Removes a collection of entities from the database in batches.
     *
     * <p>This method processes the provided entities in batches to optimize performance when handling larger collections,
     * reducing database overhead. For each entity in the collection, the method removes the corresponding record from
     * the database, if it exists. Batch processing ensures efficient handling of removals, particularly for large data sets.</p>
     *
     * @param entities an iterable collection of entities to be removed. Each entity in the collection must be non-null
     *                 and represent a valid database record for removal.
     * @throws PersistenceException if the removal operation fails due to database issues, such as connectivity problems
     *                              or constraints violations.
     */
    @Override
    public void remove(Iterable<E> entities) {
        remove(toStream(entities), defaultBatchSize);
    }

    /**
     * Removes a collection of entities from the database in batches.
     *
     * <p>This method processes the provided entities in batches to optimize performance when handling larger collections,
     * reducing database overhead. For each entity in the collection, the method removes the corresponding record from
     * the database, if it exists. Batch processing ensures efficient handling of removals, particularly for large data sets.</p>
     *
     * @param refs an iterable collection of entities to be removed. Each entity in the collection must be non-null
     *             and represent a valid database record for removal.
     * @throws PersistenceException if the removal operation fails due to database issues, such as connectivity problems
     *                              or constraints violations.
     */
    @Override
    public void removeByRef(Iterable<Ref<E>> refs) {
        removeByRef(toStream(refs), defaultBatchSize);
    }

    // Stream based methods. These methods operate in multiple batches.

    /**
     * Inserts entities in a batch mode to optimize performance and reduce database load.
     *
     * <p>For large volumes of entities, this method processes the inserts in multiple batches to ensure efficient
     * handling and minimize the impact on database resources. This structured approach facilitates the management of
     * large-scale insert operations.</p>
     *
     * @param entities the entities to insert. Must not be null.
     * @throws PersistenceException if the insert fails due to database constraints, connectivity issues, or if the
     *                              entities parameter is null.
     */
    @Override
    public void insert(Stream<E> entities) {
        insert(entities, defaultBatchSize);
    }

    /**
     * Inserts entities in a batch mode to optimize performance and reduce database load.
     *
     * <p>For large volumes of entities, this method processes the inserts in multiple batches to ensure efficient
     * handling and minimize the impact on database resources. This structured approach facilitates the management of
     * large-scale insert operations.</p>
     *
     * @param entities the entities to insert. Must not be null.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     *                           provided primary key value. Use this flag only when intentionally providing the primary
     *                           key value (e.g., migrations, data exports).
     * @throws PersistenceException if the insert fails due to database constraints, connectivity issues, or if the
     *                              entities parameter is null.
     */
    @Override
    public void insert(Stream<E> entities, boolean ignoreAutoGenerate) {
        insert(entities, defaultBatchSize, ignoreAutoGenerate);
    }

    /**
     * Inserts a stream of entities into the database, with the insertion process divided into batches of the specified
     * size.
     *
     * <p>This method inserts entities provided in a stream and uses the specified batch size for the insertion
     * operation.  Batching the inserts can greatly enhance performance by minimizing the number of database
     * interactions, especially useful when dealing with large volumes of data.</p>
     *
     * @param entities a stream of entities to insert. Each entity must not be null and must conform to the model
     *                 constraints.
     * @param batchSize the size of the batches to use for the insertion operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @throws PersistenceException if there is an error during the insertion operation, such as a violation of database
     *                              constraints, connectivity issues, or if any entity in the stream is null.
     */
    @Override
    public void insert(Stream<E> entities, int batchSize) {
        if (model.isJoinedInheritance()) {
            chunked(entities, batchSize).forEach(batch -> {
                List<E> transformed = batch.stream().map(this::fireBeforeInsert).toList();
                transformed.forEach(this::validateInsert);
                insertJoinedBatch(transformed);
                fireAfterInsert(transformed, List.of());
            });
            return;
        }
        try (var query = prepareInsertQuery()) {
            chunked(entities, batchSize)
                    .forEach(batch -> insert(batch, query));
        }
    }

    /**
     * Inserts a stream of entities into the database, with the insertion process divided into batches of the specified
     * size.
     *
     * <p>This method inserts entities provided in a stream and uses the specified batch size for the insertion
     * operation.  Batching the inserts can greatly enhance performance by minimizing the number of database
     * interactions, especially useful when dealing with large volumes of data.</p>
     *
     * @param entities a stream of entities to insert. Each entity must not be null and must conform to the model
     *                 constraints.
     * @param batchSize the size of the batches to use for the insertion operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @throws PersistenceException if there is an error during the insertion operation, such as a violation of database
     *                              constraints, connectivity issues, or if any entity in the stream is null.
     */
    @Override
    public void insert(Stream<E> entities, int batchSize, boolean ignoreAutoGenerate) {
        if (model.isJoinedInheritance()) {
            chunked(entities, batchSize).forEach(batch -> {
                List<E> transformed = batch.stream().map(this::fireBeforeInsert).toList();
                transformed.forEach(e -> validateInsert(e, ignoreAutoGenerate));
                insertJoinedBatch(transformed);
                fireAfterInsert(transformed, List.of());
            });
            return;
        }
        if (supportsMultiRowInsert()) {
            // A dialect that can return keys from a multi-row insert necessarily supports the statement form; the
            // keyless path reuses that capability rather than introducing a separate one.
            int chunkSize = Math.min(batchSize, multiRowInsertChunkSize(ignoreAutoGenerate));
            chunked(entities, chunkSize).forEach(batch -> insertMultiRow(batch, ignoreAutoGenerate));
            return;
        }
        try (var query = prepareInsertQuery(ignoreAutoGenerate)) {
            chunked(entities, batchSize)
                    .forEach(batch -> insert(batch, query, ignoreAutoGenerate));
        }
    }

    protected PreparedQuery prepareInsertQuery() {
        return prepareInsertQuery(false);
    }

    protected PreparedQuery prepareInsertQuery(boolean ignoreAutoGenerate) {
        return ormTemplate.query(insertStatement(ormTemplate.createBindVars(), ignoreAutoGenerate)).managed().prepare();
    }

    protected void insert(List<E> batch, PreparedQuery query) {
        insert(batch, query, false);
    }

    @SuppressWarnings("SameParameterValue")
    protected void insert(List<E> batch, PreparedQuery query, boolean ignoreAutoGenerate) {
        if (batch.isEmpty()) {
            return;
        }
        List<E> transformed = executeInsertBatch(batch, query, ignoreAutoGenerate);
        fireAfterInsert(transformed, List.of());
    }

    protected List<ID> insertAndFetchIds(List<E> batch, PreparedQuery query) {
        return insertAndFetchIds(batch, query, false);
    }

    @SuppressWarnings("SameParameterValue")
    private List<ID> insertAndFetchIds(List<E> batch, PreparedQuery query, boolean ignoreAutoGenerate) {
        if (batch.isEmpty()) {
            return List.of();
        }
        List<E> transformed = executeInsertBatch(batch, query, ignoreAutoGenerate);
        List<ID> ids;
        if (isAutoGeneratedPrimaryKey() && !ignoreAutoGenerate) {
            try (var stream = query.getGeneratedKeys(model.primaryKeyType())) {
                ids = stream.toList();
            }
        } else {
            ids = transformed.stream().map(Entity::id).toList();
        }
        fireAfterInsert(transformed, ids);
        return ids;
    }

    /**
     * Runs the batch through the prepared insert: fires the before-insert callbacks, validates, executes the
     * batch and checks that every row was inserted.
     *
     * @return the entities as transformed by the before-insert callbacks.
     */
    private List<E> executeInsertBatch(List<E> batch, PreparedQuery query, boolean ignoreAutoGenerate) {
        List<E> transformed = batch.stream()
                .map(this::fireBeforeInsert)
                .toList();
        transformed.stream()
                .map(e -> validateInsert(e, ignoreAutoGenerate))
                .forEach(query::addBatch);
        int[] result = query.executeBatch();
        if (IntStream.of(result).anyMatch(r -> r != 1)) {
            throw new PersistenceException("Batch insert of %s failed. One or more rows were not affected.".formatted(model.type().getSimpleName()));
        }
        return transformed;
    }

    /**
     * Updates a stream of entities in the database using the default batch size.
     *
     * <p>This method updates entities provided in a stream, optimizing the update process by batching them
     * with a default size. This helps to reduce the number of database operations and can significantly improve
     * performance when updating large numbers of entities.</p>
     *
     * @param entities a stream of entities to update. Each entity must not be null, must already exist in the database,
     *                 and must conform to the model constraints.
     * @throws PersistenceException if there is an error during the update operation, such as a violation of database
     *                              constraints, connectivity issues, or if any entity in the stream is null.
     */
    @Override
    public void update(Stream<E> entities) {
        update(entities, defaultBatchSize);
    }

    private sealed interface PartitionKey {}
    private static final class NoOpKey implements PartitionKey {
        private static final NoOpKey INSTANCE = new NoOpKey();
    }
    private record UpdateKey(Set<Metamodel<?, ?>> fields) implements PartitionKey {
        UpdateKey() {
            this(Set.of()); // All fields.
        }
    }

    /**
     * Updates a stream of entities in the database, with the update process divided into batches of the specified size.
     *
     * <p>This method updates entities provided in a stream and uses the specified batch size for the update operation.
     * Batching the updates can greatly enhance performance by minimizing the number of database interactions,
     * especially useful when dealing with large volumes of data.</p>
     *
     * @param entities a stream of entities to update. Each entity must not be null, must already exist in the database,
     *                 and must conform to the model constraints.
     * @param batchSize the size of the batches to use for the update operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @throws PersistenceException if there is an error during the update operation, such as a violation of database
     *                              constraints, connectivity issues, or if any entity in the stream is null.
     */
    @Override
    public void update(Stream<E> entities, int batchSize) {
        if (model.isJoinedInheritance()) {
            Stream<E> mapped = hasEntityCallbacks()
                    ? entities.map(this::fireBeforeUpdate)
                    : entities;
            var entityCache = entityCache();
            chunked(mapped, batchSize).forEach(batch -> {
                batch.forEach(this::validateUpdate);
                entityCache.ifPresent(cache -> batch.stream()
                        .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                        .forEach(e -> cache.remove(e.id())));
                JoinedEntityHelper.updateBatch(ormTemplate, model, batch);
                fireAfterUpdate(batch);
            });
            return;
        }
        var updateQueries = new HashMap<Set<Metamodel<?, ?>>, PreparedQuery>();
        try {
            var entityCache = entityCache();
            Stream<E> mapped = hasEntityCallbacks()
                    ? entities.map(this::fireBeforeUpdate)
                    : entities;
            partitioned(mapped, batchSize, entity -> {
                var dirty = getDirty(entity, entityCache.orElse(null));
                if (dirty.isEmpty()) {
                    return NoOpKey.INSTANCE;
                }
                return new UpdateKey(dirty.get());
            }, dirtySupport.getMaxShapes(), new UpdateKey()).forEach(partition -> {
                switch (partition.key()) {
                    case NoOpKey ignore -> {}
                    case UpdateKey u -> update(partition.chunk(),
                            updateQueries.computeIfAbsent(u.fields(), this::prepareUpdateQuery),
                            entityCache.orElse(null));
                }
            });
        } finally {
            closeQuietly(updateQueries.values().stream());
        }
    }

    /**
     * Returns the dirty fields of the entity, an empty set if all fields must be regarded as dirty, or an empty
     * optional if the entity is not dirty.
     *
     * @param entity the entity to check.
     * @param cache the entity cache.
     * @return an optional containing the dirty fields, or an empty optional if the entity is not dirty.
     */
    protected Optional<Set<Metamodel<?, ?>>> getDirty(E entity, @Nullable EntityCache<E, ID> cache) {
        return dirtySupport.getDirty(entity, cache);
    }

    /**
     * Returns the maximum number of distinct update shapes that may be generated when dynamic updates are enabled.
     *
     * @return the maximum number of allowed update shapes.
     * @since 1.9
     */
    protected int getMaxShapes() {
        return dirtySupport.getMaxShapes();
    }

    // Single definition of each statement's template, parameterized only by the value source: a Data instance or
    // id/ref for the per-call path, or BindVars for a plan or a prepared batch. Every plan, per-call fallback, and
    // batch path builds its template here, so a plan cannot structurally diverge from the statement it replaces.

    private TemplateString updateStatement(Object valueSource, Set<Metamodel<?, ?>> fields) {
        var set = valueSource instanceof BindVars bindVars
                ? Templates.set(bindVars, fields)
                : Templates.set((Data) valueSource, fields);
        return TemplateString.raw("""
                UPDATE \0
                SET \0
                WHERE \0""", model.type(), set, valueSource);
    }

    private TemplateString insertStatement(Object valueSource, boolean ignoreAutoGenerate) {
        var values = valueSource instanceof BindVars bindVars
                ? Templates.values(bindVars, ignoreAutoGenerate)
                : Templates.values((Data) valueSource, ignoreAutoGenerate);
        return TemplateString.raw("""
                INSERT INTO \0
                VALUES \0""", Templates.insert(model.type(), ignoreAutoGenerate), values);
    }

    private TemplateString deleteByKeyStatement(Object valueSource) {
        return TemplateString.raw("""
                DELETE FROM \0
                WHERE \0""", model.type(), valueSource);
    }

    /**
     * Returns a managed query that updates the given entity's dirty {@code fields}.
     *
     * <p>The query is served from a plan cached per dirty shape whenever plans are supported and the shape bound
     * admits it; template processing then runs once per shape rather than once per update. Shapes beyond the bound,
     * and templates without bind variables support, use per-call template processing.</p>
     */
    private Query updateQuery(E entity, Set<Metamodel<?, ?>> fields) {
        if (usePlans()) {
            var plan = updatePlans.get(fields);
            if (plan == null && updatePlans.size() <= dirtySupport.getMaxShapes()) {
                plan = createUpdatePlan(fields);
            }
            if (plan != null) {
                return plan.bind(entity).managed();
            }
        }
        return ormTemplate.query(updateStatement(entity, fields)).managed();
    }

    private QueryPlan createUpdatePlan(Set<Metamodel<?, ?>> fields) {
        var plan = createPlanQuietly(() -> ormTemplate.plan(updateStatement(ormTemplate.createBindVars(), fields)));
        if (plan == null) {
            return null;
        }
        var existing = updatePlans.putIfAbsent(fields, plan);
        return existing != null ? existing : plan;
    }

    /**
     * Returns a managed query that inserts the given entity. The query is served from a cached plan whenever plans
     * are supported, so template processing runs once per repository rather than once per insert; see
     * {@link #usePlans()} for the guards.
     */
    private Query insertQuery(E entity, boolean ignoreAutoGenerate) {
        if (usePlans()) {
            var plan = ignoreAutoGenerate ? insertIgnoringAutoGeneratePlan : insertPlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> ormTemplate.plan(insertStatement(ormTemplate.createBindVars(), ignoreAutoGenerate)));
                if (ignoreAutoGenerate) {
                    insertIgnoringAutoGeneratePlan = plan;
                } else {
                    insertPlan = plan;
                }
            }
            if (plan != null) {
                return plan.bind(entity).managed();
            }
        }
        return ormTemplate.query(insertStatement(entity, ignoreAutoGenerate)).managed();
    }

    /**
     * Returns a managed query that deletes the given entity by its identifying columns. The query is served from a
     * cached plan whenever plans are supported, so template processing runs once per repository rather than once per
     * remove; see {@link #usePlans()} for the guards.
     */
    private Query removeQuery(E entity) {
        if (usePlans()) {
            var plan = removePlan;
            if (plan == null) {
                plan = createPlanQuietly(() -> ormTemplate.plan(deleteByKeyStatement(ormTemplate.createBindVars())));
                removePlan = plan;
            }
            if (plan != null) {
                return plan.bind(entity).managed();
            }
        }
        return ormTemplate.query(deleteByKeyStatement(entity)).managed();
    }

    /**
     * Returns the cached plan that deletes a row by its identifying columns, or {@code null} when plans are
     * unavailable. Shared by {@link #removeById(Object)} and {@link #removeByRef(Ref)}, which bind an id or ref.
     */
    private @Nullable QueryPlan deleteByKeyPlan() {
        var plan = removeByIdPlan;
        if (plan == null) {
            plan = createPlanQuietly(() -> ormTemplate.plan(deleteByKeyStatement(ormTemplate.createBindVars())));
            removeByIdPlan = plan;
        }
        return plan;
    }

    protected PreparedQuery prepareUpdateQuery(Set<Metamodel<?, ?>> fields) {
        return ormTemplate.query(updateStatement(ormTemplate.createBindVars(), fields)).managed().prepare();
    }

    protected void update(List<E> batch, PreparedQuery query, @Nullable EntityCache<E, ID> cache) {
        updateAndFetchIds(batch, query, cache);
    }

    protected List<ID> updateAndFetchIds(List<E> batch, PreparedQuery query, @Nullable EntityCache<E, ID> cache) {
        if (batch.isEmpty()) {
            return List.of();
        }
        batch.stream().map(this::validateUpdate).forEach(query::addBatch);
        if (cache != null) {
            batch.stream()
                    .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                    .forEach(e -> cache.remove(e.id()));
        }
        int[] result = query.executeBatch();
        if (query.isVersionAware() && IntStream.of(result).anyMatch(r -> r == 0)) {
            throw new OptimisticLockException("Batch update of %s failed due to optimistic lock. One or more entities may have been modified or deleted by another transaction.".formatted(model.type().getSimpleName()));
        } else if (IntStream.of(result).anyMatch(r -> r != 1)) {
            throw new PersistenceException("Batch update of %s failed. One or more rows were not affected.".formatted(model.type().getSimpleName()));
        }
        fireAfterUpdate(batch);
        return batch.stream().map(Entity::id).toList();
    }

    protected List<E> updateAndFetch(List<E> batch, PreparedQuery query, @Nullable EntityCache<E, ID> cache) {
        return findAllById(updateAndFetchIds(batch, query, cache));
    }

    /**
     * Inserts or updates a stream of entities in the database in batches.
     *
     * <p>This method processes the provided stream of entities in batches, performing an "upsert" operation on each.
     * For each entity, it will be inserted into the database if it does not already exist; if it does exist, it will
     * be updated to reflect the current state of the entity. Batch processing optimizes the performance of the
     * upsert operation for larger data sets by reducing database overhead.</p>
     *
     * @param entities a stream of entities to be inserted or updated. Each entity in the stream must be non-null
     *                 and contain valid data for insertion or update in the database.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void upsert(Stream<E> entities) {
        upsert(entities, defaultBatchSize);
    }

    // Partition keys for the upsert batch routing.
    private sealed interface UpsertPartitionKey {}
    private static final class UpsertNoOp implements UpsertPartitionKey {
        private static final UpsertNoOp INSTANCE = new UpsertNoOp();
    }
    private static final class UpsertInsertKey implements UpsertPartitionKey {
        private static final UpsertInsertKey INSTANCE = new UpsertInsertKey();
    }
    private static final class UpsertSqlKey implements UpsertPartitionKey {
        private static final UpsertSqlKey INSTANCE = new UpsertSqlKey();
    }
    private record UpsertUpdateKey(Set<Metamodel<?, ?>> fields) implements UpsertPartitionKey {
        UpsertUpdateKey() {
            this(Set.of()); // All fields.
        }
    }

    /**
     * Inserts or updates a stream of entities in the database in configurable batch sizes.
     *
     * <p>This method processes the provided stream of entities in batches, performing an "upsert" operation on each.
     * For each entity, it will be inserted if it does not already exist in the database, or updated if it does.
     * The batch size can be configured to control the number of entities processed in each database operation,
     * allowing for optimized performance and memory management based on system requirements.</p>
     *
     * @param entities a stream of entities to be inserted or updated. Each entity in the stream must be non-null
     *                 and contain valid data for insertion or update in the database.
     * @param batchSize the number of entities to process in each batch. A larger batch size may improve performance
     *                  but increase memory usage, while a smaller batch size may reduce memory usage but increase
     *                  the number of database operations.
     * @throws PersistenceException if the upsert operation fails due to database issues, such as connectivity
     *                              problems, constraints violations, or invalid entity data.
     */
    @Override
    public void upsert(Stream<E> entities, int batchSize) {
        requireNonJoinedSealedEntity();
        var updateQueries = new HashMap<Set<Metamodel<?, ?>>, PreparedQuery>();
        var insertQuery = new LazySupplier<>(this::prepareInsertQuery);
        var upsertQuery = new LazySupplier<>(this::prepareUpsertQuery);
        try {
            var entityCache = entityCache();
            partitioned(entities, batchSize, entity -> {
                if (isUpsertUpdate(entity)) {
                    var dirty = getDirty(entity, entityCache.orElse(null));
                    if (dirty.isEmpty()) {
                        return UpsertNoOp.INSTANCE;
                    }
                    return new UpsertUpdateKey(dirty.get());
                }
                if (isUpsertInsert(entity)) {
                    return UpsertInsertKey.INSTANCE;
                }
                return UpsertSqlKey.INSTANCE;
            }, getMaxShapes(), new UpsertUpdateKey()).forEach(partition -> {
                switch (partition.key()) {
                    case UpsertNoOp ignore -> {}
                    case UpsertInsertKey ignore -> insert(partition.chunk(), insertQuery.get());
                    case UpsertSqlKey ignore -> {
                        List<E> batch = hasEntityCallbacks()
                                ? partition.chunk().stream().map(this::fireBeforeUpsert).toList()
                                : partition.chunk();
                        doUpsertBatch(batch, upsertQuery.get(), entityCache.orElse(null));
                        if (hasEntityCallbacks()) {
                            fireAfterUpsert(batch, List.of());
                        }
                    }
                    case UpsertUpdateKey u -> {
                        List<E> batch = hasEntityCallbacks()
                                ? partition.chunk().stream().map(this::fireBeforeUpdate).toList()
                                : partition.chunk();
                        update(batch,
                                updateQueries.computeIfAbsent(u.fields(), this::prepareUpdateQuery),
                                entityCache.orElse(null));
                    }
                }
            });
        } finally {
            var streams = Stream.concat(updateQueries.values().stream(), insertQuery.value().stream());
            closeQuietly(Stream.concat(streams, upsertQuery.value().stream()));
        }
    }

    /**
     * Prepares the SQL-level upsert query. Dialect-specific subclasses must override this method to provide
     * the dialect-specific upsert SQL (e.g., {@code INSERT ... ON CONFLICT}, {@code MERGE}).
     *
     * @return the prepared upsert query.
     * @since 1.9
     */
    protected PreparedQuery prepareUpsertQuery() {
        throw upsertNotAvailable();
    }

    /**
     * Performs the SQL-level upsert for a batch of entities, without lifecycle callbacks.
     *
     * <p>Dialect-specific subclasses must override this method to provide the actual batch upsert SQL logic.</p>
     *
     * @param batch the batch of entities to upsert.
     * @param query the prepared upsert query.
     * @param cache the entity cache, or {@code null} if not available.
     * @since 1.9
     */
    protected void doUpsertBatch(List<E> batch, PreparedQuery query,
                                 @Nullable EntityCache<E, ID> cache) {
        throw upsertNotAvailable();
    }

    /**
     * Performs the SQL-level upsert for a batch of entities and returns their IDs, without lifecycle callbacks.
     *
     * <p>Dialect-specific subclasses must override this method to provide the actual batch upsert SQL logic.</p>
     *
     * @param batch the batch of entities to upsert.
     * @param query the prepared upsert query.
     * @param cache the entity cache, or {@code null} if not available.
     * @return the list of IDs of the upserted entities.
     * @since 1.9
     */
    protected List<ID> doUpsertAndFetchIdsBatch(List<E> batch, PreparedQuery query,
                                                @Nullable EntityCache<E, ID> cache) {
        throw upsertNotAvailable();
    }

    // Partition keys for the dialects' id-returning upsertAndFetchIds implementations.
    public sealed interface SeqPartitionKey {}
    public static final class SeqNoOpKey implements SeqPartitionKey {
        public static final SeqNoOpKey INSTANCE = new SeqNoOpKey();
        private SeqNoOpKey() {
        }
    }
    public static final class SeqUpsertKey implements SeqPartitionKey {
        public static final SeqUpsertKey INSTANCE = new SeqUpsertKey();
        private SeqUpsertKey() {
        }
    }
    public record SeqUpdateKey(Set<Metamodel<?, ?>> fields) implements SeqPartitionKey {
        public SeqUpdateKey() {
            this(Set.of()); // All fields.
        }
    }

    /**
     * The upsert-and-fetch-ids loop for dialects that fetch the ids through a single id-returning query
     * (such as {@code INSERT ... RETURNING} or {@code MERGE ... OUTPUT}) rather than batched prepared
     * statements: the entities are partitioned into no-op, upsert and per-shape update batches, and the
     * resulting ids come back in entity order.
     *
     * @param entities the entities to upsert.
     * @param upsertPartition executes one upsert partition and returns its ids; receives the partition's
     *                        chunk and the entity cache in effect for the operation. Dialects with the
     *                        standard cache-eviction semantics pass {@link #upsertPartitionAndFetchIds}.
     * @return the ids of the upserted entities.
     * @since 1.14
     */
    protected List<ID> upsertAndFetchIdsPartitioned(
            Iterable<E> entities,
            BiFunction<List<E>, Optional<EntityCache<E, ID>>, List<ID>> upsertPartition) {
        var updateQueries = new HashMap<Set<Metamodel<?, ?>>, PreparedQuery>();
        try {
            var result = new ArrayList<ID>();
            var entityCache = entityCache();
            partitioned(toStream(entities), defaultBatchSize, entity -> {
                if (isUpsertUpdate(entity)) {
                    var dirty = getDirty(entity, entityCache.orElse(null));
                    if (dirty.isEmpty()) {
                        return SeqNoOpKey.INSTANCE;
                    }
                    return new SeqUpdateKey(dirty.get());
                } else {
                    return SeqUpsertKey.INSTANCE;
                }
            }, getMaxShapes(), new SeqUpdateKey()).forEach(partition -> {
                switch (partition.key()) {
                    case SeqNoOpKey ignore -> result.addAll(partition.chunk().stream().map(E::id).toList());
                    case SeqUpsertKey ignore -> result.addAll(upsertPartition.apply(partition.chunk(), entityCache));
                    case SeqUpdateKey u -> {
                        List<E> batch = hasEntityCallbacks()
                                ? partition.chunk().stream().map(this::fireBeforeUpdate).toList()
                                : partition.chunk();
                        result.addAll(updateAndFetchIds(batch,
                                updateQueries.computeIfAbsent(u.fields(), this::prepareUpdateQuery),
                                entityCache.orElse(null)));
                    }
                }
            });
            return result;
        } finally {
            closeQuietly(updateQueries.values().stream());
        }
    }

    /**
     * Executes one upsert partition through a single id-returning query: fires the before-upsert callbacks,
     * evicts the entities with non-default primary keys from the cache (the upsert may update them), runs
     * the query and reports the returned ids to the after-upsert callbacks.
     *
     * @param chunk the partition's entities.
     * @param entityCache the entity cache in effect for the operation.
     * @param upsertQuery creates the id-returning upsert query for a batch.
     * @return the ids of the upserted entities.
     * @since 1.14
     */
    protected List<ID> upsertPartitionAndFetchIds(List<E> chunk,
                                                  Optional<EntityCache<E, ID>> entityCache,
                                                  Function<List<E>, Query> upsertQuery) {
        List<E> batch = hasEntityCallbacks()
                ? chunk.stream().map(this::fireBeforeUpsert).toList()
                : chunk;
        entityCache.ifPresent(cache -> batch.stream()
                .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                .forEach(e -> cache.remove(e.id())));
        List<ID> ids = upsertQuery.apply(batch).getResultList(model.primaryKeyType());
        fireAfterUpsert(batch, ids);
        return ids;
    }

    /**
     * Inserts the entity and fetches the generated primary key through the dialect's
     * {@code INSERT ... RETURNING} clause, for dialects whose driver cannot report sequence-generated
     * keys through {@code getGeneratedKeys()}.
     *
     * @param entity the entity to insert.
     * @return the primary key of the inserted entity.
     * @since 1.14
     */
    protected ID insertAndFetchIdReturning(E entity) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity);
        assert primaryKeyColumns.size() == 1;
        var primaryKeyColumn = primaryKeyColumns.getFirst();
        String pkName = primaryKeyColumn.qualifiedName(ormTemplate.dialect());
        try (var query = ormTemplate.query(TemplateString.raw("""
                INSERT INTO \0
                VALUES \0
                RETURNING %s""".formatted(pkName), model.type(), entity)).managed().prepare()) {
            ID id = query.getSingleResult(model.primaryKeyType());
            fireAfterInsert(entity, id);
            return id;
        }
    }

    /**
     * Removes a stream of entities from the database in batches.
     *
     * <p>This method processes the provided stream of entities in batches to optimize performance for larger
     * data sets, reducing database overhead during removal. For each entity in the stream, the method removes
     * the corresponding record from the database, if it exists. Batch processing allows efficient handling
     * of removals, particularly for large collections of entities.</p>
     *
     * @param entities a stream of entities to be removed. Each entity in the stream must be non-null and represent
     *                 a valid database record for removal.
     * @throws PersistenceException if the removal operation fails due to database issues, such as connectivity problems
     *                              or constraints violations.
     */
    @Override
    public void remove(Stream<E> entities) {
        remove(entities, defaultBatchSize);
    }

    /**
     * Removes a stream of entities from the database in configurable batch sizes.
     *
     * <p>This method processes the provided stream of entities in batches, with the size of each batch specified
     * by the `batchSize` parameter. This allows for control over the number of entities removed in each database
     * operation, optimizing performance and memory usage based on system requirements. For each entity in the
     * stream, the method removes the corresponding record from the database, if it exists.</p>
     *
     * @param entities a stream of entities to be removed. Each entity in the stream must be non-null and represent
     *                 a valid database record for removal.
     * @param batchSize the number of entities to process in each batch. Larger batch sizes may improve performance
     *                  but require more memory, while smaller batch sizes may reduce memory usage but increase
     *                  the number of database operations.
     * @throws PersistenceException if the removal operation fails due to database issues, such as connectivity problems
     *                              or constraints violations.
     */
    @Override
    public void remove(Stream<E> entities, int batchSize) {
        if (model.isJoinedInheritance()) {
            var entityCache = entityCache();
            chunked(entities, batchSize).forEach(batch -> {
                batch.forEach(e -> {
                    validateDelete(e);
                    fireBeforeRemove(e);
                });
                entityCache.ifPresent(cache -> batch.stream()
                        .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                        .forEach(e -> cache.remove(e.id())));
                JoinedEntityHelper.removeBatch(ormTemplate, model, batch);
                fireAfterRemove(batch);
            });
            return;
        }
        var bindVars = ormTemplate.createBindVars();
        var entityCache = entityCache();
        try (var query = ormTemplate.query(TemplateString.raw("""
                DELETE FROM \0
                WHERE \0""", model.type(), bindVars)).managed().prepare()) {
            chunked(entities, batchSize).forEach(chunk -> {
                chunk.stream().map(this::validateDelete).forEach(e -> {
                    fireBeforeRemove(e);
                    query.addBatch(e);
                });
                entityCache.ifPresent(cache -> chunk.stream()
                        .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                        .forEach(e -> cache.remove(e.id())));
                int[] result = query.executeBatch();
                if (IntStream.of(result).anyMatch(r -> r != 1)) {
                    throw new PersistenceException("Batch remove of %s failed. One or more rows were not affected.".formatted(model.type().getSimpleName()));
                }
                fireAfterRemove(chunk);
            });
        }
    }

    /**
     * Removes a stream of entities from the database in batches.
     *
     * <p>This method processes the provided stream of entities in batches to optimize performance for larger
     * data sets, reducing database overhead during removal. For each entity in the stream, the method removes
     * the corresponding record from the database, if it exists. Batch processing allows efficient handling
     * of removals, particularly for large collections of entities.</p>
     *
     * @param refs a stream of entities to be removed. Each entity in the stream must be non-null and represent
     *             a valid database record for removal.
     * @throws PersistenceException if the removal operation fails due to database issues, such as connectivity problems
     *                              or constraints violations.
     */
    @Override
    public void removeByRef(Stream<Ref<E>> refs) {
        removeByRef(refs, defaultBatchSize);
    }

    /**
     * Removes a stream of entities from the database in configurable batch sizes.
     *
     * <p>This method processes the provided stream of entities in batches, with the size of each batch specified
     * by the `batchSize` parameter. This allows for control over the number of entities removed in each database
     * operation, optimizing performance and memory usage based on system requirements. For each entity in the
     * stream, the method removes the corresponding record from the database, if it exists.</p>
     *
     * @param refs a stream of entities to be removed. Each entity in the stream must be non-null and represent
     *              valid database record for removal.
     * @param batchSize the number of entities to process in each batch. Larger batch sizes may improve performance
     *                  but require more memory, while smaller batch sizes may reduce memory usage but increase
     *                  the number of database operations.
     * @throws PersistenceException if the removal operation fails due to database issues, such as connectivity problems
     *                              or constraints violations.
     */
    @Override
    public void removeByRef(Stream<Ref<E>> refs, int batchSize) {
        if (model.isJoinedInheritance()) {
            var entityCache = entityCache();
            chunked(refs, batchSize).forEach(chunk -> {
                //noinspection unchecked
                entityCache.ifPresent(cache -> chunk.stream()
                        .filter(r -> !model.isDefaultPrimaryKey((ID) r.id()))
                        .forEach(r -> cache.remove((ID) r.id())));
                JoinedEntityHelper.removeBatchByRef(ormTemplate, model, chunk);
            });
            return;
        }
        var entityCache = entityCache();
        chunked(refs, batchSize).forEach(chunk -> {
            //noinspection unchecked
            entityCache.ifPresent(cache -> chunk.stream()
                    .filter(r -> !model.isDefaultPrimaryKey((ID) r.id()))
                    .forEach(r -> cache.remove((ID) r.id())));
            // Don't use query builder to prevent WHERE IN clause.
            ormTemplate.query(TemplateString.raw("""
                    DELETE FROM \0
                    WHERE \0""", model.type(), chunk))
                    .managed()
                    .executeUpdate();
        });
    }

    /**
     * Helper method to close queries without one exception preventing the others to close.
     */
    protected void closeQuietly(Stream<PreparedQuery> queries) {
        queries.forEach(query -> {
            try {
                query.close();
            } catch (Exception e) {
                LOGGER.debug("Failed to close prepared query.", e);
            }
        });
    }

    /**
     * Returns the single result of the stream.
     *
     * @param stream the stream to get the single result from.
     * @return the single result of the stream.
     * @param <T> the type of the result.
     * @throws NoResultException if there is no result.
     * @throws NonUniqueResultException if more than one result.
     */
    private <T> T singleResult(Stream<T> stream) {
        try (stream) {
            return stream
                    .reduce((a, b) -> {
                        throw new NonUniqueResultException("Expected single result for %s, but found more than one.".formatted(model.type().getSimpleName()));
                    }).orElseThrow(() -> new NoResultException("Expected single result, but found none."));
        }
    }
}
