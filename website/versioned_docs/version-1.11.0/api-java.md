---
title: Java API Reference
---

# Java API Reference

Storm's Java API is organized into a set of focused modules. Each module has a specific role, from the core ORM engine to Spring Boot auto-configuration. This page provides an overview of the module structure and links to detailed documentation for each concept.

## Module Overview

### storm-java21

The main Java API module. It provides the `ORMTemplate` entry point, repository interfaces, SQL Templates using Java's String Templates (preview feature), and the type-safe query DSL. This is the primary dependency for Java applications.

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-java21</artifactId>
    <version>1.11.0</version>
</dependency>
```

**String Templates (Preview Feature):** The Java API uses JDK String Templates for SQL construction. String Templates are a preview feature in Java 21+, which means you must compile with `--enable-preview` and run with `--enable-preview`. The preview status means the syntax may change in future JDK releases, and Storm's Java API surface will adapt accordingly. The Kotlin API does not depend on any preview features and is fully stable.

To enable preview features in Maven:

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

### storm-spring

Spring Framework integration for Java. Provides `RepositoryBeanFactoryPostProcessor` for repository auto-discovery and injection, plus transaction integration. Add this module when you use Spring Framework without Spring Boot.

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring</artifactId>
    <version>1.11.0</version>
</dependency>
```

See [Spring Integration](spring-integration.md) for configuration details.

### storm-spring-boot-starter

Spring Boot auto-configuration for Java. Automatically creates an `ORMTemplate` bean from the `DataSource`, discovers repositories, and binds `storm.*` properties from `application.yml`. This is the recommended dependency for Spring Boot applications.

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring-boot-starter</artifactId>
    <version>1.11.0</version>
</dependency>
```

See [Spring Integration: Spring Boot Starter](spring-integration.md#spring-boot-starter) for what the starter provides and how to override its defaults.

## Key Classes

| Class | Description | Guide |
|-------|-------------|-------|
| `ORMTemplate` | The central entry point. Create with `ORMTemplate.of(dataSource)`. Provides access to entity/projection repositories and the SQL template query engine. | [Getting Started](getting-started.md) |
| `EntityRepository<E, ID>` | Type-safe repository interface for CRUD operations on entities. Extend this interface and add custom query methods with default method bodies. | [Repositories](repositories.md) |
| `ProjectionRepository<P, ID>` | Read-only repository for projections (subset of entity columns). | [Projections](projections.md) |
| `Entity<ID>` | Marker interface for entity records. Implement this on your Java records to enable repository operations. | [Entities](entities.md) |
| `Projection<ID>` | Marker interface for projection records. | [Projections](projections.md) |
| `StormConfig` | Immutable configuration holder. Pass to `ORMTemplate.of()` to override defaults. | [Configuration](configuration.md) |

## Metamodel Generation

The `storm-metamodel-processor` annotation processor generates type-safe metamodel classes (e.g., `User_`) at compile time. These classes provide static references to entity fields for use in the query DSL, enabling compile-time checked queries.

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-metamodel-processor</artifactId>
    <version>1.11.0</version>
    <scope>provided</scope>
</dependency>
```

See [Metamodel](metamodel.md) for setup and usage.

## Javadoc

The aggregated Javadoc covers all Java modules in the Storm framework:

[Browse the Javadoc](../api/java/index.html)
