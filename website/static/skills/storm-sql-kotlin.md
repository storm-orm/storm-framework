Help the user write Storm SQL Templates using Kotlin.
Ask what query they need and why QueryBuilder does not suffice.

**SQL Templates are an escape hatch — use them only when there is no code-based alternative.** Regular joins, filtering, ordering, and pagination are all expressible through the QueryBuilder API (/storm-query-kotlin). Using SQL templates for things the QueryBuilder can express defeats the purpose of the ORM.

## When to use SQL Templates

SQL Templates exist for two scenarios:

**1. Template fragments** — a single clause (SELECT, HAVING) needs SQL that QueryBuilder cannot express, but the rest of the query is code-based. This is the most common case:
```kotlin
// Prefer code over templates — use templates only for expressions QueryBuilder can't produce
orm.entity(City::class)
    .select(CityUserCount::class) { "${City::class}, COUNT(*)" }
    .leftJoin(User::class).on(City::class)
    .groupBy(City_.id)
    .resultList
```

**2. Full SQL templates** — the entire query is custom SQL. This is truly a last resort for queries that cannot be composed with the QueryBuilder at all:
- CTEs (`WITH` clauses)
- `UNION` / `INTERSECT` / `EXCEPT`
- Window functions (`ROW_NUMBER`, `RANK`, `LAG`, `LEAD`)
- Database-specific syntax

Even in full SQL templates, users still benefit from bind variables (`$value`) and metamodel references (`${Entity_.field}`).

**Do NOT use SQL Templates for:**
- Regular joins — use `innerJoin()`, `leftJoin()`, etc. on QueryBuilder
- Filtering — use `where()` with metamodel predicates or convenience methods (`findAll`, `find`)
- Ordering — use `orderBy()`, `orderByDescending()`
- Pagination, scrolling — use `page()`, `scroll()`
- Simple CRUD — use `find`, `findAll`, `remove`, `removeAll`, `insert`, `update`

**Inside SQL templates, always use metamodel references** (`${User_.email}`, `${City_.id}`) instead of hardcoding column names. This keeps queries type-safe and refactor-proof. Only use `${unsafe("raw sql")}` when there is truly no metamodel equivalent.

**FK path references:** Use `${User_.city.country}` (resolves to the FK column, e.g., `country_id`) rather than `${User_.city.country.id}` (resolves to the PK column on the joined table). The shorter form is preferred — it references the FK directly without requiring a join.

Requires the Storm compiler plugin. With the compiler plugin, use standard Kotlin `${}` interpolation inside a lambda — the plugin automatically wraps all interpolations for safe parameter binding:

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

## Aggregate example — the primary use case

Define a custom `Data` type for the result shape, then use a SQL Template for the aggregate:

```kotlin
// Custom result type — not an entity, just a data carrier
data class CityUserCount(val city: City, val userCount: Long) : Data

// Use select() with custom return type + minimal SQL template for the aggregate only
val cityCounts = orm.entity(City::class)
    .select(CityUserCount::class) { "${City::class}, COUNT(*)" }
    .leftJoin(User::class).on(City::class)
    .groupBy(City_.id)
    .resultList
```

The join, grouping, and result retrieval are all code-based. Only the `COUNT(*)` aggregate — which QueryBuilder cannot express — uses a SQL template fragment. This keeps the template to the absolute minimum.

The `Data` interface marks types for SQL generation without CRUD. It tells Storm how to map the result columns to the record fields.

All interpolated values become bind parameters. SQL injection safe by design.

**Note:** `Query.resultList` (Kotlin property, no type parameter) returns `List<Array<Any>>`. For typed results, use `query.resultList<T>()` or `query.getResultList(T::class)`. This is different from QueryBuilder's `.resultList` which returns `List<R>` already typed to the query's result type.

Critical rules:
- **Always use lambdas, never `TemplateString.raw()`**: Template expressions should always be written as lambdas (`{ "..." }`) so the compiler plugin can process them. Never construct `TemplateString.raw("...")` manually.
- **`${}` interpolation with the compiler plugin**: The Storm compiler plugin automatically wraps all `${}` interpolations inside template lambdas. You do NOT need to call `t()` manually — just use standard Kotlin string interpolation. The `t()` function only exists as a fallback for projects without the compiler plugin.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. However, joined (non-root) entities can only navigate one level deep. If you need deeper navigation from a joined entity, explicitly join the intermediate entity:
  ```kotlin
  // ❌ Wrong - two levels from joined entity
  orm.selectFrom(DemographicSet::class, ...)
      .innerJoin(DemographicDemographicSet::class).on(DemographicSet::class)
      .having { "${DemographicDemographicSet_.demographic.demographicType}" }

  // ✅ Correct - explicitly join intermediate, then one level
  orm.selectFrom(DemographicSet::class, ...)
      .innerJoin(DemographicDemographicSet::class).on(DemographicSet::class)
      .innerJoin(Demographic::class).on(DemographicDemographicSet::class)
      .having { "${Demographic_.demographicType}" }
  ```

Advanced patterns:
- Subqueries: use `table()` for inner FROM, `column()` for inner columns
- Correlated subqueries: use `column(field, INNER)` / `column(field, OUTER)` for alias scoping
- CTEs: write standard WITH clause, reference types normally
- UNION: combine multiple query blocks

## Verification

After writing SQL templates, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the SQL template produces the result the user intended — correct tables joined, correct grouping, correct aggregation. This is Storm's verify-then-trust pattern.

```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
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

The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
