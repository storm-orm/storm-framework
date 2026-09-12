# Static Metamodel

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

The static metamodel is a code generation feature that creates companion classes for your entities at compile time. These generated classes provide type-safe references to entity fields, enabling the compiler to catch errors that would otherwise surface only at runtime.

Using the metamodel is optional. Storm works without it using SQL Templates or string-based field references. With it, you get compile-checked field references, IDE completion on entity paths, and refactorings that reach into your queries.

## Why Use a Metamodel?

Storm uses Kotlin data classes and Java records as entities. While this stateless approach simplifies the programming model, it presents a challenge: how do you reference entity fields in a type-safe way without using reflection at runtime?

The metamodel solves this by generating accessor classes during compilation. These classes provide direct access to record components without reflection, which offers two advantages:

- **Performance.** No reflection overhead when accessing field metadata or values.
- **Type safety.** The compiler verifies field references, catching typos and type mismatches before your code runs.

## What is the Metamodel?

For each entity class, Storm generates a corresponding metamodel class with a `_` suffix (following the JPA naming convention):

```
┌─────────────────┐                      ┌─────────────────┐
│     Entity      │    KSP / Annotation  │    Metamodel    │
│                 │      Processor       │                 │
│  User.kt        │  ─────────────────►  │  User_.java     │
│  City.kt        │                      │  City_.java     │
│  Country.kt     │                      │  Country_.java  │
└─────────────────┘                      └─────────────────┘
```

The metamodel contains typed references to each field that can be used in queries.

## Installation

The metamodel is **optional**. Storm works without it using SQL Templates or string-based field references. However, if you want compile-time type safety for your queries, you need to configure a code generator that creates the metamodel classes during compilation.

- **Kotlin projects** use KSP (Kotlin Symbol Processing)
- **Java projects** use an annotation processor

The generator scans your entity classes and creates corresponding metamodel classes (e.g., `User_` for `User`) in the same package.

The [Storm Gradle plugin](installation.md#gradle-plugin-recommended) configures this generator for you, so if you apply it you can skip the manual setup below. The configuration here is for Maven and for Gradle users who prefer explicit setup.

### Gradle (Kotlin with KSP)

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.3.10"
}

dependencies {
    ksp("st.orm:storm-metamodel-ksp:@@STORM_VERSION@@")
}
```

### Gradle (Java)

```kotlin
annotationProcessor("st.orm:storm-metamodel-processor:@@STORM_VERSION@@")
```

### Maven (Java)

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-metamodel-processor</artifactId>
    <version>@@STORM_VERSION@@</version>
    <scope>provided</scope>
</dependency>
```

> **Important:** Metamodel classes are generated at compile time. When you create or modify an entity, you must rebuild your project (or run the KSP/annotation processor task) before the corresponding metamodel class becomes available. Until then, your IDE will show errors for references like `User_`.

## Usage

Once the metamodel is generated, you use the `_` suffixed classes in place of string-based field references throughout your queries. The metamodel provides type-safe field accessors that the compiler can verify, so a renamed or removed field produces a compile error rather than a runtime exception. The following examples demonstrate the metamodel in queries for both Kotlin and Java.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Type-safe field reference
val users = orm.findAll(User_.email eq email)

// Type-safe access to nested fields throughout the entire entity graph
val users = orm.findAll(User_.city.country.code eq "US")

// Multiple conditions
val users = orm.entity<User>()
    .select()
    .where(
        (User_.city eq city) and (User_.birthDate less LocalDate.of(2000, 1, 1))
    )
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Type-safe field reference
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.email, EQUALS, email)
    .getResultList();

// Type-safe access to nested fields throughout the entire entity graph
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.city.country.code, EQUALS, "US")
    .getResultList();
```

### SQL Templates (Java)

```java
Optional<User> user = orm.query(RAW."""
        SELECT \{User.class}
        FROM \{User.class}
        WHERE \{User_.email} = \{email}""")
    .getOptionalResult(User.class);
