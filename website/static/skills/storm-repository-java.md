---
name: storm-repository-java
description: Write Storm repositories in Java, covering EntityRepository, custom repository interfaces, CRUD, batching, and transactions. Use when adding or changing a repository in Java.
---

Help the user write a Storm repository using Java.

**Important:** Storm can run on top of JPA, but when generating repository code, always use Storm's own `EntityRepository` API with JDBC `DataSource` — not `EntityManager`, `@PersistenceContext`, or Spring Data JPA repositories.

## Key Imports

```java
import st.orm.repository.EntityRepository;        // Repository base interface
import st.orm.template.ORMTemplate;               // ORM entry point
import st.orm.template.QueryBuilder;              // Query builder
import st.orm.Operator;                           // EQUALS, NOT_EQUALS, IN, etc.
import static st.orm.Operator.*;                  // Static import for operator constants
import st.orm.Ref;                                // Lazy-loaded reference
import st.orm.Page;                               // Offset-based pagination result
import st.orm.Pageable;                           // Pagination request
import st.orm.Scrollable;                         // Keyset scrolling cursor
import st.orm.Window;                             // Keyset scrolling result
import st.orm.test.StormTest;                     // Test annotation
import st.orm.test.SqlCapture;                    // SQL capture for verification
import st.orm.test.CapturedSql.Operation;         // SELECT, INSERT, UPDATE, DELETE, UNDEFINED
```

Do NOT import from `st.orm.core.*` — those are Storm's internal core-engine packages. The Java API lives in `st.orm.repository` and `st.orm.template`; shared types (`Operator`, `Ref`, `Page`, `Metamodel`, ...) live in `st.orm`. `Operator` is an interface with static constants (not an enum) — the static import works as shown.

Ask: which entity, what custom queries?

**Result types:** Custom query result types (aggregation DTOs, computed shapes) are plain records — they do NOT implement `Data`, which is reserved for table-backed types. Define them in or beside the repository whose queries return them (not in the entity package), and document each one as a query result shape:

```java
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain record — deliberately not a Data type.
 */
record CityUserCount(City city, long userCount) {}
```

Detect the project's framework from its build file (pom.xml or build.gradle): look for `storm-spring-boot-starter` or `spring-boot-starter` (Spring Boot) or neither (standalone). Use the detected framework to suggest the appropriate repository registration pattern.

**DI preference:** In Spring Boot projects, always prefer constructor-injected repositories over `orm.entity(T.class)` or `orm.repository(T.class)` lookups. Repository lookup via `orm` is for standalone (non-DI) use and tests only. In DI environments, repositories are beans — inject them.

**Layering rule:** Follow the codebase's existing convention first — if handlers already use repositories directly, or a service layer is consistently in place, match that style rather than introduce a competing one. Absent a clear stance (new code, greenfield), promote the layered architecture: controller → service → repository, where controllers never inject repositories — all data access flows through services, which own the transaction boundaries (e.g. `@Transactional` on service methods) and return view-model types. Whatever the stance, do not mix styles: layer-skipping controllers undermine the service layer's cross-cutting concerns (transactions, caching, authorization).

## Getting a Repository

### Spring Boot (preferred in DI environments)

Inject repositories via constructor injection. The Spring Boot Starter (or a `RepositoryBeanFactoryPostProcessor`) auto-registers repository interfaces as beans:

```java
@Service
public class UserService {
    private final UserRepository userRepository;
    public UserService(UserRepository userRepository) { this.userRepository = userRepository; }
    public Optional<User> findUser(String email) { return userRepository.findByEmail(email); }
}

// For generic entity access without a custom repository, inject EntityRepository directly:
@Service
public class CityService {
    private final EntityRepository<City, Integer> cities;
    public CityService(EntityRepository<City, Integer> cities) { this.cities = cities; }
}
```

### Standalone / Tests

Create repositories directly from the `ORMTemplate` (no DI container):

```java
// Generic entity access (no custom interface needed)
var users = orm.entity(User.class);  // EntityRepository<User, Integer>

// Custom repository (interface with explicit default method bodies)
var userRepository = orm.repository(UserRepository.class);
```

```java
interface UserRepository extends EntityRepository<User, Integer> {
    default Optional<User> findByEmail(String email) {
        return select().where(User_.email, EQUALS, email).getOptionalResult();
    }
    default List<User> findByCity(City city) {
        return select().where(User_.city, EQUALS, city).getResultList();
    }
}
```

