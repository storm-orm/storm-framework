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
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import st.orm.Metamodel
import st.orm.Scrollable
import st.orm.SqlFunction
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.test.SqlCapture
import st.orm.test.StormTest

@StormTest(scripts = ["/data.sql"])
internal class SqlFunctionTest {

    private val upper = SqlFunction.of<String>("UPPER")
    private val lower = SqlFunction.of<String>("LOWER")
    private val lastName = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
    private val firstName = Metamodel.of<Owner, String>(Owner::class.java, "firstName")
    private val id = Metamodel.of<Owner, Int>(Owner::class.java, "id")

    private fun SqlCapture.sql(): String = statements().single().statement()

    @Test
    fun `a comparison should apply the function to the column and to the value`(orm: ORMTemplate, capture: SqlCapture) {
        // UPPER on the column alone would compare 'DAVIS' with 'davis' and find nobody.
        val owners = capture.execute {
            orm.entity(Owner::class).select().where(upper(lastName) eq "davis").orderBy(id).resultList
        }
        owners.map { it.firstName } shouldBe listOf("Betty", "Harold")
        capture.sql() shouldContain "UPPER(o.last_name) = UPPER(?)"
    }

    @Test
    fun `search and the text operators should match through the function on both sides`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select().where(search("davi", upper(lastName))).orderBy(id).resultList
        }
        owners.map { it.firstName } shouldBe listOf("Betty", "Harold")
        capture.sql() shouldContain "LOWER(UPPER(o.last_name)) LIKE LOWER(UPPER(?))"
    }

    @Test
    fun `calls should nest with the innermost applied first`(orm: ORMTemplate, capture: SqlCapture) {
        capture.execute {
            orm.entity(Owner::class).select().where(lower(upper(lastName)) eq "DAVIS").resultList
        }.size shouldBe 2
        capture.sql() shouldContain "LOWER(UPPER(o.last_name)) = LOWER(UPPER(?))"
    }

    @Test
    fun `a call on a path through a relation should join its table`(orm: ORMTemplate, capture: SqlCapture) {
        val ownerLastName = Metamodel.of<Pet, String>(Pet::class.java, "owner.lastName")
        capture.execute {
            orm.entity(Pet::class).select().where(upper(ownerLastName) eq "davis").resultList
        }.map { it.name }.sorted() shouldBe listOf("Iggy", "Leo")
        capture.sql() shouldContain "UPPER(o.last_name) = UPPER(?)"
    }

    @Test
    fun `orderFirst, orderBy and a template should render the call`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .where { "${upper(firstName)} LIKE 'J%'" }
                .orderFirst(upper(lastName) eq "black")
                .orderBy(upper(firstName))
                .resultList
        }
        owners.map { it.firstName } shouldBe listOf("Jeff", "Jean")
        val sql = capture.sql()
        sql shouldContain "UPPER(o.first_name) LIKE 'J%'"
        sql shouldContain "CASE WHEN UPPER(o.last_name) = UPPER(?) THEN 0 ELSE 1 END"
        sql shouldContain "UPPER(o.first_name)"
    }

    @Test
    fun `groupBy should group by the call`(orm: ORMTemplate, capture: SqlCapture) {
        val counts = capture.execute {
            orm.entity(Owner::class)
                .select(Long::class) { "COUNT(*)" }
                .groupBy(upper(lastName))
                .resultList
        }
        counts.sum() shouldBe 10L
        counts.size shouldBe 9
        capture.sql() shouldContain "GROUP BY UPPER(o.last_name)"
    }

    @Test
    fun `a call should never equal the path it wraps`() {
        upper(lastName) shouldNotBe lastName
        lastName shouldNotBe upper(lastName)
        Metamodel.key(lastName) shouldNotBe upper(lastName)
        upper(lastName) shouldBe upper(lastName)
        upper(lastName) shouldNotBe lower(lastName)
    }

    @Test
    fun `a scroll should refuse a call as key or sort field`() {
        assertThrows<IllegalArgumentException> {
            Scrollable.of(Metamodel.key(upper(lastName)), 5)
        }.message!! shouldContain "SqlFunction"
        assertThrows<IllegalArgumentException> {
            Scrollable.of(Metamodel.key(id), 5).sortBy(upper(lastName))
        }.message!! shouldContain "SqlFunction"
    }

    @Test
    fun `a function name should be an identifier`() {
        SqlFunction.of<String>("public.fold_accents").name shouldBe "public.fold_accents"
        assertThrows<IllegalArgumentException> { SqlFunction.of<String>("fold(x); DROP TABLE owner; --") }
        assertThrows<IllegalArgumentException> { SqlFunction.of<String>("") }
    }

    @Test
    fun `a call on a path of several columns should be refused`(orm: ORMTemplate) {
        val address = Metamodel.of<Owner, Any>(Owner::class.java, "address")
        assertThrows<st.orm.PersistenceException> {
            orm.entity(Owner::class).select().where(SqlFunction.of<Any>("UPPER")(address).isNull()).resultList
        }
    }
}
