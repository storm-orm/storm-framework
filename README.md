# ST/ORM

[![Maven Central](https://img.shields.io/maven-central/v/st.orm/storm-core.svg)](https://central.sonatype.com/namespace/st.orm)
[![CI](https://github.com/storm-orm/storm-framework/actions/workflows/ci.yml/badge.svg)](https://github.com/storm-orm/storm-framework/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/storm-orm/storm-framework/graph/badge.svg)](https://codecov.io/gh/storm-orm/storm-framework)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Docs](https://img.shields.io/badge/docs-orm.st-blue)](https://orm.st)
[![Kotlin 2.0+](https://img.shields.io/badge/Kotlin-2.0%2B-purple)](https://kotlinlang.org/)
[![Java 21](https://img.shields.io/badge/Java-21-blue)](https://openjdk.org/projects/jdk/21/)

**Storm is the type-safe, SQL-first ORM for Kotlin 2.0+ and Java 21.** Immutable data-class entities, one-line queries checked at compile time, and none of the machinery you fight in traditional ORMs: no proxies, no N+1, no persistence context.

```kotlin
// An entity is a data class. This is the whole mapping.
data class User(
    @PK val id: Int = 0,
    val email: String,
    @FK val city: City,
) : Entity<Int>

// Query nested properties in one line, checked at compile time.
val users = orm.findAll(User_.city.name eq "Sunnyvale")

// Repositories inherit CRUD. Add only the queries that are yours.
interface UserRepository : EntityRepository<User, Int> {
    fun findByCity(name: String) = findAll(User_.city.name eq name)
}
```

The `city` graph loads in a single query, and `User_` is generated at compile time so a typo is a compile error. What you write is what runs.

## Why Storm

Storm draws on decades of ORM experience but starts from a different premise: capture exactly what you want to do in the fewest lines, stay close to SQL, and keep the runtime transparent. Records mirror your schema, queries mirror SQL, and nothing happens that you did not write.

| Traditional ORM pain | Storm |
|----------------------|-------|
| N+1 queries from lazy loading | Entity graphs load in a single query |
| Hidden magic: proxies, implicit flush, cascades | Stateless records, explicit and predictable |
| Entity state confusion: managed, detached, transient | Immutable records, no state to manage |
| Entities tied to a session or context | Stateless records, cached and shared across layers |
| Dirty checking via bytecode manipulation | Dirty checking is free, thanks to immutability |
| Complex mapping configuration | Convention over configuration |
| Runtime query errors | Compile-time, type-safe DSL |
| SQL hidden behind abstraction layers | SQL-first: you stay close to the database |

Storm is built for developers who want the object model and the database model to work in harmony. Custom mappings are there when you need them, but the elegance comes from alignment, not abstraction.

## More Kotlin

Both Kotlin and Java use SQL templates for query composition. Kotlin adds a type-safe DSL with infix operators, coroutines, and `Flow` streaming.

```kotlin
// Block DSL: where, orderBy, joins, pagination.
val users = userRepository.select {
    where(User_.city.name eq "Sunnyvale")
    orderBy(User_.name)
}.resultList

// Stream results as a Flow.
val stream: Flow<User> = orm.entity(User::class).select().resultFlow

// Programmatic transactions, coroutine-friendly.
transaction {
    val city = orm insert City(name = "Sunnyvale", population = 161_884)
    orm insert User(email = "bob@example.com", name = "Bob", city = city)
}
```

Prefer Java? The same model works with records and Java string templates. See the [Java + Spring Boot example](https://github.com/storm-orm/storm-example-java-spring-boot-4) and the [String Templates](docs/string-templates.md) guide.

> **String Templates:** Kotlin uses a compiler plugin that wraps interpolations at compile time; Java uses String Templates, a preview feature. See [String Templates](docs/string-templates.md) for setup in both languages.

## Quick Start

New to Storm? The fastest path is the **5-minute [Quickstart](https://orm.st/quickstart)**: install, define an entity, run a type-safe query.

### Gradle (recommended)

The Storm Gradle plugin is the whole setup in one block: it imports the BOM, adds the core dependencies, wires the metamodel processor, selects the compiler-plugin variant for your Kotlin version, and sets the Java preview flags. Requires Gradle 8.5+.

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("com.google.devtools.ksp") version "2.3.10"
    id("st.orm") version "1.14.1"
}
```

For Java, drop the Kotlin and KSP plugins:

```kotlin
plugins {
    java
    id("st.orm") version "1.14.1"
}
```

Add a database dialect and any integrations as ordinary dependencies; versions come from the BOM the plugin imports, so you never specify them per module:

```kotlin
dependencies {
    runtimeOnly("st.orm:storm-postgresql")
    implementation("st.orm:storm-kotlin-spring-boot-starter")
}
```

### Which modules do I need?

Storm is modular; you add only what your stack uses. Versions come from the BOM, so you never specify them per module.

| Your stack              | Add |
|-------------------------|-----|
| **Kotlin** (any)        | `storm-kotlin` + `storm-core` (runtime) + `storm-metamodel-ksp` (ksp) + `storm-compiler-plugin-2.x` |
| **Kotlin + Spring Boot** | `storm-kotlin-spring-boot-starter` |
| **Kotlin + Ktor**       | `storm-kotlin` + `storm-ktor` |
| **Java 21**             | `storm-java21` + `storm-core` (runtime) + `storm-metamodel-processor` |
| **Java + Spring Boot**  | `storm-spring-boot-starter` |
| **+ your database**     | one dialect (runtime): `storm-postgresql`, `storm-mysql`, `storm-mariadb`, `storm-oracle`, `storm-mssqlserver`, `storm-sqlite`, `storm-h2` |
| **+ JSON columns**      | `storm-jackson2`, `storm-jackson3`, or `storm-kotlinx-serialization` |
| **+ metrics & tracing** | `storm-micrometer` (included in the Spring Boot starters) |
| **+ Spring Boot test slice** | `storm-spring-boot-test-autoconfigure` (test scope, `@DataStormTest`) |

See [Installation](https://orm.st/docs/installation) for the full module overview.

### Without the plugin (Maven, or manual Gradle)

Without the Gradle plugin, import the BOM once and add the modules yourself. On Gradle the compiler-plugin variant matches your Kotlin major.minor version (`storm-compiler-plugin-2.0` for Kotlin 2.0.x, `-2.1` for 2.1.x, and so on).

**Maven:**

```xml
<dependencyManagement>
    <dependencies>
        <dependency>
            <groupId>st.orm</groupId>
            <artifactId>storm-bom</artifactId>
            <version>1.14.1</version>
            <type>pom</type>
            <scope>import</scope>
        </dependency>
    </dependencies>
</dependencyManagement>
```

**Gradle (Kotlin DSL), Kotlin:**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:1.14.1"))
    implementation("st.orm:storm-kotlin")
    runtimeOnly("st.orm:storm-core")
    ksp("st.orm:storm-metamodel-ksp")
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")
}
```

**Gradle (Kotlin DSL), Java:**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:1.14.1"))
    implementation("st.orm:storm-java21")
    runtimeOnly("st.orm:storm-core")
    annotationProcessor("st.orm:storm-metamodel-processor")
}

tasks.withType<JavaCompile> {
    options.compilerArgs.add("--enable-preview")
}

tasks.withType<Test> {
    jvmArgs("--enable-preview")
}
```

Both paths add the metamodel processor, which generates the `User_` and `City_` classes queries are written against, and the Java path sets the preview flags that Storm's string templates require. The Gradle plugin does all of that for you.

With the BOM imported, add Storm modules without specifying versions.

## AI-Assisted Development

Storm's stateless, immutable model is a natural fit for AI coding tools: what you see in the source is exactly what runs, with no proxies, lazy loading, or hidden persistence-context rules to trip up generated code. An optional workflow gives AI tools full schema awareness through a local MCP server, guides them with Storm-specific skills, and closes the loop by verifying generated entities and queries with real tests rather than trusting model reasoning.

```bash
npm install -g @storm-orm/cli
storm init
```

See [AI-Assisted Development](docs/ai.md) for the full workflow.

## Examples

Three complete, runnable **Storm Movies** applications, each importing the public IMDB dataset and rendered inline at [orm.st/examples](https://orm.st/examples):

| Example | Stack | Repository |
|---------|-------|------------|
| [Kotlin + Ktor](https://orm.st/examples/kotlin-ktor) | Kotlin, Ktor 3, Ktor DI, PostgreSQL | [storm-example-kotlin-ktor](https://github.com/storm-orm/storm-example-kotlin-ktor) |
| [Kotlin + Spring Boot](https://orm.st/examples/kotlin-spring-boot) | Kotlin, Spring Boot 4, PostgreSQL | [storm-example-kotlin-spring-boot-4](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4) |
| [Java + Spring Boot](https://orm.st/examples/java-spring-boot) | Java 21, Spring Boot 4, PostgreSQL | [storm-example-java-spring-boot-4](https://github.com/storm-orm/storm-example-java-spring-boot-4) |

Each repository is a GitHub template. Click **Use this template** to start your own project: [Kotlin + Ktor](https://github.com/storm-orm/storm-example-kotlin-ktor/generate) · [Kotlin + Spring Boot](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4/generate) · [Java + Spring Boot](https://github.com/storm-orm/storm-example-java-spring-boot-4/generate).

## Documentation

Full documentation is available at [orm.st](https://orm.st).

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
| [Entity Design](docs/entity-design.md) | Choosing between inlined foreign keys and Refs (9 min) |
| [Batch & Streaming](docs/batch-streaming.md) | Bulk operations and Flow/Stream (5 min) |
| [Upserts](docs/upserts.md) | Insert-or-update operations (6 min) |
| [Write Sets](docs/write-sets.md) | Dependency-ordered writes of mixed-type entity graphs (7 min) |
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
| [SQL Logging](docs/sql-logging.md) | Statement logging and per-call SQL summaries (6 min) |
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
[![Java](https://img.shields.io/badge/Java-21-ED8B00?style=for-the-badge&logo=openjdk&logoColor=white)](https://openjdk.org/)

Storm targets Kotlin 2.0+ as its minimum supported Kotlin version; the Kotlin path runs on JDK 21 or later. The Java API uses String Templates, a preview feature, and preview class files are version-locked: the Java path compiles and runs on JDK 21 exactly until the platform reintroduces string templates as a stable feature. See [String Templates](https://orm.st/docs/string-templates#status) for the full story.

## Contributing

We welcome contributions! See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

## Community

Have a question, an idea, or something you built with Storm? Join the conversation in [GitHub Discussions](https://github.com/storm-orm/storm-framework/discussions):

- [Ask a question](https://github.com/storm-orm/storm-framework/discussions/categories/q-a) if you are getting started or stuck.
- [Share an idea](https://github.com/storm-orm/storm-framework/discussions/categories/ideas) for a future release.
- [Show what you built](https://github.com/storm-orm/storm-framework/discussions/categories/show-and-tell).

If Storm is useful to you, a [star](https://github.com/storm-orm/storm-framework) helps other developers find it.

## License

Storm is released under the [Apache 2.0 License](LICENSE.txt).
