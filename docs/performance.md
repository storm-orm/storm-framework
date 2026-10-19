import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Performance

Storm is designed to add minimal overhead on top of JDBC. In most applications, the bottleneck is the database itself, not the ORM layer. Still, understanding how Storm processes queries, caches compiled templates, and manages entity state helps you make informed decisions about configuration and optimization.

This page covers Storm's internal performance mechanisms, the configuration properties that control them, and the JMX metrics you can use to monitor behavior in production.

---

## Query Execution Model

When you execute a query through Storm, the framework performs these steps:

```
1. Template Compilation     Parse the query template, resolve entity mappings,
                            and generate the SQL string with ? placeholders.

2. Cache Lookup             Check the template cache for a previously compiled
                            result with the same shape.

3. Parameter Binding        Bind runtime values to the compiled SQL template.

4. JDBC Execution           Send the PreparedStatement to the database via JDBC.

5. Result Mapping           Map result set rows to record instances.
```

Steps 1 and 2 are where Storm's compilation cache provides its largest performance benefit. Steps 4 and 5 are dominated by database I/O and are largely outside the framework's control.

---

## Template Compilation Cache

The compilation cache is Storm's most significant performance optimization. SQL template compilation involves parsing the template structure, resolving entity metadata, generating column lists, and building the final SQL string. This work is substantial, and the compilation cache avoids repeating it.

### How It Works

Each unique template shape (the combination of entity types, column selections, and query structure) produces a compiled result that is stored in a bounded LRU cache. When the same template shape is requested again, the cached result is reused and only the runtime parameter binding step is repeated.

The performance difference is significant: a cache hit typically completes in single-digit microseconds, while a cache miss (full compilation) can take tens to hundreds of microseconds depending on entity complexity.

```
First request (cache miss):    ~100-500 us    Full compilation
Subsequent requests (cache hit): ~1-10 us     Reuse compiled result
```

### Configuration

The cache size is configured via the `storm.template_cache.size` property:

| Property | Default | Description |
|---|---|---|
| `storm.template_cache.size` | `2048` | Maximum number of compiled templates to cache. Set to `0` to disable caching. |

With Spring Boot, use the `storm.template-cache.size` property in `application.yml`:

```yaml
storm:
  template-cache:
    size: 4096
```

Or configure programmatically:

```java
StormConfig config = StormConfig.of(Map.of(
    TEMPLATE_CACHE_SIZE, "4096"
));
ORMTemplate orm = ORMTemplate.of(dataSource, config);
```

For most applications, the default of 2048 is sufficient. If you have a large number of distinct query shapes (hundreds of different entity types or complex dynamic queries), consider increasing it. Monitor the hit ratio via JMX to determine if the cache is sized appropriately.

---

## Entity Cache

Storm maintains a transaction-scoped entity cache that serves multiple purposes: avoiding redundant database round-trips, preserving object identity within a transaction, and providing the baseline for dirty checking.

### Transaction Scope

The entity cache is created when a transaction begins and discarded when it commits or rolls back. There is no second-level or cross-transaction cache. This design avoids cache coherency problems and aligns with standard transaction isolation semantics.

### Isolation-Level Awareness

The cache behavior adapts to your transaction isolation level:

| Isolation Level | Cache Behavior |
|---|---|
| `READ_UNCOMMITTED` | Observation is disabled by default. All entities are treated as dirty. |
| `READ_COMMITTED` | Observation is enabled. Cache serves dirty checking. |
| `REPEATABLE_READ` | Full caching. Returning cached instances matches database guarantees. |
| `SERIALIZABLE` | Full caching. Same as `REPEATABLE_READ`. |

### Cache Retention

The `storm.entity_cache.retention` property controls how long cached entity state is retained:

| Value | Description |
|---|---|
| `DEFAULT` | Retained for the duration of the transaction. Higher memory usage, better dirty-check hit rate. |
| `LIGHT` | May be cleaned up when the application no longer holds a reference. Lower memory usage, but may cause dirty-check cache misses. |

```yaml
storm:
  entity-cache:
    retention: LIGHT
```

### Hit and Miss Patterns

A cache **hit** occurs when Storm finds a previously observed entity by primary key. This means the entity was already read in the current transaction and can be returned immediately (or used as the dirty-check baseline) without a database round-trip.

A cache **miss** occurs when the entity is not in the cache. This results in a database query and the entity being stored in the cache for future use.

For dirty checking specifically, a miss means no baseline is available and Storm falls back to a full-row update (all columns are included regardless of what changed).

---

