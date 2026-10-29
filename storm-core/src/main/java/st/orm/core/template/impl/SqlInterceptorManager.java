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

import static java.util.Collections.newSetFromMap;

import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;
import st.orm.core.template.Sql;
import st.orm.core.template.SqlTemplate;
import st.orm.spi.StatementOrigin;

/**
 * Manages SQL interceptors.
 *
 * @since 1.1
 */
public final class SqlInterceptorManager {

    /** Shared identity customizer; lets callers recognize operators that do not customize the template. */
    private static final UnaryOperator<SqlTemplate> IDENTITY_CUSTOMIZER = it -> it;

    /**
     * One registered interceptor, with the template customizer it was registered alongside.
     *
     * <p>Public so an integration can carry a scope across threads through {@link #holder()}; the contents are of
     * no use to a caller, which only ever moves the snapshot as a whole.</p>
     */
    public record Operator(UnaryOperator<Sql> interceptor,
                          UnaryOperator<SqlTemplate> customizer,
                          StatementListener listener) {
        public Operator(UnaryOperator<Sql> interceptor, UnaryOperator<SqlTemplate> customizer) {
            this(interceptor, customizer, null);
        }

        Operator(UnaryOperator<Sql> interceptor) {
            this(interceptor, IDENTITY_CUSTOMIZER, null);
        }
    }

    public interface Carrier {
        void run(Runnable runnable);
        <R> R call(Callable<? extends R> op) throws Exception;
        <R> R get(Supplier<? extends R> op);
    }

    private static class CarrierImpl implements Carrier {
        private final Operator operator;

        public CarrierImpl(Operator operator) {
            this.operator = operator;
        }

        @Override
        public void run(Runnable runnable) {
            Operator[] previous = install(operator);
            try {
                runnable.run();
            } finally {
                restore(previous);
            }
        }

        @Override
        public <R> R call(Callable<? extends R> op) throws Exception {
            Operator[] previous = install(operator);
            try {
                return op.call();
            } finally {
                restore(previous);
            }
        }

        @Override
        public <R> R get(Supplier<? extends R> op) {
            Operator[] previous = install(operator);
            try {
                return op.get();
            } finally {
                restore(previous);
            }
        }
    }

    /**
     * Installs the operator for the calling thread and returns what was in scope before it, to restore.
     *
     * <p>The scope is an immutable snapshot: installing copies, so a statement reads a value that cannot change
     * under it, and restoring puts back exactly what was there. A snapshot is also what a coroutine context element
     * can carry, since it binds a value.</p>
     */
    private static Operator[] install(Operator operator) {
        Operator[] previous = LOCAL_OPERATORS.get();
        Operator[] installed;
        if (previous == null) {
            installed = new Operator[] {operator};
        } else {
            installed = new Operator[previous.length + 1];
            installed[0] = operator;
            System.arraycopy(previous, 0, installed, 1, previous.length);
        }
        LOCAL_OPERATORS.set(installed);
        LOCAL_SCOPES.incrementAndGet();
        return previous;
    }

    /** Restores the scope that was in place before the matching {@link #install}. */
    private static void restore(Operator[] previous) {
        LOCAL_SCOPES.decrementAndGet();
        if (previous == null) {
            // Clear the thread-local to prevent memory leaks.
            LOCAL_OPERATORS.remove();
        } else {
            LOCAL_OPERATORS.set(previous);
        }
    }

    /**
     * Returns the operators scoped to the calling thread, or {@code null} when it has none.
     *
     * <p>Reading the count first keeps the common case, where nothing is scoped anywhere, to a single volatile read
     * rather than a thread-local lookup.</p>
     */
    static Operator @Nullable [] localOperators() {
        return LOCAL_SCOPES.get() == 0 ? null : LOCAL_OPERATORS.get();
    }

