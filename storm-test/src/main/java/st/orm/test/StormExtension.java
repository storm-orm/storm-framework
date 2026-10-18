/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package st.orm.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionConfigurationException;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import st.orm.core.template.impl.AdviceLog;

/**
 * JUnit 5 extension that provides automatic {@link DataSource} creation and parameter injection for Storm tests.
 *
 * <p>This extension is normally activated via the {@link StormTest} annotation. It supports injecting the following
 * parameter types into test methods:</p>
 * <ul>
 *     <li>{@link DataSource} -- the test database connection</li>
 *     <li>{@link SqlCapture} -- a fresh capture instance for verifying SQL statements</li>
 *     <li>Any type with a static {@code of(DataSource)} factory method or a Kotlin companion object
 *         {@code of(DataSource)} method (e.g., {@code ORMTemplate})</li>
 * </ul>
 *
 * <p>By default, the extension creates an H2 in-memory database. {@link StormTest#database()} runs the tests on a
 * database in a Testcontainers-managed container instead, shared by the test classes of the run, with a fresh
 * database inside it per test class. To use a custom {@link DataSource} of your own, define a static
 * {@code dataSource()} method on the test class that returns a {@link DataSource}. When present, this method takes
 * precedence over the {@code url}, {@code username}, and {@code password} attributes of {@link StormTest}. SQL
 * scripts specified in {@link StormTest#scripts()} are executed against whichever database the class ends up
 * with.</p>
 *
 * <p>Unless {@link StormTest#rollback()} is disabled, each test runs inside a database transaction that is rolled
 * back after the test: the {@link DataSource} injected into test methods (and used by types created from it, such as
 * {@code ORMTemplate}) hands out connections that share one database transaction for the duration of the test.
 * Parameters injected into {@code @BeforeAll} methods resolve to the unwrapped {@link DataSource}, so class-level
 * setup commits.</p>
 *
 * @since 1.9
 */
