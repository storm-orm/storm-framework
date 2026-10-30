---
name: storm-query-java
description: Write Storm queries in Java with the QueryBuilder, covering joins, predicates, ordering, pagination, keyset scrolling, and Ref navigation. Use for any read query in Java.
---

Help the user write Storm queries using Java.

**Write queries with Storm's QueryBuilder and operator-based predicates, never JPQL, `CriteriaBuilder` or `EntityManager.createQuery()`.** Storm can run on top of JPA, but its queries go through its own API.

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

**Never import from `st.orm.core.*`.** Those are Storm's internal core-engine packages. The Java API lives in `st.orm.repository` and `st.orm.template`. `st.orm.Operator` is an interface with static constants, static-importable like an enum; the full list is under **Predicate Operators**.

Ask what data they need, filters, ordering, or pagination.

**DI preference: constructor-inject repositories in Spring Boot projects** (see /storm-repository-java). Use `orm.entity(T.class)` and `orm.repository(T.class)` lookups only in standalone (non-DI) contexts and tests. In DI environments, write queries on injected repository instances.

**Layering rule: follow the codebase's existing convention first.**
- If handlers already use repositories directly, or a service layer is consistently in place, match that style rather than introduce a competing one.
- Absent a clear stance (new code, greenfield), promote controller → service → repository. Controllers never inject repositories: all data access flows through services, which own the transaction boundaries (e.g. `@Transactional` on service methods) and return view-model types.
- Never mix styles. Layer-skipping controllers undermine the service layer's cross-cutting concerns (transactions, caching, authorization).

## API Design: Builder Methods vs Convenience Methods

Repository/entity methods fall into two categories.

**Builder methods** return `QueryBuilder` for composable, chainable queries. They never execute immediately:
- `select()`: build SELECT queries
- `selectRef()`: build SELECT queries returning Refs
- `selectCount()`: build COUNT queries
- `update()`: build UPDATE queries that `set` columns on the rows a condition selects
- `delete()`: build DELETE queries

The `select(predicate)` / `delete(predicate)` shorthands are Kotlin-only. In Java, chain `.where(...)` on the builder.

Terminal operations: `.getResultList()`, `.getSingleResult()`, `.getOptionalResult()`, `.getResultStream()`, `.windows(size)`, `.getResultCount()`, `.getResultGroupedBy(path)`, `.getResultGroupedByRef(path)`, `.page()`, `.scroll()`, `.executeUpdate()`

**Load one-to-many with `getResultGroupedBy(path)`.** It groups results by a related record, typically the parent entity of a foreign key, into an unmodifiable, insertion-ordered `Map<Parent, List<T>>`.
- The same select runs (single query, no SQL change); the grouping happens during hydration.
- Repeated parents are materialized once and grouped by instance identity, so the join's duplication is not paid during hydration.
- The grouping does not depend on row order: a parent collects its children wherever they fall in the result set.
- Without an `orderBy()` the map and its lists follow the order the database returns. Order by what you want ordered, not by the parent path: `orderBy(User_.city.name, User_.email)` gives the cities alphabetically with their users by email.
- The path must resolve non-null for every result.
- `getResultGroupedByRef(path)` returns `Map<Ref<Parent>, List<T>>` with keys compared by primary key. For eager entity paths the keys are loaded refs (`getOrNull()` returns the already-materialized record). For `Ref` foreign-key fields it groups on the foreign key without fetching the parent; `findAllByRef(map.keySet())` fetches the parents in one query when needed.

```java
// Load cities with their users in one query
Map<City, List<User>> usersByCity = orm.entity(User.class)
    .select()
    .getResultGroupedBy(User_.city);
```

**Convenience methods** execute immediately and return results directly:
- `findById()`, `findByRef()`, `findAll()`, `findAllRef()`, `findBy()`, `findAllBy()`, `getById()`, `getByRef()`, `getBy()`, `count()`, `exists()`, `remove()`, `removeById()`, `removeByRef()`, `removeAll()`, `removeAllBy()`, `page()`, `pageRef()`, `scroll()`

**`remove` acts on what you hold; `delete` builds a query.** `remove` operates on entities or ids you already have and executes immediately. `delete` builds a query that finds and deletes rows by criteria and returns `QueryBuilder`. Updates split the same way: `update(entity)` writes an entity you hold, and the `update()` builder sets columns on the rows a condition selects (see **Bulk DELETE/UPDATE**).

**Prefer the simplest approach that works.** Three query levels, from simplest to most powerful:

| Level | Approach | Best for |
|-------|----------|----------|
| 1 | Convenience methods (`findBy`, `findAllBy`, `removeAllBy`, `countBy`, `existsBy`) | Simple lookups and operations |
| 2 | Builder chained (`select().where(...)`, `update().set(...).where(...)`, `delete().where(...)`) | Most application queries needing ordering, pagination, or joins |
| 3 | SQL Templates (/storm-sql-java) | CTEs, window functions, database-specific features |

### When to use each, and when NOT to

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

**Escalate only when the simpler level cannot express what you need.** If you need ordering, you need Level 2. If you need CTEs or window functions, you need Level 3.

**Level 1: convenience methods** (execute immediately, no terminal needed):
```java
var users = orm.entity(User.class);
Optional<User> user = users.findBy(User_.email, email);
List<User> list = users.findAllBy(User_.city, city);
long count = users.count();
```

**Level 2: builder** (returns `QueryBuilder`; chain ordering, pagination and a terminal):
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

