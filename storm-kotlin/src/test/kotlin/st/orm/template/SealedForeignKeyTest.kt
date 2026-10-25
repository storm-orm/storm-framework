package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Metamodel
import st.orm.PersistenceException
import st.orm.Ref
import st.orm.template.model.Checkup
import st.orm.template.model.JoinedCat
import st.orm.template.model.Pet
import st.orm.template.model.Surgery
import st.orm.template.model.Treatment
import st.orm.template.model.Vet
import java.time.LocalDate

/**
 * Foreign keys declared by the subtypes of a sealed entity, and a subtype of a single-table hierarchy as an entity of
 * its own, through the Kotlin API with runtime metamodels.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class SealedForeignKeyTest(
    @Autowired val orm: ORMTemplate,
) {

    @Test
    fun `select sealed type hydrates the foreign keys of every subtype`() {
        val treatments = orm.entity(Treatment::class).select().resultList
        treatments shouldHaveSize 4
        val checkup = treatments[0].shouldBeInstanceOf<Checkup>()
        checkup.pet.name shouldBe "Leo"
        checkup.pet.owner.shouldNotBeNull().lastName shouldBe "Davis"
        checkup.vet.shouldNotBeNull().id() shouldBe 1
        treatments[1].shouldBeInstanceOf<Checkup>().vet.shouldBeNull()
        val surgery = treatments[2].shouldBeInstanceOf<Surgery>()
        surgery.pet.name shouldBe "Rosy"
        surgery.surgeon.lastName shouldBe "Leary"
    }

    @Test
    fun `insert and update through the sealed type write the foreign keys`() {
        val treatments = orm.entity(Treatment::class)
        val pet = orm.entity(Pet::class).getById(2)
        val vet = orm.entity(Vet::class).getById(4)
        val id = treatments.insertAndFetchId(Surgery(pet = pet, treatmentDate = LocalDate.of(2024, 5, 1), surgeon = vet, duration = 30))
        val surgery = treatments.getById(id).shouldBeInstanceOf<Surgery>()
        surgery.surgeon.lastName shouldBe "Ortega"
        treatments.update(surgery.copy(surgeon = orm.entity(Vet::class).getById(5)))
        treatments.getById(id).shouldBeInstanceOf<Surgery>().surgeon.lastName shouldBe "Stevens"
        val checkupId = treatments.insertAndFetchId(Checkup(pet = pet, treatmentDate = LocalDate.of(2024, 5, 2), vet = Ref.of(vet), notes = "Fine"))
        treatments.getById(checkupId).shouldBeInstanceOf<Checkup>().vet.shouldNotBeNull().id() shouldBe 4
    }

    @Test
    fun `where on the sealed type through a runtime metamodel`() {
        val pet = Metamodel.of<Treatment, Pet>(Treatment::class.java, "pet")
        val leo = orm.entity(Pet::class).getById(1)
        orm.entity(Treatment::class).select().where(pet eq leo).resultList shouldHaveSize 2
        val petName = Metamodel.of<Treatment, String>(Treatment::class.java, "pet.name")
        orm.entity(Treatment::class).select().where(petName eq "Rosy").resultList shouldHaveSize 1
        val surgeon = Metamodel.of<Surgery, Vet>(Surgery::class.java, "surgeon")
        val leary = orm.entity(Vet::class).getById(2)
        orm.entity(Treatment::class).select().where(surgeon eq leary).resultList shouldHaveSize 1
    }

    @Test
    fun `semi-join and anti-join on a subtype carry its discriminator`(): Unit = runBlocking {
        // Surgeries treat pets 3 and 1, checkups pets 1 and 2; of 13 pets, the discriminator tells them apart.
        val pets = orm.entity(Pet::class)
        pets.select().antiJoin<Surgery>().on<Pet>().resultList shouldHaveSize 11
        pets.select().semiJoin<Surgery>().on<Pet>().resultList shouldHaveSize 2
        val duration = Metamodel.of<Surgery, Int>(Surgery::class.java, "duration")
        pets.select().antiJoin<Surgery>().on<Pet> { duration greaterEq 60 }.resultList shouldHaveSize 12
        pets.select().semiJoin<Surgery>().on<Pet> { duration greaterEq 60 }.resultList.map { it.name } shouldBe listOf("Rosy")
        pets.select().antiJoin<Checkup>().on<Pet>().resultList shouldHaveSize 11
        pets.select().antiJoin<Surgery>().on<Pet>().windows(5).toList().map { it.content().size } shouldBe listOf(5, 5, 1)
    }

    @Test
    fun `subtype repository works on the shared table`() {
        val surgeries = orm.entity(Surgery::class)
        surgeries.count() shouldBe 2
        surgeries.select().resultList.map { it.surgeon.lastName } shouldBe listOf("Leary", "Douglas")
        surgeries.findById(1).shouldBeNull()
        surgeries.getById(3).pet.name shouldBe "Rosy"
        val duration = Metamodel.of<Surgery, Int>(Surgery::class.java, "duration")
        surgeries.select().where(duration eq 45).resultList shouldHaveSize 1
        val pet = orm.entity(Pet::class).getById(2)
        surgeries.insert(Surgery(pet = pet, treatmentDate = LocalDate.of(2024, 6, 1), surgeon = orm.entity(Vet::class).getById(1), duration = 15))
        surgeries.count() shouldBe 3
        orm.entity(Checkup::class).count() shouldBe 2
        surgeries.delete().where(duration eq 15).executeUpdate() shouldBe 1
        surgeries.removeAll()
        surgeries.count() shouldBe 0
        orm.entity(Treatment::class).count() shouldBe 2
    }

    @Test
    fun `joined subtype repository is refused`() {
        val exception = assertThrows<PersistenceException> { orm.entity(JoinedCat::class) }
        exception.message.shouldNotBeNull() shouldContain "joined inheritance"
    }
}
