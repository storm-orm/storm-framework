import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Migration from JPA

This guide helps you transition from JPA/Hibernate to Storm. The two frameworks can coexist in the same application, allowing you to migrate gradually, one entity or repository at a time.

## Key Differences

| JPA/Hibernate | Storm |
|---------------|-------|
| Mutable entities with proxies | Immutable records/data classes |
| Managed persistence context | Stateless operations |
| Lazy loading by default | Eager loading in single query |
| `@Entity`, `@Id`, `@Column` | `@PK`, `@FK`, `@DbColumn` |
| JPQL / Criteria API | Type-safe DSL / SQL Templates |
| EntityManager | ORMTemplate |
| `@OneToMany`, `@ManyToOne` | `@FK` annotation |

## Entity Migration

The biggest conceptual shift from JPA to Storm is the move from mutable, proxy-backed classes to immutable records and data classes. In JPA, entities carry hidden state: change-tracking proxies, managed lifecycle, and lazy-loading hooks injected via bytecode. Storm eliminates all of this. An entity is a plain value object with annotations that describe its mapping. The database interaction happens in repositories and templates, not inside the entity itself.

This separation makes entities safe to pass across layers, serialize, and store in collections without worrying about detachment or session scope.

### Complete Before/After Walkthrough

The following example demonstrates migrating a complete JPA entity with relationships, a Spring Data repository, and JPQL queries to their Storm equivalents.

**JPA Entity:**

```java
@Entity
@Table(name = "user")
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "city_id")
    private City city;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    // Getters and setters (15+ lines omitted)...
}
```

**Storm Entity:**

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Long = 0,
    val email: String,
    val name: String,
    @FK val city: City?,
    val createdAt: LocalDateTime?
) : Entity<Long>
```

Storm derives the table name (`user`) from the class name and column names (`email`, `name`, `city_id`, `created_at`) from the field names, both using camelCase-to-snake_case conversion. The `@PK` annotation marks the primary key, and `@FK` marks the foreign key relationship. No `@Column`, `@Table`, `@GeneratedValue`, or `@JoinColumn` annotations are needed because the defaults match. The default value `id: Long = 0` tells Storm that the ID is auto-generated.

</TabItem>
<TabItem value="java" label="Java">

```java
record User(
    @PK Long id,
    @Nonnull String email,
    @Nonnull String name,
    @Nullable @FK City city,
    @Nullable LocalDateTime createdAt
) implements Entity<Long> {}
```

Storm derives the table name (`user`) from the class name and column names (`email`, `name`, `city_id`, `created_at`) from the field names, both using camelCase-to-snake_case conversion. The `@PK` annotation marks the primary key, and `@FK` marks the foreign key relationship. No `@Column`, `@Table`, `@GeneratedValue`, or `@JoinColumn` annotations are needed because the defaults match. Passing `null` for the ID tells Storm that the ID is auto-generated.

</TabItem>
</Tabs>

**JPA Repository (Spring Data):**

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmail(String email);

    List<User> findByCityOrderByNameAsc(City city);

    @Query("SELECT u FROM User u WHERE u.createdAt > :since")
    List<User> findRecentUsers(@Param("since") LocalDateTime since);
}
```

**Storm Repository:**

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
interface UserRepository : EntityRepository<User, Long> {

    fun findByEmail(email: String): User? =
        find(User_.email eq email)

    fun findByCity(city: City): List<User> =
        select()
            .where(User_.city eq city)
            .orderBy(User_.name)
            .resultList

    fun findRecentUsers(since: LocalDateTime): List<User> =
        findAll(User_.createdAt gt since)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
interface UserRepository extends EntityRepository<User, Long> {

    default Optional<User> findByEmail(String email) {
        return select()
            .where(User_.email, EQUALS, email)
            .getOptionalResult();
    }

    default List<User> findByCity(City city) {
        return select()
            .where(User_.city, EQUALS, city)
            .orderBy(User_.name)
            .getResultList();
    }

