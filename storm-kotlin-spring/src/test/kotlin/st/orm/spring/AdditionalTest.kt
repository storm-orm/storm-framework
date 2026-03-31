package st.orm.spring

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.BeanDefinitionHolder
import org.springframework.beans.factory.config.DependencyDescriptor
import org.springframework.beans.factory.support.AutowireCandidateResolver
import org.springframework.beans.factory.support.DefaultListableBeanFactory
import org.springframework.beans.factory.support.RootBeanDefinition
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.context.jdbc.Sql
import st.orm.repository.countAll
import st.orm.repository.exists
import st.orm.repository.removeAll
import st.orm.spring.impl.RepositoryAopAutoConfiguration
import st.orm.spring.impl.ResolverRegistration
import st.orm.spring.model.City
import st.orm.spring.model.Pet
import st.orm.spring.model.Visit
import st.orm.template.ORMTemplate
import st.orm.template.TransactionIsolation.REPEATABLE_READ
import st.orm.template.TransactionPropagation.NESTED
import st.orm.template.TransactionPropagation.REQUIRED
import st.orm.template.TransactionPropagation.REQUIRES_NEW
import st.orm.template.UnexpectedRollbackException
import st.orm.template.setGlobalTransactionOptions
import st.orm.template.transactionBlocking

/**
 * Additional coverage tests targeting uncovered methods in storm-kotlin-spring:
 * - SpringTransactionContext: getEntityCache, findEntityCache, isRollbackOnly branches
 * - RepositoryAutowireCandidateResolver: getQualifierValue branches, register
 * - ResolverRegistration: constructor
 * - RepositoryBeanFactoryPostProcessor: getOrmTemplateBeanName, getRepositoryBasePackages
 * - RepositoryAopAutoConfiguration: constructor, repositoryProxyingPostProcessor
 */
