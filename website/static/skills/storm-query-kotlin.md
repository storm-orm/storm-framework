Help the user write Storm queries using Kotlin.
**Important:** Storm can run on top of JPA, but when writing queries, always use Storm's own QueryBuilder and infix predicate operators — not JPQL, `CriteriaBuilder`, or `EntityManager.createQuery()`.

## Key Imports

```kotlin
import st.orm.template.*                         // QueryBuilder, eq, neq, like, ref, orm, etc.
import st.orm.Operator.*                         // EQUALS, NOT_EQUALS, LIKE, IN, IS_NULL, etc.
import st.orm.Ref                                // Lazy-loaded reference
import st.orm.Page                               // Offset-based pagination result
import st.orm.Pageable                           // Pagination request
import st.orm.Scrollable                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window                             // Keyset scrolling result (Window<R>)
import org.junit.jupiter.api.Assertions.*        // assertEquals, assertTrue, assertFalse
```

Use `import st.orm.template.*` to get all infix operators and the `ref()` / `orm` extensions in one import. The `select { }` / `delete { }` block DSL and `and` / `or` combinators are member functions — no import needed.

All infix predicate operators (`eq`, `neq`, `like`, `greater`, `less`, `inList`, `isNull`, `isNotNull`, `isTrue`, `isFalse`, `between`, etc.) are extension functions on `Metamodel<T, V>` defined in `st.orm.template` (in QueryBuilder.kt).

Ask what data they need, filters, ordering, or pagination.

## Kotlin Infix Predicate Operators

All operators are extension functions on `Metamodel<T, V>` (generated metamodel fields like `User_.name`):

```kotlin
User_.name eq "Alice"              // EQUALS
User_.name neq "Bob"               // NOT_EQUALS
User_.age greater 18               // GREATER_THAN
User_.age greaterEq 21             // GREATER_THAN_OR_EQUAL
User_.age less 65                  // LESS_THAN
User_.age lessEq 30               // LESS_THAN_OR_EQUAL
User_.name like "%alice%"          // LIKE
User_.name notLike "%test%"        // NOT_LIKE
User_.roles inList listOf("a","b") // IN
User_.roles notInList listOf("x")  // NOT_IN
User_.age.between(18, 65)          // BETWEEN
User_.active.isTrue()              // IS_TRUE
User_.archived.isFalse()           // IS_FALSE
User_.email.isNull()               // IS_NULL
User_.email.isNotNull()            // IS_NOT_NULL
```

Combine with `and`/`or`:
```kotlin
(User_.active eq true) and (User_.email isNotNull())
(User_.role eq "admin") or (User_.role eq "superadmin")
```

The `eq` operator accepts both entities and `Ref<T>`. When you have an entity, use it directly — no need to extract the ID or convert to a `Ref`:
```kotlin
User_.city eq city          // ✅ entity directly — compares by FK
User_.city eq city.ref()    // also works, but unnecessary when you have the entity
// Same pattern works for any FK field — always pass the entity or Ref, not the raw ID

// When you only have an ID (e.g., from a URL parameter), create a Ref:
User_.city eq refById<City>(cityId)   // ✅ import st.orm.template.refById
// ❌ Don't construct a dummy entity: City(id = cityId, name = "", ...)
```

## API Design: Builder Methods vs Convenience Methods

Repository/entity methods fall into two categories:

**Builder methods** return `QueryBuilder` for composable, chainable queries. They never execute immediately:
- `select()`, `select(predicate)`, `select { }` -- build SELECT queries
- `selectRef()`, `selectRef(predicate)` -- build SELECT queries returning Refs
- `selectCount()` -- build COUNT queries
- `delete()`, `delete(predicate)`, `delete { }` -- build DELETE queries

Terminal operations: `.resultList`, `.singleResult`, `.optionalResult`, `.resultFlow`, `.resultStream`, `.resultCount`, `.page()`, `.scroll()`, `.executeUpdate()`

**Convenience methods** execute immediately and return results directly:
- `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findBy()`, `findAllBy()`, `getById()`, `getByRef()`, `getBy()`, `count()`, `exists()`, `remove()`, `removeById()`, `removeByRef()`, `removeAll()`, `removeAll(predicate)`, `removeAllBy()`, `page()`, `pageRef()`, `scroll()`

