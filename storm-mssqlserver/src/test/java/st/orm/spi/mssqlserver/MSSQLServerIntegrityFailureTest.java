package st.orm.spi.mssqlserver;

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
 * What the SQL Server classifier reads from the errors SQL Server reports for a constraint.
 */
class MSSQLServerIntegrityFailureTest {

    private final MSSQLServerIntegrityFailureClassifier classifier = new MSSQLServerIntegrityFailureClassifier();

    private IntegrityFailure classify(int errorCode, String message) {
        return classifier.classify(new SQLException(message, "23000", errorCode)).orElseThrow();
    }

    @Test
    void aUniqueKeyViolationNamesTheConstraintAndTheTable() {
        var failure = classify(2627, "Violation of UNIQUE KEY constraint 'iv_parent_code_uk'. Cannot insert duplicate key in object 'dbo.iv_parent'. The duplicate key value is (A).");
        assertEquals(UNIQUE, failure.kind());
        assertEquals("iv_parent_code_uk", failure.constraint());
        assertEquals("iv_parent", failure.table());
        assertFalse(failure.primaryKey());
    }

    @Test
    void aPrimaryKeyViolationIsMarkedAsOne() {
        var failure = classify(2627, "Violation of PRIMARY KEY constraint 'iv_parent_pk'. Cannot insert duplicate key in object 'dbo.iv_parent'. The duplicate key value is (1).");
        assertEquals(UNIQUE, failure.kind());
        assertEquals("iv_parent_pk", failure.constraint());
        assertTrue(failure.primaryKey());
    }

    @Test
    void aUniqueIndexViolationNamesTheIndex() {
        var failure = classify(2601, "Cannot insert duplicate key row in object 'dbo.iv_parent' with unique index 'iv_parent_code_ix'. The duplicate key value is (A).");
        assertEquals(UNIQUE, failure.kind());
        assertEquals("iv_parent_code_ix", failure.constraint());
        assertEquals("iv_parent", failure.table());
    }

    @Test
    void aMissingParentNamesTheConstraintWithoutTheParentTable() {
        var failure = classify(547, "The INSERT statement conflicted with the FOREIGN KEY constraint \"iv_child_parent_fk\". The conflict occurred in database \"test\", table \"dbo.iv_parent\", column 'id'.");
        assertEquals(FOREIGN_KEY, failure.kind());
        assertEquals("iv_child_parent_fk", failure.constraint());
        assertNull(failure.table(), "the table the message names is the parent's");
    }

    @Test
    void aReferencedRowNamesTheConstraintAndTheReferencingTable() {
        var failure = classify(547, "The DELETE statement conflicted with the REFERENCE constraint \"iv_child_parent_fk\". The conflict occurred in database \"test\", table \"dbo.iv_child\", column 'parent_id'.");
        assertEquals(REFERENCED, failure.kind());
        assertEquals("iv_child_parent_fk", failure.constraint());
        assertEquals("iv_child", failure.table());
    }

    @Test
    void aCheckViolationNamesTheConstraintAndTheTable() {
        var failure = classify(547, "The INSERT statement conflicted with the CHECK constraint \"iv_parent_amount_ck\". The conflict occurred in database \"test\", table \"dbo.iv_parent\", column 'amount'.");
        assertEquals(CHECK, failure.kind());
        assertEquals("iv_parent_amount_ck", failure.constraint());
        assertEquals("iv_parent", failure.table());
    }

    @Test
    void aConflictWithAnUnknownConstraintKindIsNotAViolation() {
        assertTrue(classifier.classify(new SQLException("The statement has been terminated.", "23000", 547)).isEmpty());
    }

    @Test
    void aNullNamesTheColumnAndTheTable() {
        var failure = classify(515, "Cannot insert the value NULL into column 'code', table 'test.dbo.iv_parent'; column does not allow nulls. INSERT fails.");
        assertEquals(NOT_NULL, failure.kind());
        assertEquals(List.of("code"), failure.columns());
        assertEquals("iv_parent", failure.table());
    }

    @Test
    void aTruncationNamesTheColumnWhereTheMessageHasIt() {
        var failure = classify(2628, "String or binary data would be truncated in table 'test.dbo.iv_parent', column 'code'. Truncated value: 'ABCDE'.");
        assertEquals(LENGTH, failure.kind());
        assertEquals(List.of("code"), failure.columns());
        assertEquals("iv_parent", failure.table());
        var unnamed = classify(8152, "String or binary data would be truncated.");
        assertEquals(LENGTH, unnamed.kind());
        assertTrue(unnamed.columns().isEmpty());
    }

    @Test
    void anArithmeticOverflowIsOutOfRange() {
        assertEquals(NUMERIC_RANGE, classify(8115, "Arithmetic overflow error converting numeric to data type numeric.").kind());
        assertEquals(NUMERIC_RANGE, classify(220, "Arithmetic overflow error for data type tinyint, value = 256.").kind());
    }

    @Test
    void aMessageInAnotherLanguageKeepsItsQuotedNamesAndSqlTerms() {
        var duplicate = classify(2627, "Verletzung der UNIQUE KEY-Einschränkung 'iv_parent_code_uk'. Ein doppelter Schlüssel kann in das Objekt 'dbo.iv_parent' nicht eingefügt werden. Der doppelte Schlüsselwert ist (A).");
        assertEquals("iv_parent_code_uk", duplicate.constraint());
        assertEquals("iv_parent", duplicate.table());
        var referenced = classify(547, "Die DELETE-Anweisung steht in Konflikt mit der REFERENCE-Einschränkung \"iv_child_parent_fk\". Der Konflikt trat in der Datenbank \"test\", Tabelle \"dbo.iv_child\", column 'parent_id' auf.");
        assertEquals(REFERENCED, referenced.kind());
        assertEquals("iv_child_parent_fk", referenced.constraint());
        assertEquals("iv_child", referenced.table());
        var nullValue = classify(515, "Der Wert NULL kann in die Spalte 'code', Tabelle 'test.dbo.iv_parent' nicht eingefügt werden. Die Spalte lässt NULL-Werte nicht zu.");
        assertEquals(List.of("code"), nullValue.columns());
        assertEquals("iv_parent", nullValue.table());
    }

    @Test
    void anotherErrorIsNotAViolation() {
        assertTrue(classifier.classify(new SQLException("deadlock victim", "40001", 1205)).isEmpty());
    }
}
