import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Upserts

Many applications need to create a record if it does not exist, or update it if it does. A naive approach using separate SELECT-then-INSERT-or-UPDATE logic introduces race conditions: two concurrent requests can both see that a row is missing and both attempt to insert, causing a constraint violation. Even with application-level locking, this approach adds complexity and reduces throughput.

Storm provides first-class support for upsert (insert-or-update) operations across all major databases. By delegating conflict resolution to the database engine itself, upserts behave predictably and handle race conditions atomically in a single SQL statement. No application-level locking or retry logic is needed.

Use upsert when you need idempotent write operations, data synchronization from external sources, or any scenario where the same logical record may arrive multiple times.

---

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

### Single Upsert

The simplest form of upsert operates on a single entity. Storm determines whether to insert or update based on the table's unique constraints. The returned entity includes any database-generated values, such as an auto-incremented primary key.

```kotlin
val user = orm upsert User(
    email = "alice@example.com",
    name = "Alice",
    birthDate = LocalDate.of(1990, 5, 15),
    city = city
)
// user.id is now populated with the database-generated ID
```

If a user with matching unique constraints exists, it will be updated. Otherwise, a new user is inserted. The returned entity includes any database-generated values (such as the primary key).

### Batch Upsert

Upsert multiple entities in a single batch operation:

```kotlin
val users = listOf(
    User(email = "alice@example.com", name = "Alice Updated", city = city),
    User(email = "bob@example.com", name = "Bob", city = city),
    User(email = "charlie@example.com", name = "Charlie", city = city)
)

orm upsert users
```

### Upsert within a Transaction

Upserts participate in transactions like any other Storm operation. When you need to upsert an entity that depends on another entity (for example, a user that references a city), wrap both operations in a transaction to ensure atomicity.

```kotlin
transaction {
    val city = orm insert City(name = "Sunnyvale", population = 155_000)
    val user = orm upsert User(
        email = "alice@example.com",
        name = "Alice",
        city = city
    )
}
```

</TabItem>
<TabItem value="java" label="Java">

### Single Upsert

The Java API provides upsert through the `entity()` method. Pass `null` for the primary key field to indicate that the database should generate the value on insert.

```java
orm.entity(User.class).upsert(new User(
    null,  // null ID triggers insert logic
    "alice@example.com",
    "Alice",
    LocalDate.of(1990, 5, 15),
    city
));
```

If a user with matching unique constraints exists, it will be updated. Otherwise, a new user is inserted.

### Upsert and Fetch

When you need the resulting entity with all database-generated values (such as the assigned primary key or default column values), use `upsertAndFetch`. This performs the upsert and returns the complete entity as it exists in the database after the operation. In Kotlin, `orm upsert` returns the entity with generated values by default, but the Java API separates `upsert` (void) from `upsertAndFetch` (returns entity) for clarity.

```java
User user = orm.entity(User.class).upsertAndFetch(new User(
    null,
    "alice@example.com",
    "Alice",
    LocalDate.of(1990, 5, 15),
    city
));
// user.id() is now populated with the database-generated ID
```

### With Lombok Builder

If your entity uses Lombok's `@Builder`, you can construct upsert arguments using the builder pattern. This avoids positional constructor arguments and makes the code more readable when entities have many fields.

```java
User user = orm.entity(User.class).upsertAndFetch(User.builder()
    .email("alice@example.com")
    .name("Alice")
    .birthDate(LocalDate.of(1990, 5, 15))
    .city(city)
    .build()
);
```

### Batch Upsert

Batch upserts process a list of entities in a single batched operation, combining JDBC batching with the database's native upsert syntax. This is significantly faster than upserting entities one at a time in a loop.

```java
List<User> users = List.of(
    new User(null, "alice@example.com", "Alice Updated", null, city),
    new User(null, "bob@example.com", "Bob", null, city)
);

orm.entity(User.class).upsert(users);
```

</TabItem>
</Tabs>

---

## How Upsert Works

Storm does not implement upsert logic in application code. Instead, it delegates to each database platform's native upsert syntax. This ensures atomicity at the database level and avoids race conditions that would occur with application-level check-then-insert logic. The specific SQL syntax varies by database:

