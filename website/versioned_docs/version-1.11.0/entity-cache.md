import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Entity Cache

Storm maintains a transaction-scoped entity cache that optimizes database interactions. The cache is a pure performance optimization: it never changes the semantics of your transactions. What you read from the database is exactly what you would read without caching; the cache simply avoids redundant work.

## Design Principles

**Semantics-preserving:** The cache is carefully designed to align with your chosen transaction isolation level. At `READ_COMMITTED` or lower, you see fresh data on every read; the cache won't return stale instances. At `REPEATABLE_READ` or higher, returning cached instances is safe and matches what the database guarantees.

**Transparent:** You don't need to manage the cache. It's automatically scoped to the transaction and cleared on commit or rollback. There's no flush, no detach, no merge. Just predictable behavior aligned with your isolation level.

**Multi-purpose:** The cache serves four complementary goals:

1. **Query optimization:** Avoid redundant database round-trips for the same entity
2. **Hydration optimization:** Skip entity construction when a cached instance exists
3. **Identity preservation:** Same database row returns the same object instance
4. **Dirty checking:** Track observed state for efficient updates

## How It Works

When you read an entity within a transaction, Storm stores it in a transaction-local cache keyed by primary key:

```
┌─────────────────────────────────────────────────────────────────────────┐
│                        Transaction Scope                                │
│                                                                         │
│   ┌──────────────┐                      ┌──────────────────────────┐    │
│   │   Database   │                      │      Entity Cache        │    │
│   └──────┬───────┘                      │  ┌────────┬───────────┐  │    │
│          │                              │  │   PK   │  Entity   │  │    │
│          │  SELECT                      │  ├────────┼───────────┤  │    │
│          ▼                              │  │   1    │  User(1)  │  │    │
│   ┌──────────────┐    cache write       │  │   2    │  User(2)  │  │    │
│   │  User(id=1)  │ ─────────────────────▶  │   42   │  City(42) │  │    │
│   └──────────────┘                      │  └────────┴───────────┘  │    │
│                                         └──────────────────────────┘    │
│                                                    │                    │
│   ┌──────────────┐    cache read                   │                    │
│   │ findById(1)  │ ◀───────────────────────────────┘                    │
│   └──────────────┘    (no SQL)                                          │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

---

## Cache Behavior

Whether Storm returns cached instances depends on the transaction isolation level:

| Isolation Level | Cache Write | Cache Read |
|-----------------|-------------|------------|
| `READ_COMMITTED` or lower | If dirty checking enabled | No |
| `REPEATABLE_READ` or higher | Yes | Yes |

At `READ_COMMITTED` or lower, Storm fetches fresh data on every read. At `REPEATABLE_READ` or higher, cached instances are returned. This matches what the database guarantees at each isolation level.

When no isolation level is explicitly set, Storm uses the database default and fetches fresh data on each read. Most databases default to `READ_COMMITTED`.

---

## Query Optimization

The primary benefit of the entity cache is avoiding redundant database round-trips. When your code reads the same entity multiple times within a transaction, the cache short-circuits the second read. This matters most in business logic that navigates entity graphs, where the same parent entity may be reached through multiple paths.

### Repository Lookups

Repository methods that fetch by primary key check the cache first:

```kotlin
transaction {
    val user = userRepository.findById(1)    // Database query, result cached

    // ... other operations ...

    val sameUser = userRepository.findById(1) // Cache hit, no query
    // user === sameUser (same instance at REPEATABLE_READ+)
}
```

This applies to:
- `findById()` / `getById()`
- `findByRef()` / `getByRef()`
- `selectById()` / `selectByRef()`

### Ref Resolution

When you call `fetch()` on a `Ref`, Storm checks the cache before querying:

```kotlin
transaction {
    val order = orderRepository.findById(orderId)

    // If the customer was already loaded in this transaction,
    // this returns the cached instance without a query
    val customer = order.customer.fetch()
}
```

This is particularly useful when navigating entity graphs where the same entity might be referenced multiple times.

### Select Operations

Batch select operations benefit from cache-aware splitting. When you request multiple entities by ID, Storm partitions the IDs into cache hits and cache misses, queries the database only for the misses, and merges the results. This is transparent to the caller and reduces query size when some entities have already been loaded.

```kotlin
transaction {
    // Load some users
    val user1 = userRepository.findById(1)  // Cached
    val user2 = userRepository.findById(2)  // Cached

    // Select users 1, 2, 3, 4, 5
    val users = userRepository.select(listOf(1, 2, 3, 4, 5))
    // Only queries for IDs 3, 4, 5 - returns cached instances for 1, 2
}
```

---

## Entity Identity

At `REPEATABLE_READ` and above, the cache ensures consistent entity identity within a transaction:

```kotlin
transaction(isolation = REPEATABLE_READ) {
    val user1 = userRepository.findById(1)
    val user2 = userRepository.findById(1)

    // Same instance
    check(user1 === user2)

    // Also applies to entities loaded via relationships
    val order = orderRepository.findById(orderId)
    val orderUser = order.user.fetch()

    if (order.userId == 1) {
        check(orderUser === user1)  // Same instance
    }
}
```

This identity guarantee simplifies application logic: you can use reference equality (`===`) to check if two variables refer to the same database row.

---

## Cache Invalidation

The cache must stay consistent with the database. Rather than trying to predict what the database will store after a mutation (which is impossible when triggers, computed columns, or version increments are involved), Storm invalidates the cache entry for any mutated entity. The next read fetches the authoritative state from the database.

### After Mutations

Insert, update, upsert, and delete operations invalidate the cache entry for the affected entity:

```kotlin
transaction {
    val user = userRepository.findById(1)     // Cached

    userRepository.update(user.copy(name = "New Name"))
    // Cache entry invalidated

    val freshUser = userRepository.findById(1) // Database query
    // freshUser has the database state (including any trigger modifications)
}
```

Why invalidate rather than update? The database may modify data in ways not visible to the application:
- Triggers can change values after INSERT/UPDATE
- Version fields are incremented by the database
- Default values and computed columns
- `ON UPDATE CURRENT_TIMESTAMP` constraints

By invalidating, the next read fetches the actual persisted state.

### Raw SQL Mutations

When you execute raw SQL mutations, Storm cannot determine which entities were affected:

```kotlin
transaction {
    val user = userRepository.findById(1)   // Cached
    val city = cityRepository.findById(42)  // Cached

    // Raw SQL - Storm doesn't know what was affected
    orm.execute("UPDATE user SET status = 'inactive' WHERE last_login < ?", cutoffDate)

    // All caches cleared for safety
    val freshUser = userRepository.findById(1)  // Database query
}
```

To preserve cache efficiency, prefer using repository methods or typed templates that specify the entity type.

---

## Memory Management

The entity cache is scoped to a single transaction and automatically discarded when the transaction commits or rolls back. You don't need to manage cache lifecycle; it's tied to the transaction boundary.

Within a transaction, memory is managed automatically based on what your code is using:

- Entities you're actively using stay cached
- Entities you've moved past can be reclaimed
- Memory usage stays proportional to your working set

### Retention Modes

The retention mode controls how long the JVM retains cached entities within a transaction.

The `default` mode retains entities for the duration of the transaction, which provides reliable dirty checking. The JVM may still reclaim entries under memory pressure. Switch to `light` only if you have memory-constrained transactions that load a very large number of entities and you are willing to trade dirty-checking accuracy for lower memory usage.

Configure retention behavior via `StormConfig` or system property:

```kotlin
val config = StormConfig.of(mapOf(ENTITY_CACHE_RETENTION to "light"))
val orm = ORMTemplate.of(dataSource, config)
```

```bash
-Dstorm.entity_cache.retention=default   # Default
-Dstorm.entity_cache.retention=light
```

| Mode | Behavior | Use Case |
|------|----------|----------|
| `default` | Entries retained for the transaction duration (reclaimable under memory pressure) | Most applications |
| `light` | Entries may be cleaned up when entity is no longer referenced | Memory-constrained bulk operations |

### Impact on Dirty Checking

If an entity's cache entry is cleaned up before you call `update()`, Storm falls back to a full-row update. This is correct but less optimal. With the `default` retention mode, this rarely happens. If you use `light` retention and observe frequent fallbacks, consider:

- Switching to `default` retention
- Keeping references to entities until update
- Restructuring code to update sooner after reading

---

## Dirty Checking Integration

The entity cache serves a dual purpose beyond query optimization: it stores the original state of entities at the time they were read. This original state is the baseline for dirty checking. When you update an entity, Storm compares the new values against the cached original to determine which fields changed, producing a minimal UPDATE statement. Without the cache, Storm falls back to updating all columns.

```kotlin
transaction {
    val user = userRepository.findById(1)  // State observed and cached

    // Modify the entity
    val updated = user.copy(name = "New Name")

    // Storm compares against cached state
    userRepository.update(updated)
    // With FIELD mode: UPDATE user SET name = ? WHERE id = ?
    // With ENTITY mode: Full row update, but skipped if unchanged
}
```

Cache writes for dirty checking occur at all isolation levels when dirty checking is enabled for the entity type. See [Dirty Checking & Update Modes](dirty-checking.md) for details on configuring update behavior.

---

## Transaction Boundaries

The entity cache is scoped to a single transaction. When the transaction commits or rolls back, all cached state is discarded. This ensures that no stale data leaks across transaction boundaries.

### Nested Transactions

Cache behavior with nested transactions follows from the underlying transaction semantics. Propagation modes that share the parent transaction also share the parent cache. Propagation modes that create a new transaction (or suspend the current one) start with a fresh, empty cache.

| Propagation | Cache Behavior |
|-------------|----------------|
| `REQUIRED`, `SUPPORTS`, `MANDATORY` | Shares parent's cache |
| `NESTED` | Shares parent's cache; cleared on savepoint rollback |
| `REQUIRES_NEW` | Fresh cache (separate transaction) |
| `NOT_SUPPORTED`, `NEVER` | Fresh cache (no transaction) |

```kotlin
transaction {
    val user = userRepository.findById(1)  // Cached in outer transaction

    transaction(propagation = NESTED) {
        val sameUser = userRepository.findById(1)  // Cache hit from outer
        // sameUser === user
    }

    transaction(propagation = REQUIRES_NEW) {
        val differentUser = userRepository.findById(1)  // Fresh query, new cache
        // differentUser !== user (different transaction, different instance)
    }
}
```

---

## Configuration Reference

| Property | Default | Description |
|----------|---------|-------------|
| `storm.entity_cache.retention` | `default` | Cache retention: `default` or `light` |

---

## Query-Level Identity

Even without transaction-level caching, Storm preserves entity identity within a single query result. When the same entity appears multiple times in one query (e.g., through joins), Storm interns these to the same object instance. This happens automatically during result set hydration.

```kotlin
// Even at READ_COMMITTED in a read-write transaction:
val orders = orderRepository.findAll(Order_.status eq "pending")

