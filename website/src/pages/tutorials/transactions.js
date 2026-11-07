import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: transactions, Spring @Transactional vs Storm's programmatic API.
// Storm facts verified against docs/transactions.md (callbacks, retries,
// cancellation, Spring-managed transactions and the suspend restriction) and
// st.orm.template.transaction's signature.

const TITLE = 'Transactions as Blocks: Spring @Transactional vs Storm';
const DESC =
  '@Transactional works through a proxy, which skips self-invocation in its ' +
  'default mode. Storm\'s transaction blocks put the scope in the code, register ' +
  'commit callbacks inline, rerun on deadlocks and serialization failures, and ' +
  'run as suspend functions.';

const CODE_SPRING_TX = [
  A('@Service'), P('\n'),
  K('class '), T('OrderService'), P('(\n'),
  P('    '), K('private val '), P('orders: '), T('OrderRepository'), P(',\n'),
  P('    '), K('private val '), P('mailer: '), T('Mailer'), P(',\n'),
  P(') {\n'),
  P('    '), A('@Transactional'), P('\n'),
  P('    '), K('fun '), F('placeOrder'), P('(order: '), T('Order'), P(') {\n'),
  P('        orders.'), F('save'), P('(order)\n'),
  P('        mailer.'), F('sendConfirmation'), P('(order)   '), C('// sent before the commit\n'),
  P('    }\n\n'),
  P('    '), K('fun '), F('importAll'), P('(batch: '), T('List'), P('<'), T('Order'), P('>) {\n'),
  P('        batch.'), F('forEach'), P(' { '), F('placeOrder'), P('(it) }   '), C('// a call on this: no proxy, no transaction\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_TX = [
  C('// The transaction is the block\n'),
  F('transaction'), P(' {\n'),
  P('    '), K('val '), P('order = orders.'), F('insertAndFetch'), P('(newOrder)\n'),
  P('    inventory.'), F('decrease'), P('(order.product, order.quantity)\n'),
  P('}\n'),
  C('// commits when the block returns, rolls back when it throws'),
].join('');

const CODE_STORM_CALLBACKS = [
  F('transaction'), P(' {\n'),
  P('    '), K('val '), P('order = orders.'), F('insertAndFetch'), P('(newOrder)\n'),
  P('    inventory.'), F('decrease'), P('(order.product, order.quantity)\n\n'),
  P('    '), F('onCommit'), P(' {\n'),
  P('        '), C('// runs after the commit succeeded\n'),
  P('        mailer.'), F('sendConfirmation'), P('(order)\n'),
  P('        events.'), F('publish'), P('('), T('OrderCreated'), P('(order))\n'),
  P('    }\n\n'),
  P('    '), F('onRollback'), P(' {\n'),
  P('        metrics.'), F('increment'), P('('), S('"orders.failed"'), P(')\n'),
  P('    }\n'),
  P('}'),
].join('');

const CODE_STORM_CONFIG = [
  C('// Isolation, timeout and reruns, stated at the call site\n'),
  F('transaction'), P('(isolation = '), T('SERIALIZABLE'), P(', timeoutSeconds = '), N('5'), P(', retries = '), N('2'), P(') {\n'),
  P('    inventory.'), F('decrease'), P('(newOrder.product, newOrder.quantity)\n'),
  P('    '), K('val '), P('order = orders.'), F('insertAndFetch'), P('(newOrder)\n'),
  P('    '), F('onCommit'), P(' { mailer.'), F('sendConfirmation'), P('(order) }\n'),
  P('}'),
].join('');

const CODE_STORM_COROUTINE = [
  C('// A suspend transaction keeps its connection across dispatcher switches\n'),
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
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Transactions</div>
  <h1>Transactions <span class="grad">as blocks</span></h1>
  <p class="dek"><code>@Transactional</code> applies through a proxy around the bean, and the proxy has rules about which calls it sees. Storm's transaction is a block of code, and its scope is what the block encloses.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Insert an order and decrease the inventory in one transaction. Send the confirmation email only once the transaction has committed.</p>

  <h2><span class="hno">02</span>The Spring way</h2>
  <p><code>@Transactional</code> makes the method atomic. It works through a proxy that wraps the bean, and the proxy only sees calls that go through it:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Spring', code: CODE_SPRING_TX})}
  <p>The snippet has two bugs, and neither fails a test on the happy path. The email goes out before the commit, so a failing commit leaves a customer with a confirmation for an order that does not exist. Spring's fix is to publish an event and handle it in a <code>@TransactionalEventListener</code>, or to register a <code>TransactionSynchronization</code>. And <code>importAll</code> calls <code>placeOrder</code> on <code>this</code>, which bypasses the proxy in Spring's default proxy mode: the orders are saved without the transaction the annotation asks for, and nothing in the log says so.</p>

  <h2><span class="hno">03</span>The Storm way</h2>
  <p>Storm's transaction is a function you call with a block. The scope is the block, in any class, method or visibility:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_TX})}
  <p>There is no proxy, so calling a function that opens a <code>transaction</code> block from the same class behaves like calling it from anywhere else. Nested blocks follow the propagation they state, <code>REQUIRED</code> by default.</p>

  <h2><span class="hno">04</span>Side effects after the outcome</h2>
  <p>Register the email as a callback inside the block. Running it after the block is not the same thing: with <code>REQUIRED</code> propagation the block may have joined an outer transaction, so the end of the block commits nothing and the outer transaction can still roll back. Callbacks bind to the physical transaction, so they fire on its outcome however deeply the block is nested:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_CALLBACKS})}
  <p><code>onCommit</code> runs once the data is durable. If the commit fails, it is skipped and <code>onRollback</code> runs instead. <code>onCompletion { committed -> }</code> covers work that has to happen either way.</p>

  <h2><span class="hno">05</span>Settings at the call site</h2>
  <p>Propagation, isolation, timeout, read-only and reruns are parameters of the block, so the transaction's guarantees are visible where it starts:</p>
  ${editor({file: 'OrderService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_CONFIG})}
  <p>Under <code>SERIALIZABLE</code> the database may refuse one of two concurrent transactions with a serialization failure. Storm classifies that failure, a deadlock and an optimistic lock conflict as a <code>TransientPersistenceException</code>, and <code>retries = 2</code> runs the block up to two more times on one. Each attempt is a transaction of its own with its own timeout, and <code>onCommit</code> fires only for the attempt that committed. A constraint violation is not rerun. A block that joins an outer transaction refuses <code>retries</code>, because only the outer transaction can start over.</p>

  <h2><span class="hno">06</span>Coroutines</h2>
  <p><code>transaction</code> is a suspend function. The transaction travels with the coroutine, so switching dispatchers inside the block keeps it:</p>
  ${editor({file: 'BatchJob.kt', tag: 'Kotlin · Storm', code: CODE_STORM_COROUTINE})}
  <p>A repository call on the other dispatcher joins the same transaction. Cancelling the coroutine stops the statement the block is waiting on through the driver, and the transaction rolls back and releases its connection, so a request the client aborted does not keep its query running. For blocking code, <code>transactionBlocking</code> takes the same parameters. With a JDBC transaction manager, Spring's <code>@Transactional</code> does not support suspend functions; it needs a <code>ReactiveTransactionManager</code> for those.</p>

  <h2><span class="hno">07</span>Mixing with Spring</h2>
  <p>In a Spring application the two systems bridge when the template is wired to Spring's transaction management. The Spring Boot starters do this when a transaction manager is present; outside Boot, build the template with <code>springOrmTemplate</code>. Storm's blocks then run through Spring's transaction manager: a <code>transactionBlocking</code> inside a <code>@Transactional</code> method joins the surrounding transaction, and <code>onCommit</code> waits for Spring's commit.</p>
  <p>The suspend <code>transaction</code> is not available while Spring manages Storm's transactions, because Spring binds its transaction state to the calling thread; Storm refuses it with an error that says so. Applications written with suspend functions keep a Storm-managed template, as Ktor applications do. See <a class="tlink" href="/docs/spring-integration">Spring Integration</a>.</p>

  <h2><span class="hno">08</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>Spring @Transactional</th><th>Storm</th></tr>
    <tr><td>Mechanism</td><td>A proxy around the bean</td><td>A function that takes a block</td></tr>
    <tr><td>Self-invocation</td><td>Bypasses the transaction in proxy mode</td><td>A block applies wherever it is called</td></tr>
    <tr><td>After-commit effects</td><td><code>@TransactionalEventListener</code> or a <code>TransactionSynchronization</code></td><td><code>onCommit</code> and <code>onRollback</code> in the block</td></tr>
    <tr><td>Rerun on deadlock</td><td><code>@Retryable</code> on a method around the transaction</td><td><code>retries = 2</code> on the block</td></tr>
    <tr><td>Suspend functions</td><td>Need a <code>ReactiveTransactionManager</code></td><td><code>transaction { }</code> is a suspend function</td></tr>
  </table>

  <h2><span class="hno">09</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/transactions">Transactions</a>
    <a href="/docs/spring-integration">Spring Integration</a>
    <a href="/docs/error-handling">Error Handling</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function TransactionsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="transactions" body={BODY} />;
}
