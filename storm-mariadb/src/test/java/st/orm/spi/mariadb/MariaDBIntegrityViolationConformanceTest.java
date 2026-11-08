package st.orm.spi.mariadb;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MariaDBContainer;
import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against MariaDB. The suite commits, so it runs without the per-test
 * rollback.
 */
@StormTest(rollback = false)
public class MariaDBIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {
    private static MariaDBContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MariaDBContainer<>("mariadb:11.8");
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
     * MariaDB names the column of a value out of range.
     */
    @Override
    protected boolean namesColumnOfRangeViolation() {
        return true;
    }

    /**
     * MariaDB resolves an upsert on any unique key.
     */
    @Override
    protected boolean upsertResolvesOnUniqueKeys() {
        return true;
    }

    /**
     * The character set of the database decides whether a character column holds supplementary characters at all.
     */
    @Override
    protected boolean storesSupplementaryCharactersByCount() {
        return false;
    }
}
