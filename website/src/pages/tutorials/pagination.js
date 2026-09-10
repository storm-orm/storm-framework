import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: pagination and keyset scrolling, JPA vs Storm.

const TITLE = 'Pagination and Keyset Scrolling: JPA vs Storm';
const DESC =
  'Offset pagination degrades on deep pages and runs a count query per request. ' +
  'Storm offers classic pages when you want them and keyset scrolling with ' +
  'typed keys and REST-ready cursors when you need to scale.';

const CODE_JPA_PAGE = [
  K('val '), P('page = userRepository.'), F('findAll'), P('(\n'),
  P('    '), T('PageRequest'), P('.'), F('of'), P('('), N('0'), P(', '), N('10'), P(', '), T('Sort'), P('.'), F('by'), P('('), S('"createdAt"'), P('))   '), C('// the sort column is a string\n'),
  P(')\n\n'),
  K('val '), P('users = page.content\n'),
  K('val '), P('total = page.totalElements   '), C('// a COUNT(*) runs on every request'),
].join('');

const SQL_JPA_PAGE = [
  QC('-- every page issues two queries'), '\n',
  QK('SELECT COUNT'), '(*) ', QK('FROM'), ' user u\n',
  QK('SELECT'), ' u.* ', QK('FROM'), ' user u ', QK('ORDER BY'), ' u.created_at ', QK('LIMIT'), ' ', QQ('10'), ' ', QK('OFFSET'), ' ', QQ('?'), '\n\n',
  QC('-- page 5,000: the database walks and discards 50,000 rows to reach it'),
].join('');

const CODE_STORM_PAGE = [
  K('val '), P('pageable = '), T('Pageable'), P('.'), F('ofSize'), P('('), N('10'), P(').'), F('sortBy'), P('('), T('User_'), P('.createdAt)   '), C('// type-checked sort\n\n'),
  K('val '), P('page = orm.'), F('entity'), P('<'), T('User'), P('>().'), F('select'), P('()\n'),
  P('    .'), F('where'), P('('), T('User_'), P('.city, '), T('EQUALS'), P(', city)\n'),
  P('    .'), F('page'), P('(pageable)\n\n'),
  P('page.content          '), C('// the rows\n'),
  P('page.totalCount       '), C('// total matches\n'),
  P('page.'), F('totalPages'), P('()     '), C('// computed\n'),
  P('page.'), F('next'), P('()           '), C('// sort orders carry over'),
].join('');

