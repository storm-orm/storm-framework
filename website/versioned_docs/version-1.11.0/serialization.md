import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Entity Serialization

Storm entities are plain records and data classes. Because they carry no proxies, no hidden state, and no framework-managed lifecycle, they serialize naturally with standard JSON libraries. An entity that contains only primitive fields, standard types like `LocalDate`, and inline `@FK` relationships will work out of the box with Jackson or kotlinx.serialization, with no additional configuration required.

The challenge arises when entities contain `Ref<T>` fields. A `Ref` is Storm's abstraction for a deferred reference to another entity (see [Refs](refs.md)). Unlike a plain foreign key or an eagerly loaded relationship, a ref can exist in two states: **unloaded** (carrying only the primary key) or **loaded** (holding the full referenced entity in memory). Standard serialization libraries do not understand this distinction, so they cannot serialize or deserialize `Ref` instances without help.

The Storm serialization modules solve this by registering custom serializers and deserializers that handle both ref states. Once registered, entities with refs serialize and deserialize correctly, preserving the loaded/unloaded distinction across the JSON round-trip.

---

## Setup

### Jackson (Kotlin & Java)

For Jackson-based projects, register `StormModule` on your `ObjectMapper`. This single registration covers all `Ref` fields across all entity types:

```java
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new StormModule());
```

The `StormModule` class lives in the `st.orm.jackson` package and is available in both `storm-jackson2` (Jackson 2.17+) and `storm-jackson3` (Jackson 3.0+). Choose the module that matches your Jackson version. For installation details and guidance on choosing between the two, see [JSON Support](json.md).

**Spring Boot:** Spring Boot auto-detects any Jackson `Module` bean and registers it on the application's `ObjectMapper`. Declaring `StormModule` as a bean is all that is needed:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Configuration
class JacksonConfig {

