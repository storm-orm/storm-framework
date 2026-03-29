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
import st.orm.Operator.EQUALS
import st.orm.Operator.IN
import st.orm.template.*
import kotlin.reflect.KClass

/**
 * Provides a generic interface with read operations for projections.
 *
 * Projection repositories provide a high-level abstraction for reading projections in the database. They offer a
 * set of methods for reading projections, as well as querying and filtering entities based on specific criteria. The
 * repository interface is designed to work with entity records that implement the [Projection] interface,
 * providing a consistent and type-safe way to interact with the database.
 *
 * @since 1.1
 * @see QueryBuilder
 *
 * @param <P> the type of projection managed by this repository.
 * @param <ID> the type of the primary key of the projection, or [Void] if the projection has no primary key.
 */
interface ProjectionRepository<P, ID : Any> : Repository where P : Projection<ID> {
    /**
     * Returns the projection model associated with this repository.
     *
     * @return the projection model.
     */
    val model: Model<P, ID>

    /**
     * Creates a new ref projection instance with the specified primary key.
     *
     * @param id the primary key of the projection.
     * @return a ref projection instance.
     */
    fun ref(id: ID): Ref<P>

    /**
     * Creates a new ref projection instance with the specified projection.
     *
     * @param projection the projection.
     * @return a ref projection instance.
     */
    fun ref(projection: P, id: ID): Ref<P>

    // Query builder methods.
    /**
     * Creates a new query builder for the projection type managed by this repository.
     *
     * @return a new query builder for the projection type.
     */
    fun select(): QueryBuilder<P, P, ID>

    /**
     * Constructs a SELECT query using a block-based DSL.
     *
     * The block uses scope methods (e.g., `where`, `orderBy`, `limit`) to construct the query.
     *
     * ```kotlin
     * interface OwnerViewRepository : ProjectionRepository<OwnerView, Int> {
     *     fun findByCity(city: City): List<OwnerView> = select {
     *         where(OwnerView_.city eq city)
     *         orderBy(OwnerView_.lastName)
     *     }.resultList
     * }
     * ```
     */
    fun select(block: SqlScope<P, P, ID>.() -> Any?): QueryBuilder<P, P, ID> {
        val scope = SqlScope(select())
        scope.validateResult(scope.block())
        return scope.builder
    }

    /**
     * Constructs a SELECT query filtered by the given predicate.
     *
     * ```kotlin
     * ownerViewRepository.select(OwnerView_.city eq city).resultList
     * ```
     */
    fun select(predicate: PredicateBuilder<P, *, *>): QueryBuilder<P, P, ID> = select().where(predicate)

    /**
     * Creates a new query builder for the projection type managed by this repository.
     *
     * @return a new query builder for the projection type.
     */
    fun selectCount(): QueryBuilder<P, Long, ID>

    /**
     * Creates a new query builder for the custom `selectType`.
     *
     * @param selectType the result type of the query.
     * @return a new query builder for the custom `selectType`.
     * @param <R> the result type of the query.
     */
    fun <R : Any> select(selectType: KClass<R>): QueryBuilder<P, R, ID>

    /**
     * Creates a new query builder for selecting refs to projections of the type managed by this repository.
     *
     *
     * This method is typically used when you only need the primary keys of the projection initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full entity, call [Ref.fetch], which will perform an
     * additional database query on demand.
     *
     * @return a new query builder for selecting refs to projections.
     * @since 1.3
     */
    fun selectRef(): QueryBuilder<P, Ref<P>, ID>

    /**
     * Constructs a SELECT query for refs, filtered by the given predicate.
     *
     * ```kotlin
     * ownerViewRepository.selectRef(OwnerView_.city eq city).resultList
     * ```
     *
     * @since 1.3
     */
    fun selectRef(predicate: PredicateBuilder<P, *, *>): QueryBuilder<P, Ref<P>, ID> = selectRef().where(predicate)

    /**
     * Creates a new query builder for the custom `selectType` and custom `template` for the select clause.
     *
     * @param selectType the result type of the query.
     * @param builder the custom template for the select clause.
     * @return a new query builder for the custom `selectType`.
     * @param <R> the result type of the query.
     */
    fun <R : Any> select(selectType: KClass<R>, builder: TemplateBuilder): QueryBuilder<P, R, ID> = select(selectType, builder.build())

    /**
     * Creates a new query builder for the custom `selectType` and custom `template` for the select clause.
     *
     * @param selectType the result type of the query.
     * @param template the custom template for the select clause.
     * @return a new query builder for the custom `selectType`.
     * @param <R> the result type of the query.
     */
    fun <R : Any> select(selectType: KClass<R>, template: TemplateString): QueryBuilder<P, R, ID>

