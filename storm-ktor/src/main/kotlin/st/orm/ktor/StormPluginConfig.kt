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
package st.orm.ktor

import st.orm.EntityCallback
import st.orm.StormConfig
import javax.sql.DataSource
import kotlin.time.Duration

/**
 * Configuration DSL for the Storm Ktor plugin.
 *
 * Example usage:
 * ```kotlin
 * install(Storm) {
 *     dataSource = HikariDataSource(hikariConfig)
 *     schemaValidation = "warn"
 *     entityCallback(AuditCallback())
 * }
 * ```
 */
public class StormPluginConfig {

    /**
     * The [DataSource] to use. If not provided, one will be created from the HOCON configuration under
     * `storm.datasource`.
     */
    public var dataSource: DataSource? = null

    /**
     * Optional [StormConfig] override. If not provided, configuration is read from the HOCON configuration under
     * `storm`.
     */
    public var config: StormConfig? = null

    /**
     * Optional [st.orm.core.spi.ConnectionProvider] override. When not set, the plugin uses the coroutine-aware
     * provider that binds connections to Storm's programmatic transactions.
     *
     * @since 1.13
     */
    public var connectionProvider: st.orm.core.spi.ConnectionProvider? = null

    /**
     * Optional [st.orm.core.spi.TransactionTemplateProvider] override. When not set, the plugin uses the JDBC
     * transaction provider that backs Storm's `transaction { }` API. Templates that should share transactions must
     * use the same provider instance.
     *
     * @since 1.13
     */
    public var transactionTemplateProvider: st.orm.core.spi.TransactionTemplateProvider? = null

    /**
     * Optional [st.orm.spi.ExceptionMapper] that maps failures raised during query execution to the runtime
     * exception thrown to the caller. Applies to every database unless a database block sets its own.
     *
     * @since 1.13
     */
    public var exceptionMapper: st.orm.spi.ExceptionMapper? = null

    /**
     * Optional [st.orm.spi.QueryObserver] that is notified of query executions, for metrics and tracing
     * bindings. Applies to every database unless a database block sets its own.
     *
     * @since 1.13
     */
    public var queryObserver: st.orm.spi.QueryObserver? = null

    /**
     * Optional [st.orm.spi.SqlCommenter] that appends per-execution comment content to statements, such
     * as the current trace context ([st.orm.micrometer.TraceContextSqlCommenter]). Note that per-execution
     * content defeats prepared statement caching; enable selectively. Applies to every database unless a
     * database block sets its own.
     *
     * @since 1.13
     */
    public var sqlCommenter: st.orm.spi.SqlCommenter? = null

    /**
     * Optional composition applied to each database's template builder after the plugin has wired the
     * integration, such as a table name resolver. Applies to every database unless a database block sets its
     * own. Overriding an integration strategy here remains possible for templates that want custom behavior.
     *
     * @since 1.14
     */
    public var customize: (st.orm.template.ORMTemplate.Builder.() -> Unit)? = null

    /**
     * Whether each call is wrapped in a SQL log whose summary is logged, reporting what one request cost the
     * database: how many statements it took, how long they took against how long the call took, and which
     * statement carried the weight.
     *
     * ```
     * SQL (GET /owners/42): 12 statements, 8 fetches, 34 ms in database, 96 ms total
     * 	18 ms  112 rows  4x  Pet           SELECT p.id, p.name FROM pet p WHERE p.owner_id = ?
     * 	 9 ms    8 rows  8x  City  fetch   SELECT c.id, c.name FROM city c WHERE c.id = ?
     * ```
     *
     * The summary logs under `st.orm.sql.perf` at INFO. Statements are recorded only while that logger is
     * enabled, so leaving this on costs nothing once the logger is turned down. Disabled by default.
     *
     * Every `sqlLog*` option can come from the application configuration instead, under `storm.sqlLog` (or
     * `storm.sql_log`), so the log can be switched on in production without a redeploy. A setting here overrides
     * the configuration file; when neither sets an option, its default applies.
     *
     * ```
     * storm.sqlLog {
     *     performance {
     *         enabled = true
     *         threshold {
     *             statements = 50
     *             duration = 500ms
     *         }
     *     }
     *     slow {
     *         threshold = 200ms
     *     }
     * }
     * ```
     *
     * Configuration key `storm.sqlLog.performance.enabled`. For a narrower boundary than a request, open a scope
     * directly with [st.orm.template.sqlLog].
     *
     * @since 1.13
     */
    public var sqlLogPerformance: Boolean? = null

