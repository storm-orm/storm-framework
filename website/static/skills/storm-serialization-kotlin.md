Help the user serialize Storm entities to JSON for REST APIs using Kotlin.
This is about serializing entities for API responses or caching (Jackson, kotlinx.serialization), not about JSON database columns (use /storm-json-kotlin for that).

Ask: which serialization library (Jackson or kotlinx.serialization), whether entities have `Ref<T>` fields.

Detect the project's framework from its build file (pom.xml or build.gradle.kts): look for `storm-kotlin-spring-boot-starter` or `spring-boot-starter` (Spring Boot), `storm-ktor` or `ktor-server-core` (Ktor), or neither (standalone). Adapt setup examples to the detected framework.

## When You Need the Storm Module

Entities without `Ref` fields serialize with plain Jackson or kotlinx.serialization. No Storm module needed.

Entities with `Ref<T>` fields require the Storm serialization module, because `Ref` can be unloaded (only the FK ID) or loaded (full entity/projection). Standard libraries cannot handle this distinction without help.

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

**Critical**: Every `Ref` field in a `@Serializable` class must be annotated with `@Contextual`:
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

Without `@Contextual`, the kotlinx compiler plugin tries to serialize `Ref` directly and fails at runtime with "RefImpl is not found in the polymorphic scope".

## Kotlinx Serialization Cascade Rule

When an entity is annotated with `@Serializable`, all entities reachable from it must also be `@Serializable`. This includes entities referenced via `@FK` fields (both direct entity fields and `Ref<T>` fields). For `Ref<T>`, the target type `T` must be `@Serializable`; `StormSerializers` handles the `Ref` wrapper itself.

```kotlin
@Serializable
data class Address(
    @PK val id: Int = 0,
    @FK val user: User,                      // User must be @Serializable
    @FK @Contextual val city: Ref<City>?,    // City must be @Serializable
) : Entity<Int>
```

### Java time types

Kotlinx.serialization does not support `java.time` types out of the box. If your entities contain `Instant`, `LocalDate`, `LocalTime`, or similar types, you need custom serializers:

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

Entities cached in external stores like Redis need full serialization support. When using kotlinx.serialization with Redis (e.g., `KotlinxSerializationRedisSerializer`), configure the serializer with `StormSerializers` to handle `Ref<T>`:

```kotlin
val serializer = KotlinxSerializationRedisSerializer(
    Json { serializersModule = StormSerializers }
)
```

The same cascade rule applies: all entities reachable from a cached entity must be `@Serializable`, and all `Ref<T>` fields must have `@Contextual`.

## Ref Serialization Format

| Ref state | JSON output | Example |
|-----------|-------------|---------|
| Unloaded | Raw primary key | `1` |
| Loaded entity | `{"@entity": {...}}` | `{"@entity": {"id": 1, "name": "Betty"}}` |
| Loaded projection | `{"@id": ..., "@projection": {...}}` | `{"@id": 1, "@projection": {"id": 1, "name": "Betty"}}` |
| Null | `null` | `null` |

The format is fully round-trippable. Jackson and kotlinx.serialization produce identical JSON.

## Rules

- Refs deserialized from JSON are **detached**: they carry the ID but have no database connection. Calling `fetch()` on a deserialized ref throws `PersistenceException`. Use the deserialized ID to query the database directly.
- Entities without `Ref` fields need no Storm module registration.
- Both Jackson modules (`storm-jackson2`, `storm-jackson3`) provide the same `StormModule` API.
- **Kotlinx cascade**: if an entity is `@Serializable`, every entity it references (directly or via `Ref<T>`) must also be `@Serializable`.
- **`@Contextual` on `Ref<T>`**: without it, the kotlinx compiler plugin tries to serialize `Ref` directly and fails at runtime with "RefImpl is not found in the polymorphic scope".
