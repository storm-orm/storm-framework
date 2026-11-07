import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'Entities are just data: immutable records, not managed objects';
const DESC =
  'In ST/ORM an entity is a plain record, an immutable value with no session ' +
  'and no hidden state. This post covers what that gives you across an ' +
  'application, and how dirty checking and lazy loading still work.';
const SLUG = 'entities-should-be-values';
const DATE = '2025-10-28';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>Entities are just data</div>
  <h1>Entities are just <span class="grad">data</span></h1>
  <p class="dek">In <a class="tlink" href="/blog/why-we-built-storm">the story of how ST/ORM came to be</a>, we kept saying that an entity is a plain record. That sounds too simple to matter, yet the rest of the design leans on it.</p>
  <div class="meta"><span>October 28, 2025</span><span>Design</span><span>4 min read</span></div>

  <h2>A value, not an object</h2>
  <p>A record, or an immutable Kotlin data class, is a value. It has no object identity beyond the fields it holds, and two of them with the same contents are equal. You can copy one, put it in a set, hand it to another thread, or pass it to a serializer without risk, because nothing hidden is there to disturb and it has no lifecycle to violate. The source shows everything that exists at runtime. An entity in ST/ORM is exactly this and nothing more, the same plain shape we followed to its conclusion in <a class="tlink" href="/blog/data-layer-first-principles">a first-principles look at the data layer</a>.</p>

  <h2>The words you stop needing</h2>
  <p>A managed entity comes with a vocabulary: attached and detached, the persistence context, flush, merge, cascade. Those words describe the machinery that tracks a mutable object across a session, so the framework can work out what changed and when to write it, and none of them says anything about your data. When the entity is a value, most of that machinery has nothing to attach to, and the words drop out of your codebase. What you reason about is the data itself.</p>

  <h2>What that lets you do</h2>
  <p>Because the entity carries no session and no persistence state, you can pass it anywhere: out of the repository, through your services, into a controller, across a coroutine boundary, and back. A service that returns an entity does not also return an invisible dependency on an open session. There is no session for it to outlive, so the error a managed model throws after its session closes cannot happen here. You can cache an entity, since it will not change under you, and serialize it, since it consists of its fields.</p>

  <h2>What you do not give up</h2>
  <p>Two worries usually come up at this point, dirty checking and lazy loading, and immutability costs you neither. You change an entity by producing a new copy, and ST/ORM writes only the columns that differ by comparing the value you saved against the one you read, with no proxy involved. When deferring a load is the right call, you declare the field as a Ref and load it with a call you make, in code a reviewer can see. Both are still there. They are explicit where a managed model keeps them hidden, which is much of the reason to want plain values in the first place.</p>

  <p>That is the bet behind ST/ORM. The database owns the data model, and the application gets plain typed values it can pass around, test, and cache without asking what session they belong to.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/blog/" class="btn">More from the blog</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
