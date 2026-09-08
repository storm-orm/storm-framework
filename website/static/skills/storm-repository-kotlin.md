---
name: storm-repository-kotlin
description: Write Storm repositories in Kotlin, covering EntityRepository, custom repository interfaces, CRUD, batching, and transactions. Use when adding or changing a repository in Kotlin.
---

Help the user write a Storm repository using Kotlin.
**Important:** Storm can run on top of JPA, but when generating repository code, always use Storm's own `EntityRepository` API with JDBC `DataSource` — not `EntityManager`, `@PersistenceContext`, or Spring Data JPA repositories.

## Key Imports

```kotlin
import st.orm.repository.*                       // EntityRepository, plus every repository extension:
                                                 //   entity<User>(), repository<UserRepository>(),
                                                 //   select<Result, _, _> { }, selectRef, findBy, findAllBy,
                                                 //   insert, writeSet, exists, count, ...
                                                 // Use the wildcard: these are top-level extensions, and naming
                                                 // them one by one is how an import ends up missing.
import st.orm.template.*                         // ORMTemplate, QueryBuilder, orm, ref, eq, neq, etc.
import st.orm.Operator.*                         // escape hatch: only when the operator is chosen at runtime
import st.orm.Ref                                // Lazy-loaded reference
import st.orm.Page                               // Offset-based pagination result
import st.orm.Pageable                           // Pagination request
import st.orm.Scrollable                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window                             // Keyset scrolling result (Window<R>)
import st.orm.test.StormTest                     // Test annotation
import st.orm.test.SqlCapture                    // SQL capture for verification
import st.orm.test.CapturedSql.Operation         // SELECT, INSERT, UPDATE, DELETE, UNDEFINED
import org.junit.jupiter.api.Assertions.*        // assertEquals, assertTrue, assertFalse
```

Ask: which entity, what custom queries?

**Repository rule:** All database queries must live in repository interfaces, not inline in services or other classes. Services orchestrate by calling repository methods — they never build queries directly. When a skill or tool generates a query, always place it in the appropriate repository interface.

**Layering rule:** Follow the codebase's existing convention first — if handlers already use repositories directly, or a service layer is consistently in place, match that style rather than introduce a competing one. Absent a clear stance (new code, greenfield), promote the layered architecture: controller → service → repository, where controllers never inject repositories — all data access flows through services, which own the transaction boundaries and return view-model types. Whatever the stance, do not mix styles: layer-skipping controllers undermine the service layer's cross-cutting concerns (transactions, caching, authorization).

**Result types:** Custom query result types (aggregation DTOs, computed shapes) are plain data classes — they do NOT implement `Data`, which is reserved for table-backed types. Define them top-level in the repository file whose queries return them (not in the model package), and document each one as a query result shape:

```kotlin
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain data class — deliberately not a Data type.
 */
data class CityUserCount(val city: City, val userCount: Long)
```

Detect the project's framework from its build file (pom.xml or build.gradle.kts): look for `storm-kotlin-spring-boot-starter` or `spring-boot-starter` (Spring Boot), `storm-ktor` or `ktor-server-core` (Ktor), or neither (standalone). Use the detected framework to suggest the appropriate repository registration pattern below.

**DI preference:** In Spring Boot or Ktor projects, always prefer constructor-injected repositories over `orm.entity<T>()` or `orm.repository<T>()` lookups. Repository lookup via `orm` is for standalone (non-DI) use and tests only. In DI environments, repositories are beans/components — inject them.

## Getting a Repository

### Spring Boot (preferred in DI environments)

Inject repositories via constructor injection. The Spring Boot Starter (or a `RepositoryBeanFactoryPostProcessor`) auto-registers repository interfaces as beans:

```kotlin
@Service
class UserService(private val userRepository: UserRepository) {
    fun findUser(email: String) = userRepository.findByEmail(email)
}

// For generic entity access without a custom repository, inject EntityRepository directly:
@Service
class CityService(private val cities: EntityRepository<City, Int>) {
    fun findAll() = cities.findAll()
}
```

### Ktor

Repositories from the compile-time index are registered automatically when the `Storm` plugin is installed; access them with a bare `repository<T>()` in route handlers:

```kotlin
get("/users/{email}") {
    val users = repository<UserRepository>()
    call.respond(users.findByEmail(call.parameters.getOrFail("email")))
}
```

### Standalone / Tests

Create repositories directly from the `ORMTemplate` (no DI container):

```kotlin
// Generic entity access (no custom interface needed)
val users = orm.entity<User>()               // preferred — reified, import st.orm.repository.entity
val users = orm.entity(User::class)          // also works, no import needed

// Custom repository (interface with explicit query method bodies)
val userRepository = orm.repository<UserRepository>()  // import st.orm.repository.repository
```

**Star projection caveat:** `orm.entity<User>()` returns `EntityRepository<User, *>` — the ID type is erased. Methods that depend on the ID type parameter (`existsById`, `findById`, `removeById`, etc.) will fail with star projection errors. For ID-based operations, use `orm.entity<User, _>()` (reified; the underscore infers the ID type from the entity declaration), a typed custom repository (`EntityRepository<User, Int>`), or `orm.entity(User::class)` (the ID type is inferred from context).

```kotlin
// ⚠️ Repository interfaces MUST import the predicate operators — they are Kotlin extension functions:
// import st.orm.template.eq   (and neq, like, greater, less, etc.) — or simply import st.orm.template.*
// Without the import, `eq` etc. will not resolve in the interface file.
// (`and`/`or` are member functions on PredicateBuilder — they need no import.)

interface UserRepository : EntityRepository<User, Int> {
    fun findByEmail(email: String): User? = find(User_.email eq email)
    fun findByCity(city: City): List<User> = findAll(User_.city eq city)
    fun findActiveInCity(city: City): List<User> =
        findAll((User_.city eq city) and (User_.active eq true))
}
```

