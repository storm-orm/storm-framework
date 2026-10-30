import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Statement Advice

Statement advice spots the common pitfalls of data access and shows them where they occur, based on what the application actually does: the statements it runs and how long they take, the entities it reads, what a unit of work repeats, and the indexes the database reports. Advice is only worth reading when it is worth acting on, and only worth having when it does not slow the application down. Storm decides advice once per statement shape, when it builds the statement, and makes the checks that need evidence only for an execution that was already slow, so advice comes at no measurable cost. The advice it gives is:

- **Relevant.** Advice rests on evidence Storm has: the shape of a statement, the entity model, the indexes the database reports, or what a unit of work did. Where the cost depends on data Storm cannot see, such as how many rows a table holds, the advice waits for a slow execution as evidence.
- **Not repeated.** The advice log reports a piece of advice once by default, not at every execution, and design advice once per entity. Advice you have accepted stays quiet where you accepted it.
- **Informative.** Every line says what the statement costs, what to use instead, and links to a section on this page that explains the trade-off.
- **Quiet by default.** Only correctness advice is reported everywhere. The advice log reports design and performance advice while developing, where the code is still changing; in production it reaches you only on the line of a slow statement, once you set a slow threshold, unless you ask for more.
- **Configurable.** The categories, how often advice is reported, the design budget and the slow thresholds are settings, and each piece of advice has a logger of its own to silence it in the advice log. The defaults suit most applications without changing any of them.

Storm gives advice where a statement's own shape says it reads more than it needs to or returns rows in no stated order, and where the entity model makes reads cost more than the application needs. Each piece of advice has a category:

