# Projections

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## What Are Projections?

Projections are **read-only** data structures for the query side of your application. A projection can map a database view, a subset of a table's columns, or the result of a custom SQL query defined with `@ProjectionQuery`. Like entities, they are plain Kotlin data classes or Java records with no proxies and no bytecode manipulation, so you get purpose-built read models without writing a DTO mapping layer by hand. Unlike entities, projections support only read operations: no insert, update, or remove.

```
┌─────────────────────────────────────────────────────────────────────┐
│                  Entity vs Projection                               │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Entity<ID>                          Projection<ID>                 │
│  ───────────                         ──────────────                 │
│  - Full CRUD operations              - Read-only operations         │
│  - Represents a database table       - Represents a query result    │
│  - Primary key required              - Primary key optional         │
│  - Dirty checking supported          - No dirty checking needed     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

## When to Use Projections

**Database views:** Represent database views or materialized views as first-class types in your application.

**Lightweight table reads:** Map a subset of a table's columns for list views, dropdowns, and search results without loading full entities.

**Complex reusable queries:** Use `@ProjectionQuery` to define projections backed by complex SQL involving joins, aggregations, or subqueries that you want to reuse across your application.

For simple ad-hoc queries or one-off aggregations, prefer using a plain data class. Projections are best suited for reusable, view-like structures. See [SQL Templates](sql-templates.md) for details.

---

## Defining a Projection

A projection is a data class (Kotlin) or record (Java) that implements `Projection<ID>`, where `ID` is the type of the primary key. Use `Projection<Void>` when the projection has no primary key.

### Basic Projection with Primary Key

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("owner")
data class OwnerView(
    @PK val id: Int,
    val firstName: String,
    val lastName: String,
    val telephone: String?
) : Projection<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("owner")
record OwnerView(
    @PK Integer id,
    String firstName,
    String lastName,
    @Nullable String telephone
) implements Projection<Integer> {}
```

</TabItem>
</Tabs>

By default, Storm derives the table name from the class name using camelCase to snake_case conversion, so `OwnerView` would map to `owner_view`. The `@DbTable` annotation points the projection at the `owner` table instead, so it reads a subset of that table's columns. Leave the annotation out when the class name already matches the view or table you are mapping.

### Projection Without Primary Key

When a projection doesn't need a primary key (e.g., aggregation results), use `Projection<Void>`:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class VisitSummary(
    val visitDate: LocalDate,
    val description: String?,
    val petName: String
) : Projection<Void>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record VisitSummary(
    LocalDate visitDate,
    @Nullable String description,
    String petName
) implements Projection<Void> {}
```

</TabItem>
</Tabs>

This projection reads from a `visit_summary` view, following the default class name to table name conversion.

### The ID Type Parameter

`Projection<ID>` declares the projection's row identity type: the type the id-based operations work with, such as `findById`, `ref(id)`, and `selectById`. When the primary key component is a foreign key, the row identity is the referenced table's key rather than the component value. A projection with `@PK @FK val basket: Basket` is identified by the basket's `Int` key, so it declares `Projection<Int>`.

Unlike entities, projections expose no `id()` accessor: a projection's row identity is not always derivable from its components. It may differ in type from the primary key component, as above, or it may not be among the mapped columns at all. Operations that need the id of a projection instance take it explicitly, as in `Ref.of(projection, id)`.

Storm validates the declared type argument against the mapped primary key: a projection that maps a `@PK` component must not declare `Void`, and the declared type must match the key's row identity type. A projection without a `@PK` component may still declare a row identity type. This supports typed detached refs, while the id-based repository operations require the mapped key.

### Projection with Foreign Keys

Projections can reference entities or other projections using `@FK`:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("pet")
data class PetView(
    @PK val id: Int,
    val name: String,
    @FK val owner: OwnerView  // References another projection
) : Projection<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("pet")
record PetView(@PK Integer id,
               String name,
               @FK OwnerView owner  // References another projection
) implements Projection<Integer> {}
```

</TabItem>
</Tabs>

