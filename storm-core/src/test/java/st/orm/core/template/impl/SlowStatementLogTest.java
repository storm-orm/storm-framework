package st.orm.core.template.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;
import st.orm.Data;
import st.orm.core.template.impl.SlowStatementLog.ShapeStats;
import st.orm.spi.QueryContext;
import st.orm.spi.QueryContext.ExecutionKind;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Verifies how a slow line renders and how a shape's baseline and reporting budget behave, both without a database.
 */
public class SlowStatementLogTest {

    private record Context(SqlOperation operation,
                           Class<? extends Data> type,
                           ExecutionKind kind,
                           StatementOrigin origin,
                           long shapeId) implements QueryContext {
        @Override
        public Optional<Class<? extends Data>> dataType() {
            return Optional.ofNullable(type);
        }

        @Override
        public OptionalInt batchSize() {
            return OptionalInt.empty();
        }

        @Override
        public Optional<String> statement() {
            return Optional.of("SELECT c.id\nFROM city c\nWHERE c.id = ?");
        }
    }

    private static Context select(long shapeId) {
        return new Context(SqlOperation.SELECT, City.class, ExecutionKind.QUERY, StatementOrigin.DIRECT, shapeId);
    }

    private record City(Integer id) implements st.orm.Entity<Integer> {
    }

    @Test
    public void theHeadlineNamesTheExecutionAndWhatItCost() {
        String line = SlowStatementLog.render(select(0x3f9a2cL), 1_840_000_000L, 0, 3, true, null, "PetService.kt:42",
                "SELECT c.id\nFROM city c\nWHERE c.id = ?", null, 1, null, 0);
        assertTrue(line.startsWith("SQL slow (SELECT City): 1840 ms in database, 3 rows, PetService.kt:42"), line);
        // The statement follows as sent, indented like the statement log's.
        assertTrue(line.contains(System.lineSeparator() + "\tSELECT c.id" + System.lineSeparator() + "\tFROM city c"), line);
        assertTrue(line.endsWith("\tshape: 00000000003f9a2c  parameters: 1"), line);
    }

    @Test
    public void adviceTheStatementCarriesFollowsTheFacts() {
        String line = SlowStatementLog.render(select(0x3f9a2cL), 1_840_000_000L, 0, 3, true, null, null,
                "SELECT c.id FROM city c", null, 1, null, 0, java.util.List.of("every search term is short"));
        assertTrue(line.endsWith("\tshape: 00000000003f9a2c  parameters: 1" + System.lineSeparator()
                + "\tadvice: every search term is short"), line);
    }

    @Test
    public void aFetchAndABatchAreLabelled() {
        var fetch = new Context(SqlOperation.SELECT, City.class, ExecutionKind.QUERY, StatementOrigin.FETCH, 1);
        assertTrue(SlowStatementLog.render(fetch, 300_000_000L, 0, 1, true, null, null, "SELECT 1", null, 1, null, 0)
                .startsWith("SQL slow (SELECT City, fetch): 300 ms in database, 1 rows"));
        var batch = new Context(SqlOperation.INSERT, City.class, ExecutionKind.BATCH, StatementOrigin.DIRECT, 1);
        String line = SlowStatementLog.render(batch, 3_200_000_000L, 0, 5000, true, null, null, "INSERT", null, 0, null, 0);
        assertTrue(line.startsWith("SQL slow (INSERT City, batch): 3200 ms in database, 5000 rows"), line);
        // A batch binds its rows through bind variables, so a parameter count would describe the template.
        assertFalse(line.contains("parameters"), line);
    }

    @Test
    public void aStreamedReadSeparatesConsumptionFromDatabaseTime() {
        String line = SlowStatementLog.render(select(1), 1_840_000_000L, 3_200_000_000L, 12_400, false, null, null,
                "SELECT 1", null, 1, null, 0);
        assertTrue(line.contains("1840 ms in database, 12400* rows read over 3200 ms"), line);
        // Below a millisecond of consumption there is nothing to separate.
        String prompt = SlowStatementLog.render(select(1), 1_840_000_000L, 200_000L, 3, true, null, null,
                "SELECT 1", null, 1, null, 0);
        assertFalse(prompt.contains("read over"), prompt);
    }

