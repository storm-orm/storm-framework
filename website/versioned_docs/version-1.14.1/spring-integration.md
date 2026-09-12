import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Spring Integration

Storm integrates with Spring Framework and Spring Boot for dependency injection, transaction management, and repository auto-wiring. This guide covers setup for both languages.

## Installation

Storm provides Spring Boot Starter modules that auto-configure everything you need. If you use the starter, you do not need to add `storm-kotlin-spring` or `storm-spring` separately; the starter includes them.

### Spring Boot Starter (Recommended)

The starter modules provide zero-configuration setup: an `ORMTemplate` bean is created automatically from the `DataSource`, repositories are discovered from the application's base package, and (for Kotlin) transaction integration is enabled. See [Spring Boot Starter](#spring-boot-starter) for full details.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-kotlin-spring-boot-starter:@@STORM_VERSION@@")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-kotlin-spring-boot-starter</artifactId>
    <version>@@STORM_VERSION@@</version>
</dependency>
```

</TabItem>
<TabItem value="java" label="Java">

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-spring-boot-starter:@@STORM_VERSION@@")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring-boot-starter</artifactId>
    <version>@@STORM_VERSION@@</version>
</dependency>
```

</TabItem>
</Tabs>

### Spring Integration Without Auto-Configuration

If you prefer manual configuration, or need to customize the setup beyond what the starter provides, use the integration modules directly:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-kotlin-spring:@@STORM_VERSION@@")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-kotlin-spring</artifactId>
    <version>@@STORM_VERSION@@</version>
</dependency>
```

</TabItem>
<TabItem value="java" label="Java">

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-spring:@@STORM_VERSION@@")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring</artifactId>
    <version>@@STORM_VERSION@@</version>
</dependency>
```

</TabItem>
</Tabs>

The Spring integration modules provide transaction integration and repository auto-discovery. They are in addition to the base `storm-kotlin` or `storm-java21` dependency.

---

## Configuration

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

The minimum setup requires a single `ORMTemplate` bean. This bean is the entry point for all Storm operations and takes a standard `DataSource` as its only dependency. Spring Boot applications typically have a `DataSource` already configured through `application.properties`, so the `ORMTemplate` bean is the only Storm-specific configuration you need to add.

```kotlin
@Configuration
@EnableTransactionManagement
class ORMConfiguration(private val dataSource: DataSource) {

    @Bean
    fun ormTemplate(): ORMTemplate = dataSource.orm
}
```

### Transaction Integration

A template created with `dataSource.orm` manages its own transactions, independently of Spring's transaction context: a transaction block inside a `@Transactional` method would open a separate database connection and transaction. To wire a template to Spring's transaction management instead, compose it with `springOrmTemplate` (from `st.orm.spring.kotlin`), which hands the Spring-aware connection and transaction providers to that specific template. Since 1.13 the integration is per template rather than JVM-global, so multiple application contexts, and plain templates next to Spring-managed ones, coexist without interference.

```kotlin
@Configuration
@EnableTransactionManagement
class ORMConfiguration {

    @Bean
    fun ormTemplate(
        dataSource: DataSource,
        transactionManagers: ObjectProvider<PlatformTransactionManager>,
    ): ORMTemplate = springOrmTemplate(dataSource) { transactionManagers.orderedStream().toList() }
}
```

This allows combining Spring's `@Transactional` with Storm's programmatic `transaction` blocks:

```kotlin
@Transactional
fun processUsers() {
    // Spring manages outer transaction

    transactionBlocking {
        // Participates in Spring transaction
        orm.removeAll<Visit>()
    }
}
```

### Repository Injection

Storm repositories are interfaces with default method implementations. Spring cannot discover them automatically because they are not annotated with `@Component` or `@Repository`. Enable scanning with `@EnableStormRepositories`, mirroring annotations like `@EnableJpaRepositories`: the given packages are scanned for interfaces that extend `EntityRepository` or `ProjectionRepository`, and each is registered as a Spring bean, available for constructor injection like any other Spring-managed dependency.

```kotlin
@Configuration
@EnableStormRepositories(basePackages = ["com.acme.repository"])
class AcmeConfiguration
```

Without explicit packages, the package of the annotated class is scanned. For full control, or for multiple repository sets bound to different templates, define `RepositoryBeanFactoryPostProcessor` beans (from `st.orm.spring.kotlin`) instead:

