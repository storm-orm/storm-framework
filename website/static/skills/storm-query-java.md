---
name: storm-query-java
description: Write Storm queries in Java with the QueryBuilder, covering joins, predicates, ordering, pagination, keyset scrolling, and Ref navigation. Use for any read query in Java.
---

Help the user write Storm queries using Java.

**Important:** Storm can run on top of JPA, but when writing queries, always use Storm's own QueryBuilder and operator-based predicates — not JPQL, `CriteriaBuilder`, or `EntityManager.createQuery()`.

## Key Imports

```java
import st.orm.template.QueryBuilder;              // Query builder
import st.orm.Operator;                           // EQUALS, NOT_EQUALS, LIKE, IN, IS_NULL, etc.
import static st.orm.Operator.*;                  // Static import for operator constants
import st.orm.Metamodel;                          // Generated metamodel fields (User_, City_, etc.)
import st.orm.Ref;                                // Lazy-loaded reference
import st.orm.Page;                               // Offset-based pagination result
import st.orm.Pageable;                           // Pagination request
import st.orm.Scrollable;                         // Keyset scrolling cursor (single type param: Scrollable<T>)
import st.orm.Window;                             // Keyset scrolling result (Window<R>)
```

Do NOT import from `st.orm.core.*` — those are Storm's internal core-engine packages; the Java API lives in `st.orm.repository` and `st.orm.template`. `st.orm.Operator` is an interface with static constants (static-importable like an enum): `EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LIKE`, `NOT_LIKE`, `CONTAINS`, `NOT_CONTAINS`, `STARTS_WITH`, `NOT_STARTS_WITH`, `ENDS_WITH`, `NOT_ENDS_WITH`, `IS_NULL`, `IS_NOT_NULL`, `IS_TRUE`, `IS_FALSE`, `IN`, `NOT_IN`, `BETWEEN`.

Ask what data they need, filters, ordering, or pagination.

**DI preference:** In Spring Boot projects, repositories should be constructor-injected (see /storm-repository-java). Use `orm.entity(T.class)` and `orm.repository(T.class)` lookups only in standalone (non-DI) contexts and tests. In DI environments, write queries on injected repository instances.

**Layering rule:** Follow the codebase's existing convention first — if handlers already use repositories directly, or a service layer is consistently in place, match that style rather than introduce a competing one. Absent a clear stance (new code, greenfield), promote the layered architecture: controller → service → repository, where controllers never inject repositories — all data access flows through services, which own the transaction boundaries (e.g. `@Transactional` on service methods) and return view-model types. Whatever the stance, do not mix styles: layer-skipping controllers undermine the service layer's cross-cutting concerns (transactions, caching, authorization).

## API Design: Builder Methods vs Convenience Methods

Repository/entity methods fall into two categories:

**Builder methods** return `QueryBuilder` for composable, chainable queries. They never execute immediately:
- `select()` -- build SELECT queries
- `selectRef()` -- build SELECT queries returning Refs
- `selectCount()` -- build COUNT queries
- `update()` -- build UPDATE queries that `set` columns on the rows a condition selects
- `delete()` -- build DELETE queries

(The `select(predicate)` / `delete(predicate)` shorthands are Kotlin-only — in Java, chain `.where(...)` on the builder.)

Terminal operations: `.getResultList()`, `.getSingleResult()`, `.getOptionalResult()`, `.getResultStream()`, `.windows(size)`, `.getResultCount()`, `.getResultGroupedBy(path)`, `.getResultGroupedByRef(path)`, `.page()`, `.scroll()`, `.executeUpdate()`

**One-to-many loading:** `getResultGroupedBy(path)` groups results by a related record, typically the parent entity of a foreign key. The same select is executed (single query, no SQL change) and the results are grouped during hydration into an unmodifiable, insertion-ordered `Map<Parent, List<T>>`. Repeated parents are materialized once and grouped by instance identity, so the join's duplication is not paid during hydration. The grouping does not depend on row order: a parent collects its children wherever they fall in the result set. Without an `orderBy()` the map and its lists follow the order the database returns, so order by what you want ordered rather than by the parent path: `orderBy(User_.city.name, User_.email)` gives the cities alphabetically with their users by email. The path must resolve non-null for every result. The ref-based variant returns `Map<Ref<Parent>, List<T>>` with keys compared by primary key. For eager entity paths the keys are loaded refs (`getOrNull()` returns the already-materialized record); for `Ref` foreign-key fields it groups directly on the foreign key without fetching the parent, and `findAllByRef(map.keys)` fetches the parents in one query when needed.

```java
// Load cities with their users in one query
Map<City, List<User>> usersByCity = orm.entity(User.class)
    .select()
    .getResultGroupedBy(User_.city);
```

**Convenience methods** execute immediately and return results directly:
- `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findBy()`, `findAllBy()`, `getById()`, `getByRef()`, `getBy()`, `count()`, `exists()`, `remove()`, `removeById()`, `removeByRef()`, `removeAll()`, `removeAllBy()`, `page()`, `pageRef()`, `scroll()`

The `delete`/`remove` distinction: `remove` operates on entities or ids you already have (immediate execution). `delete` builds a query to find and delete rows by criteria (returns `QueryBuilder`). Updates split the same way: `update(entity)` writes an entity you hold, the `update()` builder sets columns on the rows a condition selects (see **Bulk DELETE/UPDATE**).

