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
package st.orm.core.template;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.PersistenceException;
import st.orm.Ref;

/**
 * Abstraction for DQL (Data Query Language) statements, such as SELECT queries and  DML (Data Manipulation Language)
 * statements, such as INSERT, UPDATE and DELETE statements
 */
public interface Query {

    /**
     * Returns a query that executes without a fetch-size hint, intended for results that are consumed eagerly and
     * fully, such as {@code getResultList()} and {@code getSingleResult()}.
     *
     * <p>On dialects where cursor-based fetching requires a transaction, the fetch-size hint
     * causes queries on auto-commit connections to be wrapped in a transaction, adding round trips. Eagerly consumed
     * results gain nothing from cursor-based fetching, so executing them without the hint avoids that cost. Lazily
     * consumed streams should keep the fetch-size hint to bound memory usage.</p>
     *
     * @return a query without a fetch-size hint; may return {@code this} if no hint is configured.
     * @since 1.13
     */
    default Query withoutFetchSize() {
        return this;
    }

    /**
     * Prepares the query for execution.
     *
     * <p>Queries are normally constructed in a lazy fashion, unlike prepared queries which are constructed eagerly.
     * Prepared queries allow the use of bind variables and enable reading generated keys after row insertion.</p>
     *
     * <p><strong>Note:</strong> The prepared query must be closed after usage to prevent resource leaks. As the
     * prepared query is {@code AutoCloseable}, it is recommended to use it within a {@code try-with-resources} block.
     * </p>
     *
     * @return the prepared query.
     * @throws PersistenceException if the query preparation fails.
     */
    PreparedQuery prepare();

    /**
     * Returns a new query that allows dangerous operations, such as DELETE and UPDATE without a WHERE clause.
     *
     * @return a new query that allows dangerous operations.
     * @since 1.2
     */
    Query unsafe();

    /**
     * Marks this query as repository-managed, indicating that cache invalidation
     * and other side effects are handled explicitly by the repository rather than
     * automatically by the framework. Non-managed DML queries will trigger automatic
     * cache invalidation.
     *
     * @return a new query marked as managed.
     * @since 1.8
     */
    Query managed();

    /**
     * Execute a SELECT query and returns a single row, where the columns of the row corresponds to the order of values
     * in the list.
     *
     * @return a single row, where the columns of the row corresponds to the order of values the list.
     * @throws NoResultException if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the query fails.
     */
    default Object[] getSingleResult() {
        return singleResult(getResultStream());
    }

    /**
     * Execute a SELECT query and returns a single row, where the columns of the row corresponds to the order of values
     * in the list.
     *
     * @return a single row, where the columns of the row corresponds to the order of values the list, or an empty
     * optional if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the query fails.
     */
    default Optional<Object[]> getOptionalResult() {
        return optionalResult(getResultStream());
    }

    /**
     * Returns the number of results of this query.
     *
     * @return the total number of results of this query as a long value.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    default long getResultCount() {
        try (var stream = getResultStream()) {
            return stream.count();
        }
    }

    /**
     * Execute a SELECT query and returns a single row, where the columns of the row are mapped to the constructor
     * arguments of the specified {@code type}.
     *
     * @param type the type of the result.
     * @return a single row, where the columns of the row corresponds to the order of values the list.
     * @throws NoResultException if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the query fails.
     */
    default <T> T getSingleResult(Class<T> type) {
        return singleResult(getResultStream(type));
    }

    /**
     * Execute a SELECT query and returns a single row, where the columns of the row are mapped to the constructor
     * arguments of the specified {@code type}.
     *
     * @param type the type of the result.
     * @return a single row, where the columns of the row corresponds to the order of values the list, or an empty
     * optional if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the query fails.
     */
    default <T> Optional<T> getOptionalResult(Class<T> type) {
        return optionalResult(getResultStream(type));
    }

    /**
     * Execute a SELECT query and return the resulting rows as a list of row instances.
     *
     * <p>Each element in the list represents a row in the result, where the columns of the row corresponds to the
     * order of values in the row array.</p>
     *
     * @return the result list.
     * @throws PersistenceException if the query fails.
     */
    default List<Object[]> getResultList() {
        try (var stream = getResultStream()) {
            return stream.toList();
        }
    }

    /**
     * Execute a SELECT query and return the resulting rows as a list of row instances.
     *
     * <p>Each element in the list represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments of the specified {@code type}.</p>
     *
     * @param type the type of the result.
     * @return the result list.
     * @throws PersistenceException if the query fails.
     */
    default <T> List<T> getResultList(Class<T> type) {
        try (var stream = getResultStream(type)) {
            return stream.toList();
        }
    }

