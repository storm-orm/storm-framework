# SQL Templates

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

SQL templates are the foundation of Storm. The `EntityRepository` and `ProjectionRepository` APIs are built entirely on top of SQL templates. Everything those repositories do, such as generating SELECT columns, deriving joins from `@FK` relationships, and resolving table aliases, uses the same template engine available to you directly.

Most users will interact with Storm through repositories and only use templates when they need custom queries. This page covers the template features you're most likely to use: referencing tables and columns with automatic alias resolution, and understanding how joins are derived.

For details on how query results are mapped to records, see [Hydration](hydration.md).

---

## Template Syntax

Storm uses string interpolation to inject template elements into SQL. Rather than concatenating strings or using positional placeholders, you embed type references, metamodel fields, and parameter values directly in the SQL text. Storm resolves these at compilation time into proper column lists, table aliases, and parameterized placeholders.

The syntax differs between Kotlin and Java due to language-level string interpolation support.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin uses `${}` interpolation inside a lambda. With the [Storm compiler plugin](string-templates.md), interpolated expressions are automatically wrapped in `t()` calls at compile time, so you can write natural Kotlin string interpolation:

```kotlin
orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    WHERE ${User_.email} = $email
""" }
```

The compiler plugin wraps each interpolated expression in `t()`, which is the single entry point for all template elements: types expand to column lists, metamodel references resolve to column names, and values become parameterized placeholders. Without the plugin, you can wrap expressions in `t()` manually. See [String Templates](string-templates.md) for setup instructions.

</TabItem>
<TabItem value="java" label="Java">

Java uses string templates with `\{}` syntax:

```java
orm.query(RAW."""
    SELECT \{User.class}
    FROM \{User.class}
    WHERE \{User_.email} = \{email}""")
```

> **Note:** Java string templates are a preview feature. Storm for Java requires Java 21+ with preview mode enabled (`--enable-preview`). Storm will adapt to the final string template specification once it's released.

</TabItem>
</Tabs>

---

## Data Interface

The `Data` interface marks a record or data class as eligible for Storm's SQL generation. Without this marker, Storm treats the type as a plain container and expects you to write all SQL manually. With it, template expressions like `${MyType::class}` in a SELECT clause expand into the full column list, and the same expression in a FROM clause generates the table name with appropriate joins for `@FK` fields.

Use `Data` for query-specific result types that do not need full repository support (insert, update, remove). If you need CRUD operations, use `Entity` or `Projection` instead, which extend `Data`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class PetWithOwner(
    val name: String,
    val birthDate: LocalDate?,
    @FK val owner: Owner
) : Data

// SQL template generates SELECT columns and joins
val pets = orm.query { """
    SELECT ${PetWithOwner::class}
    FROM ${PetWithOwner::class}
    WHERE ${Owner_.city} = $city
""" }.getResultList(PetWithOwner::class)
```

</TabItem>
<TabItem value="java" label="Java">

```java
record PetWithOwner(
    @Nonnull String name,
    @Nullable LocalDate birthDate,
    @FK Owner owner
) implements Data {}

// SQL template generates SELECT columns and joins
List<PetWithOwner> pets = orm.query(RAW."""
        SELECT \{PetWithOwner.class}
        FROM \{PetWithOwner.class}
        WHERE \{Owner_.city} = \{city}""")
    .getResultList(PetWithOwner.class);
```

</TabItem>
</Tabs>

**When to use:** Single-use queries where you want Storm's SQL generation, automatic joins via `@FK`, and type-safe column references.

---

## Entity and Projection

For reusable types with repository support (`findById`, `insert`, `update`, etc.), use `Entity` or `Projection`. These extend `Data` and provide full repository operations.

See [Entities](entities.md) and [Projections](projections.md) for details.

| Type | Template Support | Repository Support |
|------|------------------|-------------------|
| Plain record | No | No |
| `Data` | Yes | No |
| `Entity`/`Projection` | Yes | Yes |

For plain records with manual SQL, see [Hydration](hydration.md).

---

## Auto-Join Generation

When you use a type in both SELECT and FROM expressions, Storm automatically generates joins for `@FK` relationships. This eliminates the need to write join clauses manually.

### How Auto-Joins Work

Given these entities:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Country(
    @PK val id: Int,
    val name: String,
    val code: String
) : Entity<Int>

data class City(
    @PK val id: Int,
    val name: String,
    @FK val country: Country
) : Entity<Int>

data class User(
    @PK val id: Int,
    val email: String,
    @FK val city: City
) : Entity<Int>
```

This query:

```kotlin
orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
""" }
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Country(@PK Integer id,
               @Nonnull String name,
               @Nonnull String code
) implements Entity<Integer> {}

record City(@PK Integer id,
            @Nonnull String name,
            @FK Country country
) implements Entity<Integer> {}

record User(@PK Integer id,
            @Nonnull String email,
            @FK City city
) implements Entity<Integer> {}
```

This query:

```java
orm.query(RAW."""
    SELECT \{User.class}
    FROM \{User.class}""")
```

