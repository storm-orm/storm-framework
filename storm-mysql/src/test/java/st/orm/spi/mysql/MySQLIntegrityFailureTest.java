package st.orm.spi.mysql;

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
 * What the MySQL classifier reads from the errors MySQL reports for a constraint.
 */
class MySQLIntegrityFailureTest {

    private final MySQLIntegrityFailureClassifier classifier = new MySQLIntegrityFailureClassifier();

    private IntegrityFailure classify(int errorCode, String message) {
        return classifier.classify(new SQLException(message, "23000", errorCode)).orElseThrow();
    }

    @Test
    void aDuplicateNamesTheKeyWithoutItsTable() {
        var failure = classify(1062, "Duplicate entry 'A' for key 'iv_parent.iv_parent_code_uk'");
        assertEquals(UNIQUE, failure.kind());
        assertEquals("iv_parent_code_uk", failure.constraint());
        assertFalse(failure.primaryKey());
    }

    @Test
    void aDuplicateUnderThePrimaryKeyIsMarkedAsOne() {
        var failure = classify(1062, "Duplicate entry '1' for key 'iv_parent.PRIMARY'");
        assertEquals(UNIQUE, failure.kind());
        assertTrue(failure.primaryKey());
        assertNull(failure.constraint());
    }

    @Test
    void aMissingParentNamesTheConstraintAndTheReferencingTable() {
        var failure = classify(1452, "Cannot add or update a child row: a foreign key constraint fails (`test`.`iv_child`, CONSTRAINT `iv_child_parent_fk` FOREIGN KEY (`parent_id`) REFERENCES `iv_parent` (`id`))");
        assertEquals(FOREIGN_KEY, failure.kind());
        assertEquals("iv_child_parent_fk", failure.constraint());
        assertEquals("iv_child", failure.table());
    }

    @Test
    void aReferencedRowNamesTheConstraintAndTheReferencingTable() {
        var failure = classify(1451, "Cannot delete or update a parent row: a foreign key constraint fails (`test`.`iv_child`, CONSTRAINT `iv_child_parent_fk` FOREIGN KEY (`parent_id`) REFERENCES `iv_parent` (`id`))");
        assertEquals(REFERENCED, failure.kind());
        assertEquals("iv_child_parent_fk", failure.constraint());
        assertEquals("iv_child", failure.table());
    }

    @Test
    void aNullNamesTheColumn() {
        assertEquals(List.of("code"), classify(1048, "Column 'code' cannot be null").columns());
        var failure = classify(1364, "Field 'code' doesn't have a default value");
        assertEquals(NOT_NULL, failure.kind());
        assertEquals(List.of("code"), failure.columns());
    }

    @Test
    void aCheckViolationNamesTheConstraint() {
        var failure = classify(3819, "Check constraint 'iv_parent_amount_ck' is violated.");
        assertEquals(CHECK, failure.kind());
        assertEquals("iv_parent_amount_ck", failure.constraint());
    }

    @Test
    void aValueTooLongNamesTheColumn() {
        var failure = classify(1406, "Data too long for column 'code' at row 1");
        assertEquals(LENGTH, failure.kind());
        assertEquals(List.of("code"), failure.columns());
    }

    @Test
    void aValueOutOfRangeNamesTheColumn() {
        var failure = classify(1264, "Out of range value for column 'amount' at row 1");
        assertEquals(NUMERIC_RANGE, failure.kind());
        assertEquals(List.of("amount"), failure.columns());
    }

    @Test
    void aMessageInAnotherLanguageKeepsItsQuotedNames() {
        var duplicate = classify(1062, "Doppelter Eintrag 'A' für Schlüssel 'iv_parent.iv_parent_code_uk'");
        assertEquals(UNIQUE, duplicate.kind());
        assertEquals("iv_parent_code_uk", duplicate.constraint());
        assertTrue(classify(1062, "Doppelter Eintrag '1' für Schlüssel 'iv_parent.PRIMARY'").primaryKey());
        assertEquals(List.of("code"), classify(1048, "Feld 'code' darf nicht NULL sein").columns());
        assertEquals(List.of("code"), classify(1406, "Daten zu lang für Feld 'code' in Zeile 1").columns());
        assertEquals("iv_parent_amount_ck", classify(3819, "Die CHECK-Bedingung 'iv_parent_amount_ck' ist verletzt.").constraint());
        var missing = classify(1452, "Kann Kind-Zeile nicht hinzufügen oder aktualisieren: eine Fremdschlüsselbedingung schlägt fehl (`test`.`iv_child`, CONSTRAINT `iv_child_parent_fk` FOREIGN KEY (`parent_id`) REFERENCES `iv_parent` (`id`))");
        assertEquals("iv_child_parent_fk", missing.constraint());
        assertEquals("iv_child", missing.table());
    }

    @Test
    void anotherErrorIsNotAViolation() {
        assertTrue(classifier.classify(new SQLException("Deadlock found", "40001", 1213)).isEmpty());
        assertTrue(classifier.classify(new SQLException(null, "23000", 1062)).isPresent());
    }
}