```

</TabItem>
</Tabs>

## Path Resolution

Storm supports two forms of metamodel references: **nested paths** and **short form**. They resolve differently: a nested path is always unambiguous, while a short form can fail at runtime if the table it names appears more than once in the entity graph.

Consider an entity graph where `User` has a `city` field pointing to `City`, which has a `country` field pointing to `Country`:

```
┌────────┐       ┌────────┐       ┌──────────┐
│  User  │──────►│  City  │──────►│ Country  │
└────────┘ city  └────────┘country└──────────┘
```

### Nested Paths (Fully Qualified)

Nested paths traverse from the root entity through relationships by chaining field accessors:

```kotlin
// Start from User, traverse city → country → name
User_.city.country.name eq "United States"
```

Each step in the path corresponds to a foreign key relationship in your entity model:

```
User_.city          → User has FK to City
      .country      → City has FK to Country
             .name  → Country.name column
```

**Why nested paths are always unambiguous:**

When you write `User_.city.country.name`, Storm knows exactly which tables to join and in what order. Even if `Country` appears multiple times in your entity graph (e.g., via different relationships), the nested path explicitly identifies which occurrence you mean.

Storm automatically generates the necessary JOINs based on the path. For the example above:

```sql
SELECT ...
FROM user u
INNER JOIN city c ON u.city_id = c.id
INNER JOIN country co ON c.country_id = co.id
WHERE co.name = 'United States'
```

Each segment of the path gets its own table alias, and Storm tracks the mapping between paths and aliases internally.

### Short Form

Short form uses the target table's metamodel directly:

```kotlin
// Reference Country directly
Country_.name eq "United States"
```

Short form works when the entity graph holds **one occurrence of the table that you could mean**. If `Country` is referenced from two different places, Storm cannot determine which one you mean.

**Example where short form works:**

```kotlin
data class User(
    @PK val id: Int = 0,
    val name: String,
    @FK val city: City       // City → Country (only path to Country)
) : Entity<Int>

// Short form works - Country appears only once in User's entity graph
val users = orm.entity<User>().select {
    where(Country_.name eq "United States")  // Resolves to User → City → Country
}.resultList
```

The short form `Country_.name` works here because Storm first establishes `User` as the root entity, then looks up `Country` in User's entity graph. Since there's only one path to `Country` (via `city.country`), it's unambiguous.

**Where each form is accepted:** the chained builder is typed to the root until a join, so before any join its `where()` takes root paths only — a `Country`-typed predicate does not compile there. Short form is accepted wherever the query is not root-typed: inside the `select { }` block, on the chained builder from the first join onward, and after an explicit `widen()` — the opt-in for short-form references on a query that joins nothing. A nested path (`User_.city.country.name`) starts at the root, so it is accepted everywhere.

**Type safety considerations:**

- A **nested path** is fully type-safe: it is rooted at the query's entity type, so column lookup is guaranteed to succeed at runtime.
- A **short form** reference is type-safe for the values you pass (e.g., comparing a `String` field to a `String` value), but the column lookup may fail at query time if the referenced table doesn't exist in the query or appears multiple times (ambiguity). The error names the entity and the query root. Use nested paths or ensure uniqueness to avoid these.

**Example where short form fails:**

When `Country` appears multiple times in the entity graph, Storm cannot determine which one you mean:

```
                    ┌────────┐       ┌──────────┐
              ┌────►│  City  │──────►│ Country  │  (path 1: city.country)
┌────────┐    │     └────────┘country└──────────┘
│  User  │────┤
└────────┘    │                      ┌──────────┐
              └─────────────────────►│ Country  │  (path 2: birthCountry)
                    birthCountry     └──────────┘
```

```kotlin
data class User(
    @PK val id: Int = 0,
    val name: String,
    @FK val city: City,           // City → Country (path 1)
    @FK val birthCountry: Country  // Direct reference (path 2)
) : Entity<Int>

