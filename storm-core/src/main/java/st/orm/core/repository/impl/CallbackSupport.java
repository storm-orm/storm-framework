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

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.function.Function;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.Entity;
import st.orm.EntityCallback;
import st.orm.core.template.ORMTemplate;

/**
 * Dispatches {@link EntityCallback} invocations for a single entity type.
 *
 * <p>The "after" callbacks observe what the calling repository method reports to its caller: the entity as sent for
 * the methods that return nothing, the entity carrying the primary key the database assigned for the
 * {@code *AndFetchId} methods, and the row as read back for the {@code *AndFetch} methods. The fetching methods build
 * on the id-returning ones, so their callbacks are collected at the point where the write completes and fired once
 * the rows are available, keeping the write itself on a single path.</p>
 *
 * <p>The "after" callbacks are always dispatched in their list form: a batch as one list, a single entity as a list
 * of one, and the callbacks collected by a {@code *AndFetch} call or a write set as one list per run of entities of the
 * same type and outcome, in the order they were written. Each callback receives a list before the next one does.</p>
 *
 * <p>Three thread-scoped concerns are handled here. Callbacks never fire recursively, so database work performed
 * inside a callback runs without triggering callbacks of its own. A {@code *AndFetch} call in flight collects rather
 * than fires. And a write whose caller asked for nothing back withholds any key it retrieved along the way, which is
 * what the write set needs when it reads keys only to bind foreign keys on dependent rows.</p>
 *
 * <p>Instances are per repository and immutable; the thread-scoped state is static so it spans the repositories a
 * single operation touches.</p>
 *
 * @param <E> the entity type.
 * @param <ID> the primary key type.
 * @since 1.13
 */
final class CallbackSupport<E extends Entity<ID>, ID> {

    /**
     * Re-entrancy guard that prevents entity callbacks from firing recursively. When a callback performs database
     * operations (e.g., inserting an audit log), those operations must not trigger callbacks again. Static and
     * thread-local so that it applies across all repository instances on the current thread.
     */
    private static final ThreadLocal<Boolean> ACTIVE = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Collects the "after" callbacks of an in-flight {@code *AndFetch} call until the rows have been read back. */
    private static final ThreadLocal<List<Deferred>> DEFERRED = new ThreadLocal<>();

    /** Withholds retrieved primary keys from the "after" callbacks. */
    private static final ThreadLocal<Boolean> WITHHOLD_KEYS = ThreadLocal.withInitial(() -> Boolean.FALSE);

    /** Identifies which "after" callback an invocation dispatches to. */
    private enum After { INSERT, UPDATE, UPSERT, REMOVE }

    /**
     * An "after" callback that has been collected rather than fired, holding the entity as sent to the database, the
     * primary key the database assigned if one was retrieved, and the repository that owns the callbacks.
     */
    private record Deferred(CallbackSupport<?, ?> support,
                            Entity<?> entity,
                            @Nullable Object generatedPrimaryKey,
                            After type) {}

    private final ORMTemplate ormTemplate;

    private final List<EntityCallback<E>> callbacks;

    /**
     * Per callback, in the order of {@link #callbacks}, whether it receives upserted batches through
     * {@link EntityCallback#afterInsert(List)}: a callback that overrides neither upsert form does, so that its insert
     * callbacks, batched or not, cover the upsert path as the single-entity default does.
     */
    private final boolean[] upsertsAsInserts;

    CallbackSupport(ORMTemplate ormTemplate, Class<E> entityType) {
        this.ormTemplate = ormTemplate;
        this.callbacks = resolve(ormTemplate.entityCallbacks(), entityType);
        this.upsertsAsInserts = new boolean[this.callbacks.size()];
        for (int i = 0; i < this.callbacks.size(); i++) {
            var type = this.callbacks.get(i).getClass();
            upsertsAsInserts[i] = !overrides(type, "afterUpsert", Entity.class)
                    && !overrides(type, "afterUpsert", List.class);
        }
    }