- **Correctness.** A [limit or offset without an order](#unordered-limit) returns an arbitrary part of the result, however fast it runs.
- **Performance.** A deep offset, a `distinct` over a join that repeats rows, or a search of short words alone costs more as the data grows, and on a small table costs nothing worth changing.
- **Design.** Whether a foreign key is an inline entity or a `Ref` decides what every read of the entity joins. The schema is what it is; this choice is yours, and [Entity Design](entity-design.md) gives the reasoning.

The advice reaches you three ways.

**The slow statement log.** A line in the [slow statement log](sql-logging.md#the-slow-statement-log) ends with every piece of advice its statement carries, every time it is slow.

**The performance log.** A [performance log](sql-logging.md#the-performance-log) summary ends with advice on what its unit of work repeated across its transactions, which no single statement shows: references fetched one at a time, and the same single-row read or write run again and again, each with the time it cost, the transactions it spanned and the call site where recorded, heaviest first.

**The advice log.** The `st.orm.sql.advice` logger reports advice whether the statement is fast or slow, with the statement, its call site, its shape and what the execution cost:

- **Correctness is on by default.** An application that logs at `INFO` sees it without asking, in production too: rows in no stated order are wrong wherever they run.
- **Design and performance are on in development.** Spring Boot with DevTools on the classpath, Ktor in development mode, and every `@StormTest` report all three categories, so the developer and the agent see what to change while writing the code, and a production deployment never runs the design check. Design advice on an entity is reported once per entity, at startup or when the entity is first used.
- **Named categories win.** `storm.advice.categories: correctness, design, performance`, in Spring Boot, Ktor or as a system property, sets them in any environment, development included.
- **Accepted advice stays quiet.** Where an entity or a query is as intended, [accept the advice](#accepting-advice) there with `@AcceptAdvice`, and the advice log stops reporting it there.
- **A deliberate choice is advised only when slow.** A page that reports its total is a choice of `page` over `slice`, so its [count](#page-count) is advised in the slow statement log alone.
- **Advice that needs evidence is advised only when slow.** Whether an index is worth its cost depends on how many rows a table holds and how few a condition selects, which only a slow execution shows, so a [missing index](#missing-index) and an [unindexed foreign key](#unindexed-foreign-key) are advised in the slow statement log, and a missing index also after a repeat whose executions each cost more than their round trip.
- **A transaction is measured against a threshold of its own.** A [transaction that holds its connection idle](#idle-transaction) for more than a second beyond what its statements spent on it is reported by the slow statement log, in production too; `storm.sql_log.slow.transaction` retunes the second.
- **A transaction reports what it repeated when it completes.** Where the advice log reports performance and design advice, every transaction counts the statements on its connection, and the [same row lookup](#repeated-select), the [same single-row write](#repeated-write) or [references fetched one at a time](#repeated-fetch) ten or more times is reported as one block under the call site that began the transaction, with the time the executions spent and the call site of the statement. The block is counted per call site: once per piece of advice there at `INFO`, once per repeat there at `DEBUG`, so what one transaction repeats is reported whatever another reported before.

The level of `st.orm.sql.advice` decides how often a piece of advice is reported:

| Level | How often |
|-------|-----------|
| `INFO` | once per piece of advice, at the first statement that drew it |
| `DEBUG` | once per statement shape |
| `TRACE` | at every execution |

At `INFO` the log stays quiet. At `DEBUG` it lists every statement shape to change, a list to work through while developing or in a test environment. The offset of a page is part of its statement, so each page of the same query is a shape of its own. See [The Advice Log](sql-logging.md#the-advice-log).

Each piece of advice is derived in the scope that sees what it is about, and reported where that scope ends:

| Advice | Derived per | Reported |
|--------|-------------|----------|
| [`unordered-limit`](#unordered-limit), [`deep-offset`](#deep-offset), [`distinct-over-join`](#distinct-over-join), [`unindexed-search`](#unindexed-search), [`in-list-shapes`](#in-list-shapes) | statement shape, for the life of the application | the advice log, once per shape, with the call site and the cost of the execution; every slow line of the shape |
| [`repeated-select`](#repeated-select), [`repeated-write`](#repeated-write), [`repeated-fetch`](#repeated-fetch) within a transaction | transaction | the advice log when the transaction completes, one block under its call site |
| the same, across transactions, and [`writes-without-transaction`](#writes-without-transaction) | the unit of work a performance log scope covers, less the executions a transaction advised on | the performance log summary, which says that a batch commits the rows together |
| [`idle-transaction`](#idle-transaction) | transaction | the slow statement log when the transaction completes |
| [`page-count`](#page-count), [`missing-index`](#missing-index), [`unindexed-foreign-key`](#unindexed-foreign-key), [`wide-entity-read`](#wide-entity-read) | statement shape, decided for a slow execution | the slow statement log |
| [`entity-budget`](#entity-budget) | entity, at startup or first use | the advice log, once per entity |

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

The rest only shows in real use. Whether a `Clinic` on every `Visit` is worth its width depends on how the application reads visits, which no check of the model can tell. Real use answers it both ways: a read that is [slow and wide](#wide-entity-read) says what a `Ref` would save, and a `Ref` that every unit of work [fetches anyway](#repeated-fetch) says it should be an inline entity. A wide graph that reads fast is never advised, and an entity within the budget is never advised in advance.

Each piece of advice links to its section on this page. The links stay valid for as long as Storm gives the advice, so a log line written today still leads here after the rest of the documentation has moved on.

Most advice rests on the statement itself, whatever indexes exist. Advice on a [missing index](#missing-index) or an [unindexed foreign key](#unindexed-foreign-key) rests on the indexes and foreign keys the database reports, read when the schema is [loaded](#loading-the-schema). A slow statement without advice is slow for a reason neither shows: its plan, its parameters, or the load on the database. Advice is decided when Storm builds the statement, and the index check when an execution is slow, so it costs an execution nothing while the advice log is off.

### Loading the schema

Loading the schema is optional. Storm works the same without it; what it adds is knowledge of the database that Storm uses for advice and optimization, such as the indexes behind the [missing-index advice](#missing-index) and the foreign keys behind the [unindexed-foreign-key advice](#unindexed-foreign-key). The schema is read once and kept for the data source, and every template on that data source shares it.

- **Schema validation loads it.** Spring Boot and Ktor validate the schema at startup by default, and validation loads the schema as it reads it, so most applications have it loaded without doing anything.
- **Load without validating.** `storm.validation.schema-mode: load` in Spring Boot, or `schemaValidation = "load"` in Ktor, loads the schema at startup without validating it. Elsewhere, call `orm.loadSchema()`.
- **Each load reads anew.** A load or validation replaces what an earlier one kept for the same schemas. After a migration that changed the schema while the application runs, such as one that added an index, call `loadSchema()` again to make the change known.
- **Nothing is read while statements run.** The loaded schema is a snapshot; a table created after the load is not in it, and a table that is not in the loaded schema gets no advice.

## A search of short words alone {#unindexed-search}

```text
every search term is shorter than the 3 characters a substring index serves, so the search reads every row.
fix: for a search box that queries as the user types, use suggest, which returns no rows for such input.
```

Category: performance. Logger: `st.orm.sql.advice.unindexed-search`.

A substring index, such as PostgreSQL's trigram index, serves words of a minimum length, three characters for a trigram. `search` tests a shorter word only on the rows its longer words found, but an input of short words alone, such as `de`, leaves it nothing to start from, so the database reads every row of each searched table.

A search box that queries as the user types meets such input on almost every keystroke. Use `suggest` there: it matches every word of two characters or more in the form the index serves, at the start of a word where a substring index cannot serve it anywhere and as a prefix of the column where no index serves a substring, and matches no rows for an input without such a word, so every statement it renders is served by an index. The search page the box leads to keeps `search`. See [Suggestions as the User Types](search.md#suggestions-as-the-user-types) and [Indexing a Search](search.md#indexing-a-search).

## DISTINCT over a join that repeats rows {#distinct-over-join}

```text
the join to Pet repeats each row once per related row, and DISTINCT removes the copies afterwards.
fix: semiJoin(Pet) filters by Pet without repeating rows, so the query needs no DISTINCT.
```

Category: performance. Logger: `st.orm.sql.advice.distinct-over-join`.

An inner join to the many side of a relation returns a row per related row: an owner with three pets that match comes back three times, and `distinct` removes the copies again. The database builds every copy first, then sorts or hashes the whole result to find them.

When the joined table only filters the query, a semi-join says so. It keeps each row that has a matching related row, once, with no copies to remove:

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

The advice is given for a `distinct` select of the query's own table over an inner join. A left join keeps the rows without a related row, which a semi-join would drop, so it gets no advice. A query that orders or selects by the joined table's columns keeps its join, since a semi-joined table adds no columns. See [Filtering by Related Rows](queries.md#filtering-by-related-rows).

## A missing index {#missing-index}

```text
no index on customer starts with last_name, so the database reads the table in full to find the matching rows.
index: customer(last_name) finds them directly where the condition selects few of them.
```

Category: performance, reported by the slow statement log, and right after a [repeat](#repeated-select) whose executions took 10 ms or more each, only once the schema is [loaded](#loading-the-schema). No single execution of such a repeat need reach the slow threshold, while together they are the cost to look at.

Storm records which columns a statement filters, joins, groups and sorts by, where it builds the condition: a `where` on a field, the foreign key a [semi-join or anti-join](queries.md#filtering-by-related-rows) correlates on, the foreign key a to-many join reads the joined table by, a `groupBy` on a field, the order of a result it limits, and the columns a select built from the metamodel reads. When an execution is slow, it checks those columns against the indexes the database reported when the schema was loaded. A table none of whose indexes starts with any of them is read in full, and the advice names an index that would serve the statement: the columns compared for equality first, then a column compared by range, or the column a limited result is sorted by. For a limited result sorted by a column no index starts with, the database sorts every row before it returns the first ones; an index on the column reads the rows in order and stops at the limit.

Where finding the matching rows and reading them in the order the statement needs differ, no single index is right for every table, so the advice names both, with when each wins:

```text
no index on owner starts with source, so the database reads the table in full and sorts the matching rows into groups.
index: owner(source, city, last_name) finds the matching rows and reads them already grouped, without a temporary table or sort
covering: with first_name, address appended it reads the index alone, since it then holds every column the select reads
or: owner(source) finds them directly where the condition selects few of them; source is an ENUM, with few distinct values, so an index on it alone is often not chosen.
```

- **A grouped read.** The equality columns followed by the group columns find the rows and read them already grouped, without a temporary table or sort, which helps even where the condition selects many rows. An index on the equality columns alone helps only where it selects few.
- **A range and a limited order on another column.** The equality columns followed by the range column find the matching rows and sort them, which is best where the range selects few; followed by the sort column instead, they read in order, check the range on each row and stop at the limit, which is best where it selects many.
- **A column with few distinct values.** An equality on an `ENUM`, `BOOLEAN` or `BIT` column rarely selects few rows, so an index on it alone is often passed over; the advice says so and names the index that reads in order first. A rare value can still be selective.
- **One index where one serves.** Where the group columns are the equality columns, or the range and the order are on one column, a single index both finds the rows and reads them in order, and the advice names that one.
- **An index that stops too early.** A filter an index serves can still leave the rows to sort: an index on `owner(source)` finds the rows of a grouped read but not in group order. The advice then names the index that continues it, `owner(source, city, last_name)`, as an extension of the index that holds exactly the filter's columns where one does. Group columns count in any order; sort columns in theirs.
- **Nothing to sort or group.** An equality on every column of a unique index selects at most one row, and a group that holds the primary key puts every row in a group of its own, so neither is advised.
- **What the select reads.** The grouping index alone carries most of the gain; holding the columns the select reads too lets the database read the index alone, for a wider key. The advice names those columns apart, `with first_name, address appended`, so the two can be weighed. Where the select names every column it reads, through the metamodel, with functions, keywords, literals and aliases around them, the advice says the index then holds every column the select reads; where part of the select is text Storm cannot see into, it says the index reads alone where the select reads no other columns. An order in mixed directions is left alone, since an index serves it only when declared in the same directions.
- **The key limit.** A database that checks the declared sizes of an index key's columns against a limit, through `SqlDialect.maxIndexKeyBytes()`, gets an index it can create: the advice sizes the key from the columns' declared sizes and character set, leaves out the select's columns where appending them would pass the limit, saying so, and says where the index itself would pass it, so a column must be shortened or left out. A database that checks the values it stores rather than declared sizes sets no limit, and the advice names the columns as they are.

Each piece of this is checked against the databases themselves: a conformance test per dialect creates the index the advice names and reads the database's own plan to find it used.

Without row counts an index cannot be judged worth its cost in advance: a small table is read in full faster than through an index, and a condition on a column with few distinct values, such as a status, selects too many rows for one to help. A slow execution is the evidence that the read costs too much, so the advice waits for it. Check the statement's plan before adding the index.

Storm stays quiet where it cannot tell:

- **A part of the statement it does not build**, such as a raw template fragment, names no columns to check.
- **An index of any kind counts for its column.** A full-text or other special index on a column is taken to serve it, whether or not it serves the comparison. An index on an expression, such as `lower(email)`, serves only a statement that compares the same expression, never the bare column, so it counts for no column.
- **A table the loaded schema does not hold**, such as one created after the load, is not advised.
- **One served column is enough.** A statement whose condition an index serves on any one of its columns has a way to avoid reading the table in full, even when the index is not the best one.

## An unindexed foreign key behind a delete {#unindexed-foreign-key}

```text
every row deleted from owner is checked against the foreign key pet(owner_id), and no index on pet starts with owner_id, so the database reads pet in full for each deleted row.
fix: an index on pet(owner_id) serves the check.
```

Category: performance, reported by the slow statement log, and right after a [repeat](#repeated-select) whose executions took 10 ms or more each, only once the schema is [loaded](#loading-the-schema). No single execution of such a repeat need reach the slow threshold, while together they are the cost to look at.

Deleting a row checks every foreign key that references its table: the database looks for rows that still point at it, and with `ON DELETE CASCADE` or `SET NULL` it finds them to change them. Without an index that starts with the referencing column, each check reads the referencing table in full. Deleting an owner then reads all of `pet`, although the statement never names `pet`, so neither the statement nor the [missing-index advice](#missing-index) points at it.

When a delete is slow, Storm looks up the foreign keys the loaded schema lists as referencing the table it deletes from, and advises each one that no index of its own table starts with. A compound foreign key counts as served when an index starts with any of its columns. MySQL, MariaDB and H2 create an index for every foreign key themselves, so the loaded schema shows each one served and the advice does not appear there; PostgreSQL, Oracle, SQL Server and SQLite leave the index to the schema.

Storm stays quiet where it cannot tell:

- **A table the loaded schema does not hold**, or whose foreign keys or indexes it could not read, is not advised.
- **A joined inheritance hierarchy** deletes from several tables, which its type does not name one by one, so its deletes are not advised.
- **A delete written as SQL text** names no entity, so the table it deletes from is not resolved.

## A transaction that holds its connection idle {#idle-transaction}

```text
the transaction held its connection 2300 ms, 2180 ms of it idle after its last statement, with its row locks held for 2100 ms of that.
fix: move that work before the transaction or after it commits.
```

Category: performance, reported by the slow statement log only, against a threshold of its own: a transaction may hold its connection for one second beyond what its statements spent on it, and `storm.sql_log.slow.transaction` (Spring Boot `storm.sql-log.slow.transaction`, Ktor `sqlLogSlowTransaction`) retunes that, with `0` stopping the watch. The transaction is reported only where acting on it pays: its idle time is at least a quarter of the time it held the connection, or its row locks were held for a second or more of that idle time, which other transactions wait on whatever the share. Below that its statements are the cost, and the transaction is reported at `TRACE`, where a reader looking for it still finds it.

A transaction that calls another service, sends mail or renders a document between a write and its commit keeps its connection out of the pool for the whole of that work, and the row locks of its writes with it. Every statement in it is fast, so neither the statement log nor a slow statement line shows it; under load the pool runs dry and other transactions queue on the locks. Storm times both halves: when a transaction it runs or joins takes its connection and when it returns it, and what each statement spent on it. The difference is time the connection was held for nothing, and a transaction whose difference reaches the threshold is reported when it completes, with the call site that began it, how many statements it ran and whether they wrote. The row locks are held from the first write on, so the advice claims them for the idle time from that write to the completion: a transaction that reads, computes for a second and then writes held no locks during that second, and its line says the locks were held for the commit alone. See [A Transaction That Holds Its Connection](sql-logging.md#a-transaction-that-holds-its-connection).

While the advice log reports performance advice, as it does in development, the transaction's block in the advice log carries this advice too, after what the transaction repeated or alone, so a reader working through that log sees everything a transaction has to be advised on in one place; the slow statement log reports the transaction on its own line regardless, in production too.

The advice says where the time went: before the first statement, between the statements, or after the last until the commit, naming each part that holds a tenth of the time or more. The remedy follows the part that holds most of it.

- **At the edges**, before the first statement or after the last, the work can move out of the transaction: before it, where the work decides what to write, or after it commits, through an `onCommit` callback or a row the transaction writes and a worker acts on.
- **Between the statements**, the work uses what the reads returned and the writes may depend on it, so it cannot move before the transaction or after it. Where the writes need not see the reads under the same transaction, read and do the work first, then write in a short transaction of its own. A transaction that only reads is split the same way from the other side: read what the work needs inside it and do the work after it returns, which also fits a framework scope that always opens a transaction. That holds every row the work needs in memory at once, where the loop may have read and dropped them step by step. Where the reads need not see one state of the database and the statements are 10 ms or more apart on average, the advice also offers reading without the transaction, which holds a connection only while each statement runs; closer together, a connection borrowed per statement, with the validation and reset a pool may do on each borrow, costs about what the held connection did.

Between the statements, the advice names the longest gap from the statement before it, where the work began, to the one that ended it, and counts the gaps of 1 ms or more. The statement that ended it carries its call site; the one before it carries its own where the advice log records what transactions repeat, as in development, since naming it otherwise would walk the stack for every statement. With both, the reader has the range of code the work ran in. One gap is one step to look at; hundreds are a loop whose work per row adds up:

```text
the transaction held its connection 92379 ms, 34736 ms of it idle between statements, with its row locks held for 780 ms of that.
longest idle: 4210 ms, after SlotService.kt:88 -> SlotRepository.findOpenByClinic, before AppointmentService.kt:370 -> AppointmentRepository.insert, of 412 gaps of 1.0 ms or more
fix: where the writes need not see the reads of this transaction, do the reads and that work first, then write in a short transaction of its own.
```

Where the locks were held for a small part of the time, as 780 ms of 34736 ms here, the cost is the pooled connection rather than other transactions waiting on the rows, which weighs against restructuring the job. Where the work has to stay inside, such as a batch job that holds its transaction by design, accept the advice on the method that runs the transaction with `@AcceptAdvice("idle-transaction")`, and the transaction is not reported there.

What the measurement covers:

- **Storm's own transactions** are measured from the moment they take their connection, at their first statement, to the moment they return it.
- **A Spring-managed transaction** is measured from Storm's first statement in it to its completion, since Spring opened the connection before Storm saw the transaction; the line says `from its first statement`.
- **A joined block** is part of the transaction it joined, which is reported once, on the frame that owns the connection. A block that opens a transaction of its own, `REQUIRES_NEW`, is measured on its own.
- **The threshold is on the idle time**, not on the transaction's length: a transaction that spends its whole time in its statements is never reported, however long it runs.
- **The statements' time is the union of their intervals.** A statement run while another is open, such as one run while a stream is read, adds only the time past the other's close.
- **The locks are claimed from the first write on.** The idle time the line states for the row locks runs from the first write to the moment the connection is returned, the commit included, less what the statements spent in it.

## The count behind a page {#page-count}

```text
page counts every matching row to report the total.
fix: slice reads the same page without the count, and says whether a next page exists.
```

Category: performance, reported by the slow statement log only: choosing `page` over `slice` is a decision about the screen, not a mistake in the statement, so the count is advised once it is slow.

`page` reports the total number of matching rows, and finding it takes a count query that visits every one of them, however small the page. On a large or expensive result the count costs more than the page itself.

A list that shows "next" rather than "page 3 of 120" needs no total. `slice` reads the same page with one row beyond it, which says whether a next page exists, and runs no count. Where the total is needed but changes slowly, `page(pageable, totalCount)` takes a total counted once and kept. A page that is not full is its own total, so its count never runs. See [Slices](pagination-and-scrolling.md#slices).

## A limit or offset without an order {#unordered-limit}

```text
the statement takes part of the result without an ORDER BY, so the database picks the rows, which can change between executions, and pages can repeat or skip rows.
fix: order by a unique key, or by sort fields that end in one.
```

Category: correctness. Logger: `st.orm.sql.advice.unordered-limit`.

Without an ORDER BY, a database returns rows in whatever order is cheapest for it at that moment, and the order can change with the plan, with concurrent writes, or from one execution to the next. A `limit` or `offset` then takes an arbitrary part of the result: the first page and the second can overlap or miss rows between them, which no test on a small, quiet table shows. Because it holds however fast the statement runs, the advice log reports this advice too.

Order by a unique key, or by sort fields that end in one, so every row has one place in the order: `orderBy(Owner_.id)`, or `Pageable.ofSize(20).sortBy(Owner_.lastName).sortBy(Owner_.id)`. `scroll` always orders by its key, so it never gets this advice, and neither does a `limit(1)`, which asks for any matching row.

## A deep offset {#deep-offset}

```text
the database reads and throws away the 1000 rows the offset skips before it returns the first.
fix: scroll continues from the last row read, at the same cost for every window.
```

Category: performance. Logger: `st.orm.sql.advice.deep-offset`.

An offset tells the database to skip rows, and it skips them by reading them: page 100 of ten rows reads a thousand rows to return ten, and each page further reads ten more. Storm gives this advice from an offset of a thousand rows.

`scroll` continues from the last row read instead: it compares the sort fields and the key with that row's values, which an index on them serves, so every window costs what the first one does. It needs a unique key to continue from; a query without one reads deep pages through an offset, and the advice then says what each of them costs. See [Scrolling](pagination-and-scrolling.md#scrolling).

## An IN list of many lengths {#in-list-shapes}

```text
the IN list runs in many lengths, and each length is a separate statement to the database and its plan cache.
fix: storm.in_list=padded pads each list to the next power of two by repeating its last value, which changes no result and keeps the statements to a few.
```

Category: performance. Logger: `st.orm.sql.advice.in-list-shapes`. Reported by the advice log in development, and carried on a slow statement line wherever the slow statement log runs.

An `IN` list with a placeholder per value, the default shape on every database but PostgreSQL, is one statement to Storm whatever its length and a different statement to the database for each: a lookup of a varying number of keys is parsed, planned and cached once per length, and a plan cache such as Oracle's shared pool or SQL Server's fills with the variants. Storm records the lengths a statement shape renders with, while either log would show them, and the shape carries the advice once it has run in eight of them, enough to show that the length follows the input rather than a fixed set. In development the advice log reports it; in production, where the churn shows as a slow execution, the slow line carries it, and its facts say in how many lengths the shape has run so far, `parameters: 8  lengths: 12`.

`storm.in_list=padded` pads every list to the next power of two by repeating its last value, so lengths 1 to 1,000 render as 11 statements, and changes no result; see [IN Lists](queries.md#in-lists). It is a setting of the template, so it applies to every list the template renders. On PostgreSQL a list on a plain column binds as one array and is never reported; the advice there points at a list compared through a function, which renders placeholders, and `padded` pads that one at the cost of the array for the rest. A statement that runs in many lengths by design, and on a database whose plan cache has room for them, accepts the advice with `@AcceptAdvice("in-list-shapes")`.

The advice fits the database it runs on. A database that plans every execution anew and whose driver sends prepared statements as text by default, as MySQL and MariaDB do, keeps no plan per statement text: a list of 17 values costs it what a list of 18 costs, and padding would bind values for nothing. The dialect says so through `SqlDialect.keepsPlanPerStatementText()`, and no length is recorded or advised on there.

## An entity past the design budget {#entity-budget}

```text
every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns.
fix: a Ref for Clinic.organization removes 4 joins and 52 columns.
```

Category: design. Logger: `st.orm.sql.advice.entity-budget`. Reported once per entity, at startup where the integration checks the model, or when the entity is first used:

```
SQL advice (design Visit)
	graph: Visit(Pet(Owner(City), PetType, Clinic(Address, Organization(Country))))  12 joins, 140 columns beyond its own
	advice: every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns.
		fix: a Ref for Clinic.organization removes 4 joins and 52 columns.
		see: https://orm.st/docs/advice#entity-budget
```

An entity component joins its table into every read and brings that table's whole select list, along with every entity it declares in turn. The [design budget](entity-design.md#the-budget) bounds what a read adds beyond the entity's own columns: 10 joins and 96 columns, unless the application [sets its own](entity-design.md#the-budget). The advice states the budget in effect. The default is calibrated so ordinary schemas never reach it; an entity past it has a graph that grew, usually through entities nobody was editing.

The advice names the fields a `Ref` would cut to bring the read back within the budget, by the rules of [Where to Draw the Line](entity-design.md#where-to-draw-the-line): the deepest field that is enough on its own, since a cut near the leaves narrows every entity above it at once, and otherwise the widest fields first. It never names an identifying foreign key, which is composition, nor an entity without foreign keys of its own, such as a lookup table, which can never grow. A `Ref` keeps the relationship: filter, join and fetch through it as before, and resolve it in the queries that need it with `select().fetch(...)`.

Where you know the path is read constantly, an entity past the budget can stay as it is: put `@AcceptAdvice("entity-budget")` on it. Where the budget does not suit the model as a whole, [set the budget](entity-design.md#the-budget) instead.

## A slow read of a wide entity {#wide-entity-read}

```text
every read of Visit joins 6 tables and selects 58 columns beyond its own.
fix, where that width makes the read slow: a Ref for Clinic.organization removes 3 joins and 31 columns.
```

Category: design, reported by the slow statement log only: whether a read within the budget is too wide shows only in real use, so the advice comes with a read that turned out slow.

A read of an entity whose graph reaches half the design budget, 5 joins or 48 columns beyond its own by default, carries this advice to the slow statement log. A read can be slow for other reasons, such as its `WHERE` clause or a missing index, so the advice says what the width costs and what a `Ref` would save, not that the width is the cause. Compare the statement's plan with and without the joins before cutting. The field it names is chosen as for [an entity past the budget](#entity-budget), aiming below half the budget.

## References fetched one at a time {#repeated-fetch}

```text
24 statements each fetched one City, 28 ms, 1.2 ms each.
at: OwnerView.kt:31
fix: an inline foreign key joins it into every read of the rows
or: a query that fetches the references, such as select().fetch(...), resolves them in the statement that reads them.
```

Category: design. Reported by the advice log when a transaction that fetched ten or more references of one type completes, and by the [performance log](sql-logging.md#the-performance-log) summary where the fetches spanned the transactions of its unit of work: a `Ref` the application fetches on every read is evidence from real use that it should be an inline entity.

Each `Ref.fetch()` that the transaction's entity cache cannot serve is a statement of its own, so a loop over rows that fetches a reference from each runs one statement per row: the N+1 pattern. The advice states the time the fetches spent in the database and the call site of the fetch, where the stack was walked for it: in a transaction at the tenth fetch, in a summary when the scope records call sites.

Where every reader needs the referenced row, make the foreign key inline: `@FK val pet: Pet` rather than `Ref<Pet>` joins the pet into every read of a visit, repository methods such as `findById` included. See [Entity Design](entity-design.md).

Where only some readers need it, keep the `Ref` and read the rows with a query that fetches it: `select().fetch(Visit_.pet)` adds the pets to the statement that reads the visits. A repository method such as `findById` or `findAll` takes no `fetch`, so write that read as a `select()` that does. See [Resolving a Ref as Part of the Query](refs.md#resolving-a-ref-as-part-of-the-query).

## The same row lookup, again and again {#repeated-select}

```text
getById of Owner ran 12 times, 36 ms, 3.0 ms each, one row each.
at: OwnerService.kt:52 -> OwnerRepository.getById
fix: findAllById over the keys reads the rows in one round trip.
```

Category: performance. Reported by the advice log when a transaction that ran the statement ten or more times completes, and by the [performance log](sql-logging.md#the-performance-log) summary where the executions spanned the transactions of its unit of work.

A transaction, or a unit of work, that runs the same SELECT ten or more times from one call site, each returning at most one row, is looking rows up one at a time, typically by key inside a loop. Every lookup is a round trip to the database. A read a built-in repository method made, `findById`, `getById`, `existsById` or their `Ref` forms, is named by the method; a query of your own is named the same SELECT. The advice states the time the executions spent in the database and the call site of the statement; a shape repeated from two loops is two pieces of advice, each pointing at its own loop and weighed by its own cost. The call site names the caller and the repository method it called, in the direction of the call, `ReminderService.kt:52 -> VisitRepository.findByPet`: the loop that calls the method is the line to change, and the method is where a list variant such as `findByPets` would go. Where repository methods call each other, the one the caller called is named. A built-in call is named the same way, by the repository interface it was called on, `OwnerService.kt:52 -> OwnerRepository.getById`, or by its method alone on a repository obtained without one, and so is a flow a repository method built, which runs after the method has returned.

Executions that ran concurrently cost the caller the time they covered on the clock, not their sum. Where the sum is at least twice that, the line states both, `727 ms over 140 ms elapsed`, and the advice is ordered by the time on the clock. A repeat that took less than 50 ms on the clock carries no advice: in a larger application most repeats are correct and cost a few milliseconds, such as lookups of a few reference rows or a guard that keeps a task idempotent, and they would bury the few worth a change. `storm.advice.repeat_floor` (Spring Boot `storm.advice.repeat-floor`, Ktor `adviceRepeatFloor`) sets the floor, and `0` advises on every repeat past ten executions; the performance log's table lists every shape either way. One run is a rough judge of a repeat near the floor: the first run after startup reads on cold caches, so a loop advised at 188 ms then may cost 25 ms on every later run, and a concurrent repeat varies with how its executions overlap from run to run. The advice is for working through, and a repeat is reported once per call site however many runs cross the floor.

Read them in one query. For a built-in call the advice names its list form, `findAllById(ids)` or `findAllByRef(refs)` over the keys of every execution, which reads the rows, or for `existsById` the ones that exist, in one statement; `existsById` counts through the query builder, so without the call it would be advised a grouped query, which is more than the loop asks for. For a query of your own, a `where` with `IN` over the keys returns all of them in one statement. A read of several rows each time, such as scrolling window after window, gets no advice. A read of computed values, such as a count per pet, is not a lookup of rows: the advice then names one query with a `where` with `IN` over what the loop varies, which selects the keys that exist, with a `GROUP BY` on it where the counts themselves are used.

The line states the mean time per execution. From 10 ms per execution, well above what a round trip costs, it says the statement itself is the cost and to look at it first: combining twelve executions of 170 ms each saves little next to what each one costs on its own. Where the schema is loaded, the index such a statement lacks follows the line as [missing-index](#missing-index) advice. A line whose executions read or wrote nothing says `no rows`.

Within one transaction every execution sees the same data, so the advice is reported as the transaction's, as one block with its other repeats under the call site that began it:

```
SQL advice (transaction): VisitReminderService.kt:41
	advice: the same SELECT of Visit ran 40 times, 120 ms, 3.0 ms each, one row each.
		at: VisitReminderService.kt:52
		fix: one query for all of them, such as findAllById or a where with IN, reads the rows in one round trip.
		see: https://orm.st/docs/advice#repeated-select
```

The call site that heads the block is walked where the transaction's block is entered, so a suspending transaction, which completes on the dispatcher that ran its block, is named all the same; a transaction another manager opened is named by the method still on the stack when it completes. A joined block counts toward the transaction it joined; a `REQUIRES_NEW` block is a transaction of its own. The transaction records its statements only while the advice log reports the advice, which is development, and the record is discarded when it completes. A loop that opens a transaction per item repeats across transactions, which no single transaction sees; the performance log summary of the unit of work reports it, and says how many transactions the executions ran in. The summary leaves the executions a transaction advised on to that transaction, so one repeat never reads as two problems. Accept the advice on the method that runs the loop, or on the entity, with `@AcceptAdvice("repeated-select")`.

## Related writes that each commit on their own {#writes-without-transaction}

```text
the unit of work wrote Owner and then Pet, which references Owner, each committing on its own, so when a later write fails, the earlier ones stay.
fix: run them in one transaction.
```

Category: correctness, reported by the [performance log](sql-logging.md#the-performance-log) summary of the unit of work.

Two writes outside a transaction each commit on their own: when the second fails, the first stays. Often that is deliberate, such as a batch job that commits as it goes, an outbox row, or an audit record that must survive a failure, so advice on every unit of work with several writes would be noise. One shape is almost never deliberate: a write to a table that references a table an earlier write of the same unit of work wrote, such as an owner and then its pet. If the pet fails, the database keeps an owner without it.

Each recorded statement keeps whether it ran in a transaction, and the summary walks its writes once: a write outside any transaction to an entity whose foreign key references an entity an earlier write outside any transaction wrote is advised, once per pair of types. The check is by type, from the model's foreign keys, not by row, so two writes to related types that touch unrelated rows are advised as well. Writes Storm does not run, such as through another library, are not seen.

Run the writes in one transaction, `transaction { }` in Kotlin or `transaction(tx -> ...)` in Java, so that a failure rolls back what came before it. Where the second write stands on its own by design, such as an audit record that must survive the failure of what it describes, accept the advice on that entity with `@AcceptAdvice("writes-without-transaction")`, or on the method or class that runs the unit of work.

## The same single-row write, again and again {#repeated-write}

```text
insert of Owner ran 10 times, 14 ms, 1.4 ms each, one row each, in 10 transactions of their own.
fix: the overloads that take a list send the rows as one batch, which commits them together.
```

Category: performance. Reported by the advice log when a transaction that ran the statement ten or more times completes, and by the [performance log](sql-logging.md#the-performance-log) summary where the executions spanned the transactions of its unit of work.

A transaction, or a unit of work, that runs the same INSERT, UPDATE or DELETE ten or more times, one row each, sends every row in a round trip of its own. A write a repository method made for an entity it was given, `insert`, `update`, `upsert` or `remove`, is named by the method; a write of your own is named the same statement. The advice states the time the executions spent and the call site of the statement where it is known, as [the same row lookup](#repeated-select) does, and is reported in the same scopes.

The repository overloads that take a list, such as `insert(owners)`, send the rows as one JDBC batch. A batch already counts as one execution, so batched writes never get this advice. That remedy is for an insert, and for an update or removal the repository made for an entity or key it was given. A write by condition, such as `removeAll(Visit_.pet eq pet)` per pet, has no list overload: the advice names one statement whose condition covers all of them, such as `Visit_.pet inList pets`, which is often a change of design as well as of the loop.

Within one transaction a batch changes nothing about what commits. Across transactions it does: a task that calls an external registry per `Pet` and records each answer in a transaction of its own keeps the answers recorded so far when a later call fails, and batched, they commit together. The summary knows the executions ran in transactions of their own, each holding nothing but that write, so its advice says that the batch commits the rows together, and the choice is yours. Where each transaction ran the write once and held more, such as a delete run once per rebuild of a study inside that study's transaction, the repeat is the loop over units of work, and no batch merges the writes without taking them out of the transactions they belong to. Such a repeat carries no advice, unless each execution took 10 ms or more: then the advice says the statement runs once per transaction and is the thing to look at, without a remedy for the loop.

Where the writes sit between work outside the database, as in that loop around a remote call, the advice says so and names the remedy that fits: collect the rows and write them after the loop, never a transaction around it, which would hold the connection across every call:

```text
insert of PetRegistration ran 29 times, 96 ms, 3.3 ms each, one row each, in 29 transactions of their own, spread over 7157 ms with work outside the database between them.
at: PetRegistryService.kt:60
fix: collect the rows and write them after the loop through the overloads that take a list, which sends them as one batch and commits them together, rather than a transaction around the loop.
```

Storm keeps when each execution began and returned, and when every other statement of the scope ran, so it sees the gaps: the executions are spread when the time between the first and the last that no statement spent in the database is at least ten times what they spent inside it. A loop that runs other queries between the writes is not spread by them. A read is never said to be spread: the clause is there for a write's remedy, which must not become a transaction around the loop.

An upsert is named one, since the dialect recognizes the database's own upsert form, a `MERGE` that both updates and inserts included, and its remedy comes first: a sync that upserts the same rows every run writes back what the rows already hold, which a batch makes cheaper and a read first removes, leaving one select and no writes in the steady state. On a database whose upsert draws a generated key value for the insert before it finds the row to update, the advice adds that each upsert that leaves the key to the database takes one, which the dialect states through `SqlDialect.upsertTakesGeneratedKeyOnUpdate()`:

```text
upsert of PetRegistration ran 29 times, ...
fix: where the rows mostly exist and rarely change, read them first and write only the new or changed ones, which avoids the writes, and the generated key value each upsert takes even where it updates
or: the overloads that take a list send the rows as one batch, which commits them together.
```
