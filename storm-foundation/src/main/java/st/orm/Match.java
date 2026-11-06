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

import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/**
 * A text column of a {@code search} or {@code suggest} with the form a typed word matches it in. A word matches
 * anywhere in the value, at the start of any word in it, as a prefix of the value, or the whole value; every form
 * compares ignoring case, and each renders the same SQL on every database. The default form of {@code search} is
 * {@link #anywhere(Navigable) anywhere}, that of {@code suggest} is {@link #wordStart(Navigable) wordStart}; a call
 * that states the form states it for every column.
 *
 * <p>{@link #prefix(Navigable) prefix} and {@link #exact(Navigable) exact} leave the column bare and lower the word,
 * so an ordinary index over the column serves them; the column is then one that compares case-insensitively on its
 * own, through its collation or as a stored lowered column. {@link #prefixIgnoreCase(Navigable) prefixIgnoreCase} and
 * {@link #exactIgnoreCase(Navigable) exactIgnoreCase} lower the column too, on any column, served by an index over
 * {@code LOWER(column)}, as a match anywhere or at a word start is.</p>
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
 * <p>A match states the shortest word by which its column takes the words of the input in its form, its
 * {@link #minLength() minLength}. A match anywhere or at a word start takes words of
 * {@value #DEFAULT_MIN_LENGTH} characters by default, since a shorter word matches too large a share of a table there,
 * and until a word of the input reaches the length, the column takes the whole input in its
 * {@link #fallback() fallback}, the start of the value, ignoring case. A match at the start of the value or of the
 * whole value is narrow on its own, so it takes words of {@value #DEFAULT_VALUE_MIN_LENGTH} characters by default,
 * without a fallback. {@link #withFallback(BiFunction)} states another form on the same column,
 * {@link #withFallback(Match)} a match on another column, and {@link #withoutFallback()} keeps the column out of the
 * statement until a word reaches the length, as for a column of a related table whose matches rank low.</p>
 *
 * <p>A match composes with a {@link SqlFunction}: {@code wordStart(foldAccents.apply(Owner_.lastName))} matches the
 * folded name. The match is not a path, so only {@code search} and {@code suggest} take it.</p>
 *
 * @param <T> the root table type of the column.
 * @since 1.16
 */
public sealed interface Match<T extends Data> {

    /**
     * The shortest word, in characters, by which a match anywhere or at a word start takes the words in its form by
     * default.
     */
    int DEFAULT_MIN_LENGTH = 3;

    /**
     * The shortest word, in characters, a match at the start of the value or of the whole value takes by default, and
     * the shortest input the default fallback takes: a one-character input matches too many rows.
     */
    int DEFAULT_VALUE_MIN_LENGTH = 2;

