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

Advanced patterns:
- Subqueries: use \`table()\` for inner FROM, \`column()\` for inner columns
- Correlated subqueries: use \`column(field, INNER)\` / \`column(field, OUTER)\` for alias scoping
- CTEs: write standard WITH clause, reference types normally
- UNION: combine multiple query blocks
