package st.orm.tck;

import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.Properties;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * A data source over {@link DriverManager}, for a conformance suite that starts its own container and hands the
 * connection details to {@code @StormTest} through a static {@code dataSource()} method.
 */
public final class ContainerDataSource implements DataSource {

    /**
     * Oracle's error for a listener that finds no free handler for the service. The listener counts the server
     * processes from the load the database reports every few seconds, so a suite that opens and closes connections in
     * quick succession meets it while the processes it closed are still counted; it clears within about a second.
     */
    private static final int ORACLE_NO_HANDLER = 12516;
    private static final long ORACLE_RETRY_MILLIS = 5_000;
    private static final long ORACLE_RETRY_INTERVAL_MILLIS = 100;

    private final String url;
    private final Properties properties = new Properties();

    private ContainerDataSource(String url, String username, String password) {
        this.url = url;
        properties.setProperty("user", username);
        properties.setProperty("password", password);
    }

    public static DataSource of(String url, String username, String password) {
        return new ContainerDataSource(url, username, password);
    }

    @Override
    public Connection getConnection() throws SQLException {
        return connect(properties);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        var credentials = new Properties();
        credentials.setProperty("user", username);
        credentials.setProperty("password", password);
        return connect(credentials);
    }

    /**
     * Opens a connection, waiting out an Oracle listener that has no handler free yet.
     */
    private Connection connect(Properties credentials) throws SQLException {
        long deadline = System.nanoTime() + ORACLE_RETRY_MILLIS * 1_000_000;
        while (true) {
            try {
                return DriverManager.getConnection(url, credentials);
            } catch (SQLException e) {
                if (!url.startsWith("jdbc:oracle:") || e.getErrorCode() != ORACLE_NO_HANDLER
                        || System.nanoTime() > deadline) {
                    throw e;
                }
                try {
                    Thread.sleep(ORACLE_RETRY_INTERVAL_MILLIS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    @Override
    public PrintWriter getLogWriter() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setLogWriter(PrintWriter out) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setLoginTimeout(int seconds) {
        throw new UnsupportedOperationException();
    }

    @Override
    public int getLoginTimeout() {
        return 0;
    }

    @Override
    public Logger getParentLogger() throws SQLFeatureNotSupportedException {
        throw new SQLFeatureNotSupportedException();
    }

    @Override
    public <T> T unwrap(Class<T> iface) throws SQLException {
        if (iface.isInstance(this)) {
            return iface.cast(this);
        }
        throw new SQLException("Not a wrapper for " + iface.getName() + ".");
    }

    @Override
    public boolean isWrapperFor(Class<?> iface) {
        return iface.isInstance(this);
    }
}
