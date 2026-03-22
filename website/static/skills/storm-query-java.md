Help the user write Storm queries using Java.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask what data they need, filters, ordering, or pagination.

Three query levels (suggest the simplest that works):
| Approach | Best for |
|----------|----------|
| findById / select().where() | Simple lookups |
| QueryBuilder (fluent chain) | Most application queries |
| SQL Templates (/storm-sql-java) | CTEs, window functions, subqueries |

Quick queries:
\`\`\`java
var users = orm.entity(User.class);
Optional<User> user = users.select().where(User_.email, EQUALS, email).getOptionalResult();
List<User> list = users.select().where(User_.city, EQUALS, city).getResultList();
long count = users.count();
\`\`\`

Compound filters:
\`\`\`java
List<User> result = users.select()
    .where(it -> it.where(User_.city, EQUALS, city)
            .and(it.where(User_.birthDate, LESS_THAN, LocalDate.of(2000, 1, 1))))
    .orderBy(User_.name)
    .getResultList();
\`\`\`

Nested paths: \`User_.city.country.code\` with appropriate operator
Ordering: \`.orderBy(User_.name)\`, \`.orderByDescending(User_.createdAt)\`
Pagination: \`.page(0, 20)\` or \`.page(Pageable.ofSize(20).sortBy(User_.name))\`
Scrolling (keyset): \`.scroll(User_.id, 20)\`
Explicit joins: \`.innerJoin(UserRole.class).on(Role.class).where(UserRole_.user, EQUALS, user)\`
Projection: \`.select(ProjectionType.class)\`

Operators: EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL, IN, NOT_IN

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: selectAll() returns a Stream. ALWAYS use try-with-resources to avoid connection leaks.
