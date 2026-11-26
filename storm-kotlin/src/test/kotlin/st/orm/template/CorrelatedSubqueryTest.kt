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

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import st.orm.Metamodel
import st.orm.ResolveScope.INNER
import st.orm.ResolveScope.OUTER
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.template.model.Visit
import st.orm.test.SqlCapture
import st.orm.test.StormTest
import java.time.LocalDate

/**
 * A subquery correlates with the enclosing query through `column(..., OUTER)`, which names the outer query's table
 * and never the subquery's own, and an existence test selects a constant.
 */
@StormTest(scripts = ["/data.sql"])
internal class CorrelatedSubqueryTest {

    private val petId = Metamodel.of<Pet, Int>(Pet::class.java, "id")
    private val petName = Metamodel.of<Pet, String>(Pet::class.java, "name")
    private val petOwner = Metamodel.of<Pet, Owner>(Pet::class.java, "owner")
    private val ownerId = Metamodel.of<Owner, Int>(Owner::class.java, "id")
    private val visitDescription = Metamodel.of<Visit, String>(Visit::class.java, "description")
    private val visitPet = Metamodel.of<Visit, Pet>(Visit::class.java, "pet")
    private val visitPetId = Metamodel.of<Visit, Int>(Visit::class.java, "pet.id")
    private val visitPetOwnerId = Metamodel.of<Visit, Int>(Visit::class.java, "pet.owner.id")

    private fun SqlCapture.sql(): String = statements().single().statement().replace(Regex("\\s+"), " ")

    @Test
    fun `OUTER joins the outer table it names when the select does not read it`(orm: ORMTemplate, capture: SqlCapture) {
        // The visits of pets that had a rabies shot, counted: the outer query reads no pet column of its own.
        val count = capture.execute {
            orm.entity(Visit::class).select(Long::class) { "COUNT(*)" }
                .whereExists {
                    subquery<Visit>().where(
                        (visitDescription eq "rabies shot") and { "${t(Templates.column(petId, INNER))} = ${t(Templates.column(petId, OUTER))}" },
                    )
                }
                .singleResult
        }
        count shouldBe 7
        capture.sql() shouldContain "INNER JOIN pet p ON v.pet_id = p.id"
    }

    internal data class OwnerLastVisit(val owner: Owner, val lastVisit: LocalDate?)