    /**
     * Creates a new query builder for selecting refs to projections of the type managed by this repository.
     *
     *
     * This method is typically used when you only need the primary keys of the projections initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full projection, call [Ref.fetch], which will perform an
     * additional database query on demand.
     *
     * @param refType the type that is selected as ref.
     * @return a new query builder for selecting refs to projections.
     * @since 1.3
     */
    fun <R : Data> selectRef(refType: KClass<R>): QueryBuilder<P, Ref<R>, ID>

    // Base methods.
    /**
     * Returns the number of projections in the database of the projection type supported by this repository.
     *
     * @return the total number of projections in the database as a long value.
     * @throws st.orm.PersistenceException if the count operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun count(): Long

    /**
     * Checks if any projection of the type managed by this repository exists in the database.
     *
     * @return true if at least one projection exists, false otherwise.
     * @throws st.orm.PersistenceException if there is an underlying database issue during the count operation.
     */
    fun exists(): Boolean

    /**
     * Checks if a projection with the specified primary key exists in the database.
     *
     *
     * This method determines the presence of a projection by checking if the count of projections with the given primary
     * key is greater than zero. It leverages the `selectCount` method, which performs a count operation on the
     * database.
     *
     * @param id the primary key of the projection to check for existence.
     * @return true if a projection with the specified primary key exists, false otherwise.
     * @throws st.orm.PersistenceException if there is an underlying database issue during the count operation.
     */
    fun existsById(id: ID): Boolean

    /**
     * Checks if a projection with the specified primary key exists in the database.
     *
     *
     * This method determines the presence of a projection by checking if the count of projections with the given primary
     * key is greater than zero. It leverages the `selectCount` method, which performs a count operation on the
     * database.
     *
     * @param ref the primary key of the projection to check for existence.
     * @return true if a projection with the specified primary key exists, false otherwise.
     * @throws st.orm.PersistenceException if there is an underlying database issue during the count operation.
     */
    fun existsByRef(ref: Ref<P>): Boolean

    // Singular findBy methods.

    /**
     * Retrieves a projection based on its primary key.
     *
     *
     * This method performs a lookup in the database, returning the corresponding projection if it exists.
     *
     * @param id the primary key of the projection to retrieve.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun findById(id: ID): P?

    /**
     * Retrieves a projection based on its primary key.
     *
     *
     * This method performs a lookup in the database, returning the corresponding projection if it exists.
     *
     * @param ref the ref to match.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun findByRef(ref: Ref<P>): P?

    /**
     * Retrieves a projection based on its primary key.
     *
     *
     * This method performs a lookup in the database, returning the corresponding projection if it exists.
     *
     * @param id the primary key of the projection to retrieve.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws st.orm.NoResultException if no projection is found matching the given primary key, indicating that there's no
     * corresponding data in the database.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun getById(id: ID): P

    /**
     * Retrieves a projection based on its primary key.
     *
     *
     * This method performs a lookup in the database, returning the corresponding projection if it exists.
     *
     * @param ref the ref to match.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws st.orm.NoResultException if no projection is found matching the given primary key, indicating that there's no
     * corresponding data in the database.
     * @throws st.orm.PersistenceException if the retrieval operation fails due to underlying database issues, such as
     * connectivity problems or query execution errors.
     */
    fun getByRef(ref: Ref<P>): P

    // Singular findBy / getBy methods for unique keys.

    /**
     * Retrieves a projection by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the projection matching the given key value, or null if none exists.
     * @since 1.9
     */
    fun <V : Any> findBy(key: Metamodel.Key<P, V>, value: V): P?

    /**
     * Retrieves a projection by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the projection matching the given key value.
     * @throws st.orm.NoResultException if no projection is found matching the given key value.
     * @since 1.9
     */
    fun <V : Any> getBy(key: Metamodel.Key<P, V>, value: V): P

    /**
     * Retrieves a projection by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the projection matching the given ref value, or null if none exists.
     * @since 1.9
     */
    fun <V : Data> findByRef(key: Metamodel.Key<P, V>, value: Ref<V>): P?

    /**
     * Retrieves a projection by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the projection matching the given ref value.
     * @throws st.orm.NoResultException if no projection is found matching the given ref value.
     * @since 1.9
     */
    fun <V : Data> getByRef(key: Metamodel.Key<P, V>, value: Ref<V>): P

    // List based methods.

