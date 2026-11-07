import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import {TUT_CSS, navHtml, FOOT_HTML, heroArt} from '../../components/tutorial/tutorialTheme';

// The tutorial hub at /tutorials, rendered in the landing-page style (see
// tutorialTheme.js). Three series: "From JPA" and "From Exposed" compare the
// familiar approach with the Storm approach; "The Storm way" is task recipes
// for people already using Storm. Each tutorial page lives next to this file.

const TITLE = 'ST/ORM Tutorials · Familiar persistence tasks, the Storm way';
const DESC =
  'Task-focused tutorials for engineers coming from JPA, Hibernate, or Exposed, ' +
  'plus how-to recipes for Storm itself. Each comparison takes a familiar ' +
  'persistence problem, shows both approaches, and shows the SQL they produce.';

const BODY = `
${navHtml('tutorials')}

<div class="pagehero">
  <h1>Familiar Tasks.<br><span class="grad">Simpler Solutions.</span></h1>
  <p class="sub">Task-focused tutorials for engineers coming from JPA, Hibernate, or Exposed, plus how-to recipes for Storm itself. Each comparison takes a persistence task you already know, shows both approaches side by side, and lets you inspect the SQL they produce.</p>
  <div class="catnav">
    <a href="" class="on" data-all="1" aria-current="true">All<b>26</b></a>
    <a href="#start-here">Start here<b>1</b></a>
    <a href="#from-jpa">From JPA<b>10</b></a>
    <a href="#from-exposed">From Exposed<b>7</b></a>
    <a href="#storm-way">The Storm way<b>8</b></a>
  </div>
  ${heroArt('tutorials', {priority: true})}
</div>

<div class="shead" id="start-here"><span class="mark">//</span>Start here<span class="sdesc">New to Storm? Build a complete REST API from an empty project, then use the task recipes below as you go.</span></div>
<div class="cards">
  <a class="tcard" href="/tutorials/build-a-rest-api" style="grid-column:1 / -1">
    <div class="tt">Build a REST API from scratch<span class="arrow">→</span></div>
    <div class="td">Empty project to a running, tested API: two entities, their relationship loaded in one query, CRUD routes on Ktor, and a test that asserts on the SQL. About twenty minutes.</div>
    <div class="tm"><span>Kotlin</span><span>Ktor 3</span><span>~20 min</span></div>
  </a>
</div>

<div class="shead" id="from-jpa"><span class="mark">//</span>From JPA<span class="sdesc">You know Spring Data JPA and Hibernate. Each tutorial shows a task the JPA way and the Storm way, side by side.</span></div>
<div class="cards">
  <a class="tcard" href="/tutorials/n-plus-one">
    <div class="tt">Solving the N+1 problem<span class="arrow">→</span></div>
    <div class="td">Why a JPA list query over a lazy relation runs 1 + N selects, what JOIN FETCH, entity graphs and batch fetching change, and how Storm decides loading in the entity model.</div>
    <div class="tm"><span>JPA to Storm</span><span>7 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/projections">
    <div class="tt">Projections: read-only views on your data<span class="arrow">→</span></div>
    <div class="td">A Storm projection is a read-only type over a table, a database view or a SQL query, with a repository without write methods, typed predicates and nesting. In JPA the same job falls to Hibernate's @Immutable and @Subselect.</div>
    <div class="tm"><span>JPA to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/query-results">
    <div class="tt">Typed query results without an entity<span class="arrow">→</span></div>
    <div class="td">Spring Data JPA shapes custom results with interface projections and constructor expressions. In Storm, any data class whose constructor matches the selected columns is a result type, for the query builder and for full SQL.</div>
    <div class="tm"><span>JPA to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/pagination">
    <div class="tt">Pagination and keyset scrolling<span class="arrow">→</span></div>
    <div class="td">Offset pages cost more the deeper they go, and a total takes a count query. Storm reads pages and slices by offset, scrolls by keyset with cursors a client sends back, and logs advice when a count is slow or an offset is deep.</div>
    <div class="tm"><span>JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/upserts">
    <div class="tt">Upserts in one statement<span class="arrow">→</span></div>
    <div class="td">Spring Data JPA saves an entity with an assigned key by reading it first. Storm sends the database's own upsert, keyed on the primary key, on every supported database.</div>
    <div class="tm"><span>JPA to Storm</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/optimistic-locking">
    <div class="tt">Optimistic locking with immutable entities<span class="arrow">→</span></div>
    <div class="td">JPA checks the version when the persistence context flushes, usually at commit. Storm checks it on the update call, with the version the entity carries, and transaction(retries = 2) reruns a block that lost a conflict.</div>
    <div class="tm"><span>JPA to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/transactions">
    <div class="tt">Transactions as blocks<span class="arrow">→</span></div>
    <div class="td">@Transactional applies through a proxy, which skips self-invocation in its default mode. Storm's transaction blocks put the scope in the code, register onCommit callbacks inline, rerun on deadlocks with retries, and run as suspend functions.</div>
    <div class="tm"><span>JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/mapped-collections">
    <div class="tt">Mapped collections vs queried associations<span class="arrow">→</span></div>
    <div class="td">owner.pets is one property access. Storm queries the association instead: one line that loads when you call it, takes filters, paging and many owners at once, and keeps working when the join table gains columns.</div>
    <div class="tm"><span>JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/object-graphs">
    <div class="tt">Persisting object graphs: cascades vs write sets<span class="arrow">→</span></div>
    <div class="td">CascadeType configures, once per mapping, how far every persist travels. A Storm write set decides per call: pass the entities, and Storm inserts their unsaved parents first, in dependency order, with generated keys filled in.</div>
    <div class="tm"><span>JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/sql-templates">
    <div class="tt">Full SQL with your model<span class="arrow">→</span></div>
    <div class="td">A native query is SQL text the entity model does not see. Storm's SQL templates bind interpolated values as parameters, map rows onto data classes, and expand entity types and metamodel paths into columns, joins and aliases.</div>
    <div class="tm"><span>JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>
  </a>
</div>

<div class="shead" id="from-exposed"><span class="mark">//</span>From Exposed<span class="sdesc">You know and like JetBrains Exposed, with good reason. Same format: the Exposed way next to the Storm way.</span></div>
<div class="cards">
  <a class="tcard" href="/tutorials/exposed-entities">
    <div class="tt">One model instead of three<span class="arrow">→</span></div>
    <div class="td">Exposed and Storm are both type-safe Kotlin data layers. They differ in where joins and row mappings live: Exposed writes them with each query, Storm declares them once in the data class.</div>
    <div class="tm"><span>Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/exposed-ktor">
    <div class="tt">Ktor services, side by side<span class="arrow">→</span></div>
    <div class="td">Exposed needs a transaction around every operation, reads included. Storm installs as a plugin, reads without one, and covers writes with suspend transaction { } blocks and transactional { } routes.</div>
    <div class="tm"><span>Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/exposed-n-plus-one">
    <div class="tt">Eager loading and N+1<span class="arrow">→</span></div>
    <div class="td">Exposed DAO references load lazily, and .with() batches them into one query at each call site. Storm decides loading in the model: an entity field is joined, a Ref field loads when your code asks.</div>
    <div class="tm"><span>Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/exposed-transactions">
    <div class="tt">Transactions translate almost one to one<span class="arrow">→</span></div>
    <div class="td">Exposed and Storm agree that transactions are explicit blocks. A translation table for the settings you know, plus where they differ: what reruns and timeouts cover, propagation per block, and after-commit callbacks.</div>
    <div class="tm"><span>Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/exposed-queries">
    <div class="tt">Queries and full SQL<span class="arrow">→</span></div>
    <div class="td">Everyday Exposed queries translate to Storm almost line by line, without the row mapping. For SQL written as text, exec() hands you the ResultSet; Storm's SQL templates map rows onto data classes and bind interpolated values.</div>
    <div class="tm"><span>Exposed to Storm</span><span>5 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/exposed-upserts">
    <div class="tt">Upserts and batching<span class="arrow">→</span></div>
    <div class="td">Both libraries write the database's own upsert. Exposed can target any unique index and shape the update per call; Storm resolves on the primary key and returns the entity.</div>
    <div class="tm"><span>Exposed to Storm</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/exposed-schema">
    <div class="tt">Who owns the schema<span class="arrow">→</span></div>
    <div class="td">Exposed creates tables from its table objects, which suits prototypes. Storm leaves the schema to your migrations and validates the entities against it at startup.</div>
    <div class="tm"><span>Exposed to Storm</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
</div>

<div class="shead" id="storm-way"><span class="mark">//</span>The Storm way<span class="sdesc">Already using Storm? Task recipes, no comparisons, straight to the point.</span></div>
<div class="cards">
  <a class="tcard" href="/tutorials/search">
    <div class="tt">Search and suggest on your own database<span class="arrow">→</span></div>
    <div class="td">A search page and a type-ahead box with two predicates. Input goes in as typed, matches the same way on every database, finds owners through their pets, and runs on an ordinary trigram index.</div>
    <div class="tm"><span>The Storm way</span><span>7 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/advice">
    <div class="tt">Statement advice: what to change, and why<span class="arrow">→</span></div>
    <div class="td">Storm builds every statement, so it knows the shape, the entity graph, the indexes and what each transaction repeated. The log names the problem, the fix and the trade-off, at no cost per execution.</div>
    <div class="tm"><span>The Storm way</span><span>8 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/testing">
    <div class="tt">Testing your data layer with @StormTest<span class="arrow">→</span></div>
    <div class="td">One annotation gives you an in-memory database, schema scripts, per-test rollback and injected parameters. Assert results and the SQL that produced them, then run the same tests against PostgreSQL with one attribute.</div>
    <div class="tm"><span>The Storm way</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/streaming">
    <div class="tt">Streaming large result sets<span class="arrow">→</span></div>
    <div class="td">Process millions of rows in bounded memory: resultFlow streams rows as they arrive, windows(size) frees the connection for loops that write, and lists or Flows batch the write direction.</div>
    <div class="tm"><span>The Storm way</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/sealed-entities">
    <div class="tt">Sealed entity hierarchies<span class="arrow">→</span></div>
    <div class="td">Map a Kotlin sealed interface to one table: subtypes are data classes, the discriminator picks the type on read, each subtype is an entity of its own, and when over results is exhaustive.</div>
    <div class="tm"><span>The Storm way</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/json-columns">
    <div class="tt">JSON columns in relational entities<span class="arrow">→</span></div>
    <div class="td">Mark a field @Json and maps, lists and structured objects are written as JSON and read back typed. JSON aggregation loads a one-to-many in a single query.</div>
    <div class="tm"><span>The Storm way</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/auditing">
    <div class="tt">Auditing with entity callbacks<span class="arrow">→</span></div>
    <div class="td">Stamp createdAt and updatedAt with an EntityCallback. Entities are immutable, so a before-hook returns a changed copy; the entity, the template, Spring or Ktor registers it.</div>
    <div class="tm"><span>The Storm way</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
  <a class="tcard" href="/tutorials/observability">
    <div class="tt">Seeing what Storm does<span class="arrow">→</span></div>
    <div class="td">The SQL log shows every statement where it executes, TRACE adds the values, per-request summaries show what a call cost, and the slow statement log names the execution that took too long.</div>
    <div class="tm"><span>The Storm way</span><span>4 min read</span><span>Kotlin</span></div>
  </a>
</div>

<div class="soon">
  <div class="lbl">In progress</div>
  <div class="chips">
    <span>Converters and custom types</span>
    <span>Composite and natural keys</span>
    <span>Entity caching and dirty checking</span>
  </div>
  <p>Want one of these sooner, or a topic that is not listed? <a href="https://github.com/storm-orm/storm-framework/issues" target="_blank" rel="noopener">Open an issue on GitHub</a>.</p>
</div>

${FOOT_HTML}
`;

