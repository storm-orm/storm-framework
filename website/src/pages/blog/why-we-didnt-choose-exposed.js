import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = "Why we didn't choose Exposed for ST/ORM";
const DESC =
  "We build in Kotlin, so why didn't we build ST/ORM on JetBrains Exposed? A " +
  "respectful comparison of Exposed's schema-first design and DAO entities " +
  'with the boundary ST/ORM draws instead.';
const SLUG = 'why-we-didnt-choose-exposed';
const DATE = '2026-04-14';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>Why we didn't choose Exposed</div>
  <h1>Why we didn't choose <span class="grad">Exposed</span></h1>
  <p class="dek">We build in Kotlin, so we owe an answer to a fair question. JetBrains already makes <a class="tlink" href="https://www.jetbrains.com/help/exposed/" target="_blank" rel="noopener">JetBrains Exposed</a>, a strong, well-liked Kotlin SQL library for exactly our language. Why did we not build ST/ORM on top of it?</p>
  <div class="meta"><span>April 14, 2026</span><span>Comparison</span><span>4 min read</span></div>

  <h2>A question of boundaries</h2>
  <p>Our reasons have less to do with missing features than with where each library draws its boundary. Exposed gives you a Kotlin way to model and work with a database. ST/ORM starts from the belief that the data model belongs in the database, and concentrates on making that model available to application logic as plain, typed data. The same idea is behind <a class="tlink" href="/blog/why-we-built-storm">why we built ST/ORM</a> in the first place.</p>

  <h2>Exposed starts from the database</h2>
  <p>The two projects start from different first principles, and the ones Exposed chose suit the Kotlin story very well. Its center of gravity is the database itself. With the table DSL you describe a whole schema in Kotlin, with its tables, columns, keys and constraints, and you can drive the database from that description. Exposed is excellent at modeling a database this way, and if what you want is to describe and command a database in idiomatic Kotlin, it is a superb answer.</p>

  <h2>Exposed aims to be the Kotlin way to talk to SQL</h2>
  <p>That strength comes with a wide aim. Exposed sets out to be the complete Kotlin way to talk to a database, and the part it optimizes for is defining the schema. Writing an application against that schema day to day is a slightly different concern, and there the fit is looser. The DSL gives you type-safe queries but hands the results back as rows, which you then map into your own types. Over a handful of tables that is fine. Over a few hundred, it adds up to a lot of mapping to write and keep correct by hand.</p>

  <h2>The DAO API solves part of the mapping problem</h2>
  <p>Exposed does have an answer for the mapping. Its DAO API turns each row into an entity object you work with directly, and for many projects that closes the gap well. What gave us pause was the kind of abstraction it is. A <a class="tlink" href="/blog/three-abstractions">repository</a> abstracts access: it groups the operations for reading and writing a row and hands the row back as the same plain record it always was. The DAO abstracts representation. On top of the table it puts a second version of your data, a mutable, transaction-bound entity with lazily loaded references and updates that flush on commit. That is a parallel model to keep in sync, and it carries the managed lifecycle we had just spent a rebuild moving away from. Both abstractions are useful, but the DAO is the one that gives you another model of your data to learn.</p>

  <h2>ST/ORM draws the boundary differently</h2>
  <p>We come at this from the other side, though not quite in the way that might sound. Exposed is schema-first, while ST/ORM respects the database and faces the application. On the thing that matters most we agree with Exposed: the real data model lives in the database, not in your code. Where we part ways is that Exposed also gives you the tools to define that model from Kotlin, and we did not want to.</p>
  <p>We take the schema as the database hands it to us and put our effort into a narrower goal, which is making that model available to application code as plain, typed records. These are the same <a class="tlink" href="/blog/entities-should-be-values">values rather than managed objects</a> that shape the rest of ST/ORM, and the choice follows from <a class="tlink" href="/blog/data-layer-first-principles">our first-principles approach to the data layer</a>. Exposed lets you model the database beautifully before you reach for the data. We leave the schema to the database and only reach for the data, as plain records that go into <a class="tlink" href="/blog/stop-hiding-my-sql">real SQL</a> and come back unchanged.</p>

  <h2>Kotlin-first, enterprise JVM second</h2>
  <p>We are glad to see Exposed, after many years, fully embraced by JetBrains, with a 1.0 release and real investment behind it. It is their Kotlin-native data library, and it sits close to where JetBrains is heading, which is Kotlin everywhere, multiplatform included. ST/ORM is aimed at a different room. It lives in the enterprise JVM, where Kotlin is a first-class citizen but not the only one. It became Kotlin-first for a plain reason: Java is still catching up in a few places that matter to an API shaped like this one, and we want the Java variant to feel natural in Java too.</p>

  <h2>A fit decision, not a verdict</h2>
  <p>None of this is a case against Exposed. We admire it enough that our own transactions follow the same programmatic, coroutine-friendly shape JetBrains Exposed made popular. Exposed is excellent when you want to model and command a database in Kotlin, and ST/ORM optimizes for a different boundary. Once you commit to that boundary, you stop looking for a library to adopt, because it is probably something you will have to build yourself.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/blog/" class="btn">More from the blog</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