    @Test
    public void theBaselineTellsAnOutlierFromAUniformlySlowShape() {
        var outlier = new ShapeStats.Baseline(64, 6_000_000L, 12);
        String line = SlowStatementLog.render(select(1), 1_840_000_000L, 0, 3, true, null, null, "SELECT 1", outlier,
                4812, null, 0);
        assertTrue(line.contains("shape: 0000000000000001 (typically 6.0 ms, 306x)  parameters: 4812 (typically 12)"), line);
        var uniform = new ShapeStats.Baseline(64, 310_000_000L, 1);
        String usual = SlowStatementLog.render(select(1), 340_000_000L, 0, 3, true, null, null, "SELECT 1", uniform,
                1, null, 0);
        assertTrue(usual.contains("shape: 0000000000000001 (typically 310 ms)  parameters: 1"), usual);
        assertFalse(usual.contains("x)"), usual);
        // Too few executions to trust, so no baseline: the line states the shape alone.
        String early = SlowStatementLog.render(select(1), 1_840_000_000L, 0, 3, true, null, null, "SELECT 1", null,
                4812, null, 0);
        assertTrue(early.contains("shape: 0000000000000001  parameters: 4812"), early);
        assertFalse(early.contains("typically"), early);
    }

    @Test
    public void theCommentAndSuppressedCountFollowTheFacts() {
        String line = SlowStatementLog.render(select(1), 300_000_000L, 0, 1, true, null, null, "SELECT 1", null, 1,
                "traceparent='00-4bf92f35-00f067aa-01'", 37);
        assertTrue(line.endsWith("shape: 0000000000000001  parameters: 1  comment: traceparent='00-4bf92f35-00f067aa-01'  +37 suppressed"),
                line);
    }

    @Test
    public void aFailedExecutionNamesTheFailureInsteadOfRows() {
        String line = SlowStatementLog.render(select(1), 30_012_000_000L, 0, 0, true,
                new java.sql.SQLTimeoutException("canceling statement due to statement timeout"), "PetService.kt:42",
                "SELECT 1", null, 1, null, 0);
        assertTrue(line.startsWith("SQL slow (SELECT City): 30012 ms in database, failed (SQLTimeoutException), PetService.kt:42"),
                line);
        // The driver's message may quote values, so the class alone names the failure.
        assertFalse(line.contains("canceling"), line);
        assertFalse(line.contains("rows"), line);
    }

    @Test
    public void subMillisecondTimesKeepAValue() {
        String line = SlowStatementLog.render(select(0), 250_000L, 0, 1, true, null, null, "SELECT 1", null, 1, null, 0);
        assertTrue(line.startsWith("SQL slow (SELECT City): 0.25 ms in database"), line);
        assertTrue(SlowStatementLog.render(select(0), 2_500_000L, 0, 1, true, null, null, "SELECT 1", null, 1, null, 0)
                .startsWith("SQL slow (SELECT City): 2.5 ms in database"));
    }

    private static final long MINUTE = java.time.Duration.ofMinutes(1).toNanos();

    @Test
    public void theBaselineIsAGeometricMeanAnOutlierBarelyMoves() {
        var stats = new ShapeStats();
        long now = 1_000_000_000L;
        for (int i = 0; i < 64; i++) {
            stats.record(now, 1_000_000L, 12);
        }
        // Within the first window the baseline is the window so far, minus the execution being judged, which
        // has been recorded by the time it is judged.
        stats.record(now, 300_000_000L, 4812);
        var baseline = stats.baseline(300_000_000L, 4812);
        assertEquals(64, baseline.samples());
        assertTrue(Math.abs(baseline.typicalNanos() - 1_000_000L) < 10_000L, String.valueOf(baseline.typicalNanos()));
        assertEquals(12, baseline.typicalParameters());
        // Once the window closes the outlier is folded in; the geometric mean barely moves.
        stats.record(now + MINUTE + 1, 1_000_000L, 12);
        var closed = stats.baseline(1_000_000L, 12);
        assertEquals(65, closed.samples());
        assertTrue(closed.typicalNanos() < 1_100_000L, String.valueOf(closed.typicalNanos()));
        assertTrue(closed.typicalParameters() < 100, String.valueOf(closed.typicalParameters()));
    }

