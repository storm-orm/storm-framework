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
import st.orm.Data
import st.orm.Entity
import st.orm.Metamodel
import st.orm.Operator.EQUALS
import st.orm.Operator.IN
import st.orm.Page
import st.orm.Pageable
import st.orm.Ref
import st.orm.Scrollable
import st.orm.Window
import st.orm.template.*
import kotlin.reflect.KClass

/**
 * Provides a generic interface with CRUD operations for entities.
 *
 * <h1>Using Entity Repositories</h1>
 *
 * Entity repositories provide a high-level abstraction for managing entities in the database. They offer a set of
 * methods for creating, reading, updating, and deleting entities, as well as querying and filtering entities based on
 * specific criteria. The `EntityRepository` interface is designed to work with entity records that implement the
 * [Entity] interface, providing a consistent and type-safe way to interact with the database.
 *
 * <h2>Entity Definition</h2>
 *
 * Define the entity records to use them to in combination with repositories. The [Entity] interface is a
 * marker interface that indicates that the record is an entity and has a primary key of type `ID`. The [st.orm.PK]
 * annotation is used to mark the primary key field of the entity record. The [st.orm.FK] annotation is used to mark
 * the foreign key field of the entity record. The [st.orm.Inline] annotation (optional) is used to mark the record
 * component that is inlined in the entity record.
 *
 * Example:
 * ```
 * data class City(
 *   @PK val id: Int = 0,
 *   val name: String,
 *   val population: Long
 * ) : Entity<Int>
 *
 * data class Address(
 *   val street: String,
 *   val postalCode: String,
 *   @FK val city City
 * )
 *
 * data class User(
 *   @PK val id: Int = 0,
 *   val email: String,
 *   val birthDate: LocalDate,
 *   val address: Address
 * ) : Entity<Int>
 *```
 *
 * <h2>Repository Lookup</h2>
 *
 * An entity repository can be obtained by invoking `entity` on an `ORMTemplate` with the desired entity
 * class. The orm template can be requested as demonstrated below. Note that orm templates are supported for
 * Data Sources, JDBC Connections and JPA Entity Managers.
 * <pre>`ORMTemplate orm = Templates.ORM(dataSource);
 * EntityRepository<User> userRepository = orm.entity(User.class);
`</pre> *
 *
 * Alternatively, a specialized repository can be requested by calling the `repository` method with the repository
 * class. Specialized repositories allow specialized repository methods to be defined in the repository interface. The
 * specialized repository can be used to implement specialized queries or operations that are specific to the entity type.
 * The specialized logic can utilize the [QueryBuilder] interface to build SELECT and DELETE statements.
 *
 * ```
 * interface UserRepository : EntityRepository<User> {
 *
 * // Specialized repository methods go here:
 *
 *   fun findByEmail(String email): User? =
 *     find { User_.email eq email }
 * }
 *
 * val userRepository = orm.repository<UserRepository>()
 * ```
 *
 * <h2>Repository Injection</h2>
 *
 * A specialized repository can also be injected using Spring's dependency injection mechanism when the
 * `storm-spring` package is included in the project. Check the storm-spring package to lean how to make
 * repositories available to the application for dependency injection.
 *
 * <h2>CRUD Operations</h2>
 *
 * Entity repositories provide a set of methods for creating, reading, updating, and deleting entities in the
 * database. The following sections provide examples of how to use these methods to interact with the database.
 *
 * <h3>Create</h3>
 *
 * Insert a user into the database. The template engine also supports insertion of multiple entries in batch mode by
 * passing a list of entities. Alternatively, insertion can also be executed using a flow of entities.
 * ```
 * val user: User = ...
 * orm insert user
 * ```
 *
 * <h3>Read</h3>
 *
 * Select all users from the database that are linked to cities with the name "Sunnyvale". The static metamodel is
 * used to specify the City entity in the QueryBuilder's entity graph.
 *
 * ```
 * val cities: List<City> = cityRepository.findByName("Sunnyvale")
 * val users: List<User> = orm.findAll { User_.address.city inList cities }
 * ```
 *
 * Alternatively, `select { ... }` can be invoked to load the users lazily.
 *
 * The QueryBuilder also allows the previous queries to be combined into a single select query, using the
 * User's static metamodel to specify the city name field in the QueryBuilder's entity graph.
 *
 * ```
 * val users: List<User> = orm.findAll { User_.address.city.name eq "Sunnyvale" }   // Type-safe metamodel.
 * ```
 *
 * <h4>Update</h4>
 *
 * Update a user in the database. The repository also supports updates for multiple entries in batch model by passing
 * a list of entities. Alternatively, updates can also be executed using a flow of entities.
 * ```
 * orm update user
 * ```
 *
 * <h3>Delete</h3>
 *
 * Delete user in the database. The repository also supports updates for multiple entries in batch mode by passing a
 * list entities or primary keys. Alternatively, deletion can be executed in using a flow of entities.
 * ```
 * orm delete user
 * ```
 *
 * Also here, the QueryBuilder can be used to create specialized statement, for instance, to delete all users where
 * the email field IS NULL.
 * ```
 * orm.delete { User_.email IS_NULL } // Type-safe metamodel.
 * ```
 *
 * @see QueryBuilder
 * @param <E> the type of entity managed by this repository.
 * @param <ID> the type of the primary key of the entity.
 */
interface EntityRepository<E, ID : Any> : Repository where E : Entity<ID> {

    /**
     * The entity model associated with this repository.
     */
    val model: Model<E, ID>

    /**
     * Creates a new ref entity instance with the specified primary key.
     *
     *
     * This method creates a lightweight reference that encapsulates only the primary key of an entity,
     * without loading the full entity data into memory. The complete record can be fetched on demand by invoking
     * [Ref.fetch], which will trigger a separate database call.
     *
     * @param id the primary key of the entity.
     * @return a ref entity instance containing only the primary key.
     * @since 1.3
     */
    fun ref(id: ID): Ref<E>

    /**
     * Creates a new ref entity instance for the specified entity.
     *
     *
     * This method wraps a fully loaded entity in a lightweight reference. Although the complete entity is provided,
     * the returned ref retains only the primary key for identification. In this case, calling [Ref.fetch] will
     * return the full entity (which is already loaded), ensuring a consistent API for accessing entity records on
     * demand. This approach supports lazy-loading scenarios where only the identifier is needed initially.
     *
     * @param entity the entity to wrap in a ref.
     * @return a ref entity instance containing the primary key of the provided entity.
     * @since 1.3
     */
    fun ref(entity: E): Ref<E>

    /**
     * Unloads the given entity from memory by converting it into a lightweight ref containing only its primary key.
     *
     *
     * This method discards the full entity data and returns an attached ref that encapsulates just the primary key.
     * The actual record is not retained in memory, but can be retrieved on demand by calling [Ref.fetch],
     * which will trigger a new database call. This approach is particularly useful when you need to minimize memory
     * usage while keeping the option to re-fetch the complete record later.
     *
     * Unlike [Ref.unload], which returns a detached ref, this method returns an attached ref that can
     * re-fetch the entity from the database.
     *
     * @param entity the entity to unload into a lightweight ref.
     * @return an attached ref containing only the primary key of the entity, allowing the full record to be fetched
     * again when needed.
     * @since 1.3
     */
    fun unload(entity: E): Ref<E>

    // Query builder methods.

    /**
     * Creates a new query builder for selecting entities of the type managed by this repository.
     *
     * @return a new query builder for the entity type.
     */
    fun select(): QueryBuilder<E, E, ID>

    /**
     * Constructs a SELECT query using a block-based DSL.
     *
     * A [PredicateBuilder] returned as the block's last expression is automatically applied as a WHERE clause,
     * so `select { path eq value }` is equivalent to `select { where(path eq value) }`.
     *
     * ```kotlin
     * interface UserRepository : EntityRepository<User, Int> {
     *     fun findActive(): List<User> = select {
     *         where(User_.active eq true)
     *         orderBy(User_.name)
     *     }.resultList
     * }
     * ```
     */
    @Suppress("UNCHECKED_CAST")
    fun select(block: SqlScope<E, E, ID>.() -> Any?): QueryBuilder<E, E, ID> {
        val scope = SqlScope(select())
        scope.applyResult(scope.block())
        return scope.builder
    }

