import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Hydration

Hydration is the process of transforming flat database rows into structured Kotlin data classes and Java records.

Kotlin data classes and Java records are ideal for result mapping because they have a **canonical constructor** with a deterministic parameter order. This order matches the declaration order of the record components, providing a predictable and stable mapping target. Combined with their immutability, records eliminate the need for reflection-based field injection or setter calls during hydration.

Storm leverages this by mapping SELECT columns directly to constructor parameters by position. Several optimizations ensure high performance and low memory usage:

- **Positional mapping**: No runtime reflection on column names
- **Compiled mapping plans**: Plans are computed once per type and reused
- **Early cache lookup**: Entities are looked up by primary key before construction, skipping redundant object creation
- **Query-level interning**: Duplicate entities within a result set share the same instance
- **Memory-safe streaming**: Supports efficient iteration over large result sets

Storm natively supports a wide range of field types beyond basic JDBC types:

- **Primitives and wrappers**: `boolean`, `byte`, `short`, `int`, `long`, `float`, `double`
- **Common types**: `String`, `BigDecimal`, `byte[]`, enums
- **Legacy date/time**: `java.util.Date`, `Calendar`, `Timestamp`, `java.sql.Date`, `Time`
- **java.time**: `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime`

**Timezone handling**: Storm uses UTC for reading and writing timestamp values.

For types not in this list, use a [custom converter](#custom-type-converters).

---

## How Column Mapping Works

Storm maps columns to record fields **by position**, matching the order of columns in the result set to the order of constructor parameters in your record. This positional mapping is fast and predictable, with no runtime reflection on column names.

### Basic Example

Given a query that returns three columns:

```sql
SELECT id, email, name FROM user
```

You can map the results to a plain data class:

```kotlin
data class User(
    val id: Int,
    val email: String,
    val name: String
)
```

Storm maps columns to constructor parameters in order:

```
┌───────────────────────────────────────────────────────────────────────┐
│  Result Set Row                                                       │
│  ┌──────────┬────────────────────┬─────────────┐                      │
│  │  col 1   │       col 2        │    col 3    │                      │
│  │    42    │  "alice@test.com"  │   "Alice"   │                      │
│  └──────────┴────────────────────┴─────────────┘                      │
└───────────────────────────────────────────────────────────────────────┘
                                │
                                ▼
┌───────────────────────────────────────────────────────────────────────┐
│  Record Constructor                                                   │
│  User(id = 42, email = "alice@test.com", name = "Alice")              │
└───────────────────────────────────────────────────────────────────────┘
```

The three columns from the result set are passed directly to the `User` constructor in order. Column 1 becomes `id`, column 2 becomes `email`, and column 3 becomes `name`.

---

## Plain Records

Not every query result maps to a full entity. Aggregate queries, reports, and ad-hoc projections return custom column sets that do not correspond to any database table. Storm handles these cases without requiring special interfaces or annotations. You can define a plain Kotlin data class or Java record whose constructor parameters match the query's columns by position and type, and Storm will hydrate it directly.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class MonthlySales(
    val month: YearMonth,
    val orderCount: Long,
    val revenue: BigDecimal
)

val sales = orm.query("""
    SELECT DATE_TRUNC('month', order_date), COUNT(*), SUM(amount)
    FROM orders
    GROUP BY DATE_TRUNC('month', order_date)
""").getResultList(MonthlySales::class)
```

</TabItem>
<TabItem value="java" label="Java">

```java
record MonthlySales(
    YearMonth month,
    long orderCount,
    BigDecimal revenue
) {}

List<MonthlySales> sales = orm.query(RAW."""
        SELECT DATE_TRUNC('month', order_date), COUNT(*), SUM(amount)
        FROM orders
        GROUP BY DATE_TRUNC('month', order_date)""")
    .getResultList(MonthlySales.class);
```

</TabItem>
</Tabs>

This works for any query. The only requirement is that the number and order of columns matches the constructor parameters.

For SQL generation features (template expressions, automatic joins via `@FK`), implement `Data`, `Entity`, or `Projection`. See [SQL Templates](sql-templates.md) for details.

---

## Nested Records

Real-world data models rarely consist of flat structures. Addresses, coordinates, monetary amounts, and other value objects are naturally represented as separate types composed into larger entities. Storm supports this composition without requiring any special annotations for embedded records.

When a record contains another record as a field, Storm **flattens** the nested structure into a single column sequence. During hydration, it reconstructs the nested hierarchy. This means you can model your domain with fine-grained value objects while Storm handles the mapping to and from flat database rows.

### Column Flattening

```kotlin
data class Address(
    val street: String,
    val postalCode: String
)

