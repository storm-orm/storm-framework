# Projections

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

## What Are Projections?

Projections are **read-only** data structures that represent database views or complex queries defined via `@ProjectionQuery`. Like entities, they are plain Kotlin data classes or Java records with no proxies and no bytecode manipulation. Unlike entities, projections support only read operations: no insert, update, or delete.

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

**Complex reusable queries:** Use `@ProjectionQuery` to define projections backed by complex SQL involving joins, aggregations, or subqueries that you want to reuse across your application.

For simple ad-hoc queries or one-off aggregations, prefer using a plain data class. Projections are best suited for reusable, view-like structures. See [SQL Templates](sql-templates.md) for details.

---

## Defining a Projection

A projection is a data class (Kotlin) or record (Java) that implements `Projection<ID>`, where `ID` is the type of the primary key. Use `Projection<Void>` when the projection has no primary key.

### Basic Projection with Primary Key

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
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
record OwnerView(
    @PK Integer id,
    @Nonnull String firstName,
    @Nonnull String lastName,
    @Nullable String telephone
) implements Projection<Integer> {}
```

</TabItem>
</Tabs>

Storm maps this projection to the `owner` table (derived from the class name) and selects only the specified columns.

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
    @Nonnull LocalDate visitDate,
    @Nullable String description,
    @Nonnull String petName
) implements Projection<Void> {}
```

</TabItem>
</Tabs>

### Projection with Foreign Keys

Projections can reference entities or other projections using `@FK`:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class PetView(
    @PK val id: Int,
    val name: String,
    @FK val owner: OwnerView  // References another projection
) : Projection<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record PetView(@PK Integer id,
               @Nonnull String name,
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
    JOIN item i ON i.id = bi.item_id
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
    JOIN item i ON i.id = bi.item_id
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

Obtain a `ProjectionRepository` from the ORM template. This is the read-only counterpart to `EntityRepository`. It provides find, select, count, and existence-check operations, but no insert, update, or delete.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val ownerViews = orm.projection(OwnerView::class)
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
val owner = ownerViews.findById(1)

// Get by primary key (throws if not found)
val owner = ownerViews.getById(1)

// Check existence
val exists = ownerViews.existsById(1)

// Fetch all as a list
val allOwners = ownerViews.findAll()

// Fetch all as a lazy stream
ownerViews.selectAll().forEach { owner ->
    println(owner.firstName)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Count all
long count = ownerViews.count();

// Find by primary key
Optional<OwnerView> owner = ownerViews.findById(1);

// Get by primary key (throws if not found)
OwnerView owner = ownerViews.getById(1);

// Check existence
boolean exists = ownerViews.existsById(1);

// Fetch all as a list
List<OwnerView> allOwners = ownerViews.findAll();

// Fetch all as a stream (must close)
try (Stream<OwnerView> owners = ownerViews.selectAll()) {
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
    .where(OwnerView_.lastName, EQUALS, "Smith")
    .getResultList()

// Filter with comparison operators
val recentVisits = orm.projection(VisitView::class).select()
    .where(VisitView_.visitDate, GREATER_THAN, LocalDate.of(2024, 1, 1))
    .getResultList()

// Filter by nested foreign key
val ownerPets = orm.projection(PetView::class).select()
    .where(PetView_.owner.id, EQUALS, 1)
    .getResultList()

// Count with filter
val count = ownerViews.selectCount()
    .where(OwnerView_.lastName, EQUALS, "Smith")
    .getSingleResult()
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

// Flow-based batch fetching (lazy evaluation)
val idFlow = flowOf(1, 2, 3, 4, 5)
ownerViews.selectById(idFlow).collect { owner ->
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

## Projections vs Entities: Choosing the Right Tool

```
┌─────────────────────────────────────────────────────────────────────┐
│                    When to Use What                                  │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Use Entity when you need to:                                       │
│  • Create, update, or delete records                                │
│  • Work with the full row including all columns                     │
│  • Leverage dirty checking and optimistic locking                   │
│  • Maintain referential integrity through the ORM                   │
│                                                                     │
│  Use Projection when you need to:                                   │
│  • Map database views or materialized views                         │
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
data class OwnerListItem(
    @PK val id: Int,
    val firstName: String,
    val lastName: String
) : Projection<Int>

// Detailed projection for detail views
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
             @Nonnull String firstName,
             @Nonnull String lastName,
             @Nonnull String address,
             @Nonnull String city,
             @Nullable String telephone,
             @Version int version
) implements Entity<Integer> {}

// Lightweight projection for list views
record OwnerListItem(@PK Integer id,
                     @Nonnull String firstName,
                     @Nonnull String lastName
) implements Projection<Integer> {}

// Detailed projection for detail views
record OwnerDetail(@PK Integer id,
                   @Nonnull String firstName,
                   @Nonnull String lastName,
                   @Nonnull String address,
                   @Nonnull String city,
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
data class PetListItem(
    @PK val id: Int,
    val name: String,
    @FK val owner: Ref<OwnerView>  // Lightweight reference
) : Projection<Int>
```

The `Ref` contains only the foreign key value. You can resolve it later if needed:

```kotlin
val pet = orm.projection(PetListItem::class).getById(1)

// Access the foreign key without loading the owner
val ownerId = pet.owner.id()

// Load the full owner when needed
val owner = orm.projection(OwnerView::class).getById(ownerId)
```

---

## Mapping to Custom Tables

By default, Storm derives the table name from the projection class name. Override this with `@DbTable`:

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
| `findById(id)` | Find by primary key, returns null if not found |
| `getById(id)` | Get by primary key, throws if not found |
| `existsById(id)` | Check if projection exists |
| `findAll()` | Fetch all as a list |
| `findAllById(ids)` | Fetch multiple by IDs |
| `selectAll()` | Lazy Flow of all projections |
| `selectById(ids)` | Lazy Flow by IDs |
| `select()` | Query builder for filtering |
| `selectCount()` | Query builder for counting |

Note: Unlike `EntityRepository`, there are no `insert`, `update`, `delete`, or `upsert` methods. Projections are read-only.

---

## Best Practices

### 1. Keep Projections Focused

Design projections for specific use cases rather than trying to reuse one projection everywhere:

```kotlin
// Good: Purpose-built projections
data class OwnerDropdownItem(
    @PK val id: Int,
    val displayName: String  // Computed: firstName + lastName
) : Projection<Int>

data class OwnerSearchResult(
    @PK val id: Int,
    val firstName: String,
    val lastName: String,
    val city: String
) : Projection<Int>

// Avoid: One projection trying to serve all purposes
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
val owners = orm.entity(Owner::class).findAll()  // Loads all columns

// Load only what you need
val owners = orm.projection(OwnerListItem::class).findAll()  // Loads 3 columns
```

### 4. Use Void for Keyless Results

Aggregations and analytics often don't have a natural primary key:

```kotlin
@ProjectionQuery("""
    SELECT
        DATE_TRUNC('month', visit_date) AS month,
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
data class PetWithOwnerSummary(
    @PK val id: Int,
    val name: String,
    val birthDate: LocalDate?,
    @FK val owner: OwnerListItem  // Projection, not full entity
) : Projection<Int>
```

This fetches pet details with a lightweight owner summary in a single query.
