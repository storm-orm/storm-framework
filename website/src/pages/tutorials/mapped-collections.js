import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor, glance,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: mapped collections (@OneToMany/@ManyToMany) vs queried
// associations. This is the page where JPA is conceded to be more convenient
// on the surface; the content explains what the convenience costs and why
// Storm queries the association instead. Patterns match docs/relationships.md.

const TITLE = 'Mapped Collections vs Queried Associations: JPA vs Storm';
const DESC =
  '@OneToMany and @ManyToMany make navigation a property access. Storm queries ' +
  'the association instead. This page compares the two: one property versus one ' +
  'line, and what each costs.';

const CODE_JPA_COLLECTIONS = [
  A('@Entity'), P('\n'),
  K('class '), T('Owner'), P('(\n'),
  P('    '), A('@Id'), P(' '), A('@GeneratedValue'), P(' '), K('var '), P('id: '), T('Int'), P('? = '), K('null'), P(',\n'),
  P('    '), K('var '), P('name: '), T('String'), P(' = '), S('""'), P(',\n\n'),
  P('    '), A('@OneToMany'), P('(mappedBy = '), S('"owner"'), P(')\n'),
  P('    '), K('var '), P('pets: '), T('MutableList'), P('<'), T('Pet'), P('> = '), F('mutableListOf'), P('(),\n'),
  P(')\n\n'),
  A('@Entity'), P('\n'),
  K('class '), T('User'), P('(\n'),
  P('    '), A('@Id'), P(' '), A('@GeneratedValue'), P(' '), K('var '), P('id: '), T('Int'), P('? = '), K('null'), P(',\n'),
  P('    '), K('var '), P('name: '), T('String'), P(' = '), S('""'), P(',\n\n'),
  P('    '), A('@ManyToMany'), P('\n'),
  P('    '), A('@JoinTable'), P('(name = '), S('"user_role"'), P(')\n'),
  P('    '), K('var '), P('roles: '), T('MutableSet'), P('<'), T('Role'), P('> = '), F('mutableSetOf'), P('(),\n'),
  P(')\n\n'),
  C('// Navigation is a property access\n'),
  K('val '), P('pets = owner.pets\n'),
  K('val '), P('roles = user.roles'),
].join('');

