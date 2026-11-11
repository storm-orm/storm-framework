import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Error Handling

When something goes wrong, Storm reports it through a small set of exception types. The distinction that matters when you catch them is between a recoverable situation, like a missing entity, and a programming mistake, like a schema mismatch.

This page covers the exception hierarchy, the errors you are most likely to hit, and how to diagnose them. For mapping these exceptions to HTTP responses in a Ktor application, see the StatusPages recipes in [Ktor Integration](ktor-integration.md#error-handling).

---

## Exception Hierarchy

Storm uses unchecked exceptions for most error conditions. The root type is `PersistenceException`, which extends `RuntimeException`. This means you are not forced to catch exceptions at every call site; instead, you can handle them at the appropriate layer of your application.

| Exception | Extends | When It Is Thrown |
|---|---|---|
| `PersistenceException` | `RuntimeException` | General database or SQL errors. This is the root of Storm's exception hierarchy. |
| `NoResultException` | `PersistenceException` | `getSingleResult()` returns no rows. |
| `NonUniqueResultException` | `PersistenceException` | `getSingleResult()` or `getOptionalResult()` returns more than one row. |
| `TransientPersistenceException` | `PersistenceException` | The failure stems from concurrent activity, such as a deadlock or a serialization failure, and running the whole transaction again may succeed. See [Deadlocks and Serialization Failures](#deadlocks-and-serialization-failures). |
| `OptimisticLockException` | `TransientPersistenceException` | An update or delete detects a version conflict (the row was modified by another transaction). |
| `UnexpectedRowCountException` | `PersistenceException` | An insert, update or remove affects another number of rows than it expects, such as an update of an entity whose row does not exist. Carries the expected and actual row counts. |
| `IntegrityViolationException` | `PersistenceException` | A value breaks a rule of its table: the database refuses a write for a constraint, a non-nullable field holds null at write time, or `validateOrThrow` finds a value too large for its column. Carries the violations, each with its kind and the entity's fields. See [Integrity Violations](#integrity-violations). |
| `SchemaValidationException` | `PersistenceException` | Schema validation finds mismatches between entity definitions and the database schema. |
| `IllegalTransactionStateException` | `PersistenceException` | A transaction block cannot open against the transaction state it finds: `MANDATORY` without a transaction, `NEVER` inside one, or a joined block stating a stricter isolation level than the transaction it joins. |
| `TransactionTimedOutException` | `PersistenceException` | A transaction exceeds its configured timeout. |
| `UnexpectedRollbackException` | `PersistenceException` | A commit was attempted but the transaction had been marked rollback-only, for example by a joined inner scope. |
| `TransactionCallbackException` | `PersistenceException` | A transaction completion callback failed. The transaction itself completed; `isCommitted()` says which way, so a failed side effect is distinguishable from a failed transaction. |
| `ReadOnlyTransactionException` | `PersistenceException` | An `INSERT`, `UPDATE` or `DELETE` is issued inside a read-only transaction, or inside a block that declared itself read-only. |
| `InvalidCursorException` | `PersistenceException` | A scroll cursor is refused: malformed, from an earlier format, issued for another ordering, or carrying a value of the wrong type. |
| `SqlTemplateException` | `SQLException` | An error occurred during SQL template processing. Often attached as a suppressed exception to provide the generated SQL alongside the original error. |

The hierarchy is intentionally flat. Most code only needs to catch `PersistenceException` and, occasionally, its specific subtypes.

```
RuntimeException
 └── PersistenceException
      ├── NoResultException
      ├── NonUniqueResultException
      ├── TransientPersistenceException
      │    └── OptimisticLockException
      ├── UnexpectedRowCountException
      ├── IntegrityViolationException
      ├── SchemaValidationException
      ├── IllegalTransactionStateException
      ├── TransactionTimedOutException
      ├── UnexpectedRollbackException
      ├── TransactionCallbackException
      ├── ReadOnlyTransactionException
      └── InvalidCursorException

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
val user = orm.entity(User::class).select(User_.email eq "nobody@example.com").singleResult
```

To handle the missing-result case without exceptions, use `optionalResult`:

```kotlin
val user: User? = orm.entity(User::class)
    .select(User_.email eq "nobody@example.com")
    .optionalResult
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

With the Spring Boot starter, exception translation turns this into Spring's `OptimisticLockingFailureException`, a missing or non-unique single result into `EmptyResultDataAccessException` or `IncorrectResultSizeDataAccessException`, and an unexpected row count into `IncorrectUpdateSemanticsDataAccessException`, with the Storm exception as the cause. See [Exception Translation](spring-integration.md#exception-translation).

The exception includes a reference to the entity that caused the conflict, accessible via `getEntity()`.

### Deadlocks and Serialization Failures

When two transactions conflict, the database ends one of them. In a deadlock, each transaction waits for a row the other one holds. In a serialization failure, a transaction at `REPEATABLE_READ` or `SERIALIZABLE` writes a row that another transaction changed after it was read. Storm throws `TransientPersistenceException` for both. The dialect recognises the codes its database reports, so the type is the same on every database. `OptimisticLockException` is a subtype: a version conflict is concurrent activity too.

A database reports a serialization failure at a statement or at the commit. A database that tracks what a serializable transaction read detects some conflicts only when the transaction commits: two transactions that each read a row the other one writes both run all their statements, and the commit of one of them fails. The commit's failure is classified the same way, so it throws `TransientPersistenceException` through the template's exception mapper, and `retries` reruns the block. A database that serializes with locks reports the same conflict at a statement, as a deadlock.

The type is the signal to run the whole transaction again. The transaction the failure occurred in cannot commit its work, so the block reruns from its start, never the failed statement alone. `retries` on the transactional block does this: it states the number of reruns after the first attempt, and Storm reruns the block on a transient failure only, each attempt in a transaction of its own. A block that joins an outer transaction, or runs without one, refuses `retries` with `IllegalTransactionStateException`, since only the block that starts the transaction can start it over.

A rerun repeats everything the block does. Its database writes rolled back with the failed attempt, but anything else it did happens again: a message sent, a call to another service, a change to state the block captured. A block that may be rerun touches nothing outside the database except through [`onCommit`](transactions.md#transaction-callbacks), which runs only for the attempt that committed; a side effect that must not be lost goes into an outbox row written in the transaction, since `onCommit` runs at most once. See [Rerunning a Transaction](transactions.md#rerunning-a-transaction).

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
transactionBlocking(isolation = SERIALIZABLE, retries = 2) {
    inventoryRepository.decrease(productId, quantity)
    val order = orderRepository.insertAndFetch(newOrder)
    onCommit { emailService.sendOrderConfirmation(order) }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
transaction(TransactionOptions.defaults().withIsolation(SERIALIZABLE), 2, tx -> {
    inventoryRepository.decrease(productId, quantity);
    Order order = orderRepository.insertAndFetch(newOrder);
    tx.onCommit(() -> emailService.sendOrderConfirmation(order));
    return order;
});
```

</TabItem>
</Tabs>

When the last attempt fails, its exception leaves the block with the earlier attempts' failures as suppressed. The rerun decides on Storm's classification of the failure, not on the type of the exception thrown, so it holds when an exception mapper translates the failure to another hierarchy.

Only failures on which the database gave up the transaction's work are transient. A lock wait that runs out is not: some databases answer it by rolling back only the statement that waited. A lost connection is not either, also when it is lost during the commit: whether the transaction committed is unknown, and running it again could apply it twice. A commit that fails with a serialization failure is transient: the database states that the transaction did not commit.

With the Spring Boot starter, exception translation turns these failures into Spring's `TransientDataAccessException` subtypes, such as `ConcurrencyFailureException`, and `retries` reruns on them all the same. See [Exception Translation](spring-integration.md#exception-translation).

### Writes That Affect No Row

An insert, update or remove of an entity expects to affect exactly one row, and a batch one row per entity. When the database reports another count, Storm throws `UnexpectedRowCountException`, with the counts on `getExpectedRowCount()` and `getActualRowCount()`. The usual cause is an update or remove of an entity whose row does not exist, or a trigger that suppressed the write. For an entity with a `@Version` field, an update that affects no row is an `OptimisticLockException` instead.

### Integrity Violations

A write the database refuses for a constraint reaches the caller as an `IntegrityViolationException`. Storm reads what the database reports and states it in terms of the entity: each violation is a record of its kind, carrying the fields it resolves to as metamodel keys and, where the database reports them, the constraint and the table. A caller matches on the record type and a field instead of on a vendor error code:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
try {
    users.insert(user)
} catch (e: IntegrityViolationException) {
    if (e.violations(User_.email).any { it is IntegrityViolation.Unique }) {
        // The email is taken.
    }
    throw e
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
try {
    users.insert(user);
} catch (IntegrityViolationException e) {
    if (e.violations(User_.email).stream().anyMatch(v -> v instanceof IntegrityViolation.Unique)) {
        // The email is taken.
    }
    throw e;
}
```

</TabItem>
</Tabs>

`IntegrityViolation` is a sealed interface with a record per kind, each naming the SQL state the standard assigns to it: `Unique` (`23505`), a value another row already holds under a unique key or the primary key; `ForeignKey` (`23503`), a reference to a row that does not exist; `Referenced` (`23503` as well), a row other rows still reference, so it cannot be removed; `NotNull` (`23502`); `Check` (`23514`); `Length` (`22001`), a string longer than its column; and `NumericRange` (`22003`), a number with more digits before the decimal point than its column holds. Each record carries what its kind can report: `Referenced` and `Check` have no fields, `NotNull`, `Length` and `NumericRange` no constraint name, so a Kotlin `when` or a Java pattern switch over the records is exhaustive. A database that reports a violation under a code of its own is read by its dialect, and the driver's exception stays the cause for a caller that needs the code as reported. A compound key resolves to each of its fields. The fields are set only where the database identifies the column or the constraint; a violation whose column the database does not name has no fields, and Storm never infers one. A constraint resolves to its fields through the schema loaded at startup, by schema mode `fail` (the default), `warn` or `load`, or by `loadSchema()`; a failed write never reads the database's metadata, and without a loaded schema the violation keeps its kind and the names the database reported. For a `Referenced` violation, `type()` is the type of the removed row and `table()` the table that still references it. The driver's exception stays the cause, with the statement attached as a suppressed exception.

The kind is read from the error code or SQL state, which a database keeps stable across its versions. The constraint, table and column names are read from the error's structured fields where the database has them, and otherwise from the identifiers its message quotes and the SQL terms it carries, which a server configured for another language keeps while it translates the words around them. A message a later version reshapes costs the names and the fields they resolve to, not the kind. Storm's conformance suite runs every kind against every supported database, one of them with its messages in another language.

The database refuses a write for one constraint at a time, so a violation it reports comes alone. A constraint the database checks at commit, a deferred constraint, is reported from the commit, with the kind and the constraint and no fields. One violation is found before any statement runs: a null in a field whose column refuses it, which Storm has always refused when it reads the entity's values for a write, is reported as a `NotNull` violation on the field. That is the type-based guard, not `validate`; a write never checks lengths or precision.

The same exception passes through the template's exception mapper like any failure the database reported, so a mapper translates it to its own type; the [Spring integration](spring-integration.md#exception-translation) translates a `Unique` violation to `DuplicateKeyException` and the other kinds to `DataIntegrityViolationException`. At a web endpoint a violation answers with a status that says whose fault it is: see [In a Web Application](data-validation.md#in-a-web-application).

### Checking an Entity Before the Write

`validate` checks an entity against its columns without writing it and returns every violation found: a null in a non-nullable field, a string longer than its character column, and a decimal with more digits before the decimal point than its column holds. The limits come from the database schema Storm loads at startup, through [schema validation](validation.md) or `loadSchema()`; no annotation restates them, and nothing reads the schema after that. `validateOrThrow` throws the same violations as one `IntegrityViolationException`, through the exception mapper, so a handler that catches a database-reported violation catches these too.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val violations = users.validate(user)      // empty: every value fits its column
users.validateOrThrow(user)                // throws IntegrityViolationException with every violation
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<IntegrityViolation> violations = users.validate(user);  // empty: every value fits its column
users.validateOrThrow(user);                                  // throws IntegrityViolationException with every violation
```

</TabItem>
</Tabs>

A `Length` violation carries the column's `maxLength()`, a `NumericRange` violation its `precision()` and `scale()`, so a message can state the limit; a violation the database reported carries none. The checks are never stricter than the database: a string's length counts code points, a database that stores any value whatever the declared size is not checked against it, and a database like that needs no schema. Elsewhere, `validate` without a loaded schema throws a `PersistenceException` saying so, since it could not compare anything. Rules that depend on other rows, a unique key or a foreign key's target, are the database's to check; `validate` leaves them to the write.

### Schema Validation Errors

When schema validation is enabled, Storm checks your entity definitions against the actual database schema at startup or first use. If there are mismatches, it throws a `SchemaValidationException` with a detailed list of errors:

```
SchemaValidationException: Schema validation failed with 2 error(s):
  - Table 'user': column 'email' not found in database
  - Table 'user': column 'name' type mismatch: expected VARCHAR, found INTEGER
```

Each individual error is available programmatically through `getErrors()`, making it possible to build custom reporting or migration tooling.

### Connection and Database Errors

Low-level database problems (connection failures, query timeouts, syntax errors) are wrapped in `PersistenceException`; a lost connection is never a `TransientPersistenceException`, since the outcome of its transaction is unknown. The original `SQLException` is always available as the cause, preserving the vendor error code and SQL state:

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

The fastest way to diagnose a query problem is to see the generated SQL. Raise the `st.orm.sql` logger; no code change is needed:

```yaml
logging:
  level:
    st.orm.sql: DEBUG      # statements as sent, with placeholders
    # st.orm.sql: TRACE    # with parameter values rendered in, ready to paste into a console
```

To narrow the focus to one entity, raise its own logger instead: `st.orm.sql.User`. To see what a whole call cost rather than the statements one by one, wrap it in `sqlLog("name") { }` or enable the per-entry-point summaries. See the [SQL Logging](sql-logging.md) page for details.

### Use SqlCapture in Tests

The `SqlCapture` class from `storm-test` records all SQL statements generated during a block of code. This is useful for verifying that the correct queries are being generated:

```java
var capture = new SqlCapture();
capture.record(() -> {
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

Calling `getSingleResult()` on a query that returns all rows will throw `NonUniqueResultException` unless the table contains exactly one row. If you want to check whether results exist, use `exists()`, which stops at the first matching row.

### Catching PersistenceException Too Broadly

Catching `PersistenceException` at a high level can hide programming errors like schema mismatches or invalid queries. Prefer catching specific subtypes where possible, and let unexpected exceptions propagate to your application's global error handler.

### Ignoring OptimisticLockException

When using `@Version` columns, always have a strategy for handling `OptimisticLockException`. Common approaches include running the transaction again with `retries`, which reloads the entity, or returning a conflict response to the client and letting them resolve it. A rerun follows the rules of [Deadlocks and Serialization Failures](#deadlocks-and-serialization-failures).

### Not Closing Streams

`getResultStream()` holds a database cursor open. Always close it when done, either with a try-with-resources block or by collecting into a list:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Collect into a list (automatically closes the stream).
val users = userRepository.select().resultList

// Or use `use` for lazy processing.
userRepository.select().resultStream.use { stream ->
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

For **Kotlin with Gradle**, the simplest fix is to apply the [Storm Gradle plugin](installation.md#gradle-plugin-recommended), which wires the KSP processor automatically. If you configure it manually, add the KSP plugin and processor dependency:

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