```kotlin
@Configuration
class AcmeRepositoryConfiguration {

    @Bean
    fun acmeRepositories() = RepositoryBeanFactoryPostProcessor(
        basePackages = arrayOf("com.acme.repository"),
    )
}
```

Define repositories:

```kotlin
interface UserRepository : EntityRepository<User, Int> {

    fun findByEmail(email: String): User? =
        find(User_.email eq email)
}
```

Inject into services:

```kotlin
@Service
class UserService(
    private val userRepository: UserRepository
) {
    fun findUser(email: String): User? =
        userRepository.findByEmail(email)
}
```

### Using @Transactional

```kotlin
@Service
class UserService(
    private val orm: ORMTemplate
) {

    @Transactional
    fun createUser(email: String, name: String): User {
        return orm insert User(email = email, name = name)
    }

    @Transactional(readOnly = true)
    fun findUsers(): List<User> {
        return orm.findAll<User>()
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

The configuration mirrors the Kotlin setup. Define a single `ORMTemplate` bean; `SpringOrmTemplate.of` wires it to Spring's transaction management, so Storm's programmatic `transaction` blocks run through Spring's transaction managers and cooperate with `@Transactional`.

```java
@Configuration
@EnableTransactionManagement
public class ORMConfiguration {

    @Bean
    public ORMTemplate ormTemplate(DataSource dataSource,
                                   ObjectProvider<PlatformTransactionManager> transactionManagers) {
        return SpringOrmTemplate.of(dataSource, () -> transactionManagers.orderedStream().toList());
    }
}
```

A template created with `ORMTemplate.of(dataSource)` works too, but manages its own transactions independently of Spring's transaction context.

### Repository Injection

Enable repository scanning with `@EnableStormRepositories`. This works identically to the Kotlin version: Storm discovers interfaces extending `EntityRepository` or `ProjectionRepository` and registers them as Spring beans.

```java
@Configuration
@EnableStormRepositories(basePackages = "com.acme.repository")
public class AcmeConfiguration {
}
```

For full control, or for multiple repository sets bound to different templates, define `RepositoryBeanFactoryPostProcessor` beans instead:

```java
@Configuration
public class AcmeRepositoryConfiguration {

    @Bean
    public RepositoryBeanFactoryPostProcessor acmeRepositories() {
        return new RepositoryBeanFactoryPostProcessor(
                new String[] { "com.acme.repository" }, null, "");
    }
}
```

Define repositories:

```java
public interface UserRepository extends EntityRepository<User, Integer> {

    default Optional<User> findByEmail(String email) {
        return select()
            .where(User_.email, EQUALS, email)
            .getOptionalResult();
    }
}
```

Inject into services:

```java
@Service
public class UserService {

    private final UserRepository userRepository;

    public UserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public Optional<User> findUser(String email) {
        return userRepository.findByEmail(email);
    }
}
```

### Using @Transactional

```java
@Service
public class UserService {

    private final ORMTemplate orm;

    public UserService(ORMTemplate orm) {
        this.orm = orm;
    }

    @Transactional
    public User createUser(String email, String name) {
        return orm.entity(User.class)
            .insertAndFetch(new User(null, email, name, null, null));
    }

    @Transactional(readOnly = true)
    public List<User> findUsers() {
        return orm.entity(User.class)
            .select()
            .getResultList();
    }
}
```

</TabItem>
</Tabs>

---

## Production DataSource Configuration

Storm works with any JDBC `DataSource` and does not manage connections itself. In production, you should configure a connection pool to handle connection lifecycle, validation, and recycling. HikariCP is the default connection pool in Spring Boot and a good choice for most applications.

### Adding HikariCP

Spring Boot includes HikariCP by default when you add a `spring-boot-starter-jdbc` or `spring-boot-starter-data-jpa` dependency. If you are not using a starter that includes it, add HikariCP explicitly:

```xml
<dependency>
    <groupId>com.zaxxer</groupId>
    <artifactId>HikariCP</artifactId>
</dependency>
```

### Pool Configuration

Configure the pool in `application.yml`. A good starting point for pool size is `CPU cores * 2 + number of disk spindles`. For most cloud deployments with SSDs, this simplifies to roughly `CPU cores * 2`. A 4-core server would start with a pool of about 10 connections.

```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/mydb
    username: myuser
    password: mypassword
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000       # 30 seconds
      idle-timeout: 600000            # 10 minutes
      max-lifetime: 1800000           # 30 minutes
      validation-timeout: 5000        # 5 seconds
      connection-test-query: SELECT 1 # Only needed for drivers that don't support JDBC4 isValid()
