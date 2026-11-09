package st.orm.spi.oracle;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against Oracle. The suite commits, so it runs without the per-test
 * rollback.
 */
@StormTest(rollback = false)
public class OracleIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {

    private static final int TABLE_DEFINITION_HAS_CHANGED = 1466;
    private static final long SNAPSHOT_TIMEOUT_NANOS = SECONDS.toNanos(30);
    private static final long SNAPSHOT_RETRY_MILLIS = 100;

    private static OracleContainer container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new OracleContainer("gvenzl/oracle-free:23");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Stops the database when the class is done: each Oracle test class starts its own, and one left running slows
     * every start after it toward the startup timeout.
     */
    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /**
     * A serializable transaction reads from one snapshot, and refuses a table whose definition changed in the second
     * that snapshot was taken with ORA-01466. The suite creates its tables moments before each case, so a
     * serializable read is repeated here until its snapshot clears the tables' creation.
     */
    @Override
    protected void awaitTables() throws SQLException, InterruptedException {
        long started = System.nanoTime();
        while (true) {
            try (var connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (var statement = connection.createStatement()) {
                    statement.execute("SET TRANSACTION ISOLATION LEVEL SERIALIZABLE");
                    try (var resultSet = statement.executeQuery("SELECT COUNT(*) FROM " + CHILD_TABLE)) {
                        resultSet.next();
                    }
                }
                connection.commit();
                return;
            } catch (SQLException e) {
                if (e.getErrorCode() != TABLE_DEFINITION_HAS_CHANGED
                        || System.nanoTime() - started > SNAPSHOT_TIMEOUT_NANOS) {
                    throw e;
                }
            }
            Thread.sleep(SNAPSHOT_RETRY_MILLIS);
        }
    }

    /**
     * Oracle measures a character column in bytes by default, so five supplementary characters do not fit a column
     * of five.
     */
    @Override
    protected boolean storesSupplementaryCharactersByCount() {
        return false;
    }

    /**
     * Oracle checks a deferred foreign key at commit.
     */
    @Override
    protected String deferredForeignKeyDdl() {
        return "CREATE TABLE " + DEFERRED_TABLE + " (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, "
                + "CONSTRAINT iv_deferred_pk PRIMARY KEY (id), "
                + "CONSTRAINT iv_deferred_parent_fk FOREIGN KEY (parent_id) REFERENCES " + PARENT_TABLE + " (id) "
                + "DEFERRABLE INITIALLY DEFERRED)";
    }
}
