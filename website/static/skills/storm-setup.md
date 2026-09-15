---
name: storm-setup
description: Set up Storm ORM in a project, including Maven or Gradle dependencies, the compiler plugin, KSP, and the metamodel processor. Use when adding Storm to a project or fixing its build configuration.
---

Help the user set up Storm ORM in their project.
**Important:** Use Storm's JDBC-based API with `DataSource`. Do not add JPA/Hibernate dependencies unless the project already uses them. Storm has its own annotations (`@PK`, `@FK`, `@DbTable`, etc.) — use those instead of JPA annotations.

Before suggesting dependencies, read the project's build file (pom.xml, build.gradle.kts, or build.gradle) to detect:
- Build tool (Maven or Gradle)
- Language and version (Kotlin version from kotlin plugin, Java version from sourceCompatibility/release)
- Existing dependencies (Spring Boot, Ktor, database driver, etc.)
- If no Storm version is specified in the project, use version `@@STORM_VERSION@@`
- If no Kotlin version is specified in the project, use the latest stable Kotlin release that Storm supports (any 2.0.x–2.4.x — the compiler plugin ships variants `storm-compiler-plugin-2.0` through `-2.4`)
- KSP plugin versions come in two shapes. KSP for Kotlin 2.0 and 2.1 is paired to the Kotlin version, `<kotlin-version>-<ksp-release>` (e.g. `2.0.21-1.0.28` for Kotlin 2.0.21); from KSP 2.3 on it versions independently of Kotlin and covers Kotlin 2.2 and newer, so a bare version such as `2.3.10` is valid there. On Kotlin 2.2 and newer the `st.orm` plugin supplies KSP itself, so the project needs no KSP line at all
- If no Spring Boot version is specified, use the current stable Spring Boot release (3.x works with `storm-jackson2`, 4.x with `storm-jackson3`)

## Core Dependencies

### Kotlin (Gradle) - Recommended

Prefer the Storm Gradle plugin (`id("st.orm")`, Gradle 8.5+). It imports the BOM, adds `storm-kotlin` and `storm-core`, wires the KSP metamodel processor, and selects the Storm compiler-plugin variant matching the project's Kotlin version. Apply it alongside the Kotlin plugin:

```kotlin
plugins {
    kotlin("jvm") version "<kotlin-version>"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

**Important:** On Kotlin 2.2 and newer the `st.orm` plugin applies KSP itself, provided the Kotlin plugin is declared before it. On Kotlin 2.0 and 2.1, add the KSP plugin explicitly, with the version paired to the project's Kotlin version:

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

Where KSP is required and missing, the build fails with the exact line to add; a KSP that does not pair with the project's Kotlin version is refused with the same line. In a multi-project build on Kotlin 2.0 or 2.1, declare the paired KSP once in the root project's plugins block with `apply false` and apply it in each subproject without a version. The `st.orm` plugin version drives all Storm coordinates, so no BOM or per-module versions are needed — add only the extra modules the project needs, without versions:

```kotlin
dependencies {
    runtimeOnly("st.orm:storm-postgresql")   // your dialect
}
```

The `storm { }` extension covers overrides (`metamodel`, `compilerPlugin`, `compilerPluginVariant`, `javaPreview`).

#### Manual Gradle setup (explicit configuration)

If the project cannot apply the plugin, configure the modules by hand. In Gradle, a `platform()` BOM only applies to the configuration where it's declared. The `ksp` and `kotlinCompilerPluginClasspath` configurations are separate — they do NOT inherit the BOM from `implementation`. Apply the BOM to each configuration that needs it:

```kotlin
dependencies {
    implementation(platform("st.orm:storm-bom:<version>"))
    ksp(platform("st.orm:storm-bom:<version>"))
    kotlinCompilerPluginClasspath(platform("st.orm:storm-bom:<version>"))

    implementation("st.orm:storm-kotlin")
    runtimeOnly("st.orm:storm-core")
    ksp("st.orm:storm-metamodel-ksp")                          // version from BOM
    kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-<kotlin-major.minor>")  // version from BOM
}
```

Match the compiler plugin suffix to the project's Kotlin version: 2.0.x uses `storm-compiler-plugin-2.0`, 2.1.x uses `storm-compiler-plugin-2.1`, and so on. Published variants: `-2.0`, `-2.1`, `-2.2`, `-2.3`, `-2.4` (Kotlin 2.0–2.4), all version-managed by the Storm BOM.

### Kotlin (Maven)
- Import `st.orm:storm-bom` in dependencyManagement
- `st.orm:storm-kotlin`
- `st.orm:storm-core` (runtime scope)
- `st.orm:storm-metamodel-ksp` with `com.dyescape:kotlin-maven-symbol-processing` execution
- `st.orm:storm-compiler-plugin-<kotlin-major.minor>` as a dependency of `kotlin-maven-plugin`
- The compiler plugin must be listed under `<dependencies>` of the `kotlin-maven-plugin` configuration
- Use `build-helper-maven-plugin` to add the KSP generated sources directory (`target/generated-sources/ksp`) as a source folder

### Java (Maven)
- Import `st.orm:storm-bom` in dependencyManagement
- `st.orm:storm-java21`
- `st.orm:storm-core` (runtime scope)
- `st.orm:storm-metamodel-processor` (provided scope)
- Requires `--enable-preview` in maven-compiler-plugin and maven-surefire-plugin

### Spring Boot
- Kotlin: `st.orm:storm-kotlin-spring-boot-starter` (replaces `storm-kotlin` + `storm-core`)
- Java: `st.orm:storm-spring-boot-starter` (replaces `storm-java21` + `storm-core`)
- These include auto-configuration: `ORMTemplate` is auto-registered as a Spring bean
- The starters also auto-discover repository interfaces and register them as beans — no configuration needed. Only plain `storm-spring`/`storm-kotlin-spring` (without the starter) requires switching scanning on: `@EnableStormRepositories(basePackages = ...)`, or a `RepositoryBeanFactoryPostProcessor(basePackages = ..., ormTemplateBeanName = ..., repositoryPrefix = ...)` bean per repository set in multi-template applications
- Optionally: `st.orm:storm-spring-boot-test-autoconfigure` (test scope) for the `@DataStormTest` test slice — the Storm counterpart of `@DataJpaTest`

### Ktor
- Kotlin: `st.orm:storm-ktor`
- Optionally: `st.orm:storm-ktor-test` (test scope, for `testStormApplication` DSL)
- The plugin exposes the `ORMTemplate` and every auto-registered repository through Ktor's built-in dependency injection (`ktor-server-di`), each under its own interface type: `val users: UserRepository by dependencies`. Koin users bridge the same registry with a few lines of application code; the Ktor integration docs include the recipe
- Add `com.zaxxer:HikariCP` when using the built-in HOCON-configured DataSource (`storm.datasource.jdbcUrl` etc. in application.conf) — not needed when passing your own DataSource to `install(Storm)`
- Install with `install(Storm)`; repositories from the compile-time index auto-register, accessed via a bare `repository<T>()` in routes; `orm`, `entity<T>()`, and `projection<T>()` extensions are available too
- Run migrations in the plugin's `migration { }` hook so the default fail-mode schema validation sees the migrated schema

## Getting ORMTemplate

```kotlin
// Extension property (most common)
val orm = dataSource.orm

// With custom decorator (e.g., name resolvers)
val orm = dataSource.orm { decorator ->
    decorator.withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
}

// Factory method
val orm = ORMTemplate.of(dataSource)

