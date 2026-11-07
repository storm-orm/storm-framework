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
  K('object '), T('Cities'), P(' : '), T('Table'), P('('), S('"city"'), P(') {\n'),
  P('    '), K('val '), P('id = '), F('integer'), P('('), S('"id"'), P(').'), F('autoIncrement'), P('()\n'),
  P('    '), K('val '), P('name = '), F('varchar'), P('('), S('"name"'), P(', '), N('50'), P(')\n'),
  P('    '), K('override val '), P('primaryKey = '), T('PrimaryKey'), P('(id)\n'),
  P('}\n\n'),
  K('object '), T('Users'), P(' : '), T('Table'), P('('), S('"user"'), P(') {\n'),
  P('    '), K('val '), P('id = '), F('integer'), P('('), S('"id"'), P(').'), F('autoIncrement'), P('()\n'),
  P('    '), K('val '), P('email = '), F('varchar'), P('('), S('"email"'), P(', '), N('255'), P(')\n'),
  P('    '), K('val '), P('name = '), F('varchar'), P('('), S('"name"'), P(', '), N('100'), P(')\n'),
  P('    '), K('val '), P('cityId = '), F('integer'), P('('), S('"city_id"'), P(') '), K('references'), P(' '), T('Cities'), P('.id\n'),
  P('    '), K('override val '), P('primaryKey = '), T('PrimaryKey'), P('(id)\n'),
  P('}\n\n'),
  C('// ... the shape you pass around is a separate declaration ...\n'),
  K('data class '), T('UserRow'), P('('), K('val '), P('email: '), T('String'), P(', '), K('val '), P('name: '), T('String'), P(', '), K('val '), P('cityName: '), T('String'), P(')\n\n'),
  C('// ... and each query bridges the two\n'),
  K('val '), P('inSunnyvale = '), F('transaction'), P(' {\n'),
  P('    ('), T('Users'), P(' '), K('innerJoin'), P(' '), T('Cities'), P(')\n'),
  P('        .'), F('selectAll'), P('()\n'),
  P('        .'), F('where'), P(' { '), T('Cities'), P('.name '), K('eq'), P(' '), S('"Sunnyvale"'), P(' }\n'),
  P('        .'), F('map'), P(' { '), T('UserRow'), P('(it['), T('Users'), P('.email], it['), T('Users'), P('.name], it['), T('Cities'), P('.name]) }\n'),
  P('}\n\n'),
  C('// The next query writes the same join and the same mapping again\n'),
  K('val '), P('atAcme = '), F('transaction'), P(' {\n'),
  P('    ('), T('Users'), P(' '), K('innerJoin'), P(' '), T('Cities'), P(')\n'),
  P('        .'), F('selectAll'), P('()\n'),
  P('        .'), F('where'), P(' { '), T('Users'), P('.email '), K('like'), P(' '), S('"%@acme.io"'), P(' }\n'),
  P('        .'), F('map'), P(' { '), T('UserRow'), P('(it['), T('Users'), P('.email], it['), T('Users'), P('.name], it['), T('Cities'), P('.name]) }\n'),
  P('}'),
].join('');

const CODE_EXPOSED_DAO = [
  C('// The DAO layer replaces the mapping with a third declaration\n'),
  K('class '), T('User'), P('(id: '), T('EntityID'), P('<'), T('Int'), P('>) : '), T('IntEntity'), P('(id) {\n'),
  P('    '), K('companion object'), P(' : '), T('IntEntityClass'), P('<'), T('User'), P('>('), T('Users'), P(')\n'),
  P('    '), K('var '), P('email '), K('by'), P(' '), T('Users'), P('.email\n'),
  P('    '), K('var '), P('name '), K('by'), P(' '), T('Users'), P('.name\n'),
  P('    '), K('var '), P('city '), K('by'), P(' '), T('City'), P(' '), K('referencedOn'), P(' '), T('Users'), P('.cityId\n'),
  P('}\n\n'),
  K('val '), P('lines = '), F('transaction'), P(' {\n'),
  P('    '), T('User'), P('.'), F('all'), P('().'), F('with'), P('('), T('User'), P('::city)   '), C('// with() loads the cities eagerly for this call\n'),
  P('        .'), F('map'), P(' { '), S('"${it.name} lives in ${it.city.name}"'), P(' }\n'),
  P('}'),
].join('');

