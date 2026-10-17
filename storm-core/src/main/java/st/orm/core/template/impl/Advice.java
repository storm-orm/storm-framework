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

/**
 * The advice a statement can carry to the slow statement log, each with the anchor of its section on the advice page.
 * The page keeps an anchor for as long as Storm emits the advice, so the link in a log line outlives any change to the
 * rest of the documentation.
 *
 * @since 1.15
 */
enum Advice {

    /** A search whose terms are all shorter than the substring index serves. */
    UNINDEXED_SEARCH("unindexed-search",
            "every search term is shorter than the %d characters a substring index serves, so the search reads every "
                    + "row; a search box that queries as the user types wants suggest, which matches no rows for such "
                    + "input."),

    /** A distinct select over a join that repeats its rows. */
    DISTINCT_OVER_JOIN("distinct-over-join",
            "DISTINCT removes the rows the join to %1$s repeats, one per related row; semiJoin(%1$s) filters by it "
                    + "without repeating rows, so the query needs no DISTINCT."),

    /** The count query behind a page. */
    PAGE_COUNT("page-count",
            "page counts every matching row to report the total; slice reads the same page without the count, and "
                    + "says whether a next page exists."),

    /** An offset of at least {@link #DEEP_OFFSET_ROWS} rows. */
    DEEP_OFFSET("deep-offset",
            "the database reads and discards the %d rows the offset skips before it returns the first; scroll "
                    + "continues from the last row read, at the same cost for every window.");

    /** The offset from which a statement carries {@link #DEEP_OFFSET}. */
    static final int DEEP_OFFSET_ROWS = 1_000;

    /** The page that documents every piece of advice, one section per anchor. */
    static final String PAGE = "https://orm.st/docs/advice";

    private final String anchor;
    private final String text;

    Advice(String anchor, String text) {
        this.anchor = anchor;
        this.text = text;
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
        return text.formatted(args) + " See " + PAGE + "#" + anchor;
    }
}
