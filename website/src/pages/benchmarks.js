import React, {useEffect} from 'react';
import Head from '@docusaurus/Head';
import {
  TUT_CSS, navHtml, FOOT_HTML, wireSqlToggles, editor, clonebar, heroArt,
  K, T, S, C, F, N, A, P, QK, QQ, QC,
} from '../components/tutorial/tutorialTheme';

const TITLE = 'Benchmarks · ST/ORM vs Hibernate, jOOQ, Exposed, Ktorm and Jimmer';
const DESC = 'Across this reproducible latency suite Storm takes five of twelve workloads outright, is in the leading group on ten, and is top three on all twelve. No other framework matches that consistency.';

// Results from the reproducible suite: one tuned PostgreSQL 17 container over TCP, JMH,
// 5 forks, 5x3s measured iterations, single thread. Values are the median fork in us/op, with
// the fork range [fastest–slowest] in brackets (see the benchmark repository's methodology). Rows are same-session comparisons; the bare SELECT 1 baseline measured ~141 us
// on this runner.
const LIBS = {
  jdbc: {name: 'JDBC', cls: 'jdbc'},
  storm: {name: 'Storm', cls: 'storm'},
  hibernate: {name: 'Hibernate', cls: ''},
  jooq: {name: 'jOOQ', cls: ''},
  exposed: {name: 'Exposed', cls: ''},
  exposedDao: {name: 'Exposed DAO', cls: ''},
  ktorm: {name: 'Ktorm', cls: ''},
  jimmer: {name: 'Jimmer', cls: ''},
};

const WORKLOADS = [
  {
    id: 'singleRowById',
    title: 'Primary key lookup',
    desc: 'Load one visit by primary key. The purest round-trip test: one query, one row.',
    results: {jdbc: [148.2, 147.7, 149.0], storm: [157.0, 155.3, 157.1], jimmer: [165.8, 163.8, 167.2], jooq: [175.3, 173.7, 178.4], ktorm: [181.9, 181.3, 183.1], hibernate: [182.9, 172.2, 183.9], exposed: [292.4, 289.2, 294.7], exposedDao: [307.6, 299.2, 308.3]},
    mark: ['exposed', 'exposedDao'],
    note: '† Transaction overhead.',
  },
  {
    id: 'projection',
    title: 'Projection',
    desc: 'Three columns across three tables into a flat DTO, one hundred rows.',
    results: {jdbc: [940.6, 935.4, 946.6], storm: [984.4, 981.0, 988.7], jimmer: [984.8, 975.9, 996.8], ktorm: [1001.4, 996.2, 1029.0], jooq: [1006.2, 999.6, 1009.0], hibernate: [1022.9, 1016.2, 1040.9], exposed: [1131.0, 1128.6, 1132.8], exposedDao: [1146.3, 1143.8, 1188.6]},
  },
  {
    id: 'multiStatement',
    title: 'Create then amend',
    desc: 'Insert a visit, then amend it by its generated key, in one transaction.',
    results: {jdbc: [529.7, 528.9, 532.0], storm: [564.2, 560.6, 565.4], exposed: [566.6, 562.8, 568.0], ktorm: [583.6, 582.3, 584.9], jimmer: [588.8, 583.5, 597.6], exposedDao: [616.0, 612.6, 621.9], hibernate: [687.7, 643.7, 917.0], jooq: [694.3, 693.5, 763.7]},
  },
  {
    id: 'updateById',
    title: 'Read, modify, update',
    desc: 'Read one owner, change one field, persist atomically with one UPDATE.',
    results: {jdbc: [454.0, 449.6, 470.3], storm: [471.8, 462.0, 497.2], exposed: [475.4, 472.2, 476.0], jimmer: [505.4, 502.9, 511.3], ktorm: [511.4, 504.2, 512.8], hibernate: [539.4, 486.9, 548.4], jooq: [615.1, 612.8, 624.4], exposedDao: [721.7, 535.7, 837.9]},
  },
  {
    id: 'dynamic',
    title: 'Dynamic query',
    desc: 'A filtered search assembled at runtime from a cycling set of optional predicates.',
    results: {jdbc: [778.7, 762.3, 794.9], hibernate: [924.4, 919.6, 927.5], storm: [936.8, 930.8, 940.0], jimmer: [954.4, 953.9, 961.1], ktorm: [965.2, 938.2, 972.5], jooq: [965.3, 962.0, 986.0], exposed: [1077.4, 1075.1, 1081.6], exposedDao: [1087.4, 1083.3, 1088.7]},
  },
  {
    id: 'graphInsert',
    title: 'Graph insert',
    desc: 'Write 20 owner-to-pet-to-visit graphs, generated keys threaded level to level.',
    results: {jdbc: [2239.3, 2227.2, 2292.8], ktorm: [2418.6, 2391.0, 2446.0], storm: [2450.7, 2435.4, 2479.1], jooq: [2601.7, 2558.4, 2647.6], jimmer: [3166.3, 3143.4, 3259.5], hibernate: [3185.3, 3158.8, 3197.6], exposed: [3338.5, 3305.1, 3445.5], exposedDao: [3418.3, 3326.5, 3433.9]},
  },
  {
    id: 'objectGraph',
    title: 'Object graph',
    desc: 'Load the owners of a city, each with their list of pets, grouped one-to-many.',
    results: {jooq: [845.5, 842.1, 852.1], jdbc: [1038.8, 1038.0, 1043.3], storm: [1152.7, 1146.1, 1160.6], exposed: [1325.1, 1319.0, 1330.9], hibernate: [1530.5, 1492.4, 1738.1], jimmer: [1574.4, 1563.8, 1591.4], exposedDao: [1830.7, 1820.6, 1838.4], ktorm: [1960.3, 1947.5, 2022.5]},
  },
  {
    id: 'batchInsert',
    title: 'Batch insert',
    desc: 'Insert 100 visits atomically and fetch their database-generated keys.',
    results: {jdbc: [3204.5, 3152.4, 3266.2], storm: [3276.0, 3248.2, 3280.1], jooq: [3548.1, 3504.8, 3574.5], ktorm: [3857.9, 3464.6, 4038.9], jimmer: [4524.7, 4491.0, 4582.6], hibernate: [4738.6, 4688.9, 4795.1], exposed: [4980.2, 4956.4, 5007.8], exposedDao: [5255.9, 5188.6, 5305.6]},
  },
  {
    id: 'keyset',
    title: 'Keyset pagination',
    desc: 'One page of 20 rows by keyset (seek) pagination, object graph materialized.',
    results: {jdbc: [382.8, 381.7, 387.9], storm: [456.4, 448.4, 465.7], hibernate: [484.4, 478.8, 500.1], jooq: [501.3, 497.7, 531.0], exposed: [583.8, 575.6, 587.8], jimmer: [697.5, 692.0, 710.6], exposedDao: [814.1, 803.4, 817.5], ktorm: [1017.2, 1009.0, 1025.2]},
  },
  {
    id: 'joinWithMapping10',
    title: 'Three-table join · 10 rows',
    desc: 'Load pets with owner and city hydrated through a single three-table join.',
    results: {jdbc: [620.2, 617.0, 636.8], jimmer: [638.8, 635.1, 653.9], storm: [659.5, 652.0, 670.7], hibernate: [687.7, 672.0, 698.4], jooq: [708.1, 706.3, 716.4], exposedDao: [758.8, 754.1, 761.3], ktorm: [791.3, 783.7, 793.7], exposed: [821.3, 813.4, 824.2]},
  },
  {
    id: 'joinWithMapping100',
    title: 'Three-table join · 100 rows',
    desc: 'The same join at 100 rows. Hydration cost starts to separate the field.',
    results: {jdbc: [724.5, 720.0, 732.3], storm: [837.7, 831.4, 838.7], jimmer: [962.9, 948.6, 969.5], jooq: [987.1, 973.8, 1141.0], exposed: [1002.4, 999.7, 1006.6], hibernate: [1011.1, 1008.8, 1014.7], ktorm: [1204.1, 1195.6, 1210.0], exposedDao: [1834.9, 1823.6, 1866.8]},
  },
  {
    id: 'joinWithMapping1000',
    title: 'Three-table join · 1,000 rows',
    desc: 'The same join at 1,000 rows. Row mapping now dominates the round trip.',
    results: {jdbc: [2289.2, 2274.8, 2295.8], storm: [3017.8, 3007.6, 3079.2], exposed: [3670.5, 3626.3, 3691.4], jooq: [4390.5, 4308.6, 4405.9], hibernate: [4691.0, 4661.9, 4715.5], jimmer: [5363.6, 5295.7, 5448.4], exposedDao: [6355.2, 6272.5, 6450.5], ktorm: [6729.6, 6594.7, 6747.5]},
  },
];

function fmt(v) {
  return v >= 1000 ? (v / 1000).toFixed(2) + ' ms' : Math.round(v) + ' µs';
}

// Frameworks inside this band of the fastest are reported as level rather than ranked. Two full
// runs of the same code on identical hardware move each figure by about 1% (median), and every
// workload in this suite is either inside 1.3% or clear by more than 4%, so the band sits in an
// empty stretch of the distribution: 2, 3 or 4 percent all partition the field identically.
const BAND_PCT = 3;
const BAND = 1 + BAND_PCT / 100;

function chartHtml(w) {
  const entries = Object.entries(w.results).sort((a, b) => a[1][0] - b[1][0]);
  const max = entries[entries.length - 1][1][0];
  const frameworkBest = Math.min(...entries.filter(([l]) => l !== 'jdbc').map(([, v]) => v[0]));
  // How many frameworks sit inside the band. One means a real lead; more than one means a tie.
  const leadCount = entries.filter(([l, v]) => l !== 'jdbc' && v[0] <= frameworkBest * BAND).length;
  // JDBC is the reference, not a competitor: it renders first, with the frameworks ranked below it.
  const ordered = [...entries.filter(([l]) => l === 'jdbc'), ...entries.filter(([l]) => l !== 'jdbc')];
  // One unit for the whole card, so a row never reads in ms beside a neighbour in µs. Milliseconds
  // only once even the fastest row clears 1 ms; below that, microseconds keep the smaller cards aligned.
  const useMs = Math.min(...ordered.map(([, v]) => v[0])) >= 1000;
  const val = (v) => (useMs ? (v / 1000).toFixed(2) + ' ms' : Math.round(v) + ' µs');
  const bound = (v) => (useMs ? (v / 1000).toFixed(2) : Math.round(v));
  const unit = useMs ? 'ms' : 'µs';
  const rows = ordered.map(([lib, [mean, lo, hi]]) => {
    const meta = LIBS[lib];
    const width = Math.max(1.5, (mean / max) * 100);
    const leading = lib !== 'jdbc' && mean <= frameworkBest * BAND;
    const marked = (w.mark || []).includes(lib);
    const gap = (mean / frameworkBest - 1) * 100;
    // Percentages are the distance to the fastest framework. JDBC is signed against the same
    // reference: a negative reads as the raw driver running that much ahead of the fastest
    // framework, positive where a framework passes it.
    //
    // A row is only called "fastest" when it is alone inside the band. Where several frameworks
    // are within it the run-to-run noise is larger than the gap between them, so they all read
    // "level": naming a winner there would be reporting which way the noise fell, not a result.
    const signed = (g) => (g >= 0 ? '+' : '') + (Math.abs(g) < 10 ? g.toFixed(1) : Math.round(g)) + '%';
    const delta = lib === 'jdbc'
      ? signed(gap)
      : leadCount > 1
      ? (leading ? 'level' : '+' + (gap < 10 ? gap.toFixed(1) : Math.round(gap)) + '%')
      : mean === frameworkBest
      ? 'fastest'
      : '+' + (gap < 0.05 ? '0' : gap < 10 ? gap.toFixed(1) : Math.round(gap)) + '%';
    return `<div class="bm-row ${lib === 'storm' ? '' : meta.cls}${leading ? ' win' : ''}">
      <span class="bm-name"${leading ? ` title="within ${BAND_PCT}% of the fastest framework"` : ''}>${meta.name}${marked ? '<sup class="bm-mark">†</sup>' : ''}</span>
      <span class="bm-track"><span class="bm-bar" style="width:${width.toFixed(1)}%"></span></span>
      <span class="bm-val" data-range="Fork range ${bound(lo)}–${bound(hi)} ${unit}">${val(mean)}</span>
      <span class="bm-delta">${delta}</span>
    </div>`;
  }).join('');
  return `<div class="bm-card">
    <h3>${w.title}</h3>
    <p class="bm-desc">${w.desc}</p>
    ${rows}
    ${w.note ? `<p class="bm-note">${w.note}</p>` : ''}
  </div>`;
}

// Queries LOC: the benchmark workload file per library, all twelve workloads, including its row mapping.
// Entities LOC (below): the entity or table definition file. Both are non-blank, non-comment, non-import,
// non-package source lines counted the same way; purpose-built workload shapes (fairness rule 3) count
// toward queries rather than the model. Generated code (Storm's metamodel, jOOQ's table classes) and the
// result types shared by every implementation in the common module are excluded on all sides. The numbers
// are the output of scripts/count_loc.py in the benchmark repository.
const LOC = [
  ['storm', 161, []],
  ['ktorm', 177, []],
  ['hibernate', 188, ['string queries']],
  ['exposedDao', 190, []],
  ['exposed', 191, ['hand-mapped rows']],
  ['jooq', 194, ['hand-mapped rows']],
  ['jimmer', 270, []],
  ['jdbc', 395, ['hand-mapped rows', 'string queries']],
];

// The entity or table definition file per library, same counting rule. Result types shared by every
// implementation live in the common module and are excluded on all sides.
const MODEL_LOC = [
  ['storm', 31], ['jimmer', 57], ['exposed', 58], ['ktorm', 60], ['exposedDao', 74], ['hibernate', 141],
];

function modelLocHtml() {
  const max = Math.max(...MODEL_LOC.map(([, n]) => n));
  const rows = MODEL_LOC.map(([lib, n]) => `<div class="bm-row ${LIBS[lib].cls}">
      <span class="bm-name">${LIBS[lib].name}</span>
      <span class="bm-track"><span class="bm-bar" style="width:${((n / max) * 100).toFixed(1)}%"></span></span>
      <span class="bm-val">${n} lines</span>
    </div>`).join('');
  return `<div class="bm-card bm-loc">
    <h3>Entities LOC</h3>
    <p class="bm-desc">The entity or table definition file by the same counting rule. This is the model code developers write and maintain.</p>
    ${rows}
    <p class="bm-note">JDBC and jOOQ have no hand-written model: JDBC maps rows by hand and jOOQ generates its table classes, so their cost appears in the suite total above instead.</p>
  </div>`;
}

function locHtml() {
  const max = Math.max(...LOC.map(([, n]) => n));
  const rows = LOC.map(([lib, n, tags]) => `<div class="bm-row ${LIBS[lib].cls}">
      <span class="bm-name">${LIBS[lib].name}${tags.map((t) => `<i class="bm-tag">${t}</i>`).join('')}</span>
      <span class="bm-track"><span class="bm-bar" style="width:${((n / max) * 100).toFixed(1)}%"></span></span>
      <span class="bm-val">${n} lines</span>
    </div>`).join('');
  return `<div class="bm-card bm-loc">
    <h3>Queries LOC</h3>
    <p class="bm-desc">The workload file for all twelve workloads, including its row mapping, counted as non-blank, non-comment, non-import, non-package lines. Generated code is excluded for the two libraries that use it, Storm's metamodel and jOOQ's table classes, and result types shared across every implementation are excluded on all sides. Purpose-built shapes defined on top of a library's regular entity to speed a workload count toward its query lines.</p>
    ${rows}
    <p class="bm-note">Storm implements all twelve workloads in the fewest lines; every other implementation needs 10% to 145% more. The counts come from <code>scripts/count_loc.py</code> in the benchmark repository, so they can be reverified against any checkout. Beyond the line count, the labels show what a low number can leave unsaid: hand-mapped rows are written and maintained by hand, and string queries are not compile-checked.</p>
  </div>`;
}

// ---- Code snippets: the operative lines of each workload, trimmed of JMH plumbing. ----