Storm automatically joins the related table and populates the nested projection.

### Projection with Custom SQL

Use `@ProjectionQuery` to define a projection backed by custom SQL:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@ProjectionQuery("""
    SELECT b.id, COUNT(*) AS item_count, SUM(i.price) AS total_price
    FROM basket b
    JOIN basket_item bi ON bi.basket_id = b.id
    JOIN item i ON bi.item_id = i.id
    GROUP BY b.id
""")
data class BasketSummary(
    @PK val id: Int,
    val itemCount: Int,
    val totalPrice: BigDecimal
) : Projection<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@ProjectionQuery("""
    SELECT b.id, COUNT(*) AS item_count, SUM(i.price) AS total_price
    FROM basket b
    JOIN basket_item bi ON bi.basket_id = b.id
    JOIN item i ON bi.item_id = i.id
    GROUP BY b.id
    """)
record BasketSummary(
    @PK Integer id,
    int itemCount,
    BigDecimal totalPrice
) implements Projection<Integer> {}
```

</TabItem>
</Tabs>

This is useful for aggregations, complex joins, or mapping database views.

---

## Querying Projections

### Getting a ProjectionRepository

Obtain a `ProjectionRepository` from the ORM template. This is the read-only counterpart to `EntityRepository`. It provides find, select, count, and existence-check operations, but no insert, update, or remove.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val ownerViews = orm.projection<OwnerView, _>()
```

</TabItem>
<TabItem value="java" label="Java">

```java
ProjectionRepository<OwnerView, Integer> ownerViews = orm.projection(OwnerView.class);
```

</TabItem>
</Tabs>

### Basic Operations

The `ProjectionRepository` supports the same query patterns as `EntityRepository`, minus write operations. Results are plain data objects with no proxy behavior or session attachment.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Count all
val count = ownerViews.count()

// Find by primary key (returns null if not found)
val foundOwner = ownerViews.findById(1)

// Get by primary key (throws if not found)
val owner = ownerViews.getById(1)

// Check existence
val exists = ownerViews.existsById(1)

// Fetch all as a list
val allOwners = ownerViews.findAll()

