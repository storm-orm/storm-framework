import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Write Sets

Inserting an object graph that spans several tables normally requires careful choreography: insert the parents first, collect their generated keys, rebuild the children against those keys, and repeat for every level. Write sets lift that burden.

A write set applies one write operation to a heterogeneous collection of entities. Storm partitions the entities by type, orders them by their foreign key dependencies, and writes them in dependency-ordered batches, propagating generated primary keys to dependent entities. `insert` and `upsert` extend the *explicit members* (the entities you supply) with *discovered members*: unsaved entities transitively reachable through insertable foreign key fields (*insert discovery*). `update` and `remove` operate on the explicit members only. Every action accepts the entities as varargs or as any `Iterable`.

Write sets are not a cascade in the JPA sense. There are no mapping annotations, no persistence context and no session-wide cascade: all writes derive from the entities supplied to the call and, for insert and upsert, their discovered members. The per-row semantics of every action are identical to the corresponding repository operation, including entity callbacks and dirty checking. Coming from JPA? [JPA Cascades vs Write Sets](jpa-cascades-vs-write-sets.md) compares the two models side by side.

A write set is obtained from the ORM template, or from any repository (`writeSet()` on a repository is a convenience that delegates to the underlying template; it is not scoped to the repository's entity type):

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.writeSet().insert(entities)

// or scoped; each action executes immediately, the block only groups the calls
transaction {
    orm.writeSet {
        insert(newEntities)
        update(changedEntities)
        remove(staleEntities)
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.writeSet().insert(entities);
```

</TabItem>
</Tabs>

---

## Inserting a Graph

Consider the classic schema where a `Pet` references an `Owner` and a `Visit` references a `Pet`. With a write set, the whole graph is built in memory first, linking children to their parents by holding the parent instance, and inserted in one call:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owner = Owner(firstName = "Alice", lastName = "Bond", address = address)   // unsaved
val wolfie = Pet(name = "Wolfie", birthDate = birthDate, type = dog, owner = owner)
val rex = Pet(name = "Rex", birthDate = birthDate, type = dog, owner = owner)  // same owner instance
val visit = Visit(visitDate = today, description = "Check-up", pet = wolfie)

orm.writeSet().insert(wolfie, rex, visit)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var owner = new Owner("Alice", "Bond", address);                       // unsaved
var wolfie = new Pet("Wolfie", birthDate, dog, owner);
var rex = new Pet("Rex", birthDate, dog, owner);                       // same owner instance
var visit = new Visit(today, "Check-up", wolfie);

orm.writeSet().insert(wolfie, rex, visit);
```

</TabItem>
</Tabs>

This particular graph needs three dependency-ordered batch operations, regardless of how many pets or visits the set contains: owners first, then pets, then visits. The owner was never passed explicitly; it becomes a discovered member because the pet values hold it in their foreign key field. That is insert discovery at work: a record whose foreign key field holds an unsaved entity is a value that describes both rows, and inserting the value inserts both.

The rules, in full:

1. **Insert and upsert write the explicit members plus their discovered members.** Discovery follows insertable, entity-valued foreign key fields (including fields inside inline components) and entity-wrapped refs, and picks up unsaved entities transitively. Referenced entities that already carry a primary key are never discovered; unless they are explicit members themselves, they only provide foreign key values.
2. **Update and remove write exactly the explicit members.** Referenced entities are never updated or removed implicitly.
3. **Storm determines a valid execution order** from the foreign key dependencies: parents before children for insert and upsert, children before parents for remove.
4. **Generated keys propagate by instance identity.** Children link to a new parent by holding the same instance: one unsaved instance describes one prospective row, and two structurally equal but distinct unsaved instances describe two separate rows.
5. **Execution is grouped into one batch operation per entity type per dependency level** (large batches are split by the configured batch size). The batch count follows the dependency shape of the data: a self-referencing type whose rows span several dependency levels needs one batch per level.

An entity counts as *unsaved* when its primary key is the default value and the key is auto-generated. The test is local and deterministic; no session state or database round trip is involved.

A write set executes multiple statements and is not atomic by itself: if a later dependency level fails, earlier levels have already been written. Run write sets inside a transaction when atomicity across the set is required.

:::warning Instance identity, not equality

Key propagation correlates by instance, not by `equals`. A `copy()` of an unsaved parent is a different row. Link children to the exact instance you want them to share.

:::

## Fetching the Result

`insertAndFetch` returns the passed entities as they exist in the database after the write, in input order, with generated keys, defaults and version columns reflected and referenced parents hydrated. Single-root variants accept one entity and return it typed, so the common "insert this graph, give me the keyed result" is one call:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val inserted = orm.writeSet().insertAndFetch(wolfie, visit)
val fetchedPet = inserted[0] as Pet     // fetchedPet.owner carries the generated key

val fetchedVisit: Visit = orm.writeSet().insertAndFetch(visit)   // single root, typed
```

</TabItem>
<TabItem value="java" label="Java">

```java
var inserted = orm.writeSet().insertAndFetch(wolfie, visit);
var fetchedPet = (Pet) inserted.get(0); // fetchedPet.owner() carries the generated key

Visit fetchedVisit = orm.writeSet().insertAndFetch(visit);       // single root, typed
```

</TabItem>
</Tabs>

`insertAndFetchIds` returns just the primary keys of the explicit members, in input order, without re-reading the rows: the keys come from the insert itself. It is the middle tier between `insert` (nothing back) and `insertAndFetch` (rows re-read with database-applied state), and the natural fit for a create endpoint that responds with ids. The batch is homogeneous in its id type; entity types may differ as long as they share it, and a batch that mixes id types keeps using `insertAndFetch`, where each returned entity carries its own id.

Kotlin additionally offers the id-returning methods as vararg extension functions (in `st.orm.repository`). The interface itself cannot declare them: `@SafeVarargs` is not applicable to interface default methods, so a Java-side generic vararg would emit heap-pollution warnings at every call site. Java call sites pass `List.of(...)`, which supplies the varargs ergonomics there.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val ids: List<Long> = orm.writeSet().insertAndFetchIds(visits)        // keys in input order, no re-read
val two: List<Long> = orm.writeSet().insertAndFetchIds(wolfie, visit) // vararg extension; one shared id type
val id: Long = orm.writeSet().insertAndFetchId(visit)                 // single root
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Long> ids = orm.writeSet().insertAndFetchIds(visits);       // keys in input order, no re-read
Long id = orm.writeSet().insertAndFetchId(visit);                // single root
```

</TabItem>
</Tabs>

## Refs

Foreign key fields typed as `Ref` participate through entity-wrapped refs, which carry the instance:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pet = Pet(name = "Shadow", birthDate = birthDate, type = dog, owner = Ref.of(owner))
orm.writeSet().insert(pet)              // owner is inserted first, the ref binds the generated key
```

</TabItem>
<TabItem value="java" label="Java">

```java
var pet = new Pet("Shadow", birthDate, dog, Ref.of(owner));
orm.writeSet().insert(pet);             // owner is inserted first, the ref binds the generated key
```

</TabItem>
</Tabs>

Id-only refs such as `Ref.of(Owner::class, 42)` are pointers to known rows: they bind as foreign key values and are never written. An id-only ref carrying a default id cannot describe a new entity and fails fast. Note that ref equality is based on type and id, so refs wrapping distinct unsaved instances compare equal until the instances are persisted; do not use unsaved refs as map keys.

## Junction Tables

The [many-to-many pattern](relationships.md#many-to-many) models a junction table with a composite primary key holding the key columns, and non-insertable foreign key fields that load the related entities:

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
) : Entity<UserRolePk> {
    constructor(user: User, role: Role) : this(UserRolePk(user.id, role.id), user, role)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
record UserRolePk(int userId, int roleId) {}

record UserRole(@PK UserRolePk userRolePk,
                @FK @Persist(insertable = false, updatable = false) User user,
                @FK @Persist(insertable = false, updatable = false) Role role
) implements Entity<UserRolePk> {

    public UserRole(User user, Role role) {
        this(new UserRolePk(user.id(), role.id()), user, role);
    }
}
```

</TabItem>
</Tabs>

The secondary constructor derives the key components from the referenced entities, so call sites pass the entities and never spell out the composite key.

Write sets recognize this shape. A non-insertable foreign key field whose column value is carried by an insertable component of the primary key participates in insert discovery like any other edge: an unsaved entity held by the field is discovered and inserted first, and its generated key is written into the carrying key component before the junction row is inserted. The key components for already-persisted entities are set as usual, so mixed rows work naturally:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val user = User(name = "Alice")                               // unsaved
val role = orm.get(Role_.name eq "admin")                     // saved
val userRole = UserRole(user, role)

orm.writeSet().insert(userRole)           // user is inserted first; its key lands in userRolePk.userId
```

</TabItem>
<TabItem value="java" label="Java">

```java
var user = new User("Alice");                                 // unsaved
var role = orm.entity(Role.class).getBy(Role_.name, "admin"); // saved
var userRole = new UserRole(user, role);

orm.writeSet().insert(userRole);          // user is inserted first; its key lands in userRolePk.userId
```

</TabItem>
</Tabs>

The unsaved entity's default id in the key component is a placeholder; the write set overwrites it with the generated key. Only when no insertable primary key component carries the column value does the reference fail fast (see below).

## Update, Upsert and Remove

The remaining actions follow the same contract, each with the ordering that suits it:

- `update` groups the explicit members by type and updates them with the usual semantics, including transaction-scoped dirty checking: unchanged entities are skipped. Referenced entities are never updated implicitly: an updated `Owner` held inside a `Pet` you update is not written, it contributes only its primary key. Pass both when both changed; dirty checking skips whichever members are unchanged. Unsaved members are rejected; a row that does not exist cannot be updated.
- `upsert` applies the per-repository upsert semantics to the explicit members and inserts the discovered members, with keys propagating as for insert. Explicit membership takes precedence: a keyed entity that is both supplied and referenced by another member is upserted, and is written before the members that reference it. Whether an upsert can create a row for a member carrying a preset key follows the dialect's per-repository upsert behavior. `upsertAndFetch` and `upsertAndFetchIds` mirror the insert-side variants: rows re-read, or just the keys.
- `remove` deletes exactly the explicit members, children before parents. Members are correlated by entity type and primary key rather than by instance, so a member referencing another member is removed first regardless of which instance its foreign key field holds. Referenced entities are never removed implicitly.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.writeSet().remove(owner, pet, visit)   // executed as: visit, pet, owner
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.writeSet().remove(owner, pet, visit); // executed as: visit, pet, owner
```

</TabItem>
</Tabs>

## Fail-Fast Behavior

Write sets keep Storm's fail-fast doctrine. Each of the following raises a descriptive `PersistenceException` before any statement is executed:

- A dependency cycle that cannot be executed by the dependency-ordering strategy. The write set does not break cycles using nullable intermediate values, deferred constraints, or follow-up updates.
- An id-only ref with a default id in an insert or upsert set (it cannot describe a new entity; wrap the instance instead).
- An unsaved entity passed to update or remove.
- An unsaved entity referenced through a non-insertable foreign key component whose column value is not carried by an insertable primary key component (junction tables carry their key columns inside the composite primary key and do participate; see [Junction Tables](#junction-tables)).

References to entities outside the effective write set follow the normal repository rules: keyed references only provide foreign key values and are never written; unsaved references fail wherever their key is required as a foreign key value.

## What Write Sets Do Not Do

Write sets complete Storm's persistence story without introducing a session. Each concern has a dedicated tool:

- **Save-or-update decisions** belong to [upserts](upserts.md), which resolve conflicts atomically in the database.
- **Skipping unchanged rows and partial updates** belong to [dirty checking](dirty-checking.md), driven by the transaction-scoped entity cache.
- **Cascading deletes of dependent rows** belong to the schema: declare `ON DELETE CASCADE` on the foreign key constraint. Storm does not discover children reactively.
- **Implicit updates of referenced entities** do not exist. A keyed entity referenced by a set member only provides its key; modifying it requires an explicit update. One rule covers every action: a write set writes the entities you name, plus the entities your values make necessary. An unsaved referenced entity is necessary (its dependent cannot be written without its key, and a row that does not exist cannot be a stale copy); a keyed referenced entity never is: it is the state that was hydrated when the value was read, and treating that snapshot as write intent would silently overwrite newer database state.
- **Re-planning after entity callbacks** does not happen. Callbacks run inside the per-type repository operations, after members are discovered and the execution order is planned; a callback that alters foreign key fields does not change what is discovered or in which order it is written.
