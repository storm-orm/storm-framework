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

import static java.sql.Connection.TRANSACTION_NONE;
import static java.sql.Connection.TRANSACTION_READ_COMMITTED;
import static java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
import static java.sql.Connection.TRANSACTION_REPEATABLE_READ;
import static java.sql.Connection.TRANSACTION_SERIALIZABLE;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import st.orm.Entity;
import st.orm.PersistenceException;
import st.orm.TransactionPropagation;
import st.orm.TransactionTimedOutException;
import st.orm.UnexpectedRollbackException;

/**
 * A JDBC transaction context implementation that provides lightweight transaction management based on JDBC.
 * This supports various transaction propagation behaviors.
 *
 * <p>Key features:</p>
 * <ul>
 *   <li>Supports all transaction propagation modes</li>
 *   <li>Manages transaction isolation levels and read-only settings</li>
 *   <li>Handles connection lifecycle and savepoint management</li>
 *   <li>Binds all state to the context object rather than a thread, supporting coroutines and virtual
 *   threads</li>
 * </ul>
 *
 * <p>Transaction state management: the context maintains a stack of transaction frames. Each frame tracks the
 * connection, savepoint, and transaction attributes; nested transactions are supported through savepoints; the
 * physical connection is bound lazily, when the first data source touches this context.</p>
 *
 * <p>What a frame joins, and which frames share a connection, follows from the frame structure alone, decided
 * when the frame begins and independent of what is bound at the time. A frame is transactional by its
 * propagation, with {@code SUPPORTS} taking after its enclosing frame, and a joining propagation joins the
 * enclosing frame only when that frame is transactional. Frames that share a physical transaction, or the
 * absence of one, form a range owned by the frame that opened it: {@code REQUIRES_NEW}, {@code NOT_SUPPORTED}
 * and {@code NEVER} always open their own, and every propagation does when the enclosing frame is not
 * transactional. Binding a data source binds the range the touching frame belongs to, bottom-up, and the data
 * source is checked for consistency within that range only, so a boundary may switch to another data source
 * and the frames beyond it stay unbound until their own first touch.</p>
 *
 * @see TransactionPropagation for supported transaction propagation modes
 * @since 1.13
 */
public final class JdbcTransactionContext implements TransactionContext {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.transaction");

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * The state of one transaction frame.
     *
     * <p>Joined REQUIRED/SUPPORTS/MANDATORY frames share the same entity-cache map instance for identity
     * stability. NESTED also shares the map (same connection), but on savepoint rollback the map is cleared to
     * avoid stale state.</p>
     */
    private static final class TransactionState {
        final TransactionPropagation propagation;
        final @Nullable Integer isolationLevel;
        final @Nullable Integer timeoutSeconds;
        final @Nullable Boolean readOnly;
        // Position on the stack, the frame that owns this frame's physical transaction range (this frame when it
        // opens its own connection), and whether the frame runs inside a transaction. All three follow from the
        // frame structure and are fixed when the frame begins.
        final int index;
        final int ownerIndex;
        final boolean transactional;
        final String transactionId = UUID.randomUUID().toString();
        // Guards lazy connection binding: two threads (e.g. a coroutine resumption racing a task) must not
        // assign different connections to the same state. A lock rather than synchronized, so JDBC calls do
        // not pin virtual threads.
        final ReentrantLock bindLock = new ReentrantLock();
        @Nullable DataSource dataSource;
        volatile @Nullable Connection connection;
        boolean ownsConnection;
        // Set when this frame opens a physical transaction, cleared when the template takes it to run the
        // dialect's transaction-opening statements.
        boolean freshTransaction;
        @Nullable Integer originalIsolationLevel;
        @Nullable Boolean originalReadOnly;
        @Nullable Boolean originalAutoCommit;
        @Nullable Savepoint savepoint;
        boolean rollbackOnly;
        boolean rollbackInherited;
        @Nullable Long deadlineNanos;
        Map<Class<?>, EntityCache<?, ?>> entityCacheMap = new HashMap<>();

