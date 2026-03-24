# First Entity

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

This guide walks you through defining your first Storm entity, creating an ORM template, and performing basic CRUD operations. By the end, you will have inserted a record into the database and read it back.

## Define an Entity

Storm entities are plain data classes (Kotlin) or records (Java) that implement the `Entity<ID>` interface. Annotate the primary key with `@PK` and foreign keys with `@FK`. Storm maps field names to column names automatically using camelCase-to-snake_case conversion, so no XML or additional configuration is needed.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class City(
    @PK val id: Int = 0,
    val name: String,
    val population: Long
) : Entity<Int>

data class User(
    @PK val id: Int = 0,
    val email: String,
    val name: String,
    @FK val city: City
) : Entity<Int>
```

Non-nullable fields (like `city: City`) produce `INNER JOIN` queries. Nullable fields (like `city: City?`) produce `LEFT JOIN` queries. Kotlin's type system maps directly to Storm's null handling.

</TabItem>
<TabItem value="java" label="Java">

```java
@Builder(toBuilder = true)
record City(@PK Integer id,
            String name,
            long population
) implements Entity<Integer> {}

@Builder(toBuilder = true)
record User(@PK Integer id,
            String email,
            String name,
            @FK City city
) implements Entity<Integer> {}
```

In Java, record components are nullable by default. Use `@Nonnull` on fields that must always have a value. Primitive types (`int`, `long`, etc.) are inherently non-nullable.

The `@Builder` annotation is from [Lombok](https://projectlombok.org/) and is optional. It generates a builder that lets you construct entities without specifying the primary key, and creates modified copies via `toBuilder()`. Without Lombok, you can pass `null` as the primary key (e.g., `new City(null, "Sunnyvale", 155_000)`) or define a convenience constructor that omits it. See [Modifying Entities](entities.md#modifying-entities) for details.

</TabItem>
</Tabs>

These entities map to the following database tables:

| Table | Columns |
|-------|---------|
| `city` | `id`, `name`, `population` |
| `user` | `id`, `email`, `name`, `city_id` |

Storm automatically appends `_id` to foreign key column names. See [Entities](entities.md) for the full set of annotations, naming conventions, and customization options.

## Create the ORM Template

The `ORMTemplate` is the central entry point for all database operations. It is thread-safe and typically created once at application startup (or provided as a Spring bean). You can create one from a JDBC `DataSource`, `Connection`, or JPA `EntityManager`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin provides extension properties for concise creation:

```kotlin
// From a DataSource (most common)
val orm = dataSource.orm

// From a Connection
val orm = connection.orm

// From a JPA EntityManager
val orm = entityManager.orm
```

</TabItem>
<TabItem value="java" label="Java">

Use the `ORMTemplate.of(...)` factory methods:

```java
// From a DataSource (most common)
var orm = ORMTemplate.of(dataSource);

// From a Connection
var orm = ORMTemplate.of(connection);

// From a JPA EntityManager
var orm = ORMTemplate.of(entityManager);
```

</TabItem>
</Tabs>

If you are using Spring Boot with one of the starter modules, the `ORMTemplate` bean is created automatically. See [Spring Integration](spring-integration.md) for details.

## Insert a Record

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Storm's Kotlin API provides infix operators for a concise syntax:

```kotlin
// Insert a city -- the returned object has the database-generated ID
val city = orm insert City(name = "Sunnyvale", population = 155_000)

// Insert a user that references the city
val user = orm insert User(
    email = "alice@example.com",
    name = "Alice",
    city = city
)
```

The `insert` operator sends an INSERT statement, retrieves the auto-generated primary key, and returns a new instance with the key populated. You do not need to set the `id` field yourself when using `IDENTITY` generation (the default).

</TabItem>
<TabItem value="java" label="Java">

```java
var cities = orm.entity(City.class);
var users = orm.entity(User.class);

// Insert a city -- the returned object has the database-generated ID
City city = cities.insertAndFetch(City.builder()
        .name("Sunnyvale")
        .population(155_000)
        .build());

// Insert a user that references the city
User user = users.insertAndFetch(User.builder()
        .email("alice@example.com")
        .name("Alice")
        .city(city)
        .build());
```

The `insertAndFetch` method sends an INSERT statement, retrieves the auto-generated primary key, and returns a new record with the key populated.

</TabItem>
</Tabs>

## Read a Record

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Find by ID
val user: User? = orm.entity<User>().findById(userId)

// Find by field value using the metamodel (requires storm-metamodel-processor)
val user: User? = orm.find { User_.email eq "alice@example.com" }
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Find by ID
Optional<User> user = orm.entity(User.class).findById(userId);

// Find by field value using the metamodel (requires storm-metamodel-processor)
Optional<User> user = orm.entity(User.class)
    .select()
    .where(User_.email, EQUALS, "alice@example.com")
    .getOptionalResult();
```

</TabItem>
</Tabs>

When Storm loads a `User`, it automatically joins the `City` table (because `city` is marked with `@FK`) and populates the full `City` object in a single query. There is no N+1 problem.

## Update a Record

Since entities are immutable, you create a new instance with the changed fields and pass it to the update operation.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val updatedUser = orm update user.copy(name = "Alice Johnson")
```

</TabItem>
<TabItem value="java" label="Java">

```java
users.update(new User(user.id(), user.email(), "Alice Johnson", user.city()));
```

</TabItem>
</Tabs>

## Delete a Record

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm delete user
```

</TabItem>
<TabItem value="java" label="Java">

```java
users.delete(user);
```

</TabItem>
</Tabs>

## Transactions

Wrap multiple operations in a transaction to ensure they succeed or fail together.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Storm provides a `transaction` block that commits on success and rolls back on exception:

```kotlin
transaction {
    val city = orm insert City(name = "Sunnyvale", population = 155_000)
    val user = orm insert User(email = "bob@example.com", name = "Bob", city = city)
}
```

</TabItem>
<TabItem value="java" label="Java">

With Spring's `@Transactional`:

```java
@Transactional
public User createUser(String email, String name, City city) {
    return orm.entity(User.class)
        .insertAndFetch(User.builder()
                .email(email)
                .name(name)
                .city(city)
                .build());
}
```

</TabItem>
</Tabs>

See [Transactions](transactions.md) for programmatic transaction control, propagation modes, and savepoints.

## Summary

You have now seen the core workflow:

1. Define entities as data classes or records with `@PK` and `@FK` annotations
2. Create an `ORMTemplate` from a `DataSource`
3. Use `insert`, `findById`, `update`, and `delete` for basic CRUD

## Next Steps

- [First Query](first-query.md) -- custom queries, repositories, filtering, and streaming
- [Entities](entities.md) -- enumerations, versioning, composite keys, and naming conventions
- [Spring Integration](spring-integration.md) -- auto-configuration and dependency injection
