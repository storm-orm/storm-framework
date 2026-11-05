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
package st.orm;

import static java.util.Objects.requireNonNull;

/**
 * A text column of a {@code search} or {@code suggest} with the form a typed word matches it in. A word matches
 * anywhere in the value, at the start of any word in it, as a prefix of the value, or the whole value; every form
 * compares ignoring case, and each renders the same SQL on every database. The default form of {@code search} is
 * {@link #anywhere(Navigable) anywhere}, that of {@code suggest} is {@link #wordStart(Navigable) wordStart}; a call
 * that states the form states it for every column.
 *
 * <p>{@link #prefix(Navigable) prefix} and {@link #exact(Navigable) exact} leave the column bare and lower the word,
 * so an ordinary index over the column serves them; the column is then one that compares case-insensitively on its
 * own, through its collation or as a stored lowered column.</p>
 *
 * <pre>{@code
 * import static st.orm.Match.*;
 *
 * owners.select()
 *         .where(it -> it.suggest(input, wordStart(Owner_.lastName), wordStart(Owner_.firstName)))
 *         .limit(8)
 *         .getResultList();
 * }</pre>
 *
 * <p>A match composes with a {@link SqlFunction}: {@code wordStart(foldAccents.apply(Owner_.lastName))} matches the
 * folded name. The match is not a path, so only {@code search} and {@code suggest} take it.</p>
 *
 * @param <T> the root table type of the column.
 * @since 1.16
 */
public sealed interface Match<T extends Data> {

    /**
     * A word matches anywhere in the value: {@code LOWER(column) LIKE LOWER('%word%')}.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     */
    record Anywhere<T extends Data>(Metamodel<T, String> path) implements Match<T> {
        public Anywhere {
            requireNonNull(path, "path");
        }
    }

    /**
     * A word matches at the start of any word in the value: the value's start, or the text after a space or one of
     * the punctuation characters that join or separate the words of a name, a hyphen, an apostrophe, an
     * opening parenthesis, a slash, a colon, a period or a comma, so {@code marie} matches "Anne-Marie" and
     * {@code brien} "O'Brien". It renders as {@code LOWER(column) LIKE LOWER('word%')} and one
     * {@code LOWER(column) LIKE LOWER('% word%')} per separator, the same on every database.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     */
    record WordStart<T extends Data>(Metamodel<T, String> path) implements Match<T> {
        public WordStart {
            requireNonNull(path, "path");
        }
    }

    /**
     * A word matches at the start of the value, the column bare and the word lowered: {@code column LIKE 'word%'}.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     */
    record Prefix<T extends Data>(Metamodel<T, String> path) implements Match<T> {
        public Prefix {
            requireNonNull(path, "path");
        }
    }

    /**
     * A word matches the whole value, the column bare and the word lowered: {@code column = 'word'}.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     */
    record Exact<T extends Data>(Metamodel<T, String> path) implements Match<T> {
        public Exact {
            requireNonNull(path, "path");
        }
    }

    /**
     * Returns the text column, as a path.
     *
     * @return the column.
     */
    Metamodel<T, String> path();

    /**
     * Returns a match of a word anywhere in the value, the default of {@code search}.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> anywhere(Navigable<T, String> path) {
        return new Anywhere<>(path.asMetamodel());
    }

    /**
     * Returns a match of a word at the start of any word in the value, the default of {@code suggest}: the value's
     * start, or the text after a space or a punctuation character that joins or separates words. It does not match
     * inside a compound, so {@code son} does not find "Davidson"; {@link #anywhere(Navigable) anywhere} does.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> wordStart(Navigable<T, String> path) {
        return new WordStart<>(path.asMetamodel());
    }

    /**
     * Returns a match of a word as a prefix of the value, with the column bare and the word lowered, which an
     * ordinary index over a column that compares case-insensitively serves.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> prefix(Navigable<T, String> path) {
        return new Prefix<>(path.asMetamodel());
    }

    /**
     * Returns a match of a word as the whole value, with the column bare and the word lowered, which an ordinary
     * index over a column that compares case-insensitively serves.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> exact(Navigable<T, String> path) {
        return new Exact<>(path.asMetamodel());
    }
}
