import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Dirty Checking

## What Is Dirty Checking?

Dirty checking is the process of determining which fields of an entity have changed since it was loaded from the database. When you update an entity, the ORM needs to decide:

1. **Whether** to execute an UPDATE statement at all
2. **Which columns** to include in the UPDATE statement

Storm's entities are **stateless** and **immutable** by design: plain Kotlin data classes or Java records with no proxies, no bytecode manipulation, and no hidden state. This design simplifies the dirty checking logic and allows for high performance.

Instead of tracking changes implicitly, Storm:

1. **Observes** entity state when you read from the database
2. **Compares** entity state when you call `update()` within the same transaction
3. **Generates** the appropriate UPDATE statement based on the configured mode

Observed state is stored in the transaction context, not on the entity itself. This keeps entities simple and predictable while still providing intelligent update behavior.

```
┌─────────────────────────────────────────────────────────────────┐
│                      Transaction Scope                          │
│                                                                 │
│   ┌─────────┐         ┌──────────────┐         ┌─────────┐      │
│   │  READ   │────────▶│   Observed   │────────▶│ UPDATE  │      │
│   │ Entity  │         │    State     │         │ Called  │      │
│   └─────────┘         │   (cached)   │         └────┬────┘      │
│                       └──────────────┘              │           │
│                              │                      │           │
│                              ▼                      ▼           │
│                       ┌──────────────────────────────┐          │
│                       │    Compare current entity    │          │
│                       │    with observed state       │          │
│                       └──────────────┬───────────────┘          │
│                                      │                          │
│                    ┌─────────────────┼─────────────────┐        │
│                    ▼                 ▼                 ▼        │
│              ┌──────────┐     ┌──────────┐     ┌──────────┐     │
│              │ No change│     │ Some     │     │ Some     │     │
│              │ detected │     │ changed  │     │ changed  │     │
│              └────┬─────┘     │ (ENTITY) │     │ (FIELD)  │     │
│                   │           └────┬─────┘     └────┬─────┘     │
│                   ▼                ▼                ▼           │
│              Skip UPDATE     Full-row UPDATE   Partial UPDATE   │
│                                                                 │
└─────────────────────────────────────────────────────────────────┘
```

**Key insight:** Dirty checking in Storm is scoped to a single transaction. Once the transaction commits, all observed state is discarded. This keeps memory usage predictable and avoids the complexity of managing detached entities.

Entity cache misses can affect dirty checking behavior. When an entity is not found in the cache, Storm falls back to a full-row update. See [Entity Cache](entity-cache.md) for cache retention configuration.

---

## Update Modes

Storm supports three update modes, each representing a different trade-off between SQL efficiency, batching potential, and write amplification:

| Mode | Dirty Check | UPDATE Behavior | SQL Variability |
|------|-------------|-----------------|-----------------|
| `OFF` | None | Always update all columns | Single shape |
| `ENTITY` | Entity-level | Skip if unchanged; full row if any changed | Single shape |
| `FIELD` | Field-level | Update only changed columns | Multiple shapes |

The selected update mode controls:
- **Whether** an UPDATE is executed (can be skipped if nothing changed)
- **What** gets updated (all columns vs. only changed columns)
- **How predictable** the generated SQL is (affects batching and caching)

### Choosing the Right Mode

```
                    ┌─────────────────────────────────────┐
                    │  What kind of workload do you have? │
                    └─────────────────┬───────────────────┘
                                      │
              ┌───────────────────────┼───────────────────────┐
              ▼                       ▼                       ▼
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
    │  Batch/ETL      │    │  Typical CRUD   │    │  Wide tables    │
    │  processing     │    │  application    │    │  or hot rows    │
    └────────┬────────┘    └────────┬────────┘    └────────┬────────┘
             │                      │                      │
             ▼                      ▼                      ▼
    ┌─────────────────┐    ┌─────────────────┐    ┌─────────────────┐
    │   Use: OFF      │    │  Use: ENTITY    │    │   Use: FIELD    │
    │                 │    │   (default)     │    │                 │
    │ Maximum batch   │    │ Good balance of │    │ Minimal write   │
    │ efficiency      │    │ efficiency and  │    │ amplification   │
    │                 │    │ simplicity      │    │                 │
    └─────────────────┘    └─────────────────┘    └─────────────────┘
```

---

## UpdateMode.OFF

