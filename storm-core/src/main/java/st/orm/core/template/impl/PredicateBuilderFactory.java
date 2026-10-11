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

import java.util.List;
import st.orm.Data;
import st.orm.Metamodel;
import st.orm.Operator;
import st.orm.Ref;
import st.orm.core.template.PredicateBuilder;
import st.orm.core.template.TemplateString;
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
     * a quoted phrase is one term; an input without terms matches every row.
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
        requireNonNull(input, "input");
        if (paths.isEmpty()) {
            throw new IllegalArgumentException("search needs at least one path to search.");
        }
        List<String> terms = SearchTerms.parse(input);
        if (terms.isEmpty()) {
            return new PredicateBuilderImpl<>(TemplateString.of("1 = 1"));
        }
        PredicateBuilder<T, R, ID> all = null;
        for (String term : terms) {
            PredicateBuilder<T, R, ID> any = null;
            for (Metamodel<?, String> path : paths) {
                PredicateBuilder<T, R, ID> match =
                        new PredicateBuilderImpl<>(wrap(new ObjectExpression(path, CONTAINS_IGNORE_CASE, List.of(term))));
                any = any == null ? match : any.or(match);
            }
            all = all == null ? any : all.and(any);
        }
        return all;
    }
}
