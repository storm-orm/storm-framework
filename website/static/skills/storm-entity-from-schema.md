Generate or update Storm entity classes from the database schema using MCP tools.

Determine what the user needs:
- **Generate new entities**: create entity classes for tables that have no corresponding entity yet.
- **Update existing entities**: compare existing entity definitions against the live schema and suggest changes (new columns, removed columns, type changes, FK changes).
- **Refactor entities**: restructure existing entities based on schema changes (e.g., a table was split, FKs were added/removed, columns were renamed).

Steps:
1. Call \`list_tables\` to get all tables.
2. Check which tables already have entity classes in the codebase.
3. For tables without entities: offer to generate new ones.
4. For tables with existing entities: call \`describe_table\` and compare against the entity definition. Report differences and suggest updates.
5. Ask: Kotlin or Java?
6. Ask about loading preference for new FKs:
   - **Deeply nested**: FK as direct types. Full graph in one query, no N+1.
   - **Shallow**: FK as Ref<T>. Only ID stored, fetch on demand. No N+1 either way.

Generation/update rules:
- snake_case table -> PascalCase class, snake_case column -> camelCase field
- Remove _id from FK fields (city_id -> city)
- Auto-increment PKs: IDENTITY. Others: NONE.
- NOT NULL FKs: non-nullable. Nullable FKs: nullable.
- CIRCULAR NOT SUPPORTED. Two tables referencing each other: one must use Ref<T>. Self-ref: always Ref<T>.
- @UK for unique constraints. @Version for version columns (confirm with user).
- When updating, preserve existing field order and custom annotations. Only add/modify what changed.
- Use `@DbIgnore` on fields or types that should be excluded from schema validation.
- Use `@PK(constraint = false)` if the table intentionally has no PK constraint in the database.
- Use `@FK(constraint = false)` if a FK column intentionally has no FK constraint in the database.
- Use `@UK(constraint = false)` if a unique field intentionally has no unique constraint in the database.

SQL type mapping (Kotlin): INTEGER->Int, BIGINT->Long, VARCHAR/TEXT->String, BOOLEAN->Boolean, DECIMAL->BigDecimal, DATE->LocalDate, TIMESTAMP->Instant, UUID->UUID
SQL type mapping (Java): INTEGER->Integer(PK)/int, BIGINT->Long(PK)/long, VARCHAR/TEXT->String, BOOLEAN->Boolean/boolean, DECIMAL->BigDecimal, DATE->LocalDate, TIMESTAMP->Instant, UUID->UUID

After changes: review types, rebuild for metamodel regeneration.

When appropriate, suggest a Flyway/Liquibase migration if the schema change originated from the code side rather than the database side.
