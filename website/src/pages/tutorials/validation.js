import React from 'react';
import {
  TutorialPage, navHtml, FOOT_HTML, editor,
  K, T, S, C, F, N, A, P,
} from '../../components/tutorial/tutorialTheme';

// Tutorial: validating input end to end. Storm facts verified against
// docs/data-validation.md, docs/error-handling.md, IntegrityViolation and
// EntityValidator. Every sample was compiled and run on H2 from the Ktor,
// Spring Boot Kotlin and Spring Boot Java starter templates.

const TITLE = 'Validate Input End to End with Storm';
const DESC =
  'A form posts an owner. The request type rejects product rules, the column rejects ' +
  'a value too long, the unique constraint rejects a duplicate, and one handler ' +
  'answers with the field that failed.';

const SCHEMA = [
  K('CREATE'), P(' '), K('TABLE'), P(' owner (\n    id         '), K('INTEGER'), P(' '), K('GENERATED'),
  P(' '), K('BY'), P(' '), K('DEFAULT'), P(' '), K('AS'), P(' '), K('IDENTITY'), P(' '), K('PRIMARY'),
  P(' '), K('KEY'), P(',\n    first_name '), K('VARCHAR'), P('('), N('30'), P(') '), K('NOT'),
  P(' '), K('NULL'), P(',\n    last_name  '), K('VARCHAR'), P('('), N('30'), P(') '), K('NOT'),
  P(' '), K('NULL'), P(',\n    telephone  '), K('VARCHAR'), P('('), N('20'), P(') '), K('NOT'),
  P(' '), K('NULL'), P(',\n    '), K('CONSTRAINT'), P(' owner_telephone_uk '), K('UNIQUE'), P(' (telephone)\n);'),
].join('');

const OWNER_KT = [
  K('data'), P(' '), K('class'), P(' '), T('Owner'), P('(\n    '), A('@PK'), P(' '), K('val'),
  P(' id: '), T('Int'), P(' = '), N('0'), P(',\n    '), K('val'), P(' firstName: '), T('String'),
  P(',\n    '), K('val'), P(' lastName: '), T('String'), P(',\n    '), A('@UK'), P(' '), K('val'),
  P(' telephone: '), T('String'), P(',\n) : '), T('Entity'), P('<'), T('Int'), P('> {\n    '),
  K('init'), P(' {\n        '), F('require'), P('(firstName.'), F('isNotBlank'), P('()) { '), S('"An owner has a first name."'),
  P(' }\n    }\n}'),
].join('');

const OWNER_JAVA = [
  K('public'), P(' '), K('record'), P(' '), T('Owner'), P('('), A('@PK'), P(' '), T('Integer'),
  P(' id,\n                    '), T('String'), P(' firstName,\n                    '), T('String'),
  P(' lastName,\n                    '), A('@UK'), P(' '), T('String'), P(' telephone) '), K('implements'),
  P(' '), T('Entity'), P('<'), T('Integer'), P('> {\n    '), K('public'), P(' '), T('Owner'), P(' {\n        '),
  K('if'), P(' (firstName.'), F('isBlank'), P('()) {\n            '), K('throw'), P(' '), K('new'),
  P(' '), T('IllegalArgumentException'), P('('), S('"An owner has a first name."'), P(');\n        }\n    }\n}'),
].join('');

const FORM_SPRING_KT = [
  K('data'), P(' '), K('class'), P(' '), T('OwnerForm'), P('(\n    '), A('@field:NotBlank'), P(' '),
  K('val'), P(' firstName: '), T('String'), P(',\n    '), A('@field:NotBlank'), P(' '), K('val'),
  P(' lastName: '), T('String'), P(',\n    '), A('@field:Pattern'), P('(regexp = '), S('"[0-9 +-]+"'),
  P(') '), K('val'), P(' telephone: '), T('String'), P(',\n)'),
].join('');

