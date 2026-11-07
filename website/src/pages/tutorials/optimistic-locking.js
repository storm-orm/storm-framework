import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: optimistic locking, JPA vs Storm. Storm facts verified against
// docs/entities.md (lost updates, timestamp versions), docs/error-handling.md,
// docs/transactions.md (retries), OptimisticLockException (a
// TransientPersistenceException), TransactionRetries.isTransient and the
// version SQL in PostgreSQLEntityRepositoryConformanceTest.

const TITLE = 'Optimistic Locking with Immutable Entities: JPA vs Storm';
const DESC =
  'JPA writes a versioned UPDATE when the persistence context flushes, usually at ' +
  'commit. Storm writes it on the update call, with the version the entity value ' +
  'carries, and transaction(retries = 2) reruns a block that lost a version conflict.';

const CODE_JPA_VERSION = [
  A('@Entity'), P('\n'),
  K('class '), T('Owner'), P('(\n'),
  P('    '), A('@Id'), P(' '), A('@GeneratedValue'), P(' '), K('var '), P('id: '), T('Int'), P('? = '), K('null'), P(',\n'),
  P('    '), K('var '), P('firstName: '), T('String'), P(' = '), S('""'), P(',\n'),
  P('    '), K('var '), P('lastName: '), T('String'), P(' = '), S('""'), P(',\n'),
  P('    '), A('@Version'), P(' '), K('var '), P('version: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P(')\n\n'),
  A('@Transactional'), P('\n'),
  K('fun '), F('rename'), P('(id: '), T('Int'), P(', lastName: '), T('String'), P(') {\n'),
  P('    '), K('val '), P('owner = ownerRepository.'), F('findById'), P('(id).'), F('orElseThrow'), P('()\n'),
  P('    owner.lastName = lastName\n'),
  P('    '), C('// the UPDATE and its version check run at flush, here at commit\n'),
  P('}'),
].join('');

