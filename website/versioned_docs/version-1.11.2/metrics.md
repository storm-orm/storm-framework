import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Metrics

Storm exposes runtime metrics through JMX (Java Management Extensions) MBeans. These metrics give you visibility into template compilation performance, dirty checking behavior, and entity cache efficiency. All MBeans are registered automatically when Storm initializes and aggregate across all `ORMTemplate` instances in the JVM.

To view these metrics, connect to the JVM with any JMX client (JConsole, VisualVM, or your monitoring platform) and navigate to the `st.orm` domain. If your application uses Spring Boot Actuator, the MBeans are also accessible through Actuator's JMX endpoint.

---

## Template Metrics

**MBean name:** `st.orm:type=TemplateMetrics`

Storm compiles SQL templates into reusable prepared statement shapes. This compilation step resolves aliases, derives joins, and expands column lists. The template cache avoids repeating this work for the same query pattern with different parameter values. These metrics help you understand whether the cache is effective.

### Available Attributes

| Attribute | Description |
|-----------|-------------|
| `Requests` | Total number of template requests |
| `Hits` | Number of cache hits |
| `Misses` | Number of cache misses |
| `HitRatioPercent` | Hit ratio as a percentage (0-100) |
| `AvgRequestMicros` | Average request duration in microseconds |
| `MaxRequestMicros` | Maximum request duration in microseconds |
| `AvgHitMicros` | Average cache hit duration in microseconds |
| `MaxHitMicros` | Maximum cache hit duration in microseconds |
| `AvgMissMicros` | Average cache miss duration in microseconds |
| `MaxMissMicros` | Maximum cache miss duration in microseconds |

A high `HitRatioPercent` (above 95%) indicates the cache is working well. If you see frequent misses, your application may have many dynamically constructed query patterns. Consider increasing the cache size via `storm.template_cache.size` (see [Configuration](configuration.md#template-cache-properties)) or reducing the number of distinct query shapes.

### Operations

| Operation | Description |
|-----------|-------------|
| `reset()` | Resets all counters to zero |

---

## Dirty Check Metrics

**MBean name:** `st.orm:type=DirtyCheckMetrics`

Dirty checking determines whether an UPDATE statement is sent to the database and which columns it includes. These metrics aggregate across all dirty checks performed by entity repositories, giving you visibility into how often updates are skipped, which resolution paths are taken, and how your `max_shapes` budget is being used. For background on how dirty checking works, see [Dirty Checking](dirty-checking.md).

### Entity-Level Counters

| Attribute | Description |
|-----------|-------------|
| `Checks` | Total number of dirty checks performed |
| `Clean` | Number of checks that found the entity unchanged (update skipped) |
| `Dirty` | Number of checks that found the entity changed (update triggered) |
| `CleanRatioPercent` | Percentage of checks where the update was skipped (0-100) |

A high `CleanRatioPercent` indicates that many updates are avoided because the entity has not changed since it was read. If this ratio is low and your application frequently calls `update()` on unmodified entities, consider reviewing your update logic.

### Resolution Path Counters

| Attribute | Description |
|-----------|-------------|
| `IdentityMatches` | Checks resolved by identity comparison (`cached == entity`), the cheapest path |
| `CacheMisses` | Checks where no cached baseline was available, causing a fallback to full-entity update |

High `CacheMisses` may indicate that the entity cache is being cleared prematurely. Consider switching from `light` to `default` cache retention if cache misses are frequent. See [Entity Cache](entity-cache.md) for details.

### Mode and Strategy Breakdown

| Attribute | Description |
|-----------|-------------|
| `EntityModeChecks` | Checks that used `ENTITY` update mode (full-row UPDATE on any change) |
| `FieldModeChecks` | Checks that used `FIELD` update mode (column-level UPDATE) |
| `InstanceStrategyChecks` | Checks that used `INSTANCE` strategy (identity-based field comparison) |
| `ValueStrategyChecks` | Checks that used `VALUE` strategy (equality-based field comparison) |

### Field-Level Counters

| Attribute | Description |
|-----------|-------------|
| `FieldComparisons` | Total number of individual field comparisons across all dirty checks |
| `FieldClean` | Number of field comparisons where the field was unchanged |
| `FieldDirty` | Number of field comparisons where the field was different |

### Shape Counters

| Attribute | Description |
|-----------|-------------|
| `EntityTypes` | Number of distinct entity types that have generated UPDATE shapes |
| `Shapes` | Total number of distinct UPDATE statement shapes across all entity types |
| `ShapesPerEntity` | Map of entity type name to the number of shapes for that type |

Compare `ShapesPerEntity` values against the configured `storm.update.max_shapes` to determine if any entity type is exhausting its shape budget. When the limit is reached, Storm falls back to full-row updates for that entity type.

### Per-Entity Configuration

| Attribute | Description |
|-----------|-------------|
| `UpdateModePerEntity` | Map of entity type name to effective update mode (`FIELD`, `ENTITY`, `OFF`) |
| `DirtyCheckPerEntity` | Map of entity type name to effective dirty check strategy (`INSTANCE`, `VALUE`) |
| `MaxShapesPerEntity` | Map of entity type name to configured max shapes limit |

### Operations

| Operation | Description |
|-----------|-------------|
| `reset()` | Resets all counters to zero |

---

## Entity Cache Metrics

**MBean name:** `st.orm:type=EntityCacheMetrics`

Storm maintains a transaction-scoped entity cache that ensures the same database row maps to the same object instance within a single transaction. These metrics aggregate across all transaction-scoped entity caches, providing visibility into cache hit rates, eviction patterns, and retention behavior. For background on how the cache works, see [Entity Cache](entity-cache.md).

### Lookup Counters

| Attribute | Description |
|-----------|-------------|
| `Gets` | Total number of `get()` calls (cache lookups) |
| `GetHits` | Number of lookups that returned a cached entity |
| `GetMisses` | Number of lookups where no cached entity was available |
| `GetHitRatioPercent` | Get hit ratio as a percentage (0-100) |

A low `GetHitRatioPercent` in combination with frequent `update()` calls suggests that entities are being evicted before they can serve as dirty-check baselines. Consider switching to `default` cache retention.

### Intern Counters

| Attribute | Description |
|-----------|-------------|
| `Interns` | Total number of `intern()` calls (cache insertions) |
| `InternHits` | Number of intern calls that reused an existing canonical instance |
| `InternMisses` | Number of intern calls that stored a new or updated instance |
| `InternHitRatioPercent` | Intern hit ratio as a percentage (0-100) |

### Lifecycle Counters

| Attribute | Description |
|-----------|-------------|
| `Removals` | Number of cache entries removed due to entity mutations (insert, update, delete) |
| `Clears` | Number of full cache clears |
| `Evictions` | Number of cache entries cleaned up after garbage collection |

High `Evictions` values indicate that entities are being garbage collected while still in the cache. This is expected with `light` retention but unusual with `default` retention unless the JVM is under memory pressure.

### Per-Entity Configuration

| Attribute | Description |
|-----------|-------------|
| `RetentionPerEntity` | Map of entity type name to effective retention mode (`DEFAULT`, `LIGHT`) |

### Operations

| Operation | Description |
|-----------|-------------|
| `reset()` | Resets all counters to zero |
