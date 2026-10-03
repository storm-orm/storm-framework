package st.orm.ktor

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.slf4j.event.EventRecordingLogger
import org.slf4j.event.Level
import org.slf4j.event.SubstituteLoggingEvent
import org.slf4j.helpers.SubstituteLogger
import st.orm.EntityCallback
import st.orm.ktor.model.PetRepository
import st.orm.ktor.vet.Vet
import st.orm.ktor.vet.VetRepository
import st.orm.spi.ExceptionMapper
import st.orm.spi.QueryContext
import st.orm.spi.QueryObserver
import st.orm.template.ORMTemplate
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the named-database support of the [Storm] plugin: per-database templates and repositories, package
 * partitioning of registration and schema validation, named accessors, and dependency injection.
 *
 * The scenario follows the pet clinic domain: the clinic's own database holds the pets, while the vet registry
 * lives in a separate, named database.
 */
internal class StormMultipleDatabasesTest {

    private fun createTestDataSource(name: String, schemaResource: String? = null): HikariDataSource {
        val config = HikariConfig().apply {
            jdbcUrl = "jdbc:h2:mem:$name-${System.nanoTime()};DB_CLOSE_DELAY=-1"
            driverClassName = "org.h2.Driver"
            username = "sa"
            password = ""
            maximumPoolSize = 2
        }
        val dataSource = HikariDataSource(config)
        if (schemaResource != null) {
            dataSource.connection.use { connection ->
                connection.createStatement().use { statement ->
                    val sql = this::class.java.getResourceAsStream(schemaResource)!!.bufferedReader().readText()
                    for (line in sql.split(";")) {
                        val trimmed = line.trim()
                        if (trimmed.isNotEmpty()) {
                            statement.execute(trimmed)
                        }
                    }
                }
            }
        }
        return dataSource
    }

    private fun withClinicAndVetsDatabases(block: Application.() -> Unit) = withClinicAndVetsDatabases(configure = {}) { block() }

    private fun withClinicAndVetsDatabases(
        configure: StormPluginConfig.() -> Unit,
        configureVets: StormDatabaseConfig.() -> Unit = {},
        block: Application.(vetsDataSource: HikariDataSource) -> Unit,
    ) {
        val clinicDataSource = createTestDataSource("storm-multi-clinic", "/schema.sql")
        val vetsDataSource = createTestDataSource("storm-multi-vets", "/schema-vets.sql")
        try {
            testApplication {
                application {
                    install(Storm) {
                        dataSource = clinicDataSource
                        configure()
                        // The primary must not see the vet package; partitioning also keeps schema
                        // validation (mode "fail" by default) green for both databases.
                        database("vets") {
                            dataSource = vetsDataSource
                            repositories("st.orm.ktor.vet")
                            configureVets()
                        }
                    }
                    block(vetsDataSource)
                }
            }
        } finally {
            clinicDataSource.close()
            vetsDataSource.close()
        }
    }

    @Test
    fun `each database gets its own template and repositories`() = withClinicAndVetsDatabases {
        val vetsDatabase = orm("vets")
        vetsDatabase shouldNotBe orm
        val vets = repository<VetRepository>("vets")
        vets.findAll().size shouldBe 2
        // The clinic's own database keeps working against its own schema.
        repository<PetRepository>().findAll().shouldNotBeEmpty()
    }

    @Test
    fun `repositories of a claimed package are not served by the primary database`() = withClinicAndVetsDatabases {
        val exception = shouldThrow<IllegalStateException> {
            repository<VetRepository>()
        }
        exception.message!! shouldContain "vets"
    }

    @Test
    fun `named template and repositories are resolvable through dependency injection`() = withClinicAndVetsDatabases {
        // The primary template resolves unnamed; each named database's template resolves under its name.
        val primary = runBlocking { dependencies.resolve<ORMTemplate>() }
        primary shouldBeSameInstanceAs orm
        val vetsDatabase = runBlocking { dependencies.resolve<ORMTemplate>("vets") }
        vetsDatabase shouldBeSameInstanceAs orm("vets")
        // Repositories are registered unnamed under their own type, regardless of database.
        val vets: VetRepository by dependencies
        vets shouldBeSameInstanceAs repository<VetRepository>("vets")
        val pets: PetRepository by dependencies
        pets shouldBeSameInstanceAs repository<PetRepository>()
    }

    @Test
    fun `writes go to the right database`() = withClinicAndVetsDatabases {
        val vets = repository<VetRepository>("vets")
        val insertedVet = vets.insertAndFetch(Vet(firstName = "Sharon", lastName = "Jenkins"))
        insertedVet.id shouldNotBe 0
        vets.findAll().size shouldBe 3
        // The clinic database has no vet rows; its repositories are untouched.
        repository<PetRepository>().findAll().size shouldBe 3
    }

    @Test
    fun `unknown database name fails with a descriptive error`() = withClinicAndVetsDatabases {
        val exception = shouldThrow<IllegalStateException> {
            orm("specialties")
        }
        exception.message!! shouldContain "No database named 'specialties'"
        exception.message!! shouldContain "vets"
    }

