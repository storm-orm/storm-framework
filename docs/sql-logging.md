import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

# SQL Logging

When debugging performance issues or tracing application behavior, you often need visibility into the SQL statements your ORM generates. Standard JDBC logging shows raw statements with `?` placeholders, giving you no context about what the statement operates on or what the actual parameter values were.

Storm logs statements where they execute, under the `st.orm.sql` logger tree. Nothing has to be annotated and nothing about the execution changes: compiled query plans and the template cache stay in effect, so what you observe is the path that runs in production.

## One Point, Three Grains

Every execution passes one interception point exactly once, and everything Storm reports about SQL draws on it. The reports differ in grain, not in vocabulary: each names the operation, the type, the origin (`fetch`), the shape, the rows and the database time the same way, so a row in a summary, a slow line and a metric series describe the same execution in the same words.

| Report | Answers | Grain | Switch | Parameter values |
|--------|---------|-------|--------|------------------|
| `st.orm.sql` | what ran | statement | logger, `DEBUG` | at `TRACE` |
| `st.orm.sql.perf` | what a call cost | unit of work | logger, `INFO`, plus a boundary | never |
| `st.orm.sql.slow` | which execution was slow, from where, with what | execution | `WARN`, plus a threshold | at `TRACE` |
| `st.orm.sql.advice` | which statements to change, and how | advice once (`INFO`), shape once (`DEBUG`), execution (`TRACE`) | logger, `INFO`, plus categories | never |
| Micrometer `storm.query` | how statements behave over time, per shape | execution, aggregated | `ObservationRegistry` | never |
| SQL comment | the key that joins the database's own slow log and plans to the trace | execution | `storm.tracing.sql-comments` | n/a |
| `SqlCapture` | assertions in tests | statement | code | yes, in tests |

