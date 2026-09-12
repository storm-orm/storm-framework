---
title: Set Up Your Project
sidebar_label: Set Up Your Project
description: "Wire Storm into a project you intend to keep: prerequisites, the four setup routes, and how to verify the result."
---

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Set Up Your Project

This page is about getting Storm into a real project: what it needs from your toolchain, which of the four setup routes fits your situation, and how to prove the wiring works before you write application code.

:::tip Just want to see it work?
The **[Quickstart](/quickstart)** takes about five minutes, needs no database server, and ends with a working query and the SQL it generated. It is the fastest way to judge Storm, and it is the recommended first stop. Come back here when you are setting up a project you intend to keep.
:::

## Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK (Kotlin path) | 21 or later |
| JDK (Java path) | 21 exactly; preview class files are version-locked |
| Kotlin (if using Kotlin) | 2.0 or later |
| Build tool | Maven 3.9+ or Gradle 8+ (Gradle 8.5+ for the Storm plugin) |
| Database | Any JDBC-compatible database |

Kotlin users need no preview flags. Java users must enable `--enable-preview` on compilation, tests, and execution, and must build and run on a JDK 21 toolchain. [Installation](installation.md) covers both in full, including the exact Maven and Gradle configuration.

The JDK 21 pin is a property of the platform, not of Storm: the Java API is built on String Templates (JEP 430), a preview feature, and preview class files only load on the JDK that compiled them. When the JDK ships a stable successor, the Java API drops the preview flags and the version pin and stands alongside Kotlin as a first-class path. [String Templates](string-templates.md#status) covers where that stands today, and which modules are affected (only `storm-java21`; the core framework and the Kotlin API are not).

## Choose a Setup Route

All four routes end at the same place: a project with the Storm dependencies, the metamodel processor, and the Kotlin compiler plugin wired up.

<Tabs>
<TabItem value="gradle" label="Gradle plugin" default>

### Gradle plugin (recommended for Kotlin)

One plugin application imports the BOM, adds the core dependencies, wires the metamodel processor through KSP, selects the compiler-plugin variant matching your Kotlin version, and sets the Java preview flags.

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("com.google.devtools.ksp") version "2.3.10"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

Add the dialect module and JDBC driver for your database, and you are done. See [Installation](installation.md#gradle-plugin-recommended) for the plugin's configuration options and the per-Kotlin-version matrix.

</TabItem>
<TabItem value="maven" label="Maven BOM">

### Maven BOM

Import the BOM once, then declare Storm modules without version numbers. The metamodel annotation processor is added to the compiler plugin's `annotationProcessorPaths`, and the Java path needs `--enable-preview`.

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

See [Installation](installation.md) for the full `pom.xml`, including the processor and preview-flag configuration.

</TabItem>
<TabItem value="template" label="Template repository">

### Start from a template

Each example application is a GitHub template with the build, schema, entities, and tests already wired. Click **Use this template** to generate a repository, then replace the sample entities with your own:

- [Kotlin + Ktor](https://github.com/storm-orm/storm-example-kotlin-ktor/generate)
- [Kotlin + Spring Boot](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4/generate)
- [Java + Spring Boot](https://github.com/storm-orm/storm-example-java-spring-boot-4/generate)

The full set, with what each one demonstrates, is on the [example projects page](/examples/).

</TabItem>
<TabItem value="ai" label="AI-assisted">

### AI-assisted setup

If you work with an AI coding tool (Claude Code, Cursor, GitHub Copilot, Windsurf, or Codex), one command installs Storm's rules and skills for it and can connect it to your development database:

```bash
npx @storm-orm/cli init
```

The tool can then add the dependencies, generate entities from your existing tables, and write repository methods. It has Storm's documentation and, with the MCP server configured, your real schema.

See [AI-Assisted Development](ai.md) for the full setup and [Database and MCP](database-and-mcp.md) for the schema-aware server.

</TabItem>
</Tabs>

## Verify the Wiring

Two checks catch almost every setup mistake before it reaches application code.

**Does the metamodel generate?** After a build, a `User` entity should have a generated `User_` alongside it. If it does not, the annotation processor (Java) or KSP (Kotlin) is not on the compile path. See [Metamodel](metamodel.md) for how generation is configured per build tool.

**Do the entities match the database?** Schema validation compares every mapped entity against the live schema and reports missing tables, missing columns, type mismatches, and nullability disagreements in one pass. `validateSchemaOrThrow()` fails loudly; `validateSchema()` returns the findings as a list so you can assert on them:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
dataSource.orm.validateSchemaOrThrow()
```

</TabItem>
<TabItem value="java" label="Java">

```java
ORMTemplate.of(dataSource).validateSchemaOrThrow();
```

</TabItem>
</Tabs>

Run it at startup in development, or as a test. [Schema Validation](validation.md#schema-validation) covers what is checked, how to scope it to specific entities, and the strict mode.

## Next

With the project wired, work through the model:

1. [First Entity](first-entity.md) -- define entities, insert and fetch records
2. [First Query](first-query.md) -- filtering, repositories, and streaming
3. [Entities](entities.md) -- annotations, nullability, naming conventions

Integrating with a framework instead? Go straight to [Spring Integration](spring-integration.md) or [Ktor Integration](ktor-integration.md), which cover dependency injection, transaction management, and configuration for each.

The full map of the documentation, including paths for migrating from JPA and for evaluating Storm in production, is on the [introduction page](index.md).

Stuck on the setup, or something here did not match what you saw? Ask in [Discord](https://discord.gg/SgQpcweUJD) or open a [discussion](https://github.com/storm-orm/storm-framework/discussions).
