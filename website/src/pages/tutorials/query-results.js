import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: typed query results (what Spring Data calls DTO projections),
// JPA vs Storm. Storm's answer to the per-query result shape is a plain data
// class mapped by position. The reusable read-model concept (Projection) has
// its own tutorial: projections.js.

const TITLE = 'Typed Query Results: JPA vs Storm';
const DESC =
  'Spring Data JPA shapes custom results with interface projections and ' +
  'constructor expressions. In Storm, any data class whose constructor matches ' +
  'the selected columns is a result type, for the query builder and for full SQL.';

const CODE_JPA_INTERFACE = [
  C('// Interface-based projection: Spring Data implements it with a proxy\n'),
  K('interface '), T('OwnerListItem'), P(' {\n'),
  P('    '), K('val '), P('id: '), T('Int'), P('\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P('\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P('\n'),
  P('}\n\n'),
  K('interface '), T('OwnerRepository'), P(' : '), T('JpaRepository'), P('<'), T('Owner'), P(', '), T('Int'), P('> {\n'),
  P('    '), K('fun '), F('findAllProjectedBy'), P('(): '), T('List'), P('<'), T('OwnerListItem'), P('>\n'),
  P('}'),
].join('');

const CODE_JPA_CONSTRUCTOR = [
  K('data class '), T('OwnerDto'), P('('), K('val '), P('id: '), T('Int'), P(', '), K('val '), P('firstName: '), T('String'), P(', '), K('val '), P('lastName: '), T('String'), P(')\n\n'),
  K('interface '), T('OwnerRepository'), P(' : '), T('JpaRepository'), P('<'), T('Owner'), P(', '), T('Int'), P('> {\n\n'),
  C('    // A JPQL constructor expression names the class in the query\n'),
  P('    '), A('@Query'), P('('), S('"SELECT new com.acme.owners.OwnerDto(o.id, o.firstName, o.lastName) FROM Owner o"'), P(')\n'),
  P('    '), K('fun '), F('findAllSummaries'), P('(): '), T('List'), P('<'), T('OwnerDto'), P('>\n'),
  P('}'),
].join('');

const CODE_STORM_BUILDER = [
  K('data class '), T('CityCount'), P('('), K('val '), P('city: '), T('String'), P(', '), K('val '), P('count: '), T('Long'), P(')\n\n'),
  K('val '), P('counts = orm.'), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(')\n'),
  P('    .'), F('select'), P('('), T('CityCount'), P('::'), K('class'), P(') { '), S('"${Owner_.city}, COUNT(*)"'), P(' }\n'),
  P('    .'), F('where'), P('('), T('Owner_'), P('.city '), K('like'), P(' '), S('"S%"'), P(')\n'),
  P('    .'), F('groupBy'), P('('), T('Owner_'), P('.city)\n'),
  P('    .resultList'),
].join('');

const SQL_STORM_BUILDER = [
  QC('-- the select clause is the template; the rest comes from the builder'), '\n',
  QK('SELECT'), ' o.city, ', QK('COUNT'), '(*)\n',
  QK('FROM'), ' owner o\n',
  QK('WHERE'), ' o.city ', QK('LIKE'), ' ', QQ('?'), '\n',
  QK('GROUP BY'), ' o.city',
].join('');

const CODE_STORM_RESULT = [
  K('data class '), T('OwnerListItem'), P('('), K('val '), P('id: '), T('Int'), P(', '), K('val '), P('firstName: '), T('String'), P(', '), K('val '), P('lastName: '), T('String'), P(')\n\n'),
  K('val '), P('owners = orm.'), F('query'), P(' { '), S('"""'), P('\n'),
  P('    '), K('SELECT'), P(' id, first_name, last_name\n'),
  P('    '), K('FROM'), P(' owner\n'),
  S('"""'), P(' }.'), F('resultList'), P('<'), T('OwnerListItem'), P('>()'),
].join('');

const CODE_STORM_AGGREGATE = [
  K('data class '), T('VisitCount'), P('('), K('val '), P('petName: '), T('String'), P(', '), K('val '), P('visits: '), T('Long'), P(')\n\n'),
  K('val '), P('counts = orm.'), F('query'), P(' { '), S('"""'), P('\n'),
  P('    '), K('SELECT'), P(' p.name, COUNT(v.id)\n'),
  P('    '), K('FROM'), P(' pet p\n'),
  P('    '), K('JOIN'), P(' visit v '), K('ON'), P(' v.pet_id = p.id\n'),
  P('    '), K('WHERE'), P(' v.visit_date >= '), T('$since'), P('\n'),
  P('    '), K('GROUP BY'), P(' p.name\n'),
  S('"""'), P(' }.'), F('resultList'), P('<'), T('VisitCount'), P('>()'),
].join('');

const SQL_STORM_AGGREGATE = [
  QC('-- $since is a bind parameter'), '\n',
  QK('SELECT'), ' p.name, ', QK('COUNT'), '(v.id)\n',
  QK('FROM'), ' pet p\n',
  QK('JOIN'), ' visit v ', QK('ON'), ' v.pet_id = p.id\n',
  QK('WHERE'), ' v.visit_date >= ', QQ('?'), '\n',
  QK('GROUP BY'), ' p.name',
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Query results</div>
  <h1>Typed query results <span class="grad">without an entity</span></h1>
  <p class="dek">A list screen needs three columns and a report needs an aggregate. Neither needs an entity. Spring Data JPA shapes such results with interface projections and constructor expressions; in Storm, any data class with a matching constructor is a result type.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>An owners list shows id, first name and last name; the <code>owner</code> table has more columns than that. A report counts owners per city, and another counts visits per pet. Each wants a typed result without a mapping layer of its own.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>Spring Data JPA offers two routes. An interface projection, which Spring Data implements with a proxy and, for a closed projection like this one, reads only its properties:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · JPA', code: CODE_JPA_INTERFACE})}
  <p>And a class, built from a derived query or a JPQL constructor expression:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · JPA', code: CODE_JPA_CONSTRUCTOR})}
  <p>Both work. Either way the query is written in JPQL against the entities, or as a native query whose rows map onto the projection by column alias.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>In Storm a result type is a data class whose constructor matches the selected columns by position. The query builder takes it with a select clause of your own, and the filter and grouping stay metamodel paths:</p>
  ${editor({file: 'ReportService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_BUILDER, sql: SQL_STORM_BUILDER})}
  <p>The result is a list of immutable <code>CityCount</code> values, ready to serialize. <code>groupBy</code> also takes a relationship, such as <code>User_.city</code>, and then groups by the related row's key in the form each database accepts.</p>

  <h2><span class="hno">04</span>Full SQL maps the same way</h2>
  <p>A SQL template maps its rows by position too, onto a class defined next to the query:</p>
  ${editor({file: 'OwnerService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_RESULT})}
  <p>Interpolated values become bind parameters, so a filter value never ends up in the SQL text:</p>
  ${editor({file: 'ReportService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_AGGREGATE, sql: SQL_STORM_AGGREGATE})}
  <p>The class and the query live and go together. Templates can also expand an entity into its columns and joins; the <a class="tlink" href="/tutorials/sql-templates">SQL templates tutorial</a> shows how.</p>

  <h2><span class="hno">05</span>When the shape is reused</h2>
  <div class="note">A result class serves one query. A shape that several places read, that nests in other types or takes typed predicates, is a read model, and Storm models it as a <code>Projection</code> with a repository of its own. See <a href="/tutorials/projections">Projections</a>.</div>

  <h2><span class="hno">06</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>Spring Data JPA</th><th>Storm</th></tr>
    <tr><td>Result type</td><td>An interface backed by a proxy, or a class with a matching constructor</td><td>A data class with a matching constructor</td></tr>
    <tr><td>Mapping</td><td>By property name or column alias, or by constructor arguments in JPQL</td><td>By column position</td></tr>
    <tr><td>Query language</td><td>JPQL against the entities, or native SQL</td><td>The query builder with your select clause, or SQL templates</td></tr>
    <tr><td>Reused read model</td><td>An entity over the table or a view</td><td>A <code>Projection</code></td></tr>
  </table>

  <h2><span class="hno">07</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/hydration">Hydration</a>
    <a href="/docs/sql-templates">SQL Templates</a>
    <a href="/docs/projections">Projections</a>
    <a href="/docs/security">Security</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function QueryResultsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="query-results" body={BODY} />;
}
