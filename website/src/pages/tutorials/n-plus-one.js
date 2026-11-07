import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import {
  TUT_CSS, navHtml, FOOT_HTML, editor, wireSqlToggles,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: the N+1 problem, JPA vs Storm. Part of the "JPA to Storm" series:
// each page takes a familiar JPA task, shows the standard JPA approach next to
// the Storm approach, and lets the reader inspect the SQL both produce.

const TITLE = 'Solving the N+1 Problem: JPA vs Storm';
const DESC =
  'Why a JPA list query over a lazy relation runs 1 + N selects, what JOIN FETCH, ' +
  'entity graphs and batch fetching change, and how Storm decides loading in the ' +
  'entity model. With the SQL each approach runs.';

const CODE_JPA_ENTITY = [
  C('// Open (all-open plugin), mutable, nullable defaults: the shape Hibernate expects\n'),
  A('@Entity'), P('\n'),
  K('class '), T('User'), P('(\n'),
  P('    '), A('@Id'), P(' '), A('@GeneratedValue'), P('\n'),
  P('    '), K('var '), P('id: '), T('Int'), P('? = '), K('null'), P(',\n\n'),
  P('    '), K('var '), P('email: '), T('String'), P(' = '), S('""'), P(',\n'),
  P('    '), K('var '), P('name: '), T('String'), P(' = '), S('""'), P(',\n\n'),
  P('    '), A('@ManyToOne'), P('(fetch = '), T('FetchType'), P('.'), T('LAZY'), P(')\n'),
  P('    '), K('var '), P('city: '), T('City'), P('? = '), K('null'), P(',\n'),
  P(')'),
].join('');

const CODE_JPA_LOOP = [
  K('val '), P('users = userRepository.'), F('findAll'), P('()   '), C('// 1 query\n'),
  K('for'), P(' (user '), K('in'), P(' users) {\n'),
  P('    '), F('render'), P('(user.city!!.name)         '), C('// + 1 query per uninitialized proxy\n'),
  P('}'),
].join('');

const SQL_JPA_LOOP = [
  QC('-- findAll(): one query for the users ...'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name, u.city_id ', QK('FROM'), ' user u\n\n',
  QC('-- ... then one more per city while rendering'), '\n',
  QK('SELECT'), ' c.id, c.name ', QK('FROM'), ' city c ', QK('WHERE'), ' c.id = ', QQ('?'), '\n',
  QK('SELECT'), ' c.id, c.name ', QK('FROM'), ' city c ', QK('WHERE'), ' c.id = ', QQ('?'), '\n',
  QK('SELECT'), ' c.id, c.name ', QK('FROM'), ' city c ', QK('WHERE'), ' c.id = ', QQ('?'), '\n',
  QC('-- 50 users in 50 cities: 51 round trips'),
].join('');

const CODE_JPA_FIXES = [
  K('interface '), T('UserRepository'), P(' : '), T('JpaRepository'), P('<'), T('User'), P(', '), T('Int'), P('> {\n\n'),
  C('    // Fix 1: JOIN FETCH, applied one JPQL query at a time\n'),
  P('    '), A('@Query'), P('('), S('"SELECT u FROM User u JOIN FETCH u.city"'), P(')\n'),
  P('    '), K('fun '), F('findAllWithCity'), P('(): '), T('List'), P('<'), T('User'), P('>\n\n'),
  C('    // Fix 2: an entity graph, applied one call site at a time\n'),
  P('    '), A('@EntityGraph'), P('(attributePaths = ['), S('"city"'), P('])\n'),
  P('    '), K('override fun '), F('findAll'), P('(): '), T('List'), P('<'), T('User'), P('>\n'),
  P('}'),
].join('');

const CODE_STORM_ENTITIES = [
  C('// The field type decides what every read of User loads\n'),
  K('data class '), T('City'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('data class '), T('User'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('email: '), T('String'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('city: '), T('City'), P(',   '), C('// entity field: joined in the same query\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>'),
].join('');

const CODE_STORM_LOOP = [
  K('val '), P('users = userRepository.'), F('findAll'), P('()   '), C('// 1 query, cities included\n'),
  K('for'), P(' (user '), K('in'), P(' users) {\n'),
  P('    '), F('render'), P('(user.city.name)             '), C('// plain field access, no database call\n'),
  P('}'),
].join('');

const SQL_STORM_LOOP = [
  QC('-- findAll() loads the city graph in the same round trip'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name, c.id, c.name\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QC('-- 50 users in 50 cities: 1 round trip'),
].join('');

const CODE_STORM_REF = [
  K('data class '), T('User'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('email: '), T('String'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('city: '), T('Ref'), P('<'), T('City'), P('>,   '), C('// Ref field: reads city_id, joins nothing\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('val '), P('user = userRepository.'), F('getById'), P('('), N('1'), P(')   '), C('// no join issued\n'),
  K('val '), P('city = user.city.'), F('fetch'), P('()           '), C('// loads the city here, one query'),
].join('');

const CODE_STORM_FETCH_PLAN = [
  C('// A query that needs the cities resolves them in the same statement\n'),
  K('val '), P('users = orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(').'), F('select'), P('()\n'),
  P('    .'), F('fetch'), P('('), T('User_'), P('.city)\n'),
  P('    .resultList\n\n'),
  K('val '), P('city = users.'), F('first'), P('().city.'), F('getOrThrow'), P('()   '), C('// loaded by the query, never queries'),
].join('');

const SQL_STORM_FETCH_PLAN = [
  QC('-- the ref field is unchanged; this query joins the city'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name, c.id, c.name\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id',
].join('');

const SQL_STORM_REF = [
  QC('-- getById(1): no join, the ref holds the foreign key'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name, u.city_id ', QK('FROM'), ' "user" u ', QK('WHERE'), ' u.id = ', QQ('?'), '\n\n',
  QC('-- fetch(): runs only where your code says so'), '\n',
  QK('SELECT'), ' c.id, c.name ', QK('FROM'), ' city c ', QK('WHERE'), ' c.id = ', QQ('?'),
].join('');

const CODE_TEST = [
  A('@StormTest'), P('(scripts = ['), S('"/schema.sql"'), P(', '), S('"/data.sql"'), P('])\n'),
  K('class '), T('UserQueryTest'), P(' {\n\n'),
  P('    '), A('@Test'), P('\n'),
  P('    '), K('fun '), F('`users and cities load in a single query`'), P('(orm: '), T('ORMTemplate'), P(', capture: '), T('SqlCapture'), P(') {\n'),
  P('        '), K('val '), P('users = capture.'), F('execute'), P(' { orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(').'), F('findAll'), P('() }\n\n'),
  P('        users.'), F('forEach'), P(' { '), F('render'), P('(it.city.name) }   '), C('// walk the graph freely\n'),
  P('        capture.'), F('count'), P('('), T('Operation'), P('.'), T('SELECT'), P(') '), K('shouldBe'), P(' '), N('1'), P('\n'),
  P('    }\n'),
  P('}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Solving the N+1 problem</div>
  <h1>Solving the <span class="grad">N+1 problem</span></h1>
  <p class="dek">A list query over a lazy relation runs one extra select per row. This page shows where that comes from in JPA, what the standard fixes change, and how Storm decides loading in the entity model, with the SQL each approach runs.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>7 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Render a list of 50 users with the name of the city each user lives in. Two tables, one foreign key, no aggregation. One query can answer it. With a lazy relation in JPA, the code below runs 51.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>A typical JPA mapping marks the relation <code>LAZY</code>, so loading a user does not always load its city. In Kotlin the class is open (through the all-open compiler plugin), mutable, with nullable defaults, and a plain class rather than a <code>data class</code>:</p>
  ${editor({file: 'User.kt', tag: 'Kotlin · JPA', code: CODE_JPA_ENTITY})}
  <p>The repository call and the rendering loop:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · JPA', code: CODE_JPA_LOOP, sql: SQL_JPA_LOOP})}
  <p>The list query fetches users only. Each <code>user.city</code> holds a proxy, and the first access to <code>name</code> on each proxy runs its own SELECT. The code and the types look the same either way; the query log shows the difference. Switching to <code>EAGER</code> does not help the list case: a JPQL list query still fetches each city with a follow-up select, only earlier. A proxy first touched after the session has closed throws <code>LazyInitializationException</code>.</p>

  <h2><span class="hno">03</span>The standard fixes</h2>
  <p>JPA has two well-known remedies, and they work:</p>
  ${editor({file: 'UserRepository.kt', tag: 'Kotlin · JPA', code: CODE_JPA_FIXES})}
  <p>Hibernate adds batch fetching: <code>@BatchSize</code> on the relation, or <code>hibernate.default_batch_fetch_size</code> for the whole application, loads the proxies in groups with an <code>IN</code> list, so 50 cities take a few queries instead of 50.</p>
  <p>JOIN FETCH and entity graphs are written per query or per call site. The entity declares one loading behavior and each query may override it, so a new query that forgets the fetch clause brings the extra selects back. Batch fetching reduces their number but keeps the separate round trips.</p>
  <div class="note">The JPA snippets follow Baeldung's <a href="https://www.baeldung.com/spring-hibernate-n1-problem" rel="noopener">guide to the N+1 problem</a>. Within JPA, JOIN FETCH and entity graphs are the right fixes.</div>

  <h2><span class="hno">04</span>The Storm way</h2>
  <p>Storm decides loading in the model. A foreign key field declared with the entity type is loaded in the same query, through a join. There is no lazy variant of that field type:</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin · Storm', code: CODE_STORM_ENTITIES})}
  <p>The same list-and-render code runs one query for every caller:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_LOOP, sql: SQL_STORM_LOOP})}
  <p>The result is a list of immutable data classes. <code>user.city</code> is a field holding a <code>City</code>, not a proxy with a session reference. You can serialize it, cache it or pass it to another thread. Storm has no session, so there is no <code>LazyInitializationException</code>.</p>
  <p>The join repeats the city columns on every row, but the result does not repeat the objects. Entities with the same id in one result share one instance: 50 users in 5 cities <a class="tlink" href="/docs/hydration">hydrate</a> into 5 <code>City</code> objects. Sharing is safe because the objects are immutable.</p>

  <h2><span class="hno">05</span>When you do not want the join</h2>
  <p>Some readers never need the city, and joining it for them is wasted work. In Storm that choice is a field type. Declare the field as <code>Ref&lt;City&gt;</code> and Storm reads only the foreign key column. Loading the city is an explicit call:</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin · Storm', code: CODE_STORM_REF, sql: SQL_STORM_REF})}
  <p><code>City</code> means loaded, <code>Ref&lt;City&gt;</code> means not loaded. A loop that calls <code>fetch()</code> per row is an N+1, and the call is in the code where a reviewer sees it. A <code>Ref</code> fetched twice queries once, and inside a transaction <code>fetch()</code> first checks the <a class="tlink" href="/docs/entity-cache">entity cache</a>, so a city an earlier read already loaded usually costs no query.</p>
  <p>A query whose caller does need the cities says so with <code>fetch(...)</code>. The field stays a <code>Ref</code>, and the refs come back loaded from the same statement:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_FETCH_PLAN, sql: SQL_STORM_FETCH_PLAN})}
  <p>Read a resolved ref with <code>getOrThrow()</code>. It never queries, so if someone removes the <code>fetch(...)</code>, the code fails at that line instead of turning into one query per row. In development, Storm also reports the loop itself: a transaction that fetches ten or more references of one type one at a time draws <code>repeated-fetch</code> advice in the log, with the call site and the time the fetches took. See <a class="tlink" href="/tutorials/advice">Statement advice</a>.</p>

  <h2><span class="hno">06</span>Prove it in a test</h2>
  <p>A query count is something a test can assert. Storm's test module captures the SQL a block runs, so a change that adds a query fails the build:</p>
  ${editor({file: 'UserQueryTest.kt', tag: 'Kotlin · storm-test', code: CODE_TEST})}
  <p><code>@StormTest</code> starts an in-memory H2 database, runs your schema scripts and injects the <code>ORMTemplate</code> and <code>SqlCapture</code> parameters. For <code>Ref</code> fields, <code>capture.count(CapturedSql.Origin.FETCH)</code> counts the statements that resolved a reference one at a time. See <a class="tlink" href="/docs/testing">Testing</a> for the full setup, including running the same test against PostgreSQL in a container.</p>

  <h2><span class="hno">07</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>JPA with Hibernate</th><th>Storm</th></tr>
    <tr><td>Where loading is decided</td><td>On the mapping, overridden per query (JOIN FETCH, entity graphs), or at access time through proxies</td><td>In the entity model: a <code>City</code> field joins, a <code>Ref&lt;City&gt;</code> field does not; <code>fetch(...)</code> resolves a ref in one query</td></tr>
    <tr><td>Lazy relation in a loop</td><td>1&nbsp;+&nbsp;N selects, fewer with batch fetching</td><td>Extra queries only where <code>fetch()</code> is written</td></tr>
    <tr><td>Outside a transaction</td><td><code>LazyInitializationException</code> when an untouched proxy outlives its session</td><td>Results are plain values; a ref fetches over its template's connection</td></tr>
    <tr><td>Finding an N+1</td><td>Query log, Hibernate statistics, or a statement-count check in tests</td><td><code>repeated-fetch</code> advice in development; <code>SqlCapture</code> counts in tests</td></tr>
  </table>

  <h2><span class="hno">08</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/refs">Refs</a>
    <a href="/docs/relationships">Relationships</a>
    <a href="/docs/entity-design">Entity Design</a>
    <a href="/docs/advice">Statement Advice</a>
    <a href="/docs/hydration">Hydration</a>
    <a href="/docs/testing">Testing</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function NPlusOneTutorial() {
  useEffect(() => wireSqlToggles(), []);

  return (
    <>
      <Head>
        <html lang="en" />
        <title>{`${TITLE} · ST/ORM Tutorials`}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href="https://orm.st/tutorials/n-plus-one" />
        <meta property="og:type" content="article" />
        <meta property="og:title" content={TITLE} />
        <meta property="og:description" content={DESC} />
        <meta name="twitter:title" content={TITLE} />
        <meta name="twitter:description" content={DESC} />
        <script type="application/ld+json">
          {JSON.stringify({
            '@context': 'https://schema.org',
            '@type': 'TechArticle',
            headline: TITLE,
            description: DESC,
            url: 'https://orm.st/tutorials/n-plus-one',
            proficiencyLevel: 'Beginner',
            author: {
              '@type': 'Organization',
              name: 'Storm',
              url: 'https://github.com/storm-orm',
            },
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
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: BODY}} />
    </>
  );
}
