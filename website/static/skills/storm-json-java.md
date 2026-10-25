---
name: storm-json-java
description: Map JSON database columns with @Json and write JSON aggregation queries in Java. Use for JSON or JSONB columns, not for serializing REST responses.
---

Help the user work with JSON columns in Storm entities using Java.
Ask: what data they want to store as JSON and whether they need JSON aggregation.

## JSON Columns

**Annotate a field with `@Json` to store it as a JSON column.** Storm auto-detects Jackson at runtime.

```java
record User(@PK Integer id,
            String email,
            @Json Map<String, String> preferences
) implements Entity<Integer> {}
```

## Complex Types

**JSON columns can store structured domain objects,** not just maps and primitives. Jackson handles records without additional annotations.

```java
record Address(String street, String city, String postalCode) {}

record User(@PK Integer id,
            @Json Address address
) implements Entity<Integer> {}
```

## JSON Aggregation

**Use JSON aggregation functions to load one-to-many relationships in a single query:**

```java
/**
 * Query result shape: a user with their roles aggregated from JSON. Not
 * backed by a database table or view, so it is a plain record,
 * deliberately not a Data type.
 */
record RolesByUser(User user, @Json List<Role> roles) {}

var results = orm.entity(User.class)
    .select(RolesByUser.class, RAW."\{User.class}, JSON_OBJECTAGG(\{Role.class})")
    .innerJoin(UserRole.class).on(User.class)
    .groupBy(User_.id)
    .getResultList();
```

## Dependencies

Storm supports two Jackson modules for Java (add one):
- `storm-jackson2` - Jackson 2.17+ (Spring Boot 3.x)
- `storm-jackson3` - Jackson 3.0+ (Spring Boot 4+)

Storm auto-detects Jackson at runtime. Just add the dependency.

## Database Support

### Column types

**In migrations, use the database's JSON column type, or a text type holding JSON where it has none.**

### JSON aggregation functions

**`JSON_OBJECTAGG(\{Role.class})` with the entity class as single argument is valid in a Storm template.** Storm expands `\{Role.class}` to the entity's projected columns, producing the dialect-appropriate key/value arguments.

**When writing the SQL by hand or debugging generated SQL, use the database's JSON object and array aggregate functions.** These are `JSON_OBJECTAGG` and `JSON_ARRAYAGG` in standard SQL, under the spelling the target database documents. A database without them builds the JSON with its own query syntax, written by hand.

**Run tests that use JSON aggregation on the project's database.** The in-memory test database has no JSON aggregate functions. Use `@StormTest(database = ..., ...)` with the constant for the project's database, which runs the test in a Testcontainers-managed container (see /storm-setup). Alternatively, verify only the generated SQL with `SqlCapture`, without executing the query.

## Rules

- Use JSON for truly dynamic or denormalized data, not to avoid proper schema design.
- JSON aggregation suits moderate-size collections (< 100 items, < 1MB). For large or unbounded collections, use separate queries.
- `@Json` fields are harder to filter and index than normalized columns. Consider query patterns before choosing JSON.
- Always ask or detect the target database dialect before writing JSON aggregation queries. The function names and syntax vary.
- For a GraalVM native image, annotate a query result record outside the entity graph with `@GenerateMetamodel`: the native-image metadata and an instantiator are generated for it. Entities, projections and the types of `@Json` columns need nothing.
