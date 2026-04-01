import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Configuration

Storm can be configured through `StormConfig`, system properties, Spring Boot's `application.yml`, or Ktor's `application.conf`. These properties control runtime behavior for features like dirty checking and entity caching. All properties have sensible defaults, so **configuration is optional**. Storm works out of the box without any configuration.

---

## Properties

| Property | Default | Description |
|----------|---------|-------------|
| `storm.update.default_mode` | `ENTITY` | Default update mode for entities without `@DynamicUpdate` |
| `storm.update.dirty_check` | `INSTANCE` | Default dirty check strategy (`INSTANCE` or `VALUE`) |
| `storm.update.max_shapes` | `5` | Maximum UPDATE shapes before fallback to full-row |
| `storm.entity_cache.retention` | `default` | Cache retention mode: `default` or `light` |
| `storm.template_cache.size` | `2048` | Maximum number of compiled templates to cache |
| `storm.validation.record_mode` | `fail` | Record validation mode: `fail`, `warn`, or `none` |
| `storm.validation.schema_mode` | `none` | Schema validation mode: `none`, `warn`, or `fail` (Spring Boot and Ktor) |
| `storm.validation.strict` | `false` | Treat schema validation warnings as errors |
| `storm.validation.interpolation_mode` | `warn` | Interpolation safety mode: `warn`, `fail`, or `none` (see [Interpolation Safety](#interpolation-safety)) |
| `st.orm.scrollable.maxSize` | `1000` | Maximum window size allowed in a serialized cursor (system property only) |

### Setting Properties

**Via JVM arguments:**

```bash
java -Dstorm.update.default_mode=FIELD \
     -Dstorm.update.dirty_check=VALUE \
     -Dstorm.update.max_shapes=10 \
     -Dstorm.entity_cache.retention=light \
     -Dstorm.template_cache.size=4096 \
     -jar myapp.jar
```

**Programmatically via `StormConfig`:**

`StormConfig` holds an immutable set of `String` key-value properties. Pass a `StormConfig` to `ORMTemplate.of()` to apply the configuration. Any property not explicitly set falls back to the system property, then to the built-in default.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val config = StormConfig.of(mapOf(
    UPDATE_DEFAULT_MODE to "FIELD",
    ENTITY_CACHE_RETENTION to "light",
    TEMPLATE_CACHE_SIZE to "4096"
))

val orm = ORMTemplate.of(dataSource, config)

// Or using the extension function
val orm = dataSource.orm(config)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var config = StormConfig.of(Map.of(
    UPDATE_DEFAULT_MODE, "FIELD",
    ENTITY_CACHE_RETENTION, "light",
    TEMPLATE_CACHE_SIZE, "4096"
));

var orm = ORMTemplate.of(dataSource, config);
```

</TabItem>
</Tabs>

When `StormConfig` is omitted, `ORMTemplate.of(dataSource)` reads system properties and built-in defaults automatically.

**In Spring Boot's `application.yml`** (requires `storm-spring-boot-starter` or `storm-kotlin-spring-boot-starter`):

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
    record-mode: fail
    schema-mode: none
    strict: false
```

The Spring Boot Starter binds these properties and builds a `StormConfig` that is passed to the `ORMTemplate` factory. Values not set in YAML fall back to system properties and then to built-in defaults. See [Spring Integration](spring-integration.md#configuration-via-applicationyml) for details.

**In Ktor's `application.conf`** (requires `storm-ktor`):

```hocon
storm {
    ansiEscaping = false
    update {
        defaultMode = "ENTITY"
        dirtyCheck = "INSTANCE"
        maxShapes = 5
    }
    entityCache {
        retention = "default"
    }
    templateCache {
        size = 2048
    }
    validation {
        recordMode = "fail"
        schemaMode = "none"
        strict = false
    }
}
```

The Storm Ktor plugin reads these properties and builds a `StormConfig` that is passed to the `ORMTemplate` factory. HOCON supports environment variable substitution with `${?VAR_NAME}` syntax. See [Ktor Integration](ktor-integration.md#configuration) for details.

---

## ORMTemplate Factory Overloads

The `ORMTemplate.of()` factory method is the main entry point for creating an ORM template outside of Spring. It accepts optional parameters for configuration and template decoration, so you can combine `StormConfig` (for runtime properties) with a `TemplateDecorator` (for name resolution customization) at creation time.

The simplest form takes only a `DataSource` and uses all defaults. From there, you can add a `StormConfig` for property overrides, a decorator for custom naming conventions, or both. The decorator parameter is a `UnaryOperator<TemplateDecorator>` that receives the default decorator and returns a modified version.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Minimal: defaults only
val orm = dataSource.orm

// With configuration
val orm = dataSource.orm(config)

// With decorator (custom name resolution)
val orm = dataSource.orm { decorator ->
    decorator.withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
}

// With both configuration and decorator
val orm = dataSource.orm(config) { decorator ->
    decorator.withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT))
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Minimal: defaults only
var orm = ORMTemplate.of(dataSource);

// With configuration
var orm = ORMTemplate.of(dataSource, config);

// With decorator (custom name resolution)
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT)));

