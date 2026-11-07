import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Upserts

Many applications need to create a record if it does not exist, or update it if it does. A naive approach using separate SELECT-then-INSERT-or-UPDATE logic introduces race conditions: two concurrent requests can both see that a row is missing and both attempt to insert, causing a constraint violation. Even with application-level locking, this approach adds complexity and reduces throughput.

Storm provides first-class support for upsert (insert-or-update) operations across all major databases. By delegating conflict resolution to the database engine itself, upserts behave predictably and handle race conditions atomically in a single SQL statement. No application-level locking or retry logic is needed.

Upsert resolves on the primary key. When a row with the entity's primary key exists, it is updated; otherwise the entity is inserted. It fits entities whose key the caller assigns, such as a code that arrives from an external source. Use it for idempotent writes, data synchronization, or any scenario where the same logical record may arrive multiple times.

---

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

### Single Upsert

The simplest form of upsert operates on a single entity. The examples on this page use a `Country` whose primary key is assigned by the data source, so `generation = NONE`:

```kotlin
data class Country(
    @PK(generation = NONE) val code: String,  // Assigned by the source
    val name: String
) : Entity<String>

val country = orm upsert Country(code = "NL", name = "Netherlands")
```

If a country with code `NL` exists, its name is updated. Otherwise, a new country is inserted. The returned entity is the row as the database holds it after the upsert, including any database-generated values.

### Batch Upsert

Upsert multiple entities in a single batch operation:

```kotlin
val countries = listOf(
    Country(code = "NL", name = "Netherlands"),
    Country(code = "BE", name = "Belgium"),
    Country(code = "DE", name = "Germany")
)

orm upsert countries
```

### Upsert within a Transaction

Upserts participate in transactions like any other Storm operation. Wrap several writes in a transaction when they must commit together:

```kotlin
transaction {
    orm upsert Country(code = "NL", name = "Netherlands")
    orm upsert Country(code = "BE", name = "Belgium")
}
```

</TabItem>
<TabItem value="java" label="Java">

### Single Upsert

The Java API provides upsert through the `entity()` method. The examples on this page use a `Country` whose primary key is assigned by the data source, so `generation = NONE`:

```java
record Country(@PK(generation = NONE) String code,  // Assigned by the source
               String name
) implements Entity<String> {}

orm.entity(Country.class).upsert(new Country("NL", "Netherlands"));
```

If a country with code `NL` exists, its name is updated. Otherwise, a new country is inserted.

### Upsert and Fetch

When you need the resulting entity with all database-generated values (such as default column values), use `upsertAndFetch`. This performs the upsert and returns the complete entity as it exists in the database after the operation. In Kotlin, `orm upsert` returns the entity by default, but the Java API separates `upsert` (void) from `upsertAndFetch` (returns entity) for clarity.

```java
Country country = orm.entity(Country.class).upsertAndFetch(new Country("NL", "Netherlands"));
```

### With Lombok Builder

If your entity uses Lombok's `@Builder`, you can construct upsert arguments using the builder pattern. This avoids positional constructor arguments and makes the code more readable when entities have many fields.

```java
Country country = orm.entity(Country.class).upsertAndFetch(Country.builder()
    .code("NL")
    .name("Netherlands")
    .build()
);
```

### Batch Upsert

Batch upserts process a list of entities in a single batched operation, combining JDBC batching with the database's native upsert syntax. This is significantly faster than upserting entities one at a time in a loop.

```java
List<Country> countries = List.of(
    new Country("NL", "Netherlands"),
    new Country("BE", "Belgium")
);

orm.entity(Country.class).upsert(countries);
```

</TabItem>
</Tabs>

---

## How Upsert Works

Storm does not implement upsert logic in application code. Instead, it delegates to each database platform's native upsert syntax. This ensures atomicity at the database level and avoids race conditions that would occur with application-level check-then-insert logic. The conflict target is the primary key on every database; the SQL syntax varies:

| Database | SQL Strategy | Conflict Detection |
|----------|--------------|--------------------|
| Oracle | `MERGE INTO ...` | Matches on the primary key |
| MS SQL Server | `MERGE INTO ...` | Matches on the primary key |
| PostgreSQL | `INSERT ... ON CONFLICT (pk) DO UPDATE` | The primary key |
| MySQL/MariaDB | `INSERT ... ON DUPLICATE KEY UPDATE` | The primary key or any unique key |
| SQLite | `INSERT ... ON CONFLICT (pk) DO UPDATE` | The primary key |
| H2 | `MERGE INTO ...` | Matches on the primary key |

### Database-Specific Behavior

- **Oracle**, **MS SQL Server**, and **H2** run a `MERGE` statement whose match condition compares the primary key columns of the table and the incoming row.
- **PostgreSQL** and **SQLite** name the primary key columns as the `ON CONFLICT` target. A violation of any other unique constraint is not resolved by the upsert; it fails the statement.
- **MySQL/MariaDB** have no conflict target: `ON DUPLICATE KEY UPDATE` triggers the update branch when an insert would violate the primary key **or any unique key**. When a table has several unique keys, the database decides which conflict applies, so be deliberate about the unique keys on tables you upsert into.

### Generated Primary Keys

