---
name: storm-serialization-kotlin
description: Serialize Storm entities to JSON for REST APIs with Jackson or kotlinx.serialization, including Ref fields. Use for API responses, caching, and messaging, not for JSON database columns.
---

Help the user serialize Storm entities to JSON for REST APIs using Kotlin.
This is about serializing entities for API responses or caching (Jackson, kotlinx.serialization), not about JSON database columns (use /storm-json-kotlin for that).

Ask: which serialization library (Jackson or kotlinx.serialization), whether entities have `Ref<T>` fields.

**Prefer kotlinx.serialization for Kotlin.** It is compile-time checked and Kotlin-native, paired with `st.orm:storm-kotlinx-serialization` (add the `kotlin("plugin.serialization")` plugin and `kotlinx-serialization-json`).
- Use Jackson (`storm-jackson2` for Spring Boot 3.x, `storm-jackson3` for Spring Boot 4.x) when the project already standardizes on it.
- Spring MVC serializes `@RestController` responses with Jackson by default, so the two often coexist: Jackson for HTTP responses, kotlinx.serialization for caching and messaging.

**Detect the project's framework from its build file** (pom.xml or build.gradle.kts): `storm-kotlin-spring-boot-starter` or `spring-boot-starter` means Spring Boot, `storm-ktor` or `ktor-server-core` means Ktor, neither means standalone. Adapt setup examples to the detected framework.

## When You Need the Storm Module

**Entities without `Ref` fields serialize with plain Jackson or kotlinx.serialization.** No Storm module is needed.

**Entities with `Ref<T>` fields require the Storm serialization module.** A `Ref` can be unloaded (only the FK ID) or loaded (full entity/projection), and standard libraries cannot handle this distinction without help.

## Jackson Setup

Register `StormModule` on the `ObjectMapper`:
```kotlin
val mapper = ObjectMapper()
mapper.registerModule(StormModule())
```

Spring Boot auto-detects Module beans:
```kotlin
@Configuration
class JacksonConfig {
    @Bean
    fun stormModule(): StormModule = StormModule()
}
```

Ktor Content Negotiation with Jackson:
```kotlin
install(ContentNegotiation) {
    jackson {
        registerModule(StormModule())
    }
}
```

Ktor Content Negotiation with kotlinx.serialization:
```kotlin
install(ContentNegotiation) {
    json(Json {
        serializersModule = StormSerializersModule()
    })
}
```

`StormModule` is in `st.orm.jackson`, available in both `storm-jackson2` and `storm-jackson3`.

## Kotlinx Serialization Setup

`StormSerializersModule()` and the pre-built `StormSerializers` instance live in package `st.orm.serialization` (import `st.orm.serialization.StormSerializersModule` / `st.orm.serialization.StormSerializers`):

```kotlin
val json = Json {
    serializersModule = StormSerializersModule()
}
```

Or use the pre-built convenience instance:
```kotlin
val json = Json {
    serializersModule = StormSerializers
}
```

**Annotate every `Ref` field in a `@Serializable` class with `@Contextual`.** Without it, the kotlinx compiler plugin tries to serialize `Ref` directly and fails at runtime with "Class 'RefImpl' is not registered for polymorphic serialization in the scope of 'Ref'".
```kotlin
@Serializable
data class User(
    @PK val id: Int = 0,
    val name: String,
    @FK @Contextual val city: Ref<City>?
) : Entity<Int>
```

For collections of refs, annotate both the field and the type argument:
```kotlin
@Serializable
data class TeamMembers(
    @Contextual val members: List<@Contextual Ref<User>>
)
```

## Kotlinx Serialization Cascade Rule

**When an entity is `@Serializable`, every entity reachable from it must be `@Serializable` too.** This includes entities referenced via `@FK` fields, both direct entity fields and `Ref<T>` fields. For `Ref<T>`, the target type `T` must be `@Serializable`; `StormSerializers` handles the `Ref` wrapper itself.

```kotlin
@Serializable
data class Address(
    @PK val id: Int = 0,
    @FK val user: User,                      // User must be @Serializable
    @FK @Contextual val city: Ref<City>?,    // City must be @Serializable
) : Entity<Int>
```

### Java time types

**Give `java.time` fields a custom serializer.** Kotlinx.serialization does not support `Instant`, `LocalDate`, `LocalTime` or similar types out of the box:

```kotlin
@Serializable
data class Event(
    @PK val id: Int = 0,
    @Serializable(with = InstantAsStringSerializer::class) val timestamp: Instant,
    @Serializable(with = LocalDateAsStringSerializer::class) val eventDate: LocalDate,
) : Entity<Int>
```

This does not apply to Jackson, which supports `java.time` natively (with the `jackson-datatype-jsr310` module, included by Spring Boot).

## Caching (Redis, etc.)

**Configure an external-cache serializer with `StormSerializers`.** Entities cached in external stores like Redis need full serialization support. With kotlinx.serialization and Redis (e.g., `KotlinxSerializationRedisSerializer`), `StormSerializers` handles `Ref<T>`:

```kotlin
val serializer = KotlinxSerializationRedisSerializer(
    Json { serializersModule = StormSerializers }
)
```

The cascade rule and the `@Contextual` rule apply to every cached entity.

**Spring cache annotations: back the cache with serialized values, not object references.** With `@EnableCaching` + `@Cacheable` on the service method, use, for example, a `ConcurrentMapCache` subclass whose `toStoreValue`/`fromStoreValue` encode and decode JSON with kotlinx.serialization, or Redis as above. Serialized JSON mirrors production external caches and proves the entities round-trip losslessly (immutable data classes decode value-equal). Two caveats:
- `@Cacheable` does not support suspend functions without the Reactor bridge (`kotlinx-coroutines-reactor`). Make the cached method non-suspend and open its transaction with `transactionBlocking { }`.
- kotlinx.serialization has no built-in `BigDecimal` or `java.time` support, so such fields need custom serializers.

## Ref Serialization Format

| Ref state | JSON output | Example |
|-----------|-------------|---------|
| Unloaded | Raw primary key | `1` |
| Loaded entity | `{"@entity": {...}}` | `{"@entity": {"id": 1, "name": "Betty"}}` |
| Loaded projection | `{"@id": ..., "@projection": {...}}` | `{"@id": 1, "@projection": {"id": 1, "name": "Betty"}}` |
| Null | `null` | `null` |

The format is fully round-trippable. Jackson and kotlinx.serialization produce identical JSON.

## Rules

- **Refs deserialized from JSON are detached by default.** They carry the ID but have no database connection, so calling `fetch()` on one throws `PersistenceException`. Pass the ref itself to `findByRef`, `getByRef` or `findAllByRef`, which read it by its id; never unwrap it into an id to query. Supplying a `RefFactory` to the Storm module yields attached, fetchable refs instead.
- Entities without `Ref` fields need no Storm module registration.
- Both Jackson modules (`storm-jackson2`, `storm-jackson3`) provide the same `StormModule` API.
- **Kotlinx cascade**: if an entity is `@Serializable`, every entity it references (directly or via `Ref<T>`) must also be `@Serializable`.
- **`@Contextual` on `Ref<T>`**: every `Ref` field of a `@Serializable` class needs it (see Kotlinx Serialization Setup).
