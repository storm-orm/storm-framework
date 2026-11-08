package st.orm.spi.oracle;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * What the Oracle classifier reads from the errors Oracle reports for a constraint.
 */
class OracleSqlFailureClassifierTest {

    private final OracleSqlFailureClassifier classifier = new OracleSqlFailureClassifier();

    private IntegrityFailure classify(int errorCode, String message) {
        return classifier.integrityFailure(new SQLException(message, "23000", errorCode)).orElseThrow();
    }

    @Test
    void aUniqueViolationNamesTheConstraintWithoutItsSchema() {
        var failure = classify(1, "ORA-00001: unique constraint (TEST.IV_PARENT_CODE_UK) violated\n");
        assertEquals(UNIQUE, failure.kind());
        assertEquals("IV_PARENT_CODE_UK", failure.constraint());
    }

    @Test
    void aMissingParentNamesTheConstraint() {
        var failure = classify(2291, "ORA-02291: integrity constraint (TEST.IV_CHILD_PARENT_FK) violated - parent key not found\n");
        assertEquals(FOREIGN_KEY, failure.kind());
        assertEquals("IV_CHILD_PARENT_FK", failure.constraint());
    }

    @Test
    void aReferencedRowNamesTheConstraint() {
        var failure = classify(2292, "ORA-02292: integrity constraint (TEST.IV_CHILD_PARENT_FK) violated - child record found\n");
        assertEquals(REFERENCED, failure.kind());
        assertEquals("IV_CHILD_PARENT_FK", failure.constraint());
    }

    @Test
    void aNullNamesTheTableAndTheColumn() {
        var inserted = classify(1400, "ORA-01400: cannot insert NULL into (\"TEST\".\"IV_PARENT\".\"CODE\")\n");
        assertEquals(NOT_NULL, inserted.kind());
        assertEquals("IV_PARENT", inserted.table());
        assertEquals(List.of("CODE"), inserted.columns());
        var updated = classify(1407, "ORA-01407: cannot update (\"TEST\".\"IV_PARENT\".\"CODE\") to NULL\n");
        assertEquals(NOT_NULL, updated.kind());
        assertEquals(List.of("CODE"), updated.columns());
    }

    @Test
    void aCheckViolationNamesTheConstraint() {
        var failure = classify(2290, "ORA-02290: check constraint (TEST.IV_PARENT_AMOUNT_CK) violated\n");
        assertEquals(CHECK, failure.kind());
        assertEquals("IV_PARENT_AMOUNT_CK", failure.constraint());
    }

    @Test
    void aValueTooLargeNamesTheTableAndTheColumn() {
        var failure = classify(12899, "ORA-12899: value too large for column \"TEST\".\"IV_PARENT\".\"CODE\" (actual: 6, maximum: 5)\n");
        assertEquals(LENGTH, failure.kind());
        assertEquals("IV_PARENT", failure.table());
        assertEquals(List.of("CODE"), failure.columns());
    }

    @Test
    void aValueLargerThanThePrecisionIsOutOfRange() {
        var failure = classify(1438, "ORA-01438: value larger than specified precision allowed for this column\n");
        assertEquals(NUMERIC_RANGE, failure.kind());
        assertTrue(failure.columns().isEmpty());
    }

    @Test
    void aDeferredConstraintFailingAtCommitIsClassifiedByTheNestedError() {
        var failure = classify(2091, "ORA-02091: transaction rolled back\nORA-02291: integrity constraint (TEST.IV_DEFERRED_PARENT_FK) violated - parent key not found\n");
        assertEquals(FOREIGN_KEY, failure.kind());
        assertEquals("IV_DEFERRED_PARENT_FK", failure.constraint());
        assertTrue(classifier.integrityFailure(new SQLException("ORA-02091: transaction rolled back\n", "40000", 2091)).isEmpty());
        assertTrue(classifier.integrityFailure(new SQLException("ORA-02091: transaction rolled back\nORA-00060: deadlock detected\n", "40000", 2091)).isEmpty());
    }

    @Test
    void aMessageWithoutNamesKeepsTheKind() {
        assertEquals(UNIQUE, classify(1, "changed").kind());
        assertNull(classify(1, "changed").constraint());
        assertTrue(classify(1400, "changed").columns().isEmpty());
    }

    @Test
    void anotherErrorIsNotAViolation() {
        assertTrue(classifier.integrityFailure(new SQLException("ORA-00060: deadlock detected", "61000", 60)).isEmpty());
        assertTrue(classifier.integrityFailure(new SQLException(null, "23000", 1)).isPresent());
    }
}