const CODE_STORM_VERSION = [
  K('data class '), T('Owner'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P('    '), A('@Version'), P(' '), K('val '), P('version: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('val '), P('owners = orm.'), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(')\n\n'),
  C('// GET: the version leaves with the record\n'),
  K('val '), P('owner = owners.'), F('getById'), P('(ownerId)\n\n'),
  C('// PUT: the version comes back with the edit\n'),
  K('val '), P('updated = owners.'), F('updateAndFetch'), P('(owner.'), F('copy'), P('(lastName = form.lastName, version = form.version))'),
].join('');

const SQL_STORM_VERSION = [
  QK('UPDATE'), ' owner\n',
  QK('SET'), ' first_name = ', QQ('?'), ', last_name = ', QQ('?'), ', version = version + 1\n',
  QK('WHERE'), ' id = ', QQ('?'), ' ', QK('AND'), ' version = ', QQ('?'), '\n',
  QC('-- no row matched: OptimisticLockException, thrown by this call'),
].join('');

const CODE_STORM_CONFLICT = [
  K('val '), P('edited = owner.'), F('copy'), P('(lastName = form.lastName, version = form.version)\n'),
  K('try'), P(' {\n'),
  P('    owners.'), F('update'), P('(edited)\n'),
  P('} '), K('catch'), P(' (exception: '), T('OptimisticLockException'), P(') {\n'),
  P('    '), K('val '), P('current = owners.'), F('getById'), P('(edited.id)\n'),
  P('    '), C('// edited and current are both values: return them with a 409\n'),
  P('    '), C('// and let the user decide which change wins\n'),
  P('}'),
].join('');

const CODE_STORM_RETRY = [
  C('// Reads and writes in one block: a rerun reads the row again\n'),
  F('transaction'), P('(retries = '), N('2'), P(') {\n'),
  P('    '), K('val '), P('owner = owners.'), F('getById'), P('(ownerId)\n'),
  P('    owners.'), F('update'), P('(owner.'), F('copy'), P('(lastName = owner.lastName.'), F('trim'), P('()))\n'),
  P('}'),
].join('');

const CODE_STORM_TIMESTAMP = [
  C('// A timestamp works as the version too\n'),
  K('data class '), T('Visit'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('visitDate: '), T('LocalDate'), P(',\n'),
  P('    '), K('val '), P('description: '), T('String'), P('? = '), K('null'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('pet: '), T('Pet'), P(',\n'),
  P('    '), A('@Version'), P(' '), K('val '), P('timestamp: '), T('Instant'), P('?,\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Optimistic locking</div>
  <h1>Optimistic locking with <span class="grad">immutable entities</span></h1>
  <p class="dek">Two people edit the same record, and the second save must not overwrite the first. JPA and Storm both use a version column for this. They differ in when the check runs and in what you hold when it fails.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>An owner record is open in two browser tabs. Both tabs edit it and save. Without a guard, the later save overwrites the earlier one. A version column turns that into an error the application can handle.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>JPA puts <code>@Version</code> on the entity and checks it in the UPDATE it writes:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · JPA', code: CODE_JPA_VERSION})}
  <p>Changing a managed entity schedules the UPDATE for the next flush. In a Spring <code>@Transactional</code> method that is usually the commit, after the method body has run, so the conflict surfaces as <code>ObjectOptimisticLockingFailureException</code> where the transaction ends. <code>saveAndFlush()</code> moves the check to the call. Across two requests, the version from the form reaches the check through a detached entity and <code>merge()</code>.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>Storm has no persistence context. The update runs when you call it, and it checks the version that the entity value carries:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_VERSION, sql: SQL_STORM_VERSION})}
  <p>The version travels to the client with the record and comes back with the edit. <code>copy()</code> puts it on the entity, and the UPDATE matches no row when another save got there first. <code>updateAndFetch()</code> returns the row with its new version, ready for the next edit.</p>

  <h2><span class="hno">04</span>Handling the conflict</h2>
  <p>The entity you tried to write and the row in the database are both plain values, so the handler can compare them field by field:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_CONFLICT})}
  <p>With the Spring Boot starter, the exception arrives as Spring's <code>OptimisticLockingFailureException</code>, with Storm's exception as the cause.</p>

  <h2><span class="hno">05</span>Rerunning the transaction</h2>
  <p><code>OptimisticLockException</code> is a <code>TransientPersistenceException</code>, like a deadlock or a serialization failure: running the whole transaction again may succeed. <code>retries</code> on the transaction block does that, each attempt in a transaction of its own:</p>
  ${editor({file: 'OwnerCleanup.kt', tag: 'Kotlin · Storm', code: CODE_STORM_RETRY})}
  <p>A rerun helps when the block reads the row it writes. The form above brings its version from the client, so a rerun would send the same stale version and fail again; return the conflict instead. A change computed from a column's current value, such as a counter or a stock level, needs no version: set it in the database with the <a class="tlink" href="/docs/entities#lost-updates"><code>update()</code> builder</a>, which also increments <code>@Version</code> on the rows it sets.</p>

  <h2><span class="hno">06</span>Timestamp versions</h2>
  <p>If the table tracks a last-modified timestamp, it can serve as the version:</p>
  ${editor({file: 'Visit.kt', tag: 'Kotlin · Storm', code: CODE_STORM_TIMESTAMP})}

  <h2><span class="hno">07</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>JPA with Hibernate</th><th>Storm</th></tr>
    <tr><td>When the check runs</td><td>At flush, usually the commit; <code>saveAndFlush()</code> runs it at the call</td><td>On the update call</td></tr>
    <tr><td>Version checked across requests</td><td>The detached entity's, through <code>merge()</code></td><td>The one the entity value carries</td></tr>
    <tr><td>Exception with Spring</td><td><code>ObjectOptimisticLockingFailureException</code></td><td><code>OptimisticLockingFailureException</code> with the starter</td></tr>
    <tr><td>Rerunning the transaction</td><td>A retry around the transactional method, such as <code>@Retryable</code></td><td><code>transaction(retries = 2) { }</code></td></tr>
  </table>

  <h2><span class="hno">08</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/entities#lost-updates">Lost Updates</a>
    <a href="/docs/error-handling">Error Handling</a>
    <a href="/docs/transactions">Transactions</a>
    <a href="/docs/dirty-checking">Dirty Checking</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function OptimisticLockingTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="optimistic-locking" body={BODY} />;
}
