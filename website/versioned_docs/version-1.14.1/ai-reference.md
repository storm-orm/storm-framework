# AI Tools Reference

This page lists the configuration locations, skills, and database skills that Storm installs for each AI coding tool. For the main guide, see [AI-Assisted Development](ai.md).

---

## Tool Configuration Locations

Each AI tool stores its configuration in a different location, but the content is the same: Storm's conventions, entity rules, query patterns, and verification guidelines.

| Tool | Rules | Skills | MCP |
|------|-------|--------|-----|
| Claude Code | CLAUDE.md | .claude/skills/ | .mcp.json |
| Cursor | .cursor/rules/storm.md | .cursor/rules/ | .cursor/mcp.json |
| GitHub Copilot | .github/copilot-instructions.md | .github/instructions/ | (tool-dependent) |
| Windsurf | .windsurf/rules/storm.md | .windsurf/rules/ | (manual config) |
| Codex | AGENTS.md | - | .codex/config.toml |

---

## Skills

Skills are per-topic guides that the AI loads on demand when working on a specific task. Each skill contains focused instructions, code examples, and common pitfalls for one area of Storm. Skills are fetched from orm.st during setup and can be updated automatically on each run without requiring a CLI update.

| Skill | Purpose |
|-------|---------|
| storm-setup | Configure dependencies (detects Spring Boot, Ktor, or standalone) |
| storm-docs | Load full Storm documentation |
| storm-entity-kotlin | Create Kotlin entities |
| storm-entity-java | Create Java entities |
| storm-repository-kotlin | Write Kotlin repositories (framework-aware: Spring Boot, Ktor, standalone) |
| storm-repository-java | Write Java repositories |
| storm-query-kotlin | Kotlin QueryBuilder queries |
| storm-query-java | Java QueryBuilder queries |
| storm-sql-kotlin | Kotlin SQL Templates |
| storm-sql-java | Java SQL Templates |
| storm-json-kotlin / storm-json-java | JSON columns and JSON aggregation |
| storm-serialization-kotlin / storm-serialization-java | Entity serialization for REST APIs (framework-aware content negotiation) |
| storm-migration | Write Flyway/Liquibase migration SQL |

---

## Database Skills

With the [MCP server](database-and-mcp.md) configured, three additional skills become available:

| Skill | Purpose |
|-------|---------|
| storm-schema | Inspect your live database schema |
| storm-validate | Compare entities against the live schema |
| storm-entity-from-schema | Generate, update, or refactor entities from database tables |
