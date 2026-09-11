import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Ktor Integration

Storm integrates with [Ktor](https://ktor.io/) through a dedicated plugin that handles DataSource lifecycle, configuration, and ORM access. Because Ktor is coroutine-first and Storm's Kotlin API already provides a suspend-friendly `transaction { }` function, the integration is lightweight: no custom transaction infrastructure or SPI providers are needed.

The integration follows Ktor's plugin-based architecture. You `install(Storm)` like any other Ktor plugin, configure it through HOCON or programmatically, and access the ORM through extension properties on `Application`, `ApplicationCall`, and `RoutingContext`. Storm handles connection pooling, transaction propagation, and DataSource lifecycle automatically.

## Installation

Apply the Storm Gradle plugin, then add the Ktor module alongside it. The plugin imports the BOM and wires the core dependencies, the metamodel processor (applying KSP itself on Kotlin 2.3+), and the Kotlin compiler plugin, so the Ktor setup only adds the module itself, a connection pool, and a dialect:

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("st.orm") version "@@STORM_VERSION@@"
}

dependencies {
    implementation("st.orm:storm-ktor")

    // Connection pooling (recommended)
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Database dialect (pick yours)
    runtimeOnly("st.orm:storm-postgresql")

    // Testing
    testImplementation("st.orm:storm-ktor-test")
    testImplementation("com.h2database:h2")
}
```

See [Installation](installation.md#gradle-plugin-recommended) for the plugin's `storm { }` options and the manual setup if you prefer explicit configuration.

Storm integrates with Ktor's built-in dependency injection out of the box (see [Dependency Injection](#dependency-injection)).

---

## Plugin Setup

Install the `Storm` plugin in your Ktor application module. The plugin creates an `ORMTemplate` and manages the DataSource lifecycle. There are two ways to provide a DataSource: let the plugin create one from your HOCON configuration, or pass one explicitly.

### Zero-Configuration Setup

When you call `install(Storm)` without providing a DataSource, the plugin reads the `storm.datasource` section from `application.conf` and creates a HikariCP connection pool automatically. This is the recommended approach for most applications, as it keeps database configuration external to your code and environment-specific.

```kotlin
fun Application.module() {
    install(Storm)

    routing {
        get("/users/{id}") {
            val user = entity<User, _>().findById(call.parameters.getOrFail("id").toInt())
            call.respond(user ?: HttpStatusCode.NotFound)
        }
    }
}
```

```hocon
# application.conf
storm {
    datasource {
        jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        driverClassName = "org.postgresql.Driver"
        username = "dbuser"
        password = "dbpass"
        maximumPoolSize = 10
    }
}
```

The plugin reads Storm ORM properties from the same `storm` section (see [Configuration](#configuration) below). Configuration files are loaded by Ktor's `EngineMain` entry point (`fun main(args: Array<String>) = EngineMain.main(args)`); a hand-built `embeddedServer` starts from an empty configuration unless you pass one explicitly.

### Explicit DataSource

If you need full control over connection pool configuration, or want to reuse a DataSource from another library, pass it directly. This is useful when integrating with existing infrastructure or when HikariCP configuration goes beyond what HOCON properties cover.

```kotlin
fun Application.module() {
    val hikariConfig = HikariConfig().apply {
        jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        username = "dbuser"
        password = "dbpass"
        maximumPoolSize = 10
    }

    install(Storm) {
        dataSource = HikariDataSource(hikariConfig)
    }
}
```

### Plugin Configuration Options

The `Storm` plugin accepts several configuration options through its DSL:

```kotlin
install(Storm) {
    // DataSource: auto-created from HOCON if not provided
    dataSource = myDataSource

    // Storm config: auto-read from HOCON if not provided
    config = StormConfig.of(mapOf(UPDATE_DEFAULT_MODE to "FIELD"))

    // Schema migrations: runs after the DataSource is available, before validation
    migration { dataSource ->
        Flyway.configure().dataSource(dataSource).load().migrate()
    }

    // Schema validation: "none", "warn", or "fail".
    // When omitted, read from storm.validation.schemaMode in the application config; defaults to "fail".
    schemaValidation = "warn"

    // Entity callbacks for lifecycle hooks
    entityCallback(AuditCallback())

    // Application-specific template composition, applied after the plugin has wired the integration
    customize = {
        decorator { it.withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT)) }
    }

    // Repository auto-registration (enabled by default; see Repository Registration below)
    repositories("com.myapp.repository")   // optional: narrow to specific packages
    // autoRegisterRepositories = false    // optional: disable auto-registration
}
```

| Option | Default | Description |
|--------|---------|-------------|
| `dataSource` | Created from HOCON | The JDBC DataSource to use. When omitted, a HikariCP pool is created from `storm.datasource.*` in `application.conf`. |
| `config` | Read from HOCON | A `StormConfig` with ORM properties. When omitted, properties are read from `storm.*` in `application.conf`. |
| `migration(...)` | None | A hook that runs after the DataSource is available and before schema validation. The place for Flyway or Liquibase migrations, guaranteeing validation sees the migrated schema. |
| `schemaValidation` | From config, else `"fail"` | Validates entity definitions against the database schema during installation. `"fail"` (the default) blocks startup on mismatches; `"warn"` logs them; `"none"` opts out. When not set, the mode is read from `storm.validation.schemaMode` in the application configuration. |
| `entityCallback(...)` | None | Registers entity lifecycle callbacks for insert, update, and delete operations. |
| `customize` | None | Composition applied to each database's template builder after the plugin has wired the integration, such as a template decorator. A database block inherits it unless it sets its own. |
| `autoRegisterRepositories` | `true` | Registers all repository interfaces from the compile-time type index during installation, so `repository<T>()` works without further setup. |
| `repositories(...)` | All indexed | Narrows repository auto-registration to the given packages (including sub-packages). |

When the application stops, the plugin automatically closes the connection pools it created from configuration. If you provide your own DataSource, the plugin never closes it; manage its lifecycle yourself.

---

## Accessing the ORM

The `ORMTemplate` is stored in the application's attributes and accessible through extension properties. Storm provides extensions on three types, so you can pick the most convenient access pattern depending on where you are in the code:

| Extension | Available in | Example |
|-----------|-------------|---------|
| `Application.orm` | Application setup, plugins | `application.orm.entity<User>()` |
| `ApplicationCall.orm` | Route handlers | `call.orm.entity<User>()` |
| `RoutingContext.orm` | Route handlers (implicit `this`) | `orm.entity<User>()` |

```kotlin
// In route handlers via ApplicationCall (explicit)
get("/users") {
    val users = call.orm.entity<User>()
    call.respond(users.findAll())
}