// ERROR at query time: Multiple paths to Country in User's entity graph
val users = orm.entity<User>().select {
    where(Country_.name eq "United States")
}.resultList

// OK: Nested paths are unambiguous (and can use where since they're rooted at User_)
val users = orm.entity<User>()
    .select()
    .where(User_.city.country.name eq "United States")
    .resultList

val users = orm.entity<User>()
    .select()
    .where(User_.birthCountry.name eq "United States")
    .resultList
```

When Storm detects ambiguity, it throws an exception with a message indicating which paths are available.

### Custom Joins

Sometimes you need to join a table that has no `@FK` relationship defined in your entity model. For example, you might query users and filter by their orders without adding an `orders` field to the `User` entity. Custom joins add these tables to the query at runtime, making them available for filtering and projection.

Custom joins add tables that are not part of the entity graph:

```
Entity Graph                          Custom Join
─────────────                         ───────────
┌────────┐       ┌────────┐
│  User  │──────►│  City  │           ┌─────────┐
└────────┘       └────────┘      ┌───►│  Order  │  (added via innerJoin)
     │                           │    └─────────┘
     └───────────────────────────┘
         (manual join)
```

When you add custom joins to a query, those joined tables can **only** be referenced using short form:

```kotlin
val users = orm.entity<User>()
    .select()
    .innerJoin<Order>().on<User>()  // Custom join widens the query
    .where(Order_.total greater BigDecimal(100))  // Short form required
    .resultList
```

Custom joins are not part of the entity graph traversal, so nested paths cannot reach them. The short form works here because Storm registers the custom join's alias, and the join has widened the query so `where()` accepts an `Order`-typed predicate.

**Uniqueness still applies:** If you join the same table multiple times, you must use the `join` method with explicit aliases to disambiguate:

```kotlin
val users = orm.entity<User>()
    .select()
    .join(JoinType.inner(), Order::class, "recent").on(User::class)
    .join(JoinType.inner(), Order::class, "first").on(User::class)
    .where(/* use SQL template with explicit aliases */)
    .resultList
```

### Resolution Order

When resolving a metamodel reference, Storm follows this order:

1. **Nested path.** If a path is specified (e.g., `User_.city.country`), use the alias for that specific traversal.
2. **Unique table lookup.** If short form (e.g., `Country_`), check whether the entity graph or registered joins hold a single occurrence of the table that you could mean.
3. **Error.** If more than one distinct occurrence exists, throw an exception indicating the ambiguity.

### Best Practices

1. **Prefer nested paths** for clarity and to avoid ambiguity issues
2. **Use short form** for custom joins (required) or when you're certain the table is unique
3. **Check error messages.** Storm tells you which paths are available when ambiguity is detected.

## Navigating Through Refs

A foreign key declared as `Ref<T>` also gets a metamodel, so you can navigate through it the same way you navigate a directly-referenced entity. The difference is where the join goes: a `Ref` is selected as its foreign key column and is never hydrated, so navigating beyond it adds the join *on demand*, only for the query that references a column past the foreign key. A query that stops at the reference emits no join for the referenced table.

Given `User` with `@FK val city: Ref<City>` and `City` with a `country` foreign key:

```kotlin
// Navigates beyond the city reference: Storm adds the city and country joins for this query.
User_.city.country.name eq "United States"