    /**
     * Returns a list of all projections of the type supported by this repository. Each element in the list represents
     * a projection in the database, encapsulating all relevant data as mapped by the projection model.
     *
     *
     * **Note:** Loading all projections into memory at once can be very memory-intensive if your table
     * is large.
     *
     * @return a stream of all entities of the type supported by this repository.
     * @throws st.orm.PersistenceException if the selection operation fails due to underlying database issues, such as
     * connectivity.
     */
    fun findAll(): List<P>

    /**
     * Retrieves a list of projections based on their primary keys.
     *
     *
     * This method retrieves projections matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     *
     *
     *
     * **Note:** The order of projections in the returned list is not guaranteed to match the order of
     * IDs in the input collection, as the database may not preserve insertion order during retrieval.
     *
     * @param ids the primary keys of the projections to retrieve, represented as an iterable collection.
     * @return a list of projections corresponding to the provided primary keys. Projections are returned without any
     * guarantee of order alignment with the input list. If an ID does not correspond to any projection in the
     * database, no corresponding projection will be included in the returned list.
     * @throws st.orm.PersistenceException if the selection operation fails due to database issues, such as connectivity
     * problems or invalid input parameters.
     */
    fun findAllById(ids: Iterable<ID>): List<P>

    /**
     * Retrieves a list of projections based on their primary keys.
     *
     *
     * This method retrieves projections matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     *
     *
     *
     * **Note:** The order of projections in the returned list is not guaranteed to match the order of
     * IDs in the input collection, as the database may not preserve insertion order during retrieval.
     *
     * @param refs the primary keys of the projections to retrieve, represented as an iterable collection.
     * @return a list of projections corresponding to the provided primary keys. Projections are returned without any
     * guarantee of order alignment with the input list. If an ID does not correspond to any projection in the
     * database, no corresponding projection will be included in the returned list.
     * @throws st.orm.PersistenceException if the selection operation fails due to database issues, such as connectivity
     * problems or invalid input parameters.
     */
    fun findAllByRef(refs: Iterable<Ref<P>>): List<P>

    // Stream based methods.
    //
    // The BatchCallback interface is used to allow the caller to process the results in batches. This approach is
    // preferred over returning a stream of results directly because it allows the repository to control the batch
    // processing and resource management. The repository can decide how to batch the results and ensure that the
    // resources are properly managed. The BatchCallback interface provides a clean and flexible way to process the
    // results in batches, allowing the caller to define the processing logic for each batch.
    //
    // If the repository returned a stream of results directly, that stream would effectively be linked to the input
    // stream. If the caller would fail to fully consume the resulting stream, the input stream would not be fully
    // processed. The BatchCallback approach prevents the caller from accidentally misusing the API.
    //

    /**
     * Counts the number of projections identified by the provided stream of IDs using the default batch size.
     *
     *
     * This method calculates the total number of projections that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.
     *
     * @param ids a stream of IDs for which to count matching projections.
     * @return the total count of projections matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countById(ids: Flow<ID>): Long

    /**
     * Counts the number of projections identified by the provided stream of IDs, with the counting process divided into
     * batches of the specified size.
     *
     *
     * This method performs the counting operation in batches, specified by the `chunkSize` parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.
     *
     * @param ids a stream of IDs for which to count matching projections.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @return the total count of projections matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countById(ids: Flow<ID>, chunkSize: Int): Long

    /**
     * Counts the number of projections identified by the provided stream of refs using the default batch size.
     *
     *
     * This method calculates the total number of projections that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.
     *
     * @param refs a stream of refs for which to count matching projections.
     * @return the total count of projections matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countByRef(refs: Flow<Ref<P>>): Long

    /**
     * Counts the number of projections identified by the provided stream of refs, with the counting process divided into
     * batches of the specified size.
     *
     *
     * This method performs the counting operation in batches, specified by the `chunkSize` parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.
     *
     * @param refs a stream of refs for which to count matching projections.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     * performance but may also increase the load on the database.
     * @return the total count of projections matching the provided IDs.
     * @throws st.orm.PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    suspend fun countByRef(refs: Flow<Ref<P>>, chunkSize: Int): Long

    // Kotlin specific DSL

    /**
     * Returns a list of refs to all projections of type [P] from the repository.
     *
     * @return a list containing refs to all projections.
     */
    fun findAllRef(): List<Ref<P>> = selectRef().resultList

