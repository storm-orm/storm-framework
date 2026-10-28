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

import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.SqlTemplate.Parameter;
import st.orm.core.template.impl.SqlInterceptorManager;
import st.orm.core.template.impl.StatementListener;
import st.orm.spi.QueryContext;
import st.orm.test.CapturedSql.Operation;
import st.orm.test.CapturedSql.Origin;

/**
 * Captures the SQL statements an action executes.
 *
 * <p>Statements are recorded around execution, so each carries what caused it, its bound parameter values, and how
 * long it took, and a statement that is built but never run is not captured. Statements accumulate across multiple
 * {@link #record}, {@link #execute}, or {@link #executeThrowing} calls; use {@link #clear()} to reset between
 * captures.</p>
 *
 * <p>The capture is bound to the thread that runs the action and to the contexts Storm carries it into, such as a
 * {@code transaction} block; it does not follow work handed to another thread or coroutine outside those contexts.
 * Recording itself is safe from any thread a carried context reaches. Kotlin coroutine code records through the
 * suspending {@code recording} extension of the {@code storm-kotlin-test} module, which follows the coroutine across
 * the threads it resumes on.</p>
 *
 * <p><strong>Testing only.</strong> Captured statements retain their bound parameter values, which may be
 * sensitive (credentials, personal data). This class lives in the test-scoped {@code storm-test} module; do
 * not route captured statements to logs or external systems from production code.</p>
 *
 * @since 1.9
 */
public final class SqlCapture {

    private final Queue<CapturedSql> statements = new ConcurrentLinkedQueue<>();

    /**
     * Records each execution as it completes, so the capture carries the execution's duration: the time the
     * statement spent in the database, from prepare to its return, which for a streamed read excludes the
     * consumption of the stream.
     */
    private final StatementListener listener = new StatementListener() {
        @Override
        public Handle onExecute(QueryContext context, List<Parameter> parameters) {
            Operation op = switch (context.operation()) {
                case SELECT -> Operation.SELECT;
                case INSERT -> Operation.INSERT;
                case UPDATE -> Operation.UPDATE;
                case DELETE -> Operation.DELETE;
                case UPSERT -> Operation.UPSERT;
                case UNDEFINED -> Operation.UNDEFINED;
            };
            Origin origin = switch (context.origin()) {
                case DIRECT -> Origin.DIRECT;
                case FETCH -> Origin.FETCH;
            };
            String statement = context.statement().orElse("");
            List<Object> values = parameters.stream()
                    .map(Parameter::dbValue)
                    .toList();
            long start = System.nanoTime();
            return new Handle() {
                private long executed;

                @Override
                public void executed() {
                    executed = System.nanoTime();
                }

                @Override
                public void close(long rows, boolean exact) {
                    statements.add(new CapturedSql(op, statement, values, origin,
                            Duration.ofNanos(executed - start), rows, exact));
                }
            };
        }
    };

    /**
     * Executes the given action while capturing all SQL statements it generates.
     *
     * <p>Named {@code record} rather than {@code run} so a Kotlin caller can never silently resolve to the
     * {@code kotlin.run} scope function, which would execute the action without capturing anything.</p>
     *
     * @param action the action to execute.
     * @since 1.14
     */
    public void record(Runnable action) {
        SqlInterceptorManager.listen(listener).run(action);
    }

    /**
     * Attaches the capture to the calling thread until the returned handle is closed, for brackets around code that
     * a callable cannot wrap, such as a test lifecycle.
     *
     * <p>The handle must be closed on the thread that attached, which a try-with-resources block guarantees.</p>
     *
     * @return the handle that detaches the capture.
     * @since 1.14
     */
    public AutoCloseable attach() {
        return SqlInterceptorManager.attach(listener);
    }

    /**
     * Executes the given action while capturing all SQL statements it generates, returning its result.
     *
     * <p>The result type admits null, so an action that ends in a lookup returning no row is captured the same way
     * as one that returns a value.</p>
     *
     * @param action the action to execute.
     * @param <T> the result type.
     * @return the result of the action.
     */
    public <T extends @Nullable Object> T execute(Supplier<T> action) {
        return SqlInterceptorManager.listen(listener).get(action);
    }

    /**
     * Executes the given action while capturing all SQL statements it generates, returning its result and allowing
     * checked exceptions.
     *
     * <p>The result type admits null, so an action that ends in a lookup returning no row is captured the same way
     * as one that returns a value.</p>
     *
     * @param action the action to execute.
     * @param <T> the result type.
     * @return the result of the action.
     * @throws Exception if the action throws an exception.
     */
    public <T extends @Nullable Object> T executeThrowing(Callable<T> action) throws Exception {
        return SqlInterceptorManager.listen(listener).call(action);
    }

    /**
     * Returns all captured statements.
     *
     * @return an unmodifiable copy of the captured statements.
     */
    public List<CapturedSql> statements() {
        return List.copyOf(statements);
    }

    /**
     * Returns captured statements filtered by operation type.
     *
     * @param operation the operation type to filter by.
     * @return the matching statements.
     */
    public List<CapturedSql> statements(Operation operation) {
        return statements.stream()
                .filter(s -> s.operation() == operation)
                .toList();
    }

    /**
     * Returns captured statements filtered by what caused them to execute.
     *
     * @param origin the origin to filter by.
     * @return the matching statements.
     * @since 1.13
     */
    public List<CapturedSql> statements(Origin origin) {
        return statements.stream()
                .filter(s -> s.origin() == origin)
                .toList();
    }

    /**
     * Returns the total number of captured statements.
     *
     * @return the statement count.
     */
    public int count() {
        return statements.size();
    }

    /**
     * Returns the number of captured statements matching the given operation type.
     *
     * @param operation the operation type to count.
     * @return the matching statement count.
     */
    public int count(Operation operation) {
        return statements(operation).size();
    }

    /**
     * Returns the number of captured statements matching the given origin.
     *
     * <p>Asserting that {@link Origin#FETCH} stays at zero, or below a bound, holds a query to the shape its
     * fetch plan produces: the statements it counts are the ones a plan brings back in the same statement.</p>
     *
     * @param origin the origin to count.
     * @return the matching statement count.
     * @since 1.13
     */
    public int count(Origin origin) {
        return statements(origin).size();
    }

    /**
     * Clears all captured statements.
     */
    public void clear() {
        statements.clear();
    }
}
