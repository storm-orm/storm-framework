# Changelog

All notable changes to Storm are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/), and Storm follows
[Semantic Versioning](https://semver.org/).

Releases are tag-driven and published to
[Maven Central](https://central.sonatype.com/namespace/st.orm) (`st.orm`) and,
for the CLI, to [npm](https://www.npmjs.com/package/@storm-orm/cli)
(`@storm-orm/cli`). Full release notes for every version are on the
[GitHub Releases](https://github.com/storm-orm/storm-framework/releases) page.

## [Unreleased]

- Added `@EntityCallbacks`, which names the `EntityCallback` types that apply to an entity. Storm creates each of them through its public no-argument constructor, once per entity type, and applies it wherever the entity is written, through the Spring starter's template, the Ktor plugin's template and a standalone one alike, so a callback that belongs to the entity's own definition needs no registration in any environment; registering an instance on a template used to be the only form, which meant wiring the same audit rule once per framework. A declared callback applies to the annotated entity whatever its own type parameter would otherwise match, fires before the callbacks registered on the template, in the order the annotation lists them, and is replaced by a registered instance of its type, so a callback that moves from declared to registered never fires twice on the way. Both failures a declaration can carry are refused when the repository is created: a type parameter that does not cover the entity names both types, and a class Storm cannot create names the ways to register an instance instead, which is where a callback that needs collaborators belongs. A declared callback cannot be scoped to some of an application's templates, since the entity carries the declaration; such a callback is registered rather than declared. The GraalVM feature and the Spring AOT hints register the no-argument constructor, so a declared callback survives into a native image.
- Storm reports what it registers of its own accord at `DEBUG`: the callbacks resolved for each entity type under `st.orm.callback`, each marked as declared on the entity or registered on the template, the default converters it discovered under `st.orm.converter`, and how many types each type index yielded under `st.orm.discovery`. Repository registration already reported itself, under `st.orm.spring.repository` in Spring and the application logger in Ktor.
- Added `ORMTemplate.current()`, the template of the operation that fired the entity callback running on this thread. A callback receives the entity and nothing else, so one that performs database work of its own used to capture a template at construction or inject one as a bean, and nothing tied that template to the operation: a callback instance registered on two templates wrote to whichever database it had been handed rather than the one the write went to, and a template built for the callback alone carries transaction machinery of its own, refused inside a Storm block and quietly running outside a Spring-managed transaction. The accessor hands the callback the template the write is running on, so its work goes to the same database, over the same connection, inside the same transaction, for reads as well as writes, and follows whichever template fired it. It is available while a callback executes and is refused elsewhere, naming where it is valid, a commit callback included: that runs once the dispatch has returned, so work registered for after the commit captures the template while the callback runs. The Java, Kotlin and core templates each return their own type, and the re-entrancy guard covers the work as it always has.
- A scroll window reads its cursor values from the row without selecting a column twice. A sort field or key the select list already carries is read where it is, and only a column the list lacks is appended, so an entity or ref scrolled by its own key runs the plain select with its ORDER BY and nothing more; the statement reports where each cursor value sits through `Sql.cursorColumns()`. An inline record key is read from the row like every other key, so refs and custom select types scroll by one too, and the key is built from its columns as a row is: a key component holding an entity or projection is refused with the message naming it, since its columns cannot rebuild the record and a cursor string could not carry it either; reference it as a `Ref`, or scroll by a single-column key. The type a reference sort field's cursor value decodes to is derived once per field rather than on every window. Both join the Technology Compatibility Kit: every dialect scrolls a compound key on entities and refs in both directions, and asserts that a window over an entity's key runs the plain select list and a ref's sort field is appended once.
- A reference named in a query's fetch plan comes back loaded even when the row already carries an unloaded reference to the same record, built while the row was mapped: a `CapitalCity` whose `Country` holds the capital as an entity, with the capital referring back to its country, resolves `fetch(CapitalCity_.country)` to a reference whose `isLoaded()` is true and whose `getOrThrow()` returns the record without a query. Interning never trades a loaded reference for an unloaded one: the loaded reference is the canonical instance from the moment it is interned, so later rows of the same record share it, while a record built earlier with the unloaded instance keeps it, since its own field was not in the plan.
- The Gradle plugin refuses a KSP that does not pair with the project's Kotlin version, with the paired plugin line to declare and where to put it, in the shape of the failure for a missing KSP. The metamodel processor runs inside whichever KSP the build resolved, and on Kotlin 2.0 and 2.1 the bundled KSP fails inside the Kotlin Gradle plugin with a linkage error that names neither KSP nor Kotlin; a subproject that applies KSP without a version inherits the bundled one from a root classpath that carries `st.orm`, so that build used to run the wrong KSP without a word. In a multi-project build the paired KSP is declared once in the root project's plugins block with `apply false` and applied in each subproject without a version; a declared version beats the bundled preference. Kotlin 2.2 joins the automatic path: the bundled KSP compiles a metamodel there, which a functional test settles, so a Kotlin 2.2 project needs no KSP line, and the refusal covers Kotlin 2.0 and 2.1.
- A transaction states the read-only mode on its connection once, and only when it has to. A frame that opens a connection with a requested mode reads the mode the connection arrived with, sets its own only when the two differ, and restores what it changed before the connection returns to the pool; a frame that requests no mode, the default under the Java and the Kotlin API alike, makes no read-only call at all. A read-write transaction therefore makes no call for the mode, a read-only transaction one call each way, and a driver that carries the flag to the server pays no round trip for a mode the connection already has. The call-sequence test under Storm's own JDBC transactions states the resulting sequence, with a connection that arrives read-only among its cases.
- A joined block runs at the isolation level of the transaction it joins, and says so. A `REQUIRED`, `SUPPORTS`, `MANDATORY` or `NESTED` block that states a stricter level than the transaction it joins is refused with an `IllegalTransactionStateException`, a `PersistenceException` that `MANDATORY` without a transaction and `NEVER` inside one now raise as well, naming both levels and the ways out, `REQUIRES_NEW` among them, as is a stated level inside a transaction running at the database's default, whose level Storm does not know; a level equal to or lower than the transaction's joins, since the transaction already honours it. The entity cache follows the transaction owning the connection: a joined block inside a `REPEATABLE_READ` transaction returns cached instances whether or not it states a level, where it used to follow the block's own declaration and could serve cached instances inside a `READ_COMMITTED` transaction, and a block outside any transaction returns fresh instances whatever it states. The same rules hold under Spring-managed transactions, `@Transactional(isolation = ...)` included, and a manager configured with `validateExistingTransaction` has its refusal reported against the block the same way.
- A block that declares `readOnly = true` inside a read-write transaction holds its promise: the writes issued inside it, from the blocks it encloses included, are refused with `ReadOnlyTransactionException`, where they used to go through because the mode was read off the transaction owning the connection alone. A read-write block inside a read-only transaction keeps joining it and is refused on write, not at the join, so a read-write helper called from a read-only report that never writes keeps working.
- A Kotlin result flow is cold, as its documentation states: `resultFlow` on a query builder, and `resultFlow`, `getResultFlow` and `getRefFlow` on a template query, run their statement when collection starts rather than when the flow is built, read the rows as they are emitted, and close the statement when collection completes, fails or is cancelled. A flow that is built and not yet collected holds nothing, so a caller may build several inside one transaction and collect them one after the other, a flow that is never collected leaves the connection free, and each collection runs the query again. The flow used to open its result stream as it was built, so building a second one, or issuing any statement, inside a transaction before the first was read to its end was refused as a statement on a connection with an open stream, which is how two flows passed as the arguments of one call failed.
- Upgrade note: a joined block stating a stricter isolation level than its enclosing transaction, or any level inside an enclosing transaction at the database's default, now fails where it used to run silently at the enclosing level, and a default set with `setGlobalTransactionOptions` or `withTransactionOptions` counts as stated. State the level on the outermost block, open the inner block with `REQUIRES_NEW`, or leave its level unstated. A refused block fails as a joined block does, so the transaction it joined rolls back with it.
- Upgrade note: `readOnly = false`, spelled out, is now a request for read-write rather than the default it used to coincide with. Such a transaction reads the connection's mode and lifts a read-only one, so a pool that hands out read-only connections is overridden by it and honoured by a transaction that leaves the mode unspoken. Leave the argument out where no mode is meant; an argument-less `setGlobalTransactionOptions()` resets the global default to no mode.
- Added `rows()` on a Kotlin `Flow<Window<R>>`: `windows(size).rows()` reads the windows as one flow of their rows. Each window is fetched by a statement that has closed before its rows are emitted, so the connection is free at every row, where a collector of `resultFlow` holds it consume-only until the last row; memory stays bounded by the window size and the rows arrive in the windows' key order. It is the one-token switch for a loop over `resultFlow` that the stream guard refuses because it queries, fetches or writes per row, and the guard's message names it, with the Java form `windows(size).flatMap(Slice::stream)`. The batched stream reads, `selectById(Stream)` and `selectByRef(Stream)` on the repositories, document what they do: no query runs until the stream is consumed, each batch's query running as the stream reaches it.
- Upgrade note: a result flow's query runs where the flow is collected. A failure surfaces from `collect` rather than from the line that built the flow, a flow collected twice runs its query twice, and a flow built inside a transaction but collected outside it reads outside the transaction, on a connection of its own; collect it inside the block that should cover it. `resultStream` and `getResultStream()` keep running their statement when they are called, as their documentation states, since a stream is a resource the caller closes.
- Each dialect quotes the words its database reserves, taken from the database where it lists them, `pg_get_keywords()` on PostgreSQL, `information_schema.KEYWORDS` on MySQL and MariaDB, `V$RESERVED_WORDS` on Oracle and the parser's keyword tokens on H2, and from the documentation for SQL Server and SQLite. The lists used to be the SQL:2016 reserved words and a few of each database's own, so a name such as `desc`, `rank`, `key`, `number` or `returning` reached the database unquoted and the statement failed: PostgreSQL missed 9 of its reserved words, MySQL 70, MariaDB 72, Oracle 15, SQL Server 38 and SQLite 4. MariaDB has a list of its own rather than MySQL's, and the words MySQL 9, MariaDB 12.3 and SQL Server 2025 reserve are on the lists. On H2 and Oracle a dialect quotes nothing else: both store an unquoted name in upper case, so quoting a word they accept, as the SQL:2016 list did for `position`, `date`, `time`, `timestamp`, `result`, `language`, `value` and around two hundred more, made the lower-case name case-sensitive, and an entity failed with a missing column against a table created without quotes. A dialect recognises a keyword whatever the JVM's default locale, where `limit` upper-cased to `LİMİT` under a Turkish locale and went unquoted. The Technology Compatibility Kit reads each database's list where it publishes one and fails on a reserved word the dialect leaves unquoted, so a newer test image surfaces the words its version adds; on H2 and Oracle it also checks that the database refuses every word the dialect quotes, and every dialect reads and writes an entity with a `position` and an `order` column.
- Upgrade note: on H2 and Oracle, a name the database does not reserve is no longer quoted, so a column created quoted in lower case to match the quoting Storm used to apply, such as `"timestamp"` or `"position"`, no longer matches the name Storm renders. Recreate the column without quotes, or keep it and set `escape = true` on its `@DbColumn`.
- A statement issued after its block's deadline is refused with `TransactionTimedOutException` before it reaches the database. A block past its deadline can only complete with that exception, yet each further statement used to run with a one-second query timeout, so a transaction of many short statements ran on past its deadline to the end of its work, holding its locks, only to roll it all back at completion, and a block outside a transaction committed the statements it issued after its deadline and then reported the timeout. The time left is rounded up to whole seconds, so a statement with less than a second left still runs, bounded by that second. A block whose deadline passed before its first statement refuses that statement too, under Storm's own JDBC transactions and Spring-managed ones alike: such a block opens its transaction with the zero seconds it has left, and the Spring bridge reads a timeout of zero as a deadline that has already passed, as Storm's JDBC transactions and Spring's own managers do, where it used to read it as no timeout and let the block run to completion as though it had none.
- Upgrade note: a timeout surfaces from the first statement issued after the deadline rather than from the block's completion, so the code between that statement and the end of the block no longer runs. Under Spring-managed transactions, a block whose deadline passes before its first statement, or that states `timeoutSeconds = 0`, fails with `TransactionTimedOutException` where it used to run without a timeout.
- Added a list form of every "after" entity callback: `afterInsert(List)`, `afterUpdate(List)`, `afterUpsert(List)` and `afterRemove(List)` on `EntityCallback`. Storm always calls the list form, a batch write with the entities of one batch in the order they were written and a single-entity write with a list of one; a `*AndFetch` call and a write set pass each type's entities as one list once the rows have been read back, and a stream passes them a batch at a time. By default the list form calls the single-entity form for each entity, so existing callbacks see every entity as before. A callback that writes to the database itself overrides the list form and handles a batch in a fixed number of statements, where the single-entity form cost it a statement per entity. An upserted batch reaches `afterUpsert(List)`, or `afterInsert(List)` when the callback overrides neither upsert form, so an insert callback covers the upsert path in its list form as it does in its single-entity form.
- Upgrade note: where several callbacks apply to one batch, each now receives the whole batch before the next one does, where the callbacks used to take turns entity by entity.

## [1.14.1] - 2026-09-12

A patch release around two fixes. A result stream keeps its connection to itself on every database, and the keyset reads are adjusted so that windows, the shape that leaves the connection free, can take a stream's place wherever a loop needs the database. A read-only transaction is a promise Storm holds on its own side rather than leaving to the driver: the writes it recognises are refused before they reach the database, and the transaction opens read-only on the server where the driver keeps the flag to itself.

- A result stream holds its connection consume-only until it is read to its end or closed, on every database. Inside a transaction the stream and every other statement share the transaction's connection, and what the driver does with a second statement differs: MySQL Connector/J rejects it, MariaDB Connector/J and the SQL Server driver first read the rest of the open result into memory, which turns a bounded stream into a whole-table list without any signal. Storm now refuses the statement itself, with a `PersistenceException` naming the open stream, the refused statement and the windows form, so a loop that passes its tests on H2 behaves the same in production on any dialect. A `Ref.fetch()` from inside the loop is named as such, with the fetch plan as the fix.
- Added `windows(size)` and `windows(scrollable)` to the query builders and repositories: the query runs in keyset windows over the primary key, or the key of the `Scrollable`, each window one closed statement, returned as a `Flow<Window<R>>` in Kotlin and a `Stream<Window<R>>` in Java. Between windows the connection is free, so the loop may query, fetch references and write, inside one transaction or with a transaction per window; a batched write per window costs one statement rather than one per row. The stream carries no database resource and needs no closing, and each window's `next()` token or cursor string resumes the iteration after it. A compound primary key is read from the mapped record, and a start position before a row is refused, since windows always continue after each other.
- For windows to resume and to run in either direction, a `Scrollable` states its ordering and its position as two things: the sort fields, in any number and each in its own direction, the key that breaks ties, with `descending()` for a newest-first key, and optionally the row to continue after or before. `backward()` is gone; `previous()` navigates, and every window comes back in the request's sort order, the one reached through `previous()` included. `hasNext` and `hasPrevious` say whether rows exist after and before the window. The sort and key values are read from the row alongside the result, so refs, projections read as another type and custom select types navigate like entities, and `scrollRef` joins `pageRef` on the repositories. Sort fields must not allow NULL values, checked the way the key is.
- A cursor string carries the position only, an opaque `Position` that says on which side of the row the request continues, under a fingerprint of the ordering. The ordering and the size stay in the request, so `Scrollable.of(key, size).sortBy(field).from(cursor)` replaces `fromCursor`, a client may ask for another size on the next request, and the `st.orm.scrollable.maxSize` property goes away. A refused cursor, malformed, from an earlier format, issued for another ordering or codec registry, or carrying a value of the wrong type, throws `InvalidCursorException`, a `PersistenceException`, so a web layer maps one exception to its "start over" response; a cursor issued before 1.14.1 is refused the same way.
- The offset reads line up with the windows. `Page` navigates with `next()` and `previous()`, which replace `nextPageable()` and `previousPageable()`, and `previous()` is `null` on the first page, on `Page` and on `Pageable`. `scroll(int)` is `slice(pageable)`, a page without the count query: the same `Pageable` as `page`, one row beyond the page size to decide `hasNext`, `hasPrevious` from the page number, and the request's `next()` and `previous()` to navigate; `slice` and `sliceRef` join `page` and `pageRef` on the repositories. `Slice` stays the shape `Page` and `Window` share and iterates over its content, so a loop reads `for (user in window)`, and `Order` is a top-level type that `Pageable` and `Scrollable` share.
- An offset read without an ordering of its own, such as `page(0, 20)`, works on SQL Server, whose `OFFSET` and `FETCH` are only valid after an `ORDER BY`: the dialect adds a constant ordering there, and the read stays as it was on the other databases.
- The reads in parts join the Technology Compatibility Kit: every dialect runs the keyset predicate with mixed directions, the previous-window order and flags, refs and reference sort fields, cursor round trips, slices, and pages with and without their count query.
- Read-only transactions join the Technology Compatibility Kit: every dialect runs a read-only transaction, a `REQUIRES_NEW` and a `NOT_SUPPORTED` frame writing on a connection of their own inside it, a joined `REQUIRED` frame that cannot lift the enclosing mode, and the connection leaving the transaction read-write for its next borrower. The refusal of a write in a read-only transaction is asserted where the driver carries the flag to the server, PostgreSQL, MySQL and MariaDB; the H2, SQL Server and Oracle drivers keep the flag to themselves, and the SQLite driver fixes the mode at connect time, so no read-only transaction opens on a pooled connection there. Two call-sequence tests state what Storm asks of a connection for the mode, under its own JDBC transactions and under Spring-managed ones.
- A write inside a read-only transaction is refused before it reaches the database. An `INSERT`, `UPDATE` or `DELETE`, through a repository or as a template, executed while the transaction owning the connection is read-only throws `ReadOnlyTransactionException`, a `PersistenceException`, on every database alike, where the outcome used to depend on the driver: MariaDB Connector/J carries the flag to the server only from 3.5.10, and the H2, SQL Server and Oracle drivers keep it to themselves, so the write executed and committed without a signal. The mode is that of the transaction owning the connection, so a joined `REQUIRED` block inside a read-only transaction is read-only whatever it declares, a `REQUIRES_NEW` or `NOT_SUPPORTED` block has its own, and a Spring-managed `@Transactional(readOnly = true)` is honoured the same way. A statement of undefined operation, such as a stored procedure call or a `MERGE`, still reaches the database, which refuses it where the driver carries the mode; the Technology Compatibility Kit asserts both layers apart. Read-only transactions keep committing, since rolling them back would swallow a leaked write instead of surfacing it.
- A read-only transaction is opened read-only on the server where the driver does not do it. `SqlDialect.readOnlyTransactionStatement()` names the statement, and Storm sends it as the first statement of every read-only transaction it opens, under its own JDBC transactions and under Spring-managed ones alike; the Oracle and MariaDB dialects return `SET TRANSACTION READ ONLY`, since the Oracle driver keeps the connection's flag to itself and MariaDB Connector/J carries it only from 3.5.10, while the PostgreSQL and MySQL dialects leave it to their drivers, which already carry the flag, rather than spend a round trip repeating it. Oracle joins the databases whose refusal the Technology Compatibility Kit asserts. Inside a Spring transaction Storm did not open, the transaction manager's `enforceReadOnly` setting covers the same ground.
- The Gradle plugin applies KSP itself, so `plugins { kotlin("jvm"); id("st.orm") }` is the entire Kotlin setup on Kotlin 2.3 and newer. The bundled KSP version is only preferred, so a KSP version the build declares wins the classpath, and an already applied KSP is left untouched; Kotlin 2.0–2.2 keep applying their paired KSP builds explicitly, guided by the same instructive failure as before. The Gradle property `storm.autoApplyKsp=false` opts out of the automatic application.
- Upgrade note: with `st.orm` on a root project's classpath, a subproject that declares `id("com.google.devtools.ksp") version "..."` now fails resolution with "the plugin is already on the classpath". Drop the version there: the subproject inherits the classpath's KSP, and Storm still defers to it. On Kotlin 2.0 and 2.1 the inherited KSP is the bundled one, which does not pair with those Kotlin lines; declare the paired version once in the root project's plugins block with `apply false`, which beats the bundled preference.
- A suspending `transaction { }` or `withTransactionOptions { }` whose coroutine context carries no scoped options inherits the defaults of the calling thread, set with `withTransactionOptionsBlocking`, before falling back to the global ones, as the blocking variant already did. A coroutine bridged from a servlet thread with `runBlocking` therefore follows what a filter declared for the request, such as `readOnly = true` for a GET, without the application carrying the declaration across itself. Scoped options in the coroutine context keep winning over the thread's, and explicit arguments over both; a bridge that switches dispatchers before its first transaction pins the thread's defaults with an argument-less `withTransactionOptions { }`.

## [1.14.0] - 2026-09-02

A quality release: a smaller, more coherent API, and SQL that is correct on every dialect Storm supports rather than on the permissive ones, held there by a Technology Compatibility Kit that every dialect runs.

- A join widens the query: from the join onward every clause accepts paths from any entity in the query, so referencing a joined table needs no separate method. `whereAny`, `whereAnyRef`, `whereAnyBuilder`, `havingAny`, `groupByAny`, `orderByAny`, `orderByDescendingAny`, `andAny` and `orAny` are removed, and `and` / `or` inherit the query root. A path on an entity the query does not carry fails when the query is built, naming the entity, the root, and the paths that pin the table where it appears more than once.
- Added `widen()` and `narrow(rootType)`, the two directions made explicit; renamed `typed(pkType)` to `typedId(pkType)`; `fetch(...)` comes right after `select()`, before any join, enforced at compile time. Kotlin's `select { }` and `delete { }` blocks are widened from the start and carry the chained builder's clause vocabulary.
- A grouping states an identity, and the generator emits the columns that express it. `groupBy(User_.city)` emits `GROUP BY c.id` on PostgreSQL, MySQL, SQLite and H2, and every column of the referenced key on SQL Server and Oracle, which do not resolve functional dependency from a key. The relationship, the key beyond it, the root's own key and a reference all state the same identity; any other path groups by its value. A grouping that determines nothing is refused when the query is built, on every dialect rather than only the ones that would notice.
- Every dialect runs the same Technology Compatibility Kit. The new build-time `storm-dialect-tck` module holds 146 conformance tests over entity repository, polymorphic, schema validation and multi-column expression behavior, and all seven dialects run all four of them; fourteen behaviors used to run on H2 alone, the most permissive dialect in the set, among them the key chains where a key is a reference the mapper has to flatten rather than a column. Where dialects legitimately differ they state a capability once and pin their own statement text, and a drift guard fails naming any statement a dialect can reach but has not pinned. A dialect that cannot do something demonstrates that it refuses cleanly rather than being silently exempt.
- The engine leaves the application's compile classpath. `storm-core` is `provided` on the library modules and `runtime` on the Spring Boot starters, so completion and imports offer exactly one `EntityRepository`, one `ORMTemplate`, one `QueryBuilder`: the facade's. Creating a template without the engine on the runtime classpath names the missing `st.orm:storm-core` dependency instead of surfacing as a `NoClassDefFoundError` at the first statement.
- The integration contract moves to `st.orm.spi` in storm-foundation: `ExceptionMapper`, `ExceptionContext`, `SqlOperation`, `QueryObserver`, `QueryContext`, `StatementOrigin`, `SqlCommenter` and the cursor codecs; `SqlTemplateException` joins `PersistenceException` in `st.orm`. Transaction bridging, `RefFactory` and the dialect surface stay engine SPI in `storm-core`. `QueryTemplate.dialect()` is removed from the Java API, the facade builders drop `connectionProvider` and `transactionTemplateProvider` in favor of `SpringOrmTemplate.builder(dataSource, beanFactory)` and `springOrmTemplateBuilder(dataSource, beanFactory)`, `QueryObserver.onTransaction` receives `st.orm.TransactionOptions`, and `@StormTest` injects `SchemaValidation` rather than the engine's validator.
- A placeholder swallowed by a string literal is refused. A value interpolated inside quotes renders as the literal text `'?'`, which is valid SQL, so the value binds to the position of the next placeholder and every parameter after it shifts; where the count balances, the statement runs against the wrong arguments and returns results that look ordinary. A statement that binds more positional parameters than it exposes placeholders is now refused when it is built, and the Kotlin compiler plugin reports it in the editor where it is written. `inlineParameters` remains the explicit opt-in for inlining.
- The SQL log is configured per log and named after the two loggers it writes to: `storm.sql-log.performance.*` for the performance log (`st.orm.sql.perf`), which says what a unit of work cost the database, and `storm.sql-log.slow.*` for the slow statement log (`st.orm.sql.slow`), which names the execution that cost too much. The old flat keys are removed rather than mapped. The Ktor DSL, the HOCON keys, the system properties and the Spring types follow: `StormPerformanceLogFilter` and `StormPerformanceLogEntryPointPostProcessor`.
- Added the slow statement log: each execution whose database time exceeds a threshold reports at `WARN`, carrying the statement, the rows, and how the execution compares to what its shape typically costs (`typically 6.0 ms, 306x`) and binds (`parameters 32 (typically 3)`). Values render at `TRACE` only and lines are rate-limited per shape. The threshold derives from the performance log when it has none of its own. Database time is measured to the statement's return everywhere, so the summary, `SqlCapture.duration` and the slow line agree.
- The SQL log is retunable while the application runs: `SlowStatementLog.threshold(Duration)` and `limit(int)` land on the next execution, and the performance log reads its settings per unit of work. Where the actuator is on the class path, the `storm` endpoint reads and sets both halves (`GET` / `POST /actuator/storm`).
- `@StormTest` runs each test inside a database transaction that is rolled back afterwards, so tests no longer observe each other's writes. Transaction blocks demarcate with savepoints inside it; `rollback = false` opts a class out.
- `@StormTest` and `@DataStormTest` run on the database the application deploys on: `database = POSTGRESQL` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`) starts a Testcontainers-managed container once per JVM and gives each test class a freshly created database inside it. The database's Testcontainers 2 module and JDBC driver stay out of `storm-test`'s dependencies, and a test that names a container database fails naming both when one is missing. `TestDatabase.POSTGRESQL.container()` exposes the shared container, and `createDatabase()` a database of your own.
- The public API is JSpecify null-marked, and the jakarta annotations are gone from Storm's signatures, so `jakarta.annotation-api` is no longer a dependency. JSpecify itself is optional: compilers and analysis tools read the annotations from bytecode. Kotlin callers get real `T` / `T?` types where the Java surface used to be platform types, and the generated nullable metamodel chain compiles on Kotlin 2.1+.
- `Projection<ID>`'s type argument is a checked contract instead of a phantom parameter: `ID` is the projection's row identity type, which for a foreign-key-typed primary key is the referenced table's key. Record validation rejects a declaration the record contradicts, and the rule that a foreign key must not be an auto-generated primary key applies to entities only.
- The implementation is sealed: `st.orm.core.template.impl` and `st.orm.core.repository.impl` are exported to Storm's own modules only, and storm-kotlin's `impl` packages are `internal` throughout, including the `Flow` operators that collided with their kotlinx.coroutines namesakes. All five Kotlin modules compile in explicit API mode; the coroutine-aware SQL log recording the Ktor plugin shares is the one exception, behind `@InternalStormApi`.
- Added `OrmTemplateFactory` to the Spring Boot starters: one bean that composes a fully integrated `ORMTemplate` wherever the application defines its own template beans. Failure translation follows `storm.exception-translation.enabled` per data source, observations resolve their conventions from each data source's JDBC URL and report the template's name as `storm.database`, and a customize block applies application-specific composition without touching the integration SPI.
- The Ktor plugin closes its composition gaps against the starters: `storm.observations.semanticConventions = otel` selects the OpenTelemetry conventions per database, and a `customize` slot applies application-specific composition after the integration is wired. Observer composition is shared with the starters through `QueryObservers` in storm-micrometer.
- Storm-initiated transaction blocks resolve the Spring manager that owns their data source, covering `JdbcTransactionManager`, a `JpaTransactionManager` backed by it, and a `JtaTransactionManager` where no resource-bound manager claims it. An option a manager refuses is reported against the option passed to the block, and a connection that arrives with auto-commit disabled fails naming the two possible causes and the fix.
- What a transaction block joins, and which blocks share a connection, follows from the block structure rather than from what happens to be bound at the time. A `REQUIRED` block inside a `NOT_SUPPORTED` or `NEVER` block opens a transaction of its own, `MANDATORY` and `NEVER` are checked against the block the enclosing code declares, and data source consistency is checked per physical transaction, so an audit write can go to a second database from inside a transaction on the first.
- Added `manualCommitConnections()`: a template declares that its `DataSource` hands out connections with auto-commit already disabled, and the non-transactional paths manage auto-commit accordingly.
- The `and` and `or` combinators root at their operands' least common root, so a cross-root conjunction is one clause rather than an escalation to `whereBuilder { }`. On a narrow builder the combination is unsatisfiable and is rejected at the call site, at compile time.
- The Java and Kotlin facades close their remaining parity gaps. Kotlin's path-based `where` and `whereRef` clauses accept `Navigable` paths, so navigation-only nodes reached through a `Ref` compile in both languages; Java's `where(path, record)` bounds the record by `Data`. Kotlin's `findRefBy` drops two unused type parameters, `findAllRefBy(field, values)` accepts any value type, and `Templates` gains the named `param(name, Calendar, TemporalType)` variant. `hasOrderBy()` is no longer public API, and the two-argument `selectFrom(fromType, selectType)` renders the select list from the FROM table, so `selectType` may be any record shape over those columns.
- The `select { }` and `delete { }` blocks add the remaining clause forms: `where(records)`, `where(path, records)`, `where(path, operator, values)`, `whereRef(path, refs)` and the descending order template. `whereRef` works inside `whereBuilder { }` too.
- Transaction observations go through an observation convention, `StormTransactionObservationConvention`, the way query observations always did, and the Ktor plugin forwards them. Query observations carry the shape identity as the low-cardinality `storm.shape` key value, cached per compiled template. The shape identity uses the full 64 bits it is declared with and renders as sixteen hex digits.
- Both metamodel processors reject a cycle of non-Ref foreign keys at compile time, naming the cycle and the fix, and converge on one contract: the Java processor generates the `<Type>NullableMetamodel` chain variant, and KSP sources components from the primary constructor, contributes abstract properties only for sealed interfaces, and escapes keyword-named properties. The Java processor registers with Gradle as an aggregating incremental processor, and the Gradle plugin wires it into every source set, so entities in test sources get a metamodel.
- The `@DataStormTest` slice imports every auto-configuration the starters register in production, verified by a parity test, and provides `JdbcTemplate`, `JdbcClient` and Testcontainers service connection support.
- `StormConfig.sqlShapingKeys()` exposes the configuration keys whose values affect the generated SQL, and the template cache key includes exactly these. `SqlLog` carries the diagnostics API only: summary rendering lives in `SqlLogRenderer` and call-site capture in `CallSiteCapture`, both internal, and the hydration shape is gone from summary rows.
- The deliberately paired artifacts, `storm-java21` / `storm-kotlin`, the two Spring Boot starters, `storm-jackson2` / `storm-jackson3` and the two metamodel processors, ship the same fully qualified names, one half per class path. Each half bans its twin through a maven-enforcer rule.
- Fixed `getResultCount()` counting the rows a select returned instead of executing a count query; page totals are inferred from it and `QueryBuilder.append` is removed.
- Fixed grouping by a reference resolving to its own column rather than to the referenced table's key.
- Fixed `@GenerateMetamodel` living where the processors could not find it, Java record entities missing from the type index, and a missing type index passing unreported.
- Fixed Storm-initiated transaction blocks failing to find a `JpaTransactionManager`: matching is JPA-first, ambiguity fails fast naming the candidates, and the auto-configuration orders after the JDBC and Hibernate managers.
- Fixed an explicitly set dialect being dropped back to discovery by `withConfig`, and discovery silently picking the first candidate; resolution is lazy and reports the candidates by name.
- Fixed static caches pinning classes, class loaders and data sources, which kept redeployed applications alive.
- Fixed compiler-folded constant interpolations reaching the SQL as text rather than as parameters, template keywords dispatching inside identifiers rather than on word boundaries, and unknown interpolation-safety modes passing silently; the compiler plugin marker is now required.
- Fixed `Metamodel.KeyDelegate.isNullable()` always returning false through the documented factory, so nullability now derives from the underlying field.
- Fixed the JSON converters sharing one mapper cache across field types, dropping the annotations Java propagates to a record component's backing field, accessor or constructor parameter, and writing `@Json` fields with the runtime rather than the declared type. Fixed the outer `RefFactory` not being restored after nested deserialization, and compound primary key `Ref` objects failing to deserialize.
- Fixed MariaDB sequences going undiscovered during schema validation, and `@StormTest` sharing one database across test classes.
- Fixed `SqlCapture` losing statements across Kotlin coroutine boundaries and skipping an action that returns no row; storm-kotlin-test gains a blocking `recording { }` scope and the Ktor test scope captures for real.
- Fixed Ktor named databases not inheriting plugin-level configuration, an unclaimed named lookup passing silently, the SQL log not binding to `application.conf`, and the plugin closing pools it did not create.
- Fixed unknown validation-mode values skipping schema validation with only a boot warning and silently escalating record validation; a typo now fails fast naming the accepted values.
- Fixed the slow log's per-shape rate limit skipping the executions it has no shape for, which is where a degraded database could flood it.
- Fixed error messages and API docs naming methods that exist on no surface, the PostgreSQL, MariaDB and MSSQL JDBC drivers leaking onto consumers' classpaths at compile scope, published POM metadata pointing at the wrong organization, and the CLI accepting unknown commands.


## [1.13.1] - 2026-08-07

- Fixed the Java API and the `Any` variants rejecting navigation-only paths (beyond a `Ref`): every clause parameter now accepts `Navigable`, and the new `Navigable.asMetamodel()` resolves a node to its column.
- Fixed template fragments degrading a beyond-`Ref` path to a bind parameter instead of resolving the column.
- Fixed schema validation resolving the SQL dialect by classpath order instead of from the database product.
- Fixed the Kotlin compiler plugin interpolating the wrong operands when a template lambda uses string concatenation.
- Fixed row mapping for reference-resolving statements (`fetch(...)`) read through a custom query executor: the public mapper overload accepts the resolved reference paths.
- Column resolution errors name the cause and the candidates: an ambiguous short-form reference lists the paths that pin it, and a reference to a table outside the query says so, naming the query root.

## [1.13.0] - 2026-08-01

Feature and performance release: write sets, `Ref` navigation and resolution in queries, compiled query plans, GraalVM native images, the Storm Gradle plugin, Java transaction parity, SQL logging, and Micrometer observability, with the read, write and transaction hot paths leaner throughout.

- `EntityCallback.beforeDelete` / `afterDelete` are renamed `beforeRemove` / `afterRemove`. A Java override without `@Override` compiles and stops being called, so search for the old names.
- "After" callbacks observe what the calling method reports: `*AndFetchId(s)` the entity carrying the assigned key, `*AndFetch` the row as read back, the void methods the entity as sent.
- Integration points are instance-scoped: `ORMTemplate.builder(...)` composes `connectionProvider`, `transactionTemplateProvider`, `exceptionMapper` and `queryObserver`, `transaction { }` binds to the first template used inside the block, and templates no longer enlist in Spring transactions based on classpath presence. `@EnableTransactionIntegration` and the Spring `ServiceLoader` providers are removed; use `springOrmTemplate(...)` or the starter.
- Models are null-marked by default, aligning Java with Kotlin and JSpecify. A bare `@FK` joins INNER, `@Nullable @FK` joins LEFT.
- Spring Boot: SQL failures translate to Spring's `DataAccessException` hierarchy. Update catch blocks and rollback rules, or set `storm.exception-translation.enabled=false`.
- Ktor is upgraded to 3.4.3, moving storm-ktor to the Kotlin 2.3 toolchain; the other modules stay on Kotlin 2.0. `storm-ktor-koin` is removed in favor of Ktor's built-in dependency injection.
- Added write sets (`orm.writeSet()`, Kotlin also `orm.writeSet { }`): one write action over a mixed-type entity collection, ordered by foreign key dependencies and batched per type. `insert` and `upsert` extend to the transitively reachable unsaved entities; `update` and `remove` write exactly what is passed.
- Added `getResultGroupedBy` / `getResultGroupedByRef`: the one-to-many read grouped during hydration in a single query, typed through the new `TypedMetamodel`.
- Added navigation through `Ref` foreign keys in queries (`User_.city.country.name`), joining the referenced table on demand; nodes beyond a reference are navigation-only (the new `Navigable`).
- Added `select().fetch(User_.city)`: the query resolves the named references in place of their foreign key columns, so `Ref.fetch()` returns without querying. `Ref.getOrThrow()` reads one without querying and fails where the plan does not cover it.
- Added compiled query plans (`QueryTemplate.plan(...)`, `QueryBuilder.plan()`): a template is processed once into a reusable `QueryPlan` and bound per execution. Repositories reuse cached plans for their fixed-shape operations.
- Added SQL logging: statements on the `st.orm.sql` logger (DEBUG, values at TRACE) and per-call summaries on `st.orm.sql.perf` through `sqlLog { }`, `SqlLog.open(...)`, `storm.sql-log.*` and the Ktor slots. Replaces the removed `@SqlLog` annotation.
- Added GraalVM native image support: reachability metadata, Spring AOT hints and AOT-participating repository scanning for Spring Boot, and a storm-core GraalVM feature for Ktor and plain JVM applications.
- Added the Storm Gradle plugin (`id("st.orm")`, Gradle 8.5+): imports the BOM, adds the language-path dependencies, wires the metamodel processor and matching compiler-plugin variant, and sets the preview flags; configuration-cache compatible.
- Added programmatic transactions for Java (`Transactions.transaction(...)`) with the semantics of Kotlin's `transaction { }`, a shared transaction vocabulary in storm-foundation, a Spring bridge that joins `@Transactional` transactions, and `Transaction.onCompletion(committed)`.
- Added `storm.query` and `storm.transaction` Micrometer Observations (new storm-micrometer module), auto-bound by the Spring Boot starters and the Ktor plugin, with opt-in OpenTelemetry semantic conventions and trace-context SQL comments.
- Added `@EnableStormRepositories`, the `@DataStormTest` test slice (new storm-spring-boot-test-autoconfigure module), and starter auto-configurations shared by both stacks.
- Added to the Ktor plugin: repositories and the template through Ktor's built-in dependency injection, multiple databases via `database("name") { }`, and the `transactional { }` route DSL.
- Performance: batch inserts emit a single multi-row `INSERT ... VALUES`, retrieving generated keys in one round trip on every capable dialect; single-row reads skip the stream pipeline, cached entities skip column decoding, eager reads skip the fetch-size hint, and the dirty-check, statement-build, expression-binding and result-mapping paths allocate less. Transactions restore connection isolation and read-only settings only when a block changed them, saving two round trips on PostgreSQL.
- Changed: records are constructed through generated instantiators instead of reflection, removing the last reflective call from row mapping.
- Changed: `groupBy` and `orderBy` resolve a path to the columns a predicate on it uses. A foreign key contributes its own column(s) without joining the referenced table, an inline record its component columns, and `Visit_.pet.id` names `visit.pet_id`.
- Changed: Spring repository scanning is single-sourced in storm-spring with the Kotlin bindings in `st.orm.spring.kotlin`; one starter per application.
- Fixed transaction callbacks inside an externally managed transaction firing when the block returned rather than when the transaction completed, so `onCommit` could report a commit that Spring then rolled back.
- Fixed multi-column key comparisons rendering as row-value tuples, which MariaDB does not index in an UPDATE or a DELETE: batches of keyed updates were quadratic in the table and prone to deadlock.
- Fixed entity callbacks being skipped on the dialect-specific key-returning insert paths (PostgreSQL, MariaDB, SQL Server), and `upsertAndFetchIds` failing on SQL Server with an auto-generated primary key.
- Fixed entity-typed primary keys: row identity in the entity cache, refs and write sets no longer depends on non-key columns round-tripping exactly, and `findAllById` / `whereId` resolve when the key entity's table appears twice in the join graph.
- Fixed the metamodel in both generators: a reference metamodel's value is nullable in Java, matching KSP; `@Json` columns are addressed by their stored type; field types resolve from the canonical constructor.
- Fixed the dynamic proxy interface order of monitored resources, keeping GraalVM proxy registrations valid; hardened schema metadata queries and `Ref` deserialization.

## [1.12.1] - 2026-07-12

- Fixed metamodel references to components of compound primary keys resolving to the underlying columns.
- Fixed repository lookups by a `Ref` primary key (`Entity<Ref<T>>`): the reference was bound instead of the key it carries.
- Website and documentation updates.

## [1.12.0] - 2026-07-06

Feature release centered on the reified Kotlin query API. Breaking changes are accepted with no deprecation shims (1.12 policy).

- `QueryBuilder`, `JoinBuilder`, and `TypedJoinBuilder` are now abstract classes (were interfaces) so they can host reified members; recompile against 1.12.
- Schema validation now defaults to `fail` in the Spring Boot starters and the Ktor plugin; set `storm.validation.schema_mode` / `schemaMode` to `warn` or `none` to relax.
- Added a reified Kotlin query API: reified joins (`innerJoin<Rating>().on<Movie>()`, `innerJoin<Owner, Pet>()`), selects (`select<R, _, _>`, `selectFrom<T, R>`), repository lookup (`entity<T, ID>()`), and `Query` result terminals (`resultList<T>()`, `resultFlow<T>()`, and friends).
- Added `findBy` / `getBy` / `findAllBy` repository shortcuts (and `Ref` variants) for Java 21.
- Added the `storm-ktor-koin` module: `Application.stormModule()` bridges the ORM template and auto-registered repositories into Koin.
- Ktor: repositories auto-register when the `Storm` plugin is installed (`stormRepositories { }` is now optional); added a `migration { }` hook that runs before schema validation.
- Foreign keys now follow key chains, resolving to the referenced key's columns for dependent one-to-one relationships; FK columns are schema-validated through the chain.
- Dependency-aware join ordering fixes forward alias references that PostgreSQL rejected when outer joins are present; JSpecify `@NonNull` is now recognized for record-component nullability.
- Typed joins onto projections resolve by table match; ambiguous foreign-key joins now fail fast with a descriptive error instead of a silent first match.
- Fixed `@Json` binding to PostgreSQL `jsonb`, H2 natural-key upserts, and `@StormTest` script splitting on semicolons inside comments and literals.
- Spring Boot 4 auto-configuration compatibility.

## [1.11.6] - 2026-07-01
- Kotlin 2.4 support: added the `storm-compiler-plugin-2.4` variant built against the Kotlin 2.4.0 compiler API.
- Fixed `@Convert` on an embedded (`@Inline`) component field failing on save.

## [1.11.5] - 2026-06-27
- Documentation and tooling release.
- Corrected the quick-start install command to `npx @storm-orm/cli` (the previously documented `@storm/cli` package does not exist on npm).

## [1.11.4] - 2026-06-27
- Improved handling of `NULL` for optional results in `Query` and `QueryBuilder` (Java and Kotlin) so optional single-result lookups behave consistently.
- Optimized object mapping for value types, reducing per-row mapping overhead.

## [1.11.3] - 2026-05-30
- Added `validateSchema(Predicate<...>)` / `validateSchemaOrThrow(...)` overloads on `ORMTemplate`.
- Added `validateAndReport(Predicate, strict)` / `validateReportAndThrow(...)` on `SchemaValidator` for filter-based validation.

## [1.11.2] - 2026-04-09
- Added the `storm db` command group for managing a global database connection library.
- Added multi-database MCP support (`storm mcp add/list/remove`).
- Added optional read-only data access via the `select_data` MCP tool.

## [1.11.1] - 2026-04-01
- Added `Slice<R>` as the common base for `Window` (cursor-based scrolling) and `Page` (offset pagination).
- Added `findAllRef()` on `EntityRepository` / `ProjectionRepository`.
- Added `select(predicate)` / `selectRef(predicate)` convenience methods.

## [1.11.0] - 2026-03-27
- Added the cursor-based scroll API (`Scrollable`, `Window`, `MappedWindow`).
- Added opaque cursor serialization (`CursorCodec`, `CursorFactory` SPI).
- Added the SQLite dialect module (`storm-sqlite`).

## [1.10.0] - 2026-03-14
- Added the Kotlin compiler plugin (`storm-compiler-plugin`) for the SQL Template DSL, replacing the `storm-kotlin-validator` lint rules with compile-time wrapping.
- Added multi-dollar string support in SQL templates.
- Added built-in offset-based pagination (`Page`, `Pageable`).

## [1.9.1] - 2026-03-07
- Fixed the repository scanner registering non-`Repository` interfaces in scanned packages.

## [1.9.0] - 2026-03-05
- Added the `storm-bom` module for centralized dependency version management.
- Added the `storm-test` module with the `@StormTest` JUnit 5 extension and `StatementCapture`.
- Added Spring Boot starter auto-configuration.

## [1.8.2] - 2026-02-13
- Added transaction-scoped, cache-first lookups; `Ref.fetch()` now checks the cache before querying.
- Raw update queries now invalidate the cache for the affected entity type.

## [1.8.1] - 2026-01-29
- Entity cache enabled in read-only transactions and disabled for `READ_UNCOMMITTED`.
- Primary-key-based cache lookups instead of full entity equality checks.

## [1.8.0] - 2026-01-28
- Added SQL template caching and template-generation metric logging.
- Separated SQL template compilation and binding stages.

## [1.7.2] - 2026-01-09
- Clean entity cache after nested transaction rollback.
- Cleaned up the extension-functions API.

## [1.7.1] - 2025-12-30
- Support for custom field types and kotlinx.serialization (Jackson or kotlinx).
- Serializability for entities and projections; entity-level dirty checks for updates.

## [1.6.2] - 2025-11-02
- Allow a primary key to be a `Ref`.

## [1.6.1] - 2025-10-04
- Fixed `WhereProcessor` for PK/FK combinations; improved join inclusion and JDBC time-type handling.

## [1.6.0] - 2025-09-13
- Added sequence-based ID generation strategy and entity-to-ref conversion.

## [1.5.0] - 2025-08-19
- Added Kotlin coroutine support, programmatic transactions, and `Flow` support.

## [1.4.0] - 2025-08-11
- Made Kotlin a first-class citizen; set the Kotlin baseline to 2.0.21.
- Clients now explicitly include the `storm-core` module.

## [1.3.8] - 2025-07-06
- Reflection performance improvements; scope-limited alias resolution.

## [1.3.7] - 2025-06-29
- Added parameter inlining for `SqlTemplate` and a `SqlTemplate` customizer.

## [1.3.6] - 2025-05-31
- Added Kotlin extension functions for convenient repository access.

## [1.3.5] - 2025-05-26
- Eliminated annotations from the metamodel; added a nullable reference method.

## [1.3.4] - 2025-05-18
- Full support for compound primary keys; improved nullability checks and ordering.

## [1.3.3] - 2025-05-05
- Refactored `SqlInterceptor` to use `ScopedValue`.

## [1.3.2] - 2025-05-03
- Aligned entity and projection repositories with existing persistence APIs; auto-register Jackson modules.

---

For releases prior to 1.3.2, see the
[GitHub Releases](https://github.com/storm-orm/storm-framework/releases) page.

[1.14.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.14.1
[1.14.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.14.0
[1.13.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.13.1
[1.13.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.13.0
[1.12.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.12.1
[1.12.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.12.0
[1.11.6]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.6
[1.11.5]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.5
[1.11.4]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.4
[1.11.3]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.3
[1.11.2]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.2
[1.11.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.1
[1.11.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.11.0
[1.10.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.10.0
[1.9.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.9.1
[1.9.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.9.0
[1.8.2]: https://github.com/storm-orm/storm-framework/releases/tag/v1.8.2
[1.8.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.8.1
[1.8.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.8.0
[1.7.2]: https://github.com/storm-orm/storm-framework/releases/tag/v1.7.2
[1.7.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.7.1
[1.6.2]: https://github.com/storm-orm/storm-framework/releases/tag/v1.6.2
[1.6.1]: https://github.com/storm-orm/storm-framework/releases/tag/v1.6.1
[1.6.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.6.0
[1.5.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.5.0
[1.4.0]: https://github.com/storm-orm/storm-framework/releases/tag/v1.4.0
[1.3.8]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.8
[1.3.7]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.7
[1.3.6]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.6
[1.3.5]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.5
[1.3.4]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.4
[1.3.3]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.3
[1.3.2]: https://github.com/storm-orm/storm-framework/releases/tag/v1.3.2