        TransactionState(TransactionPropagation propagation,
                         @Nullable Integer isolationLevel,
                         @Nullable Integer timeoutSeconds,
                         @Nullable Boolean readOnly,
                         int index,
                         int ownerIndex,
                         boolean transactional) {
            this.propagation = propagation;
            this.isolationLevel = isolationLevel;
            this.timeoutSeconds = timeoutSeconds;
            this.readOnly = readOnly;
            this.index = index;
            this.ownerIndex = ownerIndex;
            this.transactional = transactional;
        }

        boolean ownsRange() {
            return index == ownerIndex;
        }

        String timeoutDescription() {
            return "isolation=" + isolationName(isolationLevel) + ", timeout="
                    + (timeoutSeconds == null ? "<none>" : timeoutSeconds + "s");
        }

        @Nullable
        Integer remainingSeconds() {
            if (deadlineNanos == null) {
                return null;
            }
            long remaining = deadlineNanos - nowNanos();
            if (remaining <= 0L) {
                return 0;
            }
            if (remaining >= (long) Integer.MAX_VALUE * NANOS_PER_SECOND) {
                return Integer.MAX_VALUE;
            }
            return (int) (remaining / NANOS_PER_SECOND);
        }
    }

    private final List<TransactionState> stack = new ArrayList<>();

    private static long nowNanos() {
        return System.nanoTime();
    }

    private static long deadlineFromNow(int timeoutSeconds) {
        return nowNanos() + (long) timeoutSeconds * NANOS_PER_SECOND;
    }

    private static String isolationName(@Nullable Integer isolation) {
        if (isolation == null) {
            return "DEFAULT";
        }
        return switch (isolation) {
            case TRANSACTION_NONE -> "NONE";
            case TRANSACTION_READ_UNCOMMITTED -> "READ_UNCOMMITTED";
            case TRANSACTION_READ_COMMITTED -> "READ_COMMITTED";
            case TRANSACTION_REPEATABLE_READ -> "REPEATABLE_READ";
            case TRANSACTION_SERIALIZABLE -> "SERIALIZABLE";
            default -> "UNKNOWN (" + isolation + ")";
        };
    }

    private TransactionState currentState() {
        if (stack.isEmpty()) {
            throw new IllegalStateException("No transaction active.");
        }
        return stack.getLast();
    }

    @Nullable
    private TransactionState lastOrNull() {
        return stack.isEmpty() ? null : stack.getLast();
    }

    @Override
    public Optional<String> describe() {
        var state = lastOrNull();
        return Optional.ofNullable(state == null ? null : state.timeoutDescription());
    }

    /**
     * Obtains a JDBC connection for the current transaction, creating or reusing one based on the transaction
     * propagation rules.
     *
     * <p>Connections are expected to arrive from the data source in auto-commit mode.</p>
     *
     * @param dataSource the data source to get the connection from.
     * @return the JDBC connection.
     * @throws PersistenceException if the connection cannot be obtained.
     */
    public Connection getConnection(DataSource dataSource) {
        return getConnection(dataSource, false);
    }

    /**
     * Obtains a JDBC connection for the current transaction, creating or reusing one based on the transaction
     * propagation rules.
     *
     * <p>The {@code manualCommitConnections} declaration selects which arrival state is correct for connections
     * freshly obtained from the data source; the arrival state is verified in both directions, so a wrong
     * declaration fails fast rather than silently corrupting transaction semantics. In declared mode the
     * transactional path performs no auto-commit flips and releases connections in their arrived state.</p>
     *
     * @param dataSource the data source to get the connection from.
     * @param manualCommitConnections whether the data source is declared to hand out connections with
     * auto-commit disabled.
     * @return the JDBC connection.
     * @throws PersistenceException if the connection cannot be obtained.
     * @since 1.14
     */
    public Connection getConnection(DataSource dataSource, boolean manualCommitConnections) {
        useDataSource(dataSource, manualCommitConnections);
        return currentState().connection;
    }

