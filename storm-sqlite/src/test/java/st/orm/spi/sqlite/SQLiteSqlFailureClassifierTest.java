package st.orm.spi.sqlite;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.IntegrityFailure.Kind.CHECK;
import static st.orm.core.template.IntegrityFailure.Kind.FOREIGN_KEY;
import static st.orm.core.template.IntegrityFailure.Kind.NOT_NULL;
import static st.orm.core.template.IntegrityFailure.Kind.UNIQUE;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;
import st.orm.core.template.IntegrityFailure;

/**
 * What the SQLite classifier reads from the constraint violations SQLite reports.
 */
class SQLiteSqlFailureClassifierTest {

    private final SQLiteSqlFailureClassifier classifier = new SQLiteSqlFailureClassifier();

    private IntegrityFailure classify(String message) {
        return classifier.integrityFailure(new SQLException(message, "", 19)).orElseThrow();
    }

    @Test
    void aUniqueViolationNamesTheColumnsWithoutTheirTable() {
        var failure = classify("[SQLITE_CONSTRAINT_UNIQUE] A UNIQUE constraint failed (UNIQUE constraint failed: iv_parent.code, iv_parent.region)");
        assertEquals(UNIQUE, failure.kind());
        assertEquals(List.of("code", "region"), failure.columns());
        assertNull(failure.constraint());
        assertFalse(failure.primaryKey());
    }

    @Test
    void aPrimaryKeyViolationIsMarkedAsOne() {
        var failure = classify("[SQLITE_CONSTRAINT_PRIMARYKEY] A PRIMARY KEY constraint failed (UNIQUE constraint failed: iv_parent.id)");
        assertEquals(UNIQUE, failure.kind());
        assertTrue(failure.primaryKey());
    }

    @Test
    void aForeignKeyViolationNamesNothing() {
        var failure = classify("[SQLITE_CONSTRAINT_FOREIGNKEY] A foreign key constraint failed (FOREIGN KEY constraint failed)");
        assertEquals(FOREIGN_KEY, failure.kind());
        assertNull(failure.constraint());
        assertTrue(failure.columns().isEmpty());
    }

    @Test
    void aNullNamesTheColumn() {
        var failure = classify("[SQLITE_CONSTRAINT_NOTNULL] A NOT NULL constraint failed (NOT NULL constraint failed: iv_parent.code)");
        assertEquals(NOT_NULL, failure.kind());
        assertEquals(List.of("code"), failure.columns());
    }

    @Test
    void aCheckViolationNamesTheConstraint() {
        var failure = classify("[SQLITE_CONSTRAINT_CHECK] A CHECK constraint failed (CHECK constraint failed: iv_parent_amount_ck)");
        assertEquals(CHECK, failure.kind());
        assertEquals("iv_parent_amount_ck", failure.constraint());
    }

    @Test
    void anotherConstraintOrErrorIsNotAViolation() {
        assertTrue(classifier.integrityFailure(new SQLException("[SQLITE_CONSTRAINT_TRIGGER] A trigger raised", "", 19)).isEmpty());
        assertTrue(classifier.integrityFailure(new SQLException("[SQLITE_BUSY] The database file is locked", "", 5)).isEmpty());
        assertTrue(classifier.integrityFailure(new SQLException(null, "", 19)).isEmpty());
    }

    @Test
    void theDriversResultCodeDecidesBeforeTheMessage() {
        // The driver's exception carries the extended code; its message need not name it.
        var fromDriver = new SQLiteException("UNIQUE constraint failed: iv_parent.code", SQLiteErrorCode.SQLITE_CONSTRAINT_UNIQUE);
        var failure = classifier.integrityFailure(fromDriver).orElseThrow();
        assertEquals(UNIQUE, failure.kind());
        assertEquals(List.of("code"), failure.columns());
        assertEquals(FOREIGN_KEY, classifier.integrityFailure(new SQLiteException("FOREIGN KEY constraint failed",
                SQLiteErrorCode.SQLITE_CONSTRAINT_FOREIGNKEY)).orElseThrow().kind());
        assertTrue(classifier.integrityFailure(new SQLiteException("busy", SQLiteErrorCode.SQLITE_BUSY)).isEmpty());
    }

    @Test
    void aMessageWithoutNamesKeepsTheKind() {
        assertTrue(classifier.integrityFailure(new SQLException("[SQLITE_CONSTRAINT_UNIQUE] changed", "", 19)).orElseThrow().columns().isEmpty());
        assertNull(classifier.integrityFailure(new SQLException("[SQLITE_CONSTRAINT_CHECK] changed", "", 19)).orElseThrow().constraint());
        assertTrue(classifier.integrityFailure(new SQLiteException("changed", SQLiteErrorCode.SQLITE_CONSTRAINT_PRIMARYKEY)).orElseThrow().primaryKey());
        assertEquals(NOT_NULL, classifier.integrityFailure(new SQLiteException("changed", SQLiteErrorCode.SQLITE_CONSTRAINT_NOTNULL)).orElseThrow().kind());
        assertEquals(CHECK, classifier.integrityFailure(new SQLiteException("changed", SQLiteErrorCode.SQLITE_CONSTRAINT_CHECK)).orElseThrow().kind());
    }

    @Test
    void sqliteStoresAnyValueWhateverTheDeclaredSize() {
        assertFalse(new SQLiteSqlDialect().enforcesColumnSizes());
    }
}
