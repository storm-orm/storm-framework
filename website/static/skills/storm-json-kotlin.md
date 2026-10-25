---
name: storm-json-kotlin
description: Map JSON database columns with @Json and write JSON aggregation queries in Kotlin, with Jackson or kotlinx.serialization. Use for JSON or JSONB columns, not for serializing REST responses.
---

Help the user work with JSON columns in Storm entities using Kotlin.
Ask: what data they want to store as JSON, which serialization library (Jackson or kotlinx.serialization), and whether they need JSON aggregation.

## JSON Columns

**Annotate a field with `@Json` to store it as a JSON column.** Storm auto-detects the serialization library at runtime.

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @Json val preferences: Map<String, String>
) : Entity<Int>
```

## Complex Types

**JSON columns can store structured domain objects,** not just maps and primitives. With kotlinx.serialization, annotate the nested type with `@Serializable`. Jackson discovers types through reflection.

```kotlin
@Serializable  // For kotlinx.serialization
data class Address(val street: String, val city: String, val postalCode: String)

data class User(
    @PK val id: Int = 0,
    @Json val address: Address
) : Entity<Int>
```

## JSON Aggregation

**Use JSON aggregation functions to load one-to-many relationships in a single query:**

```kotlin
/**
 * Query result shape: a user with their roles aggregated from JSON. Not
 * backed by a database table or view, so it is a plain data class,
 * deliberately not a Data type.
 */
data class RolesByUser(val user: User, @Json val roles: List<Role>)

val results = orm.entity(User::class)
    .select(RolesByUser::class) { "${User::class}, JSON_OBJECTAGG(${Role::class})" }
    .innerJoin<UserRole>().on<User>()
    .groupBy(User_.id)
    .resultList
```

## Dependencies

Storm supports three JSON libraries for Kotlin (add one):
- `storm-jackson2` - Jackson 2.17+ (Spring Boot 3.x)
- `storm-jackson3` - Jackson 3.0+ (Spring Boot 4+)
- `storm-kotlinx-serialization` - Kotlinx Serialization (requires the `plugin.serialization` Gradle plugin)

Storm auto-detects the library at runtime. Just add the dependency. With kotlinx.serialization and a Jackson module both present, kotlinx converts the `@Json` fields whose type it has a serializer for (`@Serializable`), Jackson the rest; a `@Convert` on the field wins over both.

## Database Support

### Column types

**In migrations, use the database's JSON column type, or a text type holding JSON where it has none.**

### JSON aggregation functions

**`JSON_OBJECTAGG(${Role::class})` with the entity class as single argument is valid in a Storm template.** Storm expands `${Role::class}` to the entity's projected columns, producing the dialect-appropriate key/value arguments.

**When writing the SQL by hand or debugging generated SQL, use the database's JSON object and array aggregate functions.** These are `JSON_OBJECTAGG` and `JSON_ARRAYAGG` in standard SQL, under the spelling the target database documents. A database without them builds the JSON with its own query syntax, written by hand.

**Run tests that use JSON aggregation on the project's database.** The in-memory test database has no JSON aggregate functions. Use `@StormTest(database = ..., ...)` with the constant for the project's database, which runs the test in a Testcontainers-managed container (see /storm-setup). Alternatively, verify only the generated SQL with `SqlCapture`, without executing the query.

## Rules

- Use JSON for truly dynamic or denormalized data, not to avoid proper schema design.
- JSON aggregation suits moderate-size collections (< 100 items, < 1MB). For large or unbounded collections, use separate queries.
- `@Json` fields are harder to filter and index than normalized columns. Consider query patterns before choosing JSON.
- Always ask or detect the target database dialect before writing JSON aggregation queries. The function names and syntax vary.
- For a GraalVM native image, annotate a query result record outside the entity graph with `@GenerateMetamodel`: the native-image metadata and an instantiator are generated for it. Entities, projections and the types of `@Json` columns need nothing.