// Selecting the root leaves User.city as an unloaded Ref; no join is added.
orm.entity<User>().select().resultList
```

### Value Nodes and Navigation-Only Nodes

The metamodel distinguishes two kinds of node, and the distinction is enforced by the type system:

- A **value node** (`Metamodel` / `TypedMetamodel`) can both name a column in a query and extract that field's value from an in-memory record. Every node in the eagerly-hydrated entity graph is a value node, and so is the reference node itself (`User_.city`), whose value is the `Ref`.
- A **navigation-only node** (`Navigable`, but not `Metamodel`) can name a column in a query but cannot extract a value from a record. Every node reached *beyond* a reference is navigation-only, because a `Ref` is never hydrated into the parent, so there is no in-memory value to read past it.

Navigation-only nodes can be used anywhere a query needs a column reference: `where`, `orderBy`, `groupBy`, `having`, and custom selected columns. They cannot be passed to value operations. Because `resultGroupedBy` reads the group key from each hydrated record, it accepts only value nodes, so grouping by a beyond-reference path does not compile. Group by the reference itself with `resultGroupedByRef` instead, which reads the foreign key without hydrating the target:

```kotlin
// Compiles: the reference node is value-extractable and yields the Ref.
val visitsByPet: Map<Ref<Pet>, List<Visit>> = orm.entity<Visit>()
    .select()
    .resultGroupedByRef(Visit_.pet)

// Does not compile: a node beyond the reference is navigation-only.
// orm.entity<Visit>().select().resultGroupedBy(Visit_.pet.owner)
```

A path is not the only way to reach the referenced table. Naming the table by type does it too: selecting it (`select(City::class)`) joins it and hydrates it with its own foreign keys, and joining onto it (`innerJoin<X>().on<City>()`) brings it in. Both share the occurrence a navigating path resolves against, so a query that names the table both ways joins it once. A table the query joins explicitly keeps that occurrence.

### Cyclic References

A reference may point back at a table already on the path, either at the record that declares it or through a longer cycle. Such a path joins the table to itself, and each occurrence is joined under its own alias, so a predicate or ordering resolves against the occurrence the path reached:

```kotlin
// Users invited by Alice. The user table is joined to itself once.
User_.invitedBy.name eq "Alice"
// SELECT ... FROM user u LEFT JOIN user u1 ON u.invited_by_id = u1.id WHERE u1.name = ?
```

A nullable reference is joined with an outer join, so ordering by a column beyond it keeps rows whose reference is null.

**The typed metamodel navigates a cycle two hops deep.** Generated metamodels construct their children eagerly, so a class cannot reference itself without recursing forever. The generator therefore emits two classes for a cyclic type, a reference metamodel and a navigation metamodel, and breaks the cycle at a navigation leaf below them:

```
User_.invitedBy                             reference metamodel, with navigation children (id, name, invitedBy)
User_.invitedBy.name                        compiles
User_.invitedBy.invitedBy.name              compiles, joining the user table to itself twice
User_.invitedBy.invitedBy.invitedBy         exists, but is a navigation leaf and has no children
User_.invitedBy.invitedBy.invitedBy.name    does not compile
```

**The query engine itself has no depth limit.** To navigate a cycle beyond the generated depth, name the path as a string; the engine adds one join per hop:

```kotlin
orm.entity<User>()
    .select()
    .where(Metamodel.of(User::class.java, "invitedBy.invitedBy.invitedBy.name") eq "Alice")
    .resultList
```

This bound applies only to cycles. A path that keeps reaching new tables is generated to its full depth, including a chain that crosses more than one reference.

A direct (non-`Ref`) self-referential foreign key is rejected at generation time: a self-reference must be a `Ref`, which is also what keeps the eagerly hydrated entity graph finite. See [Preventing Circular Dependencies](refs.md#preventing-circular-dependencies).

## Generated Code

Understanding the generated code helps when debugging or reading compiler errors. The metamodel mirrors your entity structure, creating a static field for each entity field. Each field carries generic type parameters that encode both the root entity type and the field's value type, which is how the compiler enforces type safety in queries.

```
Entity                              Metamodel
──────                              ─────────
User                                User_
 ├── id: Int (PK)                    ├── id      → Metamodel.Key<User, Int>
 ├── email: String                   ├── email   → Metamodel<User, String>
 ├── name: String                    ├── name    → Metamodel<User, String>
 └── city: City (FK)                 └── city    → CityMetamodel<User>
                                                      ├── id
                                                      ├── name
                                                      └── country → CountryMetamodel<User>
