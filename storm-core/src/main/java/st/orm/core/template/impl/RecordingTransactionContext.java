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

import org.jspecify.annotations.Nullable;

/**
 * A transaction context that keeps a {@link TransactionRecord} of its transaction, which the slow statement log and
 * the advice log credit with each statement as it closes. Storm's own transaction contexts implement it; a context
 * that does not records nothing, and its transactions draw no transaction advice.
 *
 * @since 1.16
 */
public interface RecordingTransactionContext {

    /**
     * Returns the record of the transaction, or {@code null} when no transaction is active or transactions are not
     * recorded.
     *
     * <p>The context releases the record when the connection goes back to the pool, which is when the logs report
     * what they have to say about the transaction.</p>
     *
     * @return the transaction record, or {@code null}.
     */
    @Nullable TransactionRecord transactionRecord();
}