Builder quick reference:
- Nested paths: `User_.city.country.code` with the appropriate operator.
- Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`.
- Limit/Offset: `.limit(10)`, `.offset(20)`.
- Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`.
- Scrolling (keyset): `.scroll(Scrollable.of(User_.id, 20))`. Never combine it with `orderBy()`: the `Scrollable` manages ORDER BY (see **Keyset Scrolling**).
- Streaming: `.getResultStream()` is consume-only while rows remain; a loop that queries or writes per row uses `.windows(size)` (see **Streams and the Connection**).
- Explicit joins: `.innerJoin(Entity.class).on(OtherEntity.class)`, `.leftJoin(Entity.class).on(OtherEntity.class)`, `.rightJoin(Entity.class).on(OtherEntity.class)`.
- A condition on the join: `.leftJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Leo"))` adds the condition to the resolved foreign key. On a left join it keeps the rows without a match, which the same condition in `where` would drop.
- Filtering by related rows: `.semiJoin(Pet.class).on(Owner.class)` keeps the rows with a related row matching the conditions that name it, each once; `.antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot"))` keeps the rows without one.
- Result type: `.select(ResultType.class)` returns a different type than the root entity.

**Auto-join types follow FK nullability.** A `@FK` record component is non-null by default, so its auto-join is an INNER JOIN. Mark the component `@Nullable` (JSpecify `org.jspecify.annotations.Nullable` or `jakarta.annotation.Nullable`) when the FK column allows NULL; that produces a LEFT JOIN. If generated SQL shows INNER JOIN where you expect LEFT JOIN, the entity's FK component is missing `@Nullable`.

**Cross-entity pitfall: put the query on the target entity's repository.** Selecting a different entity type from the wrong root repository can fail with "Cannot find alias for column" when both entities have columns with the same name (e.g., `id`).

**Always prefer entity/metamodel-based QueryBuilder methods over SQL template strings.** SQL templates are an escape hatch for what the QueryBuilder cannot express. Three rules:

1. **Code-first:** If it can be done with QueryBuilder methods (joins, where, orderBy, groupBy, having), do it in code. Never use a template string for a `WHERE` clause that could be a `.where(field, EQUALS, value)`, or an `ORDER BY` that could be `.orderBy(field)`.
2. **Metamodel in templates:** When you do need a template fragment (e.g., for `COUNT(*)` in a select clause), still use metamodel references inside it (`\{User_.email}`, not `"email"`). This keeps column references type-safe and refactor-proof.
3. **Full SQL last resort:** Use a full `SELECT ... FROM ...` SQL template only for totally custom queries (CTEs, UNIONs, window functions) that the QueryBuilder cannot build at all. Even then, use bind variables (`\{value}`) and metamodel references (`\{Entity_.field}`).

Write template strings as `RAW."""..."""` (Java string templates with `--enable-preview`), never `TemplateString.raw()`.

**Template joins are a code smell: fix the entity.** A template ON clause (`.innerJoin(T.class).on(RAW."...")`) or a full `orm.query(RAW."...")` for a join that follows a database FK constraint means the entity model is missing an `@FK` annotation. Add `@FK` (with `Ref<T>` for PK fields, full entity for non-PK fields); the join becomes `.innerJoin(Entity.class).on(OtherEntity.class)`, pure code. A template join is justified only when the database has no FK constraint.

**Projections join like entities.** `.on(ProjectionType.class)` resolves the foreign key by matching the referenced entity's table against the projection's table. When several foreign keys reference that table the join is ambiguous: Storm fails with an error naming the candidate fields. Disambiguate with a template ON clause.

## Predicate Operators

Operators (static constants of `st.orm.Operator`): `EQUALS`, `NOT_EQUALS`, `LESS_THAN`, `LESS_THAN_OR_EQUAL`, `GREATER_THAN`, `GREATER_THAN_OR_EQUAL`, `LIKE`, `NOT_LIKE`, `CONTAINS`, `NOT_CONTAINS`, `STARTS_WITH`, `NOT_STARTS_WITH`, `ENDS_WITH`, `NOT_ENDS_WITH`, `EQUALS_IGNORE_CASE`, `NOT_EQUALS_IGNORE_CASE`, `CONTAINS_IGNORE_CASE`, `NOT_CONTAINS_IGNORE_CASE`, `STARTS_WITH_IGNORE_CASE`, `NOT_STARTS_WITH_IGNORE_CASE`, `ENDS_WITH_IGNORE_CASE`, `NOT_ENDS_WITH_IGNORE_CASE`, `IS_NULL`, `IS_NOT_NULL`, `IS_TRUE`, `IS_FALSE`, `IN`, `NOT_IN`, `BETWEEN`.

**Compare a foreign key with the entity or a `Ref`, never its id.** `.where(User_.city, EQUALS, city)` compares by FK: `EQUALS` accepts both entities and `Ref<T>`, so pass the entity directly, without converting it to a `Ref`. When you only have an ID (e.g., from a URL parameter), create a `Ref` instead of a dummy entity with empty fields:
```java
.where(User_.city, EQUALS, Ref.of(City.class, cityId))   // ✅
// ❌ new City(cityId, "", null)
```

**Pass the whole list to `IN`.** Never chunk ids by hand, build one query per key, or write the list into a template to control the shape.
- On a database that binds a list as one array parameter (`id = ANY(?)`), `IN`, `findAllById` and `whereId` render one statement whatever the list's length. The database plans and caches one statement for a varying number of keys, faster than a placeholder per value.
- Elsewhere each value has a placeholder.
- Where a database's plan cache fills with the different lengths, `storm.in_list` set to `padded` renders a placeholder per value, padded to the next power of two. It changes no result, and it forgoes the array parameter where the database has one, which is faster.
- The `in-list-shapes` advice says when to set it. It is reported once a statement has run with eight different list lengths: in the advice log in development, on a slow line in production. A database that keeps no plan per statement text gets none, since every length costs it the same there.
- `storm.in_list=exact` keeps a placeholder per value everywhere.