The `delete`/`remove` distinction: `remove` operates on entities or ids you already have (immediate execution). `delete` builds a query to find and delete rows by criteria (returns `QueryBuilder`).

Prefer the simplest approach that works. Four query levels, from simplest to most powerful:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`find`, `findAll`, `removeAll`, `count`, `exists`) | Simple lookups and operations |
| 2 | Builder with predicate (`select(predicate)`, `delete(predicate)`) | Filtered queries needing ordering, pagination, or joins |
| 3 | Block DSL (`select { }`, `delete { }`) | Complex queries with multiple joins and conditions |
| 4 | SQL Templates (/storm-sql-kotlin) | CTEs, window functions, database-specific features |

### When to use each — and when NOT to

| Need | Use (simplest) | Don't use (unnecessarily complex) |
|------|----------------|-----------------------------------|
| All rows as list | `findAll()` | `select().resultList` |
| Filter by single field | `findAllBy(field, value)` | `select(field eq value).resultList` |
| Filter by predicate | `findAll(predicate)` | `select(predicate).resultList` |
| Single result by predicate | `find(predicate)` | `select(predicate).optionalResult` |
| Single result (throw if missing) | `get(predicate)` | `select(predicate).singleResult` |
| Count by predicate | `count(predicate)` | `selectCount().where(predicate).singleResult` |
| Exists check | `exists(predicate)` | `count(predicate) > 0` |
| Delete by predicate | `removeAll(predicate)` | `delete(predicate).executeUpdate()` |
| Delete by field | `removeAllBy(field, value)` | `delete(field eq value).executeUpdate()` |
| Filtered + **ordering/pagination** | `select(predicate).orderBy(...).resultList` | convenience methods (can't add ordering) |
| Filtered + **joins** | `select { }` or `select().innerJoin(...)` | convenience methods (can't add joins) |
| Filtered + **streaming** | `select(predicate).resultFlow` | convenience methods (return List, not Flow) |
| Aggregates, CTEs, window functions | SQL Template (/storm-sql-kotlin) | QueryBuilder (can't express these) |

The rule: **escalate only when the simpler level cannot express what you need.** If you need ordering, you need at least Level 2. If you need joins, you need Level 2 or 3. If you need CTEs or window functions, you need Level 4.

**Level 1 — Convenience methods** (execute immediately, no terminal needed):
```kotlin
val user = orm.find(User_.email eq email)
val users = orm.findAll(User_.city eq city)
val exists = orm.existsBy(User_.email, email)
val removed = orm.removeAll(User_.active eq false)
```

**Level 2 — Builder with predicate** (returns `QueryBuilder`, chain terminal + ordering/pagination):
```kotlin
val users = users
    .select(User_.city eq city)
    .orderBy(User_.name)
    .resultList
```

Alternatively, use `select()` chained with `.where()` — equivalent, just a style preference:
```kotlin
val users = orm.entity<User>()
    .select()
    .where((User_.city eq city) and (User_.birthDate less LocalDate.of(2000, 1, 1)))
    .orderBy(User_.name)
    .resultList
```

Compound filters: `(A eq x) and (B eq y)`, `(A eq x) or (B eq y)`
Nested paths: `User_.city.country.code eq "US"`
Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`. Page API methods: `page.content()`, `page.totalPages()`, `page.totalElements()`, `page.number()`, `page.size()`, `page.hasNext()`, `page.hasPrevious()` — all are methods, not properties.
Scrolling (keyset, better for large tables): `.scroll(Scrollable.of(User_.id, 20))` — do NOT combine with `orderBy()` (Scrollable manages ORDER BY internally, see Keyset Scrolling section)
Explicit joins — two syntax forms depending on context:
- **Block DSL** (inside `select { }`): `innerJoin(UserRole::class, Role::class)` — two-arg, no `.on()`
- **Chained API**: `.innerJoin(UserRole::class).on(Role::class)` — returns builder, chain `.whereAny()` etc.
Select result type: `.select(ResultType::class)` to return a different type than the root entity

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** SQL templates are an escape hatch for things the QueryBuilder cannot express.

**Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin(T::class).on { "..." }`) or a full `orm.query { }` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin(Entity::class).on(OnEntity::class)`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database.

Three rules:

1. **Code-first:** If it can be done with QueryBuilder methods (joins, where, orderBy, groupBy, having), do it in code. Never use a template string for a `WHERE` clause that could be a `.where(predicate)`, or an `ORDER BY` that could be `.orderBy(field)`.
2. **Metamodel in templates:** When you do need a template fragment (e.g., for `COUNT(*)` in a select clause), still use metamodel references inside it (`${User_.email}`, not `"email"`). This keeps column references type-safe and refactor-proof.
3. **Full SQL last resort:** A full `SELECT ... FROM ...` SQL template should only be used for totally custom queries (CTEs, UNIONs, window functions) that cannot be built at all with the QueryBuilder. Even then, users still benefit from bind variables (`$value`) and metamodel references (`${Entity_field}`).

When you do use template lambdas, use `${}` interpolation (the compiler plugin handles parameter binding automatically) — never use `TemplateString.raw()` or `${t(...)}` manually.

## Aggregation

```kotlin
val userCount = orm.entity(User::class).selectCount().singleResult

val citySummaries = orm.entity(City::class)
    .select(CitySummary::class)
    .groupBy(City_.country)
    .having(City_.population, Operator.GREATER_THAN, 100000)
    .resultList
```

**Computed aggregates (COUNT, AVG, SUM, etc.):** When the SELECT clause needs expressions that QueryBuilder can't produce, use `select(ResultType::class) { template }` for the SELECT only — keep joins, groupBy, having, orderBy, and limit in code.

**Important:** The `{ template }` provides the SELECT list only — not a full SQL query. If you put a full `SELECT ... FROM ... WHERE ...` inside, Storm wraps it as a scalar subquery, causing errors. For full custom SQL, use `orm.query { }.getResultList(T::class)` (see /storm-sql-kotlin).

```kotlin
data class CityUserCount(val city: City, val userCount: Long) : Data

val cityCounts = orm.entity<City>()
    .select(CityUserCount::class) { "${City::class}, COUNT(*)" }
    .leftJoin(User::class).on(City::class)
    .groupBy(City_.id)
    .resultList

// More complex example with WHERE, HAVING, and ORDER BY — all in code:
data class CityUserStats(val cityName: String, val averageAge: Double, val userCount: Long) : Data

val minUsers = 10
val topCities = orm.entity<City>()
    .select(CityUserStats::class) { "${City_.name}, AVG(${User_.age}), COUNT(*)" }
    .leftJoin(User::class).on(City::class)
    .groupBy(City_.name)
    .having { "COUNT(*) >= $minUsers" }               // template form for aggregate expressions
    .orderByDescending { "AVG(${User_.age})" }
    .resultList

// Multi-field groupBy — always use the varargs metamodel form:
data class CityActiveCount(val city: Ref<City>, val active: Boolean, val userCount: Long) : Data

val counts = orm.entity<User>()
    .select(CityActiveCount::class) { "${User_.city}, ${User_.active}, COUNT(*)" }
    .groupBy(User_.city, User_.active)    // ✅ varargs metamodel form
    .resultList

// ❌ Don't use template lambda when metamodel fields work:
//    .groupBy { "${User_.city}, ${User_.active}" }
// ✅ Use varargs metamodel form — code-first, type-safe:
//    .groupBy(User_.city, User_.active)
```

**`Ref<T>` in aggregation result types:** When the SELECT clause references a FK field (`${User_.city}`) rather than a full entity (`${City::class}`), use `Ref<T>` in the result type — not the raw ID type and not the full entity. `Ref<City>` maps correctly to the FK column value. Use the full entity type only when the SELECT includes all its columns via `${City::class}`.

Always prefer code over templates. Templates are for expressions QueryBuilder can't produce (e.g., `COUNT(*)`, `AVG()`). `groupBy`, `having`, and `orderBy` also accept template lambdas when needed (e.g., `.having { "COUNT(*) >= $min" }`, `.orderByDescending { "AVG(${User_.age})" }`), but **always use the varargs metamodel form for `groupBy`** and **the metamodel form for `orderBy`** when possible — reserve template lambdas for computed expressions. Do NOT write the entire query as a raw SQL string.

## Row Locking

```kotlin
val user = orm.entity(User::class)
    .select()
    .where(User_.id eq userId)
    .forUpdate()         // SELECT ... FOR UPDATE
    .singleResult

// Or shared lock for reading
    .forShare()          // SELECT ... FOR SHARE
```

## Distinct and Count

```kotlin
val uniqueCities = orm.entity(User::class)
    .select(City::class)
    .distinct()
    .resultList

val count = orm.entity(User::class)
    .selectCount()
    .where(User_.active eq true)
    .singleResult
```

## Ref-Based Queries

```kotlin
// Query by ref
val user = orm.entity(User::class)
    .select()
    .where(userRef)
    .singleResult

// Query by multiple refs
val users = orm.entity(User::class)
    .select()
    .whereRef(userRefs)
    .resultList

// Select refs instead of full entities (lightweight)
val refs = orm.entity(User::class)
    .selectRef()
    .where(User_.city eq city)
    .resultList
```

## Subqueries (EXISTS / NOT EXISTS)

```kotlin
// WHERE EXISTS — filter entities that have related data
val citiesWithUsers = orm.entity(City::class)
    .select()
    .whereExists { subquery(User::class) }
    .resultList

// WHERE NOT EXISTS
val citiesWithoutUsers = orm.entity(City::class)
    .select()
    .whereNotExists { subquery(User::class) }
    .resultList
```

The `whereExists { }` / `whereNotExists { }` lambdas receive a `SubqueryTemplate` that provides the `subquery()` method. The subquery is automatically correlated with the outer query.

## Compound Predicates (whereBuilder)

For complex WHERE clauses that need AND/OR grouping beyond what infix operators provide:

```kotlin
val users = orm.entity(User::class)
    .select()
    .whereBuilder {
        where(User_.active, EQUALS, true)
            .and(where(User_.email, IS_NOT_NULL))
            .or(where(User_.role, EQUALS, "admin"))
    }
    .resultList
```

The `whereBuilder { }` lambda receives a `WhereBuilder` that provides `where()`, `exists()`, `notExists()` methods returning `PredicateBuilder` instances composable with `.and()` / `.or()`.

## Joined-Entity Predicates and Ordering

The `where()` and `orderBy()` methods in the block DSL are typed to the root entity (`T`). To filter, order, or group by a joined entity's field, use the `Any` variants:

- `whereAny(predicate)` — accepts `PredicateBuilder<*, *, *>` (any entity type)
- `orderByAny(path)` — accepts `Metamodel<*, *>` (any entity type)
- `orderByDescendingAny(path)` — same, descending
- `groupByAny(path)` — accepts `Metamodel<*, *>` (any entity type)

The `Any` variants (`whereAny`, `orderByAny`, `orderByDescendingAny`, `groupByAny`) are needed when referencing fields from joined (non-root) entities.

```kotlin
select {
    innerJoin(UserRole::class, User::class)
    whereAny(UserRole_.role eq role)
    orderByAny(User_.name)
}.resultList
```

These are also available on the chained QueryBuilder API: `.whereAny(...)`, `.orderByAny(...)`, `.orderByDescendingAny(...)`, `.groupByAny(...)`.

## Keyset Scrolling

Keyset scrolling uses cursor-based navigation instead of offset, making it efficient for large tables. `Scrollable<T>` takes a **single type parameter** — the entity type (e.g., `Scrollable<User>`). Do not pass a second type parameter. **Scrollable manages ORDER BY internally** — do NOT add `orderBy()` when using `scroll(Scrollable)`, or Storm throws `PersistenceException`.

**Composite PK limitation:** Keyset scrolling requires a simple (non-composite) primary key as the scroll key. Entities with composite PKs (e.g., junction tables) cannot be scrolled directly — Storm throws `SqlTemplateException: Column not found for metamodel`. To scroll filtered results from a junction table, query the related entity with a simple PK and JOIN through the junction table for filtering:
```kotlin
// ❌ Cannot scroll a junction table with composite PK
userRoles.scroll(Scrollable.of(UserRole_.id, 20))  // fails — UserRole has composite PK

// ✅ Scroll User (simple PK) with a JOIN through UserRole for filtering
users.select {
    innerJoin(UserRole::class, User::class)
    whereAny(UserRole_.role eq role)
}.scroll(Scrollable.of(User_.id, 20))
```

```kotlin
// WRONG: orderBy conflicts with Scrollable
select {
    where(User_.active eq true)
    orderBy(User_.name)        // ❌ Scrollable manages ordering
}.scroll(Scrollable.of(User_.id, 20))

// CORRECT: ordering is controlled by the Scrollable's key (and optional sort field)
select {
    where(User_.active eq true)
}.scroll(Scrollable.of(User_.id, 20))
```

**First request vs subsequent requests:** On the first request there is no cursor, so use `Scrollable.of()`. On subsequent requests, use `Scrollable.fromCursor()` — the cursor string encodes the size, direction, and position:

```kotlin
val scrollable = if (cursor != null) {
    Scrollable.fromCursor(User_.id, cursor)           // size encoded in cursor
} else {
    Scrollable.of(User_.id, 20)                       // first page, size 20
}
val window = users.scroll(scrollable)                     // prefer val — avoids Window<User> verbosity
val nextCursor: String? = window.nextCursor()          // null if no more results
```

**Custom sort column** (non-unique sort field with key as tiebreaker):
```kotlin
val scrollable = Scrollable.of(User_.id, User_.name, 20)
val window = users.scroll(scrollable)
```

**Backward scrolling and navigation:**
```kotlin
val window = users.scroll(Scrollable.of(User_.id, 20))
if (window.hasNext) {
    val next = users.scroll(window.next()!!)
}
if (window.hasPrevious) {
    val previous = users.scroll(window.previous()!!)
}
```

## Bulk DELETE/UPDATE

`delete()` is a builder method that returns `QueryBuilder`. Call `.executeUpdate()` to execute:

```kotlin
// DELETE with WHERE (safe) -- builder returns QueryBuilder, terminal executes
orm.entity(User::class).delete().where(User_.active eq false).executeUpdate()

// DELETE with predicate shorthand -- also returns QueryBuilder
orm.entity(User::class).delete(User_.active eq false).executeUpdate()

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User::class).delete().unsafe().executeUpdate()

// Convenience method: removeAll() executes immediately (calls unsafe() internally)
users.removeAll()
```

## Block-Based Query DSL

Use `select { }` / `delete { }` to build queries without chaining. Both are **builder methods** that return `QueryBuilder` -- they never execute immediately. Inside the block, use scope methods like `where()`, `orderBy()`, `limit()` to construct the query. Then call a terminal operation to execute:

```kotlin
orm.select<User> {
    where(User_.active eq true)
    orderBy(User_.name)
    limit(10)
}.resultList

// On EntityRepository — same syntax
select {
    where(User_.city eq city)
}.scroll(Scrollable.of(User_.id, 20))  // do NOT add orderBy — Scrollable manages ORDER BY

// delete { } also returns QueryBuilder — call .executeUpdate() to run it
delete {
    where(User_.active eq false)
}.executeUpdate()
```

Predicate variants also return `QueryBuilder`:
```kotlin
// select(predicate) returns QueryBuilder
users.select(User_.active eq true).resultList

// delete(predicate) returns QueryBuilder
users.delete(User_.active eq false).executeUpdate()
```

**Conditional logic inside the block:** The block is a regular Kotlin lambda — use `if`, `when`, and loops to compose queries dynamically. This avoids duplicating shared parts (ordering, pagination, terminals) across branches:
```kotlin
// ✅ Single select { } with conditional logic inside
select {
    if (city != null) {
        where(User_.city eq city)
    }
    orderBy(User_.name)
}.page(page, size)

// ❌ Don't branch outside and duplicate the shared parts
if (city != null) {
    select { where(User_.city eq city) }.orderBy(User_.name).page(page, size)
} else {
    select().orderBy(User_.name).page(page, size)
}
```

This also works with joins — conditionally add a join only when needed:
```kotlin
select {
    if (city != null) {
        innerJoin(UserAddress::class, User::class)
        whereAny(UserAddress_.city eq city)
    }
    orderByDescending(User_.createdAt)
}.page(page, size)
```

Available in the block: `where`, `whereAny`, `orderBy`, `orderByAny`, `orderByDescending`, `orderByDescendingAny`, `groupBy`, `groupByAny`, `having`, `limit`, `offset`, `distinct`, `forUpdate`, `forShare`, `innerJoin`, `leftJoin`, `rightJoin`, `crossJoin`, `append`.

**Note:** The block DSL has `orderBy { template }` but NOT `orderByDescending { template }`. For template-based descending order, use the chained API: `.orderByDescending { template }` or escape to raw SQL.

The block DSL is typed to the root entity. There is **no** `select(ResultType::class) { block }` form — `select { }` always returns the root entity type. To select a different result type, use the chained API:
```kotlin
// ❌ Not valid — no block DSL overload for result type
select(UserSummary::class) {
    where(User_.active eq true)
}.resultList

// ✅ Use chained API for a different result type
select(UserSummary::class)
    .where(User_.active eq true)
    .resultList

// ✅ With joins — chained API uses .innerJoin(A::class).on(B::class), not two-arg form
select(UserSummary::class)
    .innerJoin(UserRole::class).on(User::class)
    .whereAny(UserRole_.role eq role)
    .scroll(Scrollable.of(User_.id, 20))
```

**What `select(ResultType::class)` is for:** It selects a different result type from the query. This works for:
- **Joined entity types** — e.g., selecting `City::class` from a `User` query that joins `City`.
- **Custom SELECT with template** — e.g., `select(Summary::class, template)` with a custom SQL select clause that maps to the result type's fields.

It does **not** work for selecting a column subset of the root entity — e.g., a `UserSummary` with only `id` and `name` from `User` will fail with "Cannot find alias for column." For column subsets, use a `Projection<T>` with `ProjectionRepository`.

**Cross-entity pitfall:** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`). Put the query on the target entity's repository instead.

