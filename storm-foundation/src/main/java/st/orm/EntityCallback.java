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
package st.orm;

import java.util.List;

/**
 * Typed callback interface for entity lifecycle events.
 *
 * <p>The type parameter {@code E} determines which entity type this callback applies to. The framework automatically
 * resolves the type parameter at runtime and only invokes the callback for matching entity types. Use
 * {@code EntityCallback<Entity<?>>} to create a global callback that fires for all entities.</p>
 *
 * <p>The "before" callbacks for insert, update, and upsert return the (potentially transformed) entity to persist,
 * which is essential for immutable record-based entities that cannot be mutated in place. The "after" callbacks and
 * {@link #beforeRemove} are observers that do not affect the persisted data.</p>
 *
 * <h2>Upsert callback routing</h2>
 *
 * <p>An upsert operation may be executed as a SQL-level upsert (e.g., {@code INSERT ... ON CONFLICT},
 * {@code MERGE}), or it may be routed to a plain insert or update depending on the entity's primary key state and
 * the database dialect. The callbacks that fire depend on which path is taken:</p>
 * <ul>
 *   <li>When routed to <b>insert</b>: {@link #beforeInsert}/{@link #afterInsert} fire.</li>
 *   <li>When routed to <b>update</b>: {@link #beforeUpdate}/{@link #afterUpdate} fire.</li>
 *   <li>When executed as a <b>SQL-level upsert</b>: {@link #beforeUpsert}/{@link #afterUpsert} fire.</li>
 * </ul>
 * <p>Exactly one pair of callbacks fires per entity; they are never combined.</p>
 *
 * <h2>"After" callback entity state</h2>
 *
 * <p>The "after" callbacks observe what the calling method reports to its caller:</p>
 * <ul>
 *   <li>Methods that return nothing ({@code insert}, {@code update}, {@code upsert}) report the entity as it was
 *       sent to the database, after the corresponding "before" transformation. No key is read back, so a
 *       database-generated primary key is not reflected.</li>
 *   <li>The {@code *AndFetchId} methods report that same entity carrying the primary key the database assigned.</li>
 *   <li>The {@code *AndFetch} methods report the entity as read back from the database, reflecting generated keys,
 *       column defaults, version increments and trigger-applied changes.</li>
 * </ul>
 *
 * <p>A callback that needs the primary key must therefore be driven by a method that reports one. Selecting the
 * method is the caller's choice: the callback receives exactly what the caller receives, and no more.</p>
 *
 * <h2>Batches</h2>
 *
 * <p>Each "after" callback has a form that takes a list, and Storm always calls that form: a write of several entities
 * passes the entities of one batch in the order they were written, and a write of one entity passes a list of one. By
 * default the list form calls the single-entity form for each entity, so a callback that overrides only the
 * single-entity form sees every entity. A callback that writes to the database itself overrides the list form instead,
 * so a batch costs it a fixed number of statements rather than a statement per entity. A {@code *AndFetch} call and a
 * write set pass each type's entities as one list once the rows have been read back, and a stream passes them a batch
 * at a time. Where several callbacks apply, each receives the whole list before the next one does.</p>
 *
 * <h2>Transactions</h2>
 *
 * <p>Callbacks run inline on the thread that performed the write and on its connection, so they see exactly the
 * transaction the write runs in. The "before" callbacks run before the statement, the "after" callbacks once it has
 * returned. Inside a transactional block, or under a transaction another framework manages, database work a callback
 * performs belongs to that transaction and a rollback takes it back together with the write, and an "after" callback
 * that throws rolls the write back with it.</p>
 *
 * <p>Storm opens no transaction of its own. A write issued outside one commits on its own, and so does each statement
 * of a call that issues several, such as an {@code *AndFetch} method or a batch. An "after" callback then observes a
 * row that is already durable: throwing cannot take it back, and the callback's own statements commit separately. A
 * callback whose work has to succeed or fail with the write therefore depends on the caller having opened a
 * transaction.</p>
 *
 * <p>A callback that performs database work does so through the template the operation runs on, or one derived from
 * it, so that it shares the connection and with it the transaction. A template the callback builds for itself carries
 * transaction machinery of its own and shares neither: inside a Storm transactional block the mismatch is refused,
 * and under a transaction another framework manages the work runs on a connection of its own, outside it.</p>
 *
 * <p>Because an "after" callback runs before the commit, it is the wrong place for an effect outside the database,
 * such as publishing an event or invalidating a cache: the transaction may still roll back, leaving the effect
 * describing a write that never landed. Such work belongs on a commit callback, which a callback registers by opening
 * a joining transactional block and calling {@link Transaction#onCommit(Runnable)}; it runs once the physical
 * transaction has committed. Registering from the list form registers one commit callback for the batch rather than
 * one per entity. Commit callbacks run synchronously before the transactional block returns, so work that can block
 * for long belongs on a background worker the callback hands off to.</p>
 *
 * <p>All methods have default no-op implementations, so users only need to override the hooks they care about.</p>
 *
 * <p>Typical use cases include auditing (setting created/updated timestamps), validation, and logging.</p>
 *
 * @param <E> the entity type this callback applies to. Use {@code Entity<?>} to match all entity types.
 * @since 1.9
 */
