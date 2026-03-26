package st.orm.template

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
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
import st.orm.Operator.*
import st.orm.Ref
import st.orm.Scrollable
import st.orm.repository.*
import st.orm.template.model.*

@ExtendWith(SpringExtension::class)
@ContextConfiguration(classes = [IntegrationConfig::class])
@Sql("/data.sql")
open class ProjectionRepositoryTest(
    @Autowired val orm: ORMTemplate,
) {
    @Suppress("UNCHECKED_CAST")
    private fun <T : Data, V> metamodel(model: Model<*, *>, columnName: String): Metamodel<T, V> = model.columns.first { it.name == columnName }.metamodel as Metamodel<T, V>

    // ProjectionRepository: findAll

    @Test
    fun `findAll should return all owner views`() {
        // data.sql inserts exactly 10 owners (ids 1-10). OwnerView is backed by owner_view.
        val repo = orm.projection(OwnerView::class)
        val views = repo.findAll()
        views shouldHaveSize 10
    }

    // ProjectionRepository: findById

    @Test
    fun `findById should return owner view when exists`() {
        // data.sql: Owner(id=1, first_name='Betty', last_name='Davis', ...).
        val repo = orm.projection(OwnerView::class)
        val view = repo.findById(1)
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findById should return null when not exists`() {
        val repo = orm.projection(OwnerView::class)
        repo.findById(999).shouldBeNull()
    }

    // ProjectionRepository: findByRef

    @Test
    fun `findByRef should return owner view for valid ref`() {
        val repo = orm.projection(OwnerView::class)
        val ref = repo.ref(1)
        val view = repo.findByRef(ref)
        view.shouldNotBeNull()
        view.id shouldBe 1
    }

    @Test
    fun `findByRef should return null for non-existent ref`() {
        val repo = orm.projection(OwnerView::class)
        val ref = repo.ref(999)
        repo.findByRef(ref).shouldBeNull()
    }

    // ProjectionRepository: getById

    @Test
    fun `getById should return owner view when exists`() {
        // data.sql: Owner(id=2, first_name='George', last_name='Franklin', ...).
        val repo = orm.projection(OwnerView::class)
        val view = repo.getById(2)
        view.firstName shouldBe "George"
    }

    @Test
    fun `getById should throw NoResultException when not exists`() {
        val repo = orm.projection(OwnerView::class)
        assertThrows<NoResultException> {
            repo.getById(999)
        }
    }

    // ProjectionRepository: getByRef

    @Test
    fun `getByRef should return owner view when exists`() {
        // data.sql: Owner(id=3, first_name='Eduardo', last_name='Rodriquez', ...).
        val repo = orm.projection(OwnerView::class)
        val ref = repo.ref(3)
        val view = repo.getByRef(ref)
        view.firstName shouldBe "Eduardo"
    }

    @Test
    fun `getByRef should throw NoResultException when not exists`() {
        val repo = orm.projection(OwnerView::class)
        val ref = repo.ref(999)
        assertThrows<NoResultException> {
            repo.getByRef(ref)
        }
    }

    // ProjectionRepository: count and exists

    @Test
    fun `count should return total number of owner views`() {
        // data.sql inserts 10 owners, so owner_view should also have 10 rows.
        val repo = orm.projection(OwnerView::class)
        repo.count() shouldBe 10
    }

    @Test
    fun `exists should return true when projections exist`() {
        val repo = orm.projection(OwnerView::class)
        repo.exists() shouldBe true
    }

    @Test
    fun `existsById should return true for existing owner view`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsById(1) shouldBe true
    }

    @Test
    fun `existsById should return false for non-existent owner view`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsById(999) shouldBe false
    }

    @Test
    fun `existsByRef should return true for existing owner view ref`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsByRef(repo.ref(1)) shouldBe true
    }

    @Test
    fun `existsByRef should return false for non-existent owner view ref`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsByRef(repo.ref(999)) shouldBe false
    }

    // ProjectionRepository: findAllById and findAllByRef

    @Test
    fun `findAllById should return matching owner views`() {
        val repo = orm.projection(OwnerView::class)
        val views = repo.findAllById(listOf(1, 2, 3))
        views shouldHaveSize 3
    }

    @Test
    fun `findAllById with non-existent ids should return only existing`() {
        val repo = orm.projection(OwnerView::class)
        val views = repo.findAllById(listOf(1, 999))
        views shouldHaveSize 1
        views.first().id shouldBe 1
    }

    @Test
    fun `findAllByRef should return matching owner views`() {
        val repo = orm.projection(OwnerView::class)
        val refs = listOf(repo.ref(1), repo.ref(2))
        val views = repo.findAllByRef(refs)
        views shouldHaveSize 2
    }

    // ProjectionRepository: ref operations

    @Test
    fun `ref from id should create ref with correct id`() {
        val repo = orm.projection(OwnerView::class)
        val ref = repo.ref(1)
        ref.shouldNotBeNull()
    }

    @Test
    fun `findAllRef should return all refs`() {
        val repo = orm.projection(OwnerView::class)
        val refs = repo.findAllRef()
        refs shouldHaveSize 10
    }

    @Test
    fun `selectAllRef should return flow of all refs`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = repo.selectRef().resultFlow.toList()
        refs shouldHaveSize 10
    }

    // ProjectionRepository: Flow operations

    @Test
    fun `selectAll should return flow of all owner views`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        repo.select().resultFlow.count() shouldBe 10
    }

    @Test
    fun `selectById with flow of ids should return matching owner views`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val ids = flowOf(1, 2, 3)
        val views = repo.selectById(ids).toList()
        views shouldHaveSize 3
    }

    @Test
    fun `selectByRef with flow of refs should return matching owner views`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = flowOf(repo.ref(1), repo.ref(2))
        val views = repo.selectByRef(refs).toList()
        views shouldHaveSize 2
    }

    @Test
    fun `selectById with custom chunk size should return matching owner views`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val ids = (1..10).asFlow()
        val views = repo.selectById(ids, 3).toList()
        views shouldHaveSize 10
    }

    @Test
    fun `selectByRef with custom chunk size should return matching owner views`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = (1..10).map { repo.ref(it) }.asFlow()
        val views = repo.selectByRef(refs, 3).toList()
        views shouldHaveSize 10
    }

    @Test
    fun `selectById with empty flow should return empty flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val views = repo.selectById(emptyFlow()).toList()
        views.shouldBeEmpty()
    }

    @Test
    fun `selectByRef with empty flow should return empty flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val views = repo.selectByRef(emptyFlow()).toList()
        views.shouldBeEmpty()
    }

    // ProjectionRepository: countById and countByRef

    @Test
    fun `countById should count matching projections from flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.countById(flowOf(1, 2, 3))
        count shouldBe 3
    }

    @Test
    fun `countById with empty flow should return zero`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.countById(emptyFlow())
        count shouldBe 0
    }

    @Test
    fun `countByRef should count matching projections from flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = flowOf(repo.ref(1), repo.ref(2))
        val count = repo.countByRef(refs)
        count shouldBe 2
    }

    @Test
    fun `countByRef with empty flow should return zero`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.countByRef(emptyFlow())
        count shouldBe 0
    }

    // ProjectionRepository: predicate-based methods

    @Test
    fun `findAll with predicate should filter owner views`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.findAll(firstNamePath eq "Betty")
        views shouldHaveSize 1
        views.first().firstName shouldBe "Betty"
    }

    @Test
    fun `findAll with predicate returning empty should return empty list`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.findAll(firstNamePath eq "NonExistent")
        views.shouldBeEmpty()
    }

    @Test
    fun `find with predicate should return matching owner view`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.find(firstNamePath eq "George")
        view.shouldNotBeNull()
        view.firstName shouldBe "George"
    }

    @Test
    fun `find with predicate should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.find(firstNamePath eq "NonExistent")
        view.shouldBeNull()
    }

    @Test
    fun `get with predicate should return matching owner view`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.get(firstNamePath eq "Eduardo")
        view.firstName shouldBe "Eduardo"
    }

    @Test
    fun `get with predicate should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        assertThrows<NoResultException> {
            repo.get(firstNamePath eq "NonExistent")
        }
    }

    @Test
    fun `count with predicate should count matching owner views`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val count = repo.count(firstNamePath eq "Betty")
        count shouldBe 1
    }

    @Test
    fun `exists with predicate should return true for matching owner view`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.exists(firstNamePath eq "Betty") shouldBe true
    }

    @Test
    fun `exists with predicate should return false when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.exists(firstNamePath eq "NonExistent") shouldBe false
    }

    @Test
    fun `select with predicate should return flow of matching owner views`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val count = repo.select().where(firstNamePath eq "Betty").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `findAllRef with predicate should return refs of matching owner views`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val refs = repo.findAllRef(firstNamePath eq "Betty")
        refs shouldHaveSize 1
    }

    @Test
    fun `findRef with predicate should return ref for matching owner view`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRef(firstNamePath eq "George")
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRef with predicate should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRef(firstNamePath eq "NonExistent")
        ref.shouldBeNull()
    }

    @Test
    fun `getRef with predicate should return ref for matching owner view`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.getRef(firstNamePath eq "Eduardo")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with predicate should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        assertThrows<NoResultException> {
            repo.getRef(firstNamePath eq "NonExistent")
        }
    }

    @Test
    fun `selectRef with predicate should return flow of refs`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val count = repo.selectRef().where(firstNamePath eq "Betty").resultFlow.count()
        count shouldBe 1
    }

    // RepositoryLookup extension methods for projections

    @Test
    fun `orm findAll reified should return all owner views`() {
        val views = orm.findAll<OwnerView>()
        views shouldHaveSize 10
    }

    @Test
    fun `orm selectAll reified should return all owner views as flow`(): Unit = runBlocking {
        orm.select<OwnerView>().resultFlow.count() shouldBe 10
    }

    @Test
    fun `orm findAllRef reified should return all owner view refs`() {
        val refs = orm.findAllRef<OwnerView>()
        refs shouldHaveSize 10
    }

    @Test
    fun `orm selectAllRef reified should return all owner view refs as flow`(): Unit = runBlocking {
        orm.selectRef<OwnerView>().resultFlow.count() shouldBe 10
    }

    @Test
    fun `orm countAll reified should return total count of owner views`() {
        orm.countAll<OwnerView>() shouldBe 10
    }

    @Test
    fun `orm exists reified should return true when owner views exist`() {
        orm.exists<OwnerView>() shouldBe true
    }

    // RepositoryLookup predicate extension methods for projections

    @Test
    fun `orm findAll with predicate should filter owner views`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val views = orm.findAll<OwnerView>(firstNamePath eq "Betty")
        views shouldHaveSize 1
        views.first().firstName shouldBe "Betty"
    }

    @Test
    fun `orm find with predicate should return single matching owner view`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val view = orm.find<OwnerView>(firstNamePath eq "George")
        view.shouldNotBeNull()
        view.firstName shouldBe "George"
    }

    @Test
    fun `orm find with predicate should return null when no match`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val view = orm.find<OwnerView>(firstNamePath eq "Nonexistent")
        view.shouldBeNull()
    }

    @Test
    fun `orm get with predicate should return matching owner view`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val view = orm.get<OwnerView>(firstNamePath eq "Eduardo")
        view.firstName shouldBe "Eduardo"
    }

    @Test
    fun `orm get with predicate should throw when no match`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        assertThrows<NoResultException> {
            orm.get<OwnerView>(firstNamePath eq "Nonexistent")
        }
    }

    @Test
    fun `orm count with predicate should count matching owner views`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val count = orm.count<OwnerView>(firstNamePath eq "Betty")
        count shouldBe 1
    }

    @Test
    fun `orm exists with predicate should return true for match`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        orm.exists<OwnerView>(firstNamePath eq "Betty") shouldBe true
    }

    @Test
    fun `orm exists with predicate should return false for no match`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        orm.exists<OwnerView>(firstNamePath eq "Nonexistent") shouldBe false
    }

    @Test
    fun `orm select with predicate should return matching flow`(): Unit = runBlocking {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val count = orm.select<OwnerView>().where(firstNamePath eq "Betty").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `orm findAllRef with predicate should return refs of matching owner views`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val refs = orm.findAllRef<OwnerView>(firstNamePath eq "Betty")
        refs shouldHaveSize 1
    }

    // ProjectionRepository: findBy/getBy with Metamodel

    @Test
    fun `findBy with field and value should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.findBy(firstNamePath, "Betty")
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findBy with field and value should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.findBy(firstNamePath, "NonExistent")
        view.shouldBeNull()
    }

    @Test
    fun `findAllBy with field and single value should return matching projections`() {
        // data.sql: Two owners have last_name 'Davis': Betty Davis (id=1) and Harold Davis (id=4).
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val views = repo.findAllBy(lastNamePath, "Davis")
        views shouldHaveSize 2
    }

    @Test
    fun `findAllBy with field and iterable values should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.findAllBy(firstNamePath, listOf("Betty", "George", "Eduardo"))
        views shouldHaveSize 3
    }

    @Test
    fun `getBy with field and value should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.getBy(firstNamePath, "Betty")
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getBy with field and value should throw NoResultException when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        assertThrows<NoResultException> {
            repo.getBy(firstNamePath, "NonExistent")
        }
    }

    // ProjectionRepository: countBy/existsBy with Metamodel

    @Test
    fun `countBy with field and value should count matching projections`() {
        // data.sql: Two owners have last_name 'Davis': Betty Davis (id=1) and Harold Davis (id=4).
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        repo.countBy(lastNamePath, "Davis") shouldBe 2
    }

    @Test
    fun `existsBy with field and value should return true when match exists`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.existsBy(firstNamePath, "Betty") shouldBe true
    }

    @Test
    fun `existsBy with field and value should return false when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.existsBy(firstNamePath, "NonExistent") shouldBe false
    }

    // ProjectionRepository: selectBy with Metamodel

    @Test
    fun `selectBy with field and value should return matching projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.selectBy(firstNamePath, "Betty").toList()
        views shouldHaveSize 1
        views.first().firstName shouldBe "Betty"
    }

    @Test
    fun `selectBy with field and iterable values should return matching projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.selectBy(firstNamePath, listOf("Betty", "George")).toList()
        views shouldHaveSize 2
    }

    // ProjectionRepository: findRefBy/getRefBy with Metamodel

    @Test
    fun `findRefBy with field and value should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRefBy<Any, Any, String>(firstNamePath, "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRefBy with field and value should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRefBy<Any, Any, String>(firstNamePath, "NonExistent")
        ref.shouldBeNull()
    }

    @Test
    fun `findAllRefBy with field and value should return refs of matching projections`() {
        // data.sql: Two owners have last_name 'Davis': Betty Davis (id=1) and Harold Davis (id=4).
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val refs = repo.findAllRefBy(lastNamePath, "Davis")
        refs shouldHaveSize 2
    }

    @Test
    fun `getRefBy with field and value should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.getRefBy(firstNamePath, "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRefBy with field and value should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        assertThrows<NoResultException> {
            repo.getRefBy(firstNamePath, "NonExistent")
        }
    }

    @Test
    fun `selectRefBy with field and value should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val refs = repo.selectRefBy(firstNamePath, "Betty").toList()
        refs shouldHaveSize 1
    }

    // ProjectionRepository: PredicateBuilder direct-call variants

    @Test
    fun `findAll with direct PredicateBuilder should filter projections`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.findAll(firstNamePath eq "Betty")
        views shouldHaveSize 1
        views.first().firstName shouldBe "Betty"
    }

    @Test
    fun `find with direct PredicateBuilder should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.find(firstNamePath eq "Betty")
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `get with direct PredicateBuilder should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.get(firstNamePath eq "Betty")
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findAllRef with direct PredicateBuilder should return refs`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val refs = repo.findAllRef(firstNamePath eq "Betty")
        refs shouldHaveSize 1
    }

    @Test
    fun `findRef with direct PredicateBuilder should return ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRef(firstNamePath eq "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with direct PredicateBuilder should return ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.getRef(firstNamePath eq "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `select with direct PredicateBuilder should return flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val count = repo.select().where(firstNamePath eq "Betty").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `selectRef with direct PredicateBuilder should return flow of refs`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val count = repo.selectRef().where(firstNamePath eq "Betty").resultFlow.count()
        count shouldBe 1
    }

    @Test
    fun `count with direct PredicateBuilder should count matching`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.count(firstNamePath eq "Betty") shouldBe 1
    }

    @Test
    fun `exists with direct PredicateBuilder should return true for match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.exists(firstNamePath eq "Betty") shouldBe true
    }

    @Test
    fun `findBy with field and ref value should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val view = repo.findBy(cityPath, cityRef)
        view.shouldNotBeNull()
        // City 1 has one owner: Betty.
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findBy with field and ref value should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        val view = repo.findBy(cityPath, cityRef)
        view.shouldBeNull()
    }

    @Test
    fun `getBy with field and ref value should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val view = repo.getBy(cityPath, cityRef)
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getBy with field and ref value should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        assertThrows<NoResultException> {
            repo.getBy(cityPath, cityRef)
        }
    }

    @Test
    fun `findAllBy with field and ref value should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val views = repo.findAllBy(cityPath, cityRef)
        // City 2 has 4 owners.
        views shouldHaveSize 4
    }

    @Test
    fun `findAllByRef with metamodel and iterable of refs should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 3))
        val views = repo.findAllByRef(cityPath, refs)
        // City 1: Betty, City 3: Eduardo
        views shouldHaveSize 2
    }

    @Test
    fun `selectBy with field and ref value should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val count = repo.selectBy(cityPath, cityRef).count()
        count shouldBe 4
    }

    @Test
    fun `selectByRef with metamodel and iterable of refs should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 3))
        val count = repo.selectByRef(cityPath, refs).count()
        count shouldBe 2
    }

    @Test
    fun `findRefBy with field and ref value should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val ref = repo.findRefBy(cityPath, cityRef)
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRefBy with field and ref value should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        val ref = repo.findRefBy(cityPath, cityRef)
        ref.shouldBeNull()
    }

    @Test
    fun `selectRefBy with field and ref value should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val refs = repo.selectRefBy(cityPath, cityRef).toList()
        refs shouldHaveSize 1
    }

    @Test
    fun `countBy with field and ref value should count matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        repo.countBy(cityPath, cityRef) shouldBe 4
    }

    @Test
    fun `existsBy with field and ref value should return true when match exists`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        repo.existsBy(cityPath, cityRef) shouldBe true
    }

    @Test
    fun `existsBy with field and ref value should return false when no match`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 999)
        repo.existsBy(cityPath, cityRef) shouldBe false
    }

    @Test
    fun `scroll should return first page of projections`() {
        val repo = orm.projection(OwnerView::class)
        val idPath = metamodel<OwnerView, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(3)
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scroll with large size should not have next`() {
        val repo = orm.projection(OwnerView::class)
        val idPath = metamodel<OwnerView, Int>(repo.model, "id")
        val window = repo.select().orderBy(idPath).scroll(100)
        window.content shouldHaveSize 10
        window.hasNext shouldBe false
    }

    @Test
    fun `findAllBy with field and iterable of ref values should return matching`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val refs = listOf(
            Ref.of(City::class.java, 1),
            Ref.of(City::class.java, 3),
            Ref.of(City::class.java, 5),
        )
        // We use findAllByRef with metamodel
        val views = repo.findAllByRef(cityPath, refs)
        // City 1: Betty, City 3: Eduardo, City 5: Jean + Jeff = 4 total
        views shouldHaveSize 4
    }

    @Test
    fun `selectBy with field and iterable of ref values should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val refs = listOf(
            Ref.of(City::class.java, 1),
            Ref.of(City::class.java, 3),
        )
        val count = repo.selectByRef(cityPath, refs).count()
        count shouldBe 2
    }

    @Test
    fun `orm findBy reified projection with ref should return matching`() {
        val cityPath = metamodel<OwnerView, City>(orm.projection(OwnerView::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val view = orm.findBy<OwnerView, City>(cityPath, cityRef)
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `orm getBy reified projection with ref should return matching`() {
        val cityPath = metamodel<OwnerView, City>(orm.projection(OwnerView::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val view = orm.getBy<OwnerView, City>(cityPath, cityRef)
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `orm findAllBy reified projection with ref should return matching`() {
        val cityPath = metamodel<OwnerView, City>(orm.projection(OwnerView::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val views = orm.findAllBy<OwnerView, City>(cityPath, cityRef)
        views shouldHaveSize 4
    }

    @Test
    fun `orm countBy reified projection with ref should count matching`() {
        val cityPath = metamodel<OwnerView, City>(orm.projection(OwnerView::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val count = orm.countBy<OwnerView, City>(cityPath, cityRef)
        count shouldBe 4
    }

    @Test
    fun `orm existsBy reified projection with ref should return true`() {
        val cityPath = metamodel<OwnerView, City>(orm.projection(OwnerView::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        orm.existsBy<OwnerView, City>(cityPath, cityRef) shouldBe true
    }

    @Test
    fun `orm selectBy reified projection with ref should return matching flow`(): Unit = runBlocking {
        val cityPath = metamodel<OwnerView, City>(orm.projection(OwnerView::class).model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val count = orm.selectBy<OwnerView, City>(cityPath, cityRef).count()
        count shouldBe 4
    }

    @Test
    fun `orm findRefBy reified projection should return ref`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val ref = orm.findRefBy<OwnerView, String>(firstNamePath, "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm getRefBy reified projection should return ref`() {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val ref = orm.getRefBy<OwnerView, String>(firstNamePath, "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `orm selectRefBy reified projection should return flow of refs`(): Unit = runBlocking {
        val firstNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "first_name")
        val count = orm.selectRefBy<OwnerView, String>(firstNamePath, "Betty").count()
        count shouldBe 1
    }

    @Test
    fun `orm findAllRefBy reified projection should return refs`() {
        val lastNamePath = metamodel<OwnerView, String>(orm.projection(OwnerView::class).model, "last_name")
        val refs = orm.findAllRefBy<OwnerView, String>(lastNamePath, "Davis")
        refs shouldHaveSize 2
    }

    @Test
    fun `findBy with field and string value should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.findBy(firstNamePath, "Betty")
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findBy with field and string value should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.findBy(firstNamePath, "NonExistentName")
        view.shouldBeNull()
    }

    @Test
    fun `getBy with field and string value should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.getBy(firstNamePath, "Betty")
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getBy with field and string value should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        assertThrows<NoResultException> {
            repo.getBy(firstNamePath, "NonExistentName")
        }
    }

    @Test
    fun `findAllBy with field and string value should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val views = repo.findAllBy(lastNamePath, "Davis")
        views shouldHaveSize 2
    }

    @Test
    fun `findAllBy with field and iterable of values should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val views = repo.findAllBy(firstNamePath, listOf("Betty", "Eduardo"))
        views shouldHaveSize 2
    }

    @Test
    fun `selectBy with field and string value should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val count = repo.selectBy(lastNamePath, "Davis").count()
        count shouldBe 2
    }

    @Test
    fun `selectBy with field and iterable of values should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val count = repo.selectBy(firstNamePath, listOf("Betty", "Eduardo")).count()
        count shouldBe 2
    }

    @Test
    fun `findRefBy with field and string value should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRefBy<Any, Any, String>(firstNamePath, "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRefBy with field and string value should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRefBy<Any, Any, String>(firstNamePath, "NonExistentName")
        ref.shouldBeNull()
    }

    @Test
    fun `getRefBy with field and string value should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.getRefBy(firstNamePath, "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRefBy with field and string value should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        assertThrows<NoResultException> {
            repo.getRefBy(firstNamePath, "NonExistentName")
        }
    }

    @Test
    fun `getRefBy with field and ref value should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 1)
        val ref = repo.getRefBy(cityPath, cityRef)
        ref.shouldNotBeNull()
    }

    @Test
    fun `findAllRefBy with field and string value should return matching refs`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val refs = repo.findAllRefBy(lastNamePath, "Davis")
        refs shouldHaveSize 2
    }

    @Test
    fun `findAllRefBy with field and ref value should return matching refs`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val refs = repo.findAllRefBy(cityPath, cityRef)
        refs shouldHaveSize 4
    }

    @Test
    fun `findAllRefByRef with metamodel and iterable of refs should return matching refs`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 3))
        val resultRefs = repo.findAllRefByRef(cityPath, refs)
        resultRefs shouldHaveSize 2
    }

    @Test
    fun `selectRefBy with field and string value should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val refs = repo.selectRefBy(lastNamePath, "Davis").toList()
        refs shouldHaveSize 2
    }

    @Test
    fun `selectRefBy with field and ref value for multiple results should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cityRef: Ref<City> = Ref.of(City::class.java, 2)
        val refs = repo.selectRefBy(cityPath, cityRef).toList()
        refs shouldHaveSize 4
    }

    @Test
    fun `selectRefBy with field and iterable of values should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val refs = repo.selectRefBy(firstNamePath, listOf("Betty", "Eduardo")).toList()
        refs shouldHaveSize 2
    }

    @Test
    fun `selectRefByRef with metamodel and iterable of refs should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val refs = listOf(Ref.of(City::class.java, 1), Ref.of(City::class.java, 3))
        val resultRefs = repo.selectRefByRef(cityPath, refs).toList()
        resultRefs shouldHaveSize 2
    }

    @Test
    fun `countBy with field and string value should count matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        repo.countBy(lastNamePath, "Davis") shouldBe 2
    }

    @Test
    fun `existsBy with field and string value should return true when match exists`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.existsBy(firstNamePath, "Betty") shouldBe true
    }

    @Test
    fun `existsBy with field and string value should return false when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.existsBy(firstNamePath, "NonExistentName") shouldBe false
    }

    @Test
    fun `find with WhereBuilder predicate should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.find(firstNamePath eq "Betty")
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `find with WhereBuilder predicate should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.find(firstNamePath eq "NonExistentName")
        view.shouldBeNull()
    }

    @Test
    fun `get with WhereBuilder predicate should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val view = repo.get(firstNamePath eq "Betty")
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findRef with WhereBuilder predicate should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.findRef(firstNamePath eq "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with WhereBuilder predicate should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val ref = repo.getRef(firstNamePath eq "Betty")
        ref.shouldNotBeNull()
    }

    @Test
    fun `findAll with WhereBuilder predicate should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val views = repo.findAll(lastNamePath eq "Davis")
        views shouldHaveSize 2
    }

    @Test
    fun `findAllRef with WhereBuilder predicate should return matching refs`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val refs = repo.findAllRef(lastNamePath eq "Davis")
        refs shouldHaveSize 2
    }

    @Test
    fun `select with WhereBuilder predicate should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val count = repo.select().where(lastNamePath eq "Davis").resultFlow.count()
        count shouldBe 2
    }

    @Test
    fun `selectRef with WhereBuilder predicate should return matching refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val refs = repo.selectRef().where(lastNamePath eq "Davis").resultFlow.toList()
        refs shouldHaveSize 2
    }

    @Test
    fun `count with WhereBuilder predicate should count matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val count = repo.count(lastNamePath eq "Davis")
        count shouldBe 2
    }

    @Test
    fun `exists with WhereBuilder predicate should return true when match exists`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.exists(firstNamePath eq "Betty") shouldBe true
    }

    @Test
    fun `exists with WhereBuilder predicate should return false when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        repo.exists(firstNamePath eq "NonExistent") shouldBe false
    }

    @Test
    fun `findAllRef should return all projection refs`() {
        val repo = orm.projection(OwnerView::class)
        val refs = repo.findAllRef()
        refs shouldHaveSize 10
    }

    @Test
    fun `selectAllRef should return all projection refs as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.selectRef().resultFlow.count()
        count shouldBe 10
    }

    @Test
    fun `findAllRefBy with field and iterable of Data values should return matching refs`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cities = listOf(City(id = 1, name = "Sun Prairie"), City(id = 3, name = "McFarland"))
        val refs = repo.findAllRefBy(cityPath, cities)
        refs shouldHaveSize 2
    }

    @Test
    fun `count should return total number of projections`() {
        val repo = orm.projection(OwnerView::class)
        repo.count() shouldBe 10
    }

    @Test
    fun `existsById should return true for existing id`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsById(1) shouldBe true
    }

    @Test
    fun `existsById should return false for non-existing id`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsById(999) shouldBe false
    }

    @Test
    fun `existsByRef should return true for existing ref`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsByRef(Ref.of(OwnerView::class.java, 1)) shouldBe true
    }

    @Test
    fun `existsByRef should return false for non-existing ref`() {
        val repo = orm.projection(OwnerView::class)
        repo.existsByRef(Ref.of(OwnerView::class.java, 999)) shouldBe false
    }

    @Test
    fun `findById should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val view = repo.findById(1)
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findById should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val view = repo.findById(999)
        view.shouldBeNull()
    }

    @Test
    fun `findByRef should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val view = repo.findByRef(Ref.of(OwnerView::class.java, 1))
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getById should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val view = repo.getById(1)
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getByRef should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val view = repo.getByRef(Ref.of(OwnerView::class.java, 1))
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findAll should return all projections`() {
        val repo = orm.projection(OwnerView::class)
        val views = repo.findAll()
        views shouldHaveSize 10
    }

    @Test
    fun `findAllById should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val views = repo.findAllById(listOf(1, 2, 3))
        views shouldHaveSize 3
    }

    @Test
    fun `findAllByRef should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val refs = listOf(
            Ref.of(OwnerView::class.java, 1),
            Ref.of(OwnerView::class.java, 2),
        )
        val views = repo.findAllByRef(refs)
        views shouldHaveSize 2
    }

    @Test
    fun `selectAll should return all projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.select().resultFlow.count()
        count shouldBe 10
    }

    @Test
    fun `selectById should return matching projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.selectById(kotlinx.coroutines.flow.flowOf(1, 2, 3)).count()
        count shouldBe 3
    }

    @Test
    fun `selectByRef should return matching projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = kotlinx.coroutines.flow.flowOf(
            Ref.of(OwnerView::class.java, 1),
            Ref.of(OwnerView::class.java, 2),
        )
        val count = repo.selectByRef(refs).count()
        count shouldBe 2
    }

    @Test
    fun `selectById with chunkSize should return matching projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.selectById(kotlinx.coroutines.flow.flowOf(1, 2, 3), 2).count()
        count shouldBe 3
    }

    @Test
    fun `selectByRef with chunkSize should return matching projections as flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = kotlinx.coroutines.flow.flowOf(
            Ref.of(OwnerView::class.java, 1),
            Ref.of(OwnerView::class.java, 2),
        )
        val count = repo.selectByRef(refs, 2).count()
        count shouldBe 2
    }

    @Test
    fun `countById with chunkSize should count matching projections from flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val count = repo.countById(kotlinx.coroutines.flow.flowOf(1, 2, 3), 2)
        count shouldBe 3
    }

    @Test
    fun `countByRef with chunkSize should count matching projections from flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = kotlinx.coroutines.flow.flowOf(
            Ref.of(OwnerView::class.java, 1),
            Ref.of(OwnerView::class.java, 2),
        )
        val count = repo.countByRef(refs, 2)
        count shouldBe 2
    }

    @Test
    fun `find with direct PredicateBuilder should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "NonExistent"
        val view = repo.find(predicate)
        view.shouldBeNull()
    }

    @Test
    fun `get with direct PredicateBuilder should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "NonExistent"
        assertThrows<NoResultException> {
            repo.get(predicate)
        }
    }

    @Test
    fun `findRef with direct PredicateBuilder should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "Betty"
        val ref = repo.findRef(predicate)
        ref.shouldNotBeNull()
    }

    @Test
    fun `findRef with direct PredicateBuilder should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "NonExistent"
        val ref = repo.findRef(predicate)
        ref.shouldBeNull()
    }

    @Test
    fun `getRef with direct PredicateBuilder should return matching ref`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "Betty"
        val ref = repo.getRef(predicate)
        ref.shouldNotBeNull()
    }

    @Test
    fun `getRef with direct PredicateBuilder should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "NonExistent"
        assertThrows<NoResultException> {
            repo.getRef(predicate)
        }
    }

    @Test
    fun `findAll with direct PredicateBuilder should return matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val views = repo.findAll(predicate)
        views shouldHaveSize 2
    }

    @Test
    fun `findAllRef with direct PredicateBuilder should return matching refs`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val refs = repo.findAllRef(predicate)
        refs shouldHaveSize 2
    }

    @Test
    fun `select with direct PredicateBuilder should return matching flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val count = repo.select().where(predicate).resultFlow.count()
        count shouldBe 2
    }

    @Test
    fun `selectRef with direct PredicateBuilder should return matching refs flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val refs = repo.selectRef().where(predicate).resultFlow.toList()
        refs shouldHaveSize 2
    }

    @Test
    fun `count with direct PredicateBuilder should count matching projections`() {
        val repo = orm.projection(OwnerView::class)
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        repo.count(predicate) shouldBe 2
    }

    @Test
    fun `exists with direct PredicateBuilder should return true when match exists`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "Betty"
        repo.exists(predicate) shouldBe true
    }

    @Test
    fun `exists with direct PredicateBuilder should return false when no match`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val predicate = firstNamePath eq "NonExistent"
        repo.exists(predicate) shouldBe false
    }

    @Test
    fun `findBy with Metamodel Key should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val view = repo.findBy(idKey, 1)
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findBy with Metamodel Key should return null for non-existing key`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val view = repo.findBy(idKey, 999)
        view.shouldBeNull()
    }

    @Test
    fun `getBy with Metamodel Key should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val view = repo.getBy(idKey, 1)
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getBy with Metamodel Key should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        assertThrows<NoResultException> {
            repo.getBy(idKey, 999)
        }
    }

    @Test
    fun `findByRef with Metamodel Key should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val cityKey = metamodel<OwnerView, City>(repo.model, "city_id").key()
        val view = repo.findByRef(cityKey, Ref.of(City::class.java, 1))
        view.shouldNotBeNull()
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `findByRef with Metamodel Key should return null when no match`() {
        val repo = orm.projection(OwnerView::class)
        val cityKey = metamodel<OwnerView, City>(repo.model, "city_id").key()
        val view = repo.findByRef(cityKey, Ref.of(City::class.java, 999))
        view.shouldBeNull()
    }

    @Test
    fun `getByRef with Metamodel Key should return matching projection`() {
        val repo = orm.projection(OwnerView::class)
        val cityKey = metamodel<OwnerView, City>(repo.model, "city_id").key()
        val view = repo.getByRef(cityKey, Ref.of(City::class.java, 1))
        view.firstName shouldBe "Betty"
    }

    @Test
    fun `getByRef with Metamodel Key should throw when no match`() {
        val repo = orm.projection(OwnerView::class)
        val cityKey = metamodel<OwnerView, City>(repo.model, "city_id").key()
        assertThrows<NoResultException> {
            repo.getByRef(cityKey, Ref.of(City::class.java, 999))
        }
    }

    @Test
    fun `scroll with Metamodel Key should return first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scroll with Metamodel Key should return all when size exceeds total`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 100))
        window.content shouldHaveSize 10
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollBefore with Metamodel Key should return descending first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
        // First item should be the highest id
        window.content[0].id shouldBe 10
    }

    @Test
    fun `scrollRef with Metamodel Key should return first page of refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 3))
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scrollBeforeRef with Metamodel Key should return descending first page of refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 3).backward())
        window.content shouldHaveSize 3
        window.hasNext shouldBe true
    }

    @Test
    fun `scroll with Metamodel Key and WhereBuilder predicate should filter results`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `scroll with Metamodel Key and direct PredicateBuilder should filter results`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val window = repo.select().where(predicate).scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollRef with Metamodel Key and WhereBuilder predicate should filter results`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollRef with Metamodel Key and direct PredicateBuilder should filter results`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val window = repo.selectRef().where(predicate).scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollBefore with Metamodel Key and WhereBuilder should filter results descending`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `scrollBefore with Metamodel Key and direct PredicateBuilder should filter results descending`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val predicate = lastNamePath eq "Davis"
        val window = repo.select().where(predicate).scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `scroll forward with cursor should return next page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val firstWindow = repo.scroll(Scrollable.of(idKey, 3))
        firstWindow.content shouldHaveSize 3
        // Get the last ID from the first scroll to use as cursor
        val lastId = firstWindow.content.last().id
        val nextWindow = repo.select().scroll(Scrollable(idKey, lastId, null, null, 3, true))
        nextWindow.content shouldHaveSize 3
        // All IDs in next scroll should be greater than lastId
        nextWindow.content.forEach { it.id shouldBe (it.id).also { id -> assert(id > lastId) } }
    }

    @Test
    fun `scroll backward with cursor should return previous page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        // Get the last page first
        val lastWindow = repo.scroll(Scrollable.of(idKey, 3).backward())
        lastWindow.content shouldHaveSize 3
        // Navigate backward
        val firstId = lastWindow.content.last().id
        val previousWindow = repo.select().scroll(Scrollable(idKey, firstId, null, null, 3, false))
        previousWindow.content shouldHaveSize 3
    }

    @Test
    fun `selectAllRef flow should return all refs`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val refs = repo.selectRef().resultFlow.toList()
        refs shouldHaveSize 10
        refs.forEach { it.shouldNotBeNull() }
    }

    @Test
    fun `selectRef with IN operator and string values should return refs`() {
        val repo = orm.projection(OwnerView::class)
        val firstNamePath = metamodel<OwnerView, String>(repo.model, "first_name")
        val refs = repo.selectRef().where(firstNamePath, IN, listOf("Betty", "Eduardo")).resultList
        refs shouldHaveSize 2
    }

    @Test
    fun `selectRefBy with field and iterable of Data values should return refs flow`(): Unit = runBlocking {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val cities = listOf(City(id = 1, name = "Sun Prairie"), City(id = 3, name = "McFarland"))
        val refs = repo.selectRefByRef(cityPath, cities.map { Ref.of(City::class.java, it.id) }).toList()
        refs shouldHaveSize 2
    }

    @Test
    fun `getRefBy with ref value should throw for non-existing ref`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        assertThrows<NoResultException> {
            repo.getRefBy(cityPath, Ref.of(City::class.java, 999))
        }
    }

    @Test
    fun `findRefBy with ref value should return null for non-existing ref`() {
        val repo = orm.projection(OwnerView::class)
        val cityPath = metamodel<OwnerView, City>(repo.model, "city_id")
        val ref = repo.findRefBy(cityPath, Ref.of(City::class.java, 999))
        ref.shouldBeNull()
    }

    @Test
    fun `projection query builder scroll should return first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.select().scroll(Scrollable.of(idKey, 4))
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `projection query builder scrollBefore should return descending first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.select().scroll(Scrollable.of(idKey, 4).backward())
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `projection query builder scrollRef should return refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 4))
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scroll with key and size should return first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 4))
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollBefore with key and size should return last page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.scroll(Scrollable.of(idKey, 4).backward())
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollRef with key and size should return first page of refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 4))
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollBeforeRef with key and size should return refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable.of(idKey, 4).backward())
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scroll with key and predicate should return filtered first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `repo scrollRef with key and predicate should return filtered refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10))
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `repo scrollBefore with key and predicate should return filtered last page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `repo scrollBeforeRef with key and predicate should return filtered refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath eq "Davis").scroll(Scrollable.of(idKey, 10).backward())
        window.content shouldHaveSize 2
        window.hasNext shouldBe false
    }

    @Test
    fun `repo scrollAfter with key after value and size should return next page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.select().scroll(Scrollable(idKey, 3, null, null, 4, true))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollAfterRef with key after value and size should return next page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable(idKey, 3, null, null, 4, true))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollBefore with key before value and size should return previous page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.select().scroll(Scrollable(idKey, 8, null, null, 4, false))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollBeforeRef with key before value and size should return previous page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val window = repo.selectRef().scroll(Scrollable(idKey, 8, null, null, 4, false))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollAfter with key after value predicate and size should return filtered next page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().where(lastNamePath eq "Davis").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content shouldHaveSize 1
    }

    @Test
    fun `repo scrollAfterRef with key after value predicate and size should return filtered next page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath eq "Davis").scroll(Scrollable(idKey, 1, null, null, 10, true))
        window.content shouldHaveSize 1
    }

    @Test
    fun `repo scrollBefore with key before value predicate and size should return filtered previous page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().where(lastNamePath eq "Davis").scroll(Scrollable(idKey, 10, null, null, 10, false))
        window.content shouldHaveSize 2
    }

    @Test
    fun `repo scrollBeforeRef with key before value predicate and size should return filtered previous page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().where(lastNamePath eq "Davis").scroll(Scrollable(idKey, 10, null, null, 10, false))
        window.content shouldHaveSize 2
    }

    @Test
    fun `repo scroll with key sort and size should return sorted first page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.scroll(Scrollable.of(idKey, lastNamePath, 4))
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollBefore with key sort and size should return sorted last page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.scroll(Scrollable.of(idKey, lastNamePath, 4).backward())
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollRef with key sort and size should return sorted first page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().scroll(Scrollable.of(idKey, lastNamePath, 4))
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollBeforeRef with key sort and size should return sorted refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().scroll(Scrollable.of(idKey, lastNamePath, 4).backward())
        window.content shouldHaveSize 4
        window.hasNext shouldBe true
    }

    @Test
    fun `repo scrollAfter with key sort and cursors should return next sorted page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().scroll(Scrollable(idKey, 3, lastNamePath, "Davis", 4, true))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollBefore with key sort and cursors should return previous sorted page`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.select().scroll(Scrollable(idKey, 8, lastNamePath, "Smith", 4, false))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollAfterRef with key sort and cursors should return next sorted page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().scroll(Scrollable(idKey, 3, lastNamePath, "Davis", 4, true))
        window.content shouldHaveSize 4
    }

    @Test
    fun `repo scrollBeforeRef with key sort and cursors should return previous sorted page refs`() {
        val repo = orm.projection(OwnerView::class)
        val idKey = metamodel<OwnerView, Int>(repo.model, "id").key()
        val lastNamePath = metamodel<OwnerView, String>(repo.model, "last_name")
        val window = repo.selectRef().scroll(Scrollable(idKey, 8, lastNamePath, "Smith", 4, false))
        window.content shouldHaveSize 4
    }
}
