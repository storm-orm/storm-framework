import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import {TUT_CSS, navHtml, FOOT_HTML, wireSqlToggles, heroArt} from '../components/tutorial/tutorialTheme';

// The comparison page at /comparison, in the landing/tutorial style. A
// scannable, code-light companion to the in-depth docs/comparison.md: a
// decision matrix plus a compact card per framework, each linking to the full
// docs pairing. Jimmer has a card but no matrix column: the matrix stays at
// the mainstream decision set for readability, while the card and its docs
// pairing cover the closest peer in the place that can express the real
// difference (conciseness). Kept fair per the project's competitor-copy rule:
// only differences that are real and that the rival does not already cover
// are called out.

const TITLE = 'Comparison · ST/ORM vs JPA, jOOQ, Exposed, Jimmer';
const DESC =
  'How Storm compares to Hibernate/JPA, Spring Data, jOOQ, Exposed, Ktorm, ' +
  'MyBatis, and Jimmer. A decision matrix plus a short, fair take on each, with ' +
  'links to the full comparison.';

// One card per framework, in the same order and format for all of them.
const FRAMEWORKS = [
  {
    name: 'JPA / Hibernate',
    slug: 'storm-vs-jpahibernate',
    desc: 'The default for Java persistence. Storm trades managed entities, proxies, and lazy loading for immutable records and explicit, single-query loading.',
    chips: ['Java + Kotlin', 'Mutable, managed'],
  },
  {
    name: 'Spring Data JPA',
    slug: 'storm-vs-spring-data-jpa',
    desc: 'Repositories over JPA, with queries derived from method names. Storm keeps the repository convenience but writes explicit query bodies over stateless entities.',
    chips: ['Java + Kotlin', 'Derived queries'],
  },
  {
    name: 'jOOQ',
    slug: 'storm-vs-jooq',
    desc: 'A type-safe SQL DSL generated from your schema. jOOQ excels at complex SQL; Storm is more concise for entity work, deriving joins from @FK. Storm is fully open source.',
    chips: ['Java + Kotlin', 'SQL-shaped DSL'],
  },
  {
    name: 'Jimmer',
    slug: 'storm-vs-jimmer',
    desc: 'The closest peer: a modern, immutable, stateless Kotlin and Java ORM that also prevents N+1. The difference is conciseness. Storm keeps a plain data class and one-line queries; Jimmer uses interface entities, @IdView, and explicit fetchers for GraphQL-style shaping.',
    chips: ['Java + Kotlin', 'Immutable'],
  },
  {
    name: 'MyBatis',
    slug: 'storm-vs-mybatis',
    desc: 'A SQL mapper: you write every statement, Storm infers the common ones from entities and lets you drop to SQL templates for the rest.',
    chips: ['Java + Kotlin', 'Hand-written SQL'],
  },
  {
    name: 'Exposed',
    slug: 'storm-vs-exposed',
    desc: 'JetBrains’ Kotlin framework, defining tables as DSL objects. Storm declares the model once as annotated data classes and supports seven Spring-style transaction propagation modes without requiring Spring.',
    chips: ['Kotlin only', 'DSL tables'],
  },
  {
    name: 'Ktorm',
    slug: 'storm-vs-ktorm',
    desc: 'A lightweight Kotlin ORM built on mutable entity interfaces and DSL tables. Storm uses immutable data classes and loads relationships in a single query.',
    chips: ['Kotlin only', 'Mutable interfaces'],
  },
];