In `OFF` mode, Storm bypasses dirty checking entirely. Every call to `update()` generates a full UPDATE statement that writes all columns, regardless of whether values have actually changed.

```kotlin
val user = orm.find { User_.id eq 1 }
val updatedUser = user.copy(name = "New Name")

// Always generates: UPDATE user SET email=?, name=?, city_id=? WHERE id=?
// All columns are included, even though only 'name' changed
orm update updatedUser
```

No comparison is performed; every update is unconditional.

### When to Use OFF Mode

**Batch processing and ETL:** When importing or transforming large datasets, you often want predictable, unconditional writes. OFF mode gives you maximum batching efficiency because every UPDATE has the same shape.

```kotlin
// Processing 10,000 records - all UPDATEs have identical structure
// JDBC can batch them efficiently
userRepository.update(users.map { processUser(it) })
```

**Simple applications:** If your entities are small and updates are infrequent, the overhead of dirty checking may not be worth the complexity. OFF mode keeps things straightforward.

**Characteristics**
- Single, stable SQL shape (enables efficient JDBC batching)
- Zero CPU overhead (no comparisons to perform)
- Maximum predictability

**Trade-offs**
- Updates may write unchanged values to the database
- Cannot skip unnecessary UPDATEs
- May cause more database trigger activity if triggers fire on any UPDATE

---

## UpdateMode.ENTITY (Default)

`ENTITY` mode is Storm's default and provides a balanced approach. Storm checks entities against the observed state from when the entity was read. Based on this comparison:

- If **same instance**: No UPDATE is executed
- If **no field changed**: No UPDATE is executed (individual fields are checked when needed)
- If **any field changed**: A full-row UPDATE is executed (all columns are written)

```kotlin
val user = orm.get { User_.id eq 1 }  // Storm observes: {id=1, email="a@b.com", name="Alice"}

// Scenario 1: No changes
orm update user  // No SQL executed - entity unchanged

// Scenario 2: Any field changed
val updated = user.copy(name = "Bob")
orm update updated  // UPDATE user SET email=?, name=?, city_id=? WHERE id=?
                    // Full row update, even though only 'name' changed
```

### Why Full-Row Updates?

You might wonder: if Storm knows only `name` changed, why update all columns? The answer is **batching efficiency**.

When multiple entities of the same type are updated in a transaction, JDBC can batch them together only if they have the same SQL shape. With ENTITY mode, all UPDATEs for a given entity type look identical, enabling efficient batching:

```kotlin
// All updates have identical SQL shape - JDBC batches them
val users = userRepository.findAll { User_.city eq city }
userRepository.update(users.map { it.copy(lastLogin = now()) })
```

### When to Use ENTITY Mode

**Most CRUD applications:** ENTITY mode provides the right balance for typical web applications. It avoids unnecessary database round-trips when nothing changed, while maintaining predictable SQL patterns.

**Read-modify-write patterns:** When you load an entity and pass it back to update without modifications, ENTITY mode skips the UPDATE entirely.

```kotlin
val user = orm.get { User_.id eq userId }

// No changes made - UPDATE is skipped
orm update user

// Conditional modification - UPDATE only if actually changed
val updated = if (shouldUpdate) user.copy(name = "New Name") else user
orm update updated
```

**Characteristics**
- UPDATE suppression when nothing changed
- Stable SQL shape per entity (enables batching)
- Low memory overhead (stores one copy of observed state per entity)
- Minimal CPU overhead (single comparison per update)

**Trade-offs**
- Writes unchanged columns when any field is dirty
- Requires storing observed state in memory during transaction

---

## UpdateMode.FIELD

`FIELD` mode provides the most granular control. Storm compares each field individually and generates UPDATE statements that include only the columns that actually changed. Like ENTITY mode, if no fields changed, Storm skips the UPDATE entirely.

```kotlin
val user = orm.get { User_.id eq 1 }  // {id=1, email="a@b.com", name="Alice", bio="...", settings="..."}

// Only name changed
val updated = user.copy(name = "Bob")
orm update updated
// UPDATE user SET name=? WHERE id=?

// Multiple fields changed
val updated2 = user.copy(name = "Bob", email = "bob@example.com")
orm update updated2
// UPDATE user SET name=?, email=? WHERE id=?
```

### Why Use Field-Level Updates?

**Reduced write amplification:** When you have wide tables (many columns) but typically only change a few fields, FIELD mode avoids writing unchanged data. This can significantly reduce I/O, especially for tables with large TEXT or BLOB columns.