## Result Retrieval

QueryBuilder terminals:
- `.resultList` → `List<R>`
- `.singleResult` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.optionalResult` → `R?` (null if empty, throws if multiple)
- `.resultCount` → `Long`
- `.resultFlow` → `Flow<R>` (lazy, coroutines-based)
- `.resultStream` → `Stream<R>` (lazy, must close after use)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(scrollable)` → `Window<R>` (keyset scrolling — do NOT combine with `orderBy()`, see Keyset Scrolling section). Use `next()` / `previous()` for programmatic navigation, or `nextCursor()` / `previousCursor()` for REST APIs.
- `.executeUpdate()` → `Int` (for DELETE/UPDATE)

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value (or use the `select { }` DSL which handles this automatically).
- Multiple .where() calls are AND-combined.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: `select().resultFlow` returns a Flow with automatic cleanup. There are no `selectBy` methods that return Flow/Stream directly -- always use `select()` (optionally with predicate or block DSL) and then `.resultFlow` or `.resultStream`.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key:
  ```kotlin
  val countMap: MutableMap<Ref<Cell>, MutableMap<String, Int>> = mutableMapOf()
  countMap.getOrPut(candidate.cell.ref()) { mutableMapOf() }
  ```
- **Typed ID from `Ref`:** Use `ref.entityId()` (import `st.orm.template.entityId`) to extract a type-safe ID from a `Ref`. Avoid `ref.id()` — it returns `Any` and requires an unsafe cast.

## Verification

After writing queries, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the query produces the result the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
class UserQueryTest {
    @Test
    fun findActiveUsersInCity(orm: ORMTemplate, capture: SqlCapture) {
        val city = orm.entity<City>().getById(1)
        val users = capture.execute {
            orm.entity(User::class).select()
                .where((User_.city eq city) and (User_.active eq true))
                .orderBy(User_.name)
                .resultList
        }
        // Verify intent: single query, only active users in the given city, ordered by name.
        assertEquals(1, capture.count(Operation.SELECT))
        assertFalse(users.isEmpty())
        assertTrue(users.all { it.city == city && it.active })
        assertEquals(users.sortedBy { it.name }, users)
    }
}
```

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
