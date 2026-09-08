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
package st.orm.template

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.stream.consumeAsFlow
import st.orm.Data
import st.orm.NoResultException
import st.orm.NonUniqueResultException
import st.orm.PersistenceException
import st.orm.Ref
import java.util.stream.Stream
import kotlin.reflect.KClass

/**
 * Represents a constructed SQL statement that is ready for execution.
 *
 * `Query` is the result of building a query via the [QueryBuilder] or the [QueryTemplate.query] method. It supports
 * both DQL (Data Query Language) statements such as SELECT, and DML (Data Manipulation Language) statements such as
 * INSERT, UPDATE, and DELETE.
 *
 * For SELECT statements, results can be retrieved as flows, lists, single results, or optional results. The result
 * type can be raw `Array<Any?>` arrays (columns in order) or mapped to a specific data class type.
 *
 * For DML statements (INSERT, UPDATE, DELETE), use [executeUpdate] to execute the statement and obtain the number of
 * affected rows. For batch operations, use [executeBatch].
 *
 * A query can also be converted to a [PreparedQuery] via [prepare], which enables bind variable usage, batch
 * processing, and retrieval of generated keys.
 *
 * @see QueryBuilder
 * @see PreparedQuery
 * @see QueryTemplate
 */
public interface Query {
    /**
     * Prepares the query for execution.
     *
     * Queries are normally constructed in a lazy fashion, unlike prepared queries which are constructed eagerly.
     * Prepared queries allow the use of bind variables and enable reading generated keys after row insertion.
     *
     * **Note:** The prepared query must be closed after usage to prevent resource leaks. As the
     * prepared query is `AutoCloseable`, it is recommended to use it within a `try-with-resources` block.
     *
     * @return the prepared query.
     * @throws st.orm.PersistenceException if the query preparation fails.
     */
    public fun prepare(): PreparedQuery

    /**
     * Returns a new query that allows dangerous operations, such as DELETE and UPDATE without a WHERE clause.
     *
     * @return a new query that allows dangerous operations.
     * @since 1.2
     */
    public fun unsafe(): Query

    public val singleResult: Array<Any>
        /**
         * Execute a SELECT query and returns a single row, where the columns of the row corresponds to the order of values
         * in the list.
         *
         * @return a single row, where the columns of the row corresponds to the order of values the list.
         * @throws st.orm.NoResultException if there is no result.
         * @throws st.orm.NonUniqueResultException if more than one result.
         * @throws st.orm.PersistenceException if the query fails.
         */
        get() = singleResult(this.resultStream)

    public val optionalResult: Array<Any>?
        /**
         * Execute a SELECT query and returns a single row, where the columns of the row corresponds to the order of values
         * in the list.
         *
         * @return a single row, where the columns of the row corresponds to the order of values the list, or an empty
         * optional if there is no result.
         * @throws st.orm.NonUniqueResultException if more than one result.
         * @throws st.orm.PersistenceException if the query fails.
         */
        get() = optionalResult(this.resultStream)

    public val resultCount: Long
        /**
         * Returns the number of results of this query.
         *
         * @return the total number of results of this query as a long value.
         * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
         * connectivity.
         */
        get() {
            this.resultStream.use { stream ->
                return stream.count()
            }
        }

    /**
     * Execute a SELECT query and returns a single row, where the columns of the row are mapped to the constructor
     * arguments of the specified `type`.
     *
     * @param type the type of the result.
     * @return a single row, where the columns of the row corresponds to the order of values the list.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     * @throws st.orm.PersistenceException if the query fails.
     */
    public fun <T : Any> getSingleResult(type: KClass<T>): T = singleResult(getResultStream(type))

    /**
     * Execute a SELECT query and returns a single row, where the columns of the row are mapped to the constructor
     * arguments of the specified `type`.
     *
     * @param type the type of the result.
     * @return a single row, where the columns of the row corresponds to the order of values the list, or an empty
     * optional if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     * @throws st.orm.PersistenceException if the query fails.
     */
    public fun <T : Any> getOptionalResult(type: KClass<T>): T? = optionalResult(getResultStream(type))

    public val resultList: List<Array<Any>>
        /**
         * Execute a SELECT query and return the resulting rows as a list of row instances.
         *
         * Each element in the list represents a row in the result, where the columns of the row corresponds to the
         * order of values in the row array.
         *
         * @return the result list.
         * @throws st.orm.PersistenceException if the query fails.
         */
        get() {
            this.resultStream.use { stream ->
                return stream.toList()
            }
        }

    /**
     * Execute a SELECT query and return the resulting rows as a list of row instances.
     *
     * Each element in the list represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments of the specified `type`.
     *
     * @param type the type of the result.
     * @return the result list.
     * @throws st.orm.PersistenceException if the query fails.
     */
    public fun <T : Any> getResultList(type: KClass<T>): List<T> {
        getResultStream(type).use { stream ->
            return stream.toList()
        }
    }

