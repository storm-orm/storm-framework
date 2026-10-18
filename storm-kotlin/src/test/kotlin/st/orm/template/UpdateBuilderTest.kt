package st.orm.template

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Metamodel
import st.orm.OptimisticLockException
import st.orm.PersistenceException
import st.orm.Ref
import st.orm.TransactionIsolation.REPEATABLE_READ
import st.orm.repository.entity
import st.orm.repository.update
import st.orm.template.model.Address
import st.orm.template.model.City
import st.orm.template.model.Owner
import st.orm.template.model.Pet
import st.orm.template.model.Visit
import st.orm.test.SqlCapture

/**
 * The UPDATE builder through the Kotlin API: the block DSL and the chained form, values, refs, null and expressions,
 * the refusals, and the entity cache of the transaction the statement runs in.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class UpdateBuilderTest(
    @Autowired val orm: ORMTemplate,
) {

    private val ownerId = Metamodel.of<Owner, Int>(Owner::class.java, "id")
    private val ownerFirstName = Metamodel.of<Owner, String>(Owner::class.java, "firstName")
    private val ownerTelephone = Metamodel.of<Owner, String>(Owner::class.java, "telephone")
    private val ownerAddress = Metamodel.of<Owner, Address>(Owner::class.java, "address")
    private val ownerCityName = Metamodel.of<Owner, String>(Owner::class.java, "address.city.name")
    private val ownerVersion = Metamodel.of<Owner, Int>(Owner::class.java, "version")
    private val cityId = Metamodel.of<City, Int>(City::class.java, "id")
    private val cityName = Metamodel.of<City, String>(City::class.java, "name")
    private val petId = Metamodel.of<Pet, Int>(Pet::class.java, "id")
    private val petName = Metamodel.of<Pet, String>(Pet::class.java, "name")
    private val petOwner = Metamodel.of<Pet, Owner>(Pet::class.java, "owner")
    private val petOwnerFirstName = Metamodel.of<Pet, String>(Pet::class.java, "owner.firstName")
    private val visitDescription = Metamodel.of<Visit, String>(Visit::class.java, "description")

    @Test
    fun `block DSL sets columns on the rows the condition selects`() {
        val owners = orm.entity(Owner::class)
        val before = owners.getById(1)
        val updated = owners.update {
            set(ownerFirstName, "Bette")
            set(ownerTelephone, null)
            where(ownerId eq 1)
        }.executeUpdate()
        updated shouldBe 1
        val after = owners.getById(1)
        after.firstName shouldBe "Bette"
        after.telephone shouldBe null
        after.version shouldBe before.version + 1
        owners.getById(2).firstName shouldBe "George"
    }

    @Test
    fun `chained form sets columns on the rows the condition selects`() {
        val owners = orm.entity(Owner::class)
        owners.update()
            .set(ownerTelephone, "5550000")
            .where(ownerId eq 2)
            .executeUpdate() shouldBe 1
        owners.getById(2).telephone shouldBe "5550000"
    }

    @Test
    fun `chained form takes the assignments after the condition`() {
        val owners = orm.entity(Owner::class)
        owners.update()
            .where(ownerId eq 2)
            .set(ownerTelephone, "5550001")
            .executeUpdate() shouldBe 1
        owners.getById(2).telephone shouldBe "5550001"
    }

    @Test
    fun `block DSL sets the rows a path to a related table selects`() {
        val owners = orm.entity(Owner::class)
        val updated = owners.update {
            set(ownerTelephone, "5552222")
            where(ownerCityName eq "Madison")
        }.executeUpdate()
        updated shouldBe 4
        owners.select { where(ownerTelephone eq "5552222") }.resultCount shouldBe 4L
    }

    @Test
    fun `block DSL sets the rows a semi-join selects`() {
        val owners = orm.entity(Owner::class)
        owners.update {
            set(ownerTelephone, "5553333")
            semiJoin<Pet, Owner>()
            where(petName eq "Leo")
        }.executeUpdate() shouldBe 1
        owners.getById(1).telephone shouldBe "5553333"
    }

    @Test
    fun `block DSL sets the rows a nested semi-join selects`() {
        // data.sql: the neutered visits are of pets of owners 2, 3 and 6.
        val owners = orm.entity(Owner::class)
        val capture = SqlCapture()
        capture.execute {
            owners.update {
                set(ownerTelephone, "5554444")
                semiJoin<Pet, Owner>()
                semiJoin<Visit, Pet>()
                where(visitDescription eq "neutered")
            }.executeUpdate()
        } shouldBe 3
        capture.statements().single().statement().replace(Regex("\\s+"), " ").replace("( ", "(").replace(" )", ")") shouldContain "AND EXISTS (SELECT 1 FROM visit v"
        owners.select { where(ownerTelephone eq "5554444") }.resultList.map { it.id }.sorted() shouldBe listOf(2, 3, 6)
    }

    @Test
    fun `expression computes the value from the row`() {
        val cities = orm.entity(City::class)
        cities.update {
            set(cityName) { "UPPER(${t(cityName)})" }
            where(cityId eq 2)
        }.executeUpdate()
        cities.getById(2).name shouldBe "MADISON"
        cities.getById(1).name shouldBe "Sun Paririe"
    }

    @Test
    fun `foreign key takes a record, a ref and null`() {
        val pets = orm.entity(Pet::class)
        pets.update {
            set(petOwner, orm.entity(Owner::class).getById(2))
            where(petId eq 1)
        }.executeUpdate()
        pets.getById(1).owner?.id shouldBe 2
        pets.update().set(petOwner, Ref.of(Owner::class.java, 3)).where(petId eq 1).executeUpdate()
        pets.getById(1).owner?.id shouldBe 3
        pets.update {
            set(petOwner, null)
            where(petId eq 1)
        }.executeUpdate()
        pets.getById(1).owner shouldBe null
    }

    @Test
    fun `inline record sets each of its columns`() {
        val owners = orm.entity(Owner::class)
        val city = orm.entity(City::class).getById(5)
        owners.update {
            set(ownerAddress, Address("1 Lake Rd.", city))
            where(ownerId eq 1)
        }.executeUpdate()
        val owner = owners.getById(1)
        owner.address.address shouldBe "1 Lake Rd."
        owner.address.city?.id shouldBe 5
    }

    @Test
    fun `repository lookup and template create update builders`() {
        orm.update<City>().set(cityName, "Lookup").where(cityId eq 3).executeUpdate() shouldBe 1
        orm.entity(City::class).getById(3).name shouldBe "Lookup"
        orm.updateFrom(City::class).set(cityName, "Template").where(cityId eq 4).executeUpdate() shouldBe 1
        orm.entity(City::class).getById(4).name shouldBe "Template"
    }

    @Test
    fun `refuses fields an update does not set`() {
        val owners = orm.entity(Owner::class)
        assertThrows<PersistenceException> { owners.update { set(ownerId, 9) } }.message shouldContain "primary key"
        assertThrows<PersistenceException> { owners.update { set(ownerVersion, 9) } }.message shouldContain "version"
        assertThrows<PersistenceException> { owners.update { set(ownerFirstName, null) } }.message shouldContain "not nullable"
        assertThrows<PersistenceException> { orm.entity(Pet::class).update { set(petOwnerFirstName, "x") } }
            .message shouldContain "related table"
        assertThrows<PersistenceException> {
            owners.update {
                set(ownerTelephone, "1")
                set(ownerTelephone, "2")
            }
        }.message shouldContain "set twice"
    }

    @Test
    fun `refuses an update without a condition unless unsafe`() {
        val cities = orm.entity(City::class)
        assertThrows<PersistenceException> { cities.update { set(cityName, "Everywhere") }.executeUpdate() }
            .message shouldContain "unsafe()"
        cities.update {
            set(cityName, "Everywhere")
            unsafe()
        }.executeUpdate() shouldBe 6
    }

    @Test
    fun `refuses set on a select`() {
        assertThrows<PersistenceException> { orm.entity(City::class).select { set(cityName, "x") } }
    }

    // The entity cache of the transaction.

    @Test
    fun `a later read sees the values the update set`() {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val cities = orm.entity(City::class)
            val before = cities.getById(1)
            cities.update {
                set(cityName, "Renamed")
                where(cityId eq 1)
            }.executeUpdate()
            val after = cities.getById(1)
            after shouldNotBeSameInstanceAs before
            after.name shouldBe "Renamed"
        }
    }

    @Test
    fun `dirty checking does not skip a write the update made necessary`() {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val cities = orm.entity(City::class)
            val city = cities.getById(1)
            cities.update {
                set(cityName, "Renamed")
                where(cityId eq 1)
            }.executeUpdate()
            // The city as read is unchanged against what the transaction observed before the update. The update moved
            // the row, so writing it back is a write the database needs, not one dirty checking may skip.
            cities.update(city)
            cities.getById(1).name shouldBe "Sun Paririe"
        }
    }

    @Test
    fun `an entity read before the update fails its optimistic lock check`() {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val owners = orm.entity(Owner::class)
            val owner = owners.getById(1)
            owners.update {
                set(ownerTelephone, "5554444")
                where(ownerId eq 1)
            }.executeUpdate()
            assertThrows<OptimisticLockException> { owners.update(owner.copy(firstName = "Stale")) }
            setRollbackOnly()
        }
        orm.entity(Owner::class).getById(1).firstName shouldBe "Betty"
    }
}
