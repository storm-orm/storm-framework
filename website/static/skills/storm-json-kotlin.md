---
name: storm-json-kotlin
description: Map JSON database columns with @Json and write JSON aggregation queries in Kotlin, with Jackson or kotlinx.serialization. Use for JSON or JSONB columns, not for serializing REST responses.
---

Help the user work with JSON columns in Storm entities using Kotlin.
Ask: what data they want to store as JSON, which serialization library (Jackson or kotlinx.serialization), and whether they need JSON aggregation.

## JSON Columns

Annotate a field with `@Json` to store it as a JSON column. Storm auto-detects the serialization library at runtime.

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @Json val preferences: Map<String, String>
) : Entity<Int>
```

## Complex Types

JSON columns can store structured domain objects, not just maps and primitives.

With kotlinx.serialization, annotate the nested type with `@Serializable`. Jackson discovers types automatically through reflection.

```kotlin
@Serializable  // For kotlinx.serialization
data class Address(val street: String, val city: String, val postalCode: String)

data class User(
    @PK val id: Int = 0,
    @Json val address: Address
) : Entity<Int>
```

## JSON Aggregation

Use JSON aggregation functions to load one-to-many relationships in a single query:

```kotlin
/**
 * Query result shape: a user with their roles aggregated from JSON. Not
 * backed by a database table or view, so it is a plain data class —
 * deliberately not a Data type.
 */
data class RolesByUser(val user: User, @Json val roles: List<Role>)

val results = orm.entity(User::class)
    .select<RolesByUser, _, _> { "${User::class}, JSON_OBJECTAGG(${Role::class})" }
    .innerJoin<UserRole>().on<User>()
    .groupBy(User_.id)
    .resultList
```

## Dependencies

Storm supports two JSON libraries for Kotlin (add one):
- `storm-jackson2` - Jackson 2.17+ (Spring Boot 3.x)
- `storm-jackson3` - Jackson 3.0+ (Spring Boot 4+)
- `storm-kotlinx-serialization` - Kotlinx Serialization (requires `plugin.serialization` Gradle plugin)

Storm auto-detects the library at runtime. Just add the dependency.

## Database Support

### Column types

When writing migrations, use the correct JSON column type for the target database:

| Database | Column Type | Notes |
|----------|-------------|-------|
| PostgreSQL | `JSONB` | Binary format, indexable |
| MySQL | `JSON` | Native JSON type |
| MariaDB | `JSON` | Alias for LONGTEXT with validation |
| Oracle | `JSON` | Native JSON (21c+) |
| MS SQL Server | `NVARCHAR(MAX)` | Stored as text |
| H2 | `CLOB` | Stored as text (test databases) |

### JSON aggregation functions

In Storm templates, `JSON_OBJECTAGG(${Role::class})` with the entity class as single argument is valid — Storm expands `${Role::class}` to the entity's projected columns, producing the dialect-appropriate key/value arguments. The table below shows the underlying raw SQL forms per database (relevant when writing the SQL by hand or debugging generated SQL). Always ask or detect which dialect the user is targeting:

| Database | Object aggregation | Array aggregation |
|----------|-------------------|-------------------|
| PostgreSQL | `JSON_OBJECT_AGG(key, value)` | `JSON_AGG(value)` |
| MySQL | `JSON_OBJECTAGG(key, value)` | `JSON_ARRAYAGG(value)` |
| MariaDB | `JSON_OBJECTAGG(key, value)` | `JSON_ARRAYAGG(value)` |
| Oracle | `JSON_OBJECTAGG(KEY key VALUE value)` | `JSON_ARRAYAGG(value)` |
| MS SQL Server | Manual via `FOR JSON` | Manual via `FOR JSON` |
| H2 | Not supported | Not supported |

H2 does not support JSON aggregation functions. Run tests that use JSON aggregation on the target database with `@StormTest(database = POSTGRESQL, ...)` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`; a Testcontainers-managed container, see /storm-setup), or verify only the generated SQL using `SqlCapture` without executing the query.

## Rules

- Use JSON for truly dynamic or denormalized data, not to avoid proper schema design.
- JSON aggregation is suitable for moderate-size collections (< 100 items, < 1MB). For large or unbounded collections, use separate queries.
- `@Json` fields are harder to filter and index than normalized columns. Consider query patterns before choosing JSON.
- Always check the target database dialect before writing JSON aggregation queries. The function names and syntax vary.