**Search text goes through `CONTAINS`, `STARTS_WITH` or `ENDS_WITH`, never through `LIKE`.** Text a user typed can hold `%`, `_`, `[` or a backslash, which `LIKE` reads as wildcards or escapes, differently per database. The text operators escape it for the dialect and add the wildcards themselves, so `.where(User_.name, CONTAINS, query)` matches exactly what was typed.
- Never build a pattern such as `"%" + query + "%"`, and never write an escape helper.
- Keep `LIKE` for patterns the code writes itself.
- The text operators apply to a single `String` column. Case sensitivity follows the column's collation, as it does for `LIKE`.

**Text compared whatever its case goes through the `IGNORE_CASE` operators, never through a `LOWER(...)` template.** `EQUALS`, `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` follow the column's collation. A database whose default collation ignores case answers them differently from one whose collation does not.

`EQUALS_IGNORE_CASE`, `CONTAINS_IGNORE_CASE`, `STARTS_WITH_IGNORE_CASE` and `ENDS_WITH_IGNORE_CASE`, with their `NOT_` forms, have the database lower both sides. The text ones still match their text literally: `.where(User_.name, CONTAINS_IGNORE_CASE, query)`.
- A search box and a lookup by email address or user name want these. A code, a token or an id compared exactly wants `EQUALS`.
- They ignore case only. Accents follow the column's collation: ignored on a database whose default collation ignores accents, compared as they are elsewhere.
- A plain index does not serve the lowered column. Where an index must serve the lookup, index the lowered expression, or keep the plain operator on a column whose collation already ignores case.

### Search

**A search box goes through `search`, never through hand-built `CONTAINS_IGNORE_CASE` chains.** `.where(it -> it.search(input, Movie_.title, Movie_.director.name))` takes the input as the box receives it. It returns a predicate, so it combines with `.and()` and `.or()`.
- Whitespace separates the words, and a quoted phrase is one word.
- Every word must occur in at least one of the columns, ignoring case, literally.
- An empty input matches every row.
- Never split the input, reduce over the words or special-case the empty box in application code.
- To search the rows related to each row, such as an owner's pets, semi-join the related table and name its column: `.semiJoin(Pet.class).on(Owner.class).where(it -> it.search(input, Owner_.lastName, Pet_.name))`. Each word may then match a different related row, and every owner comes back once. Columns of several semi-joined tables may share one search, each answered by its own table's rows.

**A search box that queries as the user types goes through `suggest`, not `search`.** An auto-complete, a typeahead or any box that fetches on every keystroke uses `.where(it -> it.suggest(input, Movie_.title, Movie_.director.name))`. It takes the same input and paths and matches the same way, on the words the database's substring index serves alone.
- A word takes part from two characters, in the form the database's index serves: on PostgreSQL anywhere from three characters and at the start of a word below that; on every other database as a prefix of the bare column with the word lowered, `title LIKE ?`, which an ordinary index over the column serves. There the column must compare case-insensitively on its own: a case-insensitive collation (the default on MySQL, MariaDB and SQL Server), a `NOCASE` index on SQLite, or a stored lowered column declared as a query column (H2, Oracle). Never wrap the column in `LOWER()` in the schema's index for a suggestion box on those databases; index the column itself.
- A one-character word takes no part. An input without a word that takes part, an empty one included, matches no rows, so an index serves every statement `suggest` renders however the user types.
- The search page the box leads to keeps `search`, which matches every word.
- Never filter short words or an empty input out in application code: `suggest` does it per dialect.
- A word the index serves but which occurs in a large share of the rows, such as `the` in a catalog of titles, is the box's to name. `it.suggest(input, Set.of("the", "a", "of"), Movie_.title)` ignores those words as it ignores short ones, compared ignoring case: `the god` matches on `god` alone, and `the` alone matches no rows.
- Keep that set a constant next to the box. Never strip the words from the input in application code, and never pass the set to `search`, which takes none.

**Rank search results with `orderFirst`, never with a `CASE` template.** `search` and `suggest` decide which rows match, not which come first. `orderFirst(predicate)` puts the rows matching the predicate first and appends a sort key as `orderBy` does, so the calls apply in order. This lists an exact title, then titles starting with the input, then the most popular:
```java
.orderFirst(it -> it.where(Movie_.title, EQUALS_IGNORE_CASE, input))
.orderFirst(it -> it.where(Movie_.title, STARTS_WITH_IGNORE_CASE, input))
.orderByDescending(Movie_.votes)
```
- The operators match the input literally and ignoring case, as in `where`. Combine columns with `.or(...)`.
- Rank only by columns of the queried or joined tables. A semi-joined table adds no columns, so its columns are refused.
- A keyset `scroll` follows `orderFirst`: its keys lead the `Scrollable`'s sort fields and key, and the cursor carries the ranks. A `Scrollable` may sort by a field of a joined table, such as the votes of a joined rating.
- `orderFirst` cannot order a `distinct` query.
- No index serves the sort, which is fine after a search's few matches.

**A search needs an index built for it, created in the migration with the query.** `search` and the `IGNORE_CASE` text operators compare `LOWER(column) LIKE LOWER('%word%')`: a lowered expression with a leading wildcard, which no B-tree index serves. Without an index for it, each search reads and lowers every row of every table it names. That goes unnoticed on test data and takes seconds on a production-sized table.
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
- The repository declares `SqlFunction<String> foldAccents = SqlFunction.of("fold_accents")` once.
- The query searches `it.search(input, foldAccents.apply(Movie_.title))` and ranks with `orderFirst(it -> it.where(foldAccents.apply(Movie_.title), EQUALS_IGNORE_CASE, input))`.