```

| Property | Description |
|----------|-------------|
| `maximum-pool-size` | Upper bound on connections. Start with CPU cores * 2 and adjust based on load testing. |
| `minimum-idle` | Minimum idle connections to maintain. Set equal to `maximum-pool-size` for consistent latency. |
| `connection-timeout` | Maximum time (ms) to wait for a connection from the pool before throwing an exception. |
| `idle-timeout` | Maximum time (ms) a connection can sit idle before being retired. |
| `max-lifetime` | Maximum lifetime (ms) of a connection. Set slightly shorter than your database's connection timeout. |
| `connection-test-query` | Validation query for drivers that do not support JDBC4's `isValid()`. Most modern drivers do not need this. |

Storm obtains connections from the `DataSource` for each operation (or transaction) and returns them to the pool immediately afterward. This means connection pool tuning directly affects Storm's throughput and latency characteristics.

---

## Template Decorator

The `TemplateDecorator` interface lets you customize how Storm resolves table names, column names, and foreign key column names. This is useful when your database uses a naming convention that differs from Storm's default camelCase-to-snake_case conversion, or when you need to add a schema prefix or other transformation globally.

The decorator is passed as a `UnaryOperator<TemplateDecorator>` to the `ORMTemplate.of()` factory method. It receives the default decorator and returns a modified version.

### Available Resolvers

| Method | Default Behavior | Use Case |
|--------|------------------|----------|
| `withTableNameResolver` | `CamelCase` to `snake_case` (e.g., `UserProfile` to `user_profile`) | Schema prefix, uppercase tables, custom naming |
| `withColumnNameResolver` | `camelCase` to `snake_case` (e.g., `firstName` to `first_name`) | Uppercase columns, custom naming |
| `withForeignKeyResolver` | `camelCase` to `snake_case` + `_id` suffix (e.g., `city` to `city_id`) | Custom FK naming conventions |

### Example: Uppercase Table and Column Names

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val orm = dataSource.orm { decorator ->
    decorator
        .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
        .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
    .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
);
```

</TabItem>
</Tabs>

