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
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import st.orm.AbstractMetamodel
import st.orm.Metamodel
import st.orm.Ref
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.test.SqlCapture
import st.orm.test.StormTest

/**
 * The root metamodel names the row itself: the infix operators on it compare the primary key, as they compare the
 * foreign key on a foreign key path.
 */
@StormTest(scripts = ["/data.sql"])
internal class RootMetamodelTest {

    private val pet: Metamodel<Pet, Pet> = Metamodel.root(Pet::class.java)
    private val petOwner = Metamodel.of<Pet, Owner>(Pet::class.java, "owner")

    /** A root metamodel declared as the generated metamodel companion declares it. */
    private object PetRoot : AbstractMetamodel<Pet, Pet, Pet>(Pet::class.java) {
        override fun getValue(record: Pet): Pet = record

        override fun isIdentical(a: Pet, b: Pet): Boolean = a === b

        override fun isSame(a: Pet, b: Pet): Boolean = Metamodel.root(Pet::class.java).isSame(a, b)
    }

    private fun SqlCapture.sql(): String = statements().last().statement().replace(Regex("\\s+"), " ")

    private fun List<Pet>.ids(): List<Int> = map { it.id }.sorted()

    private fun pets(orm: ORMTemplate, vararg ids: Int): List<Pet> = orm.entity(Pet::class).findAllById(ids.toList())

    @Test
    fun `eq and neq compare the primary key`(orm: ORMTemplate, capture: SqlCapture) {
        val rosy = pets(orm, 3).single()
        capture.execute { orm.entity(Pet::class).findAll(pet eq rosy) }.ids() shouldBe listOf(3)
        capture.sql() shouldContain "WHERE p.id = ?"
        val others = capture.execute { orm.entity(Pet::class).findAll(pet neq rosy) }
        others shouldHaveSize 12
        others.ids() shouldNotContain 3
        capture.sql() shouldContain "WHERE p.id <> ?"
    }

    @Test
    fun `inList and notInList compare the primary keys`(orm: ORMTemplate, capture: SqlCapture) {
        val chosen = pets(orm, 1, 2, 5)
        capture.execute { orm.entity(Pet::class).findAll(pet inList chosen) }.ids() shouldBe listOf(1, 2, 5)
        capture.sql() shouldContain "WHERE p.id IN (?, ?, ?)"
        val rest = capture.execute { orm.entity(Pet::class).findAll(pet notInList chosen) }
        rest shouldHaveSize 10
        capture.sql() shouldContain "WHERE p.id NOT IN (?, ?, ?)"
    }

    @Test
    fun `refs compare the primary key`(orm: ORMTemplate) {
        val refs = listOf(Ref.of(Pet::class.java, 2), Ref.of(Pet::class.java, 7))
        orm.entity(Pet::class).findAll(pet inRefs refs).ids() shouldBe listOf(2, 7)
        orm.entity(Pet::class).findAll(pet notInRefs refs) shouldHaveSize 11
        orm.entity(Pet::class).findAll(pet eq Ref.of(Pet::class.java, 9)).ids() shouldBe listOf(9)
        orm.entity(Pet::class).findAll(pet neq Ref.of(Pet::class.java, 9)) shouldHaveSize 12
    }

    @Test
    fun `the row combines with a foreign key path`(orm: ORMTemplate, capture: SqlCapture) {
        val owners = orm.entity(Owner::class).findAllById(listOf(3, 6))
        val excluded = pets(orm, 3, 8)
        val result = capture.execute { orm.entity(Pet::class).findAll((petOwner inList owners) and (pet notInList excluded)) }
        result.ids() shouldBe listOf(4, 7)
        capture.sql() shouldContain "p.owner_id IN (?, ?)"
        capture.sql() shouldContain "p.id NOT IN (?, ?)"
    }

    @Test
    fun `the row combines with the record-level predicates in a where block`(orm: ORMTemplate) {
        val result = orm.entity(Pet::class).select()
            .whereBuilder { whereId(listOf(1, 2, 3)) and (pet neq pets(orm, 2).single()) }
            .resultList
        result.ids() shouldBe listOf(1, 3)
    }

    @Test
    fun `the row compares with the row of the enclosing query`(orm: ORMTemplate, capture: SqlCapture) {
        val result = capture.execute {
            orm.entity(Pet::class).select()
                .whereExists { subquery<Pet>().where((petOwner eq outer(petOwner)) and (pet neq outer(pet))) }
                .resultList
        }
        result.ids() shouldBe listOf(3, 4, 7, 8)
        capture.sql() shouldContain ".id <> p.id"
    }

    @Test
    fun `a root metamodel subclass resolves as the root`(orm: ORMTemplate, capture: SqlCapture) {
        PetRoot shouldBe pet
        PetRoot.hashCode() shouldBe pet.hashCode()
        val chosen = pets(orm, 1, 2)
        PetRoot.isSame(chosen[0], chosen[0].copy(name = "Leonard")) shouldBe true
        PetRoot.isSame(chosen[0], chosen[1]) shouldBe false
        capture.execute { orm.entity(Pet::class).findAll(PetRoot notInList chosen) } shouldHaveSize 11
        capture.sql() shouldContain "WHERE p.id NOT IN (?, ?)"
    }
}
