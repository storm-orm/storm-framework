Help the user write Storm queries using Java.

**Important:** Storm can run on top of JPA, but when writing queries, always use Storm's own QueryBuilder and operator-based predicates — not JPQL, `CriteriaBuilder`, or `EntityManager.createQuery()`.

## Key Imports

```java
import st.orm.core.template.QueryBuilder;         // Query builder
import st.orm.Operator;                           // EQUALS, NOT_EQUALS, LIKE, IN, IS_NULL, etc.
import static st.orm.Operator.*;                  // Static import for operator constants
import st.orm.Metamodel;                          // Generated metamodel fields (User_, City_, etc.)
import st.orm.Ref;                                // Lazy-loaded reference
import st.orm.Page;                               // Offset-based pagination result
import st.orm.Pageable;                           // Pagination request
import st.orm.Scrollable;                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window;                             // Keyset scrolling result (Window<R, T>)
```

The `Operator` enum is in `st.orm` and contains: `EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LIKE`, `NOT_LIKE`, `IS_NULL`, `IS_NOT_NULL`, `IS_TRUE`, `IS_FALSE`, `IN`, `NOT_IN`, `BETWEEN`.

Ask what data they need, filters, ordering, or pagination.

## API Design: Builder Methods vs Convenience Methods

Repository/entity methods fall into two categories:

**Builder methods** return `QueryBuilder` for composable, chainable queries. They never execute immediately:
- `select()`, `select(predicate)` -- build SELECT queries
- `selectRef()`, `selectRef(predicate)` -- build SELECT queries returning Refs
- `selectCount()` -- build COUNT queries
- `delete()`, `delete(predicate)` -- build DELETE queries

Terminal operations: `.getResultList()`, `.getSingleResult()`, `.getOptionalResult()`, `.getResultStream()`, `.getResultCount()`, `.page()`, `.scroll()`, `.executeUpdate()`

**Convenience methods** execute immediately and return results directly:
- `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findBy()`, `findAllBy()`, `getById()`, `getByRef()`, `getBy()`, `count()`, `exists()`, `remove()`, `removeById()`, `removeByRef()`, `removeAll()`, `removeAllBy()`, `page()`, `pageRef()`, `scroll()`

The `delete`/`remove` distinction: `remove` operates on entities or ids you already have (immediate execution). `delete` builds a query to find and delete rows by criteria (returns `QueryBuilder`).

Prefer the simplest approach that works. Three query levels, from simplest to most powerful:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`findBy`, `findAllBy`, `removeAllBy`, `countBy`, `existsBy`) | Simple lookups and operations |
| 2 | Builder with predicate (`select(predicate)`, `delete(predicate)`) or chained (`select().where()`) | Most application queries needing ordering, pagination, or joins |
| 3 | SQL Templates (/storm-sql-java) | CTEs, window functions, database-specific features |

### When to use each — and when NOT to

