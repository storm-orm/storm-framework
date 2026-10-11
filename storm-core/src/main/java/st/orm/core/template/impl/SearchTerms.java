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
import java.util.LinkedHashSet;
import java.util.List;

/**
 * Reads the terms of a search input, as a search box receives it. Whitespace separates the terms, and a quote at the
 * start of a term opens a phrase that runs to the next quote, so {@code "the good" ugly} holds the terms
 * {@code the good} and {@code ugly}. A quote without a closing one, or inside a term, is an ordinary character. A
 * phrase is trimmed, an empty phrase holds no term, and a term that occurs twice is read once.
 *
 * @since 1.15
 */
final class SearchTerms {

    private SearchTerms() {
    }

    /**
     * Returns the terms of the input, in the order they occur.
     *
     * @param input the search input.
     * @return the terms, empty for an input that holds none.
     */
    static List<String> parse(String input) {
        var terms = new LinkedHashSet<String>();
        int length = input.length();
        int i = 0;
        while (i < length) {
            if (Character.isWhitespace(input.charAt(i))) {
                i++;
                continue;
            }
            if (input.charAt(i) == '"') {
                int close = input.indexOf('"', i + 1);
                if (close >= 0) {
                    String phrase = input.substring(i + 1, close).strip();
                    if (!phrase.isEmpty()) {
                        terms.add(phrase);
                    }
                    i = close + 1;
                    continue;
                }
            }
            int end = i;
            while (end < length && !Character.isWhitespace(input.charAt(end))) {
                end++;
            }
            terms.add(input.substring(i, end));
            i = end;
        }
        return new ArrayList<>(terms);
    }
}