```kotlin
// Article has 20 columns including large 'content' field
// But we're only updating the view count
val article = orm.find { Article_.id eq articleId }
orm update article.copy(viewCount = article.viewCount + 1)
// UPDATE article SET view_count=? WHERE id=?
// The large 'content' column is NOT written
```

**Reduced database overhead:** Updating fewer columns reduces redo/undo log volume, replication payload size, and avoids rewriting large column values unnecessarily.

**Reduced trigger activity:** If your database has column-specific triggers, FIELD mode ensures they only fire when their columns actually change.

### Understanding SQL Shape Variability

The trade-off with FIELD mode is that it generates different SQL statements depending on which fields changed:

```
┌──────────────────────────────────────────────────────────────────┐
│                    FIELD Mode SQL Shapes                         │
├──────────────────────────────────────────────────────────────────┤
│                                                                  │
│  Change: name only                                               │
│  SQL:    UPDATE user SET name=? WHERE id=?                       │
│                                                                  │
│  Change: email only                                              │
│  SQL:    UPDATE user SET email=? WHERE id=?                      │
│                                                                  │
│  Change: name + email                                            │
│  SQL:    UPDATE user SET name=?, email=? WHERE id=?              │
│                                                                  │
│  Change: name + email + city_id                                  │
│  SQL:    UPDATE user SET name=?, email=?, city_id=? WHERE id=?   │
│                                                                  │
│  ... potentially many more combinations ...                      │
│                                                                  │
└──────────────────────────────────────────────────────────────────┘
```

This variability has two consequences:

1. **Reduced batching:** JDBC can only batch statements with identical SQL. Different update patterns cannot be batched together.

2. **Statement cache pressure:** Databases cache prepared statements for reuse. Many distinct SQL shapes consume more cache memory and reduce cache hit rates.

