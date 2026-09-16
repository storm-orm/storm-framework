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
package st.orm.core.spi;

import static java.util.Objects.requireNonNull;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import st.orm.Entity;
import st.orm.Ref;

/**
 * A weak interner that ensures canonical instances of objects while holding them weakly to permit garbage collection.
 *
 * <p>This class uses a dual-path interning strategy optimized for different object types:</p>
 * <ul>
 *   <li><b>Entities</b>: Keyed by concrete type and primary key for efficient lookup. Entities are stored with
 *       {@link ReferenceQueue}-based cleanup to ensure stale entries are removed when entities are garbage
 *       collected.</li>
 *   <li><b>Non-entities</b>: Uses object equality-based lookup via {@link WeakHashMap}, which provides automatic
 *       cleanup when objects are no longer strongly referenced.</li>
 * </ul>
 *
 * <p>The primary key-based lookup for entities avoids potentially expensive deep equality checks on complex entity
 * objects, while maintaining correct identity semantics (same type and primary key = same canonical instance).</p>
 *
 * <p>Weak references are cleared only when their referent is no longer strongly reachable, which makes interning a
 * guarantee rather than a best-effort optimization: as long as a caller retains a strong reference to an interned
 * instance, later equivalent objects resolve to that same instance. A caller in a position to compare two duplicates
 * necessarily still holds the first one, which is exactly what keeps its entry alive, so duplicates can never be
 * observed as distinct instances.</p>
 *
 * <p>A {@link Ref} equals any other reference to the same record, loaded or not, and interning never trades a loaded
 * reference for an unloaded one. A row can build both: an unloaded reference while mapping a record that points back
 * at the referenced row, and a loaded one when the referenced record itself is in the fetch plan. The loaded
 * reference is the canonical instance from the moment it is interned, so later rows share the resolved record, and
 * an unloaded reference meeting a loaded one receives the loaded instance. A record built earlier with the unloaded
 * instance keeps it, since its own field was not in the plan.</p>
 *
 * <p>This class is not thread-safe. A new instance is expected to be created for each result set processing call,
 * ensuring that interning is scoped to a single query execution.</p>
 */
public final class WeakInterner {

    /** Map for non-entity objects, using object equality for lookup. Keys are held weakly. */
    private Map<Object, WeakReference<Object>> map;

    /** Queue for tracking garbage-collected entities to enable lazy cleanup of {@link #entityMap}. */
    private ReferenceQueue<Entity<?>> queue;

    /**
     * Map for entities, keyed by concrete type then primary key. The two-level structure lets the hot path look up
     * and store entities using the primary key value already in hand.
     */
    private Map<Class<?>, Map<Object, PkWeakReference>> entityMap;

    /**
     * Creates a new weak interner.
     *
     * <p>The internal structures are initialized lazily: an interner is created per query, and queries without
     * record or entity results never intern anything.</p>
     */
    public WeakInterner() {
    }

    /**
     * Interns the given object, ensuring that only one canonical instance exists. If an equivalent object is already
     * present, returns the existing instance. Otherwise, adds the new object to the interner and returns it.
     *
     * <p>For {@link Entity} instances, lookup is performed using the entity's type and primary key for efficiency.
     * For all other objects, lookup is based on object equality.</p>
     *
     * @param object the object to intern.
     * @param <T> the type of the object.
     * @return the canonical instance of the object.
     * @throws NullPointerException if {@code object} is {@code null}.
     */
    public <T> T intern(T object) {
        requireNonNull(object, "Cannot intern null object.");
        if (object instanceof Entity<?> entity) {
            //noinspection unchecked
            return (T) internEntity(entity);
        }
        return internObject(object);
    }

    /**
     * Retrieves a cached entity by its type and primary key, if available.
     *
     * <p>This method enables early cache lookups before constructing nested objects. If an entity with the given
     * type and primary key was previously interned and is still reachable, it is returned.</p>
     *
     * @param entityType the entity class.
     * @param pk the primary key value.
     * @param <E> the entity type.
     * @return the cached entity, or {@code null} if not found or already garbage collected.
     */
    public <E extends Entity<?>> E get(Class<E> entityType, Object pk) {
        if (entityMap == null) {
            return null;
        }
        drainQueue();
        Map<Object, PkWeakReference> byPk = entityMap.get(entityType);
        if (byPk == null) {
            return null;
        }
        PkWeakReference existing = byPk.get(pk);
        if (existing != null) {
            Entity<?> result = existing.get();
            if (result != null) {
                //noinspection unchecked
                return (E) result;
            }
        }
        return null;
    }

