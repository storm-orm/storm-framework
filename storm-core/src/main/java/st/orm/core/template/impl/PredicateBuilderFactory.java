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

import static java.util.Locale.ROOT;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.toSet;
import static st.orm.Operator.CONTAINS_IGNORE_CASE;
import static st.orm.Operator.STARTS_WITH;
import static st.orm.Operator.STARTS_WITH_IGNORE_CASE;
import static st.orm.core.template.TemplateString.wrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
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
        return words(input, Set.of(), paths, false);
    }

    /**
     * Creates a predicate that matches the terms of a search box that queries as the user types, such as an
     * auto-complete, against text columns. A term takes part from two characters, and matches in the form the
     * dialect's index serves: where a substring index serves terms of a length
     * ({@link st.orm.core.template.SqlDialect#minimumSearchTermLength()}), a term of that length matches anywhere, as
     * {@link #search(String, List)} matches it, and a shorter one at the start of a word, which the index serves
     * from one character less; where no index serves a substring, a term, lowered, matches as a prefix of the bare
     * column, as {@link st.orm.Operator#STARTS_WITH} matches it, which an ordinary index over the column serves, so
     * the column is one that compares case-insensitively on its own. An
     * input without a term that takes part, an empty one included, matches no rows. Every statement it renders is
     * therefore served by an index.
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
        return suggest(input, Set.of(), paths);
    }

    /**
     * Creates the predicate of {@link #suggest(String, List)}, ignoring the given terms as well: a term that equals
     * one of them, ignoring case, takes no part in the predicate, as a term the index does not serve takes none, so an
     * input of ignored terms alone matches no rows. A search box names the words that occur in a large share of its
     * rows, such as "the" in a catalog of titles, which the index serves but which narrow nothing.
     *
     * @param input the search input, as a search box receives it.
     * @param ignore the terms to ignore, compared ignoring case.
     * @param paths the text columns to search, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @since 1.16
     */
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> suggest(String input,
                                                                      Set<String> ignore,
                                                                      List<? extends Metamodel<?, String>> paths) {
        requireNonNull(ignore, "ignore");
        return words(input, ignore, paths, true);
    }

    /**
     * Creates the predicate of {@link #search(String, List)} or {@link #suggest(String, Set, List)}. Each word is kept
     * apart until the query is built, with the forms that match it anywhere, at the start of a word and as a prefix
     * of the column, since which form the database's index serves depends on the dialect; an ignored word is left
     * out here, since no dialect serves it.
     */
    private static <T extends Data, R, ID> PredicateBuilder<T, R, ID> words(String input,
                                                                            Set<String> ignore,
                                                                            List<? extends Metamodel<?, String>> paths,
                                                                            boolean suggest) {
        requireNonNull(input, "input");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("%s needs at least one path to search.".formatted(suggest ? "suggest" : "search"));
        }
        List<String> terms = SearchTerms.parse(input);
        if (!ignore.isEmpty()) {
            Set<String> ignored = ignore.stream().map(word -> word.toLowerCase(ROOT)).collect(toSet());
            terms = terms.stream().filter(term -> !ignored.contains(term.toLowerCase(ROOT))).toList();
        }
        var words = new ArrayList<PredicateNode>(terms.size());
        var starts = new ArrayList<PredicateNode>(terms.size());
        var prefixes = new ArrayList<PredicateNode>(terms.size());
        for (String term : terms) {
            var matches = new ArrayList<PredicateNode>(paths.size());
            for (Metamodel<?, String> path : paths) {
                matches.add(new PredicateNode.Term(wrap(new ObjectExpression(path, CONTAINS_IGNORE_CASE, List.of(term))), true));
            }
            // Each word is matched on its own, so on a semi-joined table the words may match different related rows.
            words.add(PredicateNode.or(matches));
            if (!suggest) {
                continue;
            }
            // The forms a suggestion renders where the index serves no substring of the term's length.
            var wordStarts = new ArrayList<PredicateNode>(paths.size() * 2);
            var columnStarts = new ArrayList<PredicateNode>(paths.size());
            for (Metamodel<?, String> path : paths) {
                // A word starts the column or follows a space; a space before the term is literal text in the pattern.
                wordStarts.add(new PredicateNode.Term(wrap(new ObjectExpression(path, STARTS_WITH_IGNORE_CASE, List.of(term))), true));
                wordStarts.add(new PredicateNode.Term(wrap(new ObjectExpression(path, CONTAINS_IGNORE_CASE, List.of(" " + term))), true));
                // The column stays bare, for an ordinary index over it, and the term is lowered here: the column is one
                // that compares case-insensitively on its own, a stored lowered column included.
                columnStarts.add(new PredicateNode.Term(wrap(new ObjectExpression(path, STARTS_WITH, List.of(term.toLowerCase(ROOT)))), true));
            }
            starts.add(PredicateNode.or(wordStarts));
            prefixes.add(PredicateNode.or(columnStarts));
        }
        return new PredicateBuilderImpl<>(new PredicateNode.Search(terms, words, starts, prefixes, suggest));
    }
}
