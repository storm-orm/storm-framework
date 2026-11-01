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

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.jupiter.api.Test
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.template.model.City
import st.orm.test.StormTest

/**
 * A test that rolls back runs every connection over one, so transactions of separate coroutines cannot run at once,
 * while one coroutine keeps its transactions wherever it resumes.
 */
@StormTest(scripts = ["/data.sql"])
internal class StormTestConcurrentTransactionTest {

    @Test
    fun `a transaction nested after the coroutine resumed on another thread is allowed`(orm: ORMTemplate) {
        val cities = orm.entity(City::class)
        val counts = runBlocking {
            transaction {
                val outer = cities.count()
                // Suspends, so the transaction resumes on whichever thread its dispatcher picks.
                delay(10)
                val inner = transaction(propagation = REQUIRES_NEW) { cities.count() }
                outer to inner
            }
        }
        counts shouldBe (6L to 6L)
    }

    @Test
    fun `a transaction beginning while another coroutine holds one open fails`(orm: ORMTemplate) {
        val cities = orm.entity(City::class)
        val failure = runBlocking {
            val started = CompletableDeferred<Unit>()
            val release = CompletableDeferred<Unit>()
            val holder = launch {
                transaction {
                    cities.count()
                    started.complete(Unit)
                    release.await()
                }
            }
            started.await()
            val failure = runCatching { transaction { cities.count() } }.exceptionOrNull()
            release.complete(Unit)
            holder.join()
            failure
        }
        generateSequence(failure) { it.cause }.joinToString { it.message.orEmpty() } shouldContain
            "@StormTest(rollback = false)"
    }

    @Test
    fun `transactions of separate coroutines may take turns`(orm: ORMTemplate) {
        val cities = orm.entity(City::class)
        val counts = runBlocking {
            val first = launch { transaction { cities.count() } }
            first.join()
            yield()
            transaction { cities.count() }
        }
        counts shouldBe 6L
    }
}