### Example: Schema Prefix

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val orm = dataSource.orm { decorator ->
    decorator.withTableNameResolver { type ->
        "myschema." + TableNameResolver.DEFAULT.resolveTableName(type)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(type ->
        "myschema." + TableNameResolver.DEFAULT.resolveTableName(type))
);
```

</TabItem>
</Tabs>

In Spring Boot, apply the decorator when defining your `ORMTemplate` bean. If you use the starter and want to customize the auto-configured template, define your own `ORMTemplate` bean and the starter's auto-configured one will back off:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Configuration
class StormConfig(private val dataSource: DataSource) {

    @Bean
    fun ormTemplate(): ORMTemplate =
        dataSource.orm { decorator ->
            decorator.withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
        }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Configuration
public class StormConfig {

    private final DataSource dataSource;

    public StormConfig(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public ORMTemplate ormTemplate() {
        return ORMTemplate.of(dataSource, decorator -> decorator
            .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT)));
    }
}
```

</TabItem>
</Tabs>

---

## Spring Boot Starter

The Spring Boot Starter modules provide zero-configuration setup for Storm. Add the starter dependency and Storm auto-configures itself from the Spring Boot `DataSource`.

### What the Starter Provides

The starter auto-configures:

1. **`ORMTemplate` bean** created from the auto-configured `DataSource`, composed with the Spring-aware integration strategies below. If you define your own `ORMTemplate` bean, the auto-configured one backs off.
2. **Repository scanning** via `AutoConfiguredRepositoryBeanFactoryPostProcessor`, which discovers repository interfaces in the `@SpringBootApplication` base package (and its sub-packages). If you define your own `RepositoryBeanFactoryPostProcessor` bean, the auto-configured one backs off.
3. **Transaction integration** through Spring-aware `ConnectionProvider` and `TransactionTemplateProvider` beans, contributed when a `PlatformTransactionManager` is present and handed to the template it creates. Nothing is registered globally: each application context gets its own, correctly matched transaction integration. Define your own `ConnectionProvider` or `TransactionTemplateProvider` bean to override, and optionally contribute `ExceptionMapper` or `QueryObserver` beans, which are applied to the template the same way. `ExceptionMapper` and `QueryObserver` live in the `st.orm.spi` package of `storm-foundation`, so implementing them needs no extra dependency; a custom `ConnectionProvider` or `TransactionTemplateProvider` bridges the engine's transaction handling and compiles against `st.orm:storm-core`.
4. **Exception translation** to Spring's `DataAccessException` hierarchy through an auto-configured `ExceptionMapper` bean. Disable with `storm.exception-translation.enabled=false`, or define your own `ExceptionMapper` bean to translate differently.
5. **Query observations** through Micrometer when an `ObservationRegistry` bean is present: every query reports as a `storm.query` observation, yielding actuator metrics and tracing spans from a single instrumentation.
6. **Configuration properties** bound from `storm.*` in `application.yml`/`application.properties`, passed to the `ORMTemplate` via `StormConfig`.

### Minimal Spring Boot Setup (with Starter)

With the starter, a complete Spring Boot application requires no Storm-specific configuration classes:

```kotlin
@SpringBootApplication
class Application

fun main(args: Array<String>) {
    runApplication<Application>(*args)
}
```

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/mydb
spring.datasource.username=myuser
spring.datasource.password=mypassword
```

That is it. The starter creates the `ORMTemplate`, discovers repositories, and enables transaction integration automatically.

### Configuration via application.yml

The starter binds Spring properties and builds a `StormConfig` that is passed to the `ORMTemplate` factory. Values not set in YAML fall back to system properties and then to built-in defaults:

```yaml
storm:
  ansi-escaping: false
  update:
    default-mode: ENTITY
    dirty-check: INSTANCE
    max-shapes: 5
  entity-cache:
    retention: default
  template-cache:
    size: 2048
  validation:
    skip: false
    warnings-only: false
    schema-mode: fail
    strict: false
  exception-translation:
    enabled: true
  observations:
    semantic-conventions: storm
  tracing:
    sql-comments: false
```

The `schema-mode` property controls startup schema validation: `fail` (default) blocks startup if any entity definitions do not match the database schema, `warn` logs mismatches without blocking startup, and `none` skips validation. The `strict` property controls whether warnings (type narrowing, nullability mismatches) are treated as errors. See the [Configuration](configuration.md#schema-validation) guide for details.

See the [Configuration](configuration.md) guide for a description of each property and the full precedence rules.

### Overriding Auto-Configuration

Each auto-configured bean backs off when you provide your own. This lets you customize behavior incrementally.

**Custom ORMTemplate:**

```kotlin
@Configuration
class StormConfig(private val dataSource: DataSource) {

    @Bean
    fun ormTemplate(): ORMTemplate =
        dataSource.orm { decorator -> decorator /* customize */ }
}
```

**Custom repository scanning:**

```kotlin
@Configuration
@EnableStormRepositories(basePackages = ["com.myapp.repository", "com.myapp.other"])
class RepositoryConfiguration
```

### Minimal Spring Boot Setup (without Starter)

If you use the integration module directly (without the starter), you need to configure Storm manually:

```kotlin
@SpringBootApplication
@EnableTransactionManagement
class Application

@Configuration
class StormConfig {

    @Bean
    fun ormTemplate(
        dataSource: DataSource,
        transactionManagers: ObjectProvider<PlatformTransactionManager>,
    ): ORMTemplate = springOrmTemplate(dataSource) { transactionManagers.orderedStream().toList() }
}

@Configuration
@EnableStormRepositories(basePackages = ["com.myapp.repository"])
class RepositoryConfiguration
```

This gives you:
- Automatic DataSource from Spring Boot
- Transaction integration between Spring and Storm
- Repository auto-discovery and injection

## Composing Your Own Templates

The starter auto-configures one template for a single-candidate `DataSource`. An application that defines its
own template beans, which backs that auto-configuration off, injects the starter's `OrmTemplateFactory`
instead and composes each template through it. Every created template carries the same property-driven
integration the auto-configured one does: the Spring-aware connection and transaction providers, SQL failure
translation with the mapper created against the template's own data source, query and transaction
observations with the semantic conventions resolved from the template's own data source, and the
trace-context SQL commenter.

```kotlin
@Configuration
class TemplateConfiguration {

    @Bean
    fun orderTemplate(factory: OrmTemplateFactory, @Qualifier("orders") dataSource: DataSource) =
        factory.create(dataSource, "orders")

    @Bean
    fun billingTemplate(factory: OrmTemplateFactory, @Qualifier("billing") dataSource: DataSource) =
        factory.create(dataSource, "billing") {
            decorator { it.withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT)) }
        }
}
```

The `database` argument names the template in observations: every observation carries it as the
low-cardinality `storm.database` key value, so each template's queries chart under their own identity. The
trailing block applies application-specific composition, such as a template decorator, after the integration
is wired; overriding an integration strategy there remains possible for templates that want custom behavior.
Outside Spring Boot, `springOrmTemplate` remains the plain-Spring composition, wiring the connection and
transaction providers without the Boot property surface.

## Exception Translation

Spring code is written against the `DataAccessException` hierarchy: retry setups key on `TransientDataAccessException` for deadlocks and lock timeouts, rollback rules and catch blocks reference the Spring types, and Spring's other data access integrations all translate consistently. With the starter, Storm participates in that convention out of the box: SQL failures raised by Storm repositories and templates surface as Spring exceptions, translated from vendor error codes for the application's database product, with `SQLException` subclass and SQL state translation as fallback.

```kotlin
try {
    userRepository.insert(user)
} catch (exception: DuplicateKeyException) {
    // Same exception type a JdbcClient or JPA repository would raise.
}
```

Failures without a `SQLException` cause keep Storm's own semantics: `PersistenceException` and its subtypes, such as optimistic locking and result cardinality failures, pass through unchanged. The boundary is the database: what the database reports is translated, what Storm's own logic detects stays a Storm exception in every composition. Retry setups that should also retry optimistic lock failures list both types:

```kotlin
@Retryable(retryFor = [TransientDataAccessException::class, OptimisticLockException::class])
fun updateStudy(study: Study) = transactionBlocking { studyRepository.update(study) }
```

Translation applies to the templates composed with it: the starter's auto-configured template, the templates an `OrmTemplateFactory` creates, and templates created with `SpringOrmTemplate.of` (Java) or `springOrmTemplate` (Kotlin). Disable it with `storm.exception-translation.enabled=false`, define your own `ExceptionMapper` bean, or compose the template yourself with the builder and leave the mapper out.

## Observability

The starters ship with Storm's Micrometer binding. When an `ObservationRegistry` bean is present (Spring Boot Actuator provides one out of the box), every query executed by the auto-configured template is reported as a Micrometer Observation named `storm.query`. One instrumentation yields both actuator metrics (`storm.query` timers, tagged and queryable per operation and entity) and tracing spans when a tracing backend is configured.

A generic DataSource proxy can only time statements. Storm knows the entity and operation behind every statement it generates, so the observations carry meaningful tags:

- **Low-cardinality key values** (become metric tags): the SQL operation (`SELECT`, `INSERT`, ...), the execution kind (query, update, batch), the entity or projection type, the statement origin, and the statement's shape identity (`storm.shape`), which groups executions of one query template whatever their parameters expand to.
- **High-cardinality key values** (visible to trace handlers only): the SQL statement with parameter placeholders. Parameter values are never reported.

`storm.origin` separates statements your code asked for (`DIRECT`) from statements that resolved a reference (`FETCH`). A reference the query did not resolve is selected as its foreign key column and resolved on demand, one statement per reference, and such a statement is shaped exactly like a primary key lookup you could have written yourself. Tagging it `FETCH` makes what resolving references costs a quantity you can chart and alert on rather than a share of all lookups; naming the reference in the query's fetch plan is what drives the rate back to zero. Resolutions served by the transaction's entity cache issue no statement at all, so the rate counts distinct cache misses, not `fetch()` call sites.

Transactions are observed alongside the queries: every physical transaction (an outermost `transaction` block or a `REQUIRES_NEW` block, in any language stack and through the Spring bridge alike) reports as a `storm.transaction` observation with its duration, `storm.tx.outcome` (`committed` or `rolled_back`), `storm.tx.propagation`, and `storm.tx.read_only`. Joined blocks run inside an existing transaction and are deliberately not double-counted. Long-running transactions and rollback rates become queryable per application, and per domain with the multi-template tagging.

Observability backends key their database tooling (latency panels, service-map database nodes, query views) on the OpenTelemetry database client semantic conventions. Set `storm.observations.semantic-conventions: otel` and every observation additionally carries the standard attributes (`db.system.name` detected from the DataSource, `db.operation.name`, and `db.query.text` on spans), so Storm queries surface in the database UX of any OTLP-capable backend, from Elastic to Grafana to Datadog, while the `storm.*` key values remain for custom dashboards:

```yaml
storm:
  observations:
    semantic-conventions: otel