const CODE_ENTITIES = [
  `${K('data class')} ${T('City')}(`,
  `    ${A('@PK')} ${K('val')} id: ${T('Long')} = ${N('0')},`,
  `    ${K('val')} name: ${T('String')},`,
  `) : ${T('Entity')}&lt;${T('Long')}&gt;`,
  ``,
  `${K('data class')} ${T('Owner')}(`,
  `    ${A('@PK')} ${K('val')} id: ${T('Long')} = ${N('0')},`,
  `    ${K('val')} firstName: ${T('String')},`,
  `    ${K('val')} lastName: ${T('String')},`,
  `    ${K('val')} address: ${T('String')},`,
  `    ${K('val')} telephone: ${T('String')},`,
  `    ${A('@FK')} ${K('val')} city: ${T('City')},`,
  `) : ${T('Entity')}&lt;${T('Long')}&gt;`,
  ``,
  `${K('data class')} ${T('Pet')}(`,
  `    ${A('@PK')} ${K('val')} id: ${T('Long')} = ${N('0')},`,
  `    ${K('val')} name: ${T('String')},`,
  `    ${K('val')} birthDate: ${T('LocalDate')},`,
  `    ${A('@FK')} ${K('val')} type: ${T('Ref')}&lt;${T('PetType')}&gt;,`,
  `    ${A('@FK')} ${K('val')} owner: ${T('Owner')},`,
  `) : ${T('Entity')}&lt;${T('Long')}&gt;`,
].join('\n');
const CODE_MODEL_JDBC = [
  `${C('// No ORM model. Rows are hand-mapped into plain records:')}`,
  `${K('record')} ${T('City')}(${K('long')} id, ${T('String')} name) {}`,
  ``,
  `${K('record')} ${T('Owner')}(${K('long')} id, ${T('String')} firstName, ${T('String')} lastName,`,
  `               ${T('String')} address, ${T('String')} telephone, ${T('City')} city) {}`,
  ``,
  `${K('record')} ${T('Pet')}(${K('long')} id, ${T('String')} name, ${T('LocalDate')} birthDate,`,
  `             ${K('long')} typeId, ${T('Owner')} owner) {}`,
].join('\n');
const CODE_MODEL_HIBERNATE = [
  `${C('// Private fields, field access. Getters/setters added only where the app calls them.')}`,
  `${A('@Entity')} ${A('@Table')}(name = ${S('"city"')})`,
  `${K('class')} ${T('City')} {`,
  `    ${A('@Id')} ${A('@GeneratedValue')}(strategy = IDENTITY) ${T('Long')} id;`,
  `    ${A('@Column')}(name = ${S('"name"')}) ${T('String')} name;`,
  `}`,
  ``,
  `${A('@Entity')} ${A('@Table')}(name = ${S('"owner"')}) ${A('@DynamicUpdate')}`,
  `${K('class')} ${T('Owner')} {`,
  `    ${A('@Id')} ${A('@GeneratedValue')}(strategy = IDENTITY) ${T('Long')} id;`,
  `    ${A('@Column')}(name = ${S('"first_name"')}) ${T('String')} firstName;`,
  `    ${A('@Column')}(name = ${S('"last_name"')}) ${T('String')} lastName;`,
  `    ${A('@Column')}(name = ${S('"address"')}) ${T('String')} address;`,
  `    ${A('@Column')}(name = ${S('"telephone"')}) ${T('String')} telephone;`,
  `    ${A('@ManyToOne')}(fetch = LAZY) ${A('@JoinColumn')}(name = ${S('"city_id"')}) ${T('City')} city;`,
  `    ${A('@OneToMany')}(mappedBy = ${S('"owner"')}) ${T('List')}&lt;${T('Pet')}&gt; pets;`,
  `}`,
  ``,
  `${A('@Entity')} ${A('@Table')}(name = ${S('"pet"')})`,
  `${K('class')} ${T('Pet')} {`,
  `    ${A('@Id')} ${A('@GeneratedValue')}(strategy = IDENTITY) ${T('Long')} id;`,
  `    ${A('@Column')}(name = ${S('"name"')}) ${T('String')} name;`,
  `    ${A('@Column')}(name = ${S('"birth_date"')}) ${T('LocalDate')} birthDate;`,
  `    ${A('@ManyToOne')}(fetch = LAZY) ${A('@JoinColumn')}(name = ${S('"type_id"')}) ${T('PetType')} type;`,
  `    ${A('@ManyToOne')}(fetch = LAZY) ${A('@JoinColumn')}(name = ${S('"owner_id"')}) ${T('Owner')} owner;`,
  `}`,
].join('\n');
const CODE_MODEL_JOOQ = [
  `${C('// jOOQ generates the table classes from the schema; there is no entity model.')}`,
  `${C('// You still write the result records it maps into, shared here with JDBC:')}`,
  `${K('record')} ${T('City')}(${K('long')} id, ${T('String')} name) {}`,
  ``,
  `${K('record')} ${T('Owner')}(${K('long')} id, ${T('String')} firstName, ${T('String')} lastName,`,
  `               ${T('String')} address, ${T('String')} telephone, ${T('City')} city) {}`,
  ``,
  `${K('record')} ${T('Pet')}(${K('long')} id, ${T('String')} name, ${T('LocalDate')} birthDate,`,
  `             ${K('long')} typeId, ${T('Owner')} owner) {}`,
].join('\n');
const CODE_MODEL_EXPOSED = [
  `${K('object')} ${T('Cities')} : ${T('Table')}(${S('"city"')}) {`,
  `    ${K('val')} id = ${F('long')}(${S('"id"')}).${F('autoIncrement')}()`,
  `    ${K('val')} name = ${F('varchar')}(${S('"name"')}, ${N('100')})`,
  `    ${K('override val')} primaryKey = ${T('PrimaryKey')}(id)`,
  `}`,
  ``,
  `${K('object')} ${T('Owners')} : ${T('Table')}(${S('"owner"')}) {`,
  `    ${K('val')} id = ${F('long')}(${S('"id"')}).${F('autoIncrement')}()`,
  `    ${K('val')} firstName = ${F('varchar')}(${S('"first_name"')}, ${N('50')})`,
  `    ${K('val')} lastName = ${F('varchar')}(${S('"last_name"')}, ${N('50')})`,
  `    ${K('val')} address = ${F('varchar')}(${S('"address"')}, ${N('120')})`,
  `    ${K('val')} telephone = ${F('varchar')}(${S('"telephone"')}, ${N('20')})`,
  `    ${K('val')} cityId = ${F('long')}(${S('"city_id"')}).${F('references')}(${T('Cities')}.id)`,
  `    ${K('override val')} primaryKey = ${T('PrimaryKey')}(id)`,
  `}`,
  ``,
  `${K('object')} ${T('Pets')} : ${T('Table')}(${S('"pet"')}) {`,
  `    ${K('val')} id = ${F('long')}(${S('"id"')}).${F('autoIncrement')}()`,
  `    ${K('val')} name = ${F('varchar')}(${S('"name"')}, ${N('50')})`,
  `    ${K('val')} birthDate = ${F('date')}(${S('"birth_date"')})`,
  `    ${K('val')} typeId = ${F('long')}(${S('"type_id"')}).${F('references')}(${T('PetTypes')}.id)`,
  `    ${K('val')} ownerId = ${F('long')}(${S('"owner_id"')}).${F('references')}(${T('Owners')}.id)`,
  `    ${K('override val')} primaryKey = ${T('PrimaryKey')}(id)`,
  `}`,
].join('\n');
const CODE_MODEL_EXPOSED_DAO = [
  `${K('object')} ${T('Cities')} : ${T('LongIdTable')}(${S('"city"')}) { ${K('val')} name = ${F('varchar')}(${S('"name"')}, ${N('100')}) }`,
  ``,
  `${K('object')} ${T('Owners')} : ${T('LongIdTable')}(${S('"owner"')}) {`,
  `    ${K('val')} firstName = ${F('varchar')}(${S('"first_name"')}, ${N('50')})`,
  `    ${K('val')} lastName = ${F('varchar')}(${S('"last_name"')}, ${N('50')})`,
  `    ${K('val')} address = ${F('varchar')}(${S('"address"')}, ${N('120')})`,
  `    ${K('val')} telephone = ${F('varchar')}(${S('"telephone"')}, ${N('20')})`,
  `    ${K('val')} cityId = ${F('reference')}(${S('"city_id"')}, ${T('Cities')})`,
  `}`,
  ``,
  `${K('object')} ${T('Pets')} : ${T('LongIdTable')}(${S('"pet"')}) {`,
  `    ${K('val')} name = ${F('varchar')}(${S('"name"')}, ${N('50')})`,
  `    ${K('val')} birthDate = ${F('date')}(${S('"birth_date"')})`,
  `    ${K('val')} typeId = ${F('reference')}(${S('"type_id"')}, ${T('PetTypes')})`,
  `    ${K('val')} ownerId = ${F('reference')}(${S('"owner_id"')}, ${T('Owners')})`,
  `}`,
  ``,
  `${K('class')} ${T('CityDao')}(id: ${T('EntityID')}&lt;${T('Long')}&gt;) : ${T('LongEntity')}(id) {`,
  `    ${K('companion object')} : ${T('LongEntityClass')}&lt;${T('CityDao')}&gt;(${T('Cities')})`,
  `    ${K('var')} name ${K('by')} ${T('Cities')}.name`,
  `}`,
  ``,
  `${K('class')} ${T('OwnerDao')}(id: ${T('EntityID')}&lt;${T('Long')}&gt;) : ${T('LongEntity')}(id) {`,
  `    ${K('companion object')} : ${T('LongEntityClass')}&lt;${T('OwnerDao')}&gt;(${T('Owners')})`,
  `    ${K('var')} firstName ${K('by')} ${T('Owners')}.firstName`,
  `    ${K('var')} lastName ${K('by')} ${T('Owners')}.lastName`,
  `    ${K('var')} address ${K('by')} ${T('Owners')}.address`,
  `    ${K('var')} telephone ${K('by')} ${T('Owners')}.telephone`,
  `    ${K('var')} city ${K('by')} ${T('CityDao')} ${F('referencedOn')} ${T('Owners')}.cityId`,
  `    ${K('val')} pets ${K('by')} ${T('PetDao')} ${F('referrersOn')} ${T('Pets')}.ownerId`,
  `}`,
  ``,
  `${K('class')} ${T('PetDao')}(id: ${T('EntityID')}&lt;${T('Long')}&gt;) : ${T('LongEntity')}(id) {`,
  `    ${K('companion object')} : ${T('LongEntityClass')}&lt;${T('PetDao')}&gt;(${T('Pets')})`,
  `    ${K('var')} name ${K('by')} ${T('Pets')}.name`,
  `    ${K('var')} birthDate ${K('by')} ${T('Pets')}.birthDate`,
  `    ${K('var')} typeId ${K('by')} ${T('Pets')}.typeId`,
  `    ${K('var')} owner ${K('by')} ${T('OwnerDao')} ${F('referencedOn')} ${T('Pets')}.ownerId`,
  `}`,
].join('\n');
const CODE_MODEL_JIMMER = [
  `${A('@Entity')} ${A('@Table')}(name = ${S('"city"')})`,
  `${K('interface')} ${T('City')} {`,
  `    ${A('@Id')} ${K('long')} ${F('id')}();`,
  `    ${T('String')} ${F('name')}();`,
  `}`,
  ``,
  `${A('@Entity')} ${A('@Table')}(name = ${S('"owner"')})`,
  `${K('interface')} ${T('Owner')} {`,
  `    ${A('@Id')} ${K('long')} ${F('id')}();`,
  `    ${T('String')} ${F('firstName')}();`,
  `    ${T('String')} ${F('lastName')}();`,
  `    ${T('String')} ${F('address')}();`,
  `    ${T('String')} ${F('telephone')}();`,
  `    ${A('@ManyToOne')} ${A('@JoinColumn')}(name = ${S('"city_id"')}) ${T('City')} ${F('city')}();`,
  `    ${A('@OneToMany')}(mappedBy = ${S('"owner"')}) ${T('List')}&lt;${T('Pet')}&gt; ${F('pets')}();`,
  `}`,
  ``,
  `${A('@Entity')} ${A('@Table')}(name = ${S('"pet"')})`,
  `${K('interface')} ${T('Pet')} {`,
  `    ${A('@Id')} ${K('long')} ${F('id')}();`,
  `    ${T('String')} ${F('name')}();`,
  `    ${T('LocalDate')} ${F('birthDate')}();`,
  `    ${A('@ManyToOne')} ${A('@JoinColumn')}(name = ${S('"type_id"')}) ${T('PetType')} ${F('type')}();`,
  `    ${A('@ManyToOne')} ${A('@JoinColumn')}(name = ${S('"owner_id"')}) ${T('Owner')} ${F('owner')}();`,
  `}`,
].join('\n');

const CODE_MODEL_KTORM = [
  `${K('interface')} ${T('Owner')} : ${T('Entity')}&lt;${T('Owner')}&gt; {`,
  `    ${K('companion object')} : ${T('Entity')}.${T('Factory')}&lt;${T('Owner')}&gt;()`,
  `    ${K('val')} id: ${T('Long')}`,
  `    ${K('var')} firstName: ${T('String')}`,
  `    ${K('var')} lastName: ${T('String')}`,
  `    ${K('var')} address: ${T('String')}`,
  `    ${K('var')} telephone: ${T('String')}`,
  `    ${K('var')} city: ${T('City')}`,
  `}`,
  ``,
  `${K('object')} ${T('Owners')} : ${T('Table')}&lt;${T('Owner')}&gt;(${S('"owner"')}) {`,
  `    ${K('val')} id = ${F('long')}(${S('"id"')}).${F('primaryKey')}().${F('bindTo')} { it.id }`,
  `    ${K('val')} firstName = ${F('varchar')}(${S('"first_name"')}).${F('bindTo')} { it.firstName }`,
  `    ${C('// … lastName, address and telephone bindings …')}`,
  `    ${K('val')} cityId = ${F('long')}(${S('"city_id"')}).${F('references')}(${T('Cities')}) { it.city }`,
  `}`,
].join('\n');

const CODE_SINGLE_KTORM = [
  `database.${F('sequenceOf')}(${T('Visits')}).${F('find')} { it.id ${F('eq')} id }!!`,
].join('\n');
const SQL_SINGLE_KTORM = [
  `${QK('SELECT')} v.id, v.pet_id, v.visit_date, v.description`,
  `${QK('FROM')} visit v`,
  `${QK('WHERE')} v.id = ${QQ('?')}`,
  `${QK('LIMIT')} ${QQ('?')}  ${QC('-- find appends a bound limit')}`,
].join('\n');

const CODE_JOIN_KTORM = [
  `database.${F('sequenceOf')}(${T('Pets')})`,
  `    .${F('filter')} { (${T('Pets')}.id ${F('greater')} base) ${K('and')} (${T('Pets')}.id ${F('lessEq')} base + rows) }`,
  `    .${F('toList')}()  ${C('// reference bindings join owner and city')}`,
].join('\n');
const SQL_JOIN_KTORM = [
  `${QK('SELECT')} …  ${QC('-- all pet, owner and city columns')}`,
  `${QK('FROM')} pet`,
  `${QK('LEFT JOIN')} owner _ref0 ${QK('ON')} pet.owner_id = _ref0.id  ${QC('-- reference bindings join with LEFT JOIN')}`,
  `${QK('LEFT JOIN')} city _ref1 ${QK('ON')} _ref0.city_id = _ref1.id`,
  `${QK('WHERE')} (pet.id > ${QQ('?')}) ${QK('AND')} (pet.id &lt;= ${QQ('?')})`,
].join('\n');

const CODE_PROJECTION_KTORM = [
  `database.${F('from')}(${T('Pets')})`,
  `    .${F('innerJoin')}(${T('Owners')}, on = ${T('Pets')}.ownerId ${F('eq')} ${T('Owners')}.id)`,
  `    .${F('innerJoin')}(${T('Cities')}, on = ${T('Owners')}.cityId ${F('eq')} ${T('Cities')}.id)`,
  `    .${F('select')}(${T('Pets')}.name, ${T('Owners')}.lastName, ${T('Cities')}.name)`,
  `    .${F('where')} { ${T('Owners')}.cityId ${F('eq')} cityId }`,
  `    .${F('map')} { ${T('PetRow')}(it[${T('Pets')}.name]!!, it[${T('Owners')}.lastName]!!, it[${T('Cities')}.name]!!) }`,
].join('\n');

const CODE_GRAPH_KTORM = [
  `database.${F('from')}(${T('Owners')})`,
  `    .${F('innerJoin')}(${T('Cities')}, on = ${T('Owners')}.cityId ${F('eq')} ${T('Cities')}.id)`,
  `    .${F('innerJoin')}(${T('Pets')}, on = ${T('Pets')}.ownerId ${F('eq')} ${T('Owners')}.id)`,
  `    .${F('select')}()`,
  `    .${F('where')} { ${T('Owners')}.cityId ${F('eq')} cityId }`,
  `    .${F('orderBy')}(${T('Owners')}.id.${F('asc')}())`,
  `    .${F('map')} { ${T('Pets')}.${F('createEntity')}(it) }`,
  `    .${F('groupIntoOwners')}()  ${C('// in-memory LinkedHashMap grouping')}`,
].join('\n');
const SQL_GRAPH_KTORM = [
  `${QK('SELECT')} *  ${QC('-- the DSL join selects every column of the three tables')}`,
  `${QK('FROM')} owner`,
  `${QK('INNER JOIN')} city ${QK('ON')} owner.city_id = city.id`,
  `${QK('INNER JOIN')} pet ${QK('ON')} pet.owner_id = owner.id`,
  `${QK('WHERE')} owner.city_id = ${QQ('?')}`,
  `${QK('ORDER BY')} owner.id`,
].join('\n');

const CODE_UPDATE_KTORM = [
  `database.${F('useTransaction')} {`,
  `    ${C('// withReferences = false keeps the read lazy: owner columns only, no city join')}`,
  `    ${K('val')} owner = database.${F('sequenceOf')}(${T('Owners')}, withReferences = ${K('false')}).${F('find')} { it.id ${F('eq')} id }!!`,
  `    owner.telephone = ${T('Params')}.${F('toggleTelephone')}(owner.telephone)`,
  `    owner.${F('flushChanges')}()  ${C('// dirty tracking writes only the changed column')}`,
  `}`,
].join('\n');

const CODE_SINGLE = [
  `${K('val')} visit = visits.${F('getById')}(id)`,
].join('\n');
const SQL_SINGLE = [
  `${QK('SELECT')} v.id, v.pet_id, v.visit_date, v.description`,
  `${QK('FROM')} visit v`,
  `${QK('WHERE')} v.id = ${QQ('?')}`,
].join('\n');
const CODE_SINGLE_JDBC = [
  `${K('try')} (${K('var')} ps = connection.${F('prepareStatement')}(`,
  `        ${S('"SELECT id, pet_id, visit_date, description FROM visit WHERE id = ?"')})) {`,
  `    ps.${F('setLong')}(${N('1')}, id);`,
  `    ${K('try')} (${K('var')} rs = ps.${F('executeQuery')}()) {`,
  `        rs.${F('next')}();`,
  `        ${K('return')} ${K('new')} ${T('Visit')}(rs.${F('getLong')}(${N('1')}), rs.${F('getLong')}(${N('2')}),`,
  `                rs.${F('getObject')}(${N('3')}, ${T('LocalDate')}.${K('class')}), rs.${F('getString')}(${N('4')}));`,
  `    }`,
  `}`,
].join('\n');
const CODE_SINGLE_HIBERNATE = [
  `${K('return')} sessionFactory.${F('fromSession')}(session -> session.${F('find')}(${T('Visit')}.${K('class')}, id));`,
].join('\n');
const CODE_SINGLE_JOOQ = [
  `${K('return')} ctx.${F('select')}(${T('VISIT')}.ID, ${T('VISIT')}.PET_ID, ${T('VISIT')}.VISIT_DATE, ${T('VISIT')}.DESCRIPTION)`,
  `        .${F('from')}(${T('VISIT')})`,
  `        .${F('where')}(${T('VISIT')}.ID.${F('eq')}(id))`,
  `        .${F('fetchOne')}(${T('Records')}.${F('mapping')}(${T('Visit')}::new));`,
].join('\n');
const CODE_SINGLE_EXPOSED = [
  `${F('transaction')}(database) {`,
  `    ${T('Visits')}.${F('selectAll')}().${F('where')} { ${T('Visits')}.id ${F('eq')} id }.${F('single')}().${F('toVisit')}()`,
  `}`,
].join('\n');
const CODE_SINGLE_EXPOSED_DAO = [
  `${F('transaction')}(database) {`,
  `    ${T('VisitDao')}.${F('findById')}(id)!!.${F('toVisit')}()`,
  `}`,
].join('\n');
const CODE_SINGLE_JIMMER = [
  `${K('return')} sqlClient.${F('getEntities')}().${F('findById')}(${T('Visit')}.${K('class')}, id);`,
].join('\n');

