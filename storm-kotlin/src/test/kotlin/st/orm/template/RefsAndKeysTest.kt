package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Data
import st.orm.Metamodel
import st.orm.Operator.*
import st.orm.Ref
import st.orm.Scrollable
import st.orm.template.model.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class RefsAndKeysTest(
    @Autowired val orm: ORMTemplate,
) {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // Entity.ref() extension function tests

    @Test
    fun `ref on entity should create a Ref with correct id`() {
        val city = City(id = 42, name = "RefCity")
        val cityRef = city.ref()
        cityRef.shouldNotBeNull()
        cityRef.shouldBeInstanceOf<Ref<City>>()
        cityRef.id() shouldBe 42
    }

    @Test
    fun `ref on entity should provide access to the entity`() {
        val city = City(id = 5, name = "Monona")
        val cityRef = city.ref()
        val fetched = cityRef.fetch()
        fetched.shouldNotBeNull()
        fetched.id shouldBe 5
        fetched.name shouldBe "Monona"
    }

    @Test
    fun `ref on entity with default id should create ref with zero id`() {
        val city = City(name = "NewCity")
        val cityRef = city.ref()
        cityRef.id() shouldBe 0
    }

    @Test
    fun `ref on different entity types should create correct refs`() {
        val vet = Vet(id = 3, firstName = "Linda", lastName = "Douglas")
        val vetRef = vet.ref()
        vetRef.shouldNotBeNull()
        vetRef.id() shouldBe 3
    }

    @Test
    fun `ref on pet type entity should create correct ref`() {
        val petType = PetType(id = 1, name = "dog")
        val petTypeRef = petType.ref()
        petTypeRef.shouldNotBeNull()
        petTypeRef.id() shouldBe 1
    }

    @Test
    fun `ref from persisted entity should be usable for querying`() {
        // Fetch a city from the database and create a ref from it.
        val city = orm.entity(City::class).select().where(2).singleResult
        val cityRef = city.ref()
        cityRef.id() shouldBe 2

        // Use the ref to query owners in that city.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val owners = repo.select().where(cityPath, cityRef).resultList
        // data.sql: City 2 (Madison) has 4 owners: George (2), Peter (5), Maria (8), David (9).
        owners shouldHaveSize 4
    }

    // Ref.of static methods tests

    @Test
    fun `Ref of with class and id should create detached ref`() {
        val cityRef: Ref<City> = Ref.of(City::class.java, 3)
        cityRef.shouldNotBeNull()
        cityRef.id() shouldBe 3
    }

    @Test
    fun `Ref of with entity should create ref containing entity`() {
        val city = City(id = 4, name = "Windsor")
        val cityRef: Ref<City> = Ref.of(city)
        cityRef.shouldNotBeNull()
        cityRef.id() shouldBe 4
        cityRef.fetch().name shouldBe "Windsor"
    }

    // QueryTemplate.ref tests

    @Test
    fun `orm ref should create ref for entity type with id`() {
        val cityRef = orm.ref(City::class, 1)
        cityRef.shouldNotBeNull()
        cityRef.id() shouldBe 1
    }

    @Test
    fun `orm ref with record should create ref containing record`() {
        val city = City(id = 2, name = "Madison")
        val cityRef = orm.ref(city, 2)
        cityRef.shouldNotBeNull()
        cityRef.id() shouldBe 2
    }

    // Metamodel.key() extension function tests

    @Test
    fun `key on metamodel should return Metamodel Key`() {
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = idMetamodel.key()
        key.shouldNotBeNull()
        key.shouldBeInstanceOf<Metamodel.Key<City, Int>>()
    }

    @Test
    fun `key on metamodel that is already a Key should return itself`() {
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key1 = idMetamodel.key()
        val key2 = key1.key()
        // Calling key() on a Key should return the same instance.
        key2.shouldNotBeNull()
        key2.shouldBeInstanceOf<Metamodel.Key<City, Int>>()
    }

    @Test
    fun `key on name metamodel should return Metamodel Key`() {
        val nameMetamodel = Metamodel.of<City, String>(City::class.java, "name")
        val key = nameMetamodel.key()
        key.shouldNotBeNull()
        key.shouldBeInstanceOf<Metamodel.Key<City, String>>()
    }

    @Test
    fun `key should be usable in scrollpagination`() {
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = idMetamodel.key()
        // Use key in scroll to verify it works with scrolling.
        val window = orm.entity(City::class).select().scroll(Scrollable.of(key, 3))
        window.shouldNotBeNull()
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `key should be usable in scrolling pagination`() {
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = idMetamodel.key()
        // First page.
        val firstWindow = orm.entity(City::class).select().scroll(Scrollable.of(key, 3))
        firstWindow.content shouldHaveSize 3

        // Next page using scrolling with cursor value from last item.
        val lastId = firstWindow.content.last().id
        val nextWindow = orm.entity(City::class).select().scroll(Scrollable.of(key, 3).after(lastId))
        nextWindow.content shouldHaveSize 3
        nextWindow.hasNext shouldBe false
    }

    // Integration: using refs with QueryBuilder where clauses

    @Test
    fun `where with Ref should filter entities by foreign key reference`() {
        // data.sql: City 1 (Sun Paririe) has 1 owner: Betty (id=1).
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val owners = repo.select().where(cityPath, cityRef).resultList
        owners shouldHaveSize 1
        owners[0].firstName shouldBe "Betty"
    }

    @Test
    fun `whereRef with iterable of refs should filter entities`() {
        val repo = orm.entity(City::class)
        val ref1: Ref<City> = Ref.of(City::class.java, 1)
        val ref2: Ref<City> = Ref.of(City::class.java, 2)
        val cities = repo.select().whereRef(listOf(ref1, ref2)).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `where with ref from entity ref extension should filter correctly`() {
        val city = City(id = 3, name = "McFarland")
        val cityRef = city.ref()
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val owners = repo.select().where(cityPath, cityRef).resultList
        // data.sql: City 3 (McFarland) has 1 owner: Eduardo (id=3).
        owners shouldHaveSize 1
        owners[0].firstName shouldBe "Eduardo"
    }

    // Ref predicates: the top-level eq/neq/inRefs/notInRefs forms and the record-list where.

    @Test
    fun `eq with Ref should filter entities by foreign key reference`() {
        // data.sql: city 1 has one owner (Betty), the other nine live elsewhere.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val inCity = repo.select().where(cityPath eq cityRef).resultList
        inCity.map { it.firstName } shouldBe listOf("Betty")
        val elsewhere = repo.select().where(cityPath neq cityRef).resultList
        elsewhere shouldHaveSize 9
        elsewhere.none { it.firstName == "Betty" } shouldBe true
    }

    @Test
    fun `inRefs and notInRefs should partition entities by foreign key references`() {
        // data.sql: city 1 has one owner and city 2 has four; the other five owners live in cities 3 to 6.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRefs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 2))
        val inCities = repo.select().where(cityPath inRefs cityRefs).resultList
        inCities shouldHaveSize 5
        val outsideCities = repo.select().where(cityPath notInRefs cityRefs).resultList
        outsideCities shouldHaveSize 5
        (inCities.map { it.id } + outsideCities.map { it.id }).toSet() shouldBe repo.findAll().map { it.id }.toSet()
    }

    @Test
    fun `where with path and records should match the records by their keys`() {
        // The records identify rows by key; the names carried along play no part in the match.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val owners = repo.select()
            .where(cityPath, listOf(City(id = 1, name = "unused"), City(id = 2, name = "unused")))
            .resultList
        owners shouldHaveSize 5
    }
}
