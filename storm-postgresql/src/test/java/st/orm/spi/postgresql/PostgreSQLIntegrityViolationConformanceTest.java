package st.orm.spi.postgresql;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.PostgreSQLContainer;
import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against PostgreSQL. The suite commits, so it runs without the
 * per-test rollback.
 */
@StormTest(rollback = false)
public class PostgreSQLIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {
    private static PostgreSQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:17");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    /**
     * Stops the database when the class is done: each test class starts its own, and one left running holds its
     * memory until the module's tests end.
     */
    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /**
     * PostgreSQL reports a value too long for its column without the column.
     */
    @Override
    protected boolean namesColumnOfLengthViolation() {
        return false;
    }

    /**
     * PostgreSQL checks a deferred foreign key at commit.
     */
    @Override
    protected String deferredForeignKeyDdl() {
        return "CREATE TABLE " + DEFERRED_TABLE + " (id INTEGER NOT NULL, parent_id INTEGER NOT NULL, "
                + "CONSTRAINT iv_deferred_pk PRIMARY KEY (id), "
                + "CONSTRAINT iv_deferred_parent_fk FOREIGN KEY (parent_id) REFERENCES " + PARENT_TABLE + " (id) "
                + "DEFERRABLE INITIALLY DEFERRED)";
    }
}
