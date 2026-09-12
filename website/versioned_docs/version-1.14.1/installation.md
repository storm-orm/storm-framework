# Installation

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

This page covers everything you need to add Storm to your project: prerequisites, dependency setup, and optional modules.

## Prerequisites

| Requirement | Version |
|-------------|---------|
| JDK (Kotlin path) | 21 or later |
| JDK (Java path) | 21 exactly; preview class files are version-locked |
| Kotlin (if using Kotlin) | 2.0 or later |
| Build tool | Maven 3.9+ or Gradle 8+ |
| Database | Any JDBC-compatible database |

Kotlin users do not need any preview flags. Java users must enable `--enable-preview` on compilation, tests, and execution because the Java API uses String Templates (JEP 430), and must build and run on a JDK 21 toolchain: preview class files only load on the JDK that compiled them.

The pin is a property of the platform rather than of Storm, and it is not permanent. When the JDK ships a stable successor to String Templates, the Java API drops both the preview flags and the version pin and stands alongside Kotlin as a first-class path. Only `storm-java21` depends on the preview feature; the core framework and the Kotlin API are unaffected. See [String Templates](string-templates.md#status) for the current state.

## Gradle Plugin (Recommended)

The Storm Gradle plugin collapses the whole setup into one plugin application. It imports the BOM, adds the core dependencies for your language, wires the metamodel processor, selects the Kotlin compiler-plugin variant matching your Kotlin version, and configures the Java preview flags. Requires Gradle 8.5+.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

That is the entire Storm setup: on Kotlin 2.3 and newer the plugin applies KSP itself, and a KSP version you apply in the plugins block always takes precedence. Kotlin 2.2 and older pair with their own KSP builds, so add the `com.google.devtools.ksp` plugin there yourself; when it is missing, the build fails with the exact line to add.

</TabItem>
<TabItem value="java" label="Java">

```kotlin
plugins {
    java
    id("st.orm") version "@@STORM_VERSION@@"
}
```

That is the entire Storm setup, including the `--enable-preview` flags on compilation, tests, execution, and javadoc generation that storm-java21's String Templates (JEP 430) require. Use a JDK 21 toolchain: preview class files are version-locked, so storm-java21 runs on JDK 21 exactly.

</TabItem>
</Tabs>

The plugin configures, per language path:

| | Kotlin | Java |
|---|--------|------|
| BOM | `storm-bom` imported as a platform | `storm-bom` imported as a platform |
| API | `storm-kotlin` | `storm-java21` |
| Engine | `storm-core` (runtime only) | `storm-core` (runtime only) |
| Metamodel | `storm-metamodel-ksp` on every source set's KSP configuration (`ksp`, `kspTest`, ...) | `storm-metamodel-processor` on every source set's processor configuration (`annotationProcessor`, `testAnnotationProcessor`, ...) |
| Compiler plugin | `storm-compiler-plugin-<variant>` matching the Kotlin version | — |
| Compiler flags | — | `--enable-preview` on compile, test, exec, and javadoc tasks |

All Storm coordinates use the plugin's own version; the plugin and the artifacts are released together. Because the BOM is imported, optional modules stay version-less: `runtimeOnly("st.orm:storm-postgresql")` just works.

The `storm { }` extension covers the cases where the defaults do not fit:

```kotlin
storm {
    metamodel.set(true)               // metamodel generation (default true)
    compilerPlugin.set(true)          // Kotlin: Storm compiler plugin (default true)
    compilerPluginVariant.set("2.4")  // pin the variant, e.g. for a newer Kotlin than the plugin knows
    javaPreview.set(true)             // Java: --enable-preview flags (default true)
}
```

In mixed Kotlin/Java projects the Kotlin path wins: KSP processes Java declarations too. If you specifically need the Java annotation processor as well, add `annotationProcessor("st.orm:storm-metamodel-processor")` manually.

The automatic KSP application can be disabled with the Gradle property `storm.autoApplyKsp=false`; the plugin then expects KSP in your plugins block, exactly as on older Kotlin versions.

Maven users and Gradle users who prefer explicit configuration continue with the manual setup below.

## Add the BOM

Storm provides a Bill of Materials (BOM) for centralized version management. Import the BOM once, then omit version numbers from individual Storm dependencies. This prevents version mismatches between modules.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))
}
```

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

</TabItem>
<TabItem value="java" label="Java">

**Gradle (Kotlin DSL):**

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))
}
```

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

</TabItem>
</Tabs>

## Add the Core Dependencies

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

**Gradle (Kotlin DSL):**

```kotlin
plugins {
    kotlin("jvm") version "2.4.0"
    id("com.google.devtools.ksp") version "2.3.10"
}

dependencies {
    implementation(platform("st.orm:storm-bom:@@STORM_VERSION@@"))

    implementation("st.orm:storm-kotlin")
    runtimeOnly("st.orm:storm-core")
    ksp("st.orm:storm-metamodel-ksp")
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-2.4")
}
```

The `storm-metamodel-ksp` dependency generates type-safe metamodel classes (e.g., `User_`, `City_`) at compile time. See [Metamodel](metamodel.md) for details. The `storm-compiler-plugin` automatically wraps string interpolations inside SQL template lambdas, making queries injection-safe by default. The suffix matches the Kotlin major.minor version used in your project: `storm-compiler-plugin-2.4` for Kotlin 2.4.x, `storm-compiler-plugin-2.1` for Kotlin 2.1.x, and so on. See [String Templates](string-templates.md) for details.