public interface EntityCallback<E extends Entity<?>> {

    /**
     * Called before an entity is inserted into the database.
     *
     * <p>The returned entity is the one that will actually be persisted. Implementations may return a modified copy
     * of the entity (e.g., with audit fields populated) or the original entity unchanged.</p>
     *
     * <p>This callback also fires when an upsert operation is routed to an insert (e.g., for auto-generated
     * primary keys on databases that cannot perform a SQL-level upsert with generated keys).</p>
     *
     * @param entity the entity about to be inserted; never {@code null}.
     * @return the entity to insert; never {@code null}.
     */
    default E beforeInsert(E entity) {
        return entity;
    }

    /**
     * Called before an entity is updated in the database.
     *
     * <p>The returned entity is the one that will actually be persisted. Implementations may return a modified copy
     * of the entity (e.g., with an updated timestamp) or the original entity unchanged.</p>
     *
     * <p>This callback also fires when an upsert operation is routed to an update (i.e., when the entity has an
     * auto-generated primary key with a non-default value, indicating it was previously inserted).</p>
     *
     * @param entity the entity about to be updated; never {@code null}.
     * @return the entity to update; never {@code null}.
     */
    default E beforeUpdate(E entity) {
        return entity;
    }

    /**
     * Called after an entity has been successfully inserted into the database.
     *
     * <p>The entity passed to this method reflects what the calling method reports: the entity as sent for
     * {@code insert}, the entity carrying its generated primary key for {@code insertAndFetchId(s)}, and the row as
     * read back for {@code insertAndFetch}.</p>
     *
     * <p>This callback also fires when an upsert operation is routed to an insert.</p>
     *
     * @param entity the entity that was inserted; never {@code null}.
     */
    default void afterInsert(E entity) {}

    /**
     * Called after a batch of entities has been successfully inserted into the database.
     *
     * <p>Storm delivers every insert through this method: a batch write passes the entities of one batch in insertion
     * order, and a single-entity write passes a list of one. Each entity reflects what the calling method reports, as
     * described for {@link #afterInsert(Entity)}. Override this method to handle a batch as a whole, for instance with
     * one statement for the batch where {@link #afterInsert(Entity)} would issue one per entity.</p>
     *
     * <p>This callback also fires when an upsert operation is routed to an insert, and receives upserted batches when
     * the callback overrides neither {@link #afterUpsert(Entity)} nor {@link #afterUpsert(List)}.</p>
     *
     * <p>By default, this calls {@link #afterInsert(Entity)} for each entity, in order.</p>
     *
     * @param entities the entities that were inserted, in insertion order; never {@code null} or empty.
     * @since 1.14
     */
    default void afterInsert(List<E> entities) {
        for (E entity : entities) {
            afterInsert(entity);
        }
    }

    /**
     * Called after an entity has been successfully updated in the database.
     *
     * <p>The entity passed to this method reflects what the calling method reports: the entity as sent for
     * {@code update}, and the row as read back for {@code updateAndFetch}. Only the latter reflects database-side
     * changes such as version increments or trigger-applied modifications.</p>
     *
     * <p>This callback also fires when an upsert operation is routed to an update.</p>
     *
     * @param entity the entity that was updated; never {@code null}.
     */
    default void afterUpdate(E entity) {}

    /**
     * Called after a batch of entities has been successfully updated in the database.
     *
     * <p>Storm delivers every update through this method: a batch write passes the entities of one batch in update
     * order, and a single-entity write passes a list of one. Each entity reflects what the calling method reports, as
     * described for {@link #afterUpdate(Entity)}.</p>
     *
     * <p>By default, this calls {@link #afterUpdate(Entity)} for each entity, in order.</p>
     *
     * @param entities the entities that were updated, in update order; never {@code null} or empty.
     * @since 1.14
     */
    default void afterUpdate(List<E> entities) {
        for (E entity : entities) {
            afterUpdate(entity);
        }
    }

