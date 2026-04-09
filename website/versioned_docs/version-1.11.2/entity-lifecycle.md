# Entity Lifecycle

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Storm provides a typed `EntityCallback<E>` interface that lets you hook into entity lifecycle events. Callbacks are a general-purpose building block for cross-cutting concerns like auditing, validation, and logging, while keeping Storm unopinionated about how those concerns are implemented.

Rather than baking opinionated annotations like `@CreatedAt` or `@UpdatedBy` into the framework, Storm gives you the hooks and lets you decide how to use them. This keeps the framework lean and avoids hidden "magic" that can be difficult to debug or customize.

---

## The EntityCallback Interface

`EntityCallback<E>` is parameterized by the entity type it applies to. The framework resolves the type parameter at runtime and only invokes the callback for matching entity types. All methods have default no-op implementations, so you only override the hooks you need.

| Method | Description |
|---|---|
| `beforeInsert(entity)` | Called before inserting. Returns the (potentially transformed) entity to persist. |
| `beforeUpdate(entity)` | Called before updating. Returns the (potentially transformed) entity to persist. |
| `beforeUpsert(entity)` | Called before a SQL-level upsert. Returns the (potentially transformed) entity to persist. Delegates to `beforeInsert` by default. |
| `afterInsert(entity)` | Called after a successful insert. |
| `afterUpdate(entity)` | Called after a successful update. |
| `afterUpsert(entity)` | Called after a successful SQL-level upsert. Delegates to `afterInsert` by default. |
| `beforeDelete(entity)` | Called before deleting. |
| `afterDelete(entity)` | Called after a successful delete. |

:::warning After-Callback Entity State
**Important:** The entity passed to `afterInsert`, `afterUpdate`, and `afterUpsert` is the **pre-persist entity**. It does not include database-generated values such as auto-incremented IDs, server defaults, or trigger-applied changes. To access the generated ID, use the return value of `insertAndFetch` instead.
:::

Every mutation operation follows the same three-phase lifecycle: the "before" callback runs first and can transform the entity, then the SQL executes, and finally the "after" callback fires to observe the result. The following diagram illustrates this flow for an insert operation. Update, upsert, and delete follow the same pattern with their respective callback methods:

```
                         insert(entity)
                              │
                              ▼
                    ┌───────────────────┐
                    │  beforeInsert()   │  ← returns (potentially transformed) entity
                    └────────┬──────────┘
                             │
                             ▼
                    ┌───────────────────┐
                    │   INSERT INTO …   │  ← SQL executes with transformed entity
                    └────────┬──────────┘
                             │
                             ▼
                    ┌───────────────────┐
                    │  afterInsert()    │  ← observes the pre-persist entity
                    └───────────────────┘
```

### Immutable Entity Transformation

Storm entities are immutable records and data classes, so they cannot be mutated in place. To accommodate this, the "before" callbacks for insert, update, and upsert **return the entity** that will actually be persisted. Implementations can return a new instance with modified fields (e.g., audit timestamps set) or the original entity unchanged. The "after" callbacks and `beforeDelete` are purely observational and return `void`.

This design works naturally with both Kotlin's `copy()` and Java's builder pattern, keeping callback implementations concise and idiomatic in both languages.

### Typed vs. Global Callbacks

A callback can target a single entity type or apply globally to all entities. Use a specific type parameter to limit a callback to one entity:

```java
EntityCallback<Article> callback = new EntityCallback<>() { ... };
```

Use `Entity<?>` as the type parameter to create a global callback that fires for every entity type. This is useful for cross-cutting concerns like logging or security checks that apply uniformly:

```java
EntityCallback<Entity<?>> globalCallback = new EntityCallback<>() { ... };
```

The framework resolves the type parameter at runtime, so a typed callback is never invoked for entity types it does not match. When multiple callbacks are registered, they fire in registration order, and each callback in the chain receives the entity returned by the previous one.

---

## Registering a Callback

There are two ways to register callbacks: programmatically via `withEntityCallback`, or automatically through Spring Boot auto-configuration.

### Programmatic Registration

Call `withEntityCallback` on any `ORMTemplate` to create a new template instance with the callback applied. The original template is unchanged; this follows Storm's immutable configuration pattern. Multiple callbacks can be registered by chaining calls, and they fire in registration order.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val callback = object : EntityCallback<Article> {
    override fun beforeInsert(entity: Article): Article {
        return entity.copy(createdAt = Instant.now())
    }
}

val orm = dataSource.orm.withEntityCallback(callback)
```

</TabItem>
<TabItem value="java" label="Java">

```java
EntityCallback<Article> callback = new EntityCallback<>() {
    @Override
    public Article beforeInsert(Article entity) {
        return entity.toBuilder().createdAt(Instant.now()).build();
    }
};

