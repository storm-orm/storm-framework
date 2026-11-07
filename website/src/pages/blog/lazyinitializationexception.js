import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE =
  "LazyInitializationException: what it means, and why value entities can't throw it";
const DESC =
  'LazyInitializationException is the most-googled Hibernate error. It does ' +
  'not point at a bug in your code: a lazy association was resolved after its ' +
  'session closed. Value-based entities have no such association and cannot throw it.';
const SLUG = 'lazyinitializationexception';
const DATE = '2025-11-25';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>LazyInitializationException</div>
  <h1><span class="grad">LazyInitializationException</span>, and what it's telling you</h1>
  <p class="dek">It is one of the most searched Hibernate errors, and anyone who uses Hibernate long enough runs into it. Knowing what it actually is helps, because it does not point at a mistake in your code. The model throws it while working exactly as designed.</p>
  <div class="meta"><span>November 25, 2025</span><span>Hibernate</span><span>4 min read</span></div>

  <h2>The error everyone meets</h2>
  <p>You load an entity in a service method and pass it up to a controller or a template, where something touches a lazy relationship. Out comes LazyInitializationException: could not initialize proxy, no Session. The usual fixes are open-session-in-view, a JOIN FETCH, or mapping to a DTO before you leave the transaction. Each of them works, though none of them explains what went wrong.</p>

  <h2>Where it actually comes from</h2>
  <p>A lazy association holds no data yet. It is a promise to call the database later. The proxy that stands in for the related object keeps a reference to the session that made the promise, and touching the proxy is what makes it try to keep it. If that session has already closed, nothing is left to run the query with, so the proxy throws. Every workaround above changes when that call happens relative to the session: the session stays open longer, the load happens earlier, or the data is copied out before the session ends. None of them repairs a bug, since there was none. They schedule your code around a design decision.</p>

  <h2>Data cannot throw it</h2>
  <p>ST/ORM makes a different choice at the root, the one behind <a class="tlink" href="/blog/entities-should-be-values">treating entities as plain data</a>. An entity is a value, and it is complete when you receive it. A field typed as its entity was loaded in the same query, through a join. A field you want to defer is a Ref, which holds the foreign key and nothing else, and you load it with a call you write in your own code. With nothing to stand in for, there is no proxy, and the value never belonged to a session it could outlive. You can return it, serialize it, cache it, or hand it to another thread, and none of that can produce a LazyInitializationException, because the conditions that create one never arise.</p>

  <h2>The trade</h2>
  <p>You give up transparent lazy loading. In exchange, the most reliable error in the Hibernate world is no longer something you design around, because your code has no way to produce it.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/blog/" class="btn">More from the blog</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
