import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: modeling and querying, Exposed vs Storm. First entry of the
// "Exposed to Storm" series. Type safety is presented as a tie. The factual
// difference is repetition: joins and mappings are per-query knowledge in
// Exposed and model knowledge in Storm, declared once per table.

const TITLE = 'One Model Instead of Three: Exposed vs Storm';
const DESC =
  'Exposed and Storm are both type-safe Kotlin data layers. They differ in ' +
  'where joins and row mappings live: Exposed writes them with each query, ' +
  'Storm declares them once in the data class.';

const CODE_EXPOSED_DSL = [
  C('// The schema lives in table objects ...\n'),
  K('object '), T('Owners'), P(' : '), T('Table'), P('('), S('"owner"'), P(') {\n'),
  P('    '), K('val '), P('id = '), F('integer'), P('('), S('"id"'), P(').'), F('autoIncrement'), P('()\n'),
  P('    '), K('val '), P('firstName = '), F('varchar'), P('('), S('"first_name"'), P(', '), N('30'), P(')\n'),
  P('    '), K('val '), P('lastName = '), F('varchar'), P('('), S('"last_name"'), P(', '), N('30'), P(')\n'),
  P('    '), K('override val '), P('primaryKey = '), T('PrimaryKey'), P('(id)\n'),
  P('}\n\n'),
  K('object '), T('Pets'), P(' : '), T('Table'), P('('), S('"pet"'), P(') {\n'),
  P('    '), K('val '), P('id = '), F('integer'), P('('), S('"id"'), P(').'), F('autoIncrement'), P('()\n'),
  P('    '), K('val '), P('name = '), F('varchar'), P('('), S('"name"'), P(', '), N('30'), P(')\n'),
  P('    '), K('val '), P('ownerId = '), F('integer'), P('('), S('"owner_id"'), P(') '), K('references'), P(' '), T('Owners'), P('.id\n'),
  P('    '), K('override val '), P('primaryKey = '), T('PrimaryKey'), P('(id)\n'),
  P('}\n\n'),
  C('// ... the shape you pass around is a separate declaration ...\n'),
  K('data class '), T('PetRow'), P('('), K('val '), P('name: '), T('String'), P(', '), K('val '), P('ownerName: '), T('String'), P(')\n\n'),
  C('// ... and each query bridges the two\n'),
  K('val '), P('ofDavis = '), F('transaction'), P(' {\n'),
  P('    ('), T('Pets'), P(' '), K('innerJoin'), P(' '), T('Owners'), P(')\n'),
  P('        .'), F('selectAll'), P('()\n'),
  P('        .'), F('where'), P(' { '), T('Owners'), P('.lastName '), K('eq'), P(' '), S('"Davis"'), P(' }\n'),
  P('        .'), F('map'), P(' { '), T('PetRow'), P('(it['), T('Pets'), P('.name], it['), T('Owners'), P('.lastName]) }\n'),
  P('}\n\n'),
  C('// The next query writes the same join and the same mapping again\n'),
  K('val '), P('namedLeo = '), F('transaction'), P(' {\n'),
  P('    ('), T('Pets'), P(' '), K('innerJoin'), P(' '), T('Owners'), P(')\n'),
  P('        .'), F('selectAll'), P('()\n'),
  P('        .'), F('where'), P(' { '), T('Pets'), P('.name '), K('eq'), P(' '), S('"Leo"'), P(' }\n'),
  P('        .'), F('map'), P(' { '), T('PetRow'), P('(it['), T('Pets'), P('.name], it['), T('Owners'), P('.lastName]) }\n'),
  P('}'),
].join('');

const CODE_EXPOSED_DAO = [
  C('// The DAO layer replaces the mapping with a third declaration\n'),
  K('class '), T('Pet'), P('(id: '), T('EntityID'), P('<'), T('Int'), P('>) : '), T('IntEntity'), P('(id) {\n'),
  P('    '), K('companion object'), P(' : '), T('IntEntityClass'), P('<'), T('Pet'), P('>('), T('Pets'), P(')\n'),
  P('    '), K('var '), P('name '), K('by'), P(' '), T('Pets'), P('.name\n'),
  P('    '), K('var '), P('owner '), K('by'), P(' '), T('Owner'), P(' '), K('referencedOn'), P(' '), T('Pets'), P('.ownerId\n'),
  P('}\n\n'),
  K('val '), P('lines = '), F('transaction'), P(' {\n'),
  P('    '), T('Pet'), P('.'), F('all'), P('().'), F('with'), P('('), T('Pet'), P('::owner)   '), C('// with() loads the owners eagerly for this call\n'),
  P('        .'), F('map'), P(' { '), S('"${it.name} belongs to ${it.owner.lastName}"'), P(' }\n'),
  P('}'),
].join('');

