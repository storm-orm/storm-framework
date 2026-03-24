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
```kotlin
val user = orm.find { User_.email eq email }
val users = orm.findAll { User_.city eq city }
val exists = orm.existsBy(User_.email, email)
```

QueryBuilder:
```kotlin
val users = orm.entity(User::class)
    .select()
    .where((User_.city eq city) and (User_.birthDate less LocalDate.of(2000, 1, 1)))
    .orderBy(User_.name)
    .resultList
```

Compound filters: `(A eq x) and (B eq y)`, `(A eq x) or (B eq y)`
Nested paths: `User_.city.country.code eq "US"`
Ordering: `.orderBy(User_.name)`, `.orderByDescending(User_.createdAt)`
Pagination: `.page(0, 20)` or `.page(Pageable.ofSize(20).sortBy(User_.name))`
Scrolling (keyset, better for large tables): `.scroll(User_.id, 20)`
Explicit joins: `.innerJoin(UserRole::class).on(Role::class).whereAny(UserRole_.user eq user)`
Projection: `.select(ProjectionType::class)` to return lighter types

Operators: eq, notEq, less, lessOrEquals, greater, greaterOrEquals, like, notLike, isNull, isNotNull, inList, notInList

## Aggregation

```kotlin
val count = orm.entity(Order::class).selectCount().resultList.first()

val totals = orm.entity(Order::class)
    .select(OrderSummary::class)
    .groupBy(Order_.status)
    .having(Order_.amount, Operator.GREATER_THAN, 100)
    .resultList
```

## Row Locking

```kotlin
val user = orm.entity(User::class)
    .select()
    .where(User_.id eq userId)
    .forUpdate()         // SELECT ... FOR UPDATE
    .singleResult

// Or shared lock for reading
    .forShare()          // SELECT ... FOR SHARE
```

## Distinct and Count

```kotlin
val uniqueCities = orm.entity(User::class)
    .select(City::class)
    .distinct()
    .resultList

val count = orm.entity(User::class)
    .selectCount()
    .where(User_.active eq true)
    .singleResult
```

## Ref-Based Queries

```kotlin
// Query by ref
val user = orm.entity(User::class)
    .select()
    .where(userRef)
    .singleResult

// Query by multiple refs
val users = orm.entity(User::class)
    .select()
    .whereRef(userRefs)
    .resultList

// Select refs instead of full entities (lightweight)
val refs = orm.entity(User::class)
    .selectRef()
    .where(User_.city eq city)
    .resultList
```

## Bulk DELETE/UPDATE

```kotlin
// DELETE with WHERE (safe)
orm.entity(User::class).delete().where(User_.active eq false).executeUpdate()

// DELETE/UPDATE without WHERE throws by default. Use unsafe() to confirm intent:
orm.entity(User::class).delete().unsafe().executeUpdate()
```

Critical rules:
- QueryBuilder is IMMUTABLE. Every method returns a new instance. Always use the return value.
- Multiple .where() calls are AND-combined.
- DELETE/UPDATE without WHERE throws. Use unsafe().
- Streaming: selectAll() returns a Flow with automatic cleanup.
- **Metamodel navigation depth**: Multiple levels of navigation are allowed on the root entity. Joined (non-root) entities can only navigate one level deep. For deeper navigation, explicitly join the intermediate entity.
- **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for map keys, set membership, and identity-based lookups. `Ref` provides identity-based `equals`/`hashCode` on the primary key:
  ```kotlin
  val countMap: MutableMap<Ref<Cell>, MutableMap<String, Int>> = mutableMapOf()
  countMap.getOrPut(candidate.cell.ref()) { mutableMapOf() }
  ```

After writing queries, offer to write a test using `SqlCapture` to verify the generated SQL matches the user's intent:
```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
class UserQueryTest {
    @Test
    fun findActiveUsersInCity(orm: ORMTemplate, capture: SqlCapture) {
        val city = orm.entity<City>().getById(1)
        val users = capture.execute {
            orm.entity(User::class).select()
                .where((User_.city eq city) and (User_.active eq true))
                .orderBy(User_.name)
                .resultList
        }
        val sql = capture.statements().first().statement()
        assertContains(sql, "WHERE")
        assertContains(sql, "ORDER BY")
        assertFalse(users.isEmpty())
    }
}
```