const FORM_SPRING_JAVA = [
  K('public'), P(' '), K('record'), P(' '), T('OwnerForm'), P('('), A('@NotBlank'), P(' '), T('String'),
  P(' firstName,\n                        '), A('@NotBlank'), P(' '), T('String'), P(' lastName,\n                        '),
  A('@Pattern'), P('(regexp = '), S('"[0-9 +-]+"'), P(') '), T('String'), P(' telephone) {}'),
].join('');

const FORM_KTOR = [
  A('@Serializable'), P('\n'),
  K('data'), P(' '), K('class'), P(' '), T('OwnerForm'), P('('), K('val'), P(' firstName: '), T('String'),
  P(', '), K('val'), P(' lastName: '), T('String'), P(', '), K('val'), P(' telephone: '), T('String'),
  P(')\n\n'),
  K('fun'), P(' '), T('Application'), P('.'), F('validateOwnerForms'), P('() {\n    '), F('install'),
  P('('), T('RequestValidation'), P(') {\n        '), F('validate'), P('<'), T('OwnerForm'), P('> { form ->\n            '),
  K('when'), P(' {\n                form.firstName.'), F('isBlank'), P('() || form.lastName.'),
  F('isBlank'), P('() -> '), T('ValidationResult'), P('.'), T('Invalid'), P('('), S('"A name is required."'),
  P(')\n                !form.telephone.'), F('matches'), P('('), T('Regex'), P('('), S('"[0-9 +-]+"'),
  P(')) -> '), T('ValidationResult'), P('.'), T('Invalid'), P('('), S('"A telephone number holds digits."'),
  P(')\n                '), K('else'), P(' -> '), T('ValidationResult'), P('.'), T('Valid'), P('\n            }\n        }\n    }\n}'),
].join('');

const ROUTE_SPRING_KT = [
  A('@RestController'), P('\n'),
  K('class'), P(' '), T('OwnerController'), P('('), K('private'), P(' '), K('val'), P(' owners: '),
  T('OwnerRepository'), P(') {\n\n    '), A('@PostMapping'), P('('), S('"/owners"'), P(')\n    '),
  A('@ResponseStatus'), P('('), T('HttpStatus'), P('.'), T('CREATED'), P(')\n    '), K('fun'),
  P(' '), F('create'), P('('), A('@Valid'), P(' '), A('@RequestBody'), P(' form: '), T('OwnerForm'),
  P('): '), T('Owner'), P(' {\n        '), K('val'), P(' owner = '), T('Owner'), P('(firstName = form.firstName, lastName = form.lastName, telephone = form.telephone)\n        owners.'),
  F('validateOrThrow'), P('(owner)\n        '), K('return'), P(' owners.'), F('insertAndFetch'),
  P('(owner)\n    }\n}'),
].join('');

const ROUTE_SPRING_JAVA = [
  A('@RestController'), P('\n'),
  K('public'), P(' '), K('class'), P(' '), T('OwnerController'), P(' {\n\n    '), K('private'),
  P(' '), K('final'), P(' '), T('OwnerRepository'), P(' owners;\n\n    '), K('public'), P(' '),
  T('OwnerController'), P('('), T('OwnerRepository'), P(' owners) {\n        this.owners = owners;\n    }\n\n    '),
  A('@PostMapping'), P('('), S('"/owners"'), P(')\n    '), A('@ResponseStatus'), P('('), T('HttpStatus'),
  P('.'), T('CREATED'), P(')\n    '), K('public'), P(' '), T('Owner'), P(' '), F('create'), P('('),
  A('@Valid'), P(' '), A('@RequestBody'), P(' '), T('OwnerForm'), P(' form) {\n        '), K('var'),
  P(' owner = '), K('new'), P(' '), T('Owner'), P('('), K('null'), P(', form.'), F('firstName'),
  P('(), form.'), F('lastName'), P('(), form.'), F('telephone'), P('());\n        owners.'), F('validateOrThrow'),
  P('(owner);\n        '), K('return'), P(' owners.'), F('insertAndFetch'), P('(owner);\n    }\n}'),
].join('');