const SQL_STORM_PAGE = [
  QC('-- page(): a count and a data query, classic offset pagination'), '\n',
  QK('SELECT COUNT'), '(*) ', QK('FROM'), ' "user" u ', QK('WHERE'), ' u.city_id = ', QQ('?'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name ', QK('FROM'), ' "user" u\n',
  QK('WHERE'), ' u.city_id = ', QQ('?'), ' ', QK('ORDER BY'), ' u.created_at ', QK('LIMIT'), ' ', QQ('10'), ' ', QK('OFFSET'), ' ', QQ('?'),
].join('');

const CODE_STORM_SCROLL = [
  K('val '), P('users = orm.'), F('entity'), P('<'), T('User'), P('>()\n\n'),
  C('// First window: ordered by the key, no offset anywhere\n'),
  K('val '), P('first = users.'), F('select'), P('().'), F('scroll'), P('('), T('Scrollable'), P('.'), F('of'), P('('), T('User_'), P('.id, '), N('10'), P('))\n'),
  F('render'), P('(first.'), F('content'), P('())\n\n'),
  C('// Next window: seeks straight to the cursor position\n'),
  P('first.'), F('next'), P('()?.'), F('let'), P(' { request ->\n'),
  P('    '), K('val '), P('second = users.'), F('select'), P('().'), F('scroll'), P('(request)\n'),
  P('}'),
].join('');

const SQL_STORM_SCROLL = [
  QC('-- first window: one extra row decides hasNext, then is discarded'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name ', QK('FROM'), ' "user" u ', QK('ORDER BY'), ' u.id ', QK('LIMIT'), ' ', QQ('11'), '\n\n',
  QC('-- next window: an index seek, not a scan'), '\n',
  QK('SELECT'), ' u.id, u.email, u.name ', QK('FROM'), ' "user" u\n',
  QK('WHERE'), ' u.id > ', QQ('?'), ' ', QK('ORDER BY'), ' u.id ', QK('LIMIT'), ' ', QQ('11'), '\n',
  QC('-- window 5,000 costs the same as window 1'),
].join('');

const CODE_STORM_CURSOR = [
  C('// Serialize the position into an opaque string for the client\n'),
  K('val '), P('cursor: '), T('String'), P('? = window.'), F('nextCursor'), P('()\n\n'),
  C('// The client sends it back; reconstruct the position and continue\n'),
  K('val '), P('scrollable = '), T('Scrollable'), P('.'), F('of'), P('('), T('User_'), P('.id, '), N('10'), P(').'), F('from'), P('(cursor)\n'),
  K('val '), P('next = users.'), F('scroll'), P('(scrollable)'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Pagination and scrolling</div>
  <h1>Pagination and <span class="grad">keyset scrolling</span></h1>
  <p class="dek">Offset pages are fine until they are not: deep pages scan the table and every request pays for a count. Storm gives you classic pages when you want them and cursor-based scrolling when you need to scale.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Two flavors of the same problem: an admin table with numbered pages and a total count, and a feed with infinite scroll where nobody cares about page 7 of 4,812. They need different SQL strategies, and picking the wrong one shows up as database load.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>Spring Data's <code>Pageable</code> and <code>Page</code> handle the first case well:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · JPA', code: CODE_JPA_PAGE, sql: SQL_JPA_PAGE})}
  <p>Two costs hide in there. <code>Page</code> runs a count query on every request, whether the UI needs a fresh total or not. And <code>OFFSET</code> makes the database produce and discard every row before the page you asked for, so latency grows with page depth. The sort column is also a string; rename the property and the query fails at runtime. Spring Data added keyset scrolling in 3.1 with <code>ScrollPosition</code>, which addresses the depth problem; the comparison below is about how the two APIs handle the details.</p>

  <h2><span class="hno">03</span>Classic pages, typed</h2>
  <p>When the UI genuinely needs page numbers and totals, Storm's <code>page()</code> works the way you expect, with the sort expressed against the metamodel instead of a string:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_PAGE, sql: SQL_STORM_PAGE})}
  <p>Sort orders attach to the <code>Pageable</code> and carry over automatically when you navigate with <code>next()</code>, so page 2 cannot accidentally sort differently from page 1.</p>

  <h2><span class="hno">04</span>Keyset scrolling</h2>
  <p>For feeds and load-more lists, <code>scroll()</code> replaces offsets with a cursor: it remembers the last key seen and asks the database for rows after it. The database seeks via the index instead of scanning:</p>
  ${editor({file: 'FeedService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_SCROLL, sql: SQL_STORM_SCROLL})}
  <p>The <code>Scrollable</code> key is a typed metamodel reference, and it must be unique so the sort is stable; sorting by non-unique columns adds them with <code>sortBy</code>, in any number and each in its own direction, with the key as tiebreaker. Two guardrails are built in: adding your own <code>orderBy()</code> to a scrolled query is rejected at runtime instead of silently corrupting page boundaries, and there is deliberately no total count, because counting a large filtered set on every request is exactly the cost scrolling exists to avoid.</p>

  <h2><span class="hno">05</span>Cursors for REST APIs</h2>
  <p>Scroll state usually needs to cross a network boundary. <code>Window</code> serializes its position to an opaque cursor string, and a <code>Scrollable</code> reconstructs from it:</p>
  ${editor({file: 'FeedController.kt', tag: 'Kotlin · Storm', code: CODE_STORM_CURSOR})}
  <p><code>nextCursor()</code> returns null when no further results existed at query time, so it drops straight into a JSON response without extra checks. See <a class="tlink" href="/docs/cursors">Cursor Serialization</a> for supported key types and security notes.</p>

  <h2><span class="hno">06</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>Spring Data JPA</th><th>Storm</th></tr>
    <tr><td>Deep pages</td><td><code>OFFSET</code> walks and discards rows; keyset available via <code>ScrollPosition</code></td><td>Keyset scrolling seeks via the index</td></tr>
    <tr><td>Total count</td><td><code>Page</code> counts on every request; <code>Slice</code> drops it</td><td><code>page()</code> includes it; <code>slice()</code> and <code>scroll()</code> skip it</td></tr>
    <tr><td>Sort specification</td><td>Property name strings</td><td>Metamodel references, checked at compile time</td></tr>
    <tr><td>REST cursors</td><td>Hand-rolled serialization</td><td><code>nextCursor()</code> and <code>Scrollable.from()</code> built in</td></tr>
  </table>

  <h2><span class="hno">07</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/pagination-and-scrolling">Pagination and Scrolling</a>
    <a href="/docs/cursors">Cursors</a>
    <a href="/docs/repositories">Repositories</a>
    <a href="/docs/queries">Queries</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function PaginationTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="pagination" body={BODY} />;
}