data class User(
    val id: Int,
    val name: String,
    val address: Address,  // Embedded record
    val active: Boolean
)
```

Storm flattens nested records into consecutive columns:

```
  Record Structure                            Flattened Columns
  ────────────────                            ─────────────────

  ┌─────────────────────┐                     ┌───────┬─────────┬─────────────┬─────────────┬────────┐
  │ User                │                     │ col 1 │  col 2  │    col 3    │    col 4    │ col 5  │
  │  ├─ id: Int         │ ──────────────────▶ │  id   │  name   │   street    │ postalCode  │ active │
  │  ├─ name: String    │                     ├───────┼─────────┼─────────────┼─────────────┼────────┤
  │  ├─ address ────────┼──┐                  │  42   │ "Alice" │ "Main St 1" │   "94086"   │  true  │
  │  │  ┌───────────────┼──┘                  └───────┴─────────┴─────────────┴─────────────┴────────┘
  │  │  │ Address       │                           │         │           │             │        │
  │  │  │  ├─ street    │                           │         │           └──────┬──────┘        │
  │  │  │  └─ postalCode│                           │         │                  │               │
  │  │  └───────────────┘                           └────┬────┘                  │               │
  │  └─ active: Boolean │                                │                       │               │
  └─────────────────────┘                                │                       │               │
                                                         ▼                       ▼               ▼
                                                    User fields            Address fields   User fields
                                                      [1..2]                  [3..4]           [5]
```

The nested `Address` record is expanded inline between `User` fields. Columns 1-2 map to `User.id` and `User.name`, columns 3-4 map to the nested `Address`, and column 5 maps to `User.active`.

### Hydration: Reconstructing Nested Records

During hydration, Storm reconstructs the nested hierarchy from the flat columns. It processes nested records first, then returns to the parent level:

```
  Step 1: Build Address              Step 2: Build User
  ──────────────────────             ──────────────────

  cols [3..4]                        cols [1..2] + Address + col [5]
       │                                   │
       ▼                                   ▼
  ┌───────────────────────┐          ┌─────────────────────────────────────────────────────┐
  │ Address(              │          │ User(                                               │
  │   street = "Main St 1"│ ───────▶ │   id = 42,                                          │
  │   postalCode = "94086"│          │   name = "Alice",                                   │
  │ )                     │          │   address = Address("Main St 1", "94086"),          │
  └───────────────────────┘          │   active = true                                     │
                                     │ )                                                   │
                                     └─────────────────────────────────────────────────────┘
```

Storm first constructs the nested `Address` from columns 3-4, then constructs `User` using columns 1-2, the `Address` instance, and column 5.

### Deep Nesting

Nesting works recursively to any depth:

```kotlin
data class Country(
    val name: String,
    val code: String
)

data class City(
    val name: String,
    @FK val country: Country
)

data class User(
    val id: Int,
    @FK val city: City
)
```

The nested structure flattens to 4 columns, with innermost records at the end:

```
  Record Structure                       Flattened Columns
  ────────────────                       ─────────────────

  ┌────────────────────────┐             ┌──────┬───────────┬───────────────┬──────┐
  │ User                   │             │col 1 │   col 2   │     col 3     │col 4 │
  │  ├─ id: Int            │────────────▶│  id  │ city.name │ country.name  │ code │
  │  └─ city ──────────────┼──┐          ├──────┼───────────┼───────────────┼──────┤
  │     ┌──────────────────┼──┘          │  42  │"Sunnyvale"│"United States"│ "US" │
  │     │ City             │             └──────┴───────────┴───────────────┴──────┘
  │     │  ├─ name: String │                 │         │           │          │
  │     │  └─ country ─────┼──┐              │         │           └────┬─────┘
  │     │     ┌────────────┼──┘              │         │                │
  │     │     │ Country    │                 │         └───────┬────────┘
  │     │     │  ├─ name   │                 │                 │
  │     │     │  └─ code   │                 │                 │
  │     │     └────────────┘                 ▼                 ▼
  │     └──────────────────┘              User [1]       City [2..4]
  └────────────────────────┘                            Country [3..4]