## Dirty Checking Costs

When dirty checking is enabled (via `@DynamicUpdate` or the `storm.update.default_mode` property), Storm compares entity state before generating UPDATE statements. The cost of this comparison depends on the strategy used:

### INSTANCE vs VALUE Comparison

| Strategy | How It Works | Performance | Trade-off |
|---|---|---|---|
| `INSTANCE` | Compares field references using `==` (identity). | Very fast; no value inspection. | Treats structurally equal but different instances as dirty. |
| `VALUE` | Compares field values using `equals()`. | Depends on field types and `equals()` cost. | More precise; only truly changed fields are dirty. |

The default strategy is `INSTANCE`, which is fast and sufficient for most applications. If you construct entities by copying with modifications, `INSTANCE` will detect the change because the field references differ, even if the values are the same. Use `VALUE` when precision is more important than speed (for example, when `equals()` is cheap and unnecessary updates are expensive).

### When FIELD Mode Helps

With `UpdateMode.FIELD`, Storm generates UPDATE statements that include only the dirty columns. This reduces write amplification and lock scope in the database. However, it introduces additional overhead:

- **Shape diversity:** Each unique combination of dirty columns produces a distinct SQL shape. These shapes are cached, but too many shapes can reduce cache effectiveness.
- **Shape limit:** The `storm.update.max_shapes` property (default: `5`) limits the number of shapes per entity type. Beyond this limit, Storm falls back to full-row updates to preserve batching efficiency.

```yaml
storm:
  update:
    default-mode: FIELD
    dirty-check: VALUE
    max-shapes: 10
```

### Configuration Properties

| Property | Default | Description |
|---|---|---|
| `storm.update.default_mode` | `ENTITY` | Default update mode: `OFF`, `ENTITY`, or `FIELD`. |
| `storm.update.dirty_check` | `INSTANCE` | Default dirty check strategy: `INSTANCE` or `VALUE`. |
| `storm.update.max_shapes` | `5` | Maximum distinct UPDATE shapes per entity type before falling back to full-row updates. |

---

## Batch Operations

Batch operations group multiple SQL statements into a single JDBC round-trip. Storm automatically uses JDBC batching when you pass collections to `insert`, `update`, `remove`, or `upsert`.

### Performance Characteristics

Batching reduces the number of network round-trips from N (one per entity) to 1 (or a few, depending on batch size). The performance improvement depends on network latency and database server efficiency:

- **Low-latency connections** (same host or same datacenter): 2-5x improvement.
- **High-latency connections** (cross-region): 10-100x improvement.

### Streaming with Batch Size

For large data sets that do not fit in memory, use the streaming batch methods:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Insert a stream of entities in batches of 1000.
orm.entity(User::class).insert(userStream, batchSize = 1000)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Insert a stream of entities in batches of 1000.
orm.entity(User.class).insert(userStream, 1000);
```

</TabItem>
</Tabs>

The batch size controls the trade-off between memory usage and database efficiency. Larger batches use more memory but reduce the number of round-trips. A batch size of 100-1000 is a good starting point for most applications.

---

## Connection Management

Storm does not manage connections or connection pools. It receives a `DataSource` from your application and acquires connections through it. This means connection pooling is your responsibility.

### Recommended Setup

HikariCP is the recommended connection pool for Storm applications. It is the default for Spring Boot applications.

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
      idle-timeout: 600000
      max-lifetime: 1800000
```

Key sizing considerations:

- **`maximum-pool-size`:** Should match your application's concurrency level. A common formula is `connections = (2 * CPU cores) + disk spindles`. For most applications, 10-20 is sufficient.
- **`minimum-idle`:** Set equal to `maximum-pool-size` for fixed-size pools, or lower for variable workloads.
- **`connection-timeout`:** How long a thread waits for a connection before throwing an exception. Set this lower than your application's request timeout.

---

## JMX Metrics

Storm registers three MXBeans that provide runtime visibility into template compilation, entity caching, and dirty checking. These metrics are available through any JMX client (JConsole, VisualVM, Prometheus JMX exporter, etc.).

### Template Metrics

**MBean name:** `st.orm:type=TemplateMetrics`