| Database | SQL Strategy | Conflict Detection |
|----------|--------------|--------------------|
| Oracle | `MERGE INTO ...` | Explicit match conditions |
| MS SQL Server | `MERGE INTO ...` | Explicit match conditions |
| PostgreSQL | `INSERT ... ON CONFLICT DO UPDATE` | Targets a specific unique constraint or index |
| MySQL/MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE` | Primary key or any unique constraint |
| SQLite | `INSERT ... ON CONFLICT DO UPDATE` | Targets a specific unique constraint |
| H2 | `MERGE INTO ...` | Explicit match conditions |

### Database-Specific Behavior

- **Oracle**, **MS SQL Server**, and **H2** define upsert behavior through explicit match conditions in the `MERGE` statement, giving you control over how conflicts are detected.
- **PostgreSQL** upserts target a specific conflict source (a unique constraint or index), making conflict resolution explicit and predictable. This is the most granular approach.
- **MySQL/MariaDB** upserts trigger the update branch when an insert would violate the primary key **or any unique constraint**. When multiple unique constraints exist, the database decides which conflict applies. Be aware of this if your table has multiple unique constraints.
- **SQLite** uses the same `ON CONFLICT` syntax as PostgreSQL, targeting a specific unique constraint (available since SQLite 3.24).

## Failure Modes

Understanding how upserts fail helps you diagnose issues quickly and design your schema correctly.

**Missing dialect dependency:** Upsert requires a database-specific dialect module (e.g., `storm-postgresql`, `storm-mysql`). If no dialect is on the classpath, Storm throws an `UnsupportedOperationException` at runtime when you call `upsert()`. The error message indicates that the current dialect does not support upsert operations. Add the appropriate dialect dependency to resolve this. See [Dialects](dialects.md) for the full list.

**Missing unique constraint:** Upsert relies on database-level unique constraints to detect conflicts. If the table has no unique constraint (or the constraint does not cover the fields you expect), the behavior depends on the database:
- **Oracle/MS SQL Server/H2:** The `MERGE` statement's match condition determines conflict detection. If the match condition references columns without a unique constraint, concurrent upserts may produce duplicates.
- **PostgreSQL:** The `ON CONFLICT` clause references a specific constraint. If the constraint does not exist, the database returns a SQL error.
- **MySQL/MariaDB:** Without any unique constraint, every row is treated as a new insert. No update branch is triggered, and duplicates accumulate silently.
- **SQLite:** Behaves similarly to PostgreSQL. The `ON CONFLICT` clause references a specific constraint.

In all cases, Storm does **not** fall back to a plain insert. It always generates the upsert SQL for the configured dialect. If the SQL fails at the database level, the exception propagates to the caller.

**Joined sealed entities:** Upsert is not supported for [Joined Table](polymorphism.md#joined-table-inheritance) polymorphic entities. SQL-level upsert constructs (`ON CONFLICT`, `MERGE`) are fundamentally single-table operations. Attempting an upsert on a joined sealed entity throws an `UnsupportedOperationException`. Use `insert()` and `update()` separately instead.

## Requirements

1. **Database dialect** - include the appropriate dialect dependency for your database (see [Dialects](dialects.md))
2. **Unique constraints** - the table must have a primary key or unique constraint for conflict detection
3. **Null ID for new inserts** - pass default `0` (Kotlin) or `null` (Java) for the primary key field to allow the database to generate a value
4. **Not a joined sealed entity** - upsert is not supported for [Joined Table](polymorphism.md#joined-table-inheritance) polymorphic entities, because SQL-level upsert constructs (`ON CONFLICT`, `MERGE`, etc.) are fundamentally single-table operations. Use `insert()` and `update()` separately instead

## Common Use Cases

### Idempotent API Endpoints

REST APIs should be idempotent whenever possible: calling the same endpoint multiple times should produce the same result. Upserts make this straightforward. If a client retries a request (due to a timeout or network error), the second call updates the existing row instead of failing with a duplicate key violation.

```kotlin
fun syncUser(email: String, name: String, city: City): User {
    return orm upsert User(email = email, name = name, city = city)
}
```

### Data Synchronization

Import data from an external source, creating new records and updating existing ones:

```kotlin
fun syncUsersFromExternalSource(externalUsers: List<ExternalUser>) {
    val users = externalUsers.map { ext ->
        User(email = ext.email, name = ext.name, city = resolveCity(ext.city))
    }
    orm upsert users
}
```

### Configuration or Settings Tables

Key-value configuration tables are a natural fit for upserts. You want to store the latest value for a given key, regardless of whether the key already exists. Using upsert eliminates the need to check for existence before writing.

```kotlin
data class Setting(
    @PK val key: String,
    val value: String
) : Entity<String>

orm upsert Setting(key = "theme", value = "dark")
```

## Entity Definition for Upserts

For Java records, you can define a convenience constructor that omits the primary key for cleaner upsert calls:

```java
record User(@PK Integer id, String email, String name,
            LocalDate birthDate, @FK City city)
        implements Entity<Integer> {

    // Convenience constructor for inserts/upserts
    public User(String email, String name, LocalDate birthDate, City city) {
        this(null, email, name, birthDate, city);
    }
}
```

This allows you to write:

```java
orm.entity(User.class).upsert(new User("alice@example.com", "Alice", birthDate, city));
```

## Tips

1. **Use upsert for idempotent operations** - safe to retry without creating duplicates
2. **Check your constraints** - upsert relies on unique constraints to detect conflicts
3. **Use upsertAndFetch for generated IDs** (Java) - get the actual ID assigned by the database; Kotlin's `orm upsert` returns the entity with the ID populated
4. **Include the dialect dependency** - upsert requires database-specific SQL syntax; see [Dialects](dialects.md)
5. **Be mindful of multiple unique constraints** - especially on MySQL/MariaDB, where any unique constraint can trigger the update branch
