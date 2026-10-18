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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.template.SqlLog.Statement;
import st.orm.core.template.SqlLog.StatementLine;
import st.orm.core.template.SqlLog.Summary;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Tests the summary a log reader sees: the heaviest statement leads, repetition reads as a multiplier, and the
 * headline separates database time from the time the call took.
 */
public class SqlLogRendererTest {

    private static final String NL = System.lineSeparator();

    private static StatementLine line(String sql, String type, int executions, long millis, long rows) {
        return new StatementLine(sql, type, false, executions, 1, millis * 1_000_000L, 0, null, 0, rows, true);
    }

    @Test
    public void theHeaviestStatementLeadsAndRepetitionReadsAsAMultiplier() {
        String rendered = SqlLogRenderer.render("GET /owners/42", 12, 0, 0, 214, 214, 1, 678,
                List.of(line("SELECT a FROM city", "City", 1, 18, 1), line("SELECT b FROM owner", "Owner", 7, 96, 700)), 0);
        // The heaviest statement leads, whatever order it arrived in, and each row names its target type.
        assertTrue(rendered.startsWith("SQL (GET /owners/42): 12 statements, 214 ms in database, 678 ms total" + NL),
                rendered);
        assertTrue(rendered.indexOf("SELECT b FROM owner") < rendered.indexOf("SELECT a FROM city"), rendered);
        assertTrue(rendered.contains("96 ms  700 rows  7x  Owner  SELECT b FROM owner"), rendered);
        assertTrue(rendered.contains("18 ms    1 rows  1x  City   SELECT a FROM city"), rendered);
    }

    @Test
    public void concurrencyIsReportedOnlyWhenTheWorkOverlapped() {
        String serial = SqlLogRenderer.render("call", 2, 0, 0, 40, 40, 1, 90, List.of(line("SELECT 1", "-", 2, 40, 2)), 0);
        assertFalse(serial.contains("concurrent"), serial);
        String parallel = SqlLogRenderer.render("call", 8, 0, 0, 214, 61, 4, 678, List.of(line("SELECT 1", "-", 8, 214, 8)), 0);
        assertTrue(parallel.contains("214 ms in database over 61 ms elapsed (peak 4 concurrent), 678 ms total"),
                parallel);
    }

    @Test
    public void aFetchIsNamedOnItsOwnLine() {
        String rendered = SqlLogRenderer.render("call", 9, 8, 0, 30, 30, 1, 40,
                List.of(new StatementLine("SELECT c FROM city WHERE id = ?", "City", true, 8, 1, 28_000_000L, 0, null, 0, 8, true)), 0);
        assertTrue(rendered.contains("8 fetches"), rendered);
        assertTrue(rendered.contains("28 ms  8 rows  8x  City  fetch  SELECT c FROM city WHERE id = ?"), rendered);
    }

    @Test
    public void aLongStatementIsElidedOntoOneLine() {
        String rendered = SqlLogRenderer.render("call", 1, 0, 0, 5, 5, 1, 9,
                List.of(line("SELECT " + "x".repeat(200) + "\n  FROM city", "City", 1, 5, 1)), 0);
        assertFalse(rendered.contains("\n  FROM"), rendered);
        assertTrue(rendered.contains("\u2026"), rendered);
        // The tail survives the elision: the FROM clause is what identifies the statement.
        assertTrue(rendered.contains("FROM city"), rendered);
    }

    @Test
    public void aNestedSubqueryClosesUpRatherThanCarryingItsLineBreaks() {
        // A subquery is rendered on lines of its own, which leaves its parentheses opening and closing lines.
        String rendered = SqlLogRenderer.render("call", 1, 0, 0, 7, 7, 1, 9,
                List.of(line("""
                        SELECT SUM(c)
                        FROM ds
                        INNER JOIN (
                          SELECT id
                          FROM df
                          HAVING n = (
                            SELECT MAX(1)
                            FROM df1
                            WHERE id = ?
                          )
                        ) x ON ds.id = x.id""", "DemographicSet", 1, 7, 1)), 0);
        // Both subqueries close where they end, and the breaks that separate words still read as spaces.
        assertTrue(rendered.contains(
                "SELECT SUM(c) FROM ds INNER JOIN (SELECT id FROM df HAVING n = "
                        + "(SELECT MAX(1) FROM df1 WHERE id = ?)) x ON ds.id = x.id"),
                rendered);
    }

