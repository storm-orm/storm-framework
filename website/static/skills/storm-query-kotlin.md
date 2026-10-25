---
name: storm-query-kotlin
description: Write Storm queries in Kotlin with the QueryBuilder, covering joins, infix predicates, ordering, pagination, keyset scrolling, and Ref navigation. Use for any read query in Kotlin.
---

Help the user write Storm queries using Kotlin.

**Write queries with Storm's QueryBuilder and infix predicate operators, never JPQL, `CriteriaBuilder` or `EntityManager.createQuery()`.** Storm can run on top of JPA, but its queries go through its own API.

## Key Imports

```kotlin
import st.orm.template.*                         // QueryBuilder, eq, neq, like, ref, orm, etc.
import st.orm.repository.*                       // findBy, findAllBy, insert, writeSet, ... (wildcard: see below)
import st.orm.Operator.*                         // escape hatch: only when the operator is chosen at runtime
import st.orm.Ref                                // Lazy-loaded reference
import st.orm.Page                               // Offset-based pagination result
import st.orm.Pageable                           // Pagination request
import st.orm.Scrollable                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window                             // Keyset scrolling result (Window<R>)
import org.junit.jupiter.api.Assertions.*        // assertEquals, assertTrue, assertFalse
```

**Import both packages with wildcards: `st.orm.template.*` and `st.orm.repository.*`.** Naming them individually is how one ends up missing.
- `st.orm.template.*` brings all infix operators and the `ref()` / `orm` extensions. The infix predicate operators (`eq`, `neq`, `like`, `contains`, `startsWith`, `endsWith`, `eqIgnoreCase`, `containsIgnoreCase`, `greater`, `less`, `inList`, `isNull`, `isNotNull`, `isTrue`, `isFalse`, `between`, etc.) are extension functions on `Metamodel<T, V>` defined in `st.orm.template` (in QueryBuilder.kt).
- Repository operations are top-level extensions in `st.orm.repository`, so `import st.orm.template.*` alone does not bring them in.
- The `select { }` / `update { }` / `delete { }` block DSL and the `and` / `or` combinators are member functions, so they need no import.

**Read a compiler crash on a call with `_` placeholders as a missing import.** A call that uses `_` type-argument placeholders punishes a missing import hardest. On Kotlin 2.0.x the compiler crashes while reporting the resolution failure rather than naming the missing import:

```
org.jetbrains.kotlin.util.FileAnalysisException: ... Unexpected FirPlaceholderProjectionImpl
```

That crash is a Kotlin 2.0.x compiler bug, fixed in 2.1.0: an unresolved call using `_` placeholders is reported as an internal error instead of a diagnostic. On 2.1.0+ the same code says `Unresolved reference 'select'`. Read it as "something here does not resolve", and check the import first.

Ask what data they need, filters, ordering, or pagination.

**Repository rule: all database queries live in repository interfaces, not inline in services or other classes.** In Spring Boot or Ktor projects, repositories are constructor-injected (see /storm-repository-kotlin). Use `orm.entity(User::class)` and `orm.repository<UserRepository>()` lookups only in standalone (non-DI) contexts and tests.

**Layering rule: follow the codebase's existing convention first.**
- If handlers already use repositories directly, or a service layer is consistently in place, match that style rather than introduce a competing one.
- Absent a clear stance (new code, greenfield), promote controller → service → repository. Controllers never inject repositories: all data access flows through services, which own the transaction boundaries and return view-model types.
- Never mix styles. Layer-skipping controllers undermine the service layer's cross-cutting concerns (transactions, caching, authorization).

## API Design: Builder Methods vs Convenience Methods

Repository/entity methods fall into two categories.

**Builder methods** return `QueryBuilder` for composable, chainable queries. They never execute immediately:
- `select()`, `select(predicate)`, `select { }`: build SELECT queries
- `selectRef()`, `selectRef(predicate)`: build SELECT queries returning Refs
- `selectCount()`: build COUNT queries
- `update()`, `update { }`: build UPDATE queries that `set` columns on the rows a condition selects
- `delete()`, `delete(predicate)`, `delete { }`: build DELETE queries

Terminal operations: `.resultList`, `.singleResult`, `.optionalResult`, `.resultFlow`, `.resultStream`, `.windows(size)`, `.resultCount`, `.resultGroupedBy(path)`, `.resultGroupedByRef(path)`, `.page()`, `.scroll()`, `.executeUpdate()`

**Load one-to-many with `resultGroupedBy(path)`.** It groups results by a related record, typically the parent entity of a foreign key, into an unmodifiable, insertion-ordered `Map<Parent, List<T>>`.
- The same select runs (single query, no SQL change); the grouping happens during hydration.
- Repeated parents are materialized once and grouped by instance identity, so the join's duplication is not paid during hydration.
- The grouping does not depend on row order: a parent collects its children wherever they fall in the result set.
- Without an `orderBy()` the map and its lists follow the order the database returns. Order by what you want ordered, not by the parent path: `orderBy(User_.city.name, User_.email)` gives the cities alphabetically with their users by email.
- The path must resolve non-null for every result.
- `resultGroupedByRef(path)` returns `Map<Ref<Parent>, List<T>>` with keys compared by primary key. For eager entity paths the keys are loaded refs (`getOrNull()` returns the already-materialized record). For `Ref` foreign-key fields it groups on the foreign key without fetching the parent; `findAllByRef(map.keys)` fetches the parents in one query when needed.

```kotlin
// Load cities with their users in one query
val usersByCity: Map<City, List<User>> = orm.entity(User::class)
    .select()
    .resultGroupedBy(User_.city)
```

**Convenience methods** execute immediately and return results directly:
- `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findBy()`, `findAllBy()`, `getById()`, `getByRef()`, `getBy()`, `count()`, `exists()`, `remove()`, `removeById()`, `removeByRef()`, `removeAll()`, `removeAll(predicate)`, `removeAllBy()`, `page()`, `pageRef()`, `scroll()`

**`remove` acts on what you hold; `delete` builds a query.** `remove` operates on entities or ids you already have and executes immediately. `delete` builds a query that finds and deletes rows by criteria and returns `QueryBuilder`. Updates split the same way: `update(entity)` writes an entity you hold, and the `update()` builder sets columns on the rows a condition selects (see **Bulk DELETE/UPDATE**).

**Prefer the simplest approach that works.** Four query levels, from simplest to most powerful:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`find`, `findAll`, `removeAll`, `count`, `exists`) | Simple lookups and operations |
| 2 | Builder with predicate (`select(predicate)`, `delete(predicate)`) | Filtered queries needing ordering, pagination, or joins |
| 3 | Block DSL (`select { }`, `update { }`, `delete { }`) | Complex queries with multiple joins and conditions |
| 4 | SQL Templates (/storm-sql-kotlin) | CTEs, window functions, database-specific features |

