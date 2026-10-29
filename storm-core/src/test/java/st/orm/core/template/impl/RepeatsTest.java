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

import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.Entity;
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
                                                      boolean repositoryWrite, boolean upsertTakesGeneratedKey) {
        return new QueryImpl.QueryContextImpl(operation, upsert, type, ExecutionKind.QUERY, "SQL",
                StatementOrigin.DIRECT, shapeId, true, null, true, repositoryWrite, upsertTakesGeneratedKey);
    }

    private static Repeats.Repeat record(Repeats repeats, SqlOperation operation, long shapeId, long transactionId) {
        return repeats.record(context(operation, false, Owner.class, shapeId, false, false), 0, 1_000_000, 1, true,
                true, transactionId, null);
    }

    @Test
    void aStatementWhoseRepetitionCarriesNoAdviceIsNotCounted() {
        assertNull(Repeats.adviceOn(context(SqlOperation.UNDEFINED, false, Owner.class, 7, false, false)));
        // A MERGE that both updates and inserts: an undefined operation the dialect recognizes as an upsert.
        assertEquals(Advice.REPEATED_WRITE,
                Repeats.adviceOn(context(SqlOperation.UNDEFINED, true, Owner.class, 7, false, false)));
        var repeats = new Repeats(true);
        for (int i = 0; i < 20; i++) {
            assertNull(record(repeats, SqlOperation.UNDEFINED, 7, i + 1));
        }
        assertEquals(List.of(), repeats.advisories(false));
        assertFalse(UnitOfWorkRepeats.candidate(context(SqlOperation.SELECT, false, null, 7, false, false)));
        assertTrue(UnitOfWorkRepeats.candidate(context(SqlOperation.SELECT, false, Owner.class, 7, false, false)));
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
        assertTrue(advisories.getFirst().text().contains("in " + transactions + " transactions of their own"),
                advisories.toString());
    }

    @Test
    void anUpsertThatDrawsAGeneratedKeyOnUpdateIsSaidToTakeOne() {
        var repeats = new Repeats(false);
        var upsert = context(SqlOperation.INSERT, true, Owner.class, 11, true, true);
        for (int i = 0; i < Advice.REPEAT_THRESHOLD; i++) {
            repeats.record(upsert, i * 2_000_000L, i * 2_000_000L + 1_000_000L, 1, true, true, 1, null);
        }
        var text = repeats.advisories(true).getFirst().text();
        assertTrue(text.startsWith("the same UPSERT of Owner ran 10 times, "), text);
        assertTrue(text.contains("avoids the writes, and the generated key value each upsert takes even where it "
                + "updates. Otherwise, the overloads that take a list send the rows as one batch."), text);
    }

    @Test
    void noIntervalsCoverNoTime() {
        assertEquals(0, Repeats.Intervals.union(List.of(new Repeats.Intervals())));
    }
}