    /**
     * Creates a new query builder for the entity type managed by this repository.
     *
     * @return a new query builder for the entity type.
     */
    fun selectCount(): QueryBuilder<E, Long, ID>

    /**
     * Creates a new query builder for the specialized `selectType`.
     *
     * @param selectType the result type of the query.
     * @return a new query builder for the specialized `selectType`.
     * @param <R> the result type of the query.
     */
    fun <R : Any> select(selectType: KClass<R>): QueryBuilder<E, R, ID>

    /**
     * Creates a new query builder for selecting refs to entities of the type managed by this repository.
     *
     *
     * This method is typically used when you only need the primary keys of the entities initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full entity, call [Ref.fetch], which will perform an
     * additional database query on demand.
     *
     * @return a new query builder for selecting refs to entities.
     * @since 1.3
     */
    fun selectRef(): QueryBuilder<E, Ref<E>, ID>

    /**
     * Creates a new query builder for the specialized `selectType` and specialized `template` for the select clause.
     *
     * @param selectType the result type of the query.
     * @param template the specialized template for the select clause.
     * @return a new query builder for the specialized `selectType`.
     * @param <R> the result type of the query.
     */
    fun <R : Any> select(selectType: KClass<R>, template: TemplateBuilder): QueryBuilder<E, R, ID> = select(selectType, template.build())

    /**
     * Creates a new query builder for the specialized `selectType` and specialized `template` for the select clause.
     *
     * @param selectType the result type of the query.
     * @param template the specialized template for the select clause.
     * @return a new query builder for the specialized `selectType`.
     * @param <R> the result type of the query.
     */
    fun <R : Any> select(selectType: KClass<R>, template: TemplateString): QueryBuilder<E, R, ID>

    /**
     * Creates a new query builder for selecting refs to entities of the type managed by this repository.
     *
     *
     * This method is typically used when you only need the primary keys of the entities initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full entity, call [Ref.fetch], which will perform an
     * additional database query on demand.
     *
     * @param refType the type that is selected as ref.
     * @return a new query builder for selecting refs to entities.
     * @since 1.3
     */
    fun <R : Data> selectRef(refType: KClass<R>): QueryBuilder<E, Ref<R>, ID>

    /**
     * Creates a new query builder for delete entities of the type managed by this repository.
     *
     * @return a new query builder for the entity type.
     */
    fun delete(): QueryBuilder<E, *, ID>

    /**
     * Constructs and executes a DELETE statement using a block-based DSL.
     *
     * A [PredicateBuilder] returned as the block's last expression is automatically applied as a WHERE clause,
     * so `delete { path eq value }` is equivalent to `delete { where(path eq value) }`.
     *
     * ```kotlin
     * interface UserRepository : EntityRepository<User, Int> {
     *     fun deleteInactive(): Int = delete {
     *         where(User_.active eq false)
     *     }
     * }
     * ```
     *
     * @return the number of rows deleted.
     */
    @Suppress("UNCHECKED_CAST")
    fun delete(block: SqlScope<E, Any?, ID>.() -> Any?): Int {
        val scope = SqlScope(delete() as QueryBuilder<E, Any?, ID>)
        scope.applyResult(scope.block())
        return scope.builder.executeUpdate()
    }

    // Base methods.

    /**
     * Returns the number of entities in the database of the entity type supported by this repository.
     *
     * @return the total number of entities in the database as a long value.
     * @throws st.orm.PersistenceException if the count operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun count(): Long

    /**
     * Checks if any entity of the type managed by this repository exists in the database.
     *
     * @return true if at least one entity exists, false otherwise.
     * @throws st.orm.PersistenceException if there is an underlying database issue during the count operation.
     */
    fun exists(): Boolean

    /**
     * Checks if an entity with the specified primary key exists in the database.
     *
     *
     * This method determines the presence of an entity by checking if the count of entities with the given primary
     * key is greater than zero. It leverages the `selectCount` method, which performs a count operation on the
     * database.
     *
     * @param id the primary key of the entity to check for existence.
     * @return true if an entity with the specified primary key exists, false otherwise.
     * @throws st.orm.PersistenceException if there is an underlying database issue during the count operation.
     */
    fun existsById(id: ID): Boolean

    /**
     * Checks if an entity with the specified primary key exists in the database.
     *
     *
     * This method determines the presence of an entity by checking if the count of entities with the given primary
     * key is greater than zero. It leverages the `selectCount` method, which performs a count operation on the
     * database.
     *
     * @param ref the primary key of the entity to check for existence, expressed as a ref.
     * @return true if an entity with the specified primary key exists, false otherwise.
     * @throws st.orm.PersistenceException if there is an underlying database issue during the count operation.
     */
    fun existsByRef(ref: Ref<E>): Boolean

    /**
     * Inserts an entity into the database.
     *
     *
     * This method adds a new entity to the database. It ensures that the entity is persisted according to the defined
     * database constraints and entity model. It's critical for the entity to be fully initialized as per the entity
     * model requirements.
     *
     * @param entity the entity to insert. The entity must satisfy all model constraints.
     * @throws st.orm.PersistenceException if the insert operation fails. This can happen due to a variety of reasons,
     * including database constraints violations, connectivity issues, or if the entity parameter is null.
     */
    fun insert(entity: E)

    /**
     * Inserts an entity into the database.
     *
     *
     * This method adds a new entity to the database. It ensures that the entity is persisted according to the defined
     * database constraints and entity model. It's critical for the entity to be fully initialized as per the entity
     * model requirements.
     *
     * @param entity the entity to insert. The entity must satisfy all model constraints.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @throws st.orm.PersistenceException if the insert operation fails. This can happen due to a variety of reasons,
     * including database constraints violations, connectivity issues, or if the entity parameter is null.
     */
    fun insert(entity: E, ignoreAutoGenerate: Boolean)

    /**
     * Inserts an entity into the database and returns its primary key.
     *
     *
     * This method adds a new entity to the database and upon successful insertion, returns the primary key assigned to
     * the entity when the primary key is generated by the database (e.g., auto-incremented). Otherwise, if the primary
     * key is not generated by the database, the method returns an empty optional.
     *
     * @param entity the entity to insert. The entity must satisfy all model constraints.
     * @return the generated primary key of the successfully inserted entity.
     * @throws st.orm.PersistenceException if the insert operation fails for reasons such as database constraints violations,
     * connectivity issues, or if the entity parameter is null.
     */
    fun insertAndFetchId(entity: E): ID