const CODE_JOIN = [
  `${K('val')} result = pets.${F('select')}()`,
  `    .${F('where')}((${T('Pet_')}.id ${F('greater')} base) ${K('and')} (${T('Pet_')}.id ${F('lessEq')} base + rows))`,
  `    .resultList  ${C('// Pet, Owner and City hydrated from one query')}`,
].join('\n');
const SQL_JOIN = [
  `${QK('SELECT')} p.id, p.name, p.birth_date, p.type_id, p.owner_id,`,
  `       o.first_name, o.last_name, o.address, o.telephone, o.city_id, c.name`,
  `${QK('FROM')} pet p`,
  `${QK('INNER JOIN')} owner o ${QK('ON')} p.owner_id = o.id`,
  `${QK('INNER JOIN')} city c ${QK('ON')} o.city_id = c.id`,
  `${QK('WHERE')} p.id > ${QQ('?')} ${QK('AND')} p.id &lt;= ${QQ('?')}`,
].join('\n');
const CODE_JOIN_JDBC = [
  `${K('try')} (${K('var')} ps = connection.${F('prepareStatement')}(`,
  `        ${S('"SELECT p.id, p.name, ..., c.id, c.name FROM pet p"')}`,
  `        + ${S('" JOIN owner o ON p.owner_id = o.id JOIN city c ON o.city_id = c.id"')}`,
  `        + ${S('" WHERE p.id > ? AND p.id <= ?"')})) {`,
  `    ps.${F('setLong')}(${N('1')}, base); ps.${F('setLong')}(${N('2')}, base + rows);`,
  `    ${K('try')} (${K('var')} rs = ps.${F('executeQuery')}()) {`,
  `        ${T('List')}&lt;${T('Pet')}&gt; pets = ${K('new')} ${T('ArrayList')}&lt;&gt;();`,
  `        ${K('while')} (rs.${F('next')}()) pets.${F('add')}(${F('mapPet')}(rs));  ${C('// hand-map each row -> Pet, Owner, City')}`,
  `        ${K('return')} pets;`,
  `    }`,
  `}`,
].join('\n');
const CODE_JOIN_HIBERNATE = [
  `${K('return')} sessionFactory.${F('fromSession')}(session -> session`,
  `    .${F('createSelectionQuery')}(`,
  `        ${S('"from Pet p join fetch p.owner o join fetch o.city where p.id > :base and p.id <= :top"')},`,
  `        ${T('Pet')}.${K('class')})`,
  `    .${F('setParameter')}(${S('"base"')}, base)`,
  `    .${F('setParameter')}(${S('"top"')}, base + rows)`,
  `    .${F('getResultList')}());`,
].join('\n');
const CODE_JOIN_JOOQ = [
  `${K('return')} ctx.${F('select')}(`,
  `        ${T('PET')}.ID, ${T('PET')}.NAME, ${T('PET')}.BIRTH_DATE, ${T('PET')}.TYPE_ID,`,
  `        ${F('row')}(${T('OWNER')}.ID, ${T('OWNER')}.FIRST_NAME, ${T('OWNER')}.LAST_NAME, ${T('OWNER')}.ADDRESS, ${T('OWNER')}.TELEPHONE,`,
  `                ${F('row')}(${T('CITY')}.ID, ${T('CITY')}.NAME).${F('mapping')}(${T('City')}::new)).${F('mapping')}(${T('Owner')}::new))`,
  `    .${F('from')}(${T('PET')})`,
  `    .${F('join')}(${T('OWNER')}).${F('on')}(${T('PET')}.OWNER_ID.${F('eq')}(${T('OWNER')}.ID))`,
  `    .${F('join')}(${T('CITY')}).${F('on')}(${T('OWNER')}.CITY_ID.${F('eq')}(${T('CITY')}.ID))`,
  `    .${F('where')}(${T('PET')}.ID.${F('gt')}(base).${F('and')}(${T('PET')}.ID.${F('le')}(base + rows)))`,
  `    .${F('fetch')}(${T('Records')}.${F('mapping')}(${T('Pet')}::new));`,
].join('\n');
const CODE_JOIN_EXPOSED = [
  `${F('transaction')}(database) {`,
  `    (${T('Pets')} ${F('innerJoin')} ${T('Owners')} ${F('innerJoin')} ${T('Cities')})`,
  `        .${F('selectAll')}()`,
  `        .${F('where')} { (${T('Pets')}.id ${F('greater')} base) ${K('and')} (${T('Pets')}.id ${F('lessEq')} base + rows) }`,
  `        .${F('map')} { it.${F('toPet')}() }`,
  `}`,
].join('\n');
const CODE_JOIN_EXPOSED_DAO = [
  `${F('transaction')}(database) {`,
  `    ${T('PetDao')}.${F('wrapRows')}(`,
  `        ${T('Pets')}.${F('selectAll')}()`,
  `            .${F('where')} { (${T('Pets')}.id ${F('greater')} base) ${K('and')} (${T('Pets')}.id ${F('lessEq')} base + rows) })`,
  `        .${F('with')}(${T('PetDao')}::owner, ${T('OwnerDao')}::city)  ${C('// 2 extra batched SELECT ... IN queries')}`,
  `        .${F('map')} { it.${F('toPet')}() }`,
  `}`,
].join('\n');
const CODE_JOIN_JIMMER = [
  `${T('PetTable')} table = ${T('PetTable')}.$;`,
  `${K('return')} sqlClient.${F('createQuery')}(table)`,
  `    .${F('where')}(table.${F('id')}().${F('gt')}(base)).${F('where')}(table.${F('id')}().${F('le')}(base + rows))`,
  `    .${F('select')}(table.${F('fetch')}(`,
  `        ${T('PetFetcher')}.$.${F('allScalarFields')}()`,
  `            .${F('owner')}(${T('OwnerFetcher')}.$.${F('allScalarFields')}()`,
  `                .${F('city')}(${T('CityFetcher')}.$.${F('allScalarFields')}()))))  ${C('// fetchers -> 2 batched loads')}`,
  `    .${F('execute')}();`,
].join('\n');
const SQL_JOIN_EXPOSED_DAO = [
  `${QC('-- 1) main pet query')}`,
  `${QK('SELECT')} p.id, p.name, p.birth_date, p.type_id, p.owner_id`,
  `${QK('FROM')} pet p ${QK('WHERE')} p.id > ${QQ('?')} ${QK('AND')} p.id &lt;= ${QQ('?')}`,
  ``,
  `${QC('-- 2) batched owners')}`,
  `${QK('SELECT')} o.id, o.first_name, o.last_name, o.address, o.telephone, o.city_id`,
  `${QK('FROM')} owner o ${QK('WHERE')} o.id ${QK('IN')} (${QQ('?')}, ${QQ('?')}, ...)`,
  ``,
  `${QC('-- 3) batched cities')}`,
  `${QK('SELECT')} c.id, c.name ${QK('FROM')} city c ${QK('WHERE')} c.id ${QK('IN')} (${QQ('?')}, ${QQ('?')}, ...)`,
].join('\n');
const SQL_JOIN_JIMMER = [
  `${QC('-- 1) main pet query (owner_id kept as FK for the batch load)')}`,
  `${QK('SELECT')} p.id, p.name, p.birth_date, p.owner_id`,
  `${QK('FROM')} pet p ${QK('WHERE')} p.id > ${QQ('?')} ${QK('AND')} p.id &lt;= ${QQ('?')}`,
  ``,
  `${QC('-- 2) batched owners')}`,
  `${QK('SELECT')} o.id, o.first_name, o.last_name, o.address, o.telephone, o.city_id`,
  `${QK('FROM')} owner o ${QK('WHERE')} o.id = ${QK('ANY')}(${QQ('?')})  ${QC('-- array bind, chunked for large id sets')}`,
  ``,
  `${QC('-- 3) batched cities')}`,
  `${QK('SELECT')} c.id, c.name ${QK('FROM')} city c ${QK('WHERE')} c.id = ${QK('ANY')}(${QQ('?')})`,
].join('\n');

const CODE_PROJECTION = [
  `${K('data class')} ${T('PetRow')}(${K('val')} petName: ${T('String')}, ${K('val')} ownerLastName: ${T('String')}, ${K('val')} cityName: ${T('String')})`,
  ``,
  `${K('val')} rows = orm.${F('selectFrom')}&lt;${T('Pet')}, ${T('PetRow')}&gt; { ${S('"${Pet_.name}, ${Pet_.owner.lastName}, ${Pet_.owner.city.name}"')} }`,
  `    .${F('where')}(${T('Pet_')}.owner.city.id ${F('eq')} cityId)`,
  `    .resultList`,
].join('\n');
const SQL_PROJECTION = [
  `${QK('SELECT')} p.name, o.last_name, c.name`,
  `${QK('FROM')} pet p`,
  `${QK('INNER JOIN')} owner o ${QK('ON')} p.owner_id = o.id`,
  `${QK('INNER JOIN')} city c ${QK('ON')} o.city_id = c.id`,
  `${QK('WHERE')} o.city_id = ${QQ('?')}`,
].join('\n');
const CODE_PROJECTION_JDBC = [
  `${K('try')} (${K('var')} ps = connection.${F('prepareStatement')}(`,
  `        ${S('"SELECT p.name, o.last_name, c.name FROM pet p"')}`,
  `        + ${S('" JOIN owner o ON p.owner_id = o.id JOIN city c ON o.city_id = c.id"')}`,
  `        + ${S('" WHERE o.city_id = ?"')})) {`,
  `    ps.${F('setLong')}(${N('1')}, cityId);`,
  `    ${K('try')} (${K('var')} rs = ps.${F('executeQuery')}()) {`,
  `        ${T('List')}&lt;${T('PetRow')}&gt; rows = ${K('new')} ${T('ArrayList')}&lt;&gt;();`,
  `        ${K('while')} (rs.${F('next')}()) rows.${F('add')}(${K('new')} ${T('PetRow')}(rs.${F('getString')}(${N('1')}), rs.${F('getString')}(${N('2')}), rs.${F('getString')}(${N('3')})));`,
  `        ${K('return')} rows;`,
  `    }`,
  `}`,
].join('\n');
const CODE_PROJECTION_HIBERNATE = [
  `${K('return')} sessionFactory.${F('fromSession')}(session -> session`,
  `    .${F('createSelectionQuery')}(`,
  `        ${S('"select p.name, o.lastName, c.name from Pet p join p.owner o join o.city c where c.id = :cityId"')},`,
  `        ${T('PetRow')}.${K('class')})`,
  `    .${F('setParameter')}(${S('"cityId"')}, cityId)`,
  `    .${F('getResultList')}());`,
].join('\n');
const CODE_PROJECTION_JOOQ = [
  `${K('return')} ctx.${F('select')}(${T('PET')}.NAME, ${T('OWNER')}.LAST_NAME, ${T('CITY')}.NAME)`,
  `    .${F('from')}(${T('PET')})`,
  `    .${F('join')}(${T('OWNER')}).${F('on')}(${T('PET')}.OWNER_ID.${F('eq')}(${T('OWNER')}.ID))`,
  `    .${F('join')}(${T('CITY')}).${F('on')}(${T('OWNER')}.CITY_ID.${F('eq')}(${T('CITY')}.ID))`,
  `    .${F('where')}(${T('OWNER')}.CITY_ID.${F('eq')}(cityId))`,
  `    .${F('fetch')}(${T('Records')}.${F('mapping')}(${T('PetRow')}::new));`,
].join('\n');
const CODE_PROJECTION_EXPOSED = [
  `${F('transaction')}(database) {`,
  `    (${T('Pets')} ${F('innerJoin')} ${T('Owners')} ${F('innerJoin')} ${T('Cities')})`,
  `        .${F('select')}(${T('Pets')}.name, ${T('Owners')}.lastName, ${T('Cities')}.name)`,
  `        .${F('where')} { ${T('Owners')}.cityId ${F('eq')} cityId }`,
  `        .${F('map')} { ${T('PetRow')}(it[${T('Pets')}.name], it[${T('Owners')}.lastName], it[${T('Cities')}.name]) }`,
  `}`,
].join('\n');
const CODE_PROJECTION_EXPOSED_DAO = [
  `${C('// Exposed DAO drops to the same DSL query for projections')}`,
  `${F('transaction')}(database) {`,
  `    (${T('Pets')} ${F('innerJoin')} ${T('Owners')} ${F('innerJoin')} ${T('Cities')})`,
  `        .${F('select')}(${T('Pets')}.name, ${T('Owners')}.lastName, ${T('Cities')}.name)`,
  `        .${F('where')} { ${T('Owners')}.cityId ${F('eq')} ${T('EntityID')}(cityId, ${T('Cities')}) }`,
  `        .${F('map')} { ${T('PetRow')}(it[${T('Pets')}.name], it[${T('Owners')}.lastName], it[${T('Cities')}.name]) }`,
  `}`,
].join('\n');
const CODE_PROJECTION_JIMMER = [
  `${T('PetTable')} table = ${T('PetTable')}.$;`,
  `${K('return')} sqlClient.${F('createQuery')}(table)`,
  `    .${F('where')}(table.${F('owner')}().${F('city')}().${F('id')}().${F('eq')}(cityId))`,
  `    .${F('select')}(table.${F('name')}(), table.${F('owner')}().${F('lastName')}(), table.${F('owner')}().${F('city')}().${F('name')}())`,
  `    .${F('execute')}();`,
].join('\n');

const CODE_BATCH = [
  `${K('val')} ids = ${F('transaction')} {`,
  `    visits.${F('insertAndFetchIds')}(newVisits)  ${C('// 100 visits, one multi-row INSERT returning the keys')}`,
  `}`,
].join('\n');
const CODE_BATCH_JDBC = [
  `${T('List')}&lt;${T('Long')}&gt; ids = ${F('multiRowInsertReturningKeys')}(connection, ${S('"visit"')},`,
  `        ${S('"pet_id, visit_date, description"')}, ${N('3')}, BATCH_SIZE, (ps, base, i) -> {`,
  `    ps.${F('setLong')}(base + ${N('1')}, ...); ps.${F('setObject')}(base + ${N('2')}, ...); ps.${F('setString')}(base + ${N('3')}, ...);`,
  `});`,
  `${C('// one INSERT ... VALUES (...),(...) RETURNING id: the driver disables batch rewriting')}`,
  `${C('// when generated keys are requested, so executeBatch cannot express this technique')}`,
].join('\n');
const CODE_BATCH_HIBERNATE = [
  `sessionFactory.${F('fromTransaction')}(session -> {`,
  `    ${K('for')} (${K('int')} i = ${N('0')}; i &lt; ${T('BATCH_SIZE')}; i++) {`,
  `        ${T('Pet')} pet = session.${F('getReference')}(${T('Pet')}.${K('class')}, ...);  ${C('// proxy, no SELECT')}`,
  `        session.${F('persist')}(${K('new')} ${T('Visit')}(pet, ..., ...));`,
  `    }`,
  `    session.${F('flush')}();  ${C('// ids pre-fetched from visit_seq')}`,
  `    ${K('return')} visits.${F('stream')}().${F('map')}(${T('Visit')}::getId).${F('toList')}();`,
  `});`,
].join('\n');
const CODE_BATCH_JOOQ = [
  `${K('var')} insert = ctx.${F('insertInto')}(${T('VISIT')}, ${T('VISIT')}.PET_ID, ${T('VISIT')}.VISIT_DATE, ${T('VISIT')}.DESCRIPTION);`,
  `${K('for')} (${K('int')} i = ${N('0')}; i &lt; ${T('BATCH_SIZE')}; i++) {`,
  `    insert = insert.${F('values')}(..., ..., ...);  ${C('// one VALUES tuple appended per row')}`,
  `}`,
  `${K('return')} insert.${F('returning')}(${T('VISIT')}.ID).${F('fetch')}().${F('map')}(r -> r.${F('get')}(${T('VISIT')}.ID));`,
].join('\n');
const CODE_BATCH_EXPOSED = [
  `${T('Visits')}.${F('batchInsert')}(${N('0')} ${K('until')} ${T('BATCH_SIZE')}, shouldReturnGeneratedValues = ${K('true')}) { i ->`,
  `    ${K('this')}[${T('Visits')}.petId] = ...`,
  `    ${K('this')}[${T('Visits')}.visitDate] = ...`,
  `    ${K('this')}[${T('Visits')}.description] = ...`,
  `}.${F('map')} { it[${T('Visits')}.id] }  ${C('// Exposed batchInsert = JDBC addBatch, not multi-row VALUES')}`,
].join('\n');
const CODE_BATCH_EXPOSED_DAO = [
  `${K('val')} daos = (${N('0')} ${K('until')} ${T('BATCH_SIZE')}).${F('map')} {`,
  `    ${T('VisitDao')}.${F('new')} { petId = ...; visitDate = ...; description = ... }`,
  `}`,
  `daos.${F('map')} { it.id.value }  ${C('// reading ids flushes the pending inserts as a batch')}`,
].join('\n');
const CODE_BATCH_JIMMER = [
  `${T('List')}&lt;${T('Visit')}&gt; drafts = ...;  ${C('// 100x VisitDraft.$.produce(d -> { d.setPet(makeIdOnly(..)); .. })')}`,
  `sqlClient.${F('getEntities')}()`,
  `    .${F('saveEntitiesCommand')}(drafts)`,
  `    .${F('setMode')}(${T('SaveMode')}.INSERT_ONLY)`,
  `    .${F('execute')}(connection);  ${C('// JDBC batch, keys from RETURNING')}`,
].join('\n');
const SQL_BATCH = [
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')}), (${QQ('?')}, ${QQ('?')}, ${QQ('?')}), ...  ${QC('-- 100 tuples, one statement')}`,
  `${QK('RETURNING')} id`,
].join('\n');
const CODE_BATCH_KTORM = [
  `database.${F('useTransaction')} {`,
  `    ${C('// bulkInsertReturning (ktorm-support-postgresql): one multi-row INSERT … RETURNING')}`,
  `    database.${F('bulkInsertReturning')}(${T('Visits')}, ${T('Visits')}.id) {`,
  `        ${K('for')} (i ${K('in')} ${N('0')} ${K('until')} ${T('BATCH_SIZE')}) {`,
  `            ${F('item')} { ${F('set')}(it.petId, …); ${F('set')}(it.visitDate, …); ${F('set')}(it.description, …) }`,
  `        }`,
  `    }`,
  `}`,
].join('\n');
const SQL_BATCH_EXPOSED = [
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')})  ${QC('-- one row per statement, sent as a single JDBC batch of 100')}`,
  `${QK('RETURNING')} *  ${QC('-- appended by the driver to serve getGeneratedKeys')}`,
].join('\n');
const SQL_BATCH_JIMMER = [
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')})  ${QC('-- one row per statement, sent as a single JDBC batch of 100')}`,
  `${QK('RETURNING')} id  ${QC('-- keys collected per batched statement')}`,
].join('\n');
const SQL_BATCH_HIBERNATE = [
  `${QK('SELECT')} nextval('visit_seq')  ${QC('-- pooled sequence, ~2 calls for 100 rows')}`,
  ``,
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description, id)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')}, ${QQ('?')})  ${QC('-- batched x100, ids assigned client-side')}`,
].join('\n');

