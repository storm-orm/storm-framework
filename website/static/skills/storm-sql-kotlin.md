Help the user write Storm SQL Templates using Kotlin.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask what query they need and why QueryBuilder does not suffice.

When to use SQL Templates: complex joins, subqueries, CTEs, window functions, DB-specific syntax, UNION/INTERSECT.
When to use QueryBuilder (/storm-query-kotlin): simple CRUD, filtering, ordering, pagination.

Requires the Storm compiler plugin. Kotlin uses \`\${}\` interpolation inside a lambda:

\`\`\`kotlin
val users = orm.query { """
    SELECT \${User::class}
    FROM \${User::class}
    WHERE \${User_.email} = \$email
      AND \${User_.city.country.code} = \$countryCode
""" }.resultList<User>()
\`\`\`

Template elements:
- \`\${User::class}\` in SELECT: full column list with aliases
- \`\${User::class}\` in FROM: table + auto-JOINs for all @FK fields
- \`\${User_.email}\`: column reference with correct alias
- \`\$email\`: parameterized bind variable (SQL injection safe)
- \`\${from(User::class, autoJoin = false)}\`: FROM without auto-joins
- \`\${table(User::class)}\`: table name only (for subqueries)
- \`\${select(User::class, SelectMode.PK)}\`: only PK columns
- \`\${column(User_.email)}\`: explicit column with alias
- \`\${unsafe("raw sql")}\`: raw SQL (use with caution)

The Data interface marks types for SQL generation without CRUD:
\`\`\`kotlin
data class CityCount(val city: City, val count: Long) : Data
\`\`\`

All interpolated values become bind parameters. SQL injection safe by design.

Critical rules:
- **`t()` for parameter binding** (requires the Storm compiler plugin): Inside `.having {}` and other lambda expressions, use `t()` to ensure proper parameter binding and SQL injection protection:
  ```kotlin
  // ✅ Correct - t() ensures proper parameter binding
  .having { "COUNT(DISTINCT ${t(column)}) = ${t(numTypes)}" }

  // ❌ Wrong - raw interpolation bypasses parameter binding
  .having { "COUNT(DISTINCT $column) = $numTypes" }
  ```
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. However, joined (non-root) entities can only navigate one level deep. If you need deeper navigation from a joined entity, explicitly join the intermediate entity:
  ```kotlin
  // ❌ Wrong - two levels from joined entity
  orm.selectFrom(DemographicSet::class, ...)
      .innerJoin(DemographicDemographicSet::class).on(DemographicSet::class)
      .having { "${t(DemographicDemographicSet_.demographic.demographicType)}" }

  // ✅ Correct - explicitly join intermediate, then one level
  orm.selectFrom(DemographicSet::class, ...)
      .innerJoin(DemographicDemographicSet::class).on(DemographicSet::class)
      .innerJoin(Demographic::class).on(DemographicDemographicSet::class)
      .having { "${t(Demographic_.demographicType)}" }
  ```

Advanced patterns:
- Subqueries: use \`table()\` for inner FROM, \`column()\` for inner columns
- Correlated subqueries: use \`column(field, INNER)\` / \`column(field, OUTER)\` for alias scoping
- CTEs: write standard WITH clause, reference types normally
- UNION: combine multiple query blocks

After writing SQL templates, offer to write a test using `SqlCapture` to verify the generated SQL matches the user's intent:
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
        // Verify the SQL structure matches the intent.
        val sql = capture.statements().first().statement()
        assertContains(sql, "LEFT JOIN")
        assertContains(sql, "GROUP BY")
        assertFalse(results.isEmpty())
    }
}
```
