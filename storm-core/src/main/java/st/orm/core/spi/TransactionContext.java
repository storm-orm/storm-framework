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

import java.util.Optional;
import org.jspecify.annotations.Nullable;
import st.orm.Entity;

/**
 * @since 1.5
 */
public interface TransactionContext {

    /**
     * Returns a human-readable description of the current transaction's characteristics, such as isolation level and
     * timeout.
     *
     * <p>This is used to enrich diagnostic messages (for example when a statement fails or times out) with the
     * transaction settings that are in effect. Returns an empty optional when no description is available, for instance
     * when no transaction is currently active.</p>
     *
     * @return a description of the transaction characteristics, or empty if not available.
     * @since 1.11
     */
    default Optional<String> describe() {
        return Optional.empty();
    }

    /**
     * Returns true if the transaction has repeatable-read semantics.
     *
     * <p>This is true when the isolation level is {@code REPEATABLE_READ} or higher.</p>
     *
     * <p>When {@code true}, cached entities are returned when re-reading the same entity, preserving
     * entity identity within the transaction. When {@code false}, fresh data is fetched from the database.</p>
     *
     * <p>Note: Cache writes for dirty tracking still occur at all isolation levels when dirty tracking
     * is enabled for the entity type.</p>
     *
     * @return true if transaction has repeatable-read semantics, false otherwise.
     * @since 1.8
     */
    default boolean isRepeatableRead() {
        return false;
    }

    /**
     * Whether the statements of the current frame run in a read-only transaction. The mode is that of the frame
     * owning the physical transaction, since a joined frame runs on its connection; a frame that runs outside a
     * transaction is never read-only.
     *
     * @return {@code true} when a write issued now would break the transaction's read-only promise.
     * @since 1.14.1
     */
    default boolean isReadOnly() {
        return false;
    }

    /**
     * Returns a transaction-local cache for entities of the given type, creating one with the specified retention
     * behavior if it does not yet exist.
     *
     * <p>The entity cache serves two purposes:</p>
     * <ul>
     *   <li><b>Dirty tracking:</b> The cached state serves as the baseline for detecting changes when updating
     *       entities. This is always enabled regardless of isolation level.</li>
     *   <li><b>Hydration/identity:</b> When {@link #isRepeatableRead()} returns {@code true}, reading the same
     *       entity returns the cached instance. When {@code false}, fresh data is fetched.</li>
     * </ul>
     *
     * @param entityType the entity type for which to retrieve or create the cache.
     * @param retention the cache retention strategy to use when creating a new cache.
     * @return the entity cache; never {@code null}.
     * @since 1.9
     */
    EntityCache<? extends Entity<?>, ?> entityCache(Class<? extends Entity<?>> entityType,
                                                    CacheRetention retention);

    /**
     * Returns an existing transaction-local cache for entities of the given type.
     *
     * @param entityType the entity type for which to retrieve the cache.
     * @return the entity cache.
     * @throws IllegalStateException if no cache exists for the given entity type.
     * @since 1.9
     */
    EntityCache<? extends Entity<?>, ?> getEntityCache(Class<? extends Entity<?>> entityType);

    /**
     * Returns an existing transaction-local cache for entities of the given type, or {@code null} if none exists.
     *
     * @param entityType the entity type for which to retrieve the cache.
     * @return the entity cache, or {@code null} if no cache has been created for this entity type.
     * @since 1.9
     */
    @Nullable
    EntityCache<? extends Entity<?>, ?> findEntityCache(Class<? extends Entity<?>> entityType);

    /**
     * Clears all entity caches associated with this transaction context.
     *
     * <p>This method is used when a mutating SQL operation is executed but the affected entity types cannot be
     * determined. In such cases, all caches are cleared to ensure that dirty checking does not rely on stale observed
     * state.</p>
     *
     * <p>If entity caching is disabled for this transaction, this method has no effect.</p>
     */
    void clearAllEntityCaches();

    /**
     * Decorates a transaction resource before it is used.
     *
     * <p>
     * Implementations may wrap, configure, or otherwise adapt the given {@code resource} so it matches the
     * transaction characteristics and scope (for example isolation, read-only behavior, timeouts, or
     * thread/connection binding).</p>
     *
     * @param <T> the resource type being decorated
     */
    interface Decorator<T> {

        /**
         * Returns a decorated variant of the given resource.
         * <p>
         * Implementations may return the same instance if no decoration is required.
         *
         * @param resource the resource to decorate
         * @return the decorated resource (never {@code null})
         */
        T decorate(T resource);
    }

    /**
     * Gets the decorator for the specified resource type.
     *
     * @param resourceType the resource type.
     * @return the decorator.
     * @param <T> the resource type.
     */
    <T> Decorator<T> getDecorator(Class<T> resourceType);
}