Two rules hold across the loggers and the capture. **Database time** is measured from prepare to the statement's return, the moment a result set opens, an update count arrives or a batch is acknowledged; for a streamed read, consuming the stream is the application's time and is reported apart, so a stream held open across other work never reads as a slow query. (An observation is a span: it opens with the execution and closes with it, which for a stream is the stream's close.) **Values render only while a logger is at `TRACE`**: at any other level, every report carries statements with placeholders and counts, which is what makes every report safe to leave on in production.

## Turning It On

Raise the log level; there is no other switch.

```yaml
logging:
  level:
    st.orm.sql: DEBUG    # statements as sent, with placeholders
```

Every executed statement is logged once, prefixed with what it does and what it targets:

```
SQL (SELECT City):
	SELECT c.id, c.name
	FROM city c
	WHERE c.id = ?
```

### Parameter Values

At `TRACE` the parameter values are rendered into the statement, producing SQL you can paste straight into a database console:

```yaml
logging:
  level:
    st.orm.sql: TRACE
```

```
SQL (SELECT City):
	SELECT c.id, c.name
	FROM city c
	WHERE c.id = 2
```

:::warning Parameter values are database values
They may include credentials, personal data, or anything else your entities carry. That is why they appear only at `TRACE`: the level nobody enables in production by accident. `DEBUG` is the level to leave available for on-demand diagnosis.
:::

### Scoping to One Type

Statements also log under `st.orm.sql.<Type>` for the entity or projection they target, so a single type can be turned up without the rest:

```yaml
logging:
  level:
    st.orm.sql.Owner: TRACE
```

Because these are child loggers, raising `st.orm.sql` raises every type at once. Statements that target no particular type, such as raw queries, log under `st.orm.sql` itself.

### Fetches

A statement that resolves a reference is labelled, so it is distinguishable from the primary key lookups it otherwise looks identical to:

```
SQL (SELECT Owner, fetch):
	SELECT o.id, o.first_name, ...
	FROM owner o
	WHERE o.id = ?
```

Naming the reference in the query's fetch plan brings the referenced record back in the same statement instead. See [Refs](refs.md) for the fetch plan, and [Spring Integration](spring-integration.md#observability) for the `storm.origin` metric tag that measures the same thing in production.

---
## The Performance Log

Individual statements answer what ran. They do not answer what one unit of work cost, and that total is the part you act on: forty-four statements taking 678 ms tells you nothing about which of them to look at.

A scope records the statements a call executes and reports them as one summary:

```
SQL (GET /owners): 12 statements, 8 fetches, 214 ms in database over 61 ms elapsed (peak 4 concurrent), 678 ms total
	96 ms  6408 rows  7x  Visit         VisitService.kt:88  SELECT v.id, v.visit_date FROM visit v WHERE v.pet_id = ?
	28 ms     8 rows  8x  City   fetch  OwnerView.kt:31     SELECT c.id, c.name FROM city c WHERE c.id = ?
	18 ms   112 rows  4x  Pet           PetService.kt:52    SELECT p.id, p.name FROM pet p WHERE p.owner_id = ?
```

One row per distinct statement, heaviest first by **total** time, so a statement run many times cheaply ranks above one slow statement when it cost more overall. Repetition reads as the multiplier, each row carries the total rows its executions produced (or affected, for writes) followed by the execution count that accumulated them, and names the entity or projection it targets, and a statement that resolved a reference is marked `fetch`. A total is what one slow execution among many cheap ones and a uniformly slow statement have in common, so a row whose slowest execution stands out from its average, at least twice it, carries it as `max 60 ms`; the [slow statement log](#the-slow-statement-log) names that execution on its own. Long statements elide from the middle, keeping the FROM and WHERE clauses that identify them, and runs of placeholders collapse to `?, …, ?` so the visible text is the part that says something. A row count a driver declined to report in full (a batch entry answered with `SUCCESS_NO_INFO`), a stream closed before its end, or a stream still open when the scope closed is a known lower bound, marked `500*`. A statement is in the summary from the moment the database answers it, with the database time it cost, whether or not the application has finished reading the rows.

What answers which query a row is, and how it reads:

- **Call sites** name the application frame that caused each execution (`VisitService.kt:88`), which is the identity a developer thinks in. A query that lives in a repository's default method runs for every caller of the method, so the frame inside the repository says which query ran and not where; the site names the caller and the repository method it called, in the direction of the call, `ReminderService.kt:52 -> VisitRepository.findByPet`, which says at once which query repeated and where a list variant would go, and the repository alone where no application frame calls it. Where repository methods call each other, the one the caller called is named; a built-in call is named by the repository interface it was called on, `OwnerService.kt:52 -> OwnerRepository.getById`, or by its method alone on a repository obtained without one, `-> getById`. A library's inline function, such as a flow operator, is compiled into a copy under the caller's class that keeps the library's file and line; such a frame is recognized by that file, which is not the one named after its class, and skipped, so the row names the caller rather than `Emitters.kt` or `SafeCollector.common.kt`. A flow a repository method builds runs once the caller collects it, after the method has returned; the flow keeps the repository method that built it, so the row still reads `ReportService.kt:88 -> VisitRepository.findWithTreatmentsByPet`. An application with a database layer of its own declares it as plumbing, so rows name the caller beyond it: `storm.sql-log.call-site-skip` in Spring, `sqlLogCallSiteSkip` in Ktor, or the `storm.sql_log.call_site_skip` system property on a plain JVM. Entries ending in `.kt` or `.java` match by source file, which covers inline functions. Work resumed on another dispatcher has no caller on its stack at all; a context built with `sqlLogContext()` (and every context Storm builds, such as `transaction { }`) carries the launch site, captured while the caller is still on the stack, so such rows name the frame that launched the work. A stack that is plumbing end to end with no carried site reports its innermost plumbing frame rather than none. A stack walk per execution, so it is opt-in and suited to development: `storm.sql-log.performance.call-sites: true` in Spring, `sqlLogPerformanceCallSites = true` in Ktor, or the `callSites` parameter on a scope opened directly. A row seen from several frames shows the first plus `(+n sites)`.
- **TRACE detail**: with `st.orm.sql.perf` at `TRACE`, the un-elided statement texts follow the summary, one per row in row order. `TRACE` rather than `DEBUG` because this logger sits under `st.orm.sql`, so raising that to `DEBUG` for per-statement logging would otherwise repeat every statement twice. Summaries carry no parameter values at any level, so this level is as safe to enable as the others.
- **Display width**: rows aim for 200 characters, the statement text eliding to what the other columns leave; `storm.sql-log.performance.line-width` (Spring), `sqlLogPerformanceLineWidth` (Ktor), or the `storm.sql_log.performance.line_width` system property sets the target for narrow viewers (120) or wide ones (240).

Statements group by the template they were generated from, not by text. A collection parameter that expands to a different number of placeholders per execution (`IN (?)`, `IN (?, ?)`) therefore stays one row, marked `(n variants)`.

The headline separates three durations:

| Number | Meaning |
|--------|---------|
| `214 ms in database` | The summed database time of every statement: from prepare to the statement's return. |
| `over 61 ms elapsed` | The time during which at least one statement was in flight. |
| `peak 4 concurrent` | The most statements in flight at once. |
| `678 ms total` | How long the call took. |

A unit of work past the recording limit lists its first statements in the table, and every statement past the limit is still timed, keeping no statement: the database time covers all of them and is exact, and the closing line says what the rest spent and on what, heaviest first, `(135 statements not recorded, 5075 ms: 120 INSERT of Reminder 4800 ms, 15 SELECT of Visit 275 ms)`, which says what to raise the limit for or look at. A unit of work that runs more than a thousand operation and type pairs past the limit counts the rest per operation, as `of other types`. The elapsed time and the concurrency cover the recorded statements alone, so past the limit they are marked `+` as lower bounds.

Summed database time exceeds elapsed time whenever statements run concurrently, which is why both appear. The concurrency clause is omitted when nothing overlapped. And `214 ms in database` against `678 ms total` says most of that call was spent somewhere other than the database. Consuming a streamed read counts on the `total` side of that comparison, not the database side: a stream held open while the application works through it is the application's time.

A summary closes with advice where the unit of work repeated across its transactions what it could have done once, counted from every execution, past the table's recording limit too, so a repeat in a long task is advised on with its full count and time even where the table lists only its first statements: ten or more fetches of one type, or the same single-row read or write run ten or more times. A repeat inside one transaction is reported by the transaction when it completes, in the [advice log](#the-advice-log), and the summary leaves the executions a transaction advised on to it, so it counts the part no single transaction saw, such as a loop that opens a transaction per item, and says how many transactions they ran in; a shape whose executions were all advised per transaction drops out of the summary's advice, while its row stays in the table. It also says where related writes each committed on their own: a write outside any transaction to an entity that references an entity an earlier untransacted write wrote, such as an owner and then its pet, which leaves the owner behind when the pet fails.

```
SQL (GET /owners): 31 statements, 24 fetches, 41 ms in database, 96 ms total
	28 ms  24 rows  24x  City  fetch  OwnerView.kt:31  SELECT c.id, c.name FROM city c WHERE c.id = ?
	13 ms  70 rows   7x  Pet          PetService.kt:52  SELECT p.id, p.name FROM pet p WHERE p.owner_id = ?
	advice: 24 statements each fetched one City, 28 ms, 1.2 ms each, in 24 transactions of their own.
		at: OwnerView.kt:31
		fix: an inline foreign key joins it into every read of the rows
		or: a query that fetches the references, such as select().fetch(...), resolves them in the statement that reads them.
		see: https://orm.st/docs/advice#repeated-fetch
```

A repeat is counted per statement shape and call site, so a shape repeated from two loops is two pieces of advice, each pointing at its own loop and weighed by its own cost. Each piece states the time its executions spent in the database, so forty reads that cost a tenth of a millisecond each read differently from forty that cost thirty, and the call site of the statement where the scope recorded call sites. Executions that ran concurrently, such as a fan-out that checks each row in a transaction of its own, add up to more than they cost the caller, so where the sum is at least twice the time they covered on the clock the line states both: `727 ms over 140 ms elapsed`. A summary with several pieces orders them by the time on the clock, after the advice on correctness, so the first line is the one most worth reading. A repeat that took less than 50 ms on the clock carries no advice: most repeats in a larger application are correct and cost a few milliseconds, such as lookups of reference rows or a guard that keeps a task idempotent, and they would bury the few worth a change. `storm.advice.repeat_floor` (Spring Boot `storm.advice.repeat-floor`, Ktor `adviceRepeatFloor`) sets the floor, and `0` advises on every repeat; the table lists every shape either way. One run is a rough judge of a repeat near the floor, since the first run after startup reads on cold caches and a concurrent repeat varies with how its executions overlap, so read the advice as something to work through over a few runs. A write that ran in a transaction of its own every time is told that a batch commits the rows together, which is what changes. The advice is worked out from the summary when it is printed, so it costs nothing beyond the summary itself, and it links to its section of [Statement Advice](advice.md).

The headline also counts what cost nothing: `3 from cache` reports the reads the transaction's entity cache served without a statement: a reference resolving to an entity the transaction had already read, or an identity lookup at `REPEATABLE_READ` and above. The fetch count is the cache misses; this is the other side, and it appears only when any read was served.

A scope covers whatever runs inside it, whichever repository, query builder or template issued the statement. Summaries log under `st.orm.sql.perf` at `INFO`, and statements are recorded only while that logger is enabled.

### Per Entry Point

Both integrations can wrap every way work enters the application, which needs no code change and no annotation.

<Tabs groupId="framework">
<TabItem value="spring" label="Spring Boot" default>

```yaml
storm:
  sql-log:
    performance:
      enabled: true
      limit: 200    # statements the table lists per unit of work; the count and the repeat advice cover the rest
```

A servlet filter wraps each HTTP request. The same switch covers the entry points a filter cannot see: a method annotated `@Scheduled`, `@KafkaListener`, `@RabbitListener`, `@JmsListener`, or `@SqsListener` (including the `@KafkaHandler`/`@RabbitHandler` methods of a class-level listener) reports as its own summary, named after the method (`ReportJob.nightly`), so a worker without a web layer reports the same way a web application does. Matching is by annotation name, directly present on the bean method, so a listener library that is absent from the classpath costs nothing, and `storm.sql-log.performance.entry-points` replaces the set for others, such as a Pulsar listener or an application's own dispatch annotation. A final class or method cannot be proxied; open a scope inside it instead.

For production, thresholds turn the scope into a guardrail: only units of work that exceed one are reported, at WARN.

```yaml
storm:
  sql-log:
    performance:
      enabled: true
      threshold:
        statements: 50
        duration: 500ms
```

</TabItem>
<TabItem value="ktor" label="Ktor">

```kotlin
install(Storm) {
    sqlLogPerformance = true
    sqlLogPerformanceLimit = 200
}
```

Every `sqlLog*` option can come from `application.conf` instead, under `storm.sqlLog` (or `storm.sql_log`), so the log can be switched on in production without a redeploy; a plugin setting overrides the configuration file per option.

For production, thresholds turn the scope into a guardrail: only calls that exceed one are reported, at WARN.

```hocon
storm.sqlLog {
    performance {
        enabled = true
        threshold {
            statements = 50
            duration = 500ms
        }
    }
}
```

The same thresholds in the plugin configuration are `sqlLogPerformanceStatementThreshold = 50` and `sqlLogPerformanceDurationThreshold = 500.milliseconds`.

</TabItem>
</Tabs>

The boundary names the summary: a request after its route (`GET /owners/42`), an entry-point method after itself (`ReportJob.nightly`). A unit of work that touches no database produces no line.

The invoking thread is what this covers, which is where a request handler, a scheduled task or a blocking listener does its work. An application whose work runs in coroutines opens the scope inside the coroutine instead, where every child inherits it; see below.

### A Narrower Boundary

To measure one service method rather than a whole request, open a scope directly. The summary reports the same way: under `st.orm.sql.perf`, only while that logger is enabled.

<Tabs groupId="language">
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
val owners = sqlLog("importOwners") {
    ownerService.importAll(batch)
}
```

The scope follows the coroutine, so it keeps recording across a suspension that resumes on another thread, and every coroutine launched inside it is covered. A scope opened by one coroutine is never observed by another.

Kotlin code that runs outside coroutines, such as a Spring MVC controller, opens the same scope with `sqlLogBlocking`, mirroring how transactions ship as the `transaction` and `transactionBlocking` pair:

```kotlin
fun loadOwners(): List<OwnerView> = sqlLogBlocking("loadOwners") {
    ownerService.loadAll()
}
```

Code that builds its own coroutine from blocking code passes the scope along explicitly:

```kotlin
fun loadOwners(ids: List<Int>): List<Owner> = runBlocking(sqlLogContext()) {
    ids.map { async { owners.getById(it) } }.awaitAll()
}
```

`transaction { }` and `withTransactionOptions { }` carry it already, so work below them needs nothing.

</TabItem>
<TabItem value="java" label="Java">

```java
try (var scope = SqlLog.open("importOwners")) {
    ownerService.importAll(batch);
}
```

The scope follows the thread that opened it. Work handed to another thread, including a subtask forked from a `StructuredTaskScope`, falls outside it.

</TabItem>
</Tabs>

### The Summary Is the Report

A scope hands nothing back: the summary reports through the `st.orm.sql.perf` logger, and the logger is the only switch. That is deliberate. Each number a summary shows already has a home for programmatic use: production metrics are the [Micrometer observations](spring-integration.md#observability), and test assertions are [`SqlCapture`](testing.md). So the summary stays a report to be read, not an API to be coupled to.

Parameter values are absent by design: they are database values, and a summary is meant to be safe to log in production. Raise `st.orm.sql` to `TRACE` to see values.

A fetch served by the transaction's entity cache issues no statement, so the fetch count counts distinct cache misses rather than `fetch()` call sites.

---
## The Slow Statement Log

A summary judges a call; it does not name the one execution inside it that took 900 ms, and a summary's thresholds are the call's: three statements taking a second between them trip nothing. Outside a scope, in background work or on a thread no boundary wraps, nothing times anything at all. The slow statement log fills that gap: one line per execution whose database time exceeds a threshold, wherever it runs, under `st.orm.sql.slow` at `WARN`.

<Tabs groupId="framework">
<TabItem value="spring" label="Spring Boot" default>

```yaml
storm:
  sql-log:
    slow:
      threshold: 200ms
```

Independent of `storm.sql-log.performance.enabled`: the slow log needs no boundary and applies with or without the performance log.

Left unset while the performance log runs against a duration threshold, it takes that duration. A call that exceeds a duration holds at least one execution, so a statement threshold no lower than the call's can only be exceeded inside a call that is reported anyway: the derived default names the statement behind a warning rather than adding warnings of its own. Set it explicitly to report at a grain the performance log does not.

</TabItem>
<TabItem value="ktor" label="Ktor">

```kotlin
install(Storm) {
    sqlLogSlowThreshold = 200.milliseconds
}
```

Or from `application.conf`, under `storm.sqlLog.slow.threshold` (or `storm.sql_log.slow.threshold`). Independent of `sqlLogPerformance`: the slow log needs no request boundary and applies with or without the performance log. Left unset while the performance log runs against `performance.threshold.duration`, it takes that duration, for the reason above.

</TabItem>
<TabItem value="jvm" label="Plain JVM">

```
-Dstorm.sql_log.slow.threshold=200ms
```

A number with a unit (`200ms`, `2s`), a bare number of milliseconds, or an ISO-8601 duration.

</TabItem>
</Tabs>

```
SQL slow (SELECT Pet): 1840 ms in database, 3 rows, PetService.kt:42
	SELECT p.id, p.name, p.owner_id, o.id, o.first_name, o.city_id, c.id, c.name
	FROM pet p
	INNER JOIN owner o ON o.id = p.owner_id
	INNER JOIN city c ON c.id = o.city_id
	WHERE o.city_id IN (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
	shape: 9b34305d1a7c4e02 (typically 6.0 ms, 306x)  parameters: 32 (typically 3)  comment: traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'
```

The statement is printed as sent, in full; the example is abridged to fit the page.

The decision is made where the statement returns from the database, on the thread that executed it. That is what makes the line cheap and complete at once: the call site is walked only for the executions that turned out slow, at no cost to the rest, so every slow line names its caller without the per-execution stack walk that makes call sites an opt-in for the performance log; and the log follows no request, entry point or coroutine context, so an execution on a background worker or a dispatcher thread is reported the same as one inside a request. The line is written when the execution completes, so it also carries the rows.

Reading the line, headline first:

- **`1840 ms in database`** is the database time, prepare to return. A read whose rows took a while to consume adds `12400 rows read over 3200 ms`, so a large stream is not mistaken for a slow query, and a slow query is not hidden inside a large stream.
- **`3 rows`** produced or affected, marked `*` when the count is a lower bound (a batch entry answered with `SUCCESS_NO_INFO`, a stream closed before its end). A batch reads `(INSERT Visit, batch)` and its rows are the rows the batch affected. An execution that failed, a lock wait or a statement timeout that ran its course before the database gave up, reads `failed (SQLTimeoutException)` instead: its time was spent all the same, and the line carries what the caller's exception does not, the call site and the baseline. The failure is named by class alone, since a driver's message may quote values.
- **`PetService.kt:42`** is the application frame that caused the execution, subject to the same `call-site-skip` setting the performance log uses.
- **`shape: 9b34305d1a7c4e02 (typically 6.0 ms, 306x)`** is the answer to the question a slow statement raises first: is this statement always this slow, or was it these parameters? The shape is the statement's template identity, the same `storm.shape` the metrics are tagged with, and stable across parameter expansion, so an `IN` list of 3 and one of 32 are one shape. While the slow log is on, each shape keeps a baseline of what it typically costs over its recent minutes (a geometric mean, which an outlier barely moves; reported once eight other executions back it). `typically 6.0 ms, 306x` says the shape is normally fast and this execution was not: look at the values, or at a plan that changed. `typically 310 ms` with no multiplier says the statement is always this slow: look at the query, the graph its type declares (see [Entity Design](entity-design.md)) and its indexes.
- **`parameters: 32 (typically 3)`** is the parameter profile, safe to print in production: the count this execution bound against what the shape typically binds, shown when they differ by half or double. It names an oversized `IN` list, the commonest way a fast shape turns slow, without printing a value.
- **`comment: traceparent='…'`** is the SQL comment the statement carried, when a [`SqlCommenter`](spring-integration.md#observability) is configured. It is the key that joins this line to the database's own record of the same execution; see below.
- **`advice: …`** follows on a line of its own where the statement's own shape says why it reads more than it needs to: a search of short words alone, a `distinct` over a join that repeats rows, the count behind a page, or a deep offset. The first line says what is wrong, and each line below it, indented one level deeper, holds one more part: `fix:` the remedy, `or:` an alternative, and facts such as `at:` the call site, `cost:` what each execution costs, `index:` and `covering:` for an index, `longest idle:` for a transaction. The last line, `see:`, links to its section of [Statement Advice](advice.md), which says what the advice means and what to use instead.

The values themselves render while the logger is at `TRACE`, inlined into the statement so it can be pasted into a console, under the rule every Storm SQL logger follows; the line stays at `WARN`, since a slow execution is one whatever detail it is reported with. `st.orm.sql.slow` is a child of `st.orm.sql`, so raising that to `TRACE` for statement logging raises the slow log with it, and no other level shows a value.

Under a degraded database every statement is slow. Lines are rate-limited per shape, five per minute by default, and the first line of a shape after suppressed ones carries `+37 suppressed`, so the log names every shape that suffers without drowning in any of them. `storm.sql-log.slow.limit` (Spring), `sqlLogSlowLimit` / `storm.sqlLog.slow.limit` (Ktor) or the `storm.sql_log.slow.limit` system property sets the lines per shape per minute; `0` lifts the limit. A statement with no shape of its own, and every shape past the four thousand tracked, counts against one budget shared between them: those lines carry no `typically`, but the limit holds for them too.

### A Transaction That Holds Its Connection

A transaction whose statements are all fast can still hold its connection, and from its first write the row locks of its writes, for seconds: a call to another service, a mail sent or a document rendered between a write and the commit. No statement line shows it, since no statement is slow. A second threshold sets how long a transaction may hold its connection beyond what its statements spent on it; a transaction idle for longer is reported under `st.orm.sql.slow` at `WARN` when it completes, with the call site that began it. The threshold is one second by default: what is slow for a statement depends on the database, so the statement threshold has no default, but a connection held for a second while the application does other work is wrong on any database. Retune it where a transaction holds its connection by design, or set it to `0` to stop watching transactions.

<Tabs groupId="framework">
<TabItem value="spring" label="Spring Boot" default>

```yaml
storm:
  sql-log:
    slow:
      transaction: 2s
```

</TabItem>
<TabItem value="ktor" label="Ktor">

```kotlin
install(Storm) {
    sqlLogSlowTransaction = 2.seconds
}
```

Or from `application.conf`, under `storm.sqlLog.slow.transaction`.

</TabItem>
<TabItem value="jvm" label="Plain JVM">

```
-Dstorm.sql_log.slow.transaction=2s
```

</TabItem>
</Tabs>

```
SQL slow (transaction): held its connection 2300 ms, 120 ms in its 3 statements, wrote, OrderService.java:42
	advice: the transaction held its connection 2300 ms, 2180 ms of it idle after its last statement, with its row locks held for 2100 ms of that.
		fix: move that work before the transaction or after it commits.
		see: https://orm.st/docs/advice#idle-transaction
```

The time is measured from the moment the transaction takes its connection to the moment it returns it, against the time its statements spent on it, from preparation to close, so a statement whose rows are read slowly counts as time in the statement. The advice says whether the idle time came before the first statement, between the statements or after the last, and between them names the longest gap with the statements around it and how many gaps there were; see [Statement Advice](advice.md#idle-transaction) for the remedy each calls for. The row locks are claimed for the idle time from the first write on, the commit included: a transaction that did its work before its first write held no locks during it, and the line says the locks were held for the commit alone. A Spring-managed transaction is measured from Storm's first statement in it, since Spring opened the connection before Storm saw the transaction, and its line says `from its first statement`. A joined block is part of the transaction it joined, reported once; a `REQUIRES_NEW` block is measured on its own. The lines share one rate limit, as the lines of a shape do.

A measurement against a threshold rather than a check of the code, so it runs in production: two clock reads per transaction and a few additions per statement in one, plus a walk of the stack when a gap of 1 ms or more between statements is the longest so far, which happens a handful of times per transaction, and nothing for a statement outside a transaction, which stays on the path it takes while no slow log runs at all. Switched off, a transaction costs one volatile read. `@AcceptAdvice("idle-transaction")` on the method that runs the transaction accepts the advice there, for a transaction that holds its connection by design. See [Statement Advice](advice.md#idle-transaction).

### Retuning A Running Application

What a slow threshold should be is a question a degraded deployment answers better than a configuration file written months earlier, and that is the deployment a restart costs the most. Every setting the log reports with is read per unit of work, so it can be replaced while the application runs.

<Tabs groupId="framework">
<TabItem value="spring" label="Spring Boot" default>

Where the actuator is on the classpath, the `storm` endpoint reads and sets both halves of the log. It is Storm's control surface on a running application, one section per subject, so anything else Storm learns to be told at runtime arrives beside these rather than as a second endpoint. Expose it as any other endpoint:

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,storm
```

```
GET  /actuator/storm
POST /actuator/storm   {"slowStatement": "200ms", "duration": "1s", "callSites": true}
```

`slowStatement`, `slowStatementLimit` and `slowTransaction` set the slow statement log; `statements`, `duration`, `callSites` and `limit` set every performance boundary. What the request leaves out stays as it is, and `off` removes a threshold rather than setting one. It changes a running process only: nothing is written back to the configuration, and a restart returns to it.

Since the endpoint reads and changes what a deployment reports, it belongs behind the same authorization as the other write endpoints.

</TabItem>
<TabItem value="jvm" label="Plain JVM">

`SlowStatementLog.threshold(Duration)` and `SlowStatementLog.limit(int)` are safe to call at any time; the write lands on the next execution to return from the database. Wire them to whatever control surface the application already has.

</TabItem>
</Tabs>

`storm.sql-log.performance.enabled` is the exception. It decides whether the request filter and the entry-point proxies exist, and neither can be installed into a context that has already refreshed. An application that wants the performance log reachable in production enables it and leaves the thresholds high; lowering a threshold then costs nothing until it is lowered. The slow statement log needs no such plumbing and can be switched on from off.

A shape's baseline is learned while the log runs, so lines reported shortly after switching it on carry no `typically`. The performance threshold the slow threshold is derived from is read at startup, so retuning the performance threshold leaves the slow threshold where it is; set both when both are meant to move.

### The Database Side

Storm can say what ran, from where, with which parameter profile and how it compares to its own history. How the database executed it, the plan, is the database's to say, and every database keeps a record of exactly the slow executions this log reports: PostgreSQL's `log_min_duration_statement` and `auto_explain`, the MySQL and MariaDB slow query log, and their equivalents elsewhere. With `storm.tracing.sql-comments` on, the trace context travels into the statement as a comment, the database's record carries that comment, and the slow line prints it, so the two sides of one execution are joined by a key both already have. That is the production answer to "analyze it": Storm's line and the database's plan, side by side, for the actual execution that was slow.

Storm does not run `EXPLAIN` on your behalf. Beyond the connection and dialect plumbing, `EXPLAIN` with the values filled in shows the plan the database would choose for those literals, and a prepared statement that has run a few times may have executed a different, generic plan; the explanation could show a fast plan for a slow query. The database's own record shows the plan that ran.

---

## The Advice Log

Some statements are wrong to write whether or not any one execution is slow, such as a `limit` without an ORDER BY, which returns an arbitrary part of the result. Others cost more than they need to as the data grows, such as a deep offset. Storm decides this when it builds the statement, from the statement's own shape, and attaches the advice to it. The advice log reports it, fast or slow:

```
SQL advice (SELECT Owner): 0.8 ms in database, 5 rows, OwnerController.kt:31
	SELECT o.id, o.first_name, o.last_name, ...
	FROM owner o
	LIMIT 20
	shape: 4c1e07a9b2d35f18
	advice: the statement takes part of the result without an ORDER BY, so the database picks the rows, which can change between executions, and pages can repeat or skip rows.
		fix: order by a unique key, or by sort fields that end in one.
		see: https://orm.st/docs/advice#unordered-limit
```

Each piece of advice has a category, correctness, performance or design, and the log reports the categories the application asks for:

```yaml
storm:
  advice:
    categories: correctness, design, performance   # default: correctness, all three in development
    entity-budget:
      joins: 10      # the design budget design advice checks entities against
      columns: 96
    repeat-floor: 50ms   # the time a repeat must have taken before it carries advice; 0 for every repeat
```

- **Correctness by default.** Advice on correctness, such as a `limit` without an ORDER BY, reports at `INFO`, so an application that logs at `INFO` sees it without asking, in production too.
- **Design and performance in development.** Spring Boot with DevTools on the classpath, Ktor in development mode, and every `@StormTest` report all three categories, unless the categories are named. Design advice, such as an entity whose read exceeds the [design budget](entity-design.md#the-budget), is reported once per entity, when the first template checks the model at startup or when the entity is first used, so a production deployment never runs the check. Design advice that only real use can show comes with the slow statement log and the performance log instead; see [Statement Advice](advice.md#design-known-in-advance-and-shown-by-real-use).
- **Named categories win.** Advice on performance, such as a deep offset or a `distinct` over a repeating join, reports in development or once its category is named: `storm.advice.categories`, in Spring Boot, Ktor or as a system property. A page that reports its total is a deliberate choice of `page` over `slice`, so its count is advised only by the [slow statement log](#the-slow-statement-log), which prints all advice under every slow line.
- **Accept with `@AcceptAdvice`.** Put it on the entity, method, class or repository where the advice is as intended, naming the advice by its anchor, as in each line's link: `@AcceptAdvice("deep-offset")`. The log stops reporting it there; see [Accepting advice](advice.md#accepting-advice). Each piece of advice also has a logger of its own, `st.orm.sql.advice.<anchor>`, which `OFF` turns off everywhere.

The level of `st.orm.sql.advice` decides how often:

| Level | How often |
|-------|-----------|
| `INFO` | once per piece of advice, at the first statement that drew it |
| `DEBUG` | once per statement shape |
| `TRACE` | at every execution |

- **Quiet at `INFO`.** An application sees at most one line for each piece of advice.
- **A list at `DEBUG`.** Each piece of advice is reported the first time a statement of its shape executes, then not again, so the log reads as a list of what to change. Turn it on while developing or in a test environment, run the application, and work through the lines. A page's offset is part of its statement, so each page of the same query is a shape of its own. The log remembers the last four thousand pieces of advice it reported; past that it starts over, which can repeat a line but never changes what one says.
- **Cheap.** Advice is decided when the statement is built and cached with it. A statement without advice pays one test of whether it carries any; only a statement with advice asks its loggers whether they are on, and then whether it reported the advice before. A line goes out when the execution closes, so it states the database time and the rows of the execution that drew it.
- **A transaction's repeats, as one block.** While the log reports performance and design advice, every transaction counts the statements on its connection, and the same single-row read or write run ten or more times, or ten or more fetches of one type, is reported when the transaction completes, under the call site that began it, each line with the time the executions spent and the call site of the statement. The call site is walked where the transaction's block is entered, since a suspending transaction completes on the dispatcher that ran its block, with no caller on the stack; a transaction another manager opened, such as a Spring `@Transactional` method, is named by the method still on the stack when it completes. The block is counted per call site rather than per piece of advice: once per piece of advice there at `INFO`, once per repeat there at `DEBUG`, every time at `TRACE`. A piece of advice whose own logger is `OFF` leaves the block. A transaction that held its connection idle beyond the [slow statement log's threshold](#a-transaction-that-holds-its-connection) carries that advice in its block too, after the repeats or alone, so the block is not lost with a repeat under the floor. A joined block counts toward the transaction it joined; a `REQUIRES_NEW` block is a transaction of its own. The record is a small map per transaction, kept only while the advice is reported and discarded at completion. Repeats across transactions are the [performance log](#the-performance-log)'s.

```
SQL advice (transaction): VisitReminderService.kt:41
	advice: the same SELECT of Visit ran 40 times, 120 ms, 3.0 ms each, one row each.
		at: VisitReminderService.kt:52
		fix: one query for all of them, such as findAllById or a where with IN, reads the rows in one round trip.
		see: https://orm.st/docs/advice#repeated-select
```
- **No values.** The line carries the statement with placeholders, the call site and the shape, never a parameter value, at any level.

Every piece of advice links to its section of [Statement Advice](advice.md), which says what it means and what to use instead.

## Tips

1. **Reach for `st.orm.sql` to see statements, a scope to judge a call, the slow log to catch the one execution.** One answers what ran, one what it cost, one which statement to look at first.
2. **Keep `TRACE` out of production.** `DEBUG` gives you the statements on demand through log configuration alone; `TRACE` adds the parameter values, which are database values.
3. **Scope with the type logger** (`st.orm.sql.Owner`) rather than raising the root, so narrowing the focus needs a config change rather than a redeploy.
4. **Read the top row first.** It is the statement that cost the most in total, whether it was slow once or cheap many times. A `fetch` row is one you can often remove by naming the reference in the query's fetch plan.
5. **Compare `in database` against `total`.** A large gap says the call is slow for reasons the query layer cannot fix.
6. **Assert query counts in tests** with `SqlCapture` rather than reading logs. See [Testing](testing.md) for `count(Origin.FETCH)`.
7. **Leave `slow.threshold` on in production**, at a threshold that means something for your database (200 ms is a common start). It costs a volatile read per execution until something is slow, and the line it writes is the one you would otherwise reconstruct from a metric spike, a request log and a guess. Where it has to stay off, expose the endpoint instead, so switching it on during an incident does not need a deploy.
8. **Read `typically` before the statement.** A shape that is normally fast points at the parameters or the plan; a shape that is always slow points at the query.