**The database folds both sides; the application folds neither.** A comparison applies the function to the column and to the input.
- Never remove accents from the input in application code or in a query of its own.
- Never add an entity field holding the folded text.
- Never wrap the column in an accent function inside a template, which the search operators cannot use.
- Name exactly the function the index is built on; another expression is not served by it.
- A `Scrollable` refuses a function call as key or sort field.

**Store the folded text in a generated column where the function's recheck dominates.** That shows on a column where a common word matches many thousands of rows, since the database rechecks the function on every candidate row.
- Store it as `title_folded VARCHAR GENERATED ALWAYS AS (fold_accents(title)) STORED NOT NULL` and index `LOWER(title_folded)`.
- Declare it as a query column on the entity that names the function: `@QueryColumn(field = "titleFolded", type = String.class, function = "fold_accents")`. No row carries it (see the entity skill), and a value compared with it passes through the function on the value side.
- `it.search(input, Movie_.titleFolded)` renders `LOWER(m.title_folded) LIKE LOWER(fold_accents(?))`: the column as stored, and the input folded by the database in the same statement.

### Naming a foreign key column

**Name a foreign key by the field itself, never by `.id`.** A foreign key field already resolves to the foreign key column, so `User_.city` and `User_.city.id` are the same column and neither joins the target table. `.id` reads as a navigation step into `City` and suggests a join that is not there. The rule holds everywhere a column is named: predicates, template select lists, `groupBy`, `orderBy`, and aggregate expressions.

```java
record CityUserCount(@FK Ref<City> city, long userCount) {}

// ✅ The foreign key field names the foreign key column
List<CityUserCount> counts = orm.entity(User.class)
    .select(CityUserCount.class, RAW."\{User_.city}, COUNT(*)")
    .groupBy(User_.city)
    .getResultList();

// ❌ Redundant `.id`: identical SQL, and it reads like a join into City
List<CityUserCount> counts = orm.entity(User.class)
    .select(CityUserCount.class, RAW."\{User_.city.id}, COUNT(*)")
    .groupBy(User_.city.id)
    .getResultList();

COUNT(DISTINCT \{User_.city})       // ✅ inside a template
COUNT(DISTINCT \{User_.city.id})    // ❌ same column, longer
```

**Filter on a foreign key with the refs themselves.** Use `whereRef(path, refs)` rather than unwrapping refs into ids to compare against the same column.

```java
.whereRef(User_.city, cityRefs)                                          // ✅ IN over refs
.where(User_.city.id, IN, cityRefs.stream().map(Ref::entityId).toList())  // ❌ unwraps refs to reach the column they already name
```

**A compound foreign key is several columns, and templates take only one.** A foreign key to an entity whose `@PK` is an inline record is a multi-column foreign key: Storm matches its columns to the target's primary key columns one for one. Code-first operators expand it. A template placeholder stands for a single column, so interpolating one throws `SqlTemplateException: Expected exactly one column for metamodel: …` before any SQL reaches the database.

```java
// ✅ Code-first expands the compound key
.where(Order_.line, EQUALS, orderLine)   // WHERE o.order_id = ? AND o.line_no = ?
.groupBy(Order_.line)                    // GROUP BY o.order_id, o.line_no: same columns, no join

// ❌ Template placeholders reject it: SqlTemplateException, not broken SQL
RAW."COUNT(DISTINCT \{Order_.line})"
RAW."\{Order_.line}, COUNT(*)"
RAW."sub.line_id = \{Order_.line}"
```

- The naming rule holds for single-column foreign keys, the overwhelmingly common case.
- A compound key cannot appear in a template expression at all. Name one of its leaves (`Order_.line.lineNo`, which joins the referenced table) or restructure the query to the code-first form.
- `Order_.line.id` and `Order_.line` resolve to the same foreign key column(s) on the referencing table, without a join, for `Ref` and entity foreign keys alike.
- The same single-column assumption is why keyset scrolling rejects a composite key (see **Keyset Scrolling**).

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

**Computed aggregates (COUNT, AVG, SUM, etc.) go in a SELECT template only.** When the SELECT clause needs expressions that QueryBuilder can't produce, use `select(ResultType.class, RAW."template")` for the SELECT only. Keep joins, groupBy, having, orderBy, and limit in code.

**`Data` means "represents a table".** Only types that map to a database table implement `Data` (or its subinterfaces `Entity`/`Projection`). Ad-hoc query result types, such as aggregation DTOs and computed shapes, are plain records with no marker. Storm maps result columns to any suitable record, including nested entity and `Ref<T>` components.
- Implementing `Data` on a result type pulls it into Storm's type discovery, so schema validation (`validateSchema()` without arguments, or startup schema validation) fails with `TABLE_NOT_FOUND`, which then requires `@DbIgnore` to suppress.
- `implements Data` plus `@DbIgnore` on a result type is an anti-pattern: opting in and immediately opting out. A plain record expresses the intent directly.
- Define result types next to the code that produces them, typically in or beside the repository whose queries return them. The entity package is reserved for table-backed types.
- Document them as query result shapes (not backed by a table or view), so readers see why they carry no marker.
- For a GraalVM native image, annotate each result type with `@GenerateMetamodel` (`st.orm`). It is not a table marker, so schema validation still skips it: the metamodel processor gives the record an instantiator, and the native-image feature registers it. Without it the native image cannot construct the row, and the first query fails at runtime. On the JVM the annotation is optional: the row is constructed reflectively.

```java
/**
 * Query result shape: the pets of each species. Not backed by a database
 * table or view, so it is a plain record; @GenerateMetamodel lets a native
 * image construct it.
 */
@GenerateMetamodel
public record SpeciesCount(Species species, long pets) {}
```