    /**
     * Execute a SELECT query and return the resulting rows as a list of ref instances.
     *
     * Each element in the list represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments primary key type.
     *
     * @param type the type of the results that are being referenced.
     * @param pkType the primary key type.
     * @return the result list.
     * @throws st.orm.PersistenceException if the query fails.
     * @since 1.3
     */
    public fun <T : Data> getRefList(type: KClass<T>, pkType: KClass<*>): List<Ref<T>> {
        getRefStream(type, pkType).use { stream ->
            return stream.toList()
        }
    }

    /**
     * Execute a SELECT query and return the resulting rows as a stream of row instances.
     *
     * Each element in the stream represents a row in the result, where the columns of the row corresponds to the
     * order of values in the row array.
     *
     * The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.
     *
     * The stream is one open statement on the connection it reads from, and that connection is consume-only until
     * the stream is read to its end or closed: inside a transaction every statement shares the transaction's
     * connection, so a query, a `Ref.fetch()` or a write issued while rows remain unread is refused with a
     * [PersistenceException], on every database. Outside a transaction the stream holds a connection of its own for as long as it is open.
     *
     * **Note:** Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is `AutoCloseable`, it is recommended to use it
     * within a `try-with-resources` block.
     *
     * @return a stream of results.
     * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     */
    public val resultStream: Stream<Array<Any>>

    /**
     * Execute a SELECT query and return the resulting rows as a flow of row instances.
     *
     * Each element in the flow represents a row in the result, where the columns of the row corresponds to the
     * order of values in the row array.
     *
     * The flow is cold: the query executes when the flow is collected, rows are read from the database as they are
     * emitted, and the statement closes when collection completes or is cancelled. While rows remain to be
     * emitted the flow is one open statement on its connection, and that connection is consume-only: inside a
     * transaction a query, a `Ref.fetch()` or a write issued from the collector is refused with a
     * [PersistenceException], on every database.
     *
     * @return a flow of results.
     * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     * @since 1.5
     */
    public val resultFlow: Flow<Array<Any>>
        get() = resultStream.consumeAsFlow()

    /**
     * Execute a SELECT query and return the resulting rows as a stream of row instances.
     *
     * Each element in the stream represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments of the specified `type`.
     *
     * The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.
     *
     * The stream is one open statement on the connection it reads from, and that connection is consume-only until
     * the stream is read to its end or closed: inside a transaction every statement shares the transaction's
     * connection, so a query, a `Ref.fetch()` or a write issued while rows remain unread is refused with a
     * [PersistenceException], on every database. Outside a transaction the stream holds a connection of its own for as long as it is open.
     *
     * **Note:** Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is `AutoCloseable`, it is recommended to use it
     * within a `try-with-resources` block.
     *
     * @return a stream of results.
     * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     */
    public fun <T : Any> getResultStream(type: KClass<T>): Stream<T>

    /**
     * Execute a SELECT query and return the resulting rows as a flow of row instances.
     *
     * Each element in the flow represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments of the specified `type`.
     *
     * The flow is cold: the query executes when the flow is collected, rows are read from the database as they are
     * emitted, and the statement closes when collection completes or is cancelled. While rows remain to be
     * emitted the flow is one open statement on its connection, and that connection is consume-only: inside a
     * transaction a query, a `Ref.fetch()` or a write issued from the collector is refused with a
     * [PersistenceException], on every database.
     *
     * @return a flow of results.
     * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
     * connectivity.
     * @since 1.5
     */
    public fun <T : Any> getResultFlow(type: KClass<T>): Flow<T> = getResultStream(type).consumeAsFlow()

    /**
     * Execute a SELECT query and return the resulting rows as a stream of ref instances.
     *
     * Each element in the stream represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments primary key type.
     *
     * **Note:** Calling this method does trigger the execution of the underlying query, so it should
     * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
     * closed after usage to prevent resource leaks. As the stream is `AutoCloseable`, it is recommended to use it
     * within a `try-with-resources` block.
     *
     * @param type the type of the results that are being referenced.
     * @param pkType the primary key type.
     * @return a stream of ref instances.
     * @throws st.orm.PersistenceException if the query fails.
     * @since 1.3
     */
    public fun <T : Data> getRefStream(type: KClass<T>, pkType: KClass<*>): Stream<Ref<T>>

    /**
     * Execute a SELECT query and return the resulting rows as a flow of ref instances.
     *
     * Each element in the flow represents a row in the result, where the columns of the row are mapped to the
     * constructor arguments primary key type.
     *
     * @param type the type of the results that are being referenced.
     * @param pkType the primary key type.
     * @return a flow of ref instances.
     * @throws st.orm.PersistenceException if the query fails.
     * @since 1.5
     */
    public fun <T : Data> getRefFlow(type: KClass<T>, pkType: KClass<*>): Flow<Ref<T>> = getRefStream(type, pkType).consumeAsFlow()

    /**
     * Returns true if the query is version aware, false otherwise.
     *
     * @return true if the query is version aware, false otherwise.
     */
    public val versionAware: Boolean

    /**
     * Execute a command, such as an INSERT, UPDATE, or DELETE statement.
     *
     * @return the number of rows impacted as result of the statement.
     * @throws st.orm.PersistenceException if the statement fails.
     */
    public fun executeUpdate(): Int