// With both configuration and decorator
var orm = ORMTemplate.of(dataSource, config, decorator -> decorator
    .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT)));
```

</TabItem>
</Tabs>

When using Spring Boot, the starter creates the `ORMTemplate` for you and applies configuration from `application.yml`. You can still customize name resolution by defining a `TemplateDecorator` bean. See [Spring Integration: Template Decorator](spring-integration.md#template-decorator) for details.

---

## Naming Conventions

Storm uses pluggable name resolvers to convert Kotlin/Java names to database identifiers. By default, camelCase names are converted to snake_case. You can replace or wrap these resolvers to match any naming convention your database requires, whether that means uppercase identifiers, table prefixes, or entirely custom logic.

This section covers global name resolution configuration. For per-entity annotation overrides (`@DbTable`, `@DbColumn`), see [Entities: Custom Table and Column Names](entities.md#custom-table-and-column-names).

### Name Resolvers

Storm splits name resolution into three independent concerns. Each resolver is a functional interface with a single method, so you can configure them with lambdas or with full class implementations.

| Resolver | Method Signature | Purpose |
|----------|-----------------|---------|
| `TableNameResolver` | `resolveTableName(RecordType)` | Maps an entity or projection class to a table name |
| `ColumnNameResolver` | `resolveColumnName(RecordField)` | Maps a record field to a column name |
| `ForeignKeyResolver` | `resolveColumnName(RecordField, RecordType)` | Maps a foreign key field to its column name, given the target entity type |

The separation means you can, for example, use uppercase table names while keeping lowercase column names, or apply a custom foreign key naming pattern without affecting regular columns.

### Default Conversion: CamelCase to Snake_Case

Out of the box, Storm converts camelCase identifiers to snake_case by inserting underscores before uppercase letters and lowercasing the result. This matches the most common convention in relational databases.

| Field/Class | Resolved Name |
|-------------|---------------|
| `id` | `id` |
| `email` | `email` |
| `birthDate` | `birth_date` |
| `postalCode` | `postal_code` |
| `firstName` | `first_name` |
| `UserRole` | `user_role` |

For foreign keys, `_id` is appended after the conversion. This convention makes it clear which columns are foreign keys when reading the schema directly.

| FK Field | Resolved Column |
|----------|-----------------|
| `city` | `city_id` |
| `petType` | `pet_type_id` |
| `homeAddress` | `home_address_id` |

### Configuring Name Resolvers

To replace the default resolvers, pass a `TemplateDecorator` when creating the ORM template. The decorator exposes `withTableNameResolver()`, `withColumnNameResolver()`, and `withForeignKeyResolver()` methods. You only need to set the resolvers you want to change; any resolver you leave unset keeps its default behavior.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val orm = dataSource.orm { decorator ->
    decorator
        .withTableNameResolver(TableNameResolver.camelCaseToSnakeCase())
        .withColumnNameResolver(ColumnNameResolver.camelCaseToSnakeCase())
        .withForeignKeyResolver(ForeignKeyResolver.camelCaseToSnakeCase())
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(TableNameResolver.camelCaseToSnakeCase())
    .withColumnNameResolver(ColumnNameResolver.camelCaseToSnakeCase())
    .withForeignKeyResolver(ForeignKeyResolver.camelCaseToSnakeCase()));
```

