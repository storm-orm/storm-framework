---
title: Introduction
slug: /
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Storm

**Storm** is an ORM for Kotlin 2.0+ and Java 21, built on a SQL template engine. Entities are plain immutable data classes and records, queries are checked at compile time, and every database call is explicit: no proxies, no persistence context, no accidental N+1 queries.

## Start Here

| If you want to | Go to |
|----------------|-------|
| See Storm work end to end, in five minutes | **[Quickstart](/quickstart)** |
| Add Storm to a project you already have | [Set Up Your Project](getting-started.md) |
| Understand the model before writing code | [Entities](entities.md), then [Queries](queries.md) |
| Decide whether to adopt it | [Evaluating for Production](#evaluating-for-production) |

The [Quickstart](/quickstart) is the fastest first experience: an empty project, two linked entities, and one type-safe query across the relation, with the generated SQL shown at every step. It takes about five minutes and needs no database server. The rest of this documentation assumes you have either done it or do not need it.

## What Storm Looks Like

Both Kotlin and Java support SQL templates for query composition. Kotlin additionally provides a type-safe DSL with infix operators.

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

// Query builder — where, orderBy, joins, pagination
val users = userRepository.select()
    .where(User_.city.name eq "Sunnyvale")
    .orderBy(User_.name)
    .resultList
```

Full coroutine support with `Flow` for streaming and programmatic transactions:

```kotlin
// Streaming with Flow
val users: Flow<User> = orm.entity<User>().select().resultFlow
users.collect { user -> println(user.name) }

// Programmatic transactions
transaction {
    val city = orm insert City(name = "Sunnyvale", population = 161_884)
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

// Query Builder for more complex operations
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.city.name, EQUALS, "Sunnyvale")
    .orderBy(User_.name)
    .getResultList();
```

</TabItem>
</Tabs>

## Why Storm

Storm draws inspiration from established ORMs such as Hibernate, but is built from scratch around a clear design philosophy: capture intent using the minimum amount of code, optimized for Kotlin and modern Java. It embraces SQL rather than abstracting it away, so database interactions stay simple without becoming opaque.

| Traditional ORM Pain | Storm Solution |
|----------------------|----------------|
| N+1 queries from lazy loading | Entity graphs load in a single query |
| Hidden magic (proxies, implicit flush, cascades) | Stateless records; explicit, predictable behavior |
| Entity state confusion (managed/detached/transient) | Immutable records; no state to manage |
| Entities tied to session/context | Stateless records easily cached and shared across layers |
| Dirty checking via bytecode manipulation | Dirty checking that costs almost nothing, thanks to immutability |
| Complex mapping configuration | Convention over configuration |
| Runtime query errors | Compile-time type-safe DSL |
| SQL hidden behind abstraction layers | SQL-first design; stay close to the database |

Three further properties shape day-to-day use:

- **Parameterized by default.** String interpolations become bind variables, so queries are SQL injection safe by design.
- **Direct database interaction.** Method calls translate into database operations. Nothing is deferred to a flush you did not ask for.
- **Performance by construction.** Template caching, transaction-scoped entity caching, compile-time row mapping, and dirty checking that costs almost nothing thanks to immutability. Batch processing, lazy streams, and upserts are built in.

**Storm is ideal for** developers who want a database-first approach, where records mirror the schema. Custom mappings are supported when you need them, but the model works best when the two line up.

## Learning Paths

Not sure where to begin? Pick the path that fits your situation.

### New to Storm

Follow these guides in order to build a solid foundation:

1. [Set Up Your Project](getting-started.md) -- choose a setup route and get the build wired
2. [Installation](installation.md) -- dependencies, build flags, and optional modules in full
3. [First Entity](first-entity.md) -- define entities, insert and fetch records
4. [First Query](first-query.md) -- filtering, repositories, and streaming
5. [Entities](entities.md) -- annotations, nullability, naming conventions
6. [Queries](queries.md) -- the full query DSL and builder reference
7. [Repositories](repositories.md) -- the repository pattern and custom query methods
8. [Relationships](relationships.md) -- foreign keys, entity graphs, and many-to-many
9. [Entity Design](entity-design.md) -- when to inline a foreign key and when to reach for a Ref

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

1. [What Storm Does Not Do](#what-storm-does-not-do) -- the deliberate omissions, before anything else
2. [Storm vs Other Frameworks](comparison.md) -- feature-level comparison across frameworks
3. [Spring Integration](spring-integration.md) -- Spring Boot auto-configuration, repository scanning, DI
4. [Ktor Integration](ktor-integration.md) -- Ktor plugin, HOCON configuration, coroutine-native transactions
5. [Batch Processing and Streaming](batch-streaming.md) -- bulk operations and large dataset handling
6. [Testing](testing.md) -- JUnit 5 integration, statement capture, and test isolation
7. [Configuration](configuration.md) -- runtime tuning, dirty checking modes, cache retention
8. [Security](security.md) -- injection safety, and what is guaranteed by construction
9. [Database Dialects](dialects.md) -- database-specific optimizations

Release history, the issue tracker, the security policy, and the benchmark harness are linked from the [project home page](/).

## What Storm Does Not Do

Storm is focused on being a great ORM and SQL template engine. It intentionally does not include:

- **Schema migration or DDL generation.** Storm does not automatically create, alter, or drop tables at runtime. With Storm's [AI integration](ai.md), your coding assistant can read your database schema and generate Flyway or Liquibase migration scripts on demand. For schema versioning, use [Flyway](https://flywaydb.org/) or [Liquibase](https://www.liquibase.com/).
- **Second-level cache.** Storm's entity cache is transaction-scoped and cleared on commit. For cross-transaction caching, use Spring's `@Cacheable` or a dedicated cache layer like Caffeine or Redis.
- **Lazy loading proxies.** Entities are plain records with no proxies. Related entities are loaded eagerly in a single query via JOINs. For deferred loading, use [Refs](refs.md) to explicitly control when related data is fetched.

## Database Support

Storm works with any JDBC-compatible database. Dialect packages provide optimized support for:

![Oracle](https://img.shields.io/badge/Oracle-F80000?logo=oracle&logoColor=white) ![SQL Server](https://img.shields.io/badge/SQL_Server-CC2927?logo=microsoftsqlserver&logoColor=white) ![PostgreSQL](https://img.shields.io/badge/PostgreSQL-4169E1?logo=postgresql&logoColor=white) ![MySQL](https://img.shields.io/badge/MySQL-4479A1?logo=mysql&logoColor=white) ![MariaDB](https://img.shields.io/badge/MariaDB-003545?logo=mariadb&logoColor=white) ![SQLite](https://img.shields.io/badge/SQLite-003B57?logo=sqlite&logoColor=white) ![H2](https://img.shields.io/badge/H2-0000bb?logoColor=white)

See [Database Dialects](dialects.md) for installation and configuration details.

## Requirements

- Kotlin 2.0+ (JDK 21 or later), or Java on JDK 21 exactly (the Java API uses preview class files, which are version-locked)
- Maven 3.9+ or Gradle 8+

## AI-Assisted Development

Storm's stateless, immutable entities mean what you see in the source code is exactly what exists at runtime: no hidden proxies, no lazy loading surprises, no persistence context rules that trip up generated code. When you ask an AI tool to write a query, define an entity, or build a repository, the output is straightforward data classes and explicit SQL.

One command configures your tool (Claude Code, Cursor, Copilot, Windsurf, or Codex) with Storm's rules, skills, and slash commands, and can connect it to your development database for schema-aware generation:

```bash
npx @storm-orm/cli init
```

See [AI-Assisted Development](ai.md) for the full setup, and [Database and MCP](database-and-mcp.md) for the schema-aware server.

## Glossary

New to Storm's terminology? See the [Glossary](glossary.md) for definitions of key terms like Entity, Projection, Metamodel, Ref, Hydration, and more.

## License

Storm is released under the [Apache 2.0 License](https://github.com/storm-orm/storm-framework/blob/main/LICENSE.txt).
