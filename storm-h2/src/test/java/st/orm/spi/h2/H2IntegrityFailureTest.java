package st.orm.spi.h2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.IntegrityFailure.Kind.CHECK;
import static st.orm.core.template.IntegrityFailure.Kind.FOREIGN_KEY;
import static st.orm.core.template.IntegrityFailure.Kind.LENGTH;
import static st.orm.core.template.IntegrityFailure.Kind.NOT_NULL;
import static st.orm.core.template.IntegrityFailure.Kind.NUMERIC_RANGE;
import static st.orm.core.template.IntegrityFailure.Kind.REFERENCED;
import static st.orm.core.template.IntegrityFailure.Kind.UNIQUE;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import st.orm.core.template.IntegrityFailure;

/**
 * What the H2 classifier reads from the errors H2 reports for a constraint.
 */
class H2IntegrityFailureTest {

    private final H2IntegrityFailureClassifier classifier = new H2IntegrityFailureClassifier();

    private IntegrityFailure classify(int errorCode, String message) {
        return classifier.classify(new SQLException(message, String.valueOf(errorCode), errorCode)).orElseThrow();
    }

    @Test
    void aUniqueViolationNamesTheIndexAndTheTable() {
        var failure = classify(23505, "Unique index or primary key violation: \"PUBLIC.IV_PARENT_CODE_UK_INDEX_8 ON PUBLIC.IV_PARENT(CODE NULLS FIRST) VALUES ( /* 1 */ 'A' )\"; SQL statement:\nINSERT INTO iv_parent (id, code) VALUES (?, ?) [23505-232]");
        assertEquals(UNIQUE, failure.kind());
        assertEquals("IV_PARENT_CODE_UK_INDEX_8", failure.constraint());
        assertEquals("IV_PARENT", failure.table());
        assertFalse(failure.primaryKey());
    }

    @Test
    void aPrimaryKeyViolationIsMarkedAsOne() {
        var failure = classify(23505, "Unique index or primary key violation: \"PRIMARY KEY ON PUBLIC.IV_PARENT(ID) ( /* key:1 */ 1, 'B', NULL)\"; SQL statement:\nINSERT ... [23505-232]");
        assertEquals(UNIQUE, failure.kind());
        assertTrue(failure.primaryKey());
        assertNull(failure.constraint());
        assertEquals("IV_PARENT", failure.table());
    }

    @Test
    void aMissingParentNamesTheConstraintAndTheReferencingTable() {
        var failure = classify(23506, "Referential integrity constraint violation: \"IV_CHILD_PARENT_FK: PUBLIC.IV_CHILD FOREIGN KEY(PARENT_ID) REFERENCES PUBLIC.IV_PARENT(ID) (CAST(999 AS INTEGER))\"; SQL statement:\nINSERT ... [23506-232]");
        assertEquals(FOREIGN_KEY, failure.kind());
        assertEquals("IV_CHILD_PARENT_FK", failure.constraint());
        assertEquals("IV_CHILD", failure.table());
    }

    @Test
    void aReferencedRowNamesTheConstraintAndTheReferencingTable() {
        var failure = classify(23503, "Referential integrity constraint violation: \"IV_CHILD_PARENT_FK: PUBLIC.IV_CHILD FOREIGN KEY(PARENT_ID) REFERENCES PUBLIC.IV_PARENT(ID) (CAST(1 AS INTEGER))\"; SQL statement:\nDELETE ... [23503-232]");
        assertEquals(REFERENCED, failure.kind());
        assertEquals("IV_CHILD_PARENT_FK", failure.constraint());
        assertEquals("IV_CHILD", failure.table());
    }

    @Test
    void aNullNamesTheColumn() {
        var failure = classify(23502, "NULL not allowed for column \"CODE\"; SQL statement:\nINSERT ... [23502-232]");
        assertEquals(NOT_NULL, failure.kind());
        assertEquals(List.of("CODE"), failure.columns());
    }

    @Test
    void aCheckViolationNamesTheConstraint() {
        var failure = classify(23513, "Check constraint violation: \"IV_PARENT_AMOUNT_CK: AMOUNT >= 0\"; SQL statement:\nINSERT ... [23513-232]");
        assertEquals(CHECK, failure.kind());
        assertEquals("IV_PARENT_AMOUNT_CK", failure.constraint());
    }

    @Test
    void aValueTooLongNamesTheColumn() {
        var failure = classify(22001, "Value too long for column \"CODE CHARACTER VARYING(5)\": \"'ABCDEF' (6)\"; SQL statement:\nINSERT ... [22001-232]");
        assertEquals(LENGTH, failure.kind());
        assertEquals(List.of("CODE"), failure.columns());
    }

    @Test
    void aValueOutOfRangeIsReported() {
        var failure = classify(22003, "Numeric value out of range: \"1234.5\"; SQL statement:\nINSERT ... [22003-232]");
        assertEquals(NUMERIC_RANGE, failure.kind());
        assertTrue(failure.columns().isEmpty());
    }

    @Test
    void aNumberTooLongForItsColumnIsOutOfRange() {
        var failure = classify(22001, "Value too long for column \"AMOUNT DECIMAL(5, 2)\": \"1234.5 (5)\"; SQL statement:\nINSERT ... [22001-232]");
        assertEquals(NUMERIC_RANGE, failure.kind());
        assertEquals(List.of("AMOUNT"), failure.columns());
    }

    @Test
    void anotherErrorIsNotAViolation() {
        assertTrue(classifier.classify(new SQLException("Table not found", "42S02", 42102)).isEmpty());
        assertTrue(classifier.classify(new SQLException(null, "23505", 23505)).isPresent());
    }
}
