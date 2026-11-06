package st.orm.spi.mssqlserver;

import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.testcontainers.containers.MSSQLServerContainer;
import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class MSSQLServerSchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {
    private static MSSQLServerContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MSSQLServerContainer<>("mcr.microsoft.com/mssql/server:2019-latest")
                .acceptLicense();
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

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_folded AS (LOWER(name)) PERSISTED NOT NULL)";
    }
}