**The `RAW."template"` provides the SELECT list only, not a full SQL query.** A full `SELECT ... FROM ... WHERE ...` inside it is wrapped as a scalar subquery and fails. For full custom SQL, use `orm.query(RAW."...").getResultList(T.class)` (see /storm-sql-java).

```java
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain record, deliberately not a Data type.
 */
record CityUserCount(City city, long userCount) {}

List<CityUserCount> cityCounts = orm.entity(City.class)
    .select(CityUserCount.class, RAW."\{City.class}, COUNT(*)")
    .leftJoin(User.class).on(City.class)
    .groupBy(City_.id)
    .getResultList();

// More complex example with WHERE, HAVING, and ORDER BY, all in code:
record CityUserStats(String cityName, double averageAge, long userCount) {}

int minUsers = 10;
List<CityUserStats> topCities = orm.entity(City.class)
    .select(CityUserStats.class, RAW."\{City_.name}, AVG(\{User_.age}), COUNT(*)")
    .leftJoin(User.class).on(City.class)
    .groupBy(City_.name)
    .having(RAW."COUNT(*) >= \{minUsers}")               // template form for aggregate expressions
    .orderByDescending(RAW."AVG(\{User_.age})")
    .getResultList();

// Multi-field groupBy: always use the varargs metamodel form
record CityPostalCodeCount(@FK Ref<City> city, String postalCode, long userCount) {}

List<CityPostalCodeCount> counts = orm.entity(User.class)
    .select(CityPostalCodeCount.class, RAW."\{User_.city}, \{User_.postalCode}, COUNT(*)")
    .groupBy(User_.city, User_.postalCode)    // ✅ varargs metamodel form
    .getResultList();

// ❌ Don't use template when metamodel fields work:
//    .groupBy(RAW."\{User_.city}, \{User_.postalCode}")
// ✅ Use varargs metamodel form, code-first and type-safe:
//    .groupBy(User_.city, User_.postalCode)
```

**Use `Ref<T>` for a foreign key in an aggregation result type.** When the SELECT clause references a FK field (`\{User_.city}`) rather than a full entity (`\{City.class}`), the component is `Ref<City>`, not the raw ID type and not the full entity; it maps to the FK column value. Use the full entity type only when the SELECT includes all its columns via `\{City.class}`.

**Always prefer code over templates.** Templates are for expressions QueryBuilder can't produce (e.g., `COUNT(*)`, `AVG()`).
- `groupBy`, `having`, and `orderBy` accept template forms for computed expressions (e.g., `.having(RAW."COUNT(*) >= \{min}")`, `.orderByDescending(RAW."AVG(\{User_.age})")`).
- Otherwise **always use the varargs metamodel form for `groupBy`** and **the metamodel form for `orderBy`**.
- Never write the entire query as a raw SQL string.

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

**Add a hint with `.hint("...")` only when a query needs one to keep its plan.** The text is the database's own hint text, passed through as is, on `select()`, `update()` and `delete()`. Never rewrite a query as a SQL template to place a hint.

```java
List<User> users = orm.entity(User.class).select()
    .where(User_.email, CONTAINS, query)
    .hint("MAX_EXECUTION_TIME(1000)")   // on a database that reads hints from a comment: SELECT /*+ MAX_EXECUTION_TIME(1000) */ ...
    .getResultList();
```

The dialect places the hint:
- A database that reads hints from a comment after the keyword gets a `/*+ ... */` comment after `SELECT`/`UPDATE`/`DELETE`.
- A database that reads hints from an `OPTION (...)` clause at the end gets that clause. There a hinted builder cannot be a subquery; hint the outer query.
- A database that has no hint syntax runs the query unchanged, so tests on the in-memory database `@StormTest` provides by default pass.
- Several `.hint(...)` calls share one comment or clause.
- A hint containing `*/` or `;` is refused.

## Distinct and Count

**Use `distinct()` to remove duplicates from a select of another type, such as the cities of a set of users.** It is never the way to return each root row once after a join to a to-many table: that is a `semiJoin` (see **Filtering by Related Rows**).

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

**A `Ref<T>` foreign key is navigable in queries.** Filter, order, and select through it with the metamodel. Storm joins the referenced table on demand, only for a query that references a column beyond the foreign key, so filtering or ordering on the referenced table needs no explicit join. Selecting the root leaves the field as an unloaded `Ref`.

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

- Nodes beyond a reference are navigation-only: usable in `where`, `orderBy`, `groupBy`, `having`, and selected columns, but not in value operations.
- Group by the reference itself with `getResultGroupedByRef`. `getResultGroupedBy` on a beyond-reference path does not compile against the strict signature.
- The referenced table can also be named by type: selecting it or joining onto it materializes the same join, and a query that does both joins it once. A table joined explicitly keeps that occurrence.
- Navigation may cross more than one reference across distinct tables.
- A reference carries the target's primary key, so `User_.city` and `User_.city.id` both name the foreign key column without a join (see **Naming a foreign key column**). Any other column of the target joins.
- A self-referential foreign key must be a `Ref`, and it is navigable: the table is joined to itself, each occurrence under its own alias.
- The typed metamodel navigates a cycle two hops deep, because generated metamodels build their children eagerly and cannot recurse. Name deeper cyclic paths as strings, which the engine resolves to any depth.

**Prefer a `Ref` for foreign keys you do not hydrate on most reads.** It keeps SELECTs narrow while staying queryable.

### Resolving a Ref as Part of the Query

**When a read knows it needs the referenced record, resolve it with `select().fetch(path...)`.** `fetch()` on the reference itself resolves on demand and is always correct. `select().fetch(...)` folds that load into the same statement: the referenced table's columns take the place of the foreign key column, so the reference comes back loaded and reading it costs no query. The field stays a `Ref`, so one record type serves queries that resolve it and queries that do not.

