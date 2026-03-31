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
package st.orm.core.repository;

import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.NoResultException;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.Projection;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.core.template.Model;
import st.orm.core.template.QueryBuilder;
import st.orm.core.template.TemplateString;

/**
 * Provides a generic interface with read operations for projections.
 *
 * <p>Projection repositories provide a high-level abstraction for reading projections in the database. They offer a
 * set of methods for reading projections, as well as querying and filtering entities based on specific criteria. The
 * repository interface is designed to work with entity records that implement the {@link Projection} interface,
 * providing a consistent and type-safe way to interact with the database.</p>
 *
 * @since 1.1
 * @see QueryBuilder
 * @param <P> the type of projection managed by this repository.
 * @param <ID> the type of the primary key of the projection, or {@link Void} if the projection has no primary key.
 */
public interface ProjectionRepository<P extends Projection<ID>, ID> extends Repository {

    /**
     * Returns the default chunk size applied by the repository.
     *
     * @return the default chunk size applied by the repository.
     * @since 1.5
     */
    int getDefaultChunkSize();

    /**
     * Returns the projection model associated with this repository.
     *
     * @return the projection model.
     */
    Model<P, ID> model();

    /**
     * Creates a new ref projection instance with the specified primary key.
     *
     * @param id the primary key of the projection.
     * @return a ref projection instance.
     */
    Ref<P> ref(@Nonnull ID id);

    /**
     * Creates a new ref projection instance with the specified projection.
     *
     * @param projection the projection.
     * @return a ref projection instance.
     */
    Ref<P> ref(@Nonnull P projection, @Nonnull ID id);

    // Query builder methods.

    /**
     * Creates a new query builder for the projection type managed by this repository.
     *
     * @return a new query builder for the projection type.
     */
    QueryBuilder<P, P, ID> select();

    /**
     * Creates a new query builder for the projection type managed by this repository.
     *
     * @return a new query builder for the projection type.
     */
    QueryBuilder<P, Long, ID> selectCount();

    /**
     * Creates a new query builder for the custom {@code selectType}.
     *
     * @param selectType the result type of the query.
     * @return a new query builder for the custom {@code selectType}.
     * @param <R> the result type of the query.
     */
    <R> QueryBuilder<P, R, ID> select(@Nonnull Class<R> selectType);

    /**
     * Creates a new query builder for selecting refs to projections of the type managed by this repository.
     *
     * <p>This method is typically used when you only need the primary keys of the projection initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full entity, call {@link Ref#fetch()}, which will perform an
     * additional database query on demand.</p>
     *
     * @return a new query builder for selecting refs to projections.
     * @since 1.3
     */
    QueryBuilder<P, Ref<P>, ID> selectRef();

    /**
     * Creates a new query builder for the custom {@code selectType} and custom {@code template} for the select clause.
     *
     * @param selectType the result type of the query.
     * @param template the custom template for the select clause.
     * @return a new query builder for the custom {@code selectType}.
     * @param <R> the result type of the query.
     */
    <R> QueryBuilder<P, R, ID> select(@Nonnull Class<R> selectType, @Nonnull TemplateString template);

    /**
     * Creates a new query builder for selecting refs to projections of the type managed by this repository.
     *
     * <p>This method is typically used when you only need the primary keys of the projections initially, and you want to
     * defer fetching the full data until it is actually required. The query builder will return ref instances that
     * encapsulate the primary key. To retrieve the full projection, call {@link Ref#fetch()}, which will perform an
     * additional database query on demand.</p>
     *
     * @param refType the type that is selected as ref.
     * @return a new query builder for selecting refs to projections.
     * @since 1.3
     */
    <R extends Data> QueryBuilder<P, Ref<R>, ID> selectRef(@Nonnull Class<R> refType);

    // Base methods.

    /**
     * Returns the number of projections in the database of the projection type supported by this repository.
     *
     * @return the total number of projections in the database as a long value.
     * @throws PersistenceException if the count operation fails due to underlying database issues, such as
     * connectivity.
     */
    long count();

    /**
     * Checks if any projection of the type managed by this repository exists in the database.
     *
     * @return true if at least one projection exists, false otherwise.
     * @throws PersistenceException if there is an underlying database issue during the count operation.
     */
    boolean exists();

    /**
     * Checks if a projection with the specified primary key exists in the database.
     *
     * <p>This method determines the presence of a projection by checking if the count of projections with the given primary
     * key is greater than zero. It leverages the {@code selectCount} method, which performs a count operation on the
     * database.</p>
     *
     * @param id the primary key of the projection to check for existence.
     * @return true if a projection with the specified primary key exists, false otherwise.
     * @throws PersistenceException if there is an underlying database issue during the count operation.
     */
    boolean existsById(@Nonnull ID id);

