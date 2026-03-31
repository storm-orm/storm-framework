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

import static st.orm.GenerationStrategy.IDENTITY;
import static st.orm.GenerationStrategy.NONE;
import static st.orm.GenerationStrategy.SEQUENCE;
import static st.orm.core.repository.impl.StreamSupport.partitioned;
import static st.orm.core.spi.Providers.deleteFrom;
import static st.orm.core.template.TemplateString.raw;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
import st.orm.core.spi.Providers;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.TransactionTemplate;
import st.orm.core.template.Column;
import st.orm.core.template.Model;
import st.orm.core.template.ORMTemplate;
import st.orm.core.template.PreparedQuery;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.TemplateString;
import st.orm.core.template.Templates;
import st.orm.core.template.impl.JoinedEntityHelper;
import st.orm.core.template.impl.LazySupplier;

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

    /**
     * Re-entrancy guard that prevents entity callbacks from firing recursively. When a callback performs database
     * operations (e.g., inserting an audit log), those operations must not trigger callbacks again. This guard is
     * static and thread-local so that it applies across all repository instances on the current thread.
     */
    private static final ThreadLocal<Boolean> CALLBACK_ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    private final TransactionTemplate TRANSACTION_TEMPLATE = Providers.getTransactionTemplate();

    protected final int defaultBatchSize;
    protected final List<Column> primaryKeyColumns;
    protected final GenerationStrategy generationStrategy;
    private final DirtySupport<E, ID> dirtySupport;
    private final CacheRetention cacheRetention;
    private final List<EntityCallback<E>> entityCallbacks;

    public EntityRepositoryImpl(@Nonnull ORMTemplate ormTemplate, @Nonnull Model<E, ID> model) {
        super(ormTemplate, model);
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
        this.cacheRetention = CacheRetention.fromConfig(ormTemplate.config());
        this.entityCallbacks = resolveCallbacks(ormTemplate.entityCallbacks(), model.type());
        EntityCacheMetrics.getInstance().registerEntity(model.type().getName(), cacheRetention.name());
        LOGGER.debug("{}: cacheRetention={}", model.type().getSimpleName(), cacheRetention);
    }

    /**
     * Resolves the entity callbacks that match the given entity type, filtering by the generic type parameter
     * declared on each {@link EntityCallback}.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Entity<ID>, ID> List<EntityCallback<E>> resolveCallbacks(
            @Nonnull List<EntityCallback<?>> callbacks, @Nonnull Class<E> entityType) {
        List<EntityCallback<E>> result = new ArrayList<>();
        for (EntityCallback<?> callback : callbacks) {
            Class<?> callbackType = resolveCallbackEntityType(callback.getClass());
            if (callbackType.isAssignableFrom(entityType)) {
                result.add((EntityCallback<E>) callback);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Resolves the entity type parameter {@code E} from a concrete {@link EntityCallback} class by inspecting
     * its generic interface hierarchy.
     */
    private static Class<?> resolveCallbackEntityType(@Nonnull Class<?> clazz) {
        for (Type iface : clazz.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt) {
                if (pt.getRawType() == EntityCallback.class) {
                    return extractClass(pt.getActualTypeArguments()[0]);
                }
                if (pt.getRawType() instanceof Class<?> raw && EntityCallback.class.isAssignableFrom(raw)) {
                    Class<?> resolved = resolveCallbackEntityType(raw);
                    if (resolved != Entity.class) {
                        return resolved;
                    }
                }
            } else if (iface instanceof Class<?> raw && EntityCallback.class.isAssignableFrom(raw)) {
                Class<?> resolved = resolveCallbackEntityType(raw);
                if (resolved != Entity.class) {
                    return resolved;
                }
            }
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            return resolveCallbackEntityType(superclass);
        }
        return Entity.class;
    }

    private static Class<?> extractClass(@Nonnull Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        return Entity.class;
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
    protected boolean isUpsertUpdate(@Nonnull E entity) {
        return isAutoGeneratedPrimaryKey() && !model.isDefaultPrimaryKey(entity.id());
    }

    /**
     * Returns {@code true} if the given entity should be routed to {@link #insert(Entity)} when
     * {@link #upsert(Entity)} is called. This is the case for databases that cannot perform a
     * SQL-level upsert (MERGE) with auto-generated primary keys (e.g., Oracle, SQL Server).
     *
     * <p>The default implementation returns {@code false}. Dialect-specific subclasses override
     * this method to return {@code true} when appropriate.</p>
     *
     * @param entity the entity to check.
     * @return {@code true} if the upsert should be routed to insert.
     * @since 1.9
     */
    protected boolean isUpsertInsert(@Nonnull E entity) {
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
    private E fireBeforeInsert(E entity) {
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return entity;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                entity = callback.beforeInsert(entity);
            }
            return entity;
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
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
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return entity;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                entity = callback.beforeUpdate(entity);
            }
            return entity;
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Fires {@link EntityCallback#afterInsert(Entity)} on all registered callbacks.
     *
     * <p>The entity passed to this method is the entity as it was sent to the database (after
     * {@link #fireBeforeInsert(Entity) beforeInsert} transformation), not the entity as it exists in the database
     * after the operation. In particular, database-generated values such as auto-incremented primary keys, version
     * increments, or default column values are not reflected.</p>
     *
     * @param entity the entity that was inserted.
     * @since 1.9
     */
    private void fireAfterInsert(E entity) {
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                callback.afterInsert(entity);
            }
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Fires {@link EntityCallback#afterUpdate(Entity)} on all registered callbacks.
     *
     * <p>The entity passed to this method is the entity as it was sent to the database (after
     * {@link #fireBeforeUpdate(Entity) beforeUpdate} transformation), not the entity as it exists in the database
     * after the operation. In particular, database-side changes such as version increments or trigger-applied
     * modifications are not reflected.</p>
     *
     * @param entity the entity that was updated.
     * @since 1.9
     */
    private void fireAfterUpdate(E entity) {
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                callback.afterUpdate(entity);
            }
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Returns {@code true} if there are entity callbacks registered.
     *
     * @return {@code true} if entity callbacks are registered.
     * @since 1.9
     */
    protected boolean hasEntityCallbacks() {
        return !entityCallbacks.isEmpty() && !CALLBACK_ACTIVE.get();
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
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return entity;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                entity = callback.beforeUpsert(entity);
            }
            return entity;
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Fires {@link EntityCallback#afterUpsert(Entity)} on all registered callbacks.
     *
     * <p>This method is only called on the SQL-level upsert path. When an upsert is routed to
     * {@link #insert(Entity)} or {@link #update(Entity)}, the corresponding {@code afterInsert} or
     * {@code afterUpdate} callbacks are fired instead.</p>
     *
     * <p>The entity passed to this method is the entity as it was sent to the database (after
     * {@link #fireBeforeUpsert(Entity) beforeUpsert} transformation), not the entity as it exists in the database
     * after the operation.</p>
     *
     * @param entity the entity that was upserted.
     * @since 1.9
     */
    protected void fireAfterUpsert(E entity) {
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                callback.afterUpsert(entity);
            }
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Fires {@link EntityCallback#beforeDelete(Entity)} on all registered callbacks.
     *
     * @param entity the entity about to be deleted.
     * @since 1.9
     */
    private void fireBeforeDelete(E entity) {
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                callback.beforeDelete(entity);
            }
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Fires {@link EntityCallback#afterDelete(Entity)} on all registered callbacks.
     *
     * @param entity the entity that was deleted.
     * @since 1.9
     */
    private void fireAfterDelete(E entity) {
        if (entityCallbacks.isEmpty() || CALLBACK_ACTIVE.get()) {
            return;
        }
        CALLBACK_ACTIVE.set(Boolean.TRUE);
        try {
            for (EntityCallback<E> callback : entityCallbacks) {
                callback.afterDelete(entity);
            }
        } finally {
            CALLBACK_ACTIVE.set(Boolean.FALSE);
        }
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

    protected E validateInsert(@Nonnull E entity) {
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

    protected E validateInsert(@Nonnull E entity, boolean ignoreAutoGenerate) {
        if (!ignoreAutoGenerate) {
            return validateInsert(entity);
        }
        if (model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when inserting %s with ignoreAutoGenerate enabled.".formatted(model.type().getSimpleName()));
        }
        return entity;
    }

    protected E validateUpdate(@Nonnull E entity) {
        if (model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when updating %s. Provide a non-default primary key value to identify the row to update.".formatted(model.type().getSimpleName()));
        }
        return entity;
    }

    protected E validateDelete(@Nonnull E entity) {
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
    protected E validateUpsert(@Nonnull E entity) {
        if (!isAutoGeneratedPrimaryKey() && model.isDefaultPrimaryKey(entity.id())) {
            throw new PersistenceException("Primary key must be set when upserting %s because the primary key is not auto-generated. Provide a non-default primary key value.".formatted(model.type().getSimpleName()));
        }
        return entity;
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
    public Ref<E> ref(@Nonnull E entity) {
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
    public Ref<E> unload(@Nonnull E entity) {
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
    public void insert(@Nonnull E entity) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity);
        if (model.isJoinedInheritance()) {
            JoinedEntityHelper.insert(ormTemplate, model, entity);
            fireAfterInsert(entity);
            return;
        }
        var query = ormTemplate.query(TemplateString.raw("""
                INSERT INTO \0
                VALUES \0""", model.type(), entity))
                .managed();
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
    public void insert(@Nonnull E entity, boolean ignoreAutoGenerate) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity, ignoreAutoGenerate);
        var query = ormTemplate.query(TemplateString.raw("""
                INSERT INTO \0
                VALUES \0""", Templates.insert(model.type(), ignoreAutoGenerate), Templates.values(entity, ignoreAutoGenerate)))
                .managed();
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
    public ID insertAndFetchId(@Nonnull E entity) {
        entity = fireBeforeInsert(entity);
        validateInsert(entity);
        if (model.isJoinedInheritance()) {
            ID id = JoinedEntityHelper.insertAndFetchId(ormTemplate, model, entity);
            fireAfterInsert(entity);
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
            fireAfterInsert(entity);
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
    public E insertAndFetch(@Nonnull E entity) {
        return getById(insertAndFetchId(entity));
    }

    /**
     * Returns the entity cache for the current transaction, if available.
     *
     * @return the entity cache for the current transaction, or empty if not available.
     * @since 1.7
     */
    protected Optional<EntityCache<E, ID>> entityCache() {
        //noinspection unchecked
        return TRANSACTION_TEMPLATE.currentContext()
                .map(ctx -> (EntityCache<E, ID>) ctx.entityCache(model().type(), cacheRetention));
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
        return TRANSACTION_TEMPLATE.currentContext()
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
    public Optional<E> findById(@Nonnull ID id) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                Optional<E> cached = cache.get().get(id);
                if (cached.isPresent()) {
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
    public E getById(@Nonnull ID id) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                Optional<E> cached = cache.get().get(id);
                if (cached.isPresent()) {
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
    public Optional<E> findByRef(@Nonnull Ref<E> ref) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                //noinspection unchecked
                Optional<E> cached = cache.get().get((ID) ref.id());
                if (cached.isPresent()) {
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
    public E getByRef(@Nonnull Ref<E> ref) {
        if (isRepeatableRead()) {
            var cache = entityCache();
            if (cache.isPresent()) {
                //noinspection unchecked
                Optional<E> cached = cache.get().get((ID) ref.id());
                if (cached.isPresent()) {
                    return cached.get();
                }
            }
        }
        return super.getByRef(ref);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation partitions IDs into cached and uncached (when isolation is REPEATABLE_READ or higher),
     * returning cached entities immediately and only querying the database for uncached IDs.</p>
     */
    @Override
    public Stream<E> selectById(@Nonnull Stream<ID> ids, int chunkSize) {
        if (!isRepeatableRead()) {
            return super.selectById(ids, chunkSize);
        }
        var cache = entityCache();
        if (cache.isEmpty()) {
            return super.selectById(ids, chunkSize);
        }
        EntityCache<E, ID> entityCache = cache.get();
        return chunked(ids, chunkSize, batch -> {
            List<E> cached = new ArrayList<>();
            List<ID> uncached = new ArrayList<>();
            for (ID id : batch) {
                Optional<E> cachedEntity = entityCache.get(id);
                if (cachedEntity.isPresent()) {
                    cached.add(cachedEntity.get());
                } else {
                    uncached.add(id);
                }
            }
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
    public Stream<E> selectByRef(@Nonnull Stream<Ref<E>> refs, int chunkSize) {
        if (!isRepeatableRead()) {
            return super.selectByRef(refs, chunkSize);
        }
        var cache = entityCache();
        if (cache.isEmpty()) {
            return super.selectByRef(refs, chunkSize);
        }
        EntityCache<E, ID> entityCache = cache.get();
        return chunked(refs, chunkSize, batch -> {
            List<E> cached = new ArrayList<>();
            List<Ref<E>> uncached = new ArrayList<>();
            for (Ref<E> ref : batch) {
                Optional<E> cachedEntity = entityCache.get((ID) ref.id());
                if (cachedEntity.isPresent()) {
                    cached.add(cachedEntity.get());
                } else {
                    uncached.add(ref);
                }
            }
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
    public void update(@Nonnull E entity) {
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
        var query = ormTemplate.query(TemplateString.raw("""
                UPDATE \0
                SET \0
                WHERE \0""", model.type(), Templates.set(e, dirty.get()), e))
                .managed();
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
    public E updateAndFetch(@Nonnull E entity) {
        update(entity);
        return getById(entity.id());
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
    public void upsert(@Nonnull E entity) {
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
     * (e.g., {@code INSERT ... ON CONFLICT} for PostgreSQL, {@code INSERT ... ON DUPLICATE KEY} for MySQL,
     * {@code MERGE} for Oracle/SQL Server).</p>
     *
     * @param entity the entity to upsert.
     * @throws PersistenceException if the upsert operation is not available or fails.
     * @since 1.9
     */
    protected void doUpsert(@Nonnull E entity) {
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
    public ID upsertAndFetchId(@Nonnull E entity) {
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
        fireAfterUpsert(entity);
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
    protected ID doUpsertAndFetchId(@Nonnull E entity) {
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
    public E upsertAndFetch(@Nonnull E entity) {
        return getById(upsertAndFetchId(entity));
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
    public void remove(@Nonnull E entity) {
        validateDelete(entity);
        fireBeforeDelete(entity);
        entityCache().ifPresent(cache -> {
            if (!model.isDefaultPrimaryKey(entity.id())) {
                cache.remove(entity.id());
            }
        });
        if (model.isJoinedInheritance()) {
            JoinedEntityHelper.remove(ormTemplate, model, entity);
            fireAfterDelete(entity);
            return;
        }
        // Don't use query builder to prevent WHERE IN clause.
        int result = ormTemplate.query(TemplateString.raw("""
                DELETE FROM \0
                WHERE \0""", model.type(), entity))
                .managed()
                .executeUpdate();
        if (result != 1) {
            throw new PersistenceException("Remove of %s failed. 0 rows were affected, possibly because the entity does not exist or a foreign key constraint prevents deletion.".formatted(model.type().getSimpleName()));
        }
        fireAfterDelete(entity);
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
    public void removeById(@Nonnull ID id) {
        entityCache().ifPresent(cache -> cache.remove(id));
        if (model.isJoinedInheritance()) {
            JoinedEntityHelper.removeById(ormTemplate, model, id);
            return;
        }
        // Don't use query builder to prevent WHERE IN clause.
        ormTemplate.query(TemplateString.raw("""
                DELETE FROM \0
                WHERE \0""", model.type(), id))
                .managed()
                .executeUpdate();
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
    public void removeByRef(@Nonnull Ref<E> ref) {
        //noinspection unchecked
        entityCache().ifPresent(cache -> cache.remove((ID) ref.id()));
        // Don't use query builder to prevent WHERE IN clause.
        ormTemplate.query(TemplateString.raw("""
                DELETE FROM \0
                WHERE \0""", model.type(), ref))
                .managed()
                .executeUpdate();
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
     * implementations may override this method to handle database-specific limitations, such as SQL Server's
     * lack of support for {@code getGeneratedKeys()} after {@code executeBatch()}.</p>
     *
     * @param entities the entities to insert (already validated and transformed by callbacks).
     * @return the list of generated (or provided) primary keys, one per entity.
     * @throws PersistenceException if the insert fails.
     * @since 1.9
     */
    protected List<ID> insertJoinedBatch(@Nonnull List<E> entities) {
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
    public void insert(@Nonnull Iterable<E> entities) {
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
    public void insert(@Nonnull Iterable<E> entities, boolean ignoreAutoGenerate) {
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
    public List<ID> insertAndFetchIds(@Nonnull Iterable<E> entities) {
        if (model.isJoinedInheritance()) {
            return chunked(toStream(entities), defaultBatchSize, batch -> {
                List<E> transformed = batch.stream().map(this::fireBeforeInsert).toList();
                transformed.forEach(this::validateInsert);
                List<ID> ids = insertJoinedBatch(transformed);
                transformed.forEach(this::fireAfterInsert);
                return ids.stream();
            }).toList();
        }
        try (var query = prepareInsertQuery()) {
            return chunked(toStream(entities), defaultBatchSize,
                    batch -> insertAndFetchIds(batch, query).stream()
            ).toList();
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
    public List<E> insertAndFetch(@Nonnull Iterable<E> entities) {
        return findAllById(insertAndFetchIds(entities));
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
    public void update(@Nonnull Iterable<E> entities) {
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
    public List<E> updateAndFetch(@Nonnull Iterable<E> entities) {
        update(entities);
        return findAllById(toStream(entities).map(Entity::id).toList());
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
    public void upsert(@Nonnull Iterable<E> entities) {
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
    public List<ID> upsertAndFetchIds(@Nonnull Iterable<E> entities) {
        requireNonJoinedSealedEntity();
        Map<Set<Metamodel<?, ?>>, PreparedQuery> updateQueries = new HashMap<>();
        LazySupplier<PreparedQuery> insertQuery = isAutoGeneratedPrimaryKey()
                ? new LazySupplier<>(this::prepareInsertQuery) : null;
        LazySupplier<PreparedQuery> upsertQuery = new LazySupplier<>(this::prepareUpsertQuery);
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
                    case UpsertInsertKey ignore -> result.addAll(insertAndFetchIds(partition.chunk(), insertQuery.get()));
                    case UpsertSqlKey ignore -> {
                        List<E> batch = !entityCallbacks.isEmpty()
                                ? partition.chunk().stream().map(this::fireBeforeUpsert).toList()
                                : partition.chunk();
                        result.addAll(doUpsertAndFetchIdsBatch(batch, upsertQuery.get(), entityCache.orElse(null)));
                        if (!entityCallbacks.isEmpty()) {
                            batch.forEach(this::fireAfterUpsert);
                        }
                    }
                    case UpsertUpdateKey u -> {
                        List<E> batch = !entityCallbacks.isEmpty()
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
            var streams = updateQueries.values().stream();
            if (insertQuery != null) {
                streams = Stream.concat(streams, insertQuery.value().stream());
            }
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
    public List<E> upsertAndFetch(@Nonnull Iterable<E> entities) {
        return findAllById(upsertAndFetchIds(entities));
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
    public void remove(@Nonnull Iterable<E> entities) {
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
    public void removeByRef(@Nonnull Iterable<Ref<E>> refs) {
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
    public void insert(@Nonnull Stream<E> entities) {
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
    public void insert(@Nonnull Stream<E> entities, boolean ignoreAutoGenerate) {
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
    public void insert(@Nonnull Stream<E> entities, int batchSize) {
        if (model.isJoinedInheritance()) {
            chunked(entities, batchSize).forEach(batch -> {
                List<E> transformed = batch.stream().map(this::fireBeforeInsert).toList();
                transformed.forEach(this::validateInsert);
                insertJoinedBatch(transformed);
                transformed.forEach(this::fireAfterInsert);
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
    public void insert(@Nonnull Stream<E> entities, int batchSize, boolean ignoreAutoGenerate) {
        if (model.isJoinedInheritance()) {
            chunked(entities, batchSize).forEach(batch -> {
                List<E> transformed = batch.stream().map(this::fireBeforeInsert).toList();
                transformed.forEach(e -> validateInsert(e, ignoreAutoGenerate));
                insertJoinedBatch(transformed);
                transformed.forEach(this::fireAfterInsert);
            });
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
        var bindVars = ormTemplate.createBindVars();
        return ormTemplate.query(raw("""
                INSERT INTO \0
                VALUES \0""",
                Templates.insert(model.type(), ignoreAutoGenerate),
                Templates.values(bindVars, ignoreAutoGenerate))).managed().prepare();
    }

    protected void insert(@Nonnull List<E> batch, @Nonnull PreparedQuery query) {
        insert(batch, query, false);
    }

    @SuppressWarnings("SameParameterValue")
    protected void insert(@Nonnull List<E> batch, @Nonnull PreparedQuery query, boolean ignoreAutoGenerate) {
        if (batch.isEmpty()) {
            return;
        }
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
        transformed.forEach(this::fireAfterInsert);
    }

    protected List<ID> insertAndFetchIds(@Nonnull List<E> batch, @Nonnull PreparedQuery query) {
        return insertAndFetchIds(batch, query, false);
    }

    @SuppressWarnings("SameParameterValue")
    private List<ID> insertAndFetchIds(@Nonnull List<E> batch, @Nonnull PreparedQuery query, boolean ignoreAutoGenerate) {
        if (batch.isEmpty()) {
            return List.of();
        }
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
        transformed.forEach(this::fireAfterInsert);
        if (isAutoGeneratedPrimaryKey() && !ignoreAutoGenerate) {
            try (var stream = query.getGeneratedKeys(model.primaryKeyType())) {
                return stream.toList();
            }
        }
        return transformed.stream().map(Entity::id).toList();
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
    public void update(@Nonnull Stream<E> entities) {
        update(entities, defaultBatchSize);
    }

    private sealed interface PartitionKey {}
    private static final class NoOpKey implements PartitionKey {
        private static final NoOpKey INSTANCE = new NoOpKey();
    }
    private record UpdateKey(@Nonnull Set<Metamodel<?, ?>> fields) implements PartitionKey {
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
    public void update(@Nonnull Stream<E> entities, int batchSize) {
        if (model.isJoinedInheritance()) {
            Stream<E> mapped = entityCallbacks.isEmpty()
                    ? entities
                    : entities.map(this::fireBeforeUpdate);
            var entityCache = entityCache();
            chunked(mapped, batchSize).forEach(batch -> {
                batch.forEach(this::validateUpdate);
                entityCache.ifPresent(cache -> batch.stream()
                        .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                        .forEach(e -> cache.remove(e.id())));
                JoinedEntityHelper.updateBatch(ormTemplate, model, batch);
                batch.forEach(this::fireAfterUpdate);
            });
            return;
        }
        Map<Set<Metamodel<?, ?>>, PreparedQuery> updateQueries = new HashMap<>();
        try {
            var entityCache = entityCache();
            Stream<E> mapped = entityCallbacks.isEmpty()
                    ? entities
                    : entities.map(this::fireBeforeUpdate);
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
    protected Optional<Set<Metamodel<?, ?>>> getDirty(@Nonnull E entity, @Nullable EntityCache<E, ID> cache) {
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

    protected PreparedQuery prepareUpdateQuery(@Nonnull Set<Metamodel<?, ?>> fields) {
        var bindVars = ormTemplate.createBindVars();
        return ormTemplate.query(TemplateString.raw("""
                UPDATE \0
                SET \0
                WHERE \0""", model.type(), Templates.set(bindVars, fields), bindVars))
                .managed().prepare();
    }

    protected void update(@Nonnull List<E> batch, @Nonnull PreparedQuery query, @Nullable EntityCache<E, ID> cache) {
        if (batch.isEmpty()) {
            return;
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
        batch.forEach(this::fireAfterUpdate);
    }

    protected List<ID> updateAndFetchIds(@Nonnull List<E> batch, @Nonnull PreparedQuery query, @Nullable EntityCache<E, ID> cache) {
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
        batch.forEach(this::fireAfterUpdate);
        return batch.stream().map(Entity::id).toList();
    }

    protected List<E> updateAndFetch(@Nonnull List<E> batch, @Nonnull PreparedQuery query, @Nullable EntityCache<E, ID> cache) {
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
    public void upsert(@Nonnull Stream<E> entities) {
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
    private record UpsertUpdateKey(@Nonnull Set<Metamodel<?, ?>> fields) implements UpsertPartitionKey {
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
    public void upsert(@Nonnull Stream<E> entities, int batchSize) {
        requireNonJoinedSealedEntity();
        Map<Set<Metamodel<?, ?>>, PreparedQuery> updateQueries = new HashMap<>();
        LazySupplier<PreparedQuery> insertQuery = isAutoGeneratedPrimaryKey()
                ? new LazySupplier<>(this::prepareInsertQuery) : null;
        LazySupplier<PreparedQuery> upsertQuery = new LazySupplier<>(this::prepareUpsertQuery);
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
                        List<E> batch = !entityCallbacks.isEmpty()
                                ? partition.chunk().stream().map(this::fireBeforeUpsert).toList()
                                : partition.chunk();
                        doUpsertBatch(batch, upsertQuery.get(), entityCache.orElse(null));
                        if (!entityCallbacks.isEmpty()) {
                            batch.forEach(this::fireAfterUpsert);
                        }
                    }
                    case UpsertUpdateKey u -> {
                        List<E> batch = !entityCallbacks.isEmpty()
                                ? partition.chunk().stream().map(this::fireBeforeUpdate).toList()
                                : partition.chunk();
                        update(batch,
                                updateQueries.computeIfAbsent(u.fields(), this::prepareUpdateQuery),
                                entityCache.orElse(null));
                    }
                }
            });
        } finally {
            var streams = updateQueries.values().stream();
            if (insertQuery != null) {
                streams = Stream.concat(streams, insertQuery.value().stream());
            }
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
    protected void doUpsertBatch(@Nonnull List<E> batch, @Nonnull PreparedQuery query,
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
    protected List<ID> doUpsertAndFetchIdsBatch(@Nonnull List<E> batch, @Nonnull PreparedQuery query,
                                                @Nullable EntityCache<E, ID> cache) {
        throw upsertNotAvailable();
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
    public void remove(@Nonnull Stream<E> entities) {
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
    public void remove(@Nonnull Stream<E> entities, int batchSize) {
        if (model.isJoinedInheritance()) {
            var entityCache = entityCache();
            chunked(entities, batchSize).forEach(batch -> {
                batch.forEach(e -> {
                    validateDelete(e);
                    fireBeforeDelete(e);
                });
                entityCache.ifPresent(cache -> batch.stream()
                        .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                        .forEach(e -> cache.remove(e.id())));
                JoinedEntityHelper.removeBatch(ormTemplate, model, batch);
                batch.forEach(this::fireAfterDelete);
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
                    fireBeforeDelete(e);
                    query.addBatch(e);
                });
                entityCache.ifPresent(cache -> chunk.stream()
                        .filter(e -> !model.isDefaultPrimaryKey(e.id()))
                        .forEach(e -> cache.remove(e.id())));
                int[] result = query.executeBatch();
                if (IntStream.of(result).anyMatch(r -> r != 1)) {
                    throw new PersistenceException("Batch remove of %s failed. One or more rows were not affected.".formatted(model.type().getSimpleName()));
                }
                chunk.forEach(this::fireAfterDelete);
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
    public void removeByRef(@Nonnull Stream<Ref<E>> refs) {
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
    public void removeByRef(@Nonnull Stream<Ref<E>> refs, int batchSize) {
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
            } catch (Exception ignore) {}
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
