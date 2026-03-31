Help the user write a Storm repository using Kotlin.
**Important:** Storm can run on top of JPA, but when generating repository code, always use Storm's own `EntityRepository` API with JDBC `DataSource` — not `EntityManager`, `@PersistenceContext`, or Spring Data JPA repositories.

## Key Imports

```kotlin
import st.orm.repository.EntityRepository       // Repository base interface
import st.orm.repository.entity                  // Reified: orm.entity<User>()
import st.orm.repository.repository              // Reified: orm.repository<UserRepository>()
import st.orm.repository.insert                  // Infix: orm insert entity
import st.orm.template.*                         // ORMTemplate, QueryBuilder, orm, ref, eq, neq, etc.
import st.orm.Operator.*                         // EQUALS, NOT_EQUALS, IN, etc.
import st.orm.Ref                                // Lazy-loaded reference
import st.orm.Page                               // Offset-based pagination result
import st.orm.Pageable                           // Pagination request
import st.orm.Scrollable                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window                             // Keyset scrolling result (Window<R, T>)
import st.orm.test.StormTest                     // Test annotation
import st.orm.test.SqlCapture                    // SQL capture for verification
import st.orm.test.CapturedSql.Operation         // SELECT, INSERT, UPDATE, DELETE
import org.junit.jupiter.api.Assertions.*        // assertEquals, assertTrue, assertFalse
```

Ask: which entity, what custom queries?

Detect the project's framework from its build file (pom.xml or build.gradle.kts): look for `storm-kotlin-spring-boot-starter` or `spring-boot-starter` (Spring Boot), `storm-ktor` or `ktor-server-core` (Ktor), or neither (standalone). Use the detected framework to suggest the appropriate repository registration pattern below.

## Getting a Repository

```kotlin
// Generic entity access (no custom interface needed)
val users = orm.entity<User>()               // preferred — reified, import st.orm.repository.entity
val users = orm.entity(User::class)          // also works, no import needed

// Custom repository (interface with explicit query method bodies)
val userRepository = orm.repository<UserRepository>()  // import st.orm.repository.repository
```

**Star projection caveat:** `orm.entity<User>()` returns `EntityRepository<User, *>` — the ID type is erased. Methods that depend on the ID type parameter (`existsById`, `findById`, `removeById`, etc.) will fail with star projection errors. For ID-based operations, use a typed custom repository (`EntityRepository<User, Int>`) or call the method via `orm.entity(User::class)` with an explicit cast.

```kotlin
// ⚠️ Repository interfaces MUST import predicate operators — they are Kotlin extension functions:
// import st.orm.template.eq   (and neq, like, greater, less, etc.)
// import st.orm.template.and
// import st.orm.template.or
// Without these imports, `eq`, `and`, `or` etc. will not resolve in the interface file.

interface UserRepository : EntityRepository<User, Int> {
    fun findByEmail(email: String): User? = find(User_.email eq email)
    fun findByCity(city: City): List<User> = findAll(User_.city eq city)
    fun findActiveInCity(city: City): List<User> =
        findAll((User_.city eq city) and (User_.active eq true))
}

// Obtain the repository
val userRepository: UserRepository = orm.repository<UserRepository>()

// Or use the generic entity repository for simple CRUD
val users = orm.entity(User::class)
```

Key rules:
1. ALL query methods have EXPLICIT BODIES. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, update, remove, removeById, removeByRef, removeAll, findById, findBy(Key), count, existsById, page, scroll.
3. Descriptive variable names: `val users = orm.entity(User::class)`, not `val repo`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value (or use the `select { }` DSL which handles this automatically).
5. Streaming: `select().resultFlow` returns a `Flow` with automatic resource cleanup.
6. DELETE/UPDATE without WHERE throws. Use `unsafe()` for intentional bulk ops.
7. Pagination: `page(0, 20)` for offset-based. `scroll(Scrollable.of(User_.id, 20))` for keyset on large tables (see Keyset Scrolling section).
8. **Prefer entity/metamodel-based methods over templates.** For joins, use `innerJoin(Entity::class, OnEntity::class)` in the block DSL, or `.innerJoin(Entity::class).on(OnEntity::class)` in the chained API. Only fall back to template lambdas when QueryBuilder cannot express the query.
   **Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin(T::class).on { "..." }`) or a full `orm.query { }` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin(Entity::class).on(OnEntity::class)`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database.
9. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key. When a projection already returns `Ref<T>`, use it directly without calling `.ref()` again.
10. **Prefer typed parameters over raw IDs.** Repository method signatures should accept entity or `Ref<Entity>` parameters for FK fields — not raw IDs like `String` or `Int`. Raw IDs are untyped and lose the entity association. Convert IDs to `Ref` at the system boundary (controller/route handler) using `refById<T>(id)` (import `st.orm.template.refById`).

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
- **Pagination:** `page()`, `pageRef()`, `scroll()`

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

Terminal operations: `.resultList`, `.singleResult`, `.optionalResult`, `.resultFlow`, `.resultStream`, `.resultCount`, `.page()`, `.scroll()`, `.executeUpdate()`

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

// Repository methods should accept entity or Ref<T>, not raw IDs:
// ✅ fun findByCity(city: City): List<User> = findAll(User_.city eq city)
// ✅ fun findByCity(city: Ref<City>): List<User> = findAll(User_.city eq city)
// ❌ fun findByCity(cityId: Int): List<User> = ...  // untyped, loses entity association

// At the call site (controller/route handler), convert IDs to Ref:
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

## Flow-Based Streaming

Use Kotlin `Flow` for memory-efficient processing of large datasets:

```kotlin
// Stream all entities lazily (builder method + terminal)
val allUsers: Flow<User> = users.select().resultFlow

// Stream with filter (builder method + terminal)
val activeUsers: Flow<User> = users.select(User_.active eq true).resultFlow

// Count via Flow
val count: Long = users.countById(idFlow)
val count: Long = users.countByRef(refFlow, chunkSize = 500)

// Batch insert/update/remove via Flow (suspending)
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
val nextPage = users.page(page.nextPageable)

// Page API — note: these are methods (not properties), call with ()
// page.content()       — List<User> of results for this page
// page.totalPages()    — total number of pages
// page.totalElements() — total number of elements across all pages
// page.number()        — current page number (0-based)
// page.size()          — page size
// page.hasNext()       — whether a next page exists
// page.hasPrevious()   — whether a previous page exists
// page.nextPageable    — Pageable for the next page (property, not method)

// Keyset scrolling (better for large tables — no COUNT, cursor-based)
// Scrollable<T> takes a single type parameter (the entity type)
// ⚠️ Scrollable manages ORDER BY internally — do NOT add orderBy() when using scroll(Scrollable)
// ⚠️ Requires a simple (non-composite) PK — junction tables with composite PKs cannot be scrolled.
//    To scroll filtered results from a junction table, query the entity with a simple PK
//    and JOIN through the junction table (e.g., scroll User with a JOIN through UserRole).
val window = users.scroll(Scrollable.of(User_.id, 20))

// With custom sort order (sort column in addition to key)
val window = users.scroll(Scrollable.of(User_.id, User_.name, 20))

// First request vs subsequent: use Scrollable.of() when no cursor exists,
// Scrollable.fromCursor() when resuming (cursor encodes size, direction, position)
val scrollable = if (cursor != null) {
    Scrollable.fromCursor(User_.id, cursor)
} else {
    Scrollable.of(User_.id, 20)
}
val window = users.scroll(scrollable)

// Window<R, T> is the scroll result record. Both scroll() methods return Window.
// Window API:
// window.content — List<User> of results
// window.hasNext / window.hasPrevious — bounds checking
// window.nextCursor() / window.previousCursor() — serialized cursors for REST APIs
// window.nextScrollable / window.previousScrollable — for programmatic navigation
```

## Framework-Specific Repository Registration

Detect the project's framework from its build file and dependencies, then suggest the appropriate pattern:

