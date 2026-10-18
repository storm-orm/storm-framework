import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Statement Advice

Storm attaches advice to a statement where the statement's own shape says it reads more than it needs to, or returns rows in no stated order. Advice comes in two kinds:

- **Advice that holds at any speed.** A [limit or offset without an order](#unordered-limit) returns an arbitrary part of the result however fast it runs.
- **Advice about cost.** A count behind a page, a deep offset, a `distinct` over a join that repeats rows, or a search of short words alone costs more as the data grows, and on a small table costs nothing worth changing. It matters once the statement is slow.

The advice reaches you two ways.

**The slow statement log.** A line in the [slow statement log](sql-logging.md#the-slow-statement-log) ends with every piece of advice its statement carries, of either kind, every time it is slow.

**The advice log.** The `st.orm.sql.advice` logger reports advice whether the statement is fast or slow, with the statement, its call site and its shape. Each level reports more than the one above it:

| Level | Reports | How often |
|-------|---------|-----------|
| `INFO` | advice that holds at any speed | once per kind of advice |
| `DEBUG` | advice about cost as well | once per statement shape |
| `TRACE` | all advice | at every execution |

At `INFO` the log stays quiet: an application sees at most one line for each kind of advice, showing the first statement that drew it. At `DEBUG` it lists every statement shape to change, a list to work through while developing or in a test environment. The offset of a page is part of its statement, so each page of the same query is a shape of its own. `TRACE` shows each execution that carries advice. See [The Advice Log](sql-logging.md#the-advice-log).

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

A substring index, such as PostgreSQL's trigram index, serves words of a minimum length, three characters for a trigram. `search` tests a shorter word only on the rows its longer words found, but an input of short words alone, such as `de`, leaves it nothing to start from, so the database reads every row of each searched table.

A search box that queries as the user types meets such input on almost every keystroke. Use `suggest` there: it ignores the words the index cannot serve and matches no rows for an input without a longer one, so every statement it renders is served by the index. The search page the box leads to keeps `search`. See [Suggestions as the User Types](search.md#suggestions-as-the-user-types) and [Indexing a Search](search.md#indexing-a-search).

## DISTINCT over a join that repeats rows {#distinct-over-join}

> DISTINCT removes the rows the join to Pet repeats, one per related row; semiJoin(Pet) filters by it without repeating rows

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

`page` reports the total number of matching rows, and finding it takes a count query that visits every one of them, however small the page. On a large or expensive result the count costs more than the page itself.

A list that shows "next" rather than "page 3 of 120" needs no total. `slice` reads the same page with one row beyond it, which says whether a next page exists, and runs no count. Where the total is needed but changes slowly, `page(pageable, totalCount)` takes a total counted once and kept. A page that is not full already knows its total, so its count never runs. See [Slices](pagination-and-scrolling.md#slices).

## A limit or offset without an order {#unordered-limit}

> the statement takes part of the result without an ORDER BY, so which rows it returns is up to the database

Without an ORDER BY, a database returns rows in whatever order is cheapest for it at that moment, and the order can change with the plan, with concurrent writes, or from one execution to the next. A `limit` or `offset` then takes an arbitrary part of the result: the first page and the second can overlap or miss rows between them, which no test on a small, quiet table shows. Because it holds however fast the statement runs, the advice log reports this advice too.

Order by a unique key, or by sort fields that end in one, so every row has one place in the order: `orderBy(Owner_.id)`, or `Pageable.ofSize(20).sortBy(Owner_.lastName).sortBy(Owner_.id)`. `scroll` always orders by its key, so it never gets this advice, and neither does a `limit(1)`, which asks for any matching row.

## A deep offset {#deep-offset}

> the database reads and discards the 1000 rows the offset skips before it returns the first

An offset tells the database to skip rows, and it skips them by reading them: page 100 of ten rows reads a thousand rows to return ten, and each page further reads ten more. Storm gives this advice from an offset of a thousand rows.

`scroll` continues from the last row read instead: it compares the sort fields and the key with that row's values, which an index on them serves, so every window costs what the first one does. It needs a unique key to continue from; a query without one reads deep pages through an offset, and the advice then says what each of them costs. See [Scrolling](pagination-and-scrolling.md#scrolling).
