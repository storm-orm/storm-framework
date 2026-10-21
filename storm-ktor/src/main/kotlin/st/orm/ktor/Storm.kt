/*
 * Copyright 2024 - 2026 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
@file:OptIn(InternalStormApi::class)

package st.orm.ktor

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStarted
import io.ktor.server.application.ApplicationStopped
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.log
import io.ktor.server.plugins.di.DependencyKey
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.getBlocking
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import io.ktor.util.reflect.TypeInfo
import io.micrometer.observation.ObservationConvention
import io.micrometer.observation.ObservationRegistry
import org.slf4j.LoggerFactory
import st.orm.core.spi.JdbcConnectionProviderImpl
import st.orm.core.spi.JdbcTransactionTemplateProviderImpl
import st.orm.core.template.impl.AdviceLog
import st.orm.core.template.impl.CallSiteCapture
import st.orm.core.template.impl.SlowStatementLog
import st.orm.core.template.impl.SqlLogRenderer
import st.orm.micrometer.QueryObservers
import st.orm.micrometer.StormQueryObservationContext
import st.orm.micrometer.StormTransactionObservationContext
import st.orm.template.InternalStormApi
import st.orm.template.ORMTemplate
import st.orm.template.recordSqlLog
import javax.sql.DataSource
import kotlin.reflect.KClass
import kotlin.reflect.full.starProjectedType
import kotlin.reflect.typeOf
import kotlin.time.toJavaDuration

/**
 * Ktor plugin that configures Storm ORM for the application.
 *
 * The plugin creates an [st.orm.template.ORMTemplate] from either a user-provided [javax.sql.DataSource] or one
 * auto-created from the HOCON configuration under `storm.datasource`. The template is stored in the application's
 * attributes and can be accessed via extension properties on [io.ktor.server.application.Application],
 * [io.ktor.server.application.ApplicationCall], and [io.ktor.server.routing.RoutingContext].
 *
 * Repositories from the compile-time type index are registered automatically during installation and are
 * available via `repository<T>()` without further setup. Narrow the registration with `repositories(...)` or
 * disable it with `autoRegisterRepositories = false`.
 *
 * Usage:
 * ```kotlin
 * fun Application.module() {
 *     install(Storm) {
 *         // Option A: auto-configure from application.conf (zero config)
 *
 *         // Option B: provide your own DataSource
 *         dataSource = HikariDataSource(hikariConfig)
 *
 *         // Option C: override Storm config
 *         config = StormConfig.of(mapOf("storm.update.default_mode" to "FIELD"))
 *
 *         // Optional: schema validation
 *         schemaValidation = "warn"
 *
 *         // Optional: entity callbacks
 *         entityCallback(AuditCallback())
 *
 *         // Optional: narrow repository auto-registration
 *         repositories("com.myapp.repository")
 *
 *         // Optional: additional, named databases
 *         database("analytics") {
 *             repositories("com.myapp.analytics")
 *         }
 *     }
 * }
 * ```
 *
 * Additional databases declared with `database("name") { }` get their own template, repositories, schema
 * validation, and lifecycle, exposed under their name: `orm("name")`, `repository<T>("name")`, and named
 * dependency injection. The packages declared inside a database block partition repositories and schema
 * validation between the databases. A named database inherits the plugin-level policy settings, per option,
 * unless its block overrides them; see [StormDatabaseConfig] for the inheritance rule.
 *
 * @since 1.11
 */
