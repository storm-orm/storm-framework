import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Ktor Integration

Storm integrates with [Ktor](https://ktor.io/) through a dedicated plugin that handles DataSource lifecycle, configuration, and ORM access. Because Ktor is coroutine-first and Storm's Kotlin API already provides a suspend-friendly `transaction { }` function, the integration is lightweight: no custom transaction infrastructure or SPI providers are needed.

The integration follows Ktor's plugin-based architecture. You `install(Storm)` like any other Ktor plugin, configure it through HOCON or programmatically, and access the ORM through extension properties on `Application`, `ApplicationCall`, and `RoutingContext`. Storm handles connection pooling, transaction propagation, and DataSource lifecycle automatically.

## Installation

Add the Storm Ktor module alongside your core Storm dependencies:

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))

    implementation("st.orm:storm-kotlin")
    implementation("st.orm:storm-ktor")
    runtimeOnly("st.orm:storm-core")
    ksp("st.orm:storm-metamodel-ksp")
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")

    // Connection pooling (recommended)
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Database dialect (pick yours)
    runtimeOnly("st.orm:storm-postgresql")

    // Testing
    testImplementation("st.orm:storm-ktor-test")
    testImplementation("com.h2database:h2")
}
```

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
            val user = orm.entity<User>().findById(call.parameters.getOrFail("id").toInt())
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

The plugin reads Storm ORM properties from the same `storm` section (see [Configuration](#configuration) below).

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

    // Schema validation: "none" (default), "warn", or "fail"
    schemaValidation = "warn"

    // Entity callbacks for lifecycle hooks
    entityCallback(AuditCallback())
}
```

| Option | Default | Description |
|--------|---------|-------------|
| `dataSource` | Created from HOCON | The JDBC DataSource to use. When omitted, a HikariCP pool is created from `storm.datasource.*` in `application.conf`. |
| `config` | Read from HOCON | A `StormConfig` with ORM properties. When omitted, properties are read from `storm.*` in `application.conf`. |
| `schemaValidation` | `"none"` | Validates entity definitions against the database schema at startup. `"warn"` logs mismatches; `"fail"` blocks startup. |
| `entityCallback(...)` | None | Registers entity lifecycle callbacks for insert, update, and delete operations. |

When the application stops, the plugin automatically closes the DataSource if it is a HikariDataSource that the plugin created. If you provide your own DataSource, manage its lifecycle yourself.

---

## Accessing the ORM

The `ORMTemplate` is stored in the application's attributes and accessible through extension properties. Storm provides extensions on three types, so you can pick the most convenient access pattern depending on where you are in the code:

| Extension | Available in | Example |
|-----------|-------------|---------|
| `Application.orm` | Application setup, plugins | `application.orm.entity(User::class)` |
| `ApplicationCall.orm` | Route handlers | `call.orm.entity(User::class)` |
| `RoutingContext.orm` | Route handlers (implicit `this`) | `orm.entity(User::class)` |

```kotlin
// In route handlers via ApplicationCall (explicit)
get("/users") {
    val users = call.orm.entity(User::class)
    call.respond(users.findAll())
}

// In route handlers via RoutingContext (implicit, most concise)
routing {
    get("/users") {
        val users = orm.entity(User::class)
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
    val user = orm.entity<User>().findById(call.parameters.getOrFail("id").toInt())
    call.respond(user ?: HttpStatusCode.NotFound)
}
```

### Write Operations

Use `transaction { }` to group writes into a single atomic operation. The transaction commits when the block completes successfully and rolls back if an exception is thrown. No manual commit or rollback calls are needed.

```kotlin
post("/users") {
    val request = call.receive<CreateUserRequest>()
    val user = transaction {
        call.orm insert User(email = request.email, name = request.name, city = city)
    }
    call.respond(HttpStatusCode.Created, user)
}
```

Because `transaction { }` is a suspend function, it integrates naturally with Ktor's coroutine-based request handling. You can call it from any route handler without blocking the event loop.

### Nested Transactions and Propagation

Storm supports all standard propagation modes. Nested transactions are useful when composing services that each define their own transactional requirements. For example, an audit log that should persist even if the main operation fails needs its own independent transaction.

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

All seven standard propagation modes are supported: `REQUIRED` (default), `REQUIRES_NEW`, `NESTED`, `SUPPORTS`, `MANDATORY`, `NOT_SUPPORTED`, and `NEVER`. See [Transactions](transactions.md) for the full propagation matrix and detailed examples.

### Read-Only Transactions

For queries that benefit from repeatable-read consistency (e.g., generating a report from multiple queries that must see the same data snapshot), use a read-only transaction:

```kotlin
get("/reports/summary") {
    val summary = transaction(readOnly = true) {
        val orders = call.orm.entity(Order::class).findAll()
        val total = orders.sumOf { it.amount }
        ReportSummary(orderCount = orders.size, totalAmount = total)
    }
    call.respond(summary)
}
```

Read-only transactions hint the database driver to optimize for reads and enable Storm's entity cache to serve repeated lookups within the same transaction without re-querying the database.

---

## Repository Registration

Custom repository interfaces provide a clean way to encapsulate query logic. In Ktor, repositories are registered at application startup and cached for the lifetime of the application. Storm provides two registration strategies.

### Explicit Registration

Register each repository individually. This gives you full control over which repositories are available and is the most straightforward approach for small to medium projects:

```kotlin
fun Application.module() {
    install(Storm)

    stormRepositories {
        register(UserRepository::class)
        register(OrderRepository::class)
    }

    routing {
        get("/users/{email}") {
            val users = call.repository<UserRepository>()
            val user = users.findByEmail(call.parameters.getOrFail("email"))
            call.respond(user ?: HttpStatusCode.NotFound)
        }
    }
}
```

### Register by Package

For larger projects with many repositories, use `register()` with a package name to register all repository interfaces in that package automatically. This reads a compile-time index generated by the Storm metamodel processor (KSP or annotation processor), which is already part of the standard Storm setup for metamodel generation. No runtime classpath scanning is involved.

```kotlin
stormRepositories {
    // Register all repositories in a specific package (and its sub-packages)
    register("com.myapp.repository")

    // Or register all indexed repositories across the entire project
    register()
}
```

You can combine both approaches: use `register("package")` for bulk registration and `register(Type::class)` for individual repositories that live outside the scanned packages.

### Direct Entity Access

The `repository<T>()` extension is available on `Application`, `ApplicationCall`, and `RoutingContext`. For simple CRUD operations that do not require custom query methods, you can skip repository registration entirely and use the generic entity repository directly:

```kotlin
get("/users") {
    val users = call.orm.entity(User::class)
    call.respond(users.findAll())
}
```

This creates a temporary `EntityRepository<User, Int>` for the call. For frequently accessed entities, registering a named repository is more efficient because the proxy is created once at startup.

### Using with Koin

If your project uses Koin for dependency injection, combine `stormRepositories` with Koin. Register repositories through Storm (which handles proxy creation and caching), then expose them to Koin using `forEach`:

```kotlin
fun Application.module() {
    install(Storm)

    val registry = stormRepositories {
        register("com.myapp.repository")
    }

    install(Koin) {
        modules(module {
            single { this@module.orm }
            registry.forEach { type, instance ->
                single(type) { instance }
            }
        })
    }
}
```

Storm's `storm-ktor` module has no Koin dependency. The integration works because the plugin exposes `Application.orm`, which any DI framework can consume.

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

See the [Configuration](configuration.md) guide for a description of each property and the full precedence rules.

### Environment-Specific Configuration

HOCON supports substitution and include directives, making it straightforward to maintain environment-specific configurations without code changes. Define sensible defaults in `application.conf` and override them per environment:

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

Include the environment file with:

```hocon
include "application-${?KTOR_ENV}.conf"
```

Set the `KTOR_ENV` environment variable to select the active profile (e.g., `KTOR_ENV=production`). Properties in the included file override those in `application.conf`.

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

To use a decorator with the Ktor plugin, create a custom `ORMTemplate` and pass the DataSource to the plugin:

```kotlin
fun Application.module() {
    val dataSource = HikariDataSource(hikariConfig)

    install(Storm) {
        this.dataSource = dataSource
    }

    // Override the ORM template with a decorated version
    val decoratedOrm = dataSource.orm { decorator ->
        decorator
            .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
            .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
    }
}
```

See the [Spring Integration](spring-integration.md#template-decorator) section on Template Decorator for the full list of available resolvers. The resolvers are framework-agnostic and work identically in Ktor.

---

## Schema Validation

Storm can validate entity definitions against the live database schema at startup. This catches common mapping errors (missing columns, type mismatches, nullability differences) before your application serves its first request. Configure the validation mode in the plugin or in `application.conf`:

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
| `none` | Skip validation (default). Suitable for production when schemas are managed by migrations. |
| `warn` | Log mismatches at startup without blocking. Recommended during development. |
| `fail` | Block startup if any entity definitions do not match the database schema. Useful in CI/CD pipelines. |

See [Validation](validation.md) for details on what is validated and how to interpret the output.

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
| `stormSqlCapture` | `SqlCapture` | A capture instance for recording and inspecting generated SQL |

### SqlCapture

Use `SqlCapture` to verify the SQL that Storm generates during a request. This is valuable for catching unintended query changes during refactoring and for ensuring complex operations produce the expected number of statements:

```kotlin
@Test
fun `POST users generates single INSERT`() = testStormApplication(
    scripts = listOf("/schema.sql"),
) { scope ->
    application {
        install(Storm) { dataSource = scope.stormDataSource }
        routing { userRoutes() }
    }

    scope.stormSqlCapture.run {
        client.post("/users") {
            contentType(ContentType.Application.Json)
            setBody("""{"email":"alice@test.com","name":"Alice"}""")
        }
    }
    assertEquals(1, scope.stormSqlCapture.count(Operation.INSERT))
}
```

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

Both approaches use H2 in-memory databases by default. For testing against a real database (e.g., PostgreSQL with Testcontainers), provide a custom DataSource. See [Testing](testing.md) for the full testing guide.

---

## Complete Example

A minimal but complete Ktor application with Storm, showing plugin setup, repository registration, CRUD routes, and HOCON configuration:

```kotlin
// Application.kt
fun main(args: Array<String>) {
    embeddedServer(Netty, port = 8080) {
        module()
    }.start(wait = true)
}

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
            val user = call.orm.entity<User>().findById(id)
            call.respond(user ?: HttpStatusCode.NotFound)
        }

        post("/users") {
            val request = call.receive<CreateUserRequest>()
            val user = transaction {
                call.orm insert User(email = request.email, name = request.name)
            }
            call.respond(HttpStatusCode.Created, user)
        }

        delete("/users/{id}") {
            val id = call.parameters.getOrFail("id").toInt()
            transaction {
                call.orm.entity<User>().removeById(id)
            }
            call.respond(HttpStatusCode.NoContent)
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