    @Test
    public void aGroupCoveringSeveralTextsSaysSo() {
        // A collection parameter that expands per execution yields one group with several texts.
        String rendered = SqlLogRenderer.render("call", 3, 0, 0, 12, 12, 1, 20,
                List.of(new StatementLine("SELECT c FROM city WHERE id IN (?, ?)", "City", false, 3, 3, 12_000_000L, 0, null, 0, 6, true)), 0);
        assertTrue(rendered.contains("IN (?, ?) (3 variants)"), rendered);
    }

    @Test
    public void statementsBeyondTheLimitAreReportedRatherThanDropped() {
        String rendered = SqlLogRenderer.render("call", 500, 0, 0, 90, 90, 1, 120, List.of(line("SELECT 1", "-", 200, 90, 200)), 300);
        assertTrue(rendered.endsWith("(300 statements not recorded)"), rendered);
    }

    @Test
    public void truncatedDatabaseTimesReadAsLowerBounds() {
        // The unrecorded statements contributed no duration, so the summary must not present its own total as
        // the whole cost of the call.
        String truncated = SqlLogRenderer.render("call", 500, 0, 0, 90, 60, 4, 120,
                List.of(line("SELECT 1", "-", 200, 90, 200)), 300);
        assertTrue(truncated.contains("500 statements, 90+ ms in database over 60+ ms elapsed (peak 4 concurrent), 120 ms total"),
                truncated);
        // Nothing was dropped here, so the same numbers are exact and carry no marker.
        String complete = SqlLogRenderer.render("call", 200, 0, 0, 90, 60, 4, 120,
                List.of(line("SELECT 1", "-", 200, 90, 200)), 0);
        assertTrue(complete.contains("200 statements, 90 ms in database over 60 ms elapsed (peak 4 concurrent), 120 ms total"),
                complete);
    }

    @Test
    public void theSlowestExecutionShowsWhenItStandsOutFromTheGroup() {
        // Seven executions totalling 96 ms with one of 60 ms: the total ranks the row, the max tells the reading.
        String outlier = SqlLogRenderer.render("call", 7, 0, 0, 96, 96, 1, 120,
                List.of(new StatementLine("SELECT v FROM visit", "Visit", false, 7, 1, 96_000_000L, 60_000_000L,
                        null, 0, 6408, true)), 0);
        assertTrue(outlier.contains("96 ms  max 60 ms  6408 rows  7x  Visit"), outlier);
        // Two executions of 40 and 56 ms are uniformly slow; the max says nothing the total does not.
        String uniform = SqlLogRenderer.render("call", 2, 0, 0, 96, 96, 1, 120,
                List.of(new StatementLine("SELECT v FROM visit", "Visit", false, 2, 1, 96_000_000L, 56_000_000L,
                        null, 0, 6408, true)), 0);
        assertFalse(uniform.contains("max"), uniform);
        // A single execution is its own max.
        String single = SqlLogRenderer.render("call", 1, 0, 0, 96, 96, 1, 120,
                List.of(new StatementLine("SELECT v FROM visit", "Visit", false, 1, 1, 96_000_000L, 96_000_000L,
                        null, 0, 6408, true)), 0);
        assertFalse(single.contains("max"), single);
    }

    @Test
    public void aCallSiteIsRenderedWhenRecorded() {
        String rendered = SqlLogRenderer.render("call", 9, 0, 0, 30, 30, 1, 40,
                List.of(new StatementLine("SELECT s FROM user_session", "UserSession", false, 8, 1, 28_000_000L,
                        0, "MeController.kt:41", 3, 8, true)), 0);
        assertTrue(rendered.contains("MeController.kt:41 (+2 sites)  SELECT s FROM user_session"), rendered);
    }

    @Test
    public void aPlaceholderRunCollapsesInTheDisplay() {
        String rendered = SqlLogRenderer.render("call", 1, 0, 0, 5, 5, 1, 9,
                List.of(line("INSERT INTO t (a, b) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", "-", 1, 5, 4)), 0);
        assertTrue(rendered.contains("VALUES (?, \u2026, ?)"), rendered);
        // Short runs stay as they are.
        String untouched = SqlLogRenderer.render("call", 1, 0, 0, 5, 5, 1, 9,
                List.of(line("SELECT c FROM city WHERE id IN (?, ?)", "-", 1, 5, 2)), 0);
        assertTrue(untouched.contains("IN (?, ?)"), untouched);
    }