public class StormExtension implements BeforeAllCallback, BeforeEachCallback, AfterEachCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(StormExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        // A test is development: design and performance advice report unless the application named the categories.
        AdviceLog.development();
        Class<?> testClass = context.getRequiredTestClass();
        StormTest annotation = testClass.getAnnotation(StormTest.class);
        if (annotation == null) {
            return;
        }
        DataSource dataSource;
        if (annotation.database().isContainer()) {
            dataSource = containerDataSource(context, testClass, annotation);
        } else {
            if (!annotation.image().isEmpty()) {
                throw new ExtensionConfigurationException("@StormTest on " + testClass.getName()
                        + " names image " + annotation.image() + " but no container database; set database to the "
                        + "database the image runs.");
            }
            DataSourceFactory factory = findDataSourceFactory(testClass);
            if (factory != null) {
                dataSource = factory.create();
            } else if (annotation.url().isEmpty()) {
                // The nanoTime suffix keeps equally named test classes in different packages, and repeated runs of
                // the same class within one JVM, from sharing a database.
                String url = "jdbc:h2:mem:" + testClass.getSimpleName() + "-" + System.nanoTime()
                        + ";DB_CLOSE_DELAY=-1";
                dataSource = new SimpleDataSource(url, annotation.username(), annotation.password());
                // DB_CLOSE_DELAY=-1 keeps the database alive for the JVM lifetime; shut it down when the class-level
                // store closes. Only the database created here is shut down, never a user-provided one.
                getStore(context).put(DatabaseShutdown.class, new DatabaseShutdown(dataSource));
            } else {
                dataSource = new SimpleDataSource(annotation.url(), annotation.username(), annotation.password());
            }
        }
        if (annotation.scripts().length > 0) {
            try (Connection conn = dataSource.getConnection()) {
                for (String script : annotation.scripts()) {
                    String sql = readScript(testClass, script);
                    executeScript(conn, sql);
                }
            }
        }
        getStore(context).put(DataSource.class, dataSource);
    }

    /**
     * Provisions a fresh database for the test class inside the shared container of the annotation's database and
     * image, dropped when the class-level store closes.
     */
    private DataSource containerDataSource(ExtensionContext context, Class<?> testClass, StormTest annotation)
            throws Exception {
        if (!annotation.url().isEmpty()) {
            throw new ExtensionConfigurationException("@StormTest on " + testClass.getName() + " sets both database "
                    + annotation.database() + " and url " + annotation.url() + "; a container database has its own "
                    + "URL.");
        }
        if (findDataSourceFactory(testClass) != null) {
            throw new ExtensionConfigurationException("@StormTest on " + testClass.getName() + " sets database "
                    + annotation.database() + " while the class declares a static dataSource() factory method; a "
                    + "container database has its own DataSource.");
        }
        TestDatabase database = annotation.database();
        DatabaseContainer container = annotation.image().isEmpty()
                ? database.container()
                : database.container(annotation.image());
        DatabaseContainer.Database provisioned = container.createDatabase();
        getStore(context).put(DatabaseDrop.class, new DatabaseDrop(provisioned));
        return provisioned.dataSource();
    }

    @Override
    public void beforeEach(ExtensionContext context) {
        StormTest annotation = findAnnotation(context.getRequiredTestClass());
        if (annotation == null || !annotation.rollback()) {
            return;
        }
        DataSource dataSource = getStore(context).get(DataSource.class, DataSource.class);
        if (dataSource == null) {
            // The extension is active without @StormTest; there is no database to wrap.
            return;
        }
        // The wrapper lands in the method-level store, so each test gets its own transaction and lookups from the
        // class-level context (@BeforeAll parameters) keep resolving to the unwrapped DataSource.
        getStore(context).put(TestTransaction.class, new TestTransaction(dataSource));
    }

    @Override
    public void afterEach(ExtensionContext context) throws Exception {
        TestTransaction transaction = getStore(context).get(TestTransaction.class, TestTransaction.class);
        if (transaction != null) {
            transaction.rollback();
        }
    }

    /**
     * Returns the {@link StormTest} annotation governing the given test class: the annotation on the class or one of
     * its superclasses (it is {@code @Inherited}), or on an enclosing class for {@code @Nested} test classes.
     */
    private static StormTest findAnnotation(Class<?> testClass) {
        for (Class<?> type = testClass; type != null; type = type.getEnclosingClass()) {
            StormTest annotation = type.getAnnotation(StormTest.class);
            if (annotation != null) {
                return annotation;
            }
        }
        return null;
    }

    @Override
    public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx)
            throws ParameterResolutionException {
        Class<?> type = paramCtx.getParameter().getType();
        if (type == DataSource.class || type == SqlCapture.class || type == SchemaValidation.class) {
            return true;
        }
        return hasFactoryMethod(type);
    }

    @Override
    public Object resolveParameter(ParameterContext paramCtx, ExtensionContext extCtx)
            throws ParameterResolutionException {
        Class<?> type = paramCtx.getParameter().getType();
        if (type == SqlCapture.class) {
            return new SqlCapture();
        }
        DataSource dataSource = getDataSource(extCtx);
        if (type == DataSource.class) {
            return dataSource;
        }
        if (type == SchemaValidation.class) {
            return new SchemaValidation(dataSource);
        }
        try {
            return invokeFactoryMethod(type, dataSource);
        } catch (Exception e) {
            throw new ParameterResolutionException(
                    "Failed to create " + type.getName() + " via reflective factory method.", e);
        }
    }

    private DataSource getDataSource(ExtensionContext context) {
        // Method-level contexts see the test's transactional wrapper; class-level contexts see the raw DataSource.
        DataSource testTransaction = getStore(context).get(TestTransaction.class, TestTransaction.class);
        if (testTransaction != null) {
            return testTransaction;
        }
        return getStore(context).get(DataSource.class, DataSource.class);
    }

    /**
     * Returns the store for the given context. In {@link #beforeAll} the context is the class-level context, so the
     * {@link DataSource} is stored per test class; concurrently executing test classes never see each other's entry.
     * Lookups from method-level contexts fall back to ancestor stores, which also makes the entry visible to
     * {@code @Nested} test classes.
     */
    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getStore(NAMESPACE);
    }

    /**
     * A static {@code dataSource()} factory method declared by a test class, or by the companion object of a Kotlin
     * test class, in which case {@code target} is the companion.
     */
    private record DataSourceFactory(Method method, @Nullable Object target) {

        DataSource create() throws Exception {
            return (DataSource) method.invoke(target);
        }
    }

    /**
     * Looks for a static {@code dataSource()} method on the test class or one of its superclasses. This also checks
     * for a Kotlin companion object with a {@code dataSource()} method.
     *
     * <p>Superclasses are searched because {@link StormTest} is {@code @Inherited}: the annotation and the factory
     * method may both live on an abstract base class while the extension runs for the concrete subclass.</p>
     *
     * @return the factory method, or {@code null} if no such method exists.
     */
    private static @Nullable DataSourceFactory findDataSourceFactory(Class<?> testClass) throws Exception {
        // Check for a Java static method, nearest declaration first.
        for (Class<?> type = testClass; type != null && type != Object.class; type = type.getSuperclass()) {
            try {
                Method method = type.getDeclaredMethod("dataSource");
                if (Modifier.isStatic(method.getModifiers())
                        && DataSource.class.isAssignableFrom(method.getReturnType())) {
                    method.setAccessible(true);
                    return new DataSourceFactory(method, null);
                }
            } catch (NoSuchMethodException ignored) {
            }
        }
        // Check for a Kotlin companion object with a dataSource() method.
        try {
            Field companion = testClass.getField("Companion");
            Object companionObject = companion.get(null);
            Method method = companionObject.getClass().getMethod("dataSource");
            if (DataSource.class.isAssignableFrom(method.getReturnType())) {
                return new DataSourceFactory(method, companionObject);
            }
        } catch (NoSuchFieldException | NoSuchMethodException ignored) {
        }
        return null;
    }

    private static boolean hasFactoryMethod(Class<?> type) {
        // Check for a Java static interface/class method.
        try {
            Method m = type.getMethod("of", DataSource.class);
            if (Modifier.isStatic(m.getModifiers())) {
                return true;
            }
        } catch (NoSuchMethodException ignored) {
        }
        // Check for a Kotlin companion object with an of(DataSource) method.
        try {
            Field companion = type.getField("Companion");
            Object companionObj = companion.get(null);
            companionObj.getClass().getMethod("of", DataSource.class);
            return true;
        } catch (NoSuchFieldException | NoSuchMethodException | IllegalAccessException ignored) {
        }
        return false;
    }

    private static Object invokeFactoryMethod(Class<?> type, DataSource dataSource) throws Exception {
        // Try static method first.
        try {
            Method m = type.getMethod("of", DataSource.class);
            if (Modifier.isStatic(m.getModifiers())) {
                return m.invoke(null, dataSource);
            }
        } catch (NoSuchMethodException ignored) {
        }
        // Try Kotlin companion object.
        Field companionField = type.getField("Companion");
        Object companion = companionField.get(null);
        Method of = companion.getClass().getMethod("of", DataSource.class);
        return of.invoke(companion, dataSource);
    }

    /**
     * Resolves and reads a script from the classpath. Script path resolution follows conventions similar to Spring's
     * {@code @Sql} annotation:
     * <ul>
     *     <li>Paths prefixed with {@code classpath:} are resolved from the classpath root.</li>
     *     <li>Absolute paths (starting with {@code /}) are resolved from the classpath root.</li>
     *     <li>Relative paths (no prefix, no leading {@code /}) are resolved relative to the test class.</li>
     * </ul>
     */
    private static String readScript(Class<?> testClass, String path) {
        InputStream is;
        if (path.startsWith("classpath:")) {
            String classpathPath = path.substring("classpath:".length());
            if (!classpathPath.startsWith("/")) {
                classpathPath = "/" + classpathPath;
            }
            is = testClass.getResourceAsStream(classpathPath);
        } else {
            // Absolute paths (starting with /) resolve from classpath root.
            // Relative paths resolve relative to the test class package.
            is = testClass.getResourceAsStream(path);
        }
        if (is == null) {
            throw new IllegalArgumentException("Script not found on classpath: " + path);
        }
        try (is) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void executeScript(Connection conn, String sql) throws SQLException {
        for (String statement : splitStatements(sql)) {
            try (var stmt = conn.createStatement()) {
                stmt.execute(statement);
            }
        }
    }

    /**
     * Splits a SQL script into individual statements on semicolons, ignoring semicolons that appear inside line
     * comments, block comments, string literals and quoted identifiers. Fragments that contain only comments and
     * whitespace are dropped.
     */
    static List<String> splitStatements(String script) {
        var statements = new ArrayList<String>();
        var current = new StringBuilder();
        boolean hasContent = false;
        int length = script.length();
        int i = 0;
        while (i < length) {
            char c = script.charAt(i);
            char next = i + 1 < length ? script.charAt(i + 1) : '\0';
            if (c == '-' && next == '-') {
                int end = script.indexOf('\n', i);
                end = end == -1 ? length : end;
                current.append(script, i, end);
                i = end;
            } else if (c == '/' && next == '*') {
                int end = script.indexOf("*/", i + 2);
                end = end == -1 ? length : end + 2;
                current.append(script, i, end);
                i = end;
            } else if (c == '\'' || c == '"') {
                int end = i + 1;
                while (end < length) {
                    if (script.charAt(end) == c) {
                        if (c == '\'' && end + 1 < length && script.charAt(end + 1) == '\'') {
                            end += 2; // A doubled quote escapes itself within a string literal.
                            continue;
                        }
                        end++;
                        break;
                    }
                    end++;
                }
                current.append(script, i, end);
                hasContent = true;
                i = end;
            } else if (c == ';') {
                if (hasContent) {
                    statements.add(current.toString().trim());
                }
                current.setLength(0);
                hasContent = false;
                i++;
            } else {
                current.append(c);
                hasContent |= !Character.isWhitespace(c);
                i++;
            }
        }
        if (hasContent) {
            statements.add(current.toString().trim());
        }
        return statements;
    }

    /**
     * Shuts down the extension-created H2 database when the class-level store closes, releasing the memory that
     * {@code DB_CLOSE_DELAY=-1} would otherwise hold onto for the remainder of the JVM lifetime.
     *
     * <p>Implements both {@link ExtensionContext.Store.CloseableResource} and {@link AutoCloseable} so the store
     * invokes it on JUnit 5 as well as on JUnit 6, where {@code CloseableResource} is no longer supported.</p>
     */
    private record DatabaseShutdown(DataSource dataSource)
            implements ExtensionContext.Store.CloseableResource, AutoCloseable {

        @Override
        public void close() throws SQLException {
            try (Connection conn = dataSource.getConnection();
                 var stmt = conn.createStatement()) {
                stmt.execute("SHUTDOWN");
            }
        }
    }

    /**
     * Drops the database provisioned for a test class inside a shared container when the class-level store closes,
     * the container-database counterpart of {@link DatabaseShutdown}.
     */
    private record DatabaseDrop(DatabaseContainer.Database database)
            implements ExtensionContext.Store.CloseableResource, AutoCloseable {

        @Override
        public void close() {
            database.close();
        }
    }

    /**
     * A per-test {@link DataSource} wrapper that runs everything a test does in one database transaction and rolls it
     * back afterwards.
     *
     * <p>The first {@link #getConnection()} opens a single physical connection with auto-commit disabled; every
     * subsequent request hands out a proxy over that same connection, so all work of a test shares one transaction
     * regardless of how many connections are requested. Closing a proxy never closes the physical connection; the
     * extension rolls back and closes it in {@code afterEach}.</p>
     *
     * <p>The proxies present themselves as regular auto-commit connections and translate transaction demarcation
     * into savepoints: {@code setAutoCommit(false)} creates a savepoint, {@code commit()} abandons it, and
     * {@code rollback()} rolls back to it. Storm's transaction API therefore works unchanged inside a test (it
     * requires connections to arrive in auto-commit mode), while its commits keep all changes pending in the test
     * transaction. The simulation is faithful for single-threaded tests; because every proxy shares one physical
     * connection, {@code REQUIRES_NEW} loses its independence and concurrent transactions on separate threads are
     * not supported — such tests should disable {@link StormTest#rollback()}.</p>
     *
     * <p>Implements both {@link ExtensionContext.Store.CloseableResource} and {@link AutoCloseable} as a safety net
     * (see {@link DatabaseShutdown}); the extension normally rolls back in {@code afterEach}.</p>
     */
    private static final class TestTransaction
            implements DataSource, ExtensionContext.Store.CloseableResource, AutoCloseable {

        private final DataSource delegate;
        private final Object lock = new Object();
        private Connection physical;
        private boolean completed;

        TestTransaction(DataSource delegate) {
            this.delegate = delegate;
        }

        @Override
        public Connection getConnection() throws SQLException {
            synchronized (lock) {
                if (completed) {
                    // A wrapper outliving its test (for example, retained in a field with a per-class test instance
                    // lifecycle) must not silently open a connection that nothing would ever roll back.
                    throw new SQLException("The test transaction has completed; obtain a new DataSource for each test.");
                }
                if (physical == null) {
                    Connection connection = delegate.getConnection();
                    connection.setAutoCommit(false);
                    physical = connection;
                }
                return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                        new Class<?>[] {Connection.class}, new TransactionSimulatingConnection(physical));
            }
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            // The test transaction spans one physical connection; per-call credentials cannot apply to it.
            return getConnection();
        }

        void rollback() throws SQLException {
            synchronized (lock) {
                completed = true;
                Connection connection = physical;
                if (connection == null) {
                    return;
                }
                physical = null;
                try (connection) {
                    if (!connection.isClosed()) {
                        connection.rollback();
                        // Restore auto-commit before close so a pooled connection returns clean.
                        connection.setAutoCommit(true);
                    }
                }
            }
        }

        @Override
        public void close() throws SQLException {
            rollback();
        }

        @Override
        public PrintWriter getLogWriter() throws SQLException {
            return delegate.getLogWriter();
        }

        @Override
        public void setLogWriter(PrintWriter out) throws SQLException {
            delegate.setLogWriter(out);
        }

        @Override
        public void setLoginTimeout(int seconds) throws SQLException {
            delegate.setLoginTimeout(seconds);
        }

        @Override
        public int getLoginTimeout() throws SQLException {
            return delegate.getLoginTimeout();
        }

        @Override
        public Logger getParentLogger() throws SQLFeatureNotSupportedException {
            return delegate.getParentLogger();
        }

        @Override
        public <T> T unwrap(Class<T> iface) throws SQLException {
            return delegate.unwrap(iface);
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) throws SQLException {
            return delegate.isWrapperFor(iface);
        }
    }

    /**
     * Handler for the connection proxies handed out by {@link TestTransaction}. The physical connection runs with
     * auto-commit disabled; the proxy reports the auto-commit state a caller would see on a dedicated connection and
     * maps transaction demarcation onto savepoints:
     * <ul>
     *     <li>{@code setAutoCommit(false)} creates a savepoint marking the simulated transaction start.</li>
     *     <li>{@code commit()} abandons the savepoint and creates a fresh one for the next implicit transaction.
     *         Savepoints are abandoned rather than released because not every database supports releasing
     *         savepoints; an unreleased savepoint simply expires with the test transaction.</li>
     *     <li>{@code rollback()} rolls back to the savepoint, which remains valid for the next implicit
     *         transaction.</li>
     *     <li>{@code close()} only detaches the proxy; an open simulated transaction is rolled back, mirroring how
     *         drivers commonly treat a connection closed mid-transaction.</li>
     * </ul>
     * Caller-created savepoints pass through and nest inside the simulated transaction. All methods are confined to
     * the test's thread, like the connection a test would otherwise own.
     */
    private static final class TransactionSimulatingConnection implements InvocationHandler {

        private final Connection physical;
        private boolean autoCommit = true;
        private Savepoint savepoint;
        private boolean closed;

        TransactionSimulatingConnection(Connection physical) {
            this.physical = physical;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            switch (method.getName()) {
                case "equals":
                    return proxy == args[0];
                case "hashCode":
                    return System.identityHashCode(proxy);
                case "toString":
                    return "Test transaction connection for " + physical;
                case "close":
                    if (!closed) {
                        closed = true;
                        if (savepoint != null) {
                            physical.rollback(savepoint);
                            savepoint = null;
                        }
                    }
                    return null;
                case "isClosed":
                    return closed || physical.isClosed();
                default:
                    // Fall through to the transaction-state methods and pass-through below.
            }
            if (closed) {
                throw new SQLException("Connection is closed.");
            }
            switch (method.getName()) {
                case "getAutoCommit":
                    return autoCommit;
                case "setAutoCommit": {
                    boolean requested = (Boolean) args[0];
                    if (requested == autoCommit) {
                        return null;
                    }
                    if (requested) {
                        // Enabling auto-commit commits the active transaction; abandon its savepoint.
                        savepoint = null;
                    } else {
                        savepoint = physical.setSavepoint();
                    }
                    autoCommit = requested;
                    return null;
                }
                case "commit":
                    requireTransaction();
                    savepoint = physical.setSavepoint();
                    return null;
                case "rollback":
                    if (args == null || args.length == 0) {
                        requireTransaction();
                        physical.rollback(savepoint);
                        return null;
                    }
                    requireTransaction();
                    return passThrough(method, args);
                case "setSavepoint":
                case "releaseSavepoint":
                    requireTransaction();
                    return passThrough(method, args);
                default:
                    return passThrough(method, args);
            }
        }

        private void requireTransaction() throws SQLException {
            if (autoCommit) {
                throw new SQLException("Connection is in auto-commit mode.");
            }
        }

        private Object passThrough(Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(physical, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }
}
