import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Security

Storm is designed with security as a structural property rather than an afterthought. The framework's template-based query model makes SQL injection difficult by construction, and its stateless entity design reduces the surface area for common ORM-related vulnerabilities.

This page covers how Storm prevents SQL injection, the escape hatches that exist and when to use them, and patterns for building audit trails and access control into your application.

---

## SQL Injection Prevention

### Parameterized by Construction

The most important security property of Storm is that **all values are parameterized by default**. When you write a query using Storm's template API, values are never concatenated into the SQL string. They are always sent as JDBC parameters:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// The 'email' value is sent as a JDBC parameter, not interpolated into SQL.
val user = userRepository.find(User_.email eq email)
```

Generated SQL:
```sql
SELECT ... FROM "user" WHERE "email" = ?
```

</TabItem>
<TabItem value="java" label="Java">

```java
// The 'email' value is sent as a JDBC parameter, not interpolated into SQL.
User user = userRepository.find(User_.email.eq(email));
```

Generated SQL:
```sql
SELECT ... FROM "user" WHERE "email" = ?
```

</TabItem>
</Tabs>

This applies to all Storm APIs, including:

- Repository methods (`find`, `findAll`, `select`, `insert`, `update`, `delete`)
- Query builder operations (`.where()`, `.set()`, `.values()`)
- SQL templates with embedded expressions

When using SQL templates directly, embedded values are also parameterized:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Both 'status' and 'minAge' become JDBC parameters.
val users = orm.query("SELECT * FROM user WHERE status = $status AND age > $minAge")
    .getResultList(User::class)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Both 'status' and 'minAge' become JDBC parameters.
List<User> users = orm.query(RAW."""
        SELECT * FROM user WHERE status = \{status} AND age > \{minAge}""")
    .getResultList(User.class);
```

</TabItem>
</Tabs>

There is no way to accidentally create an injection vulnerability through normal Storm API usage.

### How It Works

Storm's SQL template processor separates the query structure (the SQL text with placeholders) from the values (the parameters). The JDBC driver receives the SQL template and the parameter values independently, so the database never interprets user-supplied data as SQL syntax.

```
Application Code          Storm Template Engine         JDBC Driver
      │                          │                          │
      │   query with values      │                          │
      ├─────────────────────────▶│                          │
      │                          │  SQL with ? placeholders │
      │                          ├─────────────────────────▶│
      │                          │  Parameter values        │
      │                          ├─────────────────────────▶│
      │                          │                          │
```

---

## The unsafe() Escape Hatch

Storm includes safety checks that prevent potentially dangerous operations. For example, executing a `DELETE` or `UPDATE` without a `WHERE` clause will throw a `PersistenceException` because this would affect every row in the table.

When you intentionally need to perform such an operation, call `unsafe()` on the query:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// This would throw: "DELETE without WHERE clause is potentially unsafe."
// userRepository.delete().executeUpdate()

// Explicitly marking as unsafe allows the operation.
orm.entity(User::class).delete().unsafe().executeUpdate()
```

</TabItem>
<TabItem value="java" label="Java">

```java
// This would throw: "DELETE without WHERE clause is potentially unsafe."
// userRepository.delete().executeUpdate();

// Explicitly marking as unsafe allows the operation.
orm.entity(User.class).delete().unsafe().executeUpdate();
```

</TabItem>
</Tabs>

### When unsafe() Is Appropriate

- **Test setup and teardown:** Clearing tables between tests.
- **Data migration scripts:** Bulk operations that intentionally affect all rows.
- **Administrative operations:** One-time cleanup or maintenance tasks.

### When unsafe() Is Not Appropriate

- **Any operation involving user-supplied input.** The `unsafe()` marker disables Storm's safety checks for the query shape, but it does not change how parameters are handled. However, using `unsafe()` in a code path that processes user input is a design smell that suggests the operation should be restructured.

---

## Audit Trail Patterns

Storm's `EntityCallback` interface provides lifecycle hooks that execute before and after every mutation. These hooks are ideal for building audit trails because they are invoked consistently regardless of which code path triggers the mutation.

### Timestamped Auditing

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("document")
data class Document(
    @PK val id: Int,
    val title: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
) : Entity<Int>

class AuditCallback : EntityCallback<Entity<*>> {

    override fun beforeInsert(entity: Entity<*>): Entity<*> {
        if (entity is Document) {
            val now = Instant.now()
            return entity.copy(createdAt = now, updatedAt = now)
        }
        return entity
    }

    override fun beforeUpdate(entity: Entity<*>): Entity<*> {
        if (entity is Document) {
            return entity.copy(updatedAt = Instant.now())
        }
        return entity
    }
}
```

Register the callback when creating the ORM template:

```kotlin
val orm = ORMTemplate.of(dataSource)
    .withEntityCallback(AuditCallback())
```

Or with Spring Boot, declare it as a bean and it will be auto-registered:

```kotlin
@Bean
fun auditCallback(): EntityCallback<*> = AuditCallback()
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("document")
public record Document(
    @PK int id,
    String title,
    Instant createdAt,
    Instant updatedAt
) implements Entity<Integer> {}

public class AuditCallback implements EntityCallback<Entity<?>> {

    @Override
    public Entity<?> beforeInsert(Entity<?> entity) {
        if (entity instanceof Document document) {
            var now = Instant.now();
            return new Document(document.id(), document.title(), now, now);
        }
        return entity;
    }

    @Override
    public Entity<?> beforeUpdate(Entity<?> entity) {
        if (entity instanceof Document document) {
            return new Document(document.id(), document.title(), document.createdAt(), Instant.now());
        }
        return entity;
    }
}
```

