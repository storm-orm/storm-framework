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
package st.orm.template

import st.orm.TransactionIsolation
import st.orm.TransactionPropagation

/**
 * Internal class to hold transaction options.
 *
 * @param propagation The transaction propagation behavior.
 * @param isolation The transaction isolation level.
 * @param timeoutSeconds The transaction timeout in seconds; `0` means no time is left, and `null` sets none.
 * @param readOnly Whether the transaction is read-only; `null` requests no mode and leaves the connection the mode
 * it arrives with.
 * @since 1.6
 */
internal data class TransactionDefaults(
    val propagation: TransactionPropagation = TransactionPropagation.REQUIRED,
    val isolation: TransactionIsolation? = null,
    val timeoutSeconds: Int? = null,
    val readOnly: Boolean? = null,
)
