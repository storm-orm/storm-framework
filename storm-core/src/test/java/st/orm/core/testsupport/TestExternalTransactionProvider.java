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
package st.orm.core.testsupport;

import java.util.Optional;
import java.util.function.Supplier;
import st.orm.Transaction;
import st.orm.core.spi.ExternalTransactionProvider;

/**
 * Test-only external transaction provider that reports a transaction while a test runs code through
 * {@link #during(Transaction, Supplier)}, as an external transaction manager reports the transaction it has active
 * on the calling thread. It reports none otherwise, so it leaves every other test alone.
 */
public class TestExternalTransactionProvider implements ExternalTransactionProvider {

    private static final ThreadLocal<Transaction> ACTIVE = new ThreadLocal<>();

    /**
     * Runs the given code with the given transaction reported as the external transaction of the calling thread.
     */
    public static <T> T during(Transaction transaction, Supplier<T> code) {
        ACTIVE.set(transaction);
        try {
            return code.get();
        } finally {
            ACTIVE.remove();
        }
    }

    @Override
    public Optional<Transaction> currentTransaction() {
        return Optional.ofNullable(ACTIVE.get());
    }
}
