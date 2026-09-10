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
import static st.orm.ResolveScope.CASCADE;
import static st.orm.core.template.impl.Elements.Clause.GROUP_BY;
import static st.orm.core.template.impl.Elements.Clause.ORDER_BY_ASCENDING;
import static st.orm.core.template.impl.Elements.Clause.ORDER_BY_DESCENDING;

import java.util.List;
import java.util.Optional;
import java.util.SequencedMap;
import java.util.function.Function;
import java.util.stream.Stream;
import st.orm.Data;
import st.orm.JoinType;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.NoResultException;
import st.orm.NonUniqueResultException;
import st.orm.Operator;
import st.orm.Page;
import st.orm.Pageable;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.Scrollable;
import st.orm.Slice;
import st.orm.TypedMetamodel;
import st.orm.Window;
import st.orm.core.template.impl.Elements.Columns;
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
 *             .where(User_.email, LIKE, "%@example.com")
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
 * builder.where(User_.email, LIKE, "%@example.com");  // returns a new builder, but it's ignored
 * builder.getResultList();                     // executes without the WHERE clause
 *
 * // CORRECT - chain the calls or capture the returned builder:
 * var results = userRepository.select()
 *         .where(User_.email, LIKE, "%@example.com")
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
     * Returns a query builder whose primary key type is {@code pkType}, so the operations that take an id can be
     * used.
     *
     * <p>A builder that did not come from a typed entity lookup carries no primary key type. {@code selectFrom(...)}
     * names the table but not its key, so the id is a wildcard and {@link WhereBuilder#whereId(Object)} has nothing to
     * match against. Stating the key type resolves it:</p>
     *
     * <pre>{@code
     * List<City> cities = orm.selectFrom(City.class)
     *         .typedId(Integer.class)
     *         .where(predicate -> predicate.whereId(List.of(1, 3, 5)))
     *         .getResultList();
     * }</pre>
     *
     * <p>The type is checked against the model, so a type that is not the table's key fails here rather than when the
     * query runs. This types the key, while {@link #narrow(Class)} types the root; the two are independent, and
     * neither is undone by a join.</p>
     *
     * @param pkType the primary key type.
     * @return the typed query builder.
     * @param <X> the type of the primary key.
     * @throws PersistenceException if the pk type is not valid.
     * @since 1.14
     */
    public abstract <X> QueryBuilder<T, R, X> typedId(Class<X> pkType);

    /**
     * Returns a query builder rooted at the specified type, narrowing a builder whose root was relaxed by a join.
     *
     * <p>A join relaxes the root so that clauses may name any entity in the query. This narrows it again, which
     * re-enables the operations that are defined relative to the root, such as {@link #fetch(Navigable[])} and
     * {@link #getResultGroupedBy(TypedMetamodel)}.</p>
     *
     * @param rootType the type this query is rooted at.
     * @return the query builder, rooted at {@code rootType}.
     * @param <X> the root table type.
     * @throws PersistenceException if {@code rootType} is not the type this query selects from.
     * @since 1.14
     */
    public abstract <X extends Data> QueryBuilder<X, R, ID> narrow(Class<X> rootType);

    /**
     * Widens the query as a join does, without joining: from here on, every clause accepts paths from any entity in
     * the query. Use it to reference an entity of the query's graph in short form on a query that joins nothing;
     * resolution happens when the query is built, and a table the query does not contain, or contains more than once,
     * fails with an error naming the candidates.
     *
     * <p>Widening is always safe, so unlike {@link #narrow(Class)} there is nothing to verify.</p>
     *
     * @return the query builder, accepting paths from any entity in the query.
     * @since 1.14
     */
    public abstract QueryBuilder<Data, R, ID> widen();

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
     * Resolves the references at the specified paths as part of this query.
     *
     * <p>A {@link Ref} foreign key is selected as its foreign key column and resolved on demand, which costs a query
     * per reference. A path named here is selected as the referenced table's columns instead, joined into the same
     * statement, so the reference comes back already loaded: {@link Ref#fetch()} returns the record without querying
     * and {@link Ref#isLoaded()} reports {@code true}.</p>
     *
     * <pre>{@code
     * List<User> users = orm.entity(User.class)
     *     .select()
     *     .fetch(User_.city, User_.city.country)
     *     .getResultList();
     *
     * City city = users.getFirst().city().fetch();   // already loaded, no query
     * }</pre>
     *
     * <p>The record type is unchanged: the field stays a {@code Ref}, so the same record can come from a query that
     * resolves the reference and from one that does not. Reference identity and equality are unaffected, and
     * {@link Ref#unload()} returns to a reference that carries the key alone.</p>
     *
     * <p>The plan is prefix-closed: naming {@code User_.city.country} resolves {@code User_.city} as well, since the
     * city record is what holds the country reference. A reference is always a to-one foreign key, so resolving one
     * widens the row without multiplying it, and a cycle stays bounded by the depth the path names.</p>
     *
     * <p>A nullable reference is joined with an outer join, so a row whose foreign key is null yields a null
     * reference, matching a nullable entity foreign key. A path that crosses no reference is rejected: the target is
     * already part of the entity graph and there is nothing to resolve.</p>
     *
     * @param path the paths of the references to resolve.
     * @return the query builder.
     * @throws PersistenceException if no path is provided, if a path crosses no reference, or if this query does not
     * select a record that can hold one.
     * @since 1.13
     */
    @SafeVarargs
    public final QueryBuilder<T, R, ID> fetch(Navigable<T, ? extends Data>... path) {
        return fetch(List.of(path));
    }

    /**
     * Resolves the references at the specified paths as part of this query.
     *
     * <p>A path a generated metamodel cannot express, a cycle deeper than the two hops it constructs in particular, is
     * named with {@link Metamodel#of(Class, String)}.</p>
     *
     * @param paths the paths of the references to resolve.
     * @return the query builder.
     * @throws PersistenceException if no path is provided, if a path crosses no reference, or if this query does not
     * select a record that can hold one.
     * @see #fetch(Navigable[])
     * @since 1.13
     */
    public abstract QueryBuilder<T, R, ID> fetch(List<? extends Navigable<T, ? extends Data>> paths);

    /**
     * Adds a cross join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract QueryBuilder<Data, R, ID> crossJoin(Class<? extends Data> relation);

    /**
     * Adds an inner join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> innerJoin(Class<? extends Data> relation);

    /**
     * Adds a left join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> leftJoin(Class<? extends Data> relation);

    /**
     * Adds a right join to the query.
     *
     * @param relation the relation to join.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> rightJoin(Class<? extends Data> relation);

    /**
     * Adds a join of the specified type to the query.
     *
     * @param type the type of the join (e.g., INNER, LEFT, RIGHT).
     * @param relation the relation to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract TypedJoinBuilder<T, R, ID> join(JoinType type, Class<? extends Data> relation, String alias);

    /**
     * Adds a cross join to the query.
     *
     * @param template the condition to join.
     * @return the query builder.
     */
    public abstract QueryBuilder<Data, R, ID> crossJoin(StringTemplate template);

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> innerJoin(StringTemplate template, String alias);

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> leftJoin(StringTemplate template, String alias);

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> rightJoin(StringTemplate template, String alias);

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> join(JoinType type, StringTemplate template, String alias);

    /**
     * Adds a join of the specified type to the query using a subquery.
     *
     * @param type the join type.
     * @param subquery the subquery to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> join(JoinType type, QueryBuilder<?, ?, ?> subquery, String alias);

    /**
     * Adds a WHERE clause that matches the specified primary key of the table.
     *
     * @param id the id to match.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(ID id) {
        return where(predicate -> predicate.whereId(id));
    }

    /**
     * Adds a WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the query builder.
     * @since 1.3
     */
    public final QueryBuilder<T, R, ID> where(Ref<T> ref) {
        return where(predicate -> predicate.whereRef(ref));
    }

    /**
     * Adds a WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(T record) {
        return where(predicate -> predicate.where(record));
    }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table.
     *
     * @param it ids to match.
     * @return the query builder.
     * @since 1.2
     */
    public final QueryBuilder<T, R, ID> whereId(Iterable<? extends ID> it) {
        return where(predicate -> predicate.whereId(it));
    }

    /**
     * Adds a WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it refs to match.
     * @return the query builder.
     * @since 1.3
     */
    public final QueryBuilder<T, R, ID> whereRef(Iterable<? extends Ref<T>> it) {
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
    public final <V extends Data> QueryBuilder<T, R, ID> where(Navigable<? extends T, V> path, V record) {
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
    public final <V extends Data> QueryBuilder<T, R, ID> where(Navigable<? extends T, V> path, Ref<V> ref) {
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
    public final <V extends Data> QueryBuilder<T, R, ID> where(Navigable<? extends T, V> path, Iterable<V> it) {
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
    public final <V extends Data> QueryBuilder<T, R, ID> whereRef(Navigable<? extends T, V> path, Iterable<? extends Ref<V>> it) {
        return where(predicate -> predicate.whereRef(path, it));
    }

    /**
     * Adds a WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(Iterable<? extends T> it) {
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
    public final <V> QueryBuilder<T, R, ID> where(Navigable<? extends T, V> path,
                                                  Operator operator,
                                                  Iterable<? extends V> it) {
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
    public final <V> QueryBuilder<T, R, ID> where(Navigable<? extends T, V> path,
                                                  Operator operator,
                                                  V... o) {
        return where(predicate -> predicate.where(path, operator, o));
    }

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param template the expression.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(StringTemplate template) {
        return where(it -> it.where(template));
    }

    /**
     * Adds a WHERE clause to the query using a {@link WhereBuilder}.
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     */
    public abstract QueryBuilder<T, R, ID> where(Function<WhereBuilder<T, R, ID>, PredicateBuilder<T, ?, ?>> predicate);

    /**
     * Adds a WHERE clause that keeps the rows for which the specified subquery returns at least one row.
     *
     * <p>Use {@link #where(Function)} with {@link WhereBuilder#exists} to combine the condition with others in a
     * single clause; consecutive {@code where} calls are AND-combined.</p>
     *
     * @param subquery the subquery to test for existence.
     * @return the query builder.
     * @since 1.13
     */
    public final QueryBuilder<T, R, ID> whereExists(QueryBuilder<?, ?, ?> subquery) {
        return where(predicate -> predicate.exists(subquery));
    }

    /**
     * Adds a WHERE clause that keeps the rows for which the specified subquery returns no rows.
     *
     * @param subquery the subquery to test for absence.
     * @return the query builder.
     * @since 1.13
     */
    public final QueryBuilder<T, R, ID> whereNotExists(QueryBuilder<?, ?, ?> subquery) {
        return where(predicate -> predicate.notExists(subquery));
    }

    /**
     * Adds a GROUP BY clause to the query for field at the specified path in the table graph. The metamodel can refer
     * to manually added joins.
     *
     * <p>A path resolves to the same columns a predicate on that path would use: a foreign key expands to its foreign
     * key column(s) on the referencing table, without joining the referenced table, and an inline record expands to
     * its component columns. A single-column path contributes exactly one column.</p>
     *
     * @param path the path to group by.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final QueryBuilder<T, R, ID> groupBy(Navigable<? extends T, ?>... path) {
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for GROUP BY clause.");
        }
        List<StringTemplate> templates = Stream.of(path)
                .<StringTemplate>flatMap(navigable -> {
                    Columns columns = new Columns(List.of(navigable.asMetamodel()), CASCADE, GROUP_BY);
                    return Stream.of(RAW."\{columns}", RAW.", ");
                })
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
    public abstract QueryBuilder<T, R, ID> groupBy(StringTemplate template);

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
    public final <V> QueryBuilder<T, R, ID> having(Navigable<? extends T, V> path,
                                                   Operator operator,
                                                   V... o) {
        return having(RAW."\{new ObjectExpression(path.asMetamodel(), operator, o)}");
    }

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> having(StringTemplate template);

    /**
     * Adds a HAVING clause that keeps the groups for which the specified subquery returns at least one row.
     *
     * @param subquery the subquery to test for existence.
     * @return the query builder.
     * @since 1.13
     */
    public abstract QueryBuilder<T, R, ID> havingExists(QueryBuilder<?, ?, ?> subquery);

    /**
     * Adds a HAVING clause that keeps the groups for which the specified subquery returns no rows.
     *
     * @param subquery the subquery to test for absence.
     * @return the query builder.
     * @since 1.13
     */
    public abstract QueryBuilder<T, R, ID> havingNotExists(QueryBuilder<?, ?, ?> subquery);

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final QueryBuilder<T, R, ID> orderBy(Navigable<? extends T, ?>... path) {
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for ORDER BY clause.");
        }
        List<StringTemplate> templates = Stream.of(path)
                .<StringTemplate>flatMap(navigable -> {
                    Columns columns = new Columns(List.of(navigable.asMetamodel()), CASCADE, ORDER_BY_ASCENDING);
                    return Stream.of(RAW."\{columns}", RAW.", ");
                })
                .toList();
        return orderBy(StringTemplate.combine(templates.subList(0, templates.size() - 1).toArray(new StringTemplate[0])));
    }

    /**
     * Adds an ORDER BY clause to the query for the field at the specified path in the table graph. The results are
     * sorted in descending order.
     *
     * @param path the path to order by.
     * @return the query builder.
     * @since 1.2
     */
    public final QueryBuilder<T, R, ID> orderByDescending(Navigable<? extends T, ?> path) {
        return orderBy(RAW."\{new Columns(List.of(path.asMetamodel()), CASCADE, ORDER_BY_DESCENDING)}");
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
    public final QueryBuilder<T, R, ID> orderByDescending(Navigable<? extends T, ?>... path) {
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for ORDER BY clause.");
        }
        List<StringTemplate> templates = Stream.of(path)
                .<StringTemplate>flatMap(navigable -> {
                    Columns columns = new Columns(List.of(navigable.asMetamodel()), CASCADE, ORDER_BY_DESCENDING);
                    return Stream.of(RAW."\{columns}", RAW.", ");
                })
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
    public final QueryBuilder<T, R, ID> orderByDescending(StringTemplate template) {
        return orderBy(StringTemplate.combine(template, RAW." DESC"));
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> orderBy(StringTemplate template);

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
    public abstract QueryBuilder<T, R, ID> forLock(StringTemplate template);

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
     * <p>This method executes the query for the requested page and, when the total cannot be derived from the
     * fetched page, a count query (without offset or limit). A page that is not full determines the total directly,
     * so the count query only runs for a full page, or for an empty page beyond the first. The caller is responsible
     * for adding ORDER BY clauses to ensure deterministic ordering across pages.</p>
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
     * <p>This method executes the query for the requested page and, when the total cannot be derived from the
     * fetched page, a count query (without offset or limit). A page that is not full determines the total directly,
     * so the count query only runs for a full page, or for an empty page beyond the first. Sort orders can be
     * specified either through the pageable or through explicit {@code orderBy} calls on the query builder, but not
     * both. If both are present, a {@link PersistenceException} is thrown.</p>
     *
     * <p>Use {@link Pageable#ofSize(int)} for the first page, then navigate with
     * {@link Page#next()} or {@link Page#previous()}.</p>
     *
     * @param pageable the pagination request specifying page number and page size.
     * @return a page containing the results and pagination metadata.
     * @throws PersistenceException if the pageable has sort orders and the query builder has explicit orderBy calls.
     * @since 1.10
     */
    public final Page<R> page(Pageable pageable) {
        List<R> content = pageContent(pageable, pageable.pageSize());
        long totalCount;
        if (content.size() < pageable.pageSize() && (pageable.offset() == 0 || !content.isEmpty())) {
            // A page that is not full is the last page, so the total follows from the page itself. An empty page
            // beyond the first proves nothing: the offset may lie anywhere past the end.
            totalCount = pageable.offset() + content.size();
        } else {
            totalCount = getResultCount();
        }
        return new Page<>(content, totalCount, pageable);
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
    public final Page<R> page(Pageable pageable, long totalCount) {
        return new Page<>(pageContent(pageable, pageable.pageSize()), totalCount, pageable);
    }

    /**
     * Executes the query and returns a {@link Slice} of results using offset-based pagination without a count.
     *
     * <p>The slice is read the way a page is, with OFFSET and LIMIT from the request, but one row beyond the page
     * size is fetched instead of running a count query: {@link Slice#hasNext()} says whether that row existed, and
     * {@link Slice#hasPrevious()} follows from the page number. Use it for a "load more" that needs no total, and
     * for a query without a unique key, where {@link #scroll(Scrollable)} is not possible.</p>
     *
     * <p>Page numbers are zero-based: pass {@code 0} for the first slice.</p>
     *
     * @param pageNumber the zero-based page index.
     * @param pageSize the maximum number of results per slice.
     * @return the slice.
     * @since 1.14
     */
    public final Slice<R> slice(int pageNumber, int pageSize) {
        return slice(Pageable.of(pageNumber, pageSize));
    }

    /**
     * Executes the query and returns a {@link Slice} of results using offset-based pagination without a count.
     *
     * <p>The slice is read the way a page is, with OFFSET and LIMIT from the request, but one row beyond the page
     * size is fetched instead of running a count query: {@link Slice#hasNext()} says whether that row existed, and
     * {@link Slice#hasPrevious()} follows from the page number. Use it for a "load more" that needs no total, and
     * for a query without a unique key, where {@link #scroll(Scrollable)} is not possible.</p>
     *
     * <p>Use {@link Pageable#ofSize(int)} for the first slice, then navigate with {@link Pageable#next()} or
     * {@link Pageable#previous()}.</p>
     *
     * @param pageable the request specifying page number, page size and sort orders.
     * @return the slice.
     * @throws PersistenceException if the request carries sort orders and the query an explicit ORDER BY.
     * @since 1.14
     */
    public final Slice<R> slice(Pageable pageable) {
        List<R> content = pageContent(pageable, pageable.pageSize() + 1);
        boolean hasNext = content.size() > pageable.pageSize();
        return Slice.of(hasNext ? content.subList(0, pageable.pageSize()) : content, hasNext, pageable.pageNumber() > 0);
    }

    /**
     * Fetches the content for the requested page, applying the pageable's sort orders and offset, and the limit.
     */
    private List<R> pageContent(Pageable pageable, int limit) {
        // Forbid combining explicit orderBy with Pageable sort orders for consistency with scroll, which also
        // manages ORDER BY internally and forbids explicit orderBy calls.
        if (hasOrderBy() && !pageable.orders().isEmpty()) {
            throw new PersistenceException("Pageable sort orders cannot be combined with explicit orderBy calls.");
        }
        QueryBuilder<T, R, ID> sorted = this;
        for (var order : pageable.orders()) {
            // The Pageable's sort field may be rooted anywhere in the query, so the column is named directly.
            sorted = sorted.orderBy(RAW."\{new Columns(List.of(order.field()), CASCADE, order.descending() ? ORDER_BY_DESCENDING : ORDER_BY_ASCENDING)}");
        }
        return sorted.offset((int) pageable.offset()).limit(limit).getResultList();
    }

    /**
     * Executes a scroll request and returns a {@link Window}: the results in the request's sort order, the flags
     * that say whether rows exist after and before the window, and the tokens that continue from it.
     *
     * <p>The request owns the ordering, so the query must not carry an ORDER BY of its own. The sort fields and the
     * key are read from each row alongside the result, so the tokens are there for every result type: the entity,
     * a projection, a ref or a custom select type. A window reached through {@link Window#previous()} comes back in
     * the same sort order as every other window.</p>
     *
     * @param scrollable the scroll request: ordering, size and position.
     * @return a window containing the results and navigation tokens.
     * @throws PersistenceException if the query carries an explicit ORDER BY, the key is compound or nullable, or a
     *                              sort field is nullable.
     * @since 1.11
     */
    public abstract Window<R> scroll(Scrollable<T> scrollable);

    /**
     * Executes the query in windows of {@code size} rows ordered by the primary key, each window one closed
     * statement.
     *
     * <p>Where {@link #getResultStream()} holds one open statement on the connection for as long as the stream is
     * consumed, a window is fetched by a statement that has returned and closed before the window is handed to
     * the caller. Between windows the connection is free, so the loop over a window may query, fetch references
     * and write, inside a transaction or with a transaction per window. The stream carries no database resource
     * and needs no closing. Each window is its own statement: it runs the query's WHERE clause again from the
     * cursor position, and under READ COMMITTED it sees rows committed since the previous window.</p>
     *
     * <p>Windows are keyset windows over the primary key, so the query must not carry an ORDER BY of its own, and
     * the result type must be the entity type the key belongs to: {@code selectRef()} and custom select types
     * are refused. A compound primary key is refused too; pass {@link #windows(Scrollable)} a single-column unique
     * key instead. The rows come in ascending key order; pass {@code Scrollable.of(key, size).descending()} to
     * {@link #windows(Scrollable)} for descending order.</p>
     *
     * <pre>{@code
     * users.select().where(User_.city, EQUALS, city).windows(1000).forEach(window ->
     *     users.update(window.content().stream()
     *         .map(user -> new User(user.id(), user.email().toLowerCase(), user.birthDate(), user.street(), user.postalCode(), user.city()))
     *         .toList()));
     * }</pre>
     *
     * @param size the maximum number of rows per window (must be positive).
     * @return a stream of windows; each window's {@link Window#next()} resumes the iteration after that window.
     * @throws IllegalArgumentException if {@code size} is not positive.
     * @throws PersistenceException if the query has no single-column primary key, carries an explicit ORDER BY, or
     *                              does not select the entity type.
     * @since 1.14
     */
    public abstract Stream<Window<R>> windows(int size);

    /**
     * Executes the query in windows described by the given scroll request, each window one closed statement.
     *
     * <p>This is the form of {@link #windows(int)} that chooses the key, the sort fields, the directions and the
     * starting position: {@code Scrollable.of(key, size)} iterates from the start, a {@link Window#next()} token
     * or {@link Scrollable#from(String)} resumes after an earlier window, and {@code .descending()} iterates in
     * descending key order. The same key rules as {@link #scroll(Scrollable)} apply.</p>
     *
     * @param scrollable the scroll request describing key, sort, size, direction and starting position.
     * @return a stream of windows; each window's {@link Window#next()} resumes the iteration after that window.
     * @throws PersistenceException if the query carries an explicit ORDER BY, the key is nullable, or the result
     *                              type does not carry the key.
     * @since 1.14
     */
    public abstract Stream<Window<R>> windows(Scrollable<T> scrollable);

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
     * <p>The stream is one open statement on the connection it reads from, and that connection is consume-only
     * until the stream is read to its end or closed: inside a transaction every statement shares the transaction's
     * connection, so a query, a {@code Ref.fetch()} or a write issued while rows remain unread is refused with a
     * {@link PersistenceException}, on every database. A loop that needs the connection while it iterates uses
     * {@link #windows(int)}, which runs one closed statement per window. Outside a transaction the stream holds a
     * connection of its own for as long as it is open.</p>
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
     * <p>Select queries execute a dedicated count query derived from this builder: the select clause is replaced by
     * {@code COUNT(*)}, or the query is counted as a derived table when its shape requires it (DISTINCT, GROUP BY,
     * HAVING, limit, offset or a custom select clause). Queries that lock rows fetch and count
     * the results instead, so the requested locks are acquired.</p>
     *
     * @return the total number of results of this query as a long value.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     */
    public long getResultCount() {
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
    public abstract List<R> getResultList();

    /**
     * Executes the query and returns the results grouped by the record reached via {@code path}, typically the
     * parent entity of a foreign key. The SQL is not affected by the grouping; the same select is executed and the
     * results are grouped during hydration.
     *
     * <p>The returned map and its lists are unmodifiable and insertion-ordered: groups appear in the order their
     * first result is encountered, and results appear in encounter order within each group. Use {@code orderBy()} to
     * control both. Duplicate entities within a result set are guaranteed to share the same instance as long as
     * earlier occurrences remain strongly reachable, and the grouping retains every result and group key while the
     * result set is consumed; each result's reference to its group key is therefore the map key itself.</p>
     *
     * <p>This method requires an entity query: the result type must be the table type {@code T} so that the path can
     * be resolved against the results. The path must also resolve to a non-null record for every result; paths over
     * nullable foreign keys must be narrowed with a {@code where()} clause first.</p>
     *
     * <p>The signature requires a path whose component type equals its field type, which is how the generated
     * metamodels type eagerly fetched fields. Paths over {@code Ref} fields are typed
     * {@code TypedMetamodel<T, V, Ref<V>>} and therefore do not compile; use
     * {@link #getResultGroupedByRef(Metamodel)} for those.</p>
     *
     * @param path the metamodel path from the table type to the record to group by, for example {@code Pet_.owner}.
     * @param <V> the type of the record to group by.
     * @return the results grouped by the record reached via {@code path}, in encounter order.
     * @throws PersistenceException if the query fails, if the result type is not the table type, or if the path
     *                              resolves to null for a result.
     * @since 1.13
     */
    public abstract <V extends Data> SequencedMap<V, List<R>> getResultGroupedBy(TypedMetamodel<T, V, V> path);

    /**
     * Executes the query and returns the results grouped by a lightweight ref to the record reached via
     * {@code path}, typically the parent entity of a foreign key. The SQL is not affected by the grouping; the same
     * select is executed and the results are grouped during hydration.
     *
     * <p>This is the ref-based variant of {@link #getResultGroupedBy(TypedMetamodel)}: the map keys are
     * {@link Ref} instances, which are compared by primary key, keeping map lookups constant-cost regardless of the
     * size of the group record.</p>
     *
     * <p>The behavior of the keys follows how the foreign key is declared on the record:</p>
     * <ul>
     *   <li><strong>Entity field</strong> (for example {@code @FK Owner owner}): the referenced record is fetched
     *       eagerly, as part of the query's auto-joined graph, and is materialized with each result. The keys are
     *       <em>loaded</em> refs wrapping that record: {@link Ref#getOrNull()} returns it directly, without touching
     *       the database.</li>
     *   <li><strong>Ref field</strong> (for example {@code @FK Ref<Pet> pet}): the referenced record is fetched
     *       lazily; the query reads only the foreign key column, without joining or fetching the referenced table.
     *       The keys are the <em>unloaded</em> refs produced by the query, carrying just the primary key. When the
     *       records are needed, fetch them afterwards in a single query with
     *       {@code findAllByRef(map.keySet())}.</li>
     * </ul>
     *
     * <p>The returned map and its lists are unmodifiable and insertion-ordered: groups appear in the order their
     * first result is encountered, and results appear in encounter order within each group. Use {@code orderBy()} to
     * control both.</p>
     *
     * <p>This method requires an entity query: the result type must be the table type {@code T} so that the path can
     * be resolved against the results. The path must also resolve to a non-null value for every result; paths over
     * nullable foreign keys must be narrowed with a {@code where()} clause first.</p>
     *
     * @param path the metamodel path from the table type to the record to group by, for example {@code Pet_.owner}.
     * @param <V> the type of the record to group by.
     * @return the results grouped by a ref to the record reached via {@code path}, in encounter order.
     * @throws PersistenceException if the query fails, if the result type is not the table type, if the path does
     *                              not reference an entity or ref, or if the path resolves to null for a result.
     * @since 1.13
     */
    public abstract <V extends Data> SequencedMap<Ref<V>, List<R>> getResultGroupedByRef(Metamodel<T, V> path);

    /**
     * Executes the query and returns a single result.
     *
     * @return the single result.
     * @throws NoResultException if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the single row's value is null, or the query fails.
     */
    public abstract R getSingleResult();

    /**
     * Executes the query and returns an optional result.
     *
     * @return the optional result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the single row's value is null, or the query fails.
     */
    public abstract Optional<R> getOptionalResult();

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
