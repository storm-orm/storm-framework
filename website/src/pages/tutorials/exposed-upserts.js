import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: upserts and batching, Exposed vs Storm. Both libraries write the
// database's own upsert. Exposed facts from its DSL CRUD docs (upsert with keys,
// onUpdate/onUpdateExclude/where, batchUpsert). Storm facts from
// OnConflictEntityRepositoryImpl/MergeEntityRepositoryImpl/MySQLEntityRepositoryImpl:
// the conflict target is the primary key; @Persist(updatable = false) leaves a
// column out of the update branch.

const TITLE = 'Upserts and Batching: Exposed vs Storm';
const DESC =
  'Both libraries write the database\'s own upsert statement, so most of this page ' +
  'maps one API onto the other. Exposed can target any unique index and shape the ' +
  'update branch per call; Storm resolves on the primary key and returns the entity.';

const CODE_EXPOSED_UPSERT = [
  F('transaction'), P(' {\n'),
  P('    '), C('// conflict target: the primary key, unless keys are named\n'),
  P('    '), T('Cities'), P('.'), F('upsert'), P(' {\n'),
  P('        it[code] = '), S('"NL-AMS"'), P('\n'),
  P('        it[name] = '), S('"Amsterdam"'), P('\n'),
  P('        it[population] = '), N('931_298'), P('\n'),
  P('    }\n\n'),
  P('    '), C('// any unique index, and the columns the update leaves alone\n'),
  P('    '), T('Users'), P('.'), F('upsert'), P('('), T('Users'), P('.email, onUpdateExclude = '), F('listOf'), P('('), T('Users'), P('.birthDate)) {\n'),
  P('        it[email] = '), S('"alice@example.com"'), P('\n'),
  P('        it[birthDate] = '), T('LocalDate'), P('.'), F('of'), P('('), N('1990'), P(', '), N('5'), P(', '), N('15'), P(')\n'),
  P('        it[street] = '), S('"Main Street 1"'), P('\n'),
  P('        it[city] = cityId\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_EXPOSED_BATCH = [
  F('transaction'), P(' {\n'),
  P('    '), T('Cities'), P('.'), F('batchUpsert'), P('(rows) { row ->\n'),
  P('        '), K('this'), P('['), T('Cities'), P('.code] = row.code\n'),
  P('        '), K('this'), P('['), T('Cities'), P('.name] = row.name\n'),
  P('        '), K('this'), P('['), T('Cities'), P('.population] = row.population\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_UPSERT = [
  K('data class '), T('City'), P('(\n'),
  P('    '), A('@PK'), P('(generation = '), T('NONE'), P(') '), K('val '), P('code: '), T('String'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), K('val '), P('population: '), T('Long'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('String'), P('>\n\n'),
  C('// returns the row as stored\n'),
  K('val '), P('city = orm '), K('upsert '), T('City'), P('(code = '), S('"NL-AMS"'), P(', name = '), S('"Amsterdam"'), P(', population = '), N('931_298'), P(')'),
].join('');

const SQL_STORM_UPSERT = [
  QC('-- PostgreSQL'), '\n',
  QK('INSERT INTO'), ' city (code, name, population)\n',
  QK('VALUES'), ' (', QQ('?'), ', ', QQ('?'), ', ', QQ('?'), ')\n',
  QK('ON CONFLICT'), ' (code) ', QK('DO UPDATE SET'), ' name = EXCLUDED.name, population = EXCLUDED.population\n\n',
  QC('-- MySQL, MariaDB: ON DUPLICATE KEY UPDATE; Oracle, SQL Server, H2: MERGE'),
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
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: upserts</div>
  <h1>Upserts and <span class="grad">batching</span></h1>
  <p class="dek">Both libraries write the database's own upsert statement, per dialect, in one round trip. Most of this page maps one API onto the other. The differences are the conflict target and what a call returns.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A nightly import of cities from a reference dataset, keyed by the code the dataset assigns. Insert a new code, update a known one, in one statement, for one row and for the whole file.</p>

  <h2><span class="hno">02</span>The Exposed way</h2>
  <p>Exposed's <code>upsert</code> takes the conflict target and the shape of the update branch per call:</p>
  ${editor({file: 'CityImport.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_UPSERT})}
  <p>Without keys, the conflict target is the primary key. Named keys target any unique index, such as <code>email</code>. <code>onUpdate</code>, <code>onUpdateExclude</code> and <code>where</code> decide what a conflict writes. Lists go through <code>batchUpsert</code>:</p>
  ${editor({file: 'CityImport.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_BATCH})}

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>Storm's upsert takes an entity. The conflict target is its primary key, and the update branch writes every updatable column:</p>
  ${editor({file: 'CityImport.kt', tag: 'Kotlin · Storm', code: CODE_STORM_UPSERT, sql: SQL_STORM_UPSERT})}
  <p>A column the update must leave alone, such as a creation date, is marked <code>@Persist(updatable = false)</code> on the entity, and every upsert and update skips it. That is the entity-level counterpart of <code>onUpdateExclude</code>. A list goes through the same statement:</p>
  ${editor({file: 'CityImport.kt', tag: 'Kotlin · Storm', code: CODE_STORM_BATCH})}
  <p>An entity whose key the database generates has nothing to conflict on before it is inserted: <code>upsert</code> inserts it while its id is <code>0</code> and updates it once the id is set. On MySQL and MariaDB, <code>ON DUPLICATE KEY UPDATE</code> also fires on any unique key. Storm has no form that targets another unique index on the other databases; for a row identified by its email, write the statement in a <a class="tlink" href="/docs/sql-templates">SQL template</a>.</p>

  <h2><span class="hno">04</span>The translation table</h2>
  <table class="cmp">
    <tr><th>Exposed</th><th>Storm</th></tr>
    <tr><td><code>Cities.upsert { it[col] = value }</code></td><td><code>orm upsert City(...)</code></td></tr>
    <tr><td>Conflict target: primary key, or any unique index through <code>keys</code></td><td>Conflict target: primary key</td></tr>
    <tr><td><code>onUpdateExclude</code> per call</td><td><code>@Persist(updatable = false)</code> on the field</td></tr>
    <tr><td><code>onUpdate</code> and <code>where</code> per call</td><td>No equivalent; the update writes the entity's values</td></tr>
    <tr><td>Returns the statement; generated values are read from it</td><td>Kotlin's <code>orm upsert</code> returns the entity as stored</td></tr>
    <tr><td><code>batchUpsert(rows) { ... }</code></td><td><code>upsert(cities)</code>, JDBC-batched</td></tr>
  </table>
  <p>Upserts keyed on the primary key port one to one. If your code targets another unique index or uses <code>onUpdate</code> to compute the new value, keep that statement in a SQL template, and read <a class="tlink" href="/docs/upserts">the upsert docs</a> before porting.</p>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/upserts">Upserts</a>
    <a href="/docs/batch-streaming">Batch and Streaming</a>
    <a href="/docs/dialects">Dialects</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedUpsertsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-upserts" body={BODY} />;
}
