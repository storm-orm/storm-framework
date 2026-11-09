package st.orm.spi.mysql;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MySQLContainer;
import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against MySQL. The suite commits, so it runs without the per-test
 * rollback.
 */
@StormTest(rollback = false)
public class MySQLIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {
    private static MySQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:9.2");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @AfterAll
    static synchronized void stopContainer() {
        if (container != null) {
            container.stop();
            container = null;
        }
    }

    /**
     * MySQL names the column of a value out of range.
     */
    @Override
    protected boolean namesColumnOfRangeViolation() {
        return true;
    }

    /**
     * MySQL resolves an upsert on any unique key.
     */
    @Override
    protected boolean upsertResolvesOnUniqueKeys() {
        return true;
    }
}