    default List<User> findRecentUsers(LocalDateTime since) {
        return select()
            .where(User_.createdAt, GREATER_THAN, since)
            .getResultList();
    }
}
```

</TabItem>
</Tabs>

The key difference is that Storm repository methods have explicit method bodies with the query logic visible in the source code. There is no query derivation from method names. Every query is IDE-navigable and compiler-checked.

## Annotation Mapping

Storm uses fewer annotations than JPA because it derives most mapping information from the entity structure itself. Table names follow from the class name (converted to snake_case), and column names follow from field names. You only need annotations for primary keys, foreign keys, and cases where the default naming does not match your schema.

| JPA | Storm |
|-----|-------|
| `@Entity` | Implement `Entity<T>` interface |
| `@Table(name = "...")` | `@DbTable("...")` |
| `@Id` | `@PK` |
| `@Column(name = "...")` | `@DbColumn("...")` |
| `@ManyToOne` | `@FK` |
| `@JoinColumn` | Column name in `@FK("...")` |
| `@Version` | `@Version` |

## Repository Migration

JPA repositories (particularly Spring Data JPA) rely on method name conventions or `@Query` annotations to define queries. Storm repositories use explicit method bodies with a type-safe DSL. This means slightly more code per method, but every query is visible in the source, IDE-navigable, and compiler-checked. There are no hidden query derivation rules to memorize.

### JPA Repository

```java
@Repository
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByEmail(String email);
    List<User> findByCity(City city);
}
```

### Storm Repository

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
interface UserRepository : EntityRepository<User, Long> {

    fun findByEmail(email: String): User? =
        find(User_.email eq email)

    fun findByCity(city: City): List<User> =
        findAll(User_.city eq city)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
interface UserRepository extends EntityRepository<User, Long> {

    default Optional<User> findByEmail(String email) {
        return select()
            .where(User_.email, EQUALS, email)
            .getOptionalResult();
    }

    default List<User> findByCity(City city) {
        return select()
            .where(User_.city, EQUALS, city)
            .getResultList();
    }
}
```

</TabItem>
</Tabs>

## Query Migration

Storm offers two query approaches: the type-safe DSL (using the generated metamodel) and SQL Templates (for raw SQL with type interpolation). The DSL covers common CRUD patterns concisely, while SQL Templates let you write arbitrary SQL without losing type safety on parameters and result mapping. The examples below show how each JPA query style maps to Storm equivalents.

### JPQL

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```java
// JPA
@Query("SELECT u FROM User u WHERE u.email = :email")
Optional<User> findByEmail(@Param("email") String email);
```

```kotlin
// Storm
fun findByEmail(email: String): User? =
    find(User_.email eq email)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// JPA
@Query("SELECT u FROM User u WHERE u.email = :email")
Optional<User> findByEmail(@Param("email") String email);

// Storm
default Optional<User> findByEmail(String email) {
    return select()
        .where(User_.email, EQUALS, email)
        .getOptionalResult();
}
```

</TabItem>
</Tabs>

### Criteria API

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```java
// JPA Criteria
CriteriaBuilder cb = em.getCriteriaBuilder();
CriteriaQuery<User> cq = cb.createQuery(User.class);
Root<User> root = cq.from(User.class);
cq.where(cb.equal(root.get("city"), city));
return em.createQuery(cq).getResultList();
```

```kotlin
// Storm
orm.findAll(User_.city eq city)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// JPA Criteria
CriteriaBuilder cb = em.getCriteriaBuilder();
CriteriaQuery<User> cq = cb.createQuery(User.class);
Root<User> root = cq.from(User.class);
cq.where(cb.equal(root.get("city"), city));
return em.createQuery(cq).getResultList();

// Storm
orm.entity(User.class)
    .select()
    .where(User_.city, EQUALS, city)
    .getResultList();
```

</TabItem>
</Tabs>

### Native Queries

