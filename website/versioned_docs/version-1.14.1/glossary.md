# Glossary

This page defines key terms used throughout the Storm documentation.

---

**Dirty Checking**
The process of determining which fields of an entity have changed since it was last read from the database. Storm compares the current entity state against the observed state stored in the transaction context. Only changed columns are included in the UPDATE statement. Because entities are immutable, dirty checking is fast and requires no bytecode manipulation. See [Dirty Checking](dirty-checking.md).

**Entity**
A Kotlin data class or Java record that implements the `Entity<ID>` interface and maps to a database table. Entities support full CRUD operations (insert, update, remove) through repositories. They are stateless and immutable, with no proxies or hidden state. See [Entities](entities.md).

**Entity Cache**
A transaction-scoped cache that stores entities by primary key during a transaction. It avoids redundant database round-trips, skips repeated object construction during hydration, preserves object identity within a transaction, and tracks observed state for dirty checking. The cache is automatically cleared on commit or rollback. See [Entity Cache](entity-cache.md).

**Entity Graph**
The tree of related entities loaded through `@FK` relationships in a single query using JOINs. When Storm loads a `User` that has `@FK val city: City`, it automatically joins the `city` table and returns a fully populated `User` with its `City` object. The declared graph therefore costs one statement rather than one per relationship, and because nothing loads lazily, no query is ever issued behind the application's back. See [Relationships](relationships.md).

**Entity Lifecycle**
The set of callback hooks (`beforeInsert`, `afterInsert`, `beforeUpdate`, `afterUpdate`, `beforeUpsert`, `afterUpsert`, `beforeRemove`, `afterRemove`) that fire around mutation operations. An "after" callback observes what the calling method reports to its caller. Implemented via the `EntityCallback<E>` interface, these hooks enable cross-cutting concerns like auditing and validation. See [Entity Lifecycle](entity-lifecycle.md).

**Hydration**
The process of transforming flat database rows into structured Kotlin data classes or Java records. Storm maps SELECT columns to constructor parameters by position, with no runtime reflection on column names. Hydration plans are compiled once per type and reused. See [Hydration](hydration.md).

**Inline Record**
A plain data class or record (without implementing `Entity`) that is embedded within an entity. Inline records group related fields (like an address or compound key) into a reusable structure. Their fields are stored as columns in the parent entity's table, not in a separate table. Also called an "embedded component." See [Entities](entities.md#embedded-components).

**Metamodel**
A set of companion classes (e.g., `User_`, `City_`) generated at compile time by Storm's KSP processor (Kotlin) or annotation processor (Java). The metamodel provides type-safe references to entity fields for use in queries, predicates, and ordering. See [Metamodel](metamodel.md).

**ORM Template**
The central entry point for all Storm database operations (`ORMTemplate`). Created from a JDBC `DataSource`, `Connection`, or JPA `EntityManager`, it is thread-safe and typically instantiated once at application startup. It provides access to entity repositories, query builders, and SQL template execution. See [First Entity](first-entity.md#create-the-orm-template).

**Position**
The row a scroll request continues from, and on which side of it. A `Scrollable` states it through `after`, `before` or `from(cursor)`, and a `Window` hands it back inside `next()` and `previous()`. Like the cursor string that carries it between requests, a position is opaque: it says whether the request continues after or before the row, and the engine reads the row it names.

**Projection**
A read-only data class or record that implements the `Projection<ID>` interface. Projections represent database views or complex query results defined via `@ProjectionQuery`. Unlike entities, projections only support read operations. See [Projections](projections.md).

**Ref**
A lightweight identifier (`Ref<T>`) that carries only the record type and primary key, deferring the loading of the full record until `fetch()` is called. Using `Ref<City>` instead of `City` in a foreign key field avoids the automatic JOIN, reducing query width when the related data is not always needed. See [Refs](refs.md).

**Repository**
An interface that provides database access methods for an entity or projection type. `EntityRepository<E, ID>` offers built-in CRUD operations; `ProjectionRepository<P, ID>` offers read-only operations. Custom repositories extend these interfaces with domain-specific query methods. See [Repositories](repositories.md).

**SQL Log**
Storm's built-in visibility into the statements it executes, in three grains under one logger tree. Raising `st.orm.sql` reports each statement as it executes, with parameter values at `TRACE`; `st.orm.sql.perf` reports what a unit of work, such as one request or one scheduled task, cost the database as a single summary: how many statements ran, how long the database spent on them against how long the call took, and which statement carried the weight; `st.orm.sql.slow` reports each single execution whose database time exceeds a threshold, with its call site and how it compares to what its shape typically costs. Summaries and slow lines carry no parameter values below `TRACE`, so they are safe to log in production. See [SQL Logging](sql-logging.md).

**Scrollable**
A scroll request: an ordering, a window size and optionally the position to continue from. The scrolling counterpart of `Pageable`. Created via `Scrollable.of(key, size)`, refined with `sortBy`, `sortByDescending` and `descending()`, or obtained from `Window.next()` / `Window.previous()`, which are always non-null when the window has content. The position travels across a network boundary as a cursor string via `toCursor()` and `Scrollable.from(cursor)`. See [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling).

**Slice**
The shape a `Page`, a `Window` and a plain slice share: content, `hasNext` and `hasPrevious`, and iteration over the content. `slice(pageable)` returns the plain one, a page without the count query: the same `Pageable` as `page`, one row beyond the page size to decide `hasNext`, `hasPrevious` from the page number, navigated through the request's `next()` and `previous()`. The read for a "load more" that needs no total, and for a query without a unique key, where scrolling is not possible.

**SQL Template**
Storm's template engine that uses string interpolation to embed entity types, metamodel fields, and parameter values into SQL text. Types expand to column lists, metamodel fields to column names, and values to parameterized placeholders. SQL Templates are the foundation of all Storm queries, including those generated by repositories. See [SQL Templates](sql-templates.md).

**Statement Origin**
Classifies what caused a statement to execute: `DIRECT` for statements the code asked for through a repository, query builder, or template, and `FETCH` for statements resolving a reference through `Ref.fetch()`. The origin appears on the `storm.origin` metric tag, in SQL log summaries, and on captured statements in tests, making the cost of resolving references measurable on its own. See [SQL Logging](sql-logging.md#fetches).

**Static Metamodel**
See **Metamodel** above.

**Storm Config**
A configuration object (`StormConfig`) that controls runtime behavior for features like dirty checking mode, entity cache retention, and template cache size. All settings have sensible defaults, so configuration is optional. See [Configuration](configuration.md).

**Window**
A window of query results from a scrolling operation. A `Window<R>` is a `Slice`: it iterates over its content, carries informational `hasNext` and `hasPrevious` flags (whether rows existed after and before the window at query time), and navigation tokens (`next()`, `previous()`) that continue after and before it. Every window is in the request's sort order, the one reached through `previous()` included. The navigation tokens are always non-null when the window has content; the flags are not prerequisites for following them. See [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling).