import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'Three abstractions and nothing else: the ST/ORM model';
const DESC =
  'ST/ORM has three user-facing concepts: Entity, Repository, and SQL Template. ' +
  'We kept the model that small so you can hold all of it in your head while ' +
  'you work.';
const SLUG = 'three-abstractions';
const DATE = '2026-02-17';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>Three abstractions</div>
  <h1><span class="grad">Three abstractions</span> and nothing else</h1>
  <p class="dek">Framework complexity is easy to mistake for capability, and the parts of a model you cannot keep in your head tend to show up later in a debugger. ST/ORM has three user-facing concepts, and together they cover the whole framework.</p>
  <div class="meta"><span>February 17, 2026</span><span>Design</span><span>4 min read</span></div>

  <h2>Entity</h2>
  <p>An entity is the data your application works with: a Kotlin data class or a Java record with a couple of annotations, <code>@PK</code> for the primary key and <code>@FK</code> for a foreign key. ST/ORM maps entities to tables and columns by convention, so further annotations only appear where the convention does not fit. An entity has no hidden state and no behavior. It is <a class="tlink" href="/blog/entities-should-be-values">database-backed data as a plain value</a>.</p>

  <h2>Repository</h2>
  <p>A repository gives you CRUD operations and type-safe queries for one entity. You define an interface and write the bodies of its query methods with the DSL. ST/ORM does not parse a method name like <code>findByStatusAndCreatedAtAfter</code> into a hidden query, and there is no generated query you have to reverse-engineer from a log. Any query a repository runs is one you wrote and can read.</p>

  <h2>SQL Template</h2>
  <p>SQL templates give you direct SQL with type-safe binding and result mapping, for the cases where the DSL is the wrong tool: joins, reports, database-specific features, or queries that should stay SQL. A template sits beside the DSL rather than under it, and using one is an ordinary design choice. Type references and metamodel columns keep it checked, and parameters are bound automatically. The reasoning behind it is in <a class="tlink" href="/blog/stop-hiding-my-sql">keeping SQL in view rather than hiding it</a>.</p>

  <h2>Why a small model is the point</h2>
  <p>There is more underneath, of course: transactions, a static metamodel, change detection, generated code. All of it exists to support these three concepts, and none of it adds a fourth one you have to learn.</p>
  <p>The three follow one rule, which is that what happens can be seen in the code. No query runs that you did not write, the SQL each one produces is predictable, relationships load when you ask for them, and transaction boundaries are declared instead of inferred. That makes the model something you can teach a new engineer in an afternoon, and you never have to reconstruct what the framework decided to do on your behalf.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/docs/repositories" class="btn">Repositories</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