Key rules:
1. ALL query methods have EXPLICIT BODIES with `default` keyword. Storm does NOT derive queries from method names.
2. Inherited CRUD: insert, insertAndFetch, update, remove, removeById, removeByRef, removeAll, findById, getById, findBy(Key), findAll, findAllRef, count, existsById, page, pageRef, scroll, windows.
3. Descriptive variable names: `var users = orm.entity(User.class)`, not `var repo`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value.
5. Streaming: `select().getResultStream()` returns a `Stream`. ALWAYS use try-with-resources to avoid connection leaks. While rows remain unread it is one statement and its connection is consume-only (a query, `Ref.fetch()` or write from the loop inside a transaction throws, on every database). A loop that reads or writes per row uses `windows(size)` (`Stream<Window<E>>`): one closed statement per window, nothing to close, write per window with `update(window.content().stream().map(...).toList())`.
6. DELETE/UPDATE without WHERE throws. Use `unsafe()` for intentional bulk ops.
7. Pagination: `page(0, 20)` for offset-based. `scroll(scrollable)` for keyset on large tables.
8. **Prefer entity/metamodel-based methods over templates.** Use `.innerJoin(Entity.class).on(OtherEntity.class)` for joins unless it cannot be expressed with entity classes. Only fall back to template lambdas when QueryBuilder cannot express the query.
   **Auto-join types follow FK nullability.** A `@FK` record component is non-null by default, so its auto-join is an INNER JOIN. Mark the component `@Nullable` (JSpecify `org.jspecify.annotations.Nullable` or `jakarta.annotation.Nullable`) when the FK column allows NULL; that produces a LEFT JOIN. If generated SQL shows INNER JOIN where you expect LEFT JOIN, the FK component is missing `@Nullable` in the entity.
   **Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin(T.class).on(RAW."...")`) or a full `orm.query(RAW."...")` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin(Entity.class).on(OtherEntity.class)`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database. Projections join like entities: `.on(ProjectionType.class)` resolves the foreign key by matching the referenced entity's table against the projection's table. When multiple foreign keys reference that table the join is ambiguous — Storm fails with an error naming the candidate fields; disambiguate with a template ON clause.
9. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key.
10. **Prefer typed parameters over raw IDs — full entities by default.** Repository method signatures take the full entity for FK parameters when callers naturally hold one (the common case): predicates accept entities directly, so no ref conversion is needed at the call sites. `Ref<Entity>` parameters remain fine — use them for identity-only flows, where callers hold refs (e.g. from `Ref<T>` fields) or only an id, converted at the system boundary using `Ref.of(Entity.class, id)`. Never accept raw IDs like `String` or `int` — they are untyped and lose the entity association.
11. **Typed ID from `Ref`:** Use `Ref.entityId(ref)` to extract a type-safe ID. For projections, use `Ref.projectionId(ref)`. Avoid `ref.id()` — it returns `Object` and requires an unsafe cast.

## API Design: Prefer the Simplest Approach

Three levels, from simplest to most powerful — always prefer the simplest that works:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`findBy`, `findAllBy`, `removeAllBy`, `countBy`, `existsBy`) | Simple lookups and operations |
| 2 | Builder chained (`select().where(...)`, `delete().where(...)`) | Most application queries needing ordering, pagination, or joins |
| 3 | SQL Templates (/storm-sql-java) | CTEs, window functions, database-specific features |

Unlike Kotlin, Java has no `select(predicate)` / `delete(predicate)` shorthand and no block DSL — always chain `.where(...)` on the builder.

**Level 1 — Convenience methods** execute immediately and return results directly:
- **Read:** `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findAllById()`, `findAllByRef()`, `findBy(key, value)`, `findAllBy(field, value)`, `findRefBy(...)`, `findAllRefBy(...)`
- **Read (throw):** `getById()`, `getByRef()`, `getBy(key, value)`
- **Exists/Count:** `count()`, `exists()`, `existsById()`, `existsByRef()`, `countBy(field, value)`
- **Write:** `insert()`, `insertAndFetch()`, `update()`, `updateAndFetch()`, `upsert()`, `upsertAndFetch()`
- **Remove:** `remove(entity)`, `removeById(id)`, `removeByRef(ref)`, `removeAll()`, `removeAllBy(field, value)`, `remove(Iterable)`, `removeByRef(Iterable)`, `remove(Stream)`, `removeByRef(Stream)`
- **Pagination:** `page()`, `pageRef()`, `scroll()`, `windows()`