Key rules:
1. ALL query methods have EXPLICIT BODIES. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, update, remove, removeById, removeByRef, removeAll, findById, findBy(Key), count, existsById, page, scroll, windows.
3. Descriptive variable names: `val users = orm.entity<User>()`, not `val repo`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value (or use the `select { }` DSL which handles this automatically).
5. Streaming: `select().resultFlow` returns a `Flow` with automatic resource cleanup; while rows remain to be emitted it is one open statement and its connection is consume-only (a query, `Ref.fetch()` or write from the collector inside `transaction { }` throws, on every database). A loop that reads or writes per row uses `windows(size)` (`Flow<Window<E>>`): one closed statement per window, write per window with `update(window.content().map { ... })`.
6. DELETE/UPDATE without WHERE throws. Use `unsafe()` for intentional bulk ops.
7. Pagination: `page(0, 20)` for offset-based. `scroll(Scrollable.of(User_.id, 20))` for keyset on large tables (see Keyset Scrolling section).
8. **Prefer entity/metamodel-based methods over templates.** For joins, use `innerJoin<Entity, OnEntity>()` in the block DSL, or `.innerJoin<Entity>().on<OnEntity>()` in the chained API. Only fall back to template lambdas when QueryBuilder cannot express the query.
   **Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin<T>().on { "..." }`) or a full `orm.query { }` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin<Entity>().on<OnEntity>()`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database. Projections join like entities: `.on<ProjectionType>()` resolves the foreign key by matching the referenced entity's table against the projection's table. When multiple foreign keys reference that table the join is ambiguous — Storm fails with an error naming the candidate fields; disambiguate with a template ON clause.
9. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key. When a projection already returns `Ref<T>`, use it directly without calling `.ref()` again.
10. **Prefer typed parameters over raw IDs — full entities by default.** Repository method signatures take the full entity for FK parameters when callers naturally hold one (the common case): predicates like `eq` and `inList` accept entities directly, so no `.ref()` conversion is needed at the call sites. `Ref<Entity>` parameters remain fine — use them for identity-only flows, where callers hold refs (e.g. from `Ref<T>` fields) or only an id, converted at the system boundary with `refById<T>(id)` (import `st.orm.template.refById`). Never accept raw IDs like `String` or `Int` — they are untyped and lose the entity association.
11. **Typed ID from `Ref`:** Use `ref.entityId()` (import `st.orm.template.entityId`) to extract a type-safe ID. Avoid `ref.id()` — it returns `Any` and requires an unsafe cast.

## API Design: Prefer the Simplest Approach

Four levels, from simplest to most powerful — always prefer the simplest that works:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`find`, `findAll`, `removeAll`, `count`, `exists`) | Simple lookups and operations |
| 2 | Builder with predicate (`select(predicate)`, `delete(predicate)`) | Filtered queries needing ordering, pagination, or joins |
| 3 | Block DSL (`select { }`, `delete { }`) | Complex queries with multiple joins and conditions |
| 4 | SQL Templates (/storm-sql-kotlin) | CTEs, window functions, database-specific features |

**Level 1 — Convenience methods** execute immediately and return results directly:
- **Read:** `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findAll(predicate)`, `findAllById()`, `findAllByRef()`, `findBy(key, value)`, `findAllBy(field, value)`, `findRefBy(...)`, `findAllRefBy(...)`
- **Read (throw):** `getById()`, `getByRef()`, `getBy(key, value)`
- **Exists/Count:** `count()`, `count(predicate)`, `exists()`, `exists(predicate)`, `existsById()`, `existsByRef()`, `countBy(field, value)`
- **Write:** `insert()`, `insertAndFetch()`, `update()`, `updateAndFetch()`, `upsert()`, `upsertAndFetch()`
- **Remove:** `remove(entity)`, `removeById(id)`, `removeByRef(ref)`, `removeAll()`, `removeAll(predicate)`, `removeAllBy(field, value)`, `remove(Iterable)`, `removeByRef(Iterable)`, `remove(Flow)`, `removeByRef(Flow)`
- **Pagination:** `page()`, `pageRef()`, `scroll()`, `windows()`

**Level 2 — Builder with predicate** returns `QueryBuilder` for chaining ordering, pagination, or joins:
```kotlin
users.select(User_.city eq city)
    .orderBy(User_.name)
    .resultList

users.delete(User_.active eq false)
    .executeUpdate()
```

Alternatively, use `select()` chained with `.where()` — equivalent, just a style preference:
```kotlin
users.select()
    .where(User_.city eq city)
    .orderBy(User_.name)
    .resultList
```

**Level 3 — Block DSL** for complex queries with multiple joins and conditions:
```kotlin
users.select {
    where(User_.active eq true)
    orderBy(User_.name)
}.resultList

users.delete {
    where(User_.score less 10)
}.executeUpdate()
```

Terminal operations: `.resultList`, `.singleResult`, `.optionalResult`, `.resultFlow`, `.resultStream`, `.windows(size)`, `.resultCount`, `.page()`, `.scroll()`, `.executeUpdate()`

The `find`/`get` distinction: `find` returns nullable (no result = null), `get` throws `NoResultException`.

The `delete`/`remove` distinction: `remove` operates on entities or ids you already have (immediate execution). `delete` builds a query to find and delete rows by criteria (returns `QueryBuilder`):
```kotlin
// remove — you have the entity/id, execute immediately
users.remove(user)
users.removeById(42)
users.removeAll()

// remove — with predicate (convenience, executes immediately)
val removed: Int = users.removeAll(User_.active eq false)

// delete — build a query with filtering (returns QueryBuilder)
users.delete(User_.active eq false).executeUpdate()
users.delete { where(User_.score less 10) }.executeUpdate()
```

