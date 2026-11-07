import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'The M in ORM stands for mapping, not management';
const DESC =
  'Ask what the M in ORM stands for and everyone answers mapping. Most of what ' +
  'the tools carrying the name do looks more like management, and you should ' +
  'be able to want the mapping without the rest.';
const SLUG = 'mapping-not-management';
const DATE = '2026-07-24';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>The M in ORM</div>
  <h1>The M in ORM stands for <span class="grad">…</span></h1>
  <p class="dek">Ask anyone what the M stands for and they will say mapping, a translation between relational data and typed values. Watch what the tools carrying the name spend their day on, though, and you see something else.</p>
  <div class="meta"><span>July 24, 2026</span><span>Opinion</span><span>7 min read</span></div>

  <h2>What the words actually say</h2>
  <p>Read the term literally. On one side are tables, rows and keys, and on the other the types and references of your language. Mapping translates between the two in both directions. Reads turn rows into typed values. Writes turn values back into statements, in the right order, with generated keys ending up where they need to go.</p>
  <p>That work is not trivial. Hydrating results, deduplicating repeated rows, ordering writes by their dependencies, batching and propagating keys all take real effort, and any serious mapper has to handle them. None of it requires your objects to have a lifecycle.</p>
  <p>The name says nothing about sessions, proxies or flushing. We call that machinery management.</p>

  <h2>What management means, precisely</h2>
  <p>By management we mean a persistence context that outlives individual operations and holds your entities. The context tracks which objects are attached, watches them for mutation, keeps an identity map across operations, puts proxies in place of rows you have not read yet, and decides at flush time which of your changes become SQL, and in what order.</p>
  <p>That makes it a stateful runtime that owns your objects between statements. Short-lived coordination inside a single query or write is part of the mapper's job, and we have no quarrel with it. This post is about the long-lived kind.</p>

  <h2>How the label drifted</h2>
  <p>The history is worth getting right. TopLink was doing identity maps and units of work in the nineties. Hibernate arrived in 2001 and made that persistence model the default mental image of the word ORM. Fowler catalogued the underlying patterns in 2002 (Data Mapper, Unit of Work, Identity Map, Lazy Load), and JPA later standardized a lifecycle and API in the same family. After twenty years of that, the M might as well stand for management.</p>
  <p>Keep that in mind when you listen to the standard complaints. <a class="tlink" href="/blog/n-plus-one-query-problem">N+1 queries</a> usually trace back to implicit lazy loading of associations, though nothing stops you from writing one by hand. The <a class="tlink" href="/blog/lazyinitializationexception">LazyInitializationException</a> is a session-lifecycle error by definition. A surprising UPDATE can come from dirty tracking, a cascade, relationship synchronization or flush timing, and debugging it goes the same way every time, because the mutation happened in one place and the SQL in another. Autoflush makes that gap structural, even where an explicit flush is available.</p>
  <p>None of these complaints are about mapping, and mapping has no comparable list of famous failures. A mapper has no reason to hide when database work happens, and that hiding is where most of the resentment comes from. We <a class="tlink" href="/blog/should-you-use-an-orm">made a similar point about hidden SQL</a> before.</p>
  <p>This is not limited to the JVM. Every ecosystem with a relational database has ended up at the same fork, and the vocabulary shows it: the term "micro-ORM" exists because someone needed a word for "just the mapping, please".</p>

  <h2>Convenient, until it isn't</h2>
  <p>To be fair, management gives you real things in return. You can walk an object graph as if it were in memory, configure cascades once instead of ordering writes by hand, and get batching at flush time without thinking about it. The managers implement all of this deliberately, and mostly well.</p>
  <p>You pay for it, though. Your objects gain attached and detached states, along with the ceremony for moving between them. Identity is scoped to the context, so equality across contexts becomes something you have to design for. Unless you step in, the flusher decides when a change becomes SQL. And the failure modes have names of their own, which is rarely a good sign for an abstraction.</p>
  <p>There is nothing wrong with an ORM that also manages, and we <a class="tlink" href="/blog/why-we-built-storm">reached for one by default for years</a>. With a growing model, though, the moment it stops being convenient tends to arrive in production. Our objection is to the label, which says mapping while what you usually get is management.</p>

  <h2>Both directions, no context</h2>
  <p>The fair question is whether mapping alone gets you far enough. We think it does, as long as you take both directions seriously. In ST/ORM, a three-table join hydrates into <a class="tlink" href="/blog/entities-should-be-values">immutable nested values</a>, such as an owner holding its pets. Repeated rows resolve to the same instance by primary key during hydration, which gives you the useful half of an identity map without its lifetime. A write set runs the translation the other way. You hand it a mixed collection of values, and it partitions them by type, orders the types by their foreign key dependencies, writes each level as a batch, and propagates generated keys into the rows that reference them. Updates <a class="tlink" href="/blog/dirty-checking-without-proxies">skip unchanged rows</a> by comparing the value being written against the value observed in the same transaction. Nothing gets attached or merged, and no dirty-tracked entity sits around waiting for a flush.</p>

  <h2>One without the other</h2>
  <p>Sorted by what they do rather than what they call themselves, the tools fall into three groups. First come the persistence-context ORMs: Hibernate and JPA, and the entity layers that follow their model. Then the SQL-shaped query tools and row mappers such as jOOQ, JDBI and MyBatis, which translate queries and results but stop short of graph persistence. jOOQ pointedly declines the ORM label, and that is a reasonable position. The third group is sessionless graph mapping, which is where ST/ORM sits.</p>
  <p>We are not trying to police who gets to use the word. The extras are real features, and choosing them is not a mistake. We only think nobody should have to avoid the word ORM because they do not want those features. You should be able to want the mapping without the management.</p>
  <p>ST/ORM maps objects and does not manage them, which is what the letters spelled in the first place.</p>

  <div class="cta">
    <a href="/docs/write-sets" class="btn primary">See write sets →</a>
    <a href="/blog/entities-should-be-values" class="btn">Entities should be values</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