const ROUTE_KTOR = [
  K('fun'), P(' '), T('Application'), P('.'), F('ownerRoutes'), P('() {\n    '), K('val'), P(' owners: '),
  T('OwnerRepository'), P(' '), K('by'), P(' dependencies\n    routing {\n        '), F('post'),
  P('('), S('"/owners"'), P(') {\n            '), K('val'), P(' form = call.'), F('receive'), P('<'),
  T('OwnerForm'), P('>()\n            '), K('val'), P(' owner = '), T('Owner'), P('(firstName = form.firstName, lastName = form.lastName, telephone = form.telephone)\n            owners.'),
  F('validateOrThrow'), P('(owner)\n            '), K('val'), P(' created = owners.'), F('insertAndFetch'),
  P('(owner)\n            call.'), F('respond'), P('('), T('HttpStatusCode'), P('.'), T('Created'),
  P(', '), F('mapOf'), P('('), S('"id"'), P(' to created.id))\n        }\n    }\n}'),
].join('');

const HANDLER_SPRING_KT = [
  K('data'), P(' '), K('class'), P(' '), T('FieldError'), P('('), K('val'), P(' fields: '), T('List'),
  P('<'), T('String'), P('>, '), K('val'), P(' kind: '), T('String'), P('?, '), K('val'), P(' maxLength: '),
  T('Int'), P('?)\n\n'),
  A('@RestControllerAdvice'), P('\n'),
  K('class'), P(' '), T('ViolationHandler'), P(' {\n\n    '), A('@ExceptionHandler'), P('('), T('DataIntegrityViolationException'),
  P('::'), K('class'), P(')\n    '), K('fun'), P(' '), F('handle'), P('(exception: '), T('DataIntegrityViolationException'),
  P('): '), T('ProblemDetail'), P(' {\n        '), K('val'), P(' cause = exception.cause\n        '),
  K('if'), P(' (exception !'), K('is'), P(' '), T('ErrorResponse'), P(' || cause !'), K('is'),
  P(' '), T('IntegrityViolationException'), P(') {\n            '), K('throw'), P(' exception\n        }\n        '),
  K('return'), P(' exception.body.apply {\n            '), F('setProperty'), P('('), S('"errors"'),
  P(', cause.'), F('violations'), P('().map { violation ->\n                '), T('FieldError'),
  P('(violation.'), F('fields'), P('().map { it.'), F('fieldPath'), P('() }, violation::'), K('class'),
  P('.simpleName, (violation '), K('as'), P('? '), T('Length'), P(')?.'), F('maxLength'), P('())\n            })\n        }\n    }\n}'),
].join('');

const HANDLER_SPRING_JAVA = [
  K('public'), P(' '), K('record'), P(' '), T('FieldError'), P('('), T('List'), P('<'), T('String'),
  P('> fields, '), T('String'), P(' kind, '), A('@Nullable'), P(' '), T('Integer'), P(' maxLength) {\n\n    '),
  K('static'), P(' '), T('FieldError'), P(' '), F('of'), P('('), T('IntegrityViolation'), P(' violation) {\n        '),
  K('return'), P(' '), K('new'), P(' '), T('FieldError'), P('(\n                violation.'), F('fields'),
  P('().'), F('stream'), P('().'), F('map'), P('('), T('Metamodel'), P('::fieldPath).'), F('toList'),
  P('(),\n                violation.'), F('getClass'), P('().'), F('getSimpleName'), P('(),\n                violation '),
  K('instanceof'), P(' '), T('Length'), P(' length ? length.'), F('maxLength'), P('() : '), K('null'),
  P(');\n    }\n}\n\n'),
  A('@RestControllerAdvice'), P('\n'),
  K('class'), P(' '), T('ViolationHandler'), P(' {\n\n    '), A('@ExceptionHandler'), P('('), T('DataIntegrityViolationException'),
  P('.'), K('class'), P(')\n    '), T('ProblemDetail'), P(' '), F('handle'), P('('), T('DataIntegrityViolationException'),
  P(' exception) {\n        '), K('if'), P(' (!(exception '), K('instanceof'), P(' '), T('ErrorResponse'),
  P(' response)\n                || !(exception.'), F('getCause'), P('() '), K('instanceof'), P(' '),
  T('IntegrityViolationException'), P(' cause)) {\n            '), K('throw'), P(' exception;\n        }\n        '),
  T('ProblemDetail'), P(' problem = response.'), F('getBody'), P('();\n        problem.'), F('setProperty'),
  P('('), S('"errors"'), P(', cause.'), F('violations'), P('().'), F('stream'), P('().'), F('map'),
  P('('), T('FieldError'), P('::of).'), F('toList'), P('());\n        '), K('return'), P(' problem;\n    }\n}'),
].join('');

