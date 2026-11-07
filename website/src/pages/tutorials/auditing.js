import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: auditing with entity callbacks. Storm-way how-to; verified against
// st.orm.EntityCallback (before* returns the entity, beforeUpsert defaults to
// beforeInsert, after* receive what the calling method reports) and
// docs/entity-lifecycle.md (@EntityCallbacks, withEntityCallback, Spring beans,
// Ktor entityCallback, the update() builder fires no callbacks).

const TITLE = 'Auditing with Entity Callbacks';
const DESC =
  'Set createdAt and updatedAt on every write with an EntityCallback. Entities are ' +
  'immutable, so a before-hook returns a changed copy, and the entity, the template, ' +
  'Spring or Ktor registers it.';

const CODE_CALLBACK = [
  K('data class '), T('Owner'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('firstName: '), T('String'), P(',\n'),
  P('    '), K('val '), P('lastName: '), T('String'), P(',\n'),
  P('    '), A('@Persist'), P('(updatable = '), K('false'), P(') '), K('val '), P('createdAt: '), T('Instant'), P('? = '), K('null'), P(',\n'),
  P('    '), K('val '), P('updatedAt: '), T('Instant'), P('? = '), K('null'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  C('// A before-hook returns the entity to write\n'),
  K('class '), T('AuditCallback'), P(' : '), T('EntityCallback'), P('<'), T('Owner'), P('> {\n\n'),
  P('    '), K('override fun '), F('beforeInsert'), P('(entity: '), T('Owner'), P('): '), T('Owner'), P(' {\n'),
  P('        '), K('val '), P('now = '), T('Instant'), P('.'), F('now'), P('()\n'),
  P('        '), K('return'), P(' entity.'), F('copy'), P('(createdAt = now, updatedAt = now)\n'),
  P('    }\n\n'),
  P('    '), K('override fun '), F('beforeUpdate'), P('(entity: '), T('Owner'), P('): '), T('Owner'), P(' =\n'),
  P('        entity.'), F('copy'), P('(updatedAt = '), T('Instant'), P('.'), F('now'), P('())\n'),
  P('}'),
].join('');

const CODE_REGISTER = [
  C('// A new template with the callback; the original is unchanged\n'),
  K('val '), P('orm = dataSource.orm.'), F('withEntityCallback'), P('('), T('AuditCallback'), P('())\n\n'),
  K('val '), P('owner = orm '), K('insert '), T('Owner'), P('(firstName = '), S('"Betty"'), P(', lastName = '), S('"Davis"'), P(')\n'),
  C('// owner.createdAt and owner.updatedAt are set'),
].join('');

const CODE_VALIDATION = [
  C('// A before-hook runs ahead of the statement, so it can refuse the write\n'),
  K('class '), T('OwnerValidation'), P(' : '), T('EntityCallback'), P('<'), T('Owner'), P('> {\n'),
  P('    '), K('override fun '), F('beforeInsert'), P('(entity: '), T('Owner'), P('): '), T('Owner'), P(' {\n'),
  P('        '), F('require'), P('(entity.lastName.'), F('isNotBlank'), P('()) { '), S('"Last name must not be blank"'), P(' }\n'),
  P('        '), K('return'), P(' entity\n'),
  P('    }\n'),
  P('}\n\n'),
  C('// Callbacks fire in registration order\n'),
  K('val '), P('orm = dataSource.orm\n'),
  P('    .'), F('withEntityCallback'), P('('), T('OwnerValidation'), P('())\n'),
  P('    .'), F('withEntityCallback'), P('('), T('AuditCallback'), P('())'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Auditing</div>
  <h1>Auditing with <span class="grad">entity callbacks</span></h1>
  <p class="dek">Most tables carry createdAt and updatedAt, and service code should not set them by hand. An EntityCallback runs on the write path. Entities are immutable, so the callback returns a changed copy instead of mutating the one it was given.</p>
  <div class="meta"><span>Series · The Storm way</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>Stamp audit timestamps on every insert and update of an owner, refuse an owner without a last name, and keep both out of the service code that writes owners.</p>

  <h2><span class="hno">02</span>The audit callback</h2>
  <p><code>EntityCallback&lt;E&gt;</code> is typed to the entity it applies to, with a hook before and after each insert, update, upsert and remove. The before-hooks for insert, update and upsert return the entity that is written:</p>
  ${editor({file: 'AuditCallback.kt', tag: 'Kotlin · Storm', code: CODE_CALLBACK})}
  <p><code>@Persist(updatable = false)</code> keeps <code>createdAt</code> out of every UPDATE, so an update from a form that does not carry it leaves the stored value alone. An upsert runs <code>beforeInsert</code> unless the callback overrides <code>beforeUpsert</code>, and its update branch skips <code>createdAt</code> for the same reason.</p>

  <h2><span class="hno">03</span>Registration</h2>
  <p>Register the callback on a template. <code>withEntityCallback</code> returns a new template that applies it; the original is unchanged:</p>
  ${editor({file: 'Setup.kt', tag: 'Kotlin · Storm', code: CODE_REGISTER})}
  <p>The entity can declare it instead, with <code>@EntityCallbacks(AuditCallback::class)</code>, and then it applies on every template. With the Spring Boot starter a callback bean is registered for you; the Ktor plugin takes <code>entityCallback(AuditCallback())</code> in its configuration.</p>
  <p>The after-hooks receive what the call returns: <code>insert</code> passes the entity as sent, without a generated id; <code>insertAndFetchId</code> adds the id; <code>insertAndFetch</code>, which Kotlin's <code>orm insert</code> calls, passes the row as read back.</p>
  <p>The <code>update()</code> query builder sets columns on rows it does not read, so no callback fires for it. Write <code>set(Owner_.updatedAt, ...)</code> into the builder yourself.</p>

  <h2><span class="hno">04</span>Validation</h2>
  <p>A before-hook runs before the statement, so it can also refuse a write with an error in domain terms. Several callbacks fire in the order they were registered:</p>
  ${editor({file: 'Callbacks.kt', tag: 'Kotlin · Storm', code: CODE_VALIDATION})}
  <p>To audit many entity types with one callback, register an <code>EntityCallback&lt;Entity&lt;*&gt;&gt;</code> that handles a shared <code>Auditable</code> interface. The <a class="tlink" href="/docs/entity-lifecycle">lifecycle docs</a> show that pattern and how callbacks route for upserts.</p>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/entity-lifecycle">Entity Lifecycle</a>
    <a href="/docs/entities">Entities</a>
    <a href="/docs/validation">Validation</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function AuditingTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="auditing" body={BODY} />;
}
