package st.orm.template

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Data
import st.orm.Metamodel
import st.orm.NoResultException
import st.orm.PersistenceException
import st.orm.Ref
import st.orm.repository.*
import st.orm.template.model.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class RepositoryTest(
    @Autowired val orm: ORMTemplate,
) {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // EntityRepository CRUD: insert

    @Test
    fun `insert entity should persist city`() {
        // data.sql inserts 6 cities. After inserting one more, count should be 7.
        val repo = orm.entity(City::class)
        val city = City(name = "TestCity")
        repo.insert(city)
        repo.count() shouldBe 7
    }

    @Test
    fun `insertAndFetchId should return generated id`() {
        val repo = orm.entity(City::class)
        val city = City(name = "NewCity")
        val id = repo.insertAndFetchId(city)
        id shouldNotBe 0
        repo.findById(id).shouldNotBeNull().name shouldBe "NewCity"
    }

    @Test
    fun `insertAndFetch should return persisted entity with generated id`() {
        val repo = orm.entity(City::class)
        val city = City(name = "FetchCity")
        val fetched = repo.insertAndFetch(city)
        fetched.id shouldNotBe 0
        fetched.name shouldBe "FetchCity"
    }

    // EntityRepository CRUD: update

    @Test
    fun `update entity should modify city name`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val updated = city.copy(name = "Updated")
        repo.update(updated)
        repo.getById(1).name shouldBe "Updated"
    }

    @Test
    fun `updateAndFetch should return updated entity`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(2)
        val result = repo.updateAndFetch(city.copy(name = "Changed"))
        result.name shouldBe "Changed"
        result.id shouldBe 2
    }

    // EntityRepository CRUD: upsert

    @Test
    fun `upsert should throw PersistenceException on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(City(name = "UpsertNew"))
        }
    }

    @Test
    fun `upsertAndFetch should throw PersistenceException on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(City(name = "UpsertFetch"))
        }
    }

    // EntityRepository CRUD: delete

    @Test
    fun `remove entity should remove city`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "ToDelete"))
        repo.remove(city)
        repo.findById(city.id).shouldBeNull()
    }

    @Test
    fun `removeById should remove city by primary key`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DeleteById"))
        repo.removeById(city.id)
        repo.findById(city.id).shouldBeNull()
    }

    @Test
    fun `removeByRef should remove city by ref`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DeleteByRef"))
        val ref = repo.ref(city)
        repo.removeByRef(ref)
        repo.findById(city.id).shouldBeNull()
    }

    @Test
    fun `removeAll should remove all pet types`() {
        val visitRepo = orm.entity(Visit::class)
        val petRepo = orm.entity(Pet::class)
        visitRepo.removeAll()
        petRepo.removeAll()
        val repo = orm.entity(PetType::class)
        repo.removeAll()
        repo.count() shouldBe 0
    }

    // EntityRepository: find methods

    @Test
    fun `findById should return city when exists`() {
        // data.sql: City(id=1, name='Sun Paririe').
        val repo = orm.entity(City::class)
        val city = repo.findById(1)
        city.shouldNotBeNull()
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `findById should return null when not exists`() {
        val repo = orm.entity(City::class)
        repo.findById(999).shouldBeNull()
    }

    @Test
    fun `findByRef should return city for valid ref`() {
        val repo = orm.entity(City::class)
        val ref = repo.ref(1)
        val city = repo.findByRef(ref)
        city.shouldNotBeNull()
        city.id shouldBe 1
    }

    @Test
    fun `findByRef should return null for non-existent ref`() {
        val repo = orm.entity(City::class)
        val ref = repo.ref(999)
        repo.findByRef(ref).shouldBeNull()
    }

    @Test
    fun `getById should return city when exists`() {
        // data.sql: City(id=2, name='Madison').
        val repo = orm.entity(City::class)
        val city = repo.getById(2)
        city.name shouldBe "Madison"
    }

    @Test
    fun `getById should throw NoResultException when not exists`() {
        val repo = orm.entity(City::class)
        assertThrows<NoResultException> {
            repo.getById(999)
        }
    }

    @Test
    fun `getByRef should return city when exists`() {
        // data.sql: City(id=3, name='McFarland').
        val repo = orm.entity(City::class)
        val ref = repo.ref(3)
        val city = repo.getByRef(ref)
        city.name shouldBe "McFarland"
    }

    @Test
    fun `getByRef should throw NoResultException when not exists`() {
        val repo = orm.entity(City::class)
        val ref = repo.ref(999)
        assertThrows<NoResultException> {
            repo.getByRef(ref)
        }
    }

    @Test
    fun `findAll should return all cities`() {
        // data.sql inserts 6 cities (ids 1-6).
        val repo = orm.entity(City::class)
        val cities = repo.findAll()
        cities shouldHaveSize 6
    }

    @Test
    fun `findAllById should return matching cities`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2, 3))
        cities shouldHaveSize 3
    }

    @Test
    fun `findAllById with non-existent ids should return only existing`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 999))
        cities shouldHaveSize 1
        cities.first().id shouldBe 1
    }

    @Test
    fun `findAllByRef should return matching cities`() {
        val repo = orm.entity(City::class)
        val refs = listOf(repo.ref(1), repo.ref(2))
        val cities = repo.findAllByRef(refs)
        cities shouldHaveSize 2
    }

    // EntityRepository: count and exists

    @Test
    fun `count should return total number of cities`() {
        // data.sql inserts 6 cities (ids 1-6).
        val repo = orm.entity(City::class)
        repo.count() shouldBe 6
    }

    // EntityRepository: ref and unload

    @Test
    fun `ref from id should create ref with correct id`() {
        val repo = orm.entity(City::class)
        val ref = repo.ref(1)
        ref.shouldNotBeNull()
    }

    @Test
    fun `ref from entity should create ref`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val ref = repo.ref(city)
        ref.shouldNotBeNull()
    }

    @Test
    fun `unload should create lightweight ref from entity`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val ref = repo.unload(city)
        ref.shouldNotBeNull()
    }

    // EntityRepository: DSL convenience methods

    @Test
    fun `findAll with predicate should filter cities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.findAll(namePath eq "Madison")
        cities shouldHaveSize 1
        cities.first().name shouldBe "Madison"
    }

    @Test
    fun `findAll with predicate returning empty should return empty list`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.findAll(namePath eq "NonExistent")
        cities.shouldBeEmpty()
    }

    @Test
    fun `find with predicate should return matching city`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.find(namePath eq "Windsor")
        city.shouldNotBeNull()
        city.name shouldBe "Windsor"
    }

    @Test
    fun `find with predicate should return null when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.find(namePath eq "NonExistent")
        city.shouldBeNull()
    }

    @Test
    fun `get with predicate should return matching city`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.get(namePath eq "Monona")
        city.name shouldBe "Monona"
    }

    @Test
    fun `get with predicate should throw when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NoResultException> {
            repo.get(namePath eq "NonExistent")
        }
    }

    @Test
    fun `removeAll with predicate should remove matching cities`() {
        val repo = orm.entity(City::class)
        repo.insertAndFetch(City(name = "RemovePredicateA"))
        repo.insertAndFetch(City(name = "RemovePredicateB"))
        val namePath = metamodel<City, String>(repo.model, "name")
        val removed = repo.removeAll(namePath like "RemovePredicate%")
        removed shouldBe 2
        repo.exists(namePath like "RemovePredicate%") shouldBe false
    }

    @Test
    fun `text match infix functions match their text literally`() {
        val repo = orm.entity(City::class)
        repo.insertAndFetch(City(name = "100% Literal"))
        repo.insertAndFetch(City(name = "1000 Literal"))
        val namePath = metamodel<City, String>(repo.model, "name")
        fun names(predicate: PredicateBuilder<City, City, *>) = repo.select().where(predicate).resultList.map { it.name }.sorted()
        names(namePath contains "0%") shouldBe listOf("100% Literal")
        names(namePath startsWith "100%") shouldBe listOf("100% Literal")
        names(namePath endsWith "% Literal") shouldBe listOf("100% Literal")
        names(namePath notContains "%").contains("100% Literal") shouldBe false
        names(namePath notStartsWith "100%").contains("1000 Literal") shouldBe true
        names(namePath notEndsWith "Literal").none { it.endsWith("Literal") } shouldBe true
    }

    @Test
    fun `removeAll with predicate should return zero when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val removed = repo.removeAll(namePath eq "NonExistent")
        removed shouldBe 0
    }

    // EntityRepository: batch operations with Iterable

    @Test
    fun `insert iterable should persist multiple cities`() {
        // data.sql inserts 6 cities. After inserting 3 more, count should be 9.
        val repo = orm.entity(City::class)
        val cities = listOf(City(name = "BatchA"), City(name = "BatchB"), City(name = "BatchC"))
        repo.insert(cities)
        repo.count() shouldBe 9
    }

    @Test
    fun `insertAndFetch iterable should return persisted cities`() {
        val repo = orm.entity(City::class)
        val cities = listOf(City(name = "FetchA"), City(name = "FetchB"))
        val result = repo.insertAndFetch(cities)
        result shouldHaveSize 2
        result.all { it.id != 0 } shouldBe true
        result.map { it.name } shouldContainExactlyInAnyOrder listOf("FetchA", "FetchB")
    }

    @Test
    fun `update iterable should modify multiple cities`() {
        // data.sql: City(id=1, name='Sun Paririe'), City(id=2, name='Madison').
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2))
        val updated = cities.map { it.copy(name = "${it.name}-updated") }
        repo.update(updated)
        repo.getById(1).name shouldBe "Sun Paririe-updated"
        repo.getById(2).name shouldBe "Madison-updated"
    }

    @Test
    fun `updateAndFetch iterable should return updated cities`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(3, 4))
        val updated = cities.map { it.copy(name = "${it.name}-mod") }
        val result = repo.updateAndFetch(updated)
        result shouldHaveSize 2
        result.map { it.name }.all { it.endsWith("-mod") } shouldBe true
    }

    @Test
    fun `remove iterable should remove multiple cities`() {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "DelA"), City(name = "DelB")))
        repo.remove(inserted)
        inserted.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `upsert iterable should throw PersistenceException on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(listOf(City(name = "UpsA"), City(name = "UpsB")))
        }
    }

    @Test
    fun `upsertAndFetch iterable should throw PersistenceException on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(listOf(City(name = "UfA"), City(name = "UfB")))
        }
    }

    @Test
    fun `removeByRef iterable should remove cities by refs`() {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "RefDelA"), City(name = "RefDelB")))
        val refs = inserted.map { repo.ref(it) }
        repo.removeByRef(refs)
        inserted.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `insertAndFetchIds iterable should return generated ids`() {
        val repo = orm.entity(City::class)
        val ids = repo.insertAndFetchIds(listOf(City(name = "IdsA"), City(name = "IdsB")))
        ids shouldHaveSize 2
        ids.all { it != 0 } shouldBe true
    }

    // Flow operations: selectAll

    @Test
    fun `selectAll should return flow of all cities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        repo.select().resultFlow.count() shouldBe 6
    }

    // Flow operations: selectAllRef

    @Test
    fun `selectAllRef should return flow of all city refs`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        repo.selectRef().resultFlow.count() shouldBe 6
    }

    // Flow operations: countById

    @Test
    fun `countById with non-existent ids should count only existing`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countById(flowOf(1, 999))
        count shouldBe 1
    }

    @Test
    fun `countById with empty flow should return zero`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countById(emptyFlow())
        count shouldBe 0
    }

    // Flow operations: countByRef

    @Test
    fun `countByRef should count matching entities from flow`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val refs = flowOf(repo.ref(1), repo.ref(2))
        val count = repo.countByRef(refs)
        count shouldBe 2
    }

    @Test
    fun `countByRef with empty flow should return zero`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countByRef(emptyFlow())
        count shouldBe 0
    }

    // Flow operations: batch insert

    @Test
    fun `insertAndFetch flow should return persisted entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = flowOf(City(name = "FlowFetchA"), City(name = "FlowFetchB"))
        val result = repo.insertAndFetch(cities).toList()
        result shouldHaveSize 2
        result.all { it.id != 0 } shouldBe true
    }

    @Test
    fun `insertAndFetchIds flow should return generated ids`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = flowOf(City(name = "FlowIdA"), City(name = "FlowIdB"))
        val ids = repo.insertAndFetchIds(cities).toList()
        ids shouldHaveSize 2
        ids.all { it != 0 } shouldBe true
    }

    // Flow operations: batch update

    @Test
    fun `update flow should modify entities`(): Unit = runBlocking {
        // data.sql: City(id=1, name='Sun Paririe'), City(id=2, name='Madison').
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-flow") }.asFlow()
        repo.update(cities)
        repo.getById(1).name shouldBe "Sun Paririe-flow"
        repo.getById(2).name shouldBe "Madison-flow"
    }

    @Test
    fun `updateAndFetch flow should return updated entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(3, 4)).map { it.copy(name = "${it.name}-uf") }.asFlow()
        val result = repo.updateAndFetch(cities).toList()
        result shouldHaveSize 2
        result.all { it.name.endsWith("-uf") } shouldBe true
    }

    // Flow operations: batch delete

    @Test
    fun `remove flow should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "FDelA"), City(name = "FDelB")))
        val flow = inserted.asFlow()
        repo.remove(flow)
        inserted.forEach { repo.findById(it.id).shouldBeNull() }
    }

    @Test
    fun `removeByRef flow should remove entities by ref`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "FRefDelA"), City(name = "FRefDelB")))
        val refs = inserted.map { repo.ref(it) }.asFlow()
        repo.removeByRef(refs)
        inserted.forEach { repo.findById(it.id).shouldBeNull() }
    }

    // Flow operations: batch upsert

    @Test
    fun `upsert flow should throw PersistenceException on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(flowOf(City(name = "FUpsA"), City(name = "FUpsB")))
        }
    }

    @Test
    fun `upsertAndFetch flow should throw PersistenceException on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(flowOf(City(name = "FUfA"), City(name = "FUfB"))).toList()
        }
    }

    // RepositoryLookup extension functions

    @Test
    fun `orm findAll reified should return all cities`() {
        val cities = orm.findAll<City>()
        cities shouldHaveSize 6
    }

    @Test
    fun `orm selectAll reified should return all cities as flow`(): Unit = runBlocking {
        orm.select<City>().resultFlow.count() shouldBe 6
    }

    @Test
    fun `orm insert infix should persist and return entity`() {
        val city = orm insert City(name = "InfixInsert")
        city.id shouldNotBe 0
        city.name shouldBe "InfixInsert"
    }

    @Test
    fun `orm update infix should modify entity`() {
        val city = orm.entity(City::class).getById(1)
        val updated = orm update city.copy(name = "InfixUpdate")
        updated.name shouldBe "InfixUpdate"
    }

    @Test
    fun `orm upsert infix should throw PersistenceException on H2`() {
        assertThrows<PersistenceException> {
            orm upsert City(name = "InfixUpsert")
        }
    }

    @Test
    fun `orm remove infix should remove entity`() {
        val city = orm insert City(name = "InfixDelete")
        orm remove city
        orm.entity(City::class).findById(city.id).shouldBeNull()
    }

    @Test
    fun `orm removeAll reified should remove all entities`() {
        orm.removeAll<Visit>()
        orm.removeAll<Pet>()
        orm.removeAll<PetType>()
        orm.entity(PetType::class).count() shouldBe 0
    }

    @Test
    fun `orm countAll reified should return total count`() {
        orm.countAll<City>() shouldBe 6
    }

    @Test
    fun `orm exists reified should return true when entities exist`() {
        orm.exists<City>() shouldBe true
    }

    // RepositoryLookup: DSL predicate extensions

    @Test
    fun `orm findAll with predicate should filter cities`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val cities = orm.findAll<City>(namePath eq "Madison")
        cities shouldHaveSize 1
        cities.first().name shouldBe "Madison"
    }

    @Test
    fun `orm find with predicate should return single matching city`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.find<City>(namePath eq "Windsor")
        city.shouldNotBeNull()
        city.name shouldBe "Windsor"
    }

    @Test
    fun `orm find with predicate should return null when no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.find<City>(namePath eq "Nonexistent")
        city.shouldBeNull()
    }

    @Test
    fun `orm get with predicate should return matching city`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.get<City>(namePath eq "Waunakee")
        city.name shouldBe "Waunakee"
    }

    @Test
    fun `orm get with predicate should throw when no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        assertThrows<NoResultException> {
            orm.get<City>(namePath eq "Nonexistent")
        }
    }

    @Test
    fun `orm exists with predicate should return false for no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        orm.exists<City>(namePath eq "Nonexistent") shouldBe false
    }

    @Test
    fun `orm removeAll with predicate should remove matching entities`() {
        orm.entity(City::class).insertAndFetch(City(name = "OrmRemoveA"))
        orm.entity(City::class).insertAndFetch(City(name = "OrmRemoveB"))
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val removed = orm.removeAll<City>(namePath like "OrmRemove%")
        removed shouldBe 2
        orm.exists<City>(namePath like "OrmRemove%") shouldBe false
    }

    @Test
    fun `orm removeAll with predicate should return zero when no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val removed = orm.removeAll<City>(namePath eq "Nonexistent")
        removed shouldBe 0
    }

    // RepositoryLookup: batch infix operations

    @Test
    fun `orm insert iterable should return all persisted entities`() {
        val cities = orm insert listOf(City(name = "BatchInfA"), City(name = "BatchInfB"))
        cities shouldHaveSize 2
        cities.all { it.id != 0 } shouldBe true
    }

    @Test
    fun `orm update iterable should return updated entities`() {
        val inserted = orm insert listOf(City(name = "UpdBatchA"), City(name = "UpdBatchB"))
        val updated = orm update inserted.map { it.copy(name = "${it.name}-mod") }
        updated shouldHaveSize 2
        updated.all { it.name.endsWith("-mod") } shouldBe true
    }

    @Test
    fun `orm remove iterable should remove entities`() {
        val inserted = orm insert listOf(City(name = "DelBatchA"), City(name = "DelBatchB"))
        orm remove inserted
        inserted.forEach { orm.entity(City::class).findById(it.id).shouldBeNull() }
    }

    // Versioned entity tests (Owner with @Version)

    @Test
    fun `insert and fetch versioned entity should work`() {
        val repo = orm.entity(Owner::class)
        val owner = Owner(
            firstName = "Test",
            lastName = "User",
            address = Address("123 Test St", City(1, "Sun Paririe")),
            telephone = "1234567890",
            version = 0,
        )
        val fetched = repo.insertAndFetch(owner)
        fetched.id shouldNotBe 0
        fetched.firstName shouldBe "Test"
    }

    @Test
    fun `update versioned entity should work with correct version`() {
        val repo = orm.entity(Owner::class)
        val owner = repo.getById(1)
        val updated = repo.updateAndFetch(owner.copy(firstName = "UpdatedName"))
        updated.firstName shouldBe "UpdatedName"
    }

    // Edge case: entity with null FK (Pet 13 has null owner)

    @Test
    fun `findById should handle entity with null FK`() {
        // data.sql: Pet(id=13, name='Sly', owner_id=NULL). Nullable FK should map to null.
        val repo = orm.entity(Pet::class)
        val pet = repo.findById(13)
        pet.shouldNotBeNull()
        pet.name shouldBe "Sly"
        pet.owner.shouldBeNull()
    }

    // Suspend transaction Flow tests

    @Test
    fun `selectAll within suspend transaction should work`(): Unit = runBlocking {
        transaction {
            orm.entity(City::class).select().resultFlow.count() shouldBe 6
        }
    }

    @Test
    fun `insert and remove within suspend transaction should work`(): Unit = runBlocking {
        transaction {
            val repo = orm.entity(City::class)
            val city = repo.insertAndFetch(City(name = "TxCity"))
            repo.remove(city)
            repo.findById(city.id).shouldBeNull()
        }
    }

    // findAllRef and selectAllRef

    @Test
    fun `selectAllRef should be consumable as list`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val refs = repo.selectRef().resultFlow.toList()
        refs shouldHaveSize 6
    }

    // Vet entity tests (simple entity, auto-increment)

    @Test
    fun `vet repository should return all vets`() {
        // data.sql inserts 6 vets (ids 1-6).
        val repo = orm.entity(Vet::class)
        repo.findAll() shouldHaveSize 6
    }

    @Test
    fun `vet insertAndFetch should work`() {
        val repo = orm.entity(Vet::class)
        val vet = repo.insertAndFetch(Vet(firstName = "New", lastName = "Vet"))
        vet.id shouldNotBe 0
        vet.firstName shouldBe "New"
    }

    // PetType entity tests (no auto-increment)

    @Test
    fun `petType count should return 6`() {
        // data.sql inserts 6 pet types (ids 0-5): cat, dog, lizard, snake, bird, hamster.
        val repo = orm.entity(PetType::class)
        repo.count() shouldBe 6
    }

    @Test
    fun `petType findById should work for manually assigned id`() {
        // data.sql: PetType(id=0, name='cat'). PetType uses manually assigned ids.
        val repo = orm.entity(PetType::class)
        val pt = repo.findById(0)
        pt.shouldNotBeNull()
        pt.name shouldBe "cat"
    }

    // Visit entity tests (complex entity with FK and @Version)

    @Test
    fun `visit count should return 14`() {
        // data.sql inserts exactly 14 visits (ids 1-14).
        val repo = orm.entity(Visit::class)
        repo.count() shouldBe 14
    }

    @Test
    fun `visit selectAll flow should return 14`(): Unit = runBlocking {
        // data.sql inserts exactly 14 visits (ids 1-14).
        val repo = orm.entity(Visit::class)
        repo.select().resultFlow.count() shouldBe 14
    }

    // Entity repository count and exists consistency

    @Test
    fun `entity repository count and exists are consistent`() {
        val repo = orm.entity(City::class)
        val count = repo.count()
        count shouldBe 6
        repo.exists() shouldBe true
    }

    // EntityRepository: ref-predicate methods

    @Test
    fun `findRef with predicate should return null when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRef(namePath eq "NonExistent")
        ref.shouldBeNull()
    }

    @Test
    fun `getRef with predicate should return ref for single match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.getRef(namePath eq "Monona")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with predicate should throw when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NoResultException> {
            repo.getRef(namePath eq "NonExistent")
        }
    }

    // RepositoryLookup: ref-predicate extensions

    @Test
    fun `orm findRef with predicate should return ref for matching city`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.findRef<City>(namePath eq "Windsor")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm findRef with predicate should return null when no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.findRef<City>(namePath eq "NonExistent")
        ref.shouldBeNull()
    }

    @Test
    fun `orm getRef with predicate should return ref for single match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.getRef<City>(namePath eq "Waunakee")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm getRef with predicate should throw when no match`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        assertThrows<NoResultException> {
            orm.getRef<City>(namePath eq "NonExistent")
        }
    }

    // RepositoryLookup: removeAll with predicate

    @Test
    fun `orm removeAll with predicate should remove matching cities`() {
        val city = orm insert City(name = "ToDeleteByPredicate")
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val deleted = orm.removeAll(namePath eq "ToDeleteByPredicate")
        deleted shouldBe 1
        orm.entity(City::class).findById(city.id).shouldBeNull()
    }

    // RepositoryLookup: deleteByRef

    @Test
    fun `orm removeByRef should remove city by ref`() {
        val city = orm insert City(name = "ToDeleteByOrmRef")
        val ref = orm.entity(City::class).ref(city)
        orm removeByRef ref
        orm.entity(City::class).findById(city.id).shouldBeNull()
    }

    // RepositoryLookup: selectAllRef and findAllRef

    @Test
    fun `orm selectAllRef should return flow of all city refs`(): Unit = runBlocking {
        orm.selectRef<City>().resultFlow.count() shouldBe 6
    }

    @Test
    fun `orm findAllRef should return all city refs`() {
        val refs = orm.findAllRef<City>()
        refs shouldHaveSize 6
    }

    // EntityRepository: findBy/getBy with Metamodel

    @Test
    fun `findAllBy with field and single value should return matching entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.findAllBy(namePath, "Madison")
        cities shouldHaveSize 1
        cities.first().name shouldBe "Madison"
    }

    @Test
    fun `findAllBy with field and iterable values should return matching entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.findAllBy(namePath, listOf("Madison", "Windsor"))
        cities shouldHaveSize 2
        cities.map { it.name } shouldContainExactlyInAnyOrder listOf("Madison", "Windsor")
    }

    @Test
    fun `getBy with field and value should return matching entity`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.getBy(namePath, "Waunakee")
        city.name shouldBe "Waunakee"
    }

    // EntityRepository: countBy/existsBy with Metamodel

    @Test
    fun `countBy with field and value should return zero when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.countBy(namePath, "NonExistent") shouldBe 0
    }

    // EntityRepository: findRefBy/getRefBy with Metamodel

    @Test
    fun `findRefBy with field and value should return matching ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRefBy(namePath, "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRefBy with field and value should return null when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRefBy(namePath, "NonExistent")
        ref.shouldBeNull()
    }

    @Test
    fun `getRefBy with field and value should return matching ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.getRefBy(namePath, "Windsor")
        ref.shouldNotBeNull()
    }

    // EntityRepository: deleteAllBy with Metamodel

    // EntityRepository: PredicateBuilder direct-call variants

    @Test
    fun `findRef with direct PredicateBuilder should return ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.findRef(namePath eq "Windsor")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with direct PredicateBuilder should return ref`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val ref = repo.getRef(namePath eq "Waunakee")
        ref.shouldNotBeNull()
    }

    @Test
    fun `select with direct PredicateBuilder should return flow`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath eq "Madison").resultFlow.toList()
        cities shouldHaveSize 1
        cities.first().name shouldBe "Madison"
    }

    @Test
    fun `selectRef with direct PredicateBuilder should return flow of refs`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val refs = repo.selectRef().where(namePath eq "Madison").resultFlow.toList()
        refs shouldHaveSize 1
    }

    @Test
    fun `count with direct PredicateBuilder should count matching`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        repo.count(namePath eq "Madison") shouldBe 1
    }

    @Test
    fun `delete with direct PredicateBuilder should delete matching entities`() {
        val repo = orm.entity(Vet::class)
        repo.insert(Vet(firstName = "Temp", lastName = "VetToDelete"))
        val firstNamePath = metamodel<Vet, String>(repo.model, "first_name")
        val deleted = repo.delete().where(firstNamePath eq "Temp").executeUpdate()
        deleted shouldBe 1
    }

    // EntityRepository: Ref-based Metamodel methods

    @Test
    fun `findAllByRef with field and Ref iterable should return matching entities`() {
        // data.sql: City 2 (Madison) has 4 owners, City 5 (Monona) has 2 owners (Jean, Jeff). Total: 6.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRefs = listOf(Ref.of(City::class.java, 2), Ref.of(City::class.java, 5))
        val owners = repo.findAllByRef(cityPath, cityRefs)
        owners shouldHaveSize 6
    }

    @Test
    fun `getRefBy with field and Ref should return matching ref`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val ref = repo.getRefBy(cityPath, cityRef)
        ref.shouldNotBeNull()
    }

    @Test
    fun `findAllRefByRef with field and Ref iterable should return refs`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRefs = listOf(Ref.of(City::class.java, 2), Ref.of(City::class.java, 5))
        val refs = repo.findAllRefByRef(cityPath, cityRefs)
        refs shouldHaveSize 6
    }

    @Test
    fun `findAllRefBy with field and value iterable should return refs`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val refs = repo.findAllRefBy(namePath, listOf("Madison", "Monona"))
        refs shouldHaveSize 2
    }

    @Test
    fun `removeAllBy with field and Ref should remove matching entities`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "RefDeleteCity"))
        val ownerRepo = orm.entity(Owner::class)
        val owner = ownerRepo.insertAndFetch(
            Owner(
                firstName = "RefDel",
                lastName = "Test",
                address = Address("123 St", city),
                telephone = "555",
                version = 0,
            ),
        )
        val cityPath = metamodel<Owner, City>(ownerRepo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, city.id)
        ownerRepo.removeAllBy(cityPath, cityRef) shouldBe 1
    }

    @Test
    fun `removeAllByRef with field and Ref iterable should remove matching entities`() {
        val repo = orm.entity(City::class)
        val city1 = repo.insertAndFetch(City(name = "RefDelCity1"))
        val city2 = repo.insertAndFetch(City(name = "RefDelCity2"))
        val ownerRepo = orm.entity(Owner::class)
        ownerRepo.insertAndFetch(
            Owner(
                firstName = "RD1",
                lastName = "Test",
                address = Address("1 St", city1),
                telephone = "555",
                version = 0,
            ),
        )
        ownerRepo.insertAndFetch(
            Owner(
                firstName = "RD2",
                lastName = "Test",
                address = Address("2 St", city2),
                telephone = "555",
                version = 0,
            ),
        )
        val cityPath = metamodel<Owner, City>(ownerRepo.model, "city_id")
        val cityRefs = listOf(Ref.of(City::class.java, city1.id), Ref.of(City::class.java, city2.id))
        ownerRepo.removeAllByRef(cityPath, cityRefs) shouldBe 2
    }
}