```

With deeply nested records, the innermost record (`Country`) appears last in the column sequence. Column ranges overlap: `City` spans columns 2-4 because it includes `Country`.

Hydration reconstructs from the **innermost** level outward:

```
  Step 1: Build Country         Step 2: Build City           Step 3: Build User
  ─────────────────────         ──────────────────           ──────────────────

  cols [3..4]                   col [2] + Country            col [1] + City
       │                              │                            │
       ▼                              ▼                            ▼
  ┌──────────────────┐          ┌────────────────┐           ┌──────────────────┐
  │ Country(         │          │ City(          │           │ User(            │
  │  "United States",│ ───────▶ │  "Sunnyvale",  │ ────────▶ │   id = 42,       │
  │  "US"            │          │   country ─────┼───┐       │   city ──────────┼─┐
  │ )                │          │ )              │   │       │ )                │ │
  └──────────────────┘          └────────────────┘   │       └──────────────────┘ │
                                       ▲             │              ▲             │
                                       └─────────────┘              └─────────────┘
```

`Country` is constructed first from columns 3-4. Then `City` is constructed using column 2 plus the `Country` instance. Finally, `User` is constructed using column 1 plus the `City` instance.

---

## Foreign Keys (@FK)

The `@FK` annotation marks a field as a foreign key relationship. When the result set includes a joined table, Storm hydrates all its columns into the nested record. See [SQL Templates](sql-templates.md) for how `@FK` affects query generation.

### FK Column Layout

```kotlin
data class City(
    @PK val id: Int,
    val name: String,
    val population: Long
) : Entity<Int>

data class User(
    @PK val id: Int,
    val email: String,
    @FK val city: City  // Foreign key relationship
) : Entity<Int>
```

When the result set includes both `User` and `City` columns, the layout is:

```
┌───────────────────────────────────────────────────────────────────────┐
│  Column:         1        2         3          4            5         │
│                ┌────┬──────────┬─────────┬───────────┬─────────────┐  │
│                │ id │  email   │ city.id │ city.name │ city.popul. │  │
│                └────┴──────────┴─────────┴───────────┴─────────────┘  │
│                                                                       │
│  User fields:   [1..2]                                                │
│  City fields:         [3..5]                                          │
└───────────────────────────────────────────────────────────────────────┘
```

Columns 1-2 contain `User` fields, while columns 3-5 contain all fields from the joined `City` entity. The foreign key column (`city_id`) is **not** included in the result. Storm reconstructs the relationship from the joined entity's primary key.

### Nullable FK

```kotlin
data class User(
    @PK val id: Int,
    val email: String,
    @FK val city: City?  // Nullable FK
) : Entity<Int>
```

When `city` is nullable and all city columns are NULL in a row, the hydrated `city` field is `null`.

---

## Refs (Lazy References)

Eagerly loading every related entity is not always desirable. When a `User` references a `City`, which references a `Country`, a simple user query can cascade into loading the entire object graph. In many cases, the calling code only needs the foreign key value, not the full related entity.

A `Ref<T>` is a lightweight reference that stores only the foreign key value, not the full record. This gives you control over how much data is loaded during hydration. Use `Ref<T>` when:
- You need to break circular dependencies (self-referential entities like a tree structure)
- You want to defer entity loading until the related data is actually needed
- You are processing large result sets and want to minimize memory consumption

### Ref Column Layout

```kotlin
data class User(
    @PK val id: Int,
    val email: String,
    @FK val city: Ref<City>  // Only stores city_id, not full City
) : Entity<Int>
```

With `Ref<T>`, Storm hydrates only the foreign key value (not the full entity):

Column layout:

```
┌───────────────────────────────────────────────────────────────────────┐
│  Column:         1        2         3                                 │
│                ┌────┬──────────┬─────────┐                            │
│                │ id │  email   │ city_id │                            │
│                └────┴──────────┴─────────┘                            │
│                                                                       │
│  User fields:   [1..2]                                                │
│  Ref<City>:           [3] (PK only)                                   │
└───────────────────────────────────────────────────────────────────────┘
```

Only three columns are hydrated. Column 3 contains just the foreign key value, which is wrapped in a `Ref<City>`. Call `fetch()` later to load the full entity:

```kotlin
val user = userRepository.findById(42)
val city: City = user.city.fetch()  // Loads City from database
```

### FK vs Ref Comparison

| Aspect                 | `@FK val city: City`      | `@FK val city: Ref<City>`     |
|------------------------|---------------------------|-------------------------------|
| Columns hydrated       | All City columns          | Only FK column (city_id)      |
| Memory usage           | Higher (full entity)      | Lower (just PK)               |
| Access pattern         | Immediate                 | Deferred (call `fetch()`)     |
| Circular dependencies  | Not allowed               | Allowed                       |

---

## Query-Level Identity (Interning)

When the same entity appears multiple times in a query result (e.g., through joins), Storm ensures they share the same object instance within that query. This is called **interning**.

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          Query Result Set                               │
│                                                                         │
│  SELECT u.*, c.* FROM user u JOIN city c ON u.city_id = c.id            │
│                                                                         │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ Row 1: User(id=1, city_id=42) │ City(id=42, name="Sunnyvale")     │  │
│  │ Row 2: User(id=2, city_id=42) │ City(id=42, name="Sunnyvale")     │  │
│  │ Row 3: User(id=3, city_id=99) │ City(id=99, name="Austin")        │  │
│  └───────────────────────────────────────────────────────────────────┘  │
│                             │                                           │
│                             ▼                                           │
│                 ┌───────────────────────┐                               │
│                 │       Interner        │                               │
│                 │  ┌────────┬────────┐  │                               │
│                 │  │   PK   │ Entity │  │                               │
│                 │  ├────────┼────────┤  │                               │
│                 │  │   42   │ ──────────▶ City(42)                      │
│                 │  │   99   │ ──────────▶ City(99)                      │
│                 │  └────────┴────────┘  │                               │
│                 └───────────────────────┘                               │
│                             │                                           │
│                             ▼                                           │
│  ┌───────────────────────────────────────────────────────────────────┐  │
│  │ Result:                                                           │  │
│  │   User(1) ──▶ City(42) ◀── same instance                          │  │
│  │   User(2) ──▶ City(42) ◀──┘                                       │  │
│  │   User(3) ──▶ City(99)                                            │  │
│  └───────────────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────────────┘
```