### Spring Boot
Define a `RepositoryBeanFactoryPostProcessor` with `repositoryBasePackages` to auto-register repos as beans. Or use the Spring Boot Starter which auto-discovers them.
```kotlin
@Service
class UserService(private val userRepository: UserRepository) {
    fun findUser(email: String) = userRepository.findByEmail(email)
}
```

### Ktor
Register repositories via `stormRepositories { }`, then access them in routes:
```kotlin
fun Application.module() {
    install(Storm)
    stormRepositories {
        register(UserRepository::class)       // register individually
        // or: register("com.myapp.repository") // register all in package
    }
    routing {
        get("/users/{email}") {
            val users = call.repository<UserRepository>()
            call.respond(users.findByEmail(call.parameters.getOrFail("email")))
        }
    }
}
```

### Standalone
Create repositories directly from the `ORMTemplate`:
```kotlin
val userRepository = orm.repository<UserRepository>()
```

## Transactions

### Spring Boot
Use `@Transactional` on service methods (standard Spring):
```kotlin
@Service
class UserService(private val userRepository: UserRepository) {
    @Transactional
    fun createUser(email: String, city: City): User =
        userRepository.insertAndFetch(User(email = email, city = city))
}
```

### Ktor
Use `transaction { }` blocks:
```kotlin
get("/users") {
    call.orm.transaction {
        val users = entity(User::class).findAll()
        call.respond(users)
    }
}
```

### Standalone
Use programmatic `transaction { }` blocks on the ORM template:
```kotlin
orm.transaction {
    val user = entity(User::class).insertAndFetch(User(email = "alice@example.com", city = city))
    // All operations within the block share the same transaction.
}
```

## Block-Based Query DSL

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

**Result types and the block DSL:** There is **no** `select(ResultType::class) { block }` form. The block DSL always returns the root entity type. To select a different result type, use the chained API:
```kotlin
// ❌ Not valid — no block DSL overload for result type
fun findSummaries(): List<UserSummary> = select(UserSummary::class) {
    where(User_.active eq true)
}.resultList

// ✅ Use chained API — note: joins use .innerJoin(A::class).on(B::class), not two-arg form
fun findSummaries(): List<UserSummary> = select(UserSummary::class)
    .where(User_.active eq true)
    .resultList
```

**What `select(ResultType::class)` is for:** It selects a different result type from the query. This works for joined entity types (e.g., `City::class` from a `User` query) and custom SELECT with a template string (`select(Summary::class, template)`). It does **not** work for column subsets of the root entity — `select(UserSummary::class)` where `UserSummary` has a subset of `User` fields will fail with "Cannot find alias for column." For column subsets, use a `Projection<T>` with `ProjectionRepository`.

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
            innerJoin(UserAddress::class, User::class)
            whereAny(UserAddress_.city eq city)
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

Standalone usage on `ORMTemplate`:
```kotlin
val users = orm.select<User> {
    where(User_.name eq "Alice")
    orderBy(User_.email)
    limit(10)
}.resultList
```

## Verification

After writing repository methods, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the repository method produces the query the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
class UserRepositoryTest {
    @Test
    fun findByCity(orm: ORMTemplate, capture: SqlCapture) {
        val userRepository = orm.repository<UserRepository>()
        val city = orm.entity<City>().getById(1)
        val users = capture.execute { userRepository.findByCity(city) }
        // Verify intent: single query, filtered by city, returns expected data.
        assertEquals(1, capture.count(Operation.SELECT))
        assertFalse(users.isEmpty())
        assertTrue(users.all { it.city == city })
    }
}
```

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

**Test isolation:** `SqlCapture` accumulates SQL across the entire test method. When writing multiple verification tests in one class, use `capture.clear()` between logical operations, or put each verification in its own `@Test` method. To avoid order-dependent failures, make assertions idempotent (don't assume specific row counts from prior inserts in other test methods) or use `@TestMethodOrder(MethodOrderer.OrderAnnotation::class)` with `@Order` if test ordering matters.

The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