    @Bean
    fun stormModule(): StormModule = StormModule()
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class JacksonConfig {

    @Bean
    public StormModule stormModule() {
        return new StormModule();
    }
}
```

</TabItem>
</Tabs>

With this in place, every `@RestController` response that returns an entity with `Ref` fields will serialize correctly without any per-endpoint configuration.

### Kotlinx Serialization (Kotlin)

For Kotlin projects using kotlinx.serialization, configure the `Json` instance with `StormSerializersModule`. This registers contextual serializers for the `Ref` type:

```kotlin
val json = Json {
    serializersModule = StormSerializersModule()
}
```

If you do not need any customization, a pre-built convenience instance is available:

```kotlin
val json = Json {
    serializersModule = StormSerializers
}
```

Both `StormSerializersModule` and `StormSerializers` are in the `st.orm.serialization` package, provided by the `storm-kotlinx-serialization` module.

#### The `@Contextual` Requirement

Kotlinx.serialization uses compile-time code generation for serializers. It only delegates to the `SerializersModule` at runtime for fields explicitly annotated with `@Contextual`. Because `Ref` is a Storm type (not a kotlinx-serializable class), every `Ref` field in a `@Serializable` class must carry this annotation. Without it, kotlinx.serialization will fail at compile time because it cannot generate a serializer for `Ref` on its own.

```kotlin
@Serializable
data class Order(
    @PK val id: Int = 0,
    @FK @Contextual val customer: Ref<Customer>,
) : Entity<Int>
```

The same applies to collections of refs. Both the field itself and the type argument need the annotation so that the contextual serializer is used at both the collection level and the element level:

```kotlin
@Serializable
data class TeamMembers(
    @Contextual val members: List<@Contextual Ref<User>>,
)
```

This requirement does not apply to Jackson, which resolves serializers at runtime through reflection and does not need compile-time annotations for `Ref`.

---

## Serialization Format

The serialization module uses a compact, self-describing JSON format that preserves the ref's state. The format varies depending on whether the ref is unloaded (only the foreign key is known), loaded with an entity, or loaded with a projection.

| Ref state | JSON output | Example |
|-----------|-------------|---------|
| Unloaded | Raw primary key value | `1` or `"abc-123"` |
| Loaded entity | `{"@entity": {...}}` | `{"@entity": {"id": 1, "name": "Betty"}}` |
| Loaded projection | `{"@id": ..., "@projection": {...}}` | `{"@id": 1, "@projection": {"id": 1, "name": "Betty"}}` |
| Null | `null` | `null` |

An unloaded ref serializes as a bare value because there is nothing more to convey than the primary key. This keeps the JSON minimal, which is convenient for API responses where the client only needs the ID and can fetch the full object separately if needed.

A loaded entity ref wraps the full entity data in an `@entity` object. This tells the deserializer that the enclosed data is a complete entity, from which it can reconstruct a loaded ref with `getOrNull()` returning the entity instance.

A loaded projection ref uses a different wrapper (`@projection`) and includes a separate `@id` field. The explicit ID is necessary because projections are partial views of an entity and may not expose an `id()` accessor. Without the separate `@id` field, the deserializer would have no reliable way to recover the primary key.

Both Jackson and kotlinx.serialization produce identical JSON for the same ref state, so output from one library can be consumed by the other.

---

## Examples

The following examples walk through the common serialization scenarios, starting with the simplest case and building up to loaded refs and round-trip deserialization.

### Entities Without Refs

Entities that contain only standard field types serialize with plain Jackson or kotlinx.serialization. No Storm module registration is needed, and no special annotations are required beyond what the serialization library itself expects.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Serializable
data class PetType(
    @PK val id: Int = 0,
    val name: String,
) : Entity<Int>

val petType = PetType(id = 1, name = "cat")
val json = Json.encodeToString(petType)
// {"id":1,"name":"cat"}
```

Because `PetType` has no `Ref` fields, the default kotlinx.serialization behavior handles everything. The `@Serializable` annotation generates the serializer at compile time.

</TabItem>
<TabItem value="java" label="Java">

```java
record PetType(@PK Integer id, String name) implements Entity<Integer> {}

PetType petType = new PetType(1, "cat");
ObjectMapper mapper = new ObjectMapper();
String json = mapper.writeValueAsString(petType);
// {"id":1,"name":"cat"}
```

Java records are natively supported by Jackson. No module registration is needed when the entity has no `Ref` fields.

</TabItem>
</Tabs>

### Unloaded Ref

The most common scenario in REST APIs is returning entities where the ref has not been fetched. Storm loads only the foreign key ID into the ref, and the serializer writes that ID as a bare value. This produces compact JSON and avoids unnecessary database lookups during serialization.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Serializable
data class Pet(
    @PK val id: Int = 0,
    val name: String,
    @FK @Contextual val owner: Ref<Owner>?,
) : Entity<Int>

val pet = orm.get(Pet_.id eq 1)
val json = Json { serializersModule = StormSerializers }
    .encodeToString(pet)
// {"id":1,"name":"Leo","owner":1}
```

The `owner` field serializes as `1`, the owner's primary key. No `Owner` data was loaded from the database; only the foreign key column value was available, and that is exactly what appears in the JSON.

</TabItem>
<TabItem value="java" label="Java">

```java
record Pet(@PK Integer id,
           String name,
           @FK Ref<Owner> owner
) implements Entity<Integer> {}

Pet pet = orm.entity(Pet.class).getById(1);
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new StormModule());
String json = mapper.writeValueAsString(pet);
// {"id":1,"name":"Leo","owner":1}
```

The `owner` field serializes as `1`, the owner's primary key. No `Owner` data was loaded from the database; only the foreign key column value was available, and that is exactly what appears in the JSON.

</TabItem>
</Tabs>

### Loaded Entity Ref

When the application calls `fetch()` on a ref before serialization, the referenced entity is loaded into memory. The serializer detects this and writes the full entity data inside an `@entity` wrapper. This is useful when the API consumer needs the related object inline without making a separate request.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pet = orm.get(Pet_.id eq 1)
pet.owner?.fetch()  // Load the owner into the ref

val json = Json { serializersModule = StormSerializers }
    .encodeToString(pet)
// {"id":1,"name":"Leo","owner":{"@entity":{"id":1,"firstName":"Betty","lastName":"Davis"}}}
```

After `fetch()`, calling `pet.owner?.getOrNull()` returns the `Owner` instance. The serializer sees that the ref holds data and emits the `@entity` wrapper instead of the bare ID.

</TabItem>
<TabItem value="java" label="Java">

```java
Pet pet = orm.entity(Pet.class).getById(1);
pet.owner().fetch();  // Load the owner into the ref

ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new StormModule());
String json = mapper.writeValueAsString(pet);
// {"id":1,"name":"Leo","owner":{"@entity":{"id":1,"firstName":"Betty","lastName":"Davis"}}}
```

After `fetch()`, calling `pet.owner().getOrNull()` returns the `Owner` instance. The serializer sees that the ref holds data and emits the `@entity` wrapper instead of the bare ID.