// In route handlers via RoutingContext (implicit, most concise)
routing {
    get("/users") {
        val users = orm.entity<User>()
        call.respond(users.findAll())
    }
}

// From the Application object (during setup)
val orm = application.orm
```

### Why a Single Instance Works

Unlike JPA's `EntityManager` (which is session-scoped and must be opened/closed per request), Storm's `ORMTemplate` is stateless. It does not hold connections, track entity state, or maintain a persistence context. A single application-wide instance is correct.

Connection scoping happens automatically:
- **Outside transactions**: each ORM operation acquires a connection from the pool, executes the query, and returns the connection immediately.
- **Inside `transaction { }` blocks**: a single connection is held for the duration of the transaction and propagated through the coroutine context, ensuring all operations within the block share the same connection and transaction.

This means there is no connection-per-request interceptor and no risk of connection leaks from forgotten close calls.

---

## Transaction Management

Storm's Kotlin transaction API works directly in Ktor route handlers because both are coroutine-based. There is no need for annotations, proxies, or additional transaction infrastructure. You call `transaction { }` and Storm handles the connection, commit, and rollback lifecycle.

### Read Operations

Simple reads do not require an explicit transaction. The ORM acquires a connection for each operation and returns it to the pool immediately. This is efficient for single-query endpoints:

```kotlin
get("/users/{id}") {
    val user = entity<User, _>().findById(call.parameters.getOrFail("id").toInt())
    call.respond(user ?: HttpStatusCode.NotFound)
}
```

### Write Operations

Use `transaction { }` to group writes into a single atomic operation. The transaction commits when the block completes successfully and rolls back if an exception is thrown. No manual commit or rollback calls are needed.

```kotlin
post("/users") {
    val request = call.receive<CreateUserRequest>()
    val user = transaction {
        call.orm insert User(email = request.email, name = request.name)
    }
    call.respond(HttpStatusCode.Created, user)
}
```

Because `transaction { }` is a suspend function, it integrates naturally with Ktor's coroutine-based request handling. You can call it from any route handler without blocking the event loop.

### Route-Scoped Transactions

To remove the per-handler boilerplate, wrap a group of routes in `transactional { }`: every call to a route inside the block runs in its own transaction, opened before the handler, committed when it completes, and rolled back when it throws. The handlers contain no transaction code at all:

```kotlin
routing {
    // Writes: each call runs in its own read-write transaction.
    transactional {
        post("/owners") {
            val request = call.receive<CreateOwnerRequest>()
            val owner = repository<OwnerRepository>().insertAndFetch(request.toEntity())
            call.respond(HttpStatusCode.Created, owner)
        }
        put("/owners/{id}") { ... }
    }

    // Reads that span several queries: one transaction, hinted read-only.
    transactional(readOnly = true) {
        get("/reports/summary") { ... }
    }

    // Simple reads outside the block keep running without a transaction.
    get("/owners/{id}") { ... }
}
```

The parameters mirror `transaction { }` (`propagation`, `isolation`, `timeoutSeconds`, `readOnly`, `dispatcher`) and apply uniformly to every route in the block, whatever the HTTP method. Group routes by their transactional needs rather than wrapping everything: simple single-query reads are cheapest with no transaction at all.

The transaction binds to the first ORM template the handler touches, so nothing needs to be configured for [multiple databases](#multiple-databases): a route that works against a named database transacts against that database. Nested `transactional` blocks compose exactly like nested `transaction { }` calls: with the default propagation the inner block joins the outer transaction.

One semantic difference from calling `transaction { }` manually: the handler, including `call.respond`, runs inside the transaction, and the commit happens after the handler completes. A commit failure can therefore not change a response that has already been sent. When that distinction matters, for example when the client must only see 201 Created after a guaranteed commit, call `transaction { }` inside the handler and respond after it returns.

### Nested Transactions and Propagation

Storm supports the full set of Spring-style propagation modes. Nested transactions are useful when composing services that each define their own transactional requirements. For example, an audit log that should persist even if the main operation fails needs its own independent transaction.

```kotlin
post("/orders") {
    transaction {
        // Main order processing: participates in the outer transaction
        call.orm insert order

        transaction(propagation = REQUIRES_NEW) {
            // Audit log: commits independently, even if the outer transaction rolls back
            call.orm insert log
        }
    }
}
```

All seven Spring-style propagation modes are supported: `REQUIRED` (default), `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `MANDATORY`, `NOT_SUPPORTED`, and `NEVER`. See [Transactions](transactions.md) for the full propagation matrix and detailed examples.

