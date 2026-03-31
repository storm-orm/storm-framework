# Queries

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Storm provides a powerful and flexible query API. All queries are type-safe; the generated metamodel (`User_`, `City_`, etc.) catches errors at compile time rather than at runtime.

Key features:
- **Compile-time checked** -- field references are validated by the metamodel
- **No string-based queries** -- no risk of typos in column names
- **Single-query loading** -- related entities load in JOINs, not N+1 queries
- **Two styles** -- quick methods for simple cases, fluent builder for complex queries

---

## Choosing a Query Approach

Storm offers three ways to query data, each suited to different complexity levels:

| Approach | Best for | Type safety | Flexibility |
|----------|----------|-------------|-------------|
| **Repository `findBy`** | Simple key lookups by primary key or unique key | Full compile-time | Low (single-field equality only) |
| **Query DSL** | Filtering, ordering, pagination with type-safe conditions | Full compile-time | Medium (AND/OR predicates, joins, ordering) |
| **SQL Templates** | Complex joins, subqueries, CTEs, window functions, database-specific SQL | Column references checked at compile time, SQL structure at runtime | High (full SQL control) |

Start with the simplest approach that meets your needs. Use `findBy` or `findAll` for straightforward lookups. Move to the query builder when you need compound filters or pagination. Use SQL templates when you need SQL features the DSL does not cover.

---

## Quick Queries

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Storm for Kotlin offers two complementary query styles; use whichever fits best.

For simple queries, use methods directly on the ORM template:

```kotlin
// Find single entity with predicate
val user: User? = orm.find(User_.email eq email)

// Find all matching
val users: List<User> = orm.findAll(User_.city eq city)

// Find by field value
val user: User? = orm.findBy(User_.email, email)

// Check existence
val exists: Boolean = orm.existsBy(User_.email, email)
```

</TabItem>
<TabItem value="java" label="Java">

The Java DSL uses the same `EntityRepository` interface as Kotlin. Obtain a repository with `orm.entity(Class)` and use its fluent query builder. Return types use `Optional` for single results and `List` for collections.

```java
var users = orm.entity(User.class);

// Find by ID
Optional<User> user = users.findById(userId);

// Find all matching
List<User> usersInCity = users.select()
    .where(User_.city, EQUALS, city)
    .getResultList();

// Find first matching
Optional<User> user = users.select()
    .where(User_.email, EQUALS, email)
    .getOptionalResult();

// Count
long count = users.count();
```

</TabItem>
</Tabs>

---

## Repository Queries

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

For more complex operations, use the repository:

```kotlin
val users = orm.entity(User::class)

// Find by ID
val user: User? = users.findById(userId)

// Find with predicate
val user: User? = users.find(User_.email eq email)

// Find all matching
val usersInCity: List<User> = users.findAll(User_.city eq city)

// Count
val count: Long = users.count()

// Exists
val exists: Boolean = users.existsById(userId)
```

</TabItem>
<TabItem value="java" label="Java">

For more complex operations, use the repository:

```java
var users = orm.entity(User.class);

// Find by ID
Optional<User> user = users.findById(userId);

// Find with predicate
Optional<User> user = users.select()
    .where(User_.email, EQUALS, email)
    .getOptionalResult();

// Find all matching
List<User> usersInCity = users.select()
    .where(User_.city, EQUALS, city)
    .getResultList();

// Count
long count = users.count();

// Exists
boolean exists = users.existsById(userId);
```

</TabItem>
</Tabs>

---

## Filtering with Predicates

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Combine conditions with `and` and `or`:

```kotlin
// AND condition
val users = orm.findAll(
    (User_.city eq city) and (User_.birthDate less LocalDate.of(2000, 1, 1))
)

// OR condition
val users = orm.findAll(
    (User_.role eq adminRole) or (User_.role eq superUserRole)
)

// Complex conditions
val users = orm.entity(User::class)
    .select()
    .where(
        (User_.city eq city) and (
            (User_.role eq adminRole) or (User_.birthDate greaterEq LocalDate.of(1990, 1, 1))
        )
    )
    .resultList
```