    /**
     * Execute a SELECT query and return the resulting rows as a list of ref instances.
     *
     * <p>Each element in the list represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments primary key type.</p>
     *
     * @param type the type of the results that are being referenced.
     * @param pkType the primary key type.
     * @return the result list.
     * @throws PersistenceException if the query fails.
     * @since 1.3
     */
    default <T extends Data> List<Ref<T>> getRefList(Class<T> type, Class<?> pkType) {
        try (var stream = getRefStream(type, pkType)) {
            return stream.toList();
        }
    }

    /**
     * Execute a SELECT query and return the resulting rows as a stream of row instances.
     *
     * <p>Each element in the stream represents a row in the result, where the columns of the row corresponds to the
     * order of values in the row array.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.</p>
     *
     * <p>The stream is one open statement on the connection it reads from, and that connection is consume-only
     * until the stream is read to its end or closed: inside a transaction every statement shares the transaction's
     * connection, so a query, a {@code Ref.fetch()} or a write issued while rows remain unread is refused with a
     * {@link PersistenceException}, on every database. Outside a transaction the stream holds a connection of its
     * own for as long as it is open.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @return a stream of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    Stream<Object[]> getResultStream();

    /**
     * Execute a SELECT query and return the resulting rows as a stream of row instances.
     *
     * <p>Each element in the stream represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments of the specified {@code type}.</p>
     *
     * <p>The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.</p>
     *
     * <p>The stream is one open statement on the connection it reads from, and that connection is consume-only
     * until the stream is read to its end or closed: inside a transaction every statement shares the transaction's
     * connection, so a query, a {@code Ref.fetch()} or a write issued while rows remain unread is refused with a
     * {@link PersistenceException}, on every database. Outside a transaction the stream holds a connection of its
     * own for as long as it is open.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @return a stream of results.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    <T> Stream<T> getResultStream(Class<T> type);

    /**
     * Execute a SELECT query and return the resulting rows as a stream of ref instances.
     *
     * <p>Each element in the stream represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments primary key type.</p>
     *
     * <p><strong>Note:</strong> Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is {@code AutoCloseable}, it is recommended to use it
     * within a {@code try-with-resources} block.</p>
     *
     * @param type the type of the results that are being referenced.
     * @param pkType the primary key type.
     * @return a stream of ref instances.
     * @throws PersistenceException if the query fails.
     * @since 1.3
     */
    <T extends Data> Stream<Ref<T>> getRefStream(Class<T> type, Class<?> pkType);

    /**
     * Returns true if the query is version aware, false otherwise.
     *
     * @return true if the query is version aware, false otherwise.
     */
    boolean isVersionAware();

    /**
     * Execute a command, such as an INSERT, UPDATE, or DELETE statement.
     *
     * @return the number of rows impacted as result of the statement.
     * @throws PersistenceException if the statement fails.
     */
    int executeUpdate();

    /**
     * Execute a batch of commands.
     *
     * @throws PersistenceException if the batch fails.
     * @return an array of update counts containing one element for each command in the batch. The elements of the
     * array are ordered according to the order in which commands were added to the batch, following
     * {@code Statement.executeBatch} semantics.
     */
    int[] executeBatch();

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
            var iterator = stream.iterator();
            if (!iterator.hasNext()) {
                throw new NoResultException("Expected single result, but found none.");
            }
            T result = iterator.next();
            if (iterator.hasNext()) {
                throw new NonUniqueResultException("Expected single result, but found more than one.");
            }
            if (result == null) {
                throw new PersistenceException("Expected single result, but found null. Wrap the field in COALESCE() to provide a non-null default.");
            }
            return result;
        }
    }

    /**
     * Returns the single result of the stream, or an empty optional if there is no result.
     *
     * @param stream the stream to get the single result from.
     * @param <T> the type of the result.
     * @return the single result of the stream.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the single row's value is null.
     */
    private <T> Optional<T> optionalResult(Stream<T> stream) {
        try (stream) {
            var iterator = stream.iterator();
            if (!iterator.hasNext()) {
                return Optional.empty();
            }
            T result = iterator.next();
            if (iterator.hasNext()) {
                throw new NonUniqueResultException("Expected single result, but found more than one.");
            }
            if (result == null) {
                throw new PersistenceException("Result is null. Wrap the field in COALESCE() to provide a non-null default.");
            }
            return Optional.of(result);
        }
    }
}