    /** Returns whether the callback type overrides the given {@link EntityCallback} default method. */
    private static boolean overrides(Class<?> type, String name, Class<?> parameterType) {
        try {
            return type.getMethod(name, parameterType).getDeclaringClass() != EntityCallback.class;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Runs a write with retrieved primary keys withheld from the "after" callbacks, so they observe the entities as
     * sent.
     *
     * @param write the write to perform.
     * @return the result of the write.
     */
    static <R> R withoutObservedKeys(Supplier<R> write) {
        if (WITHHOLD_KEYS.get()) {
            return write.get();
        }
        WITHHOLD_KEYS.set(Boolean.TRUE);
        try {
            return write.get();
        } finally {
            WITHHOLD_KEYS.set(Boolean.FALSE);
        }
    }

    /** Returns whether callbacks are registered and would fire on the current thread. */
    boolean isActive() {
        return !callbacks.isEmpty() && !ACTIVE.get();
    }

    //
    // "Before" callbacks.
    //

    E beforeInsert(E entity) {
        return transform(entity, EntityCallback::beforeInsert);
    }

    E beforeUpdate(E entity) {
        return transform(entity, EntityCallback::beforeUpdate);
    }

    E beforeUpsert(E entity) {
        return transform(entity, EntityCallback::beforeUpsert);
    }

    void beforeRemove(E entity) {
        observe(entity, EntityCallback::beforeRemove);
    }

    void afterRemove(E entity) {
        fire(entity, null, After.REMOVE);
    }

    /** Fires the after-remove callbacks for a batch. */
    void afterRemove(List<E> entities) {
        fireBatch(entities, List.of(), After.REMOVE);
    }

    /** Applies each callback in registration order, chaining the entity each one returns. */
    private E transform(E entity, Transformer<E> transformer) {
        if (!isActive()) {
            return entity;
        }
        ACTIVE.set(Boolean.TRUE);
        CallbackTemplate.bind(ormTemplate);
        try {
            for (var callback : callbacks) {
                entity = transformer.apply(callback, entity);
            }
            return entity;
        } finally {
            CallbackTemplate.unbind();
            ACTIVE.set(Boolean.FALSE);
        }
    }

    /** Invokes each callback in registration order without altering the entity. */
    private void observe(E entity, Observer<E> observer) {
        if (!isActive()) {
            return;
        }
        ACTIVE.set(Boolean.TRUE);
        CallbackTemplate.bind(ormTemplate);
        try {
            for (var callback : callbacks) {
                observer.accept(callback, entity);
            }
        } finally {
            CallbackTemplate.unbind();
            ACTIVE.set(Boolean.FALSE);
        }
    }

    @FunctionalInterface
    private interface Transformer<E extends Entity<?>> {
        E apply(EntityCallback<E> callback, E entity);
    }

    @FunctionalInterface
    private interface Observer<E extends Entity<?>> {
        void accept(EntityCallback<E> callback, E entity);
    }

    //
    // "After" callbacks.
    //

    /** Fires the after-insert callbacks with the entity as sent to the database. */
    void afterInsert(E entity) {
        fire(entity, null, After.INSERT);
    }

    /** Fires the after-insert callbacks with the entity carrying the primary key the database assigned. */
    void afterInsert(E entity, @Nullable ID generatedPrimaryKey) {
        fire(entity, generatedPrimaryKey, After.INSERT);
    }

    /**
     * Fires the after-insert callbacks for a batch, pairing each entity with the primary key the database assigned.
     * The keys are reported in insertion order, which is the contract the batch insert paths already rely on.
     */
    void afterInsert(List<E> entities, List<ID> generatedPrimaryKeys) {
        fireBatch(entities, generatedPrimaryKeys, After.INSERT);
    }

    void afterUpdate(E entity) {
        fire(entity, null, After.UPDATE);
    }

    /** Fires the after-update callbacks for a batch, with the entities as sent to the database. */
    void afterUpdate(List<E> entities) {
        fireBatch(entities, List.of(), After.UPDATE);
    }

    /** Fires the after-upsert callbacks with the entity as sent to the database. */
    void afterUpsert(E entity) {
        fire(entity, null, After.UPSERT);
    }

    /** Fires the after-upsert callbacks with the entity carrying the primary key the database assigned. */
    void afterUpsert(E entity, @Nullable ID generatedPrimaryKey) {
        fire(entity, generatedPrimaryKey, After.UPSERT);
    }

    /** Fires the after-upsert callbacks for a batch, pairing each entity with its assigned primary key. */
    void afterUpsert(List<E> entities, List<ID> generatedPrimaryKeys) {
        fireBatch(entities, generatedPrimaryKeys, After.UPSERT);
    }

    /**
     * Dispatches the "after" callbacks for a batch as one list, pairing each entity with the primary key at the same
     * position, or collects them one by one when a {@code *AndFetch} call is in flight.
     */
    private void fireBatch(List<E> entities, List<ID> generatedPrimaryKeys, After type) {
        if (!isActive() || entities.isEmpty()) {
            return;
        }
        boolean withholdKeys = WITHHOLD_KEYS.get();
        var deferred = deferredFor(type);
        var observed = new ArrayList<E>(entities.size());
        for (int i = 0; i < entities.size(); i++) {
            ID generatedPrimaryKey = !withholdKeys && i < generatedPrimaryKeys.size() ? generatedPrimaryKeys.get(i) : null;
            if (deferred != null) {
                deferred.add(new Deferred(this, entities.get(i), generatedPrimaryKey, type));
            } else {
                observed.add(withPrimaryKey(entities.get(i), generatedPrimaryKey));
            }
        }
        if (deferred == null) {
            invoke(observed, type);
        }
    }

    /** Dispatches an "after" callback as a list of one, or collects it when a {@code *AndFetch} call is in flight. */
    private void fire(E entity, @Nullable ID generatedPrimaryKey, After type) {
        if (!isActive()) {
            return;
        }
        if (WITHHOLD_KEYS.get()) {
            generatedPrimaryKey = null;
        }
        var deferred = deferredFor(type);
        if (deferred != null) {
            deferred.add(new Deferred(this, entity, generatedPrimaryKey, type));
            return;
        }
        invoke(List.of(withPrimaryKey(entity, generatedPrimaryKey)), type);
    }

    /**
     * Returns the collection of the {@code *AndFetch} call in flight, or {@code null} when the callback fires at once.
     * A removal reads no row back, so its callback fires at once whatever call is in flight.
     */
    private static @Nullable List<Deferred> deferredFor(After type) {
        return type == After.REMOVE ? null : DEFERRED.get();
    }

    /**
     * Invokes the list form of the "after" callbacks of the given type in registration order, guarding against
     * re-entrancy. Each callback receives the whole list, unmodifiable, before the next one does.
     */
    private void invoke(List<E> entities, After type) {
        entities = Collections.unmodifiableList(entities);
        ACTIVE.set(Boolean.TRUE);
        CallbackTemplate.bind(ormTemplate);
        try {
            for (int i = 0; i < callbacks.size(); i++) {
                var callback = callbacks.get(i);
                switch (type) {
                    case INSERT -> callback.afterInsert(entities);
                    case UPDATE -> callback.afterUpdate(entities);
                    case UPSERT -> {
                        if (upsertsAsInserts[i]) {
                            callback.afterInsert(entities);
                        } else {
                            callback.afterUpsert(entities);
                        }
                    }
                    case REMOVE -> callback.afterRemove(entities);
                }
            }
        } finally {
            CallbackTemplate.unbind();
            ACTIVE.set(Boolean.FALSE);
        }
    }

    /**
     * Returns the entity carrying the primary key the database assigned, rebuilding it when that key differs from the
     * one that was sent. Entities whose type declares no {@code @PK} component to carry the key, and entities whose
     * key was not generated, are returned unchanged.
     */
    @SuppressWarnings("unchecked")
    private E withPrimaryKey(E entity, @Nullable ID generatedPrimaryKey) {
        if (generatedPrimaryKey == null || generatedPrimaryKey.equals(entity.id())) {
            return entity;
        }
        int primaryKeyIndex = RecordRebuilder.primaryKeyIndex(entity.getClass());
        if (primaryKeyIndex < 0) {
            return entity;
        }
        return (E) RecordRebuilder.withComponent(entity, primaryKeyIndex, generatedPrimaryKey);
    }

    //
    // Deferral.
    //

    /**
     * Runs a write that reports its result by reading the rows back, firing the "after" callbacks against those rows
     * rather than against the entities that were sent.
     *
     * <p>Each collected callback is matched to one of the reported rows by primary key. A callback whose row is
     * absent from the fetch, which a concurrent delete can cause, falls back to the entity as sent carrying its key,
     * so a write is never observed twice and never goes unobserved.</p>
     *
     * @param write the write, returning the rows it read back.
     * @return the rows the write read back.
     */
    List<E> fetchAndFire(Supplier<List<E>> write) {
        if (!isActive()) {
            return write.get();
        }
        return fetchAndFire(write, rows -> rows);
    }

    /**
     * Runs a write that reports rows read back, firing the "after" callbacks against those rows rather than against
     * the entities that were sent.
     *
     * <p>Static so that a write set, which spans repositories of several types, can wrap its whole execution: each
     * collected callback replays against the callbacks that collected it, and is matched to a reported row by type
     * and primary key. A callback whose row is not among those reported falls back to the entity as sent carrying
     * its key, so a write is never observed twice and never goes unobserved.</p>
     *
     * @param write the write to perform.
     * @param rows the rows the write read back, extracted from its result.
     * @return the result of the write.
     */
    static <R> R fetchAndFire(Supplier<R> write, Function<R, List<? extends Entity<?>>> rows) {
        var previous = DEFERRED.get();
        var deferred = new ArrayList<Deferred>();
        R result;
        DEFERRED.set(deferred);
        try {
            result = write.get();
        } finally {
            if (previous == null) {
                DEFERRED.remove();
            } else {
                DEFERRED.set(previous);
            }
        }
        replay(deferred, rows.apply(result));
        return result;
    }

    /**
     * Returns whether the {@code *AndFetch} call in flight has collected any callback, so a caller can decide how
     * widely to read rows back before those callbacks are fired.
     */
    static boolean hasDeferred() {
        var deferred = DEFERRED.get();
        return deferred != null && !deferred.isEmpty();
    }

    /** Identifies a reported row, so entities of different types that share a primary key stay distinct. */
    private record TypeIdKey(Class<?> type, @Nullable Object id) {}

    /**
     * Replays the collected callbacks against the reported rows, in the order they were collected, dispatching each
     * run of entries that share their callbacks and outcome as one list.
     */
    private static void replay(List<Deferred> deferred, List<? extends Entity<?>> fetched) {
        if (deferred.isEmpty()) {
            return;
        }
        var byTypeAndKey = new HashMap<TypeIdKey, Entity<?>>();
        for (Entity<?> entity : fetched) {
            byTypeAndKey.put(new TypeIdKey(entity.getClass(), entity.id()), entity);
        }
        int start = 0;
        while (start < deferred.size()) {
            var first = deferred.get(start);
            int end = start + 1;
            while (end < deferred.size()
                    && deferred.get(end).support() == first.support()
                    && deferred.get(end).type() == first.type()) {
                end++;
            }
            first.support().replayRun(deferred.subList(start, end), byTypeAndKey);
            start = end;
        }
    }

    @SuppressWarnings("unchecked")
    private void replayRun(List<Deferred> run, HashMap<TypeIdKey, Entity<?>> byTypeAndKey) {
        var observed = new ArrayList<E>(run.size());
        for (var entry : run) {
            E sent = withPrimaryKey((E) entry.entity(), (ID) entry.generatedPrimaryKey());
            Entity<?> row = byTypeAndKey.get(new TypeIdKey(sent.getClass(), sent.id()));
            observed.add(row == null ? sent : (E) row);
        }
        invoke(observed, run.getFirst().type());
    }

    //
    // Resolution.
    //

    /**
     * Resolves the entity callbacks that match the given entity type, filtering by the generic type parameter
     * declared on each {@link EntityCallback}.
     */
    @SuppressWarnings("unchecked")
    private static <E extends Entity<ID>, ID> List<EntityCallback<E>> resolve(
            List<EntityCallback<?>> callbacks, Class<E> entityType) {
        var result = new ArrayList<EntityCallback<E>>();
        for (var callback : callbacks) {
            Class<?> callbackType = resolveEntityType(callback.getClass());
            if (callbackType.isAssignableFrom(entityType)) {
                result.add((EntityCallback<E>) callback);
            }
        }
        return List.copyOf(result);
    }

    /**
     * Resolves the entity type parameter {@code E} from a concrete {@link EntityCallback} class by inspecting its
     * generic interface hierarchy.
     */
    private static Class<?> resolveEntityType(Class<?> clazz) {
        for (Type iface : clazz.getGenericInterfaces()) {
            if (iface instanceof ParameterizedType pt) {
                if (pt.getRawType() == EntityCallback.class) {
                    return extractClass(pt.getActualTypeArguments()[0]);
                }
                if (pt.getRawType() instanceof Class<?> raw && EntityCallback.class.isAssignableFrom(raw)) {
                    Class<?> resolved = resolveEntityType(raw);
                    if (resolved != Entity.class) {
                        return resolved;
                    }
                }
            } else if (iface instanceof Class<?> raw && EntityCallback.class.isAssignableFrom(raw)) {
                Class<?> resolved = resolveEntityType(raw);
                if (resolved != Entity.class) {
                    return resolved;
                }
            }
        }
        Class<?> superclass = clazz.getSuperclass();
        if (superclass != null && superclass != Object.class) {
            return resolveEntityType(superclass);
        }
        return Entity.class;
    }

    private static Class<?> extractClass(Type type) {
        if (type instanceof Class<?> cls) {
            return cls;
        }
        if (type instanceof ParameterizedType pt) {
            return (Class<?>) pt.getRawType();
        }
        return Entity.class;
    }
}
