import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Frequently Asked Questions

## General

### What databases does Storm support?

Storm works with any JDBC-compatible database. Dialect packages provide optimized support for PostgreSQL, MySQL, MariaDB, Oracle, and MS SQL Server. See [Database Dialects](dialects.md).

### Does Storm require preview features?

- **Kotlin:** No. The Kotlin API has no preview dependencies.
- **Java:** Yes. The Java API is built on String Templates, a preview feature that is still evolving in the JDK. String Templates are the best way to write SQL that is both readable and injection-safe by design, and Storm ships with support today rather than waiting for the feature to stabilize. If you prefer a stable API right now, the Kotlin API requires no preview features. Only `storm-java21` depends on this preview feature. The Java API is production-ready from a quality perspective, but its API surface will adapt as String Templates move toward a stable release.

### Can I use Storm with Spring Boot?

Yes. Storm integrates seamlessly with Spring Boot. See [Spring Integration](spring-integration.md).

### Is Storm production-ready?

Yes. Storm is used in production environments and follows semantic versioning for stable releases.

### Does Storm support schema validation?

Yes. Storm can validate your entity definitions against the actual database schema, catching mismatches like missing tables, missing columns, type incompatibilities, type narrowing (potential precision loss), nullability differences, primary key mismatches, missing sequences, missing unique constraints, and missing foreign key constraints. This works similarly to Hibernate's `ddl-auto=validate`, but Storm never modifies the schema.

Enable it in Spring Boot:

```yaml
storm:
  validation:
    schema-mode: fail   # or "warn" or "none"
```