const HANDLER_KTOR = [
  K('fun'), P(' '), T('Application'), P('.'), F('violationPages'), P('() {\n    '), F('install'),
  P('('), T('StatusPages'), P(') {\n        '), F('stormIntegrityViolations'), P('()\n        '),
  F('exception'), P('<'), T('RequestValidationException'), P('> { call, cause ->\n            call.'),
  F('respond'), P('('), T('HttpStatusCode'), P('.'), T('BadRequest'), P(', cause.reasons)\n        }\n    }\n}'),
].join('');

const BODY = `
${navHtml('tutorials')}

<div class="art">
  <div class="crumbs"><a href="/tutorials/">Tutorials</a><span class="sep">/</span>Validate input</div>
  <h1>Validate input <span class="grad">end to end</span></h1>
  <p class="dek">A form posts an owner, and the client should learn exactly what is wrong with it. Each rule has one place to live and is checked there, once, so nothing is written down twice.</p>
  <div class="meta"><span>The Storm way</span><span>6 min read</span><span>Kotlin and Java</span></div>

  <h2><span class="hno">01</span>The task</h2>
  <p>A <code>POST /owners</code> creates an owner. A name is required, a telephone number holds digits, a name fits its column, and no two owners share a number. Those are four rules of different kinds, and each is checked by whatever knows the answer.</p>

  <h2><span class="hno">02</span>The table and the entity</h2>
  <p>The schema already states the limits and the unique constraint:</p>
  ${editor({file: 'V1__owner.sql', tag: 'SQL', code: SCHEMA})}
  <p>The entity adds what must hold for every owner, wherever it comes from. The check is in the constructor, so an owner without a first name cannot exist:</p>
  ${editor({variants: [
    {label: 'Kotlin', file: 'Owner.kt', tag: 'Kotlin · Storm', code: OWNER_KT, selected: true},
    {label: 'Java', file: 'Owner.java', tag: 'Java · Storm', code: OWNER_JAVA},
  ]})}
  <p>The constructor runs on reads as well, so an invariant has to be true of the rows you already have.</p>

  <h2><span class="hno">03</span>Product rules on the request type</h2>
  <p>A required field and a format are decisions about this form. They belong to the request type and to the framework's own validator, which rejects the request before your code runs:</p>
  ${editor({variants: [
    {label: 'Spring · Kotlin', file: 'OwnerForm.kt', tag: 'Kotlin · Spring', code: FORM_SPRING_KT, selected: true},
    {label: 'Spring · Java', file: 'OwnerForm.java', tag: 'Java · Spring', code: FORM_SPRING_JAVA},
    {label: 'Ktor', file: 'OwnerForm.kt', tag: 'Kotlin · Ktor', code: FORM_KTOR},
  ]})}
  <p>There is no length here. The column says 30, and a second copy of that number could only come to disagree with it. A limit stricter than the column would be a product rule, and it would go here.</p>

  <h2><span class="hno">04</span>Column limits from the schema</h2>
  <p>Build the entity, then check it against its columns:</p>
  ${editor({variants: [
    {label: 'Spring · Kotlin', file: 'OwnerController.kt', tag: 'Kotlin · Spring', code: ROUTE_SPRING_KT, selected: true},
    {label: 'Spring · Java', file: 'OwnerController.java', tag: 'Java · Spring', code: ROUTE_SPRING_JAVA},
    {label: 'Ktor', file: 'Owners.kt', tag: 'Kotlin · Ktor', code: ROUTE_KTOR},
  ]})}
  <p><code>validateOrThrow</code> compares the values against the schema Storm loaded at startup and throws every violation it finds, without a statement. It is optional: the database would refuse the same value, one at a time. Call it when a form should report all its problems at once.</p>

  <h2><span class="hno">05</span>The duplicate</h2>
  <p>The route has no query asking whether the number is taken. Two requests could both get the answer no and both insert. The unique constraint lets exactly one through, so the insert runs and the database decides. Storm reads the refusal and states it in terms of the entity: a <code>Unique</code> violation on the <code>telephone</code> field.</p>

  <h2><span class="hno">06</span>The answer to the client</h2>
  <p>A violation has the same shape whether <code>validateOrThrow</code> found it or the database reported it, and the status says whose fault it is: a conflict with other rows is a 409, and a value that breaks a rule of its own is a 400. With the Spring Boot starter the status needs no code at all, and a handler only adds the fields. In Ktor, one line answers with both:</p>
  ${editor({variants: [
    {label: 'Spring · Kotlin', file: 'ViolationHandler.kt', tag: 'Kotlin · Spring', code: HANDLER_SPRING_KT, selected: true},
    {label: 'Spring · Java', file: 'ViolationHandler.java', tag: 'Java · Spring', code: HANDLER_SPRING_JAVA},
    {label: 'Ktor', file: 'StatusPages.kt', tag: 'Kotlin · Ktor', code: HANDLER_KTOR},
  ]})}
  <p>With the Spring Boot starter, the exception arrives as Spring's <code>DataIntegrityViolationException</code>, or its subclass <code>DuplicateKeyException</code> for a <code>Unique</code> violation, with Storm's exception as the cause. A service catches those types as usual.</p>

  <h2><span class="hno">07</span>What the client receives</h2>
  <table class="cmp">
    <tr><th>The form sent</th><th>Who answered</th><th>Response</th></tr>
    <tr><td>A blank first name</td><td>The request type</td><td>400 from the framework's validator</td></tr>
    <tr><td>A telephone number with letters</td><td>The request type</td><td>400 from the framework's validator</td></tr>
    <tr><td>A first name of 31 characters</td><td><code>validateOrThrow</code></td><td>400, with <code>fields: ["firstName"]</code>, <code>kind: "Length"</code> and <code>maxLength: 30</code></td></tr>
    <tr><td>A number another owner has</td><td>The database</td><td>409, with <code>fields: ["telephone"]</code> and <code>kind: "Unique"</code></td></tr>
  </table>
  <p>No response names a table, a constraint or a statement.</p>

  <h2><span class="hno">08</span>Keep going</h2>
  <p>The reference documentation covers the details:</p>
  <div class="refs">
    <a href="/docs/data-validation">Validating Data</a>
    <a href="/docs/error-handling#integrity-violations">Integrity Violations</a>
    <a href="/docs/spring-integration#exception-translation">Exception Translation</a>
    <a href="/docs/ktor-integration#error-handling">Ktor Error Handling</a>
  </div>
  <div class="cta">
    <a href="/docs/getting-started" class="btn primary">Get started →</a>
    <a href="https://github.com/storm-orm/storm-framework" target="_blank" rel="noopener" class="btn">Star on GitHub</a>
  </div>
</div>

${FOOT_HTML}
`;

export default function ValidationTutorial() {
  return <TutorialPage title={TITLE} description={DESC} slug="validation" body={BODY} />;
}
