Help the user write Storm SQL Templates using Java.
Ask what query they need and why QueryBuilder does not suffice.

**SQL Templates should only be used when there is no code-based alternative.** Regular joins, filtering, ordering, and pagination are all expressible through the QueryBuilder API (/storm-query-java). The most common use case for SQL Templates is when a **custom return type** is needed — typically for aggregates where the result shape differs from any entity or projection.

## When to use SQL Templates

- Aggregate queries with custom result types (e.g., `GROUP BY` with `COUNT`, `SUM`, `AVG`)
- Window functions (`ROW_NUMBER`, `RANK`, `LAG`, `LEAD`)
- CTEs (`WITH` clauses)
- `UNION` / `INTERSECT` / `EXCEPT`
- Database-specific syntax not covered by QueryBuilder

**Do NOT use SQL Templates for:**
- Regular joins — use `innerJoin()`, `leftJoin()`, etc. on QueryBuilder
- Filtering — use `where()` with metamodel predicates
- Ordering, pagination, scrolling — use `orderBy()`, `page()`, `scroll()`

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

## Aggregate example — the primary use case

Define a custom `Data` type for the result shape, then use a SQL Template for the aggregate:

\`\`\`java
// Custom result type — not an entity, just a data carrier
record CityUserCount(@FK City city, long userCount) implements Data {}

// Use select() with custom return type + minimal SQL template for the aggregate only
List<CityUserCount> cityCounts = orm.entity(City.class)
        .select(CityUserCount.class, RAW."\{City.class}, COUNT(*)")
        .leftJoin(User.class).on(City.class)
        .groupBy(City_.id)
        .getResultList();
\`\`\`

The join, grouping, and result retrieval are all code-based. Only the `COUNT(*)` aggregate — which QueryBuilder cannot express — uses a SQL template fragment. This keeps the template to the absolute minimum.

The `Data` interface marks types for SQL generation without CRUD. It tells Storm how to map the result columns to the record fields.

All interpolated values become bind parameters. SQL injection safe by design.

Critical rules:
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. However, joined (non-root) entities can only navigate one level deep. If you need deeper navigation from a joined entity, explicitly join the intermediate entity.

Close any ResultStream from custom queries. Use try-with-resources for getResultStream().

## Verification

After writing SQL templates, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm generates. The goal is not to test Storm itself, but to verify that the SQL template produces the result the user intended — correct tables joined, correct grouping, correct aggregation. This is Storm's verify-then-trust pattern.

```java
@StormTest(scripts = {"schema.sql", "data.sql"})
class CityCountQueryTest {
    @Test
    void citiesWithUserCounts(ORMTemplate orm, SqlCapture capture) {
        List<CityCount> results = capture.execute(() ->
            orm.query(RAW."""
                SELECT \{City.class}, COUNT(*)
                FROM \{City.class}
                LEFT JOIN \{User.class} ON \{User_.city} = \{City_.id}
                GROUP BY \{City_.id}""")
            .getResultList(CityCount.class));
        // Verify intent: one row per city, each with a user count.
        assertFalse(results.isEmpty());
        assertTrue(results.stream().allMatch(r -> r.count() >= 0));
    }
}
```

Run the test. Show the user the captured SQL and explain how it aligns with the intended behavior. If a query produces unexpected SQL or the right approach is unclear, ask the user for feedback before changing the query.


The test can be temporary — verify and remove, or keep as a regression test. Ask the user which they prefer.