    /**
     * Checks if a projection with the specified primary key exists in the database.
     *
     * <p>This method determines the presence of a projection by checking if the count of projections with the given primary
     * key is greater than zero. It leverages the {@code selectCount} method, which performs a count operation on the
     * database.</p>
     *
     * @param ref the primary key of the projection to check for existence.
     * @return true if a projection with the specified primary key exists, false otherwise.
     * @throws PersistenceException if there is an underlying database issue during the count operation.
     */
    boolean existsByRef(@Nonnull Ref<P> ref);

    // Singular findBy methods.

    /**
     * Retrieves a projection based on its primary key.
     *
     * <p>This method performs a lookup in the database, returning the corresponding projection if it exists.</p>
     *
     * @param id the primary key of the projection to retrieve.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    Optional<P> findById(@Nonnull ID id);

    /**
     * Retrieves a projection based on its primary key.
     *
     * <p>This method performs a lookup in the database, returning the corresponding projection if it exists.</p>
     *
     * @param ref the ref to match.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    Optional<P> findByRef(@Nonnull Ref<P> ref);

    /**
     * Retrieves a projection based on its primary key.
     *
     * <p>This method performs a lookup in the database, returning the corresponding projection if it exists.</p>
     *
     * @param id the primary key of the projection to retrieve.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws NoResultException if no projection is found matching the given primary key, indicating that there's no
     *                           corresponding data in the database.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    P getById(@Nonnull ID id);

    /**
     * Retrieves a projection based on its primary key.
     *
     * <p>This method performs a lookup in the database, returning the corresponding projection if it exists.</p>
     *
     * @param ref the ref to match.
     * @return the projection associated with the provided primary key. The returned projection encapsulates all relevant data
     * as mapped by the projection model.
     * @throws NoResultException if no projection is found matching the given primary key, indicating that there's no
     *                           corresponding data in the database.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues, such as
     *                              connectivity problems or query execution errors.
     */
    P getByRef(@Nonnull Ref<P> ref);

    // Singular findBy / getBy methods for unique keys.

    /**
     * Retrieves a projection by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the projection matching the given key value, or empty if none exists.
     * @param <V> the type of the key field.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     * @since 1.9
     */
    <V> Optional<P> findBy(@Nonnull Metamodel.Key<P, V> key, @Nonnull V value);

    /**
     * Retrieves a projection by the value of a unique key field.
     *
     * @param key the metamodel key identifying a unique column.
     * @param value the value to match.
     * @return the projection matching the given key value.
     * @param <V> the type of the key field.
     * @throws NoResultException if no projection is found matching the given key value.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     * @since 1.9
     */
    <V> P getBy(@Nonnull Metamodel.Key<P, V> key, @Nonnull V value);

    /**
     * Retrieves a projection by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the projection matching the given ref value, or empty if none exists.
     * @param <V> the type of the referenced entity.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     * @since 1.9
     */
    <V extends Data> Optional<P> findByRef(@Nonnull Metamodel.Key<P, V> key, @Nonnull Ref<V> value);

    /**
     * Retrieves a projection by the ref value of a unique key field that references another entity.
     *
     * @param key the metamodel key identifying a unique foreign key column.
     * @param value the ref value to match.
     * @return the projection matching the given ref value.
     * @param <V> the type of the referenced entity.
     * @throws NoResultException if no projection is found matching the given ref value.
     * @throws PersistenceException if the retrieval operation fails due to underlying database issues.
     * @since 1.9
     */
    <V extends Data> P getByRef(@Nonnull Metamodel.Key<P, V> key, @Nonnull Ref<V> value);

    /**
     * Returns a page of projections using offset-based pagination.
     *
     * <p>This method executes two queries: a {@code SELECT COUNT(*)} to determine the total number of projections, and
     * a query with OFFSET and LIMIT to fetch the content for the requested page.</p>
     *
     * <p>Page numbers are zero-based: pass {@code 0} for the first page.</p>
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of projections per page.
     * @return a page containing the results and pagination metadata.
     * @since 1.10
     */
    default Page<P> page(int pageNumber, int pageSize) {
        return page(Pageable.of(pageNumber, pageSize));
    }

    /**
     * Returns a page of projections using offset-based pagination.
     *
     * <p>This method executes two queries: a {@code SELECT COUNT(*)} to determine the total number of projections, and
     * a query with OFFSET and LIMIT to fetch the content for the requested page.</p>
     *
     * <p>Use {@link Pageable#ofSize(int)} for the first page, then navigate with
     * {@link Page#nextPageable()} or {@link Page#previousPageable()}.</p>
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @since 1.10
     */
    default Page<P> page(@Nonnull Pageable pageable) {
        return select().page(pageable, count());
    }