```java
// JPA
@Query(value = "SELECT * FROM users WHERE email LIKE %:pattern%", nativeQuery = true)
List<User> searchByEmail(@Param("pattern") String pattern);

// Storm (Java)
orm.query(RAW."SELECT \{User.class} FROM \{User.class} WHERE email LIKE \{pattern}")
    .getResultList(User.class);
```

## Relationship Changes

JPA models relationships bidirectionally with annotations like `@OneToMany` and `@ManyToOne`, relying on lazy-loading proxies to defer fetching. Storm takes a different approach: relationships are unidirectional, defined by `@FK` on the owning side, and loaded eagerly by default in the same query. When you need deferred loading (for example, to avoid loading a large sub-graph), wrap the field type in `Ref<T>` to make fetching explicit.

### Lazy Loading to Eager/Ref

JPA default: lazy loading with proxy

```java
// JPA - fetches city on access (N+1 risk)
user.getCity().getName();
```

Storm options:

1. **Eager loading** (default with `@FK`):
```kotlin
data class User(@PK val id: Long = 0, @FK val city: City) : Entity<Long>
// City loaded in same query as User
```

2. **Deferred loading** (with `Ref`):
```kotlin
data class User(@PK val id: Long = 0, @FK val city: Ref<City>) : Entity<Long>
// City loaded explicitly when needed
val cityName = user.city.fetch().name
```

### OneToMany Collections

JPA approach:
```java
@OneToMany(mappedBy = "user")
private List<Order> orders;
```

Storm approach (query the "many" side):
```kotlin
val orders = orm.findAll(Order_.user eq user)
```

## Transaction Migration

Storm supports both Spring's `@Transactional` annotation and its own programmatic `transaction {}` block. If you are migrating a Spring application, your existing `@Transactional` annotations continue to work unchanged. Storm participates in the same Spring-managed transaction. The programmatic API is useful when you want explicit control over isolation levels, propagation, or when working outside of Spring entirely.

### JPA @Transactional

```java
@Transactional
public void createUser(String email) {
    userRepository.save(new User(email));
}
```