Three rows contain `City(42)` data twice, but the interner ensures only one instance is created. Both `User(1)` and `User(2)` reference the same `City` object in memory.

### How Interning Works

As Storm processes each row:

1. **Extract primary key**: Before constructing an entity, Storm extracts its PK from the flat column array
2. **Check interner**: If an entity with that PK was already constructed in this query, return the existing instance
3. **Construct and store**: Otherwise, construct the entity and store it in the interner

This happens automatically during hydration. The interner is scoped to a single query execution. Once you're done iterating results, the interner is discarded.

### Early Cache Lookup: Skipping Construction

A key optimization in Storm's hydration is **early primary key extraction**. Before constructing any nested objects, Storm extracts the primary key directly from the flat column array and checks if that entity already exists in the cache or interner.

When a cache hit occurs, Storm skips the entire construction process for that entity, including all its nested records. This is particularly valuable for queries with joins where the same entity appears in multiple rows.

**How it works:**

1. Storm knows the PK column offset for each entity in the flattened structure
2. Before recursing into nested construction, it reads the PK value at that offset
3. It checks the entity cache first (if applicable), then falls back to the interner
4. On cache hit: skip construction entirely, advance the column cursor, use cached instance
5. On cache miss: proceed with normal construction, then store for later lookup

**Example with joins:**

```kotlin
// Query returns 1000 users, but only 50 unique cities
val users = userRepository.findAll(User_.city eq city)
```

Without early lookup, Storm would construct 1000 `City` objects and then deduplicate. With early lookup:

- Row 1: City PK=42 not in cache -> construct City, store in interner
- Row 2: City PK=42 found in interner -> skip construction, reuse instance
- Row 3: City PK=42 found in interner -> skip construction, reuse instance
- ...

Result: Only 50 City objects are ever constructed, not 1000.

**This optimization applies to:**

- Top-level entities (checked against entity cache first, then interner)
- Nested entities via `@FK` (checked at each nesting level)
- Both simple and composite primary keys

The benefit compounds with deep nesting. If a parent entity is cached, none of its nested children need to be constructed either.

### Memory Safety

The interner only retains entities while your code uses them. Once released, they are cleaned up and don't accumulate in memory. This makes streaming and flow-based processing safe:

```kotlin
// Safe for large result sets - processed entities don't accumulate
orderRepository.selectAll().collect { order ->
    process(order)
    // order can be cleaned up after this iteration
}
```

### Relationship with Entity Cache

Query-level interning and the [entity cache](entity-cache.md) serve different purposes:

| Aspect             | Query Interner                   | Entity Cache                          |
|--------------------|----------------------------------|---------------------------------------|
| Scope              | Single query                     | Transaction                           |
| Purpose            | Deduplicate within result set    | Identity + dirty checking             |
| Isolation level    | Any                              | `REPEATABLE_READ`+ or read-only       |
| Memory management  | Cleaned up when no longer used   | Configurable retention                |

At `REPEATABLE_READ` and above, the entity cache extends query-level identity to the full transaction. The interner ensures correctness within each query regardless of cache settings.

---

