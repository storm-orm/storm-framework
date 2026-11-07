import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: JSON columns. Storm-way how-to; content verified against
// docs/json.md (@Json fields, complex types, converter selection per field)
// and the JSON_ARRAYAGG integration tests of the JSON modules.

const TITLE = 'JSON Columns in Relational Entities';
const DESC =
  'Map document-shaped fields inside relational entities with @Json: maps, ' +
  'lists and structured objects are serialized on write and read back typed, ' +
  'and JSON aggregation loads a one-to-many in a single query.';

const CODE_JSON_BASIC = [
  C('// @Json maps a field onto a JSON column\n'),
  K('data class '), T('Owner'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P('    '), A('@Json'), P(' '), K('val '), P('preferences: '), T('Map'), P('<'), T('String'), P(', '), T('String'), P('>,\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  C('// Written as JSON, read back as a Map\n'),
  K('val '), P('owner = orm '), K('insert '), T('Owner'), P('(\n'),
  P('    firstName = '), S('"Betty"'), P(',\n'),
  P('    lastName = '), S('"Davis"'), P(',\n'),
  P('    preferences = '), F('mapOf'), P('('), S('"reminders"'), P(' '), K('to'), P(' '), S('"email"'), P(', '), S('"locale"'), P(' '), K('to'), P(' '), S('"en"'), P('),\n'),
  P(')'),
].join('');

const CODE_JSON_COMPLEX = [
  C('// A structured object with a fixed shape and no table of its own\n'),
  A('@Serializable'), P('   '), C('// for kotlinx.serialization; Jackson needs no annotation\n'),
  K('data class '), T('Diet'), P('(\n'),
  P('    '), K('val '), P('food: '), T('String'), P(',\n'),
  P('    '), K('val '), P('gramsPerDay: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('allergies: '), T('List'), P('<'), T('String'), P('>,\n'),
  P(')\n\n'),
  K('data class '), T('Pet'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Owner'), P(',\n'),
  P('    '), A('@Json'), P(' '), K('val '), P('diet: '), T('Diet'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>'),
].join('');

const CODE_JSON_AGG = [
  C('// The pet names of each owner, aggregated into a JSON array by the database\n'),
  K('data class '), T('PetNamesByOwner'), P('(\n'),
  P('    '), K('val '), P('owner: '), T('Owner'), P(',\n'),
  P('    '), A('@Json'), P(' '), K('val '), P('pets: '), T('List'), P('<'), T('String'), P('>,\n'),
  P(')\n\n'),
  K('interface '), T('OwnerRepository'), P(' : '), T('EntityRepository'), P('<'), T('Owner'), P(', '), T('Int'), P('> {\n\n'),
  P('    '), K('fun '), F('getPetNames'), P('(): '), T('List'), P('<'), T('PetNamesByOwner'), P('> =\n'),
  P('        '), F('select'), P('('), T('PetNamesByOwner'), P('::'), K('class'), P(') { '), S('"${Owner::class}, JSON_ARRAYAGG(${Pet_.name})"'), P(' }\n'),
  P('            .'), F('innerJoin'), P('<'), T('Pet'), P('>().'), F('on'), P('<'), T('Owner'), P('>()\n'),
  P('            .'), F('groupBy'), P('('), T('Owner_'), P('.id)\n'),
  P('            .resultList\n'),
  P('}'),
].join('');

const SQL_JSON_AGG = [
  QC('-- the pet names of each owner arrive as one JSON array column'), '\n',
  QK('SELECT'), ' o.id, o.first_name, o.last_name, o.preferences, ', QK('JSON_ARRAYAGG'), '(p.name)\n',
  QK('FROM'), ' owner o\n',
  QK('INNER JOIN'), ' pet p ', QK('ON'), ' p.owner_id = o.id\n',
  QK('GROUP BY'), ' o.id',
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>JSON columns</div>
  <h1>JSON columns in <span class="grad">relational entities</span></h1>
  <p class="dek">Some data is document-shaped: preferences, diets, snapshots. Mark the field <code>@Json</code> and Storm writes it to a JSON column and reads it back into the typed field.</p>
  <div class="meta"><span>Series · The Storm way</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Store owner preferences and a pet's diet without a table for each, and load the pet names of every owner in one query.</p>

  <h2><span class="hno">02</span>Maps and collections</h2>
  <p>Add the JSON module for your serializer: <code>storm-kotlinx-serialization</code>, or <code>storm-jackson2</code> or <code>storm-jackson3</code>. Storm detects it at runtime. Then annotate the field:</p>
  ${editor({file: 'Owner.kt', tag: 'Kotlin · Storm', code: CODE_JSON_BASIC})}
  <p>With kotlinx.serialization and Jackson both on the classpath, the converter is chosen per field: kotlinx.serialization converts a type it has a serializer for, Jackson the rest. Classpath order plays no part.</p>

  <h2><span class="hno">03</span>Structured objects</h2>
  <p>A JSON field can also hold a domain object with a fixed shape that does not need a table of its own. It keeps its type on both ends:</p>
  ${editor({file: 'Pet.kt', tag: 'Kotlin · Storm', code: CODE_JSON_COMPLEX})}
  <p>Reading ignores a property the class does not declare. <code>@Json(failOnUnknown = true)</code> refuses such a document instead.</p>

  <h2><span class="hno">04</span>One-to-many in one query</h2>
  <p>The same conversion reads the result of a JSON aggregate. The database collects the related rows of each group into a JSON array, and Storm reads the array into a typed list, so the one-to-many loads in one round trip:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · Storm', code: CODE_JSON_AGG, sql: SQL_JSON_AGG})}
  <p>This fits a collection of moderate size that the caller uses whole. When a collection can grow without bound, or has to be paged, read it with a second query instead, such as <code>orm.findAll(Pet_.owner inList owners)</code>. The <a class="tlink" href="/docs/json">JSON docs</a> give the limits and when to split.</p>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/json">JSON</a>
    <a href="/docs/serialization">Serialization</a>
    <a href="/docs/converters">Converters</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function JsonColumnsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="json-columns" body={BODY} />;
}
