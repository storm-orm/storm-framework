import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Storm vs Other Frameworks

There is no universally "best" database framework. Each has strengths suited to different situations, team preferences, and project requirements. Teams approach data access differently, including using frameworks at various abstraction levels or even plain SQL. This page provides a comparison to help you evaluate whether Storm fits your needs, particularly if you value explicit and predictable behavior and fast development. We encourage you to explore the linked documentation for each framework and form your own conclusions.

## Feature Comparison

The following tables provide a side-by-side comparison of concrete features across all frameworks discussed on this page. "Yes" and "No" indicate built-in support; "Manual" means the feature is achievable but requires explicit effort from the developer.

### Entity & Data Modeling

| Feature | Storm | JPA | Spring Data | MyBatis | jOOQ | JDBI | Exposed | Ktorm |
|---------|-------|-----|-------------|---------|------|------|---------|-------|
| Lines per entity | ~5 | ~30<sup>1</sup> | ~30<sup>1</sup> | ~20+ | Generated | ~15 | ~12 | ~15 |
| Immutable entities | Yes | No | No | Yes | Yes | Yes | DSL only | No |
| Polymorphism | Yes<sup>2</sup> | Yes | Via JPA | No | No | No | No | No |
| Automatic relationships | Yes | Yes<sup>3</sup> | Via JPA | No | No | No | DAO only | No |
| Cascade persist | No | Yes | Yes | No | No | No | No | No |
| Lifecycle callbacks | Yes | Yes | Via JPA | No | Yes | No | DAO only | No |

<sup>1</sup> JPA/Spring Data lines without Lombok; ~10 lines with Lombok.

<sup>2</sup> Storm supports Single-Table, Joined Table, and Polymorphic FK strategies using sealed types. JPA additionally supports Table-per-Class and multi-level inheritance hierarchies.

<sup>3</sup> JPA relationships are runtime-managed via proxies.

### Querying & Data Access

| Feature | Storm | JPA | Spring Data | MyBatis | jOOQ | JDBI | Exposed | Ktorm |
|---------|-------|-----|-------------|---------|------|------|---------|-------|
| Type-safe queries | Yes | Criteria | No | No | Yes | No | Yes | Yes |
| SQL Templates | Yes | No | No | XML/Ann | Yes | Yes | No | No |
| N+1 prevention | Yes | No | No | No | Manual | Manual | No | No |
| Lazy loading | Refs | Yes | Yes | No | No | No | Yes | Yes |
| Scrolling | Yes | No | Yes | No | Yes | No | No | No |
| JSON columns | Yes | Yes<sup>4</sup> | Via JPA | Manual | Yes | Module | Yes | Module |
| JSON aggregation | Yes | No | No | No | Yes | No | No | No |

<sup>4</sup> JPA requires Hibernate 6.2+ for built-in JSON support; older versions need a third-party library or custom `AttributeConverter`.

### Runtime & Ecosystem

| Feature | Storm | JPA | Spring Data | MyBatis | jOOQ | JDBI | Exposed | Ktorm |
|---------|-------|-----|-------------|---------|------|------|---------|-------|
| Transactions | Both | Both | Declarative | Both | Programmatic | Both | Both<sup>6</sup> | Required |
| Schema validation | Yes | Yes | Via JPA | No | N/A<sup>5</sup> | No | Yes | No |
| Java support | Yes | Yes | Yes | Yes | Yes | Yes | No | No |
| Kotlin support | First-class | Good | Good | Good | Good | Good | Native | Native |
| Coroutines | Yes | No | No | No | No | No | Yes | Limited |
| Spring integration | Yes | Yes | Native | Yes | Yes | Yes | Yes | Yes |
| Runtime mechanism | Codegen<sup>7</sup> | Bytecode | Bytecode | Reflection | Codegen | Reflection | Reflection | Reflection |
| Community | New | Huge | Huge | Large | Medium | Medium | Medium | Small |

