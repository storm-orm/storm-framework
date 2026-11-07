import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'Why we built ST/ORM';
const DESC =
  'If you have worked with JPA and Hibernate long enough, you probably know ' +
  'the feeling. Most of the time things are fine, then the model and the ' +
  'codebase grow and there is some drama. When we got the opportunity to ' +
  'start over, we took it, and that is where this story starts.';
const SLUG = 'why-we-built-storm';
const DATE = '2025-09-02';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>Why we built ST/ORM</div>
  <h1>Why we built <span class="grad">ST/ORM</span></h1>
  <p class="dek"><em>&ldquo;We set out to build the model we would enjoy programming against.&rdquo;</em></p>
  <div class="meta"><span>September 2, 2025</span><span>Origin</span><span>6 min read</span></div>

  <h2>We used to reach for Hibernate by default</h2>
  <p>We still like Hibernate. It served us well for a long time, it solved real problems, and it has had a huge impact on how applications on the JVM are built.</p>
  <p>The ecosystem around it had improved as well. Modern JPA and Spring Data fixed a lot of what they inherited: a derived repository method turns a simple query into a method name, most of the old boilerplate is gone, and day-to-day work with them is pleasant. We never really liked JPQL, though it has its merits. Had you asked us at the time, we would have said the tools were good, and for the most part they were.</p>
  <p>No tool is perfect, though. As our projects grew, Hibernate got in our way more often than we liked, and in the kind of systems we were building its trade-offs became more visible.</p>
  <p>At one point one of our teams picked JdbcTemplate for a new project. When plain SQL and hand-written mapping start to feel like a relief, the abstraction is costing you more than you expected. It does feel simple at first. Then the application grows and you remember why higher-level tools were invented.</p>
  <p>Still, what wore on us sat one level below the queries, the boilerplate and the SQL, in the way the entities themselves behaved. We only saw that clearly on the day we had to start over.</p>

  <h2>The backend we had to replace</h2>
  <p>That day came when we set out to replace a backend that had been with the company since we were a startup. It had grown one requirement at a time, the way these applications do, until it needed to be rebuilt properly.</p>
  <p>The scale was daunting on its own. Modeling more than 300 tables as entities is a large job that needs care, and we had watched the previous model get heavier over the years. The more the entities referenced each other, the harder it was to keep a simple read from fanning out into many queries, or from pulling in more of the graph than we wanted. Doing all of that again from scratch was not an encouraging prospect.</p>
  <p>The effort mattered less than the decisions, though. A rebuild is when you get to ask which decisions you would make again, and one of them comes up in every JPA codebase sooner or later: do you pass entities between your internal components, or convert everything into DTOs?</p>
  <p>For an external API, DTOs make sense on their own terms. Inside the application you often write them for a narrower reason. An entity is a managed instance bound to a session, and handing it around freely is not always safe, so you copy it into a DTO to keep that persistence concern from leaking into the rest of your code. That means a lot of packing and unpacking. We had done it once already, and we had to decide whether to do it again.</p>

  <h2>Designed today, it would look different</h2>
  <p>For years we had also been turning over a different question. The ORM most of us reach for was designed more than two decades ago, and the languages have changed a lot since then. We kept coming back to the same thought experiment: if you designed it today on a blank page, what would it look like?</p>
  <p>Back then you more or less had to build on mutable, managed beans, because that was what the language gave you. Today Java records and Kotlin data classes give you immutable value types for free. They are cheap to create, equal by their contents, and safe to pass to any layer or thread without worrying about what happens to them next. With those available, an entity managed by a session looks like the answer to a constraint the language no longer has. Starting today, we would build on records.</p>

  <h2>The idea, and the model we wanted</h2>
  <p>The thought experiment became a plan when Java String Templates arrived as a preview feature. A string template lets you write a real string, SQL in our case, with typed values embedded in it and checked by the compiler. That gave us the first piece: a SQL template engine with row mapping, where your records go into the query and the same records come back out, with nothing to map by hand on either side. String Templates got us started, but ST/ORM never came to depend on them. We kept the shape, and none of it requires the preview feature.</p>
  <p>Records going in and coming out also means you can describe the whole database in its most concise form, as small records that map one to one onto your tables. We wanted that just as much. The records themselves describe the mapping, so there is no configuration on the side and no second copy of the schema in a file somewhere. A model that small is pleasant to work with, and that was part of what we were after.</p>
  <p>Since there is no abstraction laid over the database, a record keeps the actual shape of its table. It was never shaped for a particular query or screen either, so the same record serves the whole application. Everything you asked for is already in it, which leaves no lazy loading to reason about later.</p>
  <p>A record also carries no session and no persistence state, so the database stays in the layer that talks to it. Keeping it there was what the internal DTOs had been for, and with records they had nothing left to guard against.</p>
  <p>The part that had made the rebuild look so daunting got easier too. Getting all those tables right still takes time, and a wrong type or a missed nullability tends to surface later, at runtime. An entity is only a plain record, though, so there is little to get wrong, and you can validate each one against the live schema to confirm it lines up. A slow, careful job turned into one we could trust.</p>
  <p>The template engine and its row mapping were where it started. We already had plain records and a metamodel describing their columns, and with those in place the next step suggested itself: a repository layer with CRUD and type-safe queries, checked by the compiler through the same metamodel. Building it felt more like following the model than adding a feature.</p>
  <p>Most of the time you are not writing SQL at all. You call type-safe methods on your records, and real SQL backs each of them, ready for the moment you want full control. What we ended up with is an ORM backed by SQL, rather than a SQL API.</p>
  <p>We never set out to build another ORM. We wanted a model we would enjoy programming against, and the ORM followed from that. We called it ST/ORM.</p>

  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="/blog/" class="btn">More from the blog</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
