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

import static java.util.Objects.requireNonNull;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Represents a captured SQL statement with its operation type and bind variables.
 *
 * @param operation the type of SQL operation: the keyword the statement starts with.
 * @param upsert whether the statement is an upsert in the database's own form, whatever keyword it starts with.
 * @param statement the SQL statement with {@code ?} placeholders.
 * @param parameters the bind variable values; a {@code NULL} bind value is {@code null}.
 * @param origin what caused the statement to execute.
 * @param duration the time the execution spent in the database, from prepare to the statement's return; for a
 *                 streamed read this excludes the consumption of the stream.
 * @param rows the rows the execution produced or affected; a lower bound when not exact.
 * @param exactRows whether that count is exact; false when a driver declined to report a batch entry's count or
 *                  a stream closed before its end.
 * @since 1.9
 */
public record CapturedSql(
        Operation operation,
        boolean upsert,
        String statement,
        List<Object> parameters,
        Origin origin,
        Duration duration,
        long rows,
        boolean exactRows) {

    public CapturedSql(Operation operation,
                       boolean upsert,
                       String statement,
                       List<Object> parameters,
                       Origin origin,
                       Duration duration,
                       long rows,
                       boolean exactRows) {
        this.operation = requireNonNull(operation, "operation");
        this.upsert = upsert;
        this.statement = requireNonNull(statement, "statement");
        this.parameters = Collections.unmodifiableList(new ArrayList<>(parameters));
        this.origin = requireNonNull(origin, "origin");
        this.duration = requireNonNull(duration, "duration");
        this.rows = rows;
        this.exactRows = exactRows;
    }

    /**
     * Classifies what caused the statement to execute.
     *
     * <p>A statement resolving a reference is shaped exactly like a primary key lookup the test could have
     * written itself, so asserting on the origin is what pins how many statements resolving references cost.</p>
     *
     * @since 1.13
     */
    public enum Origin {

        /** The statement was asked for directly by the code under test. */
        DIRECT,

        /** The statement resolves a reference whose record was not loaded. */
        FETCH
    }

    /**
     * Classifies the type of SQL operation.
     */
    public enum Operation {
        SELECT,
        INSERT,
        UPDATE,
        DELETE,
        UNDEFINED
    }
}
