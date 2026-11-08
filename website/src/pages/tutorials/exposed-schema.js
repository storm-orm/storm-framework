import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: schema ownership, Exposed vs Storm. Exposed generates DDL from table
// objects; Storm is schema-first and validates the mapping against the live
// database. Storm facts verified against docs/validation.md, docs/ktor-integration.md
// (migration hook, schemaValidation none/load/warn/fail, storm.validation.*),
// docs/advice.md#loading-the-schema and the Kotlin ORMTemplate
// (validateSchema, validateSchemaOrThrow, loadSchema).

const TITLE = 'Who Owns the Schema: Exposed vs Storm';
const DESC =
  'Exposed can create the schema from its table objects, which suits prototypes and ' +
  'tests. Storm leaves the schema to the database and your migrations, and validates ' +
  'at startup that the entities still match it.';

const CODE_EXPOSED_SCHEMA = [
  C('// The table objects describe the columns, so they can create the tables\n'),
  F('transaction'), P(' {\n'),
  P('    '), T('SchemaUtils'), P('.'), F('create'), P('('), T('Cities'), P(', '), T('Users'), P(')\n'),
  P('}'),
].join('');

const CODE_STORM_KTOR = [
  F('install'), P('('), T('Storm'), P(') {\n'),
  P('    '), C('// runs before validation, so validation sees the migrated schema\n'),
  P('    '), F('migration'), P(' { dataSource ->\n'),
  P('        '), T('Flyway'), P('.'), F('configure'), P('().'), F('dataSource'), P('(dataSource).'), F('load'), P('().'), F('migrate'), P('()\n'),
  P('    }\n'),
  P('    schemaValidation = '), S('"fail"'), P('   '), C('// "none", "load", "warn" or "fail"\n'),
  P('}'),
].join('');

const CODE_STORM_CONFIG = [
  C('# application.conf\n'),
  P('storm {\n'),
  P('    validation {\n'),
  P('        schemaMode = '), S('"fail"'), P('\n'),
  P('        strict = '), K('true'), P('   '), C('# warnings fail validation too\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_VALIDATE = [
  C('// Without the Ktor or Spring Boot integration, call it yourself\n'),
  K('val '), P('errors: '), T('List'), P('<'), T('String'), P('> = orm.'), F('validateSchema'), P('()\n'),
  P('orm.'), F('validateSchemaOrThrow'), P('()   '), C('// throws PersistenceException listing the errors\n'),
  P('orm.'), F('loadSchema'), P('()             '), C('// reads the schema for advice, without validating'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: schema</div>
  <h1>Who owns <span class="grad">the schema</span></h1>
  <p class="dek">Exposed can create the schema from code, which helps on the first day of a project. Storm leaves the schema to the database and your migrations, and checks that the entities still match it.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Two moments in a project. On day one you want the tables to exist. In year two the schema holds production data, migrations change it, and the risk is that the code and the database disagree about a column.</p>

  <h2><span class="hno">02</span>The Exposed way</h2>
  <p>Exposed's table objects describe the columns and their types, so they can create the schema:</p>
  ${editor({file: 'Setup.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_SCHEMA})}
  <p>For prototypes, tests and a new project this is convenient, and Storm has no equivalent: it does not generate DDL. For later changes, <code>MigrationUtils</code> in the <code>exposed-migration</code> modules generates the statements a schema change needs; teams usually feed them into a migration tool such as Flyway or Liquibase. The table objects stay a second description of the schema, next to the migrations.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>In Storm the schema that your migration tool builds is the source of truth, and entities describe it. The Ktor plugin runs the migration and then validates the entities against the result:</p>
  ${editor({file: 'Application.kt', tag: 'Kotlin · Storm + Ktor', code: CODE_STORM_KTOR})}
  <p><code>fail</code> is the default in Ktor and Spring Boot, so the check runs at startup without configuration. It reports tables and columns that do not exist, incompatible types, nullability mismatches, primary and foreign keys that differ from the entity, missing sequences, and <code>NOT NULL</code> columns without a default that no field maps. Constraints the entity declares but the database lacks are warnings; a unique index counts as the constraint behind a <code>@UK</code> field. An entity can map to a table, a view, a materialized view or a partitioned table.</p>
  <p><code>strict</code> turns the warnings into errors. Set it in <code>application.conf</code>, or under <code>storm.validation</code> in Spring Boot, where the mode is <code>schema-mode</code>:</p>
  ${editor({file: 'application.conf', tag: 'HOCON · Storm', code: CODE_STORM_CONFIG})}
  <p>Outside the integrations, the same checks are methods on the template:</p>
  ${editor({file: 'Startup.kt', tag: 'Kotlin · Storm', code: CODE_STORM_VALIDATE})}
  <p>Validation also keeps the schema it read, indexes included, and Storm uses it to advise on a slow statement that no index serves. <code>"load"</code> reads the schema at startup without validating it. The <a class="tlink" href="/tutorials/advice">advice tutorial</a> shows what that adds. To catch drift before it ships, run validation in a test against the migrated database: <a class="tlink" href="/tutorials/testing">@StormTest</a> runs your schema scripts, on H2 or in a container of the database you deploy on.</p>

  <h2><span class="hno">04</span>The trade</h2>
  <table class="cmp">
    <tr><th></th><th>Exposed</th><th>Storm</th></tr>
    <tr><td>Creating the schema</td><td><code>SchemaUtils.create(...)</code> from table objects</td><td>Your migration tool; Storm does not generate DDL</td></tr>
    <tr><td>Changing the schema</td><td><code>MigrationUtils</code> generates statements; a migration tool applies them</td><td>Your migration tool; the entities follow</td></tr>
    <tr><td>Checking the mapping</td><td><code>MigrationUtils</code> lists the statements that would align the database; a check you write</td><td>Validation at startup by default, <code>validateSchemaOrThrow()</code> elsewhere</td></tr>
    <tr><td>Source of truth</td><td>The table objects, until migrations take over</td><td>The database</td></tr>
  </table>
  <p>If a project spends most of its time as a prototype, Exposed's schema generation is the better workflow. Storm assumes a schema spends most of its life under migrations, and checks the mapping against it on every start.</p>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/validation">Schema Validation</a>
    <a href="/docs/ktor-integration#schema-validation">Ktor Schema Validation</a>
    <a href="/docs/advice#loading-the-schema">Loading the Schema</a>
    <a href="/docs/testing">Testing</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedSchemaTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-schema" body={BODY} />;
}
