import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: Ktor integration, Exposed vs Storm. Storm facts verified against
// docs/ktor-integration.md and storm-ktor (install(Storm), repository<T>(),
// entity(User::class), transactional { } routes, ktor-server-di registration,
// development mode turning on advice in Storm.kt) and storm-kotlin Transactions.kt
// (suspend transaction, onCommit, cancellation, completion on the block's dispatcher).

const TITLE = 'Ktor Services: Exposed and Storm Side by Side';
const DESC =
  'Exposed connects a Database and needs a transaction around every operation, reads ' +
  'included. Storm installs as a Ktor plugin, reads without a transaction, and its ' +
  'suspend transaction { } and transactional { } routes cover the writes.';

const CODE_EXPOSED_KTOR = [
  K('fun '), T('Application'), P('.'), F('module'), P('() {\n'),
  P('    '), T('Database'), P('.'), F('connect'), P('('), F('hikariDataSource'), P('())\n\n'),
  P('    routing {\n'),
  P('        '), F('get'), P('('), S('"/users/{id}"'), P(') {\n'),
  P('            '), K('val '), P('id = call.parameters.'), F('getOrFail'), P('('), S('"id"'), P(').'), F('toInt'), P('()\n'),
  P('            '), K('val '), P('user = '), F('suspendTransaction'), P(' {   '), C('// every operation runs in a transaction\n'),
  P('                '), T('User'), P('.'), F('findById'), P('(id)?.'), F('toResponse'), P('()\n'),
  P('            }\n'),
  P('            call.'), F('respond'), P('(user ?: '), T('HttpStatusCode'), P('.'), T('NotFound'), P(')\n'),
  P('        }\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_KTOR = [
  K('interface '), T('UserRepository'), P(' : '), T('EntityRepository'), P('<'), T('User'), P(', '), T('Int'), P('>\n\n'),
  K('fun '), T('Application'), P('.'), F('module'), P('() {\n'),
  P('    '), F('install'), P('('), T('Storm'), P(')   '), C('// pool from application.conf, repositories from the type index\n\n'),
  P('    routing {\n'),
  P('        '), F('get'), P('('), S('"/users/{id}"'), P(') {\n'),
  P('            '), K('val '), P('users = '), F('repository'), P('<'), T('UserRepository'), P('>()\n'),
  P('            '), K('val '), P('user = users.'), F('findById'), P('(call.parameters.'), F('getOrFail'), P('('), S('"id"'), P(').'), F('toInt'), P('())\n'),
  P('            call.'), F('respond'), P('(user ?: '), T('HttpStatusCode'), P('.'), T('NotFound'), P(')\n'),
  P('        }\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_WRITE = [
  F('post'), P('('), S('"/owners/{id}/pets"'), P(') {\n'),
  P('    '), K('val '), P('request = call.'), F('receive'), P('<'), T('NewPetRequest'), P('>()\n'),
  P('    '), K('val '), P('owner = '), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(').'), F('getById'), P('(call.parameters.'), F('getOrFail'), P('('), S('"id"'), P(').'), F('toInt'), P('())\n'),
  P('    '), K('val '), P('pets = '), F('repository'), P('<'), T('PetRepository'), P('>()\n'),
  P('    '), K('val '), P('visits = '), F('repository'), P('<'), T('VisitRepository'), P('>()\n\n'),
  P('    '), K('val '), P('pet = '), F('transaction'), P(' {\n'),
  P('        '), K('val '), P('pet = pets.'), F('insertAndFetch'), P('(request.'), F('toPet'), P('(owner))\n'),
  P('        visits.'), F('insert'), P('(request.'), F('firstVisit'), P('(pet))\n'),
  P('        '), F('onCommit'), P(' { mailer.'), F('sendWelcome'), P('(owner, pet) }\n'),
  P('        pet\n'),
  P('    }\n\n'),
  P('    call.'), F('respond'), P('('), T('HttpStatusCode'), P('.'), T('Created'), P(', pet)\n'),
  P('}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: Ktor</div>
  <h1>Ktor services, <span class="grad">side by side</span></h1>
  <p class="dek">Both libraries are written for Kotlin and run in coroutine-based Ktor services. They differ in where transactions are required and in how the data layer reaches the routes.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A Ktor service with a read endpoint and a write endpoint: fetch a user by id, and register a pet together with its first visit in one transaction, sending a welcome mail only after the commit.</p>

  <h2><span class="hno">02</span>The Exposed way</h2>
  <p>Exposed connects a <code>Database</code> at startup. Every operation runs inside a transaction block, and <code>suspendTransaction</code> is the form for coroutines:</p>
  ${editor({file: 'Application.kt', tag: 'Kotlin · Exposed + Ktor', code: CODE_EXPOSED_KTOR})}
  <p>Many Ktor services are built this way. Exposed requires the transaction block for every operation, including a single read, so every route that touches the database has one.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>Storm ships a Ktor plugin. <code>install(Storm)</code> builds the connection pool from <code>application.conf</code> and registers every repository interface in the compile-time type index. It creates them during installation, so a broken definition fails at startup. Route handlers get them with <code>repository&lt;T&gt;()</code>:</p>
  ${editor({file: 'Application.kt', tag: 'Kotlin · Storm + Ktor', code: CODE_STORM_KTOR})}
  <p>A read outside a transaction borrows a pooled connection for the one query and returns it. For endpoints without custom queries, <code>entity(User::class)</code> and <code>projection(OwnerSummary::class)</code> give a repository without declaring an interface. Writes that belong together go in a <code>transaction</code> block, a suspend function that carries the transaction in the coroutine context:</p>
  ${editor({file: 'Routes.kt', tag: 'Kotlin · Storm + Ktor', code: CODE_STORM_WRITE})}
  <p><code>onCommit</code> runs after the commit, so a rollback never sends the mail. The block runs on Storm's transaction dispatcher and completes there, and a cancelled call stops the statement the block is waiting on. To give every route in a group its own transaction without code in the handlers, wrap the routes in <code>transactional { }</code>. See the <a class="tlink" href="/tutorials/exposed-transactions">transactions comparison</a> for the full mapping.</p>
  <p>Services that take their dependencies through Ktor's dependency injection resolve the template and each repository with <code>by dependencies</code>: the plugin registers them under their own types.</p>
  <p>In Ktor's development mode the plugin also turns on performance and design advice in the log, such as a <code>DISTINCT</code> over a to-many join or a deep offset. The <a class="tlink" href="/tutorials/advice">advice tutorial</a> walks through it.</p>

  <h2><span class="hno">04</span>The translation table</h2>
  <table class="cmp">
    <tr><th>Exposed + Ktor</th><th>Storm + Ktor</th></tr>
    <tr><td><code>Database.connect(dataSource)</code> at startup</td><td><code>install(Storm)</code>, pool from <code>application.conf</code></td></tr>
    <tr><td><code>suspendTransaction { }</code> around every operation</td><td>Reads without a transaction; <code>transaction { }</code> or <code>transactional { }</code> routes for writes</td></tr>
    <tr><td>Table objects and DAO entities</td><td>Repository interfaces, registered at install, fetched with <code>repository&lt;T&gt;()</code></td></tr>
    <tr><td>DAO entities used inside the transaction, or mapped to response types</td><td>Entities are plain values, serialized directly</td></tr>
    <tr><td><code>StatementInterceptor.afterCommit</code>, registered on the transaction</td><td><code>onCommit { }</code> in the block</td></tr>
  </table>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/ktor-integration">Ktor Integration</a>
    <a href="/docs/transactions">Transactions</a>
    <a href="/docs/serialization">Serialization</a>
    <a href="/docs/advice">Statement Advice</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedKtorTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-ktor" body={BODY} />;
}