    /**
     * Returns the thread local holding the operators scoped to the current thread.
     *
     * <p>Intended for integrations that propagate the scope across threads, such as coroutine context elements. The
     * value is an immutable snapshot, which is what makes carrying it safe: a coroutine that binds it observes the
     * operators it was started with, and cannot mutate what another coroutine reads. Application code should not
     * modify the holder directly.</p>
     *
     * <p>An integration that binds the holder must also account for {@link #scopeInstalled()}, which is what allows
     * the statement path to skip the holder entirely.</p>
     *
     * @return the thread local holding the current scope's operators.
     * @since 1.13
     */
    public static ThreadLocal<Operator[]> holder() {
        return LOCAL_OPERATORS;
    }

    /**
     * Registers that a scope became reachable on a thread the statement path may run on, so that path stops
     * skipping the holder.
     *
     * <p>An integration carrying a scope onto another thread calls this for as long as the scope can be observed
     * there, and {@link #scopeUninstalled()} once it can no longer be.</p>
     *
     * @since 1.13
     */
    public static void scopeInstalled() {
        LOCAL_SCOPES.incrementAndGet();
    }

    /**
     * Registers that a scope carried onto a thread is no longer reachable there.
     *
     * @since 1.13
     */
    public static void scopeUninstalled() {
        LOCAL_SCOPES.decrementAndGet();
    }

    /**
     * Returns whether any scope is installed on any thread, which is what lets work that only serves scopes,
     * such as deriving a statement's shape identity, be skipped entirely while none is open.
     *
     * @since 1.13
     */
    public static boolean hasActiveScopes() {
        return LOCAL_SCOPES.get() != 0;
    }

    private static final ReadWriteLock LOCK = new ReentrantReadWriteLock();
    private static final Set<Object> GLOBAL_OPERATORS = newSetFromMap(new IdentityHashMap<>());

    /**
     * Number of registered global operators. Written under {@link #LOCK}'s write lock, read without locking so the
     * hot {@link #intercept(Sql)} path can skip acquiring the read lock when no global operators are registered.
     */
    private static volatile int globalOperatorCount = 0;

    /**
     * Operators scoped to a thread, held as an immutable snapshot and absent when the thread has none. Leaving it
     * unset rather than holding an empty container keeps every thread that merely executes statements free of both
     * an allocation and a retained entry.
     */
    private static final ThreadLocal<Operator[]> LOCAL_OPERATORS = new ThreadLocal<>();

    /**
     * Number of scopes installed across all threads. Read on the statement path to skip the thread-local lookup
     * entirely while nothing is scoped, which is the state a production application runs in.
     */
    private static final AtomicInteger LOCAL_SCOPES = new AtomicInteger();

    private SqlInterceptorManager() {
    }

    /**
     * Register a global interceptor that will be called for all SQL statements.
     *
     * @param interceptor the interceptor to call for each SQL statement.
     */
    public static void registerGlobalInterceptor(UnaryOperator<Sql> interceptor) {
        addGlobalOperator(interceptor);
    }

    /**
     * Register a global observer that will be called for all SQL statements.
     *
     * @param observer the observer to call for each SQL statement.
     */
    public static void registerGlobalObserver(Consumer<Sql> observer) {
        addGlobalOperator(observer);
    }

    /**
     * Unregister a global observer.
     *
     * @param observer the observer to unregister.
     */
    public static void unregisterGlobalObserver(UnaryOperator<Sql> observer) {
        removeGlobalOperator(observer);
    }

    /**
     * Unregister a global observer.
     *
     * @param observer the observer to unregister.
     */
    public static void unregisterGlobalObserver(Consumer<Sql> observer) {
        removeGlobalOperator(observer);
    }

