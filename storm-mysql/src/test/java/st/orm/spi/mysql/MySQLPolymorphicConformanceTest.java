package st.orm.spi.mysql;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MySQLContainer;
import st.orm.tck.AbstractPolymorphicConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class MySQLPolymorphicConformanceTest extends AbstractPolymorphicConformanceTest {
    private static MySQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:9.2");
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
}