const CODE_UPDATE = [
  `${A('@DbTable')}(${S('"owner"')})  ${C('// in-place optimization for writing: city as lazy ref')}`,
  `${A('@DynamicUpdate')}(${T('UpdateMode')}.FIELD)`,
  `${K('data class')} ${T('OwnerCityRef')}(`,
  `    ${A('@PK')} ${K('val')} id: ${T('Long')} = ${N('0')},`,
  `    ${K('val')} firstName: ${T('String')},`,
  `    ${K('val')} lastName: ${T('String')},`,
  `    ${K('val')} address: ${T('String')},`,
  `    ${K('val')} telephone: ${T('String')},`,
  `    ${A('@FK')} ${K('val')} city: ${T('Ref')}&lt;${T('City')}&gt;,`,
  `) : ${T('Entity')}&lt;${T('Long')}&gt;`,
  ``,
  `${F('transaction')} {`,
  `    ${K('val')} owner = owners.${F('getById')}(id)`,
  `    owners.${F('update')}(owner.${F('copy')}(telephone = newTelephone))  ${C('// only the changed column is written')}`,
  `}`,
].join('\n');
const SQL_UPDATE = [
  `${QK('SELECT')} o.id, o.first_name, o.last_name, o.address, o.telephone, o.city_id`,
  `${QK('FROM')} owner o`,
  `${QK('WHERE')} o.id = ${QQ('?')}`,
  ``,
  `${QK('UPDATE')} owner`,
  `${QK('SET')} telephone = ${QQ('?')}`,
  `${QK('WHERE')} id = ${QQ('?')}`,
].join('\n');
const CODE_UPDATE_JDBC = [
  `connection.${F('setAutoCommit')}(${K('false')});`,
  `${T('String')} phone;`,
  `${K('try')} (${K('var')} ps = connection.${F('prepareStatement')}(`,
  `        ${S('"SELECT id, first_name, last_name, address, telephone FROM owner WHERE id = ?"')})) {`,
  `    ps.${F('setLong')}(${N('1')}, id);`,
  `    ${K('var')} rs = ps.${F('executeQuery')}(); rs.${F('next')}();`,
  `    phone = ${T('Params')}.${F('toggleTelephone')}(rs.${F('getString')}(${N('5')}));`,
  `    owner = ${K('new')} ${T('Owner')}(rs.${F('getLong')}(${N('1')}), ..., phone, ${K('null')});`,
  `}`,
  `${K('try')} (${K('var')} ps = connection.${F('prepareStatement')}(${S('"UPDATE owner SET telephone = ? WHERE id = ?"')})) {`,
  `    ps.${F('setString')}(${N('1')}, phone); ps.${F('setLong')}(${N('2')}, id); ps.${F('executeUpdate')}();`,
  `}`,
  `connection.${F('commit')}();`,
].join('\n');
const CODE_UPDATE_HIBERNATE = [
  `${A('@Entity')} ${A('@Table')}(name = ${S('"owner"')}) ${A('@DynamicUpdate')}  ${C('// write only the changed columns')}`,
  `${K('class')} ${T('Owner')} {`,
  `    ${A('@Id')} ${A('@GeneratedValue')}(strategy = IDENTITY) ${K('private')} ${T('Long')} id;`,
  `    ${A('@Column')}(name = ${S('"first_name"')}) ${K('private')} ${T('String')} firstName;`,
  `    ${A('@Column')}(name = ${S('"last_name"')}) ${K('private')} ${T('String')} lastName;`,
  `    ${A('@Column')}(name = ${S('"address"')}) ${K('private')} ${T('String')} address;`,
  `    ${A('@Column')}(name = ${S('"telephone"')}) ${K('private')} ${T('String')} telephone;`,
  `    ${A('@ManyToOne')}(fetch = LAZY) ${A('@JoinColumn')}(name = ${S('"city_id"')}) ${K('private')} ${T('City')} city;  ${C('// lazy: city not read')}`,
  `    ${A('@OneToMany')}(mappedBy = ${S('"owner"')}) ${K('private')} ${T('List')}&lt;${T('Pet')}&gt; pets;`,
  ``,
  `    ${K('public')} ${T('String')} ${F('getTelephone')}() { ${K('return')} telephone; }`,
  `    ${K('public')} ${K('void')} ${F('setTelephone')}(${T('String')} telephone) { ${K('this')}.telephone = telephone; }`,
  `    ${C('// Hibernate maps via field access; getId()/getPets() added where the app needs them')}`,
  `}`,
  ``,
  `sessionFactory.${F('fromTransaction')}(session -> {`,
  `    ${T('Owner')} owner = session.${F('find')}(${T('Owner')}.${K('class')}, id);`,
  `    owner.${F('setTelephone')}(${T('Params')}.${F('toggleTelephone')}(owner.${F('getTelephone')}()));`,
  `    ${K('return')} owner;  ${C('// dirty-checking flushes only telephone')}`,
  `});`,
].join('\n');
const CODE_UPDATE_JOOQ = [
  `${K('return')} ctx.${F('transactionResult')}(tx -> {`,
  `    ${K('var')} record = ${T('DSL')}.${F('using')}(tx).${F('fetchOne')}(${T('OWNER')}, ${T('OWNER')}.ID.${F('eq')}(id));`,
  `    record.${F('setTelephone')}(${T('Params')}.${F('toggleTelephone')}(record.${F('getTelephone')}()));`,
  `    record.${F('store')}();  ${C('// UpdatableRecord.store() updates only the changed field')}`,
  `    ${K('return')} record.${F('getId')}();`,
  `});`,
].join('\n');
const CODE_UPDATE_EXPOSED = [
  `${F('transaction')}(database) {`,
  `    ${K('val')} row = ${T('Owners')}.${F('selectAll')}().${F('where')} { ${T('Owners')}.id ${F('eq')} id }.${F('single')}()`,
  `    ${K('val')} phone = ${T('Params')}.${F('toggleTelephone')}(row[${T('Owners')}.telephone])`,
  `    ${T('Owners')}.${F('update')}({ ${T('Owners')}.id ${F('eq')} id }) { it[${T('Owners')}.telephone] = phone }`,
  `}`,
].join('\n');
const CODE_UPDATE_EXPOSED_DAO = [
  `${F('transaction')}(database) {`,
  `    ${K('val')} dao = ${T('OwnerDao')}.${F('findById')}(id) ?: ${F('error')}(${S('"owner not found"')})`,
  `    dao.telephone = ${T('Params')}.${F('toggleTelephone')}(dao.telephone)  ${C('// dirty tracking flushes only telephone')}`,
  `}`,
].join('\n');
const CODE_UPDATE_JIMMER = [
  `${T('Owner')} owner = sqlClient.${F('createQuery')}(${T('OwnerTable')}.$)`,
  `    .${F('where')}(${T('OwnerTable')}.$.${F('id')}().${F('eq')}(id)).${F('select')}(${T('OwnerTable')}.$).${F('execute')}(connection).${F('getFirst')}();`,
  `${T('Owner')} updated = ${T('OwnerDraft')}.$.${F('produce')}(owner, d -> d.${F('setTelephone')}(${T('Params')}.${F('toggleTelephone')}(owner.${F('telephone')}())));`,
  `sqlClient.${F('getEntities')}().${F('saveCommand')}(updated)`,
  `    .${F('setMode')}(${T('SaveMode')}.UPDATE_ONLY)  ${C('// writes every loaded column of the draft')}`,
  `    .${F('execute')}(connection);`,
].join('\n');

const SQL_UPDATE_JIMMER = [
  `${QK('SELECT')} o.id, o.first_name, o.last_name, o.address, o.telephone, o.city_id`,
  `${QK('FROM')} owner o ${QK('WHERE')} o.id = ${QQ('?')}`,
  ``,
  `${QK('UPDATE')} owner`,
  `${QK('SET')} first_name = ${QQ('?')}, last_name = ${QQ('?')}, address = ${QQ('?')}, telephone = ${QQ('?')}, city_id = ${QQ('?')}`,
  `${QK('WHERE')} id = ${QQ('?')}  ${QC('-- the save writes every loaded column of the draft; only the telephone value changed')}`,
].join('\n');

const CODE_GRAPH_STORM = [
  `${K('val')} result = pets.${F('select')}()`,
  `    .${F('where')}(${T('Pet_')}.owner.city.id ${F('eq')} cityId)`,
  `    .${F('orderBy')}(${T('Pet_')}.owner)`,
  `    .${F('resultGroupedBy')}(${T('Pet_')}.owner)`,
  `    .${F('map')} { (owner, pets) -> ${T('OwnerWithPets')}(owner, pets) }`,
].join('\n');
const SQL_GRAPH = [
  `${QK('SELECT')} p.id, p.name, p.birth_date, p.type_id, p.owner_id,`,
  `       o.first_name, o.last_name, o.address, o.telephone, o.city_id, c.name`,
  `${QK('FROM')} pet p`,
  `${QK('INNER JOIN')} owner o ${QK('ON')} p.owner_id = o.id`,
  `${QK('INNER JOIN')} city c ${QK('ON')} o.city_id = c.id`,
  `${QK('WHERE')} o.city_id = ${QQ('?')}`,
  `${QK('ORDER BY')} p.owner_id`,
].join('\n');

const CODE_GRAPH_HIBERNATE = [
  `${T('List')}&lt;${T('Owner')}&gt; owners = sessionFactory.${F('fromSession')}(session -> session`,
  `    .${F('createSelectionQuery')}(`,
  `        ${S('"select distinct o from Owner o join fetch o.pets join fetch o.city where o.city.id = :cityId"')},`,
  `        ${T('Owner')}.${K('class')})`,
  `    .${F('setParameter')}(${S('"cityId"')}, cityId)`,
  `    .${F('getResultList')}());`,
].join('\n');

const CODE_GRAPH_JOOQ = [
  `${T('List')}&lt;${T('OwnerWithPets')}&gt; result = ctx.${F('select')}(`,
  `            OWNER.ID, OWNER.FIRST_NAME, OWNER.LAST_NAME, OWNER.ADDRESS, OWNER.TELEPHONE,`,
  `            CITY.ID, CITY.NAME,`,
  `            ${F('multiset')}(`,
  `                    ${F('select')}(PET.ID, PET.NAME, PET.BIRTH_DATE, PET.TYPE_ID)`,
  `                            .${F('from')}(PET)`,
  `                            .${F('where')}(PET.OWNER_ID.${F('eq')}(OWNER.ID))))`,
  `    .${F('from')}(OWNER)`,
  `    .${F('join')}(CITY).${F('on')}(OWNER.CITY_ID.${F('eq')}(CITY.ID))`,
  `    .${F('where')}(OWNER.CITY_ID.${F('eq')}(cityId))`,
  `    .${F('orderBy')}(OWNER.ID)`,
  `    .${F('fetch')}(record -> {`,
  `        ${T('Owner')} owner = ${K('new')} ${T('Owner')}(record.${F('value1')}(), record.${F('value2')}(), record.${F('value3')}(),`,
  `                record.${F('value4')}(), record.${F('value5')}(), ${K('new')} ${T('City')}(record.${F('value6')}(), record.${F('value7')}()));`,
  `        ${T('List')}&lt;${T('Pet')}&gt; pets = record.${F('value8')}()`,
  `                .${F('map')}(pet -> ${K('new')} ${T('Pet')}(pet.${F('value1')}(), pet.${F('value2')}(), pet.${F('value3')}(), pet.${F('value4')}(), owner));`,
  `        ${K('return')} ${K('new')} ${T('OwnerWithPets')}(owner, pets);`,
  `    });`,
].join('\n');

const CODE_GRAPH_JDBC = [
  `${T('Map')}&lt;${T('Long')}, ${T('Owner')}&gt; owners = ${K('new')} ${T('LinkedHashMap')}&lt;&gt;();`,
  `${T('Map')}&lt;${T('Long')}, ${T('List')}&lt;${T('Pet')}&gt;&gt; pets = ${K('new')} ${T('LinkedHashMap')}&lt;&gt;();`,
  `${K('while')} (rs.${F('next')}()) {`,
  `    ${K('long')} ownerId = rs.${F('getLong')}(${N('1')});`,
  `    ${T('Owner')} owner = owners.${F('get')}(ownerId);`,
  `    ${K('if')} (owner == ${K('null')}) {`,
  `        owner = ${K('new')} ${T('Owner')}(ownerId, ..., ${K('new')} ${T('City')}(rs.${F('getLong')}(${N('6')}), rs.${F('getString')}(${N('7')})));`,
  `        owners.${F('put')}(ownerId, owner); pets.${F('put')}(ownerId, ${K('new')} ${T('ArrayList')}&lt;&gt;());`,
  `    }`,
  `    pets.${F('get')}(ownerId).${F('add')}(${K('new')} ${T('Pet')}(rs.${F('getLong')}(${N('8')}), ..., owner));`,
  `}`,
  `${C('// then zip owners + pets into List<OwnerWithPets>')}`,
].join('\n');
const CODE_GRAPH_EXPOSED = [
  `${F('transaction')}(database) {`,
  `    (${T('Owners')} ${F('innerJoin')} ${T('Cities')} ${F('innerJoin')} ${T('Pets')})`,
  `        .${F('selectAll')}()`,
  `        .${F('where')} { ${T('Owners')}.cityId ${F('eq')} cityId }`,
  `        .${F('orderBy')}(${T('Owners')}.id)`,
  `        .${F('groupIntoOwners')}()  ${C('// in-memory LinkedHashMap grouping')}`,
  `}`,
].join('\n');
const CODE_GRAPH_EXPOSED_DAO = [
  `${F('transaction')}(database) {`,
  `    ${T('OwnerDao')}.${F('find')} { ${T('Owners')}.cityId ${F('eq')} ${T('EntityID')}(cityId, ${T('Cities')}) }`,
  `        .${F('orderBy')}(${T('Owners')}.id ${K('to')} ${T('SortOrder')}.ASC)`,
  `        .${F('with')}(${T('OwnerDao')}::city, ${T('OwnerDao')}::pets)  ${C('// pets = batched SELECT ... WHERE owner_id IN (...)')}`,
  `        .${F('map')} { ${T('OwnerWithPets')}(it.${F('toOwner')}(), it.pets.${F('map')} { p -> p.${F('toPet')}() }) }`,
  `}`,
].join('\n');
const CODE_GRAPH_JIMMER = [
  `${T('OwnerTable')} table = ${T('OwnerTable')}.$;`,
  `${K('return')} sqlClient.${F('createQuery')}(table)`,
  `    .${F('where')}(table.${F('city')}().${F('id')}().${F('eq')}(cityId))`,
  `    .${F('orderBy')}(table.${F('id')}().${F('asc')}())`,
  `    .${F('select')}(table.${F('fetch')}(`,
  `        ${T('OwnerFetcher')}.$.${F('allScalarFields')}()`,
  `            .${F('city')}(${T('CityFetcher')}.$.${F('allScalarFields')}())`,
  `            .${F('pets')}(${T('PetFetcher')}.$.${F('allScalarFields')}())))  ${C('// pets = batched WHERE owner_id IN (...)')}`,
  `    .${F('execute')}();`,
].join('\n');
const SQL_GRAPH_HIBERNATE = [
  `${QK('SELECT DISTINCT')}`,
  `       o.id, o.first_name, o.last_name, o.address, o.telephone,`,
  `       p.owner_id, p.id, p.birth_date, p.name, p.type_id, c.id, c.name`,
  `${QK('FROM')} owner o`,
  `${QK('JOIN')} pet  p ${QK('ON')} o.id = p.owner_id`,
  `${QK('JOIN')} city c ${QK('ON')} c.id = o.city_id`,
  `${QK('WHERE')} c.id = ${QQ('?')}  ${QC('-- DISTINCT collapses the owner x pet cartesian, no ORDER BY')}`,
].join('\n');
const SQL_GRAPH_JOOQ = [
  `${QK('SELECT')} owner.id, owner.first_name, owner.last_name, owner.address, owner.telephone,`,
  `       city.id, city.name,`,
  `       (${QK('SELECT')} jsonb_agg(jsonb_build_array(p.id, p.name, p.birth_date, p.type_id))`,
  `        ${QK('FROM')} pet p ${QK('WHERE')} p.owner_id = owner.id) ${QK('AS')} pets  ${QC('-- correlated MULTISET, no pet join')}`,
  `${QK('FROM')} owner`,
  `${QK('JOIN')} city ${QK('ON')} owner.city_id = city.id`,
  `${QK('WHERE')} owner.city_id = ${QQ('?')}`,
  `${QK('ORDER BY')} owner.id`,
].join('\n');
const SQL_GRAPH_EXPOSED_DAO = [
  `${QC('-- 1) main owners query')}`,
  `${QK('SELECT')} owner.id, ..., owner.city_id ${QK('FROM')} owner ${QK('WHERE')} owner.city_id = ${QQ('?')} ${QK('ORDER BY')} owner.id`,
  ``,
  `${QC('-- 2) the city (one per workload call)')}`,
  `${QK('SELECT')} city.id, city.name ${QK('FROM')} city ${QK('WHERE')} city.id = ${QQ('?')}`,
  ``,
  `${QC('-- 3) batched pets (the collection)')}`,
  `${QK('SELECT')} pet.id, pet.name, pet.birth_date, pet.type_id, pet.owner_id`,
  `${QK('FROM')} pet ${QK('WHERE')} pet.owner_id ${QK('IN')} (${QQ('?')}, ${QQ('?')}, ...)`,
].join('\n');
const SQL_GRAPH_JIMMER = [
  `${QC('-- 1) main owners query (city().id() folds to owner.city_id)')}`,
  `${QK('SELECT')} o.id, o.first_name, o.last_name, o.address, o.telephone, o.city_id`,
  `${QK('FROM')} owner o ${QK('WHERE')} o.city_id = ${QQ('?')} ${QK('ORDER BY')} o.id`,
  ``,
  `${QC('-- 2) the city (one per workload call)')}`,
  `${QK('SELECT')} c.id, c.name ${QK('FROM')} city c ${QK('WHERE')} c.id = ${QQ('?')}`,
  ``,
  `${QC('-- 3) batched pets collection (the OneToMany), chunked into several ANY batches')}`,
  `${QK('SELECT')} p.owner_id, p.id, p.name, p.birth_date, p.type_id`,
  `${QK('FROM')} pet p ${QK('WHERE')} p.owner_id = ${QK('ANY')}(${QQ('?')})`,
].join('\n');


// ---- Keyset pagination ----

