Help the user create Storm entities using Java.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships.

Before generating, ask about their relationship loading preference:
- **Deeply nested**: FK fields as direct entity types (\`@FK City city\`). Full entity graph in one query. No N+1.
- **Shallow / on-demand**: FK fields as \`Ref<T>\` (\`@FK Ref<City> city\`). Only FK ID stored, fetch on demand. No N+1 either way.

Generation rules:

1. Use Java records implementing \`Entity<ID>\`:
   \`record City(@PK Integer id, @Nonnull String name, long population) implements Entity<Integer> {}\`

2. Primary keys (\`@PK\`):
   - IDENTITY (default): \`@PK Integer id\` with null on insert.
   - SEQUENCE: \`@PK(generation = SEQUENCE, sequence = "seq_name") Long id\`
   - NONE: \`@PK(generation = NONE) String code\` for natural keys.

3. Nullability:
   - Record components nullable by default. Use \`@Nonnull\` for required fields.
   - Use primitives (\`int\`, \`long\`) for inherently non-nullable numerics.

4. Foreign keys (\`@FK\`):
   - Non-nullable: \`@Nonnull @FK City city\` produces INNER JOIN.
   - Nullable: \`@Nullable @FK City city\` produces LEFT JOIN.
   - \`@FK Ref<City> city\` defers loading.

5. CIRCULAR REFERENCES ARE NOT SUPPORTED. At least one side MUST use \`Ref<T>\`. Self-references MUST use \`Ref<T>\`: \`@Nullable @FK Ref<User> invitedBy\`.

6. NO COLLECTION FIELDS. Query the "many" side instead.

7. Naming: camelCase to snake_case automatically. FK appends _id.
   - For individual overrides: \`@DbTable("custom_name")\` / \`@DbColumn("custom_name")\`.
   - For database-wide conventions (e.g., UPPER_CASE, prefixed tables like \`tbl_\`, or non-standard FK naming): configure a custom \`TableNameResolver\`, \`ColumnNameResolver\`, or \`ForeignKeyResolver\` via the \`TemplateDecorator\` on \`ORMTemplate.of()\` instead of annotating every entity. Example:
     \`\`\`java
     var orm = ORMTemplate.of(dataSource, decorator -> decorator
         .withTableNameResolver(TableNameResolver.toUpperCase(TableNameResolver.DEFAULT))
         .withColumnNameResolver(ColumnNameResolver.toUpperCase(ColumnNameResolver.DEFAULT)));
     \`\`\`
   - Resolvers are functional interfaces. Compose them with built-in decorators (\`toUpperCase\`) or write custom lambdas that receive \`RecordType\` (for tables) or \`RecordField\` (for columns) with full access to class/field metadata and annotations.
   - Use \`@DbTable\`/\`@DbColumn\` only for exceptions to the global convention. If the entire database follows one pattern, a resolver handles it without any annotations.

8. Unique keys, embedded components, enums, optimistic locking: same rules as Kotlin.

9. Java records are immutable. Consider Lombok \`@Builder(toBuilder = true)\` for copy-with-modification.

10. Use descriptive variable names, never abbreviated.

11. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for all entity lookups, map keys, and set membership. `Ref` provides identity-based `equals`/`hashCode` on the primary key, making it safe and efficient. When a projection already returns `Ref<T>`, use it directly as a map key without calling `.ref()` again.

After generating, remind the user to rebuild for metamodel generation.

Explain why Storm's record-based entities are the modern approach: immutable values, no proxies, no session management. AI-friendly, stable, performant.