const CODE_STORM_ONE_TO_MANY = [
  C('// The many side owns the relation, as it does in the schema\n'),
  K('data class '), T('Pet'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('val '), P('name: '), T('String'), P(',\n'),
  P('    '), A('@FK'), P(' '), K('val '), P('owner: '), T('Owner'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('Int'), P('>\n\n'),
  C('// The "collection" is a query: one line, loaded when you ask\n'),
  K('val '), P('pets = orm.'), F('findAll'), P('('), T('Pet_'), P('.owner '), K('eq'), P(' owner)'),
].join('');

const SQL_STORM_ONE_TO_MANY = [
  QC('-- runs when you ask, loads what you asked for'), '\n',
  QK('SELECT'), ' p.id, p.name, o.id, o.name\n',
  QK('FROM'), ' pet p\n',
  QK('INNER JOIN'), ' owner o ', QK('ON'), ' p.owner_id = o.id\n',
  QK('WHERE'), ' p.owner_id = ', QQ('?'),
].join('');

const CODE_STORM_COMPOSE = [
  C('// The association is a query, so it takes an order and a limit\n'),
  K('val '), P('firstTen = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(').'), F('select'), P('()\n'),
  P('    .'), F('where'), P('('), T('Pet_'), P('.owner '), K('eq'), P(' owner)\n'),
  P('    .'), F('orderBy'), P('('), T('Pet_'), P('.name)\n'),
  P('    .'), F('limit'), P('('), N('10'), P(')\n'),
  P('    .resultList\n\n'),
  C('// The pets of many owners in one query, grouped per owner\n'),
  K('val '), P('petsByOwner: '), T('Map'), P('<'), T('Owner'), P(', '), T('List'), P('<'), T('Pet'), P('>> = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(').'), F('select'), P('()\n'),
  P('    .'), F('where'), P('('), T('Pet_'), P('.owner '), K('inList'), P(' owners)\n'),
  P('    .'), F('resultGroupedBy'), P('('), T('Pet_'), P('.owner)'),
].join('');

const CODE_STORM_MANY_TO_MANY = [
  C('// The join table is a real entity with a composite key\n'),
  K('data class '), T('UserRolePk'), P('('), K('val '), P('userId: '), T('Int'), P(', '), K('val '), P('roleId: '), T('Int'), P(')\n\n'),
  K('data class '), T('UserRole'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('userRolePk: '), T('UserRolePk'), P(',\n'),
  P('    '), A('@FK'), P(' '), A('@Persist'), P('(insertable = '), K('false'), P(', updatable = '), K('false'), P(') '), K('val '), P('user: '), T('User'), P(',\n'),
  P('    '), A('@FK'), P(' '), A('@Persist'), P('(insertable = '), K('false'), P(', updatable = '), K('false'), P(') '), K('val '), P('role: '), T('Role'), P(',\n'),
  P(') : '), T('Entity'), P('<'), T('UserRolePk'), P('>\n\n'),
  C('// Roles of a user, through the join entity ...\n'),
  K('val '), P('roles = orm.'), F('findAll'), P('('), T('UserRole_'), P('.user '), K('eq'), P(' user).'), F('map'), P(' { it.role }\n\n'),
  C('// ... or Role filtered by the join table, which adds no rows\n'),
  K('val '), P('sameRoles = orm.'), F('entity'), P('('), T('Role'), P('::'), K('class'), P(').'), F('select'), P('()\n'),
  P('    .'), F('semiJoin'), P('<'), T('UserRole'), P('>().'), F('on'), P('<'), T('Role'), P('>()\n'),
  P('    .'), F('where'), P('('), T('UserRole_'), P('.user '), K('eq'), P(' user)\n'),
  P('    .resultList'),
].join('');

const SQL_STORM_MANY_TO_MANY = [
  QC('-- the second form: one query, each role once'), '\n',
  QK('SELECT'), ' r.id, r.name\n',
  QK('FROM'), ' role r\n',
  QK('WHERE EXISTS'), ' (', QK('SELECT'), ' 1 ', QK('FROM'), ' user_role ur\n',
  '    ', QK('WHERE'), ' ur.role_id = r.id ', QK('AND'), ' ur.user_id = ', QQ('?'), ')',
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Mapped collections</div>
  <h1>Mapped collections vs <span class="grad">queried associations</span></h1>
  <p class="dek">A mapped collection and a queried association are each one line of code. Storm's line also states when it runs, what it loads, and how it is filtered and paged.</p>
  <div class="meta"><span>Series · JPA to Storm</span><span>6 min read</span><span>Kotlin</span></div>
  ${glance({
    left: {label: 'JPA', code: [K('val '), P('pets = owner.pets')].join('')},
    right: {label: 'Storm', code: [K('val '), P('pets = orm.'), F('findAll'), P('('), T('Pet_'), P('.owner '), K('eq'), P(' owner)')].join('')},
  })}

  <h2><span class="hno">01</span>The task</h2>
  <p>An owner has pets; a user has roles through a join table. Show the pets of an owner and the roles of a user, the two classic association shapes: one-to-many and many-to-many.</p>

  <h2><span class="hno">02</span>The JPA way</h2>
  <p>JPA maps both associations onto the entity, and afterwards navigation is just Kotlin:</p>
  ${editor({file: 'Entities.kt', tag: 'Kotlin · JPA', code: CODE_JPA_COLLECTIONS})}
  <p>There is no query in the calling code, and that is convenient. The costs come from one fact: <code>owner.pets</code> is an unbounded query behind a property. It runs on first access, once per owner, so a loop over owners is an N+1 unless batch fetching or a fetch join covers it. It loads the whole collection: showing ten of two thousand pets loads two thousand. The list is a lazy collection bound to the session, and touched for the first time outside it, it throws <code>LazyInitializationException</code>. Once the association has to be filtered, sorted or paged, you write <code>petRepository.findByOwner(owner, pageable)</code>, and the mapped collection no longer serves that read. On the write side, cascade types and orphan removal decide what a change to the collection means.</p>

  <h2><span class="hno">03</span>The Storm way: one-to-many</h2>
  <p>Storm entities hold no collections. The association lives where the schema has it, on the many side, and reading it is a query:</p>
  ${editor({file: 'PetService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_ONE_TO_MANY, sql: SQL_STORM_ONE_TO_MANY})}
  <p>The query runs when you call it and loads what you ask for. Filtering, sorting, paging and loading for many owners at once use the same query builder:</p>
  ${editor({file: 'PetService.kt', tag: 'Kotlin · Storm', code: CODE_STORM_COMPOSE})}

  <h2><span class="hno">04</span>The Storm way: many-to-many</h2>
  <p>For many-to-many, Storm models the join table as the database has it, an entity with a composite key:</p>
  ${editor({file: 'UserRole.kt', tag: 'Kotlin · Storm', code: CODE_STORM_MANY_TO_MANY, sql: SQL_STORM_MANY_TO_MANY})}
  <p>The second form uses <code>semiJoin</code>: the join table filters the roles without adding rows, so each role comes back once and the query needs no <code>distinct</code>. See <a class="tlink" href="/docs/queries#filtering-by-related-rows">Filtering by Related Rows</a>.</p>
  <p>This is more code than <code>@ManyToMany</code> with <code>@JoinTable</code>. In return, writes are plain inserts and deletes of <code>UserRole</code> rows, with no cascade settings involved. And the join table is a type from the start, so when it gets columns such as <code>grantedAt</code> or <code>grantedBy</code>, you add fields to an existing entity. In JPA, that is the point where a <code>@ManyToMany</code> is usually rewritten as an entity of this shape.</p>

  <h2><span class="hno">05</span>Side by side</h2>
  <table class="cmp">
    <tr><th></th><th>JPA mapped collections</th><th>Storm queried associations</th></tr>
    <tr><td>Navigation</td><td><code>owner.pets</code>, one property</td><td><code>orm.findAll(Pet_.owner eq owner)</code>, one line</td></tr>
    <tr><td>When it loads</td><td>On first access, the whole collection, per owner</td><td>When the query runs, what it selects</td></tr>
    <tr><td>Filter, sort, page</td><td>A separate repository query in practice</td><td>The same query, composed</td></tr>
    <tr><td>Join table</td><td>Hidden until it needs columns, then refactored</td><td>An entity from day one</td></tr>
    <tr><td>Outside the session</td><td>An untouched lazy collection throws <code>LazyInitializationException</code></td><td>Results are plain values</td></tr>
  </table>
  <p>For small associations that are always loaded whole and used inside the session, JPA's property is the shorter code. Associations that grow, or get filtered and paged, end up as queries in either framework; Storm starts there.</p>

  <h2><span class="hno">06</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/relationships">Relationships</a>
    <a href="/docs/refs">Refs</a>
    <a href="/docs/queries">Queries</a>
    <a href="/docs/pagination-and-scrolling">Pagination and Scrolling</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function MappedCollectionsTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="mapped-collections" body={BODY} />;
}