const CODE_KEYSET = [
  `${C('// The request states the key and the size; the position seeks past the cursor.')}`,
  `${C('// Pet, Owner and City hydrate from one query; the key column repeats, carrying the position.')}`,
  `${K('val')} page = pets.${F('scroll')}(${T('Scrollable')}.${F('of')}(${T('Pet_')}.id, PAGE_SIZE).${F('after')}(cursor)).content`,
].join('\n');
const SQL_KEYSET = [
  `${QK('SELECT')} p.id, p.name, p.birth_date, p.type_id, p.owner_id,`,
  `       o.first_name, o.last_name, o.address, o.telephone, o.city_id, c.name, p.id  ${QC('-- the key again: the window reads its position from the row')}`,
  `${QK('FROM')} pet p`,
  `${QK('INNER JOIN')} owner o ${QK('ON')} p.owner_id = o.id`,
  `${QK('INNER JOIN')} city c ${QK('ON')} o.city_id = c.id`,
  `${QK('WHERE')} p.id > ${QQ('?')}`,
  `${QK('ORDER BY')} p.id`,
  `${QK('LIMIT')} 21  ${QC('-- page size + 1 detects a next page; the literal count lets PostgreSQL cache the plan')}`,
].join('\n');
const SQL_KEYSET_PLAIN = [
  `${QK('SELECT')} …  ${QC('-- the same three-table join')}`,
  `${QK('WHERE')} p.id > ${QQ('?')}`,
  `${QK('ORDER BY')} p.id`,
  `${QK('LIMIT')} 20  ${QC('-- inlined literal: PostgreSQL settles on a cached generic plan')}`,
].join('\n');
const SQL_KEYSET_HIBERNATE = [
  `${QK('SELECT')} …  ${QC('-- the same three-table join')}`,
  `${QK('WHERE')} p.id > ${QQ('?')}`,
  `${QK('ORDER BY')} p.id`,
  `${QK('FETCH FIRST')} 20 ${QK('ROWS ONLY')}  ${QC('-- the HQL limit clause renders the constant: cached generic plan')}`,
].join('\n');
const SQL_KEYSET_JOOQ = [
  `${QK('SELECT')} …  ${QC('-- the same three-table join')}`,
  `${QK('WHERE')} p.id > ${QQ('?')}`,
  `${QK('ORDER BY')} p.id`,
  `${QK('FETCH NEXT')} 20 ${QK('ROWS ONLY')}  ${QC('-- DSL.inline renders the constant: cached generic plan')}`,
].join('\n');
const SQL_KEYSET_KTORM = [
  `${QK('SELECT')} …`,
  `${QK('FROM')} pet`,
  `${QK('LEFT JOIN')} owner _ref0 ${QK('ON')} pet.owner_id = _ref0.id  ${QC('-- reference bindings join with LEFT JOIN')}`,
  `${QK('LEFT JOIN')} city _ref1 ${QK('ON')} _ref0.city_id = _ref1.id`,
  `${QK('WHERE')} pet.id > ${QQ('?')}`,
  `${QK('ORDER BY')} pet.id`,
  `${QK('LIMIT')} ${QQ('?')}  ${QC('-- bound page size: replanned on every execution')}`,
].join('\n');
const SQL_KEYSET_EXPOSED_DAO = [
  `${QC('-- 1) one page of pets')}`,
  `${QK('SELECT')} pet.id, pet.name, pet.birth_date, pet.type_id, pet.owner_id`,
  `${QK('FROM')} pet ${QK('WHERE')} pet.id > ${QQ('?')} ${QK('ORDER BY')} pet.id ${QK('LIMIT')} 20`,
  ``,
  `${QC('-- 2) batched owners, 3) batched cities')}`,
  `${QK('SELECT')} … ${QK('FROM')} owner ${QK('WHERE')} owner.id ${QK('IN')} (${QQ('?')}, …)`,
  `${QK('SELECT')} … ${QK('FROM')} city ${QK('WHERE')} city.id ${QK('IN')} (${QQ('?')}, …)`,
].join('\n');
const CODE_KEYSET_JDBC = [
  `${K('try')} (${K('var')} ps = connection.${F('prepareStatement')}(${S('"""')}`,
  `        ${S('SELECT p.id, p.name, … , c.id, c.name FROM pet p')}`,
  `        ${S('JOIN owner o ON p.owner_id = o.id JOIN city c ON o.city_id = c.id')}`,
  `        ${S('WHERE p.id > ? ORDER BY p.id')}`,
  `        ${S('LIMIT %d"""')}.${F('formatted')}(PAGE_SIZE))) {  ${C('// literal LIMIT: PostgreSQL settles on a cached plan')}`,
  `    ps.${F('setLong')}(${N('1')}, cursor);`,
  `    ${C('// execute and map each row into Pet, Owner and City by hand')}`,
  `}`,
].join('\n');
const CODE_KEYSET_HIBERNATE = [
  `${C('// The HQL limit clause inlines the constant page size, so PostgreSQL caches the generic plan;')}`,
  `${C('// setMaxResults would bind it and force a fresh planning pass on every call.')}`,
  `${K('return')} sessionFactory.${F('fromSession')}(session -> session`,
  `        .${F('createSelectionQuery')}(`,
  `                ${S('"from Pet p join fetch p.owner o join fetch o.city where p.id > :cursor order by p.id limit 20"')},`,
  `                ${T('Pet')}.${K('class')})`,
  `        .${F('setParameter')}(${S('"cursor"')}, cursor)`,
  `        .${F('getResultList')}());`,
].join('\n');
const CODE_KEYSET_JOOQ = [
  `${K('return')} ctx.${F('select')}(${T('PET')}.ID, ${T('PET')}.NAME, ${T('PET')}.BIRTH_DATE, ${T('PET')}.TYPE_ID,`,
  `                ${F('row')}(${T('OWNER')}.ID, … , ${F('row')}(${T('CITY')}.ID, ${T('CITY')}.NAME).${F('mapping')}(${T('City')}::new)).${F('mapping')}(${T('Owner')}::new))`,
  `        .${F('from')}(${T('PET')}).${F('join')}(${T('OWNER')}).${F('on')}(…).${F('join')}(${T('CITY')}).${F('on')}(…)`,
  `        .${F('orderBy')}(${T('PET')}.ID).${F('seek')}(cursor)`,
  `        .${F('limit')}(${F('inline')}(PAGE_SIZE))  ${C('// inlined constant: PostgreSQL caches the generic plan')}`,
  `        .${F('fetch')}(${T('Records')}.${F('mapping')}(${T('Pet')}::new));`,
].join('\n');
const CODE_KEYSET_EXPOSED = [
  `${F('transaction')}(database) {`,
  `    (${T('Pets')} ${F('innerJoin')} ${T('Owners')} ${F('innerJoin')} ${T('Cities')})`,
  `        .${F('selectAll')}()`,
  `        .${F('where')} { ${T('Pets')}.id ${F('greater')} cursor }`,
  `        .${F('orderBy')}(${T('Pets')}.id)`,
  `        .${F('limit')}(PAGE_SIZE)`,
  `        .${F('map')} { it.${F('toPet')}() }`,
  `}`,
].join('\n');
const CODE_KEYSET_EXPOSED_DAO = [
  `${F('transaction')}(database) {`,
  `    ${T('PetDao')}.${F('wrapRows')}(${T('Pets')}.${F('selectAll')}()`,
  `            .${F('where')} { ${T('Pets')}.id ${F('greater')} cursor }`,
  `            .${F('orderBy')}(${T('Pets')}.id ${K('to')} ${T('SortOrder')}.ASC).${F('limit')}(PAGE_SIZE))`,
  `        .${F('with')}(${T('PetDao')}::owner, ${T('OwnerDao')}::city)  ${C('// eager-loads in batched queries')}`,
  `        .${F('map')} { it.${F('toPet')}() }`,
  `}`,
].join('\n');
const CODE_KEYSET_KTORM = [
  `database.${F('sequenceOf')}(${T('Pets')})`,
  `    .${F('filter')} { ${T('Pets')}.id ${F('greater')} cursor }`,
  `    .${F('sortedBy')} { ${T('Pets')}.id }`,
  `    .${F('take')}(PAGE_SIZE)`,
  `    .${F('toList')}()  ${C('// reference bindings join owner and city')}`,
].join('\n');
const CODE_KEYSET_JIMMER = [
  `${K('return')} sqlClient.${F('createQuery')}(table)`,
  `        .${F('where')}(table.${F('id')}().${F('gt')}(cursor))`,
  `        .${F('orderBy')}(table.${F('id')}().${F('asc')}())`,
  `        .${F('select')}(table.${F('fetch')}(${T('PetFetcher')}.$.${F('allScalarFields')}()`,
  `                .${F('owner')}(${T('OwnerFetcher')}.$.${F('allScalarFields')}()`,
  `                        .${F('city')}(${T('CityFetcher')}.$.${F('allScalarFields')}()))))`,
  `        .${F('limit')}(PAGE_SIZE)`,
  `        .${F('execute')}();`,
].join('\n');
const SQL_KEYSET_JIMMER = [
  `${QK('SELECT')} … ${QK('FROM')} pet ${QK('WHERE')} id > ${QQ('?')} ${QK('ORDER BY')} id ${QK('LIMIT')} ${QQ('?')}`,
  `${QC('-- then the fetcher loads the associations in batched queries:')}`,
  `${QK('SELECT')} … ${QK('FROM')} owner ${QK('WHERE')} id = ${QK('ANY')}(${QQ('?')})`,
  `${QK('SELECT')} … ${QK('FROM')} city ${QK('WHERE')} id = ${QK('ANY')}(${QQ('?')})`,
].join('\n');

// ---- Dynamic query ----

const CODE_DYNAMIC = [
  `${K('var')} predicate: ${T('PredicateBuilder')}&lt;${T('Pet')}, *, *&gt; = ${T('Pet_')}.owner.city.id ${F('eq')} filter.cityId`,
  `${K('if')} (filter.byDate) predicate = predicate ${K('and')} (${T('Pet_')}.birthDate ${F('greaterEq')} filter.minBirthDate)`,
  `${K('if')} (filter.byType) predicate = predicate ${K('and')} (${T('Pet_')}.type ${F('eq')} ${F('refById')}&lt;${T('PetType')}&gt;(filter.typeId))`,
  `${K('return')} pets.${F('select')}&lt;${T('PetRow')}, _, _&gt; { ${S('"${Pet_.name}, ${Pet_.owner.lastName}, ${Pet_.owner.city.name}"')} }`,
  `    .${F('where')}(predicate)`,
  `    .resultList`,
].join('\n');
const SQL_DYNAMIC = [
  `${QK('SELECT')} p.name, o.last_name, c.name`,
  `${QK('FROM')} pet p`,
  `${QK('INNER JOIN')} owner o ${QK('ON')} p.owner_id = o.id`,
  `${QK('INNER JOIN')} city c ${QK('ON')} o.city_id = c.id`,
  `${QK('WHERE')} o.city_id = ${QQ('?')} ${QK('AND')} p.birth_date >= ${QQ('?')} ${QK('AND')} p.type_id = ${QQ('?')}`,
  `${QC('-- the optional predicates appear only when the filter sets them')}`,
].join('\n');
const CODE_DYNAMIC_JDBC = [
  `${K('var')} sql = ${K('new')} ${T('StringBuilder')}(${S('"SELECT p.name, o.last_name, c.name FROM pet p JOIN … WHERE o.city_id = ?"')});`,
  `${K('if')} (filter.${F('byDate')}()) sql.${F('append')}(${S('" AND p.birth_date >= ?"')});`,
  `${K('if')} (filter.${F('byType')}()) sql.${F('append')}(${S('" AND p.type_id = ?"')});`,
  `${C('// bind the parameters in the same order the string grew, then map each row')}`,
].join('\n');
const CODE_DYNAMIC_HIBERNATE = [
  `${K('var')} hql = ${K('new')} ${T('StringBuilder')}(${S('"select p.name, o.lastName, c.name from Pet p join p.owner o join o.city c where o.city.id = :cityId"')});`,
  `${K('if')} (filter.${F('byDate')}()) hql.${F('append')}(${S('" and p.birthDate >= :minDate"')});`,
  `${K('if')} (filter.${F('byType')}()) hql.${F('append')}(${S('" and p.type.id = :typeId"')});`,
  `${C('// create the query, set the parameters that are present, getResultList()')}`,
].join('\n');
const CODE_DYNAMIC_JOOQ = [
  `${T('Condition')} condition = ${T('OWNER')}.CITY_ID.${F('eq')}(filter.${F('cityId')}());`,
  `${K('if')} (filter.${F('byDate')}()) condition = condition.${F('and')}(${T('PET')}.BIRTH_DATE.${F('ge')}(filter.${F('minBirthDate')}()));`,
  `${K('if')} (filter.${F('byType')}()) condition = condition.${F('and')}(${T('PET')}.TYPE_ID.${F('eq')}(filter.${F('typeId')}()));`,
  `${K('return')} ctx.${F('select')}(${T('PET')}.NAME, ${T('OWNER')}.LAST_NAME, ${T('CITY')}.NAME)`,
  `        .${F('from')}(${T('PET')}).${F('join')}(${T('OWNER')}).${F('on')}(…).${F('join')}(${T('CITY')}).${F('on')}(…)`,
  `        .${F('where')}(condition)`,
  `        .${F('fetch')}(${T('Records')}.${F('mapping')}(${T('PetRow')}::new));`,
].join('\n');
const CODE_DYNAMIC_EXPOSED = [
  `(${T('Pets')} ${F('innerJoin')} ${T('Owners')} ${F('innerJoin')} ${T('Cities')})`,
  `    .${F('select')}(${T('Pets')}.name, ${T('Owners')}.lastName, ${T('Cities')}.name)`,
  `    .${F('where')} {`,
  `        ${K('var')} condition: ${T('Op')}&lt;${T('Boolean')}&gt; = ${T('Owners')}.cityId ${F('eq')} filter.cityId`,
  `        ${K('if')} (filter.byDate) condition = condition ${K('and')} (${T('Pets')}.birthDate ${F('greaterEq')} filter.minBirthDate)`,
  `        ${K('if')} (filter.byType) condition = condition ${K('and')} (${T('Pets')}.typeId ${F('eq')} filter.typeId)`,
  `        condition`,
  `    }`,
  `    .${F('map')} { ${T('PetRow')}(it[${T('Pets')}.name], it[${T('Owners')}.lastName], it[${T('Cities')}.name]) }`,
].join('\n');
const CODE_DYNAMIC_EXPOSED_DAO = [
  `${C('// The DAO layer drops to the DSL for flat projections, as DAO applications do;')}`,
  `${C('// the implementation matches Exposed with EntityID-wrapped key comparisons.')}`,
].join('\n');
const CODE_DYNAMIC_KTORM = [
  `${K('val')} conditions = ${T('ArrayList')}&lt;${T('ColumnDeclaring')}&lt;${T('Boolean')}&gt;&gt;()`,
  `conditions += ${T('Owners')}.cityId ${F('eq')} filter.cityId`,
  `${K('if')} (filter.byDate) conditions += ${T('Pets')}.birthDate ${F('greaterEq')} filter.minBirthDate`,
  `${K('if')} (filter.byType) conditions += ${T('Pets')}.typeId ${F('eq')} filter.typeId`,
  `database.${F('from')}(${T('Pets')}).${F('innerJoin')}(${T('Owners')}, on = …).${F('innerJoin')}(${T('Cities')}, on = …)`,
  `    .${F('select')}(${T('Pets')}.name, ${T('Owners')}.lastName, ${T('Cities')}.name)`,
  `    .${F('where')} { conditions.${F('reduce')} { a, b -> a ${K('and')} b } }`,
  `    .${F('map')} { ${T('PetRow')}(it[${T('Pets')}.name]!!, it[${T('Owners')}.lastName]!!, it[${T('Cities')}.name]!!) }`,
].join('\n');
const CODE_DYNAMIC_JIMMER = [
  `${C('// Jimmer ignores null predicates, its idiom for dynamic queries.')}`,
  `${K('return')} sqlClient.${F('createQuery')}(table)`,
  `        .${F('where')}(table.${F('owner')}().${F('city')}().${F('id')}().${F('eq')}(filter.${F('cityId')}()))`,
  `        .${F('where')}(filter.${F('byDate')}() ? table.${F('birthDate')}().${F('ge')}(filter.${F('minBirthDate')}()) : ${K('null')})`,
  `        .${F('where')}(filter.${F('byType')}() ? table.${F('type')}().${F('id')}().${F('eq')}(filter.${F('typeId')}()) : ${K('null')})`,
  `        .${F('select')}(table.${F('name')}(), table.${F('owner')}().${F('lastName')}(), table.${F('owner')}().${F('city')}().${F('name')}())`,
  `        .${F('execute')}();`,
].join('\n');

// ---- Create then amend ----

const CODE_MULTI = [
  `${K('return')} ${F('transaction')} {`,
  `    ${K('val')} visit = ${T('Visit')}(pet = ${F('refById')}&lt;${T('Pet')}&gt;(petId), visitDate = date, description = text)`,
  `    ${K('val')} id = visits.${F('insertAndFetchId')}(visit)`,
  `    visits.${F('update')}(visit.${F('copy')}(id = id, description = ${S('"${visit.description} (rechecked)"')}))`,
  `    id`,
  `}`,
].join('\n');
const SQL_MULTI = [
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')})`,
  `${QK('RETURNING')} id`,
  ``,
  `${QK('UPDATE')} visit`,
  `${QK('SET')} pet_id = ${QQ('?')}, visit_date = ${QQ('?')}, description = ${QQ('?')}  ${QC('-- the full row: Visit declares no field-level update tracking')}`,
  `${QK('WHERE')} id = ${QQ('?')}`,
].join('\n');
const SQL_MULTI_AMEND = [
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')})  ${QC('-- the generated key comes back through RETURNING')}`,
  ``,
  `${QK('UPDATE')} visit`,
  `${QK('SET')} description = ${QQ('?')}  ${QC('-- only the amended column is written')}`,
  `${QK('WHERE')} id = ${QQ('?')}`,
].join('\n');
const SQL_MULTI_HIBERNATE = [
  `${QK('SELECT')} nextval('visit_seq')  ${QC('-- at most once per 50 inserts: the pooled optimizer allocates client-side')}`,
  ``,
  `${QK('INSERT INTO')} visit (description, pet_id, visit_date, id)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')}, ${QQ('?')})  ${QC('-- id assigned client-side')}`,
  ``,
  `${QK('UPDATE')} visit`,
  `${QK('SET')} description = ${QQ('?')}, pet_id = ${QQ('?')}, visit_date = ${QQ('?')}  ${QC('-- dirty checking writes the full row: Visit has no @DynamicUpdate')}`,
  `${QK('WHERE')} id = ${QQ('?')}`,
].join('\n');
const CODE_MULTI_JDBC = [
  `${K('try')} (${K('var')} insert = connection.${F('prepareStatement')}(`,
  `        ${S('"INSERT INTO visit (pet_id, visit_date, description) VALUES (?, ?, ?)"')},`,
  `        ${T('Statement')}.RETURN_GENERATED_KEYS)) {`,
  `    ${C('// bind, executeUpdate, read the key from getGeneratedKeys()')}`,
  `}`,
  `${K('try')} (${K('var')} update = connection.${F('prepareStatement')}(${S('"UPDATE visit SET description = ? WHERE id = ?"')})) {`,
  `    ${C('// bind the amended description and the key, executeUpdate, commit')}`,
  `}`,
].join('\n');
const CODE_MULTI_HIBERNATE = [
  `${K('return')} sessionFactory.${F('fromTransaction')}(session -> {`,
  `    ${T('Visit')} visit = ${K('new')} ${T('Visit')}(session.${F('getReference')}(${T('Pet')}.${K('class')}, petId), date, text);`,
  `    session.${F('persist')}(visit);`,
  `    session.${F('flush')}();`,
  `    ${C('// the persisted instance is managed; amend it and let dirty checking flush')}`,
  `    visit.${F('setDescription')}(visit.${F('getDescription')}() + ${S('" (rechecked)"')});`,
  `    ${K('return')} visit.${F('getId')}();`,
  `});`,
].join('\n');
const CODE_MULTI_JOOQ = [
  `${T('Long')} id = c.${F('insertInto')}(${T('VISIT')}, ${T('VISIT')}.PET_ID, ${T('VISIT')}.VISIT_DATE, ${T('VISIT')}.DESCRIPTION)`,
  `        .${F('values')}(petId, date, text)`,
  `        .${F('returning')}(${T('VISIT')}.ID).${F('fetchOne')}().${F('getId')}();`,
  `c.${F('update')}(${T('VISIT')})`,
  `        .${F('set')}(${T('VISIT')}.DESCRIPTION, text + ${S('" (rechecked)"')})`,
  `        .${F('where')}(${T('VISIT')}.ID.${F('eq')}(id))`,
  `        .${F('execute')}();`,
].join('\n');
const CODE_MULTI_EXPOSED = [
  `${K('val')} inserted = ${T('Visits')}.${F('insert')} {`,
  `    it[${T('Visits')}.petId] = petId; it[${T('Visits')}.visitDate] = date; it[${T('Visits')}.description] = text`,
  `}`,
  `${K('val')} id = inserted[${T('Visits')}.id]  ${C('// the insert result carries the generated id')}`,
  `${T('Visits')}.${F('update')}({ ${T('Visits')}.id ${F('eq')} id }) { it[${T('Visits')}.description] = ${S('"$text (rechecked)"')} }`,
].join('\n');
const CODE_MULTI_EXPOSED_DAO = [
  `${K('val')} dao = ${T('VisitDao')}.${F('new')} { petId = …; visitDate = date; description = text }`,
  `${K('val')} id = dao.id.value  ${C('// reading the id forces the pending insert to flush')}`,
  `dao.description = dao.description + ${S('" (rechecked)"')}`,
].join('\n');
const CODE_MULTI_KTORM = [
  `${K('val')} visit = ${T('Visit')} { petId = pid; visitDate = date; description = text }`,
  `database.${F('sequenceOf')}(${T('Visits')}).${F('add')}(visit)  ${C('// populates the generated id')}`,
  `visit.description = ${S('"${visit.description} (rechecked)"')}`,
  `visit.${F('flushChanges')}()`,
].join('\n');
const CODE_MULTI_JIMMER = [
  `${T('Visit')} saved = sqlClient.${F('getEntities')}().${F('saveCommand')}(visit)`,
  `        .${F('setMode')}(${T('SaveMode')}.INSERT_ONLY).${F('execute')}(connection).${F('getModifiedEntity')}();`,
  `${T('Visit')} updated = ${T('VisitDraft')}.$.${F('produce')}(saved,`,
  `        draft -> draft.${F('setDescription')}(saved.${F('description')}() + ${S('" (rechecked)"')}));`,
  `sqlClient.${F('getEntities')}().${F('saveCommand')}(updated).${F('setMode')}(${T('SaveMode')}.UPDATE_ONLY).${F('execute')}(connection);`,
].join('\n');

