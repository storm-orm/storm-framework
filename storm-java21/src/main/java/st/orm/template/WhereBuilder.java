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

import java.util.List;
import java.util.Set;
import st.orm.Data;
import st.orm.Match;
import st.orm.Metamodel;
import st.orm.Navigable;
import st.orm.Operator;
import st.orm.PersistenceException;
import st.orm.Ref;
import st.orm.SqlTemplateException;
import st.orm.core.template.impl.PredicateBuilderFactory;
import st.orm.template.impl.QueryBuilderImpl.PredicateBuilderImpl;

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
     *
     * <p>It renders as {@code 1 = 1}, a comparison every database reads as a condition, where a boolean literal is
     * not one on SQL Server or on Oracle before 23.</p>
     */
    public final PredicateBuilder<T, R, ID> TRUE() {
        return where(RAW."1 = 1");
    }

    /**
     * A predicate that always evaluates to false.
     *
     * <p>It renders as {@code 1 <> 1}, a comparison every database reads as a condition, where a boolean literal is
     * not one on SQL Server or on Oracle before 23.</p>
     */
    public final PredicateBuilder<T, R, ID> FALSE() {
        return where(RAW."1 <> 1");
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
        try {
            try {
                return PredicateBuilderImpl.of(PredicateBuilderFactory.createWithId(path.asMetamodel(), operator, List.of(o)));
            } catch (NullPointerException e) {
                throw new SqlTemplateException("Null value not allowed.");
            }
        } catch (SqlTemplateException e) {
            throw new PersistenceException(e);
        }
    }

    /**
     * Adds a condition to the WHERE clause that compares the column at the specified path with the column at another
     * path of the query, such as a foreign key with a foreign key to the same table. Both paths resolve as they do in
     * a value comparison: to a table of the query, or to a path joined on demand; a path on a table outside the query
     * is refused when the query is built. A multi-column path compares component-wise with a path of as many columns.
     *
     * @param path     the path to the column on the left of the operator.
     * @param operator the operator to use for the comparison.
     * @param other    the path to the column on the right of the operator.
     * @param <V>      the type of the value both columns hold.
     * @return the predicate builder.
     * @since 1.16
     */
    public abstract <V> PredicateBuilder<T, R, ID> where(Navigable<? extends T, V> path,
                                                         Operator operator,
                                                         Navigable<?, V> other);

    /**
     * Matches the words of a search input against one or more text columns. Every word must occur in at least one of
     * the paths, ignoring case and matched as literal text, as {@link Operator#CONTAINS_IGNORE_CASE} matches it, so
     * {@code search("davis madison", Owner_.lastName, Owner_.address.city.name)} finds Harold Davis of Madison. Whitespace separates the words,
     * and a quote at the start of a word opens a phrase such as {@code "the good"}, one word that runs to the next
     * quote or to the end of the input; a quote inside a word is an ordinary character. An input without words, such
     * as an empty search box, matches every row. {@link #search(String, Match, Match[])} states another match form
     * per column.
     *
     * <p>A path through a relation joins its table as any other path does, and a path may start at a table the query
     * joins. A path may also start at a semi-joined table, which searches the rows related to each row; each word is
     * matched on its own, so the words may match different related rows. Accents compare as the column's collation
     * compares them.</p>
     *
     * <p>A word shorter than the database's substring index serves is tested only on the rows the longer words found,
     * so it reads no table in full; the rows found are the same. A search box that queries as the user types, such
     * as an auto-complete, uses {@link #suggest(String, Navigable, Navigable[])} instead.</p>
     *
     * @param input the search input, as a search box receives it.
     * @param path  the first text column to search.
     * @param more  further text columns to search.
     * @return the predicate builder.
     * @since 1.15
     */
    @SafeVarargs
    public final PredicateBuilder<T, R, ID> search(String input,
                                                   Navigable<? extends T, String> path,
                                                   Navigable<? extends T, String>... more) {
        return PredicateBuilderImpl.of(PredicateBuilderFactory.search(input, metamodels(path, more)));
    }

    /**
     * Matches the words of a search input against text columns, each in its stated match form, as
     * {@link #search(String, Navigable, Navigable[])} matches them anywhere in the value. A field states its form
     * through the factories of {@link Match}: {@code anywhere}, {@code wordStart}, {@code prefix} or {@code exact};
     * {@code search("van berg", wordStart(Owner_.lastName), exact(Owner_.telephone))} matches each word at the start
     * of a word of the last name or as the whole telephone number.
     *
     * @param input the search input, as a search box receives it.
     * @param field the first text column to search, with its match form.
     * @param more  further text columns to search, with their match forms.
     * @return the predicate builder.
     * @since 1.16
     */
    @SafeVarargs
    public final PredicateBuilder<T, R, ID> search(String input,
                                                   Match<? extends T> field,
                                                   Match<? extends T>... more) {
        return PredicateBuilderImpl.of(PredicateBuilderFactory.search(input, fields(field, more)));
    }

    /**
     * Matches the words of a search box that queries as the user types, such as an auto-complete, against one or more
     * text columns. Every word matches at the start of a word in one of the columns, the same rows on every
     * database: {@code suggest("van b", Owner_.lastName)} finds "Van den Berg" and not "Sullivan". The input is read
     * as {@link #search(String, Navigable, Navigable[])} reads it. An input without a word of
     * three characters matches the whole input as the start of the value, ignoring case, and a shorter word next to one that reaches the length is tested only on the
     * rows the longer word found. An empty input, or one of a single character, matches no rows, so the box never
     * fetches and sorts a whole table on the first keystroke.
     * {@link #suggest(String, Match, Match[])} states another match form per column.
     *
     * @param input the search input, as a search box receives it.
     * @param path  the first text column to search.
     * @param more  further text columns to search.
     * @return the predicate builder.
     * @since 1.16
     */
    @SafeVarargs
    public final PredicateBuilder<T, R, ID> suggest(String input,
                                                    Navigable<? extends T, String> path,
                                                    Navigable<? extends T, String>... more) {
        return suggest(input, Set.of(), path, more);
    }

    /**
     * Matches the words of a search box that queries as the user types against one or more text columns, as
     * {@link #suggest(String, Navigable, Navigable[])} does, ignoring the given words as well. A word that equals one
     * of them, ignoring case, takes no part in the predicate, so an input of ignored words alone matches no
     * rows; a quoted word is never ignored. A search box names the words that
     * occur in a large share of its rows, such as "van" in a list of last names, which narrow nothing:
     * {@code suggest("van berg", Set.of("van", "de", "der"), Owner_.lastName)} matches on "berg" alone.
     *
     * @param input  the search input, as a search box receives it.
     * @param ignore the words to ignore, compared ignoring case.
     * @param path   the first text column to search.
     * @param more   further text columns to search.
     * @return the predicate builder.
     * @since 1.16
     */
    @SafeVarargs
    public final PredicateBuilder<T, R, ID> suggest(String input,
                                                    Set<String> ignore,
                                                    Navigable<? extends T, String> path,
                                                    Navigable<? extends T, String>... more) {
        return PredicateBuilderImpl.of(PredicateBuilderFactory.suggest(input, ignore, metamodels(path, more)));
    }

    /**
     * Matches the words of a search box that queries as the user types against text columns, each in its stated
     * match form, as {@link #suggest(String, Navigable, Navigable[])} matches them at the start of a word. A field
     * states its form through the factories of {@link Match}: {@code anywhere}, {@code wordStart}, {@code prefix} or
     * {@code exact}; {@code suggest(input, wordStart(Owner_.lastName), prefix(OwnerNameSuffix_.suffix))} matches each
     * word at the start of a word of the name, or as a prefix of a suffix row, which an ordinary index serves.
     *
     * @param input the search input, as a search box receives it.
     * @param field the first text column to search, with its match form.
     * @param more  further text columns to search, with their match forms.
     * @return the predicate builder.
     * @since 1.16
     */
    @SafeVarargs
    public final PredicateBuilder<T, R, ID> suggest(String input,
                                                    Match<? extends T> field,
                                                    Match<? extends T>... more) {
        return suggest(input, Set.of(), field, more);
    }

    /**
     * Matches the words of a search box that queries as the user types against text columns, each in its stated
     * match form, as {@link #suggest(String, Match, Match[])} does, ignoring the given words as well, as
     * {@link #suggest(String, Set, Navigable, Navigable[])} ignores them. A quoted word is never ignored.
     *
     * @param input  the search input, as a search box receives it.
     * @param ignore the words to ignore, compared ignoring case.
     * @param field  the first text column to search, with its match form.
     * @param more   further text columns to search, with their match forms.
     * @return the predicate builder.
     * @since 1.16
     */
    @SafeVarargs
    public final PredicateBuilder<T, R, ID> suggest(String input,
                                                    Set<String> ignore,
                                                    Match<? extends T> field,
                                                    Match<? extends T>... more) {
        return PredicateBuilderImpl.of(PredicateBuilderFactory.suggest(input, ignore, fields(field, more)));
    }

    /** Returns the paths as the metamodels the core factory takes. */
    @SafeVarargs
    @SuppressWarnings("unchecked")
    private static <T extends Data> Metamodel<?, String>[] metamodels(Navigable<? extends T, String> path,
                                                                       Navigable<? extends T, String>... more) {
        var metamodels = (Metamodel<?, String>[]) new Metamodel<?, ?>[more.length + 1];
        metamodels[0] = path.asMetamodel();
        for (int i = 0; i < more.length; i++) {
            metamodels[i + 1] = more[i].asMetamodel();
        }
        return metamodels;
    }

    /** Returns the fields as the array the core factory takes. */
    @SafeVarargs
    private static <T extends Data> Match<?>[] fields(Match<? extends T> field, Match<? extends T>... more) {
        var fields = new Match<?>[more.length + 1];
        fields[0] = field;
        System.arraycopy(more, 0, fields, 1, more.length);
        return fields;
    }

    /**
     * Appends a custom expression to the WHERE clause.
     *
     * @param template the expression to add.
     * @return the predicate builder.
     */
    public abstract PredicateBuilder<T, R, ID> where(StringTemplate template);
}
