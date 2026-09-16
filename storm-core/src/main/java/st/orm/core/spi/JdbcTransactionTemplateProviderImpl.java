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

import org.jspecify.annotations.Nullable;
import st.orm.PersistenceException;
import st.orm.TransactionIsolation;
import st.orm.TransactionPropagation;

/**
 * The default transaction template provider, managing transactions directly on JDBC connections via
 * {@link JdbcTransactionContext}.
 *
 * <p>Suspend mode is supported by this implementation: the JDBC context binds state to the context object
 * rather than the thread.</p>
 *
 * @since 1.13
 */
public final class JdbcTransactionTemplateProviderImpl implements TransactionTemplateProvider {

    private static final ThreadLocal<TransactionContext> CONTEXT_HOLDER = new ThreadLocal<>();

    @Override
    public TransactionTemplate getTransactionTemplate() {
        return new TransactionTemplate() {
            private TransactionPropagation propagation = TransactionPropagation.REQUIRED;
            private @Nullable TransactionIsolation isolation;
            private @Nullable Integer timeoutSeconds;
            private @Nullable Boolean readOnly;

            @Override
            public TransactionTemplate propagation(TransactionPropagation propagation) {
                this.propagation = propagation;
                return this;
            }

            @Override
            public TransactionTemplate isolation(TransactionIsolation isolation) {
                this.isolation = isolation;
                return this;
            }

            @Override
            public TransactionTemplate readOnly(boolean readOnly) {
                this.readOnly = readOnly;
                return this;
            }

            @Override
            public TransactionTemplate timeout(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
                return this;
            }

            @Override
            public TransactionHandle open(@Nullable TransactionContext existing, boolean suspendMode) {
                // Suspend mode is supported by this implementation; the JDBC context binds state to the
                // context object rather than the thread.
                JdbcTransactionContext context;
                if (existing == null) {
                    context = new JdbcTransactionContext();
                } else if (existing instanceof JdbcTransactionContext jdbcContext) {
                    context = jdbcContext;
                } else {
                    throw new PersistenceException("Transaction context must be of type JdbcTransactionContext.");
                }
                context.begin(propagation, isolation == null ? null : isolation.jdbcLevel(), timeoutSeconds, readOnly);
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
                    public void complete(boolean rollback) {
                        context.complete(rollback);
                    }
                };
            }

            @Override
            public ThreadLocal<TransactionContext> contextHolder() {
                return CONTEXT_HOLDER;
            }
        };
    }
}
