package st.orm.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;
import st.orm.test.CapturedSql.Operation;

/**
 * Extended tests for {@link SqlCapture} covering uncovered branches in the
 * createObserver switch expression (DELETE and UNDEFINED operations).
 */
@StormTest(scripts = {"/test-schema.sql", "/test-data.sql"})
class SqlCaptureTest {

    record Item(@PK Integer id, String name) implements Entity<Integer> {}

    @Test
    void captureDeleteOperationShouldRecordDeleteStatement(ORMTemplate orm, SqlCapture capture) {
        // Insert an item, then delete it while capturing to exercise the DELETE branch.
        Integer insertedId = orm.entity(Item.class).insertAndFetchId(new Item(0, "ToDelete"));
        capture.run(() -> orm.entity(Item.class).remove(new Item(insertedId, "ToDelete")));
        assertEquals(1, capture.count(Operation.DELETE));
        var deleteStatements = capture.statements(Operation.DELETE);
        assertEquals(1, deleteStatements.size());
        assertTrue(deleteStatements.getFirst().statement().toUpperCase().contains("DELETE"));
    }

    @Test
    void captureUndefinedOperationViaRawSql(ORMTemplate orm, SqlCapture capture) {
        // Execute raw SQL that does not start with SELECT/INSERT/UPDATE/DELETE
        // to exercise the UNDEFINED branch in the createObserver switch.
        capture.run(() -> orm.query("SET @x = 1").executeUpdate());
        var undefinedStatements = capture.statements(Operation.UNDEFINED);
        assertEquals(1, undefinedStatements.size());
    }

    @Test
    void capturedStatementParametersShouldContainBoundValues(ORMTemplate orm, SqlCapture capture) {
        capture.run(() -> orm.entity(Item.class).findById(1));
        var statements = capture.statements(Operation.SELECT);
        assertFalse(statements.isEmpty());
        assertNotNull(statements.getFirst().parameters());
    }

    @Test
    void executeThrowingShouldCaptureStatementsAndReturnResult(ORMTemplate orm, SqlCapture capture) throws Exception {
        var result = capture.executeThrowing(() -> orm.entity(Item.class).findById(1));
        assertTrue(result.isPresent());
        assertEquals(1, capture.count(Operation.SELECT));
    }
}
