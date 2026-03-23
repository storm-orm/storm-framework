# AI-Assisted Development

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Storm is an AI-first ORM. Entities are plain Kotlin data classes or Java records. Queries are explicit SQL. Built-in verification lets AI validate its own work before anything touches production.

:::caution Storm is not for vibe coding
Database code affects data integrity, performance, and security. Generating it without understanding or verifying the result is not something Storm encourages.

Storm keeps you in control. `ORMTemplate.validateSchema()` validates that entities match the database. `SqlCapture` validates that queries match the intent. `@StormTest` runs both checks in an isolated in-memory database before anything reaches production. The AI generates code, then Storm verifies it. That is what AI-first means here.

Automated verification catches mistakes, but it does not replace understanding. Your data layer is not the place to let the codebase drift away from you.
:::

---

## Quick Setup

Install the Storm CLI and run it in your project:

```bash
npm install -g @storm-orm/cli
storm init
```

Or without installing globally:

```bash
npx @storm-orm/cli init
```

The interactive setup walks you through three steps:

### 1. Select AI tools

Choose which AI coding tools you use. Storm configures each one with rules, skills, and (optionally) a database-aware MCP server. You can select multiple tools if your team uses different editors.

| Tool | Rules | Skills | MCP |
|------|-------|--------|-----|
| Claude Code | CLAUDE.md | .claude/skills/ | .mcp.json |
| Cursor | .cursor/rules/storm.md | .cursor/rules/ | .cursor/mcp.json |
| GitHub Copilot | .github/copilot-instructions.md | .github/instructions/ | (tool-dependent) |
| Windsurf | .windsurf/rules/storm.md | .windsurf/rules/ | (manual config) |
| Codex | AGENTS.md | - | .codex/config.toml |

Each tool stores its configuration in a different location, but the content is the same: Storm's conventions, entity rules, query patterns, and verification guidelines.

### 2. Rules and skills

For each selected tool, Storm installs two types of AI context:

**Rules** are a project-level configuration file that is always loaded by the AI tool. They contain Storm's key patterns, naming conventions, and critical constraints (immutable QueryBuilder, no collection fields on entities, `Ref<T>` for circular references, etc.). The rules ensure the AI follows Storm's conventions in every interaction, without you having to repeat them.

**Skills** are per-topic guides that the AI loads on demand when working on a specific task. Each skill contains focused instructions, code examples, and common pitfalls for one area of Storm. Skills are fetched from orm.st during setup and can be updated automatically on each run without requiring a CLI update.

| Skill | Purpose |
|-------|---------|
| storm-docs | Load full Storm documentation |
| storm-entity-kotlin | Create Kotlin entities |
| storm-entity-java | Create Java entities |
| storm-repository-kotlin | Write Kotlin repositories |
| storm-repository-java | Write Java repositories |
| storm-query-kotlin | Kotlin QueryBuilder queries |
| storm-query-java | Java QueryBuilder queries |
| storm-sql-kotlin | Kotlin SQL Templates |
| storm-sql-java | Java SQL Templates |
| storm-json-kotlin / storm-json-java | JSON columns and JSON aggregation |
| storm-serialization-kotlin / storm-serialization-java | Entity serialization for REST APIs (Ref handling) |
| storm-migration | Write Flyway/Liquibase migration SQL |

### 3. Database connection (optional)

If you have a local development database running, Storm can set up a schema-aware MCP server. This gives your AI tool access to your actual database structure (table definitions, column types, foreign keys) without exposing credentials or data.

