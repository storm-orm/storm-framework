# Refs

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Refs are lightweight identifiers for entities, projections, and other data types that defer fetching until explicitly required. They optimize performance by avoiding unnecessary data retrieval and are useful for managing large object graphs.

Unlike a typical lazy reference, a `Ref` never trades away query capability. Filter, order, group, and select through it with the metamodel exactly as you would through a directly-referenced entity, and Storm adds the join only for the query that actually needs it. When a query already knows it needs the referenced record, it can resolve the `Ref` as part of that same statement instead of paying for a separate fetch. Choosing `Ref<T>` over the entity type is a decision about the SELECT you get by default, not a capability you give up.

That said, it is a decision worth making sparingly. Joins on primary keys are cheap, so the default is to model foreign keys as direct types and reach for a `Ref` when an edge lets the graph grow beyond what your reads should carry. [Entity Design](entity-design.md) covers where that line sits and how Storm's schema-first generation draws it.

---

## Using Refs in Entities

To declare a relationship as a Ref, replace the direct type with `Ref<T>` in the field declaration. Storm stores only the foreign key column value and does not generate a JOIN for the referenced table. This reduces the width of SELECT queries and avoids loading data you may never access.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @FK val city: Ref<City>  // Lightweight reference
) : Entity<Int>
```

The `city` field contains only the foreign key ID, not the full `City` entity. Compare this with declaring `@FK val city: City`, which would load the full `City` (and its transitive `@FK` relationships) via auto-generated JOINs on every query.

A `Ref` does not give up type-safe querying. Selecting the entity still stores only the foreign key value, but you can filter, order, and select *through* the reference by naming the target's columns on the metamodel (for example `User_.city.country.name`). Storm adds the join for the referenced table on demand, only for a query that actually navigates beyond the foreign key. See [Querying Through Refs](#querying-through-refs).

</TabItem>
<TabItem value="java" label="Java">

The Java API uses `Ref<T>` in the same way as Kotlin. Declare the record component with `Ref<City>` instead of `City` to store only the foreign key.

```java
record User(@PK Integer id,
            String email,
            @FK Ref<City> city  // Lightweight reference
) implements Entity<Integer> {}
```

The `city` field contains only the foreign key ID, not the full `City` entity.

A `Ref` does not give up type-safe querying. Selecting the entity still stores only the foreign key value, but you can filter, order, and select *through* the reference by naming the target's columns on the metamodel (for example `User_.city.country.name`). Storm adds the join for the referenced table on demand, only for a query that actually navigates beyond the foreign key. See [Querying Through Refs](#querying-through-refs).

</TabItem>
</Tabs>

---

## Fetching

When you need the full referenced entity, call `fetch()`. This triggers a database lookup (or cache hit) on demand, loading only the data you actually need at the point you need it.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val user = orm.get(User_.id eq userId)
val city: City = user.city.fetch()  // Loads from database
```

</TabItem>
<TabItem value="java" label="Java">

Call `fetch()` to load the referenced entity on demand.

```java
Optional<User> user = orm.entity(User.class)
    .select()
    .where(User_.id, EQUALS, userId)
    .getOptionalResult();

City city = user.map(u -> u.city().fetch()).orElse(null);  // Loads from database
```

</TabItem>
</Tabs>

---

## Resolving a Ref as Part of the Query

Calling `fetch()` costs one query per reference. When you know up front that you will need the referenced record, name it with `fetch(...)` on the query builder. Storm then selects the referenced table's columns in place of the foreign key column, joined into the same statement, and the reference comes back already loaded. `fetch(...)` is defined relative to the query's root, so it comes right after `select()`, before any explicit join — a join widens the query's type past the point where the root-relative paths resolve. Read it with `getOrThrow()` rather than `fetch()`: both return the loaded record without a query, but `getOrThrow()` makes that guarantee visible at the call site instead of relying on `fetch()`'s dual on-demand/already-loaded behavior.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = orm.entity<User>().select()
    .fetch(User_.city, User_.city.country)
    .resultList

