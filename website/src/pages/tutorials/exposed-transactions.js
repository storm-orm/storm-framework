import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: transactions, Exposed vs Storm. A knowledge-mapping page: the two
// libraries agree on explicit transaction blocks, so this translates concepts
// rather than argues. Exposed facts: transaction parameters, maxAttempts
// (reruns a top-level block on SQLException), queryTimeout (per statement),
// suspendTransaction, nesting via useNestedTransactions, after-commit hooks
// through StatementInterceptor (docs/comparison.md). Storm facts:
// docs/transactions.md (retries, timeout as a deadline, callbacks,
// cancellation).

const TITLE = 'Transactions Translate Almost One to One: Exposed vs Storm';
const DESC =
  'Exposed and Storm agree that transactions are explicit blocks, not ' +
  'annotations. If you know one API you nearly know the other; this page maps ' +
  'the concepts and marks the few places where they differ.';

const CODE_EXPOSED_TX = [
  C('// Exposed: the settings sit on the block\n'),
  F('transaction'), P('(\n'),
  P('    transactionIsolation = '), T('Connection'), P('.'), T('TRANSACTION_REPEATABLE_READ'), P(',\n'),
  P('    readOnly = '), K('false'), P(',\n'),
  P(') {\n'),
  P('    maxAttempts = '), N('3'), P('      '), C('// rerun the block on an SQLException\n'),
  P('    queryTimeout = '), N('5'), P('     '), C('// seconds, per statement\n\n'),
  P('    '), T('Orders'), P('.'), F('insert'), P(' { it[product] = productId; it[quantity] = '), N('2'), P(' }\n'),
  P('    '), T('Inventory'), P('.'), F('update'), P('({ '), T('Inventory'), P('.product '), K('eq'), P(' productId }) {\n'),
  P('        it[stock] = stock - '), N('2'), P('\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_TX = [
  C('// Storm: the same shape\n'),
  F('transaction'), P('(isolation = '), T('REPEATABLE_READ'), P(', timeoutSeconds = '), N('5'), P(', retries = '), N('2'), P(') {\n'),
  P('    '), K('val '), P('order = orders.'), F('insertAndFetch'), P('(newOrder)\n'),
  P('    inventory.'), F('decrease'), P('(order.product, order.quantity)\n'),
  P('}\n'),
  C('// commits when the block returns, rolls back when it throws'),
].join('');

const CODE_STORM_PROPAGATION = [
  C('// Nesting is chosen per block\n'),
  F('transaction'), P(' {\n'),
  P('    orders.'), F('insertAndFetch'), P('(newOrder)\n\n'),
  P('    '), F('transaction'), P('(propagation = '), T('NESTED'), P(') {\n'),
  P('        '), C('// a savepoint for this block only\n'),
  P('        audit.'), F('record'), P('(newOrder)\n'),
  P('    }\n\n'),
  P('    '), F('transaction'), P('(propagation = '), T('REQUIRES_NEW'), P(') {\n'),
  P('        '), C('// an independent transaction that commits on its own\n'),
  P('        metrics.'), F('bump'), P('('), S('"orders"'), P(')\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_EXPOSED_CALLBACKS = [
  F('transaction'), P(' {\n'),
  P('    '), F('registerInterceptor'), P('('), K('object'), P(' : '), T('StatementInterceptor'), P(' {\n'),
  P('        '), K('override fun '), F('afterCommit'), P('(transaction: '), T('Transaction'), P(') {\n'),
  P('            mailer.'), F('sendConfirmation'), P('(order)\n'),
  P('        }\n'),
  P('    })\n'),
  P('    '), T('Orders'), P('.'), F('insert'), P(' { it[id] = order.id }\n'),
  P('}'),
].join('');

const CODE_STORM_CALLBACKS = [
  F('transaction'), P(' {\n'),
  P('    '), K('val '), P('order = orders.'), F('insertAndFetch'), P('(newOrder)\n\n'),
  P('    '), F('onCommit'), P(' {\n'),
  P('        mailer.'), F('sendConfirmation'), P('(order)   '), C('// after the physical commit\n'),
  P('    }\n'),
  P('    '), F('onRollback'), P(' {\n'),
  P('        metrics.'), F('increment'), P('('), S('"orders.failed"'), P(')\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_SUSPEND = [
  C('// Exposed: suspendTransaction { }. Storm: transaction is a suspend function\n'),
  F('transaction'), P(' {\n'),
  P('    '), K('val '), P('pending = orders.'), F('findAllPending'), P('()\n\n'),
  P('    '), K('val '), P('updated = '), F('withContext'), P('('), T('Dispatchers'), P('.'), T('Default'), P(') {\n'),
  P('        '), F('heavyComputation'), P('(pending)\n'),
  P('    }\n\n'),
  P('    orders.'), F('update'), P('(updated)   '), C('// the same transaction\n'),
  P('}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Exposed vs Storm: transactions</div>
  <h1>Transactions translate <span class="grad">almost one to one</span></h1>
  <p class="dek">Exposed and Storm agree that a transaction is an explicit block of code. If you know one API you nearly know the other. This page maps the concepts and marks where they differ.</p>
  <div class="meta"><span>Series · Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Write an order and an inventory change atomically, set isolation and a timeout, rerun on a deadlock, nest blocks, send a confirmation only after the commit, and do it from coroutine code.</p>

  <h2><span class="hno">02</span>Where the two agree</h2>
  <p>Exposed made explicit transaction blocks the norm in Kotlin, with the settings at the call site:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_TX})}
  <p>Storm's block has the same shape:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_TX})}
  <p><code>transactionIsolation</code> maps to <code>isolation</code> and <code>readOnly</code> to <code>readOnly</code>. Three settings map with a difference:</p>
  <ul>
    <li><b>Reruns.</b> Exposed's <code>maxAttempts</code> counts attempts and reruns a top-level block on any <code>SQLException</code>. Storm's <code>retries</code> counts reruns after the first attempt, so <code>retries = 2</code> matches <code>maxAttempts = 3</code>. It reruns only on a failure the dialect classifies as transient, a deadlock or a serialization failure, plus an optimistic lock conflict; a constraint violation fails on the first attempt. A Storm block that joins an outer transaction refuses <code>retries</code>, since only the outer transaction can start over.</li>
    <li><b>Timeout.</b> Exposed's <code>queryTimeout</code> applies to each statement. Storm's <code>timeoutSeconds</code> is a deadline for the whole block: each statement runs with the time left, and a block that passes its deadline rolls back with <code>TransactionTimedOutException</code>.</li>
    <li><b>Reads.</b> Exposed runs every operation in a transaction, reads included. Storm's repositories take a connection per operation, and a block appears where atomicity matters.</li>
  </ul>

  <h2><span class="hno">03</span>Nesting and propagation</h2>
  <p>In Exposed, a nested <code>transaction</code> block shares the outer transaction by default, and <code>useNestedTransactions = true</code> in the database configuration turns nested blocks into savepoints. Storm chooses per block, with the propagation names Spring uses:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_PROPAGATION})}
  <p>The default, <code>REQUIRED</code>, joins the surrounding transaction, as Exposed does by default. <code>NESTED</code> is the savepoint mode. <code>REQUIRES_NEW</code> opens an independent transaction, <code>MANDATORY</code> and <code>NEVER</code> assert that a transaction is or is not active, and <code>SUPPORTS</code> and <code>NOT_SUPPORTED</code> cover code that may run without one.</p>

  <h2><span class="hno">04</span>After the outcome</h2>
  <p>Code placed after a block runs too early when the block joined an outer transaction: the end of the inner block commits nothing, and the outer transaction can still roll back. Both libraries let you register work for after the commit instead. In Exposed, a <code>StatementInterceptor</code> registered on the transaction has <code>afterCommit</code> and <code>afterRollback</code> hooks:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Exposed', code: CODE_EXPOSED_CALLBACKS})}
  <p>Storm registers a lambda on the block:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_CALLBACKS})}
  <p>A Storm callback registered in a joined or <code>NESTED</code> block waits for the outermost physical transaction, so <code>onCommit</code> fires only when the data is durable. If the commit fails, <code>onCommit</code> is skipped and <code>onRollback</code> runs. With <code>useNestedTransactions</code> on, an Exposed nested block is a transaction of its own, so check which boundary its <code>afterCommit</code> follows. Exposed's interceptors also offer <code>beforeCommit</code> and statement-level hooks, and can be registered globally; Storm has no equivalent of those.</p>

  <h2><span class="hno">05</span>Coroutines</h2>
  <p>Both libraries run transactions in coroutines: Exposed with <code>suspendTransaction { }</code>, Storm by making <code>transaction</code> a suspend function. A Storm transaction travels with the coroutine, so switching dispatchers inside the block keeps it:</p>
  ${editor({file: 'BatchJob.kt', tag: 'Kotlin · Storm', code: CODE_STORM_SUSPEND})}
  <p>A repository call on the other dispatcher joins the same transaction. Cancelling the coroutine stops the statement the block is waiting on through the driver and rolls the transaction back, so an aborted request does not keep its query running on the database.</p>

  <h2><span class="hno">06</span>The translation table</h2>
  <table class="cmp">
    <tr><th>Exposed</th><th>Storm</th></tr>
    <tr><td><code>transaction(transactionIsolation, readOnly)</code></td><td><code>transaction(isolation, readOnly)</code></td></tr>
    <tr><td><code>queryTimeout</code>, per statement</td><td><code>timeoutSeconds</code>, a deadline for the block</td></tr>
    <tr><td><code>maxAttempts</code>, reruns on any <code>SQLException</code></td><td><code>retries</code>, reruns on deadlocks, serialization failures and optimistic lock conflicts</td></tr>
    <tr><td>Nested blocks share the transaction; <code>useNestedTransactions</code> for savepoints</td><td>Propagation per block: <code>REQUIRED</code>, <code>NESTED</code>, <code>REQUIRES_NEW</code> and four more</td></tr>
    <tr><td><code>StatementInterceptor.afterCommit</code></td><td><code>onCommit { }</code>, <code>onRollback { }</code>, <code>onCompletion { }</code></td></tr>
    <tr><td><code>suspendTransaction { }</code></td><td><code>transaction { }</code> is a suspend function</td></tr>
    <tr><td>Reads run in a transaction</td><td>Repositories take a connection per operation</td></tr>
  </table>

  <h2><span class="hno">07</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/transactions">Transactions</a>
    <a href="/docs/error-handling">Error Handling</a>
    <a href="/docs/comparison">Framework Comparison</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ExposedTransactionsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="exposed-transactions" body={BODY} />;
}
