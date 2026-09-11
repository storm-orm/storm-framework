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
package st.orm.repository

import kotlinx.coroutines.flow.Flow
import st.orm.*
import st.orm.template.PredicateBuilder
import st.orm.template.QueryBuilder
import st.orm.template.eq
import st.orm.template.inList
import kotlin.reflect.KClass
import kotlin.reflect.full.isSubclassOf

/**
 * Provides access to entity and projection repositories, as well as custom repository implementations.
 *
 * Entity repositories returned by [entity] provide full CRUD operations for database tables. Projection
 * repositories returned by [projection] provide read operations for database views and projection queries.
 * Custom repositories returned by [repository] allow specialized repository logic to be implemented as default
 * methods with full access to CRUD and [QueryBuilder] capabilities.
 *
 * In addition to the core repository lookup methods, this file provides a rich set of Kotlin extension functions
 * on `RepositoryLookup` for convenient, type-safe data access without explicit repository instantiation.
 *
 * ## Example: Custom repository
 * ```kotlin
 * interface UserRepository : EntityRepository<User, Int> {
 *     fun findByName(name: String): User? =
 *         find { User_.name eq name }
 *
 *     fun findByCity(city: City): List<User> =
 *         findAll { User_.city eq city }
 * }
 * ```
 *
 * ## Example: Extension function usage
 * ```kotlin
 * val orm = dataSource.orm
 * val users: List<User> = orm.findAll()
 * val alice: User? = orm.findBy(User_.name, "Alice")
 * ```
 *
 * @see EntityRepository
 * @see ProjectionRepository
 */
public interface RepositoryLookup {
    /**
     * Returns the repository for the given entity type.
     *
     * @param type the entity type.
     * @param <T> the entity type.
     * @param <ID> the type of the entity's primary key.
     * @return the repository for the given entity type.
     */
    public fun <T, ID : Any> entity(type: KClass<T>): EntityRepository<T, ID> where T : Entity<ID>

    /**
     * Returns the repository for the given projection type.
     *
     * @param type the projection type.
     * @param <T> the projection type.
     * @param <ID> the type of the projection's primary key, or Void if the projection specifies no primary key.
     * @return the repository for the given projection type.
     */
    public fun <T, ID : Any> projection(type: KClass<T>): ProjectionRepository<T, ID> where T : Projection<ID>

    /**
     * Returns a proxy for the repository of the given type.
     *
     * @param type the repository type.
     * @param <R> the repository type.
     * @return a proxy for the repository of the given type.
     */
    public fun <R : Repository> repository(type: KClass<R>): R

    /**
     * Returns dependency-aware write operations over mixed-type sets of entities.
     *
     * A write set lifts the per-repository write actions to collections spanning multiple entity types: entities
     * are partitioned by type, ordered by their foreign key dependencies, and written with one batch statement per
     * type per dependency level. Generated primary keys propagate to dependent entities within the set.
     *
     * ```kotlin
     * val owner = Owner(name = "Alice", address = address)   // unsaved
     * val wolfie = Pet(name = "Wolfie", type = dog, owner = owner)
     * val visit = Visit(visitDate = today, description = "Check-up", pet = wolfie)
     * orm.writeSet().insert(listOf(wolfie, visit))            // owner joins via insert discovery
     * ```
     *
     * @return the write set operations bound to this lookup.
     * @see WriteSet
     * @since 1.13
     */
    public fun writeSet(): WriteSet
}

// Kotlin specific DSL

/**
 * Runs [block] against this lookup's [WriteSet], grouping related write-set calls in one scope.
 *
 * Each action inside the block executes immediately; the block groups calls visually and does not defer or reorder
 * them. Combine with `transaction { }` when atomicity across the calls is required:
 *
 * ```kotlin
 * transaction {
 *     orm.writeSet {
 *         insert(pets + visits)
 *         update(changedOwners)
 *         remove(staleVisits)
 *     }
 * }
 * ```
 *
 * @since 1.13
 */
