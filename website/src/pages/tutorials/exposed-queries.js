import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: queries and full SQL, Exposed vs Storm. Knowledge-mapping for the
// everyday query shapes, plus the query written as SQL text. Exposed facts
// verified against the DSL querying/CRUD docs (selectAll().where, select with
// columns + groupBy, exec with bind arguments and a ResultSet transform).

const TITLE = 'Queries and Full SQL: Exposed vs Storm';
const DESC =
  'Everyday Exposed queries translate to Storm almost line by line, without the ' +
  'row mapping. For SQL written as text, Exposed\'s exec() hands you the ResultSet; ' +
  'Storm\'s SQL templates map rows onto data classes and bind interpolated values.';

const CODE_EXPOSED_QUERIES = [
  C('// Filtering\n'),
  K('val '), P('davises = '), F('transaction'), P(' {\n'),
  P('    '), T('Owners'), P('.'), F('selectAll'), P('()\n'),
  P('        .'), F('where'), P(' { '), T('Owners'), P('.lastName '), K('like'), P(' '), S('"Dav%"'), P(' }\n'),
  P('        .'), F('map'), P(' { it['), T('Owners'), P('.firstName] to it['), T('Owners'), P('.lastName] }\n'),
  P('}\n\n'),
  C('// Aggregating: select the columns, group, map the rows\n'),
  K('val '), P('perOwner = '), F('transaction'), P(' {\n'),
  P('    '), T('Pets'), P('.'), F('select'), P('('), T('Pets'), P('.ownerId, '), T('Pets'), P('.id.'), F('count'), P('())\n'),
  P('        .'), F('groupBy'), P('('), T('Pets'), P('.ownerId)\n'),
  P('        .'), F('map'), P(' { it['), T('Pets'), P('.ownerId] to it['), T('Pets'), P('.id.'), F('count'), P('()] }\n'),
  P('}'),
].join('');

const CODE_STORM_QUERIES = [
  K('val '), P('owners = orm.'), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(')\n'),
  K('val '), P('pets = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(')\n\n'),
  C('// Filtering: the rows come back as Owner values\n'),
  K('val '), P('davises = owners.'), F('findAll'), P('('), T('Owner_'), P('.lastName '), K('like'), P(' '), S('"Dav%"'), P(')\n\n'),
  C('// Aggregating: your select clause, a typed filter and grouping\n'),
  K('data class '), T('OwnerCount'), P('('), K('val '), P('owner: '), T('Owner'), P(', '), K('val '), P('count: '), T('Long'), P(')\n\n'),
  K('val '), P('perOwner = pets\n'),
  P('    .'), F('select'), P('('), T('OwnerCount'), P('::'), K('class'), P(') { '), S('"${Owner::class}, COUNT(*)"'), P(' }\n'),
  P('    .'), F('groupBy'), P('('), T('Pet_'), P('.owner)\n'),
  P('    .resultList'),
].join('');

const SQL_STORM_QUERIES = [
  QC('-- the aggregate: Owner expands to its columns, the join comes from the model'), '\n',
  QK('SELECT'), ' o.id, o.first_name, o.last_name, ', QK('COUNT'), '(*)\n',
  QK('FROM'), ' pet p\n',
  QK('INNER JOIN'), ' owner o ', QK('ON'), ' p.owner_id = o.id\n',
  QK('GROUP BY'), ' o.id',
].join('');

