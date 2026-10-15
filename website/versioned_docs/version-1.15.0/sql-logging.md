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

- **Call sites** name the application frame that caused each execution (`VisitService.kt:88`), which is the identity a developer thinks in. An application with a database layer of its own declares it as plumbing, so rows name the caller beyond it: `storm.sql-log.call-site-skip` in Spring, `sqlLogCallSiteSkip` in Ktor, or the `storm.sql_log.call_site_skip` system property on a plain JVM. Entries ending in `.kt` or `.java` match by source file, which covers inline functions. Work resumed on another dispatcher has no caller on its stack at all; a context built with `sqlLogContext()` (and every context Storm builds, such as `transaction { }`) carries the launch site, captured while the caller is still on the stack, so such rows name the frame that launched the work. A stack that is plumbing end to end with no carried site reports its innermost plumbing frame rather than none. A stack walk per execution, so it is opt-in and suited to development: `storm.sql-log.performance.call-sites: true` in Spring, `sqlLogPerformanceCallSites = true` in Ktor, or the `callSites` parameter on a scope opened directly. A row seen from several frames shows the first plus `(+n sites)`.
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

Summed database time exceeds elapsed time whenever statements run concurrently, which is why both appear. The concurrency clause is omitted when nothing overlapped. And `214 ms in database` against `678 ms total` says most of that call was spent somewhere other than the database. Consuming a streamed read counts on the `total` side of that comparison, not the database side: a stream held open while the application works through it is the application's time.

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
      limit: 200    # statements recorded per unit of work; the count covers the rest
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
	shape 9b34305d1a7c4e02 (typically 6.0 ms, 306x)  parameters 32 (typically 3)  comment traceparent='00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01'
```

The statement is printed as sent, in full; the example is abridged to fit the page.

The decision is made where the statement returns from the database, on the thread that executed it. That is what makes the line cheap and complete at once: the call site is walked only for the executions that turned out slow, at no cost to the rest, so every slow line names its caller without the per-execution stack walk that makes call sites an opt-in for the performance log; and the log follows no request, entry point or coroutine context, so an execution on a background worker or a dispatcher thread is reported the same as one inside a request. The line is written when the execution completes, so it also carries the rows.

Reading the line, headline first:

- **`1840 ms in database`** is the database time, prepare to return. A read whose rows took a while to consume adds `12400 rows read over 3200 ms`, so a large stream is not mistaken for a slow query, and a slow query is not hidden inside a large stream.
- **`3 rows`** produced or affected, marked `*` when the count is a lower bound (a batch entry answered with `SUCCESS_NO_INFO`, a stream closed before its end). A batch reads `(INSERT Visit, batch)` and its rows are the rows the batch affected. An execution that failed, a lock wait or a statement timeout that ran its course before the database gave up, reads `failed (SQLTimeoutException)` instead: its time was spent all the same, and the line carries what the caller's exception does not, the call site and the baseline. The failure is named by class alone, since a driver's message may quote values.
- **`PetService.kt:42`** is the application frame that caused the execution, subject to the same `call-site-skip` setting the performance log uses.
- **`shape 9b34305d1a7c4e02 (typically 6.0 ms, 306x)`** is the answer to the question a slow statement raises first: is this statement always this slow, or was it these parameters? The shape is the statement's template identity, the same `storm.shape` the metrics are tagged with, and stable across parameter expansion, so an `IN` list of 3 and one of 32 are one shape. While the slow log is on, each shape keeps a baseline of what it typically costs over its recent minutes (a geometric mean, which an outlier barely moves; reported once eight other executions back it). `typically 6.0 ms, 306x` says the shape is normally fast and this execution was not: look at the values, or at a plan that changed. `typically 310 ms` with no multiplier says the statement is always this slow: look at the query, the graph its type declares (see [Entity Design](entity-design.md)) and its indexes.
- **`parameters 32 (typically 3)`** is the parameter profile, safe to print in production: the count this execution bound against what the shape typically binds, shown when they differ by half or double. It names an oversized `IN` list, the commonest way a fast shape turns slow, without printing a value.
- **`comment traceparent='…'`** is the SQL comment the statement carried, when a [`SqlCommenter`](spring-integration.md#observability) is configured. It is the key that joins this line to the database's own record of the same execution; see below.

The values themselves render while the logger is at `TRACE`, inlined into the statement so it can be pasted into a console, under the rule every Storm SQL logger follows; the line stays at `WARN`, since a slow execution is one whatever detail it is reported with. `st.orm.sql.slow` is a child of `st.orm.sql`, so raising that to `TRACE` for statement logging raises the slow log with it, and no other level shows a value.

Under a degraded database every statement is slow. Lines are rate-limited per shape, five per minute by default, and the first line of a shape after suppressed ones carries `+37 suppressed`, so the log names every shape that suffers without drowning in any of them. `storm.sql-log.slow.limit` (Spring), `sqlLogSlowLimit` / `storm.sqlLog.slow.limit` (Ktor) or the `storm.sql_log.slow.limit` system property sets the lines per shape per minute; `0` lifts the limit. A statement with no shape of its own, and every shape past the four thousand tracked, counts against one budget shared between them: those lines carry no `typically`, but the limit holds for them too.

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

`slowStatement` and `slowStatementLimit` set the slow statement log; `statements`, `duration`, `callSites` and `limit` set every performance boundary. What the request leaves out stays as it is, and `off` removes a threshold rather than setting one. It changes a running process only: nothing is written back to the configuration, and a restart returns to it.

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

## Tips

1. **Reach for `st.orm.sql` to see statements, a scope to judge a call, the slow log to catch the one execution.** One answers what ran, one what it cost, one which statement to look at first.
2. **Keep `TRACE` out of production.** `DEBUG` gives you the statements on demand through log configuration alone; `TRACE` adds the parameter values, which are database values.
3. **Scope with the type logger** (`st.orm.sql.Owner`) rather than raising the root, so narrowing the focus needs a config change rather than a redeploy.
4. **Read the top row first.** It is the statement that cost the most in total, whether it was slow once or cheap many times. A `fetch` row is one you can often remove by naming the reference in the query's fetch plan.
5. **Compare `in database` against `total`.** A large gap says the call is slow for reasons the query layer cannot fix.
6. **Assert query counts in tests** with `SqlCapture` rather than reading logs. See [Testing](testing.md) for `count(Origin.FETCH)`.
7. **Leave `slow.threshold` on in production**, at a threshold that means something for your database (200 ms is a common start). It costs a volatile read per execution until something is slow, and the line it writes is the one you would otherwise reconstruct from a metric spike, a request log and a guess. Where it has to stay off, expose the endpoint instead, so switching it on during an incident does not need a deploy.
8. **Read `typically` before the statement.** A shape that is normally fast points at the parameters or the plan; a shape that is always slow points at the query.
