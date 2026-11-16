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
 * A join, semi-join or anti-join takes a predicate stating its relation: a term equating a key of the joined table
 * with a key of another table is the key the join resolves by, and the remaining terms are its condition.
 */
@StormTest(scripts = ["/data.sql"])
internal class JoinPredicateTest {

    private val petId = Metamodel.of<Pet, Int>(Pet::class.java, "id")
    private val petName = Metamodel.of<Pet, String>(Pet::class.java, "name")
    private val petOwnerId = Metamodel.of<Pet, Int>(Pet::class.java, "owner.id")
    private val ownerId = Metamodel.of<Owner, Int>(Owner::class.java, "id")
    private val ownerLastName = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
    private val ownerFirstName = Metamodel.of<Owner, String>(Owner::class.java, "firstName")
    private val visitId = Metamodel.of<Visit, Int>(Visit::class.java, "id")
    private val visitPetId = Metamodel.of<Visit, Int>(Visit::class.java, "pet.id")
    private val visitDescription = Metamodel.of<Visit, String>(Visit::class.java, "description")

    private fun SqlCapture.sql(): String = statements().single().statement().replace(Regex("\\s+"), " ")

    @Test
    fun `a join resolves by the key the predicate equates`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Pet::class).select().innerJoin<Visit>().on(visitPetId eq petId).resultList
        }
        pets shouldHaveSize 14
        capture.sql() shouldContain "INNER JOIN visit v ON v.pet_id = p.id"
        capture.sql() shouldNotContain "?"
    }

    @Test
    fun `the terms beside the key are the join's condition`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Pet::class).select()
                .innerJoin<Visit>().on((visitDescription eq "rabies shot") and (visitPetId eq petId))
                .resultList
        }
        pets shouldHaveSize 4
        capture.sql() shouldContain "INNER JOIN visit v ON v.pet_id = p.id AND (v.description = ?)"
    }

    @Test
    fun `a key of the table joined on resolves the join as well`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Pet::class).select()
                .innerJoin<Owner>().on((petOwnerId eq ownerId) and (ownerLastName eq "Davis"))
                .resultList
        }
        pets shouldHaveSize 2
        capture.sql() shouldContain "INNER JOIN owner o"
        capture.sql() shouldContain ".owner_id = o"
    }

    @Test
    fun `an inner join takes a predicate equating no key as its ON clause`(orm: ORMTemplate, capture: SqlCapture) {
        capture.execute {
            orm.entity(Pet::class).select().innerJoin<Visit>().on(visitId greater petId).resultList
        }
        capture.sql() shouldContain "INNER JOIN visit v ON v.id > p.id"
    }

    @Test
    fun `a semi-join takes the predicate as its relation and condition`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Pet::class).select()
                .semiJoin<Visit>().on((visitPetId eq petId) and (visitDescription eq "rabies shot"))
                .resultList
        }
        pets shouldHaveSize 4
        capture.sql() shouldContain "WHERE EXISTS ( SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ? )"
    }

    @Test
    fun `an anti-join keeps the rows without a related row`(orm: ORMTemplate, capture: SqlCapture) {
        val all = orm.entity(Pet::class).select().resultList.size
        val visited = orm.entity(Pet::class).select().semiJoin<Visit>().on(visitPetId eq petId).resultList.size
        val pets = capture.execute {
            orm.entity(Pet::class).select().antiJoin<Visit>().on(visitPetId eq petId).resultList
        }
        pets shouldHaveSize all - visited
        capture.sql() shouldContain "WHERE NOT EXISTS ( SELECT 1 FROM visit v WHERE v.pet_id = p.id )"
    }

    @Test
    fun `the block DSL takes the predicate`(orm: ORMTemplate, capture: SqlCapture) {
        val all = orm.entity(Pet::class).select().resultList.size
        val visited = orm.entity(Pet::class).select().semiJoin<Visit>().on(visitPetId eq petId).resultList.size
        val pets = capture.execute {
            orm.entity(Pet::class).select {
                semiJoin<Visit>((visitPetId eq petId) and (visitDescription eq "rabies shot"))
            }.resultList
        }
        pets shouldHaveSize 4
        capture.sql() shouldContain "WHERE EXISTS ( SELECT 1 FROM visit v WHERE v.pet_id = p.id AND v.description = ? )"
        val joined = orm.entity(Pet::class).select { innerJoin<Visit>(visitPetId eq petId) }.resultList
        joined shouldHaveSize 14
        val leftJoined = orm.entity(Pet::class).select { leftJoin(Visit::class, visitPetId eq petId) }.resultList.size
        val innerJoined = orm.entity(Pet::class).select { innerJoin(Visit::class, visitPetId eq petId) }.resultList.size
        leftJoined shouldBe innerJoined + all - visited
        orm.entity(Pet::class).select { rightJoin(Visit::class, visitPetId eq petId) }.resultList shouldHaveSize 14
        orm.entity(Pet::class).select { antiJoin(Visit::class, visitPetId eq petId) }.resultList shouldHaveSize all - visited
    }

    @Test
    fun `a semi-join refuses a predicate equating no key`(orm: ORMTemplate) {
        val exception = shouldThrow<PersistenceException> {
            orm.entity(Pet::class).select().semiJoin<Visit>().on(visitId greater petId)
        }
        exception.message shouldContain "semiJoin(Visit): the predicate equates no key of Visit with a key of another table"
        exception.message shouldContain "on(Visit_.<key>.id eq <Table>_.id)"
        shouldThrow<PersistenceException> {
            orm.entity(Pet::class).select().antiJoin<Visit>().on(visitId eq petId)
        }.message shouldContain "antiJoin(Visit): the predicate equates no key"
    }

    @Test
    fun `the template form stays beside the predicate`(orm: ORMTemplate, capture: SqlCapture) {
        val pets = capture.execute {
            orm.entity(Pet::class).select().innerJoin<Visit>().on { "${t(visitPetId)} = ${t(petId)}" }.resultList
        }
        pets shouldHaveSize 14
        capture.sql() shouldContain "INNER JOIN visit v ON v.pet_id = p.id"
    }

    @Test
    fun `an anti-join on a table the query joins takes its condition on the join`(orm: ORMTemplate, capture: SqlCapture) {
        // The visits per owner, leaving out the owner of the pet named George, which had 2 of the 14 visits.
        val counts = capture.execute {
            orm.entity(Owner::class).select(Long::class) { "COUNT(*)" }
                .innerJoin<Pet>().on<Owner>()
                .innerJoin<Visit>().on<Pet>()
                .antiJoin<Pet>().on(Owner::class, petName eq "George")
                .groupBy(ownerId)
                .resultList
        }
        counts shouldHaveSize 5
        counts.sum() shouldBe 12
        capture.sql() shouldContain "INNER JOIN pet p ON p.owner_id = o.id INNER JOIN visit v ON v.pet_id = p.id"
        capture.sql() shouldContain "NOT EXISTS ( SELECT 1 FROM pet p1 WHERE p1.owner_id = o.id AND p1.name = ? )"
    }

    @Test
    fun `a semi-join on a table the query joins leaves a where condition to the joined rows`(orm: ORMTemplate, capture: SqlCapture) {
        // The owners with their pets, for the owners who keep a pet named Max: Jean keeps Samantha and Max.
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .innerJoin<Pet>().on<Owner>()
                .semiJoin<Pet>().on(Owner::class, petName eq "Max")
                .where(petName neq "Max")
                .resultList
        }
        owners.map { it.firstName } shouldBe listOf("Jean")
        capture.sql() shouldContain "WHERE p.name <> ? AND EXISTS ( SELECT 1 FROM pet p1 WHERE p1.owner_id = o.id AND p1.name = ? )"
    }

    @Test
    fun `a semi-join without a condition on a table the query joins keeps the rows with a related row`(orm: ORMTemplate, capture: SqlCapture) {
        // Every owner the join returns has a pet, so the semi-join filters nothing; where names the joined pets.
        val owners = capture.execute {
            orm.entity(Owner::class).select()
                .innerJoin<Pet>().on<Owner>()
                .semiJoin<Pet>().on<Owner>()
                .where(petName eq "Max")
                .resultList
        }
        owners.map { it.firstName } shouldBe listOf("Jean")
        capture.sql() shouldContain "WHERE p.name = ? AND EXISTS ( SELECT 1 FROM pet p1 WHERE p1.owner_id = o.id )"
    }

    @Test
    fun `a path to a table the query's foreign keys join names the joined rows`(orm: ORMTemplate, capture: SqlCapture) {
        // Pet joins its owner through its own foreign key: where names that owner, the join's condition the semi-join's.
        val pets = capture.execute {
            orm.entity(Pet::class).select()
                .semiJoin<Owner>().on(Pet::class, ownerLastName eq "Davis")
                .where(ownerFirstName eq "Betty")
                .resultList
        }
        pets.map { it.name } shouldBe listOf("Leo")
        capture.sql() shouldContain "WHERE o.first_name = ? AND EXISTS ( SELECT 1 FROM owner o1 WHERE o1.id = p.owner_id AND o1.last_name = ? )"
    }
}