    /**
     * Inserts a single entity into the database and returns the inserted entity with its current state.
     *
     *
     * This method inserts the provided entity into the database. Upon successful insertion, it returns
     * the entity as it exists in the database after the operation. This ensures that the returned entity
     * includes any modifications applied during the insertion process, such as generated primary keys,
     * default values, or other automatic changes triggered by the database.
     *
     * @param entity the entity to be inserted. The entity must be non-null and contain valid data for insertion
     * into the database.
     * @return the inserted entity, reflecting its state in the database after insertion. This includes any
     * database-applied changes such as primary key assignments or default values.
     * @throws st.orm.PersistenceException if the insertion operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun insertAndFetch(entity: E): E

    /**
     * Updates a single entity in the database.
     *
     *
     * This method updates the provided entity in the database, modifying its existing record to reflect the
     * current state of the entity. It is intended for cases where only one entity needs to be updated.
     *
     * @param entity the entity to be updated. The entity must be non-null and contain valid data for updating
     * its corresponding record in the database.
     * @throws st.orm.PersistenceException if the update operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun update(entity: E)

    /**
     * Updates a single entity in the database and returns the updated entity with its current state.
     *
     *
     * This method updates the provided entity in the database and, upon successful completion,
     * returns the entity as it exists in the database after the update operation. This ensures that the returned
     * entity reflects any modifications applied during the update process, such as updated timestamps,
     * versioning, or other automatic changes triggered by the database.
     *
     * @param entity the entity to be updated. The entity must be non-null and contain valid data for updating
     * its corresponding record in the database.
     * @return the updated entity, reflecting its state in the database after the update. This includes any
     * database-applied changes such as modified timestamps or version numbers.
     * @throws st.orm.PersistenceException if the update operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun updateAndFetch(entity: E): E

    /**
     * Inserts or updates a single entity in the database.
     *
     *
     * This method performs an "upsert" operation on the provided entity. If the entity does not already exist
     * in the database, it will be inserted. If it does exist, it will be updated to reflect the current state of
     * the entity. This approach ensures that the entity is either created or brought up-to-date, depending on
     * its existence in the database.
     *
     * @param entity the entity to be inserted or updated. The entity must be non-null and contain valid data
     * for insertion or update in the database.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun upsert(entity: E)

    /**
     * Inserts or updates a single entity in the database and returns its ID.
     *
     *
     * This method performs an "upsert" operation on the provided entity. If the entity does not already exist
     * in the database, it will be inserted; if it exists, it will be updated. Upon successful completion,
     * the method returns the ID of the entity as stored in the database. This approach ensures that the entity
     * is either created or brought up-to-date, depending on its existence in the database.
     *
     * @param entity the entity to be inserted or updated. The entity must be non-null and contain valid data
     * for insertion or update in the database.
     * @return the ID of the upserted entity, reflecting its identifier in the database.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun upsertAndFetchId(entity: E): ID

    /**
     * Inserts or updates a single entity in the database and returns the entity with its current state.
     *
     *
     * This method performs an "upsert" operation on the provided entity. If the entity does not already exist
     * in the database, it will be inserted; if it exists, it will be updated. Upon successful completion,
     * the method returns the entity as it exists in the database after the upsert operation. This ensures that
     * the returned entity reflects any modifications applied during the upsert process, such as generated primary keys,
     * updated timestamps, or default values set by the database.
     *
     * @param entity the entity to be inserted or updated. The entity must be non-null and contain valid data
     * for insertion or update in the database.
     * @return the upserted entity, reflecting its current state in the database. This includes any
     * database-applied changes, such as primary key assignments, default values, or timestamp updates.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun upsertAndFetch(entity: E): E

    /**
     * Deletes an entity from the database.
     *
     *
     * This method removes an existing entity from the database. The entity must exist in the database; if it does
     * not, a [st.orm.PersistenceException] is thrown. Unlike [deleteById] and [deleteByRef], this method is strict
     * rather than idempotent, because possessing the full entity implies the caller expects it to exist.
     *
     * @param entity the entity to delete. The entity must exist in the database and should be correctly identified by
     * its primary key.
     * @throws st.orm.PersistenceException if the deletion operation fails. Reasons for failure might include the entity not
     * being found in the database, violations of database constraints, connectivity
     * issues, or if the entity parameter is null.
     */
    fun delete(entity: E)

    /**
     * Deletes an entity from the database based on its primary key.
     *
     *
     * This method ensures the entity with the given primary key is removed from the database. If the entity does
     * not exist, the operation completes successfully without error (idempotent behavior).
     *
     * @param id the primary key of the entity to delete.
     * @throws st.orm.PersistenceException if the deletion operation fails due to violations of database constraints,
     * connectivity issues, or if the id parameter is null.
     */
    fun deleteById(id: ID)

    /**
     * Deletes an entity from the database by its reference.
     *
     *
     * This method ensures the entity identified by the given reference is removed from the database. If the entity
     * does not exist, the operation completes successfully without error (idempotent behavior).
     *
     * @param ref the reference to the entity to delete.
     * @throws st.orm.PersistenceException if the deletion operation fails due to violations of database constraints,
     * connectivity issues, or if the ref parameter is null.
     */
    fun deleteByRef(ref: Ref<E>)

    /**
     * Deletes all entities from the database.
     *
     *
     * This method performs a bulk deletion operation, removing all instances of the entities managed by this
     * repository from the database.
     *
     * @throws st.orm.PersistenceException if the bulk deletion operation fails. Failure can occur for several reasons,
     * including but not limited to database access issues, transaction failures, or
     * underlying database constraints that prevent the deletion of certain records.
     */
    fun deleteAll()

    // Singular findBy methods.

    /**
     * Retrieves an entity based on its primary key.
     *
     *
     * This method performs a lookup in the database, returning the corresponding entity if it exists.
     *
     * @param id the primary key of the entity to retrieve.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun findById(id: ID): E?

    /**
     * Retrieves an entity based on its primary key, expressed by a ref.
     *
     *
     * This method performs a lookup in the database, returning the corresponding entity if it exists.
     *
     * @param ref the ref to match.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun findByRef(ref: Ref<E>): E?

    /**
     * Retrieves an entity based on its primary key.
     *
     *
     * This method performs a lookup in the database, returning the corresponding entity if it exists.
     *
     * @param id the primary key of the entity to retrieve.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws st.orm.NoResultException if no entity is found matching the given primary key, indicating that there's no
     * corresponding data in the database.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun getById(id: ID): E

    /**
     * Retrieves an entity based on its primary key, expressed by a ref.
     *
     *
     * This method performs a lookup in the database, returning the corresponding entity if it exists.
     *
     * @param ref the ref to match.
     * @return the entity associated with the provided primary key. The returned entity encapsulates all relevant data
     * as mapped by the entity model.
     * @throws st.orm.NoResultException if no entity is found matching the given primary key, indicating that there's no
     * corresponding data in the database.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun getByRef(ref: Ref<E>): E

    // Singular findBy / getBy methods for unique keys.

    /**
     * Retrieves an entity by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the entity matching the given key value, or null if none exists.
     * @since 1.9
     */
    fun <V : Any> findBy(key: Metamodel.Key<E, V>, value: V): E?

    /**
     * Retrieves an entity by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the entity matching the given key value.
     * @throws st.orm.NoResultException if no entity is found matching the given key value.
     * @since 1.9
     */
    fun <V : Any> getBy(key: Metamodel.Key<E, V>, value: V): E

    /**
     * Retrieves an entity by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the entity matching the given ref value, or null if none exists.
     * @since 1.9
     */
    fun <V : Data> findByRef(key: Metamodel.Key<E, V>, value: Ref<V>): E?

    /**
     * Retrieves an entity by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the entity matching the given ref value.
     * @throws st.orm.NoResultException if no entity is found matching the given ref value.
     * @since 1.9
     */
    fun <V : Data> getByRef(key: Metamodel.Key<E, V>, value: Ref<V>): E

    // List based methods.

    /**
     * Returns a list of all entities of the type supported by this repository. Each element in the list represents
     * an entity in the database, encapsulating all relevant data as mapped by the entity model.
     *
     * **Note:** Loading all entities into memory at once can be very memory-intensive if your
     * table is large.
     *
     * @return a list of all entities of the type supported by this repository.
     * @throws st.orm.PersistenceException if the selection operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun findAll(): List<E>

    /**
     * Retrieves a list of entities based on their primary keys.
     *
     * This method retrieves entities matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     *
     * **Note:** The order of entities in the returned list is not guaranteed to match the order of IDs
     * in the input collection, as the database may not preserve insertion order during retrieval.
     *
     * @param ids the primary keys of the entities to retrieve, represented as an iterable collection.
     * @return a list of entities corresponding to the provided primary keys. Entities are returned without any
     * guarantee of order alignment with the input list. If an ID does not correspond to any entity in the
     * database, no corresponding entity will be included in the returned list.
     * @throws st.orm.PersistenceException if the selection operation fails due to database issues, such as connectivity
     * problems or invalid input parameters.
     */
    fun findAllById(ids: Iterable<ID>): List<E>

    /**
     * Retrieves a list of entities based on their primary keys.
     *
     *
     * This method retrieves entities matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     *
     *
     *
     * **Note:** The order of entities in the returned list is not guaranteed to match the order of IDs
     * in the input collection, as the database may not preserve insertion order during retrieval.
     *
     * @param refs the primary keys of the entities to retrieve, represented as an iterable collection.
     * @return a list of entities corresponding to the provided primary keys. Entities are returned without any
     * guarantee of order alignment with the input list. If an ID does not correspond to any entity in the
     * database, no corresponding entity will be included in the returned list.
     * @throws st.orm.PersistenceException if the selection operation fails due to database issues, such as connectivity
     * problems or invalid input parameters.
     */
    fun findAllByRef(refs: Iterable<Ref<E>>): List<E>

