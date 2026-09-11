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
package st.orm.spring.impl;

import static java.sql.Connection.TRANSACTION_NONE;
import static java.sql.Connection.TRANSACTION_READ_COMMITTED;
import static java.sql.Connection.TRANSACTION_READ_UNCOMMITTED;
import static java.sql.Connection.TRANSACTION_REPEATABLE_READ;
import static java.sql.Connection.TRANSACTION_SERIALIZABLE;
import static org.springframework.transaction.TransactionDefinition.ISOLATION_DEFAULT;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_MANDATORY;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_NESTED;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_NEVER;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_NOT_SUPPORTED;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRED;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW;
import static org.springframework.transaction.TransactionDefinition.PROPAGATION_SUPPORTS;

import java.sql.PreparedStatement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.transaction.InvalidIsolationLevelException;
import org.springframework.transaction.NestedTransactionNotSupportedException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSuspensionNotSupportedException;
import org.springframework.transaction.jta.JtaTransactionManager;
import org.springframework.transaction.support.ResourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.util.ClassUtils;
import st.orm.Entity;
import st.orm.PersistenceException;
import st.orm.TransactionTimedOutException;
import st.orm.UnexpectedRollbackException;
import st.orm.core.spi.CacheRetention;
import st.orm.core.spi.EntityCache;
import st.orm.core.spi.TransactionContext;

/**
 * Transaction context that bridges Storm-initiated transactions into Spring's
 * {@link PlatformTransactionManager}.
 *
 * <p>The context maintains a stack of transaction frames for nested transaction support. Frames are opened
 * with {@link #begin(TransactionDefinition)} and finished with {@link #complete(boolean)}; the physical Spring
 * transaction starts lazily, when the first data source touches this context via
 * {@link #useDataSource(DataSource)}.</p>
 *
 * <p>Which frames share a physical transaction follows from the frame structure alone, decided when the frame
 * begins. A frame is transactional by its propagation, with {@code SUPPORTS} taking after its enclosing frame
 * (for the outermost frame, after the Spring transaction active on the thread), and a joining propagation
 * joins the enclosing frame only when that frame is transactional. Frames that share a physical transaction,
 * or the absence of one, form a range owned by the frame that opened it: {@code REQUIRES_NEW},
 * {@code NOT_SUPPORTED} and {@code NEVER} always open their own, and every propagation does when the
 * enclosing frame is not transactional. Touching a data source starts the range the touching frame belongs
 * to, bottom-up, and the data source is checked for consistency within that range only, so a boundary may
 * switch to another data source and the frames beyond it stay unstarted until their own first touch.</p>
 *
 * @since 1.13
 */
public final class SpringTransactionContext implements TransactionContext {

    private static final Logger LOGGER = LoggerFactory.getLogger("st.orm.transaction");

    private static final long NANOS_PER_SECOND = 1_000_000_000L;

    /**
     * The state of one transaction frame.
     *
     * <p>Joined REQUIRED/SUPPORTS/MANDATORY frames share the same entity-cache map instance for identity
     * stability. NESTED shares the outer map too (same physical transaction); on nested rollback, the outer
     * map is cleared. A frame that owns its range keeps its own map (separate physical transaction or
     * non-transactional boundary).</p>
     */
    private static final class TransactionState {
        // Position on the stack, the frame that owns this frame's physical transaction range (this frame when it
        // starts its own Spring transaction), and whether the frame runs inside a transaction. All three follow
        // from the frame structure and are fixed when the frame begins.
        final int index;
        final int ownerIndex;
        final boolean transactional;
        @Nullable TransactionStatus transactionStatus;
        @Nullable PlatformTransactionManager transactionManager;
        @Nullable DataSource dataSource;
        @Nullable TransactionDefinition transactionDefinition;
        // Set when Spring starts a new transaction for this frame, cleared when the template takes it to run the
        // dialect's transaction-opening statements.
        boolean freshTransaction;
        boolean rollbackOnly;
        @Nullable Integer timeoutSeconds;
        @Nullable Long deadlineNanos;
        Map<Class<?>, EntityCache<?, ?>> entityCacheMap = new HashMap<>();

