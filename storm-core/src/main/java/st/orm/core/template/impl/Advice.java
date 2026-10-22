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
 * The advice a statement can carry, each with the anchor of its section on the advice page and its category. The slow
 * statement log prints every piece of advice a slow statement carries. The advice log reports advice on correctness,
 * such as rows returned in no stated order, and on design, such as an entity whose read exceeds the design budget, by
 * default, and advice on performance once the application asks for its category; advice on a deliberate choice, such as
 * a page that reports its total, holds only once the statement is slow, so only the slow statement log reports it, as
 * it reports advice that needs a slow execution as evidence, such as an index the statement lacks, or one the
 * database's check of a foreign key lacks when the statement deletes. The advice on what repeated in one unit of work
 * closes the performance log's summary. The page keeps an anchor for as long as Storm emits the advice, so the link in
 * a log line outlives any change to the rest of the documentation.
 *
 * @since 1.16
 */
enum Advice {

    /** A search whose terms are all shorter than the substring index serves. */
    UNINDEXED_SEARCH("unindexed-search", Category.PERFORMANCE, false,
            "every search term is shorter than the %d characters a substring index serves, so the search reads every "
                    + "row; a search box that queries as the user types wants suggest, which matches no rows for such "
                    + "input."),

    /** A distinct select over a join that repeats its rows. */
    DISTINCT_OVER_JOIN("distinct-over-join", Category.PERFORMANCE, false,
            "DISTINCT removes the rows the join to %1$s repeats, one per related row; semiJoin(%1$s) filters by it "
                    + "without repeating rows, so the query needs no DISTINCT."),

    /**
     * A table none of whose indexes starts with a column the statement filters or sorts it by. Without row counts an
     * index cannot be judged worth its cost in advance, so the advice waits for a slow execution to say it is.
     */
    MISSING_INDEX("missing-index", Category.PERFORMANCE, true,
            "no index on %1$s starts with %2$s, %3$s; an index on %4$s %5$s."),

    /**
     * A delete from a table that a foreign key references, with no index on the referencing table to serve the check
     * the database makes for every row it deletes. The statement does not name the referencing table, so only the
     * loaded schema shows the cost, and a slow execution that it matters.
     */
    UNINDEXED_FOREIGN_KEY("unindexed-foreign-key", Category.PERFORMANCE, true,
            "deleting from %1$s checks the foreign key %2$s(%3$s) that references it, and no index on %2$s starts "
                    + "with %4$s, so the database reads %2$s in full for every row it deletes; an index on %2$s(%3$s) "
                    + "serves the check."),

    /**
     * A transaction that held its connection, and after a write its row locks, while the application did other work
     * between its statements. Every statement may be fast, so only the time the connection was held shows it.
     */
    IDLE_TRANSACTION("idle-transaction", Category.PERFORMANCE, true,
            "the transaction held its connection %1$s and its statements spent %2$s of it; for %3$s between them it "
                    + "held the connection%4$s while the application did other work; move that work before the "
                    + "transaction or after it commits."),

    /** The count query behind a page, a deliberate choice of page over slice, so advised only when slow. */
    PAGE_COUNT("page-count", Category.PERFORMANCE, true,
            "page counts every matching row to report the total; slice reads the same page without the count, and "
                    + "says whether a next page exists."),

    /** A limit or offset without an ORDER BY. */
    UNORDERED_LIMIT("unordered-limit", Category.CORRECTNESS, false,
            "the statement takes part of the result without an ORDER BY, so which rows it returns is up to the "
                    + "database and can change between executions, and consecutive pages can repeat or skip rows; "
                    + "order by a unique key, or by sort fields that end in one."),

    /** An offset of at least {@link #DEEP_OFFSET_ROWS} rows. */
    DEEP_OFFSET("deep-offset", Category.PERFORMANCE, false,
            "the database reads and discards the %d rows the offset skips before it returns the first; scroll "
                    + "continues from the last row read, at the same cost for every window."),

