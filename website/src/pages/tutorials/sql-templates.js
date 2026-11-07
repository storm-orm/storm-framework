import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: dropping to full SQL, JPA native queries vs Storm SQL templates.

const TITLE = 'Full SQL With Your Model: Native Queries vs Storm';
const DESC =
  'A JPA native query is SQL text the entity model does not see. Storm\'s SQL ' +
  'templates bind interpolated values as parameters, map rows onto plain data ' +
  'classes, and expand entity types and metamodel paths into columns, joins and aliases.';

const CODE_JPA_NATIVE = [
  C('// Spring Data maps native rows onto an interface projection by alias\n'),
  K('interface '), T('RankedCity'), P(' {\n'),
  P('    '), K('val '), P('name: '), T('String'), P('\n'),
  P('    '), K('val '), P('population: '), T('Long'), P('\n'),
  P('    '), K('val '), P('rank: '), T('Long'), P('\n'),
  P('}\n\n'),
  A('@Query'), P('(\n'),
  P('    value = '), S('"""\n        SELECT name, population,\n               RANK() OVER (ORDER BY population DESC) AS rank\n        FROM city\n        WHERE population >= :minPopulation\n    """'), P(',\n'),
  P('    nativeQuery = '), K('true'), P(',\n'),
  P(')\n'),
  K('fun '), F('rankedCities'), P('(minPopulation: '), T('Long'), P('): '), T('List'), P('<'), T('RankedCity'), P('>'),
].join('');

const CODE_STORM_QUERY = [
  K('data class '), T('RankedCity'), P('('), K('val '), P('name: '), T('String'), P(', '), K('val '), P('population: '), T('Long'), P(', '), K('val '), P('rank: '), T('Long'), P(')\n\n'),
  K('val '), P('ranked = orm.'), F('query'), P(' { '), S('"""'), P('\n'),
  P('    '), K('SELECT'), P(' name, population, RANK() '), K('OVER'), P(' ('), K('ORDER BY'), P(' population '), K('DESC'), P(')\n'),
  P('    '), K('FROM'), P(' city\n'),
  P('    '), K('WHERE'), P(' population >= '), T('$minPopulation'), P('\n'),
  S('"""'), P(' }.'), F('resultList'), P('<'), T('RankedCity'), P('>()'),
].join('');

const SQL_STORM_QUERY = [
  QC('-- $minPopulation is a bind parameter'), '\n',
  QK('SELECT'), ' name, population, ', QK('RANK'), '() ', QK('OVER'), ' (', QK('ORDER BY'), ' population ', QK('DESC'), ')\n',
  QK('FROM'), ' city\n',
  QK('WHERE'), ' population >= ', QQ('?'),
].join('');

const CODE_STORM_TEMPLATE = [
  C('// A type expands to its columns, or to its table and joins\n'),
  K('val '), P('owners = orm.'), F('query'), P(' { '), S('"""'), P('\n'),
  P('    '), K('SELECT'), P(' '), T('${Owner::class}'), P('\n'),
  P('    '), K('FROM'), P(' '), T('${Owner::class}'), P('\n'),
  P('    '), K('WHERE'), P(' '), T('${Owner_.address.city.name}'), P(' = '), T('$cityName'), P('\n'),
  S('"""'), P(' }.'), F('resultList'), P('<'), T('Owner'), P('>()'),
].join('');

const SQL_STORM_TEMPLATE = [
  QC('-- Owner expands to its columns and those of its city, FROM gains the @FK join,'), '\n',
  QC('-- and Owner_.address.city.name resolves to the city alias'), '\n',
  QK('SELECT'), ' o.id, o.first_name, o.last_name, o.street, c.id, c.name, c.population, o.telephone\n',
  QK('FROM'), ' owner o\n',
  QK('LEFT JOIN'), ' city c ', QK('ON'), ' o.city_id = c.id\n',
  QK('WHERE'), ' c.name = ', QQ('?'),
].join('');