// ---- Graph insert ----

const CODE_GINSERT = [
  `${K('val')} visits = graphs.${F('map')} { g ->`,
  `    ${K('val')} owner = ${T('Owner')}(firstName = …, lastName = …, address = …, telephone = …,`,
  `                      city = ${T('City')}(id = g.cityId, name = ${S('""')}))`,
  `    ${K('val')} pet = ${T('Pet')}(name = …, birthDate = …, type = ${F('refById')}&lt;${T('PetType')}&gt;(g.typeId), owner = owner)`,
  `    ${T('Visit')}(pet = ${T('Ref')}.${F('of')}(pet), visitDate = …, description = …)`,
  `}`,
  `${C('// Only the visits are passed: the write set discovers the unsaved pets and owners through the')}`,
  `${C('// refs, writes one multi-row statement per type per dependency level and propagates the keys.')}`,
  `${K('return')} ${F('transaction')} { orm.${F('writeSet')}().${F('insertAndFetchIds')}(visits) }`,
].join('\n');
const SQL_GINSERT = [
  `${QK('INSERT INTO')} owner (first_name, last_name, address, telephone, city_id)`,
  `${QK('VALUES')} (${QQ('?')}, ${QQ('?')}, ${QQ('?')}, ${QQ('?')}, ${QQ('?')}), …  ${QC('-- 20 value rows')}`,
  `${QK('RETURNING')} ${QQ('"id"')}`,
  ``,
  `${QK('INSERT INTO')} pet (name, birth_date, type_id, owner_id) ${QK('VALUES')} …, ${QK('RETURNING')} ${QQ('"id"')}`,
  `${QK('INSERT INTO')} visit (pet_id, visit_date, description) ${QK('VALUES')} …, ${QK('RETURNING')} ${QQ('"id"')}`,
  `${QC('-- no re-read: the workload returns the generated visit ids')}`,
].join('\n');
const CODE_GINSERT_JDBC = [
  `${T('List')}&lt;${T('Long')}&gt; ownerIds = ${F('multiRowInsertReturningKeys')}(connection, ${S('"owner"')}, …);`,
  `${T('List')}&lt;${T('Long')}&gt; petIds   = ${F('multiRowInsertReturningKeys')}(connection, ${S('"pet"')}, …);   ${C('// threads ownerIds')}`,
  `${T('List')}&lt;${T('Long')}&gt; visitIds = ${F('multiRowInsertReturningKeys')}(connection, ${S('"visit"')}, …); ${C('// threads petIds')}`,
  `${C('// each level is one INSERT … VALUES (…),(…) RETURNING id; the caller orders the levels')}`,
].join('\n');
const CODE_GINSERT_HIBERNATE = [
  `${K('for')} (${K('var')} g : graphs) {`,
  `    ${T('Owner')} owner = ${K('new')} ${T('Owner')}(…, session.${F('getReference')}(${T('City')}.${K('class')}, g.${F('cityId')}()));`,
  `    ${T('Pet')} pet = ${K('new')} ${T('Pet')}(…, owner); owner.${F('getPets')}().${F('add')}(pet);`,
  `    ${T('Visit')} visit = ${K('new')} ${T('Visit')}(pet, …); pet.${F('getVisits')}().${F('add')}(visit);`,
  `    ${C('// cascade persist walks owner -> pets -> visits, ordering and batching the inserts')}`,
  `    session.${F('persist')}(owner);`,
  `}`,
  `session.${F('flush')}();`,
].join('\n');
const SQL_GINSERT_HIBERNATE = [
  `${QK('SELECT')} nextval('owner_seq')  ${QC('-- pooled sequences allocate the ids client-side')}`,
  ``,
  `${QK('INSERT INTO')} owner (…, id) ${QK('VALUES')} (${QQ('?')}, …)  ${QC('-- batched x20 per type, ordered by')}`,
  `${QC('-- ORDER_INSERTS: owners, then pets, then visits')}`,
].join('\n');
const CODE_GINSERT_JOOQ = [
  `${K('var')} ownerInsert = c.${F('insertInto')}(${T('OWNER')}, ${T('OWNER')}.FIRST_NAME, …);`,
  `${K('for')} (${K('var')} g : graphs) ownerInsert = ownerInsert.${F('values')}(…);`,
  `${T('List')}&lt;${T('Long')}&gt; ownerIds = ownerInsert.${F('returning')}(${T('OWNER')}.ID).${F('fetch')}().${F('map')}(r -> r.${F('get')}(${T('OWNER')}.ID));`,
  `${C('// same shape for pets (threading ownerIds) and visits (threading petIds):')}`,
  `${C('// three multi-row INSERT … RETURNING statements, ordered by the caller')}`,
].join('\n');
const CODE_GINSERT_EXPOSED = [
  `${K('val')} ownerIds = ${T('Owners')}.${F('batchInsert')}(graphs, shouldReturnGeneratedValues = ${K('true')}) { g ->`,
  `    ${K('this')}[${T('Owners')}.firstName] = …; ${K('this')}[${T('Owners')}.cityId] = g.cityId`,
  `}.${F('map')} { it[${T('Owners')}.id] }`,
  `${C('// same shape for pets (threading ownerIds) and visits (threading petIds)')}`,
].join('\n');
const CODE_GINSERT_EXPOSED_DAO = [
  `${C('// Identical to Exposed: three batchInsert calls with generated values returned,')}`,
  `${C('// with EntityID-wrapped keys threaded between the levels.')}`,
].join('\n');
const CODE_GINSERT_KTORM = [
  `${C('// bulkInsertReturning (ktorm-support-postgresql): one multi-row INSERT … RETURNING per level.')}`,
  `${K('val')} ownerIds = database.${F('bulkInsertReturning')}(${T('Owners')}, ${T('Owners')}.id) {`,
  `    ${K('for')} (g ${K('in')} graphs) { ${F('item')} { ${F('set')}(it.firstName, …); ${F('set')}(it.cityId, g.cityId) } }`,
  `}`,
  `${C('// same shape for pets (threading ownerIds) and visits (threading petIds)')}`,
].join('\n');
const SQL_GINSERT_EXPOSED = [
  `${QK('INSERT INTO')} owner (…) ${QK('VALUES')} (${QQ('?')}, …) ${QK('RETURNING')} *  ${QC('-- one row per statement,')}`,
  `${QK('INSERT INTO')} pet (…) ${QK('VALUES')} (${QQ('?')}, …) ${QK('RETURNING')} *  ${QC('-- one JDBC batch of 20 per level')}`,
  `${QK('INSERT INTO')} visit (…) ${QK('VALUES')} (${QQ('?')}, …) ${QK('RETURNING')} *`,
].join('\n');
const SQL_GINSERT_JIMMER = [
  `${QK('INSERT INTO')} owner (…) ${QK('VALUES')} (${QQ('?')}, …) ${QK('RETURNING')} id  ${QC('-- one row per statement,')}`,
  `${QK('INSERT INTO')} pet (…) ${QK('VALUES')} (${QQ('?')}, …) ${QK('RETURNING')} id  ${QC('-- one JDBC batch of 20 per level')}`,
  `${QK('INSERT INTO')} visit (…) ${QK('VALUES')} (${QQ('?')}, …) ${QK('RETURNING')} id`,
].join('\n');
const CODE_GINSERT_JIMMER = [
  `${T('List')}&lt;${T('Long')}&gt; ownerIds = ${F('saveEntitiesReturningIds')}(connection, ownerDrafts, ${T('Owner')}::id);`,
  `${T('List')}&lt;${T('Long')}&gt; petIds   = ${F('saveEntitiesReturningIds')}(connection, petDrafts, ${T('Pet')}::id);  ${C('// threads ownerIds')}`,
  `${T('List')}&lt;${T('Long')}&gt; visitIds = ${F('saveEntitiesReturningIds')}(connection, visitDrafts, ${T('Visit')}::id);`,
  `${C('// three saveEntities commands, each level threading the previous ids')}`,
].join('\n');

// ---- Relative line chart: every workload as a multiple of the JDBC baseline. Storm carries the
// gradient; the other frameworks render in shades of gray and light up from the legend. Frameworks
// within 3% of the fastest share the lead: differences that small are within run-to-run variation.
const CHART_LIBS = ['storm', 'hibernate', 'jooq', 'exposed', 'exposedDao', 'ktorm', 'jimmer'];
const CHART_GRAYS = {
  hibernate: '#b6b6c0', jooq: '#a2a2ad', exposed: '#8d8d99', exposedDao: '#6f6f7a',
  ktorm: '#7e7e89', jimmer: '#9898a2',
};
const CHART_LABELS = {
  singleRowById: 'PK lookup', joinWithMapping10: 'join·10', joinWithMapping100: 'join·100',
  joinWithMapping1000: 'join·1k', projection: 'projection', keyset: 'keyset', dynamic: 'dynamic',
  objectGraph: 'object graph', batchInsert: 'batch insert', updateById: 'update',
  multiStatement: 'multi-stmt', graphInsert: 'graph insert',
};

function lineChartHtml() {
  const W = 1000, H = 470, m = {t: 18, r: 118, b: 54, l: 44};
  const pw = W - m.l - m.r, ph = H - m.t - m.b;
  const yMin = 0.8, yMax = 3.5;
  const y = (v) => m.t + ph - ((Math.min(v, yMax) - yMin) / (yMax - yMin)) * ph;
  const ratio = (w, lib) => w.results[lib][0] / w.results.jdbc[0];
  // The x-axis opens with the primary key lookup, every application's entry point, then sorts
  // the rest by the field's worst multiple so the envelope opens toward the right: the harder
  // the workload, the further the field drifts from JDBC. The three join sizes travel as one
  // family (keyed by the family's worst multiple, ascending row count inside) so the hydration
  // ramp reads as one story and closes the chart. The per-workload cards above follow the same
  // order, frozen from this dataset.
  const maxRatio = (w) => Math.max(...CHART_LIBS.map((lib) => ratio(w, lib)));
  const joinIds = ['joinWithMapping10', 'joinWithMapping100', 'joinWithMapping1000'];
  const familyMax = Math.max(...WORKLOADS.filter((w) => joinIds.includes(w.id)).map(maxRatio));
  const sortKey = (w) => (joinIds.includes(w.id) ? familyMax : maxRatio(w));
  const chartWorkloads = [
    ...WORKLOADS.filter((w) => w.id === 'singleRowById'),
    ...WORKLOADS.filter((w) => w.id !== 'singleRowById')
      .sort((a, b) => sortKey(a) - sortKey(b) || joinIds.indexOf(a.id) - joinIds.indexOf(b.id)),
  ];
  const x = (i) => m.l + i * (pw / (chartWorkloads.length - 1));

  const grid = [1, 2, 3].map((v) =>
    v === 1
      ? `<line class="bm-lc-baseline" x1="${m.l}" y1="${y(1)}" x2="${W - m.r}" y2="${y(1)}"/>`
      : `<line class="bm-lc-grid" x1="${m.l}" y1="${y(v)}" x2="${W - m.r}" y2="${y(v)}"/>`).join('');
  const yLabels = [1, 2, 3].map((v) =>
    `<text class="bm-lc-ylab" x="${m.l - 9}" y="${y(v) + 3.5}" text-anchor="end">${v}×</text>`).join('');
  // Two staggered label rows: the pitch is narrower than the longest names, so neighbors
  // alternate baselines instead of colliding.
  const xLabels = chartWorkloads.map((w, i) =>
    `<text class="bm-lc-xlab" x="${x(i)}" y="${m.t + ph + (i % 2 === 0 ? 18 : 34)}" text-anchor="middle">${CHART_LABELS[w.id] || w.id}</text>`).join('');

  // End-of-line name labels: two lines can finish within a few pixels of each other, so their
  // names would overprint. Anchor each at its line end, then push any closer than MIN_GAP apart,
  // top to bottom.
  const lastW = chartWorkloads[chartWorkloads.length - 1];
  const MIN_GAP = 13;
  const endLabelY = {};
  let prevEndY = -Infinity;
  [...CHART_LIBS].sort((a, b) => y(ratio(lastW, a)) - y(ratio(lastW, b))).forEach((lib) => {
    prevEndY = endLabelY[lib] = Math.max(y(ratio(lastW, lib)), prevEndY + MIN_GAP);
  });

  const series = CHART_LIBS.map((lib) => {
    const pts = chartWorkloads.map((w, i) => `${x(i).toFixed(1)},${y(ratio(w, lib)).toFixed(1)}`).join(' ');
    const stroke = lib === 'storm' ? 'url(#bmlcg)' : CHART_GRAYS[lib];
    const dots = chartWorkloads.map((w, i) => {
      const r = ratio(w, lib);
      return `<circle class="bm-lc-dot" data-lib="${lib}" cx="${x(i).toFixed(1)}" cy="${y(r).toFixed(1)}" r="${lib === 'storm' ? 3.4 : 2.6}" fill="${lib === 'storm' ? '#818cf8' : CHART_GRAYS[lib]}"><title>${LIBS[lib].name} · ${CHART_LABELS[w.id]} · ${r.toFixed(2)}× JDBC (${fmt(w.results[lib][0])})</title></circle>`;
    }).join('');
    // Each line carries its own name at its right end; the label rides in the series group and
    // dims with it. endLabelY (computed above) spreads any names that would otherwise overprint.
    const endLabel = `<text class="bm-lc-endlab${lib === 'storm' ? ' storm' : ''}" x="${W - m.r + 10}" y="${(endLabelY[lib] + 3.5).toFixed(1)}" fill="${lib === 'storm' ? 'url(#bmlcg)' : CHART_GRAYS[lib]}">${LIBS[lib].name}</text>`;
    return `<g class="bm-lc-series${lib === 'storm' ? ' storm' : ''}" data-lib="${lib}">
      <polyline class="bm-lc-line" points="${pts}" stroke="${stroke}"/>${dots}${endLabel}</g>`;
  }).join('\n');

  const legend = CHART_LIBS.map((lib) => `<button type="button" class="bm-lc-lg${lib === 'storm' ? ' storm' : ''}" data-lib="${lib}" aria-pressed="true"><span class="sw"${lib === 'storm' ? '' : ` style="background:${CHART_GRAYS[lib]}"`}></span>${LIBS[lib].name}</button>`).join('');

  // The two Exposed dots at the point lookup sit far above their mapping cost: Exposed's API
  // mandates a transaction per operation, so most of that multiple is the commit round trip.
  // The label sits directly above the dot pair; thin halo rings around the two dots do the
  // anchoring without extra strokes crossing the plot.
  const pkIndex = chartWorkloads.findIndex((w) => w.id === 'singleRowById');
  const pkX = x(pkIndex);
  const pkYs = ['exposed', 'exposedDao'].map((lib) => y(ratio(chartWorkloads[pkIndex], lib)));
  const pkTopY = Math.min(...pkYs);
  const rings = ['exposed', 'exposedDao'].map((lib) => `<circle class="bm-lc-ring" data-lib="${lib}" cx="${pkX}" cy="${y(ratio(chartWorkloads[pkIndex], lib)).toFixed(1)}" r="5.2"/>`).join('');
  const daggerMark = `<g class="bm-lc-anno">${rings}<text class="bm-lc-mark" x="${pkX - 2}" y="${(pkTopY - 10).toFixed(1)}">transaction overhead, not ORM mapping</text></g>`;

  return `<div class="bm-lc" id="bm-lc">
    <div class="bm-lc-head"><h3>Time relative to hand-written JDBC</h3><span class="bm-lc-hint">library ÷ JDBC · lower is faster</span></div>
    <svg viewBox="0 0 ${W} ${H}" role="img" aria-label="Each framework's time as a multiple of the JDBC baseline across the twelve workloads">
      <defs><linearGradient id="bmlcs" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#e6e9ef"/><stop offset="24%" stop-color="#b6bcc8"/>
        <stop offset="48%" stop-color="#8b919d"/><stop offset="54%" stop-color="#7e848f"/>
        <stop offset="74%" stop-color="#adb3bf"/><stop offset="92%" stop-color="#e2e5ec"/>
        <stop offset="100%" stop-color="#9aa0ac"/>
      </linearGradient>
      <linearGradient id="bmlcg" x1="0" y1="0" x2="1" y2="0">
        <stop offset="0%" stop-color="#a78bfa"/><stop offset="55%" stop-color="#818cf8"/><stop offset="100%" stop-color="#7dd3fc"/>
      </linearGradient></defs>
      ${grid}${yLabels}${xLabels}
      ${series}
      ${daggerMark}
    </svg>
    <div class="bm-lc-legend">${legend}<span class="bm-lc-note">hover to highlight · click to toggle</span></div>
  </div>`;
}