public inline fun <R> RepositoryLookup.writeSet(block: WriteSet.() -> R): R = writeSet().block()

/**
 * Runs [block] against the underlying ORM template's [WriteSet]; see [RepositoryLookup.writeSet].
 *
 * Convenience for repository methods; each action inside the block executes immediately.
 *
 * @since 1.13
 */
public inline fun <R> Repository.writeSet(block: WriteSet.() -> R): R = writeSet().block()

/**
 * Inserts the given entities and their discovered members and returns the primary keys of the explicit members, in
 * argument order; see [WriteSet.insertAndFetchIds].
 *
 * The batch is homogeneous in its id type; entity types may differ as long as they share it. The vararg form is a
 * Kotlin extension because the interface cannot offer it: `@SafeVarargs` is not applicable to interface default
 * methods, so a Java-side generic vararg would emit heap-pollution warnings at every call site.
 *
 * @since 1.13
 */
public fun <ID : Any> WriteSet.insertAndFetchIds(vararg entities: Entity<ID>): List<ID> = insertAndFetchIds(entities.asList())

/**
 * Upserts the given entities and their discovered members and returns the primary keys of the explicit members, in
 * argument order; see [WriteSet.upsertAndFetchIds].
 *
 * The batch is homogeneous in its id type; entity types may differ as long as they share it. The vararg form is a
 * Kotlin extension because the interface cannot offer it: `@SafeVarargs` is not applicable to interface default
 * methods, so a Java-side generic vararg would emit heap-pollution warnings at every call site.
 *
 * @since 1.13
 */
public fun <ID : Any> WriteSet.upsertAndFetchIds(vararg entities: Entity<ID>): List<ID> = upsertAndFetchIds(entities.asList())

/**
 * Returns the repository for entity type [T] with primary key type [ID].
 *
 * The primary key type can be inferred from the entity declaration with the underscore operator:
 * ```kotlin
 * val users = orm.entity<User, _>()   // EntityRepository<User, Int>
 * ```
 *
 * @since 1.12
 */
@JvmName("entityTyped")
public inline fun <reified T : Entity<ID>, ID : Any> RepositoryLookup.entity(): EntityRepository<T, ID> = entity(T::class)

/**
 * Returns the repository for entity type [T] without binding the primary key type.
 *
 * The resulting repository supports query building and record-based operations, but not ID-based operations such
 * as `findById`; use `entity<T, _>()` when the primary key type is needed.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Entity<*>> RepositoryLookup.entity(): EntityRepository<T, *> = entity(T::class as KClass<Entity<Any>>) as EntityRepository<T, *>

/**
 * Returns the repository for projection type [T] with primary key type [ID].
 *
 * The primary key type can be inferred from the projection declaration with the underscore operator:
 * ```kotlin
 * val ownerViews = orm.projection<OwnerView, _>()   // ProjectionRepository<OwnerView, Int>
 * ```
 *
 * @since 1.12
 */
@JvmName("projectionTyped")
public inline fun <reified T : Projection<ID>, ID : Any> RepositoryLookup.projection(): ProjectionRepository<T, ID> = projection(T::class)

/**
 * Returns the repository for projection type [T] without binding the primary key type.
 *
 * The resulting repository supports query building and record-based operations, but not ID-based operations such
 * as `findById`; use `projection<T, _>()` when the primary key type is needed.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Projection<*>> RepositoryLookup.projection(): ProjectionRepository<T, *> = projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<T, *>

/**
 * Returns the repository of type [R].
 */
public inline fun <reified R : Repository> RepositoryLookup.repository(): R = repository(R::class)

