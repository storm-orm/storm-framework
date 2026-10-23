package st.orm.spi.mysql;

import javax.sql.DataSource;
import org.testcontainers.containers.MySQLContainer;
import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class MySQLSchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {
    private static MySQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new MySQLContainer<>("mysql:9.2");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected boolean supportsSequences() {
        return false;
    }

    @Override
    protected String expressionIndexDdl() {
        return "CREATE UNIQUE INDEX index_probe_lower ON index_probe ((lower(a)))";
    }

    @Override
    protected boolean indexesForeignKeys() {
        return true;
    }

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_folded VARCHAR(50) AS (LOWER(name)) STORED NOT NULL)";
    }
}
