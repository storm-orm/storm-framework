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

- An `or` across two semi-joined tables, and a `search` or `suggest` over columns of several, is placed as one `EXISTS` per table, ORed with the query's own columns, rather than refused (#786). A term that compares columns of two semi-joined tables stays refused, since no row of either answers it.
- A call site names an application frame whose package starts with `st.orm.`, as the example applications' do, rather than skipping it as Storm's own, and a native image names the application's frame rather than GraalVM's (#782).
- A statement Storm executes is the driver's own rather than a JDK proxy that returns its connection when it closes, and a statement closed twice returns its connection once.
- Reads and writes spend less time in Storm, most of all multi-row inserts and reads of many rows.
- A lazily resolved template setting no longer fails with `NullPointerException: supplier` when several threads read it for the first time at once.
- Advice on what a transaction repeats, `repeated-select`, `repeated-write` and `repeated-fetch`, is reported by the advice log when the transaction completes, as one block under the call site that began it, once per call site at `INFO`, each line with the time the executions spent and the call site of the statement (#772). The performance log summary keeps the repeats that span transactions, says how many they ran in and that a batch commits the rows together, counts a repeat per shape and call site from every execution, past the table's recording limit too, leaves the executions a transaction advised on to that transaction, states the time concurrent executions covered on the clock next to their sum, and orders its advice by that time. A repeat that took less than 50 ms on the clock carries no advice; `storm.advice.repeat_floor` sets the floor, `0` for every repeat. The block carries the transaction's `idle-transaction` advice too, beside its repeats or alone. A performance log summary times every statement past its recording limit: its database time is exact, and the closing line says what the unrecorded statements spent per operation and type. `missing-index` advice records the columns a `groupBy`, a multi-column order, a to-many join and a metamodel select name, and checks each index's full column list: it advises the index that continues an indexed filter with the group or sort columns, names both indexes where finding the rows and reading them in order differ, with when each wins, says when an equality column has few distinct values (`ENUM`, `BOOLEAN`, `BIT`), and stays quiet for a unique-key equality or a group on the primary key. The columns the select reads are named apart from the grouping index, `with first_name, address appended it reads the index alone`, stated as certain where the select names every column it reads and hedged where part of it is text; `SqlDialect.maxIndexKeyBytes()` states the limit a database checks an index key's declared sizes against, which the MySQL, MariaDB, SQL Server and Oracle dialects set, and the advice leaves the select's columns out where appending them would pass it and says where the index itself would. A conformance test per dialect proves the advised index is the one the database uses and the stated limit is the database's own. Repeat advice names the built-in repository call a statement served, `getById of Owner ran 12 times`, `existsById`, `insert`, and points at its list form, `findAllById` over the keys, which for an existence check reads the ones that exist; a statement that served no call is named the same statement, and a repeated count is advised one query with `IN` that selects the keys that exist, grouped only where the counts themselves are used. It states the mean time per execution and, from 10 ms each, says the statement itself is the cost; it names a list overload only for an insert or a repository write of given entities, one covering condition for a write by condition, and says `no rows` where none were touched. Executions ran `in N transactions of their own` only where each transaction held nothing else; where the transactions held more, the advice counts them without the clause that a batch commits the rows together, and a write run once in each of them, the loop over units of work, is advised only where each execution costs 10 ms or more, as the statement to look at. A repeat whose executions cost 10 ms or more each is followed by the `missing-index` advice its statement draws. A write is `spread ... with work outside the database between them` only for the time no statement of the scope spent in the database, and a read is never said to be. A call site skips a library's inlined frame, such as a flow operator's, and a flow a repository method builds names that method where it runs. An advice log line states the database time and rows of the execution that drew it, and a call site names the caller and the repository method it called, `ReminderService.kt:52 -> VisitRepository.findByPet`: the outermost where repository methods call each other, and the built-in call where no repository method of the application's was called, `-> getById`. A repeated write spread between work outside the database, as a loop around a remote call is, says so and is advised to collect the rows and write them after the loop rather than open a transaction around it; a repeated upsert is named one and advised to read the rows first and write only the new or changed ones, with the generated key value each upsert takes on a database whose dialect says so through `SqlDialect.upsertTakesGeneratedKeyOnUpdate()`. A statement in the database's own upsert form is reported as an upsert, `QueryContext.upsert()` and `Sql.upsert()`, where the dialect recognizes it through `SqlDialect.isUpsert()`: a `MERGE` that both updates and inserts by default, `INSERT ... ON DUPLICATE KEY UPDATE` on MySQL and MariaDB, `INSERT ... ON CONFLICT ... DO UPDATE` on PostgreSQL and SQLite, and the short `MERGE INTO ... KEY` form on H2; its operation stays the keyword it starts with, `INSERT` or `UNDEFINED` for a `MERGE`, which is what a metric tag names, a read-only transaction refuses it as the write it is, and a captured statement says so through `CapturedSql.upsert()`.
- `idle-transaction` advice claims the row locks for the idle time from the first write on, and `in-list-shapes` advice is withheld on a database that keeps no plan per statement text, which `SqlDialect.keepsPlanPerStatementText()` reports and the MySQL and MariaDB dialects answer with `false` (#772). `QueryContext.transactionId()` and `SqlLog.Statement.transactionId()` identify the transaction an execution ran in, and `TransactionContext.transactionRecord()` is the record a transaction keeps.
- A suspending `transaction { }` commits or rolls back on the dispatcher that ran its block and returns the connection from there (#770). The caller's dispatcher, which the completion needed before, held connections idle in transaction while it was saturated, until the pool's wait timed out.
- The subtypes of a sealed entity declare foreign keys (#754): a foreign key every subtype declares is joined as the field declares it, one only some subtypes declare holds NULL in the others' rows and is joined with a LEFT JOIN, and `Pet_.owner` is a path of the sealed metamodel. Both Single-Table and Joined Table.
- A subtype of a Single-Table hierarchy is an entity of its own (#754): `orm.entity(Dog.class)` reads and writes the shared table, and every statement on it carries the discriminator, including a join onto it. A subtype of a Joined Table hierarchy is refused as a repository or a FROM table. Schema validation checks a Single-Table hierarchy through the sealed type's columns and a Joined Table hierarchy table by table.
- The native-image feature registers the permitted subclasses of a sealed type, which a native image reports only when registered; a sealed entity, or a polymorphic foreign key's target, failed at startup as "Record type expected" (#754). Both metamodel processors list sealed `Data` interfaces in the type index, so `validateSchema()` checks a hierarchy's tables and startup validation checks the hierarchy.
- Added the `update()` query builder, the UPDATE counterpart of `delete()` (#712): `set` names columns through the metamodel. It increments `@Version`, fires no entity callbacks and cannot set the primary key, the version or non-updatable fields.
- `delete()` and `update()` refuse to run without a condition unless `unsafe()` is called, take explicit joins on every database and refuse a joined inheritance hierarchy (#712). `SqlDialect.writeJoinSyntax()` replaces `supportsDeleteAlias()`.
- Added `semiJoin` and `antiJoin`, which filter rows by a related table without adding its rows, so no `distinct` is needed (#690). They filter `select()`, `delete()` and `update()` and nest through two to-many relations (#717).
- A semi-join or anti-join on the query's own table finds the rows that reference the same row of another table as the query's row, `visits.semiJoin<Visit>().on<Pet> { ... }` (#767). The condition on the join names the other row, a condition in `where` the query's row.
- A typed join takes a condition: `on<Owner> { ... }` in Kotlin, `on(Owner.class, it -> ...)` in Java (#690). On a left join it keeps the rows without a match, which the same condition in `where` drops.
- Added `@QueryColumn`, a column a query names but the entity does not carry, such as a generated search column (#743). Its metamodel field is never selected or written; `Model.queryColumns()` lists them. Schema validation reports one the database does not fill as `UNMAPPED_COLUMN` and accepts an unmapped generated column.
- A query column names the function that computed it, `@QueryColumn(function = "fold_accents")` (#745). A value compared with the column passes through the function, so the database folds the input. `Column.function()` reports it.
- Added `SqlFunction`, a one-argument SQL function applied to a column wherever a query takes one, so a query can match the expression an index is built on (#694). A comparison applies it to the column and the value alike.
- Added `suggest`, the `search` for input typed as you go: every word of two characters or more matches in the form the database's index serves, on PostgreSQL anywhere from the length the trigram index serves and at the start of a word below that, elsewhere as a prefix of the column with the column left bare for an ordinary index over it; a one-character word takes no part, and it takes words to ignore (#741). A conformance test per database proves the suggestion served by the index the docs name. `search` tests short words only on rows its longer words found. `storm.search.min_term_length` sets the length.
- Added `orderFirst`, which sorts the rows matching a predicate first (#693). A keyset `scroll` follows it, and `Scrollable.sortBy` and `sortByDescending` take a field of any table in the query.
- An `IN` list binds as one array parameter where the database supports it, `id = ANY(?)` (#727). `storm.in_list` selects `auto`, `padded` or `exact`, a `NULL` value binds as `NULL`, and `in-list-shapes` advice reports lists rendered in many lengths.
- `LocalDate`, `LocalDateTime` and `Instant` are accepted as primary key types, alone or as a compound key component (#750). `OffsetDateTime`, `ZonedDateTime` and the `java.util.Date` family stay rejected.
- The Kotlin API takes a type argument where the call needs no `_` placeholders and a class literal where it would, such as `orm.selectFrom<Pet>()` and `orm.entity(User::class)` (#719). Ktor gains `entity(User::class)` and `projection(OwnerView::class)`.
- Removed `orm.entity<User>()` and `orm.projection<OwnerView>()`, including their Ktor forms, which dropped the primary key type so `findById` did not resolve (#721).
- Upgrade note: replace `orm.entity<User>()` with `orm.entity(User::class)`, which infers the key type. Reified shortcuts that take records, such as `orm insert user`, are unchanged.
- Optimistic lock conflicts, single-result failures and writes that affect no row pass through the template's `ExceptionMapper` (#240). The Spring Boot starter translates them to Spring's exceptions, and `QueryTemplate.exceptionMapper()` returns the mapper.
- Upgrade note: with the starter's exception translation on, catch `OptimisticLockingFailureException`, `EmptyResultDataAccessException` or `IncorrectResultSizeDataAccessException`, or read the Storm exception from the cause.
- Added `ORMTemplate.loadSchema()`, which loads the schema without validating it, and the `load` value of `storm.validation.schema_mode`. Schema validation reads unique and non-unique indexes and accepts a unique index for a `@UK` field.
- A dialect supplies catalog queries through `SqlDialect.keyDiscoveryQuery()`, `foreignKeyDiscoveryQuery()`, `sequenceDiscoveryQuery()` and `indexDiscoveryQuery()`. `ConstraintDiscoveryStrategy.ALL_CONSTRAINTS` and `SequenceDiscoveryStrategy.ALL_SEQUENCES` are removed.
- Added the advice log, `st.orm.sql.advice` (#704). Correctness advice is always on; performance and design advice are on in development or when named in `storm.advice.categories`. The level of `st.orm.sql.advice.<anchor>` sets how often advice repeats.
- Added `@AcceptAdvice`, which accepts advice by anchor, such as `@AcceptAdvice("page-count")`, or all advice, on an entity, method, class or repository interface (#707). Accepted advice leaves only the advice log.
- Added design advice on whether a foreign key should be an inline entity or a `Ref` (#704). In development, an entity whose read exceeds the budget of 10 joins and 96 columns is reported once; `storm.advice.entity_budget.joins` and `.columns` set it (#710).
- The slow statement log advises on more shapes: `distinct` over a to-many join, the `page` count query, large offsets, short `search` words. With a loaded schema it names a missing index (#725) and an unindexed foreign key behind a slow delete (#731).
- The slow statement log reports `idle-transaction` advice for a transaction that holds its connection over a second beyond its statements (#728). `storm.sql_log.slow.transaction` sets the threshold, and `0` switches it off.
- A performance log summary closes with advice on what its unit of work repeated: ten or more `Ref.fetch()` calls for one type, or single-row SELECTs or writes of one shape.
- Added `writes-without-transaction` advice to the performance log summary: related writes that each committed on their own, such as an owner and then its pet (#730). `SqlLog.Statement.transactional()` says whether a statement ran in a transaction.
- The labels on the slow statement log, advice log and performance log summary lines end in a colon: `shape:`, `parameters:`, `comment:`, `advice:` and `graph:`.
- A Ktor application built as a GraalVM native image starts with the repositories the Storm plugin registers and inserts rows with generated keys (#689).
- Fixed `@Json(failOnUnknown = true)` on Jackson 3 and `failOnMissing = true` on Jackson 2 and 3, which accepted JSON they should refuse (#409). Sealed `@Json` arrays register their subtypes, and `{"@entity": null}` reads as no ref on Jackson 2.
- Fixed a keyset scroll whose key is reached through one of two foreign keys to the same table, such as `Recommendation_.movie.id` beside a `seedMovie`, which reported the path as ambiguous on the next window (#748).
- Fixed `SqlCapture` dropping a statement that binds a `NULL` value, such as an insert with an unset nullable foreign key (#752). Its `parameters()` hold `null` for the value.
- Fixed the metamodel processors rendering a `@Convert` record component as an inline record with a metamodel of its own (#756). It is a column, as the runtime metamodel resolves it, as is a component of a type a `@DefaultConverter` in the same compilation maps; the Java processor also reads the converter index of a dependency. A record nested in a record is skipped like one nested in a class; its metamodel did not compile.
- Fixed a `where` condition, and an `update().set`, on a converted field, which bound the compared value without running its converter, and failed when the field's type is a record (#760). The value passes through the converter as the entity's does, at the top level, inside an inline record and on a subtype of a sealed entity, and a value given in the database type passes through as the stored value. `ORMConverter.toDatabaseValue` converts a value given on its own.
- With storm-kotlinx-serialization and a Jackson module both on the classpath, the converter of a `@Json` field depended on classpath order (#764). kotlinx.serialization converts the fields whose type it has a serializer for, Jackson the rest, and a converter the field selects with `@Convert` wins over both. Two unordered providers claiming one field, and a `@Json` field no module claims, are refused with the candidates named.
- A native image constructed the result rows of custom queries reflectively without metadata, so a `select` into a record outside the entity graph failed at runtime (#764). Both metamodel processors list a record annotated `@GenerateMetamodel` in the type index, and the native-image feature and the Spring AOT hints register it like an entity, with its component types and serializer companions.
- Saving a Kotlin record whose `@Json` value held an empty collection failed in a native image: Jackson picked the serializer from the collection's runtime class, the standard library's `EmptySet`, and its Kotlin module introspected that class through Kotlin reflection (#764). The Jackson converters write a collection or map of final element types by its declared type, and the native-image feature and the Spring AOT hints register the standard library's collection classes for the values that still reach Jackson by runtime class.
- A converter failing to convert a value named the field's type instead of the record declaring the field, `'Street.street'` for the `street` field of `Address`; the message names the record.

## [1.15.0] - 2026-10-15

A minor release that matches text in the query builder, literally and ignoring case, passes optimizer hints, resolves the schema a table lives in per template, and lets an entity declare its own callbacks. Transactions hold to what they state: a joined block keeps its isolation and read-only promises, a deadline stops the next statement, and two callers on one connection are refused rather than queued. Several of these change behaviour that code may rely on; the upgrade notes say how to adapt.

- Added `CONTAINS`, `STARTS_WITH` and `ENDS_WITH` and their negations to `Operator`, with the Kotlin infix functions `contains`, `startsWith` and `endsWith` and their `not` forms (#610). They match literal text: the dialect escapes the characters its database reads as wildcards, through `SqlDialect.escapeLike`, and renders `LIKE ? ESCAPE '!'`, so `50%` or `a_b` matches exactly that text on every database.
- Added `EQUALS_IGNORE_CASE`, `CONTAINS_IGNORE_CASE`, `STARTS_WITH_IGNORE_CASE` and `ENDS_WITH_IGNORE_CASE` and their negations, with the Kotlin functions `eqIgnoreCase`, `neqIgnoreCase`, `containsIgnoreCase`, `startsWithIgnoreCase` and `endsWithIgnoreCase` (#623). They lower the column and the value alike, `LOWER(name) = LOWER(?)`, the same statement on every database; accents follow the column's collation. A plain index does not serve a lowered column, so the plain operators remain the choice on a column whose collation already ignores case.
- Added `search`, which matches the words of a search box input against one or more text columns: `search(input, Movie_.title, Movie_.director.name)` (#670). Every word must occur in one of the columns, ignoring case and matched literally; a quoted phrase is one word, and an input without words matches every row.
- `orderBy`, `orderByDescending`, `groupBy` and `fetch` take a first path followed by any further ones, so a call without a path no longer compiles (#679).
- Upgrade note: a call that passes its paths as an array, `orderBy(*paths)` in Kotlin or `orderBy(paths)` in Java, names its first path on its own, or calls `fetch(List)` for references. Code compiled against an earlier version is recompiled.
- Added `hint(...)` on the query builders, which passes an optimizer hint in the database's own syntax (#609): a `/*+ ... */` comment on MySQL, MariaDB 12.0 and later and Oracle, an `OPTION (...)` clause on SQL Server. PostgreSQL, H2 and SQLite have no hint syntax and leave it out. Dialects render hints through `SqlDialect.optimizerHint` and `applyOptimizerHintAfterKeyword`.
- Added `SchemaResolver`, set through `withSchemaResolver`, which resolves the schema an entity declares with `@DbTable(schema = ...)` per template, so tenants with schemas of their own share one set of entities; `SchemaResolver.mapping(...)` renames declared schemas. It applies to queries, joins, writes, SQL templates and schema validation. The models Storm caches per type are keyed on the template's resolvers and fetch plan, so templates with different table, column, foreign key or schema resolvers no longer share a model, and at most 64 configurations per type are held.
- Added `@EntityCallbacks`, which names the `EntityCallback` types that apply to an entity. Storm creates each through its no-argument constructor and applies it wherever the entity is written, under Spring, Ktor or a standalone template. Declared callbacks fire before registered ones, in the order listed, and a registered instance of the same type replaces the declared one. A type parameter that does not cover the entity, or a class Storm cannot create, is refused when the repository is created; the GraalVM feature and Spring AOT hints register the constructor.
- Added `ORMTemplate.current()`, the template of the operation whose entity callback is running, so the callback's own database work goes to the same database, connection and transaction. It is refused outside a callback.
- Added a list form of every "after" entity callback: `afterInsert(List)`, `afterUpdate(List)`, `afterUpsert(List)` and `afterRemove(List)`. Storm calls the list form a batch at a time; by default it calls the single-entity form per entity, so existing callbacks see every entity as before, and a callback that writes overrides it to handle a batch in a fixed number of statements.
- Upgrade note: where several callbacks apply to one batch, each receives the whole batch before the next one does, rather than taking turns entity by entity.
- Added `rows()` on a Kotlin `Flow<Window<R>>`: `windows(size).rows()` reads the windows as one flow of rows, with the connection free at every row and memory bounded by the window size. It replaces a `resultFlow` loop that the stream guard refuses because it queries per row; the Java form is `windows(size).flatMap(Slice::stream)`.
- The metamodel processors' warning for a nullable unique key can be suppressed with `"storm.nullable-unique-key"` in `@SuppressWarnings` in Java or `@Suppress` in Kotlin (#660). `scroll` on a nullable key is still refused at runtime.
- Storm reports at `DEBUG` what it registers of its own accord: the callbacks per entity type under `st.orm.callback`, the default converters under `st.orm.converter` and the type index results under `st.orm.discovery`. Routine validation progress moves to `DEBUG`; a success that validated types, and validation turned off by configuration, stay at `INFO`.
- A statement that reaches a transaction's connection while another caller holds it is refused with `Concurrent access on ...` rather than queuing (#635). Ownership follows the thread, or under a suspending `transaction { }` the coroutine, where `launch` and `async` children count as callers of their own.
- Upgrade note: statements run concurrently inside one transaction, from `launch` or `async` children or from another thread, now fail. Await each unit before starting the next, or give parallel work transactions of its own. `JdbcConnectionProviderImpl.ConcurrencyDetector` is removed; a connection provider uses `TransactionContext.connectionGuard()`.
- A joined block that states a stricter isolation level than the transaction it joins, or any level inside a transaction at the database's default, is refused with `IllegalTransactionStateException`, a `PersistenceException` that `MANDATORY` without a transaction and `NEVER` inside one now raise as well. The entity cache follows the transaction that owns the connection. The same rules hold under Spring-managed transactions.
- Upgrade note: such a block used to run silently at the enclosing level, and a default set with `setGlobalTransactionOptions` or `withTransactionOptions` counts as stated. State the level on the outermost block, open the inner block with `REQUIRES_NEW`, or leave its level unstated.
- A `readOnly = true` block inside a read-write transaction refuses the writes issued inside it, from enclosed blocks included, with `ReadOnlyTransactionException`. A read-write block inside a read-only transaction still joins it and is refused on write.
- A transaction sets the connection's read-only mode only when it requests a mode the connection does not have, and restores it before the connection returns to the pool; a transaction that requests no mode, the default, makes no read-only call.
- Upgrade note: `readOnly = false`, spelled out, now requests read-write and lifts a read-only connection. Leave the argument out where no mode is meant; an argument-less `setGlobalTransactionOptions()` resets the global default to no mode.
- A statement issued after its block's deadline is refused with `TransactionTimedOutException` before it reaches the database, rather than running on to the end of the block. The time left is rounded up to whole seconds. Under Spring, a timeout of zero is a deadline that has passed rather than no timeout.
- Upgrade note: a timeout surfaces from the first statement after the deadline, so the rest of the block no longer runs. Under Spring-managed transactions, a block whose deadline passes before its first statement, or that states `timeoutSeconds = 0`, fails with `TransactionTimedOutException`.
- A Kotlin result flow is cold: `resultFlow`, `getResultFlow` and `getRefFlow` run their statement when collection starts and close it when collection ends, so several flows can be built inside one transaction and a flow never collected holds nothing.
- Upgrade note: a flow's query runs where it is collected. A failure surfaces from `collect`, a flow collected twice queries twice, and a flow collected outside its transaction reads outside it. `resultStream` and `getResultStream()` still run when called.
- Each dialect quotes the words its database reserves, read from the database's own list on PostgreSQL, MySQL, MariaDB, Oracle and H2, and from the documentation for SQL Server and SQLite, so names such as `desc`, `rank`, `key` or `returning` no longer fail unquoted. H2 and Oracle quote nothing else, and keyword detection no longer depends on the JVM's default locale. The Technology Compatibility Kit fails on a reserved word a dialect leaves unquoted.
- Upgrade note: on H2 and Oracle, a column created quoted in lower case to match the quoting Storm used to apply, such as `"timestamp"` or `"position"`, no longer matches. Recreate it without quotes, or set `escape = true` on its `@DbColumn`.
- A group of predicates keeps its parentheses when combined: `a.or(b).and(c)` renders `(A OR B) AND C`, where it rendered `A OR (B AND C)` (#671). `and` and `or` no longer change the predicate they are called on.
- Upgrade note: a condition that relied on SQL's precedence states its group, `a.or(b.and(c))` in Java, `a or (b and c)` in Kotlin, and code that ignored the predicate `and` or `or` returned uses it.
- `TRUE()` and `FALSE()` render `1 = 1` and `1 <> 1`, so they work on SQL Server and on Oracle before 23 (#675).
- A MariaDB upsert of entities with a generated key reads their ids from the statement's `RETURNING` result, so `upsertAndFetchIds` and `upsertAndFetch` return every id on MariaDB 11.5 and later, where the driver's bulk protocol dropped the ids of updated rows (#607).
- A reference named in a query's fetch plan comes back loaded even when the row already holds an unloaded reference to the same record.
- A Kotlin `crossJoin` followed by bound values no longer fails with `Not enough bind hints` (#592). A join type without an ON clause refuses a join condition.
- `getResultCount()` on a select with a limit or GROUP BY works on SQL Server.
- A scroll window reads its cursor values from the row without selecting a column twice, appending only the columns the select list lacks; `Sql.cursorColumns()` reports where each value sits. Refs and custom select types scroll by an inline record key, and a key component holding an entity or projection is refused.
- The Gradle plugin refuses a KSP that does not pair with the project's Kotlin version, naming the plugin line to declare. Kotlin 2.2 needs no KSP line; the refusal covers Kotlin 2.0 and 2.1.
- Schema validation works with the Oracle driver 23.26 and later, where it failed with `ORA-17027: Stream has already been closed`.
- Schema validation no longer reports the columns of a view as nullable; views skip the nullability check.
- The Java API declares the nullability it already had, so Kotlin compiles against it without warnings: `ORMConverter` takes and returns nullable values, `AbstractRepositoryBeanFactoryPostProcessor.getOrmTemplateBeanName()` is `@Nullable`, and `transaction`, `withTransactionOptions`, `SqlLog.record` and `recordThrowing` may return `null`. Java callers see no change.

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