```java
List<User> users = orm.entity(User.class).select()
    .fetch(User_.city, User_.city.country)
    .getResultList();

City city = users.getFirst().city().getOrThrow();   // no query; throws if the plan did not cover it
```

**Read a resolved reference with `getOrThrow()`, not `fetch()`.** Both return the record, but `fetch()` silently queries when the plan did not cover the path. A `fetch(...)` lost in a refactor then degrades into one query per row without failing. `getOrThrow()` never queries and reports the gap where the assumption was made. Use `fetch()` on the ref only where resolving on demand is the intent.

**Assert `getOrThrow()` only where the query that produced the row is in view.** That is the same function, or a repository method whose doc names what it resolves. A method that takes an entity as a parameter cannot see the plan the entity was loaded under, and each caller settles that independently. Read its references with `fetch()`, or take the resolved record as a parameter instead of the entity.

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
| A column of a row the root references (to-one: a pet's owner) | A path: `where(Pet_.owner.lastName, EQUALS, "Davis")`, no join |
| The rows **with** a related row matching a condition (to-many: owners of a pet named Leo) | `semiJoin(Pet.class).on(Owner.class)` with the condition in `where` |
| A search over the row and its related rows (owner name and pet names) | `semiJoin(Pet.class).on(Owner.class)` with `it.search(input, Owner_.lastName, Pet_.name)` |
| The rows **without** a related row matching a condition (pets that never had a rabies shot) | `antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot"))` |
| The rows with any related row (owners with a pet) | `semiJoin(Pet.class).on(Owner.class)` |
| The rows with a related row of a related row (owners with a pet that had a visit this year) | `semiJoin(Pet.class).on(Owner.class)` then `semiJoin(Visit.class).on(Pet.class)`, the condition in `where` |
| The rows without any related row (owners without pets) | `antiJoin(Pet.class).on(Owner.class)` |
| The related rows' columns in the result, or ordering and grouping by them, a row per related row | `innerJoin(Pet.class).on(Owner.class)` or `leftJoin` |
| Related columns joined while keeping the rows without a match | `leftJoin(Pet.class).on(Owner.class, it -> it.where(Pet_.name, EQUALS, "Leo"))` |

**Read the request for the cue.** "with", "that have", "who own", "any of their", "search by ... or their ..." over a to-many relation asks for `semiJoin`. "without", "never", "no", "missing", "not yet" asks for `antiJoin`. A semi-join or anti-join returns each root row once, so it needs no `distinct`, and scrolling, paging and `getResultCount()` count root rows.

- `semiJoin` conditions go in `where`. Conditions joined by `.and()` hold for the same related row, and `search` matches each word on its own related row. A part naming only the query's own tables holds without a related row, so an `.or()` of `Owner_.lastName` and `Pet_.name` also finds a Davis without pets.
- `antiJoin` conditions go on the join, never in `where`: a `where` naming the anti-joined table is refused.
- A semi-joined or anti-joined table adds no columns, so ordering, grouping or selecting by it is refused. When the result, `orderBy` or `groupBy` needs its columns, the query wants a regular join instead.
- Both filter `delete()` and `update()` as they filter a select. `owners.delete().antiJoin(Pet.class).on(Owner.class)` removes the owners without pets. `owners.update().setNull(Owner_.telephone).semiJoin(Pet.class).on(Owner.class).where(Pet_.name, EQUALS, "Leo")` clears the telephone of the owners of a pet named Leo.
- `semiJoin` and `antiJoin` are complements: on the same table with the same condition on the join, the one keeps the rows the other drops. Without a condition, `semiJoin` keeps the rows with any related row and `antiJoin` the rows without one.
- Two semi-joins or anti-joins on one table are two subqueries, each with its own condition on the join: `antiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot")).semiJoin(Visit.class).on(Pet.class)` finds the pets with a visit but no rabies shot, and two `semiJoin`s with different conditions match a related row each. A `where` naming the table is then refused: each condition goes on its join.
- A semi-join or anti-join on the query's own table finds the rows referencing the same row as the query's row: `visits.select().semiJoin(Visit.class).on(Pet.class, it -> it.where(Visit_.description, EQUALS, "rabies shot")).where(Visit_.visitDate, GREATER_THAN_OR_EQUAL, date)` finds the visits since the date of the pets that had a rabies shot. The condition on the join names the other row, `where` the query's row. `on` names a table the query's table references; `on(Visit.class)` is refused.
- Keep `whereExists` for a subquery a foreign key does not express.

**Through two to-many relations, chain the semi-joins.** `on` names a semi-joined table, so `.semiJoin(Pet.class).on(Owner.class).semiJoin(Visit.class).on(Pet.class).where(Visit_.visitDate, GREATER_THAN_OR_EQUAL, date)` finds the owners with a pet that had a visit since the date, each once.
- The visit's `EXISTS` nests in the pet's, and conditions joined by `and` on `Pet` and `Visit` hold for the same pet.
- Through a link table, `on` names the table the link row points at: `.semiJoin(PetSitting.class).on(Owner.class).semiJoin(Visit.class).on(Pet.class)` relates the visit to the link's `pet_id` without reading the pet table.
- An `antiJoin` on a semi-joined table nests as `NOT EXISTS` (owners with a pet that never had a visit).
- A semi-join on an anti-joined table takes its condition on the join.

**Prefer a path over a join.** An explicit join is not how you filter on a related table. When the relationship is a foreign key, name the column through it and Storm joins the referenced table itself, for `Ref` foreign keys as much as for eager ones. A hand-written join names the same occurrence, so it gains nothing over the path alone:

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

Reach for an explicit join when a path cannot express the query:
- a relationship the entity model does not carry as a foreign key;
- the to-many side of one (a path navigates to-one only);
- a self-join that needs its own alias;
- a join whose entity you want the query widened to rather than filtered by.

**Filter by the to-many side with `semiJoin` or `antiJoin`, never with a join and `distinct`.** A join to the many side returns a row per related row, which takes `distinct` to undo, and a `DISTINCT` query can only order by the columns it selects.

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

**Recognize the pattern wherever the join hangs.** A select of the query's own table with `distinct()` and an inner join whose columns the result, the ordering and the grouping never use is this pattern, whatever table the join is on.
- `on(T.class)` names any table of the query, including one the root reaches through a path. `semiJoin(Related.class).on(T.class)` replaces `.distinct().innerJoin(Related.class).on(T.class)` one for one, without the `distinct`.
- A chain of inner joins through two to-many relations with `distinct`, such as owner to pet to visit, or a link table and the table hanging off the row it points at, is the same pattern. Each join becomes a semi-join on the table before it: `.semiJoin(Pet.class).on(Owner.class).semiJoin(Visit.class).on(Pet.class)`.
- A comment calling the `distinct` required because of the join marks the same pattern, not an exception.
- When a change touches such a query for another reason, replace it in the same change.

## Subqueries (EXISTS / NOT EXISTS)

**Use `whereExists(subquery)` / `whereNotExists(subquery)` for an EXISTS condition of its own.** Inside the where-lambda, `WhereBuilder.exists(subquery)` / `notExists(subquery)` combine it with other conditions. Build the subquery with `orm.selectFrom(...)`; it is automatically correlated with the outer query. For a condition on the related rows, `semiJoin` and `antiJoin` are the typed form (see **Filtering by Related Rows**).

```java
// WHERE EXISTS: filter entities that have related data
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

**Use the weakest form that compiles.** Two preferences govern every WHERE clause:

- **Typed overloads over the lambda.** `where(User_.email, LIKE, "%@example.com")` beats `where(it -> it.where(User_.email, LIKE, "%@example.com"))`. Before a join the chained `where(path, ...)` overloads take root-typed paths, and a nested path from the root (`User_.city.country.code`) is root-typed, so navigating through a foreign key never forces the lambda.
- **A join widens the query.** From the join onward the same overloads accept paths from any entity in the query, so joined-entity fields need no special form (see **Joined-Entity Predicates, Ordering, and Grouping**).

Reach for the `where(it -> ...)` lambda only for what the typed overloads cannot express: AND/OR grouping and EXISTS/NOT EXISTS.

```java
// ✅ Single condition: typed overload, no lambda
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

**Consecutive `where()` calls AND together**, each clause parenthesized. An AND of a root predicate and a joined-entity predicate is two typed calls, no lambda needed:

```java
users.select()
    .innerJoin(UserRole.class).on(User.class)     // the join widens the query
    .where(User_.email, LIKE, "%@example.com")            // root field
    .where(UserRole_.role, EQUALS, role)          // joined entity: same overload
    .getResultList();
```

## Joined-Entity Predicates, Ordering, and Grouping

**A join widens the query.** From the join onward, every clause accepts paths from any entity in the query: the joined table's fields go through the same `where`, `orderBy`, `groupBy` and `having` calls as the root's. Put joins before the clauses that reference them. A path on an entity that is not part of the query fails when the query is built, with an error naming the entity and the root.

```java
users.select()
    .innerJoin(UserRole.class).on(User.class)
    .where(UserRole_.role, EQUALS, role)   // joined entity: same overloads as root fields
    .orderBy(User_.name)                   // root path
    .orderBy(UserRole_.assignedAt)         // joined entity
    .getResultList();
```

Two operations are defined relative to the root and are affected by the widening:
- `fetch(...)` comes before any join.
- `getResultGroupedBy` needs the root back after a join: `narrow(User.class)` restores it, verified against the query's FROM table.

The counterpart `widen()` widens without a join: the way to reference a graph entity in short form on a query that joins nothing. `typedId(Integer.class)` types the primary key.

## Keyset Scrolling

Pick the read by what the caller needs to know:

| Read | Method | Result | Count query | Needs |
|---|---|---|---|---|
| Page | `page(pageable)` | `Page<R>` | yes | an ordering |
| Slice | `slice(pageable)` | `Slice<R>` | no | an ordering; a page without the count query, the only read for a query without a unique key |
| Scroll | `scroll(scrollable)` | `Window<R>` | no | a unique key; constant cost at any depth |
| Windows | `windows(size)` | `Stream<Window<R>>` | no | a primary key; one closed statement per window, so the loop may write |

**The request owns ORDER BY: never add `orderBy()` to a `scroll(Scrollable)` query, or Storm throws `PersistenceException`.** Keyset scrolling navigates by position instead of offset, which keeps it efficient on large tables. A `Scrollable<T>` states the ordering and the size. The position to continue from comes from `window.next()` / `window.previous()` or from a client's cursor string.

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

```java
var request = Scrollable.of(User_.id, size).sortBy(User_.email);      // ordering and size: code
var window = users.scroll(cursor != null ? request.from(cursor) : request);
String nextCursor = window.nextCursor();                              // null when nothing follows
```

**Tokens for every result type:** the sort and key values are read from the row, so `selectRef().scroll(...)`, `users.scrollRef(...)`, projections read as another type and custom select types all navigate. A compound (inline record) key is read from the mapped record and needs the entity type as the result.

**Iterate windows:** `for (User user : window)` works: `Window` is a `Slice` and iterates over its content. `window.size()`, `window.isEmpty()` and `window.stream()` exist too.

**Slices:** `select().slice(Pageable.ofSize(20).sortBy(User_.email))` is `page` without the count query: `hasNext()` from one extra row, `hasPrevious()` from the page number, the next slice from `pageable.next()`. `slice(0, 20)` uses the query's own ordering. `Slice` is the interface `Page` and `Window` implement.

## Bulk DELETE/UPDATE

**`delete()` is a builder method that returns `QueryBuilder`.** Call `.executeUpdate()` to execute:

```java
// DELETE with WHERE (safe): builder returns QueryBuilder, terminal executes
orm.entity(User.class).delete().where(User_.postalCode, IS_NULL).executeUpdate();

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User.class).delete().unsafe().executeUpdate();

// Convenience method: removeAll() executes immediately (calls unsafe() internally)
users.removeAll();
```

**`update()` writes a few columns of rows the code did not read.** `set` names a column through the metamodel, the conditions select the rows, and `.executeUpdate()` runs one statement without reading the rows. Use it rather than a second, partial entity for the table.

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
- **WHERE is required** (or a `semiJoin`/`antiJoin`), as for `delete()`. `unsafe()` confirms an update of every row.
- **`set` refuses** the primary key, `@Persist(updatable = false)` fields, the `@Version` field, a path into a related table (`Owner_.address.city.name`), and a field set twice. An inline record field writes each of its columns.
- **The `update()` and `delete()` builders refuse a Joined Table entity** (sealed type or subtype): its rows span two tables. Use `remove`/`removeById` and `update(entity)`.
- **`@Version` is incremented** by the statement (a timestamp version is set to the current time), so an entity read before it fails its optimistic lock check.
- **No entity callbacks fire**: `beforeUpdate`/`afterUpdate` need an entity and the statement carries none. `set` an audit column in the builder.
- **The type's entities are evicted from the transaction's entity cache** once it runs. An entity read before it still holds its old values, so re-read the entities it touched before writing them back. `update(entity)` of a stale copy writes its full row, putting the old values back over the columns the statement set; a versioned entity fails its optimistic lock check instead.
- `distinct()`, `limit()`, `offset()`, locking, result reads and use as a subquery are refused.

## Streams and the Connection

**A `getResultStream()` is consume-only while it has unread rows.** It is one open statement, and while rows remain unread its connection is consume-only, on every database. Inside a transaction every statement shares the transaction's connection, so these all throw `PersistenceException` from the loop:

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

**Never feed a `getResultStream()` of the current transaction into `insert`, `update`, `upsert`, `remove`, `removeByRef`, `insertAndFetch` or `countById`.** Feed them an in-memory stream, or iterate in windows.

The last line above is the trap that passes small tests. A batch that executes after the stream has been read to its end is allowed, and a batch that executes while rows remain is refused, so the outcome depends on batch size versus row count.

**Use `windows(size)` for a loop that needs the database.** It reads keyset windows over the primary key. Each window is fetched by one statement that has closed before the window is handed over, there is nothing to close, and one batched write per window costs one statement rather than one per row:

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

**`windows(size).flatMap(Slice::stream)` reads the windows as one `Stream<R>`** for a loop that handles one row at a time. Every row comes from a window whose statement has closed, so the connection is free at each row and memory stays bounded by the window size. It carries no database resource, so it needs no closing. Where the loop writes, the per-window form batches better.

Rules for `windows`:
- The key is the primary key (or the `Scrollable`'s key), which must be a non-null single column.
- No `orderBy()` on the query.
- The result type must be the entity: `selectRef()` and custom select types are refused.
- Each window is its own statement and sees the committed state at that moment.

What stays fine with `getResultStream()`:
- consuming it (`forEach`, `toList()`, `count()`, `map`, `filter`);
- stopping early (`findFirst()`, `limit(n)`, then closing it);
- once it has been read to its end, any statement.

A `Ref` the loop needs is loaded by naming it in the fetch plan (`select().fetch(...)`) instead of calling `fetch()` per row. Outside a transaction a stream holds a pooled connection of its own until it is closed.

## Result Retrieval

QueryBuilder terminals:
- `.getResultList()` → `List<R>`
- `.getSingleResult()` → `R` (throws `NoResultException` if empty, `NonUniqueResultException` if multiple)
- `.getOptionalResult()` → `Optional<R>`
- `.getResultCount()` → `long`
- `.getResultStream()` → `Stream<R>` (lazy, **must** close with try-with-resources)
- `.page(pageNumber, pageSize)` → `Page<R>` (offset-based pagination)
- `.scroll(scrollable)` → `Window<R>` (keyset scrolling, see **Keyset Scrolling**). Use `next()` / `previous()` for programmatic navigation, or `nextCursor()` / `previousCursor()` for REST APIs.
- `.executeUpdate()` → `int` (for DELETE/UPDATE)

Critical rules:
- **QueryBuilder is IMMUTABLE.** Every method returns a new instance. Always use the return value.
- **Streaming:** ALWAYS close a `getResultStream()` with try-with-resources to avoid connection leaks. There are no `selectBy` methods that return Stream directly: use `select()` and then `.getResultStream()`.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key.
- **Typed ID from `Ref`:** Use `Ref.entityId(ref)` to extract a type-safe ID. For projections, use `Ref.projectionId(ref)`. Avoid `ref.id()`: it returns `Object` and requires an unsafe cast.

## Verification

**After writing queries, write a test using `@StormTest` and `SqlCapture`** to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the query produces the result the user intended: correct tables joined, correct columns filtered, correct ordering, correct number of statements. This is Storm's verify-then-trust pattern.

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
                .where(User_.email, LIKE, "%@example.com")
                .orderBy(User_.name)
                .getResultList());
        // Verify intent: single query, only example.com users in the given city, ordered by name.
        assertEquals(1, capture.count(Operation.SELECT));
        assertFalse(users.isEmpty());
        assertTrue(users.stream().allMatch(u -> u.city().equals(city) && u.email().endsWith("@example.com")));
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
