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

import static java.util.Optional.empty;

import jakarta.annotation.Nonnull;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import st.orm.Entity;
import st.orm.PersistenceException;
import st.orm.core.spi.Orderable.AfterAny;

@AfterAny
public class DefaultTransactionTemplateProviderImpl implements TransactionTemplateProvider {

    // Key under which we store the TransactionContext in Spring's TransactionSynchronizationManager resources.
    private static final Object SPRING_CTX_RESOURCE_KEY =
            DefaultTransactionTemplateProviderImpl.class.getName() + ".SPRING_TX_CONTEXT";

    @Override
    public TransactionTemplate getTransactionTemplate() {
        return new TransactionTemplate() {
            @Override
            public TransactionTemplate propagation(String propagation) {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }

            @Override
            public TransactionTemplate isolation(int isolation) {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }

            @Override
            public TransactionTemplate readOnly(boolean readOnly) {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }

            @Override
            public TransactionTemplate timeout(int timeoutSeconds) {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }

            @Override
            public TransactionContext newContext(boolean suspendMode) throws PersistenceException {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }

            @Override
            public Optional<TransactionContext> currentContext() {
                final SpringReflection springReflection = SpringReflection.tryLoad();
                if (springReflection == null) {
                    return empty();
                }
                // Only expose a context when Spring says we are inside an actual transaction.
                if (!springReflection.isActualTransactionActive()) {
                    return empty();
                }
                Object existing = springReflection.getResource(SPRING_CTX_RESOURCE_KEY);
                if (existing instanceof TransactionContext) {
                    return Optional.of((TransactionContext) existing);
                }
                TransactionContext created = new SpringLinkedTransactionContext(springReflection);
                // Bind once per transaction and ensure cleanup at tx completion.
                try {
                    springReflection.bindResource(SPRING_CTX_RESOURCE_KEY, created);
                    springReflection.registerCleanupOnTxCompletion(SPRING_CTX_RESOURCE_KEY);
                    return Optional.of(created);
                } catch (Throwable bindFailure) {
                    // If already bound concurrently (unlikely; resources are thread-bound), return the bound one.
                    Object winner = springReflection.getResource(SPRING_CTX_RESOURCE_KEY);
                    if (winner instanceof TransactionContext) {
                        return Optional.of((TransactionContext) winner);
                    }
                    // If bind failed and nothing is bound, do not silently return an unbound context (it would not be tx-scoped).
                    throw new PersistenceException("Failed to bind Spring transaction context resource.", bindFailure);
                }
            }

            @Override
            public ThreadLocal<TransactionContext> contextHolder() {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }

            @Override
            public <R> R execute(@Nonnull TransactionCallback<R> action, @Nonnull TransactionContext context)
                    throws PersistenceException {
                throw new UnsupportedOperationException("Transaction template not supported.");
            }
        };
    }

    /**
     * TransactionContext bound to Spring's TransactionSynchronizationManager resources.
     */
    private static final class SpringLinkedTransactionContext implements TransactionContext {
        private final SpringReflection springReflection;
        private final Map<Class<? extends Entity<?>>, EntityCache<? extends Entity<?>, ?>> caches = new HashMap<>();
        private final Decorator<?> noopDecorator = resource -> resource;

        private SpringLinkedTransactionContext(SpringReflection springReflection) {
            this.springReflection = springReflection;
        }

        @Override
        public boolean isRepeatableRead() {
            // Check if isolation level is REPEATABLE_READ or higher.
            Integer isolationLevel = springReflection.getCurrentTransactionIsolationLevel();
            // Spring returns null when no explicit isolation level is set (database default).
            // Most databases default to READ_COMMITTED, so we return false to ensure fresh
            // data is fetched on each read. Users who want cached instances should explicitly
            // set REPEATABLE_READ or higher.
            if (isolationLevel == null || isolationLevel < 0) {
                return false;
            }
            return isolationLevel >= Connection.TRANSACTION_REPEATABLE_READ;
        }

        @Override
        public EntityCache<? extends Entity<?>, ?> entityCache(@Nonnull Class<? extends Entity<?>> entityType,
                                                                @Nonnull CacheRetention retention) {
            // Cache is used for dirty checking and/or identity preservation.
            // Whether cached instances are returned during reads is controlled by isRepeatableRead().
            //
            // We use computeIfAbsent so the "get or create" is a single operation.
            //
            // Why:
            // - This TransactionContext is bound once per physical Spring transaction via TransactionSynchronizationManager.
            //   That already gives correct cache scoping for REQUIRED and REQUIRES_NEW. We do not implement propagation
            //   rules here.
            // - This class intentionally does not try to clear or split caches for NESTED savepoints. Spring does not
            //   expose reliable hooks here for "rolled back to savepoint", only for transaction completion.
            // - computeIfAbsent avoids duplicate allocations and keeps the method simpler and harder to get wrong.
            return caches.computeIfAbsent(entityType, k -> new EntityCacheImpl<>(retention));
        }

        @Override
        public EntityCache<? extends Entity<?>, ?> getEntityCache(@Nonnull Class<? extends Entity<?>> entityType) {
            var cache = caches.get(entityType);
            if (cache == null) {
                throw new IllegalStateException("No entity cache exists for " + entityType.getName() + ".");
            }
            return cache;
        }