    @Test
    fun `a correlated subquery is a sort key and a selected value`(orm: ORMTemplate, capture: SqlCapture) {
        val ownerLastName = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
        val visitDate = Metamodel.of<Visit, LocalDate>(Visit::class.java, "visitDate")
        // The owners by the latest visit of their pets: the semi-join decides which owners are listed, the subquery
        // only orders them.
        val lastVisit = orm.subquery(Visit::class) { "MAX(${t(visitDate)})" }
            .where(visitPetOwnerId eq outer(ownerId))
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .semiJoin(Pet::class).on(Owner::class)
                .orderByDescending { "(${t(lastVisit)})" }
                .orderBy(ownerLastName, ownerId)
                .limit(4)
                .resultList
        }
        owners.map { it.lastName } shouldBe listOf("Coleman", "McTavish", "Davis", "Rodriquez")
        capture.sql() shouldContain "ORDER BY ( SELECT MAX(v.visit_date) FROM visit v INNER JOIN pet p ON v.pet_id = p.id " +
            "WHERE p.owner_id = o.id ) DESC, o.last_name, o.id"
        // A result type carries the value the rows are ordered by.
        orm.entity(Owner::class)
            .select(OwnerLastVisit::class) { "${t(Owner::class)}, (${t(lastVisit)})" }
            .orderByDescending { "(${t(lastVisit)})" }
            .orderBy(ownerLastName, ownerId)
            .limit(3)
            .resultList.map { it.owner.lastName to it.lastVisit } shouldBe listOf(
            "Coleman" to LocalDate.of(2023, 1, 13),
            "McTavish" to LocalDate.of(2023, 1, 13),
            "Davis" to LocalDate.of(2023, 1, 12),
        )
    }

    @Test
    fun `OUTER never resolves to the subquery's own alias`(orm: ORMTemplate, capture: SqlCapture) {
        // The visits of pets named Leo.
        val visits = capture.execute {
            orm.entity(Visit::class).select()
                .whereExists {
                    subquery<Pet>().where((petName eq "Leo") and { "${t(Templates.column(petId, INNER))} = ${t(Templates.column(petId, OUTER))}" })
                }
                .resultList
        }
        visits shouldHaveSize 2
        visits.map { it.pet.name }.toSet() shouldBe setOf("Leo")
        capture.sql() shouldContain "p1.id = p.id"
        capture.sql() shouldNotContain "p1.id = p1.id"
    }

    @Test
    fun `OUTER never resolves to the subquery's own alias in a counted query`(orm: ORMTemplate) {
        val count = orm.entity(Visit::class).select(Long::class) { "COUNT(*)" }
            .whereExists {
                subquery<Pet>().where((petName eq "Leo") and { "${t(Templates.column(petId, INNER))} = ${t(Templates.column(petId, OUTER))}" })
            }
            .singleResult
        count shouldBe 2
    }

    @Test
    fun `whereExists selects a constant`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .whereExists { subquery<Pet>().where { "${t(Templates.column(petOwner, INNER))} = ${t(Templates.column(ownerId, OUTER))}" } }
                .resultList
        }
        owners shouldHaveSize 10
        capture.sql() shouldContain "WHERE EXISTS ( SELECT 1 FROM pet p WHERE"
    }

    @Test
    fun `whereNotExists selects a constant`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .whereNotExists { subquery<Pet>().where { "${t(Templates.column(petOwner, INNER))} = ${t(Templates.column(ownerId, OUTER))}" } }
                .resultList
        }
        owners shouldHaveSize 0
        capture.sql() shouldContain "WHERE NOT EXISTS ( SELECT 1 FROM pet p WHERE"
    }

    @Test
    fun `exists in a where block selects a constant`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .whereBuilder { exists(subquery<Pet>().where { "${t(Templates.column(petOwner, INNER))} = ${t(Templates.column(ownerId, OUTER))}" }) }
                .resultList
        }
        owners shouldHaveSize 10
        capture.sql() shouldContain "WHERE EXISTS ( SELECT 1 FROM pet p WHERE"
    }

    @Test
    fun `a subquery with a select list of its own keeps it`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .whereExists { subquery<Pet> { "${t(Templates.column(petId, INNER))}" }.where { "${t(Templates.column(petOwner, INNER))} = ${t(Templates.column(ownerId, OUTER))}" } }
                .resultList
        }
        owners shouldHaveSize 10
        capture.sql() shouldContain "WHERE EXISTS ( SELECT p.id FROM pet p WHERE"
    }

    @Test
    fun `outer names the column of the enclosing query in a comparison`(orm: ORMTemplate, capture: SqlCapture) {
        // data.sql: pets 1 to 8 have visits.
        val pets = capture.execute {
            orm.entity(Pet::class).select()
                .whereExists { subquery<Visit>().where(visitPetId eq outer(petId)) }
                .resultList
        }
        pets shouldHaveSize 8
        capture.sql() shouldContain "WHERE EXISTS ( SELECT 1 FROM visit v WHERE v.pet_id = p.id )"
    }

    @Test
    fun `a comparison without outer names the subquery's own tables`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Pet::class).select()
                .whereExists { subquery<Visit>().where(visitPetId eq petId) }
                .resultList
        }
        pets shouldHaveSize 13
        capture.sql() shouldContain "WHERE v.pet_id = p1.id"
    }

    @Test
    fun `outer correlates a subquery that counts its rows`(orm: ORMTemplate, capture: SqlCapture) {
        // The owners with a pet that had at least two visits.
        val minimum = 2
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .whereExists {
                    subquery<Visit>()
                        .where(visitPetOwnerId eq outer(ownerId))
                        .groupBy(visitPet)
                        .having { "COUNT(*) >= ${t(minimum)}" }
                }
                .resultList
        }
        owners.map { it.firstName }.toSet() shouldBe setOf("Betty", "George", "Eduardo", "Peter", "Jean")
        capture.sql() shouldContain "FROM visit v INNER JOIN pet p ON v.pet_id = p.id WHERE p.owner_id = o.id GROUP BY v.pet_id HAVING COUNT(*) >= ?"
    }

    @Test
    fun `outer in a template renders the column of the enclosing query`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .whereExists { subquery<Pet>().where { "${t(Templates.column(petOwner, INNER))} = ${t(outer(ownerId))}" } }
                .resultList
        }
        owners shouldHaveSize 10
        capture.sql() shouldContain "WHERE EXISTS ( SELECT 1 FROM pet p WHERE p.owner_id = o.id )"
    }
}
