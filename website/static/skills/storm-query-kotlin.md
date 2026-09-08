---
name: storm-query-kotlin
description: Write Storm queries in Kotlin with the QueryBuilder, covering joins, infix predicates, ordering, pagination, keyset scrolling, and Ref navigation. Use for any read query in Kotlin.
---

Help the user write Storm queries using Kotlin.
**Important:** Storm can run on top of JPA, but when writing queries, always use Storm's own QueryBuilder and infix predicate operators — not JPQL, `CriteriaBuilder`, or `EntityManager.createQuery()`.

## Key Imports

```kotlin
import st.orm.template.*                         // QueryBuilder, eq, neq, like, ref, orm, etc.
import st.orm.repository.*                       // entity<T>(), select<Result, _, _> { }, findBy, insert, ... (wildcard: see below)
import st.orm.Operator.*                         // escape hatch: only when the operator is chosen at runtime
import st.orm.Ref                                // Lazy-loaded reference
import st.orm.Page                               // Offset-based pagination result
import st.orm.Pageable                           // Pagination request
import st.orm.Scrollable                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window                             // Keyset scrolling result (Window<R>)
import org.junit.jupiter.api.Assertions.*        // assertEquals, assertTrue, assertFalse
```

Use `import st.orm.template.*` to get all infix operators and the `ref()` / `orm` extensions in one import. The `select { }` / `delete { }` block DSL and `and` / `or` combinators are member functions — no import needed.

**Import both packages with wildcards: `st.orm.template.*` and `st.orm.repository.*`.** Repository operations are top-level extensions in `st.orm.repository`, so `import st.orm.template.*` alone does not bring them in. Naming them individually is how one ends up missing.

The custom-result form `select<Result, _, _> { ... }` is the one that punishes this hardest. Without its import the extension is out of scope, and no `select` member matches a call that passes a template lambda and three type arguments, so nothing applies. On Kotlin 2.0.x the compiler crashes while reporting that resolution failure rather than naming the missing import:

```
org.jetbrains.kotlin.util.FileAnalysisException: ... Unexpected FirPlaceholderProjectionImpl
```

That crash is a Kotlin 2.0.x compiler bug, fixed in 2.1.0, where an unresolved call using `_` placeholders is reported as an internal error instead of a diagnostic. On 2.1.0+ the same code says `Unresolved reference 'select'`. Read it as "something here does not resolve", and check the import first.

All infix predicate operators (`eq`, `neq`, `like`, `greater`, `less`, `inList`, `isNull`, `isNotNull`, `isTrue`, `isFalse`, `between`, etc.) are extension functions on `Metamodel<T, V>` defined in `st.orm.template` (in QueryBuilder.kt).

Ask what data they need, filters, ordering, or pagination.

**Repository rule:** All database queries must live in repository interfaces, not inline in services or other classes. In Spring Boot or Ktor projects, repositories are constructor-injected (see /storm-repository-kotlin). Use `orm.entity<T>()` and `orm.repository<T>()` lookups only in standalone (non-DI) contexts and tests.

**Layering rule:** Follow the codebase's existing convention first — if handlers already use repositories directly, or a service layer is consistently in place, match that style rather than introduce a competing one. Absent a clear stance (new code, greenfield), promote the layered architecture: controller → service → repository, where controllers never inject repositories — all data access flows through services, which own the transaction boundaries and return view-model types. Whatever the stance, do not mix styles: layer-skipping controllers undermine the service layer's cross-cutting concerns (transactions, caching, authorization).

**Code-first WHERE clauses:** Always express WHERE conditions using metamodel-based predicates (`eq`, `isFalse()`, `isNotNull()`, etc.) instead of template strings. Only fall back to template expressions for conditions that predicates cannot express (e.g., `COALESCE`, date arithmetic, aggregate functions). When a WHERE clause mixes expressible and inexpressible conditions, split it: use code-based predicates for what you can, templates only for what you must. Multiple `where()` calls are AND-combined automatically. FK paths through the object graph do not require explicit joins, whether they stop at the foreign key (`User_.city eq city`, which is the foreign key column and joins nothing) or navigate past it (`User_.city.country.code eq "US"`, for which Storm joins `COUNTRY` on demand). This holds for a `Ref` foreign key as well, which is navigable without being hydrated: see **Navigating Through a Ref**.