> ⚠️ There is **no** `delete(entity)` or `delete(id)` overload (unlike JPA / Spring Data `CrudRepository`). Every `delete(...)` returns a `QueryBuilder`, so a bare `users.delete(entity)` treats the entity as a predicate argument and does **not** compile as an immediate delete. To delete an entity or id you already hold, use `remove(entity)` / `removeById(id)` / `removeByRef(ref)`.

## CRUD Operations

```kotlin
// Insert (infix, returns inserted entity with generated ID)
val user = orm insert User(email = "alice@example.com", name = "Alice", city = city)

// Insert with fetch (returns entity with generated PK and DB defaults)
val user: User = users.insertAndFetch(User(email = "alice@example.com", name = "Alice", city = city))
val id: Int = users.insertAndFetchId(User(email = "alice@example.com", name = "Alice", city = city))

// Read
val found: User? = users.findById(user.id)                   // nullable
val fetched: User = users.getById(user.id)                    // throws NoResultException
val found: User? = users.findByRef(userRef)                   // by Ref
val fetched: User = users.getByRef(userRef)                   // throws if not found

// Update (infix, entities are immutable — use copy())
orm update user.copy(name = "Alice Johnson")
val updated: User = users.updateAndFetch(user.copy(name = "Alice Johnson"))

// Upsert (insert or update)
orm upsert User(id = 1, email = "alice@example.com", name = "Alice", city = city)
val upserted: User = users.upsertAndFetch(User(id = 1, email = "alice@example.com", name = "Alice", city = city))

// Remove
orm remove user
users.removeById(userId)
users.removeByRef(userRef)
```

## ORMTemplate Convenience Functions

`ORMTemplate` (via `RepositoryLookup`) provides reified extension functions for quick access without creating a repository first:

```kotlin
// Read shortcuts (reified — type inferred from predicate)
val alice: User? = orm.find(User_.name eq "Alice")
val all: List<User> = orm.findAll(User_.city eq city)
val all: List<User> = orm.findAll<User>()

// Select with predicate (returns QueryBuilder for chaining)
val users: List<User> = orm.select(User_.city eq city).resultList

// Field-based lookups
val user: User? = orm.findBy(User_.email, "alice@example.com")
val user: User = orm.getBy(User_.email, "alice@example.com")
val cityUsers: List<User> = orm.findAllBy(User_.city, city)

// Streaming (use select() builder + resultFlow terminal)
val allFlow: Flow<User> = orm.select<User>().resultFlow
val cityFlow: Flow<User> = orm.select(User_.city eq city).resultFlow

// Ref variants
val refs: List<Ref<User>> = orm.findAllRef<User>()

// Remove by field
orm.removeBy(User_.city, city)
```

## Ref-Based Operations

```kotlin
// Create a Ref directly from an entity (no repository needed)
val ref: Ref<User> = user.ref()            // import st.orm.template.ref

// Create a Ref from a type and ID (no entity instance needed)
val ref: Ref<City> = refById<City>(cityId)     // import st.orm.template.refById

// Or via repository
val ref: Ref<User> = users.ref(user)
val ref: Ref<User> = users.ref(userId)     // from ID only

// Unload an entity to a lightweight Ref (discards entity data, keeps PK)
val ref: Ref<User> = users.unload(user)

// Lookup by Ref
val found: User? = users.findByRef(ref)
val fetched: User = users.getByRef(ref)
users.removeByRef(ref)
orm removeByRef ref   // infix

// Batch Ref operations
users.removeByRef(listOf(ref1, ref2, ref3))
val entities: List<User> = users.findAllByRef(listOf(ref1, ref2))
```

**Document what a query resolves.** When a repository query names references with `fetch(...)`, say so in its doc: name the references it resolves, then that `getOrThrow()` returns them without querying. Callers cannot see the plan from the signature, so without it they fall back to `fetch()`, which quietly reverts the query to one statement per row.

```kotlin
/** Users in a country. The city is resolved, so `getOrThrow()` returns it without querying. */
fun findByCountry(country: Country) =
    select().fetch(User_.city).where(User_.city.country eq country).resultList
```

At a call site, note it only where the repeated read is not obvious from the code, trailing the query:

```kotlin
val users = userRepository.findByCountry(country)   // city resolved
users.forEach { render(it.city.getOrThrow()) }
```

**The convenience reads resolve nothing.** `findById`, `findAllById`, `getById`, `findAll`, and the predicate and `Ref` lookups carry no fetch plan, so every `Ref` on the rows they return comes back unloaded and `getOrThrow()` on one of them throws. A read whose caller reads a reference needs a query that names it.



## Predicate-Based Queries

Use predicate lambdas for quick lookups without building a full QueryBuilder chain:

```kotlin
// Single result (nullable)
val alice: User? = users.find(User_.email eq "alice@example.com")

// Single result (throws NoResultException if not found)
val alice: User = users.get(User_.email eq "alice@example.com")

// List of results
val activeUsers: List<User> = users.findAll(User_.active eq true)

// Compare by entity — use the FK field directly, don't extract the ID
val cityUsers: List<User> = users.findAll(User_.city eq city)

// Repository methods take typed parameters — the full entity by default:
// ✅ fun findByCity(city: City): List<User> = findAll(User_.city eq city)
// ✅ fun findByCity(city: Ref<City>): List<User> = findAll(User_.city eq city)  // for identity-only flows
// ❌ fun findByCity(cityId: Int): List<User> = ...  // untyped, loses entity association

// For the Ref variant, callers holding only an id convert at the boundary:
// val users = userRepository.findByCity(refById<City>(cityId))

// Ref variants (return Ref<User> instead of User — lightweight, only loads PK)
val ref: Ref<User>? = users.findRef(User_.email eq "alice@example.com")
val refs: List<Ref<User>> = users.findAllRef(User_.active eq true)

// Count by predicate
val activeCount: Long = users.count(User_.active eq true)

// Exists by predicate
val hasActive: Boolean = users.exists(User_.active eq true)

// Remove by predicate
val removed: Int = users.removeAll(User_.active eq false)
```

