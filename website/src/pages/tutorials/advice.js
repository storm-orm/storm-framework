import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, M, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: statement advice in the logs. Storm-way how-to; content verified
// against docs/advice.md and docs/sql-logging.md (advice log, slow statement
// log, idle transactions, @AcceptAdvice, storm.advice.* settings). The
// distinct-over-join and unordered-limit lines were captured from storm-core;
// the other lines follow the formats docs/advice.md documents.

const TITLE = 'Statement Advice: What Storm Tells You to Change';
const DESC =
  'Storm builds every statement, so it knows the shape, the entity graph, the ' +
  'indexes and what each transaction repeated. The log turns that into advice ' +
  'with a fix and a link, decided once per statement shape at no cost per execution.';

const CODE_CONFIG = [
  C('# application.yml\n'),
  P('storm:\n'),
  P('  sql-log:\n'),
  P('    slow:\n'),
  P('      threshold: '), S('200ms'), P('        '), C('# a slow line carries all advice its statement has\n'),
  P('  advice:\n'),
  P('    categories: '), S('correctness'), P('   '), C('# the default outside development\n\n'),
  P('logging:\n'),
  P('  level:\n'),
  P('    st.orm.sql.advice: '), S('INFO'), P('     '), C('# once per piece of advice; DEBUG: once per statement shape'),
].join('');

const CODE_DISTINCT = [
  C('// Owners with a pet called Leo\n'),
  K('val '), P('owners = orm.'), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(')\n'),
  P('    .'), F('select'), P('()\n'),
  P('    .'), F('innerJoin'), P('<'), T('Pet'), P('>().'), F('on'), P('<'), T('Owner'), P('>()\n'),
  P('    .'), F('where'), P('('), T('Pet_'), P('.name '), F('eq'), P(' '), S('"Leo"'), P(')\n'),
  P('    .'), F('distinct'), P('()\n'),
  P('    .resultList'),
].join('');

const LOG_DISTINCT = [
  P('SQL advice (SELECT Owner): 0.46 ms in database, 3 rows, OwnerService.kt:42\n'),
  M('	SELECT DISTINCT o.id, o.first_name, o.last_name, …\n'),
  M('	FROM owner o\n'),
  M('	INNER JOIN pet p ON p.owner_id = o.id\n'),
  M('	WHERE p.name = ?\n'),
  M('	shape: 919b615157cb9fda\n'),
  P('	'), K('advice:'), P(' the join to Pet repeats each row once per related row, and DISTINCT removes the copies afterwards.\n'),
  P('		'), K('fix:'), P(' semiJoin(Pet) filters by Pet without repeating rows, so the query needs no DISTINCT.\n'),
  P('		'), K('see:'), P(' https://orm.st/docs/advice#distinct-over-join'),
].join('');

const CODE_SEMI = [
  K('val '), P('owners = orm.'), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(')\n'),
  P('    .'), F('select'), P('()\n'),
  P('    .'), F('semiJoin'), P('<'), T('Pet'), P('>().'), F('on'), P('<'), T('Owner'), P('>()\n'),
  P('    .'), F('where'), P('('), T('Pet_'), P('.name '), F('eq'), P(' '), S('"Leo"'), P(')\n'),
  P('    .resultList'),
].join('');

const SQL_SEMI = [
  QK('SELECT'), ' o.id, o.first_name, o.last_name, …\n',
  QK('FROM'), ' owner o\n',
  QK('WHERE EXISTS'), ' (', QK('SELECT'), ' 1 ', QK('FROM'), ' pet p ', QK('WHERE'), ' p.owner_id = o.id ', QK('AND'), ' p.name = ', QQ('?'), ')\n',
  QC('-- each owner once, nothing to remove afterwards'),
].join('');

const CODE_REPEAT = [
  K('class '), T('VisitReminderService'), P('('), K('private val '), P('pets: '), T('PetRepository'), P(') {\n\n'),
  P('    '), K('fun '), F('remindersDue'), P('(petIds: '), T('List'), P('<'), T('Int'), P('>) = '), F('transactionBlocking'), P(' {   '), C('// line 41\n'),
  P('        petIds.'), F('map'), P(' { id -> pets.'), F('getById'), P('(id) }             '), C('// line 52\n'),
  P('            .'), F('filter'), P(' { it.'), F('dueForVaccination'), P('() }\n'),
  P('    }\n'),
  P('}'),
].join('');

const LOG_REPEAT = [
  P('SQL advice (transaction): VisitReminderService.kt:41\n'),
  P('	'), K('advice:'), P(' getById of Pet ran 40 times, 120 ms, 3.0 ms each, one row each.\n'),
  P('		'), K('at:'), P(' VisitReminderService.kt:52 -> PetRepository.getById\n'),
  P('		'), K('fix:'), P(' findAllById over the keys reads the rows in one round trip.\n'),
  P('		'), K('see:'), P(' https://orm.st/docs/advice#repeated-select'),
].join('');

const LOG_FK = [
  P('SQL slow (DELETE Owner): 2840 ms in database, 1 rows, OwnerService.kt:77\n'),
  M('	DELETE FROM owner WHERE id = ?\n'),
  M('	shape: 3f0a6c1d92be4471  parameters: 1\n'),
  P('	'), K('advice:'), P(' every row deleted from owner is checked against the foreign key pet(owner_id), and no index on pet starts with owner_id, so the database reads pet in full for each deleted row.\n'),
  P('		'), K('fix:'), P(' an index on pet(owner_id) serves the check.\n'),
  P('		'), K('see:'), P(' https://orm.st/docs/advice#unindexed-foreign-key'),
].join('');

const CODE_IDLE = [
  K('fun '), F('book'), P('(visit: '), T('Visit'), P(') = '), F('transactionBlocking'), P(' {\n'),
  P('    visits.'), F('insert'), P('(visit)\n'),
  P('    mailer.'), F('sendConfirmation'), P('(visit)     '), C('// a 2-second SMTP call inside the transaction\n'),
  P('}'),
].join('');

const LOG_IDLE = [
  P('SQL slow (transaction): held its connection 2300 ms, 4.0 ms in its statement, wrote, VisitService.kt:42\n'),
  P('	'), K('advice:'), P(' the transaction held its connection 2300 ms, 2290 ms of it idle after its last statement, with its row locks held for 2290 ms of that.\n'),
  P('		'), K('last statement:'), P(' the INSERT of Visit\n'),
  P('		'), K('fix:'), P(' the work after the INSERT of Visit can run after the commit; an onCommit callback runs it there.\n'),
  P('		'), K('see:'), P(' https://orm.st/docs/advice#idle-transaction'),
].join('');

const CODE_IDLE_FIX = [
  K('fun '), F('book'), P('(visit: '), T('Visit'), P(') = '), F('transactionBlocking'), P(' {\n'),
  P('    visits.'), F('insert'), P('(visit)\n'),
  P('    '), F('onCommit'), P(' { mailer.'), F('sendConfirmation'), P('(visit) }   '), C('// runs once the visit is committed\n'),
  P('}'),
].join('');

const LOG_BUDGET = [
  P('SQL advice (design Visit)\n'),
  P('	'), K('graph:'), P(' Visit(Pet(Owner(City), PetType, Clinic(Address, Organization(Country))))  12 joins, 140 columns beyond its own\n'),
  P('	'), K('advice:'), P(' every read of Visit joins 12 tables and selects 140 columns beyond its own, past the design budget of 10 joins and 96 columns.\n'),
  P('		'), K('fix:'), P(' a Ref for Clinic.organization removes 4 joins and 52 columns.\n'),
  P('		'), K('see:'), P(' https://orm.st/docs/advice#entity-budget'),
].join('');

const CODE_ACCEPT = [
  K('interface '), T('OwnerRepository'), P(' : '), T('EntityRepository'), P('<'), T('Owner'), P(', '), T('Int'), P('> {\n\n'),
  P('    '), C('// The admin screen shows "page 3 of 120", so it needs the total.\n'),
  P('    '), A('@AcceptAdvice'), P('('), S('"page-count"'), P(')\n'),
  P('    '), K('fun '), F('adminList'), P('(pageable: '), T('Pageable'), P('): '), T('Page'), P('<'), T('Owner'), P('> = '), F('select'), P('().'), F('page'), P('(pageable)\n'),
  P('}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Statement advice</div>
  <h1>Statement advice: <span class="grad">what to change, and why</span></h1>
  <p class="dek">Schema validation checks that your entities match the database. Statement advice checks how the application uses them. Storm builds every statement, so it knows the shape, the entity graph, the indexes and what each transaction repeated, and the log says what to change, with the fix and a link to the trade-off.</p>
  <div class="meta"><span>Series · The Storm way</span><span>8 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A pet clinic application passes its tests on a small database. In production, page two of the owner list shows an owner who was already on page one, the reminder job takes minutes, deleting an owner takes seconds, and the connection pool runs dry when the front desk opens. Each problem is visible in the code, on one specific line. You want to find those lines while you write the code, and have production point at the rest.</p>

  <h2><span class="hno">02</span>What Storm knows</h2>
  <p>Storm does not parse logs or guess from query text. It builds the statements, so it has four kinds of evidence:</p>
  <ul>
    <li><b>The statement's shape.</b> A limit without an order, a <code>distinct</code> over a join that repeats rows, a deep offset, a page that counts every row.</li>
    <li><b>The entity model.</b> How many tables and columns every read of an entity pulls in.</li>
    <li><b>The schema.</b> The indexes and foreign keys the database reports, read once when the schema is loaded. Spring Boot and Ktor do that as part of startup validation.</li>
    <li><b>What a unit of work did.</b> The same row looked up forty times in one transaction, or a transaction that held its connection while the application sent an email.</li>
  </ul>
  <p>None of this slows the statements down. Advice on a statement's shape is decided when Storm builds the statement and is cached with it, so a statement without advice pays one check. The index checks run only for an execution that was already slow. Repeats are counted only while the advice log reports performance advice, which is during development. Watching transactions for idle time costs two clock reads per transaction.</p>

  <h2><span class="hno">03</span>Turning it on</h2>
  <p>In development there is nothing to do. Spring Boot with DevTools, Ktor in development mode and every <code>@StormTest</code> report all three categories: correctness, performance and design. In production, correctness advice is on at <code>INFO</code>, because rows in no stated order are wrong wherever they run. Performance and design advice reach production on the lines of the slow statement log, once you set a threshold:</p>
  ${editor({file: 'application.yml', tag: 'YAML', code: CODE_CONFIG})}
  <p>At <code>INFO</code> the advice log reports each piece of advice once. At <code>DEBUG</code> it reports it once per statement shape, which gives you a list to work through after a test run.</p>

  <h2><span class="hno">04</span>Advice on a statement</h2>
  <p>The front desk wants every owner with a pet called Leo. A join plus <code>distinct</code> is the usual way to write that:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_DISTINCT})}
  <p>In development, the advice log reports it the first time the statement runs:</p>
  ${editor({file: 'console', tag: 'st.orm.sql.advice', code: LOG_DISTINCT})}
  <p>The join returns an owner once per matching pet, and <code>DISTINCT</code> sorts or hashes the whole result to remove the copies. A semi-join keeps each owner that has a matching pet, once:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_SEMI, sql: SQL_SEMI})}
  <p>Every line has the same layout: what the statement costs, a <code>fix:</code>, and a <code>see:</code> link to a section that explains the trade-off. The links stay valid as long as Storm gives the advice. The same mechanism catches a <code>limit</code> without an <code>orderBy</code> (correctness), an offset of a thousand rows or more (use <code>scroll</code>), and an <code>IN</code> list that runs in many lengths and fills the database's plan cache.</p>

  <h2><span class="hno">05</span>What a transaction repeated</h2>
  <p>No single statement shows an N+1 loop. Every lookup is fast; there are just forty of them. Storm counts the statements on each transaction's connection and reports the repeats when the transaction completes:</p>
  ${editor({file: 'VisitReminderService.kt', tag: 'Kotlin · Storm', code: CODE_REPEAT})}
  ${editor({file: 'console', tag: 'st.orm.sql.advice', code: LOG_REPEAT})}
  <p>The block is headed by the line that began the transaction, and <code>at:</code> names the loop and the repository method it called, in the direction of the call. The fix names the list form of the call. The same block reports a <code>Ref</code> fetched once per row, and a single-row insert or update run in a loop instead of as one batch. A repeat that cost less than 50 ms in total carries no advice, because most short repeats are correct and would bury the ones worth changing. Loops that open a transaction per item show up in the <a class="tlink" href="/tutorials/observability">performance log</a> summary instead.</p>

  <h2><span class="hno">06</span>Advice that needs evidence</h2>
  <p>Whether an index is worth having depends on how many rows a table holds and how few a condition selects, and Storm reads no row counts. So index advice waits for a slow execution and then checks the columns the statement used against the indexes the database reported. Deleting an owner is a good example, because the statement never names the table that makes it slow:</p>
  ${editor({file: 'console', tag: 'st.orm.sql.slow', code: LOG_FK})}
  <p>The database checks every foreign key that references <code>owner</code>. Without an index on <code>pet(owner_id)</code>, each check reads all pets. For a statement that filters, groups or sorts by a column no index starts with, <code>missing-index</code> advice names the index to create, with the column order that also serves the sort or the grouping. A <a class="tlink" href="/tutorials/search">search</a> that reads every row gets <code>unindexed-search</code> advice naming the index that serves it, such as a trigram index on PostgreSQL. The missing-index advice is tested against every supported database: a conformance test per dialect creates the index the advice names and reads the database's plan to confirm it is used.</p>

  <h2><span class="hno">07</span>A transaction that holds its connection</h2>
  <p>This one has its own threshold and runs in production. A transaction whose statements are all fast can still hold its connection, and the row locks of its writes, for seconds:</p>
  ${editor({file: 'VisitService.kt', tag: 'Kotlin · Storm', code: CODE_IDLE})}
  ${editor({file: 'console', tag: 'st.orm.sql.slow', code: LOG_IDLE})}
  <p>Storm times when the transaction took its connection and when it returned it, and subtracts what the statements spent. The advice says where the idle time went, before, between or after the statements, and the remedy follows from that. Here the mail goes after the commit:</p>
  ${editor({file: 'VisitService.kt', tag: 'Kotlin · Storm', code: CODE_IDLE_FIX})}
  <p>A transaction may hold its connection idle for one second by default; <code>storm.sql-log.slow.transaction</code> changes that.</p>

  <h2><span class="hno">08</span>Design advice</h2>
  <p>Every inline foreign key joins its table into every read of the entity. Graphs grow over time, usually through entities nobody was editing. In development, Storm checks each entity once against a design budget of 10 joins and 96 columns beyond its own:</p>
  ${editor({file: 'console', tag: 'st.orm.sql.advice', code: LOG_BUDGET})}
  <p>The advice names the field where a <code>Ref</code> brings the read back within budget, choosing the deepest field that is enough on its own. It never names an identifying foreign key or a lookup table. Within the budget, only real use advises: a slow read of a wide entity says what a <code>Ref</code> would save, and a <code>Ref</code> fetched in every unit of work says it should be inline. See <a class="tlink" href="/docs/entity-design">Entity Design</a>.</p>

  <h2><span class="hno">09</span>Accepting advice</h2>
  <p>Advice is a suggestion. Where the code is as intended, accept it by its anchor, the part of the link after <code>#</code>:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · Storm', code: CODE_ACCEPT})}
  <p><code>@AcceptAdvice</code> works on an entity, a repository, a class or a method, including a service method whose lambdas and <code>transaction { }</code> blocks issue the statements. Whether a shape's advice is accepted is decided once per shape. The slow statement log still prints accepted advice, since a slow statement is a reason to look again. To turn one piece of advice off everywhere, set its logger to <code>OFF</code>, such as <code>st.orm.sql.advice.deep-offset</code>.</p>

  <h2><span class="hno">10</span>Where each piece of advice shows</h2>
  <table class="cmp">
    <tr><th>Advice</th><th>Category</th><th>Reported by</th></tr>
    <tr><td><code>unordered-limit</code></td><td>correctness</td><td>advice log, everywhere</td></tr>
    <tr><td><code>distinct-over-join</code>, <code>deep-offset</code>, <code>in-list-shapes</code></td><td>performance</td><td>advice log in development; every slow line</td></tr>
    <tr><td><code>repeated-select</code>, <code>repeated-write</code>, <code>repeated-fetch</code></td><td>performance, design</td><td>advice log when a transaction completes; performance log across transactions</td></tr>
    <tr><td><code>missing-index</code>, <code>unindexed-foreign-key</code>, <code>unindexed-search</code>, <code>page-count</code></td><td>performance</td><td>slow statement log</td></tr>
    <tr><td><code>idle-transaction</code></td><td>performance</td><td>slow statement log, against its own threshold</td></tr>
    <tr><td><code>entity-budget</code></td><td>design</td><td>advice log, once per entity, in development</td></tr>
    <tr><td><code>writes-without-transaction</code></td><td>correctness</td><td>performance log summary</td></tr>
  </table>
  <p>The lines are written for a developer and for a coding agent alike. An agent running your tests reads the same advice, with the fix and the link, and can act on it without being told what to look for. See <a class="tlink" href="/docs/ai">Agentic coding</a>.</p>

  <h2><span class="hno">11</span>Keep going</h2>
  <p>The reference documentation covers every piece of advice and its trade-offs:</p>
  <div class="refs">
    <a href="/docs/advice">Statement Advice</a>
    <a href="/docs/sql-logging">SQL Logging</a>
    <a href="/docs/entity-design">Entity Design</a>
    <a href="/docs/validation">Schema Validation</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function AdviceTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="advice" body={BODY} />;
}