    private static void addGlobalOperator(Object operator) {
        LOCK.writeLock().lock();
        try {
            GLOBAL_OPERATORS.add(operator);
            globalOperatorCount = GLOBAL_OPERATORS.size();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    private static void removeGlobalOperator(Object operator) {
        LOCK.writeLock().lock();
        try {
            GLOBAL_OPERATORS.remove(operator);
            globalOperatorCount = GLOBAL_OPERATORS.size();
        } finally {
            LOCK.writeLock().unlock();
        }
    }

    /**
     * Creates a scoped interceptor that applies an operator to the SQL statements the carrier's action processes.
     *
     * <p>The scope covers the thread that runs the action, and only that thread: work handed to another thread does
     * not pass through it unless an integration carries the scope there via {@link #holder()}. Scopes on different
     * threads are isolated from one another.</p>
     *
     * @param operator the operator to apply to each SQL statement.
     * @return a {@link Carrier} that binds the interceptor to the thread running its action.
     */
    public static Carrier intercept(UnaryOperator<Sql> operator) {
        return new CarrierImpl(new Operator(operator));
    }

    /**
     * Creates a scoped interceptor that applies an operator to the SQL statements the carrier's action processes.
     *
     * <p>The scope covers the thread that runs the action, and only that thread: work handed to another thread does
     * not pass through it unless an integration carries the scope there via {@link #holder()}. Scopes on different
     * threads are isolated from one another.</p>
     *
     * @param customizer a function to customize the SQL template before use.
     * @param operator the operator to apply to each SQL statement.
     * @return a {@link Carrier} that binds the interceptor to the thread running its action.
     * @since 1.3
     */
    public static Carrier intercept(UnaryOperator<SqlTemplate> customizer, UnaryOperator<Sql> operator) {
        return new CarrierImpl(new Operator(operator, customizer));
    }

    /**
     * Creates a scoped carrier for a listener notified around each statement execution, which is what a scope
     * needs: a statement carries no duration until it runs.
     *
     * <p>The scope covers the thread that runs the action, and only that thread: work handed to another thread does
     * not pass through it unless an integration carries the scope there via {@link #holder()}.</p>
     *
     * @param listener the listener to notify around each execution.
     * @return a {@link Carrier} that binds the listener to the thread running its action.
     * @since 1.13
     */
    public static Carrier listen(StatementListener listener) {
        return new CarrierImpl(new Operator(sql -> sql, IDENTITY_CUSTOMIZER, listener));
    }

    /**
     * Installs a listener on the calling thread until the returned handle is closed, for scopes that bracket a
     * block rather than wrap a callable.
     *
     * <p>The handle must be closed on the thread that attached, which a try-with-resources block guarantees;
     * closing elsewhere would restore another thread's scope.</p>
     *
     * @param listener the listener to notify around each execution.
     * @return the handle that detaches the listener.
     * @since 1.13
     */
    public static AutoCloseable attach(StatementListener listener) {
        Operator[] previous = install(new Operator(sql -> sql, IDENTITY_CUSTOMIZER, listener));
        return () -> restore(previous);
    }

    /**
     * Returns whether a scope on the calling thread records call sites, which is what gates capturing a launch
     * site: a stack walk that nothing would read is a stack walk skipped.
     *
     * @return {@code true} when a scoped listener records call sites.
     * @since 1.13
     */
    public static boolean hasCallSiteListeners() {
        Operator[] operators = localOperators();
        if (operators == null) {
            return false;
        }
        for (var operator : operators) {
            var listener = operator.listener();
            if (listener != null && listener.callSites()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Notifies the scopes on the calling thread that reads were served from the transaction's entity cache
     * without a statement. A single counter read while no scope is open anywhere, which is the state a
     * production application runs in.
     *
     * @param dataType the entity type the cache served.
     * @param count how many reads the cache served; zero notifies nothing.
     * @since 1.13
     */
    public static void notifyCacheHits(Class<? extends st.orm.Data> dataType, int count) {
        if (count <= 0) {
            return;
        }
        Operator[] operators = localOperators();
        if (operators == null) {
            return;
        }
        for (var operator : operators) {
            var listener = operator.listener();
            if (listener != null) {
                try {
                    listener.onCacheHit(dataType, count);
                } catch (Throwable ignore) {
                    // Scope failures never affect the read.
                }
            }
        }
    }

    public static Carrier intercept(Consumer<Sql> observer) {
        return new CarrierImpl(new Operator(sql -> {
            observer.accept(sql);
            return sql;
        }));
    }

    /**
     * Creates a scoped interceptor that invokes an observer with the SQL statements the carrier's action processes.
     *
     * <p>The scope covers the thread that runs the action, and only that thread: work handed to another thread does
     * not pass through it unless an integration carries the scope there via {@link #holder()}. Scopes on different
     * threads are isolated from one another.</p>
     *
     * @param customizer a function to customize the SQL template before use.
     * @param observer the observer to invoke with each SQL statement.
     * @return a {@link Carrier} that binds the interceptor to the thread running its action.
     * @since 1.3
     */
    public static Carrier intercept(UnaryOperator<SqlTemplate> customizer, Consumer<Sql> observer) {
        return new CarrierImpl(new Operator(sql -> {
            observer.accept(sql);
            return sql;
        }, customizer));
    }

    /**
     * Returns whether a template customizer is active on the current thread's scope.
     *
     * <p>Artifacts cached from a processed template, such as query plans, are only valid for the template they were
     * processed with; callers holding such caches bypass them while a customizer is in scope so the customized
     * template generates the statement.</p>
     *
     * @return {@code true} if a scoped template customizer is active.
     * @since 1.13
     */
    public static boolean hasLocalCustomizers() {
        Operator[] operators = localOperators();
        if (operators == null) {
            return false;
        }
        for (var operator : operators) {
            if (operator.customizer() != IDENTITY_CUSTOMIZER) {
                return true;
            }
        }
        return false;
    }

    /**
     * Customizes the given SQL template using the current thread's scoped customizer, if available.
     *
     * <p>This method applies a customizer to the SQL template that is scoped to the current thread context.
     * If no customizer is set, it returns the original template.</p>
     *
     * <p>This method is intended to be used internally within the ORM framework, or it's extensions, to ensure that
     * SQL templates are adjusted according to the current thread's context, such as applying custom SQL dialects or
     * other template modifications.</p>
     *
     * @param template the SQL template to customize.
     * @return the customized SQL template, or the original template if no customizer is set.
     */
    public static SqlTemplate customize(SqlTemplate template) {
        Operator[] operators = localOperators();
        if (operators == null) {
            return template;
        }
        // The snapshot is scoped to this thread and cannot change while it is read, so no lock is needed and an
        // operator registering another one from its own apply method cannot disturb this pass.
        SqlTemplate adjusted = template;
        for (var operator : operators) {
            adjusted = operator.customizer().apply(adjusted);
        }
        return adjusted;
    }

    /**
     * Intercepts the specified SQL statement by calling all globally and locally registered interceptors.
     *
     * <p>Every execution passes through here exactly once, which is also what makes it the point that attributes
     * the statement to what caused it. Interceptors and observers therefore see the origin alongside the
     * statement.</p>
     *
     * @param sql the SQL statement to intercept.
     * @return the adjusted SQL statement.
     */
    @SuppressWarnings({"rawtypes", "unchecked"})
    static Sql intercept(Sql sql) {
        var origin = StatementOriginScope.current();
        // Statements asked for directly already carry the default origin; leave them untouched.
        Sql adjusted = origin == StatementOrigin.DIRECT ? sql : sql.origin(origin);
        // The snapshot is scoped to this thread and cannot change while it is read, so no lock is needed and an
        // operator registering another one from its own apply method cannot disturb this pass.
        Operator[] operators = localOperators();
        if (operators != null) {
            for (var operator : operators) {
                adjusted = operator.interceptor().apply(adjusted);
            }
        }
        // Skip acquiring the read lock entirely when no global operators are registered.
        if (globalOperatorCount > 0) {
            LOCK.readLock().lock();
            try {
                for (var operator : GLOBAL_OPERATORS) {
                    if (operator instanceof Consumer c) {
                        c.accept(adjusted);
                    } else if (operator instanceof UnaryOperator o) {
                        adjusted = (Sql) o.apply(adjusted);
                    }
                }
            } finally {
                LOCK.readLock().unlock();
            }
        }
        // Log what executes, after the interceptors have had their say.
        SqlStatementLogger.log(adjusted);
        return adjusted;
    }
}
