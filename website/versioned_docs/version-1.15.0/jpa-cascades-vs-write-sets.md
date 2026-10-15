import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# JPA Cascades vs Write Sets

JPA's cascading and Storm's write sets solve the same problem: an object graph that spans several tables must reach the database in the right order, with generated keys flowing from parents to children. The two solutions work from opposite philosophies. This tutorial implements the same use case with both, then examines what each choice implies.

If you are new to write sets, [Write Sets](write-sets.md) covers the API itself. For the broader migration story, see [Migration from JPA](migration-from-jpa.md).

## The Task

An `Owner` with two `Pet`s, one of which has a `Visit`. Four rows across three tables: the owner first, then the pets carrying the generated owner key, then the visit carrying a pet key.

## The JPA Version

In JPA the graph is a class model with associations in both directions, and the cascade configuration on those associations decides how far each operation travels:

```java
@Entity
public class Owner {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String firstName;
    private String lastName;

    @OneToMany(mappedBy = "owner", cascade = CascadeType.PERSIST)
    private List<Pet> pets = new ArrayList<>();

    public void addPet(Pet pet) {
        pets.add(pet);
        pet.setOwner(this);
    }
    // constructors, getters, setters
}

@Entity
public class Pet {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private String name;
    private LocalDate birthDate;

    @ManyToOne(fetch = FetchType.LAZY)
    private Owner owner;

    @OneToMany(mappedBy = "pet", cascade = CascadeType.PERSIST)
    private List<Visit> visits = new ArrayList<>();

    public void addVisit(Visit visit) {
        visits.add(visit);
        visit.setPet(this);
    }
    // constructors, getters, setters
}

@Entity
public class Visit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    private LocalDate visitDate;
    private String description;

    @ManyToOne(fetch = FetchType.LAZY)
    private Pet pet;
    // constructors, getters, setters
}
```

Persisting the graph is one call on the root:

```java
var owner = new Owner("Alice", "Bond");
var wolfie = new Pet("Wolfie", birthDate);
var rex = new Pet("Rex", birthDate);
owner.addPet(wolfie);
owner.addPet(rex);
wolfie.addVisit(new Visit(today, "Check-up"));

entityManager.persist(owner);
```

`persist` makes the owner managed and propagates along every association marked `CascadeType.PERSIST`, so the pets and the visit become managed too. The INSERT statements run at flush time, parents before children, and the provider writes each generated key into the managed child instances.

Note what carried the graph: the parent-to-child collections (`owner.pets`, `pet.visits`), kept consistent with the child-to-parent references by the `addPet` and `addVisit` helpers. And note where the behavior lives: on the mapping. Every `persist` that reaches an `Owner`, from any call site, cascades the same way.

## The Storm Version

Storm entities are records or data classes that model rows, so they reference only their parents, exactly as the foreign key columns do:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Owner(
    @PK val id: Int = 0,
    val firstName: String,
    val lastName: String
) : Entity<Int>

data class Pet(
    @PK val id: Int = 0,
    val name: String,
    val birthDate: LocalDate,
    @FK val owner: Owner
) : Entity<Int>

