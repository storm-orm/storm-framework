Help the user set up Storm ORM in their project.

Fetch https://orm.st/llms-full.txt for complete reference (see the Installation section).

Before suggesting dependencies, read the project's build file (pom.xml, build.gradle.kts, or build.gradle) to detect:
- Build tool (Maven or Gradle)
- Language and version (Kotlin version from kotlin plugin, Java version from sourceCompatibility/release)
- Existing dependencies (Spring Boot, Ktor, database driver, etc.)
- Fetch the latest Storm version from https://repo1.maven.org/maven2/st/orm/storm-bom/maven-metadata.xml

Core dependencies:

Kotlin (Gradle):
- `implementation(platform("st.orm:storm-bom:<version>"))`
- `implementation("st.orm:storm-kotlin")`
- `runtimeOnly("st.orm:storm-core")`
- `ksp("st.orm:storm-metamodel-ksp")`
- `kotlinCompilerPluginClasspath("st.orm:storm-compiler-plugin-<kotlin-major.minor>")`
  Match the suffix to the project's Kotlin version: 2.0.x uses storm-compiler-plugin-2.0, 2.1.x uses storm-compiler-plugin-2.1, etc.

Java (Maven):
- Import `st.orm:storm-bom` in dependencyManagement
- `st.orm:storm-java21`
- `st.orm:storm-core` (runtime scope)
- `st.orm:storm-metamodel-processor` (provided scope)
- Requires `--enable-preview` in maven-compiler-plugin and maven-surefire-plugin

Spring Boot:
- Kotlin: `st.orm:storm-kotlin-spring-boot-starter`
- Java: `st.orm:storm-spring-boot-starter`
- These replace the core module and include auto-configuration
- See https://orm.st/docs/spring-integration for full setup

Ktor:
- Kotlin: `st.orm:storm-ktor`
- Optionally: `st.orm:storm-ktor-test` (test scope, for `testStormApplication` DSL)
- Requires `com.zaxxer:HikariCP` for connection pooling (unless providing your own DataSource)
- See https://orm.st/docs/ktor-integration for full setup

Serialization (pick one if needed):
- `st.orm:storm-kotlinx-serialization` for kotlinx-serialization
- `st.orm:storm-jackson2` for Jackson 2 (Spring Boot 3.x)
- `st.orm:storm-jackson3` for Jackson 3 (Spring Boot 4+)

Database dialects (add as runtime dependency):
- `st.orm:storm-postgresql`
- `st.orm:storm-mysql`
- `st.orm:storm-mariadb`
- `st.orm:storm-oracle`
- `st.orm:storm-mssqlserver`

After configuring dependencies, remind the user to rebuild so the metamodel classes are generated.

Do not hardcode versions. Always fetch the latest from Maven Central or use the version already in the project's BOM.
