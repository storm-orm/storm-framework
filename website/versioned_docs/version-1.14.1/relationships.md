# Relationships

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Automatic relationship loading is a core part of Storm's design. The database owns your data model, and your entities capture it as immutable classes. When you define a foreign key, Storm automatically joins the related entity and returns complete, fully populated records in a single query.

This design enables:

- **Single-query loading.** One query returns the complete entity graph, so the declared relationships never cost a query each, and nothing loads behind your back.
- **Type-safe path expressions.** Filter on joined fields with full IDE support, including auto-completion across relationships: `User_.city.name eq "Sunnyvale"`
- **Concise syntax.** No manual joins, no fetch configuration, no lazy loading surprises.
- **Predictable behavior.** What you define is what you get. The entity structure *is* the query structure.

```kotlin
// Define the relationships once
data class Country(
    @PK val code: String,
    val name: String
) : Entity<String>

data class City(
    @PK val id: Int = 0,
    val name: String,
    @FK val country: Country
) : Entity<Int>

data class User(
    @PK val id: Int = 0,
    val name: String,
    @FK val city: City      // Auto-joins City, Country, and all nested relationships
) : Entity<Int>

// Query with type-safe access to nested fields throughout the entire entity graph
val users = orm.findAll(User_.city.country.code eq "US")

// Result: fully populated User with City and Country included
users.forEach { println("${it.name} lives in ${it.city.name}, ${it.city.country.name}") }
```

All relationship types are supported through the `@FK` annotation.

---

## One-to-One / Many-to-One

The most common relationship type. A foreign key field on one entity points to the primary key of another. Storm automatically generates a JOIN when querying and populates the referenced entity in the result.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

Use `@FK` to reference another entity:

```kotlin
data class City(
    @PK val id: Int = 0,
    val name: String,
    val population: Long
) : Entity<Int>

data class User(
    @PK val id: Int = 0,
    val email: String,
    @FK val city: City  // Many users belong to one city
) : Entity<Int>
```

When you query a `User`, the related `City` is automatically loaded:

```kotlin
val user = orm.get(User_.id eq userId)
println(user.city.name)  // City is already loaded
```

</TabItem>
<TabItem value="java" label="Java">

Use `@FK` to reference another entity:

```java
record City(@PK Integer id,
            String name,
            long population
) implements Entity<Integer> {}

record User(@PK Integer id,
            String email,
            @FK City city  // Many users belong to one city
) implements Entity<Integer> {}
```

When you query a `User`, the related `City` is automatically loaded:

```java
Optional<User> user = orm.entity(User.class)
    .select()
    .where(User_.id, EQUALS, userId)
    .getOptionalResult();

user.ifPresent(u -> System.out.println(u.city().name()));  // City is already loaded
```

</TabItem>
</Tabs>

---

## Nullable Relationships

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

When a foreign key can be null (the referenced entity is optional), Storm uses a LEFT JOIN instead of an INNER JOIN. This ensures that parent rows are still returned even when the referenced entity does not exist.

