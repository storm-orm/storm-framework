Help the user write Storm queries using Kotlin.
**Important:** Storm can run on top of JPA, but when writing queries, always use Storm's own QueryBuilder and infix predicate operators — not JPQL, `CriteriaBuilder`, or `EntityManager.createQuery()`.

## Key Imports

```kotlin
import st.orm.template.*                         // QueryBuilder, eq, neq, like, ref, orm, etc.
import st.orm.Operator.*                         // EQUALS, NOT_EQUALS, LIKE, IN, IS_NULL, etc.
import st.orm.Ref                                // Lazy-loaded reference
import st.orm.Page                               // Offset-based pagination result
import st.orm.Pageable                           // Pagination request
import st.orm.Scrollable                         // Keyset scrolling cursor
import st.orm.MappedWindow                       // Keyset scrolling result
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
Order_.user eq user         // ✅ same for any FK field — don't use Order_.id.userId eq user.id
```

Three query levels (suggest the simplest that works):
| Approach | Best for |
|----------|----------|
| find/findAll | Simple lookups |
| QueryBuilder (.select().where()) | Most application queries |
| SQL Templates (/storm-sql-kotlin) | CTEs, window functions, subqueries |

Quick queries:
```kotlin
val user = orm.find(User_.email eq email)
val users = orm.findAll(User_.city eq city)
val exists = orm.existsBy(User_.email, email)
```

QueryBuilder (use `orm.entity<T>()` — reified, `import st.orm.repository.entity`):
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
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`
Scrolling (keyset, better for large tables): `.scroll(User_.id, 20)` or `Scrollable.fromCursor(User_.id, cursorString)` to resume from a serialized cursor
Explicit joins — two syntax forms depending on context:
- **Block DSL** (inside `select { }`): `innerJoin(UserRole::class, Role::class)` — two-arg, no `.on()`
- **Chained API**: `.innerJoin(UserRole::class).on(Role::class)` — returns builder, chain `.whereAny()` etc.
Select result type: `.select(ResultType::class)` to return a different type than the root entity

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** Only fall back to template lambdas when the QueryBuilder cannot express the query (e.g., database-specific functions). When you do use template lambdas, use `${}` interpolation (the compiler plugin handles parameter binding automatically) — never use `TemplateString.raw()` or `${t(...)}` manually.

## Aggregation

```kotlin
val count = orm.entity(Order::class).selectCount().resultList.first()

val totals = orm.entity(Order::class)
    .select(OrderSummary::class)
    .groupBy(Order_.status)
    .having(Order_.amount, Operator.GREATER_THAN, 100)
    .resultList
```

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
val ownersWithPets = orm.entity(Owner::class)
    .select()
    .whereExists { subquery(Pet::class) }
    .resultList

// WHERE NOT EXISTS
val ownersWithoutPets = orm.entity(Owner::class)
    .select()
    .whereNotExists { subquery(Pet::class) }
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

The `where()` and `orderBy()` methods in the block DSL are typed to the root entity (`T`). To filter or order by a joined entity's field, use the `Any` variants:

- `whereAny(predicate)` — accepts `PredicateBuilder<*, *, *>` (any entity type)
- `orderByAny(path)` — accepts `Metamodel<*, *>` (any entity type)
- `orderByDescendingAny(path)` — same, descending

```kotlin
select {
    innerJoin(UserRole::class, User::class)
    whereAny(UserRole_.role eq role)
    orderByAny(User_.name)
}.resultList
```

These are also available on the chained QueryBuilder API: `.whereAny(...)`, `.orderByAny(...)`, `.orderByDescendingAny(...)`.

## Bulk DELETE/UPDATE

```kotlin
// DELETE with WHERE (safe)
orm.entity(User::class).delete().where(User_.active eq false).executeUpdate()

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User::class).delete().unsafe().executeUpdate()
```

## Block-Based Query DSL

Use `select { }` / `delete { }` to build queries without chaining. A `PredicateBuilder` returned as the block's last expression is automatically applied as a WHERE clause, so `select { path eq value }` is equivalent to `select { where(path eq value) }`:

```kotlin
// Predicate shorthand (auto-applied as WHERE)
orm.select<User> { User_.active eq true }.resultList

// Explicit where — equivalent, use when combining with other clauses
orm.select<User> {
    where(User_.active eq true)
    orderBy(User_.name)
    limit(10)
}.resultList

// On EntityRepository — same syntax
select { User_.city eq city }.resultList
select {
    where(User_.city eq city)
    orderByDescending(User_.createdAt)
}.scroll(20)
```

Available in the block: `where`, `whereAny`, `orderBy`, `orderByAny`, `orderByDescending`, `orderByDescendingAny`, `groupBy`, `having`, `limit`, `offset`, `distinct`, `forUpdate`, `forShare`, `innerJoin`, `leftJoin`, `rightJoin`, `crossJoin`, `append`.

The block DSL is typed to the root entity. To select a different result type, use the chained API: `repository.select(ResultType::class).where(...).resultList`.

**Important:** `select(ResultType::class)` changes the **output columns**, not the table being queried. The query always runs against the repository's root entity table. The result type must map to columns available in the query (from the root table or joined tables). It does NOT support selecting a column subset of the root entity — use a `ProjectionRepository` for that.

## Result Retrieval

QueryBuilder terminals:
- `.resultList` → `List<R>`
- `.singleResult` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.optionalResult` → `R?` (null if empty, throws if multiple)
- `.resultCount` → `Long`
- `.resultFlow` → `Flow<R>` (lazy, coroutines-based)
- `.resultStream` → `Stream<R>` (lazy, must close after use)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(size)` → `MappedWindow<R, T>` (keyset scrolling, better for large tables)
- `.executeUpdate()` → `Int` (for DELETE/UPDATE)

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value (or use the `select { }` DSL which handles this automatically).
- Multiple .where() calls are AND-combined.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: `select().resultFlow` returns a Flow with automatic cleanup.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key:
  ```kotlin
  val countMap: MutableMap<Ref<Cell>, MutableMap<String, Int>> = mutableMapOf()
  countMap.getOrPut(candidate.cell.ref()) { mutableMapOf() }
  ```

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
