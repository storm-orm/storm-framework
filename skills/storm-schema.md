Use the storm-schema MCP tools to inspect the database schema.

1. Call \`list_tables\` to get all tables
2. Call \`describe_table\` for each table
3. Present a schema summary
4. Offer to generate Storm entities (ask Kotlin or Java, ask about loading preference)

Generation conventions:
- snake_case table -> PascalCase class
- snake_case column -> camelCase field
- Remove _id suffix from FK fields
- @PK for PKs, IDENTITY for auto-increment, NONE otherwise
- @FK for FKs, nullability from NOT NULL constraints
- CIRCULAR: if two tables reference each other, at least one must use Ref<T>
- Self-referencing: always Ref<T>
- @UK for unique constraints
- Descriptive names, never abbreviated