    /**
     * Returns the current active connection, or {@code null} when no connection is bound.
     */
    @Nullable
    public Connection currentConnection() {
        var state = lastOrNull();
        return state == null ? null : state.connection;
    }

    /**
     * Returns true if the transaction has repeatable-read semantics: the isolation level is
     * {@code REPEATABLE_READ} or higher. When the isolation level is not explicitly set, the database default is
     * used; since most databases default to {@code READ_COMMITTED}, this returns false so fresh data is fetched
     * on each read.
     */
    @Override
    public boolean isRepeatableRead() {
        var isolationLevel = currentState().isolationLevel;
        if (isolationLevel == null) {
            return false;
        }
        return isolationLevel >= TRANSACTION_REPEATABLE_READ;
    }

    @Override
    public boolean isReadOnly() {
        var state = lastOrNull();
        if (state == null) {
            return false;
        }
        var owner = stack.get(state.ownerIndex);
        return owner.transactional && Boolean.TRUE.equals(owner.readOnly);
    }

    @Override
    public boolean takeFreshTransaction() {
        var state = lastOrNull();
        if (state == null) {
            return false;
        }
        var owner = stack.get(state.ownerIndex);
        if (!owner.freshTransaction) {
            return false;
        }
        owner.freshTransaction = false;
        return true;
    }

    @SuppressWarnings("unchecked")
    @Override
    public EntityCache<? extends Entity<?>, ?> entityCache(Class<? extends Entity<?>> entityType,
                                                           CacheRetention retention) {
        return (EntityCache<? extends Entity<?>, ?>) currentState().entityCacheMap
                .computeIfAbsent(entityType, ignore -> new EntityCacheImpl<>(retention));
    }

    @SuppressWarnings("unchecked")
    @Override
    public EntityCache<? extends Entity<?>, ?> getEntityCache(Class<? extends Entity<?>> entityType) {
        var cache = (EntityCache<? extends Entity<?>, ?>) currentState().entityCacheMap.get(entityType);
        if (cache == null) {
            throw new IllegalStateException("No entity cache exists for " + entityType.getName() + ".");
        }
        return cache;
    }

    @SuppressWarnings("unchecked")
    @Nullable
    @Override
    public EntityCache<? extends Entity<?>, ?> findEntityCache(Class<? extends Entity<?>> entityType) {
        return (EntityCache<? extends Entity<?>, ?>) currentState().entityCacheMap.get(entityType);
    }