</TabItem>
</Tabs>

The example above is equivalent to the defaults and is shown for illustration. In practice, you would only call these methods when you want to override the default behavior.

### Uppercase Conversion

Some databases (notably Oracle) use uppercase identifiers by default. Rather than writing a new resolver from scratch, Storm provides `toUpperCase()` wrappers that decorate any existing resolver and uppercase its output.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val orm = dataSource.orm { decorator ->
    decorator
        .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.camelCaseToSnakeCase()))
        .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.camelCaseToSnakeCase()))
        .withForeignKeyResolver(ForeignKeyResolver.toUpperCase(ForeignKeyResolver.camelCaseToSnakeCase()))
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.camelCaseToSnakeCase()))
    .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.camelCaseToSnakeCase()))
    .withForeignKeyResolver(ForeignKeyResolver.toUpperCase(ForeignKeyResolver.camelCaseToSnakeCase())));
```

</TabItem>
</Tabs>

This produces:

| Field/Class | Resolved Name |
|-------------|---------------|
| `birthDate` | `BIRTH_DATE` |
| `User` | `USER` |
| `city` (FK) | `CITY_ID` |

### Composing Resolvers

The `toUpperCase()` wrapper demonstrates a general pattern: because each resolver is a functional interface, you can compose wrappers that add behavior to any existing resolver. This is more flexible than subclassing because wrappers are independent of each other and can be combined in any order.

For example, a wrapper that adds a table name prefix. This is useful when multiple applications share a database and each uses a common prefix to avoid table name collisions.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
fun withPrefix(prefix: String, resolver: TableNameResolver) = TableNameResolver { type ->
    "$prefix${resolver.resolveTableName(type)}"
}

val orm = dataSource.orm { decorator ->
    decorator.withTableNameResolver(withPrefix("app_", TableNameResolver.camelCaseToSnakeCase()))
}
// User -> app_user, OrderItem -> app_order_item
```

</TabItem>
<TabItem value="java" label="Java">

```java
static TableNameResolver withPrefix(String prefix, TableNameResolver resolver) {
    return type -> prefix + resolver.resolveTableName(type);
}

var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(withPrefix("app_", TableNameResolver.camelCaseToSnakeCase())));
// User -> app_user, OrderItem -> app_order_item
```

</TabItem>
</Tabs>

Note that each resolver should return a plain identifier (the table name, column name, or foreign key column name). Do not include schema qualifiers or other SQL syntax in the resolved name.

### RecordType and RecordField Reference

Custom resolvers receive `RecordType` and `RecordField` objects that provide metadata about the entity or field being resolved. These objects give you access to the class, its annotations, and individual field details, so your resolvers can make decisions based on package names, annotation presence, field types, or any other metadata.

**`RecordType`** is passed to `TableNameResolver` and `ForeignKeyResolver`. It represents the entity or projection class being mapped.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `type()` | `Class<?>` | The record class |
| `annotations()` | `List<Annotation>` | All annotations on the record class |
| `fields()` | `List<RecordField>` | Metadata for all record fields, in declaration order |
| `isAnnotationPresent(Class)` | `boolean` | Whether an annotation type is present |
| `getAnnotation(Class)` | `Annotation` | Retrieve a single annotation by type |

**`RecordField`** is passed to `ColumnNameResolver` and `ForeignKeyResolver`. It represents a single field (record component) being mapped to a column.

