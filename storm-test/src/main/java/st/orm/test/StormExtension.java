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
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.util.logging.Logger;
import javax.sql.DataSource;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import st.orm.core.template.impl.SchemaValidator;

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
 * <p>By default, the extension creates an H2 in-memory database. To use a custom {@link DataSource} (for example, one
 * backed by a Testcontainers-managed database), define a static {@code dataSource()} method on the test class that
 * returns a {@link DataSource}. When present, this method takes precedence over the {@code url}, {@code username}, and
 * {@code password} attributes of {@link StormTest}. SQL scripts specified in {@link StormTest#scripts()} are still
 * executed against the returned {@link DataSource}.</p>
 *
 * @since 1.9
 */
public class StormExtension implements BeforeAllCallback, ParameterResolver {

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(StormExtension.class);

    @Override
    public void beforeAll(ExtensionContext context) throws Exception {
        StormTest annotation = context.getRequiredTestClass().getAnnotation(StormTest.class);
        if (annotation == null) {
            return;
        }
        DataSource dataSource = findDataSourceFactory(context.getRequiredTestClass());
        if (dataSource == null) {
            String url = annotation.url().isEmpty()
                    ? "jdbc:h2:mem:" + context.getRequiredTestClass().getSimpleName() + ";DB_CLOSE_DELAY=-1"
                    : annotation.url();
            dataSource = new SimpleDataSource(url, annotation.username(), annotation.password());
        }
        if (annotation.scripts().length > 0) {
            Class<?> testClass = context.getRequiredTestClass();
            try (Connection conn = dataSource.getConnection()) {
                for (String script : annotation.scripts()) {
                    String sql = readScript(testClass, script);
                    executeScript(conn, sql);
                }
            }
        }
        getStore(context).put(DataSource.class, dataSource);
    }

    @Override
    public boolean supportsParameter(ParameterContext paramCtx, ExtensionContext extCtx)
            throws ParameterResolutionException {
        Class<?> type = paramCtx.getParameter().getType();
        if (type == DataSource.class || type == SqlCapture.class || type == SchemaValidator.class) {
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
        if (type == SchemaValidator.class) {
            return SchemaValidator.of(dataSource);
        }
        try {
            return invokeFactoryMethod(type, dataSource);
        } catch (Exception e) {
            throw new ParameterResolutionException(
                    "Failed to create " + type.getName() + " via reflective factory method.", e);
        }
    }

    private DataSource getDataSource(ExtensionContext context) {
        return getStore(context).get(DataSource.class, DataSource.class);
    }

    private ExtensionContext.Store getStore(ExtensionContext context) {
        return context.getRoot().getStore(NAMESPACE);
    }

    /**
     * Looks for a static {@code dataSource()} method on the test class. If found, invokes it and returns the result.
     * This also checks for a Kotlin companion object with a {@code dataSource()} method.
     *
     * @return the {@link DataSource} returned by the factory method, or {@code null} if no such method exists.
     */
    private static DataSource findDataSourceFactory(Class<?> testClass) throws Exception {
        // Check for a Java static method.
        try {
            Method method = testClass.getDeclaredMethod("dataSource");
            if (Modifier.isStatic(method.getModifiers())
                    && DataSource.class.isAssignableFrom(method.getReturnType())) {
                method.setAccessible(true);
                return (DataSource) method.invoke(null);
            }
        } catch (NoSuchMethodException ignored) {
        }
        // Check for a Kotlin companion object with a dataSource() method.
        try {
            Field companion = testClass.getField("Companion");
            Object companionObject = companion.get(null);
            Method method = companionObject.getClass().getMethod("dataSource");
            if (DataSource.class.isAssignableFrom(method.getReturnType())) {
                return (DataSource) method.invoke(companionObject);
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
        for (String statement : sql.split(";")) {
            String trimmed = statement.trim();
            if (!trimmed.isEmpty()) {
                try (var stmt = conn.createStatement()) {
                    stmt.execute(trimmed);
                }
            }
        }
    }

    // --- Simple DataSource implementation ---

    private static final class SimpleDataSource implements DataSource {

        private final String url;
        private final String username;
        private final String password;

        SimpleDataSource(String url, String username, String password) {
            this.url = url;
            this.username = username;
            this.password = password;
        }

        @Override
        public Connection getConnection() throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public Connection getConnection(String username, String password) throws SQLException {
            return DriverManager.getConnection(url, username, password);
        }

        @Override
        public PrintWriter getLogWriter() {
            return null;
        }

        @Override
        public void setLogWriter(PrintWriter out) {
        }

        @Override
        public void setLoginTimeout(int seconds) {
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
            throw new SQLException("Not a wrapper.");
        }

        @Override
        public boolean isWrapperFor(Class<?> iface) {
            return false;
        }
    }
}