### Operators

| Operator | Description |
|----------|-------------|
| `eq` | Equals |
| `neq` | Not equals |
| `less` | Less than |
| `lessEq` | Less than or equals |
| `greater` | Greater than |
| `greaterEq` | Greater than or equals |
| `like` | LIKE pattern match |
| `notLike` | NOT LIKE |
| `isNull` | IS NULL |
| `isNotNull` | IS NOT NULL |
| `inList` | IN (list) |
| `notInList` | NOT IN (list) |

```kotlin
val users = orm.findAll(User_.email like "%@example.com")
val users = orm.findAll(User_.deletedAt.isNull())
val users = orm.findAll(User_.role inList listOf(adminRole, userRole))
```

</TabItem>
<TabItem value="java" label="Java">

Combine conditions using the lambda-based `where` builder. The `it` parameter provides access to the condition factory, which you chain with `.and()` or `.or()` calls to compose compound predicates.

```java
// AND condition
List<User> users = orm.entity(User.class)
    .select()
    .where(it -> it.where(User_.city, EQUALS, city)
            .and(it.where(User_.birthDate, LESS_THAN, LocalDate.of(2000, 1, 1))))
    .getResultList();

// OR condition
List<User> users = orm.entity(User.class)
    .select()
    .where(it -> it.where(User_.role, EQUALS, adminRole)
            .or(it.where(User_.role, EQUALS, superUserRole)))
    .getResultList();
```

### Filtering (SQL Templates)

SQL Templates let you write SQL directly while retaining type safety. Entity references and metamodel fields are interpolated into the template, and parameter values are bound safely. This approach is well suited for queries that use database-specific syntax, CTEs, or window functions that the DSL does not cover.

```java
List<User> users = orm.query(RAW."""
        SELECT \{User.class}
        FROM \{User.class}
        WHERE \{city}
          AND \{User_.birthDate} < \{LocalDate.of(2000, 1, 1)}""")
    .getResultList(User.class);
```

### Operators

| Operator | Description |
|----------|-------------|
| `EQUALS` | Equals |
| `NOT_EQUALS` | Not equals |
| `LESS_THAN` | Less than |
| `LESS_THAN_OR_EQUAL` | Less than or equals |
| `GREATER_THAN` | Greater than |
| `GREATER_THAN_OR_EQUAL` | Greater than or equals |
| `LIKE` | LIKE pattern match |
| `NOT_LIKE` | NOT LIKE |
| `IS_NULL` | IS NULL |
| `IS_NOT_NULL` | IS NOT NULL |
| `IN` | IN (list) |
| `NOT_IN` | NOT IN (list) |

```java
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.email, LIKE, "%@example.com")
    .getResultList();
```

</TabItem>
</Tabs>

### Composing Multiple Filters

Multiple `where()` calls on the same query builder are combined with AND. This lets you build up filters incrementally, which is useful when conditions are added conditionally in application code.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val results = orm.entity(User::class)
    .select()
    .where(User_.active, EQUALS, true)
    .where(User_.city eq city)           // AND-combined with previous where
    .resultList
```

Builder-style `where()` calls (with `and`/`or` predicates) compose with other `where()` calls in the same way:

```kotlin
val results = orm.entity(User::class)
    .select()
    .where(User_.active, EQUALS, true)
    .where(                              // AND-combined with the active filter above
        (User_.role eq adminRole) or (User_.role eq superUserRole)
    )
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> results = orm.entity(User.class)
    .select()
    .where(User_.active, EQUALS, true)
    .where(User_.city, EQUALS, city)     // AND-combined with previous where
    .getResultList();