Prefer the simplest approach that works. Three query levels, from simplest to most powerful:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`findBy`, `findAllBy`, `removeAllBy`, `countBy`, `existsBy`) | Simple lookups and operations |
| 2 | Builder chained (`select().where(...)`, `update().set(...).where(...)`, `delete().where(...)`) | Most application queries needing ordering, pagination, or joins |
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
| Set columns on rows not read | `update().set(...).where(...).executeUpdate()` | a second, partial entity for the table, or an `orm.query(RAW."UPDATE ...")` template |
| Parents with their children (one-to-many) | `select().getResultGroupedBy(parentPath)` | per-parent queries in a loop (N+1) or manual grouping after `getResultList()` |
| Filtered + **ordering/pagination** | `select().where(...).orderBy(...).getResultList()` | convenience methods (can't add ordering) |
| Filtered + **joins** | `select().innerJoin(...).on(...).getResultList()` | convenience methods (can't add joins) |
| Filtered + **streaming** | `select().where(...).getResultStream()` (consume-only) or `.windows(size)` (loop may query/write) | convenience methods (return List, not Stream) |
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
Foreign key columns: name the field itself, never `.id`. See **Naming a foreign key column** below.
Nested paths: `User_.city.country.code` with appropriate operator
Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`
Limit/Offset: `.limit(10)`, `.offset(20)`
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`
Scrolling (keyset): `.scroll(Scrollable.of(User_.id, 20))` — do NOT combine with `orderBy()` (Scrollable manages ORDER BY internally, see Keyset Scrolling section)

Streaming: `.getResultStream()` is one open statement; while rows remain unread its connection is consume-only, so inside a transaction a query, `Ref.fetch()` or write from the loop throws `PersistenceException`, on every database. A loop that reads or writes per row uses `.windows(size)` (`Stream<Window<R>>`): keyset windows over the primary key, one closed statement per window, the connection is free between windows, nothing to close; write per window with `update(window.content().stream().map(...).toList())`.
Explicit joins: `.innerJoin(Entity.class).on(OtherEntity.class)`, `.leftJoin(Entity.class).on(OtherEntity.class)`, `.rightJoin(Entity.class).on(OtherEntity.class)`
A condition on the join: `.leftJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Leo"))` adds the condition to the resolved foreign key; on a left join it keeps the rows without a match, which the same condition in `where` would drop
Filtering by related rows: `.semiJoin(Pet.class).on(Owner.class)` keeps the rows with a related row matching the conditions that name it, each once; `.antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot"))` keeps the rows without one
**Auto-join types follow FK nullability.** A `@FK` record component is non-null by default, so its auto-join is an INNER JOIN. Mark the component `@Nullable` (JSpecify `org.jspecify.annotations.Nullable` or `jakarta.annotation.Nullable`) when the FK column allows NULL; that produces a LEFT JOIN. If generated SQL shows INNER JOIN where you expect LEFT JOIN, the FK component is missing `@Nullable` in the entity.
Result type: `.select(ResultType.class)` to return a different type than the root entity. **Cross-entity pitfall:** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`). Put the query on the target entity's repository instead.

Operators: EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, LIKE, NOT_LIKE, CONTAINS, NOT_CONTAINS, STARTS_WITH, NOT_STARTS_WITH, ENDS_WITH, NOT_ENDS_WITH, EQUALS_IGNORE_CASE, NOT_EQUALS_IGNORE_CASE, CONTAINS_IGNORE_CASE, NOT_CONTAINS_IGNORE_CASE, STARTS_WITH_IGNORE_CASE, NOT_STARTS_WITH_IGNORE_CASE, ENDS_WITH_IGNORE_CASE, NOT_ENDS_WITH_IGNORE_CASE, IS_NULL, IS_NOT_NULL, IN, NOT_IN

**An `IN` list is one statement on PostgreSQL whatever its length.** `IN`, `findAllById` and `whereId` bind the list as one array parameter there, `id = ANY(?)`, so the database plans and caches one statement for a lookup of a varying number of keys, and faster than a placeholder per value; elsewhere each value has a placeholder. Pass the whole list: never chunk ids by hand, build one query per key, or write the list into a template to control the shape. Where a database's plan cache fills with the lengths (Oracle, SQL Server), `storm.in_list` set to `padded` renders a placeholder per value padded to the next power of two, which changes no result (and forgoes the array on PostgreSQL, where the array is faster); the `in-list-shapes` advice, reported once a statement has run with eight different list lengths (in the advice log in development, on a slow line in production), says when to set it; `storm.in_list=exact` keeps a placeholder per value everywhere.

**Search text goes through `CONTAINS`, `STARTS_WITH` or `ENDS_WITH`, never through `LIKE`.** Text a user typed can hold `%`, `_`, `[` or a backslash, which `LIKE` reads as wildcards or escapes, differently per database. The text operators escape it for the dialect and add the wildcards themselves, so `.where(User_.name, CONTAINS, query)` matches exactly what was typed. Never build a pattern such as `"%" + query + "%"`, and never write an escape helper. Keep `LIKE` for patterns the code writes itself. The text operators apply to a single `String` column; case sensitivity follows the column's collation, as it does for `LIKE`.

**Text compared whatever its case goes through the `IGNORE_CASE` operators, never through a `LOWER(...)` template.** `EQUALS`, `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` follow the column's collation, which ignores case on MySQL, MariaDB and SQL Server by default and not on PostgreSQL, Oracle and H2, so the same query answers differently per database. `EQUALS_IGNORE_CASE`, `CONTAINS_IGNORE_CASE`, `STARTS_WITH_IGNORE_CASE` and `ENDS_WITH_IGNORE_CASE`, with their `NOT_` forms, have the database lower both sides, and the text ones still match their text literally: `.where(User_.name, CONTAINS_IGNORE_CASE, query)`. A search box and a lookup by email address or user name want these; a code, a token or an id compared exactly wants `EQUALS`. They ignore case; accents follow the column's collation, ignored on MySQL and MariaDB by default and compared as they are elsewhere. A plain index does not serve the lowered column: where the lookup must be served by one, index the lowered expression, or keep the plain operator on a column whose collation ignores case already.

**A search box goes through `search`, never through hand-built `CONTAINS_IGNORE_CASE` chains.** `.where(it -> it.search(input, Movie_.title, Movie_.director.name))` takes the input as the box receives it: whitespace separates the words, a quoted phrase is one word, every word must occur in at least one of the columns (ignoring case, literally), and an empty input matches every row. It returns a predicate, so it combines with `.and()` and `.or()`. Do not split the input, reduce over the words or special-case the empty box in application code. To search the rows related to each row, such as an owner's pets, semi-join the related table and name its column: `.semiJoin(Pet.class).on(Owner.class).where(it -> it.search(input, Owner_.lastName, Pet_.name))`. Each word may then match a different related row, and every owner comes back once.

**A search box that queries as the user types goes through `suggest`, not `search`.** An auto-complete, a typeahead or any box that fetches on every keystroke uses `.where(it -> it.suggest(input, Movie_.title, Movie_.director.name))`: the same input and paths, matched the same way, on the words the database's substring index serves alone. A word shorter than the index serves (three characters on PostgreSQL, whose trigram index needs three) is ignored, and an input without such a word, an empty one included, matches no rows, so every statement it renders is served by the index however the user types. The search page the box leads to keeps `search`, which matches every word. Do not filter short words or an empty input out in application code; `suggest` does it per dialect. A word the index serves but which occurs in a large share of the rows, such as `the` in a catalog of titles, is the box's to name: `it.suggest(input, Set.of("the", "a", "of"), Movie_.title)` ignores those words as it ignores short ones, compared ignoring case, so `the god` matches on `god` alone and `the` alone matches no rows. Keep the set a constant next to the box; never strip the words from the input in application code, and never pass the set to `search`, which takes none.

**Rank search results with `orderFirst`, never with a `CASE` template.** `search` and `suggest` decide which rows match, not which come first. `orderFirst(predicate)` puts the rows matching the predicate first, appending a sort key as `orderBy` does, so the calls apply in order: `.orderFirst(it -> it.where(Movie_.title, EQUALS_IGNORE_CASE, input)).orderFirst(it -> it.where(Movie_.title, STARTS_WITH_IGNORE_CASE, input)).orderByDescending(Movie_.votes)` lists an exact title, then titles starting with the input, then the most popular. The operators match the input literally and ignoring case, as in `where`; combine columns with `.or(...)`. Rank only by columns of the queried or joined tables: a semi-joined table's columns are refused, as it adds no columns. A keyset `scroll` follows `orderFirst` (its keys lead the `Scrollable`'s sort fields and key, and the cursor carries the ranks), and a `Scrollable` may sort by a field of a joined table, such as the votes of a joined rating; `orderFirst` cannot order a `distinct` query. No index serves the sort, which is fine after a search's few matches.

**A search needs an index built for it, created in the migration with the query.** `search` and the `IGNORE_CASE` text operators compare `LOWER(column) LIKE LOWER('%word%')`: a lowered expression with a leading wildcard, which no B-tree index serves. Without an index for it, each search reads and lowers every row of every table it names, which goes unnoticed on test data and takes seconds on a production-sized table. Where a searched table can grow beyond a few thousand rows, add the target database's substring index (a trigram or n-gram index) to the migration, over exactly `LOWER(column)`, one per searched column, including the columns of a semi-joined table or of a table reached through a path. An index on the plain column does not match the lowered expression. A word shorter than the index's gram (usually three characters) cannot use it: `search` tests it only on the rows its longer words found, and a search of short words alone still reads every row; the slow log (`st.orm.sql.slow`) adds advice to such a statement's line. Confirm in the database's query plan that each table is read through its index, for the statement as the application sends it: with the word as a bind parameter, after the driver has prepared it, not with the word written into the SQL. A prepared statement may be planned once for every value; that plan cannot see the word, and a search ordered by an indexed column with a small limit, such as a list of suggestions, then walks that column's index testing every row. Where that happens, configure the driver or database to plan each execution for its values. After creating the index on a table that holds data, refresh the table's statistics; where a rare word is still estimated to match many rows, raise the statistics sample of the indexed expression. Never speed a search up by changing its predicate: a case-sensitive operator reads every row as well and changes what matches, and an `ILIKE` or a `LOWER(...)` template reads every row just the same. Where the database has no substring index, say so, and narrow the rows with a selective predicate instead. Whether accents count is the schema's decision, never the application's: the search follows the column's collation, which ignores accents on MySQL and MariaDB by default and compares them elsewhere. Where a search must ignore them on such a database, have the database remove them through a function an index is built on, and name that function in the query with `SqlFunction`: the migration wraps the database's accent function in one marked immutable where it is not (on PostgreSQL, `CREATE FUNCTION fold_accents(text) RETURNS text LANGUAGE sql IMMUTABLE PARALLEL SAFE STRICT RETURN public.unaccent('public.unaccent'::regdictionary, $1)`) and indexes `LOWER(fold_accents(column))`; the repository declares `SqlFunction<String> foldAccents = SqlFunction.of("fold_accents")` once and searches `it.search(input, foldAccents.apply(Movie_.title))`, ranking with `orderFirst(it -> it.where(foldAccents.apply(Movie_.title), EQUALS_IGNORE_CASE, input))`. A comparison applies the function to the column and to the input, so the database folds both and the application folds neither: never remove accents from the input in application code, never add an entity field holding the folded text, and never wrap the column in an accent function inside a template, which the search operators cannot use. Where the recheck of the accent function on every candidate row dominates, which shows on a column where a common word matches many thousands of rows, store the folded text in a generated column (`title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(title)) STORED NOT NULL`), index `LOWER(title_folded)`, and declare it as a query column on the entity that names the function, `@QueryColumn(field = "titleFolded", type = String.class, function = "fold_accents")`, so no row carries it (see the entity skill) and a value compared with it passes through the function on the value side: `it.search(input, Movie_.titleFolded)` renders `LOWER(m.title_folded) LIKE LOWER(fold_accents(?))`, the column as stored and the input folded by the database in the same statement. Never fold the input in application code or in a query of its own. Name exactly the function the index is built on; another expression is not served by it. A `Scrollable` refuses a function call as key or sort field.

### Naming a foreign key column

**Name a foreign key by the field itself, never by `.id`.** A foreign key field already resolves to the foreign key column, so `User_.city` and `User_.city.id` are the same column and neither joins the target table. Prefer the plain field: `.id` reads as a navigation step into `City` and invites the reader to expect a join that is not there. This holds everywhere a column is named — predicates, template select lists, `groupBy`, `orderBy`, and aggregate expressions:

```java
record CityUserCount(@FK Ref<City> city, long userCount) {}

// ✅ The foreign key field names the foreign key column
List<CityUserCount> counts = orm.entity(User.class)
    .select(CityUserCount.class, RAW."\{User_.city}, COUNT(*)")
    .groupBy(User_.city)
    .getResultList();

// ❌ Redundant `.id` — identical SQL, and it reads like a join into City
List<CityUserCount> counts = orm.entity(User.class)
    .select(CityUserCount.class, RAW."\{User_.city.id}, COUNT(*)")
    .groupBy(User_.city.id)
    .getResultList();

COUNT(DISTINCT \{User_.city})       // ✅ inside a template
COUNT(DISTINCT \{User_.city.id})    // ❌ same column, longer
```

The rule extends to set membership: filter on the foreign key with the refs themselves via `whereRef(path, refs)` rather than unwrapping them into ids to compare against the same column.

```java
.whereRef(User_.city, cityRefs)                                          // ✅ IN over refs
.where(User_.city.id, IN, cityRefs.stream().map(Ref::entityId).toList())  // ❌ unwraps refs to reach the column they already name
```

**A compound foreign key is several columns, and templates take only one.** A foreign key to an entity whose `@PK` is an inline record is a multi-column foreign key — Storm matches its columns to the target's primary key columns one for one. Code-first operators expand it; a template placeholder does not, because a placeholder stands for a single column. Interpolating one throws `SqlTemplateException: Expected exactly one column for metamodel: …` before any SQL reaches the database.

```java
// ✅ Code-first expands the compound key
.where(Order_.line, EQUALS, orderLine)   // WHERE o.order_id = ? AND o.line_no = ?
.groupBy(Order_.line)                    // GROUP BY o.order_id, o.line_no — same columns, no join

// ❌ Template placeholders reject it — SqlTemplateException, not broken SQL
RAW."COUNT(DISTINCT \{Order_.line})"
RAW."\{Order_.line}, COUNT(*)"
RAW."sub.line_id = \{Order_.line}"
```

So the naming rule stands unchanged for single-column foreign keys, which is the overwhelmingly common case, and a compound key simply cannot appear in a template expression at all — name one of its leaves (`Order_.line.lineNo`, which joins the referenced table) or restructure the query to use the code-first form. Naming the target's primary key through the foreign key is the same as naming the foreign key itself: `Order_.line.id` and `Order_.line` resolve to the same foreign key column(s) on the referencing table, without a join — for `Ref` and entity foreign keys alike. The same single-column assumption is why keyset scrolling rejects a composite key (see **Keyset Scrolling**).

## Aggregation

```java
long userCount = orm.entity(User.class).selectCount()
    .where(User_.email, LIKE, "%@example.com")
    .getSingleResult();

List<CitySummary> citySummaries = orm.entity(City.class)
    .select(CitySummary.class)
    .groupBy(City_.country)
    .having(City_.population, GREATER_THAN, 100000)
    .getResultList();
```

**Computed aggregates (COUNT, AVG, SUM, etc.):** When the SELECT clause needs expressions that QueryBuilder can't produce, use `select(ResultType.class, RAW."template")` for the SELECT only — keep joins, groupBy, having, orderBy, and limit in code.

**`Data` means "represents a table".** Only types that map to a database table implement `Data` (or its subinterfaces `Entity`/`Projection`). Ad-hoc query result types — aggregation DTOs, computed shapes — are plain records with no marker at all. Storm maps result columns to any suitable record, including nested entity and `Ref<T>` components. Implementing `Data` on a result type pulls it into Storm's type discovery, so schema validation (`validateSchema()` without arguments, or startup schema validation) fails with `TABLE_NOT_FOUND` — which then requires `@DbIgnore` to suppress. `implements Data` + `@DbIgnore` on a result type is an anti-pattern (opting in and immediately opting out); a plain record expresses the intent directly. Define result types next to the code that produces them — typically in or beside the repository whose queries return them; the entity package is reserved for table-backed types — and document them as query result shapes (not backed by a table or view) so readers immediately see why they carry no marker.

**Important:** The `RAW."template"` provides the SELECT list only — not a full SQL query. If you put a full `SELECT ... FROM ... WHERE ...` inside, Storm wraps it as a scalar subquery, causing errors. For full custom SQL, use `orm.query(RAW."...").getResultList(T.class)` (see /storm-sql-java).

```java
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain record — deliberately not a Data type.
 */
record CityUserCount(City city, long userCount) {}

List<CityUserCount> cityCounts = orm.entity(City.class)
    .select(CityUserCount.class, RAW."\{City.class}, COUNT(*)")
    .leftJoin(User.class).on(City.class)
    .groupBy(City_.id)
    .getResultList();

// More complex example with WHERE, HAVING, and ORDER BY — all in code:
record CityUserStats(String cityName, double averageAge, long userCount) {}

int minUsers = 10;
List<CityUserStats> topCities = orm.entity(City.class)
    .select(CityUserStats.class, RAW."\{City_.name}, AVG(\{User_.age}), COUNT(*)")
    .leftJoin(User.class).on(City.class)
    .groupBy(City_.name)
    .having(RAW."COUNT(*) >= \{minUsers}")               // template form for aggregate expressions
    .orderByDescending(RAW."AVG(\{User_.age})")
    .getResultList();

// Multi-field groupBy — always use the varargs metamodel form:
record CityPostalCodeCount(@FK Ref<City> city, String postalCode, long userCount) {}

List<CityPostalCodeCount> counts = orm.entity(User.class)
    .select(CityPostalCodeCount.class, RAW."\{User_.city}, \{User_.postalCode}, COUNT(*)")
    .groupBy(User_.city, User_.postalCode)    // ✅ varargs metamodel form
    .getResultList();

// ❌ Don't use template when metamodel fields work:
//    .groupBy(RAW."\{User_.city}, \{User_.postalCode}")
// ✅ Use varargs metamodel form — code-first, type-safe:
//    .groupBy(User_.city, User_.postalCode)
```

**`Ref<T>` in aggregation result types:** When the SELECT clause references a FK field (`\{User_.city}`) rather than a full entity (`\{City.class}`), use `Ref<T>` in the result type — not the raw ID type and not the full entity. `Ref<City>` maps correctly to the FK column value. Use the full entity type only when the SELECT includes all its columns via `\{City.class}`.

Always prefer code over templates. Templates are for expressions QueryBuilder can't produce (e.g., `COUNT(*)`, `AVG()`). `groupBy`, `having`, and `orderBy` also accept template forms when needed (e.g., `.having(RAW."COUNT(*) >= \{min}")`, `.orderByDescending(RAW."AVG(\{User_.age})")`), but **always use the varargs metamodel form for `groupBy`** and **the metamodel form for `orderBy`** when possible — reserve template forms for computed expressions. Do NOT write the entire query as a raw SQL string.

## Row Locking

```java
User user = orm.entity(User.class).select()
    .where(User_.id, EQUALS, userId)
    .forUpdate()         // SELECT ... FOR UPDATE
    .getSingleResult();

// Or shared lock
    .forShare()          // SELECT ... FOR SHARE
```

## Optimizer Hints

Only when a query needs a hint to keep its plan, add it with `.hint("...")`: the database's own hint text, passed through as is, on `select()`, `update()` and `delete()`. Never rewrite a query as a SQL template to place a hint.

```java
List<User> users = orm.entity(User.class).select()
    .where(User_.email, CONTAINS, query)
    .hint("MAX_EXECUTION_TIME(1000)")   // MySQL: SELECT /*+ MAX_EXECUTION_TIME(1000) */ ...
    .getResultList();
```

The dialect places it: a `/*+ ... */` comment after `SELECT`/`UPDATE`/`DELETE` on MySQL, MariaDB 12+ and Oracle, an `OPTION (...)` clause at the end on SQL Server, and nowhere on PostgreSQL, H2 and SQLite, which have no hint syntax (the query runs unchanged, so H2 tests pass). Several `.hint(...)` calls share one comment or clause. On SQL Server a hinted builder cannot be a subquery; hint the outer query. A hint containing `*/` or `;` is refused.

## Distinct and Count

`distinct()` removes duplicates from a select of another type, such as the cities of a set of users. It is never the way to return each root row once after a join to a to-many table: that is a `semiJoin` (see **Filtering by Related Rows**).

```java
List<City> uniqueCities = orm.entity(User.class)
    .select(City.class)
    .distinct()
    .getResultList();

long exampleCount = orm.entity(User.class)
    .selectCount()
    .where(User_.email, LIKE, "%@example.com")
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

### Navigating Through a Ref

A `Ref<T>` foreign key is still navigable in queries. Filter, order, and select through it with the metamodel; Storm adds the join for the referenced table on demand, only for a query that references a column beyond the foreign key, so filtering or ordering on the referenced table takes no explicit join of its own. Selecting the root leaves the field as an unloaded `Ref`.

```java
// User.city is Ref<City>; City has a country FK. The city and country tables are joined
// only because the query navigates beyond the city foreign key.
List<User> users = orm.entity(User.class).select()
    .where(User_.city.country.name, EQUALS, "United States")
    .orderBy(User_.city.name)
    .getResultList();

// Select a column from beyond the reference.
record CountryName(String name) {}
List<CountryName> names = orm.entity(User.class)
    .select(CountryName.class, RAW."\{User_.city.country.name}")
    .where(User_.city.country.name, EQUALS, "United States")
    .getResultList();

// Select the referenced entity itself: the city table is joined on demand and hydrated
// with its own country foreign key.
List<City> cities = orm.entity(User.class).select(City.class).getResultList();

// Join another table onto the referenced one; the join brings the city table in.
List<User> sharingACity = orm.entity(User.class).select()
    .innerJoin(User.class).on(City.class)
    .getResultList();
```

Nodes beyond a reference are navigation-only: usable in `where`, `orderBy`, `groupBy`, `having`, and selected columns, but not in value operations. Group by the reference itself with `getResultGroupedByRef`, not `getResultGroupedBy` on a beyond-reference path (which does not compile against the strict signature). Besides a path, the referenced table can be named by type: selecting it or joining onto it materializes the same join, and a query that does both joins it once; a table joined explicitly keeps that occurrence. Prefer a `Ref` for foreign keys you do not hydrate on most reads: it keeps SELECTs narrow while staying queryable. Navigation may cross more than one reference across distinct tables. A reference carries the target's primary key, so `User_.city` and `User_.city.id` both resolve to the foreign key column and neither joins — prefer the plain field, which is the same column an entity foreign key resolves its primary key to; any other column of the target joins. A self-referential foreign key must be a `Ref`, and it is navigable: the table is joined to itself, each occurrence under its own alias. The typed metamodel navigates a cycle two hops deep (generated metamodels build their children eagerly, so they cannot recurse); deeper cyclic paths are named as strings, which the engine resolves to any depth.

### Resolving a Ref as Part of the Query

A `Ref` is resolved on demand by `fetch()` on the reference itself, and that is always correct. When a read already knows it needs the referenced record, `select().fetch(path...)` folds that load into the same statement instead of a query of its own. It names the references the statement resolves: the referenced table's columns take the place of the foreign key column, so the reference comes back loaded and reading it costs no query. The record type is unchanged — the field stays a `Ref` — so the same type serves queries that resolve it and queries that do not.

```java
List<User> users = orm.entity(User.class).select()
    .fetch(User_.city, User_.city.country)
    .getResultList();

City city = users.getFirst().city().getOrThrow();   // no query; throws if the plan did not cover it
```

Read a resolved reference with `getOrThrow()`, not `fetch()`. Both return the record, but `fetch()` silently queries when the plan did not cover the path, so a `fetch(...)` lost in a refactor degrades into one query per row without failing; `getOrThrow()` never queries and reports it where the assumption was made. Use `fetch()` on the ref only where resolving on demand is the intent.

`getOrThrow()` is a claim about the query that produced the row, so assert it only where that query is in view: the same function, or a repository method whose doc names what it resolves. A method that takes an entity as a parameter cannot see the plan the entity was loaded under, and each caller settles that independently, so read its references with `fetch()`, or take the resolved record as a parameter instead of the entity.

```
isLoaded()      // is the record here?
getOrThrow()       // give it to me, never queries, throws if absent
getOrNull()     // give it to me or null, never queries
fetch()         // resolve now, query if needed
fetchOrNull()   // same, null instead of throwing
```

The plan is prefix-closed: naming `User_.city.country` resolves `User_.city` too, so write only the deepest path. A reference the plan does not name stays a foreign key column. A reference is always a to-one foreign key, so resolving one widens the row without multiplying it, and a cycle is bounded by the depth named (`fetch(Node_.parent.parent)` is exactly two levels). A nullable reference keeps its outer join, so a null foreign key yields a null reference. Paths that cross no reference, references to sealed types, and queries that do not select a record are rejected with a descriptive error.

A `Ref` therefore describes a relationship that belongs to particular queries rather than to the entity itself, and this is where such a query asks for it. See the entity skill for which relationships to declare each way.

## Filtering by Related Rows: Path, semiJoin, antiJoin or Join

Pick the form by the direction of the relation and by whether the related rows belong in the result:

| The query needs | Use |
|-----------------|-----|
| A column of a row the root references (to-one: a pet's owner) | A path: `where(Pet_.owner.lastName, EQUALS, "Davis")`, no join |
| The rows **with** a related row matching a condition (to-many: owners of a pet named Leo) | `semiJoin(Pet.class).on(Owner.class)` with the condition in `where` |
| A search over the row and its related rows (owner name and pet names) | `semiJoin(Pet.class).on(Owner.class)` with `it.search(input, Owner_.lastName, Pet_.name)` |
| The rows **without** a related row matching a condition (pets that never had a rabies shot) | `antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot"))` |
| The rows with any related row (owners with a pet) | `semiJoin(Pet.class).on(Owner.class)` |
| The rows with a related row of a related row (owners with a pet that had a visit this year) | `semiJoin(Pet.class).on(Owner.class)` then `semiJoin(Visit.class).on(Pet.class)`, the condition in `where` |
| The rows without any related row (owners without pets) | `antiJoin(Pet.class).on(Owner.class)` |
| The related rows' columns in the result, or ordering and grouping by them, a row per related row | `innerJoin(Pet.class).on(Owner.class)` or `leftJoin` |
| Related columns joined while keeping the rows without a match | `leftJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Leo"))` |

Read the request for the cue: "with", "that have", "who own", "any of their", "search by ... or their ..." over a to-many relation asks for `semiJoin`; "without", "never", "no", "missing", "not yet" asks for `antiJoin`. A semi-join or anti-join returns each root row once, so it needs no `distinct`, and scrolling, paging and `getResultCount()` count root rows.

- `semiJoin` conditions go in `where`: conditions joined by `.and()` hold for the same related row, a part naming only the root's columns holds without a related row, and `search` matches each word on its own related row.
- `antiJoin` conditions go on the join, never in `where`: a `where` naming the anti-joined table is refused.
- A semi-joined or anti-joined table adds no columns: when the result, `orderBy` or `groupBy` needs them, the query wants a regular join instead.
- Both filter `delete()` and `update()` as they filter a select: `owners.delete().antiJoin(Pet.class).on(Owner.class)` removes the owners without pets, and `owners.update().setNull(Owner_.telephone).semiJoin(Pet.class).on(Owner.class).where(Pet_.name, EQUALS, "Leo")` clears the telephone of the owners of a pet named Leo.
- Through two to-many relations, chain the semi-joins: `on` names a semi-joined table, so `.semiJoin(Pet.class).on(Owner.class).semiJoin(Visit.class).on(Pet.class).where(Visit_.visitDate, GREATER_THAN_OR_EQUAL, date)` finds the owners with a pet that had a visit since the date, each once. The visit's `EXISTS` nests in the pet's, and conditions joined by `and` on `Pet` and `Visit` hold for the same pet. Through a link table, `on` names the table the link row points at: `.semiJoin(PetSitting.class).on(Owner.class).semiJoin(Visit.class).on(Pet.class)` relates the visit to the link's `pet_id` without reading the pet table. An `antiJoin` on a semi-joined table nests as `NOT EXISTS` (owners with a pet that never had a visit); a semi-join on an anti-joined table takes its condition on the join.
- `semiJoin` and `antiJoin` are complements: on the same table with the same condition on the join, the one keeps the rows the other drops. Without a condition, `semiJoin` keeps the rows with any related row and `antiJoin` the rows without one.
- Keep `whereExists` for a subquery a foreign key does not express.

## Subqueries (EXISTS / NOT EXISTS)

`whereExists(subquery)` / `whereNotExists(subquery)` add an EXISTS condition of their own; inside the where-lambda, `WhereBuilder.exists(subquery)` / `notExists(subquery)` combine it with other conditions. Build the subquery with `orm.selectFrom(...)`; it is automatically correlated with the outer query. For a condition on the related rows, `semiJoin` and `antiJoin` are the typed form: see **Filtering by Related Rows**.

```java
// WHERE EXISTS — filter entities that have related data
List<City> citiesWithUsers = orm.entity(City.class)
    .select()
    .whereExists(orm.selectFrom(User.class))
    .getResultList();

// WHERE NOT EXISTS, combined with another condition
List<City> citiesWithoutUsers = orm.entity(City.class)
    .select()
    .where(it -> it.notExists(orm.selectFrom(User.class)).and(it.where(City_.name, LIKE, "M%")))
    .getResultList();
```

## Compound Predicates (where with WhereBuilder)

Two preferences govern every WHERE clause, and both say: **use the weakest form that compiles**.

- **Typed overloads over the lambda.** `where(User_.email, LIKE, "%@example.com")` beats `where(it -> it.where(User_.email, LIKE, "%@example.com"))`. Before a join the chained `where(path, ...)` overloads take root-typed paths — and a nested path from the root (`User_.city.country.code`) is root-typed, so navigating through a foreign key never forces the lambda. **A join widens the query**: from the join onward the same overloads accept paths from any entity in the query, so joined-entity fields need no special form. Reach for the `where(it -> ...)` lambda only for what the typed overloads cannot express: AND/OR grouping and EXISTS/NOT EXISTS.

```java
// ✅ Single condition — typed overload, no lambda
.where(User_.email, LIKE, "%@example.com")

// ❌ Lambda adds nothing for a single condition
.where(it -> it.where(User_.email, LIKE, "%@example.com"))

// ✅ Lambda earns its place for AND/OR grouping
List<User> users = orm.entity(User.class)
    .select()
    .where(it -> it.where(User_.email, LIKE, "%@example.com")
            .and(it.where(User_.email, IS_NOT_NULL))
            .or(it.where(User_.role, EQUALS, "admin")))
    .getResultList();
```

A chain reads left to right, each call grouping what came before it: the example above is `(email LIKE ... AND email IS NOT NULL) OR role = 'admin'`. Nest a call to group differently: `a.or(b.and(c))`.

Consecutive `where()` calls AND together (each clause parenthesized), so an AND of a root predicate and a joined-entity predicate is just two typed calls — no lambda needed:

```java
users.select()
    .innerJoin(UserRole.class).on(User.class)     // the join widens the query
    .where(User_.email, LIKE, "%@example.com")            // root field
    .where(UserRole_.role, EQUALS, role)          // joined entity — same overload
    .getResultList();
```

## Joined-Entity Predicates, Ordering, and Grouping

A join widens the query: from the join onward, every clause accepts paths from any entity in the query — the joined table's fields go through the same `where`, `orderBy`, `groupBy` and `having` calls as the root's. Put joins before the clauses that reference them; a path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root.

```java
users.select()
    .innerJoin(UserRole.class).on(User.class)
    .where(UserRole_.role, EQUALS, role)   // joined entity — same overloads as root fields
    .orderBy(User_.name)                   // root path
    .orderBy(UserRole_.assignedAt)         // joined entity
    .getResultList();
```

Two operations are defined relative to the root and are affected by the widening: `fetch(...)` comes before any join, and `getResultGroupedBy` needs the root back after one: `narrow(User.class)` restores it, verified against the query's FROM table. The counterpart `widen()` widens without a join — the way to reference a graph entity in short form on a query that joins nothing. (`typedId(Integer.class)` types the primary key.)

## Keyset Scrolling

Pick the read by what the caller needs to know:

| Read | Method | Result | Count query | Needs |
|---|---|---|---|---|
| Page | `page(pageable)` | `Page<R>` | yes | an ordering |
| Slice | `slice(pageable)` | `Slice<R>` | no | an ordering; a page without the count query, the only read for a query without a unique key |
| Scroll | `scroll(scrollable)` | `Window<R>` | no | a unique key; constant cost at any depth |
| Windows | `windows(size)` | `Stream<Window<R>>` | no | a primary key; one closed statement per window, so the loop may write |

Keyset scrolling navigates by position instead of offset, making it efficient for large tables. A `Scrollable<T>` states the ordering and the size; the position to continue from comes from `window.next()` / `window.previous()` or from a client's cursor string. **The request owns ORDER BY** — do NOT add `orderBy()` when using `scroll(Scrollable)`, or Storm throws `PersistenceException`.

```java
// WRONG: orderBy conflicts with the request's ordering
users.select()
    .where(User_.email, LIKE, "%@example.com")
    .orderBy(User_.name)        // ❌ the Scrollable orders
    .scroll(Scrollable.of(User_.id, 20));

// CORRECT: the request orders; sort fields go before the key, which stays the tiebreaker
users.select()
    .where(User_.email, LIKE, "%@example.com")
    .scroll(Scrollable.of(User_.id, 20).sortBy(User_.email));
```

**Ordering:** `Scrollable.of(key, size)` orders by the key ascending. `sortBy(field)` / `sortByDescending(field)` add sort fields before the key, in any number, each in its own direction; `descending()` orders the key itself descending. **Sort fields must be non-nullable**, checked the way the key is: a NULL never compares, so such a row would fall out of every window, and Storm throws a `PersistenceException` rather than paginate over rows it would silently skip. A date or status column is the usual sort field and the usual place a nullable type slips in. A newest-first feed is `Scrollable.of(Post_.id, 20).sortByDescending(Post_.createdAt).descending()`.

**Every window is in sort order.** `window.previous()` returns the window before this one in the same order as `window.next()` returns the one after it; never reverse a window for display. `hasNext()` / `hasPrevious()` say whether rows existed after / before the window at query time; the tokens are non-null whenever the window has content, and following one is always allowed.

**First request vs subsequent requests:** the ordering and the size are code; the position is the client's cursor. On the first request there is no cursor. On subsequent requests, put the same request at the client's cursor with `.from(cursor)`. The cursor is **opaque**: it carries the position only (the row's sort and key values, and whether to continue after or before it) under a fingerprint of the ordering, so a cursor issued for another ordering is refused. Clients treat it as a black box and echo it back unchanged. Server-side code never needs the cursor: `window.next()` / `window.previous()` are ready-to-use `Scrollable<T>` requests.

```java
var request = Scrollable.of(User_.id, size).sortBy(User_.email);      // ordering and size: code
var window = users.scroll(cursor != null ? request.from(cursor) : request);
String nextCursor = window.nextCursor();                              // null when nothing follows
```

**Tokens for every result type:** the sort and key values are read from the row, so `selectRef().scroll(...)`, `users.scrollRef(...)`, projections read as another type and custom select types all navigate. A compound (inline record) key is read from the mapped record and needs the entity type as the result.

**Iterate windows:** `for (User user : window)` works, `Window` is a `Slice` and iterates over its content; `window.size()`, `window.isEmpty()` and `window.stream()` exist too.

**Slices:** `select().slice(Pageable.ofSize(20).sortBy(User_.email))` is `page` without the count query: `hasNext()` from one extra row, `hasPrevious()` from the page number, the next slice from `pageable.next()`. `slice(0, 20)` uses the query's own ordering. `Slice` is the interface `Page` and `Window` implement.

## Bulk DELETE/UPDATE

`delete()` is a builder method that returns `QueryBuilder`. Call `.executeUpdate()` to execute:

```java
// DELETE with WHERE (safe) -- builder returns QueryBuilder, terminal executes
orm.entity(User.class).delete().where(User_.postalCode, IS_NULL).executeUpdate();

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User.class).delete().unsafe().executeUpdate();

// Convenience method: removeAll() executes immediately (calls unsafe() internally)
users.removeAll();
```

`update()` is the UPDATE counterpart: `set` names a column through the metamodel, the conditions select the rows, and `.executeUpdate()` runs one statement without reading the rows. Use it to write a few columns of rows the code did not read, rather than a second, partial entity for the table.

```java
// Transfer pets to a new owner
pets.update()
    .set(Pet_.owner, newOwner)
    .where(Pet_.owner, EQUALS, previousOwner)
    .executeUpdate();

visits.update()
    .set(Visit_.description, "Rescheduled")
    .where(Visit_.visitDate, EQUALS, closedDay)
    .executeUpdate();

visits.update()
    .setNull(Visit_.description)                       // nullable field; a bare null is ambiguous
    .where(Visit_.visitDate, EQUALS, closedDay)
    .executeUpdate();

// Foreign key: the entity or a Ref
pets.update()
    .set(Pet_.owner, newOwnerRef)
    .where(Pet_.owner, EQUALS, previousOwner)
    .executeUpdate();

// A value computed from the row: a template
owners.update()
    .set(Owner_.lastName, RAW."UPPER(\{Owner_.lastName})")
    .where(Owner_.address.city, EQUALS, city)
    .executeUpdate();

// A condition through a related table works on every database
visits.update()
    .set(Visit_.description, "Rescheduled")
    .where(Visit_.pet.owner.address.city, EQUALS, city)
    .executeUpdate();
```

Rules for `update()`:
- **WHERE is required** (or a `semiJoin`/`antiJoin`), as for `delete()`; `unsafe()` confirms an update of every row.
- **`set` refuses** the primary key, `@Persist(updatable = false)` fields, the `@Version` field, a path into a related table (`Owner_.address.city.name`), and a field set twice. An inline record field writes each of its columns. The `update()` and `delete()` builders refuse a Joined Table entity (sealed type or subtype): its rows span two tables; use `remove`/`removeById` and `update(entity)`.
- **`@Version` is incremented** by the statement (a timestamp version is set to the current time), so an entity read before it fails its optimistic lock check.
- **No entity callbacks fire**: `beforeUpdate`/`afterUpdate` need an entity and the statement carries none. `set` an audit column in the builder.
- **The type's entities are evicted from the transaction's entity cache** once it runs. An entity read before it still holds its old values: re-read the entities it touched before writing them back. `update(entity)` of a stale copy writes its full row, putting the old values back over the columns the statement set; a versioned entity fails its optimistic lock check instead.
- `distinct()`, `limit()`, `offset()`, locking, result reads and use as a subquery are refused.

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** SQL templates are an escape hatch for things the QueryBuilder cannot express.

**Template joins are a code smell.** If you need a template-based ON clause (`.innerJoin(T.class).on(RAW."...")`) or a full `orm.query(RAW."...")` to express a join that follows a database FK constraint, the entity model is missing an `@FK` annotation. Fix the entity first — add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields) — then the join becomes `.innerJoin(Entity.class).on(OtherEntity.class)`, pure code with no templates. Template joins are only justified when there is genuinely no FK constraint in the database. Projections join like entities: `.on(ProjectionType.class)` resolves the foreign key by matching the referenced entity's table against the projection's table. When multiple foreign keys reference that table the join is ambiguous — Storm fails with an error naming the candidate fields; disambiguate with a template ON clause.

**Prefer a path over a join.** An explicit join is not how you filter on a related table. When the relationship is a foreign key, name the column through it and Storm joins the referenced table itself, for `Ref` foreign keys as much as for eager ones. Both forms name the same occurrence, so a query that writes the join by hand and then filters through it gains nothing over the path alone:

```java
// Order holds `@FK Ref<User> user`; User holds `@FK City city`.

// ✅ The path carries the query; Storm joins the user and city tables on demand
orders.select()
    .where(Order_.user.city.name, EQUALS, "Madison")
    .getResultList();

// ❌ The same joins, spelled out, to reach a column the path already reaches
orders.select()
    .innerJoin(User.class).on(Order.class)
    .innerJoin(City.class).on(User.class)
    .where(City_.name, EQUALS, "Madison")
    .getResultList();
```

Reach for an explicit join when a path cannot express the query: a relationship the entity model does not carry as a foreign key, the to-many side of one (a path navigates to-one only), a self-join that needs its own alias, or a join whose entity you want the query widened to rather than filtered by.

**Filter by the to-many side with `semiJoin` or `antiJoin`, never with a join and `distinct`.** A join to the many side returns a row per related row, which takes `distinct` to undo, and a `DISTINCT` query can only order by the columns it selects. `semiJoin` filters without joining the related rows: every root row comes back once, so scrolling, paging and `getResultCount()` count root rows.

```java
// ✅ Owners of a Leo, each once
owners.select()
    .semiJoin(Pet.class).on(Owner.class)
    .where(Pet_.name, EQUALS, "Leo")
    .getResultList();

// ❌ A row per matching pet, folded back with distinct
owners.select()
    .distinct()
    .innerJoin(Pet.class).on(Owner.class)
    .where(Pet_.name, EQUALS, "Leo")
    .getResultList();

// ✅ Pets without a rabies shot: the anti-join's condition goes on the join, never in where
pets.select()
    .antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot"))
    .getResultList();
```

Conditions joined by `.and()` on a semi-joined table hold for the same related row; a part of the condition naming only the query's own tables holds without a related row, so an `.or()` of `Owner_.lastName` and `Pet_.name` also finds a Davis without pets. A semi-joined table adds no columns: ordering, grouping or selecting by it is refused. A `where` condition naming an anti-joined table is refused; it belongs on the join.

**Recognize it wherever the join hangs.** A select of the query's own table with `distinct()` and an inner join whose columns the result, the ordering and the grouping never use is this pattern, whatever table the join is on. `on(T.class)` names any table of the query, including one the root reaches through a path, so `semiJoin(Related.class).on(T.class)` replaces `.distinct().innerJoin(Related.class).on(T.class)` one for one, and the query no longer needs the `distinct`. A chain of inner joins through two to-many relations with `distinct`, such as owner to pet to visit or a link table and the table hanging off the row it points at, is the same pattern: each join becomes a semi-join on the table before it, `.semiJoin(Pet.class).on(Owner.class).semiJoin(Visit.class).on(Pet.class)`. A comment calling the `distinct` required because of the join marks the same pattern, not an exception. When a change touches such a query for another reason, replace it in the same change.

Three rules:

1. **Code-first:** If it can be done with QueryBuilder methods (joins, where, orderBy, groupBy, having), do it in code. Never use a template string for a `WHERE` clause that could be a `.where(field, EQUALS, value)`, or an `ORDER BY` that could be `.orderBy(field)`.
2. **Metamodel in templates:** When you do need a template fragment (e.g., for `COUNT(*)` in a select clause), still use metamodel references inside it (`\{User_.email}`, not `"email"`). This keeps column references type-safe and refactor-proof.
3. **Full SQL last resort:** A full `SELECT ... FROM ...` SQL template should only be used for totally custom queries (CTEs, UNIONs, window functions) that cannot be built at all with the QueryBuilder. Even then, users still benefit from bind variables (`\{value}`) and metamodel references (`\{Entity_.field}`).

When you do use template strings, use `RAW."""..."""` (Java string templates with `--enable-preview`) — never use `TemplateString.raw()`.

Operators: `EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LIKE`, `NOT_LIKE`, `CONTAINS`, `STARTS_WITH`, `ENDS_WITH`, `EQUALS_IGNORE_CASE`, `CONTAINS_IGNORE_CASE`, `STARTS_WITH_IGNORE_CASE`, `ENDS_WITH_IGNORE_CASE` (each text operator with its `NOT_` form), `IS_NULL`, `IS_NOT_NULL`, `IS_TRUE`, `IS_FALSE`, `IN`, `NOT_IN`, `BETWEEN`

The `EQUALS` operator accepts both entities and `Ref<T>`. When you have an entity, use it directly — no need to convert to a `Ref` first.

When you only have an ID (e.g., from a URL parameter), create a `Ref` — don't construct a dummy entity with empty fields:
```java
.where(User_.city, EQUALS, Ref.of(City.class, cityId))   // ✅
// ❌ new City(cityId, "", null)
```

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
    users.select().where(User_.city, EQUALS, city).windows(1000).forEach(window ->
        users.update(window.content().stream().map(user -> /* copy with changes */ user).toList()));
    return null;
});

// Resume after a restart from a stored cursor:
users.windows(Scrollable.of(User_.id, 1000).from(storedCursor)).forEach(window -> {
    process(window.content());
    store(window.nextCursor());
});
```

`windows(size).flatMap(Slice::stream)` reads the windows as one `Stream<R>` for a loop that handles one row at a time: every row comes from a window whose statement has closed, so the connection is free at each row and memory stays bounded by the window size. It carries no database resource, so it needs no closing; where the loop writes, the per-window form batches better.

Rules for `windows`: the key is the primary key (or the `Scrollable`'s key), which must be a non-null single column; no `orderBy()` on the query; the result type must be the entity (`selectRef()` and custom select types are refused). Each window is its own statement and sees the committed state at that moment.

What stays fine with `getResultStream()`: consuming it (`forEach`, `toList()`, `count()`, `map`, `filter`), stopping early (`findFirst()`, `limit(n)`, then closing it), and, once it has been read to its end, any statement. A `Ref` the loop needs is loaded by naming it in the fetch plan (`select().fetch(...)`) instead of calling `fetch()` per row. Outside a transaction a stream holds a pooled connection of its own until it is closed.

## Result Retrieval

QueryBuilder terminals:
- `.getResultList()` → `List<R>`
- `.getSingleResult()` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.getOptionalResult()` → `Optional<R>`
- `.getResultCount()` → `long`
- `.getResultStream()` → `Stream<R>` (lazy, **must** close with try-with-resources)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(scrollable)` → `Window<R>` (keyset scrolling — do NOT combine with `orderBy()`, see Keyset Scrolling section). Use `next()` / `previous()` for programmatic navigation, or `nextCursor()` / `previousCursor()` for REST APIs.
- `.executeUpdate()` → `int` (for DELETE/UPDATE)

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value.
- DELETE/UPDATE without WHERE throws. Use `unsafe()`.
- Streaming: `select().getResultStream()` returns a `Stream`. ALWAYS use try-with-resources to avoid connection leaks. There are no `selectBy` methods that return Stream directly -- always use `select()` (optionally with predicate) and then `.getResultStream()`.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key.
- **Typed ID from `Ref`:** Use `Ref.entityId(ref)` to extract a type-safe ID. For projections, use `Ref.projectionId(ref)`. Avoid `ref.id()` — it returns `Object` and requires an unsafe cast.

## Verification

After writing queries, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the query produces the result the user intended — correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

```java
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
// Without it, paths resolve relative to the test class's package.
@StormTest(scripts = {"/schema.sql", "/data.sql"})
class UserQueryTest {
    @Test
    void findExampleUsersInCity(ORMTemplate orm, SqlCapture capture) {
        City city = orm.entity(City.class).findById(1).orElseThrow();
        List<User> users = capture.execute(() ->
            orm.entity(User.class).select()
                .where(User_.city, EQUALS, city)
                .orderBy(User_.name)
                .getResultList());
        // Verify intent: single query, only example.com users in the given city, ordered by name.
        assertEquals(1, capture.count(Operation.SELECT));
        assertFalse(users.isEmpty());
        assertTrue(users.stream().allMatch(u -> u.city().equals(city) && u.email().endsWith("@example.com")));
    }
}
```

Keep the verification loop on H2: it answers in milliseconds and needs no Docker, which is what makes verify-and-fix iterations cheap. Escalate to the target database only when H2 cannot run the SQL involved (dialect-specific functions, JSON operators, upsert or sequence syntax the target database defines differently), when the user asks for it, or as a final pass before finishing a larger piece of work: adding `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`, from `st.orm.test.TestDatabase`) to `@StormTest` runs the same test in a Testcontainers-managed container of that database, at the cost of a container start per test run and a Docker requirement. Nothing else in the test changes; the class needs the database's Testcontainers module and JDBC driver in test scope (see /storm-setup).

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

`SqlCapture` answers whether a query is correct. To find which query is expensive in a running application, raise the `st.orm.sql.perf` logger and read the per-call summary: it ranks statements by total time and shows which ones repeat, which resolve references on demand, and which hydrate more graph than they use. See the repository skill for how to act on each signal.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