const CODE_STORM_MODEL = [
  C('// The data class maps the table and is the value a query returns\n'),
  K('data class '), T('Owner'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('data class '), T('Pet'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Owner'), P(',   '), C('// the join is declared here, once\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('val '), P('pets = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(')\n\n'),
  K('val '), P('ofDavis = pets.'), F('findAll'), P('('), T('Pet_'), P('.owner.lastName '), K('eq'), P(' '), S('"Davis"'), P(')\n\n'),
  C('// The next query uses the same model and states no join\n'),
  K('val '), P('namedLeo = pets.'), F('findAll'), P('('), T('Pet_'), P('.name '), K('eq'), P(' '), S('"Leo"'), P(')'),
].join('');

const SQL_STORM_MODEL = [
  QC('-- both queries derive the join and the column list from the model'), '\n',
  QK('SELECT'), ' p.id, p.name, o.id, o.first_name, o.last_name\n',
  QK('FROM'), ' pet p\n',
  QK('INNER JOIN'), ' owner o ', QK('ON'), ' p.owner_id = o.id\n',
  QK('WHERE'), ' o.last_name = ', QQ('?'), '\n\n',
  QK('SELECT'), ' p.id, p.name, o.id, o.first_name, o.last_name\n',
  QK('FROM'), ' pet p\n',
  QK('INNER JOIN'), ' owner o ', QK('ON'), ' p.owner_id = o.id\n',
  QK('WHERE'), ' p.name = ', QQ('?'),
].join('');

const CODE_STORM_VALUES = [
  C('// Results are immutable values\n'),
  K('fun '), F('petsOf'), P('(lastName: '), T('String'), P('): '), T('List'), P('<'), T('Pet'), P('> =\n'),
  P('    pets.'), F('findAll'), P('('), T('Pet_'), P('.owner.lastName '), K('eq'), P(' lastName)\n\n'),
  C('// They depend on no session or transaction, so they can be serialized,\n'),
  C('// cached or handed to another thread\n'),
  K('val '), P('json = objectMapper.'), F('writeValueAsString'), P('('), F('petsOf'), P('('), S('"Davis"'), P('))'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: the model</div>
  <h1>One model instead of <span class="grad">three</span></h1>
  <p class="dek">Exposed and Storm are both type-safe Kotlin data layers. They differ in where joins and row mappings live. Exposed writes them with each query; Storm declares them once, in the data class, and every query reuses them.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Two tables, pets and owners, with one foreign key. One query lists the pets of the owners named Davis with the owner's name, a second filters pets on name, and a service returns typed results that a controller serializes.</p>

  <h2><span class="hno">02</span>The Exposed way</h2>
  <p>Exposed, built by JetBrains, declares the schema in table objects, and the compiler checks every column reference. With the DSL the task looks like this:</p>
  ${editor({file: 'Pets.kt', tag: 'Kotlin · Exposed DSL', code: CODE_EXPOSED_DSL})}
  <p>All of it is type-safe. The join and the row mapping belong to each query, so the second query states both again. A schema with hundreds of queries carries hundreds of these, and a column added to <code>PetRow</code> means a change in each query that fills it. The DAO layer removes the mapping and adds a third declaration of the same data:</p>
  ${editor({file: 'Pet.kt', tag: 'Kotlin · Exposed DAO', code: CODE_EXPOSED_DAO})}
  <p>DAO entities are mutable, and the transaction writes the changes made to them. A reference loads on first access unless <code>with()</code> loads it eagerly for that call, so <code>Pet.all()</code> with and without <code>.with(Pet::owner)</code> differ by one call and run a different number of statements. The <a class="tlink" href="/tutorials/exposed-n-plus-one">eager loading tutorial</a> compares the two approaches.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>In Storm a relation is part of the model. The data class declares the join once, and every query uses it:</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin · Storm', code: CODE_STORM_MODEL, sql: SQL_STORM_MODEL})}
  <p>The second query does not mention the join. <code>Pet_.owner.lastName</code> is a compile-checked path through the one declaration. On type safety the two libraries are even. The difference is in the count: joins and mappings written per query grow with the number of queries, while the model grows with the number of tables.</p>

  <h2><span class="hno">04</span>Results are values</h2>
  <p>The DSL's mapped rows are plain values too; it is the DAO's entities that belong to their transaction. Storm returns immutable data classes without a mapping step:</p>
  ${editor({file: 'PetService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_VALUES})}
  <p>Reads need no <code>transaction { }</code>: repositories take a connection per operation, and a transaction comes in where atomicity matters. Lazy loading exists in Storm as a type. A field declared <code>Ref&lt;Owner&gt;</code> holds the key, and loading the owner is an explicit <code>fetch()</code> call. The <a class="tlink" href="/tutorials/n-plus-one">N+1 tutorial</a> covers that model.</p>

  <h2><span class="hno">05</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>Exposed</th><th>Storm</th></tr>
    <tr><td>Type safety</td><td>Compile-checked column references</td><td>Compile-checked metamodel paths</td></tr>
    <tr><td>Model declarations</td><td>Table object plus row mapping (DSL), plus entity class (DAO)</td><td>One data class per table</td></tr>
    <tr><td>Joins</td><td>Written per query</td><td>Declared once via <code>@FK</code>, reused by every query</td></tr>
    <tr><td>Results are</td><td>Rows to map, or mutable entities tied to their transaction</td><td>Immutable values</td></tr>
    <tr><td>Reads</td><td>Inside <code>transaction { }</code></td><td>Without one; <code>transaction { }</code> where atomicity matters</td></tr>
  </table>

  <h2><span class="hno">06</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/entities">Entities</a>
    <a href="/docs/relationships">Relationships</a>
    <a href="/docs/queries">Queries</a>
    <a href="/docs/comparison">Framework Comparison</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedEntitiesTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-entities" body={BODY} />;
}