```

Builder-style `where()` calls (with `and`/`or` predicates) compose with other `where()` calls in the same way:

```java
List<User> results = orm.entity(User.class)
    .select()
    .where(User_.active, EQUALS, true)
    .where(it -> it.where(User_.role, EQUALS, adminRole)  // AND-combined with active filter
            .or(it.where(User_.role, EQUALS, superUserRole)))
    .getResultList();
```

</TabItem>
</Tabs>

---

## Ordering

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use `orderBy` to control result ordering. Pass multiple fields as arguments to sort by more than one column. Use `orderByDescending` for descending order on a single field.

```kotlin
val users = orm.entity(User::class)
    .select()
    .orderBy(User_.name)
    .resultList

// Descending
val users = orm.entity(User::class)
    .select()
    .orderByDescending(User_.createdAt)
    .resultList

// Multiple fields (all ascending)
val users = orm.entity(User::class)
    .select()
    .orderBy(User_.lastName, User_.firstName)
    .resultList
```

Multiple `orderBy` and `orderByDescending` calls can be chained to build multi-column sort clauses with mixed directions. Each call appends to the existing ORDER BY clause rather than replacing it, so you can mix ascending and descending columns freely.

```kotlin
// Mixed sort directions: last name ascending, first name descending
val users = orm.entity(User::class)
    .select()
    .orderBy(User_.lastName)
    .orderByDescending(User_.firstName)
    .resultList
```

When an inline record (embedded component) is passed to `orderBy` or `orderByDescending`, Storm automatically expands it into its individual leaf columns using `flatten()`. For example, if `User_.fullName` is an inline record with `lastName` and `firstName` fields, `orderBy(User_.fullName)` produces `ORDER BY last_name, first_name`. The same expansion applies to `groupBy`.

For full control over the ORDER BY clause (for example, to use SQL expressions or database-specific syntax), use the template overload. Metamodel fields are resolved to their column names automatically.

```kotlin
// Mixed sort directions (template)
val users = orm.entity(User::class)
    .select()
    .orderBy { "${User_.lastName}, ${User_.firstName} DESC" }
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

Use `orderBy` to sort results by one or more columns. Pass multiple fields as arguments for multi-column sorting. Use `orderByDescending` for descending order on a single field.

```java
// Ascending (default)
List<User> users = orm.entity(User.class)
    .select()
    .orderBy(User_.name)
    .getResultList();

// Descending
List<User> users = orm.entity(User.class)
    .select()
    .orderByDescending(User_.createdAt)
    .getResultList();

// Multiple fields (all ascending)
List<User> users = orm.entity(User.class)
    .select()
    .orderBy(User_.lastName, User_.firstName)
    .getResultList();
```

Chain `orderBy` and `orderByDescending` calls to mix ascending and descending columns. Each call appends to the ORDER BY clause.

```java
// Mixed sort directions: last name ascending, first name descending
List<User> users = orm.entity(User.class)
    .select()
    .orderBy(User_.lastName)
    .orderByDescending(User_.firstName)
    .getResultList();
```

When an inline record (embedded component) is passed to `orderBy` or `orderByDescending`, Storm automatically expands it into its individual leaf columns using `flatten()`. The same expansion applies to `groupBy`.

For full control over the ORDER BY clause, use the template overload:

```java
// Mixed sort directions (template)
List<User> users = orm.entity(User.class)
    .select()
    .orderBy(RAW."\{User_.lastName}, \{User_.firstName} DESC")
    .getResultList();
```

</TabItem>
</Tabs>

## Aggregation

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

To perform GROUP BY queries with aggregate functions like COUNT, SUM, or AVG, define a result data class with the desired columns and pass a custom SELECT expression. Interpolating an entity or projection type generates the column list automatically, so you do not have to enumerate columns manually.

```kotlin
data class CityCount(val city: City, val count: Long)

val counts: List<CityCount> = orm.entity(User::class)
    .select(CityCount::class) { "${City::class}, COUNT(*)" }
    .groupBy(User_.city)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

Define a result record with the desired columns and pass a custom SELECT expression. The DSL approach uses `select(Class, template)` with `groupBy` to build the query.

```java
record CityCount(City city, long count) {}