| Attribute | Type | Description |
|---|---|---|
| `Requests` | `long` | Total number of template requests. |
| `Hits` | `long` | Number of cache hits. |
| `Misses` | `long` | Number of cache misses. |
| `HitRatioPercent` | `long` | Hit ratio as a percentage (0-100). |
| `AvgRequestMicros` | `long` | Average request duration in microseconds. |
| `MaxRequestMicros` | `long` | Maximum request duration in microseconds. |
| `AvgHitMicros` | `long` | Average cache hit duration in microseconds. |
| `MaxHitMicros` | `long` | Maximum cache hit duration in microseconds. |
| `AvgMissMicros` | `long` | Average cache miss duration in microseconds. |
| `MaxMissMicros` | `long` | Maximum cache miss duration in microseconds. |
| `TemplateCacheSize` | `int` | Configured cache size. |

**Operation:** `reset()` clears all counters.

**What to look for:**
- A `HitRatioPercent` below 90% suggests the cache is too small or the application has many distinct query shapes. Consider increasing `storm.template_cache.size`.
- A large gap between `AvgHitMicros` and `AvgMissMicros` confirms that caching is providing a significant benefit.

### Entity Cache Metrics

**MBean name:** `st.orm:type=EntityCacheMetrics`

| Attribute | Type | Description |
|---|---|---|
| `Gets` | `long` | Total number of `get()` calls. |
| `GetHits` | `long` | Cache hits (entity found in cache). |
| `GetMisses` | `long` | Cache misses (entity not cached). |
| `GetHitRatioPercent` | `long` | Get hit ratio as a percentage (0-100). |
| `Interns` | `long` | Total number of `intern()` calls (storing entities). |
| `InternHits` | `long` | Intern hits (reused an existing canonical instance). |
| `InternMisses` | `long` | Intern misses (stored a new instance). |
| `InternHitRatioPercent` | `long` | Intern hit ratio as a percentage (0-100). |
| `Removals` | `long` | Entries removed due to entity mutations. |
| `Clears` | `long` | Full cache clears (transaction commit/rollback). |
| `Evictions` | `long` | Entries cleaned up after garbage collection. |
| `RetentionPerEntity` | `Map<String, String>` | Effective cache retention mode per entity type. |

**Operation:** `reset()` clears all counters.

**What to look for:**
- High `Evictions` with `LIGHT` retention suggests the JVM is under memory pressure. Consider switching to `DEFAULT` retention or increasing heap size.
- High `GetHitRatioPercent` indicates the cache is working effectively for identity preservation and query optimization.

### Dirty Check Metrics

**MBean name:** `st.orm:type=DirtyCheckMetrics`

| Attribute | Type | Description |
|---|---|---|
| `Checks` | `long` | Total number of dirty checks performed. |
| `Clean` | `long` | Checks that found the entity clean (update skipped). |
| `Dirty` | `long` | Checks that found the entity dirty (update triggered). |
| `CleanRatioPercent` | `long` | Percentage of checks where the update was skipped. |
| `IdentityMatches` | `long` | Checks resolved by identity comparison (`cached == entity`). |
| `CacheMisses` | `long` | Checks where no cached baseline was available (fallback to full update). |
| `EntityModeChecks` | `long` | Checks using `ENTITY` update mode. |
| `FieldModeChecks` | `long` | Checks using `FIELD` update mode. |
| `InstanceStrategyChecks` | `long` | Checks using `INSTANCE` dirty check strategy. |
| `ValueStrategyChecks` | `long` | Checks using `VALUE` dirty check strategy. |
| `FieldComparisons` | `long` | Total individual field comparisons across all checks. |
| `FieldClean` | `long` | Field comparisons where the field was clean. |
| `FieldDirty` | `long` | Field comparisons where the field was dirty. |
| `EntityTypes` | `long` | Number of distinct entity types that have generated UPDATE shapes. |
| `Shapes` | `long` | Total number of distinct UPDATE statement shapes. |
| `ShapesPerEntity` | `Map<String, Long>` | Number of shapes per entity type. |
| `UpdateModePerEntity` | `Map<String, String>` | Effective update mode per entity type. |
| `DirtyCheckPerEntity` | `Map<String, String>` | Effective dirty check strategy per entity type. |
| `MaxShapesPerEntity` | `Map<String, Integer>` | Configured maximum shapes per entity type. |

**Operation:** `reset()` clears all counters.

**What to look for:**
- A high `CleanRatioPercent` means many updates are being skipped because the entity has not changed. This is the primary benefit of dirty checking.
- `CacheMisses` indicates how often a dirty check falls back to a full update because no baseline was available. High values suggest entities are being updated without being read first in the same transaction.
- `ShapesPerEntity` approaching `MaxShapesPerEntity` indicates that `FIELD` mode is generating many distinct column combinations. Consider raising `storm.update.max_shapes` or switching to `ENTITY` mode for that entity type.
