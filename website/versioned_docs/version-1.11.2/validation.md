import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Validation

Storm validates your entity and projection definitions at two levels: structural validation ensures your records follow the ORM's rules (valid primary key types, correct use of annotations, no circular dependencies), while schema validation compares your definitions against the actual database to catch mismatches before they surface as runtime errors.

Both levels are optional and configurable. Structural validation runs automatically on first use; schema validation must be explicitly enabled.

---

## Record Validation

When Storm first encounters an entity or projection type, it inspects the record structure and validates that the definition is well-formed. This catches common modeling mistakes early, at startup rather than at query time.

### What Gets Checked

**Primary key rules:**

- The `@PK` type must be one of: `boolean`, `int`, `long`, `short`, `String`, `UUID`, `BigInteger`, `Enum`, or `Ref`. Floating-point types (`float`, `double`, `BigDecimal`) are rejected because they cannot reliably serve as identity values.
- Compound keys (inline records annotated with `@PK`) follow the same type restrictions for each component.

**Foreign key rules:**

- Fields annotated with `@FK` must be a `Data` type (entity, projection, or data class with a `@PK`) or a `Ref` wrapping such a type. Scalars like `String` or `Integer` cannot be foreign keys.
- Auto-generated foreign keys (`@FK(generation = ...)`) cannot be inlined.

**Inline component rules:**

- Fields annotated with `@Inline` must be record types. Scalars cannot be inlined.
- Inline records must not declare their own `@PK`, since they are embedded within a parent entity.

**Version fields:**

- At most one field per entity can be annotated with `@Version`. Multiple version fields are rejected.

**Structural integrity:**

- Records must be immutable. Mutable fields (Kotlin `var`) are rejected.
- Entities or projections that contain other entities or projections must annotate them as `@FK` or `@Inline`. Storm needs to know the relationship type to generate correct SQL.
- The record graph is checked for cycles. If entity A inlines entity B, which inlines entity A, the circular dependency is reported.

### Configuration

Record validation runs by default and causes startup to fail on the first error. The `record-mode` property controls this behavior:

| Value | Behavior |
|-------|----------|
| `fail` | Validation errors cause startup to fail (default). |
| `warn` | Errors are logged as warnings; startup continues. |
| `none` | Record validation is skipped entirely. |

This can be set as a system property, via `StormConfig`, or in Spring Boot's `application.yml`:

```yaml
storm:
  validation:
    record-mode: fail   # or "warn" or "none" (default: fail)
```

---

## Schema Validation

Schema validation compares your entity and projection definitions against the actual database schema. It catches mismatches before they surface as runtime errors, similar to Hibernate's `ddl-auto=validate`. Storm never modifies the schema; it only reports mismatches.

### What Gets Checked

| Check | Error Kind | Severity |
|-------|-----------|----------|
| Table exists in the database | `TABLE_NOT_FOUND` | Error |
| Each mapped column exists in the table | `COLUMN_NOT_FOUND` | Error |
| Kotlin/Java type is compatible with the SQL column type | `TYPE_INCOMPATIBLE` | Error |
| Entity primary key columns match the database primary key | `PRIMARY_KEY_MISMATCH` | Error |
| `@FK` constraint references the correct target table | `FOREIGN_KEY_MISMATCH` | Error |
| Sequences referenced by `@PK(generation = SEQUENCE)` exist | `SEQUENCE_NOT_FOUND` | Error |
| | | |
| Numeric cross-category conversions (e.g., `Integer` mapped to `DECIMAL`) | `TYPE_NARROWING` | Warning |
| Non-nullable entity field mapped to a nullable database column | `NULLABILITY_MISMATCH` | Warning |
| Entity declares `@PK` but the database has no primary key constraint | `PRIMARY_KEY_MISSING` | Warning |
| `@UK` field has a matching unique constraint in the database | `UNIQUE_KEY_MISSING` | Warning |
| `@FK` field has a matching foreign key constraint in the database | `FOREIGN_KEY_MISSING` | Warning |

**Errors** indicate definitive mismatches that will cause runtime failures, such as missing tables or columns.

**Warnings** indicate situations where the mapping works at runtime but may involve subtle differences, such as precision loss when mapping a Kotlin `Int` to an Oracle `NUMBER` column. Warnings are logged but do not cause validation to fail (unless strict mode is enabled).

#### Constraint Validation

Schema validation checks that the database has the constraints your entity model declares. There are two categories of constraint findings:

**Mismatches (errors)** occur when a constraint exists in the database but contradicts the entity definition. For example, if `@FK val city: City` expects a foreign key referencing the `city` table, but the database has a foreign key on that column referencing the `account` table, that is a `FOREIGN_KEY_MISMATCH`. Similarly, if the entity declares `@PK` with columns `(id)` but the database primary key is `(user_id, role_id)`, that is a `PRIMARY_KEY_MISMATCH`. Mismatches are always hard errors because they indicate a bug in the entity definition.

**Missing constraints (warnings)** occur when the database has no constraint at all for a declared `@PK`, `@FK`, or `@UK` field. These are warnings rather than errors because the ORM functions correctly without database-level enforcement: queries return the same results, inserts and updates succeed, and scrolling works as expected.

However, database constraints serve as a safety net that the application layer cannot replace:

- **Primary key constraints** ensure row uniqueness at the database level. Without one, duplicate primary key values could be inserted by other applications or direct SQL.
- **Unique constraints** protect against application bugs and concurrent modifications that could insert duplicate values. Without a database-level unique constraint, a `@UK` field might contain duplicates that go undetected until a `findBy` call unexpectedly returns multiple results.
- **Foreign key constraints** protect referential integrity. Without a database-level foreign key constraint, orphaned rows can accumulate when referenced rows are deleted.

##### Suppressing Constraint Warnings

When the database intentionally omits a constraint (for performance, for views, or because integrity is enforced at the application level), use the `constraint` attribute to suppress the warning for that specific field:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// No FK constraint for performance reasons.
data class Order(
    @PK val id: Int = 0,
    @FK(constraint = false) val customer: Customer
) : Entity<Int>

// No unique index in the database.
data class User(
    @PK val id: Int = 0,
    @UK(constraint = false) val email: String
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
// No FK constraint for performance reasons.
record Order(@PK Integer id,
             @FK(constraint = false) Customer customer
) implements Entity<Integer> {}

// No unique index in the database.
record User(@PK Integer id,
            @UK(constraint = false) String email
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

Setting `constraint = false` only suppresses the "missing" warning. If the database *does* have a constraint that contradicts the entity definition (a mismatch), it is always reported as a hard error regardless of this flag.

In [strict mode](#strict-mode), missing constraint warnings are promoted to errors, causing validation to fail. The `constraint = false` flag takes precedence: fields marked with it are excluded from validation even in strict mode.

### Programmatic API

Any `ORMTemplate` created from a `DataSource` supports schema validation:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val orm = dataSource.orm

// Inspect errors programmatically
val errors: List<String> = orm.validateSchema()

// Or validate and throw on failure
orm.validateSchemaOrThrow()
```

</TabItem>
<TabItem value="java" label="Java">

```java
var orm = ORMTemplate.of(dataSource);

// Inspect errors programmatically
List<String> errors = orm.validateSchema();

// Or validate and throw on failure
orm.validateSchemaOrThrow();
```

</TabItem>
</Tabs>

Both methods have overloads that accept specific types to validate:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.validateSchema(User::class, Order::class)
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.validateSchema(List.of(User.class, Order.class));
```

</TabItem>
</Tabs>

The no-argument variants discover all entity and projection types on the classpath automatically.

On success, a confirmation message is logged at INFO level. On failure, each error is logged at ERROR level, and `validateSchemaOrThrow()` throws a `PersistenceException` with a summary of all errors. Warnings are always logged at WARN level regardless of the outcome.

Templates created from a raw `Connection` or JPA `EntityManager` do not support schema validation, since they lack the `DataSource` needed to query database metadata.

### Strict Mode

By default, warnings (type narrowing and nullability mismatches) do not cause validation to fail. In strict mode, all findings are treated as errors:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val config = StormConfig.of(mapOf(VALIDATION_STRICT to "true"))
val orm = ORMTemplate.of(dataSource, config)
orm.validateSchemaOrThrow()  // Warnings now cause failure
```

</TabItem>
<TabItem value="java" label="Java">

```java
var config = StormConfig.of(Map.of(VALIDATION_STRICT, "true"));
var orm = ORMTemplate.of(dataSource, config);
orm.validateSchemaOrThrow();  // Warnings now cause failure
```

</TabItem>
</Tabs>

### Suppressing Validation with @DbIgnore

Use `@DbIgnore` to suppress schema validation for specific entities or fields. This is useful for legacy tables, columns handled by custom converters, or known mismatches that are safe to ignore.

**Suppress validation for an entire entity:**

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbIgnore
data class LegacyUser(
    @PK val id: Int = 0,
    val name: String
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbIgnore
record LegacyUser(@PK Integer id,
                  @Nonnull String name
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

**Suppress validation for a specific field:**

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    val name: String,
    @DbIgnore("DB uses FLOAT, but column only stores whole numbers")
    val age: Int
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record User(@PK Integer id,
            @Nonnull String name,
            @DbIgnore("DB uses FLOAT, but column only stores whole numbers")
            @Nonnull Integer age
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

The optional `value` parameter documents why the mismatch is acceptable. When `@DbIgnore` is placed on an inline component field, validation is suppressed for all columns within that component.

### Custom Schemas

Schema validation respects `@DbTable(schema = "...")`. Each entity is validated against the schema specified in its annotation, or the connection's default schema if none is specified.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable(schema = "reporting")
data class Report(
    @PK val id: Int = 0,
    val name: String
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable(schema = "reporting")
record Report(@PK Integer id,
              @Nonnull String name
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

### Spring Boot Configuration

When using the Spring Boot Starter, both record and schema validation can be configured through `application.yml`:

```yaml
storm:
  validation:
    record-mode: fail   # or "warn" or "none" (default: fail)
    schema-mode: none   # or "warn" or "fail" (default: none)
    strict: false       # treat schema warnings as errors (default: false)
```

The `schema-mode` values:

| Value | Behavior |
|-------|----------|
| `none` | Schema validation is skipped (default). |
| `warn` | Mismatches are logged at WARN level; startup continues. |
| `fail` | Mismatches cause startup to fail with a `PersistenceException`. |

### Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `storm.validation.record_mode` | `fail` | Record validation mode: `fail`, `warn`, or `none` |
| `storm.validation.schema_mode` | `none` | Schema validation mode: `none`, `warn`, or `fail` (Spring Boot only) |
| `storm.validation.strict` | `false` | When `true`, schema validation warnings are treated as errors |