/**
 * Retrieves all records of type [T] from the repository.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @return list containing all records.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Data> RepositoryLookup.findAll(): List<T> = if (T::class.isSubclassOf(Entity::class)) {
    (entity(T::class as KClass<Entity<Any>>) as EntityRepository<Entity<*>, *>).findAll() as List<T>
} else {
    (projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<Projection<*>, *>).findAll() as List<T>
}

/**
 * Retrieves all records of type [T] from the repository.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @return list containing all records.
 */
public inline fun <reified T : Data> RepositoryLookup.findAllRef(): List<Ref<T>> = selectRef<T>().resultList

/**
 * Retrieves an optional record of type [T] based on a single field and its value.
 * Returns null if no matching record is found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return an optional record, or null if none found.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.findBy(field: Metamodel<T, V>, value: V): T? = select<T>().where(field eq value).optionalResult

/**
 * Retrieves an optional record of type [T] based on a single field and its value.
 * Returns null if no matching record is found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return an optional record, or null if none found.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.findBy(field: Metamodel<T, V>, value: Ref<V>): T? = select<T>().where(field, value).optionalResult

/**
 * Retrieves records of type [T] matching a single field and a single value.
 * Returns an empty list if no records are found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return list of matching records.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.findAllBy(field: Metamodel<T, V>, value: V): List<T> = select<T>().where(field eq value).resultList

/**
 * Retrieves records of type [T] matching a single field and a single value.
 * Returns an empty list if no records are found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return list of matching records.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.findAllBy(field: Metamodel<T, V>, value: Ref<V>): List<T> = select<T>().where(field, value).resultList

/**
 * Retrieves records of type [T] matching a single field against multiple values.
 * Returns an empty list if no records are found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param values Iterable of values to match against.
 * @return list of matching records.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.findAllBy(field: Metamodel<T, V>, values: Iterable<V>): List<T> = select<T>().where(field inList values).resultList

/**
 * Retrieves records of type [T] matching a single field against multiple values.
 * Returns an empty list if no records are found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param values Iterable of values to match against.
 * @return list of matching records.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.findAllByRef(field: Metamodel<T, V>, values: Iterable<Ref<V>>): List<T> = select<T>().whereRef(field, values).resultList

/**
 * Retrieves exactly one record of type [T] based on a single field and its value.
 * Throws an exception if no record or more than one record is found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return The matching record.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.getBy(field: Metamodel<T, V>, value: V): T = select<T>().where(field eq value).singleResult

/**
 * Retrieves exactly one record of type [T] based on a single field and its value.
 * Throws an exception if no record or more than one record is found.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return The matching record.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.getBy(field: Metamodel<T, V>, value: Ref<V>): T = select<T>().where(field, value).singleResult

/**
 * Retrieves an optional ref to a record of type [T] based on a single field and its value.
 * Returns null if no matching record is found.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return a ref to the matching record, or null if none found.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.findRefBy(field: Metamodel<T, V>, value: V): Ref<T>? = selectRef<T>().where(field eq value).optionalResult

/**
 * Retrieves an optional ref to a record of type [T] based on a single field and its value.
 * Returns null if no matching record is found.
 *
 * @param field metamodel reference of the record field.
 * @param value the value to match against.
 * @return a ref to the matching record, or null if none found.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.findRefBy(field: Metamodel<T, V>, value: Ref<V>): Ref<T>? = selectRef<T>().where(field, value).optionalResult

/**
 * Retrieves entities of type [T] matching a single field and a single value.
 * Returns an empty list if no entities are found.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return list of matching entities.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.findAllRefBy(field: Metamodel<T, V>, value: V): List<Ref<T>> = selectRef<T>().where(field eq value).resultList

/**
 * Retrieves entities of type [T] matching a single field and a single value.
 * Returns an empty list if no entities are found.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return list of matching entities.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.findAllRefBy(field: Metamodel<T, V>, value: Ref<V>): List<Ref<T>> = selectRef<T>().where(field, value).resultList

/**
 * Retrieves entities of type [T] matching a single field against multiple values.
 * Returns an empty list if no entities are found.
 *
 * @param field metamodel reference of the entity field.
 * @param values Iterable of values to match against.
 * @return list of matching entities.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.findAllRefBy(field: Metamodel<T, V>, values: Iterable<V>): List<Ref<T>> = selectRef<T>().where(field inList values).resultList

/**
 * Retrieves entities of type [T] matching a single field against multiple values.
 * Returns an empty list if no entities are found.
 *
 * @param field metamodel reference of the entity field.
 * @param values Iterable of values to match against.
 * @return list of matching entities.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.findAllRefByRef(field: Metamodel<T, V>, values: Iterable<Ref<V>>): List<Ref<T>> = selectRef<T>().whereRef(field, values).resultList

/**
 * Retrieves exactly one entity of type [T] based on a single field and its value.
 * Throws an exception if no entity or more than one entity is found.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return The matching entity.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.getRefBy(field: Metamodel<T, V>, value: V): Ref<T> = selectRef<T>().where(field eq value).singleResult

/**
 * Retrieves exactly one entity of type [T] based on a single field and its value.
 * Throws an exception if no entity or more than one entity is found.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return The matching entity.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.getRefBy(field: Metamodel<T, V>, value: Ref<V>): Ref<T> = selectRef<T>().where(field, value).singleResult

/**
 * Retrieves all records of type [T] matching the specified predicate.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param predicate the predicate to match against.
 * @return a list of matching records.
 */