function buildBody() {
  // The matrix is six columns wide, so on a phone it has to scroll sideways
  // rather than squeeze. The wrapper is the scroller and carries the card
  // chrome; it is focusable and labelled, because a scrollable region a
  // keyboard cannot reach is a region a keyboard user cannot read.
  const matrix = `
<div class="cmp-scroll" tabindex="0" role="region" aria-label="Framework comparison matrix">
<table class="cmp cmp-brand">
  <thead><tr>
    <th>Feature</th><th>Storm</th><th>JPA / Hibernate</th><th>jOOQ</th><th>Exposed</th><th>Ktorm</th>
  </tr></thead>
  <tbody>
    <tr><td>Entity model</td><td>Immutable data class (~5 lines)</td><td>Mutable class (~30, ~10 with Lombok)</td><td>Generated from schema</td><td>DSL table object (+ optional DAO)</td><td>Mutable interface (+ DSL table)</td></tr>
    <tr><td>Immutable entities</td><td>Yes</td><td>No</td><td>Yes</td><td>DSL only</td><td>No</td></tr>
    <tr><td>Type-safe queries</td><td>Yes</td><td>Criteria API</td><td>Yes</td><td>Yes</td><td>Yes</td></tr>
    <tr><td>N+1 handling</td><td>Single-query entity graph</td><td>Common pitfall</td><td>Manual</td><td>Manual joins (DSL) · eager-load (DAO)</td><td>Manual</td></tr>
    <tr><td>Query across relations</td><td>One line: joins and mapping derived from @FK</td><td>JPQL strings or Criteria builders</td><td>Implicit path joins + multiset</td><td>Manual joins and row mapping</td><td>Manual joins</td></tr>
    <tr><td>Session state</td><td>None: no session, no flush</td><td>Persistence context, flush, dirty checking</td><td>None</td><td>DSL none · DAO transaction-bound</td><td>Per-entity change tracking</td></tr>
    <tr><td>Deferred loading</td><td>Explicit Ref&lt;T&gt;</td><td>Proxies, session-bound</td><td>Not applicable</td><td>DAO lazy, transaction-bound</td><td>Manual joins</td></tr>
    <tr><td>Standalone transactions</td><td>Propagation, isolation, timeout, commit hooks</td><td>EntityTransaction · JTA/Spring for more</td><td>Lambda API, nested</td><td>Nested, isolation config</td><td>Lambda API</td></tr>
    <tr><td>Row mapping</td><td>Compile-time generated, reflection-free</td><td>Reflection / bytecode</td><td>Generated records</td><td>Manual (DSL)</td><td>Dynamic proxies</td></tr>
    <tr><td>GraalVM native images</td><td>Native support for Spring and Ktor</td><td>Via Spring Boot AOT or Quarkus</td><td>Reflection config for generated records</td><td>Via the Spring Boot integration</td><td>Manual configuration</td></tr>
    <tr><td>Full SQL escape hatch</td><td>SQL / SQL templates</td><td>Native queries</td><td>It is SQL</td><td>Raw exec()</td><td>Raw JDBC</td></tr>
    <tr><td>Languages</td><td>Kotlin + Java</td><td>Kotlin + Java</td><td>Kotlin + Java</td><td>Kotlin only</td><td>Kotlin only</td></tr>
    <tr><td>License</td><td>Apache 2.0</td><td>LGPL 2.1</td><td>Commercial for some DBs</td><td>Apache 2.0</td><td>Apache 2.0</td></tr>
  </tbody>
</table>
</div>`;

  return `
${navHtml('comparison')}

<div class="pagehero">
  <h1>Your options.<br><span class="grad">Side by side.</span></h1>
  <p class="dek">There is no universally best data framework. Storm is built for teams, and the coding agents working alongside them, who want explicit, predictable database access with concise, immutable models. Here is where it sits next to the alternatives, and where each of them is the better call.</p>
  ${heroArt('comparison', {priority: true})}
</div>

<div class="art art-wide">
  <h2>At a glance</h2>
  <p>Decision-relevant differences across the most common choices. This page compares the designs; the measured numbers, on identical workloads, live on the <a class="tlink" href="/benchmarks">benchmarks page</a>.</p>
  ${matrix}

  <h2>When an agent writes the code</h2>
  <p>Three rows above decide how well a coding agent does with a framework. <b>Entity model</b>: in Storm one data class is the table, its keys and its relations, so the agent reads one file and has the full picture. <b>Session state</b>: with none, the code the agent reads is exactly what runs, and no flush or proxy acts on it later. <b>Query across relations</b>: the domain model already gives one-line, type-safe queries with the whole entity graph loaded, so there is less for the agent to write and fewer joins and mappings to get wrong.</p>
  <p>On top of that, one command, <code>npx @storm-orm/cli init</code>, installs Storm's rules and skills for Claude Code, Cursor, GitHub Copilot, Windsurf and Codex, plus a local, read-only MCP server that gives the agent your schema without your credentials. See the <a class="tlink" href="/docs/ai">agentic coding guide</a>.</p>

  <h2>Framework by framework</h2>
  <p>A short, fair take on each. Every card links to the full pairing in the docs, with feature tables and code.</p>
  <div class="cards">
    ${FRAMEWORKS.map((f) => `
    <a class="tcard" href="/docs/comparison#${f.slug}">
      <div class="tt">Storm vs ${f.name}<span class="arrow">→</span></div>
      <div class="td">${f.desc}</div>
      <div class="tm">${f.chips.map((c) => `<span>${c}</span>`).join('')}</div>
    </a>`).join('')}
  </div>

  <div class="cta">
    <a href="/quickstart" class="btn primary">Try Storm →</a>
    <a href="/benchmarks" class="btn">See the benchmarks</a>
    <a href="/docs/comparison" class="btn">Full comparison</a>
  </div>
</div>

${FOOT_HTML}
`;
}

export default function Comparison() {
  useEffect(() => wireSqlToggles(), []);
  const url = 'https://orm.st/comparison';
  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href={url} />
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
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: buildBody()}} />
    </>
  );
}
