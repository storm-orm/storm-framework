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
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import st.orm.Metamodel
import st.orm.PersistenceException
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.template.model.Visit
import st.orm.test.SqlCapture
import st.orm.test.StormTest

/**
 * A path from a table the query joins explicitly joins the table it reaches on demand, as a path from the root does.
 */
@StormTest(scripts = ["/data.sql"])
internal class JoinedPathTest {

    private val petTypeName = Metamodel.of<Pet, String>(Pet::class.java, "type.name")
    private val ownerLastName = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
    private val visitPetTypeName = Metamodel.of<Visit, String>(Visit::class.java, "pet.type.name")

    data class LastNameAndType(val lastName: String, val type: String)

    private fun SqlCapture.sql(): String = statements().single().statement().replace(Regex("\\s+"), " ")

    @Test
    fun `a path from a joined table filters through the table it reaches`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select().innerJoin<Pet>().on<Owner>().where(petTypeName eq "dog").resultList
        }
        owners shouldHaveSize 4
        owners.map { it.id }.toSet() shouldBe setOf(3, 8, 10)
        capture.sql() shouldContain "INNER JOIN pet p ON p.owner_id = o.id INNER JOIN pet_type pt ON p.type_id = pt.id"
        capture.sql() shouldContain "WHERE pt.name = ?"
    }

    @Test
    fun `a path from a joined table selects through the table it reaches`(orm: ORMTemplate) {
        val rows = orm.entity(Owner::class)
            .select(LastNameAndType::class) { "${t(ownerLastName)}, ${t(petTypeName)}" }
            .innerJoin<Pet>().on<Owner>()
            .resultList
        rows shouldHaveSize 12
        rows.filter { it.lastName == "Davis" }.map { it.type }.sorted() shouldBe listOf("cat", "lizard")
    }

    @Test
    fun `a path from a joined table groups through the table it reaches`(orm: ORMTemplate) {
        val counts = orm.entity(Owner::class)
            .select(Long::class) { "COUNT(*)" }
            .innerJoin<Pet>().on<Owner>()
            .groupBy(petTypeName)
            .resultList
        counts shouldHaveSize 6
        counts.sum() shouldBe 12
    }

    @Test
    fun `a path from a joined table orders through the table it reaches`(orm: ORMTemplate) {
        val rows = orm.entity(Owner::class)
            .select(LastNameAndType::class) { "${t(ownerLastName)}, ${t(petTypeName)}" }
            .innerJoin<Pet>().on<Owner>()
            .orderBy(petTypeName)
            .resultList
        rows.map { it.type } shouldBe rows.map { it.type }.sorted()
    }

    @Test
    fun `a path from a left joined table joins the table it reaches with a left join`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select().leftJoin<Pet>().on<Owner>().where(petTypeName eq "dog").resultList
        }
        owners shouldHaveSize 4
        capture.sql() shouldContain "LEFT JOIN pet p ON p.owner_id = o.id LEFT JOIN pet_type pt ON p.type_id = pt.id"
    }

    @Test
    fun `a selected joined entity is hydrated with its eager foreign keys`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Owner::class).select(Pet::class).innerJoin<Pet>().on<Owner>().resultList
        }
        pets shouldHaveSize 12
        pets.map { it.type.name }.toSet() shouldBe setOf("cat", "dog", "lizard", "snake", "bird", "hamster")
        pets.map { it.owner?.lastName }.toSet() shouldBe pets.map { it.owner?.lastName }.toSet().filterNotNull().toSet()
        capture.sql() shouldContain "INNER JOIN pet p ON p.owner_id = o.id INNER JOIN pet_type pt ON p.type_id = pt.id"
        capture.sql() shouldNotContain "o1"
    }

    @Test
    fun `a path from a joined table reaches two levels deep`(orm: ORMTemplate, capture: SqlCapture) {
        // The visits of the pets of each owner, filtered by the pet type the visit's own pet path reaches.
        val rows = capture.execute {
            orm.entity(Owner::class)
                .select(Long::class) { "COUNT(*)" }
                .innerJoin<Pet>().on<Owner>()
                .innerJoin<Visit>().on<Pet>()
                .where(visitPetTypeName eq "cat")
                .singleResult
        }
        rows shouldBe 6
        capture.sql() shouldContain "INNER JOIN pet p1 ON v.pet_id = p1.id INNER JOIN pet_type pt ON p1.type_id = pt.id"
    }

    @Test
    fun `a path from a table joined twice names the joins`(orm: ORMTemplate) {
        val thrown = shouldThrow<PersistenceException> {
            orm.entity(Owner::class).select()
                .innerJoin<Pet>().on<Owner>()
                .innerJoin<Pet>().on<Owner>()
                .where(petTypeName eq "dog")
                .resultList
        }
        thrown.cause?.message shouldContain "Multiple joins found for Pet: p, p1"
    }
}