### When to use each, and when NOT to

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
| Set columns on rows not read | `update { set(...); where(...) }` | a second, partial entity for the table, or an `orm.query { "UPDATE ..." }` template |
| Parents with their children (one-to-many) | `select().resultGroupedBy(parentPath)` | per-parent queries in a loop (N+1) or manual `groupBy` after `resultList` |
| Filtered + **ordering/pagination** | `select(predicate).orderBy(...).resultList` | convenience methods (can't add ordering) |
| Filtered + **joins** | `select { }` or `select().innerJoin(...)` | convenience methods (can't add joins) |
| Filtered + **streaming** | `select(predicate).resultFlow` (consume-only) or `.windows(size)` (loop may query/write; `.rows()` flattens it) | convenience methods (return List, not Flow) |
| Aggregates, CTEs, window functions | SQL Template (/storm-sql-kotlin) | QueryBuilder (can't express these) |

**Escalate only when the simpler level cannot express what you need.** If you need ordering, you need at least Level 2. If you need joins, you need Level 2 or 3. If you need CTEs or window functions, you need Level 4.

**Level 1: convenience methods** (execute immediately, no terminal needed):
```kotlin
val user = orm.find(User_.email eq email)
val users = orm.findAll(User_.city eq city)
val exists = orm.existsBy(User_.email, email)
val removed = orm.removeAll(User_.postalCode.isNull())
```

**Level 2: builder with predicate** (returns `QueryBuilder`; chain ordering, pagination and a terminal):
```kotlin
val users = users
    .select(User_.city eq city)
    .orderBy(User_.name)
    .resultList
```

Alternatively, use `select()` chained with `.where()`, which is equivalent, just a style preference:
```kotlin
val users = orm.entity(User::class)
    .select()
    .where((User_.city eq city) and (User_.birthDate less LocalDate.of(2000, 1, 1)))
    .orderBy(User_.name)
    .resultList
```

Builder quick reference:
- Compound filters: `(A eq x) and (B eq y)`, `(A eq x) or (B eq y)`.
- Nested paths: `User_.city.country.code eq "US"`.
- Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`.
- Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`. Page API methods are Java record accessors, so always call them with `()`: `page.content()`, `page.totalPages()`, `page.totalCount()`, `page.pageNumber()`, `page.pageSize()`, `page.hasNext()`, `page.hasPrevious()`, `page.next()`.
- Scrolling (keyset, better for large tables): `.scroll(Scrollable.of(User_.id, 20))`. Never combine it with `orderBy()`: the `Scrollable` manages ORDER BY (see **Keyset Scrolling**).
- Explicit joins take two syntax forms depending on context:
  - **Block DSL** (inside `select { }`): `innerJoin<UserRole, Role>()`, the reified two-type-arg form, no `.on()`.
  - **Chained API**: `.innerJoin<UserRole>().on<Role>()`. The join widens the query; chain `.where()` for root and joined fields alike.
  - **A condition on the join**: `.leftJoin<Pet>().on<Owner> { Pet_.name eq "Leo" }` adds the condition to the resolved foreign key (block DSL: `leftJoin<Pet, Owner> { Pet_.name eq "Leo" }`). On a left join it keeps the rows without a match, which the same condition in `where` would drop.
  - **Filtering by related rows**: `.semiJoin<Pet>().on<Owner>()` keeps the rows with a related row matching the conditions that name it, each once; `.antiJoin<Visit>().on<Pet> { Visit_.description eq "rabies shot" }` keeps the rows without one (block DSL: `semiJoin<Pet, Owner>()`, `antiJoin<Visit, Pet> { ... }`).
  - **A join named by class** (an aliased `join(JoinType.inner(), Order::class, "recent")`): its `on` takes the class too, `.on(User::class)`. A join and its `on` use the same form.
- Select result type: `.select(ResultType::class)` returns a different type than the root entity.

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** SQL templates are an escape hatch for what the QueryBuilder cannot express. Three rules:

1. **Code-first:** If it can be done with QueryBuilder methods (joins, where, orderBy, groupBy, having), do it in code. Never use a template string for a `WHERE` clause that could be a `.where(predicate)`, or an `ORDER BY` that could be `.orderBy(field)`.
2. **Metamodel in templates:** When you do need a template fragment (e.g., for `COUNT(*)` in a select clause), still use metamodel references inside it (`${User_.email}`, not `"email"`). This keeps column references type-safe and refactor-proof.
3. **Full SQL last resort:** Use a full `SELECT ... FROM ...` SQL template only for totally custom queries (CTEs, UNIONs, window functions) that the QueryBuilder cannot build at all. Even then, use bind variables (`$value`) and metamodel references (`${Entity_.field}`).

**Code-first WHERE clauses: split a mixed condition.** Express WHERE conditions with metamodel-based predicates (`eq`, `isFalse()`, `isNotNull()`, etc.). Fall back to a template expression only for conditions predicates cannot express (e.g., `COALESCE`, date arithmetic, aggregate functions).
- When a WHERE clause mixes expressible and inexpressible conditions, use predicates for what you can and templates only for what you must. Multiple `where()` calls are AND-combined automatically.
- FK paths through the object graph need no explicit join. `User_.city eq city` is the foreign key column and joins nothing; for `User_.city.country.code eq "US"` Storm joins `COUNTRY` on demand.
- This holds for a `Ref` foreign key as well, which is navigable without being hydrated (see **Navigating Through a Ref**).

```kotlin
// ❌ Wrong: WHERE conditions as a template string
fun findWithPostalCodeAndEmail(city: City, minAge: Int): List<User> =
    select {
        where {
            """${User_.city} = ${city.id()}
            AND ${User_.postalCode} IS NOT NULL
            AND ${User_.email} IS NOT NULL
            AND EXTRACT(YEAR FROM CURRENT_DATE) - EXTRACT(YEAR FROM ${User_.birthDate}) >= $minAge"""
        }
    }.resultList

// ✅ Correct: code-based predicates where possible, template only when no alternative exists
fun findWithPostalCodeAndEmail(city: City, minAge: Int): List<User> =
    select {
        where((User_.city eq city) and (User_.postalCode.isNotNull()) and (User_.email.isNotNull()))
        where { "EXTRACT(YEAR FROM CURRENT_DATE) - EXTRACT(YEAR FROM ${User_.birthDate}) >= $minAge" }
    }.resultList
```

Write template lambdas with `${}` interpolation (the compiler plugin handles parameter binding automatically), never `TemplateString.raw()` or `${t(...)}` manually.

**Template joins are a code smell: fix the entity.** A template ON clause (`.innerJoin<T>().on { "..." }`) or a full `orm.query { }` for a join that follows a database FK constraint means the entity model is missing an `@FK` annotation. Add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields); the join becomes `.innerJoin<Entity>().on<OnEntity>()`, pure code. A template join is justified only when the database has no FK constraint.

**Projections join like entities.** `.on<ProjectionType>()` resolves the foreign key by matching the referenced entity's table against the projection's table. When several foreign keys reference that table the join is ambiguous: Storm fails with an error naming the candidate fields. Disambiguate with a template ON clause.

## Kotlin Infix Predicate Operators

All operators are extension functions on `Metamodel<T, V>` (generated metamodel fields like `User_.name`):

```kotlin
User_.name eq "Alice"              // EQUALS
User_.name neq "Bob"               // NOT_EQUALS
User_.age greater 18               // GREATER_THAN
User_.age greaterEq 21             // GREATER_THAN_OR_EQUAL
User_.age less 65                  // LESS_THAN
User_.age lessEq 30                // LESS_THAN_OR_EQUAL
User_.name like "%alice%"          // LIKE
User_.name notLike "%test%"        // NOT_LIKE
User_.name contains "50%"          // CONTAINS: literal text, never a pattern
User_.name startsWith "Al"         // STARTS_WITH
User_.email endsWith "@example.com" // ENDS_WITH
                                   // notContains, notStartsWith, notEndsWith negate them
User_.name containsIgnoreCase "ali" // CONTAINS_IGNORE_CASE: literal text, any case, on every database
User_.email eqIgnoreCase email     // EQUALS_IGNORE_CASE
                                   // startsWithIgnoreCase, endsWithIgnoreCase, neqIgnoreCase and the not...IgnoreCase forms
User_.roles inList listOf("a","b") // IN
User_.roles notInList listOf("x")  // NOT_IN
User_.city inRefs cityRefs         // IN over Iterable<Ref<T>>, for FK fields with refs
User_.age.between(18, 65)          // BETWEEN
User_.archived.isTrue()            // IS_TRUE
User_.archived.isFalse()           // IS_FALSE
User_.email.isNull()               // IS_NULL
User_.email.isNotNull()            // IS_NOT_NULL
```

Combine with `and`/`or`:
```kotlin
(User_.email like "%@example.com") and User_.email.isNotNull()
(User_.role eq "admin") or (User_.role eq "superadmin")
```

Compound predicates composed this way stay inside plain `where(...)`. Never reach for `whereBuilder { }` to group AND/OR (see **Choosing the WHERE Form**).

**Compare a foreign key with the entity or a `Ref`, never its id.** The `eq` operator accepts both entities and `Ref<T>`. When you have an entity, use it directly, without extracting the ID or converting it to a `Ref`. When you only have an ID (e.g., from a URL parameter), create a `Ref` instead of a dummy entity:
```kotlin
User_.city eq city          // ✅ entity directly: compares by FK
User_.city eq city.ref()    // also works, but unnecessary when you have the entity
// Same pattern works for any FK field: always pass the entity or Ref, not the raw ID

// When you only have an ID (e.g., from a URL parameter), create a Ref:
User_.city eq refById<City>(cityId)   // ✅ import st.orm.template.refById
// ❌ Don't construct a dummy entity: City(id = cityId, name = "", ...)
```

**Pass the whole list to `inList`.** Never chunk ids by hand, build one query per key, or write the list into a template to control the shape.
- On a database that binds a list as one array parameter (`id = ANY(?)`), `inList`, `findAllById` and `whereId` render one statement whatever the list's length. The database plans and caches one statement for a varying number of keys, faster than a placeholder per value.
- Elsewhere each value has a placeholder.
- Where a database's plan cache fills with the different lengths, `storm.in_list` set to `padded` renders a placeholder per value, padded to the next power of two. It changes no result, and it forgoes the array parameter where the database has one, which is faster.
- The `in-list-shapes` advice says when to set it. It is reported once a statement has run with eight different list lengths: in the advice log in development, on a slow line in production.
- `storm.in_list = exact` keeps a placeholder per value everywhere.

**Search text goes through `contains`, `startsWith` or `endsWith`, never through `like`.** Text a user typed can hold `%`, `_`, `[` or a backslash, which `like` reads as wildcards or escapes, differently per database. The text operators escape it for the dialect and add the wildcards themselves, so `User_.name contains query` matches exactly what was typed.
- Never build a pattern such as `like "%$query%"`, and never write an escape helper.
- Keep `like` for patterns the code writes itself.
- The text operators apply to a single `String` column. Case sensitivity follows the column's collation, as it does for `like`.

**Text compared whatever its case goes through the `IgnoreCase` operators, never through a `LOWER(...)` template.** `eq`, `contains`, `startsWith` and `endsWith` follow the column's collation. A database whose default collation ignores case answers them differently from one whose collation does not.

`eqIgnoreCase`, `containsIgnoreCase`, `startsWithIgnoreCase` and `endsWithIgnoreCase`, with `neqIgnoreCase` and the `not...IgnoreCase` forms, have the database lower both sides. The text ones still match their text literally.
- A search box and a lookup by email address or user name want these. A code, a token or an id compared exactly wants `eq`.
- They ignore case only. Accents follow the column's collation: ignored on a database whose default collation ignores accents, compared as they are elsewhere.
- A plain index does not serve the lowered column. Where an index must serve the lookup, index the lowered expression, or keep the plain operator on a column whose collation already ignores case.

### Search

**A search box goes through `search`, never through hand-built `containsIgnoreCase` chains.** `.where(search(input, Movie_.title, Movie_.director.name))` takes the input as the box receives it. It returns a predicate, so it combines with `and` and `or`.
- Whitespace separates the words, and a quoted phrase is one word.
- Every word must occur in at least one of the columns, ignoring case, literally.
- An empty input matches every row.
- Never split the input, `reduce` over the words or special-case the empty box in application code.
- To search the rows related to each row, such as an owner's pets, semi-join the related table and name its column: `.semiJoin<Pet>().on<Owner>().where(search(input, Owner_.lastName, Pet_.name))`. Each word may then match a different related row, and every owner comes back once.

**A search box that queries as the user types goes through `suggest`, not `search`.** An auto-complete, a typeahead or any box that fetches on every keystroke uses `.where(suggest(input, Movie_.title, Movie_.director.name))`. It takes the same input and paths and matches the same way, on the words the database's substring index serves alone.
- A word shorter than the index serves (three characters for a trigram index) is ignored.
- An input without a longer word, an empty one included, matches no rows, so the index serves every statement it renders however the user types.
- The search page the box leads to keeps `search`, which matches every word.
- Never filter short words or an empty input out in application code: `suggest` does it per dialect.
- A word the index serves but which occurs in a large share of the rows, such as `the` in a catalog of titles, is the box's to name. `suggest(input, setOf("the", "a", "of"), Movie_.title)` ignores those words as it ignores short ones, compared ignoring case: `the god` matches on `god` alone, and `the` alone matches no rows.
- Keep that set a constant next to the box. Never strip the words from the input in application code, and never pass the set to `search`, which takes none.

**Rank search results with `orderFirst`, never with a `CASE` template.** `search` and `suggest` decide which rows match, not which come first. `orderFirst(predicate)` puts the rows matching the predicate first and appends a sort key as `orderBy` does, so the calls apply in order. This lists an exact title, then titles starting with the input, then the most popular:
```kotlin
.orderFirst(Movie_.title eqIgnoreCase input)
.orderFirst(Movie_.title startsWithIgnoreCase input)
.orderByDescending(Movie_.votes)
```
- The operators match the input literally and ignoring case, as in `where`. Combine columns with `or`.
- Rank only by columns of the queried or joined tables. A semi-joined table adds no columns, so its columns are refused.
- A keyset `scroll` follows `orderFirst`: its keys lead the `Scrollable`'s sort fields and key, and the cursor carries the ranks. A `Scrollable` may sort by a field of a joined table, such as the votes of a joined rating.
- `orderFirst` cannot order a `distinct` query.
- No index serves the sort, which is fine after a search's few matches.

**A search needs an index built for it, created in the migration with the query.** `search` and the `IgnoreCase` text operators compare `LOWER(column) LIKE LOWER('%word%')`: a lowered expression with a leading wildcard, which no B-tree index serves. Without an index for it, each search reads and lowers every row of every table it names. That goes unnoticed on test data and takes seconds on a production-sized table.
- Where a searched table can grow beyond a few thousand rows, add the target database's substring index (a trigram or n-gram index) to the migration. Build it over exactly `LOWER(column)`, one per searched column, including the columns of a semi-joined table or of a table reached through a path.
- An index on the plain column does not match the lowered expression.
- A word shorter than the index's gram (usually three characters) cannot use it. `search` tests such a word only on the rows its longer words found, so a search of short words alone still reads every row; the slow log (`st.orm.sql.slow`) adds advice to that statement's line.
- Where the database has no substring index, say so, and narrow the rows with a selective predicate instead.

**Confirm in the database's query plan that each table is read through its index.** Check the statement as the application sends it: with the word as a bind parameter, after the driver has prepared it, not with the word written into the SQL.
- A prepared statement may be planned once for every value. That plan cannot see the word, so a search ordered by an indexed column with a small limit, such as a list of suggestions, walks that column's index testing every row. Where that happens, configure the driver or database to plan each execution for its values.
- After creating the index on a table that holds data, refresh the table's statistics. Where a rare word is still estimated to match many rows, raise the statistics sample of the indexed expression.

**Never speed a search up by changing its predicate.** A case-sensitive operator reads every row as well and changes what matches. An `ILIKE` or a `LOWER(...)` template reads every row just the same.

**Whether accents count is the schema's decision, never the application's.** The search follows the column's collation, which ignores accents on some databases by default and compares them on others. Where a search must ignore accents on a database that compares them, the database removes them through a function an index is built on, and the query names that function with `SqlFunction`:
- The migration wraps the database's accent-removing function in `fold_accents(text)`, marked immutable so the database accepts it in an index where the original function is not immutable. It indexes `LOWER(fold_accents(column))`.
- The repository declares `val foldAccents = SqlFunction.of<String>("fold_accents")` once, with an extension property that reads as a field of the column: `val <T : Data> Navigable<T, String>.folded: Metamodel<T, String> get() = foldAccents(this)`.
- The query searches `search(input, Movie_.title.folded)` and ranks with `orderFirst(Movie_.title.folded eqIgnoreCase input)`.

**The database folds both sides; the application folds neither.** A comparison applies the function to the column and to the input.
- Never remove accents from the input in application code or in a query of its own.
- Never add an entity field holding the folded text.
- Never wrap the column in an accent function inside a template, which the search operators cannot use.
- Name exactly the function the index is built on; another expression is not served by it.
- A `Scrollable` refuses a function call as key or sort field.

**Store the folded text in a generated column where the function's recheck dominates.** That shows on a column where a common word matches many thousands of rows, since the database rechecks the function on every candidate row.
- Store it as `title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(title)) STORED NOT NULL` and index `LOWER(title_folded)`.
- Declare it as a query column on the entity that names the function: `@QueryColumn(field = "titleFolded", type = String::class, function = "fold_accents")`. No row carries it (see the entity skill), and a value compared with it passes through the function on the value side.
- `search(input, Movie_.titleFolded)` renders `LOWER(m.title_folded) LIKE LOWER(fold_accents(?))`: the column as stored, and the input folded by the database in the same statement.

### Naming a foreign key column

**Name a foreign key by the field itself, never by `.id`.** A foreign key field already resolves to the foreign key column, so `User_.city` and `User_.city.id` are the same column and neither joins the target table. `.id` reads as a navigation step into `City` and suggests a join that is not there. The rule holds everywhere a column is named: predicates, template select lists, `groupBy`, `orderBy`, and aggregate expressions.

```kotlin
data class CityUserCount(val city: Ref<City>, val userCount: Long)

// ✅ The foreign key field names the foreign key column
orm.entity(User::class)
    .select(CityUserCount::class) { "${User_.city}, COUNT(*)" }
    .groupBy(User_.city)
    .resultList

// ❌ Redundant `.id`: identical SQL, and it reads like a join into City
orm.entity(User::class)
    .select(CityUserCount::class) { "${User_.city.id}, COUNT(*)" }
    .groupBy(User_.city.id)
    .resultList

COUNT(DISTINCT ${User_.city})       // ✅ inside a template
COUNT(DISTINCT ${User_.city.id})    // ❌ same column, longer
```

**Filter on a foreign key with the refs themselves.** Use `inRefs` rather than unwrapping refs into ids to compare against the same column.

```kotlin
User_.city inRefs cityRefs                            // ✅ IN over refs
User_.city.id inList cityRefs.map { it.entityId() }   // ❌ unwraps refs to reach the column they already name
```

**A compound foreign key is several columns, and templates take only one.** A foreign key to an entity whose `@PK` is an inline record is a multi-column foreign key: Storm matches its columns to the target's primary key columns one for one. Code-first operators expand it. A template placeholder stands for a single column, so interpolating one throws `SqlTemplateException: Expected exactly one column for metamodel: …` before any SQL reaches the database.

```kotlin
// ✅ Code-first expands the compound key
.where(Order_.line eq orderLine)   // WHERE o.order_id = ? AND o.line_no = ?
.groupBy(Order_.line)              // GROUP BY o.order_id, o.line_no: same columns, no join

// ❌ Template placeholders reject it: SqlTemplateException, not broken SQL
"COUNT(DISTINCT ${Order_.line})"
"${Order_.line}, COUNT(*)"
"sub.line_id = ${Order_.line}"
```

- The naming rule holds for single-column foreign keys, the overwhelmingly common case.
- A compound key cannot appear in a template expression at all. Name one of its leaves (`Order_.line.lineNo`, which joins the referenced table) or restructure the query to the code-first form.
- `Order_.line.id` and `Order_.line` resolve to the same foreign key column(s) on the referencing table, without a join, for `Ref` and entity foreign keys alike.
- The same single-column assumption is why keyset scrolling rejects a composite key (see **Keyset Scrolling**).

## Aggregation

```kotlin
val userCount = orm.entity(User::class).selectCount().singleResult

val citySummaries = orm.entity(City::class)
    .select(CitySummary::class)
    .groupBy(City_.country)
    .having(City_.population greater 100000)
    .resultList
```

**HAVING takes predicates too.** `having(predicate)` accepts the same infix operators as `where(predicate)`, so a condition on a grouped column stays in code.
- That includes a disjunction, which consecutive `having()` calls cannot express because they are AND-combined.
- A joined entity's column goes through the same `having(predicate)` once the query has the join.
- A condition on an *aggregate* still needs a template, since no metamodel path names a computed value.

```kotlin
.having((City_.country eq nl) or (City_.country eq be))   // grouped column: predicate
.having { "COUNT(*) > ${minimum}" }                       // aggregate: template
```

**Computed aggregates (COUNT, AVG, SUM, etc.) go in a SELECT template only.** When the SELECT clause needs expressions that QueryBuilder can't produce, use `select(ResultType::class) { template }` for the SELECT only. Keep joins, groupBy, having, orderBy, and limit in code. The result type is a class literal, as in every call where a type argument alone would not give Kotlin the repository's entity and ID types.

**`Data` means "represents a table".** Only types that map to a database table implement `Data` (or its subtypes `Entity`/`Projection`). Ad-hoc query result types, such as aggregation DTOs and computed shapes, are plain data classes with no marker. Storm maps result columns to any suitable data class, including nested entity and `Ref<T>` components.
- Implementing `Data` on a result type pulls it into Storm's type discovery, so schema validation (`validateSchema()` without arguments, or `storm.validation.schema-mode` at startup) fails with `TABLE_NOT_FOUND`, which then requires `@DbIgnore` to suppress.
- `: Data` plus `@DbIgnore` on a result type is an anti-pattern: opting in and immediately opting out. A plain data class expresses the intent directly.
- Define result types next to the code that produces them, typically top-level in the repository file whose queries return them. The model package is reserved for table-backed types.
- Document them as query result shapes (not backed by a table or view), so readers see why they carry no marker.
- For a GraalVM native image, annotate each result type with `@GenerateMetamodel` (`st.orm`). It is not a table marker, so schema validation still skips it: the metamodel processor gives the type an instantiator, and the native-image feature registers it. Without it the native image cannot construct the row, and the first query fails at runtime with `Could not compute caller for function: public constructor SpeciesCount(...)`. On the JVM the annotation is optional: the row is constructed reflectively.

```kotlin
/**
 * Query result shape: the pets of each species. Not backed by a database
 * table or view, so it is a plain data class; @GenerateMetamodel lets a
 * native image construct it.
 */
@GenerateMetamodel
data class SpeciesCount(val species: Species, val pets: Long)
```

**The `{ template }` provides the SELECT list only, not a full SQL query.** A full `SELECT ... FROM ... WHERE ...` inside it is wrapped as a scalar subquery and fails. For full custom SQL, use `orm.query { }.resultList<T>()` (see /storm-sql-kotlin).

```kotlin
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain data class, deliberately not a Data type.
 */
data class CityUserCount(val city: City, val userCount: Long)

val cityCounts = orm.entity(City::class)
    .select(CityUserCount::class) { "${City::class}, COUNT(*)" }
    .leftJoin<User>().on<City>()
    .groupBy(City_.id)
    .resultList

// More complex example with WHERE, HAVING, and ORDER BY, all in code:
data class CityUserStats(val cityName: String, val averageAge: Double, val userCount: Long)

val minUsers = 10
val topCities = orm.entity(City::class)
    .select(CityUserStats::class) { "${City_.name}, AVG(${User_.age}), COUNT(*)" }
    .leftJoin<User>().on<City>()
    .groupBy(City_.name)
    .having { "COUNT(*) >= $minUsers" }               // template form for aggregate expressions
    .orderByDescending { "AVG(${User_.age})" }
    .resultList

// Multi-field groupBy: always use the varargs metamodel form
data class CityPostalCodeCount(val city: Ref<City>, val postalCode: String?, val userCount: Long)

val counts = orm.entity(User::class)
    .select(CityPostalCodeCount::class) { "${User_.city}, ${User_.postalCode}, COUNT(*)" }
    .groupBy(User_.city, User_.postalCode)    // ✅ varargs metamodel form
    .resultList

// ❌ Don't use template lambda when metamodel fields work:
//    .groupBy { "${User_.city}, ${User_.postalCode}" }
// ✅ Use varargs metamodel form, code-first and type-safe:
//    .groupBy(User_.city, User_.postalCode)
```

**Use `Ref<T>` for a foreign key in an aggregation result type.** When the SELECT clause references a FK field (`${User_.city}`) rather than a full entity (`${City::class}`), the property is `Ref<City>`, not the raw ID type and not the full entity; it maps to the FK column value. Use the full entity type only when the SELECT includes all its columns via `${City::class}`.

**Always prefer code over templates.** Templates are for expressions QueryBuilder can't produce (e.g., `COUNT(*)`, `AVG()`).
- `groupBy`, `having`, and `orderBy` accept template lambdas for computed expressions (e.g., `.having { "COUNT(*) >= $min" }`, `.orderByDescending { "AVG(${User_.age})" }`).
- Otherwise **always use the varargs metamodel form for `groupBy`** and **the metamodel form for `orderBy`**.
- Never write the entire query as a raw SQL string.

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

## Optimizer Hints

**Add a hint with `.hint("...")` only when a query needs one to keep its plan.** The text is the database's own hint text, passed through as is, on `select`, `update` and `delete`. Never rewrite a query as a SQL template to place a hint.

```kotlin
val users = orm.entity(User::class)
    .select()
    .where(User_.email contains query)
    .hint("MAX_EXECUTION_TIME(1000)")   // on a database that reads hints from a comment: SELECT /*+ MAX_EXECUTION_TIME(1000) */ ...
    .resultList
```

The dialect places the hint:
- A database that reads hints from a comment after the keyword gets a `/*+ ... */` comment after `SELECT`/`UPDATE`/`DELETE`.
- A database that reads hints from an `OPTION (...)` clause at the end gets that clause. There a hinted builder cannot be a subquery; hint the outer query.
- A database that has no hint syntax runs the query unchanged, so tests on the in-memory database `@StormTest` provides by default pass.
- Several `.hint(...)` calls share one comment or clause.
- A hint containing `*/` or `;` is refused.

## Distinct and Count

**Use `distinct()` to remove duplicates from a select of another type, such as the cities of a set of users.** It is never the way to return each root row once after a join to a to-many table: that is a `semiJoin` (see **Filtering by Related Rows**).

```kotlin
val uniqueCities = orm.entity(User::class)
    .select(City::class)
    .distinct()
    .resultList

val count = orm.entity(User::class)
    .selectCount()
    .where(User_.email like "%@example.com")
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

### Navigating Through a Ref

**A `Ref<T>` foreign key is navigable in queries.** Filter, order, and select through it with the metamodel. Storm joins the referenced table on demand, only for a query that references a column beyond the foreign key, so filtering or ordering on the referenced table needs no explicit join. Selecting the root leaves the field as an unloaded `Ref`.

```kotlin
// User.city is Ref<City>; City has a country FK. The city and country tables are joined
// only because the query navigates beyond the city foreign key.
val users = orm.entity(User::class)
    .select()
    .where(User_.city.country.name eq "United States")
    .orderBy(User_.city.name)
    .resultList

// Select a column from beyond the reference.
data class CountryName(val name: String)
val names = orm.entity(User::class)
    .select(CountryName::class) { "${User_.city.country.name}" }
    .where(User_.city.country.name eq "United States")
    .resultList

// Select the referenced entity itself: the city table is joined on demand and hydrated
// with its own country foreign key.
val cities = orm.entity(User::class).select(City::class).resultList

// Join another table onto the referenced one; the join brings the city table in.
val sharingACity = orm.entity(User::class).select()
    .innerJoin<User>().on<City>()
    .resultList
```

- Nodes beyond a reference are navigation-only: usable in `where`, `orderBy`, `groupBy`, `having`, and selected columns, but not in value operations.
- Group by the reference itself with `resultGroupedByRef`. `resultGroupedBy` on a beyond-reference path does not compile.
- The referenced table can also be named by type: selecting it or joining onto it materializes the same join, and a query that does both joins it once. A table joined explicitly keeps that occurrence.
- Navigation may cross more than one reference across distinct tables.
- A reference carries the target's primary key, so `User_.city` and `User_.city.id` both name the foreign key column without a join (see **Naming a foreign key column**). Any other column of the target joins.
- A self-referential foreign key must be a `Ref`, and it is navigable: the table is joined to itself, each occurrence under its own alias.
- The typed metamodel navigates a cycle two hops deep, because generated metamodels build their children eagerly and cannot recurse. Name deeper cyclic paths as strings, which the engine resolves to any depth.

**Prefer a `Ref` for foreign keys you do not hydrate on most reads.** It keeps SELECTs narrow while staying queryable.

### Resolving a Ref as Part of the Query

**When a read knows it needs the referenced record, resolve it with `select().fetch(path...)`.** `fetch()` on the reference itself resolves on demand and is always correct. `select().fetch(...)` folds that load into the same statement: the referenced table's columns take the place of the foreign key column, so the reference comes back loaded and reading it costs no query. The property stays a `Ref`, so one record type serves queries that resolve it and queries that do not.

```kotlin
val users = orm.entity(User::class).select()
    .fetch(User_.city, User_.city.country)
    .resultList

val city = users.first().city.getOrThrow()   // no query; throws if the plan did not cover it
```

**Read a resolved reference with `getOrThrow()`, not `fetch()`.** Both return the record, but `fetch()` silently queries when the plan did not cover the path. A `fetch(...)` lost in a refactor then degrades into one query per row without failing. `getOrThrow()` never queries and reports the gap where the assumption was made. Use `fetch()` on the ref only where resolving on demand is the intent.

**Assert `getOrThrow()` only where the query that produced the row is in view.** That is the same function, or a repository method whose doc names what it resolves. A function that takes an entity as a parameter cannot see the plan the entity was loaded under, and each caller settles that independently. Read its references with `fetch()`, or take the resolved record as a parameter instead of the entity.

```
isLoaded()      // is the record here?
getOrThrow()    // give it to me, never queries, throws if absent
getOrNull()     // give it to me or null, never queries
fetch()         // resolve now, query if needed
fetchOrNull()   // same, null instead of throwing
```

The fetch plan:
- It is prefix-closed: naming `User_.city.country` resolves `User_.city` too, so write only the deepest path.
- A reference the plan does not name stays a foreign key column.
- A reference is always a to-one foreign key, so resolving one widens the row without multiplying it.
- A cycle is bounded by the depth named: `fetch(Node_.parent.parent)` is exactly two levels.
- A nullable reference keeps its outer join, so a null foreign key yields a null reference.
- Paths that cross no reference, references to sealed types, and queries that do not select a record are rejected with a descriptive error.

A `Ref` describes a relationship that belongs to particular queries rather than to the entity itself, and this is where such a query asks for it. See the entity skill for which relationships to declare each way.

## Filtering by Related Rows: Path, semiJoin, antiJoin or Join

Pick the form by the direction of the relation and by whether the related rows belong in the result:

| The query needs | Use |
|-----------------|-----|
| A column of a row the root references (to-one: a pet's owner) | A path: `Pet_.owner.lastName eq "Davis"`, no join |
| The rows **with** a related row matching a condition (to-many: owners of a pet named Leo) | `semiJoin<Pet>().on<Owner>()` with the condition in `where` |
| A search over the row and its related rows (owner name and pet names) | `semiJoin<Pet>().on<Owner>()` with `search(input, Owner_.lastName, Pet_.name)` |
| The rows **without** a related row matching a condition (pets that never had a rabies shot) | `antiJoin<Visit>().on<Pet> { Visit_.description eq "rabies shot" }` |
| The rows with any related row (owners with a pet) | `semiJoin<Pet>().on<Owner>()` |
| The rows with a related row of a related row (owners with a pet that had a visit this year) | `semiJoin<Pet>().on<Owner>()` then `semiJoin<Visit>().on<Pet>()`, the condition in `where` |
| The rows without any related row (owners without pets) | `antiJoin<Pet>().on<Owner>()` |
| The related rows' columns in the result, or ordering and grouping by them, a row per related row | `innerJoin<Pet>().on<Owner>()` or `leftJoin` |
| Related columns joined while keeping the rows without a match | `leftJoin<Pet>().on<Owner> { Pet_.name eq "Leo" }` |

**Read the request for the cue.** "with", "that have", "who own", "any of their", "search by ... or their ..." over a to-many relation asks for `semiJoin`. "without", "never", "no", "missing", "not yet" asks for `antiJoin`. A semi-join or anti-join returns each root row once, so it needs no `distinct`, and scrolling, paging and `resultCount` count root rows.

- `semiJoin` conditions go in `where`. Conditions joined by `and` hold for the same related row, and `search` matches each word on its own related row. A part naming only the query's own tables holds without a related row, so `(Owner_.lastName eq "Davis") or (Pet_.name eq "Leo")` also finds a Davis without pets.
- `antiJoin` conditions go on the join, never in `where`: a `where` naming the anti-joined table is refused.
- A semi-joined or anti-joined table adds no columns, so ordering, grouping or selecting by it is refused. When the result, `orderBy` or `groupBy` needs its columns, the query wants a regular join instead.
- Both filter `delete()` and `update()` as they filter a select. `owners.delete().antiJoin<Pet>().on<Owner>()` removes the owners without pets. `owners.update().set(Owner_.telephone, null).semiJoin<Pet>().on<Owner>().where(Pet_.name eq "Leo")` clears the telephone of the owners of a pet named Leo.
- `semiJoin` and `antiJoin` are complements: on the same table with the same condition on the join, the one keeps the rows the other drops. Without a condition, `semiJoin` keeps the rows with any related row and `antiJoin` the rows without one.
- Two semi-joins or anti-joins on one table are two subqueries, each with its own condition on the join: `antiJoin<Visit>().on<Pet> { Visit_.description eq "rabies shot" }.semiJoin<Visit>().on<Pet>()` finds the pets with a visit but no rabies shot, and two `semiJoin`s with different conditions match a related row each. A `where` naming the table is then refused: each condition goes on its join.
- Keep `whereExists` for a subquery a foreign key does not express.

**Through two to-many relations, chain the semi-joins.** `on` names a semi-joined table, so `.semiJoin<Pet>().on<Owner>().semiJoin<Visit>().on<Pet>().where(Visit_.visitDate greaterEq date)` finds the owners with a pet that had a visit since the date, each once.
- The visit's `EXISTS` nests in the pet's, and conditions joined by `and` on `Pet` and `Visit` hold for the same pet.
- Through a link table, `on` names the table the link row points at: `.semiJoin<PetSitting>().on<Owner>().semiJoin<Visit>().on<Pet>()` relates the visit to the link's `pet_id` without reading the pet table.
- An `antiJoin` on a semi-joined table nests as `NOT EXISTS` (owners with a pet that never had a visit).
- A semi-join on an anti-joined table takes its condition on the join.

**Prefer a path over a join.** An explicit join is not how you filter on a related table. When the relationship is a foreign key, name the column through it and Storm joins the referenced table itself, for `Ref` foreign keys as much as for eager ones. A hand-written join names the same occurrence, so it gains nothing over the path alone:

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

Reach for an explicit join when a path cannot express the query:
- a relationship the entity model does not carry as a foreign key;
- the to-many side of one (a path navigates to-one only);
- a self-join that needs its own alias;
- a join whose entity you want the query widened to rather than filtered by.

**Filter by the to-many side with `semiJoin` or `antiJoin`, never with a join and `distinct`.** A join to the many side returns a row per related row, which takes `distinct` to undo, and a `DISTINCT` query can only order by the columns it selects.

```kotlin
// ✅ Owners of a Leo, each once
owners.select()
    .semiJoin<Pet>().on<Owner>()
    .where(Pet_.name eq "Leo")
    .resultList

// ❌ A row per matching pet, folded back with distinct
owners.select()
    .distinct()
    .innerJoin<Pet>().on<Owner>()
    .where(Pet_.name eq "Leo")
    .resultList

// ✅ Pets without a rabies shot: the anti-join's condition goes on the join, never in where
pets.select()
    .antiJoin<Visit>().on<Pet> { Visit_.description eq "rabies shot" }
    .resultList
```

**Recognize the pattern wherever the join hangs.** A select of the query's own table with `distinct()` and an inner join whose columns the result, the ordering and the grouping never use is this pattern, whatever table the join is on.
- `on<T>()` names any table of the query, including one the root reaches through a path. `semiJoin<Related>().on<T>()` replaces `.distinct().innerJoin<Related>().on<T>()` one for one, without the `distinct`.
- A chain of inner joins through two to-many relations with `distinct`, such as owner to pet to visit, or a link table and the table hanging off the row it points at, is the same pattern. Each join becomes a semi-join on the table before it: `.semiJoin<Pet>().on<Owner>().semiJoin<Visit>().on<Pet>()`.
- A comment calling the `distinct` required because of the join marks the same pattern, not an exception.
- When a change touches such a query for another reason, replace it in the same change.

## Subqueries (EXISTS / NOT EXISTS)

**Use `whereExists { }` / `whereNotExists { }` for an EXISTS condition of its own.** The lambdas receive a `SubqueryTemplate` that provides the `subquery()` method. The subquery is automatically correlated with the outer query. For a condition on the related rows, `semiJoin` and `antiJoin` are the typed form (see **Filtering by Related Rows**).

```kotlin
// WHERE EXISTS: filter entities that have related data
val citiesWithUsers = orm.entity(City::class)
    .select()
    .whereExists { subquery<User>() }
    .resultList

// WHERE NOT EXISTS
val citiesWithoutUsers = orm.entity(City::class)
    .select()
    .whereNotExists { subquery<User>() }
    .resultList
```

## Choosing the WHERE Form: where() → whereBuilder { }

**Always use `where(predicate)` unless the condition needs the builder scope itself.** Two forms build a WHERE clause:

1. **`where(predicate)`**: the default, for every field condition. Compound conditions stay at this level, composed with infix `and`/`or`. Before a join the chained builder is typed to the root entity, and a nested path that starts at the root (`User_.city.country.code eq "US"`) is root-typed however deep. **A join widens the query**: from the join onward, `where()` (and `orderBy`, `groupBy`, `having`) accepts paths from any entity in the query (see **Joined-Entity Predicates and Ordering**).
2. **`whereBuilder { }`**: only when the condition needs the builder scope itself: `exists()`/`notExists()` composed into compound logic, or id/ref/record/template matching (`whereId`, `whereRef`, `where(record)`, `where { template }`) inside a compound expression. Never for plain field predicates, and never for AND/OR grouping: infix operators inside `where()` already do that.

```kotlin
// ✅ Compound predicates belong in where(): infix and/or, parenthesized per group
val users = orm.entity(User::class)
    .select()
    .where(((User_.email like "%@example.com") and User_.email.isNotNull()) or (User_.role eq "admin"))
    .resultList

// ❌ whereBuilder adds nothing here: same query, more machinery
val users = orm.entity(User::class)
    .select()
    .whereBuilder {
        where(User_.email, LIKE, "%@example.com")
            .and(where(User_.email, IS_NOT_NULL))
            .or(where(User_.role, EQUALS, "admin"))
    }
    .resultList
```

A chain reads left to right, each call grouping what came before it: the example above is `(email LIKE ... AND email IS NOT NULL) OR role = 'admin'`. With the infix functions, parentheses group as written: `((a or b) and c)`.

**Consecutive `where()` calls AND together**, each clause parenthesized. The infix `and`/`or` combinators root a combination at the operands' least common root, so a cross-entity AND works as two `where()` calls or as one compound clause on a widened builder. A narrow, join-less builder rejects the cross-root compound at the call site:

```kotlin
users.select()
    .innerJoin<UserRole>().on<User>()     // the join widens the query
    .where(User_.email like "%@example.com")          // root field
    .where(UserRole_.role eq role)        // joined entity: same call
    .resultList

users.select()
    .innerJoin<UserRole>().on<User>()
    .where((User_.email like "%@example.com") and (UserRole_.role eq role))   // or as one compound clause
    .resultList
```

A genuine `whereBuilder { }` case is a subquery composed into compound logic. A bare EXISTS needs no builder either: `whereExists { }` covers it.

```kotlin
users.select()
    .whereBuilder {
        (User_.email like "%@example.com") and exists(subquery<UserRole>().where(UserRole_.role eq role))
    }
    .resultList
```

The `whereBuilder { }` lambda receives a `WhereBuilder` that provides `where()`, `exists()`, `notExists()` methods returning `PredicateBuilder` instances composable with `.and()` / `.or()`. Infix predicates work inside it, so even there prefer `(A eq x) and exists(...)` over the operator-argument style.

## Joined-Entity Predicates and Ordering

**A join widens the query.** From the join onward, every clause accepts paths from any entity in the query. There are no separate methods for joined entities: the joined table's fields go through the same `where`, `orderBy`, `groupBy` and `having` calls.

```kotlin
// Root is User: the joined UserRole fields go through the same calls as the root's
select {
    innerJoin<UserRole, User>()
    where(UserRole_.role eq role)      // joined entity: plain where
    orderBy(User_.name)                // root path
    orderBy(UserRole_.assignedAt)      // joined entity
}.resultList
```

- Inside `select { }` the block is widened from the start, so clause order does not matter there.
- On the chained API the builder is typed to the root **until** the join, so put joins before the clauses that reference them.
- A path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root.

Two operations are defined relative to the root and are affected by the widening:
- `fetch(...)` comes before any join.
- `resultGroupedBy` needs the root back after a join: `narrow<User>()` restores it, verified against the query's FROM table.

The counterpart `widen()` widens without a join: the chained equivalent of `select { }` starting widened. `typedId(Int::class)` types the primary key.

## Keyset Scrolling

Pick the read by what the caller needs to know:

| Read | Method | Result | Count query | Needs |
|---|---|---|---|---|
| Page | `page(pageable)` | `Page<R>` | yes | an ordering |
| Slice | `slice(pageable)` | `Slice<R>` | no | an ordering; a page without the count query, the only read for a query without a unique key |
| Scroll | `scroll(scrollable)` | `Window<R>` | no | a unique key; constant cost at any depth |
| Windows | `windows(size)` | `Flow<Window<R>>` | no | a primary key; one closed statement per window, so the loop may write |

**The request owns ORDER BY: never add `orderBy()` to a `scroll(Scrollable)` query, or Storm throws `PersistenceException`.** Keyset scrolling navigates by position instead of offset, which keeps it efficient on large tables. A `Scrollable<T>` takes a **single type parameter**, the entity type, and states the ordering and the size. The position to continue from comes from `window.next()` / `window.previous()` or from a client's cursor string.

```kotlin
// WRONG: orderBy conflicts with the request's ordering
select {
    where(User_.email like "%@example.com")
    orderBy(User_.name)        // ❌ the Scrollable orders
}.scroll(Scrollable.of(User_.id, 20))

// CORRECT: the request orders; sort fields go before the key, which stays the tiebreaker
select {
    where(User_.email like "%@example.com")
}.scroll(Scrollable.of(User_.id, 20).sortBy(User_.email))
```

**Ordering:**
- `Scrollable.of(key, size)` orders by the key ascending. `descending()` orders the key itself descending.
- `sortBy(field)` / `sortByDescending(field)` add sort fields before the key, in any number, each in its own direction.
- **Sort fields must be non-nullable**, checked the way the key is. A NULL never compares, so such a row would fall out of every window; Storm throws a `PersistenceException` rather than paginate over rows it would silently skip.
- A date or status column is the usual sort field and the usual place a nullable type slips in.
- A newest-first feed is `Scrollable.of(Post_.id, 20).sortByDescending(Post_.createdAt).descending()`.

**Every window is in sort order.** `window.previous()` returns the window before this one in the same order as `window.next()` returns the one after it; never reverse a window for display. `hasNext()` / `hasPrevious()` say whether rows existed after / before the window at query time. The tokens are non-null whenever the window has content, and following one is always allowed.

**First request vs subsequent requests:** the ordering and the size are code; the position is the client's cursor. The first request has no cursor. Subsequent requests put the same request at the client's cursor with `.from(cursor)`.
- The cursor is **opaque**: it carries the position only (the row's sort and key values, and whether to continue after or before it) under a fingerprint of the ordering. A cursor issued for another ordering is refused.
- Clients treat it as a black box and echo it back unchanged.
- Server-side code never needs the cursor: `window.next()` / `window.previous()` are ready-to-use `Scrollable<T>` requests.

```kotlin
val request = Scrollable.of(User_.id, size).sortBy(User_.email)      // ordering and size: code
val window = users.scroll(if (cursor != null) request.from(cursor) else request)
val nextCursor: String? = window.nextCursor()                          // null when nothing follows
```

**Tokens for every result type:** the sort and key values are read from the row, so `selectRef().scroll(...)`, `users.scrollRef(...)`, projections read as another type and custom select types all navigate. A compound (inline record) key is read from the mapped record and needs the entity type as the result.

**Iterate windows:** `for (user in window)` works: `Window` is a `Slice` and iterates over its content. `window.size()` and `window.isEmpty()` exist too. `Window` is a Java record, so the other accessors are methods: `window.content()`, `window.hasNext()`.

**Slices:** `select().slice(Pageable.ofSize(20).sortBy(User_.email))` is `page` without the count query: `hasNext()` from one extra row, `hasPrevious()` from the page number, the next slice from `pageable.next()`. `slice(0, 20)` uses the query's own ordering. `Slice` is the interface `Page` and `Window` implement, so call its accessors with `()`.

## Bulk DELETE/UPDATE

**`delete()` is a builder method that returns `QueryBuilder`.** Call `.executeUpdate()` to execute:

```kotlin
// DELETE with WHERE (safe): builder returns QueryBuilder, terminal executes
orm.entity(User::class).delete().where(User_.postalCode.isNull()).executeUpdate()

// DELETE with predicate shorthand: also returns QueryBuilder
orm.entity(User::class).delete(User_.postalCode.isNull()).executeUpdate()

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User::class).delete().unsafe().executeUpdate()

// Convenience method: removeAll() executes immediately (calls unsafe() internally)
users.removeAll()
```

**`update()` writes a few columns of rows the code did not read.** `set` names a column through the metamodel, the conditions select the rows, and `.executeUpdate()` runs one statement without reading the rows. Use it rather than a second, partial entity for the table.

```kotlin
// Block DSL
// Transfer pets to a new owner
pets.update {
    set(Pet_.owner, newOwner)
    where(Pet_.owner eq previousOwner)
}.executeUpdate()

visits.update {
    set(Visit_.description, null)                     // nullable field
    where(Visit_.visitDate eq closedDay)
}.executeUpdate()

// Chained
visits.update()
    .set(Visit_.description, "Rescheduled")
    .where(Visit_.visitDate eq closedDay)
    .executeUpdate()

// Foreign key: the entity or a Ref
pets.update {
    set(Pet_.owner, newOwnerRef)
    where(Pet_.owner eq previousOwner)
}.executeUpdate()

// A value computed from the row: a template lambda
owners.update {
    set(Owner_.lastName) { "UPPER(${Owner_.lastName})" }
    where(Owner_.address.city eq city)
}.executeUpdate()

// A condition through a related table works on every database
visits.update {
    set(Visit_.description, "Rescheduled")
    where(Visit_.pet.owner.address.city eq city)
}.executeUpdate()
```

Rules for `update()`:
- **WHERE is required** (or a `semiJoin`/`antiJoin`), as for `delete()`. `unsafe()` confirms an update of every row.
- **`set` refuses** the primary key, `@Persist(updatable = false)` fields, the `@Version` field, a path into a related table (`Owner_.address.city.name`), and a field set twice. An inline record field writes each of its columns.
- **The `update()` and `delete()` builders refuse a Joined Table entity** (sealed type or subtype): its rows span two tables. Use `remove`/`removeById` and `update(entity)`.
- **`@Version` is incremented** by the statement (a timestamp version is set to the current time), so an entity read before it fails its optimistic lock check.
- **No entity callbacks fire**: `beforeUpdate`/`afterUpdate` need an entity and the statement carries none. `set` an audit column in the builder.
- **The type's entities are evicted from the transaction's entity cache** once it runs. An entity read before it still holds its old values, so re-read the entities it touched before writing them back. `update(entity)` of a stale copy writes its full row, putting the old values back over the columns the statement set; a versioned entity fails its optimistic lock check instead.
- `distinct`, `limit`, `offset`, locking, result reads and use as a subquery are refused.

## Block-Based Query DSL

**Prefer the chained API for linear queries.** A straight filter/order/limit pipeline reads best as a chain: `select(predicate).orderBy(...).limit(...).resultList`, or `select().where { template }...` when the condition needs a template. Reach for the `select { }` block only when you need the block structure: conditional predicates or joins (`if`/`when` inside the block), or queries with many clauses where the scoped layout helps.

**`select { }` / `update { }` / `delete { }` are builder methods that return `QueryBuilder`; they never execute immediately.** Inside the block, scope methods like `where()`, `orderBy()`, `limit()` construct the query. Then call a terminal operation to execute:

```kotlin
// Standalone: get the entity repository first. There is NO orm.select<T> { block } reified form
orm.entity(User::class).select {
    where(User_.email like "%@example.com")
    orderBy(User_.name)
    limit(10)
}.resultList

// On EntityRepository: same syntax
select {
    where(User_.city eq city)
}.scroll(Scrollable.of(User_.id, 20))  // do NOT add orderBy: Scrollable manages ORDER BY

// delete { } also returns QueryBuilder: call .executeUpdate() to run it
delete {
    where(User_.postalCode.isNull())
}.executeUpdate()
```

Predicate variants also return `QueryBuilder`:
```kotlin
// select(predicate) returns QueryBuilder
users.select(User_.email like "%@example.com").resultList

// delete(predicate) returns QueryBuilder
users.delete(User_.postalCode.isNull()).executeUpdate()
```

**Put conditional logic inside the block.** The block is a regular Kotlin lambda: use `if`, `when`, and loops to compose queries dynamically. This avoids duplicating shared parts (ordering, pagination, terminals) across branches:
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

This also works with joins: conditionally add a join only when needed.
```kotlin
select {
    if (city != null) {
        innerJoin<UserAddress, User>()
        where(UserAddress_.city eq city)
    }
    orderByDescending(User_.createdAt)
}.page(page, size)
```

Available in the block: `where`, `whereBuilder`, `whereExists`, `whereNotExists`, `orderBy`, `orderByDescending`, `groupBy`, `having`, `havingExists`, `havingNotExists`, `limit`, `offset`, `distinct`, `unsafe`, `forUpdate`, `forShare`, `hint`, `fetch`, `innerJoin`, `leftJoin`, `rightJoin`, `crossJoin`, `append`.
- The block is widened from the start, so joined-entity fields go through the same calls as root fields.
- `whereBuilder` remains the escalation for `exists`/id/ref/record matching inside compound logic (see **Choosing the WHERE Form**).

**Note:** The block DSL has `orderBy { template }` but NOT `orderByDescending { template }`. For template-based descending order, use the chained API: `.orderByDescending { template }`, or escape to raw SQL.

**The block DSL is typed to the root entity.** There is **no** `select(ResultType::class) { block }` form: `select { }` always returns the root entity type. In `select(ResultType::class) { ... }` the trailing lambda is a SQL template for the SELECT clause, not a block DSL. To select a different result type, use the chained API:
```kotlin
// ❌ Not valid: no block DSL overload for result type
select(UserSummary::class) {
    where(User_.email like "%@example.com")
}.resultList

// ✅ Use chained API for a different result type
select(UserSummary::class)
    .where(User_.email like "%@example.com")
    .resultList

// ✅ With joins: chained API uses .innerJoin<A>().on<B>(), not the two-type-arg form
select(UserSummary::class)
    .innerJoin<UserRole>().on<User>()
    .where(UserRole_.role eq role)
    .scroll(Scrollable.of(User_.id, 20))
```

**What `select(ResultType::class)` is for:** It selects a different result type from the query. This works for:
- **Joined entity types**, e.g., `select(City::class)` from a `User` query that joins `City`.
- **Custom SELECT with template**, e.g., `select(Summary::class) { template }` with a custom SQL select clause that maps to the result type's fields.

It does **not** work for selecting a column subset of the root entity: a `UserSummary` with only `id` and `name` from `User` fails with "Cannot find alias for column." Read the entity itself. Where the column subset matters for performance or the read needs a shape the entity cannot give, use a `Projection<T>` with `ProjectionRepository` (see **One Entity per Table, Projections for Performance or Customization** in the Storm rules).

**Cross-entity pitfall: put the query on the target entity's repository.** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`).