    /**
     * A word matches anywhere in the value: {@code LOWER(column) LIKE LOWER('%word%')}.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, by which the column takes the words in its form.
     * @param fallback the match of the whole input while no word reaches the length, or {@code null} for none; its
     *                 own fallback takes no part.
     * @param <T> the root table type of the column.
     */
    record Anywhere<T extends Data>(Metamodel<T, String> path, int minLength, @Nullable Match<T> fallback)
            implements Match<T> {
        public Anywhere {
            requireNonNull(path, "path");
            requireMinLength(minLength);
            fallback = leaf(fallback);
        }

        /**
         * A match with the default fallback, the start of the value, ignoring case.
         *
         * @param path the text column.
         * @param minLength the shortest word, in characters, by which the column takes the words in its form.
         */
        public Anywhere(Metamodel<T, String> path, int minLength) {
            this(path, minLength, new Prefix<>(path, DEFAULT_VALUE_MIN_LENGTH, true));
        }

        /**
         * A match of the default length, {@value #DEFAULT_MIN_LENGTH} characters, with the default fallback.
         *
         * @param path the text column.
         */
        public Anywhere(Metamodel<T, String> path) {
            this(path, DEFAULT_MIN_LENGTH);
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
     * @param minLength the shortest word, in characters, by which the column takes the words in its form.
     * @param fallback the match of the whole input while no word reaches the length, or {@code null} for none; its
     *                 own fallback takes no part.
     * @param <T> the root table type of the column.
     */
    record WordStart<T extends Data>(Metamodel<T, String> path, int minLength, @Nullable Match<T> fallback)
            implements Match<T> {
        public WordStart {
            requireNonNull(path, "path");
            requireMinLength(minLength);
            fallback = leaf(fallback);
        }

        /**
         * A match with the default fallback, the start of the value, ignoring case.
         *
         * @param path the text column.
         * @param minLength the shortest word, in characters, by which the column takes the words in its form.
         */
        public WordStart(Metamodel<T, String> path, int minLength) {
            this(path, minLength, new Prefix<>(path, DEFAULT_VALUE_MIN_LENGTH, true));
        }

        /**
         * A match of the default length, {@value #DEFAULT_MIN_LENGTH} characters, with the default fallback.
         *
         * @param path the text column.
         */
        public WordStart(Metamodel<T, String> path) {
            this(path, DEFAULT_MIN_LENGTH);
        }
    }

    /**
     * A word matches at the start of the value. With the column bare and the word lowered, {@code column LIKE 'word%'},
     * an ordinary index over the column serves it, and the column compares case-insensitively on its own, through its
     * collation or as a stored lowered column. Ignoring case, {@code LOWER(column) LIKE LOWER('word%')}, the column is
     * lowered too, which an index over {@code LOWER(column)} serves, the index of a match anywhere or at a word start
     * included.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, by which the column takes the words in its form.
     * @param ignoreCase {@code true} to lower the column as well as the word.
     * @param fallback the match of the whole input while no word reaches the length, or {@code null} for none; its
     *                 own fallback takes no part.
     * @param <T> the root table type of the column.
     */
    record Prefix<T extends Data>(Metamodel<T, String> path, int minLength, boolean ignoreCase,
                                @Nullable Match<T> fallback) implements Match<T> {
        public Prefix {
            requireNonNull(path, "path");
            requireMinLength(minLength);
            fallback = leaf(fallback);
        }

        /**
         * A match without a fallback, its own form being narrow.
         *
         * @param path the text column.
         * @param minLength the shortest word, in characters, by which the column takes the words in its form.
         * @param ignoreCase {@code true} to lower the column as well as the word.
         */
        public Prefix(Metamodel<T, String> path, int minLength, boolean ignoreCase) {
            this(path, minLength, ignoreCase, null);
        }

        /**
         * A match on the bare column without a fallback.
         *
         * @param path the text column.
         * @param minLength the shortest word, in characters, by which the column takes the words in its form.
         */
        public Prefix(Metamodel<T, String> path, int minLength) {
            this(path, minLength, false);
        }

        /**
         * A match on the bare column of the default length, {@value #DEFAULT_VALUE_MIN_LENGTH} characters, without
         * a fallback.
         *
         * @param path the text column.
         */
        public Prefix(Metamodel<T, String> path) {
            this(path, DEFAULT_VALUE_MIN_LENGTH);
        }
    }

    /**
     * A word matches the whole value. With the column bare and the word lowered, {@code column = 'word'}, an ordinary
     * index over the column serves it, and the column compares case-insensitively on its own, through its collation or
     * as a stored lowered column. Ignoring case, {@code LOWER(column) = LOWER('word')}, the column is lowered too,
     * which an index over {@code LOWER(column)} serves, the index of a match anywhere or at a word start included.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, by which the column takes the words in its form.
     * @param ignoreCase {@code true} to lower the column as well as the word.
     * @param fallback the match of the whole input while no word reaches the length, or {@code null} for none; its
     *                 own fallback takes no part.
     * @param <T> the root table type of the column.
     */
    record Exact<T extends Data>(Metamodel<T, String> path, int minLength, boolean ignoreCase,
                                @Nullable Match<T> fallback) implements Match<T> {
        public Exact {
            requireNonNull(path, "path");
            requireMinLength(minLength);
            fallback = leaf(fallback);
        }

        /**
         * A match without a fallback, its own form being narrow.
         *
         * @param path the text column.
         * @param minLength the shortest word, in characters, by which the column takes the words in its form.
         * @param ignoreCase {@code true} to lower the column as well as the word.
         */
        public Exact(Metamodel<T, String> path, int minLength, boolean ignoreCase) {
            this(path, minLength, ignoreCase, null);
        }

        /**
         * A match on the bare column without a fallback.
         *
         * @param path the text column.
         * @param minLength the shortest word, in characters, by which the column takes the words in its form.
         */
        public Exact(Metamodel<T, String> path, int minLength) {
            this(path, minLength, false);
        }

        /**
         * A match on the bare column of the default length, {@value #DEFAULT_VALUE_MIN_LENGTH} characters, without
         * a fallback.
         *
         * @param path the text column.
         */
        public Exact(Metamodel<T, String> path) {
            this(path, DEFAULT_VALUE_MIN_LENGTH);
        }
    }

    /**
     * Returns the text column, as a path.
     *
     * @return the column.
     */
    Metamodel<T, String> path();

    /**
     * Returns the shortest word, in characters, by which the column takes the words of the input in its form. Once a
     * word reaches it, the column takes every word, a shorter one tested only on the rows the longer word found; until
     * then, the column takes the whole input in its {@link #fallback() fallback}, or takes no part in the statement,
     * its related table included, and a semi-join declared for it with its condition too. {@code 0} takes every word
     * in its form.
     *
     * @return the length.
     */
    int minLength();

    /**
     * Returns how the column matches the whole input, its words joined by one space, while no word reaches the
     * match's {@link #minLength()}, or {@code null} where the column then takes no part. The fallback's own
     * {@link #minLength()} applies to the whole input, and its own fallback takes no part.
     *
     * <p>The default of a match anywhere or at a word start is the start of the value, ignoring case,
     * {@link #prefixIgnoreCase(Navigable) prefixIgnoreCase} on the same column, taking an input of
     * {@value #DEFAULT_VALUE_MIN_LENGTH} characters or more: a short word matches too large a share of a table inside
     * words or at their starts, and the index that serves the match serves its fallback. A match at the start of the
     * value or of the whole value has none, being narrow on its own.</p>
     *
     * @return the fallback, or {@code null}.
     */
    @Nullable Match<T> fallback();

    /**
     * Returns this match with a fallback in another form on the same column, such as
     * {@code wordStart(Owner_.lastName).withFallback(Match::wordStart)}, which matches a short input at every word
     * start. The factory receives the column and the shortest input the default fallback takes,
     * {@value #DEFAULT_VALUE_MIN_LENGTH} characters.
     *
     * @param form the factory of the fallback, such as {@code Match::wordStart}.
     * @return the match.
     */
    default Match<T> withFallback(BiFunction<? super Navigable<T, String>, Integer, ? extends Match<T>> form) {
        requireNonNull(form, "form");
        return withFallback(requireNonNull(form.apply(path(), DEFAULT_VALUE_MIN_LENGTH), "fallback"));
    }

    /**
     * Returns this match with a fallback on any column of the row, such as a {@link #prefix(Navigable) prefix} match
     * on a stored lowered column. The fallback takes an input of its own {@link #minLength()}.
     *
     * @param fallback the match of the whole input; its own fallback takes no part.
     * @return the match.
     */
    default Match<T> withFallback(Match<T> fallback) {
        return withFallbackOrNone(this, requireNonNull(fallback, "fallback"));
    }

    /**
     * Returns this match without a fallback: the column takes no part in the statement until a word reaches its
     * {@link #minLength()}, its related table and a semi-join declared for it included, so a column of a related table
     * whose matches rank low is not read for a short input.
     *
     * @return the match.
     */
    default Match<T> withoutFallback() {
        return withFallbackOrNone(this, null);
    }

    private static <T extends Data> Match<T> withFallbackOrNone(Match<T> match, @Nullable Match<T> fallback) {
        return switch (match) {
            case Anywhere<T> it -> new Anywhere<>(it.path(), it.minLength(), fallback);
            case WordStart<T> it -> new WordStart<>(it.path(), it.minLength(), fallback);
            case Prefix<T> it -> new Prefix<>(it.path(), it.minLength(), it.ignoreCase(), fallback);
            case Exact<T> it -> new Exact<>(it.path(), it.minLength(), it.ignoreCase(), fallback);
        };
    }

    private static void requireMinLength(int minLength) {
        if (minLength < 0) {
            throw new IllegalArgumentException("minLength must not be negative: %d.".formatted(minLength));
        }
    }

    /**
     * Returns the fallback without a fallback of its own, which takes no part.
     */
    private static <T extends Data> @Nullable Match<T> leaf(@Nullable Match<T> fallback) {
        return fallback == null || fallback.fallback() == null ? fallback : withFallbackOrNone(fallback, null);
    }

    /**
     * Returns a match of a word anywhere in the value, the default of {@code search}. It takes the words in its form
     * once one reaches {@value #DEFAULT_MIN_LENGTH} characters, and the whole input as the start of the value, ignoring
     * case, before.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> anywhere(Navigable<T, String> path) {
        return new Anywhere<>(path.asMetamodel());
    }

    /**
     * Returns a {@link #anywhere(Navigable) anywhere} match that takes the words in its form once one reaches the given
     * length, and the whole input as the start of the value, ignoring case, before: a longer length keeps a column
     * whose matches rank low, such as one of a related table, in its fallback until a word is long enough to match
     * few rows, and {@code 0} takes every word in its form.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, by which the column takes the words in its form.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> anywhere(Navigable<T, String> path, int minLength) {
        return new Anywhere<>(path.asMetamodel(), minLength);
    }

    /**
     * Returns a match of a word at the start of any word in the value, the default of {@code suggest}: the value's
     * start, or the text after a space or a punctuation character that joins or separates words. It does not match
     * inside a compound, so {@code son} does not find "Davidson"; {@link #anywhere(Navigable) anywhere} does. It takes
     * the words in its form once one reaches {@value #DEFAULT_MIN_LENGTH} characters, and the whole input as the start
     * of the value, ignoring case, before.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> wordStart(Navigable<T, String> path) {
        return new WordStart<>(path.asMetamodel());
    }

    /**
     * Returns a {@link #wordStart(Navigable) wordStart} match that takes the words in its form once one reaches the
     * given length, and the whole input as the start of the value, ignoring case, before: a longer length keeps a
     * column whose matches rank low, such as one of a related table, in its fallback until a word is long enough to
     * match few rows, and {@code 0} takes every word in its form.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, by which the column takes the words in its form.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> wordStart(Navigable<T, String> path, int minLength) {
        return new WordStart<>(path.asMetamodel(), minLength);
    }

    /**
     * Returns a match of a word as a prefix of the value, with the column bare and the word lowered, which an ordinary
     * index over a column that compares case-insensitively serves. It takes words of {@value #DEFAULT_VALUE_MIN_LENGTH}
     * characters or more.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> prefix(Navigable<T, String> path) {
        return new Prefix<>(path.asMetamodel());
    }

    /**
     * Returns a {@link #prefix(Navigable) prefix} match that takes words of at least the given length.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, the column takes.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> prefix(Navigable<T, String> path, int minLength) {
        return new Prefix<>(path.asMetamodel(), minLength);
    }

    /**
     * Returns a match of a word as a prefix of the value, ignoring case: the column lowered as well as the word, on any
     * column, which an index over {@code LOWER(column)} serves, the index of a match anywhere or at a word start
     * included. It takes words of {@value #DEFAULT_VALUE_MIN_LENGTH} characters or more.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> prefixIgnoreCase(Navigable<T, String> path) {
        return new Prefix<>(path.asMetamodel(), DEFAULT_VALUE_MIN_LENGTH, true);
    }

    /**
     * Returns a {@link #prefixIgnoreCase(Navigable) prefixIgnoreCase} match that takes words of at least the given
     * length.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, the column takes.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> prefixIgnoreCase(Navigable<T, String> path, int minLength) {
        return new Prefix<>(path.asMetamodel(), minLength, true);
    }

    /**
     * Returns a match of a word as the whole value, with the column bare and the word lowered, which an ordinary index
     * over a column that compares case-insensitively serves. It takes words of {@value #DEFAULT_VALUE_MIN_LENGTH}
     * characters or more.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> exact(Navigable<T, String> path) {
        return new Exact<>(path.asMetamodel());
    }

    /**
     * Returns a {@link #exact(Navigable) exact} match that takes words of at least the given length.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, the column takes.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> exact(Navigable<T, String> path, int minLength) {
        return new Exact<>(path.asMetamodel(), minLength);
    }

    /**
     * Returns a match of a word as the whole value, ignoring case: the column lowered as well as the word, on any
     * column, which an index over {@code LOWER(column)} serves, the index of a match anywhere or at a word start
     * included. It takes words of {@value #DEFAULT_VALUE_MIN_LENGTH} characters or more.
     *
     * @param path the text column.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> exactIgnoreCase(Navigable<T, String> path) {
        return new Exact<>(path.asMetamodel(), DEFAULT_VALUE_MIN_LENGTH, true);
    }

    /**
     * Returns a {@link #exactIgnoreCase(Navigable) exactIgnoreCase} match that takes words of at least the given
     * length.
     *
     * @param path the text column.
     * @param minLength the shortest word, in characters, the column takes.
     * @param <T> the root table type of the column.
     * @return the match.
     */
    static <T extends Data> Match<T> exactIgnoreCase(Navigable<T, String> path, int minLength) {
        return new Exact<>(path.asMetamodel(), minLength, true);
    }
}
