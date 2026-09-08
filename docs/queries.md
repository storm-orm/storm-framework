# Queries

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

All Storm queries are type-safe: the generated metamodel (`User_`, `City_`, etc.) catches errors at compile time rather than at runtime.

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
| **Repository `findBy` / `findAllBy`** | Lookups by primary key or any single field | Full compile-time | Low (single-field equality or `IN`) |
| **Query DSL** | Filtering, ordering, pagination with type-safe conditions | Full compile-time | Medium (AND/OR predicates, joins, ordering) |
| **SQL Templates** | Complex joins, subqueries, CTEs, window functions, database-specific SQL | Column references checked at compile time, SQL structure at runtime | High (full SQL control) |

Start with the simplest approach that meets your needs. Use `findById`, `findBy`, or `findAllBy` for straightforward lookups. Move to the query builder when you need compound filters or pagination. Use SQL templates when you need SQL features the DSL does not cover.

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

The Java repository exposes the same `EntityRepository` interface as Kotlin. Obtain it with `orm.entity(Class)`. Field-based finders (`findBy`, `findAllBy`, `getBy`) cover single-column lookups; the fluent query builder handles anything more complex. Return types use `Optional` for single results and `List` for collections.

```java
var users = orm.entity(User.class);

// Find by ID
Optional<User> user = users.findById(userId);

// Find one by a field value
Optional<User> byEmail = users.findBy(User_.email, email);

// Find all matching a field value
List<User> usersInCity = users.findAllBy(User_.city, city);

// Find all whose field matches any of several values (WHERE ... IN)
List<User> selected = users.findAllBy(User_.city, cities);

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
val users = orm.entity<User, _>()

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

// Find one by a field value
Optional<User> byEmail = users.findBy(User_.email, email);

// Require exactly one (throws if none, or if more than one)
User owner = users.getBy(User_.email, email);

// Find all matching a field value
List<User> usersInCity = users.findAllBy(User_.city, city);

// Count and exists
long count = users.count();
boolean exists = users.existsById(userId);
```

</TabItem>
</Tabs>

---

## Filtering with Predicates

