package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.PersistenceException
import st.orm.repository.entity
import st.orm.repository.insertAndFetchIds
import st.orm.repository.writeSet
import st.orm.template.model.Address
import st.orm.template.model.Appointment
import st.orm.template.model.AppointmentReport
import st.orm.template.model.City
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.template.model.PetType
import st.orm.template.model.Visit
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class WriteSetTest(
    @Autowired val orm: ORMTemplate,
) {

    private fun newOwner(firstName: String) = Owner(
        firstName = firstName,
        lastName = "WriteSet",
        address = Address("1 Graph Road", City(id = 1, name = "Sun Paririe")),
        telephone = null,
        version = 0,
    )

    private val dog = PetType(id = 1, name = "dog")

    @Test
    fun `insert should persist a data class graph with a shared unsaved parent`() {
        val owner = newOwner("Alice")
        val wolfie = Pet(name = "Wolfie", birthDate = LocalDate.of(2024, 1, 1), type = dog, owner = owner)
        val rex = Pet(name = "Rex", birthDate = LocalDate.of(2024, 2, 2), type = dog, owner = owner)
        val visit = Visit(visitDate = LocalDate.of(2026, 7, 14), description = "Check-up", pet = wolfie, timestamp = Instant.now())
        orm.writeSet().insert(listOf(wolfie, rex, visit))
        val owners = orm.entity(Owner::class).findAll().filter { it.firstName == "Alice" }
        owners shouldHaveSize 1
        val pets = orm.entity(Pet::class).findAll().filter { it.owner?.id == owners.single().id }
        pets shouldHaveSize 2
        val fetchedVisit = orm.entity(Visit::class).findAll().single { it.description == "Check-up" }
        fetchedVisit.pet.name shouldBe "Wolfie"
        fetchedVisit.pet.owner.shouldNotBeNull().firstName shouldBe "Alice"
    }

    @Test
    fun `insertAndFetch should pull unsaved city through the inline address component`() {
        val owner = newOwner("Inline").copy(address = Address("2 Discovery Lane", City(name = "Graphville")))
        val fetched = orm.writeSet().insertAndFetch(listOf(owner))
        fetched shouldHaveSize 1
        val fetchedOwner = fetched.single() as Owner
        fetchedOwner.address.city.shouldNotBeNull().name shouldBe "Graphville"
        fetchedOwner.address.city.shouldNotBeNull().id shouldNotBe 0
    }

    @Test
    fun `writeSet block should scope write-set calls with typed single-root variants`() {
        val owner = newOwner("Block")
        val pet = Pet(name = "BlockPet", birthDate = LocalDate.of(2024, 8, 8), type = dog, owner = owner)
        val visit: Visit = orm.writeSet {
            val inserted = insertAndFetch(
                Visit(visitDate = LocalDate.of(2026, 5, 5), description = "Block visit", pet = pet, timestamp = Instant.now()),
            )
            update(inserted.copy(description = "Block visit updated"))
            inserted
        }
        orm.entity(Visit::class).findAll().single { it.id == visit.id }.description shouldBe "Block visit updated"
        visit.pet.owner.shouldNotBeNull().firstName shouldBe "Block"
    }

    @Test
    fun `writeSet should be accessible from a repository`() {
        val pets = orm.entity(Pet::class)
        val owner = newOwner("FromRepo")
        pets.writeSet().insert(listOf(Pet(name = "RepoPet", birthDate = LocalDate.of(2023, 6, 6), type = dog, owner = owner)))
        val fetched = pets.findAll().single { it.name == "RepoPet" }
        fetched.owner.shouldNotBeNull().firstName shouldBe "FromRepo"
    }

    @Test
    fun `update should reject unsaved entities`() {
        val exception = assertThrows<PersistenceException> {
            orm.writeSet().update(listOf(newOwner("Never")))
        }
        exception.message.shouldNotBeNull() shouldContain "unsaved"
    }

    @Test
    fun `remove should order children before parents`() {
        val owner = newOwner("Removable")
        val pet = Pet(name = "Doomed", birthDate = LocalDate.of(2020, 1, 1), type = dog, owner = owner)
        val visit = Visit(visitDate = LocalDate.of(2026, 3, 3), description = "Last visit", pet = pet, timestamp = Instant.now())
        val inserted = orm.writeSet().insertAndFetch(listOf(owner, pet, visit))
        // Parents first in the argument list; the write set reorders (H2 enforces the FK constraints).
        orm.writeSet().remove(inserted)
        orm.entity(Owner::class).findAll().none { it.firstName == "Removable" } shouldBe true
        orm.entity(Pet::class).findAll().none { it.name == "Doomed" } shouldBe true
        orm.entity(Visit::class).findAll().none { it.description == "Last visit" } shouldBe true
    }

    @Test
    fun `actions should accept entities as varargs`() {
        val owner = newOwner("Vera")
        val pet = Pet(name = "VarargPet", birthDate = LocalDate.of(2024, 4, 4), type = dog, owner = owner)
        val visit = Visit(visitDate = LocalDate.of(2026, 7, 15), description = "Vararg visit", pet = pet, timestamp = Instant.now())
        val inserted = orm.writeSet().insertAndFetch(pet, visit)
        inserted shouldHaveSize 2
        val insertedPet = inserted[0] as Pet
        val insertedVisit = inserted[1] as Visit
        orm.writeSet().remove(insertedPet.owner.shouldNotBeNull(), insertedPet, insertedVisit)
        orm.entity(Owner::class).findAll().none { it.firstName == "Vera" } shouldBe true
        orm.entity(Pet::class).findAll().none { it.name == "VarargPet" } shouldBe true
        orm.entity(Visit::class).findAll().none { it.description == "Vararg visit" } shouldBe true
    }

    @Test
    fun `insertAndFetchIds should accept entities as varargs`() {
        val owner = newOwner("Ida")
        val pet = Pet(name = "VarargIdsPet", birthDate = LocalDate.of(2024, 5, 5), type = dog, owner = owner)
        val visit = Visit(visitDate = LocalDate.of(2026, 7, 22), description = "Vararg ids visit", pet = pet, timestamp = Instant.now())
        val ids = orm.writeSet().insertAndFetchIds(pet, visit)
        ids shouldHaveSize 2
        orm.entity(Pet::class).findAll().single { it.name == "VarargIdsPet" }.id shouldBe ids[0]
        orm.entity(Visit::class).findAll().single { it.description == "Vararg ids visit" }.id shouldBe ids[1]
    }

    @Test
    fun `insertAndFetch should correlate an entity-typed key by database key`() {
        // The appointment carries sub-second precision that the second-precision column does not store, so the
        // key entity read back from the database differs structurally from the in-memory data class instance; the
        // fetch correlates by the database key.
        val appointment = Appointment(description = "Vaccination", scheduledAt = LocalDateTime.of(2026, 5, 4, 10, 30, 15, 123_456_789))
        val fetched = orm.writeSet().insertAndFetch(AppointmentReport(appointment = appointment, report = "All clear"))
        fetched.appointment.id shouldNotBe 0
        fetched.report shouldBe "All clear"
        fetched.appointment.scheduledAt.nano shouldBe 0
        fetched.appointment.scheduledAt shouldNotBe appointment.scheduledAt
    }
}