    /**
     * Inserts a collection of entities into the database in batches.
     *
     *
     * This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     * be non-null and contain valid data for insertion.
     * @throws st.orm.PersistenceException if the insertion operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun insert(entities: Iterable<E>)

    /**
     * Inserts a collection of entities into the database in batches.
     *
     *
     * This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     * be non-null and contain valid data for insertion.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @throws st.orm.PersistenceException if the insertion operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun insert(entities: Iterable<E>, ignoreAutoGenerate: Boolean)

    /**
     * Inserts a collection of entities into the database in batches.
     *
     *
     * This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.
     *
     *
     * Upon successful insertion, it returns the primary keys assigned to the entities when the primary keys are
     * generated by the database (e.g., auto-incremented). Otherwise, if the primary keys are not generated by the
     * database, the method returns an empty list.
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     * be non-null and contain valid data for insertion.
     * @return the primary keys assigned to the entities when the primary keys are generated by the database,
     * @throws st.orm.PersistenceException if the insertion operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun insertAndFetchIds(entities: Iterable<E>): List<ID>

    /**
     * Inserts a collection of entities into the database in batches.
     *
     *
     * This method processes the provided entities in batches, optimizing insertion for larger collections by
     * reducing database overhead. Batch processing helps ensure that even large numbers of entities can be
     * inserted efficiently and minimizes potential memory and performance issues.
     *
     *
     * Upon successful insertion, it returns the entities that were inserted. The returned entities reflect the
     * state of the entities as they exist in the database after the insertion operation. This ensures that the
     * returned entities include any changes that might have been applied during the insertion process, such as
     * primary key, default values or triggers.
     *
     * @param entities an iterable collection of entities to be inserted. Each entity in the collection must
     * be non-null and contain valid data for insertion.
     * @return the entities that were inserted into the database.
     * @throws st.orm.PersistenceException if the insertion operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun insertAndFetch(entities: Iterable<E>): List<E>

    /**
     * Updates a collection of entities in the database in batches.
     *
     *
     * This method processes the provided entities in batches to optimize updating of larger collections,
     * reducing database overhead and improving performance. Batch processing allows efficient handling of
     * bulk updates, minimizing memory and processing costs.
     *
     * @param entities an iterable collection of entities to be updated. Each entity in the collection must
     * be non-null and contain valid, up-to-date data for modification in the database.
     * @throws st.orm.PersistenceException if the update operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    fun update(entities: Iterable<E>)

    /**
     * Updates a collection of entities in the database in batches and returns a list of the updated entities.
     *
     *
     * This method processes the provided entities in batches, optimizing performance for larger collections by
     * reducing database overhead. Upon successful update, it returns the entities as they exist in the database
     * after the update operation. This ensures that the returned entities reflect any modifications applied during
     * the update process, such as updated timestamps, versioning, or other automatic changes made by the database.
     *
     * @param entities an iterable collection of entities to be updated. Each entity in the collection must be non-null
     * and contain valid data for modification in the database.
     * @return a list of entities reflecting their state in the database after the update. The order of entities in
     * the returned list is not guaranteed to match the order of the input collection.
     * @throws st.orm.PersistenceException if the update operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun updateAndFetch(entities: Iterable<E>): List<E>

    /**
     * Inserts or updates a collection of entities in the database in batches.
     *
     *
     * This method processes the provided entities in batches, optimizing performance for larger collections by
     * reducing database overhead. For each entity, the method performs an "upsert" operation, meaning it will insert
     * the entity if it does not already exist in the database, or update it if it does. This approach ensures that
     * the entities are either created or brought up-to-date, depending on their existence in the database.
     *
     * @param entities an iterable collection of entities to be inserted or updated. Each entity in the collection must
     * be non-null and contain valid data for insertion or update in the database.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsert(entities: Iterable<E>)

    /**
     * Inserts or updates a collection of entities in the database in batches and returns a list of their IDs.
     *
     *
     * This method processes the provided entities in batches to optimize performance for larger collections,
     * reducing database overhead. For each entity, the method performs an "upsert" operation, inserting the entity
     * if it does not already exist in the database, or updating it if it does. Upon successful completion,
     * the method returns a list of the IDs of the upserted entities, reflecting their identifiers as stored
     * in the database.
     *
     * @param entities an iterable collection of entities to be inserted or updated. Each entity in the collection
     * must be non-null and contain valid data for insertion or update in the database.
     * @return a list of IDs corresponding to the upserted entities. The order of IDs in the returned list
     * is not guaranteed to match the order of the input collection.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsertAndFetchIds(entities: Iterable<E>): List<ID>

    /**
     * Inserts or updates a collection of entities in the database in batches and returns a list of the upserted
     * entities.
     *
     *
     * This method processes the provided entities in batches, optimizing performance for larger collections
     * by reducing database overhead. For each entity, it performs an "upsert" operation, inserting the entity if it
     * does not already exist in the database, or updating it if it does. Upon successful completion, it returns
     * the entities as they exist in the database after the operation. This ensures that the returned entities reflect
     * any changes applied during the upsert process, such as generated primary keys, updated timestamps, or default
     * values set by the database.
     *
     * @param entities an iterable collection of entities to be inserted or updated. Each entity in the collection
     * must be non-null and contain valid data for insertion or update in the database.
     * @return a list of upserted entities reflecting their current state in the database. The order of entities
     * in the returned list is not guaranteed to match the order of the input collection.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsertAndFetch(entities: Iterable<E>): List<E>

    /**
     * Deletes a collection of entities from the database in batches.
     *
     *
     * This method processes the provided entities in batches to optimize performance when handling larger collections,
     * reducing database overhead. For each entity in the collection, the method removes the corresponding record from
     * the database, if it exists. Batch processing ensures efficient handling of deletions, particularly for large data sets.
     *
     * @param entities an iterable collection of entities to be deleted. Each entity in the collection must be non-null
     * and represent a valid database record for deletion.
     * @throws st.orm.PersistenceException if the deletion operation fails due to database issues, such as connectivity problems
     * or constraints violations.
     */
    fun delete(entities: Iterable<E>)

    /**
     * Deletes a collection of entities from the database in batches.
     *
     *
     * This method processes the provided entities in batches to optimize performance when handling larger collections,
     * reducing database overhead. For each entity in the collection, the method removes the corresponding record from
     * the database, if it exists. Batch processing ensures efficient handling of deletions, particularly for large data sets.
     *
     * @param refs an iterable collection of entities to be deleted. Each entity in the collection must be non-null
     * and represent a valid database record for deletion.
     * @throws st.orm.PersistenceException if the deletion operation fails due to database issues, such as connectivity problems
     * or constraints violations.
     */
    fun deleteByRef(refs: Iterable<Ref<E>>)