Register the callback when creating the ORM template:

```java
ORMTemplate orm = ORMTemplate.of(dataSource)
    .withEntityCallback(new AuditCallback());
```

Or with Spring Boot, declare it as a bean and it will be auto-registered:

```java
@Bean
public EntityCallback<?> auditCallback() {
    return new AuditCallback();
}
```

</TabItem>
</Tabs>

### Mutation Logging

Use `afterInsert`, `afterUpdate`, and `afterDelete` callbacks to record mutations for compliance or debugging:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class MutationLogger : EntityCallback<Entity<*>> {

    private val logger = System.getLogger("audit")

    override fun afterInsert(entity: Entity<*>) {
        logger.log(System.Logger.Level.INFO, "INSERT: ${entity::class.simpleName} id=${entity.id()}")
    }

    override fun afterUpdate(entity: Entity<*>) {
        logger.log(System.Logger.Level.INFO, "UPDATE: ${entity::class.simpleName} id=${entity.id()}")
    }

    override fun afterDelete(entity: Entity<*>) {
        logger.log(System.Logger.Level.INFO, "DELETE: ${entity::class.simpleName} id=${entity.id()}")
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class MutationLogger implements EntityCallback<Entity<?>> {

    private final System.Logger logger = System.getLogger("audit");

    @Override
    public void afterInsert(Entity<?> entity) {
        logger.log(System.Logger.Level.INFO,
            "INSERT: %s id=%s".formatted(entity.getClass().getSimpleName(), entity.id()));
    }

    @Override
    public void afterUpdate(Entity<?> entity) {
        logger.log(System.Logger.Level.INFO,
            "UPDATE: %s id=%s".formatted(entity.getClass().getSimpleName(), entity.id()));
    }

    @Override
    public void afterDelete(Entity<?> entity) {
        logger.log(System.Logger.Level.INFO,
            "DELETE: %s id=%s".formatted(entity.getClass().getSimpleName(), entity.id()));
    }
}
```

</TabItem>
</Tabs>

---

## DataSource Credentials Management

Storm does not manage database credentials directly. It receives a `DataSource` from your application and uses it for all database operations. This means credential security is your responsibility, and standard Java best practices apply.

### Recommended Practices

**Never hardcode credentials.** Use environment variables, a secrets manager (e.g., AWS Secrets Manager, HashiCorp Vault, Azure Key Vault), or Spring's externalized configuration:

```yaml
# application.yml - reference environment variables
spring:
  datasource:
    url: ${DB_URL}
    username: ${DB_USERNAME}
    password: ${DB_PASSWORD}
```

**Use connection pooling with credential rotation.** When using HikariCP (the default for Spring Boot), configure it to support credential rotation:

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 10
      minimum-idle: 5
      connection-timeout: 30000
```

**Restrict database user permissions.** The database user your application connects with should have only the permissions it needs. For a typical CRUD application, grant `SELECT`, `INSERT`, `UPDATE`, and `DELETE` on application tables, but not `DROP`, `ALTER`, or `GRANT`.

---

## Column-Level Access Control

Storm does not provide built-in column-level access control, but you can implement it using projections and entity callbacks.

### Read Control via Projections

Use projections to expose different views of the same table to different user roles. A projection only reads the columns it declares, so restricted columns are never fetched:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Full entity (for admin users).
@DbTable("user")
data class User(
    @PK val id: Int,
    val name: String,
    val email: String,
    val socialSecurityNumber: String,
    val salary: BigDecimal
) : Entity<Int>

// Restricted projection (for regular users).
@DbTable("user")
data class UserPublicView(
    @PK val id: Int,
    val name: String,
    val email: String
) : Projection<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Full entity (for admin users).
@DbTable("user")
public record User(
    @PK int id,
    String name,
    String email,
    String socialSecurityNumber,
    BigDecimal salary
) implements Entity<Integer> {}

// Restricted projection (for regular users).
@DbTable("user")
public record UserPublicView(
    @PK int id,
    String name,
    String email
) implements Projection<Integer> {}
```

</TabItem>
</Tabs>

### Write Control via Callbacks

Use an entity callback to enforce write-level access control by validating or rejecting mutations:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class WriteAccessCallback : EntityCallback<User> {

    override fun beforeUpdate(entity: User): User {
        val currentRole = SecurityContext.currentUserRole()
        if (currentRole != "ADMIN") {
            throw PersistenceException("Only administrators can modify user records.")
        }
        return entity
    }

    override fun beforeDelete(entity: User) {
        val currentRole = SecurityContext.currentUserRole()
        if (currentRole != "ADMIN") {
            throw PersistenceException("Only administrators can delete user records.")
        }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class WriteAccessCallback implements EntityCallback<User> {

    @Override
    public User beforeUpdate(User entity) {
        String currentRole = SecurityContext.currentUserRole();
        if (!"ADMIN".equals(currentRole)) {
            throw new PersistenceException("Only administrators can modify user records.");
        }
        return entity;
    }

    @Override
    public void beforeDelete(User entity) {
        String currentRole = SecurityContext.currentUserRole();
        if (!"ADMIN".equals(currentRole)) {
            throw new PersistenceException("Only administrators can delete user records.");
        }
    }
}
```

</TabItem>
</Tabs>