List<CityCount> counts = orm.entity(User.class)
    .select(CityCount.class, RAW."\{City.class}, COUNT(*)")
    .groupBy(User_.city)
    .getResultList();
```

### Aggregation (SQL Templates)

For aggregation queries that involve multiple tables, CTEs, or HAVING clauses, SQL Templates give you full control over the query structure while still mapping results to typed records.

```java
List<CityCount> counts = orm.query(RAW."""
        SELECT \{City.class}, COUNT(*)
        FROM \{User.class}
        GROUP BY \{User_.city}""")
    .getResultList(CityCount.class);
```

</TabItem>
</Tabs>

## Data Retrieval Strategies

When working with large result sets, Storm supports three strategies for retrieving subsets: manual offset/limit, offset-based pagination, and cursor-based scrolling.

| Strategy | Navigation | Result type | Typical use |
|----------|------------|-------------|-------------|
| **Offset and Limit** | manual | `List<R>` | simple queries with known bounds |
| **Pagination** | page number | `Page<R>` | UI lists, reports |
| **Scrolling** | sequential cursor | `Window<T>` | infinite scroll, batch processing |

**Pagination** navigates by page number and includes a total count. It uses SQL `OFFSET` under the hood, which degrades on large tables. **Scrolling** uses keyset pagination for constant-time performance regardless of depth, but only supports sequential forward/backward navigation.

For detailed usage, sorting, composite scrolling, `Window` type parameters, GROUP BY with scrolling, and REST cursor support, see [Pagination and Scrolling](pagination-and-scrolling.md).

### Quick examples


<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Offset and limit
val results = orm.entity(User::class).select()
    .orderBy(User_.createdAt)
    .offset(20).limit(10)
    .resultList

// Pagination
val page: Page<User> = orm.entity(User::class).select()
    .where(User_.active, EQUALS, true)
    .page(Pageable.ofSize(10))

// Scrolling
val window: Window<User> = userRepository.scroll(Scrollable.of(User_.id, 20))
// nextScrollable() is non-null when the window has content.
// hasNext is informational; the developer decides whether to follow the cursor.
val next = userRepository.scroll(window.nextScrollable())
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Offset and limit
var results = orm.entity(User.class).select()
    .orderBy(User_.createdAt)
    .offset(20).limit(10)
    .getResultList();

// Pagination
Page<User> page = orm.entity(User.class).select()
    .where(User_.active, EQUALS, true)
    .page(Pageable.ofSize(10));

// Scrolling
Window<User> window = userRepository.scroll(Scrollable.of(User_.id, 20));
// nextScrollable() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
var next = userRepository.scroll(window.nextScrollable());
```

</TabItem>
</Tabs>

## Distinct Results

Add `.distinct()` to eliminate duplicate rows from the result set. This is useful when selecting a related entity type from a query that could produce duplicates due to one-to-many relationships.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val cities = orm.entity(User::class)
    .select(City::class)
    .distinct()
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<City> cities = orm.entity(User.class)
    .select(City.class)
    .distinct()
    .getResultList();
```

</TabItem>
</Tabs>

---

## Streaming

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

For large result sets, use `selectAll()` or `select()` which return a Kotlin `Flow<T>`. Rows are fetched lazily from the database as you collect, so memory usage stays constant regardless of result set size. Flow also handles resource cleanup automatically when collection completes or is cancelled.

```kotlin
val users: Flow<User> = orm.entity(User::class).selectAll()

// Process each
users.collect { user -> process(user) }

// Transform and collect
val emails: List<String> = users.map { it.email }.toList()

