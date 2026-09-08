---
name: storm-sql-java
description: Write Storm SQL templates in Java with RAW string templates, for queries the QueryBuilder cannot express. Use only when no QueryBuilder equivalent exists.
---

Help the user write Storm SQL Templates using Java.
Ask what query they need and why QueryBuilder does not suffice.

**SQL Templates are an escape hatch — use them only when there is no code-based alternative.** Regular joins, filtering, ordering, and pagination are all expressible through the QueryBuilder API (/storm-query-java). Using SQL templates for things the QueryBuilder can express defeats the purpose of the ORM.

## When to use SQL Templates

SQL Templates exist for two scenarios:

**1. Template fragments** — a single clause (SELECT, HAVING) needs SQL that QueryBuilder cannot express, but the rest of the query is code-based. This is the most common case:
```java
// Prefer code over templates — use templates only for expressions QueryBuilder can't produce
List<CityUserCount> cityCounts = orm.entity(City.class)
        .select(CityUserCount.class, RAW."\{City.class}, COUNT(*)")
        .leftJoin(User.class).on(City.class)
        .groupBy(City_.id)
        .getResultList();
```

**2. Full SQL templates** — the entire query is custom SQL. This is truly a last resort for queries that cannot be composed with the QueryBuilder at all:
- CTEs (`WITH` clauses)
- `UNION` / `INTERSECT` / `EXCEPT`
- Window functions (`ROW_NUMBER`, `RANK`, `LAG`, `LEAD`)
- Database-specific syntax

Even in full SQL templates, users still benefit from bind variables (`\{value}`) and metamodel references (`\{Entity_.field}`).

**Do NOT use SQL Templates for:**
- Regular joins — use `innerJoin()`, `leftJoin()`, etc. on QueryBuilder
- Filtering — use `where()` with metamodel predicates or convenience methods (`findBy`, `findAllBy`)
- Ordering — use `orderBy()`, `orderByDescending()`
- Pagination, scrolling, window iteration — use `page()`, `scroll()`, `windows()`
- Simple CRUD — use `findBy`, `findAll`, `remove`, `removeAll`, `insert`, `update`

**Mutations bypass dirty checking.** Entity updates through repositories compare against the
transaction's observed state and can skip or narrow the UPDATE; template and raw SQL mutations
write unconditionally. To keep later comparisons truthful, a template mutation whose entity type is
known invalidates the observed state of that type, and a raw SQL string without type information
clears all observed state in the transaction — repository updates after it fall back to full-row
writes. Prefer entity updates for row-level changes; keep templates for set-based mutations.

**Inside SQL templates, always use metamodel references** (`\{User_.email}`, `\{City_.id}`) instead of hardcoding column names. This keeps queries type-safe and refactor-proof. Only use `\{unsafe("raw sql")}` when there is truly no metamodel equivalent.

**FK path references:** Use `\{User_.city.country}` (resolves to the FK column, e.g., `country_id`) rather than `\{User_.city.country.id}` (resolves to the PK column on the joined table). The shorter form is preferred — it references the FK directly without requiring a join.

Requires --enable-preview. Java uses RAW string templates with \{} syntax:

```java
List<User> users = orm.query(RAW."""
        SELECT \{User.class}
        FROM \{User.class}
        WHERE \{User_.email} = \{email}
          AND \{User_.city.country.code} = \{countryCode}""")
    .getResultList(User.class);
```

Template elements:
- \{User.class} in SELECT: full column list with aliases
- \{User.class} in FROM: table + auto-JOINs for all @FK fields
- \{User_.email}: column reference with correct alias
- \{email}: parameterized bind variable (SQL injection safe)
- \{from(User.class, false)}: FROM without auto-joins
- \{table(User.class)}: table name only (for subqueries)
- \{select(User.class, SelectMode.PK)}: only PK columns
- \{column(User_.email)}: explicit column with alias
- \{unsafe("raw sql")}: raw SQL (use with caution)

## Aggregate example — the primary use case

Define a plain record for the result shape, then use a SQL Template for the aggregate:

```java
/**
 * Query result shape: user count per city. Not backed by a database table
 * or view, so it is a plain record — deliberately not a Data type.
 */
record CityUserCount(City city, long userCount) {}

// Use select() with custom return type + minimal SQL template for the aggregate only
List<CityUserCount> cityCounts = orm.entity(City.class)
        .select(CityUserCount.class, RAW."\{City.class}, COUNT(*)")
        .leftJoin(User.class).on(City.class)
        .groupBy(City_.id)
        .getResultList();
```

The join, grouping, and result retrieval are all code-based. Only the `COUNT(*)` aggregate — which QueryBuilder cannot express — uses a SQL template fragment. This keeps the template to the absolute minimum.

Storm maps the result columns positionally onto the result type's components — entity components, `Ref<T>` components, and scalars all work without any marker interface. Do not implement `Data` on result types — it is reserved for table-backed types, and a `Data` result type would be picked up by schema validation (requiring `@DbIgnore` to suppress).

**`Ref<T>` in result types:** When a SELECT clause references a FK field (`\{User_.city}`) rather than a full entity (`\{City.class}`), use `Ref<T>` in the result type — not the raw ID type and not the full entity. `Ref<City>` maps correctly to the FK column value. Use the full entity type only when selecting all its columns via `\{City.class}`.

All interpolated values become bind parameters. SQL injection safe by design.

**Note:** `Query.getResultList()` (no type parameter) returns `List<Object[]>`. For typed results, use `query.getResultList(T.class)`. This is different from QueryBuilder's `.getResultList()` which returns `List<R>` already typed to the query's result type.

Critical rules:
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. However, joined (non-root) entities can only navigate one level deep. If you need deeper navigation from a joined entity, explicitly join the intermediate entity.

Close any ResultStream from custom queries. Use try-with-resources for getResultStream(). While rows remain unread its connection is consume-only: inside a transaction a query or write from the loop throws, on every database. A template query cannot be windowed (no key); consume it, or express the query with the builder and use `windows(size)`.

## Verification

After writing SQL templates, write a test using `@StormTest` and `SqlCapture` to verify that schema, generated SQL, and intent are aligned.

Tell the user what you are doing and why: explain that `SqlCapture` records every SQL statement Storm executes, with its bound values, duration, and origin. The goal is not to test Storm itself, but to verify that the SQL template produces the result the user intended — correct tables joined, correct grouping, correct aggregation. This is Storm's verify-then-trust pattern.

```java
// Leading "/" resolves scripts from the classpath root (src/test/resources/).
@StormTest(scripts = {"/schema.sql", "/data.sql"})
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
