Help the user write a Storm repository using Java.

**Important:** Storm can run on top of JPA, but when generating repository code, always use Storm's own `EntityRepository` API with JDBC `DataSource` — not `EntityManager`, `@PersistenceContext`, or Spring Data JPA repositories.

## Key Imports

```java
import st.orm.core.repository.EntityRepository;  // Repository base interface
import st.orm.core.template.ORMTemplate;          // ORM entry point
import st.orm.core.template.QueryBuilder;         // Query builder
import st.orm.Operator;                           // EQUALS, NOT_EQUALS, IN, etc.
import static st.orm.Operator.*;                  // Static import for operator constants
import st.orm.Ref;                                // Lazy-loaded reference
import st.orm.Page;                               // Offset-based pagination result
import st.orm.Pageable;                           // Pagination request
import st.orm.Scrollable;                         // Keyset scrolling cursor
import st.orm.Window;                             // Keyset scrolling result
import st.orm.test.StormTest;                     // Test annotation
import st.orm.test.SqlCapture;                    // SQL capture for verification
import st.orm.test.CapturedSql.Operation;         // SELECT, INSERT, UPDATE, DELETE
```

Ask: which entity, what custom queries?

Detect the project's framework from its build file (pom.xml or build.gradle): look for `storm-spring-boot-starter` or `spring-boot-starter` (Spring Boot) or neither (standalone). Use the detected framework to suggest the appropriate repository registration pattern.

## Getting a Repository

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
2. Inherited CRUD: insert, insertAndFetch, update, remove, removeById, removeByRef, removeAll, findById, getById, findBy(Key), findAll, findAllRef, count, existsById, page, pageRef, scroll.
3. Descriptive variable names: `var users = orm.entity(User.class)`, not `var repo`.
4. QueryBuilder is IMMUTABLE. Always chain or capture the return value.
5. Streaming: `select().getResultStream()` returns a `Stream`. ALWAYS use try-with-resources to avoid connection leaks.
6. DELETE/UPDATE without WHERE throws. Use `unsafe()` for intentional bulk ops.
7. Pagination: `page(0, 20)` for offset-based. `scroll(scrollable)` for keyset on large tables.
8. **Prefer entity/metamodel-based methods over templates.** Use `.innerJoin(Entity.class).on(OtherEntity.class)` for joins unless it cannot be expressed with entity classes. Only fall back to template lambdas when QueryBuilder cannot express the query.
   **Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin(T.class).on(RAW."...")`) or a full `orm.query(RAW."...")` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin(Entity.class).on(OtherEntity.class)`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database.
9. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key.
10. **Prefer typed parameters over raw IDs.** Repository method signatures should accept entity or `Ref<Entity>` parameters for FK fields — not raw IDs like `String` or `int`. Raw IDs are untyped and lose the entity association. Convert IDs to `Ref` at the system boundary (controller/route handler) using `Ref.of(Entity.class, id)`.

## API Design: Prefer the Simplest Approach

Three levels, from simplest to most powerful — always prefer the simplest that works:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`findBy`, `findAllBy`, `removeAllBy`, `countBy`, `existsBy`) | Simple lookups and operations |
| 2 | Builder with predicate (`select(predicate)`, `delete(predicate)`) or chained (`select().where()`) | Most application queries needing ordering, pagination, or joins |
| 3 | SQL Templates (/storm-sql-java) | CTEs, window functions, database-specific features |

**Level 1 — Convenience methods** execute immediately and return results directly:
- **Read:** `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findAllById()`, `findAllByRef()`, `findBy(key, value)`, `findAllBy(field, value)`, `findRefBy(...)`, `findAllRefBy(...)`
- **Read (throw):** `getById()`, `getByRef()`, `getBy(key, value)`
- **Exists/Count:** `count()`, `exists()`, `existsById()`, `existsByRef()`, `countBy(field, value)`
- **Write:** `insert()`, `insertAndFetch()`, `update()`, `updateAndFetch()`, `upsert()`, `upsertAndFetch()`
- **Remove:** `remove(entity)`, `removeById(id)`, `removeByRef(ref)`, `removeAll()`, `removeAllBy(field, value)`, `remove(Iterable)`, `removeByRef(Iterable)`, `remove(Stream)`, `removeByRef(Stream)`
- **Pagination:** `page()`, `pageRef()`, `scroll()`

