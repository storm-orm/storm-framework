package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.*
import st.orm.Operator.EQUALS
import st.orm.Operator.IN
import st.orm.repository.*
import st.orm.template.model.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class EntityRepositoryTest(
    @Autowired val orm: ORMTemplate,
) {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // EntityRepository: findBy/getBy with Metamodel field and value

    @Test
    fun `findBy with field and value should return matching entity`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.findBy(namePath, "Madison")
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `findBy with field and value should return null when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.findBy(namePath, "NonExistent")
        city.shouldBeNull()
    }

    @Test
    fun `getBy with field and value should return matching entity`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.getBy(namePath, "Madison")
        city.name shouldBe "Madison"
    }

    @Test
    fun `getBy with field and value should throw NoResultException when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NoResultException> {
            repo.getBy(namePath, "NonExistent")
        }
    }

    @Test
    fun `findAllBy with field and single value should return matching entities`() {
        // data.sql: Two owners have last_name 'Davis': Betty Davis (id=1) and Harold Davis (id=4).
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val owners = repo.findAllBy(lastNamePath, "Davis")
        owners shouldHaveSize 2
    }

    @Test
    fun `findAllBy with field and iterable values should return matching entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.findAllBy(namePath, listOf("Madison", "Windsor", "Monona"))
        cities shouldHaveSize 3
    }

    // EntityRepository: findRefBy/getRefBy with Metamodel field

    @Test
    fun `findRefBy with field and value should return matching ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRefBy<Any, Any, String>(namePath, "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRefBy with field and value should return null when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRefBy<Any, Any, String>(namePath, "NonExistent")
        ref.shouldBeNull()
    }

    @Test
    fun `findAllRefBy with field and value should return refs of matching entities`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val refs = repo.findAllRefBy(lastNamePath, "Davis")
        refs shouldHaveSize 2
    }

    @Test
    fun `getRefBy with field and value should return matching ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.getRefBy(namePath, "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRefBy with field and value should throw when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NoResultException> {
            repo.getRefBy(namePath, "NonExistent")
        }
    }

    // EntityRepository: countBy/existsBy with Metamodel field

    @Test
    fun `countBy with field and value should count matching entities`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        repo.countBy(lastNamePath, "Davis") shouldBe 2
    }

    @Test
    fun `countBy with field and ref value should count matching entities`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        repo.countBy(cityPath, cityRef) shouldBe 4
    }

    @Test
    fun `existsBy with field and value should return true when match exists`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.existsBy(namePath, "Madison") shouldBe true
    }

    @Test
    fun `existsBy with field and value should return false when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.existsBy(namePath, "NonExistent") shouldBe false
    }

    @Test
    fun `existsBy with field and ref value should return true when match exists`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        repo.existsBy(cityPath, cityRef) shouldBe true
    }

    @Test
    fun `existsBy with field and ref value should return false when no match`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        repo.existsBy(cityPath, cityRef) shouldBe false
    }

    // EntityRepository: deleteAllBy with Metamodel field

    @Test
    fun `removeAllBy with field and value should remove matching entities`() {
        val repo = orm.entity(Vet::class)
        val firstNamePath = metamodel<Vet, String>(repo.model, "first_name")
        // Vet 1 (James Carter) has no vet_specialty entries, safe to delete.
        val deleted = repo.removeAllBy(firstNamePath, "James")
        deleted shouldBe 1
        repo.count() shouldBe 5
    }

    @Test
    fun `removeAllBy with field and ref value should remove matching entities`() {
        // First insert a city, then insert an owner referencing it, then delete by city ref.
        val cityRepo = orm.entity(City::class)
        val newCity = cityRepo.insertAndFetch(City(name = "DeleteByRefCity"))
        val ownerRepo = orm.entity(Owner::class)
        val newOwner = ownerRepo.insertAndFetch(
            Owner(
                firstName = "Test",
                lastName = "DeleteByRef",
                address = Address("123 Test", newCity),
                telephone = "555",
                version = 0,
            ),
        )
        val cityPath = metamodel<Owner, City>(ownerRepo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, newCity.id)
        val deleted = ownerRepo.removeAllBy(cityPath, cityRef)
        deleted shouldBe 1
        ownerRepo.findById(newOwner.id).shouldBeNull()
    }

    @Test
    fun `removeAllBy with field and iterable values should remove matching entities`() {
        val repo = orm.entity(Vet::class)
        val firstNamePath = metamodel<Vet, String>(repo.model, "first_name")
        // Delete vets 1 (James) and 6 (Sharon) - both have no vet_specialty entries.
        val deleted = repo.removeAllBy(firstNamePath, listOf("James", "Sharon"))
        deleted shouldBe 2
        repo.count() shouldBe 4
    }

    @Test
    fun `removeAllByRef with field and iterable of refs should remove matching entities`() {
        // Create two owners and delete them by city ref.
        val cityRepo = orm.entity(City::class)
        val testCity = cityRepo.insertAndFetch(City(name = "RefDelCity"))
        val ownerRepo = orm.entity(Owner::class)
        ownerRepo.insertAndFetch(
            Owner(firstName = "A", lastName = "Test", address = Address("1", testCity), telephone = "111", version = 0),
        )
        ownerRepo.insertAndFetch(
            Owner(firstName = "B", lastName = "Test", address = Address("2", testCity), telephone = "222", version = 0),
        )
        val cityPath = metamodel<Owner, City>(ownerRepo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, testCity.id)
        val deleted = ownerRepo.removeAllByRef(cityPath, listOf(cityRef))
        deleted shouldBe 2
    }

    // EntityRepository: delete with predicate

    @Test
    fun `delete with whereBuilder predicate should delete matching entities`() {
        val repo = orm.entity(Vet::class)
        val firstNamePath = metamodel<Vet, String>(repo.model, "first_name")
        val deleted = repo.delete().where(firstNamePath eq "James").executeUpdate()
        deleted shouldBe 1
        repo.count() shouldBe 5
    }

    // EntityRepository: count/exists with PredicateBuilder directly

    @Test
    fun `count with PredicateBuilder should count matching entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val count = repo.count(namePath eq "Madison")
        count shouldBe 1
    }

    @Test
    fun `exists with PredicateBuilder should return true for match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.exists(namePath eq "Madison") shouldBe true
    }

    @Test
    fun `exists with PredicateBuilder should return false for no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.exists(namePath eq "NonExistent") shouldBe false
    }

    // EntityRepository: PredicateBuilder-based find/get/select/selectRef

    @Test
    fun `findAll with PredicateBuilder should filter entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.findAll(namePath eq "Madison")
        cities shouldHaveSize 1
    }

    @Test
    fun `find with PredicateBuilder should return matching entity`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.find(namePath eq "Madison")
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `get with PredicateBuilder should return matching entity`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.get(namePath eq "Madison")
        city.name shouldBe "Madison"
    }

    @Test
    fun `findAllRef with PredicateBuilder should return refs`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val refs = repo.findAllRef(namePath eq "Madison")
        refs shouldHaveSize 1
    }

    @Test
    fun `findRef with PredicateBuilder should return ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRef(namePath eq "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with PredicateBuilder should return ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.getRef(namePath eq "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `select with PredicateBuilder should return flow`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val count = repo.select().where(namePath eq "Madison").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `selectRef with PredicateBuilder should return flow of refs`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val count = repo.selectRef().where(namePath eq "Madison").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `delete with PredicateBuilder directly should delete matching`() {
        val repo = orm.entity(Vet::class)
        val firstNamePath = metamodel<Vet, String>(repo.model, "first_name")
        val deleted = repo.delete().where(firstNamePath eq "James").executeUpdate()
        deleted shouldBe 1
    }

    // EntityRepository: lambda predicate-based find/get/select/selectRef/count/exists/delete

    @Test
    fun `findAll with predicate should filter entities`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val result = cities.findAll(namePath eq "Madison")
        result shouldHaveSize 1
    }

    @Test
    fun `find with predicate should return matching entity`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val city = cities.find(namePath eq "Madison")
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `get with predicate should return matching entity`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val city = cities.get(namePath eq "Madison")
        city.name shouldBe "Madison"
    }

    @Test
    fun `findAllRef with predicate should return refs`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val refs = cities.findAllRef(namePath eq "Madison")
        refs shouldHaveSize 1
    }

    @Test
    fun `findRef with predicate should return ref`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val ref = cities.findRef(namePath eq "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with predicate should return ref`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val ref = cities.getRef(namePath eq "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `select with where should return flow`(): Unit = runBlocking {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val count = cities.select().where(namePath eq "Madison").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `selectRef with where should return flow of refs`(): Unit = runBlocking {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val count = cities.selectRef().where(namePath eq "Madison").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `count with predicate should count matching entities`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        val count = cities.count(namePath eq "Madison")
        count shouldBe 1
    }

    @Test
    fun `exists with predicate should return true for match`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        cities.exists(namePath eq "Madison") shouldBe true
    }

    @Test
    fun `exists with predicate should return false for no match`() {
        val cities = orm.entity(City::class)
        val namePath = metamodel<City, String>(cities.model, "name")
        cities.exists(namePath eq "NonExistent") shouldBe false
    }

    @Test
    fun `delete with block DSL where predicate should delete matching`() {
        val vets = orm.entity(Vet::class)
        val firstNamePath = metamodel<Vet, String>(vets.model, "first_name")
        val deleted = vets.delete { where(firstNamePath eq "James") }.executeUpdate()
        deleted shouldBe 1
    }

    // EntityRepository: Flow batch operations with custom chunk size

    @Test
    fun `insert flow with custom batch size should persist entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = (1..5).map { City(name = "BatchCity$it") }.asFlow()
        repo.insert(cities, 2)
        repo.count() shouldBe 11
    }

    @Test
    fun `insert flow with ignoreAutoGenerate should persist entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = flowOf(City(name = "IgnoreAutoA"), City(name = "IgnoreAutoB"))
        repo.insert(cities, false)
        repo.count() shouldBe 8
    }

    @Test
    fun `insert flow with batch size and ignoreAutoGenerate should persist entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = (1..4).map { City(name = "FullBatch$it") }.asFlow()
        repo.insert(cities, 2, false)
        repo.count() shouldBe 10
    }

    @Test
    fun `update flow with custom batch size should modify entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2, 3)).map { it.copy(name = "${it.name}-batch") }.asFlow()
        repo.update(cities, 2)
        repo.getById(1).name shouldBe "Sun Paririe-batch"
        repo.getById(2).name shouldBe "Madison-batch"
    }

    @Test
    fun `insertAndFetch flow with custom batch size should return persisted entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = (1..4).map { City(name = "FetchBatch$it") }.asFlow()
        val result = repo.insertAndFetch(cities, 2).toList()
        result shouldHaveSize 4
        result.all { it.id != 0 } shouldBe true
    }

    @Test
    fun `insertAndFetchIds flow with custom batch size should return generated ids`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = (1..4).map { City(name = "IdBatch$it") }.asFlow()
        val ids = repo.insertAndFetchIds(cities, 2).toList()
        ids shouldHaveSize 4
        ids.all { it != 0 } shouldBe true
    }

    @Test
    fun `updateAndFetch flow with custom batch size should return updated entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-uf") }.asFlow()
        val result = repo.updateAndFetch(cities, 2).toList()
        result shouldHaveSize 2
        result.all { it.name.endsWith("-uf") } shouldBe true
    }

    @Test
    fun `remove flow with custom batch size should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "DelBatchA"), City(name = "DelBatchB"), City(name = "DelBatchC")))
        val flow = inserted.asFlow()
        repo.remove(flow, 2)
        inserted.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `removeByRef flow with custom batch size should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "RefDelBatchA"), City(name = "RefDelBatchB")))
        val refs = inserted.map { repo.ref(it) }.asFlow()
        repo.removeByRef(refs, 2)
        inserted.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `upsert flow with custom batch size should throw on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(flowOf(City(name = "UpsA")), 2)
        }
    }

    @Test
    fun `upsertAndFetch flow with custom batch size should throw on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(flowOf(City(name = "UfA")), 2).toList()
        }
    }

    @Test
    fun `upsertAndFetchIds flow with custom batch size should throw on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetchIds(flowOf(City(name = "UidA")), 2).toList()
        }
    }

    @Test
    fun `upsertAndFetchIds flow should throw on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetchIds(flowOf(City(name = "UidA"))).toList()
        }
    }

    @Test
    fun `countById with custom chunk size should count matching entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countById(flowOf(1, 2, 3, 4), 2)
        count shouldBe 4
    }

    @Test
    fun `countByRef with custom chunk size should count matching entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val refs = (1..4).map { repo.ref(it) }.asFlow()
        val count = repo.countByRef(refs, 2)
        count shouldBe 4
    }

    // EntityRepository: findAllByRef with metamodel (Ref-based where)

    @Test
    fun `findAllByRef with metamodel and iterable of refs should return matching entities`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 3))
        val owners = repo.findAllByRef(cityPath, refs)
        // City 1: Betty, City 3: Eduardo
        owners shouldHaveSize 2
    }

    // EntityRepository: Scroll methods

    @Test
    fun `scrollon entity repo should return first page`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(3)
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
        window.content[0].id shouldBe 1
    }

    @Test
    fun `scroll with large size should return all entities`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(100)
        window.content shouldHaveSize 6
        window.hasNext shouldBe false
    }

    // RepositoryLookup: removeAll extension functions

    @Test
    fun `orm removeAll reified with PredicateBuilder should remove matching`() {
        val firstNamePath = metamodel<Vet, String>(orm.entity(Vet::class).model, "first_name")
        val deleted = orm.removeAll(firstNamePath eq "James")
        deleted shouldBe 1
    }

    @Test
    fun `orm removeBy with field and value should remove matching`() {
        val firstNamePath = metamodel<Vet, Int>(orm.entity(Vet::class).model, "id")
        val deleted = orm.removeBy<Vet, Int>(firstNamePath, 1)
        deleted shouldBe 1
    }

    @Test
    fun `orm removeAllBy with field and value should remove matching`() {
        val firstNamePath = metamodel<Vet, String>(orm.entity(Vet::class).model, "first_name")
        val deleted = orm.removeAllBy<Vet, String>(firstNamePath, "James")
        deleted shouldBe 1
    }

    @Test
    fun `orm removeAllBy with field and iterable values should remove matching`() {
        val firstNamePath = metamodel<Vet, String>(orm.entity(Vet::class).model, "first_name")
        val deleted = orm.removeAllBy<Vet, String>(firstNamePath, listOf("James", "Sharon"))
        deleted shouldBe 2
    }

    // ORMTemplate: withEntityCallback / withEntityCallbacks

    @Test
    fun `withEntityCallback should return new ORM template`() {
        val callback = object : st.orm.EntityCallback<City> {
            override fun beforeInsert(entity: City): City = entity
        }
        val newOrm = orm.withEntityCallback(callback)
        newOrm.shouldNotBeNull()
        // Verify the new template is functional.
        val cities = newOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    @Test
    fun `withEntityCallbacks should return new ORM template`() {
        val callback = object : st.orm.EntityCallback<City> {
            override fun beforeInsert(entity: City): City = entity
        }
        val newOrm = orm.withEntityCallbacks(listOf(callback))
        newOrm.shouldNotBeNull()
        val cities = newOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    // ORMTemplate: factory methods (DataSource.orm extension)

    @Test
    fun `ORMTemplate of Connection should create functional template`() {
        // Get the DataSource from the IntegrationConfig and create a connection-based ORM.
        val dataSource = org.springframework.boot.jdbc.DataSourceBuilder.create()
            .url("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
            .username("sa")
            .password("")
            .driverClassName("org.h2.Driver")
            .build()
        val connectionOrm = dataSource.connection.use { connection ->
            val connectionOrm = ORMTemplate.of(connection)
            connectionOrm.entity(City::class).findAll()
        }
        connectionOrm shouldHaveSize 6
    }

    // RepositoryLookup: findBy/getBy/countBy/existsBy reified extensions

    @Test
    fun `orm findBy reified should return matching entity`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.findBy<City, String>(namePath, "Madison")
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `orm getBy reified should return matching entity`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.getBy<City, String>(namePath, "Madison")
        city.name shouldBe "Madison"
    }

    @Test
    fun `orm findAllBy reified with value should return matching entities`() {
        val lastNamePath = metamodel<Owner, String>(orm.entity(Owner::class).model, "last_name")
        val owners = orm.findAllBy<Owner, String>(lastNamePath, "Davis")
        owners shouldHaveSize 2
    }

    @Test
    fun `orm findAllBy reified with iterable values should return matching entities`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val cities = orm.findAllBy<City, String>(namePath, listOf("Madison", "Windsor"))
        cities shouldHaveSize 2
    }

    @Test
    fun `orm countBy reified should count matching entities`() {
        val lastNamePath = metamodel<Owner, String>(orm.entity(Owner::class).model, "last_name")
        val count = orm.countBy<Owner, String>(lastNamePath, "Davis")
        count shouldBe 2
    }

    @Test
    fun `orm existsBy reified should return true for match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        orm.existsBy<City, String>(namePath, "Madison") shouldBe true
    }

    @Test
    fun `orm existsBy reified should return false for no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        orm.existsBy<City, String>(namePath, "NonExistent") shouldBe false
    }

    // RepositoryLookup: reified delete/select extensions

    @Test
    fun `orm remove reified QueryBuilder should return builder`() {
        val deleted = orm.entity(Vet::class).delete().where(1).executeUpdate()
        deleted shouldBe 1
    }

    @Test
    fun `orm select reified QueryBuilder should return builder`() {
        val cities = orm.select<City>().resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `orm selectCount via entity repo should return count`() {
        val count = orm.entity(City::class).selectCount().singleResult
        count shouldBe 6L
    }

    @Test
    fun `orm selectRef reified should return ref builder`(): Unit = runBlocking {
        val count = orm.selectRef<City>().resultFlow.count()
        count shouldBe 6
    }

    // EntityRepository: findBy/getBy/selectBy with Ref value

    @Test
    fun `findBy with field and ref value should return matching entity`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val owner = repo.findBy(cityPath, cityRef)
        owner.shouldNotBeNull()
        owner.firstName shouldBe "Betty"
    }

    @Test
    fun `findBy with field and ref value should return null when no match`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        val owner = repo.findBy(cityPath, cityRef)
        owner.shouldBeNull()
    }

    @Test
    fun `getBy with field and ref value should return matching entity`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val owner = repo.getBy(cityPath, cityRef)
        owner.firstName shouldBe "Betty"
    }

    @Test
    fun `getBy with field and ref value should throw when no match`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        assertThrows<NoResultException> {
            repo.getBy(cityPath, cityRef)
        }
    }

    @Test
    fun `findAllBy with field and ref value should return matching entities`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val owners = repo.findAllBy(cityPath, cityRef)
        owners shouldHaveSize 4
    }

    @Test
    fun `findRefBy with field and ref value should return matching ref`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val ref = repo.findRefBy(cityPath, cityRef)
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRefBy with field and ref value should return null when no match`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        val ref = repo.findRefBy(cityPath, cityRef)
        ref.shouldBeNull()
    }

    @Test
    fun `findAllRefBy with field and ref value should return refs`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val refs = repo.findAllRefBy(cityPath, cityRef)
        refs shouldHaveSize 4
    }

    @Test
    fun `findAllRefBy with field and iterable values should return refs`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val refs = repo.selectRef().where(namePath, IN, listOf("Madison", "Windsor")).resultList
        refs shouldHaveSize 2
    }

    // EntityRepository: Ref-based findAllRefBy/selectByRef with Iterable

    @Test
    fun `findAllRefByRef with field and iterable of ref values should return refs`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 3))
        val ownerRefs = repo.findAllRefByRef(cityPath, refs)
        ownerRefs shouldHaveSize 2
    }

    // EntityRepository: unload and ref methods

    @Test
    fun `unload should return ref with just pk`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val ref = repo.unload(city)
        ref.shouldNotBeNull()
        ref.id() shouldBe 1
    }

    @Test
    fun `ref from entity should create ref`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val ref = repo.ref(city)
        ref.shouldNotBeNull()
        ref.id() shouldBe 1
    }

    // EntityRepository: selectAll and findAllRef/selectAllRef

    @Test
    fun `findAllRef should return all entity refs`() {
        val repo = orm.entity(City::class)
        val refs = repo.findAllRef()
        refs shouldHaveSize 6
    }

    @Test
    fun `selectAllRef should return all entity refs as flow`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.selectRef().resultFlow.count()
        count shouldBe 6
    }

    // EntityRepository: deleteById and deleteByRef

    @Test
    fun `removeById should remove entity`() {
        val repo = orm.entity(Vet::class)
        repo.removeById(1)
        repo.findById(1).shouldBeNull()
    }

    @Test
    fun `removeByRef should remove entity`() {
        val repo = orm.entity(Vet::class)
        repo.removeByRef(repo.ref(1))
        repo.findById(1).shouldBeNull()
    }

    @Test
    fun `removeByRef with iterable should remove entities`() {
        val repo = orm.entity(Vet::class)
        repo.removeByRef(listOf(repo.ref(1), repo.ref(6)))
        repo.findById(1).shouldBeNull()
        repo.findById(6).shouldBeNull()
    }

    @Test
    fun `removeAll should remove all entities`() {
        val repo = orm.entity(Visit::class)
        repo.removeAll()
        repo.count() shouldBe 0
    }

    // EntityRepository: batch insert/update/delete with Iterable

    @Test
    fun `insert with iterable should persist multiple entities`() {
        val repo = orm.entity(City::class)
        repo.insert(listOf(City(name = "IterA"), City(name = "IterB")))
        repo.count() shouldBe 8
    }

    @Test
    fun `insert with iterable and ignoreAutoGenerate should persist entities`() {
        val repo = orm.entity(City::class)
        repo.insert(listOf(City(name = "IgnoreA"), City(name = "IgnoreB")), false)
        repo.count() shouldBe 8
    }

    @Test
    fun `insertAndFetchIds with iterable should return ids`() {
        val repo = orm.entity(City::class)
        val ids = repo.insertAndFetchIds(listOf(City(name = "FetchIdA"), City(name = "FetchIdB")))
        ids shouldHaveSize 2
    }

    @Test
    fun `insertAndFetch with iterable should return entities`() {
        val repo = orm.entity(City::class)
        val cities = repo.insertAndFetch(listOf(City(name = "FetchA"), City(name = "FetchB")))
        cities shouldHaveSize 2
        cities.all { it.id != 0 } shouldBe true
    }

    @Test
    fun `update with iterable should update entities`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-updated") }
        repo.update(cities)
        repo.getById(1).name shouldBe "Sun Paririe-updated"
    }

    @Test
    fun `updateAndFetch with iterable should return updated entities`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-uf") }
        val updated = repo.updateAndFetch(cities)
        updated shouldHaveSize 2
        updated.all { it.name.endsWith("-uf") } shouldBe true
    }

    @Test
    fun `remove with iterable should remove entities`() {
        val repo = orm.entity(City::class)
        val newCities = repo.insertAndFetch(listOf(City(name = "DelIterA"), City(name = "DelIterB")))
        repo.remove(newCities)
        newCities.forEach { repo.findById(it.id).shouldBeNull() }
    }

    // EntityRepository: insert/update/delete with default Flow operations

    @Test
    fun `insert flow should persist entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        repo.insert(flowOf(City(name = "FlowA"), City(name = "FlowB")))
        repo.count() shouldBe 8
    }

    @Test
    fun `update flow should update entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-flow") }.asFlow()
        repo.update(cities)
        repo.getById(1).name shouldBe "Sun Paririe-flow"
    }

    @Test
    fun `remove flow should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val newCities = repo.insertAndFetch(listOf(City(name = "FlowDelA"), City(name = "FlowDelB")))
        repo.remove(newCities.asFlow())
        newCities.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `removeByRef flow should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val newCities = repo.insertAndFetch(listOf(City(name = "RefFlowDelA"), City(name = "RefFlowDelB")))
        repo.removeByRef(newCities.map { repo.ref(it) }.asFlow())
        newCities.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `insertAndFetchIds flow should return ids`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val ids = repo.insertAndFetchIds(flowOf(City(name = "FetchIdFlowA"), City(name = "FetchIdFlowB"))).toList()
        ids shouldHaveSize 2
    }

    @Test
    fun `insertAndFetch flow should return entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.insertAndFetch(flowOf(City(name = "FetchFlowA"), City(name = "FetchFlowB"))).toList()
        cities shouldHaveSize 2
    }

    @Test
    fun `updateAndFetch flow should return updated entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-ufFlow") }.asFlow()
        val updated = repo.updateAndFetch(cities).toList()
        updated shouldHaveSize 2
    }

    // QueryBuilder: PreparedQuery operations

    @Test
    fun `prepare should return PreparedQuery for batch operations`() {
        val preparedQuery = orm.entity(City::class).select().where(1).prepare()
        preparedQuery.shouldNotBeNull()
        preparedQuery.close()
    }

    // QueryBuilder: whereAny with multiple predicates

    @Test
    fun `whereAny with combined predicates should match any`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().whereAnyBuilder {
            where(namePath, EQUALS, "Madison") or where(namePath, EQUALS, "Windsor")
        }.resultList
        cities shouldHaveSize 2
    }

    // QueryBuilder: andAny / orAny predicate builders

    @Test
    fun `andAny should combine predicates with AND-OR logic`() {
        val repo = orm.entity(Owner::class)
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.select().whereBuilder {
            where(lastNamePath, EQUALS, "Davis") andAny (
                where(firstNamePath, EQUALS, "Betty") or where(firstNamePath, EQUALS, "Harold")
                )
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `orAny should combine predicates with OR logic`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereAnyBuilder {
            where(namePath, EQUALS, "Madison") or
                where(namePath, EQUALS, "Windsor") or
                where(namePath, EQUALS, "Monona")
        }.resultList
        result shouldHaveSize 3
    }

    // ORMTemplate: of(DataSource) factory method

    @Test
    fun `ORMTemplate of DataSource should create functional template`() {
        val dataSource = org.springframework.boot.jdbc.DataSourceBuilder.create()
            .url("jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false")
            .username("sa")
            .password("")
            .driverClassName("org.h2.Driver")
            .build()
        val dsOrm = ORMTemplate.of(dataSource)
        val cities = dsOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    // EntityRepository: Flow-based batch operations WITHOUT batchSize

    @Test
    fun `insert flow without batchSize should persist entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val countBefore = repo.count()
        val cities = flowOf(City(name = "FlowCity1"), City(name = "FlowCity2"))
        repo.insert(cities)
        repo.count() shouldBe countBefore + 2
    }

    @Test
    fun `update flow without batchSize should update entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "UpdFlowNoBatch"))
        val updatedCity = City(id = city.id, name = "UpdFlowNoBatchMod")
        repo.update(flowOf(updatedCity))
        repo.getById(city.id).name shouldBe "UpdFlowNoBatchMod"
    }

    @Test
    fun `remove flow without batchSize should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DelFlowNoBatch"))
        repo.remove(flowOf(city))
        repo.findById(city.id) shouldBe null
    }

    @Test
    fun `removeByRef flow without batchSize should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DelRefFlowNoBatch"))
        repo.removeByRef(flowOf(repo.ref(city)))
        repo.findById(city.id) shouldBe null
    }

    @Test
    fun `insertAndFetch flow without batchSize should return inserted entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = flowOf(City(name = "FetchFlowNoBatch1"), City(name = "FetchFlowNoBatch2"))
        val inserted = repo.insertAndFetch(cities).toList()
        inserted shouldHaveSize 2
        inserted[0].name shouldBe "FetchFlowNoBatch1"
    }

    @Test
    fun `insertAndFetchIds flow without batchSize should return ids`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = flowOf(City(name = "IdsFlowNoBatch1"), City(name = "IdsFlowNoBatch2"))
        val ids = repo.insertAndFetchIds(cities).toList()
        ids shouldHaveSize 2
    }

    @Test
    fun `updateAndFetch flow without batchSize should return updated entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "UpdFetchFlowNoBatch"))
        val updatedCity = City(id = city.id, name = "UpdFetchFlowNoBatchMod")
        val result = repo.updateAndFetch(flowOf(updatedCity)).toList()
        result shouldHaveSize 1
        result[0].name shouldBe "UpdFetchFlowNoBatchMod"
    }

    // EntityRepository: selectAll/selectAllRef

    @Test
    fun `selectAll should return all entities as flow`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.select().resultFlow.count()
        count shouldBe 6
    }

    // EntityRepository: countBy and existsBy with non-Ref value

    @Test
    fun `countBy with field and non-ref value should count matching`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.countBy(namePath, "Madison") shouldBe 1
    }

    @Test
    fun `existsBy with field and non-ref value should return true`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.existsBy(namePath, "Madison") shouldBe true
    }

    @Test
    fun `existsBy with field and non-ref value should return false when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.existsBy(namePath, "NonExistent") shouldBe false
    }

    // EntityRepository: getRefBy with non-Ref value

    @Test
    fun `getRefBy with field and non-ref value should return matching ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.getRefBy(namePath, "Madison")
        ref.shouldNotBeNull()
    }

    // EntityRepository: findAllRefBy with iterable of Data values

    @Test
    fun `findAllRefBy with field and single non-ref value should return refs`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val refs = repo.findAllRefBy(namePath, "Madison")
        refs shouldHaveSize 1
    }

    // EntityRepository: countById/countByRef with flow and chunkSize

    @Test
    fun `countById with flow should count matching entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countById(flowOf(1, 2, 3))
        count shouldBe 3
    }

    @Test
    fun `countById with flow and chunkSize should count matching entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countById(flowOf(1, 2, 3), 2)
        count shouldBe 3
    }

    @Test
    fun `countByRef with flow should count matching entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val refs = flowOf(
            Ref.of(City::class.java, 1),
            Ref.of(City::class.java, 2),
        )
        val count = repo.countByRef(refs)
        count shouldBe 2
    }

    @Test
    fun `countByRef with flow and chunkSize should count matching entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val refs = flowOf(
            Ref.of(City::class.java, 1),
            Ref.of(City::class.java, 2),
        )
        val count = repo.countByRef(refs, 2)
        count shouldBe 2
    }

    // EntityRepository: Metamodel.Key-based findBy/getBy

    @Test
    fun `findBy with Metamodel Key should return matching entity`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val city = repo.findBy(idKey, 1)
        city.shouldNotBeNull()
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `findBy with Metamodel Key should return null for non-existing key`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val city = repo.findBy(idKey, 999)
        city.shouldBeNull()
    }

    @Test
    fun `getBy with Metamodel Key should return matching entity`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val city = repo.getBy(idKey, 1)
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `getBy with Metamodel Key should throw when no match`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        assertThrows<NoResultException> {
            repo.getBy(idKey, 999)
        }
    }

    @Test
    fun `findByRef with Metamodel Key should return matching entity`() {
        val repo = orm.entity(Owner::class)
        val cityKey = metamodel<Owner, City>(repo.model, "city_id").key()
        val owner = repo.findByRef(cityKey, Ref.of(City::class.java, 1))
        owner.shouldNotBeNull()
        owner.firstName shouldBe "Betty"
    }

    @Test
    fun `getByRef with Metamodel Key should return matching entity`() {
        val repo = orm.entity(Owner::class)
        val cityKey = metamodel<Owner, City>(repo.model, "city_id").key()
        val owner = repo.getByRef(cityKey, Ref.of(City::class.java, 1))
        owner.firstName shouldBe "Betty"
    }

    // EntityRepository: Scroll methods with Metamodel.Key

    @Test
    fun `entity scroll with Metamodel Key should return first page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `entity scrollBefore with Metamodel Key should return descending first page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
        window.content[0].id shouldBe 6
    }

    @Test
    fun `entity scrollRef with Metamodel Key should return first page of refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `entity scrollBeforeRef with Metamodel Key should return refs descending`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `entity scroll with key and PredicateBuilder should filter results`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath eq "Madison").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 1
        window.hasNext shouldBe false
    }

    @Test
    fun `entity scrollRef with key and PredicateBuilder should filter refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath eq "Madison").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 1
    }

    @Test
    fun `entity scrollBefore with key and PredicateBuilder should filter descending`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath like "M%").scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 3
    }

    // EntityRepository: Scroll with predicate

    @Test
    fun `entity scroll with key and lambda predicate should filter results`() {
        val cities = orm.entity(City::class)
        val idKey = metamodel<City, Int>(cities.model, "id").key()
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.select().where(namePath eq "Madison").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 1
        window.hasNext shouldBe false
    }

    @Test
    fun `entity scrollRef with key and lambda predicate should filter refs`() {
        val cities = orm.entity(City::class)
        val idKey = metamodel<City, Int>(cities.model, "id").key()
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.selectRef().where(namePath eq "Madison").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 1
    }

    @Test
    fun `entity scrollBefore with key and lambda predicate should filter descending`() {
        val cities = orm.entity(City::class)
        val idKey = metamodel<City, Int>(cities.model, "id").key()
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.select().where(namePath like "M%").scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBeforeRef with key and lambda predicate should filter refs`() {
        val cities = orm.entity(City::class)
        val idKey = metamodel<City, Int>(cities.model, "id").key()
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.selectRef().where(namePath like "M%").scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollAfter with key and cursor should return next page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.select().scroll(Scrollable(idKey, 3, null, null, 3, true))
        window.content shouldHaveSize 3
        window.content[0].id shouldBe 4
    }

    @Test
    fun `entity scrollAfterRef with key and cursor should return next page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable(idKey, 3, null, null, 3, true))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBefore with key and cursor should return previous page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.select().scroll(Scrollable(idKey, 4, null, null, 3, false))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBeforeRef with key and cursor should return previous page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable(idKey, 4, null, null, 3, false))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollAfter with key cursor and PredicateBuilder should filter next page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath like "M%").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollAfterRef with key cursor and PredicateBuilder should filter next page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath like "M%").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBefore with key cursor and PredicateBuilder should filter previous page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath like "M%").scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBeforeRef with key cursor and PredicateBuilder should filter previous page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath like "M%").scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content shouldHaveSize 3
    }

    // EntityRepository: Scroll with sort metamodel

    @Test
    fun `entity scroll with key sort and size should return sorted first page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.scroll(Scrollable.of(idKey, namePath, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `entity scrollBefore with key sort and size should return sorted last page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.scroll(Scrollable.of(idKey, namePath, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `entity scrollRef with key sort and size should return sorted first page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().scroll(Scrollable.of(idKey, namePath, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `entity scrollBeforeRef with key sort and size should return sorted refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().scroll(Scrollable.of(idKey, namePath, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    // EntityRepository: delete with PredicateBuilder

    @Test
    fun `delete with PredicateBuilder should delete matching entities`() {
        val repo = orm.entity(Visit::class)
        val idPath = metamodel<Visit, Int>(repo.model, "id")
        val deleted = repo.delete().where(idPath eq 1).executeUpdate()
        deleted shouldBe 1
    }

    // EntityRepository: Scrolling with PK

    @Test
    fun `entity scrollAfter should return next page`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        // Owners have ids 1-10. After id > 5: ids 6,7,8,9,10 = 5 owners.
        val window = repo.select().scroll(Scrollable(idKey, 5, null, null, 10, true))
        window.content.size shouldBe 5
    }

    @Test
    fun `entity scrollAfterRef should return next page refs`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable(idKey, 5, null, null, 10, true))
        window.content.size shouldBe 5
    }

    @Test
    fun `entity scrollBefore should return previous page`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        // Owners have ids 1-10. Before id < 6: ids 1,2,3,4,5 = 5 owners.
        val window = repo.select().scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content.size shouldBe 5
    }

    @Test
    fun `entity scrollBeforeRef should return previous page refs`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content.size shouldBe 5
    }

    @Test
    fun `entity scrollAfter with PredicateBuilder should filter`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        // After id > 1 AND last_name LIKE 'D%': owners 4 (Harold Davis) = 1 owner.
        val window = repo.select().where(lastNamePath like "D%").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content.size shouldBe 1
    }

    @Test
    fun `entity scrollAfterRef with PredicateBuilder should filter refs`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath like "D%").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content.size shouldBe 1
    }

    @Test
    fun `entity scrollBefore with PredicateBuilder should filter`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        // Before id < 10 AND last_name LIKE 'D%': owners 1 (Betty Davis), 4 (Harold Davis) = 2 owners.
        val window = repo.select().where(lastNamePath like "D%").scroll(Scrollable(idKey, 10, null, null, 10, false))
        window.content.size shouldBe 2
    }

    @Test
    fun `entity scrollBeforeRef with PredicateBuilder should filter refs`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath like "D%").scroll(Scrollable(idKey, 10, null, null, 10, false))
        window.content.size shouldBe 2
    }

    // EntityRepository: Composite scrolling with sort

    @Test
    fun `entity scrollAfter with key sort cursor should return next page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val firstPage = repo.scroll(Scrollable.of(idKey, namePath, 3))
        firstPage.content shouldHaveSize 3
        val lastItem = firstPage.content.last()
        val nextPage = repo.select().scroll(Scrollable(idKey, lastItem.id, namePath, lastItem.name, 3, true))
        nextPage.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBefore with key sort cursor should return previous page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val lastPage = repo.scroll(Scrollable.of(idKey, namePath, 3).backward())
        lastPage.content shouldHaveSize 3
        val firstItem = lastPage.content.last()
        val previousPage = repo.select().scroll(Scrollable(idKey, firstItem.id, namePath, firstItem.name, 3, false))
        previousPage.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollAfterRef with key sort cursor should return next page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val firstPage = repo.scroll(Scrollable.of(idKey, namePath, 3))
        val lastItem = firstPage.content.last()
        val nextPage = repo.selectRef().scroll(Scrollable(idKey, lastItem.id, namePath, lastItem.name, 3, true))
        nextPage.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBeforeRef with key sort cursor should return previous page refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val lastPage = repo.scroll(Scrollable.of(idKey, namePath, 3).backward())
        val firstItem = lastPage.content.last()
        val previousPage = repo.selectRef().scroll(Scrollable(idKey, firstItem.id, namePath, firstItem.name, 3, false))
        previousPage.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollAfter with composite key sort cursor should return next page`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        // After (lastName > "A" OR (lastName = "A" AND id > 1)): all 10 owners have lastName > "A".
        val window = repo.select().scroll(Scrollable(idKey, 1, lastNamePath, "A", 10, true))
        window.content.size shouldBe 10
    }

    @Test
    fun `entity scrollBefore with composite key sort cursor should return previous page`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        // Before (lastName < "Z" OR (lastName = "Z" AND id < 10)): all 10 owners have lastName < "Z".
        val window = repo.select().scroll(Scrollable(idKey, 10, lastNamePath, "Z", 10, false))
        window.content.size shouldBe 10
    }

    @Test
    fun `entity scrollAfterRef with composite key sort cursor should return next page refs`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val window = repo.selectRef().scroll(Scrollable(idKey, 1, lastNamePath, "A", 10, true))
        window.content.size shouldBe 10
    }

    @Test
    fun `entity scrollBeforeRef with composite key sort cursor should return previous page refs`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val window = repo.selectRef().scroll(Scrollable(idKey, 10, lastNamePath, "Z", 10, false))
        window.content.size shouldBe 10
    }

    // EntityRepository: Predicate-based scroll with cursor (value-based)

    @Test
    fun `entity scrollAfter with value cursor and PredicateBuilder should filter`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath like "M%").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollAfterRef with value cursor and PredicateBuilder should filter refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath like "M%").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBefore with value cursor and PredicateBuilder should filter`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath like "M%").scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content shouldHaveSize 3
    }

    @Test
    fun `entity scrollBeforeRef with value cursor and PredicateBuilder should filter refs`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath like "M%").scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content shouldHaveSize 3
    }

    // EntityRepository: Page methods

    @Test
    fun `entity page should return first page`() {
        val repo = orm.entity(City::class)
        val page = repo.page(0, 3)
        page.content shouldHaveSize 3
        page.totalCount shouldBe 6
        page.totalPages() shouldBe 2
        page.hasNext() shouldBe true
        page.hasPrevious() shouldBe false
    }

    @Test
    fun `entity page should return second page`() {
        val repo = orm.entity(City::class)
        val page = repo.page(1, 3)
        page.content shouldHaveSize 3
        page.hasNext() shouldBe false
        page.hasPrevious() shouldBe true
    }

    @Test
    fun `entity page with Pageable should support navigation`() {
        val repo = orm.entity(City::class)
        val pageable = Pageable.ofSize(2)
        val firstPage = repo.page(pageable)
        firstPage.content shouldHaveSize 2
        firstPage.totalCount shouldBe 6
        firstPage.pageNumber() shouldBe 0
        val secondPage = repo.page(firstPage.nextPageable())
        secondPage.content shouldHaveSize 2
        secondPage.pageNumber() shouldBe 1
    }

    @Test
    fun `entity page with sort order should sort results`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val pageable = Pageable.ofSize(3).sortBy(namePath)
        val page = repo.page(pageable)
        page.content shouldHaveSize 3
        val names = page.content.map { it.name }
        names shouldBe names.sorted()
    }

    @Test
    fun `entity page beyond last page should return empty content`() {
        val repo = orm.entity(City::class)
        val page = repo.page(100, 3)
        page.content shouldHaveSize 0
        page.totalCount shouldBe 6
    }

    @Test
    fun `entity pageRef should return refs`() {
        val repo = orm.entity(City::class)
        val page = repo.pageRef(0, 3)
        page.content shouldHaveSize 3
        page.totalCount shouldBe 6
    }

    @Test
    fun `entity pageRef with Pageable should return refs`() {
        val repo = orm.entity(City::class)
        val pageable = Pageable.ofSize(3)
        val page = repo.pageRef(pageable)
        page.content shouldHaveSize 3
        page.totalCount shouldBe 6
        page.hasNext() shouldBe true
    }

    // EntityRepository: Scroll navigation end-to-end tests

    @Test
    fun `entity scroll navigation forward then backward should return consistent results`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val firstWindow = repo.scroll(Scrollable.of(idKey, 3))
        firstWindow.content shouldHaveSize 3
        firstWindow.hasNext shouldBe true
        firstWindow.nextScrollable.shouldNotBeNull()
        val nextWindow = repo.scroll(firstWindow.nextScrollable!!)
        nextWindow.content shouldHaveSize 3
        nextWindow.previousScrollable.shouldNotBeNull()
        val backToFirst = repo.scroll(nextWindow.previousScrollable!!)
        backToFirst.content shouldHaveSize 3
        backToFirst.content.map { it.id }.sorted() shouldBe firstWindow.content.map { it.id }.sorted()
    }

    @Test
    fun `entity scroll cursor round trip should return same results`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val firstWindow = repo.scroll(Scrollable.of(idKey, 3))
        firstWindow.content shouldHaveSize 3
        val cursor = firstWindow.nextCursor()
        cursor.shouldNotBeNull()
        val scrollable = Scrollable.fromCursor(idKey, cursor)
        val nextFromCursor = repo.scroll(scrollable)
        val nextFromScrollable = repo.scroll(firstWindow.nextScrollable!!)
        nextFromCursor.content.map { it.id } shouldBe nextFromScrollable.content.map { it.id }
    }

    @Test
    fun `entity scroll backward then navigate further back`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val lastWindow = repo.scroll(Scrollable.of(idKey, 3).backward())
        lastWindow.content shouldHaveSize 3
        lastWindow.hasNext shouldBe true
        // Backward scroll returns results in descending order.
        val ids = lastWindow.content.map { it.id }
        ids shouldBe ids.sortedDescending()
        lastWindow.nextScrollable.shouldNotBeNull()
        val furtherBack = repo.scroll(lastWindow.nextScrollable!!)
        furtherBack.content shouldHaveSize 3
        // Further back should return earlier items (still descending).
        val furtherIds = furtherBack.content.map { it.id }
        furtherIds shouldBe furtherIds.sortedDescending()
        // All IDs in the further-back window should be less than the minimum of the first window.
        furtherIds.max() shouldBe (ids.min() - 1)
    }
}
