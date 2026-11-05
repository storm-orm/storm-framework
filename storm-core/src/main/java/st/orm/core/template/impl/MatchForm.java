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

import java.util.EnumSet;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import st.orm.Match;

/**
 * The form a word of a {@code search} or {@code suggest} matches a field in, as the {@link Match} records state it,
 * named as the API names it. A statement records the form per field, and the search advice compares it with the forms
 * the database's index serves.
 *
 * @since 1.16
 */
public enum MatchForm {

    /** Anywhere in the value, on the lowered column. */
    ANYWHERE("anywhere"),

    /** At the start of a word in the value, the value's start or the text after a separator, on the lowered column. */
    WORD_START("wordStart"),

    /** As a prefix of the value, on the bare column with the word lowered. */
    PREFIX("prefix"),

    /** As the whole value, on the bare column with the word lowered. */
    EXACT("exact");

    /** The forms an ordinary index serves: one that orders its values from their first character. */
    static final Set<MatchForm> ORDINARY = EnumSet.of(PREFIX, EXACT);

    /**
     * The characters a word follows in a value, besides the value's start: a space, and the punctuation that joins
     * or separates the words of a name or a title, so that {@code man} matches "Spider-Man", {@code connor}
     * "O'Connor" and {@code off} "Face/Off". The set is the same on every database, so a word start finds the same
     * rows everywhere; a word-suffix table that stands in for a substring index splits its values on the same set.
     */
    public static final String WORD_SEPARATORS = " -'(/:.,";

    private final String factory;

    MatchForm(String factory) {
        this.factory = factory;
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
     * Returns whether the form compares the lowered column, with a wildcard in front of the word, which only an index
     * for substring matching serves.
     *
     * @return {@code true} for a match anywhere or at a word start.
     */
    boolean lowered() {
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
            case Match.Anywhere<?> ignore -> ANYWHERE;
            case Match.WordStart<?> ignore -> WORD_START;
            case Match.Prefix<?> ignore -> PREFIX;
            case Match.Exact<?> ignore -> EXACT;
        };
    }

    /**
     * Returns the forms a schema read lists for an index key, as the dialect's index query names them: the factory
     * names, separated by commas, or {@code null} for an ordinary index.
     *
     * @param forms the forms as listed, or {@code null}.
     * @return the forms.
     */
    static Set<MatchForm> listed(@Nullable String forms) {
        if (forms == null) {
            return ORDINARY;
        }
        var set = EnumSet.noneOf(MatchForm.class);
        for (String name : forms.split(",")) {
            for (var form : values()) {
                if (form.factory.equalsIgnoreCase(name.strip())) {
                    set.add(form);
                }
            }
        }
        return set;
    }
}