**Level 2 — Builder** returns `QueryBuilder` for chaining ordering, pagination, or joins:
```java
users.select().where(User_.city, EQUALS, city)
    .orderBy(User_.name).getResultList();
```

Terminal operations: `.getResultList()`, `.getSingleResult()`, `.getOptionalResult()`, `.getResultStream()`, `.windows(size)`, `.getResultCount()`, `.page()`, `.scroll()`, `.executeUpdate()`

The `find`/`get` distinction: `find` returns `Optional` (no result = empty), `get` throws `NoResultException`.

The `delete`/`remove` distinction: `remove` operates on entities or ids you already have (immediate execution). `delete` builds a query to find and delete rows by criteria (returns `QueryBuilder`):
```java
// remove — you have the entity/id, execute immediately
users.remove(user);
users.removeById(42);
users.removeAll();

// delete — build a query with filtering
users.delete().where(User_.active, EQUALS, false).executeUpdate();
```

> ⚠️ There is **no** `delete(entity)` or `delete(id)` overload (unlike JPA / Spring Data `CrudRepository`). `delete()` returns a `QueryBuilder`, so it takes no entity argument. To delete an entity or id you already hold, use `remove(entity)` / `removeById(id)` / `removeByRef(ref)`.

## CRUD Operations

```java
// Insert
users.insert(new User(null, "alice@example.com", "Alice", city));

// Insert with fetch (returns entity with generated PK and DB defaults)
User user = users.insertAndFetch(new User(null, "alice@example.com", "Alice", city));
int id = users.insertAndFetchId(new User(null, "alice@example.com", "Alice", city));

// Read
Optional<User> found = users.findById(user.id());        // nullable via Optional
User fetched = users.getById(user.id());                  // throws NoResultException
Optional<User> found = users.findByRef(userRef);          // by Ref
User fetched = users.getByRef(userRef);                   // throws if not found

// Update
users.update(new User(user.id(), user.email(), "Alice Johnson", user.city()));
User updated = users.updateAndFetch(new User(user.id(), user.email(), "Alice Johnson", user.city()));

// Upsert (insert or update)
users.upsert(new User(1, "alice@example.com", "Alice", city));
User upserted = users.upsertAndFetch(new User(1, "alice@example.com", "Alice", city));
int id = users.upsertAndFetchId(new User(1, "alice@example.com", "Alice", city));

// Remove
users.remove(user);
users.removeById(user.id());
users.removeByRef(userRef);
users.removeAll();
```

Java records are immutable. For convenient copy-with-modification, consider Lombok `@Builder(toBuilder = true)` or define a `with` method.

## Field-Based Lookups

Query by a specific metamodel field without writing a full QueryBuilder chain:

```java
// Find by field value
Optional<User> user = users.findBy(User_.email, "alice@example.com");
User user = users.getBy(User_.email, "alice@example.com");   // throws if not found

// Find all by field value — pass the entity or a Ref for FK fields
List<User> cityUsers = users.findAllBy(User_.city, city);
List<User> byRef = users.findAllBy(User_.city, Ref.of(City.class, cityId));
List<User> byNames = users.findAllBy(User_.name, List.of("Alice", "Bob"));

// Count / Exists by field
long count = users.countBy(User_.city, Ref.of(city));
boolean exists = users.existsBy(User_.email, "alice@example.com");

// Remove by field
int deleted = users.removeAllBy(User_.city, Ref.of(city));
```

Field-based methods accept a `Ref<V>` value for FK fields. Unique-key fields (`@PK`/`@UK`) additionally have `Metamodel.Key`-typed overloads (`findBy`, `getBy`, `findByRef`, `getByRef`).

## Ref-Based Operations

```java
// Create a Ref from a type and ID (no entity or repository needed)
Ref<City> ref = Ref.of(City.class, cityId);

// Create a Ref from an entity (attached — can fetch from DB)
Ref<User> ref = users.ref(user);
Ref<User> ref = users.ref(userId);     // from ID only, via repository

// Unload an entity to a lightweight Ref (discards entity data, keeps PK)
Ref<User> ref = users.unload(user);

// Lookup by Ref
Optional<User> found = users.findByRef(ref);
User fetched = users.getByRef(ref);
users.removeByRef(ref);

// Batch Ref operations
users.removeByRef(List.of(ref1, ref2, ref3));
List<User> entities = users.findAllByRef(List.of(ref1, ref2));
```

