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

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import st.orm.Window

/**
 * Reads the windows as one flow of their rows.
 *
 * Each window is fetched by a statement that has returned and closed before its rows are emitted, so the connection
 * is free at every row: the collector may query, fetch references and write, inside a transaction or not, where a
 * collector of [QueryBuilder.resultFlow] holds the connection consume-only until the last row. Memory stays bounded
 * by the window size, and the rows arrive in the windows' key order. Each window is its own statement, so under READ
 * COMMITTED a later window sees rows committed since the one before it.
 *
 * ```kotlin
 * users.select().where(User_.city eq city).windows(1000).rows().collect { user ->
 *     users.update(user.copy(email = user.email.lowercase()))
 * }
 * ```
 *
 * A write per window rather than per row is `windows(size).collect { window -> users.update(window.content()) }`.
 *
 * @return a flow of the windows' rows.
 * @since 1.14
 */
public fun <R> Flow<Window<R>>.rows(): Flow<R> = flow {
    collect { window -> window.content().forEach { emit(it) } }
}
