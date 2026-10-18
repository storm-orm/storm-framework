---
name: storm-serialization-java
description: Serialize Storm entities to JSON for REST APIs with Jackson, including Ref fields. Use for API responses and caching, not for JSON database columns.
---

Help the user serialize Storm entities to JSON for REST APIs using Java.
This is about serializing entities for API responses (Jackson), not about JSON database columns (use /storm-json-java for that).

Ask: whether entities have `Ref<T>` fields.

Detect the project's framework from its build file (pom.xml or build.gradle.kts): look for `storm-spring-boot-starter` or `spring-boot-starter` (Spring Boot) or neither (standalone). Adapt setup examples to the detected framework.

## When You Need the Storm Module

Entities without `Ref` fields serialize with plain Jackson. No Storm module needed.

Entities with `Ref<T>` fields require the Storm serialization module, because `Ref` can be unloaded (only the FK ID) or loaded (full entity/projection). Jackson cannot handle this distinction without help.

## Setup

Register `StormModule` on the `ObjectMapper`:
```java
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new StormModule());
```

Spring Boot auto-detects Module beans:
```java
@Configuration
public class JacksonConfig {
    @Bean
    public StormModule stormModule() {
        return new StormModule();
    }
}
```

`StormModule` is in `st.orm.jackson`, available in both `storm-jackson2` (Jackson 2.17+, Spring Boot 3.x) and `storm-jackson3` (Jackson 3.0+, Spring Boot 4+). Choose the module that matches your Jackson version.

## Ref Serialization Format

| Ref state | JSON output | Example |
|-----------|-------------|---------|
| Unloaded | Raw primary key | `1` |
| Loaded entity | `{"@entity": {...}}` | `{"@entity": {"id": 1, "name": "Betty"}}` |
| Loaded projection | `{"@id": ..., "@projection": {...}}` | `{"@id": 1, "@projection": {"id": 1, "name": "Betty"}}` |
| Null | `null` | `null` |

The format is fully round-trippable.

## Rules

- **DTOs and messages hold `Ref<T>`, not ids.** A field that refers to a row is a `Ref<T>` here as everywhere: an unloaded ref writes as its primary key and reads back as a ref of the declared type, so the JSON is the same as an id field while the code stays type-safe. Never add a separate id field next to, or instead of, a `Ref`.
- Refs deserialized from JSON are **detached** by default: they carry the ID but have no database connection. Calling `fetch()` on a deserialized ref throws `PersistenceException`. Pass the ref itself to `findByRef`, `getByRef` or `findAllByRef`, which read it by its id; never unwrap it into an id to query. (Supplying a `RefFactory` to `StormModule` yields attached, fetchable refs instead.)
- Entities without `Ref` fields need no Storm module registration.
- Both Jackson modules (`storm-jackson2`, `storm-jackson3`) provide the same `StormModule` API.
- Jackson supports `java.time` natively via the `jackson-datatype-jsr310` module (included by Spring Boot).
- kotlinx.serialization is Kotlin-only; Java projects use Jackson. For Kotlin projects, see /storm-serialization-kotlin (which prefers kotlinx.serialization).
- **Caching with Spring's cache annotations:** back the cache with serialized values (e.g. Redis with a Jackson-based serializer and `StormModule` registered) rather than object references — Storm's immutable records round-trip losslessly, which is what makes them safe to cache.
