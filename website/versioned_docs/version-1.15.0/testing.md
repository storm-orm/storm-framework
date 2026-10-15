import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Testing

Writing tests for database code can involve repetitive setup: creating a `DataSource`, running schema scripts, obtaining an `ORMTemplate`, and wiring everything together before the first assertion. Storm's test support module reduces this to a single annotation, letting you focus on the behavior you are testing rather than infrastructure.

The module provides two categories of functionality:

1. **JUnit 5 integration** (`@StormTest`) for automatic database setup, script execution, and parameter injection.

Spring Boot applications additionally have the [`@DataStormTest` slice](spring-integration.md#testing-with-datastormtest), which boots the Storm part of the Spring context instead of bypassing it: repositories arrive as Spring beans, exceptions arrive translated, and each test rolls back a Spring-managed transaction. `@StormTest` stays the fastest option for query-level tests; the slice covers the Spring wiring.
2. **Statement capture** (`SqlCapture`) for recording and inspecting SQL statements generated during test execution. This component is framework-agnostic and works independently of JUnit.

---

## Installation

Add `storm-test` as a test dependency.

**Gradle (Kotlin DSL):**

```kotlin
testImplementation("st.orm:storm-test")
```

**Maven:**

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-test</artifactId>
    <scope>test</scope>
</dependency>
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
        val users = orm.entity<User>().findAll()
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
| `database` | `H2`                            | The database to run on. Every value other than `H2` runs the tests in a Testcontainers-managed container of that database (see [Testing Against the Database You Deploy On](#testing-against-the-database-you-deploy-on)). |
| `image`    | `""`                            | The Docker image for `database`, including its tag. Defaults to a pinned version per database. |
| `url`      | `""`                            | JDBC URL. Defaults to an H2 in-memory database with a unique name derived from the class. Ignored when a static `dataSource()` factory method is present (see [DataSource Factory Method](#datasource-factory-method)). |
| `username` | `"sa"`                          | Database username. Ignored when a static `dataSource()` factory method is present.        |
| `password` | `""`                            | Database password. Ignored when a static `dataSource()` factory method is present.        |
| `rollback` | `true`                          | Whether each test runs inside a transaction that is rolled back afterwards (see [Per-Test Rollback](#per-test-rollback)). |

### Parameter Injection

Test methods can declare parameters of the following types, and Storm will resolve them automatically:

| Parameter type     | What is injected                                                                |
|--------------------|---------------------------------------------------------------------------------|
| `DataSource`       | The test database connection.                                                   |
| `SqlCapture` | A fresh capture instance for recording SQL statements (see below).              |
| Any type with a static `of(DataSource)` factory method | An instance created via that factory method. This covers `ORMTemplate` and custom types that follow the same pattern. |

The factory method resolution also supports Kotlin companion objects. If a class has a `Companion` field with an `of(DataSource)` method, Storm will use it. This means `ORMTemplate` works seamlessly in both Kotlin and Java tests without any additional configuration.

### Per-Test Rollback

Each test runs inside a database transaction that is rolled back when the test completes. Tests never observe each other's writes: every test starts from exactly the state the scripts created, regardless of execution order, so count assertions can be exact instead of defensive.

All connections handed out during a test share that transaction, whether obtained from the injected `DataSource` directly or through an `ORMTemplate` created from it. Storm's transaction API works as usual inside a test: transaction blocks are demarcated with savepoints, so a `transaction { }` block commits and rolls back normally within the test, while everything is still undone when the test completes. Lifecycle methods follow the scope they run in: `@BeforeEach` and `@AfterEach` methods run inside the test transaction, and setup done in a `@BeforeAll` method commits, like the scripts.

Set `rollback = false` for tests that need real commit semantics:

```java
@StormTest(scripts = {"/schema.sql", "/data.sql"}, rollback = false)
class TransactionBehaviorTest {
    // ...
}
```

Typical reasons to opt out:

1. **Writes that must be visible to other connections or threads.** With rollback enabled, everything a test does shares a single database connection, so a test that coordinates multiple concurrent transactions needs real connections.
2. **`REQUIRES_NEW` transactions whose independence is under test.** On the shared connection, an inner transaction nests in the test transaction instead of committing or locking independently.
3. **DDL statements inside a test.** Most databases commit implicitly on DDL, which ends the test transaction. Schema scripts are unaffected: they run once per class, before any test transaction starts.

With rollback disabled, writes persist across the tests of the class, so such tests must not depend on execution order or must clean up after themselves.

### Example: Full Test Class

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class ItemRepositoryTest {

    @Test
    fun `should insert and retrieve`(orm: ORMTemplate) {
        orm.entity<Item>().insert(Item(name = "NewItem"))

        val items = orm.entity<Item>().findAll()
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
            assertEquals(3, rs.getInt(1));
        }
    }
}
```

</TabItem>
</Tabs>

### Testing Against the Database You Deploy On

By default, `@StormTest` creates an H2 in-memory database. H2 is convenient, but it is also the most permissive dialect Storm supports: it accepts syntax the target database rejects and hides the differences that matter in production, such as upsert paths, sequence discovery, identity handling and keyword escaping. A green H2 suite is not evidence that the application works on PostgreSQL. The `database` attribute runs the same test on the database you deploy on, in a Docker container that [Testcontainers](https://testcontainers.com/) manages:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(database = TestDatabase.POSTGRESQL, scripts = ["/schema.sql", "/data.sql"])
class VisitRepositoryTest {

    @Test
    fun `finds visits by pet`(orm: ORMTemplate) {
        // running against a real PostgreSQL
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@StormTest(database = POSTGRESQL, scripts = {"/schema.sql", "/data.sql"})
class VisitRepositoryTest {

    @Test
    void findsVisitsByPet(ORMTemplate orm) {
        // running against a real PostgreSQL
    }
}
```

</TabItem>
</Tabs>

Nothing else in the test changes: scripts, parameter injection, `SqlCapture` and per-test rollback all work as they do on H2. Storm resolves the dialect from the connection, so the dialect module of the database (`storm-postgresql` and so on) applies as it does in the application. The constants live in `st.orm.test.TestDatabase`; the Java example imports `POSTGRESQL` statically. Spring Boot applications have the same attribute on the [`@DataStormTest` slice](spring-integration.md#testing-with-datastormtest), and both annotations share their containers within a JVM.

#### Dependencies

Testcontainers is not a dependency of `storm-test`, so tests on H2 pull in nothing new. A test that names a container database needs the Testcontainers module for that database and its JDBC driver on the test classpath; when either is missing, the test fails with a message naming the artifact to add rather than a `NoClassDefFoundError`.

| `database`     | Testcontainers module                          | JDBC driver                            | Default image                                |
|----------------|------------------------------------------------|----------------------------------------|----------------------------------------------|
| `POSTGRESQL`   | `org.testcontainers:testcontainers-postgresql` | `org.postgresql:postgresql`            | `postgres:17`                                |
| `MYSQL`        | `org.testcontainers:testcontainers-mysql`      | `com.mysql:mysql-connector-j`          | `mysql:8.4`                                  |
| `MARIADB`      | `org.testcontainers:testcontainers-mariadb`    | `org.mariadb.jdbc:mariadb-java-client` | `mariadb:11.8`                               |
| `MSSQL_SERVER` | `org.testcontainers:testcontainers-mssqlserver`| `com.microsoft.sqlserver:mssql-jdbc`   | `mcr.microsoft.com/mssql/server:2022-latest` |
| `ORACLE`       | `org.testcontainers:testcontainers-oracle-free`| `com.oracle.database.jdbc:ojdbc11`     | `gvenzl/oracle-free:23-slim-faststart`       |

These are the Testcontainers 2 modules (2.0 renamed them with a `testcontainers-` prefix), the generation Storm resolves. The Spring Boot 4 BOM manages Testcontainers 2; without a BOM, add `org.testcontainers:testcontainers-bom` for the version. Spring Boot 3's BOM manages Testcontainers 1, so a Boot 3 application raises it to 2.x: with the Spring dependency-management Gradle plugin or the Maven `spring-boot-starter-parent`, set the `testcontainers.version` property to `2.0.5`; with a plain Gradle platform, add `testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))`, which wins over the older constraint. For PostgreSQL, for example:

**Gradle (Kotlin DSL):**

```kotlin
testImplementation("st.orm:storm-test")
testImplementation("org.testcontainers:testcontainers-postgresql")
testRuntimeOnly("org.postgresql:postgresql")
```

**Maven:**

```xml
<dependency>
    <groupId>org.testcontainers</groupId>
    <artifactId>testcontainers-postgresql</artifactId>
    <scope>test</scope>
</dependency>
<dependency>
    <groupId>org.postgresql</groupId>
    <artifactId>postgresql</artifactId>
    <scope>test</scope>
</dependency>
```

Docker (or a compatible runtime Testcontainers can reach) must be available where the tests run.

#### Choosing the Image

The default images pin the major version of each database and follow its patch releases; none of them is `latest`, so a suite does not change what it runs against when a new major is published. To run on another version, or on another distribution of the database, name the image on the annotation. Any image that is a distribution of the chosen database works, such as `postgres:16`, a fully pinned `postgres:17.11`, or `pgvector/pgvector:pg17` for PostgreSQL:

```java
@StormTest(database = POSTGRESQL, image = "postgres:16", scripts = {"/schema.sql", "/data.sql"})
class VisitRepositoryPostgres16Test {
    // ...
}
```

Two databases need a word of their own:

- **SQL Server** requires accepting Microsoft's license terms. Testcontainers reads the acceptance from a `container-license-acceptance.txt` file on the test classpath (`src/test/resources`) that lists the image, including its tag, on a line of its own; the container refuses to start without it, naming the file and the image:
  ```
  mcr.microsoft.com/mssql/server:2022-latest
  ```
- **Oracle** runs from the `gvenzl/oracle-free` image (Oracle Database Free 23). The test user receives the same grants the image gives its own application user, so a test is no more and no less privileged than a hand-written container setup.

#### One Container per Run, One Database per Class

The container is started once per JVM for a given database and image, on the first test class that asks for it, and shared by every test class of the run that asks for the same one; Testcontainers removes it when the JVM exits. Sharing is safe because no two classes share a database inside the container: each test class receives a freshly created database, created before its scripts run and dropped when the class completes. On PostgreSQL, MySQL, MariaDB and SQL Server that is a new catalog; on Oracle a new user with its own schema. Scripts therefore execute against an empty database exactly as they do on H2, without drop guards, and test classes never observe each other's tables or rows. Creating a database inside a running container takes a fraction of the time the container start takes, so the container cost is paid once per run rather than once per class.

The `database` attribute is mutually exclusive with `url` and with a static `dataSource()` factory method (see below), which both point at a database of your own; combining them fails at startup with a message naming the conflict.

#### Outside the Annotation

The container is available directly as well, for setups the annotation does not cover, such as a `@BeforeAll` in another framework: `TestDatabase.POSTGRESQL.container()` starts it on first use and returns the shared `DatabaseContainer`, and `createDatabase()` on it provisions a database of your own, with `url()`, `username()`, `password()` and `dataSource()`, dropped when closed:

```java
try (var database = TestDatabase.POSTGRESQL.container().createDatabase()) {
    ORMTemplate orm = ORMTemplate.of(database.dataSource());
    // ...
}
```

### Pointing at Your Own Database

To run against a database you manage yourself, such as a shared development instance, specify its JDBC URL. Storm auto-detects the correct `SqlDialect` from the connection:

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

This requires a running database instance at the given URL, and the scripts run against it as they are on every class, so they need drop guards or a schema per class. For local development you can start one manually (the dialect modules include `docker-compose.yml` files as a reference); for automated and CI testing, prefer the `database` attribute.

### DataSource Factory Method

To hand `@StormTest` a `DataSource` you construct yourself, for example a container you configure beyond what the `database` attribute offers, define a static `dataSource()` method on the test class. When `StormExtension` finds this method, it uses the returned `DataSource` instead of creating one from the annotation's `url`, `username`, and `password` attributes. SQL scripts still execute against the returned `DataSource`, and all parameter injection (including `ORMTemplate`, `SqlCapture`, and `DataSource`) works as usual.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["/schema-postgres.sql", "/data.sql"])
@Testcontainers
class PostgresTest {

    companion object {
        @Container
        val postgres = PostgreSQLContainer("postgres:17")
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
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:17")
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

Wrap any Storm operation in a `record`, `execute`, or `executeThrowing` call to capture the SQL statements it generates:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val capture = SqlCapture()

capture.record { orm.entity<User>().findAll() }

capture.count(Operation.SELECT) shouldBe 1
```

</TabItem>
<TabItem value="java" label="Java">

```java
var capture = new SqlCapture();

capture.record(() -> orm.entity(User.class).findAll());

assertEquals(1, capture.count(Operation.SELECT));
```

</TabItem>
</Tabs>

The `execute` variant returns the result of the captured operation, so you can combine capture with normal test assertions in a single step:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val capture = SqlCapture()

val users = capture.execute { orm.entity<User>().findAll() }

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
| `record(Runnable)`  | Captures SQL during the action. Returns nothing.                        |
| `execute(Supplier)` | Captures SQL during the action. Returns the action's result.            |
| `executeThrowing(Callable)` | Same as `execute`, but allows checked exceptions.               |
| `attach()`          | Attaches until the returned handle is closed, for brackets a callable cannot wrap, such as a test lifecycle. |

These methods are scoped: only SQL statements generated within the block are recorded. Code running before or after the block is not affected. The capture binds to the thread that runs the action and to the contexts Storm carries it into, such as a `transaction { }` block; work handed to another thread outside those contexts falls outside the capture.

### Capturing Across Coroutines (Kotlin)

A capture opened by one of the blocking entry points stops recording at the first suspension that resumes on another thread. Kotlin coroutine code records through the suspending `recording` extension of the `storm-kotlin-test` module, which follows the coroutine across the threads it resumes on, and covers the coroutines launched within the block:

```kotlin
capture.recording {
    users.insert(User(email = "alice@example.com"))
    withContext(Dispatchers.IO) { users.findAll() }
}
capture.count(Operation.SELECT) shouldBe 1
```

```xml
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-kotlin-test</artifactId>
    <version>@@STORM_VERSION@@</version>
    <scope>test</scope>
</dependency>
```

The suspending entry point carries its own name: Kotlin resolves a call against a member on the lambda's shape alone, so a member named `record` would win the call and reject a suspending block rather than let it reach the extension. A suspending block inside `record { }` therefore fails to compile, pointing at `recording { }`; neither can silently resolve to the `kotlin.run` scope function the way the pre-1.14 `run` entry point could.

### Inspecting Captured Statements

Each captured statement is represented as a `CapturedSql` record with seven fields:

| Field        | Type              | Description                                                                     |
|--------------|-------------------|---------------------------------------------------------------------------------|
| `operation`  | `Operation`       | The SQL operation type: `SELECT`, `INSERT`, `UPDATE`, `DELETE`, or `UNDEFINED`. |
| `statement`  | `String`          | The SQL text with `?` placeholders for bind variables.                          |
| `parameters` | `List<Object>`    | The bound parameter values in order.                                            |
| `origin`     | `Origin`          | What caused the statement: `DIRECT`, or `FETCH` for a statement resolving a reference. |
| `duration`   | `Duration`        | The time the execution spent in the database, from prepare to the statement's return; for a streamed read this excludes consuming the stream. |
| `rows`       | `long`            | The rows the execution produced or affected; a lower bound when not exact.      |
| `exactRows`  | `boolean`         | Whether that count is exact; `false` when a driver declined to report a batch entry's count or a stream closed before its end. |

Statements are captured around execution, so each carries its duration and a statement that is built but never run is not captured. The duration is the same database time the [SQL log](sql-logging.md#one-point-three-grains) reports, measured the same way.

`origin` is what makes the cost of resolving references assertable. A reference the query did not resolve is selected as its foreign key column and resolved on demand, one statement per reference, and such a statement is shaped exactly like a primary key lookup the test could have written itself. Asserting `count(FETCH) == 0` pins the query down to the shape its fetch plan produces:

```java
List<Owner> owners = capture.execute(() -> orm.entity(Owner.class).select()
        .fetch(Owner_.city)
        .getResultList());
owners.forEach(owner -> owner.city().fetch());
assertEquals(0, capture.count(CapturedSql.Origin.FETCH));
```

Without the `fetch(Owner_.city)` the same assertion fails with one resolution per distinct city, which is the regression such a test exists to catch.

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

Statements accumulate across multiple `record`/`execute` calls on the same `SqlCapture` instance. This is useful when you want to measure the total SQL activity of a sequence of operations. Use `clear()` to reset between captures when you need to measure operations independently:

```java
capture.record(() -> orm.entity(User.class).findAll());
capture.record(() -> orm.entity(User.class).findAll());
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
    capture.record { orm.entity<Item>().insertAll(items) }

    capture.count(Operation.INSERT) shouldBe 1
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Test
void bulkInsertShouldUseSingleStatement(ORMTemplate orm, SqlCapture capture) {
    var items = List.of(new Item(0, "A"), new Item(0, "B"), new Item(0, "C"));
    capture.record(() -> orm.entity(Item.class).insertAll(items));

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
    capture.record(() -> orm.entity(User.class).findById(42));

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
    capture.record(() -> generateReport(orm));

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
        capture.record(() -> orm.entity(Item.class).insert(new Item(0, "Test")));
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

## Ktor Testing

The `storm-ktor-test` module provides a `testStormApplication` function that combines Storm's H2 setup with Ktor's `testApplication` builder. It creates an in-memory database, executes SQL scripts, and exposes a `StormTestScope` with `stormDataSource`, `stormOrm`, and `stormSqlCapture`. The scope's capture is installed around every call the application handles, so a request's statements are captured wherever its handler runs, with no wrapping at the call site.

```kotlin
@Test
fun `GET users returns list`() = testStormApplication(
    scripts = listOf("/schema.sql", "/data.sql"),
) { scope ->
    application {
        install(Storm) { dataSource = scope.stormDataSource }
        routing { userRoutes() }
    }

    client.get("/users").apply {
        assertEquals(HttpStatusCode.OK, status)
    }
}
```

You can also combine the existing `@StormTest` annotation with Ktor's `testApplication` for a more concise setup:

```kotlin
@StormTest(scripts = ["/schema.sql", "/data.sql"])
class UserRouteTest {

    @Test
    fun `users endpoint returns data`(dataSource: DataSource) = testApplication {
        application {
            install(Storm) { this.dataSource = dataSource }
            routing { userRoutes() }
        }
        client.get("/users").apply {
            assertEquals(HttpStatusCode.OK, status)
        }
    }
}
```

See [Ktor Integration](ktor-integration.md#testing) for more details.

---

## Tips

1. **Keep SQL scripts small and focused.** Each test class should set up only the tables and data it needs. This keeps tests fast and independent.
2. **Use `SqlCapture` to verify query counts.** Asserting the number of statements an operation produces is an effective way to catch unintended query changes during refactoring.
3. **Clear between captures** when a single test method needs to measure multiple operations independently.
4. **Prefer `@StormTest` over manual setup.** It eliminates boilerplate and ensures consistent database lifecycle management across test classes.
5. **Run the tests that touch dialect-specific behavior on the database you deploy on.** `database = POSTGRESQL` (or the database you use) costs one container start per run; upserts, sequences, identity handling and keyword escaping are exactly what H2 hides. Keep the bulk of the suite on H2 for speed and put the dialect-sensitive classes on the real database.
6. **`SqlCapture` binds to the thread that runs the action and the contexts Storm carries it into.** Statements executed inside a `transaction { }` block or a coroutine given `sqlLogContext()` are captured wherever they run; work handed to a thread or coroutine without that context falls outside the capture. Coroutine code records through the suspending `recording` extension of `storm-kotlin-test`, which follows the coroutine itself. Recording is safe from any thread the work reaches.
7. **A resolution served from cache issues no statement.** Inside a transaction the entity cache answers repeat resolutions of the same record, so `count(FETCH)` counts distinct cache misses rather than `fetch()` call sites.
