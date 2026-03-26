package st.orm.template

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import org.springframework.test.context.junit.jupiter.SpringExtension
import st.orm.Data
import st.orm.Metamodel
import st.orm.Operator.*
import st.orm.repository.delete
import st.orm.repository.select
import st.orm.template.model.City
import st.orm.template.model.OwnerView
import st.orm.template.model.Visit

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class SqlDslTest(
    @Autowired val orm: ORMTemplate,
) {

    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // ORMTemplate: predicate-based select/delete (RepositoryLookup extensions)

    @Test
    fun `select with predicate via ORMTemplate`() {
        val namePath = metamodel<City, String>(orm.model(City::class), "name")
        val cities = orm.select(namePath eq "Madison").resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select predicate returns null when no match`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val city = orm.select(idPath eq 999).optionalResult
        city shouldBe null
    }

    @Test
    fun `select predicate singleResult`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val city = orm.select(idPath eq 2).singleResult
        city.name shouldBe "Madison"
    }

    @Test
    fun `delete with predicate via ORMTemplate`() {
        val idPath = metamodel<Visit, Int>(orm.model(Visit::class), "id")
        val affected = orm.delete(idPath eq 14)
        affected shouldBe 1
        orm.entity(Visit::class).count() shouldBe 13
    }

    @Test
    fun `delete predicate returns zero when no match`() {
        val idPath = metamodel<City, Int>(orm.model(City::class), "id")
        val affected = orm.delete(idPath eq 999)
        affected shouldBe 0
    }

    @Test
    fun `delete with gt predicate via ORMTemplate`() {
        val idPath = metamodel<Visit, Int>(orm.model(Visit::class), "id")
        val affected = orm.delete(idPath greater 13)
        affected shouldBe 1
    }

    // EntityRepository: select { } / delete { } block DSL (default methods)

    @Test
    fun `select all via EntityRepository`() {
        val cityRepository = orm.entity(City::class)
        val cities = cityRepository.select {}.resultList
        cities shouldHaveSize 6
    }

    @Test
    fun `select with auto-applied predicate via EntityRepository`() {
        val cityRepository = orm.entity(City::class)
        val idPath = metamodel<City, Int>(cityRepository.model, "id")
        val city = cityRepository.select { idPath eq 1 }.singleResult
        city.id shouldBe 1
    }

    @Test
    fun `select with where and orderBy via EntityRepository`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            where(namePath, IN, "Madison", "Windsor", "Monona")
            orderBy(namePath)
        }.resultList
        cities shouldHaveSize 3
        cities[0].name shouldBe "Madison"
        cities[1].name shouldBe "Monona"
        cities[2].name shouldBe "Windsor"
    }

    @Test
    fun `select with limit and offset via EntityRepository`() {
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
    fun `delete with auto-applied predicate via EntityRepository`() {
        val visitRepository = orm.entity(Visit::class)
        val idPath = metamodel<Visit, Int>(visitRepository.model, "id")
        val affected = visitRepository.delete { idPath eq 14 }
        affected shouldBe 1
        visitRepository.count() shouldBe 13
    }

    // ProjectionRepository: select { } block DSL (default method)

    @Test
    fun `select all via ProjectionRepository`() {
        val ownerViewRepository = orm.projection(OwnerView::class)
        val views = ownerViewRepository.select {}.resultList
        views shouldHaveSize 10
    }

    @Test
    fun `select with auto-applied predicate via ProjectionRepository`() {
        val ownerViewRepository = orm.projection(OwnerView::class)
        val idPath = metamodel<OwnerView, Int>(ownerViewRepository.model, "id")
        val view = ownerViewRepository.select { idPath eq 1 }.singleResult
        view.id shouldBe 1
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `select with orderBy and limit via ProjectionRepository`() {
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

    // SqlScope: whereAny, orderByAny, orderByDescendingAny

    @Test
    fun `select with whereAny via EntityRepository`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            whereAny(namePath eq "Madison")
        }.resultList
        cities shouldHaveSize 1
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select with orderByAny via EntityRepository`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            orderByAny(namePath)
        }.resultList
        cities shouldHaveSize 6
        cities[0].name shouldBe "Madison"
    }

    @Test
    fun `select with orderByDescendingAny via EntityRepository`() {
        val cityRepository = orm.entity(City::class)
        val namePath = metamodel<City, String>(cityRepository.model, "name")
        val cities = cityRepository.select {
            orderByDescendingAny(namePath)
        }.resultList
        cities shouldHaveSize 6
        cities[0].name shouldBe "Windsor"
    }
}