Predicate paths traverse the entity graph (`User_.city.country.name`), and they may also navigate *through* a `Ref` foreign key. Storm adds the join for the referenced table on demand, only for a query that references a column beyond the foreign key. This holds for every clause that names a column: `where`, `orderBy`, `groupBy`, `having`, and custom selected columns. See [Querying Through Refs](refs.md#querying-through-refs).

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
val users = orm.entity<User>()
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
val results = orm.entity<User>()
    .select()
    .where(User_.active eq true)
    .where(User_.city eq city)           // AND-combined with previous where
    .resultList
```

Builder-style `where()` calls (with `and`/`or` predicates) compose with other `where()` calls in the same way:

```kotlin
val results = orm.entity<User>()
    .select()
    .where(User_.active eq true)
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
val users = orm.entity<User>()
    .select()
    .orderBy(User_.name)
    .resultList

// Descending
val users = orm.entity<User>()
    .select()
    .orderByDescending(User_.createdAt)
    .resultList

// Multiple fields (all ascending)
val users = orm.entity<User>()
    .select()
    .orderBy(User_.lastName, User_.firstName)
    .resultList
```

Multiple `orderBy` and `orderByDescending` calls can be chained to build multi-column sort clauses with mixed directions. Each call appends to the existing ORDER BY clause rather than replacing it, so you can mix ascending and descending columns freely.

```kotlin
// Mixed sort directions: last name ascending, first name descending
val users = orm.entity<User>()
    .select()
    .orderBy(User_.lastName)
    .orderByDescending(User_.firstName)
    .resultList
```

When a path passed to `orderBy`, `orderByDescending`, or `groupBy` resolves to multiple columns, it expands to those columns in order, resolved exactly as a predicate on that path would be. An inline record (embedded component) expands into its component columns: if `User_.fullName` is an inline record with `lastName` and `firstName` fields, `orderBy(User_.fullName)` produces `ORDER BY last_name, first_name`. A foreign key expands to its foreign key column(s) on the referencing table, without joining the referenced table, so `orderBy(Visit_.pet)` orders by `visit.pet_id` and a foreign key to a table with a compound primary key contributes every foreign key column once. Grouping states an identity rather than a column, so it resolves differently: see [Grouping by an entity](#grouping-by-an-entity). With `orderByDescending`, `DESC` follows every expanded column.

For full control over the ORDER BY clause (for example, to use SQL expressions or database-specific syntax), use the template overload. Metamodel fields are resolved to their column names automatically.

```kotlin
// Mixed sort directions (template)
val users = orm.entity<User>()
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

When a path passed to `orderBy`, `orderByDescending`, or `groupBy` resolves to multiple columns (an inline record, or a foreign key to a table with a compound primary key), it expands to those columns in order, resolved exactly as a predicate on that path would be: component columns for an inline record, the foreign key column(s) on the referencing table for a foreign key.

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

val counts: List<CityCount> = orm.entity<User>()
    .select<CityCount, _, _> { "${City::class}, COUNT(*)" }
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

For aggregation queries that involve multiple tables or CTEs, SQL Templates give you full control over the query structure while still mapping results to typed records. A template renders the clause as written, so the grouping names every table the select list carries; see [Grouping in a template](#grouping-in-a-template).

```java
List<CityCount> counts = orm.query(RAW."""
        SELECT \{City.class}, COUNT(*)
        FROM \{User.class}
        GROUP BY \{City_.id}""")
    .getResultList(CityCount.class);
```

</TabItem>
</Tabs>

### Grouping by an entity

A grouping states what one row stands for. `groupBy` accepts the entity you want one row of, and Storm emits whichever columns express that on the database in hand:

```kotlin
.groupBy(User_.city)      // one row per city
.groupBy(User_.city.id)   // the same relationship, so the same grouping
.groupBy(User_.id)        // one row per user
.groupBy(User_.name)      // one row per distinct name: a value, not an identity
```

The first three name an identity. The relationship, the key beyond it, and the query root's own key all say the same thing, and a reference says it too. The last names a value, and groups by that value literally.

Selecting an entity selects the tables its foreign keys reach, so when the selected entity has foreign keys of its own the grouping has to cover those tables too. Storm adds their keys. Every foreign key is to-one, so a grouped key already fixes them, and naming them cannot move a row into a different group.

### One query, every dialect

How many columns an identity takes is a property of the database, not of the query. The SQL standard lets a grouped key stand for the columns it determines, and products disagree on whether they implement it:

| | GROUP BY emitted |
| --- | --- |
| PostgreSQL, MySQL, SQLite, H2 | the key |
| SQL Server, Oracle | every selected column of that table |

Storm generates the form each product accepts, from the same source:

```kotlin
orm.entity<User>()
    .select<CityCount, _, _> { "${City::class}, COUNT(*)" }
    .groupBy(User_.city)
```

```sql
-- PostgreSQL, MySQL, SQLite, H2
SELECT c.id, c.name, c.population, COUNT(*) FROM user u INNER JOIN city c ON u.city_id = c.id GROUP BY c.id

-- SQL Server, Oracle
... GROUP BY c.id, c.name, c.population
```

Writing the second form by hand is what portability used to cost: a column list that exists only for the strictest product, that has to be revisited whenever the entity gains a field, and that says nothing about intent. Stating the identity says what the query means once, and leaves the spelling to the dialect.

### Grouping in a template

A template is the SQL you wrote, so a metamodel interpolated into one resolves to its column and nothing is added:

```java
orm.query(RAW."""
        SELECT \{City.class}, COUNT(*)
        FROM \{User.class}
        GROUP BY \{City_.id}""")
    .getResultList(CityCount.class);
```

The grouping has to name every table the select list carries. Where the selected entity has foreign keys of its own, that means their keys as well: the builder adds them for you, a template does not, because it renders what it is given. Reach for the builder when the grouping should follow the entity, and for a template when you want the clause exactly as written.


### Filtering Groups

`having()` filters the groups that `groupBy()` produces, the way `where()` filters rows. A condition on a grouped
column takes a predicate; a condition on an aggregate takes a template, because no metamodel path names a
computed value.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Grouped column: a predicate
val counts: List<Long> = orm.entity<User>()
    .selectCount()
    .groupBy(User_.city)
    .having((User_.city eq amsterdam) or (User_.city eq rotterdam))
    .resultList

// Aggregate: a template
val busy = orm.entity<User>()
    .select<CityCount, _, _> { "${City::class}, COUNT(*)" }
    .groupBy(User_.city)
    .having { "COUNT(*) > $minimum" }
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Grouped column
List<Long> counts = orm.entity(User.class)
    .selectCount()
    .groupBy(User_.city)
    .having(User_.city, EQUALS, amsterdam)
    .getResultList();

// Aggregate, or any condition combining groups with OR
List<CityCount> busy = orm.entity(User.class)
    .select(CityCount.class, RAW."\{City.class}, COUNT(*)")
    .groupBy(User_.city)
    .having(RAW."COUNT(*) > \{minimum}")
    .getResultList();
```

</TabItem>
</Tabs>

Consecutive `having()` calls are AND-combined, each clause parenthesized, the same way consecutive `where()` calls
are. In Kotlin a disjunction stays in code: compose the predicate with infix `or`. In Java a predicate cannot be
built outside a `where()` lambda, so a HAVING disjunction uses the template form.

A joined entity's column goes through the same `having()` call: a join widens the query, so every clause accepts
paths from any entity in it. The predicate form takes the predicate directly rather than a builder: a HAVING
clause filters groups, so the id, ref and record matching that `where()`'s builder offers does not carry over.

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
val results = orm.entity<User>().select()
    .orderBy(User_.createdAt)
    .offset(20).limit(10)
    .resultList

// Pagination
val page: Page<User> = orm.entity<User>().select()
    .where(User_.active eq true)
    .page(Pageable.ofSize(10))

// Scrolling
val window: Window<User> = userRepository.scroll(Scrollable.of(User_.id, 20))
// next() is non-null when the window has content.
// hasNext is informational; the developer decides whether to follow the cursor.
val next = userRepository.scroll(window.next())
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
// next() is non-null when the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
var next = userRepository.scroll(window.next());
```

</TabItem>
</Tabs>

## Distinct Results

Add `.distinct()` to eliminate duplicate rows from the result set. This is useful when selecting a related entity type from a query that could produce duplicates due to one-to-many relationships.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val cities = orm.entity<User>()
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

## Grouped Results

Group results by a related record, typically the parent entity of a foreign key. The metamodel path names the
group key; the result is a map from parent to its children:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Load cities with their users in one query
val usersByCity: Map<City, List<User>> = orm.entity<User>()
    .select()
    .where(User_.active eq true)
    .orderBy(User_.city)
    .resultGroupedBy(User_.city)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Load cities with their users in one query
Map<City, List<User>> usersByCity = orm.entity(User.class)
    .select()
    .where(User_.active, EQUALS, true)
    .orderBy(User_.city)
    .getResultGroupedBy(User_.city);
```

</TabItem>
</Tabs>

The SQL is not affected by the grouping: the same select is executed and the results are grouped during
hydration, so the whole graph loads in a single query. Hydration does not pay for the duplication in the join
result: repeated group records are materialized once and grouped by instance identity, not by comparing record
fields. The returned map and its lists are unmodifiable and insertion-ordered; use `orderBy()` to control the
order of groups and of results within each group. Because duplicate entities within a result set share the same
instance, each result's reference to its group key is the map key itself.

The where clause keeps its normal meaning: it filters the results, and a group appears only when at least one of
its results matches. The path must resolve to a non-null record for every result; narrow queries over nullable
foreign keys with a `where()` clause first. See [Relationships](relationships.md#one-to-many) for the
one-to-many loading pattern.

`resultGroupedBy` reads the group key from each hydrated record, so its path must be a value node from the
eagerly-loaded graph. A path *beyond* a `Ref` is navigation-only and cannot be a group key: it does not compile.
Group by the reference itself with `resultGroupedByRef`, which takes the foreign key without hydrating the target.
See [Navigating Through Refs](metamodel.md#navigating-through-refs).

The ref-based variant `resultGroupedByRef` (Java: `getResultGroupedByRef`) returns `Map<Ref<V>, List<T>>`
instead. Refs are compared by primary key, keeping map lookups constant-cost regardless of the size of the group
record. For eagerly fetched entity paths the keys are loaded refs: `getOrNull()` returns the record the query
already materialized, combining primary-key lookups with direct access to the data. The path may also reference
a `Ref` field, in which case the group is taken directly from the foreign key without fetching the referenced
record; such refs remain unloaded unless the query resolved the reference with
[`fetch(...)`](refs.md#resolving-a-ref-as-part-of-the-query), and `findAllByRef(map.keys)` fetches them in a single
query when needed:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Group visits by pet without fetching the pets
val visitsByPet: Map<Ref<Pet>, List<Visit>> = orm.entity<Visit>()
    .select()
    .resultGroupedByRef(Visit_.pet)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Group visits by pet without fetching the pets
Map<Ref<Pet>, List<Visit>> visitsByPet = orm.entity(Visit.class)
    .select()
    .getResultGroupedByRef(Visit_.pet);
```

</TabItem>
</Tabs>

---

## Streaming

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

For large result sets, use `select().resultFlow`, which returns a Kotlin `Flow<T>`. Rows are fetched lazily from the database as you collect, so memory usage stays constant regardless of result set size. Flow also handles resource cleanup automatically when collection completes or is cancelled.

While rows remain to be emitted, the flow is one open statement and its connection is consume-only: a query, a `Ref.fetch()` or a write from inside the collector is refused inside a transaction, on every database. A loop that needs the database iterates in windows with `select().windows(size)`, one closed statement per window; see [Batch Processing & Streaming](batch-streaming.md#streaming).

```kotlin
val users: Flow<User> = orm.entity<User>().select().resultFlow

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
try (Stream<User> users = orm.entity(User.class).select().getResultStream()) {
    List<String> emails = users.map(User::email).toList();
}
```

While rows remain unread, the stream is one statement and its connection is consume-only: a query, a `Ref.fetch()` or a write from inside the loop is refused inside a transaction, on every database. A loop that needs the database iterates in windows with `select().windows(size)`, one closed statement per window and nothing to close; see [Batch Processing & Streaming](batch-streaming.md#streaming).

</TabItem>
</Tabs>

---

## Joins

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Storm automatically joins entities referenced by `@FK` fields. When you need to join entities that are not directly referenced in the result type (for example, filtering through a many-to-many join table), use explicit `innerJoin` or `leftJoin` calls. The `on` clause specifies which existing entity in the query the joined table relates to.

A join widens the query: from the join onward, every clause accepts paths from any entity in the query, so the joined table's fields go through the same `where`, `orderBy`, `groupBy` and `having` calls as the root's.

```kotlin
val roles = orm.entity<Role>()
    .select()
    .innerJoin<UserRole>().on<Role>()
    .where(UserRole_.user eq user)
    .resultList
```

The widening trades the compile-time root check for query-time resolution: a path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root. Root-relative operations are affected by the wider type: `fetch(...)` comes before any join, and a grouped terminal such as `resultGroupedBy` needs the root back: `narrow<Role>()` restores it, verified against the query's FROM table. The counterpart `widen()` widens without a join, admitting short-form references to the entities already in the query's graph.

The infix `and`/`or` combinators follow the same discipline: a combination roots at its operands' least common root, and written directly in a `where(...)` call the builder's root drives the inference. On the widened query above, `.where((UserRole_.user eq user) and (Role_.name eq name))` composes predicates across the joined entities in one clause, while the same expression on a narrow, join-less builder is rejected at the call site.

</TabItem>
<TabItem value="java" label="Java">

Storm automatically joins entities referenced by `@FK` fields. For entities not directly referenced in the result type, such as join tables in many-to-many relationships, use explicit `innerJoin` or `leftJoin` calls. The `on` clause specifies which existing entity in the query the joined table relates to.

A join widens the query: from the join onward, every clause accepts paths from any entity in the query, so the joined table's fields go through the same `where`, `orderBy`, `groupBy` and `having` calls as the root's.

```java
List<Role> roles = orm.entity(Role.class)
    .select()
    .innerJoin(UserRole.class).on(Role.class)
    .where(UserRole_.user, EQUALS, user)
    .getResultList();
```

The widening trades the compile-time root check for query-time resolution: a path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root. Root-relative operations are affected by the wider type: `fetch(...)` comes before any join, and a grouped terminal such as `getResultGroupedBy` needs the root back: `narrow(Role.class)` restores it, verified against the query's FROM table. The counterpart `widen()` widens without a join, admitting short-form references to the entities already in the query's graph.

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

When an inline record (embedded component) is used in a query clause, Storm automatically expands it into its constituent columns. This applies to WHERE, ORDER BY, and GROUP BY clauses. A foreign key expands the same way in WHERE and ORDER BY, into its foreign key column(s) on the referencing table; in GROUP BY it states an identity, described under [Grouping by an entity](#grouping-by-an-entity).

### WHERE Clauses

Inline records expand differently depending on the operator:

**Equality** (`eq` / `neq`, Java `EQUALS` / `NOT_EQUALS`) generates per-column AND conditions:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owner = orm.entity<Owner>()
    .select()
    .where(Owner_.address eq address)
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

For inequality (`neq`, Java `NOT_EQUALS`), the condition is wrapped in NOT:

```sql
WHERE NOT (o.address = ? AND o.city_id = ?)
```

**Comparison operators** (`greater`, `greaterEq`, `less`, `lessEq`; Java `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`) generate lexicographic comparisons using nested OR/AND. This preserves the natural multi-column ordering:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity<Owner>()
    .select()
    .where(Owner_.address greater address)
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

For the inclusive variants (`greaterEq` / `lessEq`, Java `GREATER_THAN_OR_EQUAL` / `LESS_THAN_OR_EQUAL`), only the last column uses the inclusive operator:

```sql
WHERE (o.address > ? OR (o.address = ? AND o.city_id >= ?))
```

Some databases (PostgreSQL, MySQL, MariaDB, Oracle) support native tuple comparison syntax, which Storm uses automatically when available:

```sql
WHERE (o.address, o.city_id) > (?, ?)
```

**Unsupported operators.** `like`, `notLike`, `inList`, and `notInList` (Java `LIKE`, `NOT_LIKE`, `IN`, `NOT_IN`) do not have a meaningful multi-column interpretation and throw a `PersistenceException` when used with inline records. To filter on a sub-field, reference it directly:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity<Owner>()
    .select()
    .where(Owner_.address.address like "%Main%")
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
val owners = orm.entity<Owner>()
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

Inline records expand in GROUP BY the same way, and a foreign key groups by its foreign key column(s) on the referencing table: `groupBy(Order_.city)` produces `GROUP BY o.city_id`, without joining the referenced table. Grouping by a column makes it unique in the result set, which is particularly useful in combination with scrolling. Wrap the metamodel with `.key()` to indicate it can serve as a cursor:

```kotlin
data class CityOrderCount(val city: City, val count: Long)

val orders = orm.entity<Order>()
val window = orders.select<CityOrderCount, _, _> { "${City::class}, COUNT(*)" }
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
val count: Long = orm.entity<User>()
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
4. **Entity graphs load in one query** -- related entities marked with `@FK` are JOINed automatically, so the declared graph costs one statement and nothing loads behind your back
5. **Close Java streams** -- always use try-with-resources with `Stream` results
6. **Combine conditions freely** -- use `and` / `or` in Kotlin, `it.where().and()` / `.or()` in Java to build complex predicates
7. **Always use the returned builder** -- `QueryBuilder` is immutable; methods like `where()`, `orderBy()`, and `limit()` return a new instance. Ignoring the return value silently loses the change. Chain calls or reassign the variable.
