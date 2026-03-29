---
title: Introduction
slug: /
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# ST/ORM

:::info Built for AI
Storm's concise API, strict conventions, and absence of hidden complexity make it optimized for AI-assisted development. Combined with AI skills and secure schema access via MCP, AI coding tools generate correct Storm code consistently. Install with `npm install -g @storm-orm/cli` and run `storm init` in your project. See [AI-Assisted Development](ai.md).
:::

**Storm** is a modern, high-performance ORM for Kotlin 2.0+ and Java 21+, built around a powerful SQL template engine. It focuses on simplicity, type safety, and predictable performance through immutable models and compile-time metadata.

**Key benefits:**

- **Minimal code**: Define entities with simple records/data classes and query with concise, readable syntax, no boilerplate.
- **Parameterized by default**: String interpolations are automatically converted to bind variables, making queries SQL injection safe by design.
- **Close to SQL**: Storm embraces SQL rather than abstracting it away, keeping you in control of your database operations.
- **Type-safe**: Storm's DSL mirrors SQL, providing a type-safe, intuitive experience that makes queries easy to write and read while reducing the risk of runtime errors.
- **Direct Database Interaction**: Storm translates method calls directly into database operations, offering a transparent and straightforward experience. It eliminates inefficiencies like the N+1 query problem for predictable and efficient interactions.
- **Stateless**: Avoids hidden complexities and "magic" with stateless, record-based entities, ensuring simplicity and eliminating lazy initialization and transaction issues downstream.
- **Performance**: Template caching, transaction-scoped entity caching, and zero-overhead dirty checking (thanks to immutability) ensure efficient database interactions. Batch processing, lazy streams, and upserts are built in.
- **Universal Database Compatibility**: Fully compatible with all SQL databases, it offers flexibility and broad applicability across various database systems.

## Why Storm?

Storm draws inspiration from established ORMs such as Hibernate, but is built from scratch around a clear design philosophy: capture intent using the minimum amount of code, optimized for Kotlin and modern Java.

**Storm's mission:** Make database development productive and enjoyable, with full developer control and high performance.

Storm embraces SQL rather than abstracting it away. It simplifies database interactions while remaining transparent, and scales from prototypes to enterprise systems.

| Traditional ORM Pain | Storm Solution |
|----------------------|----------------|
| N+1 queries from lazy loading | Entity graphs load in a single query |
| Hidden magic (proxies, implicit flush, cascades) | Stateless records; explicit, predictable behavior |
| Entity state confusion (managed/detached/transient) | Immutable records; no state to manage |
| Entities tied to session/context | Stateless records easily cached and shared across layers |
| Dirty checking via bytecode manipulation | Lightning-fast dirty checking thanks to immutability |
| Complex mapping configuration | Convention over configuration |
| Runtime query errors | Compile-time type-safe DSL |
| SQL hidden behind abstraction layers | SQL-first design; stay close to the database |

**Storm is ideal for** developers who understand that the best solutions emerge when object model and database model work in harmony. If you value a database-first approach where records naturally mirror your schema, Storm is built for you. Custom mappings are supported when needed, but the real elegance comes from alignment, not abstraction.

## Choose Your Language

Both Kotlin and Java support SQL Templates for powerful query composition. Kotlin additionally provides a type-safe DSL with infix operators for a more idiomatic experience.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

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
val users: Flow<User> = orm.entity(User::class).selectAll()
users.collect { user -> println(user.name) }

// Programmatic transactions
transaction {
    val city = orm insert City(name = "Sunnyvale", population = 155_000)
    val user = orm insert User(email = "bob@example.com", name = "Bob", city = city)
}
```

</TabItem>
<TabItem value="java" label="Java">

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

</TabItem>
</Tabs>

## Quick Start

Storm provides a Bill of Materials (BOM) for centralized version management. Import the BOM once and omit version numbers from individual Storm dependencies.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin (Gradle)" default>

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))
    implementation("st.orm:storm-kotlin")
    runtimeOnly("st.orm:storm-core")
    // Use storm-compiler-plugin-2.0 for Kotlin 2.0.x, -2.1 for 2.1.x, etc.
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")
}
```