Storm mitigates this with a [max shapes limit](#max-shapes-limit) that automatically falls back to full-row updates when too many shapes are generated.

### When to Use FIELD Mode

**Wide tables:** Tables with many columns where updates typically touch only a few fields.

**High-contention rows:** When multiple transactions frequently update the same rows (e.g., counters, status fields), updating fewer columns reduces conflict potential.

**Large column values:** Tables with TEXT, BLOB, or JSON columns where rewriting unchanged large values is wasteful.

**Characteristics**
- Skips UPDATE entirely if nothing changed
- Minimal write amplification
- Reduced redo/undo and replication overhead
- Efficient for wide tables with sparse updates

**Trade-offs**
- Multiple SQL shapes reduce batching efficiency
- Higher statement cache usage
- More CPU overhead for field-level comparison

---

## Configuring Update Mode Per Entity

Use the `@DynamicUpdate` annotation to specify the update mode for individual entity classes. This allows you to use different strategies for different entities based on their characteristics.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DynamicUpdate(FIELD)
data class User(
    @PK val id: Int = 0,
    val email: String,
    val name: String,
    @FK val city: City
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DynamicUpdate(FIELD)
record User(@PK Integer id,
            @Nonnull String email,
            @Nonnull String name,
            @FK City city
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

### How It Works

The `@DynamicUpdate` annotation is processed at compile time by Storm's KSP processor (Kotlin) or annotation processor (Java). The update mode is encoded in the generated metamodel class (`User_`), so there's no runtime reflection cost.

```
┌─────────────────────┐      Compile Time      ┌─────────────────────┐
│                     │                        │                     │
│   @DynamicUpdate    │  ───────────────────▶  │   User_ metamodel   │
│   data class User   │    Annotation          │   updateMode=FIELD  │
│                     │    Processor           │                     │
└─────────────────────┘                        └─────────────────────┘
                                                         │
                                                         │ Runtime
                                                         ▼
                                               ┌─────────────────────┐
                                               │  Storm reads mode   │
                                               │  from metamodel     │
                                               │  (no reflection)    │
                                               └─────────────────────┘
```

### Mixing Modes in an Application

Different entities can use different update modes based on their characteristics:

```kotlin
// Wide table with large content - use FIELD mode
@DynamicUpdate(FIELD)
data class Article(
    @PK val id: Int = 0,
    val title: String,
    val content: String,  // Large TEXT column
    val metadata: String  // JSON blob
) : Entity<Int>

// Simple entity with frequent batch updates - use ENTITY mode (default)
data class AuditLog(
    @PK val id: Int = 0,
    val action: String,
    val timestamp: Instant
) : Entity<Int>

// High-throughput batch processing - use OFF mode
@DynamicUpdate(OFF)
data class MetricSample(
    @PK val id: Int = 0,
    val value: Double,
    val timestamp: Instant
) : Entity<Int>
```

---

## Dirty Checking Strategy

When comparing an entity to its observed state, Storm needs to determine whether each field has changed. Storm supports two strategies for this comparison. Both strategies are correct; this is purely a performance tuning choice.

### Instance-Based (Default)

Instance-based checking treats a field as changed when the object reference differs (`!=` identity comparison). This is the fastest option because reference comparison is a single pointer check with no method dispatch. It works correctly in the vast majority of cases because Kotlin's `copy()` and Java's `with...()` patterns create new instances for modified fields while reusing the same references for unchanged fields.

The only scenario where instance-based checking produces a false positive is when you construct a new object with identical content. For example, `user.copy(name = user.name)` creates a new `String` reference for `name`, even though the value is unchanged. In practice, this is rare and the cost of an extra column in the UPDATE is negligible.

### Value-Based

Value-based checking compares field values using `equals()`. This avoids false positives from the scenario described above, at the cost of calling `equals()` on every field during comparison. For entities with simple fields (primitives, strings), the overhead is minimal. For entities with complex nested objects or large collections, the `equals()` calls can become measurable.

Choose value-based checking when your update patterns frequently reconstruct fields with identical values, and you want to minimize unnecessary column writes. In most applications, instance-based checking is sufficient.

**Enabling Value-Based Checking**

Per entity:

```kotlin
@DynamicUpdate(value = FIELD, dirtyCheck = VALUE)
data class User(
    @PK val id: Int = 0,
    val email: String
) : Entity<Int>
```

Globally via `StormConfig` or system property:

```kotlin
val config = StormConfig.of(mapOf(UPDATE_DIRTY_CHECK to "VALUE"))
```

```bash
-Dstorm.update.dirty_check=VALUE
```

---

## Max Shapes Limit

When using `FIELD` mode, Storm generates different SQL statements depending on which columns changed. For an entity with N columns, there could theoretically be 2^N different UPDATE shapes (though in practice, it's far fewer).

### The Problem

Databases maintain a **prepared statement cache** to avoid re-parsing SQL. Each distinct SQL shape consumes cache memory. If your application generates too many shapes, you can:

1. **Exhaust statement cache memory**, causing evictions and re-parsing
2. **Reduce cache hit rates**, degrading performance
3. **Lose batching benefits**, as only identical statements can be batched

### Storm's Solution

Storm enforces a **maximum number of UPDATE shapes per entity**. Once this limit is reached, Storm automatically falls back to full-row updates for that entity, ensuring bounded resource usage.

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Max Shapes Protection                            │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Shape 1:  UPDATE user SET name=? WHERE id=?                        │
│  Shape 2:  UPDATE user SET email=? WHERE id=?                       │
│  Shape 3:  UPDATE user SET name=?, email=? WHERE id=?               │
│  Shape 4:  UPDATE user SET city_id=? WHERE id=?                     │
│  Shape 5:  UPDATE user SET name=?, city_id=? WHERE id=?             │
│  ─────────────────────────────────────────────────────────────────  │
│  LIMIT REACHED (small default, e.g., 5)                             │
│  ─────────────────────────────────────────────────────────────────  │
│  Shape 6+: UPDATE user SET name=?, email=?, city_id=? WHERE id=?    │
│            (Falls back to full-row update)                          │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### Configuration

**Default:** 5 shapes per entity

**Configure via `StormConfig` or system property:**

```kotlin
val config = StormConfig.of(mapOf(UPDATE_MAX_SHAPES to "10"))
```

```bash
-Dstorm.update.max_shapes=10
```

### Choosing the Right Limit

- **Lower values (3-5):** Better for applications with many entity types or limited database memory. Ensures predictable caching behavior.

- **Higher values (10-20):** Appropriate when you have few entity types and want maximum write efficiency. Monitor your database's statement cache usage.

- **Very high values (50+):** Generally not recommended. If you need this many shapes, consider whether FIELD mode is appropriate for your use case.

**Tip:** Monitor your database's prepared statement cache metrics in production. If you see high eviction rates, consider lowering the max shapes limit.

---

## Configuration Reference

Storm's dirty checking behavior can be configured at multiple levels: via `StormConfig`, system properties, or per-entity annotations. Entity-level configuration always takes precedence over `StormConfig` defaults, and `StormConfig` values take precedence over system properties.

### Properties

| Property | Default | Description |
|----------|---------|-------------|
| `storm.update.default_mode` | `ENTITY` | Default update mode for entities without `@DynamicUpdate` |
| `storm.update.dirty_check` | `INSTANCE` | Default dirty check strategy (`INSTANCE` or `VALUE`) |
| `storm.update.max_shapes` | `5` | Maximum UPDATE shapes before fallback to full-row |

For cache retention settings, see [Entity Cache Configuration](entity-cache.md#configuration-reference).

**Example: Setting properties**

```kotlin
// Via StormConfig
val config = StormConfig.of(mapOf(
    UPDATE_DEFAULT_MODE to "FIELD",
    UPDATE_DIRTY_CHECK to "VALUE",
    UPDATE_MAX_SHAPES to "10"
))
val orm = ORMTemplate.of(dataSource, config)
```

```bash
# Or via JVM arguments (used as fallback when not set in StormConfig)
java -Dstorm.update.default_mode=FIELD \
     -Dstorm.update.dirty_check=VALUE \
     -Dstorm.update.max_shapes=10 \
     -jar myapp.jar
```

### Per-Entity Annotation

The `@DynamicUpdate` annotation provides fine-grained control per entity:

```kotlin
@DynamicUpdate(OFF)                           // No dirty checking
@DynamicUpdate(ENTITY)                        // Entity-level (default)
@DynamicUpdate(FIELD)                         // Field-level updates
@DynamicUpdate(FIELD, dirtyCheck = VALUE)     // Field-level with value comparison
```

### Configuration Precedence

```
┌─────────────────────────────────────────────────────────────┐
│                 Configuration Precedence                    │
├─────────────────────────────────────────────────────────────┤
│                                                             │
│  1. @DynamicUpdate annotation on entity class               │
│     ↓ (if not present)                                      │
│  2. StormConfig property                                    │
│     ↓ (if not set)                                          │
│  3. System property (-Dstorm.update.default_mode)           │
│     ↓ (if not set)                                          │
│  4. Built-in default (ENTITY mode, INSTANCE checking)       │
│                                                             │
└─────────────────────────────────────────────────────────────┘
```

Entity-level annotations always override global settings. This allows you to set a sensible default globally while customizing specific entities that have different requirements.

---

## Entity Cache Integration

Dirty checking relies on Storm's [entity cache](entity-cache.md), which stores observed entity state within a transaction. The cache serves multiple purposes beyond dirty checking. See the [Entity Cache](entity-cache.md) documentation for details on:

- Cache behavior at different isolation levels
- Memory management and retention configuration
- Query optimization and cache-first lookups

**Key point for dirty checking:** Cache writes for observed state occur at all isolation levels when dirty checking is enabled. This means dirty checking works even at `READ_COMMITTED` and `READ_UNCOMMITTED`, where cached instances are not returned on reads.

---

## Important Notes

### 1. Dirty Checking Is Not Optimistic Locking

A common misconception is that dirty checking prevents concurrent modification conflicts. **It does not.**

Dirty checking determines *what to update*. Optimistic locking determines *whether the update is safe* based on concurrent modifications by other transactions.

```kotlin
// This does NOT prevent lost updates:
val user = orm.find { User_.id eq 1 }
// ... another transaction modifies the same user ...
orm update user.copy(name = "New Name")  // May overwrite other transaction's changes!

// For conflict detection, use @Version:
data class User(
    @PK val id: Int = 0,
    @Version val version: Int = 0,  // Incremented on each update
    val name: String
) : Entity<Int>

// Now concurrent modifications are detected:
orm update user.copy(name = "New Name")
// Throws OptimisticLockException if version changed since read
```

### 2. Raw SQL Mutations Clear All Observed State

Storm tracks which entity types are affected by each mutation so it can selectively invalidate observed state. For template-based updates (using `orm update entity`), Storm knows the entity type and only clears observed state of that type. However, when you execute raw SQL mutations without entity type information, Storm cannot determine which entities may have been affected. Rather than risk stale comparisons that could silently skip a necessary UPDATE, Storm clears all observed state in the current transaction:

```kotlin
val user = orm.get { User_.id eq 1 }     // Storm observes User state
val city = orm.get { City_.id eq 100 }   // Storm observes City state

// Raw SQL mutation - Storm clears all observed state
orm.execute("UPDATE user SET name = 'Changed' WHERE id = 1")

// All observed state is now invalidated
orm update user.copy(email = "new@example.com")  // Falls back to full-row update
orm update city.copy(name = "New City")          // Also falls back to full-row update
```

This ensures correctness at the cost of losing dirty checking optimization for the remainder of the transaction.

### 3. Nested Records Are Inspected

Storm's dirty checking is not limited to top-level fields. When entities contain embedded records or value objects, Storm flattens the nested structure and inspects individual columns. This means that changing a single field inside a nested record produces a targeted UPDATE rather than rewriting the entire nested structure.

```kotlin
data class Address(val street: String, val city: String)

data class User(
    @PK val id: Int = 0,
    val name: String,
    @Embedded val address: Address
) : Entity<Int>

val user = orm.find { User_.id eq 1 }
// With FIELD mode: only changed columns in Address are updated
orm update user.copy(address = user.address.copy(city = "New City"))
// UPDATE user SET city=? WHERE id=?
```

### 4. Generated Metamodel Improves Performance

Storm uses compile-time generated metamodel classes for dirty checking operations. This provides several advantages:

- **No reflection:** Field access is direct, not reflective
- **No boxing:** Primitive values are compared without boxing overhead
- **Type safety:** Comparison operations are type-checked at compile time
- **Optimized paths:** The generated code is specialized for each entity

Ensure your build is configured to run the KSP (Kotlin) or annotation processor (Java) to generate metamodel classes. If the metamodel is not available, Storm falls back to reflection.

---

## Best Practices

### 1. Start with ENTITY Mode

For most applications, the default `ENTITY` mode provides the right balance:

- Skips unnecessary updates when nothing changed
- Maintains stable SQL shapes for batching
- Low memory and CPU overhead

Only switch to `FIELD` mode when you have a specific need (wide tables, high contention, large columns).

### 2. Use FIELD Mode Strategically

Reserve `FIELD` mode for entities where it provides clear benefits:

```kotlin
// Good candidate for FIELD mode:
// - 20+ columns
// - Large TEXT content column
// - Typically only 1-2 fields change per update
@DynamicUpdate(FIELD)
data class Article(
    @PK val id: Int,
    val title: String,
    val content: String,      // Large
    val metadata: String,     // JSON blob
    val viewCount: Int,       // Frequently updated alone
    // ... many more fields
) : Entity<Int>

// Poor candidate for FIELD mode:
// - Only 4 columns
// - All fields typically change together
// - Batched frequently
data class AuditEntry(       // Keep ENTITY mode
    @PK val id: Int,
    val action: String,
    val userId: Int,
    val timestamp: Instant
) : Entity<Int>
```

### 3. Always Use @Version for Concurrency Control

Dirty checking answers "what changed?" but not "did someone else change this?"

```kotlin
data class Account(
    @PK val id: Int = 0,
    @Version val version: Int = 0,  // Always include for concurrent access
    val balance: BigDecimal
) : Entity<Int>
```

Without `@Version`, concurrent updates can silently overwrite each other (lost update problem).

### 4. Match Mode to Workload

```
┌─────────────────────────────────────────────────────────────────────┐
│                    Mode Selection Guide                             │
├─────────────────────────────────────────────────────────────────────┤
│                                                                     │
│  Workload                              Recommended Mode             │
│  ──────────────────────────────────    ─────────────────            │
│  Typical CRUD application              ENTITY (default)             │
│  Batch import/export                   OFF                          │
│  Wide tables (20+ columns)             FIELD                        │
│  Tables with BLOB/TEXT columns         FIELD                        │
│  High-contention rows                  FIELD                        │
│  Event sourcing / audit logs           OFF                          │
│  Mixed workload                        ENTITY + selective FIELD     │
│                                                                     │
└─────────────────────────────────────────────────────────────────────┘
```

### 5. Monitor in Production

If using `FIELD` mode extensively, monitor:

- **Database statement cache:** Watch for high eviction rates
- **Query execution plans:** Ensure varied SQL shapes don't cause plan instability
- **Batch sizes:** Verify batching is still effective for your use case

Most databases provide metrics for prepared statement cache usage. If you see degradation, consider:
- Lowering `storm.update.max_shapes`
- Switching some entities back to `ENTITY` mode
- Increasing database statement cache size