```kotlin
// ❌ Wrong — WHERE conditions as a template string
fun findActiveWithEmail(city: City, minAge: Int): List<User> =
    select {
        where {
            """${User_.city} = ${city.id()}
            AND ${User_.active} = true
            AND ${User_.email} IS NOT NULL
            AND TIMESTAMPDIFF(YEAR, ${User_.birthDate}, CURDATE()) >= $minAge"""
        }
    }.resultList

// ✅ Correct — code-based predicates where possible, template only when no alternative exists
fun findActiveWithEmail(city: City, minAge: Int): List<User> =
    select {
        where((User_.city eq city) and (User_.active.isTrue()) and (User_.email.isNotNull()))
        where { "TIMESTAMPDIFF(YEAR, ${User_.birthDate}, CURDATE()) >= $minAge" }
    }.resultList
```

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
User_.city inRefs cityRefs         // IN over Iterable<Ref<T>> — for FK fields with refs
User_.age.between(18, 65)          // BETWEEN
User_.active.isTrue()              // IS_TRUE
User_.archived.isFalse()           // IS_FALSE
User_.email.isNull()               // IS_NULL
User_.email.isNotNull()            // IS_NOT_NULL
```

Combine with `and`/`or`:
```kotlin
(User_.active eq true) and User_.email.isNotNull()
(User_.role eq "admin") or (User_.role eq "superadmin")
```

Compound predicates composed this way stay inside plain `where(...)` — never reach for `whereBuilder { }` to group AND/OR (see **Choosing the WHERE Form**).

The `eq` operator accepts both entities and `Ref<T>`. When you have an entity, use it directly — no need to extract the ID or convert to a `Ref`:
```kotlin
User_.city eq city          // ✅ entity directly — compares by FK
User_.city eq city.ref()    // also works, but unnecessary when you have the entity
// Same pattern works for any FK field — always pass the entity or Ref, not the raw ID

// When you only have an ID (e.g., from a URL parameter), create a Ref:
User_.city eq refById<City>(cityId)   // ✅ import st.orm.template.refById
// ❌ Don't construct a dummy entity: City(id = cityId, name = "", ...)
```

**Name a foreign key by the field itself, never by `.id`.** A foreign key field already resolves to the foreign key column, so `User_.city` and `User_.city.id` are the same column and neither joins the target table. Prefer the plain field: `.id` reads as a navigation step into `City` and invites the reader to expect a join that is not there. This holds everywhere a column is named — predicates, template select lists, `groupBy`, `orderBy`, and aggregate expressions:

```kotlin
data class CityUserCount(val city: Ref<City>, val userCount: Long)

// ✅ The foreign key field names the foreign key column
orm.entity<User>()
    .select<CityUserCount, _, _> { "${User_.city}, COUNT(*)" }
    .groupBy(User_.city)
    .resultList

// ❌ Redundant `.id` — identical SQL, and it reads like a join into City
orm.entity<User>()
    .select<CityUserCount, _, _> { "${User_.city.id}, COUNT(*)" }
    .groupBy(User_.city.id)
    .resultList

COUNT(DISTINCT ${User_.city})       // ✅ inside a template
COUNT(DISTINCT ${User_.city.id})    // ❌ same column, longer
```

The rule extends to set membership: filter on the foreign key with `inRefs` rather than unwrapping refs into ids to compare against the same column.

```kotlin
User_.city inRefs cityRefs                            // ✅ IN over refs
User_.city.id inList cityRefs.map { it.entityId() }   // ❌ unwraps refs to reach the column they already name
```

**A compound foreign key is several columns, and templates take only one.** A foreign key to an entity whose `@PK` is an inline record is a multi-column foreign key — Storm matches its columns to the target's primary key columns one for one. Code-first operators expand it; a template placeholder does not, because a placeholder stands for a single column. Interpolating one throws `SqlTemplateException: Expected exactly one column for metamodel: …` before any SQL reaches the database.

```kotlin
// ✅ Code-first expands the compound key
.where(Order_.line eq orderLine)   // WHERE o.order_id = ? AND o.line_no = ?
.groupBy(Order_.line)              // GROUP BY o.order_id, o.line_no — same columns, no join

