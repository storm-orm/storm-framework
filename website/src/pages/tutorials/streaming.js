import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: streaming large result sets. Storm-way how-to; content verified
// against docs/batch-streaming.md (resultFlow, the consume-only rule while a
// stream is open, windows(size), batch sizes) and docs/hydration.md
// (interner memory safety).

const TITLE = 'Streaming Large Result Sets';
const DESC =
  'Process millions of rows in bounded memory: resultFlow streams rows as ' +
  'they arrive, windows(size) frees the connection between windows for loops ' +
  'that write, and batched writes handle the other direction.';

const CODE_FLOW = [
  C('// A cold Flow: the query runs when it is collected\n'),
  K('val '), P('users: '), T('Flow'), P('<'), T('User'), P('> = orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(').'), F('select'), P('().resultFlow\n\n'),
  P('users.'), F('collect'), P(' { user ->\n'),
  P('    '), F('processUser'), P('(user)   '), C('// one row in memory at a time\n'),
  P('}\n\n'),
  C('// Flow operators apply row by row as the rows arrive\n'),
  K('val '), P('emails: '), T('List'), P('<'), T('String'), P('> = users.'), F('map'), P(' { it.email }.'), F('toList'), P('()'),
].join('');

const CODE_FILTERED = [
  C('// Filter in the database, stream what remains\n'),
  K('val '), P('residents: '), T('Flow'), P('<'), T('User'), P('> = orm.'), F('selectFrom'), P('<'), T('User'), P('>()\n'),
  P('    .'), F('where'), P('('), T('User_'), P('.city '), K('eq'), P(' city)\n'),
  P('    .resultFlow'),
].join('');

const CODE_STREAM_REFUSED = [
  F('transaction'), P(' {\n'),
  P('    orm.'), F('selectFrom'), P('<'), T('User'), P('>().resultFlow.'), F('collect'), P(' { user ->\n'),
  P('        orm '), K('update'), P(' user.'), F('copy'), P('(email = user.email.'), F('lowercase'), P('())\n'),
  P('        '), C('// PersistenceException: a result stream is still open\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_WINDOWS = [
  C('// One closed statement per 1000 rows; the connection is free in between\n'),
  F('transaction'), P(' {\n'),
  P('    '), K('val '), P('users = orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(')\n'),
  P('    users.'), F('select'), P('()\n'),
  P('        .'), F('where'), P('('), T('User_'), P('.city '), K('eq'), P(' city)\n'),
  P('        .'), F('windows'), P('('), N('1000'), P(')\n'),
  P('        .'), F('collect'), P(' { window ->\n'),
  P('            users.'), F('update'), P('(window.'), F('content'), P('().'), F('map'), P(' { it.'), F('copy'), P('(email = it.email.'), F('lowercase'), P('()) })\n'),
  P('        }\n'),
  P('}'),
].join('');

const CODE_BATCH = [
  K('val '), P('users = orm.'), F('entity'), P('('), T('User'), P('::'), K('class'), P(')\n\n'),
  P('users.'), F('insert'), P('(newUsers)   '), C('// a list, written in batches of 1000\n\n'),
  K('val '), P('incoming: '), T('Flow'), P('<'), T('User'), P('> = '), F('readFromKafka'), P('()\n'),
  P('users.'), F('update'), P('(incoming, '), N('500'), P(')   '), C('// a Flow, written in batches of 500'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Streaming</div>
  <h1>Streaming <span class="grad">large result sets</span></h1>
  <p class="dek">A nightly job reads five million rows. As a list they do not fit in the heap. Storm streams them through a Flow in bounded memory, reads them in windows when the loop writes back, and batches the writes.</p>
  <div class="meta"><span>Series · The Storm way</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Export, migrate or reprocess a table that does not fit in memory: filter in the database, write changes back inside a transaction where needed, and keep the writes efficient.</p>

  <h2><span class="hno">02</span>Read as a Flow</h2>
  <p>Every query builder has <code>resultFlow</code>. The Flow is cold: building it runs nothing, and the query executes when you collect it. Rows hydrate as they arrive, and the statement closes when the collection completes or the coroutine is cancelled, so there is no close call to forget:</p>
  ${editor({file: 'ExportJob.kt', tag: 'Kotlin · Storm', code: CODE_FLOW})}
  <p>Memory stays bounded because Storm's per-query interner holds an entity only while your code still holds it, so the rows you are done with are garbage-collected as usual. Each collection runs the query again.</p>

  <h2><span class="hno">03</span>Filter in the database</h2>
  <p>A stream is a query like any other, so the <code>where</code> runs in the database and only the matching rows travel:</p>
  ${editor({file: 'ResidentsJob.kt', tag: 'Kotlin · Storm', code: CODE_FILTERED})}

  <h2><span class="hno">04</span>Write back in windows</h2>
  <p>While a stream has unread rows, its connection belongs to the stream. JDBC drivers differ in what they do with a second statement on that connection: some run it, one rejects it, and others first read the rest of the result into memory. Storm applies one rule on every database: a statement on a connection with an open stream is refused. Inside a transaction every statement uses the transaction's connection, so a loop that writes while it streams fails:</p>
  ${editor({file: 'ReprocessJob.kt', tag: 'Kotlin · Storm', code: CODE_STREAM_REFUSED})}
  <p>For a loop that needs the database, use <code>windows(size)</code>. Each window of rows is read by its own statement, ordered by the primary key, and that statement has closed before the window reaches your code. Between windows the connection is free, and one batched update per window replaces one statement per row:</p>
  ${editor({file: 'ReprocessJob.kt', tag: 'Kotlin · Storm', code: CODE_WINDOWS})}
  <p>Each window resumes from the last key it saw, so a long job can store <code>window.nextCursor()</code> and continue from it after a restart. Windows sort by a unique key, so the query takes no <code>orderBy</code> of its own. Use <code>resultFlow</code> to consume a result and <code>windows</code> when the loop reads, fetches or writes.</p>

  <h2><span class="hno">05</span>The write direction</h2>
  <p>Batch writes take a list or a Flow and write in batches of 1000 rows by default. The Flow forms take a batch size:</p>
  ${editor({file: 'ImportJob.kt', tag: 'Kotlin · Storm', code: CODE_BATCH})}

  <h2><span class="hno">06</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/batch-streaming">Batch and Streaming</a>
    <a href="/docs/streaming-design">Streaming: Design Notes</a>
    <a href="/docs/hydration">Hydration</a>
    <a href="/docs/pagination-and-scrolling">Pagination and Scrolling</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function StreamingTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="streaming" body={BODY} />;
}
