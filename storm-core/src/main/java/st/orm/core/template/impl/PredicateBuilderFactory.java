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
package st.orm.core.template.impl;

import static java.util.Objects.requireNonNull;
import static st.orm.Operator.CONTAINS_IGNORE_CASE;
import static st.orm.core.template.TemplateString.wrap;

import java.util.ArrayList;
import java.util.List;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.Ref;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.QueryBuilderImpl.PredicateBuilderImpl;

/**
 * Factory for creating instances of {@link PredicateBuilder}.
 *
 * <p>This interface provides static methods to create predicate builders for records, allowing for flexible query
 * construction.</p>
 *
 * @since 1.3
 */
public interface PredicateBuilderFactory {

    /**
     * Creates a new instance of {@link PredicateBuilder} for the specified path, operator, and values.
     *
     * @param path the metamodel path representing the field to be queried
     * @param operator the operator to be used in the predicate
     * @param o the values to be used in the predicate
     * @param <T> the type of the record
     * @param <R> the type of the result
     * @param <V> the type of the values
     * @return a new instance of {@link PredicateBuilder}
     */
    static <T extends Data, R, V> PredicateBuilder<T, R, ?> create(
            Metamodel<?, V> path,
            Operator operator,
            Iterable<V> o) {
        return new PredicateBuilderImpl<>(wrap(new ObjectExpression(path, operator, o)));
    }

    /**
     * Creates a new instance of {@link PredicateBuilder} for the specified path, operator, and values.
     *
     * @param path the metamodel path representing the field to be queried
     * @param operator the operator to be used in the predicate
     * @param o the values to be used in the predicate
     * @param <T> the type of the record
     * @param <R> the type of the result
     * @param <V> the type of the values
     * @return a new instance of {@link PredicateBuilder}
     */
    static <T extends Data, R, V extends Data> PredicateBuilder<T, R, ?> createRef(
            Metamodel<?, V> path,
            Operator operator,
            Iterable<Ref<V>> o) {
        return new PredicateBuilderImpl<>(wrap(new ObjectExpression(path, operator, o)));
    }

    /**
     * Creates a new instance of {@link PredicateBuilder} for the specified path, operator, and values with an ID.
     *
     * @param path the metamodel path representing the field to be queried
     * @param operator the operator to be used in the predicate
     * @param o the values to be used in the predicate
     * @param <T> the type of the record
     * @param <R> the type of the result
     * @param <ID> the type of the ID
     * @param <V> the type of the values
     * @return a new instance of {@link PredicateBuilder}
     */
    static <T extends Data, R, ID, V> PredicateBuilder<T, R, ID> createWithId(
            Metamodel<?, V> path,
            Operator operator,
            Iterable<V> o) {
        return new PredicateBuilderImpl<>(wrap(new ObjectExpression(path, operator, o)));
    }

    /**
     * Creates a predicate that matches the terms of a search input against text columns. Every term must occur in
     * at least one of the paths, ignoring case and matched as literal text, as {@link st.orm.Operator#CONTAINS_IGNORE_CASE}
     * matches it, so the predicate is an AND over the terms of an OR over the paths. Whitespace separates the terms and
     * a quoted phrase is one term; an input without terms matches every row. Each term is matched on its own, so on a
     * semi-joined table the terms may match different related rows.
     *
     * <p>A term shorter than the dialect's substring index serves
     * ({@link st.orm.core.template.SqlDialect#minimumSearchTermLength()}) is tested only on the rows the longer terms
     * found, so it reads no table in full, and the result is the same.</p>
     *
     * @param input the search input, as a search box receives it.
     * @param paths the text columns to search, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @since 1.15
     */
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> search(String input,
                                                                     List<? extends Metamodel<?, String>> paths) {
        return words(input, paths, false);
    }

    /**
     * Creates a predicate that matches the terms of a search box that queries as the user types, such as an
     * auto-complete, against text columns. It matches as {@link #search(String, List)} does, on the terms the
     * dialect's substring index serves alone ({@link st.orm.core.template.SqlDialect#minimumSearchTermLength()}): a
     * shorter term is ignored, and an input without a term the index serves, an empty one included, matches no rows.
     * Every statement it renders is therefore served by the index. On a dialect that treats every term alike it
     * matches as {@code search} does, except that an input without terms matches no rows.
     *
     * @param input the search input, as a search box receives it.
     * @param paths the text columns to search, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @since 1.16
     */
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> suggest(String input,
                                                                      List<? extends Metamodel<?, String>> paths) {
        return words(input, paths, true);
    }

    /**
     * Creates the predicate of {@link #search(String, List)} or {@link #suggest(String, List)}. Each word is kept
     * apart until the query is built, since which words a substring index serves depends on the dialect.
     */
    private static <T extends Data, R, ID> PredicateBuilder<T, R, ID> words(String input,
                                                                            List<? extends Metamodel<?, String>> paths,
                                                                            boolean suggest) {
        requireNonNull(input, "input");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("%s needs at least one path to search.".formatted(suggest ? "suggest" : "search"));
        }
        List<String> terms = SearchTerms.parse(input);
        var words = new ArrayList<PredicateNode>(terms.size());
        for (String term : terms) {
            var matches = new ArrayList<PredicateNode>(paths.size());
            for (Metamodel<?, String> path : paths) {
                matches.add(new PredicateNode.Term(wrap(new ObjectExpression(path, CONTAINS_IGNORE_CASE, List.of(term))), true));
            }
            // Each word is matched on its own, so on a semi-joined table the words may match different related rows.
            words.add(PredicateNode.or(matches));
        }
        return new PredicateBuilderImpl<>(new PredicateNode.Search(terms, words, suggest));
    }
}
