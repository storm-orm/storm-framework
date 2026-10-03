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
package st.orm.core.spi;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.sql.Connection;
import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;

/**
 * Lets a transaction's connection serve one caller at a time.
 *
 * <p>An access holds the connection from acquisition until its statement is released, and belongs to the caller
 * that issued it. The caller is the current thread, unless an integration installs a caller identity through
 * {@link #callerHolder()}: a coroutine keeps its identity when it resumes on another thread, and a coroutine started
 * concurrently, such as with {@code launch} or {@code async}, gets its own. The owning caller can access the
 * connection again while it holds it, and another caller that accesses it meanwhile fails fast instead of waiting
 * behind the running statement.</p>
 *
 * <p>Each transaction context keeps one guard, so the check costs a volatile read and, for the first access, an
 * uncontended compare-and-set.</p>
 *
 * @see TransactionContext#connectionGuard()
 * @since 1.15
 */
public final class ConnectionGuard {

    private static final ThreadLocal<@Nullable Object> CALLER = new ThreadLocal<>();
    private static final VarHandle OWNER;

    static {
        try {
            OWNER = MethodHandles.lookup().findVarHandle(ConnectionGuard.class, "owner", Object.class);
        } catch (ReflectiveOperationException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private volatile @Nullable Object owner;
    // Only the owner changes the depth; giving up ownership clears the volatile owner after it, which publishes it to
    // the next owner. An access that ends on another thread is handed there with synchronization of its own, which
    // carries the depth along.
    private int depth;

    /**
     * Returns the thread local that holds the identity of the current caller.
     *
     * <p>Intended for integrations whose units of work move between threads, such as coroutine context elements,
     * which install the identity of the running unit for the duration of each of its slices. When the holder is
     * empty, the current thread is the caller.</p>
     *
     * @return the thread local holding the identity of the current caller.
     */
    public static ThreadLocal<@Nullable Object> callerHolder() {
        return CALLER;
    }

    /**
     * Registers an access by the current caller.
     *
     * @param connection the connection that is accessed, named in the failure.
     * @throws PersistenceException if another caller holds the connection.
     */
    public void acquire(Connection connection) {
        var caller = CALLER.get();
        if (caller == null) {
            caller = Thread.currentThread();
        }
        var current = owner;
        if (current == caller) {
            depth++;
            return;
        }
        if (current == null && OWNER.compareAndSet(this, null, caller)) {
            depth = 1;
            return;
        }
        throw new PersistenceException(("Concurrent access on %s: another caller in the transaction is still using "
                + "its connection, which serves one caller at a time. Await concurrent work before starting the next, "
                + "or give it its own transaction.").formatted(connection));
    }

    /**
     * Ends an access registered by {@link #acquire(Connection)}. The access may end on another thread than the one
     * that registered it, as when a stream is closed elsewhere.
     */
    public void release() {
        if (owner != null && --depth == 0) {
            owner = null;
        }
    }
}