These accept a `PredicateBuilder` built with infix operators. Use parentheses — not braces — for predicates. Braces are reserved for the block DSL (see below).

## Field-Based Lookups

Query by a specific metamodel field without writing a full predicate:

```kotlin
// Find by field value
val user: User? = users.findBy(User_.email, "alice@example.com")
val user: User = users.getBy(User_.email, "alice@example.com")   // throws if not found

// Find all by field value
val cityUsers: List<User> = users.findAllBy(User_.city, city)

// Count / Exists by field
val count: Long = users.countBy(User_.city, city)
val exists: Boolean = users.existsBy(User_.email, "alice@example.com")

// Remove by field
val deleted: Int = users.removeAllBy(User_.city, city)
```

All field-based methods also accept `Ref<V>` as the value parameter for FK lookups.

## Batch Operations

```kotlin
// Batch insert/update/remove with iterables
orm insert listOf(user1, user2, user3)
orm update listOf(user1, user2)
orm remove listOf(user1, user2)

// With fetch (returns inserted/updated entities with generated values)
val inserted: List<User> = users.insertAndFetch(listOf(user1, user2))
val updated: List<User> = users.updateAndFetch(listOf(user1, user2))
val ids: List<Int> = users.insertAndFetchIds(listOf(user1, user2))

// Upsert (insert or update)
users.upsert(listOf(user1, user2))
val upserted: List<User> = users.upsertAndFetch(listOf(user1, user2))
```

## Dirty Checking and Update Suppression

Inside a transaction, Storm observes entity state as it reads and compares against that observed
state when `update()` is called. The observed state lives in the transaction context, never on the
entity, and is discarded at commit. For an entity read in the same transaction:

- **Nothing changed → no SQL at all.** `orm update user` with an unmodified instance executes no
  statement. Read-modify-write code can pass entities back unconditionally and let Storm drop the
  no-ops.
- **Anything changed → full-row UPDATE** (default `ENTITY` mode): one stable SQL shape per entity,
  which keeps JDBC batching effective.

Suppression needs the observed state to be available: it applies inside a transaction, to entities
read in that same transaction, through entity-repository updates. In every other case — outside a
transaction, an entity constructed rather than read, observed state no longer available — Storm
falls back to a full-row UPDATE. The fallback costs a redundant write, never a wrong one: treat the
skipped UPDATE as an optimization, not a guarantee. Joined-inheritance entities
(`@Polymorphic(JOINED)` hierarchies) are the exception: their multi-table updates always write and
are not dirty-checked.

**Delete-and-reinsert and dirty checking are mutually exclusive.** Inserts are never dirty-checked,
and freshly constructed rows carry no observed state, so a write path that clears rows and
re-inserts the desired set writes every row every time. The dirty-checking-native shape for "make
the table match this desired state" keeps the instances that were read: update the full set and let
Storm suppress the unchanged rows, reserving `insert`/`remove` (or a write set) for actual
additions and removals. Batch updates apply this per entity — clean entities are dropped from the
batch and only dirty ones are written.

What a dirty entity writes is the update mode — `@DynamicUpdate` on the entity
(/storm-entity-kotlin) or `storm.update.default_mode` globally:

- `ENTITY` (default): any change writes the full row; no change writes nothing.
- `FIELD`: only the changed columns are written (plus the `@Version` column when present). Narrower
  writes, but every distinct combination of changed columns is its own SQL shape: batches split per
  shape, and after `storm.update.max_shapes` distinct shapes (default 5) Storm falls back to
  full-row updates to preserve batching.
- `OFF`: no comparison; always write all columns. Predictable unconditional writes for batch/ETL
  paths.

How a field is compared is a separate axis — `@DynamicUpdate(dirtyCheck = ...)` per entity or
`storm.update.dirty_check` globally. `INSTANCE` (default) marks a field dirty when its reference
changed; `copy()` reuses the references of untouched fields, so unchanged fields compare clean at
pointer cost. `VALUE` compares with `equals()` and differs only when code rebuilds equal values in
new instances, e.g. mapping the same data back from a form or DTO.

**Foreign keys compare by id, not by content.** The dirty check follows the column. Under `VALUE`,
an `@FK` field compares the referenced entity's primary key only (the generated metamodel emits
`a.city.id == b.city.id`): a referenced `City` whose own fields changed does not make the
referencing `User` dirty, because the `city_id` column is unchanged — and updating the `User` would
not write the `City`'s fields anyway. To persist changes inside a referenced entity, update that
entity. Under `INSTANCE`, substituting a different instance with the same id marks the FK column
dirty and costs a redundant write of the same value. `Ref<T>` fields compare by the id the ref
carries.

Dirty checking decides what to write; it does not detect concurrent writers. Lost-update protection
is `@Version` (optimistic locking), unchanged by any of the above.

Bulk mutations bypass dirty checking, and Storm invalidates observed state so later comparisons
stay truthful: a mutation with a known entity type (`delete(...)` builders, template mutations
naming the type) clears the observed state of that type; a raw SQL mutation clears all observed
state in the transaction. Updates after such a mutation fall back to full-row writes.