```

Customization follows the usual back-off rules: contribute an `ObservationConvention<StormQueryObservationContext>` bean to override the naming and key values of the query observations (it wins over the property) and an `ObservationConvention<StormTransactionObservationContext>` bean for those of the transaction observations, define your own `QueryObserver` bean to replace the binding entirely, or disable the observations at the registry level with `management.observations.enable.storm.query=false` and `management.observations.enable.storm.transaction=false`.

For development and per-call diagnosis, `storm.sql-log.performance.enabled=true` reports what each unit of work cost the database as one summary (statements, database time against total time, concurrency, and the statement that carried the weight) with thresholds that turn it into a production guardrail. Every way work enters the application is a boundary: HTTP requests through a servlet filter, and `@Scheduled` tasks and message listeners through a proxy around the entry-point method, so a worker without a web layer reports the same way. See [SQL Logging](sql-logging.md#the-performance-log).

For production, `storm.sql-log.slow.threshold=200ms` reports each single execution whose database time exceeds the threshold, under `st.orm.sql.slow` at `WARN`: the statement, its call site, its rows, and how it compares to what its shape typically costs, so the line says whether to look at the parameters or at the query. It needs no boundary and applies whether or not the performance log is enabled; left unset while the performance log runs against `storm.sql-log.performance.threshold.duration`, it takes that duration, so a reported call names the statement behind it. Where the actuator is on the classpath, the `storm` endpoint reads and retunes both the slow threshold and the performance thresholds without a restart. See [The Slow Statement Log](sql-logging.md#the-slow-statement-log).

With tracing in place, `storm.tracing.sql-comments` additionally appends the current trace context to statements as a sqlcommenter-style comment (`/*traceparent='00-…'*/`), so database-side diagnostics such as MariaDB's slow query log, PostgreSQL's `auto_explain` and `pg_stat_activity` correlate directly back to the trace that issued the statement; a slow line prints the same comment, which is what joins Storm's record of a slow execution to the database's plan for it. `true` comments every statement inside a span; `sampled` comments only statements of sampled traces, which is the recommended mode when the sampling probability is below 1.0. This is deliberately opt-in: a per-execution comment changes the statement text on every call, which defeats driver-side and server-side prepared statement caching.

One composition warning for applications that wire the integration beans themselves: define beans like the `ExceptionMapper`, `QueryObserver`, or `SqlCommenter` unconditionally in your `@Configuration` classes. A `@ConditionalOnBean` condition in a user configuration evaluates before auto-configurations contribute their beans (such as the `Tracer`), so it fails silently and the integration stays dormant while looking enabled.

## Testing with @DataStormTest

`@DataStormTest` (from `storm-spring-boot-test-autoconfigure`, test scope) is the Storm test slice, the counterpart of `@DataJpaTest`: it starts only the `DataSource`, Storm's auto-configuration (template, repository scanning and proxying, transaction integration, schema validation, exception translation), and SQL initialization, plus Flyway or Liquibase when present. Repositories carry the same AOP proxy as in the running application, so `@Transactional` and other interface-level advice behave identically under test, and a `JdbcTemplate` and `JdbcClient` are available for verifying database state through a channel independent of the ORM under test. It complements [`@StormTest`](testing.md), which tests data logic without a Spring context: use `@StormTest` for fast query-level tests, and the slice when the test should see what production Spring code sees: injected repository beans, translated exceptions, Spring-managed transactions, and your `storm.*` configuration. Regular `@Component`, `@Service`, and `@Controller` beans stay out of the context. Each test method runs in a transaction that is rolled back afterwards, so tests cannot see each other's writes.

The application's `DataSource` is replaced with an embedded in-memory database by default, on Spring Boot 3 and 4 alike (the slice ships a fallback for Boot 4's relocated test-database support); put a `schema.sql` (and optionally `data.sql`) on the test classpath to initialize it, or let Flyway or Liquibase (included in the slice) create the schema as in production:

```kotlin
@DataStormTest
class VisitRepositoryTest(
    @Autowired private val visitRepository: VisitRepository,
) {

    @Test
    fun `finds all visits`() {
        visitRepository.count() shouldBe 14
    }

    @Test
    fun `insert rolls back after the test`() {
        visitRepository.insert(Visit(description = "temporary"))
    }
}
```

To run the same slice on the database the application deploys on, name it with the `database` attribute. The slice starts a Testcontainers-managed container of that database once per JVM, shared by every test class that asks for it, gives each Spring context a fresh database inside the container, and points `spring.datasource.*` at it. Nothing else changes; the same `schema.sql`, `data.sql`, Flyway or Liquibase setup that initializes the embedded database initializes the container database:

```kotlin
@DataStormTest(database = TestDatabase.POSTGRESQL)
class VisitRepositoryPostgresTest(
    @Autowired private val visitRepository: VisitRepository,
) {

    @Test
    fun `finds all visits`() {
        visitRepository.count() shouldBe 14
    }
}
```

The attribute is the one [`@StormTest`](testing.md#testing-against-the-database-you-deploy-on) has, with the same databases (`POSTGRESQL`, `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE` from `st.orm.test.TestDatabase`), the same pinned default images and `image` override, and the same dependency requirements: the Testcontainers module for the database and its JDBC driver on the test classpath, and for SQL Server the license acceptance file. Both annotations share their containers within a JVM.

What the slice sets, and why:

- `spring.datasource.url`, `spring.datasource.username` and `spring.datasource.password` point at the provisioned database, ahead of anything the application's configuration files say.
- `spring.test.database.replace=none`, so neither Boot's embedded replacement nor the slice's own Boot 4 fallback swaps the container database out again.
- `spring.sql.init.mode=always`, unless the application configures `spring.sql.init.mode` itself. Boot runs `schema.sql` and `data.sql` for embedded databases only; the container database is as disposable as the embedded one, so they run there too.

The database is created when the context is created and dropped when it closes. Test classes with the same configuration share one context, and with it one database; classes that name a different database or image get a context and a database of their own.

A container the slice does not manage, for example one configured beyond what the attribute offers, still works the way it did: disable the replacement with `spring.test.database.replace=none` and hand the slice the database through `@ServiceConnection`:

```kotlin
@DataStormTest(properties = ["spring.test.database.replace=none"])
@Testcontainers
class VisitRepositoryPostgresTest(
    @Autowired private val visitRepository: VisitRepository,
) {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:17-alpine")
    }

    @Test
    fun `finds all visits`() {
        visitRepository.count() shouldBe 14
    }
}
```

`@ServiceConnection` also works on a container declared as a `@Bean` in a test configuration, the pattern `@TestConfiguration` classes shared between tests use.

The slice works with both starters, pulling in the starter's auto-configuration classes by name, which are identical for the Java and Kotlin stacks. It also works with both Spring Boot 3 and 4: where Spring Boot moved classes between the releases, the slice is exclusion-based rather than annotation-composed. The annotation supports `properties` for per-test configuration (for example `properties = ["storm.validation.schema-mode=none"]` when the test schema deliberately diverges from the entity model) and `includeFilters`/`excludeFilters` to pull additional components into the slice.

Two composition notes. Test fixtures loaded per test method participate in the rollback transaction, so identity-generated keys drift across methods (sequences do not roll back); load reference fixtures once with `@Sql(executionPhase = ExecutionPhase.BEFORE_TEST_CLASS)` and let each test's own writes roll back. And an application that excludes `StormTransactionAutoConfiguration` (the coroutine-native setup) should re-enable it for the slice with `properties = ["spring.autoconfigure.exclude="]`; without the transaction bridge, repository writes cannot join the rollback transaction.

## Multiple Data Sources

Larger applications sometimes expose the same database through several `DataSource` beans: one connection pool per domain, each with its own pool size, timeout, and isolation settings, so heavy batch work cannot starve interactive traffic. Storm supports this topology with one `ORMTemplate` per `DataSource` and one repository post-processor per domain.

```kotlin
@Configuration
class BillingConfiguration {

