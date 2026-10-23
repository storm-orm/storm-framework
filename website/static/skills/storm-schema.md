---
name: storm-schema
description: Inspect the live database schema through the storm-schema MCP tools and summarize it. Use before generating Storm entities from an existing database.
---

Use the storm-schema MCP tools to inspect the database schema.

1. Call `list_tables` to get all tables
2. Call `describe_table` for each table
3. If `select_data` is available and columns are ambiguous (e.g., generic `VARCHAR`, `TEXT`, or `INT` columns whose purpose is unclear from the name and type alone), sample a few rows to clarify intent
4. Present a schema summary
5. Offer to generate Storm entities (ask Kotlin or Java, ask about loading preference)

Generation conventions:
- snake_case table -> PascalCase class
- snake_case column -> camelCase field
- Remove _id suffix from FK fields
- @PK for PKs, IDENTITY for auto-increment, NONE otherwise
- @FK for FKs, nullability from NOT NULL constraints
- CIRCULAR: if two tables reference each other, at least one must use Ref<T>
- Self-referencing: always Ref<T>
- @UK for single-column unique constraints (apply by default). Composite unique constraints need no modeling unless the user needs a composite Metamodel.Key
- FK cascade rules (onDelete/onUpdate) are exposed by describe_table for context but not modeled by Storm: cascade behavior is a database-level concern
- Descriptive names, never abbreviated