## Write Sets (Mixed-Type Graphs)

Apply one write operation to entities of multiple types. Storm orders the writes by foreign-key
dependencies, batches per type per dependency level, and propagates generated keys. For insert and
upsert, unsaved entities held in the passed entities' foreign-key fields are discovered and
inserted automatically (insert discovery). Children link to a new parent by holding the same
instance; two equal but distinct unsaved instances describe two rows.

```kotlin
val owner = Owner(firstName = "Alice", lastName = "Bond", address = address)   // unsaved
val wolfie = Pet(name = "Wolfie", birthDate = date, type = dog, owner = owner) // same owner instance
val rex = Pet(name = "Rex", birthDate = date, type = dog, owner = owner)
val visit = Visit(visitDate = today, description = "Check-up", pet = wolfie)

orm.writeSet().insert(listOf(wolfie, rex, visit))   // owner discovered and inserted first

// Typed single-root variant: whole graph in, keyed root out
val fetched: Visit = orm.writeSet().insertAndFetch(visit)

// Keys only, in input order, no re-read: the middle tier between insert and insertAndFetch
val ids: List<Long> = orm.writeSet().insertAndFetchIds(visits)
val two: List<Long> = orm.writeSet().insertAndFetchIds(wolfie, visit)   // vararg extension; one shared id type

// Scoped block; each verb executes immediately, wrap in a transaction for atomicity
transaction {
    orm.writeSet {
        insert(newPets)
        update(changedOwners)      // update/remove write only the entities passed, no discovery
        remove(staleVisits)        // children are removed before parents
    }
}
```

