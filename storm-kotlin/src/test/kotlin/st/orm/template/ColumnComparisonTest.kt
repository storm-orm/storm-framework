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

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import st.orm.Metamodel
import st.orm.Operator.NOT_EQUALS
import st.orm.PersistenceException
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.template.model.Visit
import st.orm.test.SqlCapture
import st.orm.test.StormTest

/**
 * A predicate compares the column at a path with the column at another path of the query, without binding a value.
 */
@StormTest(scripts = ["/data.sql"])
internal class ColumnComparisonTest {

    private val ownerFirstName = Metamodel.of<Owner, String>(Owner::class.java, "firstName")
    private val ownerLastName = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
    private val petName = Metamodel.of<Pet, String>(Pet::class.java, "name")
    private val petOwnerId = Metamodel.of<Pet, Int>(Pet::class.java, "owner.id")
    private val ownerId = Metamodel.of<Owner, Int>(Owner::class.java, "id")
    private val visitDescription = Metamodel.of<Visit, String>(Visit::class.java, "description")

    private fun SqlCapture.sql(): String = statements().single().statement().replace(Regex("\\s+"), " ")

    @Test
    fun `columns of one table compare without a parameter`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select().where(ownerFirstName neq ownerLastName).resultList
        }
        owners shouldHaveSize 10
        capture.sql() shouldContain "WHERE o.first_name <> o.last_name"
        capture.sql() shouldNotContain "?"
    }

    @Test
    fun `an operator takes a path in place of the value`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select().where(ownerFirstName, NOT_EQUALS, ownerLastName).resultList
        }
        owners shouldHaveSize 10
        capture.sql() shouldContain "WHERE o.first_name <> o.last_name"
    }

    @Test
    fun `columns compare in a join condition`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select().innerJoin<Pet>().on<Owner> { petName neq ownerFirstName }.resultList
        }
        owners shouldHaveSize 12
        capture.sql() shouldContain "INNER JOIN pet p ON p.owner_id = o.id AND (p.name <> o.first_name)"
    }

    @Test
    fun `columns compare in having`(orm: ORMTemplate, capture: SqlCapture) {
        val counts = capture.execute {
            orm.entity(Owner::class)
                .select(Long::class) { "COUNT(*)" }
                .groupBy(ownerFirstName, ownerLastName)
                .having(ownerFirstName neq ownerLastName)
                .resultList
        }
        counts shouldHaveSize 10
        capture.sql() shouldContain "HAVING o.first_name <> o.last_name"
    }

    @Test
    fun `a key beyond a foreign key of a joined table is its key column`(orm: ORMTemplate, capture: SqlCapture) {
        val visits = capture.execute {
            orm.entity(Owner::class).select()
                .crossJoin<Visit>()
                .innerJoin<Pet>().on<Visit> { petOwnerId eq ownerId }
                .resultList
        }
        visits shouldHaveSize 14
        capture.sql() shouldContain "INNER JOIN pet p ON v.pet_id = p.id AND (p.owner_id = o.id)"
        capture.sql() shouldNotContain "owner o1"
    }

    @Test
    fun `a condition on a semi-join names the query's table`(orm: ORMTemplate, capture: SqlCapture) {
        val visits = capture.execute {
            orm.entity(Visit::class).select().semiJoin<Pet>().on<Visit> { petName neq visitDescription }.resultList
        }
        visits shouldHaveSize 14
        capture.sql() shouldContain "WHERE p1.id = v.pet_id AND p1.name <> v.description"
        orm.entity(Visit::class).select().antiJoin<Pet>().on<Visit> { petName eq visitDescription }.resultList shouldHaveSize 14
    }

    @Test
    fun `a column outside the query is refused`(orm: ORMTemplate) {
        val thrown = shouldThrow<PersistenceException> {
            orm.entity(Owner::class).select().where(ownerFirstName eq petName).resultList
        }
        thrown.cause?.message shouldContain "Pet is not part of this query rooted at Owner"
    }
}
