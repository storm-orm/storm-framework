---
name: storm-setup
description: Set up Storm ORM in a project, including Maven or Gradle dependencies, the compiler plugin, KSP, and the metamodel processor. Use when adding Storm to a project or fixing its build configuration.
---

Help the user set up Storm ORM in their project.

**Use Storm's JDBC-based API with a `DataSource`.** Do not add JPA or Hibernate dependencies unless the project already uses them. Storm has its own annotations (`@PK`, `@FK`, `@DbTable`, ...); use those instead of JPA annotations.

**Read the project's build file (pom.xml, build.gradle.kts or build.gradle) before suggesting dependencies.** Detect:
- The build tool: Maven or Gradle.
- The language and version: Kotlin from the kotlin plugin, Java from sourceCompatibility or release.
- Existing dependencies: Spring Boot, Ktor, the database driver, and so on.

Defaults when the project does not specify a version:
- Storm: `@@STORM_VERSION@@`.
- Kotlin: the latest stable release Storm supports, any 2.0.x to 2.4.x. The compiler plugin ships the variants `storm-compiler-plugin-2.0` through `-2.4`.
- Spring Boot: the current stable release. 3.x works with `storm-jackson2`, 4.x with `storm-jackson3`.

KSP versions come in two shapes:
- KSP for Kotlin 2.0 and 2.1 is paired to the Kotlin version as `<kotlin-version>-<ksp-release>`, for example `2.0.21-1.0.28` for Kotlin 2.0.21.
- From KSP 2.3 on, KSP versions independently of Kotlin and covers Kotlin 2.2 and newer, so a bare version such as `2.3.10` is valid.
- On Kotlin 2.2 and newer the `st.orm` plugin supplies KSP itself, so the project needs no KSP line at all.

## Core Dependencies

### Kotlin (Gradle) - Recommended

**Prefer the Storm Gradle plugin (`id("st.orm")`, Gradle 8.5+).** It imports the BOM, adds `storm-kotlin` and `storm-core`, wires the KSP metamodel processor, and selects the compiler-plugin variant for the project's Kotlin version. Apply it alongside the Kotlin plugin:

```kotlin
plugins {
    kotlin("jvm") version "<kotlin-version>"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

**On Kotlin 2.0 and 2.1, add the KSP plugin explicitly, paired to the project's Kotlin version.** On Kotlin 2.2 and newer the `st.orm` plugin applies KSP itself, provided the Kotlin plugin is declared before it.

```kotlin
plugins {
    kotlin("jvm") version "2.0.21"
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

- Where KSP is required and missing, the build fails with the exact line to add. A KSP that does not pair with the project's Kotlin version is refused with the same line.
- In a multi-project build on Kotlin 2.0 or 2.1, declare the paired KSP once in the root project's plugins block with `apply false`, and apply it in each subproject without a version.

**Add only the extra modules the project needs, without versions.** The `st.orm` plugin version drives all Storm coordinates, so no BOM or per-module versions are needed:

```kotlin
dependencies {
    runtimeOnly("st.orm:storm-postgresql")   // your dialect
}
```

The `storm { }` extension covers overrides (`metamodel`, `compilerPlugin`, `compilerPluginVariant`, `javaPreview`).

#### Manual Gradle setup (explicit configuration)

**Apply the BOM to every configuration that needs it.** Use this setup only when the project cannot apply the plugin. A `platform()` BOM applies only to the configuration that declares it, and the `ksp` and `kotlinCompilerPluginClasspath` configurations do NOT inherit it from `implementation`.

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

**Match the compiler plugin suffix to the project's Kotlin version.** 2.0.x uses `storm-compiler-plugin-2.0`, 2.1.x uses `storm-compiler-plugin-2.1`, and so on. The published variants are `-2.0`, `-2.1`, `-2.2`, `-2.3` and `-2.4`, all version-managed by the Storm BOM.

### Kotlin (Maven)
- Import `st.orm:storm-bom` in dependencyManagement
- `st.orm:storm-kotlin`
- `st.orm:storm-core` (runtime scope)
- `st.orm:storm-metamodel-ksp` with a `com.dyescape:kotlin-maven-symbol-processing` execution
- `st.orm:storm-compiler-plugin-<kotlin-major.minor>`, listed under `<dependencies>` of the `kotlin-maven-plugin` configuration
- Use `build-helper-maven-plugin` to add the KSP generated sources directory (`target/generated-sources/ksp`) as a source folder

### Java (Maven)
- Import `st.orm:storm-bom` in dependencyManagement
- `st.orm:storm-java21`
- `st.orm:storm-core` (runtime scope)
- `st.orm:storm-metamodel-processor` (provided scope)
- Requires `--enable-preview` in maven-compiler-plugin and maven-surefire-plugin

### Spring Boot
- Kotlin: `st.orm:storm-kotlin-spring-boot-starter` (replaces `storm-kotlin` + `storm-core`).
- Java: `st.orm:storm-spring-boot-starter` (replaces `storm-java21` + `storm-core`).
- The starters auto-register `ORMTemplate` as a Spring bean, and auto-discover repository interfaces and register them as beans. No configuration is needed.
- Plain `storm-spring` or `storm-kotlin-spring` (without the starter) requires switching scanning on: `@EnableStormRepositories(basePackages = ...)`, or, in multi-template applications, a `RepositoryBeanFactoryPostProcessor(basePackages = ..., ormTemplateBeanName = ..., repositoryPrefix = ...)` bean per repository set.
- Optional: `st.orm:storm-spring-boot-test-autoconfigure` (test scope) for the `@DataStormTest` test slice, the Storm counterpart of `@DataJpaTest`.

### Ktor
- Kotlin: `st.orm:storm-ktor`.
- Optional: `st.orm:storm-ktor-test` (test scope) for the `testStormApplication` DSL.
- Install with `install(Storm)`. Repositories from the compile-time index auto-register and are reached with a bare `repository<T>()` in routes. The `orm`, `entity(User::class)` and `projection(UserView::class)` extensions are available too.
- The plugin exposes the `ORMTemplate` and every auto-registered repository through Ktor's built-in dependency injection (`ktor-server-di`), each under its own interface type: `val users: UserRepository by dependencies`. Koin users bridge the same registry with a few lines of application code; the Ktor integration docs include the recipe.
- Add `com.zaxxer:HikariCP` when using the built-in HOCON-configured DataSource (`storm.datasource.jdbcUrl` etc. in application.conf). It is not needed when passing your own DataSource to `install(Storm)`.
- Run migrations in the plugin's `migration { }` hook, so the default fail-mode schema validation sees the migrated schema.

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
- `st.orm:storm-test` (test scope) provides `@StormTest`, `SqlCapture` and support for the in-memory test database.
- `st.orm:storm-h2` (test runtime scope) is Storm's dialect for the in-memory test database.
- `com.h2database:h2:2.3.232` (test runtime scope) is its JDBC driver. It is not a transitive dependency of `storm-h2` and not version-managed by the Storm BOM, so specify the version explicitly.
- All three are needed. Without the driver, `@StormTest` fails with `No suitable driver found`.
- Key imports: `st.orm.test.StormTest`, `st.orm.test.SqlCapture`, `st.orm.test.CapturedSql.Operation`.
- `@StormTest` injects `ORMTemplate` and `SqlCapture` as test method parameters.
- Schema SQL files go in `src/test/resources/`.

**Verify on the in-memory test database by default.** It runs in milliseconds without Docker. Run a `@StormTest` class on the project's database only when the in-memory database cannot run the SQL or DDL involved, when the user asks, or as a final pass:
- Set `database = ...` on the annotation, with the constant for the project's database from `st.orm.test.TestDatabase`. The same attribute exists on `@DataStormTest`.
- Add that database's Testcontainers 2 module and its JDBC driver in test scope. The module is one of `org.testcontainers:testcontainers-postgresql`, `testcontainers-mysql`, `testcontainers-mariadb`, `testcontainers-mssqlserver` or `testcontainers-oracle-free`.
- Docker must be available where the tests run.
- The container starts once per run and is shared by all test classes. Each class gets a fresh database inside it, so scripts need no drop guards and nothing else in the test changes.
- A container image that requires a license acceptance needs `src/test/resources/container-license-acceptance.txt` with the image name as its line, for example `mcr.microsoft.com/mssql/server:2022-latest`.

**Use Testcontainers 2.** Storm resolves Testcontainers 2 only. Testcontainers versions come from the Spring Boot BOM where there is one:
- The Spring Boot 4 BOM manages Testcontainers 2.
- The Spring Boot 3 BOM manages Testcontainers 1, so raise it: set the `testcontainers.version` property to `2.0.5` with the Spring dependency-management plugin or the Maven starter parent, or add `testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))` with a plain Gradle platform.
- Without a Spring Boot BOM, add `platform("org.testcontainers:testcontainers-bom:2.0.5")`.

**Kotlin/Gradle test dependencies: use the JUnit BOM directly.** Avoid `kotlin("test")`, which can cause dependency conflicts.
```kotlin
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("st.orm:storm-test")
    testRuntimeOnly("st.orm:storm-h2")
    testRuntimeOnly("com.h2database:h2:2.3.232")  // not in Storm BOM, version required
    // Only when a class sets @StormTest(database = ...): the container module and the driver
    // of the project's database, for example:
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.testcontainers:testcontainers-postgresql")
    testRuntimeOnly("org.postgresql:postgresql:42.7.4")
}
```

Database dialects: add the dialect module of the project's database as a runtime dependency, one of:
- `st.orm:storm-postgresql`
- `st.orm:storm-mysql`
- `st.orm:storm-mariadb`
- `st.orm:storm-oracle`
- `st.orm:storm-mssqlserver`
- `st.orm:storm-sqlite`
- `st.orm:storm-h2` (also usable as a runtime dialect, not just for tests)

**Validation on startup:**
- Storm validates the *structure* of all discovered entity types (PK/FK/inline consistency, cyclic references) when the `ORMTemplate` is created, and logs "Successfully validated N Data types for correctness".
- Schema validation against the live database is on by default in the Spring Boot starters and the Ktor plugin. It runs after migrations (after all singletons in Spring, after the `migration { }` hook in Ktor), fails startup on mismatches, and logs "Successfully validated N Data types against the database schema".
- Set `storm.validation.schema_mode` (Spring) or `storm.validation.schemaMode` (Ktor) to `warn` or `none` to relax or opt out, or to `load` to load the schema without validating it.
- Validation loads the schema as it reads it. Storm keeps it for the data source and uses it for advice, such as naming the index a slow statement lacks.
- Loading is optional. `orm.loadSchema()` loads it in code, and each call reads it anew.
- Programmatic use: `validateSchema()` or `validateSchemaOrThrow()`, for example in a `@StormTest`.

After configuring dependencies, remind the user to rebuild so the metamodel classes are generated.

Use the version already in the project's BOM, or `@@STORM_VERSION@@` for new projects.
