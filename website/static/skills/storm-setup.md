Help the user set up Storm ORM in their project.
**Important:** Use Storm's JDBC-based API with `DataSource`. Do not add JPA/Hibernate dependencies unless the project already uses them. Storm has its own annotations (`@PK`, `@FK`, `@DbTable`, etc.) — use those instead of JPA annotations.

Before suggesting dependencies, read the project's build file (pom.xml, build.gradle.kts, or build.gradle) to detect:
- Build tool (Maven or Gradle)
- Language and version (Kotlin version from kotlin plugin, Java version from sourceCompatibility/release)
- Existing dependencies (Spring Boot, Ktor, database driver, etc.)
- If no Storm version is specified in the project, use version `@@STORM_VERSION@@`
- If no Kotlin version is specified in the project, use Kotlin `2.3.20` (the current stable release)
- If no KSP version is specified in the project, use KSP `2.3.6` (the current stable release)
- If no Spring Boot version is specified, use Spring Boot `4.0.5` (the current stable release)

## Core Dependencies

### Kotlin (Gradle) - Recommended

**Important:** The KSP plugin version must match the project's Kotlin version. Declare it in `plugins { }`:
```kotlin
plugins {
    kotlin("jvm") version "<kotlin-version>"
    id("com.google.devtools.ksp") version "<kotlin-version>-<ksp-patch>"  // e.g., 2.3.6
}
```

In Gradle, a `platform()` BOM only applies to the configuration where it's declared. The `ksp` and `kotlinCompilerPluginClasspath` configurations are separate — they do NOT inherit the BOM from `implementation`. You must apply the BOM to each configuration that needs it:

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

Match the compiler plugin suffix to the project's Kotlin version: 2.0.x uses `storm-compiler-plugin-2.0`, 2.1.x uses `storm-compiler-plugin-2.1`, etc.

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
- Repositories are discoverable via `RepositoryBeanFactoryPostProcessor` with `repositoryBasePackages`

### Ktor
- Kotlin: `st.orm:storm-ktor`
- Optionally: `st.orm:storm-ktor-test` (test scope, for `testStormApplication` DSL)
- Requires `com.zaxxer:HikariCP` for connection pooling (unless providing your own DataSource)
- Install with `install(Storm)`, access ORM via `call.orm` in routes
- Register repositories via `stormRepositories { register(UserRepository::class) }`

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
- `com.h2database:h2:2.3.232` (test runtime scope) — the H2 JDBC driver itself (required — `storm-h2` declares it as `provided`, and H2 is **not** version-managed by the Storm BOM, so specify the version explicitly)
- All three are needed. Without the H2 driver, `@StormTest` fails with `No suitable driver found`.
- Key imports: `st.orm.test.StormTest`, `st.orm.test.SqlCapture`, `st.orm.test.CapturedSql.Operation`
- `@StormTest` injects `ORMTemplate` and `SqlCapture` as test method parameters
- Schema SQL files go in `src/test/resources/`

**Kotlin/Gradle test dependencies:** Use the JUnit BOM directly — avoid `kotlin("test")` which can cause dependency conflicts:
```kotlin
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("st.orm:storm-test")
    testRuntimeOnly("st.orm:storm-h2")
    testRuntimeOnly("com.h2database:h2:2.3.232")  // not in Storm BOM — version required
}
```

Database dialects (add as runtime dependency):
- `st.orm:storm-postgresql`
- `st.orm:storm-mysql`
- `st.orm:storm-mariadb`
- `st.orm:storm-oracle`
- `st.orm:storm-mssqlserver`

After configuring dependencies, remind the user to rebuild so the metamodel classes are generated.

Use the version already in the project's BOM, or `@@STORM_VERSION@@` for new projects.