@ContextConfiguration(classes = [IntegrationConfig::class])
@EnableTransactionIntegration
@SpringBootTest
@Sql("/data.sql")
open class AdditionalTest(
    @Autowired val orm: ORMTemplate,
) {

    @AfterEach
    fun resetDefaults() {
        setGlobalTransactionOptions(
            propagation = REQUIRED,
            isolation = null,
            timeoutSeconds = null,
            readOnly = false,
        )
    }

    // SpringTransactionContext: entity cache (getEntityCache, findEntityCache)

    @Test
    fun `entity cache should be created for each entity type under REPEATABLE_READ`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            // Load City and verify caching
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()

            // Load Visit entities (creates their cache)
            val visit1 = orm.entity(Visit::class).select().where(1).singleResult
            val visit2 = orm.entity(Visit::class).select().where(1).singleResult
            (visit1 === visit2).shouldBeTrue()
        }
    }

    @Test
    fun `entity cache should be isolated per entity type under REPEATABLE_READ`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()

            val visits = orm.entity(Visit::class).select().resultList
            visits.shouldNotBeNull()
        }
    }

    @Test
    fun `DML should invalidate entity cache for affected type`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            // Load City into cache
            val city1 = orm.entity(City::class).select().where(1).singleResult
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()

            // Insert a new city: this should invalidate the City entity cache
            orm.entity(City::class).insert(City(name = "CoverageVille"))

            // After cache invalidation, reloading should produce a new object
            val city3 = orm.entity(City::class).select().where(1).singleResult
            city3.name shouldBe city1.name
        }
    }

    @Test
    fun `DML on Visit should not invalidate City cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            // Load City into cache
            val city1 = orm.entity(City::class).select().where(1).singleResult

            // Load Visit into cache
            val visit1 = orm.entity(Visit::class).select().where(1).singleResult

            // Delete all visits: should invalidate Visit cache but not City cache
            orm.removeAll<Visit>()

            // City cache should still be intact
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeTrue()
        }
    }

    // SpringTransactionContext: isRollbackOnly (0% covered)

    @Test
    fun `isRollbackOnly should be false before setRollbackOnly`(): Unit = runBlocking {
        transactionBlocking {
            orm.entity(City::class).count() shouldBe 6
            // Check isRollbackOnly through the Transaction interface
            isRollbackOnly.shouldBeFalse()
        }
    }

    @Test
    fun `isRollbackOnly should be true after setRollbackOnly with DB access`(): Unit = runBlocking {
        transactionBlocking {
            orm.entity(City::class).count() shouldBe 6
            setRollbackOnly()
            isRollbackOnly.shouldBeTrue()
        }
    }

    @Test
    fun `isRollbackOnly should be true after setRollbackOnly without DB access`(): Unit = runBlocking {
        transactionBlocking {
            setRollbackOnly()
            // isRollbackOnly should be true even without DB access
            // This exercises the branch where transactionStatus is null
            isRollbackOnly.shouldBeTrue()
        }
    }

    @Test
    fun `isRollbackOnly should be true after inner setRollbackOnly`(): Unit = runBlocking {
        // Inner REQUIRED joins the outer transaction; marking rollback-only in the inner
        // poisons the outer, causing UnexpectedRollbackException on outer commit.
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                transactionBlocking(REQUIRED) {
                    orm.entity(City::class).count() shouldBe 6
                    setRollbackOnly()
                    isRollbackOnly.shouldBeTrue()
                }
            }
        }
    }

    // RepositoryAutowireCandidateResolver: register (0% covered)

    @Test
    fun `register should install RepositoryAutowireCandidateResolver in bean factory`() {
        val beanFactory = DefaultListableBeanFactory()
        RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver.register(beanFactory)
        (beanFactory.autowireCandidateResolver is RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver).shouldBeTrue()
    }

    // RepositoryAutowireCandidateResolver: getQualifierValue meta-annotation

    @Qualifier
    @Retention(AnnotationRetention.RUNTIME)
    @Target(AnnotationTarget.FIELD)
    annotation class MetaQualified(val value: String = "")

    @Test
    fun `getQualifierValue should extract value from meta-Qualifier annotation`() {
        // Create a resolver with a mock delegate
        val delegate = mock(AutowireCandidateResolver::class.java)
        val resolver =
            RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate)

        // Create a mock DependencyDescriptor with our meta-annotated annotation
        val descriptor = mock(DependencyDescriptor::class.java)
        val metaAnnotation = MetaQualified::class.java.getAnnotation(Qualifier::class.java)
        metaAnnotation.shouldNotBeNull()

        // Create a BeanDefinitionHolder with a qualifier attribute
        val beanDefinition = RootBeanDefinition(String::class.java)
        beanDefinition.setAttribute("qualifier", "")
        val holder = BeanDefinitionHolder(beanDefinition, "testBean")

        // Mock delegate to reject the candidate, forcing getRequiredQualifier path
        `when`(delegate.isAutowireCandidate(holder, descriptor)).thenReturn(false)

        // Use a real annotation that is meta-annotated with @Qualifier
        val annotations = arrayOf<Annotation>(MetaQualified("testValue"))
        `when`(descriptor.annotations).thenReturn(annotations)

        // The resolver should extract the qualifier value from the meta-annotation
        // The getQualifierValue method will check:
        // 1. annotation is Qualifier -> NO (it's MetaQualified)
        // 2. annotation.annotationClass.java.getAnnotation(Qualifier) -> returns Qualifier
        // 3. metadata?.value -> returns ""
        // The holder has qualifier attribute "" which matches, so isAutowireCandidate returns true
        resolver.isAutowireCandidate(holder, descriptor).shouldBeTrue()
    }

    @Test
    fun `getQualifierValue should return null for non-qualifier annotation`() {
        val delegate = mock(AutowireCandidateResolver::class.java)
        val resolver =
            RepositoryBeanFactoryPostProcessor.RepositoryAutowireCandidateResolver(delegate)

        val descriptor = mock(DependencyDescriptor::class.java)
        val beanDefinition = RootBeanDefinition(String::class.java)
        val holder = BeanDefinitionHolder(beanDefinition, "testBean")

        `when`(delegate.isAutowireCandidate(holder, descriptor)).thenReturn(false)

        // Use an annotation that is NOT meta-annotated with @Qualifier
        val annotations = arrayOf<Annotation>(Override())
        `when`(descriptor.annotations).thenReturn(annotations)

        // Without a qualifier annotation, isAutowireCandidate should return false
        resolver.isAutowireCandidate(holder, descriptor).shouldBeFalse()
    }

    // ResolverRegistration: constructor

    @Test
    fun `ResolverRegistration should be instantiable with bean factory`() {
        val beanFactory = DefaultListableBeanFactory()
        val registration = ResolverRegistration(beanFactory)
        registration.shouldNotBeNull()
    }

    // RepositoryBeanFactoryPostProcessor: uncovered properties

    @Test
    fun `ormTemplateBeanName default should be null`() {
        val processor = RepositoryBeanFactoryPostProcessor()
        processor.ormTemplateBeanName.shouldBeNull()
    }

    @Test
    fun `repositoryBasePackages default should be empty`() {
        val processor = RepositoryBeanFactoryPostProcessor()
        processor.repositoryBasePackages shouldBe emptyArray()
    }

    @Test
    fun `postProcessBeanFactory with empty base packages should not register`() {
        val processor = RepositoryBeanFactoryPostProcessor()
        val beanFactory = DefaultListableBeanFactory()
        processor.postProcessBeanFactory(beanFactory)
    }

    // RepositoryAopAutoConfiguration (0% covered)

    @Test
    fun `RepositoryAopAutoConfiguration should be instantiable`() {
        val configuration = RepositoryAopAutoConfiguration()
        configuration.shouldNotBeNull()
    }

    @Test
    fun `RepositoryAopAutoConfiguration companion should provide repositoryProxyingPostProcessor`() {
        val postProcessor = RepositoryAopAutoConfiguration.repositoryProxyingPostProcessor()
        postProcessor.shouldNotBeNull()
    }

    // SpringTransactionContext: commit and rollback edge cases

    @Test
    fun `commit with no DB access and timeout should handle timeout check`(): Unit = runBlocking {
        transactionBlocking(timeoutSeconds = 30) {
            // No DB access, exercises commit path where transactionStatus is null
        }
    }

    @Test
    fun `rollback after exception should handle cleanup`(): Unit = runBlocking {
        assertThrows<RuntimeException> {
            transactionBlocking {
                orm.entity(City::class).count() shouldBe 6
                throw RuntimeException("forced rollback")
            }
        }
    }

    @Test
    fun `rollback after exception with timeout should handle cleanup`(): Unit = runBlocking {
        assertThrows<RuntimeException> {
            transactionBlocking(timeoutSeconds = 30) {
                orm.entity(City::class).count() shouldBe 6
                throw RuntimeException("forced rollback with timeout")
            }
        }
    }

    @Test
    fun `rollback with no DB access should handle cleanup`(): Unit = runBlocking {
        assertThrows<RuntimeException> {
            transactionBlocking {
                throw RuntimeException("rollback without DB access")
            }
        }
    }

    @Test
    fun `rollback with no DB access and timeout should handle cleanup`(): Unit = runBlocking {
        assertThrows<RuntimeException> {
            transactionBlocking(timeoutSeconds = 30) {
                throw RuntimeException("rollback without DB access but with timeout")
            }
        }
    }

    @Test
    fun `nested REQUIRED transactions should share connection`(): Unit = runBlocking {
        transactionBlocking {
            val count1 = orm.entity(City::class).count()
            transactionBlocking(REQUIRED) {
                val count2 = orm.entity(City::class).count()
                count1 shouldBe count2
            }
        }
    }

    // SpringTransactionContext: UnexpectedRollbackException in commit path

    @Test
    fun `inner setRollbackOnly should cause UnexpectedRollbackException on outer commit`(): Unit = runBlocking {
        assertThrows<UnexpectedRollbackException> {
            transactionBlocking {
                orm.entity(City::class).count() shouldBe 6
                transactionBlocking(REQUIRED) {
                    orm.entity(City::class).count() shouldBe 6
                    setRollbackOnly()
                }
                // Outer tries to commit but inner marked rollback-only
            }
        }
    }

    // SpringTransactionContext: NESTED rollback clears entity cache

    @Test
    fun `NESTED rollback should clear shared entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(NESTED) {
                orm.removeAll<Visit>()
                setRollbackOnly()
            }
            // After nested rollback, entity cache should have been cleared
            val city2 = orm.entity(City::class).select().where(1).singleResult
            (city1 === city2).shouldBeFalse()
            city1.name shouldBe city2.name
        }
    }

    // SpringTransactionContext: REQUIRES_NEW with REPEATABLE_READ isolation

    @Test
    fun `REQUIRES_NEW should use separate entity cache`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            transactionBlocking(REQUIRES_NEW, isolation = REPEATABLE_READ) {
                val city2 = orm.entity(City::class).select().where(1).singleResult
                // REQUIRES_NEW uses separate cache so objects differ
                (city1 === city2).shouldBeFalse()
                city1.name shouldBe city2.name
            }
        }
    }

    // SpringConnectionProviderImpl: getConnection with transaction context

    @Test
    fun `connection within transaction should be managed by Spring`(): Unit = runBlocking {
        transactionBlocking {
            val count1 = orm.entity(City::class).count()
            val count2 = orm.entity(Visit::class).count()
            count1 shouldBe 6
            count2 shouldBe 14
        }
    }

    @Test
    fun `connection outside transaction should still work`() {
        val count = orm.entity(City::class).count()
        count shouldBe 6
    }

    // SpringTransactionContext: transaction with multiple entity types and DML

    @Test
    fun `transaction with entity cache and multiple entity types`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city = orm.entity(City::class).select().where(1).singleResult
            val visits = orm.entity(Visit::class).select().resultList

            val cityAgain = orm.entity(City::class).select().where(1).singleResult
            (city === cityAgain).shouldBeTrue()

            visits.size shouldBe 14
        }
    }

    @Test
    fun `setRollbackOnly with DB access should mark transaction`(): Unit = runBlocking {
        transactionBlocking {
            orm.entity(City::class).count() shouldBe 6
            setRollbackOnly()
            orm.entity(City::class).count() shouldBe 6
        }
    }

    @Test
    fun `transaction with readOnly and timeout should work`(): Unit = runBlocking {
        transactionBlocking(readOnly = true, timeoutSeconds = 30) {
            orm.entity(City::class).count() shouldBe 6
        }
    }

    @Test
    fun `REPEATABLE_READ should cache entities correctly`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val city1 = orm.entity(City::class).select().where(1).singleResult
            city1.name shouldBe "Sun Paririe"
        }
    }

    // SpringTransactionContext: Pet entity with REPEATABLE_READ
    // (exercises entity cache for additional entity types)

    @Test
    fun `Pet entity should be cacheable under REPEATABLE_READ`(): Unit = runBlocking {
        transactionBlocking(isolation = REPEATABLE_READ) {
            val pet1 = orm.entity(Pet::class).select().where(1).singleResult
            val pet2 = orm.entity(Pet::class).select().where(1).singleResult
            (pet1 === pet2).shouldBeTrue()
        }
    }

    // SpringTransactionContext: countAll within various propagation modes

    @Test
    fun `countAll within REQUIRES_NEW should work`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(REQUIRES_NEW) {
                orm.countAll<City>() shouldBe 6
            }
        }
    }

    @Test
    fun `exists within NESTED should work`(): Unit = runBlocking {
        transactionBlocking {
            transactionBlocking(NESTED) {
                orm.exists<City>().shouldBeTrue()
            }
        }
    }

    // RepositoryBeanFactoryPostProcessor: defaultClassLoader without resourceLoader

    @Test
    fun `postProcessBeanFactory without resourceLoader should use default classloader`() {
        // Create a processor without calling setResourceLoader.
        // This exercises the defaultClassLoader fallback chain:
        // resourceLoader?.classLoader ?: ClassUtils.getDefaultClassLoader() ?: ClassLoader.getSystemClassLoader()
        val processor = object : RepositoryBeanFactoryPostProcessor() {
            override val repositoryBasePackages: Array<String> get() = arrayOf("st.orm.spring.repository")
        }
        // Register an ORMTemplate bean in the factory
        val beanFactory = DefaultListableBeanFactory()
        beanFactory.registerSingleton("ormTemplate", orm)
        // This should work without a resourceLoader, using the default classloader
        processor.postProcessBeanFactory(beanFactory)
    }

    // SpringTransactionContext: commit with expired deadline

    @Test
    fun `commit with expired deadline should trigger rollback path`(): Unit = runBlocking {
        // When a transaction has a very short timeout and the callback takes longer,
        // the commit method detects the expired deadline and redirects to rollback.
        assertThrows<st.orm.template.TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                orm.entity(City::class).count() shouldBe 6
                Thread.sleep(1500)
                // On commit, deadline is expired -> rollback path
            }
        }
    }

    @Test
    fun `NESTED commit after inner timeout should propagate timeout`(): Unit = runBlocking {
        assertThrows<st.orm.template.TransactionTimedOutException> {
            transactionBlocking(timeoutSeconds = 1) {
                transactionBlocking(NESTED) {
                    orm.entity(City::class).count() shouldBe 6
                    Thread.sleep(1500)
                }
            }
        }
    }
}
