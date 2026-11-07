import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: pagination and keyset scrolling, Spring Data JPA vs Storm.

const TITLE = 'Pagination and Keyset Scrolling: JPA vs Storm';
const DESC =
  'Offset pages cost more the deeper they go, and a total takes a count query. ' +
  'Storm reads pages and slices by offset, scrolls by keyset, hands a client ' +
  'an opaque cursor, and logs advice when a page count is slow or an offset is deep.';

const CODE_JPA_PAGE = [
  K('val '), P('page = userRepository.'), F('findAll'), P('(\n'),
  P('    '), T('PageRequest'), P('.'), F('of'), P('('), N('0'), P(', '), N('10'), P(', '), T('Sort'), P('.'), F('by'), P('('), S('"email"'), P('))\n'),
  P(')\n\n'),
  K('val '), P('users = page.content\n'),
  K('val '), P('total = page.totalElements'),
].join('');

const CODE_STORM_PAGE = [
  K('val '), P('users = orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(')\n'),
  K('val '), P('pageable = '), T('Pageable'), P('.'), F('ofSize'), P('('), N('10'), P(').'), F('sortBy'), P('('), T('User_'), P('.email)\n\n'),
  K('val '), P('page = users.'), F('select'), P('()\n'),
  P('    .'), F('where'), P('('), T('User_'), P('.city '), K('eq'), P(' city)\n'),
  P('    .'), F('page'), P('(pageable)\n\n'),
  P('page.content        '), C('// the rows\n'),
  P('page.totalCount     '), C('// the matching rows\n'),
  P('page.'), F('totalPages'), P('()\n'),
  P('page.'), F('next'), P('()         '), C('// the Pageable for page 2, same sort'),
].join('');

const SQL_STORM_PAGE = [
  QC('-- the page'), '\n',
  QK('SELECT'), ' u.id, u.email, u.birth_date, u.street, u.postal_code, c.id, c.name, c.population\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QK('WHERE'), ' u.city_id = ', QQ('?'), '\n',
  QK('ORDER BY'), ' u.email\n',
  QK('LIMIT'), ' 10 ', QK('OFFSET'), ' 0\n\n',
  QC('-- the total, counted only when the page is full'), '\n',
  QK('SELECT COUNT'), '(*)\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QK('WHERE'), ' u.city_id = ', QQ('?'),
].join('');

const CODE_STORM_SCROLL = [
  C('// The ordering: email first, the primary key breaks ties\n'),
  K('val '), P('request = '), T('Scrollable'), P('.'), F('of'), P('('), T('User_'), P('.id, '), N('20'), P(').'), F('sortBy'), P('('), T('User_'), P('.email)\n\n'),
  K('val '), P('first = users.'), F('scroll'), P('(request)\n'),
  F('render'), P('(first.content)\n\n'),
  K('if'), P(' (first.'), F('hasNext'), P('()) {\n'),
  P('    '), C('// next() is the request positioned after the last row of the window\n'),
  P('    '), K('val '), P('second = users.'), F('scroll'), P('(first.'), F('next'), P('()!!)\n'),
  P('}'),
].join('');

const SQL_STORM_SCROLL = [
  QC('-- first window: one row beyond the size sets hasNext'), '\n',
  QK('SELECT'), ' u.id, u.email, u.birth_date, u.street, u.postal_code, c.id, c.name, c.population\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QK('ORDER BY'), ' u.email, u.id\n',
  QK('LIMIT'), ' 21\n\n',
  QC('-- next window: continues after the email and id of the last row'), '\n',
  QK('SELECT'), ' u.id, u.email, u.birth_date, u.street, u.postal_code, c.id, c.name, c.population\n',
  QK('FROM'), ' "user" u\n',
  QK('INNER JOIN'), ' city c ', QK('ON'), ' u.city_id = c.id\n',
  QK('WHERE'), ' (u.email > ', QQ('?'), ' ', QK('OR'), ' (u.email = ', QQ('?'), ' ', QK('AND'), ' u.id > ', QQ('?'), '))\n',
  QK('ORDER BY'), ' u.email, u.id\n',
  QK('LIMIT'), ' 21',
].join('');