public inline fun <reified T : Data> RepositoryLookup.findAll(predicate: PredicateBuilder<T, *, *>): List<T> = select<T>().where(predicate).resultList

/**
 * Retrieves refs to all records of type [T] matching the specified predicate.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param predicate the predicate to match against.
 * @return a list of refs to the matching records.
 */
public inline fun <reified T : Data> RepositoryLookup.findAllRef(predicate: PredicateBuilder<T, *, *>): List<Ref<T>> = selectRef<T>().where(predicate).resultList

/**
 * Retrieves an optional record of type [T] matching the specified predicate.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param predicate the predicate to match against.
 * @return the matching record, or null if none found.
 */
public inline fun <reified T : Data> RepositoryLookup.find(predicate: PredicateBuilder<T, *, *>): T? = select<T>().where(predicate).optionalResult

/**
 * Retrieves an optional ref to a record of type [T] matching the specified predicate.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param predicate the predicate to match against.
 * @return a ref to the matching record, or null if none found.
 */
public inline fun <reified T : Data> RepositoryLookup.findRef(predicate: PredicateBuilder<T, *, *>): Ref<T>? = selectRef<T>().where(predicate).optionalResult

/**
 * Retrieves exactly one record of type [T] matching the specified predicate.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param predicate the predicate to match against.
 * @return the matching record.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 */
public inline fun <reified T : Data> RepositoryLookup.get(predicate: PredicateBuilder<T, *, *>): T = select<T>().where(predicate).singleResult

/**
 * Retrieves a ref to exactly one record of type [T] matching the specified predicate.
 *
 * [T] must be either an Entity or Projection type.
 *
 * @param predicate the predicate to match against.
 * @return a ref to the matching record.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 */
public inline fun <reified T : Data> RepositoryLookup.getRef(predicate: PredicateBuilder<T, *, *>): Ref<T> = selectRef<T>().where(predicate).singleResult

/**
 * Creates a query builder to select records of type [T].
 *
 * [T] must be either an Entity or Projection type.
 *
 * @return A [QueryBuilder] for selecting records of type [T].
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Data> RepositoryLookup.select(): QueryBuilder<T, T, *> = if (T::class.isSubclassOf(Entity::class)) {
    (entity(T::class as KClass<Entity<Any>>) as EntityRepository<Entity<*>, *>).select() as QueryBuilder<T, T, *>
} else {
    (projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<Projection<*>, *>).select() as QueryBuilder<T, T, *>
}

/**
 * Creates a query builder to select records of type [T] matching the specified predicate.
 *
 * The entity type is inferred from the predicate:
 * ```kotlin
 * orm.select { User_.city eq city }.resultList
 * orm.select { User_.city eq city }.page(0, 20)
 * ```
 *
 * @return A [QueryBuilder] with the predicate applied.
 */
