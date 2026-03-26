Help the user write Storm SQL Templates using Kotlin.
Ask what query they need and why QueryBuilder does not suffice.

**Always prefer the QueryBuilder and metamodel-based API first** (/storm-query-kotlin). SQL Templates are a fallback for queries that QueryBuilder cannot express: complex joins, subqueries, CTEs, window functions, DB-specific syntax, UNION/INTERSECT.

Even inside SQL Templates, **use metamodel references wherever possible** (e.g., `${User_.email}` instead of hardcoding column names). This keeps queries type-safe and refactor-proof.

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

The Data interface marks types for SQL generation without CRUD:
```kotlin
data class CityCount(val city: City, val count: Long) : Data
```

All interpolated values become bind parameters. SQL injection safe by design.

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
                SELECT ${CityCount::class}
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