    /**
     * Interns an entity using its type and primary key for efficient lookup.
     *
     * <p>This avoids expensive deep equality checks on complex entity objects. The entity is stored with a weak
     * reference, and cleanup is handled via {@link #drainQueue()} when entities are garbage collected.</p>
     *
     * @param entity the entity to intern.
     * @param <E> the entity type.
     * @return the canonical instance for the entity's primary key.
     */
    private <E extends Entity<?>> E internEntity(E entity) {
        if (entityMap == null) {
            entityMap = new HashMap<>();
            queue = new ReferenceQueue<>();
        } else {
            drainQueue();
        }
        Class<?> type = entity.getClass();
        Object pk = entity.id();
        Map<Object, PkWeakReference> byPk = entityMap.computeIfAbsent(type, k -> new HashMap<>());
        PkWeakReference existing = byPk.get(pk);
        if (existing != null) {
            var result = existing.get();
            if (result != null) {
                //noinspection unchecked
                return (E) result;
            }
        }
        byPk.put(pk, new PkWeakReference(type, pk, entity, queue));
        return entity;
    }

    /**
     * Interns a non-entity object using object equality for lookup.
     *
     * <p>Uses {@link WeakHashMap} which automatically removes entries when keys are garbage collected.</p>
     *
     * @param object the object to intern.
     * @param <T> the type of the object.
     * @return the canonical instance.
     * @throws IllegalArgumentException if an equivalent object of a different class is already interned.
     */
    private <T> T internObject(T object) {
        if (map == null) {
            map = new WeakHashMap<>();
        }
        WeakReference<Object> existing = map.get(object);
        if (existing != null) {
            // Equivalent object found; return existing instance
            var result = existing.get();
            if (result != null) {
                if (object instanceof Ref<?> incoming && result instanceof Ref<?> present) {
                    // A reference is identified by its type and row identity, whichever implementation carries
                    // it. A loaded reference takes over from an unloaded one as the canonical instance. The
                    // entry is re-keyed on the loaded instance: putting under the equal key would keep the
                    // unloaded instance as the key, and the entry would be cleared with it rather than with
                    // the reference it hands out.
                    if (incoming.isLoaded() && !present.isLoaded()) {
                        map.remove(present);
                        map.put(object, new WeakReference<>(object));
                        return object;
                    }
                    //noinspection unchecked
                    return (T) result;
                }
                if (result.getClass() != object.getClass()) {
                    throw new IllegalArgumentException("Cannot intern objects of different classes.");
                }
                //noinspection unchecked
                return (T) result;
            }
            return object;
        }
        map.put(object, new WeakReference<>(object));
        return object;
    }

    /**
     * Removes stale entries from {@link #entityMap} by polling the reference queue.
     *
     * <p>When an entity is garbage collected, its {@link PkWeakReference} is enqueued. This method polls the queue
     * and removes the corresponding entries from the map. Uses a two-argument remove to ensure only the exact
     * weak reference is removed, preventing removal of a newer entry with the same key.</p>
     */
    private void drainQueue() {
        PkWeakReference weakReference;
        while ((weakReference = (PkWeakReference) queue.poll()) != null) {
            Map<Object, PkWeakReference> byPk = entityMap.get(weakReference.type);
            if (byPk != null) {
                // Two-argument remove ensures only the exact stale reference is removed, never a newer entry that
                // reused the same primary key.
                byPk.remove(weakReference.pk, weakReference);
            }
        }
    }

    /**
     * A weak reference to an entity that retains its type and primary key for map cleanup.
     *
     * <p>When the entity is garbage collected, this reference is enqueued in the {@link ReferenceQueue}, allowing
     * {@link #drainQueue()} to remove the corresponding entry from {@link #entityMap} using the stored type and
     * primary key.</p>
     */
    private static final class PkWeakReference extends WeakReference<Entity<?>> {
        final Class<?> type;
        final Object pk;

        PkWeakReference(Class<?> type, Object pk, Entity<?> referent, ReferenceQueue<? super Entity<?>> q) {
            super(referent, q);
            this.type = type;
            this.pk = pk;
        }
    }
}
