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
| `afterInsert(entities)` | Called with a batch of inserted entities. Calls `afterInsert(entity)` for each by default. |
| `afterUpdate(entity)` | Called after a successful update. |
| `afterUpdate(entities)` | Called with a batch of updated entities. Calls `afterUpdate(entity)` for each by default. |
| `afterUpsert(entity)` | Called after a successful SQL-level upsert. Delegates to `afterInsert` by default. |
| `afterUpsert(entities)` | Called with a batch of upserted entities. Calls `afterUpsert(entity)` for each by default. |
| `beforeRemove(entity)` | Called before removing. |
| `afterRemove(entity)` | Called after a successful removal. |
| `afterRemove(entities)` | Called with a batch of removed entities. Calls `afterRemove(entity)` for each by default. |

:::info After-Callback Entity State
**The callback observes what the caller observes.** A method that returns nothing passes the entity as it was sent; a `*AndFetchId` method passes it carrying the generated primary key; a `*AndFetch` method passes the row read back from the database. See [After Callback Entity State](#after-callback-entity-state).
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
                    │  afterInsert()    │  ← observes what the caller receives
                    └───────────────────┘
```

### Immutable Entity Transformation

Storm entities are immutable records and data classes, so they cannot be mutated in place. To accommodate this, the "before" callbacks for insert, update, and upsert **return the entity** that will actually be persisted. Implementations can return a new instance with modified fields (e.g., audit timestamps set) or the original entity unchanged. The "after" callbacks and `beforeRemove` are purely observational and return `void`.

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
                 ┌──────────────────┼───────────────────┐
                 ▼                  ▼                   ▼
          ┌─────────────┐   ┌─────────────┐   ┌──────────────────┐
          │ Route to    │   │ Route to    │   │ SQL-level upsert │
          │ update      │   │ insert      │   │                  │
          └──────┬──────┘   └──────┬──────┘   └────────┬─────────┘
                 │                 │                   │
                 ▼                 ▼                   ▼
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

The "after" callbacks receive exactly what the calling method reports to its caller, and no more. The method name at the call site therefore tells you what the callback will see:

| Method | The callback receives |
|---|---|
| `insert`, `update`, `upsert` | The entity as sent to the database, after the "before" transformation. No key is read back, so a generated primary key is not reflected. |
| `insertAndFetchId`, `insertAndFetchIds`, `upsertAndFetchId`, `upsertAndFetchIds` | That same entity, carrying the primary key the database assigned. |
| `insertAndFetch`, `updateAndFetch`, `upsertAndFetch` | The entity as read back from the database, reflecting generated keys, column defaults, version increments, and trigger-applied changes. |

`afterRemove` always receives the entity that was passed in.

This is a deliberate trade. A method that returns nothing does not read generated keys, and making it do so would change the SQL it issues; a method that reads the row back has that row already. Rather than levelling every method down to the cheapest one, the callback is given whatever its caller paid for.

:::warning Choosing a method that reports what your callback needs
A callback that reads `entity.id()` needs a method that reports one. If an `afterInsert` callback writes a related row, calling plain `insert` elsewhere in the codebase leaves that callback with an unset primary key rather than an error. Where a callback depends on the generated key, make sure the call sites use `insertAndFetchId` or `insertAndFetch`.
:::

#### Write Sets

Write sets report on the same terms: `writeSet.insert(...)` passes the entities as sent, `insertAndFetchIds` passes them carrying their keys, and `insertAndFetch` passes the rows read back.

This holds regardless of the dependency graph. A write set retrieves the keys it needs to bind foreign keys on dependent rows, but those keys are a property of the graph rather than something the caller asked to observe, so they are not reported to callbacks unless the calling method reports them. Inserting a `City` on its own and inserting the same `City` alongside an `Owner` that references it therefore look identical to a callback.

A write set also pulls in unsaved entities reachable from the ones you pass, so a callback can fire for an entity you never handed it directly. Those discovered members are reported to callbacks on the same terms as the ones you passed: on `insertAndFetch` their callbacks observe a row read back too, even though the entity itself is not returned to you. Whether an entity was passed or reached by discovery does not change what its callback sees.

### Remove Callbacks

`beforeRemove` and `afterRemove` receive an entity, so they fire where the operation has one: `remove(entity)` and its collection and stream forms. `removeById`, `removeByRef`, `removeAll`, the `removeByRef` collection forms, and the `delete()` query builder identify rows by key or by predicate rather than by entity, so there is no entity to hand to a callback and these callbacks do not fire.

This follows the same principle as the "after" callback state above. Removing by key is one statement; reading the row first so a callback could observe it would make every removal by id a select plus a delete. Where a callback needs the entity, remove by entity.

:::warning A remove callback is not an enforcement point
A callback that throws in order to block a removal only blocks the paths that carry an entity, so `removeById`, `removeByRef` and the `delete()` builder pass straight through. Enforce an invariant in the database (a foreign key, a trigger, or a restricted grant) and use the callback for the bookkeeping that follows.
:::

### Database Operations Inside Callbacks

Callbacks execute on the thread that performed the write and on its connection, so they see exactly the transaction the write runs in. A callback can perform additional database work, such as inserting related entities, querying for validation data, or updating audit logs, and inside a transaction that work is part of it: a rollback takes back the callback's changes along with the write, and an "after" callback that throws rolls the write back with it.

Storm opens no transaction of its own, so the statement holds in the other direction too. A write issued outside a transaction commits on its own, and so does each statement of a call that issues several, such as an `*AndFetch` method or a batch. An "after" callback then runs against a row that is already durable: throwing cannot take it back, and the callback's own statements commit separately, so a failing callback leaves the write in place. A callback whose work has to succeed or fail with the write depends on the caller having opened a transaction.

:::warning Use the template the application uses
A callback shares the write's transaction because it shares its connection, and it shares the connection because it goes through the same template. Inject the `ORMTemplate` or the repository the application is wired with, or derive one from it with `withEntityCallback`. A template the callback builds for itself carries transaction machinery of its own: inside a Storm `transaction { }` block the mismatch is refused, naming both providers, and under a Spring-managed transaction the callback's work runs on a connection of its own, outside the transaction it was meant to join.
:::

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

Callbacks work with both single and batch operations. For batch operations, the "before" callbacks (`beforeInsert`, `beforeUpdate`, `beforeUpsert`) are called per entity during the mapping phase, before the batch is sent to the database. This means the "before" callback can transform each entity individually, and all transformations are applied before the batch SQL is executed.

The "after" callbacks each have a list form, and Storm always calls it: a batch write passes the entities of one batch in the order they were written, and a single-entity write passes a list of one. A `*AndFetch` call and a write set pass each type's entities as one list once the rows have been read back, and a stream passes them a batch at a time. By default the list form calls the single-entity form for each entity, so a callback that overrides only `afterInsert(entity)` sees every entity as it always did. Where several callbacks apply, each receives the whole list before the next one does.

A callback that writes to the database itself overrides the list form, so a batch costs it a fixed number of statements rather than one per entity. That matters most where each round trip to the database is expensive: a callback inserting an audit row per entity issues a hundred statements for a batch of a hundred, where the list form inserts them as one batch.

An upserted batch reaches `afterUpsert(entities)`, or `afterInsert(entities)` when the callback overrides neither upsert form, so an insert callback covers the upsert path in its list form as it does in its single-entity form. A callback that overrides `afterUpsert(entity)` keeps receiving each upserted entity through it.

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

The "after" callbacks are well-suited for logging, since they fire only after the database operation succeeds, so a statement that failed never produces an entry. What gets logged depends on the method that performed the write (see [After Callback Entity State](#after-callback-entity-state)): `insert` logs what your application sent, while `insertAndFetch` logs the stored row.

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
    public void afterRemove(Article entity) {
        log.info("Deleted article: {}", entity);
    }
}
```

They fire *within* the transaction, though, not after it commits. The statement succeeded, but the transaction it belongs to has not finished, so an entry written straight from the callback outlives a mutation that a later rollback undoes.

That is fine for a log that records attempts. Where the record must reflect committed state, and for any side effect that cannot be taken back, such as publishing an event or invalidating a cache, the callback participates in the transaction the way any code does: it opens a joining [`transaction { }`](transactions.md#registering-from-nested-code) block and registers the work as a commit callback, which runs only if the transaction commits:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class ArticlePublishingCallback : EntityCallback<Article> {
    override fun afterInsert(entity: Article) {
        transactionBlocking {
            onCommit { events.publish(ArticlePublished(entity)) }
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class ArticlePublishingCallback implements EntityCallback<Article> {
    @Override
    public void afterInsert(Article entity) {
        Transactions.transaction(tx -> {
            tx.onCommit(() -> events.publish(new ArticlePublished(entity)));
            return null;
        });
    }
}
```

</TabItem>
</Tabs>

The pattern works the same under a Spring-managed transaction (`@Transactional`): the block detects the active Spring transaction, so the publish waits for Spring's commit. See [Mixed-Usage Caveats](transactions.md#mixed-usage-caveats) for the fine print.

Register once per batch rather than once per row. `afterInsert(entity)` fires for each row of a batch write, so registering there holds one commit callback per row until the transaction ends. The [list form](#batch-operations) receives the batch as one list, which is where a single callback covers all of it:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class ArticlePublishingCallback : EntityCallback<Article> {
    override fun afterInsert(entities: List<Article>) {
        transactionBlocking {
            onCommit { entities.forEach { events.publish(ArticlePublished(it)) } }
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class ArticlePublishingCallback implements EntityCallback<Article> {
    @Override
    public void afterInsert(List<Article> entities) {
        Transactions.transaction(tx -> {
            tx.onCommit(() -> entities.forEach(article -> events.publish(new ArticlePublished(article))));
            return null;
        });
    }
}
```

</TabItem>
</Tabs>

Commit callbacks run synchronously before the transactional block returns, so their duration is added to the caller's. A publish that can block for long belongs on a background worker the callback hands off to. Work that has to survive a process dying between the commit and the publish belongs in the transaction as a row of its own, drained by a worker afterwards.
