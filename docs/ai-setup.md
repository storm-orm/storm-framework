# AI-Assisted Development

import Tabs from '@theme/Tabs';
import TabItem from '@theme/TabItem';

Storm is an AI-first ORM. It works perfectly standalone, but its design and tooling make it uniquely suited for AI-assisted development. Immutable entities produce stable, predictable code. Skills guide AI tools through entity creation, queries, repositories, and migrations. A locally running MCP server exposes only schema metadata (table definitions, column types, constraints) while shielding your database credentials and data from the LLM.

Traditional ORMs carry hidden complexity (proxies, lazy loading, persistence contexts, cascading rules) that AI tools struggle to reason about. Storm eliminates all of that: entities are plain data classes, queries are explicit SQL, and what you see in the source code is exactly what happens at runtime.

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

Choose which AI coding tools to configure. Storm supports:

| Tool | Rules | Skills | MCP |
|------|-------|--------|-----|
| Claude Code | CLAUDE.md (optional) | .claude/skills/ | .mcp.json |
| Cursor | - | .cursor/rules/ | .cursor/mcp.json |
| GitHub Copilot | .github/copilot-instructions.md | .github/instructions/ | (tool-dependent) |
| Windsurf | - | .windsurf/rules/ | (manual config) |
| Codex | AGENTS.md | - | (experimental) |

The MCP server configuration file location depends on the AI tool.

### 2. Rules and skills

For each selected tool, Storm installs:

- **Rules**: A project-level configuration file with Storm's key patterns and conventions.
- **Skills**: Per-topic guides fetched from orm.st that help the AI tool with specific tasks. Skills can be updated automatically on each run without requiring a CLI update.

Available skills:

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
| storm-migration | Write Flyway/Liquibase migration SQL |

### 3. Database connection (optional)

If you have a local development database running, Storm can set up a schema-aware MCP server. This gives your AI tool access to your actual database structure (table definitions, column types, foreign keys) without exposing credentials or data.

The MCP server:
- Runs locally on your machine
- Exposes only schema metadata, never actual data
- Stores credentials in `~/.storm/` (outside your project, outside the LLM's reach)
- Supports PostgreSQL, MySQL, MariaDB, Oracle, SQL Server, SQLite, and H2

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

Storm's design principles align naturally with how AI coding tools operate:

| Design Choice | Why it helps AI |
|---------------|-----------------|
| **Immutable entities** | No hidden state transitions for the AI to track or miss |
| **Explicit SQL** | The generated SQL is visible and predictable; the AI can reason about queries directly |
| **No proxies** | The entity class *is* the entity; no invisible bytecode transformations to account for |
| **No persistence context** | No session scope, flush ordering, or detachment rules that require deep framework knowledge |
| **Convention over configuration** | Fewer annotations and config files for the AI to keep consistent |
| **Compile-time metamodel** | Type errors caught at build time, not at runtime; the AI gets immediate feedback |
| **Secure schema access** | The MCP server gives AI tools structural database knowledge without exposing credentials or data |

When you ask an AI tool to write Storm code, it produces the same straightforward data classes and explicit queries that a human developer would write. There is no framework magic to get wrong.