const CODE_STORM_MODEL = [
  C('// The data class maps the table and is the value a query returns\n'),
  K('data class '), T('City'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('data class '), T('User'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('email: '), T('String'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('city: '), T('City'), P(',   '), C('// the join is declared here, once\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('val '), P('users = orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(')\n\n'),
  K('val '), P('inSunnyvale = users.'), F('findAll'), P('('), T('User_'), P('.city.name '), K('eq'), P(' '), S('"Sunnyvale"'), P(')\n\n'),
  C('// The next query uses the same model and states no join\n'),
  K('val '), P('atAcme = users.'), F('findAll'), P('('), T('User_'), P('.email '), K('like'), P(' '), S('"%@acme.io"'), P(')'),
].join('');

const SQL_STORM_MODEL = [
  QC('-- both queries derive the join and the column list from the model'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name, c.id, c.name\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QK('WHERE'), ' c.name = ', QQ('?'), '\n\n',
  QK('SELECT'), ' u.id, u.email, u.name, c.id, c.name\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QK('WHERE'), ' u.email ', QK('LIKE'), ' ', QQ('?'),
].join('');

const CODE_STORM_VALUES = [
  C('// Results are immutable values\n'),
  K('fun '), F('usersIn'), P('(cityName: '), T('String'), P('): '), T('List'), P('<'), T('User'), P('> =\n'),
  P('    users.'), F('findAll'), P('('), T('User_'), P('.city.name '), K('eq'), P(' cityName)\n\n'),
  C('// They depend on no session or transaction, so they can be serialized,\n'),
  C('// cached or handed to another thread\n'),
  K('val '), P('json = objectMapper.'), F('writeValueAsString'), P('('), F('usersIn'), P('('), S('"Sunnyvale"'), P('))'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: the model</div>
  <h1>One model instead of <span class="grad">three</span></h1>
  <p class="dek">Exposed and Storm are both type-safe Kotlin data layers. They differ in where joins and row mappings live. Exposed writes them with each query; Storm declares them once, in the data class, and every query reuses them.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Two tables, users and cities, with one foreign key. One query lists the users of a city with the city name, a second filters users on email, and a service returns typed results that a controller serializes.</p>

  <h2><span class="hno">02</span>The Exposed way</h2>
  <p>Exposed, built by JetBrains, declares the schema in table objects, and the compiler checks every column reference. With the DSL the task looks like this:</p>
  ${editor({file: 'Users.kt', tag: 'Kotlin · Exposed DSL', code: CODE_EXPOSED_DSL})}
  <p>All of it is type-safe. The join and the row mapping belong to each query, so the second query states both again. A schema with hundreds of queries carries hundreds of these, and a column added to <code>UserRow</code> means a change in each query that fills it. The DAO layer removes the mapping and adds a third declaration of the same data:</p>
  ${editor({file: 'User.kt', tag: 'Kotlin · Exposed DAO', code: CODE_EXPOSED_DAO})}
  <p>DAO entities are mutable, and the transaction writes the changes made to them. A reference loads on first access unless <code>with()</code> loads it eagerly for that call, so <code>User.all()</code> with and without <code>.with(User::city)</code> differ by one call and run a different number of statements. The <a class="tlink" href="/tutorials/exposed-n-plus-one">eager loading tutorial</a> compares the two approaches.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>In Storm a relation is part of the model. The data class declares the join once, and every query uses it:</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin · Storm', code: CODE_STORM_MODEL, sql: SQL_STORM_MODEL})}
  <p>The second query does not mention the join. <code>User_.city.name</code> is a compile-checked path through the one declaration. On type safety the two libraries are even. The difference is in the count: joins and mappings written per query grow with the number of queries, while the model grows with the number of tables.</p>

  <h2><span class="hno">04</span>Results are values</h2>
  <p>The DSL's mapped rows are plain values too; it is the DAO's entities that belong to their transaction. Storm returns immutable data classes without a mapping step:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_VALUES})}
  <p>Reads need no <code>transaction { }</code>: repositories take a connection per operation, and a transaction comes in where atomicity matters. Lazy loading exists in Storm as a type. A field declared <code>Ref&lt;City&gt;</code> holds the key, and loading the city is an explicit <code>fetch()</code> call. The <a class="tlink" href="/tutorials/n-plus-one">N+1 tutorial</a> covers that model.</p>

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