</TabItem>
</Tabs>

Generates:

```sql
SELECT u.id, u.email, c.id, c.name, co.id, co.name, co.code
FROM user u
INNER JOIN city c ON u.city_id = c.id
INNER JOIN country co ON c.country_id = co.id
```

Storm traverses the record type graph, following `@FK` annotations to generate the necessary joins. The ON clauses are derived automatically from the foreign key relationships.

### Nullable FKs Become LEFT JOINs

When an `@FK` field is nullable, Storm generates a LEFT JOIN instead of an INNER JOIN:

```kotlin
data class User(
    @PK val id: Int,
    val email: String,
    @FK val city: City?  // Nullable FK
) : Entity<Int>
```

Generates:

```sql
SELECT u.id, u.email, c.id, c.name, co.id, co.name, co.code
FROM user u
LEFT JOIN city c ON u.city_id = c.id
LEFT JOIN country co ON c.country_id = co.id
```

Nullability propagates through the relationship chain. If `city` is nullable, all joins that depend on it (like `country` through `city`) also become LEFT JOINs.

### Join Ordering

Storm automatically orders joins so that LEFT JOINs appear after INNER JOINs. This prevents unintended filtering effects that can occur when outer joins precede inner joins.

```
FROM user u
INNER JOIN department d ON u.department_id = d.id    -- INNER joins first
INNER JOIN company co ON d.company_id = co.id
LEFT JOIN city c ON u.city_id = c.id                 -- LEFT joins last
LEFT JOIN country cn ON c.country_id = cn.id
```

### Disabling Auto-Joins

Use `from(Class, autoJoin = false)` to disable automatic join generation:

```kotlin
orm.query { """
    SELECT ${User::class}
    FROM ${from(User::class, autoJoin = false)}
    JOIN ${table(City::class)} ON ${User_.city} = ${City_.id}
""" }
```

---

## Column References with Metamodel

Hardcoding column names as strings in SQL is error-prone: a renamed field silently breaks at runtime. Storm's compile-time metamodel eliminates this risk. For each entity or data class, the code generator (KSP for Kotlin, annotation processor for Java) generates a companion class (e.g., `User_`) with a static field for every column. These fields resolve to the correct column name and table alias at template compilation time, so a renamed field causes a compile error instead of a runtime failure.

### Basic Column Reference

For an entity `User`, Storm generates `User_` with fields for each column. Use these fields anywhere you would write a column name in SQL.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Reference a column in WHERE clause
orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    WHERE ${User_.email} = $email
""" }
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.query(RAW."""
    SELECT \{User.class}
    FROM \{User.class}
    WHERE \{User_.email} = \{email}""")
```

</TabItem>
</Tabs>

### Nested Column References

Metamodel fields support path navigation for `@FK` relationships. This lets you reference columns on joined tables without writing the join alias yourself. Storm resolves the path to the correct alias based on the auto-generated joins.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Reference a column through a relationship
orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    WHERE ${User_.city.country.code} = ${"US"}
""" }
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.query(RAW."""
    SELECT \{User.class}
    FROM \{User.class}
    WHERE \{User_.city.country.code} = \{"US"}""")
```

</TabItem>
</Tabs>

This generates:

```sql
WHERE co.code = ?
```

The alias (`co`) is resolved from the auto-generated joins.

### Column in Different Contexts

Use `column()` to explicitly reference a column with alias resolution:

```kotlin
orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    ORDER BY ${column(User_.email)}
""" }
```

---

## ResolveScope

When working with subqueries or nested template expressions, you may need to control how Storm resolves table aliases. The `ResolveScope` enum determines where Storm looks for aliases when resolving a column or table reference.

| Scope | Behavior |
|-------|----------|
| `CASCADE` | Enforce unambiguity by requiring the alias to be resolved uniquely. This is the default. |
| `INNER` | Resolve only within the current (innermost) scope. Fails if the alias is not defined locally. |
| `OUTER` | Resolve only from outer scope(s), ignoring locally defined aliases. |

The `alias()` and `column()` template functions accept an optional `ResolveScope` parameter. This is most useful in correlated subqueries where the same entity appears in both the outer and inner query. For example, selecting all pets that have at least one visit:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pets = orm.entity(Pet::class)
    .select()
    .whereExists { subquery(Visit::class)
        .where { "${column(Visit_.pet, INNER)} = ${column(Pet_.id, OUTER)}" }
    }
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
var pets = orm.entity(Pet.class).select()
        .where(wb -> wb.exists(
                wb.subquery(Visit.class)
                        .where(RAW."\{column(Visit_.pet, INNER)} = \{column(Pet_.id, OUTER)}")))
        .getResultList();
```

</TabItem>
</Tabs>

The `column()` function with a metamodel reference resolves to the fully qualified column name (e.g., `v.pet_id` and `p.id`). `INNER` tells Storm to resolve `Visit_.pet` from the subquery, while `OUTER` resolves `Pet_.id` from the main query.

In most cases the default `CASCADE` scope is correct, because it ensures that each alias resolves to exactly one table. Use `INNER` or `OUTER` when writing correlated subqueries where you need to control whether a reference resolves to the inner query's tables or the outer query's tables.

---

## Common Template Elements

Most queries only need a few template elements. Here are the ones you'll use most often:

| Element | Description |
|---------|-------------|
| `${Class}` | Type reference for SELECT columns or FROM clause |
| `${Metamodel_}` (e.g., `${User_.email}`) | Column reference with automatic alias resolution |
| `${column(Metamodel)}` | Explicit column reference |
| `${table(Class)}` | Table reference without auto-join |
| `${from(Class, autoJoin)}` | FROM clause with auto-join control |
| `${unsafe(String)}` | Raw SQL (use with caution) |

For advanced use cases like batch operations, subqueries, or custom insert/update statements, Storm provides additional elements. See the `Templates` class for the full API.

---

## Examples

The following examples demonstrate common query patterns using SQL templates. Each combines multiple template features (type references, metamodel columns, parameter binding) into a complete query.

### Filtering with Metamodel

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    WHERE ${User_.city.country.code} = ${"US"}
      AND ${User_.email} LIKE ${"%@example.com"}
""" }.getResultList(User::class)
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> users = orm.query(RAW."""
        SELECT \{User.class}
        FROM \{User.class}
        WHERE \{User_.city.country.code} = \{"US"}
          AND \{User_.email} LIKE \{"%@example.com"}""")
    .getResultList(User.class);
