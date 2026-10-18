---
name: storm-entity-from-schema
description: Generate or update Storm entities from the live database schema using the storm-schema MCP tools. Use to scaffold entities from tables or refresh them after schema changes.
---

Generate or update Storm entity classes from the database schema using MCP tools.

Determine what the user needs:
- **Generate new entities**: create entity classes for tables that have no corresponding entity yet.
- **Update existing entities**: compare existing entity definitions against the live schema and suggest changes (new columns, removed columns, type changes, FK changes).
- **Refactor entities**: restructure existing entities based on schema changes (e.g., a table was split, FKs were added/removed, columns were renamed).

Steps:
1. Call `list_tables` to get all tables.
2. Check which tables already have entity classes in the codebase.
3. For tables without entities: offer to generate new ones.
4. For tables with existing entities: call `describe_table` and compare against the entity definition. Report differences and suggest updates.
5. If `select_data` is available: sample a few rows from ambiguous columns to inform type decisions. For example, a `VARCHAR` column might contain enum-like values (suggest an enum), a `TEXT` column might store JSON (suggest `@Json`), or an `INT` column might be a type discriminator. Only query when the schema alone leaves the type decision ambiguous — do not sample every table.
6. Ask: Kotlin or Java?
7. Decide direct type vs `Ref<T>` for every FK using the algorithm below. Do NOT ask the user for a loading preference: the rule is deterministic, so the same schema always produces the same entities.

Foreign key modeling (direct type vs `Ref<T>`):

Joins on primary keys are cheap, so the default is a direct type and the algorithm below is a guard rail, not an optimization pass. It is calibrated so ordinary schemas trip nothing and every FK comes out as a direct type. It engages only where a graph would otherwise run away.

Hold this invariant for EVERY table, treated as a potential root:
- its inline closure adds at most **10 joins**, and
- its inline closure adds at most **96 columns**, excluding the table's own columns.

The *inline closure* of a table is the set of tables reachable from it through direct-type FKs. With `columns(T)` as the table's own column count (counting inline record components, which live in the same table and cost no join), `cost(T) = columns(T) + Σ cost(X)` over every inlined edge from T, and `joins(T)` is the number of joins that closure produces.

Algorithm:
1. **Break cycles.** Cut one edge per cycle; self-references are always cut. Visit tables in alphabetical order so the choice is reproducible. (This subsumes the CIRCULAR rule below.)
2. **Topologically order** the remaining graph, leaves first.
3. **Rank each table's outgoing FKs** into four tiers, tie-breaking on FK column name:
   1. Identifying FKs, where the column participates in the target's PK.
   2. Non-null FKs to targets that have no FKs of their own and are at most 32 columns wide.
   3. Remaining non-null FKs, narrowest target first.
   4. Nullable FKs, narrowest target first.
4. **Inline down that ranking while the budget holds.** The first edge that would exceed it becomes `Ref<T>`, and so does every edge below it.

Nothing is exempt from the budget, including tiers 1 and 2. The ranking gives those edges first claim, which in any realistic schema is enough for them to always survive.

Work bottom-up, never top-down. A table has exactly one class, so a top-down walk makes roots disagree about the same edge. Bottom-up also means a new FK on a leaf gets cut at the leaf, leaving the entities above it unchanged.

**Existing entities: report, never flip.** When a direct type already in the codebase exceeds the budget, report the table and the overrun and leave the code as written. Divergence is assumed deliberate. The budget describes what generation produces from a schema, not what a model is permitted to be.

**Confirm with Storm's design advice.** In development and under `@StormTest`, Storm runs the same budget check when the application's first template is created: each entity past it is logged once at `INFO` as `SQL advice (design ...)` under `st.orm.sql.advice.entity-budget`, with the field a `Ref<T>` would cut. After generating, run a test or start the application in development and confirm no such line names a generated entity. A line for an existing entity is the report above; leave the code as written and tell the user.

Generation/update rules:
- snake_case table -> PascalCase class, snake_case column -> camelCase field
- Remove _id from FK fields (city_id -> city)
- **Every column with a FK constraint must be modeled with `@FK`.** Without `@FK`, Storm cannot resolve joins automatically. Whether the field is a full entity type (`@FK val city: City` / `@FK City city`) or a `Ref<T>` is decided by the foreign key modeling algorithm above, not by judgement.
- **FK columns in primary keys:** When a PK column is also a FK (both PK and FK constraint on the same column), use raw IDs in the PK class and place `@FK @Persist(insertable = false, updatable = false)` fields on the entity for join metadata. Add a convenience constructor that accepts the FK entities/refs and constructs the PK internally.
- Auto-increment PKs: IDENTITY. Others: NONE.
- NOT NULL FKs: non-nullable. Nullable FKs: nullable. In Kotlin the type expresses this (`City` / `City?`); in Java components are non-null by default, so mark nullable columns `@Nullable` (JSpecify or jakarta) and leave NOT NULL columns unannotated.
- CIRCULAR NOT SUPPORTED. Two tables referencing each other: one must use Ref<T>. Self-ref: always Ref<T>.
- **Single-column unique constraints** (apply by default): use `@UK` on the field. Generates a `Metamodel.Key` for type-safe lookups and scrolling. Always add `@UK` when `describe_table` shows a single-column unique constraint — it's one annotation for free value.
- **Composite unique constraints** (only when needed): composite unique constraints do NOT need to be modeled unless the user explicitly needs a composite `Metamodel.Key` (for keyset pagination or type-safe lookups). When needed, use an inline record + `@UK @Persist(insertable = false, updatable = false)` — ask the user if they need this.
- `describe_table` reports unique constraints (top-level `uniqueConstraints` array with constraint name and column list) and FK cascade rules (onDelete/onUpdate). Both are exposed for context only. **Storm does not model cascade behavior or enforce uniqueness** — these are database-level concerns. Do not generate annotations or code for cascade rules.
- @Version for version columns (confirm with user).
- When updating, preserve existing field order and custom annotations. Only add/modify what changed.
- Use `@DbIgnore` on fields or types that should be excluded from schema validation. Note: ad-hoc query result types (aggregation DTOs) should not need this — write them as plain data classes/records without the `Data` marker instead of declaring `Data` and then suppressing validation with `@DbIgnore`.
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

**H2 NUMERIC/DECIMAL precision:** When generating migrations for `@StormTest` (H2), always specify precision and scale for NUMERIC/DECIMAL columns (e.g., `NUMERIC(4, 1)`, not `NUMERIC`). H2 defaults to scale 0, which silently truncates decimals — values like 8.7 become 9. Warn the user if the source schema uses NUMERIC without precision. Alternatively, verify against the source database itself: `@StormTest(database = POSTGRESQL, ...)` (or `MYSQL`, `MARIADB`, `MSSQL_SERVER`, `ORACLE`) runs the test in a Testcontainers-managed container of that database, so its DDL runs as written (see /storm-setup).

JSON column recognition: columns typed as JSONB (PostgreSQL), JSON (MySQL, MariaDB, Oracle), NVARCHAR(MAX) with JSON content (MS SQL Server), or CLOB with JSON content (H2) should be mapped with `@Json`. Ask the user what the JSON structure represents so you can choose the correct field type (e.g., `Map<String, String>`, a custom data class, or `List<T>`).

After changes: review types, rebuild for metamodel regeneration.

When appropriate, suggest a Flyway/Liquibase migration if the schema change originated from the code side rather than the database side.
