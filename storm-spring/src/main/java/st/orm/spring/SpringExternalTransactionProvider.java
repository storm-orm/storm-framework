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

import static java.util.Objects.requireNonNull;
import static java.util.Optional.empty;
import static java.util.Optional.of;
import static org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.springframework.transaction.NoTransactionException;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.ResourceHolderSupport;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import st.orm.PersistenceException;
import st.orm.Transaction;
import st.orm.core.spi.ExternalTransactionProvider;

/**
 * Detects a Spring-managed transaction on the current thread, so a Storm transactional block that never binds
 * to a template still settles against it: completion callbacks the block registered fire on the Spring
 * transaction's real outcome, and a rollback-only demand marks the Spring transaction.
 *
 * <p>The provider is stateless and reads Spring's thread-bound transaction state directly, which is what makes
 * {@code ServiceLoader} discovery safe: it carries no configuration, works identically for every application
 * context in the JVM, and is never consulted for query execution or transaction control. Per-context
 * configuration, such as the transaction managers a template runs through, stays on the composed
 * {@link SpringTransactionTemplateProvider}.</p>
 *
 * <p>Callbacks are registered as a {@link TransactionSynchronization} on the physical transaction, which is
 * what makes {@code onCommit} mean the same thing here as inside a Storm-managed transaction: it runs after
 * Spring commits, and not at all when Spring rolls back.</p>
 *
 * @since 1.13
 */
public class SpringExternalTransactionProvider implements ExternalTransactionProvider {

    @Override
    public Optional<Transaction> currentTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            return empty();
        }
        return of(new SpringTransaction());
    }

    /**
     * Handle to the Spring transaction active on the current thread.
     *
     * <p>Synchronizations are registered per callback rather than once per handle, so a handle that is resolved
     * and never used adds nothing to the transaction.</p>
     */
    private static final class SpringTransaction implements Transaction {

        @Override
        public boolean isRollbackOnly() {
            var status = aspectStatus();
            if (status != null) {
                return status.isRollbackOnly();
            }
            return boundResources().anyMatch(ResourceHolderSupport::isRollbackOnly);
        }

        @Override
        public void setRollbackOnly() {
            var status = aspectStatus();
            if (status != null) {
                status.setRollbackOnly();
                return;
            }
            // A transaction driven by Spring's TransactionTemplate binds no aspect status, so the mark goes on
            // the resource holders the transaction manager reads when it decides the outcome, which is the same
            // place its own doSetRollbackOnly writes.
            var marked = boundResources().peek(ResourceHolderSupport::setRollbackOnly).count();
            if (marked == 0) {
                throw new PersistenceException("""
                        A Spring transaction is active, but it holds no resource to mark rollback-only and no \
                        transaction status is bound to this thread. This happens when transaction \
                        synchronization was activated without Spring driving a resource transaction. Run the \
                        work under @Transactional or Spring's TransactionTemplate, or open the transaction with \
                        Storm's transaction API.""");
            }
        }

        @Override
        public void cancel() {
            // Spring holds the statements of a transaction it manages, so Storm has nothing to stop here.
            throw new UnsupportedOperationException(
                    "A transaction Spring manages cannot be cancelled through Storm. Cancel the work through Spring, "
                            + "or open the transaction with Storm's transaction API.");
        }

        @Override
        public void onCommit(Runnable callback) {
            requireNonNull(callback, "callback");
            register(committed -> {
                if (committed) {
                    callback.run();
                }
            });
        }

        @Override
        public void onRollback(Runnable callback) {
            requireNonNull(callback, "callback");
            register(committed -> {
                if (!committed) {
                    callback.run();
                }
            });
        }

        @Override
        public void onCompletion(Consumer<Boolean> callback) {
            requireNonNull(callback, "callback");
            register(callback);
        }

        /**
         * Registers a completion callback with the physical Spring transaction. Spring fires synchronizations
         * in registration order and reports the outcome through {@code afterCompletion}, which matches the
         * ordering and outcome contract of the callbacks a Storm block collects.
         */
        private void register(Consumer<Boolean> callback) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCompletion(int status) {
                    callback.accept(status == STATUS_COMMITTED);
                }
            });
        }

        /**
         * Returns the transaction status Spring's interceptor bound to this thread, or {@code null} when the
         * transaction was not driven through the interceptor, as with Spring's {@code TransactionTemplate}.
         */
        private static org.springframework.transaction.@Nullable TransactionStatus aspectStatus() {
            try {
                return TransactionAspectSupport.currentTransactionStatus();
            } catch (NoTransactionException e) {
                return null;
            }
        }

        /**
         * Returns the resource holders bound to the active transaction, which carry its rollback-only state
         * when no aspect status is available.
         */
        private static Stream<ResourceHolderSupport> boundResources() {
            return TransactionSynchronizationManager.getResourceMap().values().stream()
                    .filter(ResourceHolderSupport.class::isInstance)
                    .map(ResourceHolderSupport.class::cast);
        }
    }
}
