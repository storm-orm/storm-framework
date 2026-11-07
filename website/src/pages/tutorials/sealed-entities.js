import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: sealed entity hierarchies (single-table inheritance). Storm-way
// how-to; content verified against docs/polymorphism.md (sealed interface +
// @Discriminator, dtype column first in the model, per-subtype INSERT columns,
// subtype repositories filtered by the discriminator, exhaustive when).

const TITLE = 'Sealed Entity Hierarchies';
const DESC =
  'Map a Kotlin sealed interface to one table: subtypes are data classes, the ' +
  'discriminator column picks the type on read, each subtype is an entity of ' +
  'its own, and when over the results is checked for exhaustiveness by the compiler.';

const CODE_SEALED = [
  C('// The sealed interface is the entity; subtypes are data classes\n'),
  A('@Discriminator'), P('\n'),
  K('sealed interface '), T('Pet'), P(' : '), T('Entity'), P('<'), T('Int'), P('> {\n'),
  P('    '), K('val '), P('name: '), T('String'), P('   '), C('// shared: a path of the Pet_ metamodel\n'),
  P('}\n\n'),
  K('data class '), T('Cat'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('override val '), P('name: '), T('String'), P(',\n'),
  P('    '), K('val '), P('indoor: '), T('Boolean'), P(',\n'),
  P(') : '), T('Pet'), P('\n\n'),
  K('data class '), T('Dog'), P('(\n'),
  P('    '), A('@PK'), P(' '), K('val '), P('id: '), T('Int'), P(' = '), N('0'), P(',\n'),
  P('    '), K('override val '), P('name: '), T('String'), P(',\n'),
  P('    '), K('val '), P('weight: '), T('Int'), P(',\n'),
  P(') : '), T('Pet'), P(''),
].join('');

const CODE_CRUD = [
  K('val '), P('pets = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(')\n\n'),
  C('// Reads return the concrete subtypes\n'),
  K('for'), P(' (pet '), K('in'), P(' pets.'), F('findAll'), P('()) {\n'),
  P('    '), K('when'), P(' (pet) {\n'),
  P('        '), K('is '), T('Cat'), P(' -> '), F('render'), P('('), S('"${pet.name}, indoor=${pet.indoor}"'), P(')\n'),
  P('        '), K('is '), T('Dog'), P(' -> '), F('render'), P('('), S('"${pet.name}, ${pet.weight}kg"'), P(')\n'),
  P('    }   '), C('// add a Bird subtype and this stops compiling until handled\n'),
  P('}\n\n'),
  C('// Writes go through the same repository\n'),
  P('pets.'), F('insert'), P('('), T('Cat'), P('(name = '), S('"Bella"'), P(', indoor = '), K('true'), P('))\n'),
  P('pets.'), F('update'), P('('), T('Cat'), P('(id = '), N('1'), P(', name = '), S('"Sir Whiskers"'), P(', indoor = '), K('true'), P('))'),
].join('');

const SQL_CRUD = [
  QC('-- one table; the discriminator column picks the subtype on read'), '\n',
  QK('SELECT'), ' p.dtype, p.id, p.name, p.indoor, p.weight ', QK('FROM'), ' pet p\n\n',
  QC("-- an insert writes the discriminator and the subtype's own columns"), '\n',
  QK('INSERT INTO'), ' pet (dtype, name, indoor) ', QK('VALUES'), ' (', QQ('?'), ', ', QQ('?'), ', ', QQ('?'), ')   ', QC("-- dtype = 'Cat'"), '\n',
  QK('INSERT INTO'), ' pet (dtype, name, weight) ', QK('VALUES'), ' (', QQ('?'), ', ', QQ('?'), ', ', QQ('?'), ')   ', QC("-- dtype = 'Dog'"),
].join('');

const CODE_FILTER = [
  C('// A shared field filters the whole hierarchy\n'),
  K('val '), P('named = orm.'), F('entity'), P('('), T('Pet'), P('::'), K('class'), P(').'), F('select'), P('()\n'),
  P('    .'), F('where'), P('('), T('Pet_'), P('.name '), K('like'), P(' '), S('"B%"'), P(')\n'),
  P('    .resultList   '), C('// List<Pet>: cats and dogs\n\n'),
  C('// A subtype is an entity of its own; its statements add the discriminator\n'),
  K('val '), P('indoorCats = orm.'), F('entity'), P('('), T('Cat'), P('::'), K('class'), P(').'), F('select'), P('()\n'),
  P('    .'), F('where'), P('('), T('Cat_'), P('.indoor '), K('eq'), P(' '), K('true'), P(')\n'),
  P('    .resultList   '), C('// List<Cat>'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Sealed entities</div>
  <h1>Sealed entity <span class="grad">hierarchies</span></h1>
  <p class="dek">Kotlin models a closed set of types as a sealed interface, and the compiler checks that a <code>when</code> over it handles every subtype. Storm maps a sealed interface onto one table, so that check covers the rows you read.</p>
  <div class="meta"><span>Series · The Storm way</span><span>4 min read</span><span>Kotlin</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A <code>pet</code> table holds cats and dogs, with some shared columns and some per kind. The code should work with <code>Cat</code> and <code>Dog</code> types rather than one <code>Pet</code> with every field nullable, and a new kind should not compile until every place that handles pets handles it.</p>

  <h2><span class="hno">02</span>The model</h2>
  <p>The sealed interface is the entity and the data classes are the subtypes. <code>@Discriminator</code> declares the column that tells the rows apart, <code>dtype</code> by default, holding the subtype name:</p>
  ${editor({file: 'Pet.kt', tag: 'Kotlin · Storm', code: CODE_SEALED})}
  <p>All subtypes share the <code>pet</code> table. A shared field like <code>name</code> is one column; <code>indoor</code> and <code>weight</code> are NULL in the rows of the other subtype. A field the sealed interface declares is part of the <code>Pet_</code> metamodel; a field only a subtype declares is part of that subtype's metamodel, such as <code>Cat_.indoor</code>.</p>

  <h2><span class="hno">03</span>Reads and writes</h2>
  <p>The repository works on the sealed interface, and results come back as concrete subtypes. A <code>when</code> over a result is exhaustive, so adding a <code>Bird</code> turns every <code>when</code> that does not handle it into a compile error:</p>
  ${editor({file: 'PetService.kt', tag: 'Kotlin · Storm', code: CODE_CRUD, sql: SQL_CRUD})}
  <p>On insert and update Storm reads the runtime class: an insert writes the discriminator and that subtype's columns. On select it reads the discriminator and constructs the matching type. There is no casting and no type column in your code.</p>

  <h2><span class="hno">04</span>Querying the hierarchy</h2>
  <p>Query the sealed type for all pets, or a subtype for one kind. A subtype of a single-table hierarchy is an entity of its own: <code>orm.entity(Cat::class)</code>, or an <code>EntityRepository&lt;Cat, Int&gt;</code>, reads and writes the shared table, and every statement it runs selects the cats by the discriminator:</p>
  ${editor({file: 'PetService.kt', tag: 'Kotlin · Storm', code: CODE_FILTER})}
  <p>Subtypes may declare foreign keys as any entity does. A foreign key every subtype declares is joined with an inner join; one that only some subtypes declare is joined with a left join, since the other rows hold NULL.</p>
  <p>This page covers the single-table strategy, the default. When subtypes have many fields of their own, the joined-table strategy (<code>@Polymorphic(JOINED)</code>) puts each subtype's columns in a table of its own; there, a subtype is not a repository or a FROM table of its own, so you query through the sealed type. See <a class="tlink" href="/docs/polymorphism">Polymorphism</a> for the decision guide, discriminator options and polymorphic foreign keys.</p>

  <h2><span class="hno">05</span>Keep going</h2>
  <p>The reference documentation covers the mechanics in depth:</p>
  <div class="refs">
    <a href="/docs/polymorphism">Polymorphism</a>
    <a href="/docs/entities">Entities</a>
    <a href="/docs/metamodel">Metamodel</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function SealedEntitiesTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="sealed-entities" body={BODY} />;
}
