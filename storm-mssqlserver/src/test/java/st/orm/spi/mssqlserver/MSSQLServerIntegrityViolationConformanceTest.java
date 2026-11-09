package st.orm.spi.mssqlserver;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.tck.AbstractIntegrityViolationConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

/**
 * Runs the integrity violation conformance suite against SQL Server. The suite commits, so it runs without the
 * per-test rollback.
 */
@StormTest(rollback = false)
public class MSSQLServerIntegrityViolationConformanceTest extends AbstractIntegrityViolationConformanceTest {
    private static MSSQLServerContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-latest")
                .acceptLicense();
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
     * A {@code VARCHAR} column holds no supplementary characters: SQL Server converts each to two replacement
     * characters, so five of them do not fit a column of five.
     */
    @Override
    protected boolean storesSupplementaryCharactersByCount() {
        return false;
    }
}
