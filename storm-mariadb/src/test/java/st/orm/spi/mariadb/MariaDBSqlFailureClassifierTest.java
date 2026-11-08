package st.orm.spi.mariadb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.IntegrityFailure.Kind.CHECK;
import static st.orm.core.template.IntegrityFailure.Kind.UNIQUE;

import java.sql.SQLException;
import org.junit.jupiter.api.Test;

/**
 * What the MariaDB classifier reads from the errors MariaDB reports for a constraint, beyond what MySQL's reads.
 */
class MariaDBSqlFailureClassifierTest {

    private final MariaDBSqlFailureClassifier classifier = new MariaDBSqlFailureClassifier();

    @Test
    void aCheckViolationNamesTheConstraintAndTheTable() {
        var failure = classifier.integrityFailure(new SQLException(
                "CONSTRAINT `iv_parent_amount_ck` failed for `test`.`iv_parent`", "23000", 4025)).orElseThrow();
        assertEquals(CHECK, failure.kind());
        assertEquals("iv_parent_amount_ck", failure.constraint());
        assertEquals("iv_parent", failure.table());
    }

    @Test
    void aCheckViolationWithoutTheNamesIsStillOne() {
        var failure = classifier.integrityFailure(new SQLException(null, "23000", 4025)).orElseThrow();
        assertEquals(CHECK, failure.kind());
        assertNull(failure.constraint());
    }

    @Test
    void theOtherErrorsAreReadAsMySQLReadsThem() {
        var failure = classifier.integrityFailure(new SQLException(
                "Duplicate entry 'A' for key 'iv_parent_code_uk'", "23000", 1062)).orElseThrow();
        assertEquals(UNIQUE, failure.kind());
        assertEquals("iv_parent_code_uk", failure.constraint());
        assertTrue(classifier.integrityFailure(new SQLException("Deadlock found", "40001", 1213)).isEmpty());
    }
}
