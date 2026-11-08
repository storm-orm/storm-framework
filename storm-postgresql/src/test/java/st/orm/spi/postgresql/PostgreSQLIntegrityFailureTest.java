package st.orm.spi.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static st.orm.core.template.IntegrityFailure.Kind.CHECK;
import static st.orm.core.template.IntegrityFailure.Kind.FOREIGN_KEY;
import static st.orm.core.template.IntegrityFailure.Kind.LENGTH;
import static st.orm.core.template.IntegrityFailure.Kind.NOT_NULL;
import static st.orm.core.template.IntegrityFailure.Kind.NUMERIC_RANGE;
import static st.orm.core.template.IntegrityFailure.Kind.UNIQUE;

import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.postgresql.util.PSQLState;
import org.postgresql.util.ServerErrorMessage;

/**
 * What the PostgreSQL classifier reads from the errors PostgreSQL reports for a constraint: the SQL state decides the
 * kind, and the server's error fields, which the driver exposes, carry the names.
 */
class PostgreSQLIntegrityFailureTest {

    private final PostgreSQLIntegrityFailureClassifier classifier = new PostgreSQLIntegrityFailureClassifier();

    /**
     * Builds the driver's exception from the fields the server sends: the severity, SQL state, message and, where
     * the error carries them, the table, column and constraint, each introduced by its field code.
     */
    private static PSQLException serverError(String sqlState, String message, String... fields) {
        var encoded = new StringBuilder("S" + "ERROR" + '\0' + "C" + sqlState + '\0' + "M" + message + '\0');
        for (var field : fields) {
            encoded.append(field).append('\0');
        }
        return new PSQLException(new ServerErrorMessage(encoded.toString()));
    }

    @Test
    void aUniqueViolationCarriesTheConstraintAndTheTable() {
        var failure = classifier.classify(serverError("23505",
                "duplicate key value violates unique constraint \"iv_parent_code_uk\"",
                "tiv_parent", "niv_parent_code_uk", "spublic")).orElseThrow();
        assertEquals(UNIQUE, failure.kind());
        assertEquals("iv_parent_code_uk", failure.constraint());
        assertEquals("iv_parent", failure.table());
        assertTrue(failure.columns().isEmpty());
    }

    @Test
    void aForeignKeyViolationCarriesTheConstraintAndTheReferencingTable() {
        var failure = classifier.classify(serverError("23503",
                "update or delete on table \"iv_parent\" violates foreign key constraint \"iv_child_parent_fk\" on table \"iv_child\"",
                "tiv_child", "niv_child_parent_fk")).orElseThrow();
        assertEquals(FOREIGN_KEY, failure.kind());
        assertEquals("iv_child_parent_fk", failure.constraint());
        assertEquals("iv_child", failure.table());
    }

    @Test
    void aNullCarriesTheColumn() {
        var failure = classifier.classify(serverError("23502",
                "null value in column \"code\" of relation \"iv_parent\" violates not-null constraint",
                "tiv_parent", "ccode")).orElseThrow();
        assertEquals(NOT_NULL, failure.kind());
        assertEquals(List.of("code"), failure.columns());
        assertEquals("iv_parent", failure.table());
    }

    @Test
    void aCheckViolationCarriesTheConstraint() {
        var failure = classifier.classify(serverError("23514",
                "new row for relation \"iv_parent\" violates check constraint \"iv_parent_amount_ck\"",
                "tiv_parent", "niv_parent_amount_ck")).orElseThrow();
        assertEquals(CHECK, failure.kind());
        assertEquals("iv_parent_amount_ck", failure.constraint());
    }

    @Test
    void aValueTooLongCarriesNoColumn() {
        var failure = classifier.classify(serverError("22001",
                "value too long for type character varying(5)")).orElseThrow();
        assertEquals(LENGTH, failure.kind());
        assertTrue(failure.columns().isEmpty());
        assertNull(failure.table());
    }

    @Test
    void aNumericOverflowIsOutOfRange() {
        assertEquals(NUMERIC_RANGE, classifier.classify(serverError("22003",
                "numeric field overflow")).orElseThrow().kind());
    }

    @Test
    void anErrorWithoutServerFieldsKeepsTheKind() {
        var failure = classifier.classify(new SQLException("duplicate", "23505")).orElseThrow();
        assertEquals(UNIQUE, failure.kind());
        assertNull(failure.constraint());
        var withoutServerError = classifier.classify(
                new PSQLException("duplicate", PSQLState.UNIQUE_VIOLATION)).orElseThrow();
        assertEquals(UNIQUE, withoutServerError.kind());
        assertNull(withoutServerError.constraint());
    }

    @Test
    void anotherErrorIsNotAViolation() {
        assertTrue(classifier.classify(new SQLException("deadlock", "40P01")).isEmpty());
    }
}
