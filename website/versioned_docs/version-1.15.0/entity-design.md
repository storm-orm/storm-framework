# Entity Design

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Every foreign key in your schema becomes one of two things in your model: a direct type, which Storm joins on every read, or a [`Ref`](refs.md), which stores the key and leaves the join to the queries that ask for it. That single choice, repeated across a schema, decides what your reads cost.

This page describes how to make it, and how Storm's schema-first generation makes it for you.

---

## Where the Decision Lives

Any framework that returns objects from a relational schema has to settle one question: when a query returns an entity, how much of the surrounding data comes back with it. Storm answers that question in the entity class. The relationships a class declares as direct types are loaded alongside it, on every read, in every query, for as long as they stay declared that way. The rest of this page follows from that answer, so it is worth setting out what the choice costs and what it buys before arriving at the rule itself.

The alternative is to answer the question at each query instead. That approach is more flexible, since any given read can ask for exactly the data it needs and nothing more. What it asks in return is that every read state its requirements, and that those requirements stay correct as the surrounding code changes. When a read omits something it turns out to need, the shortfall is usually made up at runtime, one additional query per row returned, and that is a cost which stays invisible until the data grows.

Storm takes the other position. The decision is made once, where the model is defined, and applies uniformly to every read of that entity. That uniformity is the cost: a query interested in two columns still pays for the relationships the class declares. It is also the benefit, because it makes the cost of a read a property of the model rather than of whichever code happens to be calling it. You can establish what an entity costs to read by looking at its declaration, without running anything, and tooling can check that cost against a budget before the code ships.

