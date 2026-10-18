import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Statement Advice

Storm gives advice where a statement's own shape says it reads more than it needs to or returns rows in no stated order, and where the entity model makes reads cost more than the application needs. Each piece of advice has a category:

- **Correctness.** A [limit or offset without an order](#unordered-limit) returns an arbitrary part of the result, however fast it runs.
- **Performance.** A deep offset, a `distinct` over a join that repeats rows, or a search of short words alone costs more as the data grows, and on a small table costs nothing worth changing.
- **Design.** Whether a foreign key is an inline entity or a `Ref` decides what every read of the entity joins. The schema is what it is; this choice is yours, and [Entity Design](entity-design.md) gives the reasoning.

The advice reaches you two ways.

**The slow statement log.** A line in the [slow statement log](sql-logging.md#the-slow-statement-log) ends with every piece of advice its statement carries, every time it is slow.

**The performance log.** A [performance log](sql-logging.md#the-performance-log) summary ends with advice on what its unit of work repeated, which no single statement shows: references fetched one at a time, and the same single-row read or write run again and again.

**The advice log.** The `st.orm.sql.advice` logger reports advice whether the statement is fast or slow, with the statement, its call site and its shape:

- **Correctness is on by default.** An application that logs at `INFO` sees it without asking, in production too: rows in no stated order are wrong wherever they run.
- **Design and performance are on in development.** Spring Boot with DevTools on the classpath, Ktor in development mode, and every `@StormTest` report all three categories, so the developer and the agent see what to change while writing the code, and a production deployment never runs the design check. Design advice on an entity is reported once per entity, at startup or when the entity is first used.
- **Named categories win.** `storm.advice.categories: correctness, design, performance`, in Spring Boot, Ktor or as a system property, sets them in any environment, development included.
- **Accepted advice stays quiet.** Where an entity or a query is as intended, [accept the advice](#accepting-advice) there with `@AcceptAdvice`, and the advice log stops reporting it there.
- **A deliberate choice is advised only when slow.** A page that reports its total is a choice of `page` over `slice`, so its [count](#page-count) is advised in the slow statement log alone.

The level of `st.orm.sql.advice` decides how often a piece of advice is reported:

| Level | How often |
|-------|-----------|
| `INFO` | once per piece of advice, at the first statement that drew it |
| `DEBUG` | once per statement shape |
| `TRACE` | at every execution |

At `INFO` the log stays quiet. At `DEBUG` it lists every statement shape to change, a list to work through while developing or in a test environment. The offset of a page is part of its statement, so each page of the same query is a shape of its own. See [The Advice Log](sql-logging.md#the-advice-log).

```
SQL slow (SELECT Owner): 412 ms in database, 12 rows, OwnerService.kt:42
	SELECT DISTINCT o.id, o.first_name, o.last_name, ...
	FROM owner o
	INNER JOIN pet p ON p.owner_id = o.id
	WHERE p.name = ?
	shape: 9b34305d1a7c4e02
	advice: DISTINCT removes the rows the join to Pet repeats, one per related row; semiJoin(Pet) filters by it without repeating rows, so the query needs no DISTINCT. See https://orm.st/docs/advice#distinct-over-join
```

### Accepting advice

Advice is a suggestion. Where the code is as intended, accept the advice with `@AcceptAdvice`, and the advice log no longer reports it there, across restarts too:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// The admin export reads every order with its full graph.
@AcceptAdvice("entity-budget")
data class Order(@PK val id: Int = 0, ...) : Entity<Int>

interface OrderRepository : EntityRepository<Order, Int> {

    // The admin screen shows the total.
    @AcceptAdvice("page-count")
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

    // The admin screen shows the total.
    @AcceptAdvice("page-count")
    default Page<Order> adminOrders(Pageable pageable) {
        return select().page(pageable);
    }
}
```

</TabItem>
</Tabs>

- **Name the advice by its anchor**, the part of its link after `#`, such as `page-count`. Without a name, every piece of advice is accepted there. A name that matches no advice is ignored.
- **On an entity**, it accepts advice on the statements that read or write the entity, and the entity's own design advice.
- **On a method or class**, it accepts advice on the statements issued while the method runs: a service method, a repository's own method, or a repository interface, whose built-in methods such as `page(...)` count too. A lambda counts as part of the method that declares it, and so does the block of a `transaction { }`. A query a service builds from `select()` executes in the service, so accept its advice on the service method.
- **Decided once per statement shape.** The first execution of a shape that carries the advice settles whether it is accepted, so accepting costs one look at the call stack per shape, and a statement without advice pays nothing.
- **The slow statement log and the performance log show accepted advice still.** They show everything a statement carries; a slow statement is reason to look at the advice again.

To accept a piece of advice everywhere, set its logger to `OFF`, such as `st.orm.sql.advice.deep-offset: OFF`.

### Design: known in advance, and shown by real use

Some of the design is knowable from the model alone. An entity whose read [exceeds the design budget](#entity-budget) does so however the application uses it: its graph has run away, and every read pays for it. In development, Storm checks every entity against the budget, once, and the advice log reports the ones past it.

The rest only shows in real use. Whether a `Clinic` on every `Visit` is worth its width depends on how the application reads visits, which no check of the model can know. Real use answers it both ways: a read that is [slow and wide](#wide-entity-read) says what a `Ref` would save, and a `Ref` that every unit of work [fetches anyway](#repeated-fetch) says it should be an inline entity. A wide graph that reads fast is never advised, and an entity within the budget is never advised in advance.

Each piece of advice links to its section on this page. The links stay valid for as long as Storm gives the advice, so a log line written today still leads here after the rest of the documentation has moved on.

Storm does not know which indexes the database has. It advises only on what the statement itself proves, whatever indexes exist, so advice never guesses at a missing index, and a slow statement without advice is slow for a reason the statement does not show: its plan, its parameters, or the load on the database. Advice is decided when Storm builds the statement, so it costs an execution nothing while the advice log is off.

## A search of short words alone {#unindexed-search}

> every search term is shorter than the 3 characters a substring index serves, so the search reads every row

Category: performance. Logger: `st.orm.sql.advice.unindexed-search`.

A substring index, such as PostgreSQL's trigram index, serves words of a minimum length, three characters for a trigram. `search` tests a shorter word only on the rows its longer words found, but an input of short words alone, such as `de`, leaves it nothing to start from, so the database reads every row of each searched table.

A search box that queries as the user types meets such input on almost every keystroke. Use `suggest` there: it ignores the words the index cannot serve and matches no rows for an input without a longer one, so every statement it renders is served by the index. The search page the box leads to keeps `search`. See [Suggestions as the User Types](search.md#suggestions-as-the-user-types) and [Indexing a Search](search.md#indexing-a-search).

## DISTINCT over a join that repeats rows {#distinct-over-join}

> DISTINCT removes the rows the join to Pet repeats, one per related row; semiJoin(Pet) filters by it without repeating rows

Category: performance. Logger: `st.orm.sql.advice.distinct-over-join`.

An inner join to the many side of a relation returns a row per related row: an owner with three pets that match comes back three times, and `distinct` removes the copies again. The database builds every copy first, then sorts or hashes the whole result to find them.

When the joined table only filters the query, a semi-join says so. It keeps each row that has a matching related row, once, with no copies to remove:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = orm.entity<Owner>()
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

The advice is given for a `distinct` select of the query's own table over an inner join. A left join keeps the rows without a related row, which a semi-join would drop, so it gets no advice. A query that orders or selects by the joined table's columns keeps its join, since a semi-joined table adds no columns. See [Filtering by Related Rows](queries.md#filtering-by-related-rows).

## The count behind a page {#page-count}

> page counts every matching row to report the total; slice reads the same page without the count

Category: performance, reported by the slow statement log only: choosing `page` over `slice` is a decision about the screen, not a mistake in the statement, so the count is advised once it is slow.

`page` reports the total number of matching rows, and finding it takes a count query that visits every one of them, however small the page. On a large or expensive result the count costs more than the page itself.

A list that shows "next" rather than "page 3 of 120" needs no total. `slice` reads the same page with one row beyond it, which says whether a next page exists, and runs no count. Where the total is needed but changes slowly, `page(pageable, totalCount)` takes a total counted once and kept. A page that is not full already knows its total, so its count never runs. See [Slices](pagination-and-scrolling.md#slices).

## A limit or offset without an order {#unordered-limit}

> the statement takes part of the result without an ORDER BY, so which rows it returns is up to the database

Category: correctness. Logger: `st.orm.sql.advice.unordered-limit`.

Without an ORDER BY, a database returns rows in whatever order is cheapest for it at that moment, and the order can change with the plan, with concurrent writes, or from one execution to the next. A `limit` or `offset` then takes an arbitrary part of the result: the first page and the second can overlap or miss rows between them, which no test on a small, quiet table shows. Because it holds however fast the statement runs, the advice log reports this advice too.

Order by a unique key, or by sort fields that end in one, so every row has one place in the order: `orderBy(Owner_.id)`, or `Pageable.ofSize(20).sortBy(Owner_.lastName).sortBy(Owner_.id)`. `scroll` always orders by its key, so it never gets this advice, and neither does a `limit(1)`, which asks for any matching row.

## A deep offset {#deep-offset}

> the database reads and discards the 1000 rows the offset skips before it returns the first

Category: performance. Logger: `st.orm.sql.advice.deep-offset`.

An offset tells the database to skip rows, and it skips them by reading them: page 100 of ten rows reads a thousand rows to return ten, and each page further reads ten more. Storm gives this advice from an offset of a thousand rows.

`scroll` continues from the last row read instead: it compares the sort fields and the key with that row's values, which an index on them serves, so every window costs what the first one does. It needs a unique key to continue from; a query without one reads deep pages through an offset, and the advice then says what each of them costs. See [Scrolling](pagination-and-scrolling.md#scrolling).

## An entity past the design budget {#entity-budget}

> every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns; a Ref for Clinic.organization removes 4 joins and 52 columns

Category: design. Logger: `st.orm.sql.advice.entity-budget`. Reported once per entity, at startup where the integration checks the model, or when the entity is first used:

```
SQL advice (design Visit)
	graph: Visit(Pet(Owner(City), PetType, Clinic(Address, Organization(Country))))  12 joins, 140 columns beyond its own
	advice: every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns; a Ref for Clinic.organization removes 4 joins and 52 columns. See https://orm.st/docs/advice#entity-budget
```

An entity component joins its table into every read and brings that table's whole select list, along with every entity it declares in turn. The [design budget](entity-design.md#the-budget) bounds what a read adds beyond the entity's own columns: 10 joins and 96 columns, unless the application [sets its own](entity-design.md#the-budget). The advice states the budget in effect. The default is calibrated so ordinary schemas never reach it; an entity past it has a graph that grew, usually through entities nobody was editing.

The advice names the fields a `Ref` would cut to bring the read back within the budget, by the rules of [Where to Draw the Line](entity-design.md#where-to-draw-the-line): the deepest field that is enough on its own, since a cut near the leaves narrows every entity above it at once, and otherwise the widest fields first. It never names an identifying foreign key, which is composition, nor an entity without foreign keys of its own, such as a lookup table, which can never grow. A `Ref` keeps the relationship: filter, join and fetch through it as before, and resolve it in the queries that need it with `select().fetch(...)`.

Where you know the path is read constantly, an entity past the budget can stay as it is: put `@AcceptAdvice("entity-budget")` on it. Where the budget does not suit the model as a whole, [set the budget](entity-design.md#the-budget) instead.

## A slow read of a wide entity {#wide-entity-read}

> every read of Visit joins 6 tables and selects 58 columns beyond its own; where that width is what makes the read slow, a Ref for Clinic.organization removes 3 joins and 31 columns

Category: design, reported by the slow statement log only: whether a read within the budget is too wide shows only in real use, so the advice comes with a read that turned out slow.

A read of an entity whose graph reaches half the design budget, 5 joins or 48 columns beyond its own by default, carries this advice to the slow statement log. A read can be slow for other reasons, such as its `WHERE` clause or a missing index, so the advice says what the width costs and what a `Ref` would save, not that the width is the cause. Compare the statement's plan with and without the joins before cutting. The field it names is chosen as for [an entity past the budget](#entity-budget), aiming below half the budget.

## References fetched one at a time {#repeated-fetch}

> 24 statements each fetched one City; an inline foreign key joins it into every read of the rows, or a query that fetches the references, such as select().fetch(...), resolves them in the statement that reads them

Category: design, reported by the [performance log](sql-logging.md#the-performance-log) summary of the unit of work: a `Ref` the application fetches on every read is evidence from real use that it should be an inline entity.

Each `Ref.fetch()` that the transaction's entity cache cannot serve is a statement of its own, so a loop over rows that fetches a reference from each runs one statement per row: the N+1 pattern. The performance log counts these as fetches, and advises once a unit of work fetched ten or more of one type.

Where every reader needs the referenced row, make the foreign key inline: `@FK val pet: Pet` rather than `Ref<Pet>` joins the pet into every read of a visit, repository methods such as `findById` included. See [Entity Design](entity-design.md).

Where only some readers need it, keep the `Ref` and read the rows with a query that fetches it: `select().fetch(Visit_.pet)` adds the pets to the statement that reads the visits. A repository method such as `findById` or `findAll` takes no `fetch`, so write that read as a `select()` that does. See [Resolving a Ref as Part of the Query](refs.md#resolving-a-ref-as-part-of-the-query).

## The same row lookup, again and again {#repeated-select}

> the same SELECT of Owner ran 12 times, one row each

Category: performance, reported by the [performance log](sql-logging.md#the-performance-log) summary of the unit of work.

A unit of work that runs the same SELECT ten or more times, each returning at most one row, is looking rows up one at a time, typically by key inside a loop. Every lookup is a round trip to the database.

Read them in one query: `findAllById(ids)`, or a `where` with `IN` over the keys, returns all of them in one statement. A read of several rows each time, such as scrolling window after window, gets no advice.

## The same single-row write, again and again {#repeated-write}

> the same INSERT of Owner ran 10 times, one row each

Category: performance, reported by the [performance log](sql-logging.md#the-performance-log) summary of the unit of work.

A unit of work that runs the same INSERT, UPDATE or DELETE ten or more times, one row each, sends every row in a round trip of its own.

The repository overloads that take a list, such as `insert(owners)`, send the rows as one JDBC batch. A batch already counts as one execution, so batched writes never get this advice.
