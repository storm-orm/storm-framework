package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.EntityCallback
import st.orm.template.model.City
import st.orm.template.model.Owner

/**
 * Covers what the "after" callbacks observe for Kotlin data classes, which are rebuilt through the same reflection
 * provider as Java records.
 */
@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class EntityCallbackObservedEntityTest(
    @Autowired val orm: ORMTemplate,
) {

    private fun observingCities(observed: MutableList<City>) = orm.withEntityCallback(object : EntityCallback<City> {
        override fun afterInsert(entity: City) {
            observed.add(entity)
        }
    })

    @Test
    fun `batch insert should reach the list form as one list`() {
        val batches = mutableListOf<List<City>>()
        val recording = orm.withEntityCallback(object : EntityCallback<City> {
            override fun afterInsert(entities: List<City>) {
                batches.add(entities.toList())
            }
        })
        val ids = recording.entity(City::class).insertAndFetchIds(
            listOf(City(name = "Kotlin list one"), City(name = "Kotlin list two")),
        )
        batches shouldHaveSize 1
        batches.first().map { it.id } shouldBe ids
    }

    @Test
    fun `insert should observe the entity as sent`() {
        val observed = mutableListOf<City>()
        observingCities(observed).entity(City::class).insert(City(name = "Kotlin sent"))
        observed shouldHaveSize 1
        // The method reports nothing, so the key stays at its default rather than being read back.
        observed.first().id shouldBe 0
    }

    @Test
    fun `insertAndFetchId should observe the generated primary key`() {
        val observed = mutableListOf<City>()
        val id = observingCities(observed).entity(City::class)
            .insertAndFetchId(City(name = "Kotlin identified"))
        observed shouldHaveSize 1
        observed.first().id shouldBe id
        observed.first().name shouldBe "Kotlin identified"
    }

    @Test
    fun `insertAndFetch should observe the fetched entity`() {
        val observed = mutableListOf<City>()
        val inserted = observingCities(observed).entity(City::class)
            .insertAndFetch(City(name = "Kotlin fetched"))
        observed shouldHaveSize 1
        observed.first() shouldBe inserted
    }

    @Test
    fun `batch insertAndFetchIds should observe the generated primary keys`() {
        val observed = mutableListOf<City>()
        val ids = observingCities(observed).entity(City::class).insertAndFetchIds(
            listOf(City(name = "Kotlin batch one"), City(name = "Kotlin batch two")),
        )
        observed shouldHaveSize 2
        observed.map { it.id } shouldBe ids
    }

    @Test
    fun `updateAndFetch should observe the database applied version increment`() {
        val observed = mutableListOf<Owner>()
        val template = orm.withEntityCallback(object : EntityCallback<Owner> {
            override fun afterUpdate(entity: Owner) {
                observed.add(entity)
            }
        })
        val owners = template.entity(Owner::class)
        val owner = owners.getById(1)
        val updated = owners.updateAndFetch(owner.copy(telephone = "3333333333"))
        observed shouldHaveSize 1
        observed.first().version shouldBe updated.version
        observed.first().version shouldBe owner.version + 1
    }
}