Where a particular query genuinely needs more than the class declares, [`fetch(...)`](refs.md#resolving-a-ref-as-part-of-the-query) resolves the additional records as part of the same statement, so the class sets the default rather than an upper bound. What a class cannot do is prompt you to reconsider the decision later, because nothing at the call sites refers back to it. That is precisely why the reasoning behind it is worth writing down.

---

## Start by Not Optimizing

The default is to model every foreign key as a direct type and take the joins.

Storm joins on a primary key with an equality predicate, which is the cheapest thing a relational database does. It is an index lookup per row against a structure the database keeps hot. A query that joins six tables to return one fully populated entity graph is not a query in trouble. It is a query doing what the database is built for, and it beats six round trips to assemble the same object by a margin that does not depend on your schema.

So `Ref` is not the careful choice, and a direct type is not the lazy one. Reaching for `Ref` before you have a reason gives up nothing in query capability, but it does move work into your application: a `fetch()` at the call site, a second round trip, and a loading decision that every caller now has to get right. Pay that when something is buying it.

Concretely: build the graph, model the foreign keys as direct types, and move on. Most schemas never need anything else.

---

## What Actually Costs You

When a graph does start to hurt, it helps to be precise about which part hurts. There are three costs, and they are not close to equal.

**The join.** Nearly free, as above. This is the cost people optimize for, and it is the one that matters least.

**The width.** Every inlined table adds its columns to every row that every ancestor selects. A join to a 40-column table does not cost you a join, it costs you 40 columns times however many rows come back, over the wire and through the row mapper, on every read of every entity that reaches it. Width is the real bill.

**Growth you did not ask for.** This is the one worth designing against.

Consider a `Visit` that references `Pet`:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
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
record Visit(@PK Integer id,
             LocalDate visitDate,
             String description,
             @FK Pet pet
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

Later, someone adds a clinic reference to `Pet`. `Clinic` has its own foreign keys to `Address` and `Organization`, and `Address` reaches `City`, which reaches `Country`. Every query that selects a `Visit` now joins five more tables and carries their columns, and nothing about `Visit` changed. The developer who edited `Pet` had no way to see it.

That asymmetry is what justifies a boundary. Not the join. A direct type has an unbounded blast radius that grows with your schema, and it grows in a place nobody is looking. A `Ref` has a blast radius of one edge, and it is written down at the edge.

---

## What a Ref Does Not Cost

Before turning to where a cut belongs, it is worth being precise about what a cut gives up, which is less than it appears. Declaring a foreign key as a `Ref` narrows what a read returns by default. It does not narrow what a query is permitted to ask, and that distinction is what makes the choices in the next section a question of cost rather than of capability.

A reference does not terminate the metamodel; paths continue straight through it. A column on the far side of a reference, however many hops beyond it, is named exactly as it would be on a directly-referenced entity. Storm joins the tables the path crosses, for the query that asks and for no other:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// city is a Ref<City>, and the filter still reaches through it into country.
orm.entity<User>()
    .select()
    .where(User_.city.country.name eq "Netherlands")
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// city is a Ref<City>, and the filter still reaches through it into country.
List<User> users = orm.entity(User.class)
    .select()
    .where(User_.city.country.name, EQUALS, "Netherlands")
    .getResultList();
```

</TabItem>
</Tabs>

The same paths are available inside a select template, so a query is free to return data from beyond a reference even though the entity itself does not carry it:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Selects a column two hops past the reference, joining city and country for this query alone.
orm.entity<User>()
    .select<UsersPerCountry, _, _> { "${User_.city.country.name}, COUNT(*)" }
    .groupBy(User_.city.country.name)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Selects a column two hops past the reference, joining city and country for this query alone.
orm.entity(User.class)
    .select(UsersPerCountry.class, RAW."\{User_.city.country.name}, COUNT(*)")
    .groupBy(User_.city.country.name)
    .getResultList();
```

</TabItem>
</Tabs>

Both work anywhere a query names a column: `where`, `orderBy`, `groupBy`, `having`, and custom select templates. Naming the reference field on its own, `User_.city`, reads the foreign key column and adds no join at all, which is what makes it an inexpensive grouping key. Every other read of `User` is left alone throughout: the records still come back carrying an unloaded `Ref<City>`. See [Querying Through Refs](refs.md#querying-through-refs) and [Navigating Through Refs](metamodel.md#navigating-through-refs).

---

## Where to Draw the Line

When a graph does need cutting, the useful question is not "is this join expensive." It is "how far does this edge let the graph grow, and who inherits that."

**Cut at the deepest edge, not the nearest one.** If `Visit` to `Pet` to `Clinic` to `Organization` is too much, the edge to cut is the one from `Clinic` to `Organization`. Cutting close to the root severs a relationship people read through constantly. Cutting close to the leaf trims a tail that almost nobody reaches. It also localizes the fix: cut the deep edge and every entity above it gets narrower at once, without any of them changing.

**Cut the wide before the narrow.** Between two candidates, the one that drags in more columns is the one worth cutting. Cutting a narrow table saves you almost nothing and costs a `fetch()` at every call site.

**Optional relationships are the natural first cut.** A nullable foreign key is a `LEFT JOIN` that hydrates a null sub-object on the rows where the reference is absent. When the reference is usually absent, you are paying width for nothing.

Two edges to leave alone:

**Identifying foreign keys.** When the foreign key column is part of the referenced row's own primary key, the row cannot exist without its parent. That is composition, not association, and splitting it produces a model where you routinely hold half an object.

**Foreign-key-free targets.** A table with no outgoing foreign keys can never grow a subtree. Its cost is bounded by its own width, permanently, and no future schema change can make it worse. Lookup tables like `Country`, `PetType`, and `Currency` fall here, and there is rarely a reason to make one a `Ref`.

None of these cuts costs you the relationship, as above. The choice is about the SELECT you get by default, not about what you are allowed to ask.

---

## Circular References

Cycles are not a judgment call. Two entities that reference each other cannot both use direct types, so one side must be a `Ref`, and a self-reference is always a `Ref`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class Category(
    @PK val id: Int = 0,
    val name: String,
    @FK val parent: Ref<Category>?
) : Entity<Int>
```

</TabItem>
<TabItem value="java" label="Java">

```java
record Category(@PK Integer id,
                String name,
                @Nullable @FK Ref<Category> parent
) implements Entity<Integer> {}
```

</TabItem>
</Tabs>

The self-reference stays navigable: `Category_.parent.name` joins the table to itself. See [Cyclic References](metamodel.md#cyclic-references).

---

## How the Generator Decides

Everything above is reasoning for a developer, who knows which paths their application actually reads through. A generator working from a schema does not know that, so it applies a fixed rule instead, and the same schema always produces the same entities.

The rule is a guard rail, not an optimization pass. It is calibrated so that ordinary schemas trip nothing at all and every foreign key comes out as a direct type. It engages only where a graph would otherwise run away.

### The Budget

Storm's schema-first generation holds this invariant for every table, treated as a potential root:

- The table's inline closure adds at most **10 joins**.
- The table's inline closure adds at most **96 columns**, excluding the table's own.

A table's *inline closure* is the set of tables reachable from it through direct-type foreign keys. Define `columns(T)` as the table's own column count, counting [inline record](entities.md#embedded-components) components, which live in the same table and cost no join. Then `cost(T) = columns(T) + Σ cost(X)` across every edge from `T` that is inlined, and `joins(T)` is the number of joins that closure produces.

### The Algorithm

1. **Break cycles.** Every cycle gets one edge cut, and self-references are cut. Tables are visited in alphabetical order so the choice is reproducible.
2. **Order the remaining graph topologically**, leaves first.
3. **For each table, rank its outgoing foreign keys** into four tiers, breaking ties on the foreign key column name:
   1. Identifying foreign keys, where the column participates in the target's primary key.
   2. Non-null foreign keys to targets that have no foreign keys of their own and are at most 32 columns wide.
   3. Remaining non-null foreign keys, narrowest target first.
   4. Nullable foreign keys, narrowest target first.
4. **Inline down that ranking while the budget holds.** The first edge that would exceed it becomes a `Ref`, and so does every edge below it.

Working bottom-up is what makes the result stable. A new foreign key on a leaf table is cut at the leaf, so the entities above it keep the shape they already had. Cutting top-down would put the cut near whichever root happened to be processed first, and since a table has one class, roots would disagree about the same edge.

Ranking by cost is what removes the need for naming heuristics. `Country`, at two columns with no foreign keys, wins a budget in every schema it appears in. A transactional table that arrives with its own closure attached does not. The lookup-versus-aggregate distinction falls out of the schema instead of out of a list of table-name patterns.

Nothing is exempt from the budget, including tiers 1 and 2. The ranking gives those edges first claim, which in any realistic schema is enough for them to always survive, and the invariant holds without exceptions.

### A Worked Example

Take a veterinary schema: `country(2 columns)`, `pet_type(2)`, `city(3, references country)`, `owner(6, references city)`, `pet(5, references owner and pet_type)`, `visit(4, references pet)`.

Working leaves first: `cost(country) = 2` and `cost(pet_type) = 2`, both with no joins. `cost(city) = 3 + 2 = 5` across one join. `cost(owner) = 6 + 5 = 11` across two. `cost(pet) = 5 + 11 + 2 = 18` across four. `cost(visit) = 4 + 18 = 22` across five.

The widest closure in the schema belongs to `visit`, at 5 joins and 18 columns. Both budgets are untouched, so nothing is cut and every foreign key is generated as a direct type.

Now give `pet` a reference to a 40-column `clinic` table that carries its own foreign keys to `address` and `organization`. The closure under `visit` roughly doubles in joins and passes 96 columns, so the budget fires. The cut lands on `clinic`'s widest edge, `organization`, which is both the deepest edge and the one carrying the most width. The edge from `visit` to `pet`, and the edge from `pet` to `owner`, are untouched.

### Existing Entities

When generation runs against entities that already exist, a direct type that now exceeds the budget is **reported, never flipped**. You get a diagnostic naming the table and the overrun, and the code is left as written.

An entity that diverges from the rule is assumed to be doing so deliberately. The budget describes what generation produces from a schema, and it does not describe what your model is permitted to be. If you know a path is read constantly, inline past the budget. If you know one is read almost never, cut inside it.

---

## Tips

1. **Model foreign keys as direct types until something says otherwise.** Joins on primary keys are cheap, and one query beats several.
2. **Design against inherited growth, not against joins.** The cost that bites is the subtree an edge lets in later, in an entity nobody was editing.
3. **Cut deep, not shallow.** Trimming the tail of a graph narrows every entity above it at once.
4. **Never cut a lookup table.** A table with no foreign keys of its own has a cost that cannot grow.
5. **Cut optional relationships first.** A nullable foreign key is a `LEFT JOIN` whose width buys you nulls on the rows that lack the reference.
6. **A `Ref` is not a smaller relationship.** It stays filterable, orderable, and selectable through the metamodel. See [Refs](refs.md).