    @Test
    fun `an unclaimed repository type is not served by a named database`() = withClinicAndVetsDatabases {
        val exception = shouldThrow<IllegalStateException> {
            repository<PetRepository>("vets")
        }
        exception.message!! shouldContain "does not belong to database 'vets'"
        exception.message!! shouldContain "'st.orm.ktor.vet'"
        exception.message!! shouldContain "repository<PetRepository>()"
        exception.message!! shouldContain "repositories(\"st.orm.ktor.model\")"
    }

    @Test
    fun `autoRegisterRepositories false covers named databases`() {
        withClinicAndVetsDatabases(configure = { autoRegisterRepositories = false }) {
            val vetsRegistry = attributes[NamedRepositoryRegistriesKey].getValue("vets")
            shouldThrow<IllegalStateException> {
                vetsRegistry.get(VetRepository::class)
            }.message!! shouldContain "not registered"
            // The claimed type is still created lazily on first access.
            repository<VetRepository>("vets").findAll().size shouldBe 2
        }
    }

    @Test
    fun `plugin-level entity callbacks apply to named databases`() {
        val pluginCallback = RecordingVetCallback()
        val databaseCallback = RecordingVetCallback()
        withClinicAndVetsDatabases(
            configure = { entityCallback(pluginCallback) },
            configureVets = { entityCallback(databaseCallback) },
        ) {
            repository<VetRepository>("vets").insert(Vet(firstName = "Sharon", lastName = "Jenkins"))
            // The database's own callbacks apply in addition to the inherited plugin-level ones.
            pluginCallback.inserted.size shouldBe 1
            databaseCallback.inserted.size shouldBe 1
        }
    }

    @Test
    fun `plugin-level query observer applies to named databases`() {
        val pluginObserver = RecordingQueryObserver()
        withClinicAndVetsDatabases(configure = { queryObserver = pluginObserver }) {
            val before = pluginObserver.executions.get()
            repository<VetRepository>("vets").findAll()
            pluginObserver.executions.get() shouldBeGreaterThan before
        }
    }

    @Test
    fun `database-level query observer overrides the inherited one`() {
        val pluginObserver = RecordingQueryObserver()
        val databaseObserver = RecordingQueryObserver()
        withClinicAndVetsDatabases(
            configure = { queryObserver = pluginObserver },
            configureVets = { queryObserver = databaseObserver },
        ) {
            val before = pluginObserver.executions.get()
            repository<VetRepository>("vets").findAll()
            databaseObserver.executions.get() shouldBeGreaterThan 0
            pluginObserver.executions.get() shouldBe before
        }
    }

    @Test
    fun `plugin-level exception mapper applies to named databases`() {
        withClinicAndVetsDatabases(
            configure = { exceptionMapper = ExceptionMapper { cause, _ -> MarkedException(cause) } },
        ) { vetsDataSource ->
            vetsDataSource.connection.use { connection ->
                connection.createStatement().use { it.execute("DROP TABLE vet") }
            }
            shouldThrow<MarkedException> {
                repository<VetRepository>("vets").findAll()
            }
        }
    }

    @Test
    fun `named databases inherit the plugin-level schema validation mode`() {
        val clinicDataSource = createTestDataSource("storm-multi-clinic", "/schema.sql")
        // No schema at all: with the inherited "warn" mode the mismatches log instead of aborting
        // installation. The application's log records them, so the test reads what was reported.
        val emptyVetsDataSource = createTestDataSource("storm-multi-vets-empty")
        val logged = ConcurrentLinkedQueue<SubstituteLoggingEvent>()
        try {
            testApplication {
                environment {
                    log = EventRecordingLogger(SubstituteLogger("application", logged, false), logged)
                }
                application {
                    install(Storm) {
                        dataSource = clinicDataSource
                        schemaValidation = "warn"
                        database("vets") {
                            dataSource = emptyVetsDataSource
                            repositories("st.orm.ktor.vet")
                        }
                    }
                    orm("vets") shouldNotBe null
                }
                startApplication()
            }
            logged.any { it.level == Level.WARN && "TABLE_NOT_FOUND" in it.message } shouldBe true
        } finally {
            clinicDataSource.close()
            emptyVetsDataSource.close()
        }
    }
}

/**
 * Entity callback that records the vets it saw, for asserting which database's template it was applied to.
 */
private class RecordingVetCallback : EntityCallback<Vet> {
    val inserted = CopyOnWriteArrayList<Vet>()

    override fun beforeInsert(entity: Vet): Vet {
        inserted += entity
        return entity
    }
}

/**
 * Query observer that counts executions, for asserting which database's template it was installed on.
 */
private class RecordingQueryObserver : QueryObserver {
    val executions = AtomicInteger()

    override fun onExecute(context: QueryContext): QueryObserver.Observation {
        executions.incrementAndGet()
        return QueryObserver.noop().onExecute(context)
    }
}

private class MarkedException(cause: Throwable) : RuntimeException(cause)
