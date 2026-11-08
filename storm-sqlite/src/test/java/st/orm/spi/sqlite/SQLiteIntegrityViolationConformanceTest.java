package st.orm.spi.sqlite;

import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against SQLite, with foreign keys enforced, which SQLite leaves off
 * unless the connection asks for them. The suite commits, so it runs without the per-test rollback.
 */
@StormTest(url = "jdbc:sqlite:target/integrity-violation.db?foreign_keys=true", rollback = false)
public class SQLiteIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {

    /**
     * SQLite names the columns of a duplicate, not the constraint.
     */
    @Override
    protected boolean reportsUniqueConstraintName() {
        return false;
    }

    /**
     * SQLite reports a foreign key violation without the constraint.
     */
    @Override
    protected boolean reportsForeignKeyConstraintName() {
        return false;
    }

    /**
     * SQLite checks a deferred foreign key at commit.
     */
    @Override
    protected String deferredForeignKeyDdl() {
        return "CREATE TABLE " + DEFERRED_TABLE + " (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, "
                + "CONSTRAINT iv_deferred_pk PRIMARY KEY (id), "
                + "CONSTRAINT iv_deferred_parent_fk FOREIGN KEY (parent_id) REFERENCES " + PARENT_TABLE + " (id) "
                + "DEFERRABLE INITIALLY DEFERRED)";
    }
}
