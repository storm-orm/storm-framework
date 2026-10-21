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

/**
 * Configuration for an additional, named database of the [Storm] plugin.
 *
 * Declared inside `install(Storm) { database("name") { ... } }`; the options mirror the primary database's
 * configuration on [StormPluginConfig]. The database's template and repositories are exposed under the given name:
 * `orm("name")`, `repository<T>("name")`, and named dependency injection (`@Named("name")`).
 *
 * A named database inherits the plugin-level settings that express policy: the Storm configuration keys
 * (per key, `storm.databases.<name>.*` over `storm.*`), [exceptionMapper], [queryObserver], [sqlCommenter],
 * and [schemaValidation], each overridable in this block; the plugin-level entity callbacks, which apply in
 * addition to the callbacks declared here; and the [StormPluginConfig.autoRegisterRepositories] switch.
 * What identifies a database never inherits: its [dataSource], its [migration] hook, and its
 * [connectionProvider] and [transactionTemplateProvider], which define the database's transaction scope and
 * default to fresh per-database instances.
 *
 * The packages declared with [repositories] partition the application: repository interfaces and entity types under
 * these packages belong to this database. They are registered against this database's template, excluded from the
 * primary database's registration and schema validation, and validated against this database's schema instead.
 *
 * @since 1.13
 */
public class StormDatabaseConfig internal constructor(internal val name: String) {

    /**
     * The [DataSource] to use for this database. If not provided, one is created from the HOCON configuration
     * under `storm.databases.<name>.datasource`.
     */
    public var dataSource: DataSource? = null

    /**
     * Optional [StormConfig] override for this database, replacing the inherited configuration entirely. If not
     * provided, each configuration key is read from the HOCON configuration under `storm.databases.<name>`,
     * inheriting keys it does not set from the primary database's effective configuration (the plugin-level
     * [StormPluginConfig.config], or the HOCON configuration under `storm`).
     */
    public var config: StormConfig? = null

    /**
     * Optional [st.orm.core.spi.ConnectionProvider] override. When not set, this database uses its own
     * coroutine-aware provider instance; a plugin-level provider is never inherited, because a provider
     * instance scopes connection binding to one database.
     */
    public var connectionProvider: st.orm.core.spi.ConnectionProvider? = null

    /**
     * Optional [st.orm.core.spi.TransactionTemplateProvider] override. When not set, this database uses its own
     * JDBC transaction provider instance; a plugin-level provider is never inherited. Each database has its own
     * transaction provider, so a `transaction { }` block binds to one database; blocks cannot atomically span
     * databases.
     */
    public var transactionTemplateProvider: st.orm.core.spi.TransactionTemplateProvider? = null

    /**
     * Optional [st.orm.spi.ExceptionMapper] for this database's template; inherits the plugin-level
     * mapper when unset.
     */
    public var exceptionMapper: st.orm.spi.ExceptionMapper? = null

    /**
     * Optional [st.orm.spi.QueryObserver] for this database's template; inherits the plugin-level
     * observer when unset. Without either, query observations bind to the `ObservationRegistry` from the
     * dependency container once the application has started.
     */
    public var queryObserver: st.orm.spi.QueryObserver? = null

    /**
     * Optional [st.orm.spi.SqlCommenter] for this database; inherits the plugin-level commenter when
     * unset.
     *
     * @since 1.13
     */
    public var sqlCommenter: st.orm.spi.SqlCommenter? = null

    /**
     * Optional composition applied to this database's template builder after the plugin has wired the
     * integration, such as a table name resolver; inherits the plugin-level composition when unset.
     *
     * @since 1.14
     */
    public var customize: (st.orm.template.ORMTemplate.Builder.() -> Unit)? = null

    /**
     * Schema validation mode for this database: `"none"`, `"load"`, `"warn"`, or `"fail"`. Any other value aborts
     * installation. `"load"` loads the schema without validating it, which Storm uses for advice and optimization.
     *
     * When not set, the mode is read from the HOCON configuration under
     * `storm.databases.<name>.validation.schemaMode` (or `schema_mode`), inheriting the primary database's mode
     * ([StormPluginConfig.schemaValidation], or `storm.validation.schemaMode`) when that is not set either,
     * defaulting to `"fail"`. Validation covers the entity and projection types under the packages declared
     * with [repositories].
     */
    public var schemaValidation: String? = null

    internal var migration: ((DataSource) -> Unit)? = null

    /**
     * Registers a migration hook for this database, running after its [DataSource] is available but before the
     * template is created and the schema is validated.
     */
    public fun migration(block: (DataSource) -> Unit) {
        migration = block
    }

    internal val entityCallbacks = mutableListOf<EntityCallback<*>>()

    /**
     * Registers an entity callback on this database's template, in addition to the plugin-level callbacks,
     * which apply to every database.
     */
    public fun entityCallback(callback: EntityCallback<*>) {
        entityCallbacks += callback
    }

    internal val repositoryPackages = mutableListOf<String>()

    /**
     * Declares the packages that belong to this database.
     *
     * Repository interfaces from the compile-time type index under these packages (including sub-packages) are
     * registered against this database's template, and entity and projection types under these packages are
     * validated against this database's schema. The primary database excludes these packages from its own
     * registration and validation.
     *
     * A database without declared packages exposes its template (`orm("name")`) but registers no repositories
     * automatically.
     */
    public fun repositories(vararg packages: String) {
        repositoryPackages += packages
    }
}
