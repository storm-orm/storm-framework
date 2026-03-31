# Entities

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Storm entities are simple data classes that map to database tables. By default, Storm applies sensible naming conventions to map entity fields to database columns automatically.

---

## Defining Entities

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use Kotlin data classes with the `Entity` interface:

```kotlin
data class City(
    @PK val id: Int = 0,
    val name: String,
    val population: Long
) : Entity<Int>

data class User(
    @PK val id: Int = 0,
    val email: String,
    val birthDate: LocalDate,
    val street: String,
    val postalCode: String?,
    @FK val city: City
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

Use Java records with the `Entity` interface:

```java
record City(@PK Integer id,
            String name,
            long population
) implements Entity<Integer> {}

record User(@PK Integer id,
            String email,
            LocalDate birthDate,
            String street,
            String postalCode,
            @FK City city
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Entity Interface

Implementing the `Entity<ID>` interface is optional but required for using `EntityRepository` with built-in CRUD operations. The type parameter specifies the primary key type. Without this interface, you can still use Storm's SQL template features and query builder, but you lose the convenience methods like `findById`, `insert`, `update`, and `remove`. If you only need read access, consider using `Projection<ID>` instead (see [Projections](projections.md)).

Storm also supports polymorphic entity hierarchies using sealed interfaces. A sealed interface extending `Entity` can define multiple record subtypes, enabling Single-Table or Joined Table inheritance with compile-time exhaustive pattern matching. See [Polymorphism](polymorphism.md) for details.

---

## Nullability

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Kotlin's type system maps directly to Storm's null handling. A non-nullable field produces an `INNER JOIN` for foreign keys and a `NOT NULL` expectation for columns. A nullable field produces a `LEFT JOIN` for foreign keys and allows `NULL` values from the database. This means your entity definition fully describes the expected schema constraints.

Use nullable types (`?`) to indicate nullable fields:

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,           // Non-nullable
    val birthDate: LocalDate,    // Non-nullable
    val postalCode: String?,     // Nullable
    @FK val city: City?          // Nullable (results in LEFT JOIN)
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

In Java, record components are nullable by default. Use `@Nonnull` to mark fields that must always have a value. Primitive types (`int`, `long`, etc.) are inherently non-nullable. As with Kotlin, nullability determines JOIN behavior: a non-nullable `@FK` field produces an `INNER JOIN`, while a `@Nullable` one produces a `LEFT JOIN`.

```java
record User(@PK Integer id,
            @Nonnull String email,        // Non-nullable
            @Nonnull LocalDate birthDate, // Non-nullable
            String postalCode,            // Nullable (default)
            @Nullable @FK City city       // Nullable (results in LEFT JOIN)
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Primary Key Generation

The `@PK` annotation supports a `generation` parameter that controls how primary key values are generated:

| Strategy | Description |
|----------|-------------|
| `IDENTITY` | Database generates the key using an identity/auto-increment column (default) |
| `SEQUENCE` | Database generates the key using a named sequence |
| `NONE` | No generation; the caller must provide the key value |

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

**IDENTITY (default):**

```kotlin
data class User(
    @PK val id: Int = 0,  // Database generates via auto-increment
    val name: String
) : Entity<Int>
```

When inserting, Storm omits the PK column and retrieves the generated value:

```kotlin
val user = User(name = "Alice")
val inserted = orm.insert(user)  // Returns User with generated id
```

**SEQUENCE:**

```kotlin
data class Order(
    @PK(generation = SEQUENCE, sequence = "order_seq") val id: Long = 0,
    val total: BigDecimal
) : Entity<Long>
```

Storm fetches the next value from the sequence before inserting.

**NONE:**

```kotlin
data class Country(
    @PK(generation = NONE) val code: String,  // Caller provides the value
    val name: String
) : Entity<String>
```

Use `NONE` when:
- The key is a natural key (like country codes or UUIDs)
- The key comes from an external source
- The primary key is also a foreign key (see [Primary Key as Foreign Key](relationships.md#primary-key-as-foreign-key))

</TabItem>
<TabItem value="java" label="Java">

**IDENTITY (default):**

```java
record User(@PK Integer id,  // Database generates via auto-increment
            @Nonnull String name
) implements Entity<Integer> {}
```

When inserting, Storm omits the PK column and retrieves the generated value:

```java
var user = new User(null, "Alice");
var inserted = orm.entity(User.class).insert(user);  // Returns User with generated id
```

**SEQUENCE:**

```java
record Order(@PK(generation = SEQUENCE, sequence = "order_seq") Long id,
             @Nonnull BigDecimal total
) implements Entity<Long> {}
```

Storm fetches the next value from the sequence before inserting.

**NONE:**

```java
record Country(@PK(generation = NONE) String code,  // Caller provides the value
               @Nonnull String name
) implements Entity<String> {}
```

Use `NONE` when:
- The key is a natural key (like country codes or UUIDs)
- The key comes from an external source
- The primary key is also a foreign key (see [Primary Key as Foreign Key](relationships.md#primary-key-as-foreign-key))

</TabItem>
</Tabs>

---

## Composite Primary Keys

For join tables or entities whose identity is defined by a combination of columns, wrap the key fields in a separate data class and annotate it with `@PK`. Storm treats all fields in the composite key class as part of the primary key.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class UserRolePk(
    val userId: Int,
    val roleId: Int
)

data class UserRole(
    @PK val userRolePk: UserRolePk,
    @FK val user: User,
    @FK val role: Role
) : Entity<UserRolePk>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record UserRolePk(int userId, int roleId) {}

record UserRole(@PK UserRolePk userRolePk,
                @Nonnull @FK User user,
                @Nonnull @FK Role role
) implements Entity<UserRolePk> {}
```

</TabItem>
</Tabs>

---

## Foreign Keys

The `@FK` annotation marks a field as a foreign key reference to another table-backed type (entity, projection, or data class with a `@PK`). Storm uses these annotations to automatically generate JOINs when querying and to derive column names (by default, appending `_id` to the field name).

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @FK val city: City        // Always loaded via INNER JOIN
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record User(@PK Integer id,
            String email,
            @FK City city        // Always loaded via INNER JOIN
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

:::tip When to use `Ref<T>` vs the full entity type
Use the full entity type (e.g., `@FK val city: City`) when you always want the related entity loaded. Use `Ref<T>` (e.g., `@FK val city: Ref<City>`) when you only sometimes need the related entity, when the relationship is optional, or to prevent circular dependencies. See [Refs](refs.md) for details.
:::

---

## Unique Keys

Use `@UK` on fields that have a unique constraint in the database. The `@PK` annotation implies `@UK`, so primary key fields are automatically unique. Annotating a field with `@UK` tells Storm that the column contains unique values, which enables several framework features:

1. **Type-safe lookups.** `findBy(Key, value)` and `getBy(Key, value)` return a single result without requiring a predicate. The metamodel processor generates `Metamodel.Key` instances for `@UK` fields. See [Metamodel](metamodel.md#unique-keys-uk-and-metamodelkey) for details.
2. **Scrolling.** `@UK` fields can serve as cursor columns for `scroll(Scrollable)`. Because the values are unique, the cursor position is always unambiguous. See [Scrolling](pagination-and-scrolling.md#scrolling).
3. **Schema validation.** When [schema validation](validation.md) is enabled, Storm checks that the database actually has a matching unique constraint for each `@UK` field and reports a warning if it is missing.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class User(
    @PK val id: Int = 0,
    @UK val email: String,
    val name: String
) : Entity<Int>
```

For compound unique constraints spanning multiple columns, use an inline record annotated with `@UK`. When the compound key columns overlap with other fields on the entity, use `@Persist(insertable = false, updatable = false)` to prevent duplicate persistence:

```kotlin
data class UserEmailUK(val userId: Int, val email: String)

data class SomeEntity(
    @PK val id: Int = 0,
    @FK val user: User,
    val email: String,
    @UK @Persist(insertable = false, updatable = false) val uniqueKey: UserEmailUK
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record User(@PK Integer id,
            @UK String email,
            String name
) implements Entity<Integer> {}
```

For compound unique constraints spanning multiple columns, use an inline record annotated with `@UK`. When the compound key columns overlap with other fields on the entity, use `@Persist(insertable = false, updatable = false)` to prevent duplicate persistence:

```java
record UserEmailUK(int userId, String email) {}

record SomeEntity(@PK Integer id,
                  @Nonnull @FK User user,
                  @Nonnull String email,
                  @UK @Persist(insertable = false, updatable = false) UserEmailUK uniqueKey
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

When a column is not annotated with `@UK` but becomes unique in a specific query context (for example, a GROUP BY column produces unique values in the result set), wrap the metamodel with `.key()` (Kotlin) or `Metamodel.key()` (Java) to indicate it can serve as a scrolling cursor. See [Manual Key Wrapping](metamodel.md#manual-key-wrapping) for details.

---

## Embedded Components

Embedded components group related fields into a reusable data class without creating a separate database table. The component's fields are stored as columns in the parent entity's table. This is useful for value objects like addresses, coordinates, or monetary amounts that appear in multiple entities.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use data classes for embedded components:

```kotlin
data class Address(
    val street: String? = null,
    @FK val city: City? = null
)

data class Owner(
    @PK val id: Int = 0,
    val firstName: String,
    val lastName: String,
    val address: Address,
    val telephone: String?
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

Use records for embedded components:

```java
record Address(String street,
               @FK City city) {}

record Owner(@PK Integer id,
             @Nonnull String firstName,
             @Nonnull String lastName,
             @Nonnull Address address,
             @Nullable String telephone
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

### `@Persist` Propagation on Embedded Components

When `@Persist` is placed on an embedded component field, it propagates to all child fields within that component. This is useful when the embedded component's columns overlap with other fields on the entity and should not be persisted separately. Child fields can override the inherited `@Persist` with their own annotation.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class OwnerCityKey(val ownerId: Int, val cityId: Int)

data class Pet(
    @PK val id: Int = 0,
    val name: String,
    @FK val owner: Owner,
    @FK val city: City,
    @Persist(insertable = false, updatable = false) val ownerCityKey: OwnerCityKey
) : Entity<Int>
```

In this example, the `owner` and `city` foreign keys define the actual persisted columns. The `ownerCityKey` inline record maps to the same underlying columns but is excluded from INSERT and UPDATE statements because its child fields inherit `@Persist(insertable = false, updatable = false)` from the parent field.

</TabItem>
<TabItem value="java" label="Java">

```java
record OwnerCityKey(int ownerId, int cityId) {}

record Pet(@PK Integer id,
           @Nonnull String name,
           @Nonnull @FK Owner owner,
           @Nonnull @FK City city,
           @Persist(insertable = false, updatable = false) OwnerCityKey ownerCityKey
) implements Entity<Integer> {}
```

In this example, the `owner` and `city` foreign keys define the actual persisted columns. The `ownerCityKey` inline record maps to the same underlying columns but is excluded from INSERT and UPDATE statements because its child fields inherit `@Persist(insertable = false, updatable = false)` from the parent field.

</TabItem>
</Tabs>

---

## Enumerations

Storm persists enum values as their `name()` string by default, which is readable and resilient to reordering. If storage efficiency is a priority or your schema uses integer columns for enums, you can switch to ordinal storage with `@DbEnum(ORDINAL)`. Be aware that ordinal storage is sensitive to the order of enum constants: adding or reordering values will break existing data.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Enums are stored by their name by default:

```kotlin
enum class RoleType {
    USER,
    ADMIN
}

data class Role(
    @PK val id: Int = 0,
    val name: String,
    val type: RoleType  // Stored as "USER" or "ADMIN"
) : Entity<Int>
```

To store by ordinal:

```kotlin
data class Role(
    @PK val id: Int = 0,
    val name: String,
    @DbEnum(ORDINAL) val type: RoleType  // Stored as 0 or 1
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

Enums are stored by their name by default:

```java
enum RoleType {
    USER,
    ADMIN
}

record Role(@PK Integer id,
            @Nonnull String name,
            @Nonnull RoleType type  // Stored as "USER" or "ADMIN"
) implements Entity<Integer> {}
```

To store by ordinal:

```java
record Role(@PK Integer id,
            @Nonnull String name,
            @Nonnull @DbEnum(ORDINAL) RoleType type  // Stored as 0 or 1
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Converters

When an entity field uses a type that is not directly supported by the JDBC driver, use `@Convert` to specify a converter that transforms between your domain type and a JDBC-compatible column type. Storm also supports auto-apply converters via `@DefaultConverter`, which automatically apply to all matching field types without requiring explicit annotations.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Money(val amount: BigDecimal)

@DbTable("product")
data class Product(
    @PK val id: Int = 0,
    val name: String,
    @Convert(converter = MoneyConverter::class) val price: Money
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Money(BigDecimal amount) {}

@DbTable("product")
record Product(@PK Integer id,
               @Nonnull String name,
               @Convert(converter = MoneyConverter.class) Money price
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

See [Converters](converters.md) for the full `Converter<D, E>` interface, auto-apply with `@DefaultConverter`, resolution order, and practical examples.

---

## Versioning (Optimistic Locking)

Optimistic locking prevents lost updates when multiple users or threads modify the same record concurrently. Storm checks the version value during updates: if another transaction has already changed the row, the update fails with an exception rather than silently overwriting the other change. You can use either an integer counter or a timestamp.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use `@Version` for optimistic locking:

```kotlin
data class Owner(
    @PK val id: Int = 0,
    val firstName: String,
    val lastName: String,
    @Version val version: Int
) : Entity<Int>
```

Timestamps are also supported:

```kotlin
data class Visit(
    @PK val id: Int = 0,
    val visitDate: LocalDate,
    val description: String? = null,
    @FK val pet: Pet,
    @Version val timestamp: Instant?
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

Use `@Version` for optimistic locking:

```java
record Owner(@PK Integer id,
             @Nonnull String firstName,
             @Nonnull String lastName,
             @Version int version
) implements Entity<Integer> {}
```

Timestamps are also supported:

```java
record Visit(@PK Integer id,
             @Nonnull LocalDate visitDate,
             @Nullable String description,
             @Nonnull @FK Pet pet,
             @Version Instant timestamp
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Non-Updatable Fields

Some fields should be set once at creation and never changed by the application, such as creation timestamps, entity types, or references that define an object's identity. Marking a field with `@Persist(updatable = false)` tells Storm to include it in INSERT statements but exclude it from UPDATE statements.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use `@Persist(updatable = false)` for fields that should only be set on insert:

```kotlin
data class Pet(
    @PK val id: Int = 0,
    val name: String,
    @Persist(updatable = false) val birthDate: LocalDate,
    @FK @Persist(updatable = false) val type: PetType,
    @FK val owner: Owner? = null
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

Use `@Persist(updatable = false)` for fields that should only be set on insert:

```java
record Pet(@PK Integer id,
           @Nonnull String name,
           @Nonnull @Persist(updatable = false) LocalDate birthDate,
           @Nonnull @FK @Persist(updatable = false) PetType type,
           @Nullable @FK Owner owner
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Modifying Entities

Since Storm entities are immutable, updating a field means creating a new instance with the changed value. Kotlin data classes have a built-in `copy()` method for this. Java records do not provide an equivalent, but Lombok's `@Builder(toBuilder = true)` annotation generates a builder that copies all fields from an existing instance:

```java
@Builder(toBuilder = true)
record User(@PK Integer id,
            @Nonnull String email,
            @Nonnull String name,
            @FK City city
) implements Entity<Integer> {}
```

This enables `user.toBuilder().email("new@example.com").build()` to create a modified copy. See the [FAQ](faq.md#how-do-i-modify-a-java-record-entity) for alternative approaches and upcoming Java language features.

---

## Naming Conventions

Storm uses pluggable name resolvers to convert Kotlin/Java names to database identifiers. By default, camelCase names are converted to snake_case, and foreign key fields append `_id`.

### Default Conversion: CamelCase to Snake_Case

The default resolver converts camelCase to snake_case:

1. Convert the first character to lowercase
2. Insert an underscore before each uppercase letter and convert it to lowercase

| Field/Class | Resolved Name |
|-------------|---------------|
| `id` | `id` |
| `email` | `email` |
| `birthDate` | `birth_date` |
| `postalCode` | `postal_code` |
| `firstName` | `first_name` |
| `UserRole` | `user_role` |

For foreign keys, `_id` is appended after the conversion:

| FK Field | Resolved Column |
|----------|-----------------|
| `city` | `city_id` |
| `petType` | `pet_type_id` |
| `homeAddress` | `home_address_id` |

For details on customizing name resolution (uppercase conversion, custom resolvers, composable wrappers), see [Naming Conventions](configuration.md#naming-conventions).

### Per-Entity and Per-Field Overrides

Annotation overrides (`@DbTable`, `@DbColumn`, and the string parameters on `@PK` and `@FK`) always take precedence over configured resolvers. See [Custom Table and Column Names](#custom-table-and-column-names) for details and examples.

### Identifier Escaping

Storm automatically escapes identifiers that are SQL reserved words or contain special characters. Force escaping with the `escape` parameter:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("order", escape = true)  // "order" is a reserved word
data class Order(
    @PK val id: Int = 0,
    @DbColumn("select", escape = true) val select: String  // "select" is reserved
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable(value = "order", escape = true)  // "order" is a reserved word
record Order(@PK Integer id,
             @DbColumn(value = "select", escape = true) String select  // "select" is reserved
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Custom Table and Column Names

When the database schema does not follow Storm's default camelCase-to-snake_case convention, use annotations to specify the exact names. `@DbTable` overrides the table name, `@DbColumn` overrides a column name, and the string parameter on `@PK` or `@FK` overrides their respective column names. These annotations take precedence over any configured name resolver.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("app_users")
data class User(
    @PK("user_id") val id: Int = 0,
    @DbColumn("email_address") val email: String,
    @FK("home_city_id") val city: City
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("app_users")
record User(@PK("user_id") Integer id,
            @DbColumn("email_address") String email,
            @FK("home_city_id") City city
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Column Mapping

Storm automatically maps fields to columns using these conventions:

| Entity Field | Database Column |
|--------------|-----------------|
| `id` | `id` |
| `email` | `email` |
| `birthDate` | `birth_date` |
| `postalCode` | `postal_code` |
| `city` (FK) | `city_id` |

CamelCase field names are converted to snake_case column names. Foreign keys automatically append `_id` and reference the primary key of the related entity.

---

## Join Behavior

Nullability affects how relationships are loaded:

- **Non-nullable FK:** INNER JOIN (referenced entity must exist)
- **Nullable FK:** LEFT JOIN (referenced entity may be null)

---

## Suppressing Schema Validation

To suppress constraint-specific warnings (missing primary key, foreign key, or unique constraint), use the `constraint` attribute on `@PK`, `@FK`, or `@UK`. This is more targeted than `@DbIgnore` because it only suppresses the constraint check while preserving all other validation (column existence, type compatibility, nullability). See [Constraint Validation](validation.md#constraint-validation) for details and examples.

Use `@DbIgnore` to suppress [schema validation](configuration.md#schema-validation) for an entity or a specific field entirely. This is useful for legacy tables, columns handled by [custom converters](converters.md), or known type mismatches that are safe at runtime.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Suppress all schema validation for a legacy entity.
@DbIgnore
data class LegacyUser(
    @PK val id: Int = 0,
    val name: String
) : Entity<Int>

// Suppress schema validation for a specific field.
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
// Suppress all schema validation for a legacy entity.
@DbIgnore
record LegacyUser(@PK Integer id,
                  @Nonnull String name
) implements Entity<Integer> {}

// Suppress schema validation for a specific field.
record User(@PK Integer id,
            @Nonnull String name,
            @DbIgnore("DB uses FLOAT, but column only stores whole numbers")
            @Nonnull Integer age
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

The optional `value` parameter documents why the mismatch is acceptable. When placed on an embedded component field, `@DbIgnore` suppresses validation for all columns within that component.