val city = users.first().city.getOrThrow()   // no query, and the code shows it
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> users = orm.entity(User.class)
    .select()
    .fetch(User_.city, User_.city.country)
    .getResultList();

City city = users.getFirst().city().getOrThrow();   // no query, and the code shows it
```

</TabItem>
</Tabs>

The entity is unchanged: the field stays `Ref<City>`, so the same record type serves queries that resolve the reference and queries that do not. What changes is the state of the reference in the result. `isLoaded()` returns `true`, `fetch()` returns without querying, and `getOrNull()` returns the record. Identity and equality are untouched, since a reference is compared by its type and key, and `unload()` returns to a reference that carries the key alone.

The plan is prefix-closed. Naming `User_.city.country` resolves `User_.city` as well, because the city record is what holds the country reference, so the deeper path is the only one you need to write:

```java
.fetch(User_.city.country)     // resolves city and its country
```

A reference the plan does not name stays a foreign key column, so resolving one level leaves the levels below it deferred:

```java
List<User> users = orm.entity(User.class).select()
    .fetch(User_.city)
    .getResultList();

City city = users.getFirst().city().getOrThrow();    // no query, and the code shows it
city.country().isLoaded();                           // false, still a foreign key column
```

Because a reference is always a to-one foreign key, resolving one widens the row without multiplying it: there is no row fan-out to guard against, unlike a join across a collection. A cycle stays bounded by the depth the path names, so a self-reference is resolved exactly as far as you ask:

```java
.fetch(Node_.parent.parent)    // exactly two levels
```

A nullable reference is joined with an outer join, so a row whose foreign key is null yields a null reference, matching how a nullable entity foreign key behaves.

Storm rejects a path that crosses no reference, since everything it names is already part of the record the query selects. It also rejects a reference to a [sealed type](polymorphism.md), whose concrete record is chosen per row from a discriminator rather than by a fixed column layout, so there is no layout to expand the reference into. Fetch those on demand.

### Asking for What the Query Resolved

`fetch()` resolves the reference on demand: if the query did not resolve it, it queries. That is what you want when the reference is meant to be deferred, but it makes a mistake in the plan invisible. A misspelled path, a `fetch(...)` lost in a refactor, or a branch that runs a different query all keep working, one query per row.

Pair `fetch(...)` on the query with `getOrThrow()` at the use site to close that gap. `getOrThrow()` returns the record that is loaded and never queries, so a plan that does not cover the path fails immediately instead of quietly degrading:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = orm.entity<User>().select()
    .fetch(User_.city)
    .resultList

val city = users.first().city.getOrThrow()   // no query; throws if the plan did not cover it
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> users = orm.entity(User.class)
    .select()
    .fetch(User_.city)
    .getResultList();

City city = users.getFirst().city().getOrThrow();   // no query; throws if the plan did not cover it
```

</TabItem>
</Tabs>

The error names the fix: *"Record for City is not loaded. The query did not resolve this reference: name it with fetch() so the query resolves it, or call fetch() to resolve it on demand."*

So the two accessors express two different intents, and picking the one you mean is what makes the intent checkable:

| | resolve on demand | require the query to have resolved it |
|---|---|---|
| throws | `fetch()` | `getOrThrow()` |
| returns null | `fetchOrNull()` | `getOrNull()` |

Use `getOrThrow()` wherever the code runs against a query you control and expects the reference to be there. Use `fetch()` where deferring is the point. Neither says anything about how the record was loaded: a reference wrapped with `Ref.of(entity)` is loaded without ever having been fetched, and `getOrThrow()` reads it just the same.

### Resolving Up Front or On Demand

Both produce the same record; they differ in when the work happens.

| | Query-time `fetch(...)` | On-demand `Ref.fetch()` |
|---|---|---|
| Statements | One | One per distinct reference |
| Row width | Referenced columns repeat per row | Foreign key column only |
| Known up front | Yes, named at the call site | No, decided where the record is used |

