import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: read models with Projection. A Storm Projection is a read-only
// view on the data (a table subset, a database view or a SQL query), modeled
// as a first-class type. Custom per-query result shapes are covered in the
// query-results and sql-templates tutorials instead.

const TITLE = 'Projections: Read-Only Views on Your Data';
const DESC =
  'A Storm projection is a read-only type over a table, a database view or a ' +
  'SQL query, with its own repository, typed predicates and nesting. In JPA the ' +
  'same job falls to Hibernate annotations on entities and Spring Data projections.';

const CODE_JPA_IMMUTABLE = [
  C('// An entity over a view, read-only through a Hibernate annotation\n'),
  A('@Entity'), P('\n'),
  A('@Immutable'), P('\n'),
  A('@Table'), P('(name = '), S('"owner_address_view"'), P(')\n'),
  K('class '), T('OwnerAddressView'), P('(\n'),
  P('    '), A('@Id'), P(' '), K('var '), P('id: '), T('Int'), P('? = '), K('null'), P(',\n'),
  P('    '), K('var '), P('fullName: '), T('String'), P(' = '), S('""'), P(',\n'),
  P('    '), K('var '), P('address: '), T('String'), P(' = '), S('""'), P(',\n'),
  P('    '), K('var '), P('city: '), T('String'), P(' = '), S('""'), P(',\n'),
  P(')'),
].join('');