</TabItem>
</Tabs>

### Loaded Projection Ref

When the ref target is a [Projection](projections.md) rather than an `Entity`, the loaded format includes both `@id` and `@projection` fields. The separate `@id` is necessary because projections are partial views and may not include a field that maps to the primary key.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Serializable
data class OwnerSummary(
    @PK val id: Int = 0,
    val firstName: String,
) : Projection<Int>

@Serializable
data class PetWithProjectionOwner(
    @PK val id: Int = 0,
    val name: String,
    @FK @Contextual val owner: Ref<OwnerSummary>?,
) : Entity<Int>

val pet = orm.get(PetWithProjectionOwner_.id eq 1)
pet.owner?.fetch()

val json = Json { serializersModule = StormSerializers }
    .encodeToString(pet)
// {"id":1,"name":"Leo","owner":{"@id":1,"@projection":{"id":1,"firstName":"Betty"}}}
```

</TabItem>
<TabItem value="java" label="Java">

```java
record OwnerSummary(@PK Integer id, String firstName) implements Projection<Integer> {}

record PetWithProjectionOwner(@PK Integer id,
                              String name,
                              @FK Ref<OwnerSummary> owner
) implements Entity<Integer> {}

var pet = orm.entity(PetWithProjectionOwner.class).getById(1);
pet.owner().fetch();

ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new StormModule());
String json = mapper.writeValueAsString(pet);
// {"id":1,"name":"Leo","owner":{"@id":1,"@projection":{"id":1,"firstName":"Betty"}}}
```

</TabItem>
</Tabs>

### Round-Trip Deserialization

The serialization format is fully round-trippable. Both Jackson and kotlinx.serialization can reconstruct entities with refs from the JSON produced by the serializer. The ref's state is preserved: an unloaded ref (bare ID) deserializes back to an unloaded ref, and a loaded ref (`@entity` or `@projection` wrapper) deserializes back to a loaded ref with the data accessible via `getOrNull()`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Deserializing a bare ID produces an unloaded ref. The ID is available, but `getOrNull()` returns `null` because no entity data was present in the JSON.

```kotlin
val jsonString = """{"id":1,"name":"Leo","owner":1}"""
val pet = Json { serializersModule = StormSerializers }
    .decodeFromString<Pet>(jsonString)

pet.name             // "Leo"
pet.owner?.id()      // 1
pet.owner?.getOrNull()  // null (unloaded)
```

Deserializing an `@entity` wrapper produces a loaded ref. The full entity is reconstructed and available immediately.

```kotlin
val jsonString = """{"id":1,"name":"Leo","owner":{"@entity":{"id":1,"firstName":"Betty","lastName":"Davis"}}}"""
val pet = Json { serializersModule = StormSerializers }
    .decodeFromString<Pet>(jsonString)

pet.owner?.getOrNull()  // Owner(id=1, firstName="Betty", lastName="Davis")
```

</TabItem>
<TabItem value="java" label="Java">

Deserializing a bare ID produces an unloaded ref. The ID is available, but `getOrNull()` returns `null` because no entity data was present in the JSON.

```java
String jsonString = """
    {"id":1,"name":"Leo","owner":1}""";
ObjectMapper mapper = new ObjectMapper();
mapper.registerModule(new StormModule());
Pet pet = mapper.readValue(jsonString, Pet.class);

pet.name();              // "Leo"
pet.owner().id();        // 1
pet.owner().getOrNull(); // null (unloaded)
```

Deserializing an `@entity` wrapper produces a loaded ref. The full entity is reconstructed and available immediately.

```java
String jsonString = """
    {"id":1,"name":"Leo","owner":{"@entity":{"id":1,"firstName":"Betty","lastName":"Davis"}}}""";
Pet pet = mapper.readValue(jsonString, Pet.class);

pet.owner().getOrNull();  // Owner(id=1, firstName="Betty", lastName="Davis")
```

</TabItem>
</Tabs>

Note that refs deserialized from JSON are **detached**: they carry the type and primary key but have no connection to a database context. Calling `fetch()` on a deserialized ref will throw a `PersistenceException`. If you need to fetch the referenced entity, use the deserialized ID to query the database directly. See [Detached Ref Behavior](refs.md#detached-ref-behavior) for more details.

---

## See Also

- [JSON Support](json.md) -- JSON columns and aggregation with `@Json`
- [Refs](refs.md) -- lightweight entity references and deferred loading
- [Entities](entities.md) -- entity definition and annotations
