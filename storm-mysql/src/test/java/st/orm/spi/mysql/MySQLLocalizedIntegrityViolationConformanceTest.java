package st.orm.spi.mysql;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MySQLContainer;
import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against a MySQL server that reports its messages in German, so the
 * names are shown to be read from the quoted identifiers rather than from the words around them. The suite commits,
 * so it runs without the per-test rollback.
 */
@StormTest(rollback = false)
public class MySQLLocalizedIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {
    private static MySQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:9.2").withCommand("--lc-messages=de_DE");
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

    @Override
    protected boolean namesColumnOfRangeViolation() {
        return true;
    }

    @Override
    protected boolean upsertResolvesOnUniqueKeys() {
        return true;
    }
}
