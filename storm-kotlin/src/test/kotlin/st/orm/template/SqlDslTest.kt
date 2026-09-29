package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Data
import st.orm.JoinType
import st.orm.Metamodel
import st.orm.Operator.*
import st.orm.Ref
import st.orm.repository.entity
import st.orm.repository.removeAll
import st.orm.repository.select
import st.orm.template.model.City
import st.orm.template.model.Owner
import st.orm.template.model.OwnerView
import st.orm.template.model.Pet
import st.orm.template.model.PetType
import st.orm.template.model.Visit

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
internal open class SqlDslTest(
    @Autowired val orm: ORMTemplate,
) {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // ORMTemplate: predicate-based select/delete

    @Test
    fun `select with predicate`() {
        val namePath = metamodel<City, String>(orm.model(City::class), "name")
        val cities = orm.select(namePath eq "Madison").resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select with predicate returns null when no match`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val city = orm.select(idPath eq 999).optionalResult
        city shouldBe null
    }

    @Test
    fun `select with predicate returns single result`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val city = orm.select(idPath eq 2).singleResult
        city.name shouldBe "Madison"
    }

    @Test
    fun `removeAll with predicate`() {
        val idPath = metamodel<Visit, Int>(orm.model(Visit::class), "id")
        val affected = orm.removeAll<Visit>(idPath eq 14)
        affected shouldBe 1
        orm.entity(Visit::class).count() shouldBe 13
    }

    @Test
    fun `removeAll with predicate returns zero when no match`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val affected = orm.removeAll<City>(idPath eq 999)
        affected shouldBe 0
    }

    @Test
    fun `removeAll with greater-than predicate`() {
        val idPath = metamodel<Visit, Int>(orm.model(Visit::class), "id")
        val affected = orm.removeAll<Visit>(idPath greater 13)
        affected shouldBe 1
    }

    // EntityRepository: select { } / delete { } block DSL

    @Test
    fun `select all entities`() {
        val cityRepository = orm.entity(City::class)
        val cities = cityRepository.select().resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `select entity with where predicate`() {
        val cityRepository = orm.entity(City::class)
        val idPath = metamodel<City, Int>(cityRepository.model, "id")
        val city = cityRepository.select { where(idPath eq 1) }.singleResult
        city.id shouldBe 1
    }

    @Test
    fun `select entities with where and orderBy`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            where(namePath inList listOf("Madison", "Windsor", "Monona"))
            orderBy(namePath)
        }.resultList
        cities shouldHaveSize 3
        cities[0].name shouldBe "Madison"
        cities[1].name shouldBe "Monona"
        cities[2].name shouldBe "Windsor"
    }

    @Test
    fun `select entities matching a collection of records`() {
        val cityRepository = orm.entity(City::class)
        val subset = cityRepository.select().resultList.filter { it.id in listOf(2, 5) }
        val cities = cityRepository.select { where(subset) }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `select entities with path operator and value collection`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            where(namePath, IN, listOf("Madison", "Monona"))
        }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `select entities with path and record collection`() {
        // data.sql: City 2 (Madison) has 4 owners, City 5 (Monona) has 2 owners. Total: 6.
        val cityRepository = orm.entity(City::class)
        val ownerRepository = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(ownerRepository.model, "city_id")
        val selectedCities = cityRepository.select().resultList.filter { it.id in listOf(2, 5) }
        val owners = ownerRepository.select { where(cityPath, selectedCities) }.resultList
        owners shouldHaveSize 6
    }

    @Test
    fun `select entities with path and ref collection`() {
        // data.sql: City 2 (Madison) has 4 owners, City 5 (Monona) has 2 owners. Total: 6.
        val ownerRepository = orm.entity(Owner::class)
        val cityPath = metamodel<Owner, City>(ownerRepository.model, "city_id")
        val cityRefs = listOf(Ref.of(City::class.java, 2), Ref.of(City::class.java, 5))
        val owners = ownerRepository.select { whereRef(cityPath, cityRefs) }.resultList
        owners shouldHaveSize 6
    }

    @Test
    fun `select entities with descending order template`() {
        val cityRepository = orm.entity(City::class)
        val idPath = metamodel<City, Int>(cityRepository.model, "id")
        val cities = cityRepository.select {
            orderByDescending { "${t(Templates.column(idPath))}" }
        }.resultList
        cities shouldHaveSize 6
        cities[0].id shouldBe 6
    }

    @Test
    fun `whereRef inside whereBuilder combines with other predicates`() {
        // data.sql: City 1 is Windsor, City 2 is Madison.
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val madison = Ref.of(City::class.java, 2)
        val cities = cityRepository.select {
            whereBuilder { whereRef(madison) or (namePath eq "Windsor") }
        }.resultList
        cities shouldHaveSize 2
    }

    @Test
    fun `select entities with limit and offset`() {
        val cityRepository = orm.entity(City::class)
        val idPath = metamodel<City, Int>(cityRepository.model, "id")
        val cities = cityRepository.select {
            orderBy(idPath)
            limit(2)
            offset(1)
        }.resultList
        cities shouldHaveSize 2
        cities[0].id shouldBe 2
    }

    @Test
    fun `select entities with reified inner join`() {
        // data.sql: 13 pets total, but Pet(id=13, name='Sly') has NULL owner_id.
        // Inner join excludes pets with no matching owner, yielding 12.
        val pets = orm.entity<Pet>().select {
            innerJoin<Owner, Pet>()
        }.resultList
        pets shouldHaveSize 12
    }

    @Test
    fun `select entities with reified left join`() {
        // data.sql: 13 pets total. Left join preserves all pets, including Pet 13 with NULL owner.
        val pets = orm.entity<Pet>().select {
            leftJoin<Owner, Pet>()
        }.resultList
        pets shouldHaveSize 13
    }

    @Test
    fun `select entities with groupBy and having predicate`() {
        // City has exactly the two columns selected, so both belong in the GROUP BY.
        val cityRepository = orm.entity(City::class)
        val idPath = metamodel<City, Int>(cityRepository.model, "id")
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            groupBy(idPath, namePath)
            having((namePath eq "Madison") or (namePath eq "Monona"))
            orderBy(namePath)
        }.resultList
        cities shouldHaveSize 2
        cities[0].name shouldBe "Madison"
        cities[1].name shouldBe "Monona"
    }

    @Test
    fun `select entities with groupBy and having on a joined entity`() {
        // Grouping and having pair up: a HAVING condition on a joined column is only valid once that column is
        // grouped. data.sql: Betty Davis lives in city 1, Harold Davis in city 4.
        val cityRepository = orm.entity(City::class)
        val idPath = metamodel<City, Int>(cityRepository.model, "id")
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val lastNamePath = metamodel<Owner, String>(orm.model(Owner::class), "last_name")
        val cities = cityRepository.select {
            innerJoin<Owner, City>()
            groupBy(idPath, namePath)
            groupBy(lastNamePath)
            having(lastNamePath eq "Davis")
            orderBy(idPath)
        }.resultList
        cities shouldHaveSize 2
        cities[0].id shouldBe 1
        cities[1].id shouldBe 4
    }

    @Test
    fun `a join inside the block stays queryable after it`() {
        // The block returns the relaxed builder, so the chained continuation can reference the joined entity.
        val lastNamePath = metamodel<Owner, String>(orm.model(Owner::class), "last_name")
        val cities = orm.entity(City::class).select {
            innerJoin<Owner, City>()
        }.where(lastNamePath eq "Davis")
            .resultList
        // data.sql: Betty Davis lives in city 1, Harold Davis in city 4.
        cities shouldHaveSize 2
    }

    @Test
    fun `narrow narrows the block result back to the entity`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val cities = orm.entity(City::class).select {
            innerJoin<Owner, City>()
            where(idPath eq 1)
        }.narrow<City>()
            .resultList
        cities.map { it.id }.toSet() shouldBe setOf(1)
    }

    @Test
    fun `a path on an entity outside the query fails with a descriptive error`() {
        // The block no longer rejects foreign paths at compile time; resolution reports them at query time.
        val visitDatePath = metamodel<Visit, java.time.LocalDate>(orm.model(Visit::class), "visit_date")
        val exception = org.junit.jupiter.api.assertThrows<st.orm.PersistenceException> {
            orm.entity(City::class).select {
                where(visitDatePath eq java.time.LocalDate.now())
            }.resultList
        }
        exception.message!! shouldContain "Visit is not part of this query rooted at City"
    }

    // Clause vocabulary parity with the chained builder (#397).

    @Test
    fun `select entities with whereId in the block`() {
        val cities = orm.entity(City::class).select {
            whereId(listOf(1, 3))
        }.resultList
        cities.map { it.id }.toSet() shouldBe setOf(1, 3)
    }

    @Test
    fun `select entities with whereRef in the block`() {
        val ref1: Ref<City> = Ref.of(City::class.java, 1)
        val ref3: Ref<City> = Ref.of(City::class.java, 3)
        val cities = orm.entity(City::class).select {
            whereRef(listOf(ref1, ref3))
        }.resultList
        cities.map { it.id }.toSet() shouldBe setOf(1, 3)
    }

    @Test
    fun `select entity with forLock template in the block`() {
        val city = orm.entity(City::class).select {
            where(1)
            forLock { "FOR UPDATE" }
        }.singleResult
        city.id shouldBe 1
    }

    @Test
    fun `select entities with aliased class join in the block`() {
        // data.sql: 12 of 13 pets have an owner.
        val pets = orm.entity<Pet>().select {
            join(JoinType.inner(), Owner::class, "o", Pet::class)
        }.resultList
        pets shouldHaveSize 12
    }

    @Test
    fun `select entities with aliased class join and template ON in the block`() {
        val pets = orm.entity<Pet>().select {
            join(JoinType.inner(), Owner::class, "o") { "o.id = ${t(Templates.alias(Pet::class))}.owner_id" }
        }.resultList
        pets shouldHaveSize 12
    }

    @Test
    fun `select entities with class join and template ON in the block`() {
        // City ids are 1-6, pet type ids are 0-5: the matching ids are 1-5.
        val count = orm.entity(City::class).select {
            innerJoin<PetType> { "${t(Templates.alias(PetType::class))}.id = ${t(Templates.alias(City::class))}.id" }
        }.resultCount
        count shouldBe 5L
    }

    @Test
    fun `select entities with template join in the block`() {
        // City ids are 1-6, pet type ids are 0-5: the matching ids are 1-5.
        val count = orm.entity(City::class).select {
            innerJoin({ "pet_type" }, "pt") { "pt.id = ${t(Templates.alias(City::class))}.id" }
        }.resultCount
        count shouldBe 5L
    }

    @Test
    fun `select entities with JoinType template join in the block`() {
        // A left join keeps all six cities whether or not a pet type id matches.
        val count = orm.entity(City::class).select {
            join(JoinType.left(), { "pet_type" }, "pt") { "pt.id = ${t(Templates.alias(City::class))}.id" }
        }.resultCount
        count shouldBe 6L
    }

    @Test
    fun `select entities with subquery join in the block`() {
        // A self-join through the subquery matches every city exactly once.
        val subquery = orm.entity(City::class).select()
        val count = orm.entity(City::class).select {
            join(JoinType.inner(), subquery, "sub") { "sub.id = ${t(Templates.alias(City::class))}.id" }
        }.resultCount
        count shouldBe 6L
    }

    @Test
    fun `select entities with template cross join in the block`() {
        // 6 cities times 6 pet types is 36 rows.
        val count = orm.entity(City::class).select {
            crossJoin { "pet_type" }
        }.resultCount
        count shouldBe 36L
    }

    @Test
    fun `delete entity with where predicate`() {
        val visitRepository = orm.entity(Visit::class)
        val idPath = metamodel<Visit, Int>(visitRepository.model, "id")
        val affected = visitRepository.delete { where(idPath eq 14) }.executeUpdate()
        affected shouldBe 1
        visitRepository.count() shouldBe 13
    }

    // ProjectionRepository: select { } block DSL

    @Test
    fun `select all projections`() {
        val ownerViewRepository = orm.projection(OwnerView::class)
        val views = ownerViewRepository.select().resultList
        views shouldHaveSize 10
    }

    @Test
    fun `select projection with where predicate`() {
        val ownerViewRepository = orm.projection(OwnerView::class)
        val idPath = metamodel<OwnerView, Int>(ownerViewRepository.model, "id")
        val view = ownerViewRepository.select { where(idPath eq 1) }.singleResult
        view.id shouldBe 1
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `select projections with orderBy and limit`() {
        val ownerViewRepository = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(ownerViewRepository.model, "last_name")
        val views = ownerViewRepository.select {
            orderBy(lastNamePath)
            limit(3)
        }.resultList
        views shouldHaveSize 3
        views[0].lastName shouldBe "Black"
        views[1].lastName shouldBe "Coleman"
        views[2].lastName shouldBe "Davis"
    }

    // SqlScope: relaxed where and orderBy forms

    @Test
    fun `select with a relaxed where predicate`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            where(namePath eq "Madison")
        }.resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select with a relaxed orderBy`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            orderBy(namePath)
        }.resultList
        cities shouldHaveSize 6
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select with a relaxed orderByDescending`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            orderByDescending(namePath)
        }.resultList
        cities shouldHaveSize 6
        cities[0].name shouldBe "Windsor"
    }

    // Clause vocabulary parity with the chained builder (#397): the WHERE forms.

    @Test
    fun `select entity with where record in the block`() {
        val cities = orm.entity(City::class).select {
            where(City(id = 3, name = "McFarland"))
        }.resultList
        cities.map { it.id } shouldBe listOf(3)
    }

    @Test
    fun `select entity with where ref in the block`() {
        val ref: Ref<City> = Ref.of(City::class.java, 3)
        val cities = orm.entity(City::class).select {
            where(ref)
        }.resultList
        cities.map { it.id } shouldBe listOf(3)
    }

    @Test
    fun `select entities with where path operator and values in the block`() {
        val namePath = metamodel<City, String>(orm.model(City::class), "name")
        val cities = orm.entity(City::class).select {
            where(namePath, IN, "Madison", "Monona")
            orderBy(namePath)
        }.resultList
        cities.map { it.name } shouldBe listOf("Madison", "Monona")
    }

    @Test
    fun `select entities with where path and record in the block`() {
        // The record identifies the row by its key. data.sql: city 2 (Madison) has four owners.
        val cityPath = metamodel<Owner, City>(orm.model(Owner::class), "city_id")
        val owners = orm.entity(Owner::class).select {
            where(cityPath, City(id = 2, name = "Madison"))
        }.resultList
        owners shouldHaveSize 4
    }

    @Test
    fun `select entities with where path and ref in the block`() {
        val cityPath = metamodel<Owner, City>(orm.model(Owner::class), "city_id")
        val owners = orm.entity(Owner::class).select {
            where(cityPath, Ref.of(City::class.java, 2))
        }.resultList
        owners shouldHaveSize 4
    }

    @Test
    fun `select entities with where template in the block`() {
        val cities = orm.entity(City::class).select {
            where { "${t(Templates.alias(City::class))}.id > ${t(4)}" }
        }.resultList
        cities.map { it.id }.toSet() shouldBe setOf(5, 6)
    }

    @Test
    fun `select entities with whereBuilder in the block`() {
        val namePath = metamodel<City, String>(orm.model(City::class), "name")
        val cities = orm.entity(City::class).select {
            whereBuilder { (namePath eq "Madison") or (namePath eq "Monona") }
            orderBy(namePath)
        }.resultList
        cities.map { it.name } shouldBe listOf("Madison", "Monona")
    }

    @Test
    fun `select entities with whereExists in the block matches the chained form`() {
        // Both forms of the block clause, the subquery argument and the lambda, produce the chained builder's rows.
        val chained = orm.entity(Owner::class).select().whereExists { subquery(Pet::class) }.resultList
        val viaSubquery = orm.entity(Owner::class).select {
            whereExists(orm.subquery(Pet::class))
        }.resultList
        val viaLambda = orm.entity(Owner::class).select {
            whereExists { subquery(Pet::class) }
        }.resultList
        chained shouldHaveSize 10
        viaSubquery shouldBe chained
        viaLambda shouldBe chained
    }

    @Test
    fun `select entities with whereNotExists in the block matches the chained form`() {
        // data.sql: every city has an owner, so no city survives NOT EXISTS.
        val chained = orm.entity(City::class).select().whereNotExists { subquery(Owner::class) }.resultList
        val viaSubquery = orm.entity(City::class).select {
            whereNotExists(orm.subquery(Owner::class))
        }.resultList
        val viaLambda = orm.entity(City::class).select {
            whereNotExists { subquery(Owner::class) }
        }.resultList
        chained shouldHaveSize 0
        viaSubquery shouldBe chained
        viaLambda shouldBe chained
    }

    // Clause vocabulary parity with the chained builder (#397): the join forms.

    @Test
    fun `select entities with class joins in the block`() {
        // data.sql: 12 of 13 pets have an owner; a left join keeps the thirteenth.
        orm.entity<Pet>().select { innerJoin(Owner::class, Pet::class) }.resultList shouldHaveSize 12
        orm.entity<Pet>().select { leftJoin(Owner::class, Pet::class) }.resultList shouldHaveSize 13
        // Every owner has a pet, so the right join yields the twelve owned pets.
        orm.entity<Pet>().select { rightJoin(Owner::class, Pet::class) }.resultList shouldHaveSize 12
        orm.entity<Pet>().select { rightJoin<Owner, Pet>() }.resultList shouldHaveSize 12
    }

    @Test
    fun `select entities with class joins and template ON in the block`() {
        // City ids are 1-6, pet type ids are 0-5: five ids match; a left join keeps all six cities.
        val cityAlias = Templates.alias(City::class)
        val petTypeAlias = Templates.alias(PetType::class)
        orm.entity(City::class).select {
            innerJoin(PetType::class) { "${t(petTypeAlias)}.id = ${t(cityAlias)}.id" }
        }.resultCount shouldBe 5L
        orm.entity(City::class).select {
            leftJoin<PetType> { "${t(petTypeAlias)}.id = ${t(cityAlias)}.id" }
        }.resultCount shouldBe 6L
        orm.entity(City::class).select {
            leftJoin(PetType::class) { "${t(petTypeAlias)}.id = ${t(cityAlias)}.id" }
        }.resultCount shouldBe 6L
        // A right join keeps every pet type, the six of them, whether or not a city id matches. City and PetType
        // are unrelated, so each alias resolves to one table; a join onto an entity the root already references
        // needs the path that pins it instead.
        orm.entity(City::class).select {
            rightJoin<PetType> { "${t(petTypeAlias)}.id = ${t(cityAlias)}.id" }
        }.resultCount shouldBe 6L
        orm.entity(City::class).select {
            rightJoin(PetType::class) { "${t(petTypeAlias)}.id = ${t(cityAlias)}.id" }
        }.resultCount shouldBe 6L
    }

    @Test
    fun `select entities with class cross join in the block`() {
        // 6 cities times 6 cities is 36 rows, in the reified and the KClass form alike.
        orm.entity(City::class).select { crossJoin<City>() }.resultCount shouldBe 36L
        orm.entity(City::class).select { crossJoin(City::class) }.resultCount shouldBe 36L
    }

    // Clause vocabulary parity with the chained builder (#397): grouping, ordering and the remaining modifiers.

    @Test
    fun `select entities with having path operator and values in the block`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val namePath = metamodel<City, String>(orm.model(City::class), "name")
        val cities = orm.entity(City::class).select {
            groupBy(idPath, namePath)
            having(namePath, IN, "Madison", "Monona")
            orderBy(namePath)
        }.resultList
        cities.map { it.name } shouldBe listOf("Madison", "Monona")
    }

    @Test
    fun `select entities with template groupBy having and orderBy in the block`() {
        val cityAlias = Templates.alias(City::class)
        val cities = orm.entity(City::class).select {
            groupBy { "${t(cityAlias)}.id, ${t(cityAlias)}.name" }
            having { "${t(cityAlias)}.id > ${t(4)}" }
            orderBy { "${t(cityAlias)}.name DESC" }
        }.resultList
        cities.map { it.id } shouldBe listOf(6, 5)
    }

    @Test
    fun `select entities with havingExists and havingNotExists in the block`() {
        // The subquery correlates on the grouped city key: every city has an owner, so EXISTS keeps all six groups
        // and NOT EXISTS keeps none, through the subquery argument and the lambda alike.
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val namePath = metamodel<City, String>(orm.model(City::class), "name")
        orm.entity(City::class).select {
            groupBy(idPath, namePath)
            havingExists(orm.subquery(Owner::class))
        }.resultList shouldHaveSize 6
        orm.entity(City::class).select {
            groupBy(idPath, namePath)
            havingExists { subquery(Owner::class) }
        }.resultList shouldHaveSize 6
        orm.entity(City::class).select {
            groupBy(idPath, namePath)
            havingNotExists(orm.subquery(Owner::class))
        }.resultList shouldHaveSize 0
        orm.entity(City::class).select {
            groupBy(idPath, namePath)
            havingNotExists { subquery(Owner::class) }
        }.resultList shouldHaveSize 0
    }

    @Test
    fun `select entities with distinct in the block`() {
        // Six distinct cities stay six; the modifier reaches the statement.
        val cities = orm.entity(City::class).select { distinct() }.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `select entity with forUpdate in the block`() {
        val city = orm.entity(City::class).select {
            where(1)
            forUpdate()
        }.singleResult
        city.id shouldBe 1
    }

    @Test
    fun `select entity with a hint in the block reaches the builder`() {
        // H2 has no hint syntax, so the hint is left out; a refused hint proves the block passes it to the builder.
        val city = orm.entity(City::class).select {
            where(1)
            hint("NO_MERGE(recent)")
        }.singleResult
        city.id shouldBe 1
        org.junit.jupiter.api.assertThrows<st.orm.PersistenceException> {
            orm.entity(City::class).select { hint("NO_MERGE(recent) */") }
        }
    }

    @Test
    fun `select entity with forShare in the block reaches the statement`() {
        // H2 rejects FOR SHARE, so the failure proves the modifier is part of the statement, as it does for the
        // chained builder.
        org.junit.jupiter.api.assertThrows<st.orm.PersistenceException> {
            orm.entity(City::class).select {
                where(1)
                forShare()
            }.singleResult
        }
    }

    @Test
    fun `delete entities with unsafe in the block`() {
        // data.sql inserts 14 visits; a delete without a WHERE clause needs unsafe(), as in the chained builder.
        val affected = orm.entity(Visit::class).delete { unsafe() }.executeUpdate()
        affected shouldBe 14
        orm.entity(Visit::class).count() shouldBe 0
    }
}