// Fetch all as a lazy Flow (collect from a coroutine)
ownerViews.select().resultFlow.collect { owner ->
    println(owner.firstName)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Count all
long count = ownerViews.count();

// Find by primary key (empty Optional if not found)
Optional<OwnerView> foundOwner = ownerViews.findById(1);

// Get by primary key (throws if not found)
OwnerView owner = ownerViews.getById(1);

// Check existence
boolean exists = ownerViews.existsById(1);

// Fetch all as a list
List<OwnerView> allOwners = ownerViews.findAll();

// Fetch all as a stream (must close)
try (Stream<OwnerView> owners = ownerViews.select().getResultStream()) {
    owners.forEach(o -> System.out.println(o.firstName()));
}
```

</TabItem>
</Tabs>

### Query Builder

Use the `select()` method for type-safe queries with the generated metamodel:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Filter by field value
val owners = ownerViews.select()
    .where(OwnerView_.lastName eq "Smith")
    .resultList

// Filter with comparison operators
val recentVisits = orm.projection<VisitView>().select()
    .where(VisitView_.visitDate greater LocalDate.of(2024, 1, 1))
    .resultList

// Filter by nested foreign key
val ownerPets = orm.projection<PetView>().select()
    .where(PetView_.owner.id eq 1)
    .resultList

// Count with filter
val count = ownerViews.selectCount()
    .where(OwnerView_.lastName eq "Smith")
    .singleResult
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Filter by field value
List<OwnerView> owners = ownerViews.select()
    .where(OwnerView_.lastName, EQUALS, "Smith")
    .getResultList();

// Filter with comparison operators
List<VisitView> recentVisits = orm.projection(VisitView.class).select()
    .where(VisitView_.visitDate, GREATER_THAN, LocalDate.of(2024, 1, 1))
    .getResultList();

// Filter by nested foreign key
List<PetView> ownerPets = orm.projection(PetView.class).select()
    .where(PetView_.owner.id, EQUALS, 1)
    .getResultList();
```

</TabItem>
</Tabs>

### Batch Operations

Efficiently fetch multiple projections by ID:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Fetch multiple by IDs
val ids = listOf(1, 2, 3)
val owners = ownerViews.findAllById(ids)

// Flow-based fetching (lazy evaluation, collect from a coroutine)
ownerViews.select()
    .where(OwnerView_.id inList ids)
    .resultFlow
    .collect { owner ->
        // Process each owner
    }
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Fetch multiple by IDs
List<Integer> ids = List.of(1, 2, 3);
List<OwnerView> owners = ownerViews.findAllById(ids);

// Stream-based batch fetching (must close)
try (Stream<OwnerView> stream = ownerViews.selectById(ids.stream())) {
    stream.forEach(owner -> {
        // Process each owner
    });
}
```

</TabItem>
</Tabs>

---

## Choosing Between Entities and Projections

```
┌─────────────────────────────────────────────────────────────────────┐
│                    When to Use What                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Use Entity when you need to:                                       │
│  • Create, update, or delete records                                │
│  • Work with the full row including all columns                     │
│  • Use dirty checking and optimistic locking                        │
│  • Maintain referential integrity through the ORM                   │
│                                                                     │
│  Use Projection when you need to:                                   │
│  • Map database views or materialized views                         │
│  • Read a subset of a table's columns for lists and search results  │
│  • Define reusable complex queries via @ProjectionQuery             │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Example: Same Table, Different Views

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Full entity for writes
data class Owner(
    @PK val id: Int = 0,
    val firstName: String,
    val lastName: String,
    val address: String,
    val city: String,
    val telephone: String?,
    @Version val version: Int = 0
) : Entity<Int>

// Lightweight projection for list views
@DbTable("owner")
data class OwnerListItem(
    @PK val id: Int,
    val firstName: String,
    val lastName: String
) : Projection<Int>

// Detailed projection for detail views
@DbTable("owner")
data class OwnerDetail(
    @PK val id: Int,
    val firstName: String,
    val lastName: String,
    val address: String,
    val city: String,
    val telephone: String?
) : Projection<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Full entity for writes
record Owner(@PK Integer id,
             String firstName,
             String lastName,
             String address,
             String city,
             @Nullable String telephone,
             @Version int version
) implements Entity<Integer> {}

// Lightweight projection for list views
@DbTable("owner")
record OwnerListItem(@PK Integer id,
                     String firstName,
                     String lastName
) implements Projection<Integer> {}

// Detailed projection for detail views
@DbTable("owner")
record OwnerDetail(@PK Integer id,
                   String firstName,
                   String lastName,
                   String address,
                   String city,
                   @Nullable String telephone
) implements Projection<Integer> {}
```

</TabItem>
</Tabs>

Use `Owner` when creating or updating owners. Use `OwnerListItem` for displaying a list (fewer columns, faster queries). Use `OwnerDetail` for read-only detail views.

---

## Working with Refs

When a projection references another entity or projection but you do not need the full related object in every query, use `Ref<T>` to store only the foreign key value. This avoids the cost of an additional JOIN when you only need the key. You can resolve the reference later by fetching the full object on demand.

```kotlin
@DbTable("pet")
data class PetListItem(
    @PK val id: Int,
    val name: String,
    @FK val owner: Ref<OwnerView>  // Lightweight reference
) : Projection<Int>
```

The `Ref` contains only the foreign key value. You can resolve it later if needed:

```kotlin
val pet = orm.projection<PetListItem, _>().getById(1)

// Access the foreign key without loading the owner
val ownerId = pet.owner.projectionId()  // import st.orm.template.projectionId

// Load the full owner when needed
val owner = pet.owner.fetch()
```

See [Refs](refs.md) for the full lifecycle, including detached refs and fetch semantics.

---

## Mapping to Custom Tables

By default, Storm derives the table name from the projection class name using camelCase to snake_case conversion, so `OwnerSummary` maps to `owner_summary`. Override this with `@DbTable`:

```kotlin
@DbTable("owner")
data class OwnerSummary(
    @PK val id: Int,
    @DbColumn("first_name") val name: String
) : Projection<Int>
```

Use `@DbColumn` to map fields to columns with different names.

---

## ProjectionRepository Methods

| Method | Description |
|--------|-------------|
| `count()` | Count all projections |
| `findById(id)` | Find by primary key; returns null (Kotlin) or an empty `Optional` (Java) if not found |
| `getById(id)` | Get by primary key, throws if not found |
| `existsById(id)` | Check if projection exists |
| `findAll()` | Fetch all as a list |
| `findAllById(ids)` | Fetch multiple by IDs |
| `select().resultFlow` | Lazy Flow of all projections (Kotlin) |
| `select().getResultStream()` | Lazy Stream of all projections (Java) |
| `selectById(ids)` | Lazy Stream by IDs (Java) |
| `select()` | Query builder for filtering |
| `selectCount()` | Query builder for counting |

Note: Unlike `EntityRepository`, there are no `insert`, `update`, `remove`, or `upsert` methods. Projections are read-only.

---

## Best Practices

### 1. Keep Projections Focused

Design projections for specific use cases rather than trying to reuse one projection everywhere:

```kotlin
// Good: Purpose-built projections
@ProjectionQuery("""
    SELECT id, first_name || ' ' || last_name AS display_name
    FROM owner
""")
data class OwnerDropdownItem(
    @PK val id: Int,
    val displayName: String
) : Projection<Int>

@DbTable("owner")
data class OwnerSearchResult(
    @PK val id: Int,
    val firstName: String,
    val lastName: String,
    val city: String
) : Projection<Int>

// Avoid: One projection trying to serve all purposes
@DbTable("owner")
data class OwnerProjection(
    @PK val id: Int,
    val firstName: String,
    val lastName: String,
    val address: String?,      // Sometimes null, sometimes not
    val city: String?,
    val telephone: String?,
    val petCount: Int?         // Only populated in some queries
) : Projection<Int>
```

### 2. Use @ProjectionQuery for Complex Queries

When your projection involves joins, aggregations, or subqueries, define the SQL explicitly:

```kotlin
@ProjectionQuery("""
    SELECT
        o.id,
        o.first_name,
        o.last_name,
        COUNT(p.id) AS pet_count
    FROM owner o
    LEFT JOIN pet p ON p.owner_id = o.id
    GROUP BY o.id, o.first_name, o.last_name
""")
data class OwnerWithPetCount(
    @PK val id: Int,
    val firstName: String,
    val lastName: String,
    val petCount: Int
) : Projection<Int>
```

### 3. Prefer Projections for Read-Heavy Paths

In read-heavy scenarios (dashboards, lists, search results), projections reduce database load:

```kotlin
// Instead of loading full entities
val owners = orm.entity<Owner>().findAll()  // Loads all columns

// Load only what you need
val owners = orm.projection<OwnerListItem>().findAll()  // Loads 3 columns
```

### 4. Use Void for Keyless Results

Aggregations and analytics often don't have a natural primary key:

```kotlin
@ProjectionQuery("""
    SELECT
        CAST(DATE_TRUNC('month', visit_date) AS DATE) AS month,
        COUNT(*) AS visit_count,
        COUNT(DISTINCT pet_id) AS unique_pets
    FROM visit
    GROUP BY DATE_TRUNC('month', visit_date)
""")
data class MonthlyVisitStats(
    val month: LocalDate,
    val visitCount: Int,
    val uniquePets: Int
) : Projection<Void>  // No primary key
```

### 5. Combine with Entity Graphs

For complex object graphs, you can mix projections with entity relationships:

```kotlin
@DbTable("pet")
data class PetWithOwnerSummary(
    @PK val id: Int,
    val name: String,
    val birthDate: LocalDate?,
    @FK val owner: OwnerListItem  // Projection, not full entity
) : Projection<Int>
```

This fetches pet details with a lightweight owner summary in a single query.
