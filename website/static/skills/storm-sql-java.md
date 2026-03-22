Help the user write Storm SQL Templates using Java.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask what query they need and why QueryBuilder does not suffice.

When to use SQL Templates: complex joins, subqueries, CTEs, window functions, DB-specific syntax, UNION/INTERSECT.
When to use QueryBuilder (/storm-query-java): simple CRUD, filtering, ordering, pagination.

Requires --enable-preview. Java uses RAW string templates with \\{} syntax:

\`\`\`java
List<User> users = orm.query(RAW."""
        SELECT \\{User.class}
        FROM \\{User.class}
        WHERE \\{User_.email} = \\{email}
          AND \\{User_.city.country.code} = \\{countryCode}""")
    .getResultList(User.class);
\`\`\`

Template elements:
- \\{User.class} in SELECT: full column list with aliases
- \\{User.class} in FROM: table + auto-JOINs for all @FK fields
- \\{User_.email}: column reference with correct alias
- \\{email}: parameterized bind variable (SQL injection safe)
- \\{from(User.class, false)}: FROM without auto-joins
- \\{table(User.class)}: table name only (for subqueries)
- \\{select(User.class, SelectMode.PK)}: only PK columns
- \\{column(User_.email)}: explicit column with alias
- \\{unsafe("raw sql")}: raw SQL (use with caution)

The Data interface marks types for SQL generation without CRUD:
\`\`\`java
record CityCount(@FK City city, long count) implements Data {}
\`\`\`

All interpolated values become bind parameters. SQL injection safe by design.

Close any ResultStream from custom queries. Use try-with-resources for getResultStream().
