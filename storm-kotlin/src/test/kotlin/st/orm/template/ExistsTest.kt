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
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.string.shouldStartWith
import org.junit.jupiter.api.Test
import st.orm.Metamodel
import st.orm.repository.exists
import st.orm.repository.existsBy
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/data.sql"])
internal class ExistsTest {

    private val lastName = Metamodel.of<Owner, String>(Owner::class.java, "lastName")

    private fun SqlCapture.shouldStopAtTheFirstRow() {
        statements().forEach { statement ->
            statement.statement() shouldStartWith "SELECT 1\n"
            statement.statement() shouldNotContain "COUNT"
        }
    }

    @Test
    fun `a query should test for a row without counting`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = orm.entity(Owner::class)
        capture.execute { owners.select().where(lastName eq "Davis").exists() } shouldBe true
        capture.execute { owners.select().where(lastName eq "Nobody").exists() } shouldBe false
        capture.execute { owners.select().semiJoin<Pet>().on<Owner>().exists() } shouldBe true
        capture.shouldStopAtTheFirstRow()
    }

    @Test
    fun `the repository existence checks should test for a row without counting`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = orm.entity(Owner::class)
        capture.execute { owners.exists() } shouldBe true
        capture.execute { owners.exists(lastName eq "Davis") } shouldBe true
        capture.execute { owners.existsBy(lastName, "Nobody") } shouldBe false
        capture.execute { owners.existsById(1) } shouldBe true
        capture.execute { orm.exists<Owner>(lastName eq "Davis") } shouldBe true
        capture.execute { orm.existsBy(lastName, "Nobody") } shouldBe false
        capture.shouldStopAtTheFirstRow()
    }
}
