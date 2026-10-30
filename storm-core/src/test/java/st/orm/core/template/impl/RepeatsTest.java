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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import st.orm.Data;
import st.orm.Entity;
import st.orm.core.template.impl.IndexDemands.Kind;
import st.orm.spi.QueryContext.ExecutionKind;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * What a unit of work repeated is counted within bounds: a statement whose repetition carries no advice is not
 * counted, a scope counts at most {@link Repeats#MAX_REPEATS} repeats, and a repeat counts the first
 * {@link Repeats#MAX_PARTS} transactions apart and the rest together, still counting them.
 */
class RepeatsTest {

    record Owner(Integer id) implements Entity<Integer> {}

    @BeforeEach
    void noFloor() {
        AdviceLog.repeatFloor(null);
    }

    @AfterEach
    void restore() {
        AdviceLog.reset();
    }

    private static QueryImpl.QueryContextImpl context(SqlOperation operation, boolean upsert,
                                                      @Nullable Class<? extends Data> type, long shapeId,
                                                      @Nullable RepositoryCall call, boolean upsertTakesGeneratedKey) {
        return new QueryImpl.QueryContextImpl(operation, upsert, type, ExecutionKind.QUERY, "SQL",
                StatementOrigin.DIRECT, shapeId, true, null, true, call, upsertTakesGeneratedKey, null, null,
                null);
    }

    private static QueryImpl.QueryContextImpl context(SqlOperation operation, @Nullable RepositoryCall call,
                                                      @Nullable IndexDemands indexDemands,
                                                      @Nullable DataSource dataSource) {
        return new QueryImpl.QueryContextImpl(operation, false, Owner.class, ExecutionKind.QUERY, "SQL",
                StatementOrigin.DIRECT, 13, true, null, true, call, false, null, indexDemands, dataSource);
    }

    /**
     * Records ten executions of the statement, one in each of ten transactions, each taking the given time; where
     * {@code alongside} is set, each transaction holds another statement too.
     */
    private static Repeats perTransaction(QueryImpl.QueryContextImpl context, long nanos, boolean alongside) {
        var repeats = new Repeats(true);
        for (int i = 0; i < Advice.REPEAT_THRESHOLD; i++) {
            long start = i * 2 * nanos;
            repeats.observed(i + 1, start, start + nanos);
            repeats.record(context, start, start + nanos, 1, true, true, i + 1, null);
            if (alongside) {
                repeats.observed(i + 1, start + nanos, start + nanos + 1_000_000);
            }
        }
        return repeats;
    }

    private static Repeats.Repeat record(Repeats repeats, SqlOperation operation, long shapeId, long transactionId) {
        var context = context(operation, false, Owner.class, shapeId, null, false);
        repeats.observed(transactionId, 0, 1_000_000);
        return repeats.record(context, 0, 1_000_000, 1, true, true, transactionId, null);
    }

    @Test
    void aStatementWhoseRepetitionCarriesNoAdviceIsNotCounted() {
        assertNull(Repeats.adviceOn(context(SqlOperation.UNDEFINED, false, Owner.class, 7, null, false)));
        // A MERGE that both updates and inserts: an undefined operation the dialect recognizes as an upsert.
        assertEquals(Advice.REPEATED_WRITE,
                Repeats.adviceOn(context(SqlOperation.UNDEFINED, true, Owner.class, 7, null, false)));
        var repeats = new Repeats(true);
        for (int i = 0; i < 20; i++) {
            assertNull(record(repeats, SqlOperation.UNDEFINED, 7, i + 1));
        }
        assertEquals(List.of(), repeats.advisories(false));
        assertFalse(UnitOfWorkRepeats.candidate(context(SqlOperation.SELECT, false, null, 7, null, false)));
        assertTrue(UnitOfWorkRepeats.candidate(context(SqlOperation.SELECT, false, Owner.class, 7, null, false)));
    }

    @Test
    void aScopeCountsABoundedNumberOfRepeats() {
        var repeats = new Repeats(false);
        for (int shape = 1; shape <= Repeats.MAX_REPEATS; shape++) {
            record(repeats, SqlOperation.SELECT, shape, 1);
        }
        // A further statement is not counted, however often it runs.
        for (int i = 0; i < 20; i++) {
            assertNull(record(repeats, SqlOperation.SELECT, Repeats.MAX_REPEATS + 1, 1));
        }
        assertEquals(List.of(), repeats.advisories(true));
    }

    @Test
    void transactionsPastTheOnesCountedApartAreStillCounted() {
        var repeats = new Repeats(true);
        int transactions = Repeats.MAX_PARTS + 76;
        for (int transaction = 1; transaction <= transactions; transaction++) {
            record(repeats, SqlOperation.SELECT, 7, transaction);
        }
        var advisories = repeats.advisories(false);
        assertEquals(1, advisories.size(), advisories.toString());
        assertTrue(advisories.getFirst().text().contains("ran " + transactions + " times, "), advisories.toString());
        // Past the transactions counted apart, whether each held the execution alone is not known, so not claimed.
        assertTrue(advisories.getFirst().text().contains("in " + transactions + " transactions.\n"),
                advisories.toString());
        assertFalse(advisories.getFirst().text().contains("of their own"), advisories.toString());
    }

    @Test
    void aTransactionThatHeldOtherStatementsDidNotHoldTheExecutionOnItsOwn() {
        var repeats = new Repeats(true);
        for (int transaction = 1; transaction <= Advice.REPEAT_THRESHOLD; transaction++) {
            record(repeats, SqlOperation.SELECT, 7, transaction);
            // Another statement of the same transaction.
            repeats.observed(transaction, 2_000_000, 3_000_000);
        }
        var text = repeats.advisories(false).getFirst().text();
        assertTrue(text.contains("in 10 transactions.\n"), text);
        assertFalse(text.contains("of their own"), text);
    }

    @Test
    void theTimeOtherStatementsSpentBetweenAWritesExecutionsIsNotWorkOutsideTheDatabase() {
        // Ten inserts of 1 ms, 100 ms apart: spread, unless other statements of the scope fill the gaps.
        var insert = context(SqlOperation.INSERT, false, Owner.class, 11, RepositoryCall.INSERT, false);
        var spread = new Repeats(true);
        var filled = new Repeats(true);
        for (int i = 0; i < Advice.REPEAT_THRESHOLD; i++) {
            long start = i * 100_000_000L;
            for (var repeats : List.of(spread, filled)) {
                repeats.observed(i + 1, start, start + 1_000_000);
                repeats.record(insert, start, start + 1_000_000, 1, true, true, i + 1, null);
            }
            // A statement outside any counted transaction, which spent the gap in the database.
            filled.observed(0, start + 1_000_000, start + 100_000_000L);
        }
        var spreadText = spread.advisories(false).getFirst().text();
        assertTrue(spreadText.contains(", in 10 transactions of their own, spread over "), spreadText);
        assertTrue(spreadText.contains(" with work outside the database between them.\nfix: collect the rows"), spreadText);
        var filledText = filled.advisories(false).getFirst().text();
        assertTrue(filledText.contains(", in 10 transactions of their own.\nfix: the overloads"), filledText);
        assertFalse(filledText.contains("outside the database"), filledText);
    }

    @Test
    void aReadIsNeverSaidToBeSpread() {
        var read = context(SqlOperation.SELECT, false, Owner.class, 7, RepositoryCall.FIND_BY_ID, false);
        var repeats = new Repeats(true);
        for (int i = 0; i < Advice.REPEAT_THRESHOLD; i++) {
            long start = i * 100_000_000L;
            repeats.observed(i + 1, start, start + 1_000_000);
            repeats.record(read, start, start + 1_000_000, 1, true, true, i + 1, null);
        }
        var text = repeats.advisories(false).getFirst().text();
        assertTrue(text.startsWith("findById of Owner ran 10 times, "), text);
        assertTrue(text.contains(", in 10 transactions of their own.\nfix: findAllById over the keys reads the rows"), text);
        assertFalse(text.contains("outside the database"), text);
    }

    @Test
    void anUpsertThatDrawsAGeneratedKeyOnUpdateIsSaidToTakeOne() {
        var repeats = new Repeats(false);
        var upsert = context(SqlOperation.INSERT, true, Owner.class, 11, RepositoryCall.UPSERT, true);
        for (int i = 0; i < Advice.REPEAT_THRESHOLD; i++) {
            repeats.observed(1, i * 2_000_000L, i * 2_000_000L + 1_000_000L);
            repeats.record(upsert, i * 2_000_000L, i * 2_000_000L + 1_000_000L, 1, true, true, 1, null);
        }
        var text = repeats.advisories(true).getFirst().text();
        assertTrue(text.startsWith("upsert of Owner ran 10 times, "), text);
        assertTrue(text.contains("avoids the writes, and the generated key value each upsert takes even where it "
                + "updates\nor: the overloads that take a list send the rows as one batch."), text);
    }

    @Test
    void aWriteRunOnceInEachOfTransactionsThatHeldMoreIsNoRepeatWhileItIsCheap() {
        var update = context(SqlOperation.UPDATE, RepositoryCall.UPDATE, null, null);
        assertEquals(List.of(), perTransaction(update, 1_000_000, true).advisories(false));
        // Held alone, the transactions are the loop, which the list form commits together.
        var own = perTransaction(update, 1_000_000, false).advisories(false);
        assertEquals(1, own.size(), own.toString());
        assertTrue(own.getFirst().text().contains("transactions of their own"), own.toString());
    }

    @Test
    void aCostlyWriteRunOnceInEachOfTransactionsThatHeldMoreIsTheStatementToLookAt() {
        var update = context(SqlOperation.UPDATE, RepositoryCall.UPDATE, null, null);
        var advisories = perTransaction(update, Advice.STATEMENT_COST_NANOS, true).advisories(false);
        assertEquals(1, advisories.size(), advisories.toString());
        var text = advisories.getFirst().text();
        assertTrue(text.contains("\ncost: at 10 ms each the statement itself costs more than its round trip, and it runs once per transaction, so look at the statement."),
                text);
        assertFalse(text.contains("as one batch"), text);
    }

    @Test
    void aCostlyRepeatNamesTheIndexItLacksRightAfterIt() throws SQLException {
        DataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:repeats_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE owner (id INTEGER PRIMARY KEY, last_name VARCHAR(50))");
            IndexCatalog.publish(dataSource, IndexCatalog.of(Map.of("", DatabaseSchema.read(connection))));
        }
        var builder = new IndexDemands.Builder();
        builder.add("", "owner", "last_name", Kind.EQUALITY);
        var select = context(SqlOperation.SELECT, null, builder.build(), dataSource);
        var costly = perTransaction(select, Advice.STATEMENT_COST_NANOS, false).advisories(false);
        assertEquals(2, costly.size(), costly.toString());
        assertEquals(Advice.REPEATED_SELECT, costly.get(0).advice());
        assertEquals(Advice.MISSING_INDEX, costly.get(1).advice());
        assertTrue(costly.get(1).text().contains("last_name"), costly.toString());
        // A cheap repeat is its round trips, which no index saves.
        var cheap = perTransaction(select, 1_000_000, false).advisories(false);
        assertEquals(1, cheap.size(), cheap.toString());
    }

    @Test
    void noIntervalsCoverNoTime() {
        assertEquals(0, Repeats.Intervals.union(List.of(new Repeats.Intervals())));
    }
}
