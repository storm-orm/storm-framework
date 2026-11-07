import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: eager loading and N+1, Exposed vs Storm. Exposed facts verified
// against the DAO relationships docs: references load lazily on first use,
// .with() batch-loads them eagerly per call site, and loaded references are
// meant to be used within the same transaction.

const TITLE = 'Eager Loading and N+1: Exposed vs Storm';
const DESC =
  'Exposed DAO references load lazily, and .with() batches them into one query ' +
  'at each call site. Storm decides loading in the model: an entity field is ' +
  'joined in the same query, a Ref field loads when your code asks.';

const CODE_EXPOSED_DAO = [
  K('object '), T('Pets'), P(' : '), T('IntIdTable'), P('('), S('"pet"'), P(') {\n'),
  P('    '), K('val '), P('name = '), F('varchar'), P('('), S('"name"'), P(', '), N('30'), P(')\n'),
  P('    '), K('val '), P('owner = '), F('reference'), P('('), S('"owner_id"'), P(', '), T('Owners'), P(')\n'),
  P('}\n\n'),
  K('class '), T('Pet'), P('(id: '), T('EntityID'), P('<'), T('Int'), P('>) : '), T('IntEntity'), P('(id) {\n'),
  P('    '), K('companion object'), P(' : '), T('IntEntityClass'), P('<'), T('Pet'), P('>('), T('Pets'), P(')\n'),
  P('    '), K('var '), P('name '), K('by'), P(' '), T('Pets'), P('.name\n'),
  P('    '), K('var '), P('owner '), K('by'), P(' '), T('Owner'), P(' '), K('referencedOn'), P(' '), T('Pets'), P('.owner   '), C('// loads lazily, on first read\n'),
  P('}'),
].join('');

const CODE_EXPOSED_LOOP = [
  C('// Lazy by default: each owner loads when first read\n'),
  K('val '), P('lines = '), F('transaction'), P(' {\n'),
  P('    '), T('Pet'), P('.'), F('all'), P('().'), F('map'), P(' { '), S('"${it.name} belongs to ${it.owner.lastName}"'), P(' }   '), C('// 1 + N\n'),
  P('}\n\n'),
  C('// The fix: eager loading, added at this call site\n'),
  K('val '), P('fixed = '), F('transaction'), P(' {\n'),
  P('    '), T('Pet'), P('.'), F('all'), P('().'), F('with'), P('('), T('Pet'), P('::owner)\n'),
  P('        .'), F('map'), P(' { '), S('"${it.name} belongs to ${it.owner.lastName}"'), P(' }\n'),
  P('}'),
].join('');

const SQL_EXPOSED_LOOP = [
  QC('-- without with(): one query per owner on first read'), '\n',
  QK('SELECT'), ' ... ', QK('FROM'), ' pet\n',
  QK('SELECT'), ' ... ', QK('FROM'), ' owner ', QK('WHERE'), ' owner.id = ', QQ('?'), '\n',
  QK('SELECT'), ' ... ', QK('FROM'), ' owner ', QK('WHERE'), ' owner.id = ', QQ('?'), '\n',
  QC('-- ...'), '\n\n',
  QC('-- with(Pet::owner): the references batch into a second query'), '\n',
  QK('SELECT'), ' ... ', QK('FROM'), ' pet\n',
  QK('SELECT'), ' ... ', QK('FROM'), ' owner ', QK('WHERE'), ' owner.id ', QK('IN'), ' (', QQ('?'), ', ', QQ('?'), ', ', QQ('?'), ')',
].join('');