### Storm (works with Spring @Transactional)

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Transactional
fun createUser(email: String) {
    orm insert User(email = email)
}
```

### Storm Programmatic

```kotlin
transaction {
    orm insert User(email = email)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Transactional
public void createUser(String email) {
    orm.entity(User.class).insert(new User(null, email, null, null, null));
}
```

</TabItem>
</Tabs>

## Schema Management

Storm validates your schema but does not generate or migrate it. Storm never issues DDL statements (`CREATE TABLE`, `ALTER TABLE`, etc.) against your database. For schema migrations, use dedicated tools like [Flyway](https://flywaydb.org/) or [Liquibase](https://www.liquibase.org/) alongside Storm.

If you are coming from JPA with `ddl-auto=update` or `ddl-auto=create`, you will need to manage schema changes explicitly. This is a deliberate choice: automatic schema generation is convenient for prototyping but dangerous in production, where unreviewed DDL can cause data loss or downtime. Flyway and Liquibase give you version-controlled, reviewable, repeatable migrations.

### Flyway Example Layout

A typical project structure places Flyway migrations alongside your Storm entities:

```
src/
├── main/
│   ├── kotlin/com/example/
│   │   ├── entity/
│   │   │   ├── User.kt
│   │   │   └── Order.kt
│   │   └── repository/
│   │       ├── UserRepository.kt
│   │       └── OrderRepository.kt
│   └── resources/
│       ├── application.yml
│       └── db/migration/
│           ├── V1__create_user_table.sql
│           ├── V2__create_order_table.sql
│           └── V3__add_user_email_index.sql
```

Each `V*__.sql` file contains the DDL for that migration step. Flyway runs them in order and tracks which migrations have been applied. Spring Boot auto-configures Flyway when it is on the classpath, so no additional setup is needed beyond adding the dependency.

### Recommended Schema Validation Configuration

Storm's schema validation (see [Validation](validation.md)) acts as a safety net that catches drift between your entity definitions and the actual database structure. Use different modes depending on the environment:

```yaml
# Development: warn on mismatches but allow startup
storm:
  validation:
    schema-mode: warn

# CI and production: block startup if entities don't match the schema
storm:
  validation:
    schema-mode: fail
```

The `warn` mode is useful during development when you are iterating on both entities and migrations simultaneously. The `fail` mode is recommended for CI pipelines and production, where a mismatch indicates either a missing migration or an entity definition that is out of sync. See [Validation](validation.md) for details on the checks performed and how to suppress known mismatches with `@DbIgnore`.

## Gradual Migration Strategy

A full rewrite is rarely practical. Storm and JPA can share the same DataSource, so you can migrate incrementally without a flag day. Start with leaf entities (those with no inbound foreign keys from other JPA entities) and work inward. Each migrated entity reduces your JPA surface area without breaking existing code.

1. **Add Storm dependencies** alongside JPA.
2. **Create Storm entities** for new tables.
3. **Migrate simple entities first** (no complex relationships).
4. **Replace lazy loading with Ref** where needed.
5. **Migrate repositories** one at a time.
6. **Update service layer** to use Storm repositories.
7. **Remove JPA entities and dependencies** when complete.

## Running Storm Alongside JPA

Storm and JPA can coexist in the same Spring Boot application. Both frameworks use JDBC under the hood, so they share the same `DataSource`, connection pool, and Spring-managed transactions. This means a single `@Transactional` method can call both a JPA repository and a Storm repository, and both operations will participate in the same database transaction.

This works because Spring's `PlatformTransactionManager` manages the underlying JDBC connection. Both JPA (via its `EntityManager`) and Storm (via its `ORMTemplate`) obtain connections from the same `DataSource`, and Spring ensures they share the transaction context.

### Configuration

No special configuration is needed beyond making sure Storm's `ORMTemplate` uses the same `DataSource` that JPA uses. Spring Boot's auto-configuration handles this automatically when you include the Storm Spring Boot Starter.

### Example: Mixed JPA and Storm Service

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// JPA entity (legacy)
@Entity
@jakarta.persistence.Table(name = "legacy_customer")
class LegacyCustomer {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long? = null
    var name: String = ""
    var email: String = ""
}

// JPA repository (legacy)
interface LegacyCustomerRepository : JpaRepository<LegacyCustomer, Long>

// Storm entity (new)
data class CustomerProfile(
    @PK val id: Long = 0,
    val customerId: Long,
    val bio: String,
    val avatarUrl: String?
) : Entity<Long>

// Storm repository (new)
interface CustomerProfileRepository : EntityRepository<CustomerProfile, Long> {
    fun findByCustomerId(customerId: Long): CustomerProfile? =
        find(CustomerProfile_.customerId eq customerId)
}

// Service that uses both
@Service
class CustomerService(
    private val legacyCustomerRepository: LegacyCustomerRepository,
    private val customerProfileRepository: CustomerProfileRepository
) {
    @Transactional
    fun createCustomerWithProfile(name: String, email: String, bio: String): CustomerProfile {
        // JPA insert
        val customer = LegacyCustomer().apply {
            this.name = name
            this.email = email
        }
        legacyCustomerRepository.save(customer)

        // Storm insert in the same transaction
        val profile = CustomerProfile(
            customerId = customer.id!!,
            bio = bio,
            avatarUrl = null
        )
        customerProfileRepository.insert(profile)
        return profile
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// JPA entity (legacy)
@jakarta.persistence.Entity
@jakarta.persistence.Table(name = "legacy_customer")
public class LegacyCustomer {
    @jakarta.persistence.Id
    @jakarta.persistence.GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private String email;
    // getters and setters omitted
}

// JPA repository (legacy)
public interface LegacyCustomerRepository extends JpaRepository<LegacyCustomer, Long> {}

// Storm entity (new)
public record CustomerProfile(
    @PK Long id,
    long customerId,
    String bio,
    @Nullable String avatarUrl
) implements Entity<Long> {}

// Storm repository (new)
public interface CustomerProfileRepository extends EntityRepository<CustomerProfile, Long> {
    default Optional<CustomerProfile> findByCustomerId(long customerId) {
        return select()
            .where(CustomerProfile_.customerId, EQUALS, customerId)
            .getOptionalResult();
    }
}

// Service that uses both
@Service
public class CustomerService {

    private final LegacyCustomerRepository legacyCustomerRepository;
    private final CustomerProfileRepository customerProfileRepository;

    public CustomerService(LegacyCustomerRepository legacyCustomerRepository,
                           CustomerProfileRepository customerProfileRepository) {
        this.legacyCustomerRepository = legacyCustomerRepository;
        this.customerProfileRepository = customerProfileRepository;
    }

    @Transactional
    public CustomerProfile createCustomerWithProfile(String name, String email, String bio) {
        // JPA insert
        var customer = new LegacyCustomer();
        customer.setName(name);
        customer.setEmail(email);
        legacyCustomerRepository.save(customer);

        // Storm insert in the same transaction
        var profile = new CustomerProfile(null, customer.getId(), bio, null);
        customerProfileRepository.insert(profile);
        return profile;
    }
}
```

</TabItem>
</Tabs>

Both the JPA `save()` and the Storm `insert()` execute within the same database transaction. If either operation fails, the entire transaction rolls back. This works because both frameworks delegate to Spring's transaction manager, which coordinates the underlying JDBC connection.

## Common Pitfalls

The most frequent issues arise from habits carried over from JPA. The following patterns cover the mistakes that developers encounter most often during migration.

### Missing Eager Fetch

In JPA, relationships are lazy-loaded by default, so you can define a foreign key column as a raw ID and still access the related entity through the proxy. Storm has no proxies. If you declare a field as a raw ID (e.g., `val cityId: Long`), Storm treats it as a plain column value with no relationship. To load the related entity, use `@FK` with the entity type.

```kotlin
// Wrong - city not available
data class User(@PK val id: Long = 0, val cityId: Long) : Entity<Long>

// Right - city loaded with user
data class User(@PK val id: Long = 0, @FK val city: City) : Entity<Long>
```

### Mutable Habits

JPA entities are mutable: you call setters, and the persistence context tracks changes automatically. Storm entities are immutable values. To modify an entity, create a new instance with the changed fields using Kotlin's `copy()` method or Java's record `with` pattern. The original instance remains unchanged, which makes reasoning about state straightforward.

```kotlin
// Wrong (JPA style)
user.setName("New Name")

// Right (Storm style)
val updated = user.copy(name = "New Name")
orm update updated
```

### Collection Expectations

Storm intentionally does not support collection fields on entities. This is a deliberate design choice. Collections on entities lead to lazy loading, N+1 queries, and unpredictable behavior. Query relationships explicitly:

```kotlin
// Wrong expectation
val orders = user.orders  // Not supported

// Right approach
val orders = orm.findAll(Order_.user eq user)
```

## Schema Validation

If you relied on Hibernate's `ddl-auto=validate` to catch entity/schema mismatches, Storm offers the same capability through its schema validation feature. Enable it in `application.yml`:

```yaml
storm:
  validation:
    schema-mode: fail
```

This validates all entity definitions against the database schema at startup and blocks if any mismatches are found. See [Configuration: Schema Validation](configuration.md#schema-validation) for details on the checks performed, warning vs. error severity, strict mode, and `@DbIgnore` for suppressing known mismatches.

## What You Gain

After migrating from JPA to Storm, you can expect:

- **No more N+1 queries.** Entity graphs load in a single query by default.
- **No more LazyInitializationException.** No proxies, no surprise database hits.
- **No more detached entity errors.** Entities are stateless and always safe to use.
- **Simpler entities.** Records and data classes with a few annotations replace complex JPA mappings.
- **Predictable SQL.** What you see is what gets executed, no hidden query generation.
- **Fewer lines of code.** Typically ~5 lines per entity vs. ~30 for JPA.
