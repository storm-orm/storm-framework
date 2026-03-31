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
import javax.sql.DataSource

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class ORMTemplateTest(
    @Autowired val orm: ORMTemplate,
    @Autowired val dataSource: DataSource,
) {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // ORMTemplate Companion: factory methods

    @Test
    fun `ORMTemplate of DataSource should create functional template`() {
        val dsOrm = ORMTemplate.of(dataSource)
        val cities = dsOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    @Test
    fun `ORMTemplate of DataSource with decorator should create functional template`() {
        val dsOrm = ORMTemplate.of(dataSource) { it }
        val cities = dsOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    @Test
    fun `ORMTemplate of Connection should create functional template`() {
        dataSource.connection.use { connection ->
            val connectionOrm = ORMTemplate.of(connection)
            val cities = connectionOrm.entity(City::class).findAll()
            cities shouldHaveSize 6
        }
    }

    @Test
    fun `ORMTemplate of Connection with decorator should create functional template`() {
        dataSource.connection.use { connection ->
            val connectionOrm = ORMTemplate.of(connection) { it }
            val cities = connectionOrm.entity(City::class).findAll()
            cities shouldHaveSize 6
        }
    }

    // ORMTemplate Kotlin extension properties

    @Test
    fun `DataSource orm extension should create functional template`() {
        val dsOrm = dataSource.orm
        val cities = dsOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    @Test
    fun `Connection orm extension should create functional template`() {
        dataSource.connection.use { connection ->
            val connectionOrm = connection.orm
            val cities = connectionOrm.entity(City::class).findAll()
            cities shouldHaveSize 6
        }
    }

    @Test
    fun `DataSource orm extension with decorator should create functional template`() {
        val dsOrm = dataSource.orm { it }
        val cities = dsOrm.entity(City::class).findAll()
        cities shouldHaveSize 6
    }

    @Test
    fun `Connection orm extension with decorator should create functional template`() {
        dataSource.connection.use { connection ->
            val connectionOrm = connection.orm { it }
            val cities = connectionOrm.entity(City::class).findAll()
            cities shouldHaveSize 6
        }
    }

    // ORMTemplate: validateSchema

    @Test
    fun `validateSchema should return empty for valid types`() {
        val errors = orm.validateSchema(City::class)
        errors shouldHaveSize 0
    }

    @Test
    fun `validateSchema should return errors for invalid types`() {
        @DbTable("nonexistent_table")
        data class NonExistentEntity(@PK val id: Int = 0) : Entity<Int>
        val errors = orm.validateSchema(NonExistentEntity::class)
        errors.size shouldBe 1
    }

    @Test
    fun `validateSchemaOrThrow should not throw for valid types`() {
        orm.validateSchemaOrThrow(City::class)
    }

    @Test
    fun `validateSchemaOrThrow should throw for invalid types`() {
        @DbTable("nonexistent_table")
        data class NonExistentEntity(@PK val id: Int = 0) : Entity<Int>
        assertThrows<PersistenceException> {
            orm.validateSchemaOrThrow(NonExistentEntity::class)
        }
    }

    // EntityRepository: Metamodel.Key-based find/get

    @Test
    fun `findBy with Metamodel Key should return matching entity`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val city = repo.findBy(key, 2)
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `getBy with Metamodel Key should return matching entity`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val city = repo.getBy(key, 2)
        city.name shouldBe "Madison"
    }

    @Test
    fun `findBy with Metamodel and Ref value should return matching entity`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val owner = repo.findBy(cityPath, Ref.of(City::class.java, 1))
        owner.shouldNotBeNull()
        owner.firstName shouldBe "Betty"
    }

    @Test
    fun `getBy with Metamodel and Ref value should return matching entity`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val owner = repo.getBy(cityPath, Ref.of(City::class.java, 1))
        owner.firstName shouldBe "Betty"
    }

    // EntityRepository: Scroll methods

    @Test
    fun `scroll with key should return first page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.scroll(Scrollable.of(key, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollBefore with key should return last page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.scroll(Scrollable.of(key, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollRef with key should return first page of refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.selectRef().scroll(Scrollable.of(key, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollBeforeRef with key should return last page of refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.selectRef().scroll(Scrollable.of(key, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scroll with key and PredicateBuilder should return filtered first page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath eq "Madison").scroll(Scrollable.of(key, 10))
        window.content shouldHaveSize 1
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollRef with key and PredicateBuilder should return filtered refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath eq "Madison").scroll(Scrollable.of(key, 10))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollAfter with key and cursor should return next page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.select().scroll(Scrollable(key, 3, null, null, 3, true))
        window.content shouldHaveSize 3
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollAfterRef with key and cursor should return next page of refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.selectRef().scroll(Scrollable(key, 3, null, null, 3, true))
        window.content shouldHaveSize 3
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollBefore with key and cursor should return previous page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.select().scroll(Scrollable(key, 4, null, null, 3, false))
        window.content shouldHaveSize 3
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollBeforeRef with key and cursor should return previous page of refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.selectRef().scroll(Scrollable(key, 4, null, null, 3, false))
        window.content shouldHaveSize 3
    }

    @Test
    fun `scrollAfter with key cursor and PredicateBuilder should return filtered next page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath eq "Madison").scroll(Scrollable(key, 1, null, null, 10, true))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollAfterRef with key cursor and PredicateBuilder should return filtered next refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath eq "Madison").scroll(Scrollable(key, 1, null, null, 10, true))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollBefore with key cursor and PredicateBuilder should return filtered prev page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.select().where(namePath eq "Madison").scroll(Scrollable(key, 6, null, null, 10, false))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollBeforeRef with key cursor and PredicateBuilder should return filtered prev refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().where(namePath eq "Madison").scroll(Scrollable(key, 6, null, null, 10, false))
        window.content shouldHaveSize 1
    }

    // EntityRepository: Scroll with predicate

    @Test
    fun `scroll with key and lambda predicate should return filtered first page`() {
        val cities = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.select().where(namePath eq "Madison").scroll(Scrollable.of(key, 10))
        window.content shouldHaveSize 1
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollRef with key and lambda predicate should return filtered refs`() {
        val cities = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.selectRef().where(namePath eq "Madison").scroll(Scrollable.of(key, 10))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollAfter with key cursor and lambda predicate should return filtered next page`() {
        val cities = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.select().where(namePath eq "Madison").scroll(Scrollable(key, 1, null, null, 10, true))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollAfterRef with key cursor and lambda predicate should return filtered next refs`() {
        val cities = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.selectRef().where(namePath eq "Madison").scroll(Scrollable(key, 1, null, null, 10, true))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollBefore with key cursor and lambda predicate should return filtered prev page`() {
        val cities = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.select().where(namePath eq "Madison").scroll(Scrollable(key, 6, null, null, 10, false))
        window.content shouldHaveSize 1
    }

    @Test
    fun `scrollBeforeRef with key cursor and lambda predicate should return filtered prev refs`() {
        val cities = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(cities.model, "name")
        val window = cities.selectRef().where(namePath eq "Madison").scroll(Scrollable(key, 6, null, null, 10, false))
        window.content shouldHaveSize 1
    }

    // EntityRepository: Scroll with key and sort metamodel

    @Test
    fun `scroll with key and sort should return sorted first page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.scroll(Scrollable.of(key, namePath, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollBefore with key and sort should return sorted last page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.scroll(Scrollable.of(key, namePath, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollRef with key and sort should return first page of refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().scroll(Scrollable.of(key, namePath, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollBeforeRef with key and sort should return last page of refs`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val namePath = metamodel<City, String>(repo.model, "name")
        val window = repo.selectRef().scroll(Scrollable.of(key, namePath, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    // EntityRepository: select with custom type

    @Test
    fun `selectCount should return count result`() {
        val repo = orm.entity(City::class)
        val count = repo.selectCount().singleResult
        count shouldBe 6
    }

    @Test
    fun `selectRef with refType should return typed refs`() {
        val repo = orm.entity(Pet::class)
        val refs = repo.selectRef(PetType::class).resultList
        refs shouldHaveSize 13
    }

    @Test
    fun `select with TemplateBuilder should return typed results`() {
        val repo = orm.entity(City::class)
        val cities = repo.select(City::class) { t(City::class) }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `select with TemplateString should return typed results`() {
        val repo = orm.entity(City::class)
        val templateString = TemplateString.wrap(City::class)
        val cities = repo.select(City::class, templateString).resultList
        cities shouldHaveSize 6
    }

    // EntityRepository: Flow-based batch operations (default batch size)

    @Test
    fun `insert flow without batch size should persist entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = flowOf(City(name = "FlowA"), City(name = "FlowB"))
        repo.insert(cities)
        repo.count() shouldBe 8
    }

    @Test
    fun `update flow without batch size should modify entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-flow") }.asFlow()
        repo.update(cities)
        repo.getById(1).name shouldBe "Sun Paririe-flow"
    }

    @Test
    fun `insertAndFetch flow without batch size should return persisted`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val result = repo.insertAndFetch(flowOf(City(name = "FFA"), City(name = "FFB"))).toList()
        result shouldHaveSize 2
        result.all { it.id != 0 } shouldBe true
    }

    @Test
    fun `insertAndFetchIds flow without batch size should return ids`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val ids = repo.insertAndFetchIds(flowOf(City(name = "IFA"), City(name = "IFB"))).toList()
        ids shouldHaveSize 2
        ids.all { it != 0 } shouldBe true
    }

    @Test
    fun `updateAndFetch flow without batch size should return updated`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-uf2") }.asFlow()
        val result = repo.updateAndFetch(cities).toList()
        result shouldHaveSize 2
        result.all { it.name.endsWith("-uf2") } shouldBe true
    }

    @Test
    fun `remove flow without batch size should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "DelA"), City(name = "DelB")))
        repo.remove(inserted.asFlow())
        inserted.forEach { repo.findById(it.id) shouldBe null }
    }

    @Test
    fun `removeByRef flow without batch size should remove entities`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "RefDelA"), City(name = "RefDelB")))
        val refs = inserted.map { repo.ref(it) }.asFlow()
        repo.removeByRef(refs)
        inserted.forEach { repo.findById(it.id) shouldBe null }
    }

    @Test
    fun `upsert flow without batch size should throw on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(flowOf(City(name = "UpsA")))
        }
    }

    @Test
    fun `upsertAndFetch flow without batch size should throw on H2`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(flowOf(City(name = "UfA"))).toList()
        }
    }

    // EntityRepository: selectAll, selectById, selectByRef (default chunk)

    @Test
    fun `selectAll should return all entities as flow`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.select().resultFlow.count()
        count shouldBe 6
    }

    @Test
    fun `countById flow without chunk size should count matching`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val count = repo.countById(flowOf(1, 2, 3))
        count shouldBe 3
    }

    @Test
    fun `countByRef flow without chunk size should count matching`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val refs = (1..3).map { repo.ref(it) }.asFlow()
        val count = repo.countByRef(refs)
        count shouldBe 3
    }

    // EntityRepository: ref and unload

    @Test
    fun `ref with id should return valid ref`() {
        val repo = orm.entity(City::class)
        val ref = repo.ref(1)
        ref.id() shouldBe 1
    }

    @Test
    fun `ref with entity should return loaded ref`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val ref = repo.ref(city)
        ref.id() shouldBe 1
    }

    @Test
    fun `unload should return unloaded ref`() {
        val repo = orm.entity(City::class)
        val city = repo.getById(1)
        val ref = repo.unload(city)
        ref.id() shouldBe 1
    }

    // EntityRepository: existsById, existsByRef

    @Test
    fun `existsById should return true for existing entity`() {
        val repo = orm.entity(City::class)
        repo.existsById(1) shouldBe true
    }

    @Test
    fun `existsById should return false for non-existing entity`() {
        val repo = orm.entity(City::class)
        repo.existsById(999) shouldBe false
    }

    @Test
    fun `existsByRef should return true for existing entity`() {
        val repo = orm.entity(City::class)
        repo.existsByRef(repo.ref(1)) shouldBe true
    }

    @Test
    fun `existsByRef should return false for non-existing entity`() {
        val repo = orm.entity(City::class)
        repo.existsByRef(repo.ref(999)) shouldBe false
    }

    @Test
    fun `exists should return true when entities exist`() {
        val repo = orm.entity(City::class)
        repo.exists() shouldBe true
    }

    // EntityRepository: findByRef, getByRef

    @Test
    fun `findByRef should return entity for valid ref`() {
        val repo = orm.entity(City::class)
        val city = repo.findByRef(repo.ref(1))
        city.shouldNotBeNull()
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `getByRef should return entity for valid ref`() {
        val repo = orm.entity(City::class)
        val city = repo.getByRef(repo.ref(1))
        city.name shouldBe "Sun Paririe"
    }

    // EntityRepository: Iterable batch operations

    @Test
    fun `insert iterable should persist entities`() {
        val repo = orm.entity(City::class)
        repo.insert(listOf(City(name = "IterA"), City(name = "IterB")))
        repo.count() shouldBe 8
    }

    @Test
    fun `insert iterable with ignoreAutoGenerate should persist entities`() {
        val repo = orm.entity(City::class)
        repo.insert(listOf(City(name = "IgnA"), City(name = "IgnB")), false)
        repo.count() shouldBe 8
    }

    @Test
    fun `insertAndFetchIds iterable should return generated ids`() {
        val repo = orm.entity(City::class)
        val ids = repo.insertAndFetchIds(listOf(City(name = "FetchIdA"), City(name = "FetchIdB")))
        ids shouldHaveSize 2
        ids.all { it != 0 } shouldBe true
    }

    @Test
    fun `update iterable should modify entities`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-upd") }
        repo.update(cities)
        repo.getById(1).name shouldBe "Sun Paririe-upd"
    }

    @Test
    fun `updateAndFetch iterable should return updated entities`() {
        val repo = orm.entity(City::class)
        val cities = repo.findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-updf") }
        val result = repo.updateAndFetch(cities)
        result shouldHaveSize 2
        result.all { it.name.endsWith("-updf") } shouldBe true
    }

    @Test
    fun `upsert iterable should throw on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(listOf(City(name = "UpsIterA")))
        }
    }

    @Test
    fun `upsertAndFetchIds iterable should throw on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetchIds(listOf(City(name = "UpsIdA")))
        }
    }

    @Test
    fun `upsertAndFetch iterable should throw on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(listOf(City(name = "UpsFA")))
        }
    }

    @Test
    fun `remove iterable should remove entities`() {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "DItrA"), City(name = "DItrB")))
        repo.remove(inserted)
        inserted.forEach { repo.findById(it.id) shouldBe null }
    }

    @Test
    fun `removeByRef iterable should remove entities`() {
        val repo = orm.entity(City::class)
        val inserted = repo.insertAndFetch(listOf(City(name = "DRItrA"), City(name = "DRItrB")))
        val refs = inserted.map { repo.ref(it) }
        repo.removeByRef(refs)
        inserted.forEach { repo.findById(it.id) shouldBe null }
    }

    // EntityRepository: single entity operations

    @Test
    fun `insert single entity should persist`() {
        val repo = orm.entity(City::class)
        repo.insert(City(name = "SingleInsert"))
        repo.count() shouldBe 7
    }

    @Test
    fun `insert single entity with ignoreAutoGenerate should persist`() {
        val repo = orm.entity(City::class)
        repo.insert(City(name = "SingleIgn"), false)
        repo.count() shouldBe 7
    }

    @Test
    fun `insertAndFetchId should return generated id`() {
        val repo = orm.entity(City::class)
        val id = repo.insertAndFetchId(City(name = "FetchIdSingle"))
        (id > 0) shouldBe true
    }

    @Test
    fun `upsert single should throw on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsert(City(name = "UpsSingle"))
        }
    }

    @Test
    fun `upsertAndFetchId single should throw on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetchId(City(name = "UpsIdSingle"))
        }
    }

    @Test
    fun `upsertAndFetch single should throw on H2`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.upsertAndFetch(City(name = "UpsFSingle"))
        }
    }

    @Test
    fun `remove single entity should remove`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DelSingle"))
        repo.remove(city)
        repo.findById(city.id) shouldBe null
    }

    @Test
    fun `removeById should remove entity`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DelById"))
        repo.removeById(city.id)
        repo.findById(city.id) shouldBe null
    }

    @Test
    fun `removeByRef should remove entity`() {
        val repo = orm.entity(City::class)
        val city = repo.insertAndFetch(City(name = "DelByRef"))
        repo.removeByRef(repo.ref(city))
        repo.findById(city.id) shouldBe null
    }

    @Test
    fun `removeById should remove specific entity`() {
        // Vet 1 (James Carter) has no vet_specialty entries, safe to delete.
        val repo = orm.entity(Vet::class)
        val countBefore = repo.count()
        repo.removeById(1)
        repo.count() shouldBe countBefore - 1
    }

    // RepositoryLookup: reified insert/update/upsert/delete extensions

    @Test
    fun `orm insert reified should persist and return entity`() {
        val city: City = orm.insert(City(name = "ReifiedInsert"))
        city.id shouldBe 7
    }

    @Test
    fun `orm insert reified iterable should persist and return entities`() {
        val cities: List<City> = orm.insert(listOf(City(name = "ReifiedA"), City(name = "ReifiedB")))
        cities shouldHaveSize 2
    }

    @Test
    fun `orm update reified should modify and return entity`() {
        val city = orm.entity(City::class).getById(1)
        val updated: City = orm.update(city.copy(name = "UpdatedReified"))
        updated.name shouldBe "UpdatedReified"
    }

    @Test
    fun `orm update reified iterable should modify and return entities`() {
        val cities = orm.entity(City::class).findAllById(listOf(1, 2)).map { it.copy(name = "${it.name}-reifUpd") }
        val updated: List<City> = orm.update(cities)
        updated shouldHaveSize 2
    }

    @Test
    fun `orm remove reified entity should remove`() {
        val city = orm.entity(City::class).insertAndFetch(City(name = "ReifDel"))
        orm.remove(city)
        orm.entity(City::class).findById(city.id) shouldBe null
    }

    @Test
    fun `orm remove reified iterable should remove`() {
        val cities = orm.entity(City::class).insertAndFetch(listOf(City(name = "RDItA"), City(name = "RDItB")))
        orm.remove(cities)
        cities.forEach { orm.entity(City::class).findById(it.id) shouldBe null }
    }

    @Test
    fun `orm removeByRef reified should remove`() {
        val city = orm.entity(City::class).insertAndFetch(City(name = "ReifDelRef"))
        orm.removeByRef(Ref.of(City::class.java, city.id))
        orm.entity(City::class).findById(city.id) shouldBe null
    }

    @Test
    fun `orm removeByRef reified iterable should remove`() {
        val cities = orm.entity(City::class).insertAndFetch(listOf(City(name = "RDRItA"), City(name = "RDRItB")))
        val refs = cities.map { Ref.of(City::class.java, it.id) }
        orm.removeByRef(refs)
        cities.forEach { orm.entity(City::class).findById(it.id) shouldBe null }
    }

    // RepositoryLookup: reified findRefBy/getRefBy/findAllRefBy/selectRefBy

    @Test
    fun `orm findRefBy reified entity should return ref`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.findRefBy<City, String>(namePath, "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm getRefBy reified entity should return ref`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.getRefBy<City, String>(namePath, "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm findAllRefBy reified entity should return refs`() {
        val lastNamePath = metamodel<Owner, String>(orm.entity(Owner::class).model, "last_name")
        val refs = orm.findAllRefBy<Owner, String>(lastNamePath, "Davis")
        refs shouldHaveSize 2
    }

    // RepositoryLookup: reified Ref-based findBy/getBy/findAllBy/selectBy/deleteBy

    @Test
    fun `orm findBy reified entity with ref should return matching`() {
        val cityPath = metamodel<Owner, City>(orm.entity(Owner::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val owner = orm.findBy<Owner, City>(cityPath, cityRef)
        owner.shouldNotBeNull()
        owner.firstName shouldBe "Betty"
    }

    @Test
    fun `orm getBy reified entity with ref should return matching`() {
        val cityPath = metamodel<Owner, City>(orm.entity(Owner::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val owner = orm.getBy<Owner, City>(cityPath, cityRef)
        owner.firstName shouldBe "Betty"
    }

    @Test
    fun `orm findAllBy reified entity with ref should return matching`() {
        val cityPath = metamodel<Owner, City>(orm.entity(Owner::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val owners = orm.findAllBy<Owner, City>(cityPath, cityRef)
        owners shouldHaveSize 4
    }

    @Test
    fun `orm countBy reified entity with ref should count matching`() {
        val cityPath = metamodel<Owner, City>(orm.entity(Owner::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val count = orm.countBy<Owner, City>(cityPath, cityRef)
        count shouldBe 4
    }

    @Test
    fun `orm existsBy reified entity with ref should return true`() {
        val cityPath = metamodel<Owner, City>(orm.entity(Owner::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        orm.existsBy<Owner, City>(cityPath, cityRef) shouldBe true
    }

    @Test
    fun `orm deleteBy reified entity should delete matching`() {
        val repo = orm.entity(Vet::class)
        val firstNamePath = metamodel<Vet, String>(repo.model, "first_name")
        val deleted = repo.delete().where(firstNamePath, EQUALS, "James").executeUpdate()
        deleted shouldBe 1
    }

    @Test
    fun `orm removeAllByRef reified with field and refs should remove matching`() {
        val cityRepo = orm.entity(City::class)
        val testCity = cityRepo.insertAndFetch(City(name = "ReifRefDel"))
        val ownerRepo = orm.entity(Owner::class)
        ownerRepo.insertAndFetch(
            Owner(firstName = "Test", lastName = "ReifRefDel", address = Address("1", testCity), telephone = "111", version = 0),
        )
        val cityPath = metamodel<Owner, City>(ownerRepo.model, "city_id")
        val deleted = orm.removeAllByRef<Owner, City>(cityPath, listOf(Ref.of(City::class.java, testCity.id)))
        deleted shouldBe 1
    }

    // RepositoryLookup: reified count/exists with predicate

    @Test
    fun `orm count reified with PredicateBuilder should count matching`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val count = orm.count<City>(namePath eq "Madison")
        count shouldBe 1
    }

    @Test
    fun `orm exists reified with PredicateBuilder should return true`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        orm.exists<City>(namePath eq "Madison") shouldBe true
    }

    // RepositoryLookup: reified find/get/findAll/findRef/findAllRef with predicate

    @Test
    fun `orm find reified with PredicateBuilder should find entity`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.find<City>(namePath eq "Madison")
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `orm get reified with PredicateBuilder should get entity`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.get<City>(namePath eq "Madison")
        city.name shouldBe "Madison"
    }

    @Test
    fun `orm findAll reified with PredicateBuilder should find entities`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val cities = orm.findAll<City>(namePath eq "Madison")
        cities shouldHaveSize 1
    }

    @Test
    fun `orm findRef reified with PredicateBuilder should find ref`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.findRef<City>(namePath eq "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm getRef reified with PredicateBuilder should get ref`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val ref = orm.getRef<City>(namePath eq "Madison")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm findAllRef reified with PredicateBuilder should find refs`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val refs = orm.findAllRef<City>(namePath eq "Madison")
        refs shouldHaveSize 1
    }

    // RepositoryLookup: reified select/selectRef with PredicateBuilder

    @Test
    fun `orm select reified with PredicateBuilder should return flow`(): Unit = runBlocking {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val count = orm.select<City>().where(namePath eq "Madison").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `orm selectRef reified with PredicateBuilder should return flow`(): Unit = runBlocking {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val count = orm.selectRef<City>().where(namePath eq "Madison").resultFlow.count()
        count shouldBe 1
    }

    // RepositoryLookup: reified methods with predicate

    @Test
    fun `orm count with predicate should count matching`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val count = orm.count(namePath eq "Madison")
        count shouldBe 1
    }

    @Test
    fun `orm exists with predicate should return true`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        orm.exists(namePath eq "Madison") shouldBe true
    }

    @Test
    fun `orm find with predicate should find entity`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.find(namePath eq "Madison")
        city.shouldNotBeNull()
        city.name shouldBe "Madison"
    }

    @Test
    fun `orm get with predicate should get entity`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val city = orm.get(namePath eq "Madison")
        city.name shouldBe "Madison"
    }

    @Test
    fun `orm findAll with predicate should find entities`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val cities = orm.findAll(namePath eq "Madison")
        cities shouldHaveSize 1
    }

    @Test
    fun `orm select with predicate should return results`() {
        val namePath = metamodel<City, String>(orm.entity(City::class).model, "name")
        val count = orm.select(namePath eq "Madison").resultCount
        count shouldBe 1
    }

    @Test
    fun `orm removeAll with predicate should remove matching`() {
        val firstNamePath = metamodel<Vet, String>(orm.entity(Vet::class).model, "first_name")
        val deleted = orm.removeAll(firstNamePath eq "James")
        deleted shouldBe 1
    }

    // PreparedQuery: getSingleResult, getResultList, getResultStream with KClass

    @Test
    fun `prepared query getSingleResult with KClass should return typed result`() {
        val prepared = orm.entity(City::class).select().where(1).prepare()
        prepared.use { query ->
            val city = query.getSingleResult(City::class)
            city.name shouldBe "Sun Paririe"
        }
    }

    @Test
    fun `prepared query getResultList with KClass should return typed results`() {
        val prepared = orm.entity(City::class).select().prepare()
        prepared.use { query ->
            val cities = query.getResultList(City::class)
            cities shouldHaveSize 6
        }
    }

    @Test
    fun `prepared query getResultStream with KClass should return typed stream`() {
        val prepared = orm.entity(City::class).select().prepare()
        prepared.use { query ->
            val count = query.getResultStream(City::class).count()
            count shouldBe 6L
        }
    }

    // QueryBuilder: scrollAfter/scrollBefore with cursor on QueryBuilder level

    @Test
    fun `queryBuilder scrollAfter with key and cursor should return next page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.select().scroll(Scrollable(key, 3, null, null, 3, true))
        window.content shouldHaveSize 3
        window.hasNext shouldBe false
    }

    @Test
    fun `queryBuilder scrollBefore with key and cursor should return previous page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.select().scroll(Scrollable(key, 4, null, null, 3, false))
        window.content shouldHaveSize 3
    }

    @Test
    fun `queryBuilder scrollBefore with key no cursor should return last page`() {
        val repo = orm.entity(City::class)
        val idMetamodel = Metamodel.of<City, Int>(City::class.java, "id")
        val key = Metamodel.key(idMetamodel)
        val window = repo.select().scroll(Scrollable.of(key, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    // QueryBuilder: typed with pkType

    @Test
    fun `queryBuilder typed with pkType should work`() {
        val repo = orm.entity(City::class)
        val typedBuilder = repo.select().typed(Int::class)
        val cities = typedBuilder.resultList
        cities shouldHaveSize 6
    }

    // QueryBuilder: unsafe should allow delete without WHERE

    @Test
    fun `queryBuilder unsafe should allow delete without WHERE clause`() {
        // Use Visit table since it has no FK references from other tables.
        val repo = orm.entity(Visit::class)
        val count = repo.delete().unsafe().executeUpdate()
        count shouldBe 14
    }

    // QueryBuilder: limit and offset

    @Test
    fun `queryBuilder limit should restrict results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy(idPath).limit(3).resultList
        cities shouldHaveSize 3
    }

    @Test
    fun `queryBuilder offset should skip results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy(idPath).offset(2).limit(3).resultList
        cities shouldHaveSize 3
        cities[0].id shouldBe 3
    }

    // QueryBuilder: distinct

    @Test
    fun `queryBuilder distinct should return unique results`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().distinct().resultList
        cities shouldHaveSize 6
    }

    // Query: getResultList, getResultStream, getSingleResult with KClass

    @Test
    fun `query getResultList with KClass should return results`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        val cities = query.getResultList(City::class)
        cities shouldHaveSize 6
    }

    @Test
    fun `query getResultStream with KClass should return stream`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        val count = query.getResultStream(City::class).use { it.count() }
        count shouldBe 6L
    }

    @Test
    fun `query getSingleResult with KClass should return single result`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)} WHERE ${t(Templates.alias(City::class))}.id = ${t(1)}" }
        val city = query.getSingleResult(City::class)
        city.name shouldBe "Sun Paririe"
    }

    // QueryTemplate: selectFrom variants

    @Test
    fun `selectFrom with single type should return query builder`() {
        val builder = orm.selectFrom(City::class)
        val cities = builder.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `selectFrom with fromType and selectType should return query builder`() {
        val builder = orm.selectFrom(City::class, City::class)
        val cities = builder.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `selectFrom with fromType selectType and TemplateBuilder should return query builder`() {
        val builder = orm.selectFrom(City::class, Long::class) { "COUNT(*)" }
        val count = builder.singleResult
        count shouldBe 6L
    }

    // QueryTemplate/SubqueryTemplate: subquery variants

    @Test
    fun `subquery with single type should be usable in whereExists`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereExists { subquery(Owner::class).where(TemplateString.raw("1 = 1")) }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `subquery with fromType and selectType should work`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereExists { subquery(Owner::class, Owner::class).where(TemplateString.raw("1 = 1")) }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `subquery with fromType and TemplateBuilder should work`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereExists { subquery(Owner::class) { "1" }.where(TemplateString.raw("1 = 1")) }.resultList
        cities shouldHaveSize 6
    }

    // QueryTemplate: model

    @Test
    fun `model with type should return model`() {
        val model = orm.model(City::class)
        model.shouldNotBeNull()
        model.columns.shouldNotBeNull()
    }

    @Test
    fun `model with type and requirePrimaryKey should return model`() {
        val model = orm.model(City::class, true)
        model.shouldNotBeNull()
    }

    // QueryTemplate: query with TemplateBuilder

    @Test
    fun `query with TemplateBuilder should return results`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        val cities = query.getResultList(City::class)
        cities shouldHaveSize 6
    }

    // Query: getOptionalResult, getRefList, getRefStream, getRefFlow

    @Test
    fun `query getOptionalResult with KClass should return result`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)} WHERE ${t(Templates.alias(City::class))}.id = ${t(1)}" }
        val city = query.getOptionalResult(City::class)
        city.shouldNotBeNull()
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `query getOptionalResult with KClass should return null for no match`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)} WHERE ${t(Templates.alias(City::class))}.id = ${t(999)}" }
        val city = query.getOptionalResult(City::class)
        city.shouldBeNull()
    }

    @Test
    fun `query getRefList should return refs`() {
        val query = orm.query("SELECT id FROM city")
        val refs = query.getRefList(City::class, java.lang.Integer::class)
        refs shouldHaveSize 6
    }

    @Test
    fun `query getRefFlow should return refs as flow`(): Unit = runBlocking {
        val query = orm.query("SELECT id FROM city")
        val count = query.getRefFlow(City::class, java.lang.Integer::class).count()
        count shouldBe 6
    }

    @Test
    fun `query getResultFlow with KClass should return flow`(): Unit = runBlocking {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        val count = query.getResultFlow(City::class).count()
        count shouldBe 6
    }

    @Test
    fun `query resultFlow should return flow of arrays`(): Unit = runBlocking {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        val count = query.resultFlow.count()
        count shouldBe 6
    }

    @Test
    fun `query resultCount should return total count`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        query.resultCount shouldBe 6L
    }

    @Test
    fun `query optionalResult as array should return row`() {
        val query = orm.query { "SELECT ${t(Templates.alias(City::class))}.id FROM ${t(City::class)} WHERE ${t(Templates.alias(City::class))}.id = ${t(1)}" }
        val result = query.optionalResult
        result.shouldNotBeNull()
    }

    @Test
    fun `query singleResult as array should return row`() {
        val query = orm.query { "SELECT ${t(Templates.alias(City::class))}.id FROM ${t(City::class)} WHERE ${t(Templates.alias(City::class))}.id = ${t(1)}" }
        val result = query.singleResult
        result.shouldNotBeNull()
    }

    @Test
    fun `query resultList as array should return rows`() {
        val query = orm.query { "SELECT ${t(Templates.alias(City::class))}.id FROM ${t(City::class)}" }
        val result = query.resultList
        result shouldHaveSize 6
    }

    // QueryBuilder: whereExists/whereNotExists

    @Test
    fun `whereExists with subquery builder should filter correctly`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereExists { subquery(Owner::class).where(TemplateString.raw("1 = 1")) }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `whereNotExists with subquery should return empty when subquery matches all`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereNotExists(
            orm.selectFrom(Owner::class).where(TemplateString.raw("1 = 1")),
        ).resultList
        cities shouldHaveSize 0
    }

    // QueryBuilder: orderByDescending

    @Test
    fun `orderByDescending should order results descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByDescending(idPath).resultList
        cities.first().id shouldBe 6
        cities.last().id shouldBe 1
    }

    @Test
    fun `orderByDescending with vararg should order results descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().orderByDescending(idPath, namePath).resultList
        cities.first().id shouldBe 6
    }

    @Test
    fun `orderByDescending with TemplateBuilder should order results descending`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().orderByDescending { "${t(Templates.alias(City::class))}.id" }.resultList
        cities.first().id shouldBe 6
    }

    // QueryBuilder: orderByAny

    @Test
    fun `orderByAny with metamodel should order results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByAny(idPath).resultList
        cities.first().id shouldBe 1
    }

    @Test
    fun `orderByDescendingAny with metamodel should order results descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByDescendingAny(idPath).resultList
        cities.first().id shouldBe 6
    }

    // WhereBuilder: whereAny, whereAnyRef, whereId

    @Test
    fun `whereBuilder with whereAny record should filter correctly`() {
        val repo = orm.entity(City::class)
        val city = City(id = 1, name = "Sun Paririe")
        val cities = repo.select().whereBuilder { whereAny(city) }.resultList
        cities shouldHaveSize 1
    }

    @Test
    fun `whereBuilder with whereAnyRef should filter correctly`() {
        val repo = orm.entity(City::class)
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val cities = repo.select().whereBuilder { whereAnyRef(cityRef) }.resultList
        cities shouldHaveSize 1
    }

    @Test
    fun `whereBuilder with whereAnyRef iterable should filter correctly`() {
        val repo = orm.entity(City::class)
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 2))
        val cities = repo.select().whereBuilder { whereAnyRef(refs) }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `whereBuilder with whereAny iterable of records should filter correctly`() {
        val repo = orm.entity(City::class)
        val records: List<Data> = listOf(
            City(id = 1, name = "Sun Paririe"),
            City(id = 2, name = "Madison"),
        )
        val cities = repo.select().whereBuilder { whereAny(records) }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `whereBuilder with whereId should filter correctly`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereBuilder { whereId(1) }.resultList
        cities shouldHaveSize 1
    }

    @Test
    fun `whereBuilder with whereId iterable should filter correctly`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereBuilder { whereId(listOf(1, 2, 3)) }.resultList
        cities shouldHaveSize 3
    }

    @Test
    fun `whereBuilder with whereRef iterable should filter correctly`() {
        val repo = orm.entity(City::class)
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 2))
        val cities = repo.select().whereBuilder { whereRef(refs) }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `whereBuilder with TRUE should return all results`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereBuilder { TRUE() }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `whereBuilder with FALSE should return no results`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereBuilder { FALSE() }.resultList
        cities shouldHaveSize 0
    }

    // PredicateBuilder: andAny/orAny

    @Test
    fun `predicateBuilder andAny should combine predicates from different entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().whereBuilder {
            where(namePath, EQUALS, "Madison") andAny where(idPath, EQUALS, 2)
        }.resultList
        cities shouldHaveSize 1
    }

    @Test
    fun `predicateBuilder orAny should combine predicates from different entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().whereBuilder {
            where(namePath, EQUALS, "Madison") orAny where(idPath, EQUALS, 1)
        }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `predicateBuilder and with TemplateBuilder should work`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().whereBuilder {
            where(namePath, EQUALS, "Madison") and { "${t(Templates.alias(City::class))}.id = ${t(2)}" }
        }.resultList
        cities shouldHaveSize 1
    }

    @Test
    fun `predicateBuilder or with TemplateBuilder should work`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().whereBuilder {
            where(namePath, EQUALS, "Madison") or { "${t(Templates.alias(City::class))}.id = ${t(1)}" }
        }.resultList
        cities shouldHaveSize 2
    }

    // WhereBuilder: whereAny with metamodel path

    @Test
    fun `whereBuilder whereAny with metamodel path and record should filter correctly`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val city = City(id = 2, name = "Madison")
        val owners = repo.select().whereBuilder { whereAny(cityPath, city) }.resultList
        owners shouldHaveSize 4
    }

    @Test
    fun `whereBuilder whereAny with metamodel path and ref should filter correctly`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val owners = repo.select().whereBuilder { whereAny(cityPath, cityRef) }.resultList
        owners shouldHaveSize 4
    }

    @Test
    fun `whereBuilder whereAnyRef with metamodel path and iterable should filter correctly`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 2))
        val owners = repo.select().whereBuilder { whereAnyRef(cityPath, refs) }.resultList
        owners shouldHaveSize 5
    }

    @Test
    fun `whereBuilder whereAny with metamodel path and iterable of records should filter correctly`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cities = listOf(City(id = 1, name = "Sun Paririe"), City(id = 3, name = "McFarland"))
        val owners = repo.select().whereBuilder { whereAny(cityPath, cities) }.resultList
        owners shouldHaveSize 2
    }

    @Test
    fun `whereBuilder whereAny with metamodel path operator and iterable should filter correctly`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().whereBuilder { whereAny(namePath, IN, listOf("Madison", "Windsor")) }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `whereBuilder whereAny with metamodel path operator and vararg should filter correctly`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().whereBuilder { whereAny(namePath, IN, "Madison", "Windsor") }.resultList
        cities shouldHaveSize 2
    }

    // Query: unsafe, versionAware, prepare

    @Test
    fun `query unsafe should allow dangerous operations`() {
        val query = orm.query { "DELETE FROM ${t(City::class)}" }
        val unsafeQuery = query.unsafe()
        unsafeQuery.shouldNotBeNull()
    }

    @Test
    fun `query versionAware should return false for non-versioned`() {
        val query = orm.query { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        query.versionAware shouldBe false
    }

    // PreparedQuery: batch operations and generated keys

    @Test
    fun `preparedQuery addBatch and executeBatch should work`() {
        val bindVars = orm.createBindVars()
        val query = orm.query { "INSERT INTO ${t(City::class)} VALUES ${t(bindVars)}" }
        query.prepare().use { preparedQuery ->
            preparedQuery.addBatch(City(name = "BatchCity1"))
            preparedQuery.addBatch(City(name = "BatchCity2"))
            val results = preparedQuery.executeBatch()
            results shouldHaveSize 2
        }
        val count = orm.entity(City::class).count()
        count shouldBe 8L
    }

    @Test
    fun `preparedQuery getGeneratedKeys should return keys`() {
        val bindVars = orm.createBindVars()
        val query = orm.query { "INSERT INTO ${t(City::class)} VALUES ${t(bindVars)}" }
        query.prepare().use { preparedQuery ->
            preparedQuery.addBatch(City(name = "GeneratedKeyCity"))
            preparedQuery.executeBatch()
            val keys = preparedQuery.getGeneratedKeys(Int::class).toList()
            keys shouldHaveSize 1
        }
    }

    // QueryBuilder: groupByAny

    @Test
    fun `groupByAny with metamodel should group results`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val builder = orm.selectFrom(Owner::class, Long::class) { "COUNT(*)" }
        val result = builder.groupByAny(cityPath).resultList
        result.shouldNotBeNull()
    }

    // QueryBuilder: whereAny with metamodel path

    @Test
    fun `queryBuilder where with record should filter correctly`() {
        val repo = orm.entity(City::class)
        val city = City(id = 1, name = "Sun Paririe")
        val cities = repo.select().where(city).resultList
        cities shouldHaveSize 1
    }
}
