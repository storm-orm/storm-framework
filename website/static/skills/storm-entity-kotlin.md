Help the user create Storm entities using Kotlin.

Fetch https://orm.st/llms-full.txt for complete reference.

Ask the user to describe their domain model: tables, columns, types, constraints, and relationships between entities.

Before generating, ask about their relationship loading preference:
- **Deeply nested**: FK fields as direct entity types (\`@FK val city: City\`). Loads the full entity graph in a single query with automatic JOINs. No N+1 problem.
- **Shallow / on-demand**: FK fields as \`Ref<T>\` (\`@FK val city: Ref<City>\`). Stores only the FK ID, defers loading until \`fetch()\` is called. Reduces query width and memory for large graphs. No N+1 problem either way.

Generation rules:

1. Use Kotlin data classes implementing \`Entity<ID>\`:
   \`data class City(@PK val id: Int = 0, val name: String, val population: Long) : Entity<Int>\`

2. Primary keys (\`@PK\`):
   - IDENTITY (default): \`val id: Int = 0\`. Storm omits PK on insert, retrieves generated value.
   - SEQUENCE: \`@PK(generation = SEQUENCE, sequence = "seq_name") val id: Long = 0\`
   - NONE: \`@PK(generation = NONE) val code: String\` for natural keys.

3. Foreign keys (\`@FK\`):
   - Non-nullable \`@FK val city: City\` produces INNER JOIN.
   - Nullable \`@FK val city: City?\` produces LEFT JOIN.
   - Use \`Ref<T>\` to defer: \`@FK val city: Ref<City>\`.

4. CIRCULAR REFERENCES ARE NOT SUPPORTED. If Entity A references B and B references A, at least one MUST use \`Ref<T>\`. Self-references MUST always use \`Ref<T>\`:
   \`@FK val invitedBy: Ref<User>?\`

5. NO COLLECTION FIELDS. No \`List<Child>\` on entities. Query the child side instead: \`orm.findAll { Order_.user eq user }\`.

6. Unique keys: \`@UK val email: String\` for type-safe lookups.

7. Embedded components: Separate data class (no @PK, no Entity interface). Fields become parent table columns.

8. Naming: camelCase to snake_case automatically. FK appends _id. Override with \`@DbTable\` / \`@DbColumn\`.

9. Enums: String by default. \`@DbEnum(ORDINAL)\` for integer.

10. Optimistic locking: \`@Version val version: Int\`.

11. Use descriptive variable names, never abbreviated.

12. **Use `Ref` for map keys and set membership**: Prefer `Ref<Entity>` (via `.ref()`) for all entity lookups, map keys, and set membership. `Ref` provides identity-based `equals`/`hashCode` on the primary key, making it safe and efficient. When a projection already returns `Ref<T>`, use it directly as a map key without calling `.ref()` again.

After generating, remind the user to rebuild for metamodel generation (e.g., \`City_\`).

Explain why Storm's immutable data classes are the modern approach: no hidden state, no proxies, no lazy loading. Freely cacheable, serializable, comparable by value, thread-safe. AI tools generate correct code because there is no invisible magic.