// Spring Boot: injected automatically
@Service
class UserService(private val orm: ORMTemplate)
```

Serialization (pick one if needed):
- `st.orm:storm-kotlinx-serialization` for kotlinx-serialization
- `st.orm:storm-jackson2` for Jackson 2 (Spring Boot 3.x)
- `st.orm:storm-jackson3` for Jackson 3 (Spring Boot 4.x)

Testing:
- `st.orm:storm-test` (test scope) — provides `@StormTest`, `SqlCapture`, and H2 in-memory database support
- `st.orm:storm-h2` (test runtime scope) — Storm's H2 dialect
- `com.h2database:h2:2.3.232` (test runtime scope) — the H2 JDBC driver itself (required — the driver is not a transitive dependency of `storm-h2`, and H2 is **not** version-managed by the Storm BOM, so specify the version explicitly)
- All three are needed. Without the H2 driver, `@StormTest` fails with `No suitable driver found`.
- Key imports: `st.orm.test.StormTest`, `st.orm.test.SqlCapture`, `st.orm.test.CapturedSql.Operation`
- `@StormTest` injects `ORMTemplate` and `SqlCapture` as test method parameters
- Schema SQL files go in `src/test/resources/`
- H2 is the right default for the verification loop (milliseconds, no Docker). Only when H2 cannot run the SQL or DDL involved, when the user asks, or as a final pass, run a `@StormTest` class on the database the project deploys on: set `database = TestDatabase.POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`; `st.orm.test.TestDatabase`) on the annotation and add that database's Testcontainers 2 module (`org.testcontainers:testcontainers-postgresql`, `testcontainers-mysql`, `testcontainers-mariadb`, `testcontainers-mssqlserver`, `testcontainers-oracle-free`) plus its JDBC driver in test scope. Storm resolves Testcontainers 2 only. The Spring Boot 4 BOM manages it; the Boot 3 BOM manages Testcontainers 1, so a Boot 3 project raises it: `testcontainers.version` property = `2.0.5` with the Spring dependency-management plugin or the Maven starter parent, or `testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))` with a plain Gradle platform. Docker must be available where the tests run. The container starts once per run and is shared by all test classes; each class gets a fresh database inside it, so scripts need no drop guards and nothing else in the test changes. The same attribute exists on `@DataStormTest`. Testcontainers versions come from the Spring Boot BOM; without one, add `platform("org.testcontainers:testcontainers-bom:2.0.5")`. SQL Server additionally needs `src/test/resources/container-license-acceptance.txt` with the line `mcr.microsoft.com/mssql/server:2022-latest`.

**Kotlin/Gradle test dependencies:** Use the JUnit BOM directly — avoid `kotlin("test")` which can cause dependency conflicts:
```kotlin
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("st.orm:storm-test")
    testRuntimeOnly("st.orm:storm-h2")
    testRuntimeOnly("com.h2database:h2:2.3.232")  // not in Storm BOM — version required
    // Only when a class sets @StormTest(database = POSTGRESQL): the container module and the driver
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql:42.7.4")
}
```

Database dialects (add as runtime dependency):
- `st.orm:storm-postgresql`
- `st.orm:storm-mysql`
- `st.orm:storm-mariadb`
- `st.orm:storm-oracle`
- `st.orm:storm-mssqlserver`
- `st.orm:storm-sqlite`
- `st.orm:storm-h2` (also usable as a runtime dialect, not just for tests)

**Validation on startup:** Storm automatically validates the *structure* of all discovered entity types (PK/FK/inline consistency, cyclic references) when the `ORMTemplate` is created, logging "Successfully validated N Data types for correctness". Schema validation (against the live database) is also on by default in the Spring Boot starters and the Ktor plugin: it runs after migrations (after all singletons in Spring; after the `migration { }` hook in Ktor), fails startup on mismatches, and logs "Successfully validated N Data types against the database schema". Set `storm.validation.schema_mode` (Spring) / `storm.validation.schemaMode` (Ktor) to `warn` or `none` to relax or opt out. Programmatic use: `validateSchema()`/`validateSchemaOrThrow()` (e.g. in a `@StormTest`).

After configuring dependencies, remind the user to rebuild so the metamodel classes are generated.

Use the version already in the project's BOM, or `@@STORM_VERSION@@` for new projects.
