import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Error Handling

When something goes wrong, Storm communicates the problem through a small, well-defined set of exception types. Understanding which exceptions can be thrown and when helps you write robust error handling that distinguishes between recoverable situations (like a missing entity) and programming mistakes (like a schema mismatch).

This page covers Storm's exception hierarchy, the most common error scenarios you will encounter, and strategies for diagnosing problems when they arise.

---

## Exception Hierarchy

Storm uses unchecked exceptions for most error conditions. The root type is `PersistenceException`, which extends `RuntimeException`. This means you are not forced to catch exceptions at every call site; instead, you can handle them at the appropriate layer of your application.

| Exception | Extends | When It Is Thrown |
|---|---|---|
| `PersistenceException` | `RuntimeException` | General database or SQL errors. This is the root of Storm's exception hierarchy. |
| `NoResultException` | `PersistenceException` | `getSingleResult()` returns no rows. |
| `NonUniqueResultException` | `PersistenceException` | `getSingleResult()` or `getOptionalResult()` returns more than one row. |
| `OptimisticLockException` | `PersistenceException` | An update or delete detects a version conflict (the row was modified by another transaction). |
| `SchemaValidationException` | `PersistenceException` | Schema validation finds mismatches between entity definitions and the database schema. |
| `SqlTemplateException` | `SQLException` | An error occurred during SQL template processing. Often attached as a suppressed exception to provide the generated SQL alongside the original error. |

The hierarchy is intentionally flat. Most code only needs to catch `PersistenceException` and, occasionally, its specific subtypes.

```
RuntimeException
 └── PersistenceException
      ├── NoResultException
      ├── NonUniqueResultException
      ├── OptimisticLockException
      └── SchemaValidationException

SQLException
 └── SqlTemplateException
```

---

## Common Error Scenarios

### No Result Found

When you call `getSingleResult()` on a query that returns zero rows, Storm throws `NoResultException`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Throws NoResultException if no user has this email.
val user = orm.entity(User::class).select(User_.email eq "nobody@example.com").getSingleResult()
```

To handle the missing-result case without exceptions, use `getOptionalResult()`:

```kotlin
val user: User? = orm.entity(User::class)
    .select(User_.email eq "nobody@example.com")
    .getOptionalResult(User::class)
```

Or use the repository's `findById` method:

```kotlin
val user: User? = userRepository.findById(42)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Throws NoResultException if no user has this email.
User user = orm.entity(User.class).select(User_.email.eq("nobody@example.com")).getSingleResult();
```

To handle the missing-result case without exceptions, use `getOptionalResult()`:

```java
Optional<User> user = orm.entity(User.class)
    .select(User_.email.eq("nobody@example.com"))
    .getOptionalResult();
```

Or use the repository's `findById` method:

```java
Optional<User> user = userRepository.findById(42);
```

</TabItem>
</Tabs>

### Multiple Results When One Was Expected

`getSingleResult()` and `getOptionalResult()` both throw `NonUniqueResultException` when the query returns more than one row. This typically signals a logical error in your query or data:

```
NonUniqueResultException: Expected single result, but found more than one.
```

If multiple results are valid, use `getResultList()` or `getResultStream()` instead.

### Optimistic Lock Conflicts

When an entity has a `@Version` column and the version in the database no longer matches the version in your entity, the update or delete fails with an `OptimisticLockException`. This happens when another transaction modified the same row between your read and your write.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
try {
    userRepository.update(outdatedUser)
} catch (exception: OptimisticLockException) {
    // The entity was modified by another transaction.
    // Reload and retry, or inform the user.
    val freshUser = userRepository.getById(outdatedUser.id())
    // ... merge changes and retry
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
try {
    userRepository.update(outdatedUser);
} catch (OptimisticLockException exception) {
    // The entity was modified by another transaction.
    // Reload and retry, or inform the user.
    User freshUser = userRepository.getById(outdatedUser.id());
    // ... merge changes and retry
}
```

</TabItem>
</Tabs>

The exception includes a reference to the entity that caused the conflict, accessible via `getEntity()`.

### Constraint Violations

