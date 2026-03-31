import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Pagination and Scrolling

Storm supports three strategies for retrieving subsets of a result set: manual offset/limit, offset-based pagination, and cursor-based scrolling. This page covers each in detail, including their trade-offs, type signatures, and advanced usage.

For a quick overview, see [Queries: Data Retrieval Strategies](queries.md#data-retrieval-strategies).

## Choosing a Strategy

Storm provides three ways to retrieve a subset of query results. The right choice depends on how your application navigates the data and how large the result set is.

| Feature | Offset and Limit | Pagination | Scrolling |
|---------|-----------------|------------|-----------|
| Navigation | manual | page number | cursor |
| Result type | `List<R>` | `Page<R>` | `Window<T>` |
| Count query | no | yes | no |
| Random access | yes | yes | no |
| Navigation tokens | no | `nextPageable()` / `previousPageable()` | `nextScrollable()` / `previousScrollable()` |
| Performance on large datasets | degrades with offset | degrades with offset | constant |

**Offset and Limit** gives raw control with `offset()` and `limit()` on the query builder. Both pagination and offset/limit use SQL `OFFSET` under the hood, which degrades on large tables because the database must scan and discard all skipped rows.

**Pagination** wraps offset/limit with a `Page` container that includes total counts and page metadata. This is useful for UIs that display "Page 3 of 12" or need random page access.

**Scrolling** uses keyset pagination: it remembers the last value seen and asks the database for rows after (or before) that value. The database seeks directly to the cursor position using an index, so performance stays constant regardless of depth. The trade-off is that you can only move forward or backward from the current position.

## Offset and Limit

For direct offset/limit control, use `offset` and `limit` on the query builder. Always combine these with `orderBy` to ensure deterministic ordering.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val results = orm.entity(User::class)
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

## Pagination

Pagination navigates by page number and returns a `Page<R>`. Each request typically requires two queries: a `SELECT COUNT(*)` to determine the total number of results, and a data query with `OFFSET`/`LIMIT` for the content.

Use the `page` terminal method on the query builder. Pass a `Pageable` to specify the page number and page size. The result is a `Page` containing the content, total count, and navigation methods.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val pageable = Pageable.ofSize(10)
val page: Page<User> = orm.entity(User::class)
    .select()
    .where(User_.active, EQUALS, true)
    .page(pageable)

// Navigate
if (page.hasNext()) {
    val nextPage = orm.entity(User::class)
        .select()
        .where(User_.active, EQUALS, true)
        .page(page.nextPageable())
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
Pageable pageable = Pageable.ofSize(10);
Page<User> page = orm.entity(User.class)
    .select()
    .where(User_.active, EQUALS, true)
    .page(pageable);

// Navigate
if (page.hasNext()) {
    Page<User> nextPage = orm.entity(User.class)
        .select()
        .where(User_.active, EQUALS, true)
        .page(page.nextPageable());
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
| `nextPageable()` / `previousPageable()` | Returns a `Pageable` for the adjacent page |

### Sorting

Sort orders are specified on the `Pageable` using `sortBy` (ascending) and `sortByDescending` (descending). Multiple calls append columns to build a multi-column sort, and the orders carry over automatically when navigating with `nextPageable()` or `previousPageable()`. You do not need to call `orderBy` separately on the query builder.

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

Scrolling navigates sequentially using a cursor and returns a `Window<T>`. A `Window` represents a portion of the result set: it contains the data, informational flags (`hasNext`, `hasPrevious`) that indicate whether adjacent results existed at query time, and `Scrollable<T>` navigation tokens for sequential traversal, but no total count or page number. The navigation tokens `nextScrollable()` and `previousScrollable()` are always available when the window has content, regardless of whether `hasNext` or `hasPrevious` is `true`. This allows the developer to decide whether to follow a cursor, since new data may appear after the query was executed.

Under the hood, scrolling uses keyset pagination: it remembers the last value seen on the current page and asks the database for rows after (or before) that value. This avoids the performance cliff of `OFFSET` on large tables, because the database can seek directly to the cursor position using an index.

:::info Sort Stability
Scrolling requires a stable sort order. The final sort column must be unique (typically the primary key). Using a non-unique sort column like `createdAt` without a tiebreaker will produce duplicate or missing rows at page boundaries. Use the [sort overload](#sorting-by-non-unique-columns) (`Scrollable.of(key, sort, size)`) when sorting by a non-unique column.
:::

The `scroll` method is available directly on repositories and on the query builder. It accepts a `Scrollable<T>` that captures the cursor state and returns a `Window<T>` containing:

| Field / Method | Description |
|-------|-------------|
| `content()` | The list of results for this window. |
| `hasNext()` | `true` if more results existed beyond this window at query time. |
| `hasPrevious()` | `true` if this window was fetched with a cursor position (i.e., not the first page). |
| `nextScrollable()` | Returns a `Scrollable<T>` for the next window, or `null` if the window is empty. |
| `previousScrollable()` | Returns a `Scrollable<T>` for the previous window, or `null` if the window is empty. |

Create a `Scrollable` using the factory methods, or obtain one from a `Window`:

| Method | Purpose | SQL effect |
|--------|---------|------------|
| `Scrollable.of(key, size)` | Request for the first page (ascending). | `ORDER BY key ASC LIMIT size+1` |
| `Scrollable.of(key, size).backward()` | Request for the first page (descending). | `ORDER BY key DESC LIMIT size+1` |
| `window.nextScrollable()` | Request for the next page after the current window. | `WHERE key > cursor ORDER BY key ASC LIMIT size+1` |
| `window.previousScrollable()` | Request for the previous page before the current window. | `WHERE key < cursor ORDER BY key DESC LIMIT size+1` |

The extra row (`size+1`) is used internally to determine the value of `hasNext`, then discarded from the returned content.

**Result ordering.** Forward scrolling returns results in ascending key order. Backward scrolling (via `.backward()`) returns results in **descending** key order. If you need ascending order for display after navigating backward, reverse the list.

**No total count.** Unlike pagination, scrolling does not include a total element count. A separate `COUNT(*)` query must execute the same joins, filters, and conditions as the main query, which can be expensive on large or complex result sets. Total counts are also inherently unstable: rows may be inserted or deleted while a user navigates through pages, so the count can become stale between requests. Scrolling is designed for sequential "load more" or infinite-scroll patterns where a total is rarely needed. If you do need a total count (for example, for a UI label like "showing 10 of 4,827 results"), call the `count` (Kotlin) or `getCount()` (Java) method on the query builder separately, keeping in mind that the value is a snapshot that may drift as the underlying data changes.

**REST cursor support.** For REST APIs that need to pass scroll state as an opaque string (for example, as a query parameter), `Window` provides `nextCursor()` and `previousCursor()` methods that serialize the scroll position to a cursor string. These convenience methods are gated by the informational flags: `nextCursor()` returns `null` when `hasNext()` is `false`, and `previousCursor()` returns `null` when `hasPrevious()` is `false`. This makes them safe to use directly in REST responses without additional checks. The underlying `nextScrollable()` and `previousScrollable()` methods remain available whenever the window has content, so server-side code can still follow a cursor even when the flags indicate no more results were seen at query time. To reconstruct a `Scrollable` from a cursor string, use `Scrollable.fromCursor(key, cursor)`. For details on supported cursor types, security considerations, and custom codec registration, see [Cursor Serialization](cursors.md).

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Serialize cursor for REST response
val cursor: String? = window.nextCursor()

// Client sends cursor back in next request
val scrollable = Scrollable.fromCursor(User_.id, cursor)
val next = userRepository.scroll(scrollable)
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Serialize cursor for REST response
String cursor = window.nextCursor();

// Client sends cursor back in next request
var scrollable = Scrollable.fromCursor(User_.id, cursor);
var next = userRepository.scroll(scrollable);
```

</TabItem>
</Tabs>

**Basic usage.** Pass a `Metamodel.Key` that identifies a unique, indexed column (typically the primary key) and the desired page size. The key determines both ordering and the cursor column. Fields annotated with `@UK` or `@PK` automatically generate `Metamodel.Key` instances in the metamodel. See [Metamodel](metamodel.md#unique-keys-uk-and-metamodelkey) for details.

> **Nullable keys.** If a `@UK` field is nullable and the default `nullsDistinct = true` applies, scroll methods throw a `PersistenceException` at runtime. Either use a non-nullable type, or set `@UK(nullsDistinct = false)` if the database constraint prevents duplicate NULLs. See [Nullable Unique Keys](metamodel.md#nullable-unique-keys) for details.

For repository convenience methods, see [Repositories: Scrolling](repositories.md#scrolling).

Use `scroll` as a terminal operation on the query builder for filtering, joins, or projections:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val window = userRepository.select()
    .where(User_.active, EQUALS, true)
    .scroll(Scrollable.of(User_.id, 10))
```

</TabItem>
<TabItem value="java" label="Java">

```java
var window = userRepository.select()
    .where(User_.active, EQUALS, true)
    .scroll(Scrollable.of(User_.id, 10));
```

</TabItem>
</Tabs>

:::warning Ordering is built in
The `scroll` method generates the ORDER BY clause from the key provided in the `Scrollable` (ascending for forward scrolling, descending for backward scrolling). Adding your own `orderBy()` call conflicts with the ordering that scrolling depends on, so Storm rejects the combination at runtime with a `PersistenceException`.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// Wrong: orderBy conflicts with scroll
userRepository.select()
    .orderBy(User_.name)          // PersistenceException at runtime
    .scroll(Scrollable.of(User_.id, 10))

// Correct: scroll handles ordering via the key
userRepository.select()
    .scroll(Scrollable.of(User_.id, 10))
```

</TabItem>
<TabItem value="java" label="Java">

```java
// Wrong: orderBy conflicts with scroll
userRepository.select()
    .orderBy(User_.name)          // PersistenceException at runtime
    .scroll(Scrollable.of(User_.id, 10));

// Correct: scroll handles ordering via the key
userRepository.select()
    .scroll(Scrollable.of(User_.id, 10));
```

</TabItem>
</Tabs>
:::

### Sorting by Non-Unique Columns

The single-key `Scrollable.of(key, size)` uses the cursor column as both the sort column and the tiebreaker, which means the column must contain unique values. When you want to sort by a non-unique column (for example, a timestamp or status), use the overload that accepts a separate sort column: `Scrollable.of(key, sort, size)`. This accepts a unique `key` column (typically the primary key) as a tiebreaker for deterministic paging, and a `sort` column for the primary sort order.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
// First page sorted by creation date ascending, with ID as tiebreaker
val window = postRepository.select()
    .scroll(Scrollable.of(Post_.id, Post_.createdAt, 20))

// Next page (cursor values are captured in the Scrollable automatically).
// nextScrollable() is non-null whenever the window has content.
// hasNext() is informational; the developer decides whether to follow the cursor.
val next = postRepository.select()
    .scroll(window.nextScrollable())

// First page sorted by creation date descending (most recent first)
val latest = postRepository.select()
    .scroll(Scrollable.of(Post_.id, Post_.createdAt, 20).backward())

// Previous page
val prev = postRepository.select()
    .scroll(window.previousScrollable())
```

</TabItem>
<TabItem value="java" label="Java">

```java
// First page sorted by creation date, with ID as tiebreaker
var window = postRepository.select()
    .scroll(Scrollable.of(Post_.id, Post_.createdAt, 20));

// Next page (cursor values are captured in the Scrollable automatically).
// nextScrollable() is non-null whenever the window has content.
// You can check hasNext() if you only want to proceed when more results
// were known to exist at query time, or follow the cursor unconditionally
// to pick up data that may have arrived after the query.
var nextScrollable = window.nextScrollable();
if (nextScrollable != null) {
    var next = postRepository.select()
        .scroll(nextScrollable);
}

// Previous page
var previousScrollable = window.previousScrollable();
if (previousScrollable != null) {
    var prev = postRepository.select()
        .scroll(previousScrollable);
}
```

</TabItem>
</Tabs>

The `Window` carries navigation tokens (`nextScrollable()`, `previousScrollable()`) that encode the cursor values internally, so the client does not need to extract cursor values manually.

The generated SQL uses a composite WHERE condition that maintains correct ordering even when `sort` values repeat:

```sql
WHERE (created_at > ? OR (created_at = ? AND id > ?))
ORDER BY created_at ASC, id ASC
LIMIT 21
```

As with the single-key variant, scrolling manages ORDER BY internally and rejects any explicit `orderBy()` call.

**Indexing.** For scrolling with sort to perform well, create a composite index that covers both columns in the correct order:

```sql
CREATE INDEX idx_post_created_id ON post (created_at, id);
```

This allows the database to seek directly to the cursor position and scan forward, giving consistent performance regardless of page depth.

### GROUP BY and Aggregated Projections

When a query uses GROUP BY, the grouped column produces unique values in the result set even if the column itself is not annotated with `@UK`. In this case, wrap the metamodel with `.key()` (Kotlin) or `Metamodel.key()` (Java) to indicate it can serve as a scrolling cursor:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val window = orm.query(Order::class)
    .select(Order_.city, "COUNT(*)")
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Order_.city.key(), 20))
```

</TabItem>
<TabItem value="java" label="Java">

```java
var window = orm.query(Order.class)
    .select(Order_.city, "COUNT(*)")
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Metamodel.key(Order_.city), 20));
```

</TabItem>
</Tabs>

See [Manual Key Wrapping](metamodel.md#manual-key-wrapping) for more details.

### Window Type Parameters

`Window<R, T>` is a record with two type parameters: `R` is the result type and `T` is the entity type from the FROM clause. It provides result content, cursor-based string navigation (`nextCursor()`, `previousCursor()`), and typed `Scrollable<T>` navigation tokens (`nextScrollable()`, `previousScrollable()`) for programmatic traversal.

The repository convenience method `scroll()` returns `Window<E, E>`. The query builder `scroll()` also returns `Window<R, T>`. For entity queries where `R` and `T` are the same type, `Window` carries `Scrollable<T>` navigation tokens.

For queries where the result type differs from the entity type (for example, selecting into a data class that combines columns from multiple sources), `Window` does not carry navigation tokens because Storm cannot extract cursor values from a result type it does not know how to navigate. In this case, `nextScrollable()` and `previousScrollable()` return `null` (even when the window has content), and `hasNext()` still works correctly as an informational flag. To continue scrolling, check `hasNext()` and construct the next `Scrollable` manually using cursor values from your result:

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
data class OrderSummary(val city: Ref<City>, val orderCount: Long) : Data

val window: Window<OrderSummary, Order> = orm.selectFrom(Order::class, OrderSummary::class) {
    """${Order_.city.id}, COUNT(*)"""
}
.groupBy(Order_.city)
.scroll(Scrollable.of(Order_.city.key(), 20))

// Navigation tokens are null because OrderSummary != Order.
// Construct the next scrollable manually from the last result.
// hasNext() is informational; the developer decides whether to follow the cursor.
val lastCity = window.content.last().city.id()
val next: Window<OrderSummary, Order> = orm.selectFrom(Order::class, OrderSummary::class) { ... }
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Order_.city.key(), lastCity, 20))
```

</TabItem>
<TabItem value="java" label="Java">

```java
record OrderSummary(Ref<City> city, long orderCount) implements Data {}

Window<OrderSummary, Order> window = orm.selectFrom(Order.class, OrderSummary.class,
    RAW."""SELECT \{Order_.city.id}, COUNT(*)""")
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Metamodel.key(Order_.city), 20));

// Navigation tokens are null because OrderSummary != Order.
// Construct the next scrollable manually from the last result.
// hasNext() is informational; the developer decides whether to follow the cursor.
var lastCity = window.content().getLast().city().id();
Window<OrderSummary, Order> next = orm.selectFrom(Order.class, OrderSummary.class, ...)
    .groupBy(Order_.city)
    .scroll(Scrollable.of(Metamodel.key(Order_.city), lastCity, 20));
```

</TabItem>
</Tabs>

## Pagination vs Scrolling Summary

| | Pagination | Scrolling |
|---|---|---|
| Request | `Pageable` | `Scrollable<T>` |
| Result | `Page` | `Window` |
| Method | `page(pageable)` | `scroll(scrollable)` |
| Navigate forward | `page.nextPageable()` | `window.nextScrollable()` |
| Navigate backward | `page.previousPageable()` | `window.previousScrollable()` |