public inline fun <reified T : Data> RepositoryLookup.select(
    predicate: PredicateBuilder<T, *, *>,
): QueryBuilder<T, T, *> = select<T>().where(predicate)

/**
 * Creates a query builder to select references of entity records of type [T].
 *
 * @return A [QueryBuilder] for selecting references of entity records of type [T].
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Data> RepositoryLookup.selectRef(): QueryBuilder<T, Ref<T>, *> = if (T::class.isSubclassOf(Entity::class)) {
    (entity(T::class as KClass<Entity<Any>>) as EntityRepository<Entity<*>, *>).selectRef() as QueryBuilder<T, Ref<T>, *>
} else {
    (projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<Projection<*>, *>).selectRef() as QueryBuilder<T, Ref<T>, *>
}

/**
 * Creates a query builder to count records of type [T]; the counting counterpart of [select].
 *
 * [T] must be either an Entity or Projection type.
 *
 * @return A [QueryBuilder] counting records of type [T].
 */
@PublishedApi
@Suppress("UNCHECKED_CAST")
internal inline fun <reified T : Data> RepositoryLookup.selectCount(): QueryBuilder<T, Long, *> = if (T::class.isSubclassOf(Entity::class)) {
    (entity(T::class as KClass<Entity<Any>>) as EntityRepository<Entity<*>, *>).selectCount() as QueryBuilder<T, Long, *>
} else {
    (projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<Projection<*>, *>).selectCount() as QueryBuilder<T, Long, *>
}

/**
 * Counts entities of type [T] matching the specified field and value.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return the count of matching entities.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.countBy(field: Metamodel<T, V>, value: V): Long = selectCount<T>().where(field eq value).singleResult

/**
 * Counts entities of type [T] matching the specified field and referenced value.
 *
 * @param field metamodel reference of the entity field.
 * @param value the referenced value to match against.
 * @return the count of matching entities.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.countBy(field: Metamodel<T, V>, value: Ref<V>): Long = selectCount<T>().where(field, value).singleResult

/**
 * Counts all entities of type [T].
 *
 * @return the total count of entities.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Data> RepositoryLookup.countAll(): Long = if (T::class.isSubclassOf(Entity::class)) {
    (entity(T::class as KClass<Entity<Any>>) as EntityRepository<Entity<*>, *>).count()
} else {
    (projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<Projection<*>, *>).count()
}

/**
 * Counts entities of type [T] matching the specified predicate.
 *
 * @param predicate Lambda to build the WHERE clause.
 * @return the count of matching entities.
 */
public inline fun <reified T : Data> RepositoryLookup.count(predicate: PredicateBuilder<T, *, *>): Long = selectCount<T>().where(predicate).singleResult

/**
 * Checks if entities of type [T] matching the specified field and value exists.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return true if any matching entities exist, false otherwise.
 */
public inline fun <reified T : Data, V : Any> RepositoryLookup.existsBy(field: Metamodel<T, V>, value: V): Boolean = selectCount<T>().where(field eq value).singleResult > 0

/**
 * Checks if entities of type [T] matching the specified field and referenced value exists.
 *
 * @param field metamodel reference of the entity field.
 * @param value the referenced value to match against.
 * @return true if any matching entities exist, false otherwise.
 */
public inline fun <reified T : Data, V : Data> RepositoryLookup.existsBy(field: Metamodel<T, V>, value: Ref<V>): Boolean = selectCount<T>().where(field, value).singleResult > 0

/**
 * Checks if any entities of type [T] exist.
 *
 * @return true if any entities exist, false otherwise.
 */