const CODE_EXPOSED_EXEC = [
  C('// exec() binds the arguments and hands you the JDBC ResultSet\n'),
  K('val '), P('ranked = '), F('transaction'), P(' {\n'),
  P('    '), F('exec'), P('(\n'),
  P('        '), S('"""\n            SELECT name, population, RANK() OVER (ORDER BY population DESC)\n            FROM city\n            WHERE population >= ?\n        """'), P(',\n'),
  P('        '), F('listOf'), P('('), T('LongColumnType'), P('() '), K('to'), P(' minPopulation),\n'),
  P('    ) { resultSet ->\n'),
  P('        '), F('buildList'), P(' {\n'),
  P('            '), K('while'), P(' (resultSet.'), F('next'), P('()) {\n'),
  P('                '), F('add'), P('('), T('RankedCity'), P('(resultSet.'), F('getString'), P('('), N('1'), P('), resultSet.'), F('getLong'), P('('), N('2'), P('), resultSet.'), F('getLong'), P('('), N('3'), P(')))\n'),
  P('            }\n'),
  P('        }\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_TEMPLATE = [
  K('data class '), T('RankedCity'), P('('), K('val '), P('name: '), T('String'), P(', '), K('val '), P('population: '), T('Long'), P(', '), K('val '), P('rank: '), T('Long'), P(')\n\n'),
  K('val '), P('ranked = orm.'), F('query'), P(' { '), S('"""'), P('\n'),
  P('    '), K('SELECT'), P(' name, population, RANK() '), K('OVER'), P(' ('), K('ORDER BY'), P(' population '), K('DESC'), P(')\n'),
  P('    '), K('FROM'), P(' city\n'),
  P('    '), K('WHERE'), P(' population >= '), T('$minPopulation'), P('\n'),
  S('"""'), P(' }.'), F('resultList'), P('<'), T('RankedCity'), P('>()'),
].join('');

const SQL_STORM_TEMPLATE = [
  QC('-- $minPopulation is a bind parameter'), '\n',
  QK('SELECT'), ' name, population, ', QK('RANK'), '() ', QK('OVER'), ' (', QK('ORDER BY'), ' population ', QK('DESC'), ')\n',
  QK('FROM'), ' city\n',
  QK('WHERE'), ' population >= ', QQ('?'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: queries</div>
  <h1>Queries and <span class="grad">full SQL</span></h1>
  <p class="dek">Everyday Exposed queries translate to Storm almost line by line, and the row mapping step goes away. The larger difference is in queries written as SQL text.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Three query shapes cover most of a data layer: a filter, an aggregate, and a query the team writes as SQL text, here a report that ranks cities by population.</p>

  <h2><span class="hno">02</span>The everyday queries</h2>
  <p>Exposed's DSL writes filters and aggregates with compile-checked columns:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_QUERIES})}
  <p>The Storm versions read much the same. The rows hydrate into your types, so there is no mapping step, and the join in the aggregate comes from the <code>@FK</code> on <code>Pet</code>:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_QUERIES, sql: SQL_STORM_QUERIES})}
  <p><code>groupBy(Pet_.owner)</code> groups by the owner's key, in the form each database accepts. For text search over columns, Storm has <code>search</code> and <code>suggest</code> predicates; see <a class="tlink" href="/tutorials/search">Search and suggest</a>.</p>

  <h2><span class="hno">03</span>SQL as text</h2>
  <p>Exposed's DSL covers window functions too. When a query is written as SQL text, Exposed runs it with <code>exec()</code>, which binds the arguments you pass as column type and value pairs and hands the JDBC <code>ResultSet</code> to your code:</p>
  ${editor({file: 'ReportService.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_EXEC})}
  <p>A Storm SQL template maps the rows by position onto a data class, and an interpolated Kotlin value becomes a bind parameter:</p>
  ${editor({file: 'ReportService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_TEMPLATE, sql: SQL_STORM_TEMPLATE})}
  <p>The Storm compiler plugin, applied by the Storm Gradle plugin, turns each interpolation into a template value. A template also takes entity types, which expand to column lists and joins, and metamodel paths, which resolve to aliased columns. The <a class="tlink" href="/tutorials/sql-templates">SQL templates tutorial</a> covers that.</p>

  <h2><span class="hno">04</span>The translation table</h2>
  <table class="cmp">
    <tr><th>Exposed</th><th>Storm</th></tr>
    <tr><td><code>selectAll().where { col like x }</code> plus <code>map</code></td><td><code>findAll(Owner_.lastName like x)</code></td></tr>
    <tr><td><code>select(columns).groupBy(col)</code> plus <code>map</code></td><td><code>select(OwnerCount::class) { ... }.groupBy(...)</code>, typed rows</td></tr>
    <tr><td><code>exec(sql, args) { resultSet -> ... }</code></td><td><code>query { sql }.resultList&lt;T&gt;()</code></td></tr>
    <tr><td>Every statement inside <code>transaction { }</code></td><td>Repositories read without one; <code>transaction { }</code> where atomicity matters</td></tr>
  </table>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/queries">Queries</a>
    <a href="/docs/sql-templates">SQL Templates</a>
    <a href="/docs/hydration">Hydration</a>
    <a href="/docs/comparison">Framework Comparison</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedQueriesTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-queries" body={BODY} />;
}
