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
package st.orm.spring;

import static java.util.Optional.empty;
import static java.util.Optional.of;

import java.sql.Connection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import st.orm.Entity;
import st.orm.PersistenceException;
import st.orm.TransactionIsolation;
import st.orm.TransactionPropagation;
import st.orm.core.spi.CacheRetention;
import st.orm.core.spi.EntityCache;
import st.orm.core.spi.TransactionContext;
import st.orm.core.spi.TransactionStatus;
import st.orm.core.spi.TransactionTemplate;
import st.orm.core.spi.TransactionTemplateProvider;
import st.orm.spring.impl.EntityCaches;
import st.orm.spring.impl.SpringTransactionContext;

/**
 * Transaction template provider that bridges Storm's transaction API into Spring's
 * {@link PlatformTransactionManager} and exposes a transaction context for Spring-managed transactions.
 *
 * <p>Two directions are covered. Storm-initiated transactions ({@code Transactions.transaction(...)} in Java,
 * {@code transaction { }} in Kotlin) open through Spring's transaction manager with full propagation,
 * isolation, timeout and read-only support; construct the provider with the transaction managers of the owning
 * application context (the Spring Boot starter does this automatically). Spring-initiated transactions
 * ({@code @Transactional}, Spring's {@code TransactionTemplate}) are observed via a context bound to
 * {@code TransactionSynchronizationManager}, enabling transaction-scoped entity caching and dirty tracking.</p>
 *
 * <p>The no-argument constructor creates a provider for Spring-managed transactions only: {@code open()} fails
 * with a descriptive error, while the {@code @Transactional} cache scoping keeps working.</p>
 *
 * <p>Templates that should share transactions must be configured with the <em>same provider instance</em>, so
 * integrations expose one provider per application context.</p>
 *
 * @see SpringConnectionProvider
 * @since 1.13
 */
public class SpringTransactionTemplateProvider implements TransactionTemplateProvider {

    private static final Object CONTEXT_RESOURCE_KEY =
            SpringTransactionTemplateProvider.class.getName() + ".TX_CONTEXT";

    private static final ThreadLocal<TransactionContext> CONTEXT_HOLDER = new ThreadLocal<>();

    private final @Nullable Supplier<List<PlatformTransactionManager>> transactionManagers;

    /**
     * Creates a provider for Spring-managed transactions only: Storm-initiated transactions are rejected with
     * a descriptive error, while {@code @Transactional} entity-cache scoping works.
     */
    public SpringTransactionTemplateProvider() {
        this.transactionManagers = null;
    }

    /**
     * Creates a provider that bridges Storm-initiated transactions through the given transaction managers; the
     * manager owning the touched {@code DataSource} is resolved lazily, when the first data source touches the
     * transaction. Both JDBC transaction managers and JPA transaction managers backed by the data source
     * qualify.
     *
     * @param transactionManagers supplies the transaction managers of the owning application context.
     */
    public SpringTransactionTemplateProvider(Supplier<List<PlatformTransactionManager>> transactionManagers) {
        this.transactionManagers = transactionManagers;
    }

    /**
     * Creates a provider for an eagerly resolved list of transaction managers.
     *
     * @param transactionManagers the transaction managers of the owning application context.
     */
    public SpringTransactionTemplateProvider(List<PlatformTransactionManager> transactionManagers) {
        this(() -> transactionManagers);
    }

