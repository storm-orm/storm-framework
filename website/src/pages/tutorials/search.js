import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, M, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: search and suggest on the database the application already has.
// Storm-way how-to; content verified against docs/search.md, the Kotlin
// search/suggest/Match functions in storm-kotlin QueryBuilder.kt, and SQL
// captured from storm-core (SearchIntegrationTest, SemiJoinIntegrationTest
// models). The word-start SQL is PostgreSQL's (PostgreSQLSqlDialect.wordStartMatch).

const TITLE = 'Search and Suggest on Your Own Database';
const DESC =
  'A search page and a type-ahead box with two predicates: search and suggest ' +
  'take the input as typed, match it the same way on every database, find ' +
  'owners through their pets, and run on an ordinary trigram index.';

const CODE_MODEL = [
  K('data class '), T('Owner'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('address: '), T('Address'), P(',\n'),
  P('    '), K('val '), P('telephone: '), T('String'), P('?\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  K('data class '), T('Address'), P('('), K('val '), P('street: '), T('String'), P(', '), A('@FK'), P(' '), K('val '), P('city: '), T('City'), P(')\n\n'),
  K('data class '), T('Pet'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Owner'), P('\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>'),
].join('');

const CODE_SEARCH = [
  K('interface '), T('OwnerRepository'), P(' : '), T('EntityRepository'), P('<'), T('Owner'), P(', '), T('Int'), P('> {\n\n'),
  P('    '), C('// "davis madison": every word must match the last name or the city\n'),
  P('    '), K('fun '), F('find'), P('(input: '), T('String'), P('): '), T('List'), P('<'), T('Owner'), P('> =\n'),
  P('        '), F('select'), P('()\n'),
  P('            .'), F('where'), P('('), F('search'), P('(input, '), T('Owner_'), P('.lastName, '), T('Owner_'), P('.address.city.name))\n'),
  P('            .'), F('orderBy'), P('('), T('Owner_'), P('.lastName)\n'),
  P('            .resultList\n'),
  P('}'),
].join('');

const SQL_SEARCH = [
  QK('SELECT'), ' o.id, o.first_name, o.last_name, …, c.name\n',
  QK('FROM'), ' owner o\n',
  QK('LEFT JOIN'), ' city c ', QK('ON'), ' o.city_id = c.id\n',
  QK('WHERE'), ' (', QK('LOWER'), '(o.last_name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!' ", QK('OR'), ' ', QK('LOWER'), '(c.name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!')\n",
  '  ', QK('AND'), ' (', QK('LOWER'), '(o.last_name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!' ", QK('OR'), ' ', QK('LOWER'), '(c.name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!')\n",
  QK('ORDER BY'), ' o.last_name\n',
  QC("-- binds '%davis%' and '%madison%': one AND per word, one OR per column"),
].join('');

const CODE_SUGGEST = [
  C('// "dav" as the receptionist types: owners first, best match on top\n'),
  K('fun '), F('suggestions'), P('(input: '), T('String'), P('): '), T('List'), P('<'), T('Owner'), P('> =\n'),
  P('    '), F('select'), P('()\n'),
  P('        .'), F('where'), P('('), F('suggest'), P('(input, '), T('Owner_'), P('.lastName, '), T('Owner_'), P('.firstName))\n'),
  P('        .'), F('orderFirst'), P('('), T('Owner_'), P('.lastName '), F('eqIgnoreCase'), P(' input)\n'),
  P('        .'), F('orderFirst'), P('('), T('Owner_'), P('.lastName '), F('startsWithIgnoreCase'), P(' input)\n'),
  P('        .'), F('orderBy'), P('('), T('Owner_'), P('.lastName)\n'),
  P('        .'), F('limit'), P('('), N('6'), P(')\n'),
  P('        .resultList'),
].join('');

const SQL_SUGGEST = [
  QC('-- PostgreSQL renders a word start as one regular expression'), '\n',
  QK('SELECT'), ' o.id, o.first_name, o.last_name, …\n',
  QK('FROM'), ' owner o\n',
  QK('WHERE'), ' ', QK('LOWER'), '(o.last_name) ~ ', QK('LOWER'), '(', QQ('?'), ') ', QK('OR'), ' ', QK('LOWER'), '(o.first_name) ~ ', QK('LOWER'), '(', QQ('?'), ')\n',
  QK('ORDER BY'), ' ', QK('CASE WHEN'), ' ', QK('LOWER'), '(o.last_name) = ', QK('LOWER'), '(', QQ('?'), ') ', QK('THEN'), ' 0 ', QK('ELSE'), ' 1 ', QK('END'), ',\n',
  '         ', QK('CASE WHEN'), ' ', QK('LOWER'), '(o.last_name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!' ", QK('THEN'), ' 0 ', QK('ELSE'), ' 1 ', QK('END'), ',\n',
  '         o.last_name\n',
  QK('LIMIT'), ' ', QQ('6'), '\n',
  QC("-- the word binds as '(^|[- ''(/:.,])dav': the value's start, or after a space or punctuation"),
].join('');

const CODE_RELATED = [
  C('// "davis leo": the owner named Davis who has a pet called Leo\n'),
  K('fun '), F('find'), P('(input: '), T('String'), P('): '), T('List'), P('<'), T('Owner'), P('> =\n'),
  P('    '), F('select'), P('()\n'),
  P('        .'), F('semiJoin'), P('<'), T('Pet'), P('>().'), F('on'), P('<'), T('Owner'), P('>()\n'),
  P('        .'), F('where'), P('('), F('search'), P('(input, '), T('Owner_'), P('.lastName, '), T('Pet_'), P('.name))\n'),
  P('        .resultList'),
].join('');

const SQL_RELATED = [
  QK('SELECT'), ' o.id, o.first_name, o.last_name, …\n',
  QK('FROM'), ' owner o\n',
  QK('WHERE'), ' o.id ', QK('IN'), ' (\n',
  '  ', QK('SELECT'), ' o.id ', QK('FROM'), ' owner o ', QK('WHERE'), ' ', QK('LOWER'), '(o.last_name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!'\n",
  QK('UNION'), '\n',
  '  ', QK('SELECT'), ' p.owner_id ', QK('FROM'), ' pet p ', QK('WHERE'), ' ', QK('LOWER'), '(p.name) ', QK('LIKE'), ' ', QK('LOWER'), '(', QQ('?'), ") ", QK('ESCAPE'), " '!'\n",
  ') ', QK('AND'), ' o.id ', QK('IN'), ' (\n',
  '  ', QC('-- the same two branches for the second word'), '\n',
  ')\n',
  QC('-- each branch reads its own table through its own index; no join, no DISTINCT'),
].join('');

const CODE_FORMS = [
  K('val '), P('nameNoise = '), F('setOf'), P('('), S('"van"'), P(', '), S('"de"'), P(', '), S('"der"'), P(', '), S('"den"'), P(')\n\n'),
  K('fun '), F('suggestions'), P('(input: '), T('String'), P('): '), T('List'), P('<'), T('Owner'), P('> =\n'),
  P('    '), F('select'), P('()\n'),
  P('        .'), F('semiJoin'), P('<'), T('Pet'), P('>().'), F('on'), P('<'), T('Owner'), P('>()\n'),
  P('        .'), F('where'), P('('), F('suggest'), P('(\n'),
  P('            input,\n'),
  P('            nameNoise,                                '), C('// words that narrow nothing\n'),
  P('            '), F('wordStart'), P('('), T('Owner_'), P('.lastName),\n'),
  P('            '), F('wordStart'), P('('), T('Pet_'), P('.name).'), F('withoutFallback'), P('(),  '), C('// pets join in from three characters\n'),
  P('            '), F('exact'), P('('), T('Owner_'), P('.telephone),                '), C('// a whole number, on an ordinary index\n'),
  P('        ))\n'),
  P('        .'), F('orderBy'), P('('), T('Owner_'), P('.lastName)\n'),
  P('        .'), F('limit'), P('('), N('6'), P(')\n'),
  P('        .resultList'),
].join('');

const SQL_INDEX = [
  QC('-- a migration; PostgreSQL ships the trigram extension'), '\n',
  QK('CREATE EXTENSION IF NOT EXISTS'), ' pg_trgm;\n\n',
  QK('CREATE INDEX'), ' owner_last_name_trgm  ', QK('ON'), ' owner ', QK('USING'), ' gin (', QK('LOWER'), '(last_name) gin_trgm_ops);\n',
  QK('CREATE INDEX'), ' owner_first_name_trgm ', QK('ON'), ' owner ', QK('USING'), ' gin (', QK('LOWER'), '(first_name) gin_trgm_ops);\n',
  QK('CREATE INDEX'), ' pet_name_trgm         ', QK('ON'), ' pet   ', QK('USING'), ' gin (', QK('LOWER'), '(name) gin_trgm_ops);\n',
  QK('CREATE INDEX'), ' owner_telephone       ', QK('ON'), ' owner (telephone);',
].join('');

const CODE_ADVICE = [
  P('SQL slow (SELECT Owner): 412 ms in database, 6 rows, OwnerController.kt:31\n'),
  M('	SELECT o.id, o.first_name, o.last_name, … FROM owner o WHERE LOWER(o.last_name) ~ LOWER(?) …\n'),
  M('	shape: 56f85e0581c8f643  parameters: 3\n'),
  P('	'), K('advice:'), P(' no index on owner serves wordStart(last_name), so the database reads owner in full to match it.\n'),
  P('		'), K('fix:'), P(' a trigram index on LOWER(last_name) serves it.\n'),
  P('		'), K('see:'), P(' https://orm.st/docs/advice#unindexed-search'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Search and suggest</div>
  <h1>Search and suggest on <span class="grad">your own database</span></h1>
  <p class="dek">A search page and a type-ahead box need two predicates and an index. Storm's <code>search</code> and <code>suggest</code> take the input as the user typed it, match it the same way on every database, and find rows through related tables. There is no second system to run or keep in sync.</p>
  <div class="meta"><span>Series · The Storm way</span><span>7 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>The front desk of a pet clinic looks up owners all day. It has two boxes. The search page takes a few words, such as <code>davis madison</code>, and lists every owner they match. The box at the top suggests owners while the receptionist types, six at a time.</p>
  <p>Both boxes receive raw input: several words, any case, sometimes a quote or a stray <code>%</code>. Written by hand, that means splitting the words, lowering them, escaping the <code>LIKE</code> wildcards, building an <code>OR</code> per column and an <code>AND</code> per word, and handling the empty box. It also means a query that reads the whole table once the data grows. The model for this tutorial:</p>
  ${editor({file: 'Model.kt', tag: 'Kotlin · Storm', code: CODE_MODEL})}

  <h2><span class="hno">02</span>The search page</h2>
  <p><code>search</code> takes the input as the box received it and the columns to match. It returns a predicate, so it goes in <code>where</code> and combines with other conditions through <code>and</code> and <code>or</code>:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · Storm', code: CODE_SEARCH, sql: SQL_SEARCH})}
  <p>Every word has to occur in at least one of the columns. Matching ignores case and treats the input as literal text, so <code>50%</code> looks for a percent sign. The city name sits on another table; the path <code>Owner_.address.city.name</code> joins it. The statement is the same on every database Storm supports.</p>
  <table class="cmp">
    <tr><th>Input</th><th>Words</th></tr>
    <tr><td><code>davis madison</code></td><td><code>davis</code> and <code>madison</code>, in any order</td></tr>
    <tr><td><code>"van der" berg</code></td><td><code>van der</code> and <code>berg</code>: a quoted phrase is one word</td></tr>
    <tr><td><code>"van der</code></td><td><code>van der</code>: an open quote runs to the end while the user is still typing</td></tr>
    <tr><td>empty</td><td>none, so the page lists every owner</td></tr>
  </table>

  <h2><span class="hno">03</span>Suggestions while typing</h2>
  <p>A type-ahead box queries on inputs like <code>da</code> and <code>van b</code>, far more often than a search page, so each query has to stay cheap. <code>suggest</code> takes the same input and columns and matches every word at the start of a word: <code>van b</code> finds "Van den Berg" and not "Sullivan". A word starts at the beginning of the value or after a space, hyphen, apostrophe, parenthesis, slash, colon, period or comma, so <code>marie</code> finds "Anne-Marie" and <code>brien</code> finds "O'Brien". <code>orderFirst</code> puts the closest matches on top:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · Storm', code: CODE_SUGGEST, sql: SQL_SUGGEST})}
  <p>The two functions read input the same way and differ in what an empty or very short box means:</p>
  <table class="cmp">
    <tr><th>Input</th><th><code>search</code></th><th><code>suggest</code></th></tr>
    <tr><td><code>van</code></td><td>anywhere: "Van den Berg", "Sullivan"</td><td>at a word start: "Van den Berg"</td></tr>
    <tr><td><code>va</code></td><td colspan="2">shorter than three characters, so the whole input matches the start of the value: "Van den Berg", not "De Vaal"</td></tr>
    <tr><td><code>v</code></td><td colspan="2">no rows: one character matches too many</td></tr>
    <tr><td>empty</td><td>every row</td><td>no rows</td></tr>
  </table>
  <p>The short-word rule keeps the first keystrokes from reading and sorting a large part of the table. Once one word reaches three characters, the others are tested only on the rows that word found: for <code>van d</code>, <code>van</code> finds the rows and <code>d</code> narrows them.</p>

  <h2><span class="hno">04</span>Finding owners through their pets</h2>
  <p>The receptionist often remembers the dog, not the owner. Semi-join the pets and name the pet's column next to the owner's. Each owner comes back once, however many pets match, and an owner without pets is still found by name:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · Storm', code: CODE_RELATED, sql: SQL_RELATED})}
  <p>Each word is matched on its own, so <code>leo max</code> finds the owner of both a Leo and a Max. A word renders as an <code>IN</code> over a <code>UNION</code> with a branch per table, so the database reads each table through its own index instead of joining owners to every pet. A further <code>semiJoin&lt;Visit&gt;().on&lt;Pet&gt;()</code> with <code>Visit_.description</code> finds owners by what their pets came in for.</p>

  <h2><span class="hno">05</span>Match forms and noise words</h2>
  <p>Each column can state how a word matches it: <code>anywhere</code>, <code>wordStart</code>, <code>prefix</code> or <code>exact</code>, plus <code>IgnoreCase</code> variants of the last two. Some words narrow nothing. In a Dutch clinic, <code>van</code> starts a large share of last names and also occurs inside "Sullivan", so it costs the most to test and drops owners who lack it. Pass a set of words to ignore after the input:</p>
  ${editor({file: 'OwnerRepository.kt', tag: 'Kotlin · Storm', code: CODE_FORMS})}
  <p>With that box, <code>van berg</code> matches on <code>berg</code> alone, and a quoted <code>"van"</code> still counts. A telephone number matches only whole, which an ordinary index serves. <code>withoutFallback()</code> keeps the pets out of the statement until a word reaches three characters: for <code>le</code>, the query reads only the owners, and pets join in at <code>leo</code>. Pet names that merely start with "le" would rank below the owners' own names anyway, and reading them is the expensive part.</p>

  <h2><span class="hno">06</span>The index that keeps it fast</h2>
  <p><code>search</code> and <code>suggest</code> compare <code>LOWER(column)</code> against a pattern that can start anywhere in the value. An ordinary B-tree index cannot serve that. On PostgreSQL, a trigram index over the same expression serves both the <code>LIKE</code> and the word-start expression:</p>
  ${editor({file: 'V12__owner_search.sql', tag: 'SQL · PostgreSQL', code: SQL_INDEX})}
  <p>Storm renders the predicate the same way for every input, so the index matches without changes to the query. <code>prefix</code> and <code>exact</code> compare the bare column, which an ordinary index serves on any database. On a database without a substring index, a word-suffix table kept by a trigger gives <code>wordStart</code> an ordinary index to use; the <a class="tlink" href="/docs/search#large-tables-without-a-substring-index">search docs</a> show the table and the query.</p>
  <p>Storm also checks this for you. Spring Boot and Ktor load the schema when they validate it at startup, and once a slow threshold is set, a search that turns out slow carries advice naming the index it lacks:</p>
  ${editor({file: 'console', tag: 'st.orm.sql.slow', code: CODE_ADVICE})}
  <p>The <a class="tlink" href="/tutorials/advice">advice tutorial</a> covers where that line comes from. For accents, wrap a column in a <code>SqlFunction</code> over an accent-removing database function, so "muller" finds "Müller" through an index on the same expression; see <a class="tlink" href="/docs/search#ignoring-accents">Ignoring Accents</a>.</p>

  <h2><span class="hno">07</span>What it does not do</h2>
  <p><code>search</code> matches words. It does not score relevance, stem words, or correct typos the way a search engine or a full-text index does. You state the ranking with <code>orderFirst</code>, and for full-text features you can call the database's own full-text search from a <a class="tlink" href="/tutorials/sql-templates">SQL template</a>. For looking up owners, pets, products or orders by name, the database you already run is enough.</p>

  <h2><span class="hno">08</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/search">Search</a>
    <a href="/docs/queries#filtering-by-related-rows">Filtering by related rows</a>
    <a href="/docs/advice#unindexed-search">Unindexed search advice</a>
    <a href="/docs/pagination-and-scrolling">Pagination and scrolling</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function SearchTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="search" body={BODY} />;
}