With a key the database generates (`IDENTITY` or `SEQUENCE`), a new entity has no key value to conflict on. Storm routes such an entity by its id: with the default id (`0` in Kotlin, `null` in Java), upsert inserts it; with the id set, upsert updates the row with that id. On MySQL and MariaDB the insert still runs as `INSERT ... ON DUPLICATE KEY UPDATE`, so a conflict on another unique key turns it into an update. On the other databases it is a plain insert, and a duplicate value in a unique column fails it.

### Rows Identified by Another Unique Column

Because upsert resolves on the primary key, it cannot insert-or-update a row that is identified by another unique column, such as a user's email under a generated id. Write that statement as a [SQL template](sql-templates.md) with the database's own syntax. On PostgreSQL:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.query { """
    INSERT INTO ${User::class}
    VALUES $user
    ON CONFLICT (email) DO UPDATE
    SET birth_date = EXCLUDED.birth_date, city_id = EXCLUDED.city_id""" }.executeUpdate()
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.query(RAW."""
    INSERT INTO \{User.class}
    VALUES \{user}
    ON CONFLICT (email) DO UPDATE
    SET birth_date = EXCLUDED.birth_date, city_id = EXCLUDED.city_id""").executeUpdate();
```

</TabItem>
</Tabs>

## Failure Modes

Understanding how upserts fail helps you diagnose issues quickly and design your schema correctly.

**Missing dialect dependency:** Upsert requires a database-specific dialect module (e.g., `storm-postgresql`, `storm-mysql`). If no dialect is on the classpath, Storm throws an `UnsupportedOperationException` at runtime when you call `upsert()`. The error message indicates that the current dialect does not support upsert operations. Add the appropriate dialect dependency to resolve this. See [Dialects](dialects.md) for the full list.

**Missing primary key constraint:** Upsert resolves the conflict on the primary key, so the table needs that constraint in the database. Without it, the behavior depends on the database:
- **Oracle/MS SQL Server/H2:** The `MERGE` statement still matches on the key columns, but concurrent upserts of the same key may produce duplicates.
- **PostgreSQL/SQLite:** The database rejects an `ON CONFLICT` target that no unique index covers, and the statement fails.
- **MySQL/MariaDB:** `ON DUPLICATE KEY UPDATE` finds no conflict on the key, every row is inserted, and duplicates accumulate silently.

**Conflict on another unique key:** On every database except MySQL and MariaDB, a row that collides with an existing row on a unique column other than the primary key fails the upsert with a constraint violation. On MySQL and MariaDB it updates the existing row instead.

In all cases, Storm does **not** fall back to a plain insert. It always generates the upsert SQL for the configured dialect. If the SQL fails at the database level, the exception propagates to the caller.

**Joined sealed entities:** Upsert is not supported for [Joined Table](polymorphism.md#joined-table-inheritance) polymorphic entities. SQL-level upsert constructs (`ON CONFLICT`, `MERGE`) are fundamentally single-table operations. Attempting an upsert on a joined sealed entity throws an `UnsupportedOperationException`. Use `insert()` and `update()` separately instead.

## Requirements

1. **Database dialect** - include the appropriate dialect dependency for your database (see [Dialects](dialects.md))
2. **Primary key** - the entity and the table must have a primary key; it is the conflict target
3. **Assigned keys for natural upserts** - upsert resolves a conflict when the entity carries its key. With a generated key, the default `0` (Kotlin) or `null` (Java) inserts and a set id updates (see [Generated Primary Keys](#generated-primary-keys))
4. **Not a joined sealed entity** - upsert is not supported for [Joined Table](polymorphism.md#joined-table-inheritance) polymorphic entities, because SQL-level upsert constructs (`ON CONFLICT`, `MERGE`, etc.) are fundamentally single-table operations. Use `insert()` and `update()` separately instead

## Common Use Cases

### Idempotent API Endpoints

REST APIs should be idempotent whenever possible: calling the same endpoint multiple times should produce the same result. Upserts make this straightforward. If a client retries a request (due to a timeout or network error), the second call updates the existing row instead of failing with a duplicate key violation.

```kotlin
fun saveCountry(code: String, name: String): Country {
    return orm upsert Country(code = code, name = name)
}
```

### Data Synchronization

Import data from an external source, creating new records and updating existing ones:

```kotlin
fun syncCountriesFromExternalSource(externalCountries: List<ExternalCountry>) {
    val countries = externalCountries.map { ext ->
        Country(code = ext.isoCode, name = ext.name)
    }
    orm upsert countries
}
```

### Configuration or Settings Tables

Key-value configuration tables are a natural fit for upserts. You want to store the latest value for a given key, regardless of whether the key already exists. Using upsert eliminates the need to check for existence before writing.

```kotlin
data class Setting(
    @PK(generation = NONE) val key: String,
    val value: String
) : Entity<String>

orm upsert Setting(key = "theme", value = "dark")
```

## Tips

1. **Use upsert for idempotent operations** - safe to retry without creating duplicates
2. **Key the entity by what identifies the row** - upsert resolves on the primary key; a row identified by another unique column needs a [SQL template](#rows-identified-by-another-unique-column)
3. **Use upsertAndFetch for the stored row** (Java) - get the entity as the database holds it after the upsert; Kotlin's `orm upsert` returns it directly
4. **Include the dialect dependency** - upsert requires database-specific SQL syntax; see [Dialects](dialects.md)
5. **Be mindful of unique keys on MySQL/MariaDB** - any unique key can trigger the update branch there
