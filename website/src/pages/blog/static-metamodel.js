import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'The static metamodel behind ST/ORM: type-safe queries without reflection';
const DESC =
  'ST/ORM generates a static metamodel from each entity at compile time. ' +
  'Column and path references become type-checked, and queries and hydration ' +
  'run on generated code instead of reflection.';
const SLUG = 'static-metamodel';
const DATE = '2026-03-03';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>The static metamodel</div>
  <h1>The static <span class="grad">metamodel</span></h1>
  <p class="dek">Every entity you write gets a metamodel class generated at compile time. It is a small part of the design, and it is why type safety and speed in ST/ORM come from the same decision.</p>
  <div class="meta"><span>March 3, 2026</span><span>Internals</span><span>4 min read</span></div>

  <h2>A generated metamodel class</h2>
  <p>For an entity like <code>City</code>, ST/ORM generates a small metamodel class, <code>City_</code>, at compile time, and you write nothing in it by hand. The metamodel does not define the data model. It exposes the database-backed shape of the entity to application code, with one typed handle per field and one per relationship for the columns and paths you can reach from that row. It is regenerated whenever the entity changes, so it stays in step with the entity it describes.</p>

  <h2>Type safety you cannot forget to ask for</h2>
  <p>Those handles are real, typed code, so the compiler checks every reference a query is built from. In <code>User_.city.name eq "Sunnyvale"</code> the path is one the compiler knows, where a string would only happen to match a column. A renamed field, a broken path, or a wrong type becomes a build error instead of something you find in production. This is the same generated metamodel that lets <a class="tlink" href="/blog/data-layer-first-principles">a relationship become a query across the graph</a>, and that keeps <a class="tlink" href="/blog/stop-hiding-my-sql">real SQL templates checked</a> against known fields and types. There is nothing to opt in to, since the references only exist in this typed form.</p>

  <h2>Generated paths, not reflection</h2>
  <p>The other half is what the runtime no longer has to do. Many ORMs discover your fields by reflection: while the program runs, they ask the class what it has, then read and write fields through that reflective handle. This works, but it is slower than plain field access, and it moves a class of errors from the compiler to the first request that reaches the path. ST/ORM already holds the metamodel as generated code, so the paths are known ahead of time and reading or writing a field is direct generated access, with no per-row reflective lookup in the way.</p>

  <h2>Where it pays off: hydration</h2>
  <p>You notice this most in hydration, the step that turns a result row into an entity. It runs for every row of every query, so it has to be fast. With a generated metamodel, hydration is generated code as well. Each column is read through a known path into the right constructor argument, and nothing asks the class at runtime what to do with it. Without per-row reflection to pay for, the mapping runs close to the cost of the work itself.</p>

  <h2>One decision</h2>
  <p>Both properties come from generating the model's shape once, at compile time. The compiler checks your queries against it, and the runtime skips reflection entirely, so there are no two features to balance against each other. Everything downstream, from a query predicate to change detection, uses those generated paths instead of discovering fields at runtime.</p>

  <div class="cta">
    <a href="/docs/metamodel" class="btn primary">The metamodel →</a>
    <a href="/blog/" class="btn">More from the blog</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
