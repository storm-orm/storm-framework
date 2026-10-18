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

import org.jspecify.annotations.Nullable;

/**
 * The advice a statement can carry, each with the anchor of its section on the advice page. The slow statement log
 * prints every piece of advice a slow statement carries. The advice log reports the advice that holds at any speed, such
 * as rows returned in no stated order, from {@code INFO}; advice about cost, such as the count behind a page, matters
 * only once the statement is slow, so the advice log reports it only from {@code DEBUG}. The page keeps an anchor for as long as Storm emits
 * the advice, so the link in a log line outlives any change to the rest of the documentation.
 *
 * @since 1.15
 */
enum Advice {

    /** A search whose terms are all shorter than the substring index serves. */
    UNINDEXED_SEARCH("unindexed-search", false,
            "every search term is shorter than the %d characters a substring index serves, so the search reads every "
                    + "row; a search box that queries as the user types wants suggest, which matches no rows for such "
                    + "input."),

    /** A distinct select over a join that repeats its rows. */
    DISTINCT_OVER_JOIN("distinct-over-join", false,
            "DISTINCT removes the rows the join to %1$s repeats, one per related row; semiJoin(%1$s) filters by it "
                    + "without repeating rows, so the query needs no DISTINCT."),

    /** The count query behind a page. */
    PAGE_COUNT("page-count", false,
            "page counts every matching row to report the total; slice reads the same page without the count, and "
                    + "says whether a next page exists."),

    /** A limit or offset without an ORDER BY. */
    UNORDERED_LIMIT("unordered-limit", true,
            "the statement takes part of the result without an ORDER BY, so which rows it returns is up to the "
                    + "database and can change between executions, and consecutive pages can repeat or skip rows; "
                    + "order by a unique key, or by sort fields that end in one."),

    /** An offset of at least {@link #DEEP_OFFSET_ROWS} rows. */
    DEEP_OFFSET("deep-offset", false,
            "the database reads and discards the %d rows the offset skips before it returns the first; scroll "
                    + "continues from the last row read, at the same cost for every window.");

    /** The offset from which a statement carries {@link #DEEP_OFFSET}. */
    static final int DEEP_OFFSET_ROWS = 1_000;

    /** The page that documents every piece of advice, one section per anchor. */
    static final String PAGE = "https://orm.st/docs/advice";

    private final String anchor;
    private final boolean anySpeed;
    private final String text;
    private final String link;

    Advice(String anchor, boolean anySpeed, String text) {
        this.anchor = anchor;
        this.anySpeed = anySpeed;
        this.text = text;
        this.link = " See " + PAGE + "#" + anchor;
    }

    /**
     * Returns whether the advice holds however fast the statement runs, so the advice log reports it at {@code INFO}.
     * Advice about cost holds only once the statement is slow, so the slow statement log reports it, and the advice log
     * from {@code DEBUG}.
     *
     * @return {@code true} for advice that holds at any speed.
     */
    boolean anySpeed() {
        return anySpeed;
    }

    /**
     * Returns the advice an advisory, as {@link #format(Object...)} rendered it, gives.
     *
     * @param advisory the advisory a statement carries.
     * @return the advice the advisory links to, or {@code null} if it links to none.
     */
    static @Nullable Advice of(String advisory) {
        for (var advice : values()) {
            if (advisory.endsWith(advice.link)) {
                return advice;
            }
        }
        return null;
    }

    /**
     * Returns the anchor of the advice's section on the advice page.
     *
     * @return the anchor.
     */
    String anchor() {
        return anchor;
    }

    /**
     * Returns the advice as a log line reports it: the text with its arguments, followed by the link to its section.
     *
     * @param args the arguments the text is formatted with.
     * @return the advice.
     */
    String format(Object... args) {
        return text.formatted(args) + link;
    }
}
