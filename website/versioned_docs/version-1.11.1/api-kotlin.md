---
title: Kotlin API Reference
---

# Kotlin API Reference

Storm's Kotlin API is organized into a set of focused modules. Each module has a specific role, from the core ORM engine with coroutine support to Spring Boot auto-configuration and validation. This page provides an overview of the module structure and links to detailed documentation for each concept.

## Module Overview

### storm-kotlin

The main Kotlin API module. It provides the `ORMTemplate` interface, extension functions (`DataSource.orm`, `Connection.orm`), repository interfaces, coroutine support, and the type-safe query DSL. This is the primary dependency for Kotlin applications.

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-kotlin:@@STORM_VERSION@@")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-kotlin</artifactId>
    <version>@@STORM_VERSION@@</version>
</dependency>
```

The Kotlin API does not depend on any preview features. All APIs are stable and production-ready.

### storm-kotlin-spring

Spring Framework integration for Kotlin. Provides `RepositoryBeanFactoryPostProcessor` for repository auto-discovery and injection, `@EnableTransactionIntegration` for bridging Storm's programmatic transactions with Spring's `@Transactional`, and transaction-aware coroutine support. Add this module when you use Spring Framework without Spring Boot.

```kotlin
implementation("st.orm:storm-kotlin-spring:@@STORM_VERSION@@")
```

See [Spring Integration](spring-integration.md) for configuration details.

### storm-kotlin-spring-boot-starter

Spring Boot auto-configuration for Kotlin. Automatically creates an `ORMTemplate` bean from the `DataSource`, discovers repositories, enables transaction integration, and binds `storm.*` properties from `application.yml`. This is the recommended dependency for Spring Boot applications.

```kotlin
implementation("st.orm:storm-kotlin-spring-boot-starter:@@STORM_VERSION@@")
```

See [Spring Integration: Spring Boot Starter](spring-integration.md#spring-boot-starter) for what the starter provides and how to override its defaults.

## Key Classes and Functions

| Class/Function | Description | Guide |
|----------------|-------------|-------|
| `ORMTemplate` | The central entry point. Create with `dataSource.orm` or `ORMTemplate.of(dataSource)`. Provides access to entity/projection repositories and the SQL template query engine. | [Getting Started](getting-started.md) |
| `EntityRepository<E, ID>` | Type-safe repository interface for CRUD operations on entities. Extend this interface and add custom query methods with default method bodies. | [Repositories](repositories.md) |
| `ProjectionRepository<P, ID>` | Read-only repository for projections (subset of entity columns). | [Projections](projections.md) |
| `Entity<ID>` | Marker interface for entity data classes. Implement this on your Kotlin data classes to enable repository operations. | [Entities](entities.md) |
| `Projection<ID>` | Marker interface for projection data classes. | [Projections](projections.md) |
| `DataSource.orm` | Extension property that creates an `ORMTemplate` from a `DataSource`. | [Getting Started](getting-started.md) |
| `transaction { }` | Coroutine-aware programmatic transaction block. | [Transactions](transactions.md) |
| `transactionBlocking { }` | Blocking variant of the programmatic transaction block. | [Transactions](transactions.md) |
| `StormConfig` | Immutable configuration holder. Pass to `dataSource.orm(config)` to override defaults. | [Configuration](configuration.md) |

## Coroutine Support

Storm's Kotlin API provides first-class coroutine support. Query results can be consumed as `Flow<T>` for streaming, and the `transaction { }` block is a suspending function that integrates with structured concurrency. Storm leverages JVM virtual threads under the hood, so database operations do not block platform threads even when using JDBC (which is inherently synchronous).

```kotlin
// Streaming with Flow
val users: Flow<User> = orm.entity(User::class).selectAll()
users.collect { processUser(it) }

// Suspending transaction
transaction {
    orm insert User(name = "Alice")
}
```

## Metamodel Generation

The metamodel generates type-safe companion classes (e.g., `User_`) at compile time. These classes provide static references to entity fields for use in the query DSL, enabling compile-time checked queries.

There are two ways to configure metamodel generation for Kotlin projects, depending on your build tool:

- **Gradle with KSP:** Use `storm-metamodel-ksp`, which is a Kotlin Symbol Processing plugin.
- **Maven with kapt:** Use `storm-metamodel-processor`, which is a standard Java annotation processor invoked through kapt.

Both generate the same metamodel classes; they are different build tool integrations.

**Gradle (Kotlin DSL) with KSP:**

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

dependencies {
    ksp("st.orm:storm-metamodel-ksp:@@STORM_VERSION@@")
}
```

**Maven with kapt:**

```xml
<plugin>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-maven-plugin</artifactId>
    <executions>
        <execution>
            <id>kapt</id>
            <goals><goal>kapt</goal></goals>
            <configuration>
                <annotationProcessorPaths>
                    <path>
                        <groupId>st.orm</groupId>
                        <artifactId>storm-metamodel-processor</artifactId>
                        <version>@@STORM_VERSION@@</version>
                    </path>
                </annotationProcessorPaths>
            </configuration>
        </execution>
    </executions>
</plugin>
```

See [Metamodel](metamodel.md) for setup and usage.

## KDoc

KDoc is generated per module using Dokka. Select a module below to browse its API documentation.

| Module | Description |
|--------|-------------|
| [storm-kotlin](../api/kotlin/storm-kotlin/index.html) | Kotlin API with coroutine support |
| [storm-kotlin-spring](../api/kotlin/storm-kotlin-spring/index.html) | Spring Framework integration for Kotlin |
| [storm-kotlin-spring-boot-starter](../api/kotlin/storm-kotlin-spring-boot-starter/index.html) | Spring Boot auto-configuration for Kotlin |
| [storm-metamodel-ksp](../api/kotlin/storm-metamodel-ksp/index.html) | Kotlin Symbol Processing for metamodel generation |
| [storm-kotlinx-serialization](../api/kotlin/storm-kotlinx-serialization/index.html) | Kotlinx Serialization support |
