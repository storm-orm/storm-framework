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
package st.orm.ktor

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.Hook
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext
import kotlinx.coroutines.CoroutineDispatcher
import st.orm.TransactionIsolation
import st.orm.TransactionPropagation
import st.orm.template.TransactionDispatchers
import st.orm.template.transaction

/**
 * Runs every route declared inside [build] within a Storm `transaction { }`, removing the per-handler
 * boilerplate: the transaction opens before the handler runs, commits when the handler completes, and rolls
 * back when the handler throws.
 *
 * ```kotlin
 * routing {
 *     // Writes: each call runs in its own read-write transaction.
 *     transactional {
 *         post("/owners") { ... }
 *         put("/owners/{id}") { ... }
 *     }
 *
 *     // Reads that need one consistent snapshot across queries.
 *     transactional(readOnly = true) {
 *         get("/reports/summary") { ... }
 *     }
 *
 *     // Simple reads outside the block keep running without a transaction.
 *     get("/owners/{id}") { ... }
 * }
 * ```
 *
 * The parameters mirror [st.orm.template.transaction] and apply uniformly to every route in the block,
 * whatever the HTTP method. The transaction binds to the first ORM template that executes inside the handler,
 * so no database needs to be named and named databases work unchanged. Nested [transactional] blocks compose
 * exactly like nested `transaction { }` calls: with the default propagation the inner block joins the outer
 * transaction; use [TransactionPropagation.REQUIRES_NEW] and friends to change that.
 *
 * The handler, including `call.respond`, runs inside the transaction; the commit happens after the handler
 * completes, so a commit failure cannot change a response that has already been sent. When that distinction
 * matters, call `transaction { }` in the handler and respond after it returns. An exception thrown by the
 * handler rolls the transaction back before StatusPages renders the error response.
 *
 * @param dispatcher the coroutine dispatcher the transaction runs on; defaults to
 *                   [TransactionDispatchers.Default], matching `transaction { }`.
 * @param propagation the transaction propagation; defaults to the provider's default (`REQUIRED`).
 * @param isolation the transaction isolation level; defaults to the provider's default.
 * @param timeoutSeconds the transaction timeout in seconds; defaults to the provider's default.
 * @param readOnly whether the transaction is read-only; if `null`, no mode is requested and the connection keeps
 *                 the mode it arrives with.
 * @param build the routes that run transactionally.
 * @return the route subtree wrapped by the transaction.
 * @since 1.13
 */
public fun Route.transactional(
    dispatcher: CoroutineDispatcher = TransactionDispatchers.Default,
    propagation: TransactionPropagation? = null,
    isolation: TransactionIsolation? = null,
    timeoutSeconds: Int? = null,
    readOnly: Boolean? = null,
    build: Route.() -> Unit,
): Route {
    val transactionalRoute = createChild(TransactionalRouteSelector())
    transactionalRoute.install(StormTransaction) {
        this.dispatcher = dispatcher
        this.propagation = propagation
        this.isolation = isolation
        this.timeoutSeconds = timeoutSeconds
        this.readOnly = readOnly
    }
    transactionalRoute.build()
    return transactionalRoute
}

/**
 * Configuration of the route-scoped transaction plugin; mirrors the parameters of
 * [st.orm.template.transaction].
 */
internal class StormTransactionConfig {
    var dispatcher: CoroutineDispatcher = TransactionDispatchers.Default
    var propagation: TransactionPropagation? = null
    var isolation: TransactionIsolation? = null
    var timeoutSeconds: Int? = null
    var readOnly: Boolean? = null
}

/**
 * Route-scoped plugin that wraps the execution of the route's handlers in a Storm `transaction { }`.
 */
internal val StormTransaction = createRouteScopedPlugin("StormTransaction", ::StormTransactionConfig) {
    val dispatcher = pluginConfig.dispatcher
    val propagation = pluginConfig.propagation
    val isolation = pluginConfig.isolation
    val timeoutSeconds = pluginConfig.timeoutSeconds
    val readOnly = pluginConfig.readOnly
    on(TransactionHook) { proceedWithCall ->
        transaction(dispatcher, propagation, isolation, timeoutSeconds, readOnly) {
            proceedWithCall()
        }
    }
}

/**
 * Hook that wraps the remainder of the call pipeline, so the transaction spans the route handler and commits
 * or rolls back after it finishes.
 */
private object TransactionHook : Hook<suspend (suspend () -> Unit) -> Unit> {
    override fun install(pipeline: ApplicationCallPipeline, handler: suspend (suspend () -> Unit) -> Unit) {
        pipeline.intercept(ApplicationCallPipeline.Plugins) {
            handler { proceed() }
        }
    }
}

/**
 * Transparent selector for the child route created by [transactional]: invisible to path resolution. A fresh
 * instance per [transactional] call keeps sibling blocks distinct, so each block gets its own plugin
 * installation with its own options.
 */
private class TransactionalRouteSelector : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int): RouteSelectorEvaluation = RouteSelectorEvaluation.Transparent

    override fun toString(): String = "(transactional)"
}
