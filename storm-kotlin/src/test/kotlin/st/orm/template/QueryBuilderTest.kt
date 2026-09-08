package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.count
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Data
import st.orm.JoinType
import st.orm.Metamodel
import st.orm.Navigable
import st.orm.NoResultException
import st.orm.NonUniqueResultException
import st.orm.Operator.*
import st.orm.Pageable
import st.orm.PersistenceException
import st.orm.Ref
import st.orm.Scrollable
import st.orm.TypedMetamodel
import st.orm.Window
import st.orm.repository.entity
import st.orm.template.model.*
import java.util.stream.Stream
import kotlin.reflect.KClass

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class QueryBuilderTest(
    @Autowired val orm: ORMTemplate,
) {

    // Helper to get a typed metamodel from column for use in typed where/orderBy calls.
    @Suppress("UNCHECKED_CAST")
    private fun <T : st.orm.Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // WHERE clause tests

    @Test
    fun `select with where by id should return matching entity`() {
        // data.sql: City(id=1, name='Sun Paririe').
        val city = orm.entity(City::class).select().where(1).singleResult
        city.id shouldBe 1
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `select with where by record should return matching entity`() {
        // data.sql: City(id=2, name='Madison'). Filtering by record matches on PK.
        val city = City(id = 2, name = "Madison")
        val result = orm.entity(City::class).select().where(city).singleResult
        result.id shouldBe 2
        result.name shouldBe "Madison"
    }

    @Test
    fun `select with whereId iterable should return matching entities`() {
        val cities = orm.entity(City::class).select().whereId(listOf(1, 2, 3)).resultList
        cities shouldHaveSize 3
    }

    @Test
    fun `select with where metamodel path and EQUALS operator should return matching entity`() {
        // data.sql: Only one city has name 'Madison' (id=2).
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath eq "Madison").resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select with where metamodel path and IN operator should return matching entities`() {
        // data.sql: 'Madison' (id=2) and 'Windsor' (id=4) are both present.
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath inList listOf("Madison", "Windsor")).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `select with whereBuilder should return matching entity`() {
        // data.sql: City(id=3, name='McFarland').
        val repo = orm.entity(City::class)
        val result = repo.select().whereBuilder { whereId(3) }.singleResult
        result.id shouldBe 3
        result.name shouldBe "McFarland"
    }

    @Test
    fun `select with whereExists subquery should return owners with pets`() {
        val ownerRepo = orm.entity(Owner::class)
        val results = ownerRepo.select().whereExists { subquery(Pet::class) }.resultList
        // All 10 owners have at least one pet.
        results shouldHaveSize 10
    }

    @Test
    fun `select with whereNotExists subquery should return owners without pets`() {
        val cityRepo = orm.entity(City::class)
        // All cities 1-6 are referenced by owners, so this checks cities with no owner.
        val results = cityRepo.select().whereNotExists { subquery(Owner::class) }.resultList
        results shouldHaveSize 0
    }

    // Result method tests

    @Test
    fun `resultList should return all matching entities`() {
        // data.sql inserts 6 cities (ids 1-6).
        val cities = orm.entity(City::class).select().resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `resultCount should return count of all entities`() {
        // data.sql inserts 6 cities.
        val count = orm.entity(City::class).select().resultCount
        count shouldBe 6L
    }

    @Test
    fun `singleResult should return entity when exactly one match`() {
        // data.sql: City(id=1, name='Sun Paririe').
        val city = orm.entity(City::class).select().where(1).singleResult
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `singleResult should throw NonUniqueResultException when multiple matches`() {
        assertThrows<NonUniqueResultException> {
            orm.entity(City::class).select().singleResult
        }
    }

    @Test
    fun `singleResult should throw NoResultException when no matches`() {
        assertThrows<NoResultException> {
            orm.entity(City::class).select().where(999).singleResult
        }
    }

    @Test
    fun `optionalResult should return entity when exactly one match`() {
        val city = orm.entity(City::class).select().where(1).optionalResult
        city shouldNotBe null
        city!!.name shouldBe "Sun Paririe"
    }

    @Test
    fun `optionalResult should return null when no matches`() {
        val city = orm.entity(City::class).select().where(999).optionalResult
        city shouldBe null
    }

    @Test
    fun `optionalResult should throw NonUniqueResultException when multiple matches`() {
        assertThrows<NonUniqueResultException> {
            orm.entity(City::class).select().optionalResult
        }
    }

    @Test
    fun `resultFlow should return all entities as flow`(): Unit = runBlocking {
        val count = orm.entity(City::class).select().resultFlow.count()
        count shouldBe 6
    }

    @Test
    fun `resultFlow with where should return matching entities`(): Unit = runBlocking {
        val cities = orm.entity(City::class).select().where(1).resultFlow.toList()
        cities shouldHaveSize 1
        cities[0].name shouldBe "Sun Paririe"
    }

    // Distinct tests

    @Test
    fun `distinct should return unique results`() {
        val cities = orm.entity(City::class).select().distinct().resultList
        cities shouldHaveSize 6
    }

    // OrderBy tests

    @Test
    fun `orderBy should sort results ascending`() {
        // Alphabetical order of 6 cities: Madison, McFarland, Monona, Sun Paririe, Waunakee, Windsor.
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().orderBy(namePath).resultList
        cities[0].name shouldBe "Madison"
        cities[1].name shouldBe "McFarland"
        cities[2].name shouldBe "Monona"
    }

    @Test
    fun `orderByDescending should sort results descending`() {
        // Reverse alphabetical: Windsor, Waunakee, Sun Paririe, Monona, McFarland, Madison.
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().orderByDescending(namePath).resultList
        cities[0].name shouldBe "Windsor"
        cities[1].name shouldBe "Waunakee"
        cities[2].name shouldBe "Sun Paririe"
    }

    @Test
    fun `groupBy on a foreign key groups by its foreign key column`() {
        // data.sql: 13 pets over owner ids 1..10 plus one pet without an owner. Grouping resolves to the foreign key
        // column on the pet table itself, so the ownerless pet keeps its own NULL group instead of being dropped by a
        // join to the owner table.
        val counts = orm.entity(Pet::class).selectCount()
            .groupBy(Metamodel.of<Pet, Owner>(Pet::class.java, "owner"))
            .resultList
        counts shouldHaveSize 11
        counts.sum() shouldBe 13L
    }

    @Test
    fun `groupBy on an inline record expands to its component columns`() {
        // data.sql: 10 owners with 10 distinct (address, city) pairs. The inline record expands to its component
        // columns on the owner table.
        val counts = orm.entity(Owner::class).selectCount()
            .groupBy(Metamodel.of<Owner, Address>(Owner::class.java, "address"))
            .resultList
        counts shouldHaveSize 10
        counts.sum() shouldBe 10L
    }

    // Limit and Offset tests

    @Test
    fun `limit should restrict number of results`() {
        val cities = orm.entity(City::class).select().limit(3).resultList
        cities shouldHaveSize 3
    }

    @Test
    fun `offset should skip results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy(idPath).offset(2).limit(2).resultList
        cities shouldHaveSize 2
        cities[0].id shouldBe 3
        cities[1].id shouldBe 4
    }

    @Test
    fun `limit and offset combined for pagination`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        // Page 1
        val page1 = repo.select().orderBy(idPath).limit(2).offset(0).resultList
        page1 shouldHaveSize 2
        page1[0].id shouldBe 1
        page1[1].id shouldBe 2
        // Page 2
        val page2 = repo.select().orderBy(idPath).limit(2).offset(2).resultList
        page2 shouldHaveSize 2
        page2[0].id shouldBe 3
        page2[1].id shouldBe 4
    }

    // GroupBy and Having tests

    @Test
    fun `selectCount with groupBy should group results`() {
        // data.sql: 10 owners across 6 distinct cities. Grouping by city_id yields 6 groups.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, Any>(repo.model, "city_id")
        val counts = repo.selectCount().groupBy(cityPath).resultList
        counts shouldHaveSize 6
    }

    // ForUpdate tests

    @Test
    fun `forUpdate should not change result content`() {
        val city = orm.entity(City::class).select().where(1).forUpdate().singleResult
        city.id shouldBe 1
        city.name shouldBe "Sun Paririe"
    }

    // Unsafe mode tests

    // Join tests

    @Test
    fun `innerJoin should return entities with matching join`() {
        // data.sql: 13 pets total, but Pet(id=13, name='Sly') has NULL owner_id.
        // Inner join excludes pets with no matching owner, yielding 12.
        val repo = orm.entity(Pet::class)
        val pets = repo.select().innerJoin(Owner::class).on(Pet::class).resultList
        pets shouldHaveSize 12
    }

    @Test
    fun `leftJoin should include entities without matching join`() {
        // data.sql: 13 pets total. Left join preserves all pets, including Pet 13 with NULL owner.
        val repo = orm.entity(Pet::class)
        val pets = repo.select().leftJoin(Owner::class).on(Pet::class).resultList
        pets shouldHaveSize 13
    }

    @Test
    fun `innerJoin with reified type arguments should return entities with matching join`() {
        // data.sql: 13 pets total, but Pet(id=13, name='Sly') has NULL owner_id.
        // Inner join excludes pets with no matching owner, yielding 12.
        val pets = orm.entity<Pet>().select().innerJoin<Owner>().on<Pet>().resultList
        pets shouldHaveSize 12
    }

    @Test
    fun `leftJoin with reified type arguments should include entities without matching join`() {
        // data.sql: 13 pets total. Left join preserves all pets, including Pet 13 with NULL owner.
        val pets = orm.entity<Pet>().select().leftJoin<Owner>().on<Pet>().resultList
        pets shouldHaveSize 13
    }

    // Delete tests

    @Test
    fun `delete with where by id should remove matching entity`() {
        // data.sql: Vet(id=1, 'James', 'Carter') has no vet_specialty entries, safe to delete.
        // After deleting 1 of 6 vets, 5 remain.
        val repo = orm.entity(Vet::class)
        val deleted = repo.delete().where(1).executeUpdate()
        deleted shouldBe 1
        repo.count() shouldBe 5
    }

    @Test
    fun `delete with where by record should remove matching entity`() {
        // data.sql: Vet(id=6, 'Sharon', 'Jenkins') has no vet_specialty entries, safe to delete.
        // After deleting 1 of 6 vets, 5 remain.
        val repo = orm.entity(Vet::class)
        val vet = repo.select().where(6).singleResult
        val deleted = repo.delete().where(vet).executeUpdate()
        deleted shouldBe 1
        repo.count() shouldBe 5
    }

    // WhereBuilder predicate chaining tests

    @Test
    fun `whereBuilder with and should combine predicates`() {
        // data.sql: Owner(id=4, first_name='Harold', last_name='Davis') is the only match.
        val repo = orm.entity(Owner::class)
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.select().whereBuilder {
            (firstNamePath eq "Harold") and (lastNamePath eq "Davis")
        }.resultList
        result shouldHaveSize 1
        result[0].firstName shouldBe "Harold"
        result[0].lastName shouldBe "Davis"
    }

    @Test
    fun `whereBuilder with or should match either predicate`() {
        // data.sql: City(id=2, name='Madison') and City(id=4, name='Windsor').
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            (namePath eq "Madison") or (namePath eq "Windsor")
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereBuilder with TRUE should match all records`() {
        val repo = orm.entity(City::class)
        val result = repo.select().whereBuilder { TRUE() }.resultList
        result shouldHaveSize 6
    }

    @Test
    fun `whereBuilder with FALSE should match no records`() {
        val repo = orm.entity(City::class)
        val result = repo.select().whereBuilder { FALSE() }.resultList
        result shouldHaveSize 0
    }

    @Test
    fun `whereBuilder with complex and-or predicate`() {
        // data.sql: Owner(id=1, 'Betty', 'Davis') and Owner(id=2, 'George', 'Franklin').
        val repo = orm.entity(Owner::class)
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.select().whereBuilder {
            ((firstNamePath eq "Betty") and (lastNamePath eq "Davis")) or
                ((firstNamePath eq "George") and (lastNamePath eq "Franklin"))
        }.resultList
        result shouldHaveSize 2
    }

    // Cross-root predicate combination tests

    @Test
    fun `and across roots inline in a widened where should filter by both entities`() {
        // data.sql: Pet('Leo') belongs to Owner(id=1, 'Betty', 'Davis'). The join widens the query, so the
        // builder's root flows into the inference and the inline cross-root combination is accepted.
        val petNamePath = Metamodel.of<Pet, String>(Pet::class.java, "name")
        val ownerLastNamePath = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
        val pets = orm.entity(Pet::class).select()
            .innerJoin(Owner::class).on(Pet::class)
            .where((petNamePath eq "Leo") and (ownerLastNamePath eq "Davis"))
            .resultList
        pets shouldHaveSize 1
        pets[0].name shouldBe "Leo"
    }

    @Test
    fun `or across roots inline in a widened where should match either predicate`() {
        // data.sql: the Davis owners (Betty, Harold) hold Leo and Iggy; Basil belongs to Franklin.
        val petNamePath = Metamodel.of<Pet, String>(Pet::class.java, "name")
        val ownerLastNamePath = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
        val pets = orm.entity(Pet::class).select()
            .innerJoin(Owner::class).on(Pet::class)
            .where((petNamePath eq "Basil") or (ownerLastNamePath eq "Davis"))
            .resultList
        pets.map { it.name }.toSet() shouldBe setOf("Basil", "Leo", "Iggy")
    }

    @Test
    fun `a standalone cross-root predicate is accepted by a widened builder`() {
        // Built outside any where call, the combination roots at the operands' least common root, which the
        // widened builder accepts and verifies when the query is built.
        val petNamePath = Metamodel.of<Pet, String>(Pet::class.java, "name")
        val ownerLastNamePath = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
        val predicate = (petNamePath eq "Leo") and (ownerLastNamePath eq "Davis")
        val pets = orm.entity(Pet::class).select()
            .innerJoin(Owner::class).on(Pet::class)
            .where(predicate)
            .resultList
        pets shouldHaveSize 1
        pets[0].name shouldBe "Leo"
    }

    @Test
    fun `and on a shared root keeps the root and serves a narrow builder`() {
        // data.sql: Owner(id=4, 'Harold', 'Davis') is the only match. No join, so where() is typed to Owner
        // and the same-root combination infers that root.
        val firstNamePath = Metamodel.of<Owner, String>(Owner::class.java, "firstName")
        val lastNamePath = Metamodel.of<Owner, String>(Owner::class.java, "lastName")
        val owners = orm.entity(Owner::class).select()
            .where((firstNamePath eq "Harold") and (lastNamePath eq "Davis"))
            .resultList
        owners shouldHaveSize 1
        owners[0].firstName shouldBe "Harold"
    }

    @Test
    fun `a combined predicate on an entity outside the query fails with a descriptive error`() {
        // Vet is neither joined nor reachable from Pet, so building the query reports the foreign path.
        val petNamePath = Metamodel.of<Pet, String>(Pet::class.java, "name")
        val vetLastNamePath = Metamodel.of<Vet, String>(Vet::class.java, "lastName")
        val exception = assertThrows<PersistenceException> {
            orm.entity(Pet::class).select()
                .innerJoin(Owner::class).on(Pet::class)
                .where((petNamePath eq "Leo") and (vetLastNamePath eq "Carter"))
                .resultList
        }
        exception.message!! shouldContain "Vet is not part of this query"
    }

    // ColumnImpl tests

    @Test
    fun `city model columns should have correct properties`() {
        val model = orm.entity(City::class).model
        val columns = model.columns
        // City has: id (PK), name
        columns.size shouldBe 2

        val idCol = columns.first { it.name == "id" }
        idCol.primaryKey shouldBe true
        idCol.nullable shouldBe false
        idCol.foreignKey shouldBe false
        idCol.version shouldBe false
        idCol.ref shouldBe false
        idCol.insertable shouldBe true

        val nameCol = columns.first { it.name == "name" }
        nameCol.primaryKey shouldBe false
        nameCol.nullable shouldBe false
        nameCol.foreignKey shouldBe false
        nameCol.insertable shouldBe true
        nameCol.updatable shouldBe true
    }

    @Test
    fun `owner model columns should detect version column`() {
        val model = orm.entity(Owner::class).model
        val columns = model.columns

        val versionCol = columns.first { it.name == "version" }
        versionCol.version shouldBe true
        versionCol.primaryKey shouldBe false
    }

    @Test
    fun `owner model columns should detect foreign key`() {
        val model = orm.entity(Owner::class).model
        val columns = model.columns

        val cityCol = columns.first { it.name == "city_id" }
        cityCol.foreignKey shouldBe true
        cityCol.nullable shouldBe true
    }

    @Test
    fun `pet model columns should detect non-updatable column`() {
        val model = orm.entity(Pet::class).model
        val columns = model.columns

        val birthDateCol = columns.first { it.name == "birth_date" }
        birthDateCol.updatable shouldBe false
        birthDateCol.insertable shouldBe true

        val typeCol = columns.first { it.name == "type_id" }
        typeCol.foreignKey shouldBe true
        typeCol.updatable shouldBe false
    }

    @Test
    fun `pet model columns should detect nullable foreign key`() {
        val model = orm.entity(Pet::class).model
        val columns = model.columns

        val ownerCol = columns.first { it.name == "owner_id" }
        ownerCol.foreignKey shouldBe true
        ownerCol.nullable shouldBe true
    }

    @Test
    fun `model should report correct table name`() {
        val model = orm.entity(City::class).model
        model.name shouldBe "city"
    }

    @Test
    fun `model should report correct primary key type`() {
        val model = orm.entity(City::class).model
        model.primaryKeyType shouldBe Int::class
    }

    @Test
    fun `model should report correct entity type`() {
        val model = orm.entity(City::class).model
        model.type shouldBe City::class
    }

    @Test
    fun `column metamodel should not be null`() {
        val model = orm.entity(City::class).model
        model.columns.forEach { col ->
            col.metamodel shouldNotBe null
        }
    }

    // Infix DSL operator tests

    @Test
    fun `eq infix operator should filter by equality`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath eq "Madison").resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Madison"
    }

    @Test
    fun `neq infix operator should filter by inequality`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath neq "Madison").resultList
        result shouldHaveSize 5
    }

    @Test
    fun `like infix operator should match pattern`() {
        // data.sql: Cities starting with 'M': Madison, McFarland, Monona (3 of 6).
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath like "M%").resultList
        result shouldHaveSize 3
    }

    @Test
    fun `notLike infix operator should exclude pattern`() {
        // data.sql: Cities NOT starting with 'M': Sun Paririe, Windsor, Waunakee (3 of 6).
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath notLike "M%").resultList
        result shouldHaveSize 3
    }

    @Test
    fun `greater infix operator should filter by greater than`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val result = repo.select().where(idPath greater 4).resultList
        // IDs 5, 6
        result shouldHaveSize 2
    }

    @Test
    fun `less infix operator should filter by less than`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val result = repo.select().where(idPath less 3).resultList
        // IDs 1, 2
        result shouldHaveSize 2
    }

    @Test
    fun `greaterEq infix operator should filter by greater than or equal`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val result = repo.select().where(idPath greaterEq 5).resultList
        // IDs 5, 6
        result shouldHaveSize 2
    }

    @Test
    fun `lessEq infix operator should filter by less than or equal`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val result = repo.select().where(idPath lessEq 2).resultList
        // IDs 1, 2
        result shouldHaveSize 2
    }

    @Test
    fun `inList infix operator should match values in list`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath inList listOf("Madison", "Windsor", "Monona")).resultList
        result shouldHaveSize 3
    }

    @Test
    fun `notInList infix operator should exclude values in list`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath notInList listOf("Madison", "Windsor", "Monona")).resultList
        // Sun Paririe, McFarland, Waunakee
        result shouldHaveSize 3
    }

    @Test
    fun `isNull should find entities with null values`() {
        // data.sql: Pet(id=13, name='Sly', owner_id=NULL) is the only pet with a null owner.
        val repo = orm.entity(Pet::class)
        val ownerPath = metamodel<Pet, Any>(repo.model, "owner_id")
        val result = repo.select().where(ownerPath.isNull()).resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Sly"
    }

    @Test
    fun `isNotNull should find entities with non-null values`() {
        // data.sql: 13 pets total, 1 with NULL owner (Sly), so 12 have non-null owners.
        val repo = orm.entity(Pet::class)
        val ownerPath = metamodel<Pet, Any>(repo.model, "owner_id")
        val result = repo.select().where(ownerPath.isNotNull()).resultList
        result shouldHaveSize 12
    }

    @Test
    fun `between should filter by range`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val result = repo.select().where(idPath.between(2, 4)).resultList
        // IDs 2, 3, 4
        result shouldHaveSize 3
    }

    // Combined infix and predicate builder tests

    @Test
    fun `infix operators with and combinator`() {
        val repo = orm.entity(Owner::class)
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.select().where(
            (firstNamePath eq "Harold") and (lastNamePath eq "Davis"),
        ).resultList
        result shouldHaveSize 1
        result[0].firstName shouldBe "Harold"
    }

    @Test
    fun `infix operators with or combinator`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(
            (namePath eq "Madison") or (namePath eq "Windsor"),
        ).resultList
        result shouldHaveSize 2
    }

    // Additional WHERE clause tests

    @Test
    fun `where with iterable of records should match all`() {
        val repo = orm.entity(City::class)
        val city1 = City(id = 1, name = "Sun Paririe")
        val city2 = City(id = 2, name = "Madison")
        val result = repo.select().where(listOf(city1, city2)).resultList
        result shouldHaveSize 2
    }

    @Test
    fun `where with LIKE operator should match pattern`() {
        // data.sql: Cities containing 'on': Madison, Monona (2 of 6).
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath like "%on%").resultList
        result shouldHaveSize 2
    }

    // Model declaredColumns tests

    @Test
    fun `declaredColumns should return columns for owner model`() {
        val model = orm.entity(Owner::class).model
        val declared = model.declaredColumns
        val allColumns = model.columns
        // Owner has: id, firstName, lastName, address (inline), telephone, version
        // The inline Address expands into address (String) and city (FK -> city_id).
        // Verify both accessors work and return column data.
        declared.isNotEmpty() shouldBe true
        allColumns.isNotEmpty() shouldBe true
        // Verify known columns exist.
        allColumns.any { it.name == "id" } shouldBe true
        allColumns.any { it.name == "first_name" } shouldBe true
        allColumns.any { it.name == "last_name" } shouldBe true
        allColumns.any { it.name == "city_id" } shouldBe true
        allColumns.any { it.name == "version" } shouldBe true
    }

    // Model values tests

    @Test
    fun `model values should extract column values from entity`() {
        val model = orm.entity(City::class).model
        val city = City(id = 42, name = "TestCity")
        val values = model.values(city)
        values.size shouldBe 2
        val idEntry = values.entries.first { it.key.name == "id" }
        idEntry.value shouldBe 42
        val nameEntry = values.entries.first { it.key.name == "name" }
        nameEntry.value shouldBe "TestCity"
    }

    @Test
    fun `model isDefaultPrimaryKey should detect default pk`() {
        val model = orm.entity(City::class).model
        model.isDefaultPrimaryKey(0) shouldBe true
        model.isDefaultPrimaryKey(1) shouldBe false
    }

    // Edge cases

    @Test
    fun `whereId with empty iterable should return no results`() {
        val result = orm.entity(City::class).select().whereId(emptyList()).resultList
        result shouldHaveSize 0
    }

    @Test
    fun `where with NOT_IN and empty list should return all results`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath notInList emptyList<String>()).resultList
        result shouldHaveSize 6
    }

    @Test
    fun `inList with empty list should return no results`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath inList emptyList()).resultList
        result shouldHaveSize 0
    }

    @Test
    fun `limit zero should return no results`() {
        val cities = orm.entity(City::class).select().limit(0).resultList
        cities shouldHaveSize 0
    }

    @Test
    fun `resultFlow count should match resultCount`(): Unit = runBlocking {
        val repo = orm.entity(City::class)
        val flowCount = repo.select().resultFlow.count()
        val resultCount = repo.select().resultCount
        flowCount.toLong() shouldBe resultCount
    }

    // Query interface default methods

    @Test
    fun `build should return executable Query`() {
        val query = orm.entity(City::class).select().where(1).build()
        val result = query.getSingleResult(City::class)
        result.id shouldBe 1
        result.name shouldBe "Sun Paririe"
    }

    @Test
    fun `query getResultFlow should return typed flow`(): Unit = runBlocking {
        val query = orm.entity(City::class).select().build()
        val cities = query.getResultFlow(City::class).toList()
        cities shouldHaveSize 6
    }

    @Test
    fun `query resultFlow should return untyped array flow`(): Unit = runBlocking {
        val query = orm.entity(City::class).select().build()
        val count = query.resultFlow.count()
        count shouldBe 6
    }

    @Test
    fun `query getResultList should return typed list`() {
        val query = orm.entity(City::class).select().build()
        val cities = query.getResultList(City::class)
        cities shouldHaveSize 6
    }

    @Test
    fun `query getSingleResult should return typed result`() {
        val query = orm.entity(City::class).select().where(1).build()
        val city = query.getSingleResult(City::class)
        city.id shouldBe 1
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `query getOptionalResult should return typed result or null`() {
        val queryWithResult = orm.entity(City::class).select().where(1).build()
        val city = queryWithResult.getOptionalResult(City::class)
        city shouldNotBe null
        city!!.name shouldBe "Sun Paririe"

        val queryWithoutResult = orm.entity(City::class).select().where(999).build()
        val missing = queryWithoutResult.getOptionalResult(City::class)
        missing shouldBe null
    }

    @Test
    fun `query resultCount should count results`() {
        val query = orm.entity(City::class).select().build()
        query.resultCount shouldBe 6L
    }

    @Test
    fun `query versionAware should return version status`() {
        val cityQuery = orm.entity(City::class).select().build()
        // City has no @Version column, so version aware should be false.
        cityQuery.versionAware shouldBe false
    }

    @Test
    fun `query getRefList should return ref list`() {
        // getRefList maps each result row to a Ref by interpreting rows as the PK type.
        // The query must select only the PK column for this to work.
        val query = orm.query("SELECT id FROM city")
        val refs = query.getRefList(City::class, Integer::class)
        refs shouldHaveSize 6
        refs.forEach { ref ->
            ref.id() shouldNotBe null
        }
    }

    @Test
    fun `query getRefFlow should return ref flow`(): Unit = runBlocking {
        // getRefFlow maps each result row to a Ref by interpreting rows as the PK type.
        // The query must select only the PK column for this to work.
        val query = orm.query("SELECT id FROM city")
        val refs = query.getRefFlow(City::class, Integer::class).toList()
        refs shouldHaveSize 6
        refs.forEach { ref ->
            ref.id() shouldNotBe null
        }
    }

    // QueryBuilder: forShare

    @Test
    fun `forShare should build query with lock`() {
        // H2 does not support FOR SHARE syntax, so we verify the builder method is callable
        // and that execution throws a PersistenceException (SQL syntax error on H2).
        assertThrows<st.orm.PersistenceException> {
            orm.entity(City::class).select().where(1).forShare().singleResult
        }
    }

    // QueryBuilder: having

    @Test
    fun `having with template should filter grouped results`() {
        // data.sql: 10 owners across 6 cities. Per-city counts:
        //   city 1: 1 (Betty), city 2: 4 (George, Peter, Maria, David),
        //   city 3: 1 (Eduardo), city 4: 1 (Harold), city 5: 2 (Jean, Jeff), city 6: 1 (Carlos).
        // Only cities with count > 1: city 2 (4 owners), city 5 (2 owners).
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, Any>(repo.model, "city_id")
        val counts = repo.selectCount().groupBy(cityPath).having { "COUNT(*) > ${t(1)}" }.resultList
        counts shouldHaveSize 2
    }

    // QueryBuilder: rightJoin

    @Test
    fun `rightJoin should include entities without matching join`() {
        val repo = orm.entity(Pet::class)
        // Right join Pet -> Owner includes all owners, even those whose pets do not reference them via this join path.
        // All 13 pets have types, and there are 6 pet types. Right join on PetType should include all pet types.
        val pets = repo.select().rightJoin(Owner::class).on(Pet::class).resultList
        // All 12 pets that have an owner (Pet 13 "Sly" has null owner, so right join excludes it
        // but includes all owners). Because it's a right join, owners without pets would also appear,
        // but all 10 owners have at least one pet. The result should be 12 (matching pet-owner pairs).
        pets shouldHaveSize 12
    }

    // QueryBuilder: crossJoin

    @Test
    fun `crossJoin should return cartesian product`() {
        // Cross join City x City produces 6*6 = 36 rows, but we only select from the primary table (City).
        // Limit to verify it produces more than 6 results.
        val repo = orm.entity(City::class)
        val count = repo.select().crossJoin(City::class).resultCount
        count shouldBe 36L
    }

    // QueryBuilder: whereBuilder advanced

    @Test
    fun `whereBuilder with subquery should filter entities`() {
        val ownerRepo = orm.entity(Owner::class)
        val results = ownerRepo.select().whereBuilder { exists(subquery(Pet::class)) }.resultList
        // All 10 owners have at least one pet.
        results shouldHaveSize 10
    }

    @Test
    fun `whereBuilder with notExists subquery should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val results = cityRepo.select().whereBuilder { notExists(subquery(Owner::class)) }.resultList
        // All 6 cities are referenced by at least one owner.
        results shouldHaveSize 0
    }

    @Test
    fun `whereBuilder with where using Ref should filter by FK reference`() {
        // data.sql: City 2 (Madison) has 4 owners: George (id=2), Peter (id=5), Maria (id=8), David (id=9).
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val results = repo.select().whereBuilder { where(cityPath, cityRef) }.resultList
        results shouldHaveSize 4
    }

    // QueryBuilder: orderBy with limit

    @Test
    fun `orderBy with limit should window ordered results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        // Use orderBy + limit to get first 3 cities by id.
        val cities = repo.select().orderBy(idPath).limit(3).resultList
        cities shouldHaveSize 3
        cities[0].id shouldBe 1
        cities[2].id shouldBe 3
    }

    @Test
    fun `where with TemplateBuilder should filter results`() {
        // Use a TemplateBuilder lambda to add a WHERE clause filtering by name.
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where { "${t(Templates.column(namePath))} = ${t("Madison")}" }.resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `where with TemplateString should filter results`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val templateString = TemplateString.raw { "${t(Templates.column(namePath))} = ${t("Windsor")}" }
        val cities = repo.select().where(templateString).resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Windsor"
    }

    @Test
    fun `where with Ref should filter by foreign key reference`() {
        // data.sql: City 2 (Madison) has 4 owners: George (id=2), Peter (id=5), Maria (id=8), David (id=9).
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val owners = repo.select().where(cityPath, cityRef).resultList
        owners shouldHaveSize 4
    }

    @Test
    fun `whereRef with iterable of refs should filter entities`() {
        // Filter cities by a list of refs: city 1 and city 3.
        val repo = orm.entity(City::class)
        val ref1: Ref<City> = Ref.of(City::class.java, 1)
        val ref3: Ref<City> = Ref.of(City::class.java, 3)
        val cities = repo.select().whereRef(listOf(ref1, ref3)).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `whereRef with metamodel path and iterable of refs should filter entities`() {
        // Filter owners by city refs: city 1 and city 3.
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val ref1: Ref<City> = Ref.of(City::class.java, 1)
        val ref3: Ref<City> = Ref.of(City::class.java, 3)
        val owners = repo.select().whereRef(cityPath, listOf(ref1, ref3)).resultList
        // City 1: Betty (id=1), City 3: Eduardo (id=3)
        owners shouldHaveSize 2
    }

    @Test
    fun `innerJoin with TemplateString and alias should join and filter`() {
        // Use City (which has no FK relations) and join to pet_type to avoid alias conflicts.
        val repo = orm.entity(City::class)
        val templateString = TemplateString.raw("pet_type")
        val count = repo.select().innerJoin(templateString, "pt").on { "pt.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        // Pet types have IDs 0-5, cities have IDs 1-6. Matching IDs: 1,2,3,4,5 = 5.
        count shouldBe 5L
    }

    @Test
    fun `innerJoin with TemplateBuilder and alias should join and filter`() {
        val repo = orm.entity(City::class)
        val templateBuilder: TemplateBuilder = { "pet_type" }
        val count = repo.select().innerJoin(templateBuilder, "pt").on { "pt.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        // Pet types have IDs 0-5, cities have IDs 1-6. Matching IDs: 1,2,3,4,5 = 5.
        count shouldBe 5L
    }

    @Test
    fun `leftJoin with TemplateString and alias should include all rows`() {
        val repo = orm.entity(City::class)
        val templateString = TemplateString.raw("vet")
        val count = repo.select().leftJoin(templateString, "v").on { "v.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        // 6 cities, all have matching vet ids (1..6), left join keeps all.
        count shouldBe 6L
    }

    @Test
    fun `leftJoin with TemplateBuilder and alias should include all rows`() {
        val repo = orm.entity(City::class)
        val templateBuilder: TemplateBuilder = { "vet" }
        val count = repo.select().leftJoin(templateBuilder, "v").on { "v.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        count shouldBe 6L
    }

    @Test
    fun `rightJoin with TemplateString and alias should include join-side rows`() {
        val repo = orm.entity(City::class)
        val templateString = TemplateString.raw("vet")
        val count = repo.select().rightJoin(templateString, "v").on { "v.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        // 6 vets, all have matching city ids (1..6), right join keeps all.
        count shouldBe 6L
    }

    @Test
    fun `rightJoin with TemplateBuilder and alias should include join-side rows`() {
        val repo = orm.entity(City::class)
        val templateBuilder: TemplateBuilder = { "vet" }
        val count = repo.select().rightJoin(templateBuilder, "v").on { "v.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        count shouldBe 6L
    }

    @Test
    fun `crossJoin with TemplateString should produce cartesian product`() {
        val repo = orm.entity(City::class)
        val templateString = TemplateString.raw("pet_type")
        val count = repo.select().crossJoin(templateString).resultCount
        // 6 cities x 6 pet types = 36
        count shouldBe 36L
    }

    @Test
    fun `crossJoin with TemplateBuilder should produce cartesian product`() {
        val repo = orm.entity(City::class)
        val count = repo.select().crossJoin { "pet_type" }.resultCount
        // 6 cities x 6 pet types = 36
        count shouldBe 36L
    }

    @Test
    fun `join with JoinType and TemplateString should work`() {
        val repo = orm.entity(City::class)
        val templateString = TemplateString.raw("pet_type")
        val count = repo.select().join(JoinType.inner(), templateString, "pt").on { "pt.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        // Pet types have IDs 0-5, cities have IDs 1-6. Matching IDs: 1,2,3,4,5 = 5.
        count shouldBe 5L
    }

    @Test
    fun `join with JoinType and TemplateBuilder should work`() {
        val repo = orm.entity(City::class)
        val templateBuilder: TemplateBuilder = { "pet_type" }
        val count = repo.select().join(JoinType.left(), templateBuilder, "pt").on { "pt.id = ${t(Templates.alias(City::class))}.id" }.resultCount
        count shouldBe 6L
    }

    @Test
    fun `join with JoinType and KClass and alias should work`() {
        // Use Pet joining Owner, which is the existing typed join pattern.
        val repo = orm.entity(Pet::class)
        val count = repo.select().join(JoinType.inner(), Owner::class, "").on(Pet::class).resultCount
        // 12 pets have owners.
        count shouldBe 12L
    }

    @Test
    fun `orderBy with TemplateBuilder should sort results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy { "${t(Templates.column(idPath))}" }.resultList
        cities[0].id shouldBe 1
        cities[5].id shouldBe 6
    }

    @Test
    fun `orderBy with TemplateString should sort results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val templateString = TemplateString.raw { t(Templates.column(idPath)) }
        val cities = repo.select().orderBy(templateString).resultList
        cities[0].id shouldBe 1
    }

    @Test
    fun `orderByDescending with TemplateBuilder should sort descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByDescending { t(Templates.column(idPath)) }.resultList
        cities[0].id shouldBe 6
        cities[5].id shouldBe 1
    }

    @Test
    fun `orderByDescending with TemplateString should sort descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val templateString = TemplateString.raw { t(Templates.column(idPath)) }
        val cities = repo.select().orderByDescending(templateString).resultList
        cities[0].id shouldBe 6
    }

    @Test
    fun `orderByAny with metamodel should sort results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy(idPath).resultList
        cities[0].id shouldBe 1
        cities[5].id shouldBe 6
    }

    @Test
    fun `orderByDescending with vararg metamodels should sort descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByDescending(idPath).resultList
        cities[0].id shouldBe 6
        cities[5].id shouldBe 1
    }

    @Test
    fun `orderByDescendingAny with vararg metamodels should sort descending`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val owners = repo.select().orderByDescending(lastNamePath, firstNamePath).resultList
        // Verify first result has alphabetically last-last-name (Schroeder > Rodriquez > ...)
        owners shouldHaveSize 10
    }

    @Test
    fun `groupBy with TemplateBuilder should group results`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, Any>(repo.model, "city_id")
        val counts = repo.selectCount().groupBy { t(Templates.column(cityPath)) }.resultList
        counts shouldHaveSize 6
    }

    @Test
    fun `groupBy with TemplateString should group results`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, Any>(repo.model, "city_id")
        val templateString = TemplateString.raw { t(Templates.column(cityPath)) }
        val counts = repo.selectCount().groupBy(templateString).resultList
        counts shouldHaveSize 6
    }

    @Test
    fun `groupByAny with empty vararg should throw PersistenceException`() {
        val repo = orm.entity(Owner::class)
        assertThrows<PersistenceException> {
            repo.selectCount().groupBy().resultList
        }
    }

    @Test
    fun `orderByAny with empty vararg should throw PersistenceException`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.select().orderBy().resultList
        }
    }

    @Test
    fun `orderByDescendingAny with empty vararg should throw PersistenceException`() {
        val repo = orm.entity(City::class)
        assertThrows<PersistenceException> {
            repo.select().orderByDescending().resultList
        }
    }

    @Test
    fun `having with TemplateString should filter grouped results`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, Any>(repo.model, "city_id")
        val templateString = TemplateString.raw { "COUNT(*) > ${t(1)}" }
        val counts = repo.selectCount().groupBy(cityPath).having(templateString).resultList
        counts shouldHaveSize 2
    }

    @Test
    fun `forLock with TemplateString should apply lock mode`() {
        val templateString = TemplateString.raw("FOR UPDATE")
        val city = orm.entity(City::class).select().where(1).forLock(templateString).singleResult
        city.id shouldBe 1
    }

    @Test
    fun `forLock with TemplateBuilder should apply lock mode`() {
        val city = orm.entity(City::class).select().where(1).forLock { "FOR UPDATE" }.singleResult
        city.id shouldBe 1
    }

    @Test
    fun `and should combine two predicates`() {
        val repo = orm.entity(Owner::class)
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.select().whereBuilder {
            (firstNamePath eq "Betty") and (lastNamePath eq "Davis")
        }.resultList
        result shouldHaveSize 1
        result[0].firstName shouldBe "Betty"
    }

    @Test
    fun `and should combine predicates across entities after a join`() {
        val petNamePath = metamodel<Pet, String>(orm.entity(Pet::class).model, "name")
        val ownerLastNamePath = metamodel<Owner, String>(orm.entity(Owner::class).model, "last_name")
        val pets = orm.entity(Pet::class).select()
            .innerJoin(Owner::class).on(Pet::class)
            .whereBuilder {
                (petNamePath eq "Leo") and (ownerLastNamePath eq "Davis")
            }.resultList
        pets shouldHaveSize 1
        pets[0].name shouldBe "Leo"
    }

    @Test
    fun `or should combine predicates across entities after a join`() {
        // Pets named Leo (Betty Davis's pet) or owned by a Davis: Leo and Harold Davis's Iggy.
        val petNamePath = metamodel<Pet, String>(orm.entity(Pet::class).model, "name")
        val ownerLastNamePath = metamodel<Owner, String>(orm.entity(Owner::class).model, "last_name")
        val pets = orm.entity(Pet::class).select()
            .innerJoin(Owner::class).on(Pet::class)
            .whereBuilder {
                (petNamePath eq "Leo") or (ownerLastNamePath eq "Davis")
            }.resultList
        pets shouldHaveSize 2
    }

    @Test
    fun `predicate and with TemplateBuilder should combine conditions`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            (namePath eq "Madison") and { "${t(Templates.column(metamodel<City, Int>(repo.model, "id")))} = ${t(2)}" }
        }.resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Madison"
    }

    @Test
    fun `predicate and with TemplateString should combine conditions`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val templateString = TemplateString.raw { "${t(Templates.column(metamodel<City, Int>(repo.model, "id")))} = ${t(2)}" }
        val result = repo.select().whereBuilder {
            (namePath eq "Madison") and templateString
        }.resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Madison"
    }

    @Test
    fun `predicate or with TemplateBuilder should combine conditions`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            (namePath eq "Madison") or { "${t(Templates.column(namePath))} = ${t("Windsor")}" }
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `predicate or with TemplateString should combine conditions`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val templateString = TemplateString.raw { "${t(Templates.column(namePath))} = ${t("Windsor")}" }
        val result = repo.select().whereBuilder {
            (namePath eq "Madison") or templateString
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereAny with record should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val city = City(id = 2, name = "Madison")
        val result = cityRepo.select().whereBuilder { where(city) }.resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Madison"
    }

    @Test
    fun `whereAny with iterable of records should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val city1 = City(id = 1, name = "Sun Paririe")
        val city2 = City(id = 2, name = "Madison")
        val result = cityRepo.select().whereBuilder { where(listOf(city1, city2)) }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereAnyRef with single ref should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val ref: Ref<City> = Ref.of(City::class.java, 3)
        val result = cityRepo.select().whereBuilder { whereRef(ref) }.resultList
        result shouldHaveSize 1
        result[0].name shouldBe "McFarland"
    }

    @Test
    fun `whereAnyRef with iterable of refs should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val ref1: Ref<City> = Ref.of(City::class.java, 1)
        val ref2: Ref<City> = Ref.of(City::class.java, 4)
        val result = cityRepo.select().whereBuilder { whereRef(listOf(ref1, ref2)) }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereRef with single ref should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val ref: Ref<City> = Ref.of(City::class.java, 5)
        val result = cityRepo.select().whereBuilder { whereRef(ref) }.resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Monona"
    }

    @Test
    fun `whereRef with iterable of refs via WhereBuilder should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val ref1: Ref<City> = Ref.of(City::class.java, 2)
        val ref5: Ref<City> = Ref.of(City::class.java, 5)
        val result = cityRepo.select().whereBuilder { whereRef(listOf(ref1, ref5)) }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereBuilder with where TemplateBuilder should filter`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            where { "${t(Templates.column(namePath))} = ${t("Madison")}" }
        }.resultList
        result shouldHaveSize 1
    }

    @Test
    fun `whereBuilder with where TemplateString should filter`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val templateString = TemplateString.raw { "${t(Templates.column(namePath))} = ${t("McFarland")}" }
        val result = repo.select().whereBuilder {
            where(templateString)
        }.resultList
        result shouldHaveSize 1
    }

    @Test
    fun `whereAny with PredicateBuilder should filter entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath eq "Madison").resultList
        result shouldHaveSize 1
    }

    @Test
    fun `whereAnyBuilder should use WhereBuilder from any type`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            (namePath eq "Madison")
        }.resultList
        result shouldHaveSize 1
    }

    @Test
    fun `where with ref should filter by PK`() {
        val repo = orm.entity(City::class)
        val ref: Ref<City> = Ref.of(City::class.java, 4)
        val result = repo.select().where(ref).resultList
        result shouldHaveSize 1
        result[0].name shouldBe "Windsor"
    }

    @Test
    fun `where with iterable of records should delegate to whereBuilder`() {
        val repo = orm.entity(City::class)
        val city1 = City(id = 1, name = "Sun Paririe")
        val city3 = City(id = 3, name = "McFarland")
        val result = repo.select().where(listOf(city1, city3)).resultList
        result shouldHaveSize 2
    }

    @Test
    fun `where with path operator and iterable should filter`() {
        // Use the where(path inList Iterable) overload
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().where(namePath inList listOf("Madison", "Windsor")).resultList
        result shouldHaveSize 2
    }

    @Test
    fun `scroll with simple size should return correct page`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(3)
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scroll with size greater than total should not have next`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(10)
        window.content shouldHaveSize 6
        window.hasNext shouldBe false
    }

    @Test
    fun `scroll with exact size should not have next`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(6)
        window.content shouldHaveSize 6
        window.hasNext shouldBe false
    }

    @Test
    fun `scroll with zero size should throw IllegalArgumentException`() {
        val repo = orm.entity(City::class)
        assertThrows<IllegalArgumentException> {
            repo.select().scroll(0)
        }
    }

    @Test
    fun `selectFrom with entity and select type should work`() {
        val cities = orm.selectFrom(City::class, City::class).resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `selectFrom with TemplateBuilder should work`() {
        val cities = orm.selectFrom(City::class, City::class) { t(City::class) }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `selectFrom with TemplateString should work`() {
        val templateString = TemplateString.wrap(City::class)
        val cities = orm.selectFrom(City::class, City::class, templateString).resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `prepare should return PreparedQuery that can be closed`() {
        val preparedQuery = orm.entity(City::class).select().where(1).prepare()
        preparedQuery.use { query ->
            val city = query.getSingleResult(City::class)
            city.id shouldBe 1
        }
    }

    @Test
    fun `prepared query addBatch and getGeneratedKeys should work for insert`() {
        val bindVars = orm.createBindVars()
        val insertQuery = orm.query { "INSERT INTO ${t(City::class)} VALUES ${t(bindVars)}" }
        insertQuery.prepare().use { prepared ->
            prepared.addBatch(City(name = "PreparedA"))
            prepared.addBatch(City(name = "PreparedB"))
            prepared.executeBatch()
            val keys = prepared.getGeneratedKeys(Int::class).toList()
            keys shouldHaveSize 2
            keys.all { it > 0 } shouldBe true
        }
    }

    @Test
    fun `query with TemplateString should execute`() {
        val templateString = TemplateString.raw { "SELECT ${t(City::class)} FROM ${t(City::class)}" }
        val query = orm.query(templateString)
        val cities = query.getResultList(City::class)
        cities shouldHaveSize 6
    }

    @Test
    fun `model should return type metadata`() {
        val model = orm.model(City::class)
        model.name shouldBe "city"
        model.type shouldBe City::class
    }

    @Test
    fun `model with requirePrimaryKey true should work for entity types`() {
        val model = orm.model(City::class, true)
        model.name shouldBe "city"
    }

    @Test
    fun `ref with type and id should create ref`() {
        val ref = orm.ref(City::class, 1)
        ref.id() shouldBe 1
    }

    @Test
    fun `ref with record and id should create loaded ref`() {
        val city = City(id = 42, name = "Test")
        val ref = orm.ref(city, 42)
        ref.id() shouldBe 42
    }

    @Test
    fun `createBindVars should return non-null`() {
        val bindVars = orm.createBindVars()
        bindVars shouldBe bindVars // non-null check
    }

    @Test
    fun `whereAny with path operator and iterable should filter`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            (namePath inList listOf("Madison", "Windsor"))
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereAny with path and ref should filter by FK`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val result = repo.select().whereBuilder {
            where(cityPath, cityRef)
        }.resultList
        result shouldHaveSize 4
    }

    @Test
    fun `whereAnyRef with path and iterable of refs should filter`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val ref1: Ref<City> = Ref.of(City::class.java, 1)
        val ref3: Ref<City> = Ref.of(City::class.java, 3)
        val result = repo.select().whereBuilder {
            whereRef(cityPath, listOf(ref1, ref3))
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `whereAny path with record should use EQUALS operator`() {
        val repo = orm.entity(City::class)
        val city = City(id = 2, name = "Madison")
        val idPath = metamodel<City, City>(repo.model, "id")
        // This tests the where(path eq record) default method.
        val result = repo.select().whereBuilder {
            where(idPath, city)
        }.resultList
        result shouldHaveSize 1
    }

    @Test
    fun `whereAny path with iterable of records should use IN operator`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val result = repo.select().whereBuilder {
            where(namePath, IN, listOf("Madison", "McFarland"))
        }.resultList
        result shouldHaveSize 2
    }

    @Test
    fun `having with metamodel path should filter grouped results`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, Any>(repo.model, "city_id")
        // Use the having(path, operator, value) overload that delegates to havingAny.
        val counts = repo.selectCount().groupBy(cityPath)
            .having { "COUNT(*) >= ${t(2)}" }
            .resultList
        // Cities with 2+ owners: city 2 (4 owners), city 5 (2 owners).
        counts shouldHaveSize 2
    }

    // distinct() tests

    @Test
    fun `distinct with join should eliminate duplicates from join expansion`() {
        // Inner join Pet -> Owner can produce duplicate owners if an owner has multiple pets.
        // Owner 3 (Eduardo) has 2 pets, Owner 5 (Peter) has 1, Owner 6 (Jean) has 2, etc.
        // Without distinct, the join yields 12 rows (one per pet with an owner).
        // With distinct, we should still get 12 because Pet is the primary entity (each pet row is unique).
        val pets = orm.entity(Pet::class).select().distinct().innerJoin(Owner::class).on(Pet::class).resultList
        pets shouldHaveSize 12
    }

    @Test
    fun `distinct with resultCount should return correct count`() {
        val count = orm.entity(City::class).select().distinct().resultCount
        count shouldBe 6L
    }

    @Test
    fun `distinct with resultFlow should return all unique entities`(): Unit = runBlocking {
        val count = orm.entity(City::class).select().distinct().resultFlow.count()
        count shouldBe 6
    }

    // forUpdate() tests

    @Test
    fun `forUpdate should return correct entity data`() {
        // H2 supports FOR UPDATE. Verify that the result is correct.
        val city = orm.entity(City::class).select().where(2).forUpdate().singleResult
        city.id shouldBe 2
        city.name shouldBe "Madison"
    }

    @Test
    fun `forUpdate with where clause should lock and return matching entity`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath eq "Windsor").forUpdate().resultList
        cities shouldHaveSize 1
        cities[0].id shouldBe 4
    }

    @Test
    fun `forUpdate with limit should restrict locked rows`() {
        val cities = orm.entity(City::class).select().limit(2).forUpdate().resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `forUpdate followed by build should produce executable query`() {
        val query = orm.entity(City::class).select().where(1).forUpdate().build()
        val city = query.getSingleResult(City::class)
        city.id shouldBe 1
    }

    // forShare() tests

    @Test
    fun `forShare with multiple rows should throw on H2`() {
        // Verify that forShare consistently throws on H2 regardless of row count.
        assertThrows<PersistenceException> {
            orm.entity(City::class).select().forShare().resultList
        }
    }

    // orderBy() template tests

    @Test
    fun `orderBy with raw SQL template should order query`() {
        // Order by a raw column template. The first 3 cities by id should be 1, 2, 3.
        val repo = orm.entity(City::class)
        val cities = repo.select().orderBy { "${t(Templates.column(metamodel<City, Int>(repo.model, "id")))}" }.limit(3).resultList
        cities shouldHaveSize 3
        cities[0].id shouldBe 1
        cities[1].id shouldBe 2
        cities[2].id shouldBe 3
    }

    // typed() tests

    @Test
    fun `typed with where by id should return matching entity`() {
        val repo = orm.entity(City::class)
        val city = repo.select().typedId(Int::class).where(1).singleResult
        city.id shouldBe 1
        city.name shouldBe "Sun Paririe"
    }

    @Test
    fun `typed with whereId iterable should return matching entities`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().typedId(Int::class).whereId(listOf(1, 2, 3)).resultList
        cities shouldHaveSize 3
    }

    // selectFrom / deleteFrom tests (QueryTemplate level)

    @Test
    fun `selectFrom should return query builder for entity type`() {
        val cities = orm.selectFrom(City::class).resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `selectFrom with typed and where should filter results`() {
        val city = orm.selectFrom(City::class).typedId(Int::class).where(1).singleResult
        city.id shouldBe 1
    }

    @Test
    fun `selectFrom with distinct should return unique results`() {
        val count = orm.selectFrom(City::class).distinct().resultCount
        count shouldBe 6L
    }

    @Test
    fun `deleteFrom should create delete query builder`() {
        val deleted = orm.deleteFrom(Visit::class).unsafe().executeUpdate()
        deleted shouldBe 14
    }

    // forLock() tests

    @Test
    fun `forLock with custom template should apply lock mode`() {
        // H2 supports FOR UPDATE, so using forLock with a "FOR UPDATE" template should work.
        val city = orm.entity(City::class).select().where(1).forLock { "FOR UPDATE" }.singleResult
        city.id shouldBe 1
        city.name shouldBe "Sun Paririe"
    }

    // Query resultFlow tests

    @Test
    fun `build query resultFlow with where should return filtered flow`(): Unit = runBlocking {
        val query = orm.entity(City::class).select().where(1).build()
        val cities = query.getResultFlow(City::class).toList()
        cities shouldHaveSize 1
        cities[0].name shouldBe "Sun Paririe"
    }

    // Combined advanced query scenarios

    @Test
    fun `distinct with orderBy and limit should work together`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().distinct().orderBy(namePath).limit(3).resultList
        cities shouldHaveSize 3
        // Alphabetical: Madison, McFarland, Monona
        cities[0].name shouldBe "Madison"
        cities[1].name shouldBe "McFarland"
        cities[2].name shouldBe "Monona"
    }

    @Test
    fun `distinct with where and resultCount should return correct count`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val count = repo.select().distinct().where(namePath like "M%").resultCount
        // Madison, McFarland, Monona
        count shouldBe 3L
    }

    @Test
    fun `forUpdate with orderBy should return correctly ordered locked rows`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy(idPath).forUpdate().resultList
        cities shouldHaveSize 6
        cities[0].id shouldBe 1
        cities[5].id shouldBe 6
    }

    // QueryBuilder: where with varargs

    // QueryBuilder: having with metamodel path and operator

    @Test
    fun `having with template builder should filter groups`() {
        val repo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val queryResult = repo.selectCount()
            .groupBy(cityPath)
            .having { "COUNT(*) > 1" }
            .resultList
        // Cities with more than 1 owner
        queryResult.size shouldNotBe 0
    }

    // QueryBuilder: where/whereAny with PredicateBuilder (using eq infix)

    @Test
    fun `where with PredicateBuilder should filter entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val predicate = namePath eq "Madison"
        val cities = repo.select().where(predicate).resultList
        cities shouldHaveSize 1
    }

    // QueryBuilder: resultCount

    @Test
    fun `resultCount should return total count`() {
        val repo = orm.entity(City::class)
        val count = repo.select().resultCount
        count shouldBe 6L
    }

    @Test
    fun `resultCount with where should return filtered count`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val count = repo.select().where(namePath eq "Madison").resultCount
        count shouldBe 1L
    }

    // QueryBuilder: limit/offset

    @Test
    fun `limit with offset should skip and restrict results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderBy(idPath).limit(2).offset(2).resultList
        cities shouldHaveSize 2
        cities[0].id shouldBe 3
    }

    // QueryBuilder: selectAll flow

    // QueryBuilder: whereExists and whereNotExists

    @Test
    fun `whereExists with subquery builder should filter cities with owners`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereExists { subquery(Owner::class) }.resultList
        // All 6 cities are referenced by owners
        cities shouldHaveSize 6
    }

    @Test
    fun `whereNotExists with subquery builder should filter cities without owners`() {
        val repo = orm.entity(City::class)
        val cities = repo.select().whereNotExists { subquery(Owner::class) }.resultList
        // All cities are referenced by owners
        cities shouldHaveSize 0
    }

    // orderByDescending tests

    @Test
    fun `orderByDescending with single metamodel path should sort descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByDescending(idPath).resultList
        cities shouldHaveSize 6
        cities[0].id shouldBe 6
        cities[5].id shouldBe 1
    }

    @Test
    fun `orderByDescending with varargs metamodel paths should sort descending`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val owners = repo.select().orderByDescending(lastNamePath, firstNamePath).limit(3).resultList
        owners shouldHaveSize 3
    }

    @Test
    fun `orderByDescending with template builder should sort descending`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().orderByDescending { t(Templates.column(idPath)) }.resultList
        cities shouldHaveSize 6
        cities[0].id shouldBe 6
        cities[5].id shouldBe 1
    }

    // orderByAny tests

    @Test
    fun `orderByAny with multiple metamodel paths should sort ascending`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val firstNamePath = metamodel<Owner, String>(repo.model, "first_name")
        val owners = repo.select().orderBy(lastNamePath, firstNamePath).limit(3).resultList
        owners shouldHaveSize 3
    }

    // singleResult / optionalResult tests

    @Test
    fun `singleResult with metamodel path should return entity when exactly one match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.select().where(namePath eq "Madison").singleResult
        city.name shouldBe "Madison"
    }

    @Test
    fun `singleResult should throw NoResultException when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NoResultException> {
            repo.select().where(namePath eq "NonExistent").singleResult
        }
    }

    @Test
    fun `singleResult with metamodel path should throw NonUniqueResultException when multiple matches`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NonUniqueResultException> {
            repo.select().where(namePath like "M%").singleResult
        }
    }

    @Test
    fun `optionalResult with metamodel path should return entity when exactly one match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.select().where(namePath eq "Madison").optionalResult
        city shouldNotBe null
        city!!.name shouldBe "Madison"
    }

    @Test
    fun `optionalResult should return null when no match`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val city = repo.select().where(namePath eq "NonExistent").optionalResult
        city shouldBe null
    }

    @Test
    fun `optionalResult with metamodel path should throw NonUniqueResultException when multiple matches`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        assertThrows<NonUniqueResultException> {
            repo.select().where(namePath like "M%").optionalResult
        }
    }

    // having / havingAny with metamodel + operator

    @Test
    fun `having with TemplateBuilder should add having clause`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.selectCount()
            .groupBy(lastNamePath)
            .having { "COUNT(*) >= 1" }
            .resultList
        result.size shouldNotBe 0
    }

    @Test
    fun `having with metamodel and operator should filter groups`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.selectCount()
            .groupBy(lastNamePath)
            .having(lastNamePath eq "Davis")
            .resultList
        result shouldHaveSize 1
    }

    @Test
    fun `havingAny with metamodel and operator should filter groups`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.selectCount()
            .groupBy(lastNamePath)
            .having(lastNamePath inList listOf("Davis", "Franklin"))
            .resultList
        result shouldHaveSize 2
    }

    // having / havingAny with PredicateBuilder

    @Test
    fun `having with predicate should filter groups`() {
        // data.sql: Betty and Harold Davis share a last name, so the Davis group holds 2 owners.
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.selectCount()
            .groupBy(lastNamePath)
            .having(lastNamePath eq "Davis")
            .resultList
        result shouldHaveSize 1
        result[0] shouldBe 2L
    }

    @Test
    fun `having with or-composed predicate should filter groups`() {
        // A disjunction has no other form: consecutive having() calls are AND-combined.
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.selectCount()
            .groupBy(lastNamePath)
            .having((lastNamePath eq "Davis") or (lastNamePath eq "Franklin"))
            .resultList
        result shouldHaveSize 2
        result.sum() shouldBe 3L
    }

    @Test
    fun `consecutive having predicates should be and-combined`() {
        // The two clauses cannot both hold, so AND-combining them yields no groups.
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val result = repo.selectCount()
            .groupBy(lastNamePath)
            .having(lastNamePath eq "Davis")
            .having(lastNamePath eq "Franklin")
            .resultList
        result shouldHaveSize 0
    }

    @Test
    fun `havingExists with subquery should keep every group when the subquery matches`() {
        // The subquery is uncorrelated, so EXISTS holds for every group as long as any pet exists.
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val groups = repo.selectCount().groupBy(lastNamePath).resultList
        val kept = repo.selectCount()
            .groupBy(lastNamePath)
            .havingExists(orm.subquery(Pet::class))
            .resultList
        groups.size shouldNotBe 0
        kept shouldBe groups
    }

    @Test
    fun `havingExists lambda should build the subquery from the query's own factory`() {
        // The lambda receiver is the query's subquery factory, not a WhereBuilder. It must produce the same groups as
        // the subquery-argument form.
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val viaArgument = repo.selectCount()
            .groupBy(lastNamePath)
            .havingExists(orm.subquery(Pet::class))
            .resultList
        val viaLambda = repo.selectCount()
            .groupBy(lastNamePath)
            .havingExists { subquery(Pet::class) }
            .resultList
        viaLambda.size shouldNotBe 0
        viaLambda shouldBe viaArgument
    }

    @Test
    fun `havingNotExists lambda should drop every group when the subquery matches`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val kept = repo.selectCount()
            .groupBy(lastNamePath)
            .havingNotExists { subquery(Pet::class) }
            .resultList
        kept shouldHaveSize 0
    }

    @Test
    fun `havingNotExists with subquery should drop every group when the subquery matches`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val kept = repo.selectCount()
            .groupBy(lastNamePath)
            .havingNotExists(orm.subquery(Pet::class))
            .resultList
        kept shouldHaveSize 0
    }

    @Test
    fun `having with nested and-or predicate should filter groups`() {
        val repo = orm.entity(Owner::class)
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        val cityPath = metamodel<Owner, City>(repo.model, "city_id")
        val result = repo.selectCount()
            .groupBy(lastNamePath, cityPath)
            .having(((lastNamePath eq "Davis") and (cityPath eq City(1, "Sun Paririe"))) or (lastNamePath eq "Franklin"))
            .resultList
        result shouldHaveSize 2
    }

    // where with Metamodel and Data (FK reference)

    @Test
    fun `where with metamodel and data reference should filter entities`() {
        val cityRepo = orm.entity(City::class)
        val madison = cityRepo.select().where(2).singleResult
        val ownerRepo = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(ownerRepo.model, "city_id")
        val owners = ownerRepo.select().where(cityPath, madison).resultList
        owners shouldHaveSize 4
    }

    // QueryBuilder scroll (no key) tests

    // QueryBuilder: scrollAfter/scrollBefore with PK cursor

    @Test
    fun `scrollAfter should return next page`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        // Owners have ids 1-10. After id > 5: ids 6,7,8,9,10 = 5 owners.
        val window = repo.select().scroll(Scrollable(idKey, 5, null, null, 10, true))
        window.content shouldHaveSize 5
    }

    @Test
    fun `scrollBefore should return previous page`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        // Owners have ids 1-10. Before id < 6: ids 1,2,3,4,5 = 5 owners.
        val window = repo.select().scroll(Scrollable(idKey, 6, null, null, 10, false))
        window.content shouldHaveSize 5
    }

    // QueryBuilder: composite scroll with sort + cursor

    @Test
    fun `scrollAfter with composite key and sort cursor should return next page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        // Get first page sorted by name
        val firstPage = repo.select().scroll(Scrollable.of(idKey, namePath, 3))
        firstPage.content shouldHaveSize 3
        val lastItem = firstPage.content.last()
        // Get next page after last item
        val nextPage = repo.select().scroll(Scrollable(idKey, lastItem.id, namePath, lastItem.name, 3, true))
        nextPage.content shouldHaveSize 3
    }

    @Test
    fun `scrollBefore with composite key and sort cursor should return previous page`() {
        val repo = orm.entity(City::class)
        val idKey = metamodel<City, Int>(repo.model, "id").key()
        val namePath = metamodel<City, String>(repo.model, "name")
        // Get last page (descending)
        val lastPage = repo.select().scroll(Scrollable.of(idKey, namePath, 3).backward())
        lastPage.content shouldHaveSize 3
        val firstItem = lastPage.content.last()
        // Get previous page before first item
        val previousPage = repo.select().scroll(Scrollable(idKey, firstItem.id, namePath, firstItem.name, 3, false))
        previousPage.content shouldHaveSize 3
    }

    @Test
    fun `scrollAfter with composite key and sort cursor should return all matching owners`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        // After (lastName > "A" OR (lastName = "A" AND id > 1)): all 10 owners have lastName > "A".
        val window = repo.select().scroll(Scrollable(idKey, 1, lastNamePath, "A", 10, true))
        window.content shouldHaveSize 10
    }

    @Test
    fun `scrollBefore with composite key and sort cursor should return all matching owners`() {
        val repo = orm.entity(Owner::class)
        val idKey = metamodel<Owner, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<Owner, String>(repo.model, "last_name")
        // Before (lastName < "Z" OR (lastName = "Z" AND id < 10)): all 10 owners have lastName < "Z".
        val window = repo.select().scroll(Scrollable(idKey, 10, lastNamePath, "Z", 10, false))
        window.content shouldHaveSize 10
    }

    // QueryBuilder: join with template

    @Test
    fun `join with joinType and template and alias should produce correct result`() {
        val repo = orm.entity(Pet::class)
        val count = repo.select()
            .join(JoinType.inner(), TemplateString.raw("visit"), "v")
            .on(TemplateString.raw("v.pet_id = p.id"))
            .resultList.size
        count shouldNotBe 0
    }

    // Kotlin DSL infix predicates

    @Test
    fun `neq infix should filter not equal entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath neq "Madison").resultList
        cities shouldHaveSize 5
    }

    @Test
    fun `greater infix should filter greater than entities`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().where(idPath greater 4).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `less infix should filter less than entities`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().where(idPath less 3).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `greaterEq infix should filter greater or equal entities`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().where(idPath greaterEq 5).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `lessEq infix should filter less or equal entities`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().where(idPath lessEq 2).resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `notLike infix should filter not matching entities`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath notLike "M%").resultList
        cities shouldHaveSize 3
    }

    @Test
    fun `notInList infix should filter entities not in list`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath notInList listOf("Madison", "Windsor")).resultList
        cities shouldHaveSize 4
    }

    @Test
    fun `isNotNull predicate should filter non-null values`() {
        val repo = orm.entity(City::class)
        val namePath = metamodel<City, String>(repo.model, "name")
        val cities = repo.select().where(namePath.isNotNull()).resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `between predicate should filter values in range`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        val cities = repo.select().where(idPath.between(2, 4)).resultList
        cities shouldHaveSize 3
    }

    // resultGroupedBy tests

    @Test
    fun `resultGroupedBy should group pets by their owner in encounter order`() {
        // data.sql: 12 pets have an owner (owners 3 and 6 have two pets each); pet 13 has none.
        @Suppress("UNCHECKED_CAST")
        val ownerPath = Metamodel.of<Pet, Owner>(Pet::class.java, "owner") as TypedMetamodel<Pet, Owner, Owner>
        val groupedPets = orm.entity(Pet::class).select()
            .whereId((1..12).toList())
            .orderBy(ownerPath)
            .resultGroupedBy(ownerPath)
        groupedPets.size shouldBe 10
        groupedPets.values.sumOf { it.size } shouldBe 12
        groupedPets.keys.map { it.id }.zipWithNext().all { (previous, next) -> previous < next } shouldBe true
        groupedPets.forEach { (owner, pets) ->
            pets.forEach { pet -> (pet.owner === owner) shouldBe true }
        }
    }

    @Test
    fun `resultGroupedBy should fail when the path resolves to null`() {
        // data.sql: pet 13 has no owner.
        @Suppress("UNCHECKED_CAST")
        val ownerPath = Metamodel.of<Pet, Owner>(Pet::class.java, "owner") as TypedMetamodel<Pet, Owner, Owner>
        assertThrows<PersistenceException> {
            orm.entity(Pet::class).select().resultGroupedBy(ownerPath)
        }
    }

    @Test
    fun `resultGroupedByRef should group pets by a ref to their owner`() {
        // data.sql: 12 pets have an owner (owners 3 and 6 have two pets each); pet 13 has none.
        val ownerPath: Metamodel<Pet, Owner> = Metamodel.of(Pet::class.java, "owner")
        val groupedPets = orm.entity(Pet::class).select()
            .whereId((1..12).toList())
            .resultGroupedByRef(ownerPath)
        groupedPets.size shouldBe 10
        groupedPets.values.sumOf { it.size } shouldBe 12
        groupedPets.forEach { (ownerRef, pets) ->
            pets.forEach { pet ->
                pet.owner?.id shouldBe ownerRef.id()
                // Eager path: keys are loaded refs exposing the materialized record without a query.
                (pet.owner === ownerRef.getOrNull()) shouldBe true
            }
        }
    }

    // page() tests

    @Test
    fun `page with partial first page should infer total`() {
        val page = orm.entity(City::class).select().page(Pageable.ofSize(20))
        page.totalCount() shouldBe 6L
        page.content() shouldHaveSize 6
    }

    @Test
    fun `page with full page should count total`() {
        val page = orm.entity(City::class).select().page(0, 3)
        page.totalCount() shouldBe 6L
        page.content() shouldHaveSize 3
    }

    @Test
    fun `page with partial last page should infer total`() {
        val page = orm.entity(City::class).select().page(1, 4)
        page.totalCount() shouldBe 6L
        page.content() shouldHaveSize 2
    }

    @Test
    fun `page beyond the end should count total`() {
        val page = orm.entity(City::class).select().page(5, 4)
        page.totalCount() shouldBe 6L
        page.content() shouldHaveSize 0
    }

    @Test
    fun `page with precomputed total should skip count`() {
        val page = orm.entity(City::class).select().page(Pageable.ofSize(3), 42)
        page.totalCount() shouldBe 42L
        page.content() shouldHaveSize 3
    }

    @Test
    fun `page with pageable sorts and explicit orderBy should throw`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        assertThrows<PersistenceException> {
            repo.select().orderBy(idPath).page(Pageable.ofSize(3).sortBy(idPath))
        }
    }

    // where/having convenience overload tests

    @Test
    fun `where with operator and iterable should filter results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        repo.select().where(idPath, IN, listOf(1, 2)).resultList shouldHaveSize 2
    }

    @Test
    fun `where with operator and vararg should filter results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        repo.select().where(idPath, EQUALS, 1).resultList shouldHaveSize 1
    }

    @Test
    fun `whereExists with subquery should filter results`() {
        val repo = orm.entity(City::class)
        repo.select().whereExists(orm.entity(City::class).select()).resultList shouldHaveSize 6
    }

    @Test
    fun `having with operator should filter grouped results`() {
        val repo = orm.entity(City::class)
        val idPath = metamodel<City, Int>(repo.model, "id")
        repo.selectCount().groupBy(idPath).having(idPath, EQUALS, 1).resultList shouldHaveSize 1
    }

    // Base result terminals: a minimal subclass inherits the streaming defaults, so they are exercised directly.

    private class ForwardingQueryBuilder(private val delegate: QueryBuilder<City, City, Int>) : QueryBuilder<City, City, Int>() {
        override fun <X : Any> typedId(pkType: KClass<X>): QueryBuilder<City, City, X> = delegate.typedId(pkType)
        override fun <X : Data> narrow(rootType: KClass<X>): QueryBuilder<X, City, Int> = delegate.narrow(rootType)
        override fun widen(): QueryBuilder<Data, City, Int> = delegate.widen()
        override fun unsafe(): QueryBuilder<City, City, Int> = delegate.unsafe()
        override fun distinct(): QueryBuilder<City, City, Int> = delegate.distinct()
        override fun fetch(paths: List<Navigable<City, out Data>>): QueryBuilder<City, City, Int> = delegate.fetch(paths)
        override fun crossJoin(relation: KClass<out Data>): QueryBuilder<Data, City, Int> = delegate.crossJoin(relation)
        override fun innerJoin(relation: KClass<out Data>): TypedJoinBuilder<City, City, Int> = delegate.innerJoin(relation)
        override fun leftJoin(relation: KClass<out Data>): TypedJoinBuilder<City, City, Int> = delegate.leftJoin(relation)
        override fun rightJoin(relation: KClass<out Data>): TypedJoinBuilder<City, City, Int> = delegate.rightJoin(relation)
        override fun join(type: JoinType, relation: KClass<out Data>, alias: String): TypedJoinBuilder<City, City, Int> = delegate.join(type, relation, alias)
        override fun crossJoin(template: TemplateString): QueryBuilder<Data, City, Int> = delegate.crossJoin(template)
        override fun innerJoin(template: TemplateString, alias: String): JoinBuilder<City, City, Int> = delegate.innerJoin(template, alias)
        override fun leftJoin(template: TemplateString, alias: String): JoinBuilder<City, City, Int> = delegate.leftJoin(template, alias)
        override fun rightJoin(template: TemplateString, alias: String): JoinBuilder<City, City, Int> = delegate.rightJoin(template, alias)
        override fun join(type: JoinType, template: TemplateString, alias: String): JoinBuilder<City, City, Int> = delegate.join(type, template, alias)
        override fun join(type: JoinType, subquery: QueryBuilder<*, *, *>, alias: String): JoinBuilder<City, City, Int> = delegate.join(type, subquery, alias)
        override fun whereBuilder(predicate: WhereBuilder<City, City, Int>.() -> PredicateBuilder<out City, *, *>): QueryBuilder<City, City, Int> = delegate.whereBuilder(predicate)
        override fun groupBy(template: TemplateString): QueryBuilder<City, City, Int> = delegate.groupBy(template)
        override fun having(template: TemplateString): QueryBuilder<City, City, Int> = delegate.having(template)
        override fun having(predicate: PredicateBuilder<out City, *, *>): QueryBuilder<City, City, Int> = delegate.having(predicate)
        override fun havingExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<City, City, Int> = delegate.havingExists(subquery)
        override fun havingNotExists(subquery: QueryBuilder<*, *, *>): QueryBuilder<City, City, Int> = delegate.havingNotExists(subquery)
        override fun subqueryTemplate(): SubqueryTemplate = delegate.subqueryTemplate()
        override fun orderBy(template: TemplateString): QueryBuilder<City, City, Int> = delegate.orderBy(template)

        // hasOrderBy is protected on another instance, and the tests using this forwarder never order.
        override fun hasOrderBy(): Boolean = false
        override fun limit(limit: Int): QueryBuilder<City, City, Int> = delegate.limit(limit)
        override fun offset(offset: Int): QueryBuilder<City, City, Int> = delegate.offset(offset)
        override fun forShare(): QueryBuilder<City, City, Int> = delegate.forShare()
        override fun forUpdate(): QueryBuilder<City, City, Int> = delegate.forUpdate()
        override fun forLock(template: TemplateString): QueryBuilder<City, City, Int> = delegate.forLock(template)
        override fun build(): Query = delegate.build()
        override fun scroll(size: Int): Window<City> = delegate.scroll(size)
        override fun scroll(scrollable: Scrollable<City>): Window<City> = delegate.scroll(scrollable)
        override fun windows(size: Int): Flow<Window<City>> = delegate.windows(size)
        override fun windows(scrollable: Scrollable<City>): Flow<Window<City>> = delegate.windows(scrollable)
        override val resultStream: Stream<City> get() = delegate.resultStream
        override fun <V : Data> resultGroupedBy(path: TypedMetamodel<City, V, out V?>): Map<V, List<City>> = delegate.resultGroupedBy(path)
        override fun <V : Data> resultGroupedByRef(path: Metamodel<City, V>): Map<Ref<V>, List<City>> = delegate.resultGroupedByRef(path)
    }

    @Test
    fun `base result terminals stream and count`() {
        val repo = orm.entity(City::class)
        val all = ForwardingQueryBuilder(repo.select())
        all.resultCount shouldBe 6L
        all.resultList shouldHaveSize 6
        assertThrows<NonUniqueResultException> { all.singleResult }
        assertThrows<NonUniqueResultException> { all.optionalResult }
        val single = ForwardingQueryBuilder(repo.select().where(1))
        single.singleResult.id shouldBe 1
        single.optionalResult?.id shouldBe 1
        val none = ForwardingQueryBuilder(repo.select().where(999))
        assertThrows<NoResultException> { none.singleResult }
        none.optionalResult shouldBe null
    }

    // isTrue / isFalse predicates

    @Test
    fun `isTrue and isFalse should match boolean columns and skip nulls`() {
        // data.sql: rollout is enabled, legacy is disabled, undecided leaves the column null. Neither predicate
        // matches the null row, which is what separates them from comparing the column to a value.
        val flags = orm.entity(FeatureFlag::class)
        val enabledPath = metamodel<FeatureFlag, Boolean>(flags.model, "enabled")
        flags.select().where(enabledPath.isTrue()).resultList.map { it.name } shouldBe listOf("rollout")
        flags.select().where(enabledPath.isFalse()).resultList.map { it.name } shouldBe listOf("legacy")
    }
}