### Read-Only Transactions

For reads that span several queries, such as a report assembled from multiple lookups, run them in one transaction and mark it read-only:

```kotlin
get("/reports/summary") {
    val summary = transaction(readOnly = true) {
        val orders = call.orm.entity<Order>().findAll()
        val total = orders.sumOf { it.amount }
        ReportSummary(orderCount = orders.size, totalAmount = total)
    }
    call.respond(summary)
}
```

Read-only lets the database optimize for reads, such as taking lighter locks, and Storm refuses an `INSERT`, `UPDATE` or `DELETE` inside the transaction with `ReadOnlyTransactionException` before it reaches the database. See [Transactions](transactions.md#read-only-transactions) for the details.

---

## Error Handling

Storm's exceptions map naturally onto HTTP status codes through Ktor's [StatusPages](https://ktor.io/docs/server-status-pages.html) plugin. Ktor has no platform exception hierarchy to translate into, so this is plain application configuration; the recipes below cover the common cases:

```kotlin
install(StatusPages) {
    // getById on a missing row.
    exception<NoResultException> { call, _ ->
        call.respond(HttpStatusCode.NotFound)
    }
    // A @Version conflict: the row was modified by another transaction.
    exception<OptimisticLockException> { call, _ ->
        call.respond(HttpStatusCode.Conflict)
    }
    // Constraint violations (unique key, foreign key, not-null) and everything else.
    exception<PersistenceException> { call, cause ->
        val constraintViolation = generateSequence<Throwable>(cause) { it.cause }
            .filterIsInstance<SQLException>()
            .any { it.sqlState?.startsWith("23") == true }
        if (constraintViolation) {
            call.respond(HttpStatusCode.Conflict)
        } else {
            application.log.error("Unhandled persistence failure.", cause)
            call.respond(HttpStatusCode.InternalServerError)
        }
    }
}
```

A few notes on the recipes:

- **Missing rows.** `getById` throws `NoResultException`; `findById` returns null instead. Prefer `findById` when a missing row is an expected outcome you handle in the route, and `getById` with the recipe when it should uniformly become a 404.
- **Constraint violations.** Storm wraps the JDBC failure in `PersistenceException` with the driver's `SQLException` as the cause. The recipe walks the cause chain and checks for SQL state class `23` (integrity-constraint violation), which is portable across databases; checking `instanceof SQLIntegrityConstraintViolationException` is not, because some drivers (PostgreSQL, for example) throw a plain `SQLException` subclass with state `23505` instead.
- **Optimistic locking.** `OptimisticLockException` carries the conflicting entity via `entity`; include it in the response body if the client can resolve the conflict. Some APIs prefer 412 Precondition Failed over 409 when the client sent a version precondition.
- **Never leak SQL.** The fallback logs the failure server-side and returns a bare 500; Storm attaches the SQL statement to the exception for diagnostics, which belongs in logs, not in responses.
- **Ordering with transactions.** StatusPages handles the exception after the route pipeline unwinds, so a `transactional { }` route has already rolled back by the time the error response is rendered.

For translating Storm exceptions into a custom exception hierarchy at the ORM level, before they reach Ktor, set an `exceptionMapper` in the plugin configuration; the StatusPages recipes then match on your own types. See [Error Handling](error-handling.md) for the full exception reference.

---

## Repository Registration

Custom repository interfaces provide a clean way to encapsulate query logic. In Ktor, repositories are created once and cached for the lifetime of the application.

### Automatic Registration (Default)

No registration step is needed. When the plugin is installed, Storm reads the compile-time type index generated by the metamodel processor (KSP or annotation processor, already part of the standard Storm setup) and registers every repository interface it finds. No runtime classpath scanning is involved. `repository<T>()` then works anywhere:

```kotlin
fun Application.module() {
    install(Storm)

    routing {
        get("/users/{email}") {
            val users = repository<UserRepository>()
            val user = users.findByEmail(call.parameters.getOrFail("email"))
            call.respond(user ?: HttpStatusCode.NotFound)
        }
    }
}
```

Like `orm`, the `repository<T>()` extension is available on `Application`, `ApplicationCall`, and `RoutingContext`, so route handlers can call it bare (as above) and setup code can call it on the application.

Because the proxies are created eagerly during installation, an invalid repository definition fails at startup rather than at first request.

Auto-registration can be narrowed to specific packages, or disabled entirely, through the plugin configuration:

```kotlin
install(Storm) {
    repositories("com.myapp.repository")   // only this package (and sub-packages)
    // autoRegisterRepositories = false    // or opt out completely
}
```

Repository types that are not registered, for example because they fall outside the configured packages, are still resolved by `repository<T>()`: the proxy is created lazily on first access and cached from then on.

### Explicit Registration

For full manual control, or to combine bulk and individual registration, use `stormRepositories`. The block operates on the same registry the plugin created, so explicit registrations and auto-registrations share one cache:

```kotlin
fun Application.module() {
    install(Storm) {
        autoRegisterRepositories = false
    }

    stormRepositories {
        // Register individually
        register(UserRepository::class)

        // Or register all repositories in a package (and its sub-packages)
        register("com.myapp.repository")

        // Or register all indexed repositories
        register()
    }
}
```

### Direct Entity and Projection Access

For simple operations that do not require custom query methods, skip custom repositories entirely: the `entity<T>()` and `projection<T>()` extensions are available on `Application`, `ApplicationCall`, and `RoutingContext`, just like `repository<T>()` and `orm`:

```kotlin
get("/users") {
    call.respond(entity<User>().findAll())
}

get("/users/{id}") {
    // entity<T, _> binds the primary key type, enabling findById
    val user = entity<User, _>().findById(call.parameters.getOrFail("id").toInt())
    call.respond(user ?: HttpStatusCode.NotFound)
}

get("/owners") {
    call.respond(projection<OwnerSummary>().findAll())
}
```

Repositories are stateless, so these are cheap to create; custom repository interfaces remain the better home for query logic you want to name, share, and test.

### Dependency Injection

The Storm plugin integrates with Ktor's built-in dependency injection (`ktor-server-di`). When the plugin is installed, it registers the `ORMTemplate` and every registered repository in the application's dependency container, each repository under its own interface type. Modules and routes can then inject them directly:

```kotlin
fun Application.module() {
    install(Storm)

    val orm: ORMTemplate by dependencies
    val visits: VisitRepository by dependencies
}
```

Because Ktor injects module parameters from the same container, repositories can also be declared as plain parameters on modules that are installed after Storm:

```kotlin
fun Application.visitRoutes(visits: VisitRepository) {
    routing {
        get("/visits") {
            call.respond(visits.findAll())
        }
    }
}
```

Install the Storm plugin before any module that injects Storm types, so the providers are registered by the time they are resolved. With auto-registration (the default), the container covers the application's entire repository layer; repositories created lazily after installation (`autoRegisterRepositories = false`) are not added automatically. Set `registerDependencies = false` in the plugin configuration to leave the dependency container untouched.

### Multiple Databases

Working with one database requires no extra configuration. Additional databases are declared as named `database` blocks inside the plugin, each with the same options as the primary:

```kotlin
install(Storm) {
    // primary database: zero-config from storm.datasource

    database("analytics") {
        repositories("com.myapp.analytics")
    }
}
```

Each named database gets its own `ORMTemplate`, repositories, schema validation, migration hook, and lifecycle. Provide a `dataSource` in the block, or let the plugin create one from the HOCON configuration under `storm.databases.<name>.datasource`, with the same properties as the primary's `storm.datasource` section.

A named database inherits the plugin-level settings that express policy: the Storm configuration keys (per key, `storm.databases.<name>.*` over `storm.*`), the exception mapper, the query observer, the SQL commenter, and the schema validation mode, each overridable in its block; the plugin-level entity callbacks, which apply in addition to the callbacks declared in the block; and the `autoRegisterRepositories` switch. What identifies a database never inherits: its data source, its migration hook, and its connection and transaction template providers, which define the database's transaction scope and default to fresh per-database instances.

The packages declared with `repositories(...)` partition the application between the databases: repository interfaces and entity types under those packages belong to that database. They are registered against its template, validated against its schema, and excluded from the primary database's registration and validation. Requesting a claimed repository from the primary fails with an error naming the owning database, and requesting a type no database block claims from a named database fails the same way: an undeclared type belongs to the primary, so a named lookup for it is a wiring error, not a request to bind the type to that database.

Access the named database through the name-taking variants of the standard accessors, or through dependency injection:

```kotlin
val analytics = orm("analytics")
val reports = repository<ReportRepository>("analytics")

// Dependency injection: templates resolve by name, repositories simply by their type.
val analyticsTemplate = dependencies.resolve<ORMTemplate>("analytics")
val reportsByType: ReportRepository by dependencies
```

Repositories keep unnamed dependency keys because package partitioning makes each repository type belong to exactly one database; only the templates need names. Transactions bind to one database per physical transaction: a `transaction { }` block binds to the database of the first template that runs in it, and a single block cannot atomically span databases.

---

## Configuration

The Storm plugin reads its configuration from Ktor's HOCON configuration file (`application.conf` or `application.yaml`). All properties under `storm` are mapped to Storm's configuration system. Both camelCase (HOCON convention) and snake_case (Storm convention) are accepted.

### DataSource Properties

The `storm.datasource` section maps directly to HikariCP configuration. These properties are only used when the plugin creates the DataSource automatically (i.e., when you call `install(Storm)` without providing a `dataSource`).

```hocon
storm {
    datasource {
        jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        driverClassName = "org.postgresql.Driver"
        username = "dbuser"
        password = ${?DB_PASSWORD}       # Environment variable substitution
        maximumPoolSize = 10
        connectionTimeout = 30000        # 30 seconds
        idleTimeout = 600000             # 10 minutes
        maxLifetime = 1800000            # 30 minutes
        minimumIdle = 2
    }
}
```

| Property | Default | Description |
|----------|---------|-------------|
| `jdbcUrl` | (required) | JDBC connection URL |
| `driverClassName` | Auto-detected | JDBC driver class. Most modern drivers are detected from the URL. |
| `username` | None | Database username |
| `password` | None | Database password |
| `maximumPoolSize` | 10 | Upper bound on connections. Start with CPU cores x 2 and adjust based on load testing. |
| `minimumIdle` | Same as max | Minimum idle connections to maintain |
| `connectionTimeout` | 30000 | Maximum time (ms) to wait for a connection from the pool |
| `idleTimeout` | 600000 | Maximum time (ms) a connection can sit idle before being retired |
| `maxLifetime` | 1800000 | Maximum lifetime (ms) of a connection. Set shorter than your database's timeout. |

### Storm Properties

Storm ORM properties control runtime behavior for features like dirty checking, entity caching, and validation. All properties have sensible defaults, so this section is entirely optional.

```hocon
storm {
    update {
        defaultMode = "ENTITY"           # ENTITY, FIELD, or OFF
        dirtyCheck = "INSTANCE"          # INSTANCE or FIELD
        maxShapes = 5
    }
    entityCache {
        retention = "default"            # "default" or "light"
    }
    templateCache {
        size = 256
    }
    ansiEscaping = false
    validation {
        recordMode = "fail"              # "none", "warn", or "fail"
        schemaMode = "warn"              # "none", "warn", or "fail"
        strict = false
    }
}
```

A named database reads the same keys under `storm.databases.<name>`, inheriting every key it does not set from the `storm` section. The per-call [SQL log](sql-logging.md#the-performance-log) is configured under `storm.sqlLog`. See the [Configuration](configuration.md) guide for a description of each property and the full precedence rules.

### Environment-Specific Configuration

Define sensible defaults in `application.conf` and keep each environment's overrides in a file of its own. `EngineMain` accepts multiple `-config` parameters and merges them in order, so the environment is selected at launch, without code changes:

```hocon
# application.conf (default / development)
storm {
    datasource {
        jdbcUrl = "jdbc:h2:mem:dev;DB_CLOSE_DELAY=-1"
        username = "sa"
        password = ""
    }
}
```

```hocon
# application-production.conf
storm {
    datasource {
        jdbcUrl = "jdbc:postgresql://prod-host:5432/mydb"
        username = ${DB_USER}
        password = ${DB_PASSWORD}
        maximumPoolSize = 20
    }
    validation {
        schemaMode = "fail"
    }
}
```

Start the application with both files; keys in later files override those in earlier ones:

```shell
java -jar app.jar -config=application.conf -config=application-production.conf
```

Each `-config` value resolves against the classpath first, then the file system. Passing any `-config` parameter replaces the default `application.conf` lookup entirely, so list the defaults first and the environment file after it. Secrets stay out of both files: `${DB_USER}` and `${DB_PASSWORD}` are substituted from environment variables when the configuration loads.

---

## Content Negotiation

Ktor uses the `ContentNegotiation` plugin for JSON serialization. If your entities contain `Ref<T>` fields (lazy-loaded foreign key references), you need to register Storm's serialization module so that `Ref` values are serialized correctly. Without it, unloaded refs would fail to serialize.

### With Jackson

```kotlin
install(ContentNegotiation) {
    jackson {
        registerModule(StormModule())
    }
}
```

Add `storm-jackson2` (for Jackson 2.17+) or `storm-jackson3` (for Jackson 3.0+) to your dependencies.

### With Kotlinx Serialization

```kotlin
install(ContentNegotiation) {
    json(Json {
        serializersModule = StormSerializersModule()
    })
}
```

Add `storm-kotlinx-serialization` to your dependencies. When using kotlinx.serialization, every `Ref` field in a `@Serializable` entity must be annotated with `@Contextual`. See [Serialization](serialization.md) for the full guide on `Ref<T>` serialization behavior, the cascade rule, and Java time type handling.

### Entities Without Refs

If your entities do not use `Ref<T>` fields (i.e., all foreign keys are loaded eagerly as direct entity references), no Storm serialization module is needed. Standard Jackson or kotlinx.serialization handles them out of the box.

---

## Template Decorator

The `TemplateDecorator` lets you customize how Storm resolves table names, column names, and foreign key column names globally. This is useful when your database uses a naming convention that differs from Storm's default camelCase-to-snake_case conversion, or when you need to add a schema prefix.

Apply a decorator through the plugin's `customize` slot, which runs after the plugin has wired the integration, so the plugin's own template, and every repository registered on it, carries the decorator:

```kotlin
install(Storm) {
    dataSource = myDataSource
    customize = {
        decorator {
            it.withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
                .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
        }
    }
}
```

A database block inherits the plugin-level `customize` unless it sets its own, so one naming convention serves every database by default. See the [Spring Integration](spring-integration.md#template-decorator) section on Template Decorator for the full list of available resolvers. The resolvers are framework-agnostic and work identically in Ktor.

---

## Schema Validation

Storm can validate entity definitions against the live database schema at startup. This catches common mapping errors (missing columns, type mismatches, nullability differences) before your application serves its first request. Validation runs in `fail` mode by default; set it to `warn` or `none` in the plugin or in `application.conf` to relax it:

```kotlin
install(Storm) {
    schemaValidation = "warn"   // "none", "warn", or "fail"
}
```

Or in HOCON:

```hocon
storm {
    validation {
        schemaMode = "warn"
    }
}
```

| Mode | Behavior |
|------|----------|
| `fail` | Block startup if any entity definitions do not match the database schema (default). |
| `warn` | Log mismatches at startup without blocking. Useful while the schema is still evolving. |
| `none` | Skip validation. Suitable when schemas are managed entirely by migrations. |

See [Validation](validation.md) for details on what is validated and how to interpret the output.

---

## Observability

The plugin turns query executions into [Micrometer Observations](https://docs.micrometer.io/micrometer/reference/observation.html) automatically. Register an `ObservationRegistry` in the dependency container, in any module, and every Storm query is observed from then on:

```kotlin
fun Application.module() {
    dependencies {
        provide<ObservationRegistry> { observationRegistry }
    }
    install(Storm)
}
```

No further configuration is needed; without a registry, queries run unobserved at no cost. To report the OpenTelemetry database client semantic conventions, the standard attributes observability backends key their database tooling on, set the semantic conventions in the application configuration; the database product resolves from each database's own data source, and a named database overrides the value under `storm.databases.<name>.observations.semanticConventions`:

```hocon
storm {
    observations {
        semanticConventions = "otel"    # "storm" (default) or "otel"
    }
}
```

An `ObservationConvention<StormQueryObservationContext>` registered in the dependency container takes precedence over the configuration and serves every database:

```kotlin
dependencies {
    provide<ObservationRegistry> { observationRegistry }
    provide<ObservationConvention<StormQueryObservationContext>> {
        OtelDatabaseObservationConvention.fromJdbcUrl(jdbcUrl)
    }
}
```

With tracing in place, the `sqlCommenter` slot appends the current trace context to statements as a sqlcommenter-style comment, correlating database-side diagnostics such as slow query logs back to the trace. Opt-in: a per-execution comment defeats prepared statement caching; the `onlySampled` constructor argument (`TraceContextSqlCommenter(tracer, true)`) limits the comments to sampled traces.

```kotlin
install(Storm) {
    sqlCommenter = TraceContextSqlCommenter(tracer)
}
```
 What each observation produces depends on the handlers attached to the registry: timing metrics, tracing spans, or both. Storm spans nest under the current trace context, so with context propagation in place (for example the OpenTelemetry agent, or micrometer-tracing with a `TracingObservationHandler`) queries appear under the active request span.

Physical transactions report as `storm.transaction` observations with their duration, outcome (`committed` or `rolled_back`), propagation, and read-only flag; joined blocks are not double-counted. An `ObservationConvention<StormTransactionObservationContext>` registered in the dependency container overrides their naming and key values, just as an `ObservationConvention<StormQueryObservationContext>` overrides those of the query observations. Query observations are named `storm.query` and carry the following key values:

| Key | Cardinality | Value |
|-----|-------------|-------|
| `storm.operation` | low | The SQL operation: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, or `UNDEFINED`. |
| `storm.execution` | low | How the statement executed: `QUERY`, `UPDATE`, or `BATCH`. |
| `storm.data_type` | low | Simple name of the entity or projection type the statement operates on, or `none` for raw queries. |
| `storm.origin` | low | What caused the statement: `DIRECT`, or `FETCH` for a statement resolving a reference. |
| `storm.shape` | low | Identity of the statement's shape: statements generated from one template share it whatever their parameters expand to, so grouping by shape treats them as one statement where the text would split them. `none` when unknown. |
| `storm.database` | low | The database name; `primary` for the primary database. |
| `db.statement` | high | The SQL statement. Available to trace handlers as a span attribute; never a metric tag. |

Queries against a named database are tagged `storm.database=<name>`; the primary database is tagged `storm.database=primary`. The tag is always present because meters of one name must share a single set of tag keys; registries such as Prometheus drop series whose tag keys differ. Queries issued during plugin installation, such as schema validation, run before the registry is resolved and are not observed.

For development and per-call diagnosis, `sqlLogPerformance = true` in the plugin configuration (or `storm.sqlLog.performance.enabled = true` in `application.conf`) reports what each call cost the database as one summary (statements, database time against total time, concurrency, and the statement that carried the weight) with thresholds that turn it into a production guardrail. See [SQL Logging](sql-logging.md#the-performance-log).

For production, `sqlLogSlowThreshold = 200.milliseconds` (or `storm.sqlLog.slow.threshold = 200ms`) reports each single execution whose database time exceeds the threshold, under `st.orm.sql.slow` at `WARN`: the statement, its call site, its rows, and how it compares to what its shape typically costs. It needs no request boundary, follows no coroutine context, and applies whether or not the performance log is enabled; left unset while the performance log runs against `sqlLogPerformanceDurationThreshold`, it takes that duration, so a reported call names the statement behind it. See [The Slow Statement Log](sql-logging.md#the-slow-statement-log).

For full control, set an explicit observer in the plugin configuration; it takes precedence over the automatic binding. The `queryObserver` slot accepts any `st.orm.spi.QueryObserver`, including a hand-configured `MicrometerQueryObserver` from the `storm-micrometer` module (custom `ObservationConvention`, extra key values).

```kotlin
install(Storm) {
    queryObserver = MicrometerQueryObserver(observationRegistry, KeyValues.of("app.tenant", "acme"))
}
```

Non-Ktor applications can use `storm-micrometer` directly by configuring the observer on the template builder: `ORMTemplate.builder(dataSource).queryObserver(MicrometerQueryObserver(registry))`.

---

## Testing

Storm provides two complementary approaches for testing Ktor applications, both designed to eliminate database setup boilerplate.

### testStormApplication DSL

The `storm-ktor-test` module provides a `testStormApplication` function that creates an H2 in-memory database, executes SQL scripts, and exposes Storm infrastructure through a `StormTestScope`. This is the most convenient approach for route-level integration tests:

```kotlin
@Test
fun `GET users returns list`() = testStormApplication(
    scripts = listOf("/schema.sql", "/data.sql"),
) { scope ->
    application {
        install(Storm) { dataSource = scope.stormDataSource }
        install(ContentNegotiation) { jackson() }
        routing { userRoutes() }
    }

    client.get("/users").apply {
        assertEquals(HttpStatusCode.OK, status)
    }
}
```

The `StormTestScope` provides three properties:

| Property | Type | Description |
|----------|------|-------------|
| `stormDataSource` | `DataSource` | The H2 in-memory DataSource, pre-loaded with your SQL scripts |
| `stormOrm` | `ORMTemplate` | A pre-configured ORM template backed by the test DataSource |
| `stormSqlCapture` | `SqlCapture` | A capture recording the SQL the application executes while handling calls |

### SqlCapture

Use `SqlCapture` to verify the SQL that Storm generates during a request. This is valuable for catching unintended query changes during refactoring and for ensuring complex operations produce the expected number of statements.

The scope's capture is installed around every call the application handles, so a request's statements are captured wherever its handler runs, with no wrapping at the call site:

```kotlin
@Test
fun `POST users generates single INSERT`() = testStormApplication(
    scripts = listOf("/schema.sql"),
) { scope ->
    application {
        install(Storm) { dataSource = scope.stormDataSource }
        routing { userRoutes() }
    }

    client.post("/users") {
        contentType(ContentType.Application.Json)
        setBody("""{"email":"alice@test.com","name":"Alice"}""")
    }
    assertEquals(1, scope.stormSqlCapture.count(Operation.INSERT))
}
```

Statements accumulate across requests; call `scope.stormSqlCapture.clear()` between requests to assert on one request at a time. Statements the test body executes directly against `scope.stormOrm`, such as seeding data, stay outside the capture, so they never distort a request's counts. To capture direct ORM work as well, wrap it in the suspending `recording` extension from `storm-kotlin-test`: `scope.stormSqlCapture.recording { ... }`.

### Combining with @StormTest

The existing `@StormTest` annotation from `storm-test` works alongside Ktor's `testApplication`. This approach is useful when you want JUnit 5 parameter injection for `DataSource`, `ORMTemplate`, or `SqlCapture` alongside Ktor's test builder:

```kotlin
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class UserRouteTest {

    @Test
    fun `users endpoint returns data`(dataSource: DataSource) = testApplication {
        application {
            install(Storm) { this.dataSource = dataSource }
            routing { userRoutes() }
        }
        client.get("/users").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
```

Both approaches use H2 in-memory databases by default. To run a `@StormTest` class against the database the application deploys on, set `database = TestDatabase.POSTGRESQL` (or the database you use) on the annotation; the injected `DataSource` then points at a Testcontainers-managed container, and the Ktor test needs no other change. `testStormApplication` takes `url`, `username` and `password` for the same purpose; a database provisioned through `TestDatabase.POSTGRESQL.container().createDatabase()` supplies all three. See [Testing](testing.md#testing-against-the-database-you-deploy-on) for the full testing guide.

---

## Complete Example

A minimal but complete Ktor application with Storm, showing plugin setup, repository registration, CRUD routes, and HOCON configuration:

```kotlin
// UserRepository.kt
interface UserRepository : EntityRepository<User, Int>

// Application.kt
fun main(args: Array<String>) = EngineMain.main(args)

fun Application.module() {
    install(Storm)
    install(ContentNegotiation) {
        jackson { registerModule(StormModule()) }
    }

    stormRepositories {
        register(UserRepository::class)
    }

    routing {
        get("/users") {
            val users = call.repository<UserRepository>()
            call.respond(users.findAll())
        }

        get("/users/{id}") {
            val id = call.parameters.getOrFail("id").toInt()
            val user = call.repository<UserRepository>().findById(id)
            call.respond(user ?: HttpStatusCode.NotFound)
        }

        post("/users") {
            // Ad-hoc write through the template; the infix insert needs no repository interface.
            val request = call.receive<CreateUserRequest>()
            val user = transaction {
                call.orm insert User(email = request.email, name = request.name)
            }
            call.respond(HttpStatusCode.Created, user)
        }

        delete("/users/{id}") {
            val id = call.parameters.getOrFail("id").toInt()
            transaction {
                call.repository<UserRepository>().removeById(id)
            }
            call.respond(HttpStatusCode.NoContent)
        }
    }
}
```

```hocon
# application.conf
ktor {
    deployment {
        port = 8080
    }
    application {
        modules = [ com.myapp.ApplicationKt.module ]
    }
}

storm {
    datasource {
        jdbcUrl = "jdbc:postgresql://localhost:5432/mydb"
        driverClassName = "org.postgresql.Driver"
        username = "dbuser"
        password = "dbpass"
        maximumPoolSize = 10
    }
    validation {
        schemaMode = "warn"
    }
}
```

---

## Tips

1. **Use the zero-config setup.** Define your DataSource in `application.conf` and let the plugin handle the rest. This keeps database configuration external and environment-specific.
2. **Use `transaction { }` for writes.** Reads work without explicit transactions, but writes should always be wrapped to ensure atomicity.
3. **Register frequently-used repositories at startup.** The `stormRepositories { register(...) }` DSL creates proxy instances once, avoiding per-request overhead.
4. **Use `call.orm` in routes.** It is the most concise access pattern for ad-hoc entity operations.
5. **Schema validation catches mapping errors early.** Set `schemaMode = "warn"` during development to surface mismatches between your entities and the database without blocking startup.
6. **Hot reload is safe.** Storm's stateless `ORMTemplate` has no proxied state or open sessions. The plugin closes the DataSource on `ApplicationStopped`, so Ktor's development mode auto-reload works without connection leaks.