<sup>5</sup> jOOQ generates code from the database schema, so schema validation is inherent in its code generation step.

<sup>6</sup> Exposed requires `transaction {}` blocks natively, but supports declarative `@Transactional` via its Spring integration module.

<sup>7</sup> Storm uses codegen with reflection fallback.

---

## Storm vs JPA/Hibernate

JPA (typically implemented by Hibernate) is the most widely used persistence framework in the Java ecosystem. It provides a full object-relational mapping layer with managed entities and second-level caching. Storm takes a fundamentally different approach: entities are plain values with no managed state, and database interactions are explicit rather than implicit. This makes Storm simpler to reason about at the cost of JPA's more automated (but less predictable) features.

| Aspect | Storm | JPA/Hibernate                            |
|--------|-------|------------------------------------------|
| **Entities** | Immutable records/data classes | Mutable classes with getters/setters     |
| **Polymorphism** | Sealed types (Single-Table, Joined, Polymorphic FK); STRING, INTEGER, CHAR discriminators | Class hierarchy (Single-Table, Joined, Table-per-Class); STRING, INTEGER, CHAR discriminators |
| **State** | Stateless; no persistence context | Managed entities                         |
| **Loading** | Loading in single query | Lazy loading common              |
| **N+1 Problem** | Prevented by design; requires explicit opt-in | Common pitfall                           |
| **Queries** | Type-safe DSL, SQL Templates | JPQL, Criteria API                       |
| **Caching** | Transaction-scoped observation | First/second level cache                 |
| **Transactions** | Programmatic + `@Transactional` (Spring) | `@Transactional`, JTA, container-managed |
| **Schema Validation** | Programmatic + Spring Boot | `ddl-auto=validate` |
| **Learning Curve** | Gentle; SQL-like | Steep; many concepts                     |
| **Magic** | What you see is what you get | Proxies, bytecode enhancement            |

