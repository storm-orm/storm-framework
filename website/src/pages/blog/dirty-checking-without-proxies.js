import React from 'react';
import {BlogPage} from '../../components/blog/blogTheme';

const TITLE = 'Dirty checking without proxies';
const DESC =
  'You do not need bytecode enhancement or a session-bound proxy to know ' +
  'what changed on an entity. The value before and the value after are ' +
  'enough, and this is how ST/ORM uses them.';
const SLUG = 'dirty-checking-without-proxies';
const DATE = '2026-03-17';

const BODY = `
<div class="art">
  <div class="crumbs"><a href="/blog/">Blog</a><span class="sep">/</span>Dirty checking without proxies</div>
  <h1>Dirty checking <span class="grad">without proxies</span></h1>
  <p class="dek">Dirty checking is one of the nice things a traditional ORM does for you: it skips entities you did not change and can limit an UPDATE to the columns you did. The usual objection to value-based ORMs is that they give this up. ST/ORM keeps it.</p>
  <div class="meta"><span>March 17, 2026</span><span>Internals</span><span>4 min read</span></div>

  <h2>How the classic approach works</h2>
  <p>Hibernate has two ways to know what changed. It can snapshot every managed entity when it loads and, at flush time, diff the current state against that snapshot to build the UPDATE. Or it can enhance your bytecode so that setters record which fields were touched. Both are clever, and both need the session to own your objects, because the change tracking belongs to the managed lifecycle rather than to the data. That ownership is also where detached-state bugs and <a class="tlink" href="/blog/lazyinitializationexception">proxies that outlive their session</a> come from.</p>

  <h2>How ST/ORM does it</h2>
  <p>ST/ORM compares the entity you write with the entity you read in the same transaction. If they match, it skips the UPDATE. If they differ, the default mode writes the row and field mode writes only the columns that changed. No proxy or bytecode enhancement is involved, and no session keeps a snapshot of your objects. The value observed at read time belongs to the transaction and is discarded when it ends. You read a <code>User</code>, produce an updated copy and save it, and ST/ORM works out the difference.</p>
  <p>Because entities are immutable, an updated copy is the natural way to change one anyway, and it leaves the before and the after as two distinct values, which is what a diff needs. Immutability helps a second time when you save the same value you read. It is the same object, so reference identity settles the question before any field is compared, and nothing is written. When there is a change to inspect, ST/ORM reads the fields through the <a class="tlink" href="/blog/static-metamodel">generated metamodel</a> instead of by reflection, so the comparison goes through generated accessors with no runtime lookup in the path.</p>

  <h2>What that changes</h2>
  <p>You get the same minimal updates with a different cost structure. Change detection becomes a function of two values instead of a lifecycle you have to keep an entity inside. Outside a transaction an entity is plain data: nothing tracks it, nothing will flush it, and it cannot go stale the way a managed object can.</p>

  <div class="cta">
    <a href="/docs/dirty-checking" class="btn primary">How dirty checking works →</a>
    <a href="/blog/entities-should-be-values" class="btn">Entities should be values</a>
  </div>
</div>`;

export default function Page() {
  return <BlogPage title={TITLE} description={DESC} slug={SLUG} dateISO={DATE} body={BODY} />;
}
