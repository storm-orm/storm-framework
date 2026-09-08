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

import static java.lang.System.identityHashCode;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.jspecify.annotations.Nullable;
import st.orm.Data;
import st.orm.PersistenceException;
import st.orm.spi.SqlOperation;
import st.orm.spi.StatementOrigin;

/**
 * Refuses a statement on a connection whose result stream still has rows to read.
 *
 * <p>A result stream reads its rows from the database as they are consumed, and the rows not yet consumed live on
 * the server. Whether another statement may run on the same connection in the meantime is up to the driver: MySQL
 * Connector/J rejects it, MariaDB Connector/J and the SQL Server driver read the rest of the open result into
 * application memory first, and PostgreSQL and Oracle interleave the two. A result read to its end blocks nothing
 * on any of them, so the guard is released when the last row is read as well as when the stream closes. A stream
 * that is consume-only on every database behaves the same in a test on H2 as in production on any of them, so the
 * guard applies on every dialect rather than only where the driver would misbehave; a loop that needs the
 * connection while it iterates has the window terminals, which run one closed statement per window.</p>
 *
 * <p>Streams are tracked per connection: inside a transaction every statement shares the transaction's
 * connection, and a connection-backed template shares the caller's. Outside a transaction each statement obtains
 * its own connection, so no stream and statement ever meet. The connection is identified by the statement's own
 * {@link PreparedStatement#getConnection()}, which pool and transaction wrappers resolve to the same object for
 * every statement they hand out on it.</p>
 */
final class StreamGuard {

    /** An open stream registered against a connection. */
    interface Handle extends AutoCloseable {
        @Override
        void close();
    }

    private static final Handle NOOP = () -> { };

    /** Weak identity key, so a connection a caller abandons without closing its stream cannot be pinned. */
    private static final class ConnectionIdentity extends WeakReference<Connection> {
        private final int id;

        ConnectionIdentity(Connection connection, @Nullable ReferenceQueue<Connection> queue) {
            super(connection, queue);
            this.id = identityHashCode(connection);
        }

        @Override
        public int hashCode() {
            return id;
        }

        @Override
        public boolean equals(Object other) {
            return other instanceof ConnectionIdentity otherIdentity
                    && this.get() == otherIdentity.get()
                    && this.get() != null;
        }
    }

    /** What the open stream selects, for the message that names it. */
    private record OpenStream(SqlOperation operation, @Nullable Class<? extends Data> dataType,
                              @Nullable String statementText) {}

    private static final ReferenceQueue<Connection> QUEUE = new ReferenceQueue<>();
    private static final Map<ConnectionIdentity, OpenStream> OPEN = new ConcurrentHashMap<>();

    private StreamGuard() {
    }

    /**
     * Refuses to execute when a stream is still open on the statement's connection.
     *
     * @param statement the statement about to execute.
     * @param environment the environment of the query about to execute, naming what it does.
     * @throws PersistenceException if a stream is open on the connection.
     */
    static void check(PreparedStatement statement, QueryImpl.Environment environment) {
        if (OPEN.isEmpty()) {
            return;
        }
        var connection = connectionOf(statement);
        if (connection == null) {
            return;
        }
        var open = OPEN.get(new ConnectionIdentity(connection, null));
        if (open != null) {
            throw new PersistenceException(message(open, environment.operation(), environment.dataType(),
                    environment.statementText(), environment.origin()));
        }
    }

    /**
     * Registers a stream handed to the caller as open on the statement's connection. Returns the handle that
     * releases the registration; closing it more than once is harmless, as the stream releases it both when its
     * last row is read and when it closes.
     *
     * @param statement the statement whose result the stream reads.
     * @param environment the environment of the query that produced the stream, naming what it selects.
     * @return the handle to close with the stream.
     */
    static Handle open(PreparedStatement statement, QueryImpl.Environment environment) {
        var connection = connectionOf(statement);
        if (connection == null) {
            return NOOP;
        }
        expunge();
        var key = new ConnectionIdentity(connection, QUEUE);
        OPEN.put(key, new OpenStream(environment.operation(), environment.dataType(), environment.statementText()));
        return () -> OPEN.remove(key);
    }

    private static @Nullable Connection connectionOf(PreparedStatement statement) {
        try {
            return statement.getConnection();
        } catch (SQLException | RuntimeException ignore) {
            // A statement that cannot name its connection cannot be guarded; the driver reports whatever follows.
            return null;
        }
    }

    private static void expunge() {
        ConnectionIdentity stale;
        while ((stale = (ConnectionIdentity) QUEUE.poll()) != null) {
            OPEN.remove(stale);
        }
    }

    private static String message(OpenStream open, SqlOperation operation, @Nullable Class<? extends Data> dataType,
                                  @Nullable String statementText, StatementOrigin origin) {
        var message = new StringBuilder(512);
        message.append("Cannot execute a statement while a result stream is still open on the same connection. ")
                .append("Open stream: ").append(describe(open.operation(), open.dataType(), open.statementText()))
                .append(". Statement: ")
                .append(describe(operation, dataType, statementText))
                .append(". ");
        if (origin == StatementOrigin.FETCH) {
            message.append("The statement resolves a Ref the stream's query did not load; name the reference in ")
                    .append("the query's fetch plan to load it in the same statement. ");
        }
        message.append("Read the stream to its end or close it before executing other statements, or iterate in windows: ")
                .append("windows(size) runs one closed statement per window and leaves the connection free ")
                .append("between windows. This holds on every database: MySQL Connector/J rejects a statement ")
                .append("while a stream is open, and MariaDB Connector/J and the SQL Server driver first read ")
                .append("the rest of the open result into memory.");
        return message.toString();
    }

    private static String describe(SqlOperation operation, @Nullable Class<? extends Data> dataType,
                                   @Nullable String statementText) {
        var description = new StringBuilder(operation.name());
        if (dataType != null) {
            description.append(' ').append(dataType.getSimpleName());
        }
        if (statementText != null) {
            description.append(" [").append(abbreviate(statementText)).append(']');
        }
        return description.toString();
    }

    private static String abbreviate(String statementText) {
        var text = statementText.strip().replaceAll("\\s+", " ");
        return text.length() <= 160 ? text : text.substring(0, 157) + "...";
    }
}