// If order1 and order2 have the same customer, they share the instance
val customer1 = orders[0].customer.fetch()
val customer2 = orders[1].customer.fetch()  // Same instance if same customer
```

**Why this matters:**
- **Memory efficiency:** Duplicate entities in a result set share one instance
- **Consistent identity:** Within a query, `===` works as expected for same-row entities

**Relationship with transaction-level caching:**
- At `READ_COMMITTED` or lower: Identity preserved within each query, but separate queries may return different instances
- At `REPEATABLE_READ` or higher: Query-level identity is extended transaction-wide via the cache

For details on how the query interner works during hydration, see [Hydration](hydration.md#query-level-identity-interning).

---

## Best Practices

### 1. Choose the Right Isolation Level

When no isolation level is explicitly set, Storm uses the database default (typically `READ_COMMITTED` for most databases). Use higher isolation levels only when you have a specific consistency requirement:

```kotlin
// Database default: Fresh data on each read
transaction {
    val user = userRepository.findById(1)
    // ... later ...
    val freshUser = userRepository.findById(1)  // Fresh database query
}

// REPEATABLE_READ: Consistent snapshot, cached instances, more locking
transaction(isolation = REPEATABLE_READ) {
    val user = userRepository.findById(1)
    val sameUser = userRepository.findById(1)  // Cache hit, same instance
}
```

See [Transactions](transactions.md#isolation-levels) for guidance on choosing isolation levels.

### 2. Leverage Ref.fetch() Caching

When navigating relationships, `Ref.fetch()` automatically uses the cache:

```kotlin
transaction {
    // Load orders with their users
    val orders = orderRepository.findAll(Order_.status eq "pending")

    // If multiple orders share the same user, only one query per unique user
    orders.forEach { order ->
        val user = order.user.fetch()  // Cached after first fetch per user
        println("${order.id} belongs to ${user.name}")
    }
}
```

### 3. Batch Lookups for Cache Efficiency

When you need multiple entities, use batch lookups to optimize cache interaction:

```kotlin
transaction {
    // Efficient: single query for cache misses
    val users = userRepository.select(userIds)

    // Less efficient: N queries (though cached results help)
    val users = userIds.map { userRepository.findById(it) }
}
```

### 4. Keep Transactions Focused

Since the cache is transaction-scoped, long-running transactions accumulate cached entities. Keep transactions focused on specific operations to maintain predictable memory usage.
