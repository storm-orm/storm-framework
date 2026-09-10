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

import static java.util.Objects.requireNonNull;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.IN;
import static st.orm.ResolveScope.CASCADE;
import static st.orm.core.template.TemplateString.wrap;
import static st.orm.core.template.impl.Elements.Clause.GROUP_BY;
import static st.orm.core.template.impl.Elements.Clause.ORDER_BY_ASCENDING;
import static st.orm.core.template.impl.Elements.Clause.ORDER_BY_DESCENDING;

import java.util.AbstractList;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.RandomAccess;
import java.util.SequencedMap;
import java.util.Spliterators;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.Entity;
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
 * A query builder that constructs a query from a template.
 *
 * <p>{@code QueryBuilder} is immutable: every builder method (such as {@code where()}, {@code orderBy()},
 * {@code limit()}, etc.) returns a <em>new</em> instance with the modification applied, leaving the original
 * unchanged. If you call a builder method and ignore the return value, the change is silently lost. Always use the
 * returned builder, either by chaining calls or by reassigning the variable.</p>
 *
 * @param <T> the type of the table being queried.
 * @param <R> the type of the result.
 * @param <ID> the type of the primary key.
 */
public abstract class QueryBuilder<T extends Data, R, ID> {

    /**
     * Returns the data type used in the FROM clause of the query. This is the entity or projection type {@code T}
     * that the query is built against.
     *
     * @return the FROM clause data type.
     */
    protected abstract Class<T> getFromType();

