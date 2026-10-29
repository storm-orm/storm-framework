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

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntToLongFunction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.AcceptAdvice;
import st.orm.Data;
import st.orm.Entity;
import st.orm.FK;
import st.orm.PK;
import st.orm.core.model.City;
import st.orm.core.model.Owner;
import st.orm.core.model.Pet;
import st.orm.core.template.SqlLog;
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

    /** The statements of these summaries take milliseconds, under the default floor; the floor is tested apart. */
    @BeforeEach
    public void noFloor() {
        AdviceLog.repeatFloor(null);
    }

    @AfterEach
    public void restore() {
        AdviceLog.reset();
    }

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
    public void timedStatementsPastTheLimitMakeTheDatabaseTimeExactAndSayWhereItWent() {
        var unrecorded = new SqlLog.Unrecorded(5_075_000_000L, List.of(
                new SqlLog.Unrecorded.Group(SqlOperation.INSERT, "Reminder", false, 120, 4_800_000_000L),
                new SqlLog.Unrecorded.Group(SqlOperation.SELECT, "Visit", false, 10, 200_000_000L),
                new SqlLog.Unrecorded.Group(SqlOperation.SELECT, null, true, 4, 50_000_000L),
                new SqlLog.Unrecorded.Group(SqlOperation.DELETE, "Visit", false, 1, 25_000_000L)));
        String rendered = SqlLogRenderer.render("VisitReminderScheduler.run", 335, 0, 2, 6441, 1366, 1, 8390,
                List.of(line("SELECT v FROM visit", "Visit", 1, 841, 28)), 135, unrecorded);
        assertTrue(rendered.startsWith("SQL (VisitReminderScheduler.run): 335 statements, 2 from cache, 6441 ms in "
                + "database, 8390 ms total"), rendered);
        assertTrue(rendered.endsWith(NL + "\t(135 statements not recorded, 5075 ms: 120 INSERT of Reminder 4800 ms, "
                + "10 SELECT of Visit 200 ms, 4 SELECT of other types 50 ms, +1 more)"), rendered);
        // The elapsed time covers the recorded executions alone, so it stays a lower bound.
        String concurrent = SqlLogRenderer.render("call", 30, 0, 0, 900, 120, 4, 300,
                List.of(line("SELECT 1", "-", 8, 214, 8)), 22, unrecorded);
        assertTrue(concurrent.contains(", 900 ms in database over 120+ ms elapsed (peak 4 concurrent)"), concurrent);
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

    /** An execution outside any transaction, for {@link #repeated}. */
    private static final long OUTSIDE = -1;

    /** An execution in a transaction the record does not identify, for {@link #repeated}. */
    private static final long UNKNOWN = 0;

    /**
     * A unit of work that ran the statement the given number of times, each producing the given rows, in
     * transactions it does not identify.
     */
    private static Summary repeated(SqlOperation operation, Class<? extends Data> type, StatementOrigin origin,
                                    int executions, long rows, boolean exactRows) {
        return new Summary("call", repeated(operation, type, origin, executions, rows, exactRows, 1, null,
                execution -> UNKNOWN), executions, 0, executions);
    }

    /**
     * The statements of a unit of work that ran the statement the given number of times, each producing the given
     * rows in the given milliseconds, from the given call site, in the transaction the function names for it: a
     * positive identity, {@link #UNKNOWN} for one the record does not identify, or {@link #OUTSIDE} for none.
     */
    private static List<Statement> repeated(SqlOperation operation, Class<? extends Data> type,
                                            StatementOrigin origin, int executions, long rows, boolean exactRows,
                                            long millis, String callSite, IntToLongFunction transaction) {
        var statements = new ArrayList<Statement>();
        long nanos = millis * 1_000_000L;
        long shapeId = (type.getName() + operation + origin).hashCode();
        for (int i = 0; i < executions; i++) {
            long transactionId = transaction.applyAsLong(i);
            long start = i * nanos;
            statements.add(new Statement(operation, false, type, origin, "SQL " + type.getSimpleName(), start,
                    start + nanos, start + nanos, shapeId, callSite, rows, exactRows, transactionId != OUTSIDE,
                    Math.max(transactionId, 0)));
        }
        return statements;
    }

    /** A unit of work that wrote one row of each type in turn, each write in a transaction or on its own. */
    @SafeVarargs
    private static Summary writes(boolean transactional, Class<? extends Data>... types) {
        var statements = new ArrayList<Statement>();
        for (int i = 0; i < types.length; i++) {
            statements.add(new Statement(SqlOperation.INSERT, false, types[i], StatementOrigin.DIRECT,
                    "INSERT " + types[i].getSimpleName(), i, i + 1, i + 1, types[i].getName().hashCode(), null, 1,
                    true, transactional, 0));
        }
        return new Summary("call", statements, types.length, 0, types.length);
    }

    /** A line of an audit trail, written after the row it describes by design. */
    @AcceptAdvice("writes-without-transaction")
    public record OwnerAudit(@PK Integer id, @FK Owner owner, String change) implements Entity<Integer> {}

    @Test
    public void relatedWritesOutsideATransactionCarryAdvice() {
        var advisories = SqlLogRenderer.advisories(writes(false, Owner.class, Pet.class));
        assertEquals(1, advisories.size(), advisories.toString());
        assertEquals("the unit of work wrote Owner and then Pet, which references Owner, each in a transaction of "
                + "its own; when a later write fails, the earlier ones stay; run them in one transaction. See "
                + "https://orm.st/docs/advice#writes-without-transaction", advisories.getFirst());
        // The reference through an inline record counts: an owner's address references its city.
        assertEquals(1, SqlLogRenderer.advisories(writes(false, City.class, Owner.class)).size());
        // One piece of advice per pair of types, however many rows.
        assertEquals(1, SqlLogRenderer.advisories(writes(false, Owner.class, Pet.class, Pet.class, Owner.class, Pet.class)).size());
    }

    @Test
    public void relatedWritesDrawNoAdviceInATransactionOrApartOrInTheOtherOrder() {
        assertEquals(List.of(), SqlLogRenderer.advisories(writes(true, Owner.class, Pet.class)));
        assertEquals(List.of(), SqlLogRenderer.advisories(writes(false, Pet.class, City.class)));
        assertEquals(List.of(), SqlLogRenderer.advisories(writes(false, Pet.class, Owner.class)));
        assertEquals(List.of(), SqlLogRenderer.advisories(writes(false, Pet.class)));
        // The write in a transaction is not the one the untransacted write follows.
        var mixed = new ArrayList<>(writes(true, Owner.class).statements());
        mixed.addAll(writes(false, Pet.class).statements());
        assertEquals(List.of(), SqlLogRenderer.advisories(new Summary("call", mixed, 2, 0, 2)));
    }

    @Test
    public void relatedWritesAcceptedOnTheTypeWrittenSecondDrawNoAdvice() {
        assertEquals(List.of(), SqlLogRenderer.advisories(writes(false, Owner.class, OwnerAudit.class)));
    }

    @Test
    @AcceptAdvice("writes-without-transaction")
    public void relatedWritesAcceptedOnTheMethodRunningTheUnitOfWorkDrawNoAdvice() {
        assertEquals(List.of(), SqlLogRenderer.advisories(writes(false, Owner.class, Pet.class)));
    }

    @Test
    public void repeatedFetchesOfOneTypeCarryAdvice() {
        var summary = repeated(SqlOperation.SELECT, City.class, StatementOrigin.FETCH, 10, 1, true);
        assertEquals(1, SqlLogRenderer.advisories(summary).size());
        String rendered = SqlLogRenderer.render(summary);
        assertTrue(rendered.contains(NL + "\tadvice: 10 statements each fetched one City, 10 ms, 1.0 ms each; an inline foreign key joins it"), rendered);
        assertTrue(rendered.endsWith("See https://orm.st/docs/advice#repeated-fetch"), rendered);
    }

    @Test
    public void repeatedSingleRowReadsAndWritesCarryAdvice() {
        var read = SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true));
        assertEquals(1, read.size(), read.toString());
        assertTrue(read.getFirst().startsWith("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row each; one query"), read.getFirst());
        var write = SqlLogRenderer.advisories(repeated(SqlOperation.INSERT, Owner.class, StatementOrigin.DIRECT, 10, 1, true));
        assertEquals(1, write.size(), write.toString());
        assertTrue(write.getFirst().startsWith("the same INSERT of Owner ran 10 times, 10 ms, 1.0 ms each, one row each; the overloads"), write.getFirst());
        assertTrue(write.getFirst().endsWith("send the rows as one batch. See https://orm.st/docs/advice#repeated-write"), write.getFirst());
    }

    @Test
    public void aRepeatNamesTheTimeItsExecutionsSpentAndTheirCallSite() {
        var statements = repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 3,
                "OwnerService.kt:52", execution -> UNKNOWN);
        var advisories = SqlLogRenderer.advisories(new Summary("call", statements, 12, 0, 36));
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("the same SELECT of Owner ran 12 times, 36 ms, 3.0 ms each, one row each, at "
                + "OwnerService.kt:52; one query"), advisories.getFirst());
        var fetches = repeated(SqlOperation.SELECT, City.class, StatementOrigin.FETCH, 10, 1, true, 2,
                "OwnerView.kt:31", execution -> UNKNOWN);
        var fetched = SqlLogRenderer.advisories(new Summary("call", fetches, 10, 0, 20));
        assertTrue(fetched.getFirst().startsWith("10 statements each fetched one City, 20 ms, 2.0 ms each, at OwnerView.kt:31; "
                + "an inline foreign key"), fetched.getFirst());
    }

    @Test
    public void aShapeRepeatedFromTwoCallersIsTwoPiecesOfAdvice() {
        var statements = new ArrayList<Statement>();
        statements.addAll(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 2,
                "OwnerService.kt:52", execution -> UNKNOWN));
        statements.addAll(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 10, 1, true, 1,
                "OwnerService.kt:91", execution -> UNKNOWN));
        var advisories = SqlLogRenderer.advisories(new Summary("call", statements, 22, 0, 34));
        assertEquals(2, advisories.size(), advisories.toString());
        assertTrue(advisories.get(0).startsWith("the same SELECT of Owner ran 12 times, 24 ms, 2.0 ms each, one row each, at "
                + "OwnerService.kt:52; "), advisories.get(0));
        assertTrue(advisories.get(1).startsWith("the same SELECT of Owner ran 10 times, 10 ms, 1.0 ms each, one row each, at "
                + "OwnerService.kt:91; "), advisories.get(1));
    }

    @Test
    public void aRepeatWhoseExecutionsAreSlowOnTheirOwnSaysTheStatementIsTheCost() {
        var statements = repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 170, null,
                execution -> UNKNOWN);
        var advisories = SqlLogRenderer.advisories(new Summary("call", statements, 12, 0, 2040));
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("the same SELECT of Owner ran 12 times, 2040 ms, 170 ms each, one "
                + "row each; at 170 ms each the statement itself costs more than its round trips, so look at it "
                + "first; then one query for all of them"), advisories.getFirst());
        // Below ten milliseconds per execution the round trips are the cost, so the advice goes straight to them.
        var cheap = SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT,
                12, 1, true));
        assertTrue(cheap.getFirst().startsWith("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row "
                + "each; one query"), cheap.getFirst());
    }

    @Test
    public void adviceIsOrderedByTheTimeItsExecutionsSpentAfterCorrectness() {
        var statements = new ArrayList<Statement>();
        statements.addAll(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 10, 1, true, 1, null,
                execution -> UNKNOWN));
        statements.addAll(repeated(SqlOperation.INSERT, City.class, StatementOrigin.DIRECT, 10, 1, true, 5, null,
                execution -> UNKNOWN));
        statements.addAll(repeated(SqlOperation.SELECT, Pet.class, StatementOrigin.FETCH, 10, 1, true, 3, null,
                execution -> UNKNOWN));
        statements.addAll(writes(false, Owner.class, Pet.class).statements());
        var advisories = SqlLogRenderer.advisories(new Summary("call", statements, statements.size(), 0, 100));
        assertEquals(4, advisories.size(), advisories.toString());
        assertTrue(advisories.get(0).startsWith("the unit of work wrote Owner and then Pet"), advisories.get(0));
        assertTrue(advisories.get(1).startsWith("the same INSERT of City ran 10 times, 50 ms"), advisories.get(1));
        assertTrue(advisories.get(2).startsWith("10 statements each fetched one Pet, 30 ms"), advisories.get(2));
        assertTrue(advisories.get(3).startsWith("the same SELECT of Owner ran 10 times, 10 ms"), advisories.get(3));
    }

    @Test
    public void aRepeatInTransactionsOfItsOwnSaysSoAndThatABatchCommitsTheRowsTogether() {
        // Outside any transaction, every execution commits on its own.
        var writes = repeated(SqlOperation.INSERT, Owner.class, StatementOrigin.DIRECT, 10, 1, true, 1, null,
                execution -> OUTSIDE);
        var written = SqlLogRenderer.advisories(new Summary("call", writes, 10, 0, 10));
        assertEquals("the same INSERT of Owner ran 10 times, 10 ms, 1.0 ms each, one row each, in 10 transactions of their own; "
                + "the overloads that take a list send the rows as one batch, which commits them together. See "
                + "https://orm.st/docs/advice#repeated-write", written.getFirst());
        // A transaction per execution says the same; a read commits nothing, so its remedy is unchanged.
        var reads = repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 1, null,
                execution -> execution + 1L);
        var read = SqlLogRenderer.advisories(new Summary("call", reads, 12, 0, 12));
        assertEquals("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row each, in 12 transactions of their own; "
                + "one query for all of them, such as findAllById or a where with IN, reads the rows in one round "
                + "trip. See https://orm.st/docs/advice#repeated-select", read.getFirst());
    }

    /**
     * The statements of a repeat whose executions each followed work outside the database, such as a remote call:
     * one millisecond in the database, then a gap of the given millis before the next.
     */
    private static List<Statement> spread(SqlOperation operation, Class<? extends Data> type, int executions,
                                          long gapMillis, IntToLongFunction transaction) {
        var statements = new ArrayList<Statement>();
        long nanos = 1_000_000L;
        long shapeId = (type.getName() + operation + "spread").hashCode();
        for (int i = 0; i < executions; i++) {
            long transactionId = transaction.applyAsLong(i);
            long start = i * (nanos + gapMillis * 1_000_000L);
            statements.add(new Statement(operation, false, type, StatementOrigin.DIRECT, "SQL " + type.getSimpleName(), start,
                    start + nanos, start + nanos, shapeId, "PetRegistryService.kt:60", 1, true,
                    transactionId != OUTSIDE, Math.max(transactionId, 0)));
        }
        return statements;
    }

    @Test
    public void aWriteSpreadBetweenWorkOutsideTheDatabaseIsCollectedAndWrittenAfterTheLoop() {
        // Each write in a transaction of its own, 240 ms of other work between them: the loop around a remote call.
        var writes = spread(SqlOperation.INSERT, Owner.class, 29, 240, execution -> OUTSIDE);
        var advisories = SqlLogRenderer.advisories(new Summary("task", writes, 29, 0, 7_000));
        assertEquals("the same INSERT of Owner ran 29 times, 29 ms, 1.0 ms each, one row each, in 29 transactions of "
                + "their own, spread over 6749 ms with work outside the database between them, at "
                + "PetRegistryService.kt:60; collect the rows and write them after the loop through the overloads "
                + "that take a list, which sends them as one batch and commits them together, rather than opening a "
                + "transaction around the loop. See https://orm.st/docs/advice#repeated-write", advisories.getFirst());
        // Within one transaction, the fact is stated and the rows are still to be collected and written after it.
        var within = spread(SqlOperation.INSERT, Owner.class, 12, 240, execution -> 5L);
        var advice = SqlLogRenderer.advisories(new Summary("task", within, 12, 0, 3_000)).getFirst();
        assertTrue(advice.contains("one row each, spread over 2652 ms with work outside the database between them, at "
                + "PetRegistryService.kt:60; collect the rows and write them after the loop through the overloads "
                + "that take a list, which sends them as one batch. See"), advice);
        // Work that is a fraction of the writes is no spread: the executions follow one another.
        var dense = spread(SqlOperation.INSERT, Owner.class, 12, 5, execution -> OUTSIDE);
        var plain = SqlLogRenderer.advisories(new Summary("task", dense, 12, 0, 100)).getFirst();
        assertFalse(plain.contains("spread over"), plain);
        assertTrue(plain.contains("in 12 transactions of their own, at PetRegistryService.kt:60; the overloads that "
                + "take a list send the rows as one batch, which commits them together."), plain);
    }

    @Test
    public void aRepeatAcrossSeveralTransactionsSaysHowMany() {
        var statements = repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 1, null,
                execution -> execution % 3 + 1L);
        var advisories = SqlLogRenderer.advisories(new Summary("call", statements, 12, 0, 12));
        assertTrue(advisories.getFirst().startsWith("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row each, in 3 "
                + "transactions; one query"), advisories.getFirst());
        // A transaction the record does not identify is not counted, so the summary states nothing about them.
        var mixed = repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 1, null,
                execution -> execution % 2 == 0 ? UNKNOWN : 7L);
        var unstated = SqlLogRenderer.advisories(new Summary("call", mixed, 12, 0, 12));
        assertTrue(unstated.getFirst().startsWith("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row each; one "
                + "query"), unstated.getFirst());
    }

    /**
     * The statements of a repeat whose executions all ran at once, each in a transaction of its own: the sum of
     * their database time is the count times the millis, while on the clock they covered the millis once.
     */
    private static List<Statement> concurrent(SqlOperation operation, Class<? extends Data> type, int executions,
                                              long millis) {
        var statements = new ArrayList<Statement>();
        long nanos = millis * 1_000_000L;
        long shapeId = (type.getName() + operation + "concurrent").hashCode();
        for (int i = 0; i < executions; i++) {
            statements.add(new Statement(operation, false, type, StatementOrigin.DIRECT, "SQL " + type.getSimpleName(), 0,
                    nanos, nanos, shapeId, null, 1, true, true, i + 1L));
        }
        return statements;
    }

    @Test
    public void aConcurrentRepeatStatesTheTimeItCoveredOnTheClock() {
        var advisories = SqlLogRenderer.advisories(new Summary("task", concurrent(SqlOperation.SELECT, Owner.class,
                29, 25), 29, 0, 25));
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("the same SELECT of Owner ran 29 times, 725 ms over 25 ms elapsed, "
                + "25 ms each, one row each, in 29 transactions of their own; at 25 ms each the statement itself "
                + "costs more than its round trips, so look at it first; then one query"), advisories.getFirst());
        // Executions that ran one after another cost what they add up to, so the line states the sum alone.
        var sequential = SqlLogRenderer.advisories(repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT,
                12, 1, true));
        assertTrue(sequential.getFirst().startsWith("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row each"),
                sequential.getFirst());
    }

    @Test
    public void adviceIsOrderedByTheTimeOnTheClockNotTheSum() {
        var statements = new ArrayList<Statement>();
        // 29 concurrent reads: 725 ms summed, 25 ms on the clock.
        statements.addAll(concurrent(SqlOperation.SELECT, Owner.class, 29, 25));
        // 10 sequential inserts: 100 ms either way.
        statements.addAll(repeated(SqlOperation.INSERT, City.class, StatementOrigin.DIRECT, 10, 1, true, 10, null,
                execution -> UNKNOWN));
        var advisories = SqlLogRenderer.advisories(new Summary("task", statements, 39, 0, 100));
        assertEquals(2, advisories.size(), advisories.toString());
        assertTrue(advisories.get(0).startsWith("the same INSERT of City ran 10 times, 100 ms"), advisories.get(0));
        assertTrue(advisories.get(1).startsWith("the same SELECT of Owner ran 29 times, 725 ms over 25 ms elapsed"),
                advisories.get(1));
    }

    @Test
    public void aRepeatBelowTheFloorDrawsNoAdviceByItsTimeOnTheClock() {
        var summary = new Summary("task", concurrent(SqlOperation.SELECT, Owner.class, 29, 25), 29, 0, 25);
        AdviceLog.repeatFloor(Duration.ofMillis(100));
        try {
            // 725 ms summed is above the floor; the 25 ms the executions covered is not.
            assertEquals(List.of(), SqlLogRenderer.advisories(summary));
            AdviceLog.repeatFloor(Duration.ofMillis(20));
            assertEquals(1, SqlLogRenderer.advisories(summary).size());
        } finally {
            AdviceLog.repeatFloor(null);
        }
    }

    @Test
    public void aRepeatInsideOneTransactionThatDidNotAdviseOnItIsTheSummarys() {
        // A transaction that recorded no repeats, as in production, leaves every execution to the summary.
        var statements = repeated(SqlOperation.SELECT, Owner.class, StatementOrigin.DIRECT, 12, 1, true, 1, null,
                execution -> 7L);
        var advisories = SqlLogRenderer.advisories(new Summary("call", statements, 12, 0, 12));
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().startsWith("the same SELECT of Owner ran 12 times, 12 ms, 1.0 ms each, one row each; one "
                + "query"), advisories.getFirst());
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