    /**
     * Clears all entity caches associated with this transaction context.
     */
    @Override
    public void clearAllEntityCaches() {
        currentState().entityCacheMap.values().forEach(EntityCache::clear);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Decorator<T> getDecorator(Class<T> resourceType) {
        if (resourceType != PreparedStatement.class) {
            return resource -> resource; // No-op.
        }
        return resource -> {
            var preparedStatement = (PreparedStatement) resource;
            // Prefer dynamic remaining time; fall back to static seconds if present.
            var state = currentState();
            Integer remaining = state.remainingSeconds();
            Integer seconds;
            if (remaining != null && remaining > 0) {
                seconds = remaining;
            } else if (remaining != null) {
                seconds = 1; // Already out of time: force a fast timeout.
            } else {
                seconds = state.timeoutSeconds;
            }
            if (seconds != null && seconds > 0) {
                try {
                    preparedStatement.setQueryTimeout(seconds);
                } catch (SQLException e) {
                    throw new PersistenceException("Failed to set query timeout.", e);
                }
            }
            return resource;
        };
    }

    /**
     * Begins a transaction frame with the specified attributes.
     *
     * <p>The physical connection is bound lazily, when the first data source touches this context via
     * {@code useDataSource}. The frame is finished with {@link #complete(boolean)}.</p>
     *
     * <p>Whether the frame runs inside a transaction, and which frame owns its physical transaction range, are
     * decided here from the enclosing frame, so {@code MANDATORY} and {@code NEVER} are checked against the
     * transaction the enclosing block declares rather than against whatever it has bound so far.</p>
     *
     * @throws PersistenceException if the propagation is {@code MANDATORY} and no enclosing transaction exists,
     * or {@code NEVER} and one does.
     */
    public void begin(TransactionPropagation propagation,
               @Nullable Integer isolation,
               @Nullable Integer timeoutSeconds,
               @Nullable Boolean readOnly) {
        var enclosing = lastOrNull();
        boolean enclosingTransactional = enclosing != null && enclosing.transactional;
        int index = stack.size();
        boolean transactional = switch (propagation) {
            case REQUIRED, REQUIRES_NEW, NESTED -> true;
            case MANDATORY -> {
                if (!enclosingTransactional) {
                    throw new PersistenceException("No existing transaction for MANDATORY propagation.");
                }
                yield true;
            }
            case NEVER -> {
                if (enclosingTransactional) {
                    throw new PersistenceException("Existing transaction found for NEVER propagation.");
                }
                yield false;
            }
            case SUPPORTS -> enclosingTransactional;
            case NOT_SUPPORTED -> false;
        };
        int ownerIndex = switch (propagation) {
            case REQUIRES_NEW, NOT_SUPPORTED, NEVER -> index;
            // A joining propagation shares the enclosing frame's range when that frame is transactional; inside
            // a non-transactional frame it opens its own, a transaction or a plain connection as its own
            // transactionality says.
            case REQUIRED, SUPPORTS, MANDATORY, NESTED -> enclosingTransactional ? enclosing.ownerIndex : index;
        };
        var state = new TransactionState(propagation, isolation, timeoutSeconds, readOnly, index, ownerIndex,
                transactional);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("""
                    Starting transaction ({}):
                        propagation: {}
                        isolation: {}
                        timeout: {}
                        readOnly: {}""",
                    state.transactionId, propagation, isolationName(isolation),
                    timeoutSeconds == null ? "<no timeout>" : timeoutSeconds + " second(s)", readOnly);
        }
        state.deadlineNanos = timeoutSeconds == null ? null : deadlineFromNow(timeoutSeconds);
        stack.add(state);
    }

    /**
     * Completes the current transaction frame.
     *
     * <p>When {@code rollback} is {@code true}, the frame rolls back and any rollback failure is suppressed so
     * the caller can surface the original error. Otherwise the frame commits; the commit path detects timeouts
     * and rollback-only marks and rolls back instead, throwing accordingly.</p>
     *
     * @param rollback whether the transactional work failed and the frame must be rolled back.
     */
    public void complete(boolean rollback) {
        if (rollback) {
            if (LOGGER.isTraceEnabled()) {
                var state = lastOrNull();
                LOGGER.trace("Transaction failed ({}).", state == null ? null : state.transactionId);
            }
            rollback(true); // Suppress any rollback exception to ensure handling of exception.
        } else {
            // Let commit detect timeout or rollback-only.
            commit();
        }
    }

    /**
     * Whether the current transaction is marked for rollback-only.
     */
    public boolean isRollbackOnly() {
        var state = lastOrNull();
        return state != null && state.rollbackOnly;
    }

    /**
     * Marks the current transaction so that it will roll back on completion.
     */
    public void setRollbackOnly() {
        var currentState = currentState();
        LOGGER.debug("Marking transaction for rollback ({}).", currentState.transactionId);
        currentState.rollbackOnly = true;
        currentState.rollbackInherited = false; // Reset inherited flag, if applicable.
        // Do NOT propagate from NESTED (savepoint) scopes and do NOT propagate from owners (outermost or
        // REQUIRES_NEW).
        if (currentState.propagation == TransactionPropagation.NESTED || currentState.ownsRange()) {
            return;
        }
        markRangeRollbackOnly(currentState);
    }

