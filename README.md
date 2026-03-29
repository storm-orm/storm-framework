# ST/ORM

[![Maven Central](https://img.shields.io/maven-central/v/st.orm/storm-core.svg)](https://central.sonatype.com/namespace/st.orm)
[![CI](https://github.com/storm-repo/storm-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/storm-repo/storm-framework/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/storm-repo/storm-framework/graph/badge.svg)](https://codecov.io/gh/storm-repo/storm-framework)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Docs](https://img.shields.io/badge/docs-storm--framework-blue)](https://storm-repo.github.io/storm-framework/)
[![Kotlin 2.0+](https://img.shields.io/badge/Kotlin-2.0%2B-purple)](https://kotlinlang.org/)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/projects/jdk/21/)

**Storm** is a modern, high-performance ORM for Kotlin 2.0+ and Java 21+, built around a powerful SQL template engine. It focuses on simplicity, type safety, and predictable performance through immutable models and compile-time metadata.

**Key benefits:**

- **Minimal code**: Define entities with simple records/data classes and query with concise, readable syntax; no boilerplate.
- **Parameterized by default**: String interpolations are automatically converted to bind variables, making queries SQL injection safe by design.
- **Close to SQL**: Storm embraces SQL rather than abstracting it away, keeping you in control of your database operations.
- **Type-safe**: Storm's DSL mirrors SQL, providing a type-safe, intuitive experience that makes queries easy to write and read while reducing the risk of runtime errors.
- **Direct Database Interaction**: Storm translates method calls directly into database operations, offering a transparent and straightforward experience. It eliminates inefficiencies like the N+1 query problem for predictable and efficient interactions.
- **Stateless**: Avoids hidden complexities and "magic" with stateless, record-based entities, ensuring simplicity and eliminating lazy initialization and transaction issues downstream.
- **Performance**: Template caching, transaction-scoped entity caching, and zero-overhead dirty checking (thanks to immutability) ensure efficient database interactions. Batch processing, lazy streams, and upserts are built in.
- **Universal Database Compatibility**: Fully compatible with all SQL databases, it offers flexibility and broad applicability across various database systems.

## Why Storm?

Storm draws inspiration from established ORMs such as Hibernate, but is built from scratch around a clear design philosophy: capturing exactly what you want to do using the minimum amount of code, optimized for Kotlin and modern Java.

**Storm’s mission:** Make database development productive and enjoyable, with full developer control and high performance.

Storm embraces SQL rather than abstracting it away. It simplifies database interactions while remaining transparent, and scales from prototypes to enterprise systems.

| Traditional ORM Pain | Storm Solution |
|----------------------|----------------|
| N+1 queries from lazy loading | Entity graphs load in a single query |
| Hidden magic (proxies, implicit flush, cascades) | Stateless records—explicit, predictable behavior |
| Entity state confusion (managed/detached/transient) | Immutable records—no state to manage |
| Entities tied to session/context | Stateless records easily cached and shared across layers |
| Dirty checking via bytecode manipulation | Lightning-fast dirty checking thanks to immutability |
| Complex mapping configuration | Convention over configuration |
| Runtime query errors | Compile-time type-safe DSL |
| SQL hidden behind abstraction layers | SQL-first design—stay close to the database |

**Storm is ideal for** developers who understand that the best solutions emerge when object model and database model work in harmony. If you value a database-first approach where records naturally mirror your schema, Storm is built for you. Custom mappings are supported when needed, but the real elegance comes from alignment, not abstraction.

## Choose Your Language

Both Kotlin and Java support SQL Templates for powerful query composition. Kotlin additionally provides a type-safe DSL with infix operators for a more idiomatic experience.

### Kotlin

```kotlin
// Define an entity
data class User(
    @PK val id: Int = 0,
    val email: String,
    val name: String,
    @FK val city: City
) : Entity<Int>

// Type-safe predicates — query nested properties like city.name in one go
val users = orm.findAll(User_.city.name eq "Sunnyvale")

// Custom repository — inherits all CRUD operations, add your own queries
interface UserRepository : EntityRepository<User, Int> {
    fun findByCityName(name: String) = findAll(User_.city.name eq name)
}
val users = userRepository.findByCityName("Sunnyvale")

// Block DSL — build queries with where, orderBy, joins, pagination
val users = userRepository.select {
    where(User_.city.name eq "Sunnyvale")
    orderBy(User_.name)
}.resultList

// SQL Template for full control; parameterized by default, SQL injection safe
val users = orm.query { """
        SELECT ${User::class}
        FROM ${User::class}
        WHERE ${User_.city.name} = $cityName"""
    }.resultList<User>()
```

Full coroutine support with `Flow` for streaming and programmatic transactions:

```kotlin
// Streaming with Flow
val users: Flow<User> = orm.entity(User::class).select().resultFlow
users.collect { user -> println(user.name) }

// Programmatic transactions
transaction {
    val city = orm insert City(name = "Sunnyvale", population = 155_000)
    val user = orm insert User(email = "bob@example.com", name = "Bob", city = city)
}
```

### Java

```java
// Define an entity
record User(@PK Integer id,
            String email,
            String name,
            @FK City city
) implements Entity<Integer> {}

// Custom repository—inherits all CRUD operations, add your own queries
interface UserRepository extends EntityRepository<User, Integer> {
    default List<User> findByCityName(String name) {
        return select().where(User_.city.name, EQUALS, name).getResultList();
    }
}
List<User> users = userRepository.findByCityName("Sunnyvale");

// Query Builder for more complex operations
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.city.name, EQUALS, "Sunnyvale")
    .orderBy(User_.name)
    .getResultList();

// SQL Template for full control; parameterized by default, SQL injection safe
List<User> users = orm.query(RAW."""
        SELECT \{User.class}
        FROM \{User.class}
        WHERE \{User_.city.name} = \{cityName}
        """).getResultList(User.class);
```

> **String Templates:** Kotlin uses a compiler plugin that automatically wraps interpolations at compile time. Java uses String Templates, a preview feature. See [String Templates](docs/string-templates.md) for setup and details on both languages.

## Quick Start

### Dependency Management (BOM)

Storm provides a Bill of Materials (BOM) for centralized version management. Import the BOM once and omit version numbers from individual Storm dependencies.

**Maven:**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>st.orm</groupId>
            <artifactId>storm-bom</artifactId>
            <version>@@STORM_VERSION@@</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))
}
```

With the BOM imported, add Storm modules without specifying versions:

### Kotlin

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))
    implementation("st.orm:storm-kotlin")
    runtimeOnly("st.orm:storm-core")
    // Use storm-compiler-plugin-2.0 for Kotlin 2.0.x, -2.1 for 2.1.x, etc.
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")
}
```

### Java

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-java21</artifactId>
</dependency>
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-core</artifactId>
    <scope>runtime</scope>
</dependency>
```

## Documentation

Full documentation is available at [storm-repo.github.io/storm-framework](https://storm-repo.github.io/storm-framework/).

### Core Concepts

Everything you need to build applications with Storm. Start with Getting Started and work through the topics as needed.

| Topic | Description |
|-------|-------------|
| [Getting Started](docs/getting-started.md) | Installation and first steps (7 min) |
| [Entities](docs/entities.md) | Defining entities, annotations, naming (12 min) |
| [Projections](docs/projections.md) | Read-only database views (8 min) |
| [Relationships](docs/relationships.md) | One-to-one, many-to-one, many-to-many (13 min) |
| [Repositories](docs/repositories.md) | Repository pattern and custom methods (5 min) |
| [Queries](docs/queries.md) | Select, filter, aggregate, order (8 min) |
| [Metamodel](docs/metamodel.md) | Compile-time type safety (10 min) |
| [Refs](docs/refs.md) | Lazy loading and optimized references (7 min) |
| [Batch & Streaming](docs/batch-streaming.md) | Bulk operations and Flow/Stream (5 min) |
| [Upserts](docs/upserts.md) | Insert-or-update operations (6 min) |
| [Polymorphism](docs/polymorphism.md) | Sealed type inheritance strategies (20 min) |
| [Entity Lifecycle](docs/entity-lifecycle.md) | Callbacks for auditing, validation, and logging (8 min) |
| [JSON Support](docs/json.md) | JSON columns and aggregation (6 min) |
| [Transactions](docs/transactions.md) | Transaction management and propagation (22 min) |
| [Spring Integration](docs/spring-integration.md) | Spring Boot Starter and auto-configuration (8 min) |
| [Database Dialects](docs/dialects.md) | Database-specific support (5 min) |
| [Testing](docs/testing.md) | JUnit 5 integration and statement capture (5 min) |
| [Validation](docs/validation.md) | Record and schema validation (5 min) |

### Advanced Topics

Deep dives into Storm's internals. You don't need these to be productive, but they help you understand what happens under the hood and optimize performance.

| Topic | Description |
|-------|-------------|
| [String Templates](docs/string-templates.md) | Kotlin compiler plugin and Java string templates (5 min) |
| [SQL Templates](docs/sql-templates.md) | Template parameters and query generation (10 min) |
| [Hydration](docs/hydration.md) | Result mapping to records (16 min) |
| [Dirty Checking](docs/dirty-checking.md) | Update modes and change detection (19 min) |
| [Entity Cache](docs/entity-cache.md) | Transaction-scoped caching and identity (10 min) |
| [Configuration](docs/configuration.md) | System properties reference (7 min) |
| [SQL Logging](docs/sql-logging.md) | Declarative query logging with `@SqlLog` (6 min) |
| [Metrics](docs/metrics.md) | JMX runtime metrics for monitoring (5 min) |

### Resources

Guides for evaluating Storm and transitioning from other frameworks.

| Topic | Description |
|-------|-------------|
| [Comparison](docs/comparison.md) | Storm vs other frameworks |
| [FAQ](docs/faq.md) | Frequently asked questions |
| [Migration from JPA](docs/migration-from-jpa.md) | Transitioning from JPA/Hibernate |

## Database Support

Storm works with any JDBC-compatible database. Dialect packages provide optimized support for:

[![Oracle](https://img.shields.io/badge/Oracle-F80000?style=for-the-badge&logo=oracle&logoColor=white)](https://www.oracle.com/database/)
[![SQL Server](https://img.shields.io/badge/SQL%20Server-CC2927?style=for-the-badge&logo=microsoftsqlserver&logoColor=white)](https://www.microsoft.com/sql-server)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-336791?style=for-the-badge&logo=postgresql&logoColor=white)](https://www.postgresql.org/)
[![MySQL](https://img.shields.io/badge/MySQL-4479A1?style=for-the-badge&logo=mysql&logoColor=white)](https://www.mysql.com/)
[![MariaDB](https://img.shields.io/badge/MariaDB-003545?style=for-the-badge&logo=mariadb&logoColor=white)](https://mariadb.org/)
[![SQLite](https://img.shields.io/badge/SQLite-003B57?style=for-the-badge&logo=sqlite&logoColor=white)](https://www.sqlite.org/)
[![H2](https://img.shields.io/badge/H2-0000bb?style=for-the-badge&logoColor=white)](https://h2database.com/)

## Requirements

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0%2B-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)](https://kotlinlang.org/)
[![Java](https://img.shields.io/badge/Java-21%2B-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)

Storm targets Kotlin 2.0+ and Java 21+ as minimum supported versions. These baselines will be maintained for the foreseeable future.

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## License

Storm is released under the [Apache 2.0 License](LICENSE).
