package st.orm.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.DriverManager;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import st.orm.Entity;
import st.orm.PK;
import st.orm.core.template.ORMTemplate;

/**
 * Tests that {@link StormExtension} uses a static {@code dataSource()} factory method on the test class when present,
 * instead of creating a default H2 DataSource from the {@link StormTest} annotation attributes.
 */
@StormTest(scripts = {"/test-schema.sql", "/test-data.sql"})
class StormExtensionDataSourceFactoryTest {

    private static final String CUSTOM_DB_NAME = "custom_datasource_factory_test";

    record Item(@PK Integer id, String name) implements Entity<Integer> {}

    static DataSource dataSource() {
        String url = "jdbc:h2:mem:" + CUSTOM_DB_NAME + ";DB_CLOSE_DELAY=-1";
        return new SimpleTestDataSource(url, "sa", "");
    }

    @Test
    void shouldUseDataSourceFromFactoryMethod(DataSource dataSource) throws Exception {
        try (var conn = dataSource.getConnection()) {
            var url = conn.getMetaData().getURL();
            assertTrue(url.contains(CUSTOM_DB_NAME),
                    "Expected connection URL to contain '" + CUSTOM_DB_NAME + "' but got: " + url);
        }
    }

    @Test
    void scriptsShouldExecuteAgainstFactoryDataSource(ORMTemplate orm) {
        var items = orm.entity(Item.class).findAll();
        assertEquals(3, items.size());
    }

    /**
     * Minimal DataSource implementation for testing.
     */
    private record SimpleTestDataSource(String url, String username, String password) implements DataSource {

        @Override
        public java.sql.Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public java.sql.Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public java.io.PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(java.io.PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
        }

        @Override
        public int getLoginTimeout() {
            return 0;
        }

        @Override
        public java.util.logging.Logger getParentLogger() {
            return null;
        }

        @Override
        public <T> T unwrap(Class<T> iface) {
            return null;
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