data class Visit(
    @PK val id: Int = 0,
    val visitDate: LocalDate,
    val description: String,
    @FK val pet: Pet
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Owner(@PK Integer id, String firstName, String lastName)
        implements Entity<Integer> {}

record Pet(@PK Integer id, String name, LocalDate birthDate, @FK Owner owner)
        implements Entity<Integer> {}

record Visit(@PK Integer id, LocalDate visitDate, String description, @FK Pet pet)
        implements Entity<Integer> {}
```

</TabItem>
</Tabs>

The graph is built by holding parent instances, and written with one call:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owner = Owner(firstName = "Alice", lastName = "Bond")
val wolfie = Pet(name = "Wolfie", birthDate = birthDate, owner = owner)
val rex = Pet(name = "Rex", birthDate = birthDate, owner = owner)
val visit = Visit(visitDate = today, description = "Check-up", pet = wolfie)

orm.writeSet().insert(wolfie, rex, visit)
```

</TabItem>
<TabItem value="java" label="Java">

```java
var owner = new Owner(0, "Alice", "Bond");
var wolfie = new Pet(0, "Wolfie", birthDate, owner);
var rex = new Pet(0, "Rex", birthDate, owner);
var visit = new Visit(0, today, "Check-up", wolfie);

orm.writeSet().insert(wolfie, rex, visit);
```

</TabItem>
</Tabs>

The owner was never passed. It is discovered: `insert` extends the explicit members with every unsaved entity reachable through their foreign key fields, the *discovered members*. Storm orders the result by foreign key dependencies, executes one batch per entity type per dependency level (owners, then pets, then visits), and propagates generated keys by instance identity: both pets hold the same `owner` instance, so both rows receive the same generated key.

There is no cascade configuration anywhere. Nothing on `Owner` says that pets depend on it; the pet values say so themselves.

## The Philosophical Difference

### Cascades point down, write sets point up

A JPA object model references in both directions. Parents hold collections of children so that operations can travel downward, which is the direction a cascade needs. A Storm entity references only upward: a pet names its owner because the pet row holds the foreign key, and that is the only graph there is.

This one inversion explains most of what follows:

- **Insert discovery needs no configuration in Storm** because every value declares its own dependencies. There is nothing to over-cascade into: discovery finds exactly the unsaved entities your values reference, transitively.
- **Delete discovery does not exist in Storm** because a value never names its children. `remove` deletes exactly the entities you pass, children before parents. Reactive cleanup of dependents belongs to the schema: declare `ON DELETE CASCADE` on the foreign key constraint and the database maintains it under every writer, not just your application.

### A managed graph vs an explicit operation

In JPA, persistence is a property of the object model. You configure once, on the mappings, how operations travel the graph. A persistence context keeps the loaded graph and the database converged, and the writes are derived from state: new managed entities, dirty fields, orphaned children, all collected and ordered at flush time. Cascades extend that convergence across associations. The call site stays small, `persist(owner)`, because the mapping and the session carry the knowledge.

In Storm, persistence is an operation on values. There is no session and no managed state; entities are plain values that describe rows. A write set is a single call whose inputs fully determine what happens: the action, the members you pass, and the discovered members those values imply. The call site carries the knowledge, and the model stays free of behavior.

Neither choice is free. The price of Storm's model is that every call site states its intent; there is no per-association default to configure once and rely on everywhere. The price of JPA's model is action at a distance: reading a call site is not enough to know what it writes, because the mapping and the current session state complete the sentence.

### Consequences side by side

| Question | JPA cascades | Storm write sets |
|----------|--------------|------------------|
| Where is the behavior declared? | On the mapping, once, for every call site | At the call site, per operation |
| What carries the graph? | Object references in both directions | Foreign key fields; a value names its parents |
| When do statements run? | At flush time, ordered by the provider | At the call, in dependency-ordered batches |
| How do children get parent keys? | The provider updates managed instances in place | By instance identity; the `AndFetch` variants return keyed values |
| What counts as new? | Entity state: transient, managed, detached | A local test: default primary key and an auto-generated strategy |
| Cascading deletes | `CascadeType.REMOVE`, `orphanRemoval` | `ON DELETE CASCADE` in the schema; ORM deletes stay explicit |

## Operation by Operation

### `persist` with PERSIST becomes `insert`

Shown above. One difference follows from immutability: JPA assigns the generated key to your instance, while Storm values never change. Use the `AndFetch` variants to get the persisted state back:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val saved: Visit = orm.writeSet().insertAndFetch(visit)
// saved.pet and saved.pet.owner carry the generated keys; visit is untouched
```

</TabItem>
<TabItem value="java" label="Java">

```java
Visit saved = orm.writeSet().insertAndFetch(visit);
// saved.pet() and saved.pet().owner() carry the generated keys; visit is untouched
```

</TabItem>
</Tabs>

When only the keys are needed, `insertAndFetchIds` returns them in input order without re-reading the rows.

### `merge` with MERGE becomes `update` or `upsert`

JPA's `merge` copies detached state onto a managed instance and, with `CascadeType.MERGE`, walks the associations; whether a row is inserted or updated follows from entity state. Storm splits that decision into two explicit actions: `update` for rows you know exist, and [`upsert`](upserts.md) when the database should resolve it atomically. Both write exactly the explicit members. Referenced entities provide foreign key values but are never written implicitly, and `update` rejects unsaved members instead of quietly inserting them.

This is the sharpest instinct to retrain. An updated `Owner` held inside a `Pet` passed to `update` is not written: a keyed referenced entity contributes only its primary key, so the owner's changes stay in memory. Where a JPA session would eventually flush a managed owner even without a cascade, Storm has no session and writes what you name, nothing else.

There is no cascade that insert has and update lacks; one rule covers every action: a write set writes the entities you name, plus the entities your values make necessary. An unsaved referenced entity is necessary: the pet row cannot be written without the owner's key, and a row that does not exist cannot be a stale copy, so holding it is unambiguous intent to create it. A keyed referenced entity is never necessary: it already provides the foreign key value, and beyond that it is whatever snapshot was hydrated when the pet was read. If `update` wrote it anyway, every pet update would rewrite the owner row with a possibly stale copy, silently overwriting newer writes. JPA can afford to walk the graph on merge because the session identity-maps entities: the one managed owner genuinely represents current state plus your changes. Without a session, a snapshot is not write intent. When both changed, name both; each row is written with its own dirty check, so unchanged members still cost nothing:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.writeSet().update(pet, owner)
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.writeSet().update(pet, owner);
```

</TabItem>
</Tabs>

### `remove` with REMOVE becomes `remove` plus the schema

`writeSet().remove` deletes the entities you name, children before parents, so a member referencing another member never trips a constraint:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
orm.writeSet().remove(owner, wolfie, visit)   // executed as: visit, wolfie, owner
```

</TabItem>
<TabItem value="java" label="Java">

```java
orm.writeSet().remove(owner, wolfie, visit); // executed as: visit, wolfie, owner
```

</TabItem>
</Tabs>

What it does not do is walk the graph looking for dependents. `orphanRemoval` has no counterpart either: Storm entities hold no child collections, so there is no collection mutation to react to. Declare the reaction where the database can enforce it, on the constraint, or delete the rows explicitly.

## What Stays the Same

Some instincts carry over directly:

- **Correlation by instance.** JPA correlates parent and child through the managed object; write sets correlate through instance identity. In both worlds, two structurally equal but distinct new instances describe two rows, and children link to a parent by holding it.
- **Skipping clean rows.** JPA's flush skips managed entities that have not changed. Storm's `update` does the same through transaction-scoped [dirty checking](dirty-checking.md).
- **Per-row semantics.** Every row a write set touches goes through the same path as the corresponding repository operation, including [entity callbacks](entity-lifecycle.md).
- **Transactions.** A JPA flush runs inside the surrounding transaction. A write set executes several statements and is not atomic by itself, so wrap it in a [transaction](transactions.md) when the set must commit or fail as one.

One instinct inverts. JPA reports an unresolvable reference late: a flush that finds an association pointing at a transient entity outside any cascade fails at flush time. Storm validates the plan first and raises a descriptive error before any statement runs, for example when `update` receives an unsaved member or when a dependency cycle cannot be ordered.

## Translating Your Cascades

| In your JPA model | In Storm |
|-------------------|----------|
| `CascadeType.PERSIST` on a collection | Nothing to declare. Build children holding the parent instance and pass them to `writeSet().insert` |
| `CascadeType.MERGE` | `writeSet().update`, or `writeSet().upsert` when the database should decide |
| `CascadeType.REMOVE`, `orphanRemoval` | `ON DELETE CASCADE` on the constraint; explicit `writeSet().remove` otherwise |
| `CascadeType.ALL` | Decide per call site; there is no per-association default |
| `addPet`/`addVisit` helpers keeping both sides in sync | Nothing to maintain; only child-to-parent references exist |
| Flush-time write-behind | Statements run at the call, in dependency order |

The mechanical translation is small: drop the collections, keep the parent references, and say at each call site what you want written. The conceptual translation is the subject of this page: the graph moves out of the mappings and into your values, and the write moves out of the session and into a call you can read.
