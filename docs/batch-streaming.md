import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Batch Processing & Streaming

Database performance often degrades when applications issue many individual SQL statements in a loop. Each statement incurs network latency, server-side parsing, and transaction log overhead. Batch processing and streaming solve two sides of this problem: batch processing reduces the cost of writing many rows, and streaming reduces the memory cost of reading many rows.

- **Batch processing** groups multiple insert/update/delete operations into a single database round-trip, reducing network overhead. JDBC batching sends a prepared statement once and supplies multiple parameter sets, which the database can execute as a unit. This is significantly faster than issuing individual statements.
- **Streaming** processes query results row by row without loading the entire result set into memory. This is essential when result sets are too large to fit in memory, or when you want to begin processing before the query has finished returning all rows.

---

## Batch Processing

When you pass a list of entities to Storm's insert, update, remove, or upsert methods, Storm automatically uses JDBC batch statements. The framework groups rows together and sends them to the database in a single round-trip, rather than issuing one statement per entity.

### Batch Insert

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = listOf(
    User(email = "alice@example.com", name = "Alice", city = city),
    User(email = "bob@example.com", name = "Bob", city = city),
    User(email = "charlie@example.com", name = "Charlie", city = city)
)

orm insert users
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> users = List.of(
    new User(null, "alice@example.com", "Alice", null, city),
    new User(null, "bob@example.com", "Bob", null, city),
    new User(null, "charlie@example.com", "Charlie", null, city)
);

orm.entity(User.class).insert(users);
```

</TabItem>
</Tabs>

### Batch Update

Pass a list of modified entities and Storm generates a batched UPDATE statement. Each entity in the list produces one row in the batch. This is especially useful when you need to apply a transformation to many rows at once.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val updatedUsers = users.map { it.copy(active = true) }
orm update updatedUsers
```

</TabItem>
<TabItem value="java" label="Java">

Since Java records are immutable, you create new record instances with the modified values. Storm batches the resulting UPDATE statements.

```java
List<User> updatedUsers = users.stream()
    .map(u -> new User(u.id(), u.email(), u.name(), true, u.city()))
    .toList();

orm.entity(User.class).update(updatedUsers);
```

</TabItem>
</Tabs>

### Batch Remove

Batch removes delete multiple entities in a single round-trip. Storm generates a batched DELETE using each entity's primary key.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm remove users

// Or remove all entities of a type
orm.removeAll<User>()
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.entity(User.class).remove(users);
```

</TabItem>
</Tabs>

### Batch Upsert

Batch upserts combine insert and update semantics for a list of entities. Each entity is either inserted (if no matching row exists) or updated (if a row with the same unique constraint already exists). This is useful for data synchronization scenarios where you receive a batch of records from an external source and need to merge them into your database. See [Upserts](upserts.md) for details on how conflict detection works per database.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = listOf(
    User(email = "alice@example.com", name = "Alice Updated", city = city),
    User(email = "dave@example.com", name = "Dave", city = city)
)

orm upsert users  // Inserts new, updates existing
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> users = List.of(
    new User(null, "alice@example.com", "Alice Updated", null, city),
    new User(null, "dave@example.com", "Dave", null, city)
);

orm.entity(User.class).upsert(users);  // Inserts new, updates existing
```

</TabItem>
</Tabs>

### Batch Size

Storm automatically groups batch operations for optimal performance. Batch operations have overloaded methods that accept a batch size parameter, giving you control over how many rows are grouped together before being sent to the database. Smaller batches reduce memory usage, while larger batches reduce network round-trips. The default batch size works well for most cases.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Insert in batches of 500
orm.entity<User>().insert(users, 500)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Insert in batches of 500
orm.entity(User.class).insert(users, 500);
```

</TabItem>
</Tabs>

---

## Streaming

When a query returns thousands or millions of rows, loading them all into a `List` can exhaust memory. Storm offers two streaming shapes, and they differ in what the connection may do while the rows are read.

- **A result stream** (`resultFlow`, `getResultStream()`) is one open statement. Rows are read from the database as they are consumed, so memory stays bounded, and the statement stays open until the stream is closed. Until the stream has been read to its end or closed, the connection it reads from is consume-only.
- **Windows** (`windows(size)`) run one closed statement per window of rows. Between windows the connection is free, so the loop may query, fetch references and write. This is the shape for a loop that does more than consume.

### Result streams

:::warning Stream Lifecycle
Streams returned by Storm must be closed after use. Use `.use {}` (Kotlin) or try-with-resources (Java) to ensure proper cleanup. Failing to close a stream will leak database resources (cursors, connections). Kotlin's `Flow` closes itself when collection completes or is cancelled.
:::

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin uses `Flow` for streaming. The flow is cold: the query executes when the flow is collected, and the statement closes when the collection completes or the coroutine is cancelled, without explicit cleanup code.

```kotlin
val users: Flow<User> = orm.entity<User>().select().resultFlow

// Process one at a time -- only one row in memory
users.collect { user ->
    processUser(user)
}

// Transform and collect
val emails: List<String> = users
    .map { it.email }
    .toList()

// Count without loading all entities
val count: Int = users.count()
```

</TabItem>
<TabItem value="java" label="Java">

Java uses `Stream` for streaming. Unlike Kotlin's Flow, Java streams do not have automatic resource management through structured concurrency. You must explicitly close streams to release database resources (cursors, connections). **Always use try-with-resources** to ensure cleanup happens even if an exception occurs.

```java
// Process one at a time
try (Stream<User> users = orm.entity(User.class).select().getResultStream()) {
    users.forEach(user -> processUser(user));
}

