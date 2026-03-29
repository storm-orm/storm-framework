package st.orm.spring

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestConstructor.AutowireMode.ALL
import org.springframework.test.context.jdbc.Sql
import st.orm.PersistenceException
import st.orm.repository.countAll
import st.orm.repository.exists
import st.orm.repository.findAll
import st.orm.repository.insert
import st.orm.repository.remove
import st.orm.repository.removeAll
import st.orm.repository.update
import st.orm.spring.model.City
import st.orm.spring.model.Pet
import st.orm.spring.model.PetType
import st.orm.spring.model.Visit
import st.orm.spring.repository.VisitRepository
import st.orm.template.ORMTemplate
import java.time.LocalDate

/**
 * Tests for repository CRUD operations through Spring-managed repositories.
 *
 * These tests verify that entity repositories registered via [TestRepositoryBeanFactoryPostProcessor]
 * correctly perform create, read, update, and delete operations, including batch operations and
 * error scenarios.
 */
@Suppress("SpringJavaInjectionPointsAutowiringInspection")
@ContextConfiguration(classes = [IntegrationConfig::class])
@Import(TestRepositoryBeanFactoryPostProcessor::class)
@TestConstructor(autowireMode = ALL)
@SpringBootTest
@Sql("/data.sql")
class RepositoryAdvancedTest(
    val visitRepository: VisitRepository,
    val orm: ORMTemplate,
) {

    /**
     * Count operations.
     */

    @Test
    fun `count should return total number of visits in test data`() {
        visitRepository.count() shouldBe 14
    }

    @Test
    fun `countAll extension on orm should return total number of cities`() {
        orm.countAll<City>() shouldBe 6
    }

    @Test
    fun `countAll extension on orm should return total number of pet types`() {
        orm.countAll<PetType>() shouldBe 6
    }

    /**
     * Exists operations.
     */

    @Test
    fun `exists should return true when visits are present`() {
        visitRepository.exists() shouldBe true
    }

    @Test
    fun `existsById should return true for known visit id`() {
        visitRepository.existsById(1) shouldBe true
    }

    @Test
    fun `existsById should return false for nonexistent visit id`() {
        visitRepository.existsById(9999) shouldBe false
    }

    /**
     * Find operations.
     */

    @Test
    fun `findById should return visit for known id`() {
        val visit = visitRepository.findById(1)
        visit.shouldNotBeNull()
        visit.id shouldBe 1
        visit.description shouldBe "rabies shot"
    }

    @Test
    fun `findById should return null for nonexistent id`() {
        visitRepository.findById(9999) shouldBe null
    }

    @Test
    fun `findAll extension on orm should return all cities`() {
        val cities = orm.findAll<City>()
        cities shouldHaveSize 6
    }

    @Test
    fun `findAll extension on orm should return all pet types`() {
        val petTypes = orm.findAll<PetType>()
        petTypes shouldHaveSize 6
    }

    /**
     * Select (query builder) operations.
     */

    @Test
    fun `select on repository should return all visits as list`() {
        val visits = visitRepository.select().resultList
        visits shouldHaveSize 14
    }

    @Test
    fun `selectCount on repository should return total count`() {
        val count = visitRepository.selectCount().singleResult
        count shouldBe 14
    }

    /**
     * Insert operations.
     */

    @Test
    fun `insert should persist a new visit and make it findable`() {
        val pet = orm.findAll<Pet>().first()
        val newVisit = Visit(
            visitDate = LocalDate.of(2024, 6, 15),
            description = "annual checkup",
            pet = pet,
            timestamp = null,
        )
        val insertedVisit = orm insert newVisit
        insertedVisit.id shouldNotBe 0
        insertedVisit.description shouldBe "annual checkup"

        val fetched = visitRepository.findById(insertedVisit.id)
        fetched.shouldNotBeNull()
        fetched.description shouldBe "annual checkup"
    }

    @Test
    fun `insert should increase total count`() {
        val countBefore = visitRepository.count()
        val pet = orm.findAll<Pet>().first()
        orm insert Visit(
            visitDate = LocalDate.of(2024, 7, 20),
            description = "vaccination",
            pet = pet,
            timestamp = null,
        )
        visitRepository.count() shouldBe countBefore + 1
    }

    /**
     * Update operations.
     */

    @Test
    fun `update should persist changes to an existing visit`() {
        val original = visitRepository.findById(1)
        original.shouldNotBeNull()

        val updated = original.copy(description = "updated description")
        orm update updated

        val fetched = visitRepository.findById(1)
        fetched.shouldNotBeNull()
        fetched.description shouldBe "updated description"
    }

    /**
     * Delete operations.
     */

    @Test
    fun `remove should remove a specific visit`() {
        val visit = visitRepository.findById(1)
        visit.shouldNotBeNull()

        orm remove visit

        visitRepository.findById(1) shouldBe null
        visitRepository.count() shouldBe 13
    }

    @Test
    fun `removeAll should remove all visits`() {
        orm.removeAll<Visit>()
        visitRepository.count() shouldBe 0
        visitRepository.exists() shouldBe false
    }

    @Test
    fun `removeAll should not affect other entity types`() {
        orm.removeAll<Visit>()
        orm.countAll<City>() shouldBe 6
        orm.countAll<PetType>() shouldBe 6
    }

    /**
     * ORM template access from repository.
     */

    @Test
    fun `repository orm property should provide access to the underlying orm template`() {
        val repositoryOrm = visitRepository.orm
        repositoryOrm.shouldNotBeNull()
        repositoryOrm.countAll<Visit>() shouldBe 14
    }

    @Test
    fun `repository orm property should be able to perform cross-entity queries`() {
        val repositoryOrm = visitRepository.orm
        repositoryOrm.countAll<City>() shouldBe 6
    }

    /**
     * Duplicate and constraint violation scenarios.
     */

    @Test
    fun `insert with explicit duplicate primary key should throw PersistenceException`() {
        val city = City(id = 1, name = "Sun Paririe")
        // Inserting a city with an existing id=1 should fail.
        shouldThrow<PersistenceException> {
            orm.entity(City::class).insert(city, true)
        }
    }
}