    @Test
    public void cacheServedReadsAppearInTheHeadlineOnlyWhenAnyWere() {
        String rendered = SqlLogRenderer.render("call", 3, 2, 4, 30, 30, 1, 40, List.of(line("SELECT 1", "-", 3, 30, 3)), 0);
        assertTrue(rendered.contains("3 statements, 2 fetches, 4 from cache, 30 ms in database"), rendered);
        String without = SqlLogRenderer.render("call", 3, 0, 0, 30, 30, 1, 40, List.of(line("SELECT 1", "-", 3, 30, 3)), 0);
        assertFalse(without.contains("from cache"), without);
    }

    @Test
    public void anInexactRowCountIsMarkedAsALowerBound() {
        // A batch entry whose count the driver declined to report, or a stream closed before its end, leaves the
        // count a known lower bound; the star says so, and the exact count in the other row stays unmarked.
        String rendered = SqlLogRenderer.render("call", 2, 0, 0, 45, 45, 1, 60, List.of(
                new StatementLine("INSERT INTO city (name) VALUES (?)", "City", false, 1, 1, 40_000_000L, 0, null, 0,
                        500, false),
                line("SELECT c FROM city", "City", 1, 5, 3)), 0);
        assertTrue(rendered.contains("40 ms  500* rows  1x  City  INSERT INTO city (name) VALUES (?)"), rendered);
        // The star participates in the column width, so the exact count aligns under it.
        assertTrue(rendered.contains(" 5 ms     3 rows  1x  City  SELECT c FROM city"), rendered);
    }

    /** A unit of work that ran the statement the given number of times, each producing the given rows. */
    private static Summary repeated(SqlOperation operation, Class<? extends Data> type, StatementOrigin origin,
                                    int executions, long rows, boolean exactRows) {
        var statements = new ArrayList<Statement>();
        for (int i = 0; i < executions; i++) {
            statements.add(new Statement(operation, type, origin, "SQL " + type.getSimpleName(), i, i + 1, i + 1,
                    type.getName().hashCode(), null, rows, exactRows));
        }
        return new Summary("call", statements, executions, 0, executions);
    }

    @Test
    public void repeatedFetchesOfOneTypeCarryAdvice() {
        var summary = repeated(SqlOperation.SELECT, City.class, StatementOrigin.FETCH, 10, 1, true);
        assertEquals(1, SqlLogRenderer.advisories(summary).size());
        String rendered = SqlLogRenderer.render(summary);
        assertTrue(rendered.contains(NL + "\tadvice: 10 statements each fetched one City; an inline foreign key joins it"), rendered);
        assertTrue(rendered.endsWith("See https://orm.st/docs/advice#repeated-fetch"), rendered);
    }

    @Test
    public void repeatedSingleRowReadsAndWritesCarryAdvice() {
        var read = SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true));
        assertEquals(1, read.size(), read.toString());
        assertTrue(read.getFirst().startsWith("the same SELECT of Owner ran 12 times, one row each"), read.getFirst());
        var write = SqlLogRenderer.advisories(repeated(SqlOperation.INSERT, Owner.class, StatementOrigin.DIRECT, 10, 1, true));
        assertEquals(1, write.size(), write.toString());
        assertTrue(write.getFirst().startsWith("the same INSERT of Owner ran 10 times, one row each"), write.getFirst());
    }

    @Test
    public void fewerRepetitionsOrMoreRowsCarryNoAdvice() {
        assertEquals(List.of(), SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, City.class, StatementOrigin.FETCH, 9, 1, true)));
        assertEquals(List.of(), SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 9, 1, true)));
        // A read of several rows a time is not a lookup by key: scrolling window after window repeats it too.
        assertEquals(List.of(), SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 20, 5, true)));
        // A batch affects its rows in one execution; a count the driver did not report proves nothing.
        assertEquals(List.of(), SqlLogRenderer.advisories(repeated(SqlOperation.INSERT, Owner.class, StatementOrigin.DIRECT, 10, 50, true)));
        assertEquals(List.of(), SqlLogRenderer.advisories(repeated(SqlOperation.INSERT, Owner.class, StatementOrigin.DIRECT, 10, 1, false)));
        assertFalse(SqlLogRenderer.render(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 9, 1, true))
                .contains("advice"));
    }
}
