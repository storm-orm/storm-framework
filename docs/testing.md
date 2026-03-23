import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Testing

Writing tests for database code can involve repetitive setup: creating a `DataSource`, running schema scripts, obtaining an `ORMTemplate`, and wiring everything together before the first assertion. Storm's test support module reduces this to a single annotation, letting you focus on the behavior you are testing rather than infrastructure.

The module provides two categories of functionality:

1. **JUnit 5 integration** (`@StormTest`) for automatic database setup, script execution, and parameter injection.
2. **Statement capture** (`SqlCapture`) for recording and inspecting SQL statements generated during test execution. This component is framework-agnostic and works independently of JUnit.

---

## Installation

Add `storm-test` as a test dependency.

**Maven:**

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-test</artifactId>
    <scope>test</scope>
</dependency>
```

**Gradle (Kotlin DSL):**

```kotlin
testImplementation("st.orm:storm-test")
```

The module uses H2 as its default in-memory database. To use H2, add it as a test dependency if it is not already present:

```xml
<dependency>
    <groupId>com.h2database</groupId>
    <artifactId>h2</artifactId>
    <scope>test</scope>
</dependency>
```

### JUnit 5 is Optional

JUnit 5 (`junit-jupiter-api`) is an optional dependency of `storm-test`. It is not pulled in transitively, so it does not appear on your classpath unless you add it yourself. Most projects already have JUnit Jupiter as a test dependency, in which case the `@StormTest` annotation and `StormExtension` are available automatically with no extra configuration.

If you only need `SqlCapture` and `CapturedSql` (for example, in a project that uses TestNG, or for development-time debugging outside of any test framework), `storm-test` works without JUnit on the classpath. The JUnit-specific classes simply remain unused.

---

## JUnit 5 Integration

### @StormTest

The `@StormTest` annotation activates the Storm JUnit 5 extension on a test class. It creates an in-memory H2 database, optionally executes SQL scripts, and injects test method parameters automatically.

A minimal example:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class UserRepositoryTest {

    @Test
    fun `should find all users`(orm: ORMTemplate) {
        val users = orm.entity(User::class).findAll()
        users.size shouldBe 3
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@StormTest(scripts = {"/schema.sql", "/data.sql"})
class UserRepositoryTest {

    @Test
    void shouldFindAllUsers(ORMTemplate orm) {
        var users = orm.entity(User.class).findAll();
        assertEquals(3, users.size());
    }
}
```

</TabItem>
</Tabs>

The annotation accepts the following attributes:

