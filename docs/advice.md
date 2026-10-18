import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Statement Advice

Storm attaches advice to a statement where the statement's own shape says it reads more than it needs to, or returns rows in no stated order. Each piece of advice has a category:

- **Correctness.** A [limit or offset without an order](#unordered-limit) returns an arbitrary part of the result, however fast it runs.
- **Performance.** A deep offset, a `distinct` over a join that repeats rows, or a search of short words alone costs more as the data grows, and on a small table costs nothing worth changing.

The advice reaches you two ways.

**The slow statement log.** A line in the [slow statement log](sql-logging.md#the-slow-statement-log) ends with every piece of advice its statement carries, every time it is slow.

**The performance log.** A [performance log](sql-logging.md#the-performance-log) summary ends with advice on what its unit of work repeated, which no single statement shows: references fetched one at a time, and the same single-row read or write run again and again.

**The advice log.** The `st.orm.sql.advice` logger reports advice whether the statement is fast or slow, with the statement, its call site and its shape:

- **Correctness is on by default.** An application that logs at `INFO` sees advice on correctness without asking for it.
- **Performance is on request.** Name it in the categories the log reports: `storm.sql-log.advice.categories: correctness, performance` in Spring Boot, `storm.sqlLog.advice.categories` in Ktor, or the `storm.sql_log.advice.categories` system property.
- **Accepted advice is turned off.** Each piece of advice has a logger of its own, named after its section on this page. Set it to `OFF` when the statement is as intended, such as `st.orm.sql.advice.deep-offset: OFF`.
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
	shape 9b34305d1a7c4e02
	advice DISTINCT removes the rows the join to Pet repeats, one per related row; semiJoin(Pet) filters by it without repeating rows, so the query needs no DISTINCT. See https://orm.st/docs/advice#distinct-over-join
```

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

## References fetched one at a time {#repeated-fetch}

> 24 statements each fetched one City; fetch on the query that read the references resolves them in that statement

Category: performance, reported by the [performance log](sql-logging.md#the-performance-log) summary of the unit of work.

Each `Ref.fetch()` that the transaction's entity cache cannot serve is a statement of its own, so a loop over rows that fetches a reference from each runs one statement per row: the N+1 pattern. The performance log counts these as fetches, and advises once a unit of work fetched ten or more of one type.

Resolve the references in the query that read them: `fetch(Visit_.pet)` on that query adds them to its statement. Where every reader needs the referenced row, an inline foreign key (`@FK val pet: Pet` rather than `Ref<Pet>`) joins it by default. See [Resolving a Ref as Part of the Query](refs.md#resolving-a-ref-as-part-of-the-query) and [Entity Design](entity-design.md).

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