    /**
     * Retrieves a flow of entities based on their primary keys.
     *
     * This method executes queries in batches, depending on the number of primary keys in the specified ids flow.
     * This optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities.
     * The batching strategy enhances performance, particularly when dealing with large sets of primary keys.
     *
     * The resulting flow is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the flow. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * **Note:** Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the flow holds resources open
     * while in use, it must be closed after usage to prevent resource leaks. As the flow is `AutoCloseable`, it
     * is recommended to use it within a `try-with-resources` block.
     *
     * @param ids a flow of entity IDs to retrieve from the repository.
     * @return a flow of entities corresponding to the provided primary keys. The order of entities in the flow is
     * not guaranteed to match the order of ids in the input flow. If an id does not correspond to any entity
     * in the database, it will simply be skipped, and no corresponding entity will be included in the returned
     * flow. If the same entity is requested multiple times, it may be included in the flow multiple times
     * if it is part of a separate batch.
     * @throws st.orm.PersistenceException if the selection operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun selectById(ids: Flow<ID>): Flow<E>

    /**
     * Retrieves a flow of entities based on their primary keys.
     *
     * This method executes queries in batches, depending on the number of primary keys in the specified ids flow.
     * This optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities.
     * The batching strategy enhances performance, particularly when dealing with large sets of primary keys.
     *
     * The resulting flow is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the flow. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * **Note:** Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the flow holds resources open
     * while in use, it must be closed after usage to prevent resource leaks. As the flow is `AutoCloseable`, it
     * is recommended to use it within a `try-with-resources` block.
     *
     * @param refs a flow of refs to retrieve from the repository.
     * @return a flow of entities corresponding to the provided primary keys. The order of entities in the flow is
     * not guaranteed to match the order of ids in the input flow. If an id does not correspond to any entity
     * in the database, it will simply be skipped, and no corresponding entity will be included in the returned
     * flow. If the same entity is requested multiple times, it may be included in the flow multiple times
     * if it is part of a separate batch.
     * @throws st.orm.PersistenceException if the selection operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun selectByRef(refs: Flow<Ref<E>>): Flow<E>

    /**
     * Retrieves a flow of entities based on their primary keys.
     *
     * This method executes queries in batches, with the batch size determined by the provided parameter. This
     * optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities. The
     * batching strategy enhances performance, particularly when dealing with large sets of primary keys.
     *
     * The resulting flow is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the flow. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * **Note:** Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the flow holds resources open
     * while in use, it must be closed after usage to prevent resource leaks. As the flow is `AutoCloseable`, it
     * is recommended to use it within a `try-with-resources` block.
     *
     * @param ids a flow of entity IDs to retrieve from the repository.
     * @param chunkSize the number of primary keys to include in each batch. This parameter determines the size of the
     * batches used to execute the selection operation. A larger batch size can improve performance, especially when
     * dealing with large sets of primary keys.
     * @return a flow of entities corresponding to the provided primary keys. The order of entities in the flow is
     * not guaranteed to match the order of refs in the input flow. If an id does not correspond to any entity in the
     * database, it will simply be skipped, and no corresponding entity will be included in the returned flow. If the
     * same entity is requested multiple times, it may be included in the flow multiple times if it is part of a
     * separate batch.
     * @throws st.orm.PersistenceException if the selection operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun selectById(ids: Flow<ID>, chunkSize: Int): Flow<E>

    /**
     * Retrieves a flow of entities based on their primary keys.
     *
     * This method executes queries in batches, with the batch size determined by the provided parameter. This
     * optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve entities. The
     * batching strategy enhances performance, particularly when dealing with large sets of primary keys.
     *
     * The resulting flow is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the flow. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * **Note:** Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the flow holds resources open
     * while in use, it must be closed after usage to prevent resource leaks. As the flow is `AutoCloseable`, it
     * is recommended to use it within a `try-with-resources` block.
     *
     * @param refs a flow of refs to retrieve from the repository.
     * @param chunkSize the number of primary keys to include in each batch. This parameter determines the size of the
     * batches used to execute the selection operation. A larger batch size can improve performance, especially when
     * dealing with large sets of primary keys.
     * @return a flow of entities corresponding to the provided primary keys. The order of entities in the flow is
     * not guaranteed to match the order of refs in the input flow. If an id does not correspond to any entity in the
     * database, it will simply be skipped, and no corresponding entity will be included in the returned flow. If the
     * same entity is requested multiple times, it may be included in the flow multiple times if it is part of a
     * separate batch.
     * @throws st.orm.PersistenceException if the selection operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun selectByRef(refs: Flow<Ref<E>>, chunkSize: Int): Flow<E>

    /**
     * Counts the number of entities identified by the provided flow of IDs using the default batch size.
     *
     * This method calculates the total number of entities that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.
     *
     * @param ids a flow of IDs for which to count matching entities.
     * @return the total count of entities matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countById(ids: Flow<ID>): Long

    /**
     * Counts the number of entities identified by the provided flow of IDs, with the counting process divided into
     * batches of the specified size.
     *
     * This method performs the counting operation in batches, specified by the `chunkSize` parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.
     *
     * @param ids a flow of IDs for which to count matching entities.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @return the total count of entities matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countById(ids: Flow<ID>, chunkSize: Int): Long

    /**
     * Counts the number of entities identified by the provided flow of refs using the default batch size.
     *
     * This method calculates the total number of entities that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.
     *
     * @param refs a flow of IDs for which to count matching entities.
     * @return the total count of entities matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countByRef(refs: Flow<Ref<E>>): Long

    /**
     * Counts the number of entities identified by the provided flow of refs, with the counting process divided into
     * batches of the specified size.
     *
     * This method performs the counting operation in batches, specified by the `chunkSize` parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.
     *
     * @param refs a flow of IDs for which to count matching entities.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @return the total count of entities matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countByRef(refs: Flow<Ref<E>>, chunkSize: Int): Long

    /**
     * Inserts entities in a batch mode to optimize performance and reduce database load.
     *
     * For large volumes of entities, this method processes the inserts in multiple batches to ensure efficient
     * handling and minimize the impact on database resources. This structured approach facilitates the management of
     * large-scale insert operations.
     *
     * @param entities the entities to insert. Must not be null.
     * @throws st.orm.PersistenceException if the insert fails due to database constraints, connectivity issues, or if the
     * entities parameter is null.
     */
    suspend fun insert(entities: Flow<E>)

    /**
     * Inserts entities in a batch mode to optimize performance and reduce database load.
     *
     * For large volumes of entities, this method processes the inserts in multiple batches to ensure efficient
     * handling and minimize the impact on database resources. This structured approach facilitates the management of
     * large-scale insert operations.
     *
     * @param entities the entities to insert. Must not be null.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @throws st.orm.PersistenceException if the insert fails due to database constraints, connectivity issues, or if the
     * entities parameter is null.
     */
    suspend fun insert(entities: Flow<E>, ignoreAutoGenerate: Boolean)

    /**
     * Inserts a flow of entities into the database, with the insertion process divided into batches of the specified
     * size.
     *
     * This method inserts entities provided in a flow and uses the specified batch size for the insertion
     * operation.  Batching the inserts can greatly enhance performance by minimizing the number of database
     * interactions, especially useful when dealing with large volumes of data.
     *
     * @param entities a flow of entities to insert. Each entity must not be null and must conform to the model
     * constraints.
     * @param batchSize the size of the batches to use for the insertion operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @throws st.orm.PersistenceException if there is an error during the insertion operation, such as a violation of database
     * constraints, connectivity issues, or if any entity in the flow is null.
     */
    suspend fun insert(entities: Flow<E>, batchSize: Int)

    /**
     * Inserts a flow of entities into the database, with the insertion process divided into batches of the specified
     * size.
     *
     * This method inserts entities provided in a flow and uses the specified batch size for the insertion
     * operation.  Batching the inserts can greatly enhance performance by minimizing the number of database
     * interactions, especially useful when dealing with large volumes of data.
     *
     * @param entities a flow of entities to insert. Each entity must not be null and must conform to the model
     * constraints.
     * @param batchSize the size of the batches to use for the insertion operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @param ignoreAutoGenerate true to ignore the auto-generate flag on the primary key and explicitly insert the
     * provided primary key value. Use this flag only when intentionally providing the primary
     * key value (e.g., migrations, data exports).
     * @throws st.orm.PersistenceException if there is an error during the insertion operation, such as a violation of database
     * constraints, connectivity issues, or if any entity in the flow is null.
     */
    suspend fun insert(entities: Flow<E>, batchSize: Int, ignoreAutoGenerate: Boolean)

    /**
     * Inserts a flow of entities into the database using the default batch size and returns a flow of their
     * generated primary keys.
     *
     * This method facilitates the insertion of entities and fetches their primary keys immediately after insertion.
     * This is particularly useful when the primary keys are generated by the database (e.g., auto-increment fields). It
     * uses the default batch size to optimize the number of database interactions.
     *
     * @param entities a flow of entities to insert. Each entity must not be null and must conform to the model
     * constraints.
     * @throws st.orm.PersistenceException if there is an error during the insertion or key retrieval operation, such as a
     * violation of database constraints, connectivity issues, or if any entity in the
     * flow is null.
     */
    fun insertAndFetchIds(entities: Flow<E>): Flow<ID>