| Attribute  | Default                         | Description                                                                               |
|------------|---------------------------------|-------------------------------------------------------------------------------------------|
| `scripts`  | `{}`                            | Classpath SQL scripts to execute before tests run. Executed once per test class.           |
| `url`      | `""`                            | JDBC URL. Defaults to an H2 in-memory database with a unique name derived from the class. Ignored when a static `dataSource()` factory method is present (see [DataSource Factory Method](#datasource-factory-method)). |
| `username` | `"sa"`                          | Database username. Ignored when a static `dataSource()` factory method is present.        |
| `password` | `""`                            | Database password. Ignored when a static `dataSource()` factory method is present.        |

### Parameter Injection

Test methods can declare parameters of the following types, and Storm will resolve them automatically:

| Parameter type     | What is injected                                                                |
|--------------------|---------------------------------------------------------------------------------|
| `DataSource`       | The test database connection.                                                   |
| `SqlCapture` | A fresh capture instance for recording SQL statements (see below).              |
| Any type with a static `of(DataSource)` factory method | An instance created via that factory method. This covers `ORMTemplate` and custom types that follow the same pattern. |

The factory method resolution also supports Kotlin companion objects. If a class has a `Companion` field with an `of(DataSource)` method, Storm will use it. This means `ORMTemplate` works seamlessly in both Kotlin and Java tests without any additional configuration.

### Example: Full Test Class

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class ItemRepositoryTest {

    @Test
    fun `should insert and retrieve`(orm: ORMTemplate) {
        orm.entity(Item::class).insert(Item(name = "NewItem"))

        val items = orm.entity(Item::class).findAll()
        items.size shouldBe 4
    }

    @Test
    fun `should inject data source`(dataSource: DataSource) {
        dataSource.connection.use { conn ->
            conn.createStatement().use { stmt ->
                stmt.executeQuery("SELECT COUNT(*) FROM item").use { rs ->
                    rs.next() shouldBe true
                    rs.getInt(1) shouldBe 3
                }
            }
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Item(@PK Integer id, String name) implements Entity<Integer> {}

@StormTest(scripts = {"/schema.sql", "/data.sql"})
class ItemRepositoryTest {

    @Test
    void shouldInsertAndRetrieve(ORMTemplate orm) {
        orm.entity(Item.class).insert(new Item(0, "NewItem"));

        var items = orm.entity(Item.class).findAll();
        assertEquals(4, items.size());
    }

    @Test
    void shouldInjectDataSource(DataSource dataSource) throws Exception {
        try (var conn = dataSource.getConnection();
             var stmt = conn.createStatement();
             var rs = stmt.executeQuery("SELECT COUNT(*) FROM item")) {
            assertTrue(rs.next());
            assertTrue(rs.getInt(1) >= 3);
        }
    }
}
```

</TabItem>
</Tabs>

### Using a Custom Database

By default, `@StormTest` creates an H2 in-memory database. This works well for dialect-agnostic logic, but H2 has its own SQL dialect. If your schema scripts or queries use database-specific syntax (for example, PostgreSQL's `SERIAL` type, MySQL's `AUTO_INCREMENT`, or Oracle's sequence syntax), they will not run against H2. In these cases, you need to test against the actual target database.

To point `@StormTest` at a different database, specify a JDBC URL. Storm auto-detects the correct `SqlDialect` from the URL:

```java
@StormTest(
    url = "jdbc:postgresql://localhost:5432/testdb",
    username = "testuser",
    password = "testpass",
    scripts = {"/schema.sql", "/data.sql"}
)
class PostgresTest {
    // ...
}
```

This requires a running database instance at the given URL. For local development you can start one manually (the dialect modules include `docker-compose.yml` files as a reference), but for automated and CI testing, [Testcontainers](https://testcontainers.com/) is the recommended approach. Testcontainers starts a disposable Docker container before the test and tears it down afterwards, so tests remain self-contained and reproducible.

### DataSource Factory Method

Since `@StormTest` takes its URL as a compile-time annotation attribute, it cannot receive the dynamic URL that Testcontainers assigns at runtime. To solve this, define a static `dataSource()` method on the test class. When `StormExtension` finds this method, it uses the returned `DataSource` instead of creating one from the annotation's `url`, `username`, and `password` attributes. SQL scripts still execute against the returned `DataSource`, and all parameter injection (including `ORMTemplate`, `SqlCapture`, and `DataSource`) works as usual.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["/schema-postgres.sql", "/data.sql"])
@Testcontainers
class PostgresTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:latest")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test")

        @JvmStatic
        fun dataSource(): DataSource {
            val dataSource = PGSimpleDataSource()
            dataSource.setUrl(postgres.jdbcUrl)
            dataSource.user = postgres.username
            dataSource.password = postgres.password
            return dataSource
        }
    }

    @Test
    fun `should use PostgreSQL dialect`(orm: ORMTemplate) {
        // orm is connected to the Testcontainers PostgreSQL instance,
        // scripts have been executed, and parameter injection works as usual.
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@StormTest(scripts = {"/schema-postgres.sql", "/data.sql"})
@Testcontainers
class PostgresTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:latest")
            .withDatabaseName("test")
            .withUsername("test")
            .withPassword("test");

    static DataSource dataSource() {
        var dataSource = new PGSimpleDataSource();
        dataSource.setUrl(postgres.getJdbcUrl());
        dataSource.setUser(postgres.getUsername());
        dataSource.setPassword(postgres.getPassword());
        return dataSource;
    }

    @Test
    void shouldUsePostgreSQLDialect(ORMTemplate orm) {
        // orm is connected to the Testcontainers PostgreSQL instance,
        // scripts have been executed, and parameter injection works as usual.
    }
}
```

</TabItem>
</Tabs>

The factory method must be static, take no arguments, and return a `DataSource`. Kotlin companion object methods are also supported.

---

## Statement Capture

When testing database code, knowing _what_ SQL is executed is often as important as knowing _whether_ the operation succeeded. A test might pass because the correct rows were returned, but the underlying query could be inefficient, missing a filter, or using unexpected parameters. `SqlCapture` gives you visibility into the SQL that Storm generates, so you can write assertions not just on results, but on the queries themselves.

`SqlCapture` records every SQL statement generated during a block of code, along with its operation type (`SELECT`, `INSERT`, `UPDATE`, `DELETE`) and bound parameter values. It provides a high-level API designed for test assertions: count statements, filter by operation type, and inspect individual queries.

`SqlCapture` is framework-agnostic. It does not depend on JUnit and can be used with any test framework, or even outside of tests entirely (for example, in development-time debugging or diagnostics).

### Use Cases

**Verifying query counts.** After refactoring a repository method or changing entity relationships, you want to confirm that the number of SQL statements has not changed unexpectedly. A simple count assertion catches regressions early.

**Asserting operation types.** When testing a service method that should only read data, you can assert that no `INSERT`, `UPDATE`, or `DELETE` statements were generated. This is a lightweight way to verify that read-only operations remain read-only.

**Inspecting SQL structure.** For custom queries or complex filter logic, you may want to verify that the generated SQL contains specific clauses (such as a `WHERE` condition or a `JOIN`) or that the correct parameters were bound. This is especially useful when testing query builder logic that constructs dynamic predicates.

**Debugging during development.** When a query does not return the expected results, wrapping the operation in a `SqlCapture` block lets you print the exact SQL and parameters without configuring logging or attaching a debugger.

### Basic Usage

Wrap any Storm operation in a `run`, `execute`, or `executeThrowing` call to capture the SQL statements it generates:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val capture = SqlCapture()

capture.run { orm.entity(User::class).findAll() }

capture.count(Operation.SELECT) shouldBe 1
```

</TabItem>
<TabItem value="java" label="Java">

```java
var capture = new SqlCapture();

capture.run(() -> orm.entity(User.class).findAll());

assertEquals(1, capture.count(Operation.SELECT));
```

</TabItem>
</Tabs>

The `execute` variant returns the result of the captured operation, so you can combine capture with normal test assertions in a single step:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val capture = SqlCapture()

val users = capture.execute { orm.entity(User::class).findAll() }

users.size shouldBe 3
capture.count(Operation.SELECT) shouldBe 1
```

</TabItem>
<TabItem value="java" label="Java">

```java
var capture = new SqlCapture();

List<User> users = capture.execute(() -> orm.entity(User.class).findAll());

assertEquals(3, users.size());
assertEquals(1, capture.count(Operation.SELECT));
```

</TabItem>
</Tabs>

### Capture Methods

| Method              | Description                                                             |
|---------------------|-------------------------------------------------------------------------|
| `run(Runnable)`     | Captures SQL during the action. Returns nothing.                        |
| `execute(Supplier)` | Captures SQL during the action. Returns the action's result.            |
| `executeThrowing(Callable)` | Same as `execute`, but allows checked exceptions.               |

All three methods are scoped: only SQL statements generated within the block are recorded. Code running before or after the block, or on other threads, is not affected.

### Inspecting Captured Statements

Each captured statement is represented as a `CapturedSql` record with three fields:

| Field        | Type              | Description                                                                     |
|--------------|-------------------|---------------------------------------------------------------------------------|
| `operation`  | `Operation`       | The SQL operation type: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, or `UNDEFINED`. |
| `statement`  | `String`          | The SQL text with `?` placeholders for bind variables.                          |
| `parameters` | `List<Object>`    | The bound parameter values in order.                                            |

Query the capture results using `count()`, `statements()`, or their filtered variants:

```java
// Total statement count
int total = capture.count();

// Count by operation type
int selects = capture.count(Operation.SELECT);
int inserts = capture.count(Operation.INSERT);

// Get all captured statements
List<CapturedSql> all = capture.statements();

// Filter by operation type
List<CapturedSql> selectStmts = capture.statements(Operation.SELECT);

// Inspect a specific statement
CapturedSql stmt = selectStmts.getFirst();
String sql = stmt.statement();          // SQL with ? placeholders
List<Object> params = stmt.parameters(); // Bound parameter values
Operation op = stmt.operation();         // SELECT, INSERT, UPDATE, DELETE, or UNDEFINED
```

### Accumulation and Clearing

Statements accumulate across multiple `run`/`execute` calls on the same `SqlCapture` instance. This is useful when you want to measure the total SQL activity of a sequence of operations. Use `clear()` to reset between captures when you need to measure operations independently:

```java
capture.run(() -> orm.entity(User.class).findAll());
capture.run(() -> orm.entity(User.class).findAll());
assertEquals(2, capture.count(Operation.SELECT));

capture.clear();
assertEquals(0, capture.count());
```

### Verifying Query Counts

A count assertion is the simplest and most common use of `SqlCapture`. It protects against regressions where a code change inadvertently introduces extra queries:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Test
fun `bulk insert should use single statement`(orm: ORMTemplate, capture: SqlCapture) {
    val items = listOf(Item(name = "A"), Item(name = "B"), Item(name = "C"))
    capture.run { orm.entity(Item::class).insertAll(items) }

    capture.count(Operation.INSERT) shouldBe 1
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Test
void bulkInsertShouldUseSingleStatement(ORMTemplate orm, SqlCapture capture) {
    var items = List.of(new Item(0, "A"), new Item(0, "B"), new Item(0, "C"));
    capture.run(() -> orm.entity(Item.class).insertAll(items));

    assertEquals(1, capture.count(Operation.INSERT));
}
```

</TabItem>
</Tabs>

### Verifying Statement Content

For finer-grained assertions, inspect the SQL text and bound parameters of individual statements. This is useful when testing custom query logic to ensure the correct filters and parameters are applied:

```java
@Test
void findByIdShouldUseWhereClause(ORMTemplate orm, SqlCapture capture) {
    capture.run(() -> orm.entity(User.class).findById(42));

    var stmts = capture.statements(Operation.SELECT);
    assertEquals(1, stmts.size());
    assertTrue(stmts.getFirst().statement().toUpperCase().contains("WHERE"));
    assertEquals(List.of(42), stmts.getFirst().parameters());
}
```

### Asserting Read-Only Behavior

When a service method should only read data, you can verify that no write operations were generated:

```java
@Test
void reportGenerationShouldBeReadOnly(ORMTemplate orm, SqlCapture capture) {
    capture.run(() -> generateReport(orm));

    assertEquals(0, capture.count(Operation.INSERT));
    assertEquals(0, capture.count(Operation.UPDATE));
    assertEquals(0, capture.count(Operation.DELETE));
}
```

---

## With JUnit 5 Parameter Injection

When using `@StormTest`, a fresh `SqlCapture` instance is automatically injected into each test method that declares it as a parameter. This means you do not need to create one manually, and each test starts with a clean slate:

```java
@StormTest(scripts = {"/schema.sql", "/data.sql"})
class QueryCountTest {

    @Test
    void insertShouldGenerateOneStatement(ORMTemplate orm, SqlCapture capture) {
        capture.run(() -> orm.entity(Item.class).insert(new Item(0, "Test")));
        assertEquals(1, capture.count(Operation.INSERT));
    }

    @Test
    void eachTestGetsAFreshCapture(SqlCapture capture) {
        // No statements from previous tests
        assertEquals(0, capture.count());
    }
}
```

---

## Tips

1. **Keep SQL scripts small and focused.** Each test class should set up only the tables and data it needs. This keeps tests fast and independent.
2. **Use `SqlCapture` to verify query counts.** Asserting the number of statements an operation produces is an effective way to catch unintended query changes during refactoring.
3. **Clear between captures** when a single test method needs to measure multiple operations independently.
4. **Prefer `@StormTest` over manual setup.** It eliminates boilerplate and ensures consistent database lifecycle management across test classes.
5. **`SqlCapture` is thread-local.** Captures are bound to the calling thread, so multi-threaded tests will only record statements from the thread that called `run`/`execute`.
