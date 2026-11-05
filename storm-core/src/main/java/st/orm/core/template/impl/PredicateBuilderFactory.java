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
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.STARTS_WITH;
import static st.orm.Operator.STARTS_WITH_IGNORE_CASE;
import static st.orm.core.template.TemplateString.wrap;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import st.orm.Data;
import st.orm.Match;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.Ref;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.impl.Elements.ObjectExpression;
import st.orm.core.template.impl.Elements.PathExpression;
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
     * Creates a predicate comparing the column at a path with the column at another path of the query, such as a
     * foreign key with a foreign key to the same table. Each path resolves as it does in a value comparison: to a
     * table of the query, or to a path joined on demand. A multi-column path compares component-wise with a path of
     * as many columns.
     *
     * @param path the path to the column on the left of the operator.
     * @param operator the operator.
     * @param other the path to the column on the right of the operator.
     * @param <T> the type of the record.
     * @param <R> the type of the result.
     * @param <V> the type of the value both columns hold.
     * @return the predicate.
     * @since 1.16
     */
    static <T extends Data, R, V> PredicateBuilder<T, R, ?> compare(
            Metamodel<?, V> path,
            Operator operator,
            Metamodel<?, V> other) {
        return new PredicateBuilderImpl<>(wrap(new PathExpression(path, operator, other)));
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
     * Creates a predicate that matches the terms of a search input against text columns, each in the default form of
     * {@code search}: a term matches anywhere in the value, ignoring case and as literal text, as
     * {@link st.orm.Operator#CONTAINS_IGNORE_CASE} matches it.
     *
     * @param input the search input, as a search box receives it.
     * @param paths the text columns to search, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @see #search(String, Match[])
     * @since 1.15
     */
    @SafeVarargs
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> search(String input, Metamodel<?, String>... paths) {
        return search(input, fields(Match::anywhere, paths));
    }

    /**
     * Creates a predicate that matches the terms of a search input against text columns, each in its stated match
     * form. Every term must occur in at least one of the fields, so the predicate is an AND over the terms of an OR
     * over the fields. Whitespace separates the terms, and a quote at the start of a term opens a phrase that runs to
     * the next quote or to the end of the input; an input without terms matches every row. Each term is matched on
     * its own, so on a semi-joined table the terms may match different related rows.
     *
     * <p>A term shorter than the dialect's substring index serves
     * ({@link st.orm.core.template.SqlDialect#minimumSearchTermLength()}) is tested only on the rows the longer terms
     * found, so it reads no table in full, and the result is the same.</p>
     *
     * @param input the search input, as a search box receives it.
     * @param fields the text columns to search with their match forms, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @since 1.16
     */
    @SafeVarargs
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> search(String input, Match<?>... fields) {
        return words(input, Set.of(), fields, false);
    }

    /**
     * Creates a predicate that matches the terms of a search box that queries as the user types against text
     * columns, each in the default form of {@code suggest}: a term matches at the start of any word in the value.
     *
     * @param input the search input, as a search box receives it.
     * @param ignore the terms to ignore, compared ignoring case.
     * @param paths the text columns to search, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @see #suggest(String, Set, Match[])
     * @since 1.16
     */
    @SafeVarargs
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> suggest(String input,
                                                                      Set<String> ignore,
                                                                      Metamodel<?, String>... paths) {
        return suggest(input, ignore, fields(Match::wordStart, paths));
    }

    /**
     * Creates a predicate that matches the terms of a search box that queries as the user types, such as an
     * auto-complete, against text columns, each in its stated match form. A term takes part from two characters; an
     * input without a term that takes part, an empty one included, matches no rows, so the box never fetches and
     * sorts a whole table on the first keystroke. The input is read as {@link #search(String, Match[])} reads it.
     *
     * <p>A bare term that equals one of the ignored terms, ignoring case, takes no part in the predicate, as a
     * one-character term takes none; a quoted term is never ignored. A search box names the words that occur in a
     * large share of its rows, such as "the" in a catalog of titles, which narrow nothing.</p>
     *
     * @param input the search input, as a search box receives it.
     * @param ignore the terms to ignore, compared ignoring case.
     * @param fields the text columns to search with their match forms, at least one.
     * @param <T> the type of the table being queried.
     * @param <R> the type of the result.
     * @param <ID> the type of the primary key.
     * @return the predicate.
     * @since 1.16
     */
    @SafeVarargs
    static <T extends Data, R, ID> PredicateBuilder<T, R, ID> suggest(String input,
                                                                      Set<String> ignore,
                                                                      Match<?>... fields) {
        requireNonNull(ignore, "ignore");
        return words(input, ignore, fields, true);
    }

    /**
     * Returns the paths as fields of one match form, the default form of a function.
     */
    private static Match<?>[] fields(Function<Metamodel<?, String>, Match<?>> form, Metamodel<?, String>[] paths) {
        var fields = new Match<?>[paths.length];
        for (int i = 0; i < paths.length; i++) {
            fields[i] = form.apply(paths[i]);
        }
        return fields;
    }

    /**
     * Creates the predicate of {@link #search(String, Match[])} or {@link #suggest(String, Set, Match[])}. Each term is kept
     * apart until the query is built, since {@code search} plans its short terms by the length the dialect's
     * substring index serves; an ignored term is left out here, since no dialect serves it.
     */
    private static <T extends Data, R, ID> PredicateBuilder<T, R, ID> words(String input,
                                                                            Set<String> ignore,
                                                                            Match<?>[] fields,
                                                                            boolean suggest) {
        requireNonNull(input, "input");
        if (fields.length == 0) {
            throw new IllegalArgumentException("%s needs at least one field to search.".formatted(suggest ? "suggest" : "search"));
        }
        List<SearchTerms.Term> terms = SearchTerms.parse(input);
        if (!ignore.isEmpty()) {
            Set<String> ignored = ignore.stream().map(word -> word.toLowerCase(ROOT)).collect(toSet());
            terms = terms.stream().filter(term -> term.quoted() || !ignored.contains(term.text().toLowerCase(ROOT))).toList();
        }
        var texts = new ArrayList<String>(terms.size());
        var words = new ArrayList<PredicateNode>(terms.size());
        for (SearchTerms.Term term : terms) {
            var matches = new ArrayList<PredicateNode>(fields.length);
            for (Match<?> field : fields) {
                matches.add(match(field, term.text()));
            }
            texts.add(term.text());
            // Each term is matched on its own, so on a semi-joined table the terms may match different related rows.
            words.add(PredicateNode.or(matches));
        }
        return new PredicateBuilderImpl<>(new PredicateNode.Search(texts, words, suggest));
    }

    /**
     * Returns the predicate matching a term in a field, in the field's form: anywhere in the value, at the start of a
     * word, which is the value's start or the text after a {@link MatchForm#WORD_SEPARATORS separator}, as a prefix
     * of the value with the column bare and the term lowered, or as the whole value, bare and lowered alike.
     */
    private static PredicateNode match(Match<?> field, String term) {
        var path = field.path();
        var form = MatchForm.of(field);
        return switch (form) {
            case ANYWHERE -> new PredicateNode.Term(wrap(ObjectExpression.search(path, CONTAINS_IGNORE_CASE, term, form)), true);
            case WORD_START -> {
                var starts = new ArrayList<PredicateNode>(MatchForm.WORD_SEPARATORS.length() + 1);
                starts.add(new PredicateNode.Term(wrap(ObjectExpression.search(path, STARTS_WITH_IGNORE_CASE, term, form)), true));
                // A separator before the term is literal text in the pattern.
                for (char separator : MatchForm.WORD_SEPARATORS.toCharArray()) {
                    starts.add(new PredicateNode.Term(wrap(ObjectExpression.search(path, CONTAINS_IGNORE_CASE, separator + term, form)), true));
                }
                yield PredicateNode.or(starts);
            }
            // The column stays bare, for an ordinary index over it, and the term is lowered here: the column is one
            // that compares case-insensitively on its own, a stored lowered column included.
            case PREFIX -> new PredicateNode.Term(wrap(ObjectExpression.search(path, STARTS_WITH, term.toLowerCase(ROOT), form)), true);
            case EXACT -> new PredicateNode.Term(wrap(ObjectExpression.search(path, EQUALS, term.toLowerCase(ROOT), form)), true);
        };
    }
}