```

</TabItem>
</Tabs>

### Custom Joins

When auto-join does not produce the join type or condition you need, disable it with `from(Class, autoJoin = false)` and write explicit join clauses. This is common for LEFT JOINs with aggregation or joins on non-FK conditions.

```kotlin
orm.query { """
    SELECT ${User::class}, COUNT(${Order_.id})
    FROM ${from(User::class, autoJoin = false)}
    LEFT JOIN ${table(Order::class)} ON ${Order_.userId} = ${User_.id}
    GROUP BY ${User_.id}
""" }
```

### Subquery

Subqueries use `column()` and `table()` to reference columns and tables without triggering auto-join generation. This keeps the subquery self-contained, with its own FROM clause and alias scope.

```kotlin
orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    WHERE ${User_.id} IN (
        SELECT ${column(Order_.userId)}
        FROM ${table(Order::class)}
        WHERE ${Order_.total} > ${1000}
    )
""" }
```

---

## Template Processing

Since all Storm operations are built on the SQL template engine, understanding how templates are processed helps explain Storm's performance characteristics. Whether you use repository methods like `findById()` or write custom queries, the same template engine powers every database interaction.

Storm processes templates in two distinct steps:

1. **Compilation.** The template is parsed and analyzed. Storm resolves table aliases, traverses record type graphs to determine `@FK` relationships, generates the appropriate joins, and produces a reusable SQL shape with parameter placeholders. This step involves type introspection, alias management, and SQL construction.

2. **Binding.** Parameter values are substituted into the compiled template. This step is lightweight: it simply fills in the placeholders with actual values and prepares the statement for execution.

The compilation step does the heavy lifting. It analyzes your record types, walks through nested relationships, determines which joins are needed and in what order, and assembles the final SQL structure. The binding step, by contrast, is a straightforward value substitution.

Because the template model closely mirrors SQL structure, compilation is already fast. Storm doesn't need to translate between paradigms or build complex query plans. The template essentially describes the SQL you want, and Storm fills in the details like column lists, aliases, and join conditions. This direct mapping keeps compilation overhead low even without caching.

### Compilation Caching

Storm caches compiled templates to eliminate even this small overhead on repeated queries. The cache key is based on the template structure, not the parameter values. When you execute the same query pattern with different parameter values, Storm retrieves the compiled template from the cache and only performs the binding step.

```kotlin
// First execution: full compilation + binding
userRepository.find(User_.email eq "alice@example.com")

// Subsequent executions: cache hit, binding only
userRepository.find(User_.email eq "bob@example.com")
userRepository.find(User_.email eq "charlie@example.com")
```

This applies to all Storm operations. Repository methods like `findAll()`, `insert()`, and `update()` benefit from the same caching mechanism. Once a query pattern has been compiled, repeated use across your application reuses the cached compilation.

The performance improvement from caching is significant, typically 10-20x faster for cached queries compared to full compilation. For most applications, templates are compiled once during the initial requests and then served from cache for the lifetime of the application.

### Why This Matters

Traditional database latency from network round-trips and query execution is handled efficiently by modern runtimes through non-blocking IO and asynchronous operations. This means IO-bound work scales well without consuming threads or CPU cycles while waiting.

At high scale, CPU time becomes the limiting factor. A server handling thousands of requests per second needs to minimize per-request overhead. Compilation caching ensures that Storm contributes minimal CPU overhead after the initial warmup period, leaving cycles available for your application logic and allowing better utilization of your hardware.