const CODE_STORM_MODEL = [
  C('// The loading policy is declared once, in the model\n'),
  K('data class '), T('Pet'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Owner'), P(',   '), C('// entity field: joined in the same query\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('val '), P('lines = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(').'), F('findAll'), P('()\n'),
  P('    .'), F('map'), P(' { '), S('"${it.name} belongs to ${it.owner.lastName}"'), P(' }   '), C('// no database access in the loop'),
].join('');

const SQL_STORM_MODEL = [
  QC('-- one query, for every caller'), '\n',
  QK('SELECT'), ' p.id, p.name, o.id, o.first_name, o.last_name\n',
  QK('FROM'), ' pet p\n',
  QK('INNER JOIN'), ' owner o ', QK('ON'), ' p.owner_id = o.id',
].join('');

const CODE_STORM_REF = [
  C('// Laziness, where you want it, is a field type\n'),
  K('data class '), T('Pet'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Ref'), P('<'), T('Owner'), P('>,   '), C('// reads owner_id, joins nothing\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('val '), P('pet = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(').'), F('getById'), P('('), N('1'), P(')\n'),
  K('val '), P('owner = pet.owner.'), F('fetch'), P('()   '), C('// loads the owner here, one query'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: eager loading</div>
  <h1>Eager loading and <span class="grad">N+1</span></h1>
  <p class="dek">Exposed batches lazy references with one call, <code>.with()</code>, made at each call site. Storm decides loading once, in the model, and every query follows it.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Render 50 pets with the last name of each pet's owner. Two tables, one foreign key. The question is when the owner loads.</p>

  <h2><span class="hno">02</span>The Exposed way</h2>
  <p>Exposed's DAO layer models the relation with a delegated property. The reference is lazy by design and loads when first read:</p>
  ${editor({file: 'Pet.kt', tag: 'Kotlin · Exposed DAO', code: CODE_EXPOSED_DAO})}
  <p>Read in a loop, each reference loads with its own query. The remedy is <code>.with()</code>, which loads all the references in one <code>IN</code> query:</p>
  ${editor({file: 'FeedService.kt', tag: 'Kotlin · Exposed DAO', code: CODE_EXPOSED_LOOP, sql: SQL_EXPOSED_LOOP})}
  <p>The mechanism is efficient. The decision sits at the call site: each query that reads the relation has to add <code>.with()</code>, and a query without it looks almost the same as one with it, while one runs two statements and the other 1 + N. References loaded this way are cached in the transaction that loaded them, so results that leave the transaction need their data read first.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>Storm decides in the model. A relation declared with the entity type is joined in the same query, for every caller, and the same call produces the same SQL each time:</p>
  ${editor({file: 'FeedService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_MODEL, sql: SQL_STORM_MODEL})}
  <p>There is no eager-loading call to add, because the field is not lazy. Where deferring the load is the right choice, that is also a field type:</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin · Storm', code: CODE_STORM_REF})}
  <p>A loop that fetches a <code>Ref</code> per row is an N+1 you can see in the code. A query that needs the owners resolves them in the same statement with <code>select().fetch(Pet_.owner)</code>, and in development the advice log reports a loop of single fetches (<a class="tlink" href="/tutorials/advice">Statement advice</a>). The <a class="tlink" href="/tutorials/n-plus-one">JPA edition of this tutorial</a> covers both in more depth, and query counts are one-line assertions with <a class="tlink" href="/tutorials/testing">SqlCapture</a>.</p>

  <h2><span class="hno">04</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>Exposed DAO</th><th>Storm</th></tr>
    <tr><td>Default</td><td>Lazy; each reference loads on first read</td><td>Joined in the same query</td></tr>
    <tr><td>Eager loading</td><td><code>.with()</code> per call site, one batched <code>IN</code> query</td><td>The field type: an entity field is always joined</td></tr>
    <tr><td>Choosing laziness</td><td>The default</td><td>A type: <code>Ref&lt;T&gt;</code>, loaded by <code>fetch()</code> or by the query's <code>fetch(...)</code></td></tr>
    <tr><td>Result scope</td><td>Loaded references are cached in their transaction</td><td>Plain values, usable anywhere</td></tr>
  </table>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/relationships">Relationships</a>
    <a href="/docs/refs">Refs</a>
    <a href="/docs/entity-cache">Entity Cache</a>
    <a href="/docs/comparison">Framework Comparison</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedNPlusOneTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-n-plus-one" body={BODY} />;
}
