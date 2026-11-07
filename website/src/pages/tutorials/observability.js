import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: SQL logging, summaries and the slow statement log. Storm-way
// how-to; content verified against docs/sql-logging.md (st.orm.sql logger,
// per-type child loggers, TRACE values, st.orm.sql.perf summaries,
// st.orm.sql.slow), StormProperties (storm.sql-log.performance.*,
// storm.sql-log.slow.*) and SqlLogRenderer/SlowStatementLog (line formats).

const TITLE = 'Seeing What Storm Does: The SQL Log';
const DESC =
  'Raise a log level to see every statement as it executes, raise it to TRACE ' +
  'to get SQL you can paste into a console, turn on per-request summaries to ' +
  'see what a call cost the database, and let the slow statement log name the ' +
  'one execution that took too long.';

const CODE_LOGGER = [
  C('# application.yml: the log level is the only switch\n'),
  P('logging:\n'),
  P('  level:\n'),
  P('    st.orm.sql.City: ', ), S('DEBUG'), P('   '), C('# one type, not every statement\n'),
].join('');

const SQL_STATEMENT = [
  QC('-- SQL (SELECT City):'), '\n',
  QK('SELECT'), ' c.id, c.name\n',
  QK('FROM'), ' city c\n',
  QK('WHERE'), ' c.id = ', QQ('?'),
].join('');

const CODE_TRACE = [
  C('# TRACE renders the bound values into the statement\n'),
  P('logging:\n'),
  P('  level:\n'),
  P('    st.orm.sql.City: ', ), S('TRACE'), P('\n'),
].join('');

const SQL_TRACE = [
  QC('-- SQL (SELECT City):'), '\n',
  QK('SELECT'), ' c.id, c.name\n',
  QK('FROM'), ' city c\n',
  QK('WHERE'), ' c.id = ', QQ('2'),
].join('');

const CODE_SUMMARY = [
  C('# One summary per unit of work: HTTP requests, @Scheduled methods,\n'),
  C('# and Kafka, RabbitMQ, JMS and SQS listener methods\n'),
  P('storm:\n'),
  P('  sql-log:\n'),
  P('    performance:\n'),
  P('      enabled: '), K('true'), P('\n'),
  P('      threshold:\n'),
  P('        statements: '), N('50'), P('    '), C('# report only the calls that exceed\n'),
  P('        duration: '), S('500ms'), P('  '), C('# a threshold, at WARN\n'),
].join('');

const CODE_SUMMARY_OUT = [
  P('SQL (GET /owners): 19 statements, 8 fetches, 142 ms in database, 678 ms total\n'),
  P('    96 ms  6408 rows  7x  Visit         SELECT v.id, v.visit_date FROM visit v WHERE v.pet_id = ?\n'),
  P('    28 ms     8 rows  8x  City   '), K('fetch'), P('  SELECT c.id, c.name FROM city c WHERE c.id = ?\n'),
  P('    18 ms   112 rows  4x  Pet           SELECT p.id, p.name FROM pet p WHERE p.owner_id = ?'),
].join('');

const CODE_SLOW = [
  C('# One line per execution slower than the threshold, wherever it runs\n'),
  P('storm:\n'),
  P('  sql-log:\n'),
  P('    slow:\n'),
  P('      threshold: '), S('200ms'), P('\n'),
].join('');