Or call it programmatically:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.validateSchemaOrThrow()
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.validateSchemaOrThrow();
```

</TabItem>
</Tabs>

See [Configuration: Schema Validation](configuration.md#schema-validation) for full details.

---

## What Storm Does Not Do

Storm is intentionally scoped. The following are conscious design decisions, not missing features. Each reflects a trade-off that keeps the framework simple, predictable, and free of hidden behavior.

### No Schema Generation or Migration

Storm never issues DDL statements (CREATE TABLE, ALTER TABLE, DROP TABLE). It reads and writes data, but never modifies the database structure. For schema management, use dedicated migration tools like [Flyway](https://flywaydb.org/) or [Liquibase](https://www.liquibase.org/). Storm's [schema validation](validation.md) can verify that your entities match the database at startup, serving as a safety net alongside your migration tool.

### No Lazy-Loading Proxies

Storm does not use bytecode manipulation or runtime proxies to intercept field access. This eliminates `LazyInitializationException`, hidden database queries, and session-dependent entity behavior. Relationships declared with `@FK` are loaded eagerly in a single query. When you need deferred loading (for example, a rarely-accessed large sub-graph), use `Ref<T>` to make the database access explicit and intentional. See [Entities: Deferred Loading](entities.md#deferred-loading-with-ref) for details.

### No Second-Level Cache

Storm maintains only a transaction-scoped entity cache for identity guarantees and dirty checking. There is no cross-transaction or application-wide cache. This avoids cache invalidation complexity, stale data bugs, and the configuration burden of managing cache regions. For caching reference data or frequently-read entities, use Spring's `@Cacheable` annotation or a dedicated caching layer (Redis, Caffeine) at the service level, where cache scope and invalidation strategy are explicit.

### No Bytecode Manipulation

Storm does not enhance, instrument, or proxy your entity classes at build time or runtime. Entities are plain Kotlin data classes or Java records with no hidden behavior. The metamodel is generated at compile time by a KSP plugin (Kotlin) or annotation processor (Java), but this is standard code generation, not bytecode rewriting.

---

## Entities

### Why use records/data classes instead of regular classes?

Storm entities are pure data carriers. They never need to intercept method calls, track dirty fields, or manage lifecycle state. Data classes (Kotlin) and records (Java) are the natural fit because the language enforces immutability and generates `equals`, `hashCode`, and `toString` for free. This eliminates an entire category of bugs related to mutable shared state, identity confusion, and missing boilerplate.

- **Immutability:** Prevents accidental state changes.
- **Simplicity:** No boilerplate getters/setters.
- **Equality:** Value-based equals/hashCode by default.
- **Transparency:** No hidden proxy magic.

### How do I modify a Java record entity?

Since Java records are immutable, you need to create a new instance with the changed values. There are several approaches:

**Lombok `@Builder(toBuilder = true)` (recommended):** Generates a builder that copies all fields from an existing instance. This is the most ergonomic option and is used throughout Storm's own test suite. See [Modifying Entities](entities.md#modifying-entities) for the annotation setup.

```java
var updated = user.toBuilder().email("new@example.com").build();
orm.entity(User.class).update(updated);
```

**Canonical constructor:** Call the record constructor directly. This works but becomes unwieldy as the number of fields grows.

```java
var updated = new User(user.id(), "new@example.com", user.name(), user.city());
```

**Custom wither methods:** Define `with*` methods on the record that return a new instance with a single field changed. Clean API but requires a method per field.

```java
record User(@PK Integer id, @Nonnull String email, @Nonnull String name, @FK City city
) implements Entity<Integer> {
    User withEmail(String email) { return new User(id, email, name, city); }
}
```

**Future: JEP 468 (Derived Record Creation):** Java has proposed language-level support through [JEP 468](https://openjdk.org/jeps/468), which would allow concise copy-with-modification syntax without any external tooling:

```java
// Proposed syntax (not yet available)
var updated = user with { email = "new@example.com"; }
```

Until this feature is finalized, `@Builder(toBuilder = true)` remains the recommended approach.

:::note
Kotlin data classes have a built-in `copy()` method that handles this naturally: `user.copy(email = "new@example.com")`.
:::

### Can I use inheritance with Storm entities?

Kotlin data classes and Java records cannot extend other classes, but Storm supports polymorphic entity hierarchies using sealed interfaces. A sealed interface defines the type hierarchy, and each permitted subtype is a record or data class. Storm provides three inheritance strategies: **Single-Table** (all subtypes in one table), **Joined Table** (base table plus extension tables), and **Polymorphic FK** (independent tables referenced via a two-column foreign key). See the [Polymorphism](polymorphism.md) guide for details.

To share fields across unrelated entities (without a polymorphic hierarchy), extract them into an embedded record or data class and include it as a field.

### Which discriminator type should I use (STRING, INTEGER, CHAR)?

Storm supports three discriminator column types via the `type()` attribute on `@Discriminator`:

- **STRING** (default): Uses a `VARCHAR` column. Values are human-readable strings like the class name (`"Cat"`, `"Dog"`) or custom labels. This is the best choice for most new schemas because the discriminator values are self-documenting in the database.
- **INTEGER**: Uses an `INTEGER` column. Each subtype must declare an explicit numeric value (e.g., `@Discriminator("1")`). Use this when your schema already has a numeric type code column, or when you need compact discriminator storage on high-volume tables.
- **CHAR**: Uses a `CHAR(1)` column. Each subtype must declare a single-character value (e.g., `@Discriminator("C")`). This provides a compact, fixed-width discriminator that is still somewhat readable.

If you are designing a new schema, `STRING` is the simplest choice. If you are integrating with an existing schema that uses integer or character type codes, use `INTEGER` or `CHAR` to match. See [Polymorphism: Discriminator Types](polymorphism.md#discriminator-types) for code examples.

### Why does the Polymorphic FK sealed interface extend `Data` instead of `Entity`?

In Storm, `Entity<ID>` represents a type backed by a specific database table. For Polymorphic FK, the sealed interface does not correspond to any table. It groups unrelated entities under a common type so they can be referenced by a two-column foreign key (discriminator + ID). Because the interface has no table, it extends `Data` (a marker for types that participate in SQL generation without owning a table). Each subtype independently implements `Entity<ID>` because each one maps to its own independent table.

This design ensures that Storm treats the sealed interface as a type constraint rather than a table reference. The discriminator column in the referencing entity identifies which subtype (and therefore which table) the foreign key points to, while the ID column identifies the specific row.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Data: no table, just a type grouping
sealed interface Commentable : Data {
    // Entity: has its own table
    data class Post(@PK val id: Int = 0, val title: String) : Commentable, Entity<Int>
    data class Photo(@PK val id: Int = 0, val url: String) : Commentable, Entity<Int>
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Data: no table, just a type grouping
sealed interface Commentable extends Data permits Post, Photo {}

// Entity: has its own table
record Post(@PK Integer id, String title) implements Commentable, Entity<Integer> {}
record Photo(@PK Integer id, String url) implements Commentable, Entity<Integer> {}
```