| Method | Return Type | Description |
|--------|-------------|-------------|
| `name()` | `String` | The field name (e.g., `"birthDate"`) |
| `type()` | `Class<?>` | The raw field type |
| `declaringType()` | `Class<?>` | The class that declares this field |
| `annotations()` | `List<Annotation>` | All annotations on the field |
| `isAnnotationPresent(Class)` | `boolean` | Whether an annotation type is present |
| `nullable()` | `boolean` | Whether the field can be null |

### Custom Resolvers

When the built-in resolvers and wrappers are not enough, you can implement fully custom naming strategies. There are two approaches: lambda expressions for simple, inline logic, and interface implementations for strategies that are complex or shared across projects.

#### Lambda-Based Configuration

Lambdas are convenient for quick, self-contained overrides. Since each resolver is a functional interface, a single lambda replaces the entire resolution strategy for that concern.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Identity resolver: use the field name as-is, without any conversion
val orm = dataSource.orm { decorator ->
    decorator.withColumnNameResolver { field -> field.name() }
}

// Custom prefix for foreign key columns
val orm = dataSource.orm { decorator ->
    decorator.withForeignKeyResolver { field, type ->
        "fk_${ForeignKeyResolver.camelCaseToSnakeCase().resolveColumnName(field, type)}"
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Identity resolver: use the field name as-is, without any conversion
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withColumnNameResolver(field -> field.name()));

// Custom prefix for foreign key columns
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withForeignKeyResolver((field, type) ->
        "fk_" + ForeignKeyResolver.camelCaseToSnakeCase().resolveColumnName(field, type)));
```

</TabItem>
</Tabs>

#### Interface-Based Implementation

For more complex or reusable naming strategies, implement the resolver interfaces as standalone classes. This approach is preferable when the resolver contains non-trivial logic, needs to be tested independently, or is shared across multiple ORM template instances.

The examples below show three resolvers working together: a table name resolver that adds a prefix based on the entity's package, a column name resolver that marks encrypted columns, and a foreign key resolver that uses the target table name instead of the field name.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
class PrefixedTableNameResolver : TableNameResolver {
    override fun resolveTableName(type: RecordType): String {
        val pkg = type.type().packageName
        val prefix = if (pkg.contains(".admin")) "admin_" else ""
        val tableName = TableNameResolver.camelCaseToSnakeCase().resolveTableName(type)
        return "$prefix$tableName"
    }
}

class EncryptedColumnNameResolver : ColumnNameResolver {
    override fun resolveColumnName(field: RecordField): String {
        val columnName = ColumnNameResolver.camelCaseToSnakeCase().resolveColumnName(field)
        return if (field.isAnnotationPresent(Encrypted::class.java)) "enc_$columnName" else columnName
    }
}

class TargetTableForeignKeyResolver : ForeignKeyResolver {
    override fun resolveColumnName(field: RecordField, type: RecordType): String {
        val targetTable = TableNameResolver.camelCaseToSnakeCase().resolveTableName(type)
        return "${targetTable}_fk"
    }
}
```

Register custom implementations:

```kotlin
val orm = dataSource.orm { decorator ->
    decorator
        .withTableNameResolver(PrefixedTableNameResolver())
        .withColumnNameResolver(EncryptedColumnNameResolver())
        .withForeignKeyResolver(TargetTableForeignKeyResolver())
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
public class PrefixedTableNameResolver implements TableNameResolver {
    @Override
    public String resolveTableName(RecordType type) {
        String pkg = type.type().getPackageName();
        String prefix = pkg.contains(".admin") ? "admin_" : "";
        String tableName = TableNameResolver.camelCaseToSnakeCase()
            .resolveTableName(type);
        return prefix + tableName;
    }
}

public class EncryptedColumnNameResolver implements ColumnNameResolver {
    @Override
    public String resolveColumnName(RecordField field) {
        String columnName = ColumnNameResolver.camelCaseToSnakeCase()
            .resolveColumnName(field);
        if (field.isAnnotationPresent(Encrypted.class)) {
            return "enc_" + columnName;
        }
        return columnName;
    }
}

public class TargetTableForeignKeyResolver implements ForeignKeyResolver {
    @Override
    public String resolveColumnName(RecordField field, RecordType type) {
        String targetTable = TableNameResolver.camelCaseToSnakeCase()
            .resolveTableName(type);
        return targetTable + "_fk";
    }
}
```

