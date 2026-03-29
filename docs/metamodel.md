# Static Metamodel

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

The static metamodel is a code generation feature that creates companion classes for your entities at compile time. These generated classes provide type-safe references to entity fields, enabling the compiler to catch errors that would otherwise surface only at runtime.

Using the metamodel is optional. Storm works without it using SQL Templates or string-based field references. However, for projects that want to leverage Storm's full capabilities, the metamodel provides significant benefits in terms of type safety, IDE support, and maintainability.

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

### Gradle (Kotlin with KSP)

```kotlin
plugins {
    id("com.google.devtools.ksp") version "2.0.21-1.0.28"
}

dependencies {
    ksp("st.orm:storm-metamodel-processor:@@STORM_VERSION@@")
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
val users = orm.entity(User::class)
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

Storm supports two forms of metamodel references: **nested paths** and **short form**. Understanding when to use each is important for writing correct queries.

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

Short form works **only when the table appears exactly once** in the entity graph. If `Country` is referenced in multiple places, Storm cannot determine which one you mean.

**Example where short form works:**

```kotlin
data class User(
    @PK val id: Int = 0,
    val name: String,
    @FK val city: City       // City → Country (only path to Country)
) : Entity<Int>

// Short form works - Country appears only once in User's entity graph
val users = orm.entity(User::class)
    .select()
    .whereAny(Country_.name eq "United States")  // Resolves to User → City → Country
    .resultList
```

The short form `Country_.name` works here because Storm first establishes `User` as the root entity, then looks up `Country` in User's entity graph. Since there's only one path to `Country` (via `city.country`), it's unambiguous.

Note the use of `whereAny` instead of `where`. The `where` method requires predicates typed to the root entity (`User`), while `whereAny` accepts predicates for any table in the entity graph. Since `Country_.name` produces a `Country`-typed predicate, `whereAny` is required.

**Type safety considerations:**

- **`where`** is fully type-safe. The predicate must be rooted at the query's entity type, so column lookup is guaranteed to succeed at runtime.
- **`whereAny`** is type-safe for the values you pass (e.g., comparing a `String` field to a `String` value), but the column lookup may fail at runtime if the referenced table doesn't exist in the entity graph or appears multiple times (ambiguity). Use nested paths or ensure uniqueness to avoid runtime exceptions.

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

// ERROR: Multiple paths to Country in User's entity graph
val users = orm.entity(User::class)
    .select()
    .whereAny(Country_.name eq "United States")
    .resultList

// OK: Nested paths are unambiguous (and can use where since they're rooted at User_)
val users = orm.entity(User::class)
    .select()
    .where(User_.city.country.name eq "United States")
    .resultList

val users = orm.entity(User::class)
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
val users = orm.entity(User::class)
    .select()
    .innerJoin(Order::class).on(User::class)  // Custom join
    .whereAny(Order_.total greater BigDecimal(100))  // Short form required, use whereAny
    .resultList
```

Custom joins are not part of the entity graph traversal, so nested paths cannot reach them. The short form works here because Storm registers the custom join's alias. Use `whereAny` since the predicate references `Order`, not the root entity `User`.

**Uniqueness still applies:** If you join the same table multiple times, you must use the `join` method with explicit aliases to disambiguate:

```kotlin
val users = orm.entity(User::class)
    .select()
    .join(JoinType.inner(), Order::class, "recent").on(User::class)
    .join(JoinType.inner(), Order::class, "first").on(User::class)
    .where(/* use SQL template with explicit aliases */)
    .resultList
```

### Resolution Order

When resolving a metamodel reference, Storm follows this order:

1. **Nested path.** If a path is specified (e.g., `User_.city.country`), use the alias for that specific traversal.
2. **Unique table lookup.** If short form (e.g., `Country_`), check if the table appears exactly once in the entity graph or registered joins.
3. **Error.** If multiple paths exist, throw an exception indicating the ambiguity.

### Best Practices

1. **Prefer nested paths** for clarity and to avoid ambiguity issues
2. **Use short form** for custom joins (required) or when you're certain the table is unique
3. **Check error messages.** Storm tells you which paths are available when ambiguity is detected.

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
data class UserEmailUK(val userId: Int, val email: String)

