import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import {TUT_CSS, navHtml, FOOT_HTML, heroArt} from '../../components/tutorial/tutorialTheme';

// The blog hub at /blog, rendered in the landing-page style (see
// tutorialTheme.js) rather than with Docusaurus's built-in blog plugin, so it
// matches the tutorials and examples. Each article lives next to this file as
// its own custom page; keep this list newest-first and in sync with them.

const TITLE = 'ST/ORM Blog · Design decisions and deep dives';
const DESC =
  'Posts on the design decisions behind ST/ORM and how it works inside, from ' +
  'entities as plain values to keeping SQL in view.';

// Kept newest-first in this array, but rendered oldest-first on the page so the
// hub reads top to bottom in series order, starting from the origin post.
const POSTS = [
  {
    slug: 'mapping-not-management',
    date: 'July 24, 2026',
    tag: 'Opinion',
    title: 'The M in ORM stands for …',
    blurb:
      'Everyone says the M stands for mapping. Look at what the tools carrying the name actually do and a lot of it is management. We think you should be able to have the mapping without the management.',
  },
  {
    slug: 'should-you-use-an-orm',
    date: 'June 9, 2026',
    tag: 'Opinion',
    title: "Why shouldn't we use an ORM?",
    blurb:
      'The strongest arguments against ORMs turn out to be arguments against ORMs that hide SQL, and an ORM that keeps SQL in view answers most of them.',
  },
  {
    slug: 'why-we-didnt-choose-exposed',
    date: 'April 14, 2026',
    tag: 'Comparison',
    title: "Why we didn't choose Exposed",
    blurb:
      'We build in Kotlin, and JetBrains Exposed is excellent at modeling a database, so building ST/ORM on top of it was a fair question. ST/ORM draws the boundary in a different place: the model stays in the database, and application code works with plain, typed records.',
  },
  {
    slug: 'dirty-checking-without-proxies',
    date: 'March 17, 2026',
    tag: 'Internals',
    title: 'Dirty checking without proxies',
    blurb:
      'To write only the columns that changed, ST/ORM compares the value before with the value after. No bytecode enhancement or session-bound proxy is involved.',
  },
  {
    slug: 'static-metamodel',
    date: 'March 3, 2026',
    tag: 'Internals',
    title: 'The static metamodel',
    blurb:
      'Each entity gets a companion class generated at compile time, which type-checks column and path references and lets queries and hydration run on generated code instead of reflection.',
  },
  {
    slug: 'three-abstractions',
    date: 'February 17, 2026',
    tag: 'Design',
    title: 'Three abstractions and nothing else',
    blurb:
      'ST/ORM has three concepts: Entity, Repository and SQL Template. We kept the model that small so you can hold all of it in your head.',
  },
  {
    slug: 'stop-hiding-my-sql',
    date: 'January 20, 2026',
    tag: 'SQL',
    title: 'Stop hiding my SQL',
    blurb:
      'SQL is the interface every database and every backend engineer already shares. We think it should be typed and kept in view, instead of replaced by a second query language.',
  },
  {
    slug: 'n-plus-one-query-problem',
    date: 'December 16, 2025',
    tag: 'Hibernate',
    title: 'The N+1 problem, and loading you can see',
    blurb:
      'A fetch clause fixes N+1 only until the next query forgets it. ST/ORM makes loading a decision the code shows you, and one you can assert in a test.',
  },
  {
    slug: 'lazyinitializationexception',
    date: 'November 25, 2025',
    tag: 'Hibernate',
    title: "LazyInitializationException, and what it's telling you",
    blurb:
      'The most-googled Hibernate error comes from a lazy load that runs after the session has closed, rather than from a bug in your code. Value entities cannot throw it.',
  },
  {
    slug: 'entities-should-be-values',
    date: 'October 28, 2025',
    tag: 'Design',
    title: 'Entities are just data',
    blurb:
      'An entity in ST/ORM is a plain record, a value with no session and no hidden state. This post covers what that gets you across an application, and how dirty checking and lazy loading still work.',
  },
  {
    slug: 'data-layer-first-principles',
    date: 'September 30, 2025',
    tag: 'Design',
    title: 'A first-principles approach to your data layer',
    blurb:
      'Describe a table in its most concise form and you get the type that carries your data, which is also the contract for querying the entire relation graph.',
  },
  {
    slug: 'why-we-built-storm',
    date: 'September 2, 2025',
    tag: 'Origin',
    title: 'Why we built ST/ORM',
    blurb:
      'If you have worked with JPA and Hibernate long enough, you probably know the feeling. Most of the time things are fine, then the model and the codebase grow and there is some drama. When we got the opportunity to start over, we took it, and that is where this story starts.',
  },
];

