import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Ktor Integration

Storm integrates with [Ktor](https://ktor.io/) through a dedicated plugin that handles DataSource lifecycle, configuration, and ORM access. Because Ktor is coroutine-first and Storm's Kotlin API already provides a suspend-friendly `transaction { }` function, the integration is lightweight: no custom transaction infrastructure or SPI providers are needed.

## Installation

Add the Storm Ktor module alongside your core Storm dependencies:

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:1.11.0"))

    implementation("st.orm:storm-kotlin")
    implementation("st.orm:storm-ktor")
    runtimeOnly("st.orm:storm-core")
    ksp("st.orm:storm-metamodel-ksp")
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")

    // Connection pooling (recommended)
    implementation("com.zaxxer:HikariCP:6.2.1")

    // Database dialect (pick yours)
    runtimeOnly("st.orm:storm-postgresql")
}
```

**Maven:**

```xml
<dependencies>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-kotlin</artifactId>
    </dependency>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-ktor</artifactId>
    </dependency>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-core</artifactId>
        <scope>runtime</scope>
    </dependency>
    <dependency>
        <groupId>com.zaxxer</groupId>
        <artifactId>HikariCP</artifactId>
    </dependency>
</dependencies>
```

For testing, add the test support module:

```kotlin
testImplementation("st.orm:storm-ktor-test")
testImplementation("com.h2database:h2")
```

---

## Plugin Setup

Install the `Storm` plugin in your Ktor application module. The plugin creates an `ORMTemplate` and manages the DataSource lifecycle.

### Zero-Configuration Setup

If you configure your database in `application.conf`, the plugin creates a HikariCP DataSource automatically:

```kotlin
fun Application.module() {
    install(Storm)

    routing {
        get("/users/{id}") {
            val user = call.orm.entity(User::class).findById(call.parameters["id"]!!.toInt())
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

### Explicit DataSource

If you prefer to create the DataSource yourself (or use an existing one), pass it directly:

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

The `Storm` plugin accepts several configuration options:

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

When the application stops, the plugin automatically closes the DataSource if it is a HikariDataSource. If you provide your own DataSource, manage its lifecycle yourself.

---

## Accessing the ORM

The `ORMTemplate` is stored in the application's attributes and accessible through extension properties:

```kotlin
// In route handlers (most common)
get("/users") {
    val users = call.orm.entity(User::class)
    call.respond(users.findAll().toList())
}

// From the Application object
val orm = application.orm

// From RoutingContext
routing {
    get("/users") {
        val users = orm.entity(User::class)
        call.respond(users.findAll().toList())
    }
}
```

The `ORMTemplate` is stateless: it does not hold connections or track sessions. A single application-wide instance is correct. Inside `transaction { }` blocks, connections are managed automatically through the coroutine context. Outside transactions, each ORM operation acquires and releases its own connection from the pool.

---

## Transaction Management

Storm's Kotlin transaction API works directly in Ktor route handlers because both are coroutine-based. No additional transaction infrastructure or annotations are needed.

### Read Operations

Simple reads do not require an explicit transaction. The ORM acquires a connection for each operation and returns it to the pool immediately:

```kotlin
get("/users/{id}") {
    val user = call.orm.entity(User::class).findById(call.parameters["id"]!!.toInt())
    call.respond(user ?: HttpStatusCode.NotFound)
}
```

### Write Operations

Use `transaction { }` to group writes into a single atomic operation:

```kotlin
post("/users") {
    val request = call.receive<CreateUserRequest>()
    val user = transaction {
        call.orm.entity(User::class)
            .insertAndFetch(User(email = request.email, name = request.name, city = city))
    }
    call.respond(HttpStatusCode.Created, user)
}
```

The transaction commits on success and rolls back on exception. No manual commit or rollback calls are needed.

### Nested Transactions and Propagation

Storm supports all standard propagation modes. See [Transactions](transactions.md) for the full guide.

```kotlin
post("/orders") {
    transaction {
        // Main order processing
        call.orm.entity(Order::class).insert(order)

        transaction(propagation = REQUIRES_NEW) {
            // Audit log in a separate transaction
            call.orm.entity(AuditLog::class).insert(log)
        }
    }
}
```

### Read-Only Transactions

For queries that benefit from repeatable-read consistency, use a read-only transaction:

```kotlin
get("/reports/summary") {
    val summary = transaction(readOnly = true) {
        val orders = call.orm.entity(Order::class).findAll().toList()
        val total = orders.sumOf { it.amount }
        ReportSummary(orderCount = orders.size, totalAmount = total)
    }
    call.respond(summary)
}
```

---

## Repository Registration

Storm provides two ways to register repositories: explicit registration and automatic scanning.

### Explicit Registration

Register each repository individually. This gives you full control over which repositories are available:

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
            val user = users.findByEmail(call.parameters["email"]!!)
            call.respond(user ?: HttpStatusCode.NotFound)
        }
    }
}
```

### Register by Package

Use `register()` with a package name to register all repository interfaces in that package. This reads a compile-time index generated by the Storm metamodel processor (KSP or annotation processor), which is already part of the standard Storm setup for metamodel generation.

```kotlin
stormRepositories {
    // Register all repositories in a specific package
    register("com.myapp.repository")

    // Or register all indexed repositories
    register()
}
```

You can combine both approaches: use `register("package")` for bulk registration and `register(Type::class)` for individual repositories.

### Direct Access

The `repository<T>()` extension is available on `Application`, `ApplicationCall`, and `RoutingContext`.

For simple CRUD operations, you can skip registration and use the generic entity repository directly:

```kotlin
get("/users") {
    val users = call.orm.entity(User::class)
    call.respond(users.findAll().toList())
}
```

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

The Storm plugin reads its configuration from Ktor's HOCON configuration file (`application.conf` or `application.yaml`). All properties under `storm` are mapped to Storm's configuration system.

### DataSource Properties

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

### Storm Properties

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

See the [Configuration](configuration.md) guide for a description of each property.

### Environment-Specific Configuration

Ktor supports HOCON's substitution and include directives for environment-specific profiles:

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

---

## Content Negotiation

Ktor uses the `ContentNegotiation` plugin for JSON serialization. Configure it to handle Storm's `Ref<T>` type correctly.

### With Jackson

```kotlin
install(ContentNegotiation) {
    jackson {
        registerModule(StormModule())
    }
}
```

Add `storm-jackson2` or `storm-jackson3` to your dependencies.

### With Kotlinx Serialization

```kotlin
install(ContentNegotiation) {
    json(Json {
        serializersModule = StormSerializersModule()
    })
}
```

Add `storm-kotlinx-serialization` to your dependencies. See [Serialization](serialization.md) for details on `Ref<T>` serialization behavior and the `@Contextual` annotation requirement.

---

## Template Decorator

To customize table names, column names, or foreign key naming globally, pass a decorator through `StormConfig` or by providing a custom `ORMTemplate`:

```kotlin
install(Storm) {
    val customDataSource = HikariDataSource(hikariConfig)
    dataSource = customDataSource
    config = StormConfig.of()
}
```

Or create a fully custom `ORMTemplate` outside the plugin:

```kotlin
val orm = dataSource.orm { decorator ->
    decorator
        .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
        .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
}