**Polymorphism differences.** Storm and JPA overlap on Single-Table and Joined Table, but diverge beyond that. Storm adds [Polymorphic FK](polymorphism.md#polymorphic-foreign-keys), a two-column foreign key (type + id) that references independent tables with no shared base. This has no JPA equivalent (Hibernate offers the non-standard `@Any` annotation for a similar purpose). JPA adds Table-per-Class, which duplicates all fields into per-subtype tables and queries the base type via `UNION ALL`, and multi-level inheritance (e.g., `Animal` → `Pet` → `Cat`). Storm intentionally limits hierarchies to a single sealed level, which covers the vast majority of real-world use cases while keeping SQL generation predictable.

### When to Choose Storm

- You want predictable, explicit database behavior
- You want concise entity definitions with minimal boilerplate
- N+1 queries have been a recurring problem
- You prefer immutable data structures
- You value simplicity over complexity
- You want a lightweight, minimal dependency footprint
- You're using Kotlin and want idiomatic APIs

### When to Choose JPA/Hibernate

- You rely on second-level caching
- You have complex multi-level inheritance hierarchies (Storm supports [single-level sealed type polymorphism](polymorphism.md))
- You have an existing JPA codebase to maintain
- You need JPA compliance for vendor reasons
- You want access to a large community and extensive resources

## Storm vs Spring Data JPA

Spring Data JPA wraps JPA with a repository abstraction that derives query implementations from method names. It reduces boilerplate but inherits all of JPA's runtime complexity (proxies, managed state, lazy loading). Storm's Spring integration provides similar repository convenience with explicit query bodies instead of naming conventions.

| Aspect | Storm | Spring Data JPA |
|--------|-------|-----------------|
| **Foundation** | Custom ORM | JPA/Hibernate |
| **Polymorphism** | Sealed types (Single-Table, Joined, Polymorphic FK) | Via JPA |
| **Repositories** | Interface with default methods | Interface with method naming, `@Query` |
| **Query Methods** | Explicit DSL in method body | Derived from method names, `@Query` |
| **Entities** | Records/data classes | JPA entities |
| **State** | Stateless | Managed |
| **Transactions** | Programmatic + `@Transactional` (Spring) | `@Transactional` (Spring-managed) |

### When to Choose Storm

- You want stateless, immutable entities with minimal boilerplate
- You prefer explicit query logic over naming conventions
- You want to avoid JPA's complexity
- You want a lightweight, minimal dependency footprint

### When to Choose Spring Data JPA

- You need JPA features (lazy loading, caching)
- You like query derivation from method names
- You're already invested in the JPA ecosystem

## Storm vs MyBatis

MyBatis is a SQL mapper that gives you full control over every query. You write SQL in XML files or annotations and map results to POJOs manually. Storm sits at a higher abstraction level, inferring SQL from entity definitions while still allowing raw SQL when needed. The trade-off is flexibility vs. automation: MyBatis never generates SQL for you, while Storm handles the common cases and lets you drop to raw SQL for complex queries.

| Aspect | Storm | MyBatis |
|--------|-------|---------|
| **Approach** | Stateless ORM | SQL mapper |
| **Polymorphism** | Sealed types (Single-Table, Joined, Polymorphic FK) | Manual (via SQL) |
| **SQL Definition** | Inferred from entities, SQL Templates (optional) | XML files or annotations |
| **Result Mapping** | Automatic from entity definitions | Manual XML/annotation mapping |
| **Entities** | Records/data classes with annotations | POJOs, manual mapping |
| **Relationships** | Automatic via `@FK` | Manual nested queries/joins |
| **Type Safety** | Compile-time checked | String SQL, typed result mapping |
| **N+1 Problem** | Prevented by design; requires explicit opt-in | Manual optimization |
| **Transactions** | Programmatic + `@Transactional` (Spring) | Manual or Spring `@Transactional` |
| **Dynamic SQL** | Kotlin/Java code | XML tags (`<if>`, `<foreach>`) |
| **Learning Curve** | Gentle; annotation-based | Moderate; XML knowledge helpful |

### When to Choose Storm

- You want automatic entity mapping without XML and minimal boilerplate
- You prefer type-safe queries over string SQL
- You want relationships handled automatically
- You value compile-time safety
- You're starting a new project without legacy SQL

### When to Choose MyBatis

- You have complex SQL that doesn't fit ORM patterns
- You need fine-grained control over every query
- You're working with legacy databases or stored procedures
- You need XML-based SQL externalization

## Storm vs jOOQ

jOOQ generates Java code from your database schema, providing a type-safe SQL DSL that mirrors the structure of your tables. Storm also treats the database schema as the source of truth, but instead of generating code from the schema, you write entity definitions that reflect it, and the metamodel is generated from those entities. Both frameworks provide compile-time type safety, but queries look very different. jOOQ excels at complex SQL (window functions, CTEs, recursive queries) where its DSL closely follows SQL syntax, but this means every join, column reference, and condition must be spelled out explicitly. Storm queries are more concise: the metamodel and automatic join derivation from `@FK` annotations let you write queries that focus on what you want rather than how to join it. Storm excels at entity-oriented operations where automatic relationship handling and repository patterns reduce boilerplate.

| Aspect | Storm | jOOQ |
|--------|-------|------|
| **Approach** | Schema-reflective ORM | Schema-driven code generation |
| **Polymorphism** | Sealed types (Single-Table, Joined, Polymorphic FK) | Manual (via SQL DSL) |
| **Type Safety** | Metamodel from entities | Generated from schema |
| **Setup** | Define entities, code generation | Schema, code generation |
| **Entities** | Records/data classes with `Entity` | Records or POJOs |
| **Query Style** | Repository + ORM DSL + SQL Templates | SQL-like DSL |
| **Query Verbosity** | Concise; auto joins from `@FK`, metamodel shortcuts | Verbose; explicit joins, columns, and conditions |
| **Relationships** | Automatic from `@FK` | Manual joins |
| **Transactions** | Programmatic + `@Transactional` (Spring) | DSL context, Spring integration |
| **License** | Apache 2.0 | Commercial for some DBs |

### When to Choose Storm

- You prefer writing entity definitions that reflect the schema over generating code from it
- You want concise, type-safe queries with automatic join derivation
- You want automatic relationship handling
- You value convention over configuration
- You need a fully open-source solution

### When to Choose jOOQ

- You prefer pure SQL control
- You want native DSL support for advanced SQL features (window functions, CTEs)
- You want a thin layer over SQL with minimal runtime overhead

## Storm vs JDBI

JDBI is a lightweight SQL convenience library that sits just above JDBC. It handles parameter binding, result mapping, and connection management without imposing an object model. Storm provides more structure with entity definitions, automatic relationship loading, and a repository pattern. Choose JDBI when you want minimal abstraction and full SQL control; choose Storm when you want the framework to handle common patterns while still allowing raw SQL escape hatches.

| Aspect | Storm | JDBI |
|--------|-------|------|
| **Level** | Stateless ORM | Low-level SQL mapping |
| **Polymorphism** | Sealed types (Single-Table, Joined, Polymorphic FK) | Manual |
| **Entities** | Automatic from annotations | Manual mapping |
| **Relationships** | Automatic via `@FK` | Manual |
| **Type Safety** | Metamodel DSL | String SQL |
| **Transactions** | Programmatic + `@Transactional` (Spring) | Manual, `@Transaction` annotation |

### When to Choose Storm

- You want automatic entity mapping with concise entity definitions
- You need relationship handling
- You prefer type-safe queries over raw SQL

### When to Choose JDBI

- You want full SQL control
- You prefer minimal abstraction
- You have mostly complex queries that don't fit ORM patterns

---

## Kotlin-Only Frameworks

The following frameworks are Kotlin-only. Storm supports both Kotlin and Java.

## Storm vs Exposed

Exposed is JetBrains' official Kotlin database framework. It offers two APIs: a DSL that mirrors SQL syntax and a DAO layer for ORM-style access. Exposed defines tables as Kotlin objects rather than annotations on data classes. Storm and Exposed share the goal of idiomatic Kotlin database access but differ in entity design (mutable DAO entities vs. immutable data classes) and relationship loading strategy (lazy references vs. eager single-query loading).

| Aspect | Exposed | Storm                                |
|--------|---------|--------------------------------------|
| **Language** | Kotlin only | Kotlin + Java                        |
| **Polymorphism** | No | Sealed types (Single-Table, Joined, Polymorphic FK) |
| **APIs** | DSL (SQL) + DAO (ORM) | Unified ORM + SQL Templates          |
| **Table Definition** | DSL objects (`object Users : Table()`) | Annotations on data classes          |
| **Entities (DAO)** | Mutable, extend `Entity` class | Immutable data classes (Kotlin) / records (Java) |
| **Relationships** | Lazy references, manual loading | Loading in single query              |
| **N+1 Problem** | Possible with DAO | Prevented by design; requires explicit opt-in                 |
| **Coroutines** | Supported (added later) | First-class from the start           |
| **Type Safety** | Column references | Metamodel DSL                        |
| **Transactions** | `transaction {}` block, declarative via Spring module | Optional, programmatic + declarative |

#### Transaction Propagation

Both Storm and Exposed use a `transaction { }` block for programmatic transaction management, but they differ significantly in propagation support.

Exposed's native API supports two modes: shared nesting (the default, where inner blocks join the outer transaction) and savepoint-based nesting (via `useNestedTransactions = true`). For other propagation behaviors, Exposed relies on Spring's `@Transactional` through its `SpringTransactionManager` integration module.

Storm supports all seven standard propagation modes natively in its `transaction { }` block, without requiring Spring:

| Propagation | Storm | Exposed |
|-------------|-------|---------|
| `REQUIRED` | Yes | Yes (default behavior) |
| `REQUIRES_NEW` | Yes | No (Spring only) |
| `NESTED` | Yes | Yes (`useNestedTransactions`) |
| `MANDATORY` | Yes | No |
| `SUPPORTS` | Yes | No |
| `NOT_SUPPORTED` | Yes | No |
| `NEVER` | Yes | No |

This means Storm's programmatic API can express patterns like audit logging (`REQUIRES_NEW`), defensive boundary enforcement (`MANDATORY`, `NEVER`), and non-transactional operations (`NOT_SUPPORTED`) directly in code, while Exposed requires Spring integration for these use cases. See [Transactions](transactions.md) for details and examples of each propagation mode.

#### Transaction Callbacks

Both frameworks allow running logic after a transaction commits or rolls back, but the APIs differ significantly.

Storm provides `onCommit` and `onRollback` callbacks on the `Transaction` object. Callbacks accept suspend functions, execute in registration order, and are resilient to individual failures (remaining callbacks still run). When a callback is registered inside a joined scope (`REQUIRED`, `NESTED`), it is automatically deferred to the outermost physical transaction's commit or rollback, so it only fires when data is actually durable:

```kotlin
transaction {
    orderRepository.insert(order)
    onCommit { emailService.sendConfirmation(order) }  // Fires after physical commit
}
```

Exposed uses a `StatementInterceptor` interface with lifecycle methods (`beforeCommit`, `afterCommit`, `beforeRollback`, `afterRollback`, among others) that is registered on the transaction via `registerInterceptor()`. Global interceptors can be registered via Java `ServiceLoader`. This approach is well suited for cross-cutting concerns that apply to many transactions:

```kotlin
transaction {
    // Exposed: register an interceptor
    registerInterceptor(object : StatementInterceptor {
        override fun afterCommit(transaction: Transaction) {
            emailService.sendConfirmation(order)
        }
    })
    OrderTable.insert { it[id] = order.id }
}
```

| Aspect | Storm | Exposed |
|--------|-------|---------|
| API style | Lambda (`onCommit { }`) | Interface (`StatementInterceptor`) |
| Suspend support | Yes (JDBC) | R2DBC only (`SuspendStatementInterceptor`) |
| Nested transaction behavior | Deferred to physical commit | Fires after savepoint release (data not yet durable) |
| Callback isolation | Yes (remaining callbacks still run on failure) | No (exception propagates, skipping remaining interceptors) |
| Global interceptors | No | Yes (via `ServiceLoader`) |
| Additional hooks | No | `beforeCommit`, `beforeRollback`, `beforeExecution`, `afterExecution` |

The most significant behavioral difference is with nested transactions. Exposed's `afterCommit` fires on the nested transaction's own "commit," which for savepoint-based nesting is just a savepoint release, not an actual database commit. If the outer transaction subsequently rolls back, the `afterCommit` callback will have already executed despite the data never becoming durable. Storm avoids this by deferring callbacks to the outermost physical transaction.

Storm's callback isolation behavior (remaining callbacks still execute when one fails) follows the same approach as Spring's `TransactionSynchronization`, where post-commit and post-completion callbacks are invoked independently. Since callbacks fire after the transaction outcome is final, there is nothing to undo; silently skipping remaining side effects because of one failure would be worse than running them all and surfacing the first exception.

Exposed's `StatementInterceptor` also provides hooks that Storm intentionally does not offer: `beforeCommit`, `beforeRollback`, and statement-level interceptors (`beforeExecution`, `afterExecution`). In Storm's stateless model, pre-commit logic belongs at the end of the `transaction { }` block itself, since there is no persistence context to flush or dirty state to reconcile before the commit. Statement-level observability is covered by Storm's [`@SqlLog`](sql-logging.md) annotation and `SqlCapture` test utility rather than a general interceptor mechanism.

#### Schema Migration

Exposed provides built-in schema management through its `SchemaUtils` utility. You can create tables, add missing columns, and generate migration statements programmatically:

```kotlin
transaction {
    SchemaUtils.create(UsersTable, OrdersTable)           // CREATE TABLE IF NOT EXISTS
    SchemaUtils.createMissingTablesAndColumns(UsersTable)  // ALTER TABLE ADD COLUMN ...
    SchemaUtils.statementsRequiredToActualizeScheme()      // Returns DDL statements without executing
}
```

This is convenient for prototyping and simple applications. For production use, JetBrains recommends pairing Exposed with a dedicated migration tool like Flyway or Liquibase, since `SchemaUtils` does not handle column removal, type changes, or data migration.

Storm does not include schema management or migration utilities. Schema management is expected to be handled externally using tools like Flyway, Liquibase, or plain SQL scripts. Storm's [schema validation](validation.md) feature can verify at startup that entity definitions match the database schema, catching mismatches early without modifying the schema itself.

### When to Choose Storm

- You need Kotlin and Java support
- You want concise, immutable entities without base class inheritance
- You prefer annotation-based entity definitions
- N+1 queries are a concern
- You want relationships loaded automatically
- You need full support for transaction propagation modes

### When to Choose Exposed

- You're building a Kotlin-only project
- You prefer DSL-based table definitions
- You want to switch between SQL DSL and DAO styles
- You like the JetBrains ecosystem integration
- You need fine-grained control over lazy loading
- You need R2DBC support for reactive database access*

*Storm uses JDBC and relies on JVM virtual threads for non-blocking I/O instead of R2DBC.

## Storm vs Ktorm

Ktorm is a lightweight Kotlin ORM that uses entity interfaces and DSL-based table definitions. It requires no code generation and has minimal dependencies. Storm differs primarily in its use of immutable data classes (instead of mutable interfaces), automatic relationship loading, and optional metamodel generation for compile-time type safety.

| Aspect | Ktorm | Storm |
|--------|-------|-------|
| **Language** | Kotlin only | Kotlin + Java |
| **Polymorphism** | No | Sealed types (Single-Table, Joined, Polymorphic FK) |
| **Entities** | Interfaces extending `Entity` | Data classes with annotations |
| **Table Definition** | DSL objects (`object Users : Table<User>`) | Annotations on data classes |
| **Query Style** | Sequence API, DSL | ORM DSL + SQL Templates |
| **Relationships** | References, manual loading | Automatic loading |
| **N+1 Problem** | Possible | Prevented by design; requires explicit opt-in |
| **Code Generation** | None required | Optional metamodel |
| **Immutability** | Mutable entity interfaces | Immutable data classes |
| **Coroutines** | Limited | First-class support |
| **Transactions** | `useTransaction {}` block | Programmatic + `@Transactional` (Spring) |

### When to Choose Storm

- You need Kotlin and Java support
- You want concise, immutable data classes instead of mutable interfaces
- You prefer annotation-based definitions
- N+1 prevention is important
- You want automatic relationship loading

### When to Choose Ktorm

- You're building a Kotlin-only project
- You prefer no code generation
- You like the Sequence API style
- You prefer DSL-based table definitions

## Summary

Storm is a newer framework, so community resources and third-party tutorials are still growing. However, the API is designed to be intuitive for developers familiar with SQL and Kotlin and modern Java.

Choose Storm if you value:
- **Simplicity** over complexity
- **Predictability** over magic
- **Immutability** over managed state
- **Explicit** over implicit behavior
- **Kotlin and modern Java** development with first-class support for both

Ready to try it? See the [Getting Started](getting-started.md) guide.

## Framework Links

- [Hibernate ORM](https://hibernate.org/orm/)
- [Spring Data JPA](https://spring.io/projects/spring-data-jpa)
- [MyBatis](https://mybatis.org/mybatis-3/)
- [jOOQ](https://www.jooq.org/)
- [JDBI](https://jdbi.org/)
- [Exposed](https://github.com/JetBrains/Exposed)
- [Ktorm](https://www.ktorm.org/)