    /**
     * Marks the joined frames beneath the given frame rollback-only, up to and including the frame that owns
     * the physical transaction, stopping at a NESTED boundary because a savepoint scope settles on its own.
     */
    private void markRangeRollbackOnly(TransactionState from) {
        for (int i = from.index - 1; i >= from.ownerIndex; i--) {
            var state = stack.get(i);
            if (state.propagation == TransactionPropagation.NESTED) {
                break; // Do not cross NESTED boundary.
            }
            state.rollbackOnly = true;
            state.rollbackInherited = true; // Indicates caller-triggered.
        }
    }

    /**
     * Binds the physical transaction range the current frame belongs to, from its owner up to the current frame,
     * to the given data source. Frames beyond the owner belong to another range: they are neither bound nor
     * consulted, so a boundary such as {@code REQUIRES_NEW} may run against a different data source than the
     * block that encloses it, and an enclosing frame that has not been touched yet binds on its own first touch.
     */
    private void useDataSource(DataSource dataSource, boolean manualCommitConnections) {
        var current = currentState();
        for (int i = current.ownerIndex; i < stack.size(); i++) {
            var state = stack.get(i);
            if (state.connection != null) {
                // Already bound: the frames of one range share one connection, so they share one data source.
                if (state.dataSource != dataSource) {
                    throw new PersistenceException(
                            "Incompatible DataSource: " + dataSource + " but already using " + state.dataSource + ".");
                }
                continue;
            }
            if (state.ownsRange()) {
                if (state.transactional) {
                    openNewTransaction(state, dataSource, manualCommitConnections);
                } else {
                    openConnection(state, dataSource, manualCommitConnections); // Non-transactional.
                }
                continue;
            }
            // A joined frame: the enclosing frame is in the same range and, walking bottom-up, bound by now.
            var outer = stack.get(i - 1);
            if (state.propagation == TransactionPropagation.NESTED) {
                openNestedTransaction(state, outer, dataSource);
            } else {
                joinOuterTransaction(state, outer);
            }
        }
    }

    private void openNestedTransaction(TransactionState state,
                                       TransactionState outer,
                                       DataSource dataSource) {
        if (outer.dataSource != dataSource) {
            throw new PersistenceException(
                    "Incompatible DataSource: expected " + outer.dataSource + ", got " + dataSource + ".");
        }
        try {
            var outerConnection = outer.connection;
            var savepoint = outerConnection.setSavepoint();
            LOGGER.debug("Creating nested transaction with savepoint {} on {} ({}).",
                    savepoint, outerConnection, state.transactionId);
            state.connection = outerConnection;
            state.dataSource = dataSource;
            state.savepoint = savepoint;
            state.ownsConnection = false;
            // Share caches since this is the same JDBC connection/transaction. If we roll back to this
            // savepoint, we will clear the shared cache to avoid stale state. The connection settings are not
            // captured: close() only restores settings on frames that own the connection.
            state.entityCacheMap = outer.entityCacheMap;
            Long innerRequested = state.timeoutSeconds == null ? null : deadlineFromNow(state.timeoutSeconds);
            if (outer.deadlineNanos == null && innerRequested == null) {
                state.deadlineNanos = null;
            } else if (outer.deadlineNanos == null) {
                state.deadlineNanos = innerRequested;
            } else if (innerRequested == null) {
                state.deadlineNanos = outer.deadlineNanos;
            } else {
                state.deadlineNanos = Math.min(outer.deadlineNanos, innerRequested);
            }
        } catch (SQLException e) {
            throw new PersistenceException("Failed to create savepoint.", e);
        }
    }

