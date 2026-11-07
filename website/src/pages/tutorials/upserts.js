import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: upserts, JPA vs Storm. Storm facts verified against
// OnConflictEntityRepositoryImpl, MergeEntityRepositoryImpl, MySQLEntityRepositoryImpl
// and EntityRepositoryImpl.isUpsertUpdate/isUpsertInsert: the conflict target is
// the primary key on every dialect; an auto-generated key routes to insert (default
// id) or update (id set). SQL shape from PostgreSQLEntityRepositoryConformanceTest.

const TITLE = 'Upserts in One Statement: JPA vs Storm';
const DESC =
  'Spring Data JPA saves an entity with an assigned key through merge(): a SELECT, ' +
  'then an INSERT or UPDATE. Storm sends the database\'s own upsert, resolved on ' +
  'the primary key, on PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite and H2.';

const CODE_CITY = [
  K('data class '), T('City'), P('(\n'),
  P('    '), A('@PK'), P('(generation = '), T('NONE'), P(') '), K('val '), P('code: '), T('String'), P(',   '), C('// assigned by the source\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), K('val '), P('population: '), T('Long'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('String'), P('>'),
].join('');

const CODE_JPA_UPSERT = [
  A('@Transactional'), P('\n'),
  K('fun '), F('importCity'), P('(row: '), T('CityRow'), P(') {\n'),
  P('    '), C('// The key is assigned, so save() calls merge(): it reads the row first\n'),
  P('    cityRepository.'), F('save'), P('('), T('City'), P('(row.code, row.name, row.population))\n'),
  P('}'),
].join('');

const SQL_JPA_UPSERT = [
  QK('SELECT'), ' ... ', QK('FROM'), ' city ', QK('WHERE'), ' code = ', QQ('?'), '\n',
  QK('INSERT INTO'), ' city (...) ', QK('VALUES'), ' (...)   ', QC('-- or UPDATE, when the row exists'), '\n\n',
  QC('-- two imports of a new code both find no row;'), '\n',
  QC('-- the second INSERT fails on the primary key'),
].join('');

const CODE_STORM_UPSERT = [
  K('val '), P('city = orm '), K('upsert '), T('City'), P('(code = '), S('"NL-AMS"'), P(', name = '), S('"Amsterdam"'), P(', population = '), N('931_298'), P(')'),
].join('');

const SQL_STORM_UPSERT = [
  QC('-- PostgreSQL'), '\n',
  QK('INSERT INTO'), ' city (code, name, population)\n',
  QK('VALUES'), ' (', QQ('?'), ', ', QQ('?'), ', ', QQ('?'), ')\n',
  QK('ON CONFLICT'), ' (code) ', QK('DO UPDATE SET'), ' name = EXCLUDED.name, population = EXCLUDED.population\n\n',
  QC('-- SQLite: ON CONFLICT (code) DO UPDATE'), '\n',
  QC('-- MySQL, MariaDB: INSERT ... ON DUPLICATE KEY UPDATE'), '\n',
  QC('-- Oracle, SQL Server, H2: MERGE INTO city t ... ON (t.code = src.code)'),
].join('');

const CODE_STORM_BATCH = [
  K('val '), P('cities = rows.'), F('map'), P(' { '), T('City'), P('(it.code, it.name, it.population) }\n\n'),
  F('transaction'), P(' {\n'),
  P('    orm.'), F('entity'), P('('), T('City'), P('::'), K('class'), P(').'), F('upsert'), P('(cities)   '), C('// JDBC batches of 1000 rows\n'),
  P('}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Upserts</div>
  <h1>Upserts in <span class="grad">one statement</span></h1>
  <p class="dek">Insert the row if it is new, update it if it exists. Spring Data JPA does this by reading the row first. Storm sends the database's own upsert statement, keyed on the primary key.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A nightly job imports cities from a reference dataset. Each row carries the code the dataset assigns, and the same code arrives every night. The write has to insert a new code, update a known one, and stay correct when two imports run at once.</p>
  ${editor({file: 'City.kt', tag: 'Kotlin · Storm', code: CODE_CITY})}

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>The JPA specification has no upsert. With an assigned key, Spring Data's <code>save()</code> treats the entity as existing and calls <code>merge()</code>, which loads the row before it writes:</p>
  ${editor({file: 'ImportService.kt', tag: 'Kotlin · JPA', code: CODE_JPA_UPSERT, sql: SQL_JPA_UPSERT})}
  <p>The read and the write are two statements. Two imports of the same new code can both read nothing, and the second insert fails with a constraint violation. Hibernate 6.3 added <code>StatelessSession.upsert()</code>, which writes one statement keyed on the id; it runs outside the persistence context, so it is a second API next to the repositories.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p><code>upsert</code> is a repository operation, and the dialect writes the statement:</p>
  ${editor({file: 'ImportService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_UPSERT, sql: SQL_STORM_UPSERT})}
  <p>The database decides between insert and update inside one statement, so concurrent imports of the same code do not collide. Kotlin's <code>orm upsert</code> returns the row as stored; <code>upsert</code> on the repository returns nothing and skips the read.</p>
  <div class="note">The conflict target is the primary key on every database. An entity whose key the database generates has nothing to conflict on before it is inserted: <code>upsert</code> inserts it while its id is <code>0</code> and updates it once the id is set. MySQL and MariaDB are the exception, since <code>ON DUPLICATE KEY UPDATE</code> also fires on any unique key, so be deliberate when a table has several. Upsert needs the dialect module for your database, such as <code>storm-postgresql</code>. See <a href="/docs/upserts">Upserts</a>.</div>

  <h2><span class="hno">04</span>Batches</h2>
  <p>A list goes through the same statement, sent as JDBC batches of 1000 rows. Inside a transaction the whole import commits together:</p>
  ${editor({file: 'CityImport.kt', tag: 'Kotlin · Storm', code: CODE_STORM_BATCH})}
  <p>Calling <code>upsert</code> once per row in a loop sends each row in its own round trip. In development, Storm's <a class="tlink" href="/docs/advice#repeated-write">statement advice</a> reports a single-row write repeated ten or more times and names the list form. The <a class="tlink" href="/tutorials/advice">advice tutorial</a> shows how to read it.</p>

  <h2><span class="hno">05</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>JPA with Hibernate</th><th>Storm</th></tr>
    <tr><td>Upsert</td><td><code>save()</code> with an assigned key: SELECT, then INSERT or UPDATE. <code>StatelessSession.upsert()</code> since 6.3</td><td><code>orm upsert city</code>, one statement</td></tr>
    <tr><td>Concurrent writes of a new key</td><td>The read and the write can interleave</td><td>The database resolves the conflict</td></tr>
    <tr><td>SQL</td><td>Generated by Hibernate per dialect</td><td><code>ON CONFLICT</code>, <code>ON DUPLICATE KEY UPDATE</code> or <code>MERGE</code>, per dialect</td></tr>
    <tr><td>Lists</td><td><code>saveAll()</code>, a read per entity</td><td><code>upsert(cities)</code>, JDBC-batched</td></tr>
  </table>

  <h2><span class="hno">06</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/upserts">Upserts</a>
    <a href="/docs/batch-streaming">Batch and Streaming</a>
    <a href="/docs/dialects">Dialects</a>
    <a href="/docs/advice#repeated-write">Statement Advice</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function UpsertsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="upserts" body={BODY} />;
}