| Need | Use (simplest) | Don't use (unnecessarily complex) |
|------|----------------|-----------------------------------|
| All rows as list | `findAll()` | `select().getResultList()` |
| Filter by single field | `findAllBy(field, value)` | `select().where(field, EQUALS, value).getResultList()` |
| Single by unique key | `findBy(key, value)` | `select().where(key, EQUALS, value).getOptionalResult()` |
| Count by field | `countBy(field, value)` | `selectCount().where(field, EQUALS, value).getSingleResult()` |
| Exists check | `existsBy(field, value)` | `countBy(field, value) > 0` |
| Delete by field | `removeAllBy(field, value)` | `delete().where(field, EQUALS, value).executeUpdate()` |
| Filtered + **ordering/pagination** | `select().where(...).orderBy(...).getResultList()` | convenience methods (can't add ordering) |
| Filtered + **joins** | `select().innerJoin(...).on(...).getResultList()` | convenience methods (can't add joins) |
| Filtered + **streaming** | `select().where(...).getResultStream()` | convenience methods (return List, not Stream) |
| Aggregates, CTEs, window functions | SQL Template (/storm-sql-java) | QueryBuilder (can't express these) |

The rule: **escalate only when the simpler level cannot express what you need.** If you need ordering, you need Level 2. If you need CTEs or window functions, you need Level 3.

**Level 1 — Convenience methods** (execute immediately, no terminal needed):
```java
var users = orm.entity(User.class);
Optional<User> user = users.findBy(User_.email, email);
List<User> list = users.findAllBy(User_.city, city);
long count = users.count();
```

**Level 2 — Builder** (returns `QueryBuilder`, chain terminal + ordering/pagination):
```java
// With predicate shorthand
List<User> list = users.select(it -> it.where(User_.city, EQUALS, city))
    .orderBy(User_.name)
    .getResultList();

// Or equivalently, chained with .where()
List<User> list = users.select()
    .where(User_.city, EQUALS, city)
    .orderBy(User_.name)
    .getResultList();
```

Compound filters:
```java
List<User> result = users.select()
    .where(it -> it.where(User_.city, EQUALS, city)
            .and(it.where(User_.birthDate, LESS_THAN, LocalDate.of(2000, 1, 1))))
    .orderBy(User_.name)
    .getResultList();
```

Entity comparison: `.where(User_.city, EQUALS, city)` compares by FK — pass the entity directly, don't extract the ID. When you only have an ID, use `Ref.of(City.class, cityId)` instead of constructing a full entity with dummy field values.
Nested paths: `User_.city.country.code` with appropriate operator
Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`
Limit/Offset: `.limit(10)`, `.offset(20)`
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`
Scrolling (keyset): `.scroll(Scrollable.of(User_.id, 20))` — do NOT combine with `orderBy()` (Scrollable manages ORDER BY internally, see Keyset Scrolling section)
Explicit joins: `.innerJoin(Entity.class).on(OtherEntity.class)`, `.leftJoin(Entity.class).on(OtherEntity.class)`, `.rightJoin(Entity.class).on(OtherEntity.class)`
Result type: `.select(ResultType.class)` to return a different type than the root entity. **Cross-entity pitfall:** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`). Put the query on the target entity's repository instead.

Operators: EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL, IN, NOT_IN

## Aggregation

```java
long userCount = orm.entity(User.class).selectCount()
    .where(User_.active, EQUALS, true)
    .getSingleResult();

List<CitySummary> citySummaries = orm.entity(City.class)
    .select(CitySummary.class)
    .groupBy(City_.country)
    .having(City_.population, GREATER_THAN, 100000)
    .getResultList();
```

**Computed aggregates (COUNT, AVG, SUM, etc.):** When the SELECT clause needs expressions that QueryBuilder can't produce, use `select(ResultType.class, RAW."template")` for the SELECT only — keep joins, groupBy, having, orderBy, and limit in code.

**Important:** The `RAW."template"` provides the SELECT list only — not a full SQL query. If you put a full `SELECT ... FROM ... WHERE ...` inside, Storm wraps it as a scalar subquery, causing errors. For full custom SQL, use `orm.query(RAW."...").getResultList(T.class)` (see /storm-sql-java).

```java
record CityUserCount(@FK City city, long userCount) implements Data {}

List<CityUserCount> cityCounts = orm.entity(City.class)
    .select(CityUserCount.class, RAW."\{City.class}, COUNT(*)")
    .leftJoin(User.class).on(City.class)
    .groupBy(City_.id)
    .getResultList();

// More complex example with WHERE, HAVING, and ORDER BY — all in code:
record CityUserStats(String cityName, double averageAge, long userCount) implements Data {}

int minUsers = 10;
List<CityUserStats> topCities = orm.entity(City.class)
    .select(CityUserStats.class, RAW."\{City_.name}, AVG(\{User_.age}), COUNT(*)")
    .leftJoin(User.class).on(City.class)
    .groupBy(City_.name)
    .having(RAW."COUNT(*) >= \{minUsers}")               // template form for aggregate expressions
    .orderByDescending(RAW."AVG(\{User_.age})")
    .getResultList();
```

Always prefer code over templates. Templates are for expressions QueryBuilder can't produce (e.g., `COUNT(*)`, `AVG()`). `groupBy`, `having`, and `orderBy` also accept template forms when needed (e.g., `.having(RAW."COUNT(*) >= \{min}")`, `.orderByDescending(RAW."AVG(\{User_.age})")`), but use the code-based methods when possible. Do NOT write the entire query as a raw SQL string.

## Row Locking

```java
User user = orm.entity(User.class).select()
    .where(User_.id, EQUALS, userId)
    .forUpdate()         // SELECT ... FOR UPDATE
    .getSingleResult();

// Or shared lock
    .forShare()          // SELECT ... FOR SHARE
```

## Distinct and Count

```java
List<City> uniqueCities = orm.entity(User.class)
    .select(City.class)
    .distinct()
    .getResultList();

long activeCount = orm.entity(User.class)
    .selectCount()
    .where(User_.active, EQUALS, true)
    .getSingleResult();
```

## Ref-Based Queries

```java
// Query by ref
User user = orm.entity(User.class).select()
    .where(userRef)
    .getSingleResult();

// Query by multiple refs
List<User> users = orm.entity(User.class).select()
    .whereRef(userRefs)
    .getResultList();

// Select refs instead of full entities (lightweight)
List<Ref<User>> refs = orm.entity(User.class).selectRef()
    .where(User_.city, EQUALS, city)
    .getResultList();
```

## Subqueries (EXISTS / NOT EXISTS)

```java
// WHERE EXISTS — filter entities that have related data
List<City> citiesWithUsers = orm.entity(City.class)
    .select()
    .whereExists(it -> it.subquery(User.class))
    .getResultList();

// WHERE NOT EXISTS
List<City> citiesWithoutUsers = orm.entity(City.class)
    .select()
    .whereNotExists(it -> it.subquery(User.class))
    .getResultList();
```

## Compound Predicates (where with WhereBuilder)

For complex WHERE clauses with AND/OR grouping:

```java
List<User> users = orm.entity(User.class)
    .select()
    .where(it -> it.where(User_.active, EQUALS, true)
            .and(it.where(User_.email, IS_NOT_NULL))
            .or(it.where(User_.role, EQUALS, "admin")))
    .getResultList();
```

## Joined-Entity Predicates, Ordering, and Grouping

The `where()`, `orderBy()`, and `groupBy()` methods are typed to the root entity. To filter, order, or group by a joined entity's field, use the `Any` variants: `.whereAny(...)`, `.orderByAny(...)`, `.orderByDescendingAny(...)`, `.groupByAny(...)`. The `Any` variants (`whereAny`, `orderByAny`, `orderByDescendingAny`, `groupByAny`) are needed when referencing fields from joined (non-root) entities.

```java
users.select()
    .innerJoin(UserRole.class).on(User.class)
    .whereAny(UserRole_.role, EQUALS, role)
    .orderByAny(UserRole_.assignedAt)
    .getResultList();
```

## Keyset Scrolling

Keyset scrolling uses cursor-based navigation instead of offset, making it efficient for large tables. **Scrollable manages ORDER BY internally** — do NOT add `orderBy()` when using `scroll(Scrollable)`, or Storm throws `PersistenceException`.

**Composite PK limitation:** Keyset scrolling requires a simple (non-composite) primary key as the scroll key. Entities with composite PKs (e.g., junction tables) cannot be scrolled directly — Storm throws `SqlTemplateException: Column not found for metamodel`. To scroll filtered results from a junction table, query the related entity with a simple PK and JOIN through the junction table for filtering:
```java
// ❌ Cannot scroll a junction table with composite PK
userRoles.scroll(Scrollable.of(UserRole_.id, 20));  // fails — UserRole has composite PK

// ✅ Scroll User (simple PK) with a JOIN through UserRole for filtering
users.select()
    .innerJoin(UserRole.class).on(User.class)
    .whereAny(UserRole_.role, EQUALS, role)
    .scroll(Scrollable.of(User_.id, 20));
```

```java
// WRONG: orderBy conflicts with Scrollable
users.select()
    .where(User_.active, EQUALS, true)
    .orderBy(User_.name)        // ❌ Scrollable manages ordering
    .scroll(Scrollable.of(User_.id, 20));

// CORRECT: ordering is controlled by the Scrollable's key (and optional sort field)
users.select()
    .where(User_.active, EQUALS, true)
    .scroll(Scrollable.of(User_.id, 20));
```

**First request vs subsequent requests:** On the first request there is no cursor, so use `Scrollable.of()`. On subsequent requests, use `Scrollable.fromCursor()` — the cursor string encodes the size, direction, and position:

```java
var scrollable = cursor != null
    ? Scrollable.fromCursor(User_.id, cursor)       // size encoded in cursor
    : Scrollable.of(User_.id, 20);                  // first page, size 20
var window = users.scroll(scrollable);                     // prefer var — avoids Window<User, User> verbosity
String nextCursor = window.nextCursor();             // null if no more results
```

**Custom sort column** (non-unique sort field with key as tiebreaker):
```java
var scrollable = Scrollable.of(User_.id, User_.name, 20);
```

**Backward scrolling and navigation:**
```java
var window = users.scroll(Scrollable.of(User_.id, 20));
if (window.hasNext()) {
    var next = users.scroll(window.nextScrollable());
}
if (window.hasPrevious()) {
    var previous = users.scroll(window.previousScrollable());
}
```

## Bulk DELETE/UPDATE

`delete()` is a builder method that returns `QueryBuilder`. Call `.executeUpdate()` to execute:

```java
// DELETE with WHERE (safe) -- builder returns QueryBuilder, terminal executes
orm.entity(User.class).delete().where(User_.active, EQUALS, false).executeUpdate();

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User.class).delete().unsafe().executeUpdate();

// Convenience method: removeAll() executes immediately (calls unsafe() internally)
users.removeAll();
```

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** SQL templates are an escape hatch for things the QueryBuilder cannot express.

**Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin(T.class).on(RAW."...")`) or a full `orm.query(RAW."...")` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin(Entity.class).on(OtherEntity.class)`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database.

Three rules:

1. **Code-first:** If it can be done with QueryBuilder methods (joins, where, orderBy, groupBy, having), do it in code. Never use a template string for a `WHERE` clause that could be a `.where(field, EQUALS, value)`, or an `ORDER BY` that could be `.orderBy(field)`.
2. **Metamodel in templates:** When you do need a template fragment (e.g., for `COUNT(*)` in a select clause), still use metamodel references inside it (`\{User_.email}`, not `"email"`). This keeps column references type-safe and refactor-proof.
3. **Full SQL last resort:** A full `SELECT ... FROM ...` SQL template should only be used for totally custom queries (CTEs, UNIONs, window functions) that cannot be built at all with the QueryBuilder. Even then, users still benefit from bind variables (`\{value}`) and metamodel references (`\{Entity_.field}`).

When you do use template strings, use `RAW."""..."""` (Java string templates with `--enable-preview`) — never use `TemplateString.raw()`.

Operators: `EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LIKE`, `NOT_LIKE`, `IS_NULL`, `IS_NOT_NULL`, `IS_TRUE`, `IS_FALSE`, `IN`, `NOT_IN`, `BETWEEN`

The `EQUALS` operator accepts both entities and `Ref<T>`. When you have an entity, use it directly — no need to convert to a `Ref` first.

When you only have an ID (e.g., from a URL parameter), create a `Ref` — don't construct a dummy entity with empty fields:
```java
.where(User_.city, EQUALS, Ref.of(City.class, cityId))   // ✅
// ❌ new City(cityId, "", null)
```

## Result Retrieval

QueryBuilder terminals:
- `.getResultList()` → `List<R>`
- `.getSingleResult()` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.getOptionalResult()` → `Optional<R>`
- `.getResultCount()` → `long`
- `.getResultStream()` → `Stream<R>` (lazy, **must** close with try-with-resources)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(scrollable)` → `Window<R, T>` (keyset scrolling — do NOT combine with `orderBy()`, see Keyset Scrolling section). Use `nextScrollable()` / `previousScrollable()` for programmatic navigation, or `nextCursor()` / `previousCursor()` for REST APIs.
- `.executeUpdate()` → `int` (for DELETE/UPDATE)

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value.
- DELETE/UPDATE without WHERE throws. Use `unsafe()`.
- Streaming: `select().getResultStream()` returns a `Stream`. ALWAYS use try-with-resources to avoid connection leaks. There are no `selectBy` methods that return Stream directly -- always use `select()` (optionally with predicate) and then `.getResultStream()`.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key.

## Verification

After writing queries, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the query produces the result the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```java
@StormTest(scripts = {"schema.sql", "data.sql"})
class UserQueryTest {
    @Test
    void findActiveUsersInCity(ORMTemplate orm, SqlCapture capture) {
        City city = orm.entity(City.class).findById(1).orElseThrow();
        List<User> users = capture.execute(() ->
            orm.entity(User.class).select()
                .where(User_.city, EQUALS, city)
                .orderBy(User_.name)
                .getResultList());
        // Verify intent: single query, only active users in the given city, ordered by name.
        assertEquals(1, capture.count(Operation.SELECT));
        assertFalse(users.isEmpty());
        assertTrue(users.stream().allMatch(u -> u.city().equals(city) && u.active()));
    }
}
```

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
