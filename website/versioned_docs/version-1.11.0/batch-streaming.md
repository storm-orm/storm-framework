import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Batch Processing & Streaming

Database performance often degrades when applications issue many individual SQL statements in a loop. Each statement incurs network latency, server-side parsing, and transaction log overhead. Batch processing and streaming solve two sides of this problem: batch processing reduces the cost of writing many rows, and streaming reduces the memory cost of reading many rows.

- **Batch processing** groups multiple insert/update/delete operations into a single database round-trip, reducing network overhead. JDBC batching sends a prepared statement once and supplies multiple parameter sets, which the database can execute as a unit. This is significantly faster than issuing individual statements.
- **Streaming** processes query results row by row without loading the entire result set into memory. This is essential when result sets are too large to fit in memory, or when you want to begin processing before the query has finished returning all rows.

---

## Batch Processing

When you pass a list of entities to Storm's insert, update, delete, or upsert methods, Storm automatically uses JDBC batch statements. The framework groups rows together and sends them to the database in a single round-trip, rather than issuing one statement per entity.

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

### Batch Delete

Batch deletes remove multiple entities in a single round-trip. Storm generates a batched DELETE using each entity's primary key.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm delete users

// Or delete all entities of a type
orm.deleteAll<User>()
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.entity(User.class).delete(users);
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
orm.entity(User::class).insert(users, 500)
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

When a query returns thousands or millions of rows, loading them all into a `List` can exhaust memory. Streaming processes rows one at a time as they arrive from the database, keeping memory usage constant regardless of result set size.

:::warning Stream Lifecycle
Streams returned by Storm must be closed after use. Use `.use {}` (Kotlin) or try-with-resources (Java) to ensure proper cleanup. Failing to close a stream will leak database resources (cursors, connections).
:::

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin uses `Flow` for streaming, which provides automatic resource cleanup through structured concurrency. When the Flow completes or the coroutine is cancelled, database cursors and connections are released without explicit cleanup code.

```kotlin
val users: Flow<User> = orm.entity(User::class).selectAll()

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
try (Stream<User> users = orm.entity(User.class).selectAll()) {
    users.forEach(user -> processUser(user));
}

// Transform and collect
try (Stream<User> users = orm.entity(User.class).selectAll()) {
    List<String> emails = users
        .map(User::email)
        .toList();
}

// Count without loading all entities
try (Stream<User> users = orm.entity(User.class).selectAll()) {
    long count = users.count();
}
```

</TabItem>
</Tabs>

### Filtered Streaming

You can combine streaming with query filters to process only rows that match your criteria. This pushes the filtering to the database rather than loading all rows and filtering in application code.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val filteredUsers: Flow<User> = orm.entity(User::class)
    .select()
    .where(User_.name like "A%")
    .resultFlow
```

</TabItem>
<TabItem value="java" label="Java">

```java
try (Stream<User> users = orm.entity(User.class)
        .select()
        .where(User_.name, LIKE, "A%")
        .getResultStream()) {
    users.forEach(this::processUser);
}
```

</TabItem>
</Tabs>

### Streaming with Transactions

When you need to read and update rows as part of a single atomic operation, wrap the streaming operation in a transaction. This ensures that the data you read and the updates you write are consistent, and that the entire operation either succeeds or is rolled back.

```kotlin
transaction {
    val users: Flow<User> = orm.selectAll<User>()
    users.collect { user ->
        // Process within the same transaction
        orm update user.copy(processed = true)
    }
}
```

---

## Tips

1. **Always close Java streams** - use try-with-resources to prevent resource leaks (database cursors, connections)
2. **Kotlin Flow is safer** - automatic resource management through structured concurrency
3. **Use streaming for large datasets** - avoid loading millions of rows into memory
4. **Batch operations are automatic** - Storm handles JDBC batching internally for bulk inserts/updates/deletes
5. **Wrap in transactions** - batch operations within a transaction commit atomically and perform better
6. **Tune batch size for large imports** - use the batch size parameter for datasets with thousands of rows