**Document what a query resolves.** When a repository query names references with `fetch(...)`, say so in its doc: name the references it resolves, then that `getOrThrow()` returns them without querying. Callers cannot see the plan from the signature, so without it they fall back to `fetch()`, which quietly reverts the query to one statement per row.

```java
/** Users in a country. The city is resolved, so {@code getOrThrow()} returns it without querying. */
default List<User> findByCountry(Country country) {
    return select().fetch(User_.city).where(User_.city.country, EQUALS, country).getResultList();
}
```

At a call site, note it only where the repeated read is not obvious from the code, trailing the query:

```java
List<User> users = userRepository.findByCountry(country);   // city resolved
users.forEach(user -> render(user.city().getOrThrow()));
```

**The convenience reads resolve nothing.** `findById`, `findAllById`, `getById`, `findAll`, and the predicate and `Ref` lookups carry no fetch plan, so every `Ref` on the rows they return comes back unloaded and `getOrThrow()` on one of them throws. A read whose caller reads a reference needs a query that names it.



## Batch Operations

```java
// Batch insert/update/remove with iterables
users.insert(List.of(user1, user2, user3));
users.update(List.of(user1, user2));
users.remove(List.of(user1, user2));

// With fetch (returns inserted/updated entities with generated values)
List<User> inserted = users.insertAndFetch(List.of(user1, user2));
List<User> updated = users.updateAndFetch(List.of(user1, user2));
List<Integer> ids = users.insertAndFetchIds(List.of(user1, user2));

// Upsert batch
users.upsert(List.of(user1, user2));
List<User> upserted = users.upsertAndFetch(List.of(user1, user2));
List<Integer> ids = users.upsertAndFetchIds(List.of(user1, user2));

// Batch by IDs/Refs
List<User> found = users.findAllById(List.of(1, 2, 3));
List<User> found = users.findAllByRef(List.of(ref1, ref2));
```

## Dirty Checking and Update Suppression

Inside a transaction, Storm observes entity state as it reads and compares against that observed
state when `update()` is called. The observed state lives in the transaction context, never on the
entity, and is discarded at commit. For an entity read in the same transaction:

- **Nothing changed → no SQL at all.** `users.update(user)` with an unmodified instance executes no
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
(/storm-entity-java) or `storm.update.default_mode` globally:

- `ENTITY` (default): any change writes the full row; no change writes nothing.
- `FIELD`: only the changed columns are written (plus the `@Version` column when present). Narrower
  writes, but every distinct combination of changed columns is its own SQL shape: batches split per
  shape, and after `storm.update.max_shapes` distinct shapes (default 5) Storm falls back to
  full-row updates to preserve batching.
- `OFF`: no comparison; always write all columns. Predictable unconditional writes for batch/ETL
  paths.

How a field is compared is a separate axis — `@DynamicUpdate(dirtyCheck = ...)` per entity or
`storm.update.dirty_check` globally. `INSTANCE` (default) marks a field dirty when its reference
changed; rebuilding a record (Lombok `toBuilder()` or a constructor call) passes the untouched
components through by reference, so unchanged fields compare clean at pointer cost. `VALUE`
compares with `equals()` and differs only when code rebuilds equal values in new instances, e.g.
mapping the same data back from a form or DTO.

**Foreign keys compare by id, not by content.** The dirty check follows the column. Under `VALUE`,
an `@FK` field compares the referenced entity's primary key only (the generated metamodel compares
`city().id()` on both sides): a referenced `City` whose own fields changed does not make the
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

```java
var owner = new Owner("Alice", "Bond", address);           // unsaved
var wolfie = new Pet("Wolfie", date, dog, owner);          // same owner instance
var rex = new Pet("Rex", date, dog, owner);
var visit = new Visit(today, "Check-up", wolfie);

orm.writeSet().insert(List.of(wolfie, rex, visit));        // owner discovered and inserted first

// Typed single-root variant: whole graph in, keyed root out
Visit fetched = orm.writeSet().insertAndFetch(visit);

// Keys only, in input order, no re-read: the middle tier between insert and insertAndFetch
List<Long> ids = orm.writeSet().insertAndFetchIds(List.of(visit));

// update/remove write only the entities passed, no discovery; children are removed before parents
orm.writeSet().update(List.of(changedOwner, changedPet));
orm.writeSet().remove(List.of(visit, pet, owner));
```

