import React from 'react';
import Head from '@docusaurus/Head';
import {TUT_CSS, navHtml, FOOT_HTML, heroArt} from '../../components/tutorial/tutorialTheme';

// The example-projects hub at /examples, rendered in the landing-page style
// (see tutorialTheme.js). Three complete movie-browser applications built on
// the public IMDB dataset: the same app on Ktor and Spring Boot, in Kotlin
// and Java. Each card links to its detail page (README rendered inline by
// the example-readmes plugin, with the clone command and GitHub link).
//
// These are applications to read and run, not to start from: a project begins
// at /templates, where the same stacks are one vertical slice each.

const TITLE = 'ST/ORM Example Projects · Complete applications built with Storm';
const DESC =
  'Complete example applications built with Storm ORM: a movie browser on ' +
  'the public IMDB dataset, implemented on Spring Boot 4 and Ktor, in Kotlin ' +
  'and Java. Clone one, run it, and read idiomatic Storm in a real project; ' +
  'to begin a project of your own, start from a template instead.';

const BODY = `
${navHtml('examples')}

<div class="pagehero">
  <h1>Real applications,<br><span class="grad">built with Storm.</span></h1>
  <p class="sub">The same movie browser on the public IMDB dataset, built on Ktor and Spring Boot, in Kotlin and Java, with GraalVM native-image variants. Each one shows idiomatic Storm at the scale of a real application. Clone one and run it with Docker and Gradle. Beginning a project of your own? Start from a <a href="/templates/">template</a>: the same stacks, one vertical slice each, with nothing to delete first.</p>
  ${heroArt('examples', {priority: true})}
</div>

<div class="shead" id="projects"><span class="mark">//</span>Example projects<span class="sdesc">Server-rendered movie browsers: entities, repositories, projections, pagination, transactions, and tests in a working application. Written to be read, not to be started from.</span></div>
<div class="cards">
  <a class="tcard" href="/examples/kotlin-ktor/">
    <div class="tt">Storm Movies · Kotlin + Ktor<span class="arrow">→</span></div>
    <div class="td">A server-rendered movie browser on Ktor 3 with the Storm plugin: automatic repository registration, service wiring with ktor-server-di, coroutine-native transactions, kotlinx.serialization for the JSON APIs, and Playwright-driven interface tests.</div>
    <div class="tm"><span>Kotlin</span><span>Ktor 3</span></div>
  </a>
  <a class="tcard" href="/examples/kotlin-spring-boot/">
    <div class="tt">Storm Movies · Kotlin + Spring Boot 4<span class="arrow">→</span></div>
    <div class="td">The same movie browser on Spring Boot 4 with immutable data-class entities, metamodel-based queries, coroutine-native transactions, and schema validation. PostgreSQL with Flyway migrations; repository tests on H2 with storm-test.</div>
    <div class="tm"><span>Kotlin</span><span>Spring Boot 4</span></div>
  </a>
  <a class="tcard" href="/examples/java-spring-boot/">
    <div class="tt">Storm Movies · Java + Spring Boot 4<span class="arrow">→</span></div>
    <div class="td">The Java flavor on Java 21: immutable record entities, metamodel-based queries, Spring-managed transactions, and schema validation. No JPA, no proxies, no persistence context.</div>
    <div class="tm"><span>Java 21</span><span>Spring Boot 4</span></div>
  </a>
</div>

<div class="shead" id="graalvm"><span class="mark">//</span>GraalVM native images<span class="sdesc">The same applications compiled to native binaries: sub-second startup, the data layer registered by Storm itself, verified by the same Playwright suites.</span></div>
<div class="cards">
  <a class="tcard" href="/examples/kotlin-spring-boot-graalvm/">
    <div class="tt">Storm Movies · Spring Boot 4 · GraalVM<span class="arrow">→</span></div>
    <div class="td">The Spring Boot movie browser as a native image: entities and scanned repositories registered automatically through Storm's Spring AOT hints, startup around a quarter of a second, and the full Playwright suite running against the native binary.</div>
    <div class="tm"><span>Kotlin</span><span>Spring Boot 4</span><span>GraalVM</span></div>
  </a>
  <a class="tcard" href="/examples/kotlin-ktor-graalvm/">
    <div class="tt">Storm Movies · Ktor · GraalVM<span class="arrow">→</span></div>
    <div class="td">The Ktor movie browser as a native image: storm-core ships a GraalVM feature that registers entities and repositories from the compile-time type index, so the data layer needs no native configuration at all.</div>
    <div class="tm"><span>Kotlin</span><span>Ktor 3</span><span>GraalVM</span></div>
  </a>
</div>

<div class="shead" id="templates"><span class="mark">//</span>Starting a project?<span class="sdesc">An example is an application to read. A template is what your own project begins as.</span></div>
<div class="cards">
  <a class="tcard" href="/templates/">
    <div class="tt">Starter templates<span class="arrow">→</span></div>
    <div class="td">The same three stacks at their smallest: two entities, one repository query, one service, two endpoints and their tests, around three hundred lines. Each is a GitHub template that runs on H2 the moment you clone it.</div>
  </a>
</div>

${FOOT_HTML}
`;

export default function Examples() {
  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href="https://orm.st/examples/" />
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