const CODE_STORM_CURSOR = [
  C('// The ordering lives in code; the position comes from the client\n'),
  K('val '), P('request = '), T('Scrollable'), P('.'), F('of'), P('('), T('User_'), P('.id, size).'), F('sortBy'), P('('), T('User_'), P('.email)\n'),
  K('val '), P('window = users.'), F('scroll'), P('('), K('if'), P(' (cursor != '), K('null'), P(') request.'), F('from'), P('(cursor) '), K('else'), P(' request)\n\n'),
  C('// nextCursor() is null when no rows followed the window\n'),
  K('return '), T('UserList'), P('(window.content, window.'), F('nextCursor'), P('())'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Pagination and scrolling</div>
  <h1>Pagination and <span class="grad">keyset scrolling</span></h1>
  <p class="dek">An offset page costs more the deeper it is, and a total takes a count query. Storm reads pages and slices by offset, scrolls by keyset with cursors a client can send back, and tells you in the log when a count is slow or an offset is deep.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Two screens read the same table of users. An admin table shows numbered pages and a total. An API serves the list to a client that loads the next part as the user scrolls. The first reads by offset; the second should continue from the last row it returned.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>Spring Data reads a page through a <code>Pageable</code>:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · JPA', code: CODE_JPA_PAGE})}
  <p>Spring Data runs the count only when the page is full, and a <code>Slice</code> result skips it. The sort names the property as a string, or through <code>TypedSort</code>. Since 3.1, a repository method that takes a <code>ScrollPosition</code> and returns a <code>Window</code> scrolls by keyset. The keyset position holds its keys as a map of property names to values, and encoding it for a client is left to the application.</p>

  <h2><span class="hno">03</span>Pages and slices</h2>
  <p>Storm's <code>page()</code> takes a <code>Pageable</code> that sorts by metamodel fields:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_PAGE, sql: SQL_STORM_PAGE})}
  <p>The sort orders travel with the <code>Pageable</code>, so <code>next()</code> and <code>previous()</code> keep them. A page that is not full is its own total, so the count runs only for a full page. <code>page(pageable, totalCount)</code> reuses a total the application already has, and <code>slice(pageable)</code> reads one row beyond the page to report <code>hasNext()</code> and runs no count.</p>
  <p>Storm also reports what an offset costs. In development, a page read with an offset of a thousand rows or more gets <code>deep-offset</code> advice in the log, and a limit or offset without an <code>ORDER BY</code> gets <code>unordered-limit</code>, in production too, since the pages can repeat or skip rows. When the count behind a page is slow, the slow statement log adds <code>page-count</code> advice, which names <code>slice</code> as the read without it.</p>

  <h2><span class="hno">04</span>Keyset scrolling</h2>
  <p>A <code>Scrollable</code> states the ordering and the window size. <code>scroll()</code> returns a <code>Window</code> with the rows and the requests that continue after and before it:</p>
  ${editor({file: 'UserService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_SCROLL, sql: SQL_STORM_SCROLL})}
  <p>The next window compares the sort fields and the key with the values of the last row read, so an index on <code>(email, id)</code> serves every window at the same cost. The key must be unique and not null, typically the primary key. Sort fields go before it in any number, each in its own direction, and may belong to any table the query joins. A nullable sort field is refused, since rows with a NULL would fall out of every window.</p>
  <p>The <code>Scrollable</code> owns the <code>ORDER BY</code>, so a scrolled query with its own <code>orderBy()</code> fails with a <code>PersistenceException</code>. <code>orderFirst</code>, which puts the rows matching a condition first, works with <code>scroll</code>. There is no total count; if a screen needs one, <code>resultCount</code> on the query builder runs it separately.</p>

  <h2><span class="hno">05</span>Cursors for REST APIs</h2>
  <p>A <code>Window</code> encodes its positions as opaque, URL-safe strings with <code>nextCursor()</code> and <code>previousCursor()</code>. <code>Scrollable.from(cursor)</code> puts a request at the position a client sends back:</p>
  ${editor({file: 'UserController.kt', tag: 'Kotlin · Storm', code: CODE_STORM_CURSOR})}
  <p>The cursor carries the position only. The ordering and the size stay in code, so a client can ask for a different size on the next request. A cursor that does not match the request's ordering throws <code>InvalidCursorException</code>, which a web layer maps to a fresh first window. The format is not signed; see <a class="tlink" href="/docs/cursors">Cursor Serialization</a> for the supported key types and how to protect cursors from tampering.</p>

  <h2><span class="hno">06</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>Spring Data JPA</th><th>Storm</th></tr>
    <tr><td>Offset pages</td><td><code>Page</code>; the count is skipped on a partial page</td><td><code>page()</code>, the same; <code>page(pageable, totalCount)</code> reuses a known total</td></tr>
    <tr><td>Without a count</td><td><code>Slice</code></td><td><code>slice()</code></td></tr>
    <tr><td>Keyset scrolling</td><td><code>ScrollPosition.keyset()</code> with a <code>Window</code>, since 3.1</td><td><code>scroll(Scrollable)</code> with a <code>Window</code></td></tr>
    <tr><td>Sort fields</td><td>Property names in <code>Sort</code>, or <code>TypedSort</code></td><td>Metamodel fields, of any table in the query</td></tr>
    <tr><td>Client cursors</td><td>A map of keys, encoded by the application</td><td><code>nextCursor()</code> and <code>Scrollable.from()</code>, an opaque string</td></tr>
    <tr><td>Cost in the log</td><td>No equivalent</td><td><code>deep-offset</code>, <code>unordered-limit</code>, and <code>page-count</code> when the count is slow</td></tr>
  </table>

  <h2><span class="hno">07</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/pagination-and-scrolling">Pagination and Scrolling</a>
    <a href="/docs/cursors">Cursors</a>
    <a href="/docs/advice">Statement Advice</a>
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