    /**
     * Commits the current transaction block. If nested, releases the savepoint; if outermost (or REQUIRES_NEW),
     * commits and closes; if joined REQUIRED, just restores settings.
     */
    private void commit() {
        var current = currentState();
        boolean expired = current.deadlineNanos != null && nowNanos() >= current.deadlineNanos;
        if (current.rollbackOnly || expired) {
            rollback(false);
            return;
        }
        var state = popState();
        var connection = state.connection;
        // If no connection was ever used, still enforce timeout deterministically.
        if (connection == null) {
            boolean expiredAfter = state.deadlineNanos != null && nowNanos() >= state.deadlineNanos;
            if (expiredAfter) {
                throw new TransactionTimedOutException(
                        "Did not complete within timeout [" + state.timeoutDescription() + "].");
            }
            return;
        }
        try {
            if (state.savepoint != null) {
                LOGGER.debug("Committing nested scope; releasing savepoint {} on {} ({}).",
                        state.savepoint, connection, state.transactionId);
                connection.releaseSavepoint(state.savepoint);
            } else if (state.ownsConnection) {
                LOGGER.debug("Committing transaction on {} ({}).", connection, state.transactionId);
                try {
                    if (!connection.getAutoCommit()) {
                        connection.commit();
                    }
                } finally {
                    // Always return the connection to the pool — even if commit() threw — so the pool does not
                    // retain a connection in a different state than it handed out (which would fail the
                    // arrival-state precondition on the next openNewTransaction()).
                    try {
                        close(connection, state);
                    } catch (SQLException closeException) {
                        LOGGER.warn("Failed to close connection after commit ({}).", state.transactionId, closeException);
                    }
                }
            }
            // else: joined REQUIRED: nothing to do yet.
        } catch (SQLException e) {
            throw new PersistenceException("Commit failed.", e);
        }
    }

    /**
     * Rolls back the current transaction frame.
     */
    private void rollback(boolean suppressException) {
        var state = popState();
        boolean expired = state.deadlineNanos != null && nowNanos() >= state.deadlineNanos;
        var connection = state.connection;
        try {
            if (state.savepoint != null && connection != null) {
                LOGGER.debug("Rolling back to savepoint {} on {} ({}).", state.savepoint, connection, state.transactionId);
                connection.rollback(state.savepoint);
                // We shared the cache map with the outer scope. After savepoint rollback, cached entities can
                // be inconsistent with the DB state. Invalidate the cache for the remainder of the outer
                // transaction.
                var outer = lastOrNull();
                if (outer != null) {
                    outer.entityCacheMap.clear();
                }
            } else if (state.ownsConnection && connection != null) {
                LOGGER.debug("Rolling back transaction on {} ({}).", connection, state.transactionId);
                try {
                    if (!connection.getAutoCommit()) {
                        connection.rollback();
                    }
                } finally {
                    // Always return the connection to the pool — even if rollback() threw — so the pool does
                    // not retain a connection in a different state than it handed out (which would fail the
                    // arrival-state precondition on the next openNewTransaction()).
                    try {
                        close(connection, state);
                    } catch (SQLException closeException) {
                        LOGGER.warn("Failed to close connection after rollback ({}).", state.transactionId, closeException);
                    }
                }
            } else if (!state.ownsRange()) {
                // A joined frame, bound or not: its failure dooms the physical transaction it belongs to.
                LOGGER.debug("Marking transaction for rollback ({}).", state.transactionId);
                markRangeRollbackOnly(state);
            }
            // else: an owner that never bound a connection; nothing physical happened, and the frames beyond
            // it belong to another range.
        } catch (SQLException e) {
            if (!suppressException) {
                throw new PersistenceException("Rollback failed.", e);
            }
        }
        if (!suppressException && expired) {
            throw new TransactionTimedOutException(
                    "Did not complete within timeout [" + state.timeoutDescription() + "].");
        }
        if (!suppressException && state.rollbackInherited) {
            throw new UnexpectedRollbackException("Transaction was marked rollback-only by a joined scope.");
        }
    }