@OptIn(InternalStormApi::class)
public val Storm: ApplicationPlugin<StormPluginConfig> = createApplicationPlugin(name = "Storm", createConfiguration = ::StormPluginConfig) {

    // Packages claimed by the named databases (package name to database name). These partition repository
    // registration and schema validation: types under a claimed package belong to that database only.
    val claimedPackages = mutableMapOf<String, String>()
    for (databaseConfig in pluginConfig.databases.values) {
        for (packageName in databaseConfig.repositoryPackages) {
            val existingOwner = claimedPackages.put(packageName, databaseConfig.name)
            if (existingOwner != null && existingOwner != databaseConfig.name) {
                throw IllegalStateException(
                    "Package '$packageName' is declared by both database '$existingOwner' and " +
                        "database '${databaseConfig.name}'. Each package can belong to only one database.",
                )
            }
        }
    }

    // Query observers, keyed by database name (null for the primary database). An explicit queryObserver always
    // wins; otherwise a delegating observer is installed that binds to the ObservationRegistry from the
    // dependency container once the application has started, composing its convention from the database's
    // configured semantic conventions and its own data source.
    val delegatingObservers = LinkedHashMap<String?, PendingObservationBinding>()
    // Validated at installation so a typo fails startup rather than silently changing the observation identity.
    val rootSemanticConventions = application.readSemanticConventions().also {
        QueryObservers.semanticConventionFor(it, "storm.observations.semanticConventions", null)
    }

    // ---- Per-call SQL log settings ----

    // Resolved before any template exists: the first template checks the model against the design budget, which
    // reports design advice only where its category is on. Ktor's development mode reports every category unless the
    // application names them.
    val sqlLogSettings = resolveSqlLogSettings(application, pluginConfig)
    if (sqlLogSettings.entityBudgetJoins != null || sqlLogSettings.entityBudgetColumns != null) {
        AdviceLog.entityBudget(sqlLogSettings.entityBudgetJoins, sqlLogSettings.entityBudgetColumns)
    }
    val adviceCategories = sqlLogSettings.adviceCategories
    if (adviceCategories != null) {
        AdviceLog.categories(adviceCategories)
    } else if (application.developmentMode) {
        AdviceLog.development()
    }

    // ---- Primary database ----

    // Ownership is decided at creation: only pools the plugin builds from configuration are closed at
    // shutdown, never a user-supplied instance.
    val ownedDataSources = mutableListOf<DataSource>()
    val dataSource = pluginConfig.dataSource
        ?: createDataSourceFromConfig(application).also { ownedDataSources += it }
    val stormConfig = pluginConfig.config ?: readStormConfig(application)

    // Run the migration hook (e.g., Flyway) before the ORM template is created and the schema is
    // validated, so validation always sees the migrated schema.
    pluginConfig.migration?.invoke(dataSource)

    // Compose the template with explicit, plugin-scoped integration strategies: one provider instance per
    // database, so all repositories of a database share transactions, and the ambient transaction { }
    // API binds to the database's provider when its template executes inside a transaction block.
    val engine = st.orm.core.template.ORMTemplate.builder(dataSource)
        .connectionProvider(pluginConfig.connectionProvider ?: JdbcConnectionProviderImpl())
        .transactionTemplateProvider(pluginConfig.transactionTemplateProvider ?: JdbcTransactionTemplateProviderImpl())
    val builder = ORMTemplate.Builder(engine).config(stormConfig)
    pluginConfig.exceptionMapper?.let { builder.exceptionMapper(it) }
    pluginConfig.sqlCommenter?.let { builder.sqlCommenter(it) }
    builder.queryObserver(
        pluginConfig.queryObserver ?: DelegatingQueryObserver().also {
            delegatingObservers[null] = PendingObservationBinding(it, dataSource, rootSemanticConventions)
        },
    )
    pluginConfig.customize?.invoke(builder)
    var ormTemplate = builder.build()
    if (pluginConfig.entityCallbacks.isNotEmpty()) {
        ormTemplate = ormTemplate.withEntityCallbacks(pluginConfig.entityCallbacks)
    }

    application.attributes.put(OrmTemplateKey, ormTemplate)
    application.attributes.put(DataSourceKey, dataSource)

    // Create the repository registry and auto-register repositories from the compile-time type index.
    // Eager registration creates the proxies now, so a broken repository definition fails at startup
    // rather than at first request. Narrow with repositories("com.myapp") or disable with
    // autoRegisterRepositories = false; unregistered types are still created lazily on first access.
    // Types under packages claimed by a named database are excluded here and registered there instead.
    val repositoryRegistry = RepositoryRegistry(ormTemplate, application)
    repositoryRegistry.claimedPackages = claimedPackages
    if (pluginConfig.autoRegisterRepositories) {
        repositoryRegistry.register(*pluginConfig.repositoryPackages.toTypedArray())
    }
    application.attributes.put(RepositoryRegistryKey, repositoryRegistry)

    // ---- Named databases ----

    // A named database inherits the plugin-level policy settings, per option, unless its block overrides them:
    // the Storm configuration (per key), the exception mapper, query observer, SQL commenter, entity callbacks,
    // schema validation mode, and the auto-registration switch. The connection and transaction template
    // providers never inherit: a provider instance defines a database's transaction scope, so each database
    // gets its own unless its block sets one.
    val namedTemplates = LinkedHashMap<String, ORMTemplate>()
    val namedDataSources = LinkedHashMap<String, DataSource>()
    val namedRegistries = LinkedHashMap<String, RepositoryRegistry>()
    for (databaseConfig in pluginConfig.databases.values) {
        val name = databaseConfig.name
        val databaseDataSource = databaseConfig.dataSource
            ?: createDataSourceFromConfig(application, "storm.databases.$name.datasource")
                .also { ownedDataSources += it }
        val databaseStormConfig = databaseConfig.config
            ?: readStormConfig(application, "storm.databases.$name", fallback = stormConfig)
        databaseConfig.migration?.invoke(databaseDataSource)
        val databaseEngine = st.orm.core.template.ORMTemplate.builder(databaseDataSource)
            .connectionProvider(databaseConfig.connectionProvider ?: JdbcConnectionProviderImpl())
            .transactionTemplateProvider(
                databaseConfig.transactionTemplateProvider ?: JdbcTransactionTemplateProviderImpl(),
            )
        val databaseBuilder = ORMTemplate.Builder(databaseEngine).config(databaseStormConfig)
        (databaseConfig.exceptionMapper ?: pluginConfig.exceptionMapper)?.let { databaseBuilder.exceptionMapper(it) }
        (databaseConfig.sqlCommenter ?: pluginConfig.sqlCommenter)?.let { databaseBuilder.sqlCommenter(it) }
        val databaseSemanticConventions = application.readSemanticConventions("storm.databases.$name")
            ?.also { QueryObservers.semanticConventionFor(it, "storm.databases.$name.observations.semanticConventions", null) }
            ?: rootSemanticConventions
        databaseBuilder.queryObserver(
            databaseConfig.queryObserver ?: pluginConfig.queryObserver
                ?: DelegatingQueryObserver().also {
                    delegatingObservers[name] = PendingObservationBinding(it, databaseDataSource, databaseSemanticConventions)
                },
        )
        (databaseConfig.customize ?: pluginConfig.customize)?.invoke(databaseBuilder)
        var databaseTemplate = databaseBuilder.build()
        val databaseCallbacks = pluginConfig.entityCallbacks + databaseConfig.entityCallbacks
        if (databaseCallbacks.isNotEmpty()) {
            databaseTemplate = databaseTemplate.withEntityCallbacks(databaseCallbacks)
        }
        val databaseRegistry = RepositoryRegistry(databaseTemplate, application)
        databaseRegistry.databaseName = name
        databaseRegistry.ownedPackages = databaseConfig.repositoryPackages.toList()
        databaseRegistry.claimedPackages = claimedPackages.filterValues { it != name }
        if (pluginConfig.autoRegisterRepositories && databaseConfig.repositoryPackages.isNotEmpty()) {
            databaseRegistry.register(*databaseConfig.repositoryPackages.toTypedArray())
        }
        namedTemplates[name] = databaseTemplate
        namedDataSources[name] = databaseDataSource
        namedRegistries[name] = databaseRegistry
    }
    application.attributes.put(NamedOrmTemplatesKey, namedTemplates)
    application.attributes.put(NamedDataSourcesKey, namedDataSources)
    application.attributes.put(NamedRepositoryRegistriesKey, namedRegistries)

    // ---- Dependency injection ----

    // Expose the templates and the registered repositories through Ktor's dependency injection. The primary
    // template is registered unnamed and each named database's template under its name. Repositories are always
    // registered unnamed under their own interface type: package partitioning guarantees each type belongs to
    // exactly one database, so `val visits: VisitRepository by dependencies` works regardless of the database.
    // Repositories created lazily after installation are not added to the container.
    if (pluginConfig.registerDependencies) {
        val providedKeys = mutableListOf<DependencyKey>()
        application.dependencies {
            provide<ORMTemplate> { ormTemplate }
        }
        providedKeys += DependencyKey<ORMTemplate>()
        providedKeys += application.registerRepositories(repositoryRegistry)
        for ((name, template) in namedTemplates) {
            application.dependencies {
                provide<ORMTemplate>(name) { template }
            }
            providedKeys += DependencyKey<ORMTemplate>(name)
            providedKeys += application.registerRepositories(namedRegistries.getValue(name))
        }
        // The container resolves dependencies lazily; a declaration nobody resolves keeps an unfinished
        // deferred that the container cancels at shutdown, logging a spurious "Exception during cleanup"
        // warning for the key and each of its covariant supertype keys. Resolving every provided key once
        // at startup completes those deferreds. The instances already exist, so this creates nothing.
        // Resolution failures stay per key: this loop only suppresses shutdown noise, so a key nothing in
        // the application ever resolves (an ambiguous covariant repository key, for example) must not abort
        // startup, which is what an exception thrown through Events.raise would do.
        application.monitor.subscribe(ApplicationStarted) {
            for (providedKey in providedKeys) {
                try {
                    application.dependencies.getBlocking<Any?>(providedKey)
                } catch (e: Throwable) {
                    application.log.debug("Eager resolution of $providedKey failed; the key stays lazily resolvable.", e)
                }
            }
        }
    }

    // ---- Schema validation ----

    // The packages declared by the named databases partition validation: each database validates only the entity
    // and projection types under its packages, and the primary validates everything else.
    val rootSchemaMode = pluginConfig.schemaValidation
        ?: application.environment.config.propertyOrNull("storm.validation.schemaMode")?.getString()
        ?: application.environment.config.propertyOrNull("storm.validation.schema_mode")?.getString()
        ?: "fail"
    application.runSchemaValidation(
        template = ormTemplate,
        configuredMode = rootSchemaMode,
        property = "storm.validation.schemaMode",
        description = "primary database",
    ) { type -> claimedPackages.keys.none { type.java.name.startsWith("$it.") } }
    for (databaseConfig in pluginConfig.databases.values) {
        val name = databaseConfig.name
        application.runSchemaValidation(
            template = namedTemplates.getValue(name),
            // Per-database settings before inherited ones: the database's block, then its configuration keys,
            // then the plugin-level mode the primary uses.
            configuredMode = databaseConfig.schemaValidation
                ?: application.environment.config.propertyOrNull("storm.databases.$name.validation.schemaMode")?.getString()
                ?: application.environment.config.propertyOrNull("storm.databases.$name.validation.schema_mode")?.getString()
                ?: rootSchemaMode,
            property = "storm.databases.$name.validation.schemaMode",
            description = "database '$name'",
        ) { type -> databaseConfig.repositoryPackages.any { type.java.name.startsWith("$it.") } }
    }

    // ---- Observability ----

    // The ObservationRegistry may be registered by any module, so it only becomes resolvable once the
    // application has started. Bind the delegating observers then; without a registry they stay no-op. Queries
    // issued during installation (such as schema validation) run before binding and are not observed.
    if (delegatingObservers.isNotEmpty()) {
        application.monitor.subscribe(ApplicationStarted) {
            application.bindQueryObservations(delegatingObservers)
        }
    }

    // ---- Per-call SQL log ----

    // Each option resolves from the plugin configuration first, then the application configuration under
    // storm.sqlLog, so either log can be switched on through the configuration file alone. The display settings
    // (call-site skip, line width) are JVM-wide by design and are only applied when actually configured, leaving
    // the system-property defaults, or another application's settings, in effect otherwise.
    // The slow statement log needs no request boundary: it reports each execution whose database time exceeds
    // the threshold, wherever it runs, so it applies with or without the performance log. Left unset while the
    // performance log runs against a duration threshold, it takes that duration: a call holds at least one
    // execution, so the statement grain can only be exceeded inside a call that is reported anyway, which names
    // the statement behind a warning rather than adding one.
    val slowThreshold = sqlLogSettings.slowThreshold
        ?: if (sqlLogSettings.enabled) sqlLogSettings.durationThreshold else null
    slowThreshold?.let { SlowStatementLog.threshold(it.toJavaDuration()) }
    sqlLogSettings.slowLimit?.let { SlowStatementLog.limit(it) }
    if (sqlLogSettings.enabled) {
        val limit = sqlLogSettings.limit
        val callSites = sqlLogSettings.callSites
        if (sqlLogSettings.callSiteSkip.isNotEmpty()) {
            CallSiteCapture.ignoreCallSites(*sqlLogSettings.callSiteSkip.toTypedArray())
        }
        sqlLogSettings.lineWidth?.let { SqlLogRenderer.lineWidth(it) }
        val statementThreshold = sqlLogSettings.statementThreshold
        val durationThreshold = sqlLogSettings.durationThreshold
        val thresholded = statementThreshold != null || durationThreshold != null
        val logger = LoggerFactory.getLogger("st.orm.sql.perf")
        // Intercepting surrounds the rest of the pipeline, so the scope covers everything the call does rather
        // than a point within it. The scope follows the coroutine, so it keeps recording across a suspension
        // that resumes on another thread, which is exactly what a handler does around the database.
        application.intercept(ApplicationCallPipeline.Monitoring) {
            if (if (thresholded) !logger.isWarnEnabled else !logger.isInfoEnabled) {
                // Nothing consumes the summary, so do not open a scope to build one.
                proceed()
                return@intercept
            }
            val name = context.request.httpMethod.value + " " + context.request.path()
            recordSqlLog(name, limit, callSites, { proceed() }) { summary ->
                // A call that touched no database says nothing worth a line. Without thresholds every call that
                // did is reported; with one, only calls that exceed it are, at WARN.
                // At TRACE the full statement texts follow the summary, so an elided row can be matched to its
                // statement. TRACE rather than DEBUG because this logger is a child of st.orm.sql: raising that
                // to DEBUG for per-statement logging would otherwise repeat every statement already written.
                val rendered = if (logger.isTraceEnabled) summary.toDetailedString() else summary
                when {
                    summary.statementCount() == 0 -> {}
                    !thresholded -> logger.info("{}", rendered)
                    (statementThreshold != null && summary.statementCount() >= statementThreshold) ||
                        (
                            durationThreshold != null &&
                                summary.durationNanos() >= durationThreshold.inWholeNanoseconds
                            ) ->
                        logger.warn("{}", rendered)
                }
            }
        }
    }

    // Close the pools the plugin created; user-supplied data sources stay under the caller's control.
    application.monitor.subscribe(ApplicationStopped) {
        ownedDataSources.forEach { closeOwnedDataSource(it) }
    }
}

