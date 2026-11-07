import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'The N+1 query problem in ORMs, and loading you can see';
const DESC =
  'The N+1 query problem keeps returning as long as the fix is a fetch clause ' +
  'someone has to remember. ST/ORM makes loading a decision you can see in the ' +
  'type and in the source.';
const SLUG = 'n-plus-one-query-problem';
const DATE = '2025-12-16';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>The N+1 problem</div>
  <h1>The N+1 problem, and <span class="grad">loading you can see</span></h1>
  <p class="dek">The N+1 problem keeps coming back in codebases that have already fixed it more than once. Carelessness has little to do with it. The default hides the queries, and the fix depends on someone remembering it.</p>
  <div class="meta"><span>December 16, 2025</span><span>Hibernate</span><span>4 min read</span></div>

  <h2>The problem that keeps coming back</h2>
  <p>Say you render fifty users with the name of each user's city, from two tables joined by one foreign key. The list query fetches the users, and then each read of user.city.name runs its own select, so one query becomes fifty-one. The types and the compiler give no warning, because nothing is broken. The code does what the framework's default asks, which is to load the city the moment you read it. You find out from the query log or from production latency, and you fix it with a JOIN FETCH or an entity graph, both of which work. A few weeks later someone writes a new query for a new screen, forgets the fetch clause, and the problem is back. The team has fixed this exact problem before and will fix it again.</p>

  <h2>Why it keeps returning</h2>
  <p>The fix lives in the wrong place. The mapping declares one loading behavior, individual queries override it at each call site, and the compiler checks none of it. Every new query is another chance to forget, and forgetting makes no noise.</p>

  <h2>Put the loading decision in the type</h2>
  <p>ST/ORM takes the loading decision out of the query and puts it in the entity, as a type. It is the same idea that makes <a class="tlink" href="/blog/entities-should-be-values">entities plain data</a>. A single-valued foreign key written as its plain entity is always loaded with the row, through a join: write <code>@FK val city: City</code> and the city comes back with the user. That field has no lazy variant, so there is nothing to forget, and every caller gets the same behavior. When deferring the load is the right call, the type says so. Declare the field as <code>@FK val city: Ref&lt;City&gt;</code> and ST/ORM reads only the foreign key until you fetch the city on purpose. Both the compiler and the reviewer can see which of the two you chose. A loop that fetches per row shows its extra queries in the source, where a reviewer will spot them, instead of hiding them inside a getter.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/blog/" class="btn">More from the blog</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
