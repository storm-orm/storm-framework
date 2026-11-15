import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Statement Advice

Storm watches the statements your application runs and tells you when one of them can be written better. It might return rows in an unpredictable order, read far more rows than it needs, or run once per row inside a loop. Each piece of advice is one log entry. It says what is wrong, what to change, and links to its section on this page.

```
SQL advice (SELECT Owner): 412 ms in database, 12 rows, OwnerService.kt:42
	SELECT DISTINCT o.id, o.first_name, o.last_name, ...
	FROM owner o
	INNER JOIN pet p ON p.owner_id = o.id
	WHERE p.name = ?
	shape: 9b34305d1a7c4e02
	advice: the join to Pet repeats each row once per related row, and DISTINCT removes the copies afterwards.
		fix: semiJoin(Pet) filters by Pet without repeating rows, so the query needs no DISTINCT.
		see: https://orm.st/docs/advice#distinct-over-join
```

Advice is cheap to have on. Storm decides it once per statement shape, when it builds the statement, not on every execution. Checks that need evidence, such as whether an index is missing, run only for an execution that was already slow.

## Seeing Advice

In development, you don't need to configure anything. Spring Boot with DevTools on the classpath, Ktor in development mode, and every `@StormTest` report all advice. It goes to the logger `st.orm.sql.advice` at `INFO`.

In production, Storm stays quiet by default:

- **Correctness advice** is reported in every environment. A query that returns an unpredictable part of its result is wrong wherever it runs. The one exception is [`writes-without-transaction`](#writes-without-transaction), which needs the [performance log](sql-logging.md#the-performance-log), since only that log sees a whole unit of work.
- **Performance and design advice** appear only on the line of a slow statement, once you set a slow threshold. See [The Slow Statement Log](sql-logging.md#the-slow-statement-log).
- **An idle transaction**, one that holds its connection for more than a second without running a statement, is reported by default.

To choose the categories yourself, in any environment, name them:

<Tabs groupId="framework">
<TabItem value="spring" label="Spring Boot" default>

```yaml
storm:
  advice:
    categories: correctness, design, performance
```

</TabItem>
<TabItem value="ktor" label="Ktor">

```kotlin
install(Storm) {
    adviceCategories = listOf("correctness", "design", "performance")
}
```

</TabItem>
<TabItem value="jvm" label="Plain JVM">

```
-Dstorm.advice.categories=correctness,design,performance
```

</TabItem>
</Tabs>

At `INFO`, each piece of advice is reported once, the first time a statement draws it. To get a full list of what to change, set `st.orm.sql.advice` to `DEBUG`, run the application or the test suite, and work through the lines:

| Level | How often a piece of advice is reported |
|-------|-----------|
| `INFO` | once, at the first statement that draws it |
| `DEBUG` | once per statement shape |
| `TRACE` | at every execution |

A statement shape is the statement text without its parameter values. The offset of a page is part of the text, so each page of the same query is a shape of its own.

## Reading an Advice Line

Every piece of advice has the same layout. The first line, after `advice:`, says what the statement does and what that costs. Each line below it holds one more part:

| Label | What it holds |
|-------|---------------|
| `fix:` | The change to make. |
| `or:` | An alternative fix, for when the first one does not suit the code. |
| `and:` | A further change that belongs to the same fix. |
| `at:` | The line of code that ran the statement. `OwnerService.kt:52 -> OwnerRepository.getById` names your code first, then the repository method it called. |
| `cost:` | What each execution costs, where the statement itself is what to look at. |
| `index:` | The index to create, and what it does for the statement. |
| `covering:` | The columns to append to that index so the database reads the index alone, without the table. |
| `idle:`, `longest idle:`, `first statement:`, `last statement:` | Where an idle transaction spent its time. |
| `see:` | The section of this page that explains the advice. |

The link after `see:` stays valid for as long as Storm gives the advice, so a log line written today still leads here after the rest of the documentation has moved on.

## All Advice at a Glance

| Advice | What it tells you | Category | Reported by |
|--------|-------------------|----------|-------------|
| [`unordered-limit`](#unordered-limit) | A limit or offset without an ORDER BY returns an unpredictable part of the result. | correctness | advice log, everywhere |
| [`writes-without-transaction`](#writes-without-transaction) | Related writes commit one by one, so a failure leaves half the work behind. | correctness | performance log |
| [`deep-offset`](#deep-offset) | A large offset reads and discards every row it skips. | performance | advice log |
| [`page-count`](#page-count) | A page runs a count of every matching row to report its total. | performance | slow statement log |
| [`distinct-over-join`](#distinct-over-join) | A join repeats rows that DISTINCT then removes again. | performance | advice log |
| [`in-list-shapes`](#in-list-shapes) | An IN list of varying length is a new statement to the database for every length. | performance | advice log |
| [`repeated-select`](#repeated-select) | The same one-row read runs again and again, typically in a loop. | performance | advice log, performance log |
| [`repeated-write`](#repeated-write) | The same one-row write runs again and again instead of as one batch. | performance | advice log, performance log |
| [`repeated-fetch`](#repeated-fetch) | A `Ref` is fetched one row at a time: the N+1 pattern. | design | advice log, performance log |
| [`idle-transaction`](#idle-transaction) | A transaction holds its connection, and its locks, while the application does other work. | performance | slow statement log |
| [`missing-index`](#missing-index) | No index serves the columns a slow statement filters or sorts by. | performance | slow statement log |
| [`unindexed-foreign-key`](#unindexed-foreign-key) | A slow delete checks a foreign key that no index serves. | performance | slow statement log |
| [`unindexed-search`](#unindexed-search) | No index serves a slow `search` or `suggest`. | performance | slow statement log |
| [`entity-budget`](#entity-budget) | Every read of an entity joins more tables than the design budget allows. | design | advice log, once per entity |
| [`wide-entity-read`](#wide-entity-read) | A slow read joins many tables, and a `Ref` would cut them. | design | slow statement log |

The three categories:

- **Correctness.** The statement can return other rows than you intended, however fast it runs.
- **Performance.** The statement costs more than it needs to as the data grows. On a small table, the cost is usually not worth changing anything for.
- **Design.** The entity model makes reads cost more than they need to. Whether a foreign key is an inline entity or a `Ref` decides what every read of the entity joins. [Entity Design](entity-design.md) gives the reasoning.

## Where Advice Is Reported

Storm reports advice in three places. Each sees a different scope, so each reports the advice that scope can prove.

**The advice log** (`st.orm.sql.advice`) reports advice on a statement whether it ran fast or slow. Each entry shows the statement, its call site, its shape and what the execution cost. When a transaction completes, the advice log also reports what the transaction repeated, as one block under the line of code that began the transaction:

```
SQL advice (transaction): VisitReminderService.kt:41
	advice: the same SELECT of Visit ran 40 times, 120 ms, 3.0 ms each, one row each.
		at: VisitReminderService.kt:52
		fix: one query for all of them, such as findAllById or a where with IN, reads the rows in one round trip.
		see: https://orm.st/docs/advice#repeated-select
```

**The slow statement log** (`st.orm.sql.slow`) reports each execution that exceeds a threshold you set, and ends its line with every piece of advice the statement carries. Some advice is reported only here, because it is only worth acting on once the statement is slow:

- **Advice on a deliberate choice.** A page that reports its total is a choice of `page` over `slice`. The [count](#page-count) is worth questioning only once it is slow.
- **Advice that needs evidence.** Whether an index pays off depends on how many rows a table holds, which Storm does not know. A slow execution is the evidence. This applies to a [missing index](#missing-index), an [unindexed foreign key](#unindexed-foreign-key), an [unindexed search](#unindexed-search) and a [wide entity read](#wide-entity-read).
- **An idle transaction**, measured against a threshold of its own. See [idle-transaction](#idle-transaction).

The slow statement log shows all the advice a statement carries, including advice you have accepted: a slow statement is a reason to look at it again. See [The Slow Statement Log](sql-logging.md#the-slow-statement-log).

**The performance log** reports a summary per unit of work, such as an HTTP request, and closes it with advice that no single transaction shows: a loop that opens a transaction per item and repeats the same read or write, and related writes that each committed on their own. See [The Performance Log](sql-logging.md#the-performance-log).

<details>
<summary>Which scope each piece of advice is derived in</summary>

| Advice | Derived per | Reported |
|--------|-------------|----------|
| `unordered-limit`, `deep-offset`, `distinct-over-join`, `in-list-shapes` | statement shape, for the life of the application | the advice log, once per shape, with the call site and the cost of the execution; every slow line of the shape |
| `repeated-select`, `repeated-write`, `repeated-fetch` within a transaction | transaction | the advice log when the transaction completes, one block under its call site |
| the same, across transactions, and `writes-without-transaction` | the unit of work a performance log scope covers, less the executions a transaction already advised on | the performance log summary |
| `idle-transaction` | transaction | the slow statement log when the transaction completes |
| `page-count`, `missing-index`, `unindexed-search`, `unindexed-foreign-key`, `wide-entity-read` | statement shape, decided for a slow execution | the slow statement log |
| `entity-budget` | entity, at startup or first use | the advice log, once per entity |

A transaction records its statements only while the advice log reports performance or design advice, as it does in development, and discards the record when it completes. The transaction block is counted per call site: once per piece of advice at `INFO`, once per repeat at `DEBUG`, every time at `TRACE`.

</details>

## Accepting Advice

Advice is a suggestion. When the code is as you intend it, accept the advice with `@AcceptAdvice`, and the advice log stops reporting it there:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// The admin export reads every order with its full graph.
@AcceptAdvice("entity-budget")
data class Order(@PK val id: Int = 0, ...) : Entity<Int>

interface OrderRepository : EntityRepository<Order, Int> {

    // The admin screen jumps to any page, so it keeps the offset.
    @AcceptAdvice("deep-offset")
    fun adminOrders(pageable: Pageable): Page<Order> = select().page(pageable)
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
// The admin export reads every order with its full graph.
@AcceptAdvice("entity-budget")
record Order(@PK Integer id, ...) implements Entity<Integer> {}

interface OrderRepository extends EntityRepository<Order, Integer> {

    // The admin screen jumps to any page, so it keeps the offset.
    @AcceptAdvice("deep-offset")
    default Page<Order> adminOrders(Pageable pageable) {
        return select().page(pageable);
    }
}
```

</TabItem>
</Tabs>

- **Name the advice** by the part of its link after `#`, such as `page-count`. Without a name, every piece of advice is accepted there. A name that matches no advice is ignored.
- **On an entity**, it accepts advice on the statements that read or write the entity, and the entity's own design advice.
- **On a method or class**, it accepts advice on the statements that run while the method runs: a service method, a repository method, or a whole repository interface, including its built-in methods such as `page(...)`. A lambda counts as part of the method that declares it, and so does the body of a `transaction { }`. A query that a service builds from `select()` runs in the service, so accept its advice on the service method.
- **The slow statement log still shows accepted advice.** A slow statement is a reason to look at the advice again. Advice that only the slow statement log reports, such as `page-count` or `missing-index`, therefore cannot be accepted; it appears only while the statement is slow.

To silence one piece of advice everywhere, turn its logger off: `st.orm.sql.advice.deep-offset: OFF`. Each piece of advice has a logger named after its anchor.

Accepting costs nothing per execution. The first execution of a statement shape that carries the advice decides whether it is accepted, with one look at the call stack, and a statement without advice pays nothing.

## Design: Known in Advance, and Shown by Real Use

Some design problems show in the model alone. An entity whose read [exceeds the design budget](#entity-budget) does so however the application uses it: every read pays for its graph. In development, Storm checks every entity against the budget once, and the advice log reports the ones past it.

Other design questions only real use can answer. Whether a `Clinic` on every `Visit` is worth its width depends on how the application reads visits. Real use answers it both ways:

- A read that is [slow and wide](#wide-entity-read) says what a `Ref` would save.
- A `Ref` that the application [fetches anyway](#repeated-fetch) says it should be an inline entity.

A wide graph that reads fast is never advised, and an entity within the budget is never advised in advance.

## Loading the Schema

Advice about indexes needs to know which indexes and foreign keys the database has. Storm reads them when it loads the schema. Without a loaded schema, Storm still works the same, but it gives no [missing-index](#missing-index), [unindexed-foreign-key](#unindexed-foreign-key) or [unindexed-search](#unindexed-search) advice.

- **Schema validation loads it.** Spring Boot and Ktor validate the schema at startup by default, which loads it too. Most applications have it loaded without doing anything.
- **Load without validating.** Set `storm.validation.schema-mode: load` in Spring Boot, or `schemaValidation = "load"` in Ktor. Elsewhere, call `orm.loadSchema()`.
- **Load again after a migration.** The loaded schema is a snapshot, read once per data source and shared by every template on it. After a migration that changed the schema while the application runs, such as one that added an index, call `loadSchema()` again. A table created after the load gets no advice until then.

## A Limit or Offset Without an Order {#unordered-limit}

```text
the statement takes part of the result without an ORDER BY, so the database picks the rows, which can change between executions, and pages can repeat or skip rows.
fix: order by a unique key, or by sort fields that end in one.
```

**What it means.** Without an ORDER BY, a database returns rows in whatever order is cheapest for it at that moment. The order can change with the query plan, with concurrent writes, or from one execution to the next. A `limit` or `offset` then takes an arbitrary part of the result: page one and page two can overlap or miss rows between them. A test on a small, quiet table rarely shows it.

**How to fix it.** Order by something unique, so every row has exactly one place in the order. Order by the primary key, or end your sort fields with it:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .orderBy(Owner_.lastName, Owner_.id)
    .limit(20)
    .resultList

val pageable = Pageable.ofSize(20).sortBy(Owner_.lastName).sortBy(Owner_.id)
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> owners = orm.entity(Owner.class)
    .select()
    .orderBy(Owner_.lastName, Owner_.id)
    .limit(20)
    .getResultList();

Pageable pageable = Pageable.ofSize(20).sortBy(Owner_.lastName).sortBy(Owner_.id);
```

</TabItem>
</Tabs>

**When Storm reports it.** Category: correctness. Reported by the advice log everywhere, production included, and on every slow line of the statement. Logger: `st.orm.sql.advice.unordered-limit`. `scroll` always orders by its key, so it never gets this advice, and neither does `limit(1)`, which asks for any one matching row.

## Related Writes That Each Commit on Their Own {#writes-without-transaction}

```text
the unit of work wrote Owner and then Pet, which references Owner, each committing on its own, so when a later write fails, the earlier ones stay.
fix: run them in one transaction.
```

**What it means.** A write outside a transaction commits on its own. When an owner is inserted and then its pet, and the pet fails, the database keeps an owner without the pet.

**How to fix it.** Run the writes in one transaction, so a failure rolls back what came before it:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
transaction {
    val owner = owners.insertAndFetch(newOwner)
    pets.insert(Pet(name = "Leo", owner = owner))
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
transaction(tx -> {
    Owner owner = owners.insertAndFetch(newOwner);
    pets.insert(new Pet(null, "Leo", owner));
    return null;
});
```

</TabItem>
</Tabs>

**When to leave it.** Separate commits are sometimes the point, such as an audit record that must survive the failure of what it describes. Accept the advice on that entity with `@AcceptAdvice("writes-without-transaction")`, or on the method or class that runs the unit of work.

**When Storm reports it.** Category: correctness. Reported by the [performance log](sql-logging.md#the-performance-log) summary of the unit of work, so the performance log has to cover it.

<details>
<summary>How Storm decides</summary>

Writes outside a transaction are often deliberate: a batch job that commits as it goes, an outbox row, an audit record. Advice on every unit of work with several writes would be noise. Storm advises on one shape that is almost never deliberate: a write to a table that references a table an earlier write of the same unit of work wrote.

Each recorded statement keeps whether it ran in a transaction. The summary walks the writes once and advises once per pair of types. The check is by type, from the model's foreign keys, not by row, so two writes to related types that touch unrelated rows are advised as well. Writes Storm does not run, such as through another library, are not seen.

</details>

## A Deep Offset {#deep-offset}

```text
the database reads and discards the 1000 rows the offset skips before it returns any.
fix: scroll(...) continues from the last row read (keyset pagination), so every page costs what the first one does.
```

**What it means.** An offset tells the database to skip rows, and it skips them by reading them. Page 100 of ten rows reads a thousand rows to return ten, and every further page reads ten more.

**How to fix it.** Use `scroll`. It continues from the last row read instead of counting rows from the start: it compares the sort fields and the key with that row's values, which an index on them serves. Every page costs what the first one does.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val first = owners.scroll(Scrollable.of(Owner_.id, 20))
val second = first.next()?.let { owners.scroll(it) }
```

</TabItem>
<TabItem value="java" label="Java">

```java
Window<Owner> first = owners.scroll(Scrollable.of(Owner_.id, 20));
Window<Owner> second = owners.scroll(first.next());
```

</TabItem>
</Tabs>

**When to leave it.** `scroll` moves page by page; it cannot jump to page 100. A screen that needs to jump keeps the offset and pays for it: accept the advice with `@AcceptAdvice("deep-offset")`.

**When Storm reports it.** Category: performance. Reported by the advice log in development, and on every slow line of the statement. Storm gives this advice from an offset of 1,000 rows. Logger: `st.orm.sql.advice.deep-offset`. See [Scrolling](pagination-and-scrolling.md#scrolling).

## The Count Behind a Page {#page-count}

```text
page(...) runs a count of every matching row to report the total.
fix: slice(...) reads the same page without the count, and says whether a next page exists.
```

**What it means.** `page` reports the total number of matching rows. To find it, the database runs a count query that visits every matching row, however small the page. On a large result, the count costs more than the page itself.

**How to fix it.** A list that shows "next" rather than "page 3 of 120" needs no total. `slice` reads the same page with one row beyond it, which says whether a next page exists, and runs no count:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val slice: Slice<Owner> = owners.slice(Pageable.ofSize(20).sortBy(Owner_.id))
if (slice.hasNext()) { ... }
```

</TabItem>
<TabItem value="java" label="Java">

```java
Slice<Owner> slice = owners.slice(Pageable.ofSize(20).sortBy(Owner_.id));
if (slice.hasNext()) { ... }
```

</TabItem>
</Tabs>

If the total is needed but changes slowly, count it once, keep it, and pass it in: `page(pageable, totalCount)`. A page that is not full is its own total, so Storm skips the count for it. See [Slices](pagination-and-scrolling.md#slices).

**When to leave it.** If the screen shows the total, keep `page`. The advice appears only while the count is slow, which is when the total is worth questioning.

**When Storm reports it.** Category: performance. Reported by the slow statement log only: choosing `page` is a decision about the screen, not a mistake, so the count is questioned only once it is slow.

## DISTINCT Over a Join That Repeats Rows {#distinct-over-join}

```text
the join to Pet repeats each row once per related row, and DISTINCT removes the copies afterwards.
fix: semiJoin(Pet) filters by Pet without repeating rows, so the query needs no DISTINCT.
```

**What it means.** An inner join to the many side of a relation returns a row per related row. An owner with three matching pets comes back three times, and `distinct` removes the copies again. The database builds every copy first, then sorts or hashes the whole result to find them.

**How to fix it.** If the joined table only filters the query, use a semi-join. It keeps each row that has a matching related row, once, with no copies to remove:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity(Owner::class)
    .select()
    .semiJoin<Pet>().on<Owner>()
    .where(Pet_.name eq "Leo")
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<Owner> owners = orm.entity(Owner.class)
    .select()
    .semiJoin(Pet.class).on(Owner.class)
    .where(Pet_.name, EQUALS, "Leo")
    .getResultList();
```

</TabItem>
</Tabs>

**When Storm reports it.** Category: performance. Reported by the advice log in development, and on every slow line of the statement. Logger: `st.orm.sql.advice.distinct-over-join`. The advice applies to a `distinct` select of the query's own table over an inner join. A left join keeps the rows without a related row, which a semi-join would drop, so it gets no advice. A query that orders or selects by the joined table's columns needs its join, since a semi-joined table adds no columns, so it gets no advice either. See [Filtering by Related Rows](queries.md#filtering-by-related-rows).

## An IN List of Many Lengths {#in-list-shapes}

```text
the IN list has run with 8 or more different lengths, and the database parses, plans and caches each length as a separate statement.
fix: storm.in_list=padded pads every list the template renders to the next power of two by repeating its last value, which changes no result and keeps the statements to a few.
```

**What it means.** An `IN` list renders one placeholder per value: `IN (?, ?, ?)`. To Storm that is one statement whatever its length, but to the database a list of 3 values and a list of 4 are different statements. Each is parsed, planned and cached on its own, and a lookup of a varying number of keys fills the database's plan cache with variants.

**How to fix it.** Set `storm.in_list=padded` (Spring Boot `storm.in-list: padded`). Storm then pads every list to the next power of two by repeating its last value, so lengths 1 to 1,000 render as 11 statements. Repeating a value changes no result. The setting applies to every list the template renders. See [IN Lists](queries.md#in-lists).

**When to leave it.** If a statement runs in many lengths by design, and the database's plan cache has room for them, accept the advice with `@AcceptAdvice("in-list-shapes")`.

**When Storm reports it.** Category: performance. Reported by the advice log in development once a statement shape has run with eight different lengths, and on every slow line of the statement, where `lengths: 12` says how many it has run with so far. Logger: `st.orm.sql.advice.in-list-shapes`.

<details>
<summary>Databases this advice does not apply to</summary>

- **PostgreSQL** binds a list on a plain column as one array parameter, so that list is one statement at any length and never gets this advice. The advice there points at a list compared through a function, which renders placeholders. `padded` pads that list too, at the cost of the array for the others.
- **MySQL and MariaDB** plan every execution anew and keep no plan per statement text, so a list of 17 values costs them what a list of 18 costs. Padding would bind values for nothing, so Storm records no lengths there. The dialect states this through `SqlDialect.keepsPlanPerStatementText()`.

</details>

## The Same Row Lookup, Again and Again {#repeated-select}

```text
getById of Owner ran 24 times, 72 ms, 3.0 ms each, one row each.
at: OwnerService.kt:52 -> OwnerRepository.getById
fix: findAllById over the keys reads the rows in one round trip.
```

**What it means.** The same one-row read ran ten or more times from one line of code. That is almost always a loop that looks rows up one by one. Every lookup is a round trip to the database.

**How to fix it.** Read all the rows in one query. For a built-in repository call, the advice names its list form, such as `findAllById(ids)` or `findAllByRef(refs)`. For `existsById`, `findAllById` reads the ones that exist. For a query of your own, use a `where` with `IN` over the keys:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// One query per id
val found = ids.map { owners.getById(it) }

// One query for all of them
val found = owners.findAllById(ids)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// One query per id
List<Owner> found = ids.stream().map(owners::getById).toList();

// One query for all of them
List<Owner> found = owners.findAllById(ids);
```

</TabItem>
</Tabs>

The `at:` line names your code first and the repository method it called second, `ReminderService.kt:52 -> VisitRepository.findByPet`. The loop at the caller is what to change, and the repository is where a list variant such as `findByPets` would go.

A read of computed values, such as a count per pet, is not a lookup of rows. The advice then suggests one query with a `where` with `IN` over the values the loop varies, grouped by that column if the loop uses the counts.

**Look at the statement first when each execution is slow.** If each execution takes 10 ms or more, far more than a round trip, the advice adds a `cost:` line: the statement itself is the cost, and combining twelve executions of 170 ms each saves little. If the schema is loaded, the index such a statement lacks follows as [missing-index](#missing-index) advice.

**When to leave it.** Accept the advice on the method that runs the loop, or on the entity, with `@AcceptAdvice("repeated-select")`.

**When Storm reports it.** Category: performance. Within one transaction, the advice log reports it when the transaction completes. When the repeats span several transactions, such as a loop that opens a transaction per item, the [performance log](sql-logging.md#the-performance-log) summary reports it and says how many transactions they ran in. A repeat that took less than 50 ms in total carries no advice: most repeats in a larger application cost a few milliseconds and are fine. `storm.advice.repeat_floor` (Spring Boot `storm.advice.repeat-floor`, Ktor `adviceRepeatFloor`) sets that floor, and `0` advises on every repeat.

<details>
<summary>How Storm counts a repeat</summary>

- **What counts.** The same SELECT, ten or more times, from one call site, each execution returning at most one row. A read of several rows each time, such as scrolling page after page, gets no advice. A shape repeated from two loops is two pieces of advice, each pointing at its own loop and weighed by its own cost.
- **How it is named.** A read that a built-in repository method made (`findById`, `getById`, `existsById` or their `Ref` forms) is named by the method. A query of your own is named "the same SELECT". Where repository methods call each other, the one your code called is named. A repository obtained without an interface is named by its method alone.
- **Concurrent executions.** Executions that ran at the same time cost the caller the time they covered on the clock, not their sum. Where the sum is at least twice that, the line states both, `727 ms over 140 ms elapsed`, and the advice is ordered by the time on the clock.
- **One run is a rough judge near the floor.** The first run after startup reads on cold caches, so a loop advised at 188 ms then may cost 25 ms on every later run. Each repeat is reported once per call site, however many runs cross the floor.
- **Within or across transactions.** Within one transaction every execution sees the same data, so the advice is the transaction's, reported as one block under the line of code that began it. A joined block counts toward the transaction it joined; a `REQUIRES_NEW` block is a transaction of its own. The performance log summary leaves the executions a transaction already advised on to that transaction, so one repeat never reads as two problems.
- **The call site that heads the block.** It is captured where the transaction's block is entered, so a suspending transaction, which completes on another thread, is named all the same. A transaction another manager opened, such as a Spring `@Transactional` method, is named by the method still on the stack when it completes.
- A line whose executions read nothing says `no rows`.

</details>

## The Same Single-Row Write, Again and Again {#repeated-write}

```text
insert of Owner ran 40 times, 56 ms, 1.4 ms each, one row each, in 40 transactions of their own.
fix: the overloads that take a list send the rows as one batch, which commits them together.
```

**What it means.** The same INSERT, UPDATE or DELETE ran ten or more times, one row each. Every row takes a round trip of its own.

**How to fix it.** Use the repository overloads that take a list, such as `insert(owners)`. They send the rows as one JDBC batch. A batch counts as one execution, so batched writes never get this advice.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// One statement per owner
newOwners.forEach { owners.insert(it) }

// One batch
owners.insert(newOwners)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// One statement per owner
newOwners.forEach(owners::insert);

// One batch
owners.insert(newOwners);
```

</TabItem>
</Tabs>

The fix depends on the write:

- **An insert, or an update or removal of entities you pass in**, takes the list overload.
- **A write by condition**, such as `removeAll(Visit_.pet eq pet)` per pet, has no list overload. The advice names one statement whose condition covers all of them, such as `Visit_.pet inList pets`.
- **A write between calls to another service.** If the writes are spread over far more time than they spent in the database, with other work between them, the advice says so. Collect the rows and write them after the loop. Do not wrap the loop in a transaction, which would hold the connection across every call:

  ```text
  insert of PetRegistration ran 29 times, 96 ms, 3.3 ms each, one row each, in 29 transactions of their own, spread over 7157 ms with work outside the database between them.
  at: PetRegistryService.kt:60
  fix: collect the rows and write them after the loop through the overloads that take a list, which sends them as one batch and commits them together, rather than a transaction around the loop.
  ```

- **An upsert that mostly rewrites what is already there.** A sync that upserts the same rows on every run writes back what the rows already hold. Read them first and write only what is new or changed; in the steady state that leaves one select and no writes. On some databases an upsert also draws a generated key even when it updates, and the advice says so too:

  ```text
  upsert of PetRegistration ran 29 times, ...
  fix: if the rows mostly exist and rarely change, read them first and write only the new or changed ones, which skips the needless writes and the generated key each upsert draws even when it updates
  or: the overloads that take a list send the rows as one batch, which commits them together.
  ```

**A batch can change what commits together.** Within one transaction it changes nothing. Across transactions it does: when each write ran in a transaction of its own, the line says `in 40 transactions of their own`, and the fix says a batch commits them together. A task that records each answer from a remote registry in its own transaction keeps the answers so far when a later call fails; batched, they commit or fail together. The choice is yours.

**When to leave it.** Accept the advice on the method that runs the loop, or on the entity, with `@AcceptAdvice("repeated-write")`.

**When Storm reports it.** Category: performance. Reported in the same places, and with the same 50 ms floor, as [the same row lookup](#repeated-select).

<details>
<summary>How Storm decides</summary>

- **How it is named.** A write that a repository method made for an entity you passed (`insert`, `update`, `upsert` or `remove`) is named by the method. A write of your own is named "the same INSERT", "the same UPDATE" and so on. A database's own upsert form, including a `MERGE` that both updates and inserts, is named an upsert.
- **A write once per unit of work.** A write that ran once in each of several transactions that also did other work, such as a delete run once per rebuild inside that rebuild's transaction, is a loop over units of work. No batch can merge those writes without taking them out of the transactions they belong to, so the repeat carries no advice, unless each execution took 10 ms or more. Then the advice says the statement itself is the cost, without a fix for the loop.
- **Spread writes.** Storm keeps when each execution began and returned, and when every other statement ran. The writes are spread when the time between the first and the last that no statement spent in the database is at least ten times what they spent inside it. A loop that runs other queries between the writes is not spread by them. A read is never said to be spread.
- **The generated key.** The dialect states through `SqlDialect.upsertTakesGeneratedKeyOnUpdate()` whether its upsert draws a key value before it finds the row to update.

</details>

## References Fetched One at a Time {#repeated-fetch}

```text
24 statements each fetched one City through a Ref, 64 ms, 2.7 ms each.
at: OwnerView.kt:31
fix: declare the field as City instead of Ref<City>, so every read joins it in
or: keep the Ref and fetch it in the query that reads the rows, such as select().fetch(...), so one statement reads both.
```

**What it means.** A `Ref` is a reference that Storm loads only when you call `fetch()`. Each `fetch()` that the transaction's entity cache cannot answer is a query of its own. A loop over rows that fetches a reference from each runs one query per row: the N+1 pattern.

**How to fix it.** Choose by who needs the referenced row:

- **Every reader needs it.** Declare the field as the entity rather than a `Ref`. Storm then joins it into every read of the entity, including repository methods such as `findById`. See [Entity Design](entity-design.md).
- **Only some readers need it.** Keep the `Ref`, and fetch it in the query that reads the rows. `select().fetch(...)` adds the referenced table to that statement. Repository methods such as `findById` or `findAll` take no `fetch`, so write that read as a `select()`. See [Resolving a Ref as Part of the Query](refs.md#resolving-a-ref-as-part-of-the-query).

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Every reader needs the city: an inline entity
data class Owner(@PK val id: Int = 0, @FK val city: City) : Entity<Int>

// Only this reader needs it: keep the Ref, fetch it in the query
data class Owner(@PK val id: Int = 0, @FK val city: Ref<City>) : Entity<Int>

val owners = orm.entity(Owner::class)
    .select()
    .fetch(Owner_.city)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Every reader needs the city: an inline entity
record Owner(@PK Integer id, @FK City city) implements Entity<Integer> {}

// Only this reader needs it: keep the Ref, fetch it in the query
record Owner(@PK Integer id, @FK Ref<City> city) implements Entity<Integer> {}

List<Owner> owners = orm.entity(Owner.class)
    .select()
    .fetch(Owner_.city)
    .getResultList();
```

</TabItem>
</Tabs>

**When to leave it.** If a loop over a few rows fetches by design, accept the advice on the method that runs it, or on the entity, with `@AcceptAdvice("repeated-fetch")`.

**When Storm reports it.** Category: design. Reported by the advice log when a transaction that fetched ten or more references of one type completes, and by the [performance log](sql-logging.md#the-performance-log) summary when the fetches spanned several transactions. The same 50 ms floor as for [the same row lookup](#repeated-select) applies. The `at:` line names the line of code that called `fetch()`: in a transaction from the tenth fetch on, in a summary when the performance log records call sites.

## A Transaction That Holds Its Connection Idle {#idle-transaction}

```text
the transaction held its connection for 2300 ms, 2180 ms of it idle after its last statement, with its row locks held for 2100 ms of that.
last statement: the UPDATE of Order
fix: the work after the UPDATE of Order can run after the commit; an onCommit callback runs it there.
```

**What it means.** A transaction keeps its database connection from its first statement until it commits. If it calls another service, sends mail or renders a document in between, the connection stays out of the pool for all that time. After the transaction's first write, other transactions also wait on its row locks. Every statement can be fast, so no slow statement line shows this. Under load, the pool runs dry and requests queue.

**How to fix it.** Move the work that does not need the database out of the transaction. Where the idle time was decides how:

- **After the last statement.** Run the work after the commit. An `onCommit` callback runs it there, and only if the transaction commits:

  <Tabs groupId="language">
  <TabItem value="kotlin" label="Kotlin" default>

  ```kotlin
  transaction {
      val order = orders.updateAndFetch(placed)
      onCommit { mail.sendConfirmation(order) }
  }
  ```

  </TabItem>
  <TabItem value="java" label="Java">

  ```java
  transaction(tx -> {
      Order order = orders.updateAndFetch(placed);
      tx.onCommit(() -> mail.sendConfirmation(order));
      return null;
  });
  ```

  </TabItem>
  </Tabs>

- **Before the first statement.** Do the work before the transaction starts, such as computing what to write.
- **Between statements.** Here the work uses what the reads returned, so it cannot move out as a whole. If the writes need not see the reads under the same transaction, do the reads and the work first, then write in a short transaction of its own. A transaction that only reads splits the other way: read what the work needs inside it, and do the work after it returns. If the reads also need not see one consistent state of the database, and the statements are 10 ms or more apart on average, the advice also suggests reading without a transaction, which holds a connection only while each statement runs.

The advice names the line of code at the edge of the idle time where Storm captured it, such as `the work after OrderService.kt:88 can run after the commit`. Between statements, it names the longest gap, with the statements on either side of it, and counts the gaps of 1 ms or more. One gap is one step to look at; hundreds are a loop whose work per row adds up:

```text
the transaction held its connection for 92379 ms, 34736 ms of it idle between statements, with its row locks held for 780 ms of that.
longest idle: 4210 ms, after SlotService.kt:88 -> SlotRepository.findOpenByClinic, before AppointmentService.kt:370 -> AppointmentRepository.insert; 412 gaps of 1.0 ms or more in all
fix: if the writes need not see this transaction's reads, do the reads and the work between them first, then write in a short transaction of its own.
```

The row-lock figure helps you weigh the fix. Here the locks were held for only 780 ms of 34736 ms, so the cost is mostly the pooled connection, not other transactions waiting on rows. That may not be worth restructuring a batch job for.

**When to leave it.** A batch job may hold its transaction by design. Accept the advice on the method that runs the transaction with `@AcceptAdvice("idle-transaction")`.

**When Storm reports it.** Category: performance. Reported by the slow statement log at `WARN` when the transaction completes, in production too, with the line of code that began the transaction. This needs no slow statement threshold: a transaction is reported when it is idle for more than one second beyond what its statements spent. `storm.sql_log.slow.transaction` (Spring Boot `storm.sql-log.slow.transaction`, Ktor `sqlLogSlowTransaction`) changes the second, and `0` turns the watch off. Where the advice log reports performance advice, as in development, the transaction's advice log block carries this advice too. See [A Transaction That Holds Its Connection](sql-logging.md#a-transaction-that-holds-its-connection).

<details>
<summary>How Storm measures it</summary>

- **Worth acting on.** A transaction past the threshold is reported at `WARN` only where its idle time is at least a quarter of the time it held the connection, or where its row locks were held for a second or more of the idle time. Below that, its statements are the main cost, and it is reported at `TRACE`.
- **Idle time.** Storm records when a transaction takes its connection and when it returns it, and what each statement spent on it. The difference is the idle time. The statements' time is the union of their intervals: a statement run while another is open, such as one run while a stream is read, adds only the time past the other's close. The threshold is on the idle time, not on the transaction's length: a transaction that spends all its time in statements is never reported.
- **Row locks.** They are counted from the first write on, until the connection is returned, the commit included. A transaction that reads, computes for a second and then writes held no locks during that second, and its line says the locks were held for the commit alone.
- **Where the time went.** The advice names each part, before the first statement, between statements or after the last, that holds a tenth of the idle time or more. The fix follows the part with the most.
- **Spring-managed transactions** are measured from Storm's first statement in them, since Spring opened the connection before Storm saw the transaction. The line then says `from its first statement`.
- **A joined block** is part of the transaction it joined, which is reported once. A `REQUIRES_NEW` block is measured on its own.
- **Call sites.** A blocking transaction is named by the method running it, captured as it completes. A suspending block is captured as it is entered, since it completes on another thread. A block entered from a helper in a package named in `call-site-skip`, with no application code beyond it, names the helper's innermost frame. A transaction that Ktor's `transactional { }` begins is named by its route, such as `GET /owners/{id}`.
- **Statements at the edges** are named at their line of code where Storm captured it: for the first statement, when the time before it reached a millisecond; for the last, when the advice log records repeats, as in development. Otherwise they are named by what they did, such as `the UPDATE of Order`.

</details>

## A Missing Index {#missing-index}

```text
no index on customer starts with the column last_name, so the database reads the table in full to find the matching rows.
index: customer(last_name) finds them directly when the condition selects few of them.
```

**What it means.** A slow statement filters, joins, groups or sorts a table by columns that no index of the table starts with. The database reads the whole table to find the rows, or sorts all of them before it returns the first.

**How to fix it.** Check the statement's plan, then create the index the advice names. When finding the rows and reading them in the needed order call for different indexes, the advice names both, and says when each one wins:

```text
no index on owner starts with the column source, so the database reads the table in full and sorts the matching rows into groups.
index: owner(source, city, last_name) finds the matching rows and reads them already grouped, without a temporary table or sort
covering: with first_name, address appended it reads the index alone, since it then holds every column the select reads
or: owner(source) finds them directly when the condition selects few of them; source is an ENUM, with few distinct values, so an index on it alone is often not chosen.
```

How to choose between them:

- **A grouped read.** The equality columns followed by the group columns find the rows and read them already grouped, without a sort. That helps even when the condition selects many rows. An index on the equality columns alone helps only when it selects few.
- **A range and a limit sorted by another column.** The equality columns followed by the range column find the matching rows and then sort them: best when the range selects few. Followed by the sort column instead, they read in order, check the range on each row and stop at the limit: best when the range selects many.
- **A column with few distinct values.** An equality on an `ENUM`, `BOOLEAN` or `BIT` column rarely selects few rows, so the database often passes over an index on it alone. The advice says so and names the index that reads in order first. A rare value can still be selective.
- **A covering index.** The `covering:` line names the columns to append so the database reads the index alone, without the table. The index without them carries most of the gain; appending them makes the key wider. Weigh the two.

When an index already serves the filter but not the order, the advice names the index that continues it, as an extension of the existing one, such as `owner(source, city, last_name), extending OWNER_SOURCE`.

**When to leave it.** A small table is read in full faster than through an index, and every index costs each write. Without row counts, Storm cannot judge in advance whether an index pays off, which is why it waits for a slow execution. Check the plan before you add the index.

**When Storm reports it.** Category: performance. Reported by the slow statement log, and right after a [repeat](#repeated-select) whose executions took 10 ms or more each: no single execution of such a repeat needs to be slow, while together they are the cost to look at. Needs the [loaded schema](#loading-the-schema).

<details>
<summary>How Storm decides</summary>

Storm records which columns a statement filters, joins, groups and sorts by, where it builds the condition: a `where` on a field, the foreign key a [semi-join or anti-join](queries.md#filtering-by-related-rows) correlates on, the foreign key a to-many join reads the joined table by, a `groupBy` on a field, the order of a result it limits, and the columns a select built from the metamodel reads. When an execution is slow, it checks those columns against the indexes the loaded schema lists. A table none of whose indexes starts with any of them is read in full. The advice names an index that would serve the statement: the columns compared for equality first, then a column compared by range, or the column a limited result is sorted by.

- **One index where one serves.** Where the group columns are the equality columns, or the range and the order are on one column, a single index both finds the rows and reads them in order, and the advice names that one.
- **Order of the columns.** Group columns count in any order; sort columns in theirs. An order in mixed directions is left alone, since an index serves it only when declared in the same directions.
- **Nothing to sort or group.** An equality on every column of a unique index selects at most one row, and a group that holds the primary key puts every row in a group of its own, so neither is advised.
- **What the select reads.** Where the select names every column it reads, through the metamodel, the `covering:` line says the index then holds every column the select reads. Where part of the select is text Storm cannot see into, it says the index reads alone if the select reads no other columns.
- **The key limit.** A database that limits the declared size of an index key, through `SqlDialect.maxIndexKeyBytes()`, gets an index it can create. The advice sizes the key from the columns' declared sizes and character set, leaves out the select's columns if appending them would exceed the limit, and says so where the index itself would, so that a column must be shortened or left out.
- **Checked against real databases.** A conformance test per dialect creates the index the advice names and reads the database's own plan to confirm it is used.

Storm stays quiet where it cannot tell:

- **A part of the statement it does not build**, such as a raw template fragment, names no columns to check.
- **Any index counts for its column.** A full-text or other special index on a column is taken to serve it. An index on an expression, such as `lower(email)`, serves only a statement that compares the same expression, never the bare column.
- **A table the loaded schema does not hold**, such as one created after the load, is not advised.
- **One served column is enough.** A statement whose condition an index serves on any one of its columns can avoid a full read, even if that index is not the best one.

</details>

## An Unindexed Foreign Key Behind a Delete {#unindexed-foreign-key}

```text
every row deleted from owner is checked against the foreign key pet(owner_id), and no index on pet starts with the column owner_id, so the database reads pet in full for each deleted row.
fix: an index on pet(owner_id) serves the check.
```

**What it means.** When you delete a row, the database checks every foreign key that references its table: it looks for rows that still point at the deleted row. With `ON DELETE CASCADE` or `SET NULL` it also changes them. Without an index that starts with the referencing column, each check reads the referencing table in full. Deleting one owner then reads all of `pet`, even though the statement never names `pet`.

**How to fix it.** Create the index the advice names on the referencing table.

**When Storm reports it.** Category: performance. Reported by the slow statement log, and right after a [repeat](#repeated-write) whose executions took 10 ms or more each. Needs the [loaded schema](#loading-the-schema). MySQL, MariaDB and H2 create an index for every foreign key themselves, so the advice does not appear there. PostgreSQL, Oracle, SQL Server and SQLite leave the index to you.

<details>
<summary>How Storm decides</summary>

When a delete is slow, Storm looks up the foreign keys that the loaded schema lists as referencing the table, and advises on each one that no index of its own table starts with. A compound foreign key counts as served when an index starts with any of its columns.

Storm stays quiet where it cannot tell:

- **A table the loaded schema does not hold**, or whose foreign keys or indexes it could not read.
- **A joined inheritance hierarchy**, which deletes from several tables that its type does not name one by one.
- **A delete written as SQL text**, which names no entity, so the table it deletes from is not resolved.

</details>

## A Search Field No Index Serves {#unindexed-search}

```text
no index on owner serves wordStart(last_name), so the database reads owner in full to match it.
fix: a trigram index on LOWER(last_name) serves it.
```

**What it means.** A slow `search` or `suggest` matched a column that no index serves in the way it matches, so the database read the table in full.

**How to fix it.** Create the index the advice names. Which index serves a match depends on its [form](search.md#match-forms):

- **`prefix` or `exact`** compare the bare column, which an ordinary index on the column serves.
- **`anywhere` or `wordStart`** compare `LOWER(column)` inside the value, which only an index for substring matching serves, such as a trigram index on PostgreSQL. A database without one gets `this database has no index for matching inside a value`, with the alternative: a table of the column's [word suffixes](search.md#large-tables-without-a-substring-index), matched with `prefix`, which an ordinary index serves.
- **A column wrapped in a function** compares an expression, which an index on that expression serves, or a column that stores the value, declared as a query column, with an ordinary index.

See [Indexing a Search](search.md#indexing-a-search) for the index per database.

**When Storm reports it.** Category: performance. Reported by the slow statement log. Needs the [loaded schema](#loading-the-schema). A read of a small table is never slow, so a search over one is never advised. A table with any index that starts with an expression may already serve a lowered match, so Storm stays quiet for it.

## An Entity Past the Design Budget {#entity-budget}

```text
every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns.
fix: a Ref for Clinic.organization removes 4 joins and 52 columns.
```

**What it means.** A foreign key declared as an entity is joined into every read. That entity brings its own entity fields, and so on down the graph. The [design budget](entity-design.md#the-budget) bounds what one read may add beyond the entity's own columns: 10 joins and 96 columns by default. Ordinary schemas stay well within it. An entity past it has a graph that grew, usually through entities nobody was editing.

**How to fix it.** Turn the field the advice names into a `Ref`. Storm then reads only its key, and you load it where you need it. A `Ref` keeps the relationship: you can still filter, join and fetch through it, and resolve it in the queries that need it with `select().fetch(...)`.

The advice picks the field by the rules of [Where to Draw the Line](entity-design.md#where-to-draw-the-line). It prefers the deepest field that is enough on its own, since a cut near the leaves narrows every entity above it, and otherwise the widest fields first, one per `and:` line. It never names a foreign key that is part of the primary key, nor an entity without foreign keys of its own, such as a lookup table. Where every field is of that kind, it suggests reading through a projection instead.

The advice log reports it with the graph:

```
SQL advice (design Visit)
	graph: Visit(Pet(Owner(City), PetType, Clinic(Address, Organization(Country))))  12 joins, 140 columns beyond its own
	advice: every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns.
		fix: a Ref for Clinic.organization removes 4 joins and 52 columns.
		see: https://orm.st/docs/advice#entity-budget
```

**When to leave it.** If the path is read constantly and needs every join, keep it and put `@AcceptAdvice("entity-budget")` on the entity. If the budget does not suit the model as a whole, [set your own budget](entity-design.md#the-budget).

**When Storm reports it.** Category: design. Reported by the advice log in development, once per entity, at startup or when the entity is first used. A production deployment never runs this check. Logger: `st.orm.sql.advice.entity-budget`.

## A Slow Read of a Wide Entity {#wide-entity-read}

```text
every read of Visit joins 6 tables and selects 58 columns beyond its own.
fix: if the width is what makes the read slow, a Ref for Clinic.organization removes 3 joins and 31 columns.
```

**What it means.** A read was slow, and the entity it reads reaches at least half the design budget: 5 joins or 48 columns beyond its own by default. The width may be the cause, or the `WHERE` clause or a missing index may be. The advice says what a `Ref` would save, not that the width is the cause.

**How to fix it.** Compare the statement's plan with and without the joins before cutting. If the joins are the cost, turn the field the advice names into a `Ref`, as for [an entity past the budget](#entity-budget). The field is chosen to bring the read below half the budget.

**When Storm reports it.** Category: design. Reported by the slow statement log only: whether a read within the budget is too wide shows only in real use.
