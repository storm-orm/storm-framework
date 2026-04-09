# Installation

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

This page covers everything you need to add Storm to your project: prerequisites, dependency setup, and optional modules.

## Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK | 21 or later |
| Kotlin (if using Kotlin) | 2.0 or later |
| Build tool | Maven 3.9+ or Gradle 8+ |
| Database | Any JDBC-compatible database |

Kotlin users do not need any preview flags. Java users must enable `--enable-preview` in their compiler configuration because the Java API uses String Templates (JEP 430).

## Add the BOM

Storm provides a Bill of Materials (BOM) for centralized version management. Import the BOM once, then omit version numbers from individual Storm dependencies. This prevents version mismatches between modules.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))
}
```

</TabItem>
<TabItem value="java" label="Java">

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

</TabItem>
</Tabs>

## Add the Core Dependencies

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))

    implementation("st.orm:storm-kotlin")
    runtimeOnly("st.orm:storm-core")
    ksp("st.orm:storm-metamodel-ksp")
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.0")
}
```

The `storm-metamodel-ksp` dependency generates type-safe metamodel classes (e.g., `User_`, `City_`) at compile time. See [Metamodel](metamodel.md) for details. The `storm-compiler-plugin` automatically wraps string interpolations inside SQL template lambdas, making queries injection-safe by default. The `2.0` suffix matches the Kotlin major.minor version used in your project (e.g., `storm-compiler-plugin-2.1` for Kotlin 2.1.x). See [String Templates](string-templates.md) for details.

</TabItem>
<TabItem value="java" label="Java">

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))

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

**Maven:**

```xml
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
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-metamodel-processor</artifactId>
        <scope>provided</scope>
    </dependency>
</dependencies>
```

Enable preview features for String Templates (JEP 430):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <configuration>
        <release>21</release>
        <compilerArgs>
            <arg>--enable-preview</arg>
        </compilerArgs>
    </configuration>
</plugin>
```

</TabItem>
</Tabs>

The metamodel processor generates type-safe metamodel classes (e.g., `User_`, `City_`) at compile time. See [Metamodel](metamodel.md) for details.

## Optional Modules

Storm is modular. Add only what you need.

### Database Dialects

Storm works with any JDBC-compatible database out of the box. Dialect modules provide database-specific optimizations (e.g., native upsert syntax, tuple comparisons). Add the one that matches your database as a runtime dependency:

| Module | Database |
|--------|----------|
| `storm-oracle` | Oracle |
| `storm-mssqlserver` | SQL Server |
| `storm-postgresql` | PostgreSQL |
| `storm-mysql` | MySQL |
| `storm-mariadb` | MariaDB |
| `storm-sqlite` | SQLite |
| `storm-h2` | H2 |

```kotlin
runtimeOnly("st.orm:storm-postgresql")
```

See [Database Dialects](dialects.md) for what each dialect provides.

### Spring Boot Integration

For Spring Boot applications, use the starter modules instead of the base modules. The starters auto-configure the `ORMTemplate` bean, enable repository scanning, and integrate with Spring's transaction management. See [Spring Integration](spring-integration.md) for full setup details.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
implementation("st.orm:storm-kotlin-spring-boot-starter")
```

</TabItem>
<TabItem value="java" label="Java">

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring-boot-starter</artifactId>
</dependency>
```

</TabItem>
</Tabs>

### Ktor Integration

For Ktor applications, add the Ktor plugin module. It provides a `Storm` plugin that manages the DataSource lifecycle, reads HOCON configuration, and exposes the `ORMTemplate` through extension properties on `Application`, `ApplicationCall`, and `RoutingContext`. See [Ktor Integration](ktor-integration.md) for full setup details.

```kotlin
implementation("st.orm:storm-ktor")
```

For testing:

```kotlin
testImplementation("st.orm:storm-ktor-test")
```

### JSON Support

Storm supports storing and reading JSON-typed columns. Pick the module that matches your serialization library:

| Module | Library |
|--------|---------|
| `storm-jackson2` | Jackson 2.17+ (Spring Boot 3.x) |
| `storm-jackson3` | Jackson 3.0+ (Spring Boot 4+) |
| `storm-kotlinx-serialization` | Kotlinx Serialization |

See [JSON Support](json.md) for usage details.

## Module Overview

The following diagram shows how Storm's modules relate to each other. You only need the modules relevant to your language and integration choices.

```
storm-foundation (base interfaces)
└── storm-kotlin / storm-java21 (your primary dependency)
    ├── storm-kotlin-spring / storm-spring (Spring Framework)
    │   └── storm-kotlin-spring-boot-starter / storm-spring-boot-starter
    ├── storm-ktor (Ktor)
    │   └── storm-ktor-test (testing support)
    ├── dialect modules (postgresql, mysql, mariadb, oracle, mssqlserver, sqlite, h2)
    └── JSON modules (jackson2, jackson3, kotlinx-serialization)
```

## Next Steps

With Storm installed, you are ready to define your first entity and run your first query:

- [First Entity](first-entity.md) -- define an entity, create an ORM template, insert and fetch a record
- [First Query](first-query.md) -- custom queries, repositories, and type-safe filtering
