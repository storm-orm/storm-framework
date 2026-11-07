import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = "Why shouldn't we use an ORM? The case for visible SQL";
const DESC =
  'The strongest arguments against ORMs turn out to be arguments against ORMs ' +
  'that hide SQL. Remove the concealment and most of the case goes with it, ' +
  'which leaves room for a third option.';
const SLUG = 'should-you-use-an-orm';
const DATE = '2026-06-09';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>Why shouldn't we use an ORM?</div>
  <h1>Why <span class="grad">shouldn't</span> we use an ORM?</h1>
  <p class="dek">It is a fair question, and the people who ask it usually have scars to back it up. Listen to the complaints, though, and very few of them are about mapping rows to types. Most are about everything else the ORM does.</p>
  <div class="meta"><span>June 9, 2026</span><span>Opinion</span><span>5 min read</span></div>

  <h2>The strongest case against ORMs</h2>
  <p>The argument deserves to be made properly. ORMs hide the query, so you cannot see what runs against your database. They make performance opaque, so an <a class="tlink" href="/blog/n-plus-one-query-problem">N+1</a> or a missing index shows up in production instead of in review. In the JPA world they often put JPQL or a criteria API between you and the SQL you already know, and that layer leaves you on your own once you need something it cannot express. They also tie your objects to a session lifecycle, which is where <a class="tlink" href="/blog/lazyinitializationexception">a whole genre of runtime errors</a> comes from.</p>
  <p>There is truth in every one of those complaints. If that were the whole story, the answer would be easy: drop the ORM and write SQL by hand.</p>

  <h2>Notice what the complaints have in common</h2>
  <p>None of them is about mapping. Connecting a plain record to a table, binding a parameter safely, turning a result row into a typed value: nobody writes an angry post about that part, because it is useful and stays out of the way. What the complaints share is concealment. You cannot see the query or track the state, and you have to keep the lifecycle in your head. People are objecting to the hiding.</p>
  <p>That matters because mapping does not require hiding anything. The two were bundled together historically, and an ORM can keep the ergonomics of mapping while leaving the SQL and the state in view.</p>
  <p>None of this makes an ORM that hides things wrong. We <a class="tlink" href="/blog/why-we-built-storm">reached for Hibernate by default for years</a>, and liked it. Hidden machinery is convenient until the day you need to see what it is doing, and in our experience that day tends to come in production.</p>

  <h2>A third option</h2>
  <p>ST/ORM keeps the mapping and leaves out the concealment. It maps rows to <a class="tlink" href="/blog/entities-should-be-values">plain values</a>, and conventions keep the mapping concise, so you are not hand-writing result mapping or annotating every column. The SQL stays in view. A type-safe DSL handles the boring, high-volume queries, and real SQL templates handle the ones where SQL should stay SQL, with type-checked column references and parameters bound automatically. There is no JPQL to learn and no proxy to outlive.</p>
  <p>Our answer to "why shouldn't we use an ORM?" is to avoid the kind that hides your SQL and your state and asks you to reason about a lifecycle you cannot see. We agree with that position, and it describes the kind of ORM we chose not to build.</p>

  <div class="cta">
    <a href="/docs/sql-templates" class="btn primary">See SQL templates →</a>
    <a href="/blog/stop-hiding-my-sql" class="btn">Stop hiding my SQL</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
