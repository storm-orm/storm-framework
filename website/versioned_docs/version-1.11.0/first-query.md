# First Query

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Once you can insert and fetch records (see [First Entity](first-entity.md)), the next step is querying. This page covers the query patterns you will use most often: filtering with predicates, using repositories, streaming results, and writing type-safe queries with the metamodel.

## Filtering with Predicates

The simplest way to query is with predicate methods directly on the ORM template or entity repository.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = orm.entity(User::class)

// Find all users in a city
val usersInCity: List<User> = users.findAll(User_.city eq city)

// Find a single user by email
val user: User? = users.find(User_.email eq "alice@example.com")

// Combine conditions with and / or
val results: List<User> = users.findAll(
    (User_.city eq city) and (User_.name like "A%")
)

// Check existence
val exists: Boolean = users.existsById(userId)

// Count
val count: Long = users.count()
```

</TabItem>
<TabItem value="java" label="Java">

```java
var users = orm.entity(User.class);

// Find all users in a city
List<User> usersInCity = users.select()
    .where(User_.city, EQUALS, city)
    .getResultList();

// Find a single user by email
Optional<User> user = users.select()
    .where(User_.email, EQUALS, "alice@example.com")
    .getOptionalResult();

// Combine conditions with and / or
List<User> results = users.select()
    .where(it -> it.where(User_.city, EQUALS, city)
            .and(it.where(User_.name, LIKE, "A%")))
    .getResultList();

// Check existence
boolean exists = users.existsById(userId);

// Count
long count = users.count();
```

</TabItem>
</Tabs>

These predicate methods use the [Static Metamodel](metamodel.md) (`User_`, `City_`), which is generated at compile time. The compiler catches typos and type mismatches in field references before your code runs.

## Custom Repositories

For domain-specific queries that you will reuse, define a custom repository interface. This keeps query logic in a single place and makes it testable through interface substitution.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
interface UserRepository : EntityRepository<User, Int> {

    fun findByEmail(email: String): User? =
        find(User_.email eq email)

    fun findByNameInCity(name: String, city: City): List<User> =
        findAll((User_.city eq city) and (User_.name eq name))

    fun streamByCity(city: City): Flow<User> =
        select { User_.city eq city }
}

// Get the repository from the ORM template
val userRepository = orm.repository<UserRepository>()

// Use it
val user = userRepository.findByEmail("alice@example.com")
val usersInCity = userRepository.findByNameInCity("Alice", city)
```

Custom repositories inherit all built-in CRUD operations (`insert`, `findById`, `update`, `delete`, etc.) from `EntityRepository`. You only add methods for domain-specific queries.

</TabItem>
<TabItem value="java" label="Java">

```java
interface UserRepository extends EntityRepository<User, Integer> {

    default Optional<User> findByEmail(String email) {
        return select()
            .where(User_.email, EQUALS, email)
            .getOptionalResult();
    }

    default List<User> findByNameInCity(String name, City city) {
        return select()
            .where(it -> it.where(User_.city, EQUALS, city)
                    .and(it.where(User_.name, EQUALS, name)))
            .getResultList();
    }
}

// Get the repository from the ORM template
UserRepository userRepository = orm.repository(UserRepository.class);

// Use it
Optional<User> user = userRepository.findByEmail("alice@example.com");
```

Custom repositories inherit all built-in CRUD operations from `EntityRepository`. You only add `default` methods for domain-specific queries.

</TabItem>
</Tabs>

See [Repositories](repositories.md) for the full repository pattern, Spring integration, and scrolling.

## Query Builder

For queries that need ordering, pagination, joins, or aggregation, use the fluent query builder.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = orm.entity(User::class)

// Ordering and pagination
val page = users.select()
    .where(User_.city eq city)
    .orderBy(User_.name)
    .limit(10)
    .resultList

// Joins (for entities not directly referenced by @FK)
val roles = orm.entity(Role::class)
    .select()
    .innerJoin(UserRole::class).on(Role::class)
    .whereAny(UserRole_.user eq user)
    .resultList

// Aggregation
data class CityCount(val city: City, val count: Long)

val counts = users.select(CityCount::class) { "${City::class}, COUNT(*)" }
    .groupBy(User_.city)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
var users = orm.entity(User.class);

// Ordering and pagination
List<User> page = users.select()
    .where(User_.city, EQUALS, city)
    .orderBy(User_.name)
    .limit(10)
    .getResultList();

// Joins (for entities not directly referenced by @FK)
List<Role> roles = orm.entity(Role.class)
    .select()
    .innerJoin(UserRole.class).on(Role.class)
    .where(UserRole_.user, EQUALS, user)
    .getResultList();

// Aggregation
record CityCount(City city, long count) {}

List<CityCount> counts = users
    .select(CityCount.class, RAW."\{City.class}, COUNT(*)")
    .groupBy(User_.city)
    .getResultList();
```

</TabItem>
</Tabs>

See [Queries](queries.md) for the full query reference, including scrolling, distinct results, and compound field handling.

## Streaming

For large result sets, streaming avoids loading all rows into memory at once. Rows are fetched lazily from the database as you consume them.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin uses `Flow`, which provides automatic resource management through structured concurrency:

```kotlin
val users: Flow<User> = orm.entity(User::class).selectAll()

// Process each row
users.collect { user -> println(user.name) }

// Transform and collect
val emails: List<String> = users.map { it.email }.toList()
```

</TabItem>
<TabItem value="java" label="Java">

Java uses `Stream`, which holds an open database cursor. Always close streams to release resources:

```java
try (Stream<User> users = orm.entity(User.class).selectAll()) {
    List<String> emails = users.map(User::email).toList();
}
```

</TabItem>
</Tabs>

See [Batch Processing and Streaming](batch-streaming.md) for bulk operations and advanced streaming patterns.

## SQL Templates

When the query builder does not cover your use case (for example, CTEs, window functions, or database-specific syntax), SQL Templates give you full control over the SQL while retaining type safety and parameterized values.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val users = orm.query {
    """SELECT ${User::class}
       FROM ${User::class}
       WHERE ${User_.city} = $city
       ORDER BY ${User_.name}"""
}.resultList<User>()
```

With the [Storm compiler plugin](string-templates.md), interpolated expressions are automatically processed by the template engine: entity types expand to column lists, metamodel fields resolve to column names, and values become parameterized placeholders.

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> users = orm.query(RAW."""
        SELECT \{User.class}
        FROM \{User.class}
        WHERE \{User_.city} = \{city}
        ORDER BY \{User_.name}""")
    .getResultList(User.class);
```

Java uses String Templates (JEP 430) with the `RAW` processor. Entity types expand to column lists, metamodel fields to column names, and values to parameterized placeholders.

</TabItem>
</Tabs>

See [SQL Templates](sql-templates.md) for the full template reference.

## Summary

Storm provides multiple query styles that you can mix freely:

| Style | Best for |
|-------|----------|
| Predicate methods (`find`, `findAll`) | Simple single-entity lookups |
| Custom repositories | Reusable domain-specific queries |
| Query builder | Ordering, pagination, joins, aggregation |
| SQL Templates | Complex SQL, CTEs, window functions |

Start with the simplest approach that fits your query. Move to a more powerful style only when needed.

## Next Steps

- [Queries](queries.md) -- full query reference
- [Repositories](repositories.md) -- repository pattern and Spring integration
- [Entities](entities.md) -- annotations, nullability, and naming conventions
- [Relationships](relationships.md) -- one-to-one, many-to-one, many-to-many
- [Metamodel](metamodel.md) -- compile-time type-safe field references