Resolve the reference when the code that runs the query already knows the referenced record is needed, especially when reading many rows. Leave it deferred when only some code paths need it, or when the referenced record is large relative to how often it is read.

---

## Preventing Circular Dependencies

Without Refs, an entity that references its own type would cause infinite recursion during auto-join generation: `User` joins `User`, which joins `User`, and so on. Declaring the self-referential field as `Ref<User>` breaks the cycle. Storm stores only the foreign key and does not attempt to join the table to itself.

This pattern applies to any recursive or hierarchical data model, such as organizational trees, threaded comments, or referral chains.

A self-reference is navigable like any other reference: the table is joined to itself, each occurrence under its own alias, so `User_.invitedBy.email` filters on the inviter's email rather than the row's own. The typed metamodel navigates a cycle two hops deep, because generated metamodels construct their children eagerly and so cannot recurse; beyond that, name the path as a string, which the engine resolves to any depth. See [Cyclic References](metamodel.md#cyclic-references).

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @FK val city: City,
    @FK val invitedBy: Ref<User>?  // Self-reference
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record User(@PK Integer id,
            String email,
            @FK City city,
            @Nullable @FK Ref<User> invitedBy  // Self-reference
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Selecting Refs

When you need to collect entity identifiers without loading full rows, select refs directly. This is useful for building ID lists to pass into subsequent queries (e.g., batch lookups or IN clauses) without the memory overhead of full entity hydration.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val role: Role = ...
val userRefs: Flow<Ref<User>> = orm.entity<UserRole>()
    .selectRef(User::class)
    .where(UserRole_.role eq role)
    .resultFlow
```

</TabItem>
<TabItem value="java" label="Java">

Selecting refs in Java returns a `List` of `Ref<T>` objects. You can also use SQL templates to achieve the same result with more control over the query structure.

```java
Role role = ...;
List<Ref<User>> users = orm.entity(UserRole.class)
    .selectRef(User.class)
    .where(UserRole_.role, EQUALS, role)
    .getResultList();
```

Using SQL Templates:

```java
List<Ref<User>> users = orm.query(RAW."""
        SELECT \{select(User.class, SelectMode.PK)}
        FROM \{UserRole.class}
        WHERE \{role}""")
    .getRefList(User.class, Integer.class);
```

</TabItem>
</Tabs>

---

## Using Refs in Queries

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Refs integrate directly into query filter expressions. You can pass a collection of Refs to an `inRefs` clause, which generates an `IN (...)` SQL expression using only the primary key values. This lets you chain queries efficiently: select refs from one query, then use them as filters in the next.

```kotlin
val userRefs: List<Ref<User>> = ...
val roles: List<Role> = orm.entity<UserRole>()
    .select(Role::class)
    .distinct()
    .where(UserRole_.user inRefs userRefs)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

Refs can be used directly in where clauses:

```java
List<Ref<User>> users = ...;
List<Role> roles = orm.entity(UserRole.class)
    .select(Role.class)
    .distinct()
    .whereRef(UserRole_.user, users)
    .getResultList();
```

Using SQL Templates:

```java
List<Ref<User>> users = ...;
List<Role> roles = orm.query(RAW."""
        SELECT DISTINCT \{Role.class}
        FROM \{UserRole.class}
        WHERE \{users}""")
    .getResultList(Role.class);
```

</TabItem>
</Tabs>

---

## Querying Through Refs

A `Ref` breaks the eager join, not the entity graph. You can still filter, order, and select through the foreign key by naming the target's columns on the metamodel, exactly as you would for a directly-referenced entity. Storm materializes the join for the referenced table on demand: only a query that navigates *beyond* the foreign key adds the join, while a query that stops at the reference selects it as its foreign key column with no join at all.

Consider `User` with `@FK val city: Ref<City>`, where `City` has a `country` foreign key.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Filter and order through the reference. The city and country tables are joined only because
// the query navigates beyond the city foreign key.
val users = orm.entity<User>()
    .select()
    .where(User_.city.country.name eq "United States")
    .orderBy(User_.city.name)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Filter and order through the reference. The city and country tables are joined only because
// the query navigates beyond the city foreign key.
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.city.country.name, EQUALS, "United States")
    .orderBy(User_.city.name)
    .getResultList();
```

</TabItem>
</Tabs>

Selecting the root entity still yields an unloaded `Ref`: the navigated columns pull in the join for filtering and ordering, but the selected `User.city` remains a foreign-key-only reference you resolve later with `fetch()`. A query that never navigates beyond the reference emits no join for the referenced table, so the reference stays as cheap as a plain foreign key column.

### Selecting a Column Through a Ref

A custom projection that references a beyond-reference column adds the join and selects that column:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class CountryName(val name: String)

val names = orm.entity<User>()
    .select<CountryName, _, _> { "${User_.city.country.name}" }
    .where(User_.city.country.name eq "United States")
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
record CountryName(String name) {}

List<CountryName> names = orm.entity(User.class)
    .select(CountryName.class, RAW."\{User_.city.country.name}")
    .where(User_.city.country.name, EQUALS, "United States")
    .getResultList();
```

</TabItem>
</Tabs>

### The Target's Primary Key Is Part of the Reference

A reference carries the target's primary key: `ref.id()` returns it without fetching the target, because the key is the foreign key column stored on the row itself. Queries mirror that. Reaching the primary key through a reference resolves to that column, so it needs no join, while any other column of the target does:

```kotlin
// No join: the key is already on the user row, exactly as user.city.id() reads it without fetching.
orm.entity<User>().select().where(User_.city.id eq 42).resultList
// SELECT ... FROM user u WHERE u.city_id = ?

// Joins: the name is not part of the reference, exactly as user.city.fetch().name needs the target.
orm.entity<User>().select().where(User_.city.name eq "Sunnyvale").resultList
// SELECT ... FROM user u INNER JOIN city c ON u.city_id = c.id WHERE c.name = ?
```

This is the same column the reference itself resolves to, so `User_.city.id eq 42` and `User_.city eq refById<City>(42)` produce identical SQL. It is also the same column an entity foreign key resolves its primary key to, so a path means the same thing whether the relationship is declared as an entity or as a `Ref`.

Because the key is read from the row, a match does not require the referenced row to exist. Express that requirement explicitly with a join or an exists clause when you need it.

### Naming the Referenced Table by Type

A path names the referenced table one column at a time. A query can also name the table itself, and the join is materialized the same way. Selecting the target hydrates it with its own foreign keys, exactly as selecting it through an entity foreign key does:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Selects the referenced entity: the city table is joined on demand, and so is its own country foreign key.
val cities = orm.entity<User>().select(City::class).resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Selects the referenced entity: the city table is joined on demand, and so is its own country foreign key.
List<City> cities = orm.entity(User.class).select(City.class).getResultList();
```

</TabItem>
</Tabs>

An explicit `innerJoin(...).on(City.class)` names the table the same way, and the reference brings it in for the join to resolve against. A query that names the table both ways gets one occurrence: a path navigating to it resolves against the same join, and a table the query joins explicitly keeps that occurrence, so an explicit join stays in charge of the table it brings in.

Bringing the table in is not the same as resolving the reference. A join makes the referenced table available to the rest of the statement, and `select(City::class)` makes it the result, but neither one touches the `User.city` of a selected user: it stays an unloaded foreign key. Resolving it into the user is what [`fetch(User_.city)`](#resolving-a-ref-as-part-of-the-query) does, and it works on the select list rather than on the tables the query can name. The two also differ in what they do to the rows: an inner join drops users whose city does not match, while `fetch(...)` uses an outer join for a nullable reference so every row survives.

### What You Can and Cannot Do Beyond a Ref

Nodes reached *beyond* a reference are **navigation-only**. They can be used anywhere a query needs a column reference: `where`, `orderBy`, `groupBy`, `having`, and custom selected columns. They cannot extract a value from an in-memory record, because a `Ref` is never hydrated into the parent, so value operations (such as `getValue` or `resultGroupedBy`) are not available on them and fail to compile. The reference node itself (`User_.city`) is value-extractable and yields the `Ref`, so grouping by the reference with [`resultGroupedByRef`](queries.md#grouped-results) works. See [Navigating Through Refs](metamodel.md#navigating-through-refs) for the type-level details.

### Designing Entities to Avoid Excessive Joins

A directly-referenced entity foreign key (`@FK val city: City`) is joined on **every** query, together with its own transitive foreign keys, because Storm hydrates the whole reachable graph in one select (see [Relationship Loading Behavior](relationships.md#relationship-loading-behavior)). For a wide or deep graph this fans out into many joins that most reads do not need.

Declaring the field as `Ref<City>` removes that join from every read while keeping the relationship fully queryable: the join appears only for the specific query that navigates beyond it. Prefer a `Ref` for foreign keys you do not hydrate on most reads, especially in wide or deep graphs, to keep SELECTs narrow without giving up type-safe filtering, ordering, and projection through the relationship.

### How Deep Should the Eager Graph Be?

Storm hydrates the eager graph in a single query, so reading an entity brings its relationships with it and there is no N+1 to manage. That graph is declared on the type, which means every read of the entity gets the same one. It should therefore describe what the entity *is*, rather than what any one screen happens to need.

That gives a clear line to draw:

- Declare an **entity foreign key** for relationships that are part of the entity, the ones you would expect to see whenever you look at it. In practice that is one or two levels.
- Declare a **`Ref`** for relationships that belong to particular queries. The read stays focused on the entity, and the reference is resolved where it is needed.

A `Ref` is complete on its own: call `fetch()` on it and the record is loaded. Naming it with [`fetch(...)`](#resolving-a-ref-as-part-of-the-query) on the query is an optimization for when you already know the read will need it, folding the load into the same statement rather than a query of its own. Reach for it when it helps; nothing about a `Ref` depends on it.

```java
record Order(
        @PK Integer id,
        @FK Customer customer,      // part of an order: every view of one shows it
        @FK Ref<Warehouse> origin,  // belongs to the fulfilment views
        @FK Ref<Campaign> campaign  // belongs to reporting
) implements Entity<Integer> {}
```

The fulfilment view already knows it works with the warehouse, so it says so and reads it without a second query:

```java
List<Order> orders = orm.entity(Order.class).select()
    .fetch(Order_.origin)
    .getResultList();

Warehouse origin = orders.getFirst().origin().getOrThrow();
```

Two things shape the graph:

- **Width and depth behave differently.** Foreign keys side by side add a join each; levels stacked on top of each other multiply by the fan-out of the level above. Depth is what determines the shape of a read, so it is the dimension worth being deliberate about.
- **A cycle must be a `Ref`**, so a self-reference or a mutual reference bounds the graph for you. See [Preventing Circular Dependencies](#preventing-circular-dependencies).

Repeated `fetch()` calls on the same reference are the signal that the declaration is in the wrong place. If particular reads need it, resolve it there with [`fetch(...)`](#resolving-a-ref-as-part-of-the-query); if nearly all of them do, it belongs on the entity as a plain foreign key.

---

## Creating Refs

You can create Refs programmatically from a type and ID, or extract one from an existing entity.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use the `ref()` extension to go from an entity to a Ref, and `refById()` when you have only the key. Both need an import from `st.orm.template`.

```kotlin
import st.orm.template.ref
import st.orm.template.refById

// From an existing entity
val user: User = ...
val ref: Ref<User> = user.ref()

// From type and ID, without an entity instance
val userRef: Ref<User> = refById<User>(42)
```

The underlying `Ref.of(user)` and `Ref.of(User::class.java, 42)` do the same thing; the extensions read better and infer the type, so prefer them in Kotlin.

</TabItem>
<TabItem value="java" label="Java">

```java
// From type and ID
Ref<User> userRef = Ref.of(User.class, 42);

// From existing entity
User user = ...;
Ref<User> ref = Ref.of(user);
```

</TabItem>
</Tabs>

---

## Detached Ref Behavior

Refs created with `Ref.of(type, primaryKey)` are **detached**: they carry the entity type and primary key but have no connection to a database context. This has important implications for fetching behavior.

- Calling `fetch()` on a detached ref throws a `PersistenceException` because there is no database connection available to retrieve the record.
- Calling `fetchOrNull()` returns `null` for the same reason.
- The `isFetchable()` method returns `false` for detached refs.

By contrast, refs created with `Ref.of(entity)` wrap an already-loaded entity instance. Calling `fetch()` or `fetchOrNull()` on such a ref returns the wrapped entity without any database access. The `isFetchable()` method also returns `false` (since it does not need to fetch), but `isLoaded()` returns `true`.

| Factory method | Holds data? | `fetch()` behavior | `isFetchable()` |
|----------------|-------------|-------------------|------------------|
| `Ref.of(type, primaryKey)` | No (ID only) | Throws `PersistenceException` | `false` |
| `Ref.of(entity)` | Yes (full entity) | Returns the wrapped entity | `false` |
| Loaded by Storm (from query) | Yes (after fetch) | Returns entity or fetches from DB/cache | `true` |

Use `Ref.of(entity)` when you already have the entity in memory and want to wrap it as a ref (for example, to pass into a method that expects `Ref<T>`). Use `Ref.of(type, primaryKey)` when you only have the ID and want a lightweight identifier for equality checks, map keys, or a lookup you hand to a repository with `findByRef` or `findAllByRef`.

---

## Aggregation with Refs

Refs are particularly useful in aggregation queries where you group by a foreign key. Instead of loading the full related entity for each group, name the foreign key path and select it as a Ref. This keeps the query lightweight while still giving you a typed identifier to use in subsequent lookups if needed.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class GroupedByCity(
    val city: Ref<City>,
    val count: Long
)

val counts: Map<Ref<City>, Long> = orm.entity<User>()
    .select<GroupedByCity, _, _> { "${User_.city}, COUNT(*)" }
    .groupBy(User_.city)
    .resultList
    .associate { it.city to it.count }
```

</TabItem>
<TabItem value="java" label="Java">

```java
record GroupedByCity(Ref<City> city, long count) {}

Map<Ref<City>, Long> counts = orm.entity(User.class)
    .select(GroupedByCity.class, RAW."\{User_.city}, COUNT(*)")
    .groupBy(User_.city)
    .getResultList().stream()
    .collect(toMap(GroupedByCity::city, GroupedByCity::count));
```

Using SQL Templates:

```java
Map<Ref<City>, Long> counts = orm.query(RAW."""
        SELECT \{User_.city}, COUNT(*)
        FROM \{User.class}
        GROUP BY \{User_.city}""")
    .getResultList(GroupedByCity.class).stream()
    .collect(toMap(GroupedByCity::city, GroupedByCity::count));
```

</TabItem>
</Tabs>

The database does the aggregating, so one row per city comes back and the reference carries the key you group by. Fetch the cities themselves with `findAllByRef(counts.keys)` when a later step needs them.

Naming the foreign key path selects the key column on the user table itself, so the referenced table is never joined:

```sql
SELECT u.city_id, COUNT(*) FROM user u GROUP BY u.city_id
```

The key does not have to be a Ref. Selecting the entity itself, `${City::class}` in place of `${User_.city}` with a `City` component instead of `Ref<City>`, produces a `Map<City, Long>` whose keys carry their data, with no later fetch. That form pays for the join, for the city columns on every row, and for a `GROUP BY` over all of them, and its keys hash across every field rather than the primary key alone. Prefer the Ref form unless you need the records and would have fetched them anyway.

This is not the same as [`resultGroupedBy`](queries.md#grouped-results). That variant does not change the SQL: it runs the same select and groups the hydrated rows, returning each city with the users that belong to it. Reach for it when you want the records themselves, and aggregate in the database, as above, when you only want a number per group.

---

## Use Cases

The following patterns illustrate the main scenarios where Refs provide concrete benefits over loading full entities. The common thread is reducing the amount of data loaded from the database until the moment it is actually needed.

### Optimizing Memory

When processing large collections of entities, loading full object graphs for each row can exhaust available memory. Refs store only the entity type and primary key (typically 16-32 bytes per reference, versus hundreds of bytes or more for a fully hydrated entity with nested relationships).

```kotlin
// Instead of loading full User objects
val users: List<User> = ...  // Each User has all fields loaded

// Load only IDs
val userRefs: List<Ref<User>> = ...  // Only IDs in memory
```

### Efficient Collections

Refs implement `equals()` and `hashCode()` based on their entity type and primary key, making them reliable keys in maps and sets. This lets you build lookup structures keyed by entity identity without loading the full entity data.

```kotlin
val userScores: Map<Ref<User>, Int> = ...

// Access by ref without loading full entity
val score = userScores[Ref.of(User::class.java, userId)]
```

### Deferred Loading

Refs enable a controlled form of lazy loading without proxies or bytecode manipulation. The entity field is declared as a Ref, and the calling code decides if and when to call `fetch()`. This makes the loading decision explicit in the code rather than hidden behind an ORM proxy.

```kotlin
data class Report(
    @PK val id: Int = 0,
    @FK val author: Ref<User>,  // Don't load user automatically
    val content: String
) : Entity<Int>

// Later, when you need the author
val report = orm.find(Report_.id eq reportId)
if (needsAuthorInfo) {
    val author = report?.author?.fetch()
}
```

## Fetching Behavior

Understanding how `fetch()` resolves its target helps you predict performance and avoid runtime errors.

- `fetch()` returns immediately when the query already resolved the reference (see [Resolving a Ref as Part of the Query](#resolving-a-ref-as-part-of-the-query)). Check with `isLoaded()`, or call `getOrThrow()` to demand it (see [Asking for What the Query Resolved](#asking-for-what-the-query-resolved)).
- `getOrThrow()` and `getOrNull()` never query. They return what is already loaded, so they are the accessors to reach for when the query was meant to resolve the reference.
- `fetch()` checks the [entity cache](entity-cache.md) before querying the database. If the entity was already loaded in the current transaction, no additional query is issued.
- Multiple Refs pointing to the same entity share the cached instance within a transaction, preserving object identity.
- Calling `fetch()` on a detached Ref created with `Ref.of(type, id)` always fails. Fetching needs the `ORMTemplate` that produced the reference, and a detached one carries only a type and a key. An active transaction does not change that; pass the reference to `findByRef` or `findAllByRef` instead.
- An attached Ref, conversely, needs no transaction of its own. It fetches over the connection its template provides, and that read runs in whatever transaction happens to be active at the time, or in none. There is no session to keep open and no state to lose, so a reference fetched outside a transaction reads exactly as it would inside one.

## Tips

1. **Reach for a Ref when an edge lets the graph grow.** A join on its own is not a reason, and neither is an optional relationship. Optional edges are simply the natural first cut once a graph does need trimming. See [Entity Design](entity-design.md).
2. **Use Refs for self-references.** Prevent circular loading in hierarchical data.
3. **Use Refs in aggregations.** Get counts by FK without loading full entities.
4. **Refs are reliable map keys.** They provide lightweight, identity-based comparison.
5. **Refs stay queryable.** Filter, order, and select through a Ref with the metamodel; the join is added only when a query navigates beyond the foreign key.
6. **Resolve the Ref when the query already knows you need it.** `fetch(User_.city)` on the query builder brings the referenced record back in the same statement, so reading it costs nothing.
7. **Read a resolved Ref with `getOrThrow()`.** It never queries, so a query that stopped resolving the reference fails where the assumption was made instead of turning into one query per row.
