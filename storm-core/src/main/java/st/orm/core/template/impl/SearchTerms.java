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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the terms of a search input, as a search box receives it. Whitespace separates the terms, and a quote at the
 * start of a term opens a phrase that runs to the next quote, or to the end of the input when there is none, so
 * {@code "the good" ugly} holds the terms {@code the good} and {@code ugly}, and {@code "the good} holds
 * {@code the good} while the user is still typing the phrase. A quote inside a term, as in {@code 12"}, is an
 * ordinary character. A phrase is trimmed, an empty phrase holds no term, and a term that occurs twice is read once.
 *
 * @since 1.15
 */
final class SearchTerms {

    /**
     * A term of the input.
     *
     * @param text the term as it matches.
     * @param quoted {@code true} for a phrase the input quoted, which is an explicit request and is never ignored.
     */
    record Term(String text, boolean quoted) {
    }

    private SearchTerms() {
    }

    /**
     * Returns the terms of the input, in the order they occur.
     *
     * @param input the search input.
     * @return the terms, empty for an input that holds none.
     */
    static List<Term> parse(String input) {
        Map<Term, Term> terms = new LinkedHashMap<>();
        int length = input.length();
        int i = 0;
        while (i < length) {
            if (Character.isWhitespace(input.charAt(i))) {
                i++;
                continue;
            }
            if (input.charAt(i) == '"') {
                int close = input.indexOf('"', i + 1);
                int end = close >= 0 ? close : length;
                String phrase = input.substring(i + 1, end).strip();
                if (!phrase.isEmpty()) {
                    var term = new Term(phrase, true);
                    terms.putIfAbsent(term, term);
                }
                i = end + 1;
                continue;
            }
            int end = i;
            while (end < length && !Character.isWhitespace(input.charAt(end))) {
                end++;
            }
            var term = new Term(input.substring(i, end), false);
            terms.putIfAbsent(term, term);
            i = end;
        }
        return new ArrayList<>(terms.values());
    }
}
