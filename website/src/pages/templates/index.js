import React from 'react';
import Head from '@docusaurus/Head';
import {TUT_CSS, navHtml, FOOT_HTML, heroArt} from '../../components/tutorial/tutorialTheme';

// The starter-template hub at /templates, rendered in the landing-page style
// (see tutorialTheme.js). Three minimal applications, one per language and
// framework, each a GitHub template repository: the smallest thing that is
// already a working Storm application, with nothing to delete before writing
// your own code. The complete demo applications live at /examples.

const TITLE = 'ST/ORM Starter Templates · Begin a project with Storm';
const DESC =
  'Minimal starter templates for Storm ORM: a runnable Ktor or Spring Boot ' +
  'application with two entities, a repository query, a service, two ' +
  'endpoints and their tests. Use one as a GitHub template and start writing ' +
  'your own code.';

const BODY = `
${navHtml('templates')}

<div class="pagehero">
  <h1>Start your project<br><span class="grad">with one vertical slice.</span></h1>
  <p class="sub">Each template is a working Storm application at its smallest: two entities, one repository query, one service, two endpoints, and the tests that cover them. It runs on H2 the moment you clone it, and PostgreSQL is a configuration change. Around three hundred lines, so there is nothing to delete before your own code goes in.</p>
  ${heroArt('examples', {priority: true})}
</div>

<div class="shead" id="templates"><span class="mark">//</span>Starter templates<span class="sdesc">GitHub template repositories. Press <b>Use this template</b>, rename the package, replace the schema, and the project is yours.</span></div>
<div class="cards">
  <a class="tcard" href="/templates/kotlin-ktor/">
    <div class="tt">Starter template · Kotlin + Ktor<span class="arrow">→</span></div>
    <div class="td">A Ktor application wired by one <code>install(Storm)</code>: the connection pool, the repositories, the Flyway migration and entity validation at startup. Coroutine-native transactions in the service, JSON endpoints, and tests on H2.</div>
    <div class="tm"><span>Kotlin</span><span>Ktor 3</span><span>H2 or PostgreSQL</span></div>
  </a>
  <a class="tcard" href="/templates/kotlin-spring-boot/">
    <div class="tt">Starter template · Kotlin + Spring Boot<span class="arrow">→</span></div>
    <div class="td">The same slice on Spring Boot 4: the starter builds the <code>ORMTemplate</code>, registers the repositories as beans, bridges Storm's transactions with Spring's, and validates every entity against the live schema at startup.</div>
    <div class="tm"><span>Kotlin</span><span>Spring Boot 4</span><span>H2 or PostgreSQL</span></div>
  </a>
  <a class="tcard" href="/templates/java-spring-boot/">
    <div class="tt">Starter template · Java + Spring Boot<span class="arrow">→</span></div>
    <div class="td">The Java flavor on Java 21: immutable record entities, a metamodel query that navigates the foreign key, a Spring-managed transaction, and the same two tests. No JPA, no proxies, no persistence context.</div>
    <div class="tm"><span>Java 21</span><span>Spring Boot 4</span><span>H2 or PostgreSQL</span></div>
  </a>
</div>

<div class="shead" id="whats-inside"><span class="mark">//</span>What is inside<span class="sdesc">The same slice in every template, so the language and the framework are the only difference.</span></div>
<div class="cards">
  <a class="tcard" href="/docs/entities">
    <div class="tt">Two entities and a migration<span class="arrow">→</span></div>
    <div class="td">City and User as immutable records or data classes, with <code>@PK</code> and <code>@FK</code>, and the Flyway migration they map to. Storm checks the two against each other at startup, so a mismatch fails the application rather than the first request.</div>
  </a>
  <a class="tcard" href="/docs/queries">
    <div class="tt">A repository, a service, two endpoints<span class="arrow">→</span></div>
    <div class="td">One query that navigates the foreign key without naming a join, a service that reads in a read-only transaction and writes two rows in one, and the endpoints that expose them.</div>
  </a>
  <a class="tcard" href="/docs/testing">
    <div class="tt">Tests that come with it<span class="arrow">→</span></div>
    <div class="td">A repository test on H2 through <code>@StormTest</code>, asserting the statements with <code>SqlCapture</code>, and a schema validation test that checks every entity against the migration. Both run without Docker.</div>
  </a>
</div>

<div class="shead" id="examples"><span class="mark">//</span>Looking for a complete application?<span class="sdesc">The templates are where a project begins. The examples are where Storm is shown at scale.</span></div>
<div class="cards">
  <a class="tcard" href="/examples/">
    <div class="tt">Example projects<span class="arrow">→</span></div>
    <div class="td">The Storm Movies applications: the public IMDB dataset, keyset pagination, projections, grouped reads, observability, native images and a UI. Built to be read and run, not to be started from.</div>
  </a>
</div>

${FOOT_HTML}
`;

export default function Templates() {
  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href="https://orm.st/templates/" />
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