    /**
     * Returns a page of projection refs using offset-based pagination.
     *
     * <p>Page numbers are zero-based: pass {@code 0} for the first page.</p>
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of refs per page.
     * @return a page containing the ref results and pagination metadata.
     * @since 1.10
     */
    default Page<Ref<P>> pageRef(int pageNumber, int pageSize) {
        return pageRef(Pageable.of(pageNumber, pageSize));
    }

    /**
     * Returns a page of projection refs using offset-based pagination.
     *
     * <p>This method executes two queries: a {@code SELECT COUNT(*)} to determine the total number of projections, and
     * a query with OFFSET and LIMIT to fetch the refs for the requested page.</p>
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the ref results and pagination metadata.
     * @since 1.10
     */
    default Page<Ref<P>> pageRef(@Nonnull Pageable pageable) {
        return selectRef().page(pageable, count());
    }

    /**
     * Scrolls through projections using the given scrollable request.
     *
     * <p>This is a convenience method that delegates to {@code select().scroll(scrollable)}. It is typically used
     * with a {@link Scrollable} obtained from {@link Window#next()} or {@link Window#previous()}.</p>
     *
     * @param scrollable the scroll request describing cursor position and page size.
     * @return a window containing the results.
     * @throws PersistenceException if the query fails due to underlying database issues.
     * @since 1.11
     */
    default Window<P> scroll(@Nonnull Scrollable<P> scrollable) {
        return select().scroll(scrollable);
    }

    // List based methods.

    /**
     * Returns a list of all projections of the type supported by this repository. Each element in the list represents
     * a projection in the database, encapsulating all relevant data as mapped by the projection model.
     *
     * <p><strong>Note:</strong> Loading all projections into memory at once can be very memory-intensive if your table
     * is large.</p>
     *
     * @return a stream of all entities of the type supported by this repository.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    List<P> findAll();

    /**
     * Returns a list of refs to all projections of the type supported by this repository. Each element in the list
     * represents a lightweight reference to a projection in the database, containing only the primary key.
     *
     * <p>This method is useful when you need to retrieve all projection identifiers without loading the full
     * projection data. The complete projection can be fetched on demand by calling {@link Ref#fetch()} on any of
     * the returned refs.</p>
     *
     * <p><strong>Note:</strong> While this method is more memory-efficient than {@link #findAll()} since it only
     * loads primary keys, loading all refs into memory at once can still be memory-intensive for very large tables.</p>
     *
     * @return a list of refs to all projections of the type supported by this repository.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     * @since 1.3
     */
    List<Ref<P>> findAllRef();

    /**
     * Retrieves a list of projections based on their primary keys.
     *
     * <p>This method retrieves projections matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     * </p>
     *
     * <p><strong>Note:</strong> The order of projections in the returned list is not guaranteed to match the order of
     * IDs in the input collection, as the database may not preserve insertion order during retrieval.</p>
     *
     * @param ids the primary keys of the projections to retrieve, represented as an iterable collection.
     * @return a list of projections corresponding to the provided primary keys. Projections are returned without any
     *         guarantee of order alignment with the input list. If an ID does not correspond to any projection in the
     *         database, no corresponding projection will be included in the returned list.
     * @throws PersistenceException if the selection operation fails due to database issues, such as connectivity
     *         problems or invalid input parameters.
     */
    List<P> findAllById(@Nonnull Iterable<ID> ids);

    /**
     * Retrieves a list of projections based on their primary keys.
     *
     * <p>This method retrieves projections matching the provided IDs in batches, consolidating them into a single list.
     * The batch-based retrieval minimizes database overhead, allowing efficient handling of larger collections of IDs.
     * </p>
     *
     * <p><strong>Note:</strong> The order of projections in the returned list is not guaranteed to match the order of
     * IDs in the input collection, as the database may not preserve insertion order during retrieval.</p>
     *
     * @param refs the primary keys of the projections to retrieve, represented as an iterable collection.
     * @return a list of projections corresponding to the provided primary keys. Projections are returned without any
     *         guarantee of order alignment with the input list. If an ID does not correspond to any projection in the
     *         database, no corresponding projection will be included in the returned list.
     * @throws PersistenceException if the selection operation fails due to database issues, such as connectivity
     *         problems or invalid input parameters.
     */
    List<P> findAllByRef(@Nonnull Iterable<Ref<P>> refs);

    // Stream based methods.