The MCP server runs locally on your machine, exposes only schema metadata, and stores credentials in `~/.storm/` (outside your project, outside the LLM's reach). It supports PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite, and H2.

With the database connected, three additional skills become available:

| Skill | Purpose |
|-------|---------|
| storm-schema | Inspect your live database schema |
| storm-validate | Compare entities against the live schema |
| storm-entity-from-schema | Generate, update, or refactor entities from database tables |

To reconfigure the database connection later, run `storm mcp`.

---

## Manual Setup

If you prefer to configure your AI tool manually, Storm publishes two machine-readable documentation files following the [llms.txt standard](https://llmstxt.org/):

| File | URL | Best for |
|------|-----|----------|
| `llms.txt` | [orm.st/llms.txt](https://orm.st/llms.txt) | Quick reference with essential patterns and gotchas |
| `llms-full.txt` | [orm.st/llms-full.txt](https://orm.st/llms-full.txt) | Complete documentation for tools with large context windows |

<Tabs>
<TabItem value="claude-code" label="Claude Code" default>

Use `@url` to fetch Storm context in a conversation:

```
@url https://orm.st/llms-full.txt
```

</TabItem>
<TabItem value="cursor" label="Cursor">

Add Storm documentation as a doc source in Cursor settings:

1. Open **Settings > Features > Docs**
2. Click **Add new doc**
3. Enter `https://orm.st/llms-full.txt`

</TabItem>
<TabItem value="generic" label="Other Tools">

Most AI coding tools support adding context through URLs or pasted text. Point your tool at `https://orm.st/llms-full.txt` for complete documentation.

</TabItem>
</Tabs>

---

## Why Storm Works Well With AI

AI works better when framework behavior is explicit and visible in source code.

Traditional ORMs rely on mechanisms that are powerful but implicit: proxy objects that intercept field access, lazy loading that triggers queries at unpredictable moments, persistence contexts that track entity state across transaction boundaries, and cascading rules that propagate changes through the object graph. These features serve real purposes, but they make AI-assisted development harder. The AI has to account for behavior that does not appear in the code. Code that compiles and looks correct can still break at runtime because of invisible framework state.

Storm eliminates all of that. Entities are plain Kotlin data classes or Java records. There are no proxies, no managed state, no persistence context, and no lazy loading. Queries are explicit, and what you see in the source code is exactly what happens at runtime. This makes Storm's behavior predictable for AI tools: the code is the complete picture.

The design choices that matter most:

- **Immutable entities.** No hidden state transitions for the AI to track or miss.
- **No proxies.** The entity class is the entity. No invisible bytecode transformations to account for.
- **No persistence context.** No session scope, flush ordering, or detachment rules that require deep framework knowledge.
- **Convention over configuration.** Fewer annotations and config files for the AI to keep consistent.
- **Compile-time metamodel.** Type errors caught at build time, not at runtime. The AI gets immediate feedback.
- **Secure schema access.** The MCP server gives AI tools structural database knowledge without exposing credentials or data.

Beyond the data model, Storm provides dedicated tooling for AI-assisted workflows:

- **Skills** guide AI tools through specific tasks (entity creation, queries, repositories, migrations) with framework-aware conventions and rules.
- **A locally running MCP server** gives AI tools access to your live database schema: table definitions, column types, constraints, and foreign keys. The AI can inspect your actual database structure to generate entities that match, or validate entities it just created.
- **Built-in verification** through `ORMTemplate.validateSchema()` and `SqlCapture` lets the AI validate its own work. After generating entities, the AI can validate them against the database. After writing queries, it can capture and inspect the actual SQL. Both checks run in an isolated in-memory database through `@StormTest`, so verification happens before anything touches production. For dialect-specific code, `@StormTest` supports a static `dataSource()` factory method on the test class, allowing integration with Testcontainers to test against the actual target database.

---

## Schema-First and Entity-First

Storm fully supports both directions of working: starting from the database schema and generating entities to match, or starting from the entity model and generating the migration scripts to create the schema. Both approaches share the same development cycle; they just enter it at a different point.

```
            Entity-first                          Schema-first
            starts here                           starts here
                 │                                     │
                 ▼                                     ▼
        ┌─────────────────┐                   ┌─────────────────┐
        │  Define/update  │──────────────────▶│ Generate/update │
        │    entities     │                   │    migration    │
        │                 │                   │                 │
        │   [You / AI]    │                   │   [You / AI]    │
        └─────────────────┘                   └─────────────────┘
                 ▲                                     │
                 │                                     ▼
        ┌─────────────────┐                   ┌─────────────────┐
        │    Validate     │◀──────────────────│  Apply schema   │
        │                 │                   │                 │
        │    [Storm]      │                   │  [Flyway / H2]  │
        └─────────────────┘                   └─────────────────┘
```

The AI generates and updates code (entities, migrations, queries). Storm validates correctness (`ORMTemplate.validateSchema()`, `SqlCapture`). The cycle repeats whenever either side changes: a schema change triggers entity updates; an entity change triggers a new migration. Schema validation closes the loop by proving that entities and schema agree after every change.

### Schema-first

In a schema-first workflow, the database is the source of truth. The schema already exists (or is managed by a DBA), and entities need to match it.

When the MCP server is configured, the AI has access to the live database through `list_tables` and `describe_table`. This gives it full visibility into table definitions, column types, constraints, and foreign key relationships.

The AI workflow:

1. **Inspect the schema.** The AI calls `list_tables` to discover tables, then `describe_table` for each relevant table.
2. **Generate entities.** Based on the schema metadata and Storm's entity conventions (naming, `@PK`, `@FK`, `@UK`, nullability, `Ref<T>` for circular or self-references), the AI generates Kotlin data classes or Java records.
3. **Validate.** The AI writes a temporary test that validates the generated entities against the database using `ORMTemplate.validateSchema()`.

When the database schema evolves, the same flow applies: the AI inspects the changed tables, updates the affected entities, and re-validates.

### Entity-first

In an entity-first workflow, the code is the source of truth. You design your domain model as entities, and the database schema is derived from them.

The AI workflow:

1. **Design entities.** The AI creates Kotlin data classes or Java records based on the domain model you describe.
2. **Generate migration.** The AI writes a Flyway or Liquibase migration script that creates the tables, columns, constraints, and indexes to match the entity definitions, following Storm's naming conventions.
3. **Validate.** The AI writes a temporary test that applies the migration to an H2 in-memory database and validates the entities against the resulting schema using `ORMTemplate.validateSchema()`. This confirms that the entity definitions and the migration script are consistent with each other, before anything touches the real database.

### Verification with Schema Validation

Both approaches converge on the same verification step. `ORMTemplate.validateSchema()` checks entities against the database at the JDBC level, catching mismatches that are difficult to spot by inspection: type incompatibilities, nullability disagreements, missing constraints, unmapped NOT NULL columns, and more. The AI can validate only the specific entities it created or modified:

<Tabs>
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["schema.sql"])
class EntitySchemaTest {
    @Test
    fun validateNewEntities(orm: ORMTemplate) {
        val errors = orm.validateSchema(
            Order::class,
            OrderLine::class,
            Product::class
        )
        assertTrue(errors.isEmpty()) { "Schema validation errors: $errors" }
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@StormTest(scripts = {"schema.sql"})
class EntitySchemaTest {
    @Test
    void validateNewEntities(ORMTemplate orm) {
        orm.validateSchemaOrThrow(List.of(
            Order.class,
            OrderLine.class,
            Product.class
        ));
    }
}
```

</TabItem>
</Tabs>

In the schema-first case, `schema.sql` is the existing migration or DDL. In the entity-first case, it is the migration the AI just generated. Either way, schema validation confirms that entities and schema agree.

---

## Query Verification With SqlCapture

The same pattern applies to queries. A query that compiles and runs without errors is not necessarily correct: the WHERE clause might filter on the wrong column, a JOIN might be missing, or an ORDER BY might not match the user's intent. After the AI writes a query, it can write a test that captures the actual SQL Storm generates and verifies it matches the intended behavior.

`SqlCapture` records every SQL statement, its operation type, and its bind parameters:

<Tabs>
<TabItem value="kotlin" label="Kotlin" default>

```kotlin
@StormTest(scripts = ["schema.sql", "data.sql"])
class OrderQueryTest {
    @Test
    fun findShippedOrders(orm: ORMTemplate, capture: SqlCapture) {
        val orders = capture.execute {
            orm.entity(Order::class).select()
                .where(Order_.status eq "SHIPPED")
                .orderBy(Order_.createdAt)
                .resultList
        }
        // Verify the query structure matches the intent.
        val sql = capture.statements().first().statement()
        assertContains(sql, "WHERE")
        assertContains(sql, "ORDER BY")
    }
}
```

</TabItem>
<TabItem value="java" label="Java">

```java
@StormTest(scripts = {"schema.sql", "data.sql"})
class OrderQueryTest {
    @Test
    void findShippedOrders(ORMTemplate orm, SqlCapture capture) {
        List<Order> orders = capture.execute(() ->
            orm.entity(Order.class).select()
                .where(Order_.status, EQUALS, "SHIPPED")
                .orderBy(Order_.createdAt)
                .getResultList());
        // Verify the query structure matches the intent.
        String sql = capture.statements().getFirst().statement();
        assertTrue(sql.contains("WHERE"));
        assertTrue(sql.contains("ORDER BY"));
    }
}
```

</TabItem>
</Tabs>

`SqlCapture` is injected automatically in `@StormTest` methods. The AI can verify:

- **SQL structure**: check that the expected WHERE, JOIN, GROUP BY, and ORDER BY clauses are present.
- **Query count**: `capture.count(SELECT)` confirms the expected number of statements were issued.
- **Operation types**: `capture.count(INSERT)`, `capture.count(UPDATE)`, etc. for mutation tests.
- **Bind parameters**: `capture.statements().first().parameters()` to inspect parameterized values.

If the test fails, the AI has the actual SQL in the failure output and can correct the query immediately.

---

## Temporary Self-Verification Tests

The verification tests the AI writes do not need to become part of your codebase. The AI can write a test, run it, and remove it again, all within a single conversation. This gives the AI a way to validate its own work without leaving behind test artifacts you did not ask for.

The workflow:

1. **Write.** The AI creates a test file in the project's test source directory (e.g., `src/test/kotlin/StormAIVerificationTest.kt`). For entity-first work, it may also write a temporary schema SQL file to `src/test/resources/`.
2. **Run.** The AI executes only that test using a targeted command:
   ```bash
   # Maven
   mvn test -pl your-module -Dtest=StormAIVerificationTest

   # Gradle
   ./gradlew :your-module:test --tests StormAIVerificationTest
   ```
3. **Fix (if needed).** If the test fails, the error messages tell the AI exactly what is wrong. It fixes the entities, queries, or migration and re-runs the test.
4. **Clean up.** Once the test passes, the AI deletes the temporary test file (and any temporary SQL scripts it created). The verified code stays; the scaffolding goes.

This works because `@StormTest` spins up an H2 in-memory database by default, executes the setup scripts, and tears everything down after the test. No external database, no persistent state, no side effects. When the code under test uses dialect-specific SQL, define a static `dataSource()` factory method on the test class to provide a Testcontainers-backed `DataSource` for the target database instead of H2.

You can also ask the AI to keep the test as a permanent regression test. The choice is yours, and the AI should ask.

---

## The Gold Standard: Verify, Then Trust

This is what makes Storm the gold standard for AI-assisted database development. The AI does not just generate code and hope for the best. It generates code, then validates it through Storm's own verification, before anything is committed.

| Task | AI generates | Storm verifies |
|------|-------------|-------------------|
| **Entities (schema-first)** | Data classes/records from live schema | `validateSchema()` checks types, nullability, constraints, unmapped columns |
| **Entities (entity-first)** | Data classes/records + migration script | `validateSchema()` confirms entity and migration agree |
| **Queries** | QueryBuilder or SQL Template code | `SqlCapture` verifies the generated SQL matches the intended structure and parameters |
| **Repositories** | Custom query methods | `SqlCapture` confirms each method produces the expected SQL |

Storm's immutable entities, explicit queries, and convention-based naming make AI-generated code straightforward to verify. The verify-then-trust pattern below closes the gap between "looks right" and "is right":

1. **The AI generates code** using Storm's skills, documentation, and (when configured) live schema metadata from the MCP server.
2. **The AI writes a focused test** that exercises exactly the code it just wrote, using `ORMTemplate.validateSchema()` for entities or `SqlCapture` for queries.
3. **The AI runs the test.** If it passes, the code is correct by construction, verified by the same validation logic that Storm uses internally. If it fails, the error messages tell the AI exactly what to fix.
4. **The test stays or goes.** Keep it as a regression test, or let the AI remove it once verified. Either way, the verification happened.

This is the combination that makes it work: an AI-friendly data model that produces stable code, a schema-aware MCP server that gives the AI structural knowledge, and built-in test tooling that lets the AI verify its own work through the framework rather than around it.