@Suppress("UNCHECKED_CAST")
public inline fun <reified T : Data> RepositoryLookup.exists(): Boolean = if (T::class.isSubclassOf(Entity::class)) {
    (entity(T::class as KClass<Entity<Any>>) as EntityRepository<Entity<*>, *>).exists()
} else {
    (projection(T::class as KClass<Projection<Any>>) as ProjectionRepository<Projection<*>, *>).exists()
}

/**
 * Checks if entities of type [T] matching the specified predicate exists.
 *
 * @param predicate Lambda to build the WHERE clause.
 * @return true if any matching entities exist, false otherwise.
 */
public inline fun <reified T : Data> RepositoryLookup.exists(predicate: PredicateBuilder<T, *, *>): Boolean = selectCount<T>().where(predicate).singleResult > 0

/**
 * Inserts an entity of type [T] into the repository.
 *
 * @param entity The entity to insert.
 * @return The inserted entity after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.insert(entity: T): T = entity<T>().insertAndFetch(entity)

/**
 * Inserts multiple entities of type [T] into the repository.
 *
 * @param entities Iterable collection of entities to insert.
 * @return list of inserted entities after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.insert(entities: Iterable<T>): List<T> = entity<T>().insertAndFetch(entities)

/**
 * Inserts multiple entities of type [T] into the repository.
 *
 * @param entities Flow of entities to insert.
 * @return flow of inserted entities after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.insert(entities: Flow<T>): Flow<T> = entity<T>().insertAndFetch(entities)

/**
 * Upserts (inserts or updates) an entity of type [T] into the repository.
 *
 * @param entity The entity to upsert.
 * @return The upserted entity after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.upsert(entity: T): T = entity<T>().upsertAndFetch(entity)

/**
 * Upserts (inserts or updates) multiple entities of type [T] into the repository.
 *
 * @param entities Iterable collection of entities to upsert.
 * @return list of upserted entities after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.upsert(entities: Iterable<T>): List<T> = entity<T>().upsertAndFetch(entities)

/**
 * Upserts (inserts or updates) multiple entities of type [T] into the repository.
 *
 * @param entities Flow of entities to upsert.
 * @return flow of upserted entities after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.upsert(entities: Flow<T>): Flow<T> = entity<T>().upsertAndFetch(entities)

/**
 * Creates a query builder to delete records of type [T].
 *
 * @return A [QueryBuilder] for deleting records of type [T].
 */
public inline fun <reified T> RepositoryLookup.delete(): QueryBuilder<T, *, *>
        where T : Data, T : Entity<*> = entity<T>().delete()

/**
 * Removes an entity of type [T] from the repository.
 *
 * @param entity The entity to remove.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.remove(entity: T) = entity<T>().remove(entity)

/**
 * Removes multiple entities of type [T] from the repository.
 *
 * @param entity List of entities to remove.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.remove(entity: Iterable<T>) = entity<T>().remove(entity)

/**
 * Removes multiple entities of type [T] from the repository.
 *
 * @param entity Flow of entities to remove.
 */
public suspend inline infix fun <reified T : Entity<*>> RepositoryLookup.remove(entity: Flow<T>) = entity<T>().remove(entity)

/**
 * Removes an entity of type [T] from the repository.
 *
 * @param ref The entity to remove.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.removeByRef(ref: Ref<T>) = entity<T>().removeByRef(ref)

/**
 * Removes multiple entities of type [T] from the repository.
 *
 * @param refs List of entities to remove.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.removeByRef(refs: Iterable<Ref<T>>) = entity<T>().removeByRef(refs)

/**
 * Removes multiple entities of type [T] from the repository.
 *
 * @param refs Flow of entities to remove.
 */
public suspend inline infix fun <reified T : Entity<*>> RepositoryLookup.removeByRef(refs: Flow<Ref<T>>) = entity<T>().removeByRef(refs)

