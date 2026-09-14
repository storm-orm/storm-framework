import React from 'react';
import useDocusaurusContext from '@docusaurus/useDocusaurusContext';
import {
  TutorialPage,
  navHtml,
  FOOT_HTML,
  editor,
  KOTLIN_VARIANTS,
  DATABASE_VARIANTS,
  K, T, S, C, F, N, A, P, QK, QC,
} from '../../components/tutorial/tutorialTheme';

// End-to-end tutorial: build a small but complete REST API from an empty
// project to running endpoints and a test. Kotlin + Ktor (the lightest way to
// show the whole loop). Lives as a website tutorial page so it deploys live
// immediately and the /quickstart "Build a real app" button can link to it.
// The install snippet renders the resolved release version from customFields.

const TITLE = 'Build a REST API from scratch';
const DESC =
  'From an empty project to a running, tested REST API with Storm and Ktor: ' +
  'model two entities, load their relationship in a single query, expose CRUD ' +
  'routes, and assert the SQL with a test.';

function buildBody(version) {
  // The Storm plugin applies its bundled KSP itself, so only the Kotlin-paired
  // lines (kspRequired in KOTLIN_VARIANTS) declare KSP.
  const gradleFor = ({kotlin, ksp, kspRequired}) =>
    C('// build.gradle.kts\n') +
    F('plugins') + P(' {\n') +
    P('    ') + F('kotlin') + P('(') + S('"jvm"') + P(') version ') + S(`"${kotlin}"`) + P('\n') +
    P('    ') + F('id') + P('(') + S('"io.ktor.plugin"') + P(') version ') + S('"3.4.3"') + P('\n') +
    (kspRequired
      ? P('    ') + F('id') + P('(') + S('"com.google.devtools.ksp"') + P(') version ') + S(`"${ksp}"`) + P('\n')
      : '') +
    P('    ') + F('id') + P('(') + S('"st.orm"') + P(') version ') + S(`"${version}"`) + P('\n') +
    P('}\n\n') +
    F('dependencies') + P(' {\n') +
    P('    ') + F('implementation') + P('(') + S('"st.orm:storm-ktor"') + P(')\n') +
    P('    ') + F('runtimeOnly') + P('(') + S('"st.orm:storm-h2"') + P(')\n') +
    P('    ') + F('runtimeOnly') + P('(') + S('"com.h2database:h2:2.3.232"') + P(')\n\n') +
    P('    ') + F('implementation') + P('(') + S('"io.ktor:ktor-server-netty"') + P(')\n') +
    P('    ') + F('implementation') + P('(') + S('"io.ktor:ktor-server-content-negotiation"') + P(')\n') +
    P('    ') + F('implementation') + P('(') + S('"io.ktor:ktor-serialization-jackson"') + P(')\n') +
    P('    ') + F('implementation') + P('(') + S('"st.orm:storm-jackson2"') + P(')\n\n') +
    P('    ') + F('testImplementation') + P('(') + S('"st.orm:storm-ktor-test"') + P(')\n') +
    P('    ') + F('testImplementation') + P('(') + S('"com.h2database:h2"') + P(')\n') +
    P('}');

  // One DDL script per supported database (DATABASE_VARIANTS in the shared
  // theme). The tutorial itself runs on H2, the default.
  const schemaFor = ({idType, idClause, strType, strLen, inlineFk}) => {
    const strCol = (n) =>
      strLen ? T(strType) + P('(') + N(String(n)) + P(')') : T(strType);
    const fkLines = inlineFk
      ? P('    folder_id  ') + T(idType) + P(' ') + K('NOT NULL REFERENCES') + P(' folder(id)\n')
      : P('    folder_id  ') + T(idType) + P(' ') + K('NOT NULL') + P(',\n') +
        P('    ') + K('FOREIGN KEY') + P(' (folder_id) ') + K('REFERENCES') + P(' folder(id)\n');
    return (
      C('-- src/main/resources/schema.sql\n') +
      K('CREATE TABLE ') + P('folder (\n') +
      P('    id    ') + T(idType) + P(' ') + K(idClause) + P(',\n') +
      P('    name  ') + strCol(100) + P(' ') + K('NOT NULL') + P('\n') +
      P(');\n') +
      K('CREATE TABLE ') + P('bookmark (\n') +
      P('    id         ') + T(idType) + P(' ') + K(idClause) + P(',\n') +
      P('    url        ') + strCol(2000) + P(' ') + K('NOT NULL') + P(',\n') +
      P('    title      ') + strCol(200) + P(' ') + K('NOT NULL') + P(',\n') +
      fkLines +
      P(');\n') +
      C('-- one folder to start with\n') +
      K('INSERT INTO ') + P('folder (name) ') + K('VALUES') + P(' (') + S("'Reading'") + P(');')
    );
  };

  const entities =
    C('// Folder.kt\n') +
    K('data class ') + T('Folder') + P('(\n') +
    P('    ') + A('@PK') + P(' ') + K('val ') + P('id: ') + T('Int') + P(' = ') + N('0') + P(',\n') +
    P('    ') + K('val ') + P('name: ') + T('String') + P(',\n') +
    P(') : ') + T('Entity') + P('<') + T('Int') + P('>\n\n') +
    C('// Bookmark.kt: @FK means the folder is loaded in the same query\n') +
    K('data class ') + T('Bookmark') + P('(\n') +
    P('    ') + A('@PK') + P(' ') + K('val ') + P('id: ') + T('Int') + P(' = ') + N('0') + P(',\n') +
    P('    ') + K('val ') + P('url: ') + T('String') + P(',\n') +
    P('    ') + K('val ') + P('title: ') + T('String') + P(',\n') +
    P('    ') + A('@FK') + P(' ') + K('val ') + P('folder: ') + T('Folder') + P(',\n') +
    P(') : ') + T('Entity') + P('<') + T('Int') + P('>');

  const conf =
    C('# src/main/resources/application.conf\n') +
    P('storm {\n') +
    P('    datasource {\n') +
    P('        jdbcUrl = ') + S("\"jdbc:h2:mem:bookmarks;DB_CLOSE_DELAY=-1;INIT=RUNSCRIPT FROM 'classpath:schema.sql'\"") + P('\n') +
    P('        username = ') + S('"sa"') + P('\n') +
    P('        password = ') + S('""') + P('\n') +
    P('    }\n') +
    P('    validation { schemaMode = ') + S('"warn"') + P(' }') + P('   ') + C('# log any entity/schema mismatch\n') +
    P('}');

  const repo =
    C('// Repositories.kt: CRUD is inherited; add only your own queries\n') +
    K('interface ') + T('BookmarkRepository') + P(' : ') + T('EntityRepository') + P('<') + T('Bookmark') + P(', ') + T('Int') + P('> {\n') +
    P('    ') + K('fun ') + F('findByFolderName') + P('(name: ') + T('String') + P('): ') + T('List') + P('<') + T('Bookmark') + P('> =\n') +
    P('        ') + F('findAll') + P('(Bookmark_.folder.name ') + K('eq') + P(' name)\n') +
    P('}\n\n') +
    C('// the minimal case (optional): one line, nothing to implement\n') +
    K('interface ') + T('FolderRepository') + P(' : ') + T('EntityRepository') + P('<') + T('Folder') + P(', ') + T('Int') + P('>');

  const app =
    C('// Application.kt\n') +
    K('fun ') + F('main') + P('() {\n') +
    P('    ') + F('embeddedServer') + P('(') + T('Netty') + P(', port = ') + N('8080') + P(') { ') + F('module') + P('() }.') + F('start') + P('(wait = ') + K('true') + P(')\n') +
    P('}\n\n') +
    K('fun ') + T('Application') + P('.') + F('module') + P('() {\n') +
    P('    ') + F('install') + P('(') + T('Storm') + P(')') + P('                     ') + C('// reads storm.datasource; auto-registers repositories\n') +
    P('    ') + F('install') + P('(') + T('ContentNegotiation') + P(') {\n') +
    P('        ') + F('jackson') + P(' { ') + F('registerModule') + P('(') + T('StormModule') + P('()) }\n') +
    P('    }\n') +
    P('    ') + F('routing') + P(' { ') + F('bookmarkRoutes') + P('() }\n') +
    P('}');

  const routes =
    C('// Routes.kt\n') +
    A('@JsonIgnoreProperties') + P('(ignoreUnknown = ') + K('true') + P(')\n') +
    K('data class ') + T('NewBookmark') + P('(') + K('val ') + P('url: ') + T('String') + P(', ') + K('val ') + P('title: ') + T('String') + P(', ') + K('val ') + P('folderId: ') + T('Int') + P(')\n\n') +
    K('fun ') + T('Route') + P('.') + F('bookmarkRoutes') + P('() {\n') +
    P('    ') + F('get') + P('(') + S('"/bookmarks"') + P(') {\n') +
    P('        call.') + F('respond') + P('(') + F('repository') + P('<') + T('BookmarkRepository') + P('>().') + F('findAll') + P('())\n') +
    P('    }\n\n') +
    P('    ') + F('get') + P('(') + S('"/bookmarks/{id}"') + P(') {\n') +
    P('        ') + K('val ') + P('id = call.parameters.') + F('getOrFail') + P('(') + S('"id"') + P(').') + F('toInt') + P('()\n') +
    P('        ') + K('val ') + P('bookmark = ') + F('repository') + P('<') + T('BookmarkRepository') + P('>().') + F('findById') + P('(id)\n') +
    P('        call.') + F('respond') + P('(bookmark ?: ') + T('HttpStatusCode') + P('.NotFound)\n') +
    P('    }\n\n') +
    P('    ') + F('post') + P('(') + S('"/bookmarks"') + P(') {\n') +
    P('        ') + K('val ') + P('body = call.') + F('receive') + P('<') + T('NewBookmark') + P('>()\n') +
    P('        ') + K('val ') + P('folder = ') + F('repository') + P('<') + T('FolderRepository') + P('>().') + F('findById') + P('(body.folderId)\n') +
    P('        ') + K('if ') + P('(folder == ') + K('null') + P(') { call.') + F('respond') + P('(') + T('HttpStatusCode') + P('.BadRequest, ') + S('"unknown folder"') + P('); ') + K('return@post') + P(' }\n') +
    P('        ') + K('val ') + P('created = ') + F('transaction') + P(' {\n') +
    P('            ') + F('repository') + P('<') + T('BookmarkRepository') + P('>()\n') +
    P('                .') + F('insertAndFetch') + P('(') + T('Bookmark') + P('(url = body.url, title = body.title, folder = folder))\n') +
    P('        }\n') +
    P('        call.') + F('respond') + P('(') + T('HttpStatusCode') + P('.Created, created)\n') +
    P('    }\n\n') +
    P('    ') + F('delete') + P('(') + S('"/bookmarks/{id}"') + P(') {\n') +
    P('        ') + K('val ') + P('id = call.parameters.') + F('getOrFail') + P('(') + S('"id"') + P(').') + F('toInt') + P('()\n') +
    P('        ') + F('transaction') + P(' { ') + F('repository') + P('<') + T('BookmarkRepository') + P('>().') + F('removeById') + P('(id) }\n') +
    P('        call.') + F('respond') + P('(') + T('HttpStatusCode') + P('.NoContent)\n') +
    P('    }\n') +
    P('}');

  const listCode =
    C('// GET /bookmarks returns every bookmark, each with its folder\n') +
    F('repository') + P('<') + T('BookmarkRepository') + P('>().') + F('findAll') + P('()');
  const listSql =
    QC('-- one query, no N+1: the folder is joined in\n') +
    QK('SELECT') + ' b.id, b.url, b.title, f.id, f.name\n' +
    QK('FROM') + ' bookmark b\n' +
    QK('INNER JOIN') + ' folder f ' + QK('ON') + ' b.folder_id = f.id';

  const run =
    C('# start the app\n') +
    P('./gradlew run\n\n') +
    C('# create a bookmark in folder 1\n') +
    P('curl -s -X POST localhost:8080/bookmarks \\\n') +
    P("  -H 'content-type: application/json' \\\n") +
    P('  -d ') + S('\'{"url":"https://orm.st","title":"Storm","folderId":1}\'') + P('\n\n') +
    C('# list them back: each bookmark includes its folder object\n') +
    P('curl -s localhost:8080/bookmarks');

  const test =
    C('// BookmarkRoutesTest.kt\n') +
    A('@Test') + P('\n') +
    K('fun ') + P('`POST creates a bookmark with a single insert`() = ') + F('testStormApplication') + P('(\n') +
    P('    scripts = ') + F('listOf') + P('(') + S('"/schema.sql"') + P('),\n') +
    P(') { scope ->\n') +
    P('    ') + F('application') + P(' {\n') +
    P('        ') + F('install') + P('(') + T('Storm') + P(') { dataSource = scope.stormDataSource }\n') +
    P('        ') + F('install') + P('(') + T('ContentNegotiation') + P(') { ') + F('jackson') + P(' { ') + F('registerModule') + P('(') + T('StormModule') + P('()) } }\n') +
    P('        ') + F('routing') + P(' { ') + F('bookmarkRoutes') + P('() }\n') +
    P('    }\n\n') +
    P('    scope.stormSqlCapture.') + F('run') + P(' {\n') +
    P('        client.') + F('post') + P('(') + S('"/bookmarks"') + P(') {\n') +
    P('            ') + F('contentType') + P('(') + T('ContentType') + P('.Application.Json)\n') +
    P('            ') + F('setBody') + P('(') + S('"""{"url":"https://orm.st","title":"Storm","folderId":1}"""') + P(')\n') +
    P('        }\n') +
    P('    }\n') +
    P('    ') + F('assertEquals') + P('(') + N('1') + P(', scope.stormSqlCapture.') + F('count') + P('(') + T('Operation') + P('.INSERT))\n') +
    P('}');

  return `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/">Home</a><span class="sep">/</span><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Build a REST API</div>
  <h1>Build a REST API<br><span class="grad">from scratch.</span></h1>
  <p class="dek">Start with an empty project and finish with a running, tested REST API: two entities, their relationship loaded in a single query, CRUD routes over Ktor, and a test that asserts the exact SQL. About twenty minutes end to end.</p>
  <div class="meta"><span>Kotlin</span><span>Ktor 3</span><span>~20 min</span></div>

  <div class="note">New to Storm? The <a href="/quickstart">five-minute quickstart</a> covers just install, entity, and query. This tutorial builds the whole app around them.</div>

  <h2><span class="hno">1</span>Create the project</h2>
  <p>Start a plain Kotlin/Gradle project and add Ktor and Storm. The Ktor plugin manages the Ktor artifact versions, and the Storm Gradle plugin imports Storm's BOM and wires the metamodel processor and compiler plugin, so most dependencies need no version. We use H2 so there is nothing to install.</p>
  ${editor({
    file: 'build.gradle.kts',
    tag: 'Gradle · Kotlin DSL',
    copy: true,
    variants: KOTLIN_VARIANTS.map((v) => ({label: v.label, code: gradleFor(v), selected: v.selected})),
  })}
  <p>Assembling it by hand is the point of this tutorial. When you are starting real work instead, the <a class="tlink" href="/templates/kotlin-ktor">Ktor starter template</a> is this project already assembled: the same plugin block, two entities with their migration, a repository query, a service, two endpoints and their tests. Press <b>Use this template</b> on GitHub and it runs on H2 before you change a line.</p>

  <h2><span class="hno">2</span>Create the schema</h2>
  <p>Storm maps to an existing schema rather than generating one, which keeps migrations under your control. For this tutorial a small script is enough; H2 runs it on startup, so there is no migration tool to set up yet. On another database, pick it in the block below.</p>
  ${editor({
    file: 'schema.sql',
    tag: 'SQL',
    copy: true,
    variants: DATABASE_VARIANTS.map((d) => ({label: d.label, code: schemaFor(d), selected: d.selected})),
  })}

  <h2><span class="hno">3</span>Model the domain</h2>
  <p>Two immutable data classes. A <code>Bookmark</code> belongs to a <code>Folder</code>; marking that reference <code>@FK</code> is the whole relationship. Field names map to columns automatically, so <code>folder</code> becomes the <code>folder_id</code> column.</p>
  ${editor({file: 'model.kt', tag: 'Kotlin', code: entities, copy: true})}

  <h2><span class="hno">4</span>Point Storm at the database</h2>
  <p>The Storm Ktor plugin reads its DataSource from <code>application.conf</code>. No wiring code: <code>install(Storm)</code> builds a connection pool and, because the metamodel processor already indexed them, registers your repositories.</p>
  ${editor({file: 'application.conf', tag: 'HOCON', code: conf, copy: true})}

  <h2><span class="hno">5</span>Add a repository</h2>
  <p>Extend <code>EntityRepository</code> and every CRUD method comes for free. Add your own one-line queries on top; <code>Bookmark_</code> is the compile-time metamodel, so a typo in a field name fails to compile. <code>FolderRepository</code> shows the minimal case: one line, nothing to implement.</p>
  ${editor({file: 'Repositories.kt', tag: 'Kotlin', code: repo, copy: true})}

  <h2><span class="hno">6</span>Wire up the application</h2>
  <p>A standard Ktor <code>main</code> and module. Install Storm, register Storm's Jackson module so entities serialize cleanly, and mount the routes.</p>
  ${editor({file: 'Application.kt', tag: 'Kotlin', code: app, copy: true})}

  <h2><span class="hno">7</span>Write the routes</h2>
  <p>Read straight from the ORM, write inside <code>transaction { }</code>. Because Ktor and Storm are both coroutine-based, the transaction rides the request's coroutine with no proxies or annotations. <code>repository&lt;T&gt;()</code> is an extension available right in the handler.</p>
  ${editor({file: 'Routes.kt', tag: 'Kotlin', code: routes, copy: true})}
  <p>The list endpoint is where Storm earns its keep. One call, one query: the folder for every bookmark is joined in, so there is no N+1 to discover later. Toggle <b>Show SQL</b> to see exactly what runs.</p>
  ${editor({file: 'BookmarkRepository.kt', tag: 'Kotlin', code: listCode, sql: listSql, copy: true})}

  <h2><span class="hno">8</span>Run it</h2>
  <p>Start the server and exercise it with curl. The created bookmark comes back with its full folder object, loaded in the same query that fetched the bookmark.</p>
  ${editor({file: 'terminal', tag: 'shell', code: run, copy: true})}

  <h2><span class="hno">9</span>Test it</h2>
  <p><code>storm-ktor-test</code> spins up an in-memory database from your schema script and captures the SQL. Here we assert the create path is a single INSERT, so an accidental N+1 or extra round-trip fails the build rather than slipping into production.</p>
  ${editor({file: 'BookmarkRoutesTest.kt', tag: 'Kotlin · test', code: test, copy: true})}

  <h2><span class="hno">✓</span>You built it</h2>
  <p>An empty folder to a running, tested API: two entities, a joined relationship, four routes, and a repository, with no persistence context, no proxies, and no N+1. From here:</p>
  <div class="refs">
    <a href="/docs/relationships">Relationships</a>
    <a href="/docs/repositories">Repositories</a>
    <a href="/docs/ktor-integration">Ktor integration</a>
    <a href="/docs/testing">Testing</a>
    <a href="/templates/kotlin-ktor">The Ktor starter template</a>
    <a href="/examples/kotlin-ktor">The full Ktor example app</a>
  </div>

  <div class="cta">
    <a href="/examples/" class="btn primary">See full example apps →</a>
    <a href="/docs/" class="btn">Read the docs</a>
  </div>
</div>

${FOOT_HTML}`;
}

export default function BuildARestApi() {
  const {siteConfig} = useDocusaurusContext();
  const version = siteConfig.customFields?.stormVersion || '0.0.0';
  return (
    <TutorialPage
      title={TITLE}
      description={DESC}
      slug="build-a-rest-api"
      body={buildBody(version)}
    />
  );
}
