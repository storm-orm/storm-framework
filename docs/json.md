import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# JSON Support

Storm provides first-class support for JSON columns, allowing you to store and query JSON data directly in your entities. Annotate a field with `@Json` and Storm handles serialization/deserialization automatically.

## Installation

Storm supports two JSON serialization libraries. Choose the one that fits your project:

### Jackson (Kotlin & Java)

Works with both Kotlin and Java projects. Two variants are available, matching the two major Jackson versions. Both modules require Jackson to be present on the classpath; they do not bring Jackson as a transitive dependency. If you are using Spring Boot, Jackson is already included and the version is managed for you: Spring Boot 3.x ships with Jackson 2, while Spring Boot 4+ ships with Jackson 3. Choose the Storm module that matches your Jackson version. If you are not using Spring Boot, add Jackson to your project directly alongside the corresponding Storm module.

**Jackson 2** (requires Jackson 2.17+):

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-jackson2</artifactId>
    <version>1.11.0</version>
</dependency>
```

```groovy
implementation 'st.orm:storm-jackson2:1.11.0'
```

**Jackson 3** (requires Jackson 3.0+):

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-jackson3</artifactId>
    <version>1.11.0</version>
</dependency>
```

```groovy
implementation 'st.orm:storm-jackson3:1.11.0'
```

The two modules are mutually exclusive on the classpath. Both provide the same public API (`st.orm.jackson` package), so switching between them requires only changing the Maven dependency.

### Kotlinx Serialization (Kotlin)

A Kotlin-native option with compile-time safety. Requires the `kotlinx-serialization` Gradle plugin.

```kotlin
plugins {
    kotlin("plugin.serialization") version "2.0.0"
}

dependencies {
    implementation("st.orm:storm-kotlinx-serialization:1.11.0")
}
```

Storm auto-detects the serialization library at runtime. Just add the dependency and it works.

---

## JSON Columns

Use `@Json` to map a field to a JSON column.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @Json val preferences: Map<String, String>
) : Entity<Int>
```

The `preferences` field is automatically serialized to JSON when writing and deserialized when reading.

</TabItem>
<TabItem value="java" label="Java">

```java
record User(@PK Integer id,
            String email,
            @Json Map<String, String> preferences
) implements Entity<Integer> {}
```

The `preferences` field is automatically serialized to JSON when writing and deserialized when reading.

</TabItem>
</Tabs>

## Complex Types

JSON columns are not limited to maps and primitive collections. You can store structured domain objects directly, preserving their full type hierarchy during serialization and deserialization. This is useful when the nested object has a well-defined shape but does not need its own database table.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

When using kotlinx.serialization, annotate the nested type with `@Serializable`. Jackson discovers types automatically through reflection, so no additional annotation is needed.

```kotlin
@Serializable  // For kotlinx.serialization
data class Address(
    val street: String,
    val city: String,
    val postalCode: String
)