    /**
     * An {@code IN} list rendered with a placeholder per value in {@link #IN_LIST_LENGTHS} distinct lengths, each a
     * statement of its own to the database, where padding would render them as a few.
     */
    IN_LIST_SHAPES("in-list-shapes", Category.PERFORMANCE, false,
            "the statement runs with varying lengths of its IN list, and each length is a statement of its own to "
                    + "the database and its plan cache; storm.in_list=padded pads a list to the next power of two by "
                    + "repeating its last value, which changes no result and renders every length as one of a few "
                    + "statements."),

    /** References of one type fetched one statement at a time, in one unit of work. */
    REPEATED_FETCH("repeated-fetch", Category.DESIGN, false,
            "%1$d statements each fetched one %2$s; an inline foreign key joins it into every read of the rows, or "
                    + "a query that fetches the references, such as select().fetch(...), resolves them in the "
                    + "statement that reads them."),

    /** An entity whose read exceeds the design budget. */
    ENTITY_BUDGET("entity-budget", Category.DESIGN, false,
            "every read of %1$s joins %2$d tables and selects %3$d columns beyond its own, past the design budget of "
                    + "%5$d joins and %6$d columns; %4$s."),

    /** A slow read of an entity whose read is at least half the design budget. */
    WIDE_ENTITY_READ("wide-entity-read", Category.DESIGN, true,
            "every read of %1$s joins %2$d tables and selects %3$d columns beyond its own; where that width is what "
                    + "makes the read slow, %4$s."),

    /** The same single-row read repeated, in one unit of work. */
    REPEATED_SELECT("repeated-select", Category.PERFORMANCE, false,
            "the same SELECT of %2$s ran %1$d times, one row each; one query for all of them, such as findAllById "
                    + "or a where with IN, reads the rows in one round trip."),

    /** The same single-row write repeated without a batch, in one unit of work. */
    REPEATED_WRITE("repeated-write", Category.PERFORMANCE, false,
            "the same %1$s of %2$s ran %3$d times, one row each; the overloads that take a list send the rows as "
                    + "one batch."),

    /**
     * A write outside any transaction to a type that references a type an earlier write outside any transaction
     * wrote, in one unit of work: each committed on its own, so the first stays when the second fails.
     */
    WRITES_WITHOUT_TRANSACTION("writes-without-transaction", Category.CORRECTNESS, false,
            "the unit of work wrote %1$s and then %2$s, which references %1$s, each in a transaction of its own; "
                    + "when a later write fails, the earlier ones stay; run them in one transaction.");

    /** The executions in one unit of work from which a repeated statement or fetch carries advice. */
    static final int REPEAT_THRESHOLD = 10;

    /** The offset from which a statement carries {@link #DEEP_OFFSET}. */
    static final int DEEP_OFFSET_ROWS = 1_000;

    /**
     * The distinct lengths of an {@code IN} list from which a statement shape carries {@link #IN_LIST_SHAPES}: enough
     * to show that the length follows the input rather than a fixed set.
     */
    static final int IN_LIST_LENGTHS = 8;

    /** The page that documents every piece of advice, one section per anchor. */
    static final String PAGE = "https://orm.st/docs/advice";

    /** What a piece of advice is about. */
    enum Category {

        /** The statement can return other rows than intended, however fast it runs. */
        CORRECTNESS,

        /** The statement costs more than it needs to as the data grows. */
        PERFORMANCE,

        /** The entity model makes reads cost more than the application needs, a choice of {@code Ref} or entity. */
        DESIGN
    }

    private final String anchor;
    private final Category category;
    private final boolean slowOnly;
    private final String text;
    private final String link;

    Advice(String anchor, Category category, boolean slowOnly, String text) {
        this.anchor = anchor;
        this.category = category;
        this.slowOnly = slowOnly;
        this.text = text;
        this.link = " See " + PAGE + "#" + anchor;
    }

    /**
     * Returns what the advice is about.
     *
     * @return the category.
     */
    Category category() {
        return category;
    }

    /**
     * Returns whether the advice holds only once the statement is slow, as for a deliberate choice of API, so only the
     * slow statement log reports it.
     *
     * @return {@code true} for advice the advice log never reports.
     */
    boolean slowOnly() {
        return slowOnly;
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