        @Override
        public EntityCache<? extends Entity<?>, ?> findEntityCache(@Nonnull Class<? extends Entity<?>> entityType) {
            return caches.get(entityType);
        }

        @Override
        public void clearAllEntityCaches() {
            for (EntityCache<? extends Entity<?>, ?> cache : caches.values()) {
                cache.clear();
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T> Decorator<T> getDecorator(@Nonnull Class<T> resourceType) {
            // noop decorator as requested
            return (Decorator<T>) noopDecorator;
        }
    }

    /**
     * Reflection wrapper for org.springframework.transaction.support.TransactionSynchronizationManager
     * to keep Spring as an optional dependency.
     */
    private static final class SpringReflection {
        private static final String TSM_FQCN =
                "org.springframework.transaction.support.TransactionSynchronizationManager";
        private static final String TS_FQCN =
                "org.springframework.transaction.support.TransactionSynchronization";

        private final Method isActualTransactionActive;
        private final Method getCurrentTransactionIsolationLevel;
        private final Method getResource;
        private final Method bindResource;
        private final Method registerSynchronization;
        private final Method unbindResourceIfPossible;
        private final Class<?> transactionSynchronizationType;

        private SpringReflection(
                Method isActualTransactionActive,
                Method getCurrentTransactionIsolationLevel,
                Method getResource,
                Method bindResource,
                Method registerSynchronization,
                Method unbindResourceIfPossible,
                Class<?> transactionSynchronizationType
        ) {
            this.isActualTransactionActive = isActualTransactionActive;
            this.getCurrentTransactionIsolationLevel = getCurrentTransactionIsolationLevel;
            this.getResource = getResource;
            this.bindResource = bindResource;
            this.registerSynchronization = registerSynchronization;
            this.unbindResourceIfPossible = unbindResourceIfPossible;
            this.transactionSynchronizationType = transactionSynchronizationType;
        }

        static SpringReflection tryLoad() {
            try {
                ClassLoader classLoader = DefaultTransactionTemplateProviderImpl.class.getClassLoader();
                Class<?> tsm = Class.forName(TSM_FQCN, false, classLoader);
                Method isActualTransactionActive = tsm.getMethod("isActualTransactionActive");
                Method getCurrentTransactionIsolationLevel = tsm.getMethod("getCurrentTransactionIsolationLevel");
                Method getResource = tsm.getMethod("getResource", Object.class);
                Method bindResource = tsm.getMethod("bindResource", Object.class, Object.class);
                // Cleanup hooks (may not exist in very old Spring).
                Method registerSynchronization = null;
                Method unbindResourceIfPossible = null;
                Class<?> ts = null;
                try {
                    ts = Class.forName(TS_FQCN, false, classLoader);
                    registerSynchronization = tsm.getMethod("registerSynchronization", ts);
                    unbindResourceIfPossible = tsm.getMethod("unbindResourceIfPossible", Object.class);
                } catch (Throwable ignored) {
                    // No cleanup support available via reflection; we'll run without it.
                }
                return new SpringReflection(
                        isActualTransactionActive,
                        getCurrentTransactionIsolationLevel,
                        getResource,
                        bindResource,
                        registerSynchronization,
                        unbindResourceIfPossible,
                        ts
                );
            } catch (Throwable ignored) {
                return null;
            }
        }

        boolean isActualTransactionActive() {
            try {
                return (boolean) isActualTransactionActive.invoke(null);
            } catch (Throwable t) {
                return false;
            }
        }

        Integer getCurrentTransactionIsolationLevel() {
            try {
                return (Integer) getCurrentTransactionIsolationLevel.invoke(null);
            } catch (Throwable t) {
                return null;
            }
        }

        Object getResource(Object key) {
            try {
                return getResource.invoke(null, key);
            } catch (Throwable t) {
                return null;
            }
        }

        void bindResource(Object key, Object value) {
            try {
                bindResource.invoke(null, key, value);
            } catch (RuntimeException re) {
                throw re;
            } catch (Throwable t) {
                throw new RuntimeException(t);
            }
        }

        void registerCleanupOnTxCompletion(Object key) {
            // If we could not reflect the synchronization APIs, we cannot auto-clean.
            if (registerSynchronization == null || unbindResourceIfPossible == null || transactionSynchronizationType == null) {
                return;
            }
            try {
                Object sync = Proxy.newProxyInstance(
                        transactionSynchronizationType.getClassLoader(),
                        new Class<?>[]{transactionSynchronizationType},
                        (proxy, method, args) -> {
                            String name = method.getName();
                            if ("afterCompletion".equals(name)) {
                                // afterCompletion(int status)
                                try {
                                    unbindResourceIfPossible.invoke(null, key);
                                } catch (Throwable ignored) {
                                    // best effort
                                }
                                return null;
                            }
                            // Default return values for other methods.
                            Class<?> rt = method.getReturnType();
                            if (rt == boolean.class) return false;
                            if (rt == int.class) return 0;
                            if (rt == void.class) return null;
                            return null;
                        }
                );
                registerSynchronization.invoke(null, sync);
            } catch (Throwable ignored) {
                // Best effort cleanup registration; if this fails, we at least will not break tx execution.
            }
        }
    }
}
