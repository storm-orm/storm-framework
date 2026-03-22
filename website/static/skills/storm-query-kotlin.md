Help the user write Storm queries using Kotlin.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask what data they need, filters, ordering, or pagination.

Three query levels (suggest the simplest that works):
| Approach | Best for |
|----------|----------|
| find/findAll | Simple lookups |
| QueryBuilder (.select().where()) | Most application queries |
| SQL Templates (/storm-sql-kotlin) | CTEs, window functions, subqueries |

Quick queries:
\`\`\`kotlin
val user = orm.find { User_.email eq email }
val users = orm.findAll { User_.city eq city }
val exists = orm.existsBy(User_.email, email)
\`\`\`

QueryBuilder:
\`\`\`kotlin
val users = orm.entity(User::class)
    .select()
    .where((User_.city eq city) and (User_.birthDate less LocalDate.of(2000, 1, 1)))
    .orderBy(User_.name)
    .resultList
\`\`\`

Compound filters: \`(A eq x) and (B eq y)\`, \`(A eq x) or (B eq y)\`
Nested paths: \`User_.city.country.code eq "US"\`
Ordering: \`.orderBy(User_.name)\`, \`.orderByDescending(User_.createdAt)\`
Pagination: \`.page(0, 20)\` or \`.page(Pageable.ofSize(20).sortBy(User_.name))\`
Scrolling (keyset, better for large tables): \`.scroll(User_.id, 20)\`
Explicit joins: \`.innerJoin(UserRole::class).on(Role::class).whereAny(UserRole_.user eq user)\`
Projection: \`.select(ProjectionType::class)\` to return lighter types

Operators: eq, notEq, less, lessOrEquals, greater, greaterOrEquals, like, notLike, isNull, isNotNull, inList, notInList

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value.
- Multiple .where() calls are AND-combined.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: selectAll() returns a Flow with automatic cleanup.
