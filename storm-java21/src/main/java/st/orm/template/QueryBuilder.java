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
package st.orm.template;

import static java.lang.StringTemplate.RAW;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;

import jakarta.annotation.Nonnull;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.JoinType;
import st.orm.Metamodel;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.Operator;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Window;
import st.orm.core.template.impl.Elements.ObjectExpression;

/**
 * A fluent builder for constructing type-safe SELECT and DELETE queries using the entity graph and metamodel.
 *
 * <p>The {@code QueryBuilder} provides a composable, chainable API for building SQL queries without writing raw SQL.
 * It supports joins, WHERE clauses with type-safe metamodel paths, GROUP BY, HAVING, ORDER BY, LIMIT/OFFSET,
 * row locking (FOR SHARE/FOR UPDATE), and result retrieval as streams, lists, or single results.</p>
 *
 * <p>Instances are obtained from an {@link st.orm.repository.EntityRepository} or
 * {@link st.orm.repository.ProjectionRepository} via their {@code select()}, {@code selectCount()}, or
 * {@code delete()} methods, or from a {@link QueryTemplate} via {@code selectFrom()} and {@code deleteFrom()}.
 *
 * <h2>Example: Select with type-safe WHERE clause</h2>
 * <pre>{@code
 * List<User> users = userRepository
 *         .select()
 *         .where(User_.address.city.name, EQUALS, "Sunnyvale")
 *         .orderBy(User_.email)
 *         .limit(10)
 *         .getResultList();
 * }</pre>
 *
 * <h2>Example: Delete with WHERE clause</h2>
 * <pre>{@code
 * int deleted = userRepository
 *         .delete()
 *         .where(User_.email, IS_NULL)
 *         .executeUpdate();
 * }</pre>
 *
 * <h2>Example: Join and subquery</h2>
 * <pre>{@code
 * List<User> users = userRepository
 *         .select()
 *         .innerJoin(Order.class).on(User.class)
 *         .where(predicate -> predicate
 *             .where(User_.active, EQUALS, true)
 *             .and(predicate.where(Order_.total, GREATER_THAN, 100)))
 *         .getResultList();
 * }</pre>
 *
 * <h2>Immutability</h2>
 * <p>{@code QueryBuilder} is immutable: every builder method (such as {@code where()}, {@code orderBy()},
 * {@code limit()}, etc.) returns a <em>new</em> instance with the modification applied, leaving the original
 * unchanged. If you call a builder method and ignore the return value, the change is silently lost.</p>
 *
 * <pre>{@code
 * // WRONG - the where clause is lost because the return value is discarded:
 * var builder = userRepository.select();
 * builder.where(User_.active, EQUALS, true);  // returns a new builder, but it's ignored
 * builder.getResultList();                     // executes without the WHERE clause
 *
 * // CORRECT - chain the calls or capture the returned builder:
 * var results = userRepository.select()
 *         .where(User_.active, EQUALS, true)
 *         .getResultList();
 * }</pre>
 *
 * @param <T> the type of the table being queried.
 * @param <R> the type of the result.
 * @param <ID> the type of the primary key.
 * @see st.orm.repository.EntityRepository
 * @see st.orm.repository.ProjectionRepository
 * @see QueryTemplate
 */
public abstract class QueryBuilder<T extends Data, R, ID> {

    /**
     * Returns a typed query builder for the specified primary key type.
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param <X> the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.2
     */
    public abstract <X> QueryBuilder<T, R, X> typed(@Nonnull Class<X> pkType);

    /**
     * Returns a query builder that allows UPDATE and DELETE queries without a WHERE clause.
     *
     * <p>By default, Storm rejects UPDATE and DELETE queries that lack a WHERE clause, throwing a
     * {@link PersistenceException}. Call this method to disable that check when you intentionally want to affect all
     * rows in the table.</p>
     *
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> unsafe();

    /**
     * Marks the current query as a distinct query.
     *
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> distinct();

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> crossJoin(@Nonnull Class<? extends Data> relation);

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> innerJoin(@Nonnull Class<? extends Data> relation);

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> leftJoin(@Nonnull Class<? extends Data> relation);

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> rightJoin(@Nonnull Class<? extends Data> relation);

    /**
     * Adds a join of the specified type to the query.
     *
     * @param type the type of the join (e.g., INNER, LEFT, RIGHT).
     * @param relation the relation to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> join(@Nonnull JoinType type, @Nonnull Class<? extends Data> relation, @Nonnull String alias);

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> crossJoin(@Nonnull StringTemplate template);

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> innerJoin(@Nonnull StringTemplate template, @Nonnull String alias);

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> leftJoin(@Nonnull StringTemplate template, @Nonnull String alias);

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> rightJoin(@Nonnull StringTemplate template, @Nonnull String alias);

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> join(@Nonnull JoinType type, @Nonnull StringTemplate template, @Nonnull String alias);

    /**
     * Adds a join of the specified type to the query using a subquery.
     *
     * @param type the join type.
     * @param subquery the subquery to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> join(@Nonnull JoinType type, @Nonnull QueryBuilder<?, ?, ?> subquery, @Nonnull String alias);

    /**
     * Adds a WHERE clause that matches the specified primary key of the table.
     *
     * @param id the id to match.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(@Nonnull ID id) {
        return where(predicate -> predicate.whereId(id));
    }

    /**
     * Adds a WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the query builder.
     * @since 1.3
     */
    public final QueryBuilder<T, R, ID> where(@Nonnull Ref<T> ref) {
        return where(predicate -> predicate.whereRef(ref));
    }

