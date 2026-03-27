import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Converters

Storm maps record components to database columns using built-in type support for standard Java and JDBC types. When your entity contains a type that is not directly supported by the JDBC driver, or when you want a custom mapping between your domain model and the database, you need a converter.

A converter is a bidirectional transformer that translates between a JDBC-compatible type (the "database type") and your entity's field type (the "entity type"). Storm's converter system is designed around a simple interface with clear lifecycle semantics, and it supports both explicit and automatic application.

---

## The Converter Interface

The `Converter<D, E>` interface defines two methods:

```java
public interface Converter<D, E> {

    /**
     * Converts an entity value to a database column value.
     */
    D toDatabase(@Nullable E value);

    /**
     * Converts a database column value to an entity value.
     */
    E fromDatabase(@Nullable D dbValue);
}
```

The type parameters are:

| Parameter | Role | Constraint |
|---|---|---|
| `D` | The database-visible type | Must be a type that JDBC can handle natively (e.g., `String`, `Integer`, `BigDecimal`, `Timestamp`). |
| `E` | The entity value type | The type of the record component in your entity. |

Both methods receive a possibly-null value and may return null. This allows converters to handle nullable columns naturally.

### Requirements

Every converter class must provide a **public no-argument constructor**. Storm instantiates converters via classpath scanning and cannot inject dependencies. If your converter needs external state, use a static configuration pattern or a lookup in the constructor.

---

## Applying Converters

Storm provides three ways to control conversion:

### 1. Explicit Converter

Use the `@Convert` annotation on a record component to specify exactly which converter to use:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("product")
data class Product(
    @PK val id: Int,
    val name: String,
    @Convert(converter = MoneyConverter::class) val price: Money
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("product")
public record Product(
    @PK int id,
    String name,
    @Convert(converter = MoneyConverter.class) Money price
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

When `@Convert` specifies a converter, that converter is always used, regardless of any auto-apply converters that might match.

### 2. Auto-Apply (Default Converter)

Annotate a converter class with `@DefaultConverter` to make it automatically apply whenever its entity type (`E`) matches a record component and no explicit `@Convert` is present:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DefaultConverter
class MoneyConverter : Converter<BigDecimal, Money> {

    override fun toDatabase(value: Money?): BigDecimal? =
        value?.amount

    override fun fromDatabase(dbValue: BigDecimal?): Money? =
        dbValue?.let { Money(it) }
}
```

With this converter registered, any `Money` component in any entity will automatically use `MoneyConverter` without needing `@Convert`:

```kotlin
@DbTable("product")
data class Product(
    @PK val id: Int,
    val name: String,
    val price: Money   // Automatically uses MoneyConverter.
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DefaultConverter
public class MoneyConverter implements Converter<BigDecimal, Money> {

    @Override
    public BigDecimal toDatabase(Money value) {
        return value != null ? value.amount() : null;
    }

    @Override
    public Money fromDatabase(BigDecimal dbValue) {
        return dbValue != null ? new Money(dbValue) : null;
    }
}
```

With this converter registered, any `Money` component in any entity will automatically use `MoneyConverter` without needing `@Convert`:

```java
@DbTable("product")
public record Product(
    @PK int id,
    String name,
    Money price   // Automatically uses MoneyConverter.
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

### 3. Disabling Conversion

If an auto-apply converter would match a component but you want the built-in mapping instead, disable it explicitly:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DbTable("product")
data class Product(
    @PK val id: Int,
    val name: String,
    @Convert(disableConversion = true) val rawPrice: BigDecimal
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DbTable("product")
public record Product(
    @PK int id,
    String name,
    @Convert(disableConversion = true) BigDecimal rawPrice
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Resolution Order

When Storm encounters a record component during mapping, it resolves the converter in this order:

```
1. Is there an explicit @Convert(converter = ...) annotation?
   └── YES → Use that converter.
   └── NO  → Continue.

2. Is there an @Convert(disableConversion = true) annotation?
   └── YES → Use built-in mapping (no converter).
   └── NO  → Continue.

3. Is there exactly one @DefaultConverter that matches type E?
   └── YES → Use that auto-apply converter.
   └── NO (zero matches)  → Use built-in mapping.
   └── NO (multiple matches) → ERROR: ambiguous converters.
```

When multiple `@DefaultConverter` classes match the same entity type and no explicit `@Convert` is present, Storm fails with a clear error message identifying the conflicting converters. Resolve the conflict by adding an explicit `@Convert` annotation on the component.

---

## Built-In Type Support

Storm handles the following types natively without any converter:

| Category | Types |
|---|---|
| **Primitives and wrappers** | `boolean`, `byte`, `short`, `int`, `long`, `float`, `double`, `char` and their boxed equivalents |
| **Strings** | `String` |
| **Numeric** | `BigDecimal`, `BigInteger` |
| **Date/Time** | `LocalDate`, `LocalTime`, `LocalDateTime`, `Instant`, `OffsetDateTime`, `ZonedDateTime` |
| **Binary** | `ByteBuffer` (read-only) |
| **Enums** | `Enum` types (by name or ordinal via `@DbEnum`) |
| **Other** | `UUID` |

If your entity field is one of these types, you do not need a converter. Custom converters are only needed for types not in this list.

---

## Practical Examples

### Money Type

A domain-specific value type for monetary amounts:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Money(val amount: BigDecimal)

@DefaultConverter
class MoneyConverter : Converter<BigDecimal, Money> {
    override fun toDatabase(value: Money?): BigDecimal? = value?.amount
    override fun fromDatabase(dbValue: BigDecimal?): Money? = dbValue?.let { Money(it) }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public record Money(BigDecimal amount) {}

@DefaultConverter
public class MoneyConverter implements Converter<BigDecimal, Money> {
    @Override
    public BigDecimal toDatabase(Money value) {
        return value != null ? value.amount() : null;
    }

    @Override
    public Money fromDatabase(BigDecimal dbValue) {
        return dbValue != null ? new Money(dbValue) : null;
    }
}
```

</TabItem>
</Tabs>

### Encrypted Field

Transparent encryption for sensitive columns. The database stores the encrypted text, and the application sees the plaintext:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class EncryptedStringConverter : Converter<String, String> {

    private val cipher = EncryptionService.instance()

    override fun toDatabase(value: String?): String? =
        value?.let { cipher.encrypt(it) }

    override fun fromDatabase(dbValue: String?): String? =
        dbValue?.let { cipher.decrypt(it) }
}
```

Apply it explicitly on sensitive fields:

```kotlin
@DbTable("user")
data class User(
    @PK val id: Int,
    val name: String,
    @Convert(converter = EncryptedStringConverter::class) val socialSecurityNumber: String
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class EncryptedStringConverter implements Converter<String, String> {

    private final EncryptionService cipher = EncryptionService.instance();

    @Override
    public String toDatabase(String value) {
        return value != null ? cipher.encrypt(value) : null;
    }

    @Override
    public String fromDatabase(String dbValue) {
        return dbValue != null ? cipher.decrypt(dbValue) : null;
    }
}
```

Apply it explicitly on sensitive fields:

```java
@DbTable("user")
public record User(
    @PK int id,
    String name,
    @Convert(converter = EncryptedStringConverter.class) String socialSecurityNumber
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## See Also

To understand how Storm maps database columns to constructor parameters, see [Hydration](hydration.md).