data class User(
    @PK val id: Int = 0,
    val email: String,
    @Json val address: Address
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

Structured domain objects work the same way in Java. Jackson handles serialization automatically for Java records without additional annotations.

```java
record Address(String street,
               String city,
               String postalCode) {}

record User(@PK Integer id,
            String email,
            @Json Address address
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

## JSON Aggregation

JSON aggregation solves the problem of loading one-to-many or many-to-many relationships in a single query. Instead of issuing separate queries or relying on lazy loading, you can use SQL aggregation functions like `JSON_OBJECTAGG` to collect related rows into a JSON array within the main query result. Storm then deserializes that array back into a typed collection on the result object.

This approach eliminates N+1 query problems for relationship loading at the cost of shifting serialization work to both the database and the application layer. It works best when the aggregated collection is moderate in size (see the performance section below).

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class RolesByUser(
    val user: User,
    @Json val roles: List<Role>
)

interface UserRepository : EntityRepository<User, Int> {

    fun getUserRoles(): List<RolesByUser> =
        select(RolesByUser::class) { "${User::class}, JSON_OBJECTAGG(${Role::class})" }
            .innerJoin(UserRole::class).on(User::class)
            .groupBy(User_.id)
            .resultList
}
```

</TabItem>
<TabItem value="java" label="Java">

The same aggregation pattern applies in Java using string templates. The `JSON_OBJECTAGG` function collects related entities into a JSON object that Storm deserializes into the annotated `@Json` field.

```java
record RolesByUser(User user, @Json List<Role> roles) {}

interface UserRepository extends EntityRepository<User, Integer> {

    default List<RolesByUser> getUserRoles() {
        return select(RolesByUser.class, RAW."\{User.class}, JSON_OBJECTAGG(\{Role.class})")
            .innerJoin(UserRole.class).on(User.class)
            .groupBy(User_.id)
            .getResultList();
    }
}
```

</TabItem>
</Tabs>

---

## Database Support

JSON storage works differently across databases:

| Database | JSON Type | Notes |
|----------|-----------|-------|
| PostgreSQL | `JSONB` | Binary format, indexable |
| MySQL | `JSON` | Native JSON type |
| MariaDB | `JSON` | Alias for LONGTEXT with validation |
| Oracle | `JSON` | Native JSON (21c+) |
| MS SQL Server | `NVARCHAR(MAX)` | Stored as text |
| H2 | `CLOB` | Stored as text |

## Use Cases

JSON columns are most valuable when relational normalization would add complexity without proportional benefit. The following patterns illustrate the three main scenarios where JSON storage is the right choice.

### Flexible Schema

When different rows need different sets of attributes, a JSON column avoids the overhead of schema migrations and sparse nullable columns. This is common in product catalogs, configuration storage, and user-defined fields.

```kotlin
data class Product(
    @PK val id: Int = 0,
    val name: String,
    @Json val attributes: Map<String, Any>  // Size, color, weight, etc.
) : Entity<Int>
```

### Denormalized Data

Storing a snapshot of related data directly in the parent row avoids joins at read time and preserves the exact state at the moment of creation. This is useful for data that should not change retroactively, such as a shipping address on an order or the line items at the time of purchase.

```kotlin
data class Order(
    @PK val id: Int = 0,
    val orderDate: LocalDate,
    @Json val shippingAddress: Address,  // Snapshot at order time
    @Json val items: List<OrderItem>     // Denormalized for fast access
) : Entity<Int>
```

### Aggregation Results

Fetch one-to-many or many-to-many relationships in a single query using JSON aggregation. This is the primary alternative to issuing multiple queries or using lazy loading. The trade-off is that the aggregated data arrives as a serialized blob rather than discrete rows, so it works best when the client consumes the collection as a whole rather than filtering or paging within it.

## Tips

1. **Use for truly dynamic data.** Don't use JSON to avoid proper schema design.
2. **Consider query patterns.** JSON columns are harder to filter and index than normalized columns.
3. **Size limits.** Be aware of column size limits for large JSON documents.

## JSON Aggregation Performance

JSON aggregation (`JSON_OBJECTAGG`, `JSON_ARRAYAGG`) is suitable for mappings with a **moderate size**. For larger datasets or extensive mappings, split queries into separate parts to avoid:

- Memory pressure from large JSON documents
- Slow serialization/deserialization

### When to Split Queries

**Use JSON aggregation when:**
- The aggregated collection typically has < 100 items
- The JSON payload is under ~1MB
- The data is read-heavy and benefits from single-query loading

**Split into separate queries when:**
- Collections can grow unbounded (e.g., all orders for a customer)
- You need pagination on the related data
- The aggregated data is rarely accessed

### Example: Splitting Large Relationships

Instead of aggregating all roles per user:

```kotlin
// Might be slow for users with many roles
data class RolesByUser(val user: User, @Json val roles: List<Role>)
```

Query separately:

```kotlin
// Fetch users
val users = orm.findAll<User>()

// Batch fetch roles and group by user
val rolesByUser = orm.findAll(UserRole_.user inList users)
    .groupBy({ it.user }, { it.role })
```

This approach gives you control over pagination, caching, and memory usage.

---

## See Also

- [Queries](queries.md) - aggregation with JSON
- [Dialects](dialects.md) - database-specific JSON support
- [Entities](entities.md) - `@Json` annotation
- [Entity Serialization](serialization.md) - serializing entities with `Ref` fields to JSON (for REST APIs, not database columns)