    /**
     * Inserts a flow of entities into the database using the default batch size and returns a flow of the inserted entities.
     *
     * This method inserts entities into the database and retrieves them immediately after insertion. It is useful
     * for ensuring that the returned entities reflect any database-generated values or defaults. The insertion and
     * retrieval are performed using the default batch size to optimize database performance.
     *
     * @param entities a flow of entities to insert. Each entity must not be null and must conform to the model
     * constraints.
     * @throws st.orm.PersistenceException if there is an error during the insertion or retrieval operation, such as a
     * violation of database constraints, connectivity issues, or if any entity in the
     * flow is null.
     */
    fun insertAndFetch(entities: Flow<E>): Flow<E>

    /**
     * Inserts a flow of entities into the database with the insertion process divided into batches of the specified size,
     * and returns a flow of their generated primary keys.
     *
     * This method allows for efficient insertion of a large number of entities by batching them according to the
     * specified batch size. It also fetches the primary keys immediately after insertion, useful for entities with
     * database-generated keys.
     *
     * @param entities a flow of entities to insert. Each entity must not be null and must conform to the model
     * constraints.
     * @param batchSize the size of the batches to use for the insertion operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @throws st.orm.PersistenceException if there is an error during the insertion or key retrieval operation, such as a
     * violation of database constraints, connectivity issues, or if any entity in the
     * flow is null.
     */
    fun insertAndFetchIds(entities: Flow<E>, batchSize: Int): Flow<ID>

    /**
     * Inserts a flow of entities into the database with the insertion process divided into batches of the specified size,
     * and returns a flow of the inserted entities.
     *
     * This method provides an efficient way to insert a large number of entities by batching them according to the
     * specified batch size. It fetches the inserted entities immediately after insertion to ensure that the returned
     * entities reflect any database-generated values or defaults. This is particularly useful when database triggers or
     * default values are involved.
     *
     * @param entities a flow of entities to insert. Each entity must not be null and must conform to the model
     * constraints.
     * @param batchSize the size of the batches to use for the insertion operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @throws st.orm.PersistenceException if there is an error during the insertion or retrieval operation, such as a
     * violation of database constraints, connectivity issues, or if any entity in the
     * flow is null.
     */
    fun insertAndFetch(entities: Flow<E>, batchSize: Int): Flow<E>

    /**
     * Updates a flow of entities in the database using the default batch size.
     *
     * This method updates entities provided in a flow, optimizing the update process by batching them
     * with a default size. This helps to reduce the number of database operations and can significantly improve
     * performance when updating large numbers of entities.
     *
     * @param entities a flow of entities to update. Each entity must not be null, must already exist in the database,
     * and must conform to the model constraints.
     * @throws st.orm.PersistenceException if there is an error during the update operation, such as a violation of database
     * constraints, connectivity issues, or if any entity in the flow is null.
     */
    suspend fun update(entities: Flow<E>)

    /**
     * Updates a flow of entities in the database, with the update process divided into batches of the specified size.
     *
     * This method updates entities provided in a flow and uses the specified batch size for the update operation.
     * Batching the updates can greatly enhance performance by minimizing the number of database interactions,
     * especially useful when dealing with large volumes of data.
     *
     * @param entities a flow of entities to update. Each entity must not be null, must already exist in the database,
     * and must conform to the model constraints.
     * @param batchSize the size of the batches to use for the update operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @throws st.orm.PersistenceException if there is an error during the update operation, such as a violation of database
     * constraints, connectivity issues, or if any entity in the flow is null.
     */
    suspend fun update(entities: Flow<E>, batchSize: Int)

    /**
     * Updates a flow of entities in the database using the default batch size and returns a flow of the updated entities.
     *
     * This method updates entities provided in a flow, optimizing the update process by batching them with the
     * default size. It fetches the updated entities immediately after updating to ensure that the returned entities
     * reflect any database-generated values or defaults. This is particularly useful when database triggers or default
     * values are involved.
     *
     * @param entities a flow of entities to update. Each entity must not be null, must already exist in the database,
     * and must conform to the model constraints.
     * @throws st.orm.PersistenceException if there is an error during the update or retrieval operation, such as a violation
     * of database constraints, connectivity issues, or if any entity in the flow is
     * null.
     */
    fun updateAndFetch(entities: Flow<E>): Flow<E>

    /**
     * Updates a flow of entities in the database, with the update process divided into batches of the specified size,
     * and returns a flow of the updated entities.
     *
     * This method updates entities provided in a flow and uses the specified batch size for the update operation.
     * Batching the updates can greatly enhance performance by minimizing the number of database interactions,
     * especially useful when dealing with large volumes of data. It fetches the updated entities immediately after
     * updating to ensure that the returned entities reflect any database-generated values or defaults.
     *
     * @param entities a flow of entities to update. Each entity must not be null, must already exist in the database,
     * and must conform to the model constraints.
     * @param batchSize the size of the batches to use for the update operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @throws st.orm.PersistenceException if there is an error during the update or retrieval operation, such as a violation
     * of database constraints, connectivity issues, or if any entity in the flow is
     * null.
     */
    fun updateAndFetch(entities: Flow<E>, batchSize: Int): Flow<E>

    /**
     * Inserts or updates a flow of entities in the database in batches.
     *
     * This method processes the provided flow of entities in batches, performing an "upsert" operation on each.
     * For each entity, it will be inserted into the database if it does not already exist; if it does exist, it will
     * be updated to reflect the current state of the entity. Batch processing optimizes the performance of the
     * upsert operation for larger data sets by reducing database overhead.
     *
     * @param entities a flow of entities to be inserted or updated. Each entity in the flow must be non-null
     * and contain valid data for insertion or update in the database.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    suspend fun upsert(entities: Flow<E>)

    /**
     * Inserts or updates a flow of entities in the database in configurable batch sizes.
     *
     * This method processes the provided flow of entities in batches, performing an "upsert" operation on each.
     * For each entity, it will be inserted if it does not already exist in the database, or updated if it does.
     * The batch size can be configured to control the number of entities processed in each database operation,
     * allowing for optimized performance and memory management based on system requirements.
     *
     * @param entities a flow of entities to be inserted or updated. Each entity in the flow must be non-null
     * and contain valid data for insertion or update in the database.
     * @param batchSize the number of entities to process in each batch. A larger batch size may improve performance
     * but increase memory usage, while a smaller batch size may reduce memory usage but increase
     * the number of database operations.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity
     * problems, constraints violations, or invalid entity data.
     */
    suspend fun upsert(entities: Flow<E>, batchSize: Int)

