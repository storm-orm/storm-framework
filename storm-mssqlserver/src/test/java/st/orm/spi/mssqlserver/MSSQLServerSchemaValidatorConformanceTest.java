package st.orm.spi.mssqlserver;

import javax.sql.DataSource;
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

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_folded AS (LOWER(name)) PERSISTED NOT NULL)";
    }
}