    @Bean
    fun billingDataSource(): DataSource = /* dedicated pool for the billing domain */

    @Bean
    fun billingTransactionManager(@Qualifier("billingDataSource") dataSource: DataSource): PlatformTransactionManager =
        DataSourceTransactionManager(dataSource)

    @Bean
    fun billingTemplate(
        @Qualifier("billingDataSource") dataSource: DataSource,
        transactionManagers: ObjectProvider<PlatformTransactionManager>,
    ): ORMTemplate = springOrmTemplate(dataSource) { transactionManagers.orderedStream().toList() }
}
```

Each template passes the full transaction manager list; the Spring bridge selects the manager that matches the template's `DataSource`, so every domain transacts through its own pool.

Repositories bind to a specific template through a per-domain post-processor. The `repositoryPrefix` keeps bean names apart when the same repository interface is registered for several domains:

```kotlin
@Configuration
class BillingRepositoryConfiguration {

    @Bean
    fun billingRepositories() = RepositoryBeanFactoryPostProcessor(
        basePackages = arrayOf("com.myapp.billing.repository"),
        ormTemplateBeanName = "billingTemplate",
        repositoryPrefix = "billing",
    )
}
```

The starter cooperates with this setup: with several `DataSource` beans and no `@Primary`, the auto-configured `ORMTemplate` and schema validation back off (there is no single candidate to bind to); mark one pool `@Primary` and they bind to that one. The auto-configured repository scanning backs off as soon as you define your own post-processors, and the Spring-aware `ConnectionProvider` and `TransactionTemplateProvider` beans remain available for injection into your own template definitions.

Storm's programmatic transaction API needs no per-domain configuration: a `transaction` block binds to the template used inside it, so each domain's blocks run against that domain's pool and transaction manager.

## JPA Entity Manager

Storm can create an `ORMTemplate` from a JPA `EntityManager`, which lets you use Storm queries within existing JPA transactions and services. This is particularly useful during incremental [migration from JPA](migration-from-jpa.md), where you can convert one repository or query at a time without changing your transaction management strategy.

```java
@PersistenceContext
private EntityManager entityManager;

