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

import st.orm.Data;
import st.orm.Navigable;
import st.orm.Operator;
import st.orm.Ref;

/**
 * A builder for constructing the WHERE clause of a query, providing type-safe predicate construction.
 *
 * <p>The {@code WhereBuilder} is passed to the lambda argument of {@link QueryBuilder#where(java.util.function.Function)}
 * and offers methods for matching by primary key, record, ref, metamodel path, or custom string template
 * expressions. Each method returns a {@link PredicateBuilder} that can be further composed using
 * {@code and()} and {@code or()} combinators.</p>
 *
 * <p>The {@code where} methods are type-safe and restrict metamodel paths to the root table's entity graph.
 * A join widens the query root, after which the {@code where} methods accept paths from any entity in the query;
 * a path on an entity outside the query fails when the query is built.</p>
 *
 * <h2>Example</h2>
 * <pre>{@code
 * List<User> users = userRepository
 *         .select()
 *         .where(predicate -> predicate
 *             .where(User_.email, LIKE, "%@example.com")
 *             .and(predicate.where(User_.address.city.name, EQUALS, "Sunnyvale")))
 *         .getResultList();
 * }</pre>
 *
 * @param <T>  the type of the table being queried.
 * @param <R>  the type of the result.
 * @param <ID> the type of the primary key.
 * @see QueryBuilder#where(java.util.function.Function)
 * @see PredicateBuilder
 */
public abstract class WhereBuilder<T extends Data, R, ID> implements SubqueryTemplate {

    /**
     * A predicate that always evaluates to true.
     */
    public final PredicateBuilder<T, R, ID> TRUE() {
        return where(RAW."TRUE");
    }

    /**
     * A predicate that always evaluates to false.
     */
    public final PredicateBuilder<T, R, ID> FALSE() {
        return where(RAW."FALSE");
    }

    /**
     * Adds an <code>EXISTS</code> condition to the WHERE clause using the specified subquery.
     *
     * <p>This method appends an <code>EXISTS</code> clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the presence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the updated {@link PredicateBuilder} with the EXISTS condition applied.
     */
    public abstract PredicateBuilder<T, R, ID> exists(QueryBuilder<?, ?, ?> subquery);

    /**
     * Adds an <code>NOT EXISTS</code> condition to the WHERE clause using the specified subquery.
     *
     * <p>This method appends an <code>NOT EXISTS</code> clause to the current query's WHERE condition.
     * It checks whether the provided subquery returns any rows, allowing you to filter results based
     * on the existence of related data. This is particularly useful for constructing queries that need
     * to verify the absence of certain records in a related table or subquery.
     *
     * @param subquery the subquery to check for existence.
     * @return the updated {@link PredicateBuilder} with the NOT EXISTS condition applied.
     */
    public abstract PredicateBuilder<T, R, ID> notExists(QueryBuilder<?, ?, ?> subquery);

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table.
     *
     * @param id the id to match.
     * @return the predicate builder.
     */
    public abstract PredicateBuilder<T, R, ID> whereId(ID id);

    /**
     * Adds a condition to the WHERE clause that matches the specified primary key of the table, expressed by a ref.
     *
     * @param ref the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public abstract PredicateBuilder<T, R, ID> whereRef(Ref<T> ref);

    /**
     * Adds a condition to the WHERE clause that matches the specified record.
     *
     * @param record the record to match.
     * @return the predicate builder.
     */
    public abstract PredicateBuilder<T, R, ID> where(T record);

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table.
     *
     * @param it the ids to match.
     * @return the predicate builder.
     * @since 1.2
     */
    public abstract PredicateBuilder<T, R, ID> whereId(Iterable<? extends ID> it);

    /**
     * Adds a condition to the WHERE clause that matches the specified primary keys of the table, expressed by a ref.
     *
     * @param it the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public abstract PredicateBuilder<T, R, ID> whereRef(Iterable<? extends Ref<T>> it);

    /**
     * Adds a condition to the WHERE clause that matches the specified records.
     *
     * @param it the records to match.
     * @return the predicate builder.
     */
    public abstract PredicateBuilder<T, R, ID> where(Iterable<? extends T> it);

    /**
     * Adds a condition to the WHERE clause that matches the specified record. The record can represent any of
     * the related tables in the table graph.
     *
     * @param path   the path to the object in the table graph.
     * @param record the records to match.
     * @return the predicate builder.
     */
    public final <V extends Data> PredicateBuilder<T, R, ID> where(Navigable<? extends T, V> path, V record) {
        return where(path, EQUALS, record);
    }

    /**
     * Adds a condition to the WHERE clause that matches the specified ref. The record can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param ref  the ref to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public abstract <V extends Data> PredicateBuilder<T, R, ID> where(Navigable<? extends T, V> path, Ref<V> ref);

    /**
     * Adds a condition to the WHERE clause that matches the specified refs. The refs can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the ref in the table graph.
     * @param it   the refs to match.
     * @return the predicate builder.
     * @since 1.3
     */
    public abstract <V extends Data> PredicateBuilder<T, R, ID> whereRef(Navigable<? extends T, V> path, Iterable<? extends Ref<V>> it);

    /**
     * Adds a condition to the WHERE clause that matches the specified records. The records can represent any of
     * the related tables in the table graph.
     *
     * @param path the path to the object in the table graph.
     * @param it   the records to match.
     * @return the predicate builder.
     */
    public final <V extends Data> PredicateBuilder<T, R, ID> where(Navigable<? extends T, V> path, Iterable<V> it) {
        return where(path, IN, it);
    }

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param it       the objects to match, which can be primary keys, records representing the table, or fields in the
     *                 table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    public abstract <V> PredicateBuilder<T, R, ID> where(Navigable<? extends T, V> path,
                                                         Operator operator,
                                                         Iterable<? extends V> it);

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o        the object(s) to match, which can be primary keys, records representing the table, or fields in the
     *                 table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    @SafeVarargs
    public final <V> PredicateBuilder<T, R, ID> where(Navigable<? extends T, V> path,
                                                      Operator operator,
                                                      V... o) {
        return whereImpl(path, operator, o);
    }

    /**
     * Appends a custom expression to the WHERE clause.
     *
     * @param template the expression to add.
     * @return the predicate builder.
     */
    public abstract PredicateBuilder<T, R, ID> where(StringTemplate template);

    /**
     * Adds a condition to the WHERE clause that matches the specified objects at the specified path in the table
     * graph or manually added joins.
     *
     * @param path     the path to the object in the table graph.
     * @param operator the operator to use for the comparison.
     * @param o        the object(s) to match, which can be primary keys, records representing the table, or fields in the
     *                 table graph.
     * @param <V>      the type of the object that the metamodel represents.
     * @return the query builder.
     * @since 1.2
     */
    protected abstract <V> PredicateBuilder<T, R, ID> whereImpl(Navigable<? extends T, V> path,
                                                                Operator operator,
                                                                V[] o);
}