const ORDERED = [...POSTS].reverse();

// Distinct categories in series order (first appearance, oldest-first), each
// with a post count, for the clickable filter bar.
const CATEGORIES = ORDERED.reduce((acc, p) => {
  const found = acc.find((c) => c.tag === p.tag);
  if (found) found.count += 1;
  else acc.push({tag: p.tag, count: 1});
  return acc;
}, []);

const cards = ORDERED.map(
  (p) => `
  <a class="tcard" href="/blog/${p.slug}" data-tag="${p.tag}">
    <div class="tt">${p.title}<span class="arrow">→</span></div>
    <div class="td">${p.blurb}</div>
    <div class="tm"><span>${p.date}</span><span>${p.tag}</span></div>
  </a>`,
).join('');

// An "All" chip carrying the full count, then one chip per category. "All" is
// selected on load so the unfiltered state is represented rather than implied;
// wireBlogFilters() keeps it in sync with the category chips.
const filterBar = `
<div class="bfilters">
  <button type="button" class="bchip on" data-filter="" aria-pressed="true">All<b>${ORDERED.length}</b></button>
  ${CATEGORIES.map(
    (c) => `<button type="button" class="bchip" data-filter="${c.tag}" aria-pressed="false">${c.tag}<b>${c.count}</b></button>`,
  ).join('')}
</div>`;

// The filter pills themselves are styled in the shared theme, alongside the
// tutorials row, so the two stay identical.
const BLOG_FILTER_CSS = ``;

// Wires the filter chips: clicking one highlights it and shows only the cards
// whose data-tag matches (an empty filter shows all). Mirrors wireSqlToggles().
function wireBlogFilters() {
  const root = document.querySelector('.storm-tut');
  if (!root) return () => {};
  const chips = Array.from(root.querySelectorAll('.bchip'));
  const cardEls = Array.from(root.querySelectorAll('.cards .tcard'));
  const handlers = [];
  // Exactly one chip is always active: a category, or "All" (the empty filter)
  // when nothing is narrowed. Clicking the active category chip returns to All.
  const apply = (filter) => {
    chips.forEach((c) => c.classList.toggle('on', c.getAttribute('data-filter') === filter));
    chips.forEach((c) => c.setAttribute('aria-pressed', String(c.classList.contains('on'))));
    cardEls.forEach((card) => {
      const show = !filter || card.getAttribute('data-tag') === filter;
      card.style.display = show ? '' : 'none';
    });
  };
  chips.forEach((chip) => {
    const onClick = () => {
      const next = chip.classList.contains('on') ? '' : chip.getAttribute('data-filter');
      apply(next);
    };
    chip.addEventListener('click', onClick);
    handlers.push([chip, onClick]);
  });
  return () => handlers.forEach(([chip, fn]) => chip.removeEventListener('click', fn));
}

const BODY = `
${navHtml('blog')}

<div class="pagehero">
  <h1>Ideas behind<br><span class="grad">the framework.</span></h1>
  <p class="sub">Notes on the design decisions in ST/ORM and on how it works inside.</p>
${filterBar}
${heroArt('blog', {priority: true})}
</div>

<div class="shead" id="articles"><span class="mark">//</span>Articles<span class="sdesc">Why ST/ORM is built the way it is, and what that means for the code you write.</span></div>
<div class="cards">
${cards}
</div>

${FOOT_HTML}
`;

export default function Blog() {
  useEffect(() => wireBlogFilters(), []);
  return (
    <>
      <Head>
        <html lang="en" />
        <title>{TITLE}</title>
        <meta name="description" content={DESC} />
        <link rel="canonical" href="https://orm.st/blog/" />
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
      <style dangerouslySetInnerHTML={{__html: TUT_CSS + BLOG_FILTER_CSS}} />
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: BODY}} />
    </>
  );
}