    /**
     * Retrieves an optional entity of type [P] based on a single field and its value.
     * Returns null if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <V> findBy(field: Metamodel<P, V>, value: V): P? = select().where(field, EQUALS, value).optionalResult

    /**
     * Retrieves an optional entity of type [P] based on a single field and its value.
     * Returns null if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <V : Data> findBy(field: Metamodel<P, V>, value: Ref<V>): P? = select().where(field, value).optionalResult

    /**
     * Retrieves entities of type [P] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return list of matching entities.
     */
    fun <V> findAllBy(field: Metamodel<P, V>, value: V): List<P> = select().where(field, EQUALS, value).resultList

    /**
     * Retrieves entities of type [P] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllBy(field: Metamodel<P, V>, value: Ref<V>): List<P> = select().where(field, value).resultList

    /**
     * Retrieves entities of type [P] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return list of matching entities.
     */
    fun <V> findAllBy(field: Metamodel<P, V>, values: Iterable<V>): List<P> = select().where(field, IN, values).resultList

    /**
     * Retrieves entities of type [P] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllByRef(field: Metamodel<P, V>, values: Iterable<Ref<V>>): List<P> = select().whereRef(field, values).resultList

    /**
     * Retrieves exactly one entity of type [P] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V> getBy(field: Metamodel<P, V>, value: V): P = select().where(field, EQUALS, value).singleResult

    /**
     * Retrieves exactly one entity of type [P] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V : Data> getBy(field: Metamodel<P, V>, value: Ref<V>): P = select().where(field, value).singleResult

    /**
     * Retrieves an optional entity of type [P] based on a single field and its value.
     * Returns a ref with a null value if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <T, ID, V> findRefBy(field: Metamodel<P, V>, value: V): Ref<P>? = selectRef().where(field, EQUALS, value).optionalResult

    /**
     * Retrieves an optional entity of type [P] based on a single field and its value.
     * Returns a ref with a null value if no matching entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return an optional entity, or null if none found.
     */
    fun <V : Data> findRefBy(field: Metamodel<P, V>, value: Ref<V>): Ref<P>? = selectRef().where(field, value).optionalResult

    /**
     * Retrieves entities of type [P] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a list of matching entities.
     */
    fun <V> findAllRefBy(field: Metamodel<P, V>, value: V): List<Ref<P>> = selectRef().where(field, EQUALS, value).resultList

    /**
     * Retrieves entities of type [P] matching a single field and a single value.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllRefBy(field: Metamodel<P, V>, value: Ref<V>): List<Ref<P>> = selectRef().where(field, value).resultList

    /**
     * Retrieves entities of type [P] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllRefBy(field: Metamodel<P, V>, values: Iterable<V>): List<Ref<P>> = selectRef().where(field, IN, values).resultList

    /**
     * Retrieves entities of type [P] matching a single field against multiple values.
     * Returns an empty list if no entities are found.
     *
     * @param field metamodel reference of the entity field.
     * @param values Iterable of values to match against.
     * @return a list of matching entities.
     */
    fun <V : Data> findAllRefByRef(field: Metamodel<P, V>, values: Iterable<Ref<V>>): List<Ref<P>> = selectRef().whereRef(field, values).resultList

    /**
     * Retrieves exactly one entity of type [P] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V> getRefBy(field: Metamodel<P, V>, value: V): Ref<P> = selectRef().where(field, EQUALS, value).singleResult

    /**
     * Retrieves exactly one entity of type [P] based on a single field and its value.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun <V : Data> getRefBy(field: Metamodel<P, V>, value: Ref<V>): Ref<P> = selectRef().where(field, value).singleResult

    /**
     * Retrieves entities of type [P] matching the specified predicate.
     *
     * @return a list of matching entities.
     */
    fun findAll(predicate: PredicateBuilder<P, *, *>): List<P> = select().where(predicate).resultList

    /**
     * Retrieves entities of type [P] matching the specified predicate.
     *
     * @return a list of matching entities.
     */
    fun findAllRef(predicate: PredicateBuilder<P, *, *>): List<Ref<P>> = selectRef().where(predicate).resultList

    /**
     * Retrieves an optional entity of type [P] matching the specified predicate.
     * Returns null if no matching entity is found.
     *
     * @return an optional entity, or null if none found.
     */
    fun find(
        predicate: PredicateBuilder<P, *, *>,
    ): P? = select().where(predicate).optionalResult

    /**
     * Retrieves an optional entity of type [P] matching the specified predicate.
     * Returns a ref with a null value if no matching entity is found.
     *
     * @return an optional entity, or null if none found.
     */
    fun findRef(
        predicate: PredicateBuilder<P, *, *>,
    ): Ref<P>? = selectRef().where(predicate).optionalResult