## Flows and the Connection

**A `resultFlow` is cold.** Building it runs nothing; the query runs when the flow is collected, and each collection runs it again. Several flows may be built up front and collected one after the other, and a flow that is never collected holds no connection.

**A collected `resultFlow` is consume-only while it has rows to emit.** It is one open statement, and while rows remain its connection is consume-only, on every database. Inside `transaction { }` every statement shares the transaction's connection, so these all throw `PersistenceException` from the collector:

```kotlin
transaction {
    users.select().resultFlow.collect { user ->
        orm update user.copy(email = user.email.lowercase())   // ❌ write while the flow has rows left
        user.city.fetch()                        // ❌ Ref.fetch() is a statement too
        cities.count()                           // ❌ any query
    }
    users.update(users.select().resultFlow)      // ❌ batched write fed by a flow of the same transaction
}
```

**Never feed a `resultFlow` of the current transaction into `insert`, `update`, `upsert`, `remove`, `removeByRef`, `insertAndFetch` or `countById`.** Feed them an in-memory flow, or iterate in windows.

The last line above is the trap that passes small tests. The flow completes and closes when its last row is emitted, so a batch that executes after the flow has been read to its end is allowed, and a batch that executes while rows remain is refused. Whether that happens depends on batch size versus row count.

**Use `windows(size)` for a loop that needs the database.** It reads keyset windows over the primary key. Each window is fetched by one statement that has closed before the window is emitted, so the collector may query, fetch refs and write; there is nothing to close, and one batched write per window costs one statement rather than one per row:

```kotlin
// One transaction for the whole walk:
transaction {
    users.select(User_.city eq city).windows(1000).collect { window ->
        users.update(window.content().map { it.copy(email = it.email.lowercase()) })
    }
}

// Or a transaction per window, so progress is durable and locks are short-lived:
users.windows(1000).collect { window ->
    transaction { users.update(window.content().map { it.copy(email = it.email.lowercase()) }) }
}

// Resume after a restart from a stored cursor:
users.windows(Scrollable.of(User_.id, 1000).from(storedCursor)).collect { window ->
    process(window.content())
    store(window.nextCursor())
}
```

Rules for `windows`:
- The key is the primary key (or the `Scrollable`'s key), which must be a non-null single column.
- No `orderBy()` on the query.
- The result type must be the entity: `selectRef()` and custom select types are refused.
- Each window is its own statement and sees the committed state at that moment.

**`windows(size).rows()` reads the windows as one `Flow<R>`** for a loop that handles one row at a time. Each row comes from a window whose statement has already closed, so the connection is free at every row, memory stays bounded by the window size, and the rows arrive in key order. It is the one-token switch for a `resultFlow` loop the guard refuses. Where the loop writes, the per-window form above batches better:

```kotlin
transaction {
    users.select(User_.city eq city).windows(1000).rows().collect { user ->
        audit.insert(AuditEntry(user = user.ref(), checkedAt = now))   // a statement per row, allowed
    }
}
```

What stays fine with `resultFlow`:
- consuming it (`collect`, `toList()`, `count()`, `map`, `filter`);
- stopping early (`first()`, `take(n)` cancel the flow and close the statement);
- once it has completed, any statement.

A `Ref` the loop needs is loaded by naming it in the fetch plan (`select().fetch(...)`) instead of calling `fetch()` per row. Outside `transaction { }` a collected flow holds a pooled connection of its own for as long as it is collected.

## Result Retrieval

QueryBuilder terminals:
- `.resultList` → `List<R>`
- `.singleResult` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.optionalResult` → `R?` (null if empty, throws if multiple)
- `.resultCount` → `Long`
- `.resultFlow` → `Flow<R>` (cold; consume-only while collecting, see **Flows and the Connection**)
- `.resultStream` → `Stream<R>` (lazy, must close after use; same connection rule)
- `.windows(size)` / `.windows(scrollable)` → `Flow<Window<R>>` (one closed statement per window; the loop may query, fetch refs and write between windows; nothing to close)
- `.windows(size).rows()` → `Flow<R>` (the windows' rows as one flow; the connection is free at every row)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(scrollable)` → `Window<R>` (keyset scrolling, see **Keyset Scrolling**). Use `next()` / `previous()` for programmatic navigation, or `nextCursor()` / `previousCursor()` for REST APIs.
- `.executeUpdate()` → `Int` (for DELETE/UPDATE)

Critical rules:
- **QueryBuilder is IMMUTABLE.** Every method returns a new instance. Always use the return value (or use the `select { }` DSL, which handles this automatically).
- **Streaming:** `select().resultFlow` returns a Flow with automatic cleanup. There are no `selectBy` methods that return Flow/Stream directly: use `select()` (optionally with predicate or block DSL) and then `.resultFlow`, `.resultStream` or `.windows(size)`.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key:
  ```kotlin
  val countMap: MutableMap<Ref<Cell>, MutableMap<String, Int>> = mutableMapOf()
  countMap.getOrPut(candidate.cell.ref()) { mutableMapOf() }
  ```
- **Typed ID from `Ref`:** Use `ref.entityId()` (import `st.orm.template.entityId`) to extract a type-safe ID from a `Ref`. Avoid `ref.id()`: it returns `Any` and requires an unsafe cast.

## Verification

**After writing queries, write a test using `@StormTest` and `SqlCapture`** to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the query produces the result the user intended: correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```kotlin
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Without it, paths resolve relative to the test class's package.
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class UserQueryTest {
    @Test
    fun findExampleUsersInCity(orm: ORMTemplate, capture: SqlCapture) {
        val city = orm.entity(City::class).getById(1)
        val users = capture.execute {
            orm.entity(User::class).select()
                .where((User_.city eq city) and (User_.email like "%@example.com"))
                .orderBy(User_.name)
                .resultList
        }
        // Verify intent: single query, only example.com users in the given city, ordered by name.
        assertEquals(1, capture.count(Operation.SELECT))
        assertFalse(users.isEmpty())
        assertTrue(users.all { it.city == city && it.email.endsWith("@example.com") })
        assertEquals(users.sortedBy { it.name }, users)
    }
}
```

**Keep the verification loop on the in-memory database `@StormTest` provides by default.** It answers in milliseconds and needs no Docker, which keeps verify-and-fix iterations cheap. Escalate to the target database only:
- when the in-memory database cannot run the SQL involved (dialect-specific functions, JSON operators, upsert or sequence syntax the target database defines differently);
- when the user asks for it;
- as a final pass before finishing a larger piece of work.

Adding `database = ...` to `@StormTest`, with the constant for the project's database from `st.orm.test.TestDatabase`, runs the same test in a Testcontainers-managed container of that database. It costs a container start per test run and requires Docker. Nothing else in the test changes; the class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

**`SqlCapture` answers whether a query is correct; the `st.orm.sql.perf` logger finds which query is expensive.** In a running application, raise that logger and read the per-call summary. It ranks statements by total time and shows which ones repeat, which resolve references on demand, and which hydrate more graph than they use. See the repository skill for how to act on each signal.

The test can be temporary: verify and remove, or keep as a regression test. Ask the user which they prefer.
