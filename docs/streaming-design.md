import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# Streaming: Design Notes

Storm streams a large result in two shapes: a result stream (`resultFlow`, `getResultStream()`), which is one open statement, and windows (`windows(size)`), which are one closed statement per window. A statement issued on a connection whose result stream still has rows to read is refused, on every database. This page records why the API has that shape, what else was considered, and why a flow in Kotlin is cold. The how-to is on [Batch Processing & Streaming](batch-streaming.md#streaming).

## The constraint is the wire protocol

A result stream leaves its unread rows on the server and pulls them as the caller consumes them. What the connection may do in the meantime is not Storm's choice; it is decided by the driver and the protocol beneath it, and they disagree:

| Database | Driver behaviour on a second statement while a stream is open |
|---|---|
| PostgreSQL, Oracle | Interleaves it. Each statement has a portal or cursor of its own. |
| MySQL | Connector/J throws: a streaming result is active on the connection. |
| MariaDB, SQL Server | The driver first reads the rest of the open result into application memory, then runs the second statement. The stream silently becomes a whole-table list. |
| H2, SQLite | Interleaves it. |

Inside a transaction every statement shares the transaction's connection, so a loop that reads a related record, fetches a reference or writes per row while a stream is open meets one of these three behaviours. The third is the worst: the code is correct on the test database, correct on the small tenant, and exhausts the heap on the large one without a single log line.

Reactive drivers do not change this. The R2DBC specification requires a result to be consumed before the next statement runs on the connection, and its drivers either fail or buffer, so a reactive API sits on the same constraint.

## What other frameworks do

The JDBC-based ORMs (JPA `getResultStream()`, Spring Data's `Stream` methods, jOOQ `fetchLazy()`) hand the stream over and leave the second statement to the driver; jOOQ's manual documents the MySQL case. Hibernate Reactive offers no streaming at all and returns lists. Android's Room and SQLDelight expose a `Flow<List<T>>` that re-emits the whole list on a table change, which is a change notification rather than a stream. None of them make the behaviour independent of the database.

## Options considered

**Refuse where the driver cannot interleave, allow where it can.** PostgreSQL and Oracle users would keep the natural pattern. But a loop then passes its tests on H2 and PostgreSQL and fails in production on MySQL or MariaDB, which is precisely the class of failure Storm's compatibility kit exists to remove. Rejected, also as an opt-in setting: a knob that makes behaviour depend on the dialect undoes the guarantee.

**Buffer the rest of the open result up to a limit, refuse past it.** This is what MariaDB does, with a cap. The outcome then depends on data volume, so it fails only in production and only at scale. Rejected.

**Read the stream on a connection of its own.** The stream would then read outside the transaction: its own writes invisible, a different snapshot, twice the pool pressure. Defensible only as an explicit choice for a read-only export, and not as what a stream does by default.

**Resume the stream as windows when a second statement arrives.** Start with one statement ordered by the key, and when the loop issues a statement, close the cursor and continue from the last emitted key in windows. The happy path stays one statement, but every stream would carry an `ORDER BY` on the key, only entity types with a key could stream at all, and the snapshot would change in the middle of the iteration without the caller asking for it, so a row updated in between may be seen twice or not at all. A silent change of consistency is worse than an explicit choice. Rejected.

**Server-held cursors** (`DECLARE ... WITH HOLD`, MySQL cursor fetch). Dialect-specific, and `WITH HOLD` materialises the result on the server. Rejected.

**No streaming, lists and windows only.** Simpler, but it gives up the one-statement snapshot, and a multi-million-row export is a real use case that windows serve worse. Rejected.

## The decision

Two explicit shapes, one rule everywhere.

- A **result stream** is one statement and one snapshot, accepts any query including a template, and holds its connection consume-only until it is read to its end or closed. Storm refuses any other statement on that connection meanwhile, with a message that names the open stream, the refused statement and the way out. The refusal is the same on every dialect, so the compatibility kit can assert it once and a loop behaves in production as it did in its tests.
- **Windows** are one closed statement per window, ordered by a unique key. Between windows the connection is free, so the loop may query, fetch references and write, and one batched write per window costs one statement rather than one per row. The price is the keyset price: a key, no `ORDER BY` of the query's own, a snapshot per window. `windows(size).rows()` in Kotlin and `windows(size).flatMap(Slice::stream)` in Java flatten the windows into one stream of rows for a loop that works row by row, still with the connection free at every row.

Keyset resumption is the only portable way to free the connection. Offset pagination is quadratic and unstable under concurrent writes, and every other option above is either dialect-specific or dependent on data volume.

## Why a Kotlin flow is cold

A `Flow` in Kotlin is cold by convention: nothing runs until it is collected, and each collection runs it again. Storm's `resultFlow` follows that convention. Building the flow runs nothing, so several flows may be built up front and collected one after the other, a flow handed to another function as a value holds no connection until that function collects it, a flow that is never collected leaks nothing, and a failure surfaces from `collect`, where the caller is reading, rather than from the line that built the flow. The consume-only rule applies only while a collection is under way, which is the only time a statement is actually open.

The Java `Stream` is deliberately not cold. It is the idiom of `getResultStream()` in JPA and of the other JDBC-based libraries, it is single-use, and the caller closes it with try-with-resources, so an open statement is visibly the caller's from the moment the method returns.