```kotlin
data class User(
    @PK val id: Int = 0,
    val email: String,
    @FK val city: City?  // Nullable = LEFT JOIN
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

In Java, use `@Nullable` on foreign key fields to indicate that the referenced entity is optional. Storm switches from INNER JOIN to LEFT JOIN for nullable foreign keys.

```java
record User(@PK Integer id,
            String email,
            @Nullable @FK City city  // Nullable = LEFT JOIN
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## One-to-Many

Storm does not store collections on the "one" side of a relationship. Instead, query the "many" side and filter by the parent entity. This keeps entities stateless and avoids the lazy-loading pitfalls found in traditional ORMs.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Find all users in a city
val usersInCity: List<User> = orm.findAll(User_.city eq city)
```

To load parents with their children, group the query by the parent path. The same select is executed; the
results are grouped during hydration:

```kotlin
// Load cities with their users in one query
val usersByCity: Map<City, List<User>> = orm.entity<User>()
    .select()
    .orderBy(User_.city)
    .resultGroupedBy(User_.city)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Find all users in a city
List<User> usersInCity = orm.entity(User.class)
    .select()
    .where(User_.city, EQUALS, city)
    .getResultList();
```

To load parents with their children, group the query by the parent path. The same select is executed; the
results are grouped during hydration:

```java
// Load cities with their users in one query
Map<City, List<User>> usersByCity = orm.entity(User.class)
    .select()
    .orderBy(User_.city)
    .getResultGroupedBy(User_.city);
```

</TabItem>
</Tabs>

The grouped terminal returns an unmodifiable, insertion-ordered map: parents appear in the order
their first row is encountered, children in row order within each parent. Because duplicate entities within a
result set are guaranteed to share the same instance, each child's reference to its parent is the map key itself, and repeated
parents are materialized once rather than once per row. The path must resolve to a non-null record for every
result; narrow queries over nullable foreign keys with a `where()` clause first. This replaces the manual
pattern of querying the many side and grouping in memory, and it loads the whole graph in a single query,
without the N+1 queries or the join duplication handling that collection-based ORMs need.

---

## Many-to-Many

Use a join entity with composite primary key:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class UserRolePk(
    val userId: Int,
    val roleId: Int
)

data class UserRole(
    @PK val userRolePk: UserRolePk,
    @FK @Persist(insertable = false, updatable = false) val user: User,
    @FK @Persist(insertable = false, updatable = false) val role: Role
) : Entity<UserRolePk>
```

The `@Persist(insertable = false, updatable = false)` annotation indicates that the FK columns overlap with the composite PK columns. The FK fields are used to load the related entities, but the column values come from the PK during insert/update operations. [Write sets](write-sets.md#junction-tables) recognize this shape: a junction row referencing an unsaved entity is inserted after its parent, with the generated key propagated into the composite PK.

Query through the join entity:

```kotlin
// Find all roles for a user
val userRoles: List<UserRole> = orm.findAll(UserRole_.user eq user)
val roles: List<Role> = userRoles.map { it.role }

// Find all users with a specific role
val userRoles: List<UserRole> = orm.findAll(UserRole_.role eq role)
val users: List<User> = userRoles.map { it.user }

// Find roles for many users at once, grouped per user
val rolesByUser: Map<User, List<Role>> = orm.entity<UserRole>()
    .select()
    .where(UserRole_.user inList users)
    .resultGroupedBy(UserRole_.user)
    .mapValues { (_, userRoles) -> userRoles.map { it.role } }
```

For more control, use explicit join queries:

```kotlin
val roles: List<Role> = orm.entity<Role>()
    .select()
    .innerJoin<UserRole>().on<Role>()
    .where(UserRole_.user eq user)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
record UserRolePk(int userId, int roleId) {}

record UserRole(@PK UserRolePk userRolePk,
                @FK @Persist(insertable = false, updatable = false) User user,
                @FK @Persist(insertable = false, updatable = false) Role role
) implements Entity<UserRolePk> {}
```

The `@Persist(insertable = false, updatable = false)` annotation indicates that the FK columns overlap with the composite PK columns. The FK fields are used to load the related entities, but the column values come from the PK during insert/update operations.

Query through the join entity:

```java
// Find all roles for a user
List<UserRole> userRoles = orm.entity(UserRole.class)
    .select()
    .where(UserRole_.user, EQUALS, user)
    .getResultList();

List<Role> roles = userRoles.stream()
    .map(UserRole::role)
    .toList();

// Find roles for many users at once, grouped per user
Map<User, List<UserRole>> rolesByUser = orm.entity(UserRole.class)
    .select()
    .where(UserRole_.user, IN, users)
    .getResultGroupedBy(UserRole_.user);
```

For more control, use explicit join queries:

```java
List<Role> roles = orm.entity(Role.class)
    .select()
    .innerJoin(UserRole.class).on(Role.class)
    .where(UserRole_.user, EQUALS, user)
    .getResultList();
```

</TabItem>
</Tabs>

---

## Composite Foreign Keys

When referencing an entity with a composite primary key, Storm automatically generates multi-column join conditions:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Entity with composite PK
data class UserRolePk(
    val userId: Int,
    val roleId: Int
)

data class UserRole(
    @PK val pk: UserRolePk,
    @FK val user: User,
    @FK val role: Role,
    val grantedAt: Instant
) : Entity<UserRolePk>

// Entity referencing the composite PK entity
data class AuditLog(
    @PK val id: Int = 0,
    val action: String,
    @FK val userRole: UserRole?  // References entity with composite PK
) : Entity<Int>
```

Storm generates a multi-column join condition:

```sql
LEFT JOIN user_role ur
  ON al.user_id = ur.user_id
  AND al.role_id = ur.role_id
```

**Custom column names:** Use `@DbColumn` annotations to specify custom FK column names:

```kotlin
data class AuditLog(
    @PK val id: Int = 0,
    val action: String,
    @FK @DbColumn("audit_user_id") @DbColumn("audit_role_id") val userRole: UserRole?
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Entity with composite PK
record UserRolePk(int userId, int roleId) {}

record UserRole(@PK UserRolePk pk,
                @FK User user,
                @FK Role role,
                Instant grantedAt
) implements Entity<UserRolePk> {}

// Entity referencing the composite PK entity
record AuditLog(@PK Integer id,
                String action,
                @Nullable @FK UserRole userRole  // References entity with composite PK
) implements Entity<Integer> {}
```

Storm generates a multi-column join condition:

```sql
LEFT JOIN user_role ur
  ON al.user_id = ur.user_id
  AND al.role_id = ur.role_id
```

**Custom column names:** Use `@DbColumn` annotations to specify custom FK column names:

```java
record AuditLog(@PK Integer id,
                String action,
                @Nullable @FK @DbColumn("audit_user_id") @DbColumn("audit_role_id")
                UserRole userRole
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Self-Referential Relationships

When an entity references itself (e.g., employees with managers, categories with parents), eager loading would recurse infinitely. Use `Ref<T>` to break the cycle. `Ref` stores only the foreign key value without loading the referenced entity, so Storm stops the JOIN chain at that point.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Employee(
    @PK val id: Int = 0,
    val name: String,
    @FK val manager: Ref<Employee>?  // Self-reference with Ref
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Employee(@PK Integer id,
                String name,
                @Nullable @FK Ref<Employee> manager  // Self-reference with Ref
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

---

## Primary Key as Foreign Key

Sometimes a table's primary key is also a foreign key to another entity. This is common for:

- **Dependent one-to-one relationships** where a child entity cannot exist without its parent
- **Extension tables** that add optional data to an existing entity
- **Specialized subtypes** in a table-per-subtype inheritance strategy (see [Polymorphism](polymorphism.md))

Use both `@PK` and `@FK` annotations on the same field, with `generation = NONE` since the key value comes from the related entity rather than being auto-generated:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class UserProfile(
    @PK(generation = NONE) @FK val user: User,  // PK is also FK to User
    val bio: String?,
    val avatarUrl: String?,
    val theme: Theme?
) : Entity<User>
```

The `generation = NONE` tells Storm that the primary key is not auto-generated; the value must be provided when inserting. This is necessary because the key comes from the related `User` entity.

**Column name resolution:** When both `@PK` and `@FK` are present, Storm resolves the column name in this order:

1. Explicit name in `@PK` (e.g., `@PK("user_profile_id")`)
2. Explicit name in `@DbColumn`
3. Foreign key naming convention (default)

For a field named `user`, the FK convention produces `user_id`. To override this, specify the name explicitly:

```kotlin
@PK("user_profile_id", generation = NONE) @FK val user: User  // Uses "user_profile_id"
```

The entity's type parameter is the related entity type (`User`), not a primitive key type. This reflects that the `UserProfile` is uniquely identified by its associated `User`.

When inserting, provide the related entity:

```kotlin
val profile = UserProfile(
    user = existingUser,
    bio = "Software developer",
    avatarUrl = null,
    theme = Theme.DARK
)
orm.insert(profile)
```

Storm extracts the primary key from the `User` entity and uses it as the value for the `user_id` column.

</TabItem>
<TabItem value="java" label="Java">

```java
record UserProfile(@PK(generation = NONE) @FK User user,  // PK is also FK to User
                   @Nullable String bio,
                   @Nullable String avatarUrl,
                   @Nullable Theme theme
) implements Entity<User> {}
```

The `generation = NONE` tells Storm that the primary key is not auto-generated; the value must be provided when inserting. This is necessary because the key comes from the related `User` entity.

**Column name resolution:** When both `@PK` and `@FK` are present, Storm resolves the column name in this order:

1. Explicit name in `@PK` (e.g., `@PK("user_profile_id")`)
2. Explicit name in `@DbColumn`
3. Foreign key naming convention (default)

For a field named `user`, the FK convention produces `user_id`. To override this, specify the name explicitly:

```java
@PK(value = "user_profile_id", generation = NONE) @FK User user  // Uses "user_profile_id"
```

The entity's type parameter is the related entity type (`User`), not a primitive key type. This reflects that the `UserProfile` is uniquely identified by its associated `User`.

When inserting, provide the related entity:

```java
var profile = new UserProfile(existingUser, "Software developer", null, Theme.DARK);
orm.entity(UserProfile.class).insert(profile);
```

Storm extracts the primary key from the `User` entity and uses it as the value for the `user_id` column.

</TabItem>
</Tabs>

### Key Chains

The referenced entity's primary key may itself be a foreign key, or a compound key record. Storm follows this *key chain* to its terminal columns. A dependent one-to-one on an entity that is itself a dependent one-to-one works the same way as the single-level case:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class ProfileAudit(
    @PK(generation = NONE) @FK val profile: UserProfile,  // UserProfile's own PK is the FK to User
    val remark: String
) : Entity<UserProfile>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record ProfileAudit(@PK(generation = NONE) @FK UserProfile profile,  // UserProfile's own PK is the FK to User
                    String remark
) implements Entity<UserProfile> {}
```

</TabItem>
</Tabs>

The foreign key spans the same columns as the terminal key of the chain: a single-column chain resolves to one column named by the FK convention (`profile_id` here), and a compound key contributes the referenced key's column names. Circular key chains are rejected at model construction with a clear error.

---

## Relationship Loading Behavior

Storm loads the complete reachable entity graph in a single query using JOINs, unless a relationship is explicitly broken with `Ref`:

```kotlin
data class Order(
    @PK val id: Int = 0,
    @FK val customer: Customer,
    @FK val shippingAddress: Address
) : Entity<Int>

data class Customer(
    @PK val id: Int = 0,
    val name: String,
    @FK val defaultAddress: Address
) : Entity<Int>
```

When you query `Order`:
1. `Order` is loaded
2. `Customer` is loaded (via JOIN)
3. `Address` for shipping is loaded (via JOIN)
4. `Address` for customer default is loaded (via JOIN)

All in **one SQL query**. No lazy loading surprises, and no query per relationship to avoid.

### How It Works

Storm generates a single SELECT with all necessary JOINs:

```
┌─────────────────────────────────────────────────────────────────────┐
│  SELECT o.id, o.customer_id, o.shipping_address_id,                 │
│         c.id, c.name, c.default_address_id,                         │
│         a1.id, a1.street, a1.city,                                  │
│         a2.id, a2.street, a2.city                                   │
│  FROM order o                                                       │
│  INNER JOIN customer c ON o.customer_id = c.id                      │
│  INNER JOIN address a1 ON o.shipping_address_id = a1.id             │
│  INNER JOIN address a2 ON c.default_address_id = a2.id              │
│  WHERE o.id = ?                                                     │
└─────────────────────────────────────────────────────────────────────┘
                              │
                              ▼
┌─────────────────────────────────────────────────────────────────────┐
│  Result: Single row with all columns from all joined tables         │
│                                                                     │
│  Storm automatically:                                               │
│  1. Parses columns back into their respective entity types          │
│  2. Constructs the complete object graph                            │
│  3. Returns a fully populated Order with nested entities            │
└─────────────────────────────────────────────────────────────────────┘
```

Storm always uses explicit column names (never `SELECT *`), ensuring predictable results even when table schemas change.

### Entity Graph to JOIN Mapping

Storm traverses the entity graph and generates JOINs based on FK nullability:

```
Entity Graph                              Generated JOINs
─────────────                             ───────────────

┌─────────┐                               FROM order o
│  Order  │
└────┬────┘
     │
     ├──── @FK customer ──────────────►   INNER JOIN customer c
     │         (non-null)                     ON o.customer_id = c.id
     │              │
     │              └─ @FK defaultAddress ►       INNER JOIN address a2
     │                     (non-null)                 ON c.default_address_id = a2.id
     │
     └──── @FK shippingAddress? ──────►   LEFT JOIN address a1
               (nullable)                     ON o.shipping_address_id = a1.id
```

**Join type is determined by nullability:**
- Non-nullable FK -> INNER JOIN (referenced entity must exist)
- Nullable FK -> LEFT JOIN (referenced entity may be null)

**Nested FKs are joined transitively.** Storm follows the entire entity graph, joining each FK it encounters.

### Why Eager Loading?

Traditional ORMs use lazy loading, which causes:

| Problem | Description |
|---------|-------------|
| **N+1 queries** | Accessing a collection triggers N additional queries |
| **LazyInitializationException** | Accessing data outside transaction scope fails |
| **Unpredictable performance** | Same code has different DB load depending on access patterns |
| **Hidden complexity** | Proxied entities mask when database access occurs |

Storm's approach:

| Benefit | Description |
|---------|-------------|
| **Predictable queries** | One query per `find`/`select` operation |
| **No session required** | Entities work anywhere, no transaction scope needed |
| **Transparent behavior** | What you query is what you get |
| **Simple debugging** | Easy to trace and optimize SQL |

### Managing Graph Depth

Circular relationships force a `Ref`, and a graph that has grown genuinely wide can use one to break the loading chain:

```kotlin
data class Category(
    @PK val id: Int = 0,
    val name: String,
    @FK val parent: Ref<Category>?  // Stops here, loads only the ID
) : Entity<Int>
```

A `Ref` removes the join from every read but keeps the relationship queryable: you can still filter, order, and select through it with the metamodel (`Product_.category.name`, where `category` is a `Ref<Category>`), and Storm adds the join only for the query that navigates beyond the foreign key. This makes `Ref` the primary tool for keeping wide or deep graphs' reads narrow without giving up type-safe traversal.

The self-reference above is navigable too: `Category_.parent.name` joins the category table to itself, so it filters on the parent's name. The typed metamodel navigates a cycle two hops deep; deeper cyclic paths are named as strings. See [Refs](refs.md), [Querying Through Refs](refs.md#querying-through-refs) and [Cyclic References](metamodel.md#cyclic-references) for details.

Deciding which edges to cut, and which to leave inlined, is a design question in its own right. See [Entity Design](entity-design.md).

## Tips

1. **Model foreign keys as direct types by default.** A join on a primary key is cheap, and one query beats several. Reach for `Ref` when an edge lets the graph grow, not because a join exists. See [Entity Design](entity-design.md).
2. **Query the "many" side.** For one-to-many, query the child entity with a filter on the parent.
3. **Use join entities for many-to-many.** Explicit join tables give you control over the relationship.
4. **Match nullability to your schema.** Use nullable FKs only when the database column allows NULL.
5. **Use Ref for circular references.** Prevents infinite recursion in self-referential entities.