    /**
     * Inserts or updates a flow of entities in the database in batches and retrieves their IDs through a callback.
     *
     * This method processes the provided flow of entities in batches, performing an "upsert" operation on each entity.
     * For each entity, it will be inserted if it does not already exist in the database or updated if it does. After
     * each batch operation, the IDs of the upserted entities are passed to the provided callback, allowing for
     * specialized handling of the IDs as they are retrieved.
     *
     * @param entities a flow of entities to be inserted or updated. Each entity in the flow must be non-null and
     * contain valid data for insertion or update in the database.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsertAndFetchIds(entities: Flow<E>): Flow<ID>

    /**
     * Inserts or updates a flow of entities in the database in batches and retrieves the updated entities through a callback.
     *
     * This method processes the provided flow of entities in batches, performing an "upsert" operation on each entity.
     * For each entity, it will be inserted if it does not already exist in the database or updated if it does. After
     * each batch operation, the updated entities are passed to the provided callback, allowing for specialized handling
     * of the entities as they are retrieved. The entities returned reflect their current state in the database, including
     * any changes such as generated primary keys, timestamps, or default values set by the database during the upsert process.
     *
     * @param entities a flow of entities to be inserted or updated. Each entity in the flow must be non-null and
     * contain valid data for insertion or update in the database.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsertAndFetch(entities: Flow<E>): Flow<E>

    /**
     * Inserts or updates a flow of entities in the database in configurable batch sizes and retrieves their IDs through a callback.
     *
     * This method processes the provided flow of entities in batches, performing an "upsert" operation on each entity.
     * For each entity, it will be inserted if it does not already exist in the database or updated if it does. The batch size
     * parameter allows control over the number of entities processed in each batch, optimizing memory and performance based
     * on system requirements. After each batch operation, the IDs of the upserted entities are passed to the provided
     * callback, allowing for specialized handling of the IDs as they are retrieved.
     *
     * @param entities a flow of entities to be inserted or updated. Each entity in the flow must be non-null and contain
     * valid data for insertion or update in the database.
     * @param batchSize the number of entities to process in each batch. Adjusting the batch size can optimize performance
     * and memory usage, with larger sizes potentially improving performance but using more memory.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsertAndFetchIds(entities: Flow<E>, batchSize: Int): Flow<ID>

    /**
     * Inserts or updates a flow of entities in the database in configurable batch sizes and retrieves the updated entities through a callback.
     *
     * This method processes the provided flow of entities in batches, performing an "upsert" operation on each entity.
     * For each entity, it will be inserted if it does not already exist in the database or updated if it does. The
     * `chunkSize` parameter allows control over the number of entities processed in each batch, optimizing performance
     * and memory usage based on system requirements. After each batch operation, the updated entities are passed to
     * the provided callback, allowing for specialized handling of the entities as they are retrieved. The entities
     * returned reflect their current state in the database, including any changes such as generated primary keys,
     * timestamps, or default values applied during the upsert process.
     *
     * @param entities a flow of entities to be inserted or updated. Each entity in the flow must be non-null and
     * contain valid data for insertion or update in the database.
     * @param batchSize the number of entities to process in each batch. Adjusting the batch size can optimize performance
     * and memory usage, with larger sizes potentially improving performance but using more memory.
     * @throws st.orm.PersistenceException if the upsert operation fails due to database issues, such as connectivity problems,
     * constraints violations, or invalid entity data.
     */
    fun upsertAndFetch(entities: Flow<E>, batchSize: Int): Flow<E>

    /**
     * Deletes a flow of entities from the database in batches.
     *
     * This method processes the provided flow of entities in batches to optimize performance for larger
     * data sets, reducing database overhead during deletion. For each entity in the flow, the method removes
     * the corresponding record from the database, if it exists. Batch processing allows efficient handling
     * of deletions, particularly for large collections of entities.
     *
     * @param entities a flow of entities to be deleted. Each entity in the flow must be non-null and represent
     * a valid database record for deletion.
     * @throws st.orm.PersistenceException if the deletion operation fails due to database issues, such as connectivity problems
     * or constraints violations.
     */
    suspend fun delete(entities: Flow<E>)

    /**
     * Deletes a flow of entities from the database in configurable batch sizes.
     *
     * This method processes the provided flow of entities in batches, with the size of each batch specified
     * by the `chunkSize` parameter. This allows for control over the number of entities deleted in each database
     * operation, optimizing performance and memory usage based on system requirements. For each entity in the
     * flow, the method removes the corresponding record from the database, if it exists.
     *
     * @param entities a flow of entities to be deleted. Each entity in the flow must be non-null and represent
     * a valid database record for deletion.
     * @param batchSize the number of entities to process in each batch. Larger batch sizes may improve performance
     * but require more memory, while smaller batch sizes may reduce memory usage but increase
     * the number of database operations.
     * @throws st.orm.PersistenceException if the deletion operation fails due to database issues, such as connectivity problems
     * or constraints violations.
     */
    suspend fun delete(entities: Flow<E>, batchSize: Int)

    /**
     * Deletes a flow of entities from the database in batches.
     *
     * This method processes the provided flow of entities in batches to optimize performance for larger
     * data sets, reducing database overhead during deletion. For each entity in the flow, the method removes
     * the corresponding record from the database, if it exists. Batch processing allows efficient handling
     * of deletions, particularly for large collections of entities.
     *
     * @param refs a flow of entities to be deleted. Each entity in the flow must be non-null and represent
     * a valid database record for deletion.
     * @throws st.orm.PersistenceException if the deletion operation fails due to database issues, such as connectivity problems
     * or constraints violations.
     */
    suspend fun deleteByRef(refs: Flow<Ref<E>>)

    /**
     * Deletes a flow of entities from the database in configurable batch sizes.
     *
     * This method processes the provided flow of entities in batches, with the size of each batch specified
     * by the `chunkSize` parameter. This allows for control over the number of entities deleted in each database
     * operation, optimizing performance and memory usage based on system requirements. For each entity in the
     * flow, the method removes the corresponding record from the database, if it exists.
     *
     * @param refs a flow of entities to be deleted. Each entity in the flow must be non-null and represent
     * valid database record for deletion.
     * @param batchSize the number of entities to process in each batch. Larger batch sizes may improve performance
     * but require more memory, while smaller batch sizes may reduce memory usage but increase
     * the number of database operations.
     * @throws st.orm.PersistenceException if the deletion operation fails due to database issues, such as connectivity problems
     * or constraints violations.
     */
    suspend fun deleteByRef(refs: Flow<Ref<E>>, batchSize: Int)

    // Kotlin specific DSL

    /**
     * Retrieves all entities of type [E] from the repository.
     *
     * @return a list containing all entities.
     */
    fun findAllRef(): List<Ref<E>> = selectRef().resultList

    /**
     * Retrieves an optional entity of type [E] based on a single field and its value.
     * Returns null if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <V> findBy(field: Metamodel<E, V>, value: V): E? = select().where(field, EQUALS, value).optionalResult

    /**
     * Retrieves an optional entity of type [E] based on a single field and its value.
     * Returns null if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <V : Data> findBy(field: Metamodel<E, V>, value: Ref<V>): E? = select().where(field, value).optionalResult

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return list of matching entities.
     */
    fun <V> findAllBy(field: Metamodel<E, V>, value: V): List<E> = select().where(field, EQUALS, value).resultList

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a sequence of matching entities.
     */
    fun <V> selectBy(field: Metamodel<E, V>, value: V): Flow<E> = select().where(field, EQUALS, value).resultFlow

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllBy(field: Metamodel<E, V>, value: Ref<V>): List<E> = select().where(field, value).resultList

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a sequence of matching entities.
     */
    fun <V : Data> selectBy(field: Metamodel<E, V>, value: Ref<V>): Flow<E> = select().where(field, value).resultFlow

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return list of matching entities.
     */
    fun <V> findAllBy(field: Metamodel<E, V>, values: Iterable<V>): List<E> = select().where(field, IN, values).resultList

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return at sequence of matching entities.
     */
    fun <V> selectBy(field: Metamodel<E, V>, values: Iterable<V>): Flow<E> = select().where(field, IN, values).resultFlow

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllByRef(field: Metamodel<E, V>, values: Iterable<Ref<V>>): List<E> = select().whereRef(field, values).resultList

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty sequence if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a sequence of matching entities.
     */
    fun <V : Data> selectByRef(field: Metamodel<E, V>, values: Iterable<Ref<V>>): Flow<E> = select().whereRef(field, values).resultFlow

    /**
     * Retrieves exactly one entity of type [E] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V> getBy(field: Metamodel<E, V>, value: V): E = select().where(field, EQUALS, value).singleResult

    /**
     * Retrieves exactly one entity of type [E] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V : Data> getBy(field: Metamodel<E, V>, value: Ref<V>): E = select().where(field, value).singleResult

    /**
     * Retrieves an optional entity of type [E] based on a single field and its value.
     * Returns a ref with a null value if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <T, ID, V> findRefBy(field: Metamodel<E, V>, value: V): Ref<E>? = selectRef().where(field, EQUALS, value).optionalResult

    /**
     * Retrieves an optional entity of type [E] based on a single field and its value.
     * Returns a ref with a null value if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <V : Data> findRefBy(field: Metamodel<E, V>, value: Ref<V>): Ref<E>? = selectRef().where(field, value).optionalResult

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a list of matching entities.
     */
    fun <V> findAllRefBy(field: Metamodel<E, V>, value: V): List<Ref<E>> = selectRef().where(field, EQUALS, value).resultList

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a sequence of matching entities.
     */
    fun <V> selectRefBy(field: Metamodel<E, V>, value: V): Flow<Ref<E>> = selectRef().where(field, EQUALS, value).resultFlow

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllRefBy(field: Metamodel<E, V>, value: Ref<V>): List<Ref<E>> = selectRef().where(field, value).resultList

