import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {
  TUT_CSS,
  navHtml,
  FOOT_HTML,
  editor,
  wireSqlToggles,
  KOTLIN_VARIANTS,
  DATABASE_VARIANTS,
  DISCORD,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../components/tutorial/tutorialTheme';

// The 5-minute quickstart. Built in the landing/tutorial style (see
// tutorialTheme.js) rather than as a docs page so it deploys live immediately
// and is the target of every "Get started" button on the site. Kotlin-first;
// leads with the manual build, with the Storm CLI one-liner (AI-assisted
// setup) as a secondary suggestion below it. The install snippet renders the
// resolved release version, read from siteConfig.customFields.stormVersion
// (docusaurus.config.ts).

const TITLE = 'Quickstart · Zero to Storm in five minutes';
const DESC =
  'Set up Storm, define two linked entities as plain Kotlin data classes, and ' +
  'query across the relation in one type-safe line. The whole path from empty ' +
  'project to first query, with the SQL it generates.';

function buildBody(version) {
  const cliCommand = 'npx @storm-orm/cli init';
  const cli = C("# from the root of your project's workspace\n") + P(cliCommand);

  // One install snippet per supported Kotlin line (KOTLIN_VARIANTS in the
  // shared theme), switchable in the editor title bar. The Storm plugin applies
  // its bundled KSP itself, so only the Kotlin-paired lines declare KSP.
  const installFor = ({kotlin, ksp, kspRequired}) =>
    C('// build.gradle.kts\n') +
    F('plugins') + P(' {\n') +
    P('    ') + F('kotlin') + P('(') + S('"jvm"') + P(') version ') + S(`"${kotlin}"`) + P('\n') +
    (kspRequired
      ? P('    ') + F('id') + P('(') + S('"com.google.devtools.ksp"') + P(') version ') + S(`"${ksp}"`) + P('\n')
      : '') +
    P('    ') + F('id') + P('(') + S('"st.orm"') + P(') version ') + S(`"${version}"`) + P('\n') +
    P('}\n\n') +
    F('dependencies') + P(' {\n') +
    P('    ') + F('runtimeOnly') + P('(') + S('"st.orm:storm-h2"') + P(')          ') + C('// zero-setup in-memory database\n') +
    P('    ') + F('runtimeOnly') + P('(') + S('"com.h2database:h2:2.3.232"') + P(')\n') +
    P('}');

  const entity =
    C('// Entities.kt: plain data classes. This is the whole data layer.\n') +
    K('data class ') + T('Director') + P('(\n') +
    P('    ') + A('@PK') + P(' ') + K('val ') + P('id: ') + T('Int') + P(' = ') + N('0') + P(',\n') +
    P('    ') + K('val ') + P('name: ') + T('String') + P(',\n') +
    P(') : ') + T('Entity') + P('<') + T('Int') + P('>\n\n') +
    C('// A relation is a @FK field typed as the entity it points to.\n') +
    K('data class ') + T('Movie') + P('(\n') +
    P('    ') + A('@PK') + P(' ') + K('val ') + P('id: ') + T('Int') + P(' = ') + N('0') + P(',\n') +
    P('    ') + K('val ') + P('title: ') + T('String') + P(',\n') +
    P('    ') + K('val ') + P('year: ') + T('Int') + P(',\n') +
    P('    ') + K('val ') + P('rating: ') + T('Double') + P(',\n') +
    P('    ') + A('@FK') + P(' ') + K('val ') + P('director: ') + T('Director') + P(',\n') +
    P(') : ') + T('Entity') + P('<') + T('Int') + P('>');

  // One DDL script per supported database (DATABASE_VARIANTS in the shared
  // theme), switchable in the editor title bar.
  const schemaFor = ({idType, idClause, strType, strLen, intType, dblType, inlineFk}) => {
    const strCol = (n) =>
      strLen ? T(strType) + P('(') + N(String(n)) + P(')') : T(strType);
    const fkLines = inlineFk
      ? P('    director_id  ') + T(idType) + P(' ') + K('REFERENCES') + P(' director(id)\n')
      : P('    director_id  ') + T(idType) + P(',\n') +
        P('    ') + K('FOREIGN KEY') + P(' (director_id) ') + K('REFERENCES') + P(' director(id)\n');
    return (
      C('-- create the tables with your migration tool, or run this once\n') +
      K('CREATE TABLE ') + P('director (\n') +
      P('    id    ') + T(idType) + P(' ') + K(idClause) + P(',\n') +
      P('    name  ') + strCol(100) + P('\n') +
      P(');\n\n') +
      K('CREATE TABLE ') + P('movie (\n') +
      P('    id           ') + T(idType) + P(' ') + K(idClause) + P(',\n') +
      P('    title        ') + strCol(200) + P(',\n') +
      P('    year         ') + T(intType) + P(',\n') +
      P('    rating       ') + T(dblType) + P(',\n') +
      fkLines +
      P(');')
    );
  };


  const query =
    C('// Open an ORM on any JDBC DataSource. Thread-safe; create it once.\n') +
    K('val ') + P('orm = dataSource.orm\n\n') +
    C('// Insert returns a copy with the generated id.\n') +
    K('val ') + P('nolan = orm ') + K('insert ') + T('Director') + P('(name = ') + S('"Christopher Nolan"') + P(')\n') +
    K('val ') + P('villeneuve = orm ') + K('insert ') + T('Director') + P('(name = ') + S('"Denis Villeneuve"') + P(')\n\n') +
    P('orm ') + K('insert ') + T('Movie') + P('(title = ') + S('"Interstellar"') + P(', year = ') + N('2014') + P(', rating = ') + N('8.7') + P(', director = nolan)\n') +
    P('orm ') + K('insert ') + T('Movie') + P('(title = ') + S('"Oppenheimer"') + P(', year = ') + N('2023') + P(', rating = ') + N('8.3') + P(', director = nolan)\n') +
    P('orm ') + K('insert ') + T('Movie') + P('(title = ') + S('"Dune: Part Two"') + P(', year = ') + N('2024') + P(', rating = ') + N('8.5') + P(', director = villeneuve)\n\n') +
    C('// A repository for Movie: every CRUD method included, nothing to implement.\n') +
    K('val ') + P('movies = orm.') + F('entity') + P('<') + T('Movie') + P('>()\n\n') +
    C('// One type-safe line across the relation. Movie_ is generated at compile time,\n') +
    C('// so a typo in the path fails to compile.\n') +
    K('val ') + P('byChris = movies.') + F('findAll') + P('(Movie_.director.name ') + K('like') + P(' ') + S('"Chris%"') + P(')\n\n') +
    C('// The director is loaded in the same query: no proxies, no N+1.\n') +
    K('val ') + P('director = byChris.first().director.name');

  const querySql =
    QC('-- orm insert Movie(...)\n') +
    QK('INSERT INTO') + ' movie (title, year, rating, director_id) ' + QK('VALUES') + ' (' + QQ('?') + ', ' + QQ('?') + ', ' + QQ('?') + ', ' + QQ('?') + ')\n\n' +
    QC('-- movies.findAll(Movie_.director.name like "Chris%")\n') +
    QC('-- one statement, join included: the whole graph, no N+1\n') +
    QK('SELECT') + ' m.id, m.title, m.year, m.rating, d.id, d.name\n' +
    QK('FROM') + ' movie m\n' +
    QK('INNER JOIN') + ' director d ' + QK('ON') + ' m.director_id = d.id\n' +
    QK('WHERE') + ' d.name ' + QK('LIKE') + ' ' + QQ('?');

  return `
${navHtml('')}

<div class="art">
  <div class="crumbs"><a href="/">Home</a><span class="sep">/</span>Quickstart</div>
  <h1>Zero to Storm<br><span class="grad">in 5 minutes.</span></h1>
  <p class="dek">Install Storm, define two linked entities as plain data classes, and query across the relation in one type-safe line. No persistence context, no proxies, no XML. Here is the whole path.</p>
  <div class="meta"><span>Kotlin</span><span>~5 min</span><span>JDK 21+</span></div>

  <h2><span class="hno">1</span>Set up</h2>
  <p>Apply the Storm Gradle plugin. It imports the BOM and wires the core dependencies, the metamodel processor, and the Kotlin compiler plugin, so all that is left for a runnable project is an in-memory H2 database.</p>
  ${editor({
    file: 'build.gradle.kts',
    tag: 'Gradle · Kotlin DSL',
    copy: true,
    variants: KOTLIN_VARIANTS.map((v) => ({label: v.label, code: installFor(v), selected: v.selected})),
  })}
  <p>The plugin generates the type-safe metamodel (<code>Movie_</code>) through KSP and makes SQL templates injection-safe by default through the compiler plugin, with no extra dependencies to declare. On a real database, swap <code>storm-h2</code> for your dialect and add its JDBC driver. See the <a class="tlink" href="/docs/installation">installation guide</a> for all options.</p>
  <p>Working with an AI coding assistant? One command, run from the root of your project's workspace, installs Storm-aware rules and skills for it (Claude, Cursor, Copilot, Windsurf, Codex) and sets up a schema-aware MCP server, so the entities and queries it generates match your real schema.</p>
  ${editor({file: 'terminal', tag: 'shell', code: cli, copy: cliCommand})}

  <h2><span class="hno">2</span>Define two linked entities</h2>
  <p>Entities are plain immutable data classes that implement <code>Entity&lt;ID&gt;</code>. Field names map to columns automatically (camelCase to snake_case), and a relation is just a <code>@FK</code> field typed as the entity it points to. There is no base class to extend and no generated draft type to route through: the classes you write are the objects you get back.</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin', code: entity, copy: true})}
  <p>Storm maps to an existing schema rather than creating one, so define the tables with your migration tool (Flyway, Liquibase) or a one-off DDL script; pick your database in the block below. Storm can verify at startup that your entities match it with <code>validateSchema()</code>.</p>
  ${editor({
    file: 'schema.sql',
    tag: 'SQL',
    copy: true,
    variants: DATABASE_VARIANTS.map((d) => ({label: d.label, code: schemaFor(d), selected: d.selected})),
  })}

  <h2><span class="hno">3</span>Query across the relation</h2>
  <p>Open an <code>ORMTemplate</code> on your <code>DataSource</code>, insert a few records, and filter movies by their director's name in one type-safe line. Toggle <b>Show SQL</b> to see exactly what Storm runs: a single statement with the join included, fully parameterized, no N+1.</p>
  ${editor({file: 'Main.kt', tag: 'Kotlin', code: query, sql: querySql, copy: true})}
  <p>That is the core loop. Insert, <code>findById</code>, <code>update</code>, and <code>remove</code> all come for free on the repository; add your own one-line queries whenever you need them.</p>

  <h2><span class="hno">4</span>Where to next</h2>
  <p>You have the whole shape of Storm in three steps. From here:</p>
  <div class="refs">
    <a href="/tutorials/build-a-rest-api">Build a REST API from scratch</a>
    <a href="/docs/first-query">First Query</a>
    <a href="/docs/entities">Entities</a>
    <a href="/examples/">Example apps</a>
    <a href="/comparison">How Storm compares</a>
  </div>

  <div class="cta">
    <a href="/tutorials/build-a-rest-api" class="btn primary">Build a real app →</a>
    <p class="starline">Something not work, or not make sense? Tell us on <a href="${DISCORD}" target="_blank" rel="noopener" class="grad star">Discord</a>.</p>
  </div>
</div>

${FOOT_HTML}
`;
}

export default function Quickstart() {
  const {siteConfig} = useDocusaurusContext();
  const version = siteConfig.customFields?.stormVersion || '0.0.0';
  useEffect(() => wireSqlToggles(), []);
  const url = 'https://orm.st/quickstart';
  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href={url} />
        <meta property="og:type" content="article" />
        <meta property="og:title" content={TITLE} />
        <meta property="og:description" content={DESC} />
        <meta name="twitter:title" content={TITLE} />
        <meta name="twitter:description" content={DESC} />
        <script type="application/ld+json">
          {JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'HowTo',
            name: 'Get started with Storm: your first query in five minutes',
            description: DESC,
            url,
            step: [
              {'@type': 'HowToStep', name: 'Set up', text: 'Use the Storm CLI, or add the Storm BOM and Kotlin modules to your build.'},
              {'@type': 'HowToStep', name: 'Define an entity', text: 'Write a plain Kotlin data class that implements Entity<ID>.'},
              {'@type': 'HowToStep', name: 'Run a query', text: 'Open an ORMTemplate on a DataSource and run a type-safe query.'},
            ],
          })}
        </script>
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap"
          rel="stylesheet"
        />
      </Head>
      <style dangerouslySetInnerHTML={{__html: TUT_CSS}} />
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: buildBody(version)}} />
    </>
  );
}