    /**
     * Adds a WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(@Nonnull T record) {
        return where(predicate -> predicate.where(record));
    }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table.
     *
     * @param it ids to match.
     * @return the query builder.
     * @since 1.2
     */
    public final QueryBuilder<T, R, ID> whereId(@Nonnull Iterable<? extends ID> it) {
        return where(predicate -> predicate.whereId(it));
    }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it refs to match.
     * @return the query builder.
     * @since 1.3
     */
    public final QueryBuilder<T, R, ID> whereRef(@Nonnull Iterable<? extends Ref<T>> it) {
        return where(predicate -> predicate.whereRef(it));
    }

    /**
     * Adds a WHERE clause that matches the specified record. The record can represent any of the related tables in the
     * table graph.
     *
     * @param path the path to the object in the table graph.
     * @param record the records to match.
     * @return the predicate builder.
     */
    public final <V extends Record> QueryBuilder<T, R, ID> where(@Nonnull Metamodel<T, V> path, @Nonnull V record) {
        return where(path, EQUALS, record);
    }

    /**
     * Adds a WHERE clause that matches the specified ref. The ref can represent any of the related tables in the
     * table graph.
     *
     * @param path the path to the object in the table graph.
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public final <V extends Data> QueryBuilder<T, R, ID> where(@Nonnull Metamodel<T, V> path, @Nonnull Ref<V> ref) {
        return where(predicate -> predicate.where(path, ref));
    }

    /**
     * Adds a WHERE clause that matches the specified records. The records can represent any of the related tables in
     * the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it the records to match.
     * @return the predicate builder.
     */
    public final <V extends Data> QueryBuilder<T, R, ID> where(@Nonnull Metamodel<T, V> path, @Nonnull Iterable<V> it) {
        return where(path, IN, it);
    }

    /**
     * Adds a WHERE clause that matches the specified records. The records can represent any of the related tables in
     * the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it the records to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public final <V extends Data> QueryBuilder<T, R, ID> whereRef(@Nonnull Metamodel<T, V> path, @Nonnull Iterable<? extends Ref<V>> it) {
        return where(predicate -> predicate.whereRef(path, it));
    }

    /**
     * Adds a WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(@Nonnull Iterable<? extends T> it) {
        return where(predicate -> predicate.where(it));
    }

    /**
     * Adds a WHERE clause that matches the specified objects at the specified path in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param it the objects to match, which can be primary keys, records representing the table, or fields in the table
     *           graph.
     * @return the query builder.
     * @param <V> the type of the object that the metamodel represents.
     * @since 1.2
     */
    public final <V> QueryBuilder<T, R, ID> where(@Nonnull Metamodel<T, V> path,
                                                  @Nonnull Operator operator,
                                                  @Nonnull Iterable<? extends V> it) {
        return where(predicate -> predicate.where(path, operator, it));
    }

    /**
     * Adds a WHERE clause that matches the specified objects at the specified path in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o the object(s) to match, which can be primary keys, records representing the table, or fields in the
     *          table graph.
     * @return the query builder.
     * @param <V> the type of the object that the metamodel represents.
     * @since 1.2
     */
    @SafeVarargs
    public final <V> QueryBuilder<T, R, ID> where(@Nonnull Metamodel<T, V> path,
                                                  @Nonnull Operator operator,
                                                  @Nonnull V... o) {
        return where(predicate -> predicate.where(path, operator, o));
    }

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param template the expression.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(@Nonnull StringTemplate template) {
        return where(it -> it.where(template));
    }

