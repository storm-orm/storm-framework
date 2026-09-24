package st.orm.spi.oracle;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.oracle.OracleContainer;
import st.orm.tck.AbstractPolymorphicConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class OraclePolymorphicConformanceTest extends AbstractPolymorphicConformanceTest {
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
}