/**
 * Updates an entity of type [T] in the repository.
 *
 * @param entity The entity to update.
 * @return The updated entity after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.update(entity: T): T = entity<T>().updateAndFetch(entity)

/**
 * Updates multiple entities of type [T] in the repository.
 *
 * @param entities Iterable collection of entities to update.
 * @return list of updated entities after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.update(entities: Iterable<T>): List<T> = entity<T>().updateAndFetch(entities)

/**
 * Updates multiple entities of type [T] in the repository.
 *
 * @param entities Flow of entities to update.
 * @return flow of updated entities after fetching from the database.
 */
public inline infix fun <reified T : Entity<*>> RepositoryLookup.update(entities: Flow<T>): Flow<T> = entity<T>().updateAndFetch(entities)

/**
 * Removes all entities of type [T] from the repository.
 *
 * @return Unit
 */
public inline fun <reified T : Entity<*>> RepositoryLookup.removeAll() = entity<T>().removeAll()

/**
 * Removes entities of type [T] matching the specified predicate.
 *
 * @param predicate Lambda to build the WHERE clause.
 * @return the number of entities removed.
 */
public inline fun <reified T : Entity<*>> RepositoryLookup.removeAll(predicate: PredicateBuilder<T, *, *>): Int = entity<T>().removeAll(predicate)

/**
 * Removes entities of type [T] matching the specified ID field and its value.
 *
 * @param field metamodel reference of the ID field.
 * @param value the ID value to match against.
 * @return the number of entities removed (0 or 1).
 */
public inline fun <reified T : Entity<ID>, ID : Any> RepositoryLookup.removeBy(field: Metamodel<T, ID>, value: ID): Int = entity<T>().delete().where(field eq value).executeUpdate()

/**
 * Removes entities of type [T] matching the specified ID field and its value.
 *
 * @param field metamodel reference of the ID field.
 * @param value the ID value to match against.
 * @return the number of entities removed (0 or 1).
 */
public inline fun <reified T : Entity<*>> RepositoryLookup.removeBy(field: Metamodel<T, T>, value: Ref<T>): Int = entity<T>().delete().where(field, value).executeUpdate()

/**
 * Removes entities of type [T] matching the specified field and value.
 *
 * @param field metamodel reference of the entity field.
 * @param value the value to match against.
 * @return the number of entities removed.
 */
public inline fun <reified T : Entity<*>, V : Any> RepositoryLookup.removeAllBy(field: Metamodel<T, V>, value: V): Int = entity<T>().delete().where(field eq value).executeUpdate()

/**
 * Removes entities of type [T] matching the specified field and referenced value.
 *
 * @param field metamodel reference of the entity field.
 * @param value the referenced value to match against.
 * @return the number of entities removed.
 */
public inline fun <reified T : Entity<*>, V : Data> RepositoryLookup.removeAllBy(field: Metamodel<T, V>, value: Ref<V>): Int = entity<T>().delete().where(field, value).executeUpdate()

/**
 * Removes entities of type [T] matching the specified field against multiple values.
 *
 * @param field metamodel reference of the entity field.
 * @param values Iterable of values to match against.
 * @return the number of entities removed.
 */
public inline fun <reified T : Entity<*>, V : Any> RepositoryLookup.removeAllBy(field: Metamodel<T, V>, values: Iterable<V>): Int = entity<T>().delete().where(field inList values).executeUpdate()

/**
 * Removes entities of type [T] matching the specified field against multiple referenced values.
 *
 * @param field metamodel reference of the entity field.
 * @param values Iterable of referenced values to match against.
 * @return the number of entities removed.
 */
public inline fun <reified T, V> RepositoryLookup.removeAllByRef(
    field: Metamodel<T, V>,
    values: Iterable<Ref<V>>,
): Int where T : Entity<*>, V : Data = entity<T>().delete().whereRef(field, values).executeUpdate()