    @Override
    public TransactionTemplate getTransactionTemplate() {
        return new TransactionTemplate() {
            private final DefaultTransactionDefinition definition = new DefaultTransactionDefinition();

            @Override
            public TransactionTemplate propagation(TransactionPropagation propagation) {
                definition.setPropagationBehavior(switch (propagation) {
                    case REQUIRED -> DefaultTransactionDefinition.PROPAGATION_REQUIRED;
                    case SUPPORTS -> DefaultTransactionDefinition.PROPAGATION_SUPPORTS;
                    case MANDATORY -> DefaultTransactionDefinition.PROPAGATION_MANDATORY;
                    case REQUIRES_NEW -> DefaultTransactionDefinition.PROPAGATION_REQUIRES_NEW;
                    case NOT_SUPPORTED -> DefaultTransactionDefinition.PROPAGATION_NOT_SUPPORTED;
                    case NEVER -> DefaultTransactionDefinition.PROPAGATION_NEVER;
                    case NESTED -> DefaultTransactionDefinition.PROPAGATION_NESTED;
                });
                return this;
            }

            @Override
            public TransactionTemplate isolation(TransactionIsolation isolation) {
                definition.setIsolationLevel(isolation.jdbcLevel());
                return this;
            }

            @Override
            public TransactionTemplate readOnly(boolean readOnly) {
                definition.setReadOnly(readOnly);
                return this;
            }

            @Override
            public TransactionTemplate timeout(int timeoutSeconds) {
                definition.setTimeout(timeoutSeconds);
                return this;
            }

            @Override
            public TransactionHandle open(@Nullable TransactionContext existing, boolean suspendMode) {
                if (transactionManagers == null) {
                    throw new PersistenceException("""
                            Storm-managed transactions require the transaction managers of the application \
                            context. Construct SpringTransactionTemplateProvider with a \
                            PlatformTransactionManager supplier (the Spring Boot starter does this \
                            automatically), or manage transactions with Spring's @Transactional.""");
                }
                if (suspendMode) {
                    throw new PersistenceException(
                            "Suspend mode is not supported with Spring-managed transactions. Use the blocking "
                                    + "transaction API instead, or configure the template without the Spring "
                                    + "transaction template provider.");
                }
                SpringTransactionContext context;
                if (existing == null) {
                    context = new SpringTransactionContext(transactionManagers);
                } else if (existing instanceof SpringTransactionContext springContext) {
                    context = springContext;
                } else {
                    throw new PersistenceException("Transaction context must be of type SpringTransactionContext.");
                }
                context.begin(definition);
                return new TransactionHandle() {
                    @Override
                    public TransactionContext context() {
                        return context;
                    }

                    @Override
                    public TransactionStatus status() {
                        return new TransactionStatus() {
                            @Override
                            public void setRollbackOnly() {
                                context.setRollbackOnly();
                            }

                            @Override
                            public boolean isRollbackOnly() {
                                return context.isRollbackOnly();
                            }
                        };
                    }

                    @Override
                    public boolean joinedExistingTransaction() {
                        return context.joinedExistingTransaction();
                    }

                    @Override
                    public void deferCompletion(Consumer<Boolean> callback) {
                        // The Spring-managed transaction this handle joined owns the commit; the callback fires
                        // on its outcome, as a synchronization on the physical transaction.
                        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                            @Override
                            public void afterCompletion(int status) {
                                callback.accept(status == TransactionSynchronization.STATUS_COMMITTED);
                            }
                        });
                    }

                    @Override
                    public void complete(boolean rollback) {
                        context.complete(rollback);
                    }
                };
            }

            @Override
            public Optional<TransactionContext> currentContext() {
                if (!TransactionSynchronizationManager.isActualTransactionActive()) {
                    return empty();
                }
                Object existing = TransactionSynchronizationManager.getResource(CONTEXT_RESOURCE_KEY);
                if (existing instanceof TransactionContext context) {
                    return of(context);
                }
                var created = new SpringLinkedTransactionContext();
                TransactionSynchronizationManager.bindResource(CONTEXT_RESOURCE_KEY, created);
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCompletion(int status) {
                        TransactionSynchronizationManager.unbindResourceIfPossible(CONTEXT_RESOURCE_KEY);
                    }
                });
                return of(created);
            }

            @Override
            public ThreadLocal<TransactionContext> contextHolder() {
                return CONTEXT_HOLDER;
            }
        };
    }

    /**
     * Transaction context bound to Spring's {@code TransactionSynchronizationManager} resources.
     */
    private static final class SpringLinkedTransactionContext implements TransactionContext {
        private final Map<Class<? extends Entity<?>>, EntityCache<? extends Entity<?>, ?>> caches = new HashMap<>();
        private final Decorator<?> noopDecorator = resource -> resource;

        @Override
        public boolean isRepeatableRead() {
            // Spring returns null when no explicit isolation level is set (database default). Most databases default
            // to READ_COMMITTED, so return false to ensure fresh data is fetched on each read; users who want cached
            // instances should explicitly set REPEATABLE_READ or higher.
            Integer isolationLevel = TransactionSynchronizationManager.getCurrentTransactionIsolationLevel();
            if (isolationLevel == null || isolationLevel < 0) {
                return false;
            }
            return isolationLevel >= Connection.TRANSACTION_REPEATABLE_READ;
        }

        @Override
        public boolean isReadOnly() {
            // The statement runs in the Spring transaction that was already open, whose mode Spring records when it
            // begins or joins it.
            return TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        }

        @Override
        public EntityCache<? extends Entity<?>, ?> entityCache(Class<? extends Entity<?>> entityType,
                                                               CacheRetention retention) {
            // The context is bound once per physical Spring transaction, which gives correct cache scoping for
            // REQUIRED and REQUIRES_NEW. NESTED savepoint rollbacks are not observable through Spring's hooks, so no
            // cache splitting is attempted for savepoints.
            return EntityCaches.entityCache(caches, entityType, retention);
        }

        @Override
        public EntityCache<? extends Entity<?>, ?> getEntityCache(Class<? extends Entity<?>> entityType) {
            return EntityCaches.getEntityCache(caches, entityType);
        }

        @Override
        public EntityCache<? extends Entity<?>, ?> findEntityCache(Class<? extends Entity<?>> entityType) {
            return caches.get(entityType);
        }

        @Override
        public void clearAllEntityCaches() {
            EntityCaches.clearAll(caches);
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Decorator<T> getDecorator(Class<T> resourceType) {
            return (Decorator<T>) noopDecorator;
        }
    }
}
