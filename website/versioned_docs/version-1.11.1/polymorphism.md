import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Polymorphism

Storm supports polymorphic entity hierarchies using sealed types. Instead of the proxy-based inheritance strategies found in traditional ORMs, Storm leverages sealed interfaces and data classes (Kotlin) or records (Java) to provide compile-time type safety with exhaustive pattern matching. The sealed type hierarchy tells the compiler exactly which subtypes exist, so a `when` (Kotlin) or `switch` (Java) expression over a polymorphic result is guaranteed to cover all cases.

Storm provides three inheritance strategies: **Single-Table**, **Joined Table**, and **Polymorphic FK**. The strategy is detected automatically from how you structure the sealed type hierarchy. Single-Table stores a discriminator value in the entity's table and requires `@Discriminator` on the sealed interface. Joined Table supports an optional `@Discriminator`: when present, a physical discriminator column is stored in the base table; when absent, Storm resolves the concrete type at query time by checking which extension table has a matching row. Polymorphic FK stores discriminator values in the *referencing* entity instead, so the sealed interface itself needs no discriminator annotation.

## Decision Guide

Before diving into the details, use this summary to choose the right strategy for your use case:

| Strategy | Best For | Trade-offs |
|----------|----------|------------|
| [Single-Table](#single-table-inheritance) | Simple hierarchies, few fields per subtype | Fast queries, sparse columns |
| [Joined Table](#joined-table-inheritance) | Complex hierarchies, many fields per subtype | Normalized storage, JOIN cost |
| [Polymorphic FK](#polymorphic-foreign-keys) | References to different entity types | Flexible, requires type column |

**When to use which:** Start with Single-Table when your subtypes share most of their fields and you want the simplest, fastest queries. Switch to Joined Table when subtypes carry many distinct fields and you prefer a clean, normalized schema without NULL columns. Choose Polymorphic FK when the subtypes are independent entities (like posts and photos) that share a common trait (like being commentable), and you need a foreign key that can point to any of them.

---

## Overview

Each strategy maps a sealed type hierarchy to the database in a different way. The choice depends on how many subtype-specific fields you have, how normalized you want the schema, and whether the subtypes are logically "the same entity" or independent entities that share a common trait. See [Choosing a Strategy](#choosing-a-strategy) for a decision tree.

```
  Strategy                  Tables             FK Columns          Use Case
  ────────                  ──────             ──────────          ────────

  Single-Table              1 shared table     1 column            Simple hierarchies, fast queries
                            ┌────────────┐     (regular FK)
                            │    pet     │
                            └────────────┘

  Joined Table              1 base +           1 column            Normalized schemas, many
                            N extension        (FK to base)        subtype-specific fields
                            ┌────────────┐
                            │    pet     │
                            ├────────────┤
                            │    cat     │
                            │    dog     │
                            └────────────┘

  Polymorphic FK            N independent      2 columns           Comment-on-anything,
                            tables             (type + id)         tagging, auditing
                            ┌────────┐
                            │  post  │
                            │ photo  │
                            └────────┘
```

Single-Table puts everything in one table and is the fastest for queries (no JOINs), but subtype-specific columns are NULL for rows that belong to other subtypes. Joined Table eliminates the NULL columns by splitting subtype-specific fields into their own extension tables, at the cost of LEFT JOINs on every query. Polymorphic FK is fundamentally different: the subtypes are independent entities with separate tables, and the polymorphism lives in the foreign key that references them.

### Strategy Comparison

The following table summarizes the key differences between the three strategies. Each trade-off matters in different situations: query performance favors Single-Table, schema cleanliness favors Joined Table, and flexibility across unrelated entity types favors Polymorphic FK.

| Aspect | Single-Table | Joined Table | Polymorphic FK |
|--------|-------------|-------------|----------------|
| **Tables** | One shared table | Base table + extension tables | Separate independent tables |
| **Discriminator** | In the shared table | In the base table (optional<sup>1</sup>) | In the *referencing* entity |
| **Unused columns** | NULL for other subtypes | None (normalized) | None |
| **Query performance** | Fast (no JOINs) | Moderate (LEFT JOINs) | Variable (per-type lookup) |
| **Schema normalization** | Low | High | High |
| **FK from other entities** | Single column | Single column (to base) | Two columns (type + id)<sup>2</sup> |
| **Adding subtypes** | Add columns to shared table | Add new extension table | Add new table |

<sup>1</sup> When `@Discriminator` is omitted, Storm resolves the concrete type at query time by generating an expression that checks which extension table has a matching row. See [The `@Discriminator` Annotation](#the-discriminator-annotation) for details.<br/>
<sup>2</sup> Because the subtypes are independent tables with no shared base table, a single FK column cannot identify both the target table and the target row. The discriminator column identifies the table, and the ID column identifies the row. See [Polymorphic Foreign Keys](#polymorphic-foreign-keys) for details.

Each strategy has strengths that make it the natural choice in certain scenarios. The sections below cover each one in detail.

---

## Strategy Detection

Storm detects the inheritance strategy by inspecting the sealed type hierarchy. You do not specify the strategy as a string or enum; it is inferred from the type structure and annotations. This keeps the entity definitions declarative: the class hierarchy itself tells Storm everything it needs to know.

| Sealed interface extends | Annotations | Detected Strategy |
|--------------------------|-------------|-------------------|
| `Entity<ID>` | `@Discriminator` | **Single-Table** |
| `Entity<ID>` | `@Polymorphic(JOINED)` (with or without `@Discriminator`) | **Joined Table** |
| `Data` (not Entity) | (none required) | **Polymorphic FK** |

The key distinction is whether the sealed interface extends `Entity` (making it a table-backed entity) or `Data` (making it a pure type constraint for polymorphic foreign keys). Detection happens once per type and is cached, so the cost of inspecting the hierarchy is paid only on first access.

For Joined Table, `@Polymorphic(JOINED)` is the deciding factor. Neither `@DbTable` nor `@Discriminator` influence strategy detection for this type. This means you can freely add or remove `@Discriminator` on a Joined Table hierarchy to switch between explicit and implicit type resolution without changing the inheritance strategy itself.

### Validation Rules

Storm validates sealed hierarchies when the model is first accessed. If any rule is violated, a clear error message describes the problem. This catches configuration mistakes at startup rather than at query time, so you find out about structural issues immediately rather than when a specific query happens to trigger the wrong code path.

The following rules are enforced. Some apply universally, while others are specific to a particular strategy.

| Rule | Applies to |
|------|-----------|
| All permitted subclasses must be data classes (Kotlin) or records (Java) | All strategies |
| All subtypes must have the same `@PK` field type and generation strategy | All strategies |
| Discriminator values must be unique across all subtypes | All strategies |
| `@Discriminator` on subtypes must not specify a `column` attribute | All strategies |
| The sealed interface must be annotated with `@Discriminator` | Single-Table |
| `@Discriminator` on the sealed interface must not specify a `value` attribute | Single-Table, Joined Table (when `@Discriminator` is present) |
| Subtypes must not have `@DbTable` | Single-Table |
| Must have at least one common field across all subtypes | Joined Table |
| All subtypes must independently implement `Entity` | Polymorphic FK |
| The sealed interface must not have `@Discriminator` | Polymorphic FK |
| The sealed interface must not have `@Polymorphic` | Polymorphic FK |

For example, if two subtypes in a Single-Table hierarchy both declare `@Discriminator("animal")`, Storm will report a duplicate discriminator value error on first use. Similarly, if a Joined Table hierarchy has no fields in common across all subtypes, Storm will reject the hierarchy because there is nothing to put in the base table.

---

## The `@Discriminator` Annotation

The `@Discriminator` annotation configures how Storm maps between types and database discriminator values. It serves a different purpose depending on where it is placed.

On a **sealed entity interface** using Single-Table inheritance, `@Discriminator` is **required** and declares which column in the database table holds the discriminator. If you omit the `column` attribute, the default column name is `"dtype"`, which is consistent with JPA's `@DiscriminatorColumn` convention.

For **Joined Table** inheritance, `@Discriminator` is **optional**. When present, a physical discriminator column is stored in the base table, just like Single-Table. When absent, Storm resolves the concrete type at query time by generating a `CASE` expression that checks which extension table has a matching row (via `LEFT JOIN` and `IS NOT NULL` on the extension table's primary key). This aligns with Hibernate's behavior for `@Inheritance(strategy = JOINED)` without `@DiscriminatorColumn`. When no `@Discriminator` is present, every subtype always gets an extension table (even if it has no subtype-specific fields), because the extension table row serves as the type marker.

On a **concrete subtype**, `@Discriminator` is optional and sets the value stored in the discriminator column for that subtype. Without it, Storm uses the simple class name (e.g., `"Cat"`, `"Dog"`) for Single-Table and Joined Table, or the resolved table name (e.g., `"post"`, `"photo"`) for Polymorphic FK.

On a **FK field** pointing to a sealed `Data` type (Polymorphic FK), `@Discriminator` is optional and customizes the discriminator column name in the referencing entity's table. Without it, Storm derives the column name from the field name (e.g., a field named `target` produces a column `target_type`).

### Usage Contexts

The table below summarizes where `@Discriminator` can be placed, whether it is required, and what it controls. The `Target` column refers to the annotation target type in Java.

| Context | Target | Required? | Purpose | Default |
|---------|--------|-----------|---------|---------|
| Sealed interface | `TYPE` | **Yes** (Single-Table), Optional (Joined) | Set discriminator column name | `"dtype"` |
| Concrete subtype | `TYPE` | No | Set discriminator value | Simple class name |
| FK field (Polymorphic FK) | `FIELD` | No | Set discriminator column in referencing table | `"{fieldName}_type"` |

The following examples show how to apply the annotation in each context.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// On the sealed interface: required for Single-Table, optional for Joined Table
@Discriminator                    // uses default column name "dtype"
sealed interface Pet : Entity<Int> {
    val name: String
}

// Or with a custom column name
@Discriminator(column = "pet_type")
sealed interface Pet : Entity<Int> {
    val name: String
}

// Joined Table without @Discriminator: type is resolved via extension table PKs
@Polymorphic(JOINED)
sealed interface Pet : Entity<Int> {
    val name: String
}

// On a subtype: customize the discriminator value (optional)
@Discriminator("LARGE_DOG")
data class Dog(
    @PK override val id: Int = 0,
    override val name: String,
    val weight: Int
) : Pet
```

</TabItem>
<TabItem value="java" label="Java">

```java
// On the sealed interface: required for Single-Table, optional for Joined Table
@Discriminator                    // uses default column name "dtype"
sealed interface Pet extends Entity<Integer> permits Cat, Dog {
    String name();
}

// Or with a custom column name
@Discriminator(column = "pet_type")
sealed interface Pet extends Entity<Integer> permits Cat, Dog {
    String name();
}

// Joined Table without @Discriminator: type is resolved via extension table PKs
@Polymorphic(JOINED)
sealed interface Pet extends Entity<Integer> permits Cat, Dog {
    String name();
}

// On a subtype: customize the discriminator value (optional)
@Discriminator("LARGE_DOG")
record Dog(@PK Integer id, String name, int weight) implements Pet {}
```

</TabItem>
</Tabs>

Discriminator values default to the simple class name (e.g., `"Cat"`, `"Dog"`) for Single-Table and Joined Table, or the resolved table name for Polymorphic FK.

### Discriminator Types

The `@Discriminator` annotation supports a `type()` attribute that controls the SQL column type used for the discriminator. This attribute is only meaningful on the sealed interface (where it defines the column type); on subtypes and FK fields it is ignored.

Storm supports three discriminator types:

| Type | SQL Column | Value Format | Example |
|------|-----------|-------------|---------|
| `STRING` (default) | `VARCHAR` | Class name or custom string | `"Cat"`, `"LARGE_DOG"` |
| `INTEGER` | `INTEGER` | Integer parsed from `value()` | `"1"`, `"2"` |
| `CHAR` | `CHAR(1)` | Single character from `value()` | `"C"`, `"D"` |

`STRING` is the default and works well for most cases: the discriminator column stores human-readable values like the class name. `INTEGER` is useful when your schema already uses numeric type codes, or when you want a compact discriminator that matches an existing integer column. `CHAR` provides a middle ground: a single character is more compact than a full string but still readable, and maps to a fixed-width `CHAR(1)` column.

When using `INTEGER` or `CHAR`, every subtype must declare an explicit `@Discriminator` value, since numeric and character values cannot be derived automatically from the class name.

#### STRING (default)

The default type. The discriminator column is `VARCHAR`, and values are either the simple class name or a custom string.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Discriminator
sealed interface Pet : Entity<Int>

data class Cat(@PK val id: Int = 0, val name: String) : Pet

data class Dog(@PK val id: Int = 0, val name: String) : Pet
// Discriminator values: "Cat", "Dog"
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Discriminator
sealed interface Pet extends Entity<Integer> permits Cat, Dog {}

record Cat(@PK Integer id, String name) implements Pet {}

record Dog(@PK Integer id, String name) implements Pet {}
// Discriminator values: "Cat", "Dog"
```

</TabItem>
</Tabs>

#### INTEGER

The discriminator column is `INTEGER`. Each subtype must specify a numeric value via `@Discriminator("...")`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Discriminator(type = DiscriminatorType.INTEGER)
@DbTable("vehicle")
sealed interface Vehicle : Entity<Int>

@Discriminator("1")
data class Car(@PK val id: Int = 0, val model: String) : Vehicle

@Discriminator("2")
data class Truck(@PK val id: Int = 0, val payload: Int) : Vehicle
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Discriminator(type = DiscriminatorType.INTEGER)
@DbTable("vehicle")
sealed interface Vehicle extends Entity<Integer> permits Car, Truck {}

@Discriminator("1")
record Car(@PK Integer id, String model) implements Vehicle {}

@Discriminator("2")
record Truck(@PK Integer id, int payload) implements Vehicle {}
```

</TabItem>
</Tabs>

#### CHAR

The discriminator column is `CHAR(1)`. Each subtype must specify a single-character value via `@Discriminator("...")`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Discriminator(type = DiscriminatorType.CHAR)
sealed interface Status : Entity<Int>

@Discriminator("A")
data class Active(@PK val id: Int = 0, val since: LocalDate) : Status

@Discriminator("I")
data class Inactive(@PK val id: Int = 0, val reason: String) : Status
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Discriminator(type = DiscriminatorType.CHAR)
sealed interface Status extends Entity<Integer> permits Active, Inactive {}

@Discriminator("A")
record Active(@PK Integer id, LocalDate since) implements Status {}

@Discriminator("I")
record Inactive(@PK Integer id, String reason) implements Status {}
```

</TabItem>
</Tabs>

The `type()` attribute works with all three inheritance strategies that use a discriminator: Single-Table, Joined Table (with `@Discriminator`), and Polymorphic FK.

---

## Single-Table Inheritance

All subtypes share a single database table. A discriminator column distinguishes between subtypes, and subtype-specific columns are NULL for rows belonging to other subtypes. Because all data lives in one table, queries require no JOINs, which keeps them fast and straightforward. The trade-off is that the table accumulates columns from all subtypes, which can become unwieldy if subtypes have many distinct fields. This strategy maps naturally to the common pattern of a single table with a type column.

### Database Schema

```sql
CREATE TABLE pet (
    id       INTEGER AUTO_INCREMENT PRIMARY KEY,
    dtype    VARCHAR(50) NOT NULL,    -- discriminator column
    name     VARCHAR(255),            -- shared by all subtypes
    indoor   BOOLEAN,                 -- Cat-specific (NULL for Dogs)
    weight   INTEGER                  -- Dog-specific (NULL for Cats)
);
```

The discriminator column (`dtype`) stores the subtype name and is automatically populated by Storm during inserts. Subtype-specific columns use NULL as their zero-value for rows that belong to a different subtype:

```
  pet table
  ┌────┬───────┬──────────┬────────┬────────┐
  │ id │ dtype │   name   │ indoor │ weight │
  ├────┼───────┼──────────┼────────┼────────┤
  │  1 │ Cat   │ Whiskers │  true  │  NULL  │
  │  2 │ Cat   │ Luna     │ false  │  NULL  │
  │  3 │ Dog   │ Rex      │  NULL  │   30   │
  │  4 │ Dog   │ Max      │  NULL  │   15   │
  └────┴───────┴──────────┴────────┴────────┘
```

### Defining Entities

The sealed interface is the entity. Any sealed interface extending `Entity` without `@Polymorphic(JOINED)` is detected as Single-Table. The sealed interface must be annotated with `@Discriminator` to declare the discriminator column. Subtypes are data classes (Kotlin) or records (Java) that implement the sealed interface. Each subtype defines its own fields; fields shared across all subtypes (like `id` and `name` above) go into the shared table alongside subtype-specific fields.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@Discriminator
sealed interface Pet : Entity<Int>

data class Cat(
    @PK val id: Int = 0,
    val name: String,
    val indoor: Boolean
) : Pet

data class Dog(
    @PK val id: Int = 0,
    val name: String,
    val weight: Int
) : Pet
```

</TabItem>
<TabItem value="java" label="Java">

```java
@Discriminator
sealed interface Pet extends Entity<Integer> permits Cat, Dog {}

record Cat(@PK Integer id, String name, boolean indoor) implements Pet {}

record Dog(@PK Integer id, String name, int weight) implements Pet {}
```

</TabItem>
</Tabs>

The table name (`pet`) is derived automatically from the class name. Use `@DbTable` only if the table name differs from the default (e.g., `@DbTable("animals")`).

### CRUD Operations

All CRUD operations go through the sealed interface type. Storm determines the concrete subtype at runtime: on SELECT, it reads the discriminator value from the result set; on INSERT and UPDATE, it inspects the record's runtime class.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pets = orm.entity(Pet::class)

// Select all pets - returns Cat and Dog instances
val all: List<Pet> = pets.select().resultList
for (pet in all) {
    when (pet) {
        is Cat -> println("Cat: ${pet.name}, indoor=${pet.indoor}")
        is Dog -> println("Dog: ${pet.name}, ${pet.weight}kg")
    }
}

// Insert a new Cat
pets.insert(Cat(name = "Bella", indoor = true))

// Update
pets.update(Cat(id = 1, name = "Sir Whiskers", indoor = true))

// Remove
pets.remove(somePet)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var pets = orm.entity(Pet.class);

// Select all pets - returns Cat and Dog instances
var all = pets.select().getResultList();
for (var pet : all) {
    switch (pet) {
        case Cat c -> System.out.println("Cat: " + c.name() + ", indoor=" + c.indoor());
        case Dog d -> System.out.println("Dog: " + d.name() + ", " + d.weight() + "kg");
    }
}

// Insert a new Cat
pets.insert(new Cat(null, "Bella", true));

// Update
pets.update(new Cat(1, "Sir Whiskers", true));

// Remove
pets.remove(somePet);
```

</TabItem>
</Tabs>

### Generated SQL

Storm automatically includes the discriminator column in SELECT queries and populates it during inserts. The discriminator value is derived from the record's class name (or from `@Discriminator` if customized). On UPDATE and DELETE, the discriminator is not included in the SET or WHERE clause because the primary key is sufficient to identify the row.

The table below shows the SQL generated for each operation. Because all subtypes share one table, every operation is a single SQL statement.

```
  Operation       Generated SQL
  ─────────       ─────────────

  SELECT all      SELECT p.id, p.dtype, p.name, p.indoor, p.weight
                  FROM pet p

  INSERT Cat      INSERT INTO pet (dtype, name, indoor)
                  VALUES ('Cat', 'Bella', true)

  INSERT Dog      INSERT INTO pet (dtype, name, weight)
                  VALUES ('Dog', 'Buddy', 25)

  UPDATE          UPDATE pet
                  SET name = 'Sir Whiskers', indoor = true
                  WHERE id = 1

  DELETE          DELETE FROM pet
                  WHERE id = 1
```

Notice that INSERT only includes the columns relevant to the concrete subtype. Columns belonging to other subtypes are omitted entirely (they default to NULL in the database). The SELECT, by contrast, always includes all columns from all subtypes, because the query does not know in advance which subtypes will appear in the result set.

### Foreign Keys to Single-Table Entities

Other entities reference the shared table with a regular single-column foreign key. Since all subtypes live in the same table, the FK column always points to one table regardless of which concrete subtype the row represents. This is one of the advantages of Single-Table: foreign key relationships are simple and standard.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Visit(
    @PK val id: Int = 0,
    @FK val pet: Ref<Pet>   // FK to pet.id
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Visit(@PK Integer id,
             @FK Ref<Pet> pet   // FK to pet.id
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

```
  visit table                       pet table
  ┌────┬────────┐                  ┌────┬───────┬──────────┬────────┬────────┐
  │ id │ pet_id │                  │ id │ dtype │   name   │ indoor │ weight │
  ├────┼────────┤                  ├────┼───────┼──────────┼────────┼────────┤
  │  1 │   1    │─────────────────▶│  1 │ Cat   │ Whiskers │  true  │  NULL  │
  │  2 │   3    │────────┐         │  2 │ Cat   │ Luna     │ false  │  NULL  │
  └────┴────────┘        └────────▶│  3 │ Dog   │ Rex      │  NULL  │   30   │
                                   │  4 │ Dog   │ Max      │  NULL  │   15   │
                                   └────┴───────┴──────────┴────────┴────────┘
```

### Hydration

When Storm reads a result set for a sealed entity type, it uses the discriminator value to determine which concrete subtype to construct. The result set contains the union of all subtype columns, but each row only has meaningful values for the columns that belong to its subtype. Storm reads the discriminator first, resolves it to the corresponding record class, and then extracts only the fields that class declares. Fields belonging to other subtypes are ignored.

```
  Result Set Row
  ┌────┬───────┬──────────┬────────┬────────┐
  │ id │ dtype │   name   │ indoor │ weight │
  ├────┼───────┼──────────┼────────┼────────┤
  │  1 │ Cat   │ Whiskers │  true  │  NULL  │
  └────┴───┬───┴──────────┴────────┴────────┘
           │
           ▼
  ┌─────────────────────────────┐
  │  Discriminator: "Cat"       │
  │       │                     │
  │       ▼                     │
  │  Resolve to Cat.class       │
  │       │                     │
  │       ▼                     │
  │  Construct:                 │
  │  Cat(id=1,                  │
  │      name="Whiskers",       │
  │      indoor=true)           │
  └─────────────────────────────┘
```

This means adding a new subtype with new fields only requires adding columns to the existing table and a new record class. No changes to existing subtypes or queries are needed. The sealed type hierarchy guarantees that Storm will use the correct record class for each discriminator value, and pattern matching ensures that application code handles the new subtype at every relevant point.

---

## Joined Table Inheritance

Joined Table inheritance splits the data across multiple tables: a base table holds fields shared by all subtypes plus a discriminator column, and each subtype has its own extension table with subtype-specific fields. The extension table's primary key is also a foreign key to the base table, establishing a one-to-one relationship.

This strategy works well when subtypes have many distinct fields and you want a normalized schema without NULL columns. The trade-off is that every query requires LEFT JOINs to the extension tables, and DML operations touch multiple tables within a single logical operation. In return, the schema stays clean: each table contains only the columns that are meaningful for its rows.

### With and Without `@Discriminator`

Joined Table supports two modes of type resolution:

**With `@Discriminator`** (explicit discriminator column): The base table includes a discriminator column (e.g., `dtype`) that stores the subtype name. This is the same approach as Single-Table. Extension tables only need rows for subtypes that have subtype-specific fields.

**Without `@Discriminator`** (implicit type resolution): The base table has no discriminator column. Instead, Storm generates a `CASE` expression at query time that checks which extension table has a matching row. Every subtype must have an extension table, even if it has no subtype-specific fields, because the extension table row serves as the type marker. This aligns with Hibernate's default behavior for `@Inheritance(strategy = JOINED)` without `@DiscriminatorColumn`.

### Database Schema

With `@Discriminator`:

```sql
-- Base table: shared fields + discriminator
CREATE TABLE pet (
    id     INTEGER AUTO_INCREMENT PRIMARY KEY,
    dtype  VARCHAR(50) NOT NULL,
    name   VARCHAR(255)
);

-- Extension tables: subtype-specific fields
CREATE TABLE cat (
    id      INTEGER PRIMARY KEY REFERENCES pet(id),
    indoor  BOOLEAN
);

CREATE TABLE dog (
    id      INTEGER PRIMARY KEY REFERENCES pet(id),
    weight  INTEGER
);
```

Without `@Discriminator`:

```sql
-- Base table: shared fields only, no discriminator column
CREATE TABLE pet (
    id     INTEGER AUTO_INCREMENT PRIMARY KEY,
    name   VARCHAR(255)
);

-- Extension tables: subtype-specific fields
CREATE TABLE cat (
    id      INTEGER PRIMARY KEY REFERENCES pet(id),
    indoor  BOOLEAN
);

CREATE TABLE dog (
    id      INTEGER PRIMARY KEY REFERENCES pet(id),
    weight  INTEGER
);

-- PK-only extension table for subtypes without extra fields
CREATE TABLE bird (
    id      INTEGER PRIMARY KEY REFERENCES pet(id)
);
```

Note that `Bird` has no subtype-specific fields, but still needs an extension table when no discriminator is present. The extension table row acts as the type marker.

Each extension table's primary key references the base table. This foreign key constraint ensures referential integrity: an extension row cannot exist without a corresponding base row, and the same ID is used across all tables for a given entity.

```
  pet (base)                              cat (extension)
  ┌────┬───────┬──────────┐              ┌────┬────────┐
  │ id │ dtype │   name   │              │ id │ indoor │
  ├────┼───────┼──────────┤              ├────┼────────┤
  │  1 │ Cat   │ Whiskers │◀────────────▶│  1 │  true  │
  │  2 │ Cat   │ Luna     │◀────────────▶│  2 │ false  │
  │  3 │ Dog   │ Rex      │              └────┴────────┘
  └────┴───────┴──────────┘
           │                              dog (extension)
           │                              ┌────┬────────┐
           │                              │ id │ weight │
           │                              ├────┼────────┤
           └─────────────────────────────▶│  3 │   30   │
                                          └────┴────────┘
```

### Field Partitioning

Storm automatically determines which fields belong to the base table and which belong to extension tables by comparing the fields across all subtypes. The rule is straightforward: fields that appear with the same name and type in every subtype go to the base table, while fields unique to a single subtype go to that subtype's extension table. The primary key is always in the base table.

| Field | Cat | Dog | Location |
|-------|-----|-----|----------|
| `id` (Integer) | Yes | Yes | Base table |
| `name` (String) | Yes | Yes | Base table |
| `indoor` (boolean) | Yes | No | `cat` extension |
| `weight` (int) | No | Yes | `dog` extension |

This partitioning is computed once per sealed type and cached. You do not need to annotate fields to indicate which table they belong to; Storm infers it from the type structure. If a subtype has no extension-specific fields (all its fields are shared) and a `@Discriminator` is present, no extension table is needed for that subtype. Without `@Discriminator`, every subtype always requires an extension table (even if it only contains the primary key), because the extension table row serves as the type marker.

### Defining Entities

Add `@Polymorphic(JOINED)` to the sealed interface to opt into this strategy. `@Discriminator` is optional: include it for a discriminator column in the base table, or omit it for implicit type resolution via extension table PKs. Table names for the base table and extension tables are derived automatically from the class names (`Pet` resolves to `pet`, `Cat` to `cat`, `Dog` to `dog`). Use `@DbTable` on the sealed interface or subtypes to override these names.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

With `@Discriminator`:

```kotlin
@Discriminator
@Polymorphic(JOINED)
sealed interface Pet : Entity<Int> {
    val name: String
}

data class Cat(
    @PK override val id: Int = 0,
    override val name: String,
    val indoor: Boolean
) : Pet

data class Dog(
    @PK override val id: Int = 0,
    override val name: String,
    val weight: Int
) : Pet
```

Without `@Discriminator`:

```kotlin
@Polymorphic(JOINED)
sealed interface Pet : Entity<Int> {
    val name: String
}

data class Cat(
    @PK override val id: Int = 0,
    override val name: String,
    val indoor: Boolean
) : Pet

data class Dog(
    @PK override val id: Int = 0,
    override val name: String,
    val weight: Int
) : Pet

// Bird has no extension fields, but still gets an extension table
data class Bird(
    @PK override val id: Int = 0,
    override val name: String
) : Pet
```

</TabItem>
<TabItem value="java" label="Java">

With `@Discriminator`:

```java
@Discriminator
@Polymorphic(JOINED)
sealed interface Pet extends Entity<Integer> permits Cat, Dog {
    String name();
}

record Cat(@PK Integer id, String name, boolean indoor) implements Pet {}

record Dog(@PK Integer id, String name, int weight) implements Pet {}
```

Without `@Discriminator`:

```java
@Polymorphic(JOINED)
sealed interface Pet extends Entity<Integer> permits Cat, Dog, Bird {
    String name();
}

record Cat(@PK Integer id, String name, boolean indoor) implements Pet {}

record Dog(@PK Integer id, String name, int weight) implements Pet {}

// Bird has no extension fields, but still gets an extension table
record Bird(@PK Integer id, String name) implements Pet {}
```

</TabItem>
</Tabs>

### CRUD Operations

CRUD operations work through the sealed interface type, just like Single-Table. The API is identical. However, under the hood Storm generates multi-table SQL: inserts and updates touch both the base and extension tables, and deletes remove from extension tables first (to satisfy foreign key constraints) before removing the base row.

> **Transactional context required.** All multi-table DML operations (insert, update, delete) for Joined Table entities execute within the current transaction. Because these operations touch multiple tables, they require a transactional context to guarantee atomicity. If any step fails, the entire operation rolls back. Make sure your code runs inside a `transaction {}` block (Kotlin), a Spring `@Transactional` method, or equivalent transactional scope.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pets = orm.entity(Pet::class)

// Select all - Storm auto-joins extension tables
val all: List<Pet> = pets.select().resultList

// Insert a Cat - inserts into base table, then extension table
pets.insert(Cat(name = "Bella", indoor = true))

// Update a Cat - updates both base and extension tables
pets.update(Cat(id = 1, name = "Sir Whiskers", indoor = true))

// Remove - deletes from extension table first, then base table
pets.remove(somePet)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var pets = orm.entity(Pet.class);

// Select all - Storm auto-joins extension tables
var all = pets.select().getResultList();

// Insert a Cat - inserts into base table, then extension table
pets.insert(new Cat(null, "Bella", true));

// Update a Cat - updates both base and extension tables
pets.update(new Cat(1, "Sir Whiskers", true));

// Remove - deletes from extension table first, then base table
pets.remove(somePet);
```

</TabItem>
</Tabs>

### Generated SQL

SELECT queries use LEFT JOINs to bring together the base and extension table columns. LEFT JOIN (rather than INNER JOIN) is used because each row matches only one extension table; the non-matching extension tables produce NULLs.

With `@Discriminator`, the discriminator column is read directly from the base table:

```sql
SELECT p.id, p.dtype, p.name, c.indoor, d.weight
FROM pet p
LEFT JOIN cat c ON p.id = c.id
LEFT JOIN dog d ON p.id = d.id
```

Without `@Discriminator`, Storm generates a `CASE` expression that resolves the concrete type by checking which extension table has a matching row:

```sql
SELECT p.id,
       CASE WHEN c.id IS NOT NULL THEN 'Cat'
            WHEN d.id IS NOT NULL THEN 'Dog'
            WHEN b.id IS NOT NULL THEN 'Bird' END,
       p.name, c.indoor, d.weight
FROM pet p
LEFT JOIN cat c ON p.id = c.id
LEFT JOIN dog d ON p.id = d.id
LEFT JOIN bird b ON p.id = b.id
```

Unlike Single-Table, DML operations for Joined Table entities are multi-statement: they involve more than one table. Storm executes all statements within the current transaction to ensure atomicity. Each operation follows a specific order to respect foreign key constraints between the base and extension tables.

**INSERT** first writes to the base table (which owns the auto-generated primary key), then uses the generated key to insert into the extension table. The base table must come first because the extension table's primary key references it:

```
  INSERT Cat(null, "Whiskers", true)
  ─────────────────────────────────────────────────────────────────

  Step 1:  INSERT INTO pet (dtype, name)
           VALUES ('Cat', 'Whiskers')
                                            │
                                            ▼
                                   generated id = 5

  Step 2:  INSERT INTO cat (id, indoor)
           VALUES (5, true)
```

**UPDATE** follows the same order: shared fields are written to the base table first, then subtype-specific fields are written to the extension table. If a subtype has no extension-specific fields, the second statement is skipped entirely.

```
  UPDATE Cat(1, "Sir Whiskers", true)
  ─────────────────────────────────────────────────────────────────

  Step 1:  UPDATE pet SET name = 'Sir Whiskers'
           WHERE id = 1

  Step 2:  UPDATE cat SET indoor = true
           WHERE id = 1
```

**DELETE** reverses the order: extension tables are deleted first to satisfy the foreign key constraint, then the base table row is removed. When deleting by ID without knowing the concrete type, Storm attempts to delete from all extension tables (at most one will have a matching row).

```
  DELETE Pet(1)
  ─────────────────────────────────────────────────────────────────

  Step 1:  DELETE FROM cat WHERE id = 1   (extension first)
           DELETE FROM dog WHERE id = 1   (all extensions)

  Step 2:  DELETE FROM pet WHERE id = 1   (base last)
```

Note that SQL-level upsert operations (`INSERT ... ON CONFLICT`, `MERGE`, etc.) are not supported for Joined Table entities, because these SQL constructs are fundamentally single-table operations. Storm will throw a clear error if you attempt an upsert on a joined sealed entity. You can still use `insert()` and `update()` separately, which correctly handle the multi-table logic.

### Foreign Keys to Joined Table Entities

Foreign keys reference the base table, just like Single-Table. From the referencing entity's perspective, there is no difference between pointing to a Single-Table or Joined Table entity. When Storm joins to a Joined Table entity (e.g., loading a `Visit` with its `Pet`), it automatically chains the extension table LEFT JOINs.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Visit(
    @PK val id: Int = 0,
    @FK val pet: Ref<Pet>   // FK to pet.id
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Visit(@PK Integer id,
             @FK Ref<Pet> pet   // FK to pet.id
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

When querying Visit with a join to Pet, Storm generates:

```sql
SELECT v.*, p.id, p.dtype, p.name, c.indoor, d.weight
FROM visit v
INNER JOIN pet p ON v.pet_id = p.id
LEFT JOIN cat c ON p.id = c.id
LEFT JOIN dog d ON p.id = d.id
```

### Hydration

Hydration works the same way as Single-Table: the discriminator value determines the concrete subtype. The only difference is that subtype-specific field values come from different tables in the result set (via the LEFT JOINs), rather than from NULL columns in a shared table.

```
  Result Set (after JOINs)
  ┌────┬───────┬──────────┬────────┬────────┐
  │ id │ dtype │   name   │ indoor │ weight │
  ├────┼───────┼──────────┼────────┼────────┤
  │  1 │ Cat   │ Whiskers │  true  │  NULL  │  ← indoor from cat table
  │  3 │ Dog   │ Rex      │  NULL  │   30   │  ← weight from dog table
  └────┴───────┴──────────┴────────┴────────┘
                │
                ▼
  ┌──────────────────────────────────────────────┐
  │  Row 1: dtype = "Cat"                        │
  │    → Cat(id=1, name="Whiskers", indoor=true) │
  │                                              │
  │  Row 3: dtype = "Dog"                        │
  │    → Dog(id=3, name="Rex", weight=30)        │
  └──────────────────────────────────────────────┘
```

Adding a new subtype means creating a new extension table and a new record class. The base table gains no new columns, and existing subtypes are not affected. This makes Joined Table a good fit for hierarchies that evolve over time, since adding a subtype does not alter the schema of any existing table.

### Type Changes

Storm supports changing an entity's subtype via update. For example, if a `Cat` needs to become a `Dog`, you can update it by passing a `Dog` instance with the same primary key:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Convert a Cat to a Dog (same ID, different subtype)
pets.update(Dog(id = existingCatId, name = "Rex", weight = 30))
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Convert a Cat to a Dog (same ID, different subtype)
pets.update(new Dog(existingCatId, "Rex", 30));
```

</TabItem>
</Tabs>

Under the hood, Storm executes three operations:

1. **UPDATE** the base table with the new shared field values (and the new discriminator value, if present).
2. **DELETE** the old extension table row (e.g., remove the row from `cat`).
3. **INSERT** a new extension table row (e.g., insert a row into `dog`).

This sequence ensures that the base table row is preserved (keeping all foreign key references intact), while the subtype-specific data is swapped. Foreign key references from other entities should always target the base table, so the type change is transparent to referencing entities.

Type changes require a transactional context for atomicity, since the operation spans multiple tables. This works for both discriminated and discriminator-less Joined Table inheritance.

### Batch Operations

Storm supports batch operations with mixed subtypes. You can pass a list containing different concrete subtypes to `insert()`, `update()`, or `remove()`, and Storm handles them correctly.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Insert a mix of Cats and Dogs in one call
pets.insert(listOf(
    Cat(name = "Whiskers", indoor = true),
    Dog(name = "Rex", weight = 30),
    Cat(name = "Luna", indoor = false)
))

// Update mixed subtypes
pets.update(listOf(updatedCat, updatedDog))

// Remove mixed subtypes
pets.remove(listOf(someCat, someDog))
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Insert a mix of Cats and Dogs in one call
pets.insert(List.of(
    new Cat(null, "Whiskers", true),
    new Dog(null, "Rex", 30),
    new Cat(null, "Luna", false)
));

// Update mixed subtypes
pets.update(List.of(updatedCat, updatedDog));

// Remove mixed subtypes
pets.remove(List.of(someCat, someDog));
```

</TabItem>
</Tabs>

For the base table, Storm issues a single batch statement covering all entities regardless of subtype. For extension tables, Storm partitions the entities by subtype and issues a separate batch statement per extension table. This means a batch insert of 2 Cats and 1 Dog results in one batch INSERT into the `pet` base table (3 rows), one batch INSERT into the `cat` extension table (2 rows), and one batch INSERT into the `dog` extension table (1 row).

---

## Polymorphic Foreign Keys

Sometimes a foreign key needs to point to different tables depending on context. A comment might reference a post, a photo, or any other commentable entity. Each target type has its own independent table with its own schema. The sealed interface is NOT an entity itself; it serves purely as a type constraint for the FK relationship.

This strategy differs fundamentally from Single-Table and Joined Table. In those strategies, the sealed interface represents a single logical table (or table group) in the database. With Polymorphic FK, the sealed interface represents a set of unrelated tables, and the polymorphism is expressed through a two-column foreign key: one column identifies which table, and the other identifies which row.

This strategy is best for cross-cutting concerns like comments, tags, likes, or audit logs that apply to multiple unrelated entity types.

### Database Schema

The target entities live in their own independent tables with no shared base table. The referencing entity stores two columns: a discriminator that identifies the target table, and an ID that identifies the row within that table.

```sql
-- Independent tables (no shared base table)
CREATE TABLE post  (id INTEGER AUTO_INCREMENT PRIMARY KEY, title VARCHAR(255));
CREATE TABLE photo (id INTEGER AUTO_INCREMENT PRIMARY KEY, url VARCHAR(255));

-- Referencing table with discriminator + FK columns
CREATE TABLE comment (
    id          INTEGER AUTO_INCREMENT PRIMARY KEY,
    text        VARCHAR(255),
    target_type VARCHAR(50),   -- discriminator: "post" or "photo"
    target_id   INTEGER        -- FK value (points to post.id or photo.id)
);
```

Note that `target_id` cannot have a database-level foreign key constraint, because it may point to different tables depending on the value of `target_type`. Referential integrity must be maintained at the application level.

```
  comment table
  ┌────┬──────────────┬─────────────┬───────────┐
  │ id │     text     │ target_type │ target_id │
  ├────┼──────────────┼─────────────┼───────────┤
  │  1 │ Nice post!   │    post     │     1     │──────────▶ post.id = 1
  │  2 │ Great photo! │    photo    │     1     │──────────▶ photo.id = 1
  │  3 │ Love it!     │    post     │     2     │──────────▶ post.id = 2
  └────┴──────────────┴─────────────┴───────────┘

  post table                    photo table
  ┌────┬──────────────┐         ┌────┬────────────┐
  │ id │    title     │         │ id │    url     │
  ├────┼──────────────┤         ├────┼────────────┤
  │  1 │ Hello World  │         │  1 │ photo1.jpg │
  │  2 │ Second Post  │         │  2 │ photo2.jpg │
  └────┴──────────────┘         └────┴────────────┘
```

### Defining Entities

The sealed interface extends `Data` (not `Entity`) and does NOT have `@DbTable`. This is what distinguishes Polymorphic FK from the other two strategies: the sealed interface is not table-backed. Each subtype is an independent entity with its own `@PK` and its own table. Table names are derived from the class name by the table name resolver (e.g., `Post` resolves to `post`).

> **Why `Data` and not `Entity`?** In Storm, `Entity<ID>` represents a type that maps to a specific database table. For Polymorphic FK, the sealed interface does not correspond to any table; it is a pure type-level grouping of unrelated entities. `Data` is the correct marker because it tells Storm "this type participates in SQL generation (column resolution, type mapping) but has no table of its own." Each subtype independently implements `Entity<ID>` because each one *does* map to its own table. This separation is what makes the two-column foreign key possible: the discriminator identifies which subtype (and therefore which table), and the ID identifies the row within that table.

The referencing entity uses `@FK Ref<Commentable>` to declare the polymorphic foreign key. `Ref` is required here because the target spans multiple independent tables, so it cannot be eagerly loaded via a JOIN. The `Ref` acts as a lightweight handle that stores the concrete type and ID, and can be fetched on demand. When Storm encounters an `@FK Ref` targeting a sealed `Data` type, it automatically generates two columns (discriminator + ID) instead of the usual single FK column.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Sealed Data interface - NOT an entity, just a type constraint
sealed interface Commentable : Data

data class Post(
    @PK val id: Int = 0,
    val title: String
) : Commentable, Entity<Int>

data class Photo(
    @PK val id: Int = 0,
    val url: String
) : Commentable, Entity<Int>

// Entity with polymorphic FK
data class Comment(
    @PK val id: Int = 0,
    val text: String,
    @FK val target: Ref<Commentable>   // produces target_type + target_id columns
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Sealed Data interface - NOT an entity, just a type constraint
sealed interface Commentable extends Data permits Post, Photo {}

record Post(@PK Integer id, String title)
        implements Commentable, Entity<Integer> {}

record Photo(@PK Integer id, String url)
        implements Commentable, Entity<Integer> {}

// Entity with polymorphic FK
record Comment(@PK Integer id, String text,
               @FK Ref<Commentable> target   // produces target_type + target_id columns
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

### Column Generation

A regular `@FK` field produces a single column (e.g., `pet_id`). A polymorphic `@FK` targeting a sealed `Data` interface is different: Storm needs two pieces of information to resolve the reference (which table and which row), so it generates two columns instead of one.

| FK Field | Generated Columns | Column Types |
|----------|-------------------|-------------|
| `target: Ref<Commentable>` | `target_type` (VARCHAR) + `target_id` (INTEGER) | Discriminator + PK type |

The discriminator column name defaults to `{fieldName}_type`, and the FK column name defaults to `{fieldName}_id`. Both can be customized with `@Discriminator` and `@DbColumn` if your schema uses different naming conventions.

### Customizing Column Names

Use `@Discriminator` on the FK field to customize the discriminator column name. Unlike sealed entity interfaces where `@Discriminator` is required, on FK fields it is purely optional, because the default naming convention (`{fieldName}_type`) derives from the field name and is predictable.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Comment(
    @PK val id: Int = 0,
    val text: String,
    @FK @Discriminator(column = "content_type") val target: Ref<Commentable>
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Comment(@PK Integer id,
               String text,
               @FK @Discriminator(column = "content_type") Ref<Commentable> target
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

This produces `content_type` and `target_id` columns instead of `target_type` and `target_id`.

### CRUD Operations

Each subtype is an independent entity with its own repository. You insert, update, and delete subtypes using their own entity type, not through the sealed interface. The polymorphic FK only appears in the referencing entity (e.g., `Comment`). When creating a `Comment`, you obtain a `Ref` from an existing entity to establish the relationship.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// CRUD on subtypes - standard entity operations
val posts = orm.entity(Post::class)
val post = posts.insertAndFetch(Post(title = "New Post"))

// Insert a comment referencing the post
val comments = orm.entity(Comment::class)
comments.insert(Comment(
    text = "Great post!",
    target = post.ref()
))
```

</TabItem>
<TabItem value="java" label="Java">

```java
// CRUD on subtypes - standard entity operations
var posts = orm.entity(Post.class);
var post = posts.insertAndFetch(new Post(null, "New Post"));

// Insert a comment referencing the post
var comments = orm.entity(Comment.class);
comments.insert(new Comment("Great post!", Ref.of(post)));
```

</TabItem>
</Tabs>

### Generated SQL

Storm derives the discriminator value from the `Ref`'s target type. By default, the resolved table name of the concrete subtype is used as the discriminator value (e.g., `Post` resolves to `"post"`). This means the discriminator value in the database directly corresponds to the target table name, making it easy to reason about the data.

**INSERT Comment:**

```sql
INSERT INTO comment (text, target_type, target_id)
VALUES ('Great post!', 'post', 1)
```

**SELECT Comment:**

```sql
SELECT c.id, c.text, c.target_type, c.target_id
FROM comment c
```

### Loading the Target

Polymorphic FK targets cannot be auto-joined. With Single-Table and Joined Table, Storm can always generate a JOIN because there is one known base table. With Polymorphic FK, the target could be in any of several independent tables, and a single JOIN cannot span multiple unrelated tables conditionally. Instead, use `Ref.fetch()` to load the referenced entity on demand. The `Ref` already knows the concrete target type (from the discriminator value), so `fetch()` queries the correct table automatically.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val comments = orm.entity(Comment::class).select().resultList
for (comment in comments) {
    val target: Commentable = comment.target.fetch()
    when (target) {
        is Post  -> println("Comment on post: ${target.title}")
        is Photo -> println("Comment on photo: ${target.url}")
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
var comments = orm.entity(Comment.class).select().getResultList();
for (var comment : comments) {
    Commentable target = comment.target().fetch();
    switch (target) {
        case Post p  -> System.out.println("Comment on post: " + p.title());
        case Photo p -> System.out.println("Comment on photo: " + p.url());
    }
}
```

</TabItem>
</Tabs>

### Hydration

Polymorphic FK fields consume two columns from the result set. Storm reads the discriminator to determine the target type, then wraps the FK value in a `Ref` of the correct concrete type. No actual entity is loaded at this point; the `Ref` is a lightweight handle that can be used to fetch the full entity later.

```
  Result Set Row
  ┌────┬──────────────┬─────────────┬───────────┐
  │ id │     text     │ target_type │ target_id │
  ├────┼──────────────┼─────────────┼───────────┤
  │  1 │ Nice post!   │    post     │     1     │
  └────┴──────────────┴──────┬──────┴─────┬─────┘
                             │            │
                             ▼            ▼
                    ┌─────────────────────────────┐
                    │  target_type = "post"       │
                    │    → resolve to Post.class  │
                    │                             │
                    │  target_id = 1              │
                    │    → Ref.of(Post.class, 1)  │
                    └─────────────────────────────┘
```

The resulting `Ref<Commentable>` knows its concrete type is `Post` and holds ID 1. Calling `fetch()` queries the `post` table for that ID. This two-phase approach (hydrate a lightweight `Ref`, then fetch the full entity on demand) keeps the initial query simple and avoids the complexity of conditional multi-table JOINs.

---

## Choosing a Strategy

The right strategy depends on the relationship between your subtypes and how you query them. Use the following decision tree as a starting point:

```
  Do all subtypes share the same table?
  │
  ├── Yes ──▶ Are there many subtype-specific columns?
  │           │
  │           ├── No  ──▶ Single-Table     (simple, fast queries)
  │           │
  │           └── Yes ──▶ Joined Table     (normalized, no NULLs)
  │
  └── No  ──▶ Are the subtypes independent entities
              that happen to share a common trait?
              │
              └── Yes ──▶ Polymorphic FK   (cross-cutting references)
```

Single-Table works well when subtypes share most of their fields and the number of subtype-specific columns is small. Joined Table is a natural fit when subtypes carry many distinct fields and you prefer a normalized schema without NULL columns. Polymorphic FK suits situations where the subtypes are conceptually independent entities that happen to be referenced by a shared concern (comments, tags, audit logs).

### When to Use Each Strategy

The table below offers guidance on when each strategy is a good fit and when it might introduce unnecessary complexity.

| Strategy | Good For | Avoid When |
|----------|---------|------------|
| **Single-Table** | Few subtype-specific fields, high query volume, simple hierarchies | Many subtype-specific fields (too many NULL columns) |
| **Joined Table** | Many subtype-specific fields, normalized schema, data integrity | Simple hierarchies with few distinct fields (unnecessary JOINs) |
| **Polymorphic FK** | Cross-cutting concerns (comments, tags, audit logs), references to unrelated entity types | Frequent joins across the polymorphic boundary |

There is no universally "best" strategy. The choice depends on your schema design goals, query patterns, and the nature of the relationship between your subtypes.

---

## Pattern Matching

One of the key benefits of using sealed types for polymorphism is exhaustive pattern matching. The compiler verifies that all subtypes are handled in every `when` (Kotlin) or `switch` (Java) expression. This means adding a new subtype to the hierarchy produces compile errors at every unhandled location, making it impossible to forget to handle the new case.

This is a significant advantage over string-based discriminators or open class hierarchies. With a string discriminator, forgetting to handle a new type silently falls through to a default branch (or worse, throws an unexpected exception at runtime). With sealed types, the compiler catches the omission before the code even compiles.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
fun describe(pet: Pet): String = when (pet) {
    is Cat -> "${pet.name}: indoor=${pet.indoor}"
    is Dog -> "${pet.name}: ${pet.weight}kg"
    // No else needed - compiler knows all subtypes
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
String describe(Pet pet) {
    return switch (pet) {
        case Cat c -> c.name() + ": indoor=" + c.indoor();
        case Dog d -> d.name() + ": " + d.weight() + "kg";
        // No default needed - compiler knows all subtypes
    };
}
```

</TabItem>
</Tabs>

If you later add a `Bird` subtype to the `Pet` hierarchy, the compiler flags every incomplete `when`/`switch` as an error, guiding you to handle the new case everywhere. This applies to all three inheritance strategies equally, since they all use sealed types as the basis for the polymorphic hierarchy.

---

## Tips

1. **Choose the strategy that matches your schema.** Single-Table suits compact hierarchies with few subtype-specific fields. Joined Table suits hierarchies with many distinct fields and a preference for normalization. Polymorphic FK suits cross-cutting concerns like comments, tags, and audit logs.
2. **Leverage pattern matching.** Sealed types guarantee exhaustive handling. Prefer `when`/`switch` over `is`/`instanceof` chains.
3. **Keep hierarchies shallow.** Storm supports one level of sealed subtyping (interface + records). Deep inheritance chains are not supported and rarely needed with records.
4. **`@Discriminator` is required for Single-Table, optional for Joined Table.** For Single-Table, the default column name `"dtype"` (consistent with JPA) is used when no column name is specified. For Joined Table, omitting `@Discriminator` enables implicit type resolution via extension table PKs.
5. **Polymorphic FK targets cannot be auto-joined.** Use `Ref.fetch()` to load the target entity. This is by design: the target spans multiple tables, so a single JOIN is not possible.
6. **All subtypes must share the same PK type.** Mixing `Integer` and `Long` primary keys within a sealed hierarchy is not supported.