const CODE_SLOW_OUT = [
  P('SQL slow (SELECT Owner): 1840 ms in database, 3 rows, OwnerService.kt:42\n'),
  P('    SELECT o.id, o.first_name, o.last_name, ...\n'),
  P('    FROM owner o\n'),
  P('    WHERE o.last_name = ?\n'),
  P('    shape: 9b34305d1a7c4e02 (typically 6.0 ms, 306x)  parameters: 1'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Observability</div>
  <h1>Seeing what <span class="grad">Storm does</span></h1>
  <p class="dek">Storm logs every statement where it executes. A log level shows the SQL, <code>TRACE</code> adds the values, a summary per request shows what the call cost, and the slow statement log names the execution that took too long.</p>
  <div class="meta"><span>Series · The Storm way</span><span>4 min read</span><span>Kotlin · Spring Boot</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A query misbehaves in one corner of the application. You want the exact SQL that corner runs, a way to reproduce it in a database client, and a way to confirm the fix, without logging every statement the system runs.</p>

  <h2><span class="hno">02</span>Statements, scoped by type</h2>
  <p>Storm logs statements under the <code>st.orm.sql</code> logger. The log level is the only switch: nothing to annotate, and the execution path stays the one that runs in production. Each entity and projection also logs under a child logger, <code>st.orm.sql.&lt;Type&gt;</code>, so you can raise the level for the type you are investigating:</p>
  ${editor({file: 'application.yml', tag: 'YAML', code: CODE_LOGGER, sql: SQL_STATEMENT})}
  <p>Each statement logs once, headed by the operation and the type. A statement that resolves a reference reads <code>SQL (SELECT City, fetch)</code>, which tells it apart from a primary key lookup your code asked for.</p>

  <h2><span class="hno">03</span>SQL you can paste</h2>
  <p>At <code>DEBUG</code> the log shows the statement as sent, with placeholders. At <code>TRACE</code> the bound values are rendered into it, so you can paste it into a client and run it, or check its plan:</p>
  ${editor({file: 'application.yml', tag: 'YAML', code: CODE_TRACE, sql: SQL_TRACE})}
  <p>Values are database values: personal data, tokens, whatever your entities carry. Every Storm SQL logger renders them only at <code>TRACE</code>. Leave <code>DEBUG</code> available for diagnosis in production and keep <code>TRACE</code> out of it.</p>

  <h2><span class="hno">04</span>What one call cost</h2>
  <p>A statement log tells you what ran. To decide what to change you need the total per unit of work. In Spring Boot, one property turns every way work enters the application into a summary: HTTP requests, <code>@Scheduled</code> methods, and Kafka, RabbitMQ, JMS and SQS listener methods. The summary has one row per distinct statement, sorted by total time, so a statement that ran eight times cheaply ranks above one slow statement when it cost more in total:</p>
  ${editor({file: 'application.yml', tag: 'YAML', code: CODE_SUMMARY})}
  ${editor({file: 'console', tag: 'st.orm.sql.perf', code: CODE_SUMMARY_OUT})}
  <p>Each row shows the time, rows, executions and type, and <code>fetch</code> marks the statements that resolved references. Comparing <code>142 ms in database</code> with <code>678 ms total</code> tells you most of the call was spent outside the database. The summary carries no parameter values, so it is safe to log in production, and with thresholds it reports only the calls that exceed one. To measure a smaller block than a request, wrap it in <code>sqlLog("importOwners")&nbsp;{ }</code>. In Ktor, <code>sqlLogPerformance = true</code> on the plugin does the same per request.</p>

  <h2><span class="hno">05</span>The one slow execution</h2>
  <p>A summary judges a whole call. The slow statement log reports single executions: one line under <code>st.orm.sql.slow</code> at <code>WARN</code> for every execution whose database time exceeds the threshold, in a request or in background work, with the call site that ran it:</p>
  ${editor({file: 'application.yml', tag: 'YAML', code: CODE_SLOW})}
  ${editor({file: 'console', tag: 'st.orm.sql.slow', code: CODE_SLOW_OUT})}
  <p>The <code>shape</code> line answers the first question a slow statement raises. <code>typically 6.0 ms, 306x</code> says this statement is normally fast and this execution was not, so look at the values or a changed plan. A shape that reads <code>typically 310 ms</code> with no multiplier is always slow, so look at the query and its indexes. The same log reports a transaction that held its connection idle, by default once the idle time passes one second. Where Storm can tell from the statement what to change, an <code>advice:</code> line follows; <a class="tlink" href="/tutorials/advice">Statement advice</a> walks through it.</p>

  <h2><span class="hno">06</span>Beyond logging</h2>
  <p>In tests, <a class="tlink" href="/tutorials/testing">SqlCapture</a> turns statement counts and origins into assertions, so what you found while debugging becomes a regression test. In production, the <code>storm-micrometer</code> module reports every query and transaction as a Micrometer observation, and the <code>storm.origin</code> tag separates statements your code asked for from statements that resolved a reference, so you can chart and alert on the latter. JMX metrics cover the template cache, the entity cache and dirty checking; see <a class="tlink" href="/docs/metrics">Metrics</a>.</p>

  <h2><span class="hno">07</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/sql-logging">SQL Logging</a>
    <a href="/docs/advice">Statement Advice</a>
    <a href="/docs/metrics">Metrics</a>
    <a href="/docs/testing">Testing</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ObservabilityTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="observability" body={BODY} />;
}
