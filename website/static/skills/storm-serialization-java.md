Help the user serialize Storm entities to JSON for REST APIs using Java.

Fetch https://orm.st/llms-full.txt for complete reference.

This is about serializing entities for API responses (Jackson), not about JSON database columns (use /storm-json-java for that).

Ask: whether entities have `Ref<T>` fields and whether they use Spring Boot.

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

- Refs deserialized from JSON are **detached**: they carry the ID but have no database connection. Calling `fetch()` on a deserialized ref throws `PersistenceException`. Use the deserialized ID to query the database directly.
- Entities without `Ref` fields need no Storm module registration.
- Both Jackson modules (`storm-jackson2`, `storm-jackson3`) provide the same `StormModule` API.
- Jackson supports `java.time` natively via the `jackson-datatype-jsr310` module (included by Spring Boot).