## Composite Primary Keys

Some tables use multiple columns as their primary key rather than a single auto-incremented ID. Junction tables (many-to-many relationships) are a common example: the combination of two foreign keys forms the primary key. Storm supports composite primary keys by modeling the key as a separate record type that contains each key column.

```kotlin
data class UserRolePk(
    val userId: Int,    // PK column 1
    val role: String    // PK column 2
)

data class UserRole(
    @PK val pk: UserRolePk,
    val grantedAt: Instant,
    @FK val grantedBy: Ref<User>
) : Entity<UserRolePk>
```

This maps to a `user_role` table where `user_id` and `role` together form the primary key:

```
┌────────────────────────────────────────────────────────────────────────┐
│  Column:         1           2           3          4                  │
│                ┌───────────┬────────────┬──────────┬───────────┐       │
│                │  user_id  │    role    │granted_at│granted_by │       │
│                └───────────┴────────────┴──────────┴───────────┘       │
│                 \___________ __________/                               │
│                             v                                          │
│                     composite primary key                              │
│                                                                        │
│  UserRolePk:    [1..2]                                                 │
│  UserRole:      [1..4] (includes nested PK)                            │
└────────────────────────────────────────────────────────────────────────┘
```

Storm first constructs `UserRolePk` from the primary key columns (1-2), then uses it along with columns 3-4 to construct the full `UserRole` entity.

---

## Custom Type Converters

Storm's built-in type support covers standard JDBC types, but applications often use domain-specific value types that do not map directly to any JDBC type. Examples include durations stored as seconds, monetary amounts stored as cents, or encoded identifiers stored as strings. Custom type converters bridge this gap by defining a bidirectional mapping between a database column type and your domain type.

For types not natively supported by Storm, use `@Convert` to specify a custom converter:

```kotlin
// Value object for type-safe duration handling
data class DurationSeconds(val value: Duration)

// Converter transforms between database Long and DurationSeconds
class DurationConverter : Converter<Long, DurationSeconds> {
    override fun toDatabase(value: DurationSeconds?): Long? =
        value?.value?.toSeconds()

    override fun fromDatabase(dbValue: Long?): DurationSeconds? =
        dbValue?.let { DurationSeconds(Duration.ofSeconds(it)) }
}

data class Task(
    @PK val id: Int,
    @Convert(DurationConverter::class) val timeout: DurationSeconds
) : Entity<Int>
```

Converters map a single column to a custom type. For composite types spanning multiple columns, use nested records instead (see [Nested Records](#nested-records)).

---

## Nullability Handling

Database columns can contain NULL values, but not every field in your data model should accept null. Storm enforces nullability constraints during hydration, catching data integrity issues at the application boundary rather than letting null values propagate silently through your code.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin's type system indicates nullability:

```kotlin
data class User(
    val id: Int,           // Non-nullable
    val email: String,     // Non-nullable
    val nickname: String?  // Nullable
)
```

If a non-nullable field receives NULL from the database, Storm throws an exception.

</TabItem>
<TabItem value="java" label="Java">

Use `@Nonnull` and `@Nullable` annotations:

```java
record User(
    int id,                    // Primitive = non-nullable
    @Nonnull String email,     // Non-nullable
    @Nullable String nickname  // Nullable
) {}
```

</TabItem>
</Tabs>

### Nullable Nested Records

When a nested record field is nullable, Storm checks if **all** its columns are NULL:

```kotlin
data class User(
    val id: Int,
    val address: Address?  // Nullable nested record
)
```

If all columns for `address` are NULL, the field is set to `null`. If some columns are NULL but others aren't, Storm validates each field individually and may throw if non-nullable fields are NULL.

---

## Summary

| Concept | Column Behavior |
|---------|-----------------|
| **Simple field** | 1 column per field |
| **Nested record** | Flattened: all nested fields become consecutive columns |
| **`@FK` record** | All record columns hydrated |
| **`@FK Ref<T>`** | Only FK column hydrated (record PK) |
| **Composite PK** | Multiple columns for PK fields |
| **Converter** | 1 column mapped to custom type |

**Key principles:**
- Columns map by **position**, not name
- Nested records are **flattened** into consecutive columns
- `@FK` hydrates all columns from the related record
- `Ref<T>` hydrates only the foreign key value
- The interner ensures identity within a query result

---

## See Also

- [Entity Cache](entity-cache.md) - identity interning across a transaction
- [Refs](refs.md) - Ref column layout and lazy loading
- [Projections](projections.md) - projection mapping for partial entity views
- [Entities](entities.md) - entity definitions and annotations