/**
 * A delegating query observer awaiting its binding, together with the data source and configured semantic
 * conventions its composed observer follows.
 */
internal class PendingObservationBinding(
    val observer: DelegatingQueryObserver,
    val dataSource: DataSource,
    val semanticConventions: String?,
)

/**
 * Reads the configured semantic conventions under the given prefix: `<prefix>.observations.semanticConventions`,
 * or the `semantic_conventions` spelling.
 */
private fun Application.readSemanticConventions(prefix: String = "storm"): String? = environment.config.propertyOrNull("$prefix.observations.semanticConventions")?.getString()
    ?: environment.config.propertyOrNull("$prefix.observations.semantic_conventions")?.getString()

/**
 * Binds the delegating query observers to the [ObservationRegistry] from the dependency container, if one is
 * registered. Every observation carries a `storm.database` key value: the database name, or `primary` for the
 * primary database. The tag is always present because meters of one name must share a single set of tag keys;
 * registries such as Prometheus drop series whose tag keys differ.
 *
 * The query convention follows the configured semantic conventions, resolved per database:
 * `storm.observations.semanticConventions = otel` selects the OpenTelemetry database semantic conventions with
 * the database product read from each database's own data source, and a database block overrides the value
 * under `storm.databases.<name>.observations.semanticConventions`. An
 * `ObservationConvention<StormQueryObservationContext>` registered in the dependency container overrides the
 * configuration for every database, and an `ObservationConvention<StormTransactionObservationContext>` the
 * transaction observations, mirroring the convention beans of the Spring Boot starters.
 */