const CODE_STORM_SUBSET = [
  C('// Three columns of the owner table, read-only\n'),
  A('@DbTable'), P('('), S('"owner"'), P(')\n'),
  K('data class '), T('OwnerSummary'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P(') : '), T('Projection'), P('<'), T('Int'), P('>'),
].join('');

const CODE_STORM_VIEW = [
  C('// The class name maps to the view: OwnerAddressView -> owner_address_view\n'),
  K('data class '), T('OwnerAddressView'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('fullName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('address: '), T('String'), P(',\n'),
  P('    '), K('val '), P('city: '), T('String'), P(',\n'),
  P(') : '), T('Projection'), P('<'), T('Int'), P('>'),
].join('');

const CODE_STORM_QUERY = [
  K('val '), P('ownerSummaries = orm.'), F('projection'), P('('), T('OwnerSummary'), P('::'), K('class'), P(')\n\n'),
  K('val '), P('all = ownerSummaries.'), F('findAll'), P('()\n'),
  K('val '), P('smiths = ownerSummaries.'), F('findAll'), P('('), T('OwnerSummary_'), P('.lastName '), K('eq'), P(' '), S('"Smith"'), P(')'),
].join('');

const SQL_STORM_QUERY = [
  QC('-- findAll(): the projection\'s columns only'), '\n',
  QK('SELECT'), ' os.id, os.first_name, os.last_name\n',
  QK('FROM'), ' owner os\n\n',
  QC('-- findAll(OwnerSummary_.lastName eq "Smith")'), '\n',
  QK('SELECT'), ' os.id, os.first_name, os.last_name\n',
  QK('FROM'), ' owner os\n',
  QK('WHERE'), ' os.last_name = ', QQ('?'),
].join('');

const CODE_STORM_NESTED = [
  A('@DbTable'), P('('), S('"pet"'), P(')\n'),
  K('data class '), T('PetView'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('OwnerSummary'), P(',   '), C('// a projection inside a projection\n'),
  P(') : '), T('Projection'), P('<'), T('Int'), P('>\n\n'),
  C('// The predicate follows the path into the nested projection\n'),
  K('val '), P('smithsPets = orm.'), F('projection'), P('('), T('PetView'), P('::'), K('class'), P(')\n'),
  P('    .'), F('findAll'), P('('), T('PetView_'), P('.owner.lastName '), K('eq'), P(' '), S('"Smith"'), P(')'),
].join('');

const SQL_STORM_NESTED = [
  QC('-- the nested projection is joined into the same statement'), '\n',
  QK('SELECT'), ' pv.id, pv.name, os.id, os.first_name, os.last_name\n',
  QK('FROM'), ' pet pv\n',
  QK('INNER JOIN'), ' owner os ', QK('ON'), ' pv.owner_id = os.id\n',
  QK('WHERE'), ' os.last_name = ', QQ('?'),
].join('');

const CODE_STORM_PQ = [
  A('@ProjectionQuery'), P('('), S('"""\n    SELECT o.id, o.first_name, o.last_name, COUNT(p.id) AS pet_count\n    FROM owner o\n    LEFT JOIN pet p ON p.owner_id = o.id\n    GROUP BY o.id, o.first_name, o.last_name\n"""'), P(')\n'),
  K('data class '), T('OwnerWithPetCount'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('petCount: '), T('Int'), P(',\n'),
  P(') : '), T('Projection'), P('<'), T('Int'), P('>\n\n'),
  C('// A typed predicate on the aggregate\n'),
  K('val '), P('ownersWithThreePets = orm.'), F('projection'), P('('), T('OwnerWithPetCount'), P('::'), K('class'), P(')\n'),
  P('    .'), F('findAll'), P('('), T('OwnerWithPetCount_'), P('.petCount '), K('greaterEq'), P(' '), N('3'), P(')'),
].join('');

const SQL_STORM_PQ = [
  QC('-- the projection query is the derived table; the predicate applies on top'), '\n',
  QK('SELECT'), ' owpc.id, owpc.first_name, owpc.last_name, owpc.pet_count\n',
  QK('FROM'), ' (\n',
  '    ', QK('SELECT'), ' o.id, o.first_name, o.last_name, ', QK('COUNT'), '(p.id) ', QK('AS'), ' pet_count\n',
  '    ', QK('FROM'), ' owner o\n',
  '    ', QK('LEFT JOIN'), ' pet p ', QK('ON'), ' p.owner_id = o.id\n',
  '    ', QK('GROUP BY'), ' o.id, o.first_name, o.last_name\n',
  ') owpc\n',
  QK('WHERE'), ' owpc.pet_count >= ', QQ('?'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Projections</div>
  <h1>Projections: read-only <span class="grad">views on your data</span></h1>
  <p class="dek">A Storm projection is a read-only type over a table, a database view or a SQL query. It has a repository without write methods, takes typed predicates, and nests in other types.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A reporting screen reads a database view the DBA maintains. An owners list reads three columns of a wide <code>owner</code> table. A dashboard lists owners with their number of pets. None of them writes.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>JPA's mapped type is the entity, and an entity is read-write. Hibernate's <code>@Immutable</code> marks one read-only, which is the usual mapping for a view:</p>
  ${editor({file: 'OwnerAddressView.kt', tag: 'Kotlin · JPA', code: CODE_JPA_IMMUTABLE})}
  <p><code>@Immutable</code> is Hibernate's, not JPA's. Hibernate does not write changes to such an entity, and its instances are still managed by the persistence context. A Spring Data repository over it offers <code>save()</code> unless you declare a narrower one with only the find methods. For a subset of a table, Spring Data's interface and class projections shape the result of a repository method. For a read model backed by SQL, Hibernate has <code>@Subselect</code>.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>Storm has a type for it. An <code>Entity</code> is read-write; a <code>Projection</code> is read-only. A projection can map a database view:</p>
  ${editor({file: 'OwnerAddressView.kt', tag: 'Kotlin · Storm', code: CODE_STORM_VIEW})}
  <p>Or a subset of a table's columns:</p>
  ${editor({file: 'OwnerSummary.kt', tag: 'Kotlin · Storm', code: CODE_STORM_SUBSET})}
  <p>A subset projection is a second type to keep in step with the table, so start with the entity and add one where the width of the entity shows in the slow statement log or the performance log.</p>
  <p>A projection's repository has the read API: <code>findAll</code>, <code>findById</code>, counting, pages, slices, scrolling and the query builder. It has no <code>insert</code>, <code>update</code> or <code>remove</code>, so a write does not compile:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_QUERY, sql: SQL_STORM_QUERY})}
  <p>The results are immutable data classes, not proxies, and <code>OwnerSummary_.lastName</code> is a path in the generated metamodel.</p>

  <h2><span class="hno">04</span>Nesting and SQL-backed projections</h2>
  <p>An entity or another projection can reference a projection through <code>@FK</code>. Storm joins it into the same statement, and a predicate follows the path:</p>
  ${editor({file: 'PetView.kt', tag: 'Kotlin · Storm', code: CODE_STORM_NESTED, sql: SQL_STORM_NESTED})}
  <p>Within one query, equal owner rows hydrate into one <code>OwnerSummary</code> instance, so a thousand pets of fifty owners hold fifty owner objects. The <a class="tlink" href="/tutorials/n-plus-one">N+1 tutorial</a> shows the same sharing for entities.</p>
  <p><code>@ProjectionQuery</code> backs a projection with SQL of its own. Storm reads that SQL as a derived table, so a typed predicate applies to its columns, the aggregate included:</p>
  ${editor({file: 'OwnerWithPetCount.kt', tag: 'Kotlin · Storm', code: CODE_STORM_PQ, sql: SQL_STORM_PQ})}
  <div class="note">A projection is a reusable read model. For a result shape that serves one query, a plain data class is enough: see <a href="/tutorials/query-results">Typed query results</a> for mapping by position, and <a href="/tutorials/sql-templates">SQL templates</a> for shapes inside full SQL.</div>

  <h2><span class="hno">05</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>JPA with Hibernate</th><th>Storm</th></tr>
    <tr><td>Read-only type</td><td>An entity with Hibernate's <code>@Immutable</code></td><td><code>Projection</code>: its repository has no write methods</td></tr>
    <tr><td>Database view</td><td>An entity mapped over the view</td><td>A projection named after the view</td></tr>
    <tr><td>Subset of a table</td><td>An interface or class projection per repository method</td><td>A projection with <code>@DbTable</code> and its own repository</td></tr>
    <tr><td>SQL-backed read model</td><td>Hibernate's <code>@Subselect</code></td><td><code>@ProjectionQuery</code>, filtered as a derived table</td></tr>
    <tr><td>Instances</td><td>Managed entities, or projection proxies</td><td>Immutable data classes</td></tr>
  </table>

  <h2><span class="hno">06</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/projections">Projections</a>
    <a href="/docs/repositories">Repositories</a>
    <a href="/docs/serialization">Serialization</a>
    <a href="/docs/metamodel">Metamodel</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ProjectionsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="projections" body={BODY} />;
}
