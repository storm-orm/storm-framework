---
name: storm-sql-kotlin
description: Write Storm SQL templates in Kotlin as template lambdas, for queries the QueryBuilder cannot express. Use only when no QueryBuilder equivalent exists.
---

Help the user write Storm SQL Templates using Kotlin.
Ask what query they need and why QueryBuilder does not suffice.

**SQL Templates are an escape hatch: use them only when there is no code-based alternative.** Regular joins, filtering, ordering, and pagination are all expressible through the QueryBuilder API (/storm-query-kotlin), and using templates for them defeats the purpose of the ORM. So are matching text a user typed and comparing text whatever its case: use `contains`, `startsWith`, `endsWith` and their `IgnoreCase` forms, never a hand-built `LIKE` pattern or a `LOWER(...)` comparison in a template.

## When to use SQL Templates

SQL Templates exist for two scenarios:

**1. Template fragments**: a single clause (SELECT, HAVING) needs SQL that QueryBuilder cannot express, but the rest of the query is code-based. This is the most common case:
```kotlin
// Prefer code over templates: use templates only for expressions QueryBuilder can't produce
orm.entity(City::class)
    .select(CityUserCount::class) { "${City::class}, COUNT(*)" }
    .leftJoin<User>().on<City>()
    .groupBy(City_.id)
    .resultList
```

**2. Full SQL templates**: the entire query is custom SQL. This is a last resort for queries the QueryBuilder cannot compose at all:
- CTEs (`WITH` clauses)
- `UNION` / `INTERSECT` / `EXCEPT`
- Window functions (`ROW_NUMBER`, `RANK`, `LAG`, `LEAD`)
- Database-specific syntax

Full SQL templates still get bind variables (`$value`) and metamodel references (`${Entity_.field}`).

**Do NOT use SQL Templates for:**
- Regular joins: use `innerJoin()`, `leftJoin()`, etc. on QueryBuilder
- Filtering: use `where()` with metamodel predicates or convenience methods (`findAll`, `find`)
- Ordering: use `orderBy()`, `orderByDescending()`
- Pagination, scrolling, window iteration: use `page()`, `scroll()`, `windows()`
- Simple CRUD: use `find`, `findAll`, `remove`, `removeAll`, `insert`, `update`
- Set-based UPDATE and DELETE: use the `update()` and `delete()` builders (`set`, `where`, `semiJoin`, `antiJoin`), see /storm-query-kotlin

**Mutations bypass dirty checking.** Prefer entity updates for row-level changes. Set-based mutations take the `update()` and `delete()` builders, which name their type; keep templates for what the builders cannot express.
- Entity updates through repositories compare against the transaction's observed state and can skip or narrow the UPDATE. Template and raw SQL mutations write unconditionally.
- To keep later comparisons truthful, a template mutation whose entity type is known invalidates the observed state of that type. A raw SQL string without type information clears all observed state in the transaction.
- Repository updates after such a mutation fall back to full-row writes.

**Inside SQL templates, always use metamodel references** (`${User_.email}`, `${City_.id}`) instead of hardcoding column names. This keeps queries type-safe and refactor-proof. Use `${unsafe("raw sql")}` only when there is truly no metamodel equivalent.

**FK path references: prefer `${User_.city.country}`.** It resolves to the FK column (e.g., `country_id`) and needs no join. `${User_.city.country.id}` resolves to the PK column on the joined table.

**Write templates as lambdas with standard `${}` interpolation.** This requires the Storm compiler plugin, which wraps all interpolations for safe parameter binding:

```kotlin
val users = orm.query { """
    SELECT ${User::class}
    FROM ${User::class}
    WHERE ${User_.email} = $email
      AND ${User_.city.country.code} = $countryCode
""" }.resultList<User>()
```

Template elements:
- `${User::class}` in SELECT: full column list with aliases
- `${User::class}` in FROM: table + auto-JOINs for all @FK fields
- `${User_.email}`: column reference with correct alias
- `$email`: parameterized bind variable (SQL injection safe)
- `${from(User::class, autoJoin = false)}`: FROM without auto-joins
- `${table(User::class)}`: table name only (for subqueries)
- `${select(User::class, SelectMode.PK)}`: only PK columns
- `${column(User_.email)}`: explicit column with alias
- `${unsafe("raw sql")}`: raw SQL (use with caution)

## Aggregate example: the primary use case

Define a plain data class for the result shape, then use a SQL Template for the aggregate:

```kotlin
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain data class, deliberately not a Data type.
 */
data class CityUserCount(val city: City, val userCount: Long)

// Use select() with custom return type + minimal SQL template for the aggregate only.
val cityCounts = orm.entity(City::class)
    .select(CityUserCount::class) { "${City::class}, COUNT(*)" }
    .leftJoin<User>().on<City>()
    .groupBy(City_.id)
    .resultList
```

**Keep the template to the minimum.** Here the join, grouping, and result retrieval are code-based; only the `COUNT(*)` aggregate, which QueryBuilder cannot express, is a template fragment.

**Do not implement `Data` on result types.** Storm maps the result columns positionally onto the result type's components; entity components, `Ref<T>` components, and scalars all work without a marker interface. `Data` is reserved for table-backed types, and a `Data` result type would be picked up by schema validation (requiring `@DbIgnore` to suppress).

**`Ref<T>` in result types:** when a SELECT clause references a FK field (`${User_.city}`) rather than a full entity (`${City::class}`), use `Ref<T>` in the result type, not the raw ID type and not the full entity. `Ref<City>` maps correctly to the FK column value. Use the full entity type only when selecting all its columns via `${City::class}`.

All interpolated values become bind parameters, so templates are SQL injection safe by design.

**Note:** `Query.resultList` (Kotlin property, no type parameter) returns `List<Array<Any>>`, raw rows. For typed results, use the reified extension `query.resultList<T>()` (`import st.orm.template.resultList`) or `query.getResultList(T::class)`. Reified counterparts also exist for `singleResult<T>()`, `optionalResult<T>()`, `resultStream<T>()`, and `resultFlow<T>()`. This differs from QueryBuilder's `.resultList`, which returns `List<R>` already typed to the query's result type.

Critical rules:
- **Always use lambdas, never `TemplateString.raw()`**: write template expressions as lambdas (`{ "..." }`) so the compiler plugin can process them. Never construct `TemplateString.raw("...")` manually.
- **`${}` interpolation with the compiler plugin**: the plugin wraps all `${}` interpolations inside template lambdas, so do NOT call `t()` manually; use standard Kotlin string interpolation. `t()` exists only as a fallback for projects without the compiler plugin.
- **Metamodel navigation depth**: the root entity allows multiple levels of navigation, but joined (non-root) entities navigate only one level deep. For deeper navigation from a joined entity, explicitly join the intermediate entity:
  ```kotlin
  // ❌ Wrong - two levels from joined entity
  orm.entity(Role::class)
      .select(RoleSummary::class) { "..." }
      .innerJoin<UserRole>().on<Role>()
      .having { "COUNT(DISTINCT ${UserRole_.user.city}) > 1" }

  // ✅ Correct - explicitly join intermediate, then one level
  orm.entity(Role::class)
      .select(RoleSummary::class) { "..." }
      .innerJoin<UserRole>().on<Role>()
      .innerJoin<User>().on<UserRole>()
      .having { "COUNT(DISTINCT ${User_.city}) > 1" }
  ```

**Close any `resultStream` from a custom query,** with `use { }`. While rows remain unread, a `resultStream` or `resultFlow` holds its connection consume-only: inside a transaction, a query or write from the loop throws, on every database. A template query cannot be windowed (no key): consume it, or express the query with the builder and use `windows(size)`.

Advanced patterns:
- Subqueries: use `table()` for inner FROM, `column()` for inner columns
- Correlated subqueries: use `column(field, INNER)` / `column(field, OUTER)` for alias scoping
- CTEs: write a standard WITH clause, reference types normally
- UNION: combine multiple query blocks

## Verification

**After writing SQL templates, write a test with `@StormTest` and `SqlCapture`** that verifies schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why. `SqlCapture` records every SQL statement Storm executes, with its bound values, duration, and origin. The goal is not to test Storm itself but to verify that the template produces the intended result: correct tables joined, correct grouping, correct aggregation. This is Storm's verify-then-trust pattern.

```kotlin
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class CityCountQueryTest {
    @Test
    fun citiesWithUserCounts(orm: ORMTemplate, capture: SqlCapture) {
        val results = capture.execute {
            orm.query { """
                SELECT ${City::class}, COUNT(*)
                FROM ${City::class}
                LEFT JOIN ${User::class} ON ${User_.city} = ${City_.id}
                GROUP BY ${City_.id}
            """ }.resultList<CityCount>()
        }
        // Verify intent: one row per city, each with a user count.
        assertEquals(1, capture.count(Operation.SELECT))
        assertFalse(results.isEmpty())
        assertTrue(results.all { it.count >= 0 })
    }
}
```

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.

The test can be temporary: verify and remove, or keep as a regression test. Ask the user which they prefer.