ORMTemplate orm = ORMTemplate.of(dataSource).withEntityCallback(callback);
```

</TabItem>
</Tabs>

### Spring Boot Auto-Configuration

When using the Storm Spring Boot Starter, any `EntityCallback` beans in your application context are automatically detected and wired to the `ORMTemplate`. No additional configuration is needed. Each callback is registered individually and only fires for entities matching its type parameter.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Configuration
class AuditConfig {
    @Bean
    fun auditCallback(): EntityCallback<Article> = object : EntityCallback<Article> {
        override fun beforeInsert(entity: Article): Article {
            return entity.copy(createdAt = Instant.now())
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class AuditConfig {
    @Bean
    public EntityCallback<Article> auditCallback() {
        return new EntityCallback<>() {
            @Override
            public Article beforeInsert(Article entity) {
                return entity.toBuilder().createdAt(Instant.now()).build();
            }
        };
    }
}
```

</TabItem>
</Tabs>

---

## Callback Behavior

### Upsert Routing

An upsert operation does not always result in a SQL-level upsert statement. Depending on the entity's primary key state and the database dialect, the framework may route the operation to a plain insert or update instead. The callbacks that fire depend on which path is taken:

```
                              upsert(entity)
                                    │
                 ┌──────────────────┼──────────────────┐
                 ▼                  ▼                   ▼
          ┌─────────────┐   ┌─────────────┐   ┌──────────────────┐
          │ Route to    │   │ Route to    │   │ SQL-level upsert │
          │ update      │   │ insert      │   │                  │
          └──────┬──────┘   └──────┬──────┘   └────────┬─────────┘
                 │                 │                    │
                 ▼                 ▼                    ▼
          beforeUpdate /    beforeInsert /       beforeUpsert /
          afterUpdate       afterInsert          afterUpsert
```

Exactly one pair of callbacks fires per entity; they are never combined. The following table summarizes when each routing path is taken:

| Routing path | When | Callbacks fired |
|---|---|---|
| **Update** | The entity has an auto-generated primary key with a non-default value (it was previously inserted). | `beforeUpdate` / `afterUpdate` |
| **Insert** | The entity has an auto-generated primary key with a default value, and the dialect cannot perform a SQL-level upsert with generated keys (e.g., Oracle, SQL Server). | `beforeInsert` / `afterInsert` |
| **SQL-level upsert** | All other cases (non-auto-generated primary keys, or dialects that support SQL-level upsert with generated keys such as PostgreSQL and MySQL). | `beforeUpsert` / `afterUpsert` |

The practical consequence is that you do not need to override all three pairs. If you only override `beforeInsert` and `beforeUpdate`, you already cover the routed upsert paths. For the SQL-level upsert path, `beforeUpsert` delegates to `beforeInsert` by default, so insert callbacks cover all three paths out of the box. Override `beforeUpsert` only when you need different behavior for the SQL-level upsert case.

### "After" Callback Entity State

The "after" callbacks (`afterInsert`, `afterUpdate`, `afterUpsert`, `afterDelete`) always receive the entity as it was sent to the database, after the corresponding "before" transformation. They do **not** reflect database-generated values such as auto-incremented primary keys, version column increments, default column values, or trigger-applied modifications.

This applies to all repository methods, including the `*AndFetch` variants. For example, when `insertAndFetch` is called, `afterInsert` still receives the pre-persist entity; the fetched entity (with the generated ID, defaults, etc.) is only returned to the caller. This keeps the callback contract consistent and predictable regardless of which repository method was used.

### Database Operations Inside Callbacks

Callbacks execute in the same thread and transaction as the repository operation that triggered them. This means a callback can safely perform additional database work, such as inserting related entities, querying for validation data, or updating audit logs, and that work will participate in the same transaction. If the transaction rolls back, all changes made by callbacks roll back as well.

In Spring Boot, callbacks are regular beans and can have repositories or other services injected through standard dependency injection. Outside Spring, a callback can capture a reference to the `ORMTemplate` or a repository at construction time.

```java
public class ArticleHistoryCallback implements EntityCallback<Article> {
    private final ORMTemplate orm;

    public ArticleHistoryCallback(ORMTemplate orm) {
        this.orm = orm;
    }

    @Override
    public void afterUpdate(Article entity) {
        orm.insert(new ArticleHistory(entity.id(), Instant.now(), "updated"));
    }
}
```

A natural concern with database-calling callbacks is infinite recursion: if an `afterUpdate` callback inserts an entity, and that insert triggers its own callbacks, which insert more entities, and so on. Storm prevents this with a re-entrancy guard. Callbacks never fire recursively. If a callback performs a database operation that would normally trigger callbacks, that nested operation executes normally but its callbacks are suppressed. The following diagram illustrates this:

```
  Application          ArticleRepository       Callback             HistoryRepository      Database
      │                       │                    │                       │                   │
      │   update(article)     │                    │                       │                   │
      │──────────────────────▶│                    │                       │                   │
      │                       │  beforeUpdate()    │                       │                   │
      │                       │───────────────────▶│                       │                   │
      │                       │◀───────────────────│                       │                   │
      │                       │                    │                       │                   │
      │                       │  UPDATE articles …                         │                   │
      │                       │───────────────────────────────────────────────────────────────▶│
      │                       │◀───────────────────────────────────────────────────────────────│
      │                       │                    │                       │                   │
      │                       │  afterUpdate()     │                       │                   │
      │                       │───────────────────▶│                       │                   │
      │                       │                    │  insert(history)      │                   │
      │                       │                    │──────────────────────▶│                   │
      │                       │                    │                       │  callbacks        │
      │                       │                    │                       │  suppressed       │
      │                       │                    │                       │                   │
      │                       │                    │                       │  INSERT INTO …    │
      │                       │                    │                       │──────────────────▶│
      │                       │                    │                       │◀──────────────────│
      │                       │                    │◀──────────────────────│                   │
      │                       │◀───────────────────│                       │                   │
      │◀──────────────────────│                    │                       │                   │
```

This makes it safe to perform arbitrary database work inside a callback without needing manual guards or worrying about stack overflows.

### Batch Operations

Callbacks work with both single and batch operations. For batch operations, the "before" callbacks (`beforeInsert`, `beforeUpdate`, `beforeUpsert`) are called per entity during the mapping phase, before the batch is sent to the database. The "after" callbacks (`afterInsert`, `afterUpdate`, `afterUpsert`, `afterDelete`) are called per entity after the batch executes successfully. This means the "before" callback can transform each entity individually, and all transformations are applied before the batch SQL is executed.

---

## Examples

### Auditing

A common use case is automatically populating audit fields. A practical approach is to define a shared interface for auditable entities, then use a single callback to fill in the timestamps. The `beforeInsert` callback sets both `createdAt` and `updatedAt`, while `beforeUpdate` only refreshes `updatedAt`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
interface Auditable {
    fun withAudit(createdAt: Instant, updatedAt: Instant): Auditable
}

data class Article(
    @PK val id: Int = 0,
    val title: String,
    val createdAt: Instant? = null,
    val updatedAt: Instant? = null
) : Entity<Int>, Auditable {
    override fun withAudit(createdAt: Instant, updatedAt: Instant) =
        copy(createdAt = createdAt, updatedAt = updatedAt)
}

class AuditCallback : EntityCallback<Article> {
    override fun beforeInsert(entity: Article): Article {
        val now = Instant.now()
        return entity.withAudit(createdAt = now, updatedAt = now)
    }

    override fun beforeUpdate(entity: Article): Article {
        return entity.copy(updatedAt = Instant.now())
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class AuditCallback implements EntityCallback<Article> {
    @Override
    public Article beforeInsert(Article entity) {
        Instant now = Instant.now();
        return entity.toBuilder().createdAt(now).updatedAt(now).build();
    }

    @Override
    public Article beforeUpdate(Article entity) {
        return entity.toBuilder().updatedAt(Instant.now()).build();
    }
}
```

</TabItem>
</Tabs>

To apply auditing across multiple entity types without writing a separate callback for each, use a global callback with a runtime type check. Any entity that implements the `Auditable` interface gets its timestamps set; other entities pass through unchanged:

```java
public class GlobalAuditCallback implements EntityCallback<Entity<?>> {
    @Override
    public Entity<?> beforeInsert(Entity<?> entity) {
        if (entity instanceof Auditable a) {
            return (Entity<?>) a.withCreatedAt(Instant.now());
        }
        return entity;
    }
}
```

### Validation

Callbacks can enforce business rules before data reaches the database. Unlike database constraints, callback-level validation can produce domain-specific error messages and catch problems before the SQL round-trip. Both `beforeInsert` and `beforeUpdate` must return the entity, so a validation callback simply returns the original entity unchanged after checking the invariants:

```java
public class ArticleValidationCallback implements EntityCallback<Article> {
    @Override
    public Article beforeInsert(Article entity) {
        validate(entity);
        return entity;
    }

    @Override
    public Article beforeUpdate(Article entity) {
        validate(entity);
        return entity;
    }

    private void validate(Article entity) {
        if (entity.title() == null || entity.title().isBlank()) {
            throw new IllegalArgumentException("Article title must not be blank.");
        }
    }
}
```

### Logging

The "after" callbacks are well-suited for logging, since they fire only after the database operation succeeds. This avoids logging mutations that were rolled back. The entity passed to the callback is the pre-persist version (see [After Callback Entity State](#after-callback-entity-state)), so the logged values reflect what your application sent to the database:

```java
public class ArticleLoggingCallback implements EntityCallback<Article> {
    private static final Logger log = LoggerFactory.getLogger(ArticleLoggingCallback.class);

    @Override
    public void afterInsert(Article entity) {
        log.info("Inserted article: {}", entity);
    }

    @Override
    public void afterUpdate(Article entity) {
        log.info("Updated article: {}", entity);
    }

    @Override
    public void afterDelete(Article entity) {
        log.info("Deleted article: {}", entity);
    }
}
```
