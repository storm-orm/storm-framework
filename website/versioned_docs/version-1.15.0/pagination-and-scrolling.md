import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Pagination and Scrolling

Storm reads a result set in parts in four ways: a slice, a page, a window, and a stream of windows. This page covers each in detail, including their trade-offs, type signatures, and advanced usage, with raw offset and limit as the manual baseline they build on.

For a quick overview, see [Queries: Data Retrieval Strategies](queries.md#data-retrieval-strategies).

## Choosing a Read

The results share one shape, `Slice`, and two of them add navigation to it:

```
Slice<R>          content, hasNext, hasPrevious, iteration
├── Page<R>       + totalCount, next() / previous() as a Pageable
└── Window<R>     + next() / previous() as a Scrollable, cursors
```

Each read answers a different question, so pick by what the application needs to know and how it moves through the data.

| | Slice | Page | Scroll | Windows |
|---|---|---|---|---|
| Method | `slice(pageable)` | `page(pageable)` | `scroll(scrollable)` | `windows(size)` |
| Result | `Slice<R>` | `Page<R>` | `Window<R>` | `Stream<Window<R>>` or `Flow<Window<R>>` |
| Moves by | offset | page number | row values | row values |
| Needs | an ordering | an ordering | a unique key, sort fields that are not nullable | a primary key, or a `Scrollable` |
| Count query | no | yes, on every full page | no | no |
| `hasNext` from | one extra row | the count | one extra row | one extra row |
| Next request | `pageable.next()` | `page.next()` | `window.next()` | the stream continues |
| Random access | yes | yes | no | no |
| Stable under inserts and deletes | no | no | yes | yes |
| Cost of going deep | grows with the offset | grows with the offset | constant | constant |
| Typical use | "load more" without a count, or a query without a unique key | numbered pages, "page 3 of 12" | infinite scroll, REST cursors | batch jobs that write while they read |

Every read here hands back a complete result, so the loop that follows may query, fetch references and write. A result stream does not: it holds the connection consume-only until it is read to its end or closed. See [Batch Processing & Streaming](batch-streaming.md#the-connection-is-consume-only-while-a-stream-is-open).

**Slice** is a page without the count query. It takes the same `Pageable`, reads one row beyond the page size to report `hasNext`, and navigates through that `Pageable`. `Slice` is also the shape a `Page` and a `Window` share. It is the read for a "load more" that does not need a total, and for a query without a unique key, where scrolling is not possible.

**Page** wraps offset and limit with a total count and page metadata, for UIs that show page numbers or jump to a page. The count is a second query on every full page; `page(pageable, totalCount)` reuses a count the application already has.

**Scroll** navigates by keyset: the request names the row a window ended on and asks for the rows after it, or before it. The database seeks straight to that row through an index, so the cost is the same at any depth, and rows inserted or deleted elsewhere do not shift the window. The trade-off is that you move forward or backward from the current window only.

**Windows** iterates scroll requests for you: each window is one closed statement, and the connection is free between windows, so the loop body can write. See [Batch Processing & Streaming](batch-streaming.md#windows).

## Offset and Limit

For direct offset/limit control, use `offset` and `limit` on the query builder. Always combine these with `orderBy` to ensure deterministic ordering. Without one the database chooses the order, and the read still runs on every dialect: SQL Server accepts an offset only after an `ORDER BY`, so Storm adds a constant one there.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val results = orm.entity<User>()
    .select()
    .orderBy(User_.createdAt)
    .offset(20)
    .limit(10)
    .resultList
```

</TabItem>
<TabItem value="java" label="Java">

```java
List<User> results = orm.entity(User.class)
    .select()
    .orderBy(User_.createdAt)
    .offset(20)
    .limit(10)
    .getResultList();
```

</TabItem>
</Tabs>

## Slices

A slice is a page without the count query. `slice(pageable)` takes the same `Pageable` as `page(pageable)`, reads the requested page with `OFFSET` and `LIMIT`, and fetches one row beyond the page size to decide `hasNext`; `hasPrevious` follows from the page number. The next slice is the request's `next()`. Use it for a "load more" that does not need a total, and for a query without a unique key, such as an aggregation, where scrolling is not possible.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pageable = Pageable.ofSize(20).sortBy(User_.email)
val first: Slice<User> = userRepository.slice(pageable)
if (first.hasNext()) {
    val second = userRepository.slice(pageable.next())
}

// On the query builder, with the query's own ordering
val slice = userRepository.select()
    .where(User_.city eq city)
    .slice(0, 20)
```

</TabItem>
<TabItem value="java" label="Java">

```java
Pageable pageable = Pageable.ofSize(20).sortBy(User_.email);
Slice<User> first = userRepository.slice(pageable);
if (first.hasNext()) {
    Slice<User> second = userRepository.slice(pageable.next());
}

// On the query builder, with the query's own ordering
Slice<User> slice = userRepository.select()
    .where(User_.city, EQUALS, city)
    .slice(0, 20);
```

</TabItem>
</Tabs>

`Slice` is the shape `Page` and `Window` share, so every read in parts offers it:

| Method | Description |
|---|---|
| `content()` | The list of results, in the order they were read |
| `hasNext()` / `hasPrevious()` | Whether rows existed after and before the slice at query time |
| `size()` / `isEmpty()` | The number of results |
| `iterator()` / `stream()` | Iteration over the content, so `for (user : slice)` reads the rows |

`sliceRef` reads refs instead of entities, the way `pageRef` does. As with `page`, a `Pageable` that carries sort orders cannot be combined with an explicit `orderBy` on the query.

## Pagination

Pagination navigates by page number and returns a `Page<R>`. Each request runs a data query with `OFFSET`/`LIMIT` for the content, plus a `SELECT COUNT(*)` when the total cannot be derived from the fetched page. A page that is not full determines the total directly, so the count query only runs for a full page, or for an empty page beyond the first.

Use the `page` terminal method on the query builder. Pass a `Pageable` to specify the page number and page size. The result is a `Page` containing the content, total count, and navigation methods.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pageable = Pageable.ofSize(10)
val page: Page<User> = orm.entity<User>()
    .select()
    .where(User_.city eq city)
    .page(pageable)

// Navigate
if (page.hasNext()) {
    val nextPage = orm.entity<User>()
        .select()
        .where(User_.city eq city)
        .page(page.next())
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Pageable pageable = Pageable.ofSize(10);
Page<User> page = orm.entity(User.class)
    .select()
    .where(User_.city, EQUALS, city)
    .page(pageable);

// Navigate
if (page.hasNext()) {
    Page<User> nextPage = orm.entity(User.class)
        .select()
        .where(User_.city, EQUALS, city)
        .page(page.next());
}
```

</TabItem>
</Tabs>

The `Page` record contains everything needed to build pagination controls:

| Field / Method | Description |
|---|---|
| `content` | The list of results for the current page |
| `totalCount` | Total number of matching rows across all pages |
| `pageNumber()` | Zero-based index of the current page |
| `pageSize()` | Maximum number of elements per page |
| `totalPages()` | Computed total number of pages |
| `hasNext()` / `hasPrevious()` | Whether adjacent pages exist |
| `next()` / `previous()` | Returns a `Pageable` for the adjacent page; `previous()` is `null` on the first page |

### Sorting

Sort orders are specified on the `Pageable` using `sortBy` (ascending) and `sortByDescending` (descending). Multiple calls append columns to build a multi-column sort, and the orders carry over automatically when navigating with `next()` or `previous()`. You do not need to call `orderBy` separately on the query builder.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Single column, ascending
val pageable = Pageable.ofSize(10).sortBy(User_.createdAt)

// Single column, descending
val pageable = Pageable.ofSize(10).sortByDescending(User_.createdAt)

// Multi-column: last name ascending, then first name descending
val pageable = Pageable.ofSize(10)
    .sortBy(User_.lastName)
    .sortByDescending(User_.firstName)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Single column, ascending
Pageable pageable = Pageable.ofSize(10).sortBy(User_.createdAt);

// Single column, descending
Pageable pageable = Pageable.ofSize(10).sortByDescending(User_.createdAt);

// Multi-column: last name ascending, then first name descending
Pageable pageable = Pageable.ofSize(10)
    .sortBy(User_.lastName)
    .sortByDescending(User_.firstName);
```

</TabItem>
</Tabs>

For the full `Page` and `Pageable` API reference, see [Repositories: Offset-Based Pagination](repositories.md#offset-based-pagination).

## Scrolling

Scrolling navigates by keyset and returns a `Window<R>`: the results in the request's sort order, two flags that say whether rows exist after and before the window, and the tokens that continue from it. There is no total count and no page number.

Under the hood, scrolling remembers the row a window ended on and asks the database for the rows after it, or the row it started on and the rows before it. The database seeks to that row through an index instead of scanning and discarding skipped rows, so performance stays constant regardless of depth.

A scroll request is a `Scrollable`: an ordering, a window size, and optionally the position to continue from.

- **The key** is a unique, non-nullable field, typically the primary key. It orders last, breaks ties, and makes every row addressable. Fields annotated with `@UK` or `@PK` generate a `Metamodel.Key`; see [Metamodel](metamodel.md#unique-keys-uk-and-metamodelkey).
- **Sort fields** order before the key, each in its own direction, and must not allow NULL values. A NULL never compares, so a row whose sort value is NULL would fall out of every window rather than appear in one of them. Scrolling checks a sort field the way it checks the key and throws a `PersistenceException`, rather than paginate over rows it would silently skip. A date or a status is the usual thing to sort by, and those are also the columns most often left nullable, so make the field non-nullable before scrolling by it.
- **The position** names a row by its sort and key values, and says whether to continue after it or before it. It is what a `Window` hands back as `next()` and `previous()`, and what a cursor string carries across a network boundary. Like the cursor, it is opaque: the application states it through `after`, `before` or `from`, and the engine reads the row it names.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// First window, twenty users ordered by id
val window: Window<User> = userRepository.scroll(Scrollable.of(User_.id, 20))

// The window after it, and the window before it, both in id order
val next: Window<User> = userRepository.scroll(window.next())
val previous: Window<User> = userRepository.scroll(next.previous())

// Newest first: the key descending
val latest = userRepository.scroll(Scrollable.of(User_.id, 20).descending())

// Sorted by city, then birth date, with id as tiebreaker
val byName = userRepository.scroll(Scrollable.of(User_.id, 20).sortBy(User_.city).sortBy(User_.birthDate))
```

</TabItem>
<TabItem value="java" label="Java">

```java
// First window, twenty users ordered by id
Window<User> window = userRepository.scroll(Scrollable.of(User_.id, 20));

// The window after it, and the window before it, both in id order
Window<User> next = userRepository.scroll(window.next());
Window<User> previous = userRepository.scroll(next.previous());

// Newest first: the key descending
var latest = userRepository.scroll(Scrollable.of(User_.id, 20).descending());

// Sorted by city, then birth date, with id as tiebreaker
var byName = userRepository.scroll(Scrollable.of(User_.id, 20).sortBy(User_.city).sortBy(User_.birthDate));
```

</TabItem>
</Tabs>

A `Window<R>` is a `Slice`, so it iterates over its content and reports `size()` and `isEmpty()`; `for (user in window)` reads the rows without going through `content()`. It carries:

| Field / Method | Description |
|-------|-------------|
| `content()` | The results, in the request's sort order. |
| `hasNext()` | `true` if rows existed after this window, in sort order, at query time. |
| `hasPrevious()` | `true` if rows existed before this window, in sort order, at query time. |
| `next()` | A `Scrollable` for the window after this one, or `null` if the window is empty. |
| `previous()` | A `Scrollable` for the window before this one, or `null` if the window is empty. |
| `nextCursor()` / `previousCursor()` | The same positions as opaque strings, `null` when the flag says there is nothing there. |

The tokens are always there when the window has content. The sort and key values are read from each row alongside the result, so a window of refs, of a projection read as another type, or of a custom select type navigates like a window of entities. The flags are informational: they say what existed when the query ran, and following a token is always allowed, which is what a polling loop wants when new rows may have arrived.

**Every window is in sort order.** A window reached through `previous()` is fetched with the ordering reversed and turned around before it is returned, so it reads exactly like a window reached through `next()`. A descending feed is a descending sort navigated forward, not a forward sort navigated backward.

**Ordering is built in.** The request owns the `ORDER BY`. Adding your own `orderBy()` to a scrolled query is rejected at runtime with a `PersistenceException`, because it would corrupt the window boundaries.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Wrong: orderBy conflicts with the request's ordering
userRepository.select()
    .orderBy(User_.email)         // PersistenceException at runtime
    .scroll(Scrollable.of(User_.id, 10))

// Right: the request orders
userRepository.select()
    .scroll(Scrollable.of(User_.id, 10).sortBy(User_.email))
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Wrong: orderBy conflicts with the request's ordering
userRepository.select()
    .orderBy(User_.email)         // PersistenceException at runtime
    .scroll(Scrollable.of(User_.id, 10));

// Right: the request orders
userRepository.select()
    .scroll(Scrollable.of(User_.id, 10).sortBy(User_.email));
```

</TabItem>
</Tabs>

**No total count.** A `COUNT(*)` over a large filtered set costs what scrolling exists to avoid, and the number drifts while a user navigates. Scrolling is for "load more" and infinite-scroll patterns where a total is rarely needed. If you need one, call `count` (Kotlin) or `getCount()` (Java) on the query builder separately.

### Sorting by Non-Unique Columns

A sort field alone cannot address a row, because its values repeat, so the key stays the tiebreaker. `sortBy` and `sortByDescending` add sort fields in precedence order, each with its own direction, and `descending()` sets the direction of the key itself.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Oldest first: creation date ascending, id as tiebreaker
val window = postRepository.select()
    .scroll(Scrollable.of(Post_.id, 20).sortBy(Post_.createdAt))

// Newest first: creation date descending, and the key descending to match
val latest = postRepository.select()
    .scroll(Scrollable.of(Post_.id, 20).sortByDescending(Post_.createdAt).descending())

// The next and the previous window, both in the same order as the window they came from
val next = postRepository.select().scroll(window.next())
val previous = postRepository.select().scroll(window.previous())
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Oldest first: creation date ascending, id as tiebreaker
var window = postRepository.select()
    .scroll(Scrollable.of(Post_.id, 20).sortBy(Post_.createdAt));

// Newest first: creation date descending, and the key descending to match
var latest = postRepository.select()
    .scroll(Scrollable.of(Post_.id, 20).sortByDescending(Post_.createdAt).descending());

// The next and the previous window, both in the same order as the window they came from
var next = postRepository.select().scroll(window.next());
var previous = postRepository.select().scroll(window.previous());
```

</TabItem>
</Tabs>

The generated SQL is the expanded keyset condition, which stays portable to every dialect and handles a mix of directions. For `sortBy(Post_.createdAt)` continuing after a row:

```sql
WHERE (created_at > ? OR (created_at = ? AND id > ?))
ORDER BY created_at ASC, id ASC
LIMIT 21
```

Continuing before a row flips every comparison and direction, and the content is reversed afterwards:

```sql
WHERE (created_at < ? OR (created_at = ? AND id < ?))
ORDER BY created_at DESC, id DESC
LIMIT 21
```

Each further sort field adds one more term to the chain. A descending sort field inside an ascending key uses `<` for its own term and `>` for the key, so `sortByDescending(Post_.createdAt)` with an ascending id reads newest-first within the same date and oldest id first among equal dates.

**Indexing.** For scrolling with sort fields to perform well, create a composite index that covers the sort fields and the key in that order:

```sql
CREATE INDEX idx_post_created_id ON post (created_at, id);
```

### Programmatic Positions

`next()` and `previous()` are the usual way to move, and cursor strings the usual way to cross a network boundary. A position can also be stated directly, for example from a row the application already holds. The values come in ordering order: one per sort field, then the key.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val afterAlice = userRepository.scroll(Scrollable.of(User_.id, 20).sortBy(User_.email).after("alice@example.com", 3))
val beforeAlice = userRepository.scroll(Scrollable.of(User_.id, 20).sortBy(User_.email).before("alice@example.com", 3))
```

</TabItem>
<TabItem value="java" label="Java">

```java
var afterAlice = userRepository.scroll(Scrollable.of(User_.id, 20).sortBy(User_.email).after("alice@example.com", 3));
var beforeAlice = userRepository.scroll(Scrollable.of(User_.id, 20).sortBy(User_.email).before("alice@example.com", 3));
```

</TabItem>
</Tabs>

### GROUP BY and Aggregated Projections

When a query uses GROUP BY, the grouped column produces unique values in the result set even if the column itself is not annotated with `@UK`. In this case, wrap the metamodel with `.key()` (Kotlin) or `Metamodel.key()` (Java) to indicate it can serve as the key. A reference field's column carries the referenced key, so the position value is that id.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class OrderSummary(val city: Ref<City>, val orderCount: Long) : Data

val window: Window<OrderSummary> = orm.selectFrom<Order, OrderSummary> {
    """${Order_.city.id}, COUNT(*)"""
}
.groupBy(Order_.city)
.scroll(Scrollable.of(Order_.city.key(), 20))

// The custom select type navigates like an entity: the key is read from the row
val next = orm.selectFrom<Order, OrderSummary> { ... }
    .groupBy(Order_.city)
    .scroll(window.next())
```

</TabItem>
<TabItem value="java" label="Java">

```java
record OrderSummary(Ref<City> city, long orderCount) implements Data {}

Window<OrderSummary> window = orm.selectFrom(Order.class, OrderSummary.class,
    RAW."""SELECT \{Order_.city.id}, COUNT(*)""")
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Metamodel.key(Order_.city), 20));

// The custom select type navigates like an entity: the key is read from the row
Window<OrderSummary> next = orm.selectFrom(Order.class, OrderSummary.class, ...)
    .groupBy(Order_.city)
    .scroll(window.next());
```

</TabItem>
</Tabs>

See [Manual Key Wrapping](metamodel.md#manual-key-wrapping) for more details.

**REST cursor support.** For REST APIs that pass scroll state as a query parameter, `Window` provides `nextCursor()` and `previousCursor()`, which serialize the position to an opaque string, and `Scrollable.from(cursor)` puts a request at that position. The cursor carries the position only; the ordering and the size stay in code, so a client may ask for another size on the next request. See [Cursor Serialization](cursors.md).

## Summary

| | Slice | Pagination | Scrolling |
|---|---|---|---|
| Request | `Pageable` | `Pageable` | `Scrollable<T>` |
| Result | `Slice` | `Page` | `Window` |
| Method | `slice(pageable)` | `page(pageable)` | `scroll(scrollable)` |
| Count query | no | yes | no |
| Navigate forward | `pageable.next()` | `page.next()` | `window.next()` |
| Navigate backward | `pageable.previous()` | `page.previous()` | `window.previous()`, same order as forward |
| Sorting | `sortBy` / `sortByDescending` on the request | `sortBy` / `sortByDescending` on the request | `sortBy` / `sortByDescending` on the request, key as tiebreaker |