@Transactional
public void doWork() {
    var orm = ORMTemplate.of(entityManager);
    // Use orm alongside existing JPA code
}
```

## Transaction Propagation

When a template is wired to Spring's transaction management (via `springOrmTemplate` or the starter), Storm's programmatic transactions participate in Spring's transaction propagation. This means a `transaction` or `transactionBlocking` block checks for an existing Spring-managed transaction before starting a new one. If a transaction already exists, the block joins it. If not, it creates a new independent transaction.

Understanding this behavior is important for controlling atomicity. When multiple operations must commit or roll back as a unit, they need to share the same transaction. When operations should be independent (for example, logging that should persist even if the main operation fails), they need separate transactions.

### Joining Existing Transactions

```kotlin
@Transactional
fun outerMethod() {
    // Spring starts a transaction

    transactionBlocking {
        // This block joins the Spring transaction
        orm.insert(user1)
    }

    transactionBlocking {
        // This block also joins the same transaction
        orm.insert(user2)
    }

    // Both inserts commit or rollback together
}
```

### Starting New Transactions

Without an outer `@Transactional`, each `transactionBlocking` block starts and commits its own transaction independently. A failure in one block does not affect previously committed blocks.

```kotlin
fun methodWithoutTransactional() {
    transactionBlocking {
        // Starts new transaction
        orm.insert(user1)
    }  // Commits here

    transactionBlocking {
        // Starts another new transaction
        orm.insert(user2)
    }  // Commits here
}
```

### Key Benefits of Programmatic Transactions

1. **Explicit boundaries.** See exactly where transactions start and end.
2. **Compile-time safety.** No risk of forgetting `@Transactional` on a method.
3. **Flexible composition.** Easily combine with Spring's declarative model.
4. **Reduced proxy overhead.** No need for Spring's transaction proxies in pure Storm code.

### Mixing Approaches

You can use both styles in the same application:

```kotlin
@Service
class OrderService(
    private val orm: ORMTemplate,
    private val paymentService: PaymentService  // Uses @Transactional
) {

    @Transactional
    fun processOrder(order: Order) {
        // Spring transaction

        transactionBlocking {
            // Participates in Spring transaction
            orm.insert(order)
        }

        // Other @Transactional services also participate
        paymentService.processPayment(order)
    }
}
```

## Tips

1. **Use the Spring Boot Starter.** It eliminates boilerplate configuration and auto-discovers your repositories.
2. **Use `@Transactional` for declarative transactions.** Simple and familiar for Spring developers.
3. **Use programmatic transactions for complex flows.** Nested transactions, savepoints, and explicit propagation are easier to express in code.
4. **Configure Storm via `application.yml`.** The starter builds a `StormConfig` from Spring properties and passes it to the `ORMTemplate`.
5. **One `ORMTemplate` bean is enough.** Inject it into services or let repositories use it automatically.
6. **Works with any DataSource.** HikariCP, Tomcat pool, or any other connection pool that Spring Boot configures.