    /**
     * Number of statements a per-request scope records; the summary counts the rest regardless. Bounds what a
     * single runaway call can retain and print. Configuration key `storm.sqlLog.performance.limit`; defaults to 200.
     *
     * @since 1.13
     */
    public var sqlLogPerformanceLimit: Int? = null

    /**
     * Number of statements above which a call's summary is reported, at WARN. With any threshold set, only
     * calls that exceed one are reported, which is the guardrail form suited to production; without thresholds
     * every call that touches the database is reported at INFO. Configuration key
     * `storm.sqlLog.performance.threshold.statements`.
     *
     * @since 1.13
     */
    public var sqlLogPerformanceStatementThreshold: Int? = null

    /**
     * Call duration above which a call's summary is reported, at WARN. Configuration key
     * `storm.sqlLog.performance.threshold.duration`, as a duration such as `500ms`.
     *
     * @since 1.13
     */
    public var sqlLogPerformanceDurationThreshold: Duration? = null

    /**
     * Database time above which a single statement execution is reported under the `st.orm.sql.slow` logger,
     * with the statement, its call site and what there is to analyze it by. Independent of [sqlLogPerformance]: it needs no
     * scope and sees every execution, on whatever thread or coroutine it runs. Applied JVM-wide at installation.
     * Configuration key `storm.sqlLog.slow.threshold`, as a duration such as `200ms`; when unset, the
     * `storm.sql_log.slow.threshold` system property's setting stays in effect.
     *
     * @since 1.14
     */
    public var sqlLogSlowThreshold: Duration? = null

    /**
     * Slow statement lines reported per shape per minute before the rest are suppressed and counted, so a
     * degraded database names every shape that suffers without flooding the log with any of them; zero for no
     * limit. Configuration key `storm.sqlLog.slow.limit`; when unset, the
     * `storm.sql_log.slow.limit` system property's setting, or its default of 5, stays in effect.
     *
     * @since 1.14
     */
    public var sqlLogSlowLimit: Int? = null

    /**
     * Whether each execution is attributed to the application frame that caused it, shown per row as
     * `@ File.ext:line`. Costs a stack walk per execution while a scope records; suited to development.
     * Configuration key `storm.sqlLog.performance.callSites`; defaults to false.
     *
     * @since 1.13
     */
    public var sqlLogPerformanceCallSites: Boolean? = null

    /**
     * Packages whose frames are skipped when attributing an execution to a call site, so rows name the code
     * that asked for the work rather than the application's own database plumbing. Configuration key
     * `storm.sqlLog.callSiteSkip`, a list or comma-separated string. Applied JVM-wide at installation, like
     * the `storm.sql_log.call_site_skip` system property it complements; when unset, the system property's
     * setting stays in effect.
     *
     * @since 1.13
     */
    public var sqlLogCallSiteSkip: List<String>? = null

    /**
     * Width a summary row aims for, such as 120 for narrow viewers or 240 for wide ones; the statement text
     * elides to what the row's other columns leave. A display property of the deployment, applied JVM-wide at
     * installation. Configuration key `storm.sqlLog.performance.lineWidth`; when unset, the `storm.sql_log.performance.line_width`
     * system property's setting stays in effect.
     *
     * @since 1.13
     */
    public var sqlLogPerformanceLineWidth: Int? = null

    /**
     * Whether to expose the [st.orm.template.ORMTemplate] and the registered repositories through Ktor's
     * dependency injection (`ktor-server-di`). Each repository is registered under its own interface type, so
     * modules and routes can inject them directly:
     *
     * ```kotlin
     * val visits: VisitRepository by dependencies
     * ```
     *
     * Enabled by default; set to `false` to leave the dependency container untouched.
     *
     * @since 1.13
     */
    public var registerDependencies: Boolean = true