install(Storm) {
    // The plugin will use this DataSource, but you manage the ORMTemplate yourself
    dataSource = myDataSource
}

// Store custom ORM in attributes manually if needed
```

See the [Spring Integration](spring-integration.md#template-decorator) section on Template Decorator for the full list of available resolvers. The resolvers are framework-agnostic and work identically in Ktor.

---

## Schema Validation

Storm can validate entity definitions against the live database schema at startup. Configure the validation mode in the plugin or in `application.conf`:

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

- `none`: skip validation (default)
- `warn`: log mismatches without blocking startup
- `fail`: block startup if any entity definitions do not match the database schema

See [Validation](validation.md) for details on what is validated and how to interpret the output.

---

## Testing

Storm provides two approaches for testing Ktor applications.

### testStormApplication DSL

The `storm-ktor-test` module provides a `testStormApplication` function that creates an H2 in-memory database, executes SQL scripts, and exposes Storm infrastructure:

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

The `StormTestScope` provides:
- `stormDataSource`: the H2 DataSource, pre-loaded with your SQL scripts
- `stormOrm`: a pre-configured `ORMTemplate`
- `stormSqlCapture`: a `SqlCapture` instance for verifying generated SQL

### SqlCapture

Use `SqlCapture` to verify the SQL that Storm generates during a request:

```kotlin
@Test
fun `POST users generates INSERT`() = testStormApplication(
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

The existing `@StormTest` annotation from `storm-test` works alongside Ktor's `testApplication`. Use `@StormTest` for H2 setup and parameter injection, then compose with Ktor's test builder:

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

See [Testing](testing.md) for the full testing guide, including `SqlCapture` usage patterns and real database testing with Testcontainers.

---

## Complete Example

A minimal but complete Ktor application with Storm:

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
            call.respond(users.findAll().toList())
        }

        get("/users/{id}") {
            val id = call.parameters["id"]!!.toInt()
            val user = call.orm.entity(User::class).findById(id)
            call.respond(user ?: HttpStatusCode.NotFound)
        }

        post("/users") {
            val request = call.receive<CreateUserRequest>()
            val user = transaction {
                call.orm.entity(User::class)
                    .insertAndFetch(User(email = request.email, name = request.name))
            }
            call.respond(HttpStatusCode.Created, user)
        }

        delete("/users/{id}") {
            val id = call.parameters["id"]!!.toInt()
            transaction {
                call.orm.entity(User::class).deleteById(id)
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

1. **Use the zero-config setup.** Define your DataSource in `application.conf` and let the plugin handle the rest.
2. **Use `transaction { }` for writes.** Reads work without explicit transactions, but writes should always be wrapped.
3. **Register frequently-used repositories at startup.** Avoid creating repository proxies per-request.
4. **Use `call.orm` in routes.** It is the most concise access pattern.
5. **Schema validation catches mapping errors early.** Set `schemaMode = "warn"` during development.
6. **Hot reload is safe.** Storm's stateless `ORMTemplate` has no proxied state. The plugin closes the DataSource on `ApplicationStopped`.
