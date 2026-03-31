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
The tree of related entities loaded through `@FK` relationships in a single query using JOINs. When Storm loads a `User` that has `@FK val city: City`, it automatically joins the `city` table and returns a fully populated `User` with its `City` object. This eliminates the N+1 query problem. See [Relationships](relationships.md).

**Entity Lifecycle**
The set of callback hooks (`beforeInsert`, `afterInsert`, `beforeUpdate`, `afterUpdate`, `beforeDelete`, `afterDelete`) that fire around mutation operations. Implemented via the `EntityCallback<E>` interface, these hooks enable cross-cutting concerns like auditing and validation. See [Entity Lifecycle](entity-lifecycle.md).

**Hydration**
The process of transforming flat database rows into structured Kotlin data classes or Java records. Storm maps SELECT columns to constructor parameters by position, with no runtime reflection on column names. Hydration plans are compiled once per type and reused. See [Hydration](hydration.md).

**Inline Record**
A plain data class or record (without implementing `Entity`) that is embedded within an entity. Inline records group related fields (like an address or compound key) into a reusable structure. Their fields are stored as columns in the parent entity's table, not in a separate table. Also called an "embedded component." See [Entities](entities.md#embedded-components).

**Metamodel**
A set of companion classes (e.g., `User_`, `City_`) generated at compile time by Storm's KSP processor (Kotlin) or annotation processor (Java). The metamodel provides type-safe references to entity fields for use in queries, predicates, and ordering. See [Metamodel](metamodel.md).

**ORM Template**
The central entry point for all Storm database operations (`ORMTemplate`). Created from a JDBC `DataSource`, `Connection`, or JPA `EntityManager`, it is thread-safe and typically instantiated once at application startup. It provides access to entity repositories, query builders, and SQL template execution. See [First Entity](first-entity.md#create-the-orm-template).

**Projection**
A read-only data class or record that implements the `Projection<ID>` interface. Projections represent database views or complex query results defined via `@ProjectionQuery`. Unlike entities, projections only support read operations. See [Projections](projections.md).

**Ref**
A lightweight identifier (`Ref<T>`) that carries only the record type and primary key, deferring the loading of the full record until `fetch()` is called. Using `Ref<City>` instead of `City` in a foreign key field avoids the automatic JOIN, reducing query width when the related data is not always needed. See [Refs](refs.md).

**Repository**
An interface that provides database access methods for an entity or projection type. `EntityRepository<E, ID>` offers built-in CRUD operations; `ProjectionRepository<P, ID>` offers read-only operations. Custom repositories extend these interfaces with domain-specific query methods. See [Repositories](repositories.md).

**Scrollable**
A scroll request that captures cursor state for fetching a window of results. The scrolling counterpart of `Pageable`. Created via `Scrollable.of(key, size)` or obtained from `Window.next()` / `Window.previous()`, which are always non-null when the window has content. Supports cursor serialization for REST APIs via `toCursor()` / `Scrollable.fromCursor(key, cursor)`. See [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling).

**SQL Template**
Storm's template engine that uses string interpolation to embed entity types, metamodel fields, and parameter values into SQL text. Types expand to column lists, metamodel fields to column names, and values to parameterized placeholders. SQL Templates are the foundation of all Storm queries, including those generated by repositories. See [SQL Templates](sql-templates.md).

**Static Metamodel**
See [Metamodel](#metamodel) above.

**Storm Config**
A configuration object (`StormConfig`) that controls runtime behavior for features like dirty checking mode, entity cache retention, and template cache size. All settings have sensible defaults, so configuration is optional. See [Configuration](configuration.md).

**Window**
A window of query results from a scrolling operation. A `Window<R>` contains the result list (`content`), informational `hasNext` and `hasPrevious` flags (a snapshot at query time), and navigation tokens (`next()`, `previous()`) for sequential traversal. The navigation tokens are always non-null when the window has content; `hasNext` and `hasPrevious` are not prerequisites for accessing them, since new data may appear after the query. Also provides `nextCursor()` / `previousCursor()` for REST API cursor strings. See [Pagination and Scrolling: Scrolling](pagination-and-scrolling.md#scrolling).