// Count
val count: Int = users.count()
```

</TabItem>
<TabItem value="java" label="Java">

Java streams hold an open database cursor and JDBC resources. Unlike Kotlin's `Flow` (which handles cleanup automatically), Java `Stream` results must be explicitly closed. Always wrap them in a try-with-resources block to prevent connection leaks.

```java
try (Stream<User> users = orm.entity(User.class).selectAll()) {
    List<String> emails = users.map(User::email).toList();
}
```

</TabItem>
</Tabs>

---

## Joins

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Storm automatically joins entities referenced by `@FK` fields. When you need to join entities that are not directly referenced in the result type (for example, filtering through a many-to-many join table), use explicit `innerJoin` or `leftJoin` calls. The `on` clause specifies which existing entity in the query the joined table relates to.

```kotlin
val roles = orm.entity(Role::class)
    .select()
    .innerJoin(UserRole::class).on(Role::class)
    .whereAny(UserRole_.user eq user)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

Storm automatically joins entities referenced by `@FK` fields. For entities not directly referenced in the result type, such as join tables in many-to-many relationships, use explicit `innerJoin` or `leftJoin` calls. The `on` clause specifies which existing entity in the query the joined table relates to.

```java
List<Role> roles = orm.entity(Role.class)
    .select()
    .innerJoin(UserRole.class).on(Role.class)
    .where(UserRole_.user, EQUALS, user)
    .getResultList();
```

### Joins (SQL Templates)

SQL Templates let you write JOIN clauses directly, which is useful when the join condition is not a simple foreign key match or when you need to join on computed expressions.

```java
List<Role> roles = orm.query(RAW."""
        SELECT \{Role.class}
        FROM \{Role.class}
        INNER JOIN \{UserRole.class} ON \{UserRole_.role} = \{Role_.id}
        WHERE \{UserRole_.user} = \{user.id()}""")
    .getResultList(Role.class);
```

</TabItem>
</Tabs>

---

## Result Classes

Query result classes can be:
- **Plain records** -- Storm maps columns to fields (you write all SQL)
- **`Data` implementations** -- enable SQL template helpers like `${Class::class}`
- **`Entity`/`Projection`** -- full repository support with CRUD operations

Choose the simplest option that meets your needs. See [SQL Templates](sql-templates.md) for details.

---

## Compound Fields in Queries

When an inline record (embedded component) is used in a query clause, Storm automatically expands it into its constituent columns. This applies to WHERE, ORDER BY, and GROUP BY clauses.

### WHERE Clauses

Inline records expand differently depending on the operator:

**EQUALS / NOT_EQUALS** generate per-column AND conditions:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owner = orm.entity(Owner::class)
    .select()
    .where(Owner_.address, EQUALS, address)
    .singleResult
```

</TabItem>
<TabItem value="java" label="Java">

```java
Owner owner = orm.entity(Owner.class)
    .select()
    .where(Owner_.address, EQUALS, address)
    .getSingleResult();
```

</TabItem>
</Tabs>

```sql
WHERE o.address = ? AND o.city_id = ?
```

For NOT_EQUALS, the condition is wrapped in NOT:

```sql
WHERE NOT (o.address = ? AND o.city_id = ?)
```

**Comparison operators** (GREATER_THAN, GREATER_THAN_OR_EQUAL, LESS_THAN, LESS_THAN_OR_EQUAL) generate lexicographic comparisons using nested OR/AND. This preserves the natural multi-column ordering:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .where(Owner_.address, GREATER_THAN, address)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> owners = orm.entity(Owner.class)
    .select()
    .where(Owner_.address, GREATER_THAN, address)
    .getResultList();
```

</TabItem>
</Tabs>

```sql
WHERE (o.address > ? OR (o.address = ? AND o.city_id > ?))
```

For GREATER_THAN_OR_EQUAL, only the last column uses the inclusive operator:

```sql
WHERE (o.address > ? OR (o.address = ? AND o.city_id >= ?))
```

Some databases (PostgreSQL, MySQL, MariaDB, Oracle) support native tuple comparison syntax, which Storm uses automatically when available:

```sql
WHERE (o.address, o.city_id) > (?, ?)
```

**Unsupported operators.** LIKE, NOT_LIKE, IN, and NOT_IN do not have a meaningful multi-column interpretation and throw a `PersistenceException` when used with inline records. To filter on a sub-field, reference it directly:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .where(Owner_.address.address, LIKE, "%Main%")
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> owners = orm.entity(Owner.class)
    .select()
    .where(Owner_.address.address, LIKE, "%Main%")
    .getResultList();
```

</TabItem>
</Tabs>

### ORDER BY

Passing an inline record to `orderBy` or `orderByDescending` expands it into its leaf columns. For example, if `Owner_.address` is an inline record with `address` and `city` fields:

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .orderBy(Owner_.address)
    .resultList
```

```sql
ORDER BY o.address, o.city_id
```

Using `orderByDescending` applies DESC to each expanded column:

```sql
ORDER BY o.address DESC, o.city_id DESC
```

### GROUP BY

Inline records expand in GROUP BY the same way. This is particularly useful in combination with scrolling, where grouping by a column makes it unique in the result set. Wrap the metamodel with `.key()` to indicate it can serve as a cursor:

```kotlin
data class CityOrderCount(val city: City, val count: Long)

val orders = orm.entity(Order::class)
val window = orders.select(CityOrderCount::class) { "${City::class}, COUNT(*)" }
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Order_.city.key(), 20))
```

See [Scrolling: GROUP BY](#group-by) for details.

---

## Common Patterns

### Checking Existence

Use `existsBy` (Kotlin) or `.exists()` on the query builder (Java) to check whether a matching row exists without loading the full entity.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val exists: Boolean = orm.existsBy(User_.email, email)
```

</TabItem>
<TabItem value="java" label="Java">

```java
boolean exists = orm.entity(User.class)
    .select()
    .where(User_.email, EQUALS, email)
    .exists();
```

</TabItem>
</Tabs>

### Count with Filter

Combine `where` with `count` to count rows matching a condition without loading the entities themselves. Storm translates this to a `SELECT COUNT(*)` query.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val count: Long = orm.entity(User::class)
    .select()
    .where(User_.city eq city)
    .count
```

</TabItem>
<TabItem value="java" label="Java">

```java
long count = orm.entity(User.class)
    .select()
    .where(User_.city, EQUALS, city)
    .getCount();
```

</TabItem>
</Tabs>

### Finding a Single Result

When you expect at most one matching row, use `find` (Kotlin, returns `null` if not found) or `getOptionalResult` (Java, returns `Optional`). These methods throw if more than one row matches.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val user: User? = orm.find(User_.email eq email)
```

</TabItem>
<TabItem value="java" label="Java">

```java
Optional<User> user = orm.entity(User.class)
    .select()
    .where(User_.email, EQUALS, email)
    .getOptionalResult();
```

</TabItem>
</Tabs>

---

## Tips

1. **Use the metamodel** -- `User_.email` catches typos at compile time; see [Metamodel](metamodel.md)
2. **Kotlin: choose your style** -- quick queries (`orm.find`, `orm.findAll`) for simple cases, query builder for complex operations
3. **Java: DSL or Templates** -- DSL for type-safe conditions, SQL Templates for complex SQL like CTEs, window functions, or database-specific features
4. **Entity graphs load in one query** -- related entities marked with `@FK` are JOINed automatically, no N+1 problems
5. **Close Java streams** -- always use try-with-resources with `Stream` results
6. **Combine conditions freely** -- use `and` / `or` in Kotlin, `it.where().and()` / `.or()` in Java to build complex predicates
7. **Always use the returned builder** -- `QueryBuilder` is immutable; methods like `where()`, `orderBy()`, and `limit()` return a new instance. Ignoring the return value silently loses the change. Chain calls or reassign the variable.