An entity is unsaved when its primary key is the default value on an auto-generated key. Also
available on repositories: `users.writeSet()` (delegates to the template; not scoped to the
repository's type).

## Flow-Based Streaming

Use Kotlin `Flow` for memory-efficient processing of large datasets:

```kotlin
// Stream all entities lazily (builder method + terminal)
val allUsers: Flow<User> = users.select().resultFlow

// Stream with filter (builder method + terminal)
val activeUsers: Flow<User> = users.select(User_.active eq true).resultFlow

// A flow with rows still to emit is one open statement: its connection is consume-only, so inside transaction { }
// a query, Ref.fetch() or write from the collector throws. Loops that need the database use windows:
// keyset windows over the primary key, one closed statement per window, connection free in between.
users.select(User_.active eq true).windows(1000).collect { window ->
    users.update(window.content().map { it.copy(processed = true) })   // one batched statement per window
}

// Count via Flow
val count: Long = users.countById(idFlow)
val count: Long = users.countByRef(refFlow, chunkSize = 500)

// Batch insert/update/remove via Flow (suspending). Feed an in-memory flow; a resultFlow of the same
// transaction is refused as soon as a batch executes while that flow still has rows to emit.
users.insert(userFlow, batchSize = 100)
users.update(userFlow, batchSize = 100)
users.remove(userFlow, batchSize = 100)

// Remove by Ref via Flow
users.removeByRef(refFlow, batchSize = 100)

// Insert via Flow with fetch (returns Flow of results)
val insertedFlow: Flow<User> = users.insertAndFetch(userFlow)
val idFlow: Flow<Int> = users.insertAndFetchIds(userFlow, batchSize = 500)
```

Flow operations are lazy — entities are retrieved/processed as consumed. Use `batchSize`/`chunkSize` to control how many items are sent to the database per batch. Default batch size is used when omitted.

## Flows and the Connection

A `resultFlow` is one open statement. While it still has rows to emit, the connection it reads from is consume-only, on every database. Inside `transaction { }` every statement shares the transaction's connection, so these all throw `PersistenceException` from the collector:

```kotlin
transaction {
    users.select().resultFlow.collect { user ->
        orm update user.copy(processed = true)   // ❌ write while the flow has rows left
        user.city.fetch()                        // ❌ Ref.fetch() is a statement too
        cities.count()                           // ❌ any query
    }
    users.update(users.select().resultFlow)      // ❌ batched write fed by a flow of the same transaction
}
```

The last line is the trap that passes small tests: the flow completes and closes when its last row is emitted, so a batch that executes after the flow has been read to its end is allowed, and a batch that executes while rows remain is refused. Whether that happens depends on batch size versus row count, so never feed a `resultFlow` of the current transaction into `insert`, `update`, `upsert`, `remove`, `removeByRef`, `insertAndFetch` or `countById`. Feed them an in-memory flow, or iterate in windows.

`windows(size)` is the shape for a loop that needs the database. Each window is fetched by one statement that has closed before the window is emitted, so the collector may query, fetch refs and write, and one batched write per window costs one statement rather than one per row:

```kotlin
// One transaction for the whole walk:
transaction {
    users.select(User_.active eq true).windows(1000).collect { window ->
        users.update(window.content().map { it.copy(processed = true) })
    }
}

// Or a transaction per window, so progress is durable and locks are short-lived:
users.windows(1000).collect { window ->
    transaction { users.update(window.content().map { it.copy(processed = true) }) }
}

// Resume after a restart from a stored cursor:
users.windows(Scrollable.fromCursor(User_.id, storedCursor)).collect { window ->
    process(window.content())
    store(window.nextCursor())
}
```

Rules for `windows`: the key is the primary key (or the `Scrollable`'s key), which must be a non-null single column; no `orderBy()` on the query; the result type must be the entity (`selectRef()` and custom select types are refused). Each window is its own statement and sees the committed state at that moment.

What stays fine with `resultFlow`: consuming it (`collect`, `toList()`, `count()`, `map`, `filter`), stopping early (`first()`, `take(n)` cancel the flow and close the statement), and, once it has completed, any statement. A `Ref` the loop needs is loaded by naming it in the fetch plan (`select().fetch(...)`) instead of calling `fetch()` per row. Outside `transaction { }` a collected flow holds a pooled connection of its own for as long as it is collected.

## Count, Exists, Remove

```kotlin
val count: Long = users.count()
val exists: Boolean = users.existsById(userId)
val existsByRef: Boolean = users.existsByRef(userRef)
users.removeById(userId)
users.removeByRef(userRef)
users.removeAll()   // removes all entities
```

## Pagination and Scrolling

```kotlin
// Offset-based pagination (executes count + select)
// Page numbers are 0-based — page 0 is the first page.
// When accepting 1-based page numbers from a URL (e.g., ?page=1), pass page - 1.
val page: Page<User> = users.page(0, 20)
val page: Page<User> = users.page(Pageable.ofSize(20).sortBy(User_.name))
val nextPage = users.page(page.nextPageable())

// Page API — Page is a Java record; ALL accessors are methods, call with ()
// page.content()       — List<User> of results for this page
// page.totalPages()    — total number of pages
// page.totalCount()    — total number of elements across all pages
// page.pageNumber()    — current page number (0-based)
// page.pageSize()      — page size
// page.hasNext()       — whether a next page exists
// page.hasPrevious()   — whether a previous page exists
// page.nextPageable()  — Pageable for the next page

// Keyset scrolling (better for large tables — no COUNT, cursor-based)
// Scrollable<T> takes a single type parameter (the entity type)
// ⚠️ Scrollable manages ORDER BY internally — do NOT add orderBy() when using scroll(Scrollable)
// ⚠️ The scroll key must be a single-column, non-nullable unique key (e.g. a simple @PK or @UK
//    field) — junction tables with composite PKs cannot be scrolled directly.
//    To scroll filtered results from a junction table, query the entity with a simple PK
//    and JOIN through the junction table (e.g., scroll User with a JOIN through UserRole).
val window = users.scroll(Scrollable.of(User_.id, 20))

// With custom sort order (sort column in addition to key)
val window = users.scroll(Scrollable.of(User_.id, User_.name, 20))

// First request vs subsequent: use Scrollable.of() when no cursor exists,
// Scrollable.fromCursor() when resuming. The cursor is opaque and exists for
// client-server communication: it contains exactly what the client needs to
// navigate the scroll window (key position, size, direction) — clients echo
// it back unchanged, never parse or construct it. Server-side code never
// needs the cursor: window.next()/previous() return a ready-to-use typed
// Scrollable<T> — the cursor is merely its serialized form.
val scrollable = if (cursor != null) {
    Scrollable.fromCursor(User_.id, cursor)
} else {
    Scrollable.of(User_.id, 20)
}
val window = users.scroll(scrollable)

// Window<R> is the scroll result record. Both scroll() methods return Window.
// Window API — Window is a Java record; ALL accessors are methods, call with ()
// window.content() — List<User> of results
// window.hasNext() / window.hasPrevious() — bounds checking
// window.nextCursor() / window.previousCursor() — opaque cursors for REST APIs (see above)
// window.next() / window.previous() — typed Scrollable<T> for programmatic navigation
// window.nextScrollable() / window.previousScrollable() — raw Scrollable<?> record component accessors (use next()/previous() instead)
```

## Framework-Specific Repository Registration

Detect the project's framework from its build file and dependencies, then suggest the appropriate pattern:

### Spring Boot
With `storm-kotlin-spring-boot-starter`, repository interfaces are auto-discovered and registered as beans — no configuration needed; just inject them. Only when using plain `storm-kotlin-spring` (no starter) do you switch scanning on with `@EnableStormRepositories(basePackages = [...])`, or define `RepositoryBeanFactoryPostProcessor(basePackages = ..., ormTemplateBeanName = ..., repositoryPrefix = ...)` beans when multiple repository sets bind to different templates.
```kotlin
@Service
class UserService(private val userRepository: UserRepository) {
    fun findUser(email: String) = userRepository.findByEmail(email)
}
```

### Ktor
Repositories auto-register at `install(Storm)` from the compile-time index (narrow with `repositories("com.myapp")` in the plugin config, or disable with `autoRegisterRepositories = false`); access them in routes with a bare `repository<T>()`:
```kotlin
fun Application.module() {
    install(Storm)
    routing {
        get("/users/{email}") {
            val users = repository<UserRepository>()
            call.respond(users.findByEmail(call.parameters.getOrFail("email")))
        }
    }
}
```

When the app has a service layer, use Ktor's built-in dependency injection: the plugin registers every repository under its own interface type, so services declare repositories as constructor parameters and are provided in the dependency container:
```kotlin
class UserService(private val userRepository: UserRepository) { ... }

fun Application.module() {
    install(Storm)
    dependencies {
        provide { UserService(resolve()) }
    }
    routing {
        val userService: UserService by dependencies
        get("/users/{email}") { call.respond(userService.find(call.parameters.getOrFail("email"))) }
    }
}
```
Koin users bridge the same repository registry with a few lines of application code; the Ktor integration docs include the recipe.

### Standalone
Create repositories directly from the `ORMTemplate`:
```kotlin
val userRepository = orm.repository<UserRepository>()
```

## Transactions

**Respect an existing stance first.** When the codebase has already settled on a convention — declarative `@Transactional` throughout, no coroutines at all, or fully suspend — Storm code should follow that convention rather than introduce a competing style. The preference below applies when the project has not taken a clear stance (new code, greenfield modules).

Prefer Storm's programmatic transactions over declarative `@Transactional` — in every environment, including Spring Boot. The boundary is explicit code, so there are no AOP proxy pitfalls (self-invocation and non-public methods silently skip `@Transactional`), it is coroutine-native, and the `Transaction` receiver exposes `setRollbackOnly()`, `onCommit { }`, and `onRollback { }` as typed API. In Spring Boot, suspend mode requires disabling Storm's Spring transaction integration (see below); Storm then manages transactions directly on the DataSource. Declarative `@Transactional` remains fully supported for teams standardized on it — in that mode, use `transactionBlocking { }` instead of suspend mode.

Use the top-level suspend function `transaction { }` (import `st.orm.template.transaction`). Two placement rules apply, and they are separate concerns. **Transactions open at the service level** — the method that represents one business operation — never in controllers or route handlers, which handle HTTP and delegate. Controllers that only read directly from repositories do not open transactions. (In minimal route-based apps without a service layer, the route handler doubles as the operation boundary.) **`suspend` propagates toward the start of the call stack** — services and any intermediate functions that open a `transaction { }` are `suspend`; never use `runBlocking` merely to wrap a `transaction { }` deeper in the stack — the bridge to blocking code, if one is needed at all, belongs at the entry point. `transaction { }` is NOT a method on `ORMTemplate` — never write `orm.transaction { }` or `call.orm.transaction { }`. The lambda receiver is a `Transaction` — it does NOT provide `entity(...)`; use repositories or `orm` captured from the enclosing scope. Optional parameters: `propagation`, `isolation`, `timeoutSeconds`, and `readOnly` (e.g. `transaction(readOnly = true) { }` around multi-query reads for a consistent snapshot on one connection).

**`runBlocking` belongs at entry points only.** When a non-suspend framework callback starts the call chain (a Spring MVC handler, an `ApplicationRunner`, a `@Scheduled` method, a message listener, a test), bridge with `runBlocking { }` at that entry point and keep everything below it suspend; never bury `runBlocking` inside services or repositories. In coroutine-native servers (Ktor, WebFlux) the handler itself is suspend and no bridge is needed. For Spring MVC, prefer non-suspend handlers with `runBlocking` over suspend handlers: MVC is a blocking servlet stack — with virtual threads the blocking bridge is cheap, whereas suspend MVC handlers require `kotlinx-coroutines-reactor` and route responses through async dispatch machinery that buys nothing there. `transactionBlocking { }` exists for code that is genuinely outside any coroutine context.

### Spring Boot

Suspend mode is incompatible with Spring-managed Storm transactions (Spring's transaction context is thread-bound, coroutines are not) — Storm fails fast with "Suspend mode is not supported when spring-managed transactions are enabled". When using suspend `transaction { }`, exclude the integration; keep it (the default) when using `@Transactional` or `transactionBlocking { }` that must join Spring-managed transactions:

```yaml
spring:
  autoconfigure:
    exclude:
      - st.orm.spring.boot.autoconfigure.StormTransactionAutoConfiguration
```

```kotlin
@Service
class UserService(private val userRepository: UserRepository) {
    suspend fun createUser(email: String, city: City): User = transaction {
        userRepository.insertAndFetch(User(email = email, city = city))
    }
}

@RestController
class UserController(private val userService: UserService) {
    // The MVC handler is the entry point: bridge with runBlocking here and
    // keep the call chain below it suspend.
    @PostMapping("/users")
    fun createUser(@RequestBody request: CreateUserRequest): User = runBlocking {
        userService.createUser(request.email, request.city)
    }
}
```

Declarative `@Transactional` on service methods also works (standard Spring) for teams that prefer it.

### Ktor

```kotlin
get("/users") {
    val users = repository<UserRepository>()
    transaction {
        // All operations within the block share the same transaction.
        call.respond(users.findAll())
    }
}
```

Transaction options are also available globally via `withTransactionOptions { }` (isolation via `TransactionIsolation`, propagation via `TransactionPropagation`).

## Block-Based Query DSL

**Prefer the chained API for linear queries.** A straight filter/order/limit pipeline reads best as a chain — `select(predicate).orderBy(...).limit(...).resultList`, or `select().where { template }...` when the condition needs a template. Reach for the `select { }` block only when you truly need the block structure: conditional predicates or joins (`if`/`when` inside the block), or queries with many clauses where the scoped layout helps.

Repository methods can use the `select { }` / `delete { }` DSL for building queries. Both are **builder methods** that return `QueryBuilder` -- they never execute immediately. Inside the block, use scope methods like `where()`, `orderBy()`, `limit()` to construct the query. Then call a terminal operation to execute:

```kotlin
interface UserRepository : EntityRepository<User, Int> {
    fun findActive(): List<User> = select { where(User_.active eq true) }.resultList

    fun findActiveByCity(city: City): List<User> = select {
        where((User_.active eq true) and (User_.address.city eq city))
        orderBy(User_.name)
    }.resultList

    fun deleteInactive(): Int = delete { where(User_.active eq false) }.executeUpdate()
}
```

Both `select { }` and `delete { }` return a `QueryBuilder`, so you pick the terminal: `.resultList`, `.singleResult`, `.optionalResult`, `.scroll(scrollable)`, `.page(0, 20)`, `.resultFlow`, `.resultCount` (for select), or `.executeUpdate()` (for delete). **Do NOT combine `orderBy()` with `.scroll(Scrollable)`** — see Keyset Scrolling section above.

**Result types and the block DSL:** There is **no** `select(ResultType::class) { block }` form. The block DSL always returns the root entity type. (In `select(ResultType::class) { ... }` / `select<ResultType, _, _> { ... }` the trailing lambda is a SQL template for the SELECT clause, not a block DSL.) To select a different result type, use the chained API:
```kotlin
// ❌ Not valid — no block DSL overload for result type
fun findSummaries(): List<UserSummary> = select(UserSummary::class) {
    where(User_.active eq true)
}.resultList

// ✅ Use chained API — note: joins use .innerJoin<A>().on<B>(), not the two-type-arg form
fun findSummaries(): List<UserSummary> = select(UserSummary::class)
    .where(User_.active eq true)
    .resultList
```

**What `select(ResultType::class)` is for:** It selects a different result type from the query. This works for joined entity types (e.g., `City::class` from a `User` query) and custom SELECT with a template string (`select<Summary, _, _> { template }` — the result type is explicit; the underscores let Kotlin infer the repository's entity and ID types). Style: use the underscore form only in code that is otherwise fully reified; in `::class`-style code write `select(Summary::class) { template }` — keep each snippet internally consistent. It does **not** work for column subsets of the root entity — `select(UserSummary::class)` where `UserSummary` has a subset of `User` fields will fail with "Cannot find alias for column." For column subsets, use a `Projection<T>` with `ProjectionRepository`.

**Cross-entity pitfall:** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`). Put the query on the target entity's repository instead.

**Conditional logic inside the block:** The block is a regular Kotlin lambda — use `if`, `when`, and loops to compose queries dynamically. This keeps shared parts (ordering, pagination, terminals) in one place:
```kotlin
interface UserRepository : EntityRepository<User, Int> {
    fun findByCity(city: Ref<City>?, page: Int, size: Int): Page<User> =
        select {
            if (city != null) {
                where(User_.city eq city)
            }
            orderBy(User_.name)
        }.page(page, size)
}
```

This also works with conditional joins:
```kotlin
fun findFiltered(city: Ref<City>?, page: Int, size: Int): Page<User> =
    select {
        if (city != null) {
            innerJoin<UserAddress, User>()
            where(UserAddress_.city eq city)
        }
        orderByDescending(User_.createdAt)
    }.page(page, size)
```

Predicate variants also return `QueryBuilder`:
```kotlin
// select(predicate) returns QueryBuilder
users.select(User_.active eq true).resultList

// delete(predicate) returns QueryBuilder
users.delete(User_.active eq false).executeUpdate()
```

Standalone usage via `ORMTemplate` — note there is **no** `orm.select<T> { block }` reified form; get the entity repository first:
```kotlin
val users = orm.entity<User>().select {
    where(User_.name eq "Alice")
    orderBy(User_.email)
    limit(10)
}.resultList
```

## Verification

After writing repository methods, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the repository method produces the query the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```kotlin
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Without it, paths resolve relative to the test class's package.
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class UserRepositoryTest {
    @Test
    fun findByCity(orm: ORMTemplate, capture: SqlCapture) {
        val userRepository = orm.repository<UserRepository>()
        val city = orm.entity<City, _>().getById(1)   // getById is ID-based — use the two-type-arg form
        val users = capture.execute { userRepository.findByCity(city) }
        // Verify intent: single query, filtered by city, returns expected data.
        assertEquals(1, capture.count(Operation.SELECT))
        assertFalse(users.isEmpty())
        assertTrue(users.all { it.city == city })
    }
}
```

Keep the verification loop on H2: it answers in milliseconds and needs no Docker, which is what makes verify-and-fix iterations cheap. Escalate to the target database only when H2 cannot run the SQL involved (dialect-specific functions, JSON operators, upsert or sequence syntax the target database defines differently), when the user asks for it, or as a final pass before finishing a larger piece of work: adding `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`, from `st.orm.test.TestDatabase`) to `@StormTest` runs the same test in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. Nothing else in the test changes; the class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

**SQL visibility outside tests:** raise the `st.orm.sql` logger to `DEBUG` to log every executed statement at runtime, or to `TRACE` to render parameter values into it — useful for debugging without a test harness.

**Finding what a call costs:** statement logging answers what ran; the SQL log summary answers what a unit of work cost. Raise `st.orm.sql.perf` to `INFO` and each web controller request, scheduled task or listener invocation reports one line: how many statements it took, the summed database time against how long the call took, and a row per distinct statement ranked by total time. Read it for the usual wins:

- A row with a high execution multiplier (`7x`) is one statement issued once per record. Storm hides no query, so the repetition is a loop in application code: batch it with `findAllById`, an `inList` predicate, or `resultGroupedBy` instead of a query per parent.
- A row marked `fetch` is a reference resolved on demand. Naming it in the query's fetch plan (`select().fetch(path)`) folds the load into the parent statement, and `getOrThrow()` then reads it without querying.
- A read that joins many tables and maps many columns for a call that uses few of them says the type materializes more graph than the read needs. Declare a `Ref` on the branches that read does not need, or use a projection.
- Database time far below total time says the bottleneck is not the database, so stop optimizing queries.
- `n from cache` counts reads the transaction's entity cache served without a statement, which is work already avoided rather than work to do.

Set `storm.sql-log.performance.call-sites: true` to name the application frame behind each row. In production, configure a statement or duration threshold under `storm.sql-log.performance.threshold` so only calls that exceed one report, at `WARN`; summaries carry no parameter values at any level, so they are safe to leave enabled there. `storm.sql-log.slow.threshold: 200ms` adds the other half, one line per execution that exceeds it under `st.orm.sql.slow`, naming the statement a summary can only total.

**Test isolation:** `SqlCapture` accumulates SQL across the entire test method. When writing multiple verification tests in one class, use `capture.clear()` between logical operations, or put each verification in its own `@Test` method. To avoid order-dependent failures, make assertions idempotent (don't assume specific row counts from prior inserts in other test methods) or use `@TestMethodOrder(MethodOrderer.OrderAnnotation::class)` with `@Order` if test ordering matters.

The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