    /**
     * Schema validation mode: `"none"`, `"warn"`, or `"fail"`. Any other value aborts installation.
     *
     * When not set, the mode is read from the application configuration under
     * `storm.validation.schemaMode` (or `storm.validation.schema_mode`), matching the Spring Boot
     * starter's property. Defaults to `"fail"`: every entity and projection is validated against the
     * live database schema during installation, and mismatches abort startup. Run migrations in the
     * [migration] hook so the schema is up to date before validation, or set this to `"none"` to opt out.
     */
    public var schemaValidation: String? = null

    internal var migration: ((DataSource) -> Unit)? = null

    /**
     * Registers a migration hook that runs after the [DataSource] is available but before the
     * ORM template is created and the schema is validated.
     *
     * This is the place to run schema migrations (e.g., Flyway or Liquibase) when the plugin creates
     * the DataSource from configuration, guaranteeing that schema validation sees the migrated schema:
     *
     * ```kotlin
     * install(Storm) {
     *     migration { dataSource ->
     *         Flyway.configure().dataSource(dataSource).load().migrate()
     *     }
     * }
     * ```
     *
     * @since 1.12
     */
    public fun migration(block: (DataSource) -> Unit) {
        migration = block
    }

    /**
     * Entity callbacks for lifecycle hooks on insert, update, upsert, and remove operations. Applied to every
     * database; callbacks declared in a database block apply to that database in addition.
     */
    public val entityCallbacks: MutableList<EntityCallback<*>> = mutableListOf()

    /**
     * Adds an entity callback.
     */
    public fun entityCallback(callback: EntityCallback<*>) {
        entityCallbacks.add(callback)
    }

    /**
     * Whether to automatically register all repository interfaces from the compile-time type index when the
     * plugin is installed. Enabled by default.
     *
     * Auto-registration creates the repository proxies eagerly, so an invalid repository definition fails at
     * startup rather than at first request. Use [repositories] to narrow registration to specific packages, or
     * set this to `false` to skip auto-registration entirely, for the primary and named databases alike;
     * repositories are then created lazily on first [repository] access, or explicitly via
     * [stormRepositories]. The packages declared in a database block keep partitioning either way.
     *
     * @since 1.12
     */
    public var autoRegisterRepositories: Boolean = true

    internal val repositoryPackages: MutableList<String> = mutableListOf()

    /**
     * Narrows repository auto-registration to the given packages (including sub-packages).
     *
     * Only repository interfaces from the compile-time type index whose package matches one of the given
     * [packages] are registered at startup. Repositories outside these packages remain available through lazy
     * creation on first [repository] access.
     *
     * @param packages one or more package names to register repositories from.
     * @since 1.12
     */
    public fun repositories(vararg packages: String) {
        repositoryPackages.addAll(packages)
    }

    internal val databases = LinkedHashMap<String, StormDatabaseConfig>()

    /**
     * Configures an additional, named database.
     *
     * The block mirrors the primary database's options: provide a [StormDatabaseConfig.dataSource] or let the
     * plugin create one from the HOCON configuration under `storm.databases.<name>.datasource`. The database's
     * template and repositories are exposed under the given name via `orm("name")`, `repository<T>("name")`, and
     * named dependency injection.
     *
     * Declare the packages that belong to this database with [StormDatabaseConfig.repositories]; they partition
     * repository registration and schema validation between the databases:
     *
     * ```kotlin
     * install(Storm) {
     *     // primary database: unchanged, zero-config from storm.datasource
     *
     *     database("analytics") {
     *         repositories("com.myapp.analytics")
     *     }
     * }
     * ```
     *
     * @param name the database name; must be unique and not blank.
     * @param block the configuration for the named database.
     * @since 1.13
     */
    public fun database(name: String, block: StormDatabaseConfig.() -> Unit) {
        require(name.isNotBlank()) { "Database name must not be blank." }
        require(name !in databases) { "Database '$name' is already configured." }
        databases[name] = StormDatabaseConfig(name).apply(block)
    }
}
