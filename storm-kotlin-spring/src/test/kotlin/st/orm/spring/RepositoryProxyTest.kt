package st.orm.spring

import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import org.springframework.aop.support.AopUtils
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationContext
import org.springframework.context.annotation.Import
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.TestConstructor
import org.springframework.test.context.TestConstructor.AutowireMode.ALL
import org.springframework.test.context.jdbc.Sql
import st.orm.repository.EntityRepository
import st.orm.repository.Repository
import st.orm.repository.countAll
import st.orm.spring.impl.RepositoryProxyingPostProcessor
import st.orm.spring.model.Visit
import st.orm.spring.repository.VisitRepository
import st.orm.template.ORMTemplate

/**
 * Tests that proxied repositories behave correctly.
 *
 * The [st.orm.spring.impl.RepositoryProxyingPostProcessor] wraps all [Repository] beans in Spring AOP proxies.
 * This test class verifies that the proxy behavior is transparent: CRUD operations, the [Repository.orm] property,
 * and standard [Object] methods (toString, hashCode, equals) all work correctly on the proxied bean.
 */
@Suppress("SpringJavaInjectionPointsAutowiringInspection")
@ContextConfiguration(classes = [IntegrationConfig::class])
@Import(TestRepositoryBeanFactoryPostProcessor::class, RepositoryProxyingPostProcessor::class)
@TestConstructor(autowireMode = ALL)
@SpringBootTest
@Sql("/data.sql")
class RepositoryProxyTest(
    val visitRepository: VisitRepository,
    val applicationContext: ApplicationContext,
) {

    // Proxy detection

    @Test
    fun `visit repository should be a Spring AOP proxy`() {
        AopUtils.isAopProxy(visitRepository).shouldBeTrue()
    }

    @Test
    fun `visit repository proxy should target the correct class`() {
        val targetClass = AopUtils.getTargetClass(visitRepository)
        // The target class should be assignable to VisitRepository.
        VisitRepository::class.java.isAssignableFrom(targetClass).shouldBeTrue()
    }

    // Proxied repository should be an instance of the expected interfaces

    @Test
    fun `visit repository should be instance of VisitRepository`() {
        visitRepository.shouldBeInstanceOf<VisitRepository>()
    }

    @Test
    fun `visit repository should be instance of EntityRepository`() {
        visitRepository.shouldBeInstanceOf<EntityRepository<Visit, Int>>()
    }

    @Test
    fun `visit repository should be instance of Repository`() {
        visitRepository.shouldBeInstanceOf<Repository>()
    }

    // CRUD operations through proxied repository

    @Test
    fun `proxied repository count should return correct value`() {
        visitRepository.count() shouldBe 14
    }

    @Test
    fun `proxied repository exists should return true when data exists`() {
        visitRepository.exists() shouldBe true
    }

    @Test
    fun `proxied repository existsById should return true for known id`() {
        visitRepository.existsById(1) shouldBe true
    }

    @Test
    fun `proxied repository existsById should return false for unknown id`() {
        visitRepository.existsById(9999) shouldBe false
    }

    @Test
    fun `proxied repository findById should return entity for known id`() {
        val visit = visitRepository.findById(1)
        visit.shouldNotBeNull()
        visit.id shouldBe 1
    }

    @Test
    fun `proxied repository findById should return null for unknown id`() {
        visitRepository.findById(9999) shouldBe null
    }

    @Test
    fun `proxied repository select should return query builder that works`() {
        val visits = visitRepository.select().resultList
        visits.size shouldBe 14
    }

    @Test
    fun `proxied repository removeAll should remove all entities`() {
        visitRepository.removeAll()
        visitRepository.count() shouldBe 0
    }

    // Repository.orm property through proxy

    @Test
    fun `proxied repository orm property should not be null`() {
        visitRepository.orm.shouldNotBeNull()
    }

    @Test
    fun `proxied repository orm property should be an ORMTemplate`() {
        visitRepository.orm.shouldBeInstanceOf<ORMTemplate>()
    }

    @Test
    fun `proxied repository orm property should be usable for queries`() {
        visitRepository.orm.countAll<Visit>() shouldBe 14
    }

    // Object methods on proxied repository (toString, hashCode, equals)

    @Test
    fun `proxied repository toString should not throw and should return a non-empty string`() {
        val result = visitRepository.toString()
        result.shouldNotBeNull()
        result.length shouldNotBe 0
    }

    @Test
    fun `proxied repository hashCode should not throw`() {
        // hashCode should be callable without exception.
        val hash = visitRepository.hashCode()
        // Hash codes are non-deterministic, just verify it does not throw.
        hash shouldBe visitRepository.hashCode()
    }

    @Test
    fun `proxied repository equals itself should return true`() {
        @Suppress("ReplaceCallWithBinaryOperator")
        visitRepository.equals(visitRepository) shouldBe true
    }

    @Test
    fun `proxied repository equals null should return false`() {
        @Suppress("ReplaceCallWithBinaryOperator")
        visitRepository.equals(null) shouldBe false
    }

    @Test
    fun `proxied repository equals a different object type should return false`() {
        @Suppress("ReplaceCallWithBinaryOperator")
        visitRepository.equals("not a repository") shouldBe false
    }

    // Bean lookup from ApplicationContext

    @Test
    fun `visit repository should be retrievable from application context by type`() {
        val repository = applicationContext.getBean(VisitRepository::class.java)
        repository.shouldNotBeNull()
        repository.count() shouldBe 14
    }

    @Test
    fun `visit repository should be retrievable from application context by bean name`() {
        // The TestRepositoryBeanFactoryPostProcessor registers beans using the simple class name.
        val repository = applicationContext.getBean("VisitRepository")
        repository.shouldNotBeNull()
        repository.shouldBeInstanceOf<VisitRepository>()
    }

    @Test
    fun `visit repository retrieved by type and by name should be the same proxy instance`() {
        val byType = applicationContext.getBean(VisitRepository::class.java)
        val byName = applicationContext.getBean("VisitRepository")
        (byType === byName) shouldBe true
    }

    // Proxy transparency: model property access

    @Test
    fun `proxied repository model property should not be null`() {
        visitRepository.model.shouldNotBeNull()
    }

    @Test
    fun `proxied repository model entityType should be Visit`() {
        visitRepository.model.type shouldBe Visit::class
    }
}