Register custom implementations:

```java
var orm = ORMTemplate.of(dataSource, decorator -> decorator
    .withTableNameResolver(new PrefixedTableNameResolver())
    .withColumnNameResolver(new EncryptedColumnNameResolver())
    .withForeignKeyResolver(new TargetTableForeignKeyResolver()));
```

</TabItem>
</Tabs>

### Per-Entity and Per-Field Overrides

Annotations on individual entities and fields always take precedence over configured resolvers. This means you can set a global naming convention through resolvers and still override specific tables or columns where the convention does not apply (for example, a legacy table with a non-standard name).

Use `@DbTable` to override a table name, `@DbColumn` to override a column name, and the string parameter on `@PK` or `@FK` to override their respective column names. See [Entities: Custom Table and Column Names](entities.md#custom-table-and-column-names) for details and examples.

### Identifier Escaping

When a table or column name conflicts with a SQL reserved word, the database will reject the query unless the identifier is escaped. Storm automatically detects and escapes common reserved words. For cases that are not caught automatically, you can force escaping with the `escape` parameter on `@DbTable` or `@DbColumn`. Storm uses the escaping syntax appropriate for the active database dialect (double quotes for most databases, square brackets for SQL Server).

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

## Scrolling Properties

### st.orm.scrollable.maxSize

Sets the maximum window size that a deserialized cursor (via `Scrollable.fromCursor()`) is allowed to carry. This is a safety limit that prevents untrusted clients from requesting excessively large pages through cursor manipulation. The limit is only enforced when deserializing a cursor string; programmatic usage via `Scrollable.of()` is not restricted.

This property is a JVM system property only; it is not configurable through `StormConfig` or `application.yml`, because it applies at the `Scrollable` record level in storm-foundation, before any ORM template is created.

```bash
java -Dst.orm.scrollable.maxSize=5000 -jar myapp.jar
```

Repository or API layers may choose to enforce stricter per-endpoint limits on top of this framework-level bound.

---

## Dirty Checking Properties

These properties control how Storm detects changes to entities during update operations. Dirty checking determines whether an UPDATE statement is sent to the database and, if so, which columns it includes. Choosing the right mode depends on your entity size, update frequency, and whether you use immutable records or mutable objects. See [Dirty Checking](dirty-checking.md) for a detailed explanation of each strategy.

### storm.update.default_mode

Controls the default update mode for entities that don't have an explicit `@DynamicUpdate` annotation. This setting applies globally and can be overridden per entity with the `@DynamicUpdate` annotation.

| Value | Behavior |
|-------|----------|
| `OFF` | No dirty checking. Always update all columns. |
| `ENTITY` | Skip UPDATE if entity unchanged; full-row update if any field changed. |
| `FIELD` | Update only the columns that actually changed. |

### storm.update.dirty_check

Controls how Storm compares field values to detect changes. The choice between `INSTANCE` and `VALUE` depends on whether your entities are truly immutable. Immutable records (the recommended pattern) work correctly with `INSTANCE` because unchanged fields share the same object reference. If your entities contain mutable objects that could change without creating a new reference, use `VALUE` to compare by `equals()` instead.

| Value | Behavior |
|-------|----------|
| `INSTANCE` | Compare by reference identity. Fast, works well with immutable records. |
| `VALUE` | Compare using `equals()`. More accurate for mutable objects. |

### storm.update.max_shapes

In `FIELD` mode, each unique combination of changed columns produces a distinct UPDATE statement shape (e.g., updating only `email` is a different shape than updating `email` and `name`). Each shape occupies a slot in the database's prepared statement cache. This property caps the number of shapes per entity type. Once the limit is reached, Storm falls back to full-row updates to prevent statement cache bloat.

Lower values (3-5) are better for applications with many entity types, where the total number of cached statements across all entities matters. Higher values (10-20) allow more granular updates but increase statement cache pressure.

---

## Entity Cache Properties

Storm maintains a transaction-scoped entity cache that ensures the same database row maps to the same object instance within a single transaction. This property controls the cache's memory behavior. See [Entity Cache](entity-cache.md) for details on how the cache interacts with identity guarantees and garbage collection.

### storm.entity_cache.retention

Controls how long entities are retained in the cache during a transaction. The choice is a trade-off between memory consumption and dirty-checking reliability. With `default`, entities are retained for the duration of the transaction, which provides reliable dirty checking while still allowing the JVM to reclaim entries under memory pressure. With `light`, the JVM can reclaim cached entities as soon as your code no longer holds a reference, which reduces memory usage but may cause dirty-check cache misses.

| Value | Behavior |
|-------|----------|
| `default` | Entities retained for the transaction duration. Reliable dirty checking. The JVM may still reclaim entries under memory pressure. |
| `light` | Entities can be garbage collected when no longer referenced by your code. Memory-efficient but may cause full-row updates due to cache misses. |

---

## Template Cache Properties

Storm compiles SQL templates into reusable prepared statement shapes. This compilation step resolves aliases, derives joins, and expands column lists. Caching the compiled result avoids repeating this work for the same query pattern with different parameter values. See [SQL Templates](sql-templates.md#compilation-caching) for details on how compilation and caching work.

### storm.template_cache.size

Sets the maximum number of compiled templates to keep in the cache. When the cache is full, the least recently used templates are evicted.

The default of 2048 is sufficient for most applications. A typical application uses a few hundred distinct query patterns. Increase this value if you have many distinct query patterns (for example, from dynamically constructed queries) and observe cache eviction in your metrics. Each cached entry is small (the compiled SQL structure and metadata), so increasing the limit has minimal memory impact.

---

## Validation Properties

Storm provides two independent validation subsystems, each controlled by a mode property. Record validation checks that your entity and projection definitions are structurally correct (valid primary key types, proper annotation usage, no circular dependencies). Schema validation compares your definitions against the actual database schema to catch mismatches before they surface as runtime errors.

### storm.validation.record_mode

Controls whether record (structural) validation runs when Storm first encounters an entity or projection type.

| Value | Behavior |
|-------|----------|
| `fail` | Validation errors cause startup to fail with a `PersistenceException` (default). |
| `warn` | Errors are logged as warnings; startup continues. |
| `none` | Record validation is skipped entirely. |

### storm.validation.schema_mode

Controls whether schema validation runs at startup (Spring Boot only; for programmatic use, see [Validation](validation.md#programmatic-api)).

| Value | Behavior |
|-------|----------|
| `none` | Schema validation is skipped (default). |
| `warn` | Mismatches are logged at WARN level; startup continues. |
| `fail` | Mismatches cause startup to fail with a `PersistenceException`. |

### storm.validation.strict

When `true`, schema validation warnings (type narrowing, nullability mismatches, missing unique/foreign key constraints) are promoted to errors. This is useful in CI environments where any schema drift should be caught.

See [Validation](validation.md) for a complete list of what each validation level checks.

---

## Interpolation Safety

Storm's Kotlin API uses the Storm compiler plugin to automatically wrap string interpolations inside SQL template lambdas, ensuring all values are parameterized and SQL injection safe. When a `TemplateBuilder` lambda runs without the compiler plugin and without any explicit `t()` or `interpolate()` calls, Storm cannot distinguish a pure SQL literal (safe) from a string with accidentally concatenated interpolations (SQL injection risk). The `storm.validation.interpolation_mode` property controls how Storm handles this situation.

### storm.validation.interpolation_mode

| Value | Behavior |
|-------|----------|
| `warn` | Logs a warning at `WARNING` level (default). |
| `fail` | Throws an `IllegalStateException`, preventing execution of potentially unsafe templates. |
| `none` | Disables the check entirely. Use only when you are certain the compiler plugin is not needed. |

See [String Templates](string-templates.md) for setup instructions for the compiler plugin.

:::tip Monitoring
Storm exposes runtime metrics for template compilation, dirty checking, and entity cache behavior through JMX MBeans. See [Metrics](metrics.md) for details.
:::

---

## Per-Entity Configuration

System properties set global defaults, but individual entities often have different update characteristics. An entity with a large text column benefits from field-level updates, while a small entity with three columns does not. Per-entity annotations let you tune update behavior where it matters most, without changing the global default.

### @DynamicUpdate

Override the update mode for a specific entity. This is most valuable for entities with large or variable-size columns where sending unchanged data wastes bandwidth.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@DynamicUpdate(FIELD)
data class Article(
    @PK val id: Int = 0,
    val title: String,
    val content: String  // Large column - benefits from field-level updates
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
@DynamicUpdate(FIELD)
record Article(
    @PK Integer id,
    @Nonnull String title,
    @Nonnull String content
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

### Dirty Check Strategy Per Entity

You can also override the dirty check strategy on a per-entity basis. This is useful when a specific entity contains mutable objects that require value-based comparison, while the rest of your application uses the default instance-based comparison.

```kotlin
@DynamicUpdate(value = FIELD, dirtyCheck = VALUE)
data class User(
    @PK val id: Int = 0,
    val email: String
) : Entity<Int>
```

---

## Configuration Precedence

Entity-level annotations take the highest precedence, followed by explicit `StormConfig` values, then system properties, and finally built-in defaults:

```
1. @DynamicUpdate annotation on entity class
   ↓ (if not present)
2. StormConfig (explicit value passed to factory)
   ↓ (if not set)
3. System property (-Dstorm.*)
   ↓ (if not set)
4. Built-in default
```

When using the Spring Boot Starter, `StormConfig` is built from `application.yml` properties. Properties not set in YAML fall through to system properties and then to built-in defaults.

---

## Recommended Configurations

The following profiles cover common scenarios. Start with the defaults and adjust only when profiling reveals a specific bottleneck.

### Default (Most Applications)

The built-in defaults work well for most applications. No configuration needed:
- `ENTITY` mode skips UPDATE when nothing changed, but sends all columns when any field changes
- `INSTANCE` comparison is fast and correct with immutable records/data classes
- `default` cache retention provides reliable dirty checking

### High-Write Applications

For applications with frequent updates to large entities, field-level updates reduce the amount of data sent to the database on each UPDATE. This matters most when entities have large text or binary columns where sending unchanged data wastes network bandwidth and database I/O.

```bash
java -Dstorm.update.default_mode=FIELD \
     -Dstorm.update.max_shapes=10 \
     -jar myapp.jar
```

This reduces network bandwidth by only sending changed columns.

### Memory-Constrained Bulk Operations

For transactions that load a very large number of entities (bulk migrations, large reports), light cache retention allows the JVM to reclaim cached entities sooner. The trade-off is that dirty checking may encounter cache misses, resulting in full-row updates.

```bash
java -Dstorm.entity_cache.retention=light \
     -jar myapp.jar
```

This reduces memory usage at the cost of less efficient dirty checking.

### Production Hardening

For production environments, consider enabling strict validation and interpolation safety checks. These settings catch configuration issues and potential security problems that should not reach production:

```bash
java -Dstorm.validation.schema_mode=fail \
     -Dstorm.validation.interpolation_mode=fail \
     -jar myapp.jar
```

- `storm.validation.schema_mode=fail` catches entity-to-schema mismatches at startup rather than at runtime.
- `storm.validation.interpolation_mode=fail` prevents execution of templates that were not processed by the compiler plugin and do not use explicit `t()` calls, protecting against accidental SQL injection.

During development, the defaults (`schema_mode=none`, `interpolation_mode=warn`) provide a smoother experience: schema validation is skipped (since the schema may be evolving), and missing compiler plugin usage is logged as a warning rather than blocking execution.