const CODE_STORM_DATA = [
  C('// Data enables template expansion for a type without a repository\n'),
  A('@DbTable'), P('('), S('"pet"'), P(')\n'),
  K('data class '), T('PetWithOwner'), P('(\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), K('val '), P('birthDate: '), T('LocalDate'), P('?,\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Owner'), P(',\n'),
  P(') : '), T('Data'), P('\n\n'),
  K('val '), P('pets = orm.'), F('query'), P(' { '), S('"""'), P('\n'),
  P('    '), K('SELECT'), P(' '), T('${PetWithOwner::class}'), P('\n'),
  P('    '), K('FROM'), P(' '), T('${PetWithOwner::class}'), P('\n'),
  P('    '), K('WHERE'), P(' '), T('${Owner_.address.city}'), P(' = '), T('$city'), P('\n'),
  S('"""'), P(' }.'), F('resultList'), P('<'), T('PetWithOwner'), P('>()'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>SQL templates</div>
  <h1>Full SQL <span class="grad">with your model</span></h1>
  <p class="dek">Some queries are best written in the database's own SQL. In JPA that is a native query, a string the entity model does not see. A Storm SQL template is SQL text in which values bind as parameters and entity types and metamodel paths expand into columns, joins and aliases.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A report ranks cities by population with a window function, <code>RANK() OVER</code>, and the team writes it in the database's SQL. A second query reads owners by the name of their city, and should not repeat the column list and the join by hand.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>JPA runs SQL through a native query. Spring Data maps its rows onto an interface projection:</p>
  ${editor({file: 'CityRepository.kt', tag: 'Kotlin · JPA', code: CODE_JPA_NATIVE})}
  <p>Each property of the interface matches a column alias, and the result is a proxy behind the interface. A class result takes an <code>@SqlResultSetMapping</code>. The SQL text is not checked against the entities, so a column renamed in an entity mapping stays in the native query until it runs.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>A Storm SQL template is a Kotlin string in a lambda. Interpolated values become bind parameters, and rows map by position onto any data class:</p>
  ${editor({file: 'ReportService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_QUERY, sql: SQL_STORM_QUERY})}
  <p>The Storm compiler plugin, which the Storm Gradle plugin applies, wraps each interpolation in the lambda in a <code>t()</code> call, so <code>$minPopulation</code> reaches the database as a <code>?</code> with a bound value. Without the compiler plugin, write <code>t(minPopulation)</code> yourself. A <code>null</code> value binds as SQL <code>NULL</code>. <code>RankedCity</code> is an ordinary data class defined next to the query.</p>

  <h2><span class="hno">04</span>Templates that know your model</h2>
  <p>A template also takes entity types and metamodel paths. A type in the select list expands to its columns; in the FROM clause it expands to its table and the joins its <code>@FK</code> fields declare. A metamodel path resolves to the column on the right alias:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_TEMPLATE, sql: SQL_STORM_TEMPLATE})}
  <p>The parts you write stay SQL, and the column lists and joins come from the model in the same statement. A renamed field changes the metamodel, so a path to it no longer compiles.</p>

  <h2><span class="hno">05</span>One-off result shapes</h2>
  <p>A report rarely needs an entity. A type that implements <code>Data</code> gets template expansion and the joins of its <code>@FK</code> fields, without a repository:</p>
  ${editor({file: 'ReportService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_DATA})}
  <p>That gives three steps. A plain data class such as <code>RankedCity</code> takes a SELECT you write in full and maps by position (see <a class="tlink" href="/tutorials/query-results">Typed query results</a>). A <code>Data</code> type adds expansion and joins. A shape that the codebase reuses, with a repository, nesting and predicates, is a <code>Projection</code> (see <a class="tlink" href="/tutorials/projections">Projections</a>).</p>

  <h2><span class="hno">06</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>JPA native queries</th><th>Storm SQL templates</th></tr>
    <tr><td>Parameters</td><td>Named or positional placeholders</td><td>Interpolated values, bound as parameters</td></tr>
    <tr><td>Result mapping</td><td>Interface projections by column alias; <code>@SqlResultSetMapping</code> for classes</td><td>Any data class, by position</td></tr>
    <tr><td>Model awareness</td><td>The SQL text is not checked against the entities</td><td>Types expand to columns and joins; metamodel paths resolve to aliased columns</td></tr>
    <tr><td>Engine</td><td>Separate from JPQL and the Criteria API</td><td>The same engine the query builder renders through</td></tr>
  </table>

  <h2><span class="hno">07</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/sql-templates">SQL Templates</a>
    <a href="/docs/string-templates">String Templates</a>
    <a href="/docs/hydration">Hydration</a>
    <a href="/docs/metamodel">Metamodel</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function SqlTemplatesTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="sql-templates" body={BODY} />;
}