```

For an entity like:

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    val name: String,
    @FK val city: City
) : Entity<Int>
```

The metamodel generates an interface with typed field accessors:

```java
@Generated("st.orm.metamodel.MetamodelProcessor")
public interface User_ extends Metamodel<User, User> {
    /** Represents the {@link User#id} field. */
    AbstractKeyMetamodel<User, Integer, Integer> id = ...;
    /** Represents the {@link User#email} field. */
    AbstractMetamodel<User, String, String> email = ...;
    /** Represents the {@link User#name} field. */
    AbstractMetamodel<User, String, String> name = ...;
    /** Represents the {@link User#city} foreign key. */
    CityMetamodel<User> city = ...;
}
```

Foreign key fields like `city` generate their own metamodel classes, enabling navigation through relationships with full type safety.

A foreign key declared as `Ref<T>` generates a **reference metamodel**. The reference node is a value node whose value is the `Ref`, and its children are navigation-only nodes for the referenced type:

```
Entity                              Metamodel
──────                              ─────────
User                                User_
 ├── id: Int (PK)                    ├── id       → Metamodel.Key<User, Int>
 └── city: Ref<City> (FK)            └── city     → CityRefMetamodel<User>   (value node: getValue → Ref<City>)
                                                      ├── id                 (navigation-only)
                                                      └── country            (navigation-only)
                                                            └── name         (navigation-only)
```

Selecting `User_.city` selects the foreign key column and yields a `Ref<City>`; navigating past it (`User_.city.country.name`) adds the join on demand. The target's primary key is the exception: a reference carries it (`ref.id()` reads it without fetching), so `User_.city.id` resolves to the foreign key column itself and needs no join, matching what an entity foreign key resolves its primary key to. See [Navigating Through Refs](#navigating-through-refs).