        TransactionState(int index, int ownerIndex, boolean transactional) {
            this.index = index;
            this.ownerIndex = ownerIndex;
            this.transactional = transactional;
        }

        boolean ownsRange() {
            return index == ownerIndex;
        }

        String timeoutDescription() {
            return "isolation=" + isolationName(transactionDefinition == null ? null : transactionDefinition.getIsolationLevel())
                    + ", timeout=" + (timeoutSeconds == null ? "<none>" : timeoutSeconds + "s");
        }

        @Nullable
        Integer remainingSeconds() {
            if (deadlineNanos == null) {
                return null;
            }
            long remaining = deadlineNanos - nowNanos();
            return remaining <= 0L ? 0 : (int) (remaining / NANOS_PER_SECOND);
        }

        boolean deadlineExpired() {
            return deadlineNanos != null && nowNanos() >= deadlineNanos;
        }
    }

    private final Supplier<List<PlatformTransactionManager>> transactionManagers;

    private final List<TransactionState> stack = new ArrayList<>();

    public SpringTransactionContext(Supplier<List<PlatformTransactionManager>> transactionManagers) {
        this.transactionManagers = transactionManagers;
    }

    private static long nowNanos() {
        return System.nanoTime();
    }

    private static long deadlineFromNow(int timeoutSeconds) {
        return nowNanos() + (long) timeoutSeconds * NANOS_PER_SECOND;
    }