    private void close(Connection connection, TransactionState state) throws SQLException {
        if (state.originalIsolationLevel != null) {
            connection.setTransactionIsolation(state.originalIsolationLevel);
        }
        if (state.originalReadOnly != null) {
            connection.setReadOnly(state.originalReadOnly);
        }
        // Restore auto-commit only when this frame changed it, so the pool gets the connection back in its
        // arrived state: auto-commit for regular pools, manual-commit for declared pools.
        if (state.originalAutoCommit != null) {
            connection.setAutoCommit(state.originalAutoCommit);
        }
        connection.close();
    }

    private TransactionState popState() {
        if (stack.isEmpty()) {
            throw new IllegalStateException("No transaction active.");
        }
        return stack.removeLast();
    }

    /**
     * Opens a fresh JDBC connection for REQUIRED (when no outer) or REQUIRES_NEW.
     */
    private void openNewTransaction(TransactionState state, DataSource dataSource, boolean manualCommitConnections) {
        // Lock the TransactionState so that only one thread can initialize its connection. Without this, two
        // threads could race to assign different connections (or tx modes) to the same state. Ensuring a
        // single, consistent connection instance lets downstream logic detect and fail fast on concurrent
        // access within the same transaction.
        if (state.connection != null) {
            return;
        }
        state.bindLock.lock();
        try {
            if (state.connection != null) {
                return;
            }
            LOGGER.trace("Opening new transaction ({}).", state.transactionId);
            Connection connection;
            try {
                connection = dataSource.getConnection();
            } catch (SQLException e) {
                throw new PersistenceException("Failed to open transaction.", e);
            }
            LOGGER.trace("Obtained connection {} ({}).", connection, state.transactionId);
            verifyArrivedAutoCommitState(connection, manualCommitConnections);
            try {
                // Only read and change the connection settings when explicitly requested: reading the isolation
                // level can cost a round trip on some drivers, and close() restores (another round trip) only what
                // was captured here.
                if (state.isolationLevel != null) {
                    state.originalIsolationLevel = connection.getTransactionIsolation();
                    connection.setTransactionIsolation(state.isolationLevel);
                }
                if (state.readOnly != null) {
                    state.originalReadOnly = connection.isReadOnly();
                    connection.setReadOnly(state.readOnly);
                }
                // A declared manual-commit pool hands the connection out with auto-commit already disabled (the
                // arrival state is verified above), so the transactional path performs no flips and the
                // connection is released in its arrived state.
                if (!manualCommitConnections) {
                    connection.setAutoCommit(false);
                    state.originalAutoCommit = true;
                }
            } catch (SQLException e) {
                closeQuietly(connection, state.transactionId);
                throw new PersistenceException("Failed to open transaction.", e);
            }
            state.dataSource = dataSource;
            state.ownsConnection = true;
            state.freshTransaction = true;
            if (state.deadlineNanos == null && state.timeoutSeconds != null) {
                state.deadlineNanos = deadlineFromNow(state.timeoutSeconds);
            }
            state.connection = connection;
        } finally {
            state.bindLock.unlock();
        }
    }

    /**
     * Opens a non-transactional connection (auto-commit).
     */
    private void openConnection(TransactionState state, DataSource dataSource, boolean manualCommitConnections) {
        // Lock the TransactionState so that only one thread can initialize its connection; see
        // openNewTransaction for the rationale.
        if (state.connection != null) {
            return;
        }
        state.bindLock.lock();
        try {
            if (state.connection != null) {
                return;
            }
            LOGGER.trace("Opening connection ({}).", state.transactionId);
            Connection connection;
            try {
                connection = dataSource.getConnection();
            } catch (SQLException e) {
                throw new PersistenceException("Failed to open connection.", e);
            }
            LOGGER.trace("Obtained connection {} ({}).", connection, state.transactionId);
            verifyArrivedAutoCommitState(connection, manualCommitConnections);
            // Non-transactional scopes execute each statement in auto-commit mode. On a declared manual-commit
            // pool the connection arrives with auto-commit disabled, so auto-commit is enabled for the scope and
            // restored to the arrived state before the connection is released; leaving it disabled would let the
            // pool roll back uncommitted statements on release, silently losing writes.
            if (manualCommitConnections) {
                try {
                    connection.setAutoCommit(true);
                    state.originalAutoCommit = false;
                } catch (SQLException e) {
                    closeQuietly(connection, state.transactionId);
                    throw new PersistenceException("Failed to open connection.", e);
                }
            }
            state.dataSource = dataSource;
            state.ownsConnection = true;
            // Non-transactional: deadline is not meaningful (no commit), but the decorator still uses
            // remainingSeconds().
            state.deadlineNanos = state.timeoutSeconds == null ? null : deadlineFromNow(state.timeoutSeconds);
            state.connection = connection;
        } finally {
            state.bindLock.unlock();
        }
    }