    /**
     * Adds a WHERE clause to the query using a {@link WhereBuilder}.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> where(@Nonnull Function<WhereBuilder<T, R, ID>, PredicateBuilder<T, ?, ?>> predicate);

    /**
     * Adds a WHERE clause to the query using a {@link WhereBuilder}.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> whereAny(@Nonnull Function<WhereBuilder<T, R, ID>, PredicateBuilder<?, ?, ?>> predicate);

    /**
     * Adds a GROUP BY clause to the query for field at the specified path in the table graph. The metamodel can refer
     * to manually added joins.
     *
     * @param path the path to group by.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final QueryBuilder<T, R, ID> groupBy(@Nonnull Metamodel<T, ?>... path) {
        // We can safely invoke groupByAny as the underlying logic is identical. The main purpose of having these
        // separate methods is to provide (more) type safety when using metamodels that are guaranteed to be present in
        // the table graph.
        return groupByAny(path);
    }

    /**
     * Adds a GROUP BY clause to the query for field at the specified path in the table graph. The metamodel can refer
     * to manually added joins.
     *
     * @param path the path to group by.
     * @return the query builder.
     * @since 1.2
     */
    public final QueryBuilder<T, R, ID> groupByAny(@Nonnull Metamodel<?, ?>... path) {
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for GROUP BY clause.");
        }
        List<StringTemplate> templates = Stream.of(path)
                .flatMap(metamodel -> Stream.of(RAW."\{metamodel}", RAW.", "))
                .toList();
        return groupBy(StringTemplate.combine(templates.subList(0, templates.size() - 1).toArray(new StringTemplate[0])));
    }

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    protected abstract QueryBuilder<T, R, ID> groupBy(@Nonnull StringTemplate template);

    /**
     * Adds a HAVING clause to the query using the specified expression.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o the object(s) to match, which can be primary keys, records representing the table, or fields in the
     *          table graph.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final <V> QueryBuilder<T, R, ID> having(@Nonnull Metamodel<T, V> path,
                                                   @Nonnull Operator operator,
                                                   @Nonnull V... o) {
        return havingAny(path, operator, o);
    }

    /**
     * Adds a HAVING clause to the query using the specified expression. The metamodel can refer to manually added joins.
     *
     * @param path the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o the object(s) to match, which can be primary keys, records representing the table, or fields in the
     *          table graph or manually added joins.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final <V> QueryBuilder<T, R, ID> havingAny(@Nonnull Metamodel<?, V> path,
                                                      @Nonnull Operator operator,
                                                      @Nonnull V... o) {
        return having(RAW."\{new ObjectExpression(path, operator, o)}");
    }

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    protected abstract QueryBuilder<T, R, ID> having(@Nonnull StringTemplate template);

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final QueryBuilder<T, R, ID> orderBy(@Nonnull Metamodel<T, ?>... path) {
        // We can safely invoke orderByAny as the underlying logic is identical. The main purpose of having these
        // separate methods is to provide (more) type safety when using metamodels that are guaranteed to be present in
        // the table graph.
        return orderByAny(path);
    }

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph. The results are
     * sorted in descending order.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    public final QueryBuilder<T, R, ID> orderByDescending(@Nonnull Metamodel<T, ?> path) {
        return orderBy(RAW."\{path} DESC");
    }

    /**
     * Adds an ORDER BY clause to the query for the fields at the specified paths in the table graph. The results
     * are sorted in descending order for each column.
     *
     * @param path the paths to order by.
     * @return the query builder.
     * @since 1.9
     */
    @SafeVarargs
    public final QueryBuilder<T, R, ID> orderByDescending(@Nonnull Metamodel<T, ?>... path) {
        return orderByDescendingAny(path);
    }

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph or manually added
     * joins. The results are sorted in descending order.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.9
     */
    public final QueryBuilder<T, R, ID> orderByDescendingAny(@Nonnull Metamodel<?, ?> path) {
        return orderBy(RAW."\{path} DESC");
    }

    /**
     * Adds an ORDER BY clause to the query for the fields at the specified paths in the table graph or manually
     * added joins. The results are sorted in descending order for each column.
     *
     * @param path the paths to order by.
     * @return the query builder.
     * @since 1.9
     */
    public final QueryBuilder<T, R, ID> orderByDescendingAny(@Nonnull Metamodel<?, ?>... path) {
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for ORDER BY clause.");
        }
        List<StringTemplate> templates = Stream.of(path)
                .flatMap(metamodel -> Stream.of(RAW."\{metamodel}", RAW." DESC", RAW.", "))
                .toList();
        return orderBy(StringTemplate.combine(templates.subList(0, templates.size() - 1).toArray(new StringTemplate[0])));
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. The results are sorted in descending order.
     * Multiple calls to this method append additional columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.9
     */
    public final QueryBuilder<T, R, ID> orderByDescending(@Nonnull StringTemplate template) {
        return orderBy(StringTemplate.combine(template, RAW." DESC"));
    }

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph or manually added
     * joins.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    public final QueryBuilder<T, R, ID> orderByAny(@Nonnull Metamodel<?, ?>... path) {
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for ORDER BY clause.");
        }
        List<StringTemplate> templates = Stream.of(path)
                .flatMap(metamodel -> Stream.of(RAW."\{metamodel}", RAW.", "))
                .toList();
        return orderBy(StringTemplate.combine(templates.subList(0, templates.size() - 1).toArray(new StringTemplate[0])));
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    protected abstract QueryBuilder<T, R, ID> orderBy(@Nonnull StringTemplate template);

    /**
     * Returns {@code true} if any ORDER BY columns have been added to this query builder.
     *
     * @return {@code true} if ORDER BY columns are present, {@code false} otherwise.
     * @since 1.9
     */
    protected abstract boolean hasOrderBy();

    /**
     * Adds a LIMIT clause to the query.
     *
     * @param limit the maximum number of records to return.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> limit(int limit);

    /**
     * Adds an OFFSET clause to the query.
     *
     * @param offset the offset.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> offset(int offset);

    /**
     * Append the query with a string template.
     *
     * @param template the string template to append.
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> append(@Nonnull StringTemplate template);

    //
    // Locking.
    //

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> forShare();

    /**
     * Locks the selected rows for reading.
     *
     * @return the query builder.
     * @throws PersistenceException if the database does not support the specified lock mode, or if the lock mode is
     * not supported for the current query.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> forUpdate();

    /**
     * Locks the selected rows using a custom lock mode.
     *
     * <p><strong>Note:</strong> This method results in non-portable code, as the lock mode is specific to the
     * underlying database.</p>
     *
     * @return the query builder.
     * @throws PersistenceException if the lock mode is not supported for the current query.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> forLock(@Nonnull StringTemplate template);

    //
    // Finalization.
    //

    /**
     * Builds the query based on the current state of the query builder.
     *
     * @return the constructed query.
     */
    public abstract Query build();

    /**
     * Prepares the query for execution.
     *
     * <p>Unlike regular queries, which are constructed lazily, prepared queries are constructed eagerly.
     * Prepared queries allow the use of bind variables and enable reading generated keys after row insertion.</p>
     *
     * <p><strong>Note:</strong> The prepared query must be closed after usage to prevent resource leaks. As the
     * prepared query is {@code AutoCloseable}, it is recommended to use it within a {@code try-with-resources} block.
     * </p>
     *
     * @return the prepared query.
     * @throws PersistenceException if the query preparation fails.
     */
    public final PreparedQuery prepare() {
        return build().prepare();
    }

    /**
     * Executes the query and returns a {@link Page} of results using offset-based pagination.
     *
     * <p>This method executes two queries: one to count the total number of matching results (without offset or
     * limit), and one to fetch the content for the requested page. The caller is responsible for adding ORDER BY
     * clauses to ensure deterministic ordering across pages.</p>
     *
     * <p>Page numbers are zero-based: pass {@code 0} for the first page.</p>
     *
     * @param pageNumber the zero-based page index (must not be negative).
     * @param pageSize the maximum number of results per page (must be positive).
     * @return a page containing the results and pagination metadata.
     * @throws IllegalArgumentException if {@code pageNumber} is negative or {@code pageSize} is not positive.
     * @since 1.10
     */
    public final Page<R> page(int pageNumber, int pageSize) {
        return page(Pageable.of(pageNumber, pageSize));
    }

    /**
     * Executes the query and returns a {@link Page} of results using offset-based pagination.
     *
     * <p>This method executes two queries: one to count the total number of matching results (without offset or
     * limit), and one to fetch the content for the requested page. Sort orders can be specified either through the
     * pageable or through explicit {@code orderBy} calls on the query builder, but not both. If both are present,
     * a {@link PersistenceException} is thrown.</p>
     *
     * <p>Use {@link Pageable#ofSize(int)} for the first page, then navigate with
     * {@link Page#nextPageable()} or {@link Page#previousPageable()}.</p>
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @throws PersistenceException if the pageable has sort orders and the query builder has explicit orderBy calls.
     * @since 1.10
     */
    public final Page<R> page(@Nonnull Pageable pageable) {
        return page(pageable, getResultCount());
    }

    /**
     * Executes the query and returns a {@link Page} of results using offset-based pagination with a pre-computed
     * total count.
     *
     * <p>This method applies the sort orders from the pageable, then fetches the content for the requested page
     * using the provided total count instead of executing a separate count query. This is useful when the total
     * count is already known (for example, cached from a previous request or obtained from an external source),
     * avoiding a redundant {@code COUNT} query.</p>
     *
     * <p>Sort orders can be specified either through the pageable or through explicit {@code orderBy} calls on
     * the query builder, but not both. If both are present, a {@link PersistenceException} is thrown.</p>
     *
     * @param pageable the pagination request specifying page number and page size.
     * @param totalCount the pre-computed total number of matching results.
     * @return a page containing the results and pagination metadata.
     * @throws PersistenceException if the pageable has sort orders and the query builder has explicit orderBy calls.
     * @since 1.10
     */
    public final Page<R> page(@Nonnull Pageable pageable, long totalCount) {
        // Forbid combining explicit orderBy with Pageable sort orders for consistency with scroll, which also
        // manages ORDER BY internally and forbids explicit orderBy calls.
        if (hasOrderBy() && !pageable.orders().isEmpty()) {
            throw new PersistenceException("page with Pageable sort orders cannot be combined with explicit orderBy calls.");
        }
        QueryBuilder<T, R, ID> sorted = this;
        for (var order : pageable.orders()) {
            sorted = order.descending()
                    ? sorted.orderByDescendingAny(order.field())
                    : sorted.orderByAny(order.field());
        }
        List<R> content = sorted.offset((int) pageable.offset()).limit(pageable.pageSize()).getResultList();
        return new Page<>(content, totalCount, pageable);
    }

    /**
     * Executes the query and returns a {@link Window} of results.
     *
     * <p>This method fetches {@code size + 1} rows to determine whether more results are available, then returns at
     * most {@code size} results along with a {@code hasNext} flag. The caller is responsible for managing any WHERE
     * and ORDER BY clauses externally.</p>
     *
     * <p>The returned window does not carry navigation tokens ({@code next()} and
     * {@code previous()} return {@code null}).</p>
     *
     * @param size the maximum number of results to include in the window (must be positive).
     * @return a window containing the results and a flag indicating whether more results exist.
     * @throws IllegalArgumentException if {@code size} is not positive.
     * @since 1.11
     */
    public abstract Window<R> scroll(int size);

    /**
     * Executes a scroll request from a {@link Scrollable} token, typically obtained from
     * {@link Window#next()} or {@link Window#previous()}.
     *
     * @param scrollable the scroll request containing cursor state, key, sort, size, and direction.
     * @return a window containing the results and navigation tokens.
     * @since 1.11
     */
    public abstract Window<R> scroll(@Nonnull Scrollable<T> scrollable);

    //
    // Execution methods.
    //

    /**
     * Executes the query and returns a stream of results.
     *
     * <p>The resulting stream is lazily loaded, meaning that the records are only retrieved from the database as they
     * are consumed by the stream. This approach is efficient and minimizes the memory footprint, especially when
     * dealing with large volumes of records.</p>
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
    public abstract Stream<R> getResultStream();

    /**
     * Returns the number of results of this query.
     *
     * @return the total number of results of this query as a long value.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public final long getResultCount() {
        try (var stream = getResultStream()) {
            return stream.count();
        }
    }

    /**
     * Executes the query and returns a list of results.
     *
     * @return the list of results.
     * @throws PersistenceException if the query fails.
     */
    public final List<R> getResultList() {
        try (var stream = getResultStream()) {
            return stream.toList();
        }
    }

    /**
     * Executes the query and returns a single result.
     *
     * @return the single result.
     * @throws NoResultException if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the query fails.
     */
    public final R getSingleResult() {
        try (var stream = getResultStream()) {
            return stream
                    .reduce((_, _) -> {
                        throw new NonUniqueResultException("Expected single result, but found more than one.");
                    }).orElseThrow(() -> new NoResultException("Expected single result, but found none."));
        }
    }

    /**
     * Executes the query and returns an optional result.
     *
     * @return the optional result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the query fails.
     */
    public final Optional<R> getOptionalResult() {
        try (var stream = getResultStream()) {
            return stream.reduce((_, _) -> {
                throw new NonUniqueResultException("Expected single result, but found more than one.");
            });
        }
    }

    /**
     * Execute a DELETE statement.
     *
     * @return the number of rows impacted as result of the statement.
     * @throws PersistenceException if the statement fails.
     */
    public final int executeUpdate() {
        return build().executeUpdate();
    }
}