**Level 2 — Builder** returns `QueryBuilder` for chaining ordering, pagination, or joins:
```java
// With predicate shorthand
users.select(it -> it.where(User_.city, EQUALS, city))
    .orderBy(User_.name).getResultList();

// Or equivalently, chained with .where()
users.select().where(User_.city, EQUALS, city)
    .orderBy(User_.name).getResultList();
```

Terminal operations: `.getResultList()`, `.getSingleResult()`, `.getOptionalResult()`, `.getResultStream()`, `.getResultCount()`, `.page()`, `.scroll()`, `.executeUpdate()`

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

Query by a specific metamodel key without writing a full QueryBuilder chain:

```java
// Unique key lookups
Optional<User> user = users.findBy(User_.email, "alice@example.com");
User user = users.getBy(User_.email, "alice@example.com");   // throws if not found

// Ref-based key lookups
Optional<User> user = users.findByRef(User_.city, cityRef);
User user = users.getByRef(User_.city, cityRef);
```

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

// Count via Stream
long count = users.countById(idStream);
long count = users.countByRef(refStream, chunkSize);

// Batch insert/update/remove via Stream
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

// Page API:
// page.content()       — List<User> of results for this page
// page.totalPages()    — total number of pages
// page.totalElements() — total number of elements across all pages
// page.number()        — current page number (0-based)
// page.size()          — page size
// page.hasNext()       — whether a next page exists
// page.hasPrevious()   — whether a previous page exists
// page.nextPageable()  — Pageable for the next page

// Ref-based pagination
Page<Ref<User>> refPage = users.pageRef(0, 20);

// Keyset scrolling (better for large tables — no COUNT, cursor-based)
// ⚠️ Scrollable manages ORDER BY internally — do NOT add orderBy() when using scroll(Scrollable)
// ⚠️ Requires a simple (non-composite) PK — junction tables with composite PKs cannot be scrolled.
//    To scroll filtered results from a junction table, query the entity with a simple PK
//    and JOIN through the junction table (e.g., scroll User with a JOIN through UserRole).
var window = users.scroll(Scrollable.of(User_.id, 20));    // prefer var — avoids Window<User, User> verbosity

// With custom sort order (sort column in addition to key)
var window = users.scroll(Scrollable.of(User_.id, User_.name, 20));

// First request vs subsequent: use Scrollable.of() when no cursor exists,
// Scrollable.fromCursor() when resuming (cursor encodes size, direction, position)
var scrollable = cursor != null
    ? Scrollable.fromCursor(User_.id, cursor)
    : Scrollable.of(User_.id, 20);
var window = users.scroll(scrollable);

// Window<R, T> is the scroll result record. Both scroll() methods return Window.
// Window API:
// window.content() — List<User>
// window.hasNext() / window.hasPrevious()
// window.nextCursor() / window.previousCursor() — serialized cursors for REST APIs
// window.nextScrollable() / window.previousScrollable() — for programmatic navigation
```

## Framework-Specific Repository Registration

### Spring Boot
Define a `RepositoryBeanFactoryPostProcessor` with `repositoryBasePackages` to auto-register repos as beans:
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

### Spring Boot
Use `@Transactional` on service methods (standard Spring):
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
Use programmatic transaction blocks:
```java
orm.transactionBlocking(tx -> {
    var user = tx.entity(User.class).insertAndFetch(new User(null, email, "Alice", city));
    // All operations share the same transaction.
});
```

## Verification

After writing repository methods, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the repository method produces the query the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```java
@StormTest(scripts = {"schema.sql", "data.sql"})
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

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

**Test isolation:** `SqlCapture` accumulates SQL across the entire test method. When writing multiple verification tests in one class, use `capture.clear()` between logical operations, or put each verification in its own `@Test` method. To avoid order-dependent failures, make assertions idempotent (don't assume specific row counts from prior inserts in other test methods) or use `@TestMethodOrder(MethodOrderer.OrderAnnotation.class)` with `@Order` if test ordering matters.

The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