    /**
     * Retrieves a stream of projections based on their primary keys.
     *
     * <p>This method executes queries in batches, depending on the number of primary keys in the specified ids stream.
     * This optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve projections.
     * The batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the projections are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of projections.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param ids a stream of projection IDs to retrieve from the repository.
     * @return a stream of projections corresponding to the provided primary keys. The order of projections in the stream is
     *         not guaranteed to match the order of ids in the input stream. If an id does not correspond to any projection
     *         in the database, it will simply be skipped, and no corresponding projection will be included in the returned
     *         stream. If the same projection is requested multiple times, it may be included in the stream multiple times
     *         if it is part of a separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    Stream<P> selectById(@Nonnull Stream<ID> ids);

    /**
     * Retrieves a stream of projections based on their primary keys.
     *
     * <p>This method executes queries in batches, depending on the number of primary keys in the specified ids stream.
     * This optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve projections.
     * The batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the projections are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of projections.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param refs a stream of refs to retrieve from the repository.
     * @return a stream of projections corresponding to the provided primary keys. The order of projections in the stream is
     *         not guaranteed to match the order of ids in the input stream. If an id does not correspond to any projection
     *         in the database, it will simply be skipped, and no corresponding projection will be included in the returned
     *         stream. If the same projection is requested multiple times, it may be included in the stream multiple times
     *         if it is part of a separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    Stream<P> selectByRef(@Nonnull Stream<Ref<P>> refs);

    /**
     * Retrieves a stream of projections based on their primary keys.
     *
     * <p>This method executes queries in batches, with the batch size determined by the provided parameter. This
     * optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve projections. The
     * batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the projections are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of projections.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param ids a stream of projection IDs to retrieve from the repository.
     * @param chunkSize the number of primary keys to include in each batch. This parameter determines the size of the
     *                  batches used to execute the selection operation. A larger batch size can improve performance, especially when
     *                  dealing with large sets of primary keys.
     * @return a stream of projections corresponding to the provided primary keys. The order of projections in the stream is
     * not guaranteed to match the order of ids in the input stream. If an id does not correspond to any projection in the
     * database, it will simply be skipped, and no corresponding projection will be included in the returned stream. If the
     * same projection is requested multiple times, it may be included in the stream multiple times if it is part of a
     * separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    Stream<P> selectById(@Nonnull Stream<ID> ids, int chunkSize);

    /**
     * Retrieves a stream of projections based on their primary keys.
     *
     * <p>This method executes queries in batches, with the batch size determined by the provided parameter. This
     * optimization aims to reduce the overhead of executing multiple queries and efficiently retrieve projections. The
     * batching strategy enhances performance, particularly when dealing with large sets of primary keys.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the projections are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of projections.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param refs a stream of refs to retrieve from the repository.
     * @param chunkSize the number of primary keys to include in each batch. This parameter determines the size of the
     *                  batches used to execute the selection operation. A larger batch size can improve performance, especially when
     *                  dealing with large sets of primary keys.
     * @return a stream of projections corresponding to the provided primary keys. The order of projections in the stream is
     * not guaranteed to match the order of refs in the input stream. If an id does not correspond to any projection in the
     * database, it will simply be skipped, and no corresponding projection will be included in the returned stream. If the
     * same projection is requested multiple times, it may be included in the stream multiple times if it is part of a
     * separate batch.
     * @throws PersistenceException if the selection operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    Stream<P> selectByRef(@Nonnull Stream<Ref<P>> refs, int chunkSize);

    /**
     * Counts the number of projections identified by the provided stream of IDs using the default batch size.
     *
     * <p>This method calculates the total number of projections that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.</p>
     *
     * @param ids a stream of IDs for which to count matching projections.
     * @return the total count of projections matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    long countById(@Nonnull Stream<ID> ids);

    /**
     * Counts the number of projections identified by the provided stream of IDs, with the counting process divided into
     * batches of the specified size.
     *
     * <p>This method performs the counting operation in batches, specified by the {@code chunkSize} parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.</p>
     *
     * @param ids a stream of IDs for which to count matching projections.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @return the total count of projections matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    long countById(@Nonnull Stream<ID> ids, int chunkSize);

    /**
     * Counts the number of projections identified by the provided stream of refs using the default batch size.
     *
     * <p>This method calculates the total number of projections that match the provided primary keys. The counting
     * is performed in batches, which helps optimize performance and manage database load when dealing with
     * large sets of IDs.</p>
     *
     * @param refs a stream of refs for which to count matching projections.
     * @return the total count of projections matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    long countByRef(@Nonnull Stream<Ref<P>> refs);

    /**
     * Counts the number of projections identified by the provided stream of refs, with the counting process divided into
     * batches of the specified size.
     *
     * <p>This method performs the counting operation in batches, specified by the {@code chunkSize} parameter. This
     * batching approach is particularly useful for efficiently handling large volumes of IDs, reducing the overhead on
     * the database and improving performance.</p>
     *
     * @param refs a stream of refs for which to count matching projections.
     * @param chunkSize the size of the batches to use for the counting operation. A larger batch size can improve
     *                  performance but may also increase the load on the database.
     * @return the total count of projections matching the provided IDs.
     * @throws PersistenceException if there is an error during the counting operation, such as connectivity issues.
     */
    long countByRef(@Nonnull Stream<Ref<P>> refs, int chunkSize);
}