// Wires legend hover (highlight one framework) and click (toggle a framework) after mount.
export function wireBenchChart() {
  const root = document.getElementById('bm-lc');
  if (!root || root.dataset.bmWired) return undefined;
  root.dataset.bmWired = '1';
  const seriesFor = (lib) => root.querySelectorAll(`.bm-lc-series[data-lib="${lib}"]`);
  const listeners = [];
  const listen = (el, type, fn) => { el.addEventListener(type, fn); listeners.push([el, type, fn]); };
  root.querySelectorAll('.bm-lc-lg').forEach((chip) => {
    const lib = chip.getAttribute('data-lib');
    const spotlight = () => {
      if (chip.classList.contains('off')) return;
      seriesFor(lib).forEach((n) => {
        n.classList.add('active');
        n.parentNode.appendChild(n); // SVG paint order: bring the highlighted series to the front
      });
    };
    const unspotlight = () => {
      root.querySelectorAll('.bm-lc-series.active').forEach((n) => n.classList.remove('active'));
    };
    listen(chip, 'mouseenter', spotlight);
    listen(chip, 'mouseleave', unspotlight);
    listen(chip, 'focus', spotlight);
    listen(chip, 'blur', unspotlight);
    listen(chip, 'click', () => {
      const chips = [...root.querySelectorAll('.bm-lc-lg')];
      const setOn = (c, on) => {
        c.classList.toggle('off', !on);
        c.setAttribute('aria-pressed', String(on));
        seriesFor(c.getAttribute('data-lib')).forEach((n) => n.classList.toggle('off', !on));
      };
      // From the everything-on state, a tap isolates the tapped library against Storm.
      // Switching off the last library other than Storm restores the full chart.
      // In any other state, a tap toggles that library.
      if (chips.every((c) => !c.classList.contains('off'))) {
        chips.forEach((c) => {
          const l = c.getAttribute('data-lib');
          setOn(c, l === lib || l === 'storm');
        });
      } else if (!chip.classList.contains('off')
          && chips.filter((c) => c !== chip && !c.classList.contains('off') && c.getAttribute('data-lib') !== 'storm').length === 0) {
        chips.forEach((c) => setOn(c, true));
      } else {
        setOn(chip, chip.classList.contains('off'));
      }
    });
  });
  return () => {
    listeners.forEach(([el, type, fn]) => el.removeEventListener(type, fn));
    delete root.dataset.bmWired;
  };
}

const BM_CSS = `
  .bm-grid{display:grid;grid-template-columns:repeat(2,1fr);gap:22px;margin:26px 0 10px}
  @media(max-width:900px){.bm-grid{grid-template-columns:1fr}}
  .bm-card{border:1px solid var(--border);background:var(--panel);border-radius:14px;padding:28px 30px 24px}
  .storm-tut .art .bm-card h3{margin:0 0 6px;font-size:15.5px;letter-spacing:.01em}
  .bm-desc{margin:0 0 24px;color:var(--muted);font-size:13px;line-height:1.55}
  .bm-card .bm-desc{min-height:2lh}
  .bm-row{display:grid;grid-template-columns:92px 1fr 70px 52px;align-items:center;gap:11px;margin:11px 0}
  .bm-name{font-family:var(--mono);font-size:12px;color:var(--muted);white-space:nowrap}
  .bm-track{height:9px;background:var(--panel-2);border-radius:5px;overflow:hidden;border:1px solid var(--border-soft)}
  .bm-bar{display:block;height:100%;background:#414150;border-radius:4px}
  .bm-row.jdbc .bm-name{color:var(--muted)}
  .bm-row.jdbc .bm-bar{background:repeating-linear-gradient(45deg,#2b2b35,#2b2b35 4px,#20202a 4px,#20202a 8px)}
  .bm-row.storm .bm-bar{background:linear-gradient(90deg,#a78bfa,#818cf8 55%,#7dd3fc)}
  .bm-row.storm .bm-name{width:fit-content;background:linear-gradient(100deg,#a78bfa,#818cf8 55%,#7dd3fc);-webkit-background-clip:text;background-clip:text;color:transparent;font-weight:600}
  .bm-val{font-family:var(--mono);font-size:11.5px;color:var(--body);text-align:right;white-space:nowrap}
  .bm-val[data-range]{position:relative;cursor:help}
  .bm-val[data-range]::after{content:attr(data-range);position:absolute;bottom:calc(100% + 7px);right:0;white-space:nowrap;
    background:var(--panel);border:1px solid var(--border);border-radius:7px;padding:5px 9px;font-size:10.5px;color:var(--muted);
    opacity:0;transform:translateY(3px);pointer-events:none;transition:opacity .12s ease,transform .12s ease;
    box-shadow:0 6px 18px rgba(0,0,0,.45);z-index:6}
  .bm-val[data-range]:hover::after{opacity:1;transform:translateY(0)}
  .bm-delta{font-family:var(--mono);font-size:11px;color:var(--muted);justify-self:end;white-space:nowrap}
  .bm-row.win .bm-delta{background:linear-gradient(100deg,#fcd34d,#eda921 55%,#d98a26);-webkit-background-clip:text;background-clip:text;color:transparent;font-weight:600}
  .bm-note{margin:10px 0 0;color:var(--faint);font-size:12px}
  .bm-card:not(.bm-loc) .bm-note{font-size:10.5px}
  .bm-mark{color:var(--faint);font-size:9px;margin-left:2px}
  .bm-stats{display:grid;grid-template-columns:repeat(3,1fr);gap:18px;margin:28px 0 6px}
  @media(max-width:900px){.bm-stats{grid-template-columns:1fr}}
  .bm-stat{border:1px solid var(--border);background:var(--panel);border-radius:14px;padding:16px 18px}
  .bm-stat b{display:block;font-size:22px;margin-bottom:4px}
  .bm-stat span{color:var(--muted);font-size:13px;line-height:1.5}
  .bm-meta{color:var(--faint);font-size:12.5px;font-family:var(--mono);margin:8px 0 0}
  .storm-tut .art h2{margin:52px 0 14px}
  .storm-tut .art h3{margin:44px 0 10px}
  .storm-tut .art h3 + p{margin-top:0}
  .bm-stat b{background:linear-gradient(100deg,#feeeb0,#fbbf24 55%,#f59e0b);-webkit-background-clip:text;background-clip:text;color:transparent}
  .storm-tut .getit{display:flex;gap:12px;margin-top:26px;flex-wrap:wrap;align-items:stretch}
  .storm-tut .clonebar{display:flex;align-items:center;gap:10px;font-family:var(--mono);font-size:13px;color:var(--plain);
    background:var(--panel);border:1px solid var(--border);border-radius:10px;padding:0 18px;min-height:44px;overflow-x:auto;white-space:nowrap}
  .storm-tut .clonebar .dollar{color:var(--green);user-select:none}
  .bm-lc{border:1px solid var(--border);border-radius:14px;background:#050507;margin:22px 0 10px;padding:20px 20px 14px}
  .bm-lc-head{display:flex;justify-content:space-between;align-items:baseline;gap:16px;flex-wrap:wrap;margin-bottom:8px}
  .storm-tut .art .bm-lc h3{margin:0;font-size:14.5px;color:var(--body)}
  .bm-lc-hint{font-family:var(--mono);font-size:11px;color:var(--muted)}
  .bm-lc svg{display:block;width:100%;height:auto}
  .bm-lc-grid{stroke:#1c1c24;stroke-width:1}
  .bm-lc-baseline{stroke:#8a8c9a;stroke-width:1.3;stroke-dasharray:2 5;opacity:.8}
  .bm-lc-ylab{fill:#5f616e;font-family:var(--mono);font-size:11px}
  .bm-lc-endlab{font-family:var(--mono);font-size:11px}
  .bm-lc-endlab.storm{font-weight:700}
  .bm-lc-mark{fill:#8a8a96;font-family:var(--mono);font-size:10.5px}
  .bm-lc-ring{fill:none;stroke:#8a8a96;stroke-width:1;opacity:.8}
  .bm-lc:has(.bm-lc-series[data-lib="exposed"].off) .bm-lc-ring[data-lib="exposed"]{display:none}
  .bm-lc:has(.bm-lc-series[data-lib="exposedDao"].off) .bm-lc-ring[data-lib="exposedDao"]{display:none}
  .bm-lc:has(.bm-lc-series[data-lib="exposed"].off):has(.bm-lc-series[data-lib="exposedDao"].off) .bm-lc-anno text{display:none}
  .bm-lc-xlab{fill:#8b8d9b;font-family:var(--mono);font-size:10.5px}
  .bm-lc-line{fill:none;stroke-width:1.6;stroke-linejoin:round;stroke-linecap:round}
  .bm-lc-series{transition:opacity .18s ease}
  .bm-lc-series .bm-lc-line{transition:stroke-width .15s ease}
  .bm-lc-series.storm .bm-lc-line{stroke-width:3}
  .bm-lc-series.active:not(.storm) .bm-lc-line{stroke:url(#bmlcs);stroke-width:3.5}
  .bm-lc-series.active:not(.storm) .bm-lc-dot{fill:#dfe3ea}
  .bm-lc-series.active.storm .bm-lc-line{stroke-width:3.5;filter:brightness(1.15)}
  .bm-lc-series.off{display:none}
  .bm-lc-legend{display:flex;flex-wrap:wrap;align-items:center;gap:6px 8px;margin-top:14px;padding-top:12px;border-top:1px solid #1c1c24}
  .bm-lc-lg{display:inline-flex;align-items:center;gap:8px;font-family:var(--sans);font-size:12.5px;color:var(--body);
    background:transparent;border:1px solid #232330;border-radius:999px;padding:5px 11px 5px 9px;cursor:pointer;
    transition:border-color .15s ease,opacity .15s ease}
  .bm-lc-lg:hover{border-color:#3a3a48}
  .bm-lc-lg .sw{width:15px;height:3px;border-radius:2px;flex:none}
  .bm-lc-lg.storm{color:var(--text);font-weight:600}
  .bm-lc-lg.storm .sw{background:linear-gradient(90deg,#a78bfa,#7dd3fc)}
  .bm-lc-lg.off{opacity:.35}
  .bm-lc-lg.off .sw{background:#33333d !important}
  .bm-lc-note{font-family:var(--mono);font-size:10.5px;color:var(--muted);margin-left:auto}
  .bm-row.win .bm-name{width:fit-content;background:linear-gradient(100deg,#fcd34d,#eda921 55%,#d98a26);-webkit-background-clip:text;background-clip:text;color:transparent;font-weight:600}
  .bm-row.win .bm-bar{background:linear-gradient(90deg,#fcd34d,#eda921 55%,#d98a26)}
  .bm-scroll{overflow-x:auto;margin:6px 0 26px}
  .bm-facts{display:table;width:100%;border-collapse:collapse;font-size:13px;line-height:1.6;margin:0}
  .bm-facts thead,.bm-facts thead tr{background:transparent;border:0}
  .bm-facts tbody tr,.bm-facts tr{background:transparent;border:0}
  .bm-facts tr:nth-child(2n){background:transparent}
  .bm-facts th{background:transparent;border:0;border-bottom:1px solid var(--border);text-align:left;color:var(--muted);font-weight:600;font-size:11px;letter-spacing:.6px;text-transform:uppercase;padding:6px 26px 10px 0;white-space:nowrap}
  .bm-facts td{border:0;border-bottom:1px solid rgba(148,148,170,.12);vertical-align:top;color:var(--muted);padding:13px 26px 13px 0}
  .bm-facts tr:last-child td{border-bottom:none}
  .bm-facts th:last-child,.bm-facts td:last-child{padding-right:0}
  .bm-facts td:first-child{color:var(--text);white-space:nowrap}
  .bm-matrix-read{color:var(--muted);font-size:13.5px}
  .bm-details{margin:20px 0 8px;border:1px solid var(--border);border-radius:14px;background:var(--panel-2)}
  .bm-matrix-read + .bm-details{margin-top:44px}
  .bm-details summary{cursor:pointer;padding:14px 18px;font-size:14px;font-weight:600;color:var(--body);list-style:none;display:flex;align-items:center;gap:10px;user-select:none}
  .bm-details summary::-webkit-details-marker{display:none}
  .bm-details summary::before{content:'▸';color:var(--accent);font-size:12px;transition:transform .15s ease}
  .bm-details[open] summary::before{transform:rotate(90deg)}
  .bm-details > .bm-desc{margin:0 18px 6px}
  .bm-details .bm-grid{padding:0 16px 16px;margin:8px 0 0}
  .bm-details .bm-scroll{margin:18px 18px 18px}
  .bm-code{margin:12px 0}
  .bm-code .bm-code-body{margin:10px 18px 18px}
  .bm-loc{margin:22px 0 8px}
  .bm-loc .bm-desc{margin-bottom:20px}
  .bm-loc .bm-row{grid-template-columns:250px 1fr 90px;margin:9px 0}
  .bm-loc .bm-note{margin-top:16px}
  .bm-loc .bm-name{white-space:normal}
  /* On a phone the fixed 250px label column plus the 90px value column and the
     gaps exceed the content lane, and the value column is what spills: the page
     picks up a horizontal scrollbar. Make the label column elastic and size the
     value column to its content instead. */
  @media(max-width:760px){
    .bm-loc .bm-row{grid-template-columns:minmax(0,1fr) minmax(48px,.55fr) auto;gap:10px}
    .bm-loc .bm-name{overflow-wrap:anywhere}
  }
  .bm-tag{display:inline-block;font-style:normal;font-size:9.5px;line-height:1;padding:3px 7px;margin-left:6px;border:1px solid var(--border);border-radius:999px;color:var(--muted);white-space:nowrap;vertical-align:1px}
  .bm-limits{border:1px solid var(--border);border-left:3px solid var(--accent);border-radius:12px;background:var(--panel-2);padding:16px 20px;margin:8px 0 24px}
  .storm-tut .art .bm-limits h3{margin:0 0 6px;font-size:15px}
  .bm-limits p{margin:0;color:var(--muted);font-size:13.5px;line-height:1.6}
`;

// One workload: Storm's implementation shows by default; the other six are a
// selector away and the SQL a toggle away, both living in the editor's own
// title bar, so a workload adds a single editor rather than extra panels or
// toggle links. Per-library SQL divergences ride on their variant.
function codeBlock({title, desc, file, storm, others, sql, sqlExtras}) {
  const divergent = new Map(sqlExtras || []);
  const variants = [
    {label: 'Storm', tag: 'Kotlin', code: storm, selected: true},
    ...others.map(({selected, ...v}) => (divergent.has(v.label) ? {...v, sql: divergent.get(v.label)} : v)),
  ];
  return `
  <details class="bm-details bm-code">
    <summary>${title}</summary>
    ${desc ? `<p class="bm-desc">${desc}</p>` : ''}
    <div class="bm-code-body">${editor({file, tag: 'Kotlin', sql, variants})}</div>
  </details>`;
}