    /**
     * Verifies that a connection freshly obtained from a data source arrived in the declared auto-commit state,
     * closing the connection and failing fast on a mismatch.
     *
     * <p>An undeclared template requires auto-commit arrivals: a connection arriving with auto-commit disabled
     * is indistinguishable from a connection carrying an unfinished transaction, and silently adopting it would
     * let Storm commit or roll back work it does not own. A declared template requires manual-commit arrivals
     * for the same reason in the opposite direction: a trusted-but-wrong declaration would commit transactional
     * work per statement and turn rollback into a no-op. The declaration only selects which arrival state is
     * correct; misconfiguration stays loud in every combination.</p>
     */
    static void verifyArrivedAutoCommitState(Connection connection, boolean manualCommitConnections) {
        boolean autoCommit;
        try {
            autoCommit = connection.getAutoCommit();
        } catch (SQLException e) {
            closeQuietly(connection, null);
            throw new PersistenceException("Failed to determine the auto-commit state of the connection.", e);
        }
        if (autoCommit == manualCommitConnections) {
            closeQuietly(connection, null);
            if (manualCommitConnections) {
                throw new PersistenceException("""
                        Connection returned from DataSource arrived in auto-commit mode, but the template \
                        declares manual-commit connections. Remove the manualCommitConnections declaration, or \
                        configure the pool to hand out manual-commit connections.""");
            }
            throw new PersistenceException("""
                    Connection returned from DataSource must be in auto-commit mode, but arrived with \
                    auto-commit disabled. Either the pool is configured for manual-commit connections, or the \
                    connection carries an unfinished transaction. Configure the pool to hand out auto-commit \
                    connections, or declare the pool's mode via \
                    ORMTemplate.builder(dataSource).manualCommitConnections() if manual-commit connections are \
                    intended.""");
        }
    }

    private static void closeQuietly(Connection connection, @Nullable String transactionId) {
        try {
            connection.close();
        } catch (SQLException e) {
            LOGGER.warn("Failed to close connection after failed open ({}).", transactionId, e);
        }
    }

    /**
     * Joins an existing transaction.
     */
    private void joinOuterTransaction(TransactionState state, TransactionState outer) {
        LOGGER.debug("Joining transaction ({} -> {}).", state.transactionId, outer.transactionId);
        var connection = outer.connection;
        if (connection == null) {
            throw new PersistenceException("No outer connection to join.");
        }
        state.dataSource = outer.dataSource;
        state.ownsConnection = false;
        // Share the entity cache map with the transaction we joined. The connection settings are not
        // captured: close() only restores settings on frames that own the connection.
        state.entityCacheMap = outer.entityCacheMap;
        Long innerRequested = state.timeoutSeconds == null ? null : deadlineFromNow(state.timeoutSeconds);
        if (outer.deadlineNanos == null && innerRequested == null) {
            // Keep any deadline already set when the frame began.
        } else if (outer.deadlineNanos == null) {
            state.deadlineNanos = innerRequested;
        } else if (innerRequested == null) {
            state.deadlineNanos = outer.deadlineNanos;
        } else {
            state.deadlineNanos = Math.min(outer.deadlineNanos, innerRequested);
        }
        state.connection = connection;
    }
}