data class SomeEntity(
    @PK val id: Int = 0,
    @FK val user: User,
    val email: String,
    @UK @Persist(insertable = false, updatable = false) val uniqueKey: UserEmailUK
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record UserEmailUK(int userId, String email) {}

record SomeEntity(@PK Integer id,
                  @Nonnull @FK User user,
                  @Nonnull String email,
                  @UK @Persist(insertable = false, updatable = false) UserEmailUK uniqueKey
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
// nextScrollable() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
val nextWindow: Window<User> = userRepository.scroll(window.nextScrollable())
```

Compound unique keys work the same way. The inline record is used as the cursor value:

```kotlin
val window: Window<SomeEntity> = repository.scroll(Scrollable.of(SomeEntity_.uniqueKey, 20))
val nextWindow: Window<SomeEntity> = repository.scroll(window.nextScrollable())
```

</TabItem>
<TabItem value="java" label="Java">

```java
Window<User> window = userRepository.scroll(Scrollable.of(User_.id, 20));
// nextScrollable() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
Window<User> next = userRepository.scroll(window.nextScrollable());
```

Compound unique keys work the same way:

```java
Window<SomeEntity> window = repository.scroll(Scrollable.of(SomeEntity_.uniqueKey, 20));
Window<SomeEntity> next = repository.scroll(window.nextScrollable());
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

1. **Compile-time warning.** The metamodel processor emits a warning when a `@UK` field is nullable (a nullable type in Kotlin, or a reference type without `@Nonnull` in Java) and the default `nullsDistinct = true` applies.
2. **Runtime check.** The `scroll` method throws a `PersistenceException` if the key's metamodel indicates that nulls are distinct for a nullable field, preventing silent data loss.

Database behavior varies. Some databases offer stricter NULL handling for unique constraints:

- **PostgreSQL 15+** supports `NULLS NOT DISTINCT` on unique indexes, which rejects duplicate `NULL` values.
- **SQL Server** allows only one `NULL` by default in a unique index (unless a filtered index is used).
- **Most other databases** (MySQL, MariaDB, Oracle, H2) follow the SQL standard and allow multiple `NULL` values.

The `@UK` annotation provides a `nullsDistinct` attribute to control this behavior:

| Field | `nullsDistinct` | Effect |
|-------|-----------------|--------|
| `@UK @Nonnull String email` | (irrelevant) | Safe. No warning, no runtime check. |
| `@UK int count` | (irrelevant) | Safe. Primitive is never null. |
| `@UK String email` | `true` (default) | Compile-time warning. `scroll` throws `PersistenceException`. |
| `@UK(nullsDistinct = false) String email` | `false` | No warning. `scroll` works (user asserts DB prevents duplicate NULLs). |

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
            @UK @Nonnull String email,  // Non-nullable, safe for scrolling
            String name
) implements Entity<Integer> {}

// Opt-in for nullable keys
record User(@PK Integer id,
            @UK(nullsDistinct = false) String email,  // DB prevents duplicate NULLs
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

`Metamodel.flatten()` expands an inline record (embedded component) into its individual leaf column metamodels. If the metamodel already represents a leaf column, it returns a singleton list containing itself. This is the same expansion Storm performs internally for ORDER BY and GROUP BY clauses.

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

By default, the metamodel processor generates metamodel classes for all records that implement `Entity` or `Projection`. If you have a plain record (or data class) that does not implement either interface but you still want a metamodel generated for it, annotate it with `@GenerateMetamodel`.

This is useful for:
- Inline records (embedded components) that you want to reference in queries via the metamodel
- `Data` implementations used in custom SQL templates
- Any non-entity record where you want compile-time type-safe field references

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@GenerateMetamodel
data class Address(
    val street: String,
    @FK val city: City
)

// Now Address_ is available for type-safe references
val addresses = orm.query { """
    SELECT ${Address::class}
    FROM ${Address::class}
    WHERE ${Address_.street} LIKE ${"%Main%"}
""" }
```

</TabItem>
<TabItem value="java" label="Java">

```java
@GenerateMetamodel
record Address(String street,
               @FK City city) {}

// Now Address_ is available for type-safe references
var addresses = orm.query(RAW."""
        SELECT \{Address.class}
        FROM \{Address.class}
        WHERE \{Address_.street} LIKE \{"%Main%"}""");
```

</TabItem>
</Tabs>

The `@GenerateMetamodel` annotation is located in `st.orm.core.template` and requires the `storm-core` dependency at compile time (provided scope is sufficient).

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
