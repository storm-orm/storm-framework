package st.orm.spi.postgresql;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import st.orm.StormConfig;
import st.orm.core.spi.Providers;
import st.orm.core.template.impl.DatabaseSchema;
import st.orm.tck.AbstractSchemaValidatorConformanceTest;
import st.orm.tck.ContainerDataSource;
import st.orm.test.StormTest;

@StormTest(scripts = "/data.sql")
public class PostgreSQLSchemaValidatorConformanceTest extends AbstractSchemaValidatorConformanceTest {
    private static PostgreSQLContainer<?> container;

    public static synchronized DataSource dataSource() {
        if (container == null) {
            container = new PostgreSQLContainer<>("postgres:17");
            container.start();
        }
        return ContainerDataSource.of(container.getJdbcUrl(), container.getUsername(), container.getPassword());
    }

    @Override
    protected String expressionIndexDdl() {
        return "CREATE UNIQUE INDEX index_probe_lower ON index_probe ((lower(a)))";
    }

    @Test
    public void testAPartialIndexIsMarkedAndAViewIndexIsLeftOut() throws SQLException {
        try (var connection = dataSource.getConnection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE index_partial (id INTEGER NOT NULL PRIMARY KEY, a VARCHAR(50), c INTEGER)");
            statement.execute("CREATE MATERIALIZED VIEW index_partial_view AS SELECT id, a FROM index_partial");
            try {
                statement.execute("CREATE INDEX index_partial_a ON index_partial (a) WHERE c > 0");
                statement.execute("CREATE INDEX index_partial_view_a ON index_partial_view (a)");
                var dialect = Providers.getSqlDialect(connection, StormConfig.defaults());
                var schema = DatabaseSchema.read(connection, connection.getCatalog(), connection.getSchema(),
                    DatabaseSchema.Discovery.of(dialect));
                var partial = schema.getIndexes("index_partial").stream()
                        .filter(index -> index.indexName().equals("index_partial_a"))
                        .toList();
                assertEquals(1, partial.size(), () -> schema.getIndexes("index_partial").toString());
                assertTrue(partial.getFirst().partial());
                // A materialized view is not a table of the schema, so its index is not read.
                assertTrue(schema.getIndexes("index_partial_view").isEmpty());
            } finally {
                statement.execute("DROP MATERIALIZED VIEW index_partial_view");
                statement.execute("DROP TABLE index_partial");
            }
        }
    }

    @Override
    protected String generatedColumnProbeDdl() {
        return "CREATE TABLE generated_probe (id INTEGER NOT NULL PRIMARY KEY, name VARCHAR(50) NOT NULL, name_folded VARCHAR(50) GENERATED ALWAYS AS (LOWER(name)) STORED NOT NULL)";
    }
}
