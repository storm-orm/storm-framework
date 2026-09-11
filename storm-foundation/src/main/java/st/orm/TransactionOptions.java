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
package st.orm;

import org.jspecify.annotations.Nullable;

/**
 * Options for a transactional block. A {@code null} component means the option is inherited from the
 * surrounding defaults: the thread-scoped options, then the global options, then the baseline
 * ({@link TransactionPropagation#REQUIRED}, provider-default isolation and timeout, read-write).
 *
 * @param propagation how the block relates to an already active transaction.
 * @param isolation the isolation level.
 * @param timeoutSeconds the transaction timeout in seconds.
 * @param readOnly whether the transaction is read-only.
 * @since 1.13
 */
public record TransactionOptions(
        @Nullable TransactionPropagation propagation,
        @Nullable TransactionIsolation isolation,
        @Nullable Integer timeoutSeconds,
        @Nullable Boolean readOnly
) {

    private static final TransactionOptions DEFAULTS = new TransactionOptions(null, null, null, null);

    /**
     * Options with every field inherited from the surrounding defaults.
     */
    public static TransactionOptions defaults() {
        return DEFAULTS;
    }

    public TransactionOptions withPropagation(TransactionPropagation propagation) {
        return new TransactionOptions(propagation, isolation, timeoutSeconds, readOnly);
    }

    public TransactionOptions withIsolation(TransactionIsolation isolation) {
        return new TransactionOptions(propagation, isolation, timeoutSeconds, readOnly);
    }

    public TransactionOptions withTimeoutSeconds(int timeoutSeconds) {
        return new TransactionOptions(propagation, isolation, timeoutSeconds, readOnly);
    }

    public TransactionOptions withReadOnly(boolean readOnly) {
        return new TransactionOptions(propagation, isolation, timeoutSeconds, readOnly);
    }
}