**Maven:**

```xml
<dependencies>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-kotlin</artifactId>
    </dependency>
    <dependency>
        <groupId>st.orm</groupId>
        <artifactId>storm-core</artifactId>
        <scope>runtime</scope>
    </dependency>
</dependencies>
```

Configure the Kotlin compiler with the Storm compiler plugin on its classpath; the plugin registers itself:

```xml
<plugin>
    <groupId>org.jetbrains.kotlin</groupId>
    <artifactId>kotlin-maven-plugin</artifactId>
    <version>${kotlin.version}</version>
    <configuration>
        <jvmTarget>21</jvmTarget>
    </configuration>
    <dependencies>
        <dependency>
            <groupId>st.orm</groupId>
            <artifactId>storm-compiler-plugin-2.4</artifactId>
            <version>@@STORM_VERSION@@</version>
        </dependency>
    </dependencies>
</plugin>
```

As in the Gradle setup, the compiler-plugin suffix follows your Kotlin major.minor version. Metamodel generation runs through KSP, which ships first-party build integration for Gradle only; Maven projects wire KSP through a community extension such as [kotlin-maven-symbol-processing](https://github.com/Dyescape/kotlin-maven-symbol-processing), with `st.orm:storm-metamodel-ksp` as the processor. The KClass-based query API works without generated metamodels.

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

Tests run in a forked JVM that needs the flag as well; without it, every test run fails at class load. Add it to surefire (and to failsafe, if you run integration tests):

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-surefire-plugin</artifactId>
    <configuration>
        <argLine>--enable-preview</argLine>
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

### Observability

| Module | Provides |
|--------|----------|
| `storm-micrometer` | Micrometer Observations for queries and transactions (`storm.query`, `storm.transaction`), the OpenTelemetry database semantic conventions, and trace-context SQL comments |

The Spring Boot starters include `storm-micrometer`; Ktor applications add it explicitly. See the observability sections of [Spring Integration](spring-integration.md#observability) and [Ktor Integration](ktor-integration.md#observability).

### Testing

| Module | Provides |
|--------|----------|
| `storm-test` | `@StormTest` JUnit 5 extension and `SqlCapture`, framework-free; runs on H2 or, with the database's Testcontainers module added, on PostgreSQL, MySQL, MariaDB, SQL Server or Oracle |
| `storm-kotlin-test` | The suspending `recording` extension, carrying `SqlCapture` across coroutines (test scope) |
| `storm-spring-boot-test-autoconfigure` | The `@DataStormTest` Spring Boot test slice (test scope) |

See [Testing](testing.md) and [Testing with @DataStormTest](spring-integration.md#testing-with-datastormtest).

## One Per Classpath

Four module pairs ship the same classes under the same fully qualified names, one half per language stack, Jackson major version, or annotation-processing toolchain. Every setup on this page selects exactly one half of each pair; this section states the rule once, because the failure mode is confusing when both halves land on one classpath through a transitive dependency. On the module path, the JVM refuses to start with a `LayerInstantiationException` for the duplicated package exports. On the plain classpath, whichever jar comes first wins, and the application fails at first use with a `ClassCastException` or `NoSuchMethodError` that points nowhere near the cause.

| Pick one of | Shared classes | Choose by |
|-------------|----------------|-----------|
| `storm-java21` / `storm-kotlin` | `st.orm.template`, `st.orm.repository` | Project language |
| `storm-spring-boot-starter` / `storm-kotlin-spring-boot-starter` | `st.orm.spring.boot.autoconfigure` | Project language |
| `storm-jackson2` / `storm-jackson3` | `st.orm.jackson` | Jackson major version |
| `storm-metamodel-processor` / `storm-metamodel-ksp` | `st.orm.metamodel` and the generated metamodel | Annotation processing toolchain: javac or KSP |

A mixed Kotlin/Java application picks one language stack and stays on it; the Gradle plugin picks the Kotlin stack. The metamodel processors are the one pair that can coexist in a mixed build, because each lives on its own processor path (`annotationProcessor` and `ksp`) rather than on the application classpath.

Storm's own artifacts never introduce a twin transitively: each half of each pair fails Storm's build when its dependency tree contains the other half. If both halves end up on your classpath, your own dependency declarations or a third-party library brought the second one; remove or exclude it.

## Module Overview

The following diagram shows how Storm's modules relate to each other. You only need the modules relevant to your language and integration choices.

```
storm-foundation (base interfaces)
└── storm-kotlin / storm-java21 (your primary dependency)
    ├── storm-kotlin-spring / storm-spring (Spring Framework)
    │   └── storm-kotlin-spring-boot-starter / storm-spring-boot-starter
    ├── storm-ktor (Ktor)
    │   └── storm-ktor-test (testing support)
    ├── storm-kotlin-test (coroutine-aware SqlCapture, testing support)
    ├── dialect modules (postgresql, mysql, mariadb, oracle, mssqlserver, sqlite, h2)
    └── JSON modules (jackson2, jackson3, kotlinx-serialization)
```

## Next Steps

With Storm installed, you are ready to define your first entity and run your first query:

- [First Entity](first-entity.md) -- define an entity, create an ORM template, insert and fetch a record
- [First Query](first-query.md) -- custom queries, repositories, and type-safe filtering