private fun Application.bindQueryObservations(delegatingObservers: Map<String?, PendingObservationBinding>) {
    val registryKey = DependencyKey(TypeInfo(ObservationRegistry::class, ObservationRegistry::class.starProjectedType))
    if (!dependencies.contains(registryKey)) {
        return
    }
    val observationRegistry = dependencies.getBlocking<ObservationRegistry>(registryKey)
    val convention = resolveObservationConvention()
    val transactionConvention = resolveTransactionObservationConvention()
    for ((databaseName, binding) in delegatingObservers) {
        val databaseConvention = convention ?: QueryObservers.semanticConventionFor(
            binding.semanticConventions,
            "storm.observations.semanticConventions",
            binding.dataSource,
        )
        binding.observer.delegate = QueryObservers.create(
            observationRegistry,
            databaseConvention,
            transactionConvention,
            databaseName ?: "primary",
        )
    }
    log.info("Storm query observations enabled via the ObservationRegistry from the dependency container.")
}

/**
 * Resolves a query observation convention from the dependency container, registered either under the
 * parameterized type or under a plain [ObservationConvention].
 */
@Suppress("UNCHECKED_CAST")
private fun Application.resolveObservationConvention(): ObservationConvention<StormQueryObservationContext>? {
    val keys = listOf(
        DependencyKey(
            TypeInfo(
                ObservationConvention::class,
                typeOf<ObservationConvention<StormQueryObservationContext>>(),
            ),
        ),
        DependencyKey(TypeInfo(ObservationConvention::class, ObservationConvention::class.starProjectedType)),
    )
    val key = keys.firstOrNull { dependencies.contains(it) } ?: return null
    return dependencies.getBlocking<ObservationConvention<StormQueryObservationContext>>(key)
}

