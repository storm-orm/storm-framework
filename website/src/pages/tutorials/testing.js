import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: testing the data layer with @StormTest and SqlCapture. First entry
// of "The Storm way" how-to series (no framework comparison, just the recipe).
// Verified against docs/testing.md (parameter injection, per-test rollback and
// its concurrent-transaction refusal, TestDatabase, Testcontainers 2 module
// names) and docs/advice.md (every @StormTest reports all advice categories).

const TITLE = 'Testing Your Data Layer with @StormTest';
const DESC =
  'One annotation gives you an in-memory database, schema scripts, per-test ' +
  'rollback and injected ORMTemplate and SqlCapture parameters. Assert results ' +
  'and the SQL that produced them, then run the same tests against PostgreSQL ' +
  'with one attribute.';

const CODE_BASIC = [
  A('@StormTest'), P('(scripts = ['), S('"/schema.sql"'), P(', '), S('"/data.sql"'), P('])\n'),
  K('class '), T('OwnerRepositoryTest'), P(' {\n\n'),
  P('    '), A('@Test'), P('\n'),
  P('    '), K('fun '), F('`finds owners by last name`'), P('(orm: '), T('ORMTemplate'), P(') {\n'),
  P('        '), K('val '), P('owners = orm.'), F('entity'), P('('), T('Owner'), P('::'), K('class'), P(').'), F('findAll'), P('('), T('Owner_'), P('.lastName '), K('eq'), P(' '), S('"Smith"'), P(')\n\n'),
  P('        owners.size '), K('shouldBe'), P(' '), N('2'), P('\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_CAPTURE = [
  A('@Test'), P('\n'),
  K('fun '), F('`pet list loads owners in the same query`'), P('(orm: '), T('ORMTemplate'), P(', capture: '), T('SqlCapture'), P(') {\n'),
  P('    '), K('val '), P('pets = capture.'), F('execute'), P(' { orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(').'), F('findAll'), P('() }\n\n'),
  P('    pets.'), F('forEach'), P(' { '), F('render'), P('(it.owner.lastName) }   '), C('// the owner came with the pet\n'),
  P('    capture.'), F('count'), P('('), T('Operation'), P('.'), T('SELECT'), P(') '), K('shouldBe'), P(' '), N('1'), P('   '), C('// a second query fails the test\n'),
  P('    capture.'), F('count'), P('('), T('Operation'), P('.'), T('UPDATE'), P(') '), K('shouldBe'), P(' '), N('0'), P('   '), C('// a read writes nothing\n'),
  P('}'),
].join('');

const CODE_CONTAINERS = [
  A('@StormTest'), P('(database = '), T('TestDatabase'), P('.'), T('POSTGRESQL'), P(', scripts = ['), S('"/schema.sql"'), P(', '), S('"/data.sql"'), P('])\n'),
  K('class '), T('PostgresOwnerTest'), P(' {\n\n'),
  P('    '), A('@Test'), P('\n'),
  P('    '), K('fun '), F('`finds owners by last name`'), P('(orm: '), T('ORMTemplate'), P(') {\n'),
  P('        '), C('// the same test body, run on PostgreSQL\n'),
  P('    }\n'),
  P('}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Testing the data layer</div>
  <h1>Testing your data layer with <span class="grad">@StormTest</span></h1>
  <p class="dek">A data-layer test usually starts with setup: a DataSource, schema scripts, wiring. Storm's test module replaces that with one annotation, and lets you assert on the SQL a block of code ran.</p>
  <div class="meta"><span>Series · The Storm way</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Test repository and query code against a real schema, fast enough to run on every build, without starting a dependency-injection container or writing connection code in every test class.</p>

  <h2><span class="hno">02</span>One annotation</h2>
  <p><code>@StormTest</code> creates an in-memory H2 database, runs your schema and data scripts once per class, and injects test method parameters. It needs no Spring context and no base class:</p>
  ${editor({file: 'OwnerRepositoryTest.kt', tag: 'Kotlin · storm-test', code: CODE_BASIC})}
  <p>Parameters resolve by type: <code>ORMTemplate</code>, <code>DataSource</code>, <code>SqlCapture</code>, or any type with a static <code>of(DataSource)</code> factory. Each test runs in a transaction that is rolled back afterwards, so every test starts from the state the scripts created and count assertions can be exact. A <code>transaction { }</code> block inside a test commits and rolls back as usual, on a savepoint. All of a test's connections share that one transaction, so a transaction that begins on another thread while one is open fails and names <code>rollback = false</code>, the setting for tests that need real concurrent transactions.</p>

  <h2><span class="hno">03</span>Assert the SQL, not only the rows</h2>
  <p>A test that checks results passes as long as the rows are right, however many queries produced them. <code>SqlCapture</code> records every statement a block runs, with its operation, bound parameters, origin and database time, so the query count becomes an assertion:</p>
  ${editor({file: 'PetQueryTest.kt', tag: 'Kotlin · storm-test', code: CODE_CAPTURE})}
  <p>"No N+1", "this service only reads" and "the batch runs one statement" each become one line that fails the build when the code changes. <code>count(Origin.FETCH)</code> counts the statements that resolved references, the ones that grow with the number of rows.</p>
  <p>The test log is worth reading too. Every <code>@StormTest</code> reports all advice categories, so the log names statements worth changing, such as a <code>distinct</code> over a to-many join or an entity past the design budget, while the test still passes. <a class="tlink" href="/tutorials/advice">Statement advice</a> explains how to read it.</p>

  <h2><span class="hno">04</span>The same tests against PostgreSQL</h2>
  <p>H2 keeps the loop fast, but it accepts SQL your production database rejects and behaves differently on upserts, sequences, identity columns and keyword escaping. The <code>database</code> attribute runs a test class on the real database, in a Testcontainers container that every test class of the run shares:</p>
  ${editor({file: 'PostgresOwnerTest.kt', tag: 'Kotlin · storm-test', code: CODE_CONTAINERS})}
  <p>Scripts, parameter injection, <code>SqlCapture</code> and per-test rollback work as on H2, so both suites share their test code. Each class gets a new, empty database inside the shared container, so the scripts run without drop guards. Add <code>org.testcontainers:testcontainers-postgresql</code> and the PostgreSQL driver to the test classpath; H2 tests need nothing new. MySQL, MariaDB, SQL Server and Oracle work the same way, and the Spring Boot <code>@DataStormTest</code> slice takes the same attribute.</p>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/testing">Testing</a>
    <a href="/docs/sql-logging">SQL Logging</a>
    <a href="/docs/advice">Statement Advice</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function TestingTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="testing" body={BODY} />;
}