    /**
     * Retrieves entities of type [E] matching a single field and a single value.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a sequence of matching entities.
     */
    fun <V : Data> selectRefBy(field: Metamodel<E, V>, value: Ref<V>): Flow<Ref<E>> = selectRef().where(field, value).resultFlow

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllRefBy(field: Metamodel<E, V>, values: Iterable<V>): List<Ref<E>> = selectRef().where(field, IN, values).resultList

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a sequence of matching entities.
     */
    fun <V> selectRefBy(field: Metamodel<E, V>, values: Iterable<V>): Flow<Ref<E>> = selectRef().where(field, IN, values).resultFlow

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllRefByRef(field: Metamodel<E, V>, values: Iterable<Ref<V>>): List<Ref<E>> = selectRef().whereRef(field, values).resultList

    /**
     * Retrieves entities of type [E] matching a single field against multiple values.
     * Returns an empty sequence if no entities are found.
     *
     * The resulting sequence is lazily loaded, meaning that the entities are only retrieved from the database as they
     * are consumed by the sequence. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of entities.
     *
     * Note: Calling this method does trigger the execution of the underlying
     * query, so it should only be invoked when the query is intended to run. Since the sequence holds resources open
     * while in use, it must be closed after usage to prevent resource leaks.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a sequence of matching entities.
     */
    fun <V : Data> selectRefByRef(field: Metamodel<E, V>, values: Iterable<Ref<V>>): Flow<Ref<E>> = selectRef().whereRef(field, values).resultFlow

    /**
     * Retrieves exactly one entity of type [E] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V> getRefBy(field: Metamodel<E, V>, value: V): Ref<E> = selectRef().where(field, EQUALS, value).singleResult

    /**
     * Retrieves exactly one entity of type [E] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V : Data> getRefBy(field: Metamodel<E, V>, value: Ref<V>): Ref<E> = selectRef().where(field, value).singleResult

    /**
     * Retrieves entities of type [E] matching the specified predicate.
     *
     * @return a list of matching entities.
     */
    fun findAll(predicate: PredicateBuilder<E, *, *>): List<E> = select().where(predicate).resultList

    /**
     * Retrieves entities of type [E] matching the specified predicate.
     *
     * @return a list of matching entities.
     */
    fun findAllRef(predicate: PredicateBuilder<E, *, *>): List<Ref<E>> = selectRef().where(predicate).resultList

    /**
     * Retrieves an optional entity of type [E] matching the specified predicate.
     * Returns null if no matching entity is found.
     *
     * @return an optional entity, or null if none found.
     */
    fun find(
        predicate: PredicateBuilder<E, *, *>,
    ): E? = select().where(predicate).optionalResult

    /**
     * Retrieves an optional entity of type [E] matching the specified predicate.
     * Returns a ref with a null value if no matching entity is found.
     *
     * @return an optional entity, or null if none found.
     */
    fun findRef(
        predicate: PredicateBuilder<E, *, *>,
    ): Ref<E>? = selectRef().where(predicate).optionalResult

    /**
     * Retrieves a single entity of type [E] matching the specified predicate.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun get(
        predicate: PredicateBuilder<E, *, *>,
    ): E = select().where(predicate).singleResult

    /**
     * Retrieves a single entity of type [E] matching the specified predicate.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun getRef(
        predicate: PredicateBuilder<E, *, *>,
    ): Ref<E> = selectRef().where(predicate).singleResult

    /**
     * Counts entities of type [E] matching the specified field and value.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the count of matching entities.
     */
    fun <V> countBy(
        field: Metamodel<E, V>,
        value: V,
    ): Long = selectCount().where(field, EQUALS, value).singleResult

    /**
     * Counts entities of type [E] matching the specified field and referenced value.
     *
     * @param field metamodel reference of the entity field.
     * @param value the referenced value to match against.
     * @return the count of matching entities.
     */
    fun <V : Data> countBy(
        field: Metamodel<E, V>,
        value: Ref<V>,
    ): Long = selectCount().where(field, value).singleResult

    /**
     * Counts entities of type [E] matching the specified predicate.
     *
     * @param predicate Lambda to build the WHERE clause.
     * @return the count of matching entities.
     */
    fun count(
        predicate: PredicateBuilder<E, *, *>,
    ): Long = selectCount().where(predicate).singleResult

    /**
     * Checks if entities of type [E] matching the specified field and value exists.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return true if any matching entities exist, false otherwise.
     */
    fun <V> existsBy(
        field: Metamodel<E, V>,
        value: V,
    ): Boolean = selectCount().where(field, EQUALS, value).singleResult > 0

    /**
     * Checks if entities of type [E] matching the specified field and referenced value exists.
     *
     * @param field metamodel reference of the entity field.
     * @param value the referenced value to match against.
     * @return true if any matching entities exist, false otherwise.
     */
    fun <V : Data> existsBy(
        field: Metamodel<E, V>,
        value: Ref<V>,
    ): Boolean = selectCount().where(field, value).singleResult > 0

    /**
     * Checks if entities of type [E] matching the specified predicate exists.
     *
     * @param predicate Lambda to build the WHERE clause.
     * @return true if any matching entities exist, false otherwise.
     */
    fun exists(
        predicate: PredicateBuilder<E, *, *>,
    ): Boolean = selectCount().where(predicate).singleResult > 0

    /**
     * Deletes entities of type [E] matching the specified field and value.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the number of entities deleted.
     */
    fun <V> deleteAllBy(
        field: Metamodel<E, V>,
        value: V,
    ): Int = delete().where(field, EQUALS, value).executeUpdate()

    /**
     * Deletes entities of type [E] matching the specified field and referenced value.
     *
     * @param field metamodel reference of the entity field.
     * @param value the referenced value to match against.
     * @return the number of entities deleted.
     */
    fun <V : Data> deleteAllBy(
        field: Metamodel<E, V>,
        value: Ref<V>,
    ): Int = delete().where(field, value).executeUpdate()

    /**
     * Deletes entities of type [E] matching the specified field against multiple values.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return the number of entities deleted.
     */
    fun <V> deleteAllBy(
        field: Metamodel<E, V>,
        values: Iterable<V>,
    ): Int = delete().where(field, IN, values).executeUpdate()

    /**
     * Deletes entities of type [E] matching the specified field against multiple referenced values.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of referenced values to match against.
     * @return the number of entities deleted.
     */
    fun <V : Data> deleteAllByRef(
        field: Metamodel<E, V>,
        values: Iterable<Ref<V>>,
    ): Int = delete().whereRef(field, values).executeUpdate()

    /**
     * Returns a page of entities using offset-based pagination.
     *
     * This method executes two queries: a `SELECT COUNT(*)` to determine the total number of entities, and
     * a query with OFFSET and LIMIT to fetch the content for the requested page.
     *
     * Page numbers are zero-based: pass `0` for the first page.
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of entities per page.
     * @return a page containing the results and pagination metadata.
     * @since 1.10
     */
    fun page(pageNumber: Int, pageSize: Int): Page<E>

    /**
     * Returns a page of entities using offset-based pagination.
     *
     * This method executes two queries: a `SELECT COUNT(*)` to determine the total number of entities, and
     * a query with OFFSET and LIMIT to fetch the content for the requested page.
     *
     * Use [Pageable.ofSize] for the first page, then navigate with
     * [Page.nextPageable] or [Page.previousPageable].
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @since 1.10
     */
    fun page(pageable: Pageable): Page<E>

    /**
     * Executes a scroll request from a [Scrollable] token, typically obtained from
     * [Window.nextScrollable] or [Window.previousScrollable].
     *
     * @param scrollable the scroll request containing cursor state, key, sort, size, and direction.
     * @return a window containing the entity results for the requested scroll position.
     * @since 1.11
     */
    fun scroll(scrollable: Scrollable<E>): Window<E> = Window.of(select().scroll(scrollable))
}