An entity is unsaved when its primary key is the default value on an auto-generated key. Wrap
write sets in a transaction when atomicity across the calls is required. Also available on
repositories: `users.writeSet()` (delegates to the template; not scoped to the repository's type).

## Stream-Based Operations

Use Java `Stream` for memory-efficient processing of large datasets. **ALWAYS use try-with-resources** to avoid connection leaks:

```java
// Stream all entities lazily (builder method + terminal)
try (Stream<User> stream = users.select().getResultStream()) {
    stream.forEach(System.out::println);
}

// Stream with filter (builder method + terminal)
try (Stream<User> stream = users.select()
        .where(User_.active, EQUALS, true)
        .getResultStream()) {
    stream.forEach(System.out::println);
}

// A stream with rows still unread is one statement: its connection is consume-only, so inside a transaction a query,
// Ref.fetch() or write from the loop throws. Loops that need the database use windows: keyset windows
// over the primary key, one closed statement per window, connection free in between, nothing to close.
users.select().where(User_.active, EQUALS, true).windows(1000).forEach(window ->
    users.update(window.content().stream().map(user -> /* copy with changes */ user).toList()));

// Count via Stream
long count = users.countById(idStream);
long count = users.countByRef(refStream, chunkSize);

// Batch insert/update/remove via Stream. Feed an in-memory stream; a getResultStream() of the same
// transaction is refused as soon as a batch executes while that stream still has rows unread.
users.insert(userStream);
users.insert(userStream, batchSize);
users.update(userStream);
users.update(userStream, batchSize);
users.remove(userStream);
users.remove(userStream, batchSize);
users.upsert(userStream);
users.upsert(userStream, batchSize);
users.removeByRef(refStream);
users.removeByRef(refStream, batchSize);
```

Stream operations are lazy — entities are retrieved/processed as consumed. Use `batchSize`/`chunkSize` to control how many items are sent to the database per batch.

## Streams and the Connection

A `getResultStream()` is one open statement. While it still has unread rows, the connection it reads from is consume-only, on every database. Inside a transaction every statement shares the transaction's connection, so these all throw `PersistenceException` from the loop:

```java
transaction(tx -> {
    try (var stream = users.select().getResultStream()) {
        stream.forEach(user -> {
            users.update(...);        // ❌ write while the stream has rows left
            user.city().fetch();      // ❌ Ref.fetch() is a statement too
            cities.count();           // ❌ any query
        });
    }
    users.update(users.select().getResultStream());   // ❌ batched write fed by a stream of the same transaction
    return null;
});
```

The last line is the trap that passes small tests: a batch that executes after the stream has been read to its end is allowed, and a batch that executes while rows remain is refused, so the outcome depends on batch size versus row count. Never feed a `getResultStream()` of the current transaction into `insert`, `update`, `upsert`, `remove`, `removeByRef`, `insertAndFetch` or `countById`. Feed them an in-memory stream, or iterate in windows.

`windows(size)` is the shape for a loop that needs the database. Each window is fetched by one statement that has closed before the window is handed over, there is nothing to close, and one batched write per window costs one statement rather than one per row:

```java
transaction(tx -> {
    users.select().where(User_.active, EQUALS, true).windows(1000).forEach(window ->
        users.update(window.content().stream().map(user -> /* copy with changes */ user).toList()));
    return null;
});

// Resume after a restart from a stored cursor:
users.windows(Scrollable.fromCursor(User_.id, storedCursor)).forEach(window -> {
    process(window.content());
    store(window.nextCursor());
});
```

Rules for `windows`: the key is the primary key (or the `Scrollable`'s key), which must be a non-null single column; no `orderBy()` on the query; the result type must be the entity (`selectRef()` and custom select types are refused). Each window is its own statement and sees the committed state at that moment.

What stays fine with `getResultStream()`: consuming it (`forEach`, `toList()`, `count()`, `map`, `filter`), stopping early (`findFirst()`, `limit(n)`, then closing it), and, once it has been read to its end, any statement. A `Ref` the loop needs is loaded by naming it in the fetch plan (`select().fetch(...)`) instead of calling `fetch()` per row. Outside a transaction a stream holds a pooled connection of its own until it is closed.

## Count, Exists, Remove

```java
long count = users.count();
boolean exists = users.exists();
boolean exists = users.existsById(userId);
boolean exists = users.existsByRef(userRef);
users.removeById(userId);
users.removeByRef(userRef);
users.removeAll();
```

## Pagination and Scrolling

```java
// Offset-based pagination (executes count + select)
Page<User> page = users.page(0, 20);
Page<User> page = users.page(Pageable.ofSize(20).sortBy(User_.name));
Page<User> next = users.page(page.nextPageable());

// Page API (record accessors):
// page.content()       — List<User> of results for this page
// page.totalPages()    — total number of pages
// page.totalCount()    — total number of elements across all pages
// page.pageNumber()    — current page number (0-based)
// page.pageSize()      — page size
// page.hasNext()       — whether a next page exists
// page.hasPrevious()   — whether a previous page exists
// page.nextPageable()  — Pageable for the next page

// Ref-based pagination
Page<Ref<User>> refPage = users.pageRef(0, 20);

// Keyset scrolling (better for large tables — no COUNT, cursor-based)
// ⚠️ Scrollable manages ORDER BY internally — do NOT add orderBy() when using scroll(Scrollable)
// ⚠️ The scroll key must be a single-column, non-nullable unique key (Metamodel.Key, e.g. a simple
//    @PK or @UK field) — junction tables with composite PKs cannot be scrolled directly.
//    To scroll filtered results from a junction table, query the entity with a simple PK
//    and JOIN through the junction table (e.g., scroll User with a JOIN through UserRole).
var window = users.scroll(Scrollable.of(User_.id, 20));    // prefer var — avoids Window<User> verbosity

// With custom sort order (sort column in addition to key)
var window = users.scroll(Scrollable.of(User_.id, User_.name, 20));

// First request vs subsequent: use Scrollable.of() when no cursor exists,
// Scrollable.fromCursor() when resuming. The cursor is opaque and exists for
// client-server communication: it contains exactly what the client needs to
// navigate the scroll window (key position, size, direction) — clients echo
// it back unchanged, never parse or construct it. Server-side code never
// needs the cursor: window.next()/previous() return a ready-to-use typed
// Scrollable<T> — the cursor is merely its serialized form.
var scrollable = cursor != null
    ? Scrollable.fromCursor(User_.id, cursor)
    : Scrollable.of(User_.id, 20);
var window = users.scroll(scrollable);

// Window<R> is the scroll result record. Both scroll() methods return Window.
// Window API:
// window.content() — List<User>
// window.hasNext() / window.hasPrevious()
// window.nextCursor() / window.previousCursor() — opaque cursors for REST APIs (see above)
// window.next() / window.previous() — typed Scrollable<T> for programmatic navigation
// window.nextScrollable() / window.previousScrollable() — raw Scrollable<?> record component accessors (use next()/previous() instead)
```

## Framework-Specific Repository Registration

### Spring Boot
With `storm-spring-boot-starter`, repository interfaces are auto-discovered and registered as beans — no configuration needed; just inject them. Only when using plain `storm-spring` (no starter) do you switch scanning on with `@EnableStormRepositories(basePackages = ...)`, or define `RepositoryBeanFactoryPostProcessor(basePackages, ormTemplateBeanName, repositoryPrefix)` beans when multiple repository sets bind to different templates:
```java
@Service
public class UserService {
    private final UserRepository userRepository;
    public UserService(UserRepository userRepository) { this.userRepository = userRepository; }
}
```

### Standalone
Create repositories directly from the `ORMTemplate`:
```java
UserRepository userRepository = orm.repository(UserRepository.class);
```

## Transactions

Since 1.13, the Java API has a full programmatic transaction API: `Transactions.transaction(...)` with the same semantics as Kotlin's `transaction { }` blocks — all seven propagation modes, isolation, timeout, read-only, rollback-only, and commit/rollback callbacks. The block is value-returning (void blocks `return null`), checked exceptions propagate to the caller unchanged, and the transaction binds to the first `ORMTemplate` that executes inside it:
```java
import static st.orm.template.Transactions.transaction;
import static st.orm.TransactionPropagation.REQUIRES_NEW;

User created = transaction(tx -> userRepository.insertAndFetch(new User(null, email, "Alice", city)));

transaction(REQUIRES_NEW, tx -> {
    tx.onCommit(() -> log.info("audit committed"));
    return auditRepository.insertAndFetch(entry);
});
```

### Spring Boot
Both styles work and cooperate. `@Transactional` on service methods remains first-class, and Storm's `transaction(...)` blocks run through Spring's transaction managers when the template is Spring-composed (the starter does this automatically): a Storm block inside a `@Transactional` method joins it.
```java
@Service
public class UserService {
    @Transactional
    public User createUser(String email, City city) {
        return userRepository.insertAndFetch(new User(null, email, "Alice", city));
    }
}
```

### Standalone
Without Spring, `Transactions.transaction(...)` manages real JDBC transactions directly on the template's `DataSource` — no framework transaction manager is involved, and the blocking API is virtual-thread friendly.

## Verification

After writing repository methods, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the repository method produces the query the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```java
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Without it, paths resolve relative to the test class's package.
@StormTest(scripts = {"/schema.sql", "/data.sql"})
class UserRepositoryTest {
    @Test
    void findByCity(ORMTemplate orm, SqlCapture capture) {
        var userRepository = orm.repository(UserRepository.class);
        City city = orm.entity(City.class).findById(1).orElseThrow();
        List<User> users = capture.execute(() -> userRepository.findByCity(city));
        // Verify intent: single query, filtered by city, returns expected data.
        assertEquals(1, capture.count(Operation.SELECT));
        assertFalse(users.isEmpty());
        assertTrue(users.stream().allMatch(u -> u.city().equals(city)));
    }
}
```

Keep the verification loop on H2: it answers in milliseconds and needs no Docker, which is what makes verify-and-fix iterations cheap. Escalate to the target database only when H2 cannot run the SQL involved (dialect-specific functions, JSON operators, upsert or sequence syntax the target database defines differently), when the user asks for it, or as a final pass before finishing a larger piece of work: adding `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`, from `st.orm.test.TestDatabase`) to `@StormTest` runs the same test in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. Nothing else in the test changes; the class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

**SQL visibility outside tests:** raise the `st.orm.sql` logger to `DEBUG` to log every executed statement at runtime, or to `TRACE` to render parameter values into it — useful for debugging without a test harness.

**Finding what a call costs:** statement logging answers what ran; the SQL log summary answers what a unit of work cost. Raise `st.orm.sql.perf` to `INFO` and each web controller request, scheduled task or listener invocation reports one line: how many statements it took, the summed database time against how long the call took, and a row per distinct statement ranked by total time. Read it for the usual wins:

- A row with a high execution multiplier (`7x`) is one statement issued once per record. Storm hides no query, so the repetition is a loop in application code: batch it with `findAllById`, an `IN` predicate, or `resultGroupedBy` instead of a query per parent.
- A row marked `fetch` is a reference resolved on demand. Naming it in the query's fetch plan (`select().fetch(path)`) folds the load into the parent statement, and `getOrThrow()` then reads it without querying.
- A read that joins many tables and maps many columns for a call that uses few of them says the type materializes more graph than the read needs. Declare a `Ref` on the branches that read does not need, or use a projection.
- Database time far below total time says the bottleneck is not the database, so stop optimizing queries.
- `n from cache` counts reads the transaction's entity cache served without a statement, which is work already avoided rather than work to do.

Set `storm.sql-log.performance.call-sites: true` to name the application frame behind each row. In production, configure a statement or duration threshold under `storm.sql-log.performance.threshold` so only calls that exceed one report, at `WARN`; summaries carry no parameter values at any level, so they are safe to leave enabled there. `storm.sql-log.slow.threshold: 200ms` adds the other half, one line per execution that exceeds it under `st.orm.sql.slow`, naming the statement a summary can only total.

**Test isolation:** `SqlCapture` accumulates SQL across the entire test method. When writing multiple verification tests in one class, use `capture.clear()` between logical operations, or put each verification in its own `@Test` method. To avoid order-dependent failures, make assertions idempotent (don't assume specific row counts from prior inserts in other test methods) or use `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` with `@Order` if test ordering matters.

The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
