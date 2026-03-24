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
```java
var users = orm.entity(User.class);
Optional<User> user = users.select().where(User_.email, EQUALS, email).getOptionalResult();
List<User> list = users.select().where(User_.city, EQUALS, city).getResultList();
long count = users.count();
```

Compound filters:
```java
List<User> result = users.select()
    .where(it -> it.where(User_.city, EQUALS, city)
            .and(it.where(User_.birthDate, LESS_THAN, LocalDate.of(2000, 1, 1))))
    .orderBy(User_.name)
    .getResultList();
```

Nested paths: `User_.city.country.code` with appropriate operator
Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`
Scrolling (keyset): `.scroll(User_.id, 20)`
Explicit joins: `.innerJoin(UserRole.class).on(Role.class).where(UserRole_.user, EQUALS, user)`
Projection: `.select(ProjectionType.class)`

Operators: EQUALS, NOT_EQUALS, LESS_THAN, LESS_THAN_OR_EQUAL, GREATER_THAN, GREATER_THAN_OR_EQUAL, LIKE, NOT_LIKE, IS_NULL, IS_NOT_NULL, IN, NOT_IN

## Aggregation

```java
long count = orm.entity(Order.class).selectCount()
    .where(Order_.status, EQUALS, Status.ACTIVE)
    .getSingleResult();

List<OrderSummary> totals = orm.entity(Order.class)
    .select(OrderSummary.class)
    .groupBy(Order_.status)
    .having(Order_.amount, GREATER_THAN, 100)
    .getResultList();
```

## Row Locking

```java
User user = orm.entity(User.class).select()
    .where(User_.id, EQUALS, userId)
    .forUpdate()         // SELECT ... FOR UPDATE
    .getSingleResult();

// Or shared lock
    .forShare()          // SELECT ... FOR SHARE
```

## Distinct and Count

```java
List<City> uniqueCities = orm.entity(User.class)
    .select(City.class)
    .distinct()
    .getResultList();

long activeCount = orm.entity(User.class)
    .selectCount()
    .where(User_.active, EQUALS, true)
    .getSingleResult();
```

## Ref-Based Queries

```java
// Query by ref
User user = orm.entity(User.class).select()
    .where(userRef)
    .getSingleResult();

// Query by multiple refs
List<User> users = orm.entity(User.class).select()
    .whereRef(userRefs)
    .getResultList();

// Select refs instead of full entities (lightweight)
List<Ref<User>> refs = orm.entity(User.class).selectRef()
    .where(User_.city, EQUALS, city)
    .getResultList();
```

## Bulk DELETE/UPDATE

```java
// DELETE with WHERE (safe)
orm.entity(User.class).delete().where(User_.active, EQUALS, false).executeUpdate();

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User.class).delete().unsafe().executeUpdate();
```

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: selectAll() returns a Stream. ALWAYS use try-with-resources to avoid connection leaks.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key.

After writing queries, offer to write a test using `SqlCapture` to verify the generated SQL matches the user's intent:
```java
@StormTest(scripts = {"schema.sql", "data.sql"})
class UserQueryTest {
    @Test
    void findActiveUsersInCity(ORMTemplate orm, SqlCapture capture) {
        City city = orm.entity(City.class).findById(1).orElseThrow();
        List<User> users = capture.execute(() ->
            orm.entity(User.class).select()
                .where(User_.city, EQUALS, city)
                .orderBy(User_.name)
                .getResultList());
        String sql = capture.statements().getFirst().statement();
        assertTrue(sql.contains("WHERE"));
        assertTrue(sql.contains("ORDER BY"));
        assertFalse(users.isEmpty());
    }
}
```
