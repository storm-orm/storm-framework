import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';
import {editor, K, T, A, P} from '../../components/tutorial/tutorialTheme';

const TITLE = 'A first-principles approach to a JVM data layer';
const DESC =
  'Start from the most concise way to work with a database table and follow it ' +
  'to a complete data layer without adding anything. The smallest typed shape ' +
  'ends up carrying your data and serving as the contract for querying the ' +
  'relation graph.';
const SLUG = 'data-layer-first-principles';
const DATE = '2025-09-30';

const CODE_CITY = [
  K('data class '), T('City'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), K('val '), P('country: '), T('String'), P('\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>'),
].join('');

const CODE_USER = [
  K('data class '), T('User'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('city: '), T('City'), P('\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>'),
].join('');

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>A first-principles approach</div>
  <h1>A first-principles approach to <span class="grad">your data layer</span></h1>
  <p class="dek">Take the most concise thing you can write to work with a database table and follow where it leads, without adding anything along the way. That one small definition keeps answering the next problem, until it adds up to a whole data layer.</p>
  <div class="meta"><span>September 30, 2025</span><span>Design</span><span>4 min read</span></div>

  <h2>The simplest thing you can write</h2>
  <p>Start at the bottom. The database owns the data model, and the application needs a concise, typed way to work with it. The least you can write to make a database row usable in application code is its shape, a typed and named set of fields. In a modern language that is a record or a data class.</p>
  ${editor({file: 'City.kt', tag: 'Kotlin', code: CODE_CITY})}
  <p>The names come from convention, <code>Entity</code> marks the class as a table-backed entity ST/ORM can read and write, and Kotlin nullability says which values may be absent in application code. That is the entire application-facing shape. The first principle is to keep it this small: the data and nothing else, with no hidden lifecycle, behavior, or framework state attached. The rest of this post follows from refusing to add to it.</p>

  <h2>It happens to be the perfect carrier</h2>
  <p>The first thing you get for free is a carrier. A plain data class is also the best way to move the data around, and since nothing is attached to it, it passes through every layer unchanged: out of the repository, into your services, through the controller, out to a serializer, across a thread boundary, and back. You did not have to design a carrier, because the smallest useful shape already is one, and what you loaded is what you pass.</p>

  <h2>And it queries the whole graph</h2>
  <p>A relationship can be part of that shape too. If a user always needs its city, the field is typed as <code>City</code>; if the load should be deferred, it is typed as <code>Ref&lt;City&gt;</code>.</p>
  ${editor({file: 'User.kt', tag: 'Kotlin', code: CODE_USER})}
  <p>With relationships in the shape, the definition that carries the data also describes how to query it. ST/ORM generates a metamodel from it, and you can follow those relationships across the relation graph. <code>users.findAll(User_.city.name eq "Sunnyvale")</code> reaches from a user to its city in one line, type-checked, with the join derived from the relationship path instead of written by hand at every call site.</p>

  <h2>What falls out of keeping it small</h2>
  <p>The same small shape ends up doing jobs that usually take separate pieces you write, map, and keep in sync. It is the value your application passes around, the typed contract your queries compile against, and the path into the relation graph. In ST/ORM all of that comes from one decision: keep the application-facing view of database data small, typed, and free of hidden state.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/docs/first-query" class="btn">See the one-line queries</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