    private static String isolationName(@Nullable Integer isolation) {
        if (isolation == null || isolation == ISOLATION_DEFAULT) {
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

    private static String propagationName(int propagation) {
        return switch (propagation) {
            case PROPAGATION_REQUIRED -> "REQUIRED";
            case PROPAGATION_REQUIRES_NEW -> "REQUIRES NEW";
            case PROPAGATION_SUPPORTS -> "SUPPORTS";
            case PROPAGATION_MANDATORY -> "MANDATORY";
            case PROPAGATION_NOT_SUPPORTED -> "NOT SUPPORTED";
            case PROPAGATION_NEVER -> "NEVER";
            case PROPAGATION_NESTED -> "NESTED";
            default -> "UNKNOWN (" + propagation + ")";
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

    /**
     * Returns whether the current frame joined a Spring transaction that was already open, which happens when
     * the surrounding code is {@code @Transactional} or drives Spring's own {@code TransactionTemplate}. Spring
     * then owns the completion, so the frame's commit is a participation rather than a physical commit.
     *
     * <p>A frame whose transaction has not started yet joined nothing, and neither did a frame that opened its
     * own transaction, such as one propagating {@code REQUIRES_NEW}.</p>
     *
     * @return {@code true} if the current frame participates in an externally opened Spring transaction.
     * @since 1.13
     */
    public boolean joinedExistingTransaction() {
        var state = lastOrNull();
        return state != null && state.transactionStatus != null && !state.transactionStatus.isNewTransaction();
    }

    @Override
    public Optional<String> describe() {
        var state = lastOrNull();
        return Optional.ofNullable(state == null ? null : state.timeoutDescription());
    }

    /**
     * Finds an already-known DataSource in range, ensuring consistency.
     */
    @Nullable
    private DataSource findDataSourceInRange(int startIndex, int endIndex) {
        DataSource dataSource = null;
        for (int i = startIndex; i <= endIndex; i++) {
            var stackDataSource = stack.get(i).dataSource;
            if (stackDataSource != null) {
                if (dataSource == null) {
                    dataSource = stackDataSource;
                } else if (dataSource != stackDataSource) {
                    throw new IllegalStateException(
                            "Incompatible DataSource detected within the same transaction range: "
                                    + dataSource + " vs " + stackDataSource);
                }
            }
        }
        return dataSource;
    }

    /**
     * Ensures a Spring TransactionStatus exists for the frames in range using the given data source, applying
     * any pending rollback flags immediately.
     */
    private void ensureStartedInRange(int startIndex, int endIndex, DataSource dataSource) {
        for (int index = startIndex; index <= endIndex; index++) {
            var state = stack.get(index);
            if (state.dataSource == null) {
                state.dataSource = dataSource;
            }
            startTransactionIfNecessary(state, dataSource, index);
            if (state.rollbackOnly && state.transactionStatus != null) {
                state.transactionStatus.setRollbackOnly();
            }
        }
    }

    /**
     * Returns true if the transaction has repeatable-read semantics: the isolation level is
     * {@code REPEATABLE_READ} or higher. Spring uses {@code ISOLATION_DEFAULT} (-1) when no specific isolation
     * level is set; since most databases default to {@code READ_COMMITTED}, this returns false so fresh data is
     * fetched on each read.
     */
    @Override
    public boolean isRepeatableRead() {
        var definition = currentState().transactionDefinition;
        if (definition == null) {
            return false;
        }
        int isolationLevel = definition.getIsolationLevel();
        if (isolationLevel < 0) {
            return false;
        }
        return isolationLevel >= TRANSACTION_REPEATABLE_READ;
    }

    @Override
    public boolean isReadOnly() {
        var state = lastOrNull();
        if (state == null) {
            // No frame of Storm's own: the statement runs inside whatever Spring transaction surrounds it.
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }
        var owner = stack.get(state.ownerIndex);
        if (!owner.transactional) {
            return false;
        }
        var definition = owner.transactionDefinition;
        if (definition == null) {
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }
        if (definition.isReadOnly()) {
            return true;
        }
        // A frame that starts a transaction of its own has its own mode. Any other owning frame may have joined a
        // Spring transaction that was already open, such as a @Transactional method's, whose mode then applies.
        // The frame's own Spring status starts on its first statement, so the manager's flag still describes that
        // enclosing transaction here.
        return definition.getPropagationBehavior() != PROPAGATION_REQUIRES_NEW
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly();
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
        return (EntityCache<? extends Entity<?>, ?>) EntityCaches.entityCache(
                currentState().entityCacheMap, entityType, retention);
    }

    @SuppressWarnings("unchecked")
    @Override
    public EntityCache<? extends Entity<?>, ?> getEntityCache(Class<? extends Entity<?>> entityType) {
        return (EntityCache<? extends Entity<?>, ?>) EntityCaches.getEntityCache(
                currentState().entityCacheMap, entityType);
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
        EntityCaches.clearAll(currentState().entityCacheMap);
    }

    @SuppressWarnings("unchecked")
    @Override
    public <T> Decorator<T> getDecorator(Class<T> resourceType) {
        if (resourceType != PreparedStatement.class) {
            return resource -> resource; // No-op.
        }
        return resource -> {
            var preparedStatement = (PreparedStatement) resource;
            // Dynamic remaining time; fall back to the static definition timeout.
            var state = currentState();
            Integer remaining = state.remainingSeconds();
            Integer seconds;
            if (remaining != null && remaining > 0) {
                seconds = remaining;
            } else if (remaining != null) {
                seconds = 1;
            } else {
                seconds = state.timeoutSeconds;
            }
            if (seconds != null && seconds > 0) {
                try {
                    preparedStatement.setQueryTimeout(seconds);
                } catch (java.sql.SQLException e) {
                    throw new PersistenceException("Failed to set query timeout.", e);
                }
            }
            return resource;
        };
    }

    /**
     * Begins a transaction frame with the specified definition.
     *
     * <p>The physical Spring transaction starts lazily, when the first data source touches this context via
     * {@link #useDataSource(DataSource)}. The frame is finished with {@link #complete(boolean)}.</p>
     *
     * <p>Whether the frame runs inside a transaction, and which frame owns its physical transaction range, are
     * decided here from the enclosing frame, so {@code MANDATORY} and {@code NEVER} are checked against the
     * transaction the enclosing block declares rather than against whatever Spring has started so far. The
     * outermost frame's enclosing transaction is the Spring transaction active on the thread, if any.</p>
     *
     * @throws PersistenceException if the propagation is {@code MANDATORY} and no enclosing transaction exists,
     * or {@code NEVER} and one does.
     */
    public void begin(TransactionDefinition definition) {
        var enclosing = lastOrNull();
        boolean enclosingTransactional = enclosing != null
                ? enclosing.transactional
                : TransactionSynchronizationManager.isActualTransactionActive();
        int index = stack.size();
        int propagation = definition.getPropagationBehavior();
        boolean transactional = switch (propagation) {
            case PROPAGATION_REQUIRED, PROPAGATION_REQUIRES_NEW, PROPAGATION_NESTED -> true;
            case PROPAGATION_MANDATORY -> {
                if (!enclosingTransactional) {
                    throw new PersistenceException("No existing transaction for MANDATORY propagation.");
                }
                yield true;
            }
            case PROPAGATION_NEVER -> {
                if (enclosingTransactional) {
                    throw new PersistenceException("Existing transaction found for NEVER propagation.");
                }
                yield false;
            }
            case PROPAGATION_SUPPORTS -> enclosingTransactional;
            case PROPAGATION_NOT_SUPPORTED -> false;
            default -> throw new IllegalArgumentException("Unknown propagation behavior: " + propagation + ".");
        };
        int ownerIndex = switch (propagation) {
            case PROPAGATION_REQUIRES_NEW, PROPAGATION_NOT_SUPPORTED, PROPAGATION_NEVER -> index;
            // A joining propagation shares the enclosing frame's range when that frame is transactional; inside
            // a non-transactional frame, or as the outermost frame, it opens its own.
            default -> enclosing != null && enclosingTransactional ? enclosing.ownerIndex : index;
        };
        var state = new TransactionState(index, ownerIndex, transactional);
        state.transactionDefinition = definition;
        state.timeoutSeconds = definition.getTimeout() > 0 ? definition.getTimeout() : null;
        state.deadlineNanos = state.timeoutSeconds == null ? null : deadlineFromNow(state.timeoutSeconds);
        if (LOGGER.isDebugEnabled()) {
            LOGGER.debug("""
                    Starting transaction:
                        propagation: {}
                        isolation: {}
                        timeout: {}
                        readOnly: {}""",
                    propagationName(definition.getPropagationBehavior()),
                    isolationName(definition.getIsolationLevel()),
                    definition.getTimeout() == -1 ? "<no timeout>" : definition.getTimeout() + " second(s)",
                    definition.isReadOnly());
        }
        stack.add(state);
    }

    /**
     * Completes the current transaction frame.
     *
     * <p>When {@code rollback} is {@code true}, the frame rolls back; Spring rolls back the physical
     * transaction if one was actually started for this frame. Otherwise the frame commits; the commit path
     * detects timeouts and rollback-only marks and rolls back instead, throwing accordingly.</p>
     */
    public void complete(boolean rollback) {
        if (rollback) {
            rollback();
        } else {
            commit();
        }
    }

    /**
     * Called by the ConnectionProvider before obtaining a JDBC connection.
     *
     * <p>Starts the physical transaction range the current frame belongs to, from its owner up to the current
     * frame, on the given data source. Frames beyond the owner belong to another range: they are neither
     * started nor consulted, so a boundary such as {@code REQUIRES_NEW} may run against a different data source
     * than the block that encloses it, and an enclosing frame that has not been touched yet starts on its own
     * first touch.</p>
     */
    public void useDataSource(DataSource dataSource) {
        var state = currentState();
        var existingDataSource = findDataSourceInRange(state.ownerIndex, state.index);
        if (existingDataSource != null && existingDataSource != dataSource) {
            throw new IllegalStateException(
                    "Incompatible DataSource detected: " + dataSource + " but already using " + existingDataSource + ".");
        }
        ensureStartedInRange(state.ownerIndex, state.index, dataSource);
    }

    public boolean isRollbackOnly() {
        var state = currentState();
        return state.rollbackOnly || (state.transactionStatus != null && state.transactionStatus.isRollbackOnly());
    }

    public void setRollbackOnly() {
        var state = currentState();
        var dataSource = findDataSourceInRange(state.ownerIndex, state.index);
        if (dataSource != null) {
            // Starts statuses and calls setRollbackOnly() where needed.
            ensureStartedInRange(state.ownerIndex, state.index, dataSource);
        }
        state.rollbackOnly = true;
        if (state.transactionStatus != null) {
            state.transactionStatus.setRollbackOnly();
        }
    }

    /**
     * Resolves the transaction manager that owns the given data source.
     *
     * <p>A manager owns the data source when it is a {@link ResourceTransactionManager} working directly on it,
     * which covers {@code DataSourceTransactionManager} and {@code JdbcTransactionManager}, or a JPA transaction
     * manager whose entity manager factory is backed by it, which is what Spring Boot registers when JPA is on
     * the class path. Resolution fails fast when several managers own the same data source: the choice decides
     * which manager completes Storm-initiated transactions, so it must be made by configuration rather than by
     * list order.</p>
     *
     * <p>A JTA transaction manager owns no single data source, since a global transaction spans every resource
     * enlisted in it. It is therefore considered only when no resource-bound manager claims this data source,
     * which leaves an application that configures both with the resource-bound manager and keeps the ambiguity
     * check above meaningful for the case it was written for.</p>
     */
    private PlatformTransactionManager resolveTransactionManager(DataSource dataSource) {
        var managers = transactionManagers.get();
        var candidates = managers.stream()
                .filter(manager -> managesDataSource(manager, dataSource))
                .toList();
        if (candidates.isEmpty() && JTA_PRESENT) {
            candidates = managers.stream()
                    .filter(JtaSupport::isJtaTransactionManager)
                    .toList();
        }
        if (candidates.size() > 1) {
            throw new IllegalStateException(
                    "Multiple TransactionManagers found for DataSource " + dataSource + ": "
                            + candidates.stream()
                                    .map(manager -> manager.getClass().getName())
                                    .collect(Collectors.joining(", "))
                            + ". Keep a single transaction manager per DataSource, or define a "
                            + "TransactionTemplateProvider bean constructed with the manager that must own "
                            + "Storm-initiated transactions.");
        }
        return candidates.stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "No TransactionManager found for DataSource " + dataSource + "."));
    }

    private static final boolean JPA_PRESENT = ClassUtils.isPresent(
            "org.springframework.orm.jpa.JpaTransactionManager",
            SpringTransactionContext.class.getClassLoader());

    private static final boolean JTA_PRESENT = ClassUtils.isPresent(
            "jakarta.transaction.UserTransaction",
            SpringTransactionContext.class.getClassLoader());

    private static boolean managesDataSource(PlatformTransactionManager manager,
                                             DataSource dataSource) {
        if (JPA_PRESENT && JpaSupport.isJpaTransactionManager(manager)) {
            return JpaSupport.managesDataSource(manager, dataSource);
        }
        if (manager instanceof ResourceTransactionManager resourceManager) {
            return resourceFactoryOrNull(resourceManager) == dataSource;
        }
        return false;
    }

    @Nullable
    private static Object resourceFactoryOrNull(ResourceTransactionManager manager) {
        try {
            return manager.getResourceFactory();
        } catch (IllegalStateException e) {
            // The manager has no resource factory configured, so it owns no data source.
            return null;
        }
    }

    /**
     * Touches spring-orm types; only loaded when spring-orm is on the class path.
     */
    private static final class JpaSupport {
        static boolean isJpaTransactionManager(PlatformTransactionManager manager) {
            return manager instanceof JpaTransactionManager;
        }

        static boolean managesDataSource(PlatformTransactionManager manager,
                                         DataSource dataSource) {
            return manager instanceof JpaTransactionManager jpaManager && jpaManager.getDataSource() == dataSource;
        }
    }

    /**
     * Touches {@code JtaTransactionManager}, which carries {@code jakarta.transaction} types in its signature;
     * only loaded when the JTA API is on the class path.
     */
    private static final class JtaSupport {
        static boolean isJtaTransactionManager(PlatformTransactionManager manager) {
            return manager instanceof JtaTransactionManager;
        }
    }

    /**
     * Starts a Spring TransactionStatus for the given state if not already started.
     *
     * <p>A frame that owns its range resolves the manager for the data source it touches; a joined frame takes
     * the manager and data source of the frame it joins, which is in the same range and, since ranges start
     * bottom-up, started by now. The transaction manager is still asked for a status using this frame's
     * definition to honor propagation semantics. Joined frames share the entity cache of the frame they join;
     * an owner keeps its own. The deadline reconciles with the enclosing frame's, whether or not that frame is
     * in the range: an inner block runs inside the outer block's wall clock either way.</p>
     */
    private void startTransactionIfNecessary(TransactionState state, DataSource dataSource, int level) {
        var definition = state.transactionDefinition;
        if (definition == null) {
            throw new IllegalStateException("TransactionDefinition must not be null.");
        }
        if (state.dataSource != null && state.transactionManager != null && state.transactionStatus != null) {
            if (state.dataSource != dataSource) {
                throw new IllegalStateException(
                        "Incompatible DataSource detected: " + dataSource + " but already using " + state.dataSource + ".");
            }
            return;
        }
        if (state.ownsRange()) {
            state.dataSource = dataSource;
            state.transactionManager = resolveTransactionManager(dataSource);
        } else {
            var outer = stack.get(level - 1);
            if (outer.transactionManager == null) {
                throw new IllegalStateException("The joined transaction frame has not been started.");
            }
            state.dataSource = outer.dataSource != null ? outer.dataSource : dataSource;
            state.transactionManager = outer.transactionManager;
            state.entityCacheMap = outer.entityCacheMap;
        }
        if (level > 0) {
            // Reconcile deadlines: inner deadline = min(enclosing, requested).
            Long enclosing = enclosingDeadline(level);
            Long requested = state.timeoutSeconds == null ? null : deadlineFromNow(state.timeoutSeconds);
            if (enclosing == null && requested == null) {
                state.deadlineNanos = null;
            } else if (enclosing == null) {
                state.deadlineNanos = requested;
            } else if (requested == null) {
                state.deadlineNanos = enclosing;
            } else {
                state.deadlineNanos = Math.min(enclosing, requested);
            }
        }
        // else: root, deadline already set in begin(); keep it.
        var transactionStatus = getTransaction(state.transactionManager, definition);
        state.transactionStatus = transactionStatus;
        // A transaction Spring started here has run no statement yet; one it joined may have.
        state.freshTransaction = transactionStatus.isNewTransaction();
        if (state.rollbackOnly) {
            transactionStatus.setRollbackOnly();
        }
    }

    /**
     * Returns the deadline the frames enclosing {@code level} impose, or {@code null} when none has one.
     *
     * <p>A started frame carries its reconciled deadline, so the walk stops there; frames that have not
     * started yet, which happens when a boundary starts before the block that encloses it, contribute their own
     * timeout each.</p>
     */
    @Nullable
    private Long enclosingDeadline(int level) {
        Long deadline = null;
        for (int i = level - 1; i >= 0; i--) {
            var enclosing = stack.get(i);
            if (enclosing.deadlineNanos != null) {
                deadline = deadline == null ? enclosing.deadlineNanos : Math.min(deadline, enclosing.deadlineNanos);
            }
            if (enclosing.transactionStatus != null) {
                break;
            }
        }
        return deadline;
    }

    /**
     * Asks the manager for a transaction status, translating the definition options a manager may refuse into
     * errors that name the transaction option the caller passed rather than Spring's internal one.
     *
     * <p>A JTA manager refuses all three by default: a global transaction has no portable isolation level,
     * nesting needs a {@code jakarta.transaction.TransactionManager} configured for savepoints, and suspending
     * the surrounding transaction, which {@code REQUIRES_NEW} and {@code NOT_SUPPORTED} do, needs that same
     * manager for suspend and resume. Whether a manager accepts them is a property of its configuration that
     * it does not expose, so the option is offered and the refusal translated, rather than rejected up front
     * against a setting Storm cannot read.</p>
     */
    private static TransactionStatus getTransaction(PlatformTransactionManager transactionManager,
                                                    TransactionDefinition definition) {
        try {
            return transactionManager.getTransaction(definition);
        } catch (InvalidIsolationLevelException e) {
            throw new PersistenceException(
                    "Transaction manager " + transactionManager.getClass().getName() + " does not support "
                            + isolationName(definition.getIsolationLevel()) + " isolation. Leave the isolation "
                            + "at its default, or configure the manager to allow custom isolation levels.", e);
        } catch (NestedTransactionNotSupportedException e) {
            throw new PersistenceException(
                    "Transaction manager " + transactionManager.getClass().getName() + " does not support NESTED "
                            + "propagation. Use REQUIRES_NEW for an independent transaction, or REQUIRED to join "
                            + "the surrounding one.", e);
        } catch (TransactionSuspensionNotSupportedException e) {
            throw new PersistenceException(
                    "Transaction manager " + transactionManager.getClass().getName() + " cannot suspend the "
                            + "surrounding transaction, which " + propagationName(definition.getPropagationBehavior())
                            + " propagation requires. Use REQUIRED to join it, or configure the manager for "
                            + "suspend and resume.", e);
        }
    }

    private void commit() {
        var current = currentState();
        if (current.rollbackOnly || current.deadlineExpired()) {
            rollback();
            return;
        }
        var state = popState();
        // If this frame never touched a DataSource/started a status, still enforce timeout deterministically.
        if (state.transactionStatus == null) {
            if (state.deadlineExpired()) {
                throw new TransactionTimedOutException(
                        "Transaction did not complete within timeout (" + state.timeoutSeconds + "s).");
            }
            return;
        }
        try {
            state.transactionManager.commit(state.transactionStatus);
        } catch (org.springframework.transaction.TransactionTimedOutException e) {
            throw timedOut(e);
        } catch (org.springframework.transaction.UnexpectedRollbackException e) {
            // If Spring threw because some inner joined frame marked rollback-only, surface a clean message.
            throw new UnexpectedRollbackException(
                    e.getMessage() == null ? "Transaction was marked rollback-only by a joined scope." : e.getMessage(),
                    e);
        } catch (Exception e) {
            throw new PersistenceException(e);
        }
    }

    private void rollback() {
        var state = popState();
        // If the status never started, just check the deadline and throw appropriately.
        if (state.transactionStatus == null) {
            throwIfDeadlineExpired(state);
            return;
        }
        try {
            state.transactionManager.rollback(state.transactionStatus);
        } catch (org.springframework.transaction.TransactionTimedOutException e) {
            throw timedOut(e);
        } catch (Exception e) {
            throw new PersistenceException(e);
        }
        // If this frame was NESTED, Spring rolled back to a savepoint. The cache map is shared with the outer
        // scope, so it may now be stale relative to the database state.
        var definition = state.transactionDefinition;
        if (definition != null && definition.getPropagationBehavior() == PROPAGATION_NESTED) {
            var outer = lastOrNull();
            if (outer != null) {
                outer.entityCacheMap.clear();
            }
        }
        throwIfDeadlineExpired(state);
    }

    private static void throwIfDeadlineExpired(TransactionState state) {
        if (state.deadlineExpired()) {
            throw new TransactionTimedOutException(
                    "Did not complete within timeout (" + state.timeoutSeconds + "s).");
        }
    }

    private static TransactionTimedOutException timedOut(
            org.springframework.transaction.TransactionTimedOutException e) {
        return new TransactionTimedOutException(
                e.getMessage() == null ? "Did not complete within timeout." : e.getMessage());
    }

    private TransactionState popState() {
        if (stack.isEmpty()) {
            throw new IllegalStateException("No transaction in progress to commit/rollback.");
        }
        return stack.removeLast();
    }
}