Database constraint violations (unique constraints, foreign key constraints, not-null constraints) surface as `PersistenceException` wrapping the underlying JDBC `SQLException`. The original SQL error message and vendor-specific error code are preserved in the exception chain:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
try {
    userRepository.insert(duplicateUser)
} catch (exception: PersistenceException) {
    val cause = exception.cause
    if (cause is java.sql.SQLIntegrityConstraintViolationException) {
        // Handle duplicate key, foreign key violation, etc.
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
try {
    userRepository.insert(duplicateUser);
} catch (PersistenceException exception) {
    Throwable cause = exception.getCause();
    if (cause instanceof java.sql.SQLIntegrityConstraintViolationException) {
        // Handle duplicate key, foreign key violation, etc.
    }
}
```

</TabItem>
</Tabs>

### Schema Validation Errors

When schema validation is enabled, Storm checks your entity definitions against the actual database schema at startup or first use. If there are mismatches, it throws a `SchemaValidationException` with a detailed list of errors:

```
SchemaValidationException: Schema validation failed with 2 error(s):
  - Table 'user': column 'email' not found in database
  - Table 'user': column 'name' type mismatch: expected VARCHAR, found INTEGER
```

Each individual error is available programmatically through `getErrors()`, making it possible to build custom reporting or migration tooling.

### Connection and Database Errors

Low-level database problems (connection failures, query timeouts, syntax errors) are wrapped in `PersistenceException`. The original `SQLException` is always available as the cause, preserving the vendor error code and SQL state:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
try {
    userRepository.findAll()
} catch (exception: PersistenceException) {
    val sqlCause = exception.cause as? java.sql.SQLException
    if (sqlCause != null) {
        println("SQL State: ${sqlCause.sqlState}")
        println("Error Code: ${sqlCause.errorCode}")
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
try {
    userRepository.findAll();
} catch (PersistenceException exception) {
    if (exception.getCause() instanceof SQLException sqlCause) {
        System.out.println("SQL State: " + sqlCause.getSQLState());
        System.out.println("Error Code: " + sqlCause.getErrorCode());
    }
}
```

</TabItem>
</Tabs>

---

## Debugging Strategies

### Enable SQL Logging

The fastest way to diagnose a query problem is to see the generated SQL. Use the `@SqlLog` annotation on your repository to log every statement:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@SqlLog
interface UserRepository : EntityRepository<User, Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@SqlLog
public interface UserRepository extends EntityRepository<User, Integer> {}
```

</TabItem>
</Tabs>

For more targeted logging, annotate individual methods instead of the entire repository. See the [SQL Logging](sql-logging.md) page for details.

### Use SqlCapture in Tests

The `SqlCapture` class from `storm-test` records all SQL statements generated during a block of code. This is useful for verifying that the correct queries are being generated:

```java
var capture = new SqlCapture();
capture.run(() -> {
    userRepository.findAll();
});

// Inspect the captured SQL.
List<CapturedSql> statements = capture.statements();
assertEquals(1, statements.size());
assertTrue(statements.get(0).statement().contains("SELECT"));
```

See the [Testing](testing.md) page for full details on `SqlCapture` and the `@StormTest` annotation.

### Read the Suppressed SQL

When a `PersistenceException` is thrown during query execution, Storm attaches the generated SQL as a suppressed `SqlTemplateException`. This means the full SQL text is available in the exception chain even when the original error is a JDBC-level failure:

```java
try {
    userRepository.findAll();
} catch (PersistenceException exception) {
    for (Throwable suppressed : exception.getSuppressed()) {
        if (suppressed instanceof SqlTemplateException) {
            System.out.println("Generated SQL: " + suppressed.getMessage());
        }
    }
}
```

### Enable Schema Validation

Schema validation catches entity-to-database mismatches early, before they surface as cryptic SQL errors at runtime. Enable it through configuration to get clear, actionable error messages about missing columns, type mismatches, and other structural issues. See the [Validation](validation.md) page for configuration details.

---

## Common Mistakes

### Using getSingleResult() Without a WHERE Clause

Calling `getSingleResult()` on a query that returns all rows will throw `NonUniqueResultException` unless the table contains exactly one row. If you want to check whether results exist, use `getResultCount()` or `getResultStream()`.

### Catching PersistenceException Too Broadly

Catching `PersistenceException` at a high level can hide programming errors like schema mismatches or invalid queries. Prefer catching specific subtypes where possible, and let unexpected exceptions propagate to your application's global error handler.

### Ignoring OptimisticLockException

When using `@Version` columns, always have a strategy for handling `OptimisticLockException`. Common approaches include retrying the operation after reloading the entity, or returning a conflict response to the client and letting them resolve it.

### Not Closing Streams

`getResultStream()` holds a database cursor open. Always close it when done, either with a try-with-resources block or by collecting into a list:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Collect into a list (automatically closes the stream).
val users = userRepository.select().getResultList()

// Or use try-with-resources for lazy processing.
userRepository.select().getResultStream().use { stream ->
    stream.forEach { user -> process(user) }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Collect into a list (automatically closes the stream).
List<User> users = userRepository.select().getResultList();

// Or use try-with-resources for lazy processing.
try (var stream = userRepository.select().getResultStream()) {
    stream.forEach(user -> process(user));
}
```

</TabItem>
</Tabs>

---

## Common Beginner Mistakes

### Metamodel Class Does Not Compile (`User_` Not Found)

**Symptom:** Your code references `User_` but the compiler reports that the class does not exist.

**Cause:** The metamodel processor is not configured. Storm generates companion classes like `User_` at compile time using an annotation processor (Java) or KSP plugin (Kotlin).

**Fix:**

For **Kotlin with Gradle**, add the KSP plugin and processor dependency:

```kotlin
plugins {
    id("com.google.devtools.ksp")
}

dependencies {
    ksp("st.orm:storm-metamodel-ksp:${stormVersion}")
}
```

For **Java with Maven**, configure the annotation processor in the compiler plugin:

```xml
<annotationProcessorPaths>
    <path>
        <groupId>st.orm</groupId>
        <artifactId>storm-metamodel-processor</artifactId>
        <version>${storm.version}</version>
    </path>
</annotationProcessorPaths>
```

### Using `var` Instead of `val` in Kotlin Data Class Fields

**Symptom:** Storm throws an error or behaves unexpectedly when reading or writing entities.

**Cause:** Storm entities are designed to be immutable. Kotlin data class fields should use `val`, not `var`.

**Fix:** Change all `var` declarations to `val`:

```kotlin
// Wrong
data class User(
    @PK var id: Int = 0,
    var name: String
) : Entity<Int>

// Correct
data class User(
    @PK val id: Int = 0,
    val name: String
) : Entity<Int>
```

### Using `@Column` Instead of `@DbColumn`

**Symptom:** Your custom column name annotation is ignored. Storm maps the field using its default naming convention instead.

**Cause:** Storm uses `@DbColumn` for column name overrides, not `@Column` (which is a JPA annotation that Storm does not process).

**Fix:** Replace `@Column` with `@DbColumn`:

```kotlin
// Wrong
data class User(
    @PK val id: Int = 0,
    @Column("email_address") val email: String
) : Entity<Int>

// Correct
data class User(
    @PK val id: Int = 0,
    @DbColumn("email_address") val email: String
) : Entity<Int>
```

### Forgot `@FK` on a Relationship Field

**Symptom:** Storm treats the field as an embedded component or fails with a mapping error instead of generating a JOIN.

**Cause:** Without the `@FK` annotation, Storm does not know that the field represents a foreign key relationship.

**Fix:** Add `@FK` to any field that references another entity:

```kotlin
// Wrong
data class User(
    @PK val id: Int = 0,
    val city: City
) : Entity<Int>

// Correct
data class User(
    @PK val id: Int = 0,
    @FK val city: City
) : Entity<Int>
```

### Forgot Dialect Dependency When Using Upsert

**Symptom:** Calling `upsert()` throws an `UnsupportedOperationException` or `PersistenceException`.

**Cause:** Upsert requires a database-specific dialect module because the SQL syntax differs between databases (e.g., `ON CONFLICT` for PostgreSQL, `ON DUPLICATE KEY` for MySQL).

**Fix:** Add the dialect dependency for your database:

```xml
<!-- PostgreSQL -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-postgresql</artifactId>
</dependency>

<!-- MySQL -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-mysql</artifactId>
</dependency>
```
