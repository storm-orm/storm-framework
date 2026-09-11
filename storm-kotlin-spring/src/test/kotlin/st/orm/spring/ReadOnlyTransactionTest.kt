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
package st.orm.spring

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import st.orm.ReadOnlyTransactionException
import st.orm.TransactionPropagation.NOT_SUPPORTED
import st.orm.TransactionPropagation.REQUIRED
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.repository.countAll
import st.orm.repository.removeAll
import st.orm.spring.model.Visit
import st.orm.template.ORMTemplate
import st.orm.template.setGlobalTransactionOptions
import st.orm.template.transactionBlocking

/**
 * A write Storm recognises is refused inside a read-only transaction before it reaches the database, under
 * Spring-managed transactions: Storm's own blocks, and a Spring transaction that was already open when Storm's
 * statement ran, such as a `@Transactional(readOnly = true)` method's.
 */
@ContextConfiguration(classes = [SpringIntegrationConfig::class])
@SpringBootTest
@Sql("/data.sql")
internal open class ReadOnlyTransactionTest(
    @Autowired val orm: ORMTemplate,
    @Autowired val transactionManager: PlatformTransactionManager,
) {
    @AfterEach
    fun resetDefaults() {
        setGlobalTransactionOptions(
            propagation = REQUIRED,
            isolation = null,
            timeoutSeconds = null,
            readOnly = false,
        )
    }

    private fun springTransaction(readOnly: Boolean): TransactionTemplate = TransactionTemplate(transactionManager).apply { isReadOnly = readOnly }

    @Test
    fun `a repository write inside a read-only transaction is refused before it reaches the database`() {
        assertThrows<ReadOnlyTransactionException> {
            transactionBlocking(readOnly = true) {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `a joined REQUIRED frame cannot lift the enclosing read-only mode`() {
        assertThrows<ReadOnlyTransactionException> {
            transactionBlocking(readOnly = true) {
                orm.countAll<Visit>()
                transactionBlocking(REQUIRED, readOnly = false) {
                    orm.removeAll<Visit>()
                }
            }
        }
        orm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `a REQUIRES_NEW frame writes on a transaction of its own inside a read-only one`() {
        transactionBlocking(readOnly = true) {
            orm.countAll<Visit>()
            transactionBlocking(REQUIRES_NEW, readOnly = false) {
                orm.removeAll<Visit>()
            }
            assertThrows<ReadOnlyTransactionException> {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 0
    }

    @Test
    fun `a NOT_SUPPORTED frame writes outside the read-only transaction`() {
        transactionBlocking(readOnly = true) {
            orm.countAll<Visit>()
            transactionBlocking(NOT_SUPPORTED) {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 0
    }

    @Test
    fun `a write inside a read-only Spring transaction is refused`() {
        assertThrows<ReadOnlyTransactionException> {
            springTransaction(readOnly = true).execute {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `a Storm block joining a read-only Spring transaction takes its mode`() {
        assertThrows<ReadOnlyTransactionException> {
            springTransaction(readOnly = true).execute {
                transactionBlocking(readOnly = false) {
                    orm.removeAll<Visit>()
                }
            }
        }
        orm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `a REQUIRES_NEW block inside a read-only Spring transaction writes on a transaction of its own`() {
        springTransaction(readOnly = true).execute {
            // The block's own Spring transaction starts on its first statement, which is the write itself.
            transactionBlocking(REQUIRES_NEW, readOnly = false) {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 0
    }

    @Test
    fun `a write inside a read-write Spring transaction goes through`() {
        springTransaction(readOnly = false).execute {
            orm.removeAll<Visit>()
        }
        orm.countAll<Visit>() shouldBe 0
    }
}
