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
package st.orm.template.impl

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.stream.consumeAsFlow
import java.util.stream.Stream

/**
 * Returns a cold flow over the streams [open] returns.
 *
 * Nothing runs until the flow is collected. Each collection calls [open] for a stream of its own, emits its
 * elements as they are read and closes it when collection completes, fails or is cancelled, so the flow can be
 * collected more than once. A result stream is one open statement that holds its connection consume-only until it
 * is read to its end or closed; opening it on collection keeps a flow that is built but not yet collected, or never
 * collected, off the connection, and lets a caller hold several flows and collect them one after the other.
 */
internal fun <T> streamFlow(open: () -> Stream<T>): Flow<T> = flow {
    emitAll(open().consumeAsFlow())
}
