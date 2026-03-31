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

Naming convention detection:
- Before generating entities, analyze the database naming pattern across tables and columns.
- If the database follows Storm's default convention (snake_case tables, snake_case columns, FK columns with _id suffix), no configuration is needed.
- If the database follows a DIFFERENT but consistent convention (e.g., UPPER_CASE, PascalCase, prefixed tables like `tbl_`, non-standard FK naming), suggest a custom `TableNameResolver`, `ColumnNameResolver`, or `ForeignKeyResolver` via `TemplateDecorator` instead of annotating every entity with `@DbTable`/`@DbColumn`.
- Resolvers are functional interfaces configured on `ORMTemplate.of()`:
  Kotlin: `dataSource.orm { decorator -> decorator.withTableNameResolver { type -> ... } }`
  Java: `ORMTemplate.of(dataSource, decorator -> decorator.withTableNameResolver(type -> ...))`
- Built-in helpers: `TableNameResolver.toUpperCase(TableNameResolver.DEFAULT)`, `ColumnNameResolver.toUpperCase(...)`, `ForeignKeyResolver.toUpperCase(...)`. Custom lambdas receive `RecordType` (class, annotations, fields) or `RecordField` (name, type, annotations) for full flexibility.
- Use `@DbTable`/`@DbColumn` only for tables/columns that deviate from the global convention. The goal is clean entities with minimal annotations.

SQL type mapping (Kotlin): INTEGER->Int, BIGINT->Long, VARCHAR/TEXT->String, BOOLEAN->Boolean, DECIMAL->BigDecimal, DATE->LocalDate, TIMESTAMP->Instant, UUID->UUID
SQL type mapping (Java): INTEGER->Integer(PK)/int, BIGINT->Long(PK)/long, VARCHAR/TEXT->String, BOOLEAN->Boolean/boolean, DECIMAL->BigDecimal, DATE->LocalDate, TIMESTAMP->Instant, UUID->UUID

**H2 NUMERIC/DECIMAL precision:** When generating migrations for `@StormTest` (H2), always specify precision and scale for NUMERIC/DECIMAL columns (e.g., `NUMERIC(4, 1)`, not `NUMERIC`). H2 defaults to scale 0, which silently truncates decimals — values like 8.7 become 9. Warn the user if the source schema uses NUMERIC without precision.

JSON column recognition: columns typed as JSONB (PostgreSQL), JSON (MySQL, MariaDB, Oracle), NVARCHAR(MAX) with JSON content (MS SQL Server), or CLOB with JSON content (H2) should be mapped with `@Json`. Ask the user what the JSON structure represents so you can choose the correct field type (e.g., `Map<String, String>`, a custom data class, or `List<T>`).

After changes: review types, rebuild for metamodel regeneration.

When appropriate, suggest a Flyway/Liquibase migration if the schema change originated from the code side rather than the database side.
