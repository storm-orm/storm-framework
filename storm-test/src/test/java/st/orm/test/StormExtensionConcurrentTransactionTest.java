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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import st.orm.core.spi.ConnectionGuard;

/**
 * A test that rolls back runs every connection over one, so transactions of separate callers cannot run at once.
 */
@StormTest(scripts = {"/test-schema.sql", "/test-data.sql"})
class StormExtensionConcurrentTransactionTest {

    private static void begin(Connection connection) throws SQLException {
        connection.setAutoCommit(false);
    }

    private static void end(Connection connection) throws SQLException {
        connection.commit();
        connection.setAutoCommit(true);
        connection.close();
    }

    private static <T> T onAnotherThread(CheckedSupplier<T> work) throws Exception {
        return CompletableFuture.supplyAsync(() -> {
            try {
                return work.get();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }).join();
    }

    interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @Test
    void aTransactionBeginningOnAnotherThreadWhileOneIsOpenFails(DataSource dataSource) throws Exception {
        Connection first = dataSource.getConnection();
        begin(first);
        SQLException failure = onAnotherThread(() -> {
            try (Connection second = dataSource.getConnection()) {
                return assertThrows(SQLException.class, () -> begin(second));
            }
        });
        assertTrue(failure.getMessage().contains("@StormTest(rollback = false)"), failure.getMessage());
        end(first);
    }

    @Test
    void transactionsOnSeparateThreadsMayTakeTurns(DataSource dataSource) throws Exception {
        Connection first = dataSource.getConnection();
        begin(first);
        end(first);
        onAnotherThread(() -> {
            Connection second = dataSource.getConnection();
            begin(second);
            end(second);
            return null;
        });
        Connection third = dataSource.getConnection();
        assertDoesNotThrow(() -> begin(third));
        end(third);
    }

    @Test
    void aTransactionNestedOnTheSameThreadIsAllowed(DataSource dataSource) throws Exception {
        Connection outer = dataSource.getConnection();
        begin(outer);
        Connection inner = dataSource.getConnection();
        assertDoesNotThrow(() -> begin(inner));
        end(inner);
        end(outer);
    }

    @Test
    void aCallerKeepsItsTransactionsWhenItMovesToAnotherThread(DataSource dataSource) throws Exception {
        // A coroutine resumes on another thread under the identity it began with.
        Object caller = new Object();
        ConnectionGuard.callerHolder().set(caller);
        Connection outer;
        try {
            outer = dataSource.getConnection();
            begin(outer);
        } finally {
            ConnectionGuard.callerHolder().remove();
        }
        onAnotherThread(() -> {
            ConnectionGuard.callerHolder().set(caller);
            try {
                Connection inner = dataSource.getConnection();
                begin(inner);
                end(inner);
            } finally {
                ConnectionGuard.callerHolder().remove();
            }
            return null;
        });
        end(outer);
    }

    @Test
    void aClosedConnectionEndsItsTransaction(DataSource dataSource) throws Exception {
        Connection first = dataSource.getConnection();
        begin(first);
        first.close();
        onAnotherThread(() -> {
            Connection second = dataSource.getConnection();
            begin(second);
            end(second);
            return null;
        });
    }
}
