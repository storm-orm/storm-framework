# GraalVM Native Images

Storm applications compile to GraalVM native images out of the box. The framework ships its own
reachability metadata and registers your entities and repositories automatically, so a Storm data
layer needs no reflection configuration, no tracing agent runs, and no hand-written hints.

A native image of the [example movie browser](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4-graalvm)
starts in about a quarter of a second, including the Flyway migration check, the Hikari pool, and
Storm validating every entity against the live database schema.

## Why Storm fits a native image

GraalVM's closed-world analysis is hostile to classic ORMs: runtime bytecode generation,
lazy-loading entity proxies, and reflective entity construction all need extensive metadata, or
simply do not work. Storm avoids those mechanisms by design:

- **Entities are immutable data classes and records.** There are no runtime-generated entity
  proxies and no persistence context weaving.
- **Rows are constructed without reflection.** The metamodel processor generates an instantiator
  per entity at compile time. In a native image these are ordinary compiled classes.
- **Queries are built against the compile-time metamodel**, so query construction needs no runtime
  introspection either.

What little reflection remains, such as the one-time model introspection at startup, is covered by
metadata Storm provides itself.

## How registration works

Storm's metamodel processor already writes a type index to `META-INF/storm/` at compile time. The
native-image support builds on that same index, so it covers exactly the entities, projections,
converters, and repositories of your application without configuration:

- **Spring Boot**: storm-spring contributes Spring AOT hints (registered through `aot.factories`)
  during `processAot`. Repository scanning registers AOT-compatible bean definitions, so scanned
  repositories work in a native image unchanged.
- **Ktor and plain JVM applications**: storm-core ships a GraalVM feature that activates through
  the jar's `native-image.properties` whenever storm-core is on the image classpath. During the
  image build it reports what it registered:

```text
Storm: registered 11 data types, 0 converters, and 11 repositories from the type index.
```

Both paths register the same set of types: every Data type with its constructors, accessors, and
generated metamodel companions, compound primary keys and inline components reached through
constructor signatures, kotlinx.serialization companions for JSON columns, and every repository
interface as a JDK proxy shape.

## Spring Boot

Add the GraalVM build plugin next to the Storm plugin and compile:

```kotlin
plugins {
    id("org.springframework.boot") version "4.1.0"
    id("org.graalvm.buildtools.native") version "0.11.1"
    id("st.orm") version "@@STORM_VERSION@@"
}
```

```bash
GRAALVM_HOME=/path/to/graalvm ./gradlew nativeCompile
./build/native/nativeCompile/your-app
```

Spring Boot 4 writes its AOT metadata in the consolidated `reachability-metadata.json` format,
which requires GraalVM for JDK 23 or newer.

The [Kotlin + Spring Boot 4 example](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4-graalvm)
is the complete movie browser compiled this way: repository scanning, suspend transactions, the
full Thymeleaf UI, and the streaming dataset import all run natively, verified by the project's
Playwright test suite against the native binary.

## Ktor

The same application code runs natively on Ktor; Storm's GraalVM feature takes the place of the
Spring AOT hints, and the Ktor plugin's repository auto-registration reads the same type index at
runtime. Two Ktor-specific notes, both unrelated to Storm:

- Use the CIO engine; it is Ktor's recommended engine for native images.
- Native images cannot scan the classpath, so hand Flyway its migration files through a
  `ResourceProvider` instead of relying on location scanning.

The [Kotlin + Ktor example](https://github.com/storm-orm/storm-example-kotlin-ktor-graalvm) shows
both, along with the small amount of application-level metadata a Ktor native image needs for the
web stack itself (the `EngineMain` module reference, template-facing types, and the ktor-network
socket classes).

## What remains application-level

Storm covers the data layer. Your application's own stack keeps its usual native-image
requirements: template engines evaluate expressions reflectively against your view models, JSON
serialization resolves serializers for your response types, and libraries without shipped
metadata may need an entry or two. The example projects keep all of this in one commented
metadata directory, small enough to read in a minute:

- [Spring Boot example metadata](https://github.com/storm-orm/storm-example-kotlin-spring-boot-4-graalvm/blob/main/src/main/kotlin/st/orm/demo/imdb/ApplicationRuntimeHints.kt)
- [Ktor example metadata](https://github.com/storm-orm/storm-example-kotlin-ktor-graalvm/tree/main/src/main/resources/META-INF/native-image)

## What you get

Both examples compile to a self-contained native binary that starts in a fraction of a second,
with no JVM and no warmup, and both are verified by their Playwright interface suites running
against the native binaries. A batch import can even finish faster natively than on the JVM: a
one-shot job never runs long enough to earn back the JIT warmup it would need to catch up.