// Wires the series chips in the hero: clicking one shows only that section
// (its header and cards) and highlights the chip; clicking it again clears the
// filter and shows all. Falls back to anchor jumps when JS is unavailable.
function wireTutorialFilters() {
  const root = document.querySelector('.storm-tut');
  if (!root) return () => {};
  const chips = Array.from(root.querySelectorAll('.catnav a'));
  const sections = Array.from(root.querySelectorAll('.shead'));
  const handlers = [];
  const apply = (filter) => {
    chips.forEach((c) => {
      const isAll = c.hasAttribute('data-all');
      const on = filter ? !isAll && (c.getAttribute('href') || '').slice(1) === filter : isAll;
      c.classList.toggle('on', on);
      if (on) c.setAttribute('aria-current', 'true');
      else c.removeAttribute('aria-current');
    });
    sections.forEach((shead) => {
      const cards = shead.nextElementSibling;
      const show = !filter || shead.id === filter;
      shead.style.display = show ? '' : 'none';
      if (cards && cards.classList.contains('cards')) cards.style.display = show ? '' : 'none';
    });
  };
  chips.forEach((chip) => {
    const onClick = (e) => {
      e.preventDefault();
      const id = chip.hasAttribute('data-all') ? '' : (chip.getAttribute('href') || '').slice(1);
      apply(chip.classList.contains('on') ? '' : id);
    };
    chip.addEventListener('click', onClick);
    handlers.push([chip, onClick]);
  });
  return () => handlers.forEach(([chip, fn]) => chip.removeEventListener('click', fn));
}

export default function Tutorials() {
  useEffect(() => wireTutorialFilters(), []);
  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href="https://orm.st/tutorials/" />
        <meta property="og:type" content="website" />
        <meta property="og:title" content={TITLE} />
        <meta property="og:description" content={DESC} />
        <meta name="twitter:title" content={TITLE} />
        <meta name="twitter:description" content={DESC} />
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link
          href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap"
          rel="stylesheet"
        />
      </Head>
      <style dangerouslySetInnerHTML={{__html: TUT_CSS}} />
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: BODY}} />
    </>
  );
}