    /**
     * Returns a typed query builder for the specified primary key type.
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
     * re-enables the operations that are defined relative to the root: {@link #fetch(Navigable[])},
     * {@link #where(Data)} and {@link #getResultGroupedBy(TypedMetamodel)}.</p>
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
    public abstract QueryBuilder<Data, R, ID> crossJoin(TemplateString template);

    /**
     * Adds an inner join to the query.
     *
     * @param template the condition to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> innerJoin(TemplateString template, String alias);

    /**
     * Adds a left join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> leftJoin(TemplateString template, String alias);

    /**
     * Adds a right join to the query.
     *
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> rightJoin(TemplateString template, String alias);

    /**
     * Adds a join of the specified type to the query using a template.
     *
     * @param type the join type.
     * @param template the template to join.
     * @param alias the alias to use for the joined relation.
     * @return the query builder.
     */
    public abstract JoinBuilder<T, R, ID> join(JoinType type, TemplateString template, String alias);

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
        return where(path.asMetamodel(), EQUALS, record);
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
        Metamodel<? extends T, V> metamodel = path.asMetamodel();
        return where(predicate -> predicate.where(metamodel, ref));
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
        return where(path.asMetamodel(), IN, it);
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
        Metamodel<? extends T, V> metamodel = path.asMetamodel();
        return where(predicate -> predicate.whereRef(metamodel, it));
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
        Metamodel<? extends T, V> metamodel = path.asMetamodel();
        return where(predicate -> predicate.where(metamodel, operator, it));
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
        Metamodel<? extends T, V> metamodel = path.asMetamodel();
        return where(predicate -> predicate.where(metamodel, operator, o));
    }

    /**
     * Adds a WHERE clause to the query for the specified expression.
     *
     * @param template the expression.
     * @return the query builder.
     */
    public final QueryBuilder<T, R, ID> where(TemplateString template) {
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
     * Returns the factory this query builds its subqueries with.
     *
     * <p>The factory belongs to the query rather than to a clause: a subquery correlates through how it is embedded,
     * not through where it was created, so a clause that takes a subquery can obtain one here without going through a
     * {@link WhereBuilder}.</p>
     *
     * @return the subquery factory for this query.
     * @since 1.13
     */
    public abstract SubqueryTemplate subqueryTemplate();

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
        // Navigation-only nodes reached beyond a reference are accepted here: they name a column, and Storm
        // materializes the join for the referenced table on demand.
        if (path.length == 0) {
            throw new PersistenceException("At least one path must be provided for GROUP BY clause.");
        }
        List<TemplateString> templates = Stream.of(path)
                .flatMap(path_ -> Stream.of(wrap(new Columns(List.of(path_.asMetamodel()), CASCADE, GROUP_BY)), TemplateString.of(", ")))
                .toList();
        return groupBy(TemplateString.combine(templates.subList(0, templates.size() - 1).toArray(new TemplateString[0])));
    }

    /**
     * Adds a GROUP BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the GROUP BY clause.
     *
     * @param template the template to group by.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> groupBy(TemplateString template);

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
        return having(wrap(new ObjectExpression(path.asMetamodel(), operator, o)));
    }

    /**
     * Adds a HAVING clause to the query using the specified expression. Multiple calls to this method are combined
     * using AND.
     *
     * @param template the expression to add.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> having(TemplateString template);

    /**
     * Adds a HAVING clause to the query for the specified predicate. Multiple calls to this method are combined using
     * AND; compose the predicate with {@code and} and {@code or} to build a single clause that mixes both.
     *
     * <p>The predicate is taken directly rather than through a {@link WhereBuilder}. A HAVING clause filters groups
     * rather than rows, so the builder's identity matching would not carry over, and its methods would read
     * {@code where} at a {@code having} call site.</p>
     *
     * @param predicate the predicate to add.
     * @return the query builder.
     * @since 1.13
     */
    public abstract QueryBuilder<T, R, ID> having(PredicateBuilder<? extends T, ?, ?> predicate);

    /**
     * Adds a HAVING clause that keeps the groups for which the specified subquery returns at least one row.
     *
     * @param subquery the subquery to test for existence.
     * @return the query builder.
     * @since 1.13
     */
    public final QueryBuilder<T, R, ID> havingExists(QueryBuilder<?, ?, ?> subquery) {
        return having(TemplateString.raw("EXISTS (\0)", subquery));
    }

    /**
     * Adds a HAVING clause that keeps the groups for which the specified subquery returns no rows.
     *
     * @param subquery the subquery to test for absence.
     * @return the query builder.
     * @since 1.13
     */
    public final QueryBuilder<T, R, ID> havingNotExists(QueryBuilder<?, ?, ?> subquery) {
        return having(TemplateString.raw("NOT EXISTS (\0)", subquery));
    }

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
        List<TemplateString> templates = Stream.of(path)
                .flatMap(path_ -> Stream.of(wrap(new Columns(List.of(path_.asMetamodel()), CASCADE, ORDER_BY_ASCENDING)), TemplateString.of(", ")))
                .toList();
        return orderBy(TemplateString.combine(templates.subList(0, templates.size() - 1).toArray(new TemplateString[0])));
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
        return orderBy(TemplateString.wrap(new Columns(List.of(path.asMetamodel()), CASCADE, ORDER_BY_DESCENDING)));
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
        List<TemplateString> templates = Stream.of(path)
                .<TemplateString>flatMap(path_ -> Stream.of(wrap(new Columns(List.of(path_.asMetamodel()), CASCADE, ORDER_BY_DESCENDING)), TemplateString.of(", ")))
                .toList();
        return orderBy(TemplateString.combine(templates.subList(0, templates.size() - 1).toArray(new TemplateString[0])));
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. The results are sorted in descending order.
     * Multiple calls to this method append additional columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.9
     */
    public final QueryBuilder<T, R, ID> orderByDescending(TemplateString template) {
        return orderBy(TemplateString.combine(template, TemplateString.of(" DESC")));
    }

    /**
     * Adds an ORDER BY clause to the query using a string template. Multiple calls to this method append additional
     * columns to the ORDER BY clause.
     *
     * @param template the template to order by.
     * @return the query builder.
     * @since 1.2
     */
    public abstract QueryBuilder<T, R, ID> orderBy(TemplateString template);

    /**
     * Returns {@code true} if any ORDER BY columns have been added to this query builder.
     *
     * @return {@code true} if ORDER BY columns are present, {@code false} otherwise.
     * @since 1.9
     */
    public abstract boolean hasOrderBy();

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
    public abstract QueryBuilder<T, R, ID> forLock(TemplateString template);

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
     * Compiles this query into a reusable plan.
     *
     * <p>The builder's template is processed once; see {@link QueryTemplate#plan(TemplateString)} for the plan
     * contract. Queries without parameters, such as unfiltered selects and counts, execute via
     * {@link QueryPlan#query()}; queries whose variable parts are expressed as bind variables bind records via
     * {@link QueryPlan#bind(Data)}. Queries carrying fixed parameter values are rejected.</p>
     *
     * @return a reusable plan for this query.
     * @throws PersistenceException if the query carries fixed parameter values, or plans are not supported.
     * @since 1.13
     */
    public abstract QueryPlan plan();

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
            sorted = sorted.orderBy(wrap(new Columns(List.of(order.field()), CASCADE, order.descending() ? ORDER_BY_DESCENDING : ORDER_BY_ASCENDING)));
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
     * <p>Windows are keyset windows over the primary key, so the query must not carry an ORDER BY of its own. The
     * key is read from each row alongside the result, so refs and custom select types iterate too; a compound
     * primary key is read from the mapped record and needs the entity type as the result. The rows come in
     * ascending key order; pass {@code Scrollable.of(key, size).descending()} to {@link #windows(Scrollable)} for
     * descending order.</p>
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
     * @throws PersistenceException if the query has no primary key or carries an explicit ORDER BY.
     * @since 1.14
     */
    public final Stream<Window<R>> windows(int size) {
        var primaryKey = getPrimaryKeyMetamodel().orElseThrow(() -> new PersistenceException(
                "windows(size) orders by the primary key, but %s has none; pass windows(Scrollable.of(key, size)) with a unique key."
                        .formatted(getFromType().getSimpleName())));
        return windows(Scrollable.of(Metamodel.key(primaryKey), size));
    }

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
    public final Stream<Window<R>> windows(Scrollable<T> scrollable) {
        requireNonNull(scrollable, "scrollable");
        if (scrollable.position() != null && !scrollable.position().after()) {
            throw new PersistenceException(
                    "windows iterates in sort order from its start, so the start is a position after a row or the "
                    + "beginning; a position before a row belongs to scroll.");
        }
        return StreamSupport.stream(new WindowSpliterator(scrollable), false);
    }

    /**
     * Fetches one window per advance, so a window is read only when the caller asks for it and the statement that
     * read it has closed before the window is handed over.
     */
    private final class WindowSpliterator extends Spliterators.AbstractSpliterator<Window<R>> {
        private @Nullable Scrollable<T> next;

        WindowSpliterator(Scrollable<T> first) {
            super(Long.MAX_VALUE, ORDERED | NONNULL);
            this.next = first;
        }

        @Override
        public boolean tryAdvance(Consumer<? super Window<R>> action) {
            var request = next;
            if (request == null) {
                return false;
            }
            var window = scroll(request);
            if (window.content().isEmpty()) {
                next = null;
                return false;
            }
            Scrollable<T> token = window.next();
            if (token == null) {
                // The window has content but no navigation token, so the rows do not carry the key: the result type
                // is a ref or a custom select type. Refused up front rather than after the first window.
                throw new PersistenceException(
                        ("windows requires the result type to carry the key %s, but the query selects %s; "
                        + "select the entity type, or scroll the windows by hand.")
                                .formatted(request.key().fieldPath(), window.content().getFirst().getClass().getSimpleName()));
            }
            next = window.hasNext() ? token : null;
            action.accept(window);
            return true;
        }
    }

    /**
     * Returns the metamodel of the primary key of the FROM type, if it has one.
     *
     * @return the primary key metamodel, or empty when the FROM type has no primary key.
     * @since 1.14
     */
    protected abstract Optional<Metamodel<T, ?>> getPrimaryKeyMetamodel();

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
     * Executes the query and returns a stream of results for eager, full consumption.
     *
     * <p>Unlike {@link #getResultStream()}, implementations may execute without a fetch-size hint, since eagerly
     * consumed results gain nothing from cursor-based fetching. On dialects where cursors require a
     * transaction, this avoids wrapping auto-commit queries in a transaction. The eager terminal
     * operations, such as {@link #getResultList()} and {@link #getSingleResult()}, consume this stream.</p>
     *
     * <p>The same resource-handling rules as {@link #getResultStream()} apply: close the stream after usage,
     * preferably with a {@code try-with-resources} block.</p>
     *
     * @return a stream of results for eager consumption.
     * @throws PersistenceException if the query operation fails due to underlying database issues, such as
     *                              connectivity.
     * @since 1.13
     */
    protected Stream<R> getMaterializedResultStream() {
        return getResultStream();
    }

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
    public final List<R> getResultList() {
        try (var stream = getMaterializedResultStream()) {
            return stream.toList();
        }
    }

    /**
     * Expected distinct-group count for the grouped terminals. Grouping typically collects children per parent, so
     * group counts regularly reach the dozens; the grouping maps are sized so that such results build up without a
     * resize, at a cost of roughly 2 KB per call for small results.
     */
    private static final int EXPECTED_GROUPS = 64;

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
    public final <V extends Data> SequencedMap<V, List<R>> getResultGroupedBy(TypedMetamodel<T, V, V> path) {
        requireNonNull(path, "path");
        try (var stream = getMaterializedResultStream()) {
            // Duplicate records within a result set share the same instance (query-scoped interning), so the identity
            // map serves as a per-instance memo of each key's canonical group: the value-based hash of the group
            // record is paid once per distinct instance rather than once per row. The maps' strong references pin the
            // interner entries for every group key, so this holds regardless of the interner's reference strength.
            // Equal-by-value keys that were not interned resolve to the same group through the result map, so the
            // result is correct even without interning.
            var root = path.root();
            var groups = new IdentityHashMap<V, Group<R>>(EXPECTED_GROUPS);
            var result = LinkedHashMap.<V, List<R>>newLinkedHashMap(EXPECTED_GROUPS);
            stream.forEach(element -> {
                if (!root.isInstance(element)) {
                    throw new PersistenceException(
                            "Grouped results require an entity query rooted at %s, but got a result of type %s."
                                    .formatted(root.getName(),
                                            element == null ? "null" : element.getClass().getName()));
                }
                // Object intermediate on purpose: assigning the typed return directly would insert a checkcast to
                // V's bound and fail with a bare ClassCastException for dynamically built ref paths, bypassing the
                // descriptive backstop below.
                //noinspection unchecked
                Object value = path.getValue((T) element);
                if (value == null) {
                    throw new PersistenceException(
                            "Cannot group by %s: the path resolved to null. Narrow the query with a where() clause to exclude results without a %s."
                                    .formatted(path, path.fieldType().getSimpleName()));
                }
                if (value instanceof Ref<?>) {
                    throw new PersistenceException(
                            "Cannot group by %s: the path resolves to a ref because %s is mapped as a Ref field. Use getResultGroupedByRef() instead."
                                    .formatted(path, path.fieldType().getSimpleName()));
                }
                //noinspection unchecked
                V group = (V) value;
                var elements = groups.get(group);
                if (elements == null) {
                    elements = (Group<R>) result.computeIfAbsent(group, ignore -> new Group<>());
                    groups.put(group, elements);
                }
                elements.elements.add(element);
            });
            // The values are unmodifiable views over the lists built above, so no copy or re-wrapping is needed.
            return Collections.unmodifiableSequencedMap(result);
        }
    }

    /**
     * Executes the query and returns the results grouped by a lightweight ref to the record reached via
     * {@code path}, typically the parent entity of a foreign key. The SQL is not affected by the grouping; the same
     * select is executed and the results are grouped during hydration.
     *
     * <p>This is the ref-based variant of {@link #getResultGroupedBy(TypedMetamodel)}: the map keys are {@link Ref}
     * instances, which are compared by primary key, keeping map lookups constant-cost regardless of the size of the
     * group record.</p>
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
    public final <V extends Data> SequencedMap<Ref<V>, List<R>> getResultGroupedByRef(Metamodel<T, V> path) {
        requireNonNull(path, "path");
        try (var stream = getMaterializedResultStream()) {
            // Refs hash and compare by primary key, so the grouping map is cheap without an identity-based fast path.
            // The values are unmodifiable views appended through their backing lists, so no copy or re-wrapping is
            // needed when the result is returned.
            var root = path.root();
            var groups = LinkedHashMap.<Ref<V>, List<R>>newLinkedHashMap(EXPECTED_GROUPS);
            stream.forEach(element -> {
                if (!root.isInstance(element)) {
                    throw new PersistenceException(
                            "Grouped results require an entity query rooted at %s, but got a result of type %s."
                                    .formatted(root.getName(),
                                            element == null ? "null" : element.getClass().getName()));
                }
                //noinspection unchecked
                Object value = path.getValue((T) element);
                if (value == null) {
                    throw new PersistenceException(
                            "Cannot group by %s: the path resolved to null. Narrow the query with a where() clause to exclude results without a %s."
                                    .formatted(path, path.fieldType().getSimpleName()));
                }
                Ref<V> group;
                if (value instanceof Ref<?> ref) {
                    //noinspection unchecked
                    group = (Ref<V>) ref;
                } else if (value instanceof Entity<?> entity) {
                    //noinspection unchecked
                    group = (Ref<V>) Ref.of(entity);
                } else {
                    throw new PersistenceException(
                            "Cannot group by ref at %s: the path must reference an entity or a ref, but resolved to %s."
                                    .formatted(path, value.getClass().getName()));
                }
                ((Group<R>) groups.computeIfAbsent(group, ignore -> new Group<>())).elements.add(element);
            });
            return Collections.unmodifiableSequencedMap(groups);
        }
    }

    /**
     * Unmodifiable list view over a group's elements. The grouping terminals append through {@link #elements} while
     * building, so the map values are unmodifiable from the start and require no copy or re-wrapping when the result
     * is returned.
     */
    private static final class Group<R> extends AbstractList<R> implements RandomAccess {
        final ArrayList<R> elements = new ArrayList<>();

        @Override
        public R get(int index) {
            return elements.get(index);
        }

        @Override
        public int size() {
            return elements.size();
        }
    }

    /**
     * Executes the query and returns a single result.
     *
     * @return the single result.
     * @throws NoResultException if there is no result.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the single row's value is null, or the query fails.
     */
    public final R getSingleResult() {
        return singleResultInternal();
    }

    /**
     * Backs {@link #getSingleResult()}. The default consumes {@link #getMaterializedResultStream()}; subclasses may
     * override with a cheaper single-row path.
     */
    protected R singleResultInternal() {
        try (var stream = getMaterializedResultStream()) {
            var iterator = stream.iterator();
            if (!iterator.hasNext()) {
                throw new NoResultException("Expected single result, but found none.");
            }
            R result = iterator.next();
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
     * Executes the query and returns an optional result.
     *
     * @return the optional result; {@link Optional#empty()} when no row matched.
     * @throws NonUniqueResultException if more than one result.
     * @throws PersistenceException if the single row's value is null, or the query fails.
     */
    public final Optional<R> getOptionalResult() {
        return optionalResultInternal();
    }

    /**
     * Backs {@link #getOptionalResult()}. The default consumes {@link #getMaterializedResultStream()}; subclasses may
     * override with a cheaper single-row path.
     */
    protected Optional<R> optionalResultInternal() {
        try (var stream = getMaterializedResultStream()) {
            var iterator = stream.iterator();
            if (!iterator.hasNext()) {
                return Optional.empty();
            }
            R result = iterator.next();
            if (iterator.hasNext()) {
                throw new NonUniqueResultException("Expected single result, but found more than one.");
            }
            if (result == null) {
                throw new PersistenceException("Result is null. Wrap the field in COALESCE() to provide a non-null default.");
            }
            return Optional.of(result);
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
