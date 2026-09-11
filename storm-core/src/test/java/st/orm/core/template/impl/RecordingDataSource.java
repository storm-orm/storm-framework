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
package st.orm.core.template.impl;

import java.io.PrintWriter;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Hands out proxies over the target's connections, numbered in the order they are opened, and records every
 * statement prepared or executed on them as {@code n:prepare(sql)} and {@code n:execute(sql)}, with {@code n} the
 * number of the connection the statement ran on.
 */
final class RecordingDataSource implements DataSource {

    private final DataSource target;
    final List<String> calls = new ArrayList<>();
    private int opened;

    RecordingDataSource(DataSource target) {
        this.target = target;
    }

    void reset() {
        calls.clear();
        opened = 0;
    }

    @Override
    public Connection getConnection() throws SQLException {
        var physical = target.getConnection();
        int number = ++opened;
        return proxy(Connection.class, physical, (method, arguments, result) -> {
            if (method.getName().equals("prepareStatement")) {
                calls.add(number + ":prepare(" + arguments[0] + ")");
                return result;
            }
            if (method.getName().equals("createStatement") && result instanceof Statement statement) {
                return proxy(Statement.class, statement, (statementMethod, statementArguments, statementResult) -> {
                    if (statementMethod.getName().startsWith("execute") && statementArguments != null
                            && statementArguments.length > 0 && statementArguments[0] instanceof String sql) {
                        calls.add(number + ":execute(" + sql + ")");
                    }
                    return statementResult;
                });
            }
            return result;
        });
    }

    @FunctionalInterface
    private interface Observer {
        Object observe(java.lang.reflect.Method method, Object[] arguments, Object result);
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, T physical, Observer observer) {
        InvocationHandler handler = (proxy, method, arguments) -> {
            Object result;
            try {
                result = method.invoke(physical, arguments);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
            return observer.observe(method, arguments, result);
        };
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
        return getConnection();
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