</TabItem>
</Tabs>

See [Polymorphism: Polymorphic Foreign Keys](polymorphism.md#polymorphic-foreign-keys) for the full explanation.

### How do I handle auto-generated IDs?

Storm detects auto-generated IDs by checking whether the primary key is set to its default value (Kotlin) or `null` (Java). When inserting an entity with a null or default-valued primary key, Storm omits the ID from the INSERT statement and lets the database assign it. The generated ID is returned and available on the inserted instance.

```kotlin
data class User(@PK val id: Int = 0, val name: String) : Entity<Int>

val user = orm insert User(name = "Alice")  // id will be populated
```

### Can I use UUID primary keys?

Yes. Storm supports any type as a primary key, including `UUID`. When using UUIDs, you typically generate the ID on the client side rather than relying on database auto-increment. This works well for distributed systems where coordination-free ID generation is important.

```kotlin
data class User(@PK val id: UUID = UUID.randomUUID(), val name: String) : Entity<UUID>
```

---

## Data Classes

### When should I use Data vs Entity vs Projection vs plain records?

Storm provides multiple data class types to match different use cases. `Entity<ID>` is the primary type for tables you read from and write to. `Projection<ID>` maps to the same table but exposes a subset of columns, useful for read-heavy queries where you do not need the full row. `Data` is a marker interface for ad-hoc query results that span multiple tables or include computed columns; Storm can still generate SQL fragments for `Data` types. Plain records (with no Storm interface) work when you write the entire SQL yourself and just need result mapping.

| Use Case | Type | Example |
|----------|------|---------|
| Reusable types for CRUD operations | `Entity<ID>` | `User`, `Order` |
| Reusable read-only views | `Projection<ID>` | `UserSummary`, `OrderView` |
| Single-use query with SQL template support | `Data` | Ad-hoc joins with SQL generation |
| Single-use query with complete manual SQL | Plain record | Complex aggregations, CTEs |

See [SQL Templates](sql-templates.md) for details on using `Data` and plain records.

---

## Queries

### How do I prevent N+1 queries?

You do not need to take any special action. Storm prevents N+1 queries by design. When you define a relationship with `@FK`, Storm generates a single SQL query that joins the related tables and hydrates the entire entity graph from one result set. There is no lazy loading that triggers additional queries behind the scenes. If you need a reference to a related entity without loading its full graph, use `Ref<T>` to defer fetching until you explicitly call `fetch()`.

### Can I write raw SQL?

Yes. Use SQL templates for raw queries:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.query { "SELECT * FROM user WHERE email = $email" }
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.query(RAW."SELECT * FROM user WHERE email = \{email}")
    .getResultList();
```

</TabItem>
</Tabs>

Interpolated values like `email` are automatically converted to bind variables (`?`) in the generated SQL, preventing SQL injection.

### How do I handle pagination?

Storm supports two strategies. **Offset-based pagination** uses `offset()` and `limit()` on the query builder, which translates directly to SQL `OFFSET` and `LIMIT`. This works well for small tables or when users need to jump to arbitrary page numbers.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val page = orm.entity(User::class)
    .select()
    .orderByDescending(User_.createdAt)
    .offset(20)
    .limit(10)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
var page = orm.entity(User.class)
    .select()
    .orderByDescending(User_.createdAt)
    .offset(20)
    .limit(10)
    .getResultList();
```

</TabItem>
</Tabs>

For large tables where users scroll through results sequentially, prefer **scrolling** via `scroll()` with a `Scrollable`. This is available directly on repositories and on the query builder, and remains performant regardless of how deep into the result set you are. `Window` intentionally does not include a total element count, since a separate `COUNT(*)` must execute the same joins and filters as the main query, which can be expensive on large or complex result sets. Total counts are also inherently unstable, as rows may be inserted or deleted while a user navigates through pages. If you need a total count separately, use the `count` (Kotlin) or `getCount()` (Java) method on the query builder. See [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling) for a full explanation.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val window = userRepository.scroll(Scrollable.of(User_.id, 20))
// nextScrollable() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
val next = userRepository.scroll(window.nextScrollable())
```

</TabItem>
<TabItem value="java" label="Java">

```java
Window<User> window = userRepository.scroll(Scrollable.of(User_.id, 20));
// nextScrollable() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
Window<User> next = userRepository.scroll(window.nextScrollable());
```

</TabItem>
</Tabs>

### Why does my DELETE without a WHERE clause throw an exception?

By default, Storm rejects DELETE and UPDATE queries that have no WHERE clause with a `PersistenceException`. This is a safety mechanism that prevents accidental deletion or modification of every row in a table.

This protection is particularly valuable because `QueryBuilder` is immutable. If you accidentally ignore the return value of `where()` on a delete builder, the WHERE clause is silently lost and the query would affect all rows. The safety check catches this at runtime:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// This throws PersistenceException: the where() return value is discarded,
// so the delete has no WHERE clause and Storm blocks it.
val builder = userRepository.delete()
builder.where(User_.city eq city)
builder.executeUpdate()

// Correct: chain the calls so the WHERE clause is included.
userRepository.delete()
    .where(User_.city eq city)
    .executeUpdate()
```

</TabItem>
<TabItem value="java" label="Java">

```java
// This throws PersistenceException: the where() return value is discarded,
// so the delete has no WHERE clause and Storm blocks it.
var builder = userRepository.delete();
builder.where(User_.city, EQUALS, city);
builder.executeUpdate();

// Correct: chain the calls so the WHERE clause is included.
userRepository.delete()
    .where(User_.city, EQUALS, city)
    .executeUpdate();
```

</TabItem>
</Tabs>

If you genuinely need to delete all rows from a table, use the `deleteAll()` convenience method:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
userRepository.deleteAll()
```

</TabItem>
<TabItem value="java" label="Java">

```java
userRepository.deleteAll();
```

</TabItem>
</Tabs>

Alternatively, you can use the builder approach and call `unsafe()` to opt out of the safety check:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
userRepository.delete().unsafe().executeUpdate()
```

</TabItem>
<TabItem value="java" label="Java">

```java
userRepository.delete().unsafe().executeUpdate();
```

</TabItem>
</Tabs>

The `unsafe()` method signals that the absence of a WHERE clause is intentional. Without it, Storm assumes the missing WHERE clause is a mistake. The `deleteAll()` convenience method calls `unsafe()` internally.

### Can I use database-specific functions?

Yes. Use SQL templates for database-specific SQL:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.query { "SELECT * FROM user WHERE LOWER(email) = LOWER($email)" }
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.query(RAW."SELECT * FROM user WHERE LOWER(email) = LOWER(\{email})")
    .getResultList();
```

</TabItem>
</Tabs>

---

## Relationships

### How do I model one-to-many relationships?

Storm does not store collections on entities. This is intentional: collection fields on entities are the root cause of lazy loading, N+1 queries, and unpredictable fetch behavior in JPA. Instead, query the "many" side explicitly. This makes the database access visible in your code and gives you full control over filtering, ordering, and pagination of the related records.

```kotlin
// Instead of user.orders (not supported)
val orders = orm.findAll(Order_.user eq user)
```

### Why doesn't Storm support lazy loading?

Lazy loading requires runtime proxies that intercept method calls on entity fields. This introduces hidden database access, makes entity behavior depend on session state, and is the primary source of `LazyInitializationException` in JPA applications. Storm avoids this entirely by loading the full entity graph in one query. When you genuinely need to defer loading of a relationship (for example, a rarely-accessed large sub-graph), use `Ref<T>`. A `Ref` holds only the foreign key ID until you explicitly call `fetch()`, making the database access visible and intentional.

```kotlin
data class User(@PK val id: Int = 0, @FK val department: Ref<Department>) : Entity<Int>
```

### How do I handle circular references?

Circular references (such as an employee who references a manager, who is also an employee) would cause infinite recursion during eager loading. Use `Ref<T>` to break the cycle. The `Ref` stores only the foreign key ID, preventing Storm from recursively loading the full graph. You can fetch the referenced entity on demand when needed.

```kotlin
data class Employee(@PK val id: Int = 0, @FK val manager: Ref<Employee>?) : Entity<Int>
```

---

## Transactions

### How do transactions work in Kotlin?

Storm provides a `transaction {}` block that wraps its body in a JDBC transaction. The block commits automatically on successful completion and rolls back on any exception. You can nest transactions with propagation modes (such as `NESTED` for savepoints or `REQUIRES_NEW` for independent transactions). Inside the block, all Storm operations share the same connection and participate in the same transaction.

```kotlin
transaction {
    orm insert User(name = "Alice")
    // Commits on success, rolls back on exception
}
```

### Can I use Spring's @Transactional?

Yes. Storm participates in Spring-managed transactions automatically. Enable transaction integration for Kotlin to mix declarative and programmatic styles.

### How do I do nested transactions?

Use propagation modes:

```kotlin
transaction(propagation = REQUIRED) {
    transaction(propagation = NESTED) {
        // Creates savepoint; can rollback independently
    }
}
```

---

## Performance

### Is Storm fast?

Yes. Storm adds minimal overhead on top of JDBC. There are no runtime proxies, no bytecode enhancement, and no reflection on the hot path (when using the generated metamodel). The framework generates SQL at query build time and executes it directly through JDBC prepared statements. Key performance features:
- Single-query entity graph loading
- Batch insert/update/delete
- Streaming for large result sets
- Connection pooling support

### How do I optimize large result sets?

Loading millions of rows into a `List` consumes proportional memory and delays processing until the entire result set is fetched. Streaming processes rows one at a time as the database returns them, keeping memory usage constant regardless of result set size. In Kotlin, Storm exposes streams as `Flow`, which integrates naturally with coroutines.

```kotlin
val users: Flow<User> = orm.entity(User::class).selectAll()
users.collect { processUser(it) }
```

### How does dirty checking work?

When you read an entity within a transaction, Storm stores the original field values in the entity cache. When you later call `update()` with a modified copy, Storm compares the new values against the cached original to determine which fields actually changed. In `FIELD` mode, only the changed columns appear in the UPDATE statement. In `ENTITY` mode, Storm issues a full-row update but can skip the statement entirely if nothing changed. See [Dirty Checking](dirty-checking.md) for configuration details.

---

## Troubleshooting

### My where/orderBy/limit clause has no effect

`QueryBuilder` is immutable. Every builder method returns a *new* instance with the modification applied, leaving the original unchanged. If you call a method like `where()`, `orderBy()`, or `limit()` and ignore the return value, the change is silently lost.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Wrong: the where clause is discarded
val builder = userRepository.select()
builder.where(User_.active, EQUALS, true)   // returns a new builder, but it's ignored
builder.resultList                           // executes without the WHERE clause

// Correct: chain the calls
val results = userRepository.select()
    .where(User_.active, EQUALS, true)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Wrong: the where clause is discarded
var builder = userRepository.select();
builder.where(User_.active, EQUALS, true);   // returns a new builder, but it's ignored
builder.getResultList();                      // executes without the WHERE clause

// Correct: chain the calls
var results = userRepository.select()
        .where(User_.active, EQUALS, true)
        .getResultList();
```

</TabItem>
</Tabs>

This applies to all builder methods: `where()`, `orderBy()`, `limit()`, `offset()`, `distinct()`, `groupBy()`, `having()`, joins, and locking methods like `forUpdate()`. Always use the returned builder.

For DELETE and UPDATE queries, this mistake is especially dangerous because a lost WHERE clause means the operation applies to every row in the table. Storm guards against this by default: executing a DELETE or UPDATE without a WHERE clause throws a `PersistenceException`. See [Why does my DELETE without a WHERE clause throw an exception?](#why-does-my-delete-without-a-where-clause-throw-an-exception) below for details.

### My entity isn't mapping correctly

Storm maps entity fields to database columns by converting field names from camelCase to snake_case. If your schema uses a different convention, explicit column annotations are required. The most common mapping issues stem from missing annotations or name mismatches.

1. Check that `@PK` is present on the primary key field.
2. Verify field names match database columns (or use `@DbColumn`).
3. Ensure the entity implements `Entity<T>` for repository operations.

### I'm getting "column not found" errors

Storm uses snake_case by default. `birthDate` maps to `birth_date`. Use `@DbColumn` for custom mappings:

```kotlin
@DbColumn("dateOfBirth") val birthDate: LocalDate
```

### Upsert isn't working

Upsert (INSERT ... ON CONFLICT) is a database-specific feature. Storm delegates to the dialect module for your database to generate the correct SQL. Without the dialect dependency, Storm cannot produce the upsert syntax.

1. Ensure you have included the dialect dependency for your database.
2. Verify your table has a primary key or unique constraint.
3. Pass default `0` (Kotlin) or `null` (Java) for the primary key.

### Refs won't fetch

A `Ref` created manually with `Ref.of(Type.class, id)` holds only the foreign key value. It is not connected to a database session and cannot fetch the referenced entity. Only `Ref` instances loaded from the database within an active transaction have the context needed to execute the fetch query. If you need to resolve a reference by ID, use the repository's `findById()` or `getById()` method instead.

### Streams are empty or already closed

Storm's Java streams are backed by a JDBC `ResultSet`, which is tied to the database connection. The stream must be consumed within the scope that opened it. Returning an unconsumed stream from a try-with-resources block closes the underlying `ResultSet` before the caller can read any rows. Either consume the stream inside the block or ensure the caller is responsible for closing it.

```java
// Wrong: stream closed before consumption
Stream<User> getUsers() {
    try (var users = orm.entity(User.class).selectAll()) {
        return users;  // Stream is closed when method returns
    }
}

// Right: consume within the block
List<User> getUsers() {
    try (var users = orm.entity(User.class).selectAll()) {
        return users.toList();
    }
}
```

### How do I see the SQL Storm generates?

Annotate your repository with `@SqlLog` to log all generated SQL:

```java
@SqlLog
public interface UserRepository extends EntityRepository<User, Integer> { ... }
```

To see executable SQL with actual parameter values instead of `?` placeholders, use `inlineParameters`:

```java
@SqlLog(inlineParameters = true)
public interface UserRepository extends EntityRepository<User, Integer> { ... }
```

See [SQL Logging](sql-logging.md) for the full guide.

### Schema validation reports type narrowing warnings for my Integer columns

Some databases (notably Oracle) use a single numeric type for all integer columns. For example, Oracle's `NUMBER` maps to `java.sql.Types.NUMERIC`, which Storm considers a "narrowing" conversion for `Integer` fields. These are logged as warnings because the mapping works at runtime but may involve precision differences.

If the warnings are expected, you can suppress them per field with `@DbIgnore`:

```kotlin
data class User(
    @PK val id: Int = 0,
    @DbIgnore("Oracle NUMBER maps to NUMERIC")
    val score: Int
) : Entity<Int>
```

Alternatively, enable strict mode to treat these warnings as errors if you want zero tolerance:

```yaml
storm:
  validation:
    strict: true
```

See [Configuration: Schema Validation](configuration.md#schema-validation) for details.

### Can I use Storm without Spring?

Yes. Storm has no dependency on Spring. Create an `ORMTemplate` from any JDBC `DataSource`:

```kotlin
val orm = ORMTemplate.of(dataSource)
```

Spring integration is optional via the `storm-spring` or `storm-kotlin-spring` modules.