// Transform and collect
try (Stream<User> users = orm.entity(User.class).select().getResultStream()) {
    List<String> emails = users
        .map(User::email)
        .toList();
}

// Count without loading all entities
try (Stream<User> users = orm.entity(User.class).select().getResultStream()) {
    long count = users.count();
}
```

</TabItem>
</Tabs>

#### The connection is consume-only while a stream is open

A result stream reads its rows from one open statement, and the rows not yet consumed live on the database server. What the connection may do in the meantime is up to the JDBC driver: PostgreSQL and Oracle interleave a second statement with the open result, MySQL Connector/J rejects it, and MariaDB Connector/J and the SQL Server driver read the rest of the open result into application memory before running the second statement, which turns the stream back into a whole-table list without any signal.

Storm makes this one rule on every database: **a statement on a connection whose result stream still has unread rows is refused** with a `PersistenceException` that names the open stream, the refused statement and the windows form. A stream read to its end blocks nothing, closed or not, and a Kotlin flow closes itself when its last row is emitted. Inside a transaction every statement shares the transaction's connection, so a query, a `Ref.fetch()` or a write issued from inside the loop is refused there. Outside a transaction the stream holds a connection of its own, and other statements run on other connections. Because the rule does not depend on the dialect, a loop that passes its tests on H2 behaves the same in production on any database.

```kotlin
transaction {
    orm.select<User>().resultFlow.collect { user ->
        orm update user.copy(email = user.email.lowercase())   // PersistenceException: a result stream is still open
    }
}
```

The loop above needs the connection while it iterates. That is what windows are for.

### Windows

`windows(size)` iterates the query in windows of `size` rows ordered by the primary key. Each window is fetched by its own statement, which has returned and closed before the window is handed to the loop, so between windows the connection is free. The loop may query, fetch references and write, inside one transaction or with a transaction per window, and a batched write per window costs one statement instead of one per row.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
transaction {
    val users = orm.entity<User>()
    users.select()
        .where(User_.city eq city)
        .windows(1000)
        .collect { window ->
            users.update(window.content().map { it.copy(email = it.email.lowercase()) })
        }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
transaction(tx -> {
    var users = orm.entity(User.class);
    users.select()
        .where(User_.city, EQUALS, city)
        .windows(1000)
        .forEach(window ->
            users.update(window.content().stream()
                .map(user -> new User(user.id(), user.email().toLowerCase(), user.birthDate(), user.street(), user.postalCode(), user.city()))
                .toList()));
    return null;
});
```

</TabItem>
</Tabs>

The stream of windows carries no database resource, so it needs no closing. Each element is a `Window`, the same type `scroll` returns: `content()` holds the rows, `hasNext()` says whether more rows existed when the window was read, and `next()` is a `Scrollable` that resumes the iteration after the window. A long-running job can persist `window.nextCursor()` after each window and resume from it after a restart with `windows(Scrollable.of(User_.id, 1000).from(cursor))`.

Windows are keyset windows, so the rules of [scrolling](pagination-and-scrolling.md#scrolling) apply:

- The query must not carry an `orderBy` of its own; the request orders. `windows(Scrollable.of(User_.id, 1000).sortBy(User_.birthDate))` sorts by a non-unique field with the key as tiebreaker, and `.descending()` iterates in descending key order.
- The key must be a non-nullable unique key. `windows(size)` uses the primary key.
- The key is read from each row alongside the result, so refs and custom select types iterate too. An inline record key is read from the mapped record and needs the entity type as the result.
- Each window is its own statement. It runs the query's `WHERE` clause again from the position, so the key should be indexed, which a primary or unique key is. Under `READ COMMITTED` a later window sees rows committed after the previous one; rows the loop writes and that it has passed are never visited again.

### Choosing between them

| | Result stream | Windows |
|---|---|---|
| Statements | One, open until the stream closes | One closed statement per window |
| Connection while iterating | Consume-only | Free |
| Snapshot | One, for the whole result | One per window |
| Requires | Nothing; any query, including templates | A unique key and no `orderBy` of its own |
| Resource to close | The stream (Java); the Flow closes itself | None |
| Resumable | No | Yes, from `window.next()` or a cursor string |

Use a result stream to consume a large result: export it, aggregate it, map it to a file. Use windows when the loop needs the database: reading a related record, fetching a reference, writing per row.

---

## Tips

1. **Always close Java streams** - use try-with-resources to prevent resource leaks (database cursors, connections)
2. **A result stream is consume-only** - a query, `Ref.fetch()` or write from inside the loop is refused on every database while rows remain unread; use `windows(size)` for loops that need the connection
3. **Use streaming for large datasets** - avoid loading millions of rows into a list
4. **Batch operations are automatic** - Storm handles JDBC batching internally for bulk inserts/updates/deletes
5. **Write per window, not per row** - inside `windows(size)` one batched `update` per window costs one statement instead of one per row
6. **Wrap in transactions** - batch operations within a transaction commit atomically and perform better; a long window loop may also commit per window so its progress is durable
7. **Tune batch size for large imports** - use the batch size parameter for datasets with thousands of rows
