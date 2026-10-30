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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.jspecify.annotations.Nullable;

/**
 * A prepared statement with what closing it returns: the connection a data-source template took for it, or nothing
 * for a template backed by a connection the caller manages. Storm closes the lease wherever it closes a statement,
 * so the statement Storm executes is the driver's own, and every method called on it is the driver's.
 */
final class StatementLease {

    private final PreparedStatement statement;
    private final @Nullable Runnable release;
    private boolean released;

    private StatementLease(PreparedStatement statement, @Nullable Runnable release) {
        this.statement = statement;
        this.release = release;
    }

    /**
     * Returns a lease that returns its connection through the given action when it is closed.
     *
     * @param statement the driver's statement.
     * @param release returns the connection the statement runs on.
     * @return the lease.
     */
    static StatementLease releasing(PreparedStatement statement, Runnable release) {
        return new StatementLease(statement, release);
    }

    /**
     * Returns a lease on a statement whose connection the caller manages, which closing the lease leaves alone.
     *
     * @param statement the driver's statement.
     * @return the lease.
     */
    static StatementLease of(PreparedStatement statement) {
        return new StatementLease(statement, null);
    }

    /** Returns the driver's statement. */
    PreparedStatement statement() {
        return statement;
    }

    /**
     * Closes the statement and returns its connection, once: a lease closed again closes the driver's statement
     * again, which a driver ignores, and returns nothing a second time.
     *
     * @throws SQLException if closing the statement fails; the connection is returned all the same.
     */
    void close() throws SQLException {
        try {
            statement.close();
        } finally {
            if (release != null && !released) {
                released = true;
                release.run();
            }
        }
    }

    /**
     * Returns the statement as the application closes it, for a statement handed to application code: closing it
     * closes the lease. A lease that returns nothing on close hands out the driver's statement itself; one that
     * returns a connection hands out a proxy, so every method but {@code close} stays the driver's, newer JDBC
     * methods included.
     *
     * @return the statement for the application.
     */
    PreparedStatement forApplication() {
        if (release == null) {
            return statement;
        }
        return (PreparedStatement) Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),
                new Class<?>[] {PreparedStatement.class}, (ignore, method, args) -> {
                    if (method.getName().equals("close") && method.getParameterCount() == 0) {
                        close();
                        return null;
                    }
                    try {
                        return method.invoke(statement, args);
                    } catch (InvocationTargetException e) {
                        throw e.getTargetException();
                    }
                });
    }
}