// ❌ Template placeholders reject it — SqlTemplateException, not broken SQL
"COUNT(DISTINCT ${Order_.line})"
"${Order_.line}, COUNT(*)"
"sub.line_id = ${Order_.line}"
```

So the naming rule stands unchanged for single-column foreign keys, which is the overwhelmingly common case, and a compound key simply cannot appear in a template expression at all — name one of its leaves (`Order_.line.lineNo`, which joins the referenced table) or restructure the query to use the code-first form. Naming the target's primary key through the foreign key is the same as naming the foreign key itself: `Order_.line.id` and `Order_.line` resolve to the same foreign key column(s) on the referencing table, without a join — for `Ref` and entity foreign keys alike. The same single-column assumption is why keyset scrolling rejects a composite key (see **Keyset Scrolling**).

## API Design: Builder Methods vs Convenience Methods

Repository/entity methods fall into two categories:

**Builder methods** return `QueryBuilder` for composable, chainable queries. They never execute immediately:
- `select()`, `select(predicate)`, `select { }` -- build SELECT queries
- `selectRef()`, `selectRef(predicate)` -- build SELECT queries returning Refs
- `selectCount()` -- build COUNT queries
- `delete()`, `delete(predicate)`, `delete { }` -- build DELETE queries

Terminal operations: `.resultList`, `.singleResult`, `.optionalResult`, `.resultFlow`, `.resultStream`, `.windows(size)`, `.resultCount`, `.resultGroupedBy(path)`, `.resultGroupedByRef(path)`, `.page()`, `.scroll()`, `.executeUpdate()`

**One-to-many loading:** `resultGroupedBy(path)` groups results by a related record, typically the parent entity of a foreign key. The same select is executed (single query, no SQL change) and the results are grouped during hydration into an unmodifiable, insertion-ordered `Map<Parent, List<T>>`. Repeated parents are materialized once and grouped by instance identity, so the join's duplication is not paid during hydration. The path must resolve non-null for every result. The ref-based variant returns `Map<Ref<Parent>, List<T>>` with keys compared by primary key. For eager entity paths the keys are loaded refs (`getOrNull()` returns the already-materialized record); for `Ref` foreign-key fields it groups directly on the foreign key without fetching the parent, and `findAllByRef(map.keys)` fetches the parents in one query when needed.

```kotlin
// Load cities with their users in one query
val usersByCity: Map<City, List<User>> = orm.entity<User>()
    .select()
    .orderBy(User_.city)
    .resultGroupedBy(User_.city)
```

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
| Parents with their children (one-to-many) | `select().resultGroupedBy(parentPath)` | per-parent queries in a loop (N+1) or manual `groupBy` after `resultList` |
| Filtered + **ordering/pagination** | `select(predicate).orderBy(...).resultList` | convenience methods (can't add ordering) |
| Filtered + **joins** | `select { }` or `select().innerJoin(...)` | convenience methods (can't add joins) |
| Filtered + **streaming** | `select(predicate).resultFlow` (consume-only) or `.windows(size)` (loop may query/write) | convenience methods (return List, not Flow) |
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
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`. Page API methods (Java record accessors — always call with `()`): `page.content()`, `page.totalPages()`, `page.totalCount()`, `page.pageNumber()`, `page.pageSize()`, `page.hasNext()`, `page.hasPrevious()`, `page.nextPageable()`.
Scrolling (keyset, better for large tables): `.scroll(Scrollable.of(User_.id, 20))` — do NOT combine with `orderBy()` (Scrollable manages ORDER BY internally, see Keyset Scrolling section)
Explicit joins — two syntax forms depending on context:
- **Block DSL** (inside `select { }`): `innerJoin<UserRole, Role>()` — reified two-type-arg form, no `.on()`
- **Chained API**: `.innerJoin<UserRole>().on<Role>()` — the join widens the query; chain `.where()` for root and joined fields alike
Select result type: `.select(ResultType::class)` to return a different type than the root entity

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** SQL templates are an escape hatch for things the QueryBuilder cannot express.

**Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin<T>().on { "..." }`) or a full `orm.query { }` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin<Entity>().on<OnEntity>()`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database. Projections join like entities: `.on<ProjectionType>()` resolves the foreign key by matching the referenced entity's table against the projection's table. When multiple foreign keys reference that table the join is ambiguous — Storm fails with an error naming the candidate fields; disambiguate with a template ON clause.

**Prefer a path over a join.** An explicit join is not how you filter on a related table. When the relationship is a foreign key, name the column through it and Storm joins the referenced table itself, for `Ref` foreign keys as much as for eager ones. Both forms name the same occurrence, so a query that writes the join by hand and then filters through it gains nothing over the path alone:

```kotlin
// Order holds `@FK val user: Ref<User>`; User holds `@FK val city: City`.

// ✅ The path carries the query; Storm joins the user and city tables on demand
orders.select()
    .where(Order_.user.city.name eq "Madison")
    .resultList

// ❌ The same joins, spelled out, to reach a column the path already reaches
orders.select()
    .innerJoin<User>().on<Order>()
    .innerJoin<City>().on<User>()
    .where(City_.name eq "Madison")
    .resultList
```

Reach for an explicit join when a path cannot express the query: a relationship the entity model does not carry as a foreign key, the to-many side of one (a path navigates to-one only), a self-join that needs its own alias, or a join whose entity you want the query widened to rather than filtered by.

Three rules:

1. **Code-first:** If it can be done with QueryBuilder methods (joins, where, orderBy, groupBy, having), do it in code. Never use a template string for a `WHERE` clause that could be a `.where(predicate)`, or an `ORDER BY` that could be `.orderBy(field)`.
2. **Metamodel in templates:** When you do need a template fragment (e.g., for `COUNT(*)` in a select clause), still use metamodel references inside it (`${User_.email}`, not `"email"`). This keeps column references type-safe and refactor-proof.
3. **Full SQL last resort:** A full `SELECT ... FROM ...` SQL template should only be used for totally custom queries (CTEs, UNIONs, window functions) that cannot be built at all with the QueryBuilder. Even then, users still benefit from bind variables (`$value`) and metamodel references (`${Entity_field}`).

When you do use template lambdas, use `${}` interpolation (the compiler plugin handles parameter binding automatically) — never use `TemplateString.raw()` or `${t(...)}` manually.

## Aggregation

```kotlin
val userCount = orm.entity<User>().selectCount().singleResult

val citySummaries = orm.entity<City>()
    .select(CitySummary::class)
    .groupBy(City_.country)
    .having(City_.population greater 100000)
    .resultList
```

**HAVING takes predicates too.** `having(predicate)` accepts the same infix operators as `where(predicate)`, so a condition on a grouped column stays in code — including a disjunction, which consecutive `having()` calls cannot express because they are AND-combined. A joined entity's column goes through the same `having(predicate)` once the query has the join. A condition on an *aggregate* still needs a template, since no metamodel path names a computed value:

```kotlin
.having((City_.country eq nl) or (City_.country eq be))   // grouped column — predicate
.having { "COUNT(*) > ${minimum}" }                       // aggregate — template
```

