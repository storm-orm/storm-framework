import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Spring Integration

Storm integrates seamlessly with Spring Framework and Spring Boot for dependency injection, transaction management, and repository auto-wiring. This guide covers setup for both languages.

## Installation

Storm provides Spring Boot Starter modules that auto-configure everything you need. If you use the starter, you do not need to add `storm-kotlin-spring` or `storm-spring` separately; the starter includes them.

### Spring Boot Starter (Recommended)

The starter modules provide zero-configuration setup: an `ORMTemplate` bean is created automatically from the `DataSource`, repositories are discovered from the application's base package, and (for Kotlin) transaction integration is enabled. See [Spring Boot Starter](#spring-boot-starter) for full details.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-kotlin-spring-boot-starter:1.11.0")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-kotlin-spring-boot-starter</artifactId>
    <version>1.11.0</version>
</dependency>
```

</TabItem>
<TabItem value="java" label="Java">

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring-boot-starter</artifactId>
    <version>1.11.0</version>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-spring-boot-starter:1.11.0")
```

</TabItem>
</Tabs>

### Spring Integration Without Auto-Configuration

If you prefer manual configuration, or need to customize the setup beyond what the starter provides, use the integration modules directly:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-kotlin-spring:1.11.0")
```

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-kotlin-spring</artifactId>
    <version>1.11.0</version>
</dependency>
```

</TabItem>
<TabItem value="java" label="Java">

```xml
<!-- Maven -->
<dependency>
    <groupId>st.orm</groupId>
    <artifactId>storm-spring</artifactId>
    <version>1.11.0</version>
</dependency>
```

```kotlin
// Gradle (Kotlin DSL)
implementation("st.orm:storm-spring:1.11.0")
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

By default, Storm manages its own transactions independently of Spring's transaction context. The `@EnableTransactionIntegration` annotation bridges the two systems so that Storm's programmatic `transaction` and `transactionBlocking` blocks participate in Spring-managed transactions. Without this annotation, a transaction block inside a `@Transactional` method would open a separate database connection and transaction.

```kotlin
@EnableTransactionIntegration
@Configuration
class ORMConfiguration(private val dataSource: DataSource) {

    @Bean
    fun ormTemplate(): ORMTemplate = dataSource.orm
}
```

This allows combining Spring's `@Transactional` with Storm's programmatic `transaction` blocks:

```kotlin
@Transactional
fun processUsers() {
    // Spring manages outer transaction

    transactionBlocking {
        // Participates in Spring transaction
        orm.deleteAll<Visit>()
    }
}
```

### Repository Injection

Storm repositories are interfaces with default method implementations. Spring cannot discover them automatically because they are not annotated with `@Component` or `@Repository`. The `RepositoryBeanFactoryPostProcessor` scans specified packages for interfaces that extend `EntityRepository` or `ProjectionRepository` and registers them as Spring beans. This makes them available for constructor injection like any other Spring-managed dependency.

```kotlin
@Configuration
class AcmeRepositoryBeanFactoryPostProcessor : RepositoryBeanFactoryPostProcessor() {

    override val repositoryBasePackages: Array<String>
        get() = arrayOf("com.acme.repository")
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

The configuration mirrors the Kotlin setup. Define a single `ORMTemplate` bean that wraps the Spring-managed `DataSource`.

```java
@Configuration
public class ORMConfiguration {

    private final DataSource dataSource;

    public ORMConfiguration(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Bean
    public ORMTemplate ormTemplate() {
        return ORMTemplate.of(dataSource);
    }
}
```

### Repository Injection

Register a `RepositoryBeanFactoryPostProcessor` that scans your repository packages. This works identically to the Kotlin version: Storm discovers interfaces extending `EntityRepository` or `ProjectionRepository` and registers them as Spring beans.

```java
@Configuration
public class AcmeRepositoryBeanFactoryPostProcessor extends RepositoryBeanFactoryPostProcessor {

    @Override
    public String[] getRepositoryBasePackages() {
        return new String[] { "com.acme.repository" };
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

1. **`ORMTemplate` bean** created from the auto-configured `DataSource`. If you define your own `ORMTemplate` bean, the auto-configured one backs off.
2. **Repository scanning** via `AutoConfiguredRepositoryBeanFactoryPostProcessor`, which discovers repository interfaces in the `@SpringBootApplication` base package (and its sub-packages). If you define your own `RepositoryBeanFactoryPostProcessor` bean, the auto-configured one backs off.
3. **Transaction integration** (Kotlin only) by automatically activating `SpringTransactionConfiguration`, removing the need for `@EnableTransactionIntegration`.
4. **Configuration properties** bound from `storm.*` in `application.yml`/`application.properties`, passed to the `ORMTemplate` via `StormConfig`.

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
    schema-mode: none
    strict: false
```

The `schema-mode` property controls startup schema validation: `none` (default) skips validation, `warn` logs mismatches without blocking startup, and `fail` blocks startup if any entity definitions do not match the database schema. The `strict` property controls whether warnings (type narrowing, nullability mismatches) are treated as errors. See the [Configuration](configuration.md#schema-validation) guide for details.

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
class MyRepositoryPostProcessor : RepositoryBeanFactoryPostProcessor() {

    override val repositoryBasePackages: Array<String>
        get() = arrayOf("com.myapp.repository", "com.myapp.other")
}
```

### Minimal Spring Boot Setup (without Starter)

If you use the integration module directly (without the starter), you need to configure Storm manually:

```kotlin
@SpringBootApplication
@EnableTransactionManagement
class Application

@Configuration
@EnableTransactionIntegration
class StormConfig(private val dataSource: DataSource) {

    @Bean
    fun ormTemplate() = dataSource.orm
}

@Configuration
class MyRepositoryBeanFactoryPostProcessor : RepositoryBeanFactoryPostProcessor() {

    override val repositoryBasePackages: Array<String>
        get() = arrayOf("com.myapp.repository")
}
```

This gives you:
- Automatic DataSource from Spring Boot
- Transaction integration between Spring and Storm
- Repository auto-discovery and injection

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

When `@EnableTransactionIntegration` is active, Storm's programmatic transactions participate in Spring's transaction propagation. This means a `transaction` or `transactionBlocking` block checks for an existing Spring-managed transaction before starting a new one. If a transaction already exists, the block joins it. If not, it creates a new independent transaction.

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
