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

import static st.orm.Operator.CONTAINS_IGNORE_CASE;
import static st.orm.Operator.EQUALS;
import static st.orm.Operator.EQUALS_IGNORE_CASE;
import static st.orm.Operator.STARTS_WITH;
import static st.orm.Operator.STARTS_WITH_IGNORE_CASE;

import java.util.Locale;
import org.jspecify.annotations.Nullable;
import st.orm.Match;
import st.orm.Match.Anywhere;
import st.orm.Match.Exact;
import st.orm.Match.Prefix;
import st.orm.Match.WordStart;
import st.orm.Operator;

/**
 * The form a word of a {@code search} or {@code suggest} matches a field in, as the {@link Match} records state it,
 * named as the API names it. A statement records the form per field for the search advice.
 *
 * @since 1.16
 */
public enum MatchForm {

    /** Anywhere in the value, on the lowered column. */
    ANYWHERE("anywhere", CONTAINS_IGNORE_CASE, false),

    /**
     * At the start of a word in the value, the value's start or the text after one of the
     * {@link st.orm.core.template.SqlDialect#WORD_SEPARATORS}, on the lowered column, rendered as the dialect renders
     * it: one regular expression where the database matches one, {@code LIKE} patterns elsewhere.
     */
    WORD_START("wordStart", null, false),

    /** As a prefix of the value, on the bare column with the word lowered. */
    PREFIX("prefix", STARTS_WITH, true),

    /** As the whole value, on the bare column with the word lowered. */
    EXACT("exact", EQUALS, true),

    /** As a prefix of the value, on the lowered column. */
    PREFIX_IGNORE_CASE("prefixIgnoreCase", STARTS_WITH_IGNORE_CASE, false),

    /** As the whole value, on the lowered column. */
    EXACT_IGNORE_CASE("exactIgnoreCase", EQUALS_IGNORE_CASE, false);

    private final String factory;
    private final @Nullable Operator operator;
    private final boolean lowersWord;

    MatchForm(String factory, @Nullable Operator operator, boolean lowersWord) {
        this.factory = factory;
        this.operator = operator;
        this.lowersWord = lowersWord;
    }

    /**
     * Returns the operator the form compares with, or {@code null} for a word start, which the dialect renders.
     *
     * @return the operator, or {@code null}.
     */
    public @Nullable Operator operator() {
        return operator;
    }

    /**
     * Returns the text the form compares for a typed word: the word lowered where the form leaves the column bare,
     * the word as typed where the comparison lowers both sides.
     *
     * @param word the typed word.
     * @return the text to compare.
     */
    public String text(String word) {
        return lowersWord ? word.toLowerCase(Locale.ROOT) : word;
    }

    /**
     * Returns the form as the API names it, the name of the factory that states it, such as {@code wordStart}.
     *
     * @return the factory name.
     */
    public String factory() {
        return factory;
    }

    /**
     * Returns whether the form compares the lowered column, which only an index over that expression serves.
     *
     * @return {@code true} for every form but a prefix or exact match on the bare column.
     */
    boolean lowered() {
        return this != PREFIX && this != EXACT;
    }

    /**
     * Returns whether the form puts a wildcard in front of the word, which only an index for substring matching
     * serves.
     *
     * @return {@code true} for a match anywhere or at a word start.
     */
    boolean substring() {
        return this == ANYWHERE || this == WORD_START;
    }

    /**
     * Returns the form of a match.
     *
     * @param match the match.
     * @return its form.
     */
    public static MatchForm of(Match<?> match) {
        return switch (match) {
            case Anywhere<?> ignore -> ANYWHERE;
            case WordStart<?> ignore -> WORD_START;
            case Prefix<?> it -> it.ignoreCase() ? PREFIX_IGNORE_CASE : PREFIX;
            case Exact<?> it -> it.ignoreCase() ? EXACT_IGNORE_CASE : EXACT;
        };
    }

}