    /**
     * Retrieves a single entity of type [P] matching the specified predicate.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun get(
        predicate: PredicateBuilder<P, *, *>,
    ): P = select().where(predicate).singleResult

    /**
     * Retrieves a single entity of type [P] matching the specified predicate.
     * Throws an exception if no entity or more than one entity is found.
     *
     * @return the matching entity.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    fun getRef(
        predicate: PredicateBuilder<P, *, *>,
    ): Ref<P> = selectRef().where(predicate).singleResult

    /**
     * Counts entities of type [P] matching the specified field and value.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return the count of matching entities.
     */
    fun <V> countBy(
        field: Metamodel<P, V>,
        value: V,
    ): Long = selectCount().where(field, EQUALS, value).singleResult

    /**
     * Counts entities of type [P] matching the specified field and referenced value.
     *
     * @param field metamodel reference of the entity field.
     * @param value the referenced value to match against.
     * @return the count of matching entities.
     */
    fun <V : Data> countBy(
        field: Metamodel<P, V>,
        value: Ref<V>,
    ): Long = selectCount().where(field, value).singleResult

    /**
     * Counts entities of type [P] matching the specified predicate.
     *
     * @param predicate Lambda to build the WHERE clause.
     * @return the count of matching entities.
     */
    fun count(
        predicate: PredicateBuilder<P, *, *>,
    ): Long = selectCount().where(predicate).singleResult

    /**
     * Checks if entities of type [P] matching the specified field and value exists.
     *
     * @param field metamodel reference of the entity field.
     * @param value the value to match against.
     * @return true if any matching entities exist, false otherwise.
     */
    fun <V> existsBy(
        field: Metamodel<P, V>,
        value: V,
    ): Boolean = selectCount().where(field, EQUALS, value).singleResult > 0

    /**
     * Checks if entities of type [P] matching the specified field and referenced value exists.
     *
     * @param field metamodel reference of the entity field.
     * @param value the referenced value to match against.
     * @return true if any matching entities exist, false otherwise.
     */
    fun <V : Data> existsBy(
        field: Metamodel<P, V>,
        value: Ref<V>,
    ): Boolean = selectCount().where(field, value).singleResult > 0

    /**
     * Checks if entities of type [P] matching the specified predicate exists.
     *
     * @param predicate Lambda to build the WHERE clause.
     * @return true if any matching entities exist, false otherwise.
     */
    fun exists(
        predicate: PredicateBuilder<P, *, *>,
    ): Boolean = selectCount().where(predicate).singleResult > 0

    /**
     * Returns a page of projections using offset-based pagination.
     *
     * This method executes two queries: a `SELECT COUNT(*)` to determine the total number of projections, and
     * a query with OFFSET and LIMIT to fetch the content for the requested page.
     *
     * Page numbers are zero-based: pass `0` for the first page.
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of projections per page.
     * @return a page containing the results and pagination metadata.
     * @since 1.10
     */
    fun page(pageNumber: Int, pageSize: Int): Page<P>

    /**
     * Returns a page of projections using offset-based pagination.
     *
     * This method executes two queries: a `SELECT COUNT(*)` to determine the total number of projections, and
     * a query with OFFSET and LIMIT to fetch the content for the requested page.
     *
     * Use [Pageable.ofSize] for the first page, then navigate with
     * [Page.nextPageable] or [Page.previousPageable].
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @since 1.10
     */
    fun page(pageable: Pageable): Page<P>

    /**
     * Returns a page of projection refs using offset-based pagination.
     *
     * Page numbers are zero-based: pass `0` for the first page.
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of refs per page.
     * @return a page containing the ref results and pagination metadata.
     * @since 1.10
     */
    fun pageRef(pageNumber: Int, pageSize: Int): Page<Ref<P>>

    /**
     * Returns a page of projection refs using offset-based pagination.
     *
     * This method executes two queries: a `SELECT COUNT(*)` to determine the total number of projections, and
     * a query with OFFSET and LIMIT to fetch the refs for the requested page.
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the ref results and pagination metadata.
     * @since 1.10
     */
    fun pageRef(pageable: Pageable): Page<Ref<P>>

    /**
     * Executes a scroll request from a [Scrollable] token, typically obtained from
     * [Window.nextScrollable] or [Window.previousScrollable].
     *
     * @param scrollable the scroll request containing cursor state, key, sort, size, and direction.
     * @return a window containing the projection results for the requested scroll position.
     * @since 1.11
     */
    fun scroll(scrollable: Scrollable<P>): Window<P> = Window.of(select().scroll(scrollable))
}