    @Test
    public void tooFewExecutionsGiveNoBaseline() {
        var stats = new ShapeStats();
        long now = 1_000_000_000L;
        for (int i = 0; i < 8; i++) {
            stats.record(now, 1_000_000L, 1);
        }
        // Eight in the window, of which one is the execution being judged: seven others is too few.
        assertNull(stats.baseline(1_000_000L, 1));
        stats.record(now, 1_000_000L, 1);
        assertNotNull(stats.baseline(1_000_000L, 1));
        // A closed window with too few samples defers to the current one rather than answering thinly.
        var thin = new ShapeStats();
        thin.record(now, 1_000_000L, 1);
        for (int i = 0; i < 9; i++) {
            thin.record(now + MINUTE + 1, 2_000_000L, 1);
        }
        var baseline = thin.baseline(2_000_000L, 1);
        assertNotNull(baseline);
        assertTrue(Math.abs(baseline.typicalNanos() - 2_000_000L) < 10_000L, String.valueOf(baseline.typicalNanos()));
    }

    @Test
    public void aLargeWindowOutweighsASmallOne() {
        var stats = new ShapeStats();
        long now = 1_000_000_000L;
        for (int i = 0; i < 1000; i++) {
            stats.record(now, 1_000_000L, 1);
        }
        // One stray execution in the next window closes as a window of one; it barely moves a thousand.
        stats.record(now + MINUTE + 1, 300_000_000L, 1);
        stats.record(now + 2 * (MINUTE + 1), 1_000_000L, 1);
        var baseline = stats.baseline(1_000_000L, 1);
        assertEquals(1001, baseline.samples());
        assertTrue(baseline.typicalNanos() < 1_100_000L, String.valueOf(baseline.typicalNanos()));
    }

    @Test
    public void recentWindowsWeighMost() {
        var stats = new ShapeStats();
        long now = 1_000_000_000L;
        for (int i = 0; i < 10; i++) {
            stats.record(now, 300_000_000L, 1);
        }
        // Three windows at 3 ms after one at 300 ms: the baseline follows the shape's new cost.
        for (int window = 1; window <= 3; window++) {
            for (int i = 0; i < 10; i++) {
                stats.record(now + window * (MINUTE + 1), 3_000_000L, 1);
            }
        }
        stats.record(now + 4 * (MINUTE + 1), 3_000_000L, 1);
        var baseline = stats.baseline(3_000_000L, 1);
        assertTrue(baseline.typicalNanos() < 10_000_000L, String.valueOf(baseline.typicalNanos()));
    }

    @Test
    public void aShapeReportsTheLimitPerMinuteAndCountsTheRest() {
        var stats = new ShapeStats();
        long now = 1_000_000_000L;
        stats.record(now, 1, 0);
        for (int i = 0; i < 5; i++) {
            assertEquals(0, stats.claim(5));
        }
        assertEquals(-1, stats.claim(5));
        assertEquals(-1, stats.claim(5));
        // The next window's first line stands for the two the previous window suppressed.
        stats.record(now + MINUTE + 1, 1, 0);
        assertEquals(2, stats.claim(5));
        assertEquals(0, stats.claim(5));
    }

    @Test
    public void aLimitOfZeroSuppressesNothing() {
        var stats = new ShapeStats();
        stats.record(1_000_000_000L, 1, 0);
        for (int i = 0; i < 100; i++) {
            assertEquals(0, stats.claim(0));
        }
    }
}
