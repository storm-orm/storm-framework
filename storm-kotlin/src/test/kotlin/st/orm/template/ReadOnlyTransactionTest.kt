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
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.ReadOnlyTransactionException
import st.orm.TransactionPropagation.NOT_SUPPORTED
import st.orm.TransactionPropagation.REQUIRED
import st.orm.TransactionPropagation.REQUIRES_NEW
import st.orm.repository.countAll
import st.orm.repository.removeAll
import st.orm.template.model.City
import st.orm.template.model.Visit

/**
 * A write Storm recognises is refused inside a read-only transaction before it reaches the database, under Storm's
 * own JDBC transactions. H2 executes a write in a read-only transaction, so every refusal here is Storm's, and a
 * statement Storm does not recognise as a write shows where Storm's part ends and the database's begins.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class ReadOnlyTransactionTest(
    @Autowired val orm: ORMTemplate,
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
    fun `a suspending read-only transaction refuses a write as well`(): Unit = runBlocking {
        assertThrows<ReadOnlyTransactionException> {
            transaction(readOnly = true) {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `a template starting with a write keyword is refused, leading comments and whitespace included`() {
        assertThrows<ReadOnlyTransactionException> {
            transactionBlocking(readOnly = true) {
                orm.query("DELETE FROM visit WHERE id = 1").executeUpdate()
            }
        }
        assertThrows<ReadOnlyTransactionException> {
            transactionBlocking(readOnly = true) {
                orm.query("  -- a leading comment\n  UPDATE visit SET description = 'changed' WHERE id = 1").executeUpdate()
            }
        }
        orm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `a statement Storm does not recognise as a write reaches the database`() {
        // H2 does not enforce the mode, so the merge goes through; on a database that does, the database refuses it.
        transactionBlocking(readOnly = true) {
            orm.query("MERGE INTO city (id, name) KEY (id) VALUES (1, 'Renamed')").executeUpdate()
        }
        orm.entity(City::class).select().where(1).singleResult.name shouldBe "Renamed"
    }

    @Test
    fun `the refusal names the operation and the way out`() {
        val thrown = assertThrows<ReadOnlyTransactionException> {
            transactionBlocking(readOnly = true) {
                orm.removeAll<Visit>()
            }
        }
        thrown.message shouldBe "Cannot execute DELETE in a read-only transaction; a write needs a transaction of its own, opened with REQUIRES_NEW, or a read-write enclosing transaction."
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
    fun `a read-only frame joining a read-write transaction takes the enclosing mode`() {
        transactionBlocking {
            orm.countAll<Visit>()
            transactionBlocking(REQUIRED, readOnly = true) {
                orm.removeAll<Visit>()
            }
        }
        orm.countAll<Visit>() shouldBe 0
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
    fun `a read-only REQUIRES_NEW frame inside a read-write transaction is refused and the outer still writes`() {
        transactionBlocking {
            orm.countAll<Visit>()
            assertThrows<ReadOnlyTransactionException> {
                transactionBlocking(REQUIRES_NEW, readOnly = true) {
                    orm.removeAll<Visit>()
                }
            }
            orm.removeAll<Visit>()
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
    fun `a global read-only default applies and an explicit read-write transaction lifts it`() {
        setGlobalTransactionOptions(readOnly = true)
        assertThrows<ReadOnlyTransactionException> {
            transactionBlocking {
                orm.removeAll<Visit>()
            }
        }
        transactionBlocking(readOnly = false) {
            orm.removeAll<Visit>()
        }
        orm.countAll<Visit>() shouldBe 0
    }

    @Test
    fun `a write outside any transaction is not read-only`() {
        orm.removeAll<Visit>()
        orm.countAll<Visit>() shouldBe 0
    }
}