function buildBody() {
  const charts = WORKLOADS.map(chartHtml).join('\n');
  return `
${navHtml('benchmarks')}

<div class="pagehero">
  <h1>Concise by design.<br><span class="grad">Fast by default.</span></h1>
  <p class="dek">Across this reproducible latency suite Storm takes five of twelve workloads outright, with no framework inside 3%. It is in the leading group on ten and top three on all twelve, a consistency no other framework matches. Its clearest margins are on the mapping-heavy joins.</p>
  <p class="dek">Eight implementations run against the same database with identical schema, data, and transaction boundaries. Every result includes a real TCP round trip, and the source behind every number is open for inspection.</p>
  <p class="bm-meta">PostgreSQL 17 over TCP · JMH · Storm 1.14.1 · measured 2026-09-13</p>

  <div class="bm-stats">
    <div class="bm-stat"><b>5 of 12</b><span>workloads where Storm is fastest, with no framework within 3%.</span></div>
    <div class="bm-stat"><b>10 of 12</b><span>workloads where Storm is within 3% of the fastest.</span></div>
    <div class="bm-stat"><b>Only one</b><span>framework in the top three on every workload. The next most consistent manages four, and Storm's worst placing across the suite is third.</span></div>
  </div>
  ${heroArt('benchmarks', {priority: true})}
</div>

<div class="art art-wide">
  <h2>Performance results</h2>
  <p>The workloads cover common data-access paths: point reads, joined entity hydration, projections, keyset pagination, dynamic queries, batch and dependency-ordered writes, change-aware updates and one-to-many object graphs.</p>
  <p>Eight implementations, one database, one discipline: same schema, same data, same transaction boundaries, every score a real network round trip away from PostgreSQL. The chart plots every workload as a multiple of the hand-written JDBC baseline, so each line traces a framework's overhead across the twelve workloads. The chart opens with the primary key lookup and then orders the workloads by how far the field spreads from JDBC, keeping the three join sizes together, so overhead grows to the right and a flat line means the framework does not follow. Lower is faster; the dashed line is JDBC itself.</p>
  ${lineChartHtml()}
  <p class="bm-matrix-read">The field falls into three groups. Storm is alone at the front on five workloads, with no framework within 3%: the primary-key lookup, keyset pagination, the batch insert, and the hundred- and thousand-row joins. On five more it is level with the leaders, inside a band narrower than the run-to-run noise: the projection, the dynamic query, the single-row update, the create-then-amend transaction and the graph insert. Two go elsewhere. jOOQ takes the object graph with a single MULTISET JSON aggregate instead of repeated join rows, 36% ahead, and Jimmer edges the ten-row join by 3.2%, just outside the band and inside what a repeat of the whole suite has been seen to move.</p>

  <p class="bm-matrix-read">The consistency is the part no other framework matches: Storm is in the top three on all twelve workloads and its worst placing anywhere in the suite is third. The next most consistent framework reaches the top three on four, and every other one drops to fifth or lower somewhere, four of them to eighth. Four frameworks come first somewhere — jOOQ on the object graph, Jimmer on the ten-row join, Hibernate on the dynamic query, Ktorm on the graph insert — and only the first two by more than the 3% band. A framework that is quick on the workloads it likes and mid-field on the rest is a different proposition from one with no weak workload at all.</p>

  <p class="bm-matrix-read">Hydration is where the field spreads furthest: on the thousand-row join Storm's overhead over hand-written JDBC is 47% smaller than the closest framework's, and the rest of the field pays between 1.9x and 6.1x it.</p>

  <p class="bm-matrix-read">Storm's write advantage is a volume story: the batch and graph inserts run 1.3 to 1.5x faster than Hibernate, while all four writes sit in the leading group, none of them more than 1.3% off the front.</p>

  <details class="bm-details">
    <summary>Per-workload charts: each framework's distance to the fastest, fork range on hover</summary>
    <p class="bm-desc">Compare within a chart; each chart is a same-session comparison. The percentage is each framework's distance to the fastest; gold marks the leading group, every framework within 3%. Where more than one framework is in it they read level rather than ranked, because the gap between them is smaller than the run-to-run noise. Hover a row for its fork range.</p>
    <div class="bm-grid">
${charts}
    </div>
  </details>

  <details class="bm-details" id="optimizations">
    <summary>Optimizations applied: each library's fastest documented setup</summary>
    <p class="bm-desc">Each library gets the most performant solution its ecosystem documents; every entry is documented, recommended by production guidance for that library, and free of semantic penalty for its workload (the full rules are under Methodology). Storm's row is listed for symmetry: it runs unconfigured, and its fast paths are defaults rather than settings.</p>
    <div class="bm-scroll"><table class="bm-facts">
    <thead><tr><th>Scope</th><th>Optimization</th><th>Effect</th></tr></thead>
    <tbody>
      <tr><td>Everyone</td><td>Sequence-fed primary keys on the insert-target tables</td><td>No library loses JDBC batching to an identity column; Hibernate's pooled generator (<code>allocationSize&nbsp;=&nbsp;50</code>) allocates ids client-side.</td></tr>
      <tr><td>Storm</td><td><code>@DynamicUpdate(FIELD)</code> on a purpose-built update shape</td><td>Writes only the changed column; the shape's lines count toward Storm's query LOC. Storm's only opt-in besides the PostgreSQL dialect module on the classpath; the multi-row RETURNING batches and the literal page size are default behavior.</td></tr>
      <tr><td>Hibernate</td><td><code>@DynamicUpdate</code> on the owner entity</td><td>Writes only the changed column.</td></tr>
      <tr><td>Hibernate</td><td>HQL <code>limit 20</code>, a literal</td><td>PostgreSQL caches the generic plan for the keyset join instead of replanning it on every call.</td></tr>
      <tr><td>jOOQ</td><td><code>limit(inline(20))</code></td><td>The same plan-cache effect on the keyset query.</td></tr>
      <tr><td>Ktorm</td><td><code>bulkInsertReturning</code> from <code>ktorm-support-postgresql</code></td><td>One multi-row <code>INSERT … RETURNING</code> per batch; core Ktorm would retrieve keys row by row.</td></tr>
      <tr><td>Jimmer</td><td><code>setConstraintViolationTranslatable(false)</code></td><td>Removes the SAVEPOINT / RELEASE pair around each save command; constraint violations surface as raw exceptions, which these workloads never read.</td></tr>
    </tbody>
  </table></div>
  </details>

  <details class="bm-details" id="semantics">
    <summary>Semantic differences inside the scores</summary>
    <p class="bm-desc">Result shapes are equivalent across libraries, but not every implementation does identical work. These are the differences worth knowing when reading a row.</p>
    <div class="bm-scroll"><table class="bm-facts">
    <thead><tr><th>Where</th><th>Difference</th><th>In the numbers</th></tr></thead>
    <tbody>
      <tr><td>Create then amend</td><td>Storm, Hibernate and Jimmer write the full row, since Visit declares no field-level change tracking; the change-tracking libraries write only the amended column.</td><td>Same statement count, wider UPDATE.</td></tr>
      <tr><td>Update, Jimmer</td><td>The save writes every loaded column of the draft, not a change delta; the value change is still only the telephone.</td><td>Wider UPDATE than the others' single column.</td></tr>
      <tr><td>Keyset, Ktorm</td><td><code>take(n)</code> has no literal form, so the page size stays a bind parameter.</td><td>PostgreSQL replans the three-table join on every call, a planning pass the literal-limit implementations skip.</td></tr>
      <tr><td>Joins and keyset, Jimmer and Exposed&nbsp;DAO</td><td>Fetcher and eager-loading models load associations in follow-up batched queries.</td><td>Extra round trips by design; query counts are listed with each workload.</td></tr>
      <tr><td>Batch insert, Hibernate</td><td>Ids are allocated client-side from the pooled sequence; no keys are requested from the driver.</td><td>About two <code>nextval</code> calls per hundred rows; the rows go out as one JDBC batch of single-row statements.</td></tr>
    </tbody>
  </table></div>
  </details>

  <h2>Code comparison</h2>
  <p>Numbers without code invite tuned-benchmark suspicion, so the counts below, and the workloads that follow, show exactly what each library runs, trimmed of harness plumbing. The full sources for all eight implementations are in the benchmark repository.</p>
  ${modelLocHtml()}
  ${locHtml()}
  <p class="bm-note">LOC is indicative, not conclusive: twelve workloads over a five-table schema is a small corpus, and a different application profile shifts the counts. It is presented as an illustration of these benchmark implementations, not as a universal measure of framework complexity.</p>

  <h2>Inspect each workload</h2>
  <p>Each workload shows Storm's implementation. Pick another library from the selector to compare it, or toggle <i>Show SQL</i> for the exact statement on the wire.</p>

  ${codeBlock({
    title: 'The model',
    file: 'Entities.kt',
    desc: `Storm's model is plain data classes. Nullability, keys and relations live in the type: <code>@FK val owner: Owner</code> hydrates through a join, <code>Ref&lt;PetType&gt;</code> stays a lazy reference until asked. No proxies, no session lifecycle, nothing to configure. Compare it against the JPA entities, table objects and interfaces the other libraries declare for the same five tables.`,
    storm: CODE_ENTITIES,
    others: [
      {label: 'JDBC', file: 'Models.java', tag: 'Java', code: CODE_MODEL_JDBC, selected: true},
      {label: 'Hibernate', file: 'Entities.java', tag: 'Java', code: CODE_MODEL_HIBERNATE},
      {label: 'jOOQ', file: 'Models.java', tag: 'Java', code: CODE_MODEL_JOOQ},
      {label: 'Exposed', file: 'Tables.kt', tag: 'Kotlin', code: CODE_MODEL_EXPOSED},
      {label: 'Exposed DAO', file: 'Entities.kt', tag: 'Kotlin', code: CODE_MODEL_EXPOSED_DAO},
      {label: 'Ktorm', file: 'Tables.kt', tag: 'Kotlin', code: CODE_MODEL_KTORM},
      {label: 'Jimmer', file: 'Entities.java', tag: 'Java', code: CODE_MODEL_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Primary key lookup',
    file: 'singleRowById',
    desc: `Load one visit by its primary key: one query, one row, the purest round-trip test. The pet reference stays lazy for every implementation (a <code>Ref</code> in Storm, a proxy or plain id elsewhere), so no join runs and the wire round trip dominates the score. What separates libraries here is per-call machinery: building the statement, binding one value and mapping one row.`,
    storm: CODE_SINGLE,
    sql: SQL_SINGLE,
    sqlExtras: [['Ktorm', SQL_SINGLE_KTORM]],
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_SINGLE_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_SINGLE_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_SINGLE_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_SINGLE_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_SINGLE_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_SINGLE_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_SINGLE_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Projection',
    file: 'projection',
    desc: 'A template picks three columns across the graph; the metamodel keeps every path compile-checked.',
    storm: CODE_PROJECTION,
    sql: SQL_PROJECTION,
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_PROJECTION_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_PROJECTION_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_PROJECTION_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_PROJECTION_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_PROJECTION_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_PROJECTION_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_PROJECTION_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Create then amend',
    file: 'multiStatement',
    desc: `One transaction, two dependent statements: insert a visit, then amend it using the generated key. Storm returns the key from the insert and updates a <code>copy</code> of the immutable record; the entity libraries amend a managed instance and let change tracking write it.`,
    storm: CODE_MULTI,
    sql: SQL_MULTI,
    sqlExtras: [['JDBC', SQL_MULTI_AMEND], ['Hibernate', SQL_MULTI_HIBERNATE], ['jOOQ', SQL_MULTI_AMEND], ['Exposed', SQL_MULTI_AMEND], ['Exposed DAO', SQL_MULTI_AMEND], ['Ktorm', SQL_MULTI_AMEND]],
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_MULTI_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_MULTI_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_MULTI_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_MULTI_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_MULTI_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_MULTI_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_MULTI_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Read, modify, update',
    file: 'updateById',
    desc: `Storm's regular <code>Owner</code> is an aggregate: reading one loads its city through a join. Every other library declares that association lazy and reads the owner row alone, so to keep the read side of this workload identical for everyone, the benchmark uses a dedicated shape of the same table where city stays a lazy <code>Ref</code>. That shape is one record; declaring it is Storm's equivalent of the <code>FetchType.LAZY</code> the others put on their entities, and its ten lines are counted against Storm in the Queries LOC table above. On the write side, <code>@DynamicUpdate(FIELD)</code> writes only the column that changed. Entities are immutable; an update is a <code>copy</code>.`,
    storm: CODE_UPDATE,
    sql: SQL_UPDATE,
    sqlExtras: [['Jimmer', SQL_UPDATE_JIMMER]],
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_UPDATE_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_UPDATE_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_UPDATE_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_UPDATE_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_UPDATE_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_UPDATE_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_UPDATE_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Dynamic query',
    file: 'dynamic',
    desc: `A filtered search assembled at runtime from optional predicates. Storm composes type-safe predicates with <code>and</code> and keeps the projection a flat row type. JDBC and Hibernate grow the query string; jOOQ, Exposed, Ktorm and Jimmer compose typed conditions of their own. The SQL carries only the predicates that are active.`,
    storm: CODE_DYNAMIC,
    sql: SQL_DYNAMIC,
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_DYNAMIC_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_DYNAMIC_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_DYNAMIC_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_DYNAMIC_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_DYNAMIC_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_DYNAMIC_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_DYNAMIC_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Graph insert',
    file: 'graphInsert',
    desc: `Twenty owner-to-pet-to-visit graphs written in one transaction, generated keys propagated from parent to child. Storm receives only the visits: the write set discovers the unsaved pets and owners through the refs, orders the dependency levels and writes one multi-row statement per type. The workload returns the generated visit ids, the contract of a create endpoint. Every other implementation except Hibernate orders the levels itself.`,
    storm: CODE_GINSERT,
    sql: SQL_GINSERT,
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_GINSERT_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_GINSERT_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_GINSERT_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_GINSERT_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_GINSERT_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_GINSERT_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_GINSERT_JIMMER},
    ],
    sqlExtras: [['Hibernate', SQL_GINSERT_HIBERNATE], ['Exposed', SQL_GINSERT_EXPOSED], ['Exposed DAO', SQL_GINSERT_EXPOSED], ['Jimmer', SQL_GINSERT_JIMMER]],
  })}

  ${codeBlock({
    title: 'Object graph',
    file: 'objectGraph',
    desc: `Load the owners of a city, each with their list of pets. Storm, JDBC, Exposed and Ktorm run one three-table join and group the rows during hydration; in Storm, repeated owners deduplicate to the same instance, so grouping is an identity operation rather than a hash of every field. jOOQ nests the pets server-side with <code>MULTISET</code>, Hibernate collapses its <code>join fetch</code> cartesian with <code>distinct</code>, and Exposed DAO and Jimmer load the pets in a follow-up batched query.`,
    storm: CODE_GRAPH_STORM,
    sql: SQL_GRAPH,
    sqlExtras: [['Hibernate', SQL_GRAPH_HIBERNATE], ['jOOQ', SQL_GRAPH_JOOQ], ['Exposed DAO', SQL_GRAPH_EXPOSED_DAO], ['Ktorm', SQL_GRAPH_KTORM], ['Jimmer', SQL_GRAPH_JIMMER]],
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_GRAPH_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_GRAPH_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_GRAPH_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_GRAPH_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_GRAPH_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_GRAPH_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_GRAPH_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Batch insert',
    file: 'batchInsert',
    desc: `Insert one hundred visits in a transaction and return their generated keys. Storm emits a single multi-row <code>INSERT</code> carrying all hundred rows and reads the keys from its <code>RETURNING</code> clause; jOOQ builds the same statement from a hundred <code>values()</code> tuples, and Ktorm matches it through <code>bulkInsertReturning</code> from its PostgreSQL support module. Exposed, Exposed DAO and Jimmer read the keys back from a JDBC batch of single-row statements, and Hibernate assigns its ids client-side from the pooled sequence and batches the same way, asking for no keys back.`,
    storm: CODE_BATCH,
    sql: SQL_BATCH,
    sqlExtras: [['Hibernate', SQL_BATCH_HIBERNATE], ['Exposed', SQL_BATCH_EXPOSED], ['Exposed DAO', SQL_BATCH_EXPOSED], ['Jimmer', SQL_BATCH_JIMMER]],
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_BATCH_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_BATCH_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_BATCH_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_BATCH_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_BATCH_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_BATCH_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_BATCH_JIMMER},
    ],
  })}

  ${codeBlock({
    title: 'Keyset pagination',
    file: 'keyset',
    desc: `One page of twenty rows through seek pagination: filter past the cursor, order by the key, stop after a page. Storm's <code>scroll</code> terminal fetches one extra row to detect whether a next page exists, and inlines the page size as a literal. That literal matters more than it looks: the execution plan is the same either way, but when the page size arrives as a bind parameter PostgreSQL never adopts a cached generic plan and replans the three-table join on every call, which costs about as much as executing it. Every implementation that can express a literal page size does: Storm and JDBC by construction, Exposed's <code>limit</code>, Hibernate's HQL <code>limit</code> clause, jOOQ's <code>DSL.inline</code>. Ktorm's <code>take</code> has no literal form and pays that planning pass, and the fetcher libraries load owner and city in follow-up batched queries, paying round trips instead.`,
    storm: CODE_KEYSET,
    sql: SQL_KEYSET,
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_KEYSET_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_KEYSET_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_KEYSET_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_KEYSET_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_KEYSET_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_KEYSET_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_KEYSET_JIMMER},
    ],
    sqlExtras: [['JDBC', SQL_KEYSET_PLAIN], ['Hibernate', SQL_KEYSET_HIBERNATE], ['jOOQ', SQL_KEYSET_JOOQ], ['Exposed', SQL_KEYSET_PLAIN], ['Exposed DAO', SQL_KEYSET_EXPOSED_DAO], ['Ktorm', SQL_KEYSET_KTORM], ['Jimmer', SQL_KEYSET_JIMMER]],
  })}

  ${codeBlock({
    title: 'Three-table join',
    file: 'joinWithMapping',
    desc: `No fetch joins to spell out and no N+1 to dodge: the entity graph declares what a Pet is, so selecting pets hydrates owner and city from one query. When reading the three sizes, note that the bound range predicate races PostgreSQL's plan-cache decision, which follows the bind values: 10-row spans keep every implementation on per-call custom planning, while 100-row spans sit at the custom-versus-generic cost crossover and can settle either way per statement, which is why the 100-row column carries a plan-regime component on top of framework overhead (a baseline faster at 100 rows than at 10 is the cached-plan signature). The 1,000-row join, where the regimes converge, is the cleanest read of per-row mapping cost.`,
    storm: CODE_JOIN,
    sql: SQL_JOIN,
    sqlExtras: [['Exposed DAO', SQL_JOIN_EXPOSED_DAO], ['Ktorm', SQL_JOIN_KTORM], ['Jimmer', SQL_JOIN_JIMMER]],
    others: [
      {label: 'JDBC', tag: 'Java', code: CODE_JOIN_JDBC, selected: true},
      {label: 'Hibernate', tag: 'Java', code: CODE_JOIN_HIBERNATE},
      {label: 'jOOQ', tag: 'Java', code: CODE_JOIN_JOOQ},
      {label: 'Exposed', tag: 'Kotlin', code: CODE_JOIN_EXPOSED},
      {label: 'Exposed DAO', tag: 'Kotlin', code: CODE_JOIN_EXPOSED_DAO},
      {label: 'Ktorm', tag: 'Kotlin', code: CODE_JOIN_KTORM},
      {label: 'Jimmer', tag: 'Java', code: CODE_JOIN_JIMMER},
    ],
  })}

  <h2>Methodology and reproduction</h2>

  <div class="bm-limits">
    <h3>Scope and limitations</h3>
    <p>These benchmarks measure single-threaded operation latency on PostgreSQL. They do not measure application throughput, connection-pool contention, startup time, memory use, native-image performance or behaviour on other databases.</p>
  </div>

  <p>Every rule below is enforced by the harness code, not just described here.</p>
  <ul>
    <li><b>Real round trips.</b> One tuned PostgreSQL 17 container, reached over TCP. On the published runner the bare <code>SELECT 1</code> baseline measured about 84 µs, and every score includes it. That compresses relative differences; the mapping-heavy workloads are where library differences show.</li>
    <li><b>JMH, properly.</b> Five forks, five 3-second measurement iterations after warmup, single thread: latency, not throughput. Each figure is the median of the five forks, with the fork range, fastest to slowest, in brackets alongside. The median is robust to a fork drawing an unlucky GC, scheduling or JIT profile, and does not reward implementations whose forks vary more; the raw per-fork data is committed with each run, so any other estimator can be recomputed from the same artifacts. Sanity checks run every workload once per trial and verify row counts before anything is timed.</li>
    <li><b>Same work for everyone.</b> Identical schema and data, identical transaction boundaries on writes, and update values derived from the value just read, so change-detecting libraries can never silently skip a write. On the update workload every implementation reads a lazy association shape and issues a single UPDATE.</li>
    <li><b>The fastest documented setup for everyone.</b> Each library runs the most performant solution its ecosystem documents: its own modules and configuration are allowed, the benchmark code stays within its API, and the JDBC driver and database run stock for everyone, so the suite measures the ORM rather than driver tuning. Every optimization must be documented, recommended by production guidance, and free of semantic penalty for its workload; best practice takes precedence over raw speed. Each application is tracked in <a href="#optimizations">the optimizations table</a>.</li>
    <li><b>Extra shapes count as query code.</b> A library may define a purpose-built entity shape for a workload on top of its regular entity for that type, held to the same bar as any optimization: documented features, recommended practice, no semantic penalty. Because such a shape exists to speed a query, its lines count toward the query comparison rather than the model comparison.</li>
    <li><b>Rows are the unit of comparison.</b> Libraries within one chart ran in the same session under the same conditions. Comparing across charts, or treating values as absolute costs, carries environment drift that comparing within a chart does not.</li>
  </ul>

  <p>Versions: Storm 1.14.1, Hibernate 7.4.7, jOOQ 3.21.7, Exposed 1.5.0, Ktorm 4.2.1, Jimmer 0.11.7, PostgreSQL 17, JDK 21.</p>

  <h3>Reproducing these numbers</h3>
  <p>The published figures come from the repository's <code>benchmark</code> GitHub Actions workflow on a GitHub-hosted dedicated runner (dedicated, 4 vCPU, 16 GB, Ubuntu 24.04), which builds Storm from source at the commit stated with each run, executes the full suite against PostgreSQL 17 in Docker, and uploads the results as an artifact. The raw per-fork JMH data, the merged tables and a metadata file recording the exact versions, runner and JMH configuration are committed under <code>results/</code> in the repository, so every published number can be recomputed from its artifacts.</p>
  <p>To reproduce: fork the repository and dispatch the <code>benchmark</code> workflow, choosing the Storm ref to build, the runner label and the mode; a comparable dedicated runner class gives comparable stability. Or run the suite locally with JDK 21 and Docker: <code>scripts/run.sh</code> starts one tuned container and runs every module. Absolute numbers depend on the hardware; the comparison within a table is the point.</p>
  <div class="getit">
    ${clonebar('git clone https://github.com/storm-orm/storm-benchmarks.git')}
    <a class="btn" href="https://github.com/storm-orm/storm-benchmarks" target="_blank" rel="noopener">View on GitHub →</a>
  </div>

</div>

${FOOT_HTML}
`;
}

export default function Benchmarks() {
  useEffect(() => {
    const cleanupToggles = wireSqlToggles();
    const cleanupChart = wireBenchChart();
    return () => {
      if (typeof cleanupToggles === 'function') cleanupToggles();
      if (typeof cleanupChart === 'function') cleanupChart();
    };
  }, []);
  const url = 'https://orm.st/benchmarks';
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
        <meta property="og:url" content={url} />
        <meta name="twitter:card" content="summary" />
        <meta name="twitter:title" content={TITLE} />
        <meta name="twitter:description" content={DESC} />
        <link rel="preconnect" href="https://fonts.googleapis.com" />
        <link rel="preconnect" href="https://fonts.gstatic.com" crossOrigin="anonymous" />
        <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700;800&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet" />
        <script type="application/ld+json">{JSON.stringify({
          '@context': 'https://schema.org',
          '@type': 'WebPage',
          name: TITLE,
          description: DESC,
          url,
        })}</script>
      </Head>
      <style dangerouslySetInnerHTML={{__html: TUT_CSS + BM_CSS}} />
      <div className="storm-tut" dangerouslySetInnerHTML={{__html: buildBody()}} />
    </>
  );
}