    /**
     * Execute a batch of commands.
     *
     * @throws st.orm.PersistenceException if the batch fails.
     * @return an array of update counts containing one element for each command in the batch. The elements of the
     * array are ordered according to the order in which commands were added to the batch, following
     * `Statement.executeBatch` semantics.
     */
    public fun executeBatch(): IntArray

    /**
     * Returns the single result of the stream.
     *
     * @param stream the stream to get the single result from.
     * @return the single result of the stream.
     * @param <T> the type of the result.
     * @throws st.orm.NoResultException if there is no result.
     * @throws st.orm.NonUniqueResultException if more than one result.
     */
    private fun <T> singleResult(stream: Stream<T>): T {
        stream.use {
            val iterator = stream.iterator()
            if (!iterator.hasNext()) {
                throw NoResultException("Expected single result, but found none.")
            }
            val result = iterator.next()
            if (iterator.hasNext()) {
                throw NonUniqueResultException("Expected single result, but found more than one.")
            }
            if (result == null) {
                throw PersistenceException("Expected single result, but found null. Wrap the field in COALESCE() to provide a non-null default.")
            }
            return result
        }
    }

    /**
     * Returns the single result of the stream, or `null` if there is no result.
     *
     * Iterates the stream explicitly rather than using [Stream.reduce] — the standard reduce internally
     * calls `Optional.of(element)`, which throws a message-less [NullPointerException] when the only
     * element is `null`. The iterator form lets the method detect that case and report it via a typed
     * [PersistenceException] with a clear message.
     *
     * @param stream the stream to get the single result from.
     * @param <T> the type of the result.
     * @return the single result of the stream, or `null` when no row matched.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the single row's value is SQL NULL.
     */
    private fun <T> optionalResult(stream: Stream<T>): T? {
        stream.use {
            val iterator = stream.iterator()
            if (!iterator.hasNext()) {
                return null
            }
            val result = iterator.next()
            if (iterator.hasNext()) {
                throw NonUniqueResultException("Expected single result, but found more than one.")
            }
            if (result == null) {
                throw PersistenceException("Result is null. Wrap the field in COALESCE() to provide a non-null default.")
            }
            return result
        }
    }
}

/**
 * Execute a SELECT query and returns a single row, where the columns of the row are mapped to the constructor
 * arguments of type [T].
 *
 * @param T the type of the result.
 * @return a single row, where the columns of the row corresponds to the order of values the list.
 * @throws st.orm.NoResultException if there is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 * @throws st.orm.PersistenceException if the query fails.
 * @see Query.getSingleResult
 * @since 1.12
 */
public inline fun <reified T : Any> Query.singleResult(): T = getSingleResult(T::class)

/**
 * Execute a SELECT query and returns a single row, where the columns of the row are mapped to the constructor
 * arguments of type [T].
 *
 * @param T the type of the result.
 * @return a single row, where the columns of the row corresponds to the order of values the list, or `null` if there
 * is no result.
 * @throws st.orm.NonUniqueResultException if more than one result.
 * @throws st.orm.PersistenceException if the query fails.
 * @see Query.getOptionalResult
 * @since 1.12
 */
public inline fun <reified T : Any> Query.optionalResult(): T? = getOptionalResult(T::class)

/**
 * Execute a SELECT query and return the resulting rows as a list of row instances.
 *
 * Each element in the list represents a row in the result, where the columns of the row are mapped to the
 * constructor arguments of type [T]:
 * ```kotlin
 * val users = orm.query { """
 *     SELECT ${User::class}
 *     FROM ${User::class}
 *     WHERE ${User_.city.name} = $city"""
 * }.resultList<User>()
 * ```
 *
 * @param T the type of the result.
 * @return the result list.
 * @throws st.orm.PersistenceException if the query fails.
 * @see Query.getResultList
 * @since 1.12
 */
public inline fun <reified T : Any> Query.resultList(): List<T> = getResultList(T::class)

/**
 * Execute a SELECT query and return the resulting rows as a stream of row instances.
 *
 * Each element in the stream represents a row in the result, where the columns of the row are mapped to the
 * constructor arguments of type [T].
 *
 * **Note:** Calling this method does trigger the execution of the underlying query, so it should
 * only be invoked when the query is intended to run. Since the stream holds resources open while in use, it must be
 * closed after usage to prevent resource leaks. As the stream is `AutoCloseable`, it is recommended to use it
 * within a `use` block.
 *
 * @param T the type of the result.
 * @return a stream of results.
 * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
 * connectivity.
 * @see Query.getResultStream
 * @since 1.12
 */
public inline fun <reified T : Any> Query.resultStream(): Stream<T> = getResultStream(T::class)

/**
 * Execute a SELECT query and return the resulting rows as a flow of row instances.
 *
 * Each element in the flow represents a row in the result, where the columns of the row are mapped to the
 * constructor arguments of type [T].
 *
 * @param T the type of the result.
 * @return a flow of results.
 * @throws st.orm.PersistenceException if the query operation fails due to underlying database issues, such as
 * connectivity.
 * @see Query.getResultFlow
 * @since 1.12
 */
public inline fun <reified T : Any> Query.resultFlow(): Flow<T> = getResultFlow(T::class)