A reference that points back at the record declaring it generates a childless variant instead, so the reference stays selectable while navigating past it does not compile. See [Cyclic References](#cyclic-references).

## Unique Keys (`@UK`) and `Metamodel.Key`

Use `@UK` on fields that have a unique constraint in the database. Fields annotated with `@UK` indicate that the corresponding column contains unique values. The metamodel processor generates `Metamodel.Key` instances for these fields, enabling type-safe single-result lookups and scrolling.

The `@PK` annotation is meta-annotated with `@UK`, so primary key fields are automatically recognized as unique keys without needing an explicit `@UK` annotation.

### Defining Unique Keys

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    @UK val email: String,
    val name: String
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record User(@PK Integer id,
            @UK String email,
            String name
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

The metamodel processor generates `Metamodel.Key` fields for `id` (via `@PK`) and `email` (via `@UK`):

```
User_
 ├── id      → Metamodel.Key<User, Integer>   (via @PK, which implies @UK)
 ├── email   → Metamodel.Key<User, String>    (via @UK)
 └── name    → Metamodel<User, String>
```

### Compound Unique Keys

For compound unique constraints spanning multiple columns, use an inline record annotated with `@UK`. When the compound key columns overlap with other fields on the entity, combine `@UK` with `@Persist(insertable = false, updatable = false)` to prevent duplicate persistence:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class UserEmailUk(val userId: Int, val email: String)

data class SomeEntity(
    @PK val id: Int = 0,
    @FK val user: User,
    val email: String,
    @UK @Persist(insertable = false, updatable = false) val uniqueKey: UserEmailUk
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record UserEmailUk(int userId, String email) {}

record SomeEntity(@PK Integer id,
                  @FK User user,
                  String email,
                  @UK @Persist(insertable = false, updatable = false) UserEmailUk uniqueKey
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

The metamodel processor generates a `Metamodel.Key` for the compound field, which can be used for lookups and scrolling just like a single-column key.

### Using Keys for Lookups

`Metamodel.Key` enables type-safe single-result lookups through the repository:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val user: User? = userRepository.findBy(User_.email, "alice@example.com")
val user: User = userRepository.getBy(User_.email, "alice@example.com")  // throws if not found
```

</TabItem>
<TabItem value="java" label="Java">

```java
Optional<User> user = userRepository.findBy(User_.email, "alice@example.com");
User user = userRepository.getBy(User_.email, "alice@example.com");  // throws if not found
```

</TabItem>
</Tabs>

### Using Keys for Scrolling

`Metamodel.Key` is also required for scrolling, where the cursor column must be unique:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val window: Window<User> = userRepository.scroll(Scrollable.of(User_.id, 20))
// next() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
val nextWindow: Window<User> = userRepository.scroll(window.next())
```

Compound unique keys work the same way. The inline record is used as the cursor value:

```kotlin
val window: Window<SomeEntity> = repository.scroll(Scrollable.of(SomeEntity_.uniqueKey, 20))
val nextWindow: Window<SomeEntity> = repository.scroll(window.next())
```

</TabItem>
<TabItem value="java" label="Java">

```java
Window<User> window = userRepository.scroll(Scrollable.of(User_.id, 20));
// next() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
Window<User> next = userRepository.scroll(window.next());
```

Compound unique keys work the same way:

```java
Window<SomeEntity> window = repository.scroll(Scrollable.of(SomeEntity_.uniqueKey, 20));
Window<SomeEntity> next = repository.scroll(window.next());
```

</TabItem>
</Tabs>

See [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling) for full details.

### Manual Key Wrapping

For dynamically constructed metamodels or composite keys where the processor does not generate a `Key` instance, use `Metamodel.key()` (or the `.key()` extension in Kotlin) to wrap an existing metamodel:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val key: Metamodel.Key<User, String> = Metamodel.key(Metamodel.of(User::class.java, "email"))
```

</TabItem>
<TabItem value="java" label="Java">

```java
Metamodel.Key<User, String> key = Metamodel.key(Metamodel.of(User.class, "email"));
```

</TabItem>
</Tabs>

This is also useful when a column that is not annotated with `@UK` becomes unique in the context of a query, for example because of a GROUP BY clause. In that case, the column can serve as a scrolling cursor even though the metamodel processor did not generate a `Key` for it:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val ordersByCity = orm.query(Order::class)
    .select(Order_.city, "COUNT(*)")
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Order_.city.key(), 20))
```

</TabItem>
<TabItem value="java" label="Java">

```java
var ordersByCity = orm.query(Order.class)
    .select(Order_.city, "COUNT(*)")
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Metamodel.key(Order_.city), 20));
```

</TabItem>
</Tabs>

Callers are responsible for ensuring that the column contains unique values in the result set.

### Nullable Unique Keys

In standard SQL, `NULL != NULL`. This means a `UNIQUE` constraint typically allows multiple rows with `NULL` in the unique column, because each `NULL` is considered distinct from every other `NULL`. While this behavior is well-defined in the SQL standard, it has practical implications for two Storm features: single-result lookups and scrolling.

**Single-result lookups (`findBy`, `getBy`) are safe.** These methods throw if the query returns more than one row. Even if multiple `NULL` rows exist, the lookup either finds zero or one match (when searching for a non-null value) or throws an exception (when multiple rows match). There is no risk of silently returning the wrong result.

**Scrolling is not safe with nullable keys.** Scrolling works by adding a `WHERE key > cursor` (or `WHERE key < cursor`) condition. In SQL, any comparison with `NULL` evaluates to `UNKNOWN`, which means rows with `NULL` in the key column are silently excluded from the result set. This can cause missing data without any error or indication that rows were skipped.

Because of this, Storm validates nullable unique keys at two levels:

1. **Compile-time warning.** The metamodel processor emits a warning when a `@UK` field is nullable (a nullable type in Kotlin, a `@Nullable` reference type in Java, or any reference type in a `@NullUnmarked` scope) and the default `nullsDistinct = true` applies.
2. **Runtime check.** The `scroll` method throws a `PersistenceException` if the key's metamodel indicates that nulls are distinct for a nullable field, preventing silent data loss.

Database behavior varies. Some databases offer stricter NULL handling for unique constraints:

- **PostgreSQL 15+** supports `NULLS NOT DISTINCT` on unique indexes, which rejects duplicate `NULL` values.
- **SQL Server** allows only one `NULL` by default in a unique index (unless a filtered index is used).
- **Most other databases** (MySQL, MariaDB, Oracle, H2) follow the SQL standard and allow multiple `NULL` values.

The `@UK` annotation provides a `nullsDistinct` attribute to control this behavior:

| Field | `nullsDistinct` | Effect |
|-------|-----------------|--------|
| `@UK String email` | (irrelevant) | Safe. Non-null by default. No warning, no runtime check. |
| `@UK int count` | (irrelevant) | Safe. Primitive is never null. |
| `@UK @Nullable String email` | `true` (default) | Compile-time warning. `scroll` throws `PersistenceException`. |
| `@UK(nullsDistinct = false) @Nullable String email` | `false` | No warning. `scroll` works (user asserts DB prevents duplicate NULLs). |

When `nullsDistinct` is set to `false`, you are telling Storm that your database constraint prevents duplicate `NULL` values in the column. Storm trusts this assertion and skips both the compile-time warning and the runtime check. Use this only when your database actually enforces this guarantee (for example, with a `NULLS NOT DISTINCT` unique index in PostgreSQL 15+, or on SQL Server where unique indexes allow at most one `NULL` by default).

The following examples show how to define unique keys that are safe for scrolling.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Safe (non-nullable)
data class User(
    @PK val id: Int = 0,
    @UK val email: String,     // Non-nullable, safe for scrolling
    val name: String
) : Entity<Int>

// Opt-in for nullable keys
data class User(
    @PK val id: Int = 0,
    @UK(nullsDistinct = false) val email: String?,  // DB prevents duplicate NULLs
    val name: String
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Safe (non-nullable)
record User(@PK Integer id,
            @UK String email,  // Non-null by default, safe for scrolling
            String name
) implements Entity<Integer> {}

// Opt-in for nullable keys
record User(@PK Integer id,
            @UK(nullsDistinct = false) @Nullable String email,  // DB prevents duplicate NULLs
            String name
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

In most cases, the simplest approach is to ensure your unique key fields are non-nullable. If nullability is required, verify that your database constraint actually prevents duplicate `NULL` values before setting `nullsDistinct = false`.

## Working with Metamodel Programmatically

Beyond compile-time query construction, the `Metamodel` interface provides several runtime methods for working with entity metadata and values programmatically.

### Extracting Field Values

`Metamodel.getValue(record)` extracts the value of the field represented by a metamodel from a given record instance. This works for any metamodel, including nested paths. If any intermediate record in the path is `null`, the method returns `null`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val user = User(id = 1, email = "alice@example.com", name = "Alice", city = someCity)

// Extract the email value from the user record
val email = User_.email.getValue(user)  // "alice@example.com"

// Extract a nested value through the entity graph
val countryName = User_.city.country.name.getValue(user)  // "United States"
```

</TabItem>
<TabItem value="java" label="Java">

```java
var user = new User(1, "alice@example.com", "Alice", someCity);

// Extract the email value from the user record
Object email = User_.email.getValue(user);  // "alice@example.com"

// Extract a nested value through the entity graph
Object countryName = User_.city.country.name.getValue(user);  // "United States"
```

</TabItem>
</Tabs>

### Flattening Inline Records

`Metamodel.flatten()` expands an inline record (embedded component) into its individual leaf column metamodels. Any other metamodel returns a singleton list containing itself: it names its own column(s), and a foreign key names the foreign key column(s) on the referencing table. ORDER BY and GROUP BY resolve a path to the same columns a predicate would use, expanding multi-column paths during column resolution.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// If Address is an inline record with (street, cityId) fields:
val leafColumns = Owner_.address.flatten()
// Returns: [Owner_.address.street, Owner_.address.city]
```

</TabItem>
<TabItem value="java" label="Java">

```java
// If Address is an inline record with (street, cityId) fields:
List<Metamodel<Owner, ?>> leafColumns = Owner_.address.flatten();
// Returns: [Owner_.address.street, Owner_.address.city]
```

</TabItem>
</Tabs>

### Canonical Form for Equality Checks

`Metamodel.canonical()` returns a path-independent form of a metamodel that captures only the table type and field name. Two metamodels that refer to the same underlying field (but are reached through different paths in the entity graph) will have equal canonical forms. This is useful for programmatic comparison of metamodels.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// These two metamodels reach the same Country.name field through different paths
val path1 = User_.city.country.name
val path2 = Order_.shippingAddress.country.name

// Their canonical forms are equal
path1.canonical() == path2.canonical()  // true
```

</TabItem>
<TabItem value="java" label="Java">

```java
// These two metamodels reach the same Country.name field through different paths
var path1 = User_.city.country.name;
var path2 = Order_.shippingAddress.country.name;

// Their canonical forms are equal
path1.canonical().equals(path2.canonical());  // true
```

</TabItem>
</Tabs>

### Wrapping as a Key

`Metamodel.key(metamodel)` wraps any metamodel as a `Metamodel.Key`, indicating that the column can serve as a unique cursor for scrolling. If the metamodel already implements `Key`, it is returned as-is. See [Manual Key Wrapping](#manual-key-wrapping) for usage examples.

## `@GenerateMetamodel` Annotation

The metamodel processor generates metamodel classes for every record that implements `Data`, which includes all entities and projections, and for every record such a type references, so the entity graph is covered automatically. A record that stays outside the entity graph, such as a plain result row for a custom query, is skipped. Annotate it with `@GenerateMetamodel` to process it anyway: its metamodel class is generated next to it, and it receives an instantiator so the runtime constructs instances without reflection. On GraalVM native images this keeps custom query rows working without reflection configuration.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@GenerateMetamodel
data class CityStats(
    val name: String,
    val inhabitants: Int
)
```

</TabItem>
<TabItem value="java" label="Java">

```java
@GenerateMetamodel
record CityStats(String name, int inhabitants) {}
```

</TabItem>
</Tabs>

The root metamodel interface (`CityStats_` in this example) is only generated for `Data` types: every metamodel is rooted in a `Data` type, so a plain record participates in queries through the entity graph that references it rather than as a root of its own.

The `@GenerateMetamodel` annotation is located in the `st.orm` package alongside Storm's other annotations.

## Benefits

1. **Compile-time safety.** Typos caught at compile time, not runtime.
2. **IDE support.** Auto-completion for field names.
3. **Refactoring.** Rename fields safely; the compiler catches all usages.
4. **Type checking.** Can't compare a String field to an Integer.

## Without the Metamodel

The metamodel is not required. You can use Storm with SQL Templates (Java) or raw query methods and string-based field references. This approach works well for prototyping, small projects, or queries that are too dynamic to express through the DSL. The trade-off is that field references become strings, which the compiler cannot verify. Typos and type mismatches will surface as runtime exceptions rather than compile errors.

## Tips

1. **Rebuild after changes.** Run `./gradlew build` or `mvn compile` after adding or modifying entity fields.
2. **Check your IDE setup.** Ensure KSP (Kotlin) or annotation processing (Java) is enabled in your IDE settings.
3. **Use for all queries.** Consistent use of metamodel prevents runtime errors.