/**
 * Resolves a transaction observation convention from the dependency container, registered under the
 * parameterized type. The plain [ObservationConvention] fallback belongs to the query convention.
 */
private fun Application.resolveTransactionObservationConvention(): ObservationConvention<StormTransactionObservationContext>? {
    val key = DependencyKey(
        TypeInfo(
            ObservationConvention::class,
            typeOf<ObservationConvention<StormTransactionObservationContext>>(),
        ),
    )
    if (!dependencies.contains(key)) {
        return null
    }
    return dependencies.getBlocking<ObservationConvention<StormTransactionObservationContext>>(key)
}

/**
 * Registers every repository of the given registry in the dependency container, each under its own interface type.
 *
 * @return the keys the repositories are registered under.
 */
private fun Application.registerRepositories(registry: RepositoryRegistry): List<DependencyKey> {
    val registeredKeys = mutableListOf<DependencyKey>()
    registry.forEach { type, instance ->
        val key = DependencyKey(TypeInfo(type, type.starProjectedType))
        dependencies {
            set(key) { instance }
        }
        registeredKeys += key
    }
    return registeredKeys
}

/**
 * Runs schema validation for one database's template, limited to the types accepted by [filter].
 *
 * The mode is matched case-insensitively after trimming; a blank value means the `fail` default, matching the
 * Spring entry point. Any other value is a configuration error and fails installation, so a typo cannot
 * silently disable validation. [property] names the configuration key in that error.
 */
private fun Application.runSchemaValidation(
    template: ORMTemplate,
    configuredMode: String,
    property: String,
    description: String,
    filter: (KClass<out st.orm.Data>) -> Boolean,
) {
    when (configuredMode.trim().lowercase().ifEmpty { "fail" }) {
        "none" -> {}
        "fail" -> {
            template.validateSchemaOrThrow(filter)
            log.info("Storm schema validation passed for $description (mode=fail).")
        }
        "warn" -> template.validateSchema(filter).forEach { log.warn(it) }
        "load" -> template.loadSchema(filter)
        else -> throw IllegalStateException(
            "Invalid schema validation mode '$configuredMode' for $description " +
                "(schemaValidation option or $property). Valid values are: none, load, warn, fail.",
        )
    }
}