    /**
     * Called before an entity is upserted via a SQL-level upsert statement (e.g., {@code INSERT ... ON CONFLICT},
     * {@code MERGE}).
     *
     * <p>This callback only fires when the upsert is executed as a SQL-level upsert. When the operation is routed
     * to a plain insert or update, {@link #beforeInsert} or {@link #beforeUpdate} fires instead.</p>
     *
     * <p>The returned entity is the one that will actually be persisted. By default, this delegates to
     * {@link #beforeInsert(Entity)}, so that insert callbacks automatically cover the upsert path.
     * Override this method to provide upsert-specific behavior.</p>
     *
     * @param entity the entity about to be upserted; never {@code null}.
     * @return the entity to upsert; never {@code null}.
     */
    default E beforeUpsert(E entity) {
        return beforeInsert(entity);
    }

    /**
     * Called after an entity has been successfully upserted via a SQL-level upsert statement.
     *
     * <p>This callback only fires when the upsert is executed as a SQL-level upsert. When the operation is routed
     * to a plain insert or update, {@link #afterInsert} or {@link #afterUpdate} fires instead.</p>
     *
     * <p>The entity passed to this method reflects what the calling method reports: the entity as sent for
     * {@code upsert}, the entity carrying its generated primary key for {@code upsertAndFetchId(s)}, and the row as
     * read back for {@code upsertAndFetch}.</p>
     *
     * <p>By default, this delegates to {@link #afterInsert(Entity)}, so that insert callbacks automatically
     * cover the upsert path. Override this method to provide upsert-specific behavior.</p>
     *
     * @param entity the entity that was upserted; never {@code null}.
     */
    default void afterUpsert(E entity) {
        afterInsert(entity);
    }

    /**
     * Called after a batch of entities has been successfully upserted via a SQL-level upsert statement.
     *
     * <p>Storm delivers every SQL-level upsert through this method, or through {@link #afterInsert(List)} as described
     * below: a batch write passes the entities of one batch in upsert order, and a single-entity write passes a list of
     * one. Each entity reflects what the calling method reports, as described for {@link #afterUpsert(Entity)}.</p>
     *
     * <p>By default, this calls {@link #afterUpsert(Entity)} for each entity, in order. A callback that overrides
     * neither this method nor {@link #afterUpsert(Entity)} receives upserted batches through
     * {@link #afterInsert(List)} instead, so that insert callbacks, batched or not, cover the upsert path as the
     * single-entity default does.</p>
     *
     * @param entities the entities that were upserted, in upsert order; never {@code null} or empty.
     * @since 1.14
     */
    default void afterUpsert(List<E> entities) {
        for (E entity : entities) {
            afterUpsert(entity);
        }
    }

    /**
     * Called before an entity is removed from the database.
     *
     * <p>Fires where the operation carries an entity, so {@code remove(entity)} and its collection and stream forms
     * trigger it. {@code removeById}, {@code removeByRef}, {@code removeAll} and the {@code delete()} query builder
     * identify rows by key or by predicate rather than by entity, so there is no entity to pass and this callback does
     * not fire. A callback that throws in order to block a removal therefore blocks only the paths that carry an
     * entity, and is not an enforcement point.</p>
     *
     * @param entity the entity about to be removed; never {@code null}.
     */
    default void beforeRemove(E entity) {}

    /**
     * Called after an entity has been successfully removed from the database.
     *
     * <p>As with {@link #beforeRemove}, fires only where the operation carries an entity.</p>
     *
     * @param entity the entity that was removed; never {@code null}.
     */
    default void afterRemove(E entity) {}

    /**
     * Called after a batch of entities has been successfully removed from the database.
     *
     * <p>Storm delivers every removal that carries entities through this method: a batch removal passes the entities
     * of one batch in removal order, and a single-entity removal passes a list of one. As with
     * {@link #afterRemove(Entity)}, removals by key or by predicate carry no entity and do not fire it.</p>
     *
     * <p>By default, this calls {@link #afterRemove(Entity)} for each entity, in order.</p>
     *
     * @param entities the entities that were removed, in removal order; never {@code null} or empty.
     * @since 1.14
     */
    default void afterRemove(List<E> entities) {
        for (E entity : entities) {
            afterRemove(entity);
        }
    }
}