**Computed aggregates (COUNT, AVG, SUM, etc.):** When the SELECT clause needs expressions that QueryBuilder can't produce, use `select<ResultType, _, _> { template }` for the SELECT only — keep joins, groupBy, having, orderBy, and limit in code. The result type is explicit; the underscores let Kotlin infer the repository's entity and ID types. Style: use the underscore form only in code that is otherwise fully reified; if the surrounding query uses `::class` calls, write `select(ResultType::class) { template }` instead — keep each snippet internally consistent (template interpolations like `${City::class}` don't count).

**`Data` means "represents a table".** Only types that map to a database table implement `Data` (or its subtypes `Entity`/`Projection`). Ad-hoc query result types — aggregation DTOs, computed shapes — are plain data classes with no marker at all. Storm maps result columns to any suitable data class, including nested entity and `Ref<T>` components. Implementing `Data` on a result type pulls it into Storm's type discovery, so schema validation (`validateSchema()` without arguments, or `storm.validation.schema-mode` at startup) fails with `TABLE_NOT_FOUND` — which then requires `@DbIgnore` to suppress. `: Data` + `@DbIgnore` on a result type is an anti-pattern (opting in and immediately opting out); a plain data class expresses the intent directly. Define result types next to the code that produces them — typically top-level in the repository file whose queries return them; the model package is reserved for table-backed types — and document them as query result shapes (not backed by a table or view) so readers immediately see why they carry no marker.

**Important:** The `{ template }` provides the SELECT list only — not a full SQL query. If you put a full `SELECT ... FROM ... WHERE ...` inside, Storm wraps it as a scalar subquery, causing errors. For full custom SQL, use `orm.query { }.resultList<T>()` (see /storm-sql-kotlin).

```kotlin
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain data class — deliberately not a Data type.
 */
data class CityUserCount(val city: City, val userCount: Long)

val cityCounts = orm.entity<City>()
    .select<CityUserCount, _, _> { "${City::class}, COUNT(*)" }
    .leftJoin<User>().on<City>()
    .groupBy(City_.id)
    .resultList

// More complex example with WHERE, HAVING, and ORDER BY — all in code:
data class CityUserStats(val cityName: String, val averageAge: Double, val userCount: Long)

val minUsers = 10
val topCities = orm.entity<City>()
    .select<CityUserStats, _, _> { "${City_.name}, AVG(${User_.age}), COUNT(*)" }
    .leftJoin<User>().on<City>()
    .groupBy(City_.name)
    .having { "COUNT(*) >= $minUsers" }               // template form for aggregate expressions
    .orderByDescending { "AVG(${User_.age})" }
    .resultList

// Multi-field groupBy — always use the varargs metamodel form:
data class CityActiveCount(val city: Ref<City>, val active: Boolean, val userCount: Long)

val counts = orm.entity<User>()
    .select<CityActiveCount, _, _> { "${User_.city}, ${User_.active}, COUNT(*)" }
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
val user = orm.entity<User>()
    .select()
    .where(User_.id eq userId)
    .forUpdate()         // SELECT ... FOR UPDATE
    .singleResult

// Or shared lock for reading
    .forShare()          // SELECT ... FOR SHARE
```

## Distinct and Count

```kotlin
val uniqueCities = orm.entity<User>()
    .select(City::class)
    .distinct()
    .resultList

val count = orm.entity<User>()
    .selectCount()
    .where(User_.active eq true)
    .singleResult
```

## Ref-Based Queries

```kotlin
// Query by ref
val user = orm.entity<User>()
    .select()
    .where(userRef)
    .singleResult

// Query by multiple refs
val users = orm.entity<User>()
    .select()
    .whereRef(userRefs)
    .resultList

// Select refs instead of full entities (lightweight)
val refs = orm.entity<User>()
    .selectRef()
    .where(User_.city eq city)
    .resultList
```

### Navigating Through a Ref

A `Ref<T>` foreign key is still navigable in queries. Filter, order, and select through it with the metamodel; Storm adds the join for the referenced table on demand, only for a query that references a column beyond the foreign key, so filtering or ordering on the referenced table takes no explicit join of its own. Selecting the root leaves the field as an unloaded `Ref`.

```kotlin
// User.city is Ref<City>; City has a country FK. The city and country tables are joined
// only because the query navigates beyond the city foreign key.
val users = orm.entity<User>()
    .select()
    .where(User_.city.country.name eq "United States")
    .orderBy(User_.city.name)
    .resultList

// Select a column from beyond the reference.
data class CountryName(val name: String)
val names = orm.entity<User>()
    .select<CountryName, _, _> { "${User_.city.country.name}" }
    .where(User_.city.country.name eq "United States")
    .resultList

// Select the referenced entity itself: the city table is joined on demand and hydrated
// with its own country foreign key.
val cities = orm.entity<User>().select(City::class).resultList

// Join another table onto the referenced one; the join brings the city table in.
val sharingACity = orm.entity<User>().select()
    .innerJoin<User>().on<City>()
    .resultList
```

Nodes beyond a reference are navigation-only: usable in `where`, `orderBy`, `groupBy`, `having`, and selected columns, but not in value operations. Group by the reference itself with `resultGroupedByRef`, not `resultGroupedBy` on a beyond-reference path (which does not compile). Besides a path, the referenced table can be named by type: selecting it or joining onto it materializes the same join, and a query that does both joins it once; a table joined explicitly keeps that occurrence. Prefer a `Ref` for foreign keys you do not hydrate on most reads: it keeps SELECTs narrow while staying queryable. Navigation may cross more than one reference across distinct tables. A reference carries the target's primary key, so `User_.city` and `User_.city.id` both resolve to the foreign key column and neither joins — prefer the plain field, which is the same column an entity foreign key resolves its primary key to; any other column of the target joins. A self-referential foreign key must be a `Ref`, and it is navigable: the table is joined to itself, each occurrence under its own alias. The typed metamodel navigates a cycle two hops deep (generated metamodels build their children eagerly, so they cannot recurse); deeper cyclic paths are named as strings, which the engine resolves to any depth.

### Resolving a Ref as Part of the Query

A `Ref` is resolved on demand by `fetch()` on the reference itself, and that is always correct. When a read already knows it needs the referenced record, `select().fetch(path...)` folds that load into the same statement instead of a query of its own. It names the references the statement resolves: the referenced table's columns take the place of the foreign key column, so the reference comes back loaded and reading it costs no query. The record type is unchanged — the property stays a `Ref` — so the same type serves queries that resolve it and queries that do not.

```kotlin
val users = orm.entity<User>().select()
    .fetch(User_.city, User_.city.country)
    .resultList

val city = users.first().city.getOrThrow()   // no query; throws if the plan did not cover it
```

Read a resolved reference with `getOrThrow()`, not `fetch()`. Both return the record, but `fetch()` silently queries when the plan did not cover the path, so a `fetch(...)` lost in a refactor degrades into one query per row without failing; `getOrThrow()` never queries and reports it where the assumption was made. Use `fetch()` on the ref only where resolving on demand is the intent.

`getOrThrow()` is a claim about the query that produced the row, so assert it only where that query is in view: the same function, or a repository method whose doc names what it resolves. A function that takes an entity as a parameter cannot see the plan the entity was loaded under, and each caller settles that independently, so read its references with `fetch()`, or take the resolved record as a parameter instead of the entity.

```
isLoaded()      // is the record here?
getOrThrow()       // give it to me, never queries, throws if absent
getOrNull()     // give it to me or null, never queries
fetch()         // resolve now, query if needed
fetchOrNull()   // same, null instead of throwing
```

The plan is prefix-closed: naming `User_.city.country` resolves `User_.city` too, so write only the deepest path. A reference the plan does not name stays a foreign key column. A reference is always a to-one foreign key, so resolving one widens the row without multiplying it, and a cycle is bounded by the depth named (`fetch(Node_.parent.parent)` is exactly two levels). A nullable reference keeps its outer join, so a null foreign key yields a null reference. Paths that cross no reference, references to sealed types, and queries that do not select a record are rejected with a descriptive error.

A `Ref` therefore describes a relationship that belongs to particular queries rather than to the entity itself, and this is where such a query asks for it. See the entity skill for which relationships to declare each way.

## Subqueries (EXISTS / NOT EXISTS)

```kotlin
// WHERE EXISTS — filter entities that have related data
val citiesWithUsers = orm.entity<City>()
    .select()
    .whereExists { subquery(User::class) }
    .resultList

// WHERE NOT EXISTS
val citiesWithoutUsers = orm.entity<City>()
    .select()
    .whereNotExists { subquery(User::class) }
    .resultList
```

The `whereExists { }` / `whereNotExists { }` lambdas receive a `SubqueryTemplate` that provides the `subquery()` method. The subquery is automatically correlated with the outer query.

## Choosing the WHERE Form: where() → whereBuilder { }

Two forms build a WHERE clause: **always use `where(predicate)` unless the condition needs the builder scope itself**.

1. **`where(predicate)`** — the default, for every field condition. Compound conditions stay at this level — compose them with infix `and`/`or`. Before a join the chained builder is typed to the root entity, and a nested path that starts at the root (`User_.city.country.code eq "US"`) is root-typed however deep. **A join widens the query**: from the join onward, `where()` (and `orderBy`, `groupBy`, `having`) accepts paths from any entity in the query, so a joined table's fields go through the same plain calls. Inside `select { }` the block is widened from the start. A path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root.
2. **`whereBuilder { }`** — only when the condition needs the builder scope itself: `exists()`/`notExists()` composed into compound logic, or id/ref/record/template matching (`whereId`, `whereRef`, `where(record)`, `where { template }`) inside a compound expression. Never for plain field predicates, and never for AND/OR grouping — infix operators inside `where()` already do that.

```kotlin
// ✅ Compound predicates belong in where() — infix and/or, parenthesized per group
val users = orm.entity<User>()
    .select()
    .where(((User_.active eq true) and User_.email.isNotNull()) or (User_.role eq "admin"))
    .resultList

// ❌ whereBuilder adds nothing here — same query, more machinery
val users = orm.entity<User>()
    .select()
    .whereBuilder {
        where(User_.active, EQUALS, true)
            .and(where(User_.email, IS_NOT_NULL))
            .or(where(User_.role, EQUALS, "admin"))
    }
    .resultList
```

Consecutive `where()` calls AND together (each clause parenthesized), and the infix `and`/`or` combinators root a combination at the operands' least common root, so a cross-entity AND works as two `where()` calls or as one compound clause on a widened builder; a narrow, join-less builder rejects the cross-root compound at the call site:

```kotlin
users.select()
    .innerJoin<UserRole>().on<User>()     // the join widens the query
    .where(User_.active eq true)          // root field
    .where(UserRole_.role eq role)        // joined entity — same call
    .resultList

users.select()
    .innerJoin<UserRole>().on<User>()
    .where((User_.active eq true) and (UserRole_.role eq role))   // or as one compound clause
    .resultList
```

A genuine `whereBuilder { }` case — a subquery composed into compound logic (a bare EXISTS needs no builder either: `whereExists { }` covers it):

```kotlin
users.select()
    .whereBuilder {
        (User_.active eq true) and exists(subquery(UserRole::class).where(UserRole_.role eq role))
    }
    .resultList
```

The `whereBuilder { }` lambda receives a `WhereBuilder` that provides `where()`, `exists()`, `notExists()` methods returning `PredicateBuilder` instances composable with `.and()` / `.or()` — and infix predicates work inside it, so even there prefer `(A eq x) and exists(...)` over the operator-argument style.

## Joined-Entity Predicates and Ordering

A join widens the query: from the join onward, every clause accepts paths from any entity in the query. There are no separate methods for joined entities — the joined table's fields go through the same `where`, `orderBy`, `groupBy` and `having` calls:

```kotlin
// Root is User: the joined UserRole fields go through the same calls as the root's
select {
    innerJoin<UserRole, User>()
    where(UserRole_.role eq role)      // joined entity — plain where
    orderBy(User_.name)                // root path
    orderBy(UserRole_.assignedAt)      // joined entity
}.resultList
```

Inside `select { }` the block is widened from the start, so clause order does not matter there. On the chained API the builder is typed to the root **until** the join, so put joins before the clauses that reference them. A path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root.

Two operations are defined relative to the root and are affected by the widening: `fetch(...)` comes before any join, and `resultGroupedBy` needs the root back after one: `narrow<User>()` restores it, verified against the query's FROM table. The counterpart `widen()` widens without a join — the chained equivalent of `select { }` starting widened. (`typedId(Int::class)` types the primary key.)

## Keyset Scrolling

Keyset scrolling uses cursor-based navigation instead of offset, making it efficient for large tables. `Scrollable<T>` takes a **single type parameter** — the entity type (e.g., `Scrollable<User>`). Do not pass a second type parameter. **Scrollable manages ORDER BY internally** — do NOT add `orderBy()` when using `scroll(Scrollable)`, or Storm throws `PersistenceException`.

**Composite PK limitation:** The scroll key must be a single-column, non-nullable unique key (e.g. a simple `@PK` or `@UK` field). Entities whose only unique key is a composite PK (e.g., junction tables) cannot be scrolled directly — the key doesn't resolve to a single column. To scroll filtered results from a junction table, query the related entity with a simple PK and JOIN through the junction table for filtering:
```kotlin
// ❌ Cannot scroll a junction table with composite PK
userRoles.scroll(Scrollable.of(UserRole_.id, 20))  // fails — UserRole has composite PK

// ✅ Scroll User (simple PK) with a JOIN through UserRole for filtering
users.select {
    innerJoin<UserRole, User>()
    where(UserRole_.role eq role)
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

**First request vs subsequent requests:** On the first request there is no cursor, so use `Scrollable.of()`. On subsequent requests, use `Scrollable.fromCursor()`. The cursor is **opaque** and exists for client-server communication: it contains exactly the information the client needs to navigate the scroll window (key position, window size, direction). Clients treat it as a black box — never parse or construct it — and echo it back unchanged to fetch the adjacent window. Server-side code never needs the cursor: `window.next()` / `window.previous()` return a ready-to-use typed `Scrollable<T>` — the cursor is merely the serialized form of that same `Scrollable` for crossing the client-server boundary:

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

**Backward scrolling and navigation** (`Window` is a Java record — accessors are methods, call with `()`):
```kotlin
val window = users.scroll(Scrollable.of(User_.id, 20))
if (window.hasNext()) {
    val next = users.scroll(window.next()!!)
}
if (window.hasPrevious()) {
    val previous = users.scroll(window.previous()!!)
}
```

## Bulk DELETE/UPDATE

`delete()` is a builder method that returns `QueryBuilder`. Call `.executeUpdate()` to execute:

```kotlin
// DELETE with WHERE (safe) -- builder returns QueryBuilder, terminal executes
orm.entity<User>().delete().where(User_.active eq false).executeUpdate()

// DELETE with predicate shorthand -- also returns QueryBuilder
orm.entity<User>().delete(User_.active eq false).executeUpdate()

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity<User>().delete().unsafe().executeUpdate()

// Convenience method: removeAll() executes immediately (calls unsafe() internally)
users.removeAll()
```

## Block-Based Query DSL

**Prefer the chained API for linear queries.** A straight filter/order/limit pipeline reads best as a chain — `select(predicate).orderBy(...).limit(...).resultList`, or `select().where { template }...` when the condition needs a template. Reach for the `select { }` block only when you truly need the block structure: conditional predicates or joins (`if`/`when` inside the block), or queries with many clauses where the scoped layout helps.

Use `select { }` / `delete { }` to build queries without chaining. Both are **builder methods** that return `QueryBuilder` -- they never execute immediately. Inside the block, use scope methods like `where()`, `orderBy()`, `limit()` to construct the query. Then call a terminal operation to execute:

```kotlin
// Standalone: get the entity repository first — there is NO orm.select<T> { block } reified form
orm.entity<User>().select {
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
        innerJoin<UserAddress, User>()
        where(UserAddress_.city eq city)
    }
    orderByDescending(User_.createdAt)
}.page(page, size)
```

Available in the block: `where`, `whereBuilder`, `whereExists`, `whereNotExists`, `orderBy`, `orderByDescending`, `groupBy`, `having`, `havingExists`, `havingNotExists`, `limit`, `offset`, `distinct`, `unsafe`, `forUpdate`, `forShare`, `fetch`, `innerJoin`, `leftJoin`, `rightJoin`, `crossJoin`, `append`. The block is widened from the start, so joined-entity fields go through the same calls as root fields; `whereBuilder` remains the escalation for `exists`/id/ref/record matching inside compound logic (see **Choosing the WHERE Form**).

**Note:** The block DSL has `orderBy { template }` but NOT `orderByDescending { template }`. For template-based descending order, use the chained API: `.orderByDescending { template }` or escape to raw SQL.

The block DSL is typed to the root entity. There is **no** `select(ResultType::class) { block }` form — `select { }` always returns the root entity type. (In `select(ResultType::class) { ... }` / `select<ResultType, _, _> { ... }` the trailing lambda is a SQL template for the SELECT clause, not a block DSL.) To select a different result type, use the chained API:
```kotlin
// ❌ Not valid — no block DSL overload for result type
select(UserSummary::class) {
    where(User_.active eq true)
}.resultList