</TabItem>
<TabItem value="java" label="Java (Maven)">

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

<dependencies>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-java21</artifactId>
    </dependency>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-core</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

</TabItem>
</Tabs>

Ready to get started? Head to the [Getting Started](getting-started.md) guide.

## Learning Paths

Not sure where to begin? Pick the path that fits your situation.

### New to Storm

If you are new to Storm, follow these guides in order to build a solid foundation:

1. [Installation](installation.md) -- add Storm to your project
2. [First Entity](first-entity.md) -- define entities, insert and fetch records
3. [First Query](first-query.md) -- filtering, repositories, and streaming
4. [Entities](entities.md) -- annotations, nullability, naming conventions
5. [Queries](queries.md) -- the full query DSL and builder reference
6. [Repositories](repositories.md) -- the repository pattern and custom query methods
7. [Relationships](relationships.md) -- foreign keys, entity graphs, and many-to-many

### Migrating from JPA

If you are coming from JPA or Hibernate, these pages explain the key differences and how to transition:

1. [Migration from JPA](migration-from-jpa.md) -- annotation mapping, concept translation, coexistence strategy
2. [Storm vs Other Frameworks](comparison.md) -- feature comparison with JPA, jOOQ, MyBatis, and others
3. [Entities](entities.md) -- how Storm entities differ from JPA entities
4. [Repositories](repositories.md) -- Storm repositories vs. Spring Data repositories
5. [Transactions](transactions.md) -- transaction management without an EntityManager
6. [Spring Integration](spring-integration.md) -- Spring Boot Starter and auto-configuration

### Evaluating for Production

If you are a tech lead or architect evaluating Storm for a production system, these pages cover the areas that matter most:

1. [Storm vs Other Frameworks](comparison.md) -- feature-level comparison across frameworks
2. [Spring Integration](spring-integration.md) -- Spring Boot auto-configuration, repository scanning, DI
3. [Ktor Integration](ktor-integration.md) -- Ktor plugin, HOCON configuration, coroutine-native transactions
4. [Batch Processing and Streaming](batch-streaming.md) -- bulk operations and large dataset handling
4. [Testing](testing.md) -- JUnit 5 integration, statement capture, and test isolation
5. [Configuration](configuration.md) -- runtime tuning, dirty checking modes, cache retention
6. [Database Dialects](dialects.md) -- database-specific optimizations

## What Storm Does Not Do

Storm is focused on being a great ORM and SQL template engine. It intentionally does not include:

- **Schema migration or DDL generation.** Storm does not automatically create, alter, or drop tables at runtime. With Storm's [AI integration](/ai), your coding assistant can read your database schema and generate Flyway or Liquibase migration scripts on demand. For schema versioning, use [Flyway](https://flywaydb.org/) or [Liquibase](https://www.liquibase.com/).
- **Second-level cache.** Storm's entity cache is transaction-scoped and cleared on commit. For cross-transaction caching, use Spring's `@Cacheable` or a dedicated cache layer like Caffeine or Redis.
- **Lazy loading proxies.** Entities are plain records with no proxies. Related entities are loaded eagerly in a single query via JOINs. For deferred loading, use [Refs](refs.md) to explicitly control when related data is fetched.

## Database Support

Storm works with any JDBC-compatible database. Dialect packages provide optimized support for:

![Oracle](https://img.shields.io/badge/Oracle-F80000?logo=oracle&logoColor=white) ![SQL Server](https://img.shields.io/badge/SQL_Server-CC2927?logo=microsoftsqlserver&logoColor=white) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-4479A1?logo=mysql&logoColor=white) ![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white) ![SQLite](https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white) ![H2](https://img.shields.io/badge/H2-0000bb?logoColor=white)

See [Database Dialects](dialects.md) for installation and configuration details.

## Requirements

- Kotlin 2.0+ or Java 21+
- Maven 3.9+ or Gradle 8+

## Glossary

New to Storm's terminology? See the [Glossary](glossary.md) for definitions of key terms like Entity, Projection, Metamodel, Ref, Hydration, and more.

## License

Storm is released under the [Apache 2.0 License](https://github.com/storm-repo/storm-framework/blob/main/LICENSE).