// ✅ Use chained API for a different result type
select(UserSummary::class)
    .where(User_.active eq true)
    .resultList

// ✅ With joins — chained API uses .innerJoin<A>().on<B>(), not the two-type-arg form
select(UserSummary::class)
    .innerJoin<UserRole>().on<User>()
    .where(UserRole_.role eq role)
    .scroll(Scrollable.of(User_.id, 20))
```

**What `select(ResultType::class)` is for:** It selects a different result type from the query. This works for:
- **Joined entity types** — e.g., selecting `City::class` from a `User` query that joins `City`.
- **Custom SELECT with template** — e.g., `select<Summary, _, _> { template }` with a custom SQL select clause that maps to the result type's fields.

It does **not** work for selecting a column subset of the root entity — e.g., a `UserSummary` with only `id` and `name` from `User` will fail with "Cannot find alias for column." For column subsets, use a `Projection<T>` with `ProjectionRepository`.

**Cross-entity pitfall:** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`). Put the query on the target entity's repository instead.

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

## Result Retrieval

QueryBuilder terminals:
- `.resultList` → `List<R>`
- `.singleResult` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.optionalResult` → `R?` (null if empty, throws if multiple)
- `.resultCount` → `Long`
- `.resultFlow` → `Flow<R>` (lazy, coroutines-based; one open statement, the connection is consume-only while collecting)
- `.resultStream` → `Stream<R>` (lazy, must close after use; same connection rule)
- `.windows(size)` / `.windows(scrollable)` → `Flow<Window<R>>` (keyset windows over the primary key, one closed statement per window; the loop may query, fetch refs and write between windows; nothing to close)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(scrollable)` → `Window<R>` (keyset scrolling — do NOT combine with `orderBy()`, see Keyset Scrolling section). Use `next()` / `previous()` for programmatic navigation, or `nextCursor()` / `previousCursor()` for REST APIs.
- `.executeUpdate()` → `Int` (for DELETE/UPDATE)

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value (or use the `select { }` DSL which handles this automatically).
- Multiple .where() calls are AND-combined.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: `select().resultFlow` returns a Flow with automatic cleanup. While rows remain to be emitted the flow is one open statement and its connection is consume-only: inside `transaction { }` a query, `Ref.fetch()` or write from the collector throws `PersistenceException`, on every database. For a loop that reads or writes per row use `select().windows(size)` (`Flow<Window<R>>`): one closed statement per window, the connection is free between windows, write per window with `update(window.content().map { ... })`. There are no `selectBy` methods that return Flow/Stream directly -- always use `select()` (optionally with predicate or block DSL) and then `.resultFlow`, `.resultStream` or `.windows(size)`.
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
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Without it, paths resolve relative to the test class's package.
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class UserQueryTest {
    @Test
    fun findActiveUsersInCity(orm: ORMTemplate, capture: SqlCapture) {
        val city = orm.entity<City, _>().getById(1)   // getById is ID-based — use the two-type-arg form
        val users = capture.execute {
            orm.entity<User>().select()
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

Keep the verification loop on H2: it answers in milliseconds and needs no Docker, which is what makes verify-and-fix iterations cheap. Escalate to the target database only when H2 cannot run the SQL involved (dialect-specific functions, JSON operators, upsert or sequence syntax the target database defines differently), when the user asks for it, or as a final pass before finishing a larger piece of work: adding `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`, from `st.orm.test.TestDatabase`) to `@StormTest` runs the same test in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. Nothing else in the test changes; the class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

`SqlCapture` answers whether a query is correct. To find which query is expensive in a running application, raise the `st.orm.sql.perf` logger and read the per-call summary: it ranks statements by total time and shows which ones repeat, which resolve references on demand, and which hydrate more graph than they use. See the repository skill for how to act on each signal.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
